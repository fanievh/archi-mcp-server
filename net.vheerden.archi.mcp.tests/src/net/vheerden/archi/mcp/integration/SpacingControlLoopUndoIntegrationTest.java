package net.vheerden.archi.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;

import net.vheerden.archi.mcp.model.LayoutMetrics;
import net.vheerden.archi.mcp.model.SpacingControlLoop;
import net.vheerden.archi.mcp.model.SpacingMutationCommand;

/**
 * Integration pin for the single-undo wrapping invariant of the embedded
 * observe → decide → back-off control loop.
 *
 * <p><strong>Substrate (Path B-prime — real-EMF pure-unit JUnit):</strong>
 * mirrors the project's existing convention for command-level tests
 * ({@code CreateViewCommandTest} / {@code DeleteProfileCommandTest} /
 * {@code UpdateRelationshipCommandTest} etc.) — uses real
 * {@link IArchimateFactory#eINSTANCE} for EMF model construction and the
 * GEF-base {@link CompoundCommand} (pure-Java; no OSGi). NO OSGi/PDE Plug-in
 * Test substrate is required; this test runs as standard JUnit 4. The
 * "integration" package placement matches the existing
 * {@code ErrorConsistencyTest} / {@code MultiStepWorkflowTest} convention
 * where "integration" describes test SCOPE (multi-component composition:
 * SpacingControlLoop + GEF CompoundCommand + real EMF mutation) NOT substrate
 * (OSGi-loaded).</p>
 *
 * <p><strong>Why GEF base {@code CompoundCommand} instead of Archi's
 * {@code NonNotifyingCompoundCommand} (the production wrapper):</strong> the
 * Archi {@code NonNotifyingCompoundCommand.execute()} transitively invokes
 * {@code IEditorModelManager.INSTANCE.firePropertyChange(...)} during change-
 * notification suppression; that static initializer requires
 * {@code ArchiPlugin.getInstance() != null} which is only true under the OSGi
 * runtime. Pure-unit JUnit cannot satisfy that precondition without launching
 * a full PDE substrate. The invariant under test (atomic
 * execute/undo/redo across N inner commands wrapped in a single compound) is
 * provided by the GEF base {@code CompoundCommand}'s standard semantics —
 * the {@code NonNotifyingCompoundCommand} subclass adds ONLY notification-
 * suppression (irrelevant to undo atomicity). The production
 * {@code ArchiModelAccessorImpl.applyXxxSpacingRecommendations(...)} methods
 * use {@code NonNotifyingCompoundCommand} (verified live via the MCP plugin);
 * this test substrates
 * the same atomicity property at the GEF base level.</p>
 *
 * <p><strong>What this pin verifies (properties (a) + (b) + (c)):</strong>
 * <ol>
 *   <li>{@code oneAccessorCall_compoundCommandWrapsAllAcceptedIterations} —
 *       (a): one tool call produces exactly ONE undo-stack entry. The
 *       {@link SpacingControlLoop#iterate} call's
 *       {@code result.acceptedCommands()} list is wrapped in exactly one
 *       {@link CompoundCommand}; the compound's
 *       {@code getCommands().size()} equals the accepted-iteration count;
 *       {@code result.iterations().size()} matches both.</li>
 *   <li>{@code compoundUndo_revertsAllAcceptedIterationsAtomically} —
 *       (b): {@code compound.undo()} called ONCE reverts ALL accepted
 *       iterations atomically. Real EMF state (an element's
 *       {@link IDiagramModelArchimateObject#setBounds(int,int,int,int)} x
 *       coordinate) verified to return to the pre-execute baseline, NOT to
 *       any intermediate state.</li>
 *   <li>{@code compoundRedo_replaysAllAcceptedIterationsAtomically} —
 *       (c): {@code compound.redo()} called ONCE after an undo replays ALL
 *       accepted iterations atomically. Real EMF state verified to return to
 *       the post-execute target, NOT to any intermediate state.</li>
 * </ol></p>
 *
 * <p><strong>Scope caveat (documented intentionally):</strong> this pin
 * verifies the COMPOUND atomicity property — the level at which the
 * invariant lives. It does NOT exercise the accessor's
 * {@code dispatchOrQueue(...)} end-to-end stack-push behaviour through the
 * Archi {@code CommandStack} (that requires full OSGi/PDE substrate which the
 * project does NOT use for tests). The accessor-level end-to-end behaviour was
 * verified live —
 * the live MCP {@code apply-element-spacing-recommendations(viewId="...",
 * dryRun=true)} call returned a response DTO with the expected
 * {@code terminationReason} / {@code iterationCount} / {@code appliedDeltas}
 * fields populated end-to-end through the MCP envelope serialization. For full
 * live undo/redo verification across the public command stack on a real model,
 * the canonical evidence is the live empirical run (sub-agents exercising
 * the live {@code apply-*-recommendations} tools through the MCP server).</p>
 *
 * <p><strong>Sibling-symmetric with the pure-unit
 * {@code SpacingControlLoopTest} (17 @Test methods covering the loop's
 * own logic) and the test-stub-based
 * {@code Apply{Element,Group,}SpacingRecommendationsToolTest}
 * (6 @Test methods each covering the accessor's tool-level integration).</strong>
 * This class delivers the required minimum 3 @Test methods.</p>
 */
