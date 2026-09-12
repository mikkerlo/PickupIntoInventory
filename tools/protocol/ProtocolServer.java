import java.util.Arrays;

/**
 * The server half: vanilla's own container sync, vanilla's click handling and transaction block,
 * and a transcription of PIISync's per-player round.
 *
 * The two feature switches are what make the regressions reproducible. repair=false is the
 * behaviour before #4: vanilla sync only. confirm=false is the repair without the round trip
 * behind it, which is where a repair can overwrite a prediction and never learn that it did.
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

    private final Link toClient;

    // ------------------------------------------------------------ vanilla transaction state

    private short vanillaSeq;
    private short vanillaUid;
    private boolean vanillaAwaiting;
    private boolean clickBlocked;

    // ------------------------------------------------------------ PIISync.Pending

    private boolean hasPending;
    private final boolean[] stale = new boolean[Model.MAIN_END];
    private int staleCount;
    private final boolean[] sent = new boolean[Model.MAIN_END];
    private int actions;
    private int actionsLastTick = -1;
    private int actionsAtRepair;
    private int actionsAtAnswer = NO_ANSWER;
    private boolean answerShared;
    private short barrier;
    private boolean awaitingAnswer;
    private short lastBarrier;
    private short barrierSeq;
    private int waited;
    private int unanswered;

    int rounds;

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
            mark(i);
            return i;
        }
        return -1;
    }

    /** A merge into an existing partial stack: never asks getFirstEmptyStack, still marked. */
    boolean merge(int index) {
        if (inv[index] == 0) return false;
        inv[index] = inv[index] + 1; // count is the low byte
        mark(index);
        return true;
    }

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
            default: throw new IllegalStateException("client received its own packet kind");
        }
    }

    private void click(Pkt p, int now) {
        if (hasPending) actions++; // PIISync.noteWindowAction, which drops what has no record

        final int index = Model.inventoryIndex(p.slot);
        if (index < 0) return;

        if (clickBlocked || inv[index] != p.expect) {
            // Vanilla rejects: block further clicking until the echo comes back, tell the client
            // no, and resend the whole container and the cursor.
            vanillaUid = ++vanillaSeq;
            vanillaAwaiting = true;
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
        final boolean shared = vanillaAwaiting && p.uid == vanillaUid;
        if (confirm && noteTransactionAck(p.window, p.uid, shared)) return; // ours; vanilla never sees it
        if (shared) {
            vanillaAwaiting = false;
            clickBlocked = false;
        }
    }

    private boolean noteTransactionAck(int windowId, short uid, boolean shared) {
        if (windowId != 0) return false;
        if (!hasPending || !awaitingAnswer || uid != barrier) return false;
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
        return vanillaAwaiting && id == vanillaUid;
    }

    private void beginRepair() {
        System.arraycopy(stale, 0, sent, 0, Model.MAIN_END);
        Arrays.fill(stale, false);
        staleCount = 0;
    }

    private void restoreRepair() {
        for (int i = Model.MAIN_FIRST; i < Model.MAIN_END; i++) {
            if (!sent[i] || stale[i]) continue;
            stale[i] = true;
            staleCount++;
        }
        dropRepair();
    }

    private void dropRepair() { Arrays.fill(sent, false); }

    private void sendRepair(int now) {
        int snapshotUpTo = -1;
        for (int i = Model.MAIN_FIRST; i < Model.MAIN_END; i++) {
            if (!sent[i]) continue;
            final int slotNumber = Model.slotNumber(i);
            if (applied(0, slotNumber, creative)) {
                toClient.send(Pkt.setSlot(0, slotNumber, inv[i]), now);
                continue;
            }
            if (slotNumber > snapshotUpTo) snapshotUpTo = slotNumber;
        }
        if (snapshotUpTo < 0) return;
        toClient.send(Pkt.windowItems(0, contents(snapshotUpTo + 1)), now);
    }

    private static boolean applied(int window, int slotNumber, boolean creative) {
        if (window != 0) return true;
        if (slotNumber >= 36 && slotNumber < 45) return true;
        return !creative;
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
}
