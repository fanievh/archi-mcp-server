package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

/**
 * Tests for {@link CreateViewCommand} (Story 7-2).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (add to folder) and undo (remove from folder) behavior.</p>
 */
public class CreateViewCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder diagramsFolder;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();
        diagramsFolder = model.getFolder(FolderType.DIAGRAMS);

        view = factory.createArchimateDiagramModel();
        view.setName("Test View");
    }

    @Test
    public void shouldAddViewToFolder_whenExecuted() {
        CreateViewCommand cmd = new CreateViewCommand(view, diagramsFolder);

        cmd.execute();

        assertTrue("View should be in folder",
                diagramsFolder.getElements().contains(view));
    }

    @Test
    public void shouldRemoveViewFromFolder_whenUndone() {
        CreateViewCommand cmd = new CreateViewCommand(view, diagramsFolder);
        cmd.execute();

        cmd.undo();

        assertFalse("View should not be in folder after undo",
                diagramsFolder.getElements().contains(view));
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        CreateViewCommand cmd = new CreateViewCommand(view, diagramsFolder);

        String label = cmd.getLabel();

        assertTrue("Label should contain view name",
                label.contains("Test View"));
        assertTrue("Label should start with 'Create view:'",
                label.startsWith("Create view:"));
    }

    @Test
    public void shouldNotAffectExistingViews_whenExecuted() {
        IArchimateDiagramModel existing = factory.createArchimateDiagramModel();
        existing.setName("Existing View");
        diagramsFolder.getElements().add(existing);
        int initialCount = diagramsFolder.getElements().size();

        CreateViewCommand cmd = new CreateViewCommand(view, diagramsFolder);
        cmd.execute();

        assertEquals("Should have one more view", initialCount + 1,
                diagramsFolder.getElements().size());
    }

    @Test
    public void shouldRestoreExactState_whenExecuteThenUndo() {
        IArchimateDiagramModel existing = factory.createArchimateDiagramModel();
        existing.setName("Existing View");
        diagramsFolder.getElements().add(existing);
        int initialCount = diagramsFolder.getElements().size();

        CreateViewCommand cmd = new CreateViewCommand(view, diagramsFolder);
        cmd.execute();
        cmd.undo();

        assertEquals("Should be back to initial count", initialCount,
                diagramsFolder.getElements().size());
    }
}
