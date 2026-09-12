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
        theCreativeTabFallsBackToAPrefix();
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
     * which the server cannot see. The fallback is a window-0 S30 truncated after the highest slot
     * that needs it, which handleWindowItems always applies.
     */
    private static void theCreativeTabFallsBackToAPrefix() {
        final Sim sim = new Sim(2, true, true);
        sim.server.creative = true;
        sim.client.creativeTab = true;

        sim.run(4, new Step() { public void run(Sim s) { if (s.now == 0) s.server.pickup(stack(9, 1)); } });
        sim.quiet();

        check("a client on a creative tab is repaired by the prefix", sim.converged());
        check("the prefix stops before the hotbar", sim.prefixes > 0 && sim.longestPrefix <= 36);
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

    private static void randomised(int trials) {
        final Random rnd = new Random(20260912L);
        int worstRounds = 0;
        for (int t = 0; t < trials; t++) {
            final int latency = 1 + rnd.nextInt(20);
            final boolean packetsFirst = rnd.nextBoolean();
            final boolean creative = rnd.nextInt(4) == 0;
            final int ticks = 20 + rnd.nextInt(100);
            final int clickChance = 1 + rnd.nextInt(60);   // percent, per tick
            final int pickupChance = 1 + rnd.nextInt(40);

            final Sim sim = new Sim(latency, true, true);
            sim.packetsFirst = packetsFirst;
            sim.server.creative = creative;
            sim.client.creativeTab = creative && rnd.nextBoolean();

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
                    if (inner.nextInt(100) < clickChance) s.client.click(inner.nextInt(36), s.now);
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
        }
        check(trials + " randomised interleavings converged, 1-20 tick latency (worst "
            + worstRounds + " repair rounds in one trial)", true);
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
        int prefixes;
        int longestPrefix;

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
            for (int i = 0; i < due.size(); i++) {
                final Pkt p = due.get(i);
                if (p.kind == Pkt.S30 && p.prefix.length < Model.SLOTS) {
                    prefixes++;
                    if (p.prefix.length > longestPrefix) longestPrefix = p.prefix.length;
                }
                client.receive(p, now);
            }
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
