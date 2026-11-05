package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIdentifier;
import com.fasterxml.jackson.databind.JsonNode;

import net.vheerden.archi.mcp.response.dto.PendingProposalView;

/**
 * {@code add-note-to-view}'s {@code gap} and {@code position}, and {@code update-view}'s
 * {@code connectionRouterType}, are read by one strict rule on the tool and on its {@code bulk-mutate}
 * operation: a value the rule cannot read is refused by name, whole message pinned, with nothing changed and
 * the model version unmoved. It used to be truncated, wrapped, or read as absent, so the call answered success
 * having done something else.
 *
 * <p>The fixture view's content spans y 40..100 from x 40 (see {@link ToolArgumentReadsTest}). Notes carry an
 * explicit size because the fitted height measures text, which needs a display.</p>
 */
public class NotePlacementAndRouterReadsTest {

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("note-router-reads-model");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    private static Map<String, Object> note(Object... extra) {
        Map<String, Object> params = args("viewId", "view-source", "content", "Title", "width", 100, "height", 40);
        params.putAll(args(extra));
        return params;
    }

    private static Map<String, Object> op(String tool, Map<String, Object> params) {
        return Map.of("tool", tool, "params", params);
    }

    /** The whole message of the one failure row a refused bulk call reports. */
    private String bulkRow(String tool, Map<String, Object> params) {
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(op(tool, params))));
        assertTrue(reply.toString(), reply.path("isError").asBoolean());
        String message = reply.path("error").path("message").asText();
        assertTrue(message, message.startsWith("Operation 0 (" + tool + "): "));
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

    /** Refused on the tool and on bulk with {@code message}, the code INVALID_PARAMETER, and nothing changed. */
    private void refusedOnBoth(List<String> wrong, String tool, Map<String, Object> params, String message) {
        String version = h.f.accessor.getModelVersion();
        String state = state();
        JsonNode error = h.errorOf(tool, new LinkedHashMap<>(params));
        if (!"INVALID_PARAMETER".equals(error.path("code").asText())) {
            wrong.add(params + " tool: code " + error.path("code").asText());
        }
        if (!message.equals(error.path("message").asText())) {
            wrong.add(params + " tool: " + error.path("message").asText());
        }
        String row = bulkRow(tool, new LinkedHashMap<>(params));
        if (!message.equals(row)) {
            wrong.add(params + " bulk: " + row);
        }
        if (!version.equals(h.f.accessor.getModelVersion())) {
            wrong.add(params + ": the model version moved");
        }
        if (!state.equals(state())) {
            wrong.add(params + ": changed " + state + " to " + state());
        }
    }

    private String state() {
        return h.f.view.getName() + " " + h.f.view.getConnectionRouterType() + " " + notes().size() + " notes";
    }

    // ---- gap ----

    private static String outOfRange(String described) {
        return "gap must be a whole number from 0 to 1000, got " + described + ". Nothing was changed.";
    }

    @Test
    public void shouldRefuseAGapItCannotRead_onBothPaths_withNothingAdded() {
        List<String> wrong = new ArrayList<>();
        refusedOnBoth(wrong, "add-note-to-view", note("position", "below-content", "gap", 3000000000L),
                outOfRange("the long 3000000000"));
        refusedOnBoth(wrong, "add-note-to-view", note("position", "below-content", "gap", -1),
                outOfRange("the integer -1"));
        refusedOnBoth(wrong, "add-note-to-view", note("position", "below-content", "gap", 1001),
                outOfRange("the integer 1001"));
        refusedOnBoth(wrong, "add-note-to-view",
                note("position", "below-content", "gap", new BigInteger("99999999999999999999")),
                outOfRange("the biginteger 99999999999999999999"));
        refusedOnBoth(wrong, "add-note-to-view", note("position", "below-content", "gap", 15.7),
                "gap must be a whole number, got the double 15.7. Nothing was changed.");
        refusedOnBoth(wrong, "add-note-to-view", note("position", "below-content", "gap", "15"),
                "gap must be a whole number, got the string '15'. Nothing was changed.");
        refusedOnBoth(wrong, "add-note-to-view", note("position", "below-content", "gap", true),
                "gap must be a whole number, got the boolean true. Nothing was changed.");
        assertEquals(List.of(), wrong);
        assertTrue(notes().isEmpty());
    }

    @Test
    public void shouldSuggestTheRange_forAGapOutsideIt() {
        assertEquals("Pass gap from 0 to 1000, or omit it.",
                h.errorOf("add-note-to-view", note("position", "below-content", "gap", -1))
                        .path("suggestedCorrection").asText());
    }

    @Test
    public void shouldStillPlaceANoteAtAWholeGap_onBothPaths() {
        h.success("add-note-to-view", "viewId", "view-source", "content", "a", "width", 100, "height", 40,
                "position", "below-content", "gap", 20);
        h.success("add-note-to-view", "viewId", "view-source", "content", "b", "width", 100, "height", 40,
                "position", "below-content", "gap", 20.0);
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                op("add-note-to-view", note("position", "below-content", "gap", 20)))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        List<String> placed = notes().stream().map(NotePlacementAndRouterReadsTest::bounds).toList();
        assertEquals("gap 20 below the content, then below each note placed before it, on every path",
                List.of("40,120,100x40", "40,180,100x40", "40,240,100x40"), placed);
    }

    @Test
    public void shouldPlaceANoteAtEachEndOfTheRange() {
        h.success("add-note-to-view", "viewId", "view-source", "content", "a", "width", 100, "height", 40,
                "position", "below-content", "gap", 0);
        h.success("add-note-to-view", "viewId", "view-source", "content", "b", "width", 100, "height", 40,
                "position", "below-content", "gap", 1000);
        assertEquals("gap 0 below the content, then gap 1000 below that note", List.of("40,100,100x40",
                "40,1140,100x40"),
                notes().stream().map(NotePlacementAndRouterReadsTest::bounds).toList());
    }

    // ---- position ----

    @Test
    public void shouldRefuseAPositionItCannotRead_onBothPaths_withNothingAdded() {
        List<String> wrong = new ArrayList<>();
        refusedOnBoth(wrong, "add-note-to-view", note("position", 5),
                "position must be a string, got the integer 5. Nothing was changed.");
        refusedOnBoth(wrong, "add-note-to-view", note("position", "  "),
                "position must not be blank, got the string '  '. Nothing was changed.");
        refusedOnBoth(wrong, "add-note-to-view", note("position", ""),
                "position must not be blank, got an empty string. Nothing was changed.");
        refusedOnBoth(wrong, "add-note-to-view", note("position", List.of("above-content")),
                "position must be a string, got an array. Nothing was changed.");
        assertEquals(List.of(), wrong);
        assertTrue(notes().isEmpty());
        assertEquals("Pass position as a non-blank string or omit it.",
                h.errorOf("add-note-to-view", note("position", "  ")).path("suggestedCorrection").asText());
    }

    // ---- x and y beside position ----

    @Test
    public void shouldRefuseOneOfXAndY_besidePosition_onBothPaths_withNothingAdded() {
        List<String> wrong = new ArrayList<>();
        String half = "Both x and y must be specified together, or both omitted for auto-placement";
        refusedOnBoth(wrong, "add-note-to-view", note("position", "above-content", "x", 10), half);
        refusedOnBoth(wrong, "add-note-to-view", note("position", "below-content", "y", 10), half);
        assertEquals(List.of(), wrong);
        assertTrue(notes().isEmpty());
    }

    @Test
    public void shouldStillLetPositionTakePrecedence_overXAndYGivenTogether() {
        JsonNode reply = h.success("add-note-to-view", "viewId", "view-source", "content", "a", "width", 100,
                "height", 40, "position", "above-content", "x", 10, "y", 20);
        assertEquals("position='above-content' takes precedence over explicit x/y coordinates",
                reply.path("result").path("note").asText());
        assertEquals("40,-10,100x40", bounds(notes().get(0)));
    }

    @Test
    public void shouldStillPlaceAboveTheContent() {
        h.success("add-note-to-view", "viewId", "view-source", "content", "a", "width", 100, "height", 40,
                "position", "above-content");
        assertEquals("40,-10,100x40", bounds(notes().get(0)));
    }

    // ---- connectionRouterType ----

    @Test
    public void shouldRefuseARouterTypeItCannotRead_onBothPaths_withNothingRenamed() {
        List<String> wrong = new ArrayList<>();
        refusedOnBoth(wrong, "update-view", args("viewId", "view-source", "name", "Renamed",
                "connectionRouterType", 5),
                "connectionRouterType must be a string, got the integer 5. Nothing was changed.");
        refusedOnBoth(wrong, "update-view", args("viewId", "view-source", "name", "Renamed",
                "connectionRouterType", "  "),
                "connectionRouterType must not be blank, got the string '  '. Nothing was changed.");
        refusedOnBoth(wrong, "update-view", args("viewId", "view-source", "connectionRouterType", "  "),
                "connectionRouterType must not be blank, got the string '  '. Nothing was changed.");
        assertEquals(List.of(), wrong);
        assertEquals("Source", h.f.view.getName());
        assertEquals("Pass connectionRouterType as a non-blank string, \"\" to clear it, or omit it.",
                h.errorOf("update-view", args("viewId", "view-source", "connectionRouterType", 5))
                        .path("suggestedCorrection").asText());
    }

    private int views() {
        return h.f.model.getFolder(FolderType.DIAGRAMS).getElements().size();
    }

    private IDiagramModel createdView(JsonNode reply) {
        String id = reply.path("result").path("id").asText();
        return (IDiagramModel) h.f.model.getFolder(FolderType.DIAGRAMS).getElements().stream()
                .filter(v -> id.equals(((IIdentifier) v).getId())).findFirst().orElseThrow();
    }

    @Test
    public void shouldRefuseACreateViewRouterTypeItCannotRead_onBothPaths_withNoViewCreated() {
        List<String> wrong = new ArrayList<>();
        int before = views();
        refusedOnBoth(wrong, "create-view", args("name", "New", "connectionRouterType", " "),
                "connectionRouterType must not be blank, got the string ' '. Nothing was changed.");
        refusedOnBoth(wrong, "create-view", args("name", "New", "connectionRouterType", 5),
                "connectionRouterType must be a string, got the integer 5. Nothing was changed.");
        refusedOnBoth(wrong, "create-view", args("name", "New", "connectionRouterType", List.of("manhattan")),
                "connectionRouterType must be a string, got an array. Nothing was changed.");
        assertEquals(List.of(), wrong);
        assertEquals("no view created", before, views());
        assertEquals("Pass connectionRouterType as a non-blank string, \"\" to clear it, or omit it.",
                h.errorOf("create-view", args("name", "New", "connectionRouterType", 5))
                        .path("suggestedCorrection").asText());
    }

    @Test
    public void shouldStillCreateAViewWithEachRouterTypeThatReads_andWithNone() {
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, createdView(h.success("create-view",
                "name", "A", "connectionRouterType", "manhattan")).getConnectionRouterType());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, createdView(h.success("create-view",
                "name", "B", "connectionRouterType", "manual")).getConnectionRouterType());
        assertEquals("the empty string is the default, as on update-view", IDiagramModel.CONNECTION_ROUTER_BENDPOINT,
                createdView(h.success("create-view", "name", "C", "connectionRouterType", ""))
                        .getConnectionRouterType());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT,
                createdView(h.success("create-view", "name", "D")).getConnectionRouterType());
        int before = views();
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                op("create-view", args("name", "E", "connectionRouterType", "")),
                op("create-view", args("name", "F", "connectionRouterType", "manhattan")))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals(before + 2, views());
    }

    @Test
    public void shouldLeaveTheEmptyRouterTypeOffTheCreateViewProposal_andShowARealOne() {
        h.f.approval = true;
        h.success("create-view", "name", "A", "connectionRouterType", "");
        h.success("create-view", "name", "B", "connectionRouterType", "manhattan");
        List<PendingProposalView> pending = h.f.dispatcher.listAllPending();
        assertEquals(2, pending.size());
        assertFalse("\"\" means the default, so the card shows no router type: "
                + pending.get(0).proposal().proposedChanges(),
                pending.get(0).proposal().proposedChanges().containsKey("connectionRouterType"));
        assertEquals("manhattan", pending.get(1).proposal().proposedChanges().get("connectionRouterType"));
    }

    @Test
    public void shouldStillSetAndClearTheRouterType_throughTheTool() {
        h.success("update-view", "viewId", "view-source", "connectionRouterType", "manhattan");
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, h.f.view.getConnectionRouterType());
        h.success("update-view", "viewId", "view-source", "connectionRouterType", "");
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, h.f.view.getConnectionRouterType());
    }
}
