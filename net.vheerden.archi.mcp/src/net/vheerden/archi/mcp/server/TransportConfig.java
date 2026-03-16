package net.vheerden.archi.mcp.server;

import java.io.File;
import java.net.BindException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import io.modelcontextprotocol.server.McpServerFeatures;
import net.vheerden.archi.mcp.McpPlugin;

/**
 * Configures and manages the embedded Jetty server with MCP SDK servlet transports.
 *
 * <p>Provides dual transport support:</p>
 * <ul>
 *   <li>Streamable-HTTP at {@code /mcp} — stateful bidirectional (Claude CLI default)</li>
 *   <li>SSE at {@code /sse} — Server-Sent Events (Cline preference)</li>
 * </ul>
 *
 * <p>Optionally supports TLS/HTTPS when configured via preferences. TLS operates at the
 * Jetty connector level, below the servlet layer, so both transports automatically get
 * TLS protection with zero transport-layer changes.</p>
 *
 * <p>This class handles Jetty server creation, MCP SDK wiring, and start/stop lifecycle.
 * The {@link McpServerManager} (Story 1.4) will use this class for higher-level lifecycle
 * management.</p>
 */
public class TransportConfig {

    private static final Logger logger = LoggerFactory.getLogger(TransportConfig.class);

    private static final String SERVER_NAME = "ArchiMate MCP Server";
    private static final String SERVER_VERSION = "1.0.0";

    private Server jettyServer;
    private McpSyncServer streamableMcpServer;
    private McpSyncServer sseMcpServer;
    private int activePort;
    private String activeBindAddress;
    private boolean tlsActive;
    private List<McpServerFeatures.SyncToolSpecification> toolSpecifications = Collections.emptyList();
    private List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications = Collections.emptyList();

    /**
     * Sets the tool specifications to register on both MCP servers at build time.
     *
     * @param toolSpecs the tool specifications, or empty list for no tools
     */
    public void setToolSpecifications(List<McpServerFeatures.SyncToolSpecification> toolSpecs) {
        this.toolSpecifications = toolSpecs != null ? toolSpecs : Collections.emptyList();
    }

    /**
     * Sets the resource specifications to register on both MCP servers at build time.
     *
     * @param resourceSpecs the resource specifications, or empty list for no resources
     */
    public void setResourceSpecifications(List<McpServerFeatures.SyncResourceSpecification> resourceSpecs) {
        this.resourceSpecifications = resourceSpecs != null ? resourceSpecs : Collections.emptyList();
    }

    /**
     * Starts the embedded Jetty server with MCP transports on the configured port and bind address.
     *
     * <p>Reads configuration from {@link McpPlugin} preferences:</p>
     * <ul>
     *   <li>Port — {@link McpPlugin#getServerPort()}</li>
     *   <li>Bind address — {@link McpPlugin#getBindAddress()}</li>
     *   <li>TLS enabled — {@link McpPlugin#isTlsEnabled()}</li>
     *   <li>Keystore path — {@link McpPlugin#getKeystorePath()}</li>
     *   <li>Keystore password — {@link McpPlugin#getKeystorePassword()}</li>
     * </ul>
     *
     * @throws ServerStartException if the server cannot start (port conflict, bind failure, etc.)
     */
    public void startServer() throws ServerStartException {
        McpPlugin plugin = McpPlugin.getDefault();
        int port = plugin.getServerPort();
        String bindAddress = plugin.getBindAddress();
        boolean tlsEnabled = plugin.isTlsEnabled();
        String keystorePath = plugin.getKeystorePath();
        String keystorePassword = plugin.getKeystorePassword();
        startServer(port, bindAddress, tlsEnabled, keystorePath, keystorePassword);
    }

    /**
     * Starts the embedded Jetty server with MCP transports on the specified port and bind address,
     * without TLS.
     *
     * @param port        the TCP port to listen on
     * @param bindAddress the network interface to bind to (e.g. "127.0.0.1")
     * @throws ServerStartException if the server cannot start
     */
    public void startServer(int port, String bindAddress) throws ServerStartException {
        startServer(port, bindAddress, false, null, null);
    }

