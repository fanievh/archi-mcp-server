package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
 * Tests for {@link RemoveConnectionFromViewCommand} (Story 7-8).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * disconnect (execute), reconnect (undo), and re-disconnect (redo).</p>
 */
public class RemoveConnectionFromViewCommandTest {

    private IArchimateFactory factory;
    private IDiagramModelArchimateObject sourceViewObj;
    private IDiagramModelArchimateObject targetViewObj;
    private IDiagramModelArchimateConnection connection;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
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
    public void shouldDisconnect_whenExecuted() {
        RemoveConnectionFromViewCommand cmd = new RemoveConnectionFromViewCommand(connection);

        cmd.execute();

        assertFalse("Source should not have connection",
                sourceViewObj.getSourceConnections().contains(connection));
        assertFalse("Target should not have connection",
                targetViewObj.getTargetConnections().contains(connection));
    }

    @Test
    public void shouldReconnect_whenUndone() {
        RemoveConnectionFromViewCommand cmd = new RemoveConnectionFromViewCommand(connection);
        cmd.execute();

        cmd.undo();

        assertTrue("Source should have connection after undo",
                sourceViewObj.getSourceConnections().contains(connection));
        assertTrue("Target should have connection after undo",
                targetViewObj.getTargetConnections().contains(connection));
    }

    @Test
    public void shouldReDisconnect_whenRedone() {
        RemoveConnectionFromViewCommand cmd = new RemoveConnectionFromViewCommand(connection);
        cmd.execute();
        cmd.undo();

        cmd.redo();

        assertFalse("Source should not have connection after redo",
                sourceViewObj.getSourceConnections().contains(connection));
        assertFalse("Target should not have connection after redo",
                targetViewObj.getTargetConnections().contains(connection));
    }

    @Test
    public void shouldExposeFieldsViaGetters() {
        RemoveConnectionFromViewCommand cmd = new RemoveConnectionFromViewCommand(connection);

        assertSame(connection, cmd.getConnection());
        assertSame(sourceViewObj, cmd.getSource());
        assertSame(targetViewObj, cmd.getTarget());
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        RemoveConnectionFromViewCommand cmd = new RemoveConnectionFromViewCommand(connection);

        assertTrue("Label should describe connection removal",
                cmd.getLabel().contains("connection"));
    }
}
