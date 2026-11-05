package net.vheerden.archi.mcp.logging;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.junit.Test;
import org.slf4j.helpers.NOPLoggerFactory;

/**
 * Unit tests for {@link LoggingBindingCheck}.
 */
public class LoggingBindingCheckTest {

    @Test
    public void shouldLogNothing_whenSlf4jIsBoundToThePlugin() {
        RecordingLog log = new RecordingLog();

        LoggingBindingCheck.warnIfUnbound(new EclipseLoggerFactory(), log);

        assertTrue("Expected no log entry, got " + log.statuses, log.statuses.isEmpty());
    }

    @Test
    public void shouldLogOneWarningNamingTheFactory_whenSlf4jFellBackToNop() {
        RecordingLog log = new RecordingLog();

        LoggingBindingCheck.warnIfUnbound(new NOPLoggerFactory(), log);

        assertEquals(1, log.statuses.size());
        IStatus status = log.statuses.get(0);
        assertEquals(IStatus.WARNING, status.getSeverity());
        assertEquals(EclipseLogger.PLUGIN_ID, status.getPlugin());
        assertTrue(status.getMessage(), status.getMessage().contains("org.slf4j.helpers.NOPLoggerFactory"));
        assertTrue(status.getMessage(), status.getMessage().contains("disabled"));
    }

    @Test
    public void shouldLogOneWarning_whenTheFactoryIsNull() {
        RecordingLog log = new RecordingLog();

        LoggingBindingCheck.warnIfUnbound(null, log);

        assertEquals(1, log.statuses.size());
        assertEquals(IStatus.WARNING, log.statuses.get(0).getSeverity());
    }

    @Test
    public void shouldNotThrow_whenTheLogIsNull() {
        LoggingBindingCheck.warnIfUnbound(new NOPLoggerFactory(), null);
    }

    private static class RecordingLog implements ILog {
        final List<IStatus> statuses = new ArrayList<>();

        @Override
        public void log(IStatus status) {
            statuses.add(status);
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
