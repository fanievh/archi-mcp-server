package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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

/**
 * Pins that {@code auto-connect-view} connects every pair of occurrences a relationship joins, as
 * Archi does when a relationship is dragged onto a view, skipping only a pair the same
 * relationship already runs between in that direction. A relationship drawn between one pair no
 * longer stops it being drawn between the others.
 *
 * <p>One departure from Archi is kept on purpose: Archi never draws a relationship from an object
 * to itself, while this tool gives each occurrence of an element with a relationship to itself a
 * loop, if it has none. Between two occurrences of that element it draws both directions, as
 * Archi does.</p>
 */
public class AutoConnectViewOccurrencesTest {

    private EchoToolHarness h;
    private IArchimateElement a;
    private IArchimateElement b;
    private IArchimateRelationship ab;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("auto-connect-view-occurrences-model");
        a = h.f.element("occ-a");
        b = h.f.element("occ-b");
        ab = h.f.relationship("occ-ab", a, b);
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    @Test
    public void shouldConnectEveryOccurrence_ofATargetShownTwice() {
        IDiagramModelArchimateObject a1 = h.f.object(h.f.view, "a1", a, 40, 40, 120, 55);
        h.f.object(h.f.view, "b1", b, 300, 40, 120, 55);
        h.f.object(h.f.view, "b2", b, 300, 240, 120, 55);
        JsonNode result = connectAll();
        assertEquals(2, result.path("connectionsCreated").asInt());
        assertEquals(0, result.path("connectionsSkipped").asInt());
        assertEquals("[\"occ-ab\",\"occ-ab\"]", result.path("relationshipIdsConnected").toString());
        assertEquals(List.of("a1->b1", "a1->b2"), from(a1));
    }

    @Test
    public void shouldConnectOnlyThePairsNotJoinedAlready() {
        IDiagramModelArchimateObject a1 = h.f.object(h.f.view, "a1", a, 40, 40, 120, 55);
        IDiagramModelArchimateObject b1 = h.f.object(h.f.view, "b1", b, 300, 40, 120, 55);
        h.f.object(h.f.view, "b2", b, 300, 240, 120, 55);
        h.f.archimateConnection("drawn", ab, a1, b1);
        JsonNode result = connectAll();
        assertEquals(1, result.path("connectionsCreated").asInt());
        assertEquals(1, result.path("connectionsSkipped").asInt());
        assertEquals(List.of("a1->b1", "a1->b2"), from(a1));
    }

    /**
     * Archi draws a relationship only from an occurrence of its source to one of its target; a
     * connection the other way round would turn the relationship itself round in the model. Every
     * pair is drawn that way, and the relationship is left as it was.
     */
    @Test
    public void shouldDrawEveryPairTheRelationshipsWay_andLeaveTheRelationshipAlone() {
        h.f.object(h.f.view, "a1", a, 40, 40, 120, 55);
        h.f.object(h.f.view, "a2", a, 40, 240, 120, 55);
        IDiagramModelArchimateObject b1 = h.f.object(h.f.view, "b1", b, 300, 40, 120, 55);
        IDiagramModelArchimateObject b2 = h.f.object(h.f.view, "b2", b, 300, 240, 120, 55);
        assertEquals(4, connectAll().path("connectionsCreated").asInt());
        assertEquals(List.of(), from(b1));
        assertEquals(List.of(), from(b2));
        assertEquals("occ-a->occ-b", ab.getSource().getId() + "->" + ab.getTarget().getId());
    }

