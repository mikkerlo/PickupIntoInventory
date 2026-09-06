package net.greatkorn.pickupintoinventory.mixins;

import net.greatkorn.pickupintoinventory.PIIContext;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The second - and, in 1.7.10, last - vanilla caller that throws away the addItemStackToInventory
 * result. onEaten / func_77654_b returns the empty bottle directly when the potion stack is spent,
 * but when more potions are left it hands the bottle to addItemStackToInventory and drops the
 * boolean. Refusing there deletes the bottle, where vanilla would have used the free hotbar slot.
 *
 * Same one-line exemption as Container: the main-inventory preference still applies, the refusal
 * does not.
 */
@Mixin(ItemPotion.class)
public abstract class MixinItemPotion {

    @Redirect(
        method = "func_77654_b(Lnet/minecraft/item/ItemStack;Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/item/ItemStack;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/InventoryPlayer;func_70441_a(Lnet/minecraft/item/ItemStack;)Z"))
    private boolean pii$placeEmptyBottle(InventoryPlayer inventory, ItemStack bottle) {
        final boolean previous = PIIContext.beginUncheckedInsert();
        try {
            return inventory.func_70441_a(bottle);
        } finally {
            PIIContext.endUncheckedInsert(previous);
        }
    }
}
