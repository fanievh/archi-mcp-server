package net.vheerden.archi.mcp.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * SLF4J 2.0 service provider that bridges SLF4J logging to Eclipse's ILog system.
 *
 * <p>Discovered via {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider}.
 * Replaces {@code slf4j-simple} so that MCP SDK, Jetty, and application logs
 * appear in ArchimateTool's Error Log view instead of System.err.</p>
 */
public class EclipseLogServiceProvider implements SLF4JServiceProvider {

    /**
     * Declare compatibility with SLF4J 2.0.x API.
     * "2.0.99" is the convention used by official SLF4J providers.
     */
    public static final String REQUESTED_API_VERSION = "2.0.99";

    private ILoggerFactory loggerFactory;
    private IMarkerFactory markerFactory;
    private MDCAdapter mdcAdapter;

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public void initialize() {
        loggerFactory = new EclipseLoggerFactory();
        markerFactory = new BasicMarkerFactory();
        mdcAdapter = new NOPMDCAdapter();
    }
}
