package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.handlers.ViewPlacementHandlerTest;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAssessmentSummaryDto;
import net.vheerden.archi.mcp.response.dto.HiddenLabelDto;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * The regression disclosure the quality loops emit when the state they commit is worse than the
 * state they were handed.
 *
 * <p>Pure and display-free by construction: the decision is a function of two assessments the
 * caller already holds, which is what makes the whole feature testable without an SWT display. The
 * loop that produces those assessments is covered separately where a display is available.</p>
 */
public class QualityTargetTerminationTest {

    // ------------------------------------------------------------------
    // The predicate — ordinal first, then the tier-weighted score
    // ------------------------------------------------------------------

    @Test
    public void hasRatingRegressed_shouldReturnTrue_whenTheRatingBandDropped() {
        AssessLayoutResultDto before = assessment("fair", 0, 26);
        AssessLayoutResultDto after = assessment("poor", 4, 40);

        assertTrue("a band drop is the headline case this disclosure exists for",
                QualityTargetTermination.hasRatingRegressed(before, after));
    }

    @Test
    public void hasRatingRegressed_shouldReturnFalse_whenTheRatingBandImproved() {
        AssessLayoutResultDto before = assessment("poor", 4, 40);
        AssessLayoutResultDto after = assessment("fair", 0, 26);

        assertFalse("an improvement must never warn",
                QualityTargetTermination.hasRatingRegressed(before, after));
    }

    @Test
    public void hasRatingRegressed_shouldReturnTrue_whenTheBandHeldButTheScoreWorsened() {
        // EDGE_COINCIDENCE_GOOD_MAX = 2 / FAIR_MAX = 5, so 3 -> 5 coincident segments never leaves
        // 'fair'. Ordinal-only would certify "no regression" about a change the loop itself scores
        // as worse — the false all-clear this project's rules forbid.
        AssessLayoutResultDto before = assessmentWithCoincident("fair", 3);
        AssessLayoutResultDto after = assessmentWithCoincident("fair", 5);

        assertTrue("a within-band regression must fire — the five bands are too coarse to see it",
                QualityTargetTermination.hasRatingRegressed(before, after));
    }

    @Test
    public void hasRatingRegressed_shouldReturnFalse_whenTheBandHeldAndTheScoreHeld() {
        AssessLayoutResultDto before = assessmentWithCoincident("fair", 4);
        AssessLayoutResultDto after = assessmentWithCoincident("fair", 4);

        assertFalse("equal ordinal and equal score is not a regression",
                QualityTargetTermination.hasRatingRegressed(before, after));
    }

    @Test
    public void hasRatingRegressed_shouldReturnFalse_whenTheBandHeldAndTheScoreImproved() {
        AssessLayoutResultDto before = assessmentWithCoincident("fair", 5);
        AssessLayoutResultDto after = assessmentWithCoincident("fair", 3);

        assertFalse("equal ordinal with a better score is an improvement, not a regression",
                QualityTargetTermination.hasRatingRegressed(before, after));
    }

    @Test
    public void hasRatingRegressed_shouldReturnFalse_whenEitherSideIsMissing() {
        AssessLayoutResultDto present = assessment("poor", 4, 40);

        assertFalse("no measurement means no claim, not a claim of regression",
                QualityTargetTermination.hasRatingRegressed(null, present));
        assertFalse("no measurement means no claim, not a claim of regression",
                QualityTargetTermination.hasRatingRegressed(present, null));
    }

    // ------------------------------------------------------------------
    // The emitted warning
    // ------------------------------------------------------------------

    @Test
    public void ratingRegressionWarnings_shouldBeEmpty_whenNothingRegressed() {
        assertTrue("no regression emits no warning",
                QualityTargetTermination.ratingRegressionWarnings(
                        assessment("poor", 4, 40), assessment("good", 0, 2)).isEmpty());
    }

    @Test
    public void ratingRegressionWarnings_shouldCarryTheCodeAndTheUndoRemedy() {
        List<StructuredWarningDto> warnings = QualityTargetTermination.ratingRegressionWarnings(
                assessment("fair", 0, 26), assessment("poor", 4, 40));

        assertEquals("exactly one entry per call", 1, warnings.size());
        assertEquals(StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED, warnings.get(0).code());
        assertEquals("the applied state is the thing that should change",
                "undo", warnings.get(0).remediationTool());
    }

    @Test
    public void ratingRegressionWarnings_shouldNameBothRatingsWhenTheBandDropped() {
        String message = QualityTargetTermination.ratingRegressionWarnings(
                assessment("fair", 0, 26), assessment("poor", 4, 40)).get(0).message();

        assertTrue("the message must name the band it left: " + message,
                message.contains("fair"));
        assertTrue("the message must name the band it landed in: " + message,
                message.contains("poor"));
    }

    @Test
    public void ratingRegressionWarnings_shouldNameEachWorsenedMetricWithItsBeforeAndAfterCount() {
        // Both ratings are identical on the wire on a within-band regression, which without more
        // reads as a self-contradiction. The metric deltas are what make the claim checkable.
        String message = QualityTargetTermination.ratingRegressionWarnings(
                assessmentWithCoincident("fair", 3),
                assessmentWithCoincident("fair", 5)).get(0).message();

        assertTrue("the worsened metric must be named: " + message,
                message.contains("coincident segments"));
        assertTrue("its before count must be given: " + message, message.contains("3"));
        assertTrue("its after count must be given: " + message, message.contains("5"));
    }

