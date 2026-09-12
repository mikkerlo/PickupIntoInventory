package net.greatkorn.pickupintoinventory;

import net.minecraft.item.ItemStack;

/**
 * Marks the one insertion in which refusing a slot is safe - that is, in which the caller still has
 * the item and will keep it when addItemStackToInventory answers false.
 *
 * The mark is deliberately this way round. "Leave it on the ground" only means anything where there
 * is a ground to leave it on, and in Minecraft that is exactly two callers: EntityItem and
 * EntityArrow, both in onCollideWithPlayer, both of which check the result and let the entity live
 * when the insert failed. Every other caller either checks the result and puts the item somewhere
 * of its own choosing - where refusing changes nothing we have any business changing - or throws
 * the result away, and there refusing destroys the stack outright.
 *
 * Naming the unsafe callers instead was the obvious first answer, because in vanilla there are only
 * two: Container.slotClick's number-key swap, which has already overwritten the hotbar slot with the
 * clicked container item before handing over the stack displaced from it, and ItemPotion.onEaten
 * returning the empty bottle. Mod code does not stay that small - a discarding
 * addItemStackToInventory with no check on the result is a common idiom in backpack returns,
 * machine output-to-player and quest rewards - so a list of who must not be refused has to be
 * rewritten every time the pack changes, and is right in the player's favour only by accident. A
 * list of who may be refused is two callers, in Minecraft, and cannot go stale.
 *
 * What it costs is that allowHotbarWhenInventoryFull=false no longer holds for an item a mod hands
 * over directly - a magnet, a quest reward, a machine emptying into the player - which takes the
 * hotbar slot instead of being left where it was. That is the trade: the setting is weaker in
 * places it was never reliable anyway, and no configuration of this mod can delete an item.
 *
 * <h3>Why the mark is a stack and not a flag</h3>
 *
 * A flag marks a stretch of the call, and everything reached from inside that stretch inherits it.
 * A mod handling EntityItemPickupEvent, or wrapping the insert, that inserts a *second* item of its
 * own from in there would have that second insert read as a ground pickup and be refusable - and
 * that caller may well be one of the discarding ones, which is the deletion this whole change
 * exists to remove. Holding the ItemStack the mark was made for and comparing identity keeps it to
 * the one insertion it was made for. Vanilla builds a fresh ItemStack per pickup and hands over
 * that same reference, so identity is exactly the right test.
 *
 * Thread-local: in singleplayer the logical client and the logical server run this code on separate
 * threads, and only the server ever picks anything up off the ground. Nesting-safe, so a mod
 * wrapping the same call cannot clear the mark early.
 */
public final class PIIContext {

    private static final ThreadLocal<ItemStack> GROUND_PICKUP = new ThreadLocal<ItemStack>();

    private PIIContext() {}

    /** @return the value to hand back to {@link #endGroundPickup(ItemStack)} when the call returns. */
    public static ItemStack beginGroundPickup(ItemStack stack) {
        final ItemStack previous = GROUND_PICKUP.get();
        GROUND_PICKUP.set(stack);
        return previous;
    }

    public static void endGroundPickup(ItemStack previous) {
        if (previous == null) GROUND_PICKUP.remove();
        else GROUND_PICKUP.set(previous);
    }

    /** True while refusing <em>this</em> stack would leave it where it was rather than destroy it. */
    public static boolean isGroundPickup(ItemStack stack) {
        return stack != null && GROUND_PICKUP.get() == stack;
    }
}
