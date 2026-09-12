import java.util.Arrays;

/**
 * The server half: vanilla's own container sync, vanilla's click handling and transaction block,
 * and a transcription of PIISync's per-player round.
 *
 * The three feature switches are what make the regressions reproducible. repair=false is the
 * behaviour before #4: vanilla sync only. confirm=false is the repair without the round trip
 * behind it, which is where a repair can overwrite a prediction and never learn that it did.
 * perSlot=true is the first draft of the repair, which addressed each marked slot with its own
 * S2FPacketSetSlot and only fell back to a prefix when the server could see a reason the client
 * would drop it - a reason the server cannot always see.
 */
final class ServerModel {

    static final int NO_ANSWER = -1;
    static final int ANSWER_TIMEOUT = 100;
    static final int ANSWER_ATTEMPTS = 3;
    static final int BARRIER_ATTEMPTS = 4;

    final int[] inv = new int[36];
    int cursor;

    /** Container.inventoryItemStacks: what detectAndSendChanges believes the client has. */
    private final int[] cached = new int[Model.SLOTS];

    /** EntityPlayerMP.isChangingQuantityOnly, which makes sendSlotContents a no-op. */
    private boolean changingQuantityOnly;

    boolean creative;

    final boolean repair;
    final boolean confirm;
    boolean perSlot;

    private final Link toClient;

    // ------------------------------------------------------------ vanilla transaction state

    private short vanillaSeq;

    /**
     * NetHandlerPlayServer.field_147372_n, the per-window id vanilla rejected a click with. It is
     * written on a rejection and read by func_147339_a, which re-enables crafting and leaves the
     * entry where it is - so once a click has been rejected the entry stays for the rest of the
     * session. That is why this is two fields and not one: hasUid is the map entry, clickBlocked is
     * Container.getCanCraft, and pii$awaits reads the former. The guard therefore keeps refusing
     * that id long after the block it belonged to lifted, which costs nothing and is not something
     * the suite can assert from outside: with the guard in place no barrier ever reaches it.
     */
    private short vanillaUid;
    private boolean vanillaHasUid;
    private boolean clickBlocked;

    // ------------------------------------------------------------ PIISync.Pending

    private boolean hasPending;
    private final boolean[] stale = new boolean[Model.MAIN_END];
    private int staleCount;
    private final boolean[] sent = new boolean[Model.MAIN_END];
    private int actions;
    private int closes;
    private int actionsLastTick = -1;
    private int actionsAtRepair;
    private int actionsAtAnswer = NO_ANSWER;
    private boolean answerShared;
    private short barrier;
    private boolean awaitingAnswer;
    private short lastBarrier;

    /**
     * Starts where PIISync's does. Vanilla's ids are Container.func_75136_a on the client, counting
     * up from 1 per container, so the far end of the range is the cheapest place to be.
     */
    private short barrierSeq = Short.MIN_VALUE;
    private int waited;
    private int unanswered;

    int rounds;

    /**
     * Counted here rather than off the wire because only this side knows which S30 is a repair:
     * vanilla's own rejection path sends a full-length one too, and the harness used to tell the two
     * apart by length - which is exactly the test a hotbar mark breaks, because then the repair is
     * full length as well. The link is lossless, so what is emitted is what arrives.
     */
    int repairPrefixes;
    int longestRepairPrefix;

    /** How many times noteForeignSlot was given a hotbar index, which nothing else can mark. */
    int foreignHotbarMarks;

    ServerModel(Link toClient, boolean repair, boolean confirm) {
        this.toClient = toClient;
        this.repair = repair;
        this.confirm = confirm;
    }

    /** Puts both sides in the same state without any packets, for the start of a scenario. */
    void seed(int index, int stack, ClientModel client) {
        inv[index] = stack;
        cached[Model.slotNumber(index)] = stack;
        client.inv[index] = stack;
    }

    // ------------------------------------------------------------------ pickups

