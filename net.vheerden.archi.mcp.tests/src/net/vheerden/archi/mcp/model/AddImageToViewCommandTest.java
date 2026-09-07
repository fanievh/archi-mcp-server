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
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IFolder;

/**
 * Tests for {@link AddImageToViewCommand}.
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (add to container) and undo (remove from container) behaviour
 * on a standalone {@code IDiagramModelImage} visual. Runs as a JUnit
 * Plug-in Test (needs the EMF runtime). Mirrors
 * {@code AddViewReferenceToViewCommandTest}.</p>
 */
public class AddImageToViewCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IArchimateDiagramModel targetView;
    private IDiagramModelImage imageVisual;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();
        IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);

        targetView = factory.createArchimateDiagramModel();
        targetView.setName("Target");
        diagramsFolder.getElements().add(targetView);

        imageVisual = factory.createDiagramModelImage();
        imageVisual.setImagePath("images/test.png");
        imageVisual.setBounds(50, 50, 64, 64);
    }

    @Test
    public void shouldAddImageVisualToView_topLevel() {
        AddImageToViewCommand cmd =
                new AddImageToViewCommand(imageVisual, targetView);

        cmd.execute();

        assertEquals("Target view should contain the image visual",
                1, targetView.getChildren().size());
        assertSame("Child is the image visual",
                imageVisual, targetView.getChildren().get(0));
        assertEquals("setImagePath preserved through execute",
                "images/test.png", imageVisual.getImagePath());
        assertEquals("Bounds preserved through execute",
                50, imageVisual.getBounds().getX());
    }

    @Test
    public void shouldUndoAddImageVisual() {
        AddImageToViewCommand cmd =
                new AddImageToViewCommand(imageVisual, targetView);
        cmd.execute();

        cmd.undo();

        assertFalse("Image visual should be removed after undo",
                targetView.getChildren().contains(imageVisual));
        assertTrue("View should have no children after undo",
                targetView.getChildren().isEmpty());
    }

    @Test
    public void shouldNestImageVisualInGroup() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setName("Container group");
        group.setBounds(0, 0, 300, 200);
        targetView.getChildren().add(group);

        AddImageToViewCommand cmd =
                new AddImageToViewCommand(imageVisual, group);
        cmd.execute();

        assertEquals("Group should contain the image visual",
                1, group.getChildren().size());
        assertSame("Group's child is the image visual",
                imageVisual, group.getChildren().get(0));
        assertEquals("Top-level view still has 1 child (the group)",
                1, targetView.getChildren().size());
        assertSame(group, targetView.getChildren().get(0));

        cmd.undo();
        assertTrue("Image visual removed from group on undo",
                group.getChildren().isEmpty());
        assertEquals("View-level structure preserved after undo",
                1, targetView.getChildren().size());
    }
}
