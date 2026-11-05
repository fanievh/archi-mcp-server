package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.fasterxml.jackson.databind.JsonNode;

import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins that {@code add-to-view} with {@code autoConnect} draws what Archi's drop draws: the new
 * object is connected to every occurrence of a related element already on the view, not only the
 * first, outgoing relationships first and then incoming ones. A relationship from an element to
 * itself is both, so it is drawn once each way to each occurrence, which is also what Archi does.
 * The 50-connection cap counts every one of them.
 */
public class AddToViewAutoConnectOccurrencesTest {

    private EchoToolHarness h;
    private IArchimateElement a;
    private IArchimateElement b;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("auto-connect-occurrences-model");
        a = h.f.element("occ-a");
        b = h.f.element("occ-b");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    @Test
    public void shouldConnectToEveryOccurrenceOfTheRelatedElement() {
        h.f.relationship("occ-ab", a, b);
        h.f.object(h.f.view, "b1", b, 300, 40, 120, 55);
        h.f.object(h.f.view, "b2", b, 300, 240, 120, 55);
        JsonNode reply = placeA();
        String placed = placedId(reply);
        assertEquals(List.of(placed + "->b1", placed + "->b2"), rows(reply));
        assertEquals(sorted(rows(reply)), sorted(drawn()));
    }

    @Test
    public void shouldDrawASelfRelationshipBothWaysToEveryOccurrence() {
        h.f.relationship("occ-aa", a, a);
        h.f.object(h.f.view, "a1", a, 40, 40, 120, 55);
        h.f.object(h.f.view, "a2", a, 40, 240, 120, 55);
        JsonNode reply = placeA();
        String placed = placedId(reply);
        assertEquals(List.of(placed + "->a1", placed + "->a2", "a1->" + placed, "a2->" + placed), rows(reply));
        assertEquals(sorted(rows(reply)), sorted(drawn()));
    }

    @Test
    public void shouldDrawASelfRelationshipBothWays_withOneOccurrence() {
        h.f.relationship("occ-aa", a, a);
        h.f.object(h.f.view, "a1", a, 40, 40, 120, 55);
        JsonNode reply = placeA();
        String placed = placedId(reply);
        assertEquals(List.of(placed + "->a1", "a1->" + placed), rows(reply));
    }

    @Test
    public void shouldConnectToEveryOccurrence_inABatch() {
        h.f.relationship("occ-ab", a, b);
        h.f.object(h.f.view, "b1", b, 300, 40, 120, 55);
        h.f.object(h.f.view, "b2", b, 300, 240, 120, 55);
        assertFalse(h.call("begin-batch", args("modelId", "auto-connect-occurrences-model")).path("isError").asBoolean());
        JsonNode reply = placeA();
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        assertFalse(h.call("end-batch", args()).path("isError").asBoolean());
        assertEquals(drawn().toString(), 2, drawn().size());
        assertTrue(drawn().toString(), drawn().get(0).endsWith("->b1") && drawn().get(1).endsWith("->b2"));
    }

    @Test
    public void shouldConnectToEveryOccurrence_underApproval() {
        h.f.relationship("occ-ab", a, b);
        h.f.object(h.f.view, "b1", b, 300, 40, 120, 55);
        h.f.object(h.f.view, "b2", b, 300, 240, 120, 55);
        h.f.approval = true;
        JsonNode reply = placeA();
        assertTrue(reply.toString(), reply.path("result").has("proposal"));
        String id = h.f.dispatcher.getPendingProposals(SessionManager.DEFAULT_SESSION_ID).get(0).proposalId();
        h.f.dispatcher.approveProposal(SessionManager.DEFAULT_SESSION_ID, id);
        assertEquals(drawn().toString(), 2, drawn().size());
        assertTrue(drawn().toString(), drawn().get(0).endsWith("->b1") && drawn().get(1).endsWith("->b2"));
    }

    @Test
    public void shouldCountEveryOccurrenceAgainstTheCap() {
        h.f.relationship("occ-ab", a, b);
        for (int i = 0; i < 52; i++) {
            h.f.object(h.f.view, "b" + i, b, 300 + (i % 10) * 130, 40 + (i / 10) * 80, 120, 55);
        }
        JsonNode reply = placeA();
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        JsonNode result = reply.path("result");
        assertEquals(50, result.path("autoConnections").size());
        assertEquals(2, result.path("skippedAutoConnections").asInt());
        List<String> capped = new ArrayList<>();
        result.path("skippedByCap").forEach(s -> capped.add(s.path("targetViewObjectId").asText()));
        assertEquals(List.of("b50", "b51"), capped);
        assertEquals(50, drawn().size());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private JsonNode placeA() {
        Map<String, Object> a = args("viewId", "view-source", "elementId", "occ-a", "x", 40, "y", 500,
                "width", 120, "height", 55, "autoConnect", true);
        JsonNode reply = h.call("add-to-view", a);
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        return reply;
    }

    private static String placedId(JsonNode reply) {
        return reply.path("result").path("viewObject").path("viewObjectId").asText();
    }

    private static List<String> sorted(List<String> rows) {
        return rows.stream().sorted().toList();
    }

    /** The connections the response reports, as source->target view-object ids. */
    private static List<String> rows(JsonNode reply) {
        List<String> rows = new ArrayList<>();
        reply.path("result").path("autoConnections").forEach(c -> rows.add(
                c.path("sourceViewObjectId").asText() + "->" + c.path("targetViewObjectId").asText()));
        return rows;
    }

    /** This test's connections on the view, as source->target view-object ids, in the order they were added. */
    private List<String> drawn() {
        List<String> found = new ArrayList<>();
        h.f.view.eAllContents().forEachRemaining(o -> {
            if (o instanceof IDiagramModelArchimateObject box) {
                for (Object c : box.getSourceConnections()) {
                    IDiagramModelArchimateConnection conn = (IDiagramModelArchimateConnection) c;
                    if (!conn.getArchimateRelationship().getId().startsWith("occ-")) {
                        continue; // the harness's own connection
                    }
                    found.add(conn.getSource().getId() + "->" + conn.getTarget().getId());
                }
            }
        });
        return found;
    }
}
