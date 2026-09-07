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

/**
 * Tests for {@link AddGroupToViewCommand}.
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test execute (add to the
 * target container) and undo (remove from it). The sibling of {@code AddToViewCommandTest}: the
 * group case matters separately because a group is itself a container, so it can be both the thing
 * being added and the thing something else is added to.</p>
 */
public class AddGroupToViewCommandTest {

    private IArchimateFactory factory;
    private IArchimateDiagramModel view;
    private IDiagramModelGroup group;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        view = factory.createArchimateDiagramModel();
        view.setName("Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        group = factory.createDiagramModelGroup();
        group.setName("My Group");
        group.setBounds(20, 20, 300, 200);
    }

    @Test
    public void shouldAddGroupToView_whenExecuted() {
        AddGroupToViewCommand cmd = new AddGroupToViewCommand(group, view);

        cmd.execute();

        assertTrue("Group should be in view children", view.getChildren().contains(group));
    }

    @Test
    public void shouldRemoveGroupFromView_whenUndone() {
        AddGroupToViewCommand cmd = new AddGroupToViewCommand(group, view);
        cmd.execute();

        cmd.undo();

        assertFalse("Group should be gone from view children",
                view.getChildren().contains(group));
    }

    @Test
    public void shouldRestoreGroupToView_whenRedoneAfterUndo() {
        AddGroupToViewCommand cmd = new AddGroupToViewCommand(group, view);
        cmd.execute();
        cmd.undo();

        cmd.execute();

        assertTrue("Group should be back in view children",
                view.getChildren().contains(group));
        assertEquals("and only once", 1, view.getChildren().size());
    }

    /** A group may be nested inside another group, so the parent is a container, not just a view. */
    @Test
    public void shouldAddGroupToParentGroup_whenParentIsAGroup() {
        IDiagramModelGroup outer = factory.createDiagramModelGroup();
        outer.setName("Outer");
        outer.setBounds(0, 0, 600, 400);
        view.getChildren().add(outer);

        AddGroupToViewCommand cmd = new AddGroupToViewCommand(group, outer);
        cmd.execute();

        assertTrue("Group should be nested in the outer group",
                outer.getChildren().contains(group));
        assertFalse("and not attached directly to the view",
                view.getChildren().contains(group));
        assertSame("its container must be the outer group", outer, group.eContainer());
    }

    @Test
    public void shouldExposeGroupAndParent_forQueueInspection() {
        AddGroupToViewCommand cmd = new AddGroupToViewCommand(group, view);

        assertSame("the group is readable before execute", group, cmd.getGroup());
        assertSame("so is its destined parent", view, cmd.getParent());
    }

    @Test
    public void shouldLabelItselfWithTheGroupName_whenConstructed() {
        AddGroupToViewCommand cmd = new AddGroupToViewCommand(group, view);

        assertEquals("Add group 'My Group' to view", cmd.getLabel());
    }
}
