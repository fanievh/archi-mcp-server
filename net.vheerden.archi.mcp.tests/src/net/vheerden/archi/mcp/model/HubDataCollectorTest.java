package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IApplicationComponent;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IGrouping;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * The fan-out sizing precondition, and the connection count it publishes.
 *
 * <p>An agent reads a per-element connection count from {@code detect-hub-elements} and from the
 * precondition block in consecutive calls. Both are produced by {@link HubDataCollector#collect},
 * so the pins here fix the counting RULE — a self-loop counts twice, a plain diagram connection
 * counts not at all — which is what the two would otherwise drift on.</p>
 */
public class HubDataCollectorTest {

    private static final int KAFKA_CONNECTIONS = 13;

    // ---- The floor, not the growth suggestion ----

    @Test
    public void unmetHubPreconditions_shouldReportNothing_whenTheHubAlreadyClearsBothFloors() {
        // The measured case: 13 connections at 370x603. The sizing SUGGESTION grows off the
        // element's current size and would ask this hub to grow again the moment it was sized;
        // the floor it is judged against here is 360x298, which it already clears.
        Fixture f = hubWithSpokes(KAFKA_CONNECTIONS, 370, 603);

        assertTrue("a hub at or above both floors is met and must not be reported: "
                        + HubDataCollector.unmetHubPreconditions(f.view),
                HubDataCollector.unmetHubPreconditions(f.view).isEmpty());
    }

    @Test
    public void unmetHubPreconditions_shouldReportTheRequiredFloor_whenTheHubIsShortOnBothAxes() {
        Fixture f = hubWithSpokes(KAFKA_CONNECTIONS, 240, 100);

        List<AssessLayoutResultDto.HubPreconditionDto> unmet =
                HubDataCollector.unmetHubPreconditions(f.view);

        assertEquals("exactly the one undersized hub", 1, unmet.size());
        AssessLayoutResultDto.HubPreconditionDto row = unmet.get(0);
        assertEquals("hub", row.elementId());
        assertEquals("obj-hub", row.viewObjectId());
        assertEquals("Kafka", row.name());
        assertEquals(KAFKA_CONNECTIONS, row.connectionCount());
        assertEquals(240, row.currentWidth());
        assertEquals(100, row.currentHeight());
        assertEquals("the absolute floor for 13 connections, not a growth step off 240",
                360, row.requiredWidth());
        assertEquals(298, row.requiredHeight());
    }

    @Test
    public void unmetHubPreconditions_shouldReport_whenOnlyOneAxisIsShort() {
        // Width met, height short. An entry appears when EITHER axis is short — a hub wide
        // enough for its ports and too flat to space them is still unable to hold the fan-out.
        List<AssessLayoutResultDto.HubPreconditionDto> widthOnly =
                HubDataCollector.unmetHubPreconditions(hubWithSpokes(KAFKA_CONNECTIONS, 400, 100).view);
        assertEquals(1, widthOnly.size());
        assertEquals(400, widthOnly.get(0).currentWidth());

        List<AssessLayoutResultDto.HubPreconditionDto> heightOnly =
                HubDataCollector.unmetHubPreconditions(hubWithSpokes(KAFKA_CONNECTIONS, 240, 400).view);
        assertEquals(1, heightOnly.size());
        assertEquals(400, heightOnly.get(0).currentHeight());
    }

    // ---- The fan-out gate ----

    @Test
    public void unmetHubPreconditions_shouldRespectTheFanOutGateOnBothSides() {
        // At and below the gate an element is not a hub however small it is; one connection
        // above it, the same box is reported. The gate is exclusive, so 6 is silent and 7 is not.
        int gate = SpacingControlLoop.DENSITY_HUB_FANOUT_CONN_THRESHOLD;
        assertTrue("one below the gate is silent",
                HubDataCollector.unmetHubPreconditions(hubWithSpokes(gate - 1, 40, 20).view).isEmpty());
        assertTrue("exactly at the gate is still silent",
                HubDataCollector.unmetHubPreconditions(hubWithSpokes(gate, 40, 20).view).isEmpty());
        assertEquals("one above the gate is reported", 1,
                HubDataCollector.unmetHubPreconditions(hubWithSpokes(gate + 1, 40, 20).view).size());
    }

    // ---- Zero stored bendpoints: the case hub-port quality cannot reach ----

    @Test
    public void unmetHubPreconditions_shouldFire_onAViewWithNoStoredBendpoints() {
        // Every connection in the fixture is drawn straight between two centres — the state a
        // freshly placed, unrouted view is in. Hub-port quality is vacuously 1.0 there (no face
        // carries enough terminals to be judged), so the metric-driven remedy is structurally
        // unable to speak, and this block is the only thing that can.
        Fixture f = hubWithSpokes(KAFKA_CONNECTIONS, 240, 100);
        for (IDiagramModelConnection c : f.connections) {
            assertTrue("fixture must carry no stored route", c.getBendpoints().isEmpty());
        }

        assertEquals("an unrouted, undersized hub must still be reported", 1,
                HubDataCollector.unmetHubPreconditions(f.view).size());
    }

    // ---- The counting rule the published number carries ----

    @Test
    public void collect_shouldCountASelfLoopTwice() {
        // Both endpoint ids are incremented per connection, and a self-loop has both on the same
        // object. Stated because it is the first thing a second counter gets differently.
        Fixture f = hubWithSpokes(4, 240, 100);
        addArchimateConnection(f.model, f.hub, f.hub, "loop");

        Map<String, Integer> counts = new HashMap<>();
        HubDataCollector.collect(f.view, counts, new HashMap<>(), new HashMap<>());

        assertEquals("4 spokes plus a self-loop counted at both of its ends",
                Integer.valueOf(6), counts.get("obj-hub"));
    }

    @Test
    public void collect_shouldNotCountAPlainDiagramConnection() {
        // The line Archi lets you draw to a Note is not an ArchiMate connection. It is visible on
        // the canvas and contributes nothing here, so this count can be lower than the number of
        // lines touching the object — which is exactly what detect-hub-elements publishes too.
        Fixture f = hubWithSpokes(7, 240, 100);
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId("obj-note");
        note.setBounds(900, 900, 185, 80);
        f.view.getChildren().add(note);
        IDiagramModelConnection plain = factory.createDiagramModelConnection();
        plain.setId("plain-conn");
        plain.connect(f.hub, note);

        Map<String, Integer> counts = new HashMap<>();
        HubDataCollector.collect(f.view, counts, new HashMap<>(), new HashMap<>());

        assertEquals("the note line is not an ArchiMate connection and is not counted",
                Integer.valueOf(7), counts.get("obj-hub"));
    }

    @Test
    public void unmetHubPreconditions_shouldPublishExactlyTheCountTheSharedWalkProduces() {
        // The block's number and detect-hub-elements' number come from this one walk. Reading it
        // back through both entry points is what makes the agreement structural rather than a
        // coincidence two fixtures happen to share.
        Fixture f = hubWithSpokes(9, 240, 100);
        addArchimateConnection(f.model, f.hub, f.hub, "loop");

        Map<String, Integer> counts = new HashMap<>();
        HubDataCollector.collect(f.view, counts, new HashMap<>(), new HashMap<>());
        List<AssessLayoutResultDto.HubPreconditionDto> unmet =
                HubDataCollector.unmetHubPreconditions(f.view);

        assertEquals(1, unmet.size());
        assertEquals("the published count is the shared walk's count, self-loop included",
                counts.get("obj-hub"), Integer.valueOf(unmet.get(0).connectionCount()));
        assertEquals(Integer.valueOf(11), counts.get("obj-hub"));
    }

    // ---- Candidacy ----

    @Test
    public void unmetHubPreconditions_shouldNotReportAGroupingZone() {
        // A Grouping is an element view-object and can carry relationships, but it renders as a
        // transparent zone whose box is set by what it holds. A fan-out floor would fight the
        // containment that actually determines its size.
        Fixture f = groupingZoneWithSpokes(9);

        assertTrue("a Grouping zone is not a fan-out sizing candidate: "
                        + HubDataCollector.unmetHubPreconditions(f.view),
                HubDataCollector.unmetHubPreconditions(f.view).isEmpty());
    }

    @Test
    public void unmetHubPreconditions_shouldFindAHubNestedInsideAContainer() {
        // The walk recurses, so a hub placed inside a container is judged like any other.
        Fixture f = hubWithSpokes(KAFKA_CONNECTIONS, 240, 100);
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IGrouping zoneConcept = factory.createGrouping();
        zoneConcept.setId("zone");
        zoneConcept.setName("Zone");
        f.model.getFolder(FolderType.OTHER).getElements().add(zoneConcept);
        IDiagramModelArchimateObject zone = factory.createDiagramModelArchimateObject();
        zone.setId("obj-zone");
        zone.setArchimateConcept(zoneConcept);
        zone.setBounds(0, 0, 1200, 900);
        f.view.getChildren().remove(f.hub);
        zone.getChildren().add(f.hub);
        f.view.getChildren().add(zone);

        List<AssessLayoutResultDto.HubPreconditionDto> unmet =
                HubDataCollector.unmetHubPreconditions(f.view);
        assertEquals(1, unmet.size());
        assertEquals("obj-hub", unmet.get(0).viewObjectId());
    }

    @Test
    public void unmetHubPreconditions_shouldOrderTheWorstFanOutFirst() {
        Fixture f = hubWithSpokes(8, 100, 100);
        IDiagramModelArchimateObject second = addElement(f.model, f.view, "Second", "hub2", 100, 100);
        for (int i = 0; i < 15; i++) {
            addArchimateConnection(f.model, second,
                    addElement(f.model, f.view, "x" + i, "x" + i, 120, 55), "r2-" + i);
        }

        List<AssessLayoutResultDto.HubPreconditionDto> unmet =
                HubDataCollector.unmetHubPreconditions(f.view);
        assertEquals(2, unmet.size());
        assertEquals("the busiest hub leads", "obj-hub2", unmet.get(0).viewObjectId());
        assertTrue(unmet.get(0).connectionCount() > unmet.get(1).connectionCount());
    }

    @Test
    public void unmetHubPreconditions_shouldReturnAnEmptyList_onAViewWithNoConnections() {
        Fixture f = hubWithSpokes(0, 40, 20);
        List<AssessLayoutResultDto.HubPreconditionDto> unmet =
                HubDataCollector.unmetHubPreconditions(f.view);
        assertNotNull("measured and empty, never null", unmet);
        assertTrue(unmet.isEmpty());
    }

    // ---- Fixtures ----

    private record Fixture(IArchimateModel model, IArchimateDiagramModel view,
                           IDiagramModelArchimateObject hub,
                           List<IDiagramModelConnection> connections) {}

    private Fixture hubWithSpokes(int spokes, int hubWidth, int hubHeight) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IDiagramModelArchimateObject hub =
                addElement(model, view, "Kafka", "hub", hubWidth, hubHeight);
        List<IDiagramModelConnection> connections = new java.util.ArrayList<>();
        for (int i = 0; i < spokes; i++) {
            IDiagramModelArchimateObject spoke =
                    addElement(model, view, "Spoke " + i, "s" + i, 120, 55);
            connections.add(addArchimateConnection(model, hub, spoke, "r" + i));
        }
        return new Fixture(model, view, hub, connections);
    }

    private Fixture groupingZoneWithSpokes(int spokes) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IGrouping concept = factory.createGrouping();
        concept.setId("zone");
        concept.setName("Zone");
        model.getFolder(FolderType.OTHER).getElements().add(concept);
        IDiagramModelArchimateObject zone = factory.createDiagramModelArchimateObject();
        zone.setId("obj-zone");
        zone.setArchimateConcept(concept);
        zone.setBounds(0, 0, 100, 60);
        view.getChildren().add(zone);

        List<IDiagramModelConnection> connections = new java.util.ArrayList<>();
        for (int i = 0; i < spokes; i++) {
            IDiagramModelArchimateObject spoke =
                    addElement(model, view, "Spoke " + i, "s" + i, 120, 55);
            connections.add(addArchimateConnection(model, zone, spoke, "r" + i));
        }
        return new Fixture(model, view, zone, connections);
    }

    private IDiagramModelArchimateObject addElement(IArchimateModel model,
            IDiagramModelContainer parent, String name, String id, int width, int height) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IApplicationComponent concept = factory.createApplicationComponent();
        concept.setId(id);
        concept.setName(name);
        model.getFolder(FolderType.APPLICATION).getElements().add(concept);

        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(concept);
        obj.setBounds(0, 0, width, height);
        parent.getChildren().add(obj);
        return obj;
    }

    private IDiagramModelArchimateConnection addArchimateConnection(IArchimateModel model,
            IDiagramModelArchimateObject source, IDiagramModelArchimateObject target, String id) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateRelationship relationship = factory.createAssociationRelationship();
        relationship.setId(id);
        relationship.setSource(source.getArchimateElement());
        relationship.setTarget(target.getArchimateElement());
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);

        IDiagramModelArchimateConnection connection =
                factory.createDiagramModelArchimateConnection();
        connection.setId("conn-" + id);
        connection.setArchimateConcept(relationship);
        connection.connect(source, target);
        return connection;
    }
}
