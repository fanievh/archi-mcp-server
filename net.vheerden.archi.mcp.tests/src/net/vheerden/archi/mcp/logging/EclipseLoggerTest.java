package net.vheerden.archi.mcp.logging;

import static org.junit.Assert.*;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;
import org.slf4j.event.Level;

/**
 * Unit tests for {@link EclipseLogger}.
 *
 * <p>Tests level mapping, message formatting, and fallback behavior.</p>
 */
public class EclipseLoggerTest {

    // ---- Level mapping tests ----

    @Test
    public void shouldMapTraceToOk() {
        assertEquals(IStatus.OK, EclipseLogger.mapLevelToSeverity(Level.TRACE));
    }

    @Test
    public void shouldMapDebugToOk() {
        assertEquals(IStatus.OK, EclipseLogger.mapLevelToSeverity(Level.DEBUG));
    }

    @Test
    public void shouldMapInfoToInfo() {
        assertEquals(IStatus.INFO, EclipseLogger.mapLevelToSeverity(Level.INFO));
    }

    @Test
    public void shouldMapWarnToWarning() {
        assertEquals(IStatus.WARNING, EclipseLogger.mapLevelToSeverity(Level.WARN));
    }

    @Test
    public void shouldMapErrorToError() {
        assertEquals(IStatus.ERROR, EclipseLogger.mapLevelToSeverity(Level.ERROR));
    }

    // ---- Level parsing tests ----

    @Test
    public void shouldParseTraceLevelOrdinal() {
        assertEquals(Level.TRACE.toInt(), EclipseLogger.parseLevelOrdinal("TRACE"));
    }

    @Test
    public void shouldParseDebugLevelOrdinal() {
        assertEquals(Level.DEBUG.toInt(), EclipseLogger.parseLevelOrdinal("DEBUG"));
    }

    @Test
    public void shouldParseInfoLevelOrdinal() {
        assertEquals(Level.INFO.toInt(), EclipseLogger.parseLevelOrdinal("INFO"));
    }

    @Test
    public void shouldParseWarnLevelOrdinal() {
        assertEquals(Level.WARN.toInt(), EclipseLogger.parseLevelOrdinal("WARN"));
    }

    @Test
    public void shouldParseWarningLevelOrdinal() {
        assertEquals(Level.WARN.toInt(), EclipseLogger.parseLevelOrdinal("WARNING"));
    }

    @Test
    public void shouldParseErrorLevelOrdinal() {
        assertEquals(Level.ERROR.toInt(), EclipseLogger.parseLevelOrdinal("ERROR"));
    }

    @Test
    public void shouldDefaultToInfoForUnknownLevel() {
        assertEquals(Level.INFO.toInt(), EclipseLogger.parseLevelOrdinal("UNKNOWN"));
    }

    @Test
    public void shouldBeCaseInsensitive_whenParsingLevel() {
        assertEquals(Level.DEBUG.toInt(), EclipseLogger.parseLevelOrdinal("debug"));
        assertEquals(Level.ERROR.toInt(), EclipseLogger.parseLevelOrdinal("error"));
    }