    @Test
    public void ratingRegressionWarnings_shouldNotPublishTheTierWeightedScore() {
        // The weights are an internal heuristic. Publishing the number makes it a permanent
        // response contract and invites callers to optimise against a weighting that moves
        // whenever the tier model does.
        AssessLayoutResultDto before = assessmentWithCoincident("fair", 3);
        AssessLayoutResultDto after = assessmentWithCoincident("fair", 5);
        String message = QualityTargetTermination.ratingRegressionWarnings(before, after)
                .get(0).message();

        assertFalse("the raw score must not appear in the message: " + message,
                message.contains(String.valueOf(
                        QualityTargetTermination.tierWeightedScore(after))));
    }

    @Test
    public void ratingRegressionWarnings_shouldSayTheStateWasAppliedAndOfferOneUndo() {
        String message = QualityTargetTermination.ratingRegressionWarnings(
                assessment("fair", 0, 26), assessment("poor", 4, 40)).get(0).message();

        assertTrue("the caller must learn the degraded state is applied: " + message,
                message.toLowerCase(java.util.Locale.ROOT).contains("applied"));
        assertTrue("undo is the whole recovery route and must be named: " + message,
                message.contains("undo"));
    }

    @Test
    public void ratingRegressionWarnings_shouldFollowTheFinalAssessment_notTheLoopsBest() {
        // The label fallback runs AFTER the loop and adopts its result only when it improves, so the
        // final rating is monotonically >= the loop's best. Comparing against the loop's best would
        // therefore report a regression the fallback has already cleared. This pins that the choice
        // of "after" argument is outcome-determining, so the two cannot be swapped silently.
        AssessLayoutResultDto preCall = assessmentWithCoincident("good", 2);
        AssessLayoutResultDto loopBest = assessmentWithCoincident("fair", 6);
        AssessLayoutResultDto afterFallback = assessmentWithCoincident("good", 2);

        assertFalse("compared against the loop's best, this run looks like a regression",
                QualityTargetTermination.ratingRegressionWarnings(preCall, loopBest).isEmpty());
        assertTrue("compared against the rating actually reported, it is not one — and that is the "
                        + "comparison the response must carry",
                QualityTargetTermination.ratingRegressionWarnings(preCall, afterFallback).isEmpty());
    }

    // ------------------------------------------------------------------
    // Every regression it declares must come with evidence
    // ------------------------------------------------------------------

    @Test
    public void ratingRegressionWarnings_shouldNameTheBandInputWhenNoScoredMetricMoved() {
        // spacing, offCanvas, hubNeighbourCrowding, alignment, nonOrthogonalInteriorSegments and
        // offFaceParallelTerminals each cap the overall band on their own but contribute nothing to
        // the tier-weighted score. A band drop driven only by one of them leaves every scored count
        // flat, so reporting scored counts alone would disclose a regression with no evidence at all.
        AssessLayoutResultDto before = withBreakdown("good", "spacing", "good");
        AssessLayoutResultDto after = withBreakdown("fair", "spacing", "fair");

        String message = QualityTargetTermination.ratingRegressionWarnings(before, after)
                .get(0).message();

        assertTrue("the band input that moved must be named: " + message,
                message.contains("element spacing"));
        assertTrue("with the ratings it moved between: " + message,
                message.contains("good -> fair"));
    }

    @Test
    public void ratingRegressionWarnings_shouldNotRenderTheBinaryHubFlagAsACount() {
        // hubPortQuality's "count" is a below-threshold flag over one aggregate score. Rendered
        // "0 -> 1" beside twelve genuine per-instance counts it reads as "one hub face degraded",
        // and no such per-face number exists for an agent to act on.
        AssessLayoutResultDto before = assessmentWithHubPortQuality("fair", 1.0);
        AssessLayoutResultDto after = assessmentWithHubPortQuality("fair", 0.0);

        String message = QualityTargetTermination.ratingRegressionWarnings(before, after)
                .get(0).message();

        assertTrue("the dimension must still be named: " + message,
                message.contains("hub port quality"));
        assertFalse("but not as a population count: " + message, message.contains("0 -> 1"));
    }

    // ------------------------------------------------------------------
    // The approval card, which is the only channel list-pending-approvals exposes
    // ------------------------------------------------------------------

    @Test
    public void putRatingDisclosure_shouldCarryTheRegressionOntoTheCard_notJustTheRating() {
        // Approval mode replaces nextSteps with fixed boilerplate for every tool, and
        // list-pending-approvals shows only proposedChanges — not the preview entity. Carrying the
        // pre-call rating alone is not enough on a within-band regression, where it is identical to
        // achievedRating and a human sees two equal ratings with nothing to explain them.
        Map<String, Object> card = new LinkedHashMap<>();

        QualityTargetTermination.putRatingDisclosure(card, "good",
                QualityTargetTermination.ratingRegressionWarnings(
                        assessmentWithCoincident("good", 2),
                        assessmentWithCoincident("good", 5)));

        assertEquals("good", card.get("ratingBefore"));
        Object regression = card.get("ratingRegression");
        assertNotNull("the card must say the change is a regression", regression);
        assertTrue("and carry the evidence, not a bare flag: " + regression,
                String.valueOf(regression).contains("coincident segments"));
    }