    /**
     * Starts the embedded Jetty server with MCP transports, optionally with TLS.
     *
     * @param port             the TCP port to listen on
     * @param bindAddress      the network interface to bind to (e.g. "127.0.0.1")
     * @param tlsEnabled       true to enable TLS/HTTPS
     * @param keystorePath     path to the PKCS12/JKS keystore file (required if tlsEnabled)
     * @param keystorePassword password for the keystore (required if tlsEnabled)
     * @throws ServerStartException if the server cannot start
     */
    public void startServer(int port, String bindAddress, boolean tlsEnabled,
                            String keystorePath, String keystorePassword) throws ServerStartException {
        if (isRunning()) {
            logger.warn("Server is already running on port {}", activePort);
            return;
        }

        // Validate bind address before attempting to start
        try {
            InetAddress.getByName(bindAddress);
        } catch (UnknownHostException e) {
            logger.error("Invalid bind address: {}. Server not started.", bindAddress);
            throw new ServerStartException(
                    "Invalid bind address: " + bindAddress, ServerStartException.INVALID_BIND_ADDRESS, e);
        }

        // Validate TLS configuration
        if (tlsEnabled) {
            validateTlsConfig(keystorePath, keystorePassword);
        }

        try {
            McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

            // Create Streamable-HTTP transport (MCP spec v2025-06-18)
            HttpServletStreamableServerTransportProvider streamableTransport =
                    HttpServletStreamableServerTransportProvider.builder()
                            .jsonMapper(jsonMapper)
                            .build();

            // Create SSE transport (backwards compatibility)
            HttpServletSseServerTransportProvider sseTransport =
                    HttpServletSseServerTransportProvider.builder()
                            .jsonMapper(jsonMapper)
                            .messageEndpoint("/sse/message")
                            .sseEndpoint("/sse")
                            .build();

            // Create MCP server instances wired to their transports
            ServerCapabilities capabilities = ServerCapabilities.builder()
                    .tools(true)
                    .resources(false, true)
                    .build();

            var streamableBuilder = McpServer.sync(streamableTransport)
                    .serverInfo(SERVER_NAME, SERVER_VERSION)
                    .capabilities(capabilities);
            if (!toolSpecifications.isEmpty()) {
                streamableBuilder.tools(toolSpecifications);
            }
            if (!resourceSpecifications.isEmpty()) {
                streamableBuilder.resources(resourceSpecifications);
            }
            streamableMcpServer = streamableBuilder.build();

            var sseBuilder = McpServer.sync(sseTransport)
                    .serverInfo(SERVER_NAME, SERVER_VERSION)
                    .capabilities(capabilities);
            if (!toolSpecifications.isEmpty()) {
                sseBuilder.tools(toolSpecifications);
            }
            if (!resourceSpecifications.isEmpty()) {
                sseBuilder.resources(resourceSpecifications);
            }
            sseMcpServer = sseBuilder.build();

            // Create Jetty server
            jettyServer = new Server();
            ServerConnector connector;

            if (tlsEnabled) {
                // TLS connector
                SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setKeyStorePath(keystorePath);
                sslContextFactory.setKeyStorePassword(keystorePassword);

                // Auto-detect keystore type from file extension
                if (keystorePath.endsWith(".jks")) {
                    sslContextFactory.setKeyStoreType("JKS");
                } else {
                    sslContextFactory.setKeyStoreType("PKCS12");
                }

                HttpConfiguration httpsConfig = new HttpConfiguration();
                httpsConfig.addCustomizer(new SecureRequestCustomizer());

                connector = new ServerConnector(jettyServer,
                        new SslConnectionFactory(sslContextFactory, "http/1.1"),
                        new HttpConnectionFactory(httpsConfig));
                logger.info("TLS enabled with keystore: {}", keystorePath);
            } else {
                // Plain HTTP connector
                connector = new ServerConnector(jettyServer);
            }

            connector.setHost(bindAddress);
            connector.setPort(port);
            jettyServer.addConnector(connector);

            // Create servlet context and register transports
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");

            ServletHolder streamableHolder = new ServletHolder("mcp-streamable", streamableTransport);
            streamableHolder.setAsyncSupported(true);
            context.addServlet(streamableHolder, "/mcp/*");

            ServletHolder sseHolder = new ServletHolder("mcp-sse", sseTransport);
            sseHolder.setAsyncSupported(true);
            context.addServlet(sseHolder, "/sse/*");

            jettyServer.setHandler(context);

            // Register custom JSON error handler (replaces Jetty's default HTML error pages)
            jettyServer.setErrorHandler(new JsonErrorHandler());

            // Start Jetty
            jettyServer.start();
            activePort = port;
            activeBindAddress = bindAddress;
            tlsActive = tlsEnabled;
            String scheme = tlsEnabled ? "https" : "http";
            logger.info("MCP Server started on {}://{}:{}", scheme, bindAddress, port);

        } catch (BindException e) {
            cleanup();
            logger.error("Port {} already in use. Server not started.", port);
            throw new ServerStartException("Port " + port + " already in use", e);
        } catch (ServerStartException e) {
            cleanup();
            throw e;
        } catch (Exception e) {
            cleanup();
            if (e.getCause() instanceof BindException) {
                logger.error("Port {} already in use. Server not started.", port);
                throw new ServerStartException("Port " + port + " already in use", ServerStartException.PORT_IN_USE, e);
            }
            logger.error("Failed to start MCP server on {}:{}", bindAddress, port, e);
            throw new ServerStartException("Server startup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Validates TLS configuration before attempting to start.
     *
     * @throws ServerStartException if the TLS configuration is invalid
     */
    private void validateTlsConfig(String keystorePath, String keystorePassword) throws ServerStartException {
        if (keystorePath == null || keystorePath.isBlank()) {
            throw new ServerStartException(
                    "TLS enabled but no keystore path configured",
                    ServerStartException.INVALID_TLS_CONFIG,
                    new IllegalArgumentException("Keystore path is required when TLS is enabled"));
        }

        File keystoreFile = new File(keystorePath);
        if (!keystoreFile.exists()) {
            throw new ServerStartException(
                    "Keystore file not found: " + keystorePath,
                    ServerStartException.INVALID_TLS_CONFIG,
                    new IllegalArgumentException("Keystore file does not exist: " + keystorePath));
        }

        if (!keystoreFile.canRead()) {
            throw new ServerStartException(
                    "Keystore file not readable: " + keystorePath,
                    ServerStartException.INVALID_TLS_CONFIG,
                    new IllegalArgumentException("Keystore file is not readable: " + keystorePath));
        }

        if (keystorePassword == null || keystorePassword.isBlank()) {
            throw new ServerStartException(
                    "TLS enabled but no keystore password configured",
                    ServerStartException.INVALID_TLS_CONFIG,
                    new IllegalArgumentException("Keystore password is required when TLS is enabled"));
        }
    }

    /**
     * Gracefully stops the Jetty server and releases the port.
     */
    public void stopServer() {
        if (!isRunning()) {
            logger.debug("Server is not running, nothing to stop");
            return;
        }

        int stoppedPort = activePort;
        try {
            if (jettyServer != null) {
                jettyServer.stop();
                jettyServer.join();
            }
        } catch (Exception e) {
            logger.error("Error stopping Jetty server", e);
        } finally {
            cleanup();
            logger.info("MCP Server stopped (was on port {})", stoppedPort);
        }
    }

    /**
     * Checks whether the Jetty server is currently running.
     *
     * @return true if the server is started and running
     */
    public boolean isRunning() {
        return jettyServer != null && jettyServer.isRunning();
    }

    /**
     * Returns whether TLS is active on the running server.
     *
     * @return true if the server is running with TLS/HTTPS
     */
    public boolean isTlsEnabled() {
        return isRunning() && tlsActive;
    }

    /**
     * Returns the port the server is actively listening on.
     *
     * @return the active port, or 0 if the server is not running
     */
    public int getPort() {
        if (isRunning()) {
            return activePort;
        }
        return 0;
    }

    /**
     * Returns the bind address the server is listening on.
     *
     * @return the active bind address, or null if the server is not running
     */
    public String getBindAddress() {
        if (isRunning()) {
            return activeBindAddress;
        }
        return null;
    }

    /**
     * Returns the Streamable-HTTP MCP server instance for tool registration.
     *
     * @return the sync MCP server for the Streamable-HTTP transport, or null if not started
     */
    public McpSyncServer getStreamableMcpServer() {
        return streamableMcpServer;
    }

    /**
     * Returns the SSE MCP server instance for tool registration.
     *
     * @return the sync MCP server for the SSE transport, or null if not started
     */
    public McpSyncServer getSseMcpServer() {
        return sseMcpServer;
    }

    private void cleanup() {
        if (streamableMcpServer != null) {
            try {
                streamableMcpServer.close();
            } catch (Exception e) {
                logger.debug("Error closing streamable MCP server during cleanup", e);
            }
        }
        if (sseMcpServer != null) {
            try {
                sseMcpServer.close();
            } catch (Exception e) {
                logger.debug("Error closing SSE MCP server during cleanup", e);
            }
        }
        jettyServer = null;
        streamableMcpServer = null;
        sseMcpServer = null;
        activePort = 0;
        activeBindAddress = null;
        tlsActive = false;
    }
}
