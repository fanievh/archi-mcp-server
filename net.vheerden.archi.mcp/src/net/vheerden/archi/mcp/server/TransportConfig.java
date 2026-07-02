package net.vheerden.archi.mcp.server;

import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.SizeLimitHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
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
 * The {@link McpServerManager} will use this class for higher-level lifecycle
 * management.</p>
 */
public class TransportConfig {

    private static final Logger logger = LoggerFactory.getLogger(TransportConfig.class);

    private static final String SERVER_NAME = "ArchiMate MCP Server";
    private static final String SERVER_VERSION = "1.0.0";

    // --- Jetty resource guardrails (audit S3: no request-size cap / idle timeout / thread bound on
    // the embedded server => a buggy or hostile client could OOM the JVM with a giant body or hold
    // connections open). These are SAFETY BOUNDS, not user-tunable config: they are deliberately
    // generous so no legitimate single-user loopback workload is affected, while capping the blast
    // radius (the user's unsaved model). Constants, not preferences, by design.

    /**
     * Hard ceiling on inbound request body size, in bytes (~32 MiB). Enforced by Jetty's built-in
     * {@link SizeLimitHandler}, which bounds both a declared {@code Content-Length} AND the actual
     * bytes read (so a chunked / unknown-length stream is aborted at the limit too), returning
     * {@code 413 Payload Too Large} before the body is buffered into the heap. Generous for any
     * legitimate MCP tool call, far below what exhausts a desktop JVM.
     */
    static final long MAX_REQUEST_BODY_BYTES = 32L * 1024 * 1024; // 33,554,432

    /**
     * No cap on response size ({@code -1}). The MCP read tools (e.g. {@code get-view-contents} on a
     * large model) can legitimately return large payloads; capping responses would break valid reads.
     * Only the inbound direction is a DoS vector here.
     */
    static final long MAX_RESPONSE_BODY_BYTES = -1L;

    /**
     * Connector idle timeout in milliseconds (120 s). A client that opens a connection and then stops
     * sending or reading does not hold a worker thread indefinitely.
     */
    static final long IDLE_TIMEOUT_MS = 120_000L;

    /**
     * Worker-thread floor. Jetty needs a small minimum for its reserved threads (acceptor + selectors
     * + request handling); below this it refuses to start.
     */
    static final int MIN_THREADS = 8;

    /**
     * Worker-thread ceiling — an explicit bound for a single-user loopback desktop tool, tightening
     * Jetty's implicit default of 200. Generous headroom for the two transports' async servlets while
     * still a documented, finite cap.
     */
    static final int MAX_THREADS = 50;

