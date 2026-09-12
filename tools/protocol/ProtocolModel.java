import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A model of the 1.7.10 inventory protocol, and of the repair PIISync layers on top of it.
 *
 * What this is: the client and server halves of the packet exchange, written from the disassembly
 * of NetHandlerPlayClient.func_147266_a / func_147241_a / func_147239_a,
 * NetHandlerPlayServer.func_147351_a, EntityPlayerMP.func_71111_a and Container.func_75142_b, plus
 * a transcription of PIISync's round state machine. Two machines, an ordered link with a latency,
 * and a driver that interleaves pickups and clicks.
 *
 * What it is not: PIISync. The server half here is a hand transcription and can drift from the real
 * class - it exists so the convergence claim can be re-run and argued with, not so it can stand in
 * for reading the code. Nothing below imports Minecraft, so nothing below proves the SRG names or
 * the injection points are right; that is what the javap checks in the PR description are for.
 *
 * Stacks are ints: (id << 8) | count, with 0 for empty. Equality is int equality, which is what
 * ItemStack.areItemStacksEqual comes to for the comparison processClickWindow makes.
 *
 * Slot numbering follows ContainerPlayer: container slots 0-8 are the crafting grid and armour
 * (not modelled, always empty), 9-35 are main inventory indices 9-35, and 36-44 are the hotbar,
 * inventory indices 0-8. That is the mapping the S30 prefix argument depends on.
 */
final class Pkt {

    static final int S2F = 0; // SetSlot, server -> client
    static final int S30 = 1; // WindowItems, server -> client
    static final int S32 = 2; // ConfirmTransaction, server -> client
    static final int C0E = 3; // ClickWindow, client -> server
    static final int C0F = 4; // ConfirmTransaction, client -> server

    final int kind;
    int window;
    int slot;       // container slot number, or -1 with window -1 for the cursor
    int stack;
    int[] prefix;   // S30
    short uid;      // S32 / C0F
    boolean accepted;
    int expect;     // C0E: the stack the client believed was in the slot
    int deliverAt;

    Pkt(int kind) { this.kind = kind; }

    static Pkt setSlot(int window, int slot, int stack) {
        final Pkt p = new Pkt(S2F);
        p.window = window;
        p.slot = slot;
        p.stack = stack;
        return p;
    }

    static Pkt windowItems(int window, int[] prefix) {
        final Pkt p = new Pkt(S30);
        p.window = window;
        p.prefix = prefix;
        return p;
    }

    static Pkt confirm(int kind, int window, short uid, boolean accepted) {
        final Pkt p = new Pkt(kind);
        p.window = window;
        p.uid = uid;
        p.accepted = accepted;
        return p;
    }
}

/** One direction of the connection: ordered, never lossy, constant latency in ticks. */
final class Link {

    private final ArrayDeque<Pkt> queue = new ArrayDeque<Pkt>();
    private final int latency;
    int sent;

    Link(int latency) { this.latency = latency; }

    void send(Pkt p, int now) {
        p.deliverAt = now + latency;
        queue.add(p);
        sent++;
    }

    List<Pkt> due(int now) {
        final List<Pkt> out = new ArrayList<Pkt>();
        while (!queue.isEmpty() && queue.peek().deliverAt <= now) out.add(queue.poll());
        return out;
    }

    boolean idle() { return queue.isEmpty(); }
}

final class ClientModel {

    final int[] inv = new int[36];
    int cursor;

    /**
     * A creative player sitting on a tab that is not the survival-inventory one. handleSetSlot
     * drops every window-0 packet outside slots 36-44 while this is set, and the server cannot see
     * it - which is the whole reason the repair has an S30 fallback.
     */
    boolean creativeTab;

    private final Link toServer;

    /** A client that never answers a confirmation, for the write-off path. */
    boolean answers = true;

    ClientModel(Link toServer) { this.toServer = toServer; }

    void receive(Pkt p, int now) {
        switch (p.kind) {
            case Pkt.S2F:
                if (p.window == -1 && p.slot == -1) { cursor = p.stack; return; }
                if (applies(p.window, p.slot)) put(p.slot, p.stack);
                return;
            case Pkt.S30:
                // handleWindowItems on window 0 applies to inventoryContainer unconditionally, and
                // putStacksInSlots walks the array it is given - so a prefix is legal.
                for (int i = 0; i < p.prefix.length; i++) put(i, p.prefix[i]);
                return;
            case Pkt.S32:
                if (!p.accepted && answers) {
                    toServer.send(Pkt.confirm(Pkt.C0F, p.window, p.uid, true), now);
                }
                return;
            default:
                throw new IllegalStateException("server received its own packet kind");
        }
    }

    /** Whether handleSetSlot applies rather than drops. */
    private boolean applies(int window, int slotNumber) {
        if (window != 0) return true;
        if (slotNumber >= 36 && slotNumber < 45) return true;
        return !creativeTab;
    }

    /** Predicts the swap locally and tells the server what it thought was there. */
    void click(int index, int now) {
        final int expect = inv[index];
        final int held = cursor;
        cursor = inv[index];
        inv[index] = held;
        final Pkt p = new Pkt(Pkt.C0E);
        p.window = 0;
        p.slot = Model.slotNumber(index);
        p.expect = expect;
        toServer.send(p, now);
    }

    private void put(int slotNumber, int stack) {
        final int index = Model.inventoryIndex(slotNumber);
        if (index >= 0) inv[index] = stack;
    }
}

/** Shared slot-number arithmetic, kept in one place so both halves cannot disagree about it. */
final class Model {

    static final int MAIN_FIRST = 9;
    static final int MAIN_END = 36;
    static final int SLOTS = 45;

    private Model() {}

    static int slotNumber(int index) { return index >= MAIN_FIRST ? index : index + 36; }

    /** -1 for the crafting and armour slots, which hold nothing here. */
    static int inventoryIndex(int slotNumber) {
        if (slotNumber >= MAIN_FIRST && slotNumber < MAIN_END) return slotNumber;
        if (slotNumber >= 36 && slotNumber < SLOTS) return slotNumber - 36;
        return -1;
    }
}
