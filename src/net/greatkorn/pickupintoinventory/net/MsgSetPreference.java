package net.greatkorn.pickupintoinventory.net;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

import net.greatkorn.pickupintoinventory.PIIPolicy;
import net.greatkorn.pickupintoinventory.PIIState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Client -> server: what the player wants for themselves, and the acknowledgement of being told
 * what they actually got.
 *
 * The mode is the whole point of the message. 1.3.0 sent a bare value on every connect, so a
 * /pickupinv the player had run on that server was overwritten by their local config the next time
 * they logged in, and their local config had no way of saying "this is only what I default to". A
 * preference the player has not touched since connecting is a default and is applied only where the
 * server holds nothing for them; one they just changed is an instruction and is applied over
 * whatever is there. Neither survives allowPlayerOverride=false, which refuses both alike.
 */
public class MsgSetPreference implements IMessage {

    /** Sent on connect: a default to fall back on, not an instruction to overwrite with. */
    public static final byte MODE_LOGIN = 0;

    /** The player just changed it, by keybind or config screen. */
    public static final byte MODE_EXPLICIT = 1;

    /** Not a request: the client confirming it has applied a policy it was sent. */
    public static final byte MODE_ACK = 2;

    public boolean followServerDefault;
    public boolean enabled;
    public byte mode = MODE_EXPLICIT;

    /**
     * Whether the mode byte was actually on the wire. A 1.3.0 client has no mode and no handler for
     * the answer either, so it must be treated the way 1.3.0's server treated it and never replied
     * to.
     */
    public boolean modal;

    /** For MODE_ACK, the serial of the policy being answered for. Meaningless otherwise. */
    public byte serial;

    public MsgSetPreference() {}

    public MsgSetPreference(byte mode, boolean followServerDefault, boolean enabled) {
        this.mode = mode;
        this.followServerDefault = followServerDefault;
        this.enabled = enabled;
        this.modal = true;
    }

    /** The answer to being sent a policy; carries no request of its own beyond which one it was. */
    public static MsgSetPreference acknowledgement(byte serial) {
        final MsgSetPreference ack = new MsgSetPreference(MODE_ACK, false, false);
        ack.serial = serial;
        return ack;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        followServerDefault = buf.readBoolean();
        enabled = buf.readBoolean();
        // The mode goes on the end, not the front, so the two booleans stay where 1.3.0 put them:
        // a 1.3.0 server reading this message still reads the same request out of it and ignores
        // the byte it does not know about. This direction is the one that needs the guard, since a
        // 1.3.0 client's message simply stops after the second boolean.
        modal = buf.isReadable();
        mode = modal ? buf.readByte() : MODE_EXPLICIT;
        // The serial only rides along on an acknowledgement, and only from a build that sends one;
        // everything trailing the two booleans is optional for the same reason the mode byte is.
        serial = buf.isReadable() ? buf.readByte() : 0;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(followServerDefault);
        buf.writeBoolean(enabled);
        buf.writeByte(mode);
        buf.writeByte(serial);
    }

    private PIIState.Choice requested() {
        if (followServerDefault) return PIIState.Choice.SERVER;
        return enabled ? PIIState.Choice.ON : PIIState.Choice.OFF;
    }

    public static class Handler implements IMessageHandler<MsgSetPreference, IMessage> {

        @Override
        public IMessage onMessage(MsgSetPreference msg, MessageContext ctx) {
            // Runs on the server thread: FMLProxyPacket does not override Packet.hasPriority, so
            // NetworkManager queues it and processReceivedPackets drains it in the tick loop. The
            // one thing that follows from that is where PIIState's file write lands.
            //
            // The handshake is still finishing when the first of these arrives - the client sends
            // it from ClientConnectedToServerEvent, which fires before FML's final handshake ack -
            // so the connection may still be on the login handler when the queue is drained, and
            // getServerHandler would throw a ClassCastException that costs the player their
            // connection. Ask before casting.
            if (!(ctx.netHandler instanceof NetHandlerPlayServer)) return null;
            EntityPlayerMP player = ctx.getServerHandler().field_147369_b;
            if (player == null) return null;
            final UUID id = player.func_110124_au();
            // Before the modal split, because a 1.3.0 client counts just as much: what this marks
            // is that something on the other end is running the mod at all, which is what lets the
            // redirect-off branch stop repairing slots a bare vanilla client cannot get wrong.
            PIIPolicy.noteMod(id);

            if (!msg.modal) {
                // A 1.3.0 client. It cannot say what kind of request this is, so every one of them
                // is a request, exactly as before - including the one it sends on connect, which is
                // why an old client still cannot keep a /pickupinv choice across a reconnect. It
                // also has no registration for the answer, so it is not sent one.
                if (!PIIPolicy.isLocked()) PIIState.set(id, msg.requested());
                return null;
            }

            PIIPolicy.noteSpeaker(id);

            if (msg.mode == MODE_ACK) {
                // From here on this client routes pickups exactly as this side does, so the server
                // can stop repairing the slot vanilla would have chosen on its behalf.
                PIIPolicy.noteMirror(id, msg.serial);
                return null;
            }

            // Nothing is applied while the server refuses personal settings, but the answer below
            // still goes out: being told no is the only way the client learns not to route on the
            // setting it just asked for.
            if (!PIIPolicy.isLocked()) {
                if (msg.mode == MODE_EXPLICIT) {
                    PIIState.set(id, msg.requested());
                } else {
                    // A login preference is remembered for the connection and never written to
                    // disk. Storing it is what buried a /pickupinv choice on the next login, and it
                    // would also leave behind a stored value the player never chose.
                    PIIPolicy.noteOffer(id, msg.requested());
                }
            }

            // Always answer, even where nothing was applied: a refused override and an accepted one
            // are indistinguishable from the client's side otherwise, and the answer is also what
            // tells the client whether to route pickups at all.
            PIINetwork.sendPolicy(player, msg.mode == MODE_EXPLICIT);
            return null;
        }
    }
}
