package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * Pins that anchor drift and lateral-jog reversals are <strong>informational</strong>: they may not
 * reach {@code overallRating}, the tier-weighted score, the Tier-1 regression predicate, or the
 * remediation vocabulary.
 *
 * <p>This matters beyond tidiness. Both dimensions are new, and every existing view would change
 * grade the moment either became rating-bearing — which would also alter what
 * {@code auto-layout-and-route}'s quality loops converge on, silently. Promoting either is a
 * deliberate ruling, not a side effect. {@code redundantBendpointCount} is informational for exactly
 * this reason.
 *
 * <p>Fixture connections are unnamed so nothing reaches the SWT text-measurement path.
 */
public class InformationalDimensionRatingIsolationTest {

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    /**
     * Drift isolation, single-variable: the SAME polyline assessed twice, differing only in the
     * stored anchor disagreement. No geometry moves, so any rating change could only have come from
     * the new dimension.
     */
    @Test
    public void shouldLeaveEveryRatingOutputIdentical_whenOnlyTheAnchorDriftDiffers() {
        LayoutAssessmentResult clean = assessor.assess(nodes(), List.of(cleanConnection()), true);
        LayoutAssessmentResult drifted = assessor.assess(nodes(), List.of(driftedConnection()), true);

        assertEquals("the fixture must actually fire the dimension", 0, clean.anchorDriftCount());
        assertEquals(1, drifted.anchorDriftCount());

        assertEquals(clean.overallRating(), drifted.overallRating());
        assertEquals(clean.layoutRating(), drifted.layoutRating());
        assertEquals(clean.routingRating(), drifted.routingRating());
        assertEquals(clean.ratingBreakdown(), drifted.ratingBreakdown());
    }

    /**
     * Reversal isolation, held as close to single-variable as the shape allows: the same six-point
     * route with its jog at 8px (a reversal) and at 9px (a detour). One pixel of geometry separates
     * them, which is below every rating-bearing threshold, so the dimension firing is the only
     * material difference.
     */
    @Test
    public void shouldLeaveEveryRatingOutputIdentical_whenOnlyTheJogWidthCrossesTheThreshold() {
        LayoutAssessmentResult reversing = assessor.assess(nodes(), List.of(jogConnection(8.0)), true);
        LayoutAssessmentResult detouring = assessor.assess(nodes(), List.of(jogConnection(9.0)), true);

        assertEquals("the fixture must actually fire the dimension",
                1, reversing.lateralJogReversalCount());
        assertEquals(0, detouring.lateralJogReversalCount());

        assertEquals(reversing.overallRating(), detouring.overallRating());
        assertEquals(reversing.layoutRating(), detouring.layoutRating());
        assertEquals(reversing.routingRating(), detouring.routingRating());
        assertEquals(reversing.ratingBreakdown(), detouring.ratingBreakdown());
    }

    /**
     * The rating breakdown is the map every tier decision reads. Neither dimension may appear in it
     * under any key — a key present with a "pass" value is still a rating input that a later change
     * could flip.
     */
    @Test
    public void shouldAppearInNoRatingBreakdownKey_whenBothDimensionsFire() {
        LayoutAssessmentResult result =
                assessor.assess(nodes(), List.of(driftedConnection(), jogConnection(8.0)), true);
        assertEquals(1, result.anchorDriftCount());
        assertEquals(1, result.lateralJogReversalCount());

        for (String key : result.ratingBreakdown().keySet()) {
            assertEquals("rating breakdown must not carry an informational dimension: " + key,
                    false, key.toLowerCase().contains("anchordrift")
                            || key.toLowerCase().contains("lateraljog"));
        }
    }

    /**
     * The remediation vocabulary maps a metric name to a count the quality loop acts on. An unknown
     * metric returns 0, so neither dimension can steer a loop even if a caller names it.
     */
    @Test
    public void shouldContributeNoMetricCount_whenTheRemediationVocabularyIsAsked() {
        AssessLayoutResultDto dto = AssessLayoutResultDto.degenerate(
                "v1", 0, 0, 0, List.of(), List.of(), 0, List.of(), 0, List.of(),
                0, List.of(), List.of(), java.util.Map.of(), List.of());

        assertEquals(0, QualityTargetTermination.getMetricCount("anchorDrift", dto));
        assertEquals(0, QualityTargetTermination.getMetricCount("lateralJogReversals", dto));
    }

