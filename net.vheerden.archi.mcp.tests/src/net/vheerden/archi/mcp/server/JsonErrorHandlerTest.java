package net.vheerden.archi.mcp.server;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link JsonErrorHandler}.
 *
 * <p>Tests JSON-RPC error code mapping, JSON envelope building, and JSON escaping.
 * Does not test Jetty integration (handle method) — that requires a running Jetty server.</p>
 */
public class JsonErrorHandlerTest {

    private JsonErrorHandler handler;

    @Before
    public void setUp() {
        handler = new JsonErrorHandler();
    }

    // ---- HTTP to JSON-RPC code mapping (AC2, AC5) ----

    @Test
    public void shouldMap400_toInvalidRequest() {
        assertEquals(JsonErrorHandler.JSONRPC_INVALID_REQUEST, handler.mapHttpStatusToJsonRpcCode(400));
    }

    @Test
    public void shouldMap404_toMethodNotFound() {
        assertEquals(JsonErrorHandler.JSONRPC_METHOD_NOT_FOUND, handler.mapHttpStatusToJsonRpcCode(404));
    }

    @Test
    public void shouldMap405_toMethodNotFound() {
        assertEquals(JsonErrorHandler.JSONRPC_METHOD_NOT_FOUND, handler.mapHttpStatusToJsonRpcCode(405));
    }

    @Test
    public void shouldMap415_toInvalidRequest() {
        assertEquals(JsonErrorHandler.JSONRPC_INVALID_REQUEST, handler.mapHttpStatusToJsonRpcCode(415));
    }

    @Test
    public void shouldMap500_toInternalError() {
        assertEquals(JsonErrorHandler.JSONRPC_INTERNAL_ERROR, handler.mapHttpStatusToJsonRpcCode(500));
    }

    @Test
    public void shouldMapOther4xx_toInvalidRequest() {
        assertEquals(JsonErrorHandler.JSONRPC_INVALID_REQUEST, handler.mapHttpStatusToJsonRpcCode(403));
        assertEquals(JsonErrorHandler.JSONRPC_INVALID_REQUEST, handler.mapHttpStatusToJsonRpcCode(409));
        assertEquals(JsonErrorHandler.JSONRPC_INVALID_REQUEST, handler.mapHttpStatusToJsonRpcCode(422));
    }

    @Test
    public void shouldMapOther5xx_toServerError() {
        assertEquals(JsonErrorHandler.JSONRPC_SERVER_ERROR, handler.mapHttpStatusToJsonRpcCode(502));
        assertEquals(JsonErrorHandler.JSONRPC_SERVER_ERROR, handler.mapHttpStatusToJsonRpcCode(503));
    }

    // ---- JSON envelope building (AC1, AC2) ----

    @Test
    public void shouldBuildValidJsonRpcErrorEnvelope() {
        String json = handler.buildJsonRpcError(-32600, "Bad Request", 400, "/invalid");

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":null"));
        assertTrue(json.contains("\"code\":-32600"));
        assertTrue(json.contains("\"message\":\"Bad Request\""));
        assertTrue(json.contains("\"httpStatus\":400"));
        assertTrue(json.contains("\"uri\":\"/invalid\""));
    }

    @Test
    public void shouldBuildEnvelope_for404() {
        String json = handler.buildJsonRpcError(-32601, "Not Found", 404, "/unknown/path");

        assertTrue(json.contains("\"code\":-32601"));
        assertTrue(json.contains("\"message\":\"Not Found\""));
        assertTrue(json.contains("\"httpStatus\":404"));
        assertTrue(json.contains("\"uri\":\"/unknown/path\""));
    }

    @Test
    public void shouldBuildEnvelope_for500() {
        String json = handler.buildJsonRpcError(-32603, "Internal Server Error", 500, "/mcp");

        assertTrue(json.contains("\"code\":-32603"));
        assertTrue(json.contains("\"message\":\"Internal Server Error\""));
        assertTrue(json.contains("\"httpStatus\":500"));
    }

    @Test
    public void shouldProduceValidJson_parseable() throws Exception {
        String json = handler.buildJsonRpcError(-32600, "Test message", 400, "/test");

        // Verify it's valid JSON by parsing with Jackson
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.Map<?, ?> parsed = mapper.readValue(json, java.util.Map.class);

        assertEquals("2.0", parsed.get("jsonrpc"));
        assertNull(parsed.get("id"));
        assertNotNull(parsed.get("error"));
    }

    @Test
    public void shouldNeverExposeStackTraces_inErrorEnvelope() {
        String json = handler.buildJsonRpcError(-32603, "Internal Server Error", 500, "/mcp");

        assertFalse("Should not contain stack trace markers", json.contains("at "));
        assertFalse("Should not contain exception class names", json.contains("Exception"));
        assertFalse("Should not contain .java references", json.contains(".java"));
    }

    // ---- JSON escaping (AC1 — no raw data leaks) ----

    @Test
    public void shouldEscapeDoubleQuotes_inMessage() {
        String json = handler.buildJsonRpcError(-32600, "Invalid \"field\"", 400, "/test");
        assertTrue(json.contains("Invalid \\\"field\\\""));
    }

    @Test
    public void shouldEscapeBackslashes_inUri() {
        String json = handler.buildJsonRpcError(-32600, "Bad", 400, "/path\\with\\backslash");
        assertTrue(json.contains("/path\\\\with\\\\backslash"));
    }

    @Test
    public void shouldEscapeNewlines_inMessage() {
        String json = handler.buildJsonRpcError(-32600, "Line1\nLine2", 400, "/test");
        assertTrue(json.contains("Line1\\nLine2"));
    }

    @Test
    public void shouldEscapeControlChars_inMessage() {
        String json = handler.buildJsonRpcError(-32600, "Tab\there", 400, "/test");
        assertTrue(json.contains("Tab\\there"));
    }

    @Test
    public void shouldHandleNullMessage_inEscapeJson() {
        String result = JsonErrorHandler.escapeJson(null);
        assertEquals("", result);
    }

    @Test
    public void shouldHandleEmptyString_inEscapeJson() {
        String result = JsonErrorHandler.escapeJson("");
        assertEquals("", result);
    }

    @Test
    public void shouldNotEscapeNormalText() {
        String result = JsonErrorHandler.escapeJson("Normal text 123");
        assertEquals("Normal text 123", result);
    }

    // ---- Surrogate pair handling (Finding #5) ----

    @Test
    public void shouldHandleValidSurrogatePairs() {
        // U+1F600 (grinning face) = \uD83D\uDE00 in UTF-16
        String emoji = "\uD83D\uDE00";
        String result = JsonErrorHandler.escapeJson(emoji);
        assertEquals(emoji, result);
    }

    @Test
    public void shouldReplaceLoneHighSurrogate_withReplacementChar() {
        // Lone high surrogate without matching low surrogate
        String lone = "before\uD83Dafter";
        String result = JsonErrorHandler.escapeJson(lone);
        assertEquals("before\uFFFDafter", result);
    }

    @Test
    public void shouldReplaceLoneLowSurrogate_withReplacementChar() {
        // Lone low surrogate without preceding high surrogate
        String lone = "before\uDE00after";
        String result = JsonErrorHandler.escapeJson(lone);
        assertEquals("before\uFFFDafter", result);
    }
}
