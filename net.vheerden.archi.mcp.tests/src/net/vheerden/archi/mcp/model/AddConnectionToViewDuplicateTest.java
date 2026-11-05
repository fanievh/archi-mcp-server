package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.fasterxml.jackson.databind.JsonNode;

import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins that {@code add-connection-to-view} refuses a connection only when the same one already
 * joins the same two objects: the same relationship, from this source to this target. That is the
 * test Archi applies before it draws a relationship between two objects. A connection for the
 * relationship elsewhere on the view is no reason to refuse. A connection asked for from the
 * relationship's target is drawn the relationship's way, with the route and label the caller gave:
 * connected as asked, Archi would turn the relationship itself round in the model.
 */
public class AddConnectionToViewDuplicateTest {

    private EchoToolHarness h;
    private IArchimateRelationship rel;
    private IDiagramModelArchimateObject a1;
    private IDiagramModelArchimateObject b1;
    private IDiagramModelArchimateObject b2;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("duplicate-connection-model");
        IArchimateElement a = h.f.element("occ-a");
        IArchimateElement b = h.f.element("occ-b");
        rel = h.f.relationship("occ-ab", a, b);
        a1 = h.f.object(h.f.view, "a1", a, 40, 40, 120, 55);
        b1 = h.f.object(h.f.view, "b1", b, 300, 40, 120, 55);
        b2 = h.f.object(h.f.view, "b2", b, 300, 240, 120, 55);
        h.f.archimateConnection("a1-b1", rel, a1, b1);
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    @Test
    public void shouldConnectAnotherOccurrence_whenTheRelationshipIsDrawnBetweenOthers() {
        JsonNode reply = connect("a1", "b2");
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals(reply.toString(), List.of("a1-b1", "a1->b2"), drawn());
    }

    @Test
    public void shouldRefuseTheOtherWayRound_whenTheRelationshipsWayIsDrawn_andLeaveTheRelationshipAlone() {
        JsonNode reply = connect("b1", "a1");
        assertTrue(reply.toString(), reply.toString().contains("CONNECTION_ALREADY_ON_VIEW"));
        assertEquals(List.of("a1-b1"), drawn());
        assertEquals("occ-a->occ-b", ends());
    }

    @Test
    public void shouldDrawTheRelationshipsWay_whenAskedFromItsTarget() {
        JsonNode reply = connect("b2", "a1");
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals(List.of("a1-b1", "a1->b2"), drawn());
        assertEquals("occ-a->occ-b", ends());
        JsonNode result = reply.path("result");
        assertEquals("a1", result.path("sourceViewObjectId").asText());
        assertEquals("b2", result.path("targetViewObjectId").asText());
        assertTrue(reply.toString(), reply.path("nextSteps").get(0).asText().startsWith("Drawn from 'a1' to 'b2'"));
    }

