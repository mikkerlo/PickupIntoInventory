package net.greatkorn.pickupintoinventory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

/**
 * Pushes back to the client the main-inventory slots a pickup actually changed, and then checks
 * that the client had nothing of its own in flight while it did so.
 *
 * 1.7.10 only guarantees delivery of the hotbar. NetHandlerPlayClient.handleSetSlot applies a
 * window-0 S2FPacketSetSlot to inventoryContainer unconditionally for container slots 36-44 (the
 * hotbar) and, for every other slot, only when the client's openContainer.windowId matches the
 * packet's - and, on window 0, only when the client is not sitting on a non-inventory creative tab,
 * which is a client-only state the server cannot see. EntityPlayerMP.sendSlotContents drops slot
 * packets outright while isChangingQuantityOnly is set, which vanilla itself does around every
 * accepted click and which several pack mods toggle around their own click handling.
 *
 * Vanilla mostly gets away with that because an empty hotbar slot wins every pickup, the one path
 * with a hard guarantee. Moving pickups into slots 9-35 puts them on the fragile path, so a stale
 * client shows the item missing until something rewrites the slots (pressing sort, for instance).
 *
 * <h3>What is resent</h3>
 *
 * Only the slots a pickup really touched. The mixin snapshots main inventory indices 9-35 around
 * addItemStackToInventory and diffs them afterwards, so a merge into an existing partial stack -
 * which never asks getFirstEmptyStack and so never went through the redirect at all - is tracked
 * exactly like a fresh placement. Hotbar indices 0-8 are deliberately left out: those the client
 * applies unconditionally, and during a click it has already predicted them itself.
 *
 * A changed slot is sent as a single S2FPacketSetSlot addressed through the container the player
 * actually has open (Container.getSlotFromInventory maps the inventory index to that container's
 * slot number), because a window whose id matches is applied client-side without further
 * conditions. Where handleSetSlot would drop the packet - a creative player, who may be on a tab
 * that gates window-0 updates, or an open container that does not show the player's inventory at
 * all - the fallback is a window-0 S30PacketWindowItems, which handleWindowItems always applies.
 * That packet is truncated after the highest slot that needs it: putStacksInSlots walks the array
 * it is given, so a 36-entry prefix repairs slots 0-35 and leaves the hotbar the player is most
 * likely to be clicking untouched. The cursor is never sent, so a drag in progress is safe.
 *
 * A prefix cannot start anywhere but zero, so that fallback also rewrites the crafting slots and
 * the armour, which no pickup ever touches and which nothing here marks. Overwriting a prediction
 * there is not silent, though: making one takes a click, a click moves the counter below, and the
 * round that follows sends the same prefix again with the server's contents for those slots.
 *
 * <h3>Why a confirmation round trip</h3>
 *
 * A repair packet describes the server's state at the moment it was built, and the client may have
 * moved on: it predicts every click locally and only afterwards learns whether the server agreed.
 * Writing a slot the client has already emptied by prediction leaves a ghost stack there - present
 * on the client, gone on the server - and nothing repairs it, because a click the server accepts is
 * acknowledged with isChangingQuantityOnly set, which suppresses the corrective slot packets and
 * advances the container's cached contents so later ticks see no difference either.
 *
 * Narrowing the slot list removes that race for unrelated slots but not for the repaired ones, and
 * the server cannot rule it out when it builds the packet - the conflicting click may still be in
 * transit. So the repair is verified after the fact. Immediately behind it goes an
 * S32PacketConfirmTransaction with accepted=false, which 1.7.10's client answers with a
 * C0FPacketConfirmTransaction and otherwise ignores entirely. Both directions of the connection are
 * ordered, and neither packet is processed off the server thread (neither overrides
 * Packet.hasPriority, so NetworkManager queues them for the tick loop), so any click the client
 * sent before it applied the repair is processed before that answer arrives. If our window-action
 * counter has not moved by then, nothing of the client's was outstanding while the repair landed
 * and the slots are known good; otherwise they stay marked and the repair is sent again on the next
 * quiet tick. Clicks predicted against a slot that is genuinely stale are rejected by vanilla
 * anyway - processClickWindow compares the client's pre-click view of the slot against the server's
 * - and a rejection resends the whole container, so the two mechanisms cover each other.
 *
 * Convergence, not instantaneity, is what this buys: while a player is clicking a repair may still
 * overwrite a prediction, but that is detected and undone within a round trip of them stopping.
 * If the answer never comes at all the round is abandoned after five seconds so the mechanism
 * cannot wedge.
 */
