package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.handlers.ViewPlacementHandlerTest;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * The disclosure the three spacing convenience tools emit when the state they commit is worse than
 * the state they were handed.
 *
 * <p>Pure and display-free: the decision is a function of the two {@code assess-layout} snapshots
 * the response already carries, which is the whole reason this feature is testable at all. The
 * control loop that produces those snapshots cannot execute in either automated lane — its accepted
 * commands reach {@code IEditorModelManager.INSTANCE}, whose static initialiser asserts on a
 * Platform with no instance location — so the comparison is deliberately kept out of it.</p>
 *
 * <p>Every fixture leaves its elements and relationships unnamed. A named fixture reaches the
 * assessment collector's SWT text measurement, which raises a caught {@code SWTException} on macOS
 * and an uncaught {@code SWTError} on a display-less Linux runner: a local pass would prove
 * nothing.</p>
 */
public class SpacingQualityDisclosureTest {

    // ------------------------------------------------------------------
    // The band-drop case — the shape the originating run produced
    // ------------------------------------------------------------------

    @Test
    public void warningsFor_shouldEmitOneUndoRemediableWarning_whenTheBandDropped() {
        AssessLayoutResultDto before = assessment("excellent", 0, 0);
        AssessLayoutResultDto after = assessment("poor", 5, 9);

        assertFixtureCanDiscriminate(before, after);

        List<StructuredWarningDto> warnings = SpacingQualityDisclosure.warningsFor(
                SpacingQualityDisclosure.COMPOSED_TOOL, before, after);

        assertEquals("at most one entry per call, and this call regressed", 1, warnings.size());
        assertEquals(StructuredWarningCodes.SPACING_RATING_REGRESSED, warnings.get(0).code());
        assertEquals("the applied state is the thing that should change, and one undo reverses "
                        + "the whole call", "undo", warnings.get(0).remediationTool());
        assertTrue("no violator ids: the regression is a property of the view, not of a listable "
                        + "set of objects", warnings.get(0).remediationViolatorIds().isEmpty());
    }

    @Test
    public void warningsFor_shouldNameBothRatings_whenTheBandDropped() {
        String message = messageFor(SpacingQualityDisclosure.COMPOSED_TOOL,
                assessment("excellent", 0, 0), assessment("poor", 5, 9));

        assertTrue("the caller must be able to read the drop without a second call: " + message,
                message.contains("'excellent'") && message.contains("'poor'"));
    }

    @Test
    public void warningsFor_shouldNameEveryWorsenedMetricWithItsBeforeAndAfterValue() {
        String message = messageFor(SpacingQualityDisclosure.COMPOSED_TOOL,
                assessment("excellent", 0, 0), assessment("poor", 5, 9));

        assertTrue("element overlaps 0 -> 5 is the evidence the claim rests on: " + message,
                message.contains("element overlaps 0 -> 5"));
        assertTrue("edge crossings 0 -> 9 must be named too: " + message,
                message.contains("edge crossings 0 -> 9"));
    }

    @Test
    public void warningsFor_shouldNameABandInputThatFell_whenNoScoredMetricMoved() {
        // The originating run moved off-canvas placement, which contributes nothing to the score
        // and caps the band on its own. Reporting only scored metrics would leave that regression
        // declared with no evidence beside it.
        AssessLayoutResultDto before = withBreakdown("good", "offCanvas", "good");
        AssessLayoutResultDto after = withBreakdown("fair", "offCanvas", "poor");

        String message = messageFor(SpacingQualityDisclosure.ELEMENT_TOOL, before, after);

        assertTrue("a band drop driven only by off-canvas placement must still carry evidence: "
                        + message, message.contains("off-canvas elements good -> poor"));
    }

    // ------------------------------------------------------------------
    // The same-band case — the hard one, where the ratings are equal on the wire
    // ------------------------------------------------------------------

