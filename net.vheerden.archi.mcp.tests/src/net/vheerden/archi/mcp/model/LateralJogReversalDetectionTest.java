package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Lateral-jog-reversal detection: a route that doubles back through a sidestep too narrow to be a
 * detour.
 *
 * <p>The shape needs four points to state — two opposite-direction arms on one axis, separated by a
 * perpendicular jog — which is why the three-point zigzag predicate cannot express it at any
 * tolerance. Fixture connections are deliberately <strong>unnamed</strong> so no test reaches the
 * SWT text-measurement path.
 */
public class LateralJogReversalDetectionTest {

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    /**
     * Artefact #3 — AWS Global Accelerator → ALB, the real stored seven-point path. Up 10px,
     * sidestep 7px, down 399px. This connection's anchors agree exactly, so the path below is
     * literally what Archi draws.
     */
    private static List<double[]> artefact3Path() {
        return List.of(
                new double[]{480.0, 467.5},
                new double[]{561.0, 467.5},
                new double[]{561.0, 457.5},
                new double[]{568.0, 457.5},
                new double[]{568.0, 856.5},
                new double[]{4473.0, 856.5},
                new double[]{4473.0, 817.5});
    }

    @Test
    public void shouldCountTheReversal_whenTwoOppositeArmsAreJoinedByANarrowJog() {
        LayoutAssessmentResult result = assessOn(artefact3Path());

        assertEquals(1, result.lateralJogReversalCount());
        Set<String> violators = result.violatorIds().get("lateralJogReversals");
        assertNotNull("violator key present", violators);
        assertTrue(violators.contains("artefact-3"));
    }

    /**
     * The point of the dimension: on this exact path, it is the only thing that can see the shape.
     * If either sibling reversal dimension ever starts reporting it, the new dimension has become a
     * duplicate and this test says so.
     */
    @Test
    public void shouldBeTheOnlyDimensionThatSeesIt_whenTheZigzagAndRedundantPredicatesAreRun() {
        LayoutAssessmentResult result = assessOn(artefact3Path());

        assertEquals("no triple in the window shares an axis", 0, result.zigzagCount());
        assertEquals("no point is axis-collinear and removable",
                0, result.connectionRedundantBendpointCount());
        assertEquals(1, result.lateralJogReversalCount());
    }

    /**
     * The description carries the four coordinates of the offending window, not just a count — and
     * carries them <strong>exactly</strong>.
     *
     * <p>The half pixel is not incidental. An element centre is {@code x + width / 2}, so a
     * reconstructed coordinate is half-integral whenever the box dimension is odd, and that is the
     * same representable-precision effect the anchor-drift noise floor is derived from. Rounding
     * {@code 467.5} to {@code 468} would report a coordinate the model does not hold, in the field
     * whose whole job is to state the window as fact.
     */
    @Test
    public void shouldNameTheFourExactCoordinatesOfTheWindow_whenDescribingAReversal() {
        LayoutAssessmentResult result = assessOn(artefact3Path());

        List<String> descriptions = result.lateralJogReversalDescriptions();
        assertEquals(1, descriptions.size());
        String d = descriptions.get(0);
        assertTrue("names the connection: " + d, d.contains("artefact-3"));
        assertTrue("names the endpoints: " + d, d.contains("accelerator") && d.contains("alb"));
        assertTrue("carries point a exactly: " + d, d.contains("(561,467.5)"));
        assertTrue("carries point b exactly: " + d, d.contains("(561,457.5)"));
        assertTrue("carries point c exactly: " + d, d.contains("(568,457.5)"));
        assertTrue("carries point d exactly: " + d, d.contains("(568,856.5)"));
        assertFalse("must not round the half pixel away: " + d,
                d.contains("(561,468)") || d.contains("(568,857)"));
    }

    // ---- The threshold must discriminate, not merely fire ----

    /**
     * The same real path contains a SECOND four-point window with opposite arms — the long haul
     * across the canvas, whose jog is 3905px. A detector without an upper bound on the jog would
     * report a legitimate detour as a reversal. The count of 1 above is what proves the bound works;
     * this test states the reason explicitly.
     */
    @Test
    public void shouldNotReportTheDetourWindow_whenTheJogIsWiderThanAStub() {
        // Exactly the second window of artefact #3, in isolation: down 399, jog 3905, up 39.
        List<double[]> detourOnly = List.of(
                new double[]{568.0, 457.5},
                new double[]{568.0, 856.5},
                new double[]{4473.0, 856.5},
                new double[]{4473.0, 817.5});

        assertEquals(0, assessOn(detourOnly).lateralJogReversalCount());
    }

    /** A jog at the boundary is included; one past it is a detour. */
    @Test
    public void shouldIncludeAJogAtTheThreshold_andExcludeOneBeyondIt() {
        assertEquals("8px jog is at the stub minimum", 1, assessOn(jogPath(8.0)).lateralJogReversalCount());
        assertEquals("9px jog is a detour", 0, assessOn(jogPath(9.0)).lateralJogReversalCount());
    }

    /**
     * Narrowing the jog does not hand the shape back to the zigzag dimension. Even a sub-pixel
     * sidestep puts the two arms on two parallel lines, so no triple in the window ever carries two
     * live arms — the shape needs four points to state at any jog width, which is the whole reason
     * this dimension exists rather than a looser {@code ZIGZAG_AXIS_TOLERANCE_PX}.
     */
    @Test
    public void shouldStillOwnTheShape_whenTheJogIsNarrowerThanTheAxisTolerance() {
        LayoutAssessmentResult result = assessOn(jogPath(0.5));

        assertEquals("the zigzag predicate cannot see it", 0, result.zigzagCount());
        assertEquals("this dimension can", 1, result.lateralJogReversalCount());
    }

