package net.greatkorn.pickupintoinventory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.Level;

import cpw.mods.fml.common.FMLLog;

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
 * The two things that could be lost that way are given back, as far as they can be. The file is
 * replaced atomically, so a write that dies part way through leaves the previous one whole rather
 * than a truncated stub, and the world's stop flushes synchronously rather than hoping the daemon
 * gets there, so the last /pickupinv before a shutdown is normally on disk before the server is
 * gone.
 *
 * <h3>What is still lost, and what is not</h3>
 *
 * Moving the write off the tick buys the tick back at the price of a window. A change made in the
 * second before the machine loses power, or before the JVM is killed outright, is not on disk and
 * is gone; a change made before an orderly stop is attempted there, because the stop does the
 * write itself instead of waiting on the daemon. That is the trade, and it is worth stating
 * plainly rather than as "a moment later": the exposure is up to WRITE_INTERVAL_MS plus one write,
 * not an instant.
 *
 * Attempted, not guaranteed. flush() is one more write and can fail like any other - a full disk,
 * a file another process holds - and it will not wait longer than FLUSH_WAIT_MS for a write
 * already in flight, so a stop over a hung mount returns having written nothing. Both cases are
 * logged at ERROR and neither holds the shutdown open; what the stop removes is the ordinary
 * window, not every way a write can fail.
 *
 * A write that <em>fails</em> is not in that category and must not be treated as one. Recording a
 * failed write as done loses the change permanently - including at the shutdown flush, which would
 * find nothing outstanding and return - while the player has already been told it took. So failure
 * is remembered separately from success: the generation that failed is parked so the writer does
 * not spin on it once a second forever, and the next real change, or the shutdown, tries again.
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

        static Choice parse(String key, String text) {
            if ("server".equalsIgnoreCase(text)) return SERVER;
            if ("true".equalsIgnoreCase(text)) return ON;
            if (!"false".equalsIgnoreCase(text)) {
                // Boolean.valueOf is what 1.3.0 parsed with, down to reading anything it does not
                // recognise as false, and that behaviour is kept so an existing file is read back
                // unchanged. What is not kept is the silence: a line torn by the pre-atomic writer
                // this PR replaced reads as "off" and looks exactly like a deliberate choice, and
                // a player whose setting quietly inverted has nothing to point at.
                FMLLog.log(
                    "PickupIntoInventory",
                    Level.WARN,
                    "unrecognised choice %s for %s, reading as off",
                    text,
                    key);
            }
            return OFF;
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
     * How long the shutdown flush will wait for a write already in flight before giving up on it.
     *
     * A write to a hung mount - NFS with a dead server, a stalled FUSE filesystem - does not fail,
     * it blocks, and an uninterruptible one blocks for as long as the mount takes to notice. The
     * flush is called from the world's stop, so waiting on that indefinitely turns a saved change
     * into a world that will not shut down, which is the worse of the two.
     */
    private static final long FLUSH_WAIT_MS = 5000L;

    /**
     * How many times, and how far apart, a rename is retried before the write counts as failed.
     *
     * On Windows a file this process does not have open can still be held by something else -
     * Defender mid-scan, OneDrive, a backup agent - and MoveFileEx answers ACCESS_DENIED, which
     * arrives here as AccessDeniedException rather than AtomicMoveNotSupportedException, so the
     * non-atomic fallback is no help at all. Those holders let go in milliseconds.
     */
    private static final int REPLACE_ATTEMPTS = 3;

    private static final long REPLACE_BACKOFF_MS = 50L;

    /**
     * Held for a whole write, temp file and rename together, so two of them cannot interleave and
     * an older snapshot cannot land on top of a newer one. Nothing the server thread does takes it.
     *
     * A lock rather than a monitor only so the shutdown flush can put a bound on how long it waits
     * for it; nothing here is reentrant.
     */
    private static final ReentrantLock SAVE_LOCK = new ReentrantLock();

    /**
     * Guards the two counters and the writer's lifecycle, and is never held across the file I/O -
     * that is the whole point of it: a change being recorded must never queue behind a disk write.
     */
    private static final Object PENDING_LOCK = new Object();

    /** Bumped by every request that actually changed something. Compared, never used as a size. */
    private static long changed;

    /** The counter value the file on disk is known to hold. Equal to 'changed' means nothing due. */
    private static long stored;

    /**
     * The generation a write failed on, or 0.
     *
     * This is what "do not spin" and "give up" used to be conflated into. The writer must not
     * retry a write the disk has already refused once a second forever, but the change is still
     * not on disk, and recording it as stored is a silent, permanent loss of something the player
     * was told had taken. Parking the generation here stops the loop and leaves the work
     * outstanding: the next real change moves 'changed' past it and the loop runs again, and the
     * shutdown flush clears it and tries once more regardless.
     */
    private static long failedAt;

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
        synchronized (PENDING_LOCK) {
            // The map now says exactly what this file says, so nothing is outstanding against it
            // and a parked failure against the previous one means nothing here. No writer is
            // started: there is nothing for it to do, and one is started by the first real change.
            // This runs from preInit, before any change and before any writer exists; it is not a
            // reload and must not be called while the server is running.
            stored = changed;
            failedAt = 0;
        }
        if (!f.isFile()) return;
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
            for (String key : p.stringPropertyNames()) {
                try {
                    OVERRIDES.put(UUID.fromString(key), Choice.parse(key, p.getProperty(key)));
                } catch (IllegalArgumentException bad) {
                    // not a UUID - drop the line rather than fail the whole file
                }
            }
            // Properties.load throws IllegalArgumentException - a RuntimeException, not an
            // IOException - on a malformed \\uxxxx escape, and this runs from preInit, so one
            // corrupt line in a file nothing but this class writes would take FML down with it.
            // Losing everyone's saved choice is bad; refusing to start the game is worse.
        } catch (Exception e) {
            FMLLog.log("PickupIntoInventory", Level.ERROR, e, "could not read %s", f);
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
        synchronized (PENDING_LOCK) {
            // A write that failed earlier is exactly the one most worth another go here: the
            // change is still only in memory, and after this there is no later attempt to have.
            failedAt = 0;
        }
        writePending(FLUSH_WAIT_MS);
    }

    /** Started on the first real change rather than at load, so a client that never touches its
     *  setting - and a dedicated server nobody plays on - never pays for a thread. */
    private static void startWriter() { // caller holds PENDING_LOCK
        // isAlive as well as null, to keep two writers from running at once. It does not settle
        // the dying-thread case on its own: a thread inside its own finally is still alive, and
        // that finally wants PENDING_LOCK, so a change holding the lock here sees a live thread
        // and declines. What closes it is the other end - writeLoop restarts from the finally if
        // it is leaving work behind.
        if (writer != null && writer.isAlive()) return;
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
                    // changed == failedAt is the "already refused, nothing new since" state. It is
                    // not the same as stored == changed: the work is still outstanding, it is just
                    // not worth reattempting until something moves. A new change moves 'changed'
                    // past failedAt and this wakes; so does flush(), which clears it.
                    while (stored == changed || changed == failedAt) PENDING_LOCK.wait();
                }
                writePending(0L);
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
                // Only if this thread is still the one on record. startWriter runs under the same
                // lock and may already have installed a replacement, and clearing the field then
                // would strand it.
                if (writer == Thread.currentThread()) {
                    writer = null;
                    // A change made between the loop unwinding and this block saw a thread that
                    // was alive and declined to start a replacement; it is not going to look
                    // again. So the departing thread starts the replacement itself, and only when
                    // there is something for it to do - the same condition the loop waits on.
                    if (stored != changed && changed != failedAt) startWriter();
                }
            }
        }
    }

    /**
     * Writes whatever is not on disk yet, or returns immediately if everything is. Safe from any
     * thread and safe to call when there is nothing to do, which is what lets the shutdown path and
     * the writer thread share one implementation instead of racing two.
     */
    private static void writePending(long waitMs) {
        if (waitMs <= 0) {
            SAVE_LOCK.lock();
        } else {
            boolean taken;
            try {
                taken = SAVE_LOCK.tryLock(waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                taken = false;
            }
            if (!taken) {
                // Someone else's write is stuck, not ours to wait out. Say so rather than hold the
                // shutdown open on a mount that may never answer.
                FMLLog.log(
                    "PickupIntoInventory",
                    Level.ERROR,
                    "a write was still in progress after %d ms; recent preference changes may not"
                        + " have reached disk",
                    waitMs);
                return;
            }
        }
        try {
            final File target = file;
            final long generation;
            synchronized (PENDING_LOCK) {
                if (stored == changed) return;
                generation = changed;
            }
            boolean written = false;
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
                written = write(target, snapshot);
            } else {
                // Nowhere to write: init() has not run yet, or ran without a file. The change is
                // parked below exactly as a failed one is, and it is worth a line of its own -
                // otherwise a server whose config directory never resolved is indistinguishable in
                // the log from one that had nothing to save.
                FMLLog.log(
                    "PickupIntoInventory",
                    Level.ERROR,
                    "no preference file has been set; changes up to %d are held in memory only",
                    Long.valueOf(generation));
            }
            synchronized (PENDING_LOCK) {
                if (written) {
                    if (stored < generation) stored = generation;
                    failedAt = 0;
                } else {
                    // Not recorded as stored. A failed write is still an outstanding change, and
                    // calling it done loses it for good - the shutdown flush would find nothing to
                    // do - while the command and the packet handler have both already told the
                    // player it took. Parking the generation is only what stops the writer
                    // reattempting it once a second forever; the work stays on the books.
                    failedAt = generation;
                }
                PENDING_LOCK.notifyAll();
            }
        } finally {
            SAVE_LOCK.unlock();
        }
    }

    /**
     * Never opens the real file. 1.3.0 wrote straight into it with a FileOutputStream, which
     * truncates on open, so the moment between that and a completed store was one in which every
     * player's saved choice had already been destroyed and the replacement did not exist yet -
     * anything that stopped the process there, a crash, a kill, a full disk, left an empty file
     * behind and put everybody back on the server default at the next start.
     */
    private static boolean write(File target, Properties contents) {
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
            // The rename itself is metadata, and metadata reaches the disk on the filesystem's own
            // schedule. Without this the file's contents are durable and the name pointing at them
            // is not, so a crash in the seconds after a write can come back to the previous file -
            // the one case the atomic replace above was supposed to have removed.
            syncDirectory(target);
            return true;
        } catch (IOException e) {
            FMLLog.log("PickupIntoInventory", Level.ERROR, e, "could not write %s", target);
            return false;
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
        IOException last = null;
        for (int attempt = 0; attempt < REPLACE_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(REPLACE_BACKOFF_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException busy) {
                // The Windows case: something outside this process has the target open, MoveFileEx
                // answers ACCESS_DENIED, and it arrives as AccessDeniedException - a
                // FileSystemException, but not the one the fallback above catches. Whoever has it
                // lets go in milliseconds.
                last = busy;
            }
        }
        throw last;
    }

    /**
     * Best effort, and deliberately quiet about failing. Opening a directory for read is a POSIX
     * idea; Windows refuses it outright, and there the rename is ordered by the filesystem anyway.
     */
    private static void syncDirectory(File target) {
        final Path parent = target.getAbsoluteFile().toPath().getParent();
        if (parent == null) return;
        FileChannel channel = null;
        try {
            channel = FileChannel.open(parent, StandardOpenOption.READ);
            channel.force(true);
        } catch (IOException unsupported) {
            // no directory fsync here; the contents are still durable
        } catch (RuntimeException unsupported) {
            // UnsupportedOperationException on a provider that will not open a directory
        } finally {
            close(channel);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {}
    }
}
