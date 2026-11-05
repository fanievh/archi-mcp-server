package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

/**
 * When {@link DeleteFolderCommand} vouches that it changed nothing: only a decline on contents it was
 * not prepared for, which comes before any sub-command runs. A decline after the sub-commands ran is
 * not vouched for.
 */
public class DeleteFolderCommandTest {

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IFolder parent;
    private IFolder ops;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-delete-folder");
        model.setDefaults();
        parent = model.getFolder(FolderType.BUSINESS);
        ops = folder(parent, "ops");
    }

    @Test
    public void shouldCertifyNothingChanged_whenContentArrivedBeforeItRan_andNotOnceItRunsNormally() {
        DeleteFolderCommand delete = new DeleteFolderCommand(ops, parent, 0, List.of());
        assertFalse("not before execute", delete.changedNothing());
        IArchimateElement late = actor(ops, "late");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        delete.execute();

        assertNotNull("fixture check: the delete declined", delete.getSkipReason());
        assertTrue(delete.changedNothing());
        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));

        ops.getElements().remove(late);
        delete.execute();

        assertNull("fixture check: the delete ran", delete.getSkipReason());
        assertFalse("fixture check: the folder went", parent.getFolders().contains(ops));
        assertFalse("a run that deleted certifies nothing", delete.changedNothing());
    }

    @Test
    public void shouldCertifyNothingChanged_whenARedoFindsContentItWasNotPreparedFor() {
        DeleteFolderCommand delete = new DeleteFolderCommand(ops, parent, 0, List.of());
        delete.execute();
        delete.undo();
        actor(ops, "late");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        delete.redo();

        assertNotNull("fixture check: the redo declined", delete.getSkipReason());
        assertTrue(delete.changedNothing());
        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    @Test
    public void shouldNotCertify_whenASubfolderDeclinedAfterTheContentsWent() {
        IArchimateElement gone = actor(ops, "gone");
        IFolder inner = folder(ops, "inner");
        DeleteFolderCommand delete = new DeleteFolderCommand(ops, parent, 0,
                List.of(remove(ops, gone), new DeleteFolderCommand(inner, ops, 0, List.of())));
        actor(inner, "late");

        delete.execute();

        assertNotNull("fixture check: the delete declined", delete.getSkipReason());
        assertFalse("fixture check: the contents went first", ops.getElements().contains(gone));
        assertFalse(delete.changedNothing());
    }

    private static Command remove(IFolder folder, IArchimateElement element) {
        int index = folder.getElements().indexOf(element);
        return new Command("Remove " + element.getId()) {
            @Override
            public void execute() {
                folder.getElements().remove(element);
            }

            @Override
            public void undo() {
                folder.getElements().add(index, element);
            }
        };
    }

    private IFolder folder(IFolder container, String id) {
        IFolder folder = factory.createFolder();
        folder.setId(id);
        folder.setName(id);
        container.getFolders().add(folder);
        return folder;
    }

    private IArchimateElement actor(IFolder folder, String id) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        element.setName(id);
        folder.getElements().add(element);
        return element;
    }
}
