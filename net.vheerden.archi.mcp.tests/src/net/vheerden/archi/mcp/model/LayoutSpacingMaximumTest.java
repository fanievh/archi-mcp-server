package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
 * The layout tools' spacing and padding are held to a published maximum, {@link LayoutSpacing#MAX_LAYOUT_SPACING}:
 * one above it is refused naming the key, whole message pinned, with nothing changed and the model version
 * unmoved. Each feeds {@code int} coordinate arithmetic, and {@code spacing: 2147483647} used to answer success
 * having placed an element at x -2147483519.
 */
public class LayoutSpacingMaximumTest {

    private static final String VIEW = "view-source";

    private EchoToolHarness h;
    private IDiagramModelGroup group;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("layout-spacing-maximum-model");
        group = h.f.group(h.f.view, "g1", 10, 200, 400, 200);
        h.f.object(group, "vo-g1", h.f.element("el-g1"), 10, 30, 120, 55);
        h.f.object(group, "vo-g2", h.f.element("el-g2"), 150, 30, 120, 55);
        h.f.group(h.f.view, "g2", 500, 200, 200, 100);
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    /** Each capped key, with the arguments its tool needs beside it. */
    private static List<Object[]> cappedKeys() {
        return List.of(
                new Object[] {"auto-layout-and-route", "spacing", args("viewId", VIEW)},
                new Object[] {"layout-within-group", "spacing", groupLayout()},
                new Object[] {"layout-within-group", "padding", groupLayout()},
                new Object[] {"arrange-groups", "spacing", args("viewId", VIEW, "arrangement", "row")},
                new Object[] {"optimize-group-order", "spacing", args("viewId", VIEW)},
                new Object[] {"optimize-group-order", "padding", args("viewId", VIEW)},
                new Object[] {"layout-flat-view", "spacing", args("viewId", VIEW, "arrangement", "grid")},
                new Object[] {"layout-flat-view", "padding", args("viewId", VIEW, "arrangement", "grid")});
    }

    private static Map<String, Object> groupLayout() {
        return args("viewId", VIEW, "groupViewObjectId", "g1", "arrangement", "row");
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

    private static String message(String tool, String key, Object value) {
        String described = ToolArguments.describe(value);
        return "auto-layout-and-route".equals(tool)
                ? key + " must be a whole number from 1 to 1000, got " + described + ". Nothing was changed."
                : key + " must be at most 1000, got " + described + ". Nothing was changed.";
    }

    @Test
    public void shouldRefuseASpacingOrPaddingAboveTheMaximum_withNothingChanged() {
        List<String> wrong = new ArrayList<>();
        for (Object[] c : cappedKeys()) {
            for (Object value : List.of(1001, Integer.MAX_VALUE)) {
                String tool = (String) c[0];
                String key = (String) c[1];
                @SuppressWarnings("unchecked")
                Map<String, Object> params = new LinkedHashMap<>((Map<String, Object>) c[2]);
                params.put(key, value);
                String version = h.f.accessor.getModelVersion();
                String before = state();
                JsonNode reply = h.call(tool, params);
                JsonNode error = reply.path("error");
                if (!reply.path("isError").asBoolean()) {
                    wrong.add(tool + " " + key + "=" + value + ": answered success");
                    continue;
                }
                if (!"INVALID_PARAMETER".equals(error.path("code").asText())
                        || !message(tool, key, value).equals(error.path("message").asText())) {
                    wrong.add(tool + " " + key + "=" + value + ": " + error.path("code").asText() + " "
                            + error.path("message").asText());
                }
                String suggestion = "auto-layout-and-route".equals(tool) ? "Pass spacing from 1 to 1000, or omit it."
                        : "Pass " + key + " up to 1000, or omit it.";
                if (!suggestion.equals(error.path("suggestedCorrection").asText())) {
                    wrong.add(tool + " " + key + ": suggestion " + error.path("suggestedCorrection").asText());
                }
                if (!version.equals(h.f.accessor.getModelVersion()) || !before.equals(state())) {
                    wrong.add(tool + " " + key + "=" + value + ": changed the model");
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldPublishTheMaximum_inEachSchema() {
        List<String> wrong = new ArrayList<>();
        for (Object[] c : cappedKeys()) {
            McpSchema.Tool tool = h.registry().getToolSpecifications().stream()
                    .filter(s -> s.tool().name().equals(c[0])).findFirst().orElseThrow().tool();
            @SuppressWarnings("unchecked")
            Map<String, Object> property = (Map<String, Object>) tool.inputSchema().properties().get(c[1]);
            if (!Integer.valueOf(1000).equals(property.get("maximum"))) {
                wrong.add(c[0] + " " + c[1] + ": maximum " + property.get("maximum"));
            }
            if (!String.valueOf(property.get("description")).contains("at most 1000")) {
                wrong.add(c[0] + " " + c[1] + ": " + property.get("description"));
            }
        }
        assertEquals(List.of(), wrong);
    }

    /**
     * The maximum itself is not refused by the cap, on every key. A tool may still answer something else for its
     * own reason (optimize-group-order needs inter-group connections, auto-layout-and-route a display), but never
     * the cap's refusal.
     */
    @Test
    public void shouldNotRefuseTheMaximumItself_onAnyKey() {
        List<String> wrong = new ArrayList<>();
        for (Object[] c : cappedKeys()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = new LinkedHashMap<>((Map<String, Object>) c[2]);
            params.put((String) c[1], 1000);
            JsonNode reply = h.call((String) c[0], params);
            String text = reply.path("error").path("message").asText();
            if (reply.path("isError").asBoolean() && text.startsWith(c[1] + " must be")) {
                wrong.add(c[0] + " " + c[1] + "=1000: " + text);
            }
        }
        assertEquals(List.of(), wrong);
        JsonNode flat = h.call("layout-flat-view", args("viewId", VIEW, "arrangement", "grid", "spacing", 1000,
                "padding", 1000));
        assertFalse(flat.toString(), flat.path("isError").asBoolean());
    }

    @Test
    public void shouldStillLayOutAtTheMaximum_withoutWrapping() {
        JsonNode within = h.call("layout-within-group", args("viewId", VIEW, "groupViewObjectId", "g1",
                "arrangement", "row", "spacing", 1000, "padding", 1000));
        assertFalse(within.toString(), within.path("isError").asBoolean());
        for (Object o : group.getChildren()) {
            IDiagramModelObject child = (IDiagramModelObject) o;
            assertFalse(bounds(child), child.getBounds().getX() < 0);
        }
        JsonNode arranged = h.call("arrange-groups", args("viewId", VIEW, "arrangement", "row", "spacing", 1000));
        assertFalse(arranged.toString(), arranged.path("isError").asBoolean());
        assertFalse(arranged.toString(), arranged.path("result").path("layoutWidth").asInt() < 0);
    }
}
