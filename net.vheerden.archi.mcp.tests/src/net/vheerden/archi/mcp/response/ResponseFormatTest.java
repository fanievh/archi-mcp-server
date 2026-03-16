package net.vheerden.archi.mcp.response;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link ResponseFormat}.
 */
public class ResponseFormatTest {

    @Test
    public void shouldHaveThreeValues() {
        assertEquals(3, ResponseFormat.values().length);
    }

    @Test
    public void shouldReturnCorrectStringValues() {
        assertEquals("json", ResponseFormat.JSON.value());
        assertEquals("graph", ResponseFormat.GRAPH.value());
        assertEquals("summary", ResponseFormat.SUMMARY.value());
    }

    @Test
    public void fromString_shouldParseValidValues() {
        assertEquals(ResponseFormat.JSON, ResponseFormat.fromString("json"));
        assertEquals(ResponseFormat.GRAPH, ResponseFormat.fromString("graph"));
        assertEquals(ResponseFormat.SUMMARY, ResponseFormat.fromString("summary"));
    }

    @Test
    public void fromString_shouldReturnNullForInvalidValue() {
        assertNull(ResponseFormat.fromString("xml"));
        assertNull(ResponseFormat.fromString("CSV"));
        assertNull(ResponseFormat.fromString(""));
    }

    @Test
    public void fromString_shouldReturnNullForNull() {
        assertNull(ResponseFormat.fromString(null));
    }

    @Test
    public void fromString_shouldBeCaseSensitive() {
        assertNull(ResponseFormat.fromString("JSON"));
        assertNull(ResponseFormat.fromString("Graph"));
        assertNull(ResponseFormat.fromString("SUMMARY"));
    }
}
