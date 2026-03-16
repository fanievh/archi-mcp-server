package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;

/**
 * Tests for {@link ClearViewCommand} (Story 8-0c).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (clear all children + disconnect connections) and undo (restore all).</p>
 */
public class ClearViewCommandTest {

    private IArchimateFactory factory;
    private IArchimateDiagramModel view;
    private IDiagramModelArchimateObject viewObj1;
    private IDiagramModelArchimateObject viewObj2;
    private IDiagramModelArchimateObject viewObj3;
    private IDiagramModelArchimateConnection conn1;
    private IDiagramModelArchimateConnection conn2;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        view = factory.createArchimateDiagramModel();
        view.setName("Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        // Create 3 elements
        IArchimateElement elem1 = factory.createApplicationComponent();
        elem1.setName("Comp A");
        model.getFolder(FolderType.APPLICATION).getElements().add(elem1);

        IArchimateElement elem2 = factory.createApplicationComponent();
        elem2.setName("Comp B");
        model.getFolder(FolderType.APPLICATION).getElements().add(elem2);

        IArchimateElement elem3 = factory.createApplicationComponent();
        elem3.setName("Comp C");
        model.getFolder(FolderType.APPLICATION).getElements().add(elem3);

        // Relationships: 1->2, 2->3
        IArchimateRelationship rel1 = factory.createServingRelationship();
        rel1.connect(elem1, elem2);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel1);