    @Test
    public void putRatingDisclosure_shouldAddNoRegressionEntry_whenNothingRegressed() {
        Map<String, Object> card = new LinkedHashMap<>();

        QualityTargetTermination.putRatingDisclosure(card, "poor",
                QualityTargetTermination.ratingRegressionWarnings(
                        assessment("poor", 4, 40), assessment("good", 0, 2)));

        assertEquals("poor", card.get("ratingBefore"));
        assertNull("a run that improved must leave the card quiet",
                card.get("ratingRegression"));
    }

    @Test
    public void putRatingDisclosure_shouldTolerateAnAbsentWarningList() {
        Map<String, Object> card = new LinkedHashMap<>();

        QualityTargetTermination.putRatingDisclosure(card, "fair", null);

        assertEquals("fair", card.get("ratingBefore"));
        assertNull(card.get("ratingRegression"));
    }

    // ------------------------------------------------------------------
    // An unrated view is not a band
    // ------------------------------------------------------------------

    @Test
    public void hasRatingRegressed_shouldAbstain_whenEitherSideWasNeverRated() {
        // A view too small to rate comes back "not-applicable" on both sides, which shares ordinal 0
        // with every unrecognised string. Falling through to the score tie-break there would emit a
        // disclosure phrased around a band that never meant anything for this view.
        AssessLayoutResultDto before = assessmentWithCoincident("not-applicable", 1);
        AssessLayoutResultDto after = assessmentWithCoincident("not-applicable", 4);

        assertFalse("an unrated view has no band to have dropped",
                QualityTargetTermination.hasRatingRegressed(before, after));
        assertTrue("and so emits no warning",
                QualityTargetTermination.ratingRegressionWarnings(before, after).isEmpty());
    }

    // ------------------------------------------------------------------
    // The budget-exhausted advice must stop pointing the wrong way
    // ------------------------------------------------------------------

    @Test
    public void describe_shouldStillInviteAnotherRunWhenTheBudgetRanOutWithoutRegressing() {
        String sentence = QualityTargetTermination.describe(
                QualityTargetTermination.budgetExhaustedAfter(5), false);

        assertTrue("a run that merely failed to improve may genuinely benefit from another: "
                        + sentence,
                sentence.contains("further run may still improve the result"));
    }

    @Test
    public void describe_shouldNotInviteAnotherRunWhenTheBudgetRanOutOnARegression() {
        String sentence = QualityTargetTermination.describe(
                QualityTargetTermination.budgetExhaustedAfter(5), true);

        assertFalse("inviting five more iterations after measuring itself going backwards is the "
                        + "single worst state this tool can be in: " + sentence,
                sentence.contains("further run may still improve the result"));
        assertTrue("the sentence must instead point at the regression: " + sentence,
                sentence.toLowerCase(java.util.Locale.ROOT).contains("worse"));
    }

    @Test
    public void describe_shouldBeUnchangedByTheRegressionFlagOnEveryOtherExit() {
        // The advice that was wrong is the budget-exhausted one only. A plateau or a non-remediable
        // factor already tells the caller not to re-run, so the flag must not rewrite them.
        for (String reason : List.of(
                QualityTargetTermination.plateauAtIteration(3),
                QualityTargetTermination.allMetricsPassAtIteration(2),
                QualityTargetTermination.goalReachedAtIteration(1),
                QualityTargetTermination.limitingFactorNotRemediable("labelOverlaps"))) {
            assertEquals("only the budget-exhausted advice was wrong; " + reason
                            + " must render identically",
                    QualityTargetTermination.describe(reason),
                    QualityTargetTermination.describe(reason, true));
        }
    }

    @Test
    public void describe_shouldDelegateTheSingleArgumentFormToTheNonRegressedRendering() {
        String reason = QualityTargetTermination.budgetExhaustedAfter(5);

        assertEquals("the one-argument form is the no-regression case",
                QualityTargetTermination.describe(reason, false),
                QualityTargetTermination.describe(reason));
    }

    // ------------------------------------------------------------------
    // Which arm the remedy is true of
    //
    // The disclosure is composed by buildQualityTargetDto, which runs BEFORE the facade chooses
    // between immediate dispatch, the batch queue and the approval gate. The applied tail is
    // therefore stamped on arm-blind, and is true of exactly one of the three arms.
    // ------------------------------------------------------------------

    /**
     * The immediate arm's tail, byte for byte.
     *
     * <p>Committed here rather than read off the production constant: a pin that re-derives the
     * string it is pinning moves with it and certifies nothing. The immediate arm is the common case
     * and the one verified end-to-end against the live server — two loop iterations, one stack
     * entry, one undo, complete restoration — so every rescope below is a change to a string this
     * arm shares, and this is the pin that fails if one of them widens onto it.</p>
     */
    private static final String APPLIED_TAIL =
            "The layout and routes were applied anyway — undo restores the previous state, "
                    + "and it is a single call because the winning attempt is committed as one "
                    + "compound operation.";

