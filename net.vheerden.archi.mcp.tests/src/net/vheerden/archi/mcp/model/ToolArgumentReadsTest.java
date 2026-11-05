package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIdentifier;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A declared argument is read by one rule on a tool and on its {@code bulk-mutate} operation, and a value the
 * rule cannot read is refused by name with nothing changed, through the tools as the server registers them
 * (call scope included). Every refusal is pinned whole: a pin that only checks a refusal names the key passes
 * for any refusal that names it.
 *
 * <p>The fixture view holds {@code vo-a} at (40, 40, 120x60) and {@code vo-b} at (400, 40, 120x60), joined by
 * {@code conn-ab}; its content therefore spans y 40..100 from x 40. Notes carry an explicit size because the
 * fitted height measures text, which needs a display.</p>
 */
public class ToolArgumentReadsTest {

    private static final String NOT_PLACED = ". None of them has run yet, so the note would be placed against "
            + "content the view will no longer have; it was not placed.";
    private static final String STALE_PREFIX = "position places a note against the view's content as it was "
            + "before this call and any open batch, and earlier operations have already changed that content on "
            + "view 'view-source' — ";
    private static final String NESTED = "Cannot use 'position' with 'parentViewObjectId'. Position-based "
            + "placement only works for top-level notes.";
    private static final String MIDDLE = "Invalid position value: 'middle'. Must be 'above-content' or "
            + "'below-content'";

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("reads-model");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    private static Map<String, Object> op(String tool, Map<String, Object> params) {
        return Map.of("tool", tool, "params", params);
    }

    private static Map<String, Object> note(Object... extra) {
        Map<String, Object> params = args("viewId", "view-source", "content", "Title", "width", 100, "height", 40);
        params.putAll(args(extra));
        return params;
    }

    private JsonNode bulk(Object... ops) {
        return h.call("bulk-mutate", args("operations", List.of(ops)));
    }

    private JsonNode bulkContinuing(boolean continueOnError, Object... ops) {
        return h.call("bulk-mutate", args("continueOnError", continueOnError, "operations", List.of(ops)));
    }

    /** The whole message of the one failure row a refused bulk call reports. */
    private static String row(JsonNode reply) {
        assertTrue(reply.toString(), reply.path("isError").asBoolean());
        JsonNode error = reply.path("error");
        assertEquals(reply.toString(), "BULK_VALIDATION_FAILED", error.path("code").asText());
        String message = error.path("message").asText();
        assertTrue(message, message.startsWith("Operation 0 ("));
        return message.substring(message.indexOf("): ") + 3);
    }

    private List<IDiagramModelNote> notes() {
        List<IDiagramModelNote> out = new ArrayList<>();
        for (Object o : h.f.view.getChildren()) {
            if (o instanceof IDiagramModelNote n) {
                out.add(n);
            }
        }
        return out;
    }

    private static String bounds(IDiagramModelObject o) {
        return o.getBounds().getX() + "," + o.getBounds().getY() + "," + o.getBounds().getWidth() + "x"
                + o.getBounds().getHeight();
    }

    private String viewState() {
        StringBuilder sb = new StringBuilder();
        for (Object o : h.f.view.getChildren()) {
            sb.append(((IIdentifier) o).getId()).append('=').append(bounds((IDiagramModelObject) o)).append(' ');
        }
        return sb.append(conn().getBendpoints().size()).append(" bendpoints").toString();
    }

    private IDiagramModelArchimateConnection conn() {
        IDiagramModelArchimateObject source = (IDiagramModelArchimateObject) h.f.view.getChildren().get(0);
        return (IDiagramModelArchimateConnection) source.getSourceConnections().get(0);
    }

    private IDiagramModelObject vo(String id) {
        for (Object o : h.f.view.getChildren()) {
            if (id.equals(((IIdentifier) o).getId())) {
                return (IDiagramModelObject) o;
            }
        }
        throw new AssertionError("no " + id);
    }

    private IAssociationRelationship association() {
        return (IAssociationRelationship) h.f.model.getFolder(FolderType.RELATIONS).getElements().stream()
                .filter(e -> "rel-ab".equals(((IIdentifier) e).getId())).findFirst().orElseThrow();
    }

