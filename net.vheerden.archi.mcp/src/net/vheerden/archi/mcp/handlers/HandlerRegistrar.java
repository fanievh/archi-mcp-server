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
     * {@code (accessor, formatter, registry, sm)}, but {@link RenderHandler} and
     * {@link CommandStackHandler} take no {@link SessionManager}, and
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
        new ModelQueryHandler(accessor, formatter, registry, sm).registerTools();
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
        new RenderHandler(accessor, formatter, registry).registerTools();
        new ImageHandler(accessor, formatter, registry, sm).registerTools();
        new DeletionHandler(accessor, formatter, registry, sm).registerTools();
        new FolderMutationHandler(accessor, formatter, registry, sm).registerTools();
        new SessionHandler(sm, formatter, registry).registerTools();
        new CommandStackHandler(accessor, formatter, registry).registerTools();
        // Guidance lookup is registered here, not from the server bootstrap, so that the
        // contract tests which enumerate through this method see it like every other tool.
        new ResourceHandler().registerTools(formatter, registry);
    }
}
