package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

/**
 * Tests for {@link RemoveFromViewCommand} (Story 7-8).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (remove + cascade disconnect) and undo (re-add + reconnect).</p>
 */
public class RemoveFromViewCommandTest {

    private IArchimateFactory factory;
    private IArchimateDiagramModel view;
    private IDiagramModelArchimateObject sourceViewObj;
    private IDiagramModelArchimateObject targetViewObj;
    private IDiagramModelArchimateConnection connection;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        view = factory.createArchimateDiagramModel();
        view.setName("Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IArchimateElement source = factory.createApplicationComponent();
        source.setName("Source");
        model.getFolder(FolderType.APPLICATION).getElements().add(source);

        IArchimateElement target = factory.createApplicationComponent();
        target.setName("Target");
        model.getFolder(FolderType.APPLICATION).getElements().add(target);

        IArchimateRelationship rel = factory.createServingRelationship();
        rel.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        sourceViewObj = factory.createDiagramModelArchimateObject();
        sourceViewObj.setArchimateElement(source);
        sourceViewObj.setBounds(50, 50, 120, 55);
        view.getChildren().add(sourceViewObj);

        targetViewObj = factory.createDiagramModelArchimateObject();
        targetViewObj.setArchimateElement(target);
        targetViewObj.setBounds(250, 50, 120, 55);
        view.getChildren().add(targetViewObj);

        connection = factory.createDiagramModelArchimateConnection();
        connection.setArchimateRelationship(rel);
        connection.connect(sourceViewObj, targetViewObj);
    }

    @Test
    public void shouldRemoveObjectFromView_whenExecuted() {
        RemoveFromViewCommand cmd = new RemoveFromViewCommand(
                sourceViewObj, view, List.of(connection));

        cmd.execute();

        assertFalse("Source view object should not be in view children",
                view.getChildren().contains(sourceViewObj));
    }

    @Test
    public void shouldDisconnectAttachedConnections_whenExecuted() {
        RemoveFromViewCommand cmd = new RemoveFromViewCommand(
                sourceViewObj, view, List.of(connection));

        cmd.execute();

        assertFalse("Connection should be disconnected from source",
                sourceViewObj.getSourceConnections().contains(connection));
        assertFalse("Connection should be disconnected from target",
                targetViewObj.getTargetConnections().contains(connection));
    }

    @Test
    public void shouldReAddObjectToView_whenUndone() {
        RemoveFromViewCommand cmd = new RemoveFromViewCommand(
                sourceViewObj, view, List.of(connection));
        cmd.execute();

        cmd.undo();

        assertTrue("Source view object should be in view children after undo",
                view.getChildren().contains(sourceViewObj));
    }

    @Test
    public void shouldReconnectConnections_whenUndone() {
        RemoveFromViewCommand cmd = new RemoveFromViewCommand(
                sourceViewObj, view, List.of(connection));
        cmd.execute();

        cmd.undo();

        assertTrue("Connection should be reconnected to source",
                sourceViewObj.getSourceConnections().contains(connection));
        assertTrue("Connection should be reconnected to target",
                targetViewObj.getTargetConnections().contains(connection));
    }

    @Test
    public void shouldHandleNoAttachedConnections() {
        // Remove target (no connections as source)
        RemoveFromViewCommand cmd = new RemoveFromViewCommand(
                targetViewObj, view, List.of());

        cmd.execute();

        assertFalse("Target should be removed from view",
                view.getChildren().contains(targetViewObj));
        // Source should remain
        assertTrue("Source should remain on view",
                view.getChildren().contains(sourceViewObj));
    }

    @Test
    public void shouldExposeFieldsViaGetters() {
        RemoveFromViewCommand cmd = new RemoveFromViewCommand(
                sourceViewObj, view, List.of(connection));

        assertSame(sourceViewObj, cmd.getDiagramObject());
        assertSame(view, cmd.getView());
        assertEquals(1, cmd.getAttachedConnections().size());
        assertSame(connection, cmd.getAttachedConnections().get(0));
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        RemoveFromViewCommand cmd = new RemoveFromViewCommand(
                sourceViewObj, view, List.of(connection));

        assertTrue("Label should contain element type",
                cmd.getLabel().contains("ApplicationComponent"));
        assertTrue("Label should contain 'from view'",
                cmd.getLabel().contains("from view"));
    }
}
