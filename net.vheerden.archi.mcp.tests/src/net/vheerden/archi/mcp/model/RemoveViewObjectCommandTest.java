package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

/**
 * Unit tests for {@link RemoveViewObjectCommand} (groups / notes), focused on
 * undo restoring exact child order (paint/Z-order). The command shares the
 * successor-anchor restore with {@link RemoveFromViewCommand}; these tests pin
 * the group/note path independently, including the single-removal contract and
 * a compound that removes several sibling view-objects at once.
 */
public class RemoveViewObjectCommandTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
    }

    // ---- helpers ----

    private IDiagramModelGroup addGroup(String id) {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId(id);
        group.setBounds(0, 0, 120, 80);
        view.getChildren().add(group);
        return group;
    }

    private IDiagramModelNote addNote(String id) {
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId(id);
        note.setBounds(0, 0, 120, 80);
        view.getChildren().add(note);
        return note;
    }

    private List<String> orderedChildIds() {
        List<String> ids = new ArrayList<>();
        for (IDiagramModelObject child : view.getChildren()) {
            ids.add(child.getId());
        }
        return ids;
    }

    // ---- single-removal contract (regression guard for the group/note path) ----

    @Test
    public void shouldRestoreObjectAtOriginalIndex_whenSingleRemovalUndone() {
        addGroup("g0");
        IDiagramModelGroup g1 = addGroup("g1");
        addGroup("g2");
        List<String> before = orderedChildIds();

        RemoveViewObjectCommand cmd = new RemoveViewObjectCommand(g1, view);
        cmd.execute();
        assertFalse(view.getChildren().contains(g1));

        cmd.undo();
        assertEquals("Single removal restores the object at its original index",
                before, orderedChildIds());
    }

    @Test
    public void shouldRestoreTailObjectAtEnd_whenSingleTailRemovalUndone() {
        addGroup("g0");
        addGroup("g1");
        IDiagramModelGroup tail = addGroup("g2");
        List<String> before = orderedChildIds();

        RemoveViewObjectCommand cmd = new RemoveViewObjectCommand(tail, view);
        cmd.execute();
        cmd.undo();
        assertEquals("Tail removal (null successor anchor) restores at the end",
                before, orderedChildIds());
    }

    // ---- compound removal of multiple siblings: exact order preserved ----

    @Test
    public void shouldRestoreChildOrderExactly_whenAdjacentGroupsRemovedInCompound() {
        IDiagramModelGroup g0 = addGroup("g0");
        IDiagramModelGroup g1 = addGroup("g1");
        addGroup("g2");
        addGroup("g3");
        List<String> before = orderedChildIds();

        CompoundCommand compound = new CompoundCommand("Remove two groups");
        compound.add(new RemoveViewObjectCommand(g0, view));
        compound.add(new RemoveViewObjectCommand(g1, view));
        compound.execute();
        assertFalse(view.getChildren().contains(g0));
        assertFalse(view.getChildren().contains(g1));

        compound.undo();
        assertEquals("Compound undo restores exact child order for groups",
                before, orderedChildIds());
    }

    @Test
    public void shouldRestoreChildOrderExactly_whenMixedGroupsAndNotesRemovedInCompound() {
        addGroup("g0");
        IDiagramModelNote n1 = addNote("n1");
        IDiagramModelGroup g2 = addGroup("g2");
        addNote("n3");
        IDiagramModelGroup g4 = addGroup("g4");
        List<String> before = orderedChildIds();

        // Remove a note and two groups (non-adjacent), ascending index order.
        CompoundCommand compound = new CompoundCommand("Remove mixed view-objects");
        compound.add(new RemoveViewObjectCommand(n1, view));
        compound.add(new RemoveViewObjectCommand(g2, view));
        compound.add(new RemoveViewObjectCommand(g4, view));
        compound.execute();

        compound.undo();
        assertEquals("Compound undo restores exact order across mixed groups/notes",
                before, orderedChildIds());
    }

    @Test
    public void shouldRestoreChildOrderExactly_whenAllSiblingsRemovedInCompound() {
        IDiagramModelGroup g0 = addGroup("g0");
        IDiagramModelGroup g1 = addGroup("g1");
        IDiagramModelGroup g2 = addGroup("g2");
        List<String> before = orderedChildIds();

        CompoundCommand compound = new CompoundCommand("Remove all groups");
        compound.add(new RemoveViewObjectCommand(g0, view));
        compound.add(new RemoveViewObjectCommand(g1, view));
        compound.add(new RemoveViewObjectCommand(g2, view));
        compound.execute();

        compound.undo();
        assertEquals("Compound undo restores full original order when all removed",
                before, orderedChildIds());
    }
}
