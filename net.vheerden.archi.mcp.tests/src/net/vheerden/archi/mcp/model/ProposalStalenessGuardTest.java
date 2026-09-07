package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateObject;

import net.vheerden.archi.mcp.model.ProposalStalenessGuard.StaleVerdict;

/**
 * Headless tests for the staleness guard. Covers the decision cores against a
 * real {@code Display}-free {@link CommandStack} and pure maps:
 * <ul>
 *   <li><strong>Sequence capture</strong> — the guard's sequence advances on execute/undo/redo and the
 *       human-vs-agent classification (via the {@link AgentAuthoredCommand} marker) drives
 *       {@code humanInterveneSince}.</li>
 *   <li><strong>Stale-vs-fresh decision</strong> ({@link ProposalStalenessGuard#decide}) — the five
 *       cases: no-target/unchanged ⇒ fresh; missing ⇒ stale; changed+human ⇒ stale; changed+agent
 *       ⇒ fresh.</li>
 *   <li><strong>Fingerprint</strong> sensitivity to attribute edits, and a real-model
 *       {@code capture}/{@code vet} round-trip for the fresh and removed paths.</li>
 * </ul>
 *
 * <p>The real {@code getAdapter(CommandStack.class)} dispatch and live human-edit detection are covered
 * live — the bare EMF model below carries a {@link CommandStack} via
 * {@code setAdapter} only to drive the listener headlessly.</p>
 */
public class ProposalStalenessGuardTest {

    // ---- Sequence capture + human/agent classification ----

    @Test
    public void shouldAdvanceSequence_onExecuteUndoRedo() {
        CommandStack stack = new CommandStack();
        ProposalStalenessGuard guard = newGuardOn(stack, null);

        assertEquals(0, guard.currentSequence());
        stack.execute(agent("A"));
        assertEquals(1, guard.currentSequence());
        stack.execute(agent("B"));
        assertEquals(2, guard.currentSequence());
        stack.undo();
        assertEquals(3, guard.currentSequence());
        stack.redo();
        assertEquals(4, guard.currentSequence());
    }

    @Test
    public void shouldNotFlagHumanIntervene_whenOnlyAgentCommands() {
        CommandStack stack = new CommandStack();
        ProposalStalenessGuard guard = newGuardOn(stack, null);
        long at = guard.currentSequence();
        stack.execute(agent("Agent op"));
        assertFalse("an agent command must not count as human intervention",
                guard.humanInterveneSince(at));
    }

    @Test
    public void shouldFlagHumanIntervene_afterHumanCommand() {
        CommandStack stack = new CommandStack();
        ProposalStalenessGuard guard = newGuardOn(stack, null);
        stack.execute(agent("Agent op"));
        long at = guard.currentSequence();   // capture AFTER the agent op
        assertFalse(guard.humanInterveneSince(at));
        stack.execute(human("Human box"));   // now a human edits
        assertTrue("a human command after capture is intervention", guard.humanInterveneSince(at));
    }

    // ---- decide(): the five stale-vs-fresh cases ----

    @Test
    public void shouldBeFresh_whenNoTargets() {
        assertFalse(ProposalStalenessGuard.decide(true, StalenessCapture.EMPTY,
                Map.of(), Map.of()).stale());
    }

    @Test
    public void shouldBeFresh_whenAllTargetsUnchanged() {
        StalenessCapture cap = new StalenessCapture(5L,
                Map.of("id-1", "fpA"), Map.of("id-1", "Payment Gateway"));
        StaleVerdict v = ProposalStalenessGuard.decide(true, cap,
                Map.of("id-1", "fpA"), Map.of("id-1", "Payment Gateway"));
        assertFalse("unchanged targets ⇒ fresh even with human activity", v.stale());
    }

    @Test
    public void shouldBeStale_whenTargetRemoved() {
        StalenessCapture cap = new StalenessCapture(5L,
                Map.of("id-1", "fpA"), Map.of("id-1", "Payment Gateway"));
        StaleVerdict v = ProposalStalenessGuard.decide(false, cap, Map.of(), Map.of());
        assertTrue(v.stale());
        assertTrue("names the removed target", v.reason().contains("Payment Gateway"));
        assertTrue("removed wording", v.reason().toLowerCase().contains("removed"));
    }

