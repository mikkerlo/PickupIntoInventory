package net.greatkorn.pickupintoinventory.mixins;

import net.greatkorn.pickupintoinventory.PIIContext;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * One of the two calls in the game that may be refused a slot; MixinEntityArrow is the other.
 * onCollideWithPlayer / func_70100_b_ hands the stack to addItemStackToInventory inside the
 * condition that decides whether the EntityItem dies, so a false answer leaves the item lying on
 * the ground exactly where it was - which is what allowHotbarWhenInventoryFull=false is asking
 * for, and the only kind of place asking for it makes sense.
 *
 * Verified against the bytecode this actually runs against rather than against vanilla's: Forge
 * binpatches EntityItem, and applying that patch (binpatch/client/net.minecraft.entity.item
 * .EntityItem.binpatch, source adler32 confirmed against the 1.7.10 client jar) gives a
 * func_70100_b_ with exactly one InventoryPlayer.func_70441_a call, at offset 113, whose result is
 * tested by an ifeq that skips setDead. One call site, so one redirect, which is what
 * injectors.defaultRequire = 1 in the config now holds the build to.
 *
 * The mark goes on the call rather than on the method, and the patched bytecode is why: Forge
 * fires EntityItemPickupEvent at offset 18, well before the insert at 113. A handler of that event
 * may insert something of its own and drop the answer, and marking the method would pull every
 * such handler inside the window where a refusal is allowed - which is the deletion this change
 * exists to remove. Everything outside this redirect keeps the main-inventory preference and never
 * gets a refusal. See PIIContext.
 */
@Mixin(EntityItem.class)
public abstract class MixinEntityItem {

    @Redirect(
        method = "func_70100_b_(Lnet/minecraft/entity/player/EntityPlayer;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/InventoryPlayer;func_70441_a(Lnet/minecraft/item/ItemStack;)Z"))
    private boolean pii$collectFromGround(InventoryPlayer inventory, ItemStack stack) {
        final ItemStack previous = PIIContext.beginGroundPickup(stack);
        try {
            return inventory.func_70441_a(stack);
        } finally {
            PIIContext.endGroundPickup(previous);
        }
    }
}
