package net.greatkorn.pickupintoinventory;

/**
 * What the connection is already waiting to hear from the client, so our own confirmation cannot be
 * mistaken for it - in either direction.
 *
 * Transaction ids are shorts, and both sides count them up from zero: the client's come from its
 * container's counter, one per click, and ours come from one per repair round. Two counters of
 * similar size drift past each other, so a collision is a matter of when, not whether.
 *
 * It matters because of what a collision would do to vanilla. When the server rejects a click it
 * stops accepting any further click from that player - Container.setPlayerIsPresent(player, false) -
 * until the client answers for the rejection, which is exactly what stops it acting on clicks the
 * client predicted before the corrective resend reached it. That resend goes out after the
 * rejection, but our confirmation may already be on the wire ahead of it: an answer carrying the
 * rejected id would release the block a full round trip early, and the clicks it then lets through
 * are the ones vanilla meant to drop.
 *
 * Implemented by MixinNetHandlerPlayServer over the map of rejected transactions it keeps.
 */
public interface PIITransactions {

    /** Whether an answer with this id and window would satisfy a rejection vanilla is waiting on. */
    boolean pii$awaits(int windowId, short uid);
}
