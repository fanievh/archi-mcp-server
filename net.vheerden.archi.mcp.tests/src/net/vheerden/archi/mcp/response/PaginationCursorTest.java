package net.vheerden.archi.mcp.response;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link PaginationCursor}.
 */
public class PaginationCursorTest {

    // ---- Encode/Decode Round-Trip Tests ----

    @Test
    public void shouldEncodeAndDecode_roundTrip() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", "auth");
        params.put("type", "ApplicationComponent");
        params.put("layer", null);

        String encoded = PaginationCursor.encode("abc123", 50, 50, 250, params);
        assertNotNull(encoded);
        assertFalse(encoded.isEmpty());

        PaginationCursor.CursorData decoded = PaginationCursor.decode(encoded);
        assertEquals(PaginationCursor.CURSOR_VERSION, decoded.version());
        assertEquals("abc123", decoded.modelVersion());
        assertEquals(50, decoded.offset());
        assertEquals(50, decoded.limit());
        assertEquals(250, decoded.total());
        assertEquals("auth", decoded.params().get("query"));
        assertEquals("ApplicationComponent", decoded.params().get("type"));
        assertNull("null param value should decode as null", decoded.params().get("layer"));
    }

    @Test
    public void shouldEncodeWithParams_andDecodeCorrectly() throws Exception {
        Map<String, String> params = Map.of(
                "query", "search term",
                "type", "Node",
                "layer", "Technology");

        String encoded = PaginationCursor.encode("v42", 100, 25, 500, params);
        PaginationCursor.CursorData decoded = PaginationCursor.decode(encoded);

        assertEquals("v42", decoded.modelVersion());
        assertEquals(100, decoded.offset());
        assertEquals(25, decoded.limit());
        assertEquals(500, decoded.total());
        assertEquals("search term", decoded.params().get("query"));
        assertEquals("Node", decoded.params().get("type"));
        assertEquals("Technology", decoded.params().get("layer"));
    }

    // ---- Invalid Cursor Tests ----

    @Test(expected = PaginationCursor.InvalidCursorException.class)
    public void shouldThrowInvalidCursor_whenBadBase64() throws Exception {
        PaginationCursor.decode("not!valid!base64!!!");
    }

    @Test(expected = PaginationCursor.InvalidCursorException.class)
    public void shouldThrowInvalidCursor_whenBadJson() throws Exception {
        String notJson = Base64.getEncoder().encodeToString(
                "this is not json".getBytes(StandardCharsets.UTF_8));
        PaginationCursor.decode(notJson);
    }

    @Test(expected = PaginationCursor.InvalidCursorException.class)
    public void shouldThrowInvalidCursor_whenVersionMismatch() throws Exception {
        String json = "{\"v\":99,\"mv\":\"abc\",\"offset\":0,\"limit\":50,\"total\":100,\"params\":{}}";
        String encoded = Base64.getEncoder().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));
        PaginationCursor.decode(encoded);
    }

    @Test(expected = PaginationCursor.InvalidCursorException.class)
    public void shouldThrowInvalidCursor_whenNegativeOffset() throws Exception {
        String json = "{\"v\":1,\"mv\":\"abc\",\"offset\":-1,\"limit\":50,\"total\":100,\"params\":{}}";
        String encoded = Base64.getEncoder().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));
        PaginationCursor.decode(encoded);
    }

    @Test(expected = PaginationCursor.InvalidCursorException.class)
    public void shouldThrowInvalidCursor_whenZeroLimit() throws Exception {
        String json = "{\"v\":1,\"mv\":\"abc\",\"offset\":0,\"limit\":0,\"total\":100,\"params\":{}}";
        String encoded = Base64.getEncoder().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));
        PaginationCursor.decode(encoded);
    }

    @Test(expected = PaginationCursor.InvalidCursorException.class)
    public void shouldThrowInvalidCursor_whenMissingModelVersion() throws Exception {
        String json = "{\"v\":1,\"offset\":0,\"limit\":50,\"total\":100,\"params\":{}}";
        String encoded = Base64.getEncoder().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));
        PaginationCursor.decode(encoded);
    }

    @Test(expected = PaginationCursor.InvalidCursorException.class)
    public void shouldThrowInvalidCursor_whenNegativeTotal() throws Exception {
        String json = "{\"v\":1,\"mv\":\"abc\",\"offset\":0,\"limit\":50,\"total\":-5,\"params\":{}}";
        String encoded = Base64.getEncoder().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));
        PaginationCursor.decode(encoded);
    }

    @Test(expected = PaginationCursor.InvalidCursorException.class)
    public void shouldThrowInvalidCursor_whenEmpty() throws Exception {
        PaginationCursor.decode("");
    }

    @Test(expected = PaginationCursor.InvalidCursorException.class)
    public void shouldThrowInvalidCursor_whenNull() throws Exception {
        PaginationCursor.decode(null);
    }

    // ---- Null/Empty Params Tests ----

    @Test
    public void shouldHandleNullParams_whenNoQueryParams() throws Exception {
        String encoded = PaginationCursor.encode("v1", 0, 50, 100, null);
        PaginationCursor.CursorData decoded = PaginationCursor.decode(encoded);

        assertNotNull(decoded.params());
        assertTrue(decoded.params().isEmpty());
    }

    @Test
    public void shouldHandleEmptyParams_whenEmptyMap() throws Exception {
        String encoded = PaginationCursor.encode("v1", 0, 50, 100, Collections.emptyMap());
        PaginationCursor.CursorData decoded = PaginationCursor.decode(encoded);

        assertNotNull(decoded.params());
        assertTrue(decoded.params().isEmpty());
    }

    // ---- Constants Tests ----

    @Test
    public void shouldHaveCorrectConstants() {
        assertEquals(1, PaginationCursor.CURSOR_VERSION);
        assertEquals(50, PaginationCursor.DEFAULT_PAGE_SIZE);
        assertEquals(500, PaginationCursor.MAX_PAGE_SIZE);
    }

    // ---- Zero Offset Tests ----

    @Test
    public void shouldAcceptZeroOffset() throws Exception {
        String encoded = PaginationCursor.encode("v1", 0, 50, 100, null);
        PaginationCursor.CursorData decoded = PaginationCursor.decode(encoded);
        assertEquals(0, decoded.offset());
    }
}
