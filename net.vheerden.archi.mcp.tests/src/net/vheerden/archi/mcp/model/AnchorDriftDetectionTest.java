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
 * Anchor-drift detection: the connections whose stored route was computed against a geometry that
 * has since moved.
 *
 * <p>Archi stores every bendpoint <em>twice</em> — once relative to the source centre and once
 * relative to the target centre — and both describe the same absolute point at the moment the route
 * was written. When an endpoint moves or is resized afterwards the two reconstructions stop
 * agreeing, and the drawn polyline no longer relates to the elements it was routed around.
 *
 * <p>Fixture connections are deliberately <strong>unnamed</strong>: a named connection reaches the
 * SWT text-measurement path, which raises a caught {@code SWTException} on macOS but an uncaught
 * {@code SWTError} on Linux, so a green local run would not establish headless safety.
 */
public class AnchorDriftDetectionTest {

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    // ---- Artefact #1 and #2: the two drifted connections on the semantic view ----

    /**
     * The measured geometry of the two drifted connections, reproduced from the saved model.
     * Source/target boxes and the stored (start,end) anchor pairs are the real values; the drift
     * falls out of them rather than being asserted into them.
     */
    @Test
    public void shouldCountBothDriftedConnections_whenTheStoredAnchorsDisagree() {
        List<AssessmentNode> nodes = semanticViewNodes();
        List<AssessmentConnection> connections = List.of(
                artefact1(), artefact2(), driftedConnection("clean-1", 0.0, 0.0));

        LayoutAssessmentResult result = assessor.assess(nodes, connections, true);

        assertEquals("both drifted connections counted", 2, result.anchorDriftCount());
        Set<String> violators = result.violatorIds().get("anchorDrift");
        assertNotNull("anchorDrift violator key present", violators);
        assertTrue("artefact #1 named", violators.contains("artefact-1"));
        assertTrue("artefact #2 named", violators.contains("artefact-2"));
        assertFalse("the undrifted connection is not named", violators.contains("clean-1"));
    }

    /**
     * The noise floor must discriminate, not merely fire. Every other connection on the semantic
     * view sits at 0.0 or 0.5 px of anchor disagreement; none of them may be reported.
     */
    @Test
    public void shouldNameNoConnectionAtTheNoiseFloor_whenDisagreementIsSubPixel() {
        List<AssessmentNode> nodes = semanticViewNodes();
        List<AssessmentConnection> connections = new ArrayList<>();
        // 17 connections at exactly the 0.5px odd-centre floor, 14 at exactly 0.0 — the real split.
        for (int i = 0; i < 17; i++) {
            connections.add(driftedConnection("floor-" + i, 0.5, 0.5));
        }
        for (int i = 0; i < 14; i++) {
            connections.add(driftedConnection("exact-" + i, 0.0, 0.0));
        }

        LayoutAssessmentResult result = assessor.assess(nodes, connections, true);

        assertEquals("nothing at or below the floor is reported", 0, result.anchorDriftCount());
        assertTrue("no violator key when the count is zero",
                result.violatorIds().get("anchorDrift") == null
                        || result.violatorIds().get("anchorDrift").isEmpty());
    }

    /**
     * The full semantic-view population: 33 connections, of which exactly the two owner-flagged ones
     * drift. Proves the floor discriminates across the whole real distribution rather than against a
     * hand-picked pair.
     */
    @Test
    public void shouldNameExactlyTheTwoOwnerArtefacts_whenAllThirtyThreeConnectionsAreAssessed() {
        List<AssessmentNode> nodes = semanticViewNodes();
        List<AssessmentConnection> connections = new ArrayList<>();
        connections.add(artefact1());
        connections.add(artefact2());
        for (int i = 0; i < 17; i++) {
            connections.add(driftedConnection("floor-" + i, 0.5, 0.5));
        }
        for (int i = 0; i < 14; i++) {
            connections.add(driftedConnection("exact-" + i, 0.0, 0.0));
        }
        assertEquals("the real view's connection count", 33, connections.size());

        LayoutAssessmentResult result = assessor.assess(nodes, connections, true);

        assertEquals(2, result.anchorDriftCount());
        Set<String> violators = result.violatorIds().get("anchorDrift");
        assertEquals("exactly the two owner artefacts", Set.of("artefact-1", "artefact-2"), violators);
    }

    /** The measured drift is reported as a fact per axis, not as a flag. */
    @Test
    public void shouldReportMeasuredDriftPerAxis_whenDescribingADriftedConnection() {
        List<AssessmentNode> nodes = semanticViewNodes();
        LayoutAssessmentResult result =
                assessor.assess(nodes, List.of(artefact1(), artefact2()), true);

        List<String> descriptions = result.anchorDriftDescriptions();
        assertEquals(2, descriptions.size());
        String first = descriptions.stream().filter(d -> d.contains("artefact-1")).findFirst().orElseThrow();
        assertTrue("names the source node: " + first, first.contains("mobile"));
        assertTrue("names the target node: " + first, first.contains("mno"));
        assertTrue("carries the measured x drift: " + first, first.contains("30.5"));
        assertTrue("carries the measured y drift: " + first, first.contains("10.5"));

        String second = descriptions.stream().filter(d -> d.contains("artefact-2")).findFirst().orElseThrow();
        assertTrue("carries the measured x drift: " + second, second.contains("49.5"));
        assertTrue("carries the measured y drift: " + second, second.contains("10.5"));
    }

