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

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IIdentifier;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code create-view} and {@code update-view} read {@code viewpoint} by one rule on the tool and on its
 * {@code bulk-mutate} operation: absent leaves it alone, {@code ""} means no viewpoint (or clears it), a blank
 * or non-string value is refused rather than read as absent, and an id Archi does not define is refused
 * rather than stored. Every refusal is pinned whole, with nothing created or changed and the version unmoved.
 */
public class ViewpointArgumentTest {

    private static final String UNKNOWN = ": Archi defines no viewpoint with that id, so it is refused rather "
            + "than stored. Nothing was changed.";

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("viewpoint-argument-model");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    private static Map<String, Object> op(String tool, Map<String, Object> params) {
        return Map.of("tool", tool, "params", params);
    }

    private String bulkRow(String tool, Map<String, Object> params) {
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(op(tool, params))));
        assertTrue(reply.toString(), reply.path("isError").asBoolean());
        String message = reply.path("error").path("message").asText();
        assertTrue(message, message.startsWith("Operation 0 (" + tool + "): "));
        return message.substring(message.indexOf("): ") + 3);
    }

    private IArchimateDiagramModel view(String id) {
        return (IArchimateDiagramModel) h.f.model.getFolder(FolderType.DIAGRAMS).getElements().stream()
                .filter(v -> id.equals(((IIdentifier) v).getId())).findFirst().orElseThrow();
    }

    private int views() {
        return h.f.model.getFolder(FolderType.DIAGRAMS).getElements().size();
    }

    private String state() {
        return views() + " views, source '" + h.f.view.getName() + "' viewpoint '" + h.f.view.getViewpoint() + "'";
    }

    private void refusedOnBoth(List<String> wrong, String tool, Map<String, Object> params, String message) {
        String version = h.f.accessor.getModelVersion();
        String before = state();
        JsonNode error = h.errorOf(tool, new LinkedHashMap<>(params));
        if (!"INVALID_PARAMETER".equals(error.path("code").asText())) {
            wrong.add(tool + " " + params + " tool: code " + error.path("code").asText());
        }
        if (!message.equals(error.path("message").asText())) {
            wrong.add(tool + " " + params + " tool: " + error.path("message").asText());
        }
        String row = bulkRow(tool, new LinkedHashMap<>(params));
        if (!message.equals(row)) {
            wrong.add(tool + " " + params + " bulk: " + row);
        }
        if (!version.equals(h.f.accessor.getModelVersion())) {
            wrong.add(tool + " " + params + ": the model version moved");
        }
        if (!before.equals(state())) {
            wrong.add(tool + " " + params + ": " + before + " became " + state());
        }
    }

    // ---- a value that does not read ----

    @Test
    public void shouldRefuseABlankOrNonStringViewpoint_onBothTools_andBothPaths() {
        List<String> wrong = new ArrayList<>();
        String blank = "viewpoint must not be blank, got the string ' '. Nothing was changed.";
        String number = "viewpoint must be a string, got the integer 5. Nothing was changed.";
        refusedOnBoth(wrong, "create-view", args("name", "New", "viewpoint", " "), blank);
        refusedOnBoth(wrong, "create-view", args("name", "New", "viewpoint", 5), number);
        refusedOnBoth(wrong, "update-view", args("viewId", "view-source", "viewpoint", " "), blank);
        refusedOnBoth(wrong, "update-view", args("viewId", "view-source", "viewpoint", 5), number);
        refusedOnBoth(wrong, "update-view", args("viewId", "view-source", "name", "Renamed", "viewpoint", " "), blank);
        refusedOnBoth(wrong, "update-view", args("viewId", "view-source", "name", "Renamed", "viewpoint", 5), number);
        assertEquals(List.of(), wrong);
    }

    // ---- an id Archi does not define ----

    @Test
    public void shouldRefuseAnUnknownId_andACaseNearMiss_onBothTools_andBothPaths() {
        List<String> wrong = new ArrayList<>();
        for (String id : List.of("bogus", "Layered", "none")) {
            String message = "Unknown viewpoint '" + id + "'" + UNKNOWN;
            refusedOnBoth(wrong, "create-view", args("name", "New", "viewpoint", id), message);
            refusedOnBoth(wrong, "update-view", args("viewId", "view-source", "viewpoint", id), message);
            refusedOnBoth(wrong, "update-view", args("viewId", "view-source", "name", "Renamed", "viewpoint", id),
                    message);
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldListArchisIds_sorted_inTheSuggestion() {
        List<String> ids = new ArrayList<>(ViewpointIds.known());
        assertTrue(ids.toString(), ids.contains("layered") && ids.contains("application_cooperation"));
        assertFalse("the empty 'none' viewpoint is not listed", ids.contains(""));
        assertEquals(ids.stream().sorted().toList(), ids);
        assertEquals("Use one of: " + String.join(", ", ids) + "; or \"\" for no viewpoint.",
                h.errorOf("create-view", args("name", "New", "viewpoint", "bogus")).path("suggestedCorrection")
                        .asText());
    }

    // ---- what still reads ----

    @Test
    public void shouldStillSetARealId_onBothTools() {
        String created = h.success("create-view", "name", "New", "viewpoint", "layered").path("result").path("id")
                .asText();
        assertEquals("layered", view(created).getViewpoint());
        h.success("update-view", "viewId", "view-source", "viewpoint", "application_cooperation");
        assertEquals("application_cooperation", h.f.view.getViewpoint());
    }

    @Test
    public void shouldStillClearWithTheEmptyString_andCreateWithNoViewpoint() {
        h.f.view.setViewpoint("layered");
        h.success("update-view", "viewId", "view-source", "viewpoint", "");
        assertEquals("cleared to the empty string, never null", "", h.f.view.getViewpoint());
        String created = h.success("create-view", "name", "New", "viewpoint", "").path("result").path("id").asText();
        assertEquals("", view(created).getViewpoint());
    }

    @Test
    public void shouldLeaveAStoredUnknownIdAlone_andStillUpdateTheView() {
        h.f.view.setViewpoint("legacy-unknown");
        h.success("update-view", "viewId", "view-source", "name", "Renamed");
        assertEquals("Renamed", h.f.view.getName());
        assertEquals("legacy-unknown", h.f.view.getViewpoint());
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(op("update-view",
                args("viewId", "view-source", "documentation", "doc")))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals("legacy-unknown", h.f.view.getViewpoint());
    }

    @Test
    public void shouldAcceptTheStoredUnknownIdSentBack_butNoOtherUnknownId() {
        h.f.view.setViewpoint("legacy-unknown");
        h.success("update-view", "viewId", "view-source", "name", "Renamed", "viewpoint", "legacy-unknown");
        assertEquals("Renamed", h.f.view.getName());
        assertEquals("legacy-unknown", h.f.view.getViewpoint());
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(op("update-view",
                args("viewId", "view-source", "documentation", "doc", "viewpoint", "legacy-unknown")))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals("legacy-unknown", h.f.view.getViewpoint());
        List<String> wrong = new ArrayList<>();
        refusedOnBoth(wrong, "update-view", args("viewId", "view-source", "name", "Again", "viewpoint", "legacy-other"),
                "Unknown viewpoint 'legacy-other'" + UNKNOWN);
        assertEquals(List.of(), wrong);
    }
}
