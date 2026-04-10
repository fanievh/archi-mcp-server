package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import net.vheerden.archi.mcp.response.CostEstimator;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.FieldSelector;
import net.vheerden.archi.mcp.response.GraphFormatter;
import net.vheerden.archi.mcp.response.PaginationCursor;
import net.vheerden.archi.mcp.response.ResponseFormat;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.SummaryFormatter;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for search tools: search-elements (Story 3.1), search-relationships (Story C1).
 *
 * <p>This handler follows the same pattern established by
 * {@link ModelQueryHandler} in Stories 2.2/2.3 and
 * {@link ViewHandler} in Stories 2.4/2.5.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF or ArchimateTool model types. All model access goes through
 * {@link ArchiModelAccessor}.</p>
 */
public class SearchHandler {

    private static final Logger logger = LoggerFactory.getLogger(SearchHandler.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    /**
     * Default page size for search results when no limit parameter is provided.
     * Matches the pre-pagination MAX_SEARCH_RESULTS for backward compatibility.
     */
    static final int DEFAULT_SEARCH_LIMIT = 200;

    /**
     * Valid ArchiMate element type names for the optional type filter.
     * These match exactly the EMF class names from {@code element.eClass().getName()}.
     */
    public static final Set<String> VALID_TYPES = Set.of(
            // Business Layer
            "BusinessActor", "BusinessRole", "BusinessCollaboration",
            "BusinessInterface", "BusinessProcess", "BusinessFunction",
            "BusinessInteraction", "BusinessEvent", "BusinessService",
            "BusinessObject", "Contract", "Representation", "Product",
            // Application Layer
            "ApplicationComponent", "ApplicationCollaboration",
            "ApplicationInterface", "ApplicationFunction",
            "ApplicationInteraction", "ApplicationProcess",
            "ApplicationEvent", "ApplicationService", "DataObject",
            // Technology Layer
            "Node", "Device", "SystemSoftware", "TechnologyCollaboration",
            "TechnologyInterface", "Path", "CommunicationNetwork",
            "TechnologyFunction", "TechnologyProcess", "TechnologyInteraction",
            "TechnologyEvent", "TechnologyService", "Artifact",
            // Physical Layer
            "Equipment", "Facility", "DistributionNetwork", "Material",
            // Strategy Layer
            "Resource", "Capability", "CourseOfAction", "ValueStream",
            // Motivation Layer
            "Stakeholder", "Driver", "Assessment", "Goal", "Outcome",
            "Principle", "Requirement", "Constraint", "Meaning", "Value",
            // Implementation & Migration Layer
            "WorkPackage", "Deliverable", "ImplementationEvent", "Plateau", "Gap",
            // Composite/Other
            "Location", "Grouping", "Junction");

    /**
     * Valid ArchiMate layer names for the optional layer filter.
     * These match exactly the strings returned by {@code ArchiModelAccessorImpl.resolveLayer()}.
     */
    public static final Set<String> VALID_LAYERS = Set.of(
            "Business", "Application", "Technology", "Physical",
            "Strategy", "Motivation", "Implementation & Migration");

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a SearchHandler with its required dependencies.
     *
     * @param accessor       the model accessor for querying ArchiMate data
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager for session-scoped filters, may be null
     */
    public SearchHandler(ArchiModelAccessor accessor,
                         ResponseFormatter formatter,
                         CommandRegistry registry,
                         SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager; // nullable for backward compat
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     * Registers: search-elements (Story 3.1), search-relationships (Story C1).
     */
    public void registerTools() {
        registry.registerTool(buildSearchElementsSpec());
        registry.registerTool(buildSearchRelationshipsSpec());
    }

    private McpServerFeatures.SyncToolSpecification buildSearchElementsSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description",
                "Text to search for across element names, documentation, "
                + "and property values (case-insensitive substring match). "
                + "Use an empty string to return all elements "
                + "(subject to pagination and session filters).");
        properties.put("query", queryProp);

        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("description",
                "Optional ArchiMate element type filter (e.g., "
                + "'ApplicationComponent', 'BusinessProcess'). "
                + "Only elements of this type are returned.");
        properties.put("type", typeProp);

        Map<String, Object> layerProp = new LinkedHashMap<>();
        layerProp.put("type", "string");
        layerProp.put("description",
                "Optional ArchiMate layer filter (e.g., 'Application', "
                + "'Technology', 'Business'). Only elements in this layer are returned.");
        properties.put("layer", layerProp);

        Map<String, Object> specProp = new LinkedHashMap<>();
        specProp.put("type", "string");
        specProp.put("description",
                "Optional specialization name filter (exact match, case-insensitive). "
                + "Only elements with this primary specialization are returned. "
                + "Use list-specializations to see available specializations.");
        properties.put("specialization", specProp);

        Map<String, Object> fieldsProp = new LinkedHashMap<>();
        fieldsProp.put("type", "string");
        fieldsProp.put("description", "Field verbosity preset for element data. "
                + "'minimal' returns only id and name. "
                + "'standard' (default) returns id, name, type, specialization, layer, documentation, properties. "
                + "'full' returns all available fields.");
        fieldsProp.put("enum", List.of("minimal", "standard", "full"));
        properties.put("fields", fieldsProp);

        Map<String, Object> excludeProp = new LinkedHashMap<>();
        excludeProp.put("type", "array");
        Map<String, Object> excludeItems = new LinkedHashMap<>();
        excludeItems.put("type", "string");
        excludeProp.put("items", excludeItems);
        excludeProp.put("description", "Fields to exclude from element data. "
                + "Applied after fields preset. "
                + "Valid values: documentation, properties, layer, type, "
                + "viewpointType, folderPath, visualMetadata. "
                + "Note: id and name cannot be excluded.");
        properties.put("exclude", excludeProp);

        Map<String, Object> limitProp = new LinkedHashMap<>();
        limitProp.put("type", "integer");
        limitProp.put("description", "Maximum number of results per page (1-"
                + PaginationCursor.MAX_PAGE_SIZE + "). Default: " + DEFAULT_SEARCH_LIMIT
                + ". Use with cursor for pagination.");
        properties.put("limit", limitProp);

        Map<String, Object> cursorProp = new LinkedHashMap<>();
        cursorProp.put("type", "string");
        cursorProp.put("description", "Pagination cursor from a previous response's "
                + "_meta.cursor. When provided, retrieves the next page of results. "
                + "Cursor parameters override query/type/layer parameters.");
        properties.put("cursor", cursorProp);

        Map<String, Object> dryRunProp = new LinkedHashMap<>();
        dryRunProp.put("type", "boolean");
        dryRunProp.put("description", "Set to true to get a cost estimate without returning results. "
                + "Returns estimated result count, token size, and recommended field preset. "
                + "Ignores cursor and limit parameters. "
                + "Use before executing expensive queries to choose the most efficient approach.");
        properties.put("dryRun", dryRunProp);

        Map<String, Object> formatProp = new LinkedHashMap<>();
        formatProp.put("type", "string");
        formatProp.put("description", "Response format. 'json' (default) returns standard result array. "
                + "'graph' returns nodes/edges structure (nodes = elements, edges = empty for search). "
                + "'summary' returns condensed natural language overview with type/layer distributions.");
        formatProp.put("enum", List.of("json", "graph", "summary"));
        properties.put("format", formatProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("query"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("search-elements")
                .description("[Search] Search across all ArchiMate elements by text, optionally filtered "
                        + "by element type and/or layer. Matches against element names, documentation, "
                        + "and property values using case-insensitive substring matching. "
                        + "The primary exploration tool for medium and large models. "
                        + "Use query='' to list all elements. "
                        + "Use type/layer filters to narrow results efficiently. "
                        + "Results are paginated if they exceed the limit. "
                        + "Use the cursor from _meta.cursor to retrieve subsequent pages. "
                        + "Use 'fields' to control response verbosity and 'exclude' to omit specific fields. "
                        + "Set dryRun=true to get a cost estimate without returning results. "
                        + "Set format=graph for node/edge structure, format=summary for condensed text overview. "
                        + "Results include specialization field for each element. "
                        + "Supports optional specialization filter for exact-match filtering by specialization name. "
                        + "Related: get-element (full details by ID), "
                        + "get-relationships (explore connections from a found element).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleSearchElements)
                .build();
    }

    private McpSchema.CallToolResult handleSearchElements(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling search-elements request");
        try {
            // Extract and validate required query parameter
            Object queryObj = (request.arguments() != null) ? request.arguments().get("query") : null;
            if (!(queryObj instanceof String query)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'query' parameter is required and must be a string",
                        null,
                        "Provide a search term (e.g. query: 'customer') or use an empty string (query: '') to list all elements",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Extract optional type filter (blank treated as no filter)
            String typeFilter = null;
            if (request.arguments() != null) {
                Object typeObj = request.arguments().get("type");
                if (typeObj instanceof String t && !t.isBlank()) {
                    typeFilter = t;
                }
            }

            // Validate type if provided
            if (typeFilter != null && !VALID_TYPES.contains(typeFilter)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Invalid element type: '" + typeFilter + "'",
                        "The 'type' parameter must be a valid ArchiMate element type name "
                        + "(e.g., ApplicationComponent, BusinessProcess, Node)",
                        "Use get-model-info to see all available element types in this model",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Extract optional layer filter (blank treated as no filter)
            String layerFilter = null;
            if (request.arguments() != null) {
                Object layerObj = request.arguments().get("layer");
                if (layerObj instanceof String l && !l.isBlank()) {
                    layerFilter = l;
                }
            }

            // Validate layer if provided
            if (layerFilter != null && !VALID_LAYERS.contains(layerFilter)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Invalid layer: '" + layerFilter + "'",
                        "The 'layer' parameter must be a valid ArchiMate layer name",
                        "Valid layers: Business, Application, Technology, Physical, "
                        + "Strategy, Motivation, Implementation & Migration",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Extract optional specialization filter (blank treated as no filter)
            String specializationFilter = null;
            if (request.arguments() != null) {
                Object specObj = request.arguments().get("specialization");
                if (specObj instanceof String s && !s.isBlank()) {
                    specializationFilter = s;
                }
            }

            // Extract field selection parameters
            String fieldsParam = null;
            List<String> excludeParam = null;
            if (request.arguments() != null) {
                Object fieldsObj = request.arguments().get("fields");
                if (fieldsObj instanceof String f && !f.isBlank()) {
                    fieldsParam = f;
                }
                Object excludeObj = request.arguments().get("exclude");
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
                                        + "specialization, viewpointType, folderPath, visualMetadata",
                                null);
                        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                    }
                }
            }

            // Extract dryRun parameter
            boolean dryRun = false;
            if (request.arguments() != null) {
                Object dryRunObj = request.arguments().get("dryRun");
                if (dryRunObj instanceof Boolean b) {
                    dryRun = b;
                }
            }

            // Extract and validate format parameter
            ResponseFormat format = ResponseFormat.JSON;
            if (request.arguments() != null) {
                Object formatObj = request.arguments().get("format");
                if (formatObj instanceof String f && !f.isBlank()) {
                    ResponseFormat parsed = ResponseFormat.fromString(f);
                    if (parsed == null) {
                        ErrorResponse error = new ErrorResponse(
                                ErrorCode.INVALID_PARAMETER,
                                "Invalid format: '" + f + "'",
                                null,
                                "Valid formats: json, graph, summary",
                                null);
                        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                    }
                    format = parsed;
                }
            }

            // Extract pagination parameters
            String cursorParam = null;
            Integer limitParam = null;
            if (request.arguments() != null) {
                Object cursorObj = request.arguments().get("cursor");
                if (cursorObj instanceof String c && !c.isBlank()) {
                    cursorParam = c;
                }
                Object limitObj = request.arguments().get("limit");
                if (limitObj instanceof Number n) {
                    limitParam = n.intValue();
                }
            }

            // Validate limit parameter
            if (limitParam != null && (limitParam < 1 || limitParam > PaginationCursor.MAX_PAGE_SIZE)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'limit' parameter must be between 1 and " + PaginationCursor.MAX_PAGE_SIZE,
                        null,
                        "Provide a limit between 1 and " + PaginationCursor.MAX_PAGE_SIZE
                                + ", or omit for default (" + DEFAULT_SEARCH_LIMIT + ")",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Cursor mode: decode and validate cursor
            int offset = 0;
            int limit = (limitParam != null) ? limitParam : DEFAULT_SEARCH_LIMIT;
            String effectiveQuery = query;
            String effectiveType = typeFilter;
            String effectiveLayer = layerFilter;
            String effectiveSpecialization = specializationFilter;

            if (cursorParam != null && !dryRun) {
                PaginationCursor.CursorData cursorData;
                try {
                    cursorData = PaginationCursor.decode(cursorParam);
                } catch (PaginationCursor.InvalidCursorException e) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_CURSOR,
                            e.getMessage(),
                            null,
                            "Re-run the original query without a cursor parameter",
                            null);
                    return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                }

                // Validate model version
                String currentModelVersion = accessor.getModelVersion();
                if (!cursorData.modelVersion().equals(currentModelVersion)) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_CURSOR,
                            "The model has been modified since this cursor was created",
                            null,
                            "Re-run the original query without a cursor parameter to get fresh results",
                            null);
                    return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                }

                // Use cursor params (override request params for page consistency)
                offset = cursorData.offset();
                limit = cursorData.limit();
                effectiveQuery = cursorData.params().getOrDefault("query", query);
                effectiveType = cursorData.params().get("type");
                effectiveLayer = cursorData.params().get("layer");
                effectiveSpecialization = cursorData.params().get("specialization");
                logger.debug("Decoded pagination cursor: offset={}, limit={}, modelVersion={}",
                        offset, limit, cursorData.modelVersion());
            }

            // Extract session context
            String sessionId = (sessionManager != null)
                    ? SessionManager.extractSessionId(exchange) : null;

            // Merge session filters (only for fresh queries, not cursor)
            String effectiveFieldsPreset = fieldsParam;
            Set<String> effectiveExclude = excludeParam != null ? Set.copyOf(excludeParam) : null;
            if (sessionManager != null && cursorParam == null) {
                effectiveType = sessionManager.getEffectiveType(sessionId, typeFilter);
                effectiveLayer = sessionManager.getEffectiveLayer(sessionId, layerFilter);
                effectiveFieldsPreset = sessionManager.getEffectiveFieldsPreset(sessionId, fieldsParam);
                effectiveExclude = sessionManager.getEffectiveExcludeFields(sessionId, excludeParam);
                logger.debug("Effective filters — type: {} (source: {}), layer: {} (source: {})",
                        effectiveType, typeFilter != null ? "per-query" : "session",
                        effectiveLayer, layerFilter != null ? "per-query" : "session");
            } else if (sessionManager != null) {
                // Cursor mode: still merge field selection (per-page, not in cursor)
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

            // Cache check (Story 5.4) — includes offset/limit for pagination, format for different envelopes
            // Summary format ignores pagination, so use 0 for offset/limit to share cache entries
            String cacheKey = CacheKeyBuilder.buildCacheKey("search", effectiveQuery, "type", effectiveType,
                    "layer", effectiveLayer, "specialization", effectiveSpecialization,
                    "fields", preset, "exclude", CacheKeyBuilder.sortedSetKey(effectiveExclude),
                    "offset", format == ResponseFormat.SUMMARY ? 0 : offset,
                    "limit", format == ResponseFormat.SUMMARY ? 0 : limit,
                    "format", format.value());
            if (sessionManager != null && sessionId != null && !modelChanged && !dryRun) {
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

            // Execute query (full result set)
            logger.debug("Search query: '{}', type: {}, layer: {}", effectiveQuery, effectiveType, effectiveLayer);
            List<ElementDto> allMatches = accessor.searchElements(effectiveQuery, effectiveType, effectiveLayer, effectiveSpecialization);
            int totalCount = allMatches.size();

            // Dry-run early return: estimate cost without data
            if (dryRun) {
                logger.info("Handling search-elements with dryRun=true");
                int estimatedTokens = CostEstimator.estimateTokens(totalCount, preset, CostEstimator.ItemType.ELEMENT);
                int tokensAtStandard = CostEstimator.estimateTokens(totalCount, FieldSelector.FieldPreset.STANDARD,
                        CostEstimator.ItemType.ELEMENT);
                String recommendedPreset = CostEstimator.recommendPreset(tokensAtStandard);
                String recommendation = CostEstimator.buildRecommendation(totalCount, estimatedTokens, preset);
                logger.debug("DryRun estimate: {} items, ~{} tokens at {} preset", totalCount, estimatedTokens, preset);

                List<String> dryRunNextSteps = new ArrayList<>();
                if (totalCount > 50) {
                    dryRunNextSteps.add("Add type or layer filters to narrow results (e.g., type=ApplicationComponent)");
                }
                if (estimatedTokens > CostEstimator.THRESHOLD_COMFORTABLE) {
                    dryRunNextSteps.add("Use fields=minimal to reduce token usage");
                }
                if (totalCount > limit) {
                    dryRunNextSteps.add("Use limit parameter for paginated retrieval (e.g., limit=50)");
                }
                if (dryRunNextSteps.isEmpty()) {
                    dryRunNextSteps.add("Execute the query without dryRun to retrieve results");
                }

                Map<String, Object> dryRunEnvelope = formatter.formatDryRun(
                        totalCount, estimatedTokens, recommendedPreset,
                        recommendation, dryRunNextSteps, modelVersion);
                if (modelChanged) {
                    ResponseFormatter.addModelChangedFlag(dryRunEnvelope);
                }
                return buildResult(formatter.toJsonString(dryRunEnvelope), false);
            }

            // Summary format: skip pagination, summarize full result set
            if (format == ResponseFormat.SUMMARY) {
                logger.info("Returning search-elements summary: total={}", totalCount);
                String summaryText = SummaryFormatter.summarizeElements(allMatches, effectiveQuery);

                List<String> summaryNextSteps;
                if (totalCount == 0) {
                    summaryNextSteps = List.of(
                            "Try broader search terms or partial words",
                            "Use get-model-info to see available element types and counts");
                } else {
                    summaryNextSteps = List.of(
                            "Re-run with format=json for full element data",
                            "Add type or layer filters to narrow results",
                            "Use get-relationships to explore connections from a found element");
                }

                Map<String, Object> envelope = formatter.formatSummary(
                        summaryText, summaryNextSteps, modelVersion, totalCount, totalCount);

                if (warningMessage != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
                    meta.put("warning", warningMessage);
                }
                if (modelChanged) {
                    ResponseFormatter.addModelChangedFlag(envelope);
                }

                String jsonResult = formatter.toJsonString(envelope);
                if (sessionManager != null && sessionId != null && !modelChanged) {
                    sessionManager.putCacheEntry(sessionId, cacheKey, jsonResult);
                }
                return buildResult(jsonResult, false);
            }

            // Validate cursor offset against actual results
            if (cursorParam != null && offset >= totalCount && totalCount > 0) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_CURSOR,
                        "The cursor position exceeds the current result count",
                        null,
                        "Re-run the original query without a cursor parameter",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Slice results for current page
            int endIndex = Math.min(offset + limit, totalCount);
            List<ElementDto> pageElements = (offset < totalCount)
                    ? allMatches.subList(offset, endIndex)
                    : List.of();
            boolean hasMore = endIndex < totalCount;

            logger.info("Returning search-elements page: offset={}, limit={}, pageSize={}, total={}, format={}",
                    offset, limit, pageElements.size(), totalCount, format.value());

            // Apply field selection (per-page, after slicing)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filteredResult =
                    (List<Map<String, Object>>) FieldSelector.applyFieldSelection(pageElements, preset, effectiveExclude);

            Map<String, Object> envelope;
            List<String> nextSteps;

            if (format == ResponseFormat.GRAPH) {
                // Graph format: elements as nodes, no edges for search results
                Map<String, Object> graphData = GraphFormatter.formatAsGraph(filteredResult);

                if (pageElements.isEmpty()) {
                    nextSteps = List.of(
                            "Try broader search terms or partial words",
                            "Use get-model-info to see available element types and counts");
                } else if (hasMore) {
                    nextSteps = List.of(
                            "Use cursor parameter to retrieve next page",
                            "Use get-relationships with format=graph for edge data",
                            "Use get-element with a specific ID for full element details");
                } else {
                    nextSteps = List.of(
                            "Use get-relationships with format=graph for edge data",
                            "Use get-element with a specific ID for full element details");
                }

                envelope = formatter.formatGraph(graphData, nextSteps, modelVersion,
                        pageElements.size(), 0, totalCount, hasMore);
            } else {
                // JSON format (default)
                if (pageElements.isEmpty()) {
                    nextSteps = List.of(
                            "Try broader search terms or partial words",
                            "Use get-model-info to see available element types and counts");
                } else if (hasMore) {
                    nextSteps = List.of(
                            "Use cursor parameter to retrieve next page",
                            "Use get-element with a specific ID for full element details",
                            "Use get-relationships to explore connections from a found element");
                } else {
                    nextSteps = List.of(
                            "Use get-element with a specific ID for full element details",
                            "Use get-relationships to explore connections from a found element");
                }

                envelope = formatter.formatSuccess(
                        filteredResult, nextSteps, modelVersion,
                        pageElements.size(), totalCount, hasMore);
            }

            // Add cursor token if more pages available
            if (hasMore) {
                Map<String, String> cursorParams = new LinkedHashMap<>();
                cursorParams.put("query", effectiveQuery);
                cursorParams.put("type", effectiveType);
                cursorParams.put("layer", effectiveLayer);
                cursorParams.put("specialization", effectiveSpecialization);
                String nextCursor = PaginationCursor.encode(
                        modelVersion, offset + limit, limit, totalCount, cursorParams);
                ResponseFormatter.addCursorToken(envelope, nextCursor);
            }

            // Add warning to _meta if preset fallback occurred (AC #5)
            if (warningMessage != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
                meta.put("warning", warningMessage);
            }

            // Add modelChanged flag if version changed (Story 5.3) — before serialization
            if (modelChanged) {
                ResponseFormatter.addModelChangedFlag(envelope);
            }

            String jsonResult = formatter.toJsonString(envelope);

            // Store in cache (Story 5.4) — skip when model changed (cache already invalidated)
            if (sessionManager != null && sessionId != null && !modelChanged) {
                sessionManager.putCacheEntry(sessionId, cacheKey, jsonResult);
                logger.debug("Cache miss, storing result for key: {}", cacheKey);
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
            logger.error("Unexpected error handling search-elements", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while searching elements");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    // ---- search-relationships (Story C1) ----

    private McpServerFeatures.SyncToolSpecification buildSearchRelationshipsSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description",
                "Text to search for across relationship names, documentation, "
                + "and property values (case-insensitive substring match). "
                + "Use an empty string to return all relationships "
                + "(subject to pagination and session filters).");
        properties.put("query", queryProp);

        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("description",
                "Optional ArchiMate relationship type filter (e.g., "
                + "'FlowRelationship', 'ServingRelationship', 'AccessRelationship'). "
                + "Only relationships of this type are returned.");
        properties.put("type", typeProp);

        Map<String, Object> sourceLayerProp = new LinkedHashMap<>();
        sourceLayerProp.put("type", "string");
        sourceLayerProp.put("description",
                "Optional filter by ArchiMate layer of the SOURCE element "
                + "(e.g., 'Business', 'Application'). "
                + "Only relationships where the source element is in this layer are returned.");
        properties.put("sourceLayer", sourceLayerProp);

        Map<String, Object> targetLayerProp = new LinkedHashMap<>();
        targetLayerProp.put("type", "string");
        targetLayerProp.put("description",
                "Optional filter by ArchiMate layer of the TARGET element "
                + "(e.g., 'Technology', 'Application'). "
                + "Only relationships where the target element is in this layer are returned. "
                + "Combine with sourceLayer to find cross-layer relationships "
                + "(e.g., sourceLayer='Business', targetLayer='Application').");
        properties.put("targetLayer", targetLayerProp);

        Map<String, Object> relSpecProp = new LinkedHashMap<>();
        relSpecProp.put("type", "string");
        relSpecProp.put("description",
                "Optional specialization name filter (exact match, case-insensitive). "
                + "Only relationships with this primary specialization are returned. "
                + "Use list-specializations to see available specializations.");
        properties.put("specialization", relSpecProp);

        Map<String, Object> fieldsProp = new LinkedHashMap<>();
        fieldsProp.put("type", "string");
        fieldsProp.put("description", "Field verbosity preset for relationship data. "
                + "'minimal' returns only id and name. "
                + "'standard' (default) returns id, name, type, sourceId, targetId. "
                + "'full' returns all fields including specialization, documentation, properties, sourceName, targetName.");
        fieldsProp.put("enum", List.of("minimal", "standard", "full"));
        properties.put("fields", fieldsProp);

        Map<String, Object> excludeProp = new LinkedHashMap<>();
        excludeProp.put("type", "array");
        Map<String, Object> excludeItems = new LinkedHashMap<>();
        excludeItems.put("type", "string");
        excludeProp.put("items", excludeItems);
        excludeProp.put("description", "Fields to exclude from relationship data. "
                + "Applied after fields preset. "
                + "Valid values: documentation, properties, type. "
                + "Note: id and name cannot be excluded.");
        properties.put("exclude", excludeProp);

        Map<String, Object> limitProp = new LinkedHashMap<>();
        limitProp.put("type", "integer");
        limitProp.put("description", "Maximum number of results per page (1-"
                + PaginationCursor.MAX_PAGE_SIZE + "). Default: " + DEFAULT_SEARCH_LIMIT
                + ". Use with cursor for pagination.");
        properties.put("limit", limitProp);

        Map<String, Object> cursorProp = new LinkedHashMap<>();
        cursorProp.put("type", "string");
        cursorProp.put("description", "Pagination cursor from a previous response's "
                + "_meta.cursor. When provided, retrieves the next page of results. "
                + "Cursor parameters override query/type/layer parameters.");
        properties.put("cursor", cursorProp);

        Map<String, Object> dryRunProp = new LinkedHashMap<>();
        dryRunProp.put("type", "boolean");
        dryRunProp.put("description", "Set to true to get a cost estimate without returning results. "
                + "Returns estimated result count, token size, and recommended field preset.");
        properties.put("dryRun", dryRunProp);

        Map<String, Object> formatProp = new LinkedHashMap<>();
        formatProp.put("type", "string");
        formatProp.put("description", "Response format. 'json' (default) returns standard result array. "
                + "'graph' returns nodes/edges structure (relationships as edges, "
                + "source/target elements as nodes). "
                + "'summary' returns condensed natural language overview with type distributions.");
        formatProp.put("enum", List.of("json", "graph", "summary"));
        properties.put("format", formatProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("query"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("search-relationships")
                .description("[Search] Search across ALL ArchiMate relationships in the model by text, "
                        + "type, and source/target element layer. No element ID needed. "
                        + "Matches against relationship names, documentation, and property values "
                        + "(case-insensitive substring match). "
                        + "Use query='' to list all relationships. "
                        + "Use type filter to find specific relationship types (e.g., type='FlowRelationship'). "
                        + "Use sourceLayer/targetLayer to find cross-layer relationships "
                        + "(e.g., sourceLayer='Business', targetLayer='Application'). "
                        + "Results are paginated if they exceed the limit. "
                        + "Use fields='full' to include documentation, properties, and resolved source/target names. "
                        + "WHEN TO USE: Find relationships by text/type/layer across the entire model. "
                        + "Supports optional specialization filter for exact-match filtering by specialization name. "
                        + "USE INSTEAD: get-relationships when you have a specific element ID and want its connections.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleSearchRelationships)
                .build();
    }

    private McpSchema.CallToolResult handleSearchRelationships(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling search-relationships request");
        try {
            // Extract and validate required query parameter
            Object queryObj = (request.arguments() != null) ? request.arguments().get("query") : null;
            if (!(queryObj instanceof String query)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'query' parameter is required and must be a string",
                        null,
                        "Provide a search term (e.g. query: 'data transfer') or use an empty string (query: '') to list all relationships",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Extract optional type filter
            String typeFilter = null;
            if (request.arguments() != null) {
                Object typeObj = request.arguments().get("type");
                if (typeObj instanceof String t && !t.isBlank()) {
                    typeFilter = t;
                }
            }

            // Validate type if provided
            if (typeFilter != null && !TraversalHandler.VALID_RELATIONSHIP_TYPES.contains(typeFilter)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Invalid relationship type: '" + typeFilter + "'",
                        "The 'type' parameter must be a valid ArchiMate relationship type name",
                        "Valid types: AccessRelationship, AggregationRelationship, "
                        + "AssignmentRelationship, AssociationRelationship, CompositionRelationship, "
                        + "FlowRelationship, InfluenceRelationship, RealizationRelationship, "
                        + "ServingRelationship, SpecializationRelationship, TriggeringRelationship",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Extract optional source layer filter
            String sourceLayerFilter = null;
            if (request.arguments() != null) {
                Object layerObj = request.arguments().get("sourceLayer");
                if (layerObj instanceof String l && !l.isBlank()) {
                    sourceLayerFilter = l;
                }
            }

            // Validate source layer if provided
            if (sourceLayerFilter != null && !VALID_LAYERS.contains(sourceLayerFilter)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Invalid sourceLayer: '" + sourceLayerFilter + "'",
                        "The 'sourceLayer' parameter must be a valid ArchiMate layer name",
                        "Valid layers: Business, Application, Technology, Physical, "
                        + "Strategy, Motivation, Implementation & Migration",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Extract optional target layer filter
            String targetLayerFilter = null;
            if (request.arguments() != null) {
                Object layerObj = request.arguments().get("targetLayer");
                if (layerObj instanceof String l && !l.isBlank()) {
                    targetLayerFilter = l;
                }
            }

            // Validate target layer if provided
            if (targetLayerFilter != null && !VALID_LAYERS.contains(targetLayerFilter)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Invalid targetLayer: '" + targetLayerFilter + "'",
                        "The 'targetLayer' parameter must be a valid ArchiMate layer name",
                        "Valid layers: Business, Application, Technology, Physical, "
                        + "Strategy, Motivation, Implementation & Migration",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Extract optional specialization filter (blank treated as no filter)
            String specializationFilter = null;
            if (request.arguments() != null) {
                Object specObj = request.arguments().get("specialization");
                if (specObj instanceof String s && !s.isBlank()) {
                    specializationFilter = s;
                }
            }

            // Extract field selection parameters
            String fieldsParam = null;
            List<String> excludeParam = null;
            if (request.arguments() != null) {
                Object fieldsObj = request.arguments().get("fields");
                if (fieldsObj instanceof String f && !f.isBlank()) {
                    fieldsParam = f;
                }
                Object excludeObj = request.arguments().get("exclude");
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
                                "Valid exclude fields: documentation, properties, type, specialization",
                                null);
                        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                    }
                }
            }

            // Extract dryRun parameter
            boolean dryRun = false;
            if (request.arguments() != null) {
                Object dryRunObj = request.arguments().get("dryRun");
                if (dryRunObj instanceof Boolean b) {
                    dryRun = b;
                }
            }

            // Extract and validate format parameter
            ResponseFormat format = ResponseFormat.JSON;
            if (request.arguments() != null) {
                Object formatObj = request.arguments().get("format");
                if (formatObj instanceof String f && !f.isBlank()) {
                    ResponseFormat parsed = ResponseFormat.fromString(f);
                    if (parsed == null) {
                        ErrorResponse error = new ErrorResponse(
                                ErrorCode.INVALID_PARAMETER,
                                "Invalid format: '" + f + "'",
                                null,
                                "Valid formats: json, graph, summary",
                                null);
                        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                    }
                    format = parsed;
                }
            }

            // Extract pagination parameters
            String cursorParam = null;
            Integer limitParam = null;
            if (request.arguments() != null) {
                Object cursorObj = request.arguments().get("cursor");
                if (cursorObj instanceof String c && !c.isBlank()) {
                    cursorParam = c;
                }
                Object limitObj = request.arguments().get("limit");
                if (limitObj instanceof Number n) {
                    limitParam = n.intValue();
                }
            }

            // Validate limit parameter
            if (limitParam != null && (limitParam < 1 || limitParam > PaginationCursor.MAX_PAGE_SIZE)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'limit' parameter must be between 1 and " + PaginationCursor.MAX_PAGE_SIZE,
                        null,
                        "Provide a limit between 1 and " + PaginationCursor.MAX_PAGE_SIZE
                                + ", or omit for default (" + DEFAULT_SEARCH_LIMIT + ")",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Cursor mode: decode and validate cursor
            int offset = 0;
            int limit = (limitParam != null) ? limitParam : DEFAULT_SEARCH_LIMIT;
            String effectiveQuery = query;
            String effectiveType = typeFilter;
            String effectiveSourceLayer = sourceLayerFilter;
            String effectiveTargetLayer = targetLayerFilter;
            String effectiveSpecialization = specializationFilter;

            if (cursorParam != null && !dryRun) {
                PaginationCursor.CursorData cursorData;
                try {
                    cursorData = PaginationCursor.decode(cursorParam);
                } catch (PaginationCursor.InvalidCursorException e) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_CURSOR,
                            e.getMessage(),
                            null,
                            "Re-run the original query without a cursor parameter",
                            null);
                    return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                }

                // Validate model version
                String currentModelVersion = accessor.getModelVersion();
                if (!cursorData.modelVersion().equals(currentModelVersion)) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_CURSOR,
                            "The model has been modified since this cursor was created",
                            null,
                            "Re-run the original query without a cursor parameter to get fresh results",
                            null);
                    return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                }

                offset = cursorData.offset();
                limit = cursorData.limit();
                effectiveQuery = cursorData.params().getOrDefault("query", query);
                effectiveType = cursorData.params().get("type");
                effectiveSourceLayer = cursorData.params().get("sourceLayer");
                effectiveTargetLayer = cursorData.params().get("targetLayer");
                effectiveSpecialization = cursorData.params().get("specialization");
                logger.debug("Decoded pagination cursor: offset={}, limit={}, modelVersion={}",
                        offset, limit, cursorData.modelVersion());
            }

            // Extract session context
            String sessionId = (sessionManager != null)
                    ? SessionManager.extractSessionId(exchange) : null;

            // Merge session filters (only for fresh queries, not cursor)
            String effectiveFieldsPreset = fieldsParam;
            Set<String> effectiveExclude = excludeParam != null ? Set.copyOf(excludeParam) : null;
            if (sessionManager != null && cursorParam == null) {
                // Skip session type merging: session type stores element types (e.g., ApplicationComponent)
                // which are not valid relationship types — applying them would silently return 0 results
                // Note: session layer filter applies to sourceLayer (closest semantic match)
                if (sourceLayerFilter == null) {
                    String sessionLayer = sessionManager.getEffectiveLayer(sessionId, null);
                    if (sessionLayer != null) {
                        effectiveSourceLayer = sessionLayer;
                    }
                }
                effectiveFieldsPreset = sessionManager.getEffectiveFieldsPreset(sessionId, fieldsParam);
                effectiveExclude = sessionManager.getEffectiveExcludeFields(sessionId, excludeParam);
            } else if (sessionManager != null) {
                effectiveFieldsPreset = sessionManager.getEffectiveFieldsPreset(sessionId, fieldsParam);
                effectiveExclude = sessionManager.getEffectiveExcludeFields(sessionId, excludeParam);
            }

            // Parse preset with fallback
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

            String modelVersion = accessor.getModelVersion();

            // Model version change detection
            boolean modelChanged = false;
            if (sessionManager != null && sessionId != null) {
                modelChanged = sessionManager.checkModelVersionChanged(sessionId, modelVersion);
                if (modelChanged) {
                    sessionManager.invalidateSessionCache(sessionId);
                }
            }

            // Cache check
            String cacheKey = CacheKeyBuilder.buildCacheKey("search-rel", effectiveQuery,
                    "type", effectiveType,
                    "sourceLayer", effectiveSourceLayer, "targetLayer", effectiveTargetLayer,
                    "specialization", effectiveSpecialization,
                    "fields", preset, "exclude", CacheKeyBuilder.sortedSetKey(effectiveExclude),
                    "offset", format == ResponseFormat.SUMMARY ? 0 : offset,
                    "limit", format == ResponseFormat.SUMMARY ? 0 : limit,
                    "format", format.value());
            if (sessionManager != null && sessionId != null && !modelChanged && !dryRun) {
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

            // Execute query (full result set)
            logger.debug("Search relationships query: '{}', type: {}, sourceLayer: {}, targetLayer: {}",
                    effectiveQuery, effectiveType, effectiveSourceLayer, effectiveTargetLayer);
            List<RelationshipDto> allMatches = accessor.searchRelationships(
                    effectiveQuery, effectiveType, effectiveSourceLayer, effectiveTargetLayer,
                    effectiveSpecialization);
            int totalCount = allMatches.size();

            // Dry-run early return
            if (dryRun) {
                logger.info("Handling search-relationships with dryRun=true");
                int estimatedTokens = CostEstimator.estimateTokens(totalCount, preset, CostEstimator.ItemType.RELATIONSHIP);
                int tokensAtStandard = CostEstimator.estimateTokens(totalCount, FieldSelector.FieldPreset.STANDARD,
                        CostEstimator.ItemType.RELATIONSHIP);
                String recommendedPreset = CostEstimator.recommendPreset(tokensAtStandard);
                String recommendation = CostEstimator.buildRecommendation(totalCount, estimatedTokens, preset);

                List<String> dryRunNextSteps = new ArrayList<>();
                if (totalCount > 50) {
                    dryRunNextSteps.add("Add type or layer filters to narrow results (e.g., type=FlowRelationship)");
                }
                if (estimatedTokens > CostEstimator.THRESHOLD_COMFORTABLE) {
                    dryRunNextSteps.add("Use fields=minimal to reduce token usage");
                }
                if (totalCount > limit) {
                    dryRunNextSteps.add("Use limit parameter for paginated retrieval (e.g., limit=50)");
                }
                if (dryRunNextSteps.isEmpty()) {
                    dryRunNextSteps.add("Execute the query without dryRun to retrieve results");
                }

                Map<String, Object> dryRunEnvelope = formatter.formatDryRun(
                        totalCount, estimatedTokens, recommendedPreset,
                        recommendation, dryRunNextSteps, modelVersion);
                if (modelChanged) {
                    ResponseFormatter.addModelChangedFlag(dryRunEnvelope);
                }
                return buildResult(formatter.toJsonString(dryRunEnvelope), false);
            }

            // Summary format: skip pagination, summarize full result set
            if (format == ResponseFormat.SUMMARY) {
                logger.info("Returning search-relationships summary: total={}", totalCount);
                String summaryText = SummaryFormatter.summarizeRelationshipSearch(allMatches, effectiveQuery);

                List<String> summaryNextSteps;
                if (totalCount == 0) {
                    summaryNextSteps = List.of(
                            "Try broader search terms or partial words",
                            "Use search-elements to find elements, then get-relationships for their connections");
                } else {
                    summaryNextSteps = List.of(
                            "Re-run with format=json for full relationship data",
                            "Add type or sourceLayer/targetLayer filters to narrow results",
                            "Use get-relationships with a specific elementId for traversal and depth expansion");
                }

                Map<String, Object> envelope = formatter.formatSummary(
                        summaryText, summaryNextSteps, modelVersion, totalCount, totalCount);

                if (warningMessage != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
                    meta.put("warning", warningMessage);
                }
                if (modelChanged) {
                    ResponseFormatter.addModelChangedFlag(envelope);
                }

                String jsonResult = formatter.toJsonString(envelope);
                if (sessionManager != null && sessionId != null && !modelChanged) {
                    sessionManager.putCacheEntry(sessionId, cacheKey, jsonResult);
                }
                return buildResult(jsonResult, false);
            }

            // Validate cursor offset against actual results
            if (cursorParam != null && offset >= totalCount && totalCount > 0) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_CURSOR,
                        "The cursor position exceeds the current result count",
                        null,
                        "Re-run the original query without a cursor parameter",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Slice results for current page
            int endIndex = Math.min(offset + limit, totalCount);
            List<RelationshipDto> pageResults = (offset < totalCount)
                    ? allMatches.subList(offset, endIndex)
                    : List.of();
            boolean hasMore = endIndex < totalCount;

            logger.info("Returning search-relationships page: offset={}, limit={}, pageSize={}, total={}, format={}",
                    offset, limit, pageResults.size(), totalCount, format.value());

            // Apply field selection (per-page, after slicing)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filteredResult =
                    (List<Map<String, Object>>) FieldSelector.applyFieldSelection(pageResults, preset, effectiveExclude);

            Map<String, Object> envelope;
            List<String> nextSteps;

            if (format == ResponseFormat.GRAPH) {
                // Graph format: relationships as edges, source/target elements as nodes
                Map<String, Object> graphData = GraphFormatter.formatRelationshipsAsGraph(filteredResult);
                int graphNodeCount = ((List<?>) graphData.get("nodes")).size();

                if (pageResults.isEmpty()) {
                    nextSteps = List.of(
                            "Try broader search terms or partial words",
                            "Use search-elements to find elements first");
                } else if (hasMore) {
                    nextSteps = List.of(
                            "Use cursor parameter to retrieve next page",
                            "Use get-element to get full details of connected elements");
                } else {
                    nextSteps = List.of(
                            "Use get-element to get full details of connected elements",
                            "Use get-relationships with a specific elementId for traversal");
                }

                envelope = formatter.formatGraph(graphData, nextSteps, modelVersion,
                        graphNodeCount, pageResults.size(), totalCount, hasMore);
            } else {
                // JSON format (default)
                if (pageResults.isEmpty()) {
                    nextSteps = List.of(
                            "Try broader search terms or partial words",
                            "Use search-elements to find elements, then get-relationships for their connections");
                } else if (hasMore) {
                    nextSteps = List.of(
                            "Use cursor parameter to retrieve next page",
                            "Use get-element to get full details of source/target elements",
                            "Use get-relationships with elementId for traversal and depth expansion");
                } else {
                    nextSteps = List.of(
                            "Use get-element to get full details of source/target elements",
                            "Use get-relationships with elementId for traversal and depth expansion");
                }

                envelope = formatter.formatSuccess(
                        filteredResult, nextSteps, modelVersion,
                        pageResults.size(), totalCount, hasMore);
            }

            // Add cursor token if more pages available
            if (hasMore) {
                Map<String, String> cursorParams = new LinkedHashMap<>();
                cursorParams.put("query", effectiveQuery);
                cursorParams.put("type", effectiveType);
                cursorParams.put("sourceLayer", effectiveSourceLayer);
                cursorParams.put("targetLayer", effectiveTargetLayer);
                cursorParams.put("specialization", effectiveSpecialization);
                String nextCursor = PaginationCursor.encode(
                        modelVersion, offset + limit, limit, totalCount, cursorParams);
                ResponseFormatter.addCursorToken(envelope, nextCursor);
            }

            if (warningMessage != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
                meta.put("warning", warningMessage);
            }
            if (modelChanged) {
                ResponseFormatter.addModelChangedFlag(envelope);
            }

            String jsonResult = formatter.toJsonString(envelope);

            if (sessionManager != null && sessionId != null && !modelChanged) {
                sessionManager.putCacheEntry(sessionId, cacheKey, jsonResult);
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
            logger.error("Unexpected error handling search-relationships", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while searching relationships");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    private McpSchema.CallToolResult buildResult(String json, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(isError)
                .build();
    }
}
