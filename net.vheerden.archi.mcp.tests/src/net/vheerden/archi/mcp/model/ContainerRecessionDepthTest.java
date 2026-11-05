package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.fasterxml.jackson.databind.JsonNode;

import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins that the backdrop a container recedes to when it gains a nested child never equals the fill
 * of its parent or of one of its direct children, at any depth and in every arm; that the choice is
 * made when the add runs, so a fill authored earlier in the same batch or bulk call survives; that
 * undo restores exactly; and that the response reports the receded container and its new fill.
 *
 * <p>The rule checked is the one {@code assess-layout} applies for {@code containerFillEqualsChild}:
 * a stored, non-null fill equal (ignoring case) to a direct child's stored, non-null fill.</p>
 */
public class ContainerRecessionDepthTest {

    private static final String BACKDROP = "#F4F4F4";

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("recede-depth-model");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    @Test
    public void shouldRecedeTheFirstLevelToTheBackdrop_asBefore() {
        String p = group("P", null);
        group("C", p);
        assertEquals(BACKDROP, fill(p));
        assertEquals(List.of(), sameFillPairs());
    }

    @Test
    public void shouldNeverGiveAContainerItsParentsBackdrop_twoLevelsDown() {
        String p = group("P", null);
        String c = group("C", p);
        group("G", c);
        assertEquals(BACKDROP, fill(p));
        assertTrue(fill(c), fill(c) != null && !fill(c).equalsIgnoreCase(BACKDROP));
        assertEquals(List.of(), sameFillPairs());
    }

    @Test
    public void shouldNeverGiveAContainerItsParentsBackdrop_atAnyDepth() {
        String parent = null;
        for (int depth = 0; depth < 5; depth++) {
            parent = group("L" + depth, parent);
        }
        h.call("add-to-view", args("viewId", "view-source", "elementId", "do-1", "parentViewObjectId", parent,
                "x", 10, "y", 30));
        assertEquals(List.of(), sameFillPairs());
    }

    @Test
    public void shouldAvoidAChildsFill_whenTheParentRecedesAfterTheChildDid() {
        String p = group("P", null);
        String c = group("C", p, "recede", false);
        group("G", c);
        assertEquals(BACKDROP, fill(c));
        assertNull(fill(p));
        group("D", p);
        assertTrue(fill(p), fill(p) != null && !fill(p).equalsIgnoreCase(BACKDROP));
        assertEquals(List.of(), sameFillPairs());
    }

