import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;

import net.greatkorn.pickupintoinventory.PIIState;
import net.greatkorn.pickupintoinventory.PIIState.Choice;

/**
 * Compiles and exercises the LIVE src/.../PIIState.java (not a pinned copy), which is possible
 * because task 03 left that class free of Minecraft imports.
 *
 * Sentinel technique is inherited from the original review's evidence harness: a marker comment is
 * appended to the properties file, and any full rewrite by the mod destroys it. Sentinel present
 * afterwards == no write happened. Sentinel gone == a write happened.
 */
public final class PersistHarness {

    private static int passed, failed;
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "pii-persist-" + System.nanoTime());
        dir.mkdirs();
        File f = new File(dir, "pickupintoinventory_players.properties");

        readsA130File(dir);
        identicalSetNeverWrites(f);
        realChangeWrites(f);
        burstCoalescesIntoOneWrite(f);
        flushIsSynchronous(f);
        failedWriteLeavesOldFileIntact(dir);
        failedWriteIsNotForgotten(dir);
        malformedFileDoesNotThrow(dir);
        serverTokenRoundTrips(f);

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    /** A 1.3.0 file used only true/false and no 'server' token; it must still load. */
    private static void readsA130File(File dir) throws Exception {
        File f = new File(dir, "legacy.properties");
        FileWriter w = new FileWriter(f);
        w.write(A + "=true\n" + B + "=false\n");
        w.close();
        PIIState.init(f);
        check("1.3.0 file: true parses as ON", PIIState.get(A) == Choice.ON);
        check("1.3.0 file: false parses as OFF", PIIState.get(B) == Choice.OFF);
    }

    /** Finding #7: the repeatable free message must cost nothing. */
    private static void identicalSetNeverWrites(File f) throws Exception {
        PIIState.init(f);
        PIIState.set(A, Choice.ON);
        PIIState.flush();
        check("a real change reached disk", contents(f).contains(A.toString()));

        sentinel(f);
        for (int i = 0; i < 1000; i++) PIIState.set(A, Choice.ON);
        Thread.sleep(1500);
        check("1000 identical set() calls caused zero rewrites", hasSentinel(f));
    }

    private static void realChangeWrites(File f) throws Exception {
        sentinel(f);
        PIIState.set(A, Choice.OFF);
        PIIState.flush();
        check("a differing set() does rewrite", !hasSentinel(f));
        check("and the new value is on disk", props(f).getProperty(A.toString()).equals("false"));
    }

    /** Finding #7: a burst must coalesce, not queue one write each. */
    private static void burstCoalescesIntoOneWrite(File f) throws Exception {
        sentinel(f);
        for (int i = 0; i < 200; i++) PIIState.set(B, i % 2 == 0 ? Choice.ON : Choice.OFF);
        Thread.sleep(1200);
        check("a 200-change burst did write", !hasSentinel(f));
        check("burst's final value is the one on disk",
            props(f).getProperty(B.toString()).equals("false"));

        // If the burst had queued one write per change, more writes would still be draining now.
        sentinel(f);
        Thread.sleep(2500);
        check("burst produced no further writes after the first", hasSentinel(f));
    }

    private static void flushIsSynchronous(File f) throws Exception {
        PIIState.set(B, Choice.SERVER);
        PIIState.flush(); // no sleep: shutdown must not depend on the daemon getting there
        check("flush() persists without waiting for the writer",
            props(f).getProperty(B.toString()).equals("server"));
    }

    /**
     * Finding #8, the check the original review could not run: force the write to fail partway and
     * confirm the previously saved file is still whole. A directory sitting where the temp file
     * wants to be makes FileOutputStream throw exactly as a full disk or a permissions error would.
     */
    private static void failedWriteLeavesOldFileIntact(File dir) throws Exception {
        File f = new File(dir, "durability.properties");
        PIIState.init(f);
        PIIState.set(A, Choice.ON);
        PIIState.set(B, Choice.OFF);
        PIIState.flush();
        String before = contents(f);
        check("baseline saved", props(f).size() == 2);

        File blocker = new File(dir, f.getName() + ".new");
        check("temp path is free before blocking", !blocker.exists());
        blocker.mkdir();
        try {
            PIIState.set(A, Choice.SERVER); // a real change; its write must fail
            PIIState.flush();
            check("failed write left the old file byte-identical", contents(f).equals(before));
            check("failed write left both players readable", props(f).size() == 2);
            check("failed write did not truncate the file", contents(f).length() > 0);
        } finally {
            blocker.delete();
        }

        // And the mod recovers: the next real change writes normally once the fault is gone.
        PIIState.set(B, Choice.ON);
        PIIState.flush();
        check("recovers after the fault clears", props(f).getProperty(B.toString()).equals("true"));
    }

    /**
     * The defect akvasha found in review: a write that failed was recorded as stored, so the
     * change was lost for good - the shutdown flush found nothing outstanding and returned - while
     * the command and the packet handler had already told the player it had taken.
     *
     * The distinction being tested is between "do not spin on it" and "give up on it". Nothing new
     * happens after the fault clears here: no further set(), just a flush, which is exactly the
     * shutdown path.
     */
    private static void failedWriteIsNotForgotten(File dir) throws Exception {
        File f = new File(dir, "retry.properties");
        PIIState.init(f);
        PIIState.set(A, Choice.ON);
        PIIState.flush();
        check("retry baseline saved", props(f).getProperty(A.toString()).equals("true"));

        File blocker = new File(dir, f.getName() + ".new");
        blocker.mkdir();
        String survived;
        try {
            PIIState.set(A, Choice.OFF);
            PIIState.flush(); // fails
            check("the failed write did not reach disk",
                props(f).getProperty(A.toString()).equals("true"));
            // Give the writer thread every chance to declare the work done behind our back.
            Thread.sleep(1500);
            survived = props(f).getProperty(A.toString());
        } finally {
            blocker.delete();
        }
        check("still not on disk while the fault stands", "true".equals(survived));

        // The fault is gone and nothing else has changed. A shutdown here must still write.
        PIIState.flush();
        check("the shutdown flush retried the failed change",
            props(f).getProperty(A.toString()).equals("false"));

        // Same again, but recovered by the writer thread rather than by flush().
        blocker.mkdir();
        try {
            PIIState.set(A, Choice.SERVER);
            PIIState.flush();
            Thread.sleep(1200);
        } finally {
            blocker.delete();
        }
        PIIState.set(B, Choice.ON); // any later change must carry the parked one with it
        Thread.sleep(2000);
        check("a later change carries the parked one to disk",
            props(f).getProperty(A.toString()).equals("server"));
        check("and writes itself", props(f).getProperty(B.toString()).equals("true"));
    }

    /**
     * Properties.load answers a malformed \\uxxxx escape with IllegalArgumentException, which is
     * not an IOException. init() runs from preInit, so one corrupt line used to take FML down.
     */
    private static void malformedFileDoesNotThrow(File dir) throws Exception {
        File f = new File(dir, "malformed.properties");
        FileWriter w = new FileWriter(f);
        w.write(A + "=true\n" + B + "=\\uZZZZ\n");
        w.close();
        boolean threw = false;
        try {
            PIIState.init(f);
        } catch (Throwable t) {
            threw = true;
        }
        check("a malformed escape does not propagate out of init()", !threw);

        // A value torn by the pre-atomic writer reads as off, as 1.3.0 did, but is now logged.
        File torn = new File(dir, "torn.properties");
        w = new FileWriter(torn);
        w.write(A + "=tr\n");
        w.close();
        PIIState.init(torn);
        check("a torn value still reads as off", PIIState.get(A) == Choice.OFF);
    }

    private static void serverTokenRoundTrips(File f) throws Exception {
        PIIState.init(f);
        PIIState.set(A, Choice.SERVER);
        PIIState.flush();
        PIIState.init(f);
        check("'server' survives a save/load round trip", PIIState.get(A) == Choice.SERVER);
        check("never-chosen stays null", PIIState.get(UUID.randomUUID()) == null);
    }

    // ---- plumbing ----

    private static void sentinel(File f) throws Exception {
        FileOutputStream out = new FileOutputStream(f, true);
        out.write("\n#PII-SENTINEL\n".getBytes("ISO-8859-1"));
        out.close();
    }

    private static boolean hasSentinel(File f) throws Exception {
        return contents(f).contains("#PII-SENTINEL");
    }

    private static String contents(File f) throws Exception {
        if (!f.isFile()) return "";
        InputStream in = new FileInputStream(f);
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "ISO-8859-1"));
        in.close();
        return sb.toString();
    }

    private static Properties props(File f) throws Exception {
        Properties p = new Properties();
        InputStream in = new FileInputStream(f);
        p.load(in);
        in.close();
        return p;
    }

    private static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