    private static String flagRefusal(String key, String described) {
        return key + " must be true or false, got " + described + ". Nothing was changed.";
    }

    // ---- bulk add-note-to-view reads position and gap ----

    @Test
    public void shouldPlaceABulkNoteAboveTheContent() {
        JsonNode reply = bulk(op("add-note-to-view", note("position", "above-content")));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals("one note, 10 above the content's top edge at its left edge", "40,-10,100x40",
                bounds(notes().get(0)));
        JsonNode effective = reply.path("result").path("operations").get(0).path("effectiveBounds");
        assertEquals("40,-10,100x40", effective.path("x").asInt() + "," + effective.path("y").asInt() + ","
                + effective.path("width").asInt() + "x" + effective.path("height").asInt());
    }

    @Test
    public void shouldReadTheGap_andBelowContent_inBulk() {
        JsonNode reply = bulk(op("add-note-to-view", note("position", "below-content", "gap", 25)));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals("content bottom 100 + gap 25", "40,125,100x40", bounds(notes().get(0)));
    }

    @Test
    public void shouldPlaceTheSameNoteOnBothPaths() {
        h.success("add-note-to-view", "viewId", "view-source", "content", "One", "width", 100, "height", 40,
                "position", "above-content", "gap", 15);
        String onTheTool = bounds(notes().get(0));
        // A note on the view counts toward the anchor, so take the first off before the second path.
        h.f.view.getChildren().remove(notes().get(0));
        bulk(op("add-note-to-view", note("position", "above-content", "gap", 15)));
        assertEquals(onTheTool, bounds(notes().get(0)));
        assertEquals("40,-15,100x40", bounds(notes().get(0)));
    }

    @Test
    public void shouldRefuseAnInvalidPosition_inBulk_withTheToolsMessage() {
        assertEquals(MIDDLE, h.message("add-note-to-view", "viewId", "view-source", "content", "x",
                "position", "middle"));
        assertEquals(MIDDLE, row(bulk(op("add-note-to-view", note("position", "middle")))));
        assertTrue(notes().isEmpty());
    }

    @Test
    public void shouldRefusePositionOnANestedNote_onBothPaths() {
        assertEquals(NESTED, h.message("add-note-to-view", "viewId", "view-source", "content", "x",
                "position", "above-content", "parentViewObjectId", "vo-a"));
        assertEquals("the nesting refusal answers before the position value", NESTED,
                h.message("add-note-to-view", "viewId", "view-source", "content", "x", "position", "middle",
                        "parentViewObjectId", "vo-a"));
        assertEquals(NESTED, row(bulk(op("add-note-to-view",
                note("position", "above-content", "parentViewObjectId", "vo-a")))));
        assertTrue(notes().isEmpty());
        assertTrue(((IDiagramModelArchimateObject) vo("vo-a")).getChildren().isEmpty());
    }

    @Test
    public void shouldRefusePositionOnANoteNestedInAGroupTheSameCallAdds() {
        JsonNode reply = bulk(
                op("add-group-to-view", args("viewId", "view-source", "label", "G", "x", 600, "y", 300, "width", 200,
                        "height", 100)),
                op("add-note-to-view", note("position", "above-content", "parentViewObjectId", "$0.id")));
        assertTrue(reply.toString(), reply.path("isError").asBoolean());
        // The group the same call adds is also a content change: the nested refusal answers, not the staleness one.
        assertEquals("Operation 1 (add-note-to-view): " + NESTED, reply.path("error").path("message").asText());
        assertEquals("nothing applied", 2, h.f.view.getChildren().size());
    }

    // ---- position against content an earlier operation has changed ----

