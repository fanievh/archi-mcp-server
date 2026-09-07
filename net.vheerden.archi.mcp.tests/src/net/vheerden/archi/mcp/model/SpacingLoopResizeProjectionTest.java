package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelGroup;

import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;

/**
 * What the three spacing convenience tools report about the objects they re-size, pinned across the
 * REAL control loop rather than against a hand-built compound.
 *
 * <p>Each of those tools calls the spacing helper once per iteration and dispatches the iterations
 * the loop ACCEPTED as one compound. That distinction is the whole design: the report is a
 * projection of the compound about to be dispatched, so an iteration the loop rejected is absent
 * because it was never in the input — not because a rule filtered it out — and where two accepted
 * iterations touch the same object, the last rectangle is the one the model ends at, because that
 * is what dispatching the compound will do.</p>
 *
 * <p>A fixture that only ever commits its first iteration cannot tell any of that apart, so the
 * loop here is driven to genuinely back off: iterations 0 and 1 improve and are kept, iteration 2
 * regresses, is undone, and never reaches {@code acceptedCommands}. The rectangle it wrote is one
 * the model never holds, and it must not be in the report.</p>
 *
 * <h2>Why the accessor itself is not driven here</h2>
 *
 * <p>It cannot be, in any automated lane. {@code SpacingControlLoop.iterate} executes each accepted
 * command directly, and for these tools that command wraps a {@code NonNotifyingCompoundCommand}
 * whose {@code execute()} reads {@code IEditorModelManager.INSTANCE}. Initialising that field
 * constructs an {@code EditorModelManager}, which asks {@code Platform.getInstanceLocation()} — and
 * that ASSERTS when the Eclipse application has not been initialised rather than degrading, so the
 * fallback path Archi wrote for a null location is never reached. Neither the plain headless JVM nor
 * the display-only bucket initialises an Eclipse application, so both fail identically at the first
 * bytecode of the first accepted command. What is faked here is therefore exactly one thing — the
 * helper that builds an iteration's command — while the loop, the placements, the projection and
 * the size comparison are all the shipped code.</p>
 */
public class SpacingLoopResizeProjectionTest {

    /**
     * The accessor's own adapter shape: a mutation command wrapping a GEF command, executed and
     * undone by the loop.
     *
     * <p>The wrapped command is the SAME shape production builds — the per-iteration
     * {@code NonNotifyingCompoundCommand} the spacing helper returns — because that shape is
     * precisely what the projection has to walk into, and a fixture that hands the loop a bare
     * placement instead would leave the walk's descent untested while still going green.</p>
     *
     * <p>What differs is only HOW it runs: the members are executed and undone directly rather than
     * through {@code CompoundCommand.execute()}. That is forced, not preferred —
     * {@code NonNotifyingCompoundCommand.execute()} reads {@code IEditorModelManager.INSTANCE},
     * whose initialisation asserts outside a started Eclipse application. Executing the members is
     * what that compound does internally anyway, so the model ends in the same state.</p>
     */
    private static class PlacementMutationCommand implements SpacingMutationCommand {
        private final Command gefCommand;
        private final LayoutMetrics postMetrics;

        PlacementMutationCommand(Command gefCommand, LayoutMetrics postMetrics) {
            this.gefCommand = gefCommand;
            this.postMetrics = postMetrics;
        }

        Command gefCommand() {
            return gefCommand;
        }

        LayoutMetrics postMetrics() {
            return postMetrics;
        }

        @Override
        public void execute() {
            executeDeep(gefCommand);
        }

        @Override
        public void undo() {
            undoDeep(gefCommand);
        }

        private static void executeDeep(Command command) {
            if (command instanceof CompoundCommand compound) {
                for (Object member : compound.getCommands()) {
                    executeDeep((Command) member);
                }
            } else {
                command.execute();
            }
        }

        private static void undoDeep(Command command) {
            if (command instanceof CompoundCommand compound) {
                List<?> members = compound.getCommands();
                for (int i = members.size() - 1; i >= 0; i--) {
                    undoDeep((Command) members.get(i));
                }
            } else {
                command.undo();
            }
        }
    }

