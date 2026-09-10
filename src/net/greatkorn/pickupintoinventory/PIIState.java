package net.greatkorn.pickupintoinventory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player has asked for, held and persisted by whichever side is the logical server.
 *
 * Three answers, not two. "Follow the server" has to be an answer of its own rather than the
 * absence of one, because the absence is what lets a client's local setting fill the gap the first
 * time that player connects. Collapse the two and /pickupinv server is silently replaced by an
 * explicit override on the very next login, which is the same reason a plain boolean could not
 * survive a reconnect: the login preference had nothing to distinguish "they chose this" from
 * "they never said".
 *
 * What the choice works out to once the server default and the lock are applied is PIIPolicy's
 * question, not this class's. Here there is only the choice, as the player left it.
 *
 * <h3>Where the changes come from, and where the writing happens</h3>
 *
 * Every change arrives on the main server thread. FMLProxyPacket does not override
 * Packet.hasPriority, so NetworkManager queues a preference message and processReceivedPackets
 * drains it out of the tick loop, and /pickupinv runs there too. Writing the file where the change
 * is made therefore puts a Properties.store, an fsync and a rename inside a game tick, for every
 * message - including one asking for the setting the player is already on, which a client is free
 * to repeat as fast as it likes.
 *
 * So nothing writes on that thread. A request that changes nothing is not a change at all and is
 * dropped where it is made; a real one bumps a counter and wakes a single background daemon, which
 * takes a snapshot of the map, writes it, and then pauses before looking again - so a burst of
 * changes, from one player or from many, costs one write rather than one write each, and no number
 * of them can hold a tick up. The map stays concurrent because that daemon reads it while the
 * server thread writes it.
 *
 * The two things that could be lost that way are given back. The file is replaced atomically, so a
 * write that dies part way through leaves the previous one whole rather than a truncated stub, and
 * the world's stop flushes synchronously, so the last /pickupinv before a shutdown is on disk
 * before the server is gone.
 */
public final class PIIState {

    /** A player's own answer. Absence from the map means they have never given one. */
    public enum Choice {

        ON,
        OFF,
        SERVER;

        /** How it is written to disk. true/false are exactly what 1.3.0 wrote, so its files load. */
        String token() {
            return this == ON ? "true" : this == OFF ? "false" : "server";
        }

        static Choice parse(String text) {
            if ("server".equalsIgnoreCase(text)) return SERVER;
            // Boolean.valueOf is what 1.3.0 parsed with, down to reading anything it does not
            // recognise as false; keeping that means an existing file is read back unchanged.
            return Boolean.valueOf(text) ? ON : OFF;
        }
    }

    private static final String HEADER = "Per-player choices for Pickup Into Inventory, by player"
        + " UUID. true = on, false = off, server = follow the server default.";

    /**
     * How long the writer waits after finishing one write before it will start another.
     *
     * Skipping requests that change nothing already removes the case a client can drive for free,
     * but a client alternating on and off is asking for a real change every time, and without a
     * pause between writes it would keep the disk busy for as long as it cared to. A second is far
     * below anything a player would notice and far above the rate anything legitimate changes at.
     */
    private static final long WRITE_INTERVAL_MS = 1000L;

    private static final Map<UUID, Choice> OVERRIDES = new ConcurrentHashMap<UUID, Choice>();

    /**
     * Held for a whole write, temp file and rename together, so two of them cannot interleave and
     * an older snapshot cannot land on top of a newer one. Nothing the server thread does takes it.
     */
    private static final Object SAVE_LOCK = new Object();

    /**
     * Guards the two counters and the writer's lifecycle, and is never held across the file I/O -
     * that is the whole point of it: a change being recorded must never queue behind a disk write.
     */
    private static final Object PENDING_LOCK = new Object();

    /** Bumped by every request that actually changed something. Compared, never used as a size. */
    private static long changed;

    /** The counter value the file on disk is known to hold. Equal to 'changed' means nothing due. */
    private static long stored;

    private static Thread writer;

    /** Read by the writer thread, so it cannot be an ordinary field. */
    private static volatile File file;

    private PIIState() {}

    /** null = this player has never chosen, which is not the same as choosing to follow. */
    public static Choice get(UUID id) {
        return OVERRIDES.get(id);
    }

    public static void set(UUID id, Choice value) {
        final Choice previous = value == null ? OVERRIDES.remove(id) : OVERRIDES.put(id, value);
        // These are enum constants, so identity is equality and it covers the never-chosen case as
        // null == null. A player asking for what they already have is the one message a client can
        // send over and over at no cost to itself, and answering it with a full rewrite of the file
        // was the entire cost: there is nothing to persist, so there is nothing to do.
        if (previous == value) return;
        synchronized (PENDING_LOCK) {
            changed++;
            startWriter();
            PENDING_LOCK.notifyAll();
        }
    }

