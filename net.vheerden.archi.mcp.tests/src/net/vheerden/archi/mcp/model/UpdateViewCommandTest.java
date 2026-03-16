package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModel;

/**
 * Tests for {@link UpdateViewCommand} connection router type handling (Story 9-0c).
 *
 * <p>Uses real EMF objects (IArchimateFactory.eINSTANCE) to verify the command
 * correctly sets, undoes, and redoes connection router type changes.</p>
 */
public class UpdateViewCommandTest {

    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setId("view-cmd-test");
        view.setName("Test View");
    }

    @Test
    public void execute_shouldSetConnectionRouterType() {
        // Default is BENDPOINT (0)
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, view.getConnectionRouterType());

        UpdateViewCommand cmd = new UpdateViewCommand(
                view, null, null, false, null, null,
                IDiagramModel.CONNECTION_ROUTER_MANHATTAN);
        cmd.execute();

        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, view.getConnectionRouterType());
    }

    @Test
    public void undo_shouldRevertConnectionRouterType() {
        // Start with default
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, view.getConnectionRouterType());

        UpdateViewCommand cmd = new UpdateViewCommand(
                view, null, null, false, null, null,
                IDiagramModel.CONNECTION_ROUTER_MANHATTAN);
        cmd.execute();
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, view.getConnectionRouterType());

        cmd.undo();
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, view.getConnectionRouterType());
    }

    @Test
    public void redo_shouldReapplyConnectionRouterType() {
        UpdateViewCommand cmd = new UpdateViewCommand(
                view, null, null, false, null, null,
                IDiagramModel.CONNECTION_ROUTER_MANHATTAN);
        cmd.execute();
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, view.getConnectionRouterType());

        cmd.undo();
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, view.getConnectionRouterType());

        cmd.redo();
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, view.getConnectionRouterType());
    }

    @Test
    public void execute_shouldNotChangeRouterTypeWhenNull() {
        // Pre-set to Manhattan
        view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_MANHATTAN);

        // Pass null for newConnectionRouterType — should leave unchanged
        UpdateViewCommand cmd = new UpdateViewCommand(
                view, "Renamed View", null, false, null, null, null);
        cmd.execute();

        // Router type should still be Manhattan (unchanged)
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, view.getConnectionRouterType());
        // Name should be updated
        assertEquals("Renamed View", view.getName());
    }

    @Test
    public void undo_shouldNotRevertRouterTypeWhenNull() {
        // Pre-set to Manhattan
        view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_MANHATTAN);

        // Only change name, not router type
        UpdateViewCommand cmd = new UpdateViewCommand(
                view, "New Name", null, false, null, null, null);
        cmd.execute();

        assertEquals("New Name", view.getName());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, view.getConnectionRouterType());

        cmd.undo();

        assertEquals("Test View", view.getName());
        // Router type should still be Manhattan — undo should not touch it
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, view.getConnectionRouterType());
    }

    @Test
    public void execute_shouldRevertManhattanToDefault() {
        // Start with Manhattan
        view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_MANHATTAN);

        UpdateViewCommand cmd = new UpdateViewCommand(
                view, null, null, false, null, null,
                IDiagramModel.CONNECTION_ROUTER_BENDPOINT);
        cmd.execute();

        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, view.getConnectionRouterType());

        // Undo should restore Manhattan
        cmd.undo();
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, view.getConnectionRouterType());
    }
}
