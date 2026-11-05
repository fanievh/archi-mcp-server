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

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;
import com.fasterxml.jackson.databind.JsonNode;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * A caller's bendpoints are held to published bounds: an absolute point ({@code x}, {@code y}) to
 * {@link ViewGeometry#MAX_BENDPOINT_POINT} either side of the origin, wider than an object's coordinate since
 * that is measured from its parent, and a relative offset ({@code startX}, {@code startY}, {@code endX},
 * {@code endY}) to {@link ViewGeometry#MAX_BENDPOINT_OFFSET}, since an offset runs from an end's centre to a point.
 * One past a bound is refused naming the field, whole message pinned, on add-connection-to-view,
 * update-view-connection, their bulk-mutate operations and an apply-positions connection entry, with nothing
 * changed. Each is converted with {@code int} arithmetic: an absolute
 * {@code x: 2147483000} from a source at x -2000 used to answer success, store {@code startX -2147482356} and
 * report the point back as x -648.
 */
public class BendpointBoundsTest {

    private static final String VIEW = "view-source";
    private static final int MAX_POINT = 10_000_000;
    private static final int MAX_OFFSET = 20_000_000;

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("bendpoint-bounds-model");
        voA().setBounds(-2000, 40, 120, 60);
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    private IDiagramModelObject voA() {
        return (IDiagramModelObject) h.f.view.getChildren().stream()
                .filter(o -> "vo-a".equals(o.getId())).findFirst().orElseThrow();
    }

    private IDiagramModelArchimateConnection connAb() {
        return (IDiagramModelArchimateConnection) ((IDiagramModelArchimateObject) voA()).getSourceConnections()
                .get(0);
    }

    /** The view's objects and conn-ab's stored route. */
    private String state() {
        return h.f.view.getChildren().size() + " objects; " + voA().getSourceConnections().size()
                + " connections from vo-a; conn-ab " + connAb().getBendpoints().stream()
                        .map(b -> b.getStartX() + "," + b.getStartY() + "," + b.getEndX() + "," + b.getEndY())
                        .toList();
    }

    private static Map<String, Object> absolute(String key, Object value) {
        Map<String, Object> point = args("x", 10, "y", 10);
        point.put(key, value);
        return point;
    }

    private static Map<String, Object> relative(String key, Object value) {
        Map<String, Object> offset = args("startX", 10, "startY", 10, "endX", 10, "endY", 10);
        offset.put(key, value);
        return offset;
    }

    /** The two connection tools, each with what it needs beside the bendpoints. */
    private static List<Object[]> tools() {
        return List.of(
                new Object[] {"update-view-connection", args("viewConnectionId", "conn-ab")},
                new Object[] {"add-connection-to-view", args("viewId", VIEW, "relationshipId", "rel-ab",
                        "sourceViewObjectId", "vo-a", "targetViewObjectId", "vo-b")});
    }

    private static String message(String key, int bound, Object value) {
        return "Bendpoint[0]." + key + " must be from " + -bound + " to " + bound + ", got "
                + ToolArguments.describe(value) + ". Nothing was changed.";
    }

    private void refusedOnBoth(List<String> wrong, String tool, Map<String, Object> params, String message,
            String suggestion) {
        String version = h.f.accessor.getModelVersion();
        String before = state();
        JsonNode reply = h.call(tool, new LinkedHashMap<>(params));
        JsonNode error = reply.path("error");
        if (!reply.path("isError").asBoolean()) {
            wrong.add(tool + " " + params + ": answered success");
        } else if (!"INVALID_PARAMETER".equals(error.path("code").asText())
                || !message.equals(error.path("message").asText())
                || !suggestion.equals(error.path("suggestedCorrection").asText())) {
            wrong.add(tool + " " + params + ": " + error.path("code").asText() + " " + error.path("message").asText()
                    + " / " + error.path("suggestedCorrection").asText());
        }
        unchanged(wrong, tool + " " + params, version, before);
        reply = h.call("bulk-mutate", args("operations",
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
    public void shouldRefuseAnAbsolutePointPastEitherBound_onBothToolsAndInBulk() {
        List<String> wrong = new ArrayList<>();
        for (Object[] t : tools()) {
            for (String key : List.of("x", "y")) {
                for (Object value : List.of(MAX_POINT + 1, -MAX_POINT - 1, 2147483000)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = new LinkedHashMap<>((Map<String, Object>) t[1]);
                    params.put("absoluteBendpoints", List.of(absolute(key, value)));
                    refusedOnBoth(wrong, (String) t[0], params, message(key, MAX_POINT, value),
                            "Pass each bendpoint's " + key + " from -10000000 to 10000000.");
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldRefuseARelativeOffsetPastEitherBound_onBothToolsAndInBulk() {
        List<String> wrong = new ArrayList<>();
        for (Object[] t : tools()) {
            for (String key : List.of("startX", "startY", "endX", "endY")) {
                for (Object value : List.of(MAX_OFFSET + 1, -MAX_OFFSET - 1)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = new LinkedHashMap<>((Map<String, Object>) t[1]);
                    params.put("bendpoints", List.of(relative(key, value)));
                    refusedOnBoth(wrong, (String) t[0], params, message(key, MAX_OFFSET, value),
                            "Pass each bendpoint's " + key + " from -20000000 to 20000000.");
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldRefuseTheConnectionEntryPastABound_onApplyPositions() {
        String version = h.f.accessor.getModelVersion();
        String before = state();
        JsonNode error = h.errorOf("apply-positions", args("viewId", VIEW, "connections", List.of(
                args("viewConnectionId", "conn-ab", "absoluteBendpoints", List.of(absolute("x", 2147483000))))));

        assertEquals("connections[0]: " + message("x", MAX_POINT, 2147483000), error.path("message").asText());
        assertEquals(version, h.f.accessor.getModelVersion());
        assertEquals(before, state());
    }

    @Test
    public void shouldRefuseARelativeOffsetPastTheBound_onApplyPositions() {
        String version = h.f.accessor.getModelVersion();
        String before = state();
        JsonNode error = h.errorOf("apply-positions", args("viewId", VIEW, "connections", List.of(
                args("viewConnectionId", "conn-ab", "bendpoints", List.of(relative("startY", MAX_OFFSET + 1))))));

        assertEquals("connections[0]: " + message("startY", MAX_OFFSET, MAX_OFFSET + 1),
                error.path("message").asText());
        assertEquals(version, h.f.accessor.getModelVersion());
        assertEquals(before, state());
    }

    /** Each bound itself is accepted, and an absolute point at it is stored where it was sent. */
    @Test
    public void shouldAcceptTheBoundsThemselves() {
        List<String> wrong = new ArrayList<>();
        for (int value : List.of(MAX_POINT, -MAX_POINT)) {
            JsonNode reply = h.call("update-view-connection", args("viewConnectionId", "conn-ab",
                    "absoluteBendpoints", List.of(absolute("x", value))));
            if (reply.path("isError").asBoolean()) {
                wrong.add("absolute x=" + value + ": " + reply.path("error").path("message").asText());
            } else if (reply.path("result").path("absoluteBendpoints").path(0).path("x").asInt() != value) {
                wrong.add("absolute x=" + value + ": reported " + reply.path("result").path("absoluteBendpoints"));
            }
        }
        for (int value : List.of(MAX_OFFSET, -MAX_OFFSET)) {
            JsonNode reply = h.call("update-view-connection", args("viewConnectionId", "conn-ab",
                    "bendpoints", List.of(relative("endY", value))));
            if (reply.path("isError").asBoolean()) {
                wrong.add("relative endY=" + value + ": " + reply.path("error").path("message").asText());
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldPublishTheBounds_onEveryBendpointField() {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (String tool : List.of("add-connection-to-view", "update-view-connection", "apply-positions")) {
            Map<String, Object> props = properties(tool);
            if ("apply-positions".equals(tool)) {
                props = itemProperties(props.get("connections"));
            }
            for (Object[] array : List.of(new Object[] {"absoluteBendpoints", List.of("x", "y"), MAX_POINT},
                    new Object[] {"bendpoints", List.of("startX", "startY", "endX", "endY"), MAX_OFFSET})) {
                @SuppressWarnings("unchecked")
                Map<String, Object> arrayProp = (Map<String, Object>) props.get(array[0]);
                int bound = (Integer) array[2];
                String sentence = "Each " + ("bendpoints".equals(array[0]) ? "offset" : "coordinate") + " from "
                        + -bound + " to " + bound + ".";
                if (!String.valueOf(arrayProp.get("description")).endsWith(" " + sentence)) {
                    wrong.add(tool + " " + array[0] + ": " + arrayProp.get("description"));
                }
                Map<String, Object> fields = itemProperties(arrayProp);
                @SuppressWarnings("unchecked")
                List<String> keys = (List<String>) array[1];
                for (String key : keys) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> field = (Map<String, Object>) fields.get(key);
                    checked++;
                    if (!Integer.valueOf(-bound).equals(field.get("minimum"))
                            || !Integer.valueOf(bound).equals(field.get("maximum"))) {
                        wrong.add(tool + " " + array[0] + "." + key + ": " + field);
                    }
                }
            }
        }
        assertEquals(18, checked);
        assertEquals(List.of(), wrong);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> itemProperties(Object arrayProp) {
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) arrayProp).get("items"))
                .get("properties");
    }

    private Map<String, Object> properties(String tool) {
        McpSchema.Tool spec = h.registry().getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(tool)).findFirst().orElseThrow().tool();
        return spec.inputSchema().properties();
    }
}
