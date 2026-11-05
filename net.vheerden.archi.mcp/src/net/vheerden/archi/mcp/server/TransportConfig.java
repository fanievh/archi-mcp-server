package net.vheerden.archi.mcp.server;

import java.io.File;
import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.BindException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

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
    /** What a read-only server tells a client in its {@code initialize} reply. */
    public static final String READ_ONLY_INSTRUCTIONS = "This server is read-only. It offers only tools that read "
            + "the model; it cannot change the model or write files on the server. Suggestions in tool "
            + "descriptions and responses that name other tools may refer to tools this server does not offer.";

    /** Reported as {@code serverInfo.version} when the plugin's own version cannot be read. */
    static final String UNKNOWN_SERVER_VERSION = "unknown";

    // --- Jetty resource guardrails (no request-size cap / idle timeout / thread bound on
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

    /**
     * Longest {@link #stopServer()} waits for Jetty to stop (10 s). A normal stop takes milliseconds;
     * one that runs past this is given up on, so neither the Stop command nor Archi's exit can wait on
     * it forever. The server is then still reported as running, because it may be, until that stop
     * finishes: then {@link #releaseFinishedStop()} or a later stop or start releases it.
     */
    static final long STOP_TIMEOUT_MS = 10_000L;

    /** How Jetty is stopped; replaceable so a test can make a stop that does not finish. */
    @FunctionalInterface
    interface JettyStop {
        void stop(Server server) throws Exception;
    }

    private static final JettyStop STOP_AND_JOIN = server -> {
        server.stop();
        server.join();
    };

    /** How an MCP SDK server is closed; replaceable so a test can make a close throw. */
    @FunctionalInterface
    interface McpServerClose {
        void close(McpSyncServer server);
    }

    private static final McpServerClose CLOSE = McpSyncServer::close;

    /** Supplies the running plugin's version for {@code serverInfo}; see {@link #serverVersion}. */
    private final Supplier<String> versionSupplier;

    private final long stopTimeoutMs;

    private final JettyStop jettyStop;

    private final McpServerClose mcpServerClose;

    private final Logger log;

    /** Reports the version of the running plugin bundle to MCP clients. */
    public TransportConfig() {
        this(TransportConfig::bundleVersion);
    }

    /**
     * Package-visible so a test can supply the version the servers report.
     *
     * @param versionSupplier supplies the version string (may return null/blank or throw)
     */
    TransportConfig(Supplier<String> versionSupplier) {
        this(versionSupplier, STOP_TIMEOUT_MS, STOP_AND_JOIN);
    }

    /**
     * Package-visible so a test can bound the stop more tightly and replace how Jetty is stopped.
     *
     * @param versionSupplier supplies the version string (may return null/blank or throw)
     * @param stopTimeoutMs   longest {@link #stopServer()} waits for the stop
     * @param jettyStop       stops the server and waits for it
     */
    TransportConfig(Supplier<String> versionSupplier, long stopTimeoutMs, JettyStop jettyStop) {
        this(versionSupplier, stopTimeoutMs, jettyStop, CLOSE);
    }

    /**
     * Package-visible so a test can also replace how the MCP SDK servers are closed.
     *
     * @param versionSupplier supplies the version string (may return null/blank or throw)
     * @param stopTimeoutMs   longest {@link #stopServer()} waits for the stop
     * @param jettyStop       stops the server and waits for it
     * @param mcpServerClose  closes one MCP SDK server
     */
    TransportConfig(Supplier<String> versionSupplier, long stopTimeoutMs, JettyStop jettyStop,
            McpServerClose mcpServerClose) {
        this(versionSupplier, stopTimeoutMs, jettyStop, mcpServerClose, logger);
    }

    /**
     * Package-visible so a test can also observe what the server logs.
     *
     * @param versionSupplier supplies the version string (may return null/blank or throw)
     * @param stopTimeoutMs   longest {@link #stopServer()} waits for the stop
     * @param jettyStop       stops the server and waits for it
     * @param mcpServerClose  closes one MCP SDK server
     * @param log             what the server's start, stop and clean-up log through
     */
    TransportConfig(Supplier<String> versionSupplier, long stopTimeoutMs, JettyStop jettyStop,
            McpServerClose mcpServerClose, Logger log) {
        this.versionSupplier = versionSupplier;
        this.stopTimeoutMs = stopTimeoutMs;
        this.jettyStop = jettyStop;
        this.mcpServerClose = mcpServerClose;
        this.log = log;
    }

    /** The plugin bundle's version, e.g. {@code 1.9.0.202610191646}, or null when the plugin is not started. */
    private static String bundleVersion() {
        McpPlugin plugin = McpPlugin.getDefault();
        return plugin == null ? null : plugin.getBundle().getVersion().toString();
    }

    /**
     * Resolves the version reported as {@code serverInfo.version} in the {@code initialize} reply.
     *
     * <p>This runs on every server start, and the MCP SDK refuses a null or empty version when the
     * server is built, so this never throws and never returns a blank: a supplier that returns
     * nothing, or fails, yields {@link #UNKNOWN_SERVER_VERSION}. That fallback is deliberately not
     * shaped like a version, so it cannot be mistaken for a release. Outside a running plugin (in
     * headless tests) it is the normal answer and is logged at DEBUG; a supplier that throws is a fault
     * in a running plugin, so that is logged as a WARN with its cause.</p>
     *
     * @param versionSupplier supplies the version string (may return null/blank or throw)
     * @return the trimmed supplied version, or {@link #UNKNOWN_SERVER_VERSION}
     */
    static String serverVersion(Supplier<String> versionSupplier) {
        String version;
        try {
            version = versionSupplier.get();
        } catch (RuntimeException | LinkageError e) {
            logger.warn("Plugin version could not be read; reporting '{}' to MCP clients",
                    UNKNOWN_SERVER_VERSION, e);
            return UNKNOWN_SERVER_VERSION;
        }
        if (version == null || version.isBlank()) {
            logger.debug("Plugin version is not available; reporting '{}'", UNKNOWN_SERVER_VERSION);
            return UNKNOWN_SERVER_VERSION;
        }
        return version.trim();
    }

    /** Volatile: after a stop that was given up on, the stop's own thread may forget the server. */
    private volatile Server jettyServer;
    private McpSyncServer streamableMcpServer;
    private McpSyncServer sseMcpServer;
    private int activePort;
    private String activeBindAddress;
    /** How the running server's bind was classified as it started; see {@link #getBindExposure()}. */
    private BindExposure activeBindExposure;
    private boolean tlsActive;
    /** Set when a stop ran past its bound: the server may still be running until a later stop finishes. */
    private volatile boolean stopGivenUp;
    /** A stop still running after its wait ran out; a later stop waits on it rather than starting another. */
    private FutureTask<Void> stopInFlight;
    /** Told, on the stop's own thread, each time a stop has ended; see {@link #addStopEndedListener}. */
    private final List<Runnable> stopEndedListeners = new CopyOnWriteArrayList<>();
    private List<McpServerFeatures.SyncToolSpecification> toolSpecifications = Collections.emptyList();

    /** The {@code initialize} reply's instructions on both transports; null for none. */
    private volatile String instructions;
    private List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications = Collections.emptyList();
    private List<McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplateSpecifications =
            Collections.emptyList();

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
     * Sets the resource-template specifications to register on both MCP servers at build time.
     *
     * <p>Templates advertise the parameterised form of the same URIs the static resources expose,
     * for clients that implement only {@code resources/templates/list}. They are additive: the
     * static registrations above are unaffected.</p>
     *
     * @param templateSpecs the resource-template specifications, or empty list for none
     */
    public void setResourceTemplateSpecifications(
            List<McpServerFeatures.SyncResourceTemplateSpecification> templateSpecs) {
        this.resourceTemplateSpecifications = templateSpecs != null ? templateSpecs : Collections.emptyList();
    }

    /**
     * Sets the instructions the {@code initialize} reply carries on both transports, applied when the
     * server is next built; null for none.
     *
     * @param instructions the text, or null
     */
    public void setInstructions(String instructions) {
        this.instructions = instructions;
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
     * @param port        the TCP port to listen on, or 0 for any free port (read it back with {@link #getPort()})
     * @param bindAddress the network interface to bind to (e.g. "127.0.0.1")
     * @throws ServerStartException if the server cannot start
     */
    public void startServer(int port, String bindAddress) throws ServerStartException {
        startServer(port, bindAddress, false, null, null);
    }

    /**
     * Starts the embedded Jetty server with MCP transports, optionally with TLS.
     *
     * @param port             the TCP port to listen on, or 0 for any free port (read it back with {@link #getPort()})
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
     * @param port             the TCP port to listen on, or 0 for any free port (read it back with {@link #getPort()})
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
        if (stopGivenUp) {
            // The previous server may still hold its port and threads: finish that stop before starting another.
            stopServer();
            if (stopGivenUp) {
                throw new ServerStartException("The previous MCP Server has not finished stopping. "
                        + "Try again shortly, or restart Archi.", ServerStartException.SERVER_START_FAILED, null);
            }
        }
        if (isRunning()) {
            log.warn("Server is already running on port {}", activePort);
            return;
        }

        // Validate the bind address before attempting to start, and classify it once: the guard and the
        // server-file-access decision both read this one answer, so no second lookup can disagree with it.
        BindExposure exposure;
        try {
            exposure = BindExposure.resolve(bindAddress);
        } catch (UnknownHostException e) {
            log.error("Invalid bind address: {}. Server not started.", bindAddress);
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

            String serverVersion = serverVersion(versionSupplier);
            var streamableBuilder = McpServer.sync(streamableTransport)
                    .serverInfo(SERVER_NAME, serverVersion)
                    .capabilities(capabilities);
            if (instructions != null) {
                streamableBuilder.instructions(instructions);
            }
            if (!toolSpecifications.isEmpty()) {
                streamableBuilder.tools(toolSpecifications);
            }
            if (!resourceSpecifications.isEmpty()) {
                streamableBuilder.resources(resourceSpecifications);
            }
            if (!resourceTemplateSpecifications.isEmpty()) {
                streamableBuilder.resourceTemplates(resourceTemplateSpecifications);
            }
            streamableMcpServer = streamableBuilder.build();

            var sseBuilder = McpServer.sync(sseTransport)
                    .serverInfo(SERVER_NAME, serverVersion)
                    .capabilities(capabilities);
            if (instructions != null) {
                sseBuilder.instructions(instructions);
            }
            if (!toolSpecifications.isEmpty()) {
                sseBuilder.tools(toolSpecifications);
            }
            if (!resourceSpecifications.isEmpty()) {
                sseBuilder.resources(resourceSpecifications);
            }
            if (!resourceTemplateSpecifications.isEmpty()) {
                sseBuilder.resourceTemplates(resourceTemplateSpecifications);
            }
            sseMcpServer = sseBuilder.build();

            // Create Jetty server with an explicit, bounded worker pool. Passing the
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
                log.info("TLS enabled with keystore: {}", keystorePath);
            } else {
                // Plain HTTP connector
                connector = new ServerConnector(jettyServer);
            }

            connector.setHost(bindAddress);
            connector.setPort(port);
            // Idle timeout guardrail: drop connections that stop making progress.
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
            //   SizeLimitHandler (413, size guardrail) -> Origin/Host (403, default-on)
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
                                    new RequestBodyUtf8ValidationHandler(context)), authToken), bindAddress, exposure));
            jettyServer.setHandler(sizeLimitHandler);

            // Register custom JSON error handler (replaces Jetty's default HTML error pages).
            // The guard's Response.writeError(...) routes its 403 through this handler.
            jettyServer.setErrorHandler(new JsonErrorHandler());

            // Start Jetty
            jettyServer.start();
            activePort = effectivePort(port, connector.getLocalPort());
            activeBindAddress = bindAddress;
            activeBindExposure = exposure;
            tlsActive = tlsEnabled;
            String scheme = tlsEnabled ? "https" : "http";
            log.info("MCP Server started on {}://{}:{}", scheme, bindAddress, activePort);

        } catch (BindException e) {
            releaseFailedStart();
            log.error("Port {} already in use. Server not started.", port);
            throw new ServerStartException("Port " + port + " already in use", e);
        } catch (ServerStartException e) {
            releaseFailedStart();
            throw e;
        } catch (Exception e) {
            releaseFailedStart();
            // Classify the failure so the user gets an actionable message instead of one
            // generic "startup failed". The direct catch (BindException) above is the fast path; this
            // walks the FULL cause chain — a BindException can be nested deeper than one level — and,
            // on a TLS run, attributes a keystore/SSL load failure that slipped past
            // validateTlsConfig's pre-flight checks (wrong password, corrupt/empty/wrong-type file) to
            // INVALID_TLS_CONFIG. Both reuse the existing ServerStartException codes, so
            // McpServerManager.buildErrorMessage renders the existing friendly text unchanged.
            String code = classifyStartFailure(e, tlsEnabled);
            if (ServerStartException.PORT_IN_USE.equals(code)) {
                log.error("Port {} already in use. Server not started.", port);
                throw new ServerStartException("Port " + port + " already in use",
                        ServerStartException.PORT_IN_USE, e);
            }
            if (ServerStartException.INVALID_TLS_CONFIG.equals(code)) {
                log.error("TLS keystore could not be loaded (keystore: {}). Server not started.",
                        keystorePath, e);
                throw new ServerStartException("Keystore could not be loaded: " + e.getMessage(),
                        ServerStartException.INVALID_TLS_CONFIG, e);
            }
            log.error("Failed to start MCP server on {}:{}", bindAddress, port, e);
            throw new ServerStartException("Server startup failed: " + e.getMessage(), e);
        } catch (Error e) {
            releaseFailedStart();
            throw e;
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
     * The port the server reports once started: the requested one, or — when port 0 asked the system
     * for any free port — the one the connector actually bound. Pure and package-visible so the
     * non-zero case is pinned without starting a server.
     *
     * @param requested the port passed to {@code startServer}
     * @param bound     the connector's local port after start
     * @return {@code requested} unless it is 0, else {@code bound}
     */
    static int effectivePort(int requested, int bound) {
        return requested != 0 ? requested : bound;
    }

    /** Bounded depth for {@link #hasCauseOfType} so a malformed or cyclic cause chain cannot loop. */
    private static final int MAX_CAUSE_DEPTH = 20;

    /**
     * Classifies a server-start failure into a {@link ServerStartException} error code, so
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
     * Gracefully stops the Jetty server and releases the port, waiting at most
     * {@link #STOP_TIMEOUT_MS} for it.
     *
     * <p>A stop that runs past that (or whose wait is interrupted) is given up on: it is logged at
     * ERROR with the state of the Jetty threads, the server keeps its port and stays reported as
     * running, and a later call (a stop, or a start) waits for that same stop again. When that stop
     * finishes, the listeners added with {@link #addStopEndedListener} are told; the server is
     * released only when someone then calls {@link #releaseFinishedStop()} (in Archi, the server
     * manager does), or by a later stop or start, and until then it is still reported as running. A stop that fails with an exception has ended, so it is
     * logged and the server is released as before.</p>
     */
    public void stopServer() {
        if (!isRunning()) {
            log.debug("Server is not running, nothing to stop");
            return;
        }

        int stoppedPort = activePort;
        if (!stopWithinBound(jettyServer)) {
            stopGivenUp = true;
            log.error("MCP Server did not finish stopping (waited up to {} ms) and may still be running on port {}. "
                    + "Jetty threads:\n{}", stopTimeoutMs, stoppedPort, jettyThreads());
            return;
        }
        cleanup();
        log.info("MCP Server stopped (was on port {})", stoppedPort);
    }

    /**
     * Releases what a start that failed part-way left behind. Jetty starts its thread pool before it
     * loads the keystore, so a keystore that cannot be loaded leaves a FAILED server whose pool is still
     * running; {@link #stopServer()} would not stop it, because a FAILED server is not running.
     * It runs for a start that fails with an {@link Error} too.
     *
     * <p>The stop is bounded like {@link #stopServer()}'s. One that runs past the bound is logged and
     * left to finish on its own daemon thread: the connectors are already closed, so it holds no port,
     * and since the server never ran there is no later stop to retry it.</p>
     *
     * <p>No exception leaves here: the caller is on its way to throwing the start's own failure, which
     * must be the one the user sees. An {@link Error} raised by the release itself is not caught and
     * would leave in place of the start's, with one exception: {@link #cleanup()} catches a
     * {@link LinkageError} from closing an MCP server.</p>
     */
    private void releaseFailedStart() {
        try {
            if (jettyServer != null && !jettyServer.isStopped() && !stopWithinBound(jettyServer)) {
                String waited = Thread.currentThread().isInterrupted()
                        ? "the wait for it was interrupted"
                        : "waited up to " + stopTimeoutMs + " ms";
                log.error("Jetty did not finish stopping after a failed start ({}); its threads:\n{}",
                        waited, jettyThreads());
            }
        } catch (RuntimeException e) {
            log.error("Error stopping Jetty after a failed start", e);
        } finally {
            cleanup();
        }
    }

    /**
     * Stops the server on a daemon thread of its own and waits for it at most {@link #stopTimeoutMs},
     * so a stop that never finishes cannot hold the caller, or keep Archi's JVM alive after exit.
     *
     * @return false when the wait ran out or was interrupted, true when the stop ended (even by failing)
     */
    private boolean stopWithinBound(Server server) {
        if (stopInFlight == null) {
            FutureTask<Void> stop = new FutureTask<>(() -> {
                jettyStop.stop(server);
                return null;
            });
            stopInFlight = stop;
            Thread stopper = new Thread(() -> {
                stop.run();
                // only once the stop counts as ended: a listener may need what the waiting caller holds
                tellStopEnded();
            }, THREAD_POOL_NAME + "-stop");
            stopper.setDaemon(true);
            stopper.start();
        }
        try {
            stopInFlight.get(stopTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            log.error("Error stopping Jetty server", e.getCause());
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for the MCP Server to stop");
            return false;
        }
        stopInFlight = null;
        return true;
    }

    /**
     * Adds a listener told, on the stop's own thread, each time a stop has ended, however it ended and
     * whether or not it was given up on. It is told only after the stop counts as ended, so a caller
     * waiting for that stop is released first, and {@link #releaseFinishedStop()} called from the
     * listener finds it finished. Listeners are told in the order they were added; one that throws an
     * exception is logged and the others are still told. An {@link Error} from one is not caught: the
     * listeners after it are not told.
     *
     * @param listener what to run when a stop has ended
     */
    void addStopEndedListener(Runnable listener) {
        stopEndedListeners.add(listener);
    }

    /**
     * Releases a server whose stop was given up on and has finished since, without waiting: the MCP
     * SDK servers are closed and the server is forgotten, as by a {@link #stopServer()} that finished
     * in time. Does nothing when no stop was given up on, or when the one given up on is still running.
     * It changes the server as {@link #stopServer()} does, so the same caller (in Archi, the server
     * manager, holding its lock) calls it.
     *
     * @return true when it released the server
     */
    boolean releaseFinishedStop() {
        FutureTask<Void> stop = stopInFlight;
        if (!stopGivenUp || stop == null || !stop.isDone()) {
            return false;
        }
        // the stop has ended, so this returns at once
        stopServer();
        return !isRunning();
    }

    private void tellStopEnded() {
        for (Runnable listener : stopEndedListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("A listener told that the MCP Server's stop has ended failed", e);
            }
        }
    }

    /**
     * Each Jetty thread (and the stop's own), plus any thread holding a lock one of them waits on: its
     * state, the lock it waits on and that lock's owner, every frame, and the locks it holds.
     */
    static String jettyThreads() {
        StringBuilder dump = new StringBuilder();
        try {
            ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
            Set<Long> owners = new HashSet<>();
            for (ThreadInfo info : threads) {
                if (isJettyThread(info) && info.getLockOwnerId() >= 0) {
                    owners.add(info.getLockOwnerId());
                }
            }
            for (ThreadInfo info : threads) {
                if (isJettyThread(info) || (info != null && owners.contains(info.getThreadId()))) {
                    appendThread(dump, info);
                }
            }
        } catch (RuntimeException e) {
            dump.append("(thread states unavailable: ").append(e).append(')');
        }
        return dump.toString();
    }

    private static boolean isJettyThread(ThreadInfo info) {
        return info != null && info.getThreadName().startsWith(THREAD_POOL_NAME);
    }

    private static void appendThread(StringBuilder dump, ThreadInfo info) {
        // the first line of toString() names the thread, its state, the lock and its owner; the rest
        // of toString() stops at eight frames, so the frames are written here in full
        String header = info.toString();
        int endOfFirstLine = header.indexOf('\n');
        dump.append(endOfFirstLine < 0 ? header + '\n' : header.substring(0, endOfFirstLine + 1));
        for (StackTraceElement frame : info.getStackTrace()) {
            dump.append("\tat ").append(frame).append('\n');
        }
        for (LockInfo held : info.getLockedSynchronizers()) {
            dump.append("\t- locked ").append(held).append('\n');
        }
        dump.append('\n');
    }

    /**
     * Checks whether the Jetty server is currently running.
     *
     * @return true if the server is started and running, or a stop of it was given up on
     */
    public boolean isRunning() {
        // read once: the server may be forgotten on another thread between two reads
        Server server = jettyServer;
        return server != null && (stopGivenUp || server.isRunning());
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
     * How the bind the server is listening on was classified when it started: the one answer the
     * Origin/Host guard and the server-file-access decision both read. Classified once, by the same
     * lookup that validated the bind, and never looked up again while the server runs.
     *
     * @return the bind's exposure, or null if the server is not running
     */
    public BindExposure getBindExposure() {
        if (isRunning()) {
            return activeBindExposure;
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

    /**
     * Closes both MCP SDK servers and forgets the server. A close that fails with an exception or a
     * {@link LinkageError} (the plugin's classes are broken) is logged and nothing leaves here. Any
     * other {@link Error} from a close (the SDK's own close raises only a
     * {@link VirtualMachineError} or a {@link ThreadDeath}) leaves here, but only after the other
     * close has been tried and the server forgotten.
     */
    private void cleanup() {
        try {
            closeMcpServer(streamableMcpServer, "streamable");
        } finally {
            try {
                closeMcpServer(sseMcpServer, "SSE");
            } finally {
                jettyServer = null;
                stopGivenUp = false;
                stopInFlight = null;
                streamableMcpServer = null;
                sseMcpServer = null;
                activePort = 0;
                activeBindAddress = null;
                activeBindExposure = null;
                tlsActive = false;
            }
        }
    }

    private void closeMcpServer(McpSyncServer server, String transport) {
        if (server == null) {
            return;
        }
        try {
            mcpServerClose.close(server);
        } catch (Exception e) {
            log.debug("Error closing {} MCP server during cleanup", transport, e);
        } catch (LinkageError e) {
            log.warn("Error closing {} MCP server during cleanup", transport, e);
        }
    }
}
