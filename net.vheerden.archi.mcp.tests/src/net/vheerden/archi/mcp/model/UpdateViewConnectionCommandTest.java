package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
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
import com.archimatetool.model.IDiagramModelBendpoint;

/**
 * Tests for {@link UpdateViewConnectionCommand} (Story 7-8).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (replace bendpoints), undo (restore old), and empty array (clear).</p>
 */
public class UpdateViewConnectionCommandTest {

    private IArchimateFactory factory;
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

        IDiagramModelArchimateObject sourceViewObj = factory.createDiagramModelArchimateObject();
        sourceViewObj.setArchimateElement(source);
        sourceViewObj.setBounds(50, 50, 120, 55);
        view.getChildren().add(sourceViewObj);

        IDiagramModelArchimateObject targetViewObj = factory.createDiagramModelArchimateObject();
        targetViewObj.setArchimateElement(target);
        targetViewObj.setBounds(250, 50, 120, 55);
        view.getChildren().add(targetViewObj);

        connection = factory.createDiagramModelArchimateConnection();
        connection.setArchimateRelationship(rel);
        connection.connect(sourceViewObj, targetViewObj);

        // Add an initial bendpoint
        IDiagramModelBendpoint bp = factory.createDiagramModelBendpoint();
        bp.setStartX(10);
        bp.setStartY(20);
        bp.setEndX(-10);
        bp.setEndY(-20);
        connection.getBendpoints().add(bp);
    }

    @Test
    public void shouldReplaceBendpoints_whenExecuted() {
        IDiagramModelBendpoint newBp = factory.createDiagramModelBendpoint();
        newBp.setStartX(60);
        newBp.setStartY(0);
        newBp.setEndX(-60);
        newBp.setEndY(0);

        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, List.of(newBp));

        cmd.execute();

        assertEquals(1, connection.getBendpoints().size());
        assertEquals(60, connection.getBendpoints().get(0).getStartX());
    }

    @Test
    public void shouldClearBendpoints_whenEmptyListProvided() {
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, List.of());

        cmd.execute();

        assertEquals(0, connection.getBendpoints().size());
    }

    @Test
    public void shouldRestoreOldBendpoints_whenUndone() {
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, List.of());
        cmd.execute();
        assertEquals(0, connection.getBendpoints().size());

        cmd.undo();

        assertEquals(1, connection.getBendpoints().size());
        assertEquals(10, connection.getBendpoints().get(0).getStartX());
        assertEquals(20, connection.getBendpoints().get(0).getStartY());
    }

    @Test
    public void shouldExposeConnectionViaGetter() {
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, List.of());

        assertSame(connection, cmd.getConnection());
    }

    @Test
    public void shouldCaptureOldBendpointsDefensively() {
        int oldCount = connection.getBendpoints().size();
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, List.of());

        assertEquals("Old bendpoints should be captured at construction",
                oldCount, cmd.getOldBendpoints().size());
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, List.of());

        assertTrue("Label should describe connection update",
                cmd.getLabel().contains("connection"));
    }

    @Test
    public void shouldReApplyNewBendpoints_whenRedone() {
        IDiagramModelBendpoint newBp = factory.createDiagramModelBendpoint();
        newBp.setStartX(99);
        newBp.setStartY(88);
        newBp.setEndX(-99);
        newBp.setEndY(-88);

        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, List.of(newBp));
        cmd.execute();
        cmd.undo();

        cmd.redo();

        assertEquals(1, connection.getBendpoints().size());
        assertEquals(99, connection.getBendpoints().get(0).getStartX());
    }

    // ---- Story 11-2: Connection styling tests ----

    @Test
    public void shouldApplyLineColor_whenStylingProvided() {
        StylingParams styling = new StylingParams(null, "#FF0000", null, null, null);
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, connection.getBendpoints(), styling);

        cmd.execute();

        assertEquals("#FF0000", connection.getLineColor());
        assertTrue(cmd.hasStylingChange());
    }

    @Test
    public void shouldApplyFontColor_whenStylingProvided() {
        StylingParams styling = new StylingParams(null, null, "#00FF00", null, null);
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, connection.getBendpoints(), styling);

        cmd.execute();

        assertEquals("#00FF00", connection.getFontColor());
    }

    @Test
    public void shouldApplyLineWidth_whenStylingProvided() {
        StylingParams styling = new StylingParams(null, null, null, null, 3);
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, connection.getBendpoints(), styling);

        cmd.execute();

        assertEquals(3, connection.getLineWidth());
    }

    @Test
    public void shouldClearLineColor_whenEmptyString() {
        connection.setLineColor("#FF0000");
        StylingParams styling = new StylingParams(null, "", null, null, null);
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, connection.getBendpoints(), styling);

        cmd.execute();

        assertNull("Empty string should clear to null (default)", connection.getLineColor());
    }

    @Test
    public void shouldUndoConnectionStyling() {
        String origLineColor = connection.getLineColor();
        int origLineWidth = connection.getLineWidth();

        StylingParams styling = new StylingParams(null, "#0000FF", null, null, 2);
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, connection.getBendpoints(), styling);
        cmd.execute();

        assertEquals("#0000FF", connection.getLineColor());
        assertEquals(2, connection.getLineWidth());

        cmd.undo();

        assertEquals(origLineColor, connection.getLineColor());
        assertEquals(origLineWidth, connection.getLineWidth());
    }

    @Test
    public void shouldNotHaveStylingChange_whenNullStylingParams() {
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, List.of(), null);

        assertFalse(cmd.hasStylingChange());
    }

    @Test
    public void shouldPreserveBendpoints_whenStylingOnlyWithExistingBendpoints() {
        // Regression test for H1 bug: styling-only update must not destroy existing bendpoints
        int originalBpCount = connection.getBendpoints().size();
        int originalStartX = connection.getBendpoints().get(0).getStartX();

        // Pass existing bendpoints explicitly (simulates the H1 fix in ArchiModelAccessorImpl)
        StylingParams styling = new StylingParams(null, "#FF0000", null, null, null);
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, new ArrayList<>(connection.getBendpoints()), styling);

        cmd.execute();

        assertEquals("Bendpoints should be preserved", originalBpCount, connection.getBendpoints().size());
        assertEquals("Bendpoint data should be unchanged", originalStartX,
                connection.getBendpoints().get(0).getStartX());
        assertEquals("#FF0000", connection.getLineColor());
    }

    @Test
    public void shouldApplyStylingAndBendpoints_together() {
        IDiagramModelBendpoint newBp = factory.createDiagramModelBendpoint();
        newBp.setStartX(40);
        newBp.setStartY(0);
        newBp.setEndX(-40);
        newBp.setEndY(0);

        StylingParams styling = new StylingParams(null, "#AA0000", "#00BB00", null, 2);
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                connection, List.of(newBp), styling);

        cmd.execute();

        assertEquals(1, connection.getBendpoints().size());
        assertEquals(40, connection.getBendpoints().get(0).getStartX());
        assertEquals("#AA0000", connection.getLineColor());
        assertEquals("#00BB00", connection.getFontColor());
        assertEquals(2, connection.getLineWidth());
    }
}
