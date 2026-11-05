package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IJunction;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.fasterxml.jackson.databind.JsonNode;

import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins that {@code add-to-view} places a junction at the size Archi's palette gives one, 15x15, in
 * every arm that places it: immediately, with {@code autoSize}, in a batch, as a proposal, inside
 * {@code bulk-mutate} and nested in a group. A side the caller gives wins on its own axis; any
 * other element keeps the 120x55 box.
 */
public class JunctionPlacementSizeTest {

    private EchoToolHarness h;
    private IJunction junction;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("junction-size-model");
        junction = h.f.factory.createJunction();
        junction.setId("jn-1");
        h.f.model.getFolder(FolderType.OTHER).getElements().add(junction);
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    @Test
    public void shouldPlaceAJunctionAtThePaletteSize_whenNoSizeIsGiven() {
        assertPlaced(add(), 15, 15);
    }

    @Test
    public void shouldPlaceAJunctionAtThePaletteSize_withAutoSize() {
        assertPlaced(add("autoSize", true), 15, 15);
    }

    @Test
    public void shouldLetAGivenSideWin_onItsOwnAxisOnly() {
        assertPlaced(add("width", 30), 30, 15);
        assertPlaced(add("height", 40), 15, 40);
        assertPlaced(add("width", 40, "height", 40), 40, 40);
    }

    @Test
    public void shouldKeepTheGenericBox_forAnyOtherElement() {
        JsonNode reply = h.call("add-to-view", args("viewId", "view-source", "elementId", "do-1"));
        assertPlaced(reply, 120, 55);
        reply = h.call("add-to-view", args("viewId", "view-source", "elementId", "do-1", "autoSize", true));
        assertPlaced(reply, 120, 55);
    }

    @Test
    public void shouldPlaceAJunctionAtThePaletteSize_nestedInAGroup() {
        IDiagramModelGroup group = h.f.group(h.f.view, "g-1", 600, 300, 200, 150);
        JsonNode reply = add("parentViewObjectId", "g-1", "x", 20, "y", 40);
        assertPlaced(reply, 15, 15);
        assertEquals(200, group.getBounds().getWidth());
        assertEquals(150, group.getBounds().getHeight());
    }

    @Test
    public void shouldPreviewAndCommitThePaletteSize_inABatch() {
        assertFalse(h.call("begin-batch", args("modelId", "junction-size-model")).path("isError").asBoolean());
        JsonNode preview = add("autoSize", true).path("result").path("preview").path("viewObject");
        assertEquals(15, preview.path("width").asInt());
        assertEquals(15, preview.path("height").asInt());
        assertFalse(h.call("end-batch", args()).path("isError").asBoolean());
        assertStored(15, 15);
    }

    @Test
    public void shouldProposeAndApplyThePaletteSize_underApproval() {
        h.f.approval = true;
        JsonNode reply = add();
        assertTrue(reply.toString(), reply.path("result").has("proposal"));
        JsonNode proposed = reply.path("result").path("preview").path("viewObject");
        assertEquals(15, proposed.path("width").asInt());
        assertEquals(15, proposed.path("height").asInt());
        String id = h.f.dispatcher.getPendingProposals(SessionManager.DEFAULT_SESSION_ID).get(0).proposalId();
        h.f.dispatcher.approveProposal(SessionManager.DEFAULT_SESSION_ID, id);
        assertStored(15, 15);
    }