    /**
     * The remediation half of the isolation, pinned through the function that actually chooses a
     * remedy rather than by restating the breakdown assertion above.
     *
     * <p>{@code findLimitingFactor} picks the worst metric by iterating {@code ratingBreakdown} and
     * nothing else, so a dimension absent from that map can never become the limiting factor — and
     * that absence is the ONLY thing protecting the remediation path, because
     * {@code remediationTypeFor} ends in a {@code default} branch that returns a spacing increase.
     * Were either dimension ever added to the breakdown, a drifted route would silently start
     * driving the layout loop to spread elements apart, which cannot fix a stale stored route. This
     * asserts the guard where it actually lives.
     */
    @Test
    public void shouldNeverBecomeTheLimitingFactor_whenBothDimensionsAreAtTheirWorst() {
        AssessLayoutResultDto worst = dtoWith(9999, 9999);

        String limiting = QualityTargetTermination.findLimitingFactor(worst);

        assertNotEquals("anchorDrift must never steer the remediation loop",
                "anchorDrift", limiting);
        assertNotEquals("lateralJogReversals must never steer the remediation loop",
                "lateralJogReversals", limiting);
    }

    /**
     * The tier-weighted score and the Tier-1 regression predicate both read the DTO. Two DTOs
     * differing only in the two new counts must score identically and must not read as a regression.
     */
    @Test
    public void shouldNotMoveTheTierScoreOrTripTheRegressionPredicate_whenOnlyTheNewCountsDiffer() {
        AssessLayoutResultDto without = dtoWith(0, 0);
        AssessLayoutResultDto with = dtoWith(7, 5);

        assertEquals(QualityTargetTermination.tierWeightedScore(without),
                QualityTargetTermination.tierWeightedScore(with));
        assertEquals("a rise in either count is not a Tier-1 regression",
                false, QualityTargetTermination.hasTier1Regression(with, without));
    }

    /**
     * The newly published H-axis narrow-gap count, held to the same isolation as its V sibling.
     *
     * <p>{@code parallelConnectionGap} declares itself informational — it contributes to neither
     * the rating breakdown nor the suggestion list — and that claim now has to hold for a second
     * field. Publishing a metric is the moment it becomes reachable by anything that reads the DTO
     * reflectively or by name, so the isolation is asserted on the new field rather than inferred
     * from the dimension's existing declaration.</p>
     */
    @Test
    public void shouldNotMoveTheTierScore_whenOnlyTheHAxisNarrowCountDiffers() {
        AssessLayoutResultDto without = dtoWith(0, 0, 0);
        AssessLayoutResultDto with = dtoWith(0, 0, 12);

        assertEquals("the H-axis narrow-gap count is informational and must not move the score",
                QualityTargetTermination.tierWeightedScore(without),
                QualityTargetTermination.tierWeightedScore(with));
        assertEquals("a rise in the H count is not a Tier-1 regression",
                false, QualityTargetTermination.hasTier1Regression(with, without));
    }

    @Test
    public void shouldNeverBecomeTheLimitingFactor_whenTheHAxisNarrowCountIsAtItsWorst() {
        String limiting = QualityTargetTermination.findLimitingFactor(dtoWith(0, 0, 9999));

        assertNotEquals("the H-axis narrow-gap count must never steer the remediation loop",
                "hAxisParallelGapNarrow25Count", limiting);
        assertNotEquals("nor under its dimension id",
                "parallelConnectionGap", limiting);
    }

    // ---- Fixtures ----

    private static AssessLayoutResultDto dtoWith(int anchorDrift, int lateralJog) {
        return dtoWith(anchorDrift, lateralJog, 0);
    }

