package net.greatkorn.pickupintoinventory.mixins;

import net.greatkorn.pickupintoinventory.PIIContext;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The one call in the game that may be refused a slot. onCollideWithPlayer / func_70100_b_ hands
 * the stack to addItemStackToInventory inside the condition that decides whether the EntityItem
 * dies, so a false answer leaves the item lying on the ground exactly where it was - which is what
 * allowHotbarWhenInventoryFull=false is asking for, and the only place asking for it makes sense.
 *
 * The mark goes on the call rather than on the method, so a pickup-event handler running earlier in
 * onCollideWithPlayer - which may well insert something of its own and drop the answer, the way 25
 * mods in one pack do - is outside it. Everything not inside this redirect keeps the main-inventory
 * preference and never gets a refusal. See PIIContext.
 */
@Mixin(EntityItem.class)
public abstract class MixinEntityItem {

    @Redirect(
        method = "func_70100_b_(Lnet/minecraft/entity/player/EntityPlayer;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/InventoryPlayer;func_70441_a(Lnet/minecraft/item/ItemStack;)Z"))
    private boolean pii$collectFromGround(InventoryPlayer inventory, ItemStack stack) {
        final boolean previous = PIIContext.beginGroundPickup();
        try {
            return inventory.func_70441_a(stack);
        } finally {
            PIIContext.endGroundPickup(previous);
        }
    }
}
