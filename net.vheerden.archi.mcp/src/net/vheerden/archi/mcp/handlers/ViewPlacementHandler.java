package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.AnchorResolver;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.DispatchArm;
import net.vheerden.archi.mcp.model.ElementSizer;
import net.vheerden.archi.mcp.model.LayoutValidationException;
import net.vheerden.archi.mcp.model.LayoutValidationFailures;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.QualityTargetTermination;
import net.vheerden.archi.mcp.model.ImageParams;
import net.vheerden.archi.mcp.model.StylingParams;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.LayoutEntryFailure;
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AdjustViewSpacingResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyElementSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyGroupSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplySpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.DetectHubElementsResultDto;
import net.vheerden.archi.mcp.response.dto.DiagramImageDto;
import net.vheerden.archi.mcp.response.dto.LayoutFlatViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.ResizeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionSpec;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for view placement and editing tools (Stories 7-7, 7-8, 8-0c, 8-6, 9-0a, 9-2, 9-5, 9-6, 10-29, 11-20, 13-6, 14-6):
 * add-to-view, add-group-to-view, add-note-to-view, add-view-reference-to-view,
 * add-connection-to-view,
 * update-view-object, update-view-connection, remove-from-view, clear-view,
 * apply-positions, assess-layout, auto-route-connections,
 * auto-connect-view, layout-within-group, auto-layout-and-route, arrange-groups,
 * optimize-group-order, detect-hub-elements, layout-flat-view, adjust-view-spacing.
 *
 * <p>Places, updates, and removes visual elements and connections on ArchiMate
 * diagram views. Supports auto-placement, auto-connect, partial bounds update,
 * bendpoint replacement, cascade removal, and atomic view clearing.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF, GEF, SWT, or ArchimateTool model types. All mutation logic
 * goes through {@link ArchiModelAccessor}.</p>
 */
public class ViewPlacementHandler {

    private static final Logger logger = LoggerFactory.getLogger(ViewPlacementHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;

    public ViewPlacementHandler(ArchiModelAccessor accessor,
                                 ResponseFormatter formatter,
                                 CommandRegistry registry,
                                 SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager;
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     * Registers: add-to-view, add-group-to-view, add-note-to-view,
     * add-view-reference-to-view, add-connection-to-view,
     * update-view-object, update-view-connection,
     * remove-from-view, clear-view, apply-positions,
     * assess-layout, auto-route-connections, auto-connect-view,
     * layout-within-group, auto-layout-and-route, arrange-groups,
     * optimize-group-order, detect-hub-elements,
     * layout-flat-view, adjust-view-spacing.
     */
    public void registerTools() {
        registry.registerTool(buildAddToViewSpec());
        registry.registerTool(buildAddGroupToViewSpec());
        registry.registerTool(buildAddNoteToViewSpec());
        registry.registerTool(buildAddViewReferenceToViewSpec());
        registry.registerTool(buildAddImageToViewSpec());
        registry.registerTool(buildAddConnectionToViewSpec());
        registry.registerTool(buildUpdateViewObjectSpec());
        registry.registerTool(buildUpdateViewConnectionSpec());
        registry.registerTool(buildRemoveFromViewSpec());
        registry.registerTool(buildClearViewSpec());
        registry.registerTool(buildApplyViewLayoutSpec());
        registry.registerTool(buildAssessLayoutSpec());
        registry.registerTool(buildAutoRouteConnectionsSpec());
        registry.registerTool(buildAutoConnectViewSpec());
        registry.registerTool(buildLayoutWithinGroupSpec());
        registry.registerTool(buildAutoLayoutAndRouteSpec());
        registry.registerTool(buildArrangeGroupsSpec());
        registry.registerTool(buildOptimizeGroupOrderSpec());
        registry.registerTool(buildDetectHubElementsSpec());
        registry.registerTool(buildLayoutFlatViewSpec());
        registry.registerTool(buildResizeElementsToFitSpec());
        registry.registerTool(buildAdjustViewSpacingSpec());
        registry.registerTool(buildApplyElementSpacingRecommendationsSpec());
        registry.registerTool(buildApplyGroupSpacingRecommendationsSpec());
        registry.registerTool(buildApplySpacingRecommendationsSpec());
    }

    // ---- add-to-view ----

    private McpServerFeatures.SyncToolSpecification buildAddToViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to place the element on");

        Map<String, Object> elementIdProp = new LinkedHashMap<>();
        elementIdProp.put("type", "string");
        elementIdProp.put("description", "ID of the model element to place on the view");

        Map<String, Object> xProp = new LinkedHashMap<>();
        xProp.put("type", "integer");
        xProp.put("description",
                "Optional X coordinate. When parentViewObjectId is provided, this is RELATIVE "
                + "to the parent's top-left corner. When no parent is specified, this is an "
                + "absolute canvas coordinate. Both x and y must be provided together, "
                + "or both omitted for auto-placement.");

        Map<String, Object> yProp = new LinkedHashMap<>();
        yProp.put("type", "integer");
        yProp.put("description",
                "Optional Y coordinate. When parentViewObjectId is provided, this is RELATIVE "
                + "to the parent's top-left corner. When no parent is specified, this is an "
                + "absolute canvas coordinate. Both x and y must be provided together, "
                + "or both omitted for auto-placement.");

        Map<String, Object> widthProp = new LinkedHashMap<>();
        widthProp.put("type", "integer");
        widthProp.put("description", "Optional width (default: 120)");

        Map<String, Object> heightProp = new LinkedHashMap<>();
        heightProp.put("type", "integer");
        heightProp.put("description", "Optional height (default: 55)");

        Map<String, Object> autoConnectProp = new LinkedHashMap<>();
        autoConnectProp.put("type", "boolean");
        autoConnectProp.put("description",
                "Auto-create visual connections for existing relationships to elements "
                + "already on the view (default: false)");

        Map<String, Object> parentVoProp = new LinkedHashMap<>();
        parentVoProp.put("type", "string");
        parentVoProp.put("description",
                "Optional view object ID of a group or element to nest this element inside. "
                + "The element becomes a visual child of the parent on the diagram. "
                + "NOTE: When a parent is specified, x/y coordinates are relative to the "
                + "parent's origin (top-left corner), not absolute canvas coordinates. "
                + "For example, x=30, y=30 places the element 30px from the left and 30px "
                + "from the top of the parent. "
                + "Get valid parent viewObjectIds from get-view-contents (groups or elements).");

        Map<String, Object> autoSizeProp = new LinkedHashMap<>();
        autoSizeProp.put("type", "boolean");
        autoSizeProp.put("description",
                "Auto-size the element to fit its label text using font metrics and "
                + "aspect-ratio-aware sizing (target 1.5:1, range [1.2:1, 2.5:1]). "
                + "Short names (<=15 chars) keep default 120x55. "
                + "Ignored if explicit width/height are provided. "
                + "Recommended for flat views and individual element placement "
                + "to prevent label truncation (default: false).");

        Map<String, Object> recedeProp = new LinkedHashMap<>();
        recedeProp.put("type", "boolean");
        recedeProp.put("description",
                "Optional. When this placement nests the element inside a parent group or element "
                + "(via parentViewObjectId) whose fill colour is unauthored (never set by a caller), "
                + "the parent's fill automatically recedes to a subtle backdrop so the nested view does "
                + "not read as a flat single-colour blob. A parent with an authored fill is never "
                + "touched. Set recede=false to suppress this for the call (default: true).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("elementId", elementIdProp);
        properties.put("x", xProp);
        properties.put("y", yProp);
        properties.put("width", widthProp);
        properties.put("height", heightProp);
        properties.put("autoSize", autoSizeProp);
        properties.put("autoConnect", autoConnectProp);
        properties.put("parentViewObjectId", parentVoProp);
        properties.put("recede", recedeProp);
        addStylingProperties(properties);
        addImageProperties(properties);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId", "elementId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("add-to-view")
                .description("[Mutation] Place an existing model element onto a view diagram. "
                        + "Creates a visual representation (diagram object) of the element on the view. "
                        + "The same element can be placed multiple times on a view — each placement "
                        + "creates a separate visual object with its own ID, position, and size. "
                        + "This is useful for deployment views where the same infrastructure element "
                        + "appears in multiple locations (e.g., across availability zones). "
                        + "Requires viewId and elementId. Optional: x, y (both or neither for "
                        + "auto-placement), width, height (default 120x55), "
                        + "autoSize (auto-size to fit label — recommended for flat views), "
                        + "autoConnect (auto-create connections to elements already on the view), "
                        + "fillColor, lineColor, fontColor (#RRGGBB hex), opacity (0-255), lineWidth "
                        + "(1-3), figureType ('rectangular' or 'tabbed' — applies to ArchiMate Grouping "
                        + "element only; silently ignored on other element classes), textAlignment ('left' / 'centre' / "
                        + "'right' — horizontal label alignment), verticalTextAlignment ('top' / "
                        + "'centre' / 'bottom' — vertical label position within the figure). "
                        + "Optional typography: fontName, fontSize, fontStyle "
                        + "('normal'/'bold'/'italic'/'bold-italic'). Optional gradient "
                        + "('none'/'top-bottom'/'bottom-top'/'left-right'/'right-left'). Optional "
                        + "deriveLineColor (boolean — when false, lineColor is used verbatim "
                        + "instead of being derived from fill). Optional outlineOpacity (0-255). "
                        + "Optional lineStyle ('solid'/'dashed'/'dotted'/'none' — view-object outline border style). "
                        + "Returns viewObject with the new viewObjectId and resolved x, y, "
                        + "width, height — the EFFECTIVE geometry the model holds after the write, "
                        + "which can differ from what you asked for because Archi auto-fits a "
                        + "container to its children — plus parentViewObjectId, the container the "
                        + "object landed in, omitted when it sits on the view itself. Read that one "
                        + "before the geometry beside it: x and y are RELATIVE to the parent's "
                        + "top-left corner whenever there is a parent, so without it they are a "
                        + "frame with no origin, and a parentViewObjectId that came back as "
                        + "something other than the container you meant is a mis-nesting you can "
                        + "still fix. The response carries autoConnections only when autoConnect is "
                        + "true (may "
                        + "be empty). When autoConnect nests this object inside an endpoint it has "
                        + "a relationship with, that connection is NOT drawn — on a view the nesting "
                        + "already expresses the relationship, and the line would leave the "
                        + "container and re-enter it, which is the self-pass-through assess-layout "
                        + "flags. The pair is reported instead, under 'skippedDueToNesting', with "
                        + "both view-object ids and the relationship, which is preserved: draw it "
                        + "yourself with add-connection-to-view if you want it anyway. The sibling "
                        + "auto-connect-view declines the same pair for the same reason. "
                        + "'skippedByCap' names the connections the 50-connection cap dropped, which "
                        + "skippedAutoConnections counts; that count appears only when over 50 were "
                        + "eligible, and both lists are omitted when empty. "
                        + "'resizedAncestors' names any container this placement GREW that you did not "
                        + "ask it to touch — reserving a corner icon band widens the icon-bearing parent, "
                        + "and the parent-fit cascade can carry that up to a group above it — each entry "
                        + "carrying the id and the rectangle it ended at, so you never have to re-query "
                        + "to learn where a container you never named ended up. Omitted when nothing grew — "
                        + "and also absent in a batch or pending approval, where nothing has executed yet "
                        + "and the entity is nested under 'preview', so do not read its absence there as "
                        + "'nothing grew'. "
                        + "Related: get-view-contents (inspect view), get-views (list views), "
                        + "auto-connect-view (batch connections), "
                        + "add-connection-to-view (individual connections), create-view (create new view), "
                        + "archimate-view-patterns resource (styling completeness reference).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAddToView)
                .build();
    }

    McpSchema.CallToolResult handleAddToView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling add-to-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String elementId = HandlerUtils.requireStringParam(args, "elementId");
            Integer x = HandlerUtils.optionalIntegerParam(args, "x");
            Integer y = HandlerUtils.optionalIntegerParam(args, "y");
            Integer width = HandlerUtils.optionalIntegerParam(args, "width");
            Integer height = HandlerUtils.optionalIntegerParam(args, "height");
            boolean autoConnect = HandlerUtils.optionalBooleanParam(args, "autoConnect");
            String parentViewObjectId = HandlerUtils.optionalStringParam(args, "parentViewObjectId");
            boolean autoSize = HandlerUtils.optionalBooleanParam(args, "autoSize");
            StylingParams styling = extractStylingParams(args);
            ImageParams imageParams = extractImageParams(args);

            if (autoSize && width == null && height == null) {
                Optional<ElementDto> elementOpt = accessor.getElementById(elementId);
                String elementName = elementOpt.map(ElementDto::name).orElse("");
                int[] computed = ElementSizer.computeAutoSize(elementName);
                width = computed[0];
                height = computed[1];
            }

            MutationResult<AddToViewResultDto> result = accessor.addToView(
                    sessionId, viewId, elementId, x, y, width, height, autoConnect,
                    parentViewObjectId, styling, imageParams);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAddToViewNextSteps(result),
                    buildAddToViewApprovalDisclosures(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling add-to-view", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * The auto-connect count, phrased for what has happened to those connections.
     *
     * <p>The scan that produces the count runs at prepare time, above every dispatch branch, and
     * the compound the queue receives contains exactly the connection commands that scan built —
     * so on a queued call the number describes the commands that will run, not a guess about the
     * model. Awaiting approval it is weaker: the stored proposal is a rebuild handle, and the
     * propose-time command is discarded in favour of a fresh one prepared at approval, which
     * re-runs the scan against whatever the model then holds.</p>
     */
    private static String addToViewAutoConnectionsStep(int count, DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> count + " connection(s) were auto-created. Use auto-connect-view later "
                    + "if more elements are added to this view";
            case QUEUED -> count + " connection(s) will be auto-created when this batch commits. "
                    + "Nothing has been drawn yet — end-batch rollback:true discards them with "
                    + "the placement.";
            case AWAITING_APPROVAL -> count + " connection(s) will be auto-created if this change "
                    + "is approved. The relationships were scanned when the change was proposed "
                    + "and are scanned again on approval, so the final count may differ.";
        };
    }

    /**
     * How to draw further connections to the object this call places.
     *
     * <p>The identifier is the one divergence in this builder that is not a matter of tense. A
     * queued call enqueues the prepared command verbatim, so the view object that lands at
     * {@code end-batch} is the one constructed here and its id is already correct. A stored
     * proposal keeps only a handle that re-prepares the mutation when the human approves it,
     * constructing a different view object — and Archi stamps a fresh random identifier in the
     * constructor. Naming the previewed id on that arm would hand the caller a value guaranteed
     * not to exist, which is why the approval wording carries no id at all.</p>
     */
    private static String addToViewPlacementIdStep(String voId, DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "Use add-connection-to-view for individual connections "
                    + "using sourceViewObjectId or targetViewObjectId '" + voId + "'";
            case QUEUED -> "Use add-connection-to-view for individual connections using "
                    + "sourceViewObjectId or targetViewObjectId '" + voId + "' — that id is "
                    + "already assigned, so those calls can be queued into this same batch.";
            case AWAITING_APPROVAL -> "Use add-connection-to-view for individual connections once "
                    + "this change is approved. The view object does not exist yet and the id it "
                    + "will be given is not the one previewed here, so read it back from the view "
                    + "after approval.";
        };
    }

    private static String addToViewCapStep(int skipped, DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "Auto-connect capped at 50 connections. " + skipped
                    + " additional relationship(s) exist — use add-connection-to-view manually.";
            case QUEUED -> "Auto-connect is capped at 50 connections. " + skipped
                    + " additional relationship(s) exist — use add-connection-to-view manually "
                    + "for those; those calls can be queued into this same batch.";
            case AWAITING_APPROVAL -> "Auto-connect is capped at 50 connections. " + skipped
                    + " additional relationship(s) existed when this change was proposed — once "
                    + "it is approved, use add-connection-to-view manually for whatever remains.";
        };
    }

    /**
     * Where to look at the placement — which, until it lands, is nowhere.
     *
     * <p>{@code get-view-contents} reads the committed view, so on a deferred arm it returns a view
     * without this element and a caller checking its own work reads the correct state as a failed
     * placement. This is the same rescope the three sibling view tools make; it belongs here for
     * the same reason and was missing.</p>
     */
    private static String addToViewVerifyStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "Use get-view-contents to verify the element placement";
            case QUEUED -> "Use get-view-contents after end-batch to verify the element placement "
                    + "— it reads the committed view, which does not hold this element yet.";
            case AWAITING_APPROVAL -> "Use get-view-contents once the human has approved the "
                    + "change to verify the element placement — it reads the committed view, "
                    + "which does not hold this element yet.";
        };
    }

    /** The conditional guidance a placement carries, composed once and phrased for the arm. */
    private List<String> addToViewDisclosures(AddToViewResultDto entity, DispatchArm arm) {
        List<String> steps = new ArrayList<>();
        if (entity == null) {
            return steps;
        }
        steps.add(addToViewVerifyStep(arm));
        boolean hadAutoConnections = entity.autoConnections() != null
                && !entity.autoConnections().isEmpty();
        if (hadAutoConnections) {
            steps.add(addToViewAutoConnectionsStep(entity.autoConnections().size(), arm));
        } else if (arm == DispatchArm.APPLIED) {
            // The recommendation to sweep the view for missing connections is the one branch here
            // that does not survive a rescope. auto-connect-view draws connections between the
            // objects a view already holds, and on a deferred arm this placement is not one of
            // them — so the call it recommends would connect nothing and the caller would have to
            // make it again afterwards anyway.
            steps.add("Use auto-connect-view to batch-create connections for all existing "
                    + "relationships between elements on this view (recommended)");
        }
        steps.add(addToViewPlacementIdStep(entity.viewObject().viewObjectId(), arm));
        if (entity.skippedAutoConnections() != null && entity.skippedAutoConnections() > 0) {
            steps.add(addToViewCapStep(entity.skippedAutoConnections(), arm));
        }
        return steps;
    }

    /** What an add-to-view response carries when the placement is waiting on a human. */
    private List<String> buildAddToViewApprovalDisclosures(
            MutationResult<AddToViewResultDto> result) {
        return addToViewDisclosures(result.entity(), DispatchArm.AWAITING_APPROVAL);
    }

    private List<String> buildAddToViewNextSteps(MutationResult<AddToViewResultDto> result) {
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>(
                    addToViewDisclosures(result.entity(), DispatchArm.QUEUED));
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        return new ArrayList<>(addToViewDisclosures(result.entity(), DispatchArm.APPLIED));
    }

    // ---- add-group-to-view ----

    private McpServerFeatures.SyncToolSpecification buildAddGroupToViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to place the group on");

        Map<String, Object> labelProp = new LinkedHashMap<>();
        labelProp.put("type", "string");
        labelProp.put("description",
                "Display label for the group. Common escape sequences (\\n, \\t, \\r, \\\\) "
                + "are automatically interpreted as their corresponding whitespace characters. "
                + "Pass an empty string to create an untitled group — use that for a container the "
                + "source material never named, rather than inventing a placeholder. The key itself "
                + "is still required: omitting it is an error.");

        Map<String, Object> xProp = new LinkedHashMap<>();
        xProp.put("type", "integer");
        xProp.put("description",
                "Optional X coordinate. When parentViewObjectId is provided, this is RELATIVE "
                + "to the parent's top-left corner. When no parent is specified, this is an "
                + "absolute canvas coordinate. Both x and y must be provided together, "
                + "or both omitted for auto-placement.");

        Map<String, Object> yProp = new LinkedHashMap<>();
        yProp.put("type", "integer");
        yProp.put("description",
                "Optional Y coordinate. When parentViewObjectId is provided, this is RELATIVE "
                + "to the parent's top-left corner. When no parent is specified, this is an "
                + "absolute canvas coordinate. Both x and y must be provided together, "
                + "or both omitted for auto-placement.");

        Map<String, Object> widthProp = new LinkedHashMap<>();
        widthProp.put("type", "integer");
        widthProp.put("description", "Optional width (default: 300)");

        Map<String, Object> heightProp = new LinkedHashMap<>();
        heightProp.put("type", "integer");
        heightProp.put("description", "Optional height (default: 200)");

        Map<String, Object> parentVoProp = new LinkedHashMap<>();
        parentVoProp.put("type", "string");
        parentVoProp.put("description",
                "Optional viewObjectId of a parent group or element to nest this group inside. "
                + "NOTE: When a parent is specified, x/y coordinates are relative to the "
                + "parent's origin (top-left corner), not absolute canvas coordinates. "
                + "For example, x=30, y=30 places the group 30px from the left and 30px "
                + "from the top of the parent. "
                + "Omit to place at the top level of the view.");

        Map<String, Object> recedeProp = new LinkedHashMap<>();
        recedeProp.put("type", "boolean");
        recedeProp.put("description",
                "Optional. When this group is nested inside a parent group or element (via "
                + "parentViewObjectId) whose fill colour is unauthored (never set by a caller), the "
                + "parent's fill automatically recedes to a subtle backdrop so the nested view does not "
                + "read as a flat single-colour blob. A parent with an authored fill is never touched. "
                + "Set recede=false to suppress this for the call (default: true).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("label", labelProp);
        properties.put("x", xProp);
        properties.put("y", yProp);
        properties.put("width", widthProp);
        properties.put("height", heightProp);
        properties.put("parentViewObjectId", parentVoProp);
        properties.put("recede", recedeProp);
        addStylingProperties(properties);
        addImageProperties(properties);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId", "label"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("add-group-to-view")
                .description("[Mutation] Add a visual grouping rectangle to a view diagram. "
                        + "Groups are pure visual containers — they do not represent model elements. "
                        + "Use groups to visually organize elements on a diagram. After creating a group, "
                        + "use add-to-view with parentViewObjectId to nest elements inside it. "
                        + "Requires viewId and label; label may be an empty string, which creates an "
                        + "untitled group (Archi stores and renders one correctly). Prefer that over a "
                        + "placeholder when the source has no name for the container. "
                        + "Optional: x, y (both or neither for auto-placement), "
                        + "width, height (default 300x200), "
                        + "fillColor, lineColor, fontColor (#RRGGBB hex), opacity (0-255), lineWidth "
                        + "(1-3), figureType ('rectangular' = flat, or 'tabbed' = folder-tab — Archi default), "
                        + "textAlignment ('left' / 'centre' / 'right' — horizontal label alignment), "
                        + "verticalTextAlignment ('top' / 'centre' / 'bottom' — vertical label position within "
                        + "the figure). "
                        + "Optional typography: fontName, fontSize, fontStyle. "
                        + "Optional gradient ('none'/'top-bottom'/'bottom-top'/'left-right'/'right-left'). "
                        + "Optional deriveLineColor (boolean), outlineOpacity (0-255), "
                        + "lineStyle ('solid'/'dashed'/'dotted'/'none' — view-object outline style). "
                        + "NOTE: Groups constrain element positioning and reduce connection "
                        + "routing quality. Prefer groups on structure/overview views only. "
                        + "For views needing clean routed connections, use flat layout without groups. "
                        + "Returns viewObjectId — pass as parentViewObjectId to nest elements "
                        + "— plus label, resolved bounds, and parentViewObjectId: the container "
                        + "this group landed in, omitted when it sits on the view itself. That is "
                        + "the group's own parent, and it is read from the resolved container "
                        + "rather than echoed from your request, so a nested group says which "
                        + "container it is in and its bounds have a named origin. The group's "
                        + "children are still never echoed; use get-view-contents. "
                        + "Related: add-to-view (place elements inside group), "
                        + "get-view-contents (inspect view groups), "
                        + "update-view-object (resize/relabel group), "
                        + "archimate-view-patterns resource (styling completeness reference).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAddGroupToView)
                .build();
    }

    McpSchema.CallToolResult handleAddGroupToView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling add-group-to-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            // allow-empty variant — "" creates an untitled group, which Archi holds and renders;
            // the key itself is still required, so an omitted label stays an error.
            String label = HandlerUtils.requireStringParamAllowEmpty(args, "label");
            Integer x = HandlerUtils.optionalIntegerParam(args, "x");
            Integer y = HandlerUtils.optionalIntegerParam(args, "y");
            Integer width = HandlerUtils.optionalIntegerParam(args, "width");
            Integer height = HandlerUtils.optionalIntegerParam(args, "height");
            String parentViewObjectId = HandlerUtils.optionalStringParam(args, "parentViewObjectId");
            StylingParams styling = extractStylingParams(args);
            ImageParams imageParams = extractImageParams(args);

            MutationResult<ViewGroupDto> result = accessor.addGroupToView(
                    sessionId, viewId, label, x, y, width, height, parentViewObjectId, styling,
                    imageParams);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAddGroupNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling add-group-to-view", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildAddGroupNextSteps(MutationResult<ViewGroupDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        String voId = result.entity().viewObjectId();
        return List.of(
                "Group created with viewObjectId '" + voId + "'.",
                "Use add-to-view with parentViewObjectId='" + voId
                        + "' to nest elements inside this group.",
                "Use layout-within-group to auto-position elements inside this group "
                        + "(recommended over manual coordinate computation).",
                "Use update-view-object with viewObjectId='" + voId
                        + "' to resize or relabel the group.");
    }

    // ---- add-note-to-view ----

    private McpServerFeatures.SyncToolSpecification buildAddNoteToViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to place the note on");

        Map<String, Object> contentProp = new LinkedHashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description",
                "Text content of the note. Empty string is allowed for placeholder notes. "
                + "Common escape sequences (\\n, \\t, \\r, \\\\) are automatically interpreted "
                + "as their corresponding whitespace characters.");

        Map<String, Object> xProp = new LinkedHashMap<>();
        xProp.put("type", "integer");
        xProp.put("description",
                "Optional X coordinate. When parentViewObjectId is provided, this is RELATIVE "
                + "to the parent's top-left corner. When no parent is specified, this is an "
                + "absolute canvas coordinate. Both x and y must be provided together, "
                + "or both omitted for auto-placement.");

        Map<String, Object> yProp = new LinkedHashMap<>();
        yProp.put("type", "integer");
        yProp.put("description",
                "Optional Y coordinate. When parentViewObjectId is provided, this is RELATIVE "
                + "to the parent's top-left corner. When no parent is specified, this is an "
                + "absolute canvas coordinate. Both x and y must be provided together, "
                + "or both omitted for auto-placement.");

        Map<String, Object> widthProp = new LinkedHashMap<>();
        widthProp.put("type", "integer");
        widthProp.put("description", "Optional width (default: 185)");

        Map<String, Object> heightProp = new LinkedHashMap<>();
        heightProp.put("type", "integer");
        heightProp.put("description",
                "Optional height. Omit it and the server auto-fits the height to the note's "
                + "wrapped text (real glyph measurement), with 80 as the floor and 600 as the cap "
                + "— 80 is what short content resolves to, not a fixed default. Supply a height "
                + "only to pin a fixed size; too small a value clips the text.");

        Map<String, Object> positionProp = new LinkedHashMap<>();
        positionProp.put("type", "string");
        positionProp.put("enum", List.of("above-content", "below-content"));
        positionProp.put("description",
                "Position the note relative to the view's content bounding box. "
                + "'above-content' places the note above all diagram content — recommended "
                + "for title notes after layout is complete. 'below-content' places below. "
                + "When set, x/y are computed automatically and should be omitted. "
                + "Cannot be used with parentViewObjectId.");

        Map<String, Object> gapProp = new LinkedHashMap<>();
        gapProp.put("type", "integer");
        gapProp.put("description",
                "Gap in pixels between note edge and content bounds (default: 10). "
                + "Only used when 'position' is set.");

        Map<String, Object> parentVoProp = new LinkedHashMap<>();
        parentVoProp.put("type", "string");
        parentVoProp.put("description",
                "Optional viewObjectId of a parent group or element to nest this note inside. "
                + "NOTE: When a parent is specified, x/y coordinates are relative to the "
                + "parent's origin (top-left corner), not absolute canvas coordinates. "
                + "For example, x=30, y=30 places the note 30px from the left and 30px "
                + "from the top of the parent. "
                + "Omit to place at the top level of the view. "
                + "Cannot be used with 'position'.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("content", contentProp);
        properties.put("position", positionProp);
        properties.put("gap", gapProp);
        properties.put("x", xProp);
        properties.put("y", yProp);
        properties.put("width", widthProp);
        properties.put("height", heightProp);
        properties.put("parentViewObjectId", parentVoProp);
        addStylingProperties(properties);
        addImageProperties(properties);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId", "content"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("add-note-to-view")
                .description("[Mutation] Add a text note to a view diagram. "
                        + "Notes are pure visual annotations — they do not represent model elements. "
                        + "Use notes to add explanatory text, comments, or documentation directly "
                        + "on a diagram. "
                        + "Requires viewId and content; content may be an empty string, which creates "
                        + "an empty placeholder note. Use position='above-content' for title notes "
                        + "(recommended — automatically places above diagram content after layout). "
                        + "Optional: x, y (both or neither for auto-placement), width "
                        + "(default 185), height (omit it and the height auto-fits to the wrapped "
                        + "text, floor 80, cap 600; supply one only to pin a fixed size), "
                        + "fillColor, lineColor, fontColor (#RRGGBB hex), opacity (0-255), lineWidth "
                        + "(1-3), textAlignment ('left' / 'centre' / 'right' — horizontal label alignment), "
                        + "verticalTextAlignment ('top' / 'centre' / 'bottom' — vertical label position within "
                        + "the note). NOTE: figureType is silently ignored on notes (notes have their own "
                        + "borderType vocabulary — see below). "
                        + "Optional typography: fontName, fontSize, fontStyle "
                        + "('normal'/'bold'/'italic'/'bold-italic'). Optional borderType "
                        + "('dogear' = Archi default folded-corner / 'rectangle' / 'none') — applies "
                        + "to notes specifically. Optional gradient, deriveLineColor (boolean), "
                        + "outlineOpacity (0-255), lineStyle ('solid'/'dashed'/'dotted'/'none'). "
                        + "Returns viewObjectId, content, resolved bounds, and "
                        + "parentViewObjectId only when nested. A note field appears only as "
                        + "a placement warning (position "
                        + "overrode x/y, or empty view fell back to 10,10). "
                        + "ORDERING: place a note after auto-route-connections, not before. A note is deliberately "
                        + "excluded from the router's obstacle set, so a note already sitting in a corridor is "
                        + "routed straight through rather than avoided. Place it once the routes exist, then re-run "
                        + "assess-layout: moving a note changes the routes around it, so the count after the move "
                        + "is not predictable from the count before it. "
                        + "That ordering is necessary and NOT sufficient — after routing is exactly when there "
                        + "are corridors to land in. This call measures the resolved rectangle against the routes "
                        + "already on the view and returns an ANNOTATION_PLACED_IN_ROUTED_CORRIDOR structured "
                        + "warning naming every connection it crosses, on the immediate, batched and approval arms "
                        + "alike. A note crossing lands in the informational connectionThroughNoteCount, which caps "
                        + "the view at good. Move the note with update-view-object rather than re-routing. "
                        + "Related: get-view-contents (inspect view notes), "
                        + "update-view-object (edit note text or resize), "
                        + "archimate-view-patterns resource (styling completeness reference).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAddNoteToView)
                .build();
    }

    McpSchema.CallToolResult handleAddNoteToView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling add-note-to-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            // allow-empty variant — "" creates an empty placeholder note, which this tool's own
            // content-parameter description has always promised; the key is still required.
            String content = HandlerUtils.requireStringParamAllowEmpty(args, "content");
            String position = HandlerUtils.optionalStringParam(args, "position");
            Integer gap = HandlerUtils.optionalIntegerParam(args, "gap");
            Integer x = HandlerUtils.optionalIntegerParam(args, "x");
            Integer y = HandlerUtils.optionalIntegerParam(args, "y");
            Integer width = HandlerUtils.optionalIntegerParam(args, "width");
            Integer height = HandlerUtils.optionalIntegerParam(args, "height");
            String parentViewObjectId = HandlerUtils.optionalStringParam(args, "parentViewObjectId");
            StylingParams styling = extractStylingParams(args);
            ImageParams imageParams = extractImageParams(args);

            // Validate: position and parentViewObjectId are mutually exclusive
            if (position != null && parentViewObjectId != null) {
                return HandlerUtils.buildModelAccessError(formatter,
                        new ModelAccessException(
                                "Cannot use 'position' with 'parentViewObjectId'. "
                                + "Position-based placement only works for top-level notes.",
                                ErrorCode.INVALID_PARAMETER));
            }

            MutationResult<ViewNoteDto> result = accessor.addNoteToView(
                    sessionId, viewId, content, position, gap, x, y,
                    width, height, parentViewObjectId, styling, imageParams);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAddNoteNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling add-note-to-view", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildAddNoteNextSteps(MutationResult<ViewNoteDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        String voId = result.entity().viewObjectId();
        return List.of(
                "Note created with viewObjectId '" + voId + "'.",
                "Use update-view-object with viewObjectId='" + voId
                        + "' to edit the note text or resize.",
                "Use remove-from-view to remove the note from the view.");
    }

    // ---- add-view-reference-to-view ----

    private McpServerFeatures.SyncToolSpecification buildAddViewReferenceToViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description",
                "ID of the TARGET view to place the view-reference on");

        Map<String, Object> refViewIdProp = new LinkedHashMap<>();
        refViewIdProp.put("type", "string");
        refViewIdProp.put("description",
                "ID of the SOURCE view being referenced (embedded as a thumbnail). "
                + "Must be an existing ArchiMate view in the same model. "
                + "Archi reads the referenced view's name dynamically at render time, "
                + "so renaming the referenced view via update-view auto-updates every "
                + "embedding visual without a separate mutation.");

        Map<String, Object> xProp = new LinkedHashMap<>();
        xProp.put("type", "integer");
        xProp.put("description",
                "Optional X coordinate. When parentViewObjectId is provided, this is RELATIVE "
                + "to the parent's top-left corner. When no parent is specified, this is an "
                + "absolute canvas coordinate. Both x and y must be provided together, "
                + "or both omitted for auto-placement.");

        Map<String, Object> yProp = new LinkedHashMap<>();
        yProp.put("type", "integer");
        yProp.put("description",
                "Optional Y coordinate. When parentViewObjectId is provided, this is RELATIVE "
                + "to the parent's top-left corner. When no parent is specified, this is an "
                + "absolute canvas coordinate. Both x and y must be provided together, "
                + "or both omitted for auto-placement.");

        Map<String, Object> widthProp = new LinkedHashMap<>();
        widthProp.put("type", "integer");
        widthProp.put("description", "Optional width (default: 185)");

        Map<String, Object> heightProp = new LinkedHashMap<>();
        heightProp.put("type", "integer");
        heightProp.put("description", "Optional height (default: 80)");

        Map<String, Object> parentVoProp = new LinkedHashMap<>();
        parentVoProp.put("type", "string");
        parentVoProp.put("description",
                "Optional viewObjectId of a parent group or element to nest this view-reference "
                + "inside. NOTE: When a parent is specified, x/y coordinates are relative to the "
                + "parent's origin (top-left corner), not absolute canvas coordinates. "
                + "Omit to place at the top level of the view.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("referencedViewId", refViewIdProp);
        properties.put("x", xProp);
        properties.put("y", yProp);
        properties.put("width", widthProp);
        properties.put("height", heightProp);
        properties.put("parentViewObjectId", parentVoProp);
        addStylingProperties(properties);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId", "referencedViewId"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("add-view-reference-to-view")
                .description("[Mutation] Add a view-reference visual object to a view that "
                        + "embeds another ArchiMate view as a clickable thumbnail — the "
                        + "agent-driven equivalent of Archi GUI's drag-view-onto-view behaviour. "
                        + "Use to compose landscape views that embed each layer view as a "
                        + "thumbnail, build index views that link every viewpoint, or assemble "
                        + "cross-cutting documentation views that reference detail views. "
                        + "Requires viewId (TARGET) and referencedViewId (SOURCE) — both must be "
                        + "IDs of existing ArchiMate views in the same model. "
                        + "Optional: x, y (both or neither for auto-placement), width, height "
                        + "(default 185x80), parentViewObjectId (nest inside a group or element; "
                        + "x/y become parent-relative). "
                        + "Same visual styling surface as add-note-to-view: fillColor, lineColor, "
                        + "fontColor (#RRGGBB hex), opacity (0-255), lineWidth (1-3), "
                        + "fontName, fontSize, fontStyle ('normal'/'bold'/'italic'/'bold-italic'), "
                        + "gradient, deriveLineColor (boolean), outlineOpacity (0-255), lineStyle "
                        + "('solid'/'dashed'/'dotted'/'none'), textAlignment ('left'/'centre'/"
                        + "'right'), verticalTextAlignment ('top'/'centre'/'bottom'). "
                        + "The referenced view's name is NOT stored on the visual (Archi reads it "
                        + "dynamically at render time — renaming the referenced view auto-updates "
                        + "every embedding visual). "
                        + "Complement to add-note-to-view: notes are pure annotation, view-references "
                        + "are navigational links to other views. "
                        + "Returns viewObjectId, referencedViewId, the resolved x, y, width, "
                        + "height, and parentViewObjectId only when nested. "
                        + "ORDERING: place a view-reference after auto-route-connections, not before. A "
                        + "view-reference left in a corridor is worse than a note in the same place: a route "
                        + "crossing a note is disclosed as an accepted pass-through, while a route crossing a "
                        + "view-reference is counted as a genuine rated pass-through against the view. Place it "
                        + "once the routes exist, then re-run assess-layout. "
                        + "That ordering is necessary and NOT sufficient — after routing is exactly when there "
                        + "are corridors to land in. This call measures the resolved rectangle against the routes "
                        + "already on the view and returns an ANNOTATION_PLACED_IN_ROUTED_CORRIDOR structured "
                        + "warning naming every connection it crosses, on the immediate, batched and approval arms "
                        + "alike. Move it with update-view-object rather than re-routing around it. "
                        + "Related: get-views (find view IDs), update-view-object (resize or "
                        + "restyle an existing view-reference — it's an IDiagramModelObject), "
                        + "remove-from-view (delete the placement without affecting the referenced "
                        + "view), delete-view (delete the referenced view itself — cascades "
                        + "visual placeholders).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAddViewReferenceToView)
                .build();
    }

    McpSchema.CallToolResult handleAddViewReferenceToView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling add-view-reference-to-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String referencedViewId = HandlerUtils.requireStringParam(args, "referencedViewId");
            Integer x = HandlerUtils.optionalIntegerParam(args, "x");
            Integer y = HandlerUtils.optionalIntegerParam(args, "y");
            Integer width = HandlerUtils.optionalIntegerParam(args, "width");
            Integer height = HandlerUtils.optionalIntegerParam(args, "height");
            String parentViewObjectId = HandlerUtils.optionalStringParam(args, "parentViewObjectId");
            StylingParams styling = extractStylingParams(args);

            MutationResult<EmbeddedViewDto> result = accessor.addViewReferenceToView(
                    sessionId, viewId, referencedViewId, x, y, width, height,
                    parentViewObjectId, styling);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAddViewReferenceNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling add-view-reference-to-view", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildAddViewReferenceNextSteps(MutationResult<EmbeddedViewDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        String voId = result.entity().viewObjectId();
        return List.of(
                "Use get-view-contents to see the full target view including the new view-reference (viewObjectId='"
                        + voId + "').",
                "Use update-view-object to resize or restyle the view-reference"
                        + " (it's an IDiagramModelObject).",
                "Use add-view-reference-to-view again to embed other views,"
                        + " or add-to-view to add element placements.");
    }

    // ---- add-image-to-view ----

    private McpServerFeatures.SyncToolSpecification buildAddImageToViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description",
                "ID of the TARGET view to place the image visual on");

        Map<String, Object> imagePathProp = new LinkedHashMap<>();
        imagePathProp.put("type", "string");
        imagePathProp.put("description",
                "Archive imagePath returned by add-image-to-model, or one of the paths "
                + "returned by list-model-images. The value is opaque and server-generated — "
                + "pass it back exactly as received; never construct or parse one. "
                + "MUST resolve to existing bytes in the model archive — typo'd paths "
                + "are rejected with IMAGE_NOT_FOUND. To import a new image first, "
                + "call add-image-to-model with filePath/url/imageData.");

        Map<String, Object> xProp = new LinkedHashMap<>();
        xProp.put("type", "integer");
        xProp.put("description",
                "Optional X coordinate. When parentViewObjectId is provided, this is RELATIVE "
                + "to the parent's top-left corner. When no parent is specified, this is an "
                + "absolute canvas coordinate. Both x and y must be provided together, "
                + "or both omitted for auto-placement.");

        Map<String, Object> yProp = new LinkedHashMap<>();
        yProp.put("type", "integer");
        yProp.put("description",
                "Optional Y coordinate. When parentViewObjectId is provided, this is RELATIVE "
                + "to the parent's top-left corner. When no parent is specified, this is an "
                + "absolute canvas coordinate. Both x and y must be provided together, "
                + "or both omitted for auto-placement.");

        Map<String, Object> widthProp = new LinkedHashMap<>();
        widthProp.put("type", "integer");
        widthProp.put("description", "Optional width. Default: natural image dimensions "
                + "read from archive bytes; fallback 200 if archive read fails.");

        Map<String, Object> heightProp = new LinkedHashMap<>();
        heightProp.put("type", "integer");
        heightProp.put("description", "Optional height. Default: natural image dimensions "
                + "read from archive bytes; fallback 200 if archive read fails.");

        Map<String, Object> parentVoProp = new LinkedHashMap<>();
        parentVoProp.put("type", "string");
        parentVoProp.put("description",
                "Optional viewObjectId of a parent group or element to nest this image "
                + "inside. NOTE: When a parent is specified, x/y coordinates are relative "
                + "to the parent's origin (top-left corner), not absolute canvas coordinates. "
                + "Omit to place at the top level of the view.");

        // IDiagramModelImage extends
        // IBorderObject + IDocumentable — surface their fields as image-specific
        // schema properties (NOT in addStylingProperties because those are
        // generic IDiagramModelObject fields).
        Map<String, Object> borderColorProp = new LinkedHashMap<>();
        borderColorProp.put("type", "string");
        borderColorProp.put("description",
                "Optional border colour in #RRGGBB hex format (specific to "
                + "IDiagramModelImage via IBorderObject; distinct from the "
                + "generic lineColor field). Empty string clears to default. "
                + "Omit to leave unset (Archi default).");

        Map<String, Object> documentationProp = new LinkedHashMap<>();
        documentationProp.put("type", "string");
        documentationProp.put("description",
                "Optional free-text documentation attached to the image visual "
                + "(IDocumentable). Appears in Archi's Properties tab when the "
                + "image is selected. Omit to leave empty.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("imagePath", imagePathProp);
        properties.put("x", xProp);
        properties.put("y", yProp);
        properties.put("width", widthProp);
        properties.put("height", heightProp);
        properties.put("parentViewObjectId", parentVoProp);
        properties.put("borderColor", borderColorProp);
        properties.put("documentation", documentationProp);
        addStylingProperties(properties);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId", "imagePath"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("add-image-to-view")
                .description("[Mutation] Add a standalone image visual to a view — Archi renders "
                        + "the image as a first-class diagram node (sibling to notes, groups, "
                        + "view-references). Use to embed logos, screenshots, architecture "
                        + "sketches, or reference imagery on a landscape view. "
                        + "Requires viewId (target) and imagePath (returned by add-image-to-model "
                        + "or list-model-images). "
                        + "Optional: x, y (both or neither for auto-placement), width, height "
                        + "(default: natural image dimensions read from archive bytes; falls back "
                        + "to 200x200), parentViewObjectId (nest inside a group or element; "
                        + "x/y become parent-relative). "
                        + "Same visual styling surface as add-note-to-view (fillColor, lineColor, "
                        + "fontColor, opacity, lineWidth, font fields, gradient, deriveLineColor, "
                        + "outlineOpacity, lineStyle, textAlignment, verticalTextAlignment) — "
                        + "some font/gradient fields are silently ignored by Archi's image "
                        + "renderer at paint time, though the EMF state is preserved. "
                        + "Different from update-view-object setting an imagePath on an element: "
                        + "this tool creates a standalone image visual (IDiagramModelImage), "
                        + "not an icon overlay on an existing element (IIconic.imagePath). "
                        + "Returns viewObjectId, imagePath, resolved bounds (natural image "
                        + "size if omitted), and parentViewObjectId only when nested. Only "
                        + "borderColor and documentation are echoed back. "
                        + "ORDERING: place a standalone image after auto-route-connections, not before. An image "
                        + "left in a corridor is worse than a note in the same place: a route crossing a note is "
                        + "disclosed as an accepted pass-through, while a route crossing an image is counted as a "
                        + "genuine rated pass-through against the view. Place it once the routes exist, then re-run "
                        + "assess-layout. "
                        + "That ordering is necessary and NOT sufficient — after routing is exactly when there "
                        + "are corridors to land in. This call measures the resolved rectangle against the routes "
                        + "already on the view and returns an ANNOTATION_PLACED_IN_ROUTED_CORRIDOR structured "
                        + "warning naming every connection it crosses, on the immediate, batched and approval arms "
                        + "alike. Move it with update-view-object rather than re-routing around it. "
                        + "Related: add-image-to-model (import image bytes first), "
                        + "list-model-images (browse stored images), add-view-reference-to-view "
                        + "(sibling — embed another view as thumbnail), update-view-object "
                        + "(resize or restyle an existing image visual — it's an "
                        + "IDiagramModelObject), remove-from-view (delete the placement).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAddImageToView)
                .build();
    }

    McpSchema.CallToolResult handleAddImageToView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling add-image-to-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String imagePath = HandlerUtils.requireStringParam(args, "imagePath");
            Integer x = HandlerUtils.optionalIntegerParam(args, "x");
            Integer y = HandlerUtils.optionalIntegerParam(args, "y");
            Integer width = HandlerUtils.optionalIntegerParam(args, "width");
            Integer height = HandlerUtils.optionalIntegerParam(args, "height");
            String parentViewObjectId = HandlerUtils.optionalStringParam(args, "parentViewObjectId");
            StylingParams styling = extractStylingParams(args);
            String borderColor = HandlerUtils.optionalStringParam(args, "borderColor");
            String documentation = HandlerUtils.optionalStringParam(args, "documentation");

            MutationResult<DiagramImageDto> result = accessor.addImageToView(
                    sessionId, viewId, imagePath, x, y, width, height,
                    parentViewObjectId, styling, borderColor, documentation);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAddImageToViewNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling add-image-to-view", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildAddImageToViewNextSteps(MutationResult<DiagramImageDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        String voId = result.entity().viewObjectId();
        return List.of(
                "Use get-view-contents to see the image visual in the target view "
                        + "(viewObjectId='" + voId + "').",
                "Use update-view-object to resize or restyle the image visual "
                        + "(it's an IDiagramModelObject).",
                "Use add-image-to-view again to add more images, or remove-from-view "
                        + "to delete this image visual.");
    }

    // ---- add-connection-to-view ----

    private McpServerFeatures.SyncToolSpecification buildAddConnectionToViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view containing the view objects");

        Map<String, Object> relIdProp = new LinkedHashMap<>();
        relIdProp.put("type", "string");
        relIdProp.put("description",
                "ID of the model relationship to visualize as a connection");

        Map<String, Object> sourceVoProp = new LinkedHashMap<>();
        sourceVoProp.put("type", "string");
        sourceVoProp.put("description",
                "View object ID of the source element (from get-view-contents visualMetadata viewObjectId)");

        Map<String, Object> targetVoProp = new LinkedHashMap<>();
        targetVoProp.put("type", "string");
        targetVoProp.put("description",
                "View object ID of the target element (from get-view-contents visualMetadata viewObjectId)");

        Map<String, Object> bpItemProps = new LinkedHashMap<>();
        bpItemProps.put("startX", Map.of("type", "integer"));
        bpItemProps.put("startY", Map.of("type", "integer"));
        bpItemProps.put("endX", Map.of("type", "integer"));
        bpItemProps.put("endY", Map.of("type", "integer"));

        Map<String, Object> bpItems = new LinkedHashMap<>();
        bpItems.put("type", "object");
        bpItems.put("properties", bpItemProps);
        bpItems.put("required", List.of("startX", "startY", "endX", "endY"));

        Map<String, Object> bendpointsProp = new LinkedHashMap<>();
        bendpointsProp.put("type", "array");
        bendpointsProp.put("description",
                "Optional routing bendpoints in relative format. "
                + "Each bendpoint has startX/startY (offset from source element center) "
                + "and endX/endY (offset from target element center). "
                + "Mutually exclusive with absoluteBendpoints. Omit for straight line.");
        bendpointsProp.put("items", bpItems);

        Map<String, Object> absBpItemProps = new LinkedHashMap<>();
        absBpItemProps.put("x", Map.of("type", "integer"));
        absBpItemProps.put("y", Map.of("type", "integer"));

        Map<String, Object> absBpItems = new LinkedHashMap<>();
        absBpItems.put("type", "object");
        absBpItems.put("properties", absBpItemProps);
        absBpItems.put("required", List.of("x", "y"));

        Map<String, Object> absoluteBpProp = new LinkedHashMap<>();
        absoluteBpProp.put("type", "array");
        absoluteBpProp.put("description",
                "Optional routing bendpoints in absolute canvas coordinates. "
                + "Each bendpoint has x/y (absolute position). The server converts to "
                + "Archi's relative format automatically. Preferred over relative "
                + "bendpoints for ease of use. Mutually exclusive with bendpoints. "
                + "Omit for straight line.");
        absoluteBpProp.put("items", absBpItems);

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> showLabelProp = new LinkedHashMap<>();
        showLabelProp.put("type", "boolean");
        showLabelProp.put("description",
                "Set to false to suppress the relationship name label on this connection. "
                + "Default is true (label shown). Use to reduce visual clutter on dense diagrams.");

        Map<String, Object> labelPositionProp = new LinkedHashMap<>();
        labelPositionProp.put("type", "string");
        labelPositionProp.put("enum", List.of("source", "middle", "target"));
        labelPositionProp.put("description",
                "Position the relationship name label along the connection path. "
                + "'source' = near source (15%), 'middle' = center (50%), 'target' = near target (85%). "
                + "Default is middle. Use to reduce label overlaps on dense diagrams.");

        properties.put("viewId", viewIdProp);
        properties.put("relationshipId", relIdProp);
        properties.put("sourceViewObjectId", sourceVoProp);
        properties.put("targetViewObjectId", targetVoProp);
        properties.put("bendpoints", bendpointsProp);
        properties.put("absoluteBendpoints", absoluteBpProp);
        addConnectionStylingProperties(properties);
        properties.put("showLabel", showLabelProp);
        properties.put("labelPosition", labelPositionProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties,
                List.of("viewId", "relationshipId", "sourceViewObjectId", "targetViewObjectId"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("add-connection-to-view")
                .description("[Mutation] Add a visual connection between two view objects. "
                        + "Links an existing model relationship as a visible arrow/line on the diagram. "
                        + "Requires viewId, relationshipId, sourceViewObjectId, targetViewObjectId. "
                        + "Optional: bendpoints (relative offsets from source/target element centers) "
                        + "OR absoluteBendpoints (absolute canvas coordinates, server converts automatically). "
                        + "Omit both for a straight line. Archi renders connection endpoints at element "
                        + "perimeter intersections automatically (ChopboxAnchor) — you do not need to "
                        + "specify where lines attach to element edges. "
                        + "Optional styling: lineColor, fontColor (#RRGGBB hex or empty to clear), "
                        + "lineWidth (1-3). Optional: showLabel (false to suppress relationship name label). "
                        + "Optional: labelPosition ('source'/'middle'/'target') to control label placement. "
                        + "Optional typography: fontName, fontSize, fontStyle "
                        + "('normal'/'bold'/'italic'/'bold-italic'). "
                        + "NOTE: lineStyle is a view-object property only; connection line style is "
                        + "determined by the ArchiMate relationship type and cannot be overridden "
                        + "on the view. Passing lineStyle here is REJECTED (INVALID_PARAMETER) "
                        + "rather than ignored. To distinguish connections visually — synchronous "
                        + "versus asynchronous flows, for example — use lineColor and lineWidth. "
                        + "The relationship's elements must match the view objects' elements "
                        + "(either orientation). "
                        + "Returns viewConnectionId, relationshipId, relationshipType and "
                        + "both endpoint view-object IDs. "
                        + "Related: add-to-view (place elements first), "
                        + "get-view-contents (find view object IDs in visualMetadata), "
                        + "get-relationships (find relationship IDs), "
                        + "archimate-view-patterns resource (styling completeness reference).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAddConnectionToView)
                .build();
    }

    McpSchema.CallToolResult handleAddConnectionToView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling add-connection-to-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String relationshipId = HandlerUtils.requireStringParam(args, "relationshipId");
            String sourceVoId = HandlerUtils.requireStringParam(args, "sourceViewObjectId");
            String targetVoId = HandlerUtils.requireStringParam(args, "targetViewObjectId");
            List<BendpointDto> bendpoints = extractBendpoints(args);
            List<AbsoluteBendpointDto> absoluteBendpoints = extractAbsoluteBendpoints(args);
            validateBendpointFormats(bendpoints, absoluteBendpoints);
            StylingParams styling = extractStylingParams(args);
            Boolean showLabel = (args.get("showLabel") instanceof Boolean b) ? b : null;
            Integer textPosition = parseLabelPosition(args);

            MutationResult<ViewConnectionDto> result = accessor.addConnectionToView(
                    sessionId, viewId, relationshipId, sourceVoId, targetVoId,
                    bendpoints, absoluteBendpoints, styling, showLabel, textPosition);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAddConnectionNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling add-connection-to-view", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildAddConnectionNextSteps(MutationResult<ViewConnectionDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Use get-view-contents to verify the connection placement",
                "Use add-connection-to-view to add more connections");
    }

    /**
     * Extracts an optional bendpoints array from the arguments map.
     * Each bendpoint must have startX, startY, endX, endY integer fields.
     *
     * @throws ModelAccessException with INVALID_PARAMETER if a bendpoint is missing required fields
     */
    private List<BendpointDto> extractBendpoints(Map<String, Object> args) {
        if (args == null) return null;
        Object value = args.get("bendpoints");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        List<BendpointDto> result = new ArrayList<>();
        String fieldsHint = "startX, startY, endX, endY";
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map<?, ?> map) {
                int startX = requireBendpointInt(map, "startX", i, fieldsHint);
                int startY = requireBendpointInt(map, "startY", i, fieldsHint);
                int endX = requireBendpointInt(map, "endX", i, fieldsHint);
                int endY = requireBendpointInt(map, "endY", i, fieldsHint);
                result.add(new BendpointDto(startX, startY, endX, endY));
            } else {
                throw new ModelAccessException(
                        "Bendpoint[" + i + "] must be an object with startX, startY, endX, endY",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Each bendpoint must be an object with integer fields: " + fieldsHint,
                        null);
            }
        }
        return result;
    }

    /**
     * Extracts an optional absoluteBendpoints array from the arguments map.
     * Each absolute bendpoint must have x, y integer fields.
     *
     * @throws ModelAccessException with INVALID_PARAMETER if an item is missing required fields
     */
    private List<AbsoluteBendpointDto> extractAbsoluteBendpoints(Map<String, Object> args) {
        if (args == null) return null;
        Object value = args.get("absoluteBendpoints");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        List<AbsoluteBendpointDto> result = new ArrayList<>();
        String fieldsHint = "x, y";
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map<?, ?> map) {
                int x = requireBendpointInt(map, "x", i, fieldsHint);
                int y = requireBendpointInt(map, "y", i, fieldsHint);
                result.add(new AbsoluteBendpointDto(x, y));
            } else {
                throw new ModelAccessException(
                        "absoluteBendpoints[" + i + "] must be an object with x, y",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Each absolute bendpoint must be an object with integer fields: " + fieldsHint,
                        null);
            }
        }
        return result;
    }

    /**
     * Validates that bendpoints and absoluteBendpoints are mutually exclusive.
     *
     * @throws ModelAccessException with INVALID_PARAMETER if both are provided
     */
    private void validateBendpointFormats(List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints) {
        if (bendpoints != null && !bendpoints.isEmpty()
                && absoluteBendpoints != null && !absoluteBendpoints.isEmpty()) {
            throw new ModelAccessException(
                    "Cannot provide both 'bendpoints' and 'absoluteBendpoints'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use either relative bendpoints (startX/startY/endX/endY) or absolute "
                            + "bendpoints ({x, y}), not both",
                    null);
        }
    }

    private int requireBendpointInt(Map<?, ?> map, String field, int index, String fieldsHint) {
        Object value = map.get(field);
        if (value instanceof Number n) {
            return n.intValue();
        }
        throw new ModelAccessException(
                "Bendpoint[" + index + "] is missing required integer field '" + field + "'",
                ErrorCode.INVALID_PARAMETER,
                null,
                "Each bendpoint must have integer fields: " + fieldsHint,
                null);
    }

    // ---- update-view-object ----

    private McpServerFeatures.SyncToolSpecification buildUpdateViewObjectSpec() {
        Map<String, Object> viewObjectIdProp = new LinkedHashMap<>();
        viewObjectIdProp.put("type", "string");
        viewObjectIdProp.put("description",
                "ID of the view object to update (from get-view-contents visualMetadata viewObjectId)");

        Map<String, Object> xProp = new LinkedHashMap<>();
        xProp.put("type", "integer");
        xProp.put("description",
                "New X coordinate (optional, keeps current if omitted). "
                + "For objects nested inside a group, this is RELATIVE to the parent group's "
                + "top-left corner, not an absolute canvas coordinate.");

        Map<String, Object> yProp = new LinkedHashMap<>();
        yProp.put("type", "integer");
        yProp.put("description",
                "New Y coordinate (optional, keeps current if omitted). "
                + "For objects nested inside a group, this is RELATIVE to the parent group's "
                + "top-left corner, not an absolute canvas coordinate.");

        Map<String, Object> widthProp = new LinkedHashMap<>();
        widthProp.put("type", "integer");
        widthProp.put("description", "New width (optional, keeps current if omitted)");

        Map<String, Object> heightProp = new LinkedHashMap<>();
        heightProp.put("type", "integer");
        heightProp.put("description", "New height (optional, keeps current if omitted). "
                + "EXCEPTION for a note: omitting height in a request that also changes the note's "
                + "text or width re-fits the height to the wrapped content (the same auto-fit "
                + "add-note-to-view applies), so it does not keep its current value. Supply height "
                + "to pin a fixed size; a request that only moves the note never re-fits it.");

        Map<String, Object> textProp = new LinkedHashMap<>();
        textProp.put("type", "string");
        textProp.put("description",
                "New text for groups (label) or notes (content). "
                + "Only valid for group and note view objects — rejected for element view objects. "
                + "Common escape sequences (\\n, \\t, \\r, \\\\) are automatically interpreted "
                + "as their corresponding whitespace characters. "
                + "Omit to leave text unchanged; pass an empty string to clear it, which leaves a "
                + "group untitled or a note empty. Omitted and empty are different requests. "
                + "An empty string is still rejected for element view objects — use update-element "
                + "to change an element's name.");

        Map<String, Object> labelExpressionProp = new LinkedHashMap<>();
        labelExpressionProp.put("type", "string");
        labelExpressionProp.put("description",
                "Archi label expression — a dynamic rendering template for this view object's "
                + "label. Most common tokens: '${name}' renders the element's current name (so "
                + "renaming the element updates every view); '${property:KEY}' renders the value "
                + "of an element property named KEY (e.g. '${property:Owner}'). "
                + "Unlike 'text' (which sets a literal stored label for groups and notes), "
                + "'labelExpression' is the COMPUTED rendering instruction stored on the diagram "
                + "object. Archi evaluates the expression at render time. "
                + "Set to a non-empty string to apply; set to empty string (\"\") to clear and "
                + "fall back to the element's static name; omit to leave unchanged. "
                + "Archi owns the grammar (unknown tokens render as the literal '${...}'); the only "
                + "server-side check rejects a literal HTML/XML entity (e.g. \"&amp;\", \"&lt;\", "
                + "\"&#160;\") in the template — use the actual character instead. "
                + "See the archimate-view-patterns reference for details.");

        Map<String, Object> anchorTargetProp = new LinkedHashMap<>();
        anchorTargetProp.put("type", "string");
        anchorTargetProp.put("description",
                "Anchor this object's position to another view object (its viewObjectId) so it "
                + "follows that target when the target moves or grows, instead of keeping a frozen "
                + "absolute position. The anchored position is resolved from the target's current "
                + "bounds plus anchorEdge/dx/dy. Set to a non-empty target id to anchor; set to "
                + "empty string (\"\") to clear the anchor; omit to leave unchanged. The target and "
                + "this object must share a coordinate space (both top-level, or both in the same group).");

        Map<String, Object> anchorEdgeProp = new LinkedHashMap<>();
        anchorEdgeProp.put("type", "string");
        anchorEdgeProp.put("description",
                "Which edge of the anchor target to track: 'below' (default) keeps this object below "
                + "the target and follows its growing bottom; 'above', 'right', 'left' track the "
                + "corresponding edge. Only meaningful with anchorTarget.");

        Map<String, Object> dxProp = new LinkedHashMap<>();
        dxProp.put("type", "integer");
        dxProp.put("description",
                "Offset (px) along/against the anchor edge on the x axis; defaults to 0. Only meaningful with anchorTarget.");

        Map<String, Object> dyProp = new LinkedHashMap<>();
        dyProp.put("type", "integer");
        dyProp.put("description",
                "Gap (px) from the anchor edge on the y axis; defaults to 0. Only meaningful with anchorTarget.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewObjectId", viewObjectIdProp);
        properties.put("x", xProp);
        properties.put("y", yProp);
        properties.put("width", widthProp);
        properties.put("height", heightProp);
        properties.put("text", textProp);
        properties.put("labelExpression", labelExpressionProp);
        properties.put("anchorTarget", anchorTargetProp);
        properties.put("anchorEdge", anchorEdgeProp);
        properties.put("anchorDx", dxProp);
        properties.put("anchorDy", dyProp);
        addStylingProperties(properties);
        addImageProperties(properties);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewObjectId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update-view-object")
                .description("[Mutation] Update the visual position, size, styling, image, and/or label "
                        + "expression of an element on a view. Only provided fields are modified; "
                        + "unspecified fields remain unchanged. The underlying model element is not "
                        + "affected — only the visual representation on the diagram changes. At "
                        + "least one of x, y, width, height, text, styling, image, or labelExpression "
                        + "parameter must be provided. Required: viewObjectId (string) — "
                        + "the view object ID (from get-view-contents visualMetadata or groups/notes). "
                        + "Optional: x (integer), y (integer) — new position; width (integer), height (integer) "
                        + "— new size; text (string) — new label for groups or content for notes "
                        + "(rejected for elements); labelExpression (string) — per-view-object dynamic "
                        + "label template, e.g. '${name}' or '${property:Owner}' (empty string clears, "
                        + "distinct from text which is the literal stored label); fillColor, lineColor, "
                        + "fontColor (#RRGGBB hex or empty to clear), opacity (0-255), lineWidth (1-3) "
                        + "— visual styling; figureType ('rectangular' or 'tabbed' — applies to native "
                        + "groups and the ArchiMate Grouping element only; silently ignored on notes "
                        + "and other ArchiMate element classes), textAlignment ('left' / "
                        + "'centre' / 'right' — horizontal label alignment), verticalTextAlignment "
                        + "('top' / 'centre' / 'bottom' — vertical label position within the figure); "
                        + "imagePath (string — from add-image-to-model, empty to remove), "
                        + "imagePosition (string — e.g. bottom-left), showIcon (string — if-no-image/always/never) "
                        + "— custom image on element/group/note. When an image is set AND its stored "
                        + "bytes can be decoded, the response reports imageCoveragePercent — the "
                        + "percentage of the element's area the image actually PAINTS (0-100); both "
                        + "image fields are omitted if the image cannot be read, so treat their absence "
                        + "as 'unknown', not as 'no problem'. Archi CLIPS an image to the element box, so an image "
                        + "larger than its element is cut off at the edge rather than drawn outside it, and "
                        + "the figure counts only the pixels that render; a 'fill' image is scaled to the box "
                        + "and so always reads 100. Because the value is capped at 100, it does NOT tell you "
                        + "an image is oversized — imageCoverageWarning does, and it reports two independent "
                        + "problems: the image may obscure the element name (high coverage), and/or the image "
                        + "is larger than its element and will be rendered cut off (either can occur without "
                        + "the other — an oversized image on a short element is cut off while covering little "
                        + "of it). Fix a cut-off image by enlarging the element, using a smaller image, or "
                        + "setting imagePosition to 'fill'. "
                        + "Optional typography: fontName, fontSize, fontStyle "
                        + "('normal'/'bold'/'italic'/'bold-italic'). Optional gradient "
                        + "('none'/'top-bottom'/'bottom-top'/'left-right'/'right-left'). Optional "
                        + "borderType ('dogear'/'rectangle'/'none' — note-specific, silently ignored "
                        + "on groups and elements). Optional deriveLineColor (boolean — when false, "
                        + "lineColor is used verbatim instead of derived from fill). Optional "
                        + "outlineOpacity (0-255). Optional lineStyle ('solid'/'dashed'/'dotted'/'none' "
                        + "— view-object outline border style). "
                        + "Optional anchoring: anchorTarget (string — viewObjectId to anchor to; empty "
                        + "string clears), anchorEdge ('below' default / 'above' / 'right' / 'left'), "
                        + "anchorDx / anchorDy (integer offsets) — makes this object follow the target when it "
                        + "moves or grows (e.g. a note that stays below a group as the group grows), "
                        + "instead of a frozen absolute position. Anchor resolves at commit time. "
                        + "Respects approval mode (human-gated in Archi). All changes (including labelExpression, "
                        + "figureType, textAlignment, verticalTextAlignment, typography, gradient, borderType, "
                        + "deriveLineColor, outlineOpacity, lineStyle) execute as a single undo unit. "
                        + "RESPONSE: the returned x, y, width and height are the EFFECTIVE geometry the "
                        + "model holds after the write — Archi auto-fits a container to its children, so "
                        + "these can differ from the values you passed; treat the response, not your "
                        + "request, as the current state. 'parentViewObjectId' names the container "
                        + "this object sits in, omitted when it sits on the view itself — the same "
                        + "key get-view-contents publishes, and the origin the x and y above are "
                        + "relative to. 'resizedAncestors' names any container this "
                        + "update GREW underneath itself (the parent-fit cascade, which can run several "
                        + "levels up) and 'movedObjects' names any object ANCHORED to something this "
                        + "update moved or grew, each with the position or rectangle it landed at. Both "
                        + "are omitted when empty, and both report observations — an ancestor recomputed "
                        + "to an unchanged rectangle appears in neither. In a batch or pending approval "
                        + "nothing has executed, so no effective geometry or collateral is reported and "
                        + "the entity is nested under 'preview'. "
                        + "ORDERING: size a hub element — one carrying five or more connections — before "
                        + "auto-route-connections, so the router sees the larger perimeter and can spread its ports "
                        + "across the extra faces; a hub enlarged afterwards leaves every route computed against "
                        + "the old box. The same holds for any move or resize on a view that has already been "
                        + "routed: the stored bendpoints stay anchored to bounds that have since moved and the "
                        + "drawn polyline shears away from the stored one. Re-run auto-route-connections after such "
                        + "a change, and confirm assess-layout reports anchorDriftCount back at zero. "
                        + "Related: get-view-contents (inspect view + get viewObjectIds), "
                        + "add-to-view (place elements), add-image-to-model (import images), "
                        + "archimate-view-patterns (label expression + styling completeness reference).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleUpdateViewObject)
                .build();
    }

    McpSchema.CallToolResult handleUpdateViewObject(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling update-view-object request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewObjectId = HandlerUtils.requireStringParam(args, "viewObjectId");
            Integer x = HandlerUtils.optionalIntegerParam(args, "x");
            Integer y = HandlerUtils.optionalIntegerParam(args, "y");
            Integer width = HandlerUtils.optionalIntegerParam(args, "width");
            Integer height = HandlerUtils.optionalIntegerParam(args, "height");
            // allow-empty variant — empty string "" clears a group's label or a note's content;
            // absent key leaves it unchanged.
            String text = HandlerUtils.optionalStringParamAllowEmpty(args, "text");
            StylingParams styling = extractStylingParams(args);
            ImageParams imageParams = extractImageParams(args);
            // allow-empty variant — empty string "" clears the label
            // expression; absent key leaves it unchanged.
            String labelExpression = HandlerUtils.optionalStringParamAllowEmpty(args, "labelExpression");
            // Anchor params — empty string anchorTarget clears; absent leaves unchanged.
            String anchorTarget = HandlerUtils.optionalStringParamAllowEmpty(args, "anchorTarget");
            String anchorEdge = HandlerUtils.optionalStringParam(args, "anchorEdge");
            Integer anchorDx = HandlerUtils.optionalIntegerParam(args, "anchorDx");
            Integer anchorDy = HandlerUtils.optionalIntegerParam(args, "anchorDy");
            boolean anchoring = anchorTarget != null && !anchorTarget.isEmpty();
            if (anchoring && !AnchorResolver.isValidEdge(anchorEdge)) {
                throw new ModelAccessException(
                        "Invalid anchorEdge value: '" + anchorEdge + "'",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "anchorEdge must be one of: below, above, right, left (omit for the default 'below').",
                        null);
            }

            MutationResult<ViewObjectDto> result = accessor.updateViewObject(
                    sessionId, viewObjectId, x, y, width, height, text, styling, imageParams,
                    labelExpression, anchorTarget, anchorEdge, anchorDx, anchorDy);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildUpdateViewObjectNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling update-view-object", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildUpdateViewObjectNextSteps(MutationResult<ViewObjectDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "View object updated. Use get-view-contents to inspect the current layout.",
                "Use update-view-object to make further adjustments.",
                "Use remove-from-view to remove the element from the view.");
    }

    // ---- update-view-connection ----

    private McpServerFeatures.SyncToolSpecification buildUpdateViewConnectionSpec() {
        Map<String, Object> viewConnectionIdProp = new LinkedHashMap<>();
        viewConnectionIdProp.put("type", "string");
        viewConnectionIdProp.put("description",
                "ID of the connection to update (from get-view-contents connections)");

        Map<String, Object> bpItemProps = new LinkedHashMap<>();
        bpItemProps.put("startX", Map.of("type", "integer"));
        bpItemProps.put("startY", Map.of("type", "integer"));
        bpItemProps.put("endX", Map.of("type", "integer"));
        bpItemProps.put("endY", Map.of("type", "integer"));

        Map<String, Object> bpItems = new LinkedHashMap<>();
        bpItems.put("type", "object");
        bpItems.put("properties", bpItemProps);
        bpItems.put("required", List.of("startX", "startY", "endX", "endY"));

        Map<String, Object> bendpointsProp = new LinkedHashMap<>();
        bendpointsProp.put("type", "array");
        bendpointsProp.put("description",
                "Bendpoints in relative format. Each bendpoint has "
                + "startX/startY (offset from source element center) and endX/endY "
                + "(offset from target element center). Mutually exclusive with absoluteBendpoints. "
                + "Omit both formats to clear all bendpoints (straight line).");
        bendpointsProp.put("items", bpItems);

        Map<String, Object> absBpItemProps = new LinkedHashMap<>();
        absBpItemProps.put("x", Map.of("type", "integer"));
        absBpItemProps.put("y", Map.of("type", "integer"));

        Map<String, Object> absBpItems = new LinkedHashMap<>();
        absBpItems.put("type", "object");
        absBpItems.put("properties", absBpItemProps);
        absBpItems.put("required", List.of("x", "y"));

        Map<String, Object> absoluteBpProp = new LinkedHashMap<>();
        absoluteBpProp.put("type", "array");
        absoluteBpProp.put("description",
                "Bendpoints in absolute canvas coordinates. Each bendpoint has "
                + "x/y (absolute position). The server converts to Archi's relative "
                + "format automatically. Preferred over relative bendpoints for ease of use. "
                + "Mutually exclusive with bendpoints. "
                + "Omit both formats to clear all bendpoints (straight line).");
        absoluteBpProp.put("items", absBpItems);

        Map<String, Object> showLabelProp = new LinkedHashMap<>();
        showLabelProp.put("type", "boolean");
        showLabelProp.put("description",
                "Set to false to suppress the relationship name label on this connection. "
                + "Set to true to restore it. Omit to leave unchanged.");

        Map<String, Object> labelPositionProp = new LinkedHashMap<>();
        labelPositionProp.put("type", "string");
        labelPositionProp.put("enum", List.of("source", "middle", "target"));
        labelPositionProp.put("description",
                "Position the relationship name label along the connection path. "
                + "'source' = near source (15%), 'middle' = center (50%), 'target' = near target (85%). "
                + "Omit to leave unchanged. Use to reduce label overlaps on dense diagrams.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewConnectionId", viewConnectionIdProp);
        properties.put("bendpoints", bendpointsProp);
        properties.put("absoluteBendpoints", absoluteBpProp);
        addConnectionStylingProperties(properties);
        properties.put("showLabel", showLabelProp);
        properties.put("labelPosition", labelPositionProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewConnectionId"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update-view-connection")
                .description("[Mutation] Replace the bendpoints and/or update styling of a "
                        + "connection on a view. "
                        + "Bendpoints define routing waypoints for the visual connection line. "
                        + "Providing an empty array removes all bendpoints (straight line). "
                        + "Supports two formats: bendpoints (relative offsets: "
                        + "{startX, startY, endX, endY} from source/target element centers) "
                        + "or absoluteBendpoints (absolute canvas coordinates: {x, y}, "
                        + "server converts automatically). The underlying model relationship "
                        + "is not affected. Archi renders connection endpoints at element "
                        + "perimeter intersections automatically (ChopboxAnchor) — bendpoints "
                        + "only control intermediate routing waypoints, not where lines attach "
                        + "to element edges. Required: viewConnectionId (string). "
                        + "Provide either bendpoints or absoluteBendpoints (not both). "
                        + "Optional styling: lineColor, fontColor (#RRGGBB hex or empty to clear), "
                        + "lineWidth (1-3). Optional: showLabel (false to suppress relationship "
                        + "name label, true to restore). "
                        + "Optional: labelPosition ('source'/'middle'/'target') to control label placement. "
                        + "Optional typography: fontName, fontSize, fontStyle "
                        + "('normal'/'bold'/'italic'/'bold-italic'). "
                        + "NOTE: lineStyle is a view-object property; connection line style is determined "
                        + "by the ArchiMate relationship type and cannot be overridden on the view. "
                        + "Passing lineStyle here is REJECTED (INVALID_PARAMETER) rather than ignored. "
                        + "To distinguish connections visually — synchronous versus asynchronous flows, "
                        + "for example — use lineColor and lineWidth. "
                        + "Respects approval mode (human-gated in Archi). All changes execute as a single undo unit. "
                        + "Returns post-update viewConnectionId, relationshipId, endpoint IDs "
                        + "and stored bendpoints. The nameVisible field is present only when the "
                        + "label "
                        + "is hidden — absence means visible. "
                        + "Related: get-view-contents (inspect view + get connection IDs and "
                        + "absoluteBendpoints), add-connection-to-view (add connections), "
                        + "archimate-view-patterns resource (styling completeness reference).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleUpdateViewConnection)
                .build();
    }

    McpSchema.CallToolResult handleUpdateViewConnection(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling update-view-connection request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewConnectionId = HandlerUtils.requireStringParam(args, "viewConnectionId");
            List<BendpointDto> bendpoints = extractBendpoints(args);
            List<AbsoluteBendpointDto> absoluteBendpoints = extractAbsoluteBendpoints(args);
            validateBendpointFormats(bendpoints, absoluteBendpoints);
            StylingParams styling = extractStylingParams(args);
            Boolean showLabel = (args.get("showLabel") instanceof Boolean b) ? b : null;
            Integer textPosition = parseLabelPosition(args);

            // At least one field must be provided. If all five (bendpoints, absoluteBendpoints,
            // styling, showLabel, textPosition) are null, fall back to clearing bendpoints (the
            // legacy default — preserved for compat with callers who deliberately call with no
            // arguments to straighten the line).
            boolean autoClearedBendpoints = false;
            if (bendpoints == null && absoluteBendpoints == null && styling == null
                    && showLabel == null && textPosition == null) {
                bendpoints = List.of(); // clear bendpoints
                autoClearedBendpoints = true;
            }

            MutationResult<ViewConnectionDto> result = accessor.updateViewConnection(
                    sessionId, viewConnectionId, bendpoints, absoluteBendpoints, styling,
                    showLabel, textPosition);

            // Distinguish "caller intentionally changed bendpoints" from
            // "caller passed only styling/labelling — bendpoints unchanged". The earlier code
            // treated null as "cleared" and emitted the misleading message
            // "Connection bendpoints cleared" for styling-only updates.
            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildUpdateViewConnectionNextSteps(
                            result, bendpoints, absoluteBendpoints,
                            styling, showLabel, textPosition, autoClearedBendpoints),
                    buildUpdateViewConnectionApprovalDisclosures(
                            bendpoints, absoluteBendpoints,
                            styling, showLabel, textPosition, autoClearedBendpoints),
                    accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling update-view-connection", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * Which of the four things the caller asked for, decided from the request alone.
     *
     * <p>Every selector this builder reads is a request value evaluated before the accessor is
     * called, and that is correct rather than a lapse: these branches describe the <em>shape of
     * the request</em>, not the outcome of the write. It is also what makes the guidance safe to
     * re-tense — there is no measured outcome here to be wrong about on an arm where nothing has
     * been written. The builder must keep reading no DTO field: a value sourced from the ask and
     * published under an outcome's name is the defect the effective-state rule exists to catch.</p>
     */
    private enum ConnectionUpdateShape {
        BENDPOINTS_CLEARED, BENDPOINTS_SET, STYLING_ONLY, UNSPECIFIED
    }

    private static ConnectionUpdateShape connectionUpdateShape(
            List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            StylingParams styling,
            Boolean showLabel,
            Integer textPosition,
            boolean autoClearedBendpoints) {
        boolean bendpointsCleared = autoClearedBendpoints
                || (bendpoints != null && bendpoints.isEmpty())
                || (absoluteBendpoints != null && absoluteBendpoints.isEmpty());
        if (bendpointsCleared) {
            return ConnectionUpdateShape.BENDPOINTS_CLEARED;
        }
        boolean bendpointsSet = (bendpoints != null && !bendpoints.isEmpty())
                || (absoluteBendpoints != null && !absoluteBendpoints.isEmpty());
        if (bendpointsSet) {
            return ConnectionUpdateShape.BENDPOINTS_SET;
        }
        // Styling / showLabel / textPosition only (no bendpoint change).
        boolean anyStyling = styling != null && styling.hasAnyValue();
        if (anyStyling || showLabel != null || textPosition != null) {
            return ConnectionUpdateShape.STYLING_ONLY;
        }
        return ConnectionUpdateShape.UNSPECIFIED;
    }

    /**
     * What has happened, or has not, to the connection — one clause per shape per arm.
     *
     * <p>Written out rather than assembled from a shared stem with a tense swapped in: the four
     * applied clauses are the shipped text and a substitution over a finished sentence is how a
     * constant quietly acquires a second, wrong meaning.</p>
     */
    private static String connectionOutcomeClause(ConnectionUpdateShape shape, DispatchArm arm) {
        return switch (shape) {
            case BENDPOINTS_CLEARED -> switch (arm) {
                case APPLIED -> "Connection bendpoints cleared (straight line).";
                case QUEUED -> "Connection bendpoints will be cleared (straight line) when this "
                        + "batch commits.";
                case AWAITING_APPROVAL -> "Connection bendpoints will be cleared (straight line) "
                        + "if this change is approved.";
            };
            case BENDPOINTS_SET -> switch (arm) {
                case APPLIED -> "Connection bendpoints updated.";
                case QUEUED -> "Connection bendpoints will be updated when this batch commits.";
                case AWAITING_APPROVAL -> "Connection bendpoints will be updated if this change "
                        + "is approved.";
            };
            case STYLING_ONLY -> switch (arm) {
                case APPLIED -> "Connection updated (bendpoints unchanged).";
                case QUEUED -> "Connection will be updated when this batch commits (bendpoints "
                        + "unchanged).";
                case AWAITING_APPROVAL -> "Connection will be updated if this change is approved "
                        + "(bendpoints unchanged).";
            };
            case UNSPECIFIED -> switch (arm) {
                case APPLIED -> "Connection updated.";
                case QUEUED -> "Connection will be updated when this batch commits.";
                case AWAITING_APPROVAL -> "Connection will be updated if this change is approved.";
            };
        };
    }

    /**
     * Where to look at the result — which on a deferred arm is not yet anywhere.
     *
     * <p>{@code get-view-contents} reads the committed model, so on both deferred arms it would
     * show the connection as it was and the caller would read the correct outcome as a failure.
     * The instruction is kept and moved to the moment it becomes true.</p>
     */
    private static String connectionInspectClause(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> " Use get-view-contents to inspect.";
            case QUEUED -> " Nothing is written yet — get-view-contents reads the committed view, "
                    + "so inspect after end-batch.";
            case AWAITING_APPROVAL -> " Nothing is written yet — get-view-contents reads the "
                    + "committed view, so inspect once the human has approved the change.";
        };
    }

    /** The second line of each branch: a call to make next, true whatever this call's fate. */
    private static String connectionFollowUpStep(ConnectionUpdateShape shape) {
        return switch (shape) {
            case BENDPOINTS_CLEARED -> "Use update-view-connection to add bendpoints for routing.";
            case BENDPOINTS_SET -> "Use update-view-connection with empty bendpoints array to "
                    + "straighten the connection.";
            case STYLING_ONLY -> "Provide bendpoints or absoluteBendpoints to change routing.";
            case UNSPECIFIED -> "Use update-view-connection to change bendpoints, styling, or "
                    + "label visibility.";
        };
    }

    private static List<String> updateViewConnectionDisclosures(
            ConnectionUpdateShape shape, DispatchArm arm) {
        return List.of(
                connectionOutcomeClause(shape, arm) + connectionInspectClause(arm),
                connectionFollowUpStep(shape));
    }

    /** What an update-view-connection response carries when the write is waiting on a human. */
    private List<String> buildUpdateViewConnectionApprovalDisclosures(
            List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            StylingParams styling,
            Boolean showLabel,
            Integer textPosition,
            boolean autoClearedBendpoints) {
        return updateViewConnectionDisclosures(
                connectionUpdateShape(bendpoints, absoluteBendpoints, styling, showLabel,
                        textPosition, autoClearedBendpoints),
                DispatchArm.AWAITING_APPROVAL);
    }

    private List<String> buildUpdateViewConnectionNextSteps(
            MutationResult<ViewConnectionDto> result,
            List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            StylingParams styling,
            Boolean showLabel,
            Integer textPosition,
            boolean autoClearedBendpoints) {
        ConnectionUpdateShape shape = connectionUpdateShape(bendpoints, absoluteBendpoints,
                styling, showLabel, textPosition, autoClearedBendpoints);
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>(
                    updateViewConnectionDisclosures(shape, DispatchArm.QUEUED));
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        return updateViewConnectionDisclosures(shape, DispatchArm.APPLIED);
    }

    // ---- remove-from-view ----

    private McpServerFeatures.SyncToolSpecification buildRemoveFromViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view containing the object to remove");

        Map<String, Object> viewObjectIdProp = new LinkedHashMap<>();
        viewObjectIdProp.put("type", "string");
        viewObjectIdProp.put("description",
                "ID of the view object or connection to remove "
                + "(from get-view-contents visualMetadata viewObjectId or connection IDs)");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("viewObjectId", viewObjectIdProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId", "viewObjectId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("remove-from-view")
                .description("[Mutation] Remove a visual element or connection from a view "
                        + "without deleting the underlying model object. When removing an element, "
                        + "any connections attached to that view object are also cascade-removed. "
                        + "The viewObjectId can reference either a view object (element) or a view "
                        + "connection. Required: viewId (string), viewObjectId (string) — the ID "
                        + "of the view object or connection to remove. "
                        + "Respects approval mode (human-gated in Archi). "
                        + "Returns removedObjectId and removedObjectType (viewObject/group/"
                        + "note/viewConnection). The cascadeRemovedConnectionIds list is omitted, "
                        + "not "
                        + "empty, when nothing cascaded; for a group it also lists descendant "
                        + "view-object IDs. "
                        + "Related: get-view-contents (inspect view + get IDs), "
                        + "add-to-view (re-place elements), "
                        + "add-connection-to-view (re-add connections).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleRemoveFromView)
                .build();
    }

    McpSchema.CallToolResult handleRemoveFromView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling remove-from-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String viewObjectId = HandlerUtils.requireStringParam(args, "viewObjectId");

            MutationResult<RemoveFromViewResultDto> result = accessor.removeFromView(
                    sessionId, viewId, viewObjectId);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildRemoveFromViewNextSteps(result),
                    buildRemoveFromViewApprovalDisclosures(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling remove-from-view", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * The one clause here that is true on every arm, so it is written once and never rescoped.
     *
     * <p>Taking an object off a view does not touch the concept behind it, whether the removal has
     * landed, is queued, or is waiting on a human. On a deferred arm it is the most useful thing
     * in the response: an agent that has just queued a destructive view operation wants to know
     * the model survives it.</p>
     */
    private static final String REMOVED_ELEMENT_SURVIVES =
            " Underlying model element is unchanged.";

    private static final String REMOVED_RELATIONSHIP_SURVIVES =
            " Underlying model relationship is unchanged.";

    /**
     * How many attached connections go with the object, and how firmly that is known.
     *
     * <p>The list is collected at prepare time and handed to the command at construction, which
     * disconnects exactly that list — so on a queued call the count describes the command rather
     * than predicting the model. A stored proposal re-prepares on approval, so the same walk runs
     * again against a possibly different view and the count is re-derived.</p>
     */
    private static String removedCascadeNote(int cascadeCount, DispatchArm arm) {
        if (cascadeCount == 0) {
            return "";
        }
        String plural = " (" + cascadeCount + " connection" + (cascadeCount > 1 ? "s" : "");
        return switch (arm) {
            case APPLIED -> plural + " also removed)";
            case QUEUED -> plural + " will also be removed)";
            case AWAITING_APPROVAL -> plural + " will also be removed, re-counted on approval)";
        };
    }

    private static String removedObjectStep(
            boolean isElement, int cascadeCount, DispatchArm arm) {
        String note = isElement ? removedCascadeNote(cascadeCount, arm) : "";
        String survives = isElement ? REMOVED_ELEMENT_SURVIVES : REMOVED_RELATIONSHIP_SURVIVES;
        String subject = isElement ? "Element" : "Connection";
        String outcome = switch (arm) {
            case APPLIED -> subject + " removed from view" + note + ".";
            case QUEUED -> subject + " will be removed from view when this batch commits"
                    + note + ".";
            case AWAITING_APPROVAL -> subject + " will be removed from view if this change is "
                    + "approved" + note + ".";
        };
        return outcome + survives;
    }

    /**
     * Where to look at the view — which, until the removal lands, still shows the object.
     *
     * <p>{@code get-view-contents} reads the committed model, so on a deferred arm it reports the
     * object still present and a caller checking its own work would read the correct state as a
     * failed removal.</p>
     */
    private static String removedInspectStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "Use get-view-contents to inspect the current view layout.";
            case QUEUED -> "Use get-view-contents after end-batch to inspect the view layout — it "
                    + "reads the committed view, which still holds this object until then.";
            case AWAITING_APPROVAL -> "Use get-view-contents once the human has approved the "
                    + "change to inspect the view layout — it reads the committed view, which "
                    + "still holds this object until then.";
        };
    }

    /**
     * How to put it back, and on a deferred arm why that is the wrong call.
     *
     * <p>Nothing has been removed, so the placement tools would not restore anything: they would
     * add a second copy alongside the one still there. What recovers a deferred removal is
     * discarding it — {@code end-batch rollback:true} while queued, and the human's rejection
     * while awaiting approval, which no tool the agent can call performs.</p>
     */
    private static String removedReversalStep(boolean isElement, DispatchArm arm) {
        String placement = isElement ? "add-to-view" : "add-connection-to-view";
        String duplicate = isElement ? "placement" : "connection";
        return switch (arm) {
            case APPLIED -> isElement
                    ? "Use add-to-view to place the element back on the view."
                    : "Use add-connection-to-view to add a connection back.";
            case QUEUED -> "Nothing has been removed yet: end-batch rollback:true discards this "
                    + "removal. Calling " + placement + " instead would queue a second "
                    + duplicate + " rather than restore anything.";
            case AWAITING_APPROVAL -> "Nothing has been removed yet: rejecting the change in Archi "
                    + "leaves the object where it is. Calling " + placement + " instead would "
                    + "propose a second " + duplicate + " rather than restore anything.";
        };
    }

    /**
     * What a removal reports, composed once and phrased for the arm.
     *
     * <p>The command is frozen at prepare: it is handed the cascade list at construction and
     * disconnects exactly that list, so the identifiers reported are the ones the queued command
     * will act on and a future-tense sentence about them is true of the command. That a connection
     * queued <em>later</em> in the same batch could attach to this object and be left dangling is
     * a real hazard, and it is a hazard about the cascade rather than about this report.</p>
     *
     * <p>This is the contrast with {@code clear-view}, whose builder sits a few hundred lines
     * below and looks alike. That command re-walks the view inside {@code execute()}, so its
     * prepare-time counts describe a different model than the one it will clear — and it therefore
     * withholds them here rather than re-tensing them.</p>
     */
    private List<String> removeFromViewDisclosures(
            RemoveFromViewResultDto dto, DispatchArm arm) {
        if (dto == null) {
            return List.of();
        }
        boolean isElement = "viewObject".equals(dto.removedObjectType());
        int cascadeCount = dto.cascadeRemovedConnectionIds() != null
                ? dto.cascadeRemovedConnectionIds().size() : 0;
        return List.of(
                removedObjectStep(isElement, cascadeCount, arm),
                removedInspectStep(arm),
                removedReversalStep(isElement, arm));
    }

    /** What a remove-from-view response carries when the removal is waiting on a human. */
    private List<String> buildRemoveFromViewApprovalDisclosures(
            MutationResult<RemoveFromViewResultDto> result) {
        return removeFromViewDisclosures(result.entity(), DispatchArm.AWAITING_APPROVAL);
    }

    private List<String> buildRemoveFromViewNextSteps(
            MutationResult<RemoveFromViewResultDto> result) {
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>(
                    removeFromViewDisclosures(result.entity(), DispatchArm.QUEUED));
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        return removeFromViewDisclosures(result.entity(), DispatchArm.APPLIED);
    }

    // ---- clear-view ----

    private McpServerFeatures.SyncToolSpecification buildClearViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to clear");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("clear-view")
                .description("[Mutation] Remove all visual elements and connections from a view "
                        + "without deleting the underlying model objects. This is a single atomic "
                        + "operation that clears the entire view contents, dramatically more efficient "
                        + "than calling remove-from-view for each individual element. "
                        + "Required: viewId (string). "
                        + "Respects approval mode (human-gated in Archi). "
                        + "Returns viewId, viewName and counts: elementsRemoved covers ALL "
                        + "top-level objects and already includes nonArchimateObjectsRemoved "
                        + "— do not sum them; connectionsRemoved is recursive. "
                        + "Related: get-view-contents (inspect view before clearing), "
                        + "add-to-view (re-populate the view after clearing).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleClearView)
                .build();
    }

    McpSchema.CallToolResult handleClearView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling clear-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");

            MutationResult<ClearViewResultDto> result = accessor.clearView(sessionId, viewId);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildClearViewNextSteps(result),
                    buildClearViewApprovalDisclosures(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling clear-view", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /** True whatever happens to the clear: emptying a view never deletes the concepts on it. */
    private static final String CLEARED_MODEL_SURVIVES =
            " Underlying model objects are unchanged.";

    /**
     * What the clear did, or will do — and why only one arm may say how much.
     *
     * <p>This is the one tool of its family whose numbers cannot travel. The other view mutations
     * hand their command everything it needs at construction, so a prepare-time count describes
     * exactly what the queued command will do. {@code ClearViewCommand} does not: it re-reads the
     * view's children and re-collects their connections when it executes, and clears what it finds
     * then. Inside an open batch those are two different models — everything queued between the
     * two walks is invisible to the first and removed by the second — and the prepare cannot
     * consult the queue even in principle, since it is given no session and no dispatcher helper
     * enumerates what a batch has queued onto a view.</p>
     *
     * <p>So the deferred arms withhold the counts rather than restating them in the future tense.
     * A future-tense "12 object(s) will be removed" is not a rescoped remedy; it is a measurement
     * of a model that will not exist, which is a worse answer than none.</p>
     */
    private static String clearViewOutcomeStep(ClearViewResultDto dto, DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> {
                String summary = "View cleared: " + dto.elementsRemoved() + " object(s) and "
                        + dto.connectionsRemoved() + " connection(s) removed.";
                if (dto.nonArchimateObjectsRemoved() > 0) {
                    summary += " (" + dto.nonArchimateObjectsRemoved()
                            + " non-ArchiMate object(s) such as Notes/Groups were also removed.)";
                }
                yield summary + CLEARED_MODEL_SURVIVES;
            }
            case QUEUED -> "The view will be cleared when this batch commits. What it removes is "
                    + "not counted here: the command re-walks the view as it runs, so it will also "
                    + "remove whatever else this batch queues onto the view before then."
                    + CLEARED_MODEL_SURVIVES;
            case AWAITING_APPROVAL -> "The view will be cleared if this change is approved. What "
                    + "it removes is not counted here: the command re-walks the view as it runs, "
                    + "so whatever the view holds at that moment is what it takes."
                    + CLEARED_MODEL_SURVIVES;
        };
    }

    /**
     * How to check the result — which on a deferred arm would report the opposite of the truth.
     *
     * <p>{@code get-view-contents} reads the committed view, so before the clear lands it returns
     * a populated view. A caller following the applied wording would read the correct state as a
     * failed clear.</p>
     */
    private static String clearViewVerifyStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "Use get-view-contents to verify the view is empty.";
            case QUEUED -> "Use get-view-contents after end-batch to verify the view is empty — it "
                    + "reads the committed view, which is still populated until then.";
            case AWAITING_APPROVAL -> "Use get-view-contents once the human has approved the "
                    + "change to verify the view is empty — it reads the committed view, which is "
                    + "still populated until then.";
        };
    }

    /**
     * How to fill the view again, and when.
     *
     * <p>The queued wording carries an ordering constraint the applied one has no reason to: the
     * clear takes whatever the view holds when it runs, so a placement queued into the same batch
     * behind it is removed by it.</p>
     */
    private static String clearViewRepopulateStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "Use add-to-view to re-populate the view with elements.";
            case QUEUED -> "Use add-to-view to re-populate the view. Queue those placements after "
                    + "end-batch, not into this batch: the clear removes whatever the view holds "
                    + "when it runs, this batch's own additions included.";
            case AWAITING_APPROVAL -> "Use add-to-view to re-populate the view once this change "
                    + "has been approved or rejected.";
        };
    }

    private List<String> clearViewDisclosures(ClearViewResultDto dto, DispatchArm arm) {
        if (dto == null) {
            return List.of();
        }
        return List.of(
                clearViewOutcomeStep(dto, arm),
                clearViewVerifyStep(arm),
                clearViewRepopulateStep(arm));
    }

    /** What a clear-view response carries when the clear is waiting on a human. */
    private List<String> buildClearViewApprovalDisclosures(
            MutationResult<ClearViewResultDto> result) {
        return clearViewDisclosures(result.entity(), DispatchArm.AWAITING_APPROVAL);
    }

    private List<String> buildClearViewNextSteps(MutationResult<ClearViewResultDto> result) {
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>(
                    clearViewDisclosures(result.entity(), DispatchArm.QUEUED));
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        return clearViewDisclosures(result.entity(), DispatchArm.APPLIED);
    }

    // ---- apply-positions ----

    private McpServerFeatures.SyncToolSpecification buildApplyViewLayoutSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to apply layout to");

        // positions array item schema
        Map<String, Object> posViewObjectIdProp = new LinkedHashMap<>();
        posViewObjectIdProp.put("type", "string");
        posViewObjectIdProp.put("description", "View object ID from get-view-contents");

        Map<String, Object> posXProp = new LinkedHashMap<>();
        posXProp.put("type", "integer");
        posXProp.put("description",
                "New X coordinate (omit to keep current). "
                + "For objects nested inside a group, this is RELATIVE to the parent group's "
                + "top-left corner, not an absolute canvas coordinate.");

        Map<String, Object> posYProp = new LinkedHashMap<>();
        posYProp.put("type", "integer");
        posYProp.put("description",
                "New Y coordinate (omit to keep current). "
                + "For objects nested inside a group, this is RELATIVE to the parent group's "
                + "top-left corner, not an absolute canvas coordinate.");

        Map<String, Object> posWidthProp = new LinkedHashMap<>();
        posWidthProp.put("type", "integer");
        posWidthProp.put("description", "New width (omit to keep current)");

        Map<String, Object> posHeightProp = new LinkedHashMap<>();
        posHeightProp.put("type", "integer");
        posHeightProp.put("description", "New height (omit to keep current). "
                + "EXCEPTION for a note: omitting height while supplying width re-fits the height "
                + "to the wrapped text, exactly as update-view-object does — the same rule reaches "
                + "a note through whichever tool changes its wrap.");

        Map<String, Object> posItemProps = new LinkedHashMap<>();
        posItemProps.put("viewObjectId", posViewObjectIdProp);
        posItemProps.put("x", posXProp);
        posItemProps.put("y", posYProp);
        posItemProps.put("width", posWidthProp);
        posItemProps.put("height", posHeightProp);

        Map<String, Object> posItemSchema = new LinkedHashMap<>();
        posItemSchema.put("type", "object");
        posItemSchema.put("required", List.of("viewObjectId"));
        posItemSchema.put("properties", posItemProps);

        Map<String, Object> positionsProp = new LinkedHashMap<>();
        positionsProp.put("type", "array");
        positionsProp.put("description",
                "Array of element/group/note position updates. Each entry updates one view object's bounds.");
        positionsProp.put("items", posItemSchema);

        // connections array item schema — bendpoints sub-schema
        Map<String, Object> connIdProp = new LinkedHashMap<>();
        connIdProp.put("type", "string");
        connIdProp.put("description", "Connection ID from get-view-contents");

        Map<String, Object> bpStartX = new LinkedHashMap<>();
        bpStartX.put("type", "integer");
        Map<String, Object> bpStartY = new LinkedHashMap<>();
        bpStartY.put("type", "integer");
        Map<String, Object> bpEndX = new LinkedHashMap<>();
        bpEndX.put("type", "integer");
        Map<String, Object> bpEndY = new LinkedHashMap<>();
        bpEndY.put("type", "integer");

        Map<String, Object> bpItemProps = new LinkedHashMap<>();
        bpItemProps.put("startX", bpStartX);
        bpItemProps.put("startY", bpStartY);
        bpItemProps.put("endX", bpEndX);
        bpItemProps.put("endY", bpEndY);

        Map<String, Object> bpItemSchema = new LinkedHashMap<>();
        bpItemSchema.put("type", "object");
        bpItemSchema.put("required", List.of("startX", "startY", "endX", "endY"));
        bpItemSchema.put("properties", bpItemProps);

        Map<String, Object> bendpointsProp = new LinkedHashMap<>();
        bendpointsProp.put("type", "array");
        bendpointsProp.put("description",
                "Relative bendpoints (mutually exclusive with absoluteBendpoints). "
                        + "Omit both to clear (straight line).");
        bendpointsProp.put("items", bpItemSchema);

        // absoluteBendpoints sub-schema
        Map<String, Object> abpX = new LinkedHashMap<>();
        abpX.put("type", "integer");
        Map<String, Object> abpY = new LinkedHashMap<>();
        abpY.put("type", "integer");

        Map<String, Object> abpItemProps = new LinkedHashMap<>();
        abpItemProps.put("x", abpX);
        abpItemProps.put("y", abpY);

        Map<String, Object> abpItemSchema = new LinkedHashMap<>();
        abpItemSchema.put("type", "object");
        abpItemSchema.put("required", List.of("x", "y"));
        abpItemSchema.put("properties", abpItemProps);

        Map<String, Object> absoluteBpProp = new LinkedHashMap<>();
        absoluteBpProp.put("type", "array");
        absoluteBpProp.put("description",
                "Absolute canvas coordinate bendpoints (mutually exclusive with bendpoints). "
                        + "Omit both to clear (straight line).");
        absoluteBpProp.put("items", abpItemSchema);

        Map<String, Object> connItemProps = new LinkedHashMap<>();
        connItemProps.put("viewConnectionId", connIdProp);
        connItemProps.put("bendpoints", bendpointsProp);
        connItemProps.put("absoluteBendpoints", absoluteBpProp);

        Map<String, Object> connItemSchema = new LinkedHashMap<>();
        connItemSchema.put("type", "object");
        connItemSchema.put("required", List.of("viewConnectionId"));
        connItemSchema.put("properties", connItemProps);

        Map<String, Object> connectionsProp = new LinkedHashMap<>();
        connectionsProp.put("type", "array");
        connectionsProp.put("description",
                "Array of connection bendpoint updates. Each entry updates one connection's routing.");
        connectionsProp.put("items", connItemSchema);

        Map<String, Object> descriptionProp = new LinkedHashMap<>();
        descriptionProp.put("type", "string");
        descriptionProp.put("description", "Optional label for the undo history entry in Archi");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("positions", positionsProp);
        properties.put("connections", connectionsProp);
        properties.put("description", descriptionProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("apply-positions")
                .description("[Mutation] Apply a complete visual layout to a view as a single "
                        + "atomic operation. Repositions elements/groups/notes and updates "
                        + "connection bendpoints with up to 10,000 total entries (vs bulk-mutate's "
                        + "150-operation limit). All changes form a single undo unit in Archi. "
                        + "Requires viewId. Optional: positions (array of viewObjectId with "
                        + "x/y/width/height), connections (array of viewConnectionId with "
                        + "bendpoints or absoluteBendpoints). At least one of positions or "
                        + "connections must be provided. All-or-nothing: if any entry fails "
                        + "validation, no changes are applied. Validation does not stop at the "
                        + "first bad entry: every entry failing the same check is listed in a "
                        + "'failed' array, each naming the array it came from, its index in that "
                        + "array and its own errorCode — those per-entry codes are the authority, "
                        + "since the single code on the error describes the first failure only. Rows "
                        + "do not repeat what they share: a long ending common to every row of one "
                        + "error code is published once beside the array — in 'messages' and "
                        + "'corrections', keyed by that error code — and the row names it with "
                        + "'messageRef' or 'correctionRef'. A split row keeps its own head under the "
                        + "field's own name, so head followed by the shared string is the whole value; "
                        + "a row that shares the WHOLE of its suggestedCorrection carries "
                        + "'correctionRef' and no 'suggestedCorrection' at all, so resolve the "
                        + "reference rather than reading that row as having no remedy. "
                        + "Unreadable entry shapes are checked before ids are resolved, so a "
                        + "payload with both kinds of defect reports the shapes first and the "
                        + "unresolvable ids on the next call. "
                        + "CONTAINERS GROW: a position that pushes an object past the bounds of the "
                        + "container it sits in re-fits that container, and every ancestor above it, and "
                        + "carries any object anchored to something the call repositions along with it. "
                        + "Each container is measured ONCE per call against what the same call has already "
                        + "decided about it, so two entries needing different axes of the same group both "
                        + "survive and the order they arrive in does not change the result. Those re-fits are "
                        + "committed AFTER every position lands, so naming a container in 'positions' and asking "
                        + "for it SMALLER than its children need loses to the fit — the canvas cannot show a "
                        + "container clipping its own contents. Asking for it BIGGER wins: a rectangle that "
                        + "already contains what the fit demanded retires the fit rather than being overwritten "
                        + "by it. The response "
                        + "reports counts only — it does NOT return the geometry those containers ended at, "
                        + "so re-read with get-view-contents before computing anything from a container's size. "
                        + "SPECULATIVE EXECUTION: "
                        + "To preview layout quality, apply layout → assess-layout → "
                        + "undo if unsatisfied. No dry-run needed — undo is cheap and instant. "
                        + "ORDERING: applying positions to a view that has already been routed leaves the stored "
                        + "bendpoints anchored to bounds that have since moved, and the drawn polyline shears away "
                        + "from the stored one — worst at its first and last bendpoint. Re-run "
                        + "auto-route-connections after such a call and confirm assess-layout reports "
                        + "anchorDriftCount back at zero. "
                        + "Related: get-view-contents (get current layout and IDs), "
                        + "bulk-mutate (general mutations), update-view-object (single "
                        + "element), update-view-connection (single connection), undo "
                        + "(roll back if unsatisfied).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleApplyViewLayout)
                .build();
    }

    McpSchema.CallToolResult handleApplyViewLayout(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling apply-positions request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String description = HandlerUtils.optionalStringParam(args, "description");

            // Both arrays are read before either is judged. A caller must not have to fix the
            // positions array to discover that the connections array is wrong too, and a shape
            // failure in one entry must not hide the other entries beside it.
            LayoutValidationFailures failures = new LayoutValidationFailures();
            List<ViewPositionSpec> positions = parsePositions(args, failures);
            List<ViewConnectionSpec> connections = parseConnections(args, failures);
            if (!failures.isEmpty()) {
                throw failures.toException();
            }

            MutationResult<ApplyViewLayoutResultDto> result =
                    accessor.applyViewLayout(sessionId, viewId, positions, connections, description);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildApplyViewLayoutNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (LayoutValidationException e) {
            return buildLayoutValidationError(e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling apply-positions", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * Formats a refusal that carries every entry which failed validation, not only the first.
     *
     * <p>Tool-local rather than routed through the shared {@code buildModelAccessError}: that
     * helper emits the envelope fifty-five other call sites depend on, and widening it here would
     * add {@code nextSteps} and {@code _meta.modelVersion} to every error this server emits. Only
     * this tool's multi-entry refusal takes this path; its other refusals are unchanged.</p>
     *
     * <p>Every count published here is derived from the true failure total rather than from the
     * length of the capped row list, so a refusal that cannot list everything says so with the
     * real number instead of a number that happens to be the cap.</p>
     */
    private McpSchema.CallToolResult buildLayoutValidationError(LayoutValidationException e) {
        logger.debug("Tool error (expected): {} [{}]", e.getMessage(), e.getErrorCode());
        ErrorResponse error = new ErrorResponse(
                e.getErrorCode(),
                e.getMessage(),
                e.getDetails(),
                e.getSuggestedCorrection(),
                e.getArchiMateReference());

        List<LayoutEntryFailure> failures = e.getFailures();
        int total = e.getTotalFailureCount();

        // One failure is already fully described by the fields above; a one-row array beside them
        // is noise, and its absence is what keeps that call's refusal exactly what it always was.
        Map<String, Object> extras = new LinkedHashMap<>();
        if (total > 1) {
            // Built from the rows this refusal actually publishes, not from the true total: the
            // cap can drop entries, and a dictionary built from what was dropped would carry an
            // entry no row names.
            HandlerUtils.putFailures(extras, failures);
        }

        List<String> nextSteps = new ArrayList<>();
        int named = Math.min(failures.size(), HandlerUtils.NEXT_STEP_FAILURE_LIMIT);
        for (int i = 0; i < named; i++) {
            LayoutEntryFailure failure = failures.get(i);
            nextSteps.add("Fix " + failure.array() + " entry " + failure.index() + ": "
                    + HandlerUtils.headline(failure.message()));
        }
        int unnamed = total - named;
        if (unnamed > 0) {
            nextSteps.add(unnamed + " further failed " + (unnamed == 1 ? "entry is" : "entries are")
                    + " not listed here — read the 'failed' array in the error for "
                    + (failures.size() < total
                            ? "the first " + failures.size() + " of " + total
                            : "all " + total));
        }
        String unlisted = unlistedByArray(e);
        if (unlisted != null) {
            nextSteps.add(unlisted);
        }
        nextSteps.add("Nothing was applied — fix every entry listed and resend the whole call");

        return HandlerUtils.buildResult(
                formatter.toJsonString(formatter.formatErrorWithExtras(
                        error, extras, nextSteps, accessor.getModelVersion())), true);
    }

    /**
     * Names the arrays carrying failures the capped {@code failed} array does not list, or null
     * when every array is represented.
     *
     * <p>The cap is shared across both caller arrays and the walks run in a fixed order, so an
     * array that fails wholesale takes every row slot and the other one is counted in the total
     * and never seen. Reporting only a total there would send a caller off to fix everything it
     * was shown, resend, and discover a second broken array — one round-trip per array, which is
     * the cost this refusal exists to remove. So the arrays are named even when their entries
     * cannot be.</p>
     */
    private static String unlistedByArray(LayoutValidationException e) {
        Map<String, Integer> listed = new LinkedHashMap<>();
        for (LayoutEntryFailure failure : e.getFailures()) {
            listed.merge(failure.array(), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> total : e.getTotalsByArray().entrySet()) {
            int missing = total.getValue() - listed.getOrDefault(total.getKey(), 0);
            if (missing > 0) {
                parts.add(missing + " in '" + total.getKey() + "'");
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "The 'failed' array does not list every failure — " + String.join(", ", parts)
                + " " + (parts.size() == 1 && parts.get(0).startsWith("1 ") ? "is" : "are")
                + " counted but not shown; fix what is listed, resend, and the rest will be named";
    }

    /**
     * Reads the {@code positions} array, collecting every entry it cannot read rather than
     * unwinding at the first.
     *
     * <p>A payload generated programmatically tends to carry one systematic shape error in every
     * entry at once, which is exactly the case a first-failure-only refusal serves worst.</p>
     */
    private List<ViewPositionSpec> parsePositions(Map<String, Object> args,
            LayoutValidationFailures failures) {
        Object value = args.get("positions");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        List<ViewPositionSpec> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                failures.recordMalformedEntry("positions", i, new ModelAccessException(
                        "positions[" + i + "] must be an object with viewObjectId",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Each position entry must be an object with viewObjectId and "
                                + "at least one of x, y, width, height",
                        null));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) map;
            String viewObjectId;
            try {
                viewObjectId = HandlerUtils.requireStringParam(entry, "viewObjectId");
            } catch (ModelAccessException e) {
                // Republished verbatim. A missing id already reads as a parameter fault and gains
                // nothing from an entry prefix, so a caller sending one bad entry reads exactly
                // what it always read. Only a failure that names something INSIDE the entry --
                // a bendpoint index, say -- needs telling which entry it came from.
                failures.recordMalformedEntry("positions", i, e);
                continue;
            }
            // The remaining reads cannot throw: an absent or non-numeric coordinate is simply
            // absent. A field check added here that CAN throw belongs in recordEntryField, with
            // viewObjectId, so the entry it came from survives.
            Integer x = HandlerUtils.optionalIntegerParam(entry, "x");
            Integer y = HandlerUtils.optionalIntegerParam(entry, "y");
            Integer width = HandlerUtils.optionalIntegerParam(entry, "width");
            Integer height = HandlerUtils.optionalIntegerParam(entry, "height");
            result.add(new ViewPositionSpec(viewObjectId, x, y, width, height));
        }
        return result;
    }

    /**
     * Reads the {@code connections} array, collecting every entry it cannot read rather than
     * unwinding at the first.
     *
     * <p>The id is read before the bendpoints so that a bendpoint failure can still name the
     * connection it belongs to: the bendpoint helpers are shared with the single-connection tools
     * and report a bendpoint index alone, which read from inside an array leaves a caller told that
     * some bendpoint is malformed without being told which of its connections carried it.</p>
     */
    private List<ViewConnectionSpec> parseConnections(Map<String, Object> args,
            LayoutValidationFailures failures) {
        Object value = args.get("connections");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        List<ViewConnectionSpec> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                failures.recordMalformedEntry("connections", i, new ModelAccessException(
                        "connections[" + i + "] must be an object with viewConnectionId",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Each connection entry must be an object with viewConnectionId and "
                                + "optional bendpoints or absoluteBendpoints",
                        null));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) map;
            String viewConnectionId;
            try {
                viewConnectionId = HandlerUtils.requireStringParam(entry, "viewConnectionId");
            } catch (ModelAccessException e) {
                // As in the positions walk: a missing id is republished verbatim.
                failures.recordMalformedEntry("connections", i, e);
                continue;
            }
            try {
                List<BendpointDto> bendpoints = extractBendpoints(entry);
                List<AbsoluteBendpointDto> absoluteBendpoints = extractAbsoluteBendpoints(entry);
                validateBendpointFormats(bendpoints, absoluteBendpoints);

                // If neither provided, default to empty list (clear = straight line)
                if (bendpoints == null && absoluteBendpoints == null) {
                    bendpoints = List.of();
                }

                result.add(new ViewConnectionSpec(viewConnectionId, bendpoints, absoluteBendpoints));
            } catch (ModelAccessException e) {
                failures.recordEntryField("connections", i, viewConnectionId, e);
            }
        }
        return result;
    }

    private List<String> buildApplyViewLayoutNextSteps(
            MutationResult<ApplyViewLayoutResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        ApplyViewLayoutResultDto dto = result.entity();
        return List.of(
                "Layout applied: " + dto.positionsUpdated() + " position(s) and "
                        + dto.connectionsUpdated() + " connection(s) updated.",
                "Use get-view-contents to verify the applied layout.",
                "Use export-view to visually inspect the result.");
    }

    // ---- assess-layout ----

    private McpServerFeatures.SyncToolSpecification buildAssessLayoutSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to assess");

        Map<String, Object> includeViolatorIdsProp = new LinkedHashMap<>();
        includeViolatorIdsProp.put("type", "boolean");
        includeViolatorIdsProp.put("description",
                "If true, includes a violatorIds map with per-metric visual object IDs "
                + "of elements/connections that violate each metric. Enables targeted "
                + "surgical fixes instead of global re-layout. Covers: overlaps (both "
                + "element IDs), passThroughs (connection IDs, cross-element only), "
                + "coincidentSegments (connection IDs), nonOrthogonalTerminals (connection "
                + "IDs, the whole flagged population) plus its two disjoint halves "
                + "nonOrthogonalTerminalsZeroBendpoint (straight lines between two element "
                + "centres, no route to preserve) and nonOrthogonalTerminalsRouted (connections "
                + "carrying stored bendpoints, the half to pass to auto-route-connections as "
                + "connectionIds), boundaryViolations (child element IDs). Crossings excluded "
                + "(emergent property, not per-connection fixable). Empty metrics omitted. "
                + "Default: false.");

        Map<String, Object> scopeProp = new LinkedHashMap<>();
        scopeProp.put("type", "string");
        scopeProp.put("enum", List.of("single", "all-views"));
        scopeProp.put("default", "single");
        scopeProp.put("description",
                "Assessment scope. 'single' (default) assesses the one view named by "
                + "viewId and returns the full assessment. 'all-views' assesses every "
                + "diagram in the model and returns a compact per-view map (keyed by view "
                + "id) of {name, overallRating, overallExcludingAcceptedCosmetics, "
                + "elementCount, connectionCount, overlapCount, cousinOverlapCount, "
                + "ownIconOverLabelCount, boundaryViolationCount, "
                + "parentLabelObscuredCount, "
                + "nonOrthogonalTerminalCount, crossElementPassThroughCount, "
                + "contextualPartialDimensions} — one cheap overview call instead of one full "
                + "payload per view. The contextualPartialDimensions list names the dimensions "
                + "that view's "
                + "run could not fully examine, so the counts beside it are honestly zero yet "
                + "certify nothing; it is ALWAYS present, and empty means nothing was left "
                + "unexamined. The viewId argument is ignored when scope is 'all-views'.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("includeViolatorIds", includeViolatorIdsProp);
        properties.put("scope", scopeProp);

        // viewId is required only for single-view scope; the handler enforces it there so
        // an all-views call need not supply a viewId. Hence no unconditionally-required field.
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("assess-layout")
                .description("Assess the layout quality of a view with objective metrics. "
                        + "Returns overlap count, edge crossing count, average element spacing, "
                        + "alignment score (0-100), crossingsPerConnection density, and overall "
                        + "quality rating (poor/fair/good/excellent). Includes `ratingBreakdown` "
                        + "showing each metric's individual contribution to the rating — use this "
                        + "to understand WHY the rating is what it is and which metric to fix. "
                        + "Also detects boundary violations (elements outside their parent "
                        + "container, which may be a group or another element), "
                        + "connection pass-throughs (connections crossing unrelated elements), "
                        + "and off-canvas warnings. "
                        + "Use before and after any mutation to measure improvement. "
                        + "PRECONDITIONS: the response carries `unsizedHubs` — every element whose connection "
                        + "count is above the fan-out gate and whose box is below the absolute floor for that "
                        + "count (300 + 10 × max(0, count − 7) wide, 250 + 8 × max(0, count − 7) tall), with the "
                        + "element and view-object ids, the name, the count, the box it has and the box it needs. "
                        + "This is the FLOOR, not detect-hub-elements' sizing suggestion, which grows off the "
                        + "element's current size and so can never be satisfied. It answers on a view with no "
                        + "stored routing — where hubPortQualityScore is vacuously 1.0 and cannot — so it is the "
                        + "one signal available while sizing a hub is still free, before the first "
                        + "auto-route-connections. It feeds no rating: no breakdown entry, no tier. "
                        + "SPECULATIVE WORKFLOW: apply mutation → assess-layout → undo if "
                        + "unsatisfied → adjust parameters → retry. This is the recommended "
                        + "way to 'preview' layout or routing changes without needing a dry-run. "
                        + "Related: auto-layout-and-route (automatic ELK layout + routing), auto-route-connections "
                        + "(routing), adjust-view-spacing (inflate spacing and re-route in "
                        + "one call), undo (roll back if unsatisfied), get-view-contents "
                        + "(inspect elements), export-view (visual verification).\n\n"
                        + "Overlap metrics distinguish between `overlapCount` (same-parent "
                        + "overlaps: two children of one container, or two top-level objects, "
                        + "which count as siblings of each other) and `containmentOverlaps` "
                        + "(expected overlaps from ancestor-descendant containment, e.g., "
                        + "elements inside groups). Only same-parent overlaps affect the quality "
                        + "rating and trigger suggestions.\n\n"
                        + "A CROSS-BRANCH overlap — two objects in different containers with no "
                        + "ancestor relationship between them — is deliberately NOT counted by "
                        + "`overlapCount` under those two objects' own names, but it is never "
                        + "thereby hidden. If both objects sit inside their containers, then "
                        + "those containers overlap too and `overlapCount` reports the container "
                        + "pair; if one object escaped its container instead, "
                        + "`boundaryViolationCount` reports the escape. Either way the view is rated "
                        + "on it. `cousinOverlapCount` (with `cousinOverlaps`) counts EVERY "
                        + "overlapping cross-branch pair, so the two objects a reader sees "
                        + "colliding are named among them — which `overlapCount` cannot do, since "
                        + "it reports their containers instead. Read it as a list of pairs to "
                        + "inspect, not as a count of distinct visible collisions: one collision "
                        + "between two nested objects usually yields several pairs, because each "
                        + "object also overlaps the other's container. It is informational and "
                        + "never affects the rating. Reposition one object of each pair to "
                        + "separate them; where the two belong under one parent, nesting them "
                        + "there also brings the overlap under `overlapCount`, which IS rated. "
                        + "Both `cousinOverlaps` and "
                        + "`boundaryViolations` are description lists capped at 10 entries, so "
                        + "on a badly broken view each is shorter than its own count — use "
                        + "`cousinOverlapCount` and `boundaryViolationCount` as the counts, and "
                        + "`includeViolatorIds=true` to enumerate the objects behind them past "
                        + "the cap.\n\n"
                        + "The overall rating uses the two-dimensional M6 severity-tiered model "
                        + "(worse of a layout tier and a routing tier). Routing: Tier-1R (critical: "
                        + "passThroughs, interiorTerminations, zigzags, coincidentSegments) can produce "
                        + "'poor'; Tier-2R (cap 'fair': nonOrthogonalTerminals, nonOrthogonalInteriorSegments, "
                        + "offFaceParallelTerminals, connectionEdgeCoincidence, hubPortQuality, "
                        + "labelOverlaps, labelTruncations) — connectionEdgeCoincidence escalates to "
                        + "Tier-1R once its count reaches 7, so an eye-obvious hug-storm can drive "
                        + "'poor' instead of being masked at 'fair'; "
                        + "Tier-3R (cap 'good': edgeCrossings, connectionThroughNote). Layout: Tier-1L "
                        + "(critical: overlaps, boundaryViolations, parentLabelObscured) can produce 'poor'; "
                        + "Tier-2L (cap 'fair': spacing, offCanvas, hubNeighbourCrowding); Tier-3L (cap "
                        + "'good': alignment). The `ratingBreakdown` map shows per-metric ratings.\n\n"
                        + "SUGGESTION ORDER: on a RATED view, `suggestions` is ordered by measured "
                        + "severity, not by the order the checks run in. Any large-view "
                        + "performance warning comes first because it qualifies the whole "
                        + "assessment; then every sentence a rated metric ranks, worst CAPPED "
                        + "CONTRIBUTION first; then the sentences no rated metric ranks, in the "
                        + "order the checks produced them; and last the closing disclosures (a "
                        + "finding no sentence explained, the coverage-scoped verdict, and any "
                        + "dimension this run could not fully examine). Each rated sentence ends "
                        + "with a clause naming its metric, the tier band that metric sits in, "
                        + "what that band caps its contribution at, and whether it is one of the "
                        + "metrics holding this view where it is — a tier CAPS a contribution and "
                        + "does not pin it, so a cap-'fair' metric sitting at 'good' says so and "
                        + "is not the limiter. Read the clauses rather than the position alone: "
                        + "the ordering ranks the sentences that EXIST, and SEVERAL METRICS EMIT "
                        + "A SENTENCE ONLY PAST A REMEDY THRESHOLD of their own — `edgeCrossings`, "
                        + "`spacing` and `alignment` among them — so a metric can be "
                        + "non-`pass` in "
                        + "`ratingBreakdown` and put no sentence in the list for the ordering to "
                        + "rank. Every rating-bearing metric that has no such threshold, "
                        + "`passThroughs` and `hubNeighbourCrowding` included, emits its sentence "
                        + "whenever it fires. `ratingBreakdown` is the complete per-metric record "
                        + "and is where to confirm what holds a view down. A DEGENERATE view (at "
                        + "most one object) is not rated, so its "
                        + "suggestions are neither ordered nor annotated — they are emitted in "
                        + "detection order with no clause, and there is no limiter to name. Where "
                        + "auto-layout-and-route and adjust-view-spacing republish this list they "
                        + "republish it exactly as assess-layout built it, so both the ordering "
                        + "and the clauses reach them under the same conditions.\n\n"
                        + "DE-NOISED HEADLINE: `ratingBreakdown` also carries "
                        + "`overallExcludingAcceptedCosmetics` — the same overall rating recomputed with "
                        + "the `nonOrthogonalTerminals` contribution removed. Diagonal terminal segments "
                        + "are the straight-line signature of ELK auto-layout and routinely push an "
                        + "otherwise-clean view to 'fair'. Compare the two: when `overallRating` is 'fair' "
                        + "but `overallExcludingAcceptedCosmetics` is 'good'/'excellent', the 'fair' is "
                        + "terminal cosmetics only (run auto-route-connections mode='terminals-only' to "
                        + "clear it, or accept it); when the two are EQUAL, the rating reflects a real "
                        + "routing/layout defect to fix. It is a floor, never a lift — it can only equal "
                        + "or improve `overallRating`, never worsen it. "
                        + "The carve-out is not something you switch on, and there is no field that "
                        + "reports whether it ran: `overallExcludingAcceptedCosmetics` is on every "
                        + "response, and `ratingBreakdown.nonOrthogonalTerminals` keeps its LIVE value "
                        + "because the neutralisation happens on a copy — so a 'poor' there beside an "
                        + "`overallExcludingAcceptedCosmetics` no worse than `overallRating` is the "
                        + "recompute having RUN, not having been skipped. "
                        + "So when the two are equal and the rating is still capped, the terminals are "
                        + "not the cause and iterating on them cannot move the headline: read the rest "
                        + "of `ratingBreakdown` and act on whichever metric is still below pass. A "
                        + "Tier-2R or Tier-2L metric rated 'fair' or 'poor' holds the rating at "
                        + "'fair' on its own, so one is enough to keep it there with the terminals "
                        + "already excluded — but a tier CAPS its contribution, it does not pin it, so "
                        + "a Tier-2 metric sitting at 'good' leaves the rating at 'good'. "
                        + "The carve-out covers exactly one metric — `nonOrthogonalTerminals` — and only "
                        + "its ROUTING-tier contribution: the layout tier is recomputed from the "
                        + "untouched breakdown, so a layout defect holds the headline whatever the "
                        + "terminals do.\n\n"
                        + "Edge crossing rating is lenient for grouped views: when containers with "
                        + "inter-container connections are present and crossings are the main issue "
                        + "(zero overlaps, good alignment, pass-throughs <= 3), crossings get a "
                        + "one-tier boost — cross-container edge crossings are topologically unavoidable. "
                        + "`hasGroups` reports whether this applies, and it counts BOTH container kinds: "
                        + "a native group from add-group-to-view and an ArchiMate Grouping element placed "
                        + "by add-to-view. Every group-aware metric, suggestion and nextStep here reads "
                        + "it that way, because the group-aware remedies work on either.\n\n"
                        + "`coincidentSegmentCount` reports overlapping connection route segments "
                        + "— connections sharing identical path segments that visually overlap. "
                        + "Rated as a Tier 1 (critical) metric in the quality breakdown. "
                        + "Increase element spacing or re-run auto-route-connections to fix.\n\n"
                        + "PERCEPTION-ALIGNED METRICS (supplements to the legacy 8-metric set above): "
                        + "`interiorTerminationCount` (M2) flags connections terminating inside an "
                        + "element body rather than on its perimeter face. "
                        + "`zigzagCount` (M3) flags route shapes that backtrack or zigzag along an axis. "
                        + "`connectionEdgeCoincidenceCount` (M4) flags connection-vs-element-edge "
                        + "coincidence (separate from the legacy connection-vs-connection "
                        + "`coincidentSegmentCount`); it counts CONNECTIONS that hug at least one "
                        + "element edge (stops at the first graze) and is the rating-bearing tally. "
                        + "`edgeCoincidenceGrazedElementCount` is the informational companion that "
                        + "enumerates EVERY distinct (connection, element) graze — a single trunk "
                        + "hugging three element edges contributes 3 here but 1 to "
                        + "`connectionEdgeCoincidenceCount`; the grazed element IDs are listed under "
                        + "the `edgeCoincidenceGrazedElements` violator key. Note the count sums "
                        + "distinct grazed elements PER CONNECTION (one element grazed by two "
                        + "different connections counts twice), whereas the "
                        + "`edgeCoincidenceGrazedElements` ID set is deduplicated view-wide — so the "
                        + "set can be smaller than the count; do not use the set size as the count. "
                        + "`hubPortQualityScore` (M5) is a 0–1 score measuring port distribution "
                        + "evenness across hub-element faces (1.0 = perfectly distributed, "
                        + "0.18 = catastrophic 1-slot-for-7-connections). When this score is below "
                        + "0.5, run detect-hub-elements and resize the violating hubs via "
                        + "update-view-object. "
                        + "`corridorUtilisationScore` (R8) is a 0–1 score measuring multi-occupant "
                        + "corridor occupancy/spread — how widely two or more parallel routes sharing "
                        + "a wall-pair fan out across the available corridor width. It does NOT "
                        + "measure whether a single route sits centred in its corridor versus hugs an "
                        + "edge: a single-occupant corridor is skipped (a view with no multi-occupant "
                        + "corridor scores 1.0 vacuously) and multi-occupant wall-hugging clamps to "
                        + "1.0 (edge-hugging surfaces via the edge-coincidence metric, not here). A "
                        + "perfect 1.0 therefore does NOT certify route centring — see the "
                        + "`corridorCentering` coverage dimension (not-checked) and render-verify. "
                        + "M1 (`nonOrthogonalTerminalCount`) uses a visible-segment-length guard "
                        + "so clipped diagonals invisible to the human eye no longer over-report. "
                        + "M6 reports a two-dimensional `(layoutTier, routingTier)` rating that "
                        + "decouples layout quality from routing quality so a poor-routing fix "
                        + "doesn't drag a strong-layout view's tier. "
                        + "`parallelConnectionGap` is the 5th perception-aligned metric — it "
                        + "measures how close together parallel connection segments are at the "
                        + "worst tail of the per-axis distribution. The primary signal "
                        + "`vAxisParallelGapP10` (10th-percentile V-axis parallel gap in pixels) "
                        + "anchors against an ArchiMate manual-routed reference at 13.30 ± 0.5; "
                        + "the secondary signal `vAxisParallelGapNarrow25Count` counts V-axis "
                        + "segments below 25 px gap (more = worse), and "
                        + "`hAxisParallelGapNarrow25Count` is the same count on the H axis. "
                        + "Currently INFORMATIONAL "
                        + "(no rating impact) — narrow-corridor regressions show up as "
                        + "`vAxisParallelGapP10` drops vs the baseline. Convenience spacing "
                        + "tools cannot mitigate a narrow-corridor floor; if `vAxisParallelGapP10` "
                        + "is persistently low, redesign topology (reduce hub fan-out / split the "
                        + "view) or apply manual bendpoint surgery via update-view-connection. "
                        + "Full per-axis detail (mean/min/p10/narrowGapCount@{15,25,40} for V and "
                        + "H axes) is in `parallelConnectionGapDetail` when "
                        + "`includeViolatorIds=true`.\n\n"
                        + "`contentBounds` returns the axis-aligned bounding box ({x, y, width, height}) "
                        + "of all visual content (elements, groups, notes) in absolute canvas coordinates. "
                        + "Use this for safe placement calculations — e.g., place a title note at "
                        + "(contentBounds.x, contentBounds.y - 40) without inspecting individual elements. "
                        + "Null/omitted on empty views.\n\n"
                        + "RATING-AFFECTING DETECTIONS: "
                        + "`labelTruncationCount` / `labelTruncations` — elements whose label text "
                        + "exceeds the available display width (element width minus type-icon area). "
                        + "Since M6 a nonzero count caps routingTier at 'fair' (Tier-2R). "
                        + "Use resize-elements-to-fit or increase element width to fix. "
                        + "`parentLabelObscuredCount` / `parentLabelObscuredDescriptions` — parent "
                        + "elements (groups) whose label text area is overlapped by the topmost child. "
                        + "Since M6 a nonzero count drops layoutTier to 'poor' and vetoes the overall rating (Tier-1L), "
                        + "so fix it before a view can rate 'good'. "
                        + "Move children down or increase parent top padding. "
                        + "The band tested is the one that RENDERS - one label line, two when the "
                        + "name is too wide for the box and wraps, and never deeper than the parent "
                        + "itself, because Archi clips a figure's contents to the figure. So a child "
                        + "sitting at or below its parent's bottom edge is NOT counted here; a "
                        + "child with any height is reported by `boundaryViolationCount` instead, "
                        + "which describes it correctly, and both are Tier-1L, so a view is not "
                        + "rated any better for it - move the child back inside the parent and clear "
                        + "of its title. The one gap: a ZERO-height child sitting exactly on the "
                        + "parent's bottom edge draws nothing and is reported by neither metric, so "
                        + "do not read a clean pair of counts as proof no such object exists. "
                        + "`hubNeighbourClearanceMin` — the smallest clearance (px) between a hub "
                        + "element's edge and the row of spoke neighbours packed against it (measured "
                        + "only on a hub face carrying >= 3 overlapping spoke neighbours; -1.0 = no "
                        + "measurable hub). A value at/above 0 and below the 60 px crowding floor caps "
                        + "layoutTier at 'fair' so a hub enlarged until it crowds its neighbours cannot "
                        + "rate 'good'; the -1.0 sentinel and clearances at/above the floor have no "
                        + "rating impact. This is orthogonal to hubPortQualityScore (which scores port "
                        + "DISTRIBUTION, not the room the enlarged box leaves for neighbours), so a hub "
                        + "can max hubPortQuality yet still crowd. On a near-saturated container-nested-"
                        + "hub view, nextSteps emits a single diagnostic resize-vs-reposition step gated "
                        + "on this clearance instead of generic spacing inflation. "
                        + "`connectionThroughNoteCount` / `connectionThroughNoteDescriptions` — "
                        + "connections whose route passes straight through a Note's box or an "
                        + "element's rendered image rectangle (the \"line runs through the caption/"
                        + "legend\" defect). Any nonzero count contributes to `routingRating` "
                        + "(cap-good, Tier-3R): a line through a note/image is always jarring to the "
                        + "reader, so a single crossing nudges the routing tier to 'good' (binary "
                        + "presence — one crossing and several both rate 'good', never worse). "
                        + "For NOTE crossings you do not have to come here to find them: "
                        + "auto-route-connections already named them on the call that applied the "
                        + "route, in a CONNECTION_ROUTED_THROUGH_NOTE structured warning carrying "
                        + "the note ids. Both use the same geometry, so the two always agree — but "
                        + "this count also covers element-embedded IMAGES, which that warning does "
                        + "not, so a count higher than the routing call reported means an image. "
                        + "Counted per connection×visual. Notes are excluded from the element "
                        + "pass-through scoring set, so it catches note clutter the box-based "
                        + "`connectionPassThroughs` (Tier-1R) misses. For image-bearing elements it "
                        + "tests the rendered image RECTANGLE, which is clipped to the element box — "
                        + "an image never renders outside its element — so a route through an "
                        + "element's image also crosses that element's box; where a route trips both, "
                        + "the routing tier takes the max so the Tier-1R "
                        + "pass-through dominates (no double penalty). A visual on a connection's own "
                        + "endpoint/container is not flagged. Reroute the connection or move the "
                        + "note/image clear.\n\n"
                        + "INFORMATIONAL DETECTIONS (no rating impact): each is NAMED IN "
                        + "`suggestions` on any run where its count is nonzero, carrying the count, "
                        + "the remedy quoted below, and a pointer to the description field or "
                        + "violator key that lists the objects — plus the shortfall and its size "
                        + "where the count outruns the 10-entry description cap and no violator key "
                        + "can recover the rest. A finding that no suggestion accounted for is named "
                        + "by a terminal disclosure instead, inline with its count, whether or not "
                        + "other defects were reported. Being informational governs the rating, not "
                        + "the prose. "
                        + "`imageSiblingOverlapCount` / `imageSiblingOverlapDescriptions` — elements "
                        + "whose image area (custom image or specialization icon, sized from its true "
                        + "rendered dimensions) is overlapped by a sibling element. "
                        + "Increase element spacing, reposition the image, or shrink the icon. "
                        + "`overlayIconCollisionCount` / `overlayIconCollisionDescriptions` — the "
                        + "containment companion: an element's overlay icon colliding with the icon "
                        + "of an element that contains it (e.g. a nested cluster and its zone both "
                        + "carrying a bottom-left icon). Ordinary nesting is not flagged — only "
                        + "icon-on-icon. Move the nested element, put one icon in a different "
                        + "corner, or shrink it. "
                        + "`ownIconOverLabelCount` / `ownIconOverLabelDescriptions` — the third "
                        + "icon axis: an element's OWN icon drawn on top of its OWN title "
                        + "(a large specialization glyph burying the element name on a narrow box). "
                        + "The two counts above compare an icon against other elements' geometry, "
                        + "so neither can see this. Where the title sits is read from the element "
                        + "itself on BOTH axes — horizontally from its own `textAlignment`, "
                        + "vertically from its own `verticalTextAlignment` — so a title centred or "
                        + "footed in its box is tested where it actually renders rather than at the "
                        + "top. The remedy depends on the alignment and each description names the "
                        + "one it found: widen the element, move the icon to a corner that title "
                        + "does not reach, or change the object's `textAlignment` or "
                        + "`verticalTextAlignment`. "
                        // Kept on one line: this phrase is asserted verbatim against this source
                        // text, and a concatenation boundary inside it reads as absent.
                        + "Both alignments are properties of the VIEW OBJECT, not of the model element, so the fix must be repeated on every view that shows the element"
                        + " — correcting one view leaves the others colliding. "
                        + "An object whose title width could NOT be measured is not examined at all "
                        + "— a visual Group (never measured), or an element whose text measurement "
                        + "failed. A view holding one reports this dimension as "
                        + "`partial` in `coverage`, meaning the zero is 'not examined' rather than "
                        + "'clean' — verify those objects against the render. "
                        + "`noteOverlapCount` / `noteOverlapDescriptions` — notes whose box overlaps "
                        + "an element or a group (the \"sticky note dropped on top of the diagram\" "
                        + "defect). "
                        + "A note NESTED inside a container is not flagged: a note whose parent is the "
                        + "container it sits in is deliberate placement, not a collision. Counted per "
                        + "(note, object) PAIR, so one note lying across three elements reports 3, not "
                        + "1 — the count can exceed the number of notes on the view. Move the note "
                        + "clear with update-view-object, or nest it inside the container it belongs "
                        + "to. This dimension publishes no violator-id key, so past the 10-entry "
                        + "description cap the remaining pairs have to be found in the render. "
                        + "`noteClipCount` / `noteClipDescriptions` — notes whose text content needs "
                        + "more height than their box provides (clipped). Re-send the note's `text` "
                        + "(or its `width`) through update-view-object with `height` omitted and the "
                        + "server re-fits the height to the wrapped content; or raise the height / "
                        + "reduce the font size. A clip can only arise from an explicitly pinned "
                        + "height, since an auto-fitted note is by construction tall enough. "
                        + "`connectionRedundantBendpointCount` / `connectionRedundantBendpointDescriptions` "
                        + "— bendpoints that are collinear along a HORIZONTAL or VERTICAL segment and "
                        + "lie between their neighbours, so removing the point would not change the "
                        + "orthogonal route (the \"many unnecessary bendpoints / wobbles\" defect). "
                        + "Near-collinear DIAGONAL micro-jogs and sub-pixel artifacts are NOT reported "
                        + "— removing them would diagonalise an orthogonal segment, so they are not "
                        + "redundant. Terminal egress-stub bendpoints (a first/last bendpoint sitting "
                        + "on its element's perimeter face) are also NOT reported: the router pins "
                        + "them for terminal anchoring / port distribution, so they are intentional "
                        + "and re-running auto-route will not remove them. Counted per redundant "
                        + "bendpoint and distinct from the reversal-based `zigzagCount`. The reported "
                        + "(interior) points are genuinely removable — straighten the route or re-run "
                        + "auto-route-connections.\n\n"
                        + "FURTHER RATING-AFFECTING DETECTIONS (these two DO affect routingRating, "
                        + "despite following the informational block above): "
                        + "`nonOrthogonalInteriorSegmentCount` / `nonOrthogonalInteriorSegmentDescriptions` "
                        + "— connections with at least one off-cardinal (more than ~5° from horizontal/"
                        + "vertical) segment in the INTERIOR of the route, i.e. between the two terminal "
                        + "segments. This generalises `nonOrthogonalTerminalCount` (which checks only the "
                        + "source/target segments) to mid-route bends. It contributes to `routingRating` "
                        + "(cap-fair, tier 2), ratio-bucketed identically to `nonOrthogonalTerminalCount` "
                        + "(a low interior-diagonal-per-connection ratio rates good, a high one fair); the "
                        + "two are SEPARATE breakdown entries but the routing tier combines them by max, so "
                        + "a connection diagonal at both a terminal and an interior segment is capped once. "
                        + "Counted per connection. Re-run auto-route-connections for clean orthogonal paths. "
                        + "`offFaceParallelTerminalCount` / `offFaceParallelTerminalDescriptions` "
                        + "— connections whose terminal route departs an element face then immediately runs "
                        + "PARALLEL to and hugs that same face (the first exterior segment travels along the "
                        + "departed face with a perpendicular clearance below ~8px). This catches the visible "
                        + "\"hugging exit\" that `nonOrthogonalTerminalCount` misses: when a route exits a "
                        + "fraction of a pixel off the perimeter and turns to run just beside the face, the "
                        + "exit stub is a sub-perceptible diagonal that the terminal-angle check suppresses. "
                        + "Measured against the face the route departs, not the raw segment angle. Counted per "
                        + "connection (a route hugging at either terminal counts once). It contributes to "
                        + "`routingRating` (cap-fair, tier 2) on BINARY PRESENCE, not on a ratio: any nonzero "
                        + "count caps the routing tier at fair, because a low hug-per-connection ratio is still "
                        + "a visible defect. It is deliberately NOT part of the accepted-cosmetics carve-out, "
                        + "so it caps `overallExcludingAcceptedCosmetics` too — a layout-bound hug is real and "
                        + "actionable, not an ELK cosmetic. "
                        + "Push the first segment perpendicular off the face before turning; if "
                        + "auto-route-connections reports EGRESS_LIFT_LAYOUT_BOUND the hug cannot be routed "
                        + "away and the remedy is to spread the elements.\n\n"
                        + "COORDINATE FRAME: every metric here is computed over a path this tool "
                        + "DERIVES, not over stored geometry. Each bendpoint is resolved by "
                        + "interpolating its two stored reconstructions (source-relative and "
                        + "target-relative) at the weight Archi renders with — bendpoint i of n sits "
                        + "(i+1)/(n+1) of the way from the source-anchored reconstruction to the "
                        + "target-anchored one — so these metrics describe the polyline a reader sees. "
                        + "The interpolation runs in floating point against element centres that are "
                        + "also floating point, deliberately: the sub-pixel disagreement between the two "
                        + "reconstructions is what anchorDriftCount measures, and rounding the centres "
                        + "would quantise it away. A coordinate reported here can therefore carry a "
                        + "half-pixel and can differ from the same bendpoint as get-view-contents "
                        + "reports it, which uses the same weight but whole-pixel centres and returns "
                        + "whole pixels. That difference is bounded by 0.5 + n/(n+1) px on a "
                        + "connection carrying n bendpoints — half a pixel from the truncated centres, "
                        + "plus whatever the single integer division discards. It is exactly 1 px for "
                        + "a lone bendpoint and rises towards (never reaching) 1.5 px as n grows. "
                        + "Neither frame is the router's: it works in whole pixels throughout. Treat a "
                        + "coordinate in this response as a measurement, not as a value to write "
                        + "back.\n\n"
                        + "FURTHER INFORMATIONAL DETECTIONS (no rating impact): "
                        + "`anchorDriftCount` / `anchorDriftDescriptions` "
                        + "— connections whose STORED ROUTE NO LONGER MATCHES THE GEOMETRY it was "
                        + "computed for. Every bendpoint is stored twice, once relative to the source "
                        + "centre and once relative to the target centre; both describe the same point "
                        + "at the moment the route was written, so moving or resizing an endpoint "
                        + "afterwards leaves the two reconstructions disagreeing by however far the "
                        + "element travelled. The descriptions carry the MEASURED drift in px on each "
                        + "axis, and the `anchorDrift` violator key names the connections. While the two "
                        + "disagree the connection is drawn SHEARED rather than displaced: each bendpoint "
                        + "is pulled from the halfway position by (weight - 0.5) x drift, furthest at the "
                        + "first and last bendpoints and least in the middle, so the terminal segments are "
                        + "the part a reader notices. This is NOT a "
                        + "route-shape defect and no shape-based dimension can see it: the stored path "
                        + "was correct when written. Re-route the named connections (auto-route-connections) "
                        + "so they are recomputed against the current geometry — straightening cannot "
                        + "repair a route whose shape was never the problem. "
                        + "`lateralJogReversalCount` / `lateralJogReversalDescriptions` "
                        + "— connections that DOUBLE BACK through a sidestep too narrow to be routing "
                        + "around anything: two arms running in opposite directions along one axis, "
                        + "separated by a perpendicular jog of at most 8px. The descriptions carry the "
                        + "four coordinates of each window. Distinct from `zigzagCount`, which needs "
                        + "three points sharing ONE axis — the sidestep puts the two arms on two "
                        + "parallel lines, so no triple in the window shares an axis and the zigzag "
                        + "test cannot express the shape at any tolerance. Counted per connection, and "
                        + "never double-counted: a connection already reported as a pass-through or a "
                        + "zigzag is skipped. Re-run auto-route-connections. "
                        + "`coincidentFacePortCount` / `coincidentFacePortDescriptions` "
                        + "— element faces on which TWO OR MORE connection terminals collide onto the "
                        + "same perimeter port (within ~1px along the face axis), so two edges appear to "
                        + "leave one point. This closes a blind spot in `hubPortQualityScore` (M5): its "
                        + "per-face guard only scores a face carrying four or more connections, so a face "
                        + "with two or three coincident terminals reads a vacuous 1.0 despite the "
                        + "collision. Counted per face (the `coincidentFacePorts` violator key carries the "
                        + "colliding connection IDs), informational (no rating impact — M5 is untouched). "
                        + "Spread the terminals across the face with auto-route-connections, which "
                        + "dissolves a coincident same-face pair on a low-degree element. "
                        + "`containerFillEqualsChildCount` / `containerFillEqualsChildDescriptions` "
                        + "— containers whose AUTHORED fill colour equals a nested child's fill, so the "
                        + "parent and its children merge into one flat single-colour block (the "
                        + "\"flat-blob\" defect). Counted per container, informational (no rating impact). "
                        + "Note: when you place a child inside a container whose fill is unauthored, "
                        + "add-to-view / add-group-to-view already auto-recede the parent to a backdrop "
                        + "(opt out with recede:false), so this only flags blobs from an explicit "
                        + "same-colour fill — give the container a distinct (lighter) fill. "
                        + "`connectionGrazesVisualCount` / `connectionGrazesVisualDescriptions` "
                        + "— connections whose route touches/clips a Note's or image's BORDER (the "
                        + "outer band the through-visual interior test discards), including visuals "
                        + "too small to inset that a route crosses. Counted per connection×visual and "
                        + "DISJOINT from `connectionThroughNoteCount` (interior penetration): a single "
                        + "crossing is classified as exactly one of through or graze. Informational "
                        + "(no rating impact). Reroute the connection or move the note/image clear. "
                        + "`labelOnNoteCount` / `labelOnNoteDescriptions` — connection LABELS rendered "
                        + "on a Note's rectangle (the caption/legend collision the route detectors "
                        + "cannot see, since a label is positioned independently of the line). Counted "
                        + "per label×note. Informational (no rating impact) and independent of "
                        + "`connectionThroughNoteCount` / `connectionGrazesVisualCount`. Reposition the "
                        + "label (apply a Label Offset / auto-route-connections) or move the note clear. "
                        + "`labelOnGroupCount` / `labelOnGroupDescriptions` — connection LABELS rendered "
                        + "on a CONTAINER's TITLE BAND — a native group or an ArchiMate Grouping alike "
                        + "(the title collision the label-overlap detector cannot see, since it skips "
                        + "containers wholesale as transparent). Only "
                        + "the container's top title strip is tested, so a label sitting inside the "
                        + "body is normal and NOT flagged. Counted per label×container. Informational (no "
                        + "rating impact). Reposition the label or reroute the connection clear of the "
                        + "container title.\n\n"
                        + "CONNECTION-LABEL OVERLAP: `labelOverlapCount` is render-calibrated "
                        + "(the estimated glyph box is widened to match how Archi actually renders, "
                        + "so short, tight segments are no longer under-flagged) and now also flags "
                        + "a label rendered on its OWN source/target box when more than ~30% of its "
                        + "area falls on that endpoint — own-endpoint bleed the earlier "
                        + "source/target exclusion masked. Light grazing of the attached box is "
                        + "tolerated. Three companion rules cover what the area fraction misses: a "
                        + "box-coverage rule flags a label blanketing a TINY endpoint box (e.g. a "
                        + "junction) when the overlap covers ~60%+ of that box; a short-segment "
                        + "rule lowers the bar to ~15% when the label is wider than the first/last "
                        + "segment it anchors to (a long source/target label on a short terminal "
                        + "segment); and a junction rule drops the bar to ~5% when the endpoint is "
                        + "a Junction (a solid dark shape with no readable interior), catching an "
                        + "oversized junction grazed by a label. The check is offset-aware: a Middle label already lifted clear "
                        + "by an applied Label Offset is not re-reported. On Archi 5.10, clear an "
                        + "own-endpoint bleed with auto-route-connections, which applies the "
                        + "connection Label Offset. Labels suppressed with `showLabel: false` "
                        + "reserve and flag nothing.\n\n"
                        + "VIOLATOR IDS (opt-in via includeViolatorIds=true): "
                        + "Returns a `violatorIds` map keyed by metric name, each value a list "
                        + "of visual object IDs. Use these IDs with update-view-object or "
                        + "remove-from-view for targeted per-element/per-connection fixes. "
                        + "Metrics: overlaps (both element IDs from each pair), passThroughs "
                        + "(connection IDs, cross-element only), coincidentSegments (connection "
                        + "IDs), nonOrthogonalTerminals (connection IDs — the whole flagged "
                        + "population, unchanged), nonOrthogonalTerminalsZeroBendpoint and "
                        + "nonOrthogonalTerminalsRouted (the two disjoint halves of that "
                        + "population, counted by zeroBendpointNonOrthogonalTerminalCount and "
                        + "routedNonOrthogonalTerminalCount; the halves have opposite remedies, "
                        + "and the routed half's IDs are what scope an auto-route-connections "
                        + "call via connectionIds so it cannot disturb the other half), "
                        + "boundaryViolations "
                        + "(child element IDs), interiorTerminations (connection IDs), "
                        + "zigzags (connection IDs), edgeCoincidence (connection IDs), "
                        + "edgeCoincidenceGrazedElements (the element IDs every edge-coincident "
                        + "route hugs — the full breadth of each graze), "
                        + "redundantBendpoints (connection IDs), "
                        + "nonOrthogonalInteriorSegments (connection IDs), "
                        + "containerFillRecession (container element/group IDs), "
                        + "labelOnNote (note IDs carrying a connection label), "
                        + "labelOnGroup (container IDs whose title band carries a connection label), "
                        + "coincidentFacePorts (connection IDs colliding onto a shared face port), "
                        + "hubPortLowQuality (element IDs), parallelConnectionGapV "
                        + "(connection IDs with V-axis gap < 25 px), parallelConnectionGapH "
                        + "(connection IDs with H-axis gap < 25 px). Crossings excluded — "
                        + "use auto-route-connections for crossing reduction. Empty metrics "
                        + "omitted from map.\n\n"
                        + "COVERAGE DECLARATION: the `coverage` map declares, per defect "
                        + "dimension, whether this run actually evaluated it. Each value is one "
                        + "of `checked` (the detector ran and fully covers this dimension's "
                        + "failure modes — regardless of whether it found anything, so `checked` "
                        + "with a zero/absent metric means genuinely clean), `partial` (a "
                        + "detector ran but covers only SOME of this dimension's failure modes — "
                        + "a zero/absent metric means only the covered modes are clean, so the "
                        + "uncovered modes must be render-verified before certifying clean), "
                        + "`not-checked` (this defect class was NOT evaluated — there "
                        + "is no detector for it yet, so absence of a finding is NOT evidence "
                        + "of absence; treat it as unknown, never as clean), or "
                        + "`not-applicable` (the view structurally cannot exhibit it). The map "
                        + "is keyed by dimension id and is always populated — never empty. A "
                        + "DEGENERATE view (zero or one object, counting groups and notes as "
                        + "objects) short-circuits before any detector runs, and declares that "
                        + "fact rather than staying silent: with zero objects every dimension "
                        + "reads `not-applicable`, and with one object a dimension reads "
                        + "`not-applicable` only where the defect needs two or more objects and "
                        + "`not-checked` everywhere else — including the whole connection family, "
                        + "which a self-referencing connection keeps reachable. On such a view "
                        + "`overallRating: not-applicable` is NOT a clean bill of health: "
                        + "`labelTruncations` and `offCanvas` would affect the rating on a normal "
                        + "run and are merely suppressed here, so render-verify before "
                        + "certifying. It is informational only and never affects any rating. A "
                        + "done-gate must read BOTH `coverage` and `ratingBreakdown`: a "
                        + "dimension is only 'clean' when coverage==checked AND breakdown==pass. "
                        + "A `partial` dimension is NOT certifiable as clean from the metric "
                        + "alone — render-verify its uncovered modes. Most defect-class dimensions "
                        + "report `checked`. The exceptions come in two kinds. CONTEXTUAL — declared "
                        + "`checked`, reported `partial` only on a run that triggers them: "
                        + "`labelOverlaps` when the run carries a connection label wider than its "
                        + "hosting segment, since such a label can crowd a neighbour while clearing "
                        + "it geometrically, so an overlap count of zero cannot certify that crowding "
                        + "mode clean; "
                        // Kept on one line: this phrase is asserted verbatim against this source
                        // text, and a concatenation boundary inside it reads as absent.
                        + "`ownIconOverLabel` when the run carries a named icon-bearing object whose title width could not be measured"
                        + ", so that object was never examined; and "
                        // Kept on one line each: these phrases are asserted verbatim against this
                        // source text, and a concatenation boundary inside one reads as absent.
                        + "`parentLabelObscured` when the run carries a parent whose title band width was never measured"
                        + " — a visual group, which is never measured, or an element whose label "
                        + "measurement failed. That parent IS examined and does get a verdict, but "
                        + "its title band is sized as a single line"
                        + " however long the title is, so a title that wraps onto further rows is "
                        + "compared against only its first row and a child sitting under the rest is "
                        + "not flagged; "
                        + "render-verify such a parent's title against its topmost child"
                        + " before certifying this dimension clean. PERMANENT — reported on every run, "
                        + "because the gap is structural rather than run-dependent: `edgeCoincidence` "
                        + "is always `partial`, because its detector classifies each segment as "
                        + "horizontal or vertical and skips everything else outright, so a DIAGONAL "
                        + "segment is never compared against any element edge; its zero certifies "
                        + "only that no axis-aligned segment hugs within the tolerance band, and a "
                        + "done-gate must render-verify diagonal routes before certifying this "
                        + "dimension clean. `labelTruncations` is always `partial`, because its "
                        + "detector compares a label against its box only for measured, non-group "
                        + "objects: a visual GROUP's title is never measured and the node is skipped "
                        + "before its box is consulted, and "
                        + "an element whose label width could not be measured "
                        + "is skipped by the separate width test that follows. Its count and rating stay "
                        + "meaningful — a nonzero count is a real truncation — but a zero certifies "
                        + "only that every MEASURED element label fits, so render-verify group titles "
                        + "before certifying this dimension clean. "
                        + "`corridorCentering` is always `not-checked` — no detector "
                        + "measures whether a single route sits centred in its corridor versus hugs "
                        + "an edge (the `corridorUtilisationScore` metric only measures multi-occupant "
                        + "spread), so render-verify centring regardless of that score. "
                        + "`not-applicable` appears only on the degenerate views described "
                        + "above.\n\n"
                        + "SCOPE: the `scope` parameter selects single-view (default) or whole-model "
                        + "assessment. With scope='all-views', viewId is ignored and the result is a "
                        + "compact map keyed by view id — each value {name, overallRating, "
                        + "overallExcludingAcceptedCosmetics, elementCount, connectionCount, "
                        + "overlapCount, cousinOverlapCount, ownIconOverLabelCount, "
                        + "boundaryViolationCount, parentLabelObscuredCount, "
                        + "nonOrthogonalTerminalCount, crossElementPassThroughCount, "
                        + "contextualPartialDimensions} — for a "
                        + "one-call overview of every diagram (e.g. a final close-out sweep). It omits "
                        + "violatorIds, descriptions, and the per-metric breakdown; drill into any view "
                        + "that rates 'fair'/'poor' with a single-scope call for the full assessment. "
                        + "`ownIconOverLabelCount` is carried because it contributes to no rating, so a "
                        + "view can read 'excellent' while carrying it — drill into a non-zero count "
                        + "whatever the rating says. `contextualPartialDimensions` is carried for "
                        + "the reason that matters most to a close-out sweep: it names the "
                        + "dimensions that view's run could not fully examine, so every zero beside "
                        + "it on that view is honest but proves nothing. It lists only the "
                        + "CONTEXTUAL downgrades — the permanent `partial` declarations and the "
                        + "standing `not-checked` are present on every run and are read from the "
                        + "full `coverage` map with a single-scope call. The key is ALWAYS present: "
                        + "an empty list means nothing was left unexamined on that view, which is a "
                        + "measured result and not a missing field. An "
                        + "empty model returns an empty map.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAssessLayout)
                .build();
    }

    McpSchema.CallToolResult handleAssessLayout(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling assess-layout request");
        try {
            HandlerUtils.requireModelLoaded(accessor);

            Map<String, Object> args = request.arguments();
            String scope = HandlerUtils.optionalStringParam(args, "scope");
            if (scope == null) {
                scope = "single";
            }

            if ("all-views".equals(scope)) {
                return handleAssessAllViews();
            }

            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            boolean includeViolatorIds = Boolean.TRUE.equals(args.get("includeViolatorIds"));

            AssessLayoutResultDto dto = accessor.assessLayout(viewId, includeViolatorIds);

            List<String> nextSteps = buildAssessLayoutNextSteps(dto);
            String modelVersion = accessor.getModelVersion();
            Map<String, Object> envelope = formatter.formatSuccess(
                    dto, nextSteps, modelVersion, 1, 1, false);
            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling assess-layout", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * Whole-model assess: a compact per-view summary keyed by view id, for a one-call
     * overview of every diagram (e.g. a final close-out sweep) instead of one full payload
     * per view. Each value carries the headline rating, the de-noised headline
     * ({@code overallExcludingAcceptedCosmetics}), and the key counts a consumer triages on;
     * violatorIds, descriptions, and the per-metric breakdown are intentionally omitted — drill
     * into any flagged view with a single-scope call for the full assessment. An empty model
     * (no diagrams) returns an empty map. Reuses the existing per-view assessor entry point.
     */
    private McpSchema.CallToolResult handleAssessAllViews() {
        Map<String, Object> perView = new LinkedHashMap<>();
        boolean anyOwnIconOverLabel = false;
        boolean anyContextualPartial = false;
        for (ViewDto view : accessor.getViews(null)) {
            AssessLayoutResultDto dto = accessor.assessLayout(view.id(), false);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", view.name());
            summary.put("overallRating", dto.overallRating());
            // Fall back to overallRating when the de-noised key is absent (degenerate views
            // produce an empty/absent ratingBreakdown). The values are equal when de-noising
            // has no effect, so this keeps the compact entry's key set complete and non-null
            // (the response mapper omits null fields), never silently dropping the key.
            summary.put("overallExcludingAcceptedCosmetics",
                    dto.ratingBreakdown() != null
                            ? dto.ratingBreakdown().getOrDefault(
                                    "overallExcludingAcceptedCosmetics", dto.overallRating())
                            : dto.overallRating());
            summary.put("elementCount", dto.elementCount());
            summary.put("connectionCount", dto.connectionCount());
            summary.put("overlapCount", dto.overlapCount());
            summary.put("cousinOverlapCount", dto.cousinOverlapCount());
            // Carried for the same reason cousinOverlapCount is: informational counts belong here
            // when the headline cannot surface them. This one moves no rating at all, so a view
            // whose element names are buried under their own icons rates clean and the compact
            // entry is the only place a whole-model sweep could see it.
            summary.put("ownIconOverLabelCount", dto.ownIconOverLabelCount());
            anyOwnIconOverLabel |= dto.ownIconOverLabelCount() > 0;
            summary.put("boundaryViolationCount", dto.boundaryViolationCount());
            // Beside the other Tier-1L layout count, and carried for the opposite reason to
            // ownIconOverLabelCount above: this one drives overallRating, which this same entry
            // publishes. A single obscured parent label vetoes the view to "poor" with no
            // threshold and no cap, so without the count the sweep reports a rating it cannot
            // explain — the drill-in call was the only way to learn why. It needs no sweep-level
            // nextStep of its own: the rating it moved is already the sweep's drill-in trigger.
            summary.put("parentLabelObscuredCount", dto.parentLabelObscuredCount());
            summary.put("nonOrthogonalTerminalCount", dto.nonOrthogonalTerminalCount());
            // The CHARGED count, and named for it. The size of dto.connectionPassThroughs() is a
            // capped list of descriptions mixing the charged crossings with the unrated
            // self-element ones, so publishing it under a "...Count" key beside a field of almost
            // that name told a sweep it was reading that field's size when it was reading neither
            // quantity reliably. This key is now the same number the drill-in call reports, so a
            // caller comparing the two cannot see them disagree.
            summary.put("crossElementPassThroughCount", dto.crossElementPassThroughCount());
            // Which of this view's dimensions could not be certified on the run that produced the
            // counts above. Placed LAST on purpose: it qualifies the whole entry, not the key it
            // sits beside, and a qualifier in the middle of the list it qualifies reads as
            // belonging to its neighbour. Carried as the dimension NAMES rather than a count
            // because a caller told only that something went unexamined has to make a second,
            // full-payload call to learn what — which is the drill-in this sweep exists to avoid.
            //
            // The names are the CONTEXTUAL downgrades only, never the whole coverage map: that map
            // holds 38 entries per view, of which at least three are non-checked on every run by
            // construction, so splicing it in would multiply the payload and destroy the one cheap
            // overview call this tool exists to be.
            //
            // ALWAYS PRESENT, empty when nothing fired. The response mapper omits null values, so
            // a null here would silently drop the key and the entry's key set would stop being
            // constant across views — the same trap overallExcludingAcceptedCosmetics above
            // deliberately dodges. An empty list is a positive statement (no dimension was
            // contextually downgraded), not an abstention, so it is safe to publish as one.
            List<String> unexamined = dto.contextualPartialDimensions();
            summary.put("contextualPartialDimensions",
                    unexamined == null ? List.of() : List.copyOf(unexamined));
            anyContextualPartial |= unexamined != null && !unexamined.isEmpty();
            perView.put(view.id(), summary);
        }

        List<String> nextSteps;
        if (perView.isEmpty()) {
            nextSteps = List.of("No diagram views in the model.");
        } else {
            List<String> sweepSteps = new ArrayList<>();
            sweepSteps.add("Drill into any view whose overallRating is 'fair'/'poor' with a "
                    + "single-scope assess-layout call (scope omitted, viewId set) for "
                    + "the full per-metric breakdown and violator ids.");
            sweepSteps.add("A view whose overallRating is 'fair' but "
                    + "overallExcludingAcceptedCosmetics is 'good'/'excellent' is "
                    + "terminal-cosmetic-only — run auto-route-connections "
                    + "mode='terminals-only' to clear it, or accept it.");
            // Emitted only when the sweep actually found one. Advice to act on a finding nothing
            // reported is noise, and noise in a close-out sweep's steps is what stops the real
            // entries being read.
            if (anyOwnIconOverLabel) {
                sweepSteps.add("Drill into any view with a non-zero ownIconOverLabelCount whatever "
                        + "its rating — the count contributes to no rating, so such a view can "
                        + "read 'excellent' while its element names are buried under their "
                        + "own icons. A key nobody is told to read is only half a "
                        + "disclosure.");
            }
            // Gated for the same reason as the step above: advice to act on a finding nothing
            // reported is noise, and noise in a close-out sweep's steps is what stops the real
            // entries being read.
            if (anyContextualPartial) {
                sweepSteps.add("Drill into any view with a non-empty contextualPartialDimensions "
                        + "whatever its rating — those dimensions were not fully examined on that "
                        + "view, so their counts above are honestly zero yet certify nothing. An "
                        + "empty list means nothing was left unexamined; the key is always "
                        + "present, so its emptiness is a measured result and not a missing "
                        + "field. Render-verify the named dimensions with export-view.");
            }
            nextSteps = List.copyOf(sweepSteps);
        }
        String modelVersion = accessor.getModelVersion();
        Map<String, Object> envelope = formatter.formatSuccess(
                perView, nextSteps, modelVersion, perView.size(), perView.size(), false);
        return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
    }

    /**
     * Builds context-aware nextSteps graduated by quality rating and view structure.
     * Recommends the lightest effective intervention first: auto-route-connections, then
     * auto-layout-and-route (ELK). Never recommends compute-layout.
     */
    // Thresholds for "good" rating spacing/alignment fix recommendations.
    // Stricter than assessor's EXCELLENT thresholds (30.0 / 60) because a "good" view
    // with adequate spacing should only get routing advice, not layout rearrangement.
    private static final double GOOD_SPACING_FIX_THRESHOLD = 40.0;
    private static final int GOOD_ALIGNMENT_FIX_THRESHOLD = 70;
    // Mirrors LayoutQualityAssessor.HUB_PORT_QUALITY_PASS_THRESHOLD (0.95) — anything below
    // is a routing-attributable hub-distribution defect for next-steps purposes.
    private static final double HUB_PORT_QUALITY_NEXTSTEPS_THRESHOLD = 0.95;
    // Mirrors LayoutQualityAssessor.HUB_PORT_QUALITY_GOOD_THRESHOLD (0.75) — the lower edge of
    // the band the rating leaves alone. A score below it lands in "fair" or "poor", and BOTH of
    // those cap the view's routing tier, so both are views whose hubs the caller has been marked
    // down for and told nothing about. Exclusive on purpose: a score of exactly 0.75 is rated
    // good and must stay silent, which is not a hypothetical — views scoring exactly 0.75 are
    // ordinary. Package-private so the parity pin can prove it still tracks the assessor.
    static final double HUB_PORT_QUALITY_REMEDY_THRESHOLD = 0.75;
    // Near-saturated corridor utilisation — corridors are full, so the spacing/edge-coincidence
    // pressure is best relieved by a hub-resize (where there is slack) OR an ELK reposition
    // (where a resize would crowd neighbours), not by the generic spacing-inflation step. Paired
    // with edge-coincidence pressure and hub-port quality NOT already flagged, this is the
    // container-nested-hub signal. The emitter cannot see per-element geometry or confirm a hub,
    // so the step it emits is diagnostic (run detect-hub-elements and choose), not prescriptive.
    private static final double NESTED_HUB_CORRIDOR_SATURATION_THRESHOLD = 0.9;

    // Mirrors LayoutQualityAssessor.CROWDING_FLOOR_PX (cross-package; kept in sync by the shared
    // live-calibration). When the assessor measures a hub's edge-to-spoke-row clearance, a value at
    // or above this floor means a resize has room (sparse → grow the hub), and below it a resize
    // would crowd (dense → reposition). A negative value is the "no hub measured" sentinel, which
    // keeps the diagnostic hub-existence-safe (present both levers) rather than branching.
    private static final double NESTED_HUB_CROWDING_CLEARANCE_FLOOR_PX = 60.0;

    // Package-private for direct unit testing.
    List<String> buildAssessLayoutNextSteps(AssessLayoutResultDto dto) {
        List<String> steps = new ArrayList<>();
        String rating = dto.overallRating();
        boolean hasGroups = dto.hasGroups();
        boolean hasConnections = dto.connectionCount() > 0;
        // TWO quantities, never one. connectionPassThroughs is a capped description list carrying
        // the unrated self-element pass-throughs alongside the charged ones, so its size is not the
        // number the rating was computed on: it overstates when self-element entries are present
        // and understates once the cap truncates it. Both are needed here and they are read by
        // different consumers below, so neither keeps the old shared name.
        int chargedPassThroughs = dto.crossElementPassThroughCount();
        int describedPassThroughs = dto.connectionPassThroughs() != null
                ? dto.connectionPassThroughs().size() : 0;
        // M6: M2-M5 metrics (interior, zigzag, edge-coincidence, hub-port
        // quality) can drive a view to fair/poor without any crossings or PTs. Treat any
        // non-zero M2-M5 signal as a routing issue so next-steps route to auto-route-connections
        // rather than to auto-layout-and-route (which re-positions elements unnecessarily).
        boolean hasPerceptionRoutingDefect = dto.zigzagCount() > 0
                || dto.interiorTerminationCount() > 0
                || dto.connectionEdgeCoincidenceCount() > 0
                || dto.hubPortQualityScore() < HUB_PORT_QUALITY_NEXTSTEPS_THRESHOLD
                || dto.coincidentSegmentCount() > 0
                || dto.nonOrthogonalTerminalCount() > 0;
        // Deliberately the WIDER quantity. This gates generic "re-route this view" advice, and a
        // self-element pass-through is a real routing defect worth routing even though the rating
        // does not charge it. Narrowing this to the charged count would delete guidance that was
        // legitimately flowing.
        boolean hasRoutingIssues = hasConnections
                && (dto.edgeCrossingCount() > 0 || describedPassThroughs > 0
                        || hasPerceptionRoutingDefect);
        // The CHARGED quantity: this predicate borrows its threshold from the pass-through rating
        // band, which is computed over cross-element crossings only, so anything else compared
        // against it is comparing two different measurements.
        boolean passThroughDominated = chargedPassThroughs >= 3;

        // Orphaned connection guidance — always first when present (unchanged)
        if (dto.orphanedConnections() > 0) {
            steps.add("Found " + dto.orphanedConnections()
                    + " orphaned connection(s) referencing missing view objects."
                    + " Use clear-view to rebuild the view cleanly.");
        }

        // Precondition-class nextSteps wired
        // to remediation tools by name with violator IDs. Surfaced BEFORE the rating-switch
        // advice so an LLM agent acts on hub/spacing preconditions first.

        // #0: Fan-out sizing preconditions — the earliest precondition there is, and the only one
        // that can be checked on a view with no routes yet. Placed ahead of the hub-port step
        // because the two answer different questions at different moments: this one measures the
        // element's box against the fan-out it must hold, which is answerable the instant the
        // elements are placed, whereas hub-port quality measures how the terminals ended up
        // distributed and is vacuously clean until something has routed. A caller that acts on
        // this one never reaches the other.
        List<AssessLayoutResultDto.HubPreconditionDto> unsizedHubs = dto.unsizedHubs();
        if (unsizedHubs != null && !unsizedHubs.isEmpty()) {
            StringBuilder hubs = new StringBuilder();
            for (AssessLayoutResultDto.HubPreconditionDto hub : unsizedHubs) {
                if (hubs.length() > 0) hubs.append("; ");
                hubs.append(hub.name() != null ? hub.name() : hub.viewObjectId())
                        .append(" (").append(hub.viewObjectId()).append(", ")
                        .append(hub.connectionCount()).append(" connections, ")
                        .append(hub.currentWidth()).append("x").append(hub.currentHeight())
                        .append(" needs ").append(hub.requiredWidth()).append("x")
                        .append(hub.requiredHeight()).append(")");
            }
            steps.add(unsizedHubs.size() + " element(s) are too small for their connection fan-out: "
                    + hubs + ". Run detect-hub-elements to confirm, then update-view-object to "
                    + "resize each one to at least the required box, and only THEN "
                    + "auto-route-connections. Sizing a hub after routing invalidates the stored "
                    + "routes it already carries — every terminal was computed against the old "
                    + "perimeter — so the same resize costs a re-route and an undo instead of "
                    + "nothing.");
        }

        // #1: Hub-port quality below the good band → name detect-hub-elements + violator hub IDs.
        // Violator IDs require includeViolatorIds=true at the call site (LayoutQualityAssessor
        // populates hubPortQualityFaces only when includeViolatorIds=true to save allocation in
        // the default path). When IDs are unavailable, point the agent at includeViolatorIds=true
        // for the IDs — the prose guidance still surfaces independently.
        double hpq = dto.hubPortQualityScore();
        if (hpq < HUB_PORT_QUALITY_REMEDY_THRESHOLD) {
            String violatorClause;
            if (dto.hubPortQualityFaces() != null && !dto.hubPortQualityFaces().isEmpty()) {
                Set<String> hubElemIds = new LinkedHashSet<>();
                for (AssessLayoutResultDto.HubFaceDetailDto face : dto.hubPortQualityFaces()) {
                    hubElemIds.add(face.elementId());
                }
                violatorClause = " (violator hubs: " + String.join(", ", hubElemIds) + ")";
            } else {
                violatorClause = " (re-run assess-layout with includeViolatorIds=true to list "
                        + "violator hubs)";
            }
            steps.add(String.format(
                    "Hub-port quality %.2f (below the good band at 0.75) — hubs may be undersized for "
                    + "connection fan-out. Run detect-hub-elements%s, then update-view-object "
                    + "to size each hub for its connection count "
                    + "(formula: dimension = 55 + 15 × (count − 6)). Re-run layout-within-group "
                    + "on affected groups, then auto-route-connections.",
                    hpq, violatorClause));
        }

        // #1b: Saturated container-nested-hub layout — supersedes the generic spacing step (#2)
        // below. When corridors are near-saturated AND there is edge-coincidence/coincident-segment
        // pressure AND hub-port quality was NOT already flagged by #1, a hub-resize OR a
        // reposition (ELK) is the right lever — but which one depends on per-element density the
        // emitter cannot see, and it cannot even confirm a hub exists (no hub count; hubPortQualityFaces
        // is null without includeViolatorIds; hpq defaults to neutral). So the step is DIAGNOSTIC:
        // it points at detect-hub-elements and presents both levers + the render-authoritative caveat,
        // conditioned on a hub actually being present (correct even on a hubless saturated view).
        boolean saturatedNestedHub =
                dto.corridorUtilisationScore() >= NESTED_HUB_CORRIDOR_SATURATION_THRESHOLD
                && (dto.connectionEdgeCoincidenceCount() > 4 || dto.coincidentSegmentCount() > 2)
                && hpq >= HUB_PORT_QUALITY_REMEDY_THRESHOLD;
        if (saturatedNestedHub) {
            String header = "Saturated layout (corridorUtilisation "
                    + String.format("%.2f", dto.corridorUtilisationScore())
                    + ", connectionEdgeCoincidence=" + dto.connectionEdgeCoincidenceCount()
                    + ", coincidentSegments=" + dto.coincidentSegmentCount()
                    + "): corridors are full. ";
            double clearance = dto.hubNeighbourClearanceMin();
            if (clearance >= NESTED_HUB_CROWDING_CLEARANCE_FLOOR_PX) {
                // SPARSE — the hub edge keeps a readable corridor, so a resize has room. Emit the
                // resize lever only (the present-both MVP is replaced once geometry can decide).
                steps.add(header + "Hub-to-neighbour clearance is "
                        + String.format("%.0f", clearance) + "px (>= "
                        + String.format("%.0f", NESTED_HUB_CROWDING_CLEARANCE_FLOOR_PX)
                        + "px), so there is room to grow. Run detect-hub-elements. If a hub is present, "
                        + "enlarge it in BOTH dimensions with update-view-object "
                        + "(formula: dimension = 55 + 15 × (count − 6)), then auto-route-connections. "
                        + "Re-routing alone is inert here, and a high hubPortQualityScore does NOT mean "
                        + "enlarging the hub will not help (port distribution is orthogonal to corridor "
                        + "headroom). Acceptance is render-authoritative: confirm with export-view and "
                        + "look — do not accept on the rating.");
            } else if (clearance >= 0.0) {
                // DENSE — the hub edge is within the crowding floor of a spoke row, so a resize would
                // crowd. Emit the reposition (ELK) lever only.
                steps.add(header + "Hub-to-neighbour clearance is only "
                        + String.format("%.0f", clearance) + "px (< "
                        + String.format("%.0f", NESTED_HUB_CROWDING_CLEARANCE_FLOOR_PX)
                        + "px), so enlarging the hub would crowd its neighbours. Run detect-hub-elements. "
                        + "If a hub is present, revert it to its normal size first (an oversized hub "
                        + "before ELK causes interior terminations), run auto-layout-and-route (ELK) to "
                        + "re-place elements, then a FULL auto-route-connections (NOT terminals-only — "
                        + "terminals-only vetoes terminations that land inside the re-placed elements). "
                        + "Acceptance is render-authoritative: confirm with export-view and look — do not "
                        + "accept on the rating.");
            } else {
                // No hub-neighbour clearance was measured (sentinel) — stay hub-existence-safe and
                // present both levers, deferring the sparse/dense choice to detect-hub-elements +
                // render inspection (the diagnostic MVP for the un-measurable case).
                steps.add(header + "If this view nests components inside a container with a "
                        + "central hub, generic spacing inflation is the wrong lever — diagnose first. Run "
                        + "detect-hub-elements. If a hub is present, choose by density: "
                        + "(1) SPARSE view with spare room around the hub — enlarge that hub in BOTH "
                        + "dimensions with update-view-object, then auto-route-connections. Re-routing alone "
                        + "is inert here, and a high hubPortQualityScore does NOT mean enlarging the hub will "
                        + "not help (port distribution is orthogonal to corridor headroom). "
                        + "(2) DENSE view where enlarging would crowd neighbours — revert the hub to its "
                        + "normal size first (an oversized hub before ELK causes interior terminations), run "
                        + "auto-layout-and-route (ELK) to re-place elements, then a FULL auto-route-connections "
                        + "(NOT terminals-only — terminals-only vetoes terminations that land inside the "
                        + "re-placed elements). Acceptance is render-authoritative: confirm with export-view and "
                        + "look — the rating number alone can score a crowded layout 'good', so do not accept on "
                        + "the rating.");
            }
        }

        // #2: Spacing tightness — name the right inflation tool for the view's shape.
        // adjust-view-spacing requires a grouped view (per ArchiModelAccessorImpl.adjustViewSpacing
        // runtime guard); flat views need layout-flat-view with increased spacing instead.
        // Suppressed when #1b fired so the agent is not handed two conflicting spacing remedies.
        if (!saturatedNestedHub
                && (dto.coincidentSegmentCount() > 2
                || dto.connectionEdgeCoincidenceCount() > 4)) {
            String spacingTool = hasGroups
                    ? "adjust-view-spacing with interElementDelta and/or interGroupDelta "
                            + "(inflate + re-route in a single undo step)"
                    : "layout-flat-view with increased spacing then auto-route-connections "
                            + "(adjust-view-spacing is unavailable on flat views — it requires groups)";
            steps.add("Spacing tightness flagged (coincidentSegments="
                    + dto.coincidentSegmentCount() + ", connectionEdgeCoincidence="
                    + dto.connectionEdgeCoincidenceCount() + ") — use " + spacingTool
                    + ". Heuristics in archimate://reference/archimate-view-patterns "
                    + "Pre-Layout Planning §2: connections ≤15 → 60px element / 80px group; "
                    + "16-30 → 80/100; 30+ → 100/120. Read that URI as an MCP resource, or call "
                    + "get-guidance with it if your client does not expose resources.");
        }

        // #3: High inter-group crossing density on a grouped view → name arrange-groups
        // (topology) and optimize-group-order.
        if (hasGroups && hasConnections && dto.crossingsPerConnection() > 4.0) {
            steps.add(String.format(
                    "High inter-group crossing density (%.1f crossings per connection on a "
                    + "grouped view) — consider arrange-groups with arrangement='topology' and "
                    + "spacing>=80 (creates routing corridors that auto-route-connections uses "
                    + "for cleaner orthogonal paths) and/or optimize-group-order if groups have "
                    + "not been reordered for the current layout. After reorder, ALWAYS re-run "
                    + "arrange-groups to fix any group-on-group overlaps the reorder introduced.",
                    dto.crossingsPerConnection()));
        }

        // Fires regardless of overall rating: structural boundary fix always precedes
        // rating-graduated advice. Row F (v1.5+ deferred) is the single-undo successor.
        if (dto.boundaryViolations() != null && !dto.boundaryViolations().isEmpty()) {
            // The description list is capped; its size is not the number of violations. Take the
            // true count where the DTO carries one, and fall back to the list size only for a
            // legacy-constructed DTO that has no count, so the step can never quote a smaller
            // number than the descriptions it sits beside.
            int violationCount = Math.max(dto.boundaryViolationCount(),
                    dto.boundaryViolations().size());
            String violatorClause;
            if (dto.violatorIds() != null
                    && dto.violatorIds().get("boundaryViolations") != null
                    && !dto.violatorIds().get("boundaryViolations").isEmpty()) {
                List<String> violatorElemIds = dto.violatorIds().get("boundaryViolations");
                violatorClause = " (violator elements: " + String.join(", ", violatorElemIds) + ")";
            } else {
                violatorClause = " (re-run assess-layout with includeViolatorIds=true to list "
                        + "violator elements)";
            }
            steps.add(String.format(
                    "Found %d boundary violation(s) — child element(s) positioned outside their "
                    + "parent container's bounds%s. Composite recovery: "
                    + "(1) use update-view-object to resize the affected parent container(s) to enclose "
                    + "all child elements, "
                    + "(2) re-run layout-within-group on the resized parent container(s) to re-position "
                    + "siblings, "
                    + "(3) re-run auto-route-connections to refresh routes after the layout changes. "
                    + "This sequence is undoable as multiple steps; if a single-undo composition is "
                    + "needed, the convenience tool covering this case is queued for a future release "
                    + "(Row F — apply-spacing-recommendations / apply-hub-sizing-recommendations).",
                    violationCount, violatorClause));
        }

        // An element's own icon drawn over its own title. Emitted ABOVE the rating switch on
        // purpose: the count contributes to no rating, so a view carrying it is typically rated
        // clean, and both the "excellent" arm and the default arm add nothing — a step placed
        // inside the switch would be swallowed on exactly the ratings this metric reaches. The
        // remedy names the view-object scope because the property is per view object: correcting
        // it once does not travel to the other views that show the same element.
        if (dto.ownIconOverLabelCount() > 0) {
            int iconTotal = dto.ownIconOverLabelCount();
            // The description list is capped and this dimension publishes no violator-id key, so
            // where the count outruns the list the rest is recoverable from no field at all. Say
            // that, rather than pointing at a list that silently stops short.
            int iconNamed = dto.ownIconOverLabelDescriptions() == null
                    ? 0 : dto.ownIconOverLabelDescriptions().size();
            String where;
            if (iconNamed == 0) {
                where = "";
            } else if (iconNamed < iconTotal) {
                // Opens with a word, not with the field name. This clause is appended after the
                // full stop that closes the preceding literal, and a sentence beginning with a
                // lowercase identifier reads as a run-on wherever the step is rendered. The field
                // itself stays verbatim and lowercase — it is the JSON key the caller has to read,
                // and no other spelling of it resolves — so the fix is to recompose the sentence
                // around it rather than to capitalise it. The sibling branch below already opens
                // with a capital, so leaving this one bare made the two disagree in one response.
                where = "The first " + iconNamed + " are named in assess-layout's "
                        + "ownIconOverLabelDescriptions; this dimension publishes no violator-id "
                        + "list, so the remaining " + (iconTotal - iconNamed) + " have to be "
                        + "found in the render. ";
            } else {
                where = "Read ownIconOverLabelDescriptions for the objects and the alignment each "
                        + "one uses. ";
            }
            steps.add((iconTotal == 1
                            ? "Found 1 element whose own icon is drawn over its own title label"
                            : "Found " + iconTotal + " elements whose own icons are drawn over "
                                    + "their own title labels")
                    + " — the element name is buried under the glyph. " + where
                    + "Use update-view-object to widen the element, move the icon to a "
                    + "corner the title does not reach, or change that object's text alignment. "
                    + "Text alignment is a property of the VIEW OBJECT, not of the model element, "
                    + "so repeat the correction on every view that shows the element.");
        }

        // Which dimensions the closing export-view step is actually needed FOR. This list
        // deliberately adds no second export step: the remedy for an unexamined dimension is to
        // look at the render, and the step that says so is already the unconditional tail below.
        // What was missing was the reason — the caller was told to inspect the layout without ever
        // being told which dimensions the inspection has to settle, so an export that answered
        // nothing looked like a completed check.
        //
        // A child sitting over its parent's title band. Emitted ABOVE the rating switch like the two
        // steps around it, though for the opposite reason: this metric vetoes the overall rating to
        // "poor" outright, and the "poor" arm below reaches its generic fallback for a view whose
        // only defect is this one — so the caller was being marked down to the worst rating the tool
        // gives and handed the one lever that cannot move it. Automated layout re-positions
        // elements; a parent's top padding is not a position it sets.
        if (dto.parentLabelObscuredCount() > 0) {
            int obscuredTotal = dto.parentLabelObscuredCount();
            int obscuredNamed = dto.parentLabelObscuredDescriptions() == null
                    ? 0 : dto.parentLabelObscuredDescriptions().size();
            // Capped list, no violator-id key for this dimension: past the cap the remainder is
            // recoverable from no field at all, which the caller has to be told rather than being
            // pointed at a list that stops short.
            String where;
            if (obscuredNamed == 0) {
                where = "";
            } else if (obscuredNamed < obscuredTotal) {
                // Opens with a word, not with the field name. The clause is appended after a full
                // stop, and a sentence that starts with a lowercase identifier reads as a run-on
                // to everything that renders it — the sibling branch below already starts with a
                // capital, so leaving this one bare made the two disagree in the same response.
                where = " The first " + obscuredNamed + " are named in"
                        + " parentLabelObscuredDescriptions; this dimension publishes no"
                        + " violator-id list, so the remaining " + (obscuredTotal - obscuredNamed)
                        + " have to be found in the render.";
            } else {
                where = " Read parentLabelObscuredDescriptions for the parents affected.";
            }
            steps.add("Found " + obscuredTotal + " parent"
                    + (obscuredTotal == 1 ? "" : "s")
                    + " whose title label is overlapped by the topmost child — this vetoes the"
                    + " overall rating to 'poor' on its own. Use update-view-object to move the"
                    + " child down, or to increase the parent's top padding by growing the parent."
                    + " Running auto-layout-and-route will not clear it: it re-positions"
                    + " elements, and the clearance above a child is a padding decision, not a"
                    + " routing one." + where);
        }

        // Emitted ABOVE the rating switch, for the same reason the icon step above is: coverage
        // moves no rating, so a run carrying only a downgrade rates "excellent", and both that arm
        // and the default arm (which covers "not-applicable") contribute nothing — a step placed
        // inside the switch would be swallowed on exactly the ratings this reaches.
        List<String> unexamined = dto.contextualPartialDimensions();
        if (unexamined != null && !unexamined.isEmpty()) {
            steps.add("Coverage on this view is incomplete: " + String.join(", ", unexamined)
                    + (unexamined.size() == 1 ? " was" : " were")
                    + " not fully examined on this run, so a zero on "
                    + (unexamined.size() == 1 ? "that dimension" : "those dimensions")
                    + " is not evidence of absence. The export-view step below is what settles "
                    + (unexamined.size() == 1 ? "it" : "them")
                    + " — read the coverage map for the per-dimension detail.");
        }

        switch (rating) {
            case "excellent":
                // No layout changes needed
                break;
            case "good":
                if (hasRoutingIssues) {
                    steps.add("Use auto-route-connections to fix routing issues"
                            + " (edge crossings / pass-throughs) without changing element positions.");
                }
                if (dto.overlapCount() > 0 || dto.averageSpacing() < GOOD_SPACING_FIX_THRESHOLD
                        || dto.alignmentScore() < GOOD_ALIGNMENT_FIX_THRESHOLD) {
                    if (hasGroups) {
                        String groupStep = "Use layout-within-group to fix spacing/alignment"
                                + " within each group";
                        steps.add(hasConnections
                                ? groupStep + ", then re-run auto-route-connections."
                                : groupStep + ".");
                    } else {
                        steps.add("Use apply-positions to adjust element positions"
                                + " for better spacing and alignment.");
                    }
                }
                break;
            case "fair":
                if (hasRoutingIssues) {
                    if (passThroughDominated) {
                        steps.add("Found " + chargedPassThroughs + " pass-through(s)"
                                + " — use auto-route-connections to re-route connections"
                                + " around elements without changing element positions.");
                    } else {
                        steps.add("Use auto-route-connections to re-route connections"
                                + " without moving elements — this is often sufficient"
                                + " when element positions are already well-organized.");
                    }
                    steps.add("If routing alone doesn't improve the rating, use"
                            + " auto-layout-and-route (ELK) with targetRating for"
                            + " automated quality iteration.");
                } else if (dto.overlapCount() > 0) {
                    if (hasGroups) {
                        steps.add("Use layout-within-group to fix element overlaps"
                                + " within groups.");
                    } else {
                        steps.add("Use auto-layout-and-route (ELK) to fix element"
                                + " overlaps.");
                    }
                }
                break;
            case "poor":
                // Gated on the BREAKDOWN band, not on passThroughDominated. The predicate's ">= 3"
                // is the FAIR floor, not the POOR one: a charged count of exactly 3 rates 'fair',
                // so on a view driven to 'poor' by some other metric this arm would name
                // pass-throughs as the reason the view is 'poor' when the rating never charged them
                // that far. The band is the fold's own decision and is the only thing that knows
                // it. The count conjunct is dropped rather than carried alongside: a 'poor' band
                // already implies a charged count above the fair maximum, so keeping it would add
                // no case and would put a second copy of that threshold here to drift from the one
                // the rating used. A null breakdown is a view that declared no per-metric verdict,
                // which is exactly the case where this attribution cannot be justified.
                String passThroughBand = dto.ratingBreakdown() != null
                        ? dto.ratingBreakdown().get("passThroughs") : null;
                if ("poor".equals(passThroughBand)) {
                    steps.add("Found " + chargedPassThroughs + " pass-through(s)"
                            + " — try auto-route-connections first to re-route"
                            + " connections around elements.");
                    steps.add("If pass-throughs persist, use auto-layout-and-route"
                            + " (ELK) with targetRating for automated quality iteration.");
                } else if (dto.overlapCount() == 0 && hasPerceptionRoutingDefect
                        && dto.parentLabelObscuredCount() == 0) {
                    // M6: routing-only poor (M2/M3/M4/M5 dominated) — try re-routing
                    // before falling back to ELK layout, which would unnecessarily
                    // reposition elements that are already well-placed.
                    //
                    // The obscured-label guard is what makes the causal claim below TRUE. Every
                    // metric in the perception-routing predicate caps the routing tier at "fair";
                    // none of them can reach "poor" alone. So on a view that also carries an
                    // obscured parent label — a Tier-1L veto — naming the routing defects as "the
                    // rating-driver" would be false, and the ELK step under it would contradict
                    // the obscured-label step emitted above the switch in the same response.
                    steps.add("Use auto-route-connections to re-route connections"
                            + " — element positions are clean; the routing defects"
                            + " (zigzags / interior terminations / edge-coincidence /"
                            + " hub-port distribution) are the rating-driver.");
                    steps.add("If routing alone doesn't clear the defects, use"
                            + " auto-layout-and-route (ELK) with targetRating for"
                            + " automated quality iteration.");
                } else if (dto.parentLabelObscuredCount() > 0 && dto.overlapCount() == 0) {
                    // The obscured-parent step above already names the only lever that moves this
                    // rating, so the generic ELK fallback below is deliberately not reached: it
                    // would name a lever that cannot move the metric holding the view at "poor",
                    // which is worse than silence because the caller acts on it and nothing
                    // changes. Guarded on overlapCount so a view that ALSO has overlaps still gets
                    // the layout tool that does fix those.
                    //
                    // Routing defects are still worth clearing when present — they are real, they
                    // are just not what holds this rating. Said that way round, so the step cannot
                    // be read as the remedy for the "poor".
                    if (hasPerceptionRoutingDefect) {
                        steps.add("Use auto-route-connections to clear this view's routing defects"
                                + " as well. They are real, but they cap the routing tier at 'fair'"
                                + " and are not what holds this view at 'poor' — the obscured"
                                + " parent label named above is, and no re-route or automated"
                                + " layout pass will move it.");
                    }
                } else {
                    steps.add("Use auto-layout-and-route (ELK) with targetRating"
                            + " for automated group-aware layout and routing iteration.");
                }
                break;
            default:
                // "not-applicable" or unknown — no layout steps
                break;
        }

        // Always end with export-view
        steps.add("Use export-view to visually inspect the current layout.");

        return steps;
    }

    // ---- auto-route-connections ----

    private McpServerFeatures.SyncToolSpecification buildAutoRouteConnectionsSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to route connections on");

        Map<String, Object> connectionIdsProp = new LinkedHashMap<>();
        connectionIdsProp.put("type", "array");
        Map<String, Object> connIdItems = new LinkedHashMap<>();
        connIdItems.put("type", "string");
        connectionIdsProp.put("items", connIdItems);
        connectionIdsProp.put("description",
                "Specific connection IDs to re-route. Only these connections will be "
                + "routed; all other connections on the view retain their existing "
                + "bendpoints unchanged. Omit to route all connections. Invalid IDs "
                + "are reported as warnings; valid connections are still routed.");

        Map<String, Object> strategyProp = new LinkedHashMap<>();
        strategyProp.put("type", "string");
        strategyProp.put("description",
                "Routing strategy. 'orthogonal' (default) computes right-angle "
                + "bendpoints. 'clear' removes all bendpoints (straight lines).");

        Map<String, Object> forceProp = new LinkedHashMap<>();
        forceProp.put("type", "boolean");
        forceProp.put("description",
                "When true, applies all routes including those violating constraints "
                + "(element crossings). Default false — excludes constraint-violating "
                + "routes and returns failure details with move recommendations. "
                + "Recommended workflow: (1) route with default mode, (2) review "
                + "failures and recommendations, (3) either adjust layout per "
                + "recommendations and re-route, OR (4) re-run with force=true "
                + "to accept trade-offs as a last resort.");

        Map<String, Object> autoNudgeProp = new LinkedHashMap<>();
        autoNudgeProp.put("type", "boolean");
        autoNudgeProp.put("description",
                "When true, automatically applies move recommendations and re-routes "
                + "affected connections in a single atomic operation. Collapses the "
                + "manual iterate-nudge-reroute loop into one call. Reports nudged "
                + "elements in the response. The entire operation (route + nudge + "
                + "re-route) is undoable as a single undo step. Default false. "
                + "Ignored when force=true (force already applies all routes). "
                + "Up to 2 nudge iterations are attempted. The nudgedElements list names only "
                + "elements that ended somewhere new: an element whose iterations summed "
                + "to a zero displacement is omitted from the list and from the nudge "
                + "count in nextSteps, and is named instead by an AUTO_NUDGE_NET_ZERO "
                + "structured warning. The list is omitted entirely when empty, so its "
                + "absence means nothing moved, not that autoNudge did not run.");

        Map<String, Object> snapThresholdProp = new LinkedHashMap<>();
        snapThresholdProp.put("type", "integer");
        snapThresholdProp.put("minimum", 0);
        snapThresholdProp.put("maximum", 50);
        snapThresholdProp.put("description",
                "Snap-to-straight threshold in pixels (0-50). When source and target "
                + "ports differ by at most this many pixels in one axis, the router "
                + "produces a single straight segment instead of a Z-bend. Default 20. "
                + "Set to 0 to disable snap-to-straight.");

        Map<String, Object> perimeterMarginProp = new LinkedHashMap<>();
        perimeterMarginProp.put("type", "integer");
        perimeterMarginProp.put("minimum", 10);
        perimeterMarginProp.put("maximum", 200);
        perimeterMarginProp.put("description",
                "Exterior perimeter margin in pixels (10-200). Controls how far "
                + "beyond the outermost elements the routing graph extends, creating "
                + "space for connections to route around dense element clusters. "
                + "Default 50. Increase for views with tightly packed elements where "
                + "many connections fail to find orthogonal paths. Decrease if exterior "
                + "routes are too far from content.");

        Map<String, Object> modeProp = new LinkedHashMap<>();
        modeProp.put("type", "string");
        modeProp.put("description",
                "Routing scope. 'full' (default) re-routes whole connections "
                + "via visibility-graph A*. 'terminals-only' leaves intermediate "
                + "bendpoints unchanged and only adjusts the terminal segments: it "
                + "makes them orthogonal and, when a terminal departs a face and then "
                + "runs parallel hugging that face within a few pixels, pushes that first "
                + "trunk clear of the face. Use terminals-only "
                + "to fix diagonal terminal entries/exits and off-face hugs on ELK-laid-out "
                + "views without the crossing inflation that a full re-route causes "
                + "(assess-layout reports zero-bendpoint connections as the signature). "
                + "The terminals-only mode is mutually exclusive with strategy='clear' and "
                + "autoNudge=true.");

        Map<String, Object> enableChannelNudgingProp = new LinkedHashMap<>();
        enableChannelNudgingProp.put("type", "boolean");
        enableChannelNudgingProp.put("description",
                "When true (default), routes are post-processed by a channel-global "
                + "ordered nudging pass that centres single-occupant routes in their "
                + "corridors and fans out parallel runs sharing a corridor. Set false "
                + "to disable channel nudging and reproduce the pre-nudging routing "
                + "output (useful for before/after comparison).");
        enableChannelNudgingProp.put("default", true);

        Map<String, Object> labelPolicyProp = new LinkedHashMap<>();
        labelPolicyProp.put("type", "string");
        labelPolicyProp.put("enum", List.of("keep", "auto-hide-on-collision"));
        labelPolicyProp.put("description",
                "What to do with a connection label that has NO collision-free position. The router "
                + "already evaluates all three label positions (source, middle, target) for every "
                + "labelled connection; on a dense view a few labels collide at all three and no "
                + "perpendicular offset clears them either. \"keep\" (DEFAULT) never changes label "
                + "visibility. \"auto-hide-on-collision\" hides exactly those unplaceable labels and "
                + "leaves every placeable one visible. Hidden labels are listed individually in "
                + "hiddenLabels (connectionId + reason) — never just a count — and each one is "
                + "reversible with update-view-connection(showLabel: true). A label you hid yourself "
                + "is never overridden and never counted as a policy hide. Omit this parameter for "
                + "byte-identical label behaviour: hiding is opt-in, never automatic.");
        labelPolicyProp.put("default", "keep");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("connectionIds", connectionIdsProp);
        properties.put("strategy", strategyProp);
        properties.put("force", forceProp);
        properties.put("autoNudge", autoNudgeProp);
        properties.put("snapThreshold", snapThresholdProp);
        properties.put("perimeterMargin", perimeterMarginProp);
        properties.put("mode", modeProp);
        properties.put("enableChannelNudging", enableChannelNudgingProp);
        properties.put("labelPolicy", labelPolicyProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("auto-route-connections")
                .description("[Mutation] Apply automated orthogonal routing "
                        + "to connections on a view using visibility-graph A* pathfinding "
                        + "that routes around element obstacles. Computes right-angle "
                        + "bendpoints stored on each connection. THIS IS THE PRIMARY "
                        + "ROUTING TOOL for any connected view — produces clean orthogonal "
                        + "(right-angle) paths that avoid crossing through elements. Works "
                        + "correctly with grouped views — routing quality depends on element "
                        + "spacing, not group presence. Use 40px+ spacing for grouped views, "
                        + "100px+ for dense layouts. "
                        + "NOTES ARE NOT OBSTACLES: a note is excluded from the obstacle set on "
                        + "purpose (notes are often large and treating them as solid would "
                        + "over-constrain routing), so a connection WILL be routed straight "
                        + "through a note that sits in its corridor. THIS RESPONSE NAMES THE ONES IT "
                        + "APPLIED: a structuredWarnings entry coded "
                        + "CONNECTION_ROUTED_THROUGH_NOTE lists every (connection, note) pair, with "
                        + "the crossed note ids in remediationViolatorIds — you do not need a "
                        + "second call to find out which note was crossed. One entry per call, "
                        + "never truncated, and emitted on EVERY strategy and mode that applies a "
                        + "route — orthogonal, clear (a straight line crosses more notes, not "
                        + "fewer) and terminals-only alike. A connection a veto left unchanged is "
                        + "never named, because this call did not route it. It covers the "
                        + "crossings THIS CALL applied; "
                        + "assess-layout's connectionThroughNoteCount is a whole-view figure on "
                        + "the same geometry, so it agrees pair-for-pair on what this call routed "
                        + "but can be higher — it also counts connections this call did not route "
                        + "and element images, which this warning never reports. Then either move "
                        + "the note clear with "
                        + "update-view-object or accept the crossing. Because moving a note changes "
                        + "the routes around it, position notes AFTER routing and then re-assess. "
                        + "ITERATIVE WORKFLOW: route → assess-layout "
                        + "→ if poor, increase spacing via layout-within-group → re-route → "
                        + "repeat. Use autoNudge=true to automatically apply move "
                        + "recommendations and re-route in a single call. "
                        + "IMPORTANT: If the view uses manhattan connectionRouterType, "
                        + "this tool automatically switches to bendpoint mode (connections "
                        + "remain orthogonal/right-angle — only the storage format changes, "
                        + "not the visual style). Use strategy \"clear\" to remove all "
                        + "bendpoints (straight lines; does not change router type). "
                        + "Near-aligned connections (port offset ≤ snapThreshold) are "
                        + "automatically straightened to eliminate Z-bends. "
                        + "Response includes straightLineCrossings (straight-line "
                        + "crossing estimate) alongside crossingsBefore/crossingsAfter. "
                        + "A warning is emitted if routed crossings exceed 1.5x the "
                        + "straight-line estimate — indicates layout is too dense for "
                        + "clean orthogonal routing (increase element spacing and re-route). "
                        + "COINCIDENT PORT DISSOLUTION: a gated final pass separates two "
                        + "connection terminals that a downstream stage collapsed onto the "
                        + "SAME perimeter port of a low-degree element face (the "
                        + "coincidentFacePortCount defect assess-layout reports) — it moves "
                        + "whichever terminal can move to a free along-face slot, and is a "
                        + "byte-identical no-op unless there is an actual collision with a "
                        + "clear slot, so it never disturbs a dense hub's own distribution "
                        + "or adds a crossing. "
                        + "LABEL OFFSET (Archi 5.10): when a Middle-positioned connection "
                        + "label still renders on its own source/target box after position "
                        + "selection, the router applies the connection \"Label Offset\" "
                        + "(relativePosition) to lift it clear — on a position-preserving "
                        + "route this is the only channel that can clear own-endpoint label "
                        + "bleed. Read the applied anchor back via get-view-contents "
                        + "(relativePosition); export-view does not render it. Runtime-guarded: "
                        + "a silent no-op on Archi 5.7, which lacks the feature. "
                        + "Connections are updated atomically as a single undo unit. "
                        + "Supports batch and approval modes. SPECULATIVE EXECUTION: "
                        + "To preview routing quality, apply routing → assess-layout → "
                        + "undo if unsatisfied. No dry-run needed — undo is cheap and instant. "
                        + "TERMINALS-ONLY MODE: pass mode='terminals-only' to fix "
                        + "diagonal source/target entries on ELK-laid-out views without "
                        + "re-routing the body. Preserves all intermediate bendpoints and "
                        + "element positions; only the first/last bendpoint of each "
                        + "connection may change. Use when assess-layout reports "
                        + "non-orthogonal terminals on a view with otherwise good routing — "
                        + "a full re-route would inflate crossings on ELK views (~3x measured). "
                        + "Each rectification is gated by an interior + zigzag + obstacle + "
                        + "crossing veto — connections whose L-bend would terminate inside its "
                        + "own element (an interior termination), introduce a zigzag/reversal, "
                        + "add a pass-through, cross an unrelated element, or add a new edge "
                        + "crossing with another connection are left unchanged and counted in "
                        + "connectionsSkipped (with vetoedByInterior, vetoedByZigzag, "
                        + "vetoedByObstacle and vetoedByCrossing sub-counts). This preserves "
                        + "the rating tier but means dense ELK views (high non-orth rate) may "
                        + "see only a small number of connections actually modified. Most "
                        + "effective on sparse-to-moderate layouts; on very dense views, accept "
                        + "the residual non-orth count as cosmetic or increase element spacing "
                        + "first. Pass force=true to bypass all four vetoes and force-apply "
                        + "every L-bend (matches force semantics on the orthogonal strategy). "
                        + "The terminals-only mode is mutually exclusive with strategy='clear' "
                        + "and autoNudge=true. It also sweeps redundant INTERIOR collinear "
                        + "bendpoints (an inserted terminal L-bend that lands collinear with "
                        + "the existing trunk) so a terminals-only re-route drives "
                        + "connectionRedundantBendpointCount toward zero without touching the "
                        + "pinned terminal egress anchors. "
                        + "Related: auto-layout-and-route (position elements first), assess-layout "
                        + "(evaluate quality after routing), adjust-view-spacing (inflate "
                        + "spacing and re-route in one call), apply-element-spacing-recommendations "
                        + "and apply-group-spacing-recommendations (precondition convenience tools), "
                        + "detect-hub-elements (hub-fan-out precondition), undo (roll back if "
                        + "unsatisfied), export-view (visual verification). "
                        + "PRECONDITION CHECKLIST: fetch "
                        + "archimate://prompts/routing-preconditions-checklist before invoking "
                        + "this tool on any non-trivial view. The pipeline cannot recover from "
                        + "missing preconditions (hub sizing, inter-element spacing, inter-group "
                        + "spacing) — it can only route the geometry the agent has set up. "
                        + "STRUCTURED WARNINGS: in addition to the free-text "
                        + "warnings: List<String> field, the response carries a parallel "
                        + "structuredWarnings: List<StructuredWarningDto> field with "
                        + "machine-parseable {code, message, remediationTool, remediationViolatorIds} "
                        + "entries for deterministic LLM iteration. When invoked with autoNudge=true "
                        + "on a view with overlapping sibling elements, the autoNudge phase is "
                        + "skipped and a structuredWarnings entry is emitted with "
                        + "code=AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP, "
                        + "remediationTool=\"layout-within-group\" and remediationViolatorIds "
                        + "naming the offending sibling pair. "
                        + "EGRESS_LIFT_LAYOUT_BOUND is emitted when the router generated one or "
                        + "more off-face terminal egress lifts but rolled them back because "
                        + "applying them would narrow a parallel-connection gap below the 15px "
                        + "healthy floor — i.e. the residual off-face hug is layout-bound, not a "
                        + "routing bug, so it names spreading the elements (a matching layout-bound "
                        + "nextSteps entry accompanies it) rather than declining silently. "
                        + "AUTO_NUDGE_NET_ZERO is emitted when autoNudge moved "
                        + "one or more elements and their iterations summed to a zero "
                        + "displacement, so each ended where it started: those elements are "
                        + "absent from nudgedElements and uncounted in nextSteps, their ids are "
                        + "in remediationViolatorIds, and remediationTool names a spacing lever "
                        + "because re-routing recomputes the same recommendation against the "
                        + "same geometry and reproduces the same zero. The message states that "
                        + "outcome and does not claim which of the two producers caused it - a "
                        + "move a later iteration reversed, or one the parent-containment clamp "
                        + "absorbed - because the summed deltas cannot tell them apart. "
                        + "AUTO_ROUTE_CROSSINGS_REGRESSED is emitted when a full re-route left the "
                        + "view with more edge crossings than the geometry it replaced, naming both "
                        + "the before and after figures; the route is still applied, its "
                        + "remediationTool is undo rather than this tool, and it is gated on the "
                        + "input having been routed so a first route of an unrouted view is never "
                        + "advised to undo a good result. "
                        + "CONNECTION_NOT_FOUND is emitted when one or more ids passed in "
                        + "connectionIds did not resolve on the view: the connections that were "
                        + "found are routed normally, the unknown ids are skipped, and "
                        + "remediationViolatorIds lists every id that missed. "
                        + "RECOMMENDED ITERATION: when "
                        + "structuredWarnings[].code == AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP, "
                        + "invoke layout-within-group on the parent of remediationViolatorIds "
                        + "BEFORE re-running auto-route-connections — the autoNudge skip is a "
                        + "hard gate driven by degenerate geometry that the routing pipeline "
                        + "cannot resolve, so re-running without first separating the siblings "
                        + "will reproduce the same skip. "
                        + "BLOCKED RECOMMENDATIONS: when autoNudge=true is requested AND the "
                        + "autoNudge phase is blocked by overlapping sibling elements, the move "
                        + "recommendations are surfaced under blockedRecommendations (not "
                        + "recommendations) and a top-level nudgeBlockedReason field carries the "
                        + "canonical reason (currently only \"sibling_overlap\"). The "
                        + "recommendations field is reserved for the advisory (autoNudge=false) "
                        + "path. When you see blockedRecommendations populated, resolve the "
                        + "underlying overlap via layout-within-group (or apply the listed "
                        + "recommendations manually) and re-run auto-route-connections — the "
                        + "routing pipeline cannot apply the recommendations directly until the "
                        + "sibling overlap is resolved."
                        + " Any archimate:// URI named here can also be read by calling get-guidance with that uri, for clients that do not expose MCP resources.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAutoRouteConnections)
                .build();
    }

    McpSchema.CallToolResult handleAutoRouteConnections(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling auto-route-connections request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String strategy = HandlerUtils.optionalStringParam(args, "strategy");

            // Extract optional connectionIds array
            List<String> connectionIds = extractStringList(args, "connectionIds");

            // Extract optional force parameter
            Boolean forceObj = args != null ? (Boolean) args.get("force") : null;
            boolean force = forceObj != null && forceObj;

            // Extract optional autoNudge parameter
            Boolean autoNudgeObj = args != null ? (Boolean) args.get("autoNudge") : null;
            boolean autoNudge = autoNudgeObj != null && autoNudgeObj;

            // Extract optional snapThreshold parameter
            Integer snapThresholdObj = args != null ? (Integer) args.get("snapThreshold") : null;
            int snapThreshold = snapThresholdObj != null
                    ? Math.max(0, Math.min(50, snapThresholdObj)) : 20;

            // Extract optional perimeterMargin parameter
            Integer perimeterMarginObj = args != null ? (Integer) args.get("perimeterMargin") : null;
            int perimeterMargin = perimeterMarginObj != null
                    ? Math.max(10, Math.min(200, perimeterMarginObj)) : 50;

            // Extract optional mode parameter (terminals-only routing)
            String mode = HandlerUtils.optionalStringParam(args, "mode");

            // Extract optional enableChannelNudging parameter.
            // Default true — channel-global ordered nudging post-pass runs unless
            // explicitly disabled.
            boolean enableChannelNudging =
                    HandlerUtils.optionalBooleanParam(args, "enableChannelNudging", true);

            // Optional labelPolicy — omitted means "keep", so label visibility is untouched.
            String labelPolicy = HandlerUtils.optionalStringParam(args, "labelPolicy");

            MutationResult<AutoRouteResultDto> result =
                    accessor.autoRouteConnections(sessionId, viewId, connectionIds, strategy,
                            force, autoNudge, snapThreshold, perimeterMargin, mode,
                            enableChannelNudging, labelPolicy);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAutoRouteNextSteps(result), buildAutoRouteApprovalDisclosures(result),
                    accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling auto-route-connections", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * Extracts an optional list of strings from arguments.
     */
    private List<String> extractStringList(Map<String, Object> args, String paramName) {
        if (args == null) return null;
        Object value = args.get(paramName);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof String str && !str.isBlank()) {
                result.add(str);
            } else {
                throw new ModelAccessException(
                        paramName + "[" + i + "] must be a non-empty string",
                        ErrorCode.INVALID_PARAMETER);
            }
        }
        return result;
    }

    /**
     * Which dispatch arm a response is being composed for.
     *
     * <p>A tool measures the same things whichever arm it is on — the router still computes the
     * routes a queued call will lay down, and the loop still ranks the attempt a human has not yet
     * approved. What differs is only what the caller can <em>do</em> about it: an applied write is
     * reverted with {@code undo}, a queued one is discarded or committed with {@code end-batch},
     * and a proposal is recovered by no MCP tool at all — the human rejects it in Archi.</p>
     *
     * <p>So the evidence half of every disclosure below is composed once and shared across the
     * three arms, and only the recovery clause is selected. Rescoping by editing a tail inside a
     * finished sentence is how a shared constant quietly acquires a second, wrong meaning: the
     * batched arm here carried four sentences asserting routes were applied when nothing had
     * been.</p>
     */
    /**
     * The three lines every queued response ends with, after whatever the tool disclosed.
     *
     * <p>Shared by every arm-aware builder in this handler rather than copied into each. The
     * failure that invites is not hypothetical: the sibling specialization handler carried its own
     * three copies and lost {@code get-batch-status} from all of them, which went unnoticed until a
     * census counted the line across handlers.</p>
     */
    private static List<String> queueTail(MutationResult<?> result) {
        return List.of(
                "Mutation queued as operation #" + result.batchSequenceNumber()
                        + " in current batch",
                "Use get-batch-status to check batch progress",
                "Use end-batch to commit all queued mutations");
    }

    /**
     * nextSteps guidance for a layout-bound off-face egress-lift decline. The router deliberately
     * kept the hug(s) because clearing them would narrow a parallel-connection gap below its healthy
     * floor — a routing re-run cannot help; the corridor must be widened.
     *
     * <p>The corridor remedy is true on all three arms — it is a statement about the layout, not
     * about recovering this call — so it sits in the shared tail. Only the tense of the decline
     * itself, and the sentence saying what has actually happened to the model, are selected.</p>
     */
    private static final String EGRESS_LIFT_LAYOUT_BOUND_FINDING =
            " a parallel-connection gap below the 15px healthy floor — this is layout-bound.";

    /** The corridor remedy itself, which is a layout fact and holds on every arm. */
    private static final String EGRESS_LIFT_LAYOUT_BOUND_REMEDY =
            " Increase element spacing in the affected corridor (e.g. run "
                    + "apply-spacing-recommendations), then re-route; re-routing alone will not clear "
                    + "them.";

    /** Where to read the ids and counts. Always last: a pointer is the reader's exit, not a step. */
    private static final String EGRESS_LIFT_LAYOUT_BOUND_POINTER =
            " See the structuredWarnings entry (EGRESS_LIFT_LAYOUT_BOUND) for details.";

    private static final String EGRESS_LIFT_LAYOUT_BOUND_STEP =
            "One or more off-face terminal hugs were left in place because clearing them would narrow"
                    + EGRESS_LIFT_LAYOUT_BOUND_FINDING
                    + EGRESS_LIFT_LAYOUT_BOUND_REMEDY
                    + EGRESS_LIFT_LAYOUT_BOUND_POINTER;

    static final String EGRESS_LIFT_LAYOUT_BOUND_QUEUED_STEP =
            "One or more off-face terminal hugs will be left in place because clearing them would narrow"
                    + EGRESS_LIFT_LAYOUT_BOUND_FINDING
                    + " Nothing has been applied: this routing is queued in the open batch."
                    + EGRESS_LIFT_LAYOUT_BOUND_REMEDY
                    + " Do that after end-batch, or discard the queued routing with end-batch "
                    + "rollback:true and widen the corridor first."
                    + EGRESS_LIFT_LAYOUT_BOUND_POINTER;

    static final String EGRESS_LIFT_LAYOUT_BOUND_PROPOSED_STEP =
            "One or more off-face terminal hugs will be left in place because clearing them would narrow"
                    + EGRESS_LIFT_LAYOUT_BOUND_FINDING
                    + " Nothing has been applied: this routing is waiting on the human's decision, "
                    + "so the hugs will only exist once it is approved."
                    + EGRESS_LIFT_LAYOUT_BOUND_REMEDY
                    + " Do that once the change has been approved or rejected."
                    + EGRESS_LIFT_LAYOUT_BOUND_POINTER;

    /** True when the auto-route result carries the layout-bound egress-lift structured warning. */
    private boolean hasEgressLiftLayoutBoundWarning(AutoRouteResultDto entity) {
        return entity != null && entity.structuredWarnings() != null
                && entity.structuredWarnings().stream().anyMatch(
                        w -> StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND.equals(w.code()));
    }

    /**
     * nextSteps guidance for a re-route that raised edge crossings above the geometry it
     * replaced. The route was applied — this tells the caller what was measured, and what the
     * recovery would cost.
     *
     * <p>It used to open "the view was better before the call", which is a whole-view verdict
     * drawn from a single metric: the signal behind it holds two crossing counts and nothing
     * else. Edge crossings are the most tolerable routing defect this project measures, and a
     * full re-route that raises them very often does so while clearing an interior termination
     * or a connection running through an element — defects weighted far above them. So the
     * opening clause asserted, in the field an agent reads first, the opposite of what the
     * view's own composite rating would say, and an agent obeying it discards the better
     * geometry. The measurement is untouched; only the judgment drawn from it is withdrawn.</p>
     */
    private static final String AUTO_ROUTE_CROSSINGS_REGRESSED_HEAD =
            "This re-route increased edge crossings. ";

    /**
     * The same finding for an arm on which nothing was written.
     *
     * <p>"The view was better before the call" says the view is worse <em>now</em>, and on a queued
     * or awaiting-approval call it is not: the router ran, counted the crossings and built the
     * commands, and the model still holds the geometry it started with. The next sentence does say
     * "Nothing has been applied", so the string was self-correcting rather than false — but a
     * disclosure whose first clause has to be walked back by its second is asking the reader to do
     * work the writer should have done, and an agent that keys off the opening phrase never reaches
     * the correction.</p>
     *
     * <p>The measurement is untouched: the crossing increase is real and established whatever
     * becomes of the commands. Only the claim about the model's present state moves.</p>
     */
    private static final String AUTO_ROUTE_CROSSINGS_REGRESSED_DEFERRED_HEAD =
            "This re-route increased edge crossings against the geometry it would replace — "
                    + "applying it would leave the view worse than it is now. ";

    /**
     * The measurement and the alternative, kept on every arm.
     *
     * <p>A queued or awaiting-approval re-route still ran the router and still counted the
     * crossings, so the fact is established whatever happens to the model next. Withdrawing it
     * along with the recovery sentence would delete something legitimately measured, and
     * {@code terminals-only} is the right second attempt regardless of which arm the first one
     * is sitting on.</p>
     */
    private static final String AUTO_ROUTE_CROSSINGS_REGRESSED_TAIL =
            "If the goal was "
                    + "to straighten diagonal terminals on an already-tidy layout, re-run with mode "
                    + "'terminals-only' instead: it fixes terminals without re-routing connection "
                    + "interiors and declines any rectification that would add crossings. See the "
                    + "structuredWarnings entry (AUTO_ROUTE_CROSSINGS_REGRESSED) for the counts.";

    private static final String AUTO_ROUTE_CROSSINGS_REGRESSED_STEP =
            AUTO_ROUTE_CROSSINGS_REGRESSED_HEAD
                    + "The new paths were applied anyway. Crossings alone do not determine layout "
                    + "quality — a routed view can score worse here and still read better "
                    + "overall, so review the view before deciding. Undo reverts the whole "
                    + "routing pass, not just the crossings. "
                    + AUTO_ROUTE_CROSSINGS_REGRESSED_TAIL;

    /**
     * The same measurement for a queued re-route, where {@code undo} recovers somebody else's
     * command.
     *
     * <p>Sibling of {@code AUTO_LAYOUT_RATING_REGRESSED_QUEUED_STEP}, and born of the same defect:
     * the applied-arm constant was reused verbatim on the batch branch, so one response said both
     * "The new paths were applied anyway. Undo to restore the previous paths" and "Mutation queued
     * as operation #1 in current batch".</p>
     */
    static final String AUTO_ROUTE_CROSSINGS_REGRESSED_QUEUED_STEP =
            AUTO_ROUTE_CROSSINGS_REGRESSED_DEFERRED_HEAD
                    + "Nothing has been applied: the re-route is queued in the open batch, so undo "
                    + "is not the remedy here and would revert whichever command is actually on top "
                    + "of the stack. Discard the queued re-route with end-batch rollback:true, or "
                    + "commit it with end-batch and re-run assess-layout to see what landed. "
                    + AUTO_ROUTE_CROSSINGS_REGRESSED_TAIL;

    /**
     * The same measurement for a re-route awaiting a human's decision.
     *
     * <p>Names no tool for the recovery, because none of them performs it: the agent cannot approve
     * or reject its own proposal, and an arm that names a tool the caller cannot usefully run is
     * worse than one that names none.</p>
     */
    static final String AUTO_ROUTE_CROSSINGS_REGRESSED_PROPOSED_STEP =
            AUTO_ROUTE_CROSSINGS_REGRESSED_DEFERRED_HEAD
                    + "Nothing has been applied: the re-route is waiting on the human's decision, "
                    + "so undo is not the remedy here and would revert whichever command is actually "
                    + "on top of the stack. Rejecting the change in Archi leaves the previous paths "
                    + "exactly as they are. "
                    + AUTO_ROUTE_CROSSINGS_REGRESSED_TAIL;

    /**
     * nextSteps guidance for routes applied straight through a note.
     *
     * <p>Mirrors {@link #AUTO_ROUTE_CROSSINGS_REGRESSED_STEP} in shape and deliberately NOT in
     * remedy. That one offers {@code undo}, because a re-route that raised crossings produced a
     * worse route and the previous route was the better artefact. Here the route is not the thing
     * that should change: the router did exactly what it is documented to do, and it is the note
     * that is in the way. So the remedy is {@code update-view-object} on the note, and the caller
     * is told the fixed-point fact plainly — moving a note changes the routes around it, so the
     * result has to be re-assessed rather than assumed.
     *
     * <p>Names the note ids rather than pointing at the structured entry for them: this is the
     * step an agent acts on directly, and the id is the argument the remedy takes.
     *
     * @return the guidance sentence, or null when no route crossed a note
     */
    /** How the routes are described, given what has actually happened to them. */
    private static String noteCrossingSubject(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "One or more applied routes pass through a note (";
            case QUEUED -> "One or more of the routes this call queued pass through a note (";
            case AWAITING_APPROVAL ->
                    "One or more of the routes this call proposes pass through a note (";
        };
    }

    /**
     * Why this is not the thing to reverse — phrased for what reversing would mean on this arm.
     *
     * <p>The point survives the rescope unchanged: the route is correct and the note is in the way,
     * so discarding the route is the wrong move. What changes is the name of the action that would
     * discard it.</p>
     */
    private static String noteCrossingNotTheProblem(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "do not undo the route. ";
            case QUEUED -> "nothing has been applied yet, and this is not a reason to discard "
                    + "the batch. ";
            case AWAITING_APPROVAL -> "nothing has been applied yet, and this is not a reason to "
                    + "reject the change. ";
        };
    }

    /** The remedy that moves the note, true on every arm — only when to run it differs. */
    private static String noteCrossingRemedy(DispatchArm arm) {
        String shared = "Move the note clear with update-view-object "
                + "instead, then re-run assess-layout: moving a note changes the routes around "
                + "it, so the crossing count after the move is not predictable from the one "
                + "before it. Position notes after routing for this reason.";
        // The shared sentence says "then re-run assess-layout", which is the immediate arm's
        // timing. On a queued call that read is premature, so this supersedes it rather than
        // adding a second, differently-timed instruction.
        String timing = arm == DispatchArm.QUEUED
                ? " You can queue that move into this same batch — in which case the "
                        + "assess-layout above belongs after end-batch, not now."
                : "";
        return shared + timing + " See the "
                + "structuredWarnings entry (CONNECTION_ROUTED_THROUGH_NOTE) for the pairs.";
    }

    private String connectionThroughNoteStep(AutoRouteResultDto entity, DispatchArm arm) {
        if (entity == null || entity.structuredWarnings() == null) {
            return null;
        }
        for (StructuredWarningDto warning : entity.structuredWarnings()) {
            if (!StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE.equals(warning.code())) {
                continue;
            }
            return noteCrossingSubject(arm)
                    + String.join(", ", warning.remediationViolatorIds())
                    + "). A note is not a routing obstacle, so this is deliberate rather than a "
                    + "failure — " + noteCrossingNotTheProblem(arm) + noteCrossingRemedy(arm);
        }
        return null;
    }

    /**
     * nextSteps guidance for a quality loop that committed a view worse than the one it was handed.
     *
     * <p>Mirrors {@link #AUTO_ROUTE_CROSSINGS_REGRESSED_STEP} in shape and in remedy — the applied
     * state is the thing that should change — on a different measure: the overall assessment the
     * loop already ranks its own attempts by, rather than edge crossings alone.</p>
     *
     * <p>Says that recovery is <em>one</em> call. The winning attempt is merged into a single
     * compound command before dispatch, so a caller that does not know that may not attempt the
     * undo at all.</p>
     */
    private static final String AUTO_LAYOUT_RATING_REGRESSED_STEP =
            "This run left the view worse than it found it — the view was better before the call. "
                    + "The new layout and routes were applied anyway. Undo restores the previous "
                    + "state, and one undo is enough because the whole run was committed as a "
                    + "single compound operation. See the structuredWarnings entry "
                    + "(AUTO_LAYOUT_RATING_REGRESSED) for the ratings and the metric counts that "
                    + "moved, and read ratingBefore beside achievedRating.";

    /**
     * The finding for an arm on which nothing was written, shared by the two deferred constants.
     *
     * <p>Sibling of {@code AUTO_ROUTE_CROSSINGS_REGRESSED_DEFERRED_HEAD} and moved for the same
     * reason: "the view was better before the call" says the view is worse <em>now</em>, which is
     * false on an arm where the loop's result is still sitting in a queue or on a card. Both tools
     * are re-tensed together because the two heads were deliberately identical, and re-tensing one
     * would split the wording of two sibling disclosures — which is precisely why this was deferred
     * out of the review that found it rather than patched there.</p>
     */
    private static final String AUTO_LAYOUT_RATING_REGRESSED_DEFERRED_HEAD =
            "This run produced a view worse than the one it was handed — applying it would leave "
                    + "the view worse than it is now. ";

    /**
     * The same disclosure for a call that was queued into an open batch, where the remedy differs.
     *
     * <p>{@link #AUTO_LAYOUT_RATING_REGRESSED_STEP} was reused here, so one response carried both
     * "the new layout and routes were applied anyway. Undo restores the previous state" and
     * "mutation queued as operation #1 in current batch". Nothing had been applied: an agent
     * obeying the first sentence reverts whichever command is actually on top of the stack, or on
     * a clean stack reverts nothing and then re-reads a view that is still degraded — and an agent
     * that concludes undo does not work stops attempting recoverable operations at all.</p>
     *
     * <p>The measurement is not withdrawn with the remedy, and this is <em>not</em> the spacing
     * family's {@link #SPACING_COMPARISON_UNAVAILABLE_QUEUED_STEP} abstention. That family abstains
     * because its control loop has already undone every accepted command, so its {@code after}
     * snapshot re-reads an unmutated view and the comparison is structurally unavailable. This loop
     * measures its best attempt inside the temporary dispatch window, before the queue decision, so
     * the regression here is genuinely measured. Suppressing it would delete a fact that was
     * legitimately established; only the recovery sentence was ever wrong.</p>
     */
    private static final String AUTO_LAYOUT_RATING_REGRESSED_QUEUED_STEP =
            AUTO_LAYOUT_RATING_REGRESSED_DEFERRED_HEAD
                    + "Nothing has been applied: the run is queued in the open batch, so undo is "
                    + "not the remedy here and would revert whichever command is actually on top "
                    + "of the stack. Discard the queued run with end-batch rollback:true, or "
                    + "commit it with end-batch and re-run assess-layout to see what landed. See "
                    + "the structuredWarnings entry (AUTO_LAYOUT_RATING_REGRESSED) for the ratings "
                    + "and the metric counts that moved, and read ratingBefore beside "
                    + "achievedRating.";

    /**
     * The same disclosure for a run held for a human's decision.
     *
     * <p>Third arm of the pair above, and owed for the same reason: the loop ran, ranked its
     * attempts and measured the regression inside its own temporary dispatch window, all before
     * the approval gate was consulted. The measurement is real; only the recovery differs, and on
     * this arm there is no tool that performs it — the agent cannot approve or reject its own
     * change, so naming one would be worse than naming none.</p>
     */
    private static final String AUTO_LAYOUT_RATING_REGRESSED_PROPOSED_STEP =
            AUTO_LAYOUT_RATING_REGRESSED_DEFERRED_HEAD
                    + "Nothing has been applied: the run is waiting on the human's decision, so "
                    + "undo is not the remedy here and would revert whichever command is actually "
                    + "on top of the stack. Rejecting the change in Archi leaves the previous "
                    + "state exactly as it is. See the structuredWarnings entry "
                    + "(AUTO_LAYOUT_RATING_REGRESSED) for the ratings and the metric counts that "
                    + "moved, and read ratingBefore beside achievedRating.";

    /**
     * The spacing family's rating-regression disclosure, in the channel a caller reads first.
     *
     * <p>Mirrors {@link #AUTO_LAYOUT_RATING_REGRESSED_STEP} in shape and remedy. It is emitted
     * whenever the warning fired and is deliberately independent of {@code terminationReason}, of
     * {@code noChangeReason} and of the knee-clamp branches: a run can terminate on any of the ten
     * branches, report success on every other field, and still have left the view worse. Gating it
     * on any of them would hide exactly the case it exists for.</p>
     *
     * <p>The pre-existing unconditional "use undo if unsatisfactory" line stays where it is and is
     * NOT repurposed as this disclosure — it fires on clean runs too and says nothing was
     * measured.</p>
     */
    private static final String SPACING_RATING_REGRESSED_STEP =
            "This run left the view worse than it found it — the view was better before the call. "
                    + "The spacing was applied anyway. Undo restores the previous state, and one "
                    + "undo is enough because every accepted iteration was committed as a single "
                    + "compound operation. See the structuredWarnings entry "
                    + "(SPACING_RATING_REGRESSED) for both ratings and the metric counts that "
                    + "moved, and compare the before and after snapshots yourself.";

    /**
     * The queued-call abstention: the comparison is structurally unavailable, said out loud.
     *
     * <p>When a batch is open the accepted commands are queued instead of dispatched and the
     * control loop has already undone every one of them, so the {@code after} snapshot re-reads a
     * view the queued commands have not touched. Any comparison drawn from it would report "no
     * regression" because nothing was measured, not because the state held.</p>
     *
     * <p>This is why the queued arm does not follow the auto-route precedent of adding its warning
     * steps to the batch branch: there is no warning to add. An abstention is a claim about
     * coverage, and a silent omission would read as "nothing regressed".</p>
     */
    private static final String SPACING_COMPARISON_UNAVAILABLE_QUEUED_STEP =
            "Layout-quality comparison could not be taken for a queued call: nothing has been "
                    + "applied yet, so the before and after snapshots in this response describe "
                    + "the same unmutated view. Run assess-layout after end-batch to find out "
                    + "whether the queued spacing improved the view or degraded it.";

    /** True when a spacing result carries the rating-regression structured warning. */
    private boolean hasSpacingRatingRegressedWarning(List<StructuredWarningDto> warnings) {
        return warnings != null && warnings.stream().anyMatch(
                w -> StructuredWarningCodes.SPACING_RATING_REGRESSED.equals(w.code()));
    }

    /** True when the auto-layout result carries the rating-regression structured warning. */
    private boolean hasRatingRegressedWarning(AutoLayoutAndRouteResultDto entity) {
        return entity != null && entity.structuredWarnings() != null
                && entity.structuredWarnings().stream().anyMatch(
                        w -> StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED.equals(w.code()));
    }

    /** True when the auto-route result carries the non-monotonic re-route structured warning. */
    private boolean hasCrossingsRegressedWarning(AutoRouteResultDto entity) {
        return entity != null && entity.structuredWarnings() != null
                && entity.structuredWarnings().stream().anyMatch(
                        w -> StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED.equals(w.code()));
    }

    /**
     * nextSteps guidance for a call whose {@code connectionIds} named one or more connections
     * that are not on the view. The remaining connections were routed normally.
     *
     * <p>Package-private so the handler tests pin this exact text by reference rather than by a
     * brittle string literal that silently stops matching if the wording is ever changed.</p>
     */
    private static final String CONNECTION_NOT_FOUND_HEAD =
            "One or more of the connection IDs you passed are not on this view and were skipped; ";

    /** Where the exact ids are, and how to find the ones that do exist. True on every arm. */
    private static final String CONNECTION_NOT_FOUND_TAIL =
            " See the structuredWarnings entry "
                    + "(CONNECTION_NOT_FOUND) for the exact IDs in remediationViolatorIds. Run "
                    + "get-view-contents to list the connection IDs that exist on this view.";

    static final String CONNECTION_NOT_FOUND_STEP =
            CONNECTION_NOT_FOUND_HEAD
                    + "the connections that were found are routed."
                    + CONNECTION_NOT_FOUND_TAIL;

    /**
     * The same miss on a queued call, where the surviving connections are not routed yet.
     *
     * <p>Package-private for the same reason as its applied sibling: the tests pin membership by
     * reference rather than by a literal that stops matching the moment the wording changes.</p>
     */
    static final String CONNECTION_NOT_FOUND_QUEUED_STEP =
            CONNECTION_NOT_FOUND_HEAD
                    + "nothing has been applied — the connections that were found are queued in "
                    + "the open batch and will be routed when it commits. Discard the queued "
                    + "routing with end-batch rollback:true and re-issue it with the corrected "
                    + "IDs, or commit it with end-batch."
                    + CONNECTION_NOT_FOUND_TAIL;

    /** The same miss on a call awaiting a human's decision. Names no recovery tool. */
    static final String CONNECTION_NOT_FOUND_PROPOSED_STEP =
            CONNECTION_NOT_FOUND_HEAD
                    + "nothing has been applied — the connections that were found will be routed "
                    + "only if the human approves this change, and the IDs that were skipped will "
                    + "still be missing from that routing."
                    + CONNECTION_NOT_FOUND_TAIL;

    /** True when the auto-route result carries the connection-not-found structured warning. */
    private boolean hasConnectionNotFoundWarning(AutoRouteResultDto entity) {
        return entity != null && entity.structuredWarnings() != null
                && entity.structuredWarnings().stream().anyMatch(
                        w -> StructuredWarningCodes.CONNECTION_NOT_FOUND.equals(w.code()));
    }

    /**
     * The four state-dependent disclosures, composed once for whichever arm is asking.
     *
     * <p>Every arm runs the same four guards over the same structured warnings, so a code cannot
     * be disclosed on one arm and silently dropped on another — which is how the batch branch,
     * built as a separate list beside the immediate one, ended up reusing four applied-arm
     * sentences verbatim.</p>
     */
    private List<String> autoRouteDisclosures(AutoRouteResultDto entity, DispatchArm arm) {
        List<String> disclosures = new ArrayList<>();
        if (hasConnectionNotFoundWarning(entity)) {
            disclosures.add(switch (arm) {
                case APPLIED -> CONNECTION_NOT_FOUND_STEP;
                case QUEUED -> CONNECTION_NOT_FOUND_QUEUED_STEP;
                case AWAITING_APPROVAL -> CONNECTION_NOT_FOUND_PROPOSED_STEP;
            });
        }
        if (hasEgressLiftLayoutBoundWarning(entity)) {
            disclosures.add(switch (arm) {
                case APPLIED -> EGRESS_LIFT_LAYOUT_BOUND_STEP;
                case QUEUED -> EGRESS_LIFT_LAYOUT_BOUND_QUEUED_STEP;
                case AWAITING_APPROVAL -> EGRESS_LIFT_LAYOUT_BOUND_PROPOSED_STEP;
            });
        }
        if (hasCrossingsRegressedWarning(entity)) {
            disclosures.add(switch (arm) {
                case APPLIED -> AUTO_ROUTE_CROSSINGS_REGRESSED_STEP;
                case QUEUED -> AUTO_ROUTE_CROSSINGS_REGRESSED_QUEUED_STEP;
                case AWAITING_APPROVAL -> AUTO_ROUTE_CROSSINGS_REGRESSED_PROPOSED_STEP;
            });
        }
        String noteStep = connectionThroughNoteStep(entity, arm);
        if (noteStep != null) {
            disclosures.add(noteStep);
        }
        return disclosures;
    }

    /**
     * What an auto-route response carries when the write is waiting on a human.
     *
     * <p>Supplied on its own parameter rather than by forwarding the immediate list, which is
     * present tense by construction and would tell an unapproved caller their paths were applied.
     * The three fixed approval lines stay first; these follow them.</p>
     */
    private List<String> buildAutoRouteApprovalDisclosures(
            MutationResult<AutoRouteResultDto> result) {
        return autoRouteDisclosures(result.entity(), DispatchArm.AWAITING_APPROVAL);
    }

    private List<String> buildAutoRouteNextSteps(
            MutationResult<AutoRouteResultDto> result) {
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>();
            if (result.entity() != null && result.entity().routerTypeSwitched()) {
                batchSteps.add("View router type will be switched from manhattan "
                        + "to manual (bendpoint mode) when batch is committed.");
            }
            batchSteps.addAll(autoRouteDisclosures(result.entity(), DispatchArm.QUEUED));
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        List<String> steps = new ArrayList<>();
        if (result.entity() != null && result.entity().routerTypeSwitched()) {
            steps.add("View router type switched from manhattan to manual "
                    + "(bendpoint mode) so that computed obstacle-aware paths "
                    + "are rendered correctly.");
        }
        steps.addAll(autoRouteDisclosures(result.entity(), DispatchArm.APPLIED));
        if (result.entity() != null && !result.entity().violations().isEmpty()) {
            steps.add("Routes applied with " + result.entity().violations().size()
                    + " constraint violation(s). Consider using assess-layout to check "
                    + "overall quality.");
            steps.add("For higher quality, move elements per the violation details "
                    + "and re-route without force.");
        }
        if (result.entity() != null && result.entity().connectionsSkipped() > 0) {
            AutoRouteResultDto entity = result.entity();
            int obstacle = entity.vetoedByObstacle();
            int crossing = entity.vetoedByCrossing();
            int interior = entity.vetoedByInterior();
            int zigzag = entity.vetoedByZigzag();
            int alreadyOrtho = entity.connectionsSkipped()
                    - obstacle - crossing - interior - zigzag;
            StringBuilder msg = new StringBuilder();
            msg.append(entity.connectionsSkipped())
                    .append(" connection(s) left unchanged (terminals-only mode):");
            boolean first = true;
            if (alreadyOrtho > 0) {
                msg.append(' ').append(alreadyOrtho).append(" already orthogonal");
                first = false;
            }
            if (obstacle > 0) {
                msg.append(first ? ' ' : ", ").append(obstacle)
                        .append(" vetoed (L-bend would cross an unrelated element)");
                first = false;
            }
            if (crossing > 0) {
                msg.append(first ? ' ' : ", ").append(crossing)
                        .append(" vetoed (L-bend would add edge crossings)");
                first = false;
            }
            if (interior > 0) {
                msg.append(first ? ' ' : ", ").append(interior)
                        .append(" vetoed (L-bend would terminate inside an element)");
                first = false;
            }
            if (zigzag > 0) {
                msg.append(first ? ' ' : ", ").append(zigzag)
                        .append(" vetoed (L-bend would introduce a zigzag/reversal)");
                first = false; // keep the separator flag correct if a category is added below
            }
            msg.append('.');
            if (obstacle > 0 || crossing > 0 || interior > 0 || zigzag > 0) {
                msg.append(" To force-apply the vetoed rectifications, re-run with "
                        + "force=true, or increase element spacing first.");
            }
            steps.add(msg.toString());
        }
        if (result.entity() != null && !result.entity().nudgedElements().isEmpty()) {
            steps.add(result.entity().nudgedElements().size()
                    + " element(s) were automatically nudged to resolve pass-throughs. "
                    + "Check the 'nudgedElements' array for details.");
        }
        if (result.entity() != null && !result.entity().resizedGroups().isEmpty()) {
            // Deliberately not "to contain nudged elements": the parent-fit cascade is grow-only,
            // so a group grown for a move that a later iteration reversed is not shrunk back and
            // outlives the nudge that caused it. The same pass also resizes groups whose contents
            // already overflowed before this call, with no nudge involved at all.
            steps.add(result.entity().resizedGroups().size()
                    + " group(s) were auto-resized to fit their contents. "
                    + "Run arrange-groups if group alignment needs adjustment.");
        }
        if (result.entity() != null && result.entity().connectionsFailed() > 0) {
            steps.add(result.entity().connectionsFailed()
                    + " connection(s) could not be routed without crossing elements. "
                    + "Check the 'failed' array for details. Consider moving elements "
                    + "to create more routing space, then re-route the failed connections.");
            if (result.entity().recommendations() != null
                    && !result.entity().recommendations().isEmpty()) {
                steps.add("Move recommendations suggest repositioning elements to unblock "
                        + "failed connections. Use update-view-object to apply dx/dy offsets, "
                        + "then re-route, or use autoNudge=true to automate this.");
            }
        }
        steps.add("Use export-view to visually verify the connection routing.");
        steps.add("Use assess-layout to evaluate overall layout quality.");
        steps.add("To fix specific connections without re-routing the whole view, "
                + "pass connectionIds to re-route only those connections.");
        steps.add("Use update-view-connection to fine-tune individual "
                + "connection bendpoints.");
        return steps;
    }

    // ---- auto-connect-view ----

    private McpServerFeatures.SyncToolSpecification buildAutoConnectViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to auto-connect");

        Map<String, Object> elementIdsProp = new LinkedHashMap<>();
        elementIdsProp.put("type", "array");
        Map<String, Object> elemIdItems = new LinkedHashMap<>();
        elemIdItems.put("type", "string");
        elementIdsProp.put("items", elemIdItems);
        elementIdsProp.put("description",
                "Only consider relationships involving these elements. "
                + "Omit for all elements on the view.");

        Map<String, Object> relTypesProp = new LinkedHashMap<>();
        relTypesProp.put("type", "array");
        Map<String, Object> relTypeItems = new LinkedHashMap<>();
        relTypeItems.put("type", "string");
        relTypesProp.put("items", relTypeItems);
        relTypesProp.put("description",
                "Only connect relationships of these types "
                + "(e.g., [\"ServingRelationship\", \"FlowRelationship\"]). "
                + "Omit for all types.");

        Map<String, Object> relIdsProp = new LinkedHashMap<>();
        relIdsProp.put("type", "array");
        Map<String, Object> relIdItems = new LinkedHashMap<>();
        relIdItems.put("type", "string");
        relIdsProp.put("items", relIdItems);
        relIdsProp.put("description",
                "Only connect relationships whose model ID is in this list "
                + "(AND-composed with relationshipTypes/elementIds). Use to curate a "
                + "directional slice (e.g. producer→hub→consumer) or to exclude "
                + "same-type reverse flows in a single call. Each ID must be a real "
                + "relationship. Omit for all relationships.");

        Map<String, Object> showLabelProp = new LinkedHashMap<>();
        showLabelProp.put("type", "boolean");
        showLabelProp.put("description",
                "Set to false to suppress labels on all created connections. "
                + "Default is true (labels shown). Use to reduce visual clutter "
                + "on dense diagrams without needing follow-up update calls.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("elementIds", elementIdsProp);
        properties.put("relationshipTypes", relTypesProp);
        properties.put("relationshipIds", relIdsProp);
        properties.put("showLabel", showLabelProp);
        addConnectionStylingProperties(properties);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("auto-connect-view")
                .description("[Mutation] Retroactively create visual connections on a view "
                        + "for all existing model relationships between elements already "
                        + "placed on that view. Only creates missing connections \u2014 existing "
                        + "visual connections are not duplicated. Use after placing elements "
                        + "via add-to-view to batch-create all connections at once. "
                        + "RECOMMENDED: Use the relationshipTypes filter to connect only "
                        + "the relationship types relevant to the view's perspective — "
                        + "omitting the filter connects ALL relationship types which can "
                        + "clutter the diagram. "
                        + "For a precise curated slice (e.g. a directional producer→hub→consumer "
                        + "flow, or excluding same-type reverse edges), pass relationshipIds — an "
                        + "explicit allow-list of relationship IDs, AND-composed with the type/element "
                        + "filters — instead of many single add-connection-to-view calls. "
                        + "Optional: showLabel (false to suppress labels on all created connections). "
                        + "Optional: lineColor (#RRGGBB hex, empty string clears), "
                        + "fontColor (#RRGGBB hex, empty string clears), "
                        + "lineWidth (1-3) — applied to all created connections. "
                        + "NOTE: lineStyle is a view-object property only; connection line style is "
                        + "determined by the ArchiMate relationship type and cannot be overridden on "
                        + "the view. Passing lineStyle here is REJECTED (INVALID_PARAMETER) rather "
                        + "than ignored. To distinguish connections visually — synchronous versus "
                        + "asynchronous flows, for example — use lineColor and lineWidth, both "
                        + "available on this call. "
                        + "TIP: Call multiple times with different relationshipTypes + lineColor "
                        + "to colour-code connections by type (e.g. blue for API calls, orange for events). "
                        + "Pairs where one endpoint is visually nested inside the other on this view "
                        + "are skipped (a connection between ancestor and descendant on the view "
                        + "renders as a self-pass-through). Skipped pairs are reported in the response "
                        + "under skippedDueToNesting; the model relationship is preserved. "
                        + "Related: add-connection-to-view (single connection), "
                        + "auto-route-connections (compute bendpoints for existing connections).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAutoConnectView)
                .build();
    }

    McpSchema.CallToolResult handleAutoConnectView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling auto-connect-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");

            List<String> elementIds = extractStringList(args, "elementIds");
            List<String> relationshipTypes = extractStringList(args, "relationshipTypes");
            List<String> relationshipIds = extractStringList(args, "relationshipIds");
            Boolean showLabel = (args.get("showLabel") instanceof Boolean b) ? b : null;
            StylingParams styling = extractStylingParams(args);

            MutationResult<AutoConnectResultDto> result =
                    accessor.autoConnectView(sessionId, viewId, elementIds,
                            relationshipTypes, relationshipIds, showLabel, styling);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAutoConnectNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling auto-connect-view", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildAutoConnectNextSteps(
            MutationResult<AutoConnectResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Use export-view to visually verify the created connections.",
                "Use auto-route-connections to apply orthogonal routing to newly created connections.",
                "Use auto-layout-and-route if elements need repositioning after connections are added.",
                "Use assess-layout to evaluate overall diagram quality.");
    }

    // ---- layout-within-group ----

    private McpServerFeatures.SyncToolSpecification buildLayoutWithinGroupSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view containing the group");

        Map<String, Object> groupViewObjectIdProp = new LinkedHashMap<>();
        groupViewObjectIdProp.put("type", "string");
        groupViewObjectIdProp.put("description",
                "View object ID of the container (visual group OR ArchiMate-element with "
                + "nested children) to layout children within. From get-view-contents: "
                + "groups list OR any element in visualMetadata whose children list is non-empty.");

        Map<String, Object> arrangementProp = new LinkedHashMap<>();
        arrangementProp.put("type", "string");
        arrangementProp.put("description",
                "Arrangement pattern: 'row' (horizontal), 'column' (vertical), or 'grid'");

        Map<String, Object> spacingProp = new LinkedHashMap<>();
        spacingProp.put("type", "integer");
        spacingProp.put("description",
                "Space between elements in pixels (default: 40)");

        Map<String, Object> paddingProp = new LinkedHashMap<>();
        paddingProp.put("type", "integer");
        paddingProp.put("description",
                "Space from group edges in pixels (default: 10)");

        Map<String, Object> elementWidthProp = new LinkedHashMap<>();
        elementWidthProp.put("type", "integer");
        elementWidthProp.put("description",
                "Resize all children to this width before positioning. "
                + "Omit to preserve existing sizes.");

        Map<String, Object> elementHeightProp = new LinkedHashMap<>();
        elementHeightProp.put("type", "integer");
        elementHeightProp.put("description",
                "Resize all children to this height before positioning. "
                + "Omit to preserve existing sizes.");

        Map<String, Object> autoResizeProp = new LinkedHashMap<>();
        autoResizeProp.put("type", "boolean");
        autoResizeProp.put("description",
                "Resize the group to fit its children (default: false)");

        Map<String, Object> autoWidthProp = new LinkedHashMap<>();
        autoWidthProp.put("type", "boolean");
        autoWidthProp.put("description",
                "Compute each element's width from its label text so names are not "
                + "truncated (default: false). Ignored when elementWidth is set. "
                + "On a grid this is a common source of over-wide containers, because "
                + "one long name becomes the whole grid's cell width — see 'columns' "
                + "for exactly when that happens.");

        Map<String, Object> columnsProp = new LinkedHashMap<>();
        columnsProp.put("type", "integer");
        columnsProp.put("description",
                "Number of columns for grid arrangement (default: auto-detected "
                + "from group width). Capped at element count. Only used with "
                + "arrangement: 'grid'. This value also applies at EVERY nesting "
                + "level when recursiveChildren is true. CELL WIDTH: a single-level "
                + "grid gives every cell the width of the widest element in the grid, "
                + "whether or not autoWidth is set — so one wide element widens all of "
                + "them, and the container fits to that. Set elementWidth to opt out. "
                + "With recursiveChildren each column is instead sized from its own "
                + "widest member, because across levels the inflation compounds: "
                + "widened siblings fit their container wider, and that container then "
                + "widens its own siblings one level up. "
                + "ROW HEIGHT: the grid never resizes an element taller — each keeps its "
                + "own height — but it ADVANCES every row by the tallest element anywhere "
                + "in the grid, not by the tallest in that row. A short row is therefore "
                + "followed by empty space the height of the grid's tallest element, and "
                + "autoResize fits the container around that whitespace, so the cost shows "
                + "up as an over-tall container rather than as stretched elements: do not "
                + "look for it in resizedElements, it is not written there. Unlike CELL "
                + "WIDTH there is no parameter that opts out, and recursiveChildren does "
                + "NOT drop it — that flag changes only how a column's WIDTH is chosen, so "
                + "height inflates identically on both paths. With columns: 1 every element "
                + "sits in column 0, so the cell-width inflation fires as well and a "
                + "single-column grid is never equivalent to arrangement: 'column', which "
                + "writes each element its own width and advances each row by that element's "
                + "own height. Use arrangement: 'column' for a single-column inventory. How "
                + "much that saves follows from the mechanism rather than from any one "
                + "measurement: the grid fits its container to ROWS x TALLEST, the column to "
                + "the SUM of the heights, so the two agree when every child is the same "
                + "height and diverge as they differ. Measured on a six-child band of mixed "
                + "heights the grid came out 1.4x the column's container height, and on a "
                + "band whose heights varied more, close to 2x.");

        Map<String, Object> recursiveProp = new LinkedHashMap<>();
        recursiveProp.put("type", "boolean");
        recursiveProp.put("description",
                "When true and autoResize is true, recursively resize ancestor "
                + "groups to fit their children (default: false). Propagates "
                + "sizing UPWARD through the nesting hierarchy — through NATIVE "
                + "view groups only. TWO SEPARATE restrictions follow from that, "
                + "and both really fire. (1) The walk only STARTS from a native "
                + "view group: if the groupViewObjectId you named is an "
                + "ArchiMate-element container (a Node, an ApplicationComponent, a "
                + "Grouping), the upward pass does not run at all and nothing above "
                + "it is resized — call again naming an enclosing view group. "
                + "(2) An ArchiMate Grouping element acting as a "
                + "container is not a native group: the walk STOPS at the first "
                + "such ancestor rather than skipping past it, so a Grouping "
                + "parent can be left too small for the child this call just grew "
                + "— call again on that parent. Do not infer which of these "
                + "happened from ancestorsResized: 0 is true in several situations "
                + "that ask for different actions, and the walk can also stop early "
                + "having ALREADY resized some ancestors, which is a non-zero count "
                + "hiding an unfinished parent. Read 'ancestorPropagation' instead — "
                + "it is on every response and says exactly how the upward pass "
                + "ended. Distinct from recursiveChildren, which arranges descendants DOWNWARD.");

        Map<String, Object> recursiveChildrenProp = new LinkedHashMap<>();
        recursiveChildrenProp.put("type", "boolean");
        recursiveChildrenProp.put("description",
                "When true, recursively arrange DESCENDANTS through the whole nesting "
                + "hierarchy in one call, bottom-up: the innermost containers' children "
                + "are arranged and each inner container is sized to fit first, then each "
                + "level up is arranged treating every sized inner container as a fixed box "
                + "(default: false). Use for multi-level nested inventories — "
                + "functions-in-components-in-domains, or region/zone/node/artifact — so you "
                + "don't hand-compute coordinates per level. Inner containers are always "
                + "resized to fit; the requested root container is resized only when "
                + "autoResize is also true. The same arrangement/spacing/padding/autoWidth/"
                + "columns apply at every level — one 'columns' value is used at every depth, "
                + "so pick it for the level with the most children. Grid columns are sized from "
                + "their own members here rather than from the widest element in the grid, so a "
                + "single over-wide descendant does not widen its siblings at every level up. "
                + "THAT IS A GUARANTEE ACROSS LEVELS ONLY, NOT WITHIN A COLUMN: a column is still "
                + "sized by its own widest member, and an inner container that fitted wide is one "
                + "of those members, so a narrow element sharing its column is stretched to the "
                + "container's width. Measured: an element went from 120 to 2230 pixels this way. "
                + "Read 'resizedElements' for every child this happened to. Setting elementWidth "
                + "does NOT "
                + "prevent it here, unlike on a single-level grid: the parameter sets what an "
                + "element "
                + "CONTRIBUTES to its column, and the column still takes the fitted container's "
                + "larger width. The remedy is to stop them sharing a column — change 'columns', "
                + "or arrange that container in its own call. "
                + "Orthogonal to 'recursive' (which resizes ancestors "
                + "UPWARD); both may be set together.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("groupViewObjectId", groupViewObjectIdProp);
        properties.put("arrangement", arrangementProp);
        properties.put("spacing", spacingProp);
        properties.put("padding", paddingProp);
        properties.put("elementWidth", elementWidthProp);
        properties.put("elementHeight", elementHeightProp);
        properties.put("autoResize", autoResizeProp);
        properties.put("autoWidth", autoWidthProp);
        properties.put("columns", columnsProp);
        properties.put("recursive", recursiveProp);
        properties.put("recursiveChildren", recursiveChildrenProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties,
                List.of("viewId", "groupViewObjectId", "arrangement"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("layout-within-group")
                .description("[Mutation] Arrange child elements within a visual group "
                        + "OR an ArchiMate-element container (e.g. ApplicationComponent, "
                        + "Node, ApplicationFunction) using row, column, or grid patterns. "
                        + "Computes positions "
                        + "server-side so the LLM doesn't need to calculate coordinates. "
                        + "By default repositions only the direct children of the specified "
                        + "container (sub-containers treated as fixed boxes). Set "
                        + "'recursiveChildren' to arrange an entire multi-level nesting "
                        + "hierarchy in one call (bottom-up), removing the need to hand-compute "
                        + "coordinates for functions-in-components-in-domains or "
                        + "region/zone/node/artifact layouts. Use 'columns' to control grid shape "
                        + "and 'recursive' with 'autoResize' to propagate sizing to parent "
                        + "groups automatically — noting that the upward pass runs only from a "
                        + "native view group, so naming an ArchiMate-element container here "
                        + "arranges its children but resizes nothing above it. "
                        + "SPECULATIVE EXECUTION: To preview "
                        + "arrangement quality, apply layout → assess-layout → undo if "
                        + "unsatisfied (e.g., try different spacing or arrangement, then "
                        + "undo and retry). "
                        + "RESPONSE: 'elementsRepositioned' is how many direct children moved; "
                        + "'arrangement' and 'columnsUsed' echo the pattern actually applied "
                        + "(columnsUsed is null for row/column). 'groupResized' is an OBSERVATION, "
                        + "not an echo of your autoResize request — it is false when the group's "
                        + "size did not change, even if autoResize was true — and "
                        + "'newGroupWidth'/'newGroupHeight' carry the size it ended at when it did. "
                        + "'overflow' is true when child positions exceed the group's bounds "
                        + "(only possible with autoResize=false). 'autoWidth' reports whether "
                        + "element widths were computed from label text. 'ancestorsResized' counts "
                        + "the enclosing groups the upward pass actually re-fitted and "
                        + "'resizedAncestors' says what each of them BECAME (id plus the new "
                        + "rectangle), so you never have to re-query to learn where a container "
                        + "above the one you named ended up; both describe the same set, and an "
                        + "ancestor recomputed to an unchanged rectangle appears in neither "
                        + "('resizedAncestors' is omitted entirely when empty). "
                        + "'ancestorPropagation' is on EVERY response and says how the upward "
                        + "pass ended, as a stable code, a colon and a short phrase. Triage on "
                        + "the code: 'not-requested' (you did not set recursive), "
                        + "'auto-resize-not-requested' (recursive without autoResize), "
                        + "'container-not-a-native-group' (you named an ArchiMate-element "
                        + "container, so the pass never started — call again naming an enclosing "
                        + "view group), 'no-ancestor' (nothing above it; you are done), "
                        + "'stopped-at-non-native-ancestor' (it met a Grouping or element parent "
                        + "and stopped — call again on THAT parent, which is now too small), "
                        + "'depth-cap-reached' (nesting deeper than the pass will walk; call "
                        + "again higher up), 'all-ancestors-already-fitted' (it walked and found "
                        + "nothing to change; you are done) and 'propagated' (it re-fitted "
                        + "everything up to the view). Read this rather than inferring from "
                        + "'ancestorsResized': the count does not identify the reason in either "
                        + "direction. Only 'propagated' guarantees a count above 0; five of the "
                        + "codes always report 0; and BOTH 'stopped-at-non-native-ancestor' and "
                        + "'depth-cap-reached' can arrive with 0 or with a count above 0 — those "
                        + "two are the ones that leave a container above yours too small, so a "
                        + "positive count there is not success. With "
                        + "'recursiveChildren', 'nestedContainersArranged' counts the descendant "
                        + "containers arranged, 'nestedContainersFitted' says what each of them "
                        + "BECAME (id plus the new rectangle) so you never have to re-read the view "
                        + "to learn where a container BELOW the one you named ended up — the mirror "
                        + "of 'resizedAncestors', which covers the ones above it — "
                        + "and 'maxDepthReached' is the deepest level touched "
                        + "(0 = direct children only). THIS CALL ALSO RESIZES ELEMENTS, on BOTH "
                        + "paths: every child is written a full rectangle, and in a grid that "
                        + "rectangle is its CELL, so an element lands at its column's width "
                        + "however narrow it was. This is NOT conditional on autoWidth and NOT "
                        + "conditional on recursiveChildren — leaving autoWidth unset does not "
                        + "opt out of it. 'resizedElements' names every child whose SIZE actually "
                        + "changed, with the rectangle it ended at, so you do not plan your next "
                        + "call against a width this one already replaced; it is an observation "
                        + "(a child re-written to the size it already had does not appear), it is "
                        + "disjoint from 'nestedContainersFitted' (a container the pass descended "
                        + "into is named there, never here), and it is omitted entirely when "
                        + "empty. A child that only MOVED and kept its size is not in it — this "
                        + "list is about size alone. On a SINGLE-LEVEL grid, set elementWidth to "
                        + "choose the width yourself instead; with recursiveChildren elementWidth "
                        + "does NOT prevent the stretch — see that property. In a batch or pending approval nothing has "
                        + "executed, so no outcome is reported and the entity is nested under 'preview' — "
                        + "do not read that absence as 'nothing changed'. "
                        + "Related: add-group-to-view (create groups), "
                        + "add-to-view with parentViewObjectId (nest elements), "
                        + "get-view-contents (find groupViewObjectId in groups list), "
                        + "assess-layout (evaluate result), undo (roll back if unsatisfied).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleLayoutWithinGroup)
                .build();
    }

    McpSchema.CallToolResult handleLayoutWithinGroup(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling layout-within-group request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String groupViewObjectId = HandlerUtils.requireStringParam(args, "groupViewObjectId");
            String arrangement = HandlerUtils.requireStringParam(args, "arrangement");

            // Optional parameters
            Integer spacing = HandlerUtils.optionalIntegerParam(args, "spacing");
            Integer padding = HandlerUtils.optionalIntegerParam(args, "padding");
            Integer elementWidth = HandlerUtils.optionalIntegerParam(args, "elementWidth");
            Integer elementHeight = HandlerUtils.optionalIntegerParam(args, "elementHeight");
            boolean autoResize = HandlerUtils.optionalBooleanParam(args, "autoResize", false);
            boolean autoWidth = HandlerUtils.optionalBooleanParam(args, "autoWidth", false);
            Integer columns = HandlerUtils.optionalIntegerParam(args, "columns");
            boolean recursive = HandlerUtils.optionalBooleanParam(args, "recursive", false);
            boolean recursiveChildren = HandlerUtils.optionalBooleanParam(args, "recursiveChildren", false);

            MutationResult<LayoutWithinGroupResultDto> result =
                    accessor.layoutWithinGroup(sessionId, viewId, groupViewObjectId,
                            arrangement, spacing, padding, elementWidth, elementHeight,
                            autoResize, autoWidth, columns, recursive, recursiveChildren);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildLayoutWithinGroupNextSteps(result),
                    buildLayoutWithinGroupApprovalDisclosures(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling layout-within-group", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * What a caller learns about a layout that has not been written yet.
     *
     * <p>All four fields these steps are selected on — {@code overflow},
     * {@code nestedContainersArranged}, {@code maxDepthReached} and {@code ancestorPropagation} —
     * are computed before the dispatch decision and travel on the same DTO whichever arm it takes,
     * so none of them is a request value dressed as an outcome and none is absent here. The
     * geometry they describe is real; only the tense of what has happened to it differs.</p>
     */
    private static String overflowStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "WARNING: Children overflow the group bounds. "
                    + "Use autoResize: true or manually resize the group.";
            case QUEUED -> "WARNING: Children will overflow the group bounds once this batch "
                    + "commits. Nothing has been applied yet — discard the queued layout with "
                    + "end-batch rollback:true and re-issue it with autoResize: true, or resize "
                    + "the group yourself after end-batch.";
            case AWAITING_APPROVAL -> "WARNING: Children will overflow the group bounds if this "
                    + "change is approved. Nothing has been applied yet, so re-issuing the call "
                    + "with autoResize: true would propose a layout that fits instead.";
        };
    }

    private static String nestedContainersStep(LayoutWithinGroupResultDto entity, DispatchArm arm) {
        String count = entity.nestedContainersArranged()
                + " nested container(s) across " + (entity.maxDepthReached() + 1) + " level(s)";
        return switch (arm) {
            case APPLIED -> "Arranged " + count
                    + " bottom-up. Use undo to roll back the entire recursive layout.";
            case QUEUED -> count + " will be arranged bottom-up when this batch commits. Nothing "
                    + "has been applied yet, so undo would revert whichever command is actually on "
                    + "top of the stack; discard the whole recursive layout with end-batch "
                    + "rollback:true instead.";
            case AWAITING_APPROVAL -> count + " will be arranged bottom-up if this change is "
                    + "approved. Nothing has been applied yet, so there is nothing to roll back.";
        };
    }

    /**
     * The remedy for a container the walk refused to start from.
     *
     * <p>The remedy itself holds on every arm — it is a different call to make, not a way to
     * recover this one — so it is composed once. Only the claim about what has happened to the
     * ancestors is selected.</p>
     */
    private static final String CONTAINER_NOT_NATIVE_REMEDY =
            " the upward pass runs only "
                    + "from a native view group and you named an ArchiMate-element container. "
                    + "Call layout-within-group again naming an enclosing view group, or use "
                    + "resize-elements-to-fit on the parent.";

    private static String containerNotNativeStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "Nothing above this container was resized:" + CONTAINER_NOT_NATIVE_REMEDY;
            case QUEUED -> "Nothing above this container will be resized when this batch commits:"
                    + CONTAINER_NOT_NATIVE_REMEDY + " You can queue that call into this same batch.";
            case AWAITING_APPROVAL -> "Nothing above this container will be resized if this change "
                    + "is approved:" + CONTAINER_NOT_NATIVE_REMEDY
                    + " Do that once the change has been approved or rejected — until then the "
                    + "container this describes has not grown.";
        };
    }

    private static final String STOPPED_AT_NON_NATIVE_REMEDY =
            " Call layout-within-group or resize-elements-to-fit on that parent.";

    private static String stoppedAtNonNativeStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "The upward pass stopped at a Grouping or element parent, which is now "
                    + "too small for what this call grew inside it."
                    + STOPPED_AT_NON_NATIVE_REMEDY;
            case QUEUED -> "The upward pass stopped at a Grouping or element parent, which will be "
                    + "too small for what this call grows inside it once this batch commits."
                    + STOPPED_AT_NON_NATIVE_REMEDY + " You can queue that call into this same batch.";
            case AWAITING_APPROVAL -> "The upward pass stopped at a Grouping or element parent, "
                    + "which will be too small for what this call grows inside it if this change "
                    + "is approved." + STOPPED_AT_NON_NATIVE_REMEDY
                    + " Do that once the change has been approved or rejected — until then that "
                    + "parent is still the right size.";
        };
    }

    private static final String DEPTH_CAP_EVIDENCE =
            "The upward pass stopped at its nesting limit with groups still above "
                    + "the last one it re-fitted.";

    private static String depthCapStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> DEPTH_CAP_EVIDENCE + " Call layout-within-group again higher up the "
                    + "nesting to finish propagating.";
            case QUEUED -> DEPTH_CAP_EVIDENCE + " Nothing has been applied yet: call "
                    + "layout-within-group again higher up the nesting to finish propagating — you "
                    + "can queue that call into this same batch.";
            case AWAITING_APPROVAL -> DEPTH_CAP_EVIDENCE + " Nothing has been applied yet: once "
                    + "this change is approved, call layout-within-group again higher up the "
                    + "nesting to finish propagating.";
        };
    }

    /**
     * The five conditional steps, selected once and phrased for the arm.
     *
     * <p>Prepended in this order so the propagation outcomes lead: a caller that cannot see the
     * canvas needs to know a container above the one it named is the wrong size before it is told
     * how to verify the one it asked about.</p>
     */
    private List<String> layoutWithinGroupDisclosures(
            LayoutWithinGroupResultDto entity, DispatchArm arm) {
        List<String> steps = new ArrayList<>();
        if (entity == null) {
            return steps;
        }
        if (entity.nestedContainersArranged() > 0) {
            steps.add(0, nestedContainersStep(entity, arm));
        }
        if (entity.overflow()) {
            steps.add(0, overflowStep(arm));
        }
        // The three propagation outcomes that leave work undone. Each is reported otherwise only as
        // a reason string, and a caller that cannot see the canvas has no other way to learn that a
        // container above the one it named is now too small for what this call just grew. The
        // remaining five are terminal or are the caller's own choice, and need no step.
        //
        // Every arm selects through codeOf, never through a literal prefix: codeOf refuses a
        // colonless reason, and an empty prefix would make every startsWith match and fire all
        // three remedies on a call that needs none.
        if (entity.ancestorPropagation()
                .startsWith(codeOf(LayoutWithinGroupResultDto.PROPAGATION_CONTAINER_NOT_A_NATIVE_GROUP))) {
            steps.add(0, containerNotNativeStep(arm));
        }
        if (entity.ancestorPropagation()
                .startsWith(codeOf(LayoutWithinGroupResultDto.PROPAGATION_STOPPED_AT_NON_NATIVE_ANCESTOR))) {
            steps.add(0, stoppedAtNonNativeStep(arm));
        }
        if (entity.ancestorPropagation()
                .startsWith(codeOf(LayoutWithinGroupResultDto.PROPAGATION_DEPTH_CAP_REACHED))) {
            steps.add(0, depthCapStep(arm));
        }
        return steps;
    }

    /** What a layout-within-group response carries when the write is waiting on a human. */
    private List<String> buildLayoutWithinGroupApprovalDisclosures(
            MutationResult<LayoutWithinGroupResultDto> result) {
        return layoutWithinGroupDisclosures(result.entity(), DispatchArm.AWAITING_APPROVAL);
    }

    private List<String> buildLayoutWithinGroupNextSteps(
            MutationResult<LayoutWithinGroupResultDto> result) {
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>(
                    layoutWithinGroupDisclosures(result.entity(), DispatchArm.QUEUED));
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        List<String> steps = new ArrayList<>(
                layoutWithinGroupDisclosures(result.entity(), DispatchArm.APPLIED));
        steps.addAll(List.of(
                "Use export-view to visually verify the group layout.",
                "Use assess-layout to evaluate overall layout quality.",
                "Use auto-route-connections if connections need orthogonal routing."));
        return steps;
    }

    /**
     * The leading code of a published reason, colon included — the half an agent triages on.
     *
     * <p>A reason with no colon would make {@code substring(0, -1 + 1)} the empty string, and every
     * {@code startsWith} test against it would then match every reason, firing a remedy on calls
     * that need none. The published format guarantees the colon, so this throws rather than
     * degrading into a silent match on all eight.</p>
     */
    private static String codeOf(String reason) {
        int colon = reason.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(
                    "a published propagation reason must be '<code>: <phrase>', was: " + reason);
        }
        return reason.substring(0, colon + 1);
    }

    // ---- auto-layout-and-route ----

    private McpServerFeatures.SyncToolSpecification buildAutoLayoutAndRouteSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to layout and route");

        Map<String, Object> modeProp = new LinkedHashMap<>();
        modeProp.put("type", "string");
        modeProp.put("enum", List.of("auto", "grouped"));
        modeProp.put("description",
                "Layout mode. 'auto' (default): ELK Layered algorithm — best for "
                + "flat views or when no structural intent is required. "
                + "'grouped': Orchestrated Branch 2 workflow (layout-within-group "
                + "→ arrange-groups → optimize-group-order → auto-route-connections) "
                + "— best for grouped views with structural intent (layered "
                + "architecture, producer-consumer flows, etc.). Grouped mode "
                + "produces obstacle-aware orthogonal routing between groups. "
                + "Requires the view to have containers with children — either native view groups "
                + "or ArchiMate Grouping elements at the top level; both qualify.");

        Map<String, Object> directionProp = new LinkedHashMap<>();
        directionProp.put("type", "string");
        directionProp.put("enum", List.of("DOWN", "RIGHT", "UP", "LEFT"));
        directionProp.put("description",
                "Layout direction. DOWN (default) places layers top-to-bottom, "
                + "RIGHT places left-to-right, etc.");

        Map<String, Object> spacingProp = new LinkedHashMap<>();
        spacingProp.put("type", "integer");
        spacingProp.put("description",
                "Spacing between elements in pixels. Default 50. "
                + "Larger values produce more spread-out layouts. "
                + "In grouped mode, controls both intra-group element spacing "
                + "and inter-group gap.");

        Map<String, Object> targetRatingProp = new LinkedHashMap<>();
        targetRatingProp.put("type", "string");
        targetRatingProp.put("enum", List.of("excellent", "good", "fair"));
        targetRatingProp.put("description",
                "Optional quality target. When specified, the tool runs an assess-and-adjust "
                + "loop (at most 5 attempts) that tunes whichever lever the worst-performing "
                + "metric calls for — spacing is only one of them. It stops as soon as ANY of its "
                + "exit conditions holds: the target rating is reached, every metric passes, the "
                + "worst metric is one no further iteration can move, successive attempts stop "
                + "moving that metric, or the attempt budget runs out. 'terminationReason' in the "
                + "response names the one that fired, so a low 'iterationsPerformed' is not by "
                + "itself evidence that the loop gave up early — it usually means the loop "
                + "measured that another attempt could not help. Returns the best result "
                + "achieved, with 'limitingFactor' and 'suggestedRemediation' when the target is "
                + "missed. The loop keeps the best attempt it PRODUCED, which may still be worse "
                + "than the view you gave it: compare 'ratingBefore' against 'achievedRating', and "
                + "read 'structuredWarnings' — a AUTO_LAYOUT_RATING_REGRESSED entry means the "
                + "result is worse than the input; once the run has been applied undo "
                + "restores it in one call, while a queued or awaiting-approval run has "
                + "nothing to undo yet and the entry names that arm's own remedy. "
                + "Eliminates the need for manual assess → adjust → re-layout loops. Works in both "
                + "auto and grouped modes. 'poor' and 'not-applicable' are not valid targets.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("mode", modeProp);
        properties.put("direction", directionProp);
        properties.put("spacing", spacingProp);
        Map<String, Object> layoutLabelPolicyProp = new LinkedHashMap<>();
        layoutLabelPolicyProp.put("type", "string");
        layoutLabelPolicyProp.put("enum", List.of("keep", "auto-hide-on-collision"));
        layoutLabelPolicyProp.put("description",
                "What to do with a connection label that has NO collision-free position. \"keep\" "
                + "(DEFAULT) never changes label visibility. \"auto-hide-on-collision\" hides exactly "
                + "the labels the router proved cannot be placed at any of the three label positions, "
                + "leaving every placeable label visible; each hide is listed individually in "
                + "hiddenLabels (connectionId + reason) and is reversible with "
                + "update-view-connection(showLabel: true). REQUIRES A ROUTING PASS: it is honoured in "
                + "mode=\"grouped\", and in flat mode when targetRating is set. Flat mode WITHOUT "
                + "targetRating takes ELK's own edge routes and runs no label optimizer, so the policy "
                + "is REJECTED there rather than silently ignored. A label you hid yourself is never "
                + "overridden and never counted as a policy hide.");
        layoutLabelPolicyProp.put("default", "keep");

        properties.put("targetRating", targetRatingProp);
        properties.put("labelPolicy", layoutLabelPolicyProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("auto-layout-and-route")
                .description("[Mutation] Compute element positions AND connection "
                        + "routes in a single operation. Two modes available: "
                        + "MODE 'auto' (default): ELK Layered algorithm — REPLACES "
                        + "ALL element positions. Best for flat views or when no "
                        + "specific structural intent is needed. Produces clean "
                        + "orthogonal paths with distributed port alignment. "
                        + "LIMITATION: ELK routes inter-group connections at group "
                        + "boundary level, not element level. "
                        + "MODE 'grouped': Orchestrated workflow for grouped views "
                        + "— runs layout-within-group + arrange-groups + "
                        + "optimize-group-order + auto-route-connections in a "
                        + "single atomic operation. Produces obstacle-aware "
                        + "orthogonal routing between groups. BEST CHOICE for "
                        + "views built from containers (layered architecture, "
                        + "producer-consumer, etc.). Requires the view to have at "
                        + "least one top-level container. TWO KINDS COUNT as a "
                        + "container here and both are laid out and arranged: a "
                        + "native view group created by add-group-to-view, and an "
                        + "ArchiMate Grouping element created by create-element and "
                        + "placed by add-to-view. A view built entirely from "
                        + "Grouping zones — the shape the technology and deployment "
                        + "guidance prescribes — is therefore a valid grouped-mode "
                        + "target. Call get-view-contents first: its topLevelGroups "
                        + "stat counts both kinds. That parity is about container "
                        + "KIND; on DEPTH this mode is narrower than arrange-groups. "
                        + "It arranges the view's OWN containers, so a view whose "
                        + "containers are all drawn inside a host is declined — with "
                        + "a message naming that shape rather than calling the view "
                        + "flat, and pointing at arrange-groups, which arranges such "
                        + "a container inside its host. "
                        + "Grouped mode lays out the WHOLE SUBTREE under each "
                        + "top-level group, not just its direct children: an "
                        + "ArchiMate element acting as a container, or a nested "
                        + "group, is sized to fit its own contents and descended "
                        + "into, down to a depth limit. The arrangement is derived "
                        + "per level from that level's child count, so a crowded "
                        + "container does not inherit its parent's shape. "
                        + "RESPONSE (grouped): 'elementsRepositioned' counts EVERY "
                        + "descendant moved, not only direct children; "
                        + "'nestedContainersFitted' lists each nested container "
                        + "that was re-fitted with the rectangle it landed at, so "
                        + "you do not have to re-read the view to learn where they "
                        + "went; 'depthCapHit' is true when nesting went deeper "
                        + "than the pass descends, meaning the deepest containers "
                        + "kept their existing layout. Both are omitted when "
                        + "nothing nested was touched. "
                        + "`resizedElements` names every LEAF the descent re-sized, "
                        + "with the rectangle it landed at — the pass decides a "
                        + "leaf's size itself rather than preserving the stored "
                        + "one, so an element you never named comes back a "
                        + "different width and you would otherwise plan your next "
                        + "call against a width this one already replaced. It is "
                        + "GROUPED MODE ONLY: flat mode never descends, so the list "
                        + "is always absent there. It is disjoint from "
                        + "'nestedContainersFitted' — a container the walk "
                        + "descended into is reported there and never here — and "
                        + "'elementsRepositioned' counts descendants MOVED, resized "
                        + "or not, so it cannot tell you this happened. It is an "
                        + "observation: an element re-written to the size it "
                        + "already had does not appear, and it is omitted entirely "
                        + "when empty. An element that only MOVED and kept its size "
                        + "is not in it: this list is about size alone. "
                        + "Grid cells inside a grouped layout are sized per column "
                        + "rather than all to the widest element, so a group of 4+ "
                        + "elements comes out narrower than a single-level "
                        + "layout-within-group grid would make it. "
                        + "IMPORTANT: Use auto-route-connections instead if you "
                        + "want to preserve existing element positions and only "
                        + "compute connection routes. "
                        + "For flat views, consider layout-flat-view first — it "
                        + "offers sortBy and categoryField for organized placement. "
                        + "Automatically switches to manual (bendpoint) connection "
                        + "router mode. Supports batch and approval modes. "
                        + "Use targetRating to automate quality iteration — an "
                        + "assess-and-adjust loop (at most 5 attempts) that stops "
                        + "when the target is reached, when every metric passes, "
                        + "when the limiting metric is one no further iteration "
                        + "can move, when successive attempts stop moving that "
                        + "metric, or when the attempt budget runs out. "
                        + "'terminationReason' names the condition that stopped "
                        + "it, and 'suggestedRemediation' names the "
                        + "remedy for the limiting metric — act on that rather "
                        + "than assuming more spacing is the answer. "
                        + "THIS TOOL CAN LEAVE A VIEW WORSE THAN IT FOUND IT and "
                        + "still applies the result: it keeps the best of the "
                        + "attempts it produced, which is not necessarily better "
                        + "than the view you handed it, and meeting a target is "
                        + "'at least as good as', so an already-excellent view "
                        + "can be downgraded to a 'good' target and reported as a "
                        + "success. The response says so rather than leaving you "
                        + "to find out: 'ratingBefore' is the rating the view had "
                        + "before the call, beside 'achievedRating' for after, and "
                        + "a AUTO_LAYOUT_RATING_REGRESSED entry in "
                        + "'structuredWarnings' fires whenever the applied result "
                        + "is worse — naming every metric that moved and by how "
                        + "much. Once the run has been applied the remedy is undo "
                        + "and one undo is enough — the whole run is committed as a "
                        + "single compound operation; a queued or awaiting-approval "
                        + "run has nothing to undo yet, and the warning names the "
                        + "remedy that arm does have. "
                        + "PRECONDITION CHECKLIST: fetch "
                        + "archimate://prompts/routing-preconditions-checklist "
                        + "before invoking this tool on any non-trivial view "
                        + "— the routing pipeline cannot recover from missing "
                        + "preconditions (hub sizing, inter-element spacing, "
                        + "inter-group spacing). "
                        + "See archimate-view-patterns resource for guidance on "
                        + "which mode to use."
                        + " Any archimate:// URI named here can also be read by calling get-guidance with that uri, for clients that do not expose MCP resources.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAutoLayoutAndRoute)
                .build();
    }

    McpSchema.CallToolResult handleAutoLayoutAndRoute(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling auto-layout-and-route request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String mode = HandlerUtils.optionalStringParam(args, "mode");
            String direction = HandlerUtils.optionalStringParam(args, "direction");
            Integer spacingParam = HandlerUtils.optionalIntegerParam(args, "spacing");
            int spacing = spacingParam != null ? spacingParam : 50;

            // Validate mode parameter
            if (mode != null && !"auto".equals(mode) && !"grouped".equals(mode)) {
                throw new ModelAccessException(
                        "Invalid mode: '" + mode + "'",
                        ErrorCode.INVALID_PARAMETER,
                        "mode must be one of: auto, grouped.",
                        "Use mode: \"grouped\" for views with groups, or omit for ELK layout.",
                        null);
            }

            // Optional targetRating for quality iteration
            String targetRating = HandlerUtils.optionalStringParam(args, "targetRating");
            if (targetRating != null
                    && !"excellent".equals(targetRating)
                    && !"good".equals(targetRating)
                    && !"fair".equals(targetRating)) {
                throw new ModelAccessException(
                        "Invalid targetRating: '" + targetRating + "'",
                        ErrorCode.INVALID_PARAMETER,
                        "targetRating must be one of: excellent, good, fair. "
                        + "'poor' and 'not-applicable' are not valid targets.",
                        "Use targetRating: \"good\" for typical quality iteration.",
                        null);
            }

            // Optional labelPolicy — omitted means "keep", so label visibility is untouched.
            String labelPolicy = HandlerUtils.optionalStringParam(args, "labelPolicy");

            MutationResult<AutoLayoutAndRouteResultDto> result =
                    accessor.autoLayoutAndRoute(sessionId, viewId, mode, direction,
                            spacing, targetRating, labelPolicy);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAutoLayoutAndRouteNextSteps(result),
                    buildAutoLayoutAndRouteApprovalDisclosures(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling auto-layout-and-route", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * What an auto-layout-and-route response carries when the write is waiting on a human.
     *
     * <p>Only the regression is state-dependent. The rest of this tool's guidance is advisory
     * ("run assess-layout to verify") and is correctly absent from an arm where nothing has been
     * written for the caller to verify.</p>
     */
    private List<String> buildAutoLayoutAndRouteApprovalDisclosures(
            MutationResult<AutoLayoutAndRouteResultDto> result) {
        if (!hasRatingRegressedWarning(result.entity())) {
            return List.of();
        }
        return List.of(AUTO_LAYOUT_RATING_REGRESSED_PROPOSED_STEP);
    }

    private List<String> buildAutoLayoutAndRouteNextSteps(
            MutationResult<AutoLayoutAndRouteResultDto> result) {
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>();
            // A batched call still ran the whole loop and still measured the regression before
            // anything was queued, so the disclosure is owed here exactly as on the immediate path.
            if (hasRatingRegressedWarning(result.entity())) {
                batchSteps.add(AUTO_LAYOUT_RATING_REGRESSED_QUEUED_STEP);
            }
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        List<String> steps = new ArrayList<>();
        AutoLayoutAndRouteResultDto dto = result.entity();
        boolean isGroupedMode = dto != null && "grouped".equals(dto.mode());

        // Emitted before any target-rating guidance and independently of it: a run can meet its
        // target and still have made the view worse, and that case never reaches the target-miss
        // builder below.
        if (hasRatingRegressedWarning(dto)) {
            steps.add(AUTO_LAYOUT_RATING_REGRESSED_STEP);
        }

        if (dto != null && dto.routerTypeSwitched()) {
            steps.add("View router type switched to manual (bendpoint mode) "
                    + "so that computed paths are rendered correctly.");
        }
        // When targetRating was used, quality assessment already done
        if (dto != null && dto.targetRating() != null) {
            if (dto.achievedRating() != null
                    && !dto.achievedRating().equals(dto.targetRating())
                    && dto.assessmentSummary() != null
                    && !targetMet(dto.achievedRating(), dto.targetRating())) {
                steps.addAll(buildTargetMissSteps(dto, hasRatingRegressedWarning(dto)));
            }
        } else {
            steps.add("Use assess-layout to evaluate overall layout quality.");
        }
        steps.add("Use export-view to visually verify the layout and routing.");
        if (isGroupedMode) {
            steps.add("Use auto-route-connections to re-route specific "
                    + "connections if needed.");
        } else {
            steps.add("Use auto-route-connections to re-route specific connections "
                    + "without changing element positions.");
            steps.add("Use update-view-object to fine-tune individual element "
                    + "positions after ELK layout.");
        }
        return steps;
    }

    /**
     * Builds the guidance for a quality target the loop did not reach.
     *
     * <p>Two things are deliberate here. First, the step says <em>why the loop stopped</em> in
     * prose, because the reason token alone is not something a caller can act on without a lookup
     * table. Second, the remedy is the one the response already carries in
     * {@code suggestedRemediation} — computed per limiting factor — rather than a hand-rolled
     * sentence beside it. Two sources of remediation advice can disagree; one cannot.</p>
     *
     * <p>The old unconditional "increase spacing manually" is now offered only when there is no
     * per-factor remedy <em>and</em> the loop's own dispatch table would itself have answered this
     * factor with more spacing. Telling a caller to raise spacing against a factor the loop already
     * measured as spacing-insensitive sends it at the one lever that has been disproved.</p>
     */
    private static List<String> buildTargetMissSteps(
            AutoLayoutAndRouteResultDto dto, boolean regressed) {
        List<String> steps = new ArrayList<>();
        String miss = "Target rating '" + dto.targetRating()
                + "' not achieved — achieved '" + dto.achievedRating()
                + "' after " + dto.iterationsPerformed() + " iteration(s).";
        String why = QualityTargetTermination.describe(dto.terminationReason(), regressed);
        steps.add(why != null ? miss + " " + why : miss);

        if (dto.suggestedRemediation() != null) {
            steps.add(dto.suggestedRemediation());
        } else if (QualityTargetTermination.spacingCanHelp(dto.mode(), dto.limitingFactor())) {
            steps.add("Increase the spacing parameter and re-run.");
        } else {
            steps.add("Use assess-layout to identify which metric is limiting this view.");
        }
        return steps;
    }

    /**
     * Returns true if achieved rating meets or exceeds target.
     * Rating order: excellent(4) > good(3) > fair(2) > poor(1) > not-applicable(0).
     */
    private static boolean targetMet(String achieved, String target) {
        return ratingOrdinal(achieved) >= ratingOrdinal(target);
    }

    private static int ratingOrdinal(String rating) {
        return switch (rating) {
            case "excellent" -> 4;
            case "good" -> 3;
            case "fair" -> 2;
            case "poor" -> 1;
            default -> 0;
        };
    }

    // ---- arrange-groups ----

    private McpServerFeatures.SyncToolSpecification buildArrangeGroupsSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view containing the groups to arrange");

        Map<String, Object> arrangementProp = new LinkedHashMap<>();
        arrangementProp.put("type", "string");
        arrangementProp.put("enum", List.of("grid", "row", "column", "topology"));
        arrangementProp.put("description",
                "Layout pattern: 'grid' (rows × columns), 'row' (single horizontal row), "
                + "'column' (single vertical column), 'topology' (analyzes inter-group connection "
                + "density and orders groups to minimize long-range crossings — best for views with "
                + "connections between groups, defaults to vertical/column layout; "
                + "use 'direction' param to switch to horizontal/row layout)");

        Map<String, Object> columnsProp = new LinkedHashMap<>();
        columnsProp.put("type", "integer");
        columnsProp.put("description",
                "Number of columns for grid arrangement. Auto-detected if not specified. "
                + "Ignored for row/column arrangements.");

        Map<String, Object> spacingProp = new LinkedHashMap<>();
        spacingProp.put("type", "integer");
        spacingProp.put("description",
                "Gap in pixels between groups (static default: 40). Groups are larger than "
                + "elements, so 40px is recommended minimum. "
                + "When `spacing` is OMITTED (parameter not provided) AND the view has "
                + "inter-group connections, the tool derives a heuristic-driven default "
                + "from the view's connection count instead of using 40. "
                + "Heuristic targets per connection count (connected views): ≤15 → 80 px; "
                + "16-30 → 100 px; >30 → 120 px "
                + "(`archimate://reference/archimate-view-patterns` Pre-Layout Planning §2). "
                + "Pass an explicit `spacing` value (including 0 or 40) to suppress "
                + "default-resolution. "
                + "The response DTO's `defaultResolutionReason` field reports whether "
                + "default-resolution fired and which heuristic tier produced the value. "
                + "(Applies to direct `arrange-groups` invocations only — internal compound "
                + "flows that use the static 40 default are unaffected.)");

        Map<String, Object> directionProp = new LinkedHashMap<>();
        directionProp.put("type", "string");
        directionProp.put("enum", List.of("vertical", "horizontal"));
        directionProp.put("description",
                "Direction for topology arrangement: 'vertical' (top-to-bottom, default) or "
                + "'horizontal' (left-to-right). Use horizontal for producer→middleware→consumer "
                + "flow patterns. Only applies to topology arrangement without columns; "
                + "ignored for row/column/grid arrangements.");

        Map<String, Object> groupIdsProp = new LinkedHashMap<>();
        groupIdsProp.put("type", "array");
        Map<String, Object> groupIdItems = new LinkedHashMap<>();
        groupIdItems.put("type", "string");
        groupIdsProp.put("items", groupIdItems);
        groupIdsProp.put("description",
                "Optional list of container view-object IDs to arrange. Any DIRECT child of this "
                + "view that CAN hold children is accepted: a native group, an ArchiMate Grouping "
                + "element, and also a plain ArchiMate element acting as a container — a Node "
                + "typing a cloud region, for example — which is NOT arranged by default. Naming "
                + "such a container here is the way to have it positioned, and is exactly what the "
                + "skippedContainers entries in the response are for. The test is the object's "
                + "capacity to hold children, not whether it currently holds any, so a childless "
                + "top-level element is accepted too and is positioned like an empty group. "
                + "A container drawn inside a HOST is accepted too, and is arranged in that host's "
                + "own coordinate space rather than on the canvas; naming one restricts the "
                + "arrangement inside that host to the containers you named. A container that is a "
                + "member of another arrangement target is still refused: it moves with the target "
                + "holding it, and positioning it separately would fight that arrangement. "
                + "A note, an image and a view reference cannot hold children and are refused; "
                + "place those with apply-positions or update-view-object. So is an id naming an "
                + "object on another view, a connection, this view itself, or a model concept "
                + "rather than the view object drawn from it — each refusal says which and names "
                + "its own remedy. "
                + "IMPORTANT: groupIds REPLACES the default set rather than adding to it, so name "
                + "every container you want arranged, not only the extra one. "
                + "If omitted, the view's default top-level containers are arranged and nothing "
                + "else. Non-listed containers remain in their current positions.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("arrangement", arrangementProp);
        properties.put("columns", columnsProp);
        properties.put("spacing", spacingProp);
        properties.put("direction", directionProp);
        properties.put("groupIds", groupIdsProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId", "arrangement"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("arrange-groups")
                .description("[Mutation] Positions top-level containers and qualifying standalone elements relative to each other "
                        + "in a grid, row, or column layout. "
                        + "Use AFTER creating and populating containers with elements, BEFORE routing connections.\n\n"
                        + "**Two kinds of container are arranged, and both count as a \"group\" here:**\n"
                        + "- a native view group created by add-group-to-view — a diagram-only box with no model semantics\n"
                        + "- an ArchiMate `Grouping` element created by create-element and placed by add-to-view — "
                        + "a real concept that participates in relationships and is queryable in the model\n\n"
                        + "Prefer the `Grouping` element when you want to ask questions later "
                        + "(\"what runs in this VPC?\"); prefer a native group for a purely visual box. "
                        + "A container nested inside another CONTAINER OF THE SAME KIND is a member of it, "
                        + "not a target, and moves with it. But a container nested inside a HOST — a Node typing "
                        + "a cloud region, say — is nobody's member: nothing above it is a zone, so it IS "
                        + "arranged. It cannot join the canvas layout, because a nested object's x/y are stored "
                        + "relative to its parent rather than to the canvas, so it is arranged INSIDE its host, "
                        + "in that host's own coordinate space, and reported in `nestedContainersArranged` with "
                        + "the `hostViewObjectId` its coordinates are measured from. A host too small to hold the "
                        + "arrangement is declined whole rather than half-filled — this tool never grows an "
                        + "ArchiMate element to make room.\n\n"
                        + "**A third kind of container is NOT arranged BY DEFAULT, and this is deliberate:** a plain "
                        + "ArchiMate element that happens to hold children — a `Node` typing a cloud region or "
                        + "account, for example. Such an element is a host, not a zone: on a call that does not name "
                        + "it, it is left exactly where it is and is not counted in groupsPositioned. That is easy to "
                        + "miss on a large view, so the response carries `skippedContainers` naming every direct "
                        + "child of the view that holds children and was not positioned.\n\n"
                        + "**Name such a container in `groupIds` and it IS arranged**, whatever its element type — "
                        + "positioned alongside the zones, counted in groupsPositioned, returned in "
                        + "positionedContainers, and absent from skippedContainers. This is a per-call opt-in, not a "
                        + "change of default: you can see the canvas and this tool cannot, so a `skippedContainers` "
                        + "entry you disagree with is answered by passing its `viewObjectId` straight back in "
                        + "`groupIds`. Omit `groupIds` and every view is arranged exactly as before.\n\n"
                        + "**The response accounts for every direct child of the view, so you never have to count "
                        + "them yourself.** `topLevelObjects` is the total this call measured against, and the four "
                        + "buckets below always sum to it exactly:\n"
                        + "```\n"
                        + "groupsPositioned + standaloneElementsPlaced + skippedContainers + unhandled"
                        + " = topLevelObjects\n"
                        + "```\n"
                        + "`nestedContainersArranged` is deliberately OUTSIDE that identity and counted by none "
                        + "of the four. Those containers are not direct children of the view, so they are not in "
                        + "`topLevelObjects` either; counting them in `groupsPositioned` would make the equality "
                        + "above arithmetically false on exactly the views the field exists for. A host whose "
                        + "zones were arranged still appears in `skippedContainers` — the host itself was not "
                        + "moved — and its `reason` says the containers inside it were.\n"
                        + "`unhandled` names every direct child none of the other three claimed — a loose element, "
                        + "a note, an image, a view reference — each with `viewObjectId`, `name`, `elementType` and a "
                        + "`reason` that leads with a stable code you can filter on without reading the prose:\n"
                        + "- `not-requested` — a container you excluded yourself by passing groupIds; name it there "
                        + "to have it arranged\n"
                        + "- `not-an-archimate-element` — a note, image or view reference; never arranged by any "
                        + "layout tool\n"
                        + "- `lane-not-run` — a Node/Device/Path/CommunicationNetwork on a call that was not "
                        + "`arrangement: \"topology\"` resolving to a row or column\n"
                        + "- `no-inter-container-gap` — the lane ran, but this call arranged fewer than two "
                        + "containers ON THE CANVAS, so there is no gap between containers to place anything "
                        + "in; no connection you add would change that. Reads zero on a view whose containers "
                        + "are all drawn inside a host, since those are arranged in the host's own space and "
                        + "leave the canvas empty. Decided before the connection count for exactly that "
                        + "reason\n"
                        + "- `insufficient-connections` — the lane ran, and this element reaches fewer than 2 of "
                        + "the arranged containers. **A connection reaches a container both when it terminates "
                        + "on an element INSIDE it and when it terminates on the container's own box** — the "
                        + "node-as-container shape `archimate://recipes/technology-deployment` prescribes is the "
                        + "second kind. Two connections to the same container count once\n"
                        + "- `type-not-lane-eligible` — the standalone lane never places this element's type\n\n"
                        + "Anything in `skippedContainers` or `unhandled` is still sitting wherever it was — the "
                        + "OBJECT itself, that is. A host whose nested containers this call arranged is still listed "
                        + "here because the host did not move, and its `reason` says the containers inside it did; "
                        + "read the entry as \"this box is where you left it\", not as \"nothing under it changed\". "
                        + "On a freshly built view an unmoved object usually means stacked at the origin under "
                        + "something else. If it "
                        + "matters to your layout: a container (anything in `skippedContainers`, or an `unhandled` "
                        + "entry reading `not-requested`) is arranged by naming its `viewObjectId` in `groupIds` on "
                        + "the next call; anything else — a note, an image, a view reference, a childless element — "
                        + "holds no children and must be placed with apply-positions or update-view-object. Both lists "
                        + "and the total are computed from the view as read, so they are populated and correct on a "
                        + "queued or proposed call too, where the effective geometry cannot be.\n\n"
                        + "**Recommended workflow for grouped views:**\n"
                        + "1. Create containers and add elements to them\n"
                        + "2. Use layout-within-group for each group's internal layout\n"
                        + "3. Use arrange-groups to position groups relative to each other\n"
                        + "4. Use auto-route-connections to route inter-group connections\n\n"
                        + "**When NOT to use:**\n"
                        + "- For positioning elements inside groups → use layout-within-group\n"
                        + "- For full automatic layout of flat (non-grouped) views → use auto-layout-and-route\n"
                        + "- For one-step grouped layout without fine-grained control → use auto-layout-and-route (ELK handles groups natively)\n\n"
                        + "Repositions groups (preserves each group's current width and height) and, "
                        + "for `arrangement: \"topology\"` with a 1D layout (row or column — NOT a "
                        + "topology+columns grid), also repositions qualifying standalone "
                        + "top-level elements: a `Node`, `Device`, `Path`, or `CommunicationNetwork` "
                        + "that reaches ≥ 2 of the arranged target groups is auto-placed "
                        + "in a reserved inter-group lane between its connected groups (centred "
                        + "vertically + horizontally). **A connection reaches a group whether it terminates "
                        + "on an element INSIDE the group or on the group's own container box**, and two "
                        + "connections to the same group count once. `auto-layout-and-route` with "
                        + "`mode: \"grouped\"` decides that same question with the same predicate, but it "
                        + "takes no `groupIds` and so arranges only the DEFAULT containers: a candidate "
                        + "that reaches its second container only through a host you named in `groupIds` "
                        + "is placed here and NOT there. It is narrower on DEPTH too — it positions the "
                        + "view's own containers only, where this tool also arranges a container drawn "
                        + "inside a host. That tool also carries no `unhandled` bucket in "
                        + "which to report what it did or did not place. "
                        + "This matches the recipe topology promise (`archimate://recipes/application-integration` "
                        + "hub-and-spoke + `archimate://recipes/technology-deployment` zones-with-Path). "
                        + "If no qualifier exists, output is unchanged from direct row/column/grid behaviour. "
                        + "The qualifier predicate is automatic — there is no opt-in parameter. The "
                        + "`groupIds` parameter still constrains the arranged set; qualifier qualification "
                        + "is computed against the constrained set, and a container you named there is "
                        + "arranged rather than lane-placed, so it never counts in "
                        + "standaloneElementsPlaced.\n\n"
                        + "**Related:**\n"
                        + "- `apply-group-spacing-recommendations` is the explicit-opt-in convenience-tool "
                        + "surface for the same heuristic; useful when you want a `dryRun` preview, "
                        + "post-routing application, or the full before/after metrics envelope.\n"
                        + "- `adjust-view-spacing` is for inflating spacing on an EXISTING layout without "
                        + "re-positioning groups."
                        + " Any archimate:// URI named here can also be read by calling get-guidance with that uri, for clients that do not expose MCP resources.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleArrangeGroups)
                .build();
    }

    McpSchema.CallToolResult handleArrangeGroups(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling arrange-groups request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String arrangement = HandlerUtils.requireStringParam(args, "arrangement");
            Integer columns = HandlerUtils.optionalIntegerParam(args, "columns");
            Integer spacing = HandlerUtils.optionalIntegerParam(args, "spacing");
            String direction = HandlerUtils.optionalStringParam(args, "direction");

            // Parse optional groupIds array
            List<String> groupIds = null;
            Object groupIdsObj = args.get("groupIds");
            if (groupIdsObj instanceof List<?> rawList && !rawList.isEmpty()) {
                groupIds = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof String s) {
                        groupIds.add(s);
                    } else {
                        groupIds.add(String.valueOf(item));
                    }
                }
            }

            MutationResult<ArrangeGroupsResultDto> result =
                    accessor.arrangeGroups(sessionId, viewId, arrangement,
                            columns, spacing, groupIds, direction);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildArrangeGroupsNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling arrange-groups", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildArrangeGroupsNextSteps(
            MutationResult<ArrangeGroupsResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Use layout-within-group for each group to arrange its internal elements.",
                "Use auto-route-connections to route connections between groups.",
                "Use export-view to visually verify the group arrangement.",
                "Use assess-layout to evaluate overall layout quality.");
    }

    // ---- optimize-group-order ----

    private McpServerFeatures.SyncToolSpecification buildOptimizeGroupOrderSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to optimize");

        Map<String, Object> arrangementProp = new LinkedHashMap<>();
        arrangementProp.put("type", "string");
        arrangementProp.put("description",
                "Optional. When provided, used as default arrangement for all groups. "
                + "When omitted, each group's arrangement is auto-detected from current "
                + "child positions (preserving per-group layout choices from layout-within-group). "
                + "Values: 'row' (horizontal), 'column' (vertical), or 'grid'.");

        Map<String, Object> spacingProp = new LinkedHashMap<>();
        spacingProp.put("type", "integer");
        spacingProp.put("description",
                "Space between elements in pixels (default: 40)");

        Map<String, Object> paddingProp = new LinkedHashMap<>();
        paddingProp.put("type", "integer");
        paddingProp.put("description",
                "Space from group edges in pixels (default: 10)");

        Map<String, Object> elementWidthProp = new LinkedHashMap<>();
        elementWidthProp.put("type", "integer");
        elementWidthProp.put("description",
                "Resize all children to this width. Omit to preserve existing sizes.");

        Map<String, Object> elementHeightProp = new LinkedHashMap<>();
        elementHeightProp.put("type", "integer");
        elementHeightProp.put("description",
                "Resize all children to this height. Omit to preserve existing sizes.");

        Map<String, Object> autoWidthProp = new LinkedHashMap<>();
        autoWidthProp.put("type", "boolean");
        autoWidthProp.put("description",
                "Compute each element's width from its label text (default: false). "
                + "Ignored when elementWidth is set.");

        Map<String, Object> columnsProp = new LinkedHashMap<>();
        columnsProp.put("type", "integer");
        columnsProp.put("description",
                "Number of columns for grid arrangement (default: auto-detected). "
                + "Only used with arrangement: 'grid'.");

        Map<String, Object> groupArrangementsProp = new LinkedHashMap<>();
        groupArrangementsProp.put("type", "object");
        groupArrangementsProp.put("description",
                "Optional per-group arrangement overrides. Keys are group view object IDs, "
                + "values are 'row', 'column', or 'grid'. Overrides auto-detection for "
                + "specified groups.");
        Map<String, Object> gaPropValues = new LinkedHashMap<>();
        gaPropValues.put("type", "string");
        gaPropValues.put("enum", List.of("row", "column", "grid"));
        groupArrangementsProp.put("additionalProperties", gaPropValues);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("arrangement", arrangementProp);
        properties.put("spacing", spacingProp);
        properties.put("padding", paddingProp);
        properties.put("elementWidth", elementWidthProp);
        properties.put("elementHeight", elementHeightProp);
        properties.put("autoWidth", autoWidthProp);
        properties.put("columns", columnsProp);
        properties.put("groupArrangements", groupArrangementsProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties,
                List.of("viewId"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("optimize-group-order")
                .description("[Mutation] Reorder elements within groups to minimize "
                        + "inter-group edge crossings using barycentric heuristic. "
                        + "Best used EARLY in the workflow (before initial routing) — "
                        + "reordering elements after routing invalidates existing routes. "
                        + "After optimization, MUST re-run layout-within-group with adequate "
                        + "spacing before auto-route-connections. "
                        + "Works on ALL top-level containers in the view simultaneously — native "
                        + "view groups and ArchiMate Grouping elements alike. That parity is about "
                        + "container KIND. On DEPTH this tool is narrower than arrange-groups: it "
                        + "reorders the view's own containers only. A container drawn inside a host — a "
                        + "Node typing a cloud region, say — is neither counted into the set this tool "
                        + "reorders nor reordered itself; arrange-groups does position such a container, "
                        + "inside its host. A view holding only those is refused with a message naming "
                        + "how many containers it holds, rather than one calling it groupless. "
                        + "AUTO-DETECTION: When arrangement is omitted, each group's "
                        + "arrangement is auto-detected from current child positions — "
                        + "preserving per-group layout choices from layout-within-group "
                        + "(e.g., column for one group, grid for another). Use "
                        + "groupArrangements for explicit per-group overrides. "
                        + "Response includes arrangementUsed and arrangementSource per group. "
                        + "Deterministic — same input always produces same output. "
                        + "Reports before/after crossing counts. NOTE: Crossing counts "
                        + "are topological estimates based on center-to-center straight "
                        + "lines between connected elements — they do NOT reflect actual "
                        + "routed connection paths. Actual routed crossings (reported by "
                        + "assess-layout) may be higher because orthogonal routing, "
                        + "bendpoints, and obstacle avoidance create additional crossings "
                        + "not predicted by the straight-line heuristic. Use assess-layout "
                        + "as the authoritative crossing count after routing. "
                        + "Does NOT move elements "
                        + "between groups — only reorders within each group. Groups are "
                        + "auto-resized after reordering. IMPORTANT: Reordering may change "
                        + "group sizes — always follow with arrange-groups to prevent "
                        + "group-on-group overlaps. "
                        + "REORDERING ALSO RE-SIZES CHILDREN, and `resizedElements` names every "
                        + "one whose SIZE this call changed, with the rectangle it landed at, so "
                        + "you do not plan your next call against a width this one already "
                        + "replaced. Three mechanisms populate it: a grid arrangement gives every "
                        + "cell the width of the widest element in that group, autoWidth derives a "
                        + "width from the label, and an explicit elementWidth or elementHeight "
                        + "imposes one. The third is reported like the other two even though you "
                        + "asked for it — what is reported is what the model ENDED UP holding, and "
                        + "a size you requested that did not land is exactly what you need told. "
                        + "`elementsReordered` counts only the children whose POSITION IN THE "
                        + "ORDER changed; a child that kept its index is re-placed and re-sized "
                        + "like every other child of that group and is not in that count, so the "
                        + "count can never tell you any of this happened. It is an "
                        + "observation — a child re-written to the size it already had does not "
                        + "appear, including when you requested that size — and it is omitted "
                        + "entirely when empty. A child that only MOVED and kept its size is not "
                        + "in it: this list is about size alone. It names CHILDREN; the group's "
                        + "own re-fit is not in it. "
                        + "Typical workflow: add elements → "
                        + "layout-within-group → optimize-group-order → arrange-groups → "
                        + "auto-route-connections → assess-layout → adjust-view-spacing "
                        + "(if spacing too tight). Related: layout-within-group "
                        + "(initial arrangement), arrange-groups (fix group positions after "
                        + "reorder), auto-route-connections (route after optimization), "
                        + "assess-layout (evaluate result), undo (roll back if unsatisfied).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleOptimizeGroupOrder)
                .build();
    }

    McpSchema.CallToolResult handleOptimizeGroupOrder(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling optimize-group-order request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String arrangement = HandlerUtils.optionalStringParam(args, "arrangement");

            // Optional parameters
            Integer spacing = HandlerUtils.optionalIntegerParam(args, "spacing");
            Integer padding = HandlerUtils.optionalIntegerParam(args, "padding");
            Integer elementWidth = HandlerUtils.optionalIntegerParam(args, "elementWidth");
            Integer elementHeight = HandlerUtils.optionalIntegerParam(args, "elementHeight");
            boolean autoWidth = HandlerUtils.optionalBooleanParam(args, "autoWidth", false);
            Integer columns = HandlerUtils.optionalIntegerParam(args, "columns");

            // Extract groupArrangements map (optional)
            Map<String, String> groupArrangements = null;
            Object gaObj = (args != null) ? args.get("groupArrangements") : null;
            if (gaObj instanceof Map<?, ?> gaMap) {
                groupArrangements = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : gaMap.entrySet()) {
                    if (entry.getKey() instanceof String key
                            && entry.getValue() instanceof String value) {
                        groupArrangements.put(key, value);
                    }
                }
                if (groupArrangements.isEmpty()) {
                    groupArrangements = null;
                }
            }

            MutationResult<OptimizeGroupOrderResultDto> result =
                    accessor.optimizeGroupOrder(sessionId, viewId, arrangement,
                            spacing, padding, elementWidth, elementHeight,
                            autoWidth, columns, groupArrangements);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildOptimizeGroupOrderNextSteps(result),
                    buildOptimizeGroupOrderApprovalDisclosures(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling optimize-group-order", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * The crossing comparison, which is available on every arm because nothing was ever written.
     *
     * <p>Both counts come from a pure in-memory minimizer that operates on coordinate arrays and
     * identifiers and imports no model types at all. The "after" figure is counted over
     * freshly-allocated records describing the proposed ordering, not over anything in the view —
     * nothing is dispatched, executed or undone on this path before the dispatch decision is
     * taken. So a queued caller is owed the number: it is a simulation, and it was a simulation on
     * the applied arm too.</p>
     *
     * <p>The approval wording adds what the queued one does not need: a stored proposal re-prepares
     * on approval, so the simulation is re-run against whatever the view then holds.</p>
     */
    private static String crossingReductionStep(
            OptimizeGroupOrderResultDto dto, DispatchArm arm) {
        String figures = dto.crossingsBefore() + " to " + dto.crossingsAfter()
                + " (" + dto.reductionPercent() + "% reduction)";
        return switch (arm) {
            case APPLIED -> "Crossings reduced from " + figures + ".";
            case QUEUED -> "Crossings will fall from " + figures + " when this batch commits. The "
                    + "count is a simulation over the proposed ordering, not a reading of the view.";
            case AWAITING_APPROVAL -> "Crossings will fall from " + figures + " if this change is "
                    + "approved. The count is a simulation over the proposed ordering, and it is "
                    + "re-run against the view as it then stands.";
        };
    }

    private static String reorderedNotesStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "Notes inside groups may need manual repositioning after element "
                    + "reordering.";
            case QUEUED -> "Notes inside groups may need manual repositioning once this batch "
                    + "commits and the elements are reordered.";
            case AWAITING_APPROVAL -> "Notes inside groups may need manual repositioning once this "
                    + "change is approved and the elements are reordered.";
        };
    }

    /**
     * The two conditional steps a reorder carries, phrased for the arm.
     *
     * <p>The third conditional step this tool emits — that the order was already optimal — is
     * deliberately absent here and exists only on the applied arm. It is selected by
     * {@code groupsOptimized() == 0}, and the accessor builds one update command per reordered
     * group, so a zero means an empty command list and an empty command list returns above both
     * the approval gate and the queue. The branch is therefore unreachable on either deferred arm,
     * and a sentence written for a state a caller cannot be in is worse than no sentence: it reads
     * as a description of something that happened.</p>
     */
    private List<String> optimizeGroupOrderDisclosures(
            OptimizeGroupOrderResultDto dto, DispatchArm arm) {
        List<String> steps = new ArrayList<>();
        if (dto == null) {
            return steps;
        }
        if (dto.crossingsBefore() > 0 && dto.crossingsAfter() < dto.crossingsBefore()) {
            steps.add(crossingReductionStep(dto, arm));
        }
        if (dto.groupsOptimized() > 0) {
            steps.add(reorderedNotesStep(arm));
        }
        return steps;
    }

    /** What an optimize-group-order response carries when the reorder is waiting on a human. */
    private List<String> buildOptimizeGroupOrderApprovalDisclosures(
            MutationResult<OptimizeGroupOrderResultDto> result) {
        return optimizeGroupOrderDisclosures(result.entity(), DispatchArm.AWAITING_APPROVAL);
    }

    private List<String> buildOptimizeGroupOrderNextSteps(
            MutationResult<OptimizeGroupOrderResultDto> result) {
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>(
                    optimizeGroupOrderDisclosures(result.entity(), DispatchArm.QUEUED));
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        OptimizeGroupOrderResultDto dto = result.entity();
        List<String> steps = new ArrayList<>();
        if (dto != null && dto.crossingsBefore() > 0 && dto.crossingsAfter() < dto.crossingsBefore()) {
            steps.add(crossingReductionStep(dto, DispatchArm.APPLIED));
        } else if (dto != null && dto.groupsOptimized() == 0) {
            steps.add("Element order is already optimal — no reordering was needed.");
        }
        steps.add("Use auto-route-connections to compute orthogonal paths for inter-group connections.");
        steps.add("Use assess-layout to evaluate final crossing count and layout quality.");
        if (dto != null && dto.groupsOptimized() > 0) {
            steps.add(reorderedNotesStep(DispatchArm.APPLIED));
        }
        steps.add("Use export-view to visually verify the optimized layout.");
        return steps;
    }

    // ---- Styling helper methods ----

    /**
     * Adds styling property definitions (fillColor, lineColor, fontColor, opacity, lineWidth,
     * figureType, textAlignment, verticalTextAlignment) to a tool spec properties map.
     * Used by add-to-view, add-group-to-view, add-note-to-view, and update-view-object.
     *
     * <p>{@code figureType} is supported on objects that have alternate figures (native
     * groups via {@code IBorderType.setBorderType()}, ArchiMate elements via
     * {@code IDiagramModelArchimateObject.setType()}); silently ignored on notes which
     * use {@code IBorderType} with different semantics (dogear/rectangle/none — out of
     * scope for this story).</p>
     */
    private void addStylingProperties(Map<String, Object> properties) {
        Map<String, Object> fillColorProp = new LinkedHashMap<>();
        fillColorProp.put("type", "string");
        fillColorProp.put("description",
                "Fill/background colour in #RRGGBB hex format (e.g. '#FF0000' for red). "
                + "Empty string clears to default. Omit to leave unchanged.");

        Map<String, Object> lineColorProp = new LinkedHashMap<>();
        lineColorProp.put("type", "string");
        lineColorProp.put("description",
                "Line/border colour in #RRGGBB hex format. "
                + "Empty string clears to default. Omit to leave unchanged.");

        Map<String, Object> fontColorProp = new LinkedHashMap<>();
        fontColorProp.put("type", "string");
        fontColorProp.put("description",
                "Font/text colour in #RRGGBB hex format. "
                + "Empty string clears to default. Omit to leave unchanged.");

        Map<String, Object> opacityProp = new LinkedHashMap<>();
        opacityProp.put("type", "integer");
        opacityProp.put("description",
                "Opacity from 0 (fully transparent) to 255 (fully opaque). "
                + "Default is 255. Omit to leave unchanged.");

        Map<String, Object> lineWidthProp = new LinkedHashMap<>();
        lineWidthProp.put("type", "integer");
        lineWidthProp.put("description",
                "Line width from 1 to 3. Default is 1. Omit to leave unchanged.");

        Map<String, Object> figureTypeProp = new LinkedHashMap<>();
        figureTypeProp.put("type", "string");
        figureTypeProp.put("enum", java.util.List.of("rectangular", "tabbed"));
        figureTypeProp.put("description",
                "Figure type. Values: 'rectangular' (flat) or 'tabbed' (folder-tab — Archi default). "
                + "Applies ONLY to native groups (add-group-to-view) and the ArchiMate Grouping "
                + "element (add-to-view with type='Grouping') — these are the only targets where the "
                + "'tabbed/rectangular' vocabulary is meaningful. Other ArchiMate elements (Actor, "
                + "Component, Node, etc.) also have alternate figures via setType, but their "
                + "alternates are element-specific (stick-vs-box, 3D-vs-flat, etc.) and not exposed "
                + "through this surface; figureType is silently ignored on those targets. Notes "
                + "use a separate border-type semantics (dogear/rectangle/none) — also out of scope. "
                + "Omit to leave the per-type default unchanged. Example: 'rectangular' to flatten "
                + "a Group's folder-tab figure.");

        Map<String, Object> textAlignmentProp = new LinkedHashMap<>();
        textAlignmentProp.put("type", "string");
        textAlignmentProp.put("enum", java.util.List.of("left", "centre", "center", "right"));
        textAlignmentProp.put("description",
                "Horizontal text alignment for the element/group/note label. Values: 'left', "
                + "'centre' (UK) / 'center' (US — accepted as a synonym), or 'right'. Applies to "
                + "all view objects (groups, ArchiMate elements, notes — every IDiagramModelObject "
                + "implements ITextAlignment). Omit to accept the type's default: this server "
                + "writes 'left' at creation on a Grouping, a native group and a note — the three "
                + "types whose Archi default is a fixed constant, so those match a hand-drawn "
                + "object on any host — and leaves every other element at 'centre'. Archi derives "
                + "a plain element's default from a user preference instead, so on a host where "
                + "that preference was changed a hand-drawn element can differ from one placed "
                + "here; pass this parameter explicitly when the alignment matters. Example: "
                + "'left' to left-align a plain element's label.");

        Map<String, Object> verticalTextAlignmentProp = new LinkedHashMap<>();
        verticalTextAlignmentProp.put("type", "string");
        verticalTextAlignmentProp.put("enum", java.util.List.of("top", "centre", "center", "bottom"));
        verticalTextAlignmentProp.put("description",
                "Vertical position of the label inside the figure. Values: 'top', 'centre' (UK) / "
                + "'center' (US — accepted as a synonym), or 'bottom'. Applies to groups, notes, "
                + "and ArchiMate elements (each implements ITextPosition). Omit to leave the "
                + "per-type default unchanged (top — Archi's default; labels render in the top "
                + "header band of the figure). Example: 'centre' to vertically centre a group "
                + "label inside the group's bounding rectangle.");

        // Typography (shared with addConnectionStylingProperties).
        Map<String, Object> fontNameProp = new LinkedHashMap<>();
        fontNameProp.put("type", "string");
        fontNameProp.put("description",
                "Font family name (e.g. 'Segoe UI', 'Arial', 'Courier New'). Empty string clears "
                + "to the system default view font. Omit to leave unchanged. Archi falls back at "
                + "render time when the named font is not installed on the host system — the server "
                + "does not pre-validate against installed fonts. Example: 'Comic Sans MS'.");

        Map<String, Object> fontSizeProp = new LinkedHashMap<>();
        fontSizeProp.put("type", "integer");
        fontSizeProp.put("description",
                "Font point size (positive integer, e.g. 9, 12, 16). No upper cap — Archi handles "
                + "large sizes. Omit to leave the per-type default unchanged. Example: 14.");

        Map<String, Object> fontStyleProp = new LinkedHashMap<>();
        fontStyleProp.put("type", "string");
        fontStyleProp.put("enum", java.util.List.of("normal", "bold", "italic", "bold-italic"));
        fontStyleProp.put("description",
                "Font style. Values: 'normal' (default), 'bold', 'italic', or 'bold-italic'. "
                + "Omit to leave unchanged. Example: 'bold'.");

        // Gradient (view-object only — silently ignored on connections).
        Map<String, Object> gradientProp = new LinkedHashMap<>();
        gradientProp.put("type", "string");
        gradientProp.put("enum", java.util.List.of("none", "top-bottom", "bottom-top", "left-right", "right-left"));
        gradientProp.put("description",
                "Shape fill gradient direction. Values: 'none' (Archi default — flat fill), "
                + "'top-bottom' (gradient starts at top), 'bottom-top', 'left-right', or "
                + "'right-left'. Applies to view objects (groups + ArchiMate elements + notes) — "
                + "silently ignored on connections. Empty string clears to 'none'. Omit to leave "
                + "unchanged. Example: 'top-bottom'.");

        // Note borderType (note-only — silently ignored on other view objects).
        Map<String, Object> borderTypeProp = new LinkedHashMap<>();
        borderTypeProp.put("type", "string");
        borderTypeProp.put("enum", java.util.List.of("dogear", "rectangle", "none"));
        borderTypeProp.put("description",
                "Note border type. Values: 'dogear' (Archi default — folded-corner note), "
                + "'rectangle' (plain rectangular border), or 'none' (no visible border). "
                + "Applies ONLY to notes (add-note-to-view + update-view-object on a note) — "
                + "silently ignored on groups, ArchiMate elements, and connections. Distinct from "
                + "figureType (which uses tabbed/rectangular vocabulary for groups). Empty string "
                + "clears to 'dogear'. Omit to leave unchanged. Example: 'rectangle'.");

        // deriveLineColor (view-object only).
        Map<String, Object> deriveLineColorProp = new LinkedHashMap<>();
        deriveLineColorProp.put("type", "boolean");
        deriveLineColorProp.put("description",
                "When true (Archi default), the element's outline colour is derived from its fill "
                + "colour (typically a darker shade). When false, the explicit lineColor is used "
                + "verbatim. Applies to view objects — silently ignored on connections. Omit to "
                + "leave unchanged. Example: false (to honour an explicit lineColor regardless of fill).");

        // outlineOpacity (view-object only).
        Map<String, Object> outlineOpacityProp = new LinkedHashMap<>();
        outlineOpacityProp.put("type", "integer");
        outlineOpacityProp.put("description",
                "Outline (border line) opacity from 0 (fully transparent) to 255 (fully opaque). "
                + "Archi default is 255. Distinct from 'opacity' (which controls fill opacity). "
                + "Applies to view objects — silently ignored on connections. Omit to leave "
                + "unchanged. Example: 128 (half-transparent outline).");

        // lineStyle on view objects only. On a connection there is nothing to write — the dash
        // pattern comes from the relationship type and the metamodel holds no connection
        // line-style attribute — so the connection tools REJECT it rather than dropping it.
        Map<String, Object> lineStyleProp = new LinkedHashMap<>();
        lineStyleProp.put("type", "string");
        lineStyleProp.put("enum", java.util.List.of("solid", "dashed", "dotted", "none"));
        lineStyleProp.put("description",
                "View-object outline (border) line style. Values: 'solid' (Archi default), "
                + "'dashed', 'dotted', or 'none' (no visible outline). Applies to view objects "
                + "(elements, groups, notes) ONLY; on a view object, empty string clears to "
                + "default and omitting it leaves it unchanged. On the connection tools ANY value "
                + "is REJECTED with INVALID_PARAMETER rather than ignored — including the empty "
                + "string, because there is no connection line style to clear: a connection's line "
                + "style is determined by its ArchiMate relationship type and cannot be overridden "
                + "on the view. Use lineColor and lineWidth there instead. Omit the parameter "
                + "entirely on connection calls. Example: 'dashed'.");

        properties.put("fillColor", fillColorProp);
        properties.put("lineColor", lineColorProp);
        properties.put("fontColor", fontColorProp);
        properties.put("opacity", opacityProp);
        properties.put("lineWidth", lineWidthProp);
        properties.put("figureType", figureTypeProp);
        properties.put("textAlignment", textAlignmentProp);
        properties.put("verticalTextAlignment", verticalTextAlignmentProp);
        properties.put("fontName", fontNameProp);
        properties.put("fontSize", fontSizeProp);
        properties.put("fontStyle", fontStyleProp);
        properties.put("gradient", gradientProp);
        properties.put("borderType", borderTypeProp);
        properties.put("deriveLineColor", deriveLineColorProp);
        properties.put("outlineOpacity", outlineOpacityProp);
        properties.put("lineStyle", lineStyleProp);
    }

    /**
     * Adds connection styling property definitions (lineColor, lineWidth, fontColor;
     * fontName/fontSize/fontStyle — lineStyle is view-object-only per
     * empirical correction) to a tool spec properties map.
     * Connections don't support fillColor or opacity.
     */
    private void addConnectionStylingProperties(Map<String, Object> properties) {
        Map<String, Object> lineColorProp = new LinkedHashMap<>();
        lineColorProp.put("type", "string");
        lineColorProp.put("description",
                "Line colour in #RRGGBB hex format. "
                + "Empty string clears to default. Omit to leave unchanged.");

        Map<String, Object> fontColorProp = new LinkedHashMap<>();
        fontColorProp.put("type", "string");
        fontColorProp.put("description",
                "Font/label colour in #RRGGBB hex format. "
                + "Empty string clears to default. Omit to leave unchanged.");

        Map<String, Object> lineWidthProp = new LinkedHashMap<>();
        lineWidthProp.put("type", "integer");
        lineWidthProp.put("description",
                "Line width from 1 to 3. Default is 1. Omit to leave unchanged.");

        // Typography for connection labels.
        Map<String, Object> fontNameProp = new LinkedHashMap<>();
        fontNameProp.put("type", "string");
        fontNameProp.put("description",
                "Font family name for the connection label (e.g. 'Segoe UI', 'Arial'). Empty string "
                + "clears to system default view font. Omit to leave unchanged. Example: 'Verdana'.");

        Map<String, Object> fontSizeProp = new LinkedHashMap<>();
        fontSizeProp.put("type", "integer");
        fontSizeProp.put("description",
                "Font point size for the connection label (positive integer). Omit to leave the "
                + "per-type default unchanged. Example: 11.");

        Map<String, Object> fontStyleProp = new LinkedHashMap<>();
        fontStyleProp.put("type", "string");
        fontStyleProp.put("enum", java.util.List.of("normal", "bold", "italic", "bold-italic"));
        fontStyleProp.put("description",
                "Font style for the connection label. Values: 'normal' (default), 'bold', "
                + "'italic', 'bold-italic'. Omit to leave unchanged. Example: 'italic'.");

        properties.put("lineColor", lineColorProp);
        properties.put("fontColor", fontColorProp);
        properties.put("lineWidth", lineWidthProp);
        // Typography only (lineStyle is view-object-only per empirical correction):
        properties.put("fontName", fontNameProp);
        properties.put("fontSize", fontSizeProp);
        properties.put("fontStyle", fontStyleProp);
    }

    /**
     * Parses a labelPosition string ("source"/"middle"/"target") to integer (0/1/2).
     * Returns null if the value is not provided.
     */
    private Integer parseLabelPosition(Map<String, Object> args) {
        Object value = args.get("labelPosition");
        if (value == null) return null;
        String pos = value.toString().toLowerCase();
        return switch (pos) {
            case "source" -> 0;
            case "middle" -> 1;
            case "target" -> 2;
            default -> throw new IllegalArgumentException(
                    "Invalid labelPosition: '" + pos + "'. Must be 'source', 'middle', or 'target'.");
        };
    }

    private StylingParams extractStylingParams(Map<String, Object> args) {
        String fillColor = HandlerUtils.optionalStringParamAllowEmpty(args, "fillColor");
        String lineColor = HandlerUtils.optionalStringParamAllowEmpty(args, "lineColor");
        String fontColor = HandlerUtils.optionalStringParamAllowEmpty(args, "fontColor");
        Integer opacity = HandlerUtils.optionalIntegerParam(args, "opacity");
        Integer lineWidth = HandlerUtils.optionalIntegerParam(args, "lineWidth");
        // Empty string for the three new fields is treated as null ("unchanged")
        // — they have no symmetric "clear" semantics like colours do.
        String figureType = HandlerUtils.optionalStringParam(args, "figureType");
        String textAlignment = HandlerUtils.optionalStringParam(args, "textAlignment");
        String verticalTextAlignment = HandlerUtils.optionalStringParam(args, "verticalTextAlignment");

        // Typography (allow empty to clear fontName to default; the enum fields
        // use the no-empty helper, since "" is not a meaningful enum value).
        String fontName = HandlerUtils.optionalStringParamAllowEmpty(args, "fontName");
        Integer fontSize = HandlerUtils.optionalIntegerParam(args, "fontSize");
        String fontStyle = HandlerUtils.optionalStringParam(args, "fontStyle");
        // View-object line style; allowEmpty for the "clear to default" symmetry the other
        // view-object fields have. Empty is preserved rather than folded to null on purpose: on a
        // connection every non-null value, "" included, is refused by
        // StylingHelper.validateConnectionStylingParams, and folding "" to null here would make
        // that one value silently succeed — the exact silence this rejection replaced.
        String lineStyle = HandlerUtils.optionalStringParamAllowEmpty(args, "lineStyle");
        // Gradient + borderType: allowEmpty for "clear to default" symmetry.
        String gradient = HandlerUtils.optionalStringParamAllowEmpty(args, "gradient");
        String borderType = HandlerUtils.optionalStringParamAllowEmpty(args, "borderType");
        // deriveLineColor is a Boolean; null = unchanged, true/false = set.
        Boolean deriveLineColor = (args.get("deriveLineColor") instanceof Boolean b) ? b : null;
        Integer outlineOpacity = HandlerUtils.optionalIntegerParam(args, "outlineOpacity");
        // recede is a tri-state Boolean (add-to-view / add-group-to-view only): null = default
        // (auto-recede a null-fill parent), false = opt out. Must be carried even when no other
        // styling is set, so it is part of the "is there anything to carry?" guard below.
        Boolean recede = (args.get("recede") instanceof Boolean b) ? b : null;

        if (fillColor == null && lineColor == null && fontColor == null
                && opacity == null && lineWidth == null
                && figureType == null && textAlignment == null && verticalTextAlignment == null
                && fontName == null && fontSize == null && fontStyle == null
                && lineStyle == null && gradient == null && borderType == null
                && deriveLineColor == null && outlineOpacity == null && recede == null) {
            return null;
        }
        return new StylingParams(fillColor, lineColor, fontColor, opacity, lineWidth,
                figureType, textAlignment, verticalTextAlignment,
                fontName, fontSize, fontStyle, lineStyle, gradient, borderType,
                deriveLineColor, outlineOpacity, recede);
    }

    // ---- Image helper methods ----

    /**
     * Adds image property definitions (imagePath, imagePosition, showIcon)
     * to a tool spec properties map. Used by add-to-view, add-group-to-view,
     * add-note-to-view, and update-view-object.
     */
    private void addImageProperties(Map<String, Object> properties) {
        Map<String, Object> imagePathProp = new LinkedHashMap<>();
        imagePathProp.put("type", "string");
        imagePathProp.put("description",
                "Archive image path from add-image-to-model. Set to empty string \"\" to remove image.");

        Map<String, Object> imagePositionProp = new LinkedHashMap<>();
        imagePositionProp.put("type", "string");
        imagePositionProp.put("description",
                "Image position on element: top-left, top-centre, top-right (Archi default — "
                + "AVOID: element type icon is shown here by default and will obscure custom images), "
                + "middle-left, middle-centre, middle-right, bottom-left (recommended for icons), "
                + "bottom-centre, bottom-right, fill");
        imagePositionProp.put("enum", List.of("top-left", "top-centre", "top-right",
                "middle-left", "middle-centre", "middle-right",
                "bottom-left", "bottom-centre", "bottom-right", "fill"));

        Map<String, Object> showIconProp = new LinkedHashMap<>();
        showIconProp.put("type", "string");
        showIconProp.put("description",
                "ArchiMate type icon visibility alongside custom image: "
                + "if-no-image (default — show icon only when no custom image), always, never");
        showIconProp.put("enum", List.of("if-no-image", "always", "never"));

        properties.put("imagePath", imagePathProp);
        properties.put("imagePosition", imagePositionProp);
        properties.put("showIcon", showIconProp);
    }

    private ImageParams extractImageParams(Map<String, Object> args) {
        String imagePath = HandlerUtils.optionalStringParamAllowEmpty(args, "imagePath");
        String imagePosition = HandlerUtils.optionalStringParam(args, "imagePosition");
        String showIcon = HandlerUtils.optionalStringParam(args, "showIcon");

        if (imagePath == null && imagePosition == null && showIcon == null) {
            return null;
        }
        return new ImageParams(imagePath, imagePosition, showIcon);
    }

    // ---- detect-hub-elements ----

    private McpServerFeatures.SyncToolSpecification buildDetectHubElementsSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to analyse for hub elements");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("detect-hub-elements")
                .description("Identify hub elements on a view by counting visual connections "
                        + "per element, sorted descending. Returns each element's viewObjectId, "
                        + "name, type, connection count, current dimensions, and maxLabelWidth "
                        + "(estimated pixel width of the longest connection label).\n\n"
                        + "Hub thresholds: "
                        + ">=5 connections is a hub candidate; "
                        + ">6 connections receives an explicit sizing suggestion based on the "
                        + "hub element formula (baseDimension + 15px \u00d7 (connectionCount \u2212 6)), "
                        + "adjusted for label widths when labels require more space.\n\n"
                        + "For high-fan-out hubs (> 12 connections), the response also surfaces a "
                        + "2D-resize suggestion (width += 15 \u00d7 \u2308excess/2\u2309, height += 15 \u00d7 \u230aexcess/2\u230b) "
                        + "alongside the 1D pair, so the calling agent can pick 2D inflation when "
                        + "the connection fan-out warrants distributing ports across all four edges "
                        + "(~N/4 connections per edge).\n\n"
                        + "Use after layout and before auto-route-connections to optimise hub "
                        + "element sizes for better connection routing.\n\n"
                        + "Note: assess-layout's M5 hub-port-quality metric uses a separate "
                        + "internal M5_FACE_GUARD_MIN_CONNECTIONS=4 per-face guard that is unrelated "
                        + "to the >6 sizing-suggestion threshold here.\n\n"
                        + "Related: update-view-object (resize hubs \u2014 preferred over "
                        + "resize-elements-to-fit which is label-driven and not aware of connection "
                        + "fan-out), auto-route-connections (re-route after resizing), "
                        + "assess-layout (verify hubPortQualityScore improvement).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleDetectHubElements)
                .build();
    }

    McpSchema.CallToolResult handleDetectHubElements(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling detect-hub-elements request");
        try {
            HandlerUtils.requireModelLoaded(accessor);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");

            DetectHubElementsResultDto dto = accessor.detectHubElements(viewId);

            List<String> nextSteps = buildDetectHubElementsNextSteps(dto);
            String modelVersion = accessor.getModelVersion();
            Map<String, Object> envelope = formatter.formatSuccess(
                    dto, nextSteps, modelVersion, 1, 1, false);
            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling detect-hub-elements", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildDetectHubElementsNextSteps(DetectHubElementsResultDto dto) {
        List<String> steps = new ArrayList<>();
        boolean hasHubs = dto.suggestions() != null && !dto.suggestions().isEmpty();

        if (hasHubs) {
            steps.add("Use update-view-object to resize hub elements \u2014 increase the "
                    + "dimension perpendicular to primary connection flow direction.");
            steps.add("After resizing hubs, re-run layout-within-group on the affected "
                    + "group(s) to prevent hub overlapping siblings, then arrange-groups "
                    + "to accommodate the resized group.");
            steps.add("Then run auto-route-connections to compute "
                    + "clean orthogonal paths.");
            steps.add("Run assess-layout to verify routing quality improvement.");
        } else if (!dto.elements().isEmpty()) {
            steps.add("No hub elements detected (all elements have \u22646 connections). "
                    + "Proceed with auto-route-connections for routing.");
            steps.add("Run assess-layout to check overall view quality.");
        } else {
            steps.add("View has no connected elements. Use add-to-view and "
                    + "add-connection-to-view to populate the view.");
        }
        return steps;
    }

    // ---- layout-flat-view ----

    private McpServerFeatures.SyncToolSpecification buildLayoutFlatViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to layout");

        Map<String, Object> arrangementProp = new LinkedHashMap<>();
        arrangementProp.put("type", "string");
        arrangementProp.put("enum", List.of("row", "column", "grid"));
        arrangementProp.put("description",
                "Arrangement pattern: 'row' (horizontal left-to-right), "
                + "'column' (vertical top-to-bottom), or 'grid' (rows and columns).");

        Map<String, Object> spacingProp = new LinkedHashMap<>();
        spacingProp.put("type", "integer");
        spacingProp.put("description",
                "Space between elements in pixels (default: 40). "
                + "Use 80-120 for interaction/flow views that need routing corridors.");

        Map<String, Object> paddingProp = new LinkedHashMap<>();
        paddingProp.put("type", "integer");
        paddingProp.put("description",
                "Margin from view origin (0,0) in pixels (default: 20).");

        Map<String, Object> sortByProp = new LinkedHashMap<>();
        sortByProp.put("type", "string");
        sortByProp.put("enum", List.of("name", "type", "layer"));
        sortByProp.put("description",
                "Sort elements before positioning: 'name' (alphabetical), "
                + "'type' (by ArchiMate element type), or 'layer' (by ArchiMate layer "
                + "in standard order: Strategy → Business → Application → Technology → Physical).");

        Map<String, Object> categoryFieldProp = new LinkedHashMap<>();
        categoryFieldProp.put("type", "string");
        categoryFieldProp.put("enum", List.of("type", "layer"));
        categoryFieldProp.put("description",
                "Group elements into visual sections by this field. "
                + "'type' creates sections per element type (e.g., all ApplicationComponents together). "
                + "'layer' creates sections per ArchiMate layer. "
                + "Sections have 2x spacing between them for visual separation. "
                + "Within each section, elements are arranged using the specified arrangement pattern.");

        Map<String, Object> columnsProp = new LinkedHashMap<>();
        columnsProp.put("type", "integer");
        columnsProp.put("description",
                "Number of columns for grid arrangement (default: auto-detected from "
                + "element count as ceil(sqrt(n))). Only applies to arrangement: 'grid' "
                + "— ignored for 'row' and 'column'.");

        Map<String, Object> autoLayoutChildrenProp = new LinkedHashMap<>();
        autoLayoutChildrenProp.put("type", "boolean");
        autoLayoutChildrenProp.put("description",
                "Automatically layout embedded children within parent elements "
                + "using a column arrangement (default: true). When true, children that "
                + "are stacked at default positions inside parent elements are repositioned "
                + "in a column layout, and parents are auto-resized to fit. Set to false "
                + "to skip child layout and use separate layout-within-group calls instead.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("arrangement", arrangementProp);
        properties.put("spacing", spacingProp);
        properties.put("padding", paddingProp);
        properties.put("sortBy", sortByProp);
        properties.put("categoryField", categoryFieldProp);
        properties.put("columns", columnsProp);
        properties.put("autoLayoutChildren", autoLayoutChildrenProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties,
                List.of("viewId", "arrangement"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("layout-flat-view")
                .description("[Mutation] PREFERRED LAYOUT TOOL for views WITHOUT groups — "
                        + "positions all top-level elements using row, column, or grid "
                        + "arrangement. Use THIS tool when the view has no groups or when you "
                        + "want to arrange top-level items (elements and groups) on the canvas. "
                        + "Computes positions server-side using each element's actual current "
                        + "size — elements with embedded children get proportionally more space. "
                        + "EMBEDDED CHILDREN: automatically repositions children inside parent "
                        + "elements in a column layout and auto-resizes parents to fit "
                        + "(set autoLayoutChildren=false to skip). "
                        + "THAT AUTO-RESIZE IS THE ONE THING THIS CALL CHANGES THAT YOU DID NOT "
                        + "ASK FOR, and `resizedElements` names it: every object whose SIZE this "
                        + "call changed, with the rectangle it landed at, so you do not plan your "
                        + "next call against a height this one already replaced. A parent grown to "
                        + "contain the children laid out inside it is what populates it — nothing "
                        + "else here resizes anything, because all three arrangements write each "
                        + "element its own size and the grid is uniform in cell SPACING, not cell "
                        + "SIZE. Inside an OPEN BATCH the sizes come from what the batch has "
                        + "already queued rather than from the view as it stands, so a size an "
                        + "earlier operation of the same batch asked for SURVIVES this call "
                        + "instead of being overwritten by the older one, and it is what the "
                        + "neighbouring elements are spaced around. `resizedElements` measures "
                        + "against that same basis, so an element left at the size the batch "
                        + "already gave it does not appear in it. "
                        + "`elementsRepositioned` and `childrenRepositioned` count objects "
                        + "PLACED, moved or not and resized or not, so neither can tell you this "
                        + "happened. It is an observation — an element re-written to the size it "
                        + "already had does not appear — and it is omitted entirely when empty. "
                        + "An element that only MOVED and kept its size is not in it: this list is "
                        + "about size alone. With autoLayoutChildren=false nothing can grow, so it "
                        + "is always absent. "
                        + "Does NOT route connections — run auto-route-connections after for "
                        + "clean orthogonal paths. Use 'sortBy' to sort elements before layout "
                        + "and 'categoryField' to create visual sections by type or layer "
                        + "without needing explicit groups. SPECULATIVE EXECUTION: layout → "
                        + "assess-layout → undo if unsatisfied (try different spacing or arrangement). "
                        + "Related: auto-route-connections (route after layout), assess-layout "
                        + "(evaluate result), auto-layout-and-route (ELK — for grouped views "
                        + "or when you need combined layout+routing), "
                        + "layout-within-group (layout elements INSIDE a specific group).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleLayoutFlatView)
                .build();
    }

    McpSchema.CallToolResult handleLayoutFlatView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling layout-flat-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String arrangement = HandlerUtils.requireStringParam(args, "arrangement");

            // Optional parameters
            Integer spacing = HandlerUtils.optionalIntegerParam(args, "spacing");
            Integer padding = HandlerUtils.optionalIntegerParam(args, "padding");
            String sortBy = HandlerUtils.optionalStringParam(args, "sortBy");
            String categoryField = HandlerUtils.optionalStringParam(args, "categoryField");
            Integer columns = HandlerUtils.optionalIntegerParam(args, "columns");
            boolean autoLayoutChildren = HandlerUtils.optionalBooleanParam(args, "autoLayoutChildren", true);

            MutationResult<LayoutFlatViewResultDto> result =
                    accessor.layoutFlatView(sessionId, viewId, arrangement,
                            spacing, padding, sortBy, categoryField, columns,
                            autoLayoutChildren);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildLayoutFlatViewNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling layout-flat-view", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildLayoutFlatViewNextSteps(
            MutationResult<LayoutFlatViewResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Run auto-route-connections to compute clean orthogonal paths "
                        + "between the repositioned elements.",
                "Use assess-layout to evaluate the overall layout quality.",
                "Use undo to roll back and try different spacing or arrangement "
                        + "if the result is unsatisfactory.");
    }

    // ---- resize-elements-to-fit ----

    private McpServerFeatures.SyncToolSpecification buildResizeElementsToFitSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view whose elements to resize");

        Map<String, Object> elementIdsProp = new LinkedHashMap<>();
        elementIdsProp.put("type", "array");
        Map<String, Object> elementIdItems = new LinkedHashMap<>();
        elementIdItems.put("type", "string");
        elementIdsProp.put("items", elementIdItems);
        elementIdsProp.put("description",
                "Optional list of specific element view object IDs to resize. "
                + "If omitted, resizes all elements on the view. "
                + "Get valid IDs from get-view-contents visualMetadata.");

        Map<String, Object> wrapFitProp = new LinkedHashMap<>();
        wrapFitProp.put("type", "boolean");
        wrapFitProp.put("description",
                "Optional (default false). When true, uses compact WRAP-FIT sizing: each targeted "
                + "element KEEPS its current width and only grows its height so the label wraps to a "
                + "second line and fits, instead of widening to a single line. Ancestor containers "
                + "grow height-only to contain the taller children. Use this for embedded elements in "
                + "a dense grid (e.g. nested ApplicationFunctions in 150x26 boxes) where single-line "
                + "widening would shift neighbours — wrap-fit preserves the grid's horizontal pitch. "
                + "Scope with elementIds to target only the labels you want wrapped.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("elementIds", elementIdsProp);
        properties.put("wrapFit", wrapFitProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("resize-elements-to-fit")
                .description("[Mutation] **Sizes for label legibility only — not for connection fan-out.** "
                        + "For hub elements (≥ 5 connections, the canonical "
                        + "HUB_DETECTION_THRESHOLD) where the issue is connection-port congestion "
                        + "rather than label clipping, use detect-hub-elements plus update-view-object "
                        + "instead. This tool optimizes for a 1.5:1 label-aware aspect ratio and "
                        + "ignores connection count.\n\n"
                        + "Resize elements on a view to fit their label text. "
                        + "Uses SWT font metrics and aspect-ratio-aware sizing "
                        + "(target 1.5:1 width:height, range [1.2:1, 2.5:1]). "
                        + "Short names (<=15 chars) keep Archi defaults (120x55). "
                        + "For nested elements, uses two-pass algorithm: children sized first, "
                        + "then parents sized to contain children + own label + padding. "
                        + "Recommended after placing elements on flat views to prevent label truncation. "
                        + "CONTAINERS: an ArchiMate Grouping is a zone, not a label-bearing element. "
                        + "This tool GROWS one to contain its children -- which is what makes it the "
                        + "remedy for a child outside its parent -- and never shrinks one or sizes one "
                        + "to its own name. A Grouping with no children is not sized at all: if you "
                        + "named it in elementIds the response discloses it in 'skippedContainers' with "
                        + "the reason, and if the walk merely found it the response says nothing about "
                        + "it, exactly as it says nothing about a native view group. A native view "
                        + "group is never a resize TARGET -- the walk descends through it to reach the "
                        + "elements inside -- but it is still GROWN by the parent-fit cascade when a "
                        + "child this pass widened overflows it, and that growth is reported in "
                        + "'resizedGroups'. So this tool is safe to aim at a whole view built from zones. "
                        + "When assess-layout reports a child outside its parent, name THAT PARENT in "
                        + "elementIds: a zone is grown around every element it holds, including ones you "
                        + "did not name, and an element you did not name is measured but never re-sized. "
                        + "Set wrapFit=true for compact WRAP-FIT sizing (keep width, grow height so the "
                        + "label wraps to a 2nd line) — use for embedded elements in a dense grid where "
                        + "single-line widening would shift neighbours; preserves the grid's horizontal pitch. "
                        + "RESPONSE: 'resizedElements' lists each element the pass resized with the size it "
                        + "ended at. Two further lists report what the pass changed that you did not name: "
                        + "'resizedGroups' carries the groups the parent-fit cascade GREW to contain the "
                        + "elements it widened, with their effective bounds, and 'movedObjects' carries any "
                        + "object ANCHORED to something this pass grew, with where it landed — an anchored "
                        + "note travels with its target rather than being left behind. Both are omitted when "
                        + "empty. The cascade is accumulated across the WHOLE pass, so a group holding "
                        + "several resized children is reported once at its final size, not once per child. "
                        + "In a batch or pending approval nothing has executed, so neither list is "
                        + "reported and the entity is nested under 'preview' — do not read that absence "
                        + "as 'nothing grew or moved'. "
                        + "ORDERING: run this before auto-route-connections. Every route is computed against the "
                        + "bounds that existed when the router ran, so an element resized afterwards leaves its "
                        + "stored routes anchored to a box that is no longer there — re-route after any later "
                        + "resize. For a hub this is the wrong lever in either order: it sizes for the label, not "
                        + "for connection fan-out. "
                        + "Related: add-to-view with autoSize (size at placement time), "
                        + "layout-flat-view (reposition elements), "
                        + "detect-hub-elements + update-view-object (size hubs for connection fan-out), "
                        + "auto-route-connections (route after resizing).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleResizeElementsToFit)
                .build();
    }

    McpSchema.CallToolResult handleResizeElementsToFit(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling resize-elements-to-fit request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");

            // Extract optional elementIds array
            List<String> elementIds = null;
            Object elementIdsRaw = args.get("elementIds");
            if (elementIdsRaw instanceof List<?> rawList && !rawList.isEmpty()) {
                elementIds = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof String s) {
                        elementIds.add(s);
                    }
                }
            }

            boolean wrapFit = Boolean.TRUE.equals(args.get("wrapFit"));

            MutationResult<ResizeElementsResultDto> result =
                    accessor.resizeElementsToFit(sessionId, viewId, elementIds, wrapFit);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildResizeElementsNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling resize-elements-to-fit", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildResizeElementsNextSteps(
            MutationResult<ResizeElementsResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Run auto-route-connections to recompute connection paths "
                        + "after element resizing.",
                "Use assess-layout to evaluate the layout quality.",
                "Use undo to roll back if the sizes are unsatisfactory.");
    }

    // ---- adjust-view-spacing ----

    private McpServerFeatures.SyncToolSpecification buildAdjustViewSpacingSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to adjust spacing on");

        Map<String, Object> interElementDeltaProp = new LinkedHashMap<>();
        interElementDeltaProp.put("type", "integer");
        interElementDeltaProp.put("description",
                "Pixels to add between elements within each group. Positive values "
                + "increase spacing, negative values decrease. The delta is added to "
                + "the current detected spacing. Default 0 (no change).");

        Map<String, Object> paddingDeltaProp = new LinkedHashMap<>();
        paddingDeltaProp.put("type", "integer");
        paddingDeltaProp.put("description",
                "Pixels to add to group edge padding (gap between group boundary "
                + "and its children). Positive values increase padding. Default 0.");

        Map<String, Object> interGroupDeltaProp = new LinkedHashMap<>();
        interGroupDeltaProp.put("type", "integer");
        interGroupDeltaProp.put("description",
                "Pixels to add between each pair of adjacent groups. Groups are "
                + "pushed apart along their dominant axis (horizontal or vertical). "
                + "Default 0.");

        Map<String, Object> recursiveProp = new LinkedHashMap<>();
        recursiveProp.put("type", "boolean");
        recursiveProp.put("description",
                "When true (default), inflates nested subgroups too — elements "
                + "inside subgroups are repositioned with the same deltas, and "
                + "subgroups resize to fit. Set false to inflate only top-level "
                + "groups (nested subgroup internals remain unchanged).");
        recursiveProp.put("default", true);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("interElementDelta", interElementDeltaProp);
        properties.put("paddingDelta", paddingDeltaProp);
        properties.put("interGroupDelta", interGroupDeltaProp);
        properties.put("recursive", recursiveProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("adjust-view-spacing")
                .description("[Mutation] Increase spacing between elements within groups, "
                        + "group padding, and inter-group gaps while preserving layout "
                        + "topology. Automatically re-routes connections after inflation "
                        + "(if any exist — works on views with or without connections). "
                        + "Use when assess-layout reports coincident segments or tight "
                        + "spacing on a grouped view, or when elements are too tightly "
                        + "packed for visual clarity. All three deltas are additive — "
                        + "specify only the dimensions you want to inflate. Set "
                        + "recursive=false to inflate only top-level containers (default "
                        + "true inflates nested ones too). A container is either a native "
                        + "view group or an ArchiMate Grouping element; both are inflated. "
                        + "The entire operation "
                        + "(inflate + re-route) is a single undo step. "
                        + "When `interElementDelta` is OMITTED (parameter not provided) "
                        + "AND the view has a problematic spacing-related metric "
                        + "(`coincidentSegmentCount > 2` OR `connectionEdgeCoincidenceCount > 4`), "
                        + "the tool derives a heuristic-driven default from the view's "
                        + "connection count instead of using 0. "
                        + "Heuristic targets per connection count: ≤15 → 60 px element "
                        + "spacing; 16-30 → 80 px; >30 → 100 px "
                        + "(`archimate://reference/archimate-view-patterns` Pre-Layout "
                        + "Planning §2). "
                        + "Pass `interElementDelta: 0` explicitly to suppress "
                        + "default-resolution. "
                        + "The response DTO's `defaultResolutionReason` field reports "
                        + "whether default-resolution fired and which trigger metric "
                        + "and heuristic tier produced the value. "
                        + "`resizedAncestors` names any enclosing group the OVERFLOW CASCADE grew "
                        + "underneath this inflation — spacing a group's contents can push one past "
                        + "an enclosing group's edge, and that group is then widened to keep "
                        + "containing it, up several levels — each entry carrying the id and the "
                        + "rectangle it ended at, so a container you never named does not change "
                        + "size silently. It is built from that cascade ALONE: a group this tool "
                        + "re-fits to its own contents without anything overflowing is NOT in it "
                        + "— that re-fit is reported in `resizedElements` instead. "
                        + "Omitted when nothing grew, and it "
                        + "reports observations: a group recomputed to an unchanged rectangle is "
                        + "not listed. That cascade walks NATIVE view groups only: an ArchiMate "
                        + "Grouping or element acting as a container does not grow to fit contents "
                        + "that grew inside it, so it can be left too small — check it with "
                        + "assess-layout and close it with resize-elements-to-fit. "
                        + "`resizedElements` names every object whose SIZE this call changed, "
                        + "each with the rectangle it landed at. It does not matter which "
                        + "mechanism changed it: a grid arrangement handing every cell the width "
                        + "of the widest element in it, a nested container this same call "
                        + "re-fitted, an enclosing group the overflow cascade grew, and a "
                        + "container re-fitted to its own inflated contents are all in the one "
                        + "list. That last one includes a TOP-LEVEL container, which is usually "
                        + "the largest single resize of the call: the fit is bidirectional, so a "
                        + "group left far roomier than its contents SHRINKS to them even though "
                        + "you asked only to increase spacing. Only the grid mechanism is "
                        + "arrangement-specific: the re-fits fire on row and column arrangements "
                        + "too, where the arrangement itself preserves sizes exactly. "
                        + "`elementsRepositioned` "
                        + "counts children PLACED, moved or not and resized or not, so it can "
                        + "never tell you either happened. It is an observation — an object "
                        + "re-written to the size it already had does not appear — and it is "
                        + "omitted entirely when empty. An object that only MOVED and kept its "
                        + "size is not in it: this list is about size alone. "
                        + "The two lists are NOT disjoint: a nested group this call re-fits and the "
                        + "cascade then grows again is named in both, and both carry the same "
                        + "final rectangle. "
                        + "In a batch or pending approval nothing has executed, so both lists are "
                        + "projections of what WOULD land rather than state the model holds, and "
                        + "the entity is nested under 'preview' — do not read them as applied. "
                        + "Related: assess-layout (diagnose spacing issues), "
                        + "optimize-group-order (reorder elements to reduce crossings), "
                        + "auto-route-connections (route-only without spacing change), "
                        + "apply-element-spacing-recommendations (the explicit-opt-in "
                        + "convenience-tool surface for the same heuristic; useful when "
                        + "you want a `dryRun` preview or the full before/after envelope)."
                        + " Any archimate:// URI named here can also be read by calling get-guidance with that uri, for clients that do not expose MCP resources.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAdjustViewSpacing)
                .build();
    }

    McpSchema.CallToolResult handleAdjustViewSpacing(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling adjust-view-spacing request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");

            Integer interElementDelta = HandlerUtils.optionalIntegerParam(args,
                    "interElementDelta");
            Integer paddingDelta = HandlerUtils.optionalIntegerParam(args,
                    "paddingDelta");
            Integer interGroupDelta = HandlerUtils.optionalIntegerParam(args,
                    "interGroupDelta");
            boolean recursive = HandlerUtils.optionalBooleanParam(args,
                    "recursive", true);

            MutationResult<AdjustViewSpacingResultDto> result =
                    accessor.adjustViewSpacing(sessionId, viewId,
                            interElementDelta, paddingDelta,
                            interGroupDelta, recursive);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAdjustViewSpacingNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling adjust-view-spacing", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * What this tool discloses, on the one deferred arm it has.
     *
     * <p>There is no awaiting-approval wording here because there is no awaiting-approval arm.
     * The accessor consults no approval gate and stores no proposal — both of its returns use the
     * two-argument result constructor, which cannot carry a proposal context — so an
     * approval-tense sentence would describe a state this tool cannot produce. That is asserted on
     * the mechanism rather than assumed.</p>
     *
     * <p>The coincident-segment count is a real reading, not a projection: the control loop
     * dispatches the candidate geometry, routes it, assesses the result, and undoes the dispatch
     * before returning, so the figure describes a state the view genuinely held. It is taken above
     * the queue decision, which is why a queued caller can be given it.</p>
     *
     * <p>The recovery cannot travel with it. On a queued call nothing has been applied, so
     * {@code undo} would revert whichever command happens to be on top of the stack — somebody
     * else's — and what discards this change is the batch.</p>
     */
    private List<String> adjustViewSpacingDisclosures(
            AdjustViewSpacingResultDto dto, DispatchArm arm) {
        if (arm == DispatchArm.AWAITING_APPROVAL) {
            // Refused once, here, rather than answered three times below with wording nothing can
            // reach: this tool never stores a proposal, so a caller cannot be on that arm.
            throw new IllegalArgumentException(
                    "adjust-view-spacing has no awaiting-approval arm to disclose on");
        }
        boolean queued = arm == DispatchArm.QUEUED;
        List<String> steps = new ArrayList<>();
        if (dto == null) {
            return steps;
        }
        steps.add(queued
                ? "Use assess-layout after end-batch to verify the quality improvement — it reads "
                        + "the committed view, which this batch has not changed yet."
                : "Use assess-layout to verify the quality improvement.");
        if (dto.coincidentSegmentCount() > 0) {
            steps.add(queued
                    ? "Coincident segments will remain once this batch commits — re-issue with a "
                            + "larger interElementDelta."
                    : "Coincident segments remain — try a larger interElementDelta.");
        }
        steps.add(queued
                ? "Nothing has been applied yet, so undo would revert whichever command is on top "
                        + "of the stack; discard this spacing change with end-batch rollback:true "
                        + "instead."
                : "Use undo to roll back if the result is unsatisfactory.");
        return steps;
    }

    private List<String> buildAdjustViewSpacingNextSteps(
            MutationResult<AdjustViewSpacingResultDto> result) {
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>(
                    adjustViewSpacingDisclosures(result.entity(), DispatchArm.QUEUED));
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        return adjustViewSpacingDisclosures(result.entity(), DispatchArm.APPLIED);
    }

    // ---- apply-element-spacing-recommendations
    //      (RoutingPreconditions.InterElement) ----

    /**
     * What the three spacing convenience tools say about the objects they re-size, in one place.
     *
     * <p>All three run the same control loop over the same helper and report through the same
     * embedded {@code adjustResult}, so the sentence describing that report has to be the same
     * sentence. Three copies kept in step by hand is how two of them end up telling an agent
     * different things about one field; a shared constant makes the agreement structural, which a
     * test asserting the three are equal can only check after the fact.</p>
     */
    private static final String RESIZED_ELEMENTS_RESPONSE_DOC =
            "RESPONSE: `resizedElements`, inside `adjustResult`, names every object "
            + "whose SIZE this call changed, each with the rectangle it landed at. It "
            + "does not matter which mechanism changed it: a grid arrangement handing "
            + "every cell the width of the widest element in it, a nested container "
            + "this same call re-fitted, an enclosing group grown to keep containing "
            + "what overflowed it, and the one-shot hub resize the escalate step "
            + "performs are all in the one list — the hub resize is usually the "
            + "largest of them. It covers ONLY the iterations this call committed: a "
            + "step the internal loop tried and reverted left the model as it found "
            + "it, so its rectangle is not here, and its absence is not evidence "
            + "nothing was tried — read `appliedDeltas` and `terminationReason` for "
            + "that. Where two committed iterations re-sized one object, the "
            + "rectangle reported is the one the LAST of them landed it at, which is "
            + "the size the model now holds. An object that only MOVED is not in it: "
            + "this list is about size alone, and an object re-written to the size it "
            + "already had does not appear. `resizedAncestors` is NOT populated on "
            + "this path — across several iterations a rectangle cannot be attributed "
            + "to one mechanism, so the whole report is in `resizedElements` and the "
            + "other list is omitted. Omitted entirely when nothing changed size. In "
            + "a batch nothing has executed yet, so the list is a projection of what "
            + "WOULD land rather than state the model holds, and the entity is nested "
            + "under `preview` — do not read it as applied. ";

    private McpServerFeatures.SyncToolSpecification
            buildApplyElementSpacingRecommendationsSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description",
                "ID of the view to read and (when not dryRun) inflate spacing on");

        Map<String, Object> dryRunProp = new LinkedHashMap<>();
        dryRunProp.put("type", "boolean");
        dryRunProp.put("description",
                "When true, computes the recommendation (current spacing, "
                + "current connection count, target spacing, recommended "
                + "interElementDelta) and returns the before-snapshot only "
                + "WITHOUT mutating. Use to preview before committing. "
                + "Default false (apply the inflation).");
        dryRunProp.put("default", false);

        Map<String, Object> targetSpacingProp = new LinkedHashMap<>();
        targetSpacingProp.put("type", "integer");
        targetSpacingProp.put("description",
                "Optional explicit target element spacing in pixels. When "
                + "omitted, the heuristic from "
                + "archimate://reference/archimate-view-patterns Pre-Layout "
                + "Planning §2 is used (≤15 connections → 60px, 16-30 → 80px, "
                + ">30 → 100px). When provided, this overrides the heuristic; "
                + "the response still reports heuristicRecommendation for "
                + "transparency.");

        Map<String, Object> iterationBudgetProp = new LinkedHashMap<>();
        iterationBudgetProp.put("type", "integer");
        iterationBudgetProp.put("description",
                "Optional cap on the embedded observe→decide→back-off control "
                + "loop's iteration count. Range [1, 20]; default 5. Each "
                + "iteration applies a small spacing step (+10/step monotone "
                + "ladder from currentSpacing toward targetSpacing) then "
                + "re-runs assess-layout; the loop ACCEPTS the step if "
                + "aggregate thresholds_met holds or grows, REVERTS the step "
                + "and HALTS if aggregate thresholds_met regresses (per-metric "
                + "monotonicity is NOT used). Returned terminationReason in "
                + "the response DTO names which in-loop branch fired "
                + "(goal_reached, which this tool cannot actually reach, / "
                + "budget_exhausted / aggregate_threshold_"
                + "regressed / iteration_apply_failed / structural_no_change / "
                + "heuristic_already_met). The THREE pre-loop guards "
                + "(dry_run_recommendation_not_applied, "
                + "reroute_degraded_input_baseline, "
                + "density_precondition_infeasible_reflow_required) also "
                + "surface via terminationReason; see the parent tool "
                + "description for the full ten-branch enumeration. "
                + "Out-of-range values raise invalid_argument.");
        iterationBudgetProp.put("minimum", 1);
        iterationBudgetProp.put("maximum", 20);
        iterationBudgetProp.put("default", 5);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("dryRun", dryRunProp);
        properties.put("targetSpacing", targetSpacingProp);
        properties.put("iterationBudget", iterationBudgetProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("apply-element-spacing-recommendations")
                .description("[Mutation] Convenience tool that runs an "
                        + "embedded observe → decide → density-aware "
                        + "3-state-termination control loop to inflate "
                        + "inter-element spacing on a grouped view until the "
                        + "view reaches strict quality, is honestly flagged "
                        + "as needing a structural reflow, or the iteration "
                        + "budget is exhausted. Per iteration: read the "
                        + "view's current connection count + per-group "
                        + "element spacing → consult the inter-element "
                        + "heuristics table → take a spacing step (a "
                        + "+10/step monotone ladder while progressing; a "
                        + "LARGE step when escalating) → re-run assess-layout "
                        + "→ classify on a 2×2 of aggregate-trend × "
                        + "spacing-regime-position: (1) aggregate still "
                        + "climbing → CONTINUE; (2) aggregate stalled AND "
                        + "the view is BELOW the prescribed ~100–124px "
                        + "average-spacing / fan-out-sized-hub regime → "
                        + "ESCALATE (inflate toward the ~112px mid-band in a "
                        + "few large steps + a one-shot hub-resize toward "
                        + "the fan-out-sized hub dimension); (3) "
                        + "aggregate stalled AND the view is already "
                        + "AT/ABOVE the prescribed regime → PASS-HONEST: "
                        + "more spacing cannot help, so the loop STOPS, "
                        + "preserves the best (never-degraded) state, and "
                        + "surfaces an actionable reflow-required diagnosis "
                        + "(see densityFloorDiagnosis below). "
                        + "A degrading step is reverted, but only as the "
                        + "loop's own step scalar measures it: six inputs "
                        + "that read element overlaps as a single bit and "
                        + "that do not read cousin overlaps, off-canvas "
                        + "placement or the overall rating at all. This tool "
                        + "CAN therefore return a view rated worse than the "
                        + "one it was handed, and it now says so — the "
                        + "before and after assess-layout snapshots are "
                        + "compared on the overall rating, and a regression "
                        + "is reported as a SPACING_RATING_REGRESSED "
                        + "structured warning naming both ratings and every "
                        + "metric that moved. The spacing stays applied; one "
                        + "undo reverses the whole call. On a queued (batch) "
                        + "call the comparison cannot be taken and nextSteps "
                        + "says so rather than staying silent. "
                        + "Single tool call = single undo-stack entry "
                        + "regardless of iteration count (accepted iterations "
                        + "wrap in one NonNotifyingCompoundCommand). "
                        + "Heuristic: ≤15 connections → 60px, 16-30 → 80px, "
                        + ">30 → 100px (source-of-truth: "
                        + "archimate://reference/archimate-view-patterns "
                        + "Pre-Layout Planning §2). "
                        + "For views with one or more large hubs (any "
                        + "element with > 6 connections, the canonical "
                        + "hub-candidate threshold), the heuristic returns "
                        + "the hub-aware tier instead: ≤15 → 80px, 16-30 → "
                        + "100px, >30 → 120px (+20px per tier). The "
                        + "hub-aware tier accounts for the corridor space "
                        + "that formula-resized hubs consume — without it, "
                        + "the heuristic UNDERSHOOTS post-hub-resize and "
                        + "coincSeg residuals persist. "
                        + "Termination contract — the loop terminates on "
                        + "exactly ONE of ten branches (seven in-loop "
                        + "branches + THREE pre-loop guards: dryRun + "
                        + "reroute-degraded + density-precondition-"
                        + "infeasible), surfaced in "
                        + "response DTO via terminationReason + "
                        + "iterationCount + appliedDeltas: "
                        + "(a) goal_reached_at_iteration_N (target envelope "
                        + "met, but "
                        + "NOT REACHABLE through this tool — the loop's "
                        + "goal predicate defaults to false and this tool "
                        + "never overrides it, so no call terminates here; "
                        + "listed because the constant exists); "
                        + "(b) budget_exhausted_after_N_iterations "
                        + "(iterationBudget cap hit, last accepted step "
                        + "commits); (c) aggregate_threshold_regressed_at_"
                        + "iteration_N_reverted_to_iteration_M (back-off "
                        + "fired, last accepted step commits); "
                        + "(d) structural_no_change_<reason> (no groups / "
                        + "no groups with 2+ children / no connections / "
                        + "every container drawn inside a host, so this tool "
                        + "positions none of them — call arrange-groups); "
                        + "(e) heuristic_already_met_no_change "
                        + "(currentSpacing ≥ targetSpacing at iteration 0); "
                        + "(f) dry_run_recommendation_not_applied (dryRun="
                        + "true entry-guard short-circuit; no mutation; "
                        + "iterationCount=0; appliedDeltas=[]); "
                        + "(g) iteration_apply_failed_at_iteration_N_"
                        + "reverted_after_M_accepted_iterations (a contained "
                        + "mutation — typically a route command — threw "
                        + "mid-application; best-effort rollback applied + "
                        + "prior M accepted iterations preserved for the "
                        + "outer compound dispatch); "
                        + "(h) density_floor_reflow_required (IN-LOOP "
                        + "PASS-HONEST: the loop ran, reached an in-regime "
                        + "density floor — more spacing cannot help. The "
                        + "loop STOPS without degrading the view; the "
                        + "response carries a densityFloorDiagnosis string "
                        + "naming the violated precondition: measured "
                        + "average spacing vs the 100–124px band, and the "
                        + "hub WxH vs its connection count. The loop NEVER "
                        + "auto-reflows — a structural reflow moves "
                        + "user-placed elements, so it instead OFFERS the "
                        + "reflow as an explicit user-consentable next step: "
                        + "surface + offer + wait for consent, never "
                        + "surface + act); "
                        + "(i) reroute_degraded_input_baseline (PRE-LOOP "
                        + "accessor-layer safety net, sibling to "
                        + "dry_run_recommendation_not_applied: the tool's "
                        + "internal pre-loop reroute pass scored a strictly "
                        + "lower aggregate thresholdsMet than the bare input "
                        + "baseline, indicating the reroute would have "
                        + "degraded the input. The bare input is returned "
                        + "UNTOUCHED — iterationCount=0, appliedDeltas=[], "
                        + "no mutation, no view damage. See "
                        + "archimate://prompts/routing-preconditions-"
                        + "checklist § \"When a spacing tool says it would "
                        + "have degraded the input\" for the correct "
                        + "response); "
                        + "(j) density_precondition_infeasible_reflow_required "
                        + "(PRE-LOOP SOUND infeasibility certificate, "
                        + "honestly DISTINCT from (h): the SOUND one-sided "
                        + "closed-form test idealUniformAvg = "
                        + "sqrt(unionArea/N) − avgBox < 100 proved the input "
                        + "precondition is infeasible on the current canvas; "
                        + "the loop was NEVER entered. Zero false-positives "
                        + "by construction. The view is returned UNTOUCHED "
                        + "— iterationCount=0, appliedDeltas=[], no "
                        + "mutation, no view damage. The DTO carries a "
                        + "densityFloorDiagnosis + a consent-gated reflow "
                        + "OFFER; act on it the SAME way as (h) — see "
                        + "archimate://prompts/routing-preconditions-"
                        + "checklist § \"When a spacing tool says the view "
                        + "needs a structural reflow\"). "
                        + "NOTE — in budget_exhausted_after_N_iterations, "
                        + "aggregate_threshold_regressed_at_iteration_N_"
                        + "reverted_to_iteration_M and iteration_apply_"
                        + "failed_at_iteration_N_reverted_after_M_accepted_"
                        + "iterations, N counts accepted COMMANDS, not "
                        + "iterations, despite the tokens' own nouns: an "
                        + "escalate iteration pushes TWO commands (a "
                        + "one-shot hub-resize plus that iteration's spacing "
                        + "command), so N exceeds the reported iteration "
                        + "count by the number of hub resizes. A "
                        + "termination reason of "
                        + "budget_exhausted_after_3_iterations beside an "
                        + "iteration count of 2 is consistent, not "
                        + "contradictory. Read the iteration-count field "
                        + "named above when you want iterations. "
                        + "The loop objective is the aggregate thresholds_met "
                        + "scalar ONLY (per-metric monotonicity rules are NOT "
                        + "used; they spuriously stop on net-positive "
                        + "mutations); escalate changes the step + target, "
                        + "not the objective. "
                        + "The iterationBudget parameter defaults to 5 (caller-tunable, "
                        + "[1, 20]); appliedDeltas[] reports each accepted "
                        + "iteration's spacing step in pixels. "
                        + "Set dryRun=true to preview the recommendation "
                        + "without mutation; default false runs the loop. "
                        + "Returns before/after assess-layout snapshots in "
                        + "one envelope so the visual-quality impact is "
                        + "visible immediately. Use after assess-layout "
                        + "reports connectionEdgeCoincidenceCount (M4) > 4 "
                        + "OR coincidentSegments > 2 on a "
                        + "grouped view, when you want one-call inflation-"
                        + "and-re-route with internal back-off. For surgical "
                        + "spacing edits between specific element pairs, use "
                        + "update-view-object directly. "
                        + "Best results occur when invoked AFTER hub resizing "
                        + "(use detect-hub-elements + update-view-object first "
                        + "when assess-layout reports hubPortQualityScore < "
                        + "0.5) AND PAIRED WITH inter-group spacing widening "
                        + "(use the sibling tool "
                        + "apply-group-spacing-recommendations on grouped "
                        + "views with inter-group connections, or "
                        + "arrange-groups / adjust-view-spacing with "
                        + "interGroupDelta as manual alternatives). This "
                        + "tool inflates within-group element spacing only — "
                        + "it does NOT widen group-vs-group corridors, so "
                        + "residual edge-coincidence between groups will "
                        + "persist until inter-group spacing is also "
                        + "addressed. "
                        + "If you want the inflation-knee guard "
                        + "enforced (per-call clamp of NO MORE than "
                        + "+80px element / +100px inter-group from "
                        + "current spacing, preventing cumulative "
                        + "inflation past the knee where additional "
                        + "spacing introduces NEW defects rather "
                        + "than reducing residual ones), use the "
                        + "composed tool "
                        + "`apply-spacing-recommendations(scope=both)` "
                        + "instead — it bundles BOTH heuristics in a "
                        + "single transactional call with the knee "
                        + "guard built in. "
                        + RESIZED_ELEMENTS_RESPONSE_DOC
                        + "ORDERING: call this after auto-route-connections. The loop scores its own internal "
                        + "reroute pass against the input it was handed before it starts; on connections carrying "
                        + "no stored routing the two sides are separated by a whole routing pass rather than a "
                        + "refinement of one, the pre-loop check returns reroute_degraded_input_baseline, and the "
                        + "loop is never entered — no spacing is applied at all and the view comes back untouched. "
                        + "Routing first gives that check two routed states to compare. This is the default order, "
                        + "not a recovery step. "
                        + "Related: adjust-view-spacing (the underlying "
                        + "primitive — call directly when you want explicit "
                        + "deltas including paddingDelta + interGroupDelta), "
                        + "apply-group-spacing-recommendations (sibling — "
                        + "inter-group corridor widening), "
                        + "detect-hub-elements + update-view-object (hub "
                        + "resize precondition), arrange-groups (inter-group "
                        + "corridor widening), assess-layout (diagnose "
                        + "spacing issues first), auto-route-connections "
                        + "(route-only without spacing change). "
                        + "See archimate://prompts/"
                        + "routing-preconditions-checklist for the canonical "
                        + "LLM-facing precondition playbook."
                        + " Any archimate:// URI named here can also be read by calling get-guidance with that uri, for clients that do not expose MCP resources.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleApplyElementSpacingRecommendations)
                .build();
    }

    McpSchema.CallToolResult handleApplyElementSpacingRecommendations(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling apply-element-spacing-recommendations request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            boolean dryRun = HandlerUtils.optionalBooleanParam(args,
                    "dryRun", false);
            Integer targetSpacing = HandlerUtils.optionalIntegerParam(args,
                    "targetSpacing");
            Integer iterationBudget = HandlerUtils.optionalIntegerParam(args,
                    "iterationBudget");

            MutationResult<ApplyElementSpacingRecommendationsResultDto> result =
                    accessor.applyElementSpacingRecommendations(sessionId, viewId,
                            dryRun, targetSpacing, iterationBudget);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildApplyElementSpacingRecommendationsNextSteps(result),
                    accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling "
                    + "apply-element-spacing-recommendations", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildApplyElementSpacingRecommendationsNextSteps(
            MutationResult<ApplyElementSpacingRecommendationsResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations",
                    SPACING_COMPARISON_UNAVAILABLE_QUEUED_STEP);
        }
        ApplyElementSpacingRecommendationsResultDto dto = result.entity();
        List<String> steps = new ArrayList<>();
        if (dto.noChangeReason() != null) {
            steps.add("No change applied: " + dto.noChangeReason());
            if (dto.connectionCount() == 0) {
                steps.add("Add connections to the view, then re-run this tool.");
            }
            return steps;
        }
        if (dto.dryRun()) {
            steps.add("Recommendation: inflate by interElementDelta="
                    + dto.interElementDelta() + "px to reach target "
                    + dto.targetSpacingPx() + "px (current "
                    + dto.currentSpacingPx() + "px, "
                    + dto.connectionCount() + " connections).");
            steps.add("Re-run with dryRun=false to apply.");
            return steps;
        }
        steps.add("Inflated by " + dto.interElementDelta() + "px (current "
                + dto.currentSpacingPx() + " → target "
                + dto.targetSpacingPx() + ").");
        if (hasSpacingRatingRegressedWarning(dto.structuredWarnings())) {
            steps.add(SPACING_RATING_REGRESSED_STEP);
        }
        if (dto.after() != null && dto.before() != null) {
            int beforeM4 = dto.before().connectionEdgeCoincidenceCount();
            int afterM4 = dto.after().connectionEdgeCoincidenceCount();
            int beforeCoinc = dto.before().coincidentSegmentCount();
            int afterCoinc = dto.after().coincidentSegmentCount();
            steps.add("M4 (edge-coincidence): " + beforeM4 + " → " + afterM4
                    + ". Coincident segments: " + beforeCoinc + " → "
                    + afterCoinc + ".");
            // When residual remains AND view has groups, the most likely
            // cause on multi-group views is tight inter-group corridors —
            // element-spacing inflation alone cannot widen group-vs-group
            // gaps. Prompt the agent toward the next precondition in the
            // three-tool triad (hub resize, element spacing, inter-group
            // spacing).
            if ((afterCoinc > 2 || afterM4 > 4) && dto.after().hasGroups()) {
                steps.add("Inter-group corridors may be tight — call "
                        + "apply-group-spacing-recommendations to widen "
                        + "group-vs-group gaps using the same heuristic "
                        + "table, or arrange-groups / adjust-view-spacing "
                        + "with interGroupDelta as manual alternatives. "
                        + "Element-spacing inflation alone cannot widen "
                        + "inter-group corridors.");
            }
            if (dto.after().hubPortQualityScore() < HUB_PORT_QUALITY_REMEDY_THRESHOLD) {
                steps.add("hubPortQualityScore is "
                        + dto.after().hubPortQualityScore()
                        + " (below the good band at "
                        + HUB_PORT_QUALITY_REMEDY_THRESHOLD
                        + ") — hub elements may be undersized. Use "
                        + "detect-hub-elements + update-view-object to "
                        + "resize hubs before re-running this tool.");
            }
            if (afterCoinc > 0 || afterM4 > 0) {
                steps.add("If residual coincidence remains AFTER inter-group "
                        + "spacing widening + hub resizing, consider larger "
                        + "targetSpacing or surgical update-view-object "
                        + "edits as a last resort.");
            }
        }
        steps.add("Use assess-layout to re-verify quality. Use undo to roll "
                + "back if unsatisfactory.");
        return steps;
    }

    // ---- apply-group-spacing-recommendations
    //      (RoutingPreconditions.InterGroup) ----

    private McpServerFeatures.SyncToolSpecification
            buildApplyGroupSpacingRecommendationsSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description",
                "ID of the view to read and (when not dryRun) widen "
                + "inter-group corridors on");

        Map<String, Object> dryRunProp = new LinkedHashMap<>();
        dryRunProp.put("type", "boolean");
        dryRunProp.put("description",
                "When true, computes the recommendation (current group "
                + "spacing, total + inter-group connection counts, target "
                + "group spacing, recommended interGroupDelta) and returns "
                + "the before-snapshot only WITHOUT mutating. Use to preview "
                + "before committing. Default false (apply the inflation).");
        dryRunProp.put("default", false);

        Map<String, Object> targetSpacingProp = new LinkedHashMap<>();
        targetSpacingProp.put("type", "integer");
        targetSpacingProp.put("description",
                "Optional explicit target inter-group spacing in pixels. "
                + "When omitted, the heuristic from "
                + "archimate://reference/archimate-view-patterns Pre-Layout "
                + "Planning §2 is used (≤15 connections → 80px connected / "
                + "40px unconnected, 16-30 → 100px/40px, >30 → 120px/60px). "
                + "When provided, this overrides the heuristic; the response "
                + "still reports heuristicRecommendation for transparency.");

        Map<String, Object> iterationBudgetProp = new LinkedHashMap<>();
        iterationBudgetProp.put("type", "integer");
        iterationBudgetProp.put("description",
                "Optional cap on the embedded observe→decide→back-off control "
                + "loop's iteration count. Range [1, 20]; default 5. Each "
                + "iteration applies a small inter-group spacing step "
                + "(+10/step monotone ladder from currentSpacing toward "
                + "targetSpacing) then re-runs assess-layout; the loop "
                + "ACCEPTS the step if aggregate thresholds_met holds or "
                + "grows, REVERTS the step and HALTS if aggregate "
                + "thresholds_met regresses (per-metric monotonicity is NOT "
                + "used). Returned terminationReason in the response DTO "
                + "names which in-loop branch fired "
                + "(goal_reached, which this tool cannot actually reach, / "
                + "budget_exhausted / "
                + "aggregate_threshold_regressed / iteration_apply_failed / "
                + "structural_no_change / heuristic_already_met). The THREE "
                + "pre-loop guards (dry_run_recommendation_not_applied, "
                + "reroute_degraded_input_baseline, "
                + "density_precondition_infeasible_reflow_required) also "
                + "surface via terminationReason; see the parent tool "
                + "description for the full ten-branch enumeration. Out-of-"
                + "range values raise invalid_argument.");
        iterationBudgetProp.put("minimum", 1);
        iterationBudgetProp.put("maximum", 20);
        iterationBudgetProp.put("default", 5);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("dryRun", dryRunProp);
        properties.put("targetSpacing", targetSpacingProp);
        properties.put("iterationBudget", iterationBudgetProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("apply-group-spacing-recommendations")
                .description("[Mutation] Convenience tool that runs an "
                        + "embedded observe → decide → density-aware "
                        + "3-state-termination control loop to widen "
                        + "inter-group corridors on a multi-group view until "
                        + "the view reaches strict quality, is honestly "
                        + "flagged as needing a structural reflow, or the "
                        + "iteration budget is exhausted. Per iteration: "
                        + "read the view's current connection count + "
                        + "inter-group connection count + current MIN "
                        + "inter-group spacing → consult the inter-group "
                        + "heuristics table → take a spacing step (a "
                        + "+10/step monotone ladder while progressing; a "
                        + "LARGE step when escalating) → re-run assess-layout "
                        + "→ classify on a 2×2 of aggregate-trend × "
                        + "spacing-regime-position: aggregate still climbing "
                        + "→ CONTINUE; aggregate stalled AND below the "
                        + "prescribed ~100–124px / fan-out-sized-hub regime "
                        + "→ ESCALATE (large spacing steps toward the "
                        + "~112px mid-band + a one-shot hub-resize; the fix "
                        + "for the old too-early back-off); aggregate "
                        + "stalled AND already in-regime → PASS-HONEST "
                        + "(more spacing cannot help — STOP, preserve the "
                        + "best non-degraded state, surface an actionable "
                        + "reflow-required diagnosis). "
                        + "A degrading step is reverted, but only as the "
                        + "loop's own step scalar measures it: six inputs "
                        + "that read element overlaps as a single bit and "
                        + "that do not read cousin overlaps, off-canvas "
                        + "placement or the overall rating at all. This tool "
                        + "CAN therefore return a view rated worse than the "
                        + "one it was handed, and it now says so — the "
                        + "before and after assess-layout snapshots are "
                        + "compared on the overall rating, and a regression "
                        + "is reported as a SPACING_RATING_REGRESSED "
                        + "structured warning naming both ratings and every "
                        + "metric that moved. The spacing stays applied; one "
                        + "undo reverses the whole call. On a queued (batch) "
                        + "call the comparison cannot be taken and nextSteps "
                        + "says so rather than staying silent. "
                        + "Single tool call = "
                        + "single undo-stack entry regardless of iteration "
                        + "count (accepted iterations wrap in one "
                        + "NonNotifyingCompoundCommand). "
                        + "Heuristic: ≤15 connections → 80px connected / "
                        + "40px unconnected, 16-30 → 100px/40px, >30 → "
                        + "120px/60px (source-of-truth: "
                        + "archimate://reference/archimate-view-patterns "
                        + "Pre-Layout Planning §2). "
                        + "For views with one or more large hubs (any "
                        + "element with > 6 connections, the canonical "
                        + "hub-candidate threshold), the connected-column "
                        + "heuristic returns the hub-aware tier instead: "
                        + "≤15 → 100px, 16-30 → 140px, >30 → 160px "
                        + "(+20-40px per tier). The hub-aware tier "
                        + "accounts for the corridor space that "
                        + "formula-resized hubs consume — without it, "
                        + "inter-group corridors stay too narrow and "
                        + "coincSeg residuals persist on inter-group "
                        + "connections. The unconnected column "
                        + "(40/40/60) is hub-agnostic and unchanged. "
                        + "Termination contract — the loop terminates on "
                        + "exactly ONE of ten branches (seven in-loop "
                        + "branches + THREE pre-loop guards: dryRun + "
                        + "reroute-degraded + density-precondition-"
                        + "infeasible), surfaced in "
                        + "response DTO via terminationReason + "
                        + "iterationCount + appliedDeltas: "
                        + "(a) goal_reached_at_iteration_N (target envelope "
                        + "met, but "
                        + "NOT REACHABLE through this tool — the loop's "
                        + "goal predicate defaults to false and this tool "
                        + "never overrides it, so no call terminates here; "
                        + "listed because the constant exists); "
                        + "(b) budget_exhausted_after_N_iterations "
                        + "(iterationBudget cap hit, last accepted step "
                        + "commits); (c) aggregate_threshold_regressed_at_"
                        + "iteration_N_reverted_to_iteration_M (back-off "
                        + "fired, last accepted step commits); "
                        + "(d) structural_no_change_<reason> (no groups / "
                        + "fewer than 2 top-level groups / no inter-group "
                        + "connections / the corridor lies between containers "
                        + "drawn inside a host, so this tool positions none "
                        + "of them — call arrange-groups); "
                        + "(e) heuristic_already_met_no_change "
                        + "(currentSpacing ≥ targetSpacing at iteration 0); "
                        + "(f) dry_run_recommendation_not_applied (dryRun="
                        + "true entry-guard short-circuit; no mutation; "
                        + "iterationCount=0; appliedDeltas=[]); "
                        + "(g) iteration_apply_failed_at_iteration_N_"
                        + "reverted_after_M_accepted_iterations (a contained "
                        + "mutation — typically a route command — threw "
                        + "mid-application; best-effort rollback applied + "
                        + "prior M accepted iterations preserved for the "
                        + "outer compound dispatch); "
                        + "(h) density_floor_reflow_required (IN-LOOP "
                        + "PASS-HONEST: the loop ran, reached an in-regime "
                        + "density floor — more spacing cannot help. The "
                        + "loop STOPS without degrading the view; the "
                        + "response carries a densityFloorDiagnosis string "
                        + "naming the violated precondition: measured "
                        + "average spacing vs the 100–124px band, and the "
                        + "hub WxH vs its connection count. The loop NEVER "
                        + "auto-reflows — a structural reflow moves "
                        + "user-placed elements, so it instead OFFERS the "
                        + "reflow as an explicit user-consentable next step: "
                        + "surface + offer + wait for consent, never "
                        + "surface + act); "
                        + "(i) reroute_degraded_input_baseline (PRE-LOOP "
                        + "accessor-layer safety net, sibling to "
                        + "dry_run_recommendation_not_applied: the tool's "
                        + "internal pre-loop reroute pass scored a strictly "
                        + "lower aggregate thresholdsMet than the bare input "
                        + "baseline, indicating the reroute would have "
                        + "degraded the input. The bare input is returned "
                        + "UNTOUCHED — iterationCount=0, appliedDeltas=[], "
                        + "no mutation, no view damage. See "
                        + "archimate://prompts/routing-preconditions-"
                        + "checklist § \"When a spacing tool says it would "
                        + "have degraded the input\" for the correct "
                        + "response); "
                        + "(j) density_precondition_infeasible_reflow_required "
                        + "(PRE-LOOP SOUND infeasibility certificate, "
                        + "honestly DISTINCT from (h): the SOUND one-sided "
                        + "closed-form test idealUniformAvg = "
                        + "sqrt(unionArea/N) − avgBox < 100 proved the input "
                        + "precondition is infeasible on the current canvas; "
                        + "the loop was NEVER entered. Zero false-positives "
                        + "by construction. The view is returned UNTOUCHED "
                        + "— iterationCount=0, appliedDeltas=[], no "
                        + "mutation, no view damage. The DTO carries a "
                        + "densityFloorDiagnosis + a consent-gated reflow "
                        + "OFFER; act on it the SAME way as (h) — see "
                        + "archimate://prompts/routing-preconditions-"
                        + "checklist § \"When a spacing tool says the view "
                        + "needs a structural reflow\"). "
                        + "NOTE — in budget_exhausted_after_N_iterations, "
                        + "aggregate_threshold_regressed_at_iteration_N_"
                        + "reverted_to_iteration_M and iteration_apply_"
                        + "failed_at_iteration_N_reverted_after_M_accepted_"
                        + "iterations, N counts accepted COMMANDS, not "
                        + "iterations, despite the tokens' own nouns: an "
                        + "escalate iteration pushes TWO commands (a "
                        + "one-shot hub-resize plus that iteration's spacing "
                        + "command), so N exceeds the reported iteration "
                        + "count by the number of hub resizes. A "
                        + "termination reason of "
                        + "budget_exhausted_after_3_iterations beside an "
                        + "iteration count of 2 is consistent, not "
                        + "contradictory. Read the iteration-count field "
                        + "named above when you want iterations. "
                        + "The loop objective is the aggregate thresholds_met "
                        + "scalar ONLY (per-metric monotonicity rules are NOT "
                        + "used; they spuriously stop on net-positive "
                        + "mutations); escalate changes the step + target, "
                        + "not the objective. "
                        + "The iterationBudget parameter defaults to 5 (caller-tunable, "
                        + "[1, 20]); appliedDeltas[] reports each accepted "
                        + "iteration's spacing step in pixels. "
                        + "Set dryRun=true to preview the recommendation "
                        + "without mutation; default false runs the loop. "
                        + "Preserves your current group ordering and "
                        + "topology — only widens inter-group corridors "
                        + "(strategy: inflate-only, single-undo, sibling-"
                        + "symmetric with apply-element-spacing-"
                        + "recommendations). Returns before/after "
                        + "assess-layout snapshots in one envelope so the "
                        + "visual-quality impact (especially "
                        + "connectionEdgeCoincidenceCount on inter-group "
                        + "connections) is visible immediately. "
                        + "Use after assess-layout reports "
                        + "connectionEdgeCoincidenceCount (M4) > 4 OR "
                        + "coincidentSegmentCount > 2 on a grouped view "
                        + "AND the view has inter-group connections that "
                        + "need wider routing corridors. For surgical "
                        + "group-position edits, use update-view-object "
                        + "directly. For full topology-driven re-layout, "
                        + "use auto-layout-and-route with mode='grouped'. "
                        + "Completes the routing-preconditions triad "
                        + "(hub sizing + inter-element spacing + "
                        + "inter-group spacing). For best results, invoke "
                        + "AFTER hub resizing AND PAIRED WITH "
                        + "apply-element-spacing-recommendations on grouped "
                        + "views with inter-group connections. "
                        + "If you want the inflation-knee guard "
                        + "enforced (per-call clamp of NO MORE than "
                        + "+80px element / +100px inter-group from "
                        + "current spacing, preventing cumulative "
                        + "inflation past the knee where additional "
                        + "spacing introduces NEW defects rather "
                        + "than reducing residual ones), use the "
                        + "composed tool "
                        + "`apply-spacing-recommendations(scope=both)` "
                        + "instead — it bundles BOTH heuristics in a "
                        + "single transactional call with the knee "
                        + "guard built in. "
                        + RESIZED_ELEMENTS_RESPONSE_DOC
                        + "ORDERING: call this after auto-route-connections. The loop scores its own internal "
                        + "reroute pass against the input it was handed before it starts; on connections carrying "
                        + "no stored routing the two sides are separated by a whole routing pass rather than a "
                        + "refinement of one, the pre-loop check returns reroute_degraded_input_baseline, and the "
                        + "loop is never entered — no spacing is applied at all and the view comes back untouched. "
                        + "Routing first gives that check two routed states to compare. This is the default order, "
                        + "not a recovery step. "
                        + "Related: adjust-view-spacing (the underlying "
                        + "primitive — call directly when you want explicit "
                        + "deltas including interElementDelta + "
                        + "paddingDelta), apply-element-spacing-"
                        + "recommendations (sibling — within-group spacing), "
                        + "detect-hub-elements + update-view-object (hub "
                        + "resize precondition), assess-layout (diagnose "
                        + "spacing issues first), auto-route-connections "
                        + "(route-only without spacing change), "
                        + "arrange-groups + optimize-group-order (full "
                        + "topology re-layout primitives)."
                        + " Any archimate:// URI named here can also be read by calling get-guidance with that uri, for clients that do not expose MCP resources.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleApplyGroupSpacingRecommendations)
                .build();
    }

    McpSchema.CallToolResult handleApplyGroupSpacingRecommendations(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling apply-group-spacing-recommendations request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            boolean dryRun = HandlerUtils.optionalBooleanParam(args,
                    "dryRun", false);
            Integer targetSpacing = HandlerUtils.optionalIntegerParam(args,
                    "targetSpacing");
            Integer iterationBudget = HandlerUtils.optionalIntegerParam(args,
                    "iterationBudget");

            MutationResult<ApplyGroupSpacingRecommendationsResultDto> result =
                    accessor.applyGroupSpacingRecommendations(sessionId, viewId,
                            dryRun, targetSpacing, iterationBudget);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildApplyGroupSpacingRecommendationsNextSteps(result),
                    accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling "
                    + "apply-group-spacing-recommendations", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildApplyGroupSpacingRecommendationsNextSteps(
            MutationResult<ApplyGroupSpacingRecommendationsResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations",
                    SPACING_COMPARISON_UNAVAILABLE_QUEUED_STEP);
        }
        ApplyGroupSpacingRecommendationsResultDto dto = result.entity();
        List<String> steps = new ArrayList<>();
        if (dto.noChangeReason() != null) {
            steps.add("No change applied: " + dto.noChangeReason());
            return steps;
        }
        if (dto.dryRun()) {
            steps.add("Recommendation: widen inter-group corridors by "
                    + "interGroupDelta=" + dto.interGroupDelta()
                    + "px to reach target " + dto.targetSpacingPx()
                    + "px (current " + dto.currentSpacingPx() + "px, "
                    + dto.totalConnectionCount() + " total connections / "
                    + dto.interGroupConnectionCount()
                    + " inter-group, "
                    + (dto.isConnected() ? "connected" : "unconnected")
                    + " column).");
            steps.add("Re-run with dryRun=false to apply.");
            return steps;
        }
        steps.add("Widened inter-group corridors by "
                + dto.interGroupDelta() + "px (current "
                + dto.currentSpacingPx() + " → target "
                + dto.targetSpacingPx() + ", "
                + (dto.isConnected() ? "connected" : "unconnected")
                + " column).");
        if (hasSpacingRatingRegressedWarning(dto.structuredWarnings())) {
            steps.add(SPACING_RATING_REGRESSED_STEP);
        }
        if (dto.after() != null && dto.before() != null) {
            int beforeM4 = dto.before().connectionEdgeCoincidenceCount();
            int afterM4 = dto.after().connectionEdgeCoincidenceCount();
            int beforeCoinc = dto.before().coincidentSegmentCount();
            int afterCoinc = dto.after().coincidentSegmentCount();
            steps.add("M4 (edge-coincidence): " + beforeM4 + " → " + afterM4
                    + ". Coincident segments: " + beforeCoinc + " → "
                    + afterCoinc + ".");
            if (dto.after().hubPortQualityScore() < HUB_PORT_QUALITY_REMEDY_THRESHOLD) {
                steps.add("hubPortQualityScore is "
                        + dto.after().hubPortQualityScore()
                        + " (below the good band at "
                        + HUB_PORT_QUALITY_REMEDY_THRESHOLD
                        + ") — hub elements may be undersized. Use "
                        + "detect-hub-elements + update-view-object to "
                        + "resize hubs before re-running this tool.");
            }
            if ((afterCoinc > 2 || afterM4 > 4)
                    && dto.after().hasGroups()) {
                steps.add("Residual coincidence remains — consider also "
                        + "running apply-element-spacing-recommendations to "
                        + "widen within-group element spacing (the inter-"
                        + "group corridor widening this tool applies does "
                        + "not address tight intra-group element spacing). "
                        + "Together they form the routing-preconditions "
                        + "triad with hub resizing.");
            }
            if (afterCoinc > 0 || afterM4 > 0) {
                steps.add("If residual coincidence remains AFTER element "
                        + "spacing widening + hub resizing, consider larger "
                        + "targetSpacing or surgical update-view-object "
                        + "edits as a last resort.");
            }
        }
        steps.add("Use assess-layout to re-verify quality. Use undo to roll "
                + "back if unsatisfactory.");
        return steps;
    }

    // ---- apply-spacing-recommendations
    //      (composed; RoutingPreconditions.Composed) ----

    private McpServerFeatures.SyncToolSpecification
            buildApplySpacingRecommendationsSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description",
                "ID of the view to read and (when not dryRun) inflate "
                + "spacing on");

        Map<String, Object> scopeProp = new LinkedHashMap<>();
        scopeProp.put("type", "string");
        scopeProp.put("description",
                "Which spacing arm(s) to compute and apply. 'both' (default) "
                + "computes element + inter-group deltas and passes both to a "
                + "single adjust-view-spacing call. 'element' computes only "
                + "the element delta (equivalent to "
                + "apply-element-spacing-recommendations plus the knee-clamp "
                + "guard). 'group' computes only the inter-group delta "
                + "(equivalent to apply-group-spacing-recommendations plus "
                + "the knee-clamp guard). Any other value returns an "
                + "invalid_parameter error.");
        scopeProp.put("enum", List.of("both", "element", "group"));
        scopeProp.put("default", "both");

        Map<String, Object> dryRunProp = new LinkedHashMap<>();
        dryRunProp.put("type", "boolean");
        dryRunProp.put("description",
                "When true, computes the recommendation (current spacings, "
                + "connection counts, target spacings, proposed deltas, "
                + "clamped deltas, knee-clamp flags) and returns the "
                + "before-snapshot only WITHOUT mutating. Use to preview "
                + "before committing. Default false (apply the inflation).");
        dryRunProp.put("default", false);

        Map<String, Object> elementTargetProp = new LinkedHashMap<>();
        elementTargetProp.put("type", "integer");
        elementTargetProp.put("description",
                "Optional explicit target element spacing in pixels. When "
                + "omitted, the inter-element heuristic from "
                + "archimate://reference/archimate-view-patterns Pre-Layout "
                + "Planning §2 is used. When provided, overrides the "
                + "heuristic for the element arm; the knee-clamp still "
                + "applies on top of the override.");

        Map<String, Object> groupTargetProp = new LinkedHashMap<>();
        groupTargetProp.put("type", "integer");
        groupTargetProp.put("description",
                "Optional explicit target inter-group spacing in pixels. "
                + "When omitted, the inter-group heuristic from "
                + "archimate://reference/archimate-view-patterns Pre-Layout "
                + "Planning §2 is used. When provided, overrides the "
                + "heuristic for the group arm; the knee-clamp still "
                + "applies on top of the override.");

        Map<String, Object> iterationBudgetProp = new LinkedHashMap<>();
        iterationBudgetProp.put("type", "integer");
        iterationBudgetProp.put("description",
                "Optional cap on the embedded observe→decide→back-off control "
                + "loop's TOTAL iteration count across both arms. Range "
                + "[1, 20]; default 8 (split floor(N/2) for element arm + "
                + "ceil(N/2) for group arm; 4+4 at default). Each arm runs "
                + "an independent control loop with its own per-iteration "
                + "step cap (element +80px max per step; inter-group +100px "
                + "max per step; the ELEMENT_KNEE_LIMIT_PX / "
                + "GROUP_KNEE_LIMIT_PX constants are reinterpreted as "
                + "per-iteration step caps in the composer, NOT per-call "
                + "total caps). Per-arm terminationReason fields surface "
                + "in the response DTO (elementTerminationReason / "
                + "groupTerminationReason) and name which in-loop "
                + "branch fired on that arm (goal_reached, which this "
                + "tool cannot actually reach, / "
                + "budget_exhausted / aggregate_threshold_regressed / "
                + "iteration_apply_failed / structural_no_change / "
                + "heuristic_already_met). The THREE pre-loop guards "
                + "(dry_run_recommendation_not_applied, "
                + "reroute_degraded_input_baseline, "
                + "density_precondition_infeasible_reflow_required) also "
                + "surface via the per-arm terminationReason fields; see "
                + "the parent tool description for the full ten-branch "
                + "enumeration. "
                + "Out-of-range values raise invalid_argument.");
        iterationBudgetProp.put("minimum", 1);
        iterationBudgetProp.put("maximum", 20);
        iterationBudgetProp.put("default", 8);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("scope", scopeProp);
        properties.put("dryRun", dryRunProp);
        properties.put("elementTargetSpacing", elementTargetProp);
        properties.put("groupTargetSpacing", groupTargetProp);
        properties.put("iterationBudget", iterationBudgetProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("apply-spacing-recommendations")
                .description("[Mutation] Composed convenience tool that runs "
                        + "TWO coordinated observe → decide → density-aware "
                        + "3-state-termination control loops (element arm "
                        + "first, inter-group arm second) to inflate BOTH "
                        + "element and inter-group spacing on a multi-group "
                        + "view, with the inflation-knee constants "
                        + "reinterpreted as PER-ITERATION step caps inside "
                        + "each loop. Each arm classifies on a 2×2 of "
                        + "aggregate-trend × spacing-regime-position: "
                        + "climbing → CONTINUE; stalled + below the "
                        + "~100–124px / fan-out-sized-hub regime → ESCALATE "
                        + "(large steps toward the ~112px mid-band + a "
                        + "one-shot hub-resize — the fix for the old "
                        + "too-early back-off); stalled + already in-regime "
                        + "→ PASS-HONEST (more spacing cannot help — STOP, "
                        + "preserve the best non-degraded state, surface an "
                        + "actionable reflow-required diagnosis). "
                        + "A degrading step is reverted, but only as the "
                        + "loop's own step scalar measures it: six inputs "
                        + "that read element overlaps as a single bit and "
                        + "that do not read cousin overlaps, off-canvas "
                        + "placement or the overall rating at all. This tool "
                        + "CAN therefore return a view rated worse than the "
                        + "one it was handed, and it now says so — the "
                        + "before and after assess-layout snapshots are "
                        + "compared on the overall rating, and a regression "
                        + "is reported as a SPACING_RATING_REGRESSED "
                        + "structured warning naming both ratings and every "
                        + "metric that moved. The spacing stays applied; one "
                        + "undo reverses the whole call. On a queued (batch) "
                        + "call the comparison cannot be taken and nextSteps "
                        + "says so rather than staying silent. "
                        + "Single tool call = single "
                        + "undo-stack entry across both arms (all accepted "
                        + "iterations from both loops wrap in one outer "
                        + "NonNotifyingCompoundCommand). "
                        + "Heuristics (source-of-truth: "
                        + "archimate://reference/archimate-view-patterns "
                        + "Pre-Layout Planning §2): element column "
                        + "60/80/100px (hub-aware 80/100/120px); inter-group "
                        + "connected column 80/100/120px (hub-aware "
                        + "100/140/160px); inter-group unconnected column "
                        + "40/40/60px. "
                        + "The `scope` parameter ('both' fires both arms; "
                        + "'element' fires the element arm only; 'group' "
                        + "fires the group arm only; default 'both') "
                        + "selects which loops run. "
                        + "Set dryRun=true to preview the recommendation "
                        + "without mutation; default false runs the loops. "
                        + "Each iteration applies a small spacing step "
                        + "(+10/step monotone ladder), capped per step by "
                        + "+80px (element arm) / +100px (inter-group arm) "
                        + "— the same constants previously used as per-call "
                        + "total clamps are now per-iteration caps, "
                        + "preventing the cumulative-inflation-past-the-knee "
                        + "failure mode (stacked spacing calls pushing past "
                        + "the narrow-corridor structural floor; see "
                        + "archimate://reference/archimate-view-patterns "
                        + "§ Pre-Layout Planning Checklist). The legacy "
                        + "elementKneeClampApplied / groupKneeClampApplied "
                        + "DTO fields continue to surface when a clamp "
                        + "fires inside an arm's loop. "
                        + "Termination contract — each arm terminates on "
                        + "exactly ONE of ten branches (seven in-loop "
                        + "branches + THREE pre-loop guards: dryRun + "
                        + "reroute-degraded + density-precondition-"
                        + "infeasible), surfaced in "
                        + "response DTO via per-arm "
                        + "elementTerminationReason / groupTerminationReason "
                        + "+ elementIterationCount / groupIterationCount + "
                        + "elementAppliedDeltas[] / groupAppliedDeltas[]: "
                        + "(a) goal_reached_at_iteration_N (NOT REACHABLE "
                        + "through this tool — the loop's goal predicate "
                        + "defaults to false and this tool never overrides "
                        + "it, so no call terminates here; listed because "
                        + "the constant exists); "
                        + "(b) budget_exhausted_after_N_iterations; "
                        + "(c) aggregate_threshold_regressed_at_iteration_N_"
                        + "reverted_to_iteration_M; "
                        + "(d) structural_no_change_<reason> (one is "
                        + "containers drawn inside a host, which neither arm "
                        + "positions — call arrange-groups); "
                        + "(e) heuristic_already_met_no_change; "
                        + "(f) dry_run_recommendation_not_applied (dryRun="
                        + "true entry-guard short-circuit; no mutation); "
                        + "(g) iteration_apply_failed_at_iteration_N_"
                        + "reverted_after_M_accepted_iterations (a contained "
                        + "mutation — typically a route command — threw "
                        + "mid-application on that arm; best-effort rollback "
                        + "applied + prior M accepted iterations preserved "
                        + "for the outer compound dispatch); "
                        + "(h) density_floor_reflow_required (IN-LOOP "
                        + "PASS-HONEST, per-arm: the arm's loop ran and "
                        + "reached an in-regime density floor — more "
                        + "spacing cannot help. The arm STOPS without "
                        + "degrading the view; the response carries a "
                        + "per-arm elementDensityFloorDiagnosis / "
                        + "groupDensityFloorDiagnosis string naming the "
                        + "violated precondition: measured average spacing "
                        + "vs the 100–124px band, and the hub WxH vs its "
                        + "connection count. The loop NEVER auto-reflows — a "
                        + "structural reflow moves user-placed elements, so "
                        + "it instead OFFERS the reflow as an explicit "
                        + "user-consentable next step: surface + offer + "
                        + "wait for consent, never surface + act); "
                        + "(i) reroute_degraded_input_baseline (PRE-LOOP "
                        + "accessor-layer safety net, per-arm — each arm runs "
                        + "its own routeNormalizedBaseline check before the "
                        + "loop: the arm's internal pre-loop reroute pass "
                        + "scored a strictly lower aggregate thresholdsMet "
                        + "than the bare input baseline. The arm contributes "
                        + "no commands — iterationCount=0, appliedDeltas=[], "
                        + "no mutation, no view damage. When one arm "
                        + "short-circuits and the other proceeds, the DTO "
                        + "carries the per-arm reasons distinctly. See "
                        + "archimate://prompts/routing-"
                        + "preconditions-checklist § \"When a spacing tool "
                        + "says it would have degraded the input\" for the "
                        + "correct response); "
                        + "(j) density_precondition_infeasible_reflow_required "
                        + "(PRE-LOOP SOUND infeasibility certificate, "
                        + "per-arm — honestly DISTINCT from (h): the SOUND "
                        + "one-sided closed-form test idealUniformAvg = "
                        + "sqrt(unionArea/N) − avgBox < 100 proved the "
                        + "arm's input precondition is infeasible on the "
                        + "current canvas; the arm's loop was NEVER entered. "
                        + "Zero false-positives by construction. The view is "
                        + "returned UNTOUCHED on that arm. Both composer "
                        + "arms see the same per-view geometry ⇒ both arms "
                        + "typically short-circuit identically with "
                        + "totalAcceptedCount=0 / after==before. Act on it "
                        + "the SAME way as (h) — see archimate://prompts/"
                        + "routing-preconditions-checklist § \"When a "
                        + "spacing tool says the view needs a structural "
                        + "reflow\"). "
                        + "NOTE — in budget_exhausted_after_N_iterations, "
                        + "aggregate_threshold_regressed_at_iteration_N_"
                        + "reverted_to_iteration_M and iteration_apply_"
                        + "failed_at_iteration_N_reverted_after_M_accepted_"
                        + "iterations, N counts accepted COMMANDS, not "
                        + "iterations, despite the tokens' own nouns: an "
                        + "escalate iteration pushes TWO commands (a "
                        + "one-shot hub-resize plus that iteration's spacing "
                        + "command), so N exceeds the reported iteration "
                        + "count by the number of hub resizes. A "
                        + "termination reason of "
                        + "budget_exhausted_after_3_iterations beside an "
                        + "iteration count of 2 is consistent, not "
                        + "contradictory. Read the iteration-count field "
                        + "named above when you want iterations. "
                        + "The loop objective is the aggregate thresholds_met "
                        + "scalar ONLY (per-metric monotonicity rules are NOT "
                        + "used); escalate changes the step + target, not "
                        + "the objective. "
                        + "The iterationBudget parameter defaults to 8 (split 4+4 across "
                        + "arms by default; caller-tunable, [1, 20]). "
                        + "Use this composed tool when you want both element "
                        + "and inter-group spacing inflated in a single "
                        + "transactional call with knee-enforcement built "
                        + "in. For single-axis inflation without the knee "
                        + "guard (legacy behaviour), use the sibling tools "
                        + "apply-element-spacing-recommendations or "
                        + "apply-group-spacing-recommendations directly. For "
                        + "surgical edits to a specific element/group pair, "
                        + "use update-view-object directly. "
                        + "If assess-layout reports the "
                        + "vAxisParallelGapP10 metric (documented as "
                        + "parallelConnectionGap_V_p10) below its "
                        + "calibration-fair threshold (narrow-corridor "
                        + "regime), THIS TOOL CANNOT MITIGATE — the "
                        + "narrow-corridor floor is structural / algorithmic "
                        + "and convenience spacing surfaces cannot break it. "
                        + "In that case the only paths are (a) topology "
                        + "redesign (reduce hub fan-out / split the view) "
                        + "or (b) manual bendpoint surgery via "
                        + "update-view-connection. "
                        + "Returns before/after assess-layout snapshots in "
                        + "one envelope plus both deltas + clamp flags + "
                        + "connection counts + hub-detection. "
                        + RESIZED_ELEMENTS_RESPONSE_DOC
                        + "ORDERING: call this after auto-route-connections. Each arm scores its own internal "
                        + "reroute pass against the input it was handed before its loop starts; on connections "
                        + "carrying no stored routing the two sides are separated by a whole routing pass rather "
                        + "than a refinement of one, the pre-loop check returns reroute_degraded_input_baseline, "
                        + "and that arm's loop is never entered — no spacing is applied at all and the view comes "
                        + "back untouched. Routing first gives that check two routed states to compare. This is the "
                        + "default order, not a recovery step. "
                        + "Related: adjust-view-spacing (the underlying "
                        + "primitive — call directly when you want explicit "
                        + "deltas without the knee guard, including "
                        + "paddingDelta), apply-element-spacing-"
                        + "recommendations (sibling — single-arm element "
                        + "spacing without knee guard), "
                        + "apply-group-spacing-recommendations (sibling — "
                        + "single-arm inter-group spacing without knee "
                        + "guard), detect-hub-elements + update-view-object "
                        + "(hub-resize precondition), assess-layout "
                        + "(diagnose spacing issues first)."
                        + " Any archimate:// URI named here can also be read by calling get-guidance with that uri, for clients that do not expose MCP resources.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleApplySpacingRecommendations)
                .build();
    }

    McpSchema.CallToolResult handleApplySpacingRecommendations(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling apply-spacing-recommendations request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String scope = HandlerUtils.optionalStringParam(args, "scope");
            if (scope == null) scope = "both";
            boolean dryRun = HandlerUtils.optionalBooleanParam(args,
                    "dryRun", false);
            Integer elementTargetSpacing = HandlerUtils.optionalIntegerParam(
                    args, "elementTargetSpacing");
            Integer groupTargetSpacing = HandlerUtils.optionalIntegerParam(
                    args, "groupTargetSpacing");
            Integer iterationBudget = HandlerUtils.optionalIntegerParam(
                    args, "iterationBudget");

            MutationResult<ApplySpacingRecommendationsResultDto> result =
                    accessor.applySpacingRecommendations(sessionId, viewId,
                            scope, dryRun, elementTargetSpacing,
                            groupTargetSpacing, iterationBudget);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildApplySpacingRecommendationsNextSteps(result),
                    accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling "
                    + "apply-spacing-recommendations", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildApplySpacingRecommendationsNextSteps(
            MutationResult<ApplySpacingRecommendationsResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations",
                    SPACING_COMPARISON_UNAVAILABLE_QUEUED_STEP);
        }
        ApplySpacingRecommendationsResultDto dto = result.entity();
        List<String> steps = new ArrayList<>();
        if (dto.noChangeReason() != null) {
            steps.add("No change applied: " + dto.noChangeReason());
            return steps;
        }
        if (dto.dryRun()) {
            StringBuilder rec = new StringBuilder("Recommendation (scope=");
            rec.append(dto.scope()).append("): ");
            if (dto.interElementDelta() > 0) {
                rec.append("inflate element spacing by ")
                        .append(dto.interElementDelta())
                        .append("px (current ").append(dto.currentElementSpacingPx())
                        .append(" → target ").append(dto.elementTargetSpacingPx())
                        .append(")");
                if (dto.elementKneeClampApplied()) {
                    rec.append(" [clamped from proposed ")
                            .append(dto.proposedElementDelta()).append("px]");
                }
                rec.append("; ");
            }
            if (dto.interGroupDelta() > 0) {
                rec.append("widen inter-group corridors by ")
                        .append(dto.interGroupDelta())
                        .append("px (current ").append(dto.currentGroupSpacingPx())
                        .append(" → target ").append(dto.groupTargetSpacingPx())
                        .append(")");
                if (dto.groupKneeClampApplied()) {
                    rec.append(" [clamped from proposed ")
                            .append(dto.proposedGroupDelta()).append("px]");
                }
                rec.append("; ");
            }
            rec.append(dto.connectionCount()).append(" total connections / ")
                    .append(dto.interGroupConnectionCount())
                    .append(" inter-group.");
            steps.add(rec.toString());
            steps.add("Re-run with dryRun=false to apply.");
            return steps;
        }
        StringBuilder applied = new StringBuilder("Applied (scope=");
        applied.append(dto.scope()).append("): ");
        if (dto.interElementDelta() > 0) {
            applied.append("element +").append(dto.interElementDelta())
                    .append("px");
            if (dto.elementKneeClampApplied()) {
                applied.append(" (knee-clamped from +")
                        .append(dto.proposedElementDelta()).append(")");
            }
            applied.append("; ");
        }
        if (dto.interGroupDelta() > 0) {
            applied.append("inter-group +").append(dto.interGroupDelta())
                    .append("px");
            if (dto.groupKneeClampApplied()) {
                applied.append(" (knee-clamped from +")
                        .append(dto.proposedGroupDelta()).append(")");
            }
            applied.append("; ");
        }
        steps.add(applied.toString());
        if (hasSpacingRatingRegressedWarning(dto.structuredWarnings())) {
            steps.add(SPACING_RATING_REGRESSED_STEP);
        }
        if (dto.after() != null && dto.before() != null) {
            int beforeM4 = dto.before().connectionEdgeCoincidenceCount();
            int afterM4 = dto.after().connectionEdgeCoincidenceCount();
            int beforeCoinc = dto.before().coincidentSegmentCount();
            int afterCoinc = dto.after().coincidentSegmentCount();
            steps.add("M4 (edge-coincidence): " + beforeM4 + " → " + afterM4
                    + ". Coincident segments: " + beforeCoinc + " → "
                    + afterCoinc + ".");
            if (dto.elementKneeClampApplied() || dto.groupKneeClampApplied()) {
                steps.add("Inflation knee-clamp fired — at least one delta "
                        + "was capped to stay within the +80px element / "
                        + "+100px inter-group cumulative-from-current knee. "
                        + "Past-knee inflation regresses passThroughs / "
                        + "nonOrthogonalTerminals / xings-per-connection. If "
                        + "residual coincidence remains, prefer surgical "
                        + "update-view-object edits or algorithmic routing-"
                        + "pipeline successors over further inflation.");
            }
            if (dto.after().hubPortQualityScore() < HUB_PORT_QUALITY_REMEDY_THRESHOLD) {
                steps.add("hubPortQualityScore is "
                        + dto.after().hubPortQualityScore()
                        + " (below the good band at "
                        + HUB_PORT_QUALITY_REMEDY_THRESHOLD
                        + ") — hub elements may be undersized. Use "
                        + "detect-hub-elements + update-view-object to "
                        + "resize hubs before re-running this tool.");
            }
        }
        steps.add("Use assess-layout to re-verify quality. Use undo to roll "
                + "back if unsatisfactory.");
        return steps;
    }
}