    /** addItemStackToInventory landing in the first empty main slot, and the watch around it. */
    int pickup(int stack) {
        for (int i = Model.MAIN_FIRST; i < Model.MAIN_END; i++) {
            if (inv[i] != 0) continue;
            inv[i] = stack;
            markStale(i);
            return i;
        }
        return -1;
    }

    /** A merge into an existing partial stack: never asks getFirstEmptyStack, still marked. */
    boolean merge(int index) {
        if (inv[index] == 0) return false;
        inv[index] = inv[index] + 1; // count is the low byte
        markStale(index);
        return true;
    }

    /**
     * PIISync.noteForeignSlot: one slot a client not running this side's policy is believed to have
     * written itself, or to have left empty where this side filled it. It is the only thing that
     * marks the hotbar - 0-8 are otherwise left out because the client applies those unconditionally
     * and predicts them itself during a click, and here the prediction is exactly what is wrong.
     *
     * What the model does not simulate is the divergence itself: click() here is a cursor swap, not
     * Container.slotClick's number-key swap, and this side does no routing at all. A scenario writes
     * the client's array by hand and then calls this, which is the state the redirect hands over and
     * the only part the repair has any say in.
     */
    void noteForeignSlot(int index) {
        if (index < 0 || index >= Model.MAIN_END) return;
        if (index < Model.MAIN_FIRST) foreignHotbarMarks++;
        mark(index);
    }

    /** PIISync.markStale: the pickup diff, which starts at MAIN_FIRST and never sees the hotbar. */
    private void markStale(int slot) {
        if (slot < Model.MAIN_FIRST || slot >= Model.MAIN_END) return;
        mark(slot);
    }

    /** Pending.mark, which takes any index either caller hands it. */
    private void mark(int slot) {
        if (!repair) return;
        hasPending = true;
        if (stale[slot]) return;
        stale[slot] = true;
        staleCount++;
    }

    // ------------------------------------------------------------------ inbound

    void receive(Pkt p, int now) {
        switch (p.kind) {
            case Pkt.C0E: click(p, now); return;
            case Pkt.C0F: confirmAck(p); return;
            case Pkt.C0D: close(); return;
            default: throw new IllegalStateException("client received its own packet kind");
        }
    }

    /**
     * C0DPacketCloseWindow. pii$noteClose counts it exactly as it counts a click, and nothing else
     * here cares: the window the client is leaving was never the one a repair is addressed to.
     */
    private void close() {
        if (hasPending) actions++;
        closes++;
    }

    private void click(Pkt p, int now) {
        if (hasPending) actions++; // PIISync.noteWindowAction, at HEAD, before any of vanilla's tests

        final int index = Model.inventoryIndex(p.slot);
        if (index < 0) return;

        if (clickBlocked) {
            // func_147351_a guards its whole body on getCanCraft. A click that arrives while the
            // block is up is dropped where it stands: no echo, no rejection, no resend. The client
            // has already predicted the swap locally and will never hear that it did not happen,
            // which is a vanilla desync the repair neither causes nor claims to fix - see the note
            // on the driver in ProtocolHarness.
            return;
        }

        if (inv[index] != p.expect) {
            // Vanilla rejects: block further clicking until the echo comes back, tell the client
            // no, and resend the whole container and the cursor.
            vanillaUid = ++vanillaSeq;
            vanillaHasUid = true;
            clickBlocked = true;
            toClient.send(Pkt.confirm(Pkt.S32, 0, vanillaUid, false), now);
            toClient.send(Pkt.windowItems(0, contents(Model.SLOTS)), now);
            toClient.send(Pkt.setSlot(-1, -1, cursor), now);
            syncCache();
            return;
        }

        final int held = cursor;
        cursor = inv[index];
        inv[index] = held;
        toClient.send(Pkt.confirm(Pkt.S32, 0, ++vanillaSeq, true), now);
        // The accepted-click path runs detectAndSendChanges with the suppression flag set. The
        // cache is written either way, so anything changed while it is set is never sent at all.
        changingQuantityOnly = true;
        detectAndSendChanges(now);
        changingQuantityOnly = false;
    }

