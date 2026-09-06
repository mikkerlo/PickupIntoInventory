package net.greatkorn.pickupintoinventory.net;

import java.util.UUID;

import net.greatkorn.pickupintoinventory.PIIPolicy;
import net.minecraft.entity.player.EntityPlayerMP;

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
        // Discriminators are part of the wire format and never move: 0 is what 1.3.0 registered.
        channel.registerMessage(MsgSetPreference.Handler.class, MsgSetPreference.class, 0, Side.SERVER);
        channel
            .registerMessage(MsgEffectivePolicy.Handler.class, MsgEffectivePolicy.class, 1, Side.CLIENT);
    }

    /**
     * Tells one player what is actually in force for them.
     *
     * Only ever sent to a client that has identified itself as understanding it. FML's indexed
     * codec throws on a discriminator it has no registration for, so a message a 1.3.0 client - or
     * a client without the mod, which has no channel at all - was never told about must not be put
     * on the wire towards it. A client says it understands by sending a preference carrying a mode
     * byte, which it does as soon as it connects.
     */
    public static void sendPolicy(EntityPlayerMP player, boolean announce) {
        if (player == null || channel == null) return;
        final UUID id = player.func_110124_au();
        if (!PIIPolicy.speaks(id)) return;

        // Stamping it is also what stops this client counting as mirroring us until it answers for
        // this particular policy rather than some earlier one.
        final byte serial = PIIPolicy.beginPush(id);

        channel.sendTo(
            new MsgEffectivePolicy(
                PIIPolicy.serverEffective(id),
                PIIPolicy.isLocked(),
                PIIPolicy.serverHotbarFallback(),
                PIIPolicy.choiceFor(id),
                serial,
                announce),
            player);
    }
}
