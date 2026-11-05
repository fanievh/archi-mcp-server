package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Every whole-number argument is read by one rule, on the tool and on its {@code bulk-mutate} operation where
 * it has one: a fraction, a string, a boolean, or a value past the {@code int} range is refused naming the key,
 * whole message pinned, with nothing changed and the model version unmoved. Each used to be truncated
 * ({@code 15.7} as 15), read as absent ({@code "15"} as the default), or narrowed, which wraps: {@code 2^32 + n}
 * read as {@code n} and then passed every range check after the read.
 *
 * <p>Also here: {@code x} and {@code y} are still given together or not at all, and the counts that were
 * raised or defaulted rather than refused ({@code undo} steps, auto-layout spacing, optimize-group-order's element
 * size).</p>
 */
public class WholeNumberArgumentReadsTest {

    /** {@code 2^32 + 100}: wrapped to 100, a width every check accepted. */
    private static final long WRAPS_TO_100 = 4294967396L;
    /** {@code 2^32 + 128}: wrapped to 128, an opacity inside 0..255. */
    private static final long WRAPS_TO_128 = 4294967424L;

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("whole-number-reads-model");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    private static Map<String, Object> op(String tool, Map<String, Object> params) {
        return Map.of("tool", tool, "params", params);
    }

    private static String fraction(String key, String described) {
        return key + " must be a whole number, got " + described + ". Nothing was changed.";
    }

    private static String pastInt(String key, long value) {
        return key + " must be a whole number within the integer range, got the long " + value
                + ". Nothing was changed.";
    }

    private IDiagramModelObject voA() {
        return (IDiagramModelObject) h.f.view.getChildren().stream()
                .filter(o -> "vo-a".equals(o.getId())).findFirst().orElseThrow();
    }

    private IDiagramModelArchimateConnection connAb() {
        return (IDiagramModelArchimateConnection) ((IDiagramModelArchimateObject) voA()).getSourceConnections()
                .get(0);
    }

    /** What any of these calls could change: the view's objects, vo-a's box and alpha, and conn-ab's route. */
    private String state() {
        IDiagramModelObject a = voA();
        IDiagramModelArchimateConnection c = connAb();
        return h.f.view.getChildren().size() + " objects; vo-a " + a.getBounds().getX() + "," + a.getBounds().getY()
                + " " + a.getBounds().getWidth() + "x" + a.getBounds().getHeight() + " alpha " + a.getAlpha()
                + "; conn-ab bendpoints " + c.getBendpoints().stream().map(b -> b.getStartX() + "," + b.getStartY()
                        + "," + b.getEndX() + "," + b.getEndY()).toList() + ", width " + c.getLineWidth()
                + ", font " + c.getFont();
    }

    /** Refused through the tool with {@code message} and INVALID_PARAMETER, nothing changed. */
    private void refused(List<String> wrong, String tool, Map<String, Object> params, String message) {
        String version = h.f.accessor.getModelVersion();
        String before = state();
        JsonNode reply = h.call(tool, new LinkedHashMap<>(params));
        if (!reply.path("isError").asBoolean()) {
            wrong.add(tool + " " + params + ": answered success");
            return;
        }
        JsonNode error = reply.path("error");
        if (!"INVALID_PARAMETER".equals(error.path("code").asText())) {
            wrong.add(tool + " " + params + ": code " + error.path("code").asText() + " "
                    + error.path("message").asText());
        }
        if (!message.equals(error.path("message").asText())) {
            wrong.add(tool + " " + params + ": " + error.path("message").asText());
        }
        unchanged(wrong, tool + " " + params, version, before);
    }