    private static LayoutMetrics scalarMetrics(int m4, int coincSeg, double hpq, int edgeCrossings) {
        int scalar = LayoutQualityScalar.qualityScalar(
                /*boundaryViolations=*/ 0, /*passThroughs=*/ 0, /*overlaps=*/ 0, m4, coincSeg, hpq);
        return new LayoutMetrics(scalar, hpq, m4, coincSeg,
                /*boundaryViolations=*/ 0, /*vp10=*/ 0.0, edgeCrossings);
    }

    private static IArchimateDiagramModel newDiagram() {
        return IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
    }

    private static IDiagramModelGroup group(IArchimateDiagramModel diagram, String id, String name,
            int x, int y, int w, int h) {
        IDiagramModelGroup g = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        g.setId(id);
        g.setName(name);
        g.setBounds(x, y, w, h);
        diagram.getChildren().add(g);
        return g;
    }

    /** One iteration's compound, in the shape {@code computeAdjustViewSpacing} returns: flat. */
    private static NonNotifyingCompoundCommand iterationCompound(Command... placements) {
        NonNotifyingCompoundCommand c = new NonNotifyingCompoundCommand("Adjust view spacing");
        for (Command p : placements) {
            c.add(p);
        }
        return c;
    }

    /**
     * The outer compound each of the three call sites builds, byte-for-byte in shape: one member
     * per ACCEPTED command, in acceptance order.
     */
    private static NonNotifyingCompoundCommand outerCompoundOf(
            List<SpacingMutationCommand> accepted) {
        NonNotifyingCompoundCommand outer = new NonNotifyingCompoundCommand("outer");
        for (SpacingMutationCommand cmd : accepted) {
            outer.add(((PlacementMutationCommand) cmd).gefCommand());
        }
        return outer;
    }

    private static MovedViewObjectDto entryFor(List<MovedViewObjectDto> entries, String id) {
        for (MovedViewObjectDto e : entries) {
            if (id.equals(e.viewObjectId())) {
                return e;
            }
        }
        return null;
    }

    /**
     * The discard pin. The loop keeps iterations 0 and 1 and rejects iteration 2 on an aggregate
     * regression, undoing it. Iteration 2 re-sized the same object the accepted ones did, to a
     * rectangle the model therefore never holds.
     */
    @Test
    public void shouldOmitARejectedIterationsRectangle_whenTheLoopBacksOffAfterAccepting() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group(diagram, "g", "G", 0, 0, 100, 100);

        // Wrapped exactly as production wraps them — a per-iteration compound, not a bare
        // placement — so the projection's descent into the compound is on the path under test.
        Command iter0 = iterationCompound(new UpdateViewObjectCommand(g, 0, 0, 150, 120));
        Command iter1 = iterationCompound(new UpdateViewObjectCommand(g, 0, 0, 210, 160));
        Command rejected = iterationCompound(new UpdateViewObjectCommand(g, 0, 0, 900, 700));

        // Steadily improving, then a strict regression on the third step.
        LayoutMetrics[] post = {
            scalarMetrics(/*m4=*/ 7, /*cs=*/ 4, /*hpq=*/ 0.80, 140),
            scalarMetrics(/*m4=*/ 5, /*cs=*/ 3, /*hpq=*/ 0.88, 130),
            scalarMetrics(/*m4=*/ 14, /*cs=*/ 11, /*hpq=*/ 0.20, 200),
        };
        Command[] built = { iter0, iter1, rejected };

