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

import net.minecraft.entity.player.EntityPlayer;

/**
 * Per-player preferences, held and persisted by whichever side is the logical server.
 * Written from the netty thread when a client sends its preference, so the map is concurrent
 * and file writes are serialised.
 */
public final class PIIState {

    private static final Map<UUID, Boolean> OVERRIDES = new ConcurrentHashMap<UUID, Boolean>();
    private static final Object SAVE_LOCK = new Object();
    private static File file;

    private PIIState() {}

    /** The question the mixin asks on every pickup. */
    public static boolean isEnabledFor(EntityPlayer player) {
        if (player == null) return PIIConfig.enabled;
        // On the client the local config is all there is; the server decides for real.
        if (player.field_70170_p != null && player.field_70170_p.field_72995_K) return PIIConfig.enabled;
        if (!PIIConfig.allowPlayerOverride) return PIIConfig.enabled;

        Boolean chosen = OVERRIDES.get(player.func_110124_au());
        return chosen == null ? PIIConfig.enabled : chosen.booleanValue();
    }

    /** null = follow the server default. */
    public static Boolean get(UUID id) {
        return OVERRIDES.get(id);
    }

    public static void set(UUID id, Boolean value) {
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
                    OVERRIDES.put(UUID.fromString(key), Boolean.valueOf(p.getProperty(key)));
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
            for (Map.Entry<UUID, Boolean> e : OVERRIDES.entrySet()) {
                p.setProperty(e.getKey().toString(), e.getValue().toString());
            }
            OutputStream out = null;
            try {
                out = new FileOutputStream(file);
                p.store(out, "Per-player overrides for Pickup Into Inventory. true/false per player UUID.");
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
