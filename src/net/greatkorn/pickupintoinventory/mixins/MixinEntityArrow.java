package net.greatkorn.pickupintoinventory.mixins;

import net.greatkorn.pickupintoinventory.PIIContext;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The second caller that still has the item to leave: an arrow stuck in the ground, walked into.
 *
 * Structurally the same as EntityItem's, and easy to miss for exactly that reason - it is a pickup
 * off the ground that does not go through EntityItem at all. func_70100_b_ calls
 * addItemStackToInventory at offset 80 and, on false, clears the local at 86-87 that offset 89
 * tests before calling setDead at 131. So a refusal leaves the arrow in the world, which is what
 * allowHotbarWhenInventoryFull=false asks for; without this redirect the arrow would instead take
 * the last empty hotbar slot no matter what the setting says.
 *
 * Unlike EntityItem, EntityArrow appears in no Forge binpatch, so the vanilla bytecode is the
 * bytecode this runs against and the offsets above are the real ones.
 */
@Mixin(EntityArrow.class)
public abstract class MixinEntityArrow {

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
