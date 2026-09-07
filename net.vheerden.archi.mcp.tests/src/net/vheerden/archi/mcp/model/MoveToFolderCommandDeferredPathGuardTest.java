package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.ISketchModel;

import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;

/**
 * Pins the execute-time re-checks of {@code move-to-folder}'s three live-tree guards on
 * {@link MoveToFolderCommand}: the circular-reference guard, the view-hierarchy guard, and the
 * layer guard.
 *
 * <p>{@code move-to-folder} validates all three preconditions at prepare time by reading the live
 * folder tree. On the deferred paths — a queued batch or a bulk request — every operation is
 * prepared before any runs, so an earlier co-queued move can re-parent the folders and turn a
 * valid-at-prepare move invalid by the time it executes:</p>
 * <ul>
 *   <li>a cycle silently detaches the whole subtree from the model root (measured: no exception,
 *       all folders unreachable);</li>
 *   <li>a view landed outside the Views (DIAGRAMS) hierarchy, or a concept landed under the wrong
 *       layer root, leaves the model unsaveable — host Archi's save-time checkIntegrity refuses it
 *       (measured: no exception, the object is simply mis-filed).</li>
 * </ul>
 *
 * <p>The command must re-check each guard at execution time and decline rather than corrupt. Drives
 * the ordered pairs through a plain {@link CompoundCommand} (no {@code CommandStack}, no
 * {@code NonNotifyingCompoundCommand}) so the execution order that triggers the violation is real.</p>
 */
public class MoveToFolderCommandDeferredPathGuardTest {

    private IArchimateFactory factory;
    private IArchimateModel model;
    private IFolder business;
    private IFolder a;
    private IFolder b;
    private IFolder sub;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setName("Move Cycle Fixture");
        model.setId("model-move-cycle");
        model.setDefaults();

