package net.greatkorn.pickupintoinventory.mixins;

import net.greatkorn.pickupintoinventory.PIISync;
import net.greatkorn.pickupintoinventory.PIITransactions;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.client.C10PacketCreativeInventoryAction;
import net.minecraft.util.IntHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client half of the reconciliation in PIISync: what the player is doing to their windows, and
 * the answer to the confirmation we send behind a repair.
 *
 * Clicks, creative slot edits and window closes are read at HEAD and left entirely alone. The
 * confirmation echo is the one packet that is taken out of the stream, and only when it answers a
 * confirmation of ours: vanilla would otherwise be free to read our answer as the one it is holding
 * a click block for. Which echo is whose is decided by id, and PIITransactions explains why the
 * first echo of a shared id is always ours; anything else - including the second echo of that same
 * id, which is vanilla's - runs into vanilla's handler untouched.
 *
 * All three are counted the same way, because a repair only needs to know whether the client had
 * something of its own outstanding - not what it was. A close matters as much as a click: it moves
 * the client's open container back to window 0 before the server has processed it, which is exactly
 * when a slot packet addressed to the old window would be dropped on arrival.
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayServer implements PIITransactions {

    @Shadow
    public EntityPlayerMP field_147369_b; // playerEntity

    /** Per window, the id of the transaction vanilla itself rejected and is waiting to hear about. */
    @Shadow
    private IntHashMap field_147372_n;

    @Override
    public boolean pii$awaits(int windowId, short uid) {
        final Object waited = this.field_147372_n.func_76041_a(windowId); // lookup
        return waited instanceof Short && ((Short) waited).shortValue() == uid;
    }

    @Inject(
        method = "func_147351_a(Lnet/minecraft/network/play/client/C0EPacketClickWindow;)V",
        at = @At("HEAD"))
    private void pii$noteClick(C0EPacketClickWindow packet, CallbackInfo callback) {
        PIISync.noteWindowAction(this.field_147369_b);
    }

    @Inject(
        method = "func_147344_a(Lnet/minecraft/network/play/client/C10PacketCreativeInventoryAction;)V",
        at = @At("HEAD"))
    private void pii$noteCreativeEdit(C10PacketCreativeInventoryAction packet, CallbackInfo callback) {
        PIISync.noteWindowAction(this.field_147369_b);
    }

    @Inject(
        method = "func_147356_a(Lnet/minecraft/network/play/client/C0DPacketCloseWindow;)V",
        at = @At("HEAD"))
    private void pii$noteClose(C0DPacketCloseWindow packet, CallbackInfo callback) {
        PIISync.noteWindowAction(this.field_147369_b);
    }

    @Inject(
        method = "func_147339_a(Lnet/minecraft/network/play/client/C0FPacketConfirmTransaction;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void pii$noteTransactionAck(C0FPacketConfirmTransaction packet, CallbackInfo callback) {
        final int window = packet.func_149532_c(); // windowId
        final short uid = packet.func_149533_d();
        // Swallowing the one answer vanilla is waiting for would block that player's clicks for
        // good - ContainerPlayer is built once and closing the screen does not replace it - so it
        // is worth being clear about why the answer taken here can never be that one. An id vanilla
        // is already waiting on is never picked (PIITransactions), so if it is waiting on this one
        // now, the rejection came after our confirmation went out; the client answers in the order
        // it was asked, this echo is the first of the two, and vanilla's own is still behind it.
        if (PIISync.noteTransactionAck(this.field_147369_b, window, uid, this.pii$awaits(window, uid))) {
            callback.cancel();
        }
    }
}
