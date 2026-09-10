package net.greatkorn.pickupintoinventory;

/**
 * Marks the stretch of a call in which refusing a slot is safe - that is, in which the caller still
 * has the item and will keep it when addItemStackToInventory answers false.
 *
 * There is exactly one such caller, and the mark is deliberately that way round. "Leave it on the
 * ground" only means anything where there is a ground to leave it on, which is
 * EntityItem.onCollideWithPlayer and nothing else: it checks the result and lets the EntityItem
 * live when the insert failed. Every other caller either checks the result and puts the item
 * somewhere of its own choosing - where refusing changes nothing we have any business changing - or
 * throws the result away, and there refusing destroys the stack outright.
 *
 * Naming the unsafe callers instead was the obvious first answer, because in vanilla there are only
 * two: Container.slotClick's number-key swap, which has already overwritten the hotbar slot with the
 * clicked container item before handing over the stack displaced from it, and ItemPotion.onEaten
 * returning the empty bottle. Mod code does not stay that small. A scan of one 243-jar pack turned
 * up 36 discarding call sites across 25 mods, among them bogosorter, Thaumcraft, CoFH, TConstruct,
 * LogisticsPipes, Witchery and GregTech - so a list of who must not be refused is a list that has to
 * be rewritten every time the pack changes, and is wrong in the player's favour only by accident.
 * A list of who may be refused is one caller, in Minecraft, and cannot go stale.
 *
 * What it costs is that allowHotbarWhenInventoryFull=false no longer holds for an item a mod hands
 * over directly - a magnet, a quest reward, a machine emptying into the player - which takes the
 * hotbar slot instead of being left where it was. That is the trade: the setting is weaker in
 * places it was never reliable anyway, and no configuration of this mod can delete an item.
 *
 * Thread-local: in singleplayer the logical client and the logical server run this code on separate
 * threads, and only the server ever picks anything up off the ground. Nesting-safe, so a mod
 * wrapping the same call cannot clear the mark early.
 */
public final class PIIContext {

    private static final ThreadLocal<Boolean> GROUND_PICKUP = new ThreadLocal<Boolean>();

    private PIIContext() {}

    /** @return the value to hand back to {@link #endGroundPickup(boolean)} when the call returns. */
    public static boolean beginGroundPickup() {
        final boolean previous = isGroundPickup();
        GROUND_PICKUP.set(Boolean.TRUE);
        return previous;
    }

    public static void endGroundPickup(boolean previous) {
        if (previous) GROUND_PICKUP.set(Boolean.TRUE);
        else GROUND_PICKUP.remove();
    }

    /** True while a refused insertion would leave the item where it was rather than destroy it. */
    public static boolean isGroundPickup() {
        return Boolean.TRUE.equals(GROUND_PICKUP.get());
    }
}