        SpacingControlLoop.Callbacks callbacks = new SpacingControlLoop.Callbacks() {
            int build = 0;
            int observe = 0;

            @Override
            public SpacingMutationCommand buildMutationCommand(int proposedDeltaPx) {
                if (build >= built.length) {
                    return null;
                }
                return new PlacementMutationCommand(built[build], post[build++]);
            }

            @Override
            public LayoutMetrics observeLayout() {
                return post[observe++];
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50, /*targetSpacingPx=*/ 400,
                /*iterationBudget=*/ 5, /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                /*initialMetrics=*/ scalarMetrics(9, 6, 0.60, 164), "element");

        SpacingControlLoop.Result result = SpacingControlLoop.iterate(request, callbacks);

        // The fixture must actually discriminate, or nothing below is evidence.
        assertTrue("fixture must reach the aggregate back-off, not budget exhaustion. Was: "
                + result.terminationReason(),
                result.terminationReason().startsWith("aggregate_threshold_regressed_"));
        assertTrue("the last recorded step must be the REJECTED one",
                result.iterations().get(result.iterations().size() - 1).backedOff());
        assertEquals("two iterations accepted before the back-off",
                2, result.acceptedCommands().size());

        // The three call sites' own projection, over the compound they would dispatch.
        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outerCompoundOf(result.acceptedCommands()), Map.of(), diagram);