public final class PIISync {

    /** Vanilla main inventory. Slots past this are appended by other mods and are their business. */
    private static final int MAIN_END = 36;

    /** Indices 0-8 are the hotbar: guaranteed delivery already, and predicted during a click. */
    private static final int MAIN_FIRST = 9;

    /** Where the hotbar sits in inventoryContainer's slot numbering, which is not the same order. */
    private static final int HOTBAR_SLOT_FIRST = 36;
    private static final int HOTBAR_SLOT_END = 45;

    /** Ticks to wait for the confirmation before assuming it is not coming. */
    private static final int ANSWER_TIMEOUT = 100;

    /** Unanswered rounds to repeat before concluding this client will never answer at all. */
    private static final int ANSWER_ATTEMPTS = 3;

    private static final int NO_ANSWER = -1;

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<UUID, Pending>();

    /** How many ids to step over before accepting one anyway; the map holds at most one per window. */
    private static final int BARRIER_ATTEMPTS = 4;

    /** The innermost open frame on this thread, or the (closed) frame the thread reuses. */
    private static final ThreadLocal<Watch> WATCH = new ThreadLocal<Watch>();

    private PIISync() {}

    /**
     * The slots one addItemStackToInventory changed, worked out by comparing before and after
     * rather than by asking the redirect where it steered the item - the merge path never consults
     * the redirect. Reused per thread: in singleplayer the logical client and the logical server
     * run this on separate threads.
     *
     * Nesting-safe, the same way PIIContext is, and nesting comes in two shapes. A mod inserting a
     * second item into the *same* inventory from inside a pickup only raises the depth: the
     * outermost call is the one that snapshots and diffs, so everything both calls moved is marked
     * once. A mod inserting into a *different* inventory - party-share pickup, a magnet relaying to
     * a teammate, "send the overflow to whoever has room" - pushes a frame of its own. Holding one
     * slot per thread instead would let that inner call overwrite the outer player's snapshot and
     * then close it, and the outer call would return to find its own frame gone and mark nothing:
     * exactly the stale slot this mechanism exists to prevent, failing silently and for the player
     * who was not even the subject of the inner call.
     *
     * Frames are kept and reused rather than allocated per pickup, so the common unnested case
     * costs no allocation at all and a nesting depth costs one set of arrays for the life of the
     * thread.
     *
     * The only way out of addItemStackToInventory other than a return is the ReportedException
     * vanilla's own catch builds, which skips the return injection and would leave a frame
     * standing. Both ends handle that: endPickup unwinds past any frame that is not the one it
     * opened, diffing each rather than dropping it, and the tick handler closes whatever is still
     * open at a point where no pickup can be in progress.
     */
    private static final class Watch {

        InventoryPlayer inventory;
        int depth;
        final ItemStack[] before = new ItemStack[MAIN_END];
        final int[] sizes = new int[MAIN_END];

        /** The frame this one was pushed over. Null on the frame the thread starts from. */
        Watch outer;

        /** A frame already built to push over this one, kept so a second nesting costs nothing. */
        Watch inner;

        void close() {
            inventory = null;
            depth = 0;
            Arrays.fill(before, null); // nothing here should keep a stack alive
            // outer and inner survive: they are the pooling, not the state.
        }
    }

    /**
     * Per-player repair state. Written from the server thread only: pickups happen in entity
     * ticking and the window packets we count are queued by NetworkManager for the tick loop.
     * The answer field is volatile anyway, since a mod that dispatches packets early would
     * otherwise be free to publish it unsafely.
     */
    private static final class Pending {