    /** Same-direction arms are a staircase, not a reversal. */
    @Test
    public void shouldNotReport_whenBothArmsRunTheSameDirection() {
        List<double[]> staircase = List.of(
                new double[]{100.0, 100.0},
                new double[]{100.0, 150.0},
                new double[]{107.0, 150.0},
                new double[]{107.0, 200.0});

        assertEquals(0, assessOn(staircase).lateralJogReversalCount());
    }

    /** The symmetric orientation: horizontal arms joined by a vertical jog. */
    @Test
    public void shouldReportTheSymmetricOrientation_whenHorizontalArmsAreJoinedByAVerticalJog() {
        List<double[]> horizontal = List.of(
                new double[]{100.0, 100.0},
                new double[]{200.0, 100.0},
                new double[]{200.0, 106.0},
                new double[]{140.0, 106.0});

        assertEquals(1, assessOn(horizontal).lateralJogReversalCount());
    }

    /** An arm shorter than the minimum delta is noise, not a reversal. */
    @Test
    public void shouldNotReport_whenAnArmIsBelowTheMinimumDelta() {
        List<double[]> tinyArm = List.of(
                new double[]{100.0, 100.0},
                new double[]{100.0, 100.5},
                new double[]{107.0, 100.5},
                new double[]{107.0, 50.0});

        assertEquals(0, assessOn(tinyArm).lateralJogReversalCount());
    }

    // ---- Classification precedence (a connection is never counted twice) ----

    /**
     * A connection already reported as a zigzag is not counted here as well, mirroring the
     * precedence {@code countZigzags} already applies to pass-throughs.
     */
    @Test
    public void shouldSkipAConnectionAlreadyCountedAsAZigzag_whenBothShapesArePresent() {
        // A zigzag triple (shared X, opposite arms) followed by a lateral-jog window.
        List<double[]> both = List.of(
                new double[]{100.0, 100.0},
                new double[]{100.0, 200.0},
                new double[]{100.0, 150.0},
                new double[]{100.0, 250.0},
                new double[]{107.0, 250.0},
                new double[]{107.0, 180.0});

        LayoutAssessmentResult result = assessOn(both);

        assertEquals("the zigzag claims it", 1, result.zigzagCount());
        assertEquals("so this dimension does not", 0, result.lateralJogReversalCount());
    }

    /**
     * The precedence guard must hold on the {@code includeViolatorIds=false} path too. Before this
     * story {@code countZigzags} returned an empty violator set when the flag was off, which would
     * have let the same connection be counted under both dimensions depending only on whether the
     * caller asked for violator IDs.
     */
    @Test
    public void shouldApplyPrecedence_whenViolatorIdsAreNotRequested() {
        List<double[]> both = List.of(
                new double[]{100.0, 100.0},
                new double[]{100.0, 200.0},
                new double[]{100.0, 150.0},
                new double[]{100.0, 250.0},
                new double[]{107.0, 250.0},
                new double[]{107.0, 180.0});

        LayoutAssessmentResult withIds = assessor.assess(nodes(), List.of(conn(both)), true);
        LayoutAssessmentResult withoutIds = assessor.assess(nodes(), List.of(conn(both)), false);

        assertEquals("the count must not depend on the violator-id flag",
                withIds.lateralJogReversalCount(), withoutIds.lateralJogReversalCount());
        assertEquals(0, withoutIds.lateralJogReversalCount());
    }

    // ---- Rating isolation and coverage ----

    @Test
    public void shouldLeaveTheRatingByteIdentical_whenAReversalIsPresent() {
        LayoutAssessmentResult clean = assessOn(List.of(
                new double[]{480.0, 467.5}, new double[]{561.0, 467.5},
                new double[]{561.0, 856.5}, new double[]{4473.0, 856.5},
                new double[]{4473.0, 817.5}));
        LayoutAssessmentResult reversing = assessOn(artefact3Path());

        assertEquals(0, clean.lateralJogReversalCount());
        assertEquals(1, reversing.lateralJogReversalCount());
        assertEquals(clean.overallRating(), reversing.overallRating());
        assertEquals(clean.layoutRating(), reversing.layoutRating());
        assertEquals(clean.routingRating(), reversing.routingRating());
        assertEquals(clean.ratingBreakdown(), reversing.ratingBreakdown());
    }

    @Test
    public void shouldDeclareCoverageChecked_whenTheDetectorRan() {
        assertEquals("checked", assessOn(artefact3Path()).coverage().get("lateralJogReversals"));
    }

    // ---- Fixtures ----

    /** A four-point vertical-arm reversal whose jog is exactly {@code jog} px wide. */
    private static List<double[]> jogPath(double jog) {
        return List.of(
                new double[]{100.0, 100.0},
                new double[]{100.0, 150.0},
                new double[]{100.0 + jog, 150.0},
                new double[]{100.0 + jog, 60.0});
    }

    private LayoutAssessmentResult assessOn(List<double[]> path) {
        return assessor.assess(nodes(), List.of(conn(path)), true);
    }

    private static AssessmentConnection conn(List<double[]> path) {
        return new AssessmentConnection("artefact-3", "accelerator", "alb", path, "", 0);
    }

    private static List<AssessmentNode> nodes() {
        return Arrays.asList(
                node("accelerator", 420, 440, 120, 55),
                node("alb", 4413, 790, 120, 55));
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, "", 0.0,
                null, null, 0.0, 0.0, 0.0);
    }
}