    @Test
    public void shouldKeepTheRouteAndLabelTheCallerGave_whenTheEndsAreExchanged() {
        JsonNode reply = h.call("add-connection-to-view", args("viewId", "view-source", "relationshipId", "occ-ab",
                "sourceViewObjectId", "b2", "targetViewObjectId", "a1", "labelPosition", "source",
                "bendpoints", List.of(
                        java.util.Map.of("startX", 0, "startY", 60, "endX", 260, "endY", 260),
                        java.util.Map.of("startX", -260, "startY", 60, "endX", 0, "endY", 260))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        IDiagramModelArchimateConnection conn = (IDiagramModelArchimateConnection) a1.getSourceConnections().get(1);
        assertEquals(b2, conn.getTarget());
        List<String> route = new ArrayList<>();
        conn.getBendpoints().forEach(bp -> route.add(bp.getStartX() + "," + bp.getStartY() + "/" + bp.getEndX()
                + "," + bp.getEndY()));
        // the same two canvas points, met from a1's end first; a1 is 260 left of and 200 above b2
        assertEquals(List.of("0,260/-260,60", "260,260/0,60"), route);
        assertEquals("the label stays near b2, now the target", 2, conn.getTextPosition());
        assertEquals("occ-a->occ-b", ends());
    }

    @Test
    public void shouldDrawTheRelationshipsWay_whenAskedFromItsTarget_inABatch() {
        assertFalse(h.call("begin-batch", args("modelId", "duplicate-connection-model")).path("isError").asBoolean());
        JsonNode reply = connect("b2", "a1");
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertFalse(h.call("end-batch", args()).path("isError").asBoolean());
        assertEquals(List.of("a1-b1", "a1->b2"), drawn());
        assertEquals("occ-a->occ-b", ends());
    }

    @Test
    public void shouldDrawTheRelationshipsWay_whenAskedFromItsTarget_inABulkCall() {
        JsonNode reply = h.call("bulk-mutate", args("operations", List.of(java.util.Map.of(
                "tool", "add-connection-to-view", "params", java.util.Map.of("viewId", "view-source",
                        "relationshipId", "occ-ab", "sourceViewObjectId", "b2", "targetViewObjectId", "a1")))));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertEquals(List.of("a1-b1", "a1->b2"), drawn());
        assertEquals("occ-a->occ-b", ends());
    }

    @Test
    public void shouldStillRefuseTheSameConnectionBetweenTheSameTwoObjects() {
        JsonNode reply = connect("a1", "b1");
        assertTrue(reply.toString(), reply.path("isError").asBoolean());
        assertTrue(reply.toString(), reply.toString().contains("CONNECTION_ALREADY_ON_VIEW"));
        assertTrue(reply.toString(), reply.toString().contains(
                "A connection for relationship 'occ-ab' already exists between these view objects"));
        assertEquals(List.of("a1-b1"), drawn());
    }

    @Test
    public void shouldStillRefuseASecondLoop_forTheSameSelfRelationship() {
        IArchimateRelationship self = h.f.relationship("occ-aa", a1.getArchimateElement(), a1.getArchimateElement());
        h.f.archimateConnection("a1-a1", self, a1, a1);
        JsonNode reply = h.call("add-connection-to-view", args("viewId", "view-source", "relationshipId", "occ-aa",
                "sourceViewObjectId", "a1", "targetViewObjectId", "a1"));
        assertTrue(reply.toString(), reply.toString().contains("CONNECTION_ALREADY_ON_VIEW"));
    }

    @Test
    public void shouldConnectAnotherOccurrence_inABatch() {
        assertFalse(h.call("begin-batch", args("modelId", "duplicate-connection-model")).path("isError").asBoolean());
        JsonNode reply = connect("a1", "b2");
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertFalse(h.call("end-batch", args()).path("isError").asBoolean());
        assertEquals(List.of("a1-b1", "a1->b2"), drawn());
    }

    @Test
    public void shouldConnectAnotherOccurrence_underApproval() {
        h.f.approval = true;
        JsonNode reply = connect("a1", "b2");
        assertTrue(reply.toString(), reply.path("result").has("proposal"));
        String id = h.f.dispatcher.getPendingProposals(SessionManager.DEFAULT_SESSION_ID).get(0).proposalId();
        h.f.dispatcher.approveProposal(SessionManager.DEFAULT_SESSION_ID, id);
        assertEquals(List.of("a1-b1", "a1->b2"), drawn());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private JsonNode connect(String source, String target) {
        return h.call("add-connection-to-view", args("viewId", "view-source", "relationshipId", "occ-ab",
                "sourceViewObjectId", source, "targetViewObjectId", target));
    }

    private String ends() {
        return rel.getSource().getId() + "->" + rel.getTarget().getId();
    }

    /** Every connection for the relationship: the fixture's by id, any new one as source->target. */
    private List<String> drawn() {
        List<String> found = new ArrayList<>();
        for (IDiagramModelArchimateObject box : List.of(a1, b1, b2)) {
            for (Object c : box.getSourceConnections()) {
                IDiagramModelArchimateConnection conn = (IDiagramModelArchimateConnection) c;
                if (conn.getArchimateRelationship() != null
                        && rel.getId().equals(conn.getArchimateRelationship().getId())) {
                    found.add(conn.getId().equals("a1-b1") ? "a1-b1"
                            : conn.getSource().getId() + "->" + conn.getTarget().getId());
                }
            }
        }
        return found;
    }
}
