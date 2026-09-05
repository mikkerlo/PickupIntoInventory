package net.greatkorn.pickupintoinventory.net;

import io.netty.buffer.ByteBuf;

import net.greatkorn.pickupintoinventory.PIIConfig;
import net.greatkorn.pickupintoinventory.PIIState;
import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/** Client -> server: "this is the setting I want for myself". */
public class MsgSetPreference implements IMessage {

    public boolean followServerDefault;
    public boolean enabled;

    public MsgSetPreference() {}

    public MsgSetPreference(boolean followServerDefault, boolean enabled) {
        this.followServerDefault = followServerDefault;
        this.enabled = enabled;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        followServerDefault = buf.readBoolean();
        enabled = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(followServerDefault);
        buf.writeBoolean(enabled);
    }

    public static class Handler implements IMessageHandler<MsgSetPreference, IMessage> {

        @Override
        public IMessage onMessage(MsgSetPreference msg, MessageContext ctx) {
            // Runs on the netty thread. PIIState is concurrent, so no server-thread hop is needed.
            if (!PIIConfig.allowPlayerOverride) return null;

            EntityPlayerMP player = ctx.getServerHandler().field_147369_b;
            if (player == null) return null;

            PIIState.set(
                player.func_110124_au(),
                msg.followServerDefault ? null : Boolean.valueOf(msg.enabled));
            return null;
        }
    }
}
