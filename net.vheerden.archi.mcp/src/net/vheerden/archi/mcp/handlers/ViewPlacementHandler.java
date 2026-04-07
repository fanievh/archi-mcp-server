package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
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
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.DetectHubElementsResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutFlatViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.ResizeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionSpec;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for view placement and editing tools (Stories 7-7, 7-8, 8-0c, 8-6, 9-0a, 9-2, 9-5, 9-6, 10-29, 11-20, 13-6):
 * add-to-view, add-group-to-view, add-note-to-view, add-connection-to-view,
 * update-view-object, update-view-connection, remove-from-view, clear-view,
 * apply-positions, compute-layout, assess-layout, auto-route-connections,
 * auto-connect-view, layout-within-group, auto-layout-and-route, arrange-groups,
 * optimize-group-order, detect-hub-elements, layout-flat-view.
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
     * add-connection-to-view, update-view-object, update-view-connection,
     * remove-from-view, clear-view, apply-positions, compute-layout,
     * assess-layout, auto-route-connections, auto-connect-view,
     * layout-within-group, auto-layout-and-route, arrange-groups,
     * optimize-group-order, detect-hub-elements,
     * layout-flat-view.
     */
    public void registerTools() {
        registry.registerTool(buildAddToViewSpec());
        registry.registerTool(buildAddGroupToViewSpec());
        registry.registerTool(buildAddNoteToViewSpec());
        registry.registerTool(buildAddConnectionToViewSpec());
        registry.registerTool(buildUpdateViewObjectSpec());
        registry.registerTool(buildUpdateViewConnectionSpec());
        registry.registerTool(buildRemoveFromViewSpec());
        registry.registerTool(buildClearViewSpec());
        registry.registerTool(buildApplyViewLayoutSpec());
        registry.registerTool(buildLayoutViewSpec());
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
                        + "autoConnect (auto-create connections to elements already on the view). "
                        + "Related: get-view-contents (inspect view), get-views (list views), "
                        + "auto-connect-view (batch connections), "
                        + "add-connection-to-view (individual connections), create-view (create new view).")
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

    // ---- add-group-to-view (Story 8-6) ----

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

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("label", labelProp);
        properties.put("x", xProp);
        properties.put("y", yProp);
        properties.put("width", widthProp);
        properties.put("height", heightProp);
        properties.put("parentViewObjectId", parentVoProp);
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
                        + "width, height (default 300x200). "
                        + "NOTE: Groups constrain element positioning and reduce connection "
                        + "routing quality. Prefer groups on structure/overview views only. "
                        + "For views needing clean routed connections, use flat layout without groups. "
                        + "Related: add-to-view (place elements inside group), "
                        + "get-view-contents (inspect view groups), "
                        + "update-view-object (resize/relabel group).")
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

    // ---- add-note-to-view (Story 8-6) ----

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
                        + "(default 185x80). "
                        + "Related: get-view-contents (inspect view notes), "
                        + "update-view-object (edit note text or resize).")
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
                        + "The relationship's elements must match the view objects' elements "
                        + "(either orientation). "
                        + "Related: add-to-view (place elements first), "
                        + "get-view-contents (find view object IDs in visualMetadata), "
                        + "get-relationships (find relationship IDs).")
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

    // ---- update-view-object (Story 7-8) ----

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

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewObjectId", viewObjectIdProp);
        properties.put("x", xProp);
        properties.put("y", yProp);
        properties.put("width", widthProp);
        properties.put("height", heightProp);
        properties.put("text", textProp);
        addStylingProperties(properties);
        addImageProperties(properties);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewObjectId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update-view-object")
                .description("[Mutation] Update the visual position, size, styling, and/or image of an element "
                        + "on a view. Only provided fields are modified; unspecified fields remain "
                        + "unchanged. The underlying model element is not affected — only the visual "
                        + "representation on the diagram changes. At least one of x, y, width, height, "
                        + "text, styling, or image parameter must be provided. Required: viewObjectId (string) — "
                        + "the view object ID (from get-view-contents visualMetadata or groups/notes). "
                        + "Optional: x (integer), y (integer) — new position; width (integer), height (integer) "
                        + "— new size; text (string) — new label for groups or content for notes "
                        + "(rejected for elements); fillColor, lineColor, fontColor (#RRGGBB hex or empty "
                        + "to clear), opacity (0-255), lineWidth (1-3) — visual styling; "
                        + "imagePath (string — from add-image-to-model, empty to remove), "
                        + "imagePosition (string — e.g. bottom-left), showIcon (string — if-no-image/always/never) "
                        + "— custom image on element/group/note. "
                        + "Respects approval mode (set-approval-mode). "
                        + "Related: get-view-contents (inspect view + get viewObjectIds), "
                        + "add-to-view (place elements), add-image-to-model (import images).")
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

            MutationResult<ViewObjectDto> result = accessor.updateViewObject(
                    sessionId, viewObjectId, x, y, width, height, text, styling, imageParams);

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

    // ---- update-view-connection (Story 7-8) ----

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
                        + "Respects approval mode (set-approval-mode). "
                        + "Related: get-view-contents (inspect view + get connection IDs and "
                        + "absoluteBendpoints), add-connection-to-view (add connections).")
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

            // At least one format must be provided (or both null means clear)
            if (bendpoints == null && absoluteBendpoints == null && styling == null
                    && showLabel == null && textPosition == null) {
                bendpoints = List.of(); // clear bendpoints
            }

            MutationResult<ViewConnectionDto> result = accessor.updateViewConnection(
                    sessionId, viewConnectionId, bendpoints, absoluteBendpoints, styling,
                    showLabel, textPosition);

            List<BendpointDto> effectiveBps = (bendpoints != null) ? bendpoints : List.of();
            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildUpdateViewConnectionNextSteps(result, effectiveBps), accessor, formatter);

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
            MutationResult<ViewConnectionDto> result, List<BendpointDto> bendpoints) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        if (bendpoints.isEmpty()) {
            return List.of(
                    "Connection bendpoints cleared (straight line). Use get-view-contents to inspect.",
                    "Use update-view-connection to add bendpoints for routing.");
        }
        return List.of(
                "Connection bendpoints updated. Use get-view-contents to inspect.",
                "Use update-view-connection with empty bendpoints array to straighten the connection.");
    }

    // ---- remove-from-view (Story 7-8) ----

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
                        + "Respects approval mode (set-approval-mode). "
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

    // ---- clear-view (Story 8-0c) ----

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
                        + "Respects approval mode (set-approval-mode). "
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

    // ---- apply-positions (Story 9-0a, renamed 11-8) ----

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

    // ---- compute-layout (Story 9-1, renamed 11-8) ----

    private McpServerFeatures.SyncToolSpecification buildLayoutViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to layout");

        Map<String, Object> algorithmProp = new LinkedHashMap<>();
        algorithmProp.put("type", "string");
        algorithmProp.put("description",
                "Layout algorithm: tree, spring, directed, radial, grid, horizontal-tree. "
                + "Mutually exclusive with 'preset'.");

        Map<String, Object> presetProp = new LinkedHashMap<>();
        presetProp.put("type", "string");
        presetProp.put("description",
                "Semantic preset: compact, spacious, hierarchical, organic. "
                + "Mutually exclusive with 'algorithm'.");

        Map<String, Object> spacingProp = new LinkedHashMap<>();
        spacingProp.put("type", "integer");
        spacingProp.put("description",
                "Spacing between elements in pixels (default varies by algorithm, typically 40-60)");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("algorithm", algorithmProp);
        properties.put("preset", presetProp);
        properties.put("spacing", spacingProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("compute-layout")
                .description("[Mutation] Apply an automatic layout algorithm to a view. "
                        + "Repositions all elements and clears all connection bendpoints "
                        + "(straight lines) in a single atomic operation with a single undo unit. "
                        + "Provide either 'algorithm' (direct algorithm selection) or 'preset' "
                        + "(semantic preset), not both. After layout, use export-view to visually "
                        + "inspect the result. Supported algorithms: tree (top-down hierarchy), "
                        + "spring (force-directed, non-deterministic), directed (Sugiyama layered), "
                        + "radial (concentric circles), grid (regular grid), horizontal-tree "
                        + "(left-to-right tree). Supported presets: compact (tight grid), spacious "
                        + "(generous tree), hierarchical (top-down tree), organic (force-directed "
                        + "clustering). Related: apply-positions (manual position application), "
                        + "export-view (visual verification), get-view-contents (inspect layout).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleLayoutView)
                .build();
    }

    McpSchema.CallToolResult handleLayoutView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling compute-layout request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String algorithm = HandlerUtils.optionalStringParam(args, "algorithm");
            String preset = HandlerUtils.optionalStringParam(args, "preset");

            // Build options map from optional spacing parameter
            Map<String, Object> options = null;
            Object spacingObj = args != null ? args.get("spacing") : null;
            if (spacingObj instanceof Number n) {
                options = new LinkedHashMap<>();
                options.put("spacing", n.intValue());
            }

            MutationResult<LayoutViewResultDto> result =
                    accessor.layoutView(sessionId, viewId, algorithm, preset, options);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildLayoutViewNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling compute-layout", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private List<String> buildLayoutViewNextSteps(
            MutationResult<LayoutViewResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Use export-view to visually verify the layout.",
                "Use apply-positions to fine-tune individual element positions.",
                "Connection bendpoints were cleared — use update-view-connection to add routing if needed.");
    }

    // ---- assess-layout (Story 9-2) ----

    private McpServerFeatures.SyncToolSpecification buildAssessLayoutSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view to assess");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

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
                        + "Related: compute-layout (automatic layout), auto-route-connections "
                        + "(routing), undo (roll back if unsatisfied), get-view-contents "
                        + "(inspect elements), export-view (visual verification).\n\n"
                        + "Overlap metrics distinguish between `overlapCount` (sibling overlaps "
                        + "— genuine layout problems where unrelated elements overlap) and "
                        + "`containmentOverlaps` (expected overlaps from ancestor-descendant "
                        + "containment, e.g., elements inside groups). Only sibling overlaps "
                        + "affect the quality rating and trigger suggestions.\n\n"
                        + "The overall rating uses a severity-tiered model: "
                        + "Tier 1 (critical: overlaps, passThroughs, coincidentSegments) can produce 'poor'; "
                        + "Tier 2 (moderate: edgeCrossings, nonOrthogonalTerminals) caps at 'fair'; "
                        + "Tier 3 (cosmetic: spacing, alignment, labelOverlaps) caps at 'good'. "
                        + "The `ratingBreakdown` map shows per-metric ratings including "
                        + "`coincidentSegments` and `nonOrthogonalTerminals`.\n\n"
                        + "Edge crossing rating is lenient for grouped views: when groups with "
                        + "inter-group connections are present and crossings are the main issue "
                        + "(zero overlaps, good alignment, pass-throughs <= 3), crossings get a "
                        + "one-tier boost — cross-group edge crossings are topologically unavoidable.\n\n"
                        + "`coincidentSegmentCount` reports overlapping connection route segments "
                        + "— connections sharing identical path segments that visually overlap. "
                        + "Rated as a Tier 1 (critical) metric in the quality breakdown. "
                        + "Increase element spacing or re-run auto-route-connections to fix.\n\n"
                        + "`contentBounds` returns the axis-aligned bounding box ({x, y, width, height}) "
                        + "of all visual content (elements, groups, notes) in absolute canvas coordinates. "
                        + "Use this for safe placement calculations — e.g., place a title note at "
                        + "(contentBounds.x, contentBounds.y - 40) without inspecting individual elements. "
                        + "Null/omitted on empty views.")
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
            String viewId = HandlerUtils.requireStringParam(args, "viewId");

            AssessLayoutResultDto dto = accessor.assessLayout(viewId);

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
     * Builds context-aware nextSteps graduated by quality rating and view structure (Story 11-17, 11-22).
     * Recommends the lightest effective intervention first: auto-route-connections, then
     * auto-layout-and-route (ELK). Never recommends compute-layout.
     */
    // Story 11-17: thresholds for "good" rating spacing/alignment fix recommendations.
    // Stricter than assessor's EXCELLENT thresholds (30.0 / 60) because a "good" view
    // with adequate spacing should only get routing advice, not layout rearrangement.
    private static final double GOOD_SPACING_FIX_THRESHOLD = 40.0;
    private static final int GOOD_ALIGNMENT_FIX_THRESHOLD = 70;

    private List<String> buildAssessLayoutNextSteps(AssessLayoutResultDto dto) {
        List<String> steps = new ArrayList<>();
        String rating = dto.overallRating();
        boolean hasGroups = dto.hasGroups();
        boolean hasConnections = dto.connectionCount() > 0;
        int passThroughCount = dto.connectionPassThroughs() != null
                ? dto.connectionPassThroughs().size() : 0;
        boolean hasRoutingIssues = hasConnections
                && (dto.edgeCrossingCount() > 0 || passThroughCount > 0);
        boolean passThroughDominated = passThroughCount >= 3;

        // Orphaned connection guidance — always first when present (unchanged)
        if (dto.orphanedConnections() > 0) {
            steps.add("Found " + dto.orphanedConnections()
                    + " orphaned connection(s) referencing missing view objects."
                    + " Use clear-view to rebuild the view cleanly.");
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

    // ---- auto-route-connections (Story 9-5) ----

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

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("connectionIds", connectionIdsProp);
        properties.put("strategy", strategyProp);
        properties.put("force", forceProp);
        properties.put("autoNudge", autoNudgeProp);
        properties.put("snapThreshold", snapThresholdProp);
        properties.put("perimeterMargin", perimeterMarginProp);

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
                        + "Connections are updated atomically as a single undo unit. "
                        + "Supports batch and approval modes. SPECULATIVE EXECUTION: "
                        + "To preview routing quality, apply routing → assess-layout → "
                        + "undo if unsatisfied. No dry-run needed — undo is cheap and instant. "
                        + "Related: compute-layout (position elements first), assess-layout "
                        + "(evaluate quality after routing), undo (roll back if unsatisfied), "
                        + "export-view (visual verification).")
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

            // Extract optional force parameter (Story 10-32)
            Boolean forceObj = args != null ? (Boolean) args.get("force") : null;
            boolean force = forceObj != null && forceObj;

            // Extract optional autoNudge parameter (Story 13-7)
            Boolean autoNudgeObj = args != null ? (Boolean) args.get("autoNudge") : null;
            boolean autoNudge = autoNudgeObj != null && autoNudgeObj;

            // Extract optional snapThreshold parameter (backlog-b17)
            Integer snapThresholdObj = args != null ? (Integer) args.get("snapThreshold") : null;
            int snapThreshold = snapThresholdObj != null
                    ? Math.max(0, Math.min(50, snapThresholdObj)) : 20;

            // Extract optional perimeterMargin parameter (B36)
            Integer perimeterMarginObj = args != null ? (Integer) args.get("perimeterMargin") : null;
            int perimeterMargin = perimeterMarginObj != null
                    ? Math.max(10, Math.min(200, perimeterMarginObj)) : 50;

            MutationResult<AutoRouteResultDto> result =
                    accessor.autoRouteConnections(sessionId, viewId, connectionIds, strategy,
                            force, autoNudge, snapThreshold, perimeterMargin);

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
        if (result.entity() != null && !result.entity().violations().isEmpty()) {
            steps.add("Routes applied with " + result.entity().violations().size()
                    + " constraint violation(s). Consider using assess-layout to check "
                    + "overall quality.");
            steps.add("For higher quality, move elements per the violation details "
                    + "and re-route without force.");
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

    // ---- auto-connect-view (Story 9-6) ----

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

            MutationResult<AutoConnectResultDto> result =
                    accessor.autoConnectView(sessionId, viewId, elementIds,
                            relationshipTypes, showLabel);

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
                "Use compute-layout if elements need repositioning after connections are added.",
                "Use assess-layout to evaluate overall diagram quality.");
    }

    // ---- layout-within-group (Story 9-9) ----

    private McpServerFeatures.SyncToolSpecification buildLayoutWithinGroupSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "ID of the view containing the group");

        Map<String, Object> groupViewObjectIdProp = new LinkedHashMap<>();
        groupViewObjectIdProp.put("type", "string");
        groupViewObjectIdProp.put("description",
                "View object ID of the group to layout children within "
                + "(from get-view-contents groups list)");

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
                        + "using row, column, or grid patterns. Computes positions "
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

    // ---- auto-layout-and-route (Story 10-29) ----

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

            // Validate mode parameter (backlog-b24)
            if (mode != null && !"auto".equals(mode) && !"grouped".equals(mode)) {
                throw new ModelAccessException(
                        "Invalid mode: '" + mode + "'",
                        ErrorCode.INVALID_PARAMETER,
                        "mode must be one of: auto, grouped.",
                        "Use mode: \"grouped\" for views with groups, or omit for ELK layout.",
                        null);
            }

            // Story 11-16: optional targetRating for quality iteration
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
        // Story 11-16: when targetRating was used, quality assessment already done
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

    // ---- arrange-groups (Story 11-20) ----

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
                "Gap in pixels between groups (default: 40). Groups are larger than elements, "
                + "so 40px is recommended minimum.");

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
                .description("[Mutation] Positions top-level groups relative to each other in a grid, row, or column layout. "
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
                        + "Only repositions groups — preserves each group's current width and height.")
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

    // ---- optimize-group-order (Story 11-25) ----

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
                        + "auto-route-connections → assess-layout. Related: layout-within-group "
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

    // ---- Styling helper methods (Story 11-2) ----

    /**
     * Adds styling property definitions (fillColor, lineColor, fontColor, opacity, lineWidth)
     * to a tool spec properties map. Used by add-to-view, add-group-to-view, add-note-to-view,
     * and update-view-object.
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

        properties.put("fillColor", fillColorProp);
        properties.put("lineColor", lineColorProp);
        properties.put("fontColor", fontColorProp);
        properties.put("opacity", opacityProp);
        properties.put("lineWidth", lineWidthProp);
    }

    /**
     * Adds connection styling property definitions (lineColor, lineWidth, fontColor)
     * to a tool spec properties map. Connections don't support fillColor or opacity.
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

        properties.put("lineColor", lineColorProp);
        properties.put("fontColor", fontColorProp);
        properties.put("lineWidth", lineWidthProp);
    }

    /**
     * Parses a labelPosition string ("source"/"middle"/"target") to integer (0/1/2).
     * Returns null if the value is not provided (Story 13-11).
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

        if (fillColor == null && lineColor == null && fontColor == null
                && opacity == null && lineWidth == null) {
            return null;
        }
        return new StylingParams(fillColor, lineColor, fontColor, opacity, lineWidth);
    }

    // ---- Image helper methods (Story C4) ----

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

    // ---- detect-hub-elements (Story 13-3) ----

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
                        + "(estimated pixel width of the longest connection label). Elements with "
                        + ">6 connections include sizing suggestions based on the hub element "
                        + "formula (baseDimension + 15px \u00d7 (connectionCount \u2212 6)), "
                        + "adjusted for label widths when labels require more space. "
                        + "Use after layout and before auto-route-connections to optimise hub "
                        + "element sizes for better connection routing.\n\n"
                        + "Related: update-view-object (resize hubs), auto-route-connections "
                        + "(re-route after resizing), assess-layout (verify quality improvement).")
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

    // ---- layout-flat-view (Story 13-6) ----

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

    // ---- resize-elements-to-fit (Story B48) ----

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

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("elementIds", elementIdsProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("resize-elements-to-fit")
                .description("[Mutation] Resize elements on a view to fit their label text. "
                        + "Uses SWT font metrics and aspect-ratio-aware sizing "
                        + "(target 1.5:1 width:height, range [1.2:1, 2.5:1]). "
                        + "Short names (<=15 chars) keep Archi defaults (120x55). "
                        + "For nested elements, uses two-pass algorithm: children sized first, "
                        + "then parents sized to contain children + own label + padding. "
                        + "Recommended after placing elements on flat views to prevent label truncation. "
                        + "Related: add-to-view with autoSize (size at placement time), "
                        + "layout-flat-view (reposition elements), "
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

            MutationResult<ResizeElementsResultDto> result =
                    accessor.resizeElementsToFit(sessionId, viewId, elementIds);

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
}
