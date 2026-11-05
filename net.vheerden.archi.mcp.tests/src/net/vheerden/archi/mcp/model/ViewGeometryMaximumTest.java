package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.fasterxml.jackson.databind.JsonNode;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * A caller's coordinates and sizes are held to published bounds, {@link ViewGeometry#MAX_COORDINATE} either side
 * of the origin and {@link ViewGeometry#MAX_SIZE}: one past a bound is refused naming the key, whole message
 * pinned, on the tool and on its bulk-mutate operation, with nothing changed and the model version unmoved. Each
 * feeds {@code int} arithmetic: {@code elementWidth: 2147483647} used to answer success having placed the second
 * of two children at x -2147483599, and {@code add-to-view x: 2147483000, width: 1000} placed an object whose right
 * edge is past the {@code int} range.
 */
public class ViewGeometryMaximumTest {

    private static final String VIEW = "view-source";
    private static final int MAX_COORDINATE = 1_000_000;
    private static final int MAX_SIZE = 100_000;

    private EchoToolHarness h;
    private IDiagramModelGroup group;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("view-geometry-maximum-model");
        group = h.f.group(h.f.view, "g1", 10, 200, 400, 200);
        h.f.object(group, "vo-g1", h.f.element("el-g1"), 10, 30, 120, 55);
        h.f.object(group, "vo-g2", h.f.element("el-g2"), 150, 30, 120, 55);
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    /** The six tools that place or move one object, each with the arguments it needs beside the geometry. */
    private static List<Object[]> placementTools() {
        return List.of(
                new Object[] {"add-to-view", args("viewId", VIEW, "elementId", "do-1")},
                new Object[] {"add-group-to-view", args("viewId", VIEW, "label", "G")},
                new Object[] {"add-note-to-view", args("viewId", VIEW, "content", "n")},
                new Object[] {"add-view-reference-to-view", args("viewId", VIEW, "referencedViewId", VIEW)},
                new Object[] {"add-image-to-view", args("viewId", VIEW, "imagePath", "images/a.png")},
                new Object[] {"update-view-object", args("viewObjectId", "vo-a")});
    }

    /** The layout tools' child sizes. */
    private static List<Object[]> layoutTools() {
        return List.of(
                new Object[] {"layout-within-group",
                        args("viewId", VIEW, "groupViewObjectId", "g1", "arrangement", "row")},
                new Object[] {"optimize-group-order", args("viewId", VIEW)});
    }

    private String state() {
        StringBuilder out = new StringBuilder();
        for (Object o : h.f.view.getChildren()) {
            out.append(bounds((IDiagramModelObject) o)).append(';');
        }
        for (Object o : group.getChildren()) {
            out.append(bounds((IDiagramModelObject) o)).append(';');
        }
        return out.toString();
    }

    private static String bounds(IDiagramModelObject o) {
        return o.getId() + " " + o.getBounds().getX() + "," + o.getBounds().getY() + " " + o.getBounds().getWidth()
                + "x" + o.getBounds().getHeight();
    }

    private static String coordinateMessage(String key, Object value) {
        return key + " must be from -1000000 to 1000000, got " + ToolArguments.describe(value)
                + ". Nothing was changed.";
    }

    private static String sizeMessage(String key, Object value) {
        return key + " must be at most 100000, got " + ToolArguments.describe(value) + ". Nothing was changed.";
    }

    /** {@code params} with the other coordinate beside a coordinate, since x and y are given together. */
    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> params = new LinkedHashMap<>(base);
        if ("x".equals(key)) {
            params.put("y", 10);
        } else if ("y".equals(key)) {
            params.put("x", 10);
        }
        params.put(key, value);
        return params;
    }

    /** Refused through the tool with {@code message} and {@code suggestion}, nothing changed. */
    private void refused(List<String> wrong, String tool, Map<String, Object> params, String message,
            String suggestion) {
        String version = h.f.accessor.getModelVersion();
        String before = state();
        JsonNode reply = h.call(tool, new LinkedHashMap<>(params));
        JsonNode error = reply.path("error");
        if (!reply.path("isError").asBoolean()) {
            wrong.add(tool + " " + params + ": answered success");
        } else if (!"INVALID_PARAMETER".equals(error.path("code").asText())
                || !message.equals(error.path("message").asText())
                || (suggestion != null && !suggestion.equals(error.path("suggestedCorrection").asText()))) {
            wrong.add(tool + " " + params + ": " + error.path("code").asText() + " " + error.path("message").asText()
                    + " / " + error.path("suggestedCorrection").asText());
        }
        unchanged(wrong, tool + " " + params, version, before);
    }

    /** As {@link #refused}, and refused the same way as a bulk-mutate operation. */
    private void refusedOnBoth(List<String> wrong, String tool, Map<String, Object> params, String message,
            String suggestion) {
        refused(wrong, tool, params, message, suggestion);
        String version = h.f.accessor.getModelVersion();
        String before = state();
        JsonNode reply = h.call("bulk-mutate", args("operations",
                List.of(Map.of("tool", tool, "params", new LinkedHashMap<>(params)))));
        String text = reply.path("error").path("message").asText();
        if (!reply.path("isError").asBoolean() || !text.equals("Operation 0 (" + tool + "): " + message)) {
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

    @Test
    public void shouldRefuseACoordinatePastEitherBound_onTheToolAndInBulk() {
        List<String> wrong = new ArrayList<>();
        for (Object[] t : placementTools()) {
            for (String key : List.of("x", "y")) {
                for (Object value : List.of(MAX_COORDINATE + 1, -MAX_COORDINATE - 1, 2147483000)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> base = (Map<String, Object>) t[1];
                    refusedOnBoth(wrong, (String) t[0], with(base, key, value), coordinateMessage(key, value),
                            "Pass " + key + " from -1000000 to 1000000, or omit it.");
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldRefuseASizeAboveTheMaximum_onTheToolAndInBulk() {
        List<String> wrong = new ArrayList<>();
        for (Object[] t : placementTools()) {
            for (String key : List.of("width", "height")) {
                for (Object value : List.of(MAX_SIZE + 1, Integer.MAX_VALUE)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> base = (Map<String, Object>) t[1];
                    refusedOnBoth(wrong, (String) t[0], with(base, key, value), sizeMessage(key, value),
                            "Pass " + key + " up to 100000, or omit it.");
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldRefuseAChildSizeAboveTheMaximum_onTheLayoutTools() {
        List<String> wrong = new ArrayList<>();
        for (Object[] t : layoutTools()) {
            for (String key : List.of("elementWidth", "elementHeight")) {
                for (Object value : List.of(MAX_SIZE + 1, Integer.MAX_VALUE)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> base = (Map<String, Object>) t[1];
                    refused(wrong, (String) t[0], with(base, key, value), sizeMessage(key, value),
                            "Pass " + key + " up to 100000, or omit it.");
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldRefuseThePositionEntryPastABound_besideAGoodOne() {
        List<String> wrong = new ArrayList<>();
        refused(wrong, "apply-positions", args("viewId", VIEW, "positions", List.of(
                args("viewObjectId", "vo-b", "x", 10, "y", 10),
                args("viewObjectId", "vo-a", "x", -MAX_COORDINATE - 1))),
                "positions[1]: " + coordinateMessage("x", -MAX_COORDINATE - 1), null);
        refused(wrong, "apply-positions", args("viewId", VIEW, "positions", List.of(
                args("viewObjectId", "vo-a", "width", MAX_SIZE + 1),
                args("viewObjectId", "vo-b", "height", 60))),
                "positions[0]: " + sizeMessage("width", MAX_SIZE + 1), null);
        assertEquals(List.of(), wrong);
    }

    /**
     * An anchor offset is a coordinate too: {@code resolveByEdge} adds it to the target's edge in {@code int}, so
     * {@code anchorDx: 2147483647} used to answer success with a wrapped x.
     */
    @Test
    public void shouldRefuseAnAnchorOffsetPastEitherBound() {
        List<String> wrong = new ArrayList<>();
        for (String key : List.of("anchorDx", "anchorDy")) {
            for (Object value : List.of(MAX_COORDINATE + 1, -MAX_COORDINATE - 1, Integer.MAX_VALUE)) {
                Map<String, Object> params = args("viewObjectId", "vo-a", "anchorTarget", "vo-b",
                        "anchorEdge", "right");
                params.put(key, value);
                refused(wrong, "update-view-object", params, coordinateMessage(key, value),
                        "Pass " + key + " from -1000000 to 1000000, or omit it.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> property = (Map<String, Object>) properties("update-view-object").get(key);
            if (!Integer.valueOf(-MAX_COORDINATE).equals(property.get("minimum"))
                    || !Integer.valueOf(MAX_COORDINATE).equals(property.get("maximum"))
                    || !String.valueOf(property.get("description")).endsWith(" From -1000000 to 1000000.")) {
                wrong.add("update-view-object " + key + " schema: " + property);
            }
        }
        JsonNode atBound = h.call("update-view-object", args("viewObjectId", "vo-a", "anchorTarget", "vo-b",
                "anchorEdge", "right", "anchorDx", MAX_COORDINATE));
        if (atBound.path("isError").asBoolean()) {
            wrong.add("anchorDx at the bound: " + atBound.path("error").path("message").asText());
        }
        assertEquals(List.of(), wrong);
    }

    /** The lower bounds are the tools' own, checked where they always were, with their own words. */
    @Test
    public void shouldKeepEveryLowerBoundMessage() {
        List<String> wrong = new ArrayList<>();
        refused(wrong, "add-to-view", args("viewId", VIEW, "elementId", "do-1", "width", 0),
                "width must be positive, got: 0", null);
        refused(wrong, "update-view-object", args("viewObjectId", "vo-a", "height", -5),
                "height must be positive, got: -5", null);
        assertEquals(List.of(), wrong);
    }

    /**
     * Each bound itself is not refused by the cap, on every key. A call may still answer something else for its
     * own reason (a placeholder id, a missing image), but never the cap's refusal.
     */
    @Test
    public void shouldNotRefuseTheBoundsThemselves_onAnyKey() {
        List<String> wrong = new ArrayList<>();
        List<Object[]> calls = new ArrayList<>();
        for (Object[] t : placementTools()) {
            for (String key : List.of("x", "y")) {
                calls.add(new Object[] {t[0], t[1], key, MAX_COORDINATE});
                calls.add(new Object[] {t[0], t[1], key, -MAX_COORDINATE});
            }
            for (String key : List.of("width", "height")) {
                calls.add(new Object[] {t[0], t[1], key, MAX_SIZE});
            }
        }
        for (Object[] t : layoutTools()) {
            for (String key : List.of("elementWidth", "elementHeight")) {
                calls.add(new Object[] {t[0], t[1], key, MAX_SIZE});
            }
        }
        for (Object[] c : calls) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = with((Map<String, Object>) c[1], (String) c[2], c[3]);
            JsonNode reply = h.call((String) c[0], params);
            String text = reply.path("error").path("message").asText();
            if (text.contains(" must be from -1000000") || text.contains(" must be at most 100000")) {
                wrong.add(c[0] + " " + c[2] + "=" + c[3] + ": " + text);
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldPublishTheBounds_inEachSchema() {
        List<String> wrong = new ArrayList<>();
        List<Object[]> keys = new ArrayList<>();
        for (Object[] t : placementTools()) {
            keys.add(new Object[] {t[0], properties((String) t[0])});
        }
        for (Object[] t : layoutTools()) {
            keys.add(new Object[] {t[0], properties((String) t[0])});
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> positions = (Map<String, Object>) properties("apply-positions").get("positions");
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) positions.get("items");
        keys.add(new Object[] {"apply-positions entry", entry.get("properties")});
        for (Object[] k : keys) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) k[1];
            for (String key : List.of("x", "y", "width", "height", "elementWidth", "elementHeight")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> property = (Map<String, Object>) props.get(key);
                if (property == null) {
                    continue;
                }
                boolean coordinate = key.length() == 1;
                Object min = coordinate ? -MAX_COORDINATE : null;
                Object max = coordinate ? MAX_COORDINATE : MAX_SIZE;
                String sentence = coordinate ? "From -1000000 to 1000000." : "At most 100000.";
                if (!java.util.Objects.equals(min, property.get("minimum"))
                        || !max.equals(property.get("maximum"))
                        || !String.valueOf(property.get("description")).endsWith(" " + sentence)) {
                    wrong.add(k[0] + " " + key + ": " + property);
                }
            }
        }
        assertEquals("28 keys on the tools and 4 on an apply-positions entry", 32, countBounded(keys));
        assertEquals(List.of(), wrong);
    }

    private static int countBounded(List<Object[]> keys) {
        int n = 0;
        for (Object[] k : keys) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) k[1];
            for (String key : List.of("x", "y", "width", "height", "elementWidth", "elementHeight")) {
                n += props.containsKey(key) ? 1 : 0;
            }
        }
        return n;
    }

    private Map<String, Object> properties(String tool) {
        McpSchema.Tool spec = h.registry().getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(tool)).findFirst().orElseThrow().tool();
        return spec.inputSchema().properties();
    }
}