    @Test
    public void shouldLoopEachOccurrence_andJoinTwoOccurrencesBothWays_forASelfRelationship() {
        h.f.relationship("occ-aa", a, a);
        IDiagramModelArchimateObject a1 = h.f.object(h.f.view, "a1", a, 40, 40, 120, 55);
        IDiagramModelArchimateObject a2 = h.f.object(h.f.view, "a2", a, 40, 240, 120, 55);
        JsonNode result = connectAll();
        assertEquals(4, result.path("connectionsCreated").asInt());
        assertEquals(List.of("a1->a1", "a1->a2"), from(a1));
        assertEquals(List.of("a2->a1", "a2->a2"), from(a2));
        for (IDiagramModelArchimateObject loopOn : List.of(a1, a2)) {
            IDiagramModelArchimateConnection loop = (IDiagramModelArchimateConnection) loopOn.getSourceConnections()
                    .stream().filter(c -> c.getTarget() == loopOn).findFirst().orElseThrow();
            assertEquals(3, loop.getBendpoints().size());
        }
    }

    @Test
    public void shouldNotLoopAnOccurrenceThatHasItsLoopAlready() {
        IArchimateRelationship aa = h.f.relationship("occ-aa", a, a);
        IDiagramModelArchimateObject a1 = h.f.object(h.f.view, "a1", a, 40, 40, 120, 55);
        IDiagramModelArchimateObject a2 = h.f.object(h.f.view, "a2", a, 40, 240, 120, 55);
        h.f.archimateConnection("loop", aa, a1, a1);
        JsonNode result = connectAll();
        assertEquals(3, result.path("connectionsCreated").asInt());
        assertEquals(1, result.path("connectionsSkipped").asInt());
        assertEquals(List.of("a1->a1", "a1->a2"), from(a1));
        assertEquals(List.of("a2->a1", "a2->a2"), from(a2));
    }

    @Test
    public void shouldDrawNothingNew_whenEveryPairIsJoined() {
        IDiagramModelArchimateObject a1 = h.f.object(h.f.view, "a1", a, 40, 40, 120, 55);
        IDiagramModelArchimateObject b1 = h.f.object(h.f.view, "b1", b, 300, 40, 120, 55);
        IDiagramModelArchimateObject b2 = h.f.object(h.f.view, "b2", b, 300, 240, 120, 55);
        h.f.archimateConnection("one", ab, a1, b1);
        h.f.archimateConnection("two", ab, a1, b2);
        JsonNode result = connectAll();
        assertEquals(0, result.path("connectionsCreated").asInt());
        assertEquals(2, result.path("connectionsSkipped").asInt());
    }

    /**
     * Each new connection declines if either of its ends left the view before it runs, whichever
     * end the scan reached it from: a removal queued earlier in the batch takes the source here.
     */
    @Test
    public void shouldDrawNothingToAnEndRemovedEarlierInTheBatch_whicheverEndItIs() {
        h.f.object(h.f.view, "a1", a, 40, 40, 120, 55);
        IDiagramModelArchimateObject b1 = h.f.object(h.f.view, "b1", b, 300, 40, 120, 55);
        assertFalse(h.call("begin-batch", args("modelId", "auto-connect-view-occurrences-model")).path("isError").asBoolean());
        assertFalse(h.call("remove-from-view", args("viewId", "view-source", "viewObjectId", "a1"))
                .path("isError").asBoolean());
        connectAll();
        h.call("end-batch", args());
        assertEquals(List.of(), b1.getTargetConnections().stream()
                .map(c -> ((IDiagramModelArchimateConnection) c).getSource().getId()).toList());
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Every relationship of this test's elements; the harness's own drawn connection is left out. */
    private JsonNode connectAll() {
        JsonNode reply = h.call("auto-connect-view", args("viewId", "view-source", "elementIds", List.of("occ-a")));
        assertFalse(reply.toString(), reply.path("isError").asBoolean());
        return reply.path("result");
    }

    /** The connections leaving {@code object}, as source->target ids, sorted. */
    private static List<String> from(IDiagramModelArchimateObject object) {
        List<String> rows = new ArrayList<>();
        for (Object c : object.getSourceConnections()) {
            IDiagramModelArchimateConnection conn = (IDiagramModelArchimateConnection) c;
            rows.add(conn.getSource().getId() + "->" + conn.getTarget().getId());
        }
        return rows.stream().sorted().toList();
    }
}