        final boolean[] stale = new boolean[MAIN_END];
        int staleCount;

        /** The marks the repair now in flight was built from, held until it is answered for. */
        final boolean[] sent = new boolean[MAIN_END];

        /** Client-driven window actions seen so far; only differences between two reads matter. */
        final AtomicInteger actions = new AtomicInteger();

        /**
         * Starts at a count no read can produce, so the first tick after this record appears is
         * never mistaken for a quiet one. noteWindowAction drops actions while no record exists,
         * so a player clicking hard a tick before their first pickup would otherwise present a
         * fresh 0 == 0 and draw a repair straight into the click stream - which self-heals, at the
         * cost of the round it wastes.
         */
        int actionsLastTick = -1;
        int actionsAtRepair;

        volatile int actionsAtAnswer = NO_ANSWER;
        volatile boolean answerShared;

        // Read on the same path as actionsAtAnswer, so they carry the same defence: a mod that
        // dispatches packets early would otherwise publish these two unsafely while the field
        // beside them is protected, which is the worst of both.
        volatile short barrier;
        volatile boolean awaitingAnswer;

        /** The id the round before this one used, kept only so the next round can avoid it. */
        short lastBarrier;

        /** Counted per player rather than per process, so one player cannot walk another's ids. */
        short barrierSeq;

        int waited;
        int unanswered;

        void mark(int slot) {
            if (stale[slot]) return;
            stale[slot] = true;
            staleCount++;
        }

        /**
         * Hands the current marks to the repair about to go out. They are held apart from the
         * marks, not cleared with them: a pickup that changes one of the same slots again while
         * the repair is in flight has to be repaired again, and answering for the older packet
         * says nothing about the newer change.
         */
        void beginRepair() {
            System.arraycopy(stale, 0, sent, 0, MAIN_END);
            Arrays.fill(stale, false);
            staleCount = 0;
        }

        /** The repair could not be shown to have landed cleanly, so its slots go back on the list. */
        void restoreRepair() {
            for (int i = MAIN_FIRST; i < MAIN_END; i++) {
                if (sent[i]) mark(i);
            }
            dropRepair();
        }

        void dropRepair() {
            Arrays.fill(sent, false);
        }
    }

    // ------------------------------------------------------------------ tracking

    /** Called from the mixin as addItemStackToInventory is entered. */
    public static void beginPickup(InventoryPlayer inventory) {
        if (inventory == null || !tracks(inventory.field_70458_d)) return;

        Watch watch = WATCH.get();
        if (watch == null) {
            watch = new Watch();
            WATCH.set(watch);
        } else if (watch.inventory == inventory) {
            watch.depth++; // an inner call on the same inventory; the outer snapshot counts
            return;
        } else if (watch.inventory != null) {
            // An insert into someone else's inventory from inside this one. The frame underneath
            // stays exactly as it is, snapshot and all, and is current again the moment this one
            // closes.
            Watch pushed = watch.inner;
            if (pushed == null) {
                pushed = new Watch();
                pushed.outer = watch;
                watch.inner = pushed;
            }
            watch = pushed;
            WATCH.set(watch);
        }
        final ItemStack[] main = inventory.field_70462_a;
        final int limit = Math.min(main.length, MAIN_END);
        for (int i = MAIN_FIRST; i < MAIN_END; i++) {
            final ItemStack stack = i < limit ? main[i] : null;
            watch.before[i] = stack;
            watch.sizes[i] = stack == null ? 0 : stack.field_77994_a; // stackSize
        }
        watch.inventory = inventory;
        watch.depth = 1;
    }