    /**
     * The remedy must send the agent to re-route the connection. A drifted route is not a
     * straightening failure — the stored shape was correct when written and the geometry moved
     * underneath it, so a straightener pass cannot repair it.
     */
    @Test
    public void shouldAdviseReRouting_andNotNameTheStraightener_whenDriftIsReported() {
        List<AssessmentNode> nodes = semanticViewNodes();
        LayoutAssessmentResult result =
                assessor.assess(nodes, List.of(artefact1(), artefact2()), true);

        String remedy = result.suggestions().stream()
                .filter(s -> s.toLowerCase().contains("anchor drift"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no anchor-drift remedy in " + result.suggestions()));
        assertTrue("advises re-routing: " + remedy, remedy.contains("re-route"));
        assertFalse("must not blame the straightener: " + remedy, remedy.contains("PathStraightener"));
        assertFalse("must not blame collinear removal: " + remedy, remedy.contains("removeCollinearPoints"));
    }

    /**
     * The floor's exact value, which the whole derivation turns on and no other test touches.
     *
     * <p>Two independent roundings of ±0.5 px mean an <em>unmoved</em> point's two reconstructions
     * can differ by as much as exactly 1.0 px, so 1.0 must NOT be reported. The first value that
     * cannot be rounding must be. Without this pair, flipping the skip condition between
     * {@code <=} and {@code <} would leave every other test green.
     */
    @Test
    public void shouldNotReportExactlyTheFloor_butShouldReportTheFirstValueAboveIt() {
        List<AssessmentNode> nodes = semanticViewNodes();

        assertEquals("exactly 1.0px is the worst innocent double rounding — not drift",
                0, assessor.assess(nodes, List.of(driftedConnection("at-floor", 1.0, 1.0)), true)
                        .anchorDriftCount());
        assertEquals("a hair above the floor cannot be rounding — it is drift",
                1, assessor.assess(nodes, List.of(driftedConnection("just-over", 1.0001, 0.0)), true)
                        .anchorDriftCount());
        assertEquals("and on the other axis alone, likewise",
                1, assessor.assess(nodes, List.of(driftedConnection("just-over-y", 0.0, 1.0001)), true)
                        .anchorDriftCount());
    }

    /**
     * Drift on either axis alone is drift. An endpoint that moved vertically leaves the X anchors
     * agreeing perfectly, so a detector that reads only the X axis reports a clean view.
     */
    @Test
    public void shouldReportDrift_whenOnlyOneAxisDisagrees() {
        List<AssessmentNode> nodes = semanticViewNodes();

        LayoutAssessmentResult yOnly =
                assessor.assess(nodes, List.of(driftedConnection("y-only", 0.0, 30.0)), true);
        assertEquals("vertical-only drift is reported", 1, yOnly.anchorDriftCount());

        LayoutAssessmentResult xOnly =
                assessor.assess(nodes, List.of(driftedConnection("x-only", 30.0, 0.0)), true);
        assertEquals("horizontal-only drift is reported", 1, xOnly.anchorDriftCount());
    }

    // ---- The structural claim: the collector averages the disagreement away ----

    /**
     * Proves by construction that anchor drift is <strong>structurally</strong> invisible to the
     * existing dimensions rather than merely under-tuned. Two different (start,end) anchor pairs
     * that share a midpoint reconstruct to the identical polyline, so every detector downstream of
     * {@code AssessmentCollector} is handed byte-identical input and cannot distinguish them.
     */
    @Test
    public void shouldBeIndistinguishableToEveryDetector_whenTwoAnchorPairsShareAMidpoint() {
        List<AssessmentNode> nodes = semanticViewNodes();
        // Same midpoint polyline, wildly different anchor disagreement.
        AssessmentConnection agreeing = driftedConnection("c", 0.0, 0.0);
        AssessmentConnection drifting = driftedConnection("c", 120.0, 80.0);
        assertEquals("the reconstructed polylines are identical",
                pathOf(agreeing), pathOf(drifting));

        LayoutAssessmentResult withoutDrift = assessor.assess(nodes, List.of(agreeing), true);
        LayoutAssessmentResult withDrift = assessor.assess(nodes, List.of(drifting), true);

        // Every pre-existing dimension reads the same on both — the disagreement never reaches them.
        assertEquals(withoutDrift.zigzagCount(), withDrift.zigzagCount());
        assertEquals(withoutDrift.connectionRedundantBendpointCount(),
                withDrift.connectionRedundantBendpointCount());
        assertEquals(withoutDrift.nonOrthogonalTerminalCount(), withDrift.nonOrthogonalTerminalCount());
        assertEquals(withoutDrift.interiorTerminationCount(), withDrift.interiorTerminationCount());
        assertEquals(withoutDrift.connectionEdgeCoincidenceCount(),
                withDrift.connectionEdgeCoincidenceCount());
        assertEquals(withoutDrift.edgeCrossingCount(), withDrift.edgeCrossingCount());
        assertEquals(withoutDrift.overallRating(), withDrift.overallRating());
        // Only the new dimension separates them.
        assertEquals(0, withoutDrift.anchorDriftCount());
        assertEquals(1, withDrift.anchorDriftCount());
    }

    // ---- Rating isolation ----

    /** The dimension is informational: it may not move the rating or any of its inputs. */
    @Test
    public void shouldLeaveTheRatingByteIdentical_whenDriftIsPresent() {
        List<AssessmentNode> nodes = semanticViewNodes();
        LayoutAssessmentResult clean =
                assessor.assess(nodes, List.of(driftedConnection("c", 0.0, 0.0)), true);
        LayoutAssessmentResult drifted =
                assessor.assess(nodes, List.of(driftedConnection("c", 400.0, 400.0)), true);

        assertEquals(clean.overallRating(), drifted.overallRating());
        assertEquals(clean.layoutRating(), drifted.layoutRating());
        assertEquals(clean.routingRating(), drifted.routingRating());
        assertEquals(clean.ratingBreakdown(), drifted.ratingBreakdown());
    }

    // ---- Coverage ----

    /** A dimension that ran reports {@code checked} — never a bare zero standing in for silence. */
    @Test
    public void shouldDeclareCoverageChecked_whenTheDetectorRan() {
        List<AssessmentNode> nodes = semanticViewNodes();
        LayoutAssessmentResult result =
                assessor.assess(nodes, List.of(driftedConnection("c", 0.0, 0.0)), true);

        assertEquals("checked", result.coverage().get("anchorDrift"));
    }

    // ---- Fixtures ----

    /**
     * Artefact #1 — Mobile App → MNO/ISP. Source box (20,440,120,55), target box (210,430,121,84).
     * Four bendpoints, each drifting by exactly (30.5, 10.5): the signature of one endpoint moving
     * rigidly after the route was written.
     */
    private static AssessmentConnection artefact1() {
        return new AssessmentConnection("artefact-1", "mobile", "mno",
                midpointPath(80.0, 467.5, 270.5, 472.0,
                        new double[][]{{61, 0, -99, -15}, {92, 0, -68, -15},
                                {92, 15, -68, 0}, {99, 15, -61, 0}}),
                "", 0, RelativePositionFeature.CENTER, 30.5, 10.5);
    }

    /**
     * Artefact #2 — MNO/ISP → AWS Global Accelerator. Source box (210,430,121,84), target box
     * (420,440,120,55). Four bendpoints, each drifting by exactly (49.5, 10.5).
     */
    private static AssessmentConnection artefact2() {
        return new AssessmentConnection("artefact-2", "mno", "accelerator",
                midpointPath(270.5, 472.0, 480.0, 467.5,
                        new double[][]{{62, 0, -98, 15}, {92, 0, -68, 15},
                                {92, -15, -68, 0}, {99, -15, -61, 0}}),
                "", 0, RelativePositionFeature.CENTER, 49.5, 10.5);
    }

    /** A straight two-segment route carrying the given per-axis anchor disagreement. */
    private static AssessmentConnection driftedConnection(String id, double driftX, double driftY) {
        return new AssessmentConnection(id, "mobile", "mno",
                List.of(new double[]{80.0, 467.5}, new double[]{150.0, 467.5},
                        new double[]{150.0, 472.0}, new double[]{270.5, 472.0}),
                "", 0, RelativePositionFeature.CENTER, driftX, driftY);
    }

    private static List<String> pathOf(AssessmentConnection conn) {
        List<String> out = new ArrayList<>();
        for (double[] p : conn.pathPoints()) {
            out.add(p[0] + "," + p[1]);
        }
        return out;
    }

    /**
     * Reconstructs the collector's midpoint polyline from stored (startX,startY,endX,endY) anchor
     * quads, so the fixture holds the real stored values rather than pre-chewed coordinates.
     */
    private static List<double[]> midpointPath(double srcCx, double srcCy,
            double tgtCx, double tgtCy, double[][] anchors) {
        List<double[]> path = new ArrayList<>();
        path.add(new double[]{srcCx, srcCy});
        for (double[] a : anchors) {
            path.add(new double[]{(a[0] + srcCx + a[2] + tgtCx) / 2, (a[1] + srcCy + a[3] + tgtCy) / 2});
        }
        path.add(new double[]{tgtCx, tgtCy});
        return path;
    }

    private static List<AssessmentNode> semanticViewNodes() {
        return Arrays.asList(
                node("mobile", 20, 440, 120, 55),
                node("mno", 210, 430, 121, 84),
                node("accelerator", 420, 440, 120, 55));
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, "", 0.0,
                null, null, 0.0, 0.0, 0.0);
    }
}
