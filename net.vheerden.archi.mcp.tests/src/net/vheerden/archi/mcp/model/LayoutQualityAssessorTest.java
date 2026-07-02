package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link LayoutQualityAssessor} — pure geometry computation.
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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(0, result.overlapCount());
        assertTrue(result.overlaps().isEmpty());
    }

    @Test
    public void assess_withOverlaps_shouldCountOverlappingPairs() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 50, 25, 100, 50),  // overlaps a
                node("c", 300, 0, 100, 50));  // no overlap

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(0, result.overlapCount());
    }

    @Test
    public void assess_parentChildOverlap_shouldNotCount() {
        // Finding #2: A group containing a child — overlapping rects but parent-child relationship
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("child1", 50, 50, 100, 50, "grp"),
                childNode("child2", 200, 50, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        // The group overlaps both children geometrically, but should be excluded
        assertEquals(0, result.overlapCount());
        // containment overlaps tracked separately
        assertEquals(2, result.containmentOverlapCount());
    }

    @Test
    public void assess_siblingOverlap_insideGroup_shouldCount() {
        // Two children inside a group that overlap each other — NOT parent-child, should count
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("child1", 50, 50, 100, 50, "grp"),
                childNode("child2", 100, 50, 100, 50, "grp"));  // overlaps child1

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        // verify sibling overlap counted, containment tracked separately
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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        // Each element's nearest neighbor is 50px away
        assertEquals(50.0, result.averageSpacing(), 0.1);
    }

    @Test
    public void assess_clusteredElements_shouldReturnSmallSpacing() {
        // Elements very close together (5px gap)
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 105, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(5.0, result.averageSpacing(), 0.1);
    }

    @Test
    public void assess_spacingExcludesParentChild() {
        // Finding #4: Group with children — spacing should exclude group-child distances
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("c1", 50, 50, 100, 50, "grp"),
                childNode("c2", 250, 50, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        // Children share left-edge x=150 → 100% alignment (group excluded)
        assertEquals(100, result.alignmentScore());
    }

    @Test
    public void assess_emptyNodes_alignment_shouldReturnZero() {
        // Finding #12: empty input should return 0, not 100
        LayoutAssessmentResult result = assessor.assess(List.of(), List.of(), false);
        assertEquals(0, result.alignmentScore());
    }

    @Test
    public void assess_singleNode_alignment_shouldReturnZero() {
        // Finding #12: single element should return 0, not 100
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("excellent", result.overallRating());
    }

    @Test
    public void assess_terribleLayout_shouldRatePoor() {
        // Many overlapping elements
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            nodes.add(node("n" + i, 0, 0, 100, 50));
        }

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertTrue(result.boundaryViolations().isEmpty());
    }

    @Test
    public void assess_elementOutsideParent_shouldViolate() {
        // In absolute coordinates: child at (150,100) with size (100,80) extends to (250,180)
        // Parent group at (0,0) with size (200,150) — child extends past right and bottom
        List<AssessmentNode> nodes = List.of(
                group("group", 0, 0, 200, 150),
                childNode("child", 150, 100, 100, 80, "group"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertFalse(result.offCanvasWarnings().isEmpty());
        assertTrue(result.offCanvasWarnings().get(0).contains("'a'"));
        assertTrue(result.offCanvasWarnings().get(0).contains("negative"));
    }

    @Test
    public void assess_veryLargeCoordinates_shouldWarn() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 11000, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertFalse(result.offCanvasWarnings().isEmpty());
        assertTrue(result.offCanvasWarnings().get(0).contains("'b'"));
        assertTrue(result.offCanvasWarnings().get(0).contains("beyond"));
    }

    // ---- Edge cases ----

    @Test
    public void assess_emptyNodes_shouldReturnEmptyResult() {
        LayoutAssessmentResult result = assessor.assess(List.of(), List.of(), false);

        assertEquals(0, result.overlapCount());
        assertEquals(0, result.edgeCrossingCount());
        assertEquals(0.0, result.averageSpacing(), 0.001);
        assertEquals(0, result.alignmentScore());
    }

    @Test
    public void assess_singleNode_shouldReturnTrivialResult() {
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertTrue(result.suggestions().stream()
                .anyMatch(s -> s.contains("overlapping") && s.contains("auto-layout-and-route")));
    }

    @Test
    public void assess_goodLayout_shouldSuggestNoImprovements() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 0, 100, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

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
        // GOOD_MAX_CROSSINGS raised to 20
        assertEquals(20, LayoutQualityAssessor.GOOD_MAX_CROSSINGS);
        // FAIR_MAX_PASS_THROUGHS = 3
        assertEquals(3, LayoutQualityAssessor.FAIR_MAX_PASS_THROUGHS);
    }

    // ---- Pass-through threshold tests ----

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

    // ---- Transitive containment exclusion tests ----

    @Test
    public void assess_nestedGroups_grandparentGrandchild_shouldNotCountAsSiblingOverlap() {
        // TopGroup → SubGroup → Element
        // Grandparent-grandchild should NOT count as sibling overlap
        List<AssessmentNode> nodes = List.of(
                group("topGrp", 0, 0, 500, 400),
                childGroup("subGrp", 20, 20, 460, 360, "topGrp"),
                childNode("elem1", 50, 50, 100, 50, "subGrp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("No sibling overlaps expected", 0, result.overlapCount());
        // topGrp:subGrp, topGrp:elem1, subGrp:elem1 = 3 containment pairs
        assertEquals(3, result.containmentOverlapCount());
    }

    @Test
    public void assess_deeplyNested_allAncestorDescendantExcluded() {
        // 3+ levels — all ancestor-descendant pairs excluded
        // L1 → L2 → L3 → Element
        List<AssessmentNode> nodes = List.of(
                group("l1", 0, 0, 600, 500),
                childGroup("l2", 10, 10, 580, 480, "l1"),
                childGroup("l3", 20, 20, 560, 460, "l2"),
                childNode("leaf", 50, 50, 100, 50, "l3"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("No sibling overlaps in nested containment", 0, result.overlapCount());
        // l1:l2, l1:l3, l1:leaf, l2:l3, l2:leaf, l3:leaf = 6 containment pairs
        assertEquals(6, result.containmentOverlapCount());
    }

    @Test
    public void assess_siblingOverlapsInsideNestedGroup_shouldCount() {
        // Two siblings overlapping inside a nested group
        List<AssessmentNode> nodes = List.of(
                group("topGrp", 0, 0, 500, 400),
                childGroup("subGrp", 20, 20, 460, 360, "topGrp"),
                childNode("a", 50, 50, 100, 50, "subGrp"),
                childNode("b", 100, 50, 100, 50, "subGrp"));  // overlaps a

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("Sibling overlap should be counted", 1, result.overlapCount());
        assertTrue(result.overlaps().get(0).contains("'a'"));
        assertTrue(result.overlaps().get(0).contains("'b'"));
        // topGrp:subGrp, topGrp:a, topGrp:b, subGrp:a, subGrp:b = 5 containment overlaps
        assertEquals(5, result.containmentOverlapCount());
    }

    @Test
    public void assess_containmentOverlaps_countedSeparately() {
        // Verify containment overlaps are reported as separate count
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("c1", 50, 50, 100, 50, "grp"),
                childNode("c2", 200, 50, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(0, result.overlapCount());
        // grp:c1 and grp:c2 = 2 containment overlaps
        assertEquals(2, result.containmentOverlapCount());
    }

    @Test
    public void assess_manyContainmentOverlaps_zeroSiblingOverlaps_shouldRateExcellent() {
        // Rating should use sibling overlaps only
        // Well-spaced children inside a group — excellent layout
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 500, 300),
                childNode("c1", 20, 50, 100, 50, "grp"),
                childNode("c2", 20, 150, 100, 50, "grp"),
                childNode("c3", 20, 250, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(0, result.overlapCount());
        assertTrue("Containment overlaps should exist", result.containmentOverlapCount() > 0);
        assertEquals("excellent", result.overallRating());
    }

    @Test
    public void assess_containmentOnlyOverlaps_shouldNotSuggestSpacious() {
        // Suggestions should reference sibling overlaps only
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 500, 300),
                childNode("c1", 50, 50, 100, 50, "grp"),
                childNode("c2", 50, 150, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        // No suggestion should mention "overlapping" or "spacious"
        assertFalse("Containment-only should not trigger spacious suggestion",
                result.suggestions().stream()
                        .anyMatch(s -> s.contains("overlapping") && s.contains("spacious")));
    }

    @Test
    public void assess_containmentOverlaps_shouldAddInformationalSuggestion() {
        // §10.4: containment overlaps should produce an informational suggestion
        // so the LLM knows they are expected ancestor-descendant overlaps
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 500, 300),
                childNode("c1", 50, 50, 100, 50, "grp"),
                childNode("c2", 50, 150, 100, 50, "grp"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertTrue("Should include informational containment overlap suggestion",
                result.suggestions().stream()
                        .anyMatch(s -> s.contains("containment overlaps detected")
                                && s.contains("No action needed")));
    }

    // ---- Label overlap detection tests ----

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
    public void estimateLabelBounds_shouldApplyRenderWidthFactorToGlyphRun() {
        // Width = length * CHAR_WIDTH * RENDER_WIDTH_FACTOR + PADDING_X (padding chrome not scaled).
        // "Test" (4 chars): 4 * 8.0 * 1.35 + 10.0 = 53.2
        AssessmentConnection conn = new AssessmentConnection("c1", "a", "b",
                List.of(new double[]{0, 0}, new double[]{100, 0}), "Test", 1);
        LayoutQualityAssessor.LabelBounds lb = assessor.estimateLabelBounds(conn);
        assertNotNull(lb);
        // Hardcoded literal so an accidental change to ANY of the three constants is caught
        // (a self-referential `expected` computed from the constants would pass under a wrong value):
        //   "Test" (4 chars): 4 * 8.0 * 1.35 + 10.0 = 53.2
        assertEquals(53.2, lb.width(), 0.001);
        // Height is not scaled by the render-width factor: 14.0 + 6.0 = 20.0
        assertEquals(20.0, lb.height(), 0.001);
    }

    @Test
    public void countLabelOverlaps_renderCalibration_shouldFlagThirdPartyOverlapMissedByRawEstimate() {
        // A third-party box sits just beyond the raw (8.0/char) label box but within the rendered
        // (~1.35x) glyph width. The raw estimate misses it (count 0); the calibrated width catches it.
        // Geometry (label "CalibrationProbeLabel", 21 chars, midpoint x=350):
        //   raw glyph 168 -> box half 89  -> inset right 429, proximity right 439  -> clear of obs@450
        //   cal glyph 226.8 -> box half ~118.4 -> inset right ~458.4               -> overlaps obs@450
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("obs", 450, 0, 100, 50),
                node("b", 600, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{650, 25}),
                        "CalibrationProbeLabel", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertTrue("Calibrated label width should flag the third-party element overlap",
                result.count() > 0);
        assertTrue("Description should name the overlapping element",
                result.descriptions().stream().anyMatch(s -> s.contains("overlaps element 'obs'")));
    }

    @Test
    public void countLabelOverlaps_renderCalibration_shortSegmentUsesCalibratedWidth() {
        // The short-segment "exceeds segment length" path shares the calibrated width by design: a label
        // whose RAW box fits the hosting segment but whose RENDERED (1.35x) glyph overflows it is flagged.
        // "Probe" (5 ch): raw 5*8+10 = 50 (fits a 50px segment); calibrated 5*8*1.35+10 = 64 (overflows).
        List<AssessmentNode> nodes = List.of(
                node("a", -60, 0, 50, 50),
                node("b", 60, 0, 50, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{0, 25}, new double[]{50, 25}),
                        "Probe", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertTrue("Calibrated glyph overflows the hosting segment → short-segment guidance fires",
                result.shortSegmentCount() > 0);
        assertTrue("Short-segment description should name the exceeded segment",
                result.descriptions().stream().anyMatch(s -> s.contains("exceeds segment length")));
    }

    @Test
    public void countLabelOverlaps_renderCalibration_substantialOwnEndpointBleedIsFlagged() {
        // Policy: a label whose calibrated box bleeds a SUBSTANTIAL fraction (>= LABEL_OWN_ENDPOINT_OVERLAP_FRACTION)
        // onto its own endpoints is rendered on the box and is flagged (counted ONCE, naming the more-overlapped
        // endpoint). "VeryLongLabelThatBleeds" (23 ch) at the midpoint of (50,25)->(250,25): box [20.8,279.2],
        // bleeds 79.2/258.4 ≈ 31% onto a[0,100] and the same onto b[200,300] — above the 0.30 bar.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}),
                        "VeryLongLabelThatBleeds", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Substantial own-endpoint bleed is flagged once", 1, result.count());
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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
        // labelOverlapCount should be reflected in result
        assertTrue("Label overlap count should be >= 0", result.labelOverlapCount() >= 0);
        assertNotNull(result.labelOverlaps());
    }

    @Test
    public void countLabelOverlaps_shortLabelFitsGap_notFlagged() {
        // A short label that fits the inter-box gap does not bleed onto its endpoints (0% overlap) and
        // is not flagged. "Uses" (4 ch, ~53px) at the midpoint of (50,25)->(250,25) sits in the 100px
        // gap between a[0,100] and b[200,300] — clear of both boxes.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}),
                        "Uses", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Short label that fits its gap is not flagged", 0, result.count());
    }

    // ---- Own-endpoint label-on-element detection (asymmetric overlap-fraction discriminator) ----

    @Test
    public void countLabelOverlaps_ownEndpoint_bleedsOverSource_shouldCount() {
        // Source-positioned label on a short connection: a large fraction of the label sits over its own
        // source box. a center (50,25) -> b center (250,25); textPosition 0 (15%): centre x=80, "Flow"
        // box [53.4,106.6] -> ~87% over a[0,100]. Above the 0.30 own-endpoint bar -> flagged, names 'a'.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}), "Flow", 0));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Label bleeding substantially over its own source counts once", 1, result.count());
        assertTrue("Description should name the own endpoint 'a'",
                result.descriptions().stream().anyMatch(s -> s.contains("own endpoint 'a'")));
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_bleedsOverTarget_shouldCount() {
        // Target-positioned label (85%): centre x=220, "Flow" box [193.4,246.6] -> ~87% over b[200,300].
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}), "Flow", 2));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Label bleeding substantially over its own target counts once", 1, result.count());
        assertTrue("Description should name the own endpoint 'b'",
                result.descriptions().stream().anyMatch(s -> s.contains("own endpoint 'b'")));
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_clearOfBothEndpoints_shouldNotCount() {
        // A Middle label sitting clear in a wide gap overlaps neither endpoint (0%) -> not flagged.
        // "Reads" box ~[268,332], midpoint of (50,25)->(550,25), clear of a[0,100] and b[500,600].
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 500, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{550, 25}), "Reads", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Label clear of both endpoints is not flagged", 0, result.count());
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_lightGrazingBelowThreshold_shouldNotCount() {
        // The TOLERANCE guard (asymmetric design): a label only slightly wider than its gap grazes its
        // endpoints but stays BELOW the 0.30 bar -> ignored (we don't flood for normal endpoint proximity).
        // "Synchronizes" (12 ch, ~140px) at midpoint of (50,25)->(250,25), gap a[0,100]..b[200,300]=100:
        // box [80.2,219.8] -> ~14% over each endpoint -> NOT flagged.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}), "Synchronizes", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Light grazing below the own-endpoint threshold is tolerated", 0, result.count());
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_groupEndpointExcluded_shouldNotCount() {
        // A group endpoint is a transparent container; even a label heavily over the group bounds must
        // NOT be flagged (intentional nesting).
        List<AssessmentNode> nodes = List.of(
                group("a", 0, 0, 300, 100),
                node("b", 360, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{150, 50}, new double[]{410, 25}), "Flow", 0));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Overlap with a GROUP endpoint is not flagged", 0, result.count());
    }

    // ---- Tiny-endpoint (junction) own-endpoint detection: the box-coverage OR-rule ----
    // The label-area-normalised fraction is structurally unreachable for a genuinely tiny endpoint box
    // (an ArchiMate Junction at its ~14x14 default): a label can FULLY enclose it yet cover only a small
    // fraction of the (much larger) label's own area. The box-coverage rule flags when the label covers a
    // substantial fraction of the BOX instead.

    @Test
    public void countLabelOverlaps_ownEndpoint_tinyJunctionFullyCovered_shouldCount() {
        // A 14x14 junction target fully enclosed by a "Flow" label box. The label-area fraction is only
        // ~0.18 (196 / 1064) — below the 0.30 bar, so the shipped fraction rule MISSES it — but the label
        // covers 100% of the junction box. Source 'a' is clear of the label (fraction 0), so only the
        // junction can flag, and only via box-coverage. Hosting segment (60px) >= label width (53.2px) so
        // the short-segment promotion does NOT apply: this isolates the tiny-box miss.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 40, 50),
                node("j", 73, 18, 14, 14));   // center (80,25), spans x[73,87] y[18,32]
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "j",
                        List.of(new double[]{20, 25}, new double[]{80, 25}), "Flow", 2)); // target position

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("A label fully covering a tiny junction endpoint is flagged once", 1, result.count());
        assertTrue("Description should name the tiny endpoint 'j'",
                result.descriptions().stream().anyMatch(s -> s.contains("own endpoint 'j'")));
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_normalBoxLightlyCovered_shouldNotCount() {
        // AC-3 guard: a NORMAL-sized (100x50) endpoint box that the label merely grazes — fraction ~0.14
        // (below 0.30) AND box-coverage ~0.03 (far below the box-coverage bar). The box-coverage OR-rule
        // must NOT newly flag it: a label is far too small to cover a substantial fraction of a normal box,
        // so the tolerant behaviour is unchanged for ordinary endpoints.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 40, 50),
                node("t", 90, 0, 100, 50));   // normal target box, lightly grazed
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "t",
                        List.of(new double[]{20, 25}, new double[]{80, 25}), "Flow", 2));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("A normal box lightly grazed is not flagged by the box-coverage rule", 0, result.count());
    }

    // ---- Junction-aware own-endpoint sensitivity: a label on a junction's dark fill is always a defect ----
    // A junction renders as a solid dark shape with NO usable interior, so any non-trivial label overlap is
    // unreadable. The box-coverage branch already catches a TINY junction fully under a label; these tests cover
    // the OVERSIZED junction (e.g. the 120x55 default) where a small label grazes the fill at a label-area
    // fraction BELOW the box-tolerant 0.30 bar yet visibly sits on the dark body. The near-zero junction bar
    // (LABEL_OWN_ENDPOINT_JUNCTION_OVERLAP_FRACTION) flags these; the same geometry on a normal box does not.

    @Test
    public void countLabelOverlaps_ownEndpoint_oversizedJunctionSource_grazedBelowBoxBar_shouldCount() {
        // An oversized (120x55) junction SOURCE grazed by a source-positioned "Flow" label.
        // j center (60,27.5); b center (584,27.5); path length 524; textPosition 0 (0.15) -> centre x=138.6,
        // "Flow" box [112.0,165.2]x[17.5,37.5]. Over j[0,120]: ox=8, oy=20 -> label-area fraction
        // 160/1064 = 0.150 (BELOW the 0.30 box bar -> the box rule MISSES it) and box-coverage 160/6600 =
        // 0.024 (far below 0.6 -> the tiny-junction rule cannot catch it either). ONLY the junction bar
        // (0.05) flags it, naming the junction. Without the junction branch this case is not flagged.
        List<AssessmentNode> nodes = List.of(
                junction("j", 0, 0, 120, 55),
                node("b", 534, 0, 100, 55));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "j", "b",
                        List.of(new double[]{60, 27.5}, new double[]{584, 27.5}), "Flow", 0));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("A label grazing an oversized junction's fill below the box bar is flagged once",
                1, result.count());
        assertTrue("Description should name the junction endpoint 'j'",
                result.descriptions().stream().anyMatch(s -> s.contains("own endpoint 'j'")));
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_oversizedJunctionTarget_grazedBelowBoxBar_shouldCount() {
        // The mirror of the source case — a 120x55 junction TARGET grazed by a target-positioned label.
        // Proves the junction bar is applied per endpoint (tgtThreshold), not only to the source.
        // a center (50,27.5); j center (574,27.5); textPosition 2 (0.85) -> centre x=495.4,
        // "Flow" box [468.8,522.0]. Over j[514,634]: ox=8, oy=20 -> fraction 0.150 (<0.30), coverage 0.024.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 55),
                junction("j", 514, 0, 120, 55));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "j",
                        List.of(new double[]{50, 27.5}, new double[]{574, 27.5}), "Flow", 2));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("A label grazing an oversized junction TARGET below the box bar is flagged once",
                1, result.count());
        assertTrue("Description should name the junction endpoint 'j'",
                result.descriptions().stream().anyMatch(s -> s.contains("own endpoint 'j'")));
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_oversizedNormalBox_grazedBelowBoxBar_shouldNotCount() {
        // Leak guard: the EXACT geometry of the oversized-junction-source test, but the endpoint is a
        // NORMAL 120x55 box (isJunction=false) — the single variable. The label-area fraction 0.150 stays
        // below the tolerant 0.30 box bar and box-coverage 0.024 below 0.6, so it is NOT flagged. The
        // junction sensitivity must not leak to ordinary boxes. (Passes with OR without the junction branch.)
        List<AssessmentNode> nodes = List.of(
                node("j", 0, 0, 120, 55),
                node("b", 534, 0, 100, 55));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "j", "b",
                        List.of(new double[]{60, 27.5}, new double[]{584, 27.5}), "Flow", 0));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("The same graze on a NORMAL box stays below the tolerant bar -> not flagged",
                0, result.count());
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_tinyJunctionFullyCovered_isJunction_shouldCount() {
        // No-regression: a real 14x14 Junction (isJunction=true) fully under a "Flow" label still
        // flags — the box-coverage branch (coverage 196/196=1.0 >= 0.6) catches it regardless of the junction
        // bar, and the junction bar (fraction 0.184 >= 0.05) now also fires. Either way it remains flagged.
        // a center (20,25); j[73,87]x[18,32]; textPosition 2 (0.85) on (20,25)->(80,25) -> centre x=71,
        // "Flow" box [44.4,97.6]x[15,35] fully encloses j.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 40, 50),
                junction("j", 73, 18, 14, 14));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "j",
                        List.of(new double[]{20, 25}, new double[]{80, 25}), "Flow", 2));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("A label fully covering a tiny junction is still flagged once", 1, result.count());
        assertTrue("Description should name the junction endpoint 'j'",
                result.descriptions().stream().anyMatch(s -> s.contains("own endpoint 'j'")));
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_labelClearOfTinyJunction_shouldNotCount() {
        // The desired end-state after the build-time fix — the same tiny Junction as the no-regression case
        // but with the label moved fully clear of its fill. fraction 0 (< the 0.05 junction floor) and coverage 0 (< 0.6),
        // so the junction endpoint is NOT over-flagged: the near-zero bar has a non-zero floor by design.
        // a center (20,25); j[273,287]; a Middle "Ok" label on (20,25)->(280,25) -> centre x=150, box
        // [134.2,165.8] clear of both a[0,40] and j[273,287].
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 40, 50),
                junction("j", 273, 18, 14, 14));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "j",
                        List.of(new double[]{20, 25}, new double[]{280, 25}), "Ok", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("A label fully clear of a tiny junction is not flagged", 0, result.count());
    }

    // ---- Offset-aware own-endpoint detection: credit an applied "Label Offset" so a lifted label is not re-flagged ----

    @Test
    public void countLabelOverlaps_ownEndpoint_clearingOffsetApplied_shouldNotCount() {
        // The same Middle bleed as countLabelOverlaps_renderCalibration_substantialOwnEndpointBleedIsFlagged
        // (box [20.8,279.2] ~31% over a and b), but a NORTH (1) Label Offset has been applied. The metric must
        // displace the bounds up by LABEL_OFFSET_RENDER_ESTIMATE -> the label clears both boxes -> not flagged.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}),
                        "VeryLongLabelThatBleeds", 1, 1)); // textPosition=Middle, relativePosition=NORTH

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("A clearing NORTH offset lifts the label off its box -> not re-flagged",
                0, result.count());
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_centerAnchor_shouldStillCount() {
        // Regression guard: with no offset (relativePosition=CENTER=2) the same bleed is still flagged —
        // offset-awareness must leave un-offset behaviour byte-identical to before.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}),
                        "VeryLongLabelThatBleeds", 1, 2)); // relativePosition=CENTER (un-offset)

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Un-offset (CENTER) label still flagged — behaviour unchanged", 1, result.count());
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_insufficientOffsetStillCount() {
        // Truthful, not blunt: a WEST (8) offset shifts the wide label left so it still overlaps its own
        // source box (here even more) -> still flagged. Proves the metric re-tests the displaced bounds
        // rather than blindly trusting any non-CENTER anchor.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}),
                        "VeryLongLabelThatBleeds", 1, 8)); // relativePosition=WEST (does not clear the bleed)

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("A non-clearing (WEST) offset still leaves the label on its box -> still flagged",
                1, result.count());
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_bleedsBothEndpoints_countsOnce() {
        // A Middle label bleeding >= 0.30 onto BOTH neighbours is ONE problem, counted once (not per box).
        // "is realized by" (14 ch, ~161px) at midpoint (90,25) of (30,25)->(150,25): box [9.4,170.6],
        // ~31% over a[0,60] and ~31% over b[120,180].
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 60, 50),
                node("b", 120, 0, 60, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{30, 25}, new double[]{150, 25}), "is realized by", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Bleed onto both endpoints counts once", 1, result.count());
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_selfConnection_countsOnce() {
        // A self-connection (source == target): the label over the single node counts ONCE, not twice.
        // "Loop" box [23.4,76.6] fully within a[0,100] (~100% overlap).
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "a",
                        List.of(new double[]{20, 25}, new double[]{80, 25}), "Loop", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Self-connection own-endpoint counts once, not twice", 1, result.count());
    }

    // ---- Wide-label-on-short-segment own-endpoint detection (promoted sensitivity) ----
    // A Middle label far wider than its hosting segment drapes across BOTH endpoint boxes; the
    // symmetric estimate then splits the bleed so each per-box fraction sits below the normal 0.30
    // bar even though the label visibly covers the boxes. When the rendered label width exceeds the
    // hosting segment length the label provably cannot fit, so the per-box bar is promoted (lowered).

    @Test
    public void countLabelOverlaps_ownEndpoint_wideLabelOnShortSegment_drapesBothBoxes_shouldCount() {
        // The wide-label-on-short-segment miss: a 36-char Middle label on a 200px segment. Rendered width
        // 36*8*1.35 + 10 = 398.8; path (50,25)->(250,25) length 200 < 398.8 -> exceeds its hosting segment.
        // Box [-49.4,349.4] fully covers a[0,100] (100/398.8 = 0.251) and b[200,300] (0.251): max = 0.251
        // < 0.30 so the base rule misses it, but the label exceeds its segment and 0.251 >= the promoted
        // 0.15 bar -> flagged once, naming the connection + endpoint.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}),
                        "Hosts the embedded model and toolkit", 1)); // 36 ch; any wide caption

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Wide label draped across both endpoint boxes on a short segment is flagged once",
                1, result.count());
        assertTrue("Description names the connection and an endpoint",
                result.descriptions().stream().anyMatch(s ->
                        s.contains("c1") && s.contains("own endpoint")));
    }

    @Test
    public void assess_wideLabelOnShortSegment_surfacesOwnEndpointDrape_endToEnd() {
        // End-to-end honesty anchor for the labelOverlaps coverage level (declared "checked"): the
        // wide-label-on-short-segment class must surface through the FULL assess() pipeline, not only
        // the countLabelOverlaps helper. Same geometry as the helper test above — a 36-char Middle
        // label whose rendered width (398.8) exceeds its 200px hosting segment, draping both endpoint
        // boxes at a per-box label-area fraction (0.251) below the 0.30 base bar but at/above the
        // promoted 0.15 bar. Two endpoint-only nodes, so the own-endpoint drape is the SOLE
        // label-overlap contribution (no third-party element, no second label) -> labelOverlapCount
        // is exactly 1 and the dimension participates in the rating breakdown.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}),
                        "Hosts the embedded model and toolkit", 1)); // 36 ch

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
        assertEquals("Wide label draped across both boxes on a short segment surfaces through assess()",
                1, result.labelOverlapCount());
        assertTrue("labelOverlaps participates in the rating breakdown",
                result.ratingBreakdown().containsKey("labelOverlaps"));
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_fitsSegmentGrazesAbovePromotedBar_notFlagged() {
        // Gate guard: a label that FITS its hosting segment keeps the tolerant 0.30 bar even when its
        // own-endpoint overlap sits in (0.15, 0.30) — i.e. ABOVE the promoted bar but BELOW the base bar.
        // A blunt "always promote to 0.15" implementation would wrongly flag this; the width-exceeds-segment
        // gate must suppress promotion. "Hub" (3 ch, W=3*8*1.35+10 = 42.4) on a 400px segment fits.
        // Source-positioned (textPosition 0 -> 15% point x=110): box [88.8,131.2] grazes a[0,100] by
        // 11.2px -> 11.2/42.4 = 0.264 (>0.15, <0.30) -> tolerated because the label fits its segment.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 400, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{450, 25}), "Hub", 0));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("A label that fits its segment keeps the 0.30 bar (no promotion) -> not flagged",
                0, result.count());
    }

    @Test
    public void countLabelOverlaps_ownEndpoint_exceedsShortMidJog_clearOfEndpoints_notFlagged() {
        // No-over-flag guard: exceeding the hosting segment is NOT sufficient on its own — the label must
        // still overlap an endpoint. A Middle label sits on a short vertical jog mid-path, far from both
        // endpoint boxes; it exceeds that tiny jog yet grazes neither box, so it must not be flagged.
        // Path (50,25)->(350,25)->(350,28)->(650,28): total 603, 50% point = 301.5 lands on the 3px jog
        // (P1->P2) -> hostingSegmentLength = 3. "MidJog" (6 ch, W=6*8*1.35+10 = 74.8) > 3 -> exceeds.
        // Label centre (350,26.5) -> box x[312.6,387.4]: clear of a[0,100] and b[600,700] -> fractions 0.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 600, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{350, 25},
                                new double[]{350, 28}, new double[]{650, 28}), "MidJog", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("A label exceeding a short mid-path jog but clear of both endpoints -> not flagged",
                0, result.count());
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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
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
                new AssessmentNode("g", 0, 0, 500, 200, null, true, false, null, 0.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("a", 10, 10, 100, 50, "g", false, false, null, 0.0, null, null, 0.0, 0.0, 0.0),
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
                new AssessmentNode("unrelatedGroup", 150, 0, 200, 100, null, true, false, null, 0.0, null, null, 0.0, 0.0, 0.0),
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
                new AssessmentNode("g", 0, 0, 200, 150, null, true, false, null, 0.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("child", 10, 10, 80, 40, "g", false, false, null, 0.0, null, null, 0.0, 0.0, 0.0),
                node("b", 400, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "g", "b",
                        List.of(new double[]{100, 75}, new double[]{450, 25}),
                        "Serves", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);
        assertEquals("Descendant overlaps should be excluded", 0, result.count());
    }

    // ---- Label proximity detection tests ----

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

    // ---- Group-aware suggestions tests ----

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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
        boolean hasLayoutWithinGroup = result.suggestions().stream()
                .anyMatch(s -> s.contains("layout-within-group"));
        boolean hasComputeLayout = result.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertTrue("Grouped view should suggest layout-within-group", hasLayoutWithinGroup);
        assertFalse("Grouped view should NOT suggest compute-layout", hasComputeLayout);
    }

    @Test
    public void suggestions_flatView_shouldNotRecommendComputeLayout() {
        // flat view should NOT suggest compute-layout
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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
        boolean hasLayoutWithinGroup = result.suggestions().stream()
                .anyMatch(s -> s.contains("layout-within-group"));
        boolean hasComputeLayout = result.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertTrue("Grouped view with overlaps should suggest layout-within-group",
                hasLayoutWithinGroup);
        assertFalse("Grouped view should NOT suggest compute-layout", hasComputeLayout);
    }

    // ---- Density-aware rating calibration tests ----

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
        // PRE-REDESIGN: crossings Tier 2 cap fair. POST-REDESIGN: crossings Tier 3R cap good.
        // 2.0 ratio → breakdown "fair", but routing-tier caps at "good" under M6.
        String ratingDense = assessor.computeOverallRating(
                0, 60, 50.0, 50, 0, 0, 30);
        assertEquals("M6: 2.0 crossings/connection caps at good (Tier 3R, not Tier 2)",
                "good", ratingDense);

        // 5.0 ratio → breakdown "poor", but routing-tier still caps at "good" under M6.
        String ratingVeryDense = assessor.computeOverallRating(
                0, 150, 50.0, 50, 0, 0, 30);
        assertEquals("M6: 5.0 crossings/connection caps at good (Tier 3R, was Tier 2)",
                "good", ratingVeryDense);
    }

    @Test
    public void rating_zeroConnectionsWithManyCrossings_shouldUseLegacyThreshold() {
        // No connections → use absolute threshold (backward compatibility).
        // PRE-REDESIGN: crossings Tier 2 cap fair. POST-REDESIGN: Tier 3R cap good.
        String rating = assessor.computeOverallRating(
                0, 50, 50.0, 50, 0, 0, 0);
        assertEquals("M6: 50 crossings with 0 connections → good (Tier 3R cap, was fair)",
                "good", rating);
    }

    // ---- Deep nesting overlap false positive tests ----

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
        assertEquals("Genuinely overlapping siblings should be detected",
                1, result.overlapCount());
    }

    // ---- Result includes connectionCount and crossingsPerConnection ----

    @Test
    public void assess_shouldIncludeConnectionCountAndCrossingRatio() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{50, 25}, new double[]{250, 25}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
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
        // Boundary: exactly 4.0 crossings/connection (CROSSING_RATIO_MODERATE).
        // PRE-REDESIGN: crossings Tier 2 cap fair → overall "fair".
        // POST-REDESIGN M6: crossings demoted Tier 3R cap good → overall "good".
        String rating = assessor.computeOverallRating(
                0, 120, 50.0, 50, 0, 0, 30); // 120/30 = 4.0
        assertEquals("M6: 4.0 crossings/connection breakdown fair, overall capped at good (Tier 3R)",
                "good", rating);
    }

    // ---- Note-aware layout tests ----

    @Test
    public void assess_notesExcludedFromSiblingOverlapCount() {
        // Note overlaps element — should NOT count as sibling overlap
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),
                note("n1", 0, 0, 150, 30)); // overlaps "a" but is a note

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(0, result.overlapCount());
    }

    @Test
    public void assess_notesExcludedFromSpacingCalculation() {
        // Note placed very close to element — should NOT affect spacing
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),
                note("n1", 105, 0, 80, 30)); // very close to "a", between a and b

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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
                new AssessmentNode("n1", 10, 10, 150, 30, "grp", false, true, null, 0.0, null, null, 0.0, 0.0, 0.0));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(0, result.noteOverlapCount());
        assertTrue(result.noteOverlapDescriptions().isEmpty());
    }

    @Test
    public void assess_noteOverlapsGroup_shouldCount() {
        // Top-level note overlapping a group — NOW detected
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("elem", 50, 50, 100, 50, "grp"),
                note("n1", 0, 0, 150, 30)); // overlaps group bounds

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(0, result.overlapCount());
        assertEquals(0, result.edgeCrossingCount());
        assertEquals(0, result.alignmentScore());
        // Note-to-note overlaps are not counted (only note-element overlaps)
        assertEquals(0, result.noteOverlapCount());
    }

    // ---- Note-to-group overlap detection ----

    @Test
    public void assess_noteOverlapsGroupSmallOverlapAtTop_shouldCount() {
        // Note overlaps only the top edge of a group (small overlap) — still detected via full bounding box
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                note("n1", 10, 5, 100, 20)); // overlaps group at top edge (y=5, h=20 → bottom=25)

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(2, result.noteOverlapCount());
    }

    @Test
    public void assess_noteInClearSpace_shouldNotCount() {
        // Note placed far from any element or group — no overlap
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 200, 200),
                node("elem", 10, 10, 50, 50),
                note("n1", 500, 500, 100, 30)); // far away

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(0, result.noteOverlapCount());
    }

    @Test
    public void assess_noteOverlapsRegularElement_regressionCheck() {
        // Regression: note-to-element overlap must still work after group skip removal
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                note("n1", 10, 10, 80, 30)); // overlaps "a"

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(1, result.noteOverlapCount());
        assertTrue(result.noteOverlapDescriptions().get(0).contains("element"));
    }

    @Test
    public void assess_childNoteOverlapsDifferentGroup_shouldCount() {
        // M1: Note is child of grpA but overlaps grpB — should be detected (only parent group is excluded)
        List<AssessmentNode> nodes = List.of(
                group("grpA", 0, 0, 200, 200),
                group("grpB", 180, 0, 200, 200),
                new AssessmentNode("n1", 170, 10, 50, 30, "grpA", false, true, null, 0.0, null, null, 0.0, 0.0, 0.0)); // child of grpA, overlaps grpB

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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
                new AssessmentNode("n1", 10, 40, 120, 30, "grp", false, true, null, 0.0, null, null, 0.0, 0.0, 0.0)); // child note overlaps sibling elem

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        // Parent group overlap excluded, but sibling element overlap detected
        assertEquals(1, result.noteOverlapCount());
        assertTrue(result.noteOverlapDescriptions().get(0).contains("element"));
        assertTrue(result.noteOverlapDescriptions().get(0).contains("elem"));
    }

    @Test
    public void assess_noteOverlapsGroup_ratingUnchanged() {
        // note overlaps should NOT affect the quality rating
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("a", 20, 50, 100, 50, "grp"),
                childNode("b", 200, 50, 100, 50, "grp"),
                note("n1", 0, 0, 150, 30)); // overlaps group

        LayoutAssessmentResult withNote = assessor.assess(nodes, List.of(), false);

        List<AssessmentNode> nodesWithoutNote = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("a", 20, 50, 100, 50, "grp"),
                childNode("b", 200, 50, 100, 50, "grp"));

        LayoutAssessmentResult withoutNote = assessor.assess(nodesWithoutNote, List.of(), false);

        // Rating should be identical regardless of note overlaps
        assertEquals(withoutNote.overallRating(), withNote.overallRating());
        assertTrue(withNote.noteOverlapCount() > 0); // overlap IS detected
    }

    // ---- hasGroups in LayoutAssessmentResult ----

    @Test
    public void assess_withGroups_shouldSetHasGroupsTrue() {
        List<AssessmentNode> nodes = List.of(
                group("g1", 0, 0, 400, 300),
                childNode("a", 20, 20, 100, 50, "g1"),
                childNode("b", 200, 20, 100, 50, "g1"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertTrue("hasGroups should be true when groups present", result.hasGroups());
    }

    @Test
    public void assess_withoutGroups_shouldSetHasGroupsFalse() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertFalse("hasGroups should be false when no groups present", result.hasGroups());
    }

    @Test
    public void assess_withNotesOnly_shouldSetHasGroupsFalse() {
        List<AssessmentNode> nodes = List.of(
                note("n1", 0, 0, 100, 30),
                note("n2", 200, 0, 100, 30));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertFalse("hasGroups should be false for notes-only views", result.hasGroups());
    }

    // ---- Rating breakdown and grouped-view leniency tests ----

    @Test
    public void ratingBreakdown_shouldBeIncludedInAssessResult() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertNotNull("ratingBreakdown should not be null", result.ratingBreakdown());
        assertTrue("breakdown should contain overlaps", result.ratingBreakdown().containsKey("overlaps"));
        assertTrue("breakdown should contain edgeCrossings", result.ratingBreakdown().containsKey("edgeCrossings"));
        assertTrue("breakdown should contain spacing", result.ratingBreakdown().containsKey("spacing"));
        assertTrue("breakdown should contain alignment", result.ratingBreakdown().containsKey("alignment"));
        assertTrue("breakdown should contain labelOverlaps", result.ratingBreakdown().containsKey("labelOverlaps"));
        assertTrue("breakdown should contain passThroughs", result.ratingBreakdown().containsKey("passThroughs"));
        assertTrue("breakdown should contain coincidentSegments", result.ratingBreakdown().containsKey("coincidentSegments"));
        assertTrue("breakdown should contain nonOrthogonalTerminals", result.ratingBreakdown().containsKey("nonOrthogonalTerminals"));
        assertTrue("breakdown should contain overall", result.ratingBreakdown().containsKey("overall"));
    }

    // ---- Coverage declaration tests (absence != zero) ----

    @Test
    public void coverage_shouldContainAnEntryForEveryRegistryDimension() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        Map<String, String> coverage = result.coverage();
        assertNotNull("coverage map must be present on the main assess() path", coverage);
        // Every canonical registry dimension has exactly one entry — no key missing, none extra.
        for (LayoutQualityAssessor.CoverageDimension dim
                : LayoutQualityAssessor.CoverageDimension.values()) {
            assertTrue("coverage must declare dimension '" + dim.id + "'",
                    coverage.containsKey(dim.id));
        }
        assertEquals("coverage must have exactly one entry per registry dimension",
                LayoutQualityAssessor.CoverageDimension.values().length, coverage.size());
        // Every value is one of the four legal coverage states.
        for (String v : coverage.values()) {
            assertTrue("illegal coverage value: " + v,
                    LayoutQualityAssessor.COVERAGE_CHECKED.equals(v)
                            || LayoutQualityAssessor.COVERAGE_PARTIAL.equals(v)
                            || LayoutQualityAssessor.COVERAGE_NOT_CHECKED.equals(v)
                            || LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE.equals(v));
        }
    }

    @Test
    public void coverage_fullyCheckedDimensionWithZeroFindings_reportsChecked() {
        // Load-bearing distinction: a view with ZERO overlaps must still report the overlaps
        // detector as "checked" (it ran and found nothing) — checked+zero is NOT not-checked.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("zero overlaps must still report checked",
                LayoutQualityAssessor.COVERAGE_CHECKED, result.coverage().get("overlaps"));
        assertEquals("a clean overlaps dimension scores pass",
                "pass", result.ratingBreakdown().get("overlaps"));
        assertEquals("no overlaps found", 0, result.overlapCount());
    }

    @Test
    public void coverage_eachDimensionMatchesItsDeclaredLevel() {
        // Anti-over-claim guard, expressed structurally so it survives future level flips: on a
        // clean fixture every dimension's reported coverage MUST equal the level it declares on the
        // registry — no dimension may masquerade as more (or less) covered than it is. The declared
        // level is the baseline/floor; one dimension (labelOverlaps) downgrades contextually to
        // "partial" when a label exceeds its hosting segment, which this connection-less fixture does
        // not exercise — so here every dimension reports its declared level: all declare "checked"
        // except corridorCentering, which permanently declares (and reports) "not-checked".
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        for (LayoutQualityAssessor.CoverageDimension dim
                : LayoutQualityAssessor.CoverageDimension.values()) {
            assertEquals("coverage for " + dim.id + " must match its declared level",
                    dim.coverage, result.coverage().get(dim.id));
        }
    }

    @Test
    public void coverage_labelOverlaps_shortSegmentClosed_nowChecked() {
        // The label-overlap class is fully covered: the wide-label-on-short-segment own-endpoint
        // under-count is caught via the promoted LABEL_OWN_ENDPOINT_SHORT_SEGMENT_OVERLAP_FRACTION
        // bar, and a label over a Group is covered by the separate labelOnGroup dimension. On a
        // clean / no-exceeds-segment fixture (this one) labelOverlaps reports its declared "checked"
        // and the contextual downgrade to "partial" fires only on a run where a label exceeds its
        // hosting segment (covered by the dedicated test below). The invariant asserted here is that
        // no dimension is "partial" on a clean run — NOT that the whole map is "checked":
        // corridorCentering is a permanent "not-checked" (single-route corridor centring has no
        // detector), an intentional, honest blind spot distinct from the transient "partial" states.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("connectionThroughNote (interior) is checked — graze closed the route gap",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("connectionThroughNote"));
        assertEquals("connectionGrazesVisual (border graze) is checked",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("connectionGrazesVisual"));
        assertEquals("labelOverlaps short-segment under-count closed (group hosts on labelOnGroup) "
                        + "— now checked",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("labelOverlaps"));
        assertEquals("labelOnNote (connection label on a Note rectangle) is checked",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("labelOnNote"));
        assertEquals("labelOnGroup (connection label on a Group title band) is checked",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("labelOnGroup"));
        // No dimension should remain "partial" once the last under-counter closes.
        assertFalse("no coverage dimension remains partial",
                result.coverage().containsValue(LayoutQualityAssessor.COVERAGE_PARTIAL));
    }

    /**
     * A connection whose label is wider than its hosting segment but is positioned in open space so
     * it CLEARS both endpoint boxes: the symmetric path dips to a short (40px) horizontal jog at the
     * path midpoint, far from either endpoint. The label "VeryLongLabelName" (~194px rendered) cannot
     * fit the 40px jog (so the descriptive short-segment signal fires) yet does not geometrically
     * overlap either box (so the overlap count is honestly 0). This is the live case a wide caption
     * exhibits when it overruns its segment and visually crowds a neighbour without overlapping it.
     */
    private static List<AssessmentConnection> labelExceedsSegmentConn(String labelText) {
        return List.of(new AssessmentConnection("c1", "a", "b",
                List.of(new double[]{50, 25}, new double[]{205, 25}, new double[]{205, 100},
                        new double[]{245, 100}, new double[]{245, 25}, new double[]{400, 25}),
                labelText, 1));
    }

    private static List<AssessmentNode> labelExceedsSegmentNodes() {
        return List.of(node("a", 0, 0, 50, 50), node("b", 400, 0, 50, 50));
    }

    @Test
    public void coverage_labelExceedsSegment_downgradesLabelOverlapsToPartial() {
        // The overlap detector runs and finds nothing (the label clears both boxes), but a label
        // exceeding its hosting segment is a crowding signal the overlap count cannot certify clean —
        // so coverage downgrades labelOverlaps from its declared "checked" to "partial" for this run.
        List<AssessmentNode> nodes = labelExceedsSegmentNodes();
        List<AssessmentConnection> connections = labelExceedsSegmentConn("VeryLongLabelName");

        // Precondition: the exceeds-segment signal is present (helper-level).
        assertTrue("fixture must exercise the exceeds-segment signal",
                assessor.countLabelOverlaps(connections, nodes).shortSegmentCount() > 0);

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

        // Precondition: overlap count is honestly zero — the label does not overlap either box.
        assertEquals("the label clears both endpoint boxes — no overlap", 0,
                result.labelOverlapCount());
        // Behaviour under test: coverage reports partial, not the registry-declared checked.
        assertEquals("labelOverlaps downgrades to partial when a label exceeds its hosting segment",
                LayoutQualityAssessor.COVERAGE_PARTIAL,
                result.coverage().get("labelOverlaps"));
        // Only labelOverlaps downgrades — every other dimension stays at its declared level.
        for (LayoutQualityAssessor.CoverageDimension dim
                : LayoutQualityAssessor.CoverageDimension.values()) {
            if (dim == LayoutQualityAssessor.CoverageDimension.LABEL_OVERLAPS) continue;
            assertEquals("non-labelOverlaps dimension " + dim.id + " keeps its declared level",
                    dim.coverage, result.coverage().get(dim.id));
        }
    }

    @Test
    public void coverage_labelExceedsSegment_ratingUnchangedAndControlStaysChecked() {
        // Informational projection: the coverage downgrade must NOT move the rating. The same
        // geometry with the label shortened to FIT its segment ("Hi") yields the same overall rating
        // and breakdown — only the coverage value differs. The control also confirms a no-exceeds run
        // keeps labelOverlaps "checked" and leaves the whole map partial-free (clean-run invariant).
        List<AssessmentNode> nodes = labelExceedsSegmentNodes();

        LayoutAssessmentResult exceeds =
                assessor.assess(nodes, labelExceedsSegmentConn("VeryLongLabelName"), false);
        LayoutAssessmentResult fits =
                assessor.assess(nodes, labelExceedsSegmentConn("Hi"), false);

        // Control fixture genuinely fits its segment → no short-segment signal → no downgrade.
        assertEquals("control label fits its segment — no short-segment signal", 0,
                assessor.countLabelOverlaps(labelExceedsSegmentConn("Hi"), nodes).shortSegmentCount());
        assertEquals("control keeps labelOverlaps checked",
                LayoutQualityAssessor.COVERAGE_CHECKED, fits.coverage().get("labelOverlaps"));
        assertFalse("control run leaves no dimension partial",
                fits.coverage().containsValue(LayoutQualityAssessor.COVERAGE_PARTIAL));

        // Rating identity: the downgrade is informational only.
        assertEquals("coverage downgrade must not change the overall rating",
                fits.overallRating(), exceeds.overallRating());
        assertEquals("coverage downgrade must not change the rating breakdown",
                fits.ratingBreakdown(), exceeds.ratingBreakdown());
        assertEquals("overlap count identical across the two label widths",
                fits.labelOverlapCount(), exceeds.labelOverlapCount());
    }

    @Test
    public void coverage_corridorCentering_isNotChecked() {
        // The R8 corridorUtilisationScore measures multi-occupant corridor occupancy/spread (how
        // widely two or more parallel routes sharing a wall-pair fan out across the available
        // width). Whether a single route centers within its corridor band versus hugs an edge is
        // measured by no detector: a single-occupant corridor is skipped (contributes nothing →
        // vacuous 1.0) and multi-occupant wall-hugging clamps to 1.0. The corridorCentering
        // dimension therefore declares not-checked, so a perfect occupancy score is never read as
        // certifying centering clean — the centering mode must be render-verified. (Red-on-revert
        // anchor: removing or flipping the CORRIDOR_CENTERING registry entry fails here.)
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("single-route corridor centering has no detector — not-checked",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED,
                result.coverage().get("corridorCentering"));
        // The occupancy/spread metric itself stays fully checked — only centering is unmeasured.
        assertEquals("the multi-occupant occupancy/spread metric remains fully covered",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("corridorUtilisation"));
    }

    /** An assessment node with an explicit parent and fill colour (full canonical form). */
    private static AssessmentNode cfNode(String id, double x, double y, double w, double h,
            String parentId, boolean isGroup, String fill) {
        return new AssessmentNode(id, x, y, w, h, parentId, isGroup, false, null, 0.0,
                null, null, 0.0, 0.0, 0.0, false, fill);
    }

    @Test
    public void containerFillEqualsChild_authoredBlob_isFlagged() {
        List<AssessmentNode> nodes = List.of(
                cfNode("c", 0, 0, 300, 200, null, true, "#80FF80"),
                cfNode("ch", 20, 20, 100, 50, "c", false, "#80FF80"));

        LayoutQualityAssessor.ContainerFillResult r =
                assessor.countContainerFillEqualsChild(nodes, true);

        assertEquals("authored same-fill container/child is one blob", 1, r.count());
        assertEquals(1, r.descriptions().size());
        assertTrue("violator surfaces the container id", r.violatorIds().contains("c"));
    }

    @Test
    public void containerFillEqualsChild_distinctChildFill_notFlagged() {
        List<AssessmentNode> nodes = List.of(
                cfNode("c", 0, 0, 300, 200, null, true, "#80FF80"),
                cfNode("ch", 20, 20, 100, 50, "c", false, "#3366CC"));

        assertEquals("distinct child fill stands out — no blob", 0,
                assessor.countContainerFillEqualsChild(nodes, false).count());
    }

    @Test
    public void containerFillEqualsChild_unauthoredContainer_notFlagged() {
        // A null-fill container is the emitter's territory (it recedes at add time), not a blob
        // the detector should claim — even when a child happens to carry that same null.
        List<AssessmentNode> nodes = List.of(
                cfNode("c", 0, 0, 300, 200, null, true, null),
                cfNode("ch", 20, 20, 100, 50, "c", false, null));

        assertEquals(0, assessor.countContainerFillEqualsChild(nodes, false).count());
    }

    @Test
    public void containerFillEqualsChild_caseInsensitiveHex() {
        List<AssessmentNode> nodes = List.of(
                cfNode("c", 0, 0, 300, 200, null, true, "#ABCDEF"),
                cfNode("ch", 20, 20, 100, 50, "c", false, "#abcdef"));

        assertEquals("hex case must not hide a blob", 1,
                assessor.countContainerFillEqualsChild(nodes, false).count());
    }

    @Test
    public void containerFillEqualsChild_coverageReportsChecked() {
        List<AssessmentNode> nodes = List.of(
                cfNode("c", 0, 0, 300, 200, null, true, "#80FF80"),
                cfNode("ch", 20, 20, 100, 50, "c", false, "#80FF80"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("containerFillRecession"));
        assertEquals("the blob surfaces on the result", 1,
                result.containerFillEqualsChildCount());
    }

    @Test
    public void containerFillEqualsChild_hasNoRatingImpact() {
        // Same geometry; only the child fill differs (blob vs distinct). The informational
        // container-fill detector must not perturb any rating.
        List<AssessmentNode> blob = List.of(
                cfNode("c", 0, 0, 300, 200, null, true, "#80FF80"),
                cfNode("ch", 20, 20, 100, 50, "c", false, "#80FF80"));
        List<AssessmentNode> distinct = List.of(
                cfNode("c", 0, 0, 300, 200, null, true, "#80FF80"),
                cfNode("ch", 20, 20, 100, 50, "c", false, "#3366CC"));

        LayoutAssessmentResult blobResult = assessor.assess(blob, List.of(), false);
        LayoutAssessmentResult distinctResult = assessor.assess(distinct, List.of(), false);

        assertEquals(distinctResult.overallRating(), blobResult.overallRating());
        assertEquals(distinctResult.layoutRating(), blobResult.layoutRating());
        assertEquals(distinctResult.routingRating(), blobResult.routingRating());
        assertEquals(distinctResult.ratingBreakdown(), blobResult.ratingBreakdown());
        assertEquals("detector counts differ even though ratings do not",
                1, blobResult.containerFillEqualsChildCount());
        assertEquals(0, distinctResult.containerFillEqualsChildCount());
    }

    @Test
    public void coverage_hasNoRatingImpact_ratingsStayIdentical() {
        // Rating-identity regression guard: building the coverage map must not perturb any rating.
        // Two representative multi-element views pin both ends of the rating scale; coverage
        // is computed AFTER ratings and never feeds back, so these literals must hold.
        List<AssessmentNode> cleanNodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 0, 100, 100, 50),
                node("c", 0, 200, 100, 50));
        LayoutAssessmentResult clean = assessor.assess(cleanNodes, List.of(), false);
        assertEquals("excellent", clean.overallRating());
        assertEquals("excellent", clean.layoutRating());
        assertEquals("excellent", clean.routingRating());
        // coverage is fully populated alongside an untouched excellent rating.
        assertEquals(LayoutQualityAssessor.CoverageDimension.values().length,
                clean.coverage().size());

        List<AssessmentNode> badNodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            badNodes.add(node("n" + i, 0, 0, 100, 50));
        }
        LayoutAssessmentResult bad = assessor.assess(badNodes, List.of(), false);
        assertEquals("poor", bad.overallRating());
        assertEquals(LayoutQualityAssessor.CoverageDimension.values().length,
                bad.coverage().size());

        // The coverage states must never leak into the rating breakdown values.
        for (String v : clean.ratingBreakdown().values()) {
            assertNotEquals(LayoutQualityAssessor.COVERAGE_CHECKED, v);
            assertNotEquals(LayoutQualityAssessor.COVERAGE_PARTIAL, v);
            assertNotEquals(LayoutQualityAssessor.COVERAGE_NOT_CHECKED, v);
        }
    }

    @Test
    public void ratingBreakdown_excellentView_shouldHaveAllPass() {
        // All metrics excellent: 0 overlaps, 0 crossings, good spacing/alignment, 0 labels, 0 pass-throughs
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 0, false);

        assertEquals("excellent", result.rating());
        assertEquals("pass", result.breakdown().get("overlaps"));
        assertEquals("pass", result.breakdown().get("edgeCrossings"));
        assertEquals("pass", result.breakdown().get("spacing"));
        assertEquals("pass", result.breakdown().get("alignment"));
        assertEquals("pass", result.breakdown().get("labelOverlaps"));
        assertEquals("pass", result.breakdown().get("passThroughs"));
        assertEquals("pass", result.breakdown().get("coincidentSegments"));
        assertEquals("pass", result.breakdown().get("nonOrthogonalTerminals"));
        assertEquals("excellent", result.breakdown().get("overall"));
    }

    @Test
    public void denoisedHeadline_terminalCosmeticsOnlyFair_excludesToExcellent() {
        // 3 diagonal terminals over 10 connections (ratio 0.30) → nonOrthogonalTerminals "fair"
        // (Tier-2R cap-fair), every other metric clean → overall "fair". The de-noised headline
        // removes the accepted ELK terminal cosmetic, so it reads "excellent".
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 3, 10, false);

        assertEquals("fair", result.breakdown().get("nonOrthogonalTerminals"));
        assertEquals("fair", result.breakdown().get("overall"));
        assertEquals("excellent",
                result.breakdown().get("overallExcludingAcceptedCosmetics"));
    }

    @Test
    public void denoisedHeadline_realPassThroughDefect_survivesDenoising() {
        // A real Tier-1R defect (2 pass-throughs → "fair") alongside the same diagonal
        // terminals. De-noising only removes the terminal cosmetic; the pass-through still
        // drives the rating, so both headlines read the same.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 2, 0, 3, 10, false);

        assertEquals("fair", result.breakdown().get("passThroughs"));
        assertEquals("fair", result.breakdown().get("nonOrthogonalTerminals"));
        assertEquals("fair", result.breakdown().get("overall"));
        assertEquals("fair",
                result.breakdown().get("overallExcludingAcceptedCosmetics"));
    }

    @Test
    public void denoisedHeadline_cleanView_bothExcellent() {
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 0, false);

        assertEquals("excellent", result.breakdown().get("overall"));
        assertEquals("excellent",
                result.breakdown().get("overallExcludingAcceptedCosmetics"));
    }

    @Test
    public void denoisedHeadline_isAlwaysAFloorNeverALift() {
        // Across a representative sweep the de-noised headline is never more severe than the
        // live overall (it can only equal or improve it, since it removes a contributor) and
        // the live "overall" is never altered by the de-noise computation.
        int[][] cases = {
                {0, 0, 0, 0, 0},   // clean
                {0, 0, 0, 3, 10},  // terminal cosmetics only → fair, denoise excellent
                {0, 0, 0, 6, 10},  // heavy terminals (ratio 0.6 → poor), denoise excellent
                {0, 2, 0, 3, 10},  // pass-through fair survives
                {2, 0, 0, 3, 10},  // overlap poor (Tier-1L) survives layout-side
        };
        for (int[] c : cases) {
            LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                    c[0], 0, 50.0, 80, 0, c[1], 0, c[3], c[4], false);
            int overall = ratingSeverity(result.breakdown().get("overall"));
            int denoised = ratingSeverity(
                    result.breakdown().get("overallExcludingAcceptedCosmetics"));
            assertTrue("de-noised headline must never be more severe than overall",
                    denoised <= overall);
        }
    }

    /** excellent=0 < good=1 < fair=2 < poor=3 — severity ordering for the floor assertion. */
    private static int ratingSeverity(String rating) {
        switch (rating) {
            case "excellent": return 0;
            case "good": return 1;
            case "fair": return 2;
            default: return 3;
        }
    }

    @Test
    public void ratingBreakdown_crossingsOnly_shouldShowCrossingsFair() {
        // Only crossings are bad (25 crossings, 10 connections = 2.5 ratio).
        // PRE-REDESIGN: crossings Tier 2 cap fair → overall fair.
        // POST-REDESIGN M6: crossings demoted Tier 3R cap good → overall good.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 0, 0, 0, 10, false);

        // M6: crossings now Tier 3R cap good — overall caps at "good" not "fair".
        assertEquals("good", result.rating());
        assertEquals("pass", result.breakdown().get("overlaps"));
        assertEquals("fair", result.breakdown().get("edgeCrossings"));
        assertEquals("pass", result.breakdown().get("spacing"));
        assertEquals("pass", result.breakdown().get("alignment"));
        assertEquals("good", result.breakdown().get("overall"));
    }

    @Test
    public void ratingBreakdown_groupedView_crossingsOnly_shouldShowGood() {
        // Grouped view where crossings are the ONLY issue → "good" not "fair"
        // 25 crossings, 10 connections — would be "fair" on flat view
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 0, 0, 0, 10, true);

        assertEquals("good", result.rating());
        assertEquals("good", result.breakdown().get("edgeCrossings"));
        assertEquals("pass", result.breakdown().get("overlaps"));
        assertEquals("pass", result.breakdown().get("passThroughs"));
        assertEquals("good", result.breakdown().get("overall"));
    }

    @Test
    public void ratingBreakdown_flatView_crossingsOnly_shouldStayFair() {
        // PRE-REDESIGN: Same crossings on flat view stayed "fair".
        // POST-REDESIGN: crossings demoted Tier 3R cap good → overall good.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 0, 0, 0, 10, false);

        assertEquals("good", result.rating());
        assertEquals("fair", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void ratingBreakdown_groupedView_withOverlaps_shouldNotGetBonus() {
        // Grouped view with overlaps — leniency bonus does NOT apply (predicate keyed on
        // `overlaps == 0`); under binary rule, `overlaps=2 → poor` drives overall to `poor`.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                2, 25, 50.0, 80, 0, 0, 0, 0, 10, true);

        assertEquals("poor", result.rating());
        assertEquals("fair", result.breakdown().get("edgeCrossings"));
        assertEquals("poor", result.breakdown().get("overlaps"));
    }

    @Test
    public void ratingBreakdown_groupedView_withFewPassThroughs_shouldStillGetBonus() {
        // Grouped view with PT<=3 — crossing leniency STILL applies (relaxed gate)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 2, 0, 0, 10, true);

        // Crossings boosted to "good" by leniency, but PT=2 is Tier 1 "fair" → overall "fair"
        assertEquals("fair", result.rating());
        assertEquals("good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void ratingBreakdown_groupedView_manyCrossingsNoOtherIssues_shouldBeGood() {
        // 100 crossings, 28 connections (3.57 per conn) — grouped view, no other issues
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 100, 50.0, 80, 0, 0, 0, 0, 28, true);

        assertEquals("good", result.rating());
        assertEquals("good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void ratingBreakdown_overlapBinary_zero_shouldRatePass() {
        // Lower-edge boundary: overlap=0 + zero other defects → breakdown.overlaps="pass" + overall "excellent".
        // hubPortQualityScore=0 is neutral here — HPQ only enters the routing tier for grouped views (hasGroups=false).
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 0, false);

        assertEquals("excellent", result.rating());
        assertEquals("pass", result.breakdown().get("overlaps"));
    }

    @Test
    public void ratingBreakdown_overlapBinary_one_shouldRatePoor() {
        // Upper-edge boundary (the cut-point redefined here): overlap=1 + zero other defects →
        // breakdown.overlaps="poor" + overall "poor" (Tier-1L no-cap drives layoutLevel=3 → layoutRating=poor).
        // hubPortQualityScore=0 is neutral here — HPQ only enters the routing tier for grouped views (hasGroups=false).
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                1, 0, 50.0, 80, 0, 0, 0, 0, 0, false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertTrue("Should detect groups", result.hasGroups());
        assertNotNull("ratingBreakdown should be present", result.ratingBreakdown());
        // Assessor.Redesign M6: breakdown grew from 9 (8 metrics + overall) to 17 (16 metrics + overall):
        // pre-existing 8 (overlaps, edgeCrossings, spacing, alignment, labelOverlaps, passThroughs,
        // coincidentSegments, nonOrthogonalTerminals) + new 8 (boundaryViolations, parentLabelObscured,
        // offCanvas, labelTruncations, interiorTerminations, zigzags, connectionEdgeCoincidence,
        // hubPortQuality) + "overall" = 17. Hub-to-neighbour crowding (2026-06-25) adds a 17th
        // metric ("hubNeighbourCrowding", present on every call — pass when not crowded) → 18.
        // Non-orthogonal interior segments (promoted to a rating tier) adds an 18th metric
        // ("nonOrthogonalInteriorSegments", present on every call — pass when count is zero) → 19.
        // Connection-through-note/image (promoted to a rating tier) adds a 19th metric
        // ("connectionThroughNote", present on every call — pass when count is zero) → 20.
        // The de-noised headline adds "overallExcludingAcceptedCosmetics" (a companion to
        // "overall", present on every call) → 21.
        assertEquals(21, result.ratingBreakdown().size());
        assertEquals(result.overallRating(), result.ratingBreakdown().get("overall"));
    }

    @Test
    public void ratingBreakdown_noRegression_overlapsStillProducePoor() {
        // Overlaps should still produce poor (Tier 1)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                10, 0, 50.0, 80, 0, 0, 0, 0, 0, false);

        assertEquals("poor", result.rating());
        assertEquals("poor", result.breakdown().get("overlaps"));
    }

    @Test
    public void ratingBreakdown_noRegression_passThroughsStillDowngrade() {
        // Pass-throughs should still downgrade appropriately (Tier 1)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 4, 0, 0, 0, false);

        assertEquals("poor", result.rating());
        assertEquals("poor", result.breakdown().get("passThroughs"));
    }

    // ---- Rating recalibration and suggestion fixes ----

    @Test
    public void rating_flatView_lowCrossingDensity_shouldRateGood() {
        // flat view with ~0.72 crossings/conn should rate "good"
        // 20 crossings / 28 connections = 0.71 ratio — below CROSSING_RATIO_GOOD (1.5)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 20, 50.0, 80, 0, 0, 0, 0, 28, false);

        assertEquals("0.71 crossings/conn should rate good",
                "good", result.breakdown().get("edgeCrossings"));
        assertEquals("good", result.rating());
    }

    @Test
    public void rating_flatView_highCrossingDensity_shouldRateFairOrBelow() {
        // flat view with 3.5+ crossings/conn should rate "fair" or below
        // 105 crossings / 30 connections = 3.5 ratio
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 105, 50.0, 80, 0, 0, 0, 0, 30, false);

        String crossingRating = result.breakdown().get("edgeCrossings");
        assertTrue("3.5 crossings/conn should be fair or poor",
                "fair".equals(crossingRating) || "poor".equals(crossingRating));
    }

    @Test
    public void rating_flatView_crossingRatioAtBoundary_shouldRateGood() {
        // M2 review fix: boundary test at exactly CROSSING_RATIO_GOOD (1.5)
        // 30 crossings / 20 connections = 1.5 ratio — exactly at boundary (<=)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 30, 50.0, 80, 0, 0, 0, 0, 20, false);

        assertEquals("Exactly 1.5 crossings/conn should rate good (boundary <=)",
                "good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void rating_flatView_crossingRatioJustAboveBoundary_shouldNotRateGood() {
        // M2 review fix: just above CROSSING_RATIO_GOOD — should be "fair" not "good"
        // 31 crossings / 20 connections = 1.55 ratio — just above 1.5
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 31, 50.0, 80, 0, 0, 0, 0, 20, false);

        String crossingRating = result.breakdown().get("edgeCrossings");
        assertNotEquals("1.55 crossings/conn should NOT rate good",
                "good", crossingRating);
    }

    @Test
    public void rating_groupedView_oneTierBoost_notUnconditionalGood() {
        // grouped view with very high crossing density — one-tier boost, not floor at "good"
        // 150 crossings / 28 connections = 5.36 ratio → base "poor", boost → "fair" (not "good")
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 150, 50.0, 80, 0, 0, 0, 0, 28, true);

        assertEquals("Very high density grouped view should get one-tier boost to fair, not good",
                "fair", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void rating_groupedView_moderateCrossings_shouldStillBoost() {
        // grouped view with moderate crossings benefits from one-tier boost
        // 25 crossings / 10 connections = 2.5 ratio → base "fair", boost → "good"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 0, 0, 0, 10, true);

        assertEquals("Moderate crossings grouped view should boost to good",
                "good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void suggestions_viewWithContainment_noGroups_shouldNotSuggestComputeLayout() {
        // view with nested elements (containment) but no groups
        // Should suggest auto-route / auto-layout-and-route, NOT compute-layout
        // Use overlapping siblings to trigger overlap suggestion
        List<AssessmentNode> nodes = List.of(
                node("parent", 0, 0, 300, 200),
                childNode("child1", 20, 20, 100, 50, "parent"),
                childNode("child2", 80, 20, 100, 50, "parent"),  // overlaps child1
                node("ext", 400, 50, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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
        // no suggestion text across any scenario references compute-layout
        // Test flat view with overlaps
        List<AssessmentNode> flatOverlap = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 50, 25, 100, 50));
        LayoutAssessmentResult flatResult = assessor.assess(flatOverlap, List.of(), false);
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
        LayoutAssessmentResult crossResult = assessor.assess(flatCross, connections, false);
        boolean crossHasComputeLayout = crossResult.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertFalse("Flat crossings should not suggest compute-layout", crossHasComputeLayout);

        // Test flat view with low alignment
        List<AssessmentNode> flatAlign = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 37, 63, 100, 50),
                node("c", 74, 126, 100, 50));
        LayoutAssessmentResult alignResult = assessor.assess(flatAlign, List.of(), false);
        boolean alignHasComputeLayout = alignResult.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertFalse("Flat alignment should not suggest compute-layout", alignHasComputeLayout);
    }

    @Test
    public void suggestions_groupedView_shouldPreserveGroupedWorkflow() {
        // grouped view with overlapping children → suggests layout-within-group, not compute-layout
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 300),
                childNode("a", 20, 20, 150, 50, "grp"),
                childNode("b", 100, 20, 150, 50, "grp"));  // overlaps a

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        boolean hasLayoutWithinGroup = result.suggestions().stream()
                .anyMatch(s -> s.contains("layout-within-group"));
        boolean hasComputeLayout = result.suggestions().stream()
                .anyMatch(s -> s.contains("compute-layout"));
        assertTrue("Grouped view should suggest layout-within-group", hasLayoutWithinGroup);
        assertFalse("Grouped view should NOT suggest compute-layout", hasComputeLayout);
    }

    // ---- Cross-group boundary overlap filtering ----

    @Test
    public void assess_adjacentGroups_elementsNearBoundary_zeroSiblingOverlaps() {
        // Adjacent groups with elements near shared boundary
        // Group A at x=0..200, Group B at x=200..400 (touching boundary)
        // Elements near the boundary have overlapping bounding boxes across groups
        List<AssessmentNode> nodes = List.of(
                group("grpA", 0, 0, 200, 200),
                group("grpB", 200, 0, 200, 200),
                childNode("a1", 140, 50, 80, 40, "grpA"),  // extends to x=220 (into grpB's area)
                childNode("b1", 190, 50, 80, 40, "grpB")); // starts at x=190 (overlaps a1's bbox)

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("Cross-group boundary elements should not count as sibling overlaps",
                0, result.overlapCount());
    }

    @Test
    public void assess_sameGroupElementsOverlapping_countedAsSiblingOverlap() {
        // Two elements in the SAME group that genuinely overlap
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 400, 200),
                childNode("a", 50, 50, 100, 50, "grp"),
                childNode("b", 100, 50, 100, 50, "grp"));  // overlaps a

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("Same-group sibling overlap should be counted", 1, result.overlapCount());
    }

    @Test
    public void assess_topLevelElementsOverlapping_countedAsSiblingOverlap() {
        // Two top-level elements (parentId=null) overlapping
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 50, 25, 100, 50));  // overlaps a

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("Top-level elements (both null parent) should count as sibling overlap",
                1, result.overlapCount());
    }

    @Test
    public void assess_topLevelGroupsOverlapping_countedAsSiblingOverlap() {
        // Two top-level groups that overlap each other
        List<AssessmentNode> nodes = List.of(
                group("grpA", 0, 0, 300, 200),
                group("grpB", 200, 0, 300, 200));  // overlaps grpA

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("Top-level group overlap should count as sibling overlap",
                1, result.overlapCount());
    }

    @Test
    public void assess_layeredView_multipleAdjacentGroups_zeroFalsePositiveOverlaps() {
        // Layered view with 3 adjacent groups, many elements near boundaries
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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("Layered view should have zero sibling overlaps (no false positives)",
                0, result.overlapCount());
        assertTrue("Containment overlaps should still exist",
                result.containmentOverlapCount() > 0);
    }

    @Test
    public void assess_mixedScenario_onlySameParentOverlapsCounted() {
        // Mixed scenario — some same-parent overlaps + cross-parent proximity
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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("Only same-parent overlap (a1+a2) should be counted, not cross-group (a3+b1)",
                1, result.overlapCount());
    }

    // ---- Label truncation detection tests ----

    @Test
    public void detectLabelTruncation_shouldNotDetect_whenLabelFits() {
        // Element 120px wide, 16px type icon = 104px available. Short name fits.
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("e1", 0, 0, 120, 55, null, false, false, "Short", 50.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.LabelTruncationResult result = assessor.detectLabelTruncation(nodes);
        assertEquals(0, result.count());
        assertTrue(result.descriptions().isEmpty());
    }

    @Test
    public void detectLabelTruncation_shouldDetect_whenWrappedLabelOverflowsVertically() {
        // Element 80px wide, 16px type icon = 64px available. Label 250px wide.
        // Estimated lines = ceil(250/64) = 4. Needed height = 4*14 + 6 = 62 > 55 → truncated.
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("e1", 10, 20, 80, 55, null, false, false, "Very Long Element Name That Wraps Many Lines", 250.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.LabelTruncationResult result = assessor.detectLabelTruncation(nodes);
        assertEquals(1, result.count());
        assertEquals(1, result.descriptions().size());
        assertTrue(result.descriptions().get(0).contains("Very Long Element Name"));
        assertTrue(result.descriptions().get(0).contains("may be truncated"));
    }

    @Test
    public void detectLabelTruncation_shouldNotDetect_whenWrappedLabelFitsVertically() {
        // Element 80px wide, 16px type icon = 64px available. Label 100px wide.
        // Estimated lines = ceil(100/64) = 2. Needed height = 2*14 + 6 = 34 < 55 → fits.
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("e1", 10, 20, 80, 55, null, false, false, "Wraps But Fits", 100.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.LabelTruncationResult result = assessor.detectLabelTruncation(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectLabelTruncation_shouldSkipGroups() {
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("g1", 0, 0, 50, 50, null, true, false, "Group Name", 200.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.LabelTruncationResult result = assessor.detectLabelTruncation(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectLabelTruncation_shouldSkipNotes() {
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("n1", 0, 0, 50, 50, null, false, true, "Note Text", 200.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.LabelTruncationResult result = assessor.detectLabelTruncation(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectLabelTruncation_shouldSkipNullName() {
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("e1", 0, 0, 80, 55, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.LabelTruncationResult result = assessor.detectLabelTruncation(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectLabelTruncation_shouldCapDescriptionsAtMax() {
        List<AssessmentNode> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            nodes.add(new AssessmentNode("e" + i, i * 100, 0, 50, 55, null, false, false,
                    "VeryLongName" + i, 200.0, null, null, 0.0, 0.0, 0.0));
        }
        LayoutQualityAssessor.LabelTruncationResult result = assessor.detectLabelTruncation(nodes);
        assertEquals(15, result.count()); // exact count
        assertEquals(10, result.descriptions().size()); // capped at MAX_DESCRIPTIONS
    }

    // ---- Note text clip tests ----

    /** Builds a top-level note carrying a pre-computed required content height. */
    private static AssessmentNode noteWithRequired(String id, double x, double y,
                                                   double w, double h, double requiredHeight) {
        return new AssessmentNode(id, x, y, w, h, null, false, true, null, 0.0, null, null, requiredHeight, 0.0, 0.0);
    }

    @Test
    public void detectNoteTextClipping_shouldDetect_whenContentTallerThanBox() {
        // Box 90px tall, content needs 240px → clipped.
        List<AssessmentNode> notes = List.of(
                noteWithRequired("n1", 10, 20, 600, 90, 240.0));
        LayoutQualityAssessor.NoteClipResult result = assessor.detectNoteTextClipping(notes);
        assertEquals(1, result.count());
        assertEquals(1, result.descriptions().size());
        assertTrue(result.descriptions().get(0).contains("n1"));
        assertTrue(result.descriptions().get(0).contains("clips"));
    }

    @Test
    public void detectNoteTextClipping_shouldNotDetect_whenAutoFitted() {
        // Auto-fitted note: required height == box height (within tolerance) → not clipped.
        List<AssessmentNode> notes = List.of(
                noteWithRequired("n1", 0, 0, 600, 120, 120.0));
        LayoutQualityAssessor.NoteClipResult result = assessor.detectNoteTextClipping(notes);
        assertEquals(0, result.count());
        assertTrue(result.descriptions().isEmpty());
    }

    @Test
    public void detectNoteTextClipping_shouldNotDetect_whenBoxTallerThanContent() {
        // Generously sized box: required 120px, box 400px → not clipped.
        List<AssessmentNode> notes = List.of(
                noteWithRequired("n1", 0, 0, 600, 400, 120.0));
        LayoutQualityAssessor.NoteClipResult result = assessor.detectNoteTextClipping(notes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectNoteTextClipping_shouldSkip_whenRequiredHeightUnavailable() {
        // requiredHeight == 0 means no content / measurement unavailable → skipped, even if box is tiny.
        List<AssessmentNode> notes = List.of(
                noteWithRequired("n1", 0, 0, 600, 5, 0.0));
        LayoutQualityAssessor.NoteClipResult result = assessor.detectNoteTextClipping(notes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectNoteTextClipping_shouldNotDetect_withinTolerance() {
        // required exceeds box by <= NOTE_CLIP_TOLERANCE (1px) → not flagged.
        List<AssessmentNode> notes = List.of(
                noteWithRequired("n1", 0, 0, 600, 120, 121.0));
        LayoutQualityAssessor.NoteClipResult result = assessor.detectNoteTextClipping(notes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectNoteTextClipping_shouldCapDescriptionsAtMax() {
        List<AssessmentNode> notes = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            notes.add(noteWithRequired("n" + i, i * 100, 0, 600, 50, 300.0));
        }
        LayoutQualityAssessor.NoteClipResult result = assessor.detectNoteTextClipping(notes);
        assertEquals(15, result.count()); // exact count
        assertEquals(10, result.descriptions().size()); // capped at MAX_DESCRIPTIONS
    }

    @Test
    public void noteTextClipping_shouldNotAffectRating() {
        // A clipped note must leave the rating byte-identical to the same view without it.
        List<AssessmentNode> base = createFourNodeGrid();
        List<AssessmentNode> withClippedNote = new java.util.ArrayList<>(base);
        withClippedNote.add(noteWithRequired("clip", 400, 0, 600, 40, 300.0));

        LayoutAssessmentResult plain = assessor.assess(base, List.of(), false);
        LayoutAssessmentResult withNote = assessor.assess(withClippedNote, List.of(), false);

        assertEquals(1, withNote.noteClipCount());
        assertEquals(0, plain.noteClipCount());
        // Rating dimensions are unchanged by the informational note-clip signal.
        assertEquals(plain.overallRating(), withNote.overallRating());
        assertEquals(plain.layoutRating(), withNote.layoutRating());
        assertEquals(plain.routingRating(), withNote.routingRating());
        assertEquals(plain.ratingBreakdown(), withNote.ratingBreakdown());
    }

    // ---- Connection-through-note/image tests ----

    /** Builds a top-level note (isNote=true) with no clip measurement — used purely as an obstacle. */
    private static AssessmentNode noteObstacle(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, true, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    /** Builds a top-level image-bearing element (imagePath set) with the given image position. */
    private static AssessmentNode imageElement(String id, double x, double y, double w, double h,
                                               String position) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, "Img", 0.0,
                "img/legend.png", position, 0.0, 0.0, 0.0);
    }

    /** A straight horizontal connection from src-centre to tgt-centre (2-point path). */
    private static AssessmentConnection straightConn(String id, String src, String tgt,
                                                     double srcCx, double tgtCx, double cy) {
        return new AssessmentConnection(id, src, tgt,
                List.of(new double[]{srcCx, cy}, new double[]{tgtCx, cy}), "", 1);
    }

    @Test
    public void connectionThroughVisual_routeThroughNote_shouldFlagWithDescription() {
        // src(0,0,100,50) ctr(50,25), tgt(400,0,100,50) ctr(450,25); clipped path is y=25 from x100→x400.
        // Note (200,0,100,80) sits across that line → penetrated (inset rect x[210,290] y[10,70]).
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 0, 100, 80));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 50, 450, 25));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals(1, result.connectionThroughNoteCount());
        assertEquals(1, result.connectionThroughNoteDescriptions().size());
        String desc = result.connectionThroughNoteDescriptions().get(0);
        assertTrue("description names the connection", desc.contains("c1"));
        assertTrue("description names the note", desc.contains("cap"));
        assertTrue("description identifies the visual as a note", desc.contains("note"));
        // The note is invisible to the element pass-through detector (excluded from scoring nodes).
        assertTrue("element pass-through count is untouched",
                result.connectionPassThroughs().isEmpty());
    }

    @Test
    public void connectionThroughVisual_clearOfNote_shouldNotFlag() {
        // Same connection at y=25, but the note is well below the line → not penetrated.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 200, 100, 80));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 50, 450, 25));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals(0, result.connectionThroughNoteCount());
        assertTrue(result.connectionThroughNoteDescriptions().isEmpty());
    }

    @Test
    public void connectionThroughVisual_adjacentToNote_withinInset_shouldNotFlag() {
        // Note box top is 5px below the y=25 line — inside the 10px PASS_THROUGH_INSET grazing
        // band. Treated no stricter than an element: grazing does not flag.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 30, 100, 80));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 50, 450, 25));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals(0, result.connectionThroughNoteCount());
    }

    @Test
    public void connectionThroughVisual_routeThroughImageOverhang_shouldFlagAsImage() {
        // The image rect OVERHANGS its small host box, which is the case detectPassThroughs
        // (element BOXES only) cannot see — proving the new detector adds genuine capability.
        // Host box (180,0,60,20) sits well above the y=60 route, but a 100x100 top-left image
        // extends down to y=100 and IS crossed by the route. Natural dims given so the rendered
        // image rect is larger than the host box and the default icon size.
        AssessmentNode img = new AssessmentNode("img1", 180, 0, 60, 20, null, false, false,
                "Img", 0.0, "img/legend.png", "top-left", 0.0, 100.0, 100.0);
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 40, 80, 40),
                node("tgt", 400, 40, 80, 40),
                img);
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 40, 440, 60));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals(1, result.connectionThroughNoteCount());
        String desc = result.connectionThroughNoteDescriptions().get(0);
        assertTrue("description names the element", desc.contains("img1"));
        assertTrue("description identifies the visual as an image", desc.contains("image"));
        // The host box is clear of the route — the overhanging image rect is invisible to the
        // element pass-through detector, which is exactly the gap this detector closes.
        assertTrue("image-rect overhang is not an element-box pass-through",
                result.connectionPassThroughs().isEmpty());
    }

    @Test
    public void connectionThroughVisual_imageOnOwnEndpoint_shouldNotFlag() {
        // The connection terminates AT the image-bearing element — an image on a connection's own
        // endpoint is not a pass-through (mirrors the element-loop endpoint carve-out).
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 80, 50),
                imageElement("imgTgt", 400, 0, 140, 80, "fill"));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "imgTgt", 40, 470, 25));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals(0, result.connectionThroughNoteCount());
    }

    @Test
    public void connectionThroughVisual_plainElement_flaggedByPassThroughs_notDoubleCounted() {
        // A plain (no-image) element across the path is the EXISTING connectionPassThroughs case;
        // it must NOT also register in the new note/image count.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 80, 50),
                node("tgt", 400, 0, 80, 50),
                node("mid", 180, 0, 140, 80));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 40, 440, 25));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals("plain element pass-through still flagged",
                1, result.connectionPassThroughs().size());
        assertEquals("plain element not counted as a note/image pass-through",
                0, result.connectionThroughNoteCount());
    }

    @Test
    public void connectionThroughVisual_participatesInRouting_demotesOneTier() {
        // Rating PARTICIPATION (inverts the v1 non-participation guard): a single connection routed
        // through a Note now MOVES routingRating. Single variable — the note is present in both runs
        // and only its Y differs (notes are excluded from every scoring metric, so nothing else can
        // change), so the through-note run flips connectionThroughNote pass→good and demotes the
        // otherwise-excellent routing dimension by exactly one tier (excellent → good).
        List<AssessmentNode> clearNodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 200, 100, 80));   // well below the y=25 line → clear
        List<AssessmentNode> throughNodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 0, 100, 80));      // across the y=25 line → penetrated
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 50, 450, 25));

        LayoutAssessmentResult control = assessor.assess(clearNodes, conns, false);
        LayoutAssessmentResult through = assessor.assess(throughNodes, conns, false);

        assertEquals(0, control.connectionThroughNoteCount());
        assertEquals(1, through.connectionThroughNoteCount());
        // The breakdown now ALWAYS carries the key; control is "pass", the through run is "good".
        assertTrue("breakdown carries the key",
                through.ratingBreakdown().containsKey("connectionThroughNote"));
        assertEquals("pass", control.ratingBreakdown().get("connectionThroughNote"));
        assertEquals("good", through.ratingBreakdown().get("connectionThroughNote"));
        // The single crossing demotes routing by exactly one tier; element pass-through stays clean,
        // proving the new entry — not passThroughs — drove the demotion.
        assertEquals("excellent", control.routingRating());
        assertEquals("good", through.routingRating());
        assertEquals("pass", through.ratingBreakdown().get("passThroughs"));
    }

    @Test
    public void connectionThroughNote_zeroShouldRatePass() {
        // Zero crossings → the entry passes; the routing dimension is untouched.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0);
        assertEquals("pass", result.breakdown().get("connectionThroughNote"));
    }

    @Test
    public void connectionThroughNote_capsAtGood_regardlessOfCount() {
        // Presence, not magnitude (the >=1 floor + Tier-3 cap): one crossing and three both rate
        // "good", and the routing dimension is held at "good" — never escalating to fair/poor.
        LayoutQualityAssessor.RatingResult one = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 1);
        LayoutQualityAssessor.RatingResult three = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 3);
        assertEquals("good", one.breakdown().get("connectionThroughNote"));
        assertEquals("good", three.breakdown().get("connectionThroughNote"));
        assertEquals("good", one.routingRating());
        assertEquals("good", three.routingRating());
    }

    @Test
    public void connectionThroughVisual_imageVariant_participatesInRouting() {
        // The image-rect-overhang variant moves routingRating identically to the note variant.
        // Single variable: the image-bearing element is present in both runs and only its Y differs
        // (routing ignores element position), so only the through run flips the entry to "good".
        AssessmentNode imgThrough = new AssessmentNode("img1", 180, 0, 60, 20, null, false, false,
                "Img", 0.0, "img/legend.png", "top-left", 0.0, 100.0, 100.0);
        AssessmentNode imgClear = new AssessmentNode("img1", 180, 200, 60, 20, null, false, false,
                "Img", 0.0, "img/legend.png", "top-left", 0.0, 100.0, 100.0);
        List<AssessmentNode> throughNodes = List.of(
                node("src", 0, 40, 80, 40), node("tgt", 400, 40, 80, 40), imgThrough);
        List<AssessmentNode> clearNodes = List.of(
                node("src", 0, 40, 80, 40), node("tgt", 400, 40, 80, 40), imgClear);
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 40, 440, 60));

        LayoutAssessmentResult control = assessor.assess(clearNodes, conns, false);
        LayoutAssessmentResult through = assessor.assess(throughNodes, conns, false);

        assertEquals(0, control.connectionThroughNoteCount());
        assertEquals(1, through.connectionThroughNoteCount());
        assertEquals("good", through.ratingBreakdown().get("connectionThroughNote"));
        assertEquals("excellent", control.routingRating());
        assertEquals("good", through.routingRating());
    }

    @Test
    public void connectionThroughNote_plainElementCrossing_chargedByPassThroughsNotDoubleCharged() {
        // No-double-charge: a plain element across the route rates via passThroughs (Tier-1R, "fair"
        // for one crossing — FAIR_MAX_PASS_THROUGHS is 3) while connectionThroughNote stays "pass".
        // The two detectors are disjoint, so the crossing is charged exactly once.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 80, 50),
                node("tgt", 400, 0, 80, 50),
                node("mid", 180, 0, 140, 80));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 40, 440, 25));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals(1, result.connectionPassThroughs().size());
        assertEquals(0, result.connectionThroughNoteCount());
        assertEquals("fair", result.ratingBreakdown().get("passThroughs"));
        assertEquals("pass", result.ratingBreakdown().get("connectionThroughNote"));
        assertEquals("fair", result.routingRating());
    }

    @Test
    public void connectionThroughNote_adjacentWithinInset_doesNotParticipate() {
        // A note grazing within the 10px inset does NOT flag, so it must NOT move the rating: the
        // entry stays "pass" and the rating is byte-identical to the note-clear control.
        List<AssessmentNode> grazeNodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 30, 100, 80));     // top 5px below y=25 — within inset
        List<AssessmentNode> clearNodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 200, 100, 80));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 50, 450, 25));

        LayoutAssessmentResult graze = assessor.assess(grazeNodes, conns, false);
        LayoutAssessmentResult clear = assessor.assess(clearNodes, conns, false);

        assertEquals(0, graze.connectionThroughNoteCount());
        assertEquals("pass", graze.ratingBreakdown().get("connectionThroughNote"));
        assertEquals(clear.routingRating(), graze.routingRating());
        assertEquals(clear.ratingBreakdown(), graze.ratingBreakdown());
    }

    @Test
    public void connectionGrazesVisual_routeGrazesNoteBorder_shouldFlagWithDescription() {
        // Note (200,20,100,80): full rect y[20,100], inset rect y[30,90]. The y=25 route enters the
        // note's outer 10px band (20..30) but never reaches the inset interior → border graze, NOT a
        // through-penetration. This is the band the through-visual inset discards (a live-confirmed miss).
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 20, 100, 80));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 50, 450, 25));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals("border graze flagged", 1, result.connectionGrazesVisualCount());
        assertEquals(1, result.connectionGrazesVisualDescriptions().size());
        String desc = result.connectionGrazesVisualDescriptions().get(0);
        assertTrue("description names the connection", desc.contains("c1"));
        assertTrue("description names the note", desc.contains("cap"));
        assertTrue("description identifies a border graze", desc.contains("grazes"));
        // Disjoint: a graze is NOT counted as an interior penetration.
        assertEquals("graze is not an interior through-penetration",
                0, result.connectionThroughNoteCount());
    }

    @Test
    public void connectionGrazesVisual_throughAndGraze_areDisjoint() {
        // Both ways: a route that PENETRATES counts as through (graze 0); a route that only touches
        // the border band counts as graze (through 0). The two counts never co-fire on one crossing.
        List<AssessmentNode> base = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 50, 450, 25));

        // Penetration: note (200,0,100,80) → inset y[10,70] contains the y=25 route.
        List<AssessmentNode> throughNodes = new ArrayList<>(base);
        throughNodes.add(noteObstacle("cap", 200, 0, 100, 80));
        LayoutAssessmentResult through = assessor.assess(throughNodes, conns, false);
        assertEquals("penetration → through", 1, through.connectionThroughNoteCount());
        assertEquals("penetration → not a graze", 0, through.connectionGrazesVisualCount());

        // Graze: note (200,20,100,80) → outer band only.
        List<AssessmentNode> grazeNodes = new ArrayList<>(base);
        grazeNodes.add(noteObstacle("cap", 200, 20, 100, 80));
        LayoutAssessmentResult graze = assessor.assess(grazeNodes, conns, false);
        assertEquals("border touch → graze", 1, graze.connectionGrazesVisualCount());
        assertEquals("border touch → not a through-penetration", 0, graze.connectionThroughNoteCount());
    }

    @Test
    public void connectionGrazesVisual_tinyNoteCrossed_flagsViaGraze() {
        // A 16x16 note is smaller than 2*PASS_THROUGH_INSET (20px), so pathPassesThroughNode insets
        // to a negative-size rect and returns false — a route straight through it was previously
        // SILENT. The graze path tests the un-inset rect and catches the crossing.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("tiny", 242, 17, 16, 16));    // centre (250,25) on the y=25 route
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 50, 450, 25));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals("tiny note crossed → graze flagged", 1, result.connectionGrazesVisualCount());
        assertEquals("tiny note cannot register as an interior penetration",
                0, result.connectionThroughNoteCount());
    }

    @Test
    public void connectionGrazesVisual_imageBorderGraze_shouldFlagAsImage() {
        // Image rect (180,0,100,100), inset y[10,90]. A y=95 route touches the image's bottom band
        // (90..100) but not the inset interior → image-border graze. The host box (180,0,60,20) is
        // well above y=95, so this is invisible to the element-box pass-through detector.
        AssessmentNode img = new AssessmentNode("img1", 180, 0, 60, 20, null, false, false,
                "Img", 0.0, "img/legend.png", "top-left", 0.0, 100.0, 100.0);
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 90, 80, 10),
                node("tgt", 400, 90, 80, 10),
                img);
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 40, 440, 95));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals("image-border graze flagged", 1, result.connectionGrazesVisualCount());
        String desc = result.connectionGrazesVisualDescriptions().get(0);
        assertTrue("description names the element", desc.contains("img1"));
        assertTrue("description identifies the visual as an image", desc.contains("image"));
        assertEquals("graze is not an interior penetration", 0, result.connectionThroughNoteCount());
    }

    @Test
    public void connectionGrazesVisual_clearOfNote_shouldNotFlag() {
        // The note is well clear of the route → neither penetration nor graze.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 200, 100, 80));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 50, 450, 25));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals(0, result.connectionGrazesVisualCount());
        assertTrue(result.connectionGrazesVisualDescriptions().isEmpty());
    }

    @Test
    public void connectionGrazesVisual_informational_noRatingImpact() {
        // Graze is informational: a view with a border graze rates byte-identically to the clear
        // control, and the graze never appears as a ratingBreakdown entry.
        List<AssessmentNode> grazeNodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 20, 100, 80));
        List<AssessmentNode> clearNodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 200, 100, 80));
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 50, 450, 25));

        LayoutAssessmentResult graze = assessor.assess(grazeNodes, conns, false);
        LayoutAssessmentResult clear = assessor.assess(clearNodes, conns, false);

        assertEquals(1, graze.connectionGrazesVisualCount());
        assertEquals(0, clear.connectionGrazesVisualCount());
        assertFalse("graze is not a rating breakdown entry",
                graze.ratingBreakdown().containsKey("connectionGrazesVisual"));
        assertEquals("rating unaffected by an informational graze",
                clear.overallRating(), graze.overallRating());
        assertEquals(clear.ratingBreakdown(), graze.ratingBreakdown());
    }

    @Test
    public void computeRatingWithBreakdown_20ArgOverload_defaultsThroughNotePassByteIdentical() {
        // The 20-arg overload must delegate with connectionThroughNoteCount=0 → entry "pass", rating
        // unchanged from the 21-arg form forwarding 0 (the interior-segment overload precedent).
        LayoutQualityAssessor.RatingResult viaTwenty = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0);
        LayoutQualityAssessor.RatingResult viaTwentyOneZero = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0);
        assertEquals(viaTwentyOneZero.rating(), viaTwenty.rating());
        assertEquals(viaTwentyOneZero.breakdown(), viaTwenty.breakdown());
        assertEquals("pass", viaTwenty.breakdown().get("connectionThroughNote"));
    }

    @Test
    public void connectionThroughVisual_coverageDimensionChecked() {
        // The connection-route-vs-visual class is now fully covered: connectionThroughNote (interior
        // penetration) + connectionGrazesVisual (border graze) together close it, so connectionThroughNote
        // declares "checked". (Red-on-revert anchor for the graze story's coverage flip — reverting the
        // flip to "partial" fails here.)
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("connectionThroughNote"));
    }

    // ---- Label-on-note tests (connection label rendered on a Note rectangle) ----
    // Label box geometry (estimateLabelBounds): height = LABEL_CHAR_HEIGHT + LABEL_PADDING_Y = 20,
    // centred on the path fraction point; width = len*LABEL_CHAR_WIDTH*LABEL_RENDER_WIDTH_FACTOR +
    // LABEL_PADDING_X. For "Accesses" (8 chars) on a (50,25)->(450,25) middle-positioned connection
    // the box centres at (250,25): x in [201.8,298.2], y in [15,35]; after insetRectOverlap's
    // min(10,dim/3) inset the test rect is x in [211.8,288.2], y in [21.67,28.33].

    /** A middle-positioned, straight, horizontal connection that carries a label. */
    private static AssessmentConnection labeledConn(String id, String src, String tgt,
            double x1, double x2, double y, String label) {
        return new AssessmentConnection(id, src, tgt,
                List.of(new double[]{x1, y}, new double[]{x2, y}), label, 1);
    }

    @Test
    public void labelOnNote_labelOverNote_shouldFlagWithDescriptionAndViolator() {
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));
        List<AssessmentNode> notes = List.of(noteObstacle("cap", 200, 0, 100, 50));

        LayoutQualityAssessor.LabelOnNoteResult r = assessor.countLabelOnNote(conns, notes, true);

        assertEquals(1, r.count());
        assertEquals(1, r.descriptions().size());
        String desc = r.descriptions().get(0);
        assertTrue("description names the connection", desc.contains("c1"));
        assertTrue("description names the note", desc.contains("cap"));
        assertTrue("description identifies the host as a note", desc.contains("note"));
        assertTrue("violator surfaces the note id", r.violatorIds().contains("cap"));
    }

    @Test
    public void labelOnNote_noLabel_shouldNotFlag() {
        // Empty label text → estimateLabelBounds returns null → nothing to test against a note.
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, ""));
        List<AssessmentNode> notes = List.of(noteObstacle("cap", 200, 0, 100, 50));

        LayoutQualityAssessor.LabelOnNoteResult r = assessor.countLabelOnNote(conns, notes, false);

        assertEquals(0, r.count());
        assertTrue(r.descriptions().isEmpty());
    }

    @Test
    public void labelOnNote_clearOfNote_shouldNotFlag() {
        // The note sits well clear of the label box (centred at (250,25)).
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));
        List<AssessmentNode> notes = List.of(noteObstacle("cap", 200, 200, 100, 50));

        LayoutQualityAssessor.LabelOnNoteResult r = assessor.countLabelOnNote(conns, notes, true);

        assertEquals(0, r.count());
        assertTrue("no violators when nothing overlaps", r.violatorIds().isEmpty());
    }

    @Test
    public void labelOnNote_smallLabelInLargeNote_flagsWithoutDilution() {
        // A 2-char label box sits fully inside a large note. A boolean inset overlap fires; there is
        // no label-area fraction to dilute below a threshold, so no box-coverage companion is needed.
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Hi"));
        List<AssessmentNode> notes = List.of(noteObstacle("big", 100, -50, 400, 200));

        LayoutQualityAssessor.LabelOnNoteResult r = assessor.countLabelOnNote(conns, notes, false);

        assertEquals(1, r.count());
    }

    @Test
    public void labelOnNote_labelOverTinyNote_flags() {
        // A note far smaller than the label still overlaps it → flags (inverse size regime).
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));
        List<AssessmentNode> notes = List.of(noteObstacle("tiny", 245, 20, 12, 12));

        LayoutQualityAssessor.LabelOnNoteResult r = assessor.countLabelOnNote(conns, notes, false);

        assertEquals(1, r.count());
    }

    @Test
    public void labelOnNote_overlapsTwoNotes_countsPerPair() {
        // The label box (inset x in [211.8,288.2]) straddles two adjacent notes → one annotation each.
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));
        List<AssessmentNode> notes = List.of(
                noteObstacle("n1", 210, 0, 50, 50),
                noteObstacle("n2", 270, 0, 50, 50));

        LayoutQualityAssessor.LabelOnNoteResult r = assessor.countLabelOnNote(conns, notes, true);

        assertEquals(2, r.count());
        assertTrue(r.violatorIds().contains("n1"));
        assertTrue(r.violatorIds().contains("n2"));
    }

    @Test
    public void labelOnNote_independentOfRoute_andNoRatingImpact() {
        // The note sits under the LABEL (y in [8,23]) but BELOW the route line (y=25): label-on-note
        // fires while the route detectors stay clear — proving the dimensions are independent. And the
        // count is informational: rating is byte-identical to the same view with the note moved clear.
        List<AssessmentNode> onNoteNodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 400, 0, 100, 50),
                noteObstacle("cap", 210, 8, 80, 15));
        List<AssessmentNode> clearNodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 400, 0, 100, 50),
                noteObstacle("cap", 210, 200, 80, 15));
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));

        LayoutAssessmentResult onNote = assessor.assess(onNoteNodes, conns, false);
        LayoutAssessmentResult clear = assessor.assess(clearNodes, conns, false);

        assertEquals("label on the note is counted", 1, onNote.labelOnNoteCount());
        assertEquals(1, onNote.labelOnNoteDescriptions().size());
        assertEquals("note moved clear → not counted", 0, clear.labelOnNoteCount());
        // Independent of the route-vs-visual detectors: the line (y=25) misses the note (y in [8,23]).
        assertEquals("route does not penetrate the note", 0, onNote.connectionThroughNoteCount());
        assertEquals("route does not graze the note border", 0, onNote.connectionGrazesVisualCount());
        // Informational: no rating impact, not a breakdown entry.
        assertFalse("labelOnNote is not a rating breakdown entry",
                onNote.ratingBreakdown().containsKey("labelOnNote"));
        assertEquals("rating unaffected by an informational label-on-note",
                clear.overallRating(), onNote.overallRating());
        assertEquals(clear.ratingBreakdown(), onNote.ratingBreakdown());
    }

    @Test
    public void labelOnNote_coverageDimensionChecked() {
        // Red-on-revert anchor for the coverage flip: removing the LABEL_ON_NOTE dimension fails here.
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("labelOnNote"));
    }

    // ---- Label-on-group (title band) tests ----

    /** Builds a top-level named visual group (isGroup=true) used as a title-band host. */
    private static AssessmentNode groupNode(String id, double x, double y, double w, double h,
            String name) {
        return new AssessmentNode(id, x, y, w, h, null, true, false, name, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    @Test
    public void labelOnGroup_labelOverTitleBand_shouldFlagWithDescriptionAndViolator() {
        // Label centred at (250,25); the group's title band is the top 20px strip [y in 10..30],
        // which the label box reaches → flags.
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));
        List<AssessmentNode> nodes = List.of(groupNode("g1", 100, 10, 400, 200, "Layer A"));

        LayoutQualityAssessor.LabelOnGroupResult r = assessor.countLabelOnGroup(conns, nodes, true);

        assertEquals(1, r.count());
        assertEquals(1, r.descriptions().size());
        String desc = r.descriptions().get(0);
        assertTrue("description names the connection", desc.contains("c1"));
        assertTrue("description names the group", desc.contains("g1"));
        assertTrue("description identifies the host region as the group title band",
                desc.contains("title band") && desc.contains("group"));
        assertTrue("violator surfaces the group id", r.violatorIds().contains("g1"));
    }

    @Test
    public void labelOnGroup_labelDeepInBody_shouldNotFlag_andSingleVarNudgeFlips() {
        // The calibration crux: a label INSIDE the group body (below the 20px title band) is normal
        // and must NOT flag, even though it is geometrically inside the full group rectangle. Moving
        // the label up into the title band (single variable: the connection's y) flips 0 → 1.
        List<AssessmentNode> nodes = List.of(groupNode("g1", 100, 10, 400, 200, "Layer A"));

        List<AssessmentConnection> bodyLabel =
                List.of(labeledConn("c1", "a", "b", 50, 450, 100, "Accesses")); // y=100, deep in body
        List<AssessmentConnection> bandLabel =
                List.of(labeledConn("c1", "a", "b", 50, 450, 20, "Accesses"));  // y=20, on title band

        assertEquals("label deep in the group body does not flag (band-not-full-rect)",
                0, assessor.countLabelOnGroup(bodyLabel, nodes, false).count());
        assertEquals("same label nudged up onto the title band flags",
                1, assessor.countLabelOnGroup(bandLabel, nodes, false).count());
    }

    @Test
    public void labelOnGroup_unnamedGroup_shouldNotFlag() {
        // An unnamed group has no title text to collide with → skipped even when the label is on its
        // top strip.
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));
        List<AssessmentNode> nodes = List.of(groupNode("g1", 100, 10, 400, 200, null));

        LayoutQualityAssessor.LabelOnGroupResult r = assessor.countLabelOnGroup(conns, nodes, true);

        assertEquals(0, r.count());
        assertTrue("no violators for an unnamed group", r.violatorIds().isEmpty());
    }

    @Test
    public void labelOnGroup_noteAndElementAreNotHosts_shouldNotFlag() {
        // Only visual Groups are hosts: a note (isNote) and a plain element (isGroup=false) under the
        // label do not contribute to labelOnGroupCount — they are the concern of labelOnNote /
        // labelOverlaps respectively.
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));
        List<AssessmentNode> nonGroups = List.of(
                noteObstacle("cap", 200, 10, 100, 50),
                node("plain", 230, 10, 60, 40));

        LayoutQualityAssessor.LabelOnGroupResult r = assessor.countLabelOnGroup(conns, nonGroups, false);

        assertEquals(0, r.count());
    }

    @Test
    public void labelOnGroup_overlapsTwoGroupTitleBands_countsPerPair() {
        // The label box straddles the title bands of two adjacent named groups → one annotation each.
        List<AssessmentConnection> conns =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));
        List<AssessmentNode> nodes = List.of(
                groupNode("g1", 210, 10, 40, 200, "Left"),
                groupNode("g2", 250, 10, 60, 200, "Right"));

        LayoutQualityAssessor.LabelOnGroupResult r = assessor.countLabelOnGroup(conns, nodes, true);

        assertEquals(2, r.count());
        assertTrue(r.violatorIds().contains("g1"));
        assertTrue(r.violatorIds().contains("g2"));
    }

    @Test
    public void labelOnGroup_sizeRobustBothWays_flags() {
        // Boolean inset overlap, no fractional dilution: a small label on a wide group band, and a
        // wide label on a narrow group band, both flag.
        List<AssessmentConnection> smallLabel =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Hi"));
        List<AssessmentNode> wideGroup = List.of(groupNode("wide", 100, 10, 400, 200, "Wide"));
        assertEquals("small label on a wide group title band flags",
                1, assessor.countLabelOnGroup(smallLabel, wideGroup, false).count());

        List<AssessmentConnection> wideLabel =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));
        List<AssessmentNode> narrowGroup = List.of(groupNode("narrow", 245, 10, 12, 200, "N"));
        assertEquals("wide label on a narrow group title band flags",
                1, assessor.countLabelOnGroup(wideLabel, narrowGroup, false).count());
    }

    @Test
    public void labelOnGroup_informational_noRatingImpact_andCoverageChecked() {
        // Same nodes + same connection path; the only difference is whether the connection carries a
        // label. The label overlaps no SCORED node (a/b are endpoints, the group is skipped by the
        // rating-feeding label-overlap detector), so the rating is byte-identical whether or not the
        // label is present — proving labelOnGroup never reaches the rating.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 400, 0, 100, 50),
                groupNode("g1", 100, 10, 400, 200, "Layer A"));
        List<AssessmentConnection> labelled =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses"));
        List<AssessmentConnection> unlabelled =
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, ""));

        LayoutAssessmentResult onBand = assessor.assess(nodes, labelled, false);
        LayoutAssessmentResult control = assessor.assess(nodes, unlabelled, false);

        assertEquals("label on the group title band is counted", 1, onBand.labelOnGroupCount());
        assertEquals(1, onBand.labelOnGroupDescriptions().size());
        assertEquals("no label → nothing on the band", 0, control.labelOnGroupCount());
        assertFalse("labelOnGroup is not a rating breakdown entry",
                onBand.ratingBreakdown().containsKey("labelOnGroup"));
        assertEquals("rating unaffected by an informational label-on-group",
                control.overallRating(), onBand.overallRating());
        assertEquals(control.ratingBreakdown(), onBand.ratingBreakdown());
        assertEquals("labelOnGroup coverage dimension is checked",
                LayoutQualityAssessor.COVERAGE_CHECKED, onBand.coverage().get("labelOnGroup"));
    }

    // ---- Redundant (collinear / removable) bendpoint tests ----

    @Test
    public void redundantBendpoint_straightCollinearMidpoint_shouldFlagWithDescription() {
        // (0,0)→(50,0)→(100,0): the middle point adds nothing — removing it leaves the same line.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{50, 0}, new double[]{100, 0}), "", 1);

        LayoutQualityAssessor.RedundantBendpointResult result =
                assessor.countRedundantBendpoints(List.of(conn), false);

        assertEquals("collinear midpoint is a redundant bendpoint", 1, result.count());
        assertEquals(1, result.descriptions().size());
        String desc = result.descriptions().get(0);
        assertTrue("description names the connection", desc.contains("c1"));
        assertTrue("description identifies it as redundant", desc.contains("redundant"));
        assertTrue("description names the offending index", desc.contains("index 1"));
    }

    @Test
    public void redundantBendpoint_realCorner_shouldNotFlag() {
        // (0,0)→(50,0)→(50,50): a genuine 90° turn — the bendpoint changes direction, not redundant.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{50, 0}, new double[]{50, 50}), "", 1);

        LayoutQualityAssessor.RedundantBendpointResult result =
                assessor.countRedundantBendpoints(List.of(conn), false);

        assertEquals("a real corner is not redundant", 0, result.count());
    }

    @Test
    public void redundantBendpoint_multipleCollinearPoints_countsEach() {
        // Four collinear interior points on one straight line — counted per bendpoint (not binary).
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{25, 0}, new double[]{50, 0},
                        new double[]{75, 0}, new double[]{100, 0}), "", 1);

        LayoutQualityAssessor.RedundantBendpointResult result =
                assessor.countRedundantBendpoints(List.of(conn), false);

        assertEquals("three interior collinear midpoints each count", 3, result.count());
    }

    @Test
    public void redundantBendpoint_collinearSpike_shouldNotFlag_butIsAZigzag() {
        // (0,0)→(0,40)→(0,10): collinear (all x=0) but the middle OVERSHOOTS past the target —
        // removing it WOULD change the shape. The "between" guard rejects it as redundant; it is
        // the zigzag detector's concern (opposite-sign reversal), proving the two are independent.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{0, 40}, new double[]{0, 10}), "", 1);

        assertEquals("a collinear out-and-back spike is NOT a redundant bendpoint",
                0, assessor.countRedundantBendpoints(List.of(conn), false).count());
        assertEquals("the same spike IS a zigzag (reversal)",
                1, assessor.countZigzags(List.of(conn), java.util.Set.of(), false).count());
    }

    @Test
    public void redundantBendpoint_twoPointPath_shouldNotFlag() {
        // No interior bendpoint at all — nothing to flag.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{100, 0}), "", 1);

        assertEquals(0, assessor.countRedundantBendpoints(List.of(conn), false).count());
    }

    @Test
    public void redundantBendpoint_hasNoRatingImpact() {
        // Rating-identity guard: a redundant midpoint renders the SAME visual line as the straight
        // route, so every rating value must be byte-identical — the metric is informational only.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 200, 0, 100, 50));
        AssessmentConnection straight = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{50, 25}, new double[]{250, 25}), "", 1);
        AssessmentConnection withRedundant = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{50, 25}, new double[]{150, 25}, new double[]{250, 25}), "", 1);

        LayoutAssessmentResult plain = assessor.assess(nodes, List.of(straight), false);
        LayoutAssessmentResult withR = assessor.assess(nodes, List.of(withRedundant), false);

        assertEquals(0, plain.connectionRedundantBendpointCount());
        assertEquals(1, withR.connectionRedundantBendpointCount());
        assertEquals(1, withR.connectionRedundantBendpointDescriptions().size());
        assertEquals(plain.overallRating(), withR.overallRating());
        assertEquals(plain.layoutRating(), withR.layoutRating());
        assertEquals(plain.routingRating(), withR.routingRating());
        assertEquals(plain.ratingBreakdown(), withR.ratingBreakdown());
    }

    @Test
    public void redundantBendpoint_coverageDimensionChecked() {
        // The redundantBendpoints coverage dimension has a detector — must report checked.
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("redundantBendpoints"));
    }

    @Test
    public void redundantBendpoint_collectViolatorIds_surfacesOnlyOffenders() {
        // collectViolatorIds=true must surface the offending connection's id, and only it.
        AssessmentConnection redundant = new AssessmentConnection("redundant", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{50, 0}, new double[]{100, 0}), "", 1);
        AssessmentConnection corner = new AssessmentConnection("corner", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{50, 0}, new double[]{50, 50}), "", 1);

        LayoutQualityAssessor.RedundantBendpointResult result =
                assessor.countRedundantBendpoints(List.of(redundant, corner), true);

        assertTrue("offender id is surfaced", result.violatorIds().contains("redundant"));
        assertFalse("a real corner is not surfaced", result.violatorIds().contains("corner"));
        // The collectViolatorIds=false path returns an empty, immutable set.
        assertTrue(assessor.countRedundantBendpoints(List.of(redundant), false)
                .violatorIds().isEmpty());
    }

    @Test
    public void redundantBendpoint_oneCollinearOvershoot_shouldNotFlag() {
        // Boundary guard: (0,0)→(0,2)→(0,1) is collinear but the midpoint OVERSHOOTS the target
        // by 1px — removing it would change the shape. The between-guard rejects overshoots greater
        // than ε (0.5px); a 1px overshoot is well past that. (Sub-ε overshoots are within the
        // reconstruction-noise floor and are treated as redundant by the widened guard.)
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{0, 2}, new double[]{0, 1}), "", 1);

        assertEquals(0, assessor.countRedundantBendpoints(List.of(conn), false).count());
    }

    @Test
    public void redundantBendpoint_diagonalNearCollinearMicroJog_shouldNotFlag() {
        // (0,0)→(0,1)→(50,1): the middle point is within ~1px of the a→c line at a DIAGONAL angle,
        // but it is a real orthogonal corner — removing it would replace the L with a diagonal
        // (0,0)→(50,1) (a visible, non-orthogonal change). The router's exact axis-aligned
        // removeCollinearPoints keeps it, so it is NOT redundant. thinner span = spanY = 1 > ε(0.5).
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{0, 1}, new double[]{50, 1}), "", 1);

        assertEquals("a diagonal near-collinear micro-jog is not a redundant bendpoint",
                0, assessor.countRedundantBendpoints(List.of(conn), false).count());
    }

    @Test
    public void redundantBendpoint_axisCollinearWithinReconstructionNoise_shouldFlag() {
        // (0,0)→(50,0.4)→(100,0): a genuinely horizontal-collinear leftover whose midpoint is nudged
        // 0.4px off-axis — within the ε(0.5px) int→double element-centre reconstruction floor. It is
        // still redundant (removable with no visible change), so ε must absorb the noise and flag it.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{50, 0.4}, new double[]{100, 0}), "", 1);

        assertEquals("an axis-collinear leftover within the reconstruction-noise floor still flags",
                1, assessor.countRedundantBendpoints(List.of(conn), false).count());
    }

    @Test
    public void redundantBendpoint_assessLevel_countsOnlyAxisAlignedLeftover_notDiagonalMicroJog() {
        // End-to-end payload de-noise through assess(): one routed connection carries BOTH a genuine
        // horizontal-collinear leftover (indices 0-1-2: (0,100)→(50,100)→(100,100)) AND a diagonal
        // near-collinear micro-jog (indices 3-4-5: (0,0)→(0,1)→(50,1)); the connecting triples are
        // real corners. connectionRedundantBendpointCount must count ONLY the axis-aligned leftover.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 200, 0, 100, 50));
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 100}, new double[]{50, 100}, new double[]{100, 100},
                        new double[]{0, 0}, new double[]{0, 1}, new double[]{50, 1}), "", 1);

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(conn), false);

        assertEquals("only the axis-aligned leftover counts; the diagonal micro-jog is excluded",
                1, result.connectionRedundantBendpointCount());
    }

    // ---- Terminal egress-stub exclusion (node-aware overload) tests ----

    @Test
    public void redundantBendpoint_terminalEgressStub_onSourceFace_excluded() {
        // pathPoints = [srcCenter, port0(on src RIGHT face), next] — all on y=25, so the first triple
        // is axis-collinear. port0=(100,25) sits on src's right face (x=100), so it is a router-pinned
        // terminal egress stub, not a removable interior point. The node-aware overload excludes it.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),   // right face x=100
                node("tgt", 200, 0, 100, 50));
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{50, 25}, new double[]{100, 25}, new double[]{200, 25}), "", 1);

        assertEquals("a terminal egress port on the source face is not counted as redundant",
                0, assessor.countRedundantBendpoints(List.of(conn), nodes, false).count());
    }

    @Test
    public void redundantBendpoint_terminalEgressStub_onTargetFace_excluded() {
        // The only axis-collinear removable candidate is the LAST triple's middle — the target port
        // (250,200) on tgt's TOP face (y=200). The first triple is a genuine corner (contributes 0),
        // isolating the target-face exclusion: the on-face target port is not counted, so total is 0.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 200, 200, 100, 50)); // top face y=200, x in [200,300]
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{50, 25}, new double[]{250, 25},
                        new double[]{250, 200}, new double[]{250, 225}), "", 1);

        assertEquals("a terminal egress port on the target face is not counted as redundant",
                0, assessor.countRedundantBendpoints(List.of(conn), nodes, false).count());
    }

    @Test
    public void redundantBendpoint_twoEdgeAttachStraightConnection_reportsZero() {
        // Two-edge-attachment straight connection: [srcCenter, srcPort, tgtPort, tgtCenter], all
        // collinear on y=25, with srcPort on the source right face and tgtPort on the target left
        // face. BOTH the i==0 and the i==size-3 windows are terminal egress stubs → contributes 0.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),     // right face x=100
                node("tgt", 200, 0, 100, 50));  // left face x=200
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{50, 25}, new double[]{100, 25},
                        new double[]{200, 25}, new double[]{250, 25}), "", 1);

        assertEquals("a straight two-edge-attachment connection has no removable bendpoints",
                0, assessor.countRedundantBendpoints(List.of(conn), nodes, false).count());
    }

    @Test
    public void redundantBendpoint_interiorRedundant_inFirstWindow_stillCounts() {
        // Discriminator (the single most important correctness constraint): a bendpoint in the i==0
        // window that is axis-collinear and between its neighbours but lies OFF any element face
        // (a straight centre-to-centre midpoint far outside both boxes) is a genuine interior-removable
        // point and MUST still count. Exclusion keys on the FACE test, not the window index — an
        // off-face point in a terminal window is unaffected by the exclusion.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 200, 0, 100, 50));
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{50, 25}, new double[]{150, 25}, new double[]{250, 25}), "", 1);

        assertEquals("an off-face collinear point in the first window is still redundant",
                1, assessor.countRedundantBendpoints(List.of(conn), nodes, false).count());
    }

    @Test
    public void redundantBendpoint_terminalStub_faceToleranceBoundary_discriminates() {
        // Tightly pins the on-face discrimination at ON_FACE_STUB_TOLERANCE_PX (1.5). Two otherwise
        // identical first-window collinear ports differ ONLY by how far the port sits from the source
        // right face (x=100): 101.0 is 1.0px past the face — exactly how Archi stores a router-attached
        // egress port — so it is within the stub band → treated as an on-face terminal stub → excluded
        // (0); 102.0 is 2.0px past, beyond the band → off-face interior point → still counted (1). This
        // exercises the face check directly (unlike the far-off-face discriminator above) and guards
        // that the widened stub band still catches the real 1px-off ports without over-excluding.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),     // right face x=100
                node("tgt", 300, 0, 100, 50));  // far away — irrelevant to either port
        AssessmentConnection withinTolerance = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{50, 25}, new double[]{101.0, 25}, new double[]{250, 25}), "", 1);
        AssessmentConnection pastTolerance = new AssessmentConnection("c2", "src", "tgt",
                List.of(new double[]{50, 25}, new double[]{102.0, 25}, new double[]{250, 25}), "", 1);

        assertEquals("a port within the stub band (1px off, like a stored router port) is excluded",
                0, assessor.countRedundantBendpoints(List.of(withinTolerance), nodes, false).count());
        assertEquals("a port beyond the stub band is off-face interior → still counted",
                1, assessor.countRedundantBendpoints(List.of(pastTolerance), nodes, false).count());
    }

    @Test
    public void redundantBendpoint_terminalStub_storedOnePixelOffFace_excluded() {
        // Reproduces the real stored geometry of a straight two-edge-attachment connection between two
        // adjacent boxes: both egress ports are stored exactly 1px past their element faces (source
        // right face x=326 → port x=327; target left face x=371 → port x=370). The strict 0.5px band
        // would miss both (leaving a false count of 2); the 1.5px stub band correctly excludes both → 0.
        List<AssessmentNode> nodes = List.of(
                node("src", 80, 104, 246, 55),    // right face x=326
                node("tgt", 371, 104, 246, 55));  // left face x=371
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{203, 131.5}, new double[]{327, 131.5},
                        new double[]{370, 131.5}, new double[]{494, 131.5}), "", 1);

        assertEquals("both 1px-off-face terminal ports are excluded",
                0, assessor.countRedundantBendpoints(List.of(conn), nodes, false).count());
    }

    @Test
    public void redundantBendpoint_interiorMiddle_offFace_stillCounts() {
        // A true interior middle (neither neighbour a centre, off any face) with genuine corners at
        // both terminals: only (200,300) is redundant, and it is strictly interior (i is neither 0
        // nor size-3), so the terminal exclusion never applies to it — the interior count is preserved.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 20, 20),
                node("tgt", 500, 500, 20, 20));
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{10, 10}, new double[]{10, 300}, new double[]{200, 300},
                        new double[]{400, 300}, new double[]{510, 510}), "", 1);

        assertEquals("a strictly-interior off-face collinear middle is still redundant",
                1, assessor.countRedundantBendpoints(List.of(conn), nodes, false).count());
    }

    @Test
    public void redundantBendpoint_terminalStubExclusion_hasNoRatingImpact() {
        // Informational-only gate: the (narrowed) redundant count feeds no rating — a route whose
        // terminal egress stubs are excluded (count 0) and one with a genuine interior redundant
        // point (count 1) are visually the same straight line, so every rating value is byte-identical
        // regardless of the count. Exercises the exclusion branch through assess() while pinning rating identity.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 0, 100, 50),
                node("tgt", 200, 0, 100, 50));
        AssessmentConnection stubOnly = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{50, 25}, new double[]{100, 25},
                        new double[]{200, 25}, new double[]{250, 25}), "", 1);
        AssessmentConnection interior = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{50, 25}, new double[]{150, 25}, new double[]{250, 25}), "", 1);

        LayoutAssessmentResult stub = assessor.assess(nodes, List.of(stubOnly), false);
        LayoutAssessmentResult inter = assessor.assess(nodes, List.of(interior), false);

        assertEquals("terminal egress stubs are excluded from the count",
                0, stub.connectionRedundantBendpointCount());
        assertEquals("the interior redundant point is still counted",
                1, inter.connectionRedundantBendpointCount());
        assertEquals(stub.overallRating(), inter.overallRating());
        assertEquals(stub.layoutRating(), inter.layoutRating());
        assertEquals(stub.routingRating(), inter.routingRating());
        assertEquals(stub.ratingBreakdown(), inter.ratingBreakdown());
    }

    // ---- Non-orthogonal interior (mid) segment tests ----

    @Test
    public void nonOrthInterior_diagonalMidOrthogonalTerminals_shouldFlag_andTerminalCountZero() {
        // (0,0)→(100,0)→(150,50)→(250,50): both terminals horizontal, the MIDDLE hop is a 45°
        // diagonal. The interior detector flags it; the terminal-only detector does not (disjoint).
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{100, 0},
                        new double[]{150, 50}, new double[]{250, 50}), "", 1);

        LayoutQualityAssessor.NonOrthogonalInteriorSegmentResult result =
                assessor.countNonOrthogonalInteriorSegments(List.of(conn), false);

        assertEquals("diagonal interior segment flags the connection once", 1, result.count());
        assertEquals(1, result.descriptions().size());
        String desc = result.descriptions().get(0);
        assertTrue("description names the connection", desc.contains("c1"));
        assertTrue("description identifies it as a non-orthogonal interior segment",
                desc.contains("non-orthogonal interior segment"));
        assertTrue("description names the offending segment index", desc.contains("index 1"));
        // Disjointness: the terminals are orthogonal, so the terminal-only detector sees nothing.
        assertEquals("terminal detector must not see the interior diagonal",
                0, assessor.countNonOrthogonalTerminals(List.of(conn), false).count());
    }

    @Test
    public void nonOrthInterior_allOrthogonalStaircase_shouldNotFlag() {
        // (0,0)→(100,0)→(100,50)→(200,50): every segment is horizontal or vertical.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{100, 0},
                        new double[]{100, 50}, new double[]{200, 50}), "", 1);

        assertEquals("an all-orthogonal staircase has no non-orthogonal interior segment",
                0, assessor.countNonOrthogonalInteriorSegments(List.of(conn), false).count());
    }

    @Test
    public void nonOrthInterior_singleBendpointCorner_shouldNotFlag() {
        // (0,0)→(50,0)→(50,50): one bendpoint → two TERMINAL segments, zero interior segments.
        // The 90° corner exists but it is not interior, so the interior detector ignores it.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{50, 0}, new double[]{50, 50}), "", 1);

        assertEquals("a single-bendpoint path has no interior segment",
                0, assessor.countNonOrthogonalInteriorSegments(List.of(conn), false).count());
    }

    @Test
    public void nonOrthInterior_diagonalTerminalOnly_shouldNotFlag_butTerminalCountSeesIt() {
        // (0,0)→(50,50)→(150,50)→(200,50): the diagonal is the SOURCE terminal segment, the two
        // interior/target segments are horizontal. Disjointness in the other direction.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{50, 50},
                        new double[]{150, 50}, new double[]{200, 50}), "", 1);

        assertEquals("a diagonal terminal is not an interior segment",
                0, assessor.countNonOrthogonalInteriorSegments(List.of(conn), false).count());
        assertTrue("the terminal detector still sees the diagonal terminal",
                assessor.countNonOrthogonalTerminals(List.of(conn), false).count() >= 1);
    }

    @Test
    public void nonOrthInterior_twoDiagonalMidSegments_countsConnectionOnce_twoDescriptions() {
        // (0,0)→(80,0)→(120,40)→(160,80)→(240,80): orthogonal terminals, TWO diagonal mid hops.
        // Per-connection count = 1 (mirrors the terminal count); one description per offending segment.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{80, 0}, new double[]{120, 40},
                        new double[]{160, 80}, new double[]{240, 80}), "", 1);

        LayoutQualityAssessor.NonOrthogonalInteriorSegmentResult result =
                assessor.countNonOrthogonalInteriorSegments(List.of(conn), false);

        assertEquals("counted once per connection regardless of how many segments bend",
                1, result.count());
        assertEquals("each offending interior segment yields a description",
                2, result.descriptions().size());
    }

    @Test
    public void nonOrthInterior_twoPointPath_shouldNotFlag() {
        // No bendpoints at all → no interior segment.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{100, 0}), "", 1);

        assertEquals(0, assessor.countNonOrthogonalInteriorSegments(List.of(conn), false).count());
    }

    @Test
    public void nonOrthInterior_justUnderFiveDegrees_shouldNotFlag() {
        // Interior segment (100,0)→(200,8): atan2(8,100) ≈ 4.57° < 5° threshold → not flagged.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{100, 0},
                        new double[]{200, 8}, new double[]{300, 8}), "", 1);

        assertEquals("a near-cardinal interior segment below 5° is not flagged",
                0, assessor.countNonOrthogonalInteriorSegments(List.of(conn), false).count());
    }

    @Test
    public void nonOrthInterior_justOverFiveDegrees_shouldFlag() {
        // Interior segment (100,0)→(200,10): atan2(10,100) ≈ 5.71° > 5° threshold → flagged.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{100, 0},
                        new double[]{200, 10}, new double[]{300, 10}), "", 1);

        assertEquals("an interior segment above 5° is flagged",
                1, assessor.countNonOrthogonalInteriorSegments(List.of(conn), false).count());
    }

    @Test
    public void nonOrthInterior_zeroLengthInteriorSegment_shouldNotFlag() {
        // (0,0)→(100,0)→(100,0)→(200,0): the single interior segment is zero-length (duplicate
        // bendpoints). Inherits isNonOrthogonal's zero-length guard — not flagged, no exception.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{100, 0},
                        new double[]{100, 0}, new double[]{200, 0}), "", 1);

        assertEquals(0, assessor.countNonOrthogonalInteriorSegments(List.of(conn), false).count());
    }

    @Test
    public void nonOrthInterior_participatesInRouting_lowRatioDemotesOneTier() {
        // Rating PARTICIPATION (inverts the v1 non-participation guard): toggling a connection's
        // MIDDLE segment from orthogonal to diagonal — all else constant — now moves routingRating.
        // Low-ratio fixture: ten connections, only one with a diagonal interior hop and orthogonal
        // terminals, so the interior entry rates "good" (1/10 = NON_ORTH_RATIO_GOOD) and the
        // otherwise-clean routing dimension is demoted by exactly one tier (excellent → good).
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(node("src", 0, 0, 40, 40));
        nodes.add(node("tgt", 400, 60, 40, 40));
        // Nine clean, isolated, straight orthogonal connections (no interior segments), stacked far
        // below so they neither cross nor run collinear with each other or the offender — keeping
        // every routing entry except the interior one at "pass" in both runs.
        List<AssessmentConnection> clean = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int y = 300 + i * 100;
            nodes.add(node("ca" + i, 0, y, 40, 40));
            nodes.add(node("cb" + i, 200, y, 40, 40));
            clean.add(new AssessmentConnection("clean" + i, "ca" + i, "cb" + i,
                    List.of(new double[]{40, y + 20}, new double[]{200, y + 20}), "", 1));
        }
        // Offender terminals are horizontal at the box edges; only the mid segment differs.
        AssessmentConnection orthogonalOffender = new AssessmentConnection("off", "src", "tgt",
                List.of(new double[]{40, 20}, new double[]{200, 20},
                        new double[]{200, 80}, new double[]{400, 80}), "", 1);
        AssessmentConnection diagonalOffender = new AssessmentConnection("off", "src", "tgt",
                List.of(new double[]{40, 20}, new double[]{200, 20},
                        new double[]{260, 80}, new double[]{400, 80}), "", 1);

        List<AssessmentConnection> controlConns = new ArrayList<>(clean);
        controlConns.add(orthogonalOffender);
        List<AssessmentConnection> diagConns = new ArrayList<>(clean);
        diagConns.add(diagonalOffender);

        LayoutAssessmentResult control = assessor.assess(nodes, controlConns, false);
        LayoutAssessmentResult withDiag = assessor.assess(nodes, diagConns, false);

        assertEquals(0, control.nonOrthogonalInteriorSegmentCount());
        assertEquals(1, withDiag.nonOrthogonalInteriorSegmentCount());
        assertEquals(1, withDiag.nonOrthogonalInteriorSegmentDescriptions().size());
        // The breakdown now ALWAYS carries the key; control is "pass", the diagonal run is "good".
        assertEquals("pass", control.ratingBreakdown().get("nonOrthogonalInteriorSegments"));
        assertEquals("good", withDiag.ratingBreakdown().get("nonOrthogonalInteriorSegments"));
        // The single diagonal mid-segment demotes routing by exactly one tier; the terminal
        // detector stays clean (the diagonal is interior-only), proving the new entry drove it.
        assertEquals("excellent", control.routingRating());
        assertEquals("good", withDiag.routingRating());
        assertEquals("pass", withDiag.ratingBreakdown().get("nonOrthogonalTerminals"));
    }

    @Test
    public void nonOrthInterior_coverageDimensionChecked() {
        // The nonOrthogonalInteriorSegments coverage dimension has a detector — must report checked.
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("nonOrthogonalInteriorSegments"));
    }

    @Test
    public void nonOrthInterior_collectViolatorIds_surfacesOnlyOffenders() {
        // collectViolatorIds=true surfaces the offending connection id, and only it.
        AssessmentConnection offender = new AssessmentConnection("offender", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{100, 0},
                        new double[]{150, 50}, new double[]{250, 50}), "", 1);
        AssessmentConnection clean = new AssessmentConnection("clean", "src", "tgt",
                List.of(new double[]{0, 0}, new double[]{100, 0},
                        new double[]{100, 50}, new double[]{200, 50}), "", 1);

        LayoutQualityAssessor.NonOrthogonalInteriorSegmentResult result =
                assessor.countNonOrthogonalInteriorSegments(List.of(offender, clean), true);

        assertTrue("offender id is surfaced", result.violatorIds().contains("offender"));
        assertFalse("an all-orthogonal route is not surfaced", result.violatorIds().contains("clean"));
        // The collectViolatorIds=false path returns an empty, immutable set.
        assertTrue(assessor.countNonOrthogonalInteriorSegments(List.of(offender), false)
                .violatorIds().isEmpty());
    }

    // ---- Off-face parallel-terminal detection (the route-hugs-departed-face mode) ----
    // Fixture geometry: source element bottom face at y=109; the route exits 1px off the face
    // (BP at y=110) then runs a horizontal trunk parallel to and hugging the bottom face, finally
    // approaching the target VERTICALLY so the target terminal segment stays orthogonal (keeps
    // nonOrthogonalTerminalCount=0 — the off-face metric is isolated and disjoint).

    private List<AssessmentNode> offFaceFixtureNodes() {
        // src bottom face = 50 + 59 = 109; tgt top face = 300, x-range 620..680.
        return List.of(node("src", 400, 50, 100, 59), node("tgt", 620, 300, 60, 50));
    }

    @Test
    public void offFaceParallel_hugBelowDepartedBottomFace_flagsOnce() {
        // BP1 1px below the bottom face (y=110), horizontal trunk, then vertical approach to tgt.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{473, 110},
                        new double[]{650, 110}, new double[]{650, 325}), "", 1);
        LayoutQualityAssessor.OffFaceParallelTerminalResult result =
                assessor.countOffFaceParallelTerminals(List.of(conn), offFaceFixtureNodes(), true);
        assertEquals("a terminal hugging the departed face counts once", 1, result.count());
        assertEquals("one offending terminal → one description", 1, result.descriptions().size());
        assertTrue("the hugging connection is the violator", result.violatorIds().contains("c1"));
        // Disjoint from the rating-bearing terminal metric: the 1px exit stub is sub-perceptible
        // (suppressed by the visible-length guard) and the target approach is vertical, so the
        // angular terminal detector sees nothing.
        assertEquals("off-face hug is NOT double-counted by the terminal-angle metric",
                0, assessor.countNonOrthogonalTerminals(List.of(conn), offFaceFixtureNodes(), false).count());
    }

    @Test
    public void offFaceParallel_cleanLExitWithAmpleStub_singleVarOff_doesNotFlag() {
        // SINGLE-VARIABLE control: only the trunk's perpendicular clearance changes (y 110 → 120,
        // stub 1px → 11px ≥ OFF_FACE_MIN_STUB_PX). Everything else identical → count flips to 0.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{473, 120},
                        new double[]{650, 120}, new double[]{650, 325}), "", 1);
        assertEquals(0,
                assessor.countOffFaceParallelTerminals(List.of(conn), offFaceFixtureNodes(), false).count());
    }

    @Test
    public void offFaceParallel_perpendicularExit_doesNotFlag() {
        // A clean perpendicular departure (straight down from the bottom face) is not a hug —
        // isolates the parallel condition.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{450, 130}, new double[]{450, 325}), "", 1);
        assertEquals(0,
                assessor.countOffFaceParallelTerminals(List.of(conn), offFaceFixtureNodes(), false).count());
    }

    @Test
    public void offFaceParallel_parallelTrunkButAmpleStub_doesNotFlag() {
        // Trunk runs parallel to the bottom face but a healthy 21px below it → not a hug.
        // Isolates the stub condition (parallel alone is not enough).
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{473, 130},
                        new double[]{650, 130}, new double[]{650, 325}), "", 1);
        assertEquals(0,
                assessor.countOffFaceParallelTerminals(List.of(conn), offFaceFixtureNodes(), false).count());
    }

    @Test
    public void offFaceParallel_tinyStubButPerpendicularTrunk_doesNotFlag() {
        // BP1 1px off the face (tiny stub) but the trunk runs straight down (perpendicular) → not a
        // hug. Isolates the parallel condition from the stub condition.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{450, 110}, new double[]{450, 325}), "", 1);
        assertEquals(0,
                assessor.countOffFaceParallelTerminals(List.of(conn), offFaceFixtureNodes(), false).count());
    }

    @Test
    public void offFaceParallel_bothTerminalsHug_countsOnce_twoDescriptions() {
        // Source hugs its bottom face (y=110) and target hugs its top face (y=299, top=300).
        List<AssessmentNode> nodes = List.of(
                node("src", 400, 50, 100, 59),   // bottom face = 109
                node("tgt", 400, 300, 100, 50)); // top face = 300
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{430, 110}, new double[]{560, 110},
                        new double[]{560, 299}, new double[]{430, 299}, new double[]{450, 325}), "", 1);
        LayoutQualityAssessor.OffFaceParallelTerminalResult result =
                assessor.countOffFaceParallelTerminals(List.of(conn), nodes, false);
        assertEquals("both ends hug, but the connection is counted once", 1, result.count());
        assertEquals("each hugging terminal yields a description", 2, result.descriptions().size());
    }

    @Test
    public void offFaceParallel_hugBesideDepartedRightFace_flagsWithFaceInDescription() {
        // Vertical-face coverage: element RIGHT face at x=300; route exits 2px off it then runs a
        // vertical trunk hugging the face. Exercises the dy>=dx parallel branch and the x-axis stub.
        List<AssessmentNode> nodes = List.of(
                node("src", 200, 100, 100, 80),    // RIGHT face = 300, y-range 100..180
                node("tgt", 272, 400, 60, 50));    // far below — its terminal is not a hug
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{250, 140}, new double[]{302, 150},
                        new double[]{302, 250}, new double[]{302, 425}), "", 1);
        LayoutQualityAssessor.OffFaceParallelTerminalResult result =
                assessor.countOffFaceParallelTerminals(List.of(conn), nodes, false);
        assertEquals("a vertical hug beside the RIGHT face flags", 1, result.count());
        assertEquals(1, result.descriptions().size());
        assertTrue("the description names the departed RIGHT face",
                result.descriptions().get(0).contains("RIGHT"));
    }

    @Test
    public void offFaceParallel_rightFaceAmpleStub_singleVarOff_doesNotFlag() {
        // SINGLE-VARIABLE control on the vertical (x) axis: trunk x 302 → 310 (stub 2px → 10px ≥ min).
        List<AssessmentNode> nodes = List.of(
                node("src", 200, 100, 100, 80),
                node("tgt", 272, 400, 60, 50));
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{250, 140}, new double[]{310, 150},
                        new double[]{310, 250}, new double[]{310, 425}), "", 1);
        assertEquals(0,
                assessor.countOffFaceParallelTerminals(List.of(conn), nodes, false).count());
    }

    @Test
    public void offFaceParallel_twoPointPath_hasNoTrunk_doesNotFlag() {
        // A bare center-to-center path has no exterior trunk segment to evaluate.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{650, 325}), "", 1);
        assertEquals(0,
                assessor.countOffFaceParallelTerminals(List.of(conn), offFaceFixtureNodes(), false).count());
    }

    @Test
    public void offFaceParallel_absentNode_terminalSkipped() {
        // Without the element rect there is no face to measure against → the detector is a no-op.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{473, 110},
                        new double[]{650, 110}, new double[]{650, 325}), "", 1);
        assertEquals(0,
                assessor.countOffFaceParallelTerminals(List.of(conn), List.of(), false).count());
    }

    @Test
    public void offFaceParallel_collectFalse_returnsEmptyImmutableViolatorSet() {
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{473, 110},
                        new double[]{650, 110}, new double[]{650, 325}), "", 1);
        assertTrue(assessor.countOffFaceParallelTerminals(List.of(conn), offFaceFixtureNodes(), false)
                .violatorIds().isEmpty());
    }

    @Test
    public void offFaceParallel_assessLevel_surfacesCountCoverageAndViolator_ratingUntouched() {
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{473, 110},
                        new double[]{650, 110}, new double[]{650, 325}), "", 1);
        LayoutAssessmentResult result =
                assessor.assess(offFaceFixtureNodes(), List.of(conn), true);
        assertEquals("assess surfaces the off-face hug", 1, result.offFaceParallelTerminalCount());
        assertEquals("one description on the result", 1,
                result.offFaceParallelTerminalDescriptions().size());
        assertEquals("the dimension has a detector → checked",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("offFaceParallelTerminals"));
        assertTrue("violator surfaced under the new key",
                result.violatorIds().get("offFaceParallelTerminals").contains("c1"));
        // Measurement-only: the rating-bearing terminal metric is untouched (disjoint geometry).
        assertEquals("rating-bearing nonOrthogonalTerminalCount unchanged by the new informational metric",
                0, result.nonOrthogonalTerminalCount());
    }

    @Test
    public void offFaceParallel_terminalsOnlyEgressClearance_clearsTheHug_oracleCrossCheck() {
        // ORACLE CROSS-CHECK: the terminals-only egress clearance produces exactly the geometry the
        // off-face detector reads as clean. A terminals-only rectified path whose source exit hugs the
        // departed bottom face (count ≥ 1) is lifted by RoutingPipeline.terminalsOnlyEnforceEgressClearance
        // and the SAME detector then reads 0 on the lifted geometry.
        RoutingRect src = new RoutingRect(400, 50, 100, 59, "src");  // bottom face y=109, centerX 450
        RoutingRect tgt = new RoutingRect(620, 300, 60, 50, "tgt");  // center (650,325)
        List<AbsoluteBendpointDto> rectified = List.of(
                new AbsoluteBendpointDto(450, 110),   // vertical egress from centerX, 1px below face
                new AbsoluteBendpointDto(650, 110));  // horizontal trunk hugging the bottom face

        AssessmentConnection before = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{450, 110},
                        new double[]{650, 110}, new double[]{650, 325}), "", 1);
        assertEquals("pre-clearance terminals-only hug is flagged", 1,
                assessor.countOffFaceParallelTerminals(List.of(before), offFaceFixtureNodes(), false).count());

        List<AbsoluteBendpointDto> cleared =
                RoutingPipeline.terminalsOnlyEnforceEgressClearance(src, tgt, rectified);

        AssessmentConnection after = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79},
                        new double[]{cleared.get(0).x(), cleared.get(0).y()},
                        new double[]{cleared.get(1).x(), cleared.get(1).y()},
                        new double[]{650, 325}), "", 1);
        assertEquals("post-clearance the same detector reads the lifted route as clean", 0,
                assessor.countOffFaceParallelTerminals(List.of(after), offFaceFixtureNodes(), false).count());
    }

    // ---- Off-face remedy honesty: layout remedy on a corridor too tight for a healthy lift ----
    // The router only lifts a hug perpendicular off the face when doing so keeps a healthy
    // parallel-connection gap. When the corridor beside the hug is narrower than that floor
    // (HEALTHY_PARALLEL_GAP_PX), the router (correctly) declines, so re-routing changes nothing and
    // the honest remedy is layout — widen the corridor. This is a DESCRIPTION-only branch: count,
    // violators, coverage and rating are all unaffected (asserted below).

    // Bottom-face hug identical to offFaceParallel_hugBelowDepartedBottomFace_flagsOnce (BP y=110,
    // horizontal trunk x 473..650), so only the corridor beside it varies across these tests.
    private AssessmentConnection tightCorridorHug() {
        return new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 79}, new double[]{473, 110},
                        new double[]{650, 110}, new double[]{650, 325}), "", 1);
    }

    @Test
    public void offFaceParallel_tightCorridorByObstacle_prescribesLayoutRemedy() {
        // An element sits 9px below the departed bottom face (top edge y=118, face y=109), overlapping
        // the trunk's x-span — under the 15px a healthy lift needs. The router would decline the lift.
        List<AssessmentNode> nodes = List.of(
                node("src", 400, 50, 100, 59),      // bottom face = 109
                node("tgt", 620, 300, 60, 50),
                node("block", 500, 118, 80, 40));   // top edge 118 → 9px clearance on the push side
        LayoutQualityAssessor.OffFaceParallelTerminalResult result =
                assessor.countOffFaceParallelTerminals(List.of(tightCorridorHug()), nodes, true);
        assertEquals("the hug is still detected — only the remedy text changes", 1, result.count());
        String desc = result.descriptions().get(0);
        assertTrue("a tight corridor gets the LAYOUT remedy (widen the corridor): " + desc,
                desc.contains("widen the corridor"));
        assertFalse("a tight corridor is confidently layout-bound — not the deferred auto-route hedge: "
                + desc, desc.contains("run auto-route-connections"));
    }

    @Test
    public void offFaceParallel_tightCorridorByNeighbourRun_prescribesLayoutRemedy() {
        // No near obstacle; a NEIGHBOURING connection run (horizontal, y=118) sits 9px below the face
        // and overlaps the trunk's span. Exercises the co-axial connection-run clearance scan (the
        // quantity the router's parallel-gap floor actually protects). The 2-point neighbour has no
        // exterior trunk, so it is never itself flagged.
        AssessmentConnection neighbour = new AssessmentConnection("n2", "s2", "t2",
                List.of(new double[]{500, 118}, new double[]{600, 118}), "", 1);
        LayoutQualityAssessor.OffFaceParallelTerminalResult result =
                assessor.countOffFaceParallelTerminals(
                        List.of(tightCorridorHug(), neighbour), offFaceFixtureNodes(), true);
        assertEquals("only the hug is flagged; the 2-point neighbour has no trunk", 1, result.count());
        String desc = result.descriptions().get(0);
        assertTrue("a run 9px away makes the corridor layout-bound: " + desc,
                desc.contains("widen the corridor"));
        assertFalse("confidently layout-bound — not the deferred auto-route hedge: " + desc,
                desc.contains("run auto-route-connections"));
    }

    @Test
    public void offFaceParallel_wideCorridor_singleVarOff_defersToAutoRoute() {
        // SINGLE-VARIABLE control against offFaceParallel_tightCorridorByObstacle: only the block's
        // top edge moves (y 118 → 130 → 21px clearance ≥ 15). With ≥15px local room the assessor
        // cannot know offline whether the router will keep or (for a view-wide reason) decline the
        // lift, so it defers to auto-route-connections rather than over-promising a re-route.
        List<AssessmentNode> nodes = List.of(
                node("src", 400, 50, 100, 59),
                node("tgt", 620, 300, 60, 50),
                node("block", 500, 130, 80, 40));   // top edge 130 → 21px clearance
        String desc = assessor.countOffFaceParallelTerminals(List.of(tightCorridorHug()), nodes, true)
                .descriptions().get(0);
        assertTrue("a wide corridor defers to auto-route (the authoritative layout-bound signal): "
                + desc, desc.contains("run auto-route-connections"));
        assertTrue("and cross-references the auto-route warning code: " + desc,
                desc.contains("EGRESS_LIFT_LAYOUT_BOUND"));
        assertFalse("a wide corridor must not confidently prescribe the layout remedy: " + desc,
                desc.contains("widen the corridor"));
    }

    @Test
    public void offFaceParallel_clearanceExactlyAtFloor_defersToAutoRoute() {
        // Boundary: clearance == HEALTHY_PARALLEL_GAP_PX (block top edge 124 → exactly 15px) is NOT
        // below the floor, so it takes the ≥15 branch → defer to auto-route (not the confident layout
        // remedy).
        List<AssessmentNode> nodes = List.of(
                node("src", 400, 50, 100, 59),
                node("tgt", 620, 300, 60, 50),
                node("block", 500, 124, 80, 40));   // 124 - 109 = 15.0 == floor
        String desc = assessor.countOffFaceParallelTerminals(List.of(tightCorridorHug()), nodes, true)
                .descriptions().get(0);
        assertTrue("clearance == floor takes the deferred auto-route branch: " + desc,
                desc.contains("run auto-route-connections"));
        assertFalse("clearance == floor is not confidently layout-bound: " + desc,
                desc.contains("widen the corridor"));
    }

    @Test
    public void offFaceParallel_clearanceJustBelowFloor_prescribesLayoutRemedy() {
        // Boundary: clearance just under the floor (block top edge 123 → 14px < 15) flips to layout.
        List<AssessmentNode> nodes = List.of(
                node("src", 400, 50, 100, 59),
                node("tgt", 620, 300, 60, 50),
                node("block", 500, 123, 80, 40));   // 123 - 109 = 14.0 < floor
        String desc = assessor.countOffFaceParallelTerminals(List.of(tightCorridorHug()), nodes, true)
                .descriptions().get(0);
        assertTrue("clearance just below the floor flips to the layout remedy: " + desc,
                desc.contains("widen the corridor"));
    }

    @Test
    public void offFaceParallel_tightCorridorOnLeftFace_prescribesLayoutRemedy() {
        // Vertical-face coverage of the remedy branch (the live view-2.3 case is a LEFT-face hug). The
        // route exits 2px off the LEFT face (x=398, face x=400) and runs a vertical trunk hugging it;
        // an element sits 9px to the LEFT (right edge x=391) overlapping the trunk's y-span.
        List<AssessmentNode> nodes = List.of(
                node("src", 400, 100, 100, 80),     // LEFT face = 400, y-range 100..180
                node("tgt", 372, 400, 60, 50),      // far below — its terminal is not a hug
                node("block", 300, 200, 91, 40));   // right edge 391 → 9px left of the departed face
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 140}, new double[]{398, 150},
                        new double[]{398, 250}, new double[]{402, 425}), "", 1);
        LayoutQualityAssessor.OffFaceParallelTerminalResult result =
                assessor.countOffFaceParallelTerminals(List.of(conn), nodes, true);
        assertEquals("the LEFT-face hug is detected", 1, result.count());
        String desc = result.descriptions().get(0);
        assertTrue("the description names the departed LEFT face: " + desc, desc.contains("LEFT"));
        assertTrue("a 9px corridor on a LEFT-face hug gets the layout remedy: " + desc,
                desc.contains("widen the corridor"));
        assertFalse("confidently layout-bound — not the deferred auto-route hedge: " + desc,
                desc.contains("run auto-route-connections"));
        assertFalse("a single connection on the face is not contested — no spread clause: " + desc,
                desc.contains("spread them across the element's other faces"));
    }

    @Test
    public void offFaceParallel_contestedHubFace_remedyNamesSpreadingConnections() {
        // Two connections exit src's LEFT face — c1 hugs it in a 9px-tight corridor, c2 exits cleanly
        // perpendicular. The SHARED face is a contested hub face: widening alone would only re-crowd it
        // (as the live view-2.3 confirmation showed), so the remedy additionally names spreading the
        // connections across the element's other faces.
        List<AssessmentNode> nodes = List.of(
                node("src", 400, 100, 100, 80),     // LEFT face = 400
                node("tgt", 372, 400, 60, 50),
                node("block", 300, 200, 91, 40));   // right edge 391 → 9px tight corridor for c1
        AssessmentConnection c1 = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{450, 140}, new double[]{398, 150},
                        new double[]{398, 250}, new double[]{402, 425}), "", 1);   // LEFT-face hug
        AssessmentConnection c2 = new AssessmentConnection("c2", "src", "far",
                List.of(new double[]{450, 120}, new double[]{399, 120},
                        new double[]{350, 120}, new double[]{300, 120}), "", 1);   // clean LEFT exit, not a hug
        LayoutQualityAssessor.OffFaceParallelTerminalResult result =
                assessor.countOffFaceParallelTerminals(List.of(c1, c2), nodes, true);
        assertEquals("only the hugging connection is flagged (c2 exits perpendicular)", 1, result.count());
        String desc = result.descriptions().get(0);
        assertTrue("still the confident layout remedy (tight local corridor): " + desc,
                desc.contains("widen the corridor"));
        assertTrue("contested face → remedy also names spreading the connections: " + desc,
                desc.contains("spread them across the element's other faces"));
    }

    @Test
    public void offFaceParallel_obstacleFlushOnFaceLine_isZeroClearanceLayoutRemedy() {
        // A neighbour flush on the departed face line (block top edge == face y=109 → 0px corridor) is
        // the tightest possible corridor. It must be treated as clearance 0 (layout remedy), NOT
        // silently ignored as "no neighbour" (which would misreport the corridor as open). Pins the
        // >= 0 boundary in offFaceLiftClearance.
        List<AssessmentNode> nodes = List.of(
                node("src", 400, 50, 100, 59),      // bottom face = 109
                node("tgt", 620, 300, 60, 50),
                node("block", 500, 109, 80, 40));   // top edge exactly on the face line → 0px
        String desc = assessor.countOffFaceParallelTerminals(List.of(tightCorridorHug()), nodes, true)
                .descriptions().get(0);
        assertTrue("a 0px (flush) corridor is layout-bound: " + desc, desc.contains("widen the corridor"));
        assertTrue("the reported clearance is 0.0px: " + desc, desc.contains("0.0px wide"));
    }

    @Test
    public void offFaceParallel_remedyBranch_ratingByteIdentical_informationalOnly() {
        // The remedy branch touches only the description string. A tight vs wide corridor produced by
        // moving a rating-neutral neighbouring run (endpoints are not nodes → no terminal/crossing
        // contribution; parallelConnectionGap is informational) must leave the rating identical while
        // the description differs. Guards the "informational-only" contract (no rating regression).
        AssessmentConnection runTight = new AssessmentConnection("n2", "s2", "t2",
                List.of(new double[]{500, 118}, new double[]{600, 118}), "", 1);   // 9px → layout
        AssessmentConnection runWide = new AssessmentConnection("n2", "s2", "t2",
                List.of(new double[]{500, 130}, new double[]{600, 130}), "", 1);   // 21px → routing
        LayoutAssessmentResult tight =
                assessor.assess(offFaceFixtureNodes(), List.of(tightCorridorHug(), runTight), true);
        LayoutAssessmentResult wide =
                assessor.assess(offFaceFixtureNodes(), List.of(tightCorridorHug(), runWide), true);

        assertEquals("both corridors flag the same single hug", 1, tight.offFaceParallelTerminalCount());
        assertEquals("both corridors flag the same single hug", 1, wide.offFaceParallelTerminalCount());
        assertTrue("tight corridor description is the confident layout remedy",
                tight.offFaceParallelTerminalDescriptions().get(0).contains("widen the corridor"));
        assertTrue("wide corridor description defers to auto-route",
                wide.offFaceParallelTerminalDescriptions().get(0).contains("run auto-route-connections"));
        assertEquals("overallRating byte-identical (remedy text is informational)",
                tight.overallRating(), wide.overallRating());
        assertEquals("layoutRating byte-identical", tight.layoutRating(), wide.layoutRating());
        assertEquals("routingRating byte-identical", tight.routingRating(), wide.routingRating());
        assertEquals("ratingBreakdown byte-identical", tight.ratingBreakdown(), wide.ratingBreakdown());
    }

    // ---- Coincident same-face port detection (informational; no rating impact) ----

    @Test
    public void coincidentFacePorts_twoConnsSameLeftSlot_flagsFaceOnce_namesPair() {
        // Hub LEFT face (x=200, y 100..300); two spokes both anchor at slot y=200 → one perimeter
        // point carries two ports. This face has only 2 connections, BELOW M5's four-connection guard,
        // so M5 never scores it — this detector is what surfaces the collision.
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode p1 = node("p1", 0, 150, 50, 30);
        AssessmentNode p2 = node("p2", 0, 250, 50, 30);
        AssessmentConnection c1 = connToHubLeft("c1", p1, hub, 200);
        AssessmentConnection c2 = connToHubLeft("c2", p2, hub, 200);
        LayoutQualityAssessor.CoincidentFacePortResult result =
                assessor.countCoincidentFacePorts(List.of(c1, c2), List.of(hub, p1, p2), true);
        assertEquals("one colliding face", 1, result.count());
        assertEquals("one description for the colliding face", 1, result.descriptions().size());
        assertTrue("description names the LEFT face", result.descriptions().get(0).contains("LEFT"));
        assertTrue("description names both colliding connections",
                result.descriptions().get(0).contains("c1") && result.descriptions().get(0).contains("c2"));
        assertTrue("violators are the two colliding connection ids",
                result.violatorIds().contains("c1") && result.violatorIds().contains("c2"));
    }

    @Test
    public void coincidentFacePorts_twoConnsDistinctBottomSlots_doesNotFlag() {
        // Hub BOTTOM face (y=200); the two spokes anchor at DISTINCT slots x=150 and x=250 → spread,
        // no collision — the healthy-hub control that must not over-flag.
        AssessmentNode hub = node("hub", 100, 100, 200, 100);
        AssessmentNode p1 = node("p1", 0, 400, 50, 30);
        AssessmentNode p2 = node("p2", 300, 400, 50, 30);
        AssessmentConnection c1 = connFromHubBottom("c1", hub, p1, 150);
        AssessmentConnection c2 = connFromHubBottom("c2", hub, p2, 250);
        assertEquals("distinct bottom-face slots do not collide", 0,
                assessor.countCoincidentFacePorts(List.of(c1, c2), List.of(hub, p1, p2), false).count());
    }

    @Test
    public void coincidentFacePorts_singleVarSlotSeparation_flipsCountToZero() {
        // SINGLE-VARIABLE control: two LEFT-face spokes coincident at y=200 (count 1); move ONE spoke's
        // slot to y=250 (> HUB_PORT_SLOT_TOLERANCE_PX) and the collision — and the count — disappears.
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode p1 = node("p1", 0, 150, 50, 30);
        AssessmentNode p2 = node("p2", 0, 250, 50, 30);
        assertEquals("coincident at y=200", 1, assessor.countCoincidentFacePorts(
                List.of(connToHubLeft("c1", p1, hub, 200), connToHubLeft("c2", p2, hub, 200)),
                List.of(hub, p1, p2), false).count());
        assertEquals("separated to y=250 → no collision", 0, assessor.countCoincidentFacePorts(
                List.of(connToHubLeft("c1", p1, hub, 200), connToHubLeft("c2", p2, hub, 250)),
                List.of(hub, p1, p2), false).count());
    }

    @Test
    public void coincidentFacePorts_fourConnFaceWithCollision_stillCounted_intentionalM5Overlap() {
        // A face with four coincident LEFT-face spokes is ALSO an M5 hub face (quality 4/1 = 0.25).
        // The enumeration deliberately overlaps M5: M5 rates the ratio, this counts the colliding face.
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode p1 = node("p1", 0, 110, 50, 20);
        AssessmentNode p2 = node("p2", 0, 160, 50, 20);
        AssessmentNode p3 = node("p3", 0, 230, 50, 20);
        AssessmentNode p4 = node("p4", 0, 280, 50, 20);
        List<AssessmentConnection> conns = List.of(
                connToHubLeft("c1", p1, hub, 200), connToHubLeft("c2", p2, hub, 200),
                connToHubLeft("c3", p3, hub, 200), connToHubLeft("c4", p4, hub, 200));
        List<AssessmentNode> nodes = List.of(hub, p1, p2, p3, p4);
        assertEquals("the coincident face is counted once", 1,
                assessor.countCoincidentFacePorts(conns, nodes, false).count());
        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);
        assertEquals("M5 rates the four-way collision at 0.25", 0.25, result.hubPortQualityScore(), 1e-9);
        assertEquals("and the informational count also flags the face", 1, result.coincidentFacePortCount());
    }

    @Test
    public void coincidentFacePorts_singleConnectionFace_returnsZeroAndEmptyDescriptions() {
        // A face with only ONE terminal cannot collide → sentinel/empty (safety path).
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode p1 = node("p1", 0, 150, 50, 30);
        LayoutQualityAssessor.CoincidentFacePortResult result = assessor.countCoincidentFacePorts(
                List.of(connToHubLeft("c1", p1, hub, 200)), List.of(hub, p1), true);
        assertEquals(0, result.count());
        assertTrue(result.descriptions().isEmpty());
        assertTrue(result.violatorIds().isEmpty());
    }

    @Test
    public void coincidentFacePorts_collectFalse_returnsEmptyImmutableViolatorSet() {
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode p1 = node("p1", 0, 150, 50, 30);
        AssessmentNode p2 = node("p2", 0, 250, 50, 30);
        Set<String> violators = assessor.countCoincidentFacePorts(
                List.of(connToHubLeft("c1", p1, hub, 200), connToHubLeft("c2", p2, hub, 200)),
                List.of(hub, p1, p2), false).violatorIds();
        assertTrue(violators.isEmpty());
        assertThrows("collectFalse must return an immutable empty set",
                UnsupportedOperationException.class, () -> violators.add("x"));
    }

    @Test
    public void coincidentFacePorts_chainedNearTolerance_namesOnlyTrueCluster_notTransitiveClosure() {
        // Slots 200, 200.6, 201.1 on the LEFT face with a 1.0px tolerance: 200↔200.6 (0.6) and
        // 200.6↔201.1 (0.5) overlap, but 200↔201.1 (1.1) do NOT. Clustering from the cluster's first
        // slot puts {200, 200.6} together (c1, c2) and leaves 201.1 (c3) as its own distinct port. The
        // colliding set must be exactly {c1, c2}; c3 must not be named (an all-pairs union would wrongly
        // pull it in via the chain).
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode p1 = node("p1", 0, 130, 50, 20);
        AssessmentNode p2 = node("p2", 0, 180, 50, 20);
        AssessmentNode p3 = node("p3", 0, 240, 50, 20);
        LayoutQualityAssessor.CoincidentFacePortResult result = assessor.countCoincidentFacePorts(
                List.of(connToHubLeft("c1", p1, hub, 200), connToHubLeft("c2", p2, hub, 200.6),
                        connToHubLeft("c3", p3, hub, 201.1)),
                List.of(hub, p1, p2, p3), true);
        assertEquals("the face still has a collision", 1, result.count());
        assertTrue("the true cluster's pair is named",
                result.violatorIds().contains("c1") && result.violatorIds().contains("c2"));
        assertFalse("the non-overlapping third port is NOT named (no transitive closure)",
                result.violatorIds().contains("c3"));
        assertFalse("nor does the description name it", result.descriptions().get(0).contains("c3"));
        assertTrue("the description reports two distinct ports",
                result.descriptions().get(0).contains("2 distinct"));
    }

    @Test
    public void coincidentFacePorts_selfLoopSameConnection_notCountedAsCollision() {
        // A self-association (source == target) registers its two terminals on ONE face at ONE slot,
        // both carrying the same connection id. That is one connection, not two contending ports, so it
        // must NOT be counted (requiring two DISTINCT connections per cluster).
        AssessmentNode hub = node("hub", 200, 100, 100, 200); // center (250, 200), LEFT x=200
        AssessmentConnection selfLoop = new AssessmentConnection("c1", "hub", "hub",
                List.of(new double[]{250, 200}, new double[]{200, 200}, new double[]{250, 200}), "", 1);
        LayoutQualityAssessor.CoincidentFacePortResult result =
                assessor.countCoincidentFacePorts(List.of(selfLoop), List.of(hub), true);
        assertEquals("one connection cannot collide with itself", 0, result.count());
        assertTrue(result.descriptions().isEmpty());
        assertTrue(result.violatorIds().isEmpty());
    }

    @Test
    public void coincidentFacePorts_exactlyAtTolerance_collides_justOverDoesNot() {
        // Boundary pin on HUB_PORT_SLOT_TOLERANCE_PX (1.0px): a separation of exactly the tolerance is a
        // collision (inclusive <=); a hair beyond it is not.
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode p1 = node("p1", 0, 150, 50, 30);
        AssessmentNode p2 = node("p2", 0, 250, 50, 30);
        assertEquals("separation == tolerance collides", 1, assessor.countCoincidentFacePorts(
                List.of(connToHubLeft("c1", p1, hub, 200), connToHubLeft("c2", p2, hub, 201.0)),
                List.of(hub, p1, p2), false).count());
        assertEquals("separation just over tolerance does not", 0, assessor.countCoincidentFacePorts(
                List.of(connToHubLeft("c1", p1, hub, 200), connToHubLeft("c2", p2, hub, 201.01)),
                List.of(hub, p1, p2), false).count());
    }

    @Test
    public void coincidentFacePorts_assessLevel_surfacesCountCoverageViolator_ratingUntouched() {
        // Reproduces the live view-2.3 shape: a hub LEFT face with two coincident ports (below M5's
        // guard) AND a hub BOTTOM face with two SPREAD ports (must not flag).
        AssessmentNode hub = node("hub", 200, 100, 100, 200); // LEFT x=200 y100..300; BOTTOM y=300
        AssessmentNode left1 = node("l1", 0, 150, 50, 30);
        AssessmentNode left2 = node("l2", 0, 250, 50, 30);
        AssessmentNode bot1 = node("b1", 180, 450, 40, 30);
        AssessmentNode bot2 = node("b2", 280, 450, 40, 30);
        AssessmentConnection cl1 = connToHubLeft("cl1", left1, hub, 200);   // LEFT slot 200
        AssessmentConnection cl2 = connToHubLeft("cl2", left2, hub, 200);   // LEFT slot 200 (coincident)
        AssessmentConnection cb1 = connFromHubBottom("cb1", hub, bot1, 220); // BOTTOM slot 220
        AssessmentConnection cb2 = connFromHubBottom("cb2", hub, bot2, 280); // BOTTOM slot 280 (spread)
        LayoutAssessmentResult result = assessor.assess(
                List.of(hub, left1, left2, bot1, bot2), List.of(cl1, cl2, cb1, cb2), true);
        assertEquals("only the LEFT face collides", 1, result.coincidentFacePortCount());
        assertEquals("one description", 1, result.coincidentFacePortDescriptions().size());
        assertTrue("description names the LEFT face and its pair",
                result.coincidentFacePortDescriptions().get(0).contains("LEFT")
                        && result.coincidentFacePortDescriptions().get(0).contains("cl1")
                        && result.coincidentFacePortDescriptions().get(0).contains("cl2"));
        assertEquals("dimension has a detector → checked", LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("coincidentFacePorts"));
        assertTrue("colliding ids under the new violator key",
                result.violatorIds().get("coincidentFacePorts").contains("cl1")
                        && result.violatorIds().get("coincidentFacePorts").contains("cl2"));
        // Informational-only: the rating-bearing M5 metric is BLIND to the 2-connection LEFT face
        // (below its four-connection guard) → hubPortQualityScore stays a vacuous 1.0, unchanged, and
        // no coincidentFacePorts entry enters the rating breakdown.
        assertEquals("M5 hubPortQualityScore untouched (2-conn face below its guard)",
                1.0, result.hubPortQualityScore(), 1e-9);
        assertFalse("coincident ports never enter the rating breakdown",
                result.ratingBreakdown().containsKey("coincidentFacePorts"));
    }

    // ---- Non-orthogonal interior segment RATING tests (ratio buckets mirror the terminal sibling) ----

    @Test
    public void nonOrthInterior_zeroShouldRatePass() {
        // Zero interior segments → the entry passes, exactly like the terminal sibling at zero.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0);
        assertEquals("pass", result.breakdown().get("nonOrthogonalInteriorSegments"));
    }

    @Test
    public void nonOrthInterior_lowDensityShouldRateGood() {
        // 1 interior / 10 connections = 10% → exactly at NON_ORTH_RATIO_GOOD boundary (≤) → "good".
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 1);
        assertEquals("good", result.breakdown().get("nonOrthogonalInteriorSegments"));
    }

    @Test
    public void nonOrthInterior_midDensityShouldRateFair() {
        // 5 interior / 20 connections = 25% → between 10% and 30% → "fair".
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 20, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 5);
        assertEquals("fair", result.breakdown().get("nonOrthogonalInteriorSegments"));
    }

    @Test
    public void nonOrthInterior_exactlyThirtyPercentShouldRateFair() {
        // 6 interior / 20 connections = 30% → exactly at NON_ORTH_RATIO_FAIR boundary (≤) → "fair".
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 20, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 6);
        assertEquals("fair", result.breakdown().get("nonOrthogonalInteriorSegments"));
    }

    @Test
    public void nonOrthInterior_highDensityShouldRatePoor() {
        // 4 interior / 10 connections = 40% → above NON_ORTH_RATIO_FAIR → "poor".
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 4);
        assertEquals("poor", result.breakdown().get("nonOrthogonalInteriorSegments"));
    }

    @Test
    public void nonOrthInterior_zeroConnectionsFallbackShouldRateFair() {
        // Non-zero interior count but zero connections (edge case) → "fair" (mirrors terminal).
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 0, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 3);
        assertEquals("fair", result.breakdown().get("nonOrthogonalInteriorSegments"));
    }

    @Test
    public void nonOrthInterior_disjointCap_bothDiagonalEqualsOneAtSameRatio() {
        // Routing tier disjoint-cap invariant: the tier combines members by Math.max, not by sum.
        // A connection diagonal at BOTH a terminal and an interior segment must rate exactly like a
        // terminal-only (or interior-only) view at the same ratio — no additive double demotion.
        // Ratio 3/10 = 30% → each entry rates "fair" → routing tier-2 = fair in all three cases.
        LayoutQualityAssessor.RatingResult terminalOnly = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 3, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0);
        LayoutQualityAssessor.RatingResult interiorOnly = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 3);
        LayoutQualityAssessor.RatingResult both = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 3, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 3);
        assertEquals("fair", terminalOnly.routingRating());
        assertEquals("fair", interiorOnly.routingRating());
        assertEquals("both-diagonal must equal terminal-only at the same ratio (Math.max, no sum)",
                terminalOnly.routingRating(), both.routingRating());
        assertEquals(interiorOnly.routingRating(), both.routingRating());
    }

    @Test
    public void nonOrthInterior_terminalSiblingByteIdentical_whenInteriorZero() {
        // Regression guard: with zero interior segments the terminal sibling and the routing rating
        // are exactly what they were before the interior entry existed. 3/10 terminals = 30% →
        // terminal "fair", interior "pass", routing capped at fair by the terminal entry alone.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 3, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0);
        assertEquals("fair", result.breakdown().get("nonOrthogonalTerminals"));
        assertEquals("pass", result.breakdown().get("nonOrthogonalInteriorSegments"));
        assertEquals("fair", result.routingRating());
    }

    @Test
    public void computeRatingWithBreakdown_19ArgOverload_defaultsInteriorPassByteIdentical() {
        // The 19-arg overload must delegate with interiorCount=0 → entry "pass", rating unchanged
        // from the 20-arg form forwarding 0 (the hub-crowding overload precedent).
        LayoutQualityAssessor.RatingResult viaNineteen = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false);
        LayoutQualityAssessor.RatingResult viaTwentyZero = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0);
        assertEquals(viaTwentyZero.rating(), viaNineteen.rating());
        assertEquals(viaTwentyZero.breakdown(), viaNineteen.breakdown());
        assertEquals("pass", viaNineteen.breakdown().get("nonOrthogonalInteriorSegments"));
    }

    // ---- Parent label obscured tests ----

    @Test
    public void detectParentLabelObscured_shouldNotDetect_whenChildBelowLabel() {
        // Parent at y=0, label needs 20px. Child at y=30 (relative) = y=30 (absolute) — below label.
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("parent", 0, 0, 200, 150, null, true, false, "Parent Group", 80.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("child", 10, 30, 80, 40, "parent", false, false, "Child", 40.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ParentLabelObscuredResult result = assessor.detectParentLabelObscuredByChild(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectParentLabelObscured_shouldDetect_whenChildOverlapsLabel() {
        // Parent at y=0, label needs 20px. Child at y=10 (absolute) — inside label area.
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("parent", 0, 0, 200, 150, null, true, false, "Parent Group", 80.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("child", 10, 10, 80, 40, "parent", false, false, "Child", 40.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ParentLabelObscuredResult result = assessor.detectParentLabelObscuredByChild(nodes);
        assertEquals(1, result.count());
        assertEquals(1, result.descriptions().size());
        assertTrue(result.descriptions().get(0).contains("Parent Group"));
    }

    @Test
    public void detectParentLabelObscured_shouldNotDetect_whenNoChildren() {
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("parent", 0, 0, 200, 150, null, true, false, "Parent Group", 80.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ParentLabelObscuredResult result = assessor.detectParentLabelObscuredByChild(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectParentLabelObscured_shouldCountOnlyObscuredParents() {
        // Two parents: one with child below label, one with child in label
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("p1", 0, 0, 200, 150, null, true, false, "OK Parent", 80.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("c1", 10, 30, 80, 40, "p1", false, false, "OK Child", 40.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("p2", 300, 0, 200, 150, null, true, false, "Bad Parent", 80.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("c2", 310, 5, 80, 40, "p2", false, false, "Bad Child", 40.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ParentLabelObscuredResult result = assessor.detectParentLabelObscuredByChild(nodes);
        assertEquals(1, result.count());
        assertTrue(result.descriptions().get(0).contains("Bad Parent"));
    }

    @Test
    public void detectParentLabelObscured_regressionGuard_backlogViewTitleNoteAutosize_AC13() {
        // Regression-guard pin.
        //
        // SCOPE (acknowledged per review L1): this is an ASSESSOR-UNIT pin — it verifies
        // that detectParentLabelObscuredByChild does not regress on the canonical "default-
        // sized group with child below label band" shape that the autosize fix preserves.
        // It is NOT an end-to-end test (no accessor.addGroupToView call). The end-to-end
        // height pin lives in ArchiModelAccessorImplTest.addGroupToView_shouldKeepDefault
        // Height_whenShortLabel_AC15, which guarantees the resolved-height stays at 200 for
        // short labels — combined with this assessor-unit pin, the regression-guard
        // intent is covered transitively.
        //
        // The 200-px default height continues to fit the short label (short-circuit),
        // and the child positioned at y=30 (relative-to-parent, > label-band height) does
        // not overlap the label area.
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("default-group", 0, 0, 300, 200, null, true, false,
                        "Banking Products", 80.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("child-below-label", 10, 30, 120, 55, "default-group",
                        false, false, "Customer", 40.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ParentLabelObscuredResult result =
                assessor.detectParentLabelObscuredByChild(nodes);
        assertEquals("AC-13 regression: default-sized group with short label + child below "
                + "label band must remain at 0 obscured (pass rating).",
                0, result.count());
    }

    // ---- Image sibling overlap tests ----

    @Test
    public void detectImageSiblingOverlap_shouldNotDetect_whenNoSiblingOverlap() {
        // Element with image at bottom-left, sibling far away
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("e1", 0, 0, 120, 55, null, false, false, "ImgElem", 60.0, "img/icon.png", "bottom-left", 0.0, 0.0, 0.0),
                new AssessmentNode("e2", 200, 0, 120, 55, null, false, false, "Other", 40.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ImageSiblingOverlapResult result = assessor.detectImageSiblingOverlap(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectImageSiblingOverlap_shouldDetect_whenFillImageOverlappedBySibling() {
        // Element with fill image, sibling overlaps its bounds
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("e1", 0, 0, 120, 55, null, false, false, "FillImg", 60.0, "img/bg.png", "fill", 0.0, 0.0, 0.0),
                new AssessmentNode("e2", 50, 10, 120, 55, null, false, false, "Overlapper", 60.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ImageSiblingOverlapResult result = assessor.detectImageSiblingOverlap(nodes);
        assertEquals(1, result.count());
        assertTrue(result.descriptions().get(0).contains("FillImg"));
        assertTrue(result.descriptions().get(0).contains("fill"));
    }

    @Test
    public void detectImageSiblingOverlap_shouldNotDetect_whenBottomLeftNotOverlapped() {
        // Element with bottom-left image (24x24 at bottom-left corner), sibling only overlaps top-right area
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("e1", 0, 0, 120, 55, null, false, false, "ImgElem", 60.0, "img/icon.png", "bottom-left", 0.0, 0.0, 0.0),
                new AssessmentNode("e2", 100, 0, 120, 30, null, false, false, "TopOnly", 40.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ImageSiblingOverlapResult result = assessor.detectImageSiblingOverlap(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectImageSiblingOverlap_shouldSkipElementsWithoutImage() {
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("e1", 0, 0, 120, 55, null, false, false, "NoImg", 60.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("e2", 50, 10, 120, 55, null, false, false, "Other", 60.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ImageSiblingOverlapResult result = assessor.detectImageSiblingOverlap(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectImageSiblingOverlap_shouldUseNaturalDimensions_whenProvided() {
        // e1 carries a top-right icon. At its true 80x80 size the icon spans
        // x in [40,120], y in [0,80] and reaches the neighbour e2 at (50,0,30,30).
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("e1", 0, 0, 120, 55, null, false, false, "Icon", 0.0, "img/icon.png", "top-right", 0.0, 80.0, 80.0),
                new AssessmentNode("e2", 50, 0, 30, 30, null, false, false, "Neighbour", 0.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ImageSiblingOverlapResult result = assessor.detectImageSiblingOverlap(nodes);
        assertEquals(1, result.count());
        assertTrue(result.descriptions().get(0).contains("Icon"));
    }

    @Test
    public void detectImageSiblingOverlap_shouldFallBackToFixedIconSize_whenNaturalDimensionsAbsent() {
        // Identical layout, but no natural dimensions → the icon is sized at the fixed
        // 24px (x in [96,120]), which does NOT reach e2's box [50,80] → no overlap.
        // This is the headless/read-failure fallback that keeps behaviour safe.
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("e1", 0, 0, 120, 55, null, false, false, "Icon", 0.0, "img/icon.png", "top-right", 0.0, 0.0, 0.0),
                new AssessmentNode("e2", 50, 0, 30, 30, null, false, false, "Neighbour", 0.0, null, null, 0.0, 0.0, 0.0));
        LayoutQualityAssessor.ImageSiblingOverlapResult result = assessor.detectImageSiblingOverlap(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void detectImageSiblingOverlap_shouldNotFlagContainment_whenChildImageInsideParent() {
        // A child with a large fill image nested in a parent: the parent is NOT a
        // sibling (different parentId), so containment must not be flagged even with
        // a big image rect.
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("parent", 0, 0, 300, 200, null, false, false, "Parent", 0.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("child", 10, 10, 100, 80, "parent", false, false, "Child", 0.0, "img/bg.png", "fill", 0.0, 200.0, 200.0));
        LayoutQualityAssessor.ImageSiblingOverlapResult result = assessor.detectImageSiblingOverlap(nodes);
        assertEquals(0, result.count());
    }

    @Test
    public void assess_imageSiblingOverlap_shouldNotAffectRating() {
        // e1's true 200x200 top-right icon overlaps e2 → image-sibling overlap is
        // detected. The same geometry with no image must yield an identical rating:
        // image-sibling overlap is informational and never feeds the rating.
        List<AssessmentNode> withImageOverlap = List.of(
                new AssessmentNode("e1", 0, 0, 120, 55, null, false, false, "E1", 0.0, "img/icon.png", "top-right", 0.0, 200.0, 200.0),
                new AssessmentNode("e2", 100, 0, 120, 55, null, false, false, "E2", 0.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("e3", 0, 200, 120, 55, null, false, false, "E3", 0.0, null, null, 0.0, 0.0, 0.0));
        List<AssessmentNode> withoutImage = List.of(
                node("e1", 0, 0, 120, 55),
                node("e2", 100, 0, 120, 55),
                node("e3", 0, 200, 120, 55));

        LayoutAssessmentResult withFields = assessor.assess(withImageOverlap, List.of(), false);
        LayoutAssessmentResult withoutFields = assessor.assess(withoutImage, List.of(), false);

        // The overlap really was detected (otherwise the test proves nothing).
        assertTrue(withFields.imageSiblingOverlapCount() >= 1);
        // ...yet none of the rating outputs move.
        assertEquals(withoutFields.overallRating(), withFields.overallRating());
        assertEquals(withoutFields.layoutRating(), withFields.layoutRating());
        assertEquals(withoutFields.routingRating(), withFields.routingRating());
        assertEquals(withoutFields.ratingBreakdown(), withFields.ratingBreakdown());
    }

    // ---- Rating regression test (REPLACED under Assessor.Redesign M6, 2026-04-26) ----

    @Test
    @Ignore("Assessor.Redesign M6 (2026-04-26) — REPLACED by assess_withB53Fields_shouldChangeRating_underM6Promotions. "
            + "Pre-redesign B53 fields had no rating impact. Under M6 parentLabelObscuredCount is "
            + "promoted Tier 1L and labelTruncationCount is promoted Tier 2R, so the OPPOSITE "
            + "assertion is now correct.")
    public void assess_withB53Fields_shouldNotChangeRating() {
        // Same layout as existing tests, but with the styling/label fields populated — rating must be identical
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("a", 0, 0, 120, 55, null, false, false, "Very Long Name That Gets Truncated", 200.0, "img/bg.png", "fill", 0.0, 0.0, 0.0),
                new AssessmentNode("b", 200, 0, 120, 55, null, false, false, "B", 10.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("c", 0, 100, 120, 55, null, false, false, "C", 10.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("d", 200, 100, 120, 55, null, false, false, "D", 10.0, null, null, 0.0, 0.0, 0.0));

        // Same layout without the styling/label fields
        List<AssessmentNode> nodesWithout = List.of(
                node("a", 0, 0, 120, 55),
                node("b", 200, 0, 120, 55),
                node("c", 0, 100, 120, 55),
                node("d", 200, 100, 120, 55));

        LayoutAssessmentResult withFields = assessor.assess(nodes, List.of(), false);
        LayoutAssessmentResult withoutFields = assessor.assess(nodesWithout, List.of(), false);

        assertEquals("Rating must be identical regardless of B53 fields",
                withoutFields.overallRating(), withFields.overallRating());
        assertEquals("Rating breakdown must be identical",
                withoutFields.ratingBreakdown(), withFields.ratingBreakdown());
    }

    // ---- Helper methods ----

    /** Creates a top-level leaf element (non-group, no parent). */
    /**
     * Parity guard: the
     * terminals-only interior veto ({@link RoutingPipeline#terminalsOnlyTerminatesInside})
     * MUST flag exactly what assess-layout's M2 detector ({@link LayoutQualityAssessor#isStrictlyInside})
     * flags, so the router can never introduce an interior termination the assessor would
     * then report as a Tier-1 "poor". Probes span interior / on-perimeter / outside on every
     * face (integer coords, matching Archi's int-snapped bendpoints).
     */
    @Test
    public void interiorVetoPredicate_shouldMatchAssessorIsStrictlyInside() {
        RoutingRect rect = new RoutingRect(100, 100, 200, 120, "e"); // x[100,300] y[100,220]
        AssessmentNode elem = node("e", 100, 100, 200, 120);
        RoutingRect far = new RoutingRect(5000, 5000, 10, 10, "far"); // never contains a probe
        int[][] probes = {
                {200, 160},   // dead centre — strictly inside
                {101, 160},   // just inside left face
                {100, 160},   // on left face line — not strictly inside
                {300, 160},   // on right face line — not strictly inside
                {299, 160},   // just inside right face
                {50, 160},    // outside left
                {200, 100},   // on top face line — not strictly inside
                {200, 101},   // just inside top face
                {200, 219},   // just inside bottom face
                {200, 220},   // on bottom face line — not strictly inside
        };
        for (int[] p : probes) {
            boolean assessor = LayoutQualityAssessor.isStrictlyInside(
                    new double[]{p[0], p[1]}, elem);
            // Single-BP rectified list: first==last==p, checked vs source (rect) and far target.
            boolean router = RoutingPipeline.terminalsOnlyTerminatesInside(
                    rect, far, List.of(new AbsoluteBendpointDto(p[0], p[1])));
            assertEquals("Veto predicate must match assessor isStrictlyInside at ("
                    + p[0] + "," + p[1] + ")", assessor, router);
        }
    }

    /**
     * Parity guard for the zigzag-introduction veto: `RoutingPipeline.pathHasZigzag` MUST
     * agree with assess-layout's M3 (`countZigzags`/`isZigzagTriple`) on whether a path
     * contains a reversal — so the veto rejects exactly the zigzags the assessor would flag.
     */
    @Test
    public void zigzagVetoPredicate_shouldMatchAssessorCountZigzags() {
        List<List<double[]>> paths = List.of(
                List.of(new double[]{0, 50}, new double[]{100, 50},
                        new double[]{40, 50}, new double[]{60, 50}),   // shared-Y reversal
                List.of(new double[]{50, 0}, new double[]{50, 100},
                        new double[]{50, 40}, new double[]{50, 60}),   // shared-X reversal
                List.of(new double[]{0, 0}, new double[]{100, 0},
                        new double[]{100, 100}),                       // clean L
                List.of(new double[]{0, 0}, new double[]{50, 0},
                        new double[]{100, 0}));                        // collinear
        for (List<double[]> path : paths) {
            AssessmentConnection conn = new AssessmentConnection("c", "s", "t", path, "", 1);
            boolean assessor = this.assessor
                    .countZigzags(List.of(conn), Set.of(), false).count() > 0;
            boolean router = RoutingPipeline.pathHasZigzag(path);
            assertEquals("Veto predicate must match assessor countZigzags for path " + path,
                    assessor, router);
        }
    }

    private static AssessmentNode node(String id, double x, double y,
                                        double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    /** Creates a group container (top-level, no parent). */
    private static AssessmentNode group(String id, double x, double y,
                                         double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, true, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    /** Creates a leaf element flagged as an ArchiMate Junction (isJunction=true) — the only difference
     *  from {@link #node} is the junction flag, so a junction/box pair forms a single-variable contrast. */
    private static AssessmentNode junction(String id, double x, double y,
                                            double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0, true);
    }

    /** Creates a child element inside a group. */
    private static AssessmentNode childNode(String id, double x, double y,
                                             double w, double h, String parentId) {
        return new AssessmentNode(id, x, y, w, h, parentId, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    /** Creates a child group (nested group inside a parent group). */
    private static AssessmentNode childGroup(String id, double x, double y,
                                              double w, double h, String parentId) {
        return new AssessmentNode(id, x, y, w, h, parentId, true, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    /** Creates a top-level note. */
    private static AssessmentNode note(String id, double x, double y,
                                        double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, true, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    private List<AssessmentNode> createFourNodeGrid() {
        return List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50),
                node("c", 0, 100, 100, 50),
                node("d", 200, 100, 100, 50));
    }

    // ---- Rating comparison utility tests ----

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

    // ---- Coincident segment detection ----

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(conn0, conn1), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(conn0, conn1), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(conn0, conn1), false);

        boolean hasSuggestion = result.suggestions().stream()
                .anyMatch(s -> s.contains("overlapping connection segments"));
        assertTrue("Should generate coincident segment suggestion", hasSuggestion);
    }

    // ---- Content bounding box tests ----

    @Test
    public void assess_shouldReturnContentBounds_whenViewHasElements() {
        List<AssessmentNode> nodes = List.of(
                node("a", 100, 50, 120, 60),
                node("b", 300, 200, 80, 40));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertNull("contentBounds should be null for empty view", result.contentBounds());
    }

    @Test
    public void assess_shouldReturnContentBounds_whenViewHasSingleElement() {
        // null-for-1-element is the accessor's responsibility (early-return path).
        // The assessor correctly computes bounds for any non-empty list.
        List<AssessmentNode> nodes = List.of(
                node("only", 50, 100, 200, 80));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

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

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertNotNull("contentBounds should be present", result.contentBounds());
        // min x = 0 (group), min y = 0 (group)
        assertEquals(0.0, result.contentBounds().x(), 0.001);
        assertEquals(0.0, result.contentBounds().y(), 0.001);
        // max x = max(400, 150, 450) = 450, max y = max(300, 100, 300) = 300
        assertEquals(450.0, result.contentBounds().width(), 0.001);
        assertEquals(300.0, result.contentBounds().height(), 0.001);
    }

    // ---- Self-element pass-through detection tests ----

    @Test
    public void assess_connectionThroughOwnTarget_shouldDetect() {
        // Option α: stored final point
        // STRICTLY past target center along the dominant entry axis is treated as
        // terminal-segment over-penetration (caught by terminalSegmentOverPenetrates).
        // A at left, B at right, path approaches B from west and stores its final
        // bendpoint at (380, 115) — 30px past target center (350, 115).
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 90, 50, 50),      // source: center (25, 115)
                node("b", 300, 90, 100, 50));   // target: center (350, 115), x range 300-400

        // Path stored final point (380, 115) inside target body and past center → over-penetration.
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{25, 115}, new double[]{200, 115},
                                new double[]{380, 115}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

        boolean hasSelfPassThrough = result.connectionPassThroughs().stream()
                .anyMatch(d -> d.contains("routes through its own target"));
        assertTrue("Should detect self-element pass-through for target", hasSelfPassThrough);
    }

    @Test
    public void assess_connectionThroughOwnSource_shouldDetect() {
        // Connection path re-enters its own source element
        // Source is large (200px wide), path leaves source, wraps around, and re-enters
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 90, 200, 50),     // source: x range 0-200, y range 90-140
                node("b", 400, 90, 50, 50));    // target: center (425, 115)

        // Path: source center (100,115) → exits right (210,115) → goes up (210,50)
        // → goes left back through source body (50,50) → goes down (50,115) re-entering source
        // → exits right again (425,115)
        // Non-first segment (210,50)→(50,50) at y=50 is outside source (y=90..140) → OK
        // Non-first segment (50,50)→(50,115) at x=50: y goes from 50 to 115, crosses y=90 → enters source!
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{100, 115}, new double[]{210, 115},
                                new double[]{210, 50}, new double[]{50, 50},
                                new double[]{50, 115}, new double[]{425, 115}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

        boolean hasSelfPassThrough = result.connectionPassThroughs().stream()
                .anyMatch(d -> d.contains("routes through its own source"));
        assertTrue("Should detect self-element pass-through for source", hasSelfPassThrough);
    }

    @Test
    public void assess_connectionThroughOwnSource_deepPenetration_shouldDetect() {
        // Symmetric source-side
        // counterpart of assess_connectionThroughOwnTarget_shouldDetect. Path's stored
        // first bendpoint sits past source center along the exit axis — caught by
        // terminalSegmentOverPenetrates.
        List<AssessmentNode> nodes = List.of(
                node("a", 300, 90, 100, 50),    // source: center (350, 115), x range 300-400
                node("t", 0, 90, 50, 50));      // target: center (25, 115)

        // Path stored first point (380, 115) inside source body and 30px past center → over-penetration.
        // OLD nonTerminalPassesThroughNode (source side) skips first segment, intermediate
        // segment (200,115)→(25,115) is outside source — old method does not detect.
        // NEW terminalSegmentOverPenetrates fires: tx=380 > centerX=350 (entry axis horizontal,
        // otherPoint west of terminal).
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "t",
                        List.of(new double[]{380, 115}, new double[]{200, 115},
                                new double[]{25, 115}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

        boolean hasSelfPassThrough = result.connectionPassThroughs().stream()
                .anyMatch(d -> d.contains("routes through its own source"));
        assertTrue("Should detect terminal-segment over-penetration for source", hasSelfPassThrough);
    }

    @Test
    public void nonTerminalPassesThroughNode_targetPassThrough_shouldDetect() {
        // Direct test of the nonTerminalPassesThroughNode method
        // Path: (0, 100) → (200, 100) → (350, 100) → (350, 115) [target edge]
        // Target at (300, 90, 100, 50): x range 300-400, y range 90-140
        // Segment (200,100)→(350,100) at y=100 passes through target (y=100 is inside 90-140)
        // This is a non-terminal segment (not the last one), so it should be detected
        AssessmentNode target = node("b", 300, 90, 100, 50);
        List<double[]> path = List.of(
                new double[]{0, 100},     // source edge
                new double[]{200, 100},   // intermediate
                new double[]{350, 100},   // intermediate — passes through target
                new double[]{350, 115});  // target edge

        boolean detected = assessor.nonTerminalPassesThroughNode(path, target, true);
        assertTrue("Should detect non-terminal segment passing through target", detected);
    }

    @Test
    public void nonTerminalPassesThroughNode_cleanApproach_shouldNotDetect() {
        // Path approaches target cleanly from outside — only last segment enters target
        // Target at (300, 90, 100, 50)
        // Path: (0, 115) → (290, 115) → (350, 115) [target edge at x=300]
        // Segment (0,115)→(290,115) does NOT cross target (x=290 < 300)
        // Last segment (290,115)→(350,115) enters target but is excluded (isTarget=true, last segment)
        AssessmentNode target = node("b", 300, 90, 100, 50);
        List<double[]> path = List.of(
                new double[]{0, 115},     // source edge
                new double[]{290, 115},   // intermediate — outside target
                new double[]{350, 115});  // target edge

        boolean detected = assessor.nonTerminalPassesThroughNode(path, target, true);
        assertFalse("Clean approach to target should NOT be flagged", detected);
    }

    @Test
    public void nonTerminalPassesThroughNode_sourcePassThrough_shouldDetect() {
        // Path re-enters source body on a non-terminal segment
        // Source at (0, 90, 100, 50): x range 0-100, y range 90-140
        // Path: (50, 90) [source edge] → (50, 50) → (150, 50) → (150, 115) → (50, 115) → (300, 115)
        // Segment (150,115)→(50,115) at y=115 re-enters source (x=50 inside 0-100, y=115 inside 90-140)
        // This is a non-first segment, so it should be detected
        AssessmentNode source = node("a", 0, 90, 100, 50);
        List<double[]> path = List.of(
                new double[]{50, 90},     // source edge
                new double[]{50, 50},     // exit upward
                new double[]{150, 50},    // go right
                new double[]{150, 115},   // go down
                new double[]{50, 115},    // re-enters source body! (x=50 inside 0-100)
                new double[]{300, 115});  // target edge

        boolean detected = assessor.nonTerminalPassesThroughNode(path, source, false);
        assertTrue("Should detect non-terminal segment re-entering source", detected);
    }

    @Test
    public void nonTerminalPassesThroughNode_cleanDeparture_shouldNotDetect() {
        // Path departs source cleanly — only first segment exits source
        // Source at (0, 90, 100, 50)
        // Path: (100, 115) [source edge] → (200, 115) → (350, 115) [target edge]
        // First segment (100,115)→(200,115) exits source but is excluded (isTarget=false)
        // Second segment (200,115)→(350,115) is outside source
        AssessmentNode source = node("a", 0, 90, 100, 50);
        List<double[]> path = List.of(
                new double[]{100, 115},   // source edge
                new double[]{200, 115},   // outside source
                new double[]{350, 115});  // target edge

        boolean detected = assessor.nonTerminalPassesThroughNode(path, source, false);
        assertFalse("Clean departure from source should NOT be flagged", detected);
    }

    @Test
    public void assess_connectionCleanlyConnected_shouldNotDetectSelfPassThrough() {
        // Clean connection should NOT be reported
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 90, 50, 50),
                node("b", 300, 90, 50, 50));

        // Path goes straight from source to target edge, no intermediate crossing
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{25, 115}, new double[]{300, 115}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

        boolean hasSelfPassThrough = result.connectionPassThroughs().stream()
                .anyMatch(d -> d.contains("routes through its own"));
        assertFalse("Clean connection should NOT be flagged as self-pass-through",
                hasSelfPassThrough);
    }

    @Test
    public void nonTerminalPassesThroughNode_smallElement_shouldNotDetect() {
        // Element too small after inset → no detection (avoids false positives)
        // Element 8x8 with SELF_ELEMENT_INSET=5 → (8-10)=-2 after inset → skip
        AssessmentNode smallNode = node("small", 100, 100, 8, 8);
        List<double[]> path = List.of(
                new double[]{0, 104},
                new double[]{104, 104},
                new double[]{200, 104});

        boolean detected = assessor.nonTerminalPassesThroughNode(path, smallNode, true);
        assertFalse("Small element after inset should not be flagged", detected);
    }

    // ---- Short-segment detection tests ----

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

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
        boolean hasShortSegmentSuggestion = result.suggestions().stream()
                .anyMatch(s -> s.contains("exceed available segment length"));
        assertTrue("Should include short-segment suggestion", hasShortSegmentSuggestion);
    }

    // ---- countPathCrossings tests ----

    @Test
    public void countPathCrossings_crossingPaths_shouldReturnCorrectCount() {
        // Two paths forming an X — one crossing
        List<double[]> path1 = List.of(new double[]{0, 0}, new double[]{100, 100});
        List<double[]> path2 = List.of(new double[]{100, 0}, new double[]{0, 100});

        int crossings = LayoutQualityAssessor.countPathCrossings(List.of(path1, path2));
        assertEquals(1, crossings);
    }

    @Test
    public void countPathCrossings_parallelPaths_shouldReturnZero() {
        // Two horizontal parallel paths — no crossing
        List<double[]> path1 = List.of(new double[]{0, 0}, new double[]{100, 0});
        List<double[]> path2 = List.of(new double[]{0, 50}, new double[]{100, 50});

        int crossings = LayoutQualityAssessor.countPathCrossings(List.of(path1, path2));
        assertEquals(0, crossings);
    }

    @Test
    public void countPathCrossings_emptyList_shouldReturnZero() {
        assertEquals(0, LayoutQualityAssessor.countPathCrossings(List.of()));
    }

    @Test
    public void countPathCrossings_singlePath_shouldReturnZero() {
        List<double[]> path = List.of(new double[]{0, 0}, new double[]{100, 100});
        assertEquals(0, LayoutQualityAssessor.countPathCrossings(List.of(path)));
    }

    @Test
    public void countPathCrossings_multiSegmentPaths_shouldCountAllCrossings() {
        // Path 1: horizontal at y=50
        List<double[]> path1 = List.of(new double[]{0, 50}, new double[]{200, 50});
        // Path 2: zigzag that crosses path1 twice (up-down-up)
        List<double[]> path2 = List.of(
                new double[]{50, 0}, new double[]{50, 100},
                new double[]{150, 100}, new double[]{150, 0});

        int crossings = LayoutQualityAssessor.countPathCrossings(List.of(path1, path2));
        assertEquals(2, crossings);
    }

    @Test
    public void countPathCrossings_threePaths_shouldCountAllPairCrossings() {
        // Three paths that all cross each other at different points
        List<double[]> path1 = List.of(new double[]{0, 0}, new double[]{100, 100});
        List<double[]> path2 = List.of(new double[]{100, 0}, new double[]{0, 100});
        List<double[]> path3 = List.of(new double[]{50, 0}, new double[]{50, 100});

        int crossings = LayoutQualityAssessor.countPathCrossings(List.of(path1, path2, path3));
        // path1 x path2 = 1, path1 x path3 = 1, path2 x path3 = 1
        assertEquals(3, crossings);
    }

    // ---- Coincident segment rating tests ----

    @Test
    public void b38_coincidentSegments_zeroShouldRatePass() {
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 0, false);
        assertEquals("pass", result.breakdown().get("coincidentSegments"));
    }

    @Test
    public void b38_coincidentSegments_threeShouldRateGood() {
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 3, 0, 0, false);
        assertEquals("good", result.breakdown().get("coincidentSegments"));
    }

    @Test
    public void b38_coincidentSegments_eightShouldRateFair() {
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 8, 0, 0, false);
        assertEquals("fair", result.breakdown().get("coincidentSegments"));
    }

    @Test
    public void b38_coincidentSegments_nineShouldRatePoor() {
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 9, 0, 0, false);
        assertEquals("poor", result.breakdown().get("coincidentSegments"));
        assertEquals("Coincident segments (Tier 1) should drive overall to poor",
                "poor", result.rating());
    }

    // ---- Relaxed leniency gate tests ----

    @Test
    public void b38_groupedViewLeniency_withOnePassThrough_shouldStillApply() {
        // PT=1 <= 3, grouped, no other blockers → leniency applies
        // 25 crossings / 10 conns = 2.5 ratio → base "fair", boost → "good"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 1, 0, 0, 10, true);
        assertEquals("good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void b38_groupedViewLeniency_withThreePassThroughs_shouldStillApply() {
        // PT=3 <= 3, grouped → leniency applies
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 3, 0, 0, 10, true);
        assertEquals("good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void b38_groupedViewLeniency_withFourPassThroughs_shouldNotApply() {
        // PT=4 > 3, grouped → leniency does NOT apply
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 4, 0, 0, 10, true);
        assertEquals("fair", result.breakdown().get("edgeCrossings"));
    }

    // ---- Non-orthogonal terminal tests ----

    @Test
    public void b38_nonOrthogonalTerminals_zeroShouldRatePass() {
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 0, false);
        assertEquals("pass", result.breakdown().get("nonOrthogonalTerminals"));
    }

    @Test
    public void b58_nonOrthogonalTerminals_thirtyPercentShouldRateFair() {
        // 3 non-orth / 10 connections = 30% → exactly at NON_ORTH_RATIO_FAIR boundary → "fair"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 3, 10, false);
        assertEquals("fair", result.breakdown().get("nonOrthogonalTerminals"));
    }

    @Test
    public void b58_nonOrthogonalTerminals_fortyPercentShouldRatePoor() {
        // 4 non-orth / 10 connections = 40% → above NON_ORTH_RATIO_FAIR → "poor"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 4, 10, false);
        assertEquals("poor", result.breakdown().get("nonOrthogonalTerminals"));
    }

    // ---- Density-aware non-orthogonal terminal threshold tests ----

    @Test
    public void b58_nonOrthogonalTerminals_lowDensityShouldRateGood() {
        // 2 non-orth / 47 connections = 4.3% → below NON_ORTH_RATIO_GOOD (10%) → "good"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 2, 47, false);
        assertEquals("good", result.breakdown().get("nonOrthogonalTerminals"));
    }

    @Test
    public void b58_nonOrthogonalTerminals_exactlyTenPercentShouldRateGood() {
        // 1 non-orth / 10 connections = 10% → exactly at NON_ORTH_RATIO_GOOD boundary (≤) → "good"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 1, 10, false);
        assertEquals("good", result.breakdown().get("nonOrthogonalTerminals"));
    }

    @Test
    public void b58_nonOrthogonalTerminals_midDensityShouldRateFair() {
        // 5 non-orth / 20 connections = 25% → between 10% and 30% → "fair"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 5, 20, false);
        assertEquals("fair", result.breakdown().get("nonOrthogonalTerminals"));
    }

    @Test
    public void b58_nonOrthogonalTerminals_exactlyThirtyPercentShouldRateFair() {
        // 6 non-orth / 20 connections = 30% → exactly at NON_ORTH_RATIO_FAIR boundary (≤) → "fair"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 6, 20, false);
        assertEquals("fair", result.breakdown().get("nonOrthogonalTerminals"));
    }

    @Test
    public void b58_nonOrthogonalTerminals_highDensityShouldRatePoor() {
        // 8 non-orth / 20 connections = 40% → above NON_ORTH_RATIO_FAIR → "poor"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 8, 20, false);
        assertEquals("poor", result.breakdown().get("nonOrthogonalTerminals"));
    }

    @Test
    public void b58_nonOrthogonalTerminals_veryHighDensityShouldRatePoor() {
        // 15 non-orth / 20 connections = 75% → well above NON_ORTH_RATIO_FAIR → "poor"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 15, 20, false);
        assertEquals("poor", result.breakdown().get("nonOrthogonalTerminals"));
    }

    @Test
    public void b58_nonOrthogonalTerminals_zeroConnectionsFallbackShouldRateFair() {
        // 3 non-orth / 0 connections → zero-connection fallback → "fair"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 3, 0, false);
        assertEquals("fair", result.breakdown().get("nonOrthogonalTerminals"));
    }

    // ---- Severity-tiered rating tests ----

    @Test
    public void b38_tieredRating_tier1Poor_shouldProduceOverallPoor() {
        // Overlaps "poor" (Tier 1) → overall "poor"
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                5, 0, 50.0, 80, 0, 0, 0, 0, 0, false);
        assertEquals("poor", result.rating());
    }

    @Test
    public void b38_tieredRating_tier2PoorAlone_shouldCapOverallAtFair() {
        // PRE-REDESIGN: crossings "poor" (Tier 2) → overall capped at "fair".
        // POST-REDESIGN M6: crossings demoted to Tier 3R cap "good" → overall capped at "good".
        // 150/28 = 5.36 ratio, no leniency (not grouped).
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 150, 50.0, 80, 0, 0, 0, 0, 28, false);
        assertEquals("poor", result.breakdown().get("edgeCrossings"));
        // M6: crossings now in Tier 3R cap good — overall caps at "good", not "fair".
        assertEquals("M6: crossings demoted to Tier 3R — overall good", "good", result.rating());
    }

    @Test
    public void b38_tieredRating_tier3PoorAlone_shouldCapOverallAtGood() {
        // PRE-REDESIGN: spacing + alignment both Tier 3 cap good → overall "good".
        // POST-REDESIGN M6: spacing promoted Tier 2L cap fair; alignment stays Tier 3L cap good.
        // Spacing "fair" → layout-tier 2 (capped at 2 by Tier 2L) → layoutLevel = 2 → layoutRating "fair".
        // Overall = worse(fair, excellent) = "fair".
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 10.0, 20, 0, 0, 0, 0, 0, false);
        assertEquals("fair", result.breakdown().get("spacing"));
        assertEquals("fair", result.breakdown().get("alignment"));
        assertEquals("M6: spacing promoted Tier 2L cap fair — overall fair (was good under B38 Tier 3)",
                "fair", result.rating());
    }

    @Test
    public void b38_tieredRating_mixedTier2PoorTier1Fair_shouldProduceOverallFair() {
        // PT=2 → "fair" (Tier 1R), crossings "poor" (M6: Tier 3R cap good).
        // Tier 1R drives routing to "fair"; Tier 3R caps at "good" so doesn't override.
        // Routing tier = fair; layout tier = excellent; overall = worse(layout, routing) = fair.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 150, 50.0, 80, 0, 2, 0, 0, 28, false);
        assertEquals("fair", result.breakdown().get("passThroughs"));
        assertEquals("poor", result.breakdown().get("edgeCrossings"));
        assertEquals("fair", result.rating());
    }

    @Test
    public void b38_tieredRating_allMetricsPass_shouldProduceExcellent() {
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 0, false);
        assertEquals("excellent", result.rating());
    }

    // ---- Assessor.Redesign M6: Non-orthogonal terminals — Tier 2R cap fair (promoted) ----

    @Test
    public void b59_tieredRating_nonOrthPoorAlone_shouldCapOverallAtGood() {
        // PRE-REDESIGN: nonOrth was Tier 3 cap "good".
        // POST-REDESIGN M6: nonOrth promoted to Tier 2R cap "fair" — overall now caps at "fair".
        // 15 non-orth / 20 connections = 75% ratio → "poor" per-metric (>30%)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 15, 20, false);
        assertEquals("poor", result.breakdown().get("nonOrthogonalTerminals"));
        // M6: nonOrth now Tier 2R cap fair — overall caps at "fair", not "good".
        assertEquals("M6: nonOrth promoted to Tier 2R — overall fair", "fair", result.rating());
    }

    @Test
    public void b59_tieredRating_nonOrthPoorWithSpacingFair_shouldCapOverallAtGood() {
        // PRE-REDESIGN: nonOrth "poor" + spacing "fair" (both Tier 3) → overall "good".
        // POST-REDESIGN M6: nonOrth Tier 2R fair-cap dominates → overall "fair".
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 10.0, 20, 0, 0, 0, 15, 20, false);
        assertEquals("poor", result.breakdown().get("nonOrthogonalTerminals"));
        assertEquals("fair", result.breakdown().get("spacing"));
        assertEquals("M6: nonOrth Tier 2R promoted — overall fair", "fair", result.rating());
    }

    @Test
    public void b59_tieredRating_nonOrthPoorWithCrossingsPoor_shouldProduceOverallFair() {
        // PRE-REDESIGN: crossings "poor" (Tier 2 cap fair) + nonOrth "poor" (Tier 3) → "fair".
        // POST-REDESIGN M6: crossings demoted Tier 3R cap good; nonOrth promoted Tier 2R cap fair.
        // Routing tier worst contribution = nonOrth Tier 2R fair → overall stays "fair".
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 150, 50.0, 80, 0, 0, 0, 15, 28, false);
        assertEquals("poor", result.breakdown().get("edgeCrossings"));
        assertEquals("poor", result.breakdown().get("nonOrthogonalTerminals"));
        assertEquals("fair", result.rating());
    }

    // ---- Non-orthogonal terminal detection tests ----

    @Test
    public void b38_countNonOrthogonalTerminals_orthogonalPath_shouldReturnZero() {
        // All segments horizontal/vertical
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 50}, new double[]{100, 50}, new double[]{100, 150}), "", 0));
        assertEquals(0, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b38_countNonOrthogonalTerminals_diagonalSource_shouldCountOne() {
        // First segment is diagonal (dx=30, dy=40 — both > tolerance)
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{30, 40}, new double[]{30, 100}), "", 0));
        assertEquals(1, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b38_countNonOrthogonalTerminals_diagonalTarget_shouldCountOne() {
        // Last segment is diagonal
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{0, 50}, new double[]{30, 90}), "", 0));
        assertEquals(1, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b38_countNonOrthogonalTerminals_bothDiagonal_shouldCountOnePerConnection() {
        // Both terminals diagonal — still counts as 1 (per-connection, not per-segment)
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{30, 40}, new double[]{60, 80}), "", 0));
        assertEquals(1, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b38_countNonOrthogonalTerminals_withinTolerance_shouldNotCount() {
        // dx=3, dy=40 — angular deviation = atan2(40,3) ≈ 85.7° → 4.3° from vertical → below 5° threshold
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{3, 40}, new double[]{3, 100}), "", 0));
        assertEquals(0, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b38_countNonOrthogonalTerminals_singlePointPath_shouldSkip() {
        // Path with less than 2 points — should not crash
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(new double[]{0, 0}), "", 0));
        assertEquals(0, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    // ---- Angular non-orthogonal terminal detection tests ----

    @Test
    public void b57_angularDetection_nearVertical_shouldNotFlag() {
        // dx=7, dy=270 → angle ≈ 1.5° from vertical → NOT flagged
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{7, 270}, new double[]{7, 400}), "", 0));
        assertEquals(0, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b57_angularDetection_nearHorizontal_shouldNotFlag() {
        // dx=270, dy=7 → angle ≈ 1.5° from horizontal → NOT flagged
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{270, 7}, new double[]{270, 100}), "", 0));
        assertEquals(0, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b57_angularDetection_genuinelyDiagonal_shouldFlag() {
        // dx=30, dy=40 → deviation ≈ 36.9° → flagged
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{30, 40}, new double[]{30, 100}), "", 0));
        assertEquals(1, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b57_angularDetection_justBelowThreshold_shouldNotFlag() {
        // ~4.997° from horizontal (dx=99.62, dy=8.71) → below 5° threshold → NOT flagged
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{99.62, 8.71}, new double[]{99.62, 100}), "", 0));
        assertEquals(0, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b57_angularDetection_justAboveThreshold_shouldFlag() {
        // ~5.01° from horizontal (dx=100, dy=8.77) → just above 5° threshold → flagged
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{100, 8.77}, new double[]{100, 100}), "", 0));
        assertEquals(1, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b57_angularDetection_zeroLengthSegment_shouldNotFlagOrThrow() {
        // dx=0, dy=0 → atan2(0,0)=0° → deviation=0° → NOT flagged, no exception
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{50, 50}, new double[]{50, 50}, new double[]{50, 100}), "", 0));
        assertEquals(0, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    @Test
    public void b57_angularDetection_negativeDirection_shouldNotFlag() {
        // Near-vertical with negative dy direction: p1=(100,300)→p2=(107,30) → dx=7, dy=270 → 1.5° → NOT flagged
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{100, 300}, new double[]{107, 30}, new double[]{107, 0}), "", 0));
        assertEquals(0, assessor.countNonOrthogonalTerminals(conns, false).count());
    }

    // ---- Self-element PT rating tolerance tests ----

    @Test
    public void detectPassThroughs_selfElementOnly_shouldReturnZeroCrossCount() {
        // Connection routes through own target only — crossElementCount should be 0
        // Geometry: path overshoots past target, terminal segment doubles back.
        // After clipping: (50,125)→(350,125)→(200,125). Segment 0 crosses target body.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 100, 50, 50),
                node("b", 100, 100, 100, 50));

        // Path overshoots past target, then terminal returns — non-terminal seg crosses target
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{25, 125}, new double[]{350, 125},
                                new double[]{150, 125}), "", 1));

        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, false);

        assertTrue("Should have descriptions for self-element PT",
                result.descriptions().stream().anyMatch(d -> d.contains("routes through its own")));
        assertEquals("Self-element PTs should not count as cross-element", 0, result.crossElementCount());
        assertTrue("Total count should include self-element PTs", result.totalCount() > 0);
    }

    @Test
    public void detectPassThroughs_crossElementOnly_shouldCountAsCross() {
        // Connection from A to C passes through unrelated element B
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 90, 50, 50),      // source
                node("b", 150, 90, 50, 50),     // unrelated element in the path
                node("c", 400, 90, 50, 50));    // target

        // Path goes straight through B
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "c",
                        List.of(new double[]{25, 115}, new double[]{175, 115},
                                new double[]{425, 115}), "", 1));

        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, false);

        assertTrue("Should have cross-element PT description",
                result.descriptions().stream().anyMatch(d -> d.contains("passes through element")));
        assertEquals("Cross-element PT should be counted", 1, result.crossElementCount());
    }

    @Test
    public void detectPassThroughs_mixedSelfAndCross_shouldSeparateCounts() {
        // Connection from A to C passes through unrelated B AND routes through own target C.
        // Geometry: path goes through B (cross-element), overshoots past C, terminal returns.
        // After clipping: (50,125)→(225,125)→(600,125)→(500,125).
        // Seg 0 crosses B (cross-element). Seg 1 crosses C body (self-element).
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 100, 50, 50),       // source
                node("b", 200, 100, 50, 50),      // unrelated element
                node("c", 400, 100, 100, 50));    // target (path overshoots past it)

        // Path goes through B, overshoots past C, terminal segment returns to C
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "c",
                        List.of(new double[]{25, 125}, new double[]{225, 125},
                                new double[]{600, 125}, new double[]{450, 125}), "", 2));

        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, false);

        boolean hasCross = result.descriptions().stream()
                .anyMatch(d -> d.contains("passes through element"));
        boolean hasSelf = result.descriptions().stream()
                .anyMatch(d -> d.contains("routes through its own"));
        assertTrue("Should have cross-element description", hasCross);
        assertTrue("Should have self-element description", hasSelf);
        assertEquals("Only cross-element should be in crossElementCount", 1, result.crossElementCount());
        assertTrue("Total count should include both types", result.totalCount() >= 2);
    }

    @Test
    public void detectPassThroughs_shouldNotFlagOwnParentElement_whenChildConnectsOutward() {
        // A child element nested inside a NON-GROUP parent element connects to a target
        // outside the parent. The route necessarily crosses the parent's box on its way
        // out, but the parent is an ancestor of the source and must not be counted as a
        // cross-element pass-through. A non-group parent is essential here: a group parent
        // would also be excluded by the transparent-container skip, which would mask the
        // ancestor carve-out this test is meant to pin.
        List<AssessmentNode> nodes = List.of(
                node("p", 100, 100, 400, 300),           // parent container element
                childNode("c", 140, 230, 60, 40, "p"),   // child nested inside p
                node("t", 600, 230, 80, 50));            // external target, right of p

        // Route exits p's right wall at y=250 and runs to t.
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("conn", "c", "t",
                        List.of(new double[]{170, 250}, new double[]{550, 250},
                                new double[]{620, 250}), "", 1));

        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, false);

        assertEquals("Own parent element must not count as a cross-element pass-through",
                0, result.crossElementCount());
        assertFalse("No description should name the parent element",
                result.descriptions().stream().anyMatch(d -> d.contains("'p'")));
    }

    @Test
    public void detectPassThroughs_shouldNotFlagOwnParentElement_whenExternalSourceConnectsToNestedChild() {
        // Mirror of the source-side case with the nesting on the target: an external
        // source connects into a child nested inside a non-group parent element. The
        // parent is an ancestor of the target and must not be flagged.
        List<AssessmentNode> nodes = List.of(
                node("s", 0, 230, 80, 50),               // external source, left of p
                node("p", 200, 100, 400, 300),           // parent container element
                childNode("c", 400, 230, 60, 40, "p"));  // child target nested inside p

        // Route enters p's left wall at y=250 and runs to the nested target c.
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("conn", "s", "c",
                        List.of(new double[]{40, 255}, new double[]{250, 250},
                                new double[]{430, 250}), "", 1));

        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, false);

        assertEquals("Own parent element of the target must not count as a pass-through",
                0, result.crossElementCount());
        assertFalse("No description should name the parent element",
                result.descriptions().stream().anyMatch(d -> d.contains("'p'")));
    }

    @Test
    public void detectPassThroughs_shouldExcludeAllAncestorElements_whenChildIsDeeplyNested() {
        // Three nesting levels of non-group elements: grandparent contains parent contains
        // child. The grandparent is reachable only by walking the full parentId chain
        // (child -> parent -> grandparent), so this pins the transitive ancestor exclusion,
        // not merely the immediate parent.
        List<AssessmentNode> nodes = List.of(
                node("gp", 100, 100, 500, 400),            // grandparent
                childNode("p", 120, 120, 400, 300, "gp"),  // parent inside grandparent
                childNode("c", 160, 240, 60, 40, "p"),     // child inside parent
                node("t", 700, 240, 80, 50));              // external target

        // Route exits both the parent and grandparent walls at y=260 and runs to t.
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("conn", "c", "t",
                        List.of(new double[]{190, 260}, new double[]{650, 260},
                                new double[]{720, 260}), "", 1));

        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, false);

        assertEquals("Both ancestor elements (parent and grandparent) must be excluded",
                0, result.crossElementCount());
        assertFalse("No description should name the parent element",
                result.descriptions().stream().anyMatch(d -> d.contains("'p'")));
        assertFalse("No description should name the grandparent element",
                result.descriptions().stream().anyMatch(d -> d.contains("'gp'")));
    }

    @Test
    public void detectPassThroughs_shouldNotFlagOwnParentGroup_whenChildConnectsOutward() {
        // Same outward-routing geometry as the element-parent case, but the parent is a
        // GROUP. A group ancestor is excluded both by the transparent-container skip and by
        // the ancestor carve-out, so this stays at zero either way. It is the contrast case
        // confirming the element-parent tests above exercise the ancestor exclusion
        // specifically and not the group skip.
        List<AssessmentNode> nodes = List.of(
                group("pg", 100, 100, 400, 300),          // parent GROUP container
                childNode("c", 140, 230, 60, 40, "pg"),   // child nested inside the group
                node("t", 600, 230, 80, 50));             // external target

        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("conn", "c", "t",
                        List.of(new double[]{170, 250}, new double[]{550, 250},
                                new double[]{620, 250}), "", 1));

        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, false);

        assertEquals("Own parent group must not count as a pass-through",
                0, result.crossElementCount());
    }

    @Test
    public void assess_nestedChildConnectsOutward_shouldNotPenaliseOwnParent() {
        // End-to-end through assess(): the nested-child-outward route must leave
        // connectionPassThroughs empty and the passThroughs rating unaffected.
        List<AssessmentNode> nodes = List.of(
                node("p", 100, 100, 400, 300),
                childNode("c", 140, 230, 60, 40, "p"),
                node("t", 600, 230, 80, 50));

        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("conn", "c", "t",
                        List.of(new double[]{170, 250}, new double[]{550, 250},
                                new double[]{620, 250}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

        assertTrue("Own parent must not appear as a cross-element pass-through",
                result.connectionPassThroughs().isEmpty());
        assertEquals("passThroughs rating must remain pass when only the own parent is crossed",
                "pass", result.ratingBreakdown().get("passThroughs"));
    }

    @Test
    public void rating_selfElementPTOnly_shouldNotPenalise() {
        // 3 self-element PTs, 0 cross-element → passThroughs rating should be "pass"
        // computeRatingWithBreakdown receives crossElementCount (0), not total
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 0, false);
        assertEquals("pass", result.breakdown().get("passThroughs"));
    }

    @Test
    public void rating_groupedView_selfElementPTOnly_shouldStillGetLeniency() {
        // Grouped-view leniency gate uses cross-element count only.
        // With crossElementCount=0 (even if self-element PTs exist), leniency applies.
        // crossings=25 → "fair" normally (ratio 2.5 > CROSSING_RATIO_GOOD but ≤ MODERATE).
        // Leniency boosts "fair" → "good".
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 0, 0, 0, 10, true);
        assertEquals("good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void rating_crossElementPT_shouldStillPenalise() {
        // 1 cross-element PT → passThroughs rating should be "fair" (unchanged behaviour)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 1, 0, 0, 0, false);
        assertEquals("fair", result.breakdown().get("passThroughs"));
    }

    @Test
    public void assess_selfElementPTOnly_shouldNotAffectRating() {
        // Integration test: view with only self-element PTs should get "pass" for passThroughs.
        // Same geometry as detectPassThroughs_selfElementOnly test — overshoot-and-return path.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 100, 50, 50),
                node("b", 100, 100, 100, 50));

        // Path overshoots past target, terminal returns — non-terminal seg crosses target
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{25, 125}, new double[]{350, 125},
                                new double[]{150, 125}), "", 1));

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

        // Informational reporting preserved — descriptions should contain the self-element PT
        assertTrue("Descriptions should still contain self-element PT",
                result.connectionPassThroughs().stream()
                        .anyMatch(d -> d.contains("routes through its own")));
        // Rating should not be penalised
        assertEquals("Self-element PT should not penalise rating",
                "pass", result.ratingBreakdown().get("passThroughs"));
    }

    // ---- Violator ID collection tests ----

    @Test
    public void b55_assess_withViolatorIdsFalse_shouldReturnNullViolatorIds() {
        // Two overlapping elements, but includeViolatorIds=false
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 50, 0, 100, 50));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
        assertNull("violatorIds should be null when not requested", result.violatorIds());
    }

    @Test
    public void b55_assess_withViolatorIdsTrue_overlaps_shouldReturnBothElementIds() {
        // Two overlapping sibling elements (same null parent)
        List<AssessmentNode> nodes = List.of(
                node("elem-1", 0, 0, 100, 50),
                node("elem-2", 50, 0, 100, 50));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), true);
        assertNotNull("violatorIds should be present", result.violatorIds());
        assertTrue("Should have overlaps key", result.violatorIds().containsKey("overlaps"));
        Set<String> overlapIds = result.violatorIds().get("overlaps");
        assertTrue("Should contain elem-1", overlapIds.contains("elem-1"));
        assertTrue("Should contain elem-2", overlapIds.contains("elem-2"));
    }

    @Test
    public void b55_assess_withViolatorIdsTrue_passThroughs_shouldReturnConnectionIds() {
        // Connection passes through an unrelated element
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 100, 50, 50),
                node("tgt", 400, 100, 50, 50),
                node("blocker", 150, 100, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("conn-1", "src", "tgt",
                        List.of(new double[]{25, 125}, new double[]{425, 125}), "", 1));
        LayoutAssessmentResult result = assessor.assess(nodes, connections, true);
        assertNotNull("violatorIds should be present", result.violatorIds());
        assertTrue("Should have passThroughs key", result.violatorIds().containsKey("passThroughs"));
        Set<String> ptIds = result.violatorIds().get("passThroughs");
        assertTrue("Should contain conn-1", ptIds.contains("conn-1"));
    }

    @Test
    public void b55_assess_withViolatorIdsTrue_passThroughs_shouldExcludeSelfElement() {
        // Self-element pass-through should NOT be in violatorIds (cross-element only)
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 100, 50, 50),
                node("b", 100, 100, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b",
                        List.of(new double[]{25, 125}, new double[]{350, 125},
                                new double[]{150, 125}), "", 1));
        LayoutAssessmentResult result = assessor.assess(nodes, connections, true);
        // Self-element PT only — no cross-element PTs, so no passThroughs key in violatorIds.
        // violatorIds may be null (no violations at all) or present without passThroughs key.
        // Either way, passThroughs must NOT appear.
        if (result.violatorIds() == null) {
            // Null map is acceptable — means no violating metrics at all
            assertNull("Null violatorIds is acceptable (no cross-element violations)",
                    result.violatorIds());
        } else {
            assertFalse("Self-element PTs should not appear in violatorIds",
                    result.violatorIds().containsKey("passThroughs"));
        }
    }

    @Test
    public void b55_assess_withViolatorIdsTrue_nonOrthogonalTerminals_shouldReturnConnectionIds() {
        // Connection with diagonal source terminal
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 200, 200, 50, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("conn-diag", "a", "b",
                        List.of(new double[]{25, 25}, new double[]{55, 55}, new double[]{225, 225}), "", 1));
        LayoutAssessmentResult result = assessor.assess(nodes, connections, true);
        assertNotNull("violatorIds should be present", result.violatorIds());
        assertTrue("Should have nonOrthogonalTerminals key",
                result.violatorIds().containsKey("nonOrthogonalTerminals"));
        assertTrue("Should contain conn-diag",
                result.violatorIds().get("nonOrthogonalTerminals").contains("conn-diag"));
    }

    @Test
    public void b55_assess_withViolatorIdsTrue_boundaryViolations_shouldReturnChildIds() {
        // Child extends outside parent group
        List<AssessmentNode> nodes = List.of(
                group("grp", 0, 0, 200, 200),
                childNode("child-out", 180, 50, 100, 50, "grp"));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), true);
        assertNotNull("violatorIds should be present", result.violatorIds());
        assertTrue("Should have boundaryViolations key",
                result.violatorIds().containsKey("boundaryViolations"));
        Set<String> bvIds = result.violatorIds().get("boundaryViolations");
        assertTrue("Should contain child-out", bvIds.contains("child-out"));
        assertFalse("Should NOT contain parent group ID", bvIds.contains("grp"));
    }

    @Test
    public void b55_assess_withViolatorIdsTrue_crossingsExcluded() {
        // Edge crossings exist but should NOT appear in violatorIds
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 200, 0, 50, 50),
                node("c", 0, 200, 50, 50),
                node("d", 200, 200, 50, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "d",
                        List.of(new double[]{25, 25}, new double[]{225, 225}), "", 1),
                new AssessmentConnection("c2", "b", "c",
                        List.of(new double[]{225, 25}, new double[]{25, 225}), "", 1));
        LayoutAssessmentResult result = assessor.assess(nodes, connections, true);
        assertTrue("Should have edge crossings", result.edgeCrossingCount() > 0);
        if (result.violatorIds() != null) {
            assertFalse("Crossings should NOT be in violatorIds",
                    result.violatorIds().containsKey("crossings"));
            assertFalse("edgeCrossings should NOT be in violatorIds",
                    result.violatorIds().containsKey("edgeCrossings"));
        }
    }

    @Test
    public void b55_assess_withViolatorIdsTrue_emptyMetricsOmitted() {
        // Well-laid-out view with no violations — violatorIds map should be null
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), true);
        // No violations at all → map should be null (empty map becomes null)
        assertNull("violatorIds should be null when no violations exist", result.violatorIds());
    }

    @Test
    public void b55_computeOverlaps_withViolatorIds_shouldCollectBothElementIds() {
        List<AssessmentNode> nodes = List.of(
                node("x", 0, 0, 100, 50),
                node("y", 50, 0, 100, 50),
                node("z", 300, 0, 100, 50));
        LayoutQualityAssessor.OverlapResult result =
                assessor.computeOverlaps(nodes, Set.of(), true);
        assertEquals(1, result.siblingCount());
        assertTrue("Should contain x", result.violatorIds().contains("x"));
        assertTrue("Should contain y", result.violatorIds().contains("y"));
        assertFalse("Should NOT contain z (no overlap)", result.violatorIds().contains("z"));
    }

    @Test
    public void b55_detectBoundaryViolations_withViolatorIds_shouldCollectChildIds() {
        List<AssessmentNode> nodes = List.of(
                group("g1", 0, 0, 200, 200),
                childNode("ok", 10, 30, 80, 40, "g1"),
                childNode("bad", 180, 30, 80, 40, "g1"));
        LayoutQualityAssessor.BoundaryViolationResult result =
                assessor.detectBoundaryViolations(nodes, true);
        assertEquals(1, result.descriptions().size());
        assertTrue("Should contain bad", result.violatorIds().contains("bad"));
        assertFalse("Should NOT contain ok", result.violatorIds().contains("ok"));
    }

    @Test
    public void b55_countNonOrthogonalTerminals_withViolatorIds_shouldCollectConnectionIds() {
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c-orth", "a", "b", List.of(
                        new double[]{0, 50}, new double[]{100, 50}, new double[]{100, 150}), "", 0),
                new AssessmentConnection("c-diag", "a", "c", List.of(
                        new double[]{0, 0}, new double[]{30, 40}, new double[]{30, 100}), "", 0));
        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(conns, true);
        assertEquals(1, result.count());
        assertTrue("Should contain c-diag", result.violatorIds().contains("c-diag"));
        assertFalse("Should NOT contain c-orth", result.violatorIds().contains("c-orth"));
    }

    @Test
    public void b55_detectPassThroughs_withViolatorIds_shouldCollectConnectionIds() {
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 100, 50, 50),
                node("tgt", 400, 100, 50, 50),
                node("blocker", 150, 100, 100, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("pt-conn", "src", "tgt",
                        List.of(new double[]{25, 125}, new double[]{425, 125}), "", 1),
                new AssessmentConnection("clean-conn", "src", "tgt",
                        List.of(new double[]{25, 125}, new double[]{25, 50},
                                new double[]{425, 50}, new double[]{425, 125}), "", 1));
        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, true);
        assertTrue("Should contain pt-conn", result.violatorIds().contains("pt-conn"));
    }

    // ---- Zero-bendpoint non-orthogonal terminal detection tests ----

    @Test
    public void b60_countNonOrthogonalTerminals_orthogonalZeroBendpoint_shouldNotCount() {
        // 2-point horizontal path (zero bendpoints but orthogonal) — should not count
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{100, 0}), "", 0));
        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(conns, false);
        assertEquals(0, result.count());
        assertEquals(0, result.zeroBendpointCount());
    }

    @Test
    public void b60_countNonOrthogonalTerminals_zeroBendpointDiagonal_shouldCountInZeroBP() {
        // 2-point path (source center → target center, no bendpoints) with diagonal = ELK signature
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{30, 40}), "", 0));
        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(conns, false);
        assertEquals(1, result.count());
        assertEquals(1, result.zeroBendpointCount());
    }

    @Test
    public void b60_countNonOrthogonalTerminals_routedDiagonal_shouldNotCountInZeroBP() {
        // 3-point path (has bendpoints) with diagonal source terminal
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{30, 40}, new double[]{30, 100}), "", 0));
        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(conns, false);
        assertEquals(1, result.count());
        assertEquals(0, result.zeroBendpointCount());
    }

    @Test
    public void b60_countNonOrthogonalTerminals_mixed_shouldReturnCorrectCounts() {
        // Mix of zero-BP diagonal and routed diagonal connections
        List<AssessmentConnection> conns = List.of(
                // Zero-BP diagonal (ELK signature)
                new AssessmentConnection("c-elk", "a", "b", List.of(
                        new double[]{0, 0}, new double[]{30, 40}), "", 0),
                // Routed diagonal (3-point path)
                new AssessmentConnection("c-routed", "a", "c", List.of(
                        new double[]{0, 0}, new double[]{30, 40}, new double[]{30, 100}), "", 0),
                // Orthogonal (should not count at all)
                new AssessmentConnection("c-orth", "a", "d", List.of(
                        new double[]{0, 0}, new double[]{100, 0}, new double[]{100, 50}), "", 0));
        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(conns, false);
        assertEquals(2, result.count());
        assertEquals(1, result.zeroBendpointCount());
    }

    // ---- Suggestion text differentiation tests ----

    @Test
    public void b60_suggestions_allZeroBP_shouldNotSuggestReRouting() {
        // All non-orth connections are zero-bendpoint (ELK signature)
        // Need nodes for assess() and zero-BP diagonal connections
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 200, 200, 50, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{25, 25}, new double[]{225, 225}), "", 1));
        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
        // Should have suggestion about straight-line connections
        boolean hasElkText = result.suggestions().stream()
                .anyMatch(s -> s.contains("straight-line connections typical of ELK layout"));
        assertTrue("Should have ELK-aware suggestion text", hasElkText);
        // Should NOT suggest re-routing
        boolean hasReRoute = result.suggestions().stream()
                .anyMatch(s -> s.contains("re-run auto-route-connections to improve orthogonality"));
        assertFalse("Should NOT suggest re-routing for all-zero-BP", hasReRoute);
    }

    @Test
    public void b60_suggestions_mixed_shouldContainBothElkAndReRouteAdvice() {
        // Mix of zero-BP and routed non-orth connections
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 200, 200, 50, 50),
                node("c", 400, 0, 50, 50));
        List<AssessmentConnection> connections = List.of(
                // Zero-BP diagonal (ELK)
                new AssessmentConnection("c-elk", "a", "b", List.of(
                        new double[]{25, 25}, new double[]{225, 225}), "", 1),
                // Routed diagonal (3-point path with diagonal target terminal)
                new AssessmentConnection("c-routed", "a", "c", List.of(
                        new double[]{25, 25}, new double[]{25, 200}, new double[]{225, 425}), "", 1));
        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
        // Should have ELK-aware text for zero-BP portion
        boolean hasElkText = result.suggestions().stream()
                .anyMatch(s -> s.contains("straight-line connections typical of ELK layout"));
        assertTrue("Should have ELK text for zero-BP portion", hasElkText);
        // Should also have re-route advice for routed portion. The full parenthetical
        // "(or use mode='terminals-only'" is unique to the routed-advice branches and
        // does not appear in any other generateSuggestions output (group-overlap,
        // group-crossings, M2 interior, M3 zigzag, etc.).
        boolean hasReRoute = result.suggestions().stream()
                .anyMatch(s -> s.contains("re-run auto-route-connections (or use mode='terminals-only'"));
        assertTrue("Should have re-route advice for routed portion", hasReRoute);
    }

    @Test
    public void b60_suggestions_noZeroBP_shouldSuggestReRouting() {
        // All non-orth connections are routed (3+ point paths) — existing text unchanged
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 200, 200, 50, 50));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "a", "b", List.of(
                        new double[]{25, 25}, new double[]{55, 55}, new double[]{225, 225}), "", 1));
        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);
        // Should have the existing re-route suggestion. The full parenthetical
        // "(or use mode='terminals-only'" is unique to the routed-advice branches.
        boolean hasReRoute = result.suggestions().stream()
                .anyMatch(s -> s.contains("re-run auto-route-connections (or use mode='terminals-only'"));
        assertTrue("Should suggest re-routing for all-routed non-orth", hasReRoute);
        // Should NOT have ELK text
        boolean hasElkText = result.suggestions().stream()
                .anyMatch(s -> s.contains("straight-line connections typical of ELK layout"));
        assertFalse("Should NOT have ELK text for all-routed", hasElkText);
    }

    // ====================================================================
    // Assessor.Redesign M1-M6 unit tests (Task 1.4-1.9, 2026-04-26)
    // ====================================================================

    // ---- M1: Corrected nonOrthogonalTerminalCount (post-clip definition, Task 1.4) ----

    @Test
    public void m1_shouldNotFlagAsNonOrthogonal_whenFirstBpOnSourceLeftPerimeter_alongFaceAxis() {
        // Source rect: x=200, y=100, w=100, h=80. LEFT face at x=200. Source center (250, 140).
        // BP1 (200, 130) on LEFT perimeter. Target rect chosen so target-side segment is also orthogonal.
        AssessmentNode source = node("src", 200, 100, 100, 80);
        AssessmentNode target = node("tgt", 0, 115, 50, 30); // target center (25, 130) — same Y as BP1
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{250, 140}, new double[]{200, 130},
                        new double[]{25, 130}), "", 1);

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(List.of(conn), List.of(source, target), false);

        // Pre-M1: would flag (source-center → BP1 is diagonal). Post-M1: skipped — BP1 on perimeter.
        // Target-side segment (200,130)→(25,130) is horizontal — orthogonal.
        assertEquals("M1: BP1 on LEFT perimeter must NOT flag as non-orthogonal",
                0, result.count());
    }

    @Test
    public void m1_shouldNotFlagAsNonOrthogonal_whenFirstBpOnSourceRightPerimeter() {
        // Source rect: x=100, y=100, w=100, h=80. RIGHT face at x=200.
        AssessmentNode source = node("src", 100, 100, 100, 80);
        AssessmentNode target = node("tgt", 400, 135, 50, 30); // target center (425, 150)
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{150, 140}, new double[]{200, 150},
                        new double[]{425, 150}), "", 1);

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(List.of(conn), List.of(source, target), false);

        assertEquals("M1: BP1 on RIGHT perimeter must NOT flag as non-orthogonal",
                0, result.count());
    }

    @Test
    public void m1_shouldNotFlagAsNonOrthogonal_whenFirstBpOnSourceTopPerimeter() {
        // Source rect: x=100, y=200, w=100, h=80. TOP face at y=200.
        AssessmentNode source = node("src", 100, 200, 100, 80);
        AssessmentNode target = node("tgt", 105, 0, 30, 30); // target center (120, 15) — same X as BP1
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{150, 240}, new double[]{120, 200},
                        new double[]{120, 15}), "", 1);

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(List.of(conn), List.of(source, target), false);

        assertEquals("M1: BP1 on TOP perimeter must NOT flag as non-orthogonal",
                0, result.count());
    }

    @Test
    public void m1_shouldNotFlagAsNonOrthogonal_whenFirstBpOnSourceBottomPerimeter() {
        // Source rect: x=100, y=100, w=100, h=80. BOTTOM face at y=180.
        AssessmentNode source = node("src", 100, 100, 100, 80);
        AssessmentNode target = node("tgt", 105, 400, 30, 30); // target center (120, 415)
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{150, 140}, new double[]{120, 180},
                        new double[]{120, 415}), "", 1);

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(List.of(conn), List.of(source, target), false);

        assertEquals("M1: BP1 on BOTTOM perimeter must NOT flag as non-orthogonal",
                0, result.count());
    }

    @Test
    public void m1_shouldFlagWhenBpExterior_andSegmentDiagonal() {
        // Control: BP1 well outside source rect, segment from source center to BP1 is diagonal.
        // Source rect: x=100, y=100, w=50, h=50. BP1=(300, 150) — well right of source.
        AssessmentNode source = node("src", 100, 100, 50, 50);
        AssessmentNode target = node("tgt", 400, 0, 50, 30);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                // Source center (125, 125) → BP1 (300, 150). dx=175, dy=25. angle ≈ 8.1° → non-orth.
                List.of(new double[]{125, 125}, new double[]{300, 150},
                        new double[]{425, 15}), "", 1);

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(List.of(conn), List.of(source, target), false);

        assertEquals("M1: BP1 exterior + diagonal segment must flag as non-orthogonal",
                1, result.count());
    }

    // ---- M1: Minimum-visible-length guard (Calibration.M1ManualOracle21, 2026-04-27) ----
    // When the visible (post-clip) terminal-segment length is below
    // VISIBLE_DIAGONAL_MIN_PX = 3.0, M1 must not flag the connection (sub-perceptible).
    // Calibrated against the V4 manual oracle (id-3b2665e3ff6840708dbed2b3d1415613)
    // where 20 of 21 violators had visible length 1.0–1.3px from BPs Archi stored
    // 1px off the perimeter face line.

    @Test
    public void m1_shouldNotFlag_whenBpExteriorButVisibleSegmentSubperceptible_lengthBelowThreshold() {
        // Source rect: x=200, y=100, w=100, h=50. LEFT face at x=200, source center (250, 125).
        // BP1 (199, 145) — 1px LEFT of LEFT face. Geometric segment (250,125)→(199,145):
        //   dx=51, dy=20, deviation 21.4° → predicate 2 fires.
        // Visible segment: clip at LEFT face → t=50/51, y=125+20·(50/51)≈144.61.
        //   Clip (200, 144.61), bp (199, 145). Visible length = √(1 + 0.1521) ≈ 1.07px.
        // Pre-guard: M1 = 1 (perimeter-skip miss + non-orth angle).
        // Post-guard (Calibration.M1ManualOracle21): visible 1.07 < 3.0 → suppressed → M1 = 0.
        AssessmentNode source = node("src", 200, 100, 100, 50);
        AssessmentNode target = node("tgt", 50, 0, 100, 30);  // far enough that target side won't flag
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                // Path: source center → BP1 (just outside LEFT, sub-perceptible diagonal)
                //       → BP2 (orthogonal to BP1) → target center.
                List.of(new double[]{250, 125}, new double[]{199, 145},
                        new double[]{199, 15}, new double[]{100, 15}), "", 1);

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(List.of(conn), List.of(source, target), false);

        assertEquals("M1 visible-length guard: sub-perceptible (~1.07px) diagonal must NOT flag",
                0, result.count());
    }

    @Test
    public void m1_shouldFlag_whenBpExteriorAndVisibleSegmentExceedsThreshold() {
        // Source rect: x=200, y=100, w=100, h=50. LEFT face at x=200, source center (250, 125).
        // BP1 (180, 145) — 20px LEFT of LEFT face. Geometric segment (250,125)→(180,145):
        //   dx=70, dy=20, deviation 15.95° → predicate 2 fires.
        // Visible segment: clip at LEFT face → t=50/70, y=125+20·(50/70)≈139.29.
        //   Clip (200, 139.29), bp (180, 145). Visible length = √(400 + 32.7) ≈ 20.80px.
        // Post-guard: visible 20.80 ≥ 3.0 → guard does NOT suppress → M1 = 1.
        AssessmentNode source = node("src", 200, 100, 100, 50);
        AssessmentNode target = node("tgt", 50, 0, 100, 30);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{250, 125}, new double[]{180, 145},
                        new double[]{180, 15}, new double[]{100, 15}), "", 1);

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(List.of(conn), List.of(source, target), false);

        assertEquals("M1 visible-length guard: visible diagonal ≥ 3.0px must STILL flag",
                1, result.count());
    }

    @Test
    public void m1_shouldNotFlag_whenBpInsideElement_evenForLongDiagonal() {
        // Source rect: x=200, y=100, w=100, h=50. BP1 (220, 130) STRICTLY inside source.
        // isOnOrInsideElement returns true → predicate 1 short-circuits → predicate 2/3 not
        // evaluated. Behaviour preserved post-guard (the new && visibleSegmentLength is
        // never reached). This test pins the AND-short-circuit ordering.
        AssessmentNode source = node("src", 200, 100, 100, 50);
        AssessmentNode target = node("tgt", 240, 190, 40, 30);  // target center (260, 205)
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                // Path: source center (250,125) → BP1 (220,130) [inside source] → BP2 (220,205)
                //       [outside target LEFT, orthogonal to target center] → target center.
                List.of(new double[]{250, 125}, new double[]{220, 130},
                        new double[]{220, 205}, new double[]{260, 205}), "", 1);

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(List.of(conn), List.of(source, target), false);

        assertEquals("M1: BP inside element must NOT flag — guard ordering preserved",
                0, result.count());
    }

    @Test
    public void m1_shouldFlag_targetSide_whenLastBpExteriorAndVisibleLengthExceedsThreshold() {
        // Symmetric to the source-side test above, but the diagonal is on the target terminal.
        // Target rect: x=200, y=100, w=100, h=50. LEFT face at x=200, target center (250, 125).
        // BP_last (180, 145) — 20px LEFT of target LEFT. Geometric segment (180,145)→(250,125):
        //   dx=70, dy=20, deviation 15.95° → predicate 2 fires.
        // Visible segment: clip at target LEFT → t=50/70 (parametrized from anchor=target
        //   center toward bp), y=125+20·(50/70)≈139.29.
        //   Clip (200, 139.29), bp (180, 145). Visible length ≈ 20.80px ≥ 3.0 → flag.
        AssessmentNode source = node("src", 50, 0, 100, 30);  // source center (100, 15)
        AssessmentNode target = node("tgt", 200, 100, 100, 50);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                // Source-side is orthogonal: (100,15) → (180,15) horizontal.
                // Target-side: (180,145) → (250,125) is the diagonal.
                List.of(new double[]{100, 15}, new double[]{180, 15},
                        new double[]{180, 145}, new double[]{250, 125}), "", 1);

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(List.of(conn), List.of(source, target), false);

        assertEquals("M1 target-side: visible diagonal ≥ 3.0px on target terminal must flag",
                1, result.count());
    }

    @Test
    public void m1_visibleSegmentLength_returnsExpectedValue_forKnownGeometry() {
        // Direct unit test of the visibleSegmentLength helper.
        // Element rect [200..300, 100..150] (LEFT face at x=200).
        // anchor (250, 125) inside; bp (199, 145) outside by 1px on LEFT.
        // Expected clip at (200, 144.6078...) → visible length √(1 + 0.1538...) ≈ 1.0744...px.
        AssessmentNode elem = node("e", 200, 100, 100, 50);
        double[] anchor = {250, 125};
        double[] bp = {199, 145};

        double visibleLen = LayoutQualityAssessor.visibleSegmentLength(anchor, bp, elem);

        // Tolerance 1e-3 is plenty for double-precision intersection arithmetic.
        assertEquals("visibleSegmentLength must return ~1.074px for known clip geometry",
                1.0744, visibleLen, 1e-3);

        // Also exercise the null-elem early return: must return +∞ so the guard
        // becomes a no-op in the 2-arg overload (legacy geometric-only behaviour
        // preserved — pre-existing b38/b55/b57/b60 tests depend on this).
        assertTrue("visibleSegmentLength returns +∞ when elem is null (legacy no-op)",
                Double.isInfinite(LayoutQualityAssessor.visibleSegmentLength(anchor, bp, null)));

        // Also exercise the bp-on-or-inside early return (defense-in-depth).
        double[] bpInside = {220, 130};
        assertEquals("visibleSegmentLength returns 0 when bp is on/inside elem (defense-in-depth)",
                0.0, LayoutQualityAssessor.visibleSegmentLength(anchor, bpInside, elem), 1e-9);
    }

    // ---- M2: Interior-termination detection (Task 1.5) ----

    @Test
    public void m2_shouldFlag_whenLastBpStrictlyInsideTarget_topQuadrant() {
        // Target rect: x=200, y=100, w=100, h=80. Last BP (220, 110) strictly inside.
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 200, 100, 100, 80);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{220, 25},
                        new double[]{220, 110}, new double[]{250, 140}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M2: BP_last strictly inside target — flag",
                1, result.interiorTerminationCount());
    }

    @Test
    public void m2_shouldFlag_whenFirstBpStrictlyInsideSource() {
        // Source rect: x=200, y=100, w=100, h=80. First BP (220, 110) strictly inside.
        AssessmentNode source = node("src", 200, 100, 100, 80);
        AssessmentNode target = node("tgt", 400, 200, 50, 30);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{250, 140}, new double[]{220, 110},
                        new double[]{425, 215}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M2: BP_first strictly inside source — flag",
                1, result.interiorTerminationCount());
    }

    @Test
    public void m2_shouldNotFlag_whenBpOnPerimeter_notStrictlyInside() {
        // BP exactly on perimeter line — strict-inside fails, M2 does NOT flag.
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 200, 100, 100, 80);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                // Last BP (200, 140) on LEFT face line of target — perimeter, not interior.
                List.of(new double[]{25, 25}, new double[]{200, 140}, new double[]{250, 140}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M2: BP on perimeter line is NOT strictly inside — do NOT flag",
                0, result.interiorTerminationCount());
    }

    @Test
    public void m2_shouldNotFlag_whenBpOutsideElement() {
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 200, 100, 100, 80);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                // Last BP (180, 140) OUTSIDE target rect.
                List.of(new double[]{25, 25}, new double[]{180, 140}, new double[]{250, 140}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M2: BP outside element — do NOT flag",
                0, result.interiorTerminationCount());
    }

    @Test
    public void m2_shouldFlagBothWhenBothInterior() {
        AssessmentNode source = node("src", 100, 100, 100, 80);
        AssessmentNode target = node("tgt", 400, 400, 100, 80);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                // BP1 inside source, BP_last inside target.
                List.of(new double[]{150, 140}, new double[]{120, 110},
                        new double[]{420, 410}, new double[]{450, 440}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M2: both BPs interior — single flag per connection (binary defect)",
                1, result.interiorTerminationCount());
    }

    @Test
    public void m2_shouldSkipShortPaths() {
        // path.size() == 2 (no intermediate BPs) — M2 does not apply.
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 200, 100, 100, 80);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{250, 140}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M2: 2-point path has no BP — do NOT flag",
                0, result.interiorTerminationCount());
    }

    // ---- M3: Zigzag/reversal detection (Task 1.6) ----

    @Test
    public void m3_shouldFlagSharedXReversal() {
        // Triple (100, 50) → (100, 30) → (100, 60). x=100 shared, Δy=-20, +30 — opposite signs > 1px.
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 200, 100, 50, 30);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{100, 50}, new double[]{100, 30},
                        new double[]{100, 60}, new double[]{225, 115}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M3: shared-X with opposite-sign Y deltas — flag",
                1, result.zigzagCount());
    }

    @Test
    public void m3_shouldFlagSharedYReversal() {
        // Triple (50, 100) → (30, 100) → (60, 100). y=100 shared, Δx=-20, +30 — opposite signs.
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 200, 100, 50, 30);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{50, 100}, new double[]{30, 100},
                        new double[]{60, 100}, new double[]{225, 115}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M3: shared-Y with opposite-sign X deltas — flag",
                1, result.zigzagCount());
    }

    @Test
    public void m3_shouldNotFlagMonotonicSequence() {
        // Triple (100, 50) → (100, 60) → (100, 70). x=100 shared, Δy=+10, +10 — same sign, no reversal.
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 200, 100, 50, 30);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{100, 50}, new double[]{100, 60},
                        new double[]{100, 70}, new double[]{225, 115}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M3: monotonic sequence (no reversal) — do NOT flag",
                0, result.zigzagCount());
    }

    @Test
    public void m3_shouldNotFlagWhenDeltaBelowTolerance() {
        // Triple (100, 50) → (100, 50.5) → (100, 51). Both deltas < 1px — colinear, no zigzag.
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 200, 100, 50, 30);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{100, 50}, new double[]{100, 50.5},
                        new double[]{100, 51}, new double[]{225, 115}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M3: deltas below 1px tolerance — do NOT flag",
                0, result.zigzagCount());
    }

    @Test
    public void m3_shouldFlagFixture2_apiMgmtCorpBankingC1() {
        // Live R5 C1 fixture triple (403,259) → (403,219) → (403,261). Δy=-40, +42 — flag.
        AssessmentNode source = node("apiMgmt", 641, 219, 303, 126);
        AssessmentNode target = node("corpBanking", 400, 1100, 120, 60);
        AssessmentConnection conn = new AssessmentConnection("c1", "apiMgmt", "corpBanking",
                List.of(new double[]{792, 282}, new double[]{641, 259}, new double[]{403, 259},
                        new double[]{403, 219}, new double[]{403, 261}, new double[]{437, 261},
                        new double[]{437, 1159}, new double[]{460, 1130}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M3: fixture 2 zigzag must flag",
                1, result.zigzagCount());
    }

    @Test
    public void m3_shouldCountBinaryPerConnection_evenWithMultipleZigzags() {
        // Two zigzag triples in one connection — count = 1 (binary defect per connection).
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 500, 500, 50, 30);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25},
                        new double[]{100, 50}, new double[]{100, 30}, new double[]{100, 60}, // zigzag 1
                        new double[]{200, 60}, new double[]{200, 100}, new double[]{200, 80}, // zigzag 2
                        new double[]{525, 515}), "", 1);

        LayoutAssessmentResult result = assessor.assess(List.of(source, target), List.of(conn), false);

        assertEquals("M3: multiple zigzags within same connection — count once",
                1, result.zigzagCount());
    }

    // ---- M4: Connection-vs-element-edge coincidence (Task 1.7) ----

    @Test
    public void m4_shouldFlagHorizontalSegmentNearElementTopEdge() {
        // Horizontal segment at y=148 vs foreign element TOP at y=150 (gap 2px, within 3px tolerance).
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 600, 0, 50, 50);
        AssessmentNode foreign = node("foreign", 200, 150, 200, 100); // TOP at y=150
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{100, 148}, new double[]{500, 148},
                        new double[]{625, 25}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(source, target, foreign), List.of(conn), false);

        assertEquals("M4: horizontal segment within 3px of foreign TOP edge — flag",
                1, result.connectionEdgeCoincidenceCount());
    }

    @Test
    public void m4_shouldNotFlagWhenGapExceedsTolerance() {
        // Horizontal segment at y=145 vs foreign TOP at y=150 (gap 5px, beyond 3px tolerance).
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 600, 0, 50, 50);
        AssessmentNode foreign = node("foreign", 200, 150, 200, 100);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{100, 145}, new double[]{500, 145},
                        new double[]{625, 25}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(source, target, foreign), List.of(conn), false);

        assertEquals("M4: 5px gap exceeds 3px tolerance — do NOT flag",
                0, result.connectionEdgeCoincidenceCount());
    }

    @Test
    public void m4_shouldFlagVerticalSegmentNearElementLeftEdge() {
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 0, 600, 50, 50);
        AssessmentNode foreign = node("foreign", 200, 200, 100, 200); // LEFT at x=200
        // Vertical at x=198 hugs LEFT face (gap 2px).
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{198, 50}, new double[]{198, 500},
                        new double[]{25, 625}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(source, target, foreign), List.of(conn), false);

        assertEquals("M4: vertical segment within 3px of foreign LEFT edge — flag",
                1, result.connectionEdgeCoincidenceCount());
    }

    @Test
    public void m4_shouldFlagWhenSegmentHugsOwnSourceFace() {
        // A segment running along its own source's RIGHT face FLAGS
        // (M4.RemoveSelfExclusion 2026-04-27 — self-exclusion removed; any element face is in scope).
        AssessmentNode source = node("src", 100, 100, 100, 100); // RIGHT at x=200, y-range [100,200]
        AssessmentNode target = node("tgt", 500, 0, 50, 30);
        // Vertical at x=200 hugs source's own RIGHT face — post-removal, this is in scope and flags.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{150, 150}, new double[]{200, 150}, new double[]{200, 100},
                        new double[]{525, 15}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(source, target), List.of(conn), false);

        assertEquals("M4: self-element coincidence flagged (no longer excluded)",
                1, result.connectionEdgeCoincidenceCount());
    }

    @Test
    public void m4_shouldFlagWhenSegmentHugsOwnTargetFace() {
        // A segment running along its own target's LEFT face FLAGS
        // (M4.RemoveSelfExclusion 2026-04-27).
        AssessmentNode source = node("src", 0, 0, 50, 30);
        AssessmentNode target = node("tgt", 200, 100, 100, 200); // LEFT at x=200, y-range [100,300]
        // Vertical at x=200 hugs target's own LEFT face — post-removal, this is in scope and flags.
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 15}, new double[]{200, 50},
                        new double[]{200, 250}, new double[]{250, 200}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(source, target), List.of(conn), false);

        assertEquals("M4: target's own face coincidence flagged",
                1, result.connectionEdgeCoincidenceCount());
    }

    @Test
    public void m4_grazedElementCount_enumeratesAllGrazedElements_whenTrunkGrazesThree() {
        // One horizontal trunk at y=148 grazes the TOP edge (y=150, gap 2px) of THREE foreign
        // elements spread along it. The rating-bearing connectionEdgeCoincidenceCount counts the
        // CONNECTION once (stops at the first graze); the informational
        // edgeCoincidenceGrazedElementCount enumerates all three. Removing either break in
        // countConnectionEdgeCoincidence collapses the enumeration to 1 (red-on-revert anchor).
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 600, 0, 50, 50);
        AssessmentNode fA = node("fA", 110, 150, 80, 100); // TOP y=150, x[110,190]
        AssessmentNode fB = node("fB", 250, 150, 80, 100); // TOP y=150, x[250,330]
        AssessmentNode fC = node("fC", 400, 150, 80, 100); // TOP y=150, x[400,480]
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{100, 148}, new double[]{500, 148},
                        new double[]{625, 25}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(source, target, fA, fB, fC), List.of(conn), true);

        assertEquals("M4: shipped per-connection count stays 1 (stops at first graze)",
                1, result.connectionEdgeCoincidenceCount());
        assertEquals("M4: per-element enumeration counts all three grazed elements",
                3, result.edgeCoincidenceGrazedElementCount());
        assertEquals("M4: connection-id violator key unchanged (the one connection)",
                Set.of("c1"), result.violatorIds().get("edgeCoincidence"));
        assertEquals("M4: grazed-element violator key lists all three element ids",
                Set.of("fA", "fB", "fC"),
                result.violatorIds().get("edgeCoincidenceGrazedElements"));
    }

    @Test
    public void m4_grazedElementCount_equalsConnectionCount_whenEachConnectionGrazesOne() {
        // Parity sanity: a connection grazing exactly one element → both counts agree.
        AssessmentNode source = node("src", 0, 0, 50, 50);
        AssessmentNode target = node("tgt", 600, 0, 50, 50);
        AssessmentNode foreign = node("foreign", 200, 150, 200, 100);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{100, 148}, new double[]{500, 148},
                        new double[]{625, 25}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(source, target, foreign), List.of(conn), false);

        assertEquals("M4: single graze → connection count 1",
                1, result.connectionEdgeCoincidenceCount());
        assertEquals("M4: single graze → enumeration also 1 (parity)",
                1, result.edgeCoincidenceGrazedElementCount());
    }

    @Test
    public void m4_grazedElementCount_includesOwnSourceFace_v1Default() {
        // The enumeration matches the shipped detector's scope: own source/target faces ARE in
        // scope (M4.RemoveSelfExclusion). A vertical segment hugging the connection's own source
        // RIGHT face is counted in edgeCoincidenceGrazedElementCount (no own-source carve-out in
        // v1). Pins the baseline a future carve-out decision would have to flip symmetrically.
        AssessmentNode source = node("src", 100, 100, 100, 100); // RIGHT at x=200, y[100,200]
        AssessmentNode target = node("tgt", 500, 0, 50, 30);
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{150, 150}, new double[]{200, 150}, new double[]{200, 100},
                        new double[]{525, 15}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(source, target), List.of(conn), true);

        assertEquals("M4: own-source graze flags the shipped count",
                1, result.connectionEdgeCoincidenceCount());
        assertEquals("M4: own-source graze IS enumerated (v1 default, no carve-out)",
                1, result.edgeCoincidenceGrazedElementCount());
        assertEquals("M4: the own source id is the grazed element",
                Set.of("src"), result.violatorIds().get("edgeCoincidenceGrazedElements"));
    }

    // ---- M5: Hub-port allocation quality (Task 1.8) ----

    @Test
    public void m5_shouldReportLowQuality_when4ConnectionsShareSameSlotOnLeftFace() {
        AssessmentNode hub = node("hub", 200, 100, 100, 200); // LEFT at x=200
        AssessmentNode peer1 = node("p1", 0, 100, 50, 30);
        AssessmentNode peer2 = node("p2", 0, 150, 50, 30);
        AssessmentNode peer3 = node("p3", 0, 200, 50, 30);
        AssessmentNode peer4 = node("p4", 0, 250, 50, 30);
        // 4 connections all entering hub LEFT face at slot Y=200.
        List<AssessmentConnection> conns = List.of(
                connToHubLeft("c1", peer1, hub, 200),
                connToHubLeft("c2", peer2, hub, 200),
                connToHubLeft("c3", peer3, hub, 200),
                connToHubLeft("c4", peer4, hub, 200));

        LayoutAssessmentResult result = assessor.assess(
                List.of(hub, peer1, peer2, peer3, peer4), conns, true);

        assertEquals("M5: 4 connections at 1 slot — quality = 0.25",
                0.25, result.hubPortQualityScore(), 1e-9);
        assertNotNull(result.hubPortQualityFaces());
        assertEquals("M5: one hub face detail expected", 1, result.hubPortQualityFaces().size());
    }

    @Test
    public void m5_shouldReportPerfectQuality_when4ConnectionsAtDistinctSlots() {
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode peer1 = node("p1", 0, 100, 50, 30);
        AssessmentNode peer2 = node("p2", 0, 150, 50, 30);
        AssessmentNode peer3 = node("p3", 0, 200, 50, 30);
        AssessmentNode peer4 = node("p4", 0, 250, 50, 30);
        // 4 connections at 4 distinct slot Ys: 130, 180, 230, 280.
        List<AssessmentConnection> conns = List.of(
                connToHubLeft("c1", peer1, hub, 130),
                connToHubLeft("c2", peer2, hub, 180),
                connToHubLeft("c3", peer3, hub, 230),
                connToHubLeft("c4", peer4, hub, 280));

        LayoutAssessmentResult result = assessor.assess(
                List.of(hub, peer1, peer2, peer3, peer4), conns, false);

        assertEquals("M5: 4 distinct slots — quality = 1.0",
                1.0, result.hubPortQualityScore(), 1e-9);
    }

    @Test
    public void m5_shouldNotCountFaceWithFewerThanMinConnections() {
        // Only 3 connections on the face — below M5_FACE_GUARD_MIN_CONNECTIONS=4. No hub face exists.
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode peer1 = node("p1", 0, 100, 50, 30);
        AssessmentNode peer2 = node("p2", 0, 150, 50, 30);
        AssessmentNode peer3 = node("p3", 0, 200, 50, 30);
        List<AssessmentConnection> conns = List.of(
                connToHubLeft("c1", peer1, hub, 200),
                connToHubLeft("c2", peer2, hub, 200),
                connToHubLeft("c3", peer3, hub, 200));

        LayoutAssessmentResult result = assessor.assess(
                List.of(hub, peer1, peer2, peer3), conns, false);

        assertEquals("M5: 3 connections (below threshold) — quality remains 1.0",
                1.0, result.hubPortQualityScore(), 1e-9);
    }

    @Test
    public void m5_shouldReportWorstFace_whenHubHasHealthyAndDegradedFace() {
        // IMPROVEMENT ASSERTION.
        // M5 view-aggregate is now min(faceQuality) — the WORST hub face — NOT the mean across
        // faces. A hub with one healthy face (LEFT 4/4 q1.0) and one degraded face (RIGHT 7/5
        // q0.714) must report the DEGRADED face (0.714 → "fair"), not the buoyed mean
        // (0.857 → "good"). Reproduces the live View-G IAM probe shape that the mean masked
        // (RIGHT 7 conns / 5 slots q0.71 averaged with LEFT 4/4 q1.0 → 0.86 "good").
        // Pre-fix (mean) this asserts 0.857 and FAILS; post-fix (min) it reports 0.714.
        AssessmentNode hub = node("hub", 200, 100, 100, 200); // x ∈ [200,300], y ∈ [100,300]
        List<AssessmentConnection> conns = new ArrayList<>();
        // RIGHT face (x=300): 7 conns, slots {120,120,160,200,240,280,280} → 5 distinct → 5/7 = 0.714.
        double[] rightYs = {120, 120, 160, 200, 240, 280, 280};
        for (int i = 0; i < rightYs.length; i++) conns.add(hubFaceSpoke("r" + i, hub, 300, rightYs[i]));
        // LEFT face (x=200): 4 conns, slots {120,180,240,290} → 4 distinct → 1.0.
        double[] leftYs = {120, 180, 240, 290};
        for (int i = 0; i < leftYs.length; i++) conns.add(hubFaceSpoke("l" + i, hub, 200, leftYs[i]));

        LayoutAssessmentResult result = assessor.assess(List.of(hub), conns, true);

        assertEquals("M5 min-aggregate: worst hub face (RIGHT 7/5 = 0.714) drives the score, "
                + "NOT the buoyed mean 0.857",
                5.0 / 7.0, result.hubPortQualityScore(), 1e-9);
        assertEquals("M5: two hub faces reported", 2,
                result.hubPortQualityFaces().stream().filter(d -> "hub".equals(d.elementId())).count());
    }

    // Assessor.Redesign code-review H3+M2 (2026-04-27): when terminal BPs are exterior to the
    // element rect (M1-non-orthogonal cases), M5 must STILL attribute the connection to the
    // visible face via segment clip-point. Pre-fix, exterior BPs returned inferFace==null
    // and silently dropped out of M5, masking hub congestion on real models.
    @Test
    public void m5_shouldUseClipPoint_whenBpIsExteriorToHubFace() {
        // Hub at (200, 100, 100, 200) — center (250, 200), LEFT face x=200, y range 100-300.
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode peer1 = node("p1", 0, 50, 50, 30);
        AssessmentNode peer2 = node("p2", 0, 150, 50, 30);
        AssessmentNode peer3 = node("p3", 0, 250, 50, 30);
        AssessmentNode peer4 = node("p4", 0, 350, 50, 30);
        // Each peer→hub connection has BP1 exterior — well left of hub LEFT face.
        // Segment peer-center → BP1 lands on hub LEFT at clipY=200 for ALL four peers
        // (deliberate construction: BP1 is co-linear with hub-center → clip slot = 200).
        // Pre-fix: inferFace returned null on these exterior BPs and M5 reported quality=1.0.
        // Post-fix: clipSegmentToFace finds LEFT face at slot=200 → 4 conns / 1 slot = 0.25.
        // BPs chosen so hub-center→BP segment passes through (200, 200) on LEFT face.
        // For peer connections going hub→peer: hub center (250, 200), BP at (-100, 200) — exterior.
        // dx=-350, dy=0 → exits LEFT face at x=200, y=200.
        AssessmentConnection c1 = new AssessmentConnection("c1", "hub", "p1",
                List.of(new double[]{250, 200}, new double[]{-100, 200},
                        new double[]{25, 65}), "", 1);
        AssessmentConnection c2 = new AssessmentConnection("c2", "hub", "p2",
                List.of(new double[]{250, 200}, new double[]{-200, 200},
                        new double[]{25, 165}), "", 1);
        AssessmentConnection c3 = new AssessmentConnection("c3", "hub", "p3",
                List.of(new double[]{250, 200}, new double[]{-50, 200},
                        new double[]{25, 265}), "", 1);
        AssessmentConnection c4 = new AssessmentConnection("c4", "hub", "p4",
                List.of(new double[]{250, 200}, new double[]{-300, 200},
                        new double[]{25, 365}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(hub, peer1, peer2, peer3, peer4),
                List.of(c1, c2, c3, c4), true);

        assertEquals("M5 clip-point fallback: 4 exterior BPs, all clip at LEFT slot=200 — quality 0.25",
                0.25, result.hubPortQualityScore(), 1e-6);
        assertNotNull(result.hubPortQualityFaces());
        LayoutAssessmentResult.HubFaceDetail leftFace = result.hubPortQualityFaces().stream()
                .filter(d -> "hub".equals(d.elementId()) && "LEFT".equals(d.face()))
                .findFirst().orElse(null);
        assertNotNull("hub LEFT face must be reported via clip-point", leftFace);
        assertEquals(4, leftFace.connectionsOnFace());
        assertEquals(1, leftFace.distinctSlots());
    }

    @Test
    public void m5_shouldUseClipPoint_distinctSlotsWhenSegmentsExitAtDifferentY() {
        // Same hub, but each connection's BP is at a distinct Y → clip-point on LEFT face
        // lands at distinct slot Y values (not equal, but distinct).
        AssessmentNode hub = node("hub", 200, 100, 100, 200); // center (250, 200)
        AssessmentNode p1 = node("p1", 0, 110, 50, 30);
        AssessmentNode p2 = node("p2", 0, 160, 50, 30);
        AssessmentNode p3 = node("p3", 0, 210, 50, 30);
        AssessmentNode p4 = node("p4", 0, 260, 50, 30);
        // Segment from hub-center (250, 200) to BP (100, bpY) exits LEFT (x=200) at
        // clipY = 200 + (1/3)*(bpY - 200).  For bpY ∈ {125, 175, 225, 275} this gives
        // clipY ∈ {175, 191.67, 208.33, 225} — 4 distinct slots beyond 1px tolerance.
        AssessmentConnection c1 = new AssessmentConnection("c1", "hub", "p1",
                List.of(new double[]{250, 200}, new double[]{100, 125},
                        new double[]{25, 125}), "", 1);
        AssessmentConnection c2 = new AssessmentConnection("c2", "hub", "p2",
                List.of(new double[]{250, 200}, new double[]{100, 175},
                        new double[]{25, 175}), "", 1);
        AssessmentConnection c3 = new AssessmentConnection("c3", "hub", "p3",
                List.of(new double[]{250, 200}, new double[]{100, 225},
                        new double[]{25, 225}), "", 1);
        AssessmentConnection c4 = new AssessmentConnection("c4", "hub", "p4",
                List.of(new double[]{250, 200}, new double[]{100, 275},
                        new double[]{25, 275}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(hub, p1, p2, p3, p4), List.of(c1, c2, c3, c4), false);

        assertEquals("M5 clip-point fallback: 4 distinct exit slots — quality 1.0",
                1.0, result.hubPortQualityScore(), 1e-6);
    }

    @Test
    public void m5_shouldDetectBottomFaceHub() {
        // 4 connections at same slot on hub BOTTOM face (slot = X coordinate).
        AssessmentNode hub = node("hub", 100, 100, 200, 100); // BOTTOM at y=200
        AssessmentNode peer1 = node("p1", 0, 400, 50, 30);
        AssessmentNode peer2 = node("p2", 100, 400, 50, 30);
        AssessmentNode peer3 = node("p3", 200, 400, 50, 30);
        AssessmentNode peer4 = node("p4", 300, 400, 50, 30);
        // All 4 connections enter BOTTOM face at slot X=200.
        List<AssessmentConnection> conns = List.of(
                connFromHubBottom("c1", hub, peer1, 200),
                connFromHubBottom("c2", hub, peer2, 200),
                connFromHubBottom("c3", hub, peer3, 200),
                connFromHubBottom("c4", hub, peer4, 200));

        LayoutAssessmentResult result = assessor.assess(
                List.of(hub, peer1, peer2, peer3, peer4), conns, false);

        assertEquals("M5: BOTTOM face with 4 connections at same X-slot — quality = 0.25",
                0.25, result.hubPortQualityScore(), 1e-9);
    }

    /** Builds a connection peer→hub LEFT face at the given slot Y. Path: peer center → BP on LEFT face → hub center. */
    private static AssessmentConnection connToHubLeft(String id, AssessmentNode peer,
                                                      AssessmentNode hub, double slotY) {
        double pcx = peer.x() + peer.width() / 2.0;
        double pcy = peer.y() + peer.height() / 2.0;
        double hcx = hub.x() + hub.width() / 2.0;
        double hcy = hub.y() + hub.height() / 2.0;
        return new AssessmentConnection(id, peer.id(), hub.id(),
                List.of(new double[]{pcx, pcy},
                        new double[]{hub.x(), slotY},
                        new double[]{hcx, hcy}),
                "", 1);
    }

    /**
     * Builds a hub→(phantom peer) spoke whose source-side terminal bendpoint sits exactly ON the
     * hub face perimeter line (faceX, slotY) → that face, slot = slotY. On this 3-point path the
     * face BP is {@code path.get(1)}, which serves as BOTH the source terminal and (as
     * {@code path.get(size-2)}) the target terminal; the asymmetry is harmless because the phantom
     * target id is not in the node list, so its terminal resolves to a null node and is skipped.
     * Only the hub-side terminal contributes to M5. Used to construct multi-face hub fixtures.
     */
    private static AssessmentConnection hubFaceSpoke(String id, AssessmentNode hub,
                                                      double faceX, double slotY) {
        double hcx = hub.x() + hub.width() / 2.0;
        double hcy = hub.y() + hub.height() / 2.0;
        double farX = faceX < hcx ? faceX - 200 : faceX + 200;
        return new AssessmentConnection(id, hub.id(), id + "_peer",
                List.of(new double[]{hcx, hcy}, new double[]{faceX, slotY},
                        new double[]{farX, slotY}),
                "", 1);
    }

    /** Builds a connection hub→peer BOTTOM face at the given slot X. Path: hub center → BP on BOTTOM face → peer center. */
    private static AssessmentConnection connFromHubBottom(String id, AssessmentNode hub,
                                                           AssessmentNode peer, double slotX) {
        double hcx = hub.x() + hub.width() / 2.0;
        double hcy = hub.y() + hub.height() / 2.0;
        double pcx = peer.x() + peer.width() / 2.0;
        double pcy = peer.y() + peer.height() / 2.0;
        return new AssessmentConnection(id, hub.id(), peer.id(),
                List.of(new double[]{hcx, hcy},
                        new double[]{slotX, hub.y() + hub.height()},
                        new double[]{pcx, pcy}),
                "", 1);
    }

    // ---- R8: Corridor Utilisation ----

    @Test
    public void r8_emptyConnections_returnsVacuous1() {
        LayoutAssessmentResult result = assessor.assess(
                List.of(group("g1", 0, 0, 100, 100), group("g2", 200, 0, 100, 100)),
                List.of(), false);
        assertEquals("R8: no connections → vacuous 1.0",
                1.0, result.corridorUtilisationScore(), 1e-9);
    }

    @Test
    public void r8_singleOccupantChannel_isSkipped_vacuous1() {
        // One vertical segment at x=150 in a corridor between two group walls; n=1 < 2 → skip.
        AssessmentNode left = group("left", 0, 0, 100, 400);
        AssessmentNode right = group("right", 350, 0, 100, 400);
        LayoutAssessmentResult result = assessor.assess(
                List.of(left, right),
                List.of(vSeg("c1", "left", "right", 150, 100, 200)), false);
        assertEquals("R8: single-occupant corridor skipped → vacuous 1.0",
                1.0, result.corridorUtilisationScore(), 1e-9);
    }

    @Test
    public void r8_singleChannel_fourOccupants_returnsSpanOverAvailable() {
        // Walls: left.right_edge=100, right.left_edge=350.
        // available = 350 - 100 - 2*MIN_CLEARANCE_PX(=10) = 230.
        // 4 verticals at x ∈ {150, 180, 220, 280}; span = 280-150 = 130.
        // spread_ratio = 130/230.
        AssessmentNode left = group("left", 0, 0, 100, 400);
        AssessmentNode right = group("right", 350, 0, 100, 400);
        List<AssessmentConnection> conns = List.of(
                vSeg("c1", "left", "right", 150, 100, 200),
                vSeg("c2", "left", "right", 180, 100, 200),
                vSeg("c3", "left", "right", 220, 100, 200),
                vSeg("c4", "left", "right", 280, 100, 200));
        LayoutAssessmentResult result = assessor.assess(
                List.of(left, right), conns, true);
        assertEquals("R8: span=130, available=230 → spread_ratio = 130/230",
                130.0 / 230.0, result.corridorUtilisationScore(), 1e-9);
        assertEquals("R8: one corridor detail expected when includeViolatorIds=true",
                1, result.corridorUtilisationChannels().size());
        LayoutAssessmentResult.CorridorUtilisationDetail d =
                result.corridorUtilisationChannels().get(0);
        assertEquals(0, d.axis());
        assertEquals(4, d.occupantCount());
        assertEquals(130.0, d.span(), 1e-9);
        assertEquals(230.0, d.available(), 1e-9);
    }

    @Test
    public void r8_narrowCorridor_spreadRatioClampedToOne() {
        // Walls 24 px apart: left.right=100, right.left=124.
        // available = 124 - 100 - 2*MIN_CLEARANCE_PX(=10) = 4 px.
        // 2 verticals at x ∈ {109, 120}; span = 11 px > available 4 px.
        // Pre-clamp ratio = 11/4 = 2.75; post-clamp = 1.0 (per code-review M1).
        AssessmentNode left = group("left", 0, 0, 100, 400);
        AssessmentNode right = group("right", 124, 0, 100, 400);
        List<AssessmentConnection> conns = List.of(
                vSeg("c1", "left", "right", 109, 100, 200),
                vSeg("c2", "left", "right", 120, 100, 200));
        LayoutAssessmentResult result = assessor.assess(
                List.of(left, right), conns, true);
        assertEquals("R8: narrow corridor (span > available) clamped to 1.0",
                1.0, result.corridorUtilisationScore(), 1e-9);
        assertEquals("R8: per-channel detail records the clamped value",
                1.0, result.corridorUtilisationChannels().get(0).spreadRatio(), 1e-9);
    }

    @Test
    public void r8_multiChannel_returnsOccupantCountWeightedMean() {
        // Corridor A: walls aL.right=50, aR.left=200 → available = 130. 2 occupants at
        //   x ∈ {100, 165} → span 65 → ratio 0.5.
        // Corridor B: walls bL.right=450, bR.left=600 → available = 130. 4 occupants at
        //   x ∈ {500, 510, 520, 526} → span 26 → ratio 0.2.
        // Weighted mean = (0.5*2 + 0.2*4) / 6 = 1.8 / 6 = 0.3.
        AssessmentNode aL = group("aL", 0, 0, 50, 400);
        AssessmentNode aR = group("aR", 200, 0, 50, 400);
        AssessmentNode bL = group("bL", 400, 0, 50, 400);
        AssessmentNode bR = group("bR", 600, 0, 50, 400);
        List<AssessmentConnection> conns = List.of(
                vSeg("a1", "aL", "aR", 100, 100, 200),
                vSeg("a2", "aL", "aR", 165, 100, 200),
                vSeg("b1", "bL", "bR", 500, 100, 200),
                vSeg("b2", "bL", "bR", 510, 100, 200),
                vSeg("b3", "bL", "bR", 520, 100, 200),
                vSeg("b4", "bL", "bR", 526, 100, 200));
        LayoutAssessmentResult result = assessor.assess(
                List.of(aL, aR, bL, bR), conns, false);
        assertEquals("R8: occupant-count-weighted mean = (0.5*2 + 0.2*4) / 6 = 0.3",
                0.3, result.corridorUtilisationScore(), 1e-9);
    }

    /**
     * Builds a connection whose pathPoints contain ONE long vertical segment at x=segX
     * spanning y ∈ [segYStart, segYEnd]. Pre/post diagonals (dx=5, dy=5) avoid spurious
     * axis-parallel detection on the approach/exit pairs.
     */
    private static AssessmentConnection vSeg(String id, String srcId, String tgtId,
                                              double segX, double segYStart, double segYEnd) {
        return new AssessmentConnection(id, srcId, tgtId,
                List.of(new double[]{segX - 5, segYStart - 5},
                        new double[]{segX, segYStart},
                        new double[]{segX, segYEnd},
                        new double[]{segX + 5, segYEnd + 5}),
                "", 1);
    }

    // ---- M6: Two-dimensional rating + tier promotions (Task 1.9) ----

    @Test
    public void m6_layoutCleanRoutingPoor_shouldRateOverallPoor() {
        // 4-node grid for clean alignment + spacing — layout dimension passes excellent.
        // One connection introduces an interior-termination → routing Tier 1R poor.
        AssessmentNode a = node("a", 0, 0, 100, 50);
        AssessmentNode b = node("b", 200, 0, 100, 50);
        AssessmentNode c = node("c", 0, 200, 100, 50);
        AssessmentNode d = node("d", 200, 200, 100, 50);
        AssessmentConnection interior = new AssessmentConnection("conn", "a", "d",
                // BP_last (220, 210) strictly inside d (rect 200,200,100,50).
                List.of(new double[]{50, 25}, new double[]{220, 25},
                        new double[]{220, 210}, new double[]{250, 225}), "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(a, b, c, d), List.of(interior), false);

        assertEquals("M6: routing Tier-1R interior termination — routingRating poor",
                "poor", result.routingRating());
        assertEquals("M6: clean grid — layoutRating excellent",
                "excellent", result.layoutRating());
        assertEquals("M6: overall = worse(layout, routing) = poor",
                "poor", result.overallRating());
    }

    @Test
    public void m6_layoutPoorRoutingClean_shouldRateOverallPoor() {
        // Layout-Tier-1L defect (sibling overlap), no routing defects.
        AssessmentNode a = node("a", 0, 0, 100, 50);
        AssessmentNode b = node("b", 50, 25, 100, 50); // overlaps a (sibling)

        LayoutAssessmentResult result = assessor.assess(List.of(a, b), List.of(), false);

        assertEquals("M6: layout Tier-1L overlap → layoutRating poor (binary >0 → poor)",
                "poor", result.layoutRating());
        assertEquals("M6: routing clean — routingRating excellent",
                "excellent", result.routingRating());
        assertEquals("M6: overall = worse(poor, excellent) = poor",
                "poor", result.overallRating());
    }

    @Test
    public void m6_bothDimensionsClean_shouldRateExcellent() {
        // Clean layout + no connections → both dimensions excellent.
        AssessmentNode a = node("a", 0, 0, 100, 50);
        AssessmentNode b = node("b", 200, 0, 100, 50);

        LayoutAssessmentResult result = assessor.assess(List.of(a, b), List.of(), false);

        assertEquals("M6: clean layout, no connections — layoutRating excellent",
                "excellent", result.layoutRating());
        assertEquals("M6: no connections — routingRating excellent",
                "excellent", result.routingRating());
        assertEquals("M6: both dimensions clean — overall excellent",
                "excellent", result.overallRating());
    }

    @Test
    public void m6_promotion_labelOverlapShouldCapRoutingAtFair() {
        // Pre-redesign: labelOverlap → Tier 3 cap "good". Under M6 promotion: Tier 2R cap "fair".
        // labelOverlapCount=3 produces breakdown "fair" (per existing thresholds). Under M6
        // Tier 2R cap fair — routing must rate "fair", NOT "good" (which is what pre-redesign
        // Tier 3 cap good would have produced).
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 3, 0, 0, 0, 5, false,
                0, 0, 0, 0,
                0, 0, 0, 1.0);

        assertEquals("M6 promotion: labelOverlap=3 → breakdown fair → routing Tier 2R fair",
                "fair", result.routingRating());
    }

    @Test
    public void m6_promotion_parentLabelObscuredShouldDropLayoutFromExcellent() {
        // M6 promotion: parentLabelObscuredCount info → Tier 1L. Any > 0 → layoutRating poor.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 5, false,
                0, 1, 0, 0,
                0, 0, 0, 1.0);

        assertEquals("M6 promotion: parentLabelObscuredCount > 0 — layoutRating poor",
                "poor", result.layoutRating());
        assertEquals("M6: layout poor dominates — overall poor",
                "poor", result.rating());
    }

    @Test
    public void m6_promotion_labelTruncationShouldCapRoutingAtFair() {
        // M6 promotion: labelTruncationCount info → Tier 2R cap fair.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 5, false,
                0, 0, 0, 1,
                0, 0, 0, 1.0);

        // labelTruncations rated "fair" → contributes Tier 2R level 2 → routing capped at fair.
        assertEquals("M6 promotion: labelTruncations contributes Tier 2R fair",
                "fair", result.routingRating());
    }

    @Test
    public void m6_demotion_crossingsShouldNoLongerCapAtFair() {
        // Pre-redesign: many crossings → Tier 2 cap fair. Under M6: Tier 3R cap good.
        // 25 crossings, low PT, no other defects. Pre-redesign: fair. Under M6: good.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 25, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0,
                0, 0, 0, 1.0);

        // Density 25/10 = 2.5 → falls in CROSSING_RATIO_MODERATE territory which is "fair" per metric.
        // Under M6 Tier 3R cap good, the routing-tier contribution caps at good.
        assertEquals("M6 demotion: crossings cap routing at good (Tier 3R, not Tier 2)",
                "good", result.routingRating());
    }

    // ---- M2: assess_withB53Fields_shouldChangeRating_underM6Promotions (REPLACES old test) ----
    //
    // The previous `assess_withB53Fields_shouldNotChangeRating` (REPLACED 2026-04-26) asserted
    // that those informational fields had NO rating impact. Under M6 the OPPOSITE is required:
    // parentLabelObscuredCount and labelTruncationCount are explicitly promoted (Tier 1L and
    // Tier 2R respectively) — when they're nonzero the rating MUST move.
    @Test
    public void assess_withB53Fields_shouldChangeRating_underM6Promotions() {
        // Build a layout where parentLabelObscured > 0 will drop layoutRating to poor.
        // Two siblings, parent group has child whose label position obscures parent's.
        // We synthesise this via direct rating call to avoid relying on assess()-level
        // detection mechanics (those are covered by detectParentLabelObscuredByChild_* tests).
        LayoutQualityAssessor.RatingResult cleanResult = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 5, false,
                0, 0, 0, 0,
                0, 0, 0, 1.0);
        LayoutQualityAssessor.RatingResult promotedResult = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 5, false,
                0, 1, 0, 1,
                0, 0, 0, 1.0);

        assertEquals("Baseline clean — overall excellent", "excellent", cleanResult.rating());
        assertNotEquals("Promoted parentLabelObscured + labelTruncation must change rating",
                cleanResult.rating(), promotedResult.rating());
    }

    // ---- A-gated M4 escalation ----
    //
    // M4 connectionEdgeCoincidence is Tier-2R cap-fair UNTIL the count reaches
    // EDGE_COINCIDENCE_EGREGIOUS_MAX (7 = Retail Bank View G), at which point it escalates to
    // Tier-1R so overall reads "poor" (ratified guardrail beside the Lever-B router fix).
    // Synthetic counts isolate the escalation logic in computeRoutingTierLevel; detection is
    // covered by the m4_* tests above. No existing fixture has M4 >= 7, so no prior overall pin
    // moves (Task-1.3 re-pin reduces to "verify none break").
    @Test
    public void aGated_m4EgregiousCount_escalatesOverallToPoor() {
        // Clean baseline (same arg shape as assess_withB53Fields cleanResult = "excellent"),
        // varying ONLY the M4 connectionEdgeCoincidenceCount (17th arg).
        LayoutQualityAssessor.RatingResult m4Good = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 2, 1.0);     // M4=2 (<= GOOD_MAX) -> "good" sub-rating
        LayoutQualityAssessor.RatingResult m4FairMax = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 5, 1.0);     // M4=5 (FAIR_MAX) -> "fair" sub-rating, cap-fair
        LayoutQualityAssessor.RatingResult m4PoorBelowThreshold = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 6, 1.0);     // M4=6 -> "poor" sub-rating but < EGREGIOUS -> cap-fair
        LayoutQualityAssessor.RatingResult m4Egregious = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 7, 1.0);     // M4=7 (>= EGREGIOUS) -> escalate to Tier-1R -> poor

        assertEquals("M4=2 good sub-rating -> overall good (no escalation)",
                "good", m4Good.rating());
        assertEquals("M4=5 cap-fair -> overall fair (baseline behaviour preserved)",
                "fair", m4FairMax.rating());
        // The crux of the objection: M4=6 is already 'poor' in the breakdown but stays
        // masked at overall=fair (cap-fair) because 6 < EGREGIOUS. Intentionally preserved for
        // the common forced-hug case.
        assertEquals("M4=6 poor sub-rating but below egregious -> overall still fair (cap-fair)",
                "fair", m4PoorBelowThreshold.rating());
        assertEquals("M4=6 breakdown is 'poor' even though overall is 'fair'",
                "poor", m4PoorBelowThreshold.breakdown().get("connectionEdgeCoincidence"));
        // The fix: an egregious count (>= 7, the Retail Bank View G level) escalates to Tier-1R.
        assertEquals("M4=7 egregious -> overall poor (A-gated escalation)",
                "poor", m4Egregious.rating());
        assertEquals("M4=7 routing tier is poor", "poor", m4Egregious.routingRating());
        assertEquals("M4=7 layout tier unaffected (still excellent)",
                "excellent", m4Egregious.layoutRating());
    }

    // ---- Hub-to-neighbour crowding / clearance (2026-06-25) ----

    /**
     * Hub (320×160 at origin) plus {@code spokeCount} sibling spokes forming a row {@code gap}px
     * below the hub's bottom edge, each x-overlapping the hub. Mirrors the live 2.3 resize-tight
     * config (hub 320×160, 7 inbound spokes ~45px below).
     */
    private static List<AssessmentNode> hubWithBottomSpokeRow(int spokeCount, double gap) {
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(node("hub", 0, 0, 320, 160));
        double y = 160 + gap;
        for (int i = 0; i < spokeCount; i++) {
            nodes.add(node("s" + i, i * 45.0, y, 40, 40));
        }
        return nodes;
    }

    /** One inbound connection per spoke (spoke → hub). Paths are clean vertical segments. */
    private static List<AssessmentConnection> spokeConnections(int spokeCount, double gap) {
        List<AssessmentConnection> conns = new ArrayList<>();
        double spokeTop = 160 + gap;
        for (int i = 0; i < spokeCount; i++) {
            double sx = i * 45.0 + 20;
            conns.add(new AssessmentConnection("c" + i, "s" + i, "hub",
                    List.of(new double[]{sx, spokeTop}, new double[]{sx, 160}), "", 1));
        }
        return conns;
    }

    @Test
    public void computeHubNeighbourCrowding_crowdedBottomRow_reportsClearanceAndCrowded() {
        LayoutQualityAssessor.HubNeighbourCrowdingResult result =
                assessor.computeHubNeighbourCrowding(spokeConnections(7, 45),
                        hubWithBottomSpokeRow(7, 45));
        assertEquals(45.0, result.minClearance(), 0.001);
        assertTrue("hub edge 45px from a 7-spoke row is crowded", result.crowded());
    }

    @Test
    public void computeHubNeighbourCrowding_sparseRow_reportsClearanceNotCrowded() {
        LayoutQualityAssessor.HubNeighbourCrowdingResult result =
                assessor.computeHubNeighbourCrowding(spokeConnections(7, 90),
                        hubWithBottomSpokeRow(7, 90));
        assertEquals(90.0, result.minClearance(), 0.001);
        assertFalse("hub edge 90px from the row keeps a readable corridor", result.crowded());
    }

    @Test
    public void computeHubNeighbourCrowding_belowHubThreshold_returnsSentinel() {
        // 4 connections < HUB_DETECTION_THRESHOLD (5) → not a hub → sentinel, not crowded.
        LayoutQualityAssessor.HubNeighbourCrowdingResult result =
                assessor.computeHubNeighbourCrowding(spokeConnections(4, 30),
                        hubWithBottomSpokeRow(4, 30));
        assertEquals(LayoutQualityAssessor.NO_HUB_NEIGHBOUR_CLEARANCE,
                result.minClearance(), 0.001);
        assertFalse(result.crowded());
    }

    @Test
    public void computeHubNeighbourCrowding_fewerThanKOnAnyFace_returnsSentinel() {
        // 6-connection hub but only 2 neighbours per face (< CROWDING_MIN_ADJACENT_K=3): no face
        // forms a row, so over-flag discipline yields the sentinel even though clearances are small.
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(node("hub", 0, 0, 320, 160));
        nodes.add(node("b0", 0, 200, 40, 40));
        nodes.add(node("b1", 60, 200, 40, 40));
        nodes.add(node("t0", 0, -60, 40, 40));
        nodes.add(node("t1", 60, -60, 40, 40));
        nodes.add(node("r0", 360, 0, 40, 40));
        nodes.add(node("r1", 360, 60, 40, 40));
        List<double[]> p = List.of(new double[]{0, 0}, new double[]{1, 1});
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("cb0", "b0", "hub", p, "", 1),
                new AssessmentConnection("cb1", "b1", "hub", p, "", 1),
                new AssessmentConnection("ct0", "t0", "hub", p, "", 1),
                new AssessmentConnection("ct1", "t1", "hub", p, "", 1),
                new AssessmentConnection("cr0", "r0", "hub", p, "", 1),
                new AssessmentConnection("cr1", "r1", "hub", p, "", 1));
        LayoutQualityAssessor.HubNeighbourCrowdingResult result =
                assessor.computeHubNeighbourCrowding(conns, nodes);
        assertEquals(LayoutQualityAssessor.NO_HUB_NEIGHBOUR_CLEARANCE,
                result.minClearance(), 0.001);
        assertFalse(result.crowded());
    }

    @Test
    public void computeHubNeighbourCrowding_nestedChildren_excludedAsContainment() {
        // Children are positioned BELOW the hub (y=205, 45px gap) so they WOULD form a crowded
        // 6-spoke BOTTOM row if treated as neighbours — only the containment-pair exclusion keeps
        // this from firing. This genuinely exercises the isContainmentPair guard (a child nested
        // INSIDE the hub would be filtered by face-classification before the guard is reached).
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(node("hub", 0, 0, 320, 160));
        List<AssessmentConnection> conns = new ArrayList<>();
        List<double[]> p = List.of(new double[]{0, 0}, new double[]{1, 1});
        for (int i = 0; i < 6; i++) {
            nodes.add(childNode("ch" + i, i * 50.0, 205, 40, 40, "hub"));
            conns.add(new AssessmentConnection("c" + i, "hub", "ch" + i, p, "", 1));
        }
        LayoutQualityAssessor.HubNeighbourCrowdingResult result =
                assessor.computeHubNeighbourCrowding(conns, nodes);
        assertEquals("containment children must be excluded → no spoke row → sentinel",
                LayoutQualityAssessor.NO_HUB_NEIGHBOUR_CLEARANCE, result.minClearance(), 0.001);
        assertFalse(result.crowded());
    }

    @Test
    public void computeRatingWithBreakdown_hubCrowded_capsOverallAtFair() {
        // Otherwise-pristine inputs rate excellent; the crowded flag must cap overall at fair (Tier 2L).
        LayoutQualityAssessor.RatingResult clean = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false);
        LayoutQualityAssessor.RatingResult crowded = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, true);
        assertEquals("clean baseline rates excellent", "excellent", clean.rating());
        assertEquals("crowded breakdown entry is fair", "fair",
                crowded.breakdown().get("hubNeighbourCrowding"));
        assertEquals("crowding caps overall at fair (not good/excellent)", "fair", crowded.rating());
        assertEquals("crowding is a layout-tier defect", "fair", crowded.layoutRating());
    }

    @Test
    public void computeRatingWithBreakdown_18ArgOverload_defaultsCrowdingPassByteIdentical() {
        // The 18-arg overload must delegate with crowded=false → entry "pass", rating unchanged.
        LayoutQualityAssessor.RatingResult viaEighteen = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0);
        LayoutQualityAssessor.RatingResult viaNineteenFalse = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false);
        assertEquals(viaNineteenFalse.rating(), viaEighteen.rating());
        assertEquals("pass", viaEighteen.breakdown().get("hubNeighbourCrowding"));
    }

    @Test
    public void crowdingFloor_boundaryBehaviour_pinsCalibration() {
        // Calibration pin for CROWDING_FLOOR_PX: a row exactly at the floor is NOT crowded;
        // one px inside it is. Locks the live-calibrated constant against silent drift.
        assertFalse("clearance == floor is not crowded (>= floor)",
                assessor.computeHubNeighbourCrowding(
                        spokeConnections(5, LayoutQualityAssessor.CROWDING_FLOOR_PX),
                        hubWithBottomSpokeRow(5, LayoutQualityAssessor.CROWDING_FLOOR_PX)).crowded());
        assertTrue("clearance one px inside the floor is crowded",
                assessor.computeHubNeighbourCrowding(
                        spokeConnections(5, LayoutQualityAssessor.CROWDING_FLOOR_PX - 1),
                        hubWithBottomSpokeRow(5, LayoutQualityAssessor.CROWDING_FLOOR_PX - 1)).crowded());
        assertEquals(60.0, LayoutQualityAssessor.CROWDING_FLOOR_PX, 0.001);
    }

    @Test
    public void assess_crowdedHub_capsRatingAndReportsClearance() {
        LayoutAssessmentResult result =
                assessor.assess(hubWithBottomSpokeRow(7, 45), spokeConnections(7, 45), false);
        assertEquals(45.0, result.hubNeighbourClearanceMin(), 0.001);
        assertEquals("fair", result.ratingBreakdown().get("hubNeighbourCrowding"));
        // Otherwise-clean fixture (no overlaps/crossings/routing defects) → crowding caps it at
        // exactly fair (Tier 2L cap-fair, never poor).
        assertEquals("crowding caps overall at exactly fair", "fair", result.overallRating());
    }

    @Test
    public void assess_sparseHub_doesNotFireAndReportsClearance() {
        LayoutAssessmentResult result =
                assessor.assess(hubWithBottomSpokeRow(7, 90), spokeConnections(7, 90), false);
        assertEquals(90.0, result.hubNeighbourClearanceMin(), 0.001);
        assertEquals("pass", result.ratingBreakdown().get("hubNeighbourCrowding"));
    }

    @Test
    public void assess_noHub_reportsSentinelClearanceAndPass() {
        // Plain 4-node grid, no element with >= HUB_DETECTION_THRESHOLD connections.
        LayoutAssessmentResult result = assessor.assess(createFourNodeGrid(), List.of(), false);
        assertEquals(LayoutQualityAssessor.NO_HUB_NEIGHBOUR_CLEARANCE,
                result.hubNeighbourClearanceMin(), 0.001);
        assertEquals("pass", result.ratingBreakdown().get("hubNeighbourCrowding"));
    }
}
