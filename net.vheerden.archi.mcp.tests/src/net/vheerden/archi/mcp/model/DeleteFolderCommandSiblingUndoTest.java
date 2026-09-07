package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

/**
 * Pins undo restoration ORDER for {@link DeleteFolderCommand} when several empty
 * sibling subfolders are deleted inside one GEF {@link CompoundCommand}.
 *
 * <p>Undo re-inserts each folder into its parent's subfolder list at the captured
 * absolute index. Under reverse compound undo an earlier-listed delete is not yet
 * back, so the index overshoots and the subfolder order is silently re-shuffled.
 * A separate test proves the {@code CommitSkippableCommand} decline path still
 * undoes correctly with the anchor in place. No {@code CommandStack}.</p>
 */
public class DeleteFolderCommandSiblingUndoTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder parent;
    private IFolder f1;
    private IFolder f2;
    private IFolder f3;
    private IFolder f4;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;

        model = factory.createArchimateModel();
        model.setName("Folder Order Fixture");
        model.setId("model-folder-order");
        model.setDefaults();

        IFolder business = model.getFolder(FolderType.BUSINESS);
        parent = factory.createFolder();
        parent.setName("Parent");
        business.getFolders().add(parent);

        f1 = newSubfolder("f1");
        f2 = newSubfolder("f2");
        f3 = newSubfolder("f3");
        f4 = newSubfolder("f4");
    }

    private IFolder newSubfolder(String id) {
        IFolder f = factory.createFolder();
        f.setId(id);
        f.setName(id);
        parent.getFolders().add(f);
        return f;
    }

    private DeleteFolderCommand deleteOf(IFolder folder) {
        int index = parent.getFolders().indexOf(folder);
        return new DeleteFolderCommand(folder, parent, index, List.of());
    }

    private List<String> subfolderIds() {
        List<String> ids = new ArrayList<>();
        for (IFolder f : parent.getFolders()) {
            ids.add(f.getId());
        }
        return ids;
    }

    @Test
    public void shouldRestoreSubfolderOrder_whenCompoundDeletesThreeSiblings() {
        CompoundCommand compound = new CompoundCommand("Delete three folders");
        compound.add(deleteOf(f1));
        compound.add(deleteOf(f2));
        compound.add(deleteOf(f3));

        compound.execute();
        assertEquals("Only f4 survives execute", List.of("f4"), subfolderIds());

        compound.undo();

        assertTrue("f1 restored", parent.getFolders().contains(f1));
        assertTrue("f2 restored", parent.getFolders().contains(f2));
        assertTrue("f3 restored", parent.getFolders().contains(f3));
        assertEquals("All four subfolders present after undo", 4, parent.getFolders().size());

        assertEquals("Subfolder order must be restored exactly after compound undo",
                List.of("f1", "f2", "f3", "f4"), subfolderIds());
    }

    @Test
    public void shouldRestoreAtOriginalIndex_whenSingleFolderDeleted() {
        DeleteFolderCommand cmd = deleteOf(f2);

        cmd.execute();
        assertEquals("f2 removed", List.of("f1", "f3", "f4"), subfolderIds());

        cmd.undo();
        assertEquals("Single folder delete restores at the exact original index",
                List.of("f1", "f2", "f3", "f4"), subfolderIds());
    }

    /**
     * The skip/undo split introduced for the deferred-path force guard must keep
     * working with the successor anchor in place: a folder that gains an
     * unprepared child after prepare declines to run, and undo of a declined
     * command is a no-op that leaves the parent's subfolder list untouched.
     */
    @Test
    public void shouldNotRestore_whenDeleteDeclinedForUnpreparedContent() {
        DeleteFolderCommand cmd = deleteOf(f2);
        // Simulate a co-queued create landing inside f2 AFTER this command was prepared.
        f2.getElements().add(factory.createBusinessActor());

        cmd.execute();
        assertTrue("Declined delete reports a skip reason", cmd.getSkipReason() != null);
        assertEquals("Declined delete leaves the subfolder list untouched",
                List.of("f1", "f2", "f3", "f4"), subfolderIds());

        cmd.undo();
        assertEquals("Undo of a declined delete must not re-insert or re-order anything",
                List.of("f1", "f2", "f3", "f4"), subfolderIds());
    }
}