    private void confirmAck(Pkt p) {
        // pii$awaits reads the map, which outlives the block - so an echo can be "shared" by id
        // while nothing is actually waiting on it. That is the conservative direction: the repair
        // treats the answer as possibly vanilla's and redoes the round.
        final boolean shared = vanillaHasUid && p.uid == vanillaUid;
        if (confirm && noteTransactionAck(p.window, p.uid, shared)) return; // ours; vanilla never sees it
        if (shared) clickBlocked = false; // func_147339_a re-enables crafting and leaves the entry
    }

    private boolean noteTransactionAck(int windowId, short uid, boolean shared) {
        if (windowId != 0) return false;
        if (!hasPending || !awaitingAnswer || uid != barrier) return false;
        // PIISync does this as one compareAndSet on an AtomicLong carrying both fields, because
        // there the two echoes of a shared id arrive on the same thread but the write is read from
        // another. Here there is one thread and one queue, so the test and the set are the same
        // thing; what is being modelled is which echo wins, and that is the first.
        if (actionsAtAnswer != NO_ANSWER) return false;
        answerShared = shared;
        actionsAtAnswer = actions;
        return true;
    }

    // ------------------------------------------------------------------ the tick

    void tick(int now) {
        detectAndSendChanges(now); // EntityPlayerMP.onUpdate
        if (!repair || !hasPending) return;

        final int seen = actions;
        final boolean quiet = seen == actionsLastTick;
        actionsLastTick = seen;

        if (awaitingAnswer && !settle()) return;
        if (staleCount == 0) return;
        if (!quiet) return;

        actionsAtRepair = seen;
        beginRepair();
        sendRepair(now);
        rounds++;
        if (!confirm) { dropRepair(); return; }

        lastBarrier = barrier;
        barrier = nextBarrier();
        actionsAtAnswer = NO_ANSWER;
        answerShared = false;
        awaitingAnswer = true;
        waited = 0;
        toClient.send(Pkt.confirm(Pkt.S32, 0, barrier, false), now);
    }

    /** Container.detectAndSendChanges: cache first, then send, and only when not suppressed. */
    private void detectAndSendChanges(int now) {
        for (int slotNumber = 0; slotNumber < Model.SLOTS; slotNumber++) {
            final int stack = at(slotNumber);
            if (stack == cached[slotNumber]) continue;
            cached[slotNumber] = stack;
            if (!changingQuantityOnly) toClient.send(Pkt.setSlot(0, slotNumber, stack), now);
        }
    }

    private void syncCache() {
        for (int slotNumber = 0; slotNumber < Model.SLOTS; slotNumber++) cached[slotNumber] = at(slotNumber);
    }

    private boolean settle() {
        final int answered = actionsAtAnswer;
        if (answered == NO_ANSWER) {
            if (++waited <= ANSWER_TIMEOUT) return false;
            if (++unanswered >= ANSWER_ATTEMPTS) dropRepair();
            else restoreRepair();
        } else if (answered == actionsAtRepair && !answerShared) {
            unanswered = 0;
            dropRepair();
        } else {
            unanswered = 0;
            restoreRepair();
        }
        awaitingAnswer = false;
        actionsAtAnswer = NO_ANSWER;
        return true;
    }

    private short nextBarrier() {
        short id = ++barrierSeq;
        for (int i = 0; i < BARRIER_ATTEMPTS && taken(id); i++) id = ++barrierSeq;
        return id;
    }

    private boolean taken(short id) {
        if (id == lastBarrier) return true;
        return vanillaHasUid && id == vanillaUid;
    }

    private void beginRepair() {
        System.arraycopy(stale, 0, sent, 0, Model.MAIN_END);
        Arrays.fill(stale, false);
        staleCount = 0;
    }

    private void restoreRepair() {
        // From zero, with sendRepair below and for the same reason: noteForeignSlot marks 0-8, and
        // a mark this loop skips is one the round quietly drops instead of putting back.
        for (int i = 0; i < Model.MAIN_END; i++) {
            if (!sent[i] || stale[i]) continue;
            stale[i] = true;
            staleCount++;
        }
        dropRepair();
    }