        business = model.getFolder(FolderType.BUSINESS);
        a = newFolder("A");
        b = newFolder("B");
        business.getFolders().add(a);
        business.getFolders().add(b);
        sub = newFolder("sub");
        a.getFolders().add(sub); // A/sub
    }

    private IFolder newFolder(String id) {
        IFolder f = factory.createFolder();
        f.setName(id);
        f.setId(id);
        return f;
    }

    private MoveToFolderCommand moveFolder(IFolder folder, IFolder source, IFolder target) {
        int index = source.getFolders().indexOf(folder);
        return new MoveToFolderCommand(folder, true, source, index, target);
    }

    private MoveToFolderCommand moveObject(EObject obj, IFolder source, IFolder target) {
        int index = source.getElements().indexOf(obj);
        return new MoveToFolderCommand(obj, false, source, index, target);
    }

    /** Walk up from the object's container to its root folder and return that root's type. */
    private FolderType rootTypeOf(EObject obj) {
        IFolder f = (IFolder) obj.eContainer();
        while (f.eContainer() instanceof IFolder parent) {
            f = parent;
        }
        return f.getType();
    }

    /** Walk DOWN from the model root with a visited guard so a cycle cannot loop forever. */
    private boolean reachableFromRoot(IFolder target) {
        Set<IFolder> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<IFolder> stack = new ArrayDeque<>();
        for (IFolder root : model.getFolders()) {
            stack.push(root);
        }
        while (!stack.isEmpty()) {
            IFolder f = stack.pop();
            if (!visited.add(f)) {
                continue;
            }
            if (f == target) {
                return true;
            }
            for (IFolder c : f.getFolders()) {
                stack.push(c);
            }
        }
        return false;
    }

    // ---- Core: the deferred cycle declines and the subtree survives ----

    @Test
    public void cycleGuard_declinesAndKeepsSubtreeReachable_whenPairClosesCycle() {
        // op0 = move A -> B ; op1 = move B -> A/sub. Each is acyclic at its own prepare; together
        // they close the cycle B -> A -> sub -> B. Without the execute-time guard the whole subtree
        // detaches from the model (measured).
        MoveToFolderCommand op0 = moveFolder(a, business, b);
        MoveToFolderCommand op1 = moveFolder(b, business, sub);

        CompoundCommand pair = new CompoundCommand("pair");
        pair.add(op0);
        pair.add(op1);
        pair.execute();

        // The data-loss assertion: nothing detached. All three folders still reachable from root.
        assertTrue("A must stay reachable from the model root", reachableFromRoot(a));
        assertTrue("B must stay reachable from the model root", reachableFromRoot(b));
        assertTrue("A/sub must stay reachable from the model root", reachableFromRoot(sub));

        // op0 (A -> B) is legitimate and applied; op1 (B -> A/sub) is the one that would cycle.
        assertNull("The acyclic first move must not be declined", op0.getSkipReason());
        assertNotNull("The move that would close a cycle must decline", op1.getSkipReason());
        assertTrue("B must remain where it was (not moved into its own descendant)",
                business.getFolders().contains(b));
        assertTrue("A was legitimately moved under B", b.getFolders().contains(a));
    }

    @Test
    public void cycleGuard_declines_whenReverseOrderClosesCycle() {
        // op0 = move B -> A/sub ; op1 = move A -> B closes the same cycle from the other side.
        MoveToFolderCommand op0 = moveFolder(b, business, sub);
        MoveToFolderCommand op1 = moveFolder(a, business, b);

        CompoundCommand pair = new CompoundCommand("pair");
        pair.add(op0);
        pair.add(op1);
        pair.execute();

        assertTrue("A must stay reachable from the model root", reachableFromRoot(a));
        assertTrue("B must stay reachable from the model root", reachableFromRoot(b));
        assertTrue("A/sub must stay reachable from the model root", reachableFromRoot(sub));

        assertNull("The acyclic first move must not be declined", op0.getSkipReason());
        assertNotNull("The move that would close a cycle must decline", op1.getSkipReason());
        assertTrue("A must remain where it was", business.getFolders().contains(a));
        assertTrue("B was legitimately moved under A/sub", sub.getFolders().contains(b));
    }

    // ---- Undo/redo coherence around a decline ----

    @Test
    public void undoAfterDecline_isInert() {
        // A direct cyclic move (A into its own child A/sub) declines; undo must not re-insert A into
        // a folder it never left.
        MoveToFolderCommand mv = moveFolder(a, business, sub);
        boolean businessHadABefore = business.getFolders().contains(a);
        boolean subHadABefore = sub.getFolders().contains(a);

        mv.execute();
        assertNotNull("A cyclic move must decline", mv.getSkipReason());

        mv.undo();
        assertEquals("undo of a declined move must not change source membership",
                businessHadABefore, business.getFolders().contains(a));
        assertEquals("undo of a declined move must not insert into the target",
                subHadABefore, sub.getFolders().contains(a));
        assertTrue("A is still under business, untouched", business.getFolders().contains(a));
    }

    @Test
    public void legitimateMove_executesUndoesAndRedoes() {
        // A plain acyclic move must still work end to end, and must not report a skip.
        MoveToFolderCommand mv = moveFolder(a, business, b);

        mv.execute();
        assertNull("A legitimate move must not decline", mv.getSkipReason());
        assertTrue("A moved under B", b.getFolders().contains(a));
        assertFalse("A left business", business.getFolders().contains(a));

        mv.undo();
        assertTrue("undo returns A to business", business.getFolders().contains(a));
        assertFalse("undo removes A from B", b.getFolders().contains(a));

        mv.redo();
        assertNull("redo re-checks and still finds the move safe", mv.getSkipReason());
        assertTrue("redo re-applies the move", b.getFolders().contains(a));
        assertFalse("redo removes A from business again", business.getFolders().contains(a));
    }

    // ---- Reporting: a bare top-level MoveToFolderCommand surfaces on both wires ----

    @Test
    public void batchSummary_namesTheDeclinedMove() {
        // move-to-folder queues ONE bare command (not a compound), so it is a top-level queue entry.
        // The batch collector must name it with no recursion needed.
        MutationContext context = new MutationContext();
        MoveToFolderCommand mv = moveFolder(a, business, sub); // cyclic: A into its own child

        context.beginBatch("batch");
        context.queueCommand(mv, "Move folder A to A/sub");
        mv.execute(); // simulate dispatch — the command declines and records its reason

        BatchSummaryDto summary = context.buildCommitSummary();
        assertNotNull("A declined bare move must surface in the batch summary",
                summary.skippedOperations());
        assertEquals(1, summary.skippedOperations().size());
        assertTrue("The skip is paired with the operation's own description",
                summary.skippedOperations().get(0).startsWith("Move folder A to A/sub — "));
    }

    @Test
    public void declineMessage_isReadable_whenFolderHasNoName() {
        // The skip reason is agent-facing; an unnamed folder must not render "Folder 'null'".
        IFolder unnamed = factory.createFolder();
        unnamed.setId("unnamed");
        // no setName -> getName() is null
        business.getFolders().add(unnamed);
        IFolder child = newFolder("child");
        unnamed.getFolders().add(child);

        // Move the unnamed folder into its own child -> cyclic -> declines.
        MoveToFolderCommand mv = moveFolder(unnamed, business, child);
        mv.execute();

        assertNotNull("A cyclic move must decline", mv.getSkipReason());
        assertFalse("Skip reason must not render a null folder name",
                mv.getSkipReason().contains("'null'"));
        assertTrue("Unnamed folder is described with a readable placeholder",
                mv.getSkipReason().contains("(unnamed folder)"));
    }

    @Test
    public void bulkCollector_namesTheDeclinedMove() {
        // bulk-mutate groups skips per operation (direct child of the dispatch compound). A declined
        // bare move is one operation and must yield exactly one line.
        MoveToFolderCommand op0 = moveFolder(a, business, b);
        MoveToFolderCommand op1 = moveFolder(b, business, sub);
        CompoundCommand dispatch = new CompoundCommand("bulk");
        dispatch.add(op0);
        dispatch.add(op1);
        dispatch.execute();

        List<String> perOperation = CommitSkippableCommand.collectSkipReasonsByOperation(dispatch);
        assertEquals("Exactly one operation declined", 1, perOperation.size());
        assertTrue("The declined operation's reason is reported",
                perOperation.get(0).contains("was not moved into"));
    }

    // ---- Guard 2: view-hierarchy — a view must not land outside the Views (DIAGRAMS) hierarchy --

    private IDiagramModel newView(String id) {
        IDiagramModel v = factory.createArchimateDiagramModel();
        v.setName(id);
        v.setId(id);
        return v;
    }

    @Test
    public void viewGuard_declinesAndKeepsViewInsideViews_whenTargetReparentedOut() {
        // op0 = move F out of Views into Business ; op1 = move view V into F. At op1's own prepare F
        // is still under Views, so the move looks legal; by the time it executes op0 has re-parented
        // F under Business, and landing V there would file it outside Views -> unsaveable.
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        IFolder f = newFolder("F-view");
        diagrams.getFolders().add(f);
        IDiagramModel v = newView("V");
        diagrams.getElements().add(v);

        MoveToFolderCommand op0 = moveFolder(f, diagrams, business);
        MoveToFolderCommand op1 = moveObject(v, diagrams, f);
        CompoundCommand pair = new CompoundCommand("pair");
        pair.add(op0);
        pair.add(op1);
        pair.execute();

        assertNull("The folder move out of Views is legitimate and must not decline",
                op0.getSkipReason());
        assertNotNull("Moving a view under a now-non-Views folder must decline", op1.getSkipReason());
        assertEquals("The view must stay within the Views hierarchy", FolderType.DIAGRAMS,
                rootTypeOf(v));
        assertTrue("The view stays in its original Views folder", diagrams.getElements().contains(v));
        assertTrue("The skip reason names the offending view", op1.getSkipReason().contains("View 'V'"));
    }

    @Test
    public void viewGuard_covers_sketchViews_notOnlyArchimateViews() {
        // A sketch view is an IDiagramModel but NOT an IArchimateDiagramModel. Archi governs it
        // under the Views root all the same, so the guard must cover it — otherwise a sketch moved
        // out of Views on a deferred path leaves the model unsaveable.
        IFolder fUnderBusiness = newFolder("F-sketch");
        business.getFolders().add(fUnderBusiness);
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        ISketchModel sketch = factory.createSketchModel();
        sketch.setName("Sketch");
        sketch.setId("Sketch");
        diagrams.getElements().add(sketch);

        MoveToFolderCommand mv = moveObject(sketch, diagrams, fUnderBusiness);
        mv.execute();

        assertNotNull("A sketch view move out of Views must decline", mv.getSkipReason());
        assertEquals("The sketch view is left within Views", FolderType.DIAGRAMS, rootTypeOf(sketch));
    }

    @Test
    public void viewGuard_declines_whenTreeAlreadyStale_orderIndependent() {
        // The execute-time check reads the live tree, so it declines whether the target was
        // re-parented by a co-queued op or was already outside Views before this move ran.
        IFolder fUnderBusiness = newFolder("F-biz");
        business.getFolders().add(fUnderBusiness); // already outside Views
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        IDiagramModel v = newView("V2");
        diagrams.getElements().add(v);

        MoveToFolderCommand mv = moveObject(v, diagrams, fUnderBusiness);
        mv.execute();

        assertNotNull("A view move into an out-of-Views folder must decline", mv.getSkipReason());
        assertEquals("The view is left within Views", FolderType.DIAGRAMS, rootTypeOf(v));
    }

    @Test
    public void viewGuard_batchSummary_namesTheDeclinedMove() {
        IFolder fUnderBusiness = newFolder("F-biz2");
        business.getFolders().add(fUnderBusiness);
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        IDiagramModel v = newView("V3");
        diagrams.getElements().add(v);

        MutationContext context = new MutationContext();
        MoveToFolderCommand mv = moveObject(v, diagrams, fUnderBusiness);
        context.beginBatch("batch");
        context.queueCommand(mv, "Move view V3 out of Views");
        mv.execute();

        BatchSummaryDto summary = context.buildCommitSummary();
        assertNotNull("A declined view move must surface in the batch summary",
                summary.skippedOperations());
        assertEquals(1, summary.skippedOperations().size());
        assertTrue("The skip is paired with the operation description",
                summary.skippedOperations().get(0).startsWith("Move view V3 out of Views — "));
    }

    @Test
    public void viewGuard_undoAfterDecline_isInert_andLegitimateMoveStillWorks() {
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        IFolder viewsSub = newFolder("Views-sub");
        diagrams.getFolders().add(viewsSub); // a legal target inside Views
        IFolder fUnderBusiness = newFolder("F-biz3");
        business.getFolders().add(fUnderBusiness);
        IDiagramModel v = newView("V4");
        diagrams.getElements().add(v);

        // Declined move leaves membership untouched; undo is a no-op.
        MoveToFolderCommand declined = moveObject(v, diagrams, fUnderBusiness);
        declined.execute();
        assertNotNull("Move outside Views declines", declined.getSkipReason());
        declined.undo();
        assertTrue("undo of a declined view move does not disturb the view",
                diagrams.getElements().contains(v));
        assertFalse("undo of a declined view move does not insert it into the target",
                fUnderBusiness.getElements().contains(v));

        // A legitimate move within Views still executes/undoes/redoes.
        MoveToFolderCommand legit = moveObject(v, diagrams, viewsSub);
        legit.execute();
        assertNull("A move within Views is legitimate", legit.getSkipReason());
        assertTrue("View moved into the Views subfolder", viewsSub.getElements().contains(v));
        legit.undo();
        assertTrue("undo returns the view to its Views folder", diagrams.getElements().contains(v));
        legit.redo();
        assertNull("redo re-checks and still finds the move legal", legit.getSkipReason());
        assertTrue("redo re-applies the legitimate move", viewsSub.getElements().contains(v));
    }

    // ---- Guard 3: layer — a concept must not land under a folder of the wrong ArchiMate layer ----

    @Test
    public void layerGuard_declinesAndKeepsConceptOnLayer_whenTargetReparentedOffLayer() {
        // op0 = move F from Business to Application ; op1 = move a Business element E into F. At
        // op1's own prepare F is Business-rooted, so the layer check passes; by execute op0 has
        // re-rooted F under Application, and landing E there mis-files it -> unsaveable.
        IFolder application = model.getFolder(FolderType.APPLICATION);
        IFolder f = newFolder("F-layer");
        business.getFolders().add(f);
        IArchimateElement e = factory.createBusinessActor();
        e.setName("E");
        e.setId("E");
        business.getElements().add(e);

        MoveToFolderCommand op0 = moveFolder(f, business, application);
        MoveToFolderCommand op1 = moveObject(e, business, f);
        CompoundCommand pair = new CompoundCommand("pair");
        pair.add(op0);
        pair.add(op1);
        pair.execute();

        assertNull("The folder move across layers is legitimate (folders carry no layer guard)",
                op0.getSkipReason());
        assertNotNull("Moving a Business element under a now-Application folder must decline",
                op1.getSkipReason());
        assertEquals("The element must stay under its Business layer", FolderType.BUSINESS,
                rootTypeOf(e));
        assertTrue("The element stays in its original Business folder",
                business.getElements().contains(e));
        assertTrue("The skip reason names the offending element", op1.getSkipReason().contains("'E'"));
    }

    @Test
    public void layerGuard_declines_whenTreeAlreadyStale_orderIndependent() {
        IFolder application = model.getFolder(FolderType.APPLICATION);
        IFolder fUnderApp = newFolder("F-app");
        application.getFolders().add(fUnderApp); // already off-layer for a Business element
        IArchimateElement e = factory.createBusinessActor();
        e.setName("E2");
        e.setId("E2");
        business.getElements().add(e);

        MoveToFolderCommand mv = moveObject(e, business, fUnderApp);
        mv.execute();

        assertNotNull("A Business element move under an Application folder must decline",
                mv.getSkipReason());
        assertEquals("The element is left on its Business layer", FolderType.BUSINESS, rootTypeOf(e));
    }

    @Test
    public void layerGuard_batchSummary_namesTheDeclinedMove() {
        IFolder application = model.getFolder(FolderType.APPLICATION);
        IFolder fUnderApp = newFolder("F-app2");
        application.getFolders().add(fUnderApp);
        IArchimateElement e = factory.createBusinessActor();
        e.setName("E3");
        e.setId("E3");
        business.getElements().add(e);

        MutationContext context = new MutationContext();
        MoveToFolderCommand mv = moveObject(e, business, fUnderApp);
        context.beginBatch("batch");
        context.queueCommand(mv, "Move element E3 off-layer");
        mv.execute();

        BatchSummaryDto summary = context.buildCommitSummary();
        assertNotNull("A declined layer move must surface in the batch summary",
                summary.skippedOperations());
        assertEquals(1, summary.skippedOperations().size());
        assertTrue("The skip is paired with the operation description",
                summary.skippedOperations().get(0).startsWith("Move element E3 off-layer — "));
    }

    @Test
    public void layerGuard_undoAfterDecline_isInert_andLegitimateMoveStillWorks() {
        IFolder application = model.getFolder(FolderType.APPLICATION);
        IFolder fUnderApp = newFolder("F-app3");
        application.getFolders().add(fUnderApp);
        IFolder businessSub = newFolder("Biz-sub");
        business.getFolders().add(businessSub); // a legal Business-rooted target
        IArchimateElement e = factory.createBusinessActor();
        e.setName("E4");
        e.setId("E4");
        business.getElements().add(e);

        MoveToFolderCommand declined = moveObject(e, business, fUnderApp);
        declined.execute();
        assertNotNull("Move off-layer declines", declined.getSkipReason());
        declined.undo();
        assertTrue("undo of a declined layer move does not disturb the element",
                business.getElements().contains(e));
        assertFalse("undo of a declined layer move does not insert it into the target",
                fUnderApp.getElements().contains(e));

        MoveToFolderCommand legit = moveObject(e, business, businessSub);
        legit.execute();
        assertNull("A move within the Business layer is legitimate", legit.getSkipReason());
        assertTrue("Element moved into the Business subfolder", businessSub.getElements().contains(e));
        legit.undo();
        assertTrue("undo returns the element to its Business folder", business.getElements().contains(e));
        legit.redo();
        assertNull("redo re-checks and still finds the move legal", legit.getSkipReason());
        assertTrue("redo re-applies the legitimate move", businessSub.getElements().contains(e));
    }
}
