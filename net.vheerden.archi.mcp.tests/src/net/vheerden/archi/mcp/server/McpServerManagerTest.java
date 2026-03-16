package net.vheerden.archi.mcp.server;

import static org.junit.Assert.*;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link McpServerManager}.
 *
 * <p>Uses real {@link TransportConfig} with high ports (19100+) to avoid conflicts.
 * Tests exercise actual Jetty start/stop for integration verification.
 * Uses package-visible {@code start(int, String)} to bypass McpPlugin preferences.</p>
 */
public class McpServerManagerTest {

    private static final int TEST_PORT = 19100;
    private static final String TEST_BIND_ADDRESS = "127.0.0.1";

    private McpServerManager manager;

    @Before
    public void setUp() {
        manager = new McpServerManager(new TransportConfig());
    }

    @After
    public void tearDown() {
        manager.stop();
    }

    @Test
    public void shouldReturnStopped_whenNotStarted() {
        assertEquals(ServerState.STOPPED, manager.getState());
        assertFalse(manager.isRunning());
    }

    @Test
    public void shouldTransitionToRunning_whenStartCalled() {
        manager.start(TEST_PORT, TEST_BIND_ADDRESS);

        assertEquals(ServerState.RUNNING, manager.getState());
        assertTrue(manager.isRunning());
    }

    @Test
    public void shouldTransitionToStopped_whenStopCalled() {
        manager.start(TEST_PORT + 1, TEST_BIND_ADDRESS);
        assertTrue(manager.isRunning());

        manager.stop();

        assertEquals(ServerState.STOPPED, manager.getState());
        assertFalse(manager.isRunning());
    }

    @Test
    public void shouldReturnError_whenStartFailsDueToPortConflict() throws Exception {
        try (ServerSocket blocker = new ServerSocket(TEST_PORT + 2, 1,
                java.net.InetAddress.getByName(TEST_BIND_ADDRESS))) {

            manager.start(TEST_PORT + 2, TEST_BIND_ADDRESS);

            assertEquals(ServerState.ERROR, manager.getState());
            assertFalse(manager.isRunning());
            assertNotNull(manager.getLastErrorMessage());
            assertTrue(manager.getLastErrorMessage().contains("Port"));
        }
    }

    @Test
    public void shouldNotifyListeners_whenStateChanges() {
        List<ServerState> oldStates = new ArrayList<>();
        List<ServerState> newStates = new ArrayList<>();

        manager.addStateListener((oldState, newState) -> {
            oldStates.add(oldState);
            newStates.add(newState);
        });

        manager.start(TEST_PORT + 3, TEST_BIND_ADDRESS);

        // Should have STOPPED->STARTING, STARTING->RUNNING
        assertEquals(2, oldStates.size());
        assertEquals(ServerState.STOPPED, oldStates.get(0));
        assertEquals(ServerState.STARTING, oldStates.get(1));
        assertEquals(ServerState.STARTING, newStates.get(0));
        assertEquals(ServerState.RUNNING, newStates.get(1));
    }

    @Test
    public void shouldNotifyListeners_whenStopCalled() {
        manager.start(TEST_PORT + 4, TEST_BIND_ADDRESS);

        List<ServerState> oldStates = new ArrayList<>();
        List<ServerState> newStates = new ArrayList<>();

        manager.addStateListener((oldState, newState) -> {
            oldStates.add(oldState);
            newStates.add(newState);
        });

        manager.stop();

        // Should have RUNNING->STOPPING, STOPPING->STOPPED
        assertEquals(2, oldStates.size());
        assertEquals(ServerState.RUNNING, oldStates.get(0));
        assertEquals(ServerState.STOPPING, oldStates.get(1));
        assertEquals(ServerState.STOPPING, newStates.get(0));
        assertEquals(ServerState.STOPPED, newStates.get(1));
    }

    @Test
    public void shouldBeIdempotent_whenStartCalledTwice() {
        manager.start(TEST_PORT + 5, TEST_BIND_ADDRESS);
        assertTrue(manager.isRunning());
        int port = manager.getPort();

        // Second start should be a no-op
        manager.start(TEST_PORT + 6, TEST_BIND_ADDRESS);
        assertTrue(manager.isRunning());
        assertEquals(port, manager.getPort());
    }

    @Test
    public void shouldBeIdempotent_whenStopCalledTwice() {
        manager.start(TEST_PORT + 7, TEST_BIND_ADDRESS);
        manager.stop();
        assertFalse(manager.isRunning());

        // Second stop should be a no-op
        manager.stop();
        assertFalse(manager.isRunning());
        assertEquals(ServerState.STOPPED, manager.getState());
    }

    @Test
    public void shouldReturnActivePort_whenRunning() {
        manager.start(TEST_PORT + 8, TEST_BIND_ADDRESS);

        assertEquals(TEST_PORT + 8, manager.getPort());
    }

    @Test
    public void shouldReturnZeroPort_whenNotRunning() {
        assertEquals(0, manager.getPort());
    }

    @Test
    public void shouldReturnStatus_withCorrectFields() {
        // When stopped
        assertEquals(ServerState.STOPPED, manager.getState());
        assertEquals(0, manager.getPort());
        assertNull(manager.getLastErrorMessage());

        // When running
        manager.start(TEST_PORT + 9, TEST_BIND_ADDRESS);
        assertEquals(ServerState.RUNNING, manager.getState());
        assertTrue(manager.getPort() > 0);
        assertNull(manager.getLastErrorMessage());
    }