    @Test
    public void shouldPlaceAJunctionAtThePaletteSize_insideBulkMutate() {
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                Map.of("tool", "add-to-view", "params", args("viewId", "view-source", "elementId", "jn-1")),
                Map.of("tool", "add-to-view", "params",
                        args("viewId", "view-source", "elementId", "jn-1", "autoSize", true, "x", 300, "y", 300)))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        List<IDiagramModelArchimateObject> placed = junctionBoxes();
        assertEquals(2, placed.size());
        for (IDiagramModelArchimateObject box : placed) {
            assertEquals(15, box.getBounds().getWidth());
            assertEquals(15, box.getBounds().getHeight());
        }
    }

    // ---- sized to fit -------------------------------------------------------------------------

    @Test
    public void shouldKeepAJunctionsSize_whenElementsAreResizedToFit() {
        // On the second view the junction is the only box, so no label is measured (no display here).
        List<String> wrong = new java.util.ArrayList<>();
        for (boolean wrapFit : new boolean[] {false, true}) {
            IDiagramModelArchimateObject box = h.f.object(h.f.other, "vo-jn-" + wrapFit, junction, 700, 40, 15, 15);
            JsonNode reply = h.call("resize-elements-to-fit", args("viewId", "view-other", "wrapFit", wrapFit));
            String got = reply.path("isError").asBoolean() ? reply.toString()
                    : box.getBounds().getWidth() + "x" + box.getBounds().getHeight()
                            + (reply.toString().contains("vo-jn-") ? " reported as resized" : "");
            if (!got.equals("15x15")) {
                wrong.add("wrapFit " + wrapFit + ": " + got);
            }
            h.f.other.getChildren().remove(box);
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void shouldKeepAJunctionsWidth_whenAGroupIsLaidOutWithAutoWidth() {
        List<String> wrong = new java.util.ArrayList<>();
        for (boolean recursive : new boolean[] {false, true}) {
            IDiagramModelGroup group = h.f.group(h.f.view, "g-" + recursive, 40, 300, 400, 200);
            IDiagramModelArchimateObject box = h.f.object(group, "vo-jn-" + recursive, junction, 10, 40, 15, 15);
            IDiagramModelArchimateObject data = h.f.object(group, "vo-do-" + recursive,
                    (com.archimatetool.model.IArchimateElement) ArchimateModelUtils.getObjectByID(h.f.model, "do-1"),
                    60, 40, 40, 40);
            JsonNode reply = h.call("layout-within-group", args("viewId", "view-source", "groupViewObjectId",
                    "g-" + recursive, "arrangement", "row", "autoWidth", true, "recursive", recursive));
            String got = reply.path("isError").asBoolean() ? reply.toString()
                    : box.getBounds().getWidth() + "x" + box.getBounds().getHeight() + " beside "
                            + data.getBounds().getWidth();
            // the element beside it still takes its label width
            String want = "15x15 beside " + GroupLayoutCalculator.computeAutoWidth("do-1");
            if (!got.equals(want)) {
                wrong.add("recursive " + recursive + ": " + got);
            }
        }
        assertEquals(List.of(), wrong);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private JsonNode add(Object... extra) {
        Map<String, Object> a = args("viewId", "view-source", "elementId", "jn-1");
        a.putAll(args(extra));
        return h.call("add-to-view", a);
    }

    private void assertPlaced(JsonNode reply, int width, int height) {
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        JsonNode vo = reply.path("result").path("viewObject");
        assertEquals(reply.toString(), width, vo.path("width").asInt());
        assertEquals(reply.toString(), height, vo.path("height").asInt());
        IBounds stored = find(vo.path("viewObjectId").asText()).getBounds();
        assertEquals(width, stored.getWidth());
        assertEquals(height, stored.getHeight());
    }

    private void assertStored(int width, int height) {
        List<IDiagramModelArchimateObject> placed = junctionBoxes();
        assertEquals(1, placed.size());
        assertEquals(width, placed.get(0).getBounds().getWidth());
        assertEquals(height, placed.get(0).getBounds().getHeight());
    }

    private IDiagramModelObject find(String id) {
        return junctionBoxes().stream().filter(b -> b.getId().equals(id)).findFirst()
                .map(IDiagramModelObject.class::cast)
                .orElseGet(() -> {
                    for (IDiagramModelObject o : h.f.view.getChildren()) {
                        if (o.getId().equals(id)) {
                            return o;
                        }
                    }
                    throw new AssertionError("no view object " + id);
                });
    }

    private List<IDiagramModelArchimateObject> junctionBoxes() {
        List<IDiagramModelArchimateObject> found = new java.util.ArrayList<>();
        h.f.view.eAllContents().forEachRemaining(o -> {
            if (o instanceof IDiagramModelArchimateObject box && box.getArchimateElement() == junction) {
                found.add(box);
            }
        });
        return found;
    }
}
