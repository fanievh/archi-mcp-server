package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The tools that create an element only when nothing matches refuse an abstract element type as the
 * caller's mistake, the same way create-element does, rather than answering a server error.
 */
public class AbstractElementTypeToolTest {

    private static final String REFUSAL = "Invalid ArchiMate element type: BusinessElement"
            + " is abstract: it groups element types and names none of them";

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("model-abstract-element-type");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    @Test
    public void getOrCreateElement_shouldRefuseAnAbstractType_asInvalidElementType() {
        JsonNode error = h.servedError("get-or-create-element", "type", "BusinessElement", "name", "X");
        assertEquals("INVALID_ELEMENT_TYPE", error.path("code").asText());
        assertEquals(REFUSAL, error.path("message").asText());
    }

    @Test
    public void searchAndCreate_shouldRefuseAnAbstractType_asInvalidElementType() {
        JsonNode error = h.servedError("search-and-create", "query", "nomatch-abstract",
                "createType", "BusinessElement", "createName", "X");
        assertEquals("INVALID_ELEMENT_TYPE", error.path("code").asText());
        assertEquals(REFUSAL, error.path("message").asText());
    }
}