    @Test
    public void ratingRegressionWarnings_shouldKeepTheAppliedTailVerbatim_onTheImmediateArm() {
        String message = QualityTargetTermination.ratingRegressionWarnings(
                assessment("fair", 0, 26), assessment("poor", 4, 40)).get(0).message();

        assertTrue("the immediate arm's tail must not drift: " + message,
                message.endsWith(APPLIED_TAIL));
    }

    @Test
    public void ratingRegressionWarnings_shouldStillPrescribeUndo_onTheImmediateArm() {
        assertEquals("undo", QualityTargetTermination.ratingRegressionWarnings(
                assessment("fair", 0, 26), assessment("poor", 4, 40)).get(0).remediationTool());
    }

    @Test
    public void rescopeIfQueued_shouldLeaveTheImmediateArmUntouched_whenNothingWasQueued() {
        // batchSeq == null IS the immediate arm. Identity, not equality: the arm the applied tail
        // is true of must not even be rebuilt, or a future change to the rebuild reaches it.
        AutoLayoutAndRouteResultDto dto = regressedDto();

        assertSame("a dispatched call must come back as the very DTO the loop built",
                dto, QualityTargetTermination.rescopeIfQueued(
                        dto, null, assessment("fair", 0, 26), assessment("poor", 4, 40)));
    }

    @Test
    public void rescopeIfQueued_shouldDropTheAppliedClaimAndUndo_whenTheRunWasQueued() {
        StructuredWarningDto warning = QualityTargetTermination.rescopeIfQueued(
                regressedDto(), 1, assessment("fair", 0, 26), assessment("poor", 4, 40))
                .structuredWarnings().get(0);

        assertFalse("a queued run must not claim it was applied: " + warning.message(),
                warning.message().contains("applied anyway"));
        assertFalse("nor that undo restores it: " + warning.message(),
                warning.message().contains("undo restores the previous state"));
        assertTrue("it must say so outright: " + warning.message(),
                warning.message().contains("Nothing has been applied"));
    }

    @Test
    public void rescopeIfQueued_shouldKeepTheMeasurement_whenTheRunWasQueued() {
        // The remedy is wrong on this arm; the measurement is not. bestAssessment is taken inside
        // the loop's temporary dispatch window, so the regression is genuinely measured even when
        // the result is queued — unlike the spacing family, which abstains because its 'after'
        // snapshot re-reads an unmutated view. Rescoping must not turn into suppressing.
        StructuredWarningDto warning = QualityTargetTermination.rescopeIfQueued(
                regressedDto(), 1, assessment("fair", 0, 26), assessment("poor", 4, 40))
                .structuredWarnings().get(0);

        assertEquals(StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED, warning.code());
        assertTrue("both ratings must survive the rescope: " + warning.message(),
                warning.message().contains("from 'fair' to 'poor'"));
        assertTrue("and every metric that moved: " + warning.message(),
                warning.message().contains("element overlaps 0 -> 4"));
        assertTrue("and the second one: " + warning.message(),
                warning.message().contains("edge crossings 26 -> 40"));
    }

    @Test
    public void rescopeIfQueued_shouldNameARemedyTheQueuedCallerCanActuallyInvoke() {
        StructuredWarningDto warning = QualityTargetTermination.rescopeIfQueued(
                regressedDto(), 1, assessment("fair", 0, 26), assessment("poor", 4, 40))
                .structuredWarnings().get(0);

        assertEquals("an arm that only says 'not undo' leaves the caller with nothing",
                "end-batch", warning.remediationTool());
        assertTrue("and the prose must name the discard, not only the tool: " + warning.message(),
                warning.message().contains("end-batch rollback:true"));
    }

    @Test
    public void rescopeIfQueued_shouldStayQuiet_whenNothingRegressed() {
        // The negative control on the rescope: an improved run emits no entry on ANY arm. An empty
        // list appearing on the queued path would be a new permanent contract for nothing.
        AutoLayoutAndRouteResultDto dto = cleanDto();

        assertTrue(QualityTargetTermination.rescopeIfQueued(
                dto, 1, assessment("poor", 4, 40), assessment("good", 0, 2))
                .structuredWarnings().isEmpty());
    }

    @Test
    public void rescopeAsProposed_shouldDropTheAppliedClaimAndNameNoTool() {
        StructuredWarningDto warning = QualityTargetTermination.rescopeAsProposed(
                regressedDto(), assessment("fair", 0, 26), assessment("poor", 4, 40))
                .structuredWarnings().get(0);

        assertFalse("a proposal must not claim it was applied: " + warning.message(),
                warning.message().contains("applied anyway"));
        assertTrue("it must say so outright: " + warning.message(),
                warning.message().contains("Nothing has been applied"));
        assertEquals("no MCP tool recovers a proposal, so the field must drop out rather than "
                        + "name one that does not help",
                "", warning.remediationTool());
    }

    @Test
    public void rescopeAsProposed_shouldKeepTheMeasurement() {
        StructuredWarningDto warning = QualityTargetTermination.rescopeAsProposed(
                regressedDto(), assessment("fair", 0, 26), assessment("poor", 4, 40))
                .structuredWarnings().get(0);

        assertTrue("both ratings must survive: " + warning.message(),
                warning.message().contains("from 'fair' to 'poor'"));
        assertTrue("and the metrics that moved: " + warning.message(),
                warning.message().contains("element overlaps 0 -> 4"));
    }

