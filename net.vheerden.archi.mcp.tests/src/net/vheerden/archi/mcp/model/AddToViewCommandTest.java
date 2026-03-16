package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
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
import com.archimatetool.model.IDiagramModelArchimateObject;

/**
 * Tests for {@link AddToViewCommand} (Story 7-7).
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (add to view) and undo (remove from view) behavior.</p>
 */
public class AddToViewCommandTest {

    private IArchimateFactory factory;
    private IArchimateDiagramModel view;
    private IDiagramModelArchimateObject diagramObject;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        view = factory.createArchimateDiagramModel();
        view.setName("Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IArchimateElement element = factory.createApplicationComponent();
        element.setName("My Component");
        model.getFolder(FolderType.APPLICATION).getElements().add(element);

        diagramObject = factory.createDiagramModelArchimateObject();
        diagramObject.setArchimateElement(element);
        diagramObject.setBounds(50, 50, 120, 55);
    }

    @Test
    public void shouldAddDiagramObjectToView_whenExecuted() {
        AddToViewCommand cmd = new AddToViewCommand(diagramObject, view);

        cmd.execute();

        assertTrue("Diagram object should be in view children",
                view.getChildren().contains(diagramObject));
    }

    @Test
    public void shouldRemoveDiagramObjectFromView_whenUndone() {
        AddToViewCommand cmd = new AddToViewCommand(diagramObject, view);
        cmd.execute();

        cmd.undo();

        assertFalse("Diagram object should not be in view after undo",
                view.getChildren().contains(diagramObject));
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        AddToViewCommand cmd = new AddToViewCommand(diagramObject, view);

        String label = cmd.getLabel();

        assertTrue("Label should contain element type",
                label.contains("ApplicationComponent"));
        assertTrue("Label should contain 'to view'",
                label.contains("to view"));
    }

    @Test
    public void shouldExposeFieldsViaGetters() {
        AddToViewCommand cmd = new AddToViewCommand(diagramObject, view);

        assertSame(diagramObject, cmd.getDiagramObject());
        assertSame(view, cmd.getView());
    }

    @Test
    public void shouldReAddDiagramObjectToView_whenRedone() {
        AddToViewCommand cmd = new AddToViewCommand(diagramObject, view);
        cmd.execute();
        cmd.undo();

        cmd.redo();

        assertTrue("Diagram object should be in view children after redo",
                view.getChildren().contains(diagramObject));
    }

    @Test
    public void shouldNotAffectExistingChildren_whenExecuted() {
        IDiagramModelArchimateObject existing = factory.createDiagramModelArchimateObject();
        IArchimateElement existingElem = factory.createBusinessActor();
        existingElem.setName("Existing");
        existing.setArchimateElement(existingElem);
        view.getChildren().add(existing);
        int initialCount = view.getChildren().size();

        AddToViewCommand cmd = new AddToViewCommand(diagramObject, view);
        cmd.execute();

        assertEquals("Should have one more child", initialCount + 1,
                view.getChildren().size());
    }
}
