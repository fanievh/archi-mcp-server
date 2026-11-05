package net.vheerden.archi.mcp.server;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpSyncServer;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.ResourceHandler;
import net.vheerden.archi.mcp.handlers.SearchHandler;
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

    /** Added to a failed start's message when its clean-up could not stop the server in time. */
    static final String STILL_RUNNING_AFTER_FAILED_START =
            " The server did not finish stopping and may still be running.";

    /** What a stop that was given up on was part of. */
    private enum GivenUpStop {
        /** {@link #stop()}. */
        STOP,
        /** The clean-up after a failed start. */
        FAILED_START_CLEANUP
    }

    private final TransportConfig transportConfig;
    private final List<McpServerStateListener> listeners = new CopyOnWriteArrayList<>();

    private volatile ServerState state = ServerState.STOPPED;
    private volatile String lastErrorMessage;
    private volatile ArchiModelAccessor modelAccessor;
    private volatile SessionManager sessionManager;
    /**
     * The human-facing approve/reject seam. Held alongside the model accessor and
     * exposed to {@code ui/} so the Pending Approvals dock view can list/approve/reject without
     * reaching into {@code model/}. Null while the server is stopped (no accessor) — callers must
     * tolerate that (the view shows its empty/gate-off state).
     */
    private volatile ApprovalService approvalService;
    private final CommandRegistry commandRegistry;
    private final ResourceRegistry resourceRegistry;
    /** The stop given up on that the state and message report, or null when they report none. */
    private GivenUpStop givenUpStop;
    /** The failed start's own message, shown again once its clean-up's stop has finished. */
    private String failedStartMessage;

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
        // only a stop started later tells it, so nothing reaches this manager before it is built
        transportConfig.addStopEndedListener(this::onStopEnded);
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
     * transitions to {@link ServerState#ERROR} and the error message is stored. An {@link Error}
     * other than a {@link LinkageError} is then rethrown. An exception thrown while cleaning up
     * after the failure is logged, not thrown; an {@link Error} thrown there leaves here instead,
     * once the clean-up has tried to stop the server. An {@link Error} thrown by a state listener
     * told of {@link ServerState#STARTING} leaves here in its place, with that state already set
     * and nothing after it done. One thrown by a listener told of {@link ServerState#ERROR} also
     * leaves in its place, but only after the clean-up has run.</p>
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
        givenUpStop = null;
        failedStartMessage = null;

        try {
            // Read once per start: the same value decides which tools are registered and what the
            // server announces, and nothing re-reads it while the server runs.
            boolean readOnly = net.vheerden.archi.mcp.McpPlugin.getDefault().isReadOnly();
            boolean allowServerImageSources = net.vheerden.archi.mcp.McpPlugin.getDefault()
                    .isServerImageSourcesAllowed();
            initializeResources();
            transportConfig.setToolSpecifications(commandRegistry.getToolSpecifications());
            transportConfig.setResourceSpecifications(resourceRegistry.getResourceSpecifications());
            transportConfig.setResourceTemplateSpecifications(
                    resourceRegistry.getResourceTemplateSpecifications());
            transportConfig.setInstructions(readOnly ? TransportConfig.READ_ONLY_INSTRUCTIONS : null);
            transportConfig.startServer();
            // Judged on the bind the server took, by the one classification the server made as it bound it (the
            // Origin/Host guard reads the same value). Only a loopback bind allows server file access without the
            // setting: an exposed bind, one that could not be classified, or no answer at all refuses.
            BindExposure boundTo = transportConfig.getBindExposure();
            boolean refuseServerImageSources = !allowServerImageSources
                    && (boundTo == null || !boundTo.allowsServerFileAccess());
            wireCommandRegistryServers();
            wireResourceRegistryServers();
            initializeModelAccessor();
            initializeHandlers(readOnly, refuseServerImageSources);
            // Startup invariant: the server must never advertise itself as RUNNING with zero
            // tools. If handler registration was skipped (e.g. a LinkageError earlier in this
            // try block), this turns an invisible "server up, tools/list empty" state into a
            // loud, logged ERROR rather than a silent failure.
            int toolCount = commandRegistry.getToolCount();
            if (toolCount == 0) {
                throw new IllegalStateException(
                        "Startup invariant violated: 0 tools registered after handler "
                        + "initialization");
            }
            setState(ServerState.RUNNING);
            logger.info("MCP Server started successfully on port {} with {} tools",
                    transportConfig.getPort(), toolCount);
        } catch (ServerStartException e) {
            lastErrorMessage = buildErrorMessage(e);
            setState(ServerState.ERROR);
            logger.error("Failed to start MCP Server: {}", lastErrorMessage);
        } catch (Exception | LinkageError e) {
            // LinkageError (VerifyError, NoClassDefFoundError, NoSuchMethodError) is how a host
            // binary incompatibility surfaces — e.g. Archi shipping a new major version of a
            // Require-Bundle dependency. It is an Error, not an Exception, so the previous
            // catch(Exception) let it escape to the SWT event loop, leaving the HTTP server up
            // with zero tools and no ERROR state set. Catch it here so every startup failure is
            // captured, logged, and surfaced as ERROR state.
            try {
                lastErrorMessage = "Server initialization failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
                setState(ServerState.ERROR);
                logger.error("Failed to initialize MCP Server", e);
            } finally {
                cleanupAfterFailedStart();
            }
        } catch (Error e) {
            // Any other Error (AssertionError, StackOverflowError, OutOfMemoryError, ...) would leave
            // the state STARTING, which every later start ignores, so Start could not be retried; and
            // a server already up would keep serving half-wired. Record and clean up as above, then
            // rethrow the same instance so the caller sees the Error as before.
            try {
                lastErrorMessage = "Server initialization failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
                setState(ServerState.ERROR);
                logger.error("Failed to initialize MCP Server", e);
            } finally {
                cleanupAfterFailedStart();
            }
            throw e;
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
        givenUpStop = null;
        failedStartMessage = null;

        try {
            initializeResources();
            transportConfig.setToolSpecifications(commandRegistry.getToolSpecifications());
            transportConfig.setResourceSpecifications(resourceRegistry.getResourceSpecifications());
            transportConfig.setResourceTemplateSpecifications(
                    resourceRegistry.getResourceTemplateSpecifications());
            transportConfig.setInstructions(null);
            transportConfig.startServer(port, bindAddress);
            wireCommandRegistryServers();
            wireResourceRegistryServers();
            initializeModelAccessor();
            initializeHandlers(false, false);
            // Startup invariant: the server must never advertise itself as RUNNING with zero
            // tools. If handler registration was skipped (e.g. a LinkageError earlier in this
            // try block), this turns an invisible "server up, tools/list empty" state into a
            // loud, logged ERROR rather than a silent failure.
            int toolCount = commandRegistry.getToolCount();
            if (toolCount == 0) {
                throw new IllegalStateException(
                        "Startup invariant violated: 0 tools registered after handler "
                        + "initialization");
            }
            setState(ServerState.RUNNING);
            logger.info("MCP Server started successfully on port {} with {} tools",
                    transportConfig.getPort(), toolCount);
        } catch (ServerStartException e) {
            lastErrorMessage = buildErrorMessage(e);
            setState(ServerState.ERROR);
            logger.error("Failed to start MCP Server: {}", lastErrorMessage);
        } catch (Exception | LinkageError e) {
            // LinkageError (VerifyError, NoClassDefFoundError, NoSuchMethodError) is how a host
            // binary incompatibility surfaces — e.g. Archi shipping a new major version of a
            // Require-Bundle dependency. It is an Error, not an Exception, so the previous
            // catch(Exception) let it escape to the SWT event loop, leaving the HTTP server up
            // with zero tools and no ERROR state set. Catch it here so every startup failure is
            // captured, logged, and surfaced as ERROR state.
            try {
                lastErrorMessage = "Server initialization failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
                setState(ServerState.ERROR);
                logger.error("Failed to initialize MCP Server", e);
            } finally {
                cleanupAfterFailedStart();
            }
        } catch (Error e) {
            // Any other Error (AssertionError, StackOverflowError, OutOfMemoryError, ...) would leave
            // the state STARTING, which every later start ignores, so Start could not be retried; and
            // a server already up would keep serving half-wired. Record and clean up as above, then
            // rethrow the same instance so the caller sees the Error as before.
            try {
                lastErrorMessage = "Server initialization failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
                setState(ServerState.ERROR);
                logger.error("Failed to initialize MCP Server", e);
            } finally {
                cleanupAfterFailedStart();
            }
            throw e;
        }
    }

    /**
     * Stops the MCP server gracefully.
     *
     * <p>If the server is not running, this is a no-op. A stop that the transport gives up on leaves
     * the state {@link ServerState#ERROR}; if that stop finishes later, the state becomes
     * {@link ServerState#STOPPED} then, without another call.</p>
     *
     * <p>Synchronized to prevent concurrent start/stop calls from corrupting state.</p>
     */
    public synchronized void stop() {
        if (state == ServerState.STOPPED || state == ServerState.STOPPING) {
            logger.debug("Server is already {} — ignoring stop request", state);
            return;
        }

        setState(ServerState.STOPPING);

        try {
            clearAndStopServer("Error during server stop");
        } finally {
            failedStartMessage = null;
            if (transportConfig.isRunning()) {
                givenUpStop = GivenUpStop.STOP;
                lastErrorMessage = "Server stop failed — Jetty may still be running.";
                setState(ServerState.ERROR);
                logger.error("Server stop incomplete: Jetty still running");
            } else {
                givenUpStop = null;
                setState(ServerState.STOPPED);
                lastErrorMessage = null;
            }
        }
    }

    /**
     * Told by the transport, on the stop's own thread, that a stop has ended. If it is a stop the
     * transport gave up on, the server is released now. When the state reports that stop, a Stop's
     * {@link ServerState#ERROR} becomes {@link ServerState#STOPPED}, and a failed start's message loses
     * the sentence saying the server may still be running (the state stays ERROR: the start still
     * failed). Otherwise the state and the message are left as they are: for a stop that finished in
     * time nothing is released either, and a stop given up on that the state no longer reports (a start
     * has run since) is still released.
     *
     * <p>Synchronized like {@link #stop()}, which may be waiting for this very stop: the transport tells
     * this only once the stop counts as ended, so that wait is over before this runs. The state is
     * decided from the transport afterwards even when the release throws, so a released server is not
     * left reported as possibly running.</p>
     */
    private synchronized void onStopEnded() {
        GivenUpStop pending = givenUpStop;
        try {
            transportConfig.releaseFinishedStop();
        } finally {
            if (pending != null && state == ServerState.ERROR && !transportConfig.isRunning()) {
                givenUpStop = null;
                String startFailure = failedStartMessage;
                failedStartMessage = null;
                logger.info("The MCP Server's stop finished after it was given up on; the server is stopped");
                // last, so an Error from a state listener leaves nothing above undone
                if (pending == GivenUpStop.STOP) {
                    lastErrorMessage = null;
                    setState(ServerState.STOPPED);
                } else {
                    lastErrorMessage = startFailure;
                }
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
     * Returns the human-facing approve/reject seam for the Pending Approvals dock view,
     * or {@code null} when the server is stopped (no model accessor wired). The {@code ui/} view
     * reaches the seam through this singleton, the same pattern the toggle handlers already use.
     *
     * @return the approval service, or null if the server is not started
     */
    public ApprovalService getApprovalService() {
        return approvalService;
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

        // Wire the dispatcher's gate read to the global, human-owned approval bit.
        // The agent has no path to flip this — only the SWT toggle writes ApprovalMode.
        var dispatcher = accessor.getMutationDispatcher();
        if (dispatcher != null) {
            dispatcher.setApprovalModeProvider(() -> ApprovalMode.getInstance().isOn());
            // The human-facing approve/reject seam for the Pending Approvals dock view.
            this.approvalService = new ApprovalService(dispatcher);
        }

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
     *
     * @param readOnly true to register only the tools that cannot change anything
     * @param refuseServerImageSources true to refuse an image from a file path on the server or a URL, and
     *        export-view's file output
     */
    private void initializeHandlers(boolean readOnly, boolean refuseServerImageSources) {
        // Create SessionManager and register as model change listener
        SessionManager sm = new SessionManager(
                SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        modelAccessor.addModelChangeListener(sm);
        this.sessionManager = sm;

        ResponseFormatter formatter = new ResponseFormatter();

        // Single source of truth — the same wiring the tool-discovery integration
        // test consumes, so the test can never silently drift from production.
        HandlerRegistrar.registerAll(modelAccessor, formatter, commandRegistry, sm, readOnly,
                refuseServerImageSources);

        logger.info("MCP tool handlers initialized (session management enabled)");
        if (readOnly) {
            logger.info("Read-only mode: {} tools that are not read tools are not offered",
                    commandRegistry.getWithheldToolNames().size());
        }
        if (refuseServerImageSources) {
            logger.info("The server is reachable from other machines: add-image-to-model takes images only "
                    + "as imageData, and export-view returns images only inline (set "
                    + "mcp.server.allowServerImageSources to allow file paths, URLs and file output)");
        }
    }

    /**
     * Disposes the session manager and lets go of it, even when removing its listener from the model
     * accessor throws or the dispose itself does. If both throw, the dispose's throw leaves and the
     * other is lost.
     */
    private void disposeSessionManager() {
        SessionManager sm = this.sessionManager;
        if (sm != null) {
            try {
                if (modelAccessor != null) {
                    modelAccessor.removeModelChangeListener(sm);
                }
            } finally {
                try {
                    sm.dispose();
                } finally {
                    this.sessionManager = null;
                }
            }
        }
    }

    /**
     * Disposes the model accessor and lets go of it and the approval service, even when removing the
     * manager's listener, clearing the dispatcher's sessions or the dispose itself throws. A throw from
     * removing the listener skips clearing the sessions. If the dispose throws after an earlier call
     * did, the dispose's throw leaves and the earlier one is lost.
     */
    private void disposeModelAccessor() {
        ArchiModelAccessor accessor = this.modelAccessor;
        if (accessor != null) {
            try {
                accessor.removeModelChangeListener(this);
                MutationDispatcher dispatcher = accessor.getMutationDispatcher();
                if (dispatcher != null) {
                    dispatcher.clearAllSessions();
                }
            } finally {
                try {
                    accessor.dispose();
                } finally {
                    this.modelAccessor = null;
                    this.approvalService = null;
                }
            }
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
     * when Jetty is already running). Called from a {@code finally} around the failure's
     * bookkeeping, so that an {@link Error} from a state listener told of
     * {@link ServerState#ERROR} does not skip it.
     *
     * <p>When the transport gives up on stopping the server, the failure's message gains
     * {@link #STILL_RUNNING_AFTER_FAILED_START}; listeners, already told of
     * {@link ServerState#ERROR}, are not told again. The sentence is removed when that stop
     * finishes.</p>
     */
    private void cleanupAfterFailedStart() {
        try {
            clearAndStopServer("Failed to stop transport during cleanup");
        } finally {
            if (transportConfig.isRunning()) {
                givenUpStop = GivenUpStop.FAILED_START_CLEANUP;
                failedStartMessage = lastErrorMessage;
                lastErrorMessage = lastErrorMessage + STILL_RUNNING_AFTER_FAILED_START;
            }
        }
    }

    /**
     * Clears the registries, disposes the session manager and model accessor, then stops the
     * transport. An exception from a step is logged and the next step runs. An {@link Error} from a
     * step skips the steps after it but not the stop, and then propagates. Within its own step, disposing
     * the session manager or the model accessor finishes, and lets go of it, whatever one of its calls
     * throws; an {@link Error} from the session manager's step still skips the model accessor's step.
     *
     * @param stopFailureMessage what to log if the stop itself throws an exception
     */
    private void clearAndStopServer(String stopFailureMessage) {
        try {
            runCleanupStep("clear the command registry's servers", commandRegistry::clearMcpServers);
            runCleanupStep("clear the command registry's tools", commandRegistry::clearTools);
            runCleanupStep("clear the resource registry's servers", resourceRegistry::clearMcpServers);
            runCleanupStep("clear the resource registry's resources", resourceRegistry::clearResources);
            runCleanupStep("dispose the session manager", this::disposeSessionManager);
            runCleanupStep("dispose the model accessor", this::disposeModelAccessor);
        } finally {
            try {
                transportConfig.stopServer();
            } catch (Exception stopEx) {
                logger.error(stopFailureMessage, stopEx);
            }
        }
    }

    private void runCleanupStep(String step, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            logger.error("Failed to {} during server clean-up", step, e);
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
