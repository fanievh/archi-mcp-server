package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.HashMap;
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
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
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
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for view-level tools: get-views (Story 2.4),
 * get-view-contents (Story 2.5), update-view (Story 8-7).
 *
 * <p>This handler follows the same pattern established by
 * {@link ModelQueryHandler} in Stories 2.2/2.3.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF or ArchimateTool model types. All model access goes through
 * {@link ArchiModelAccessor}.</p>
 */
public class ViewHandler {

    private static final Logger logger = LoggerFactory.getLogger(ViewHandler.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};
    private static final String TREE_ROOT_KEY = "__root__";

    /**
     * Default page size for view results when no limit parameter is provided.
     * Matches the pre-pagination MAX_VIEWS_RESULT for backward compatibility.
     */
    static final int DEFAULT_VIEWS_LIMIT = PaginationCursor.DEFAULT_PAGE_SIZE;

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a ViewHandler with its required dependencies.
     *
     * @param accessor       the model accessor for querying ArchiMate data
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager for session-scoped filters, may be null
     */
    public ViewHandler(ArchiModelAccessor accessor,
                       ResponseFormatter formatter,
                       CommandRegistry registry,
                       SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager; // nullable for backward compat; no filter logic for MVP
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     * Registers: get-views (Story 2.4), get-view-contents (Story 2.5),
     * update-view (Story 8-7).
     */
    public void registerTools() {
        registry.registerTool(buildGetViewsSpec());
        registry.registerTool(buildGetViewContentsSpec());
        registry.registerTool(buildUpdateViewSpec());
    }

    private McpServerFeatures.SyncToolSpecification buildGetViewsSpec() {
        Map<String, Object> viewpointProp = new LinkedHashMap<>();
        viewpointProp.put("type", "string");
        viewpointProp.put("description",
                "Optional viewpoint name to filter views (e.g., 'Application Usage')");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description",
                "Filter views by name (case-insensitive substring match). "
                + "Example: 'Application' returns all views with 'application' in the name.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewpoint", viewpointProp);
        properties.put("name", nameProp);

        Map<String, Object> fieldsProp = new LinkedHashMap<>();
        fieldsProp.put("type", "string");
        fieldsProp.put("description", "Field verbosity preset for view data. "
                + "'minimal' returns only id and name. "
                + "'standard' (default) returns id, name, viewpointType, folderPath. "
                + "'full' returns all available fields.");
        fieldsProp.put("enum", List.of("minimal", "standard", "full"));
        properties.put("fields", fieldsProp);

        Map<String, Object> excludeProp = new LinkedHashMap<>();
        excludeProp.put("type", "array");
        Map<String, Object> excludeItems = new LinkedHashMap<>();
        excludeItems.put("type", "string");
        excludeProp.put("items", excludeItems);
        excludeProp.put("description", "Fields to exclude from view data. "
                + "Applied after fields preset. "
                + "Valid values: viewpointType, folderPath. "
                + "Note: id and name cannot be excluded.");
        properties.put("exclude", excludeProp);

        Map<String, Object> limitProp = new LinkedHashMap<>();
        limitProp.put("type", "integer");
        limitProp.put("description", "Maximum number of results per page (1-"
                + PaginationCursor.MAX_PAGE_SIZE + "). Default: " + DEFAULT_VIEWS_LIMIT
                + ". Use with cursor for pagination.");
        properties.put("limit", limitProp);

        Map<String, Object> cursorProp = new LinkedHashMap<>();
        cursorProp.put("type", "string");
        cursorProp.put("description", "Pagination cursor from a previous response's "
                + "_meta.cursor. When provided, retrieves the next page of results. "
                + "Cursor parameters override viewpoint and name parameters.");
        properties.put("cursor", cursorProp);

        Map<String, Object> dryRunProp = new LinkedHashMap<>();
        dryRunProp.put("type", "boolean");
        dryRunProp.put("description", "Set to true to get a cost estimate without returning results. "
                + "Returns estimated result count, token size, and recommended field preset. "
                + "Ignores cursor and limit parameters.");
        properties.put("dryRun", dryRunProp);

        Map<String, Object> formatProp = new LinkedHashMap<>();
        formatProp.put("type", "string");
        formatProp.put("description", "Response format. 'json' (default) returns standard result array. "
                + "'graph' returns nodes/edges structure (nodes = views, edges = empty). "
                + "'summary' returns condensed natural language overview with viewpoint distributions.");
        formatProp.put("enum", List.of("json", "graph", "summary"));
        properties.put("format", formatProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-views")
                .description("[Views] List all views (diagrams) in the ArchiMate model. "
                        + "Optionally filter by viewpoint type or name substring. "
                        + "Results are paginated if they exceed the limit. "
                        + "Use the cursor from _meta.cursor to retrieve subsequent pages. "
                        + "Use 'fields' to control response verbosity and 'exclude' to omit specific fields. "
                        + "Set dryRun=true to get a cost estimate without returning results. "
                        + "Set format=graph for node/edge structure, format=summary for condensed text overview. "
                        + "Related: get-view-contents (explore a specific diagram), "
                        + "search-elements (find elements without browsing views).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetViews)
                .build();
    }

    private McpSchema.CallToolResult handleGetViews(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-views request");
        try {
            String viewpointFilter = null;
            String nameFilter = null;
            String fieldsParam = null;
            List<String> excludeParam = null;
            if (request.arguments() != null) {
                Object vpObj = request.arguments().get("viewpoint");
                if (vpObj instanceof String vp && !vp.isBlank()) {
                    viewpointFilter = vp;
                }
                Object nameObj = request.arguments().get("name");
                if (nameObj instanceof String n && !n.isBlank()) {
                    nameFilter = n;
                }
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
                                        + "viewpointType, folderPath, visualMetadata, connections, "
                                        + "groups, notes",
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
                                "Valid formats: json, graph, summary, tree",
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
                                + ", or omit for default (" + DEFAULT_VIEWS_LIMIT + ")",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Cursor mode: decode and validate cursor
            int offset = 0;
            int limit = (limitParam != null) ? limitParam : DEFAULT_VIEWS_LIMIT;
            String effectiveViewpoint = viewpointFilter;
            String effectiveName = nameFilter;

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

                // Use cursor params
                offset = cursorData.offset();
                limit = cursorData.limit();
                effectiveViewpoint = cursorData.params().get("viewpoint");
                effectiveName = cursorData.params().get("name");
                logger.debug("Decoded pagination cursor: offset={}, limit={}, modelVersion={}",
                        offset, limit, cursorData.modelVersion());
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
            logger.debug("Viewpoint filter: {}, name filter: {}, field selection — preset: {}, exclude: {}",
                    effectiveViewpoint, effectiveName, preset, effectiveExclude);

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
            String cacheKey = CacheKeyBuilder.buildCacheKey("views", "vp", effectiveViewpoint,
                    "name", effectiveName,
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
            List<ViewDto> allViews = accessor.getViews(effectiveViewpoint);

            // Apply name post-filter (Story 6.4)
            if (effectiveName != null && !effectiveName.isEmpty()) {
                logger.info("Handling get-views with name='{}', viewpoint='{}'", effectiveName, effectiveViewpoint);
                String lowerName = effectiveName.toLowerCase();
                allViews = allViews.stream()
                        .filter(v -> v.name() != null && v.name().toLowerCase().contains(lowerName))
                        .collect(Collectors.toList());
                logger.debug("Name filter '{}' reduced views to {}", effectiveName, allViews.size());
            }

            int totalCount = allViews.size();

            // Dry-run early return: estimate cost without data
            if (dryRun) {
                logger.info("Handling get-views with dryRun=true");
                int estimatedTokens = CostEstimator.estimateTokens(totalCount, preset, CostEstimator.ItemType.VIEW);
                int tokensAtStandard = CostEstimator.estimateTokens(totalCount, FieldSelector.FieldPreset.STANDARD,
                        CostEstimator.ItemType.VIEW);
                String recommendedPreset = CostEstimator.recommendPreset(tokensAtStandard);
                String recommendation = CostEstimator.buildRecommendation(totalCount, estimatedTokens, preset);
                logger.debug("DryRun estimate: {} views, ~{} tokens at {} preset", totalCount, estimatedTokens, preset);

                List<String> dryRunNextSteps = new ArrayList<>();
                if (totalCount > 50) {
                    dryRunNextSteps.add("Add a viewpoint filter to narrow results");
                }
                if (estimatedTokens > CostEstimator.THRESHOLD_COMFORTABLE) {
                    dryRunNextSteps.add("Use fields=minimal to reduce token usage");
                }
                if (totalCount > limit) {
                    dryRunNextSteps.add("Use limit parameter for paginated retrieval");
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
                logger.info("Returning get-views summary: total={}", totalCount);
                String summaryText = SummaryFormatter.summarizeViews(allViews, effectiveName);

                List<String> summaryNextSteps;
                if (totalCount == 0) {
                    summaryNextSteps = buildEmptyViewsNextSteps(effectiveName);
                } else {
                    summaryNextSteps = List.of(
                            "Re-run with format=json for full view data",
                            "Use get-view-contents with a view ID to explore a specific diagram");
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
            List<ViewDto> pageViews = (offset < totalCount)
                    ? allViews.subList(offset, endIndex)
                    : List.of();
            boolean hasMore = endIndex < totalCount;

            logger.info("Returning get-views page: offset={}, limit={}, pageSize={}, total={}, format={}",
                    offset, limit, pageViews.size(), totalCount, format.value());

            // Apply field selection (per-page, after slicing)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filteredResult =
                    (List<Map<String, Object>>) FieldSelector.applyFieldSelection(pageViews, preset, effectiveExclude);

            Map<String, Object> envelope;
            List<String> nextSteps;

            if (format == ResponseFormat.GRAPH) {
                // Graph format: views as nodes, no edges
                Map<String, Object> graphData = GraphFormatter.formatAsGraph(filteredResult);

                if (pageViews.isEmpty()) {
                    nextSteps = buildEmptyViewsNextSteps(effectiveName);
                } else if (hasMore) {
                    nextSteps = List.of(
                            "Use cursor parameter to retrieve next page",
                            "Use get-view-contents with format=graph for element/relationship graph",
                            "Add a viewpoint filter to narrow results");
                } else {
                    nextSteps = List.of(
                            "Use get-view-contents with format=graph for element/relationship graph");
                }

                envelope = formatter.formatGraph(graphData, nextSteps, modelVersion,
                        pageViews.size(), 0, totalCount, hasMore);
            } else {
                // JSON format (default)
                if (pageViews.isEmpty()) {
                    nextSteps = buildEmptyViewsNextSteps(effectiveName);
                } else if (hasMore) {
                    nextSteps = List.of(
                            "Use cursor parameter to retrieve next page",
                            "Use get-view-contents with a view ID to explore a specific diagram",
                            "Add a viewpoint filter to narrow results");
                } else {
                    nextSteps = List.of(
                            "Use get-view-contents with a view ID to explore a specific diagram");
                }

                envelope = formatter.formatSuccess(
                        filteredResult, nextSteps, modelVersion,
                        pageViews.size(), totalCount, hasMore);
            }

            // Add cursor token if more pages available
            if (hasMore) {
                Map<String, String> cursorParams = new LinkedHashMap<>();
                cursorParams.put("viewpoint", effectiveViewpoint);
                cursorParams.put("name", effectiveName);
                String nextCursor = PaginationCursor.encode(
                        modelVersion, offset + limit, limit, totalCount, cursorParams);
                ResponseFormatter.addCursorToken(envelope, nextCursor);
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
            logger.error("Unexpected error handling get-views", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while retrieving views");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    private McpServerFeatures.SyncToolSpecification buildGetViewContentsSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description",
                "The unique identifier (ID) of the view to retrieve contents for. "
                + "Use get-views to discover available view IDs.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);

        Map<String, Object> fieldsProp = new LinkedHashMap<>();
        fieldsProp.put("type", "string");
        fieldsProp.put("description", "Field verbosity preset for element/view data. "
                + "'minimal' returns only id and name. "
                + "'standard' (default) returns standard fields. "
                + "'full' returns all available fields.");
        fieldsProp.put("enum", List.of("minimal", "standard", "full"));
        properties.put("fields", fieldsProp);

        Map<String, Object> excludeProp = new LinkedHashMap<>();
        excludeProp.put("type", "array");
        Map<String, Object> excludeItemsDef = new LinkedHashMap<>();
        excludeItemsDef.put("type", "string");
        excludeProp.put("items", excludeItemsDef);
        excludeProp.put("description", "Fields to exclude from element/view data. "
                + "Applied after fields preset. "
                + "Valid values: documentation, properties, layer, type, visualMetadata, connections, "
                + "groups, notes. "
                + "Note: id and name cannot be excluded. "
                + "Use exclude=['visualMetadata','connections'] to omit position and routing data.");
        properties.put("exclude", excludeProp);

        Map<String, Object> dryRunProp = new LinkedHashMap<>();
        dryRunProp.put("type", "boolean");
        dryRunProp.put("description", "Set to true to get a cost estimate without returning results. "
                + "Returns estimated element and relationship counts, token size, and recommended field preset.");
        properties.put("dryRun", dryRunProp);

        Map<String, Object> formatProp = new LinkedHashMap<>();
        formatProp.put("type", "string");
        formatProp.put("description", "Response format. 'json' (default) returns standard result object. "
                + "'graph' returns deduplicated nodes/edges structure (elements as nodes, relationships as edges). "
                + "'summary' returns condensed natural language overview with element/relationship distributions. "
                + "'tree' returns compact containment hierarchy showing groups and their children — "
                + "ideal for discovering group viewObjectIds before calling layout-within-group, arrange-groups, or optimize-group-order. "
                + "Much more token-efficient than json for grouped view workflows.");
        formatProp.put("enum", List.of("json", "graph", "summary", "tree"));
        properties.put("format", formatProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-view-contents")
                .description("[Views] Retrieve the elements, relationships, visual layout, and connection routing "
                        + "within a specific ArchiMate view (diagram). "
                        + "Shows which elements appear together and how they connect in a diagram. "
                        + "Connections include sourceAnchor/targetAnchor (element center reference points "
                        + "used by the bendpoint formula — NOT visual edge attachment points; Archi computes "
                        + "perimeter intersections at render time), relative bendpoints (offsets from "
                        + "source/target centers), and absoluteBendpoints (canvas coordinates). "
                        + "Use 'fields' to control response verbosity and 'exclude' to omit specific fields. "
                        + "Use exclude=['visualMetadata','connections'] to omit position and routing data. "
                        + "Set dryRun=true to get a cost estimate without returning results. "
                        + "Set format=tree for a compact containment hierarchy showing groups and their children — "
                        + "ideal first step for grouped view workflows (discover viewObjectIds for "
                        + "layout-within-group, arrange-groups, optimize-group-order). "
                        + "Set format=graph for deduplicated node/edge structure, format=summary for condensed text overview. "
                        + "Related: get-element (full element details), "
                        + "get-relationships (connections beyond this view), "
                        + "export-view (visual verification of connection routing).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetViewContents)
                .build();
    }

    private McpSchema.CallToolResult handleGetViewContents(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-view-contents request");
        try {
            // Extract and validate required viewId parameter
            Object viewIdObj = (request.arguments() != null) ? request.arguments().get("viewId") : null;
            if (!(viewIdObj instanceof String viewId) || viewId.isBlank()) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Required parameter 'viewId' is missing or invalid",
                        "The 'viewId' parameter must be a non-empty string",
                        "Use get-views to discover available view IDs",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
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
                                "Valid formats: json, graph, summary, tree",
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
                                        + "viewpointType, folderPath, visualMetadata, connections, "
                                        + "groups, notes",
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
            logger.debug("View ID: {}, field selection — preset: {}, exclude: {}", viewId, preset, effectiveExclude);

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

            // Cache check (Story 5.4) — includes format for different envelopes
            String cacheKey = CacheKeyBuilder.buildCacheKey("view-contents", viewId,
                    "fields", preset, "exclude", CacheKeyBuilder.sortedSetKey(effectiveExclude),
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

            // Fresh query
            Optional<ViewContentsDto> viewContents = accessor.getViewContents(viewId);

            if (viewContents.isPresent()) {
                ViewContentsDto contents = viewContents.get();

                // Dry-run early return: estimate cost without data
                if (dryRun) {
                    logger.info("Handling get-view-contents with dryRun=true");
                    int elementCount = contents.elements().size();
                    int relationshipCount = contents.relationships().size();
                    int estimatedTokens = CostEstimator.estimateTokensMixed(elementCount, relationshipCount, preset);
                    int tokensAtStandard = CostEstimator.estimateTokensMixed(elementCount, relationshipCount,
                            FieldSelector.FieldPreset.STANDARD);
                    String recommendedPreset = CostEstimator.recommendPreset(tokensAtStandard);
                    String recommendation = CostEstimator.buildRecommendation(
                            elementCount + relationshipCount, estimatedTokens, preset);
                    logger.debug("DryRun estimate: {} elements + {} relationships, ~{} tokens at {} preset",
                            elementCount, relationshipCount, estimatedTokens, preset);

                    List<String> dryRunNextSteps = new ArrayList<>();
                    dryRunNextSteps.add("Execute the query without dryRun to retrieve results");
                    if (estimatedTokens > CostEstimator.THRESHOLD_COMFORTABLE) {
                        dryRunNextSteps.add("Use fields=minimal to reduce token usage");
                    }
                    if (effectiveExclude == null
                            || !effectiveExclude.contains("visualMetadata")
                            || !effectiveExclude.contains("connections")) {
                        dryRunNextSteps.add("Use exclude=['visualMetadata','connections'] to omit position and routing data");
                    }

                    Map<String, Object> dryRunEnvelope = formatter.formatDryRun(
                            elementCount + relationshipCount, estimatedTokens, recommendedPreset,
                            recommendation, dryRunNextSteps, modelVersion);
                    if (modelChanged) {
                        ResponseFormatter.addModelChangedFlag(dryRunEnvelope);
                    }
                    return buildResult(formatter.toJsonString(dryRunEnvelope), false);
                }

                // Count primary content items (visualMetadata is supplementary positional data)
                int elementCount = contents.elements().size();
                int relationshipCount = contents.relationships().size();
                int resultCount = elementCount + relationshipCount;

                // Summary format: skip field selection, generate text summary
                if (format == ResponseFormat.SUMMARY) {
                    logger.info("Returning get-view-contents summary: elements={}, relationships={}",
                            elementCount, relationshipCount);
                    String summaryText = SummaryFormatter.summarizeViewContents(contents);

                    List<String> summaryNextSteps = List.of(
                            "Re-run with format=json for full element and relationship data",
                            "Re-run with format=graph for deduplicated node/edge structure",
                            "Use get-element with an element ID for detailed information");

                    Map<String, Object> envelope = formatter.formatSummary(
                            summaryText, summaryNextSteps, modelVersion, resultCount, resultCount);

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

                // Tree format: compact containment hierarchy for group discovery (skip field selection)
                if (format == ResponseFormat.TREE) {
                    Map<String, Object> treeResult = buildContainmentTree(contents);

                    List<String> treeNextSteps;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> treeStats = (Map<String, Object>) treeResult.get("stats");
                    int totalGroups = (int) treeStats.get("totalGroups");
                    if (totalGroups > 0) {
                        treeNextSteps = List.of(
                                "Use layout-within-group with a group's viewObjectId to layout its children",
                                "Use arrange-groups to position groups relative to each other",
                                "Use optimize-group-order to minimize inter-group edge crossings",
                                "Use get-view-contents format=json for full element and relationship detail");
                    } else {
                        treeNextSteps = List.of(
                                "Use layout-flat-view for flat views (no groups) — recommended default layout tool",
                                "Use compute-layout with a preset for alternative layout algorithms",
                                "Use get-view-contents format=json for full element and relationship detail");
                    }

                    logger.info("Returning get-view-contents tree: groups={}, elements={}, notes={}",
                            treeStats.get("totalGroups"), treeStats.get("totalElements"),
                            treeStats.get("totalNotes"));

                    Map<String, Object> envelope = formatter.formatSuccess(
                            treeResult, treeNextSteps, modelVersion,
                            elementCount + relationshipCount,
                            elementCount + relationshipCount, false);

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

                // Apply field selection to ViewContentsDto (filters nested elements/relationships)
                Object filteredResult = FieldSelector.applyFieldSelection(contents, preset, effectiveExclude);

                Map<String, Object> envelope;
                List<String> nextSteps;

                if (format == ResponseFormat.GRAPH) {
                    // Graph format: elements as deduplicated nodes, relationships as edges,
                    // groups/notes as additional nodes (Story 8-6)
                    @SuppressWarnings("unchecked")
                    Map<String, Object> filteredMap = (Map<String, Object>) filteredResult;
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> elements =
                            (List<Map<String, Object>>) filteredMap.get("elements");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> relationships =
                            (List<Map<String, Object>>) filteredMap.get("relationships");

                    Map<String, Object> graphData = GraphFormatter.formatAsGraph(elements, relationships);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> graphNodes =
                            (List<Map<String, Object>>) graphData.get("nodes");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> graphEdges =
                            (List<Map<String, Object>>) graphData.get("edges");

                    // Add groups and notes as graph nodes (Story 8-6 AC14)
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> groups =
                            (List<Map<String, Object>>) filteredMap.get("groups");
                    if (groups != null) {
                        for (Map<String, Object> group : groups) {
                            Map<String, Object> node = new LinkedHashMap<>(group);
                            node.put("_nodeType", "group");
                            graphNodes.add(node);
                        }
                    }
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> notes =
                            (List<Map<String, Object>>) filteredMap.get("notes");
                    if (notes != null) {
                        for (Map<String, Object> note : notes) {
                            Map<String, Object> node = new LinkedHashMap<>(note);
                            node.put("_nodeType", "note");
                            graphNodes.add(node);
                        }
                    }

                    nextSteps = List.of(
                            "Use get-element with a node ID for detailed information",
                            "Use get-relationships with format=graph to explore connections beyond this view");

                    envelope = formatter.formatGraph(graphData, nextSteps, modelVersion,
                            graphNodes.size(), graphEdges.size(), resultCount, false);
                } else {
                    // JSON format (default)
                    nextSteps = List.of(
                            "Use get-element with an element ID for detailed information",
                            "Use get-relationships with an element ID to explore connections beyond this view");

                    envelope = formatter.formatSuccess(
                            filteredResult, nextSteps, modelVersion,
                            resultCount, resultCount, false);
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

                // Store in cache (Story 5.4) — skip when model changed (cache already invalidated)
                if (sessionManager != null && sessionId != null && !modelChanged) {
                    sessionManager.putCacheEntry(sessionId, cacheKey, jsonResult);
                    logger.debug("Cache miss, storing result for key: {}", cacheKey);
                }

                return buildResult(jsonResult, false);
            } else {
                // VIEW_NOT_FOUND with helpful context
                String details;
                try {
                    int totalViews = accessor.getViews(null).size();
                    details = "No view found with ID '" + viewId + "'. The model contains " + totalViews + " views.";
                } catch (Exception ex) {
                    logger.debug("Failed to get view count for error context", ex);
                    details = "No view found with ID '" + viewId + "'.";
                }
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.VIEW_NOT_FOUND,
                        details,
                        null,
                        "Use get-views to list available view IDs",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
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
            logger.error("Unexpected error handling get-view-contents", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while retrieving view contents");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    /**
     * Builds nextSteps for empty view results, with name-filter-aware messaging.
     */
    private List<String> buildEmptyViewsNextSteps(String effectiveName) {
        if (effectiveName != null && !effectiveName.isEmpty()) {
            return List.of(
                    "No views matching '" + effectiveName + "'. Try a broader name, "
                    + "remove the name filter, or use search-elements to find elements by name.");
        }
        return List.of(
                "Use get-model-info to verify the model is loaded correctly");
    }

    /**
     * Builds a compact containment tree from view contents (Story backlog-a1).
     *
     * <p>Transforms the flat lists in {@link ViewContentsDto} into a nested tree
     * structure showing group containment hierarchy. Each node includes only the
     * fields needed for group discovery: viewObjectId, type, name/label, and for
     * groups: childCount and children array.</p>
     *
     * @param contents the full view contents DTO
     * @return map with viewId, viewName, tree array, and stats object
     */
    private Map<String, Object> buildContainmentTree(ViewContentsDto contents) {
        // Build element lookup by ID for name/type resolution
        Map<String, ElementDto> elementById = new HashMap<>();
        if (contents.elements() != null) {
            for (ElementDto e : contents.elements()) {
                elementById.put(e.id(), e);
            }
        }

        // Index: parentViewObjectId → list of child tree nodes
        Map<String, List<Map<String, Object>>> childrenByParent = new LinkedHashMap<>();
        // Track group nodes by viewObjectId for later nesting
        Map<String, Map<String, Object>> groupNodes = new LinkedHashMap<>();

        // Stats counters
        int totalElements = 0;
        int totalNotes = 0;
        int ungroupedElements = 0;

        // Process elements (visualMetadata)
        if (contents.visualMetadata() != null) {
            for (ViewNodeDto node : contents.visualMetadata()) {
                Map<String, Object> treeNode = new LinkedHashMap<>();
                treeNode.put("viewObjectId", node.viewObjectId());
                treeNode.put("type", "element");

                // Resolve name and elementType from elements list
                ElementDto element = elementById.get(node.elementId());
                if (element != null) {
                    treeNode.put("name", element.name());
                    treeNode.put("elementType", element.type());
                }

                String parent = node.parentViewObjectId();
                childrenByParent.computeIfAbsent(
                        parent != null ? parent : TREE_ROOT_KEY, k -> new ArrayList<>()).add(treeNode);

                totalElements++;
                if (parent == null) {
                    ungroupedElements++;
                }
            }
        }

        // Process notes
        if (contents.notes() != null) {
            for (ViewNoteDto note : contents.notes()) {
                Map<String, Object> treeNode = new LinkedHashMap<>();
                treeNode.put("viewObjectId", note.viewObjectId());
                treeNode.put("type", "note");
                treeNode.put("label", note.content());

                String parent = note.parentViewObjectId();
                childrenByParent.computeIfAbsent(
                        parent != null ? parent : TREE_ROOT_KEY, k -> new ArrayList<>()).add(treeNode);

                totalNotes++;
            }
        }

        // Process groups — build group nodes with children
        int totalGroups = 0;
        int topLevelGroups = 0;
        int nestedGroups = 0;

        if (contents.groups() != null) {
            // First pass: create group nodes
            for (ViewGroupDto group : contents.groups()) {
                Map<String, Object> groupNode = new LinkedHashMap<>();
                groupNode.put("viewObjectId", group.viewObjectId());
                groupNode.put("type", "group");
                groupNode.put("label", group.label());
                groupNodes.put(group.viewObjectId(), groupNode);
                totalGroups++;
            }

            // Second pass: assemble children and nest groups
            for (ViewGroupDto group : contents.groups()) {
                Map<String, Object> groupNode = groupNodes.get(group.viewObjectId());

                // Get children for this group (elements and notes already indexed)
                List<Map<String, Object>> children =
                        childrenByParent.getOrDefault(group.viewObjectId(), new ArrayList<>());

                // Add nested child groups
                if (group.childViewObjectIds() != null) {
                    for (String childId : group.childViewObjectIds()) {
                        Map<String, Object> childGroup = groupNodes.get(childId);
                        if (childGroup != null) {
                            children.add(childGroup);
                        }
                    }
                }

                groupNode.put("childCount", children.size());
                groupNode.put("children", children);

                // Add group to its parent's list or root
                String parent = group.parentViewObjectId();
                if (parent == null) {
                    topLevelGroups++;
                } else {
                    nestedGroups++;
                }
                // Only add top-level groups to root; nested groups are added via childViewObjectIds
                if (parent == null) {
                    childrenByParent.computeIfAbsent(TREE_ROOT_KEY, k -> new ArrayList<>()).add(groupNode);
                }
            }
        }

        // Build root-level tree array
        List<Map<String, Object>> tree = childrenByParent.getOrDefault(TREE_ROOT_KEY, List.of());

        // Build stats
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalGroups", totalGroups);
        stats.put("topLevelGroups", topLevelGroups);
        stats.put("nestedGroups", nestedGroups);
        stats.put("totalElements", totalElements);
        stats.put("totalNotes", totalNotes);
        stats.put("ungroupedElements", ungroupedElements);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("viewId", contents.viewId());
        result.put("viewName", contents.viewName());
        result.put("tree", tree);
        result.put("stats", stats);

        return result;
    }

    private McpSchema.CallToolResult buildResult(String json, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(isError)
                .build();
    }

    // ---- update-view (Story 8-7) ----

    private McpServerFeatures.SyncToolSpecification buildUpdateViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "The unique identifier of the view to update");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description",
                "New display name for the view (omit to leave unchanged)");

        Map<String, Object> viewpointProp = new LinkedHashMap<>();
        viewpointProp.put("type", "string");
        viewpointProp.put("description",
                "New viewpoint type (e.g., 'layered', 'application_cooperation'). "
                + "Omit to leave unchanged. Set to empty string to clear the viewpoint.");

        Map<String, Object> documentationProp = new LinkedHashMap<>();
        documentationProp.put("type", "string");
        documentationProp.put("description",
                "New documentation text for the view (omit to leave unchanged)");

        Map<String, Object> propertiesValueSchema = new LinkedHashMap<>();
        propertiesValueSchema.put("type", "string");
        propertiesValueSchema.put("nullable", true);
        Map<String, Object> propertiesProp = new LinkedHashMap<>();
        propertiesProp.put("type", "object");
        propertiesProp.put("description",
                "Properties to add, update, or remove. Set value to a string to add/update, "
                + "set value to null to remove the property key. Omit to leave properties unchanged.");
        propertiesProp.put("additionalProperties", propertiesValueSchema);

        Map<String, Object> connectionRouterTypeProp = new LinkedHashMap<>();
        connectionRouterTypeProp.put("type", "string");
        connectionRouterTypeProp.put("description",
                "Connection routing style. 'manhattan' for orthogonal right-angle paths, "
                + "'manual' for direct lines. Omit to leave unchanged. "
                + "Set to empty string to clear (revert to default manual routing).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("name", nameProp);
        properties.put("viewpoint", viewpointProp);
        properties.put("documentation", documentationProp);
        properties.put("properties", propertiesProp);
        properties.put("connectionRouterType", connectionRouterTypeProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update-view")
                .description("[Mutation] Update an existing ArchiMate view. "
                        + "Requires viewId. Optional: name (new display name), viewpoint "
                        + "(new viewpoint type, or empty string to clear), documentation "
                        + "(new description text), properties (object with key-value pairs; "
                        + "set value to null to remove a property), connectionRouterType "
                        + "('manhattan' for orthogonal routing, 'manual' for direct lines, "
                        + "or empty string to clear/revert to default manual routing). "
                        + "Only provided fields are modified; omitted fields remain unchanged. "
                        + "If a property key appears multiple times on the view, only the first "
                        + "occurrence is updated (the response DTO may show the last value for "
                        + "that key). Warning: unrecognized viewpoint values are silently accepted "
                        + "by Archi but render as no viewpoint in the UI. "
                        + "Related: get-views (list existing), get-view-contents (inspect created view).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleUpdateView)
                .build();
    }

    McpSchema.CallToolResult handleUpdateView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling update-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String name = HandlerUtils.optionalStringParam(args, "name");
            String viewpoint = HandlerUtils.optionalStringParam(args, "viewpoint");
            String documentation = HandlerUtils.optionalStringParam(args, "documentation");
            Map<String, String> props = HandlerUtils.optionalMapParamWithNulls(args, "properties");
            String connectionRouterType = HandlerUtils.optionalStringParam(args,
                    "connectionRouterType");

            // Viewpoint clear semantics: empty string means "clear viewpoint".
            // optionalStringParam returns null for missing/null, but we need to preserve "".
            // Cross-ref: same logic in ArchiModelAccessorImpl.prepareBulkOperation("update-view")
            // and ArchiModelAccessorImpl.prepareUpdateView() which converts "" to clearViewpoint=true.
            if (args.containsKey("viewpoint") && "".equals(args.get("viewpoint"))) {
                viewpoint = "";
            }

            // ConnectionRouterType clear semantics: empty string means "revert to manual".
            if (args.containsKey("connectionRouterType")
                    && "".equals(args.get("connectionRouterType"))) {
                connectionRouterType = "";
            }

            MutationResult<ViewDto> result = accessor.updateView(
                    sessionId, viewId, name, viewpoint, documentation, props,
                    connectionRouterType);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildUpdateViewNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling update-view", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while updating view");
        }
    }

    private List<String> buildUpdateViewNextSteps(MutationResult<ViewDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        String id = result.entity().id();
        return List.of(
                "Use get-views to see the updated view in the list",
                "Use get-view-contents with viewId '" + id + "' to inspect the view's contents",
                "Use export-view to render the view as an image");
    }
}