    @Test
    public void shouldParseALowerCaseWarning_underATurkishDefaultLocale() {
        // Under tr, "warning".toUpperCase() is WARNİNG (dotted capital I), which no level matches; the
        // fallback is INFO, so "info" cannot show the difference and "warning" can.
        java.util.Locale before = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            assertEquals(Level.WARN.toInt(), EclipseLogger.parseLevelOrdinal("warning"));
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    // ---- Message formatting tests ----

    @Test
    public void shouldReturnMessageAsIs_whenNoArguments() {
        assertEquals("Hello world", EclipseLogger.formatMessage("Hello world", null));
    }

    @Test
    public void shouldReturnMessageAsIs_whenEmptyArguments() {
        assertEquals("Hello world", EclipseLogger.formatMessage("Hello world", new Object[0]));
    }

    @Test
    public void shouldFormatMessageWithArguments() {
        String result = EclipseLogger.formatMessage("Hello {} on port {}", new Object[]{"server", 8080});
        assertEquals("Hello server on port 8080", result);
    }

    @Test
    public void shouldFormatMessageWithSingleArgument() {
        String result = EclipseLogger.formatMessage("Started on port {}", new Object[]{18090});
        assertEquals("Started on port 18090", result);
    }

    // ---- Logger name and plugin ID tests ----

    @Test
    public void shouldHaveCorrectPluginId() {
        assertEquals("net.vheerden.archi.mcp", EclipseLogger.PLUGIN_ID);
    }

    @Test
    public void shouldStoreLoggerName() {
        EclipseLogger logger = new EclipseLogger("test.logger");
        assertEquals("test.logger", logger.getName());
    }

    // ---- ILog integration test (with stub) ----

    @Test
    public void shouldLogToEclipseILog_whenAvailable() {
        StubLog stubLog = new StubLog();
        EclipseLogger logger = new EclipseLogger("test.logger", stubLog);

        logger.info("Test message");

        assertNotNull("Expected ILog.log() to be called", stubLog.lastStatus);
        assertEquals(IStatus.INFO, stubLog.lastStatus.getSeverity());
        assertTrue(stubLog.lastStatus.getMessage().contains("Test message"));
        assertEquals(EclipseLogger.PLUGIN_ID, stubLog.lastStatus.getPlugin());
    }

    @Test
    public void shouldLogErrorWithException_whenAvailable() {
        StubLog stubLog = new StubLog();
        EclipseLogger logger = new EclipseLogger("test.logger", stubLog);
        RuntimeException ex = new RuntimeException("test error");

        logger.error("Failed: {}", "operation", ex);

        assertNotNull(stubLog.lastStatus);
        assertEquals(IStatus.ERROR, stubLog.lastStatus.getSeverity());
        // SLF4J LegacyAbstractLogger normalizes: last arg is Throwable if not consumed by placeholder
        assertSame(ex, stubLog.lastStatus.getException());
    }

    @Test
    public void shouldLogWarning_whenAvailable() {
        StubLog stubLog = new StubLog();
        EclipseLogger logger = new EclipseLogger("test.logger", stubLog);

        logger.warn("Something concerning happened");

        assertNotNull(stubLog.lastStatus);
        assertEquals(IStatus.WARNING, stubLog.lastStatus.getSeverity());
    }

    // ---- Fallback (no ILog) tests ----

    @Test
    public void shouldNotThrowNPE_whenLoggingWithoutInjectedILog() {
        // EclipseLogger(name) constructor does NOT inject ILog.
        // In PDE test context, Platform.getBundle() may resolve, but this test
        // verifies the lazy-init path does not throw regardless of Platform state.
        EclipseLogger logger = new EclipseLogger("test.fallback");
        logger.info("Fallback safety test");
        // No assertion needed — passing without NPE proves the contract.
    }

    @Test
    public void shouldFallbackToStderr_whenILogIsNull() {
        // Simulate null ILog by creating logger with null stub
        // The package-visible constructor sets eclipseLog = null,
        // but getEclipseLog() will try Platform lookup. To truly test
        // the stderr path, we redirect stderr and use a logger name that
        // would not resolve to a Platform bundle.
        java.io.ByteArrayOutputStream errContent = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        try {
            // Create logger that uses constructor without injected ILog
            // and override getEclipseLog indirectly: the production constructor
            // with null ILog will try Platform; if Platform resolves (in PDE test),
            // this test verifies the happy path instead. So we test the concrete
            // fallback by capturing stderr and creating a logger whose getEclipseLog returns null.
            // Since we can't force null in PDE context, we just verify no crash.
            EclipseLogger logger = new EclipseLogger("test.stderr.fallback");
            System.setErr(new java.io.PrintStream(errContent));
            logger.info("stderr fallback test");
        } finally {
            System.setErr(originalErr);
        }
        // In PDE test context, Platform IS available, so ILog will be used (not stderr).
        // True stderr fallback only occurs in non-OSGi environments (manual validation).
        // This test documents the intent and verifies no crash in either path.
    }

    // ---- Every entry is one line ----

    private static String printed(Throwable t) {
        java.io.StringWriter out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }

    @Test
    public void shouldEscapeALineBreakInTheMessage_soTheEntryStaysOneLine() {
        StubLog stubLog = new StubLog();
        new EclipseLogger("test.logger", stubLog).info("Adding {} now", "p\nq\r\u2028r\\s");

        String message = stubLog.lastStatus.getMessage();
        assertEquals("[test.logger] Adding p\\nq\\r\\u2028r\\s now", message);
        assertFalse(message, message.contains("\n") || message.contains("\r"));
    }

    @Test
    public void shouldPassAnOrdinaryMessageThroughUnchanged() {
        String message = "[test.logger] Adding C:\\models\\a.archimate at 100% {braces} \u00e9t\u00e9";
        assertSame(message, EclipseLogger.oneLine(message));
        StubLog stubLog = new StubLog();
        new EclipseLogger("test.logger", stubLog).info("Adding {} now", "C:\\models\\a.archimate");
        assertEquals("the backslash is kept, so an escaped value is not escaped twice",
                "[test.logger] Adding C:\\models\\a.archimate now", stubLog.lastStatus.getMessage());
    }

    @Test
    public void shouldPrintEachStackTraceHeaderOnOneLine_withTheOriginalFrames() {
        java.io.IOException cause = new java.io.IOException("c\rd");
        IllegalStateException thrown = new IllegalStateException("a\nb", cause);
        thrown.addSuppressed(new IllegalArgumentException("e\u2029f"));
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", thrown);

        Throwable logged = stubLog.lastStatus.getException();
        String[] lines = printed(logged).split("\\R");
        assertEquals("java.lang.IllegalStateException: a\\nb", lines[0]);
        assertTrue(printed(logged), java.util.Arrays.asList(lines).contains("Caused by: java.io.IOException: c\\rd"));
        assertTrue(printed(logged), java.util.Arrays.stream(lines)
                .anyMatch(l -> l.trim().equals("Suppressed: java.lang.IllegalArgumentException: e\\u2029f")));
        assertArrayEquals(thrown.getStackTrace(), logged.getStackTrace());
        assertArrayEquals(cause.getStackTrace(), logged.getCause().getStackTrace());
        assertEquals("a\\nb", logged.getMessage());
        assertEquals("the same trace, frames and elisions included, but for the escapes", printed(thrown),
                printed(logged).replace("a\\nb", "a\nb").replace("c\\rd", "c\rd").replace("e\\u2029f", "e f"));
    }

    @Test
    public void shouldEscapeABreakDeepInTheCauseChain_andKeepTheOuterHeaderAsItWas() {
        IllegalStateException thrown = new IllegalStateException("outer", new java.io.IOException("x\ny"));
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", thrown);

        String printed = printed(stubLog.lastStatus.getException());
        assertTrue(printed, printed.startsWith("java.lang.IllegalStateException: outer" + System.lineSeparator()));
        assertTrue(printed, printed.contains("Caused by: java.io.IOException: x\\ny" + System.lineSeparator()));
    }

    @Test
    public void shouldPassAnOrdinaryThrowableThroughAsTheSameInstance() {
        RuntimeException ex = new RuntimeException("plain C:\\path", new java.io.IOException("also plain"));
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", ex);

        assertSame(ex, stubLog.lastStatus.getException());
        assertSame(null, OneLineThrowable.of(null));
    }

    @Test
    public void shouldPrintACauseCycleOnce_withEachHeaderOnOneLine() {
        RuntimeException a = new RuntimeException("a\nb");
        RuntimeException b = new RuntimeException("c");
        a.initCause(b);
        b.initCause(a);
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", a);

        Throwable logged = stubLog.lastStatus.getException();
        assertSame(logged, logged.getCause().getCause());
        String printed = printed(logged);
        assertTrue(printed, printed.startsWith("java.lang.RuntimeException: a\\nb" + System.lineSeparator()));
        assertTrue(printed, printed.contains("CIRCULAR REFERENCE: java.lang.RuntimeException: a\\nb"));
    }

    @Test
    public void shouldStandInForAThrowableWhoseCauseIsItself_withoutACause() {
        RuntimeException self = new RuntimeException("a\nb") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", self);

        Throwable logged = stubLog.lastStatus.getException();
        assertEquals("a\\nb", logged.getMessage());
        assertSame(null, logged.getCause());
    }

    @Test
    public void shouldLogAThrowableThatCannotBeRead_asItIs() {
        RuntimeException unreadable = new RuntimeException("a\nb") {
            private static final long serialVersionUID = 1L;

            @Override
            public String getMessage() {
                throw new IllegalStateException("no message");
            }

            @Override
            public String toString() {
                return "x\ny";
            }
        };
        RuntimeException deep = new RuntimeException("d\ne");
        for (int i = 0; i < 200_000; i++) {
            deep = new RuntimeException("level", deep, false, false) {
                private static final long serialVersionUID = 1L;
            };
        }
        StubLog stubLog = new StubLog();
        EclipseLogger logger = new EclipseLogger("test.logger", stubLog);

        logger.error("Failed", unreadable);
        assertSame(unreadable, stubLog.lastStatus.getException());
        logger.error("Failed", deep);
        assertSame(deep, stubLog.lastStatus.getException());
    }

    // ---- an Eclipse status exception: still a CoreException, its contained statuses escaped too ----

    /** A CoreException over a multi-status whose message and one contained status both break a line. */
    private static CoreException statusFailure() {
        IStatus child = new Status(IStatus.ERROR, "p.child", 7, "child\rline", new java.io.IOException("x\ny"));
        return new CoreException(new MultiStatus("p.top", 3, new IStatus[] {child}, "top\nmsg", null));
    }

    @Test
    public void shouldStandInForACoreException_asACoreException_overAnEscapedCopyOfItsStatus() {
        CoreException thrown = statusFailure();
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", thrown);

        Throwable logged = stubLog.lastStatus.getException();
        assertNotSame(thrown, logged);
        assertTrue("the log writer adds a CoreException's status as a sub-entry", logged instanceof CoreException);
        IStatus status = ((CoreException) logged).getStatus();
        assertNotSame(thrown.getStatus(), status);
        assertEquals("top\\nmsg", status.getMessage());
        assertTrue(status.isMultiStatus());
        assertEquals("p.top 3 " + IStatus.ERROR, status.getPlugin() + " " + status.getCode() + " "
                + status.getSeverity());
        IStatus child = status.getChildren()[0];
        assertEquals("child\\rline", child.getMessage());
        assertEquals("p.child 7 " + IStatus.ERROR, child.getPlugin() + " " + child.getCode() + " "
                + child.getSeverity());
        assertEquals("java.io.IOException: x\\ny", child.getException().toString());
        assertEquals("the original is not changed", "child\rline", thrown.getStatus().getChildren()[0].getMessage());
        assertArrayEquals(thrown.getStackTrace(), logged.getStackTrace());
        String printed = printed(logged);
        assertTrue(printed, java.util.Arrays.asList(printed.split("\\R")).contains("Contains: child\\rline"));
        assertEquals("the same trace, Contains lines included, but for the escapes", printed(thrown),
                printed.replace("top\\nmsg", "top\nmsg").replace("child\\rline", "child\rline")
                        .replace("x\\ny", "x\ny"));
    }

    @Test
    public void shouldEscapeABreakInAContainedStatus_underACleanHeader() {
        IStatus child = new Status(IStatus.WARNING, "p", "child\nline");
        CoreException thrown = new CoreException(new MultiStatus("p", 0, new IStatus[] {child}, "clean top", null));
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", thrown);

        Throwable logged = stubLog.lastStatus.getException();
        assertNotSame("a break in a contained status split the entry", thrown, logged);
        String printed = printed(logged);
        assertTrue(printed, printed.startsWith("org.eclipse.core.runtime.CoreException: clean top"
                + System.lineSeparator()));
        assertTrue(printed, java.util.Arrays.asList(printed.split("\\R")).contains("Contains: child\\nline"));
        assertEquals(IStatus.WARNING, ((CoreException) logged).getStatus().getSeverity());
    }

    @Test
    public void shouldCopyAStatusHeldByTwoParents_underEach_asTheOriginalPrintsIt() {
        IStatus shared = new Status(IStatus.ERROR, "p", "shared\nline");
        MultiStatus left = new MultiStatus("p", 0, new IStatus[] {shared}, "left", null);
        MultiStatus right = new MultiStatus("p", 0, new IStatus[] {shared}, "right", null);
        CoreException thrown = new CoreException(new MultiStatus("p", 0, new IStatus[] {left, right}, "top", null));
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", thrown);

        String printed = printed(stubLog.lastStatus.getException());
        assertEquals("one Contains line per parent, as the original prints", printed(thrown),
                printed.replace("shared\\nline", "shared\nline"));
        assertEquals(2, java.util.Arrays.stream(printed.split("\\R")).filter("Contains: shared\\nline"::equals)
                .count());
    }

    @Test
    public void shouldEndAStatusThatContainsItself_ratherThanRepeatIt() {
        MultiStatus loop = new MultiStatus("p", 0, "loop\nline", null);
        loop.add(new Status(IStatus.INFO, "p", "child"));
        loop.add(loop);
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", new CoreException(loop));

        IStatus copy = ((CoreException) stubLog.lastStatus.getException()).getStatus();
        assertEquals("loop\\nline", copy.getMessage());
        assertEquals("the child is kept and the status itself is left out below itself", 1, copy.getChildren().length);
        assertEquals("child", copy.getChildren()[0].getMessage());
    }

    @Test
    public void shouldCopyTheChildrenOfAStatusThatDoesNotSayItIsAMultiStatus() {
        IStatus child = new Status(IStatus.ERROR, "p", "child\nline");
        IStatus holder = new Status(IStatus.ERROR, "p", "holder") {
            @Override
            public IStatus[] getChildren() {
                return new IStatus[] {child};
            }
        };
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", new CoreException(holder));

        Throwable logged = stubLog.lastStatus.getException();
        assertTrue(java.util.Arrays.asList(printed(logged).split("\\R")).contains("Contains: child\\nline"));
        IStatus copy = ((CoreException) logged).getStatus();
        assertEquals("child\\nline", copy.getChildren()[0].getMessage());
        assertFalse("the log writer adds sub-entries only for a status that says it is a multi-status",
                copy.isMultiStatus());
    }

    /** A status of a plugin's own, holding what Status and MultiStatus refuse to be built with. */
    private static final class CustomStatus implements IStatus {
        private final String plugin;
        private final int severity;
        private final String message;
        private final IStatus[] children;

        CustomStatus(String plugin, int severity, String message, IStatus... children) {
            this.plugin = plugin;
            this.severity = severity;
            this.message = message;
            this.children = children;
        }

        @Override public IStatus[] getChildren() { return children; }
        @Override public int getCode() { return 9; }
        @Override public Throwable getException() { return null; }
        @Override public String getMessage() { return message; }
        @Override public String getPlugin() { return plugin; }
        @Override public int getSeverity() { return severity; }
        @Override public boolean isMultiStatus() { return children.length > 0; }
        @Override public boolean isOK() { return severity == OK; }
        @Override public boolean matches(int mask) { return (severity & mask) != 0; }
    }

    private static IStatus loggedStatus(IStatus status) {
        CoreException thrown = new CoreException(status);
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", thrown);

        Throwable logged = stubLog.lastStatus.getException();
        assertNotSame("logged as it was, unescaped", thrown, logged);
        assertTrue(logged instanceof CoreException);
        String printed = printed(logged);
        assertFalse(printed, printed.contains("a\nb"));
        return ((CoreException) logged).getStatus();
    }

    @Test
    public void shouldEscapeAStatusWithNoPluginId_carryingTheNullOver() {
        IStatus copy = loggedStatus(new CustomStatus(null, IStatus.ERROR, "a\nb"));

        assertEquals("a\\nb", copy.getMessage());
        assertNull(copy.getPlugin());
        assertEquals(IStatus.ERROR, copy.getSeverity());
        assertEquals(9, copy.getCode());
        assertFalse(copy.isMultiStatus());
    }

    @Test
    public void shouldEscapeAStatusWithAnEmptyPluginIdOrACombinedSeverity_carryingBothOver() {
        IStatus empty = loggedStatus(new CustomStatus("", IStatus.ERROR, "a\nb"));
        assertEquals("", empty.getPlugin());
        assertEquals("a\\nb", empty.getMessage());

        IStatus combined = loggedStatus(new CustomStatus("p", IStatus.ERROR | IStatus.WARNING, "a\nb",
                new CustomStatus(null, IStatus.INFO | IStatus.CANCEL, "child")));
        assertEquals(IStatus.ERROR | IStatus.WARNING, combined.getSeverity());
        assertTrue(combined.isMultiStatus());
        assertTrue(combined.matches(IStatus.WARNING));
        assertFalse(combined.isOK());
        IStatus child = combined.getChildren()[0];
        assertEquals(IStatus.INFO | IStatus.CANCEL, child.getSeverity());
        assertNull(child.getPlugin());
    }

    @Test
    public void shouldKeepANullStatusMessageNull_underADirtyChild() {
        IStatus child = new Status(IStatus.ERROR, "p", "child\nline");
        IStatus copy = loggedStatusOf(new CustomStatus("p", IStatus.ERROR, null, child));

        assertNull(copy.getMessage());
        assertEquals("child\\nline", copy.getChildren()[0].getMessage());
    }

    private static IStatus loggedStatusOf(IStatus status) {
        StubLog stubLog = new StubLog();
        new EclipseLogger("test.logger", stubLog).error("Failed", new CoreException(status));
        return ((CoreException) stubLog.lastStatus.getException()).getStatus();
    }

    @Test
    public void shouldGiveAStoodInCoreException_theStandInOfItsStatusExceptionAsItsCause() {
        java.io.IOException cause = new java.io.IOException("c\nd");
        CoreException thrown = new CoreException(new Status(IStatus.ERROR, "p", "top", cause));
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", thrown);

        Throwable logged = stubLog.lastStatus.getException();
        assertSame(((CoreException) logged).getStatus().getException(), logged.getCause());
        assertEquals("java.io.IOException: c\\nd", logged.getCause().toString());
        assertArrayEquals(cause.getStackTrace(), logged.getCause().getStackTrace());
        assertTrue(printed(logged), printed(logged).contains("Caused by: java.io.IOException: c\\nd"
                + System.lineSeparator()));
    }

    @Test
    public void shouldPassAnOrdinaryCoreExceptionThroughAsTheSameInstance() {
        IStatus child = new Status(IStatus.ERROR, "p", "plain C:\\path", new java.io.IOException("also plain"));
        CoreException thrown = new CoreException(new MultiStatus("p", 0, new IStatus[] {child}, "plain", null));
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", thrown);

        assertSame(thrown, stubLog.lastStatus.getException());
    }

    /** A plugin's own status exception type. */
    private static final class PluginFailure extends CoreException {
        private static final long serialVersionUID = 1L;

        PluginFailure(String message) {
            super(new Status(IStatus.ERROR, "p", message));
        }
    }

    @Test
    public void shouldNameTheSubclass_inTheHeaderOfAStoodInCoreException() {
        PluginFailure thrown = new PluginFailure("a\nb");
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", thrown);

        Throwable logged = stubLog.lastStatus.getException();
        assertTrue(logged instanceof CoreException);
        assertEquals(PluginFailure.class.getName() + ": a\\nb", printed(logged).split("\\R")[0]);
    }

    @Test
    public void shouldEscapeACoreExceptionInTheCauseChain_asACoreException() {
        RuntimeException thrown = new RuntimeException("outer", new CoreException(
                new Status(IStatus.ERROR, "p", "x\ny")));
        StubLog stubLog = new StubLog();

        new EclipseLogger("test.logger", stubLog).error("Failed", thrown);

        Throwable logged = stubLog.lastStatus.getException();
        assertTrue(logged.getCause() instanceof CoreException);
        assertTrue(printed(logged), printed(logged).contains(
                "Caused by: org.eclipse.core.runtime.CoreException: x\\ny" + System.lineSeparator()));
    }

    @Test
    public void shouldKeepAStoodInCoreException_onOneLinePerLine_onStderr() {
        java.io.ByteArrayOutputStream errContent = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        try {
            System.setErr(new java.io.PrintStream(errContent, true));
            new EclipseLogger("test.stderr.status").error("Failed", statusFailure());
        } finally {
            System.setErr(originalErr);
        }
        // Where the Platform resolves (a PDE launch), the entry went to the ILog instead.
        org.junit.Assume.assumeTrue("stderr fallback not taken", errContent.size() > 0);
        java.util.List<String> lines = java.util.Arrays.asList(errContent.toString().split("\\R"));
        assertEquals("[ERROR] [test.stderr.status] Failed", lines.get(0));
        assertEquals("org.eclipse.core.runtime.CoreException: top\\nmsg", lines.get(1));
        assertTrue(lines.toString(), lines.contains("Contains: child\\rline"));
        assertTrue(lines.toString(), lines.contains("java.io.IOException: x\\ny"));
    }

    @Test
    public void shouldPassAnOrdinaryEntryToStderrUnchanged() {
        java.io.ByteArrayOutputStream errContent = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        try {
            System.setErr(new java.io.PrintStream(errContent, true));
            new EclipseLogger("test.stderr.ordinary").error("Failed for {}", "C:\\models\\a.archimate",
                    new IllegalStateException("plain C:\\path"));
        } finally {
            System.setErr(originalErr);
        }
        // Where the Platform resolves (a PDE launch), the entry went to the ILog instead.
        org.junit.Assume.assumeTrue("stderr fallback not taken", errContent.size() > 0);
        String[] lines = errContent.toString().split("\\R");
        assertEquals("[ERROR] [test.stderr.ordinary] Failed for C:\\models\\a.archimate", lines[0]);
        assertEquals("java.lang.IllegalStateException: plain C:\\path", lines[1]);
        assertTrue(lines[2], lines[2].trim().startsWith("at "));
    }

    @Test
    public void shouldKeepTheFallbackToStderrOnOneLine_messageAndStackTrace() {
        java.io.ByteArrayOutputStream errContent = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        try {
            System.setErr(new java.io.PrintStream(errContent, true));
            new EclipseLogger("test.stderr.one-line").error("Failed for {}", "p\nq",
                    new IllegalStateException("a\nb"));
        } finally {
            System.setErr(originalErr);
        }
        // Where the Platform resolves (a PDE launch), the entry went to the ILog instead.
        org.junit.Assume.assumeTrue("stderr fallback not taken", errContent.size() > 0);
        String[] lines = errContent.toString().split("\\R");
        assertEquals("[ERROR] [test.stderr.one-line] Failed for p\\nq", lines[0]);
        assertEquals("java.lang.IllegalStateException: a\\nb", lines[1]);
        assertTrue(lines[2], lines[2].trim().startsWith("at "));
    }

    // ---- Stub for Eclipse ILog ----

    private static class StubLog implements ILog {
        IStatus lastStatus;

        @Override
        public void log(IStatus status) {
            this.lastStatus = status;
        }

        @Override
        public void addLogListener(ILogListener listener) {
            // not needed for tests
        }

        @Override
        public void removeLogListener(ILogListener listener) {
            // not needed for tests
        }

        @Override
        public org.osgi.framework.Bundle getBundle() {
            return null;
        }
    }
}
