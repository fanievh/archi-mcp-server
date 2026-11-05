package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.fasterxml.jackson.databind.JsonNode;

import io.modelcontextprotocol.server.McpServerFeatures;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Every string-typed argument of every registered tool, as the served schema publishes it, and of every
 * {@code bulk-mutate} operation: a value that is present and not a string (a number, a boolean, an array, an
 * object) is refused naming the argument and the value, with nothing written, rather than read as absent. A
 * value read as absent used to let the call succeed with the argument dropped, or reach a guard that answered
 * "nothing to change" or "missing parameter" for an argument the caller did send.
 *
 * <p>Each call is sent with the tool's required arguments set to values the fixture resolves, so it reaches
 * the read of the argument under test. Every refusal is pinned whole.</p>
 */
public class StringArgumentTypeContractTest {

    /** The values sent, each with the words a refusal names it by. */
    private static final Object[][] NOT_STRINGS = {
            {1, "the integer 1"}, {true, "the boolean true"}, {List.of(), "an array"}, {Map.of(), "an object"}};

    private static final String LABEL_POSITION =
            "Invalid labelPosition: expected source, middle or target, got %s. Nothing was changed.";

    /**
     * Arguments refused by type in their own words before this contract was written: each names the argument
     * and the value given, so it is kept, alone and in bulk. The value's words are substituted for {@code %s}.
     */
    private static final Map<String, String> OWN_WORDING = Map.of(
            "add-connection-to-view labelPosition", LABEL_POSITION,
            "update-view-connection labelPosition", LABEL_POSITION,
            "change-element-type invalidRelationships",
            "invalidRelationships must be a string: refuse or to-association, got %s. Nothing was changed.",
            "update-view-connection lineColor", unread("lineColor", "a #RRGGBB string, or \"\" to clear"),
            "update-view-connection fontColor", unread("fontColor", "a #RRGGBB string, or \"\" to clear"),
            "update-view-connection fontName", unread("fontName", "a string, or \"\" to clear"),
            "update-view-connection fontStyle", unread("fontStyle", "one of normal, bold, italic, bold-italic"));

    /** update-view-connection's refusal of a call whose one argument does not read, which names its type. */
    private static String unread(String key, String expected) {
        return "update-view-connection read nothing to change from " + key + " (expected " + expected
                + ", got %s). Nothing was changed, and the route was kept.";
    }

    /**
     * Arguments deliberately read from a value that is not a string, with the reason. A number given for
     * {@code influenceStrength} is read as its text: the strength is a free-form label Archi stores as a
     * string, and a numeric strength is what a caller most often sends.
     */
    private static final Map<String, String> ACCEPTS_NON_STRING = Map.of(
            "create-relationship influenceStrength", "a number is read as its text",
            "update-relationship influenceStrength", "a number is read as its text");

    private EchoToolHarness h;
    private String folderId;

    @After
    public void tearDown() {
        if (h != null) {
            h.dispose();
        }
    }

    private void fresh() throws Exception {
        tearDown();
        h = new EchoToolHarness("string-type-model");
        h.f.group(h.f.view, "grp-1", 600, 300, 300, 200);
        folderId = h.f.model.getFolder(FolderType.APPLICATION).getId();
    }

    /** A value for a required argument that the fixture resolves, by tool then by name. */
    private Object requiredValue(String tool, String name) {
        Map<String, Object> byTool = Map.of(
                "create-relationship:type", "AssociationRelationship", "update-folder:id", folderId,
                "update-relationship:id", "rel-ab", "add-to-view:elementId", "do-1");
        Object v = byTool.get(tool + ":" + name);
        if (v != null) {
            return v;
        }
        return switch (name) {
            case "viewId", "sourceViewId" -> "view-source";
            case "referencedViewId" -> "view-other";
            case "elementId", "id", "objectId", "conceptId", "sourceId" -> "el-a";
            case "targetId" -> "el-b";
            case "relationshipId" -> "rel-ab";
            case "viewObjectId", "sourceViewObjectId" -> "vo-a";
            case "targetViewObjectId" -> "vo-b";
            case "viewConnectionId" -> "conn-ab";
            case "groupViewObjectId" -> "grp-1";
            case "parentId", "folderId", "targetFolderId" -> folderId;
            case "name", "label", "content", "text", "newName", "labelExpression", "createName" -> "N";
            case "query" -> "el";
            case "conceptType" -> "BusinessActor";
            case "arrangement" -> "row";
            case "imagePath" -> ViewContentsFixture.IMAGE_PATH;
            case "type", "createType" -> "BusinessActor";
            case "modelId" -> "string-type-model";
            case "changes" -> List.of(Map.of("elementId", "el-a", "type", "ApplicationInterface"));
            case "merges" -> List.of(Map.of("targetId", "el-a", "sourceIds", List.of("el-b")));
            case "operations" -> List.of(Map.of("tool", "update-view", "params",
                    Map.of("viewId", "view-source", "name", "Renamed")));
            default -> "NO FIXTURE VALUE";
        };
    }

