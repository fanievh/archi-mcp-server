package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

/**
 * Tests for {@link AddViewReferenceToViewCommand}.
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (add to container) and undo (remove from container) behavior.
 * Runs as a JUnit Plug-in Test (needs the EMF runtime).</p>
 */
public class AddViewReferenceToViewCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IArchimateDiagramModel targetView;
    private IArchimateDiagramModel referencedView;
    private IDiagramModelReference viewRef;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();
        IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);

        targetView = factory.createArchimateDiagramModel();
        targetView.setName("Target");
        diagramsFolder.getElements().add(targetView);

        referencedView = factory.createArchimateDiagramModel();
        referencedView.setName("Referenced");
        diagramsFolder.getElements().add(referencedView);

        viewRef = factory.createDiagramModelReference();
        viewRef.setReferencedModel(referencedView);
        viewRef.setBounds(100, 200, 185, 80);
    }

    @Test
    public void shouldAddViewReferenceToContainerOnExecute() {
        AddViewReferenceToViewCommand cmd =
                new AddViewReferenceToViewCommand(viewRef, targetView);

        cmd.execute();

        assertEquals("Target view should contain the view-reference",
                1, targetView.getChildren().size());
        assertSame("Child is the view-reference",
                viewRef, targetView.getChildren().get(0));
        assertSame("setReferencedModel preserved through execute",
                referencedView, viewRef.getReferencedModel());
    }

    @Test
    public void shouldRemoveViewReferenceFromContainerOnUndo() {
        AddViewReferenceToViewCommand cmd =
                new AddViewReferenceToViewCommand(viewRef, targetView);
        cmd.execute();

        cmd.undo();

        assertFalse("View-reference should be removed after undo",
                targetView.getChildren().contains(viewRef));
        assertTrue("View should have no children after undo",
                targetView.getChildren().isEmpty());
    }

    @Test
    public void shouldPreserveOrderInContainerChildren() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setName("Existing group");
        group.setBounds(0, 0, 300, 200);
        targetView.getChildren().add(group);

        AddViewReferenceToViewCommand cmd =
                new AddViewReferenceToViewCommand(viewRef, targetView);
        cmd.execute();

        assertEquals(2, targetView.getChildren().size());
        assertSame("Existing group remains at index 0",
                group, targetView.getChildren().get(0));
        assertSame("View-reference appended at end",
                viewRef, targetView.getChildren().get(1));
    }
}