    @Test
    public void rescopeShouldPreserveEveryOtherFieldOfTheResult() throws Exception {
        // The rescope travels on a hand-written 24-argument positional record constructor, which is
        // exactly the shape that silently transposes two same-typed neighbours. Enumerating a
        // handful of fields by name would not catch that, and would not catch a field added to the
        // record later and forgotten in the wither. So compare EVERY record component reflectively
        // and exempt only the one the rescope is supposed to change.
        AutoLayoutAndRouteResultDto original = regressedDto();

        AutoLayoutAndRouteResultDto rescoped = QualityTargetTermination.rescopeIfQueued(
                original, 1, assessment("fair", 0, 26), assessment("poor", 4, 40));

        int compared = 0;
        for (RecordComponent component : AutoLayoutAndRouteResultDto.class.getRecordComponents()) {
            if ("structuredWarnings".equals(component.getName())) {
                continue;
            }
            assertEquals("the rescope must carry " + component.getName() + " through untouched",
                    component.getAccessor().invoke(original),
                    component.getAccessor().invoke(rescoped));
            compared++;
        }
        assertTrue("the reflective sweep must actually have compared the record's fields, or this "
                        + "test passes by examining nothing: " + compared,
                compared >= 20);
        assertNotEquals("and the one field it IS allowed to change must have changed",
                original.structuredWarnings(), rescoped.structuredWarnings());
    }

    @Test
    public void rescopeShouldDropAContradictedDisclosureRatherThanReshipTheAppliedTail() {
        // The fail-safe on a desynced pair. Both production call sites hand the rescope the very
        // assessments that built the DTO, four lines earlier — but nothing in the method enforced
        // that, and if the two ever disagreed the old code returned the DTO untouched, re-shipping
        // "the layout and routes were applied anyway" with remediationTool "undo" on an arm where
        // nothing had been applied. On a deferred arm the safe direction is silence.
        AutoLayoutAndRouteResultDto carriesAnAppliedClaim = regressedDto();
        assertFalse("fixture premise: the DTO must start out carrying the applied-arm disclosure",
                carriesAnAppliedClaim.structuredWarnings().isEmpty());

        AutoLayoutAndRouteResultDto rescoped = QualityTargetTermination.rescopeIfQueued(
                carriesAnAppliedClaim, 1,
                assessment("poor", 4, 40), assessment("good", 0, 2)); // this pair says: improved

        assertTrue("a disclosure the supplied assessments contradict must not survive onto the "
                        + "queued arm: " + rescoped.structuredWarnings(),
                rescoped.structuredWarnings().stream().noneMatch(w ->
                        StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED.equals(w.code())));
    }

    // ------------------------------------------------------------------
    // The approval card, on the arm where nothing has been applied
    // ------------------------------------------------------------------

    @Test
    public void putProposedRatingDisclosure_shouldCarryTheEvidenceWithoutTheAppliedClaim() {
        // The card is what a human reads BEFORE approving. Describing the change as already
        // applied, and prescribing undo for it, is a statement about a mutation that has not
        // happened. Both quality loops build their card from the same pre-arm DTO.
        Map<String, Object> card = new LinkedHashMap<>();

        QualityTargetTermination.putProposedRatingDisclosure(
                card, "fair", assessment("fair", 0, 26), assessment("poor", 4, 40));

        assertEquals("the pre-call rating is untouched", "fair", card.get("ratingBefore"));
        String regression = String.valueOf(card.get("ratingRegression"));
        assertTrue("the card still names both ratings: " + regression,
                regression.contains("from 'fair' to 'poor'"));
        assertTrue("and the metrics that moved: " + regression,
                regression.contains("element overlaps 0 -> 4"));
        assertFalse("but must not say it was applied: " + regression,
                regression.contains("applied anyway"));
        assertFalse("nor prescribe undo: " + regression,
                regression.contains("undo restores the previous state"));
    }

