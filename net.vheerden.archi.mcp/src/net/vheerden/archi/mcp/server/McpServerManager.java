package net.vheerden.archi.mcp.server;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpSyncServer;
import net.vheerden.archi.mcp.handlers.ApprovalHandler;
import net.vheerden.archi.mcp.handlers.CommandStackHandler;
import net.vheerden.archi.mcp.handlers.DeletionHandler;
import net.vheerden.archi.mcp.handlers.FolderMutationHandler;
import net.vheerden.archi.mcp.handlers.DiscoveryHandler;
import net.vheerden.archi.mcp.handlers.FolderHandler;
import net.vheerden.archi.mcp.handlers.ModelQueryHandler;
import net.vheerden.archi.mcp.handlers.ElementCreationHandler;
import net.vheerden.archi.mcp.handlers.ElementUpdateHandler;
import net.vheerden.archi.mcp.handlers.MutationHandler;
import net.vheerden.archi.mcp.handlers.RenderHandler;
import net.vheerden.archi.mcp.handlers.ResourceHandler;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.handlers.SessionHandler;
import net.vheerden.archi.mcp.handlers.TraversalHandler;
import net.vheerden.archi.mcp.handlers.ViewHandler;
import net.vheerden.archi.mcp.handlers.ViewPlacementHandler;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.ArchiModelAccessorImpl;
import net.vheerden.archi.mcp.model.ModelChangeListener;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.registry.ResourceRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Manages the MCP server lifecycle: start, stop, and status.
 *
 * <p>Singleton that wraps {@link TransportConfig} with higher-level lifecycle
 * management, state tracking, and listener notifications.</p>
 *
 * <p>This class is the bridge between the protocol layer ({@code server/} package)
 * and the UI layer ({@code ui/} package). UI components register as
 * {@link McpServerStateListener}s to receive state change notifications.</p>
 */