    /** Called from the mixin as addItemStackToInventory returns; marks what actually moved. */
    public static void endPickup(InventoryPlayer inventory) {
        Watch watch = WATCH.get();
        if (watch == null) return;

        // Normally the top frame is the one this call opened. It is not when an inner insert threw
        // and never ran its own endPickup. Finding the frame first, then unwinding to it, means an
        // abandoned frame costs its own player a diff rather than costing the outer player their
        // marks - the old single-slot version simply returned here and left them unmarked.
        Watch owner = watch;
        while (owner != null && owner.inventory != inventory) owner = owner.outer;
        if (owner == null) return; // nothing here opened for this inventory

        while (watch != owner) {
            final Watch abandoned = watch;
            watch = abandoned.outer;
            closeFrame(abandoned);
        }
        if (--watch.depth > 0) return;
        closeFrame(watch);
    }

    /**
     * Marks whatever moved while this frame was open and makes the frame underneath current again.
     * The outermost frame is kept in place rather than dropped, closed but allocated, because it is
     * the one every unnested pickup on this thread reuses.
     */
    private static void closeFrame(Watch watch) {
        final InventoryPlayer inventory = watch.inventory;
        if (inventory != null) {
            final ItemStack[] main = inventory.field_70462_a;
            final int limit = Math.min(main.length, MAIN_END);
            for (int i = MAIN_FIRST; i < MAIN_END; i++) {
                final ItemStack stack = i < limit ? main[i] : null;
                // A slot changes either by being handed a different stack object - vanilla always
                // builds a new one - or by having its count raised, which is what a merge does.
                if (stack == watch.before[i]
                    && (stack == null || stack.field_77994_a == watch.sizes[i])) {
                    continue;
                }
                markStale(inventory.field_70458_d, i);
            }
        }
        watch.close();
        if (watch.outer != null) WATCH.set(watch.outer);
    }

    private static boolean tracks(EntityPlayer player) {
        if (!PIIConfig.resyncAfterPickup) return false;
        if (!(player instanceof EntityPlayerMP)) return false;
        if (player.field_70170_p == null || player.field_70170_p.field_72995_K) return false;
        // While the player has the redirect switched off their pickups land where vanilla would
        // have put them, and vanilla's own sync is as good or bad as it has always been.
        return PIIState.isEnabledFor(player);
    }

    private static void markStale(EntityPlayer player, int slot) {
        if (slot < MAIN_FIRST || slot >= MAIN_END || !tracks(player)) return;
        pendingFor(player.func_110124_au()).mark(slot);
    }

    private static Pending pendingFor(UUID id) {
        Pending pending = PENDING.get(id);
        if (pending != null) return pending;
        pending = new Pending();
        final Pending raced = PENDING.putIfAbsent(id, pending);
        return raced == null ? pending : raced;
    }

    // ------------------------------------------------------------- client feedback

    /**
     * Anything the client did to a window on its own initiative: a click, a creative slot edit, or
     * closing the window (which moves its open container back to the player's own, and so changes
     * where a repair packet has to be addressed). Counted, not inspected - a repair only has to
     * know whether the client was quiet, not what it did.
     */
    public static void noteWindowAction(EntityPlayerMP player) {
        if (player == null) return;
        final Pending pending = PENDING.get(player.func_110124_au());
        if (pending != null) pending.actions.incrementAndGet();
    }

    /**
     * The echo of the confirmation we send behind a repair.
     *
     * Answers are matched by id, and ids are shorts that both sides count up from zero, so one of
     * ours can end up naming a rejection of vanilla's. nextBarrier keeps clear of any rejection
     * already outstanding, which leaves only the reverse order - vanilla rejecting a click whose id
     * we are already waiting on - and there our confirmation was on the wire first, so the client
     * echoed it first. Taking the first echo of the id and letting the second through therefore
     * gives each side its own answer; this returns true for ours, which the caller keeps away from
     * vanilla so it cannot release a click block early on the strength of our packet.
     *
     * shared says vanilla is waiting on the same id even so. That should not happen, and the echo
     * is still accepted as the end of the round, but not as evidence: an answer that might be the
     * echo of a rejection would otherwise vouch for a repair the client has not yet applied.
     */
    public static boolean noteTransactionAck(EntityPlayerMP player, int windowId, short uid, boolean shared) {
        if (player == null || windowId != 0) return false;
        final Pending pending = PENDING.get(player.func_110124_au());
        if (pending == null || !pending.awaitingAnswer || uid != pending.barrier) return false;
        if (pending.actionsAtAnswer != NO_ANSWER) return false; // ours was taken already
        pending.answerShared = shared;
        pending.actionsAtAnswer = pending.actions.get();
        return true;
    }

