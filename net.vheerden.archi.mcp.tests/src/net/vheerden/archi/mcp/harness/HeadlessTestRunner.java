package net.vheerden.archi.mcp.harness;

import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.File;
import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Headless JUnit4 runner for the build+test harness ({@code tools/run-tests.sh}).
 *
 * <p>This is NOT a test class (it has no {@code @Test} methods and does not match the
 * {@code *Test.java} scan glob, so the harness class-list scan never picks it up). It lives in
 * the <b>tests</b> project — never in production {@code net.vheerden.archi.mcp/src}
 * (tooling-only, zero production-code edits).</p>
 *
 * <p>Two things {@code org.junit.runner.JUnitCore} alone does not give us, and the reason this
 * class exists:</p>
 * <ol>
 *   <li><b>{@code testsRun > 0} postcondition.</b> For every class it runs, it computes the
 *       <i>effective</i> run count = tests that actually executed, <b>excluding</b> assumption-skipped
 *       tests ({@code org.junit.Assume}), {@code @Ignore}d tests, and the synthetic
 *       {@code initializationError} pseudo-test JUnit emits when a class fails to load/initialise.
 *       If a non-excluded class contributes an effective run count of 0, the harness
 *       <b>fails loudly</b> (non-zero exit) — the silent-stale guard {@code AllPluginTestsRunner}
 *       never had.</li>
 *   <li><b>Surefire-style JUnit XML.</b> One {@code TEST-<fqcn>.xml} per class to
 *       {@code -Dharness.xmlDir}, so GitHub Actions CI can publish it.</li>
 * </ol>
 *
 * <p>Usage: {@code java -cp <cp> net.vheerden.archi.mcp.harness.HeadlessTestRunner <fqcn> [<fqcn> ...]}</p>
 *
 * <p>System properties:</p>
 * <ul>
 *   <li>{@code -Dharness.xmlDir=<dir>} — emit surefire {@code TEST-*.xml} here (omit to skip XML).</li>
 *   <li>{@code -Dharness.classify=true} — classification mode: never fails the
 *       build; prints one {@code CLASSIFY <bucket> <fqcn> ...} line per class so each class can be
 *       sorted into the junit bucket vs the {@code tools/osgi-excluded-tests.txt} manifest. In this
 *       mode the {@code testsRun>0} guard is reported, not enforced.</li>
 *   <li>{@code -Dharness.classTimeoutSeconds=<n>} — per-class deadline (default 180; {@code 0}
 *       disables it; anything else that is not a whole number of seconds refuses the run). Applies in
 *       every mode and to every class: a class that overruns ends the run with a thread dump and exit 3
 *       even when it is quarantined as known-failing, and even in classify mode, which otherwise never
 *       fails — a stuck main thread leaves nothing to continue with.</li>
 * </ul>
 *
 * <p>Exit code: 0 only if every class passed AND every class satisfied {@code effectiveRan > 0}
 * (the latter enforced only outside classify mode); 1 otherwise; 2 for bad usage (no classes, an
 * unreadable deadline); 3 when a class overran its deadline and the run was stopped.</p>
 *
 * <p>Before a run that ran classes ends, normally or by the deadline, every process the tests started
 * that is still running is ended and named on a {@code [REAPED]} line ({@link #reapDescendants()}),
 * so nothing outlives the runner holding its output open. That changes no exit code.</p>
 */
public final class HeadlessTestRunner {

    private HeadlessTestRunner() {
    }

    private enum Status {
        PASSED, FAILED, ERROR, ASSUMPTION_SKIPPED, IGNORED
    }

    /** Synthetic method name JUnit uses for a class that fails to load/initialise. */
    private static final String INIT_ERROR = "initializationError";

    private static final class TestRecord {
        final String className;
        final String methodName;
        Status status = Status.PASSED;
        String failureMessage;
        String failureType;
        String failureTrace;
        long timeMs;
        long startNanos;

        TestRecord(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        boolean isInitError() {
            return INIT_ERROR.equals(methodName);
        }
    }

    private static final class RecordingListener extends RunListener {
        final Map<String, TestRecord> records = new LinkedHashMap<>();

        /** The test method that has started and not finished, or null between tests; read by the watchdog. */
        volatile String running;

        private static String methodOf(Description d) {
            String m = d.getMethodName();
            return m != null ? m : d.getDisplayName();
        }

        private static String classOf(Description d) {
            String c = d.getClassName();
            return c != null ? c : "UNKNOWN";
        }

        @Override
        public void testStarted(Description d) {
            TestRecord r = new TestRecord(classOf(d), methodOf(d));
            r.startNanos = System.nanoTime();
            records.put(d.getDisplayName(), r);
            running = r.methodName;
        }

        @Override
        public void testFinished(Description d) {
            running = null;
            TestRecord r = records.get(d.getDisplayName());
            if (r != null) {
                r.timeMs = (System.nanoTime() - r.startNanos) / 1_000_000L;
            }
        }

        @Override
        public void testFailure(Failure f) {
            Description d = f.getDescription();
            TestRecord r = records.get(d.getDisplayName());
            if (r == null) {
                // initializationError (and other class-level failures) arrive with no prior testStarted.
                r = new TestRecord(classOf(d), methodOf(d));
                records.put(d.getDisplayName(), r);
            }
            Throwable t = f.getException();
            r.status = (t instanceof AssertionError) ? Status.FAILED : Status.ERROR;
            r.failureMessage = f.getMessage();
            r.failureType = t != null ? t.getClass().getName() : "java.lang.Throwable";
            r.failureTrace = f.getTrace();
        }

        @Override
        public void testAssumptionFailure(Failure f) {
            TestRecord r = records.get(f.getDescription().getDisplayName());
            if (r != null) {
                r.status = Status.ASSUMPTION_SKIPPED;
                r.failureMessage = f.getMessage();
            }
        }

        @Override
        public void testIgnored(Description d) {
            TestRecord r = new TestRecord(classOf(d), methodOf(d));
            r.status = Status.IGNORED;
            records.put(d.getDisplayName(), r);
        }
    }

    /** Per-class outcome rolled up from the listener records. */
    private static final class ClassResult {
        final String fqcn;
        int effectiveRan;          // PASSED + FAILED + ERROR, excluding the initializationError pseudo
        int passed;
        int failures;             // assertion failures
        int errors;               // non-assertion errors (incl. initializationError)
        int assumptionSkipped;
        int ignored;
        boolean loadError;        // Class.forName / class-init blew up
        String loadErrorDetail;
        final List<TestRecord> records = new ArrayList<>();

        ClassResult(String fqcn) {
            this.fqcn = fqcn;
        }

        boolean hasInitError() {
            for (TestRecord r : records) {
                if (r.isInitError()) {
                    return true;
                }
            }
            return loadError;
        }

        String bucket() {
            if (hasInitError()) {
                return "EXCLUDED";   // class cannot load/initialise headlessly (OSGi/Eclipse singleton)
            }
            if (effectiveRan == 0) {
                return "EXCLUDED";   // entirely assumption-skipped / no real test ran
            }
            return "JUNIT";
        }

        String reason() {
            if (loadError) {
                return "load error: " + loadErrorDetail;
            }
            if (hasInitError()) {
                return "class-init error (OSGi/Eclipse singleton not available headlessly)";
            }
            if (effectiveRan == 0) {
                return "no test executed headlessly (assumption-skipped=" + assumptionSkipped
                        + ", ignored=" + ignored + ")";
            }
            return "ran " + effectiveRan + " headless";
        }
    }

    public static void main(String[] args) {
        boolean classify = "true".equalsIgnoreCase(System.getProperty("harness.classify"));
        String xmlDir = System.getProperty("harness.xmlDir");
        File outDir = null;
        if (xmlDir != null && !xmlDir.isBlank()) {
            outDir = new File(xmlDir);
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
        }
        // Known-failing quarantine (tools/known-failing-tests.txt): these classes are RUN and
        // their results emitted to XML (visible, tracked), but their failures do NOT gate the
        // build — they are pre-existing, tracked defects, not new regressions. A NEW
        // failure in any other class still turns the build red.
        Set<String> knownFailing = loadClassList(System.getProperty("harness.knownFailingFile"));

        // Per-class deadline: a class that stops making progress ends the run with a thread dump
        // instead of stalling it. An unreadable value refuses the run rather than meaning "no limit".
        ClassWatchdog watchdog;
        try {
            watchdog = new ClassWatchdog(
                    parseClassTimeoutSeconds(System.getProperty(CLASS_TIMEOUT_PROPERTY)), outDir);
        } catch (IllegalArgumentException e) {
            System.err.println("HeadlessTestRunner: " + e.getMessage());
            System.exit(2);
            return;
        }

        if (args.length == 0) {
            System.err.println("HeadlessTestRunner: no test classes supplied");
            System.exit(2);
        }

        List<ClassResult> results = new ArrayList<>();
        int totalEffective = 0;
        int totalFailures = 0;   // gating failures (excludes known-failing)
        int totalErrors = 0;     // gating errors (excludes known-failing)
        int violations = 0;      // gating empty-class violations (excludes known-failing)
        int knownFailClasses = 0;
        int knownFailTests = 0;

        for (String fqcn : args) {
            ClassResult cr = watchdog.run(fqcn);
            results.add(cr);
            totalEffective += cr.effectiveRan;

            if (outDir != null) {
                writeXml(outDir, cr);
            }

            boolean known = knownFailing.contains(fqcn);

            if (classify) {
                System.out.printf("CLASSIFY %-8s %s | effectiveRan=%d pass=%d fail=%d err=%d assumeSkip=%d ignored=%d | %s%n",
                        cr.bucket(), cr.fqcn, cr.effectiveRan, cr.passed, cr.failures, cr.errors,
                        cr.assumptionSkipped, cr.ignored, cr.reason());
                continue;
            }

            boolean guardViolated = cr.effectiveRan == 0;
            boolean classFailed = cr.failures > 0 || cr.errors > 0 || cr.loadError;

            if (known) {
                knownFailClasses++;
                knownFailTests += cr.failures + cr.errors;
                System.out.printf("[KNOWN] %-69s ran=%d fail=%d err=%d skip=%d  (quarantined — not gating)%n",
                        cr.fqcn, cr.effectiveRan, cr.failures, cr.errors,
                        cr.assumptionSkipped + cr.ignored);
                // A quarantined class is still expected to RUN ≥1 test. If it contributes zero
                // (all assumption-skipped / cannot init headlessly) it belongs in the OSGi-excluded
                // manifest, not here — surface that loudly even though it doesn't gate the build.
                if (guardViolated) {
                    System.out.printf("    !! quarantined class ran 0 tests — move it to tools/osgi-excluded-tests.txt, not known-failing (%s)%n",
                            cr.reason());
                }
                continue;
            }

            totalFailures += cr.failures;
            totalErrors += cr.errors;
            if (guardViolated) {
                violations++;
            }
            String mark = classFailed ? "FAIL" : (guardViolated ? "EMPTY" : "ok  ");
            System.out.printf("[%s] %-70s ran=%d fail=%d err=%d skip=%d%n",
                    mark, cr.fqcn, cr.effectiveRan, cr.failures, cr.errors,
                    cr.assumptionSkipped + cr.ignored);
            if (guardViolated) {
                System.out.printf("    !! testsRun>0 VIOLATION: %s (%s)%n", cr.fqcn, cr.reason());
            }
        }

        // A process a test started and left running would hold the runner's output open after exit.
        reapDescendants();

        // Aggregate summary (mirrors AllPluginTestsRunner's ergonomic, plus the run-count guard it lacked).
        long junitBucket = results.stream().filter(r -> "JUNIT".equals(r.bucket())).count();
        long excludedBucket = results.size() - junitBucket;
        System.out.println();
        System.out.printf("SUMMARY: %d classes | %d tests run | %d failed | %d errors | %d empty-class violations%n",
                results.size(), totalEffective, totalFailures, totalErrors, violations);
        if (classify) {
            System.out.printf("CLASSIFY-SUMMARY: junit=%d excluded=%d (of %d classes)%n",
                    junitBucket, excludedBucket, results.size());
            System.exit(0); // classification never fails the build
        }
        if (knownFailClasses > 0) {
            System.out.printf("KNOWN-FAILING (quarantined, not gating): %d classes / %d failing tests — see tools/known-failing-tests.txt%n",
                    knownFailClasses, knownFailTests);
        }

        boolean ok = totalFailures == 0 && totalErrors == 0 && violations == 0;
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(ok ? 0 : 1);
    }

    /** System property holding the per-class deadline in seconds; {@code 0} disables it. */
    static final String CLASS_TIMEOUT_PROPERTY = "harness.classTimeoutSeconds";

    /** Deadline when the property is not set — over ten times the slowest class at the time of writing. */
    static final long DEFAULT_CLASS_TIMEOUT_SECONDS = 180;

    /** Exit code of a run ended by the deadline, distinct from a test failure (1) and bad usage (2). */
    static final int TIMEOUT_EXIT_CODE = 3;

    /**
     * How long the expiry report may take before the JVM is halted without it. Reporting writes to
     * standard output, which the stuck class may itself be blocking (a full pipe, a stream it
     * replaced), so the halt must not depend on the report finishing.
     */
    static final long REPORT_GRACE_MILLIS = 10_000;

    /** Longest the runner waits, in total, for the processes it ended to be gone before it exits. */
    static final long REAP_WAIT_MILLIS = 2_000;

    /**
     * Ends every process this JVM started, directly or through another process, that is still
     * running, printing one {@code [REAPED] pid <n> <command>} line for each. A process a test started
     * with the runner's standard output shared holds that output open after the runner exits, so
     * whatever reads it (the harness script's {@code tee}, CI's log capture) would wait for that
     * process instead of the run. Ending one is reported, not counted as a failure.
     *
     * <p>Every process is ended before anything is written, so output that blocks cannot keep one
     * alive. Waits at most {@link #REAP_WAIT_MILLIS} in total for them to go, then names any still
     * running on a {@code [REAP-FAILED]} line and returns. A failure to list or end them is reported
     * the same way and never escapes: the run's own verdict and exit must still follow.</p>
     *
     * <p>It reaches only processes that are still descendants: one that detached itself (its parent
     * exited and it was re-parented) is out of reach. Nothing here can make it stop a process that
     * ignores a forced kill, and no test pins that case — no such process can be started from a test —
     * so the bound on the exit is the wait limit here and, on a timeout, the unconditional halt already
     * armed.</p>
     */
    static void reapDescendants() {
        try {
            List<Ended> ended = endDescendants();
            for (Ended e : ended) {
                System.out.println(("[REAPED] pid " + e.process().pid() + " " + e.command()).stripTrailing());
            }
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REAP_WAIT_MILLIS);
            for (Ended e : ended) {
                long left = until - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                try {
                    e.process().onExit().get(left, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                    // gone or not, the exit does not wait longer
                }
            }
            for (Ended e : ended) {
                if (e.process().isAlive()) {
                    System.out.println("[REAP-FAILED] pid " + e.process().pid() + " still running after "
                            + REAP_WAIT_MILLIS + " ms");
                }
            }
        } catch (RuntimeException e) {
            System.out.println("[REAP-FAILED] " + e);
        }
    }

    /**
     * Forcibly ends every live descendant without writing anything, and returns what it ended. Lists
     * them twice, so a process started while the first ones were being ended is ended too.
     */
    private static List<Ended> endDescendants() {
        List<Ended> ended = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        for (int listing = 0; listing < 2; listing++) {
            List<ProcessHandle> fresh = ProcessHandle.current().descendants()
                    .filter(ProcessHandle::isAlive)
                    .filter(p -> seen.add(p.pid()))
                    .toList();
            if (fresh.isEmpty()) {
                break;
            }
            for (ProcessHandle p : fresh) {
                // read before the kill: a process that is gone no longer describes itself
                String command = p.info().commandLine().or(() -> p.info().command()).orElse("");
                p.destroyForcibly();
                ended.add(new Ended(p, command));
            }
        }
        return ended;
    }

    /** {@link #endDescendants()} for the last-resort halt, which must reach its halt whatever happens. */
    private static void endDescendantsQuietly() {
        try {
            endDescendants();
        } catch (RuntimeException e) {
            // the halt follows regardless
        }
    }

    private record Ended(ProcessHandle process, String command) {
    }

    static long parseClassTimeoutSeconds(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_CLASS_TIMEOUT_SECONDS;
        }
        long seconds;
        try {
            seconds = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            seconds = -1;
        }
        if (seconds < 0) {
            throw new IllegalArgumentException(CLASS_TIMEOUT_PROPERTY
                    + " must be a whole number of seconds (0 disables the deadline), was: " + value);
        }
        return seconds;
    }

    /**
     * Enforces the per-class deadline while the class runs on the CALLING thread. The class must stay
     * on the main thread: under {@code -XstartOnFirstThread} (the display pass on macOS) SWT can only
     * create its display there, so the deadline is kept by a separate daemon thread instead.
     *
     * <p>On expiry the run cannot continue — the stuck thread is the main thread, and whatever it holds
     * (a server, a lock, a static) could poison the classes after it. So the watchdog prints the
     * timeout with the test that was running, a dump of every thread, writes the class's XML with a
     * timeout error, and halts the JVM. The dump names, for each thread, the lock it waits on and the
     * thread that owns it, and the monitors and locks it holds, so a stall on a lock can be traced to
     * its holder from the dump alone ({@link #appendThread(StringBuilder, ThreadInfo)}).
     * {@code halt}, not {@code exit}: {@code exit} runs shutdown hooks, and a hook waiting on the same
     * stuck lock would recreate the hang.</p>
     *
     * <p>The expiry holds the per-class lock until the halt, and the class's completion takes the same
     * lock, so a class can never both time out and be reported as finished.</p>
     *
     * <p>The report goes to standard output, which the stuck class may be blocking, so expiry first
     * arms a second, unconditional halt {@link #REPORT_GRACE_MILLIS} later.</p>
     *
     * <p>{@code halt} ends this JVM but not the processes the stuck class started, and one sharing the
     * runner's standard output would keep a pipe reading it open. So after the report, and before the
     * halt, the expiry ends them ({@link #reapDescendants()}) — still under that second halt, so ending
     * them can neither delay the report nor keep the JVM alive past it. If the report never finishes,
     * the second halt ends them itself, silently, just before it halts.</p>
     */
    private static final class ClassWatchdog {
        private final long timeoutSeconds;
        private final File outDir;

        ClassWatchdog(long timeoutSeconds, File outDir) {
            this.timeoutSeconds = timeoutSeconds;
            this.outDir = outDir;
        }

        ClassResult run(String fqcn) {
            RecordingListener listener = new RecordingListener();
            if (timeoutSeconds == 0) {
                return runOne(fqcn, listener);
            }
            Watch watch = new Watch(listener);
            Thread classThread = Thread.currentThread();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            Thread waiter = new Thread(() -> awaitDeadline(watch, deadline, fqcn, classThread),
                    "harness-class-watchdog");
            waiter.setDaemon(true);
            waiter.start();
            try {
                return runOne(fqcn, listener);
            } finally {
                synchronized (watch) {
                    watch.done = true;
                    watch.notifyAll();
                }
            }
        }

        private void awaitDeadline(Watch watch, long deadline, String fqcn, Thread classThread) {
            synchronized (watch) {
                long remaining;
                while (!watch.done && (remaining = deadline - System.nanoTime()) > 0) {
                    try {
                        TimeUnit.NANOSECONDS.timedWait(watch, remaining);
                    } catch (InterruptedException e) {
                        // Nothing may disarm the deadline: keep waiting until it passes or the class ends.
                    }
                }
                if (!watch.done) {
                    expire(fqcn, classThread, watch.listener.running);
                }
            }
        }

        private void expire(String fqcn, Thread classThread, String runningTest) {
            Thread lastResort = new Thread(() -> {
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPORT_GRACE_MILLIS);
                long left;
                while ((left = until - System.nanoTime()) > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(left);
                    } catch (InterruptedException e) {
                        // keep the grace period whole
                    }
                }
                // The report is stuck, so the expiry never reached its own reap: nothing may outlive the halt.
                endDescendantsQuietly();
                Runtime.getRuntime().halt(TIMEOUT_EXIT_CODE);
            }, "harness-class-watchdog-last-resort");
            lastResort.setDaemon(true);
            lastResort.start();
            System.out.printf("[TIMEOUT] %s after %ds in %s — the run stops here; classes after it were not run%n",
                    fqcn, timeoutSeconds, runningTest != null ? runningTest
                            : "no test (class setup or teardown, or between tests)");
            System.out.println("---- thread dump ----");
            long dumpStarted = System.nanoTime();
            StringBuilder classThreadTrace = dumpThreads(classThread);
            System.out.printf("---- end of thread dump (%d ms) ----%n",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dumpStarted));

            if (outDir != null) {
                ClassResult cr = new ClassResult(fqcn);
                TestRecord r = new TestRecord(fqcn, "classTimeout");
                r.status = Status.ERROR;
                r.failureType = "timeout";
                r.failureMessage = "class did not finish within " + timeoutSeconds + "s";
                r.failureTrace = classThreadTrace.toString();
                r.timeMs = TimeUnit.SECONDS.toMillis(timeoutSeconds);
                cr.errors = 1;
                cr.records.add(r);
                writeXml(outDir, cr);
            }
            System.out.println("RESULT: FAIL");
            System.out.flush();
            System.err.flush();
            reapDescendants();
            System.out.flush();
            Runtime.getRuntime().halt(TIMEOUT_EXIT_CODE);
        }

        /**
         * Prints every thread, the class's own thread first, and returns the class thread's entry for the
         * XML record. Each entry carries the lock the thread waits on and its owner, the monitors held at
         * each frame and the locks held overall; {@link ThreadInfo#toString()} is not used because it cuts
         * the frames at eight. Where the platform cannot report locks, the dump falls back to frames only.
         */
        private static StringBuilder dumpThreads(Thread classThread) {
            ThreadInfo[] infos;
            try {
                ThreadMXBean threads = ManagementFactory.getThreadMXBean();
                infos = threads.dumpAllThreads(threads.isObjectMonitorUsageSupported(),
                        threads.isSynchronizerUsageSupported());
            } catch (RuntimeException | Error e) {
                // an Error here must not skip the XML record and the orderly halt that follow the dump
                System.out.println("(locks not available: " + e + ")");
                return dumpStackTraces(classThread);
            }
            StringBuilder classThreadTrace = new StringBuilder();
            for (ThreadInfo info : infos) {
                if (info != null && info.getThreadId() == classThread.threadId()) {
                    appendThread(classThreadTrace, info);
                }
            }
            System.out.print(classThreadTrace);
            for (ThreadInfo info : infos) {
                if (info != null && info.getThreadId() != classThread.threadId()) {
                    StringBuilder sb = new StringBuilder();
                    appendThread(sb, info);
                    System.out.print(sb);
                }
            }
            return classThreadTrace;
        }

        private static StringBuilder dumpStackTraces(Thread classThread) {
            Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
            StringBuilder classThreadTrace = new StringBuilder();
            appendThread(classThreadTrace, classThread, stacks.get(classThread));
            System.out.print(classThreadTrace);
            for (Map.Entry<Thread, StackTraceElement[]> e : stacks.entrySet()) {
                if (e.getKey() != classThread) {
                    StringBuilder sb = new StringBuilder();
                    appendThread(sb, e.getKey(), e.getValue());
                    System.out.print(sb);
                }
            }
            return classThreadTrace;
        }

        private static void appendThread(StringBuilder sb, ThreadInfo info) {
            sb.append('"').append(info.getThreadName()).append('"')
                    .append(info.isDaemon() ? " daemon" : "")
                    .append(' ').append(info.getThreadState());
            LockInfo waitingOn = info.getLockInfo();
            if (waitingOn != null) {
                sb.append(" on ").append(waitingOn);
                if (info.getLockOwnerName() != null) {
                    sb.append(" owned by \"").append(info.getLockOwnerName()).append("\" (id ")
                            .append(info.getLockOwnerId()).append(')');
                }
            }
            sb.append('\n');
            StackTraceElement[] frames = info.getStackTrace();
            MonitorInfo[] monitors = info.getLockedMonitors();
            for (int depth = 0; depth < frames.length; depth++) {
                sb.append("\tat ").append(frames[depth]).append('\n');
                for (MonitorInfo monitor : monitors) {
                    if (monitor.getLockedStackDepth() == depth) {
                        sb.append("\t- locked ").append(monitor).append('\n');
                    }
                }
            }
            for (LockInfo held : info.getLockedSynchronizers()) {
                sb.append("\t- locked ").append(held).append('\n');
            }
            sb.append('\n');
        }

        private static void appendThread(StringBuilder sb, Thread t, StackTraceElement[] frames) {
            sb.append('"').append(t.getName()).append('"')
                    .append(t.isDaemon() ? " daemon" : "")
                    .append(' ').append(t.getState()).append('\n');
            if (frames != null) {
                for (StackTraceElement frame : frames) {
                    sb.append("\tat ").append(frame).append('\n');
                }
            }
            sb.append('\n');
        }

        private static final class Watch {
            final RecordingListener listener;
            boolean done;

            Watch(RecordingListener listener) {
                this.listener = listener;
            }
        }
    }

    /** Loads a newline-delimited class list (ignoring blank lines and {@code #} comments). */
    private static Set<String> loadClassList(String path) {
        Set<String> out = new java.util.HashSet<>();
        if (path == null || path.isBlank()) {
            return out;
        }
        File f = new File(path);
        if (!f.isFile()) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                line = line.trim();
                if (!line.isEmpty()) {
                    out.add(line);
                }
            }
        } catch (IOException e) {
            System.err.println("WARN: could not read class list " + path + ": " + e.getMessage());
        }
        return out;
    }

    private static ClassResult runOne(String fqcn, RecordingListener listener) {
        ClassResult cr = new ClassResult(fqcn);
        Class<?> clazz;
        try {
            clazz = Class.forName(fqcn);
        } catch (Throwable t) {
            cr.loadError = true;
            cr.loadErrorDetail = t.getClass().getSimpleName() + ": " + t.getMessage();
            cr.errors = 1;
            TestRecord r = new TestRecord(fqcn, INIT_ERROR);
            r.status = Status.ERROR;
            r.failureType = t.getClass().getName();
            r.failureMessage = String.valueOf(t.getMessage());
            cr.records.add(r);
            return cr;
        }

        JUnitCore core = new JUnitCore();
        core.addListener(listener);
        try {
            core.run(clazz);
        } catch (Throwable t) {
            // Defensive: JUnitCore normally routes failures to the listener, but a catastrophic
            // class-init can escape. Treat as a load/init error.
            cr.loadError = true;
            cr.loadErrorDetail = t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        for (TestRecord r : listener.records.values()) {
            cr.records.add(r);
            if (r.isInitError()) {
                cr.errors++;   // count the synthetic init failure as an error, but NOT as effectiveRan
                continue;
            }
            switch (r.status) {
                case PASSED -> {
                    cr.effectiveRan++;
                    cr.passed++;
                }
                case FAILED -> {
                    cr.effectiveRan++;
                    cr.failures++;
                }
                case ERROR -> {
                    cr.effectiveRan++;
                    cr.errors++;
                }
                case ASSUMPTION_SKIPPED -> cr.assumptionSkipped++;
                case IGNORED -> cr.ignored++;
            }
        }
        return cr;
    }

    private static void writeXml(File outDir, ClassResult cr) {
        int tests = cr.records.size();
        int skipped = cr.assumptionSkipped + cr.ignored;
        double timeSec = cr.records.stream().mapToLong(r -> r.timeMs).sum() / 1000.0;

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"").append(esc(cr.fqcn)).append("\"")
                .append(" tests=\"").append(tests).append("\"")
                .append(" failures=\"").append(cr.failures).append("\"")
                .append(" errors=\"").append(cr.errors).append("\"")
                .append(" skipped=\"").append(skipped).append("\"")
                .append(" time=\"").append(String.format(java.util.Locale.ROOT, "%.3f", timeSec)).append("\">\n");

        for (TestRecord r : cr.records) {
            sb.append("  <testcase classname=\"").append(esc(r.className)).append("\"")
                    .append(" name=\"").append(esc(r.methodName)).append("\"")
                    .append(" time=\"").append(String.format(java.util.Locale.ROOT, "%.3f", r.timeMs / 1000.0)).append("\"");
            switch (r.status) {
                case FAILED -> {
                    sb.append(">\n");
                    sb.append("    <failure message=\"").append(esc(r.failureMessage)).append("\"")
                            .append(" type=\"").append(esc(r.failureType)).append("\">")
                            .append(esc(r.failureTrace)).append("</failure>\n");
                    sb.append("  </testcase>\n");
                }
                case ERROR -> {
                    sb.append(">\n");
                    sb.append("    <error message=\"").append(esc(r.failureMessage)).append("\"")
                            .append(" type=\"").append(esc(r.failureType)).append("\">")
                            .append(esc(r.failureTrace)).append("</error>\n");
                    sb.append("  </testcase>\n");
                }
                case ASSUMPTION_SKIPPED -> {
                    sb.append(">\n");
                    sb.append("    <skipped message=\"").append(esc(r.failureMessage)).append("\"/>\n");
                    sb.append("  </testcase>\n");
                }
                case IGNORED -> {
                    sb.append(">\n");
                    sb.append("    <skipped/>\n");
                    sb.append("  </testcase>\n");
                }
                case PASSED -> sb.append("/>\n");
            }
        }
        sb.append("</testsuite>\n");

        File f = new File(outDir, "TEST-" + cr.fqcn + ".xml");
        try {
            Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("WARN: could not write " + f + ": " + e.getMessage());
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    // Strip XML-1.0-illegal control chars (keep tab/newline/carriage-return).
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        out.append(' ');
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