    private Map<String, Object> baseArgs(String tool, List<String> required) {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("begin-batch".equals(tool)) {
            out.put("modelId", "string-type-model");
        }
        if ("get-element".equals(tool)) {
            out.put("id", "el-a");
        }
        if ("search-and-create".equals(tool)) {
            out.put("createType", "BusinessActor");
            out.put("createName", "N");
        }
        for (String r : required == null ? List.<String>of() : required) {
            out.put(r, requiredValue(tool, r));
        }
        return out;
    }

    private record Shape(List<String> strings, List<String> required) {
    }

    /** Every served tool's string-typed properties and required properties, by tool name. */
    private Map<String, Shape> served() throws Exception {
        fresh();
        Map<String, Shape> out = new TreeMap<>();
        for (McpServerFeatures.SyncToolSpecification spec : h.registry().getToolSpecifications()) {
            Map<String, Object> props = spec.tool().inputSchema().properties();
            List<String> strings = new ArrayList<>();
            if (props != null) {
                for (Map.Entry<String, Object> p : props.entrySet()) {
                    // modelId is read by the call scope before any tool, and refused there in its own words.
                    if (p.getValue() instanceof Map<?, ?> m && "string".equals(m.get("type"))
                            && !"modelId".equals(p.getKey())) {
                        strings.add(p.getKey());
                    }
                }
            }
            out.put(spec.tool().name(), new Shape(strings, spec.tool().inputSchema().required()));
        }
        return out;
    }

    private static String expected(String site, String key, String words) {
        String own = OWN_WORDING.get(site);
        return own != null ? String.format(own, words)
                : key + " must be a string, got " + words + ". Nothing was changed.";
    }

