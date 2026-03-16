package net.vheerden.archi.mcp.response;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.ElementDto;

/**
 * Tests for {@link ResponseFormatter}.
 */
public class ResponseFormatterTest {

    private ResponseFormatter formatter;

    @Before
    public void setUp() {
        formatter = new ResponseFormatter();
    }

    // ---- formatSuccess tests ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldBuildSuccessEnvelope_withAllFields() {
        ElementDto element = ElementDto.standard("id-1", "My Element",
                "BusinessActor", "Business", "A description",
                List.of(Map.of("key", "owner", "value", "team-a")));

        Map<String, Object> envelope = formatter.formatSuccess(
                element,
                List.of("get-relationships", "get-views"),
                "42", 1, 1, false);

        assertNotNull(envelope.get("result"));
        assertEquals(element, envelope.get("result"));

        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertEquals(2, nextSteps.size());
        assertEquals("get-relationships", nextSteps.get(0));

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals("42", meta.get("modelVersion"));
        assertEquals(1, meta.get("resultCount"));
        assertEquals(false, meta.get("isTruncated"));
        assertEquals(1, meta.get("totalCount"));
    }

    @Test
    public void shouldOmitNextSteps_whenNull() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        assertFalse(envelope.containsKey("nextSteps"));
    }

    @Test
    public void shouldOmitNextSteps_whenEmpty() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", List.of(), "1", 1, 1, false);

        assertFalse(envelope.containsKey("nextSteps"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldIncludeTruncationMetadata_whenTruncated() {
        Map<String, Object> envelope = formatter.formatSuccess(
                List.of("a", "b"), List.of("next"), "5", 2, 100, true);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("isTruncated"));
        assertEquals(2, meta.get("resultCount"));
        assertEquals(100, meta.get("totalCount"));
    }

    // ---- formatError tests ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldBuildErrorEnvelope() {
        ErrorResponse error = new ErrorResponse(
                ErrorCode.ELEMENT_NOT_FOUND,
                "Not found",
                "No element with ID 'xyz'",
                "Use search-elements",
                null);

        Map<String, Object> envelope = formatter.formatError(error);

        assertTrue(envelope.containsKey("error"));
        assertFalse(envelope.containsKey("result"));

        Map<String, Object> errorMap = (Map<String, Object>) envelope.get("error");
        assertEquals("ELEMENT_NOT_FOUND", errorMap.get("code"));
        assertEquals("Not found", errorMap.get("message"));
        assertEquals("No element with ID 'xyz'", errorMap.get("details"));
        assertEquals("Use search-elements", errorMap.get("suggestedCorrection"));
        assertFalse(errorMap.containsKey("archiMateReference"));
    }

    // ---- toJsonString tests ----

    @Test
    public void shouldSerializeEnvelopeToJson() {
        Map<String, Object> envelope = formatter.formatSuccess(
                Map.of("name", "Test"), List.of("next-step"), "1", 1, 1, false);

        String json = formatter.toJsonString(envelope);

        assertNotNull(json);
        assertTrue(json.contains("\"result\""));
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"Test\""));
        assertTrue(json.contains("\"_meta\""));
        assertTrue(json.contains("\"modelVersion\""));
    }

    @Test
    public void shouldOmitNullFields_inDtoSerialization() throws Exception {
        ElementDto minimal = ElementDto.minimal("id-1", "Name");

        Map<String, Object> envelope = formatter.formatSuccess(
                minimal, null, "1", 1, 1, false);

        String json = formatter.toJsonString(envelope);

        // null fields in ElementDto should be omitted
        assertFalse(json.contains("\"type\""));
        assertFalse(json.contains("\"layer\""));
        assertFalse(json.contains("\"documentation\""));
        assertFalse(json.contains("\"properties\""));
        // present fields should exist
        assertTrue(json.contains("\"id\""));
        assertTrue(json.contains("\"name\""));
    }

    @Test
    public void shouldProduceCamelCaseFieldNames() {
        Map<String, Object> envelope = formatter.formatSuccess(
                Map.of("fieldName", "value"), null, "1", 1, 1, false);

        String json = formatter.toJsonString(envelope);

        assertTrue(json.contains("\"fieldName\""));
        assertTrue(json.contains("\"modelVersion\""));
        assertTrue(json.contains("\"resultCount\""));
        assertTrue(json.contains("\"isTruncated\""));
        assertTrue(json.contains("\"totalCount\""));
    }

    // ---- Story 5.3: addModelChangedFlag tests ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldAddModelChangedFlag_toMetaSection() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        ResponseFormatter.addModelChangedFlag(envelope);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("modelChanged"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldNotContainModelChanged_whenFlagNotAdded() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse("modelChanged should be absent by default", meta.containsKey("modelChanged"));
    }

    @Test
    public void shouldHandleNullMeta_gracefully() {
        // Envelope without _meta — should not throw
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("result", "test");

        ResponseFormatter.addModelChangedFlag(envelope); // no-op, no exception
    }

    // ---- Story 5.4: addCacheHitFlag tests ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldAddCacheHitFlag_toMetaSection() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        ResponseFormatter.addCacheHitFlag(envelope);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("cacheHit"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldNotContainCacheHit_whenFlagNotAdded() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse("cacheHit should be absent by default", meta.containsKey("cacheHit"));
    }

    @Test
    public void shouldHandleNullMeta_gracefullyForCacheHit() {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("result", "test");

        ResponseFormatter.addCacheHitFlag(envelope); // no-op, no exception
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldAllowBothCacheHitAndModelChanged_inSameMeta() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        ResponseFormatter.addCacheHitFlag(envelope);
        ResponseFormatter.addModelChangedFlag(envelope);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("cacheHit"));
        assertEquals(true, meta.get("modelChanged"));
    }

    // ---- Story 6.1: addCursorToken tests ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldAddCursorToken_toMetaSection() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        ResponseFormatter.addCursorToken(envelope, "abc123cursor");

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals("abc123cursor", meta.get("cursor"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldNotContainCursor_whenTokenNotAdded() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse("cursor should be absent by default", meta.containsKey("cursor"));
    }

    @Test
    public void shouldHandleNullMeta_gracefullyForCursorToken() {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("result", "test");

        ResponseFormatter.addCursorToken(envelope, "cursor"); // no-op, no exception
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldNotAddCursor_whenCursorIsNull() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        ResponseFormatter.addCursorToken(envelope, null);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse("cursor should not be added when null", meta.containsKey("cursor"));
    }

    // ---- Story 6.1: ErrorCode INVALID_CURSOR test ----

    @Test
    public void shouldHaveInvalidCursorErrorCode() {
        ErrorCode code = ErrorCode.INVALID_CURSOR;
        assertEquals("INVALID_CURSOR", code.name());
    }

    @Test
    public void shouldSerializeErrorEnvelopeToJson() {
        ErrorResponse error = new ErrorResponse(
                ErrorCode.MODEL_NOT_LOADED,
                "No model loaded");

        Map<String, Object> envelope = formatter.formatError(error);
        String json = formatter.toJsonString(envelope);

        assertTrue(json.contains("\"error\""));
        assertTrue(json.contains("\"MODEL_NOT_LOADED\""));
        assertTrue(json.contains("\"No model loaded\""));
        // null fields should be omitted
        assertFalse(json.contains("\"details\""));
        assertFalse(json.contains("\"suggestedCorrection\""));
    }

    // ---- Story 6.2: formatDryRun tests ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldFormatDryRun_withCorrectEnvelopeStructure() {
        Map<String, Object> envelope = formatter.formatDryRun(
                247, 15438, "minimal",
                "Large result set (247 items, ~15K tokens).",
                List.of("Add type filter", "Use fields=minimal"),
                "abc123");

        // dryRun object at top level
        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        assertNotNull("dryRun key must be present", dryRun);
        assertEquals(247, dryRun.get("estimatedResultCount"));
        assertEquals(15438, dryRun.get("estimatedTokens"));
        assertEquals("minimal", dryRun.get("recommendedPreset"));
        assertTrue(((String) dryRun.get("recommendation")).contains("247 items"));

        // nextSteps
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(2, nextSteps.size());
        assertEquals("Add type filter", nextSteps.get(0));

        // _meta
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals("abc123", meta.get("modelVersion"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldFormatDryRun_withDryRunFlagInMeta() {
        Map<String, Object> envelope = formatter.formatDryRun(
                10, 500, "standard", "Small result set.",
                List.of("Execute query"), "v1");

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("dryRun"));
    }

    @Test
    public void shouldFormatDryRun_withoutResultKey() {
        Map<String, Object> envelope = formatter.formatDryRun(
                10, 500, "standard", "Small result set.",
                List.of("Execute query"), "v1");

        assertFalse("dryRun envelope must NOT contain 'result' key",
                envelope.containsKey("result"));
        assertFalse("dryRun envelope must NOT contain 'resultCount' in _meta",
                envelope.get("_meta").toString().contains("resultCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldFormatDryRun_withoutMetaResultFields() {
        Map<String, Object> envelope = formatter.formatDryRun(
                10, 500, "standard", "Small.", List.of("next"), "v1");

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse("No resultCount in dryRun _meta", meta.containsKey("resultCount"));
        assertFalse("No totalCount in dryRun _meta", meta.containsKey("totalCount"));
        assertFalse("No isTruncated in dryRun _meta", meta.containsKey("isTruncated"));
    }

    @Test
    public void shouldFormatDryRun_omitNextSteps_whenEmpty() {
        Map<String, Object> envelope = formatter.formatDryRun(
                10, 500, "standard", "Small.", List.of(), "v1");

        assertFalse(envelope.containsKey("nextSteps"));
    }

    @Test
    public void shouldFormatDryRun_omitNextSteps_whenNull() {
        Map<String, Object> envelope = formatter.formatDryRun(
                10, 500, "standard", "Small.", null, "v1");

        assertFalse(envelope.containsKey("nextSteps"));
    }

    // ---- formatGraph tests (Story 6.3) ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldBuildGraphEnvelope_withGraphKeyNotResult() {
        Map<String, Object> graphData = Map.of(
                "nodes", List.of(Map.of("id", "e1", "name", "Portal")),
                "edges", List.of());

        Map<String, Object> envelope = formatter.formatGraph(
                graphData, List.of("next step"), "v1", 1, 0, 1, false);

        assertTrue("Graph envelope must contain 'graph' key", envelope.containsKey("graph"));
        assertFalse("Graph envelope must NOT contain 'result' key", envelope.containsKey("result"));

        Map<String, Object> graph = (Map<String, Object>) envelope.get("graph");
        assertNotNull(graph.get("nodes"));
        assertNotNull(graph.get("edges"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldBuildGraphEnvelope_withEdgeCountInMeta() {
        Map<String, Object> graphData = Map.of(
                "nodes", List.of(Map.of("id", "e1"), Map.of("id", "e2")),
                "edges", List.of(Map.of("id", "r1")));

        Map<String, Object> envelope = formatter.formatGraph(
                graphData, List.of("next"), "v2", 2, 1, 3, false);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals("v2", meta.get("modelVersion"));
        assertEquals(2, meta.get("resultCount"));
        assertEquals(1, meta.get("edgeCount"));
        assertEquals(3, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));
    }

    @Test
    public void shouldBuildGraphEnvelope_omitNextSteps_whenEmpty() {
        Map<String, Object> graphData = Map.of("nodes", List.of(), "edges", List.of());
        Map<String, Object> envelope = formatter.formatGraph(
                graphData, List.of(), "v1", 0, 0, 0, false);

        assertFalse(envelope.containsKey("nextSteps"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldBuildGraphEnvelope_withNextSteps() {
        Map<String, Object> graphData = Map.of("nodes", List.of(), "edges", List.of());
        Map<String, Object> envelope = formatter.formatGraph(
                graphData, List.of("step1", "step2"), "v1", 0, 0, 0, false);

        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertEquals(2, nextSteps.size());
    }

    // ---- formatSummary tests (Story 6.3) ----

    @Test
    public void shouldBuildSummaryEnvelope_withSummaryKeyNotResult() {
        Map<String, Object> envelope = formatter.formatSummary(
                "Found 5 elements.", List.of("next"), "v1", 5, 5);

        assertTrue("Summary envelope must contain 'summary' key", envelope.containsKey("summary"));
        assertFalse("Summary envelope must NOT contain 'result' key", envelope.containsKey("result"));
        assertEquals("Found 5 elements.", envelope.get("summary"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldBuildSummaryEnvelope_withIsTruncatedFalse() {
        Map<String, Object> envelope = formatter.formatSummary(
                "Summary text.", List.of("next"), "v1", 10, 10);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(false, meta.get("isTruncated"));
        assertEquals(10, meta.get("resultCount"));
        assertEquals(10, meta.get("totalCount"));
    }

    @Test
    public void shouldBuildSummaryEnvelope_omitNextSteps_whenNull() {
        Map<String, Object> envelope = formatter.formatSummary(
                "Summary.", null, "v1", 1, 1);

        assertFalse(envelope.containsKey("nextSteps"));
    }

    // ---- Post-call _meta mutations work on graph/summary envelopes ----

    @Test
    @SuppressWarnings("unchecked")
    public void addModelChangedFlag_shouldWorkOnGraphEnvelope() {
        Map<String, Object> graphData = Map.of("nodes", List.of(), "edges", List.of());
        Map<String, Object> envelope = formatter.formatGraph(
                graphData, List.of(), "v1", 0, 0, 0, false);

        ResponseFormatter.addModelChangedFlag(envelope);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("modelChanged"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void addCacheHitFlag_shouldWorkOnSummaryEnvelope() {
        Map<String, Object> envelope = formatter.formatSummary(
                "Summary.", List.of(), "v1", 1, 1);

        ResponseFormatter.addCacheHitFlag(envelope);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("cacheHit"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void addCursorToken_shouldWorkOnGraphEnvelope() {
        Map<String, Object> graphData = Map.of("nodes", List.of(), "edges", List.of());
        Map<String, Object> envelope = formatter.formatGraph(
                graphData, List.of(), "v1", 0, 0, 0, false);

        ResponseFormatter.addCursorToken(envelope, "cursor-abc");

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals("cursor-abc", meta.get("cursor"));
    }

    // ---- Story 8-8: sessionActive in _meta (AC4) ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldIncludeSessionActive_inErrorEnvelope() {
        ErrorResponse error = new ErrorResponse(
                ErrorCode.ELEMENT_NOT_FOUND, "Not found");
        Map<String, Object> envelope = formatter.formatError(error);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull("formatError must include _meta section", meta);
        assertEquals(true, meta.get("sessionActive"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldIncludeSessionActive_inSuccessEnvelope() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("sessionActive"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldIncludeSessionActive_inGraphEnvelope() {
        Map<String, Object> graphData = Map.of("nodes", List.of(), "edges", List.of());
        Map<String, Object> envelope = formatter.formatGraph(
                graphData, List.of(), "v1", 0, 0, 0, false);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("sessionActive"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldIncludeSessionActive_inSummaryEnvelope() {
        Map<String, Object> envelope = formatter.formatSummary(
                "Summary.", null, "v1", 1, 1);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("sessionActive"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldIncludeSessionActive_inDryRunEnvelope() {
        Map<String, Object> envelope = formatter.formatDryRun(
                10, 500, "standard", "Small.", null, "v1");

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("sessionActive"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldIncludeSessionActive_inErrorWithExtrasEnvelope() {
        ErrorResponse error = new ErrorResponse(
                ErrorCode.POTENTIAL_DUPLICATES, "Duplicates found");
        Map<String, Object> envelope = formatter.formatErrorWithExtras(
                error, Map.of("duplicates", List.of()), List.of("next"), "v1");

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("sessionActive"));
    }

    // ---- Story 8-8: addSessionWarning (AC4) ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldAddSessionWarning_toMetaSection() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        ResponseFormatter.addSessionWarning(envelope, "Model changed during session");

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals("Model changed during session", meta.get("sessionWarning"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldNotAddSessionWarning_whenNull() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        ResponseFormatter.addSessionWarning(envelope, null);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse(meta.containsKey("sessionWarning"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldNotAddSessionWarning_whenBlank() {
        Map<String, Object> envelope = formatter.formatSuccess(
                "result", null, "1", 1, 1, false);

        ResponseFormatter.addSessionWarning(envelope, "   ");

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse(meta.containsKey("sessionWarning"));
    }

    @Test
    public void shouldHandleNullMeta_gracefullyForSessionWarning() {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("result", "test");

        ResponseFormatter.addSessionWarning(envelope, "warning"); // no-op, no exception
    }
}