        IArchimateRelationship rel2 = factory.createServingRelationship();
        rel2.connect(elem2, elem3);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel2);

        // View objects
        viewObj1 = factory.createDiagramModelArchimateObject();
        viewObj1.setArchimateElement(elem1);
        viewObj1.setBounds(50, 50, 120, 55);
        view.getChildren().add(viewObj1);

        viewObj2 = factory.createDiagramModelArchimateObject();
        viewObj2.setArchimateElement(elem2);
        viewObj2.setBounds(250, 50, 120, 55);
        view.getChildren().add(viewObj2);

        viewObj3 = factory.createDiagramModelArchimateObject();
        viewObj3.setArchimateElement(elem3);
        viewObj3.setBounds(450, 50, 120, 55);
        view.getChildren().add(viewObj3);

        // Connections
        conn1 = factory.createDiagramModelArchimateConnection();
        conn1.setArchimateRelationship(rel1);
        conn1.connect(viewObj1, viewObj2);

        conn2 = factory.createDiagramModelArchimateConnection();
        conn2.setArchimateRelationship(rel2);
        conn2.connect(viewObj2, viewObj3);
    }

    @Test
    public void shouldRemoveAllChildrenAndConnections() {
        ClearViewCommand cmd = new ClearViewCommand(view);

        cmd.execute();

        assertEquals("View should have no children", 0, view.getChildren().size());
        assertTrue("conn1 should be disconnected from source",
                viewObj1.getSourceConnections().isEmpty());
        assertTrue("conn2 should be disconnected from source",
                viewObj2.getSourceConnections().isEmpty());
        assertTrue("conn1 should be disconnected from target",
                viewObj2.getTargetConnections().isEmpty());
        assertTrue("conn2 should be disconnected from target",
                viewObj3.getTargetConnections().isEmpty());
    }

    @Test
    public void shouldUndoAndRestoreAll() {
        ClearViewCommand cmd = new ClearViewCommand(view);
        cmd.execute();

        cmd.undo();

        assertEquals("View should have 3 children after undo", 3, view.getChildren().size());
        assertSame("First child should be viewObj1 (z-order)", viewObj1, view.getChildren().get(0));
        assertSame("Second child should be viewObj2 (z-order)", viewObj2, view.getChildren().get(1));
        assertSame("Third child should be viewObj3 (z-order)", viewObj3, view.getChildren().get(2));

        // Connections should be restored
        assertTrue("conn1 should be reconnected to source",
                viewObj1.getSourceConnections().contains(conn1));
        assertTrue("conn1 should be reconnected to target",
                viewObj2.getTargetConnections().contains(conn1));
        assertTrue("conn2 should be reconnected to source",
                viewObj2.getSourceConnections().contains(conn2));
        assertTrue("conn2 should be reconnected to target",
                viewObj3.getTargetConnections().contains(conn2));
    }

    @Test
    public void shouldRedoAfterUndo() {
        ClearViewCommand cmd = new ClearViewCommand(view);
        cmd.execute();
        cmd.undo();

        cmd.redo();

        assertEquals("View should have no children after redo", 0, view.getChildren().size());
        assertTrue("conn1 should be disconnected after redo",
                viewObj1.getSourceConnections().isEmpty());
        assertTrue("conn2 should be disconnected after redo",
                viewObj2.getSourceConnections().isEmpty());
    }

    @Test
    public void shouldUndoAfterRedo() {
        ClearViewCommand cmd = new ClearViewCommand(view);
        cmd.execute();
        cmd.undo();
        cmd.redo();

        cmd.undo();

        assertEquals("View should have 3 children after undo-redo-undo", 3, view.getChildren().size());
        assertSame("First child should be viewObj1 (z-order)", viewObj1, view.getChildren().get(0));
        assertSame("Second child should be viewObj2 (z-order)", viewObj2, view.getChildren().get(1));
        assertSame("Third child should be viewObj3 (z-order)", viewObj3, view.getChildren().get(2));

        // Connections should be fully restored
        assertTrue("conn1 should be reconnected to source",
                viewObj1.getSourceConnections().contains(conn1));
        assertTrue("conn1 should be reconnected to target",
                viewObj2.getTargetConnections().contains(conn1));
        assertTrue("conn2 should be reconnected to source",
                viewObj2.getSourceConnections().contains(conn2));
        assertTrue("conn2 should be reconnected to target",
                viewObj3.getTargetConnections().contains(conn2));
    }

    @Test
    public void shouldHandleEmptyView() {
        IArchimateDiagramModel emptyView = factory.createArchimateDiagramModel();
        emptyView.setName("Empty View");

        ClearViewCommand cmd = new ClearViewCommand(emptyView);
        cmd.execute();

        assertEquals("Empty view should still be empty", 0, emptyView.getChildren().size());
        assertNotNull("removedChildren should be initialized", cmd.getRemovedChildren());
        assertEquals("removedChildren should be empty", 0, cmd.getRemovedChildren().size());
        assertNotNull("removedConnections should be initialized", cmd.getRemovedConnections());
        assertEquals("removedConnections should be empty", 0, cmd.getRemovedConnections().size());
    }

    @Test
    public void shouldHandleViewWithElementsButNoConnections() {
        // Create a view with just one element, no connections
        IArchimateDiagramModel soloView = factory.createArchimateDiagramModel();
        soloView.setName("Solo View");
        IArchimateElement elem = factory.createBusinessActor();
        elem.setName("Solo");
        IDiagramModelArchimateObject soloObj = factory.createDiagramModelArchimateObject();
        soloObj.setArchimateElement(elem);
        soloObj.setBounds(50, 50, 120, 55);
        soloView.getChildren().add(soloObj);

        ClearViewCommand cmd = new ClearViewCommand(soloView);
        cmd.execute();

        assertEquals("View should be empty", 0, soloView.getChildren().size());
        assertEquals("No connections to remove", 0, cmd.getRemovedConnections().size());

        // Undo should restore the element
        cmd.undo();
        assertEquals("View should have 1 child after undo", 1, soloView.getChildren().size());
        assertSame(soloObj, soloView.getChildren().get(0));
    }

    @Test
    public void shouldExposeFieldsViaGetters() {
        ClearViewCommand cmd = new ClearViewCommand(view);
        cmd.execute();

        assertSame(view, cmd.getView());
        assertEquals(3, cmd.getRemovedChildren().size());
        assertEquals(2, cmd.getRemovedConnections().size());
        assertEquals(List.of(0, 1, 2), cmd.getOriginalIndices());
    }

    // ---- Story 10-14: Nested group connection cleanup ----

    @Test
    public void shouldDisconnectConnectionsInsideGroups() {
        // Create a view with a group containing two elements connected to each other
        IArchimateDiagramModel groupView = factory.createArchimateDiagramModel();
        groupView.setName("Group View");
        IArchimateModel model2 = factory.createArchimateModel();
        model2.setDefaults();
        model2.getFolder(FolderType.DIAGRAMS).getElements().add(groupView);

        IArchimateElement elemA = factory.createApplicationComponent();
        elemA.setName("Nested A");
        model2.getFolder(FolderType.APPLICATION).getElements().add(elemA);

        IArchimateElement elemB = factory.createApplicationComponent();
        elemB.setName("Nested B");
        model2.getFolder(FolderType.APPLICATION).getElements().add(elemB);

        IArchimateRelationship rel = factory.createServingRelationship();
        rel.connect(elemA, elemB);
        model2.getFolder(FolderType.RELATIONS).getElements().add(rel);

        // Create group and nest elements inside it
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setName("Test Group");
        group.setBounds(10, 10, 400, 200);
        groupView.getChildren().add(group);

        IDiagramModelArchimateObject nestedObj1 = factory.createDiagramModelArchimateObject();
        nestedObj1.setArchimateElement(elemA);
        nestedObj1.setBounds(20, 30, 120, 55);
        group.getChildren().add(nestedObj1);

        IDiagramModelArchimateObject nestedObj2 = factory.createDiagramModelArchimateObject();
        nestedObj2.setArchimateElement(elemB);
        nestedObj2.setBounds(200, 30, 120, 55);
        group.getChildren().add(nestedObj2);

        // Connection between nested elements
        IDiagramModelArchimateConnection nestedConn = factory.createDiagramModelArchimateConnection();
        nestedConn.setArchimateRelationship(rel);
        nestedConn.connect(nestedObj1, nestedObj2);

        // Verify setup
        assertEquals(1, nestedObj1.getSourceConnections().size());
        assertEquals(1, nestedObj2.getTargetConnections().size());

        // Execute clear
        ClearViewCommand cmd = new ClearViewCommand(groupView);
        cmd.execute();

        // Connections inside groups must be disconnected (the fix for Story 10-14)
        assertEquals("View should have no children", 0, groupView.getChildren().size());
        assertTrue("Nested connection should be disconnected from source",
                nestedObj1.getSourceConnections().isEmpty());
        assertTrue("Nested connection should be disconnected from target",
                nestedObj2.getTargetConnections().isEmpty());
        assertEquals("Should have found 1 connection (nested)", 1,
                cmd.getRemovedConnections().size());
    }

    @Test
    public void shouldNotAffectConnectionsOnOtherViews() {
        // Create a second view sharing the same elements
        IArchimateDiagramModel view2 = factory.createArchimateDiagramModel();
        view2.setName("View 2");

        // Get the elements from the existing setup
        IArchimateElement elem1 = viewObj1.getArchimateElement();
        IArchimateElement elem2 = viewObj2.getArchimateElement();

        IDiagramModelArchimateObject view2Obj1 = factory.createDiagramModelArchimateObject();
        view2Obj1.setArchimateElement(elem1);
        view2Obj1.setBounds(50, 50, 120, 55);
        view2.getChildren().add(view2Obj1);

        IDiagramModelArchimateObject view2Obj2 = factory.createDiagramModelArchimateObject();
        view2Obj2.setArchimateElement(elem2);
        view2Obj2.setBounds(250, 50, 120, 55);
        view2.getChildren().add(view2Obj2);

        // Create View 2's own connection for the same relationship
        IArchimateRelationship rel1 = conn1.getArchimateRelationship();
        IDiagramModelArchimateConnection view2Conn = factory.createDiagramModelArchimateConnection();
        view2Conn.setArchimateRelationship(rel1);
        view2Conn.connect(view2Obj1, view2Obj2);

        // Clear the original view
        ClearViewCommand cmd = new ClearViewCommand(view);
        cmd.execute();

        // View 2's connections should be unaffected
        assertEquals("View 2 should still have 2 children", 2, view2.getChildren().size());
        assertEquals("View 2's connection should still be connected to source",
                1, view2Obj1.getSourceConnections().size());
        assertEquals("View 2's connection should still be connected to target",
                1, view2Obj2.getTargetConnections().size());
        assertSame("View 2's connection should be intact",
                view2Conn, view2Obj1.getSourceConnections().get(0));
    }

    @Test
    public void shouldUndoNestedGroupConnections() {
        // Create a view with a group containing connected elements
        IArchimateDiagramModel groupView = factory.createArchimateDiagramModel();
        groupView.setName("Group View");
        IArchimateModel model2 = factory.createArchimateModel();
        model2.setDefaults();
        model2.getFolder(FolderType.DIAGRAMS).getElements().add(groupView);

        IArchimateElement elemA = factory.createApplicationComponent();
        elemA.setName("Nested A");
        model2.getFolder(FolderType.APPLICATION).getElements().add(elemA);

        IArchimateElement elemB = factory.createApplicationComponent();
        elemB.setName("Nested B");
        model2.getFolder(FolderType.APPLICATION).getElements().add(elemB);

        IArchimateRelationship rel = factory.createServingRelationship();
        rel.connect(elemA, elemB);
        model2.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setName("Test Group");
        group.setBounds(10, 10, 400, 200);
        groupView.getChildren().add(group);

        IDiagramModelArchimateObject nestedObj1 = factory.createDiagramModelArchimateObject();
        nestedObj1.setArchimateElement(elemA);
        nestedObj1.setBounds(20, 30, 120, 55);
        group.getChildren().add(nestedObj1);

        IDiagramModelArchimateObject nestedObj2 = factory.createDiagramModelArchimateObject();
        nestedObj2.setArchimateElement(elemB);
        nestedObj2.setBounds(200, 30, 120, 55);
        group.getChildren().add(nestedObj2);

        IDiagramModelArchimateConnection nestedConn = factory.createDiagramModelArchimateConnection();
        nestedConn.setArchimateRelationship(rel);
        nestedConn.connect(nestedObj1, nestedObj2);

        ClearViewCommand cmd = new ClearViewCommand(groupView);
        cmd.execute();
        cmd.undo();

        // Group and nested elements should be restored
        assertEquals("View should have 1 child (group) after undo", 1,
                groupView.getChildren().size());
        assertSame(group, groupView.getChildren().get(0));
        assertEquals("Group should have 2 children after undo", 2,
                group.getChildren().size());

        // Nested connection should be reconnected
        assertTrue("Nested connection should be reconnected to source",
                nestedObj1.getSourceConnections().contains(nestedConn));
        assertTrue("Nested connection should be reconnected to target",
                nestedObj2.getTargetConnections().contains(nestedConn));
    }
}