    /** As {@link #refused}, and refused the same way as a {@code bulk-mutate} operation. */
    private void refusedOnBoth(List<String> wrong, String tool, Map<String, Object> params, String message) {
        refused(wrong, tool, params, message);
        String version = h.f.accessor.getModelVersion();
        String before = state();
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(op(tool, new LinkedHashMap<>(params)))));
        String text = reply.path("error").path("message").asText();
        String prefix = "Operation 0 (" + tool + "): ";
        if (!reply.path("isError").asBoolean() || !text.equals(prefix + message)) {
            wrong.add(tool + " " + params + " bulk: " + (reply.path("isError").asBoolean() ? text : "success"));
        }
        unchanged(wrong, tool + " " + params + " bulk", version, before);
    }

    private void unchanged(List<String> wrong, String what, String version, String before) {
        if (!version.equals(h.f.accessor.getModelVersion())) {
            wrong.add(what + ": the model version moved");
        }
        if (!before.equals(state())) {
            wrong.add(what + ": " + before + " became " + state());
        }
    }

    private static Map<String, Object> place(Object... extra) {
        Map<String, Object> params = args("viewId", "view-source", "elementId", "do-1");
        params.putAll(args(extra));
        return params;
    }

    // ---- placement and styling, tool and bulk ----

    @Test
    public void shouldRefuseAPlacementNumberItCannotRead_onBothPaths() {
        List<String> wrong = new ArrayList<>();
        refusedOnBoth(wrong, "add-to-view", place("width", 15.7), fraction("width", "the double 15.7"));
        refusedOnBoth(wrong, "add-to-view", place("width", "15"), fraction("width", "the string '15'"));
        refusedOnBoth(wrong, "add-to-view", place("width", true), fraction("width", "the boolean true"));
        refusedOnBoth(wrong, "add-to-view", place("width", 3000000000L), pastInt("width", 3000000000L));
        refusedOnBoth(wrong, "add-to-view", place("width", WRAPS_TO_100), pastInt("width", WRAPS_TO_100));
        refusedOnBoth(wrong, "add-to-view", place("x", 10.5, "y", 20), fraction("x", "the double 10.5"));
        refusedOnBoth(wrong, "add-to-view", place("opacity", WRAPS_TO_128), pastInt("opacity", WRAPS_TO_128));
        refusedOnBoth(wrong, "add-group-to-view", args("viewId", "view-source", "label", "G", "height", 40.5),
                fraction("height", "the double 40.5"));
        refusedOnBoth(wrong, "add-note-to-view", args("viewId", "view-source", "content", "N", "width", "100"),
                fraction("width", "the string '100'"));
        refusedOnBoth(wrong, "update-view-object", args("viewObjectId", "vo-a", "y", 15.7),
                fraction("y", "the double 15.7"));
        refusedOnBoth(wrong, "update-view-object", args("viewObjectId", "vo-a", "opacity", 12.5),
                fraction("opacity", "the double 12.5"));
        refusedOnBoth(wrong, "update-view-object", args("viewObjectId", "vo-a", "fontSize", WRAPS_TO_100),
                pastInt("fontSize", WRAPS_TO_100));
        refused(wrong, "update-view-object", args("viewObjectId", "vo-a", "anchorDx", 1.5),
                fraction("anchorDx", "the double 1.5"));
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldStillPlaceAtAWholeNumber_includingOneSentAsADouble_andANegative() {
        h.success("add-to-view", "viewId", "view-source", "elementId", "do-1", "x", 20, "y", 20.0, "width", 120.0,
                "height", 55);
        h.success("update-view-object", "viewObjectId", "vo-a", "x", -5, "y", 20);
        assertEquals("-5,20", voA().getBounds().getX() + "," + voA().getBounds().getY());
    }

    // ---- x and y are a pair ----

    @Test
    public void shouldRefuseOneOfXAndY_onBothPaths_withNothingAdded() {
        List<String> wrong = new ArrayList<>();
        String half = "Both x and y must be specified together, or both omitted for auto-placement";
        refusedOnBoth(wrong, "add-to-view", place("x", 10), half);
        refusedOnBoth(wrong, "add-to-view", place("y", 10), half);
        refusedOnBoth(wrong, "add-to-view", place("x", 10, "y", "20"), fraction("y", "the string '20'"));
        refusedOnBoth(wrong, "add-group-to-view", args("viewId", "view-source", "label", "G", "x", 10), half);
        refusedOnBoth(wrong, "add-note-to-view", args("viewId", "view-source", "content", "N", "y", 10), half);
        refusedOnBoth(wrong, "add-view-reference-to-view", args("viewId", "view-source", "referencedViewId",
                "view-other", "x", 10), half);
        refusedOnBoth(wrong, "add-image-to-view", args("viewId", "view-source", "imagePath",
                ViewContentsFixture.IMAGE_PATH, "y", 10), half);
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldStillMoveAnObjectOnOneAxis_withUpdateViewObject() {
        h.success("update-view-object", "viewObjectId", "vo-a", "y", 90);
        assertEquals("x kept, y moved", "40,90", voA().getBounds().getX() + "," + voA().getBounds().getY());
        JsonNode reply = h.call("apply-positions", args("viewId", "view-source", "positions",
                List.of(args("viewObjectId", "vo-a", "x", 70))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals("70,90", voA().getBounds().getX() + "," + voA().getBounds().getY());
    }

    // ---- apply-positions: an entry's coordinate is refused with the entry, beside the others ----

    @Test
    public void shouldNameThePositionEntry_whoseCoordinateDoesNotRead() {
        List<String> wrong = new ArrayList<>();
        refused(wrong, "apply-positions", args("viewId", "view-source", "positions",
                List.of(args("viewObjectId", "vo-a", "x", 1.5))),
                "positions[0]: x must be a whole number, got the double 1.5. Nothing was changed.");
        refused(wrong, "apply-positions", args("viewId", "view-source", "positions",
                List.of(args("viewObjectId", "vo-b", "x", 10), args("viewObjectId", "vo-a", "height", WRAPS_TO_100))),
                "positions[1]: " + pastInt("height", WRAPS_TO_100));
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldReportEveryFailedEntry_whenAPositionCoordinateDoesNotRead() {
        String before = state();
        JsonNode error = h.errorOf("apply-positions", args("viewId", "view-source", "positions",
                List.of(args("viewObjectId", "vo-a", "x", "15"), args("viewObjectId", "vo-b", "bogus", 1))));
        List<String> failed = new ArrayList<>();
        error.path("failed").forEach(row -> failed.add(row.path("index").asText() + " " + row.path("message").asText()));
        assertEquals(error.toString(), List.of(
                "0 positions[0]: " + fraction("x", "the string '15'"),
                "1 " + error.path("failed").get(1).path("message").asText()), failed);
        assertTrue(failed.get(1), failed.get(1).startsWith("1 positions[1]: ") && failed.get(1).contains("bogus"));
        assertEquals(before, state());
    }

    // ---- bendpoints, tool and bulk ----

    @Test
    public void shouldRefuseABendpointNumberItCannotRead_onBothPaths_withTheRouteKept() {
        List<String> wrong = new ArrayList<>();
        refusedOnBoth(wrong, "update-view-connection", args("viewConnectionId", "conn-ab", "bendpoints",
                List.of(args("startX", 10.5, "startY", 0, "endX", 0, "endY", 0))),
                "Bendpoint[0].startX must be a whole number, got the double 10.5. Nothing was changed.");
        refusedOnBoth(wrong, "update-view-connection", args("viewConnectionId", "conn-ab", "absoluteBendpoints",
                List.of(args("x", 300, "y", WRAPS_TO_100))),
                "Bendpoint[0].y must be a whole number within the integer range, got the long " + WRAPS_TO_100
                        + ". Nothing was changed.");
        assertEquals(List.of(), wrong);
        h.success("update-view-connection", "viewConnectionId", "conn-ab", "bendpoints",
                List.of(args("startX", 100.0, "startY", 0, "endX", -100, "endY", 0)));
        assertEquals("a whole number sent as a double still reads", 100, connAb().getBendpoints().get(0).getStartX());
    }

    // ---- update-view-connection: one rule for every field sent ----

    @Test
    public void shouldRefuseAFractionalLineWidth_besideAFieldThatReads_withTheRouteKept() {
        List<String> wrong = new ArrayList<>();
        refusedOnBoth(wrong, "update-view-connection", args("viewConnectionId", "conn-ab", "lineWidth", 2.7,
                "labelPosition", "source"),
                "update-view-connection cannot read lineWidth (expected an integer from 1 to 3, got the double "
                        + "2.7). Every argument sent must read, so nothing was changed, and the route was kept.");
        refusedOnBoth(wrong, "update-view-connection", args("viewConnectionId", "conn-ab", "fontSize", WRAPS_TO_100),
                "update-view-connection read nothing to change from fontSize (expected a positive integer, got "
                        + "the long " + WRAPS_TO_100 + "). Nothing was changed, and the route was kept.");
        assertEquals(List.of(), wrong);
        h.success("update-view-connection", "viewConnectionId", "conn-ab", "lineWidth", 2);
        assertEquals(2, connAb().getLineWidth());
    }

    // ---- layout, spacing and export: tool only ----

    @Test
    public void shouldRefuseALayoutOrExportNumberItCannotRead() {
        List<String> wrong = new ArrayList<>();
        refused(wrong, "layout-within-group", args("viewId", "view-source", "groupViewObjectId", "g",
                "arrangement", "grid", "spacing", 15.7), fraction("spacing", "the double 15.7"));
        refused(wrong, "arrange-groups", args("viewId", "view-source", "arrangement", "grid", "columns", "3"),
                fraction("columns", "the string '3'"));
        refused(wrong, "optimize-group-order", args("viewId", "view-source", "padding", WRAPS_TO_100),
                pastInt("padding", WRAPS_TO_100));
        refused(wrong, "layout-flat-view", args("viewId", "view-source", "arrangement", "grid", "columns", 2.5),
                fraction("columns", "the double 2.5"));
        refused(wrong, "adjust-view-spacing", args("viewId", "view-source", "interElementDelta", 1.5),
                fraction("interElementDelta", "the double 1.5"));
        refused(wrong, "apply-spacing-recommendations", args("viewId", "view-source", "iterationBudget", 2.5),
                fraction("iterationBudget", "the double 2.5"));
        refused(wrong, "export-view", args("viewId", "view-source", "format", "png", "quality", 50.5),
                fraction("quality", "the double 50.5"));
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldRefuseANonPositiveAutoLayoutSpacing_ratherThanUseFifty() {
        List<String> wrong = new ArrayList<>();
        // From 1 to 1000 since the layout spacing gained its published maximum (it was 1 to 2147483647).
        for (Object spacing : List.of(0, -5)) {
            refused(wrong, "auto-layout-and-route", args("viewId", "view-source", "spacing", spacing),
                    "spacing must be a whole number from 1 to 1000, got the integer " + spacing
                            + ". Nothing was changed.");
        }
        refused(wrong, "auto-layout-and-route", args("viewId", "view-source", "spacing", 15.7),
                fraction("spacing", "the double 15.7"));
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldRefuseANonPositiveElementSize_onOptimizeGroupOrder_asLayoutWithinGroupDoes() {
        List<String> wrong = new ArrayList<>();
        refused(wrong, "optimize-group-order", args("viewId", "view-source", "elementWidth", 0),
                "elementWidth must be positive");
        refused(wrong, "optimize-group-order", args("viewId", "view-source", "elementHeight", -1),
                "elementHeight must be positive");
        assertEquals(List.of(), wrong);
    }

    // ---- the reads that were inline ----

    @Test
    public void shouldRefuseALimitDepthOrStepCountItCannotRead() {
        List<String> wrong = new ArrayList<>();
        refused(wrong, "search-elements", args("query", "a", "limit", "50"), fraction("limit", "the string '50'"));
        refused(wrong, "search-relationships", args("query", "a", "limit", 2.5), fraction("limit", "the double 2.5"));
        refused(wrong, "get-views", args("limit", WRAPS_TO_100), pastInt("limit", WRAPS_TO_100));
        refused(wrong, "get-folder-tree", args("depth", "2"), fraction("depth", "the string '2'"));
        refused(wrong, "get-relationships", args("elementId", "el-a", "depth", 2.9),
                fraction("depth", "the double 2.9"));
        refused(wrong, "get-relationships", args("elementId", "el-a", "traverse", true, "maxDepth", 2.5),
                fraction("maxDepth", "the double 2.5"));
        refused(wrong, "undo", args("steps", 2.9), fraction("steps", "the double 2.9"));
        refused(wrong, "redo", args("steps", "2"), fraction("steps", "the string '2'"));
        for (String tool : List.of("undo", "redo")) {
            refused(wrong, tool, args("steps", 0),
                    "steps must be a whole number from 1 to 2147483647, got the integer 0. Nothing was changed.");
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldStillReadAWholeLimitAndDepth() {
        JsonNode views = h.success("get-views", "limit", 1.0);
        assertEquals(views.toString(), 1, views.path("result").size());
        assertFalse(h.call("get-folder-tree", args("depth", 1)).path("isError").asBoolean());
        assertFalse(h.call("get-relationships", args("elementId", "el-a", "depth", 2)).path("isError").asBoolean());
    }
}