    private static AssessLayoutResultDto dtoWith(int anchorDrift, int lateralJog,
            int hAxisParallelGapNarrow25) {
        AssessLayoutResultDto base = AssessLayoutResultDto.degenerate(
                "v1", 2, 1, 0, List.of(), List.of(), 0, List.of(), 0, List.of(),
                0, List.of(), List.of(), java.util.Map.of(), List.of());
        // Rebuild through the canonical accessor surface, varying only the two new counts.
        return new AssessLayoutResultDto(
                base.viewId(), base.elementCount(), base.connectionCount(), base.overlapCount(),
                base.containmentOverlaps(), base.edgeCrossingCount(), base.crossingsPerConnection(),
                base.averageSpacing(), base.alignmentScore(), base.overallRating(),
                base.ratingBreakdown(), base.overlaps(), base.boundaryViolations(),
                base.connectionPassThroughs(), base.offCanvasWarnings(), base.labelOverlapCount(),
                base.labelOverlaps(), base.orphanedConnections(),
                base.orphanedConnectionDescriptions(), base.noteOverlapCount(),
                base.noteOverlapDescriptions(), base.noteClipCount(), base.noteClipDescriptions(),
                base.hasGroups(), base.coincidentSegmentCount(), base.nonOrthogonalTerminalCount(),
                base.contentBounds(), base.labelTruncationCount(), base.labelTruncations(),
                base.parentLabelObscuredCount(), base.parentLabelObscuredDescriptions(),
                base.imageSiblingOverlapCount(), base.imageSiblingOverlapDescriptions(),
                base.overlayIconCollisionCount(), base.overlayIconCollisionDescriptions(),
                base.violatorIds(), base.suggestions(), base.interiorTerminationCount(),
                base.interiorTerminationDescriptions(), base.zigzagCount(),
                base.zigzagDescriptions(), base.connectionEdgeCoincidenceCount(),
                base.edgeCoincidenceDescriptions(), base.hubPortQualityScore(),
                base.hubPortQualityFaces(), base.layoutRating(), base.routingRating(),
                base.corridorUtilisationScore(), base.corridorUtilisationChannels(),
                base.vAxisParallelGapP10(), base.vAxisParallelGapNarrow25Count(),
                hAxisParallelGapNarrow25,
                base.parallelConnectionGapDetail(), base.hubNeighbourClearanceMin(),
                base.coverage(), base.connectionThroughNoteCount(),
                base.connectionThroughNoteDescriptions(), base.connectionRedundantBendpointCount(),
                base.connectionRedundantBendpointDescriptions(),
                base.nonOrthogonalInteriorSegmentCount(),
                base.nonOrthogonalInteriorSegmentDescriptions(),
                base.containerFillEqualsChildCount(), base.containerFillEqualsChildDescriptions(),
                base.connectionGrazesVisualCount(), base.connectionGrazesVisualDescriptions(),
                base.labelOnNoteCount(), base.labelOnNoteDescriptions(), base.labelOnGroupCount(),
                base.labelOnGroupDescriptions(), base.edgeCoincidenceGrazedElementCount(),
                base.offFaceParallelTerminalCount(), base.offFaceParallelTerminalDescriptions(),
                base.coincidentFacePortCount(), base.coincidentFacePortDescriptions(),
                base.ownIconOverLabelCount(), base.ownIconOverLabelDescriptions(),
                base.cousinOverlapCount(), base.cousinOverlaps(), base.boundaryViolationCount(),
                anchorDrift, null, lateralJog, null,
                base.zeroBendpointNonOrthogonalTerminalCount(),
                base.routedNonOrthogonalTerminalCount(),
                base.unsizedHubs(), base.contextualPartialDimensions(),
                base.crossElementPassThroughCount());
    }

    /** A connection whose anchors agree and whose route has no reversal. */
    private static AssessmentConnection cleanConnection() {
        return new AssessmentConnection("c", "a", "b",
                List.of(new double[]{50.0, 50.0}, new double[]{200.0, 50.0},
                        new double[]{200.0, 400.0}, new double[]{350.0, 400.0}),
                "", 0, RelativePositionFeature.CENTER, 0.0, 0.0);
    }

    /** Same route, carrying a large anchor disagreement. */
    private static AssessmentConnection driftedConnection() {
        return new AssessmentConnection("c", "a", "b",
                List.of(new double[]{50.0, 50.0}, new double[]{200.0, 50.0},
                        new double[]{200.0, 400.0}, new double[]{350.0, 400.0}),
                "", 0, RelativePositionFeature.CENTER, 60.0, 40.0);
    }

    /**
     * A connection carrying a four-point window whose jog is exactly {@code jog} px wide — a
     * reversal at or below the threshold, a legitimate detour above it. Anchors agree.
     */
    private static AssessmentConnection jogConnection(double jog) {
        return new AssessmentConnection("c2", "a", "b",
                List.of(new double[]{50.0, 50.0}, new double[]{120.0, 50.0},
                        new double[]{120.0, 150.0}, new double[]{120.0 + jog, 150.0},
                        new double[]{120.0 + jog, 80.0}, new double[]{350.0, 400.0}),
                "", 0, RelativePositionFeature.CENTER, 0.0, 0.0);
    }

    private static List<AssessmentNode> nodes() {
        return Arrays.asList(
                node("a", 0, 20, 100, 60),
                node("b", 300, 370, 100, 60));
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, "", 0.0,
                null, null, 0.0, 0.0, 0.0);
    }
}
