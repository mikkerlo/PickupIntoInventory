package net.greatkorn.pickupintoinventory.mixins;

import net.greatkorn.pickupintoinventory.PIIConfig;
import net.greatkorn.pickupintoinventory.PIIContext;
import net.greatkorn.pickupintoinventory.PIIState;
import net.greatkorn.pickupintoinventory.PIISync;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla routes a picked-up item to the first empty slot of mainInventory, and the hotbar is
 * slots 0-8 of that array, so an empty hotbar slot always wins. Every path that places a
 * picked-up item asks getFirstEmptyStack() for that slot:
 *
 *   addItemStackToInventory / func_70441_a  - damaged items (one call)
 *   storePartialItemStack   / func_70452_e  - everything else (two calls)
 *
 * Redirecting those call sites - rather than overwriting getFirstEmptyStack itself - keeps the
 * change scoped to item pickup. Other users of getFirstEmptyStack (creative pick-block, mod code)
 * are untouched, and mods that adjust the method's return value still get to run, because the
 * redirect calls it and then decides what to do with the answer.
 */
@Mixin(InventoryPlayer.class)
public abstract class MixinInventoryPlayer {

    private static final int PII_HOTBAR_SIZE = 9;

    /** Slots past this are appended by other mods (Backhand's offhand, BG2's quiver) - not pickup targets. */
    private static final int PII_VANILLA_MAIN_SIZE = 36;

    @Redirect(
        method = { "func_70441_a(Lnet/minecraft/item/ItemStack;)Z",
                   "func_70452_e(Lnet/minecraft/item/ItemStack;)I" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/InventoryPlayer;func_70447_i()I"))
    private int pii$preferMainInventory(InventoryPlayer self) {
        final int slot = self.func_70447_i();

        // -1 means nothing is free at all; >= 9 means it already picked a non-hotbar slot.
        if (slot < 0 || slot >= PII_HOTBAR_SIZE) return slot;
        if (!PIIState.isEnabledFor(self.field_70458_d)) return slot;

        final ItemStack[] inv = self.field_70462_a;
        final int limit = Math.min(inv.length, PII_VANILLA_MAIN_SIZE);
        for (int i = PII_HOTBAR_SIZE; i < limit; i++) {
            if (inv[i] == null) {
                // Slots 9-35 have no guaranteed sync path to the client; ask for a resend.
                PIISync.markDirty(self.field_70458_d);
                return i;
            }
        }

        // Refusing the slot leaves the item on the ground - but only where the caller still has it
        // to leave. The callers that discard our answer (Container.slotClick's number-key swap has
        // already emptied the slot it is putting this stack back into) would lose it. See PIIContext.
        if (PIIContext.isUncheckedInsert()) return slot;

        // In creative, addItemStackToInventory answers -1 by zeroing the stack and reporting
        // success instead of leaving the item on the ground, so refusing here deletes it too.
        final EntityPlayer owner = self.field_70458_d;
        if (owner != null && owner.field_71075_bZ != null && owner.field_71075_bZ.field_75098_d) return slot;

        return PIIConfig.allowHotbarWhenInventoryFull ? slot : -1;
    }
}