    @Test
    public void warningsFor_shouldNameABandInputThatLeftThePassState() {
        // Found by the live gate. A breakdown dimension reports "pass" when it is clean — not
        // "excellent" — and "pass" is not one of the five rating bands, so it fell to the
        // unknown-ordinal 0. That made a dimension going pass -> fair sort as an IMPROVEMENT and
        // vanish from the message. It is the most common shape of a spacing regression, not a
        // corner case: a run that pushes elements off the canvas moves offCanvas exactly
        // pass -> fair. Live, a run that doubled the overlaps and pushed ten elements off-canvas
        // named the overlaps and the crossings and said nothing about off-canvas at all.
        //
        // The band must drop for the predicate to fire at all — this pin is about what the message
        // CONTAINS once it fires, not about whether it fires.
        AssessLayoutResultDto before = withBreakdown("good", "offCanvas", "pass");
        AssessLayoutResultDto after = withBreakdown("fair", "offCanvas", "fair");

        String message = messageFor(SpacingQualityDisclosure.ELEMENT_TOOL, before, after);

        assertTrue("a dimension leaving the clean 'pass' state is a regression and must be named: "
                        + message, message.contains("off-canvas elements pass -> fair"));
    }

    @Test
    public void warningsFor_shouldNotReportABandInputThatREACHEDThePassState() {
        // The mirror of the above: pass is the BEST state, so moving INTO it is an improvement and
        // must never be named as something that "worsened". Pinned because the obvious fix — giving
        // "pass" a rank somewhere in the middle — satisfies the test above and breaks this one.
        //
        // The band still drops, so the warning fires and the message exists; what is under test is
        // that this dimension is absent from it.
        AssessLayoutResultDto before = withBreakdown("good", "offCanvas", "fair");
        AssessLayoutResultDto after = withBreakdown("fair", "offCanvas", "pass");

        String message = messageFor(SpacingQualityDisclosure.ELEMENT_TOOL, before, after);

        assertFalse("reaching 'pass' is an improvement and must not be listed as worsened: "
                        + message, message.contains("off-canvas elements"));
    }

    @Test
    public void warningsFor_shouldFire_whenTheBandHeldButTheMetricsBehindItGotWorse() {
        AssessLayoutResultDto before = assessmentWithCoincident("poor", 2);
        AssessLayoutResultDto after = assessmentWithCoincident("poor", 14);

        assertEquals("the two ratings are identical on the wire — the deltas are the caller's only "
                        + "evidence", before.overallRating(), after.overallRating());
        assertFixtureCanDiscriminate(before, after);

        List<StructuredWarningDto> warnings = SpacingQualityDisclosure.warningsFor(
                SpacingQualityDisclosure.GROUP_TOOL, before, after);

        assertEquals("the five bands are too coarse to discriminate on their own", 1,
                warnings.size());
        String message = warnings.get(0).message();
        assertTrue("the message must say the band did not move, or it reads as a contradiction "
                        + "beside two equal ratings: " + message,
                message.contains("band is unchanged at 'poor'"));
        assertTrue("and it must carry the delta that makes the claim checkable: " + message,
                message.contains("coincident segments 2 -> 14"));
    }

    // ------------------------------------------------------------------
    // Abstention — a missing or unrated measurement is not a regression
    // ------------------------------------------------------------------

    @Test
    public void warningsFor_shouldAbstain_whenEitherSnapshotIsAbsent() {
        AssessLayoutResultDto present = assessment("good", 0, 0);

        assertTrue("no comparison was taken, so no claim is made",
                SpacingQualityDisclosure.warningsFor(
                        SpacingQualityDisclosure.ELEMENT_TOOL, null, present).isEmpty());
        assertTrue("no comparison was taken, so no claim is made",
                SpacingQualityDisclosure.warningsFor(
                        SpacingQualityDisclosure.ELEMENT_TOOL, present, null).isEmpty());
    }

