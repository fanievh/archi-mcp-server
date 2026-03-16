package net.vheerden.archi.mcp.response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds LLM-optimized JSON responses following the standard envelope format.
 *
 * <p>All MCP tool responses use this consistent structure:</p>
 * <pre>
 * {
 *   "result": { ... },
 *   "nextSteps": ["suggestion1", "suggestion2"],
 *   "_meta": {
 *     "modelVersion": "abc123",
 *     "resultCount": 7,
 *     "isTruncated": false,
 *     "totalCount": 7
 *   }
 * }
 * </pre>
 *
 * <p>Handlers should NEVER construct JSON manually - always use this formatter.</p>
 *
 * <p>Thread-safe: uses a single shared {@link ObjectMapper} instance (which is
 * thread-safe after configuration).</p>
 */
public class ResponseFormatter {

    private final ObjectMapper objectMapper;

    public ResponseFormatter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Package-visible constructor for testing with a custom ObjectMapper.
     */
    ResponseFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Formats a successful response with the standard envelope.
     *
     * @param result       the result object (DTO or collection)
     * @param nextSteps    suggested next actions for the LLM client
     * @param modelVersion current model version string
     * @param resultCount  number of results in this response
     * @param totalCount   total results available (may differ from resultCount if truncated)
     * @param isTruncated  true if results were truncated
     * @return envelope map ready for JSON serialization
     */
    public Map<String, Object> formatSuccess(Object result, List<String> nextSteps,
                                              String modelVersion, int resultCount,
                                              int totalCount, boolean isTruncated) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("result", result);
        if (nextSteps != null && !nextSteps.isEmpty()) {
            envelope.put("nextSteps", nextSteps);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("modelVersion", modelVersion);
        meta.put("resultCount", resultCount);
        meta.put("isTruncated", isTruncated);
        meta.put("totalCount", totalCount);
        meta.put("sessionActive", true);
        envelope.put("_meta", meta);

        return envelope;
    }

    /**
     * Formats an error response with the standard error envelope.
     *
     * @param error the structured error response
     * @return envelope map ready for JSON serialization
     */
    public Map<String, Object> formatError(ErrorResponse error) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("error", error.toMap());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionActive", true);
        envelope.put("_meta", meta);

