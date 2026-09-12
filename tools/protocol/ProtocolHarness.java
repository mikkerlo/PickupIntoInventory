import java.util.List;
import java.util.Random;

/**
 * Drives ProtocolModel: reproduces the two findings #4 fixes against the behaviour that had them,
 * shows the fix closing each, and then runs randomised pickup/click interleavings looking for a
 * pair of inventories that do not converge once the traffic stops.
 *
 * One line per assertion, non-zero exit on the first failure. No test framework, same as the
 * persistence harnesses next door.
 *
 * Read the header of ProtocolModel.java before trusting a pass: this exercises a transcription of
 * PIISync's state machine, not PIISync. It can show the protocol reasoning is wrong. It cannot show
 * the mixin injects where the annotation says it does.
 */
public final class ProtocolHarness {

    private static int passed;
    private static int failed;

    /** Long enough for three answer timeouts, so a written-off round has room to finish. */
    private static final int QUIET_CAP = 2000;

    public static void main(String[] args) {
        vanillaLosesAPickupUnderSuppression();
        theRepairDeliversIt();
        aRepairWithoutItsAnswerLeavesAGhost();
        theAnswerCatchesTheGhost();
        theCreativeTabIsRepairedByAPrefix();
        aForeignHotbarSlotIsRepairedByTheWholeContainer();
        aPerSlotRepairIsDroppedAndTheMarksAreLost();
        theWindowZeroSnapshotSurvivesTheSameScreen();
        aCloseWindowCountsAsAWindowAction();
        theBarrierGuardSkipsTheIdItJustUsed();
        theBarrierGuardSkipsAnIdVanillaStillHasOnRecord();
        aSilentClientIsWrittenOff();
        randomised(4500);

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * The bug #4 was filed for. A pickup lands in slot 20, and in the same tick an accepted click
     * runs detectAndSendChanges with isChangingQuantityOnly set: the cache is written, the packet
     * is not sent, and nothing ever sends it again.
     */
    private static void vanillaLosesAPickupUnderSuppression() {
        final Sim sim = new Sim(3, false, false);
        sim.server.seed(5, stack(1, 1), sim.client); // something in the hotbar to click

        sim.client.click(5, sim.now);
        // The pickup happens before the packet drain, so the suppressed sync swallows it.
        sim.run(8, new Step() { public void run(Sim s) { if (s.now == 3) s.server.pickup(stack(7, 1)); } });
        sim.quiet();

        check("without the repair, a pickup under isChangingQuantityOnly never reaches the client",
            sim.server.inv[9] == stack(7, 1) && sim.client.inv[9] == 0);
    }

    /** The same script with the repair on. */
    private static void theRepairDeliversIt() {
        final Sim sim = new Sim(3, true, true);
        sim.server.seed(5, stack(1, 1), sim.client);

        sim.client.click(5, sim.now);
        sim.run(8, new Step() { public void run(Sim s) { if (s.now == 3) s.server.pickup(stack(7, 1)); } });
        sim.quiet();

        check("the repair delivers the slot the suppressed sync dropped", sim.converged());
    }

    /**
     * The second finding. A repair carrying the pre-click contents of slot 20 crosses a click that
     * empties it; the client applies the repair after predicting the slot empty, the server accepts
     * the click and suppresses its own correction, and the stack exists only on the client.
     */
    private static void aRepairWithoutItsAnswerLeavesAGhost() {
        check("a repair with no answer behind it leaves a ghost stack", ghostScript(false));
    }

    private static void theAnswerCatchesTheGhost() {
        check("the confirmation round trip detects the overwrite and repairs it", !ghostScript(true));
    }

    /**
     * @return whether the client ended up showing a stack the server does not have.
     */
    private static boolean ghostScript(boolean confirm) {
        final int latency = 4;
        final Sim sim = new Sim(latency, true, confirm);
        sim.server.seed(20, stack(3, 1), sim.client);

        // tick 0: a second of the same item is picked up and merges into slot 20. Vanilla's own
        // sync sends it, so the client is up to date - but the slot stays marked until a round
        // says so, and the repair that round sends is what crosses the click.
        sim.run(1, new Step() { public void run(Sim s) { if (s.now == 0) s.server.merge(20); } });
        // The client applies the merge on tick `latency` and picks the stack up on the next one,
        // which is exactly when the repair built on tick 1 arrives.
        sim.run(latency + 2, new Step() {
            public void run(Sim s) { if (s.now == latency + 1) s.client.click(20, s.now); }
        });
        sim.quiet();

        return sim.client.inv[20] != sim.server.inv[20];
    }

    /**
     * A creative player may be on a tab that drops every window-0 slot packet outside the hotbar,
     * which the server cannot see. The repair travels as a window-0 S30 truncated after the highest
     * slot that needs it, which handleWindowItems applies with no test.
     */
    private static void theCreativeTabIsRepairedByAPrefix() {
        final Sim sim = new Sim(2, true, true);
        sim.server.creative = true;
        sim.client.creativeTab = true;

        sim.run(4, new Step() { public void run(Sim s) { if (s.now == 0) s.server.pickup(stack(9, 1)); } });
        sim.quiet();

        check("a client on a creative tab is repaired by the prefix", sim.converged());
        // Slot 9 is the only one marked, and slot 9 is its own container slot number, so a prefix
        // long enough to carry it is ten entries. Not an invariant of the repair - a hotbar mark
        // takes it to all 45, which is what the scenario below is for - but the bound on what a
        // pickup costs, which is the reason the prefix is truncated at all.
        check("a prefix carrying one main-inventory slot stops just after it",
            sim.server.repairPrefixes > 0 && sim.server.longestRepairPrefix == 10);
    }

    /**
     * The other end of that cost. noteForeignSlot is the one caller that can mark 0-8, for a client
     * this side has reason to believe wrote its own hotbar - and index 0-8 number to 36-44, so the
     * prefix that carries any of them has already passed every main-inventory slot. The point of the
     * scenario is that it arrives all the same: a slot no diff can see, because both sides agree on
     * what was last sent, is still repaired.
     */
    private static void aForeignHotbarSlotIsRepairedByTheWholeContainer() {
        final Sim sim = new Sim(3, true, true);
        sim.server.seed(20, stack(4, 1), sim.client);

        // Written into the client's array by hand, which is what the divergence looks like from
        // here: a client whose own slot choice this side does not share emptied hotbar index 3 and
        // put the stack where this side never heard of. Nothing on the server changed, so the cache
        // matches, so detectAndSendChanges has nothing to say now or ever.
        sim.client.inv[3] = stack(8, 12);
        check("no diff can see a slot only the client wrote", !sim.converged());

        sim.run(2, new Step() { public void run(Sim s) { if (s.now == 0) s.server.noteForeignSlot(3); } });
        sim.quiet();

        check("a marked hotbar slot is repaired", sim.converged());
        // Index 3 numbers to 39, so the prefix is 40 entries: slots 9-35 ride along whether they
        // needed to or not, and index 8 would take it to all 45. That is the price of the mark, and
        // the reason markStale still refuses 0-8 - it is the diff, and the diff has no reason to.
        check("and carrying it costs almost the whole container",
            sim.server.longestRepairPrefix == 40);
    }

    /**
     * The finding behind the switch from per-slot packets to one snapshot. The first draft chose the
     * channel from the server's own gamemode flag; the flag that decides whether the client applies
     * a window-0 slot packet is the screen it has open, and a GuiContainerCreative outlives the
     * creative mode that opened it. With the two disagreeing the server sends an S2F it believes
     * will land, the client discards it without a word, the barrier behind it is answered out of
     * inventoryContainer anyway - so the round reports clean and drops the marks for good.
     */
    private static void aPerSlotRepairIsDroppedAndTheMarksAreLost() {
        final Sim sim = droppedRepairScript(true);

        check("a per-slot repair the client silently drops never arrives", !sim.converged());
        check("and the round that lost it reported clean, so nothing is left to retry",
            sim.settledWithNothingOutstanding());
    }

    /** The same script on the channel the client cannot decline. */
    private static void theWindowZeroSnapshotSurvivesTheSameScreen() {
        final Sim sim = droppedRepairScript(false);

        check("the window-0 snapshot reaches the same client", sim.converged());
    }

    /**
     * A survival-mode server and a client still sitting on a creative tab, running the same script
     * as the very first scenario: a click and a pickup in one tick, so vanilla's own sync is
     * suppressed and the repair is the only thing that can carry slot 9.
     */
    private static Sim droppedRepairScript(boolean perSlot) {
        final Sim sim = new Sim(3, true, true);
        sim.server.perSlot = perSlot;
        sim.server.creative = false;  // the server sees an ordinary survival player
        sim.client.creativeTab = true; // the screen the mode was left in is still up

        sim.server.seed(5, stack(1, 1), sim.client); // hotbar, which the tab does not drop
        sim.client.click(5, sim.now);
        sim.run(8, new Step() { public void run(Sim s) { if (s.now == 3) s.server.pickup(stack(7, 1)); } });
        sim.quiet();
        return sim;
    }

    /**
     * C0DPacketCloseWindow is counted exactly as a click is, so a repair that crosses one is judged
     * dirty and redone rather than written off. Nothing else in the suite sends one.
     */
    private static void aCloseWindowCountsAsAWindowAction() {
        final int latency = 4;
        final Sim sim = new Sim(latency, true, true);

        sim.run(1, new Step() { public void run(Sim s) { if (s.now == 0) s.server.pickup(stack(9, 1)); } });
        // The close leaves while the repair and its barrier are in flight, so it reaches the server
        // between the repair and the echo: the answer carries a different action count than the
        // repair did.
        sim.run(latency + 2, new Step() {
            public void run(Sim s) { if (s.now == latency + 1) s.client.closeWindow(s.now); }
        });
        sim.quiet();

        check("a close is seen as a window action", sim.server.closes() == 1);
        check("a round the close crossed is redone rather than trusted", sim.server.rounds >= 2);
        check("and the inventory still converges", sim.converged());
    }

    /**
     * The barrier counter moves one step per round, so the id it last used is 65536 rounds behind
     * it and no scenario of a plausible length reaches the guard by playing fairly. Placing the
     * counter next to the collision is the same arithmetic the wrap would have produced, without
     * the 65536 rounds.
     */
    private static void theBarrierGuardSkipsTheIdItJustUsed() {
        final Sim sim = new Sim(2, true, true);

        sim.run(2, new Step() { public void run(Sim s) { if (s.now == 0) s.server.pickup(stack(9, 1)); } });
        sim.quiet();
        final short used = sim.server.barrier();

        // Wind the counter back so the next id it hands out is the one the last round used.
        sim.server.seedBarrierSeq((short) (used - 1));
        sim.server.pickup(stack(9, 2));
        sim.quiet();

        check("the previous round's id is the one the counter would have offered",
            sim.server.previousBarrier() == used);
        check("and the barrier guard passed over it", sim.server.barrier() != used);
        check("the inventory converges across the skip", sim.converged());
    }

    /**
     * The other arm of the guard, and the reason it is not spelled `awaiting`: func_147339_a
     * re-enables crafting and leaves the field_147372_n entry in place, so the id vanilla rejected
     * a click with stays on record for the rest of the session. What a barrier reusing that id
     * costs is not a wasted round - it is vanilla's echo. Our handler takes the first echo of a
     * shared id and returns, so vanilla never hears the answer it is holding the click block for,
     * and the block never lifts.
     */
    private static void theBarrierGuardSkipsAnIdVanillaStillHasOnRecord() {
        final int latency = 6;
        final Sim sim = new Sim(latency, true, true);
        sim.server.seed(5, stack(1, 1), sim.client);

        sim.run(1, new Step() {
            public void run(Sim s) {
                if (s.now != 0) return;
                s.client.click(5, s.now);       // predicts a swap
                s.server.inv[5] = stack(2, 1);  // against something the server does not have
            }
        });

        // tick 6: the click lands, vanilla rejects it, records the id and blocks. Nothing of ours
        // is outstanding yet, so the rejection is entirely vanilla's.
        sim.run(latency + 1, NOTHING);
        final short recorded = sim.server.vanillaUid();
        check("vanilla rejected the click, recorded an id and blocked",
            sim.server.vanillaHasUid() && sim.server.blocked());

        // tick 7: a pickup to repair, with the counter wound so the id it would offer is vanilla's.
        // The round runs on tick 8 and vanilla's echo does not arrive until tick 12, so the two are
        // genuinely in flight together.
        sim.server.seedBarrierSeq((short) (recorded - 1));
        sim.server.pickup(stack(9, 1));
        sim.quiet();

        check("a barrier does not reuse an id vanilla still has on record",
            sim.server.barrier() != recorded && sim.server.previousBarrier() != recorded);
        check("so vanilla's own echo reaches it and the click block lifts", !sim.server.blocked());
        check("the inventory converges across that skip too", sim.converged());
    }

    /** A client that answers nothing must not draw packets for ever. */
    private static void aSilentClientIsWrittenOff() {
        final Sim sim = new Sim(2, true, true);
        sim.client.answers = false;

        sim.run(4, new Step() { public void run(Sim s) { if (s.now == 0) s.server.pickup(stack(9, 1)); } });
        for (int i = 0; i < QUIET_CAP; i++) sim.run(1, NOTHING);

        check("a silent client is written off after a bounded number of rounds",
            sim.server.rounds <= ServerModel.ANSWER_ATTEMPTS);
    }

    // ------------------------------------------------------------------ randomised

    /**
     * The driver does not click while vanilla's block is up. That is not the model papering over a
     * case: a click that arrives while getCanCraft is false is dropped by func_147351_a where it
     * stands - no echo, no rejection, no resend - so the client keeps a prediction the server never
     * made and the two stay apart until something else writes that slot. It is a vanilla desync,
     * present with the mod uninstalled, and the convergence figure below is about the repair. The
     * block itself is still exercised: rejections happen, ids are recorded, echoes release it.
     */
    private static void randomised(int trials) {
        final Random rnd = new Random(20260912L);
        int worstRounds = 0;
        int closes = 0;
        int foreignMarks = 0;
        for (int t = 0; t < trials; t++) {
            final int latency = 1 + rnd.nextInt(20);
            final boolean packetsFirst = rnd.nextBoolean();
            final boolean creative = rnd.nextInt(4) == 0;
            final int ticks = 20 + rnd.nextInt(100);
            final int clickChance = 1 + rnd.nextInt(60);   // percent, per tick
            final int pickupChance = 1 + rnd.nextInt(40);
            final int closeChance = rnd.nextInt(6);
            final int foreignChance = rnd.nextInt(8);

            final Sim sim = new Sim(latency, true, true);
            sim.packetsFirst = packetsFirst;
            sim.server.creative = creative;
            // Drawn independently of the gamemode, because the screen and the mode come apart: a
            // GuiContainerCreative stays up after /gamemode 0, and the server cannot see either.
            sim.client.creativeTab = rnd.nextInt(4) == 0;

            // Start with a few stacks already in place so clicks have something to move and the
            // server has something to reject against.
            for (int i = 0; i < 6; i++) {
                final int index = rnd.nextInt(36);
                sim.server.seed(index, stack(1 + rnd.nextInt(20), 1 + rnd.nextInt(60)), sim.client);
            }

            final Random inner = new Random(rnd.nextLong());
            sim.run(ticks, new Step() {
                public void run(Sim s) {
                    if (inner.nextInt(100) < pickupChance) {
                        if (inner.nextBoolean() || s.server.pickup(stack(1 + inner.nextInt(20), 1)) < 0) {
                            s.server.merge(Model.MAIN_FIRST + inner.nextInt(27));
                        }
                    }
                    if (!s.server.blocked() && inner.nextInt(100) < clickChance) {
                        s.client.click(inner.nextInt(36), s.now);
                    }
                    if (inner.nextInt(100) < closeChance) s.client.closeWindow(s.now);
                    if (inner.nextInt(100) < foreignChance) {
                        // A client running an older build's slot policy, written into its array
                        // directly: the server's own state does not move, so the cache still matches
                        // and no diff will ever see this. The mark is the only thing that carries it,
                        // and 0-8 is the range nothing else can mark.
                        final int index = inner.nextInt(Model.MAIN_FIRST);
                        s.client.inv[index] = stack(1 + inner.nextInt(20), 1 + inner.nextInt(60));
                        s.server.noteForeignSlot(index);
                    }
                }
            });
            sim.quiet();

            if (!sim.converged()) {
                check("trial " + t + " (latency=" + latency + " packetsFirst=" + packetsFirst
                    + " creative=" + creative + ") converged", false);
                System.out.println("      " + sim.diff());
                return;
            }
            if (sim.server.blocked()) {
                check("trial " + t + " left vanilla's click block released", false);
                return;
            }
            if (sim.server.rounds > worstRounds) worstRounds = sim.server.rounds;
            closes += sim.server.closes();
            foreignMarks += sim.server.foreignHotbarMarks;
        }
        check(trials + " randomised interleavings converged, 1-20 tick latency (worst "
            + worstRounds + " repair rounds in one trial)", true);
        check("the close path was actually walked (" + closes + " window closes)", closes > 0);
        check("the foreign-mark path was actually walked (" + foreignMarks + " hotbar marks)",
            foreignMarks > 0);
    }

    // ------------------------------------------------------------------ plumbing

    private interface Step { void run(Sim sim); }

    private static final Step NOTHING = new Step() { public void run(Sim sim) {} };

    private static final class Sim {

        final Link up;    // client -> server
        final Link down;  // server -> client
        final ClientModel client;
        final ServerModel server;

        int now;
        boolean packetsFirst;

        Sim(int latency, boolean repair, boolean confirm) {
            up = new Link(latency);
            down = new Link(latency);
            client = new ClientModel(up);
            server = new ServerModel(down, repair, confirm);
        }

        void run(int ticks, Step step) {
            for (int i = 0; i < ticks; i++) {
                if (packetsFirst) drainToServer();
                step.run(this);
                if (!packetsFirst) drainToServer();
                server.tick(now);
                drainToClient();
                now++;
            }
        }

        /** Runs with nothing happening until everything has drained, or the cap is reached. */
        void quiet() {
            for (int i = 0; i < QUIET_CAP; i++) {
                run(1, NOTHING);
                if (up.idle() && down.idle() && server.settled()) return;
            }
        }

        private void drainToServer() {
            final List<Pkt> due = up.due(now);
            for (int i = 0; i < due.size(); i++) server.receive(due.get(i), now);
        }

        private void drainToClient() {
            final List<Pkt> due = down.due(now);
            for (int i = 0; i < due.size(); i++) client.receive(due.get(i), now);
        }

        /** Nothing in flight, no round open, and no slot still marked for a retry. */
        boolean settledWithNothingOutstanding() {
            return up.idle() && down.idle() && server.settled();
        }

        boolean converged() {
            if (client.cursor != server.cursor) return false;
            for (int i = 0; i < 36; i++) if (client.inv[i] != server.inv[i]) return false;
            return true;
        }

        String diff() {
            final StringBuilder out = new StringBuilder();
            if (client.cursor != server.cursor) {
                out.append("cursor client=").append(client.cursor).append(" server=").append(server.cursor);
            }
            for (int i = 0; i < 36; i++) {
                if (client.inv[i] == server.inv[i]) continue;
                out.append(" slot ").append(i).append(" client=").append(client.inv[i])
                    .append(" server=").append(server.inv[i]);
            }
            return out.toString();
        }
    }

    private static int stack(int id, int count) { return (id << 8) | count; }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (ok) passed++; else failed++;
    }
}
