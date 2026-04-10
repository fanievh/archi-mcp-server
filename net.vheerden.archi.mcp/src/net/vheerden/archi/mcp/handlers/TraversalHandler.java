package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import net.vheerden.archi.mcp.response.ResponseFormat;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.SummaryFormatter;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for relationship traversal tools: get-relationships (Stories 4.1, 4.2, 4.3).
 *
 * <p>This handler follows the same pattern established by
 * {@link ModelQueryHandler}, {@link ViewHandler}, and {@link SearchHandler}.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF or ArchimateTool model types. All model access goes through
 * {@link ArchiModelAccessor}.</p>
 */
public class TraversalHandler {

    private static final Logger logger = LoggerFactory.getLogger(TraversalHandler.class);

    private static final int MIN_DEPTH = 0;
    private static final int MAX_DEPTH = 3;
    private static final int DEFAULT_DEPTH = 1;
    private static final int MAX_DEPTH3_EXPANSION = 50;
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    /**
     * Valid ArchiMate relationship type names matching EMF {@code eClass().getName()}.
     * Confirmed via javap against com.archimatetool.model_5.8.0 (Story 4.3 Task 0).
     */
    static final Set<String> VALID_RELATIONSHIP_TYPES = Set.of(
            "AccessRelationship",
            "AggregationRelationship",
            "AssignmentRelationship",
            "AssociationRelationship",
            "CompositionRelationship",
            "FlowRelationship",
            "InfluenceRelationship",
            "RealizationRelationship",
            "ServingRelationship",
            "SpecializationRelationship",
            "TriggeringRelationship");

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;
    private final TraversalEngine traversalEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a TraversalHandler with its required dependencies.
     *
     * @param accessor       the model accessor for querying ArchiMate data
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager for session-scoped filters, may be null
     */
    public TraversalHandler(ArchiModelAccessor accessor,
                            ResponseFormatter formatter,
                            CommandRegistry registry,
                            SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager; // nullable for backward compat
        this.traversalEngine = new TraversalEngine(accessor);
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     * Registers: get-relationships (Stories 4.1, 4.2, 4.3).
     */
    public void registerTools() {
        registry.registerTool(buildGetRelationshipsSpec());
    }

    private McpServerFeatures.SyncToolSpecification buildGetRelationshipsSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> elementIdProp = new LinkedHashMap<>();
        elementIdProp.put("type", "string");
        elementIdProp.put("description", "The unique identifier of the ArchiMate element "
                + "to retrieve relationships for.");
        properties.put("elementId", elementIdProp);

        Map<String, Object> depthProp = new LinkedHashMap<>();
        depthProp.put("type", "integer");
        depthProp.put("description", "Level of detail for related elements. "
                + "0: relationship IDs only, "
                + "1: element summaries (default), "
                + "2: full element details, "
                + "3: full details with nested relationships. "
                + "Ignored when traverse is true.");
        properties.put("depth", depthProp);

        Map<String, Object> traverseProp = new LinkedHashMap<>();
        traverseProp.put("type", "boolean");
        traverseProp.put("description", "Enable multi-hop traversal to follow relationship chains "
                + "transitively. When true, follows relationships across multiple hops up to maxDepth. "
                + "When false (default), returns only direct relationships.");
        properties.put("traverse", traverseProp);

        Map<String, Object> maxDepthProp = new LinkedHashMap<>();
        maxDepthProp.put("type", "integer");
        maxDepthProp.put("description", "Maximum number of hops for traversal (1-5). "
                + "Only used when traverse is true. Default: 3.");
        properties.put("maxDepth", maxDepthProp);

        Map<String, Object> directionProp = new LinkedHashMap<>();
        directionProp.put("type", "string");
        directionProp.put("description", "Direction to follow relationships during traversal. "
                + "'outgoing': follow from source to target. "
                + "'incoming': follow from target to source. "
                + "'both': follow in both directions (default). "
                + "Only used when traverse is true.");
        properties.put("direction", directionProp);

        // Story 4.3: Filter parameters
        Map<String, Object> excludeTypesProp = new LinkedHashMap<>();
        excludeTypesProp.put("type", "array");
        Map<String, Object> excludeItemsProp = new LinkedHashMap<>();
        excludeItemsProp.put("type", "string");
        excludeTypesProp.put("items", excludeItemsProp);
        excludeTypesProp.put("description", "Relationship types to exclude from results "
                + "and traversal. Blacklist approach: relationships of these types are "
                + "filtered out. Example: [\"AssociationRelationship\", \"InfluenceRelationship\"].");
        properties.put("excludeTypes", excludeTypesProp);

        Map<String, Object> includeTypesProp = new LinkedHashMap<>();
        includeTypesProp.put("type", "array");
        Map<String, Object> includeItemsProp = new LinkedHashMap<>();
        includeItemsProp.put("type", "string");
        includeTypesProp.put("items", includeItemsProp);
        includeTypesProp.put("description", "Relationship types to include (whitelist). "
                + "Only relationships of these types are returned. "
                + "Example: [\"ServingRelationship\", \"RealizationRelationship\"].");
        properties.put("includeTypes", includeTypesProp);

        Map<String, Object> filterLayerProp = new LinkedHashMap<>();
        filterLayerProp.put("type", "string");
        filterLayerProp.put("description", "Filter by the connected element's ArchiMate layer. "
                + "Only relationships where the connected element belongs to this layer "
                + "are included. Valid values: Business, Application, Technology, Physical, "
                + "Strategy, Motivation, Implementation & Migration.");
        properties.put("filterLayer", filterLayerProp);

        Map<String, Object> fieldsProp = new LinkedHashMap<>();
        fieldsProp.put("type", "string");
        fieldsProp.put("description", "Field verbosity preset for expanded element data within relationships. "
                + "'minimal' returns only id and name. "
                + "'standard' (default) returns id, name, type, layer, documentation, properties. "
                + "'full' returns all available fields. "
                + "Applies to depth 1+ element expansions.");
        fieldsProp.put("enum", List.of("minimal", "standard", "full"));
        properties.put("fields", fieldsProp);

        Map<String, Object> excludeProp = new LinkedHashMap<>();
        excludeProp.put("type", "array");
        Map<String, Object> excludeFieldItems = new LinkedHashMap<>();
        excludeFieldItems.put("type", "string");
        excludeProp.put("items", excludeFieldItems);
        excludeProp.put("description", "Fields to exclude from expanded element data. "
                + "Applied after fields preset. "
                + "Valid values: documentation, properties, layer, type, "
                + "viewpointType, folderPath, visualMetadata. "
                + "Note: id and name cannot be excluded.");
        properties.put("exclude", excludeProp);

        Map<String, Object> dryRunProp = new LinkedHashMap<>();
        dryRunProp.put("type", "boolean");
        dryRunProp.put("description", "When true, returns a cost estimate (item count, "
                + "estimated tokens, recommended preset) without returning actual data. "
                + "Use to preview query cost before executing. Ignores cursor/limit.");
        properties.put("dryRun", dryRunProp);

        Map<String, Object> formatProp = new LinkedHashMap<>();
        formatProp.put("type", "string");
        formatProp.put("description", "Response format. 'json' (default) returns standard result structure. "
                + "'graph' returns deduplicated nodes/edges (depth mode: elements as nodes, relationships as edges; "
                + "traverse mode: flattened hops with hopLevel on edges). "
                + "'summary' returns condensed natural language overview with relationship type distributions.");
        formatProp.put("enum", List.of("json", "graph", "summary"));
        properties.put("format", formatProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("elementId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-relationships")
                .description("[Traversal] Retrieve relationships for an ArchiMate element. "
                        + "Two modes: (1) depth mode (default) — returns direct relationships "
                        + "with configurable depth 0-3 for element expansion. "
                        + "(2) traverse=true — follows relationship chains across multiple hops "
                        + "with direction filtering, cycle detection, and progress indication. "
                        + "Supports filtering by relationship type (excludeTypes/includeTypes), "
                        + "connected element layer (filterLayer), and field selection (fields/exclude). "
                        + "Set dryRun=true to get a cost estimate without returning results. "
                        + "Set format=graph for deduplicated node/edge structure, format=summary for condensed text overview. "
                        + "Relationships include specialization field showing primary specialization name (null if none). "
                        + "Related: get-element (full element details), "
                        + "search-elements (find elements by name).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetRelationships)
                .build();
    }

    private McpSchema.CallToolResult handleGetRelationships(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = request.arguments();
            logger.info(
                    "Handling get-relationships request (filters: excludeTypes={}, includeTypes={}, filterLayer={})",
                    args != null ? args.get("excludeTypes") : null,
                    args != null ? args.get("includeTypes") : null,
                    args != null ? args.get("filterLayer") : null);

            // Validate elementId
            Object elementIdObj = (args != null) ? args.get("elementId") : null;
            if (!(elementIdObj instanceof String elementId) || elementId.isBlank()) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'elementId' parameter is required and must be a non-empty string",
                        null,
                        "Provide an element ID. Use search-elements to find element IDs.",
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

            // Extract dryRun (Story 6.2)
            boolean dryRun = false;
            if (args != null) {
                Object dryRunObj = args.get("dryRun");
                if (dryRunObj instanceof Boolean b) {
                    dryRun = b;
                }
            }

            // Extract and validate format parameter
            ResponseFormat format = ResponseFormat.JSON;
            if (args != null) {
                Object formatObj = args.get("format");
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

            // Model version change detection — call ONCE (Story 5.3 + 5.4)
            String modelVersion = accessor.getModelVersion();
            boolean modelChanged = false;
            if (sessionManager != null && sessionId != null) {
                modelChanged = sessionManager.checkModelVersionChanged(sessionId, modelVersion);
                if (modelChanged) {
                    sessionManager.invalidateSessionCache(sessionId);
                    logger.warn("Model changed during session {} — version now {}", sessionId, modelVersion);
                }
            }

            // Merge session filters: per-query overrides session
            String effectiveFieldsPreset = fieldsParam;
            Set<String> effectiveExclude = excludeParam != null ? Set.copyOf(excludeParam) : null;
            if (sessionManager != null && args != null) {
                // Merge session layer filter (session type NOT applied — see Design Decision #3)
                Object filterLayerObj = args.get("filterLayer");
                String perQueryLayer = (filterLayerObj instanceof String s && !s.isBlank()) ? s : null;
                String effectiveLayer = sessionManager.getEffectiveLayer(sessionId, perQueryLayer);
                if (effectiveLayer != null && perQueryLayer == null) {
                    // Session layer applies — inject into args for downstream validation
                    args = new HashMap<>(args);
                    args.put("filterLayer", effectiveLayer);
                    logger.debug("Effective filterLayer: {} (source: session)", effectiveLayer);
                }
                effectiveFieldsPreset = sessionManager.getEffectiveFieldsPreset(sessionId, fieldsParam);
                effectiveExclude = sessionManager.getEffectiveExcludeFields(sessionId, excludeParam);
            }

            // Parse preset with fallback (AC #5)
            FieldSelector.FieldPreset fieldPreset = FieldSelector.FieldPreset.STANDARD;
            String warningMessage = null;
            if (effectiveFieldsPreset != null) {
                Optional<FieldSelector.FieldPreset> parsed = FieldSelector.FieldPreset.fromString(effectiveFieldsPreset);
                if (parsed.isPresent()) {
                    fieldPreset = parsed.get();
                } else {
                    warningMessage = "Invalid fields preset '" + effectiveFieldsPreset
                            + "', using 'standard'. Valid presets: minimal, standard, full";
                }
            }
            logger.debug("Field selection — preset: {}, exclude: {}", fieldPreset, effectiveExclude);

            // Validate filters (shared between traverse and non-traverse modes)
            RelationshipFilters filters = validateFilters(args);
            if (filters.errorResult != null) {
                return filters.errorResult;
            }

            // Check for traversal mode
            boolean traverse = false;
            Object traverseObj = (args != null) ? args.get("traverse") : null;
            if (traverseObj != null) {
                if (!(traverseObj instanceof Boolean traverseBool)) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "The 'traverse' parameter must be a boolean (true/false)",
                            null,
                            "Set traverse to true to enable chain traversal",
                            null);
                    return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                }
                traverse = traverseBool;
            }

            if (traverse) {
                return handleTraversal(exchange, request, elementId, args, filters, warningMessage,
                        fieldPreset, effectiveExclude, sessionId, modelChanged, modelVersion, dryRun, format);
            }

            // Validate depth
            int depth = DEFAULT_DEPTH;
            Object depthObj = (args != null) ? args.get("depth") : null;
            if (depthObj != null) {
                if (!(depthObj instanceof Number depthNum)) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "The 'depth' parameter must be an integer between "
                                    + MIN_DEPTH + " and " + MAX_DEPTH,
                            null,
                            "Valid depth values: 0 (IDs only), 1 (summaries, default), "
                                    + "2 (full details), 3 (full with nested relationships)",
                            null);
                    return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                }
                depth = depthNum.intValue();
                if (depth < MIN_DEPTH || depth > MAX_DEPTH) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "The 'depth' parameter must be between " + MIN_DEPTH
                                    + " and " + MAX_DEPTH + ", got: " + depth,
                            null,
                            "Valid depth values: 0 (IDs only), 1 (summaries, default), "
                                    + "2 (full details), 3 (full with nested relationships)",
                            null);
                    return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                }
            }

            // Cache check (Story 5.4) — skip if version just changed or dryRun
            String cacheKey = CacheKeyBuilder.buildCacheKey("relationships-depth", elementId, "depth", depth,
                    "excl-types", CacheKeyBuilder.sortedSetKey(filters.excludeTypes),
                    "incl-types", CacheKeyBuilder.sortedSetKey(filters.includeTypes),
                    "filter-layer", filters.filterLayer,
                    "fields", fieldPreset, "exclude", CacheKeyBuilder.sortedSetKey(effectiveExclude),
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

            // Check element existence
            Optional<ElementDto> queriedElement = accessor.getElementById(elementId);
            if (queriedElement.isEmpty()) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.ELEMENT_NOT_FOUND,
                        "No element found with ID '" + elementId + "'",
                        null,
                        "Use search-elements to find elements by name or type",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Get relationships
            List<RelationshipDto> relationships = accessor.getRelationshipsForElement(elementId);

            // Apply type filters (Story 4.3)
            relationships = applyTypeFilters(relationships, filters);

            // Apply layer filter (Story 4.3)
            if (filters.filterLayer != null) {
                relationships = applyLayerFilter(relationships, elementId, filters.filterLayer);
            }

            // Dry-run early return for depth mode (Story 6.2)
            if (dryRun) {
                logger.info("Dry-run depth mode: {} relationships after filters, depth={}", relationships.size(), depth);
                int estimatedTokens = CostEstimator.estimateTokensForDepth(relationships.size(), depth);
                String recommendedPreset = CostEstimator.recommendPreset(estimatedTokens);
                String recommendation = CostEstimator.buildRecommendation(
                        relationships.size(), estimatedTokens, fieldPreset);
                List<String> dryRunNextSteps = new ArrayList<>();
                dryRunNextSteps.add("Execute without dryRun to get full results");
                if (relationships.size() > 20) {
                    dryRunNextSteps.add("Consider adding excludeTypes or includeTypes filters to narrow results");
                }
                if (estimatedTokens > CostEstimator.THRESHOLD_COMFORTABLE && depth > 1) {
                    dryRunNextSteps.add("Consider using a lower depth value to reduce response size");
                }
                Map<String, Object> dryRunEnvelope = formatter.formatDryRun(
                        relationships.size(), estimatedTokens, recommendedPreset,
                        recommendation, dryRunNextSteps, modelVersion);
                if (modelChanged) {
                    ResponseFormatter.addModelChangedFlag(dryRunEnvelope);
                }
                return buildResult(formatter.toJsonString(dryRunEnvelope), false);
            }

            // Summary format: generate text summary from raw DTOs (before field selection)
            if (format == ResponseFormat.SUMMARY) {
                logger.info("Returning get-relationships depth summary: relationships={}", relationships.size());
                String summaryText = SummaryFormatter.summarizeDepthRelationships(
                        relationships, queriedElement.get());

                List<String> summaryNextSteps = relationships.isEmpty()
                        ? List.of(
                                "Use search-elements to find related elements by name",
                                "Use get-model-info to see model structure overview")
                        : List.of(
                                "Re-run with format=json for full relationship data",
                                "Re-run with format=graph for deduplicated node/edge structure",
                                "Use get-element for full details on a related element");

                Map<String, Object> envelope = formatter.formatSummary(
                        summaryText, summaryNextSteps, modelVersion,
                        relationships.size(), relationships.size());

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

            // Build response based on depth
            Object result;
            if (depth == 0) {
                // Depth 0: relationship DTOs only — apply field selection to relationships
                result = FieldSelector.applyFieldSelection(relationships, fieldPreset, effectiveExclude);
            } else {
                result = buildExpandedResult(relationships, queriedElement.get(), depth,
                        fieldPreset, effectiveExclude);
            }

            Map<String, Object> envelope;
            List<String> nextSteps;

            if (format == ResponseFormat.GRAPH) {
                // Graph format: deduplicated nodes + edges
                Map<String, Object> graphData;
                if (depth == 0) {
                    // Depth 0 graph: build edges from raw DTOs — field selection applies
                    // to nodes only (not edges), so graph edges always retain structural fields
                    List<Map<String, Object>> rawEdges = new ArrayList<>();
                    for (RelationshipDto rel : relationships) {
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("id", rel.id());
                        edge.put("type", rel.type());
                        edge.put("name", rel.name());
                        edge.put("sourceId", rel.sourceId());
                        edge.put("targetId", rel.targetId());
                        rawEdges.add(edge);
                    }
                    graphData = GraphFormatter.formatDepth0AsGraph(rawEdges);
                } else {
                    // Depth 1+: extract nodes from expanded source/target elements
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> expandedRels = (List<Map<String, Object>>) result;
                    graphData = GraphFormatter.formatDepthModeAsGraph(expandedRels);
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> graphNodes =
                        (List<Map<String, Object>>) graphData.get("nodes");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> graphEdges =
                        (List<Map<String, Object>>) graphData.get("edges");

                nextSteps = relationships.isEmpty()
                        ? List.of(
                                "Use search-elements to find related elements by name",
                                "Use get-model-info to see model structure overview")
                        : List.of(
                                "Use get-element for full details on a graph node",
                                "Use search-elements to discover more elements");

                envelope = formatter.formatGraph(graphData, nextSteps, modelVersion,
                        graphNodes.size(), graphEdges.size(), relationships.size(), false);
            } else {
                // JSON format (default)
                nextSteps = relationships.isEmpty()
                        ? List.of(
                                "Use search-elements to find related elements by name",
                                "Use get-model-info to see model structure overview")
                        : List.of(
                                "Use get-element for full details on a related element",
                                "Use search-elements to discover more elements");

                envelope = formatter.formatSuccess(
                        result, nextSteps, modelVersion,
                        relationships.size(), relationships.size(), false);
            }

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

            // Store in cache (Story 5.4) — skip when model changed to avoid stale entries
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
            logger.error("Unexpected error handling get-relationships", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while retrieving relationships");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    // ---- Filter Validation and Application (Story 4.3) ----

    /**
     * Validates excludeTypes, includeTypes, and filterLayer parameters.
     * Returns a {@link RelationshipFilters} with either parsed filter values
     * or an error result if validation fails.
     */
    private RelationshipFilters validateFilters(Map<String, Object> args) {
        Set<String> excludeTypes = null;
        Set<String> includeTypes = null;
        String filterLayer = null;

        // Validate excludeTypes
        Object excludeTypesObj = (args != null) ? args.get("excludeTypes") : null;
        if (excludeTypesObj != null) {
            if (!(excludeTypesObj instanceof List<?> excludeList)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'excludeTypes' parameter must be an array of relationship type strings",
                        null,
                        "Example: [\"AssociationRelationship\", \"InfluenceRelationship\"]",
                        null);
                return new RelationshipFilters(null, null, null,
                        buildResult(formatter.toJsonString(formatter.formatError(error)), true));
            }
            excludeTypes = new HashSet<>();
            for (Object item : excludeList) {
                if (!(item instanceof String typeStr)) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "Each entry in 'excludeTypes' must be a string",
                            null,
                            "Example: [\"AssociationRelationship\", \"InfluenceRelationship\"]",
                            null);
                    return new RelationshipFilters(null, null, null,
                            buildResult(formatter.toJsonString(formatter.formatError(error)), true));
                }
                if (!VALID_RELATIONSHIP_TYPES.contains(typeStr)) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "Invalid relationship type: '" + typeStr + "'",
                            null,
                            "Valid relationship types: " + String.join(", ",
                                    VALID_RELATIONSHIP_TYPES.stream().sorted().toList()),
                            null);
                    return new RelationshipFilters(null, null, null,
                            buildResult(formatter.toJsonString(formatter.formatError(error)), true));
                }
                excludeTypes.add(typeStr);
            }
            if (excludeTypes.isEmpty()) {
                excludeTypes = null; // empty array = no filter
            }
        }

        // Validate includeTypes
        Object includeTypesObj = (args != null) ? args.get("includeTypes") : null;
        if (includeTypesObj != null) {
            if (!(includeTypesObj instanceof List<?> includeList)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'includeTypes' parameter must be an array of relationship type strings",
                        null,
                        "Example: [\"ServingRelationship\", \"RealizationRelationship\"]",
                        null);
                return new RelationshipFilters(null, null, null,
                        buildResult(formatter.toJsonString(formatter.formatError(error)), true));
            }
            includeTypes = new HashSet<>();
            for (Object item : includeList) {
                if (!(item instanceof String typeStr)) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "Each entry in 'includeTypes' must be a string",
                            null,
                            "Example: [\"ServingRelationship\", \"RealizationRelationship\"]",
                            null);
                    return new RelationshipFilters(null, null, null,
                            buildResult(formatter.toJsonString(formatter.formatError(error)), true));
                }
                if (!VALID_RELATIONSHIP_TYPES.contains(typeStr)) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "Invalid relationship type: '" + typeStr + "'",
                            null,
                            "Valid relationship types: " + String.join(", ",
                                    VALID_RELATIONSHIP_TYPES.stream().sorted().toList()),
                            null);
                    return new RelationshipFilters(null, null, null,
                            buildResult(formatter.toJsonString(formatter.formatError(error)), true));
                }
                includeTypes.add(typeStr);
            }
            if (includeTypes.isEmpty()) {
                includeTypes = null; // empty array = no filter
            }
        }

        // Validate filterLayer
        Object filterLayerObj = (args != null) ? args.get("filterLayer") : null;
        if (filterLayerObj != null) {
            if (!(filterLayerObj instanceof String layerStr)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'filterLayer' parameter must be a string",
                        null,
                        "Valid layers: Business, Application, Technology, Physical, "
                                + "Strategy, Motivation, Implementation & Migration",
                        null);
                return new RelationshipFilters(null, null, null,
                        buildResult(formatter.toJsonString(formatter.formatError(error)), true));
            }
            if (!layerStr.isBlank() && !SearchHandler.VALID_LAYERS.contains(layerStr)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Invalid layer: '" + layerStr + "'",
                        null,
                        "Valid layers: Business, Application, Technology, Physical, "
                                + "Strategy, Motivation, Implementation & Migration",
                        null);
                return new RelationshipFilters(null, null, null,
                        buildResult(formatter.toJsonString(formatter.formatError(error)), true));
            }
            if (!layerStr.isBlank()) {
                filterLayer = layerStr;
            }
        }

        return new RelationshipFilters(excludeTypes, includeTypes, filterLayer, null);
    }

    /**
     * Applies includeTypes and excludeTypes filters to a relationship list.
     */
    private List<RelationshipDto> applyTypeFilters(List<RelationshipDto> relationships,
                                                    RelationshipFilters filters) {
        if (filters.includeTypes == null && filters.excludeTypes == null) {
            return relationships;
        }
        List<RelationshipDto> filtered = new ArrayList<>();
        for (RelationshipDto rel : relationships) {
            if (filters.includeTypes != null && !filters.includeTypes.contains(rel.type())) {
                continue;
            }
            if (filters.excludeTypes != null && filters.excludeTypes.contains(rel.type())) {
                continue;
            }
            filtered.add(rel);
        }
        return filtered;
    }

    /**
     * Applies filterLayer to a relationship list for non-traverse mode.
     * Filters based on the connected element's layer (the "other end" from the queried element).
     */
    private List<RelationshipDto> applyLayerFilter(List<RelationshipDto> relationships,
                                                    String queriedElementId,
                                                    String filterLayer) {
        // Collect connected element IDs (the "other end" from queried element)
        Set<String> connectedIds = new HashSet<>();
        for (RelationshipDto rel : relationships) {
            String connectedId = queriedElementId.equals(rel.sourceId())
                    ? rel.targetId() : rel.sourceId();
            connectedIds.add(connectedId);
        }

        // Batch-lookup connected elements for layer info
        Map<String, ElementDto> elementMap = new HashMap<>();
        if (!connectedIds.isEmpty()) {
            List<ElementDto> elements = accessor.getElementsByIds(new ArrayList<>(connectedIds));
            for (ElementDto e : elements) {
                elementMap.put(e.id(), e);
            }
        }

        // Filter by connected element's layer
        List<RelationshipDto> filtered = new ArrayList<>();
        for (RelationshipDto rel : relationships) {
            String connectedId = queriedElementId.equals(rel.sourceId())
                    ? rel.targetId() : rel.sourceId();
            ElementDto connected = elementMap.get(connectedId);
            if (connected == null) {
                logger.debug("Connected element {} not found during layer filter — skipping relationship {}",
                        connectedId, rel.id());
                continue;
            }
            if (filterLayer.equals(connected.layer())) {
                filtered.add(rel);
            }
        }
        return filtered;
    }

    // ---- Non-Traverse Depth Shaping ----

    private List<Map<String, Object>> buildExpandedResult(
            List<RelationshipDto> relationships, ElementDto queriedElement, int depth,
            FieldSelector.FieldPreset fieldPreset, Set<String> excludeFields) {

        // Collect unique element IDs from all relationships
        Set<String> relatedIds = new HashSet<>();
        for (RelationshipDto rel : relationships) {
            if (rel.sourceId() != null) relatedIds.add(rel.sourceId());
            if (rel.targetId() != null) relatedIds.add(rel.targetId());
        }
        relatedIds.remove(queriedElement.id());

        // Batch lookup related elements
        Map<String, ElementDto> elementMap = new HashMap<>();
        elementMap.put(queriedElement.id(), queriedElement);
        if (!relatedIds.isEmpty()) {
            List<ElementDto> relatedElements = accessor.getElementsByIds(
                    new ArrayList<>(relatedIds));
            for (ElementDto e : relatedElements) {
                elementMap.put(e.id(), e);
            }
        }

        // For depth 3: get nested relationships for each connected element
        Map<String, List<RelationshipDto>> nestedRelationships = new HashMap<>();
        if (depth == 3) {
            int expandCount = 0;
            for (String connectedId : relatedIds) {
                if (expandCount >= MAX_DEPTH3_EXPANSION) {
                    logger.warn("Depth 3 expansion limited to {} connected elements",
                            MAX_DEPTH3_EXPANSION);
                    break;
                }
                if (elementMap.containsKey(connectedId)) {
                    nestedRelationships.put(connectedId,
                            accessor.getRelationshipsForElement(connectedId));
                    expandCount++;
                }
            }
            // Include queried element's own relationships (already fetched — no extra call)
            nestedRelationships.put(queriedElement.id(), relationships);
        }

        // Build expanded relationship maps
        List<Map<String, Object>> expandedResult = new ArrayList<>();
        for (RelationshipDto rel : relationships) {
            Map<String, Object> expanded = new LinkedHashMap<>();
            expanded.put("id", rel.id());
            expanded.put("name", rel.name());
            expanded.put("type", rel.type());
            expanded.put("source", buildElementResponse(
                    elementMap.get(rel.sourceId()), depth, nestedRelationships,
                    fieldPreset, excludeFields));
            expanded.put("target", buildElementResponse(
                    elementMap.get(rel.targetId()), depth, nestedRelationships,
                    fieldPreset, excludeFields));
            expandedResult.add(expanded);
        }
        return expandedResult;
    }

    private Object buildElementResponse(ElementDto element, int depth,
                                         Map<String, List<RelationshipDto>> nestedRelationships,
                                         FieldSelector.FieldPreset fieldPreset,
                                         Set<String> excludeFields) {
        if (element == null) {
            return null;
        }

        if (depth == 1) {
            // Summary: apply field selection — for MINIMAL, show only id+name; otherwise id+name+type
            Map<String, Object> summary = FieldSelector.elementDtoToMap(element);
            Set<String> summaryFields = (fieldPreset == FieldSelector.FieldPreset.MINIMAL)
                    ? Set.of("id", "name")
                    : Set.of("id", "name", "type");
            return FieldSelector.filterMap(summary, summaryFields, excludeFields);
        }

        // Depth 2: full element details with field selection
        if (depth == 2) {
            return FieldSelector.applyFieldSelection(element, fieldPreset, excludeFields);
        }

        // Depth 3: full element details with field selection + nested relationships
        @SuppressWarnings("unchecked")
        Map<String, Object> fullWithRels = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, fieldPreset, excludeFields);
        List<RelationshipDto> rels = nestedRelationships.get(element.id());
        fullWithRels.put("relationships", rels != null ? rels : List.of());
        return fullWithRels;
    }

    // ---- Traversal Mode (Stories 4.2, 4.3) ----

    private McpSchema.CallToolResult handleTraversal(McpSyncServerExchange exchange,
                                                      McpSchema.CallToolRequest request,
                                                      String elementId,
                                                      Map<String, Object> args,
                                                      RelationshipFilters filters,
                                                      String warningMessage,
                                                      FieldSelector.FieldPreset fieldPreset,
                                                      Set<String> excludeFields,
                                                      String sessionId,
                                                      boolean modelChanged,
                                                      String modelVersion,
                                                      boolean dryRun,
                                                      ResponseFormat format) {
        logger.info("Handling traversal for element: {} (filters: excludeTypes={}, includeTypes={}, filterLayer={})",
                elementId, filters.excludeTypes, filters.includeTypes, filters.filterLayer);

        // Extract progress token for progress notifications (Story 5.5)
        Object progressToken = extractProgressToken(request);
        if (progressToken != null) {
            logger.debug("Progress token provided for traversal: {}", progressToken);
        }

        // Validate maxDepth
        int maxDepth = TraversalEngine.DEFAULT_MAX_DEPTH;
        Object maxDepthObj = (args != null) ? args.get("maxDepth") : null;
        if (maxDepthObj != null) {
            if (!(maxDepthObj instanceof Number maxDepthNum)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'maxDepth' parameter must be an integer between "
                                + TraversalEngine.MIN_MAX_DEPTH + " and " + TraversalEngine.MAX_MAX_DEPTH,
                        null,
                        "Common values: 2 for immediate neighborhood, 3-5 for dependency chains",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }
            maxDepth = maxDepthNum.intValue();
            if (maxDepth < TraversalEngine.MIN_MAX_DEPTH || maxDepth > TraversalEngine.MAX_MAX_DEPTH) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'maxDepth' parameter must be between " + TraversalEngine.MIN_MAX_DEPTH
                                + " and " + TraversalEngine.MAX_MAX_DEPTH + ", got: " + maxDepth,
                        null,
                        "Common values: 2 for immediate neighborhood, 3-5 for dependency chains",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }
        }

        // Validate direction
        String direction = TraversalEngine.DEFAULT_DIRECTION;
        Object directionObj = (args != null) ? args.get("direction") : null;
        if (directionObj != null) {
            if (!(directionObj instanceof String dirStr)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'direction' parameter must be 'outgoing', 'incoming', or 'both'",
                        null,
                        "Use 'outgoing' for dependencies, 'incoming' for dependents, "
                                + "'both' for full exploration",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }
            direction = dirStr;
            if (!TraversalEngine.VALID_DIRECTIONS.contains(direction)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'direction' parameter must be 'outgoing', 'incoming', or 'both'",
                        null,
                        "Use 'outgoing' for dependencies, 'incoming' for dependents, "
                                + "'both' for full exploration",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }
        }

        // Cache check (Story 5.4) — skip if version just changed or dryRun
        String cacheKey = CacheKeyBuilder.buildCacheKey("relationships-traverse", elementId, "maxDepth", maxDepth,
                "direction", direction,
                "excl-types", CacheKeyBuilder.sortedSetKey(filters.excludeTypes),
                "incl-types", CacheKeyBuilder.sortedSetKey(filters.includeTypes),
                "filter-layer", filters.filterLayer,
                "fields", fieldPreset, "exclude", CacheKeyBuilder.sortedSetKey(excludeFields),
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

        // Check element existence
        Optional<ElementDto> queriedElement = accessor.getElementById(elementId);
        if (queriedElement.isEmpty()) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.ELEMENT_NOT_FOUND,
                    "No element found with ID '" + elementId + "'",
                    null,
                    "Use search-elements to find elements by name or type",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }

        // Execute traversal via TraversalEngine (Story 7.0a extraction)
        TraversalEngine.ProgressCallback progressCb = (progressToken != null && exchange != null)
                ? (current, total, msg) -> sendProgress(exchange, progressToken, current, total, msg)
                : null;

        TraversalEngine.TraversalResult outcome = traversalEngine.execute(
                elementId, queriedElement.get(), maxDepth, direction, filters,
                fieldPreset, excludeFields, progressCb);
        Map<String, Object> traversalResult = outcome.data();
        boolean isTruncated = outcome.truncated();
        int totalRels = outcome.totalRelationships();
        int totalElements = outcome.totalElementsDiscovered();

        logger.info("Traversal complete for {}: {} elements, {} relationships, truncated={}",
                elementId, totalElements, totalRels, isTruncated);

        // Dry-run early return for traverse mode (Story 6.2)
        if (dryRun) {
            logger.info("Dry-run traverse mode: {} elements, {} relationships discovered", totalElements, totalRels);
            int totalItems = totalElements + totalRels;
            int estimatedTokens = CostEstimator.estimateTokensMixed(totalElements, totalRels, fieldPreset);
            String recommendedPreset = CostEstimator.recommendPreset(estimatedTokens);
            String recommendation = CostEstimator.buildRecommendation(
                    totalItems, estimatedTokens, fieldPreset);
            List<String> dryRunNextSteps = new ArrayList<>();
            dryRunNextSteps.add("Execute without dryRun to get full traversal results");
            if (totalItems > 50) {
                dryRunNextSteps.add("Consider reducing maxDepth or adding direction filter to narrow results");
            }
            if (totalItems > 100) {
                dryRunNextSteps.add("Consider adding excludeTypes or includeTypes filters");
            }
            Map<String, Object> dryRunEnvelope = formatter.formatDryRun(
                    totalItems, estimatedTokens, recommendedPreset,
                    recommendation, dryRunNextSteps, modelVersion);
            if (modelChanged) {
                ResponseFormatter.addModelChangedFlag(dryRunEnvelope);
            }
            return buildResult(formatter.toJsonString(dryRunEnvelope), false);
        }

        // Summary format: generate text summary from traversal data
        if (format == ResponseFormat.SUMMARY) {
            logger.info("Returning traverse summary: {} elements, {} relationships",
                    totalElements, totalRels);
            String summaryText = SummaryFormatter.summarizeTraversal(
                    traversalResult, queriedElement.get());

            List<String> summaryNextSteps = totalRels == 0
                    ? List.of(
                            "Use search-elements to find related elements by name",
                            "Use get-model-info to see model structure overview")
                    : List.of(
                            "Re-run with format=json for full traversal data",
                            "Re-run with format=graph for flattened node/edge structure",
                            "Use get-element for full details on a discovered element");

            Map<String, Object> envelope = formatter.formatSummary(
                    summaryText, summaryNextSteps, modelVersion,
                    totalElements + totalRels, totalElements + totalRels);

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

        Map<String, Object> envelope;
        List<String> nextSteps;

        if (format == ResponseFormat.GRAPH) {
            // Graph format: flatten traversal hops into unified nodes/edges
            Map<String, Object> graphData = GraphFormatter.flattenTraverseResult(traversalResult);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> graphNodes =
                    (List<Map<String, Object>>) graphData.get("nodes");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> graphEdges =
                    (List<Map<String, Object>>) graphData.get("edges");

            nextSteps = totalRels == 0
                    ? List.of(
                            "Use search-elements to find related elements by name",
                            "Use get-model-info to see model structure overview")
                    : List.of(
                            "Use get-element for full details on a graph node",
                            "Use get-relationships with depth 2 for detailed relationship view",
                            "Use search-elements to find related elements by name");

            envelope = formatter.formatGraph(graphData, nextSteps, modelVersion,
                    graphNodes.size(), graphEdges.size(), totalElements + totalRels, isTruncated);
        } else {
            // JSON format (default)
            nextSteps = totalRels == 0
                    ? List.of(
                            "Use search-elements to find related elements by name",
                            "Use get-model-info to see model structure overview")
                    : List.of(
                            "Use get-element for full details on a discovered element",
                            "Use get-relationships with depth 2 for detailed relationship view",
                            "Use search-elements to find related elements by name");

            envelope = formatter.formatSuccess(
                    traversalResult, nextSteps, modelVersion,
                    totalRels, totalRels, isTruncated);
        }

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

        // Store in cache (Story 5.4) — skip when model changed to avoid stale entries
        if (sessionManager != null && sessionId != null && !modelChanged) {
            sessionManager.putCacheEntry(sessionId, cacheKey, jsonResult);
            logger.debug("Cache miss, storing result for key: {}", cacheKey);
        }

        return buildResult(jsonResult, false);
    }

    // ---- Progress Indication Helpers (Story 5.5) ----

    /**
     * Extracts the progress token from a tool request's _meta map.
     *
     * @param request the MCP tool call request
     * @return the progress token, or null if not provided by the client
     */
    static Object extractProgressToken(McpSchema.CallToolRequest request) {
        if (request != null && request.meta() != null) {
            return request.meta().get("progressToken");
        }
        return null;
    }

    /**
     * Sends a best-effort progress notification via the MCP exchange.
     * No-op if progressToken or exchange is null. Never throws — progress
     * failures must not break tool execution.
     *
     * @param exchange      the MCP server exchange (may be null in tests)
     * @param progressToken the client-provided progress token (may be null)
     * @param progress      current progress value
     * @param total         total expected value
     * @param message       human-readable progress description
     */
    private void sendProgress(McpSyncServerExchange exchange, Object progressToken,
                               double progress, double total, String message) {
        if (progressToken == null || exchange == null) {
            return;
        }
        try {
            exchange.progressNotification(
                    new McpSchema.ProgressNotification(progressToken, progress, total, message));
        } catch (Exception e) {
            logger.debug("Failed to send progress notification", e);
        }
    }

    // ---- Records ----

    /**
     * Holds parsed filter parameters or an error result if validation failed.
     * Package-visible for TraversalEngine access (Story 7.0a).
     */
    static record RelationshipFilters(
            Set<String> excludeTypes,
            Set<String> includeTypes,
            String filterLayer,
            McpSchema.CallToolResult errorResult) {}

    private McpSchema.CallToolResult buildResult(String json, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(isError)
                .build();
    }
}