public class McpServerManager implements ModelChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(McpServerManager.class);

    private static McpServerManager instance;

    private final TransportConfig transportConfig;
    private final List<McpServerStateListener> listeners = new CopyOnWriteArrayList<>();

    private volatile ServerState state = ServerState.STOPPED;
    private volatile String lastErrorMessage;
    private volatile ArchiModelAccessor modelAccessor;
    private volatile SessionManager sessionManager;
    private final CommandRegistry commandRegistry;
    private final ResourceRegistry resourceRegistry;

    /**
     * Creates a McpServerManager with the given TransportConfig.
     * Visible for testing.
     *
     * @param transportConfig the transport configuration to delegate to
     */
    McpServerManager(TransportConfig transportConfig) {
        this.transportConfig = transportConfig;
        this.commandRegistry = new CommandRegistry();
        this.resourceRegistry = new ResourceRegistry();
    }

    /**
     * Returns the singleton instance, creating it lazily on first access.
     *
     * @return the shared McpServerManager instance
     */
    public static synchronized McpServerManager getInstance() {
        if (instance == null) {
            instance = new McpServerManager(new TransportConfig());
        }
        return instance;
    }

    /**
     * Resets the singleton instance. Visible for testing only.
     */
    static synchronized void resetInstance() {
        instance = null;
    }

    /**
     * Starts the MCP server using preferences from {@link net.vheerden.archi.mcp.McpPlugin}.
     *
     * <p>If the server is already running, this is a no-op. On failure, the server
     * transitions to {@link ServerState#ERROR} and the error message is stored.</p>
     *
     * <p>Synchronized to prevent concurrent start/stop calls from corrupting state
     * (earlyStartup runs on a background thread while UI handlers run on the SWT thread).</p>
     */
    public synchronized void start() {
        if (state == ServerState.RUNNING || state == ServerState.STARTING) {
            logger.debug("Server is already {} — ignoring start request", state);
            return;
        }

        // Guard against McpPlugin not being activated yet
        if (net.vheerden.archi.mcp.McpPlugin.getDefault() == null) {
            lastErrorMessage = "Plugin not yet activated. Try starting the server again.";
            setState(ServerState.ERROR);
            logger.error("Failed to start MCP Server: {}", lastErrorMessage);
            return;
        }

        setState(ServerState.STARTING);
        lastErrorMessage = null;

        try {
            initializeResources();
            transportConfig.setToolSpecifications(commandRegistry.getToolSpecifications());
            transportConfig.setResourceSpecifications(resourceRegistry.getResourceSpecifications());
            transportConfig.startServer();
            wireCommandRegistryServers();
            wireResourceRegistryServers();
            initializeModelAccessor();
            initializeHandlers();
            setState(ServerState.RUNNING);
            logger.info("MCP Server started successfully on port {}", transportConfig.getPort());
        } catch (ServerStartException e) {
            lastErrorMessage = buildErrorMessage(e);
            setState(ServerState.ERROR);
            logger.error("Failed to start MCP Server: {}", lastErrorMessage);
        } catch (Exception e) {
            lastErrorMessage = "Server initialization failed: " + e.getMessage();
            setState(ServerState.ERROR);
            logger.error("Failed to initialize MCP Server", e);
            cleanupAfterFailedStart();
        }
    }

    /**
     * Starts the MCP server on the specified port and bind address.
     * Package-visible for testing without McpPlugin preferences.
     *
     * @param port        the TCP port to listen on
     * @param bindAddress the network interface to bind to
     */
    synchronized void start(int port, String bindAddress) {
        if (state == ServerState.RUNNING || state == ServerState.STARTING) {
            logger.debug("Server is already {} — ignoring start request", state);
            return;
        }

        setState(ServerState.STARTING);
        lastErrorMessage = null;

        try {
            initializeResources();
            transportConfig.setToolSpecifications(commandRegistry.getToolSpecifications());
            transportConfig.setResourceSpecifications(resourceRegistry.getResourceSpecifications());
            transportConfig.startServer(port, bindAddress);
            wireCommandRegistryServers();
            wireResourceRegistryServers();
            initializeModelAccessor();
            initializeHandlers();
            setState(ServerState.RUNNING);
            logger.info("MCP Server started successfully on port {}", transportConfig.getPort());
        } catch (ServerStartException e) {
            lastErrorMessage = buildErrorMessage(e);
            setState(ServerState.ERROR);
            logger.error("Failed to start MCP Server: {}", lastErrorMessage);
        } catch (Exception e) {
            lastErrorMessage = "Server initialization failed: " + e.getMessage();
            setState(ServerState.ERROR);
            logger.error("Failed to initialize MCP Server", e);
            cleanupAfterFailedStart();
        }
    }

    /**
     * Stops the MCP server gracefully.
     *
     * <p>If the server is not running, this is a no-op.</p>
     *
     * <p>Synchronized to prevent concurrent start/stop calls from corrupting state.</p>
     */
    public synchronized void stop() {
        if (state == ServerState.STOPPED || state == ServerState.STOPPING) {
            logger.debug("Server is already {} — ignoring stop request", state);
            return;
        }

        setState(ServerState.STOPPING);

        commandRegistry.clearMcpServers();
        commandRegistry.clearTools();
        resourceRegistry.clearMcpServers();
        resourceRegistry.clearResources();
        disposeSessionManager();
        disposeModelAccessor();

        try {
            transportConfig.stopServer();
        } catch (Exception e) {
            logger.error("Error during server stop", e);
        } finally {
            if (transportConfig.isRunning()) {
                lastErrorMessage = "Server stop failed — Jetty may still be running.";
                setState(ServerState.ERROR);
                logger.error("Server stop incomplete: Jetty still running");
            } else {
                setState(ServerState.STOPPED);
                lastErrorMessage = null;
            }
        }
    }

    /**
     * Returns whether the underlying Jetty server is currently running.
     *
     * @return true if the server is running
     */
    public boolean isRunning() {
        return transportConfig.isRunning();
    }

    /**
     * Returns the active port number.
     *
     * @return the port, or 0 if not running
     */
    public int getPort() {
        return transportConfig.getPort();
    }

    /**
     * Returns the bind address the server is listening on.
     *
     * @return the active bind address, or null if not running
     */
    public String getBindAddress() {
        return transportConfig.getBindAddress();
    }

    /**
     * Returns the name of the currently active ArchiMate model.
     *
     * @return Optional containing the model name, or empty if no model loaded or server not running
     */
    public Optional<String> getCurrentModelName() {
        ArchiModelAccessor accessor = this.modelAccessor;
        if (accessor == null) {
            return Optional.empty();
        }
        return accessor.getCurrentModelName();
    }

    /**
     * Builds the full server URL from the current running configuration.
     *
     * @return the full URL (e.g. "http://localhost:18090/mcp"), or null if not running
     */
    public String buildServerUrl() {
        if (!isRunning()) {
            return null;
        }
        String addr = getBindAddress();
        // 0.0.0.0 binds all interfaces — display as localhost for user-facing URLs
        String displayAddr = "0.0.0.0".equals(addr) ? "localhost" : addr;
        String scheme = transportConfig.isTlsEnabled() ? "https" : "http";
        return scheme + "://" + displayAddr + ":" + getPort() + "/mcp";
    }

    /**
     * Returns the current server state.
     *
     * @return the server state
     */
    public ServerState getState() {
        return state;
    }

    /**
     * Returns the last error message, or null if no error.
     *
     * @return the error message, or null
     */
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    /**
     * Returns the Streamable-HTTP MCP server instance for tool registration.
     *
     * @return the McpSyncServer, or null if not started
     */
    public McpSyncServer getStreamableMcpServer() {
        return transportConfig.getStreamableMcpServer();
    }

    /**
     * Returns the SSE MCP server instance for tool registration.
     *
     * @return the McpSyncServer, or null if not started
     */
    public McpSyncServer getSseMcpServer() {
        return transportConfig.getSseMcpServer();
    }

    /**
     * Registers a listener for server state changes.
     *
     * @param listener the listener to add
     */
    public void addStateListener(McpServerStateListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a previously registered state listener.
     *
     * @param listener the listener to remove
     */
    public void removeStateListener(McpServerStateListener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns the model accessor for querying the ArchiMate model.
     *
     * @return the model accessor, or null if server not started
     */
    public ArchiModelAccessor getModelAccessor() {
        return modelAccessor;
    }

    /**
     * Returns the command registry for tool registration.
     *
     * @return the command registry
     */
    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    /**
     * Returns the resource registry for resource registration.
     *
     * @return the resource registry
     */
    public ResourceRegistry getResourceRegistry() {
        return resourceRegistry;
    }

    // ---- ModelChangeListener ----

    @Override
    public void onModelChanged(String modelName, String modelId) {
        if (modelName != null) {
            logger.info("Active model changed: '{}' (id: {})", modelName, modelId);
        } else {
            logger.warn("Active model removed — no ArchiMate model loaded");
        }
        // Notify state listeners so UI (menu label) refreshes with new model name
        notifyListenersModelChanged();
    }

    /**
     * Notifies all state listeners of a model change by firing a state change
     * with the current state (RUNNING→RUNNING). This triggers menu label refresh
     * in ToggleServerHandler without requiring a separate listener interface.
     */
    private void notifyListenersModelChanged() {
        ServerState currentState = this.state;
        if (currentState != ServerState.RUNNING) {
            return; // Only refresh menu when server is actually running
        }
        for (McpServerStateListener listener : listeners) {
            try {
                listener.onStateChanged(currentState, currentState);
            } catch (Exception e) {
                logger.warn("State listener threw exception during model change notification", e);
            }
        }
    }

    // ---- Model accessor lifecycle ----

    private void initializeModelAccessor() {
        ArchiModelAccessor accessor = new ArchiModelAccessorImpl();
        accessor.addModelChangeListener(this);
        this.modelAccessor = accessor;

        if (accessor.isModelLoaded()) {
            String name = accessor.getCurrentModelName().orElse("(unnamed)");
            String id = accessor.getCurrentModelId().orElse("(no id)");
            logger.info("MCP Server using model: '{}' (id: {})", name, id);
        } else {
            logger.warn("MCP Server started but no ArchiMate model is loaded");
        }
    }

    /**
     * Creates and initializes tool handlers, registering their tools with the
     * command registry. Called after model accessor and command registry servers
     * are wired, so runtime tool additions notify connected clients.
     */
    private void initializeHandlers() {
        // Create SessionManager and register as model change listener
        SessionManager sm = new SessionManager(
                SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        modelAccessor.addModelChangeListener(sm);
        this.sessionManager = sm;

        ResponseFormatter formatter = new ResponseFormatter();

        ModelQueryHandler modelQueryHandler = new ModelQueryHandler(
                modelAccessor, formatter, commandRegistry, sm);
        modelQueryHandler.registerTools();

        ViewHandler viewHandler = new ViewHandler(
                modelAccessor, formatter, commandRegistry, sm);
        viewHandler.registerTools();

        SearchHandler searchHandler = new SearchHandler(
                modelAccessor, formatter, commandRegistry, sm);
        searchHandler.registerTools();

        TraversalHandler traversalHandler = new TraversalHandler(
                modelAccessor, formatter, commandRegistry, sm);
        traversalHandler.registerTools();

        FolderHandler folderHandler = new FolderHandler(
                modelAccessor, formatter, commandRegistry, sm);
        folderHandler.registerTools();

        MutationHandler mutationHandler = new MutationHandler(
                modelAccessor, formatter, commandRegistry, sm);
        mutationHandler.registerTools();

        ElementCreationHandler elementCreationHandler = new ElementCreationHandler(
                modelAccessor, formatter, commandRegistry, sm);
        elementCreationHandler.registerTools();

        ElementUpdateHandler elementUpdateHandler = new ElementUpdateHandler(
                modelAccessor, formatter, commandRegistry, sm);
        elementUpdateHandler.registerTools();

        DiscoveryHandler discoveryHandler = new DiscoveryHandler(
                modelAccessor, formatter, commandRegistry, sm);
        discoveryHandler.registerTools();

        ApprovalHandler approvalHandler = new ApprovalHandler(
                modelAccessor, formatter, commandRegistry, sm);
        approvalHandler.registerTools();

        ViewPlacementHandler viewPlacementHandler = new ViewPlacementHandler(
                modelAccessor, formatter, commandRegistry, sm);
        viewPlacementHandler.registerTools();

        RenderHandler renderHandler = new RenderHandler(
                modelAccessor, formatter, commandRegistry);
        renderHandler.registerTools();

        DeletionHandler deletionHandler = new DeletionHandler(
                modelAccessor, formatter, commandRegistry, sm);
        deletionHandler.registerTools();

        FolderMutationHandler folderMutationHandler = new FolderMutationHandler(
                modelAccessor, formatter, commandRegistry, sm);
        folderMutationHandler.registerTools();

        SessionHandler sessionHandler = new SessionHandler(
                sm, formatter, commandRegistry);
        sessionHandler.registerTools();

        CommandStackHandler commandStackHandler = new CommandStackHandler(
                modelAccessor, formatter, commandRegistry);
        commandStackHandler.registerTools();

        logger.info("MCP tool handlers initialized (session management enabled)");
    }

    private void disposeSessionManager() {
        SessionManager sm = this.sessionManager;
        if (sm != null) {
            if (modelAccessor != null) {
                modelAccessor.removeModelChangeListener(sm);
            }
            sm.dispose();
            this.sessionManager = null;
        }
    }

    private void disposeModelAccessor() {
        ArchiModelAccessor accessor = this.modelAccessor;
        if (accessor != null) {
            accessor.removeModelChangeListener(this);
            MutationDispatcher dispatcher = accessor.getMutationDispatcher();
            if (dispatcher != null) {
                dispatcher.clearAllSessions();
            }
            accessor.dispose();
            this.modelAccessor = null;
        }
    }

    private void setState(ServerState newState) {
        ServerState oldState = this.state;
        this.state = newState;

        if (oldState != newState) {
            for (McpServerStateListener listener : listeners) {
                try {
                    listener.onStateChanged(oldState, newState);
                } catch (Exception e) {
                    logger.warn("State listener threw exception during {} → {} transition",
                            oldState, newState, e);
                }
            }
        }
    }

    /**
     * Loads static resource files and registers them as MCP resources.
     * Called before server start so resources are available at build time.
     *
     * <p>Clears any previously registered resources first to prevent
     * accumulation on retry after a failed start (e.g., port in use).</p>
     */
    private void initializeResources() {
        resourceRegistry.clearResources();
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.registerResources(resourceRegistry);
    }

    /**
     * Wires the built MCP server instances into the CommandRegistry for runtime tool additions.
     */
    private void wireCommandRegistryServers() {
        commandRegistry.setMcpServers(
                transportConfig.getStreamableMcpServer(),
                transportConfig.getSseMcpServer());
    }

    /**
     * Wires the built MCP server instances into the ResourceRegistry for runtime resource additions.
     */
    private void wireResourceRegistryServers() {
        resourceRegistry.setMcpServers(
                transportConfig.getStreamableMcpServer(),
                transportConfig.getSseMcpServer());
    }

    /**
     * Cleans up resources after a failed start (e.g., model accessor init failure
     * when Jetty is already running).
     */
    private void cleanupAfterFailedStart() {
        commandRegistry.clearMcpServers();
        commandRegistry.clearTools();
        resourceRegistry.clearMcpServers();
        resourceRegistry.clearResources();
        disposeSessionManager();
        disposeModelAccessor();
        try {
            transportConfig.stopServer();
        } catch (Exception stopEx) {
            logger.error("Failed to stop transport during cleanup", stopEx);
        }
    }

    private String buildErrorMessage(ServerStartException e) {
        String code = e.getErrorCode();
        if (ServerStartException.PORT_IN_USE.equals(code)) {
            return "Port is already in use. Change the port in Preferences > MCP Server.";
        } else if (ServerStartException.INVALID_BIND_ADDRESS.equals(code)) {
            return "Invalid bind address. Check Preferences > MCP Server.";
        } else if (ServerStartException.INVALID_TLS_CONFIG.equals(code)) {
            return "Invalid TLS configuration. Check keystore path and password in Preferences > MCP Server.";
        } else {
            return e.getMessage();
        }
    }
}