    @Test
    public void warningsFor_shouldAbstain_whenEitherSideWasNeverRated() {
        // A view too small to rate lands on "not-applicable". Neither side was ever a band, so
        // there is no band to have dropped and no tie to break.
        AssessLayoutResultDto unrated = assessment("not-applicable", 0, 0);
        AssessLayoutResultDto rated = assessment("poor", 9, 9);

        assertTrue("a disclosure phrased around a rating that never meant anything is worse than "
                        + "no disclosure",
                SpacingQualityDisclosure.warningsFor(
                        SpacingQualityDisclosure.GROUP_TOOL, unrated, rated).isEmpty());
        assertTrue("and symmetrically in the other direction",
                SpacingQualityDisclosure.warningsFor(
                        SpacingQualityDisclosure.GROUP_TOOL, rated, unrated).isEmpty());
    }

    @Test
    public void warningsFor_shouldStaySilent_whenTheCallImprovedTheView() {
        assertTrue("the negative arm: an improvement must emit nothing at all",
                SpacingQualityDisclosure.warningsFor(SpacingQualityDisclosure.COMPOSED_TOOL,
                        assessment("poor", 5, 9), assessment("good", 0, 0)).isEmpty());
    }

    @Test
    public void warningsFor_shouldStaySilent_whenNothingMoved() {
        AssessLayoutResultDto held = assessment("fair", 2, 3);

        assertTrue("holding is not regressing",
                SpacingQualityDisclosure.warningsFor(
                        SpacingQualityDisclosure.ELEMENT_TOOL, held, held).isEmpty());
    }

    // ------------------------------------------------------------------
    // The queued path — structurally blind, so it abstains rather than clearing
    // ------------------------------------------------------------------

    @Test
    public void warningsForDispatch_shouldAbstain_whenTheCallWasQueuedForABatch() {
        // On the queued path the accepted commands go to the batch, the loop has already undone
        // every one of them, and the "after" snapshot re-reads a view they never touched. A fixture
        // that WOULD regress if executed must still produce nothing here — otherwise the tool would
        // be describing a state no one holds.
        AssessLayoutResultDto before = assessment("excellent", 0, 0);
        AssessLayoutResultDto after = assessment("poor", 5, 9);

        assertEquals("the fixture's premise: dispatched, this pair regresses", 1,
                SpacingQualityDisclosure.warningsForDispatch(
                        SpacingQualityDisclosure.COMPOSED_TOOL, null, before, after).size());

        assertTrue("queued, the same pair must disclose nothing at all",
                SpacingQualityDisclosure.warningsForDispatch(
                        SpacingQualityDisclosure.COMPOSED_TOOL, 7, before, after).isEmpty());
    }