public class SpacingControlLoopUndoIntegrationTest {

    /** Initial X coordinate of the test fixture's element (pre-execute baseline). */
    private static final int INITIAL_X = 100;

    /** Per-iteration delta in test-units (each accepted SpacingMutationCommand
     *  shifts the element's X by this amount). */
    private static final int PER_ITERATION_DELTA = 10;

    /** Number of accepted iterations the test scripts the loop to drive. */
    private static final int ACCEPTED_ITERATION_COUNT = 3;

    /** Expected post-execute X coordinate
     *  ({@code INITIAL_X + ACCEPTED_ITERATION_COUNT * PER_ITERATION_DELTA}). */
    private static final int EXPECTED_POST_EXECUTE_X =
            INITIAL_X + ACCEPTED_ITERATION_COUNT * PER_ITERATION_DELTA;

    private IArchimateModel model;
    private IArchimateDiagramModel diagramModel;
    private IDiagramModelGroup group;
    private IDiagramModelArchimateObject element;

    @Before
    public void setUp() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();

        diagramModel = factory.createArchimateDiagramModel();
        diagramModel.setName("Undo Integration Test View");
        model.getDefaultFolderForObject(diagramModel)
                .getElements().add(diagramModel);

        group = factory.createDiagramModelGroup();
        group.setName("Undo Integration Test Group");
        diagramModel.getChildren().add(group);

        IArchimateElement archimateElement = factory.createApplicationComponent();
        archimateElement.setName("Undo Integration Test Element");
        model.getDefaultFolderForObject(archimateElement)
                .getElements().add(archimateElement);

