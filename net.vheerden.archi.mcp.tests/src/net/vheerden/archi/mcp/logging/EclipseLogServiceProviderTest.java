package net.vheerden.archi.mcp.logging;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link EclipseLogServiceProvider}.
 */
public class EclipseLogServiceProviderTest {

    private EclipseLogServiceProvider provider;

    @Before
    public void setUp() {
        provider = new EclipseLogServiceProvider();
    }

    @Test
    public void shouldReturnCorrectApiVersion() {
        assertEquals("2.0.99", provider.getRequestedApiVersion());
    }

    @Test
    public void shouldCreateLoggerFactory_afterInitialize() {
        provider.initialize();
        assertNotNull("LoggerFactory should be created", provider.getLoggerFactory());
        assertTrue(provider.getLoggerFactory() instanceof EclipseLoggerFactory);
    }

    @Test
    public void shouldCreateMarkerFactory_afterInitialize() {
        provider.initialize();
        assertNotNull("MarkerFactory should be created", provider.getMarkerFactory());
    }

    @Test
    public void shouldCreateMDCAdapter_afterInitialize() {
        provider.initialize();
        assertNotNull("MDCAdapter should be created", provider.getMDCAdapter());
    }

    @Test
    public void shouldReturnNull_beforeInitialize() {
        assertNull("LoggerFactory should be null before initialize", provider.getLoggerFactory());
        assertNull("MarkerFactory should be null before initialize", provider.getMarkerFactory());
        assertNull("MDCAdapter should be null before initialize", provider.getMDCAdapter());
    }
}