    public static void register() {
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(new Handler());
    }

    // ------------------------------------------------------------------ repairing

    public static final class Handler {

        @SubscribeEvent
        public void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || event.side != Side.SERVER) return;

            // A frame still open here belongs to an addItemStackToInventory that never returned -
            // vanilla's crash handler throws out of it, and another mixin cancelling the call at
            // HEAD with setReturnValue skips the return injection too - so a mod that swallows
            // that would otherwise leave this thread unable to track another pickup. No pickup is
            // in progress at this point in the tick, so anything still open is finished with.
            //
            // This runs before the checks below rather than after them. Below, it is dead as soon
            // as the last tracked player logs out: PENDING goes empty, the early return fires
            // first, and a leaked frame keeps an InventoryPlayer - and through it an EntityPlayerMP
            // and its WorldServer - for the life of the process.
            for (Watch open = WATCH.get(); open != null && open.inventory != null;) {
                final Watch abandoned = open;
                open = abandoned.outer;
                closeFrame(abandoned);
            }

            if (PENDING.isEmpty() || !(event.player instanceof EntityPlayerMP)) return;

            final EntityPlayerMP player = (EntityPlayerMP) event.player;
            final Pending pending = PENDING.get(player.func_110124_au());
            if (pending == null) return;
            if (player.field_71135_a == null) return; // logging out; nothing to talk to
            if (!PIIConfig.resyncAfterPickup) { // switched off under us; stop making traffic
                PENDING.remove(player.func_110124_au(), pending);
                return;
            }

            final int actions = pending.actions.get();
            final boolean quiet = actions == pending.actionsLastTick;
            pending.actionsLastTick = actions;

            if (pending.awaitingAnswer && !settle(pending)) return;

            if (pending.staleCount == 0) return;
            // Repairing in the middle of a burst of clicks would only be undone again, and the
            // player is about to stop; the marks keep until then. This is why the record outlives
            // the round that emptied it: throw it away when it goes clean and the count goes with
            // it, and the next pickup starts from zero, reads as quiet however hard the player is
            // clicking, and fires a repair straight into the burst it was meant to wait out.
            if (!quiet) return;

