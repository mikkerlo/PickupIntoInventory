package net.greatkorn.pickupintoinventory.net;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class PIINetwork {

    /** Channel names are capped at 20 chars in 1.7.10. */
    public static final String CHANNEL_NAME = "PickupIntoInv";

    public static SimpleNetworkWrapper channel;

    private PIINetwork() {}

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
        channel.registerMessage(MsgSetPreference.Handler.class, MsgSetPreference.class, 0, Side.SERVER);
    }
}