    @Test
    public void warningsForDispatch_shouldAbstainOnEveryTool_whenQueued() {
        AssessLayoutResultDto before = assessment("good", 0, 0);
        AssessLayoutResultDto after = assessment("poor", 4, 4);

        for (String tool : List.of(SpacingQualityDisclosure.ELEMENT_TOOL,
                SpacingQualityDisclosure.GROUP_TOOL, SpacingQualityDisclosure.COMPOSED_TOOL)) {
            assertTrue(tool + " must abstain when queued, not clear",
                    SpacingQualityDisclosure.warningsForDispatch(tool, 1, before, after).isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // A known limitation, pinned so it is documented rather than silent
    // ------------------------------------------------------------------

    @Test
    public void warningsFor_shouldNotFire_whenOnlyABandInputRegressedAndTheBandAndScoreBothHeld() {
        // NOT an endorsement — a pin on a KNOWN GAP, so that closing it is a deliberate, visible
        // change rather than something a future edit does by accident.
        //
        // The predicate is the rating ordinal, tie-broken by the tier-weighted score. That score is
        // built from COUNTED metrics. Six dimensions the rating also reads carry no count —
        // spacing, offCanvas, hubNeighbourCrowding, alignment, nonOrthogonalInteriorSegments,
        // offFaceParallelTerminals — and they reach the MESSAGE but not the DECISION. So a run that
        // degrades only one of them, with the band and every counted metric flat, is not reported.
        //
        // Reachable: a view already rated 'poor' whose elements are pushed off-canvas moves
        // offCanvas pass -> fair with the band and the score both flat.
        //
        // Deliberately not fixed here: this is the same predicate auto-layout-and-route commits its
        // own attempts on, so widening it changes that tool too. It is disclosed on the warning
        // code and filed as its own row.
        AssessLayoutResultDto before = withBreakdown("poor", "offCanvas", "pass");
        AssessLayoutResultDto after = withBreakdown("poor", "offCanvas", "fair");

        assertEquals("the fixture's premise: the band really is flat",
                before.overallRating(), after.overallRating());
        assertEquals("and so is the score — otherwise this pins nothing",
                QualityTargetTermination.tierWeightedScore(before),
                QualityTargetTermination.tierWeightedScore(after));

        assertTrue("current, KNOWN-LIMITED behaviour: a band-input-only regression is not reported. "
                        + "If this assertion starts failing, the predicate was widened — update the "
                        + "warning code's javadoc, which currently discloses this gap, and close "
                        + "the backlog row rather than deleting this test quietly.",
                SpacingQualityDisclosure.warningsFor(
                        SpacingQualityDisclosure.ELEMENT_TOOL, before, after).isEmpty());
    }

    // ------------------------------------------------------------------
    // The subject — each tool names ITSELF, and the sibling family is untouched
    // ------------------------------------------------------------------

    @Test
    public void warningsFor_shouldNameTheToolThatWasActuallyCalled() {
        AssessLayoutResultDto before = assessment("good", 0, 0);
        AssessLayoutResultDto after = assessment("poor", 4, 4);

        for (String tool : List.of(SpacingQualityDisclosure.ELEMENT_TOOL,
                SpacingQualityDisclosure.GROUP_TOOL, SpacingQualityDisclosure.COMPOSED_TOOL)) {
            String message = messageFor(tool, before, after);
            assertTrue("an agent that called one tool must not be told another one did this: "
                            + message, message.startsWith(tool + " left this view worse"));
        }
    }

    @Test
    public void theThreeToolSubjectsShouldBeTheThreePublishedToolNames() {
        // The subject is read by an agent that is choosing what to call next. A subject that is not
        // a tool name it can invoke is a dead end.
        assertEquals("apply-element-spacing-recommendations", SpacingQualityDisclosure.ELEMENT_TOOL);
        assertEquals("apply-group-spacing-recommendations", SpacingQualityDisclosure.GROUP_TOOL);
        assertEquals("apply-spacing-recommendations", SpacingQualityDisclosure.COMPOSED_TOOL);
        assertNotEquals("the composed tool and the element tool must not share a subject",
                SpacingQualityDisclosure.COMPOSED_TOOL, SpacingQualityDisclosure.ELEMENT_TOOL);
    }

    @Test
    public void generalisingTheEmitterShouldNotHaveMovedTheSiblingFamilysSubjectOrCode() {
        // The fold hazard, pinned in the OTHER direction: a shared emitter that is right for the
        // spacing family and wrong for the quality-target family would pass every test above.
        AssessLayoutResultDto before = assessment("good", 0, 0);
        AssessLayoutResultDto after = assessment("poor", 4, 4);

        List<StructuredWarningDto> sibling =
                QualityTargetTermination.ratingRegressionWarnings(before, after);

        assertEquals(1, sibling.size());
        assertEquals("the sibling keeps its own published code",
                StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED, sibling.get(0).code());
        assertTrue("and its own subject: " + sibling.get(0).message(),
                sibling.get(0).message().startsWith("auto-layout-and-route left this view worse"));
        assertTrue("and its own tail, which names the winning attempt rather than the iterations: "
                        + sibling.get(0).message(),
                sibling.get(0).message().contains("the winning attempt is committed as one "
                        + "compound operation"));
    }

    @Test
    public void theApprovalCardFilterShouldStillMatchTheEmitterItWasGeneralisedWith() {
        // Parameterising the emitter and leaving a literal behind in the card filter would drop the
        // sibling's disclosure silently. Both now take the same code.
        Map<String, Object> card = new LinkedHashMap<>();

        QualityTargetTermination.putRatingDisclosure(card, "good",
                QualityTargetTermination.ratingRegressionWarnings(
                        assessment("good", 0, 0), assessment("poor", 4, 4)));

        assertTrue("the card must still carry the regression, not just the rating",
                card.containsKey("ratingRegression"));
        assertTrue("and it must be the message, not a bare flag",
                String.valueOf(card.get("ratingRegression")).contains("element overlaps 0 -> 4"));
    }

    // ------------------------------------------------------------------
    // The tail — applied anyway, undo, and ONE undo
    // ------------------------------------------------------------------

    @Test
    public void warningsFor_shouldSayTheSpacingWasAppliedAndThatOneUndoIsEnough() {
        String message = messageFor(SpacingQualityDisclosure.COMPOSED_TOOL,
                assessment("good", 0, 0), assessment("poor", 4, 4));

        assertTrue("the caller must learn the state was committed regardless: " + message,
                message.contains("applied anyway"));
        assertTrue("and that undo is the remedy: " + message,
                message.toLowerCase(Locale.ROOT).contains("undo restores the previous state"));
        assertTrue("and that ONE undo suffices — every accepted iteration wraps in one compound "
                        + "command, and a caller who does not know that may not try: " + message,
                message.contains("single call"));
    }

    @Test
    public void warningsFor_shouldNotPublishTheTierWeightedScore() {
        // Its weights are an internal heuristic that moves whenever the tier model does. Publishing
        // the number would make it a permanent response contract.
        AssessLayoutResultDto before = assessmentWithCoincident("poor", 2);
        AssessLayoutResultDto after = assessmentWithCoincident("poor", 14);
        String message = messageFor(SpacingQualityDisclosure.ELEMENT_TOOL, before, after);

        assertFalse("the raw score must not appear in the message: " + message,
                message.contains(String.valueOf(QualityTargetTermination.tierWeightedScore(after)))
                        && message.contains("score"));
    }

    // ------------------------------------------------------------------
    // Fixtures — unnamed by construction
    // ------------------------------------------------------------------

    /**
     * Asserts the fixture's own premise: a before and an after that rate alike cannot discriminate,
     * so a mutation that breaks the comparison would still pass against them.
     */
    private static void assertFixtureCanDiscriminate(
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        assertTrue("this fixture cannot discriminate: the two states are identical on both the "
                        + "band and the score, so every implementation agrees on it",
                !before.overallRating().equals(after.overallRating())
                        || QualityTargetTermination.tierWeightedScore(before)
                                != QualityTargetTermination.tierWeightedScore(after));
    }

    private static String messageFor(
            String tool, AssessLayoutResultDto before, AssessLayoutResultDto after) {
        List<StructuredWarningDto> warnings =
                SpacingQualityDisclosure.warningsFor(tool, before, after);
        assertEquals("this fixture is meant to regress", 1, warnings.size());
        return warnings.get(0).message();
    }

    /** Overall rating plus the two metrics the originating run moved. */
    private static AssessLayoutResultDto assessment(
            String overallRating, int overlapCount, int edgeCrossingCount) {
        return build(overallRating, overlapCount, edgeCrossingCount, 0, Map.of());
    }

    /** Overall rating plus a coincident-segment count, for the within-band cases. */
    private static AssessLayoutResultDto assessmentWithCoincident(
            String overallRating, int coincidentSegmentCount) {
        return build(overallRating, 0, 0, coincidentSegmentCount, Map.of());
    }

    /** Overall rating plus one breakdown entry, for the band-input cases. */
    private static AssessLayoutResultDto withBreakdown(
            String overallRating, String metric, String metricRating) {
        return build(overallRating, 0, 0, 0, Map.of(metric, metricRating));
    }

    /**
     * The pass-through clause, pinned for the first time. Its label already promises the
     * cross-element frame — "connections crossing elements" — while its number was the size of a
     * capped list that also names the self-element pass-throughs the rating never charged. A run
     * that adds four self-element pass-throughs and no crossings published a clause whose words
     * and whose number described different sets; a regression from eleven crossings to twenty
     * published "10 -> 10", which is the clause present and saying nothing moved.
     *
     * <p>The assertion is the whole composed clause in one string, label and both numbers
     * together. A fragment assertion passes on a sentence that names the right metric with the
     * wrong number.</p>
     */
    @Test
    public void warningsFor_shouldCarryTheChargedCrossingCount_whenTheDescriptionListIsCapped() {
        AssessLayoutResultDto before = withPassThroughs(assessment("good", 0, 0), 11);
        AssessLayoutResultDto after = withPassThroughs(assessment("poor", 0, 0), 20);

        String message = messageFor(SpacingQualityDisclosure.COMPOSED_TOOL, before, after);

        assertTrue("the clause must carry the crossings the rating charged, not the ten-entry "
                        + "description list both states fill: " + message,
                message.contains("connections crossing elements 11 -> 20"));
    }

    /**
     * The other direction: pass-throughs the rating does not charge must not be reported as
     * crossings that appeared. Nothing crossed an element here, and a clause saying otherwise
     * sends the caller after geometry no spacing lever can move.
     */
    @Test
    public void warningsFor_shouldNotReportACrossing_whenOnlySelfElementPassThroughsAppeared() {
        AssessLayoutResultDto before = withPassThroughs(assessment("good", 0, 0), 0);
        AssessLayoutResultDto after = selfElementPassThroughs(assessment("poor", 5, 0), 4);

        String message = messageFor(SpacingQualityDisclosure.COMPOSED_TOOL, before, after);

        assertFalse("a described but unrated pass-through is not a connection crossing an "
                        + "element: " + message,
                message.contains("connections crossing elements"));
    }

    /**
     * Rebuilds a DTO with the charged cross-element tally set and the description list filled to
     * its ten-entry cap — the shape both sides of a past-the-cap regression carry. The two are set
     * independently: deriving one from the other could not tell which the clause is sourced from.
     */
    private static AssessLayoutResultDto withPassThroughs(
            AssessLayoutResultDto base, int charged) {
        List<String> descriptions = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(charged, 10); i++) {
            descriptions.add("Connection 'c-" + i + "' passes through element 'e-" + i + "'");
        }
        return ViewPlacementHandlerTest.withComponent(
                ViewPlacementHandlerTest.withComponent(
                        base, "connectionPassThroughs", List.copyOf(descriptions)),
                "crossElementPassThroughCount", charged);
    }

    /** Description entries naming pass-throughs the rating charges at zero. */
    private static AssessLayoutResultDto selfElementPassThroughs(
            AssessLayoutResultDto base, int count) {
        List<String> descriptions = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            descriptions.add("Connection 'c-" + i + "' routes through its own target element 'e-"
                    + i + "'");
        }
        return ViewPlacementHandlerTest.withComponent(
                ViewPlacementHandlerTest.withComponent(
                        base, "connectionPassThroughs", List.copyOf(descriptions)),
                "crossElementPassThroughCount", 0);
    }

    private static AssessLayoutResultDto build(
            String overallRating, int overlapCount, int edgeCrossingCount,
            int coincidentSegmentCount, Map<String, String> ratingBreakdown) {
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
                0, List.of(), 1.0, List.of(),
                overallRating, overallRating,
                1.0, List.of());
    }
}
