package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.FieldSelector;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for model-level query tools: get-model-info (Story 2.2),
 * get-element (Story 2.3).
 *
 * <p>This is the first handler in the codebase and establishes the pattern
 * for all subsequent handlers (ViewHandler, SearchHandler, TraversalHandler).</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF or ArchimateTool model types. All model access goes through
 * {@link ArchiModelAccessor}.</p>
 */
public class ModelQueryHandler {

    private static final Logger logger = LoggerFactory.getLogger(ModelQueryHandler.class);
    private static final int MAX_BATCH_SIZE = 50;
    static final int SMALL_MODEL_THRESHOLD = 100;
    static final int LARGE_MODEL_THRESHOLD = 500;
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a ModelQueryHandler with its required dependencies.
     *
     * @param accessor       the model accessor for querying ArchiMate data
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager for session-scoped filters, may be null
     */
    public ModelQueryHandler(ArchiModelAccessor accessor,
                             ResponseFormatter formatter,
                             CommandRegistry registry,
                             SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager; // nullable for backward compat; no filter logic for ID-based lookups
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     * Currently registers: get-model-info, get-element.
     */
    public void registerTools() {
        registry.registerTool(buildGetModelInfoSpec());
        registry.registerTool(buildGetElementSpec());
    }

    private McpServerFeatures.SyncToolSpecification buildGetModelInfoSpec() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", Collections.emptyMap(), null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-model-info")
                .description("[Query] Get summary information about the loaded ArchiMate model "
                        + "including name, element/relationship/view counts, element type distribution, "
                        + "relationship type distribution, and layer distribution. "
                        + "This is the FIRST tool to call when exploring a new model — "
                        + "it provides the statistics needed to plan an efficient exploration strategy. "
                        + "The response includes model-size-aware nextSteps suggestions. "
                        + "Related: get-views (browse diagrams), search-elements (find elements), "
                        + "set-session-filter (scope queries).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetModelInfo)
                .build();
    }

