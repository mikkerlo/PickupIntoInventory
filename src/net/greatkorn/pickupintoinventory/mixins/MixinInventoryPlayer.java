package net.greatkorn.pickupintoinventory.mixins;

import net.greatkorn.pickupintoinventory.PIIContext;
import net.greatkorn.pickupintoinventory.PIIPolicy;
import net.greatkorn.pickupintoinventory.PIISync;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
        final EntityPlayer owner = self.field_70458_d;

        // -1 means nothing is free at all; >= 9 means it already picked a non-hotbar slot.
        if (slot < 0 || slot >= PII_HOTBAR_SIZE) return slot;

        final ItemStack[] inv = self.field_70462_a;

        if (!PIIPolicy.isEnabledFor(owner)) {
            // Leaving the item where vanilla puts it, which is where a client running the same
            // policy puts it too. A client that is not - one too old to be told, or on a server
            // that refuses to talk to it - has instead put it in the first empty main slot, so it
            // is wrong twice over: an item it does not have where vanilla put it, and one it does
            // have where this side left a gap. The gate is asked before the scan because for
            // everyone else there is nothing here to do.
            if (PIISync.repairsOffBranch(owner)) {
                final int predicted = pii$firstEmptyMain(inv);
                if (predicted >= 0) {
                    PIISync.noteForeignSlot(owner, predicted);
                    PIISync.noteForeignSlot(owner, slot);
                } else if (!PIIPolicy.allowsHotbarFallback(owner)) {
                    // Main full, so the other side has nowhere to route to and the branch above
                    // marks nothing - but a client still running the redirect with a refusal of
                    // its own leaves the item on the ground while this side puts it in `slot`.
                    PIISync.noteForeignSlot(owner, slot);
                }
            }
            return slot;
        }

        final int main = pii$firstEmptyMain(inv);
        if (main >= 0) {
            // Vanilla was going to use `slot`, and anything predicting this insertion without
            // running the same policy has put the item there. Nothing else can notice that: this
            // side never touches the slot, so the diff around the pickup sees nothing to resend.
            PIISync.noteForeignSlot(owner, slot);
            return main;
        }

        // Refusing the slot leaves the item on the ground - but only for the one caller that still
        // has it to leave, which is the EntityItem walked into. Everyone else either keeps the item
        // somewhere of their own choosing, where refusing gains nothing, or throws the answer away,
        // where it destroys the stack: Container.slotClick's number-key swap has already emptied the
        // slot it is putting this one back into, and 25 mods in one pack do the same. Asking who may
        // be refused rather than who must not be is what keeps that list from going stale. See
        // PIIContext.
        if (!PIIContext.isGroundPickup()) return slot;

        // In creative, addItemStackToInventory answers -1 by zeroing the stack and reporting
        // success instead of leaving the item on the ground, so refusing here deletes it too - and
        // a ground pickup reaches that branch like any other, which is why this outlives the check
        // above rather than being covered by it. PlayerCapabilities is assigned while EntityPlayer
        // is still being constructed, so only the owner itself is worth testing for null.
        if (owner != null && owner.field_71075_bZ.field_75098_d) return slot;

        // Asked of the policy rather than the local config, though nothing now depends on the
        // client holding the same answer: a ground pickup only ever happens on the server, so this
        // is the one line in the redirect the client cannot reach. Refusing used to be reachable
        // through the number-key swap, which both sides run, and the two disagreeing there put the
        // displaced stack in different slots on a click that was still accepted. That divergence is
        // gone with the whitelist inverted - both sides now take the branch above - and the client's
        // copy of the setting is kept as a guard rather than as something it routes on.
        //
        // A refusal is a divergence, and one nothing else can see: this side writes no slot, so
        // the diff around the pickup finds nothing to resend, while a client that is not running
        // the redirect has no refusal either and has put the item in `slot`. Every caller that
        // reaches here runs on both sides - ItemBucket.fillBucket, ItemGlassBottle, EntityCow's
        // interact, SlotCrafting - so the ghost is not hypothetical, and it persists until
        // something unrelated rewrites the slot.
        final boolean fallback = PIIPolicy.allowsHotbarFallback(owner);
        if (!fallback) PIISync.noteForeignSlot(owner, slot);
        return fallback ? slot : -1;
    }

    /** The slot the redirect steers to, and the slot a client still running it has predicted. */
    private static int pii$firstEmptyMain(ItemStack[] inv) {
        final int limit = Math.min(inv.length, PII_VANILLA_MAIN_SIZE);
        for (int i = PII_HOTBAR_SIZE; i < limit; i++) {
            if (inv[i] == null) return i;
        }
        return -1;
    }

    /**
     * Slots 9-35 have no guaranteed sync path to the client, so whatever a pickup puts there has to
     * be resent by hand. Which slots those are cannot be read off the redirect above: only the
     * placement paths ask getFirstEmptyStack, while storePartialItemStack merges into an existing
     * partial stack through storeItemStack and never comes near it - so a second pickup topping up
     * a stack the first one created would go unnoticed, and stay stale on the client for as long as
     * nothing else happens to rewrite the slot.
     *
     * addItemStackToInventory is the one door into all of that (storePartialItemStack is private
     * and has no other caller), so the slots are taken before and compared after. That catches
     * placements, merges, an item spread over several partial stacks, and anything a mod does to
     * the same array while the call is running.
     */
    @Inject(method = "func_70441_a(Lnet/minecraft/item/ItemStack;)Z", at = @At("HEAD"))
    private void pii$watchPickup(ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
        PIISync.beginPickup((InventoryPlayer) (Object) this);
    }

    @Inject(method = "func_70441_a(Lnet/minecraft/item/ItemStack;)Z", at = @At("RETURN"))
    private void pii$reconcilePickup(ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
        PIISync.endPickup((InventoryPlayer) (Object) this);
    }
}
