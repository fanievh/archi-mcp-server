package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link LayoutQualityAssessor} — pure geometry computation (Story 9-2).
 * No EMF or SWT runtime required.
 */
public class LayoutQualityAssessorTest {

    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() {
        assessor = new LayoutQualityAssessor();
    }

    // ---- Overlap tests ----

    @Test
    public void assess_noOverlaps_shouldReturnZeroOverlapCount() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),
                node("c", 0, 100, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(0, result.overlapCount());
        assertTrue(result.overlaps().isEmpty());
    }

    @Test
    public void assess_withOverlaps_shouldCountOverlappingPairs() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 50, 25, 100, 50),  // overlaps a
                node("c", 300, 0, 100, 50));  // no overlap

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(1, result.overlapCount());
        assertEquals(1, result.overlaps().size());
        assertTrue(result.overlaps().get(0).contains("'a'"));
        assertTrue(result.overlaps().get(0).contains("'b'"));
    }

    @Test
    public void assess_adjacentElements_shouldNotCountAsOverlap() {
        // Elements that touch but don't overlap (edge case: right edge of a == left edge of b)
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 100, 0, 100, 50));  // touching, not overlapping

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(0, result.overlapCount());
    }

    @Test
    public void assess_parentChildOverlap_shouldNotCount() {
        // Finding #2: A group containing a child — overlapping rects but parent-child relationship
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("child1", 50, 50, 100, 50, "grp"),
                childNode("child2", 200, 50, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // The group overlaps both children geometrically, but should be excluded
        assertEquals(0, result.overlapCount());
        // Story 9-0d: containment overlaps tracked separately
        assertEquals(2, result.containmentOverlapCount());
    }

    @Test
    public void assess_siblingOverlap_insideGroup_shouldCount() {
        // Two children inside a group that overlap each other — NOT parent-child, should count
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("child1", 50, 50, 100, 50, "grp"),
                childNode("child2", 100, 50, 100, 50, "grp"));  // overlaps child1

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // Story 9-0d: verify sibling overlap counted, containment tracked separately
        assertEquals(1, result.overlapCount());
        assertTrue(result.overlaps().get(0).contains("'child1'"));
        assertTrue(result.overlaps().get(0).contains("'child2'"));
        // grp overlaps both children = 2 containment overlaps
        assertEquals(2, result.containmentOverlapCount());
    }

    // ---- Edge crossing tests ----

    @Test
    public void assess_noCrossings_shouldReturnZeroCrossingCount() {
        // Two parallel horizontal connections
        List<AssessmentNode> nodes = createFourNodeGrid();
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}), "", 1),
                new AssessmentConnection("c2", "c", "d",
                        List.of(new double[]{50, 125}, new double[]{250, 125}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);

        assertEquals(0, result.edgeCrossingCount());
    }

    @Test
    public void assess_withCrossings_shouldCountIntersections() {
        // Two connections forming an X
        List<AssessmentNode> nodes = createFourNodeGrid();
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "d",
                        List.of(new double[]{50, 25}, new double[]{250, 125}), "", 1),
                new AssessmentConnection("c2", "b", "c",
                        List.of(new double[]{250, 25}, new double[]{50, 125}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);

        assertEquals(1, result.edgeCrossingCount());
    }

    @Test
    public void assess_sharedEndpointConnections_thatCross_shouldCount() {
        // Finding #8: Two connections from same source that fan out and cross
        // These share source "a" but the paths cross each other
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 50, 20, 20),
                node("b", 200, 0, 20, 20),
                node("c", 200, 100, 20, 20));

        // From a-center(10,60) to b-center(210,10) and from a-center(10,60) to c-center(210,110)
        // These don't cross since they share starting point and fan out
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{10, 60}, new double[]{210, 10}), "", 1),
                new AssessmentConnection("c2", "a", "c",
                        List.of(new double[]{10, 60}, new double[]{210, 110}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);

        // Fan-out from same source doesn't cross — segments share starting point
        assertEquals(0, result.edgeCrossingCount());
    }

    // ---- Spacing tests ----

    @Test
    public void assess_evenlySpacedElements_shouldReturnConsistentSpacing() {
        // Elements in a row with 50px gap between edges
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 150, 0, 100, 50),
                node("c", 300, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // Each element's nearest neighbor is 50px away
        assertEquals(50.0, result.averageSpacing(), 0.1);
    }

    @Test
    public void assess_clusteredElements_shouldReturnSmallSpacing() {
        // Elements very close together (5px gap)
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 105, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(5.0, result.averageSpacing(), 0.1);
    }

    @Test
    public void assess_spacingExcludesParentChild() {
        // Finding #4: Group with children — spacing should exclude group-child distances
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("c1", 50, 50, 100, 50, "grp"),
                childNode("c2", 250, 50, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // Spacing should be between the two children (100px gap), not group-to-child (0px)
        assertEquals(100.0, result.averageSpacing(), 1.0);
    }

    // ---- Alignment tests ----

    @Test
    public void assess_perfectGridAlignment_shouldScore100() {
        // All elements share left-edge x=0 — perfectly aligned
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 0, 100, 100, 50),
                node("c", 0, 200, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(100, result.alignmentScore());
    }

    @Test
    public void assess_randomPositions_shouldScoreLow() {
        // Elements at random positions with no alignment
        List<AssessmentNode> nodes = List.of(
                node("a", 17, 33, 100, 50),
                node("b", 241, 87, 80, 40),
                node("c", 123, 199, 110, 60),
                node("d", 367, 11, 90, 45));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertTrue("Alignment should be low for random positions",
                result.alignmentScore() < 50);
    }

    @Test
    public void assess_alignmentExcludesGroups() {
        // Finding #9: Groups should not participate in alignment scoring
        // Group at different position, children aligned
        List<AssessmentNode> nodes = List.of(
                group("grp", 100, 100, 400, 300),
                childNode("c1", 150, 150, 100, 50, "grp"),
                childNode("c2", 150, 250, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // Children share left-edge x=150 → 100% alignment (group excluded)
        assertEquals(100, result.alignmentScore());
    }

    @Test
    public void assess_emptyNodes_alignment_shouldReturnZero() {
        // Finding #12: empty input should return 0, not 100
        LayoutAssessmentResult result = assessor.assess(List.of(), List.of());
        assertEquals(0, result.alignmentScore());
    }

    @Test
    public void assess_singleNode_alignment_shouldReturnZero() {
        // Finding #12: single element should return 0, not 100
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of());
        assertEquals(0, result.alignmentScore());
    }

    // ---- Overall rating tests ----

    @Test
    public void assess_perfectLayout_shouldRateExcellent() {
        // No overlaps, no crossings, good spacing, good alignment
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 0, 100, 100, 50),
                node("c", 0, 200, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("excellent", result.overallRating());
    }

    @Test
    public void assess_terribleLayout_shouldRatePoor() {
        // Many overlapping elements
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            nodes.add(node("n" + i, 0, 0, 100, 50));
        }

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("poor", result.overallRating());
        assertTrue(result.overlapCount() > 3);
    }

    // ---- Boundary violation tests ----

    @Test
    public void assess_elementInsideParent_shouldNotViolate() {
        // Both in absolute coordinates: child at (50,50) is inside group at (0,0,400,300)
        List<AssessmentNode> nodes = List.of(
                group("group", 0, 0, 400, 300),
                childNode("child", 50, 50, 100, 50, "group"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertTrue(result.boundaryViolations().isEmpty());
    }

    @Test
    public void assess_elementOutsideParent_shouldViolate() {
        // In absolute coordinates: child at (150,100) with size (100,80) extends to (250,180)
        // Parent group at (0,0) with size (200,150) — child extends past right and bottom
        List<AssessmentNode> nodes = List.of(
                group("group", 0, 0, 200, 150),
                childNode("child", 150, 100, 100, 80, "group"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertFalse(result.boundaryViolations().isEmpty());
        assertTrue(result.boundaryViolations().get(0).contains("'child'"));
        assertTrue(result.boundaryViolations().get(0).contains("'group'"));
    }

    // ---- Off-canvas tests ----

    @Test
    public void assess_negativeCoordinates_shouldWarn() {
        List<AssessmentNode> nodes = List.of(
                node("a", -50, -30, 100, 50),
                node("b", 100, 100, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertFalse(result.offCanvasWarnings().isEmpty());
        assertTrue(result.offCanvasWarnings().get(0).contains("'a'"));
        assertTrue(result.offCanvasWarnings().get(0).contains("negative"));
    }

    @Test
    public void assess_veryLargeCoordinates_shouldWarn() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 11000, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertFalse(result.offCanvasWarnings().isEmpty());
        assertTrue(result.offCanvasWarnings().get(0).contains("'b'"));
        assertTrue(result.offCanvasWarnings().get(0).contains("beyond"));
    }

    // ---- Edge cases ----

    @Test
    public void assess_emptyNodes_shouldReturnEmptyResult() {
        LayoutAssessmentResult result = assessor.assess(List.of(), List.of());

        assertEquals(0, result.overlapCount());
        assertEquals(0, result.edgeCrossingCount());
        assertEquals(0.0, result.averageSpacing(), 0.001);
        assertEquals(0, result.alignmentScore());
    }

    @Test
    public void assess_singleNode_shouldReturnTrivialResult() {
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(0, result.overlapCount());
        assertEquals(0.0, result.averageSpacing(), 0.001);
        assertEquals(0, result.alignmentScore());
    }

    // ---- Suggestion generation tests ----

    @Test
    public void assess_withOverlaps_shouldSuggestAutoLayoutAndRoute() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 50, 25, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertTrue(result.suggestions().stream()
                .anyMatch(s -> s.contains("overlapping") && s.contains("auto-layout-and-route")));
    }

    @Test
    public void assess_goodLayout_shouldSuggestNoImprovements() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 0, 100, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertTrue(result.suggestions().stream()
                .anyMatch(s -> s.contains("good") || s.contains("no immediate")));
    }

    @Test
    public void assess_largeView_shouldWarnAboutPerformance() {
        // Finding #7: Performance warning for large views
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            nodes.add(node("n" + i, i * 150, 0, 100, 50));
        }

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertTrue("Should warn about large view",
                result.suggestions().stream().anyMatch(s -> s.contains("501 elements")));
    }

    // ---- Line segment intersection utility tests ----

    @Test
    public void segmentsIntersect_crossingSegments_shouldReturnTrue() {
        assertTrue(LayoutQualityAssessor.segmentsIntersect(
                0, 0, 100, 100,   // diagonal line ↘
                100, 0, 0, 100)); // diagonal line ↙
    }

    @Test
    public void segmentsIntersect_parallelSegments_shouldReturnFalse() {
        assertFalse(LayoutQualityAssessor.segmentsIntersect(
                0, 0, 100, 0,   // horizontal line
                0, 10, 100, 10)); // parallel horizontal line
    }

    @Test
    public void segmentsIntersect_nonCrossingSegments_shouldReturnFalse() {
        assertFalse(LayoutQualityAssessor.segmentsIntersect(
                0, 0, 50, 50,     // short diagonal
                100, 100, 200, 200)); // distant diagonal
    }

    @Test
    public void lineSegmentIntersectsRect_throughRect_shouldReturnTrue() {
        assertTrue(LayoutQualityAssessor.lineSegmentIntersectsRect(
                0, 25, 200, 25,  // horizontal line through middle
                50, 0, 100, 50));  // rectangle at (50,0) size 100x50
    }

    @Test
    public void lineSegmentIntersectsRect_missingRect_shouldReturnFalse() {
        assertFalse(LayoutQualityAssessor.lineSegmentIntersectsRect(
                0, 0, 100, 0,   // horizontal line at y=0
                50, 50, 100, 50));  // rectangle below the line
    }

    // ---- Pass-through detection tests ----

    @Test
    public void assess_connectionPassingThroughElement_shouldDetect() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 100, 50, 50),
                node("mid", 200, 100, 50, 50),
                node("b", 400, 100, 50, 50));

        // Connection from a to b passes through 'mid'
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{25, 125}, new double[]{425, 125}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);

        assertFalse(result.connectionPassThroughs().isEmpty());
        assertTrue(result.connectionPassThroughs().get(0).contains("'mid'"));
    }

    @Test
    public void assess_connectionThroughParentGroup_shouldNotDetect() {
        // Finding #3: Connection between children of same group should not report
        // the group as a pass-through
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 500, 200),
                childNode("a", 50, 75, 50, 50, "grp"),
                childNode("b", 400, 75, 50, 50, "grp"));

        // Connection from a to b — path goes through parent group rectangle
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{75, 100}, new double[]{425, 100}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);

        // Group should NOT be reported as pass-through (it's an ancestor of both endpoints)
        assertTrue("Parent group should not be flagged as pass-through",
                result.connectionPassThroughs().isEmpty());
    }

    // ---- Descendant exclusion tests (pass-through fix) ----

    @Test
    public void assess_connectionFromParentThroughGrandchild_shouldNotDetect() {
        // Connection from parent element to external target passes through grandchild.
        // This is expected containment behavior, not a pass-through.
        // Parent "p" contains child group "grp" which contains grandchild "gc"
        List<AssessmentNode> nodes = List.of(
                node("p", 0, 0, 500, 300),
                childGroup("grp", 20, 20, 460, 260, "p"),
                childNode("gc", 50, 50, 100, 50, "grp"),
                node("ext", 700, 125, 100, 50));

        // Connection from p-center(250,150) to ext-center(750,150) passes through gc at (50,50,100,50)
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "p", "ext",
                        List.of(new double[]{250, 150}, new double[]{750, 150}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);

        assertTrue("Grandchild of source should not be flagged as pass-through",
                result.connectionPassThroughs().isEmpty());
    }

    @Test
    public void assess_connectionToParentThroughGrandchild_shouldNotDetect() {
        // Connection from external source to parent element passes through grandchild.
        List<AssessmentNode> nodes = List.of(
                node("ext", 0, 125, 100, 50),
                node("p", 200, 0, 500, 300),
                childGroup("grp", 220, 20, 460, 260, "p"),
                childNode("gc", 300, 100, 100, 50, "grp"));

        // Connection from ext-center(50,150) to p-center(450,150) through gc
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "ext", "p",
                        List.of(new double[]{50, 150}, new double[]{450, 150}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);

        assertTrue("Grandchild of target should not be flagged as pass-through",
                result.connectionPassThroughs().isEmpty());
    }

    // ---- Path clipping tests (visual fidelity fix) ----

    @Test
    public void assess_connectionNearbyElement_notOnVisualPath_shouldNotDetect() {
        // ChopboxAnchor exits toward TARGET center, not first bendpoint.
        // Source at (0,200,500,300), center=(250,350). Target at (900,0,100,100), center=(950,50).
        // Bendpoint at (600,300). Nearby element at (480,180,80,50).
        // Center-to-bendpoint line clips nearby, but the visual line
        // (exit toward target at top-right corner) misses it entirely.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 200, 500, 300),
                node("nearby", 480, 180, 80, 50),
                node("tgt", 900, 0, 100, 100));

        // Path: src-center(250,350) → bend(600,300) → tgt-center(950,50)
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "src", "tgt",
                        List.of(new double[]{250, 350}, new double[]{600, 300},
                                new double[]{950, 50}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);

        // OrthogonalAnchor exits source toward first bendpoint (600,300),
        // ref.x=600 outside (0-500), ref.y=300 inside (200-500) → right edge at y=300.
        // Clipped path from (500,300)→(600,300) misses 'nearby' at (480,180,80,50)
        assertTrue("Element near source but not on visual path should not be flagged",
                result.connectionPassThroughs().isEmpty());
    }

    @Test
    public void orthogonalExitPoint_refAbove_shouldExitTopEdgeAtRefX() {
        // Element (100,200,400,300). Reference at (350,50) — above, x inside bounds.
        // OrthogonalAnchor: x inside [100,500], y outside [200,500] → top edge at ref.x=350
        double[] exit = assessor.orthogonalExitPoint(100, 200, 400, 300, 350, 50);
        assertNotNull(exit);
        assertEquals(350.0, exit[0], 0.1);
        assertEquals(200.0, exit[1], 0.1);
    }

    @Test
    public void orthogonalExitPoint_refToRight_shouldExitRightEdgeAtRefY() {
        // Element (100,200,400,300). Reference at (600,350) — right, y inside bounds.
        double[] exit = assessor.orthogonalExitPoint(100, 200, 400, 300, 600, 350);
        assertNotNull(exit);
        assertEquals(500.0, exit[0], 0.1);
        assertEquals(350.0, exit[1], 0.1);
    }

    @Test
    public void orthogonalExitPoint_refDiagonal_shouldFallbackToRayIntersection() {
        // Element (100,200,400,300). Reference at (600,50) — both x and y outside.
        // Falls back to ChopboxAnchor ray from center (300,350) toward (600,50).
        double[] exit = assessor.orthogonalExitPoint(100, 200, 400, 300, 600, 50);
        assertNotNull(exit);
        // Ray exits from top edge (y=200) or right edge (x=500)
        assertTrue(exit[0] >= 100 && exit[0] <= 500);
        assertTrue(exit[1] >= 200 && exit[1] <= 500);
    }

    @Test
    public void assess_largeSourceOrthogonalExit_shouldNotFalsePositiveNearbyElement() {
        // Reproduces the AWS Connect → Voice & Chat iFrame through AWS SES case.
        // Large source element, bendpoint above and to the right, obstacle in between.
        // With ChopboxAnchor, the diagonal exit passes through the obstacle.
        // With OrthogonalAnchor, the nearly-vertical exit misses it.
        List<AssessmentNode> nodes = List.of(
                node("source", 264, 1020, 481, 289),   // like AWS Connect
                node("obstacle", 540, 828, 180, 133),   // like AWS SES
                node("target", 947, 276, 178, 241));     // like Voice & Chat iFrame

        // Path: src-center(504.5,1164.5) → bend(732,446) → tgt-center(1036,396.5)
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "source", "target",
                        List.of(new double[]{504.5, 1164.5}, new double[]{732, 446},
                                new double[]{1036, 396.5}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);

        // OrthogonalAnchor exits source at ref.x=732 (first bendpoint x, inside 264-745),
        // top edge y=1020 → exit at (732,1020). Line from (732,1020) to (732,446) is nearly
        // vertical, passing to the RIGHT of obstacle (right edge at 720). No pass-through.
        assertTrue("Obstacle to left of orthogonal exit path should not be flagged",
                result.connectionPassThroughs().isEmpty());
    }

    @Test
    public void rectExitPoint_horizontalExit_shouldReturnRightEdge() {
        // Point at center of (0,0,100,100), heading right
        double[] exit = assessor.rectExitPoint(50, 50, 200, 50, 0, 0, 100, 100);
        assertNotNull(exit);
        assertEquals(100.0, exit[0], 0.1);
        assertEquals(50.0, exit[1], 0.1);
    }

    @Test
    public void rectExitPoint_diagonalExit_shouldReturnEdgePoint() {
        // Point at center of (0,0,100,100), heading up-right at 45°
        double[] exit = assessor.rectExitPoint(50, 50, 150, -50, 0, 0, 100, 100);
        assertNotNull(exit);
        // Should exit through top edge (y=0) at x=100, or right edge (x=100) at y=0
        assertTrue(exit[0] >= 0 && exit[0] <= 100);
        assertTrue(exit[1] >= 0 && exit[1] <= 100);
    }

    @Test
    public void clipPathToVisualEdges_shouldClipBothEnds() {
        // Straight horizontal connection (no bendpoints) — reference is opposite center.
        // OrthogonalAnchor: target center x=250 is outside src (0-100), y=50 inside (0-100)
        // → exits from right edge at ref.y=50. Similarly for target.
        List<double[]> path = List.of(
                new double[]{50, 50},   // center of src
                new double[]{250, 50}); // center of tgt
        AssessmentNode src = node("src", 0, 0, 100, 100);
        AssessmentNode tgt = node("tgt", 200, 0, 100, 100);

        List<double[]> clipped = assessor.clipPathToVisualEdges(path, src, tgt);

        // Start should be clipped to right edge of src at ref.y=50
        assertEquals(100.0, clipped.get(0)[0], 0.1);
        assertEquals(50.0, clipped.get(0)[1], 0.1);
        // End should be clipped to left edge of tgt at ref.y=50
        assertEquals(200.0, clipped.get(1)[0], 0.1);
        assertEquals(50.0, clipped.get(1)[1], 0.1);
    }

    @Test
    public void clipPathToVisualEdges_withBendpoint_shouldUseFirstBendpointAsReference() {
        // Connection with a bendpoint — OrthogonalAnchor uses first bendpoint as reference.
        // Source at (0,200,500,300), center=(250,350). Bendpoint at (600,300).
        // Target at (900,0,100,100), center=(950,50).
        // OrthogonalAnchor for source: ref=(600,300). ref.x=600 is outside src (0-500),
        // ref.y=300 is inside src (200-500) → exits from RIGHT edge (x=500) at ref.y=300.
        List<double[]> path = List.of(
                new double[]{250, 350},   // center of src
                new double[]{600, 300},   // bendpoint
                new double[]{950, 50});   // center of tgt
        AssessmentNode src = node("src", 0, 200, 500, 300);
        AssessmentNode tgt = node("tgt", 900, 0, 100, 100);

        List<double[]> clipped = assessor.clipPathToVisualEdges(path, src, tgt);

        // Source exits toward first bendpoint (600,300) — orthogonal exit from right edge at y=300
        assertEquals("Source should exit from right edge", 500.0, clipped.get(0)[0], 0.1);
        assertEquals("Source exit y = ref.y", 300.0, clipped.get(0)[1], 0.1);
        // Target exits toward last bendpoint (600,300) — ref.x=600 inside (900-1000)? No, 600<900.
        // ref.y=300 is outside (0-100). Both outside → diagonal → ChopboxAnchor fallback.
        // Ray from (950,50) toward (600,300): exits from left edge (x=900) or bottom edge (y=100).
        // t_left = (900-950)/(600-950) = -50/-350 = 0.143, y = 50 + 0.143*250 = 85.7 → in [0,100] ✓
        // t_bottom = (100-50)/(300-50) = 50/250 = 0.2, x = 950 + 0.2*(-350) = 880 → outside [900,1000] ✗
        assertEquals("Target should exit from left edge", 900.0, clipped.get(2)[0], 0.1);
        assertEquals("Target exit y", 85.7, clipped.get(2)[1], 1.0);
    }

    // ---- Named constants tests (Finding #11) ----

    @Test
    public void overallRating_usesNamedConstants() {
        // Verify the rating thresholds are accessible and reasonable
        assertTrue(LayoutQualityAssessor.EXCELLENT_MAX_CROSSINGS
                < LayoutQualityAssessor.GOOD_MAX_CROSSINGS);
        assertTrue(LayoutQualityAssessor.GOOD_MAX_CROSSINGS
                < LayoutQualityAssessor.FAIR_MAX_CROSSINGS);
        assertTrue(LayoutQualityAssessor.EXCELLENT_MIN_SPACING
                > LayoutQualityAssessor.GOOD_MIN_SPACING);
        // Story 10-19a: GOOD_MAX_CROSSINGS raised to 20
        assertEquals(20, LayoutQualityAssessor.GOOD_MAX_CROSSINGS);
        // Story 10-19a: FAIR_MAX_PASS_THROUGHS = 3
        assertEquals(3, LayoutQualityAssessor.FAIR_MAX_PASS_THROUGHS);
    }

    // ---- Story 10-19a: Pass-through threshold tests ----

    @Test
    public void overallRating_withPassThroughs_shouldBlockExcellentAndGood() {
        // These params would be "excellent" with 0 pass-throughs — verify pass-throughs demote to "fair"
        String rating = assessor.computeOverallRating(0, 0, 50.0, 80, 0, 1, 0);
        assertEquals("1 pass-through with otherwise-excellent metrics should be fair", "fair", rating);
    }

    @Test
    public void overallRating_withFewPassThroughs_shouldAllowFair() {
        // 1-3 pass-throughs still allow fair rating (if other criteria met)
        String rating = assessor.computeOverallRating(0, 0, 50.0, 80, 0, 3, 0);
        assertEquals("Up to 3 pass-throughs should allow fair", "fair", rating);
    }

    @Test
    public void overallRating_withManyPassThroughs_shouldRatePoor() {
        // >3 pass-throughs → poor
        String rating = assessor.computeOverallRating(0, 0, 50.0, 80, 0, 4, 0);
        assertEquals("More than 3 pass-throughs should be poor", "poor", rating);
    }

    @Test
    public void overallRating_zeroPassThroughs_shouldAllowExcellent() {
        // 0 pass-throughs with excellent metrics → excellent
        String rating = assessor.computeOverallRating(0, 0, 50.0, 80, 0, 0, 0);
        assertEquals("Zero pass-throughs should allow excellent", "excellent", rating);
    }

    // ---- Story 9-0d: Transitive containment exclusion tests ----

    @Test
    public void assess_nestedGroups_grandparentGrandchild_shouldNotCountAsSiblingOverlap() {
        // Story 9-0d: TopGroup → SubGroup → Element
        // Grandparent-grandchild should NOT count as sibling overlap
        List<AssessmentNode> nodes = List.of(
                group("topGrp", 0, 0, 500, 400),
                childGroup("subGrp", 20, 20, 460, 360, "topGrp"),
                childNode("elem1", 50, 50, 100, 50, "subGrp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("No sibling overlaps expected", 0, result.overlapCount());
        // topGrp:subGrp, topGrp:elem1, subGrp:elem1 = 3 containment pairs
        assertEquals(3, result.containmentOverlapCount());
    }

    @Test
    public void assess_deeplyNested_allAncestorDescendantExcluded() {
        // Story 9-0d: 3+ levels — all ancestor-descendant pairs excluded
        // L1 → L2 → L3 → Element
        List<AssessmentNode> nodes = List.of(
                group("l1", 0, 0, 600, 500),
                childGroup("l2", 10, 10, 580, 480, "l1"),
                childGroup("l3", 20, 20, 560, 460, "l2"),
                childNode("leaf", 50, 50, 100, 50, "l3"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("No sibling overlaps in nested containment", 0, result.overlapCount());
        // l1:l2, l1:l3, l1:leaf, l2:l3, l2:leaf, l3:leaf = 6 containment pairs
        assertEquals(6, result.containmentOverlapCount());
    }

    @Test
    public void assess_siblingOverlapsInsideNestedGroup_shouldCount() {
        // Story 9-0d: Two siblings overlapping inside a nested group
        List<AssessmentNode> nodes = List.of(
                group("topGrp", 0, 0, 500, 400),
                childGroup("subGrp", 20, 20, 460, 360, "topGrp"),
                childNode("a", 50, 50, 100, 50, "subGrp"),
                childNode("b", 100, 50, 100, 50, "subGrp"));  // overlaps a

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("Sibling overlap should be counted", 1, result.overlapCount());
        assertTrue(result.overlaps().get(0).contains("'a'"));
        assertTrue(result.overlaps().get(0).contains("'b'"));
        // topGrp:subGrp, topGrp:a, topGrp:b, subGrp:a, subGrp:b = 5 containment overlaps
        assertEquals(5, result.containmentOverlapCount());
    }

    @Test
    public void assess_containmentOverlaps_countedSeparately() {
        // Story 9-0d: Verify containment overlaps are reported as separate count
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("c1", 50, 50, 100, 50, "grp"),
                childNode("c2", 200, 50, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(0, result.overlapCount());
        // grp:c1 and grp:c2 = 2 containment overlaps
        assertEquals(2, result.containmentOverlapCount());
    }

    @Test
    public void assess_manyContainmentOverlaps_zeroSiblingOverlaps_shouldRateExcellent() {
        // Story 9-0d: Rating should use sibling overlaps only
        // Well-spaced children inside a group — excellent layout
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 500, 300),
                childNode("c1", 20, 50, 100, 50, "grp"),
                childNode("c2", 20, 150, 100, 50, "grp"),
                childNode("c3", 20, 250, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(0, result.overlapCount());
        assertTrue("Containment overlaps should exist", result.containmentOverlapCount() > 0);
        assertEquals("excellent", result.overallRating());
    }

    @Test
    public void assess_containmentOnlyOverlaps_shouldNotSuggestSpacious() {
        // Story 9-0d: Suggestions should reference sibling overlaps only
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 500, 300),
                childNode("c1", 50, 50, 100, 50, "grp"),
                childNode("c2", 50, 150, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // No suggestion should mention "overlapping" or "spacious"
        assertFalse("Containment-only should not trigger spacious suggestion",
                result.suggestions().stream()
                        .anyMatch(s -> s.contains("overlapping") && s.contains("spacious")));
    }

    // ---- Label overlap detection tests (Story 10-8) ----

    @Test
    public void countLabelOverlaps_noLabels_shouldReturnZero() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 300, 0, 100, 50));
        // Connections with empty label text
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{350, 25}), "", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals(0, result.count());
        assertTrue(result.descriptions().isEmpty());
    }

    @Test
    public void countLabelOverlaps_labelOverlapsNode_shouldCount() {
        // Node at (200, 0, 100, 50) — label at midpoint of connection passes through it
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("obstacle", 200, 0, 100, 50),
                node("b", 400, 0, 100, 50));
        // Connection from a to b with label "Accesses" at midpoint
        // Path midpoint is at x=250, which overlaps obstacle (200-300)
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{450, 25}),
                        "Accesses", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertTrue("Label should overlap at least the obstacle node", result.count() > 0);
        assertFalse(result.descriptions().isEmpty());
    }

    @Test
    public void countLabelOverlaps_labelOverlapsOtherLabel_shouldCount() {
        // Two parallel connections with labels at the same midpoint
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 500, 0, 100, 50));
        // Both connections have same path and midpoint labels
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{550, 25}),
                        "Reads", 1),
                new AssessmentConnection("c2", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{550, 25}),
                        "Writes", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        // Labels overlap each other (same midpoint, similar sizes)
        assertTrue("Two labels at same position should overlap", result.count() > 0);
    }

    @Test
    public void assess_withLabelOverlaps_shouldIncludeInResult() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("obstacle", 200, 0, 100, 50),
                node("b", 400, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{450, 25}),
                        "Accesses", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);
        // labelOverlapCount should be reflected in result
        assertTrue("Label overlap count should be >= 0", result.labelOverlapCount() >= 0);
        assertNotNull(result.labelOverlaps());
    }

    @Test
    public void countLabelOverlaps_shouldExcludeSourceAndTargetElements() {
        // Label at midpoint on a short connection — likely overlaps source and target
        // but those should be excluded from the count
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        // Short path — midpoint label near both elements
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}),
                        "Uses", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        // Label overlaps source "a" and/or target "b" should NOT be counted
        assertEquals("Source/target overlaps should be excluded", 0, result.count());
    }

    @Test
    public void assess_withLabelOverlaps_shouldAddSuggestion() {
        // Node right in the middle of the path where label will be
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("mid", 200, 0, 100, 50),
                node("b", 400, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{450, 25}),
                        "Accesses", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);
        assertTrue("Label should overlap mid element", result.labelOverlapCount() > 0);
        assertTrue("Should have label overlap suggestion",
                result.suggestions().stream()
                        .anyMatch(s -> s.contains("labels overlap")));
    }

    @Test
    public void countLabelOverlaps_shouldExcludeAncestorGroups() {
        // Connection from a (inside group g) to b — label sits inside group g's bounds
        // Group g is an ancestor of source, so should be excluded
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("g", 0, 0, 500, 200, null, true, false),
                new AssessmentNode("a", 10, 10, 100, 50, "g", false, false),
                node("b", 400, 300, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{60, 35}, new double[]{450, 325}),
                        "Accesses", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        // Label overlaps group "g" but g is ancestor of source — should be excluded
        assertEquals("Ancestor group overlaps should be excluded", 0, result.count());
    }

    @Test
    public void countLabelOverlaps_shouldExcludeGroups() {
        // Connection from a to b with an unrelated group in the middle
        // Groups are transparent containers and should be skipped
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                new AssessmentNode("unrelatedGroup", 150, 0, 200, 100, null, true, false),
                node("b", 400, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{450, 25}),
                        "Accesses", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        // Label overlaps unrelated group but groups are excluded
        assertEquals("Group overlaps should be excluded", 0, result.count());
    }

    @Test
    public void countLabelOverlaps_shouldExcludeDescendantsOfSourceTarget() {
        // Connection from parent group g to b — child c is inside g
        // Label near source may overlap child c, but c is descendant of source
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("g", 0, 0, 200, 150, null, true, false),
                new AssessmentNode("child", 10, 10, 80, 40, "g", false, false),
                node("b", 400, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "g", "b",
                        List.of(new double[]{100, 75}, new double[]{450, 25}),
                        "Serves", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Descendant overlaps should be excluded", 0, result.count());
    }

    // ---- Story 11-24: Label proximity detection tests ----

    @Test
    public void countLabelOverlaps_labelWithinProximityOfUnrelatedElement_shouldCount() {
        // Label "Hi" (24×20px) centered at midpoint x=250 → spans x=[238,262], y=[15,35]
        // Obstacle at x=265: gap of 3px from label right edge — within 5px threshold
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("obstacle", 265, 10, 100, 50),
                node("b", 450, 0, 50, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{25, 25}, new double[]{475, 25}),
                        "Hi", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertTrue("Label within proximity threshold of element should be counted",
                result.count() > 0);
        assertTrue("Description should mention 'close to'",
                result.descriptions().stream().anyMatch(s -> s.contains("close to")));
    }

    @Test
    public void countLabelOverlaps_labelFarFromAllElements_shouldNotFlag() {
        // Label at midpoint of long connection — far from any obstacle
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 800, 0, 50, 50));
        // No obstacle node anywhere near the midpoint (x=412)
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{25, 25}, new double[]{825, 25}),
                        "FarAway", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Label far from all elements should not be flagged", 0, result.count());
    }

    @Test
    public void countLabelOverlaps_labelNearOwnSourceTarget_shouldNotFlag() {
        // Label near source/target elements — expected positioning, should be excluded
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 150, 0, 100, 50));
        // Short connection — label is near both source and target
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{200, 25}),
                        "Uses", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Label near own source/target should not be flagged", 0, result.count());
    }

    @Test
    public void countLabelOverlaps_labelNearMissOtherLabel_shouldCount() {
        // Two labels positioned close but not overlapping (gap of 3px < threshold of 5px)
        // Connection 1: path y=25, label "Alpha" height=20, y=[15, 35]
        // Connection 2: path y=48, label "Beta" height=20, y=[38, 58]
        // Gap between labels: y-axis gap = 38 - 35 = 3px (within threshold of 5px)
        // After inset of 10px, label height collapses to 0 — insetRectOverlap returns false
        // But isWithinProximity expands target by 5px, detecting the near-miss
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 450, 0, 50, 70));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{25, 25}, new double[]{475, 25}),
                        "Alpha", 1),
                new AssessmentConnection("c2", "a", "b",
                        List.of(new double[]{25, 48}, new double[]{475, 48}),
                        "Beta", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertTrue("Near-miss labels should be detected by proximity check",
                result.count() > 0);
    }

    // ---- Story 11-12: Group-aware suggestions tests ----

    @Test
    public void suggestions_groupedView_shouldRecommendLayoutWithinGroup() {
        // AC #3: grouped view with crossings should suggest layout-within-group
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("a", 20, 20, 100, 50, "grp"),
                childNode("b", 200, 200, 100, 50, "grp"),
                node("ext", 500, 100, 100, 50));
        // Create crossing connections
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "ext",
                        List.of(new double[]{70, 45}, new double[]{550, 125}), "", 1),
                new AssessmentConnection("c2", "b", "ext",
                        List.of(new double[]{250, 225}, new double[]{550, 125}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);
        boolean hasLayoutWithinGroup = result.suggestions().stream()
                .anyMatch(s -> s.contains("layout-within-group"));
        boolean hasComputeLayout = result.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertTrue("Grouped view should suggest layout-within-group", hasLayoutWithinGroup);
        assertFalse("Grouped view should NOT suggest compute-layout", hasComputeLayout);
    }

    @Test
    public void suggestions_flatView_shouldNotRecommendComputeLayout() {
        // Story 11-22 AC4: flat view should NOT suggest compute-layout
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),
                node("c", 0, 200, 100, 50),
                node("d", 200, 200, 100, 50));
        // Create crossing connections: a→d and b→c cross each other
        // Need >10 crossings to exceed CROSSING_SUGGESTION_THRESHOLD
        List<AssessmentConnection> connections = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            connections.add(new AssessmentConnection("ad" + i, "a", "d",
                    List.of(new double[]{50, 25}, new double[]{250, 225}), "", 1));
            connections.add(new AssessmentConnection("bc" + i, "b", "c",
                    List.of(new double[]{250, 25}, new double[]{50, 225}), "", 1));
        }

        LayoutAssessmentResult result = assessor.assess(nodes, connections);
        assertTrue("Should have >10 crossings", result.edgeCrossingCount() > 10);
        boolean hasComputeLayout = result.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertFalse("Flat view should NOT suggest compute-layout (Story 11-22)", hasComputeLayout);
        boolean hasAutoRoute = result.suggestions().stream()
                .anyMatch(s -> s.contains("auto-route-connections"));
        assertTrue("Flat view should suggest auto-route-connections", hasAutoRoute);
    }

    @Test
    public void suggestions_groupedViewWithOverlaps_shouldNotSuggestComputeLayout() {
        // AC #3: grouped view with overlaps — suggest layout-within-group, NOT compute-layout
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("a", 20, 20, 150, 50, "grp"),
                childNode("b", 100, 20, 150, 50, "grp")); // overlaps a

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());
        boolean hasLayoutWithinGroup = result.suggestions().stream()
                .anyMatch(s -> s.contains("layout-within-group"));
        boolean hasComputeLayout = result.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertTrue("Grouped view with overlaps should suggest layout-within-group",
                hasLayoutWithinGroup);
        assertFalse("Grouped view should NOT suggest compute-layout", hasComputeLayout);
    }

    // ---- Story 11-12: Density-aware rating calibration tests ----

    @Test
    public void rating_cleanViewWithOnePassThrough_shouldBeAtLeastFair() {
        // Structural floor tolerates 1 pass-through with high alignment
        String rating = assessor.computeOverallRating(
                0, 100, 50.0, 100, 0, 1, 30);
        assertEquals("1 pass-through with alignment 100 should hit structural floor",
                "fair", rating);
    }

    @Test
    public void rating_cleanViewWithManyCrossings_shouldBeAtLeastFair() {
        // AC #1: 0 overlaps, 0 pass-throughs, alignment 90+ → at least "fair"
        // regardless of absolute crossing count
        String rating = assessor.computeOverallRating(
                0, 100, 50.0, 95, 0, 0, 30);
        assertNotEquals("Clean view with high crossings should not be poor",
                "poor", rating);
        // Should be at least "fair" due to floor rule
        assertTrue("Should be fair or better",
                "excellent".equals(rating) || "good".equals(rating)
                        || "fair".equals(rating));
    }

    @Test
    public void rating_densityRatio_shouldScaleWithConnectionCount() {
        // AC #2: 60 crossings / 30 connections = 2.0 ratio (moderate) → fair
        String ratingDense = assessor.computeOverallRating(
                0, 60, 50.0, 50, 0, 0, 30);
        assertEquals("2.0 crossings/connection with clean metrics should be fair",
                "fair", ratingDense);

        // 150 crossings / 30 connections = 5.0 ratio (high) → poor
        String ratingVeryDense = assessor.computeOverallRating(
                0, 150, 50.0, 50, 0, 0, 30);
        assertEquals("5.0 crossings/connection should be poor",
                "poor", ratingVeryDense);
    }

    @Test
    public void rating_zeroConnectionsWithManyCrossings_shouldUseLegacyThreshold() {
        // No connections → use absolute threshold (backward compatibility)
        String rating = assessor.computeOverallRating(
                0, 50, 50.0, 50, 0, 0, 0);
        assertEquals("50 crossings with 0 connections should be poor (legacy threshold)",
                "poor", rating);
    }

    // ---- Story 11-12: Deep nesting overlap false positive tests ----

    @Test
    public void assess_deeplyNestedElements_withGaps_shouldNotReportOverlap() {
        // AC #5: 3-level nesting (element → sub-group → group)
        // Elements have 15px gaps — should NOT be reported as overlapping
        // Absolute coords: group at (200,200), sub-group at (230,230),
        // elem1 at (250,250), elem2 at (365,250) — 15px gap between them
        List<AssessmentNode> nodes = List.of(
                group("layerGrp", 200, 200, 400, 300),
                childGroup("subGrp", 230, 230, 350, 250, "layerGrp"),
                childNode("elem1", 250, 250, 100, 50, "subGrp"),
                childNode("elem2", 365, 250, 100, 50, "subGrp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());
        assertEquals("Elements with 15px gap should not overlap", 0, result.overlapCount());
    }

    @Test
    public void assess_deeplyNestedElements_actualOverlap_shouldReport() {
        // Verify actual overlaps at 3-level nesting ARE still detected
        List<AssessmentNode> nodes = List.of(
                group("layerGrp", 200, 200, 400, 300),
                childGroup("subGrp", 230, 230, 350, 250, "layerGrp"),
                childNode("elem1", 250, 250, 100, 50, "subGrp"),
                childNode("elem2", 320, 250, 100, 50, "subGrp")); // overlaps elem1

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());
        assertEquals("Genuinely overlapping siblings should be detected",
                1, result.overlapCount());
    }

    // ---- Story 11-12: Result includes connectionCount and crossingsPerConnection ----

    @Test
    public void assess_shouldIncludeConnectionCountAndCrossingRatio() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);
        assertEquals(1, result.connectionCount());
        assertEquals(0.0, result.crossingsPerConnection(), 0.001);
    }

    // ---- Code review: M2 label overlap suggestion group-aware test ----

    @Test
    public void suggestions_groupedView_labelOverlap_shouldNotSuggestFlatLayout() {
        // M2 fix: grouped view label overlap should suggest layout-within-group,
        // NOT "flat layout" which would destroy group structure
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 500, 300),
                childNode("a", 20, 20, 100, 50, "grp"),
                childNode("b", 20, 120, 100, 50, "grp"),
                childNode("c", 300, 70, 100, 50, "grp"));
        // Two connections with labels that overlap element c
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "c",
                        List.of(new double[]{70, 45}, new double[]{350, 95}),
                        "uses", 1),
                new AssessmentConnection("c2", "b", "c",
                        List.of(new double[]{70, 145}, new double[]{350, 95}),
                        "uses", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);
        // Find label overlap suggestions if any
        for (String suggestion : result.suggestions()) {
            if (suggestion.contains("labels overlap")) {
                assertTrue("Grouped view label suggestion should mention layout-within-group",
                        suggestion.contains("layout-within-group"));
                assertFalse("Grouped view label suggestion should NOT say 'flat layout'",
                        suggestion.contains("flat layout"));
            }
        }
    }

    // ---- Code review: L3 boundary test at CROSSING_RATIO_MODERATE ----

    @Test
    public void rating_densityRatio_atExactModerateThreshold_shouldBeFair() {
        // Boundary: exactly 4.0 crossings/connection (CROSSING_RATIO_MODERATE)
        // Condition is <= 4.0, so should be "fair"
        String rating = assessor.computeOverallRating(
                0, 120, 50.0, 50, 0, 0, 30); // 120/30 = 4.0
        assertEquals("Exactly 4.0 crossings/connection should be fair",
                "fair", rating);
    }

    // ---- Story 11-15: Note-aware layout tests ----

    @Test
    public void assess_notesExcludedFromSiblingOverlapCount() {
        // Note overlaps element — should NOT count as sibling overlap
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),
                note("n1", 0, 0, 150, 30)); // overlaps "a" but is a note

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(0, result.overlapCount());
    }

    @Test
    public void assess_notesExcludedFromSpacingCalculation() {
        // Note placed very close to element — should NOT affect spacing
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),
                note("n1", 105, 0, 80, 30)); // very close to "a", between a and b

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // Spacing should be between "a" and "b" only (100px gap)
        assertEquals(100.0, result.averageSpacing(), 1.0);
    }

    @Test
    public void assess_notesExcludedFromAlignmentScore() {
        // Two aligned elements + misaligned note — note should not affect score
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),  // aligned with "a" on y
                note("n1", 50, 77, 100, 30)); // misaligned — should be ignored

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // Score should reflect 100% alignment of the two elements
        assertEquals(100, result.alignmentScore());
    }

    @Test
    public void assess_noteElementOverlapsReportedInSeparateField() {
        // Note overlaps an element — should appear in noteOverlaps, not overlaps
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),
                note("n1", 10, 10, 80, 30)); // overlaps "a"

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(0, result.overlapCount()); // no sibling overlap
        assertEquals(1, result.noteOverlapCount());
        assertEquals(1, result.noteOverlapDescriptions().size());
        assertTrue(result.noteOverlapDescriptions().get(0).contains("n1"));
        assertTrue(result.noteOverlapDescriptions().get(0).contains("element"));
        assertTrue(result.noteOverlapDescriptions().get(0).contains("a"));
    }

    @Test
    public void assess_noteInsideGroup_shouldNotReportParentGroupOverlap() {
        // Note inside a group (section label) — should NOT report overlap with its parent group
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("elem", 20, 50, 100, 50, "grp"),
                new AssessmentNode("n1", 10, 10, 150, 30, "grp", false, true));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(0, result.noteOverlapCount());
        assertTrue(result.noteOverlapDescriptions().isEmpty());
    }

    @Test
    public void assess_noteOverlapsGroup_shouldCount() {
        // Story 11-28: Top-level note overlapping a group — NOW detected
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("elem", 50, 50, 100, 50, "grp"),
                note("n1", 0, 0, 150, 30)); // overlaps group bounds

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(1, result.noteOverlapCount());
        assertEquals(1, result.noteOverlapDescriptions().size());
        assertTrue(result.noteOverlapDescriptions().get(0).contains("group"));
        assertTrue(result.noteOverlapDescriptions().get(0).contains("grp"));
    }

    @Test
    public void assess_viewWithOnlyNotesReturnsZeroOverlaps() {
        // View with only notes — all metrics should be zero/empty
        List<AssessmentNode> nodes = List.of(
                note("n1", 0, 0, 100, 30),
                note("n2", 50, 10, 100, 30)); // overlaps n1

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(0, result.overlapCount());
        assertEquals(0, result.edgeCrossingCount());
        assertEquals(0, result.alignmentScore());
        // Note-to-note overlaps are not counted (only note-element overlaps)
        assertEquals(0, result.noteOverlapCount());
    }

    // ---- Story 11-28: Note-to-group overlap detection ----

    @Test
    public void assess_noteOverlapsGroupSmallOverlapAtTop_shouldCount() {
        // Note overlaps only the top edge of a group (small overlap) — still detected via full bounding box
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                note("n1", 10, 5, 100, 20)); // overlaps group at top edge (y=5, h=20 → bottom=25)

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(1, result.noteOverlapCount());
        assertTrue(result.noteOverlapDescriptions().get(0).contains("group"));
    }

    @Test
    public void assess_noteOverlapsBothGroupAndElement_shouldCountBoth() {
        // Note overlaps a group AND a child element — count = 2
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("elem", 10, 10, 100, 50, "grp"),
                note("n1", 5, 5, 120, 60)); // overlaps both grp and elem

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(2, result.noteOverlapCount());
    }

    @Test
    public void assess_noteInClearSpace_shouldNotCount() {
        // Note placed far from any element or group — no overlap
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 200, 200),
                node("elem", 10, 10, 50, 50),
                note("n1", 500, 500, 100, 30)); // far away

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(0, result.noteOverlapCount());
    }

    @Test
    public void assess_noteOverlapsRegularElement_regressionCheck() {
        // Regression: note-to-element overlap must still work after group skip removal
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                note("n1", 10, 10, 80, 30)); // overlaps "a"

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals(1, result.noteOverlapCount());
        assertTrue(result.noteOverlapDescriptions().get(0).contains("element"));
    }

    @Test
    public void assess_childNoteOverlapsDifferentGroup_shouldCount() {
        // M1: Note is child of grpA but overlaps grpB — should be detected (only parent group is excluded)
        List<AssessmentNode> nodes = List.of(
                group("grpA", 0, 0, 200, 200),
                group("grpB", 180, 0, 200, 200),
                new AssessmentNode("n1", 170, 10, 50, 30, "grpA", false, true)); // child of grpA, overlaps grpB

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // Should detect overlap with grpB (not excluded) but NOT with grpA (parent excluded)
        // Also overlaps grpA since note at x=170 is within grpA's 0-200 range — but parent exclusion skips it
        assertEquals(1, result.noteOverlapCount());
        assertTrue(result.noteOverlapDescriptions().get(0).contains("grpB"));
    }

    @Test
    public void assess_childNoteOverlapsSiblingElement_shouldCount() {
        // M2: Note is child of grp and overlaps a sibling element in same group — should be detected
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("elem", 20, 50, 100, 50, "grp"),
                new AssessmentNode("n1", 10, 40, 120, 30, "grp", false, true)); // child note overlaps sibling elem

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // Parent group overlap excluded, but sibling element overlap detected
        assertEquals(1, result.noteOverlapCount());
        assertTrue(result.noteOverlapDescriptions().get(0).contains("element"));
        assertTrue(result.noteOverlapDescriptions().get(0).contains("elem"));
    }

    @Test
    public void assess_noteOverlapsGroup_ratingUnchanged() {
        // Story 11-28 AC6: note overlaps should NOT affect the quality rating
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("a", 20, 50, 100, 50, "grp"),
                childNode("b", 200, 50, 100, 50, "grp"),
                note("n1", 0, 0, 150, 30)); // overlaps group

        LayoutAssessmentResult withNote = assessor.assess(nodes, List.of());

        List<AssessmentNode> nodesWithoutNote = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("a", 20, 50, 100, 50, "grp"),
                childNode("b", 200, 50, 100, 50, "grp"));

        LayoutAssessmentResult withoutNote = assessor.assess(nodesWithoutNote, List.of());

        // Rating should be identical regardless of note overlaps
        assertEquals(withoutNote.overallRating(), withNote.overallRating());
        assertTrue(withNote.noteOverlapCount() > 0); // overlap IS detected
    }

    // ---- Story 11-17: hasGroups in LayoutAssessmentResult ----

    @Test
    public void assess_withGroups_shouldSetHasGroupsTrue() {
        List<AssessmentNode> nodes = List.of(
                group("g1", 0, 0, 400, 300),
                childNode("a", 20, 20, 100, 50, "g1"),
                childNode("b", 200, 20, 100, 50, "g1"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertTrue("hasGroups should be true when groups present", result.hasGroups());
    }

    @Test
    public void assess_withoutGroups_shouldSetHasGroupsFalse() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertFalse("hasGroups should be false when no groups present", result.hasGroups());
    }

    @Test
    public void assess_withNotesOnly_shouldSetHasGroupsFalse() {
        List<AssessmentNode> nodes = List.of(
                note("n1", 0, 0, 100, 30),
                note("n2", 200, 0, 100, 30));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertFalse("hasGroups should be false for notes-only views", result.hasGroups());
    }

    // ---- Story 11-19: Rating breakdown and grouped-view leniency tests ----

    @Test
    public void ratingBreakdown_shouldBeIncludedInAssessResult() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertNotNull("ratingBreakdown should not be null", result.ratingBreakdown());
        assertTrue("breakdown should contain overlaps", result.ratingBreakdown().containsKey("overlaps"));
        assertTrue("breakdown should contain edgeCrossings", result.ratingBreakdown().containsKey("edgeCrossings"));
        assertTrue("breakdown should contain spacing", result.ratingBreakdown().containsKey("spacing"));
        assertTrue("breakdown should contain alignment", result.ratingBreakdown().containsKey("alignment"));
        assertTrue("breakdown should contain labelOverlaps", result.ratingBreakdown().containsKey("labelOverlaps"));
        assertTrue("breakdown should contain passThroughs", result.ratingBreakdown().containsKey("passThroughs"));
        assertTrue("breakdown should contain overall", result.ratingBreakdown().containsKey("overall"));
    }

    @Test
    public void ratingBreakdown_excellentView_shouldHaveAllPass() {
        // All metrics excellent: 0 overlaps, 0 crossings, good spacing/alignment, 0 labels, 0 pass-throughs
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, false);

        assertEquals("excellent", result.rating());
        assertEquals("pass", result.breakdown().get("overlaps"));
        assertEquals("pass", result.breakdown().get("edgeCrossings"));
        assertEquals("pass", result.breakdown().get("spacing"));
        assertEquals("pass", result.breakdown().get("alignment"));
        assertEquals("pass", result.breakdown().get("labelOverlaps"));
        assertEquals("pass", result.breakdown().get("passThroughs"));
        assertEquals("excellent", result.breakdown().get("overall"));
    }

    @Test
    public void ratingBreakdown_crossingsOnly_shouldShowCrossingsFair() {
        // Only crossings are bad (25 crossings, 10 connections = 2.5 ratio)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 0, 10, false);

        assertEquals("fair", result.rating());
        assertEquals("pass", result.breakdown().get("overlaps"));
        assertEquals("fair", result.breakdown().get("edgeCrossings"));
        assertEquals("pass", result.breakdown().get("spacing"));
        assertEquals("pass", result.breakdown().get("alignment"));
        assertEquals("fair", result.breakdown().get("overall"));
    }

    @Test
    public void ratingBreakdown_groupedView_crossingsOnly_shouldShowGood() {
        // AC2: Grouped view where crossings are the ONLY issue → "good" not "fair"
        // 25 crossings, 10 connections — would be "fair" on flat view
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 0, 10, true);

        assertEquals("good", result.rating());
        assertEquals("good", result.breakdown().get("edgeCrossings"));
        assertEquals("pass", result.breakdown().get("overlaps"));
        assertEquals("pass", result.breakdown().get("passThroughs"));
        assertEquals("good", result.breakdown().get("overall"));
    }

    @Test
    public void ratingBreakdown_flatView_crossingsOnly_shouldStayFair() {
        // AC2: Same crossings on flat view should stay "fair"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 0, 10, false);

        assertEquals("fair", result.rating());
        assertEquals("fair", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void ratingBreakdown_groupedView_withOverlaps_shouldNotGetBonus() {
        // Grouped view but has overlaps too — crossing leniency should NOT apply
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                2, 25, 50.0, 80, 0, 0, 10, true);

        // Overlaps are "fair", crossings are "fair" (no leniency because overlaps exist)
        assertEquals("fair", result.rating());
        assertEquals("fair", result.breakdown().get("edgeCrossings"));
        assertEquals("fair", result.breakdown().get("overlaps"));
    }

    @Test
    public void ratingBreakdown_groupedView_withPassThroughs_shouldNotGetBonus() {
        // Grouped view but has pass-throughs — crossing leniency should NOT apply
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 2, 10, true);

        assertEquals("fair", result.rating());
        assertEquals("fair", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void ratingBreakdown_groupedView_manyCrossingsNoOtherIssues_shouldBeGood() {
        // AC2: 100 crossings, 28 connections (3.57 per conn) — grouped view, no other issues
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 100, 50.0, 80, 0, 0, 28, true);

        assertEquals("good", result.rating());
        assertEquals("good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void ratingBreakdown_overlapsPoor_shouldShowPoor() {
        // More than FAIR_MAX_OVERLAPS (3) → poor
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                5, 0, 50.0, 80, 0, 0, 0, false);

        assertEquals("poor", result.rating());
        assertEquals("poor", result.breakdown().get("overlaps"));
    }

    @Test
    public void assess_groupedView_shouldIncludeRatingBreakdownAndHasGroups() {
        // Integration test: verify ratingBreakdown flows through the full assess pipeline
        List<AssessmentNode> nodes = List.of(
                group("grp1", 0, 0, 400, 300),
                childNode("a", 30, 30, 120, 60, "grp1"),
                childNode("b", 30, 200, 120, 60, "grp1"),
                group("grp2", 500, 0, 400, 300),
                childNode("c", 530, 30, 120, 60, "grp2"),
                childNode("d", 530, 200, 120, 60, "grp2"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertTrue("Should detect groups", result.hasGroups());
        assertNotNull("ratingBreakdown should be present", result.ratingBreakdown());
        assertEquals(7, result.ratingBreakdown().size());
        assertEquals(result.overallRating(), result.ratingBreakdown().get("overall"));
    }

    @Test
    public void ratingBreakdown_noRegression_overlapsStillProducePoor() {
        // AC4: Overlaps should still produce poor
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                10, 0, 50.0, 80, 0, 0, 0, false);

        assertEquals("poor", result.rating());
        assertEquals("poor", result.breakdown().get("overlaps"));
    }

    @Test
    public void ratingBreakdown_noRegression_passThroughsStillDowngrade() {
        // AC4: Pass-throughs should still downgrade appropriately
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 4, 0, false);

        assertEquals("poor", result.rating());
        assertEquals("poor", result.breakdown().get("passThroughs"));
    }

    // ---- Story 11-22: Rating recalibration and suggestion fixes ----

    @Test
    public void rating_flatView_lowCrossingDensity_shouldRateGood() {
        // AC1: flat view with ~0.72 crossings/conn should rate "good"
        // 20 crossings / 28 connections = 0.71 ratio — below CROSSING_RATIO_GOOD (1.5)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 20, 50.0, 80, 0, 0, 28, false);

        assertEquals("0.71 crossings/conn should rate good",
                "good", result.breakdown().get("edgeCrossings"));
        assertEquals("good", result.rating());
    }

    @Test
    public void rating_flatView_highCrossingDensity_shouldRateFairOrBelow() {
        // AC1: flat view with 3.5+ crossings/conn should rate "fair" or below
        // 105 crossings / 30 connections = 3.5 ratio
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 105, 50.0, 80, 0, 0, 30, false);

        String crossingRating = result.breakdown().get("edgeCrossings");
        assertTrue("3.5 crossings/conn should be fair or poor",
                "fair".equals(crossingRating) || "poor".equals(crossingRating));
    }

    @Test
    public void rating_flatView_crossingRatioAtBoundary_shouldRateGood() {
        // M2 review fix: boundary test at exactly CROSSING_RATIO_GOOD (1.5)
        // 30 crossings / 20 connections = 1.5 ratio — exactly at boundary (<=)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 30, 50.0, 80, 0, 0, 20, false);

        assertEquals("Exactly 1.5 crossings/conn should rate good (boundary <=)",
                "good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void rating_flatView_crossingRatioJustAboveBoundary_shouldNotRateGood() {
        // M2 review fix: just above CROSSING_RATIO_GOOD — should be "fair" not "good"
        // 31 crossings / 20 connections = 1.55 ratio — just above 1.5
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 31, 50.0, 80, 0, 0, 20, false);

        String crossingRating = result.breakdown().get("edgeCrossings");
        assertNotEquals("1.55 crossings/conn should NOT rate good",
                "good", crossingRating);
    }

    @Test
    public void rating_groupedView_oneTierBoost_notUnconditionalGood() {
        // AC2: grouped view with very high crossing density — one-tier boost, not floor at "good"
        // 150 crossings / 28 connections = 5.36 ratio → base "poor", boost → "fair" (not "good")
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 150, 50.0, 80, 0, 0, 28, true);

        assertEquals("Very high density grouped view should get one-tier boost to fair, not good",
                "fair", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void rating_groupedView_moderateCrossings_shouldStillBoost() {
        // AC2: grouped view with moderate crossings benefits from one-tier boost
        // 25 crossings / 10 connections = 2.5 ratio → base "fair", boost → "good"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 0, 10, true);

        assertEquals("Moderate crossings grouped view should boost to good",
                "good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void suggestions_viewWithContainment_noGroups_shouldNotSuggestComputeLayout() {
        // AC3: view with nested elements (containment) but no groups
        // Should suggest auto-route / auto-layout-and-route, NOT compute-layout
        // Use overlapping siblings to trigger overlap suggestion
        List<AssessmentNode> nodes = List.of(
                node("parent", 0, 0, 300, 200),
                childNode("child1", 20, 20, 100, 50, "parent"),
                childNode("child2", 80, 20, 100, 50, "parent"),  // overlaps child1
                node("ext", 400, 50, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        // Verify containment is detected
        assertTrue("Should detect containment overlaps", result.containmentOverlapCount() > 0);
        boolean hasComputeLayout = result.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertFalse("Containment view should NOT suggest compute-layout", hasComputeLayout);
        boolean hasAutoLayoutAndRoute = result.suggestions().stream()
                .anyMatch(s -> s.contains("auto-layout-and-route"));
        assertTrue("Containment view should suggest auto-layout-and-route", hasAutoLayoutAndRoute);
    }

    @Test
    public void suggestions_allScenarios_shouldNeverReferenceComputeLayout() {
        // AC4: no suggestion text across any scenario references compute-layout
        // Test flat view with overlaps
        List<AssessmentNode> flatOverlap = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 50, 25, 100, 50));
        LayoutAssessmentResult flatResult = assessor.assess(flatOverlap, List.of());
        boolean flatHasComputeLayout = flatResult.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertFalse("Flat overlaps should not suggest compute-layout", flatHasComputeLayout);

        // Test flat view with crossings
        List<AssessmentNode> flatCross = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),
                node("c", 0, 200, 100, 50),
                node("d", 200, 200, 100, 50));
        List<AssessmentConnection> connections = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            connections.add(new AssessmentConnection("ad" + i, "a", "d",
                    List.of(new double[]{50, 25}, new double[]{250, 225}), "", 1));
            connections.add(new AssessmentConnection("bc" + i, "b", "c",
                    List.of(new double[]{250, 25}, new double[]{50, 225}), "", 1));
        }
        LayoutAssessmentResult crossResult = assessor.assess(flatCross, connections);
        boolean crossHasComputeLayout = crossResult.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertFalse("Flat crossings should not suggest compute-layout", crossHasComputeLayout);

        // Test flat view with low alignment
        List<AssessmentNode> flatAlign = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 37, 63, 100, 50),
                node("c", 74, 126, 100, 50));
        LayoutAssessmentResult alignResult = assessor.assess(flatAlign, List.of());
        boolean alignHasComputeLayout = alignResult.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertFalse("Flat alignment should not suggest compute-layout", alignHasComputeLayout);
    }

    @Test
    public void suggestions_groupedView_shouldPreserveGroupedWorkflow() {
        // AC5: grouped view with overlapping children → suggests layout-within-group, not compute-layout
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("a", 20, 20, 150, 50, "grp"),
                childNode("b", 100, 20, 150, 50, "grp"));  // overlaps a

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        boolean hasLayoutWithinGroup = result.suggestions().stream()
                .anyMatch(s -> s.contains("layout-within-group"));
        boolean hasComputeLayout = result.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertTrue("Grouped view should suggest layout-within-group", hasLayoutWithinGroup);
        assertFalse("Grouped view should NOT suggest compute-layout", hasComputeLayout);
    }

    // ---- Cross-group boundary overlap filtering (Story 11-26) ----

    @Test
    public void assess_adjacentGroups_elementsNearBoundary_zeroSiblingOverlaps() {
        // Story 11-26 AC1: Adjacent groups with elements near shared boundary
        // Group A at x=0..200, Group B at x=200..400 (touching boundary)
        // Elements near the boundary have overlapping bounding boxes across groups
        List<AssessmentNode> nodes = List.of(
                group("grpA", 0, 0, 200, 200),
                group("grpB", 200, 0, 200, 200),
                childNode("a1", 140, 50, 80, 40, "grpA"),  // extends to x=220 (into grpB's area)
                childNode("b1", 190, 50, 80, 40, "grpB")); // starts at x=190 (overlaps a1's bbox)

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("Cross-group boundary elements should not count as sibling overlaps",
                0, result.overlapCount());
    }

    @Test
    public void assess_sameGroupElementsOverlapping_countedAsSiblingOverlap() {
        // Story 11-26 AC3: Two elements in the SAME group that genuinely overlap
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 200),
                childNode("a", 50, 50, 100, 50, "grp"),
                childNode("b", 100, 50, 100, 50, "grp"));  // overlaps a

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("Same-group sibling overlap should be counted", 1, result.overlapCount());
    }

    @Test
    public void assess_topLevelElementsOverlapping_countedAsSiblingOverlap() {
        // Story 11-26 AC4: Two top-level elements (parentId=null) overlapping
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 50, 25, 100, 50));  // overlaps a

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("Top-level elements (both null parent) should count as sibling overlap",
                1, result.overlapCount());
    }

    @Test
    public void assess_topLevelGroupsOverlapping_countedAsSiblingOverlap() {
        // Story 11-26 AC5: Two top-level groups that overlap each other
        List<AssessmentNode> nodes = List.of(
                group("grpA", 0, 0, 300, 200),
                group("grpB", 200, 0, 300, 200));  // overlaps grpA

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("Top-level group overlap should count as sibling overlap",
                1, result.overlapCount());
    }

    @Test
    public void assess_layeredView_multipleAdjacentGroups_zeroFalsePositiveOverlaps() {
        // Story 11-26 AC2: Layered view with 3 adjacent groups, many elements near boundaries
        // Simulates the View 2 false positive scenario from E2E tests
        List<AssessmentNode> nodes = List.of(
                // Three horizontally adjacent groups (layers)
                group("layer1", 0, 0, 300, 200),
                group("layer2", 300, 0, 300, 200),
                group("layer3", 600, 0, 300, 200),
                // Elements in layer1 near right boundary
                childNode("l1a", 220, 50, 100, 40, "layer1"),
                childNode("l1b", 220, 120, 100, 40, "layer1"),
                // Elements in layer2 near left and right boundaries
                childNode("l2a", 290, 50, 100, 40, "layer2"),
                childNode("l2b", 290, 120, 100, 40, "layer2"),
                childNode("l2c", 520, 50, 100, 40, "layer2"),
                // Elements in layer3 near left boundary
                childNode("l3a", 590, 50, 100, 40, "layer3"),
                childNode("l3b", 590, 120, 100, 40, "layer3"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("Layered view should have zero sibling overlaps (no false positives)",
                0, result.overlapCount());
        assertTrue("Containment overlaps should still exist",
                result.containmentOverlapCount() > 0);
    }

    @Test
    public void assess_mixedScenario_onlySameParentOverlapsCounted() {
        // Story 11-26: Mixed scenario — some same-parent overlaps + cross-parent proximity
        List<AssessmentNode> nodes = List.of(
                group("grpA", 0, 0, 300, 200),
                group("grpB", 300, 0, 300, 200),
                // Two elements in grpA that genuinely overlap each other
                childNode("a1", 50, 50, 120, 50, "grpA"),
                childNode("a2", 100, 50, 120, 50, "grpA"),
                // Element in grpA near boundary with grpB
                childNode("a3", 240, 50, 80, 50, "grpA"),
                // Element in grpB near boundary with grpA — overlaps a3's bbox
                childNode("b1", 290, 50, 80, 50, "grpB"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertEquals("Only same-parent overlap (a1+a2) should be counted, not cross-group (a3+b1)",
                1, result.overlapCount());
    }

    // ---- Helper methods ----

    /** Creates a top-level leaf element (non-group, no parent). */
    private static AssessmentNode node(String id, double x, double y,
                                        double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false);
    }

    /** Creates a group container (top-level, no parent). */
    private static AssessmentNode group(String id, double x, double y,
                                         double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, true, false);
    }

    /** Creates a child element inside a group. */
    private static AssessmentNode childNode(String id, double x, double y,
                                             double w, double h, String parentId) {
        return new AssessmentNode(id, x, y, w, h, parentId, false, false);
    }

    /** Creates a child group (nested group inside a parent group). */
    private static AssessmentNode childGroup(String id, double x, double y,
                                              double w, double h, String parentId) {
        return new AssessmentNode(id, x, y, w, h, parentId, true, false);
    }

    /** Creates a top-level note (Story 11-15). */
    private static AssessmentNode note(String id, double x, double y,
                                        double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, true);
    }

    private List<AssessmentNode> createFourNodeGrid() {
        return List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),
                node("c", 0, 100, 100, 50),
                node("d", 200, 100, 100, 50));
    }

    // ---- Rating comparison utility tests (Story 11-16) ----

    @Test
    public void ratingOrdinal_shouldReturnCorrectOrderForAllValues() {
        assertEquals(4, LayoutQualityAssessor.ratingOrdinal("excellent"));
        assertEquals(3, LayoutQualityAssessor.ratingOrdinal("good"));
        assertEquals(2, LayoutQualityAssessor.ratingOrdinal("fair"));
        assertEquals(1, LayoutQualityAssessor.ratingOrdinal("poor"));
        assertEquals(0, LayoutQualityAssessor.ratingOrdinal("not-applicable"));
    }

    @Test
    public void ratingOrdinal_shouldReturnZeroForUnknownValue() {
        assertEquals(0, LayoutQualityAssessor.ratingOrdinal("unknown"));
        assertEquals(0, LayoutQualityAssessor.ratingOrdinal(""));
    }

    @Test
    public void meetsTarget_shouldReturnTrue_whenAchievedEqualsTarget() {
        assertTrue(LayoutQualityAssessor.meetsTarget("good", "good"));
        assertTrue(LayoutQualityAssessor.meetsTarget("fair", "fair"));
        assertTrue(LayoutQualityAssessor.meetsTarget("excellent", "excellent"));
    }

    @Test
    public void meetsTarget_shouldReturnTrue_whenAchievedExceedsTarget() {
        assertTrue(LayoutQualityAssessor.meetsTarget("excellent", "good"));
        assertTrue(LayoutQualityAssessor.meetsTarget("excellent", "fair"));
        assertTrue(LayoutQualityAssessor.meetsTarget("good", "fair"));
    }

    @Test
    public void meetsTarget_shouldReturnFalse_whenAchievedBelowTarget() {
        assertFalse(LayoutQualityAssessor.meetsTarget("fair", "good"));
        assertFalse(LayoutQualityAssessor.meetsTarget("fair", "excellent"));
        assertFalse(LayoutQualityAssessor.meetsTarget("poor", "fair"));
        assertFalse(LayoutQualityAssessor.meetsTarget("poor", "good"));
        assertFalse(LayoutQualityAssessor.meetsTarget("not-applicable", "fair"));
    }

    @Test
    public void ratingOrdinal_shouldMaintainStrictOrdering() {
        assertTrue(LayoutQualityAssessor.ratingOrdinal("excellent")
                > LayoutQualityAssessor.ratingOrdinal("good"));
        assertTrue(LayoutQualityAssessor.ratingOrdinal("good")
                > LayoutQualityAssessor.ratingOrdinal("fair"));
        assertTrue(LayoutQualityAssessor.ratingOrdinal("fair")
                > LayoutQualityAssessor.ratingOrdinal("poor"));
        assertTrue(LayoutQualityAssessor.ratingOrdinal("poor")
                > LayoutQualityAssessor.ratingOrdinal("not-applicable"));
    }

    // ---- Coincident segment detection (Story 11-23) ----

    @Test
    public void assess_shouldReportCoincidentSegments_whenConnectionsOverlap() {
        // Two elements with space between, two connections sharing a horizontal segment
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 400, 0, 100, 50));

        // Two connections sharing the same horizontal path at y=25
        // Connection 0: (50,25) -> (100,25) -> (400,25) -> (450,25)
        // Connection 1: (50,25) -> (100,25) -> (400,25) -> (450,25)
        AssessmentConnection conn0 = new AssessmentConnection("c-0", "a", "b",
                List.of(new double[]{50, 25}, new double[]{100, 25},
                        new double[]{400, 25}, new double[]{450, 25}),
                null, 0);
        AssessmentConnection conn1 = new AssessmentConnection("c-1", "a", "b",
                List.of(new double[]{50, 25}, new double[]{100, 25},
                        new double[]{400, 25}, new double[]{450, 25}),
                null, 0);

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(conn0, conn1));

        assertTrue("Should detect coincident segments",
                result.coincidentSegmentCount() > 0);
    }

    @Test
    public void assess_shouldReportZeroCoincident_whenNoOverlappingPaths() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 400, 0, 100, 50));

        // Two connections with different paths
        AssessmentConnection conn0 = new AssessmentConnection("c-0", "a", "b",
                List.of(new double[]{50, 25}, new double[]{100, 25},
                        new double[]{400, 25}, new double[]{450, 25}),
                null, 0);
        AssessmentConnection conn1 = new AssessmentConnection("c-1", "a", "b",
                List.of(new double[]{50, 75}, new double[]{100, 200},
                        new double[]{400, 200}, new double[]{450, 75}),
                null, 0);

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(conn0, conn1));

        assertEquals("Should detect no coincident segments", 0,
                result.coincidentSegmentCount());
    }

    @Test
    public void assess_shouldGenerateSuggestion_whenCoincidentSegmentsDetected() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 400, 0, 100, 50));

        // Two connections with overlapping horizontal segment
        AssessmentConnection conn0 = new AssessmentConnection("c-0", "a", "b",
                List.of(new double[]{50, 25}, new double[]{100, 25},
                        new double[]{400, 25}, new double[]{450, 25}),
                null, 0);
        AssessmentConnection conn1 = new AssessmentConnection("c-1", "a", "b",
                List.of(new double[]{50, 25}, new double[]{100, 25},
                        new double[]{400, 25}, new double[]{450, 25}),
                null, 0);

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(conn0, conn1));

        boolean hasSuggestion = result.suggestions().stream()
                .anyMatch(s -> s.contains("overlapping connection segments"));
        assertTrue("Should generate coincident segment suggestion", hasSuggestion);
    }

    // ---- Content bounding box tests (Story 11-29) ----

    @Test
    public void assess_shouldReturnContentBounds_whenViewHasElements() {
        List<AssessmentNode> nodes = List.of(
                node("a", 100, 50, 120, 60),
                node("b", 300, 200, 80, 40));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertNotNull("contentBounds should be present", result.contentBounds());
        assertEquals(100.0, result.contentBounds().x(), 0.001);
        assertEquals(50.0, result.contentBounds().y(), 0.001);
        // width = (300+80) - 100 = 280, height = (200+40) - 50 = 190
        assertEquals(280.0, result.contentBounds().width(), 0.001);
        assertEquals(190.0, result.contentBounds().height(), 0.001);
    }

    @Test
    public void assess_shouldReturnNullContentBounds_whenViewIsEmpty() {
        List<AssessmentNode> nodes = List.of();

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertNull("contentBounds should be null for empty view", result.contentBounds());
    }

    @Test
    public void assess_shouldReturnContentBounds_whenViewHasSingleElement() {
        // AC2 null-for-1-element is the accessor's responsibility (early-return path).
        // The assessor correctly computes bounds for any non-empty list.
        List<AssessmentNode> nodes = List.of(
                node("only", 50, 100, 200, 80));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertNotNull("contentBounds should be present for single element", result.contentBounds());
        assertEquals(50.0, result.contentBounds().x(), 0.001);
        assertEquals(100.0, result.contentBounds().y(), 0.001);
        assertEquals(200.0, result.contentBounds().width(), 0.001);
        assertEquals(80.0, result.contentBounds().height(), 0.001);
    }

    @Test
    public void assess_shouldIncludeNotesInContentBounds() {
        // Note placed far above the element cluster
        List<AssessmentNode> nodes = List.of(
                node("a", 100, 200, 120, 60),
                node("b", 300, 200, 80, 40),
                note("title", 50, 10, 200, 30));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertNotNull("contentBounds should be present", result.contentBounds());
        // min x = 50 (note), min y = 10 (note)
        assertEquals(50.0, result.contentBounds().x(), 0.001);
        assertEquals(10.0, result.contentBounds().y(), 0.001);
        // max x = 300+80 = 380, max y = 200+60 = 260
        // width = 380 - 50 = 330, height = 260 - 10 = 250
        assertEquals(330.0, result.contentBounds().width(), 0.001);
        assertEquals(250.0, result.contentBounds().height(), 0.001);
    }

    @Test
    public void assess_shouldUseAbsoluteCoordinatesForNestedElements() {
        // Nested elements have absolute coordinates (pre-accumulated by accessor)
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("child1", 50, 50, 100, 50, "grp"),
                childNode("child2", 350, 250, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of());

        assertNotNull("contentBounds should be present", result.contentBounds());
        // min x = 0 (group), min y = 0 (group)
        assertEquals(0.0, result.contentBounds().x(), 0.001);
        assertEquals(0.0, result.contentBounds().y(), 0.001);
        // max x = max(400, 150, 450) = 450, max y = max(300, 100, 300) = 300
        assertEquals(450.0, result.contentBounds().width(), 0.001);
        assertEquals(300.0, result.contentBounds().height(), 0.001);
    }

    // ---- Short-segment detection tests (Story 11-31) ----

    @Test
    public void countLabelOverlaps_horizontalSegmentShorterThanLabel_shouldFlagShortSegment() {
        // Short horizontal segment (40px) with label "LongLabelText" (13 chars → width=101px)
        // Label width exceeds segment length → should flag
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 90, 0, 50, 50));
        // Path: 40px horizontal segment
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{90, 25}),
                        "LongLabelText", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertTrue("Should detect short segment", result.shortSegmentCount() > 0);
        boolean hasShortSegmentDesc = result.descriptions().stream()
                .anyMatch(d -> d.contains("exceeds segment length"));
        assertTrue("Should have short-segment description", hasShortSegmentDesc);
    }

    @Test
    public void countLabelOverlaps_horizontalSegmentLongerThanLabel_shouldNotFlagShortSegment() {
        // Long horizontal segment (400px) with short label "Uses" (4 chars → width=38px)
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 450, 0, 50, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{450, 25}),
                        "Uses", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Long segment should not flag short-segment", 0, result.shortSegmentCount());
    }

    @Test
    public void countLabelOverlaps_verticalSegmentWithOverlap_shouldFlagNoClearPosition() {
        // Vertical segment with an obstacle overlapping the label at all positions
        // Obstacle covers the full vertical extent of the path
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 0, 400, 50, 50),
                node("obs", 20, 50, 100, 350));  // Obstacle covering the full path
        // Vertical path
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{25, 50}, new double[]{25, 400}),
                        "TestLabel", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        // Should have the "no clear label position" description
        boolean hasNoClearPositionDesc = result.descriptions().stream()
                .anyMatch(d -> d.contains("no clear label position"));
        assertTrue("Should flag no clear label position on vertical segment with obstacle",
                hasNoClearPositionDesc);
    }

    @Test
    public void countLabelOverlaps_shortSegmentSuggestionInAssessLayout() {
        // Verify short-segment suggestion appears in assess-layout nextSteps
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 90, 0, 50, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{90, 25}),
                        "VeryLongLabelName", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections);
        boolean hasShortSegmentSuggestion = result.suggestions().stream()
                .anyMatch(s -> s.contains("exceed available segment length"));
        assertTrue("Should include short-segment suggestion", hasShortSegmentSuggestion);
    }
}