    @Test
    public void shouldBeStale_whenTargetEditedAndHumanIntervened() {
        StalenessCapture cap = new StalenessCapture(5L,
                Map.of("id-1", "fpA"), Map.of("id-1", "Payment Gateway"));
        StaleVerdict v = ProposalStalenessGuard.decide(true, cap,
                Map.of("id-1", "fpCHANGED"), Map.of("id-1", "Payment Gateway"));
        assertTrue(v.stale());
        assertTrue("names what the human edited", v.reason().contains("Payment Gateway"));
        assertTrue("edited wording", v.reason().toLowerCase().contains("edited"));
    }

    @Test
    public void shouldBeFresh_whenTargetChangedButOnlyAgentIntervened() {
        // A changed fingerprint with NO human intervention is an agent-authored change (e.g. an earlier
        // approved proposal) — the rebuild re-resolves; not staleness.
        StalenessCapture cap = new StalenessCapture(5L,
                Map.of("id-1", "fpA"), Map.of("id-1", "Payment Gateway"));
        StaleVerdict v = ProposalStalenessGuard.decide(false, cap,
                Map.of("id-1", "fpCHANGED"), Map.of("id-1", "Payment Gateway"));
        assertFalse("agent-only change ⇒ fresh", v.stale());
    }

    // ---- fingerprint sensitivity ----

    @Test
    public void shouldChangeFingerprint_whenAttributeEdited() {
        IBusinessActor actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setName("Payment Gateway");
        String before = ProposalStalenessGuard.fingerprint(actor);
        actor.setName("Renamed Gateway");
        String after = ProposalStalenessGuard.fingerprint(actor);
        assertNotEquals("a rename must change the fingerprint", before, after);
    }

    @Test
    public void shouldReturnNullFingerprint_forNull() {
        assertNull(ProposalStalenessGuard.fingerprint(null));
    }

    // ---- real-model capture/vet round-trip (fresh + removed) ----

    @Test
    public void shouldVetFresh_whenTargetUntouched() {
        IArchimateModel model = newModel();
        IBusinessActor actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setName("Payment Gateway");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        CommandStack stack = new CommandStack();
        ProposalStalenessGuard guard = newGuardOn(stack, model);
        StalenessCapture cap = guard.capture(java.util.Set.of(actor.getId()), null);

        assertEquals("one target fingerprinted", 1, cap.targetIds().size());
        assertFalse("untouched target ⇒ fresh", guard.vet(cap, null).stale());
    }

    @Test
    public void shouldVetStaleRemoved_whenTargetDeleted() {
        IArchimateModel model = newModel();
        IBusinessActor actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setName("Payment Gateway");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        CommandStack stack = new CommandStack();
        ProposalStalenessGuard guard = newGuardOn(stack, model);
        StalenessCapture cap = guard.capture(java.util.Set.of(actor.getId()), null);

        // Human removes the targeted element from the model.
        model.getFolder(FolderType.BUSINESS).getElements().remove(actor);

        StaleVerdict v = guard.vet(cap, null);
        assertTrue("a removed target ⇒ stale (no NPE/raw exception)", v.stale());
        assertTrue(v.reason().contains("Payment Gateway"));
    }

    @Test
    public void shouldCaptureEmpty_whenNoTargets() {
        ProposalStalenessGuard guard = new ProposalStalenessGuard(() -> null);
        StalenessCapture cap = guard.capture(java.util.Set.of(), null);
        assertTrue(cap.targetIds().isEmpty());
        assertFalse("an empty capture always vets fresh", guard.vet(cap, null).stale());
    }

    // ---- bounds/move decision core (decide 5-arg) ----

    @Test
    public void shouldBeStale_whenBoundsMovedAndHumanIntervened() {
        StalenessCapture cap = new StalenessCapture(5L,
                Map.of("id-1", "fpA"), Map.of("id-1", "Customer Identity Platform"),
                Map.of("id-1", "0,0,100,50"));
        StaleVerdict v = ProposalStalenessGuard.decide(true, cap,
                Map.of("id-1", "fpA"), Map.of("id-1", "Customer Identity Platform"),
                Map.of("id-1", "120,40,100,50"));
        assertTrue(v.stale());
        assertTrue("names the moved object", v.reason().contains("Customer Identity Platform"));
        assertTrue("moved wording", v.reason().toLowerCase().contains("moved"));
    }

