package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.model.RequireAttachedContainerCommand.Wording;

/**
 * When {@link RequireAttachedContainerCommand} vouches that it changed nothing: when it declined
 * itself, or when it ran and the guard it wraps declined (two endpoint guards nested, as for a
 * connection). A guard that wraps a plain compound never vouches for it. And the caller's own check,
 * asked only once the container is attached, declines the guarded command before it runs.
 */
public class RequireAttachedContainerCommandTest {

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IFolder business;
    private IArchimateDiagramModel view;
    private IDiagramModelArchimateObject source;
    private IDiagramModelArchimateObject target;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-guard");
        model.setDefaults();
        business = model.getFolder(FolderType.BUSINESS);
        view = factory.createArchimateDiagramModel();
        view.setId("view");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        source = box("source");
        target = box("target");
    }

    @Test
    public void shouldCertifyNothingChanged_whenItsContainerWent_andNotOnceItRunsNormally() {
        IFolder ops = factory.createFolder();
        ops.setId("ops");
        business.getFolders().add(ops);
        RequireAttachedContainerCommand guard = new RequireAttachedContainerCommand(add(ops, "made"), ops, model,
                "made", "folder", Wording.CREATE_IN_FOLDER);
        assertFalse("not before execute", guard.changedNothing());
        business.getFolders().remove(ops);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        guard.execute();

        assertNotNull("fixture check: the guard declined", guard.getSkipReason());
        assertTrue(guard.changedNothing());
        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));

        business.getFolders().add(ops);
        guard.execute();

        assertEquals("fixture check: the create ran", 1, ops.getElements().size());
        assertFalse("a run that created certifies nothing", guard.changedNothing());
    }

    @Test
    public void shouldCertifyNothingChanged_whenARedoFindsItsContainerGone() {
        RequireAttachedContainerCommand guard = endpointGuard(add(business, "made"), target);
        guard.execute();
        guard.undo();
        view.getChildren().remove(target);

        guard.redo();

        assertNotNull("fixture check: the redo declined", guard.getSkipReason());
        assertTrue(guard.changedNothing());
    }

    @Test
    public void shouldCertifyNothingChanged_whenTheInnerEndpointGuardDeclined() {
        RequireAttachedContainerCommand inner = endpointGuard(add(business, "made"), target);
        RequireAttachedContainerCommand outer = endpointGuard(inner, source);
        view.getChildren().remove(target);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        outer.execute();

        assertNull("the outer guard ran", outer.getSkipReason());
        assertNotNull("fixture check: the inner guard declined", inner.getSkipReason());
        assertTrue(outer.changedNothing());
        assertEquals(List.of(inner.getSkipReason()), CommitSkippableCommand.collectSkipReasons(outer));
        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    @Test
    public void shouldCertifyNothingChanged_whenTheOuterEndpointGuardDeclined() {
        RequireAttachedContainerCommand inner = endpointGuard(add(business, "made"), target);
        RequireAttachedContainerCommand outer = endpointGuard(inner, source);
        view.getChildren().remove(source);

        outer.execute();

        assertNotNull("fixture check: the outer guard declined", outer.getSkipReason());
        assertTrue(outer.changedNothing());
    }

    @Test
    public void shouldNotCertify_whenItWrapsAPlainCompoundWhoseOtherChildWrote() {
        CompoundCommand compound = new CompoundCommand("place");
        compound.add(endpointGuard(add(business, "declined"), target));
        compound.add(add(business, "written"));
        RequireAttachedContainerCommand outer = endpointGuard(compound, source);
        view.getChildren().remove(target);

        outer.execute();

        assertNotNull("fixture check: the other child wrote",
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, "written"));
        assertFalse(outer.changedNothing());
    }

    // ---- the caller's own check

    @Test
    public void shouldDeclineBeforeTheGuardedCommandRuns_whenTheCallersCheckGivesAReason() {
        Recording guarded = new Recording();
        RequireAttachedContainerCommand guard = folderGuard(guarded, () -> "the caller's reason");

        guard.execute();

        assertEquals("never ran", 0, guarded.executed);
        assertEquals("the caller's reason", guard.getSkipReason());
        assertTrue(guard.changedNothing());
        guard.undo();
        assertEquals("undo reverses nothing", 0, guarded.undone);
    }

    @Test
    public void shouldRunTheGuardedCommand_whenTheCallersCheckGivesNoReason() {
        Recording guarded = new Recording();
        RequireAttachedContainerCommand guard = folderGuard(guarded, () -> null);

        guard.execute();

        assertEquals("ran", 1, guarded.executed);
        assertNull(guard.getSkipReason());
        assertFalse(guard.changedNothing());
    }

    @Test
    public void shouldGiveTheContainersReason_andNotAskTheCallersCheck_whenBothWouldDecline() {
        Recording guarded = new Recording();
        AtomicInteger asked = new AtomicInteger();
        RequireAttachedContainerCommand guard = folderGuard(guarded, () -> {
            asked.incrementAndGet();
            return "the caller's reason";
        });
        model.getFolder(FolderType.DIAGRAMS).getFolders().remove(views);

        guard.execute();

        assertEquals("never ran", 0, guarded.executed);
        assertTrue("the container's reason: " + guard.getSkipReason(),
                guard.getSkipReason().startsWith("'made' was not created: the folder 'Views' it was to be added to "
                        + "was removed from the model"));
        assertEquals("the caller's check was not asked", 0, asked.get());
        assertTrue(guard.changedNothing());
    }

    @Test
    public void shouldStayDeclinedOnRedo_withoutAskingAgain_whenExecuteDeclinedOnTheCallersCheck() {
        Recording guarded = new Recording();
        String[] answer = {"the caller's reason"};
        AtomicInteger asked = new AtomicInteger();
        RequireAttachedContainerCommand guard = folderGuard(guarded, () -> {
            asked.incrementAndGet();
            return answer[0];
        });
        guard.execute();
        guard.undo();
        answer[0] = null;

        guard.redo();

        assertEquals("not redone", 0, guarded.redone);
        assertEquals("asked on execute only", 1, asked.get());
        assertEquals("the caller's reason", guard.getSkipReason());
        assertTrue(guard.changedNothing());
    }

    @Test
    public void shouldRedo_withoutAskingAgain_whenExecuteRanPastTheCallersCheck() {
        Recording guarded = new Recording();
        String[] answer = {null};
        AtomicInteger asked = new AtomicInteger();
        RequireAttachedContainerCommand guard = folderGuard(guarded, () -> {
            asked.incrementAndGet();
            return answer[0];
        });
        guard.execute();
        guard.undo();
        answer[0] = "the caller's reason";

        guard.redo();

        assertEquals("redone", 1, guarded.redone);
        assertEquals("asked on execute only", 1, asked.get());
        assertNull(guard.getSkipReason());
        assertFalse(guard.changedNothing());
    }

    @Test
    public void shouldStillReCheckTheContainerOnRedo_whenExecuteRanPastTheCallersCheck() {
        Recording guarded = new Recording();
        RequireAttachedContainerCommand guard = folderGuard(guarded, () -> null);
        guard.execute();
        guard.undo();
        model.getFolder(FolderType.DIAGRAMS).getFolders().remove(views);

        guard.redo();

        assertEquals("not redone", 0, guarded.redone);
        assertTrue(guard.getSkipReason(), guard.getSkipReason().startsWith("'made' was not created: the folder"));
        assertTrue(guard.changedNothing());
    }

    private IFolder views;

    private RequireAttachedContainerCommand folderGuard(Command guarded, Supplier<String> alsoDecline) {
        views = factory.createFolder();
        views.setId("views");
        views.setName("Views");
        model.getFolder(FolderType.DIAGRAMS).getFolders().add(views);
        return new RequireAttachedContainerCommand(guarded, views, model, "made", "folder",
                Wording.CREATE_IN_FOLDER, alsoDecline);
    }

    /** Counts what the guard asks of the command it wraps. */
    private static final class Recording extends Command {
        int executed;
        int undone;
        int redone;

        @Override
        public void execute() {
            executed++;
        }

        @Override
        public void undo() {
            undone++;
        }

        @Override
        public void redo() {
            redone++;
        }
    }

    private RequireAttachedContainerCommand endpointGuard(Command guarded, IDiagramModelArchimateObject endpoint) {
        return new RequireAttachedContainerCommand(guarded, endpoint, model, "connection", "view object",
                Wording.CONNECT_ENDPOINT);
    }

    private IDiagramModelArchimateObject box(String id) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId("element-" + id);
        business.getElements().add(element);
        IDiagramModelArchimateObject box = factory.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element);
        view.getChildren().add(box);
        return box;
    }

    private Command add(IFolder folder, String id) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        return new Command("Add " + id) {
            @Override
            public void execute() {
                folder.getElements().add(element);
            }

            @Override
            public void undo() {
                folder.getElements().remove(element);
            }
        };
    }
}
