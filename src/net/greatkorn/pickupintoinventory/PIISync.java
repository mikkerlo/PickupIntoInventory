package net.greatkorn.pickupintoinventory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S30PacketWindowItems;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

/**
 * Pushes the player's inventory back to their client after we have redirected a pickup.
 *
 * 1.7.10 only guarantees delivery of the hotbar. NetHandlerPlayClient.handleSetSlot applies a
 * window-0 S2FPacketSetSlot to the player's own container unconditionally for container slots
 * 36-44 (the hotbar) and, for every other slot, only when the client's openContainer.windowId
 * happens to match. EntityPlayerMP.sendSlotContents also drops slot packets outright while
 * isChangingQuantityOnly is set, which several pack mods toggle around their own click handling.
 *
 * Vanilla mostly gets away with that because an empty hotbar slot wins every pickup, which is the
 * one path with a hard guarantee. Moving pickups into slots 9-35 puts them on the fragile path, so
 * a stale client shows the item missing until something rewrites the slots (pressing sort, for
 * instance). handleWindowItems has no such condition - a window-0 S30PacketWindowItems is always
 * applied to inventoryContainer - so that is what we resend.
 *
 * At most one packet per player per tick, and only on ticks where a pickup was actually redirected.
 * We build the packet ourselves rather than calling EntityPlayerMP.sendContainerToPlayer, which
 * would also push the cursor stack and could stomp on a drag in progress.
 */
public final class PIISync {

    private static final Set<UUID> DIRTY = ConcurrentHashMap.newKeySet();

    private PIISync() {}

    /** Called from the mixin once we have steered an item away from the hotbar. */
    public static void markDirty(EntityPlayer player) {
        if (!PIIConfig.resyncAfterPickup) return;
        if (!(player instanceof EntityPlayerMP)) return;
        if (player.field_70170_p == null || player.field_70170_p.field_72995_K) return;
        DIRTY.add(player.func_110124_au());
    }

    public static void register() {
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(new Handler());
    }

    public static final class Handler {

        @SubscribeEvent
        public void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || event.side != Side.SERVER) return;
            if (DIRTY.isEmpty() || !(event.player instanceof EntityPlayerMP)) return;
            if (!DIRTY.remove(event.player.func_110124_au())) return;

            final EntityPlayerMP player = (EntityPlayerMP) event.player;
            if (player.field_71135_a == null) return;
            player.field_71135_a.func_147359_a(
                new S30PacketWindowItems(
                    player.field_71069_bz.field_75152_c, player.field_71069_bz.func_75138_a()));
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            DIRTY.remove(event.player.func_110124_au());
        }
    }
}
