package net.vheerden.archi.mcp.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins the per-class deadline of {@link HeadlessTestRunner}: a class that stops making progress must
 * end the run with a thread dump instead of stalling it.
 *
 * <p>Each case runs the runner in a child JVM on this JVM's own classpath, because the runner ends a
 * timed-out run by halting the process. The parent bounds every child with its own wait and kills it
 * on expiry, so a broken watchdog fails this test instead of hanging the run that contains it.</p>
 *
 * <p>The cases whose fixture starts a process of its own end that process by pid when they finish,
 * pass or fail, so a runner that fails to end it cannot leave it behind in the run.</p>
 */
public class HeadlessTestRunnerWatchdogTest {

    /** How long the parent waits for a child before killing it — far below the fixture's sleep. */
    private static final long CHILD_BOUND_SECONDS = 60;

    /**
     * Deadline for a child that must NOT time out. The deadline also covers loading the class and
     * JUnit, so it is generous: a loaded machine must not turn a passing class into a timeout.
     */
    private static final String AMPLE_DEADLINE_SECONDS = "30";

    /** How long a child the runner ended may take to be gone once the runner itself has exited. */
    private static final long REAP_SETTLE_SECONDS = 5;

    private Path workDir;

    @Before
    public void setUp() throws IOException {
        workDir = Files.createTempDirectory("harness-watchdog-");
    }