    @Test
    public void shouldRefusePosition_afterTheSameBulkCallAddsAnElement() {
        IArchimateElement c = h.f.element("el-c");
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                op("add-to-view", args("viewId", "view-source", "elementId", c.getId(), "x", 40, "y", -300)),
                op("add-note-to-view", note("position", "above-content")))));
        assertTrue(reply.toString(), reply.path("isError").asBoolean());
        String message = reply.path("error").path("message").asText();
        assertTrue(message, message.matches("Operation 1 \\(add-note-to-view\\): \\Q" + STALE_PREFIX
                + "added: \\Eid-[0-9a-f]+\\Q" + NOT_PLACED + "\\E"));
        assertEquals("nothing applied", 2, h.f.view.getChildren().size());
    }

    @Test
    public void shouldRefusePosition_afterTheSameBulkCallMovesAnElement() {
        String before = viewState();
        JsonNode reply = bulk(
                op("update-view-object", args("viewObjectId", "vo-a", "x", 40, "y", -300)),
                op("add-note-to-view", note("position", "above-content")));
        assertTrue(reply.toString(), reply.toString().contains("Operation 1 (add-note-to-view): " + STALE_PREFIX
                + "moved or resized: vo-a" + NOT_PLACED));
        assertEquals(before, viewState());
    }

    @Test
    public void shouldRefusePosition_inABatchThatHasQueuedAnAdd() {
        IArchimateElement c = h.f.element("el-c");
        h.success("begin-batch", "modelId", "reads-model");
        String added = h.success("add-to-view", "viewId", "view-source", "elementId", c.getId(), "x", 40,
                "y", -300).path("result").path("preview").path("viewObject").path("viewObjectId").asText();
        assertFalse(added.isEmpty());
        JsonNode error = h.servedError("add-note-to-view", "viewId", "view-source", "content", "T", "width", 100,
                "height", 40, "position", "above-content");
        assertEquals("INVALID_PARAMETER", error.path("code").asText());
        assertEquals(STALE_PREFIX + "added: " + added + NOT_PLACED, error.path("message").asText());
        assertEquals("Pass x and y instead of position, or add the note with position in a later call (after "
                + "end-batch when a batch is open), once those operations have run.",
                error.path("suggestedCorrection").asText());
        h.success("end-batch");
        assertTrue("the batch committed its add and no note", notes().isEmpty());
        assertEquals(3, h.f.view.getChildren().size());
    }

    @Test
    public void shouldPlaceTwoNotesInOneCall_becauseANoteIsNotContent() {
        JsonNode reply = bulk(op("add-note-to-view", note("position", "above-content")),
                op("add-note-to-view", note("position", "below-content")));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals("40,-10,100x40", bounds(notes().get(0)));
        assertEquals("40,110,100x40", bounds(notes().get(1)));
    }

    @Test
    public void shouldPlaceANote_inABatchThatQueuedNothingOnTheView() {
        h.success("begin-batch", "modelId", "reads-model");
        h.success("create-element", "type", "BusinessActor", "name", "Queued Actor");
        JsonNode preview = h.success("add-note-to-view", "viewId", "view-source", "content", "T", "width", 100,
                "height", 40, "position", "above-content").path("result").path("preview");
        assertEquals(-10, preview.path("y").asInt());
        h.success("end-batch");
        assertEquals("40,-10,100x40", bounds(notes().get(0)));
    }

    @Test
    public void shouldPlaceANoteAtXY_afterAnEarlierAddInTheSameCall() {
        IArchimateElement c = h.f.element("el-c");
        JsonNode reply = bulk(
                op("add-to-view", args("viewId", "view-source", "elementId", c.getId(), "x", 40, "y", -300)),
                op("add-note-to-view", note("x", 40, "y", -400)));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals("40,-400,100x40", bounds(notes().get(0)));
    }

    // ---- auto-route-connections reads its four values strictly ----

    @Test
    public void shouldReadAutoRouteBooleansSentAsStrings() {
        for (String key : List.of("force", "autoNudge")) {
            for (String value : List.of("true", "FALSE")) {
                JsonNode reply = h.call("auto-route-connections", args("viewId", "view-source", key, value));
                assertFalse(key + "=" + value + ": " + reply, reply.path("isError").asBoolean());
            }
        }
    }

    @Test
    public void shouldRefuseAutoRouteValuesItCannotRead_andRouteNothing() {
        String before = viewState();
        Object[][] cases = {
                {"force", "yes", flagRefusal("force", "the string 'yes'")},
                {"autoNudge", 1, flagRefusal("autoNudge", "the integer 1")},
                {"snapThreshold", 20.5, "snapThreshold must be a whole number, got the double 20.5. Nothing was changed."},
                {"snapThreshold", "20", "snapThreshold must be a whole number, got the string '20'. Nothing was changed."},
                {"perimeterMargin", true, "perimeterMargin must be a whole number, got the boolean true. Nothing was changed."},
                {"perimeterMargin", "50", "perimeterMargin must be a whole number, got the string '50'. Nothing was changed."},
        };
        for (Object[] c : cases) {
            JsonNode error = h.errorOf("auto-route-connections", args("viewId", "view-source", c[0], c[1]));
            assertEquals(c[0] + "=" + c[1], "INVALID_PARAMETER", error.path("code").asText());
            assertEquals((String) c[2], error.path("message").asText());
        }
        assertEquals("Pass snapThreshold as a whole number, or omit it.", h.servedError("auto-route-connections",
                "viewId", "view-source", "snapThreshold", 20.5).path("suggestedCorrection").asText());
        assertEquals(before, viewState());
        assertFalse(h.f.stack.canUndo());
    }

    @Test
    public void shouldReadWholeNumbersSentAsDoublesOrBeyondInt() {
        for (Object value : List.of(20.0, 3_000_000_000L, -3_000_000_000L)) {
            JsonNode reply = h.call("auto-route-connections", args("viewId", "view-source", "snapThreshold", value,
                    "perimeterMargin", value));
            assertFalse(value + ": " + reply, reply.path("isError").asBoolean());
        }
    }

    @Test
    public void shouldSaturateBeyondLong_andRefuseNaN_inTheWholeNumberReader() {
        assertEquals(Long.valueOf(Long.MAX_VALUE), ToolArguments.wholeNumber(
                Map.of("k", new java.math.BigInteger("1000000000000000000000000")), "k"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), ToolArguments.wholeNumber(
                Map.of("k", new java.math.BigInteger("-1000000000000000000000000")), "k"));
        assertEquals(Long.valueOf(-7), ToolArguments.wholeNumber(Map.of("k", -7.0), "k"));
        assertNull(ToolArguments.wholeNumber(args("k", null), "k"));
        try {
            ToolArguments.wholeNumber(Map.of("k", Double.NaN), "k");
            throw new AssertionError("NaN read");
        } catch (ModelAccessException e) {
            assertEquals("k must be a whole number, got the double NaN. Nothing was changed.", e.getMessage());
        }
    }

    @Test
    public void shouldReadABigNumberWithinLongExactly_andSaturateOnlyBeyondIt() {
        assertEquals(Long.valueOf(20), ToolArguments.wholeNumber(Map.of("k", java.math.BigInteger.valueOf(20)), "k"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), ToolArguments.wholeNumber(
                Map.of("k", java.math.BigInteger.valueOf(Long.MIN_VALUE)), "k"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), ToolArguments.wholeNumber(
                Map.of("k", java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.ONE)), "k"));
        assertEquals(Long.valueOf(20), ToolArguments.wholeNumber(Map.of("k", new java.math.BigDecimal("20.000")), "k"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), ToolArguments.wholeNumber(Map.of("k", Double.POSITIVE_INFINITY), "k"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), ToolArguments.wholeNumber(Map.of("k", Double.NEGATIVE_INFINITY), "k"));
        java.math.BigDecimal nearlyWhole = new java.math.BigDecimal("20.0000000000000000001");
        try {
            ToolArguments.wholeNumber(Map.of("k", nearlyWhole), "k");
            throw new AssertionError("a fraction a double cannot hold was read");
        } catch (ModelAccessException e) {
            assertEquals("k must be a whole number, got " + ToolArguments.describe(nearlyWhole)
                    + ". Nothing was changed.", e.getMessage());
        }
    }

    /**
     * A decimal with a huge exponent saturates by its count of integer digits, without being expanded: expanding
     * {@code 1E+999999999} threw {@code ArithmeticException} at once, and {@code 1E+100000000} ran past any
     * deadline building a hundred-million-digit integer.
     */
    @Test(timeout = 2000)
    public void shouldSaturateADecimalWithAHugeExponent_withoutExpandingIt() {
        assertEquals(Long.valueOf(Long.MAX_VALUE), wholeDecimal("1E+999999999"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), wholeDecimal("-1E+999999999"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), wholeDecimal("1E+100000000"));
        assertEquals(Long.valueOf(0), wholeDecimal("0E+999999999"));
        assertEquals(Long.valueOf(9_000_000_000_000_000_000L), wholeDecimal("9E+18"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), wholeDecimal("1E+19"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), wholeDecimal("-1E+19"));
        // 19 integer digits are converted, then held to the long range as a big integer is.
        assertEquals(Long.valueOf(Long.MAX_VALUE), wholeDecimal("9223372036854775807"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), wholeDecimal("9.223372036854775807E+18"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), wholeDecimal("-9223372036854775808"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), wholeDecimal("9999999999999999999"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), wholeDecimal("-9999999999999999999"));
        assertEquals(Long.valueOf(20), wholeDecimal("20.000"));
        try {
            wholeDecimal("20.5");
            throw new AssertionError("a fraction was read");
        } catch (ModelAccessException e) {
            assertEquals("k must be a whole number, got " + ToolArguments.describe(new java.math.BigDecimal("20.5"))
                    + ". Nothing was changed.", e.getMessage());
        }
        java.math.BigDecimal huge = new java.math.BigDecimal("1E+999999999");
        try {
            ToolArguments.wholeInt(Map.of("k", huge), "k");
            throw new AssertionError("past the integer range was read");
        } catch (ModelAccessException e) {
            assertEquals("k must be a whole number within the integer range, got " + ToolArguments.describe(huge)
                    + ". Nothing was changed.", e.getMessage());
        }
    }

    private static Long wholeDecimal(String literal) {
        return ToolArguments.wholeNumber(Map.of("k", new java.math.BigDecimal(literal)), "k");
    }

    // ---- bulk update-view-object refuses the anchor keys ----

    private static String anchorRefusal(String keys, boolean one) {
        return "update-view-object in bulk-mutate cannot apply " + keys + ": anchoring is set with the "
                + "update-view-object tool, which also queues inside begin-batch. " + (one ? "It is" : "They are")
                + " refused rather than ignored, and nothing was changed.";
    }

    @Test
    public void shouldRefuseEachAnchorKey_inBulk_andApplyNothing() {
        String before = viewState();
        Object[][] cases = {{"anchorTarget", "vo-a"}, {"anchorEdge", "below"}, {"anchorDx", 5}, {"anchorDy", 5}};
        for (Object[] c : cases) {
            JsonNode reply = bulk(op("update-view-object", args("viewObjectId", "vo-b", "x", 410, c[0], c[1])));
            assertEquals(anchorRefusal("'" + c[0] + "'", true), row(reply));
            assertEquals("Remove it from this operation and call update-view-object with it instead.",
                    reply.path("error").path("suggestedCorrection").asText());
        }
        assertEquals(before, viewState());
        assertNull(vo("vo-b").getFeatures().getString("anchorTarget", null));
    }

    @Test
    public void shouldNameEveryAnchorKey_andAnUnknownKey_inOneRow() {
        JsonNode reply = bulk(op("update-view-object", args("viewObjectId", "vo-b", "anchorTarget", "vo-a",
                "anchorEdge", "below", "anchorDx", 1, "anchorDy", 2, "colour", "#fff")));
        assertEquals("update-view-object does not accept 'colour'. An argument the tool cannot read is refused "
                + "rather than ignored, and nothing was changed. update-view-object accepts " + updateViewObjectKeys()
                + ". " + anchorRefusal("'anchorDx', 'anchorDy', 'anchorEdge', 'anchorTarget'", false), row(reply));
    }

    private String updateViewObjectKeys() {
        return String.join(", ", h.registry().getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("update-view-object")).findFirst().orElseThrow()
                .tool().inputSchema().properties().keySet());
    }

    @Test
    public void shouldReportAnchorRows_forEveryOperation_andApplyTheRestUnderContinueOnError() {
        JsonNode reply = bulkContinuing(true,
                op("update-view-object", args("viewObjectId", "vo-a", "anchorTarget", "vo-b")),
                op("update-view-object", args("viewObjectId", "vo-b", "y", 300)),
                op("update-view-object", args("viewObjectId", "vo-b", "anchorEdge", "left")));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        String text = reply.toString();
        assertTrue(text, text.contains(anchorRefusal("'anchorTarget'", true)));
        assertTrue(text, text.contains(anchorRefusal("'anchorEdge'", true)));
        assertEquals(300, vo("vo-b").getBounds().getY());
        assertEquals(40, vo("vo-a").getBounds().getY());
    }

    @Test
    public void shouldStillAnchorThroughTheTool() {
        h.success("update-view-object", "viewObjectId", "vo-b", "anchorTarget", "vo-a", "anchorEdge", "below");
        assertEquals("vo-a", vo("vo-b").getFeatures().getString("anchorTarget", null));
    }

    // ---- bulk update-view clears the router with "" ----

    @Test
    public void shouldClearTheRouter_inBulk_alone() {
        h.f.view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_MANHATTAN);
        JsonNode reply = bulk(op("update-view", args("viewId", "view-source", "connectionRouterType", "")));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, h.f.view.getConnectionRouterType());
    }

    @Test
    public void shouldClearTheRouter_inBulk_besideAnotherField() {
        h.f.view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_MANHATTAN);
        JsonNode reply = bulk(op("update-view", args("viewId", "view-source", "connectionRouterType", "",
                "name", "Renamed")));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals("Renamed", h.f.view.getName());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, h.f.view.getConnectionRouterType());
    }

    @Test
    public void shouldStillSetTheRouter_inBulk() {
        bulk(op("update-view", args("viewId", "view-source", "connectionRouterType", "manhattan")));
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, h.f.view.getConnectionRouterType());
    }

    // ---- one reader for the id filters ----

    private static String notArray(String key, String described) {
        return key + " must be an array of strings, got " + described + ". Nothing was changed.";
    }

    private static String badItem(String key, int i, String described) {
        return key + "[" + i + "] must be a non-empty string, got " + described + ". Nothing was changed.";
    }

    private static String empty(String key) {
        return key + " is an empty array. An empty filter is refused rather than read as all, and nothing was "
                + "changed.";
    }

    @Test
    public void shouldRefuseEveryFilterShapeThatUsedToMeanAll_andChangeNothing() {
        // vo-a and vo-b are oversized, so a resize of everything would change both.
        vo("vo-a").setBounds(40, 40, 300, 300);
        vo("vo-b").setBounds(400, 40, 300, 300);
        String before = viewState();
        Object[][] filters = {
                {"resize-elements-to-fit", args("viewId", "view-source"), "elementIds"},
                {"auto-route-connections", args("viewId", "view-source"), "connectionIds"},
                {"auto-connect-view", args("viewId", "view-source"), "elementIds"},
                {"auto-connect-view", args("viewId", "view-source"), "relationshipTypes"},
                {"auto-connect-view", args("viewId", "view-source"), "relationshipIds"},
                {"arrange-groups", args("viewId", "view-source", "arrangement", "grid"), "groupIds"},
        };
        for (Object[] f : filters) {
            String tool = (String) f[0];
            @SuppressWarnings("unchecked")
            Map<String, Object> base = (Map<String, Object>) f[1];
            String key = (String) f[2];
            Object[][] values = {
                    {"vo-a", notArray(key, "the string 'vo-a'")},
                    {Map.of("id", "vo-a"), notArray(key, "an object")},
                    {List.of(), empty(key)},
                    {List.of(1), badItem(key, 0, "the integer 1")},
                    {List.of("vo-a", " "), badItem(key, 1, "the string ' '")},
                    {List.of("vo-a", ""), badItem(key, 1, "an empty string")},
            };
            for (Object[] v : values) {
                Map<String, Object> call = new LinkedHashMap<>(base);
                call.put(key, v[0]);
                JsonNode error = h.errorOf(tool, call);
                assertEquals(tool + " " + key + "=" + v[0], "INVALID_PARAMETER", error.path("code").asText());
                assertEquals(tool + " " + key, (String) v[1], error.path("message").asText());
            }
        }
        assertEquals("Name at least one entry in elementIds, or omit elementIds to act on all.",
                h.servedError("resize-elements-to-fit", "viewId", "view-source", "elementIds", List.of())
                        .path("suggestedCorrection").asText());
        assertEquals(before, viewState());
        assertFalse(h.f.stack.canUndo());
    }

    @Test
    public void shouldNarrowToAValidFilter() {
        vo("vo-a").setBounds(40, 40, 300, 300);
        vo("vo-b").setBounds(400, 40, 300, 300);
        JsonNode reply = h.success("resize-elements-to-fit", "viewId", "view-source", "elementIds", List.of("vo-a"));
        assertEquals(1, reply.path("result").path("resizedCount").asInt());
        assertNotEquals(300, vo("vo-a").getBounds().getWidth());
        assertEquals(300, vo("vo-b").getBounds().getWidth());
    }

    @Test
    public void shouldRefuseAGroupArrangementThatIsNotAString() {
        String before = viewState();
        assertEquals("groupArrangements entry 'g1' must be a string, got the integer 3. Nothing was changed.",
                h.message("optimize-group-order", "viewId", "view-source", "groupArrangements", Map.of("g1", 3)));
        assertEquals("groupArrangements must be an object, got the string 'grid'. Nothing was changed.",
                h.message("optimize-group-order", "viewId", "view-source", "groupArrangements", "grid"));
        assertEquals(before, viewState());
    }

    // ---- one rule for every boolean ----

    /** Every switched boolean, with the smallest call that reaches its read. */
    private Object[][] booleans() {
        return new Object[][] {
                {"create-element", args("type", "BusinessActor", "name", "B"), "force"},
                {"create-relationship", args("type", "AssociationRelationship", "sourceId", "el-a", "targetId", "el-b"),
                        "associationDirected"},
                {"update-relationship", args("id", "rel-ab"), "associationDirected"},
                {"add-to-view", args("viewId", "view-source", "elementId", "do-1"), "autoConnect"},
                {"add-to-view", args("viewId", "view-source", "elementId", "do-1"), "autoSize"},
                {"layout-within-group", args("viewId", "view-source", "groupViewObjectId", "g", "arrangement", "row"),
                        "autoResize"},
                {"layout-within-group", args("viewId", "view-source", "groupViewObjectId", "g", "arrangement", "row"),
                        "autoWidth"},
                {"layout-within-group", args("viewId", "view-source", "groupViewObjectId", "g", "arrangement", "row"),
                        "recursive"},
                {"layout-within-group", args("viewId", "view-source", "groupViewObjectId", "g", "arrangement", "row"),
                        "recursiveChildren"},
                {"optimize-group-order", args("viewId", "view-source"), "autoWidth"},
                {"update-specialization", args("name", "S", "conceptType", "BusinessActor"), "clearImagePath"},
                {"delete-specialization", args("name", "S", "conceptType", "BusinessActor"), "force"},
                {"delete-folder", args("folderId", "no-such-folder"), "force"},
                {"export-view", args("viewId", "view-source"), "inline"},
                {"update-view-object", args("viewObjectId", "vo-a"), "deriveLineColor"},
                {"resize-elements-to-fit", args("viewId", "view-source"), "wrapFit"},
                {"set-session-filter", args(), "clear"},
                {"assess-layout", args("viewId", "view-source"), "includeViolatorIds"},
                {"get-view-contents", args("viewId", "view-source"), "dryRun"},
                {"get-views", args(), "dryRun"},
                {"search-elements", args("query", "el"), "dryRun"},
                {"search-relationships", args("query", "el"), "dryRun"},
                {"get-relationships", args("elementId", "el-a"), "dryRun"},
                {"auto-route-connections", args("viewId", "view-source"), "force"},
                {"auto-route-connections", args("viewId", "view-source"), "autoNudge"},
                {"bulk-mutate", args("operations", List.of(op("update-view", args("viewId", "view-source",
                        "name", "N")))), "continueOnError"},
        };
    }

    @Test
    public void shouldRefuseEveryBooleanThatIsNotTrueOrFalse_onEveryTool() {
        String before = viewState();
        for (Object[] b : booleans()) {
            for (Object[] v : new Object[][] {{"yes", "the string 'yes'"}, {1, "the integer 1"}, {"", "an empty string"}}) {
                @SuppressWarnings("unchecked")
                Map<String, Object> call = new LinkedHashMap<>((Map<String, Object>) b[1]);
                call.put((String) b[2], v[0]);
                JsonNode error = h.errorOf((String) b[0], call);
                assertEquals(b[0] + " " + b[2] + "=" + v[0], "INVALID_PARAMETER", error.path("code").asText());
                assertEquals(b[0] + " " + b[2], flagRefusal((String) b[2], (String) v[1]),
                        error.path("message").asText());
            }
        }
        assertEquals(before, viewState());
        assertFalse("nothing was created, changed or deleted", h.f.stack.canUndo());
        assertEquals("N not applied", "Source", h.f.view.getName());
    }

    @Test
    public void shouldReadTrueAndFalseInAnyCase_onEveryTool() {
        for (Object[] b : booleans()) {
            if ("export-view".equals(b[0]) || "create-relationship".equals(b[0])) {
                continue; // these go on to render, or to Archi's relationship rules, which need the platform
            }
            for (String value : List.of("TRUE", "false")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> call = new LinkedHashMap<>((Map<String, Object>) b[1]);
                call.put((String) b[2], value);
                JsonNode reply;
                try {
                    reply = h.call((String) b[0], call);
                } catch (org.eclipse.swt.SWTError noDisplay) {
                    // Arguments are read before any work, so a call that got as far as measuring text (which
                    // needs a display a headless Linux run lacks) read the value rather than refusing it.
                    continue;
                }
                assertFalse(b[0] + " " + b[2] + "=" + value + ": " + reply, reply.path("error").path("message")
                        .asText().contains("must be true or false"));
            }
        }
    }

    @Test
    public void shouldRefuseAssociationDirectedYes_andLeaveTheRelationshipDirected() {
        association().setDirected(true);
        assertEquals(flagRefusal("associationDirected", "the string 'yes'"),
                h.message("update-relationship", "id", "rel-ab", "associationDirected", "yes"));
        assertTrue(association().isDirected());
        assertEquals(flagRefusal("associationDirected", "the string 'yes'"), row(bulk(op("update-relationship",
                args("id", "rel-ab", "associationDirected", "yes")))));
        assertTrue(association().isDirected());
    }

    @Test
    public void shouldReadAssociationDirectedAsAStringInAnyCase_onBothPaths() {
        h.success("update-relationship", "id", "rel-ab", "associationDirected", "TRUE");
        assertTrue(association().isDirected());
        bulk(op("update-relationship", args("id", "rel-ab", "associationDirected", "False")));
        assertFalse(association().isDirected());
    }

    @Test
    public void shouldRefuseEveryBulkBooleanThatIsNotTrueOrFalse_asItsRow() {
        Object[][] ops = {
                {"add-to-view", args("viewId", "view-source", "elementId", "do-1"), "autoSize"},
                {"update-relationship", args("id", "rel-ab"), "associationDirected"},
                {"create-relationship", args("type", "AssociationRelationship", "sourceId", "el-a", "targetId", "el-b"),
                        "associationDirected"},
                {"delete-folder", args("folderId", "no-such-folder"), "force"},
                {"update-specialization", args("name", "S", "conceptType", "BusinessActor"), "clearImagePath"},
                {"delete-specialization", args("name", "S", "conceptType", "BusinessActor"), "force"},
                {"update-view-object", args("viewObjectId", "vo-a"), "deriveLineColor"},
        };
        for (Object[] o : ops) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = new LinkedHashMap<>((Map<String, Object>) o[1]);
            params.put((String) o[2], "yes");
            assertEquals(o[0] + " " + o[2], flagRefusal((String) o[2], "the string 'yes'"),
                    row(bulk(op((String) o[0], params))));
        }
        assertFalse(h.f.stack.canUndo());
    }

    @Test
    public void shouldApplyTheRest_whenContinueOnErrorIsTheStringTrue() {
        JsonNode reply = h.call("bulk-mutate", args("continueOnError", "true", "operations", List.of(
                op("update-view", args("viewId", "view-source", "name", "Kept")),
                op("update-view", args("viewId", "no-such-view", "name", "Lost")))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals("Kept", h.f.view.getName());
    }
}