    @Test
    public void shouldNeverGiveAContainerItsParentsBackdrop_insideBulkMutate() {
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                op("add-group-to-view", args("viewId", "view-source", "label", "P", "x", 600, "y", 300,
                        "width", 600, "height", 400)),
                op("add-group-to-view", args("viewId", "view-source", "label", "C", "x", 10, "y", 30,
                        "width", 400, "height", 300, "parentViewObjectId", "$0.id")),
                op("add-group-to-view", args("viewId", "view-source", "label", "G", "x", 10, "y", 30,
                        "width", 200, "height", 150, "parentViewObjectId", "$1.id")))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals(List.of(), sameFillPairs());
    }

    @Test
    public void shouldKeepAFillAuthoredEarlierInTheSameBatch() {
        String p = group("P", null);
        assertFalse(h.call("begin-batch", args("modelId", "recede-depth-model")).path("isError").asBoolean());
        JsonNode update = h.call("update-view-object", args("viewObjectId", p, "fillColor", "#123456"));
        assertFalse(update.toString(), update.path("isError").asBoolean());
        JsonNode added = h.call("add-group-to-view", args("viewId", "view-source", "width", 200, "height", 120, "label", "C", "x", 10, "y", 30,
                "parentViewObjectId", p));
        assertFalse(added.toString(), added.path("isError").asBoolean());
        assertFalse(h.call("end-batch", args()).path("isError").asBoolean());
        assertEquals("#123456", fill(p));
    }

    @Test
    public void shouldKeepAFillAuthoredEarlierInTheSameBulkCall() {
        String p = group("P", null);
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                op("update-view-object", args("viewObjectId", p, "fillColor", "#123456")),
                op("add-group-to-view", args("viewId", "view-source", "width", 200, "height", 120, "label", "C", "x", 10, "y", 30,
                        "parentViewObjectId", p)))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals("#123456", fill(p));
    }

    @Test
    public void shouldRestoreEachLevelExactly_onUndo() {
        String p = group("P", null);
        String c = group("C", p);
        group("G", c);
        String cFill = fill(c);
        h.f.stack.undo();
        assertNull(fill(c));
        assertEquals(BACKDROP, fill(p));
        h.f.stack.undo();
        assertNull(fill(p));
        h.f.stack.redo();
        h.f.stack.redo();
        assertEquals(BACKDROP, fill(p));
        assertEquals(cFill, fill(c));
    }

    @Test
    public void shouldLeaveTheParentAlone_withRecedeFalse() {
        String p = group("P", null);
        group("C", p, "recede", false);
        assertNull(fill(p));
    }

    // ---- the response ---------------------------------------------------------------------------

    @Test
    public void shouldReportTheRecededParentAndItsFill_onBothTools() {
        String p = group("P", null);
        JsonNode added = h.call("add-group-to-view", args("viewId", "view-source", "label", "C", "x", 10, "y", 30,
                "width", 300, "height", 200, "parentViewObjectId", p));
        assertEquals(receded(p, BACKDROP), added.path("result").path("recededParent").toString());
        String c = added.path("result").path("viewObjectId").asText();

        JsonNode placed = h.call("add-to-view", args("viewId", "view-source", "elementId", "do-1",
                "parentViewObjectId", c, "x", 10, "y", 30));
        assertEquals(receded(c, fill(c)), placed.path("result").path("recededParent").toString());
        assertFalse(fill(c).equalsIgnoreCase(BACKDROP));
    }

    @Test
    public void shouldReportTheRecededParent_onTheBulkEntry() {
        String p = group("P", null);
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(
                op("add-to-view", args("viewId", "view-source", "elementId", "do-1", "parentViewObjectId", p,
                        "x", 10, "y", 30, "width", 120, "height", 55)),
                op("add-to-view", args("viewId", "view-source", "elementId", "do-1", "parentViewObjectId", p,
                        "x", 150, "y", 30, "width", 120, "height", 55)))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        JsonNode ops = reply.path("result").path("operations");
        assertEquals(reply.toString(), receded(p, BACKDROP), ops.path(0).path("recededParent").toString());
        assertFalse("the second placement found the parent receded already: " + reply,
                ops.path(1).has("recededParent"));
    }

    @Test
    public void shouldSayNothingOfARecede_whenNoneHappened() {
        String p = group("P", null);
        group("C", p);
        JsonNode second = h.call("add-group-to-view", args("viewId", "view-source", "width", 200, "height", 120, "label", "D", "x", 200,
                "y", 30, "parentViewObjectId", p));
        assertFalse(second.toString(), second.path("result").has("recededParent"));
        JsonNode top = h.call("add-group-to-view", args("viewId", "view-source", "width", 200, "height", 120, "label", "T"));
        assertFalse(top.toString(), top.path("result").has("recededParent"));
    }

    /**
     * The recede is decided when it runs, so a queued placement cannot say what it will write: two
     * placements into one unauthored container would each forecast a recede, and only the first
     * makes one. A batched response therefore names no receded container, under preview or not.
     */
    @Test
    public void shouldNameNoRecede_whenTheAddIsQueuedInABatch() {
        String p = group("P", null);
        assertFalse(h.call("begin-batch", args("modelId", "recede-depth-model")).path("isError").asBoolean());
        for (String label : List.of("C", "D")) {
            JsonNode added = h.call("add-group-to-view", args("viewId", "view-source", "width", 100, "height", 60, "label", label,
                    "x", 10, "y", 30, "parentViewObjectId", p));
            assertFalse(added.toString(), added.path("isError").asBoolean());
            assertFalse(added.toString(), added.path("result").has("recededParent"));
            assertFalse(added.toString(), added.path("result").path("preview").has("recededParent"));
        }
        assertFalse(h.call("end-batch", args()).path("isError").asBoolean());
        assertEquals(BACKDROP, fill(p));
    }

    @Test
    public void shouldNameNoRecede_whenTheAddAwaitsApproval() {
        String p = group("P", null);
        h.f.approval = true;
        JsonNode added = h.call("add-group-to-view", args("viewId", "view-source", "width", 100, "height", 60, "label", "C",
                "x", 10, "y", 30, "parentViewObjectId", p));
        assertTrue(added.toString(), added.path("result").has("proposal"));
        assertFalse(added.toString(), added.toString().contains("recededParent"));
        String id = h.f.dispatcher.getPendingProposals(SessionManager.DEFAULT_SESSION_ID).get(0).proposalId();
        h.f.dispatcher.approveProposal(SessionManager.DEFAULT_SESSION_ID, id);
        assertEquals(BACKDROP, fill(p));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private String group(String label, String parent, Object... extra) {
        // explicit sizes: a group sized to its label would measure text, which needs a display
        Map<String, Object> a = args("viewId", "view-source", "label", label, "width", 300, "height", 200);
        if (parent != null) {
            a.putAll(args("parentViewObjectId", parent, "x", 10, "y", 30));
        } else {
            a.putAll(args("x", 600, "y", 300));
        }
        a.putAll(args(extra));
        JsonNode reply = h.call("add-group-to-view", a);
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        return reply.path("result").path("viewObjectId").asText();
    }

    private String fill(String id) {
        return ((IDiagramModelObject) ArchimateModelUtils.getObjectByID(h.f.model, id)).getFillColor();
    }

    private static String receded(String id, String fill) {
        return "{\"viewObjectId\":\"" + id + "\",\"fillColor\":\"" + fill + "\"}";
    }

    /** Every parent and direct child that hold the same stored fill: the assessor's finding. */
    private List<String> sameFillPairs() {
        List<String> pairs = new ArrayList<>();
        h.f.view.eAllContents().forEachRemaining(o -> {
            if (o instanceof IDiagramModelObject parent && o instanceof IDiagramModelContainer container
                    && parent.getFillColor() != null) {
                for (IDiagramModelObject child : container.getChildren()) {
                    if (parent.getFillColor().equalsIgnoreCase(child.getFillColor())) {
                        pairs.add(parent.getName() + "/" + child.getName() + " " + parent.getFillColor());
                    }
                }
            }
        });
        return pairs;
    }

    private static Map<String, Object> op(String tool, Map<String, Object> params) {
        return Map.of("tool", tool, "params", params);
    }
}
