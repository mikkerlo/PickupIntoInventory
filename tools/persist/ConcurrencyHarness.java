import java.io.File;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import net.greatkorn.pickupintoinventory.PIIState;
import net.greatkorn.pickupintoinventory.PIIState.Choice;

/**
 * The claim under test is finding #7's actual requirement: a preference change made on the main
 * server thread must never wait on disk I/O. Reflection is used only to hold the mod's own save
 * lock from outside, which is how an in-flight write is simulated without a slow disk.
 */
public final class ConcurrencyHarness {

    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "pii-conc-" + System.nanoTime());
        dir.mkdirs();
        PIIState.init(new File(dir, "players.properties"));

        setNeverBlocksOnAWriteInFlight();
        concurrentBurstDoesNotDeadlock();
        flushUnderConcurrentChangesTerminates();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** Hold SAVE_LOCK, i.e. pretend a write is underway, then time a change on "the tick thread". */
    private static void setNeverBlocksOnAWriteInFlight() throws Exception {
        Field lockField = PIIState.class.getDeclaredField("SAVE_LOCK");
        lockField.setAccessible(true);
        final Object saveLock = lockField.get(null);

        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Thread writer = new Thread(new Runnable() {
            public void run() {
                synchronized (saveLock) {
                    held.countDown();
                    try { release.await(); } catch (InterruptedException ignored) {}
                }
            }
        });
        writer.setDaemon(true);
        writer.start();
        held.await();

        long worst = 0;
        for (int i = 0; i < 500; i++) {
            long t0 = System.nanoTime();
            PIIState.set(UUID.randomUUID(), i % 2 == 0 ? Choice.ON : Choice.OFF);
            worst = Math.max(worst, System.nanoTime() - t0);
        }
        release.countDown();
        writer.join(1000);

        // Anything that took the save lock would have parked here until release, i.e. seconds.
        check("set() never blocks while a write holds SAVE_LOCK (worst "
            + (worst / 1000L) + "us)", worst < 50_000_000L);
    }

    private static void concurrentBurstDoesNotDeadlock() throws Exception {
        final int threads = 8, each = 400;
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicLong worst = new AtomicLong();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread th = new Thread(new Runnable() {
                public void run() {
                    UUID u = new UUID(0, id);
                    try { go.await(); } catch (InterruptedException ignored) {}
                    for (int i = 0; i < each; i++) {
                        long t0 = System.nanoTime();
                        PIIState.set(u, i % 2 == 0 ? Choice.ON : Choice.OFF);
                        worst.set(Math.max(worst.get(), System.nanoTime() - t0));
                    }
                    done.countDown();
                }
            });
            th.setDaemon(true);
            th.start();
        }
        go.countDown();
        boolean finished = done.await(15, java.util.concurrent.TimeUnit.SECONDS);
        check("8 threads x 400 concurrent changes completed (no deadlock)", finished);
        check("worst concurrent set() stayed off the disk path (worst "
            + (worst.get() / 1000L) + "us)", worst.get() < 100_000_000L);
    }

    private static void flushUnderConcurrentChangesTerminates() throws Exception {
        final CountDownLatch stop = new CountDownLatch(1);
        Thread churn = new Thread(new Runnable() {
            public void run() {
                UUID u = UUID.randomUUID();
                int i = 0;
                while (stop.getCount() > 0) PIIState.set(u, (i++ % 2 == 0) ? Choice.ON : Choice.OFF);
            }
        });
        churn.setDaemon(true);
        churn.start();
        Thread.sleep(200);

        long t0 = System.nanoTime();
        PIIState.flush(); // the shutdown path, with changes still arriving
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        stop.countDown();
        churn.join(1000);
        check("flush() under continuous churn returned promptly (" + ms + "ms)", ms < 5000);
    }

    private static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
