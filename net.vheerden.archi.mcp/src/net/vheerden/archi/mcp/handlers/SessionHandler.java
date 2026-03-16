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
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.FieldSelector;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for session management tools: set-session-filter (Story 5.1),
 * get-session-filters (Story 5.1).
 *
 * <p>This handler does NOT need an {@code ArchiModelAccessor} because
 * session tools do not access model data directly.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class only depends on
 * {@link SessionManager}, {@link ResponseFormatter}, and {@link CommandRegistry}.</p>
 */
public class SessionHandler {

    private static final Logger logger = LoggerFactory.getLogger(SessionHandler.class);

    private final SessionManager sessionManager;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;

    /**
     * Creates a SessionHandler with its required dependencies.
     *
     * @param sessionManager the session manager for filter state
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     */
    public SessionHandler(SessionManager sessionManager,
                          ResponseFormatter formatter,
                          CommandRegistry registry) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     * Registers: set-session-filter, get-session-filters.
     */
    public void registerTools() {
        registry.registerTool(buildSetSessionFilterSpec());
        registry.registerTool(buildGetSessionFiltersSpec());
    }

    // ---- set-session-filter ----

    private McpServerFeatures.SyncToolSpecification buildSetSessionFilterSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("description", "ArchiMate element type to filter by in subsequent queries. "
                + "Example: 'ApplicationComponent', 'BusinessProcess'.");
        properties.put("type", typeProp);

        Map<String, Object> layerProp = new LinkedHashMap<>();
        layerProp.put("type", "string");
        layerProp.put("description", "ArchiMate layer to filter by in subsequent queries. "
                + "Valid values: Business, Application, Technology, Physical, Strategy, "
                + "Motivation, Implementation & Migration.");
        properties.put("layer", layerProp);

        Map<String, Object> clearProp = new LinkedHashMap<>();
        clearProp.put("type", "boolean");
        clearProp.put("description", "Set to true to remove all session filters and field selection. "
                + "If combined with type/layer/fields/exclude, clears first then sets new values.");
        properties.put("clear", clearProp);

        Map<String, Object> fieldsProp = new LinkedHashMap<>();
        fieldsProp.put("type", "string");
        fieldsProp.put("description", "Field verbosity preset for element/view data in subsequent queries. "
                + "'minimal' returns only id and name. "
                + "'standard' (default) returns id, name, type, layer, documentation, properties. "
                + "'full' returns all available fields.");
        fieldsProp.put("enum", List.of("minimal", "standard", "full"));
        properties.put("fields", fieldsProp);

