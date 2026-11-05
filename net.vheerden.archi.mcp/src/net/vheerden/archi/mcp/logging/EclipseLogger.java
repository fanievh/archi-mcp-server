package net.vheerden.archi.mcp.logging;

import java.util.Locale;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import net.vheerden.archi.mcp.model.CallerText;

/**
 * SLF4J logger implementation that forwards log messages to Eclipse's ILog system.
 *
 * <p>Maps SLF4J levels to Eclipse IStatus severities:</p>
 * <ul>
 *   <li>TRACE, DEBUG → {@link IStatus#OK} (shown in Error Log only with "All" filter)</li>
 *   <li>INFO → {@link IStatus#INFO}</li>
 *   <li>WARN → {@link IStatus#WARNING}</li>
 *   <li>ERROR → {@link IStatus#ERROR}</li>
 * </ul>
 *
 * <p>The ILog instance is lazily resolved on first use to handle early startup
 * when the Eclipse Platform may not yet be available. Falls back to System.err
 * if Platform is unavailable.</p>
 *
 * <p>Log level filtering respects the {@code mcp.server.logLevel} preference
 * when the McpPlugin is available.</p>
 */
public class EclipseLogger extends LegacyAbstractLogger {

    private static final long serialVersionUID = 1L;

    static final String PLUGIN_ID = "net.vheerden.archi.mcp";

    /** Lazily resolved Eclipse ILog instance. Volatile for thread-safe lazy init across Jetty threads. */
    private transient volatile ILog eclipseLog;

    /** Cached effective log level ordinal for fast comparison. Volatile for cross-thread visibility. */
    private transient volatile int effectiveLevelOrdinal = -1;

    /** Timestamp of last level check to avoid reading preferences on every call. Volatile for cross-thread visibility. */
    private transient volatile long lastLevelCheckTime;

    /** Re-check preference every 5 seconds */
    private static final long LEVEL_CHECK_INTERVAL_MS = 5000;

    EclipseLogger(String name) {
        this.name = name;
    }

    /**
     * Constructor for testing — allows injecting a mock ILog.
     */
    EclipseLogger(String name, ILog eclipseLog) {
        this.name = name;
        this.eclipseLog = eclipseLog;
    }

    private ILog getEclipseLog() {
        if (eclipseLog == null) {
            try {
                Bundle bundle = Platform.getBundle(PLUGIN_ID);
                if (bundle != null) {
                    eclipseLog = Platform.getLog(bundle);
                }
            } catch (Exception e) {
                // Platform not yet available — fall back to stderr
            }
        }
        return eclipseLog;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(
            Level level, Marker marker, String messagePattern,
            Object[] arguments, Throwable throwable) {

        String formattedMessage = formatMessage(messagePattern, arguments);
        int severity = mapLevelToSeverity(level);

        // Prefix with logger name for clarity in Error Log. Every entry is one line, whatever a value in it
        // holds: a line break in a message, or in the header a stack trace prints for each throwable, would
        // split the entry or forge another.
        String logMessage = oneLine("[" + name + "] " + formattedMessage);
        Throwable logged = OneLineThrowable.of(throwable);

        ILog log = getEclipseLog();
        if (log != null) {
            IStatus status = new Status(severity, PLUGIN_ID, logMessage, logged);
            log.log(status);
        } else {
            // Fallback to stderr if Eclipse platform not yet available
            System.err.println("[" + level + "] " + logMessage);
            if (logged != null) {
                logged.printStackTrace(System.err);
            }
        }
    }

    // ---- Level checking ----

    @Override
    public boolean isTraceEnabled() {
        return isLevelEnabled(Level.TRACE);
    }

    @Override
    public boolean isDebugEnabled() {
        return isLevelEnabled(Level.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return isLevelEnabled(Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return isLevelEnabled(Level.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return isLevelEnabled(Level.ERROR);
    }

    private boolean isLevelEnabled(Level level) {
        return level.toInt() >= getEffectiveLevelOrdinal();
    }

    /**
     * Returns the effective log level ordinal, caching and re-checking periodically.
     */
    private int getEffectiveLevelOrdinal() {
        long now = System.currentTimeMillis();
        if (effectiveLevelOrdinal < 0 || (now - lastLevelCheckTime) > LEVEL_CHECK_INTERVAL_MS) {
            effectiveLevelOrdinal = resolveLogLevelOrdinal();
            lastLevelCheckTime = now;
        }
        return effectiveLevelOrdinal;
    }

    /**
     * Resolves the configured log level from McpPlugin preferences.
     * Returns INFO level ordinal as default if preferences are unavailable.
     */
    private int resolveLogLevelOrdinal() {
        try {
            var plugin = net.vheerden.archi.mcp.McpPlugin.getDefault();
            if (plugin != null) {
                String levelStr = plugin.getLogLevel();
                if (levelStr != null) {
                    return parseLevelOrdinal(levelStr);
                }
            }
        } catch (Exception e) {
            // Plugin not yet activated or preference read failed — use default
        }
        return Level.INFO.toInt();
    }

    /**
     * Parses a log level string to its SLF4J Level ordinal.
     */
    static int parseLevelOrdinal(String levelStr) {
        return switch (levelStr.toUpperCase(Locale.ROOT)) {
            case "TRACE" -> Level.TRACE.toInt();
            case "DEBUG" -> Level.DEBUG.toInt();
            case "INFO" -> Level.INFO.toInt();
            case "WARN", "WARNING" -> Level.WARN.toInt();
            case "ERROR" -> Level.ERROR.toInt();
            default -> Level.INFO.toInt();
        };
    }

    // ---- Level mapping ----

    /**
     * Maps SLF4J Level to Eclipse IStatus severity.
     */
    static int mapLevelToSeverity(Level level) {
        return switch (level) {
            case ERROR -> IStatus.ERROR;
            case WARN -> IStatus.WARNING;
            case INFO -> IStatus.INFO;
            case DEBUG, TRACE -> IStatus.OK;
        };
    }

    /**
     * {@code message} with every character {@link CallerText#loggedMessage} escapes escaped, or the same
     * instance when it holds none. The backslash is kept, so a value a call site already escaped is not
     * escaped twice.
     */
    static String oneLine(String message) {
        String escaped = CallerText.loggedMessage(message);
        return escaped.equals(message) ? message : escaped;
    }

    /**
     * Formats a message pattern with arguments using SLF4J's MessageFormatter.
     */
    static String formatMessage(String messagePattern, Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return messagePattern;
        }
        return MessageFormatter.basicArrayFormat(messagePattern, arguments);
    }
}