            // Read before building anything: a click that lands while the packets are assembled
            // must count as having raced them.
            pending.actionsAtRepair = actions;
            pending.beginRepair();
            repair(player, pending);
            pending.lastBarrier = pending.barrier;
            pending.barrier = nextBarrier(player, pending);
            pending.actionsAtAnswer = NO_ANSWER;
            pending.answerShared = false;
            pending.awaitingAnswer = true;
            pending.waited = 0;
            player.field_71135_a.func_147359_a(
                new S32PacketConfirmTransaction(0, pending.barrier, false));
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            PENDING.remove(event.player.func_110124_au());
        }
    }

    /**
     * An id vanilla is itself waiting to hear about must not be reused: our answer would arrive
     * first and release a block vanilla is holding on purpose. See PIITransactions.
     *
     * Nor may the id of the round just before this one, whose echo can still be in flight when an
     * abandoned round starts another. Taking that echo as this round's answer is harmless; the
     * damage is the mirror case, where the id has meanwhile become one vanilla rejected a click
     * with, our cancel eats the echo, func_75128_a(player, false) is never undone, and the player
     * cannot click their own inventory again until they respawn - ContainerPlayer is built once
     * per EntityPlayerMP. Improbable and silent, and one comparison to rule out.
     *
     * The counter is the player's own, so the wrap that makes any of this reachable at all is
     * 65536 rounds for that one player rather than 65536 shared across everyone online.
     */
    private static short nextBarrier(EntityPlayerMP player, Pending pending) {
        final PIITransactions handler = player.field_71135_a instanceof PIITransactions
            ? (PIITransactions) player.field_71135_a
            : null;
        short id = ++pending.barrierSeq;
        for (int i = 0; i < BARRIER_ATTEMPTS && taken(handler, pending, id); i++) {
            id = ++pending.barrierSeq;
        }
        return id;
    }

    private static boolean taken(PIITransactions handler, Pending pending, short id) {
        if (id == pending.lastBarrier) return true;
        return handler != null && handler.pii$awaits(0, id);
    }

    /**
     * @return false while the outstanding round is still unresolved and nothing else should happen.
     */
    private static boolean settle(Pending pending) {
        final int answered = pending.actionsAtAnswer;
        if (answered == NO_ANSWER) {
            if (++pending.waited <= ANSWER_TIMEOUT) return false;
            // Silence is not proof the repair landed, and treating it as proof throws away exactly
            // the slot this exists to fix - a client stalled past five seconds by a chunk-gen hitch
            // or a collection pause would keep the stale stack for good. So the marks come back and
            // the repair goes again. Only a client that answers nothing at all, several rounds
            // running, is written off, so a client that cannot answer does not draw packets forever.
            if (++pending.unanswered >= ANSWER_ATTEMPTS) pending.dropRepair();
            else pending.restoreRepair();
        } else if (answered == pending.actionsAtRepair && !pending.answerShared) {
            // The client had nothing of its own outstanding while the repair was applied, so what
            // it now shows for those slots is what the server sent.
            pending.unanswered = 0;
            pending.dropRepair();
        } else {
            pending.unanswered = 0;
            pending.restoreRepair();
        }
        pending.awaitingAnswer = false;
        pending.actionsAtAnswer = NO_ANSWER;
        return true;
    }

    private static void repair(EntityPlayerMP player, Pending pending) {
        final InventoryPlayer inventory = player.field_71071_by;
        final Container open = player.field_71070_bA; // openContainer
        final Container own = player.field_71069_bz; // inventoryContainer
        final int window = open.field_75152_c; // windowId
        final boolean creative = player.field_71075_bZ != null && player.field_71075_bZ.field_75098_d;

        int snapshotUpTo = -1;
        for (int i = MAIN_FIRST; i < MAIN_END; i++) {
            if (!pending.sent[i]) continue;

            final Slot shown = open.func_75147_a(inventory, i); // getSlotFromInventory
            if (shown != null && applied(window, shown.field_75222_d, creative)) {
                player.field_71135_a.func_147359_a(
                    new S2FPacketSetSlot(window, shown.field_75222_d, shown.func_75211_c()));
                continue;
            }
            final Slot ownSlot = own.func_75147_a(inventory, i);
            if (ownSlot == null) continue; // nothing on the client is showing this slot
            if (ownSlot.field_75222_d > snapshotUpTo) snapshotUpTo = ownSlot.field_75222_d;
        }

        if (snapshotUpTo < 0) return;
        final List<?> stacks = own.func_75138_a(); // getInventory
        player.field_71135_a.func_147359_a(
            new S30PacketWindowItems(0, stacks.subList(0, Math.min(snapshotUpTo + 1, stacks.size()))));
    }

    /** Whether handleSetSlot is certain to apply a slot packet, rather than dropping it. */
    private static boolean applied(int window, int slotNumber, boolean creative) {
        // A matching non-zero window is applied to the open container with no further test, and
        // the ids match: the server is the one that handed this window out.
        if (window != 0) return true;
        // Window 0 slots 36-44 go to inventoryContainer whatever the client has on screen.
        if (slotNumber >= HOTBAR_SLOT_FIRST && slotNumber < HOTBAR_SLOT_END) return true;
        // The remaining window-0 slots are dropped while a creative non-inventory tab is open. The
        // server cannot see which tab that is, only that opening the screen at all takes creative
        // mode - which it can see, bar the corner where a player is taken out of creative with the
        // screen still up, and there the snapshot is simply not sent.
        return !creative;
    }
}
