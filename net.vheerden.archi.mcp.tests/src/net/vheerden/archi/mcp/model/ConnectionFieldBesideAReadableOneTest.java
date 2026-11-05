package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;

/**
 * Every argument sent to {@code update-view-connection} must read by its own key's rule, on the tool and in
 * {@code bulk-mutate}: one that does not is refused for the whole call, even beside a field that reads, so
 * the readable field is not applied either and the route is kept. It used to be dropped in silence beside a
 * readable field, and the call answered success having applied only part of what was asked.
 *
 * <p>JSON null on a present key is refused too, as it already was when the key stood alone. The connection
 * starts with one stored bendpoint, and every refusal asserts it is still there.</p>
 */
public class ConnectionFieldBesideAReadableOneTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TAIL = ". Every argument sent must read, so nothing was changed, and the route "
            + "was kept.";

    private ViewContentsFixture f;
    private IDiagramModelArchimateConnection conn;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-unreadable-beside-a-readable-argument");
        IArchimateElement a = f.element("el-a");
        IArchimateElement b = f.element("el-b");
        IDiagramModelArchimateObject va = f.object(f.view, "vo-a", a, 40, 40, 120, 60);
        IDiagramModelArchimateObject vb = f.object(f.view, "vo-b", b, 400, 40, 120, 60);
        conn = f.archimateConnection("conn-ab", f.relationship("rel-ab", a, b), va, vb);
        conn.getBendpoints().add(f.bendpoint(0, -100, 0, 100));
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private static Map<String, Object> params(Object... keysAndValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("viewConnectionId", "conn-ab");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            p.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return p;
    }

    private String state() {
        return conn.getBendpoints().size() + " bendpoints, textPosition " + conn.getTextPosition() + ", lineColor "
                + conn.getLineColor() + ", lineWidth " + conn.getLineWidth() + ", name shown " + conn.isNameVisible();
    }

    /** Refused with {@code message} on the tool and on bulk, as a caller error, with nothing changed. */
    private void refusedOnBoth(Map<String, Object> p, String message) throws Exception {
        String before = state();
        String out = f.serve("update-view-connection", new LinkedHashMap<>(p));
        assertTrue("refused: " + out, out.startsWith("ERROR "));
        JsonNode error = JSON.readTree(out.substring("ERROR ".length())).path("error");
        assertEquals("INVALID_PARAMETER", error.path("code").asText());
        assertEquals("tool", message, error.path("message").asText());
        assertEquals("Pass each value as the type named, or omit it.", error.path("suggestedCorrection").asText());
        assertEquals("the tool changed nothing", before, state());
        BulkOperationFailure row = bulkRow(new LinkedHashMap<>(p));
        assertEquals("bulk", message, row.message());
        assertEquals("INVALID_PARAMETER", row.errorCode());
        assertEquals("bulk changed nothing", before, state());
    }

    private BulkOperationFailure bulkRow(Map<String, Object> p) {
        try {
            BulkMutationResult result = f.accessor.executeBulk(ViewContentsFixture.SESSION,
                    List.of(new BulkOperation("update-view-connection", p)), null, false);
            fail("expected a refusal, got allSucceeded=" + result.allSucceeded());
            return null;
        } catch (BulkValidationException e) {
            assertEquals("one failure row: " + e.getFailures(), 1, e.getFailures().size());
            return e.getFailures().get(0);
        }
    }

    // ---- an unreadable field beside a readable one ----

    @Test
    public void shouldRefuseAStringLineWidth_besideALabelPosition_andApplyNeither() throws Exception {
        refusedOnBoth(params("lineWidth", "2", "labelPosition", "source"),
                "update-view-connection cannot read lineWidth (expected an integer from 1 to 3, got the string \"2\")"
                        + TAIL);
    }

    @Test
    public void shouldRefuseAnEmptyFontStyle_besideALineColor_andApplyNeither() throws Exception {
        refusedOnBoth(params("fontStyle", "", "lineColor", "#ff0000"),
                "update-view-connection cannot read fontStyle (expected one of normal, bold, italic, bold-italic, "
                        + "got an empty string)" + TAIL);
        assertNull(conn.getLineColor());
    }

    @Test
    public void shouldRefuseANullLineColor_besideALabelPosition() throws Exception {
        refusedOnBoth(params("lineColor", null, "labelPosition", "source"),
                "update-view-connection cannot read lineColor (expected a #RRGGBB string, or \"\" to clear, got null)"
                        + TAIL);
    }

    @Test
    public void shouldRefuseANullShowLabel_besideALineColor() throws Exception {
        refusedOnBoth(params("showLabel", null, "lineColor", "#ff0000"),
                "update-view-connection cannot read showLabel (expected true or false, got null)" + TAIL);
    }

    @Test
    public void shouldNameEveryUnreadableField_inTheToolsOrder() throws Exception {
        refusedOnBoth(params("fontSize", "9", "showLabel", Boolean.FALSE, "lineWidth", "2"),
                "update-view-connection cannot read lineWidth (expected an integer from 1 to 3, got the string \"2\"); "
                        + "fontSize (expected a positive integer, got the string \"9\")" + TAIL);
    }

    @Test
    public void shouldBoundANumberItRepeats() throws Exception {
        String digits = "1".repeat(60);
        refusedOnBoth(params("fontName", new java.math.BigInteger(digits), "lineColor", "#ff0000"),
                "update-view-connection cannot read fontName (expected a string, or \"\" to clear, got the biginteger '"
                        + "1".repeat(CallerText.MAX_ECHOED) + "' (the start of 60 characters))" + TAIL);
    }

    @Test
    public void shouldPublishTheRule_inTheToolsDescription() throws Exception {
        EchoToolHarness h = new EchoToolHarness("model-unreadable-argument-description");
        try {
            String description = h.registry().getToolSpecifications().stream()
                    .filter(s -> s.tool().name().equals("update-view-connection")).findFirst().orElseThrow()
                    .tool().description();
            assertTrue(description, description.contains("Every argument you send must read, or the call is "
                    + "refused: a listed argument holding a value it cannot read (a wrong type, a blank fontStyle, "
                    + "null) is refused even beside one it can, and nothing is changed. "));
        } finally {
            h.dispose();
        }
    }

    // ---- what still reads ----

    @Test
    public void shouldApplyAWholeLineWidth_andALabelPosition_together() {
        String out = f.serve("update-view-connection", params("lineWidth", 2, "labelPosition", "source"));
        assertFalse(out, out.startsWith("ERROR "));
        assertEquals(2, conn.getLineWidth());
        assertEquals(0, conn.getTextPosition());
        assertEquals("the route is kept", 1, conn.getBendpoints().size());
    }

    @Test
    public void shouldStillClearALineColor_withTheEmptyString_keepingTheRoute() {
        conn.setLineColor("#00ff00");
        String out = f.serve("update-view-connection", params("lineColor", ""));
        assertFalse(out, out.startsWith("ERROR "));
        assertNull(conn.getLineColor());
        assertEquals(1, conn.getBendpoints().size());
    }
}
