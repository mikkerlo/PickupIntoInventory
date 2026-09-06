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
     * Your own preference, and nothing else. A client offers it to the server as the setting to use
     * for you where the server holds none, and the machine running the world copies it once at
     * startup as the default for players who have not chosen - see PIIPolicy. Nothing reads it live
     * to decide a pickup, and nothing writes to it once a world is running: on a LAN host, where
     * this file is the world's default as well as the host's preference, either would make the
     * host's personal toggle a server-wide policy change. The config screen still edits it, but
     * that screen is reachable from the title screen, before any world has taken its copy. The keybind asks the server instead, and the server keeps
     * the answer under the player's UUID like anyone else's.
     */
    public static boolean enabled = true;

    /** Server-authoritative: when the main inventory is full, fall back to an empty hotbar slot. */
    public static boolean allowHotbarWhenInventoryFull = true;

    /** Server-authoritative: let players pick their own setting via the GUI or /pickupinv. */
    public static boolean allowPlayerOverride = true;

    /** Server-authoritative: resend the slots a pickup changed so the client sees them. */
    public static boolean resyncAfterPickup = true;

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
                + "Your personal preference. On a server it is offered as the setting to use for you,\n"
                + "and the server keeps to a choice you have already made there with the keybind or\n"
                + "/pickupinv rather than replacing it every time you log in. The keybind changes\n"
                + "that choice on the server and does not write here.\n"
                + "On the machine running the world it is also the default for players who have not\n"
                + "chosen, read once when the world starts - so changing it takes effect there at the\n"
                + "next world load, not immediately.");

        allowHotbarWhenInventoryFull = prop("allowHotbarWhenInventoryFull", true,
            "When the main inventory has no empty slot left, fall back to an empty hotbar slot.\n"
                + "Set to false to leave the item on the ground instead.\n"
                + "SERVER-SIDE: only the value on the machine running the world applies.");

        allowPlayerOverride = prop("allowPlayerOverride", true,
            "Let each player choose their own setting, from the config GUI or /pickupinv.\n"
                + "Set to false to force 'enabled' on everyone.\n"
                + "SERVER-SIDE: only the value on the machine running the world applies.");

        resyncAfterPickup = prop("resyncAfterPickup", true,
            "Resend the main-inventory slots a pickup changed, so the client sees them.\n"
                + "1.7.10 only guarantees that hotbar slots reach the client, so without this an item\n"
                + "can be missing from the inventory screen until something redraws it (pressing sort).\n"
                + "Only the slots that actually changed are sent, and only on ticks where a pickup\n"
                + "changed one; each repair is followed by a confirmation the client answers, so a\n"
                + "click it had in flight at the time is noticed and the repair repeated.\n"
                + "This also carries the correction for a client that routes pickups differently from\n"
                + "this server - one too old to be told what is in force for it - so turning it off\n"
                + "leaves those clients showing items in slots the server never put them in.\n"
                + "SERVER-SIDE: only the value on the machine running the world applies.");

        if (config.hasChanged()) config.save();
    }

    private static boolean prop(String key, boolean def, String comment) {
        Property p = config.get(Configuration.CATEGORY_GENERAL, key, def, comment);
        p.setLanguageKey(LANG + key);
        return p.getBoolean(def);
    }
}
