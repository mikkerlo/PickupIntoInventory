package net.greatkorn.pickupintoinventory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player has asked for, held and persisted by whichever side is the logical server.
 * Every change arrives on the main server thread - FMLProxyPacket does not override
 * Packet.hasPriority, so a preference message is queued by NetworkManager and drained in the tick
 * loop, and /pickupinv runs there too. The map is concurrent and the writes serialised anyway,
 * because nothing here should depend on that staying true.
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

    private static final Map<UUID, Choice> OVERRIDES = new ConcurrentHashMap<UUID, Choice>();
    private static final Object SAVE_LOCK = new Object();
    private static File file;

    private PIIState() {}

    /** null = this player has never chosen, which is not the same as choosing to follow. */
    public static Choice get(UUID id) {
        return OVERRIDES.get(id);
    }

    public static void set(UUID id, Choice value) {
        if (value == null) OVERRIDES.remove(id);
        else OVERRIDES.put(id, value);
        save();
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

    private static void save() {
        if (file == null) return;
        synchronized (SAVE_LOCK) {
            Properties p = new Properties();
            for (Map.Entry<UUID, Choice> e : OVERRIDES.entrySet()) {
                p.setProperty(e.getKey().toString(), e.getValue().token());
            }
            OutputStream out = null;
            try {
                out = new FileOutputStream(file);
                p.store(out, "Per-player choices for Pickup Into Inventory, by player UUID."
                    + " true = on, false = off, server = follow the server default.");
            } catch (IOException e) {
                System.err.println("[PickupIntoInventory] could not write " + file + ": " + e);
            } finally {
                close(out);
            }
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {}
    }
}