        Map<String, Object> excludeProp = new LinkedHashMap<>();
        excludeProp.put("type", "array");
        Map<String, Object> excludeItems = new LinkedHashMap<>();
        excludeItems.put("type", "string");
        excludeProp.put("items", excludeItems);
        excludeProp.put("description", "Fields to exclude from element/view data in subsequent queries. "
                + "Applied after fields preset. "
                + "Valid values: documentation, properties, layer, type, viewpointType, folderPath, visualMetadata. "
                + "Note: id and name cannot be excluded.");
        properties.put("exclude", excludeProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("set-session-filter")
                .description("[Session] Set persistent filters and field selection that apply to all subsequent queries "
                        + "in this session. Essential for large models — scope analysis to a specific "
                        + "layer or element type to reduce noise and token usage. "
                        + "Field selection controls response verbosity across all tools. "
                        + "Use clear=true to remove all session filters and field selection. "
                        + "Filters apply to: search-elements, get-element, get-views, "
                        + "get-view-contents, get-relationships.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleSetSessionFilter)
                .build();
    }

    private McpSchema.CallToolResult handleSetSessionFilter(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling set-session-filter request");
        try {
            Map<String, Object> args = request.arguments();
            String sessionId = SessionManager.extractSessionId(exchange);

            // Extract parameters
            String type = null;
            String layer = null;
            boolean clear = false;
            String fieldsPreset = null;
            Set<String> excludeFields = null;

            if (args != null) {
                Object typeObj = args.get("type");
                if (typeObj instanceof String t && !t.isBlank()) {
                    type = t;
                }

                Object layerObj = args.get("layer");
                if (layerObj instanceof String l && !l.isBlank()) {
                    layer = l;
                }

                Object clearObj = args.get("clear");
                if (clearObj instanceof Boolean c) {
                    clear = c;
                }

                Object fieldsObj = args.get("fields");
                if (fieldsObj instanceof String f && !f.isBlank()) {
                    fieldsPreset = f;
                }

                Object excludeObj = args.get("exclude");
                if (excludeObj instanceof List<?> excludeList && !excludeList.isEmpty()) {
                    excludeFields = new LinkedHashSet<>();
                    for (Object item : excludeList) {
                        if (item instanceof String s && !s.isBlank()) {
                            excludeFields.add(s);
                        }
                    }
                    if (excludeFields.isEmpty()) {
                        excludeFields = null;
                    }
                }
            }

            // Validate: at least one parameter must be provided
            boolean hasFieldSelection = fieldsPreset != null || excludeFields != null;
            if (type == null && layer == null && !clear && !hasFieldSelection) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "At least one of 'type', 'layer', 'fields', 'exclude', or 'clear' must be provided",
                        null,
                        "Example: set-session-filter with type='ApplicationComponent' or fields='minimal' or clear=true",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Execute: clear first if requested
            if (clear) {
                sessionManager.clearSessionFilter(sessionId);
            }

            // Set new filters if provided (SessionManager validates all params)
            if (type != null || layer != null || hasFieldSelection) {
                sessionManager.setSessionFilter(sessionId, type, layer, fieldsPreset, excludeFields);
            }

            // Build response with active filters
            boolean clearOnly = clear && type == null && layer == null && !hasFieldSelection;
            return buildActiveFiltersResponse(sessionId, clearOnly);

        } catch (IllegalArgumentException e) {
            // SessionManager throws IllegalArgumentException for invalid type/layer/fields/exclude
            String message = e.getMessage();
            String suggestion;
            if (message != null && message.contains("element type")) {
                suggestion = "Valid types: ApplicationComponent, BusinessProcess, Node, Device, "
                        + "Capability, Stakeholder, WorkPackage, and more. "
                        + "Use get-model-info to see all available element types.";
            } else if (message != null && message.contains("fields preset")) {
                suggestion = "Valid presets: minimal, standard, full";
            } else if (message != null && message.contains("exclude field")) {
                suggestion = "Valid exclude fields: documentation, properties, layer, type, "
                        + "viewpointType, folderPath, visualMetadata";
            } else {
                suggestion = "Valid layers: Business, Application, Technology, Physical, "
                        + "Strategy, Motivation, Implementation & Migration";
            }
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INVALID_PARAMETER,
                    message != null ? message : "Invalid filter parameter",
                    null,
                    suggestion,
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);

        } catch (Exception e) {
            logger.error("Unexpected error handling set-session-filter", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while setting session filters");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    // ---- get-session-filters ----

    private McpServerFeatures.SyncToolSpecification buildGetSessionFiltersSpec() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", new LinkedHashMap<>(), null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-session-filters")
                .description("[Session] Retrieve the currently active session-scoped filters "
                        + "and field selection preferences. Check this to understand what filters "
                        + "are affecting query results.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetSessionFilters)
                .build();
    }

    private McpSchema.CallToolResult handleGetSessionFilters(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-session-filters request");
        try {
            String sessionId = SessionManager.extractSessionId(exchange);
            return buildActiveFiltersResponse(sessionId, false);

        } catch (Exception e) {
            logger.error("Unexpected error handling get-session-filters", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while retrieving session filters");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    // ---- Response Helpers ----

    private McpSchema.CallToolResult buildActiveFiltersResponse(String sessionId, boolean wasCleared) {
        Optional<SessionManager.SessionState> state = sessionManager.getSessionFilter(sessionId);

        Map<String, Object> resultData = new LinkedHashMap<>();

        if (state.isPresent()) {
            SessionManager.SessionState s = state.get();
            Map<String, Object> activeFilters = new LinkedHashMap<>();
            if (s.typeFilter() != null) {
                activeFilters.put("type", s.typeFilter());
            }
            if (s.layerFilter() != null) {
                activeFilters.put("layer", s.layerFilter());
            }
            resultData.put("activeFilters", activeFilters);

            // Field selection preferences
            if (s.fieldsPreset() != null || s.excludeFields() != null) {
                Map<String, Object> fieldSelection = new LinkedHashMap<>();
                if (s.fieldsPreset() != null) {
                    fieldSelection.put("fields", s.fieldsPreset());
                }
                if (s.excludeFields() != null && !s.excludeFields().isEmpty()) {
                    fieldSelection.put("exclude", new ArrayList<>(s.excludeFields()));
                }
                resultData.put("activeFieldSelection", fieldSelection);
            }
        } else {
            resultData.put("activeFilters", null);
        }

        List<String> nextSteps;
        if (state.isPresent()) {
            nextSteps = List.of(
                    "Use search-elements to query with active session filters applied",
                    "Use get-relationships to explore connections with session layer filter applied",
                    "Active filters/field selection apply to: search-elements, get-element, get-views, get-view-contents, get-relationships");
        } else if (wasCleared) {
            nextSteps = List.of(
                    "Session filters and field selection cleared. All queries now return unfiltered, standard-verbosity results.",
                    "Use set-session-filter to configure new filters or field selection");
        } else {
            nextSteps = List.of(
                    "No active session filters. Use set-session-filter to configure filters.",
                    "Example: set-session-filter with type='ApplicationComponent' or fields='minimal'");
        }

        Map<String, Object> envelope = formatter.formatSuccess(
                resultData, nextSteps, null, 1, 1, false);
        return buildResult(formatter.toJsonString(envelope), false);
    }

    private McpSchema.CallToolResult buildResult(String json, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(isError)
                .build();
    }
}
