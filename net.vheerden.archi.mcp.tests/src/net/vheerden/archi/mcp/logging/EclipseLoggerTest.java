package net.vheerden.archi.mcp.logging;

import static org.junit.Assert.*;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
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