    @Test
    public void shouldClearErrorOnStop() throws Exception {
        // Force an error
        try (ServerSocket blocker = new ServerSocket(TEST_PORT + 10, 1,
                java.net.InetAddress.getByName(TEST_BIND_ADDRESS))) {

            manager.start(TEST_PORT + 10, TEST_BIND_ADDRESS);
            assertEquals(ServerState.ERROR, manager.getState());
            assertNotNull(manager.getLastErrorMessage());
        }

        // Stop should clear the error
        manager.stop();
        assertEquals(ServerState.STOPPED, manager.getState());
        assertNull(manager.getLastErrorMessage());
    }

    @Test
    public void shouldRemoveListener_whenRemoveCalled() {
        List<ServerState> states = new ArrayList<>();
        McpServerStateListener listener = (oldState, newState) -> states.add(newState);

        manager.addStateListener(listener);
        manager.removeStateListener(listener);

        manager.start(TEST_PORT + 11, TEST_BIND_ADDRESS);

        // Listener should not have been called
        assertTrue(states.isEmpty());
    }

    @Test
    public void shouldHandleListenerException_withoutAffectingOthers() {
        List<ServerState> states = new ArrayList<>();

        // First listener throws
        manager.addStateListener((oldState, newState) -> {
            throw new RuntimeException("Listener error");
        });

        // Second listener should still be called
        manager.addStateListener((oldState, newState) -> states.add(newState));

        manager.start(TEST_PORT + 12, TEST_BIND_ADDRESS);

        // Second listener should have been notified despite first one throwing
        assertFalse(states.isEmpty());
    }

    @Test
    public void shouldExposeMcpServerInstances_whenRunning() {
        assertNull(manager.getStreamableMcpServer());
        assertNull(manager.getSseMcpServer());

        manager.start(TEST_PORT + 13, TEST_BIND_ADDRESS);

        assertNotNull(manager.getStreamableMcpServer());
        assertNotNull(manager.getSseMcpServer());
    }

    @Test
    public void shouldNotAccumulateResources_afterMultipleFailedStarts() throws Exception {
        try (ServerSocket blocker = new ServerSocket(TEST_PORT + 15, 1,
                java.net.InetAddress.getByName(TEST_BIND_ADDRESS))) {

            // First failed start — resources registered then startup fails
            manager.start(TEST_PORT + 15, TEST_BIND_ADDRESS);
            assertEquals(ServerState.ERROR, manager.getState());
            int countAfterFirst = manager.getResourceRegistry().getResourceCount();

            // Second failed start — resources should NOT accumulate
            manager.start(TEST_PORT + 15, TEST_BIND_ADDRESS);
            assertEquals(ServerState.ERROR, manager.getState());
            int countAfterSecond = manager.getResourceRegistry().getResourceCount();

            assertEquals("Resources should not accumulate on retry",
                    countAfterFirst, countAfterSecond);
        }
    }

    @Test
    public void shouldBuildHttpsUrl_whenTlsEnabled() throws Exception {
        // Generate a keystore for TLS test
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-mgr-tls-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);

        // Use a TransportConfig with TLS-aware start
        TransportConfig tlsTransport = new TransportConfig();
        McpServerManager tlsManager = new McpServerManager(tlsTransport);
        try {
            // Start with TLS via transport config directly
            tlsTransport.startServer(TEST_PORT + 20, TEST_BIND_ADDRESS,
                    true, certResult.keystorePath(), certResult.password());

            String url = tlsManager.buildServerUrl();
            assertNotNull(url);
            assertTrue("URL should start with https://", url.startsWith("https://"));
            assertTrue("URL should contain the port", url.contains(":" + (TEST_PORT + 20)));
        } finally {
            tlsTransport.stopServer();
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }
    }

    @Test
    public void shouldBuildHttpUrl_whenTlsNotEnabled() {
        manager.start(TEST_PORT + 21, TEST_BIND_ADDRESS);

        String url = manager.buildServerUrl();
        assertNotNull(url);
        assertTrue("URL should start with http://", url.startsWith("http://"));
    }

    @Test
    public void shouldReportTlsConfigError_whenKeystoreInvalid() {
        // Start with TLS enabled but invalid keystore — exercises buildErrorMessage(INVALID_TLS_CONFIG)
        TransportConfig tlsTransport = new TransportConfig();
        McpServerManager tlsManager = new McpServerManager(tlsTransport);

        try {
            tlsTransport.startServer(TEST_PORT + 22, TEST_BIND_ADDRESS,
                    true, "/nonexistent/keystore.p12", "password");
            fail("Expected ServerStartException");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
        }
    }

    @Test
    public void shouldNotifyListeners_whenErrorOccurs() throws Exception {
        List<ServerState> newStates = new ArrayList<>();
        manager.addStateListener((oldState, newState) -> newStates.add(newState));

        try (ServerSocket blocker = new ServerSocket(TEST_PORT + 14, 1,
                java.net.InetAddress.getByName(TEST_BIND_ADDRESS))) {

            manager.start(TEST_PORT + 14, TEST_BIND_ADDRESS);

            // Should have STARTING and then ERROR
            assertTrue(newStates.contains(ServerState.STARTING));
            assertTrue(newStates.contains(ServerState.ERROR));
        }
    }
}