        return envelope;
    }

    /**
     * Formats an error response with extra fields merged into the error map,
     * plus nextSteps and _meta sections.
     *
     * <p>Used when an error needs additional structured data beyond what
     * {@link ErrorResponse} provides (e.g., the {@code duplicates} array
     * in POTENTIAL_DUPLICATES responses).</p>
     *
     * @param error        the structured error response
     * @param extras       additional key-value pairs merged into the error map
     * @param nextSteps    suggested next actions for the LLM client
     * @param modelVersion current model version string
     * @return envelope map ready for JSON serialization
     */
    public Map<String, Object> formatErrorWithExtras(ErrorResponse error,
                                                      Map<String, Object> extras,
                                                      List<String> nextSteps,
                                                      String modelVersion) {
        Map<String, Object> errorMap = error.toMap();
        if (extras != null) {
            errorMap.putAll(extras);
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("error", errorMap);
        if (nextSteps != null && !nextSteps.isEmpty()) {
            envelope.put("nextSteps", nextSteps);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("modelVersion", modelVersion);
        meta.put("sessionActive", true);
        envelope.put("_meta", meta);

        return envelope;
    }

    /**
     * Adds a {@code modelChanged: true} flag to the {@code _meta} section of an envelope.
     * Used by handlers when the model version has changed since the session's last query.
     *
     * <p>Follows the established post-call {@code _meta} mutation pattern
     * (same as {@code warning} in Story 5-2 and {@code notFound} in Story 3-3).</p>
     *
     * @param envelope the response envelope from {@link #formatSuccess}
     */
    @SuppressWarnings("unchecked")
    public static void addModelChangedFlag(Map<String, Object> envelope) {
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        if (meta != null) {
            meta.put("modelChanged", true);
        }
    }

    /**
     * Adds a {@code cursor} token to the {@code _meta} section of an envelope.
     * Used by handlers when returning paginated results that have more pages available.
     *
     * <p>Follows the same post-call {@code _meta} mutation pattern as
     * {@link #addModelChangedFlag(Map)} and {@link #addCacheHitFlag(Map)}.</p>
     *
     * @param envelope the response envelope from {@link #formatSuccess}
     * @param cursor   the Base64-encoded cursor token for the next page
     */
    @SuppressWarnings("unchecked")
    public static void addCursorToken(Map<String, Object> envelope, String cursor) {
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        if (meta != null && cursor != null) {
            meta.put("cursor", cursor);
        }
    }

    /**
     * Adds a {@code cacheHit: true} flag to the {@code _meta} section of an envelope.
     * Used by handlers when returning a cached result (Story 5.4 AC2).
     *
     * <p>Follows the same post-call {@code _meta} mutation pattern as
     * {@link #addModelChangedFlag(Map)}.</p>
     *
     * @param envelope the response envelope from {@link #formatSuccess}
     */
    @SuppressWarnings("unchecked")
    public static void addCacheHitFlag(Map<String, Object> envelope) {
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        if (meta != null) {
            meta.put("cacheHit", true);
        }
    }

    /**
     * Adds a {@code sessionWarning} message to the {@code _meta} section of an envelope.
     * Used when the session is approaching an unhealthy state (e.g., high error count,
     * cache cleared due to model change).
     *
     * <p>Follows the same post-call {@code _meta} mutation pattern as
     * {@link #addModelChangedFlag(Map)} and {@link #addCacheHitFlag(Map)}.</p>
     *
     * @param envelope the response envelope from any format method
     * @param warning  the warning message describing the session concern
     */
    @SuppressWarnings("unchecked")
    public static void addSessionWarning(Map<String, Object> envelope, String warning) {
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        if (meta != null && warning != null && !warning.isBlank()) {
            meta.put("sessionWarning", warning);
        }
    }

    /**
     * Formats a graph response with the standard envelope.
     *
     * <p>Graph responses use a {@code graph} top-level key instead of {@code result},
     * containing {@code nodes} and {@code edges} arrays. The {@code _meta} section
     * includes {@code edgeCount} in addition to the standard fields.</p>
     *
     * @param graphData   the graph map with nodes/edges (and optional traversalSummary)
     * @param nextSteps   suggested next actions
     * @param modelVersion current model version string
     * @param nodeCount   number of nodes in the graph
     * @param edgeCount   number of edges in the graph
     * @param totalCount  total results available
     * @param isTruncated true if results were truncated
     * @return envelope map ready for JSON serialization
     */
    public Map<String, Object> formatGraph(Map<String, Object> graphData, List<String> nextSteps,
                                            String modelVersion, int nodeCount, int edgeCount,
                                            int totalCount, boolean isTruncated) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("graph", graphData);
        if (nextSteps != null && !nextSteps.isEmpty()) {
            envelope.put("nextSteps", nextSteps);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("modelVersion", modelVersion);
        meta.put("resultCount", nodeCount);
        meta.put("edgeCount", edgeCount);
        meta.put("totalCount", totalCount);
        meta.put("isTruncated", isTruncated);
        meta.put("sessionActive", true);
        envelope.put("_meta", meta);

        return envelope;
    }

    /**
     * Formats a summary response with the standard envelope.
     *
     * <p>Summary responses use a {@code summary} top-level key (string) instead
     * of {@code result}. Summary is always complete (never truncated).</p>
     *
     * @param summaryText  natural language summary string
     * @param nextSteps    suggested next actions
     * @param modelVersion current model version string
     * @param resultCount  number of items summarized
     * @param totalCount   total results available
     * @return envelope map ready for JSON serialization
     */
    public Map<String, Object> formatSummary(String summaryText, List<String> nextSteps,
                                              String modelVersion, int resultCount,
                                              int totalCount) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("summary", summaryText);
        if (nextSteps != null && !nextSteps.isEmpty()) {
            envelope.put("nextSteps", nextSteps);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("modelVersion", modelVersion);
        meta.put("resultCount", resultCount);
        meta.put("totalCount", totalCount);
        meta.put("isTruncated", false);
        meta.put("sessionActive", true);
        envelope.put("_meta", meta);

        return envelope;
    }

    /**
     * Formats a dry-run cost estimation response with the standard envelope.
     *
     * <p>Dry-run responses use a {@code dryRun} top-level key instead of {@code result},
     * and the {@code _meta} section includes a {@code dryRun: true} flag instead of
     * {@code resultCount}/{@code totalCount}/{@code isTruncated}.</p>
     *
     * @param estimatedCount  estimated number of results
     * @param estimatedTokens estimated token count
     * @param recommendedPreset recommended field preset
     * @param recommendation  human-readable recommendation text
     * @param nextSteps       suggested next actions
     * @param modelVersion    current model version string
     * @return envelope map ready for JSON serialization
     */
    public Map<String, Object> formatDryRun(int estimatedCount, int estimatedTokens,
                                             String recommendedPreset, String recommendation,
                                             List<String> nextSteps, String modelVersion) {
        Map<String, Object> envelope = new LinkedHashMap<>();

        Map<String, Object> dryRun = new LinkedHashMap<>();
        dryRun.put("estimatedResultCount", estimatedCount);
        dryRun.put("estimatedTokens", estimatedTokens);
        dryRun.put("recommendedPreset", recommendedPreset);
        dryRun.put("recommendation", recommendation);
        envelope.put("dryRun", dryRun);

        if (nextSteps != null && !nextSteps.isEmpty()) {
            envelope.put("nextSteps", nextSteps);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("modelVersion", modelVersion);
        meta.put("dryRun", true);
        meta.put("sessionActive", true);
        envelope.put("_meta", meta);

        return envelope;
    }

    /**
     * Serializes an envelope map to a JSON string.
     *
     * <p>Uses Jackson with {@code NON_NULL} inclusion — null fields
     * in DTOs are omitted from the output. Field names use camelCase
     * (Jackson default for Java records).</p>
     *
     * @param envelope the map to serialize
     * @return JSON string
     */
    public String toJsonString(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize response to JSON", e);
        }
    }
}