    /** Thread-name prefix for the bounded pool, so its threads are identifiable in a JVM dump. */
    private static final String THREAD_POOL_NAME = "archi-mcp-jetty";

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
        startServer(port, bindAddress, tlsEnabled, keystorePath, keystorePassword, resolveAuthToken(plugin));
    }

    /**
     * Resolves the effective bearer token for the production start path, applying the opt-in /
     * fail-closed policy:
     *
     * <ul>
     *   <li>auth disabled &rarr; {@code null} (the {@link BearerTokenAuthHandler} passes through —
     *       zero behaviour change for existing configs);</li>
     *   <li>auth enabled with a stored token &rarr; that token (enforce normally);</li>
     *   <li>auth enabled but the token is absent or the secure store is unreadable
     *       ({@link BearerTokenStore.BearerTokenStoreException}) &rarr; <b>fail closed</b>: an
     *       unmatchable fresh random token, logged at ERROR, so every request is rejected with 401
     *       rather than the control silently disabling itself.</li>
     * </ul>
     *
     * @param plugin the plugin singleton (preferences + secure-storage access)
     * @return the effective token to enforce with, or {@code null} when auth is off
     */
    private static String resolveAuthToken(McpPlugin plugin) {
        if (plugin == null) {
            return null;
        }
        return resolveAuthToken(plugin.isAuthTokenEnabled(), BearerTokenStore::getToken);
    }

    /**
     * Pure fail-closed token-resolution policy, package-visible so the
     * <b>never-fail-open</b> property can be unit-tested without a live plugin or secure storage.
     * The {@code tokenSupplier} abstracts the secure-storage read ({@code BearerTokenStore::getToken}
     * in production); a test can supply a value, {@code null}, or a thrown
     * {@link BearerTokenStore.BearerTokenStoreException}.
     *
     * <p><b>Invariant:</b> when {@code enabled} is true this NEVER returns {@code null} — it returns
     * either the stored token or a fresh unmatchable random sentinel — so enforcement can never
     * silently switch itself off. It returns {@code null} only when auth is disabled.</p>
     *
     * @param enabled       whether bearer-token auth is enabled
     * @param tokenSupplier supplies the stored token (may return null/blank or throw)
     * @return the effective token to enforce with, or {@code null} only when {@code enabled} is false
     */
    static String resolveAuthToken(boolean enabled, java.util.function.Supplier<String> tokenSupplier) {
        if (!enabled) {
            return null;
        }
        try {
            String token = tokenSupplier.get();
            if (token == null || token.isBlank()) {
                logger.error("Bearer-token auth is ENABLED but no token is stored; failing closed "
                        + "(all requests will be rejected with 401). Re-generate a token in MCP Server preferences.");
                return BearerTokenStore.generate(); // unmatchable: never persisted, never shown
            }
            return token;
        } catch (BearerTokenStore.BearerTokenStoreException e) {
            logger.error("Bearer-token auth is ENABLED but the token could not be read from secure "
                    + "storage; failing closed (all requests will be rejected with 401).", e);
            return BearerTokenStore.generate(); // unmatchable fail-closed sentinel
        }
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
        // Delegate with no token => auth off. Preserves the existing 5-arg signature for the wide
        // test/caller fan-out; the new 6-arg overload carries the opt-in token.
        startServer(port, bindAddress, tlsEnabled, keystorePath, keystorePassword, null);
    }

    /**
     * Starts the embedded Jetty server with MCP transports, optionally with TLS and optionally
     * requiring an opt-in bearer token on every request (the token half of the auth audit finding).
     *
     * <p>Additive overload: the existing 5-arg {@link #startServer(int, String, boolean,
     * String, String)} delegates here with a {@code null} token, so every existing caller stays
     * auth-off and unchanged. The production no-arg {@link #startServer()} resolves the effective
     * token from preferences + secure storage (fail-closed) and passes it here.</p>
     *
     * @param port             the TCP port to listen on
     * @param bindAddress      the network interface to bind to (e.g. "127.0.0.1")
     * @param tlsEnabled       true to enable TLS/HTTPS
     * @param keystorePath     path to the PKCS12/JKS keystore file (required if tlsEnabled)
     * @param keystorePassword password for the keystore (required if tlsEnabled)
     * @param authToken        the bearer token to require, or {@code null}/blank to disable auth
     *                         (the {@link BearerTokenAuthHandler} passes through when blank)
     * @throws ServerStartException if the server cannot start
     */
    public void startServer(int port, String bindAddress, boolean tlsEnabled,
                            String keystorePath, String keystorePassword,
                            String authToken) throws ServerStartException {
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

            // Create Jetty server with an explicit, bounded worker pool (guardrail S3). Passing the
            // pool to the Server constructor hands it lifecycle ownership (started/stopped with the
            // server), replacing the implicit unbounded-by-default pool (max 200).
            QueuedThreadPool threadPool = new QueuedThreadPool(MAX_THREADS, MIN_THREADS);
            threadPool.setName(THREAD_POOL_NAME);
            jettyServer = new Server(threadPool);
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
            // Idle timeout guardrail (S3): drop connections that stop making progress.
            connector.setIdleTimeout(IDLE_TIMEOUT_MS);
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

            // Guard both transports with the wrapper chain, inserted between the server and the
            // servlet context so every request to /mcp/* and /sse/* is checked before it reaches a
            // servlet. Order (outer -> inner):
            //   SizeLimitHandler (413, guardrail S3) -> Origin/Host (403, default-on)
            //     -> bearer-token (401, opt-in) -> request-charset header (415, default-on)
            //     -> request-body UTF-8 (415, default-on) -> servlet context.
            // The size limit is OUTERMOST so an oversized body is bounded for the entire downstream
            // chain: SizeLimitHandler wraps the request's content source, so however far down the
            // body is eventually read (only the servlet reads it), the byte count is capped and a
            // 413 is raised before the heap grows. Origin/Host then 403s a browser DNS-rebind before
            // any auth/crypto work, and on a non-loopback bind (where Origin/Host relaxes to
            // pass-through) the token wrapper still enforces. The 413, like the 403/401/415, is
            // emitted as an HTTP error and rendered by the configured JsonErrorHandler as a JSON-RPC
            // envelope (413 -> 4xx -> -32600, data.httpStatus:413).
            // Trade-off of the outermost position: an oversized request from an unauthenticated /
            // bad-origin client gets 413 before the 401/403, so it learns the body cap exists. This
            // is acceptable here — OOM prevention must be unconditional, and the threat model is a
            // loopback single-user tool where a local attacker already has far more reach.
            // The two charset guards are INNERMOST (just above the servlet): rejecting a non-UTF-8
            // body is a request-correctness gate, not a DoS gate, so they run AFTER the security gates
            // — an unauthenticated / bad-origin client gets 401/403 first and is never told the charset
            // rules exist. The header guard runs first (cheaper, header-only — never reads the body),
            // and only an otherwise-acceptable POST reaches the body guard, which buffers the body,
            // verifies it is well-formed UTF-8, and replays it to the servlet (catching an *undeclared*
            // mis-encoded body, e.g. a client that sends ISO-8859-1/Windows-1252 bytes with no charset).
            // The body read goes through the outer SizeLimitHandler, so the 32 MiB cap still applies and
            // the body guard never lifts it.
            SizeLimitHandler sizeLimitHandler = new SizeLimitHandler(MAX_REQUEST_BODY_BYTES, MAX_RESPONSE_BODY_BYTES);
            sizeLimitHandler.setHandler(new OriginHostValidationHandler(
                    new BearerTokenAuthHandler(
                            new RequestCharsetValidationHandler(
                                    new RequestBodyUtf8ValidationHandler(context)), authToken), bindAddress));
            jettyServer.setHandler(sizeLimitHandler);

            // Register custom JSON error handler (replaces Jetty's default HTML error pages).
            // The guard's Response.writeError(...) routes its 403 through this handler.
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
            // Classify the failure (audit Q2) so the user gets an actionable message instead of one
            // generic "startup failed". The direct catch (BindException) above is the fast path; this
            // walks the FULL cause chain — a BindException can be nested deeper than one level — and,
            // on a TLS run, attributes a keystore/SSL load failure that slipped past
            // validateTlsConfig's pre-flight checks (wrong password, corrupt/empty/wrong-type file) to
            // INVALID_TLS_CONFIG. Both reuse the existing ServerStartException codes, so
            // McpServerManager.buildErrorMessage renders the existing friendly text unchanged.
            String code = classifyStartFailure(e, tlsEnabled);
            if (ServerStartException.PORT_IN_USE.equals(code)) {
                logger.error("Port {} already in use. Server not started.", port);
                throw new ServerStartException("Port " + port + " already in use",
                        ServerStartException.PORT_IN_USE, e);
            }
            if (ServerStartException.INVALID_TLS_CONFIG.equals(code)) {
                logger.error("TLS keystore could not be loaded (keystore: {}). Server not started.",
                        keystorePath, e);
                throw new ServerStartException("Keystore could not be loaded: " + e.getMessage(),
                        ServerStartException.INVALID_TLS_CONFIG, e);
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

    /** Bounded depth for {@link #hasCauseOfType} so a malformed or cyclic cause chain cannot loop. */
    private static final int MAX_CAUSE_DEPTH = 20;

    /**
     * Classifies a server-start failure (audit Q2) into a {@link ServerStartException} error code, so
     * the two most common desktop failures are self-diagnosable instead of collapsing into one generic
     * "startup failed":
     *
     * <ul>
     *   <li>a {@link BindException} ANYWHERE in the cause chain &rarr; {@link ServerStartException#PORT_IN_USE}
     *       (the #1 desktop failure; checked first so a port conflict wins even on a TLS run);</li>
     *   <li>otherwise, on a TLS run, a keystore/SSL load failure &rarr; {@link ServerStartException#INVALID_TLS_CONFIG}.
     *       The keystore is loaded by Jetty during {@code start()}, AFTER {@link #validateTlsConfig}'s
     *       pre-flight path/exists/readable/blank-password checks have passed, so the residual cases
     *       only surface here: a wrong password is an {@link IOException} caused by a
     *       {@link java.security.UnrecoverableKeyException}, and a corrupt/empty/wrong-type file is an
     *       {@link java.io.EOFException}/{@link IOException} with NO JSSE-specific type. Both are caught
     *       by scanning the chain for {@link GeneralSecurityException} or {@link IOException}. Requiring
     *       one of those types (rather than blanket-labelling every TLS-run failure) keeps a
     *       non-keystore programming error — e.g. a {@code RuntimeException} — out of the TLS bucket;</li>
     *   <li>anything else &rarr; {@link ServerStartException#SERVER_START_FAILED}.</li>
     * </ul>
     *
     * <p>Package-visible and pure (no Jetty/EMF/SWT state) so it is unit-testable against hand-built
     * cause chains.</p>
     *
     * @param failure    the throwable caught from server start
     * @param tlsEnabled whether the start was attempted with TLS (gates the keystore classification)
     * @return one of {@link ServerStartException#PORT_IN_USE}, {@link ServerStartException#INVALID_TLS_CONFIG},
     *         or {@link ServerStartException#SERVER_START_FAILED}
     */
    static String classifyStartFailure(Throwable failure, boolean tlsEnabled) {
        if (hasCauseOfType(failure, BindException.class)) {
            return ServerStartException.PORT_IN_USE;
        }
        if (tlsEnabled && hasCauseOfType(failure, GeneralSecurityException.class, IOException.class)) {
            return ServerStartException.INVALID_TLS_CONFIG;
        }
        return ServerStartException.SERVER_START_FAILED;
    }

    /**
     * Returns {@code true} if {@code root} or any throwable in its cause chain is an instance of any of
     * {@code types}. Cycle-safe (identity-visited set) and depth-bounded ({@link #MAX_CAUSE_DEPTH}) so a
     * self-referential or pathologically deep chain cannot loop forever.
     *
     * @param root  the throwable to inspect (may be {@code null})
     * @param types the throwable types to look for anywhere in the chain
     * @return {@code true} if any chain element matches a type
     */
    static boolean hasCauseOfType(Throwable root, Class<?>... types) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable t = root;
        int depth = 0;
        while (t != null && depth++ < MAX_CAUSE_DEPTH && seen.add(t)) {
            for (Class<?> type : types) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
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
     * Returns the connector idle timeout (ms) the running server is enforcing — the resource
     * guardrail from {@link #IDLE_TIMEOUT_MS}. Package-visible so the bound can be asserted in tests
     * without exposing the Jetty {@link Server} (mirrors {@link #getPort()}'s sentinel-when-stopped
     * contract).
     *
     * @return the connector idle timeout in ms, or {@code -1} if the server is not running
     */
    long getIdleTimeout() {
        if (isRunning()) {
            Connector[] connectors = jettyServer.getConnectors();
            if (connectors.length > 0) {
                return connectors[0].getIdleTimeout();
            }
        }
        return -1L;
    }

    /**
     * Returns the maximum worker-thread count of the bounded {@link QueuedThreadPool} — the resource
     * guardrail from {@link #MAX_THREADS}. Package-visible for test assertions.
     *
     * @return the pool's max threads, or {@code -1} if the server is not running or the pool is not
     *         a {@link QueuedThreadPool}
     */
    int getMaxThreads() {
        if (isRunning() && jettyServer.getThreadPool() instanceof QueuedThreadPool pool) {
            return pool.getMaxThreads();
        }
        return -1;
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
