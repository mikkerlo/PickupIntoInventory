package net.greatkorn.pickupintoinventory.net;

import io.netty.buffer.ByteBuf;

import net.greatkorn.pickupintoinventory.PIIPolicy;
import net.greatkorn.pickupintoinventory.PIIState;
import net.greatkorn.pickupintoinventory.PickupIntoInventory;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Server -> client: what is actually in force for this player.
 *
 * Nothing carried the effective setting back before, so the client could only guess from its own
 * config - and a client guessing wrong routes a number-key swap into a different slot than the
 * server does, with the click still accepted because the clicked slot matched. The client applies
 * what arrives here and nothing else, and until something arrives it routes the way a client
 * without the mod would.
 *
 * The hotbar fallback rides along for the same reason: it is a routing decision the client makes
 * too, and a client applying its own copy of a server-side setting is the same divergence again.
 *
 * The lock and the stored choice are carried so the player can be told what actually happened to a
 * request rather than being shown what they asked for.
 */
public class MsgEffectivePolicy implements IMessage {

    public boolean enabled;
    public boolean locked;
    public boolean hotbarFallback;

    /** Whether the client should say so in chat: true only for a change the player just asked for. */
    public boolean announce;

    /** Ordinal of PIIState.Choice; anything unrecognised reads as "follow the server". */
    public byte choice;

    /**
     * Identifies this policy so the answer to it cannot be mistaken for the answer to the one
     * before. See PIIPolicy.Session.
     */
    public byte serial;

    public MsgEffectivePolicy() {}

    public MsgEffectivePolicy(
        boolean enabled,
        boolean locked,
        boolean hotbarFallback,
        PIIState.Choice choice,
        byte serial,
        boolean announce) {
        this.enabled = enabled;
        this.locked = locked;
        this.hotbarFallback = hotbarFallback;
        this.choice = (byte) (choice == null ? PIIState.Choice.SERVER.ordinal() : choice.ordinal());
        this.serial = serial;
        this.announce = announce;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        enabled = buf.readBoolean();
        locked = buf.readBoolean();
        hotbarFallback = buf.readBoolean();
        announce = buf.readBoolean();
        choice = buf.readByte();
        serial = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(enabled);
        buf.writeBoolean(locked);
        buf.writeBoolean(hotbarFallback);
        buf.writeBoolean(announce);
        buf.writeByte(choice);
        buf.writeByte(serial);
    }

    private PIIState.Choice chosen() {
        final PIIState.Choice[] all = PIIState.Choice.values();
        return choice >= 0 && choice < all.length ? all[choice] : PIIState.Choice.SERVER;
    }

    /**
     * Runs on the client, but registerMessage instantiates every handler on both physical sides, so
     * this class is loaded on a dedicated server too - which is why the one thing here that needs a
     * client class goes through the proxy instead of naming it.
     */
    public static class Handler implements IMessageHandler<MsgEffectivePolicy, IMessage> {

        @Override
        public IMessage onMessage(MsgEffectivePolicy msg, MessageContext ctx) {
            PIIPolicy.applyToClient(msg.enabled, msg.locked, msg.hotbarFallback, msg.chosen());
            // Answer before anything else. Until the server has heard this it has to assume we are
            // still routing the way a client without the mod does, and repairs the slot vanilla
            // would have chosen after every diverted pickup to cover it - see
            // PIISync.noteForeignSlot.
            PIINetwork.channel.sendToServer(MsgSetPreference.acknowledgement(msg.serial));
            PickupIntoInventory.proxy.policyReceived(msg.announce);
            return null;
        }
    }
}
