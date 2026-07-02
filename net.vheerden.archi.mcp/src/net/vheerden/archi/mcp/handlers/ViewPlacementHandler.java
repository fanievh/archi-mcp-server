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
import net.vheerden.archi.mcp.model.ElementSizer;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.ImageParams;
import net.vheerden.archi.mcp.model.StylingParams;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.ElementDto;
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
                    buildAddToViewNextSteps(result), accessor, formatter);

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

    private List<String> buildAddToViewNextSteps(MutationResult<AddToViewResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        String voId = result.entity().viewObject().viewObjectId();
        List<String> steps = new ArrayList<>();
        steps.add("Use get-view-contents to verify the element placement");
        boolean hadAutoConnections = result.entity().autoConnections() != null
                && !result.entity().autoConnections().isEmpty();
        if (hadAutoConnections) {
            steps.add(result.entity().autoConnections().size()
                    + " connection(s) were auto-created. Use auto-connect-view later "
                    + "if more elements are added to this view");
        } else {
            steps.add("Use auto-connect-view to batch-create connections for all existing "
                    + "relationships between elements on this view (recommended)");
        }
        steps.add("Use add-connection-to-view for individual connections "
                + "using sourceViewObjectId or targetViewObjectId '" + voId + "'");
        if (result.entity().skippedAutoConnections() != null
                && result.entity().skippedAutoConnections() > 0) {
            steps.add("Auto-connect capped at 50 connections. "
                    + result.entity().skippedAutoConnections()
                    + " additional relationship(s) exist — use add-connection-to-view manually.");
        }
        return steps;
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
                + "are automatically interpreted as their corresponding whitespace characters.");

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
                        + "Requires viewId and label. Optional: x, y (both or neither for auto-placement), "
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
            String label = HandlerUtils.requireStringParam(args, "label");
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
        heightProp.put("description", "Optional height (default: 80)");

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
                        + "Requires viewId and content. Use position='above-content' for title notes "
                        + "(recommended — automatically places above diagram content after layout). "
                        + "Optional: x, y (both or neither for auto-placement), width, height "
                        + "(default 185x80), "
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
            String content = HandlerUtils.requireStringParam(args, "content");
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
                + "returned by list-model-images. Format: 'images/<sha1>.png'. "
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
                        + "determined by the ArchiMate relationship type (per Archi 5.8). "
                        + "The relationship's elements must match the view objects' elements "
                        + "(either orientation). "
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
        heightProp.put("description", "New height (optional, keeps current if omitted)");

        Map<String, Object> textProp = new LinkedHashMap<>();
        textProp.put("type", "string");
        textProp.put("description",
                "New text for groups (label) or notes (content). "
                + "Only valid for group and note view objects — rejected for element view objects. "
                + "Common escape sequences (\\n, \\t, \\r, \\\\) are automatically interpreted "
                + "as their corresponding whitespace characters. "
                + "Omit to leave text unchanged.");

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
                        + "— custom image on element/group/note. "
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
            String text = HandlerUtils.optionalStringParam(args, "text");
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
                        + "by the ArchiMate relationship type (per Archi 5.8). "
                        + "Respects approval mode (human-gated in Archi). All changes execute as a single undo unit. "
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

    private List<String> buildUpdateViewConnectionNextSteps(
            MutationResult<ViewConnectionDto> result,
            List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            StylingParams styling,
            Boolean showLabel,
            Integer textPosition,
            boolean autoClearedBendpoints) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        // Detect what the caller actually changed and tailor the message.
        boolean bendpointsCleared = autoClearedBendpoints
                || (bendpoints != null && bendpoints.isEmpty())
                || (absoluteBendpoints != null && absoluteBendpoints.isEmpty());
        boolean bendpointsSet = (bendpoints != null && !bendpoints.isEmpty())
                || (absoluteBendpoints != null && !absoluteBendpoints.isEmpty());
        if (bendpointsCleared) {
            return List.of(
                    "Connection bendpoints cleared (straight line). Use get-view-contents to inspect.",
                    "Use update-view-connection to add bendpoints for routing.");
        }
        if (bendpointsSet) {
            return List.of(
                    "Connection bendpoints updated. Use get-view-contents to inspect.",
                    "Use update-view-connection with empty bendpoints array to straighten the connection.");
        }
        // Styling / showLabel / textPosition only (no bendpoint change).
        boolean anyStyling = styling != null && styling.hasAnyValue();
        if (anyStyling || showLabel != null || textPosition != null) {
            return List.of(
                    "Connection updated (bendpoints unchanged). Use get-view-contents to inspect.",
                    "Provide bendpoints or absoluteBendpoints to change routing.");
        }
        return List.of(
                "Connection updated. Use get-view-contents to inspect.",
                "Use update-view-connection to change bendpoints, styling, or label visibility.");
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
                    buildRemoveFromViewNextSteps(result), accessor, formatter);

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

    private List<String> buildRemoveFromViewNextSteps(
            MutationResult<RemoveFromViewResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        RemoveFromViewResultDto dto = result.entity();
        if ("viewObject".equals(dto.removedObjectType())) {
            int cascadeCount = dto.cascadeRemovedConnectionIds() != null
                    ? dto.cascadeRemovedConnectionIds().size() : 0;
            String connectionNote = cascadeCount > 0
                    ? " (" + cascadeCount + " connection"
                    + (cascadeCount > 1 ? "s" : "") + " also removed)"
                    : "";
            return List.of(
                    "Element removed from view" + connectionNote
                            + ". Underlying model element is unchanged.",
                    "Use get-view-contents to inspect the current view layout.",
                    "Use add-to-view to place the element back on the view.");
        }
        return List.of(
                "Connection removed from view. Underlying model relationship is unchanged.",
                "Use get-view-contents to inspect the current view layout.",
                "Use add-connection-to-view to add a connection back.");
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
                    buildClearViewNextSteps(result), accessor, formatter);

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

    private List<String> buildClearViewNextSteps(MutationResult<ClearViewResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        ClearViewResultDto dto = result.entity();
        String summary = "View cleared: " + dto.elementsRemoved() + " object(s) and "
                + dto.connectionsRemoved() + " connection(s) removed.";
        if (dto.nonArchimateObjectsRemoved() > 0) {
            summary += " (" + dto.nonArchimateObjectsRemoved()
                    + " non-ArchiMate object(s) such as Notes/Groups were also removed.)";
        }
        summary += " Underlying model objects are unchanged.";
        return List.of(
                summary,
                "Use get-view-contents to verify the view is empty.",
                "Use add-to-view to re-populate the view with elements.");
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
        posHeightProp.put("description", "New height (omit to keep current)");

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
                        + "validation, no changes are applied. SPECULATIVE EXECUTION: "
                        + "To preview layout quality, apply layout → assess-layout → "
                        + "undo if unsatisfied. No dry-run needed — undo is cheap and instant. "
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

            // Parse positions array
            List<ViewPositionSpec> positions = parsePositions(args);

            // Parse connections array
            List<ViewConnectionSpec> connections = parseConnections(args);

            MutationResult<ApplyViewLayoutResultDto> result =
                    accessor.applyViewLayout(sessionId, viewId, positions, connections, description);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildApplyViewLayoutNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling apply-positions", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<ViewPositionSpec> parsePositions(Map<String, Object> args) {
        Object value = args.get("positions");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        List<ViewPositionSpec> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) map;
                String viewObjectId = HandlerUtils.requireStringParam(entry, "viewObjectId");
                Integer x = HandlerUtils.optionalIntegerParam(entry, "x");
                Integer y = HandlerUtils.optionalIntegerParam(entry, "y");
                Integer width = HandlerUtils.optionalIntegerParam(entry, "width");
                Integer height = HandlerUtils.optionalIntegerParam(entry, "height");
                result.add(new ViewPositionSpec(viewObjectId, x, y, width, height));
            } else {
                throw new ModelAccessException(
                        "positions[" + i + "] must be an object with viewObjectId",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Each position entry must be an object with viewObjectId and "
                                + "at least one of x, y, width, height",
                        null);
            }
        }
        return result;
    }

    private List<ViewConnectionSpec> parseConnections(Map<String, Object> args) {
        Object value = args.get("connections");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        List<ViewConnectionSpec> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) map;
                String viewConnectionId = HandlerUtils.requireStringParam(entry, "viewConnectionId");
                List<BendpointDto> bendpoints = extractBendpoints(entry);
                List<AbsoluteBendpointDto> absoluteBendpoints = extractAbsoluteBendpoints(entry);
                validateBendpointFormats(bendpoints, absoluteBendpoints);

                // If neither provided, default to empty list (clear = straight line)
                if (bendpoints == null && absoluteBendpoints == null) {
                    bendpoints = List.of();
                }

                result.add(new ViewConnectionSpec(viewConnectionId, bendpoints, absoluteBendpoints));
            } else {
                throw new ModelAccessException(
                        "connections[" + i + "] must be an object with viewConnectionId",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Each connection entry must be an object with viewConnectionId and "
                                + "optional bendpoints or absoluteBendpoints",
                        null);
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
                + "IDs), boundaryViolations (child element IDs). Crossings excluded "
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
                + "elementCount, connectionCount, overlapCount, nonOrthogonalTerminalCount, "
                + "connectionPassThroughCount} — one cheap overview call instead of one full "
                + "payload per view. viewId is ignored when scope is 'all-views'.");

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
                        + "Also detects boundary violations (elements outside parent groups), "
                        + "connection pass-throughs (connections crossing unrelated elements), "
                        + "and off-canvas warnings. "
                        + "Use before and after any mutation to measure improvement. "
                        + "SPECULATIVE WORKFLOW: apply mutation → assess-layout → undo if "
                        + "unsatisfied → adjust parameters → retry. This is the recommended "
                        + "way to 'preview' layout or routing changes without needing a dry-run. "
                        + "Related: auto-layout-and-route (automatic ELK layout + routing), auto-route-connections "
                        + "(routing), adjust-view-spacing (inflate spacing and re-route in "
                        + "one call), undo (roll back if unsatisfied), get-view-contents "
                        + "(inspect elements), export-view (visual verification).\n\n"
                        + "Overlap metrics distinguish between `overlapCount` (sibling overlaps "
                        + "— genuine layout problems where unrelated elements overlap) and "
                        + "`containmentOverlaps` (expected overlaps from ancestor-descendant "
                        + "containment, e.g., elements inside groups). Only sibling overlaps "
                        + "affect the quality rating and trigger suggestions.\n\n"
                        + "The overall rating uses the two-dimensional M6 severity-tiered model "
                        + "(worse of a layout tier and a routing tier). Routing: Tier-1R (critical: "
                        + "passThroughs, interiorTerminations, zigzags, coincidentSegments) can produce "
                        + "'poor'; Tier-2R (cap 'fair': nonOrthogonalTerminals, nonOrthogonalInteriorSegments, "
                        + "connectionEdgeCoincidence, hubPortQuality, labelOverlaps, labelTruncations); "
                        + "Tier-3R (cap 'good': edgeCrossings, connectionThroughNote). Layout: Tier-1L "
                        + "(critical: overlaps, boundaryViolations, parentLabelObscured) can produce 'poor'; "
                        + "Tier-2L (cap 'fair': spacing, offCanvas, hubNeighbourCrowding); Tier-3L (cap "
                        + "'good': alignment). The `ratingBreakdown` map shows per-metric ratings.\n\n"
                        + "DE-NOISED HEADLINE: `ratingBreakdown` also carries "
                        + "`overallExcludingAcceptedCosmetics` — the same overall rating recomputed with "
                        + "the `nonOrthogonalTerminals` contribution removed. Diagonal terminal segments "
                        + "are the straight-line signature of ELK auto-layout and routinely push an "
                        + "otherwise-clean view to 'fair'. Compare the two: when `overallRating` is 'fair' "
                        + "but `overallExcludingAcceptedCosmetics` is 'good'/'excellent', the 'fair' is "
                        + "terminal cosmetics only (run auto-route-connections mode='terminals-only' to "
                        + "clear it, or accept it); when the two are EQUAL, the rating reflects a real "
                        + "routing/layout defect to fix. It is a floor, never a lift — it can only equal "
                        + "or improve `overallRating`, never worsen it.\n\n"
                        + "Edge crossing rating is lenient for grouped views: when groups with "
                        + "inter-group connections are present and crossings are the main issue "
                        + "(zero overlaps, good alignment, pass-throughs <= 3), crossings get a "
                        + "one-tier boost — cross-group edge crossings are topologically unavoidable.\n\n"
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
                        + "segments below 25 px gap (more = worse). Currently INFORMATIONAL "
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
                        + "Counted per connection×visual. Notes are excluded from the element "
                        + "pass-through scoring set, and for image-bearing elements this tests the "
                        + "rendered image RECTANGLE (which can overhang the box), so it catches "
                        + "clutter the box-based `connectionPassThroughs` (Tier-1R) misses; where a "
                        + "route could trip both, the routing tier takes the max so the Tier-1R "
                        + "pass-through dominates (no double penalty). A visual on a connection's own "
                        + "endpoint/container is not flagged. Reroute the connection or move the "
                        + "note/image clear.\n\n"
                        + "INFORMATIONAL DETECTIONS (no rating impact): "
                        + "`imageSiblingOverlapCount` / `imageSiblingOverlapDescriptions` — elements "
                        + "whose image area (custom image or specialization icon, sized from its true "
                        + "rendered dimensions) is overlapped by a sibling element. "
                        + "Increase element spacing, reposition the image, or shrink the icon. "
                        + "`noteClipCount` / `noteClipDescriptions` — notes whose text content needs "
                        + "more height than their box provides (clipped). Omit the note `height` so "
                        + "the server auto-fits it, or increase the height / reduce the font size. "
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
                        + "auto-route-connections. "
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
                        + "connection (a route hugging at either terminal counts once), informational (no "
                        + "rating impact — distinct from the rating-bearing `nonOrthogonalTerminalCount`). "
                        + "Push the first segment perpendicular off the face before turning. "
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
                        + "on a visual Group's TITLE BAND (the title collision the label-overlap detector "
                        + "cannot see, since it skips groups wholesale as transparent containers). Only "
                        + "the group's top title strip is tested, so a label sitting inside the group "
                        + "body is normal and NOT flagged. Counted per label×group. Informational (no "
                        + "rating impact). Reposition the label or reroute the connection clear of the "
                        + "group title.\n\n"
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
                        + "IDs), nonOrthogonalTerminals (connection IDs), boundaryViolations "
                        + "(child element IDs), interiorTerminations (connection IDs), "
                        + "zigzags (connection IDs), edgeCoincidence (connection IDs), "
                        + "edgeCoincidenceGrazedElements (the element IDs every edge-coincident "
                        + "route hugs — the full breadth of each graze), "
                        + "redundantBendpoints (connection IDs), "
                        + "nonOrthogonalInteriorSegments (connection IDs), "
                        + "containerFillRecession (container element/group IDs), "
                        + "labelOnNote (note IDs carrying a connection label), "
                        + "labelOnGroup (group IDs whose title band carries a connection label), "
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
                        + "is keyed by dimension id and always present on a normal assessment "
                        + "(an empty map only appears on degenerate empty/single-element "
                        + "views). It is informational only and never affects any rating. A "
                        + "done-gate must read BOTH `coverage` and `ratingBreakdown`: a "
                        + "dimension is only 'clean' when coverage==checked AND breakdown==pass. "
                        + "A `partial` dimension is NOT certifiable as clean from the metric "
                        + "alone — render-verify its uncovered modes. Most defect-class dimensions "
                        + "report `checked`; two exceptions: `labelOverlaps` downgrades to `partial` "
                        + "on a run carrying a connection label wider than its hosting segment "
                        + "(checked otherwise) — such a label can crowd a neighbour while clearing it "
                        + "geometrically, so an overlap count of zero cannot certify that crowding "
                        + "mode clean; and `corridorCentering` is always `not-checked` — no detector "
                        + "measures whether a single route sits centred in its corridor versus hugs "
                        + "an edge (the `corridorUtilisationScore` metric only measures multi-occupant "
                        + "spread), so render-verify centring regardless of that score. The other "
                        + "levels remain defined for dimensions added later.\n\n"
                        + "SCOPE: the `scope` parameter selects single-view (default) or whole-model "
                        + "assessment. With scope='all-views', viewId is ignored and the result is a "
                        + "compact map keyed by view id — each value {name, overallRating, "
                        + "overallExcludingAcceptedCosmetics, elementCount, connectionCount, "
                        + "overlapCount, nonOrthogonalTerminalCount, connectionPassThroughCount} — for a "
                        + "one-call overview of every diagram (e.g. a final close-out sweep). It omits "
                        + "violatorIds, descriptions, and the per-metric breakdown; drill into any view "
                        + "that rates 'fair'/'poor' with a single-scope call for the full assessment. An "
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
            summary.put("nonOrthogonalTerminalCount", dto.nonOrthogonalTerminalCount());
            summary.put("connectionPassThroughCount",
                    dto.connectionPassThroughs() != null ? dto.connectionPassThroughs().size() : 0);
            perView.put(view.id(), summary);
        }

        List<String> nextSteps = perView.isEmpty()
                ? List.of("No diagram views in the model.")
                : List.of(
                        "Drill into any view whose overallRating is 'fair'/'poor' with a "
                                + "single-scope assess-layout call (scope omitted, viewId set) for "
                                + "the full per-metric breakdown and violator ids.",
                        "A view whose overallRating is 'fair' but "
                                + "overallExcludingAcceptedCosmetics is 'good'/'excellent' is "
                                + "terminal-cosmetic-only — run auto-route-connections "
                                + "mode='terminals-only' to clear it, or accept it.");
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

    // Package-private for direct unit testing (Assessor.Redesign code-review H1, 2026-04-27).
    List<String> buildAssessLayoutNextSteps(AssessLayoutResultDto dto) {
        List<String> steps = new ArrayList<>();
        String rating = dto.overallRating();
        boolean hasGroups = dto.hasGroups();
        boolean hasConnections = dto.connectionCount() > 0;
        int passThroughCount = dto.connectionPassThroughs() != null
                ? dto.connectionPassThroughs().size() : 0;
        // Assessor.Redesign M6: M2-M5 metrics (interior, zigzag, edge-coincidence, hub-port
        // quality) can drive a view to fair/poor without any crossings or PTs. Treat any
        // non-zero M2-M5 signal as a routing issue so next-steps route to auto-route-connections
        // rather than to auto-layout-and-route (which re-positions elements unnecessarily).
        boolean hasPerceptionRoutingDefect = dto.zigzagCount() > 0
                || dto.interiorTerminationCount() > 0
                || dto.connectionEdgeCoincidenceCount() > 0
                || dto.hubPortQualityScore() < HUB_PORT_QUALITY_NEXTSTEPS_THRESHOLD
                || dto.coincidentSegmentCount() > 0
                || dto.nonOrthogonalTerminalCount() > 0;
        boolean hasRoutingIssues = hasConnections
                && (dto.edgeCrossingCount() > 0 || passThroughCount > 0
                        || hasPerceptionRoutingDefect);
        boolean passThroughDominated = passThroughCount >= 3;

        // Orphaned connection guidance — always first when present (unchanged)
        if (dto.orphanedConnections() > 0) {
            steps.add("Found " + dto.orphanedConnections()
                    + " orphaned connection(s) referencing missing view objects."
                    + " Use clear-view to rebuild the view cleanly.");
        }

        // Precondition-class nextSteps wired
        // to remediation tools by name with violator IDs. Surfaced BEFORE the rating-switch
        // advice so an LLM agent acts on hub/spacing preconditions first.

        // #1: Hub-port quality below 0.5 → name detect-hub-elements + violator hub IDs.
        // Violator IDs require includeViolatorIds=true at the call site (LayoutQualityAssessor
        // populates hubPortQualityFaces only when includeViolatorIds=true to save allocation in
        // the default path). When IDs are unavailable, point the agent at includeViolatorIds=true
        // for the IDs — the prose guidance still surfaces independently.
        double hpq = dto.hubPortQualityScore();
        if (hpq < 0.5) {
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
                    "Hub-port quality %.2f (below 0.5) — hubs may be undersized for "
                    + "connection fan-out. Run detect-hub-elements%s, then update-view-object "
                    + "to size each hub for its connection count "
                    + "(formula: dimension = 55 + 15 × (count − 6)). Re-run layout-within-group "
                    + "on affected groups, then auto-route-connections.",
                    hpq, violatorClause));
        }

        // #1b: Saturated container-nested-hub layout — supersedes the generic spacing step (#2)
        // below. When corridors are near-saturated AND there is edge-coincidence/coincident-segment
        // pressure AND hub-port quality was NOT already flagged by #1 (hpq>=0.5), a hub-resize OR a
        // reposition (ELK) is the right lever — but which one depends on per-element density the
        // emitter cannot see, and it cannot even confirm a hub exists (no hub count; hubPortQualityFaces
        // is null without includeViolatorIds; hpq defaults to neutral). So the step is DIAGNOSTIC:
        // it points at detect-hub-elements and presents both levers + the render-authoritative caveat,
        // conditioned on a hub actually being present (correct even on a hubless saturated view).
        boolean saturatedNestedHub =
                dto.corridorUtilisationScore() >= NESTED_HUB_CORRIDOR_SATURATION_THRESHOLD
                && (dto.connectionEdgeCoincidenceCount() > 4 || dto.coincidentSegmentCount() > 2)
                && hpq >= 0.5;
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
                    + "16-30 → 80/100; 30+ → 100/120.");
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
            int violationCount = dto.boundaryViolations().size();
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
                    + "parent group's bounds%s. Composite recovery: "
                    + "(1) use update-view-object to resize the affected parent group(s) to enclose "
                    + "all child elements, "
                    + "(2) re-run layout-within-group on the resized parent group(s) to re-position "
                    + "siblings, "
                    + "(3) re-run auto-route-connections to refresh routes after the layout changes. "
                    + "This sequence is undoable as multiple steps; if a single-undo composition is "
                    + "needed, the convenience tool covering this case is queued for a future release "
                    + "(Row F — apply-spacing-recommendations / apply-hub-sizing-recommendations).",
                    violationCount, violatorClause));
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
                        steps.add("Found " + passThroughCount + " pass-through(s)"
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
                if (passThroughDominated) {
                    steps.add("Found " + passThroughCount + " pass-through(s)"
                            + " — try auto-route-connections first to re-route"
                            + " connections around elements.");
                    steps.add("If pass-throughs persist, use auto-layout-and-route"
                            + " (ELK) with targetRating for automated quality iteration.");
                } else if (dto.overlapCount() == 0 && hasPerceptionRoutingDefect) {
                    // M6: routing-only poor (M2/M3/M4/M5 dominated) — try re-routing
                    // before falling back to ELK layout, which would unnecessarily
                    // reposition elements that are already well-placed.
                    steps.add("Use auto-route-connections to re-route connections"
                            + " — element positions are clean; the routing defects"
                            + " (zigzags / interior terminations / edge-coincidence /"
                            + " hub-port distribution) are the rating-driver.");
                    steps.add("If routing alone doesn't clear the defects, use"
                            + " auto-layout-and-route (ELK) with targetRating for"
                            + " automated quality iteration.");
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
                + "Up to 2 nudge iterations are attempted.");

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
                + "terminals-only is mutually exclusive with strategy='clear' and autoNudge=true.");

        Map<String, Object> enableChannelNudgingProp = new LinkedHashMap<>();
        enableChannelNudgingProp.put("type", "boolean");
        enableChannelNudgingProp.put("description",
                "When true (default), routes are post-processed by a channel-global "
                + "ordered nudging pass that centres single-occupant routes in their "
                + "corridors and fans out parallel runs sharing a corridor. Set false "
                + "to disable channel nudging and reproduce the pre-nudging routing "
                + "output (useful for before/after comparison).");
        enableChannelNudgingProp.put("default", true);

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
                        + "100px+ for dense layouts. ITERATIVE WORKFLOW: route → assess-layout "
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
                        + "terminals-only is mutually exclusive with strategy='clear' and "
                        + "autoNudge=true. It also sweeps redundant INTERIOR collinear "
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
                        + "naming the offending sibling pair. A second code, "
                        + "EGRESS_LIFT_LAYOUT_BOUND, is emitted when the router generated one or "
                        + "more off-face terminal egress lifts but rolled them back because "
                        + "applying them would narrow a parallel-connection gap below the 15px "
                        + "healthy floor — i.e. the residual off-face hug is layout-bound, not a "
                        + "routing bug, so it names spreading the elements (a matching layout-bound "
                        + "nextSteps entry accompanies it) rather than declining silently. "
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
                        + "sibling overlap is resolved.")
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

            MutationResult<AutoRouteResultDto> result =
                    accessor.autoRouteConnections(sessionId, viewId, connectionIds, strategy,
                            force, autoNudge, snapThreshold, perimeterMargin, mode,
                            enableChannelNudging);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAutoRouteNextSteps(result), accessor, formatter);

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
     * nextSteps guidance for a layout-bound off-face egress-lift decline. The router deliberately
     * kept the hug(s) because clearing them would narrow a parallel-connection gap below its healthy
     * floor — a routing re-run cannot help; the corridor must be widened.
     */
    private static final String EGRESS_LIFT_LAYOUT_BOUND_STEP =
            "One or more off-face terminal hugs were left in place because clearing them would narrow "
                    + "a parallel-connection gap below the 15px healthy floor — this is layout-bound. "
                    + "Increase element spacing in the affected corridor (e.g. run "
                    + "apply-spacing-recommendations), then re-route; re-routing alone will not clear "
                    + "them. See the structuredWarnings entry (EGRESS_LIFT_LAYOUT_BOUND) for details.";

    /** True when the auto-route result carries the layout-bound egress-lift structured warning. */
    private boolean hasEgressLiftLayoutBoundWarning(AutoRouteResultDto entity) {
        return entity != null && entity.structuredWarnings() != null
                && entity.structuredWarnings().stream().anyMatch(
                        w -> StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND.equals(w.code()));
    }

    private List<String> buildAutoRouteNextSteps(
            MutationResult<AutoRouteResultDto> result) {
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>();
            if (result.entity() != null && result.entity().routerTypeSwitched()) {
                batchSteps.add("View router type will be switched from manhattan "
                        + "to manual (bendpoint mode) when batch is committed.");
            }
            if (result.entity() != null && result.entity().warnings() != null
                    && !result.entity().warnings().isEmpty()) {
                batchSteps.add("Some connection IDs were not found — check the warnings "
                        + "array for details.");
            }
            if (hasEgressLiftLayoutBoundWarning(result.entity())) {
                batchSteps.add(EGRESS_LIFT_LAYOUT_BOUND_STEP);
            }
            batchSteps.add("Mutation queued as operation #"
                    + result.batchSequenceNumber() + " in current batch");
            batchSteps.add("Use get-batch-status to check batch progress");
            batchSteps.add("Use end-batch to commit all queued mutations");
            return batchSteps;
        }
        List<String> steps = new ArrayList<>();
        if (result.entity() != null && result.entity().routerTypeSwitched()) {
            steps.add("View router type switched from manhattan to manual "
                    + "(bendpoint mode) so that computed obstacle-aware paths "
                    + "are rendered correctly.");
        }
        if (result.entity() != null && result.entity().warnings() != null
                && !result.entity().warnings().isEmpty()) {
            steps.add("Some connection IDs were not found — check the warnings "
                    + "array for details.");
        }
        if (hasEgressLiftLayoutBoundWarning(result.entity())) {
            steps.add(EGRESS_LIFT_LAYOUT_BOUND_STEP);
        }
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
            steps.add(result.entity().resizedGroups().size()
                    + " group(s) were auto-resized to contain nudged elements. "
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
                        + "Optional: showLabel (false to suppress labels on all created connections). "
                        + "Optional: lineColor (#RRGGBB hex, empty string clears), "
                        + "fontColor (#RRGGBB hex, empty string clears), "
                        + "lineWidth (1-3) — applied to all created connections. "
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
            Boolean showLabel = (args.get("showLabel") instanceof Boolean b) ? b : null;
            StylingParams styling = extractStylingParams(args);

            MutationResult<AutoConnectResultDto> result =
                    accessor.autoConnectView(sessionId, viewId, elementIds,
                            relationshipTypes, showLabel, styling);

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
                + "For grid arrangement, uses the widest auto-computed width as "
                + "uniform column width.");

        Map<String, Object> columnsProp = new LinkedHashMap<>();
        columnsProp.put("type", "integer");
        columnsProp.put("description",
                "Number of columns for grid arrangement (default: auto-detected "
                + "from group width). Capped at element count. Only used with "
                + "arrangement: 'grid'.");

        Map<String, Object> recursiveProp = new LinkedHashMap<>();
        recursiveProp.put("type", "boolean");
        recursiveProp.put("description",
                "When true and autoResize is true, recursively resize ancestor "
                + "groups to fit their children (default: false). Propagates "
                + "sizing upward through the nesting hierarchy.");

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
                        + "Only repositions direct children of the specified group (not "
                        + "recursive into sub-groups). Use 'columns' to control grid shape "
                        + "and 'recursive' with 'autoResize' to propagate sizing to parent "
                        + "groups automatically. SPECULATIVE EXECUTION: To preview "
                        + "arrangement quality, apply layout → assess-layout → undo if "
                        + "unsatisfied (e.g., try different spacing or arrangement, then "
                        + "undo and retry). Related: add-group-to-view (create groups), "
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

            MutationResult<LayoutWithinGroupResultDto> result =
                    accessor.layoutWithinGroup(sessionId, viewId, groupViewObjectId,
                            arrangement, spacing, padding, elementWidth, elementHeight,
                            autoResize, autoWidth, columns, recursive);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildLayoutWithinGroupNextSteps(result), accessor, formatter);

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

    private List<String> buildLayoutWithinGroupNextSteps(
            MutationResult<LayoutWithinGroupResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        List<String> steps = new java.util.ArrayList<>(List.of(
                "Use export-view to visually verify the group layout.",
                "Use assess-layout to evaluate overall layout quality.",
                "Use auto-route-connections if connections need orthogonal routing."));
        if (result.entity() != null && result.entity().overflow()) {
            steps.add(0, "WARNING: Children overflow the group bounds. "
                    + "Use autoResize: true or manually resize the group.");
        }
        return steps;
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
                + "Requires the view to have groups with children.");

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
                "Optional quality target. When specified, the tool iterates "
                + "with increasing spacing (up to 5 attempts) until assess-layout "
                + "reports the target rating or better. Returns the best result "
                + "achieved. Eliminates the need for manual assess → adjust → "
                + "re-layout loops. Works in both auto and grouped modes. "
                + "'poor' and 'not-applicable' are not valid targets.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("mode", modeProp);
        properties.put("direction", directionProp);
        properties.put("spacing", spacingProp);
        properties.put("targetRating", targetRatingProp);

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
                        + "views with ArchiMate groups (layered architecture, "
                        + "producer-consumer, etc.). Requires view to have groups. "
                        + "IMPORTANT: Use auto-route-connections instead if you "
                        + "want to preserve existing element positions and only "
                        + "compute connection routes. "
                        + "For flat views, consider layout-flat-view first — it "
                        + "offers sortBy and categoryField for organized placement. "
                        + "Automatically switches to manual (bendpoint) connection "
                        + "router mode. Supports batch and approval modes. "
                        + "Use targetRating to automate quality iteration — "
                        + "iterates with increasing spacing (up to 5 attempts) "
                        + "until the target rating is achieved. "
                        + "PRECONDITION CHECKLIST: fetch "
                        + "archimate://prompts/routing-preconditions-checklist "
                        + "before invoking this tool on any non-trivial view "
                        + "— the routing pipeline cannot recover from missing "
                        + "preconditions (hub sizing, inter-element spacing, "
                        + "inter-group spacing). "
                        + "See archimate-view-patterns resource for guidance on "
                        + "which mode to use.")
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

            MutationResult<AutoLayoutAndRouteResultDto> result =
                    accessor.autoLayoutAndRoute(sessionId, viewId, mode, direction,
                            spacing, targetRating);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildAutoLayoutAndRouteNextSteps(result), accessor, formatter);

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

    private List<String> buildAutoLayoutAndRouteNextSteps(
            MutationResult<AutoLayoutAndRouteResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #"
                            + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        List<String> steps = new ArrayList<>();
        AutoLayoutAndRouteResultDto dto = result.entity();
        boolean isGroupedMode = dto != null && "grouped".equals(dto.mode());

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
                steps.add("Target rating '" + dto.targetRating()
                        + "' not achieved — achieved '" + dto.achievedRating()
                        + "' after " + dto.iterationsPerformed()
                        + " iterations. Consider increasing spacing manually.");
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
                "Optional list of specific group view object IDs to arrange. "
                + "If omitted, all top-level groups in the view are arranged. "
                + "Non-listed groups remain in their current positions.");

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
                .description("[Mutation] Positions top-level groups and qualifying standalone elements relative to each other "
                        + "in a grid, row, or column layout. "
                        + "Use AFTER creating and populating groups with elements (via add-group-to-view + "
                        + "add-to-view), BEFORE routing connections.\n\n"
                        + "**Recommended workflow for grouped views:**\n"
                        + "1. Create groups and add elements to them\n"
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
                        + "that connects to elements in ≥ 2 of the arranged target groups is auto-placed "
                        + "in a reserved inter-group lane between its connected groups (centred "
                        + "vertically + horizontally). "
                        + "This matches the recipe topology promise (`archimate://recipes/application-integration` "
                        + "hub-and-spoke + `archimate://recipes/technology-deployment` zones-with-Path). "
                        + "If no qualifier exists, output is unchanged from direct row/column/grid behaviour. "
                        + "The qualifier predicate is automatic — there is no opt-in parameter. The "
                        + "`groupIds` parameter still constrains the arranged set; qualifier qualification "
                        + "is computed against the constrained set.\n\n"
                        + "**Related:**\n"
                        + "- `apply-group-spacing-recommendations` is the explicit-opt-in convenience-tool "
                        + "surface for the same heuristic; useful when you want a `dryRun` preview, "
                        + "post-routing application, or the full before/after metrics envelope.\n"
                        + "- `adjust-view-spacing` is for inflating spacing on an EXISTING layout without "
                        + "re-positioning groups.")
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
                        + "Works on ALL top-level groups in the view simultaneously. "
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
                        + "group-on-group overlaps. Typical workflow: add elements → "
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
                    buildOptimizeGroupOrderNextSteps(result), accessor, formatter);

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

    private List<String> buildOptimizeGroupOrderNextSteps(
            MutationResult<OptimizeGroupOrderResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        OptimizeGroupOrderResultDto dto = result.entity();
        List<String> steps = new ArrayList<>();
        if (dto != null && dto.crossingsBefore() > 0 && dto.crossingsAfter() < dto.crossingsBefore()) {
            steps.add("Crossings reduced from " + dto.crossingsBefore()
                    + " to " + dto.crossingsAfter()
                    + " (" + dto.reductionPercent() + "% reduction).");
        } else if (dto != null && dto.groupsOptimized() == 0) {
            steps.add("Element order is already optimal — no reordering was needed.");
        }
        steps.add("Use auto-route-connections to compute orthogonal paths for inter-group connections.");
        steps.add("Use assess-layout to evaluate final crossing count and layout quality.");
        if (dto != null && dto.groupsOptimized() > 0) {
            steps.add("Notes inside groups may need manual repositioning after element reordering.");
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
                + "implements ITextAlignment). Omit to leave the per-type default unchanged "
                + "(centre — Archi's default). Example: 'left' to left-align a group label.");

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

        // lineStyle on view objects (empirical correction —
        // Archi's lineStyle property is view-object only, not a connection property).
        Map<String, Object> lineStyleProp = new LinkedHashMap<>();
        lineStyleProp.put("type", "string");
        lineStyleProp.put("enum", java.util.List.of("solid", "dashed", "dotted", "none"));
        lineStyleProp.put("description",
                "View-object outline (border) line style. Values: 'solid' (Archi default), "
                + "'dashed', 'dotted', or 'none' (no visible outline). Applies to view objects "
                + "(elements, groups, notes) — silently ignored on connections (connection styling "
                + "is determined by the ArchiMate relationship type). Empty string clears to default. "
                + "Omit to leave unchanged. Example: 'dashed'.");

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
        // Connection-only line style; allowEmpty for the "clear to solid" symmetry on connections.
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
                        + "Set wrapFit=true for compact WRAP-FIT sizing (keep width, grow height so the "
                        + "label wraps to a 2nd line) — use for embedded elements in a dense grid where "
                        + "single-line widening would shift neighbours; preserves the grid's horizontal pitch. "
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
                        + "recursive=false to inflate only top-level groups (default "
                        + "true inflates nested subgroups too). The entire operation "
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
                        + "Related: assess-layout (diagnose spacing issues), "
                        + "optimize-group-order (reorder elements to reduce crossings), "
                        + "auto-route-connections (route-only without spacing change), "
                        + "apply-element-spacing-recommendations (the explicit-opt-in "
                        + "convenience-tool surface for the same heuristic; useful when "
                        + "you want a `dryRun` preview or the full before/after envelope).")
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

    private List<String> buildAdjustViewSpacingNextSteps(
            MutationResult<AdjustViewSpacingResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        AdjustViewSpacingResultDto dto = result.entity();
        List<String> steps = new ArrayList<>();
        steps.add("Use assess-layout to verify the quality improvement.");
        if (dto.coincidentSegmentCount() > 0) {
            steps.add("Coincident segments remain — try a larger interElementDelta.");
        }
        steps.add("Use undo to roll back if the result is unsatisfactory.");
        return steps;
    }

    // ---- apply-element-spacing-recommendations
    //      (RoutingPreconditions.InterElement) ----

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
                + "the response DTO names which of the six in-loop branches "
                + "fired (goal_reached / budget_exhausted / aggregate_threshold_"
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
                        + "(see densityFloorDiagnosis below). A degrading "
                        + "step is always reverted; the loop never presents "
                        + "a silently-degraded view. "
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
                        + "met); (b) budget_exhausted_after_N_iterations "
                        + "(iterationBudget cap hit, last accepted step "
                        + "commits); (c) aggregate_threshold_regressed_at_"
                        + "iteration_N_reverted_to_iteration_M (back-off "
                        + "fired, last accepted step commits); "
                        + "(d) structural_no_change_<reason> (no groups / "
                        + "no groups with 2+ children / no connections); "
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
                        + "no mutation, no view damage. NOT evidence that "
                        + "the prescribed spacing-then-route order is wrong; "
                        + "see archimate://prompts/routing-preconditions-"
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
                        + "The loop objective is the aggregate thresholds_met "
                        + "scalar ONLY (per-metric monotonicity rules are NOT "
                        + "used; they spuriously stop on net-positive "
                        + "mutations); escalate changes the step + target, "
                        + "not the objective. "
                        + "iterationBudget defaults to 5 (caller-tunable, "
                        + "[1, 20]); appliedDeltas[] reports each accepted "
                        + "iteration's spacing step in pixels. "
                        + "Set dryRun=true to preview the recommendation "
                        + "without mutation; default false runs the loop. "
                        + "Returns before/after assess-layout snapshots in "
                        + "one envelope so the visual-quality impact is "
                        + "visible immediately. Use after assess-layout "
                        + "reports M4 > 4 OR coincidentSegments > 2 on a "
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
                        + "LLM-facing precondition playbook.")
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
                    "Use end-batch to commit all queued mutations");
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
            if (dto.after().hubPortQualityScore() < 0.5) {
                steps.add("hubPortQualityScore is "
                        + dto.after().hubPortQualityScore()
                        + " (< 0.5) — hub elements may be undersized. Use "
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
                + "names which of the six in-loop branches fired "
                + "(goal_reached / budget_exhausted / "
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
                        + "reflow-required diagnosis). A degrading step is "
                        + "always reverted; never a silently-degraded view. "
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
                        + "met); (b) budget_exhausted_after_N_iterations "
                        + "(iterationBudget cap hit, last accepted step "
                        + "commits); (c) aggregate_threshold_regressed_at_"
                        + "iteration_N_reverted_to_iteration_M (back-off "
                        + "fired, last accepted step commits); "
                        + "(d) structural_no_change_<reason> (no groups / "
                        + "fewer than 2 top-level groups / no inter-group "
                        + "connections); (e) heuristic_already_met_no_change "
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
                        + "no mutation, no view damage. NOT evidence that "
                        + "the prescribed spacing-then-route order is wrong; "
                        + "see archimate://prompts/routing-preconditions-"
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
                        + "The loop objective is the aggregate thresholds_met "
                        + "scalar ONLY (per-metric monotonicity rules are NOT "
                        + "used; they spuriously stop on net-positive "
                        + "mutations); escalate changes the step + target, "
                        + "not the objective. "
                        + "iterationBudget defaults to 5 (caller-tunable, "
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
                        + "Use after assess-layout reports M4 > 4 OR "
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
                        + "topology re-layout primitives).")
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
                    "Use end-batch to commit all queued mutations");
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
        if (dto.after() != null && dto.before() != null) {
            int beforeM4 = dto.before().connectionEdgeCoincidenceCount();
            int afterM4 = dto.after().connectionEdgeCoincidenceCount();
            int beforeCoinc = dto.before().coincidentSegmentCount();
            int afterCoinc = dto.after().coincidentSegmentCount();
            steps.add("M4 (edge-coincidence): " + beforeM4 + " → " + afterM4
                    + ". Coincident segments: " + beforeCoinc + " → "
                    + afterCoinc + ".");
            if (dto.after().hubPortQualityScore() < 0.5) {
                steps.add("hubPortQualityScore is "
                        + dto.after().hubPortQualityScore()
                        + " (< 0.5) — hub elements may be undersized. Use "
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
                + "groupTerminationReason) and name which of the six "
                + "in-loop branches fired on that arm (goal_reached / "
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
                        + "actionable reflow-required diagnosis). A degrading "
                        + "step is always reverted; never a silently-"
                        + "degraded view. Single tool call = single "
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
                        + "(a) goal_reached_at_iteration_N; "
                        + "(b) budget_exhausted_after_N_iterations; "
                        + "(c) aggregate_threshold_regressed_at_iteration_N_"
                        + "reverted_to_iteration_M; "
                        + "(d) structural_no_change_<reason>; "
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
                        + "carries the per-arm reasons distinctly. NOT "
                        + "evidence that the prescribed spacing-then-route "
                        + "order is wrong; see archimate://prompts/routing-"
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
                        + "The loop objective is the aggregate thresholds_met "
                        + "scalar ONLY (per-metric monotonicity rules are NOT "
                        + "used); escalate changes the step + target, not "
                        + "the objective. "
                        + "iterationBudget defaults to 8 (split 4+4 across "
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
                        + "parallelConnectionGap_V_p10 metric below its "
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
                        + "(diagnose spacing issues first).")
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
                    "Use end-batch to commit all queued mutations");
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
            if (dto.after().hubPortQualityScore() < 0.5) {
                steps.add("hubPortQualityScore is "
                        + dto.after().hubPortQualityScore()
                        + " (< 0.5) — hub elements may be undersized. Use "
                        + "detect-hub-elements + update-view-object to "
                        + "resize hubs before re-running this tool.");
            }
        }
        steps.add("Use assess-layout to re-verify quality. Use undo to roll "
                + "back if unsatisfactory.");
        return steps;
    }
}