    private void dropRepair() { Arrays.fill(sent, false); }

    /**
     * One window-0 S30 truncated after the highest marked slot. handleWindowItems applies a window-0
     * packet to inventoryContainer with no test at all, so this is the one address whose delivery
     * the server can reason about without knowing what is on screen.
     *
     * From zero, not from MAIN_FIRST. The pickup diff never marks a hotbar index, but
     * noteForeignSlot does, and a mark the loop starts above is worth nothing: the round still goes
     * out, the echo still settles it clean, and dropRepair throws the mark away. The bound is also
     * the cost: index 0-8 numbers to 36-44, so one hotbar mark takes the prefix to the whole
     * container rather than to the ten or so slots a pickup needs.
     */
    private void sendRepair(int now) {
        if (perSlot) { sendRepairPerSlot(now); return; }

        int snapshotUpTo = -1;
        for (int i = 0; i < Model.MAIN_END; i++) {
            if (!sent[i]) continue;
            final int slotNumber = Model.slotNumber(i);
            if (slotNumber > snapshotUpTo) snapshotUpTo = slotNumber;
        }
        if (snapshotUpTo < 0) return;
        sendRepairPrefix(snapshotUpTo, now);
    }

    /**
     * The first draft: a per-slot S2F wherever the server believed the client would apply it, and a
     * prefix only for the slots it believed would be dropped. The belief is the bug. `creative` is
     * the server's own gamemode flag; the flag that decides the drop is the client's open screen,
     * which a GuiContainerCreative outlives its gamemode by, and which the server cannot see. When
     * the two disagree this sends an S2F the client silently discards, the barrier behind it is
     * answered out of inventoryContainer regardless, the round reports clean, and dropRepair throws
     * the marks away for good.
     */
    private void sendRepairPerSlot(int now) {
        int snapshotUpTo = -1;
        for (int i = 0; i < Model.MAIN_END; i++) {
            if (!sent[i]) continue;
            final int slotNumber = Model.slotNumber(i);
            if (!creative) {
                toClient.send(Pkt.setSlot(0, slotNumber, inv[i]), now);
                continue;
            }
            if (slotNumber > snapshotUpTo) snapshotUpTo = slotNumber;
        }
        if (snapshotUpTo < 0) return;
        sendRepairPrefix(snapshotUpTo, now);
    }

    private void sendRepairPrefix(int snapshotUpTo, int now) {
        final int[] prefix = contents(snapshotUpTo + 1);
        repairPrefixes++;
        if (prefix.length > longestRepairPrefix) longestRepairPrefix = prefix.length;
        toClient.send(Pkt.windowItems(0, prefix), now);
    }

    private int[] contents(int upTo) {
        final int[] out = new int[Math.min(upTo, Model.SLOTS)];
        for (int slotNumber = 0; slotNumber < out.length; slotNumber++) out[slotNumber] = at(slotNumber);
        return out;
    }

    private int at(int slotNumber) {
        final int index = Model.inventoryIndex(slotNumber);
        return index < 0 ? 0 : inv[index];
    }

    boolean settled() { return !awaitingAnswer && staleCount == 0; }

    /** Whether vanilla is still holding this player's own container shut, waiting for an echo. */
    boolean blocked() { return clickBlocked; }

    int closes() { return closes; }

    // ------------------------------------------------------------------ test hooks
    //
    // The barrier counter is 16 bits and moves one step per round, so the id it collides with is
    // 65536 rounds away - far enough that no scenario of a plausible length reaches the guard by
    // playing fairly. These place the counter next to a collision directly, which is the same
    // arithmetic the wrap would have produced.

    void seedBarrierSeq(short value) { barrierSeq = value; }

    short barrier() { return barrier; }

    short previousBarrier() { return lastBarrier; }

    short vanillaUid() { return vanillaUid; }

    boolean vanillaHasUid() { return vanillaHasUid; }
}