    private McpSchema.CallToolResult handleGetModelInfo(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-model-info request");
        try {
            String modelVersion = accessor.getModelVersion();
            String sessionId = (sessionManager != null)
                    ? SessionManager.extractSessionId(exchange) : null;

            // Model version change detection — call ONCE (Story 5.3 + 5.4)
            boolean modelChanged = false;
            if (sessionManager != null && sessionId != null) {
                modelChanged = sessionManager.checkModelVersionChanged(sessionId, modelVersion);
                if (modelChanged) {
                    sessionManager.invalidateSessionCache(sessionId);
                    logger.warn("Model changed during session {} — version now {}", sessionId, modelVersion);
                }
            }

            // Cache check (Story 5.4) — skip if version just changed (cache was invalidated)
            String cacheKey = CacheKeyBuilder.buildCacheKey("model-info");
            if (sessionManager != null && sessionId != null && !modelChanged) {
                try {
                    String cachedJson = sessionManager.getCacheEntry(sessionId, cacheKey);
                    if (cachedJson != null) {
                        logger.debug("Cache hit for key: {}", cacheKey);
                        Map<String, Object> cachedEnvelope = objectMapper.readValue(cachedJson, MAP_TYPE_REF);
                        ResponseFormatter.addCacheHitFlag(cachedEnvelope);
                        return buildResult(objectMapper.writeValueAsString(cachedEnvelope), false);
                    }
                } catch (Exception e) {
                    logger.debug("Cache read failed, proceeding with fresh query", e);
                }
            }

            // Fresh query
            ModelInfoDto info = accessor.getModelInfo();

            List<String> nextSteps = buildModelInfoNextSteps(info);

            Map<String, Object> envelope = formatter.formatSuccess(
                    info, nextSteps, modelVersion, 1, 1, false);

            // Store in cache (Story 5.4)
            String jsonResult = formatter.toJsonString(envelope);
            if (sessionManager != null && sessionId != null) {
                sessionManager.putCacheEntry(sessionId, cacheKey, jsonResult);
                logger.debug("Cache miss, storing result for key: {}", cacheKey);
            }

            // Add modelChanged flag if version changed (Story 5.3)
            if (modelChanged) {
                ResponseFormatter.addModelChangedFlag(envelope);
                return buildResult(formatter.toJsonString(envelope), false);
            }

            return buildResult(jsonResult, false);

        } catch (NoModelLoadedException e) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.MODEL_NOT_LOADED,
                    e.getMessage(),
                    null,
                    "Open an ArchiMate model in ArchimateTool",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);

        } catch (Exception e) {
            logger.error("Unexpected error handling get-model-info", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while retrieving model information");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    private McpServerFeatures.SyncToolSpecification buildGetElementSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> idProp = new LinkedHashMap<>();
        idProp.put("type", "string");
        idProp.put("description", "The unique identifier of a single ArchiMate element to retrieve. "
                + "Use this for single element lookup.");
        properties.put("id", idProp);

        Map<String, Object> idsProp = new LinkedHashMap<>();
        idsProp.put("type", "array");
        Map<String, Object> idsItems = new LinkedHashMap<>();
        idsItems.put("type", "string");
        idsProp.put("items", idsItems);
        idsProp.put("description", "Array of element IDs for batch retrieval. "
                + "Returns all found elements with unfound IDs listed in _meta.notFound. "
                + "More efficient than multiple get-element calls.");
        properties.put("ids", idsProp);

        Map<String, Object> fieldsProp = new LinkedHashMap<>();
        fieldsProp.put("type", "string");
        fieldsProp.put("description", "Field verbosity preset for element data. "
                + "'minimal' returns only id and name. "
                + "'standard' (default) returns id, name, type, layer, documentation, properties. "
                + "'full' returns all available fields.");
        fieldsProp.put("enum", List.of("minimal", "standard", "full"));
        properties.put("fields", fieldsProp);

        Map<String, Object> excludeProp = new LinkedHashMap<>();
        excludeProp.put("type", "array");
        Map<String, Object> excludeItemsDef = new LinkedHashMap<>();
        excludeItemsDef.put("type", "string");
        excludeProp.put("items", excludeItemsDef);
        excludeProp.put("description", "Fields to exclude from element data. "
                + "Applied after fields preset. "
                + "Valid values: documentation, properties, layer, type, "
                + "viewpointType, folderPath, visualMetadata. "
                + "Note: id and name cannot be excluded.");
        properties.put("exclude", excludeProp);

        // Neither is required — handler validates that exactly one of id/ids is provided
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-element")
                .description("[Query] Retrieve ArchiMate element(s) by ID. Use 'id' for a single element "
                        + "or 'ids' for batch retrieval of multiple elements in one call. "
                        + "Use 'fields' to control response verbosity and 'exclude' to omit specific fields. "
                        + "Get element IDs from search-elements or get-view-contents. "
                        + "Related: get-relationships (explore connections), get-view-contents (see in diagrams).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetElement)
                .build();
    }

    private McpSchema.CallToolResult handleGetElement(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-element request");
        try {
            Map<String, Object> args = request.arguments();
            Object idObj = (args != null) ? args.get("id") : null;
            Object idsObj = (args != null) ? args.get("ids") : null;

            // Early check: neither id nor ids provided
            if (idObj == null && idsObj == null) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Provide either 'id' for single element lookup or 'ids' for batch retrieval",
                        null,
                        "Use 'id' with a single element ID or 'ids' with an array of element IDs",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Extract field selection parameters
            String fieldsParam = null;
            List<String> excludeParam = null;
            if (args != null) {
                Object fieldsObj = args.get("fields");
                if (fieldsObj instanceof String f && !f.isBlank()) {
                    fieldsParam = f;
                }
                Object excludeObj = args.get("exclude");
                if (excludeObj instanceof List<?> el && !el.isEmpty()) {
                    excludeParam = new ArrayList<>();
                    for (Object item : el) {
                        if (item instanceof String s && !s.isBlank()) excludeParam.add(s);
                    }
                    if (excludeParam.isEmpty()) excludeParam = null;
                }
            }

            // Validate exclude field names
            if (excludeParam != null) {
                for (String field : excludeParam) {
                    if (!FieldSelector.VALID_EXCLUDE_FIELDS.contains(field)) {
                        ErrorResponse error = new ErrorResponse(
                                ErrorCode.INVALID_PARAMETER,
                                "Invalid exclude field: '" + field + "'",
                                null,
                                "Valid exclude fields: documentation, properties, layer, type, "
                                        + "viewpointType, folderPath, visualMetadata",
                                null);
                        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                    }
                }
            }

            // Extract session context
            String sessionId = (sessionManager != null)
                    ? SessionManager.extractSessionId(exchange) : null;

            // Merge with session field selection
            String effectiveFieldsPreset = fieldsParam;
            Set<String> effectiveExclude = excludeParam != null ? Set.copyOf(excludeParam) : null;
            if (sessionManager != null) {
                effectiveFieldsPreset = sessionManager.getEffectiveFieldsPreset(sessionId, fieldsParam);
                effectiveExclude = sessionManager.getEffectiveExcludeFields(sessionId, excludeParam);
            }

            // Parse preset with fallback (AC #5)
            FieldSelector.FieldPreset preset = FieldSelector.FieldPreset.STANDARD;
            String warningMessage = null;
            if (effectiveFieldsPreset != null) {
                Optional<FieldSelector.FieldPreset> parsed = FieldSelector.FieldPreset.fromString(effectiveFieldsPreset);
                if (parsed.isPresent()) {
                    preset = parsed.get();
                } else {
                    warningMessage = "Invalid fields preset '" + effectiveFieldsPreset
                            + "', using 'standard'. Valid presets: minimal, standard, full";
                }
            }
            logger.debug("Field selection — preset: {}, exclude: {}", preset, effectiveExclude);

            // Route to batch or single path
            if (idsObj != null) {
                return handleBatchGetElement(idObj, idsObj, preset, effectiveExclude, warningMessage, sessionId);
            } else {
                return handleSingleGetElement(idObj, preset, effectiveExclude, warningMessage, sessionId);
            }

        } catch (NoModelLoadedException e) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.MODEL_NOT_LOADED,
                    e.getMessage(),
                    null,
                    "Open an ArchiMate model in ArchimateTool",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);

        } catch (Exception e) {
            logger.error("Unexpected error handling get-element", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while retrieving element information");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    private McpSchema.CallToolResult handleSingleGetElement(Object idObj,
            FieldSelector.FieldPreset preset, Set<String> excludeFields,
            String warningMessage, String sessionId) {
        if (!(idObj instanceof String id) || id.isBlank()) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INVALID_PARAMETER,
                    "The 'id' parameter is required and must be a non-empty string",
                    null,
                    "Provide a valid element ID. Use search-elements to find element IDs.",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }

        String modelVersion = accessor.getModelVersion();

        // Model version change detection — call ONCE (Story 5.3 + 5.4)
        boolean modelChanged = false;
        if (sessionManager != null && sessionId != null) {
            modelChanged = sessionManager.checkModelVersionChanged(sessionId, modelVersion);
            if (modelChanged) {
                sessionManager.invalidateSessionCache(sessionId);
                logger.warn("Model changed during session {} — version now {}", sessionId, modelVersion);
            }
        }

        // Cache check (Story 5.4) — skip if version just changed
        String cacheKey = CacheKeyBuilder.buildCacheKey("element", id, "fields", preset, "exclude", CacheKeyBuilder.sortedSetKey(excludeFields));
        if (sessionManager != null && sessionId != null && !modelChanged) {
            try {
                String cachedJson = sessionManager.getCacheEntry(sessionId, cacheKey);
                if (cachedJson != null) {
                    logger.debug("Cache hit for key: {}", cacheKey);
                    Map<String, Object> cachedEnvelope = objectMapper.readValue(cachedJson, MAP_TYPE_REF);
                    ResponseFormatter.addCacheHitFlag(cachedEnvelope);
                    return buildResult(objectMapper.writeValueAsString(cachedEnvelope), false);
                }
            } catch (Exception e) {
                logger.debug("Cache read failed, proceeding with fresh query", e);
            }
        }

        // Fresh query
        Optional<ElementDto> element = accessor.getElementById(id);
        if (element.isPresent()) {
            Object filteredResult = FieldSelector.applyFieldSelection(element.get(), preset, excludeFields);

            List<String> nextSteps = List.of(
                    "Use get-relationships to explore this element's connections and dependencies",
                    "Use get-view-contents to see this element in context within a diagram (get view IDs from get-views first)");

            Map<String, Object> envelope = formatter.formatSuccess(
                    filteredResult, nextSteps, modelVersion, 1, 1, false);

            if (warningMessage != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
                meta.put("warning", warningMessage);
            }

            // Store in cache (Story 5.4)
            String jsonResult = formatter.toJsonString(envelope);
            if (sessionManager != null && sessionId != null) {
                sessionManager.putCacheEntry(sessionId, cacheKey, jsonResult);
                logger.debug("Cache miss, storing result for key: {}", cacheKey);
            }

            // Add modelChanged flag if version changed (Story 5.3)
            if (modelChanged) {
                ResponseFormatter.addModelChangedFlag(envelope);
                return buildResult(formatter.toJsonString(envelope), false);
            }

            return buildResult(jsonResult, false);
        } else {
            String details;
            try {
                int totalElements = accessor.getModelInfo().elementCount();
                details = "The model contains " + totalElements + " elements total.";
            } catch (Exception ex) {
                details = null;
            }
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.ELEMENT_NOT_FOUND,
                    "No element found with ID '" + id + "'",
                    details,
                    "Use search-elements to find elements by name or type",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    private McpSchema.CallToolResult handleBatchGetElement(Object idObj, Object idsObj,
            FieldSelector.FieldPreset preset, Set<String> excludeFields,
            String warningMessage, String sessionId) {
        // Validate: if id is also provided, that's an error
        if (idObj instanceof String s && !s.isBlank()) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INVALID_PARAMETER,
                    "Provide either 'id' for single retrieval or 'ids' for batch retrieval, not both",
                    null,
                    "Use 'id' for a single element or 'ids' for multiple elements",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }

        // Validate: ids must be a List
        if (!(idsObj instanceof List<?> idsList)) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INVALID_PARAMETER,
                    "The 'ids' parameter must be an array of strings",
                    null,
                    "Provide an array of element IDs, e.g. [\"id1\", \"id2\"]",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }

        // Validate: ids must not exceed max batch size
        if (idsList.size() > MAX_BATCH_SIZE) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INVALID_PARAMETER,
                    "The 'ids' array must contain at most " + MAX_BATCH_SIZE + " element IDs",
                    null,
                    "Split large batches into multiple requests of " + MAX_BATCH_SIZE + " IDs or fewer",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }

        // Validate: ids must not be empty
        if (idsList.isEmpty()) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INVALID_PARAMETER,
                    "The 'ids' array must contain at least one element ID",
                    null,
                    "Provide at least one element ID in the 'ids' array",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }

        // Validate: all items must be non-blank strings
        List<String> ids = new ArrayList<>();
        for (Object item : idsList) {
            if (!(item instanceof String s) || s.isBlank()) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "All items in 'ids' array must be non-empty strings",
                        null,
                        "Ensure every ID in the array is a valid non-empty string",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }
            ids.add(s);
        }

        // Call accessor
        List<ElementDto> found = accessor.getElementsByIds(ids);

        // Compute notFound
        Set<String> foundIds = found.stream()
                .map(ElementDto::id)
                .collect(Collectors.toSet());
        List<String> notFoundIds = ids.stream()
                .filter(id -> !foundIds.contains(id))
                .toList();

        // Apply field selection
        Object filteredResult = FieldSelector.applyFieldSelection(found, preset, excludeFields);

        // Build response
        String modelVersion = accessor.getModelVersion();
        List<String> nextSteps = List.of(
                "Use get-relationships to explore connections for retrieved elements",
                "Use search-elements to find elements by name if IDs are unknown");

        Map<String, Object> envelope = formatter.formatSuccess(
                filteredResult, nextSteps, modelVersion,
                found.size(), found.size(), false);

        // Add notFound to _meta if any IDs were not found, and warning if preset fallback
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        if (!notFoundIds.isEmpty()) {
            meta.put("notFound", notFoundIds);
        }
        if (warningMessage != null) {
            meta.put("warning", warningMessage);
        }

        // Model version change detection (Story 5.3 + 5.4)
        if (sessionManager != null && sessionId != null
                && sessionManager.checkModelVersionChanged(sessionId, modelVersion)) {
            sessionManager.invalidateSessionCache(sessionId);
            ResponseFormatter.addModelChangedFlag(envelope);
            logger.warn("Model changed during session {} — version now {}", sessionId, modelVersion);
        }

        return buildResult(formatter.toJsonString(envelope), false);
    }

    private List<String> buildModelInfoNextSteps(ModelInfoDto info) {
        List<String> nextSteps = new ArrayList<>();

        if (info.elementCount() < SMALL_MODEL_THRESHOLD) {
            nextSteps.add("Use get-views to list all diagrams — this model is small enough to explore via views");
            nextSteps.add("Use search-elements to find specific elements by name or type");
        } else if (info.elementCount() <= LARGE_MODEL_THRESHOLD) {
            nextSteps.add("Use search-elements with type or layer filters for targeted exploration");
            nextSteps.add("Use get-views with a viewpoint filter to narrow down relevant diagrams");
            nextSteps.add("Use set-session-filter to scope ongoing analysis to a specific layer or type");
        } else {
            nextSteps.add("Use search-elements with specific text queries — this model is large, avoid unfiltered listings");
            nextSteps.add("Use set-session-filter to scope all subsequent queries to a specific layer or type");
            nextSteps.add("Use get-views with a viewpoint filter to find specific diagrams");
            nextSteps.add("Avoid get-views without filters — large models may have many views");
        }

        nextSteps.add("Note: This MCP server works best with 14B+ parameter models for reliable tool calling. 8B+ models can handle basic queries but may struggle with complex multi-tool workflows.");

        return nextSteps;
    }

    private McpSchema.CallToolResult buildResult(String json, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(isError)
                .build();
    }
}