    @After
    public void tearDown() throws IOException {
        if (workDir != null) {
            try (var paths = Files.walk(workDir)) {
                paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    public void shouldEndTheRunWithAThreadDump_whenAClassOutlivesItsDeadline() throws Exception {
        long started = System.nanoTime();
        Child child = runChild("2", SleepsPastTheDeadlineFixture.class.getName());
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

        assertEquals("exit code, output:\n" + child.output, 3, child.exitCode);
        assertTrue("elapsed " + elapsedSeconds + "s", elapsedSeconds <= 30);
        String fqcn = SleepsPastTheDeadlineFixture.class.getName();
        int timeoutLine = child.output.indexOf("[TIMEOUT] " + fqcn + " after 2s");
        int dumpFrame = child.output.indexOf(fqcn + ".sleepsFarPastAnyDeadline");
        int result = child.output.indexOf("RESULT: FAIL");
        assertTrue("timeout line, output:\n" + child.output, timeoutLine >= 0);
        assertTrue("the sleeping test's frame in the dump, output:\n" + child.output, dumpFrame > timeoutLine);
        assertTrue("RESULT: FAIL after the dump, output:\n" + child.output, result > dumpFrame);

        Path xml = workDir.resolve("xml").resolve("TEST-" + fqcn + ".xml");
        assertTrue("the timed-out class still gets its XML", Files.exists(xml));
        String report = Files.readString(xml, StandardCharsets.UTF_8);
        assertTrue(report, report.contains("<error") && report.contains("type=\"timeout\""));
    }

    @Test
    public void shouldNameTheStuckTestAndTheLockOwner_whenAClassOutlivesItsDeadlineWaitingForALock()
            throws Exception {
        Child child = runChild("2", HoldsALockPastTheDeadlineFixture.class.getName());

        assertEquals("exit code, output:\n" + child.output, 3, child.exitCode);
        String fqcn = HoldsALockPastTheDeadlineFixture.class.getName();
        String holder = HoldsALockPastTheDeadlineFixture.HOLDER_THREAD;
        int timeoutLine = child.output.indexOf(
                "[TIMEOUT] " + fqcn + " after 2s in waitsForALockAnotherThreadHolds");
        assertTrue("the timeout names the test that was running, output:\n" + child.output, timeoutLine >= 0);

        // the stuck thread: what it waits on, who owns it, and where it waits
        int waiting = child.output.indexOf(
                "WAITING on java.util.concurrent.locks.ReentrantLock$NonfairSync", timeoutLine);
        assertTrue("the stuck thread's lock, output:\n" + child.output, waiting > timeoutLine);
        int owner = child.output.indexOf("owned by \"" + holder + "\"", waiting);
        assertTrue("the lock's owner, output:\n" + child.output, owner > waiting);
        int waitFrame = child.output.indexOf(fqcn + ".waitsForALockAnotherThreadHolds", owner);
        assertTrue("the stuck thread's frame, output:\n" + child.output, waitFrame > owner);

        // the owner: its own frames and the lock it holds
        int holderHeader = child.output.indexOf("\"" + holder + "\" daemon", timeoutLine);
        assertTrue("the owner's own entry, output:\n" + child.output, holderHeader > timeoutLine);
        int holderFrame = child.output.indexOf(fqcn + ".holdForever", holderHeader);
        assertTrue("the owner's frames, output:\n" + child.output, holderFrame > holderHeader);
        int holderSync = child.output.indexOf("locked java.util.concurrent.locks.ReentrantLock$NonfairSync",
                holderFrame);
        assertTrue("the lock the owner holds, output:\n" + child.output, holderSync > holderFrame);

        // the report finished inside the grace: the last-resort halt never prints the result line
        int dumpEnd = child.output.indexOf("---- end of thread dump");
        int result = child.output.indexOf("RESULT: FAIL");
        assertTrue("RESULT: FAIL after the dump, output:\n" + child.output, dumpEnd >= 0 && result > dumpEnd);

        Path xml = workDir.resolve("xml").resolve("TEST-" + fqcn + ".xml");
        String report = Files.readString(xml, StandardCharsets.UTF_8);
        assertTrue("the XML trace names the owner too:\n" + report,
                report.contains("owned by &quot;" + holder + "&quot;"));
    }

    @Test
    public void shouldSayNoTestWasRunning_whenAClassOutlivesItsDeadlineInClassSetup() throws Exception {
        Child child = runChild("2", HangsInClassSetupFixture.class.getName());

        assertEquals("exit code, output:\n" + child.output, 3, child.exitCode);
        assertTrue(child.output, child.output.contains("[TIMEOUT] " + HangsInClassSetupFixture.class.getName()
                + " after 2s in no test (class setup or teardown, or between tests)"));
    }

    @Test
    public void shouldStillEndTheRun_whenAShutdownHookWaitsOnTheStuckClass() throws Exception {
        Child child = runChild("2", HookBlocksExitFixture.class.getName());

        assertEquals("exit code, output:\n" + child.output, 3, child.exitCode);
        assertTrue(child.output, child.output.contains("[TIMEOUT] " + HookBlocksExitFixture.class.getName()));
    }

    @Test
    public void shouldStillEndTheRun_whenTheStuckClassBlocksTheReport() throws Exception {
        Child child = runChild("2", BlocksStandardOutputFixture.class.getName());

        // The report itself cannot be written, so only the exit code can show the run was ended.
        assertEquals("exit code, output:\n" + child.output, 3, child.exitCode);
    }

    @Test
    public void shouldLeaveTheRunAlone_whenTheClassFinishesInTime() throws Exception {
        Child child = runChild(AMPLE_DEADLINE_SECONDS, FinishesInTimeFixture.class.getName());

        assertEquals("exit code, output:\n" + child.output, 0, child.exitCode);
        assertFalse(child.output, child.output.contains("[TIMEOUT]"));
        assertFalse("a run that started no process ends none, output:\n" + child.output,
                child.output.contains("[REAPED]"));
        assertTrue(child.output, child.output.contains("RESULT: PASS"));
    }

    @Test
    public void shouldRefuseToStart_whenTheDeadlineIsNotANumber() throws Exception {
        Child child = runChild("soon", FinishesInTimeFixture.class.getName());

        assertEquals("exit code, output:\n" + child.output, 2, child.exitCode);
        assertTrue(child.output, child.output.contains("harness.classTimeoutSeconds"));
        assertFalse("nothing may run under an unreadable deadline", child.output.contains("RESULT:"));
    }

    @Test
    public void shouldRefuseToStart_whenTheDeadlineIsNegative() throws Exception {
        Child child = runChild("-1", FinishesInTimeFixture.class.getName());

        assertEquals("exit code, output:\n" + child.output, 2, child.exitCode);
        assertTrue(child.output, child.output.contains("harness.classTimeoutSeconds"));
    }

    @Test
    public void shouldEndTheChildrenOfAStoppedClass_whenTheDeadlineEndsTheRun() throws Exception {
        assumeSleepCommand();
        Path pidFile = workDir.resolve("child.pid");
        try {
            Child child = runChild("2", SpawnsAChildThenSleepsFixture.class.getName(), pidArg(pidFile));

            assertEquals("exit code, output:\n" + child.output, 3, child.exitCode);
            assertTrue(child.output, child.output.contains("[TIMEOUT] " + SpawnsAChildThenSleepsFixture.class.getName()));
            long pid = readPid(pidFile, child.output);
            assertTrue("pid " + pid + " outlived the runner", isGoneWithin(pid, REAP_SETTLE_SECONDS));
            assertTrue("the ended child is named, output:\n" + child.output,
                    child.output.contains("[REAPED] pid " + pid));
        } finally {
            killIfAlive(pidFile);
        }
    }

    @Test
    public void shouldStillEndTheChildrenOfAStoppedClass_whenTheStuckClassBlocksTheReport() throws Exception {
        assumeSleepCommand();
        Path pidFile = workDir.resolve("child.pid");
        try {
            Child child = runChild("2", SpawnsAChildThenBlocksStandardOutputFixture.class.getName(), pidArg(pidFile));

            // The report cannot be written, so only the exit code and the child's own fate show the run was ended.
            assertEquals("exit code, output:\n" + child.output, 3, child.exitCode);
            long pid = readPid(pidFile, child.output);
            assertTrue("pid " + pid + " outlived the runner", isGoneWithin(pid, REAP_SETTLE_SECONDS));
        } finally {
            killIfAlive(pidFile);
        }
    }

    @Test
    public void shouldCloseTheRunnersOutput_whenTheDeadlineEndsARunWhoseClassLeftAChild() throws Exception {
        assumeSleepCommand();
        Path pidFile = workDir.resolve("child.pid");
        long bound = 2_000 + HeadlessTestRunner.REPORT_GRACE_MILLIS + TimeUnit.SECONDS.toMillis(REAP_SETTLE_SECONDS);
        Process runner = null;
        try {
            runner = startChild("2", SpawnsAChildThenSleepsFixture.class.getName(), pidArg(pidFile))
                    .redirectErrorStream(true)
                    .start();
            Process reading = runner;
            StringBuffer output = new StringBuffer();
            Thread reader = new Thread(() -> {
                // appended as it arrives, so a bound that expires still shows what the runner printed
                byte[] chunk = new byte[8192];
                try (var in = reading.getInputStream()) {
                    for (int n; (n = in.read(chunk)) > 0; ) {
                        output.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (IOException e) {
                    output.append("read failed: ").append(e);
                }
            }, "watchdog-test-pipe-reader");
            reader.setDaemon(true);
            reader.start();
            reader.join(bound);

            assertFalse("the runner's output pipe was still open " + bound + " ms after it started — a "
                    + "child that shares it outlived the run. Output so far:\n" + output, reader.isAlive());
            assertTrue(output.toString(), output.toString().contains("[TIMEOUT]"));
        } finally {
            if (runner != null) {
                runner.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            killIfAlive(pidFile);
        }
    }

    @Test
    public void shouldEndTheChildrenOfAFinishedRun_beforeTheSummary() throws Exception {
        assumeSleepCommand();
        Path pidFile = workDir.resolve("child.pid");
        try {
            Child child = runChild(AMPLE_DEADLINE_SECONDS, SpawnsAChildThenFinishesFixture.class.getName(),
                    pidArg(pidFile));

            assertEquals("exit code, output:\n" + child.output, 0, child.exitCode);
            long pid = readPid(pidFile, child.output);
            assertTrue("pid " + pid + " outlived the runner", isGoneWithin(pid, REAP_SETTLE_SECONDS));
            int classLine = child.output.indexOf("[ok  ] " + SpawnsAChildThenFinishesFixture.class.getName());
            int reaped = child.output.indexOf("[REAPED] pid " + pid);
            int summary = child.output.indexOf("SUMMARY:");
            assertTrue("class line, output:\n" + child.output, classLine >= 0);
            assertTrue("the ended child is named after the class line, output:\n" + child.output,
                    reaped > classLine);
            assertTrue("and before the summary, output:\n" + child.output, summary > reaped);
            assertTrue(child.output, child.output.contains("RESULT: PASS"));
        } finally {
            killIfAlive(pidFile);
        }
    }

    /** The child the fixtures start is the {@code sleep} command, which Windows does not have. */
    private static void assumeSleepCommand() {
        assumeFalse("the fixtures' child process is the 'sleep' command, absent on Windows",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
        boolean onPath = java.util.Arrays.stream(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .filter(dir -> !dir.isEmpty())
                .anyMatch(dir -> Files.isExecutable(Path.of(dir, "sleep")));
        assumeTrue("the fixtures' child process is the 'sleep' command, not found on PATH", onPath);
    }

    private static String pidArg(Path pidFile) {
        return "-D" + SpawnsAChildThenSleepsFixture.CHILD_PID_FILE_PROPERTY + "=" + pidFile;
    }

    private static long readPid(Path pidFile, String output) throws IOException {
        assertTrue("the fixture never recorded its child's pid, output:\n" + output, Files.exists(pidFile));
        return Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
    }

    private static boolean isGoneWithin(long pid, long seconds) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            if (ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /**
     * Ends the fixture's child by pid, so a failing case can never leave it running. A process under
     * that pid that is known not to be {@code sleep} has reused it and is left alone.
     */
    private static void killIfAlive(Path pidFile) {
        try {
            if (Files.exists(pidFile)) {
                long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
                ProcessHandle.of(pid)
                        .filter(p -> p.info().command().map(c -> c.endsWith("sleep")).orElse(true))
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
        } catch (IOException | NumberFormatException e) {
            // nothing recorded, nothing to end
        }
    }

    private Child runChild(String deadlineSeconds, String fixture, String... jvmArgs) throws Exception {
        File log = workDir.resolve("child.log").toFile();
        Process process = startChild(deadlineSeconds, fixture, jvmArgs)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
        if (!process.waitFor(CHILD_BOUND_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            fail("the child runner was still alive after " + CHILD_BOUND_SECONDS + "s — the deadline did "
                    + "not end it. Output so far:\n" + Files.readString(log.toPath(), StandardCharsets.UTF_8));
        }
        return new Child(process.exitValue(), Files.readString(log.toPath(), StandardCharsets.UTF_8));
    }

    private ProcessBuilder startChild(String deadlineSeconds, String fixture, String... jvmArgs) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(java,
                "-cp", System.getProperty("java.class.path"),
                "-Dharness.classTimeoutSeconds=" + deadlineSeconds,
                "-Dharness.xmlDir=" + workDir.resolve("xml")));
        command.addAll(List.of(jvmArgs));
        command.addAll(List.of(HeadlessTestRunner.class.getName(), fixture));
        return new ProcessBuilder(command);
    }

    private record Child(int exitCode, String output) {
    }
}