    @Test
    public void shouldBeFresh_whenBoundsMovedButOnlyAgentIntervened() {
        StalenessCapture cap = new StalenessCapture(5L,
                Map.of("id-1", "fpA"), Map.of("id-1", "X"), Map.of("id-1", "0,0,100,50"));
        StaleVerdict v = ProposalStalenessGuard.decide(false, cap,
                Map.of("id-1", "fpA"), Map.of("id-1", "X"), Map.of("id-1", "9,9,100,50"));
        assertFalse("an agent-only move ⇒ fresh", v.stale());
    }

    @Test
    public void shouldPreferEditedMessage_whenBothAttributeAndBoundsChanged() {
        StalenessCapture cap = new StalenessCapture(5L,
                Map.of("id-1", "fpA"), Map.of("id-1", "X"), Map.of("id-1", "0,0,100,50"));
        StaleVerdict v = ProposalStalenessGuard.decide(true, cap,
                Map.of("id-1", "fpCHANGED"), Map.of("id-1", "X"), Map.of("id-1", "9,9,100,50"));
        assertTrue(v.stale());
        assertTrue("an attribute edit subsumes a move", v.reason().toLowerCase().contains("edited"));
    }

    @Test
    public void shouldBeFresh_whenBoundsAndAttributesUnchanged() {
        StalenessCapture cap = new StalenessCapture(5L,
                Map.of("id-1", "fpA"), Map.of("id-1", "X"), Map.of("id-1", "0,0,100,50"));
        StaleVerdict v = ProposalStalenessGuard.decide(true, cap,
                Map.of("id-1", "fpA"), Map.of("id-1", "X"), Map.of("id-1", "0,0,100,50"));
        assertFalse("nothing changed ⇒ fresh", v.stale());
    }

    // ---- boundsFingerprint sensitivity + orthogonality to the attribute fingerprint ----

    @Test
    public void shouldChangeBoundsFingerprint_whenMoved() {
        IDiagramModelArchimateObject o = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        o.setBounds(0, 0, 100, 50);
        String before = ProposalStalenessGuard.boundsFingerprint(o);
        o.setBounds(120, 40, 100, 50);
        assertNotEquals("a drag must change the bounds fingerprint", before,
                ProposalStalenessGuard.boundsFingerprint(o));
    }

    @Test
    public void shouldReturnNullBoundsFingerprint_forNonDiagramObject() {
        IBusinessActor actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        assertNull("non-diagram targets carry no bounds", ProposalStalenessGuard.boundsFingerprint(actor));
    }

    @Test
    public void shouldNotChangeAttributeFingerprint_whenOnlyBoundsMove() {
        IDiagramModelArchimateObject o = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        o.setBounds(0, 0, 100, 50);
        String before = ProposalStalenessGuard.fingerprint(o);
        o.setBounds(120, 40, 100, 50);
        assertEquals("bounds are not folded into the attribute fingerprint (orthogonal)",
                before, ProposalStalenessGuard.fingerprint(o));
    }

    // ---- real-model capture/vet over a tracked diagram-object child ----

    @Test
    public void shouldVetStaleRemoved_whenTrackedDiagramChildDeleted() {
        Fixture f = modelWithDiagramChild("child-1", "Customer Identity Platform");
        ProposalStalenessGuard guard = newGuardOn(new CommandStack(), f.model);
        StalenessCapture cap = guard.capture(java.util.Set.of("child-1"), null);
        assertEquals("the broadened child id is tracked", 1, cap.targetIds().size());

        // Human deletes the child view-object from the view (a frozen child command would now misapply).
        ((IArchimateDiagramModel) f.child.eContainer()).getChildren().remove(f.child);

        StaleVerdict v = guard.vet(cap, null);
        assertTrue("a deleted tracked child ⇒ stale", v.stale());
        assertTrue("names the removed child", v.reason().contains("Customer Identity Platform"));
        assertTrue("removed wording", v.reason().toLowerCase().contains("removed"));
    }

