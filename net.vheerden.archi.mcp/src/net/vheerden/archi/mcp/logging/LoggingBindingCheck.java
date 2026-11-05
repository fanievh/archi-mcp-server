package net.vheerden.archi.mcp.logging;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.slf4j.ILoggerFactory;

/**
 * Reports, in the Eclipse log, that the plugin's SLF4J output is not reaching it.
 *
 * <p>SLF4J finds {@link EclipseLogServiceProvider} through
 * {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider}. When that file is missing from the
 * installed bundle, SLF4J falls back to a no-operation factory and every logger in the bundle,
 * including the embedded Jetty and MCP SDK, discards its output. SLF4J's own warning about this is
 * hidden by Archi's {@code -Dslf4j.internal.verbosity=ERROR}, so nothing would say logging is off.</p>
 *
 * <p>This is the one place that writes to {@link ILog} directly instead of through SLF4J: the thing
 * it reports is SLF4J being unbound, so it cannot use SLF4J to report it.</p>
 */
public final class LoggingBindingCheck {

    private LoggingBindingCheck() {
    }

    /**
     * Writes one warning to {@code log} unless {@code factory} is the plugin's own
     * {@link EclipseLoggerFactory}. Never throws: a failed check must not stop the plugin starting.
     *
     * @param factory the factory SLF4J bound, normally {@code LoggerFactory.getILoggerFactory()}
     * @param log the plugin's Eclipse log; nothing is written when it is {@code null}
     */
    public static void warnIfUnbound(ILoggerFactory factory, ILog log) {
        if (factory instanceof EclipseLoggerFactory || log == null) {
            return;
        }
        try {
            String bound = factory == null ? "none" : factory.getClass().getName();
            log.log(new Status(IStatus.WARNING, EclipseLogger.PLUGIN_ID,
                    "The plugin's own log output is disabled: SLF4J is bound to " + bound
                    + " instead of " + EclipseLoggerFactory.class.getName()
                    + ". The installed bundle is probably missing"
                    + " META-INF/services/org.slf4j.spi.SLF4JServiceProvider."));
        } catch (RuntimeException e) {
            // Reporting must never stop the plugin from starting.
        }
    }
}
