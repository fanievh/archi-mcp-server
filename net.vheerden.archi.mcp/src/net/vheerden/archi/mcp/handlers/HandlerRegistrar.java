package net.vheerden.archi.mcp.handlers;

import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Single source of truth for wiring every MCP tool handler into the
 * {@link CommandRegistry}.
 *
 * <p>Both the production boot path ({@code McpServerManager.initializeHandlers})
 * and the {@code ToolDiscoveryIntegrationTest} call {@link #registerAll} so the
 * test verifies the exact set of tools production registers — it can no longer
 * silently omit a handler (as it once dropped {@link ImageHandler}) or drift from
 * a hand-counted total.</p>
 *
 * <p><strong>Architecture boundary:</strong> this composition-root utility touches
 * only handler constructors plus the {@link ArchiModelAccessor} interface,
 * {@link ResponseFormatter}, {@link CommandRegistry}, and {@link SessionManager}.
 * It MUST NOT import anything from {@code server/}, Jetty, EMF, GEF, or SWT — doing
 * so would drag OSGi singletons into the plain-JUnit discovery test classpath.</p>
 */
public final class HandlerRegistrar {

    private HandlerRegistrar() {
        // utility class — not instantiable
    }

    /**
     * Instantiates every MCP tool handler exactly once and registers its tools
     * with {@code registry}, in the canonical production order.
     *
     * <p>Handler constructor signatures are intentionally not uniform — most take
     * {@code (accessor, formatter, registry, sm)}, but {@link RenderHandler} takes no
     * {@link SessionManager}, and
     * {@link SessionHandler} takes {@code (sm, formatter, registry)}. Each
     * {@code new} call below preserves its handler's exact signature.</p>
     *
     * @param accessor  model accessor passed to every model-touching handler
     * @param formatter shared response formatter
     * @param registry  registry the tools are registered into
     * @param sm        session manager shared by session-aware handlers
     */
    public static void registerAll(ArchiModelAccessor accessor,
                                   ResponseFormatter formatter,
                                   CommandRegistry registry,
                                   SessionManager sm) {
        registerAll(accessor, formatter, registry, sm, false);
    }

    /**
     * As {@link #registerAll(ArchiModelAccessor, ResponseFormatter, CommandRegistry, SessionManager)},
     * optionally for a read-only server.
     *
     * <p>When {@code readOnly} is true, only the tools {@link ToolAccess} classifies READ are registered;
     * every other tool is withheld, so it is neither listed nor callable. The tools that are registered
     * keep the same definitions as on a full server, apart from what each says about read-only mode.</p>
     *
     * @param accessor  model accessor passed to every model-touching handler
     * @param formatter shared response formatter
     * @param registry  registry the tools are registered into
     * @param sm        session manager shared by session-aware handlers
     * @param readOnly  true to register only the tools that cannot change anything
     */
    public static void registerAll(ArchiModelAccessor accessor,
                                   ResponseFormatter formatter,
                                   CommandRegistry registry,
                                   SessionManager sm,
                                   boolean readOnly) {
        registerAll(accessor, formatter, registry, sm, readOnly, false);
    }

    /**
     * As {@link #registerAll(ArchiModelAccessor, ResponseFormatter, CommandRegistry, SessionManager, boolean)},
     * optionally refusing what makes the server use its own disk or network for a caller: an image read from a
     * file path on the server or fetched from a URL, and an exported view written to a file on the server.
     *
     * @param accessor  model accessor passed to every model-touching handler
     * @param formatter shared response formatter
     * @param registry  registry the tools are registered into
     * @param sm        session manager shared by session-aware handlers
     * @param readOnly  true to register only the tools that cannot change anything
     * @param refuseServerImageSources true to refuse an image from a file path on the server or a URL, and file
     *        output from export-view, as a server reachable from other machines does unless its operator allows
     *        them
     */
    public static void registerAll(ArchiModelAccessor accessor,
                                   ResponseFormatter formatter,
                                   CommandRegistry registry,
                                   SessionManager sm,
                                   boolean readOnly,
                                   boolean refuseServerImageSources) {
        // Every tool that addresses a model declares modelId, declared once here rather than in
        // each handler, so a tool added later takes it too. Then each tool takes its read/write hints
        // from the one access table, which refuses a tool it does not classify.
        registry.setToolDecorator(tool -> ToolAccess.annotate(ModelCallScope.withModelId(tool)));
        registry.setToolAdmission(readOnly ? ToolAccess::isRead : null);
        new ModelQueryHandler(accessor, formatter, registry, sm, readOnly).registerTools();
        new ViewHandler(accessor, formatter, registry, sm).registerTools();
        new SearchHandler(accessor, formatter, registry, sm).registerTools();
        new TraversalHandler(accessor, formatter, registry, sm).registerTools();
        new FolderHandler(accessor, formatter, registry, sm).registerTools();
        new MutationHandler(accessor, formatter, registry, sm).registerTools();
        new ElementCreationHandler(accessor, formatter, registry, sm).registerTools();
        new SpecializationHandler(accessor, formatter, registry, sm).registerTools();
        new ElementUpdateHandler(accessor, formatter, registry, sm).registerTools();
        new DiscoveryHandler(accessor, formatter, registry, sm).registerTools();
        new ApprovalHandler(accessor, formatter, registry, sm).registerTools();
        new ViewPlacementHandler(accessor, formatter, registry, sm).registerTools();
        new RenderHandler(accessor, formatter, registry, readOnly, refuseServerImageSources).registerTools();
        new ImageHandler(accessor, formatter, registry, sm, refuseServerImageSources).registerTools();
        new DeletionHandler(accessor, formatter, registry, sm).registerTools();
        new FolderMutationHandler(accessor, formatter, registry, sm).registerTools();
        new SessionHandler(sm, formatter, registry).registerTools();
        new CommandStackHandler(accessor, formatter, registry, sm).registerTools();
        // Guidance lookup is registered here, not from the server bootstrap, so that the
        // contract tests which enumerate through this method see it like every other tool.
        new ResourceHandler().registerTools(formatter, registry);
        // Every tool call ends with nothing it was admitted against left bound to its thread.
        var dispatcher = accessor.getMutationDispatcher();
        registry.setCallExitHook(dispatcher == null ? null : dispatcher::endCall);
        // Every tool call resolves the model it acts on once, and its response names that model; then a
        // call carrying an argument its tool does not read is refused before the tool runs.
        registry.setCallScope(new ArgumentCallScope(dispatcher == null || dispatcher.selection() == null ? null
                : new ModelCallScope(dispatcher.selection(), formatter, sm), registry, formatter));
        // Session caches and version tracking are keyed by the model instance each call acts on.
        if (sm != null && dispatcher != null && dispatcher.selection() != null) {
            sm.setModelKeySupplier(dispatcher.selection()::cacheKey);
            sm.setSessionEvictionListener(dispatcher.selection()::forgetSession);
        }
    }
}