    @Test
    public void shouldVetStaleMoved_whenTrackedDiagramChildDragged() {
        Fixture f = modelWithDiagramChild("child-1", "Customer Identity Platform");
        CommandStack stack = new CommandStack();
        ProposalStalenessGuard guard = newGuardOn(stack, f.model);
        StalenessCapture cap = guard.capture(java.util.Set.of("child-1"), null);

        stack.execute(human("Human drag"));      // a human command intervenes
        f.child.setBounds(120, 40, 100, 50);      // ...and drags the tracked child

        StaleVerdict v = guard.vet(cap, null);
        assertTrue("a dragged tracked child ⇒ stale", v.stale());
        assertTrue("names the moved child", v.reason().contains("Customer Identity Platform"));
        assertTrue("moved wording", v.reason().toLowerCase().contains("moved"));
    }

    @Test
    public void shouldVetFresh_whenTrackedDiagramChildUntouched_despiteHumanActivity() {
        Fixture f = modelWithDiagramChild("child-1", "Untouched");
        CommandStack stack = new CommandStack();
        ProposalStalenessGuard guard = newGuardOn(stack, f.model);
        StalenessCapture cap = guard.capture(java.util.Set.of("child-1"), null);

        stack.execute(human("Unrelated human edit elsewhere"));  // human intervened, child untouched

        assertFalse("an untouched tracked child ⇒ fresh even with human activity",
                guard.vet(cap, null).stale());
    }

    /**
     * Probes the residual that the reviewed-or-reject propose sites carry — the ones whose deferred
     * handle returns the already-reviewed compound instead of re-preparing.
     *
     * <p>A changed fingerprint only counts as stale when a <em>human</em> intervened; an intervening
     * <em>agent</em> command is deliberately forgiven, because the rebuild is expected to re-resolve
     * against whatever the agent did. That forgiveness is correct for every propose site that
     * re-prepares. At the sites that freeze their compound nothing re-resolves, so the forgiven change
     * is silently overwritten by propose-time state when the human approves.</p>
     *
     * <p>This measures the forgiveness itself, against a real model and a real agent-authored command.
     * It does not drive a frozen site end-to-end; that the frozen sites re-resolve nothing is a
     * structural property of their handles, not a behaviour to observe here.</p>
     */
    @Test
    public void shouldVetFresh_whenAnAgentAuthoredCommandMovedATrackedChild_frozenCompoundResidualProbe() {
        Fixture f = modelWithDiagramChild("child-1", "Payments Gateway");
        CommandStack stack = new CommandStack();
        ProposalStalenessGuard guard = newGuardOn(stack, f.model);
        StalenessCapture cap = guard.capture(java.util.Set.of("child-1"), null);

        stack.execute(agent("Agent-authored move"));  // an AGENT command intervenes...
        f.child.setBounds(400, 400, 100, 50);          // ...and moves the tracked child

        assertFalse("an agent-authored change to a tracked object is forgiven — sound where the handle "
                + "re-prepares, and the residual where it returns a frozen compound",
                guard.vet(cap, null).stale());
    }

    // ---- helpers ----

    private static ProposalStalenessGuard newGuardOn(CommandStack stack, IArchimateModel model) {
        IArchimateModel m = (model != null) ? model : newModel();
        m.setAdapter(CommandStack.class, stack);
        ProposalStalenessGuard guard = new ProposalStalenessGuard(() -> m);
        guard.onModelActive(m);
        return guard;
    }

    private static IArchimateModel newModel() {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        return model;
    }

    /** A model carrying one view with one resolvable, named, bounded diagram-object child. */
    private static Fixture modelWithDiagramChild(String childId, String name) {
        IArchimateModel model = newModel();
        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IBusinessActor actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setName(name);
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        IDiagramModelArchimateObject child = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        child.setArchimateElement(actor);   // so getName() (→ displayName) returns the element name
        child.setId(childId);
        child.setBounds(0, 0, 100, 50);
        view.getChildren().add(child);
        return new Fixture(model, child);
    }

    private static final class Fixture {
        final IArchimateModel model;
        final IDiagramModelArchimateObject child;

        Fixture(IArchimateModel model, IDiagramModelArchimateObject child) {
            this.model = model;
            this.child = child;
        }
    }

    private static AgentAuthoredCompoundCommand agent(String label) {
        return new AgentAuthoredCompoundCommand(new NoOp(label));
    }

    private static Command human(String label) {
        return new NoOp(label);
    }

    private static final class NoOp extends Command {
        NoOp(String label) {
            super(label);
        }
        @Override public void execute() { /* no-op */ }
        @Override public void undo() { /* no-op */ }
        @Override public void redo() { /* no-op */ }
    }
}
