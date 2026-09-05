package net.greatkorn.pickupintoinventory;

import java.io.File;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

/**
 * Config state. Kept free of Minecraft classes so the mixin can read it without
 * dragging anything extra onto the transformer classloader.
 */
public final class PIIConfig {

    public static final String MODID = "pickupintoinventory";
    public static final String LANG = MODID + ".cfg.";

    /**
     * Your own preference. On a server this is what the client asks the server to use for you;
     * where this copy IS the server it is the default for players who have not chosen.
     */
    public static boolean enabled = true;

    /** Server-authoritative: when the main inventory is full, fall back to an empty hotbar slot. */
    public static boolean allowHotbarWhenInventoryFull = true;

    /** Server-authoritative: let players pick their own setting via the GUI or /pickupinv. */
    public static boolean allowPlayerOverride = true;

    public static Configuration config;

    private PIIConfig() {}

    public static void init(File file) {
        if (config == null) config = new Configuration(file);
        load();
    }

    public static void load() {
        enabled = prop("enabled", true,
            "Picked-up items are placed in the main inventory instead of filling empty hotbar slots.\n"
                + "Items still stack into matching stacks anywhere, hotbar included.\n"
                + "On a server this is sent as your personal preference; on the server itself it is\n"
                + "the default for players who have not chosen one.");

        allowHotbarWhenInventoryFull = prop("allowHotbarWhenInventoryFull", true,
            "When the main inventory has no empty slot left, fall back to an empty hotbar slot.\n"
                + "Set to false to leave the item on the ground instead.\n"
                + "SERVER-SIDE: only the value on the machine running the world applies.");

        allowPlayerOverride = prop("allowPlayerOverride", true,
            "Let each player choose their own setting, from the config GUI or /pickupinv.\n"
                + "Set to false to force 'enabled' on everyone.\n"
                + "SERVER-SIDE: only the value on the machine running the world applies.");

        if (config.hasChanged()) config.save();
    }

    /** Flips 'enabled' and writes it back to disk, for the in-game keybind. */
    public static void setEnabled(boolean value) {
        enabled = value;
        if (config == null) return;
        config.get(Configuration.CATEGORY_GENERAL, "enabled", true).set(value);
        config.save();
    }

    private static boolean prop(String key, boolean def, String comment) {
        Property p = config.get(Configuration.CATEGORY_GENERAL, key, def, comment);
        p.setLanguageKey(LANG + key);
        return p.getBoolean(def);
    }
}
