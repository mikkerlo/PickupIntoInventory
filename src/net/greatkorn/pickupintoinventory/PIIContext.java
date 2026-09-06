package net.greatkorn.pickupintoinventory;

/**
 * Marks the stretch of a call where the caller <em>discards</em> the boolean that
 * addItemStackToInventory returns. There, refusing a slot does not leave the item anywhere - it
 * deletes it.
 *
 * Container.slotClick's number-key swap (mode 2) is the case that matters. It asks the unmodified
 * getFirstEmptyStack for a slot, overwrites the selected hotbar slot with the clicked container
 * item, and only then hands the stack that used to live there to addItemStackToInventory,
 * discarding the boolean - it already checked, so as far as it is concerned a slot is waiting.
 * Answering -1 at that point, which allowHotbarWhenInventoryFull=false otherwise does once slots
 * 9-35 are full, destroys that stack: it is not in the inventory, not on the cursor, not on the
 * ground. ItemPotion.onEaten does the same with the empty bottle it hands back after a drink.
 *
 * (Those are the only two: every other addItemStackToInventory call in 1.7.10 - EntityItem,
 * SlotCrafting, ItemBucket, ItemGlassBottle, ItemEmptyMap, EntityArrow, EntityCow, EntityMooshroom,
 * BlockCauldron - branches on the result and keeps the item when it is false.)
 *
 * "Leave it on the ground" is a pickup policy and only holds where the caller still has an item to
 * leave. Inside this marker the mixin keeps its main-inventory preference but never answers -1, so
 * the empty slot vanilla already counted on is used and nothing is lost.
 *
 * Thread-local: in singleplayer the logical client and the logical server run this code on separate
 * threads. Nesting-safe, so a mod wrapping the same call cannot clear the mark early.
 */
public final class PIIContext {

    private static final ThreadLocal<Boolean> UNCHECKED = new ThreadLocal<Boolean>();

    private PIIContext() {}

    /** @return the value to hand back to {@link #endUncheckedInsert(boolean)} when the call returns. */
    public static boolean beginUncheckedInsert() {
        final boolean previous = isUncheckedInsert();
        UNCHECKED.set(Boolean.TRUE);
        return previous;
    }

    public static void endUncheckedInsert(boolean previous) {
        if (previous) UNCHECKED.set(Boolean.TRUE);
        else UNCHECKED.remove();
    }

    /** True while a refused insertion would delete the stack rather than leave it where it was. */
    public static boolean isUncheckedInsert() {
        return Boolean.TRUE.equals(UNCHECKED.get());
    }
}
