package net.vheerden.archi.mcp.server;

import static org.junit.Assert.*;

import java.net.ServerSocket;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

import org.junit.After;
import org.junit.Test;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Tests for {@link TransportConfig}.
 *
 * <p>Uses high ports (19090+) to avoid conflicts with real services.
 * Tests that require actual server start/stop exercise the full Jetty + MCP SDK wiring.</p>
 */
public class TransportConfigTest {

    private static final int TEST_PORT = 19090;
    private static final String TEST_BIND_ADDRESS = "127.0.0.1";

    private TransportConfig transportConfig = new TransportConfig();

    @After
    public void tearDown() {
        transportConfig.stopServer();
    }

    @Test
    public void shouldReturnFalse_whenServerNotStarted() {
        assertFalse(transportConfig.isRunning());
    }

    @Test
    public void shouldReturnZeroPort_whenServerNotStarted() {
        assertEquals(0, transportConfig.getPort());
    }

    @Test
    public void shouldReturnTrue_whenServerStarted() throws Exception {
        transportConfig.startServer(TEST_PORT, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
    }

    @Test
    public void shouldCreateServerWithConfiguredPort_whenValidPortProvided() throws Exception {
        transportConfig.startServer(TEST_PORT, TEST_BIND_ADDRESS);

        assertEquals(TEST_PORT, transportConfig.getPort());
    }

    @Test
    public void shouldCreateServerWithConfiguredBindAddress_whenValidAddressProvided() throws Exception {
        transportConfig.startServer(TEST_PORT, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
        assertEquals(TEST_BIND_ADDRESS, transportConfig.getBindAddress());
    }

    @Test
    public void shouldHandlePortConflict_whenPortAlreadyInUse() throws Exception {
        // Bind the port first to create a conflict
        try (ServerSocket blocker = new ServerSocket(TEST_PORT + 1, 1,
                java.net.InetAddress.getByName(TEST_BIND_ADDRESS))) {

            try {
                transportConfig.startServer(TEST_PORT + 1, TEST_BIND_ADDRESS);
                fail("Expected ServerStartException for port conflict");
            } catch (ServerStartException e) {
                assertEquals(ServerStartException.PORT_IN_USE, e.getErrorCode());
                assertFalse(transportConfig.isRunning());
            }
        }
    }

    @Test
    public void shouldStopCleanly_whenServerIsRunning() throws Exception {
        transportConfig.startServer(TEST_PORT + 2, TEST_BIND_ADDRESS);
        assertTrue(transportConfig.isRunning());

        transportConfig.stopServer();

        assertFalse(transportConfig.isRunning());
        assertEquals(0, transportConfig.getPort());
    }

    @Test
    public void shouldNotThrow_whenStoppingServerThatIsNotRunning() {
        assertFalse(transportConfig.isRunning());
        transportConfig.stopServer(); // Should not throw
        assertFalse(transportConfig.isRunning());
    }

    @Test
    public void shouldNotStartTwice_whenAlreadyRunning() throws Exception {
        transportConfig.startServer(TEST_PORT + 3, TEST_BIND_ADDRESS);
        assertTrue(transportConfig.isRunning());

        // Second start should be a no-op (already running)
        transportConfig.startServer(TEST_PORT + 4, TEST_BIND_ADDRESS);
        assertTrue(transportConfig.isRunning());
        // Port should remain the original one
        assertEquals(TEST_PORT + 3, transportConfig.getPort());
    }

    @Test
    public void shouldExposeMcpServerInstances_whenStarted() throws Exception {
        transportConfig.startServer(TEST_PORT + 5, TEST_BIND_ADDRESS);

        assertNotNull(transportConfig.getStreamableMcpServer());
        assertNotNull(transportConfig.getSseMcpServer());
    }

    @Test
    public void shouldReturnNullMcpServers_whenNotStarted() {
        assertNull(transportConfig.getStreamableMcpServer());
        assertNull(transportConfig.getSseMcpServer());
    }

    @Test
    public void shouldReturnNullBindAddress_whenNotStarted() {
        assertNull(transportConfig.getBindAddress());
    }

    @Test
    public void shouldRejectInvalidBindAddress_whenAddressUnresolvable() {
        try {
            transportConfig.startServer(TEST_PORT + 6, "999.invalid.address");
            fail("Expected ServerStartException for invalid bind address");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_BIND_ADDRESS, e.getErrorCode());
            assertFalse(transportConfig.isRunning());
        }
    }

    @Test
    public void shouldHaveCorrectErrorCode_whenPortInUse() {
        ServerStartException ex = new ServerStartException(
                "Port in use", new java.net.BindException("Address already in use"));
        assertEquals(ServerStartException.PORT_IN_USE, ex.getErrorCode());
    }

    @Test
    public void shouldHaveGenericErrorCode_whenOtherFailure() {
        ServerStartException ex = new ServerStartException(
                "Other failure", new RuntimeException("something else"));
        assertEquals(ServerStartException.SERVER_START_FAILED, ex.getErrorCode());
    }

    @Test
    public void shouldAcceptResourceSpecifications_withoutError() {
        transportConfig.setResourceSpecifications(List.of(createResourceSpec(
                "archimate://test/resource", "Test", "A test resource")));
        // No exception expected
    }

    @Test
    public void shouldAcceptNullResourceSpecifications() {
        transportConfig.setResourceSpecifications(null);
        // No exception expected — null treated as empty list
    }

    @Test
    public void shouldStartServer_withResourceSpecifications() throws Exception {
        transportConfig.setResourceSpecifications(List.of(createResourceSpec(
                "archimate://test/resource", "Test Resource", "A test resource")));
        transportConfig.startServer(TEST_PORT + 7, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
        assertNotNull(transportConfig.getStreamableMcpServer());
        assertNotNull(transportConfig.getSseMcpServer());
    }

    @Test
    public void shouldStartServer_withEmptyResourceSpecifications() throws Exception {
        transportConfig.setResourceSpecifications(Collections.emptyList());
        transportConfig.startServer(TEST_PORT + 8, TEST_BIND_ADDRESS);

        assertTrue(transportConfig.isRunning());
    }

    // ---- TLS Tests ----

    @Test
    public void shouldStartWithTls_whenKeystoreConfigured() throws Exception {
        // Generate a keystore first
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-keystore-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);

        try {
            transportConfig.startServer(TEST_PORT + 20, TEST_BIND_ADDRESS,
                    true, certResult.keystorePath(), certResult.password());

            assertTrue(transportConfig.isRunning());
            assertTrue(transportConfig.isTlsEnabled());
            assertEquals(TEST_PORT + 20, transportConfig.getPort());
        } finally {
            transportConfig.stopServer();
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }
    }

    @Test
    public void shouldFailWithTlsError_whenKeystoreInvalid() {
        try {
            transportConfig.startServer(TEST_PORT + 21, TEST_BIND_ADDRESS,
                    true, "/nonexistent/keystore.p12", "password");
            fail("Expected ServerStartException for invalid keystore");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertFalse(transportConfig.isRunning());
        }
    }

    @Test
    public void shouldFailWithTlsError_whenKeystorePathEmpty() {
        try {
            transportConfig.startServer(TEST_PORT + 22, TEST_BIND_ADDRESS,
                    true, "", "password");
            fail("Expected ServerStartException for empty keystore path");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertFalse(transportConfig.isRunning());
        }
    }

    @Test
    public void shouldFailWithTlsError_whenPasswordEmpty() throws Exception {
        // Create a real keystore file to pass the file-exists check
        String keystorePath = System.getProperty("java.io.tmpdir") + "/test-tls-nopw-" + System.nanoTime() + ".p12";
        CertificateGenerator.Result certResult = CertificateGenerator.generate(keystorePath);

        try {
            transportConfig.startServer(TEST_PORT + 23, TEST_BIND_ADDRESS,
                    true, certResult.keystorePath(), "");
            fail("Expected ServerStartException for empty password");
        } catch (ServerStartException e) {
            assertEquals(ServerStartException.INVALID_TLS_CONFIG, e.getErrorCode());
            assertFalse(transportConfig.isRunning());
        } finally {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(keystorePath));
        }
    }

    @Test
    public void shouldStartWithoutTls_whenTlsDisabled() throws Exception {
        transportConfig.startServer(TEST_PORT + 24, TEST_BIND_ADDRESS,
                false, null, null);

        assertTrue(transportConfig.isRunning());
        assertFalse(transportConfig.isTlsEnabled());
    }

    @Test
    public void shouldReturnTlsDisabled_whenNotRunning() {
        assertFalse(transportConfig.isTlsEnabled());
    }

    // ---- Helper ----

    private McpServerFeatures.SyncResourceSpecification createResourceSpec(
            String uri, String name, String description) {
        McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri(uri)
                .name(name)
                .description(description)
                .mimeType("text/markdown")
                .build();

        BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> handler =
                (exchange, request) -> new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents(request.uri(), "text/markdown", "test")));

        return new McpServerFeatures.SyncResourceSpecification(resource, handler);
    }
}