    @Test
    public void putProposedRatingDisclosure_shouldAddNoRegressionEntry_whenNothingRegressed() {
        Map<String, Object> card = new LinkedHashMap<>();

        QualityTargetTermination.putProposedRatingDisclosure(
                card, "poor", assessment("poor", 4, 40), assessment("good", 0, 2));

        assertEquals("poor", card.get("ratingBefore"));
        assertNull("a proposal that improves the view must leave the card quiet",
                card.get("ratingRegression"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A grouped quality-loop result carrying the applied-arm regression disclosure. */
    private static AutoLayoutAndRouteResultDto regressedDto() {
        return dto(QualityTargetTermination.ratingRegressionWarnings(
                assessment("fair", 0, 26), assessment("poor", 4, 40)), "fair", "poor");
    }

    /** The same shape from a run that improved: no disclosure on any arm. */
    private static AutoLayoutAndRouteResultDto cleanDto() {
        return dto(List.of(), "poor", "good");
    }

    /**
     * Every field distinct, deliberately.
     *
     * <p>The field-preservation test compares the DTO reflectively to catch a transposition in the
     * wither's 24-argument positional constructor. It can only do that if no two same-typed fields
     * hold equal values: a first version left {@code nestedContainersFitted}, {@code hiddenLabels}
     * and {@code resizedElements} all {@code List.of()}, both booleans {@code false}, and two ints
     * at {@code 0} — and swapping two of them was then invisible. The mutation test that swapped
     * two list fields passed, which is how this was found. So every int here is unique, the two
     * booleans differ, and each list carries a distinguishable element.</p>
     */
    private static AutoLayoutAndRouteResultDto dto(
            List<StructuredWarningDto> warnings, String ratingBefore, String achievedRating) {
        return new AutoLayoutAndRouteResultDto(
                "v-1", "grouped", "DOWN", 40, 5, 3, true, 8, 2, 7, 9,
                "excellent", achievedRating, ratingBefore, 2,
                new AutoLayoutAssessmentSummaryDto(11, 12, 13.0, 14, achievedRating,
                        List.of("a-suggestion")),
                "connectionEdgeCoincidence", "a-remediation",
                "plateau_at_iteration_2",
                List.of(new MovedViewObjectDto("nested-1", "a-nested-container", 1, 2, 3, 4)),
                false,
                List.of(new HiddenLabelDto("conn-1", HiddenLabelDto.REASON_NO_VALID_POSITION)),
                warnings,
                List.of(new MovedViewObjectDto("resized-1", "a-resized-leaf", 5, 6, 7, 8)));
    }

    /** Overall rating plus the two metrics the originating regression moved. */
    // ------------------------------------------------------------------
    // The loop's own hub-port boundary — deliberately NOT the remedy's
    // ------------------------------------------------------------------

    /**
     * The remedy that advises a hub resize speaks for the whole capping region, {@code fair} as
     * well as {@code poor}. The loop's two inputs deliberately do not: they read the metric as a
     * binary correctness flag at the {@code poor} edge, and moving that edge would change which
     * candidate iteration the loop scores highest and therefore which geometry it commits.
     *
     * <p>0.60 is the discriminating value — inside the band the remedy now covers and outside the
     * band the loop counts. A widening applied here rather than only to the remedy turns both of
     * these to the other answer.</p>
     */
    @Test
    public void tierWeightedScore_shouldNotCountAFairBandHubPortScore() {
        AssessLayoutResultDto poor = assessmentWithHubPortQuality("fair", 0.49);
        AssessLayoutResultDto fair = assessmentWithHubPortQuality("fair", 0.60);
        AssessLayoutResultDto good = assessmentWithHubPortQuality("fair", 0.75);

        assertEquals("the loop counts the hub-port flag only below the poor edge",
                2, QualityTargetTermination.tierWeightedScore(poor));
        assertEquals("a fair-band score is not a loop input, however the remedy reads it",
                0, QualityTargetTermination.tierWeightedScore(fair));
        assertEquals("and neither is a good one",
                0, QualityTargetTermination.tierWeightedScore(good));
    }

    @Test
    public void getMetricCount_shouldNotCountAFairBandHubPortScore() {
        assertEquals("below the poor edge the loop sees one defect", 1,
                QualityTargetTermination.getMetricCount(
                        "hubPortQuality", assessmentWithHubPortQuality("fair", 0.49)));
        assertEquals("inside the fair band the loop still sees none", 0,
                QualityTargetTermination.getMetricCount(
                        "hubPortQuality", assessmentWithHubPortQuality("fair", 0.60)));
    }

    @Test
    public void getMetricCount_ownIconOverLabel_shouldStayUncounted() {
        // The metric must remain invisible to the quality control loop. A case here would make it
        // steerable by a loop whose fallback remediation is a spacing increase, and no amount of
        // spacing moves an icon off its own title.
        AssessLayoutResultDto dto = withOwnIconCount(assessment("excellent", 0, 0), 17);

        assertEquals("the loop must not be able to steer on this metric",
                0, QualityTargetTermination.getMetricCount("ownIconOverLabel", dto));
    }

    @Test
    public void findLimitingFactor_shouldNeverNameOwnIconOverLabel() {
        // findLimitingFactor walks the ratingBreakdown keys, and the metric is in no breakdown —
        // so it cannot be named even when it is the only detected defect on the view.
        AssessLayoutResultDto dto = withOwnIconCount(
                withBreakdown("fair", "spacing", "fair"), 17);

        assertNotEquals("ownIconOverLabel", QualityTargetTermination.findLimitingFactor(dto));
    }

    // ------------------------------------------------------------------
    // The pass-through quantity the loop ranks, vetoes and reports on.
    //
    // connectionPassThroughs is a capped, cross-plus-self DESCRIPTION list;
    // crossElementPassThroughCount is the uncapped cross-element tally the
    // rating is computed from. Reading the list size here disagrees with the
    // rating in both directions: it counts self-element pass-throughs the
    // rating does not charge and no spacing or routing lever can move, and it
    // stops counting at ten.
    // ------------------------------------------------------------------

    /**
     * Two candidates past the description cap must not score identically. Twelve crossings and
     * fifteen crossings both fill the ten-entry list, so a reader sourced from that list ranks
     * them equal and the loop commits whichever it saw first.
     */
    @Test
    public void tierWeightedScore_shouldSeparateTwoCandidates_whenBothAreScoredPastTheDescriptionCap() {
        AssessLayoutResultDto twelve =
                withPassThroughs(assessment("fair", 0, 0), cappedDescriptions(), 12);
        AssessLayoutResultDto fifteen =
                withPassThroughs(assessment("fair", 0, 0), cappedDescriptions(), 15);

        assertEquals("twelve charged crossings weigh 12 x 8",
                96, QualityTargetTermination.tierWeightedScore(twelve));
        assertEquals("fifteen charged crossings weigh 15 x 8",
                120, QualityTargetTermination.tierWeightedScore(fifteen));
    }

    /**
     * Self-element pass-throughs are in the description list and are charged at zero. Scoring them
     * spends eight points per entry on geometry the loop has no lever to fix.
     */
    @Test
    public void tierWeightedScore_shouldChargeNothing_whenEveryPassThroughIsSelfElement() {
        AssessLayoutResultDto selfOnly =
                withPassThroughs(assessment("fair", 0, 0), selfElementDescriptions(3), 0);

        assertEquals("an unrated pass-through must not weigh on the iteration score",
                0, QualityTargetTermination.tierWeightedScore(selfOnly));
    }

    /**
     * The charged tally is an int on the DTO, so there is no description list to fall back to and
     * no null guard to take. A null list beside a non-zero charged count is the shape that proves
     * which of the two the reader is sourced from.
     */
    @Test
    public void tierWeightedScore_shouldReadTheChargedCount_whenTheDescriptionListIsNull() {
        AssessLayoutResultDto nullList =
                withPassThroughs(assessment("fair", 0, 0), null, 3);

        assertEquals("three charged crossings weigh 3 x 8 whatever the description list holds",
                24, QualityTargetTermination.tierWeightedScore(nullList));
    }

    /** A regression from eleven to twenty crossings is invisible to a reader capped at ten. */
    @Test
    public void hasTier1Regression_shouldVeto_whenTheChargedCountRisesPastTheDescriptionCap() {
        AssessLayoutResultDto best =
                withPassThroughs(assessment("fair", 0, 0), cappedDescriptions(), 11);
        AssessLayoutResultDto current =
                withPassThroughs(assessment("fair", 0, 0), cappedDescriptions(), 20);

        assertTrue("nine more charged crossings is a Tier-1 regression however full the list is",
                QualityTargetTermination.hasTier1Regression(current, best));
    }

    /**
     * The other direction, and the one that loosens the veto: a candidate that introduces only
     * self-element pass-throughs must not be rejected. The rating does not charge them and neither
     * re-routing nor wider spacing can remove them, so vetoing on them rejects a candidate for a
     * defect no further iteration could have avoided.
     */
    @Test
    public void hasTier1Regression_shouldNotVeto_whenOnlySelfElementPassThroughsAppeared() {
        AssessLayoutResultDto best =
                withPassThroughs(assessment("fair", 0, 0), List.of(), 0);
        AssessLayoutResultDto current =
                withPassThroughs(assessment("fair", 0, 0), selfElementDescriptions(4), 0);

        assertFalse("an unrated pass-through must not veto a candidate iteration",
                QualityTargetTermination.hasTier1Regression(current, best));
    }

    /** Both description lists null, and only the charged tally moves. */
    @Test
    public void hasTier1Regression_shouldVeto_whenTheChargedCountRisesWithBothListsNull() {
        AssessLayoutResultDto best = withPassThroughs(assessment("fair", 0, 0), null, 1);
        AssessLayoutResultDto current = withPassThroughs(assessment("fair", 0, 0), null, 6);

        assertTrue("the veto reads the charged tally, not a list that was never partitioned",
                QualityTargetTermination.hasTier1Regression(current, best));
    }

    /**
     * The tie-break that names the limiting factor reads the same count, so re-sourcing it changes
     * which metric {@code auto-layout-and-route} reports as the thing holding the view back — and
     * therefore which remedy it reaches for next. Two metrics tied at the same band are separated
     * by their counts: twelve overlaps against fifteen charged crossings behind a description list
     * stopped at ten. Sourced from that list the crossings count as ten and lose the tie; sourced
     * from the charged tally they count as fifteen and win it.
     */
    @Test
    public void findLimitingFactor_shouldBreakATieOnTheChargedCount_notTheCappedListSize() {
        Map<String, String> breakdown = new LinkedHashMap<>();
        breakdown.put("passThroughs", "poor");
        breakdown.put("overlaps", "poor");
        AssessLayoutResultDto tied = withPassThroughs(
                build("poor", /*overlapCount=*/ 12, 0, 0, breakdown, 1.0),
                cappedDescriptions(), 15);

        assertEquals("fifteen charged crossings outweigh twelve overlaps at the same band",
                "passThroughs", QualityTargetTermination.findLimitingFactor(tied));
    }

    /**
     * The same tie the other way round, so the pin cannot pass by always naming the pass-through
     * metric: eight charged crossings lose to twelve overlaps.
     */
    @Test
    public void findLimitingFactor_shouldNotNamePassThroughs_whenTheChargedCountLosesTheTie() {
        Map<String, String> breakdown = new LinkedHashMap<>();
        breakdown.put("passThroughs", "poor");
        breakdown.put("overlaps", "poor");
        AssessLayoutResultDto tied = withPassThroughs(
                build("poor", /*overlapCount=*/ 12, 0, 0, breakdown, 1.0),
                selfElementDescriptions(4), 8);

        assertEquals("eight charged crossings do not outweigh twelve overlaps",
                "overlaps", QualityTargetTermination.findLimitingFactor(tied));
    }

    /** The tie-break count, and the number the published regression clause carries. */
    @Test
    public void getMetricCount_passThroughs_shouldReturnTheChargedCountNotTheCappedListSize() {
        AssessLayoutResultDto capped =
                withPassThroughs(assessment("fair", 0, 0), cappedDescriptions(), 22);

        assertEquals("the caller is told how many crossings the view was marked down for",
                22, QualityTargetTermination.getMetricCount("passThroughs", capped));
    }

    @Test
    public void getMetricCount_passThroughs_shouldCountNoneWhenEveryEntryIsSelfElement() {
        AssessLayoutResultDto selfOnly =
                withPassThroughs(assessment("fair", 0, 0), selfElementDescriptions(5), 0);

        assertEquals("a described but unrated pass-through is not a crossing",
                0, QualityTargetTermination.getMetricCount("passThroughs", selfOnly));
    }

    /**
     * Rebuilds a DTO with the icon count replaced; the widest constructor takes 87 arguments.
     *
     * <p>Delegates to the shared component-named rebuild rather than carrying its own copy of the
     * reflection. Only the widest form reaches every component: each back-compat constructor
     * defaults the ones appended after it, so a fixture built through a narrower form is green
     * without ever reaching the value it claims to test.</p>
     */
    private static AssessLayoutResultDto withOwnIconCount(AssessLayoutResultDto base, int count) {
        return ViewPlacementHandlerTest.withComponent(base, "ownIconOverLabelCount", count);
    }

    /**
     * Rebuilds a DTO with BOTH pass-through components replaced, independently of each other.
     *
     * <p>{@code descriptions} is the capped, cross-plus-self description list; {@code charged} is
     * the uncapped cross-element tally the rating is computed from. They disagree in both
     * directions, so a fixture that derived one from the other could not tell which of the two a
     * reader is sourced from — every case below therefore sets them apart deliberately.</p>
     */
    private static AssessLayoutResultDto withPassThroughs(
            AssessLayoutResultDto base, List<String> descriptions, int charged) {
        return ViewPlacementHandlerTest.withComponent(
                ViewPlacementHandlerTest.withComponent(
                        base, "connectionPassThroughs", descriptions),
                "crossElementPassThroughCount", charged);
    }

    /**
     * A full description list: ten entries, the cap. Beyond this the list stops growing while the
     * charged tally keeps counting, which is the whole under-count direction.
     */
    private static List<String> cappedDescriptions() {
        List<String> descriptions = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            descriptions.add("Connection 'c-" + i + "' passes through element 'e-" + i + "'");
        }
        return List.copyOf(descriptions);
    }

    /** Description entries naming pass-throughs the rating does not charge. */
    private static List<String> selfElementDescriptions(int count) {
        List<String> descriptions = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            descriptions.add("Connection 'c-" + i + "' routes through its own target element 'e-"
                    + i + "'");
        }
        return List.copyOf(descriptions);
    }

    private static AssessLayoutResultDto assessment(
            String overallRating, int overlapCount, int edgeCrossingCount) {
        return build(overallRating, overlapCount, edgeCrossingCount, 0);
    }

    /** Overall rating plus a coincident-segment count, for the within-band cases. */
    private static AssessLayoutResultDto assessmentWithCoincident(
            String overallRating, int coincidentSegmentCount) {
        return build(overallRating, 0, 0, coincidentSegmentCount);
    }

    /** Overall rating plus one breakdown entry, for the band-input cases. */
    private static AssessLayoutResultDto withBreakdown(
            String overallRating, String metric, String metricRating) {
        return build(overallRating, 0, 0, 0, Map.of(metric, metricRating), 1.0);
    }

    /** Overall rating plus a hub-port-quality score, for the binary-flag case. */
    private static AssessLayoutResultDto assessmentWithHubPortQuality(
            String overallRating, double hubPortQualityScore) {
        return build(overallRating, 0, 0, 0, Map.of(), hubPortQualityScore);
    }

    private static AssessLayoutResultDto build(
            String overallRating, int overlapCount, int edgeCrossingCount,
            int coincidentSegmentCount) {
        return build(overallRating, overlapCount, edgeCrossingCount, coincidentSegmentCount,
                Map.of(), 1.0);
    }

    private static AssessLayoutResultDto build(
            String overallRating, int overlapCount, int edgeCrossingCount,
            int coincidentSegmentCount, Map<String, String> ratingBreakdown,
            double hubPortQualityScore) {
        return new AssessLayoutResultDto(
                "v-1", 5, 3,
                overlapCount, 0, edgeCrossingCount, 0.0,
                50.0, 80, overallRating, ratingBreakdown,
                List.of(), List.of(), List.of(), List.of(),
                0, List.of(), 0, List.of(),
                0, List.of(), false, coincidentSegmentCount, 0, null,
                0, List.of(), 0, List.of(),
                0, List.of(), null, List.of(),
                0, List.of(), 0, List.of(),
                0, List.of(), hubPortQualityScore, List.of(),
                overallRating, overallRating,
                1.0, List.of());
    }
}