        MovedViewObjectDto reported = entryFor(resized, "g");
        assertNotNull("the object the accepted iterations re-sized must be named. Was: " + resized,
                reported);
        assertEquals("the LAST ACCEPTED rectangle, not the first", 210, reported.newWidth());
        assertEquals(160, reported.newHeight());
        assertTrue("the rejected iteration's rectangle is one the model never holds and must not "
                + "appear anywhere in the report. Was: " + resized,
                resized.stream().noneMatch(e -> e.newWidth() == 900 || e.newHeight() == 700));
    }

    /**
     * The one-shot hub resize rides in the same accepted list as a bare placement, so it sits at the
     * OUTER compound's top level rather than inside an iteration compound. It is the largest single
     * resize these tools perform and was reported on no surface at all.
     */
    @Test
    public void shouldNameTheHub_whenTheLoopPairsAOneShotResizeWithTheIterationThatEscalated() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup hub = group(diagram, "hub", "Hub", 10, 20, 120, 90);
        IDiagramModelGroup leaf = group(diagram, "leaf", "Leaf", 400, 0, 60, 55);

        // The accepted list the loop hands back on an escalate iteration: the hub-resize command is
        // added FIRST and bare, the iteration's own compound second.
        List<SpacingMutationCommand> accepted = new ArrayList<>();
        accepted.add(new PlacementMutationCommand(
                new UpdateViewObjectCommand(hub, 10, 20, 370, 306), null));
        accepted.add(new PlacementMutationCommand(iterationCompound(
                new UpdateViewObjectCommand(leaf, 400, 0, 140, 55)), null));

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outerCompoundOf(accepted), Map.of(), diagram);

        MovedViewObjectDto reportedHub = entryFor(resized, "hub");
        assertNotNull("a bare command at the outer level is the hub resize; a walk that only "
                + "descends into iteration compounds drops it silently. Was: " + resized,
                reportedHub);
        assertEquals(370, reportedHub.newWidth());
        assertEquals(306, reportedHub.newHeight());
        assertNotNull("the same call's inflation resize must still be there",
                entryFor(resized, "leaf"));
        assertEquals(2, resized.size());
    }

    /**
     * The composer runs its element arm, undoes it, replays it forward to measure the group arm and
     * undoes it again; the outer compound then re-executes both, element arm first. That only leaves
     * a truthful report because {@code UpdateViewObjectCommand} writes an ABSOLUTE rectangle, so
     * re-execution is idempotent in the final state and the later arm wins where both touched one
     * object.
     */
    @Test
    public void shouldReportTheGroupArmsRectangle_whenBothComposerArmsResizeTheSameObject() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup shared = group(diagram, "shared", "Shared", 0, 0, 100, 100);
        IDiagramModelGroup elementOnly = group(diagram, "eo", "ElementOnly", 500, 0, 100, 100);

        Command elementArmShared = new UpdateViewObjectCommand(shared, 0, 0, 180, 140);
        Command elementArmOwn = new UpdateViewObjectCommand(elementOnly, 500, 0, 200, 100);
        Command groupArmShared = new UpdateViewObjectCommand(shared, 0, 0, 260, 210);

        // Round-trip the element arm exactly as the composer does before the outer compound runs.
        elementArmShared.execute();
        elementArmOwn.execute();
        elementArmOwn.undo();
        elementArmShared.undo();

        List<SpacingMutationCommand> accepted = new ArrayList<>();
        accepted.add(new PlacementMutationCommand(
                iterationCompound(elementArmShared, elementArmOwn), null));
        accepted.add(new PlacementMutationCommand(iterationCompound(groupArmShared), null));

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outerCompoundOf(accepted), Map.of(), diagram);

        assertEquals("the group arm is the later write for the object both arms touched",
                260, entryFor(resized, "shared").newWidth());
        assertEquals(210, entryFor(resized, "shared").newHeight());
        assertNotNull("an element-arm resize the group arm never touched must survive the round "
                + "trip. Was: " + resized, entryFor(resized, "eo"));
        assertEquals(200, entryFor(resized, "eo").newWidth());
    }

    /**
     * Nothing accepted, nothing reported. The three call sites guard the whole commit-and-synthesise
     * block on a non-empty accepted list, so a loop that reverted to its initial state produces no
     * {@code adjustResult} at all — but the projection must be empty on its own terms too, since a
     * list that is only ever empty by luck cannot be trusted when it is not.
     */
    @Test
    public void shouldReportNothing_whenTheLoopAcceptedNoIterationAtAll() {
        IArchimateDiagramModel diagram = newDiagram();
        group(diagram, "g", "G", 0, 0, 100, 100);

        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outerCompoundOf(List.of()), Map.of(), diagram);

        assertTrue(resized.isEmpty());
    }

    /**
     * The size baseline is the rectangle the object EFFECTIVELY has, which inside an open batch is
     * what an earlier operation of that batch queued for it. These tools speculatively write and
     * restore the live model even inside a batch, so a plain read there is a pre-batch one while the
     * commands were computed against the queue — the two frames disagree in both directions.
     */
    @Test
    public void shouldMeasureAgainstTheQueuedRectangle_whenTheCallRunsInsideAnOpenBatch() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup g = group(diagram, "g", "G", 0, 0, 100, 100);

        List<SpacingMutationCommand> accepted = List.of(new PlacementMutationCommand(
                iterationCompound(new UpdateViewObjectCommand(g, 0, 0, 260, 200)), null));
        NonNotifyingCompoundCommand outer = outerCompoundOf(accepted);

        assertTrue("the batch already queued this exact size, so the call changes nothing about it",
                AnchorResolver.projectResizedAcrossIterations(outer,
                        Map.of("g", new int[] { 0, 0, 260, 200 }), diagram).isEmpty());

        assertNull("and against a live read alone the same command reads as a 100x100 -> 260x200 "
                + "resize, which is the false positive the queued frame removes",
                entryFor(AnchorResolver.projectResizedAcrossIterations(outer,
                        Map.of("g", new int[] { 0, 0, 260, 200 }), diagram), "g"));

        assertNotNull("with nothing queued for it the same command IS a resize",
                entryFor(AnchorResolver.projectResizedAcrossIterations(outer, Map.of(), diagram),
                        "g"));
    }

    /**
     * Metrics carrying a REAL average spacing, which the 7-arg shape leaves as NaN. The loop's
     * density discriminator is gated on {@code regimeSignalAvailable}, so a NaN average with a null
     * hub extent makes the whole escalate path unreachable — a fixture built that way cannot
     * escalate no matter what else it does.
     */
    private static LayoutMetrics regimeMetrics(int thresholdsMet, double avgSpacingPx) {
        return new LayoutMetrics(thresholdsMet, /*hpq=*/ 0.80, /*m4=*/ 4,
                /*coincidentSegmentCount=*/ 2, /*boundaryViolations=*/ 0,
                /*vp10=*/ 0.0, /*edgeCrossings=*/ 10, avgSpacingPx);
    }

    private static int indexOfCommand(List<SpacingMutationCommand> accepted, Command gef) {
        for (int i = 0; i < accepted.size(); i++) {
            if (((PlacementMutationCommand) accepted.get(i)).gefCommand() == gef) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The one-shot escalate hub resize, driven through the REAL loop.
     *
     * <p>This is the write range that had never been observed being produced. The other pins hand
     * the projection a hub-shaped command directly, which proves the projection reports such a
     * command but says nothing about whether the loop ever emits one, or where it puts it. Here the
     * loop is driven into escalate mode and asked for it.</p>
     *
     * <p><strong>How escalate is reached here — measured, not assumed.</strong> The loop has TWO
     * independent latches, and this fixture satisfies both, so disabling either one alone leaves
     * escalate reachable through the other (verified: mutating out either latch on its own leaves
     * these assertions green). The one that fires FIRST is the density classifier, which returns
     * ESCALATE on below-regime plus escalation budget <em>without waiting for a stall</em> — so
     * escalate latches at the end of iteration 0 and the hub is requested at iteration 1, which is
     * why the ordering assertion below looks at the SECOND spacing command. The other latch, the
     * ladder exhausting against a target below the mid-band (90 &lt; 112) and the loop raising its
     * own ceiling, is also available on this fixture.</p>
     *
     * <p>What both latches need, and what a fixture is most likely to get wrong, is a REGIME SIGNAL:
     * an average spacing that is a real number rather than NaN, or a non-null hub extent. Both are
     * supplied here, and the aggregate is made to climb throughout so every iteration is ACCEPTED —
     * that last part is the half a live view could not supply, because on a real degenerate view the
     * escalating step regresses the aggregate and is reverted, which is precisely what a live
     * attempt on this repo's model reproduced four times over.</p>
     */
    @Test
    public void shouldNameTheHub_whenTheLoopEscalatesAndTheIterationCarryingTheResizeIsAccepted() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup hub = group(diagram, "hub", "Hub", 10, 20, 140, 80);
        IDiagramModelGroup leaf = group(diagram, "leaf", "Leaf", 0, 0, 100, 100);

        // 7 connections ⇒ hubFanOutExcess(7) = 0 ⇒ the floors are exactly 300 x 250, and 140x80 is
        // under both, so hubUnderSizedForFanOut is true and belowRegime holds on the hub axis too.
        HubExtent hubExtent = new HubExtent(/*maxHubConnectionCount=*/ 7,
                /*hubWidthPx=*/ 140, /*hubHeightPx=*/ 80);
        Command hubResize = new UpdateViewObjectCommand(hub, 10, 20,
                SpacingControlLoop.requiredHubMinWidthPx(7),
                SpacingControlLoop.requiredHubMinHeightPx(7));

        int[] leafWidth = { 150, 210, 260, 300, 340 };
        int[] leafHeight = { 120, 160, 200, 240, 280 };
        List<Command> spacingCommands = new ArrayList<>();
        int[] hubRequests = { 0 };
        int[] builds = { 0 };
        int[] observes = { 0 };

        SpacingControlLoop.Callbacks callbacks = new SpacingControlLoop.Callbacks() {
            @Override
            public SpacingMutationCommand buildMutationCommand(int proposedDeltaPx) {
                int i = Math.min(builds[0]++, leafWidth.length - 1);
                Command iteration = iterationCompound(
                        new UpdateViewObjectCommand(leaf, 0, 0, leafWidth[i], leafHeight[i]));
                spacingCommands.add(iteration);
                return new PlacementMutationCommand(iteration, null);
            }

            @Override
            public LayoutMetrics observeLayout() {
                int i = observes[0]++;
                // Climbing aggregate, and an average spacing that stays BELOW the 100px regime
                // floor — so the loop keeps accepting AND keeps seeing itself as below regime.
                return regimeMetrics(2 + i, 70.0 + i * 5);
            }

            @Override
            public SpacingMutationCommand buildHubResizeCommand() {
                hubRequests[0]++;
                return new PlacementMutationCommand(hubResize, null);
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 60, /*targetSpacingPx=*/ 90,
                /*iterationBudget=*/ 3, /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                /*initialMetrics=*/ regimeMetrics(1, 60.0), "element", hubExtent);

        SpacingControlLoop.Result result = SpacingControlLoop.iterate(request, callbacks);

        // The fixture must actually have escalated. Without this the rest is vacuous: a loop that
        // never escalates accepts spacing commands only, and every assertion below about the hub
        // would be asserting something the loop was never asked to do.
        assertEquals("the loop must have entered escalate mode and asked for the one-shot resize; "
                + "terminationReason was: " + result.terminationReason(),
                1, hubRequests[0]);
        assertTrue("more than one iteration must have been accepted, or there is no ordering to "
                + "check", result.acceptedCommands().size() >= 3);

        // The hub resize is paired with the iteration that escalated, and runs BEFORE it, so the
        // spacing helper's re-assessment sees the resized hub.
        int hubIndex = indexOfCommand(result.acceptedCommands(), hubResize);
        int escalatingIterationIndex =
                indexOfCommand(result.acceptedCommands(), spacingCommands.get(1));
        assertTrue("the hub resize must reach acceptedCommands, or it can never reach the "
                + "dispatched compound and the report cannot mention it", hubIndex >= 0);
        assertEquals("it must sit immediately BEFORE the spacing command of the iteration that "
                + "escalated", escalatingIterationIndex - 1, hubIndex);

        // And the whole point: it reaches the report, at the rectangle the loop landed it at.
        List<MovedViewObjectDto> resized = AnchorResolver.projectResizedAcrossIterations(
                outerCompoundOf(result.acceptedCommands()), Map.of(), diagram);

        MovedViewObjectDto reportedHub = entryFor(resized, "hub");
        assertNotNull("the one-shot hub resize must be named — it is the largest single resize "
                + "these tools perform and was reported on no surface at all. Was: " + resized,
                reportedHub);
        assertEquals("the fan-out-scaled floor for 7 connections", 300, reportedHub.newWidth());
        assertEquals(250, reportedHub.newHeight());
        assertEquals("and it lands where the loop put it", 10, reportedHub.newX());
        assertEquals(20, reportedHub.newY());

        assertNotNull("the spacing iterations' own resize must still be there",
                entryFor(resized, "leaf"));
    }

    /**
     * ONE-shot means once per loop, not once per escalating iteration: the loop latches
     * {@code hubResizeApplied} before it even inspects the returned command, so a second escalating
     * iteration must not ask again. Driven by the same fixture, which escalates on more than one
     * iteration.
     */
    @Test
    public void shouldRequestTheHubResizeOnlyOnce_evenWhenSeveralIterationsEscalate() {
        IArchimateDiagramModel diagram = newDiagram();
        IDiagramModelGroup leaf = group(diagram, "leaf", "Leaf", 0, 0, 100, 100);
        int[] hubRequests = { 0 };
        int[] builds = { 0 };
        int[] observes = { 0 };

        SpacingControlLoop.Callbacks callbacks = new SpacingControlLoop.Callbacks() {
            @Override
            public SpacingMutationCommand buildMutationCommand(int proposedDeltaPx) {
                int i = builds[0]++;
                return new PlacementMutationCommand(iterationCompound(
                        new UpdateViewObjectCommand(leaf, 0, 0, 150 + i * 20, 120 + i * 20)), null);
            }

            @Override
            public LayoutMetrics observeLayout() {
                int i = observes[0]++;
                return regimeMetrics(2 + i, 70.0 + i * 5);
            }

            @Override
            public SpacingMutationCommand buildHubResizeCommand() {
                hubRequests[0]++;
                return null;   // no large under-sized hub — escalation degrades to spacing-only
            }
        };

        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                new SpacingControlLoop.Request(60, 90, /*iterationBudget=*/ 5,
                        Integer.MAX_VALUE, regimeMetrics(1, 60.0), "element",
                        new HubExtent(7, 140, 80)),
                callbacks);

        assertEquals("asked exactly once across the whole loop, however many iterations escalate",
                1, hubRequests[0]);
        assertTrue("and a null answer must not stop the loop — escalation degrades to spacing-only",
                result.acceptedCommands().size() >= 2);
    }
}