    @Test
    public void shouldRefuseEveryStringArgumentGivenAnotherType_onEveryTool() throws Exception {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, Shape> tool : served().entrySet()) {
            fresh();
            String version = h.f.accessor.getModelVersion();
            for (String key : tool.getValue().strings()) {
                String site = tool.getKey() + " " + key;
                if (ACCEPTS_NON_STRING.containsKey(site)) {
                    continue;
                }
                for (Object[] v : NOT_STRINGS) {
                    Map<String, Object> call = baseArgs(tool.getKey(), tool.getValue().required());
                    call.put(key, v[0]);
                    checked++;
                    check(wrong, site + "=" + v[1], () -> h.call(tool.getKey(), call), expected(site, key, (String) v[1]),
                            "INVALID_PARAMETER");
                }
            }
            if (h.f.stack.canUndo() || !version.equals(h.f.accessor.getModelVersion())) {
                wrong.add(tool.getKey() + ": the model changed");
            }
        }
        assertTrue("checked " + checked, checked > 1000);
        assertEquals(String.join("\n", wrong), 0, wrong.size());
    }

    @Test
    public void shouldRefuseEveryStringArgumentGivenAnotherType_onEveryBulkOperation() throws Exception {
        Map<String, Shape> served = served();
        served.put(ToolArguments.SET_VIEW_LABEL_EXPRESSION.tool(),
                new Shape(List.of("viewId", "labelExpression"), ToolArguments.SET_VIEW_LABEL_EXPRESSION.required()));
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (String tool : BulkOperation.SUPPORTED_TOOLS_ORDERED) {
            Shape shape = served.get(tool);
            fresh();
            String version = h.f.accessor.getModelVersion();
            for (String key : shape.strings()) {
                String site = tool + " " + key;
                // A bulk update-view-object refuses the anchor keys whatever their value: anchoring is the tool's.
                if (ACCEPTS_NON_STRING.containsKey(site) || ("update-view-object".equals(tool)
                        && key.startsWith("anchor"))) {
                    continue;
                }
                for (Object[] v : NOT_STRINGS) {
                    Map<String, Object> params = baseArgs(tool, shape.required());
                    params.put(key, v[0]);
                    checked++;
                    check(wrong, site + "=" + v[1], () -> h.call("bulk-mutate",
                            args("operations", List.of(Map.of("tool", tool, "params", params)))),
                            "Operation 0 (" + tool + "): " + expected(site, key, (String) v[1]),
                            "BULK_VALIDATION_FAILED");
                }
            }
            if (h.f.stack.canUndo() || !version.equals(h.f.accessor.getModelVersion())) {
                wrong.add(tool + ": the model changed");
            }
        }
        assertTrue("checked " + checked, checked > 500);
        assertEquals(String.join("\n", wrong), 0, wrong.size());
    }

    private static void check(List<String> wrong, String what, java.util.function.Supplier<JsonNode> call,
            String want, String code) {
        JsonNode reply;
        try {
            reply = call.get();
        } catch (Throwable t) {
            wrong.add(what + ": threw " + t);
            return;
        }
        String message = reply.path("error").path("message").asText();
        if (!reply.path("isError").asBoolean()) {
            wrong.add(what + ": succeeded");
        } else if (!code.equals(reply.path("error").path("code").asText()) || !want.equals(message)) {
            wrong.add(what + ": " + reply.path("error").path("code").asText() + " " + message);
        }
    }

    @Test
    public void shouldRefuseANumberGivenForFigureType_onBothReportedTools() throws Exception {
        fresh();
        String want = "figureType must be a string, got the integer 1. Nothing was changed.";
        assertEquals(want, h.message("add-view-reference-to-view", "viewId", "view-source", "referencedViewId",
                "view-other", "figureType", 1));
        assertEquals(want, h.message("update-view-object", "viewObjectId", "vo-a", "figureType", 1));
        assertEquals(1, h.f.view.getChildren().stream().filter(c -> !(c instanceof com.archimatetool.model.IDiagramModelArchimateObject)).count());
    }

    @Test
    public void shouldRefuseAnExcludeEntryThatIsNotAString_onEveryReadTool() throws Exception {
        fresh();
        Object[][] calls = {
                {"get-views", args()}, {"get-view-contents", args("viewId", "view-source")},
                {"search-elements", args("query", "el")}, {"search-relationships", args("query", "el")},
                {"get-element", args("id", "el-a")}, {"get-relationships", args("elementId", "el-a")},
                {"get-folders", args()}, {"get-folder-tree", args()}};
        for (Object[] c : calls) {
            @SuppressWarnings("unchecked")
            Map<String, Object> call = new LinkedHashMap<>((Map<String, Object>) c[1]);
            call.put("exclude", List.of("documentation", 1));
            assertEquals((String) c[0], "each exclude entry must be a string, got the integer 1. Nothing was changed.",
                    h.errorOf((String) c[0], call).path("message").asText());
        }
    }

    @Test
    public void shouldFailAnImageEntryWhoseFieldIsNotAString_namingTheField() throws Exception {
        fresh();
        JsonNode reply = h.call("add-image-to-model", args("images", List.of(Map.of("filePath", 1))));
        assertTrue(reply.toString(), reply.toString().contains(
                "filePath must be a string, got the integer 1. Nothing was changed."));
    }

    @Test
    public void shouldKeepBlankAsAbsentAndTheEmptyStringAsAValue() {
        assertNull(ToolArguments.optionalText(args("k", "  "), "k", false));
        assertEquals("  ", ToolArguments.optionalText(args("k", "  "), "k", true));
        assertEquals("", ToolArguments.optionalText(args("k", ""), "k", true));
        assertNull(ToolArguments.optionalText(args("k", null), "k", true));
        assertNull(ToolArguments.optionalText(args(), "k", false));
        assertEquals("v", ToolArguments.optionalText(args("k", "v"), "k", false));
    }
}