        element = factory.createDiagramModelArchimateObject();
        element.setArchimateElement(archimateElement);
        element.setBounds(INITIAL_X, 100, 120, 60);
        group.getChildren().add(element);
    }

    @Test
    public void oneAccessorCall_compoundCommandWrapsAllAcceptedIterations() {
        // (a): one tool call = exactly ONE undo-stack entry.
        // We verify by running SpacingControlLoop.iterate(...) with scripted
        // callbacks that drive ACCEPTED_ITERATION_COUNT (3) accepted
        // iterations, then wrapping the result.acceptedCommands() in a single
        // CompoundCommand and asserting that compound's
        // getCommands().size() matches the accepted-iteration count exactly.
        //
        // The mutation command produced per iteration is an EmfMutationCommand
        // (defined below) that adapts a real IDiagramModelArchimateObject
        // X-coordinate shift to the SpacingMutationCommand interface.
        SpacingControlLoop.Result result = runLoopScripted(ACCEPTED_ITERATION_COUNT);

        assertEquals("Loop should accept exactly the scripted iteration count",
                ACCEPTED_ITERATION_COUNT, result.acceptedCommands().size());
        assertEquals("Iteration list should match accepted-command count "
                + "(no aggregate back-off scripted)",
                ACCEPTED_ITERATION_COUNT, result.iterations().size());

        CompoundCommand compound = wrapInCompound(
                result.acceptedCommands(),
                "one-tool-call-one-undo-entry pin (a)");

        assertNotNull("Compound must be constructed", compound);
        assertEquals("Compound must wrap exactly the accepted-iteration count "
                + "of inner GEF commands — verifies (a) one tool call = "
                + "one undo-stack entry property at the CompoundCommand level",
                ACCEPTED_ITERATION_COUNT, compound.getCommands().size());

        // Sanity check — execute the compound and verify cumulative effect.
        compound.execute();
        assertEquals("Element X after compound.execute() must equal the "
                + "cumulative post-state",
                EXPECTED_POST_EXECUTE_X, element.getBounds().getX());
    }

    @Test
    public void compoundUndo_revertsAllAcceptedIterationsAtomically() {
        // (b): undo() called ONCE reverts ALL accepted iterations
        // atomically. We verify by running the loop, wrapping accepted
        // commands, executing the compound to advance the EMF state, then
        // calling compound.undo() ONCE and asserting the EMF state is
        // restored to the pre-execute baseline (NOT to any intermediate
        // state — atomic, not stepwise).
        SpacingControlLoop.Result result = runLoopScripted(ACCEPTED_ITERATION_COUNT);
        CompoundCommand compound = wrapInCompound(
                result.acceptedCommands(),
                "atomic-undo pin (b)");

        compound.execute();
        assertEquals("Pre-undo state should be the cumulative post-execute X",
                EXPECTED_POST_EXECUTE_X, element.getBounds().getX());

        compound.undo();

        assertEquals("(b) — single compound.undo() call must revert ALL "
                + ACCEPTED_ITERATION_COUNT + " accepted iterations atomically; "
                + "EMF state must be back at INITIAL_X (" + INITIAL_X + "), "
                + "not at any intermediate value (110 / 120 / 130).",
                INITIAL_X, element.getBounds().getX());
    }

    @Test
    public void compoundRedo_replaysAllAcceptedIterationsAtomically() {
        // (c): redo() called ONCE replays ALL accepted iterations
        // atomically. We verify by running the loop, wrapping accepted
        // commands, executing the compound, undoing it, then calling
        // compound.redo() ONCE and asserting the EMF state is restored to
        // the post-execute target (NOT to any intermediate state).
        SpacingControlLoop.Result result = runLoopScripted(ACCEPTED_ITERATION_COUNT);
        CompoundCommand compound = wrapInCompound(
                result.acceptedCommands(),
                "atomic-redo pin (c)");

        compound.execute();
        compound.undo();
        assertEquals("Pre-redo state should be the original baseline",
                INITIAL_X, element.getBounds().getX());

        compound.redo();

        assertEquals("(c) — single compound.redo() call must replay ALL "
                + ACCEPTED_ITERATION_COUNT + " accepted iterations atomically; "
                + "EMF state must be back at EXPECTED_POST_EXECUTE_X ("
                + EXPECTED_POST_EXECUTE_X + "), not at any intermediate value.",
                EXPECTED_POST_EXECUTE_X, element.getBounds().getX());
    }

    @Test
    public void compoundUndoRedoRoundTrip_preservesEmfStateExactly() {
        // (b) + (c) joint pin: round-trip atomicity. After
        // execute → undo → redo → undo, the EMF state must equal the original
        // pre-execute baseline; the compound must not "leak" state across
        // round-trips. This is a stronger property than the individual
        // (b) + (c) pins above (which only verify single-direction
        // transitions); included as a 4th @Test method exceeding the
        // minimum 3.
        SpacingControlLoop.Result result = runLoopScripted(ACCEPTED_ITERATION_COUNT);
        CompoundCommand compound = wrapInCompound(
                result.acceptedCommands(),
                "(b)+(c) round-trip pin");

        compound.execute();
        compound.undo();
        compound.redo();
        compound.undo();

        assertEquals("After execute → undo → redo → undo, EMF state must "
                + "equal the original baseline; the compound must round-trip "
                + "without leaking intermediate state.",
                INITIAL_X, element.getBounds().getX());

        // Verify the compound's inner-command list is preserved across the
        // round-trip — no GEF state-machine quirk that drops or duplicates
        // inner commands across execute/undo/redo cycles. This is the
        // operative pin for "one tool call = one undo-stack entry" property
        // across multiple cycles.
        assertEquals("(b)+(c) round-trip — compound's inner-command "
                + "list is preserved unchanged across execute → undo → redo "
                + "→ undo cycles (no GEF state-machine quirk drops or "
                + "duplicates inner commands).",
                ACCEPTED_ITERATION_COUNT, compound.getCommands().size());

        // Final sanity check — drive the compound forward once more to confirm
        // the post-execute target is reachable from the round-tripped state.
        compound.execute();
        assertEquals("Final compound.execute() returns to post-execute X",
                EXPECTED_POST_EXECUTE_X, element.getBounds().getX());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Run {@link SpacingControlLoop#iterate} with scripted callbacks that
     * drive exactly {@code acceptedCount} accepted iterations. Each iteration
     * builds an {@link EmfMutationCommand} that shifts the test fixture's
     * element X by {@link #PER_ITERATION_DELTA}; observed metrics monotonically
     * IMPROVE so the loop's aggregate-back-off rule never fires (all
     * iterations accepted; loop terminates via budget exhaustion).
     */
    private SpacingControlLoop.Result runLoopScripted(int acceptedCount) {
        // Build observation script: iteration i's post-state has
        // thresholdsMet=i+1 (monotonically improving — aggregate back-off
        // never fires; all iterations accepted).
        List<LayoutMetrics> observationScript = new ArrayList<>(acceptedCount);
        for (int i = 0; i < acceptedCount; i++) {
            observationScript.add(layoutMetrics(/*thresholdsMet=*/ i + 1));
        }

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 40,
                /*targetSpacingPx=*/ 40 + PER_ITERATION_DELTA * acceptedCount + 100,
                /*iterationBudget=*/ acceptedCount,
                /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                /*initialMetrics=*/ layoutMetrics(/*thresholdsMet=*/ 0),
                /*toolLabel=*/ "undo-integration-test");

        return SpacingControlLoop.iterate(request,
                new EmfMutatingScriptedCallbacks(observationScript, element));
    }

    /**
     * Wrap the loop-result's accepted commands in a single
     * {@link CompoundCommand}, mirroring the production
     * {@code ArchiModelAccessorImpl} accessor's outer-compound pattern.
     */
    private CompoundCommand wrapInCompound(
            List<SpacingMutationCommand> accepted, String label) {
        CompoundCommand compound =
                new CompoundCommand(label);
        for (SpacingMutationCommand cmd : accepted) {
            compound.add(((EmfMutationCommand) cmd).asGefCommand());
        }
        return compound;
    }

    /**
     * Convenience factory — minimal LayoutMetrics with the given
     * thresholdsMet aggregate; other fields default-zero for test simplicity.
     * Mirrors {@code SpacingControlLoopTest.metrics(...)} convention.
     */
    private static LayoutMetrics layoutMetrics(int thresholdsMet) {
        return new LayoutMetrics(
                thresholdsMet,
                /*hpq=*/ 0.0,
                /*m4=*/ 0,
                /*coincidentSegmentCount=*/ 0,
                /*boundaryViolations=*/ 0,
                /*vp10=*/ 0.0,
                /*edgeCrossings=*/ 0);
    }

    // ------------------------------------------------------------------
    // EmfMutationCommand — adapts an EMF X-coordinate shift to the
    // SpacingMutationCommand interface AND exposes a GEF Command face
    // for CompoundCommand wrapping.
    // ------------------------------------------------------------------

    /**
     * Mutation command that shifts an {@link IDiagramModelArchimateObject}'s
     * X coordinate by {@link #PER_ITERATION_DELTA}, capturing the pre-state
     * for {@link #undo()} reversal. Exposes an inner GEF
     * {@link Command} via {@link #asGefCommand()} so the
     * {@link CompoundCommand} (a GEF compound) can wrap it.
     */
    private static class EmfMutationCommand implements SpacingMutationCommand {

        private final IDiagramModelArchimateObject target;
        private final int deltaX;
        private int preExecuteX;

        EmfMutationCommand(IDiagramModelArchimateObject target, int deltaX) {
            this.target = target;
            this.deltaX = deltaX;
        }

        @Override
        public void execute() {
            preExecuteX = target.getBounds().getX();
            applyShift(deltaX);
        }

        @Override
        public void undo() {
            applyShift(-deltaX);
        }

        private void applyShift(int delta) {
            int x = target.getBounds().getX();
            int y = target.getBounds().getY();
            int w = target.getBounds().getWidth();
            int h = target.getBounds().getHeight();
            target.setBounds(x + delta, y, w, h);
        }

        Command asGefCommand() {
            return new Command("EmfMutationCommand-adapter") {
                @Override
                public void execute() {
                    EmfMutationCommand.this.execute();
                }

                @Override
                public void undo() {
                    EmfMutationCommand.this.undo();
                }

                @Override
                public void redo() {
                    EmfMutationCommand.this.execute();
                }
            };
        }
    }

    /**
     * Scripted callbacks that build {@link EmfMutationCommand}s targeting the
     * test fixture's element. Observation script controls the monotonic
     * thresholdsMet trajectory so the loop accepts every iteration (no
     * aggregate back-off fires).
     */
    private static class EmfMutatingScriptedCallbacks
            implements SpacingControlLoop.Callbacks {

        private final List<LayoutMetrics> script;
        private int observationIndex = 0;
        private final IDiagramModelArchimateObject target;

        EmfMutatingScriptedCallbacks(List<LayoutMetrics> script,
                IDiagramModelArchimateObject target) {
            this.script = script;
            this.target = target;
        }

        @Override
        public SpacingMutationCommand buildMutationCommand(int proposedDeltaPx) {
            // Each iteration shifts the EMF element X by PER_ITERATION_DELTA
            // (test invariant; the loop's proposedDeltaPx is informational
            // here — the test verifies CompoundCommand atomicity, not the
            // ladder arithmetic).
            return new EmfMutationCommand(target, PER_ITERATION_DELTA);
        }

        @Override
        public LayoutMetrics observeLayout() {
            assertTrue("observeLayout called more times than scripted (test "
                    + "fixture bug — increase script size)",
                    observationIndex < script.size());
            return script.get(observationIndex++);
        }
    }
}
