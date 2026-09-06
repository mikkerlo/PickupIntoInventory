package net.greatkorn.pickupintoinventory.mixins;

import net.greatkorn.pickupintoinventory.PIIContext;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * slotClick / func_75144_a holds the one addItemStackToInventory call in Container, in the
 * number-key swap branch (mode 2): the selected hotbar slot has already been overwritten with the
 * clicked container item, and the stack that used to be there is handed over to be put back
 * somewhere. Vanilla drops the boolean - it called getFirstEmptyStack before mutating anything, so
 * as far as it is concerned a slot is waiting.
 *
 * That check ran against the unmodified getFirstEmptyStack, not against our answer. With
 * allowHotbarWhenInventoryFull=false and slots 9-35 full, our redirect refuses the empty hotbar
 * slot vanilla had counted on and the displaced stack is deleted outright. Marking the call tells
 * the redirect to keep steering into the main inventory but never to refuse.
 */
@Mixin(Container.class)
public abstract class MixinContainer {

    @Redirect(
        method = "func_75144_a(IIILnet/minecraft/entity/player/EntityPlayer;)Lnet/minecraft/item/ItemStack;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/InventoryPlayer;func_70441_a(Lnet/minecraft/item/ItemStack;)Z"))
    private boolean pii$placeDisplacedStack(InventoryPlayer inventory, ItemStack stack) {
        final boolean previous = PIIContext.beginUncheckedInsert();
        try {
            return inventory.func_70441_a(stack);
        } finally {
            PIIContext.endUncheckedInsert(previous);
        }
    }
}
