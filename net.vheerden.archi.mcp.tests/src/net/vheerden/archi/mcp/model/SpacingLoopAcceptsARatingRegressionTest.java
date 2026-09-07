package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * Why the control loop cannot catch this on its own, executed rather than argued.
 *
 * <p>The loop accepts or rejects each step on {@code LayoutQualityScalar.qualityScalar} — six
 * inputs over {@code [0, 12]}: three correctness bits and three graded band credits. Element
 * overlaps enter it as <em>one binary bit</em> however many there are, and the dimensions the
 * overall rating reads beyond those six do not reach it at all. Inflating spacing reliably improves
 * edge-coincidence and coincident segments, which is what it is for. So a step can lose the single
 * overlap bit, gain more than one band credit, score net-positive, and be accepted — while the view
 * the caller gets back rates worse than the one they handed in.</p>
 *
 * <p>This test drives the <em>real</em> {@link SpacingControlLoop#iterate} with a fake
 * {@code Callbacks} whose commands are inert, which is the only way the loop runs in an automated
 * lane: a real accepted command reaches {@code IEditorModelManager.INSTANCE}, whose static
 * initialiser asserts on a Platform with no instance location. Nothing here touches EMF or SWT, and
 * no fixture names an element.</p>
 *
 * <p>It is the executable form of the reason this story reports the regression instead of trying to
 * prevent it. Widening the scalar would change what every accepted step is on all three tools;
 * reporting the outcome changes none of them.</p>
 */
public class SpacingLoopAcceptsARatingRegressionTest {

    /** Inert command: the loop's accept/reject arithmetic is what is under test, not the mutation. */
    private static class InertCommand implements SpacingMutationCommand {
        int executeCount;
        int undoCount;

        @Override
        public void execute() {
            executeCount++;
        }

        @Override
        public void undo() {
            undoCount++;
        }
    }

    /** Returns one scripted observation per iteration and always builds a command. */
    private static class ScriptedCallbacks implements SpacingControlLoop.Callbacks {
        private final List<LayoutMetrics> script;
        private final List<InertCommand> built = new ArrayList<>();
        private int index;

        ScriptedCallbacks(List<LayoutMetrics> script) {
            this.script = script;
        }

        @Override
        public SpacingMutationCommand buildMutationCommand(int proposedDeltaPx) {
            InertCommand cmd = new InertCommand();
            built.add(cmd);
            return cmd;
        }

        @Override
        public LayoutMetrics observeLayout() {
            return script.get(Math.min(index++, script.size() - 1));
        }
    }

    @Test
    public void theLoopShouldAcceptAStepThatRaisesItsScalarWhileTheOverallRatingFalls() {
        // The shape the originating run produced: inflating spacing cleared the coincidence the
        // tool exists to clear, and in doing so pushed elements into each other.
        AssessLayoutResultDto before = assessment("excellent",
                /*overlapCount=*/ 0, /*edgeCoincidence=*/ 9, /*coincidentSegments=*/ 12);
        AssessLayoutResultDto after = assessment("poor",
                /*overlapCount=*/ 5, /*edgeCoincidence=*/ 0, /*coincidentSegments=*/ 0);

        LayoutMetrics beforeMetrics = LayoutQualityScalar.toLayoutMetrics(before);
        LayoutMetrics afterMetrics = LayoutQualityScalar.toLayoutMetrics(after);

        // (1) The premise: the step scores NET POSITIVE on the loop's own scalar. Losing the single
        //     overlap bit costs one point; clearing edge-coincidence and coincident segments pays
        //     more than one back.
        assertTrue("this fixture cannot demonstrate the blind spot unless the step genuinely "
                        + "scores better on the scalar: before=" + beforeMetrics.thresholdsMet()
                        + " after=" + afterMetrics.thresholdsMet(),
                afterMetrics.thresholdsMet() > beforeMetrics.thresholdsMet());

        // (2) And the premise on the other side: the same pair is a regression on the WIDER
        //     comparison the disclosure takes.
        assertTrue("and unless the same pair regresses on the rating, there is nothing to disclose",
                RatingRegressionDisclosure.hasRegressed(before, after));

        // (3) Now the real loop, with the real converter feeding it.
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 40,
                /*targetSpacingPx=*/ 100,
                /*iterationBudget=*/ 1,
                /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                beforeMetrics,
                "element");
        ScriptedCallbacks callbacks = new ScriptedCallbacks(List.of(afterMetrics));

        SpacingControlLoop.Result result = SpacingControlLoop.iterate(request, callbacks);

        assertEquals("the loop accepted the step — it saw its scalar rise and nothing else",
                1, result.acceptedCommands().size());
        assertFalse("so it did not back off; the back-off reason must not appear",
                result.terminationReason().startsWith(
                        SpacingControlLoop.REASON_AGGREGATE_REGRESSED_PREFIX));
        assertTrue("the accepted step is on the trajectory the caller gets back",
                result.iterations().size() >= 1);
    }

    @Test
    public void theAcceptedStepsScalarAndTheDisclosureShouldDisagreeByConstruction() {
        // Stated as its own pin so the disagreement is not an incidental property of the fixture
        // above: the loop's scalar is blind to the overlap COUNT, to cousin overlaps, to off-canvas
        // placement and to the rating itself. Five overlaps and one overlap score identically.
        AssessLayoutResultDto oneOverlap = assessment("fair", 1, 0, 0);
        AssessLayoutResultDto fiveOverlaps = assessment("poor", 5, 0, 0);

        assertEquals("the scalar cannot tell one overlap from five — it reads a single bit",
                LayoutQualityScalar.toLayoutMetrics(oneOverlap).thresholdsMet(),
                LayoutQualityScalar.toLayoutMetrics(fiveOverlaps).thresholdsMet());

        assertTrue("the wider comparison can, and does",
                RatingRegressionDisclosure.hasRegressed(oneOverlap, fiveOverlaps));
    }

    /**
     * An assessment carrying the three metrics this fixture moves. Elements and relationships stay
     * unnamed: a named fixture reaches the collector's SWT text measurement, which is caught on
     * macOS and fatal on a display-less runner.
     */
    private static AssessLayoutResultDto assessment(String overallRating,
            int overlapCount, int edgeCoincidence, int coincidentSegments) {
        return new AssessLayoutResultDto(
                "v-1", 5, 3,
                overlapCount, 0, 0, 0.0,
                50.0, 80, overallRating, Map.of(),
                List.of(), List.of(), List.of(), List.of(),
                0, List.of(), 0, List.of(),
                0, List.of(), false, coincidentSegments, 0, null,
                0, List.of(), 0, List.of(),
                0, List.of(), null, List.of(),
                0, List.of(), edgeCoincidence, List.of(),
                0, List.of(), 1.0, List.of(),
                overallRating, overallRating,
                1.0, List.of());
    }
}