    public static void init(File f) {
        file = f;
        OVERRIDES.clear();
        if (!f.isFile()) return;
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
            for (String key : p.stringPropertyNames()) {
                try {
                    OVERRIDES.put(UUID.fromString(key), Choice.parse(p.getProperty(key)));
                } catch (IllegalArgumentException bad) {
                    // not a UUID - drop the line rather than fail the whole file
                }
            }
        } catch (IOException e) {
            System.err.println("[PickupIntoInventory] could not read " + f + ": " + e);
        } finally {
            close(in);
        }
    }

    /**
     * Puts everything asked for so far on disk before returning, on the caller's own thread rather
     * than by waiting for the writer to get to it. Called as the world stops, where blocking is
     * exactly what is wanted: the writer is a daemon and the process may be seconds from ending, so
     * a change still sitting in memory here is a change the player never made. It can only wait on
     * one write already in flight, and it does nothing at all when the file already matches.
     */
    public static void flush() {
        writePending();
    }

    /** Started on the first real change rather than at load, so a client that never touches its
     *  setting - and a dedicated server nobody plays on - never pays for a thread. */
    private static void startWriter() { // caller holds PENDING_LOCK
        if (writer != null) return;
        writer = new Thread(new Runnable() {

            @Override
            public void run() {
                writeLoop();
            }
        }, "PickupIntoInventory preference writer");
        // A daemon, because nothing here is worth holding a JVM open for; what makes that safe is
        // the flush on the way out, not the thread outliving the shutdown.
        writer.setDaemon(true);
        writer.start();
    }

    private static void writeLoop() {
        try {
            while (true) {
                synchronized (PENDING_LOCK) {
                    while (stored == changed) PENDING_LOCK.wait();
                }
                writePending();
                // Not a delay before writing, which would widen the window a crash can lose - the
                // change just made goes out immediately. This only paces what comes after it, so
                // everything that arrives during the pause is merged into the next snapshot.
                Thread.sleep(WRITE_INTERVAL_MS);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } finally {
            // Nothing interrupts this thread today, but leaving the field pointing at a dead one
            // would quietly turn every later change into a change that is never written, which is
            // worse than the bug this class is fixing. Dropping it lets the next change start
            // another, and flush() would still write on its own thread regardless.
            synchronized (PENDING_LOCK) {
                writer = null;
            }
        }
    }

    /**
     * Writes whatever is not on disk yet, or returns immediately if everything is. Safe from any
     * thread and safe to call when there is nothing to do, which is what lets the shutdown path and
     * the writer thread share one implementation instead of racing two.
     */
    private static void writePending() {
        synchronized (SAVE_LOCK) {
            final File target = file;
            final long generation;
            synchronized (PENDING_LOCK) {
                if (stored == changed) return;
                generation = changed;
            }
            if (target != null) {
                // Copied after the counter was read and outside that lock, so a change made on the
                // server thread waits for nothing here. set() puts its value in the map before
                // bumping the counter, so everything counted at or below 'generation' is certainly
                // in this copy; what the order allows is the opposite, a change made after the read
                // being copied too, and that costs one redundant write and nothing else. Reading
                // the counter after the copy instead would be the arrangement that loses changes.
                final Properties snapshot = new Properties();
                for (Map.Entry<UUID, Choice> e : OVERRIDES.entrySet()) {
                    snapshot.setProperty(e.getKey().toString(), e.getValue().token());
                }
                write(target, snapshot);
            }
            synchronized (PENDING_LOCK) {
                // Recorded even when the write failed, and even when there was no file to write to.
                // The alternative is a writer thread that finds work outstanding every time it
                // looks and re-attempts a write the disk has already refused, once a second,
                // forever. The error is reported; the next real change is what retries it.
                if (stored < generation) stored = generation;
                PENDING_LOCK.notifyAll();
            }
        }
    }

    /**
     * Never opens the real file. 1.3.0 wrote straight into it with a FileOutputStream, which
     * truncates on open, so the moment between that and a completed store was one in which every
     * player's saved choice had already been destroyed and the replacement did not exist yet -
     * anything that stopped the process there, a crash, a kill, a full disk, left an empty file
     * behind and put everybody back on the server default at the next start.
     */
    private static void write(File target, Properties contents) {
        // Beside the real file rather than in a temp directory, so the rename below is within one
        // filesystem and cannot degrade into a copy.
        final File temporary =
            new File(target.getAbsoluteFile().getParentFile(), target.getName() + ".new");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(temporary);
            contents.store(out, HEADER);
            out.flush();
            // The rename only orders itself against data the filesystem has actually taken, and a
            // store that has only reached the page cache is not that. Without this a crash just
            // after the rename can leave the new name pointing at a block of nothing, which is the
            // same total loss as before, moved one step later.
            out.getFD().sync();
            out.close();
            out = null;
            replace(temporary, target);
        } catch (IOException e) {
            System.err.println("[PickupIntoInventory] could not write " + target + ": " + e);
        } finally {
            close(out);
            // On the way out through the failure path this is a half-written file nobody should
            // read; on the way out through the success path it no longer exists, because the rename
            // is what took it away. Either way the real file is the last one written whole.
            if (temporary.isFile()) temporary.delete();
        }
    }

    /**
     * ATOMIC_MOVE is asked for on its own: it replaces an existing target on both of the platforms
     * this runs on - rename(2) on POSIX, MoveFileEx with MOVEFILE_REPLACE_EXISTING on Windows - so
     * REPLACE_EXISTING beside it would say nothing that is not already said and is ignored anyway.
     * The fallback is for the filesystems that refuse the atomic path outright, some network and
     * FUSE mounts among them, where a replace that is merely quick still beats a file that spends
     * the length of a write not existing.
     */
    private static void replace(File temporary, File target) throws IOException {
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {}
    }
}
