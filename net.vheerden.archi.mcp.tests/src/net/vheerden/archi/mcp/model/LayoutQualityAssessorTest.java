package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

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
    public void assess_cleanLayout_shouldScopeItsVerdictToTheDimensionsExamined() {
        // The clean case, pinned POSITIVELY. Its predecessor asserted
        // contains("good") || contains("no immediate") — a disjunction of two fragments that any
        // sentence carrying the word "good" satisfies without establishing anything, and which a
        // grep for the full published phrase could not even find. What matters is that the verdict
        // does not claim the whole view is clean, so that is what is asserted.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 0, 100, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        String verdict = result.suggestions().stream()
                .filter(s -> s.contains("No defects were found on the dimensions this run examined"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "a clean run must still state a verdict: " + result.suggestions()));
        assertTrue("the verdict must refuse the whole-view claim: " + verdict,
                verdict.contains("not a clean bill of health for the whole view"));
        assertTrue("...and must name the tool whose coverage map holds the detail, because this"
                        + " sentence is republished by tools that carry no coverage map: " + verdict,
                verdict.contains("assess-layout's coverage map"));
        assertFalse("the unqualified all-clear must be gone: " + result.suggestions(),
                result.suggestions().stream()
                        .anyMatch(s -> s.contains("no immediate improvements needed")));
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
        // Level 1 → Level 2 → Level 3 → Element
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
        // Guard: a NORMAL-sized (100x50) endpoint box that the label merely grazes — fraction ~0.14
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
        assertFalse("Flat view should NOT suggest compute-layout", hasComputeLayout);
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

    // ---- Label-overlap suggestion, group-aware ----

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

    // ---- Boundary test at CROSSING_RATIO_MODERATE ----

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
        // not exercise — so here every dimension reports its declared level. Three declare something
        // other than "checked" permanently: corridorCentering declares "not-checked" (no detector
        // measures single-route centring), edgeCoincidence declares "partial" (its detector skips
        // non-axis-aligned segments outright) and labelTruncations declares "partial" (its detector
        // skips every group and every unmeasured label width outright).
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
        // no CONTEXTUALLY-downgradable dimension is "partial" on a clean run — NOT that the whole map
        // is "checked", and NOT that the map is partial-free. Three dimensions declare a non-checked
        // level permanently and are unaffected by how clean a run is: corridorCentering is
        // "not-checked" (single-route corridor centring has no detector), edgeCoincidence is
        // "partial" (its detector skips non-axis-aligned segments outright) and labelTruncations is
        // "partial" (its detector skips every group and every unmeasured label width outright).
        // Those are intentional, honest, structural blind spots — distinct from the transient
        // contextual "partial" states this test exists to police, so sweeping the whole map would
        // conflate the two.
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
        // No CONTEXTUAL downgrade should fire on a clean run once the last under-counter closes.
        // (Permanent declarations are excluded by name — see the block comment above.)
        assertNoContextualPartial(result.coverage());
    }

    /**
     * Asserts that no dimension carrying a CONTEXTUAL downgrade reported {@code partial} on this
     * run. Deliberately narrower than sweeping the whole map for {@code partial}: a dimension that
     * declares {@code partial} permanently (its detector covers only part of its failure-mode space
     * on every run) is not evidence that a contextual downgrade misfired, and conflating the two
     * would make every permanent declaration break unrelated clean-run tests.
     */
    private static void assertNoContextualPartial(Map<String, String> coverage) {
        // Derived from the registry, not enumerated here. The hand-written list this replaced named
        // two of the three contextually-downgradable dimensions, so every clean-run test using this
        // helper was blind to a parentLabelObscured misfire. A list maintained by hand beside the
        // authority it is meant to track is the defect, not the spelling of any one entry.
        List<String> contextualDimensions = new ArrayList<>();
        for (LayoutQualityAssessor.CoverageDimension dim
                : LayoutQualityAssessor.CoverageDimension.values()) {
            if (dim.contextualTrigger != LayoutQualityAssessor.ContextualTrigger.NONE) {
                contextualDimensions.add(dim.id);
            }
        }
        assertEquals("the derived contextual set must be non-trivial, or this helper asserts"
                + " nothing", 3, contextualDimensions.size());
        for (String contextual : contextualDimensions) {
            assertNotEquals("contextually-downgradable dimension '" + contextual
                            + "' must not be partial on a run that does not trigger it",
                    LayoutQualityAssessor.COVERAGE_PARTIAL, coverage.get(contextual));
        }
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
        // keeps labelOverlaps "checked" and fires no contextual downgrade at all (clean-run
        // invariant; permanent declarations such as edgeCoincidence are excluded by name, since they
        // report their level on every run regardless of this fixture).
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
        assertNoContextualPartial(fits.coverage());

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

    // ---- Degenerate-view coverage declaration (empty / single-object views) ----
    //
    // A view holding at most one object short-circuits before the assessor runs, so it used to
    // return an EMPTY coverage map. Empty defeats the very checked/not-checked distinction the map
    // exists to make: a consumer could not tell a dimension that cannot apply from one that was
    // never evaluated, and two rating-bearing detections (labelTruncations, offCanvas) plus two
    // informational ones (noteClip, ownIconOverLabel) read as zeros they never earned.
    //
    // Ruling principle for the single-object level, declared per dimension on the registry: a
    // dimension is not-applicable ONLY when its failure mode structurally requires two or more
    // view objects. Everything reachable with one object — on its own, or via a self-referencing
    // connection, which the collector does not filter out — stays not-checked. Where applicability
    // is uncertain, not-checked is mandatory: it costs one unnecessary render-verify, whereas
    // not-applicable on a reachable mode is a false all-clear.
    //
    // EXECUTION MODE: every test in this block runs HEADLESS and needs no --swt. That was measured,
    // not assumed: none of them reaches the facade's assessLayout overload, because nothing can —
    // BaseTestAccessor.assessLayout throws, ArchiModelAccessorImplTest never calls it, and the
    // handler tests drive a stub accessor. The tests below therefore exercise the pure assessor
    // helpers, the DTO constructor, and the facade's SOURCE TEXT. If a future test in this block
    // does reach the facade, it must state its own execution mode here.

    /** Source root of the production plugin, relative to either project directory. */
    private static final String[] PRODUCTION_SOURCE_ROOTS = {
            "net.vheerden.archi.mcp/src",
            "../net.vheerden.archi.mcp/src",
    };

    @Test
    public void degenerateCoverage_emptyView_declaresEveryDimensionNotApplicable() {
        // Zero objects means zero connections too — the collector needs both endpoints to be view
        // objects — so nothing at all is reachable and not-applicable is the honest level for
        // every dimension, including the connection family that stays not-checked at count 1.
        Map<String, String> coverage = LayoutQualityAssessor.buildDegenerateCoverageMap(0);

        assertEquals("an empty view must still declare every registry dimension",
                LayoutQualityAssessor.CoverageDimension.values().length, coverage.size());
        for (Map.Entry<String, String> e : coverage.entrySet()) {
            assertEquals("nothing is reachable on a zero-object view: " + e.getKey(),
                    LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE, e.getValue());
        }
    }

    @Test
    public void degenerateCoverage_singleObjectView_reportsEachDimensionsDeclaredLevel() {
        // The single-object map is the registry's declaration verbatim — not a builder branch, so
        // a dimension added later cannot inherit a default it never stated (that is the point of
        // holding the level as an enum field).
        Map<String, String> coverage = LayoutQualityAssessor.buildDegenerateCoverageMap(1);

        assertEquals("a one-object view must still declare every registry dimension",
                LayoutQualityAssessor.CoverageDimension.values().length, coverage.size());
        for (LayoutQualityAssessor.CoverageDimension dim
                : LayoutQualityAssessor.CoverageDimension.values()) {
            assertEquals("dimension '" + dim.id + "' must report its declared degenerate level",
                    dim.degenerateCoverage, coverage.get(dim.id));
        }
    }

    /**
     * Every dimension whose failure mode lives on a single connection's own polyline must report
     * {@code not-checked} on a one-object view — never {@code not-applicable}.
     *
     * <p>A lone object can carry a self-referencing connection, which the assessor's own degenerate
     * path counts rather than asserting away, so no object count makes such a shape impossible.
     * {@code not-applicable} on a reachable mode is a false all-clear: it tells a consumer the
     * question was settled when it was never asked.
     *
     * <p>The test above cannot catch this, because it reads each dimension's <em>declared</em> level
     * and compares it with itself — a wrong declaration passes it. This one pins the rule that
     * decides what the declaration should be, so a dimension added later cannot quietly write off a
     * mode it merely did not look for.
     */
    @Test
    public void degenerateCoverage_perConnectionShapeDimensions_mustNotClaimNotApplicable() {
        Map<String, String> coverage = LayoutQualityAssessor.buildDegenerateCoverageMap(1);

        // Dimensions detected from ONE connection's own geometry — no second object required.
        List<String> perConnectionShape = List.of(
                "connectionPassThroughs", "interiorTerminations", "zigzags", "redundantBendpoints",
                "nonOrthogonalTerminals", "nonOrthogonalInteriorSegments",
                "offFaceParallelTerminals", "coincidentFacePorts",
                "anchorDrift", "lateralJogReversals");

        for (String id : perConnectionShape) {
            assertNotNull("registry must still carry dimension '" + id + "'", coverage.get(id));
            assertEquals("dimension '" + id + "' is reachable on a lone object via a "
                            + "self-referencing connection, so a one-object view must report it as "
                            + "not-checked rather than writing the mode off",
                    "not-checked", coverage.get(id));
        }
    }

    @Test
    public void degenerateCoverage_bothShapes_holdSizeOrderAndLegalValueInvariants() {
        for (int objectCount : new int[] { 0, 1 }) {
            Map<String, String> coverage =
                    LayoutQualityAssessor.buildDegenerateCoverageMap(objectCount);

            assertNotNull("the degenerate map is never null (objectCount=" + objectCount + ")",
                    coverage);
            assertFalse("the degenerate map is never EMPTY — empty reports silence as an answer "
                    + "(objectCount=" + objectCount + ")", coverage.isEmpty());
            assertEquals("exactly one entry per registry dimension (objectCount=" + objectCount
                    + ")", LayoutQualityAssessor.CoverageDimension.values().length,
                    coverage.size());

            // Insertion order follows the registry, exactly as buildCoverageMap's does.
            List<String> registryOrder = new ArrayList<>();
            for (LayoutQualityAssessor.CoverageDimension dim
                    : LayoutQualityAssessor.CoverageDimension.values()) {
                registryOrder.add(dim.id);
            }
            assertEquals("degenerate map must iterate in registry order (objectCount="
                    + objectCount + ")", registryOrder, new ArrayList<>(coverage.keySet()));

            for (Map.Entry<String, String> e : coverage.entrySet()) {
                String v = e.getValue();
                assertTrue("illegal coverage value '" + v + "' for " + e.getKey(),
                        LayoutQualityAssessor.COVERAGE_CHECKED.equals(v)
                                || LayoutQualityAssessor.COVERAGE_PARTIAL.equals(v)
                                || LayoutQualityAssessor.COVERAGE_NOT_CHECKED.equals(v)
                                || LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE.equals(v));
            }
            // A degenerate view ran no detector, so nothing may claim to have been looked at.
            assertFalse("no dimension may report 'checked' when no detector ran (objectCount="
                            + objectCount + ")",
                    coverage.containsValue(LayoutQualityAssessor.COVERAGE_CHECKED));
            assertFalse("no dimension may report 'partial' when no detector ran (objectCount="
                            + objectCount + ")",
                    coverage.containsValue(LayoutQualityAssessor.COVERAGE_PARTIAL));
        }
    }

    @Test
    public void degenerateCoverage_singleObject_suppressedDetections_areNotChecked() {
        // The four detections a one-object view actually suppresses. Two are rating-bearing
        // (labelTruncations promotes routing Tier 2R; offCanvas promotes layout Tier 2L), so
        // reporting them as anything but not-checked hands the consumer a zero that no detector
        // produced. All four fire on a single node: the first three are fed layoutNodes, noteClip
        // is fed noteNodes, and which of the two a lone object lands in depends on what it IS —
        // the map is keyed on count alone, so not-checked is the only level true in both cases.
        Map<String, String> coverage = LayoutQualityAssessor.buildDegenerateCoverageMap(1);

        assertEquals("rating-bearing label truncation is suppressed, not clean",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("labelTruncations"));
        assertEquals("rating-bearing off-canvas is suppressed, not clean",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("offCanvas"));
        assertEquals("a lone note's text clip is suppressed, not clean",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("noteClip"));
        assertEquals("a lone element's own icon over its own label is suppressed, not clean",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("ownIconOverLabel"));
    }

    @Test
    public void degenerateCoverage_singleObject_connectionFamily_isNotChecked() {
        // AssessmentCollector.collectAssessmentConnections filters only on both endpoints being
        // view objects — it does NOT exclude self-loops. An element with a relationship to itself
        // therefore renders as one node and one real connection, so every connection dimension is
        // computable in principle on a one-object view and must not be written off.
        Map<String, String> coverage = LayoutQualityAssessor.buildDegenerateCoverageMap(1);

        for (String dimension : new String[] {
                "edgeCrossings", "connectionPassThroughs", "coincidentSegments",
                "nonOrthogonalTerminals", "interiorTerminations", "zigzags", "edgeCoincidence",
                "redundantBendpoints", "nonOrthogonalInteriorSegments", "hubPortQuality",
                "coincidentFacePorts", "offFaceParallelTerminals", "parallelConnectionGap" }) {
            assertEquals("a self-loop makes '" + dimension + "' reachable with one object, so it "
                    + "may not be declared not-applicable",
                    LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get(dimension));
        }

        // The one connection dimension that IS structurally dead: computeHubNeighbourCrowding
        // holds the assessor's only self-loop guard (it skips connections whose source equals its
        // target), and a hub needs neighbours a one-object view cannot supply.
        assertEquals("hub-neighbour crowding skips self-loops and needs neighbours",
                LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE, coverage.get("hubNeighbourCrowding"));
    }

    @Test
    public void degenerateCoverage_singleObject_pairwiseDimensions_areNotApplicable() {
        // The dimensions whose detectors are guarded on having two or more objects, verified
        // against the implementations rather than assumed: computeAverageSpacing and
        // computeAlignmentScore both return early below two nodes; detectBoundaryViolations skips
        // any node whose parent is not in the list; the corridor wall scan needs one wall below
        // the segment and another above it, which a single box cannot both be.
        Map<String, String> coverage = LayoutQualityAssessor.buildDegenerateCoverageMap(1);

        for (String dimension : new String[] {
                "overlaps", "containmentOverlaps", "spacing", "alignment", "parentLabelObscured",
                "boundaryViolations", "corridorUtilisation", "noteOverlap", "imageSiblingOverlap",
                "overlayIconCollision", "containerFillRecession" }) {
            assertEquals("'" + dimension + "' needs two or more view objects",
                    LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE, coverage.get(dimension));
        }
    }

    @Test
    public void degenerateCoverage_objectCountOutsideZeroOrOne_isRefusedNotAnswered() {
        // Both helpers branch on == 0 and treat everything else as the single-object case, so an
        // out-of-range count would be answered confidently and wrongly: a 5-object view would be
        // told it holds one object, and told which dimensions "could not apply" when in truth all
        // of them were assessable. That is silence dressed as an answer — the exact failure this
        // map exists to remove — so the helpers refuse the question instead.
        // The production call site is guarded by nodes.size() <= 1 and List.size() is never
        // negative, so this is unreachable today; it pins the CONTRACT for the next caller.
        for (int illegal : new int[] { -1, 2, 35 }) {
            try {
                LayoutQualityAssessor.buildDegenerateCoverageMap(illegal);
                fail("buildDegenerateCoverageMap must refuse objectCount=" + illegal
                        + " rather than answer as if the view held one object");
            } catch (IllegalArgumentException expected) {
                assertTrue("the message must name the offending count, got: "
                                + expected.getMessage(),
                        expected.getMessage().contains(String.valueOf(illegal)));
            }
            try {
                LayoutQualityAssessor.degenerateSuggestion(illegal);
                fail("degenerateSuggestion must refuse objectCount=" + illegal
                        + " rather than claim the view holds one object");
            } catch (IllegalArgumentException expected) {
                assertTrue("the message must name the offending count, got: "
                                + expected.getMessage(),
                        expected.getMessage().contains(String.valueOf(illegal)));
            }
        }
        // The two legal counts stay answerable.
        assertEquals(LayoutQualityAssessor.CoverageDimension.values().length,
                LayoutQualityAssessor.buildDegenerateCoverageMap(0).size());
        assertEquals(LayoutQualityAssessor.CoverageDimension.values().length,
                LayoutQualityAssessor.buildDegenerateCoverageMap(1).size());
    }

    @Test
    public void degenerateCoverage_everyDimensionDeclaresALegalDegenerateLevel() {
        // Totality guard. The declaration is a registry field, so a dimension added later cannot
        // COMPILE without stating its degenerate level; this pins the complementary property that
        // whatever it states is one of the two levels a degenerate view may honestly report.
        // "checked"/"partial" are illegal here by construction: no detector ran.
        for (LayoutQualityAssessor.CoverageDimension dim
                : LayoutQualityAssessor.CoverageDimension.values()) {
            assertNotNull("dimension '" + dim.id + "' declares no degenerate level",
                    dim.degenerateCoverage);
            assertTrue("dimension '" + dim.id + "' declares an illegal degenerate level '"
                            + dim.degenerateCoverage + "' — a degenerate view ran no detector",
                    LayoutQualityAssessor.COVERAGE_NOT_CHECKED.equals(dim.degenerateCoverage)
                            || LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE
                                    .equals(dim.degenerateCoverage));
        }
    }

    @Test
    public void degenerateCoverage_keySetMatchesMainPath_whileValuesDiffer() {
        // The two maps answer the same question over the same namespace, so their KEY SETS must
        // never drift — a dimension covered on one path and absent from the other is exactly the
        // silent blind spot the registry exists to prevent. Their VALUES must differ, because the
        // main path ran detectors and the degenerate path ran none.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));
        Map<String, String> mainPath = assessor.assess(nodes, List.of(), false).coverage();
        Map<String, String> degenerate = LayoutQualityAssessor.buildDegenerateCoverageMap(1);

        assertEquals("both maps must cover the identical dimension namespace",
                mainPath.keySet(), degenerate.keySet());
        assertEquals("and in the identical order",
                new ArrayList<>(mainPath.keySet()), new ArrayList<>(degenerate.keySet()));
        assertNotEquals("the degenerate map must not report the fully-assessed levels",
                mainPath, degenerate);
    }

    @Test
    public void degenerateSuggestion_saysObjectRatherThanElement() {
        // The count is over every view object — elements, groups AND notes — so the old wording
        // ("View has only one element") was false on a view holding a lone note or a lone group,
        // which reports elementCount 1 while containing zero elements. This string is read by an
        // agent as prose, so it is pinned verbatim.
        assertEquals("View has no objects — layout assessment is not applicable.",
                LayoutQualityAssessor.degenerateSuggestion(0));
        assertEquals("View has only one object — layout assessment is not applicable.",
                LayoutQualityAssessor.degenerateSuggestion(1));
        for (int objectCount : new int[] { 0, 1 }) {
            assertFalse("the degenerate suggestion must not claim 'element' (objectCount="
                            + objectCount + ")",
                    LayoutQualityAssessor.degenerateSuggestion(objectCount).contains("element"));
        }
    }

    @Test
    public void degenerateCoverage_dtoBackCompatConstructor_carriesTheCoverageMap() {
        // Plumbing pin. The 45-arg back-compat constructor the short-circuit used HARD-CODES an
        // empty coverage map, so the call site could not declare coverage even if it wanted to —
        // this was a plumbing defect before it was a policy one. The 46-arg overload forwards a
        // real map; the 45-arg one still yields empty, so existing callers are unchanged.
        Map<String, String> declared = LayoutQualityAssessor.buildDegenerateCoverageMap(1);

        AssessLayoutResultDto declaredDto = degenerateDto(1, declared);
        AssessLayoutResultDto legacyDto = degenerateDto(1, null);

        assertEquals("the 46-arg overload must forward the coverage map verbatim",
                declared, declaredDto.coverage());
        assertEquals("and it must survive as a populated map, not an empty one",
                LayoutQualityAssessor.CoverageDimension.values().length,
                declaredDto.coverage().size());
        assertTrue("the 45-arg overload keeps its empty-map legacy contract",
                legacyDto.coverage().isEmpty());
        // Everything else about the two responses is identical — coverage is the only difference.
        assertEquals(legacyDto.overallRating(), declaredDto.overallRating());
        assertEquals(legacyDto.ratingBreakdown(), declaredDto.ratingBreakdown());
        assertEquals(legacyDto.layoutRating(), declaredDto.layoutRating());
        assertEquals(legacyDto.routingRating(), declaredDto.routingRating());
        assertEquals(legacyDto.suggestions(), declaredDto.suggestions());
        assertEquals(legacyDto.elementCount(), declaredDto.elementCount());
        assertEquals(legacyDto.connectionCount(), declaredDto.connectionCount());
    }

    /**
     * Builds the degenerate response shape through the back-compat constructor under test: the
     * 46-arg overload when {@code coverage} is non-null, the 45-arg one otherwise.
     */
    private static AssessLayoutResultDto degenerateDto(int objectCount,
            Map<String, String> coverage) {
        String suggestion = LayoutQualityAssessor.degenerateSuggestion(objectCount);
        if (coverage == null) {
            return new AssessLayoutResultDto(
                    "view-1", objectCount, 0, 0, 0, 0, 0.0, 0.0, 0,
                    "not-applicable", Map.of("overall", "not-applicable"),
                    null, null, null, null, 0, null,
                    0, null, 0, null, false, 0, 0, null,
                    0, null, 0, null, 0, null, null, List.of(suggestion),
                    0, null, 0, null, 0, null, 1.0, null,
                    "not-applicable", "not-applicable", 1.0, null);
        }
        return new AssessLayoutResultDto(
                "view-1", objectCount, 0, 0, 0, 0, 0.0, 0.0, 0,
                "not-applicable", Map.of("overall", "not-applicable"),
                null, null, null, null, 0, null,
                0, null, 0, null, false, 0, 0, null,
                0, null, 0, null, 0, null, null, List.of(suggestion),
                0, null, 0, null, 0, null, 1.0, null,
                "not-applicable", "not-applicable", 1.0, null, coverage);
    }

    @Test
    public void degenerateCoverage_facadeShortCircuit_isWiredToTheDeclaredMapAndSuggestion() {
        // No test can EXECUTE the facade's degenerate path: BaseTestAccessor.assessLayout throws,
        // ArchiModelAccessorImplTest's stub manager deliberately does not stand up the runtime
        // assessLayout needs (and never calls it), and the handler tests drive a stub accessor.
        // So the call site is pinned STRUCTURALLY here — computing a correct map proves nothing if
        // the response is still built from the constructor that hard-codes an empty one — and
        // behaviourally by the agent-in-loop live gate against a rebuilt plugin.
        //
        // The registry-map and shared-suggestion calls now live one layer down, in the assessor's
        // assessDegenerate, because the degenerate response became a real assessment rather than a
        // constant row. The guarantee is unchanged and is asserted where it now holds; the facade's
        // side of the wiring is pinned by the block B companion to this test.
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");
        String assessor = readProductionSource("model/LayoutQualityAssessor.java");

        assertTrue("the degenerate path must build its map from the registry, never inline",
                assessor.contains("buildDegenerateCoverageMap(nodes.size())"));
        assertTrue("the degenerate path must use the shared suggestion text",
                assessor.contains("degenerateSuggestion("));
        assertFalse("the old element-claiming prose must be gone from the facade",
                facade.contains("View has only one element"));
        assertFalse("the old no-elements prose must be gone from the facade",
                facade.contains("View has no elements — layout assessment"));
        assertFalse("the old element-claiming prose must be gone from the assessor too",
                assessor.contains("View has only one element"));
    }

    /** Reads a production source file, failing rather than silently covering nothing. */
    private static String readProductionSource(String relativePath) {
        for (String candidate : PRODUCTION_SOURCE_ROOTS) {
            Path path = Paths.get(candidate, "net/vheerden/archi/mcp", relativePath);
            if (Files.isRegularFile(path)) {
                try {
                    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new AssertionError("None of " + String.join(", ", PRODUCTION_SOURCE_ROOTS)
                + " resolved " + relativePath + " from " + Paths.get("").toAbsolutePath()
                + " — this gate cannot silently cover nothing");
    }

    // ====================================================================
    // OVER-CLAIMED COVERAGE — BLOCK A: edgeCoincidence declares "partial"
    // ====================================================================
    //
    // The edge-coincidence detector examines ONLY axis-aligned segments: countConnectionEdgeCoincidence
    // executes `if (!horizontal && !vertical) continue;` before it ever consults an element, so a
    // diagonal segment is never compared against any edge. That skip is UNCONDITIONAL — it applies on
    // every run, to every view — which is exactly what "partial" means and why this is a declared
    // registry level rather than a contextual downgrade: there is no run on which the diagonal mode
    // IS covered, so there is nothing for a per-run flag to switch on.
    //
    // Deliberately NOT changed: EDGE_COINCIDENCE_TOLERANCE_PX stays 3.0. A hug at the router's
    // designed obstacle clearance (10px, held identically by OrthogonalVisibilityGraph.DEFAULT_MARGIN,
    // RoutingPipeline.DEFAULT_MARGIN, EdgeNudger.DEFAULT_OBSTACLE_MARGIN, ChannelNudgingPass
    // .MIN_CLEARANCE_PX and NON_HUB_OBSTACLE_CLEARANCE_PX in CorridorSpreadEnforcer and
    // AlternativeCorridorSelector) is the router's intended output, so a detector firing there would
    // flag optimal routing on every dense view. This block corrects what the map CLAIMS, not what the
    // detector FINDS: no rating moves, no count moves.
    //
    // EXECUTION MODE: headless, no --swt — these exercise the pure assessor and its registry only.

    @Test
    public void coverage_edgeCoincidence_declaresPartial_notChecked() {
        // The over-claim itself. "checked" asserts the detector covers this dimension's whole
        // failure-mode space; it does not, so a zero from it cannot certify the dimension clean.
        // (Red-on-revert anchor: restoring COVERAGE_CHECKED on the EDGE_COINCIDENCE registry entry
        // fails here.)
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("edgeCoincidence examines only axis-aligned segments, so it is partial",
                LayoutQualityAssessor.COVERAGE_PARTIAL,
                result.coverage().get("edgeCoincidence"));
    }

    @Test
    public void coverage_edgeCoincidence_partialIsOneOfTheFourLegalValues() {
        // Guard, not a RED: whatever level this dimension declares must remain drawn from the closed
        // vocabulary. A typo'd or invented level would otherwise reach a consumer that switches on it.
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                node("b", 200, 0, 100, 50));

        String level = assessor.assess(nodes, List.of(), false).coverage().get("edgeCoincidence");

        assertNotNull("edgeCoincidence must declare a level at all", level);
        assertTrue("illegal coverage level '" + level + "'",
                LayoutQualityAssessor.COVERAGE_CHECKED.equals(level)
                        || LayoutQualityAssessor.COVERAGE_PARTIAL.equals(level)
                        || LayoutQualityAssessor.COVERAGE_NOT_CHECKED.equals(level)
                        || LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE.equals(level));
    }

    @Test
    public void edgeCoincidence_diagonalHugIsNeverExamined_whileTheIdenticalOrthogonalHugIsFlagged() {
        // The EVIDENCE for the level above, as a single-variable pair. Both segments span the same
        // x-range (0→100) and share the SAME MIDPOINT y=52 — and the midpoint is precisely what
        // segmentHugsHorizontalEdge tests against the element's bottom edge at y=50 (gap 2px, inside
        // the 3px band; x-overlap 100px, over the 10px minimum). So the hug predicate cannot tell
        // them apart. The ONLY difference is axis-alignment, and that alone decides whether the
        // element is ever consulted.
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));

        AssessmentConnection orthogonal = new AssessmentConnection("orth", "a", "a",
                List.of(new double[]{0, 52}, new double[]{100, 52}), "", 1);
        AssessmentConnection diagonal = new AssessmentConnection("diag", "a", "a",
                List.of(new double[]{0, 42}, new double[]{100, 62}), "", 1);

        int orthogonalCount = assessor
                .countConnectionEdgeCoincidence(List.of(orthogonal), nodes, false).count();
        int diagonalCount = assessor
                .countConnectionEdgeCoincidence(List.of(diagonal), nodes, false).count();

        assertEquals("an axis-aligned segment hugging the bottom edge is flagged",
                1, orthogonalCount);
        assertEquals("the identically-positioned diagonal is skipped before any element is consulted"
                + " — this is the uncovered mode that makes the dimension partial",
                0, diagonalCount);
    }

    @Test
    public void coverageDeclaration_namesEdgeCoincidenceAsPermanentlyPartial() {
        // The tool description is the ONLY place an agent learns what a coverage level means, so a
        // level flip that leaves the prose behind ships a response the docs contradict. Asserted on
        // SUBSTANCE, not by a bare contains(): the stale count is required to be gone, the dimension
        // must be named alongside the reason a done-gate cares (the unexamined diagonal mode), and
        // the sibling contextual downgrade that the old sentence silently omitted must be present.
        String handler = readProductionSource("handlers/ViewPlacementHandler.java");

        assertFalse("the fixed 'two exceptions' count is stale and must not be reinstated —"
                        + " a hard count in prose goes wrong the moment a level changes",
                handler.contains("two exceptions"));
        assertTrue("the declaration must name edgeCoincidence's uncovered mode",
                handler.contains("a DIAGONAL "));
        assertTrue("the declaration must tell a done-gate what to do about it",
                handler.contains("render-verify diagonal routes"));
        // The contextual downgrade must be documented, and documented for the reason it ACTUALLY
        // fires. It is not a statement about groups: the trigger is a title that could not be
        // MEASURED, which a native group, a Grouping and a plain element all reach by different
        // routes. Asserting the substance rather than a phrase means a future edit that quietly
        // narrows the claim back to one kind reds this test.
        assertTrue("the contextual downgrade must be documented",
                handler.contains("`ownIconOverLabel` when the run carries"));
        assertTrue("...and must attribute it to an unmeasurable title, not to a kind of object",
                handler.contains("title width could not be measured"));
        assertFalse("the downgrade must NOT be described as group-specific — it is not",
                handler.contains("`ownIconOverLabel` when the run carries a named group"));
    }

    @Test
    public void ownIconOverLabelRemedy_mustScopeTheAlignmentFixToTheViewObject() {
        // The remedy used to say "the element's textAlignment", which reads as a property of the
        // model element — so an agent applies it once and believes the model is clean. Alignment
        // is stored on the VIEW OBJECT, so the same element shown on four views needs four
        // corrections. A live run corrected three views and generalised the result to the model;
        // the fourth was still colliding. Naming the scope in the remedy is what prevents that.
        String handler = readProductionSource("handlers/ViewPlacementHandler.java");
        String assessor = readProductionSource("model/LayoutQualityAssessor.java");

        assertTrue("the served remedy must name the view-object scope",
                handler.contains("properties of the VIEW OBJECT, not of the model element"));
        assertTrue("...and must say the fix has to be repeated per view",
                handler.contains("repeated on every view that shows the element"));
        assertFalse("the remedy must not attribute the alignment to the model element",
                handler.contains("change the element's `textAlignment`"));
        // The per-finding description is the surface an agent reads seventeen times on a bad
        // view, so it carries the same scope rather than deferring to the tool description.
        assertTrue("each finding's own description must carry the scope too",
                assessor.contains("per view object, so correcting it here does not travel"));
    }

    // ====================================================================
    // OVER-CLAIMED COVERAGE — BLOCK B: computable detectors on a one-object view
    // ====================================================================
    //
    // A view holding at most one object short-circuits before the assessor runs. Four detections are
    // nevertheless COMPUTABLE on a single object, because they inspect that object's own geometry
    // rather than comparing two: offCanvas and labelTruncations (both rating-bearing on a normal
    // run) plus the informational ownIconOverLabel and noteClip. Suppressing them reported a real,
    // visible defect as a clean zero on a view declared unassessable.
    //
    // The view still does not RATE — see assessDegenerate's contract. Rating stays "not-applicable"
    // because computeAverageSpacing and computeAlignmentScore both return an explicit no-data
    // sentinel below two objects, and scoring those would rate a pristine one-object view "fair" on
    // both layout axes for having nothing to compare against. The findings are reported BESIDE the
    // non-rating instead: counts, descriptions, coverage, and the suggestion list.
    //
    // EXECUTION MODE: headless, no --swt. These exercise the pure assessor plus the facade's SOURCE
    // TEXT — nothing here reaches the facade's assessLayout, because nothing can (see block A's
    // note and the facade wiring pin below).

    @Test
    public void degenerate_offCanvasDefectOnALoneObject_isReportedNotSuppressed() {
        // Rating-bearing on a normal run (negative coordinates), and entirely decidable from this
        // one object's own geometry — there is nothing to compare against and nothing needs to be.
        List<AssessmentNode> nodes = List.of(node("a", -80, -40, 100, 50));

        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(nodes, List.of());

        assertEquals("the off-canvas object must be reported, not suppressed",
                1, result.offCanvasWarnings().size());
        assertTrue("the description must identify the object",
                result.offCanvasWarnings().get(0).contains("a"));
    }

    @Test
    public void degenerate_truncatedLabelOnALoneObject_isReportedNotSuppressed() {
        // The other rating-bearing object-local detection. A narrow box with a long name truncates
        // regardless of what else is on the view.
        // available width 40-16=24px, measured label 200px → ~9 wrapped lines → 132px needed vs 30px
        List<AssessmentNode> nodes = List.of(namedNode("a", 0, 0, 40, 30,
                "An Extremely Long Element Name That Cannot Possibly Fit", 200.0));

        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(nodes, List.of());

        assertEquals("the truncated label must be reported",
                1, result.labelTruncationCount());
        assertEquals("count and descriptions must agree",
                1, result.labelTruncations().size());
    }

    @Test
    public void degenerate_ratingStaysNotApplicable_whileTheDefectIsStillVisible() {
        // The ruling this block implements: running the detectors must NOT start rating the view.
        // A one-object view has no arrangement to judge — computeAverageSpacing and
        // computeAlignmentScore return no-data sentinels (0.0 / 0) that would score it "fair" on
        // both layout axes for being small. So the finding is carried by the counts and the
        // SUGGESTIONS instead, which is what makes it visible without inventing a score.
        List<AssessmentNode> nodes = List.of(node("a", -80, -40, 100, 50));

        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(nodes, List.of());

        assertFalse("a detected defect must be named in the suggestions, not left implicit",
                result.suggestions().size() < 2);
        assertTrue("the base not-applicable suggestion must still be present",
                result.suggestions().get(0).contains("layout assessment is not applicable"));
        String joined = String.join(" | ", result.suggestions());
        assertTrue("the detector's own finding must be carried into the suggestions verbatim, so an"
                        + " agent reading only the suggestions still sees it: " + joined,
                joined.contains(result.offCanvasWarnings().get(0)));
    }

    @Test
    public void degenerate_selfReferencingConnection_isCountedNotHardcodedToZero() {
        // trap: connectionCount was a hard 0 asserted as measured fact. collectAssessmentConnections
        // does not filter self-loops (it only requires both endpoints to resolve to view objects,
        // which for a self-loop is the same object twice), so a lone element with a relationship to
        // itself genuinely carries one connection.
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));
        AssessmentConnection selfLoop = new AssessmentConnection("c1", "a", "a",
                List.of(new double[]{100, 25}, new double[]{150, 25}), "", 1);

        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(nodes, List.of(selfLoop));

        assertEquals("a self-referencing connection on a lone object must be counted",
                1, result.connectionCount());
    }

    @Test
    public void degenerateCoverage_dimensionsWhoseDetectorRan_areNoLongerNotChecked() {
        // Coverage must track what actually executed. These ran, so reporting them "not-checked"
        // would now understate the assessment exactly as hard-coding zeros overstated it. The
        // connection family stays not-checked: counting a connection is not assessing it.
        //
        // labelTruncations is asserted separately because it is upgraded to its DECLARED level, not
        // to a literal "checked", and that level is permanently "partial" (the detector cannot
        // measure a group's title on any path). This path deliberately reports at exactly the level
        // the fully-assessed path would report for the same node set — never better — so the
        // upgrade tracks the registry rather than hard-coding an answer.
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50));

        Map<String, String> coverage =
                assessor.assessDegenerate(nodes, List.of()).coverage();

        for (String ran : List.of("offCanvas", "ownIconOverLabel")) {
            assertEquals("dimension '" + ran + "' ran on this object and must say so",
                    LayoutQualityAssessor.COVERAGE_CHECKED, coverage.get(ran));
        }
        assertEquals("labelTruncations ran on this object and must say so — at its declared level,"
                        + " which is permanently partial",
                LayoutQualityAssessor.COVERAGE_PARTIAL, coverage.get("labelTruncations"));
        assertNotEquals("and it must no longer be reported as never evaluated",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("labelTruncations"));
        assertEquals("no detector examined the connection family, so it stays not-checked",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("edgeCrossings"));
        assertEquals("pairwise dimensions remain structurally impossible on one object",
                LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE, coverage.get("overlaps"));
    }

    @Test
    public void degenerateCoverage_aLoneNote_checksNoteClipButNotTheElementDetectors() {
        // The computable set depends on WHAT the object is: the element detectors are fed
        // layoutNodes and the note detector noteNodes, so a lone note leaves the element detectors
        // with nothing to examine. Upgrading them anyway would be the same false all-clear in a new
        // costume, so they must stay not-checked.
        List<AssessmentNode> nodes = List.of(noteNode("n", 0, 0, 100, 50, 140.0));

        Map<String, String> coverage =
                assessor.assessDegenerate(nodes, List.of()).coverage();

        assertEquals("the note detector had a MEASURABLE note to examine",
                LayoutQualityAssessor.COVERAGE_CHECKED, coverage.get("noteClip"));
        assertEquals("no layout node existed, so the off-canvas detector examined nothing",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("offCanvas"));
        assertEquals("likewise the truncation detector",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("labelTruncations"));
    }

    @Test
    public void degenerateCoverage_unmeasurableNote_doesNotClaimNoteClipChecked() {
        // A detector being HANDED the object is not the same as it EXAMINING the object.
        // detectNoteTextClipping skips a note whose required height is unavailable, so it returns a
        // clean zero having compared nothing. Reporting "checked" off that zero is exactly the
        // false all-clear this dimension's coverage value exists to prevent.
        List<AssessmentNode> nodes = List.of(noteNode("n", 0, 0, 100, 50, 0.0));

        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(nodes, List.of());

        assertEquals("an unmeasurable note leaves the mode unexamined",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, result.coverage().get("noteClip"));
        assertEquals("and nothing may be reported as found", 0, result.noteClipCount());
    }

    @Test
    public void degenerateCoverage_loneGroup_doesNotClaimLabelTruncationsChecked() {
        // detectLabelTruncation skips groups outright — it cannot measure a title band. A view whose
        // only object is a group therefore had no label compared at all.
        List<AssessmentNode> nodes = List.of(group("g", 0, 0, 300, 200));

        Map<String, String> coverage =
                assessor.assessDegenerate(nodes, List.of()).coverage();

        assertEquals("a lone group leaves the truncation mode unexamined",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("labelTruncations"));
        // The coordinate-based detector has no such guard, so it genuinely did examine the group.
        assertEquals("off-canvas tests any object's coordinates, group included",
                LayoutQualityAssessor.COVERAGE_CHECKED, coverage.get("offCanvas"));
    }

    @Test
    public void degenerateCoverage_unmeasuredLabelWidth_doesNotClaimLabelTruncationsChecked() {
        // The subtler half of the same guard: a named element whose label width was never measured
        // is skipped at `textWidth <= 0`, so the detector cannot say whether it truncates.
        List<AssessmentNode> nodes = List.of(namedNode("a", 0, 0, 120, 60, "Some Element", 0.0));

        Map<String, String> coverage =
                assessor.assessDegenerate(nodes, List.of()).coverage();

        assertEquals("an unmeasured label width leaves the mode unexamined",
                LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("labelTruncations"));
    }

    @Test
    public void degenerateCoverage_measurableLabel_upgradesLabelTruncationsToItsDeclaredLevel() {
        // Control for the two tests above: when the detector CAN compare, coverage must say so.
        // The guard still distinguishes examined from not-examined — it has NOT been tightened into
        // permanent abstention — but the level it upgrades TO is the registry's declared level, and
        // that is permanently "partial" because no path can measure a group's title. Asserting the
        // registry rather than a literal is what keeps this path reporting at exactly the level the
        // fully-assessed path would report for the same node set, never better.
        List<AssessmentNode> nodes = List.of(namedNode("a", 0, 0, 120, 60, "Some Element", 80.0));

        Map<String, String> coverage =
                assessor.assessDegenerate(nodes, List.of()).coverage();

        assertEquals("a measurable label was genuinely examined, so the dimension is upgraded out"
                        + " of not-checked to the level the main path would report",
                LayoutQualityAssessor.CoverageDimension.LABEL_TRUNCATIONS.coverage,
                coverage.get("labelTruncations"));
        assertEquals("and that declared level is permanently partial",
                LayoutQualityAssessor.COVERAGE_PARTIAL, coverage.get("labelTruncations"));
    }

    @Test
    public void degenerateCoverage_emptyViewStillDeclaresEverythingNotApplicable() {
        // The zero-object shape is untouched by this change: with no objects, no detector has any
        // input at all, so not-applicable remains honest for every dimension.
        Map<String, String> coverage =
                assessor.assessDegenerate(List.of(), List.of()).coverage();

        assertEquals(LayoutQualityAssessor.CoverageDimension.values().length, coverage.size());
        for (Map.Entry<String, String> e : coverage.entrySet()) {
            assertEquals("nothing is reachable on a zero-object view: " + e.getKey(),
                    LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE, e.getValue());
        }
    }

    @Test
    public void degenerateCoverage_stillOneLegalEntryPerRegistryDimension() {
        // The map invariants survive the upgrade: same key set as the registry, same order, every
        // value drawn from the closed vocabulary. A dimension added later cannot slip through
        // undeclared.
        for (List<AssessmentNode> shape : List.of(
                List.<AssessmentNode>of(), List.of(node("a", 0, 0, 100, 50)))) {
            Map<String, String> coverage =
                    assessor.assessDegenerate(shape, List.of()).coverage();

            assertEquals("one entry per registry dimension",
                    LayoutQualityAssessor.CoverageDimension.values().length, coverage.size());
            List<String> expectedOrder = new ArrayList<>();
            for (LayoutQualityAssessor.CoverageDimension dim
                    : LayoutQualityAssessor.CoverageDimension.values()) {
                expectedOrder.add(dim.id);
            }
            assertEquals("registry iteration order preserved",
                    expectedOrder, new ArrayList<>(coverage.keySet()));
            for (Map.Entry<String, String> e : coverage.entrySet()) {
                assertTrue("illegal coverage value for " + e.getKey() + ": " + e.getValue(),
                        List.of(LayoutQualityAssessor.COVERAGE_CHECKED,
                                        LayoutQualityAssessor.COVERAGE_PARTIAL,
                                        LayoutQualityAssessor.COVERAGE_NOT_CHECKED,
                                        LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE)
                                .contains(e.getValue()));
            }
        }
    }

    @Test
    public void degenerate_facadeShortCircuit_isWiredToTheAssessorAndCountsConnections() {
        // Structural, NOT behavioural — stated plainly because the distinction matters: no test can
        // execute the facade's degenerate path (BaseTestAccessor.assessLayout throws,
        // ArchiModelAccessorImplTest never calls it, the handler tests drive a stub accessor), so
        // computing the right answer in the assessor proves nothing if the facade still builds its
        // response from hard-coded zeros. This pins the call site; the agent-in-loop live gate on a
        // rebuilt plugin is the behavioural proof.
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");

        assertTrue("the degenerate short-circuit must delegate to the assessor",
                facade.contains("assessDegenerate("));
        assertTrue("it must collect connections so a self-loop can be counted",
                facade.contains("collectAssessmentConnections(diagramModel, nodes)"));
        assertFalse("the hard-coded degenerate zero-row must be gone",
                facade.contains("viewId, objectCount, 0, 0, 0, 0, 0.0, 0.0, 0,"));
    }

    // ====================================================================
    // OVER-CLAIMED COVERAGE — BLOCK C: labelTruncations declares "partial"
    // ====================================================================
    //
    // The truncation detector never examines a GROUP's title, and the gap is two layers deep.
    // AssessmentCollector guards its measurement with `!isGroup && !isNote`, so a group's
    // labelTextWidth keeps its 0.0 initialiser and is never measured at all; detectLabelTruncation
    // then discards the node at `node.isGroup()`, the FIRST clause of its entry guard, before any
    // width, box or wrap arithmetic runs. The same guard's `textWidth <= 0` arm also discards a
    // normal element whose measurement threw — so two distinct unmeasured modes arrive as one
    // indistinguishable sentinel.
    //
    // Both skips are UNCONDITIONAL: there is no run on which the detector CAN examine a group's
    // title. That is what makes this a PERMANENT declaration rather than a contextual downgrade —
    // a per-run flag has nothing to switch on for a mode the code never reaches — and it is the
    // same shape ruled for edgeCoincidence in Block A. The count and the rating are deliberately
    // untouched: this corrects what the coverage map CLAIMS, not what the detector FINDS.
    //
    // EXECUTION MODE: every test in this block runs HEADLESS and needs no --swt. Nothing here
    // reaches the facade's assessLayout overload — these exercise the pure assessor, its registry,
    // and production SOURCE TEXT.

    @Test
    public void labelTruncation_groupTitleIsNeverExamined_whileTheIdenticalElementIsFlagged() {
        // The EVIDENCE for the level below, as a single-variable pair. Both nodes carry the SAME
        // name, the SAME 120x80 box and the SAME measured label width — 540px needs 6 wrapped lines
        // in the 104px available beside the type icon (6*14+6 = 90px > 80px), so the truncation
        // predicate itself cannot tell them apart. The ONLY difference is the isGroup flag, and that
        // alone decides whether the label is ever compared against its box.
        AssessmentNode element = namedNode("e", 0, 0, 120, 80, LONG_TITLE, 540.0);
        AssessmentNode group = namedGroup("g", 0, 0, 120, 80, LONG_TITLE, 540.0);

        int elementCount = assessor.detectLabelTruncation(List.of(element)).count();
        int groupCount = assessor.detectLabelTruncation(List.of(group)).count();

        assertEquals("an element whose label cannot fit its box is flagged", 1, elementCount);
        assertEquals("the identically-shaped, identically-labelled GROUP is skipped before its box"
                + " is consulted — this is the uncovered mode that makes the dimension partial",
                0, groupCount);
    }

    @Test
    public void coverage_labelTruncations_isPartialOnEveryRun_groupBearingOrNot() {
        // The declaration itself, asserted as what it actually is: PERMANENT, not contextual. The
        // group-bearing fixture is the shape the defect was reproduced on — a named group whose
        // title cannot fit its box, where the count is honestly 0 because the group was never
        // examined, so "checked" would let a done-gate reading `coverage==checked &&
        // breakdown==pass` certify the dimension clean on a visibly dirty view. But the level does
        // NOT depend on that group being present, and asserting only the group-bearing fixture would
        // imply a per-run downgrade this dimension deliberately does not have. Both fixtures are
        // therefore pinned: the value is unconditional, which is precisely what distinguishes this
        // from the contextual downgrades assertNoContextualPartial polices. The mechanism the group
        // flag actually drives is pinned by the single-variable pair above.
        List<AssessmentNode> groupBearing = List.of(
                namedGroup("g", 0, 0, 120, 80, LONG_TITLE, 540.0),
                namedNode("e", 300, 0, 200, 80, "Fits Fine", 60.0));
        List<AssessmentNode> groupFree = List.of(
                namedNode("a", 0, 0, 200, 80, "Fits Fine", 60.0),
                namedNode("b", 300, 0, 200, 80, "Also Fine", 60.0));

        for (List<AssessmentNode> nodes : List.of(groupBearing, groupFree)) {
            boolean carriesGroup = nodes.stream().anyMatch(AssessmentNode::isGroup);
            LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
            String level = result.coverage().get("labelTruncations");

            assertEquals("the truncation detector can never examine a group title on ANY run, so"
                            + " the dimension abstains unconditionally (carriesGroup="
                            + carriesGroup + ")",
                    LayoutQualityAssessor.COVERAGE_PARTIAL, level);
            assertTrue("illegal coverage level '" + level + "'",
                    LayoutQualityAssessor.COVERAGE_CHECKED.equals(level)
                            || LayoutQualityAssessor.COVERAGE_PARTIAL.equals(level)
                            || LayoutQualityAssessor.COVERAGE_NOT_CHECKED.equals(level)
                            || LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE.equals(level));
            assertEquals("the count must NOT move — this corrects the claim, not the detector"
                            + " (carriesGroup=" + carriesGroup + ")",
                    0, result.labelTruncationCount());
            assertEquals("nor may the rating move (carriesGroup=" + carriesGroup + ")",
                    "pass", result.ratingBreakdown().get("labelTruncations"));
        }
    }

    @Test
    public void labelTruncation_collectorGuardStillLeavesAGroupWidthUnmeasured() {
        // Layer 1, source-pinned. No headless test can execute AssessmentCollector (package-private,
        // EMF-bound), yet the whole justification for the level above rests on this guard: a group is
        // excluded from measurement, so its width keeps the 0.0 initialiser and the detector's
        // `textWidth <= 0` arm would discard it even if the isGroup clause were removed. If this
        // guard changes, the justification dies silently — this is what stops that.
        String collector = readProductionSource("model/AssessmentCollector.java");

        assertTrue("the label width must still start at the unmeasured sentinel",
                collector.contains("double labelTextWidth = 0.0;"));
        assertTrue("groups and notes must still be excluded from label measurement",
                collector.contains("!isGroup && !isNote"));
        assertTrue("and the measurement failure path must still fall through to that same sentinel",
                collector.contains("Failed to measure text for"));
    }

    @Test
    public void coverageDeclaration_namesLabelTruncationsAsPermanentlyPartial() {
        // The tool description is the ONLY place an agent learns what a coverage level means, so a
        // level flip that leaves the prose behind ships a response the docs contradict. Asserted on
        // SUBSTANCE, not by a bare contains(): the dimension must be named as PERMANENT, alongside
        // the uncovered mode a done-gate cares about and what it must do instead.
        String handler = readProductionSource("handlers/ViewPlacementHandler.java");

        assertTrue("the declaration must name labelTruncations' permanent level",
                handler.contains("`labelTruncations` is always `partial`"));
        assertTrue("it must name the uncovered mode — a group's title is never measured",
                handler.contains("a visual GROUP's title is never measured"));
        assertTrue("it must name the second uncovered mode — an unmeasured element label width",
                handler.contains("an element whose label width could not be measured"));
        assertTrue("it must tell a done-gate what to do about it",
                handler.contains("render-verify group titles"));
        // The surviving phrase counts KINDS (contextual vs permanent), not dimensions, so it stays
        // true with a third dimension in the permanent kind and must not be "corrected".
        assertTrue("the kind-counting phrase is still right and must not be disturbed",
                handler.contains("The exceptions come in two kinds"));
    }

    // ---- parentLabelObscured: the title band is undersized whenever the width was not measured ----
    //
    // Execution mode: headless, no display. These exercise the pure assessor, its coverage registry
    // and production SOURCE TEXT only; nothing here reaches the facade's assessLayout.
    //
    // The sibling dimension above abstains because its detector EXCLUDES a group before examining
    // it. This one is the opposite shape and the harsher failure: detectParentLabelObscuredByChild
    // has no group guard at all, so it examines a group parent, sizes its title band from a width
    // that was never measured, and returns a confident "not obscured". The band is the only thing
    // wrong, so the fix is a declared coverage gap, not a changed count.

    @Test
    public void parentLabelObscured_bandCollapsesToOneLine_whenTheParentWidthWasNeverMeasured() {
        // The EVIDENCE for the contextual downgrade, as a single-variable pair. Both parents are
        // groups with the SAME name, the SAME 120x80 box and the SAME child at relative y=25. The
        // ONLY difference is labelTextWidth, and that alone decides the band: 540px exceeds the
        // 104px available, so estimateLabelBandHeight doubles to 40px and 25 < 40 flags; the
        // unmeasured 0.0 can never exceed any width, so the band stays 20px and 25 < 20 does not.
        //
        // A real Archi group renders its title in a wrapping TextFlow laid out over the WHOLE group
        // rectangle, so a long multi-word title genuinely occupies that second line — but the
        // collector never measures a group's width, so production always takes the 0.0 branch. The
        // right-hand column below is therefore the production shape, and it is a false negative.
        AssessmentNode measuredParent =
                namedGroup("measured", 0, 0, 120, 80, LONG_TITLE, 540.0);
        AssessmentNode unmeasuredParent =
                namedGroup("unmeasured", 0, 0, 120, 80, LONG_TITLE, 0.0);

        int measuredCount = assessor.detectParentLabelObscuredByChild(
                List.of(measuredParent, childOf("measured", 10, 25))).count();
        int unmeasuredCount = assessor.detectParentLabelObscuredByChild(
                List.of(unmeasuredParent, childOf("unmeasured", 10, 25))).count();

        assertEquals("a parent whose wrapped title needs two lines is flagged when the width was"
                + " measured", 1, measuredCount);
        assertEquals("the identically-shaped, identically-titled parent whose width was NEVER"
                + " measured is NOT flagged — the band silently collapses to one line, and this is"
                + " the false negative that makes the dimension partial", 0, unmeasuredCount);
    }

    @Test
    public void coverage_parentLabelObscured_isPartialOnlyWhenAParentBandWasUnmeasured() {
        // The declaration itself, asserted as what it actually is: CONTEXTUAL, not permanent. The
        // sibling labelTruncations pin asserts BOTH its fixtures are "partial", because that level
        // is unconditional. A contextual level is pinned by the two fixtures DISAGREEING — a test
        // here that asserted "partial" on both would pass identically whichever arm had shipped.
        List<AssessmentNode> unmeasuredBand = List.of(
                namedGroup("g", 0, 0, 120, 80, LONG_TITLE, 0.0),
                childOf("g", 10, 25));
        List<AssessmentNode> measuredBand = List.of(
                namedNode("e", 0, 0, 200, 120, "Fits Fine", 60.0),
                childOf("e", 10, 40));

        LayoutAssessmentResult unmeasured = assessor.assess(unmeasuredBand, List.of(), false);
        LayoutAssessmentResult measured = assessor.assess(measuredBand, List.of(), false);

        assertEquals("a run carrying a parent whose title band width was never measured cannot"
                        + " certify this dimension — the detector answered off a fabricated band",
                LayoutQualityAssessor.COVERAGE_PARTIAL,
                unmeasured.coverage().get("parentLabelObscured"));
        assertEquals("but a run whose parents were all measured IS fully covered, which is exactly"
                        + " what makes this contextual rather than permanent",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                measured.coverage().get("parentLabelObscured"));

        // Direction check: this corrects what the map CLAIMS, never what the detector FINDS. The
        // defect under-flags, so a moved count would mean the band arithmetic had been changed.
        for (LayoutAssessmentResult result : List.of(unmeasured, measured)) {
            assertEquals("the count must NOT move", 0, result.parentLabelObscuredCount());
            assertEquals("nor may the rating move", "pass",
                    result.ratingBreakdown().get("parentLabelObscured"));
        }
    }

    @Test
    public void coverage_parentLabelObscured_childlessGroupIsNeverExamined_soDoesNotDowngrade() {
        // Handed is not examined. A childless group never becomes a key in the detector's
        // parent->children map, so its unmeasured width is never consumed and nothing is claimed
        // off it. Without this pin the trigger silently degrades into a group-PRESENCE check, which
        // is the defect the sibling row's review caught one level down.
        List<AssessmentNode> nodes = List.of(
                namedGroup("lonely", 0, 0, 120, 80, LONG_TITLE, 0.0),
                namedNode("e", 300, 0, 200, 120, "Fits Fine", 60.0));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("a group the detector never examined must not downgrade the dimension",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("parentLabelObscured"));
    }

    @Test
    public void coverage_parentLabelObscured_unmeasuredElementParentAlsoDowngrades() {
        // The second unmeasured mode. A group is excluded from measurement structurally, but a
        // normal element whose measureText threw falls through to the SAME 0.0 sentinel, and its
        // band collapses identically. A trigger keyed on isGroup would leave this mode silently
        // uncovered while claiming the dimension checked.
        List<AssessmentNode> nodes = List.of(
                namedNode("e", 0, 0, 120, 80, LONG_TITLE, 0.0),
                childOf("e", 10, 25));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("an ELEMENT parent whose label width could not be measured undersizes its band"
                        + " exactly as a group does, and must downgrade the dimension too",
                LayoutQualityAssessor.COVERAGE_PARTIAL,
                result.coverage().get("parentLabelObscured"));
        assertEquals("the count must NOT move", 0, result.parentLabelObscuredCount());
    }

    @Test
    public void parentLabelObscured_collectorGuardStillLeavesAParentBandUnmeasured() {
        // Source-pinned, because no headless test can execute AssessmentCollector (package-private,
        // EMF-bound) and the whole justification for the downgrade rests on this guard: a group is
        // excluded from measurement, so its width keeps the 0.0 initialiser and estimateLabelBandHeight
        // can never take its wrap branch. The sibling truncation dimension pins the same guard for
        // its own reason; this one is stated in terms of the BAND so it survives independently if
        // that dimension is ever closed.
        String collector = readProductionSource("model/AssessmentCollector.java");
        String assessorSource = readProductionSource("model/LayoutQualityAssessor.java");

        assertTrue("the label width must still start at the unmeasured sentinel",
                collector.contains("double labelTextWidth = 0.0;"));
        assertTrue("groups must still be excluded from label measurement",
                collector.contains("!isGroup && !isNote"));
        assertTrue("and a measurement failure must still fall through to that same sentinel",
                collector.contains("Failed to measure text for"));
        assertTrue("the band must still gate its multi-line branch on the measured width, which is"
                        + " what an unmeasured 0.0 can never satisfy",
                assessorSource.contains("node.labelTextWidth() > availableWidth"));
    }

    @Test
    public void coverageDeclaration_namesParentLabelObscuredAsContextuallyPartial() {
        // The tool description is the ONLY place an agent learns what a coverage level means.
        // Asserted on SUBSTANCE, not by a bare contains(): the dimension must be named in the
        // CONTEXTUAL kind, alongside the trigger and what a done-gate must do instead.
        String handler = readProductionSource("handlers/ViewPlacementHandler.java");

        assertTrue("the declaration must name parentLabelObscured's contextual trigger",
                handler.contains("`parentLabelObscured` when the run carries a parent whose title"
                        + " band width was never measured"));
        assertTrue("it must say why the zero cannot be trusted — the band, not the comparison",
                handler.contains("its title band is sized as a single line"));
        assertTrue("it must tell a done-gate what to render-verify",
                handler.contains("render-verify such a parent's title against its topmost child"));
        // Counts KINDS, not dimensions — still true with a third contextual dimension.
        assertTrue("the kind-counting phrase is still right and must not be disturbed",
                handler.contains("The exceptions come in two kinds"));
    }

    /**
     * A child element nested in {@code parentId}, positioned in ABSOLUTE coordinates. The detector
     * compares absolute child y against absolute parent y, and every parent in these fixtures sits
     * at y=0, so the y passed here is also the relative offset.
     */
    private static AssessmentNode childOf(String parentId, double x, double y) {
        return new AssessmentNode(parentId + "-child", x, y, 80, 40, parentId, false, false,
                "Child", 40.0, null, null, 0.0, 0.0, 0.0);
    }

    /** A 72-character title — the live exemplar's shape, far too wide for a 120px box. */
    private static final String LONG_TITLE =
            "Customer Onboarding And Identity Verification Orchestration Service Hub";

    /**
     * A visual GROUP carrying a name AND a measured label width — the impossible-in-production
     * combination that isolates the detector's isGroup guard as the single variable. The collector
     * never measures a group's width, so only a hand-built node can hold one; that is precisely what
     * makes the pair single-variable rather than confounded by the width.
     */
    private static AssessmentNode namedGroup(String id, double x, double y,
            double w, double h, String name, double labelTextWidth) {
        return new AssessmentNode(id, x, y, w, h, null, true, false, name, labelTextWidth,
                null, null, 0.0, 0.0, 0.0);
    }

    /**
     * A layout node carrying a name AND a measured label width. The truncation detector skips any
     * node whose labelTextWidth is 0, so a name alone is not enough to exercise it.
     */
    private static AssessmentNode namedNode(String id, double x, double y,
            double w, double h, String name, double labelTextWidth) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, name, labelTextWidth,
                null, null, 0.0, 0.0, 0.0);
    }

    /**
     * A note node carrying a measured required height. The clipping detector skips any note whose
     * noteRequiredHeight is 0 ("measurement unavailable"), so this must be set for it to run.
     */
    private static AssessmentNode noteNode(String id, double x, double y,
            double w, double h, double requiredHeight) {
        return new AssessmentNode(id, x, y, w, h, null, false, true, "note", 0.0,
                null, null, requiredHeight, 0.0, 0.0);
    }

    /**
     * An assessment node with an explicit parent and fill colour (full canonical form).
     *
     * <p>{@code isContainer} tracks {@code isGroup} here: these fixtures exercise the
     * container-fill recession check with native groups, so the two flags coincide. A
     * {@code Grouping}-flavoured container (isGroup false, isContainer true) is exercised through
     * the real collector in {@code TopLevelGroupingAssessmentTest}.</p>
     */
    private static AssessmentNode cfNode(String id, double x, double y, double w, double h,
            String parentId, boolean isGroup, String fill) {
        return new AssessmentNode(id, x, y, w, h, parentId, isGroup, false, null, 0.0,
                null, null, 0.0, 0.0, 0.0, false, fill, isGroup, AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
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
        // M6: breakdown grew from 9 (8 metrics + overall) to 17 (16 metrics + overall):
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
        // Off-face parallel-terminal hug (promoted to a rating tier) adds a 20th metric
        // ("offFaceParallelTerminals", present on every call — pass when count is zero) → 22.
        assertEquals(22, result.ratingBreakdown().size());
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
        // Boundary test at exactly CROSSING_RATIO_GOOD (1.5)
        // 30 crossings / 20 connections = 1.5 ratio — exactly at boundary (<=)
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 30, 50.0, 80, 0, 0, 0, 0, 20, false);

        assertEquals("Exactly 1.5 crossings/conn should rate good (boundary <=)",
                "good", result.breakdown().get("edgeCrossings"));
    }

    @Test
    public void rating_flatView_crossingRatioJustAboveBoundary_shouldNotRateGood() {
        // Just above CROSSING_RATIO_GOOD — should be "fair" not "good"
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
    public void connectionThroughVisual_routeThroughImageInsideBox_shouldFlagAsImage() {
        // A route through an element's rendered image is flagged as an image crossing. The image
        // rect (180,0,100,100) lies INSIDE the 120x100 host box, because Archi clips an element's
        // image to the element box — an image never renders outside its element.
        AssessmentNode img = new AssessmentNode("img1", 180, 0, 120, 100, null, false, false,
                "Img", 0.0, "img/legend.png", "top-left", 0.0, 100.0, 100.0);
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 40, 80, 40),
                node("tgt", 400, 40, 80, 40),
                img);
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 40, 440, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals(1, result.connectionThroughNoteCount());
        String desc = result.connectionThroughNoteDescriptions().get(0);
        assertTrue("description names the element", desc.contains("img1"));
        assertTrue("description identifies the visual as an image", desc.contains("image"));
        // Because the image rect is bounded by the element box, a route through the image is also
        // a route through the box: for an ELEMENT host the image axis is subsumed by the box-based
        // pass-through detector, and the two are no longer disjoint. Charging is still single —
        // the routing tier takes the worse of the two (pinned by the no-double-charge test below).
        // The image axis retains independent value only where the box is NOT scored: notes, which
        // are split out of the scoring node set.
        assertFalse("a route through the image is also an element-box pass-through",
                result.connectionPassThroughs().isEmpty());
    }

    @Test
    public void connectionThroughVisual_oversizedIconOverhang_shouldNotFlag_norDemoteRouting() {
        // The false positive this clamp removes, and the reason it is not merely cosmetic.
        // A 100x100 icon on a 60x20 box: the UNCLAMPED rect reached y=100 and the y=60 route
        // "crossed" it, so the assessor flagged a crossing AND demoted routingRating from
        // excellent to good. Archi clips the icon to the 20px-tall box, so the route crosses
        // nothing that renders: the count is 0, there is no graze, and the rating is undemoted.
        AssessmentNode img = new AssessmentNode("img1", 180, 0, 60, 20, null, false, false,
                "Img", 0.0, "img/legend.png", "top-left", 0.0, 100.0, 100.0);
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 40, 80, 40),
                node("tgt", 400, 40, 80, 40),
                img);
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 40, 440, 60));

        LayoutAssessmentResult result = assessor.assess(nodes, conns, false);

        assertEquals("a route through undrawn overhang is not an image crossing",
                0, result.connectionThroughNoteCount());
        assertEquals("nor is it a border graze", 0, result.connectionGrazesVisualCount());
        assertTrue("nor an element-box pass-through", result.connectionPassThroughs().isEmpty());
        assertEquals("pass", result.ratingBreakdown().get("connectionThroughNote"));
        assertEquals("the removed false positive was demoting this rating",
                "excellent", result.routingRating());
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
        // The image variant moves the connectionThroughNote breakdown entry, which is what carries
        // the metric into the routing tier. Single variable: the image-bearing element is present
        // in both runs and only its Y differs (routing ignores element position), so only the
        // through run flips the entry to "good".
        AssessmentNode imgThrough = new AssessmentNode("img1", 180, 0, 120, 100, null, false, false,
                "Img", 0.0, "img/legend.png", "top-left", 0.0, 100.0, 100.0);
        AssessmentNode imgClear = new AssessmentNode("img1", 180, 200, 120, 100, null, false, false,
                "Img", 0.0, "img/legend.png", "top-left", 0.0, 100.0, 100.0);
        List<AssessmentNode> throughNodes = List.of(
                node("src", 0, 40, 80, 40), node("tgt", 400, 40, 80, 40), imgThrough);
        List<AssessmentNode> clearNodes = List.of(
                node("src", 0, 40, 80, 40), node("tgt", 400, 40, 80, 40), imgClear);
        List<AssessmentConnection> conns = List.of(straightConn("c1", "src", "tgt", 40, 440, 50));

        LayoutAssessmentResult control = assessor.assess(clearNodes, conns, false);
        LayoutAssessmentResult through = assessor.assess(throughNodes, conns, false);

        assertEquals(0, control.connectionThroughNoteCount());
        assertEquals(1, through.connectionThroughNoteCount());
        assertEquals("pass", control.ratingBreakdown().get("connectionThroughNote"));
        assertEquals("good", through.ratingBreakdown().get("connectionThroughNote"));
        // Since the image rect is bounded by the element box, the same crossing is also a Tier-1R
        // box pass-through, which dominates the routing tier. The demotion is therefore real but
        // driven by the worse of the two — no double penalty (see the no-double-charge test).
        // Pinned to the exact value, not merely "not excellent": one pass-through is Tier-1R "fair"
        // (FAIR_MAX_PASS_THROUGHS is 3), which outranks this metric's own Tier-3R cap of "good".
        assertEquals("excellent", control.routingRating());
        assertEquals("one box pass-through (Tier-1R fair) dominates the Tier-3R image cap",
                "fair", through.routingRating());
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
        // (90..100) but not the inset interior → image-border graze. The image rect lies inside the
        // 120x100 host box (Archi clips an image to its element), and y=95 is outside that box's
        // inset interior too, so this remains invisible to the element-box pass-through detector.
        AssessmentNode img = new AssessmentNode("img1", 180, 0, 120, 100, null, false, false,
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

    /**
     * A container that is NOT a native group (an ArchiMate {@code Grouping}) whose title is wide
     * enough to WRAP: {@code labelTextWidth} exceeds {@code width - TYPE_ICON_WIDTH}, which is the
     * condition {@code estimateLabelBandHeight} doubles the band on. Such an object is genuinely
     * measured — the collector measures every non-group, non-note object — which is exactly what
     * makes the wrap reachable here and unreachable for a native group.
     */
    private static AssessmentNode wrapTitledZone(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false,
                "Wrapping Title That Is Long Enough To Need Two Rows", w + 40.0,
                null, null, 0.0, 0.0, 0.0, false, null, true,
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
    }

    @Test
    public void labelOnGroup_shouldTestTheWRAPPEDBand_whenAContainersTitleNeedsTwoRows() {
        // A wrap-titled container renders its name on TWO rows. Measured at the render (SVG export,
        // Archi 5.10): the second row's ink reached 29px below the box top, while this detector
        // tested only a fixed 20px band — so a connection label sitting on that second row collided
        // with the container's own name and went unflagged.
        //
        // The band now comes from estimateLabelBandHeight, which doubles 20 -> 40 exactly when the
        // title is too wide for its box. y=42 is DERIVED, not picked: a label rect is 20px tall and
        // insetRectOverlap shrinks it by height/3 each side, so its effective span is y+/-3.33.
        // Against a band starting at y=10 that clears the single-line band (ends 30) from y>=34 and
        // still meets the doubled band (ends 50) up to y<=50. 42 sits mid-window, so the case is
        // robust to a pixel either way while still deciding the change on its own.
        List<AssessmentNode> zone = List.of(wrapTitledZone("z1", 100, 10, 400, 200));
        List<AssessmentConnection> secondRowLabel =
                List.of(labeledConn("c1", "a", "b", 150, 450, 42, "Accesses"));

        assertEquals("a label on the wrapped title's second row must flag",
                1, assessor.countLabelOnGroup(secondRowLabel, zone, false).count());
    }

    @Test
    public void labelOnGroup_shouldNotExtendTheWrappedBandBelowTheContainer() {
        // The wrapped band must never be deeper than the container it belongs to. Archi clips a
        // figure's contents to the figure, so a 40px title band on a 30px-tall zone renders 30px of
        // title and nothing below — the last 10px is not this container's title, it is whatever
        // sits underneath.
        //
        // THIS TEST IS ALSO THE PIN FOR THE CLIP'S LOCATION. The clip used to be a local Math.min at
        // this detector's call site; it now lives in estimateLabelBandHeight, read by all three
        // consumers. Moving it left this assertion untouched and green, which is what "the removal
        // was behaviour-neutral" means here — and if the helper's clip is ever reverted, this reds,
        // so the neutrality is pinned rather than asserted.
        //
        // This is the false-positive direction of the wrap fix, and it is reachable on the shape it
        // most matters for: Groupings are routinely authored as thin, wide swim-lanes, and only a
        // measured container can reach the doubling at all (a native group has no measured width).
        // 250x30 with a 280px title doubles to 40. A label at y=48 has an effective span of
        // [44.67, 51.33] — entirely BELOW the container's bottom edge at y=40, yet inside an
        // unclamped 40px band.
        AssessmentNode thinZone = new AssessmentNode("z1", 100, 10, 250, 30, null, false, false,
                "Regulatory Compliance And Reporting Services", 280.0,
                null, null, 0.0, 0.0, 0.0, false, null, true,
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
        List<AssessmentConnection> labelBelowTheZone =
                List.of(labeledConn("c1", "a", "b", 150, 300, 48, "Accesses"));

        assertEquals("a label below the container's own bottom edge is not on its title band",
                0, assessor.countLabelOnGroup(labelBelowTheZone, List.of(thinZone), false).count());

        // Positive control on the SAME node: a label genuinely inside the visible band still flags,
        // so the clamp cannot be mistaken for having disabled the detector on thin containers.
        List<AssessmentConnection> labelOnTheZone =
                List.of(labeledConn("c2", "a", "b", 150, 300, 25, "Accesses"));
        assertEquals("a label inside the container's visible title band still flags",
                1, assessor.countLabelOnGroup(labelOnTheZone, List.of(thinZone), false).count());
    }

    @Test
    public void labelOnGroup_shouldSkipAContainerWithNoUsableHeight() {
        // A container with zero, negative or non-finite height draws no figure, so it has no title
        // strip for a label to collide with and must never contribute a finding.
        //
        // This is NOT hypothetical bookkeeping — it is the branch that made moving the clip into the
        // shared helper a real behaviour change at THIS seam, in the false-positive direction. The
        // clip used to be a local Math.min here and applied unconditionally: it drove the band to 0
        // for a zero height, negative for a negative one, and NaN for NaN — and every one of those
        // makes insetRectOverlap's comparisons false, so a degenerate container could never flag.
        // The shared helper deliberately leaves such heights UNCLIPPED (it must not rewrite a
        // rating-bearing band on geometry the clip is not about), so without this guard the band
        // would come back as a full, finite 20 or 40 px and start flagging labels against a
        // container that renders nothing.
        //
        // The guard is written `!(height > 0)` and not `height <= 0` because NaN fails every
        // comparison: `NaN <= 0` is false and would let NaN through, `!(NaN > 0)` is true and skips.
        //
        // Worth flagging louder than its reachability suggests: labelOnGroup is not one of the
        // dimensions that can downgrade itself to `partial`, so a finding invented here would be
        // published as fully `checked` — a false positive wearing a certified-clean label.
        List<AssessmentConnection> labelOnTheTopStrip =
                List.of(labeledConn("c1", "a", "b", 150, 300, 25, "Accesses"));

        for (double unusableHeight : new double[]{0.0, -30.0, Double.NaN}) {
            AssessmentNode degenerate = new AssessmentNode("z1", 100, 10, 250, unusableHeight,
                    null, false, false, "Regulatory Compliance And Reporting Services",
                    250 - LayoutQualityAssessor.TYPE_ICON_WIDTH + 1.0,
                    null, null, 0.0, 0.0, 0.0, false, null, true,
                    AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);

            assertEquals("height " + unusableHeight + ": a container with no usable height has no"
                    + " title band, so no label can be on it",
                    0, assessor.countLabelOnGroup(labelOnTheTopStrip, List.of(degenerate), false)
                            .count());
        }

        // Single-variable control: the SAME label and the SAME container with a usable height does
        // flag, so the guard above cannot be mistaken for having disabled the detector outright.
        AssessmentNode usable = new AssessmentNode("z1", 100, 10, 250, 30,
                null, false, false, "Regulatory Compliance And Reporting Services",
                250 - LayoutQualityAssessor.TYPE_ICON_WIDTH + 1.0,
                null, null, 0.0, 0.0, 0.0, false, null, true,
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
        assertEquals("the identical label on the identical container with a usable height flags",
                1, assessor.countLabelOnGroup(labelOnTheTopStrip, List.of(usable), false).count());
    }

    @Test
    public void labelOnGroup_shouldStillUseTheSingleLineBand_whenTheTitleDoesNotWrap() {
        // NEGATIVE CONTROL 1 — the band only doubles when the title actually wraps. Same container
        // kind, same geometry, same label position; only the measured title width differs, so this
        // is the single variable that flips the previous test.
        AssessmentNode narrowTitle = new AssessmentNode("z1", 100, 10, 400, 200, null, false, false,
                "Short", 50.0, null, null, 0.0, 0.0, 0.0, false, null, true,
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
        List<AssessmentConnection> secondRowLabel =
                List.of(labeledConn("c1", "a", "b", 150, 450, 42, "Accesses"));

        assertEquals("a label below a single-line title band is in the body and must not flag",
                0, assessor.countLabelOnGroup(secondRowLabel, List.of(narrowTitle), false).count());
    }

    @Test
    public void labelOnGroup_shouldBeUnchangedForANativeGroup() {
        // NEGATIVE CONTROL 2 — and the reason the old rationale was right for the kind it was
        // written about. A native group carries NO measured labelTextWidth (label text is collected
        // only for non-group, non-note objects), so estimateLabelBandHeight cannot take its
        // multi-line branch and returns exactly the single line it always did. This change must not
        // move a native group's verdict by a pixel, however long its name.
        List<AssessmentNode> group = List.of(groupNode("g1", 100, 10, 400, 200,
                "Wrapping Title That Is Long Enough To Need Two Rows"));
        List<AssessmentConnection> secondRowLabel =
                List.of(labeledConn("c1", "a", "b", 150, 450, 42, "Accesses"));

        assertEquals("a native group's band stays single-line — unmeasured, so undoubled",
                0, assessor.countLabelOnGroup(secondRowLabel, group, false).count());
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
    public void offFaceParallel_assessLevel_surfacesCountCoverageViolator_andCapsRatingAtFair() {
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
        assertTrue("violator surfaced under the key",
                result.violatorIds().get("offFaceParallelTerminals").contains("c1"));
        // Promoted to a rating tier: the sole off-face hug caps the headline at fair (never poor),
        // with the breakdown entry present — the headline can no longer read good/excellent while
        // the render shows the hug.
        assertEquals("off-face hug is a Tier-2R (cap-fair) rating contributor",
                "fair", result.ratingBreakdown().get("offFaceParallelTerminals"));
        assertEquals("the sole routing defect caps overall at fair", "fair", result.overallRating());
        assertEquals("routing tier reflects the hug", "fair", result.routingRating());
        // Disjoint by construction: the rating-bearing terminal-angle metric stays untouched — the
        // 1px exit stub is sub-perceptible (visible-length-guard-suppressed) and the target approach
        // is vertical, so nonOrthogonalTerminals sees nothing and never double-charges the hug.
        assertEquals("nonOrthogonalTerminalCount stays 0 — off-face is a disjoint metric",
                0, result.nonOrthogonalTerminalCount());
        assertEquals("the disjoint terminal-angle entry stays pass",
                "pass", result.ratingBreakdown().get("nonOrthogonalTerminals"));
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
    public void offFaceParallel_remedyBranch_ratingNeutral_bothCorridorsRateFair() {
        // The remedy branch (layout-bound spacing copy vs deferred auto-route) touches ONLY the
        // description string — it is binary presence that drives the rating, not the corridor width.
        // A tight vs wide corridor produced by moving a rating-neutral neighbouring run (endpoints are
        // not nodes → no terminal/crossing contribution; parallelConnectionGap is informational) both
        // flag the same single hug, so both rate the same fair headline while the remedy text differs.
        // Guards that the remedy-text branch never leaks into the (binary) rating.
        AssessmentConnection runTight = new AssessmentConnection("n2", "s2", "t2",
                List.of(new double[]{500, 118}, new double[]{600, 118}), "", 1);   // 9px → layout copy
        AssessmentConnection runWide = new AssessmentConnection("n2", "s2", "t2",
                List.of(new double[]{500, 130}, new double[]{600, 130}), "", 1);   // 21px → auto-route copy
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
        assertEquals("both hugs cap the headline at fair (binary presence, not corridor width)",
                "fair", tight.overallRating());
        assertEquals("overallRating identical across the remedy-text branch",
                tight.overallRating(), wide.overallRating());
        assertEquals("layoutRating identical", tight.layoutRating(), wide.layoutRating());
        assertEquals("routingRating identical", tight.routingRating(), wide.routingRating());
        assertEquals("ratingBreakdown identical (remedy text does not enter the rating)",
                tight.ratingBreakdown(), wide.ratingBreakdown());
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

    // ---- Off-face parallel-terminal RATING tests (binary presence, Tier-2R cap-fair) ----
    // Promotion from informational to a rating tier. Unlike the ratio-bucketed terminal/interior
    // siblings, this is BINARY presence: any real face-hug is a visible defect the headline must
    // reflect, so a single hug caps routing at fair (never poor), and magnitude does not escalate.

    @Test
    public void offFaceParallel_zeroCountShouldRatePass() {
        // No off-face hug → the entry passes and contributes nothing to the routing tier.
        LayoutQualityAssessor.RatingResult result = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 0);
        assertEquals("pass", result.breakdown().get("offFaceParallelTerminals"));
    }

    @Test
    public void offFaceParallel_presenceShouldRateFair_magnitudeDoesNotEscalate() {
        // Binary presence: one hug and five hugs both rate fair — magnitude never escalates it to
        // poor (contrast the ratio-bucketed terminal/interior siblings). Even five hugs on a single
        // connection cap the routing tier at fair, never poor.
        LayoutQualityAssessor.RatingResult one = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 1);
        LayoutQualityAssessor.RatingResult five = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 1, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 5);
        assertEquals("fair", one.breakdown().get("offFaceParallelTerminals"));
        assertEquals("fair", five.breakdown().get("offFaceParallelTerminals"));
        assertEquals("presence caps routing at fair (never poor)", "fair", one.routingRating());
        assertEquals("magnitude does not escalate — still fair, never poor", "fair", five.routingRating());
    }

    @Test
    public void offFaceParallel_redOnRevert_flipsFairToGoodWhenNeutered() {
        // Load-bearing wiring proof. A view whose non-off-face state rates good (a single
        // connection-through-note nudges routing to good) drops to fair when a real off-face hug is
        // present, and rates good again the moment the metric is neutered to zero. If the wiring were
        // removed, both calls would rate good and this test would go red on revert.
        LayoutQualityAssessor.RatingResult withHug = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 1, 1);   // throughNote=1 (good) + offFace=1 (fair)
        LayoutQualityAssessor.RatingResult neutered = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 1, 0);   // same view, off-face count zeroed
        assertEquals("real off-face hug caps the headline at fair", "fair", withHug.rating());
        assertEquals("fair", withHug.breakdown().get("offFaceParallelTerminals"));
        assertEquals("neutering the metric restores the good headline", "good", neutered.rating());
        assertEquals("pass", neutered.breakdown().get("offFaceParallelTerminals"));
    }

    @Test
    public void offFaceParallel_disjointFromTerminalMetric_scoresOnlyItsOwnEntry() {
        // A pure off-face hug scores offFaceParallelTerminals only; the terminal-angle sibling stays
        // pass. And the converse — a pure visible-diagonal terminal scores nonOrthogonalTerminals
        // only, leaving off-face pass — so the two disjoint metrics never bleed into each other.
        LayoutQualityAssessor.RatingResult offFaceOnly = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 1);   // terminals=0, offFace=1
        assertEquals("fair", offFaceOnly.breakdown().get("offFaceParallelTerminals"));
        assertEquals("the disjoint terminal-angle entry stays pass",
                "pass", offFaceOnly.breakdown().get("nonOrthogonalTerminals"));
        LayoutQualityAssessor.RatingResult terminalOnly = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 3, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 0);   // terminals=3/10 fair, offFace=0
        assertEquals("fair", terminalOnly.breakdown().get("nonOrthogonalTerminals"));
        assertEquals("the disjoint off-face entry stays pass",
                "pass", terminalOnly.breakdown().get("offFaceParallelTerminals"));
    }

    @Test
    public void offFaceParallel_disjointCap_bothMetricsEqualsEitherAlone() {
        // Routing tier combines the terminal family by Math.max, not by sum. A connection tripping
        // BOTH a visible-diagonal terminal (3/10 = fair) AND an off-face hug (fair) must rate exactly
        // like either alone — capped once at fair, no additive demotion to poor.
        LayoutQualityAssessor.RatingResult terminalOnly = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 3, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 0);
        LayoutQualityAssessor.RatingResult offFaceOnly = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 1);
        LayoutQualityAssessor.RatingResult both = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 3, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 1);
        assertEquals("fair", terminalOnly.routingRating());
        assertEquals("fair", offFaceOnly.routingRating());
        assertEquals("both-metrics must equal terminal-only (Math.max, no sum)",
                terminalOnly.routingRating(), both.routingRating());
        assertEquals(offFaceOnly.routingRating(), both.routingRating());
    }

    @Test
    public void computeRatingWithBreakdown_21ArgOverload_defaultsOffFacePassByteIdentical() {
        // The 21-arg overload must delegate with offFaceParallelTerminalCount=0 → entry "pass", rating
        // unchanged from the 22-arg form forwarding 0 (the through-note overload precedent).
        LayoutQualityAssessor.RatingResult viaTwentyOne = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0);
        LayoutQualityAssessor.RatingResult viaTwentyTwoZero = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 0);
        assertEquals(viaTwentyTwoZero.rating(), viaTwentyOne.rating());
        assertEquals(viaTwentyTwoZero.breakdown(), viaTwentyOne.breakdown());
        assertEquals("pass", viaTwentyOne.breakdown().get("offFaceParallelTerminals"));
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
    public void detectParentLabelObscured_regressionGuard_backlogViewTitleNoteAutosize() {
        // Regression-guard pin.
        //
        // SCOPE: this is an ASSESSOR-UNIT pin — it verifies
        // that detectParentLabelObscuredByChild does not regress on the canonical "default-
        // sized group with child below label band" shape that the autosize fix preserves.
        // It is NOT an end-to-end test (no accessor.addGroupToView call). The end-to-end
        // height pin lives in
        // ArchiModelAccessorImplTest.addGroupToView_shouldKeepDefaultHeight_whenShortLabel,
        // which guarantees the resolved-height stays at 200 for short labels — combined with
        // this assessor-unit pin, the regression-guard intent is covered transitively.
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
        assertEquals("Regression: default-sized group with short label + child below "
                + "label band must remain at 0 obscured (pass rating).",
                0, result.count());
    }

    // ---- parentLabelObscured: the band below the figure, and what already reports it ----
    //
    // The four tests above all use height=200, so none of them approaches the boundary between the
    // title band and the parent's own bottom edge; their greenness says nothing about it. These do.
    //
    // The band comes from estimateLabelBandHeight, which returns one line or — when the title is too
    // wide for the box — two, and never consults the parent's height. On a container shorter than its
    // own wrapped title the band therefore reaches BELOW the figure, into a region Archi clips away
    // and does not render as title. The question this fixture settles is what happens to the reported
    // numbers if that band is clipped to the figure, and the answer turns on which OTHER metric is
    // already firing on the same geometry.

    /**
     * A container that is NOT a native group (an ArchiMate {@code Grouping}) sized so its own title
     * band is DEEPER than the box: the title is wider than {@code width - TYPE_ICON_WIDTH}, so
     * {@code estimateLabelBandHeight} takes its wrap branch and returns two lines, while the box is
     * only one-and-a-half lines tall.
     *
     * <p>Geometry is derived from the constants, never hardcoded, so the fixture follows them if
     * they move. Only a MEASURED object can reach the wrap branch at all — the collector measures
     * label text for every non-group, non-note object — which is why this is a {@code Grouping}
     * ({@code isGroup=false}, {@code isContainer=true}) and not a native group. The thin, wide
     * swim-lane is the shape Groupings are routinely authored in, and is the shape this was first
     * measured on at the render.</p>
     */
    private static AssessmentNode thinWrapTitledZone(String id, double x, double y) {
        double width = 250.0;
        double height = LayoutQualityAssessor.ESTIMATED_LABEL_HEIGHT * 1.5;   // 30 — under the 40px band
        return new AssessmentNode(id, x, y, width, height, null, false, false,
                "Regulatory Compliance And Reporting Services",
                width - LayoutQualityAssessor.TYPE_ICON_WIDTH + 1.0,          // wider than available -> wraps
                null, null, 0.0, 0.0, 0.0, false, null, true,
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
    }

    /** The doubled band a {@link #thinWrapTitledZone} computes for itself. */
    private static double wrappedBand() {
        return LayoutQualityAssessor.ESTIMATED_LABEL_HEIGHT * 2;
    }

    @Test
    public void parentLabelObscured_aParentInTheDifferenceRegionAlreadyViolatesItsOwnBoundary() {
        // The whole case for clipping the band rests on this. Clipping changes a parent's verdict
        // ONLY when its topmost child sits in [parent.bottom, parent.y + band) — and since that child
        // is the MINIMUM-y child, every child of that parent starts at or below the parent's bottom
        // edge. detectBoundaryViolations flags exactly that shape, over the SAME node list, and is
        // Tier-1L "poor" in the same way parentLabelObscured is.
        //
        // So the two metrics are not independent here: the difference region is a subset of the
        // boundary-violating region. Proven, not asserted.
        AssessmentNode parent = thinWrapTitledZone("zone", 100, 10);
        double parentBottom = parent.y() + parent.height();
        // Mid-window of the difference region, so the fixture is robust to a pixel either way.
        double childY = (parentBottom + parent.y() + wrappedBand()) / 2;
        assertTrue("fixture precondition: the child must sit BELOW the parent's own bottom edge",
                childY >= parentBottom);
        assertTrue("fixture precondition: the child must still sit INSIDE the unclipped band",
                childY < parent.y() + wrappedBand());

        List<AssessmentNode> nodes = List.of(parent,
                new AssessmentNode("escapee", 110, childY, 80, 40, "zone", false, false,
                        "Escapee", 40.0, null, null, 0.0, 0.0, 0.0));

        assertEquals("a child starting at or below the parent's own bottom edge is not on a title"
                + " the parent renders — the band is clipped to the figure, so this does not flag",
                0, assessor.detectParentLabelObscuredByChild(nodes).count());
        assertTrue("the same child is ALREADY reported as escaping its parent's boundary — which is"
                + " what makes the composite rating indifferent to the band, and what means clipping"
                + " the band drops no escapee from the report",
                assessor.detectBoundaryViolations(nodes, false).violationCount() > 0);
    }

    @Test
    public void parentLabelObscured_shouldStillFlag_whenAChildSitsInsideAThinParentsVISIBLEBand() {
        // THE NEGATIVE CONTROL for the clip, on the fixture that provoked it. Clipping the band to
        // the figure must not be mistakable for having disabled the detector on thin containers: the
        // very same 250x30 wrap-titled zone, with a child high enough to be genuinely on the title
        // Archi does render, still flags.
        //
        // This is the half of the argument the spec row made FOR keeping the band unclipped — "a
        // child at y=25 in a 30px box is still colliding with the title" — and it is true. The clip
        // preserves it. Its only effect is on children at or past the bottom edge.
        AssessmentNode parent = thinWrapTitledZone("zone", 100, 10);
        double childY = parent.y() + 5;
        assertTrue("fixture precondition: the child must sit INSIDE the parent's own box",
                childY < parent.y() + parent.height());

        assertEquals("a child on the visible part of a thin container's title still flags", 1,
                assessor.detectParentLabelObscuredByChild(List.of(parent,
                        new AssessmentNode("intruder", 110, childY, 80, 20, "zone", false, false,
                                "Intruder", 40.0, null, null, 0.0, 0.0, 0.0))).count());
    }

    @Test
    public void parentLabelObscured_shouldStillFlag_whenTheBandFitsWhollyInsideTheParent() {
        // THE POSITIVE CONTROL: the ordinary shape, where the clip is a no-op because the band
        // already fits. A parent taller than its own wrapped band, with a child inside that band,
        // is counted exactly as before — so the clip cannot be credited with, or blamed for,
        // anything outside the thin-container region it is scoped to.
        double tall = LayoutQualityAssessor.ESTIMATED_LABEL_HEIGHT * 10;   // far deeper than any band
        AssessmentNode parent = new AssessmentNode("zone", 100, 10, 250, tall, null, false, false,
                "Regulatory Compliance And Reporting Services",
                250 - LayoutQualityAssessor.TYPE_ICON_WIDTH + 1.0,
                null, null, 0.0, 0.0, 0.0, false, null, true,
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
        double childY = parent.y() + wrappedBand() - 1;   // inside the band, derived not picked
        assertTrue("fixture precondition: the band must fit inside this parent",
                wrappedBand() < parent.height());

        assertEquals("the clip is a no-op where the band already fits: this parent is still counted",
                1, assessor.detectParentLabelObscuredByChild(List.of(parent,
                        new AssessmentNode("intruder", 110, childY, 80, 40, "zone", false, false,
                                "Intruder", 40.0, null, null, 0.0, 0.0, 0.0))).count());
    }

    @Test
    public void parentLabelObscured_shouldLeaveTheBandUNCLIPPED_whenTheHeightIsNotUsable() {
        // The clip bites only against a height there is something to clip TO. A box with zero,
        // negative or non-finite height renders no figure and therefore no title, and running such a
        // value through Math.min would rewrite a rating-bearing band on inputs this change is not
        // about — zero collapses it, and NaN propagates through and silently defeats every later
        // comparison. Both are pre-existing degenerate-geometry terrain, deferred separately; the
        // point of this pin is that the clip does NOT quietly change them.
        //
        // Each parent below keeps the verdict the unclipped band gave it. Read as: the guard is
        // `height > 0`, and `NaN > 0` is false, which is why the NaN case lands here and not in a
        // silently-emptied band.
        for (double unusableHeight : new double[]{0.0, -30.0, Double.NaN}) {
            AssessmentNode parent = new AssessmentNode("zone", 100, 10, 250, unusableHeight,
                    null, false, false, "Regulatory Compliance And Reporting Services",
                    250 - LayoutQualityAssessor.TYPE_ICON_WIDTH + 1.0,
                    null, null, 0.0, 0.0, 0.0, false, null, true,
                    AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
            assertEquals("height " + unusableHeight + ": the band is left exactly as the unclipped"
                    + " estimate, so a degenerate box keeps the verdict it has always had",
                    1, assessor.detectParentLabelObscuredByChild(List.of(parent,
                            new AssessmentNode("intruder", 110, parent.y() + wrappedBand() - 1,
                                    80, 40, "zone", false, false, "Intruder", 40.0,
                                    null, null, 0.0, 0.0, 0.0))).count());
        }
    }

    @Test
    public void layoutRating_isPoorOnTheDifferenceRegionFixture_whicheverBandModelIsUsed() {
        // The consequence of the pairing above, at the rating. Both metrics are Tier-1L with no cap
        // and computeLayoutTierLevel takes the max, so a boundary violation alone already pins the
        // layout tier at "poor". Varying parentLabelObscured 1 -> 0 while boundaryViolations stays 1
        // is exactly the delta a clipped band can produce, and it moves nothing.
        //
        // This is the single-variable form. Asserting only through assess() would leave the claim
        // resting on whichever band the code happens to ship.
        LayoutQualityAssessor.RatingResult withBothFiring = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 0, false,
                1, 1, 0, 0,
                0, 0, 0, 1.0);
        LayoutQualityAssessor.RatingResult withOnlyTheBoundaryFiring = assessor.computeRatingWithBreakdown(
                0, 0, 50.0, 80, 0, 0, 0, 0, 0, false,
                1, 0, 0, 0,
                0, 0, 0, 1.0);

        // Pin the WIRING before pinning the outcome. Both calls above pass eighteen positional
        // arguments, so if the parentLabelObscured slot were swapped with a same-typed neighbour
        // the two ratings would still come back "poor" and this test would assert nothing while
        // looking like it asserted the claim in its own comment. Reading the breakdown back makes
        // the argument positions self-verifying: this is the only pair of assertions here that can
        // distinguish "I varied parentLabelObscured" from "I varied some other count".
        assertEquals("the first call must actually be the one with parentLabelObscured firing",
                "poor", withBothFiring.breakdown().get("parentLabelObscured"));
        assertEquals("the second call must actually have parentLabelObscured at zero",
                "pass", withOnlyTheBoundaryFiring.breakdown().get("parentLabelObscured"));
        assertEquals("and boundaryViolations must be held firing across BOTH, or the pair is not"
                + " single-variable", "poor", withBothFiring.breakdown().get("boundaryViolations"));
        assertEquals("poor", withOnlyTheBoundaryFiring.breakdown().get("boundaryViolations"));

        assertEquals("poor", withBothFiring.layoutRating());
        assertEquals("dropping parentLabelObscured to zero cannot lift the layout rating while the"
                + " boundary violation it implies is still reported",
                "poor", withOnlyTheBoundaryFiring.layoutRating());
        assertEquals("poor", withOnlyTheBoundaryFiring.rating());

        // And at the composite, on the real fixture, through the real assess() path.
        AssessmentNode parent = thinWrapTitledZone("zone", 100, 10);
        double childY = (parent.y() + parent.height() + parent.y() + wrappedBand()) / 2;
        LayoutAssessmentResult assessed = assessor.assess(List.of(parent,
                new AssessmentNode("escapee", 110, childY, 80, 40, "zone", false, false,
                        "Escapee", 40.0, null, null, 0.0, 0.0, 0.0)), List.of(), false);
        assertEquals("the view is rated poor on this fixture regardless of which band is used",
                "poor", assessed.layoutRating());
    }

    @Test
    public void parentLabelObscured_theDegenerateEscape_aZeroHeightChildAtTheParentsBottomEdge() {
        // The ONE shape where the pairing above does not hold, recorded so it is not mistaken for a
        // counter-example later. detectBoundaryViolations uses a strict >, so a child with NO height
        // sitting exactly on the parent's bottom edge escapes it — and the clipped band ends at that
        // same edge, so nothing flags this shape at all: the clip removes a count with no sibling
        // metric reporting the object.
        //
        // It is the honest limit of the "subset of boundaryViolations" claim, and it is narrow: a
        // zero-height child draws nothing, so there is no title to obscure and no figure to escape.
        // Pinned as an OUTCOME so a later reader can see the case was found and weighed rather than
        // missed — not as an argument that the clip is wrong.
        AssessmentNode parent = thinWrapTitledZone("zone", 100, 10);
        List<AssessmentNode> nodes = List.of(parent,
                new AssessmentNode("flat", 110, parent.y() + parent.height(), 80, 0, "zone",
                        false, false, "Flat", 40.0, null, null, 0.0, 0.0, 0.0));

        assertEquals("a zero-height child exactly on the clipped band's bottom edge does not flag",
                0, assessor.detectParentLabelObscuredByChild(nodes).count());
        assertEquals("and it is NOT a boundary violation either — the one shape where clipping the"
                + " band removes a count with nothing else reporting it",
                0, assessor.detectBoundaryViolations(nodes, false).violationCount());
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
        // e1 carries a top-right icon. At its true 80x80 size the icon spans x in [40,120] and,
        // clipped to the 55px-tall element box, y in [0,55] — reaching the neighbour e2 at
        // (50,0,30,30). Sizing from the fixed 24px icon instead would place it at x in [96,120]
        // and miss e2 entirely, which is what this test pins.
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

    // ---- Image-rect clamping to the element box ----
    //
    // Archi CLIPS an element's image to the element box — an image larger than its element is cut
    // off at the box edge, never drawn outside it. The estimated image rectangle is therefore the
    // intersection of the anchored natural-size rectangle with the element box. These tests pin
    // that clamp through detectImageSiblingOverlap, which compares an element's image rectangle
    // against each sibling's box.

    /** An element box of 40x40 at (100,100) carrying a grossly oversized 200x200 icon. */
    private static AssessmentNode oversizedIconNode(String id, String position) {
        return new AssessmentNode(id, 100, 100, 40, 40, null, false, false, id, 0.0,
                "img/" + id + ".png", position, 0.0, 200.0, 200.0);
    }

    @Test
    public void estimateImageBounds_shouldClampToElementBox_onEveryAnchoredPosition() {
        // Each probe sits in the region the UNCLAMPED 200x200 rectangle would have covered but the
        // 40x40 element box at (100,100) does not — space where Archi draws nothing. Every one of
        // the nine anchored positions must therefore report 0. Note the probes to the left of and
        // above the box: a right- or centre-anchored oversized icon places its ORIGIN outside the
        // element, so clamping the extent alone would leave these reachable.
        double[][] probes = {
                {200, 200}, {30, 200}, {-40, 200},   // top-left, top-centre, top-right
                {200, 30},  {30, 30},  {-40, 30},    // middle-left, middle-centre, middle-right
                {200, -40}, {30, -40}, {-40, -40},   // bottom-left, bottom-centre, bottom-right
        };
        String[] positions = {
                "top-left", "top-centre", "top-right",
                "middle-left", "middle-centre", "middle-right",
                "bottom-left", "bottom-centre", "bottom-right",
        };
        for (int i = 0; i < positions.length; i++) {
            List<AssessmentNode> nodes = List.of(
                    oversizedIconNode("icon", positions[i]),
                    node("probe", probes[i][0], probes[i][1], 20, 20));
            assertEquals("clamped icon rect must not reach outside the element box: " + positions[i],
                    0, assessor.detectImageSiblingOverlap(nodes).count());
        }
    }

    @Test
    public void estimateImageBounds_shouldLeaveRectUnchanged_whenIconFitsInsideElement() {
        // The common case must be untouched. A 24x24 top-left icon on a 120x55 box spans x[0,24];
        // the clamp must not move that edge. A probe starting at x=23 overlaps it, one starting at
        // x=24 does not (rectanglesOverlap is strict), which pins the edge to the pixel.
        AssessmentNode fitting = new AssessmentNode("icon", 0, 0, 120, 55, null, false, false,
                "Icon", 0.0, "img/icon.png", "top-left", 0.0, 24.0, 24.0);
        assertEquals("fitting icon's right edge stays at x=24", 1,
                assessor.detectImageSiblingOverlap(
                        List.of(fitting, node("probe", 23, 0, 10, 10))).count());
        assertEquals("fitting icon's right edge stays at x=24", 0,
                assessor.detectImageSiblingOverlap(
                        List.of(fitting, node("probe", 24, 0, 10, 10))).count());

        // The headless 24px fallback (natural dimensions absent) follows the same path.
        AssessmentNode fallback = new AssessmentNode("icon", 0, 0, 120, 55, null, false, false,
                "Icon", 0.0, "img/icon.png", "top-left", 0.0, 0.0, 0.0);
        assertEquals("fallback icon's right edge stays at x=24", 1,
                assessor.detectImageSiblingOverlap(
                        List.of(fallback, node("probe", 23, 0, 10, 10))).count());
        assertEquals("fallback icon's right edge stays at x=24", 0,
                assessor.detectImageSiblingOverlap(
                        List.of(fallback, node("probe", 24, 0, 10, 10))).count());

        // An icon exactly filling its element is the clamp's boundary case: still unchanged.
        AssessmentNode exact = new AssessmentNode("icon", 0, 0, 40, 40, null, false, false,
                "Icon", 0.0, "img/icon.png", "top-left", 0.0, 40.0, 40.0);
        assertEquals("exact-fit icon's right edge stays at x=40", 1,
                assessor.detectImageSiblingOverlap(
                        List.of(exact, node("probe", 39, 0, 10, 10))).count());
        assertEquals("exact-fit icon's right edge stays at x=40", 0,
                assessor.detectImageSiblingOverlap(
                        List.of(exact, node("probe", 40, 0, 10, 10))).count());

        // The clamp is applied uniformly after the anchor switch, so it must be a no-op on every
        // anchor — not just top-left. bottom-right on a 120x55 box puts a fitting 24x24 icon at
        // x[96,120] y[31,55]; middle-centre puts it at x[48,72] y[15.5,39.5]. Both edges pinned.
        AssessmentNode bottomRight = new AssessmentNode("icon", 0, 0, 120, 55, null, false, false,
                "Icon", 0.0, "img/icon.png", "bottom-right", 0.0, 24.0, 24.0);
        assertEquals("fitting bottom-right icon's left edge stays at x=96", 1,
                assessor.detectImageSiblingOverlap(
                        List.of(bottomRight, node("probe", 87, 31, 10, 10))).count());
        assertEquals("fitting bottom-right icon's left edge stays at x=96", 0,
                assessor.detectImageSiblingOverlap(
                        List.of(bottomRight, node("probe", 86, 31, 10, 10))).count());

        AssessmentNode middleCentre = new AssessmentNode("icon", 0, 0, 120, 55, null, false, false,
                "Icon", 0.0, "img/icon.png", "middle-centre", 0.0, 24.0, 24.0);
        assertEquals("fitting middle-centre icon's left edge stays at x=48", 1,
                assessor.detectImageSiblingOverlap(
                        List.of(middleCentre, node("probe", 39, 20, 10, 10))).count());
        assertEquals("fitting middle-centre icon's left edge stays at x=48", 0,
                assessor.detectImageSiblingOverlap(
                        List.of(middleCentre, node("probe", 38, 20, 10, 10))).count());
    }

    @Test
    public void estimateImageBounds_shouldReportNoImage_onDegenerateElementBox() {
        // A degenerate element box clamps the rectangle to zero extent on that axis, which is not a
        // rectangle at all — nothing renders — so the assessor reports no image rather than a
        // zero-area rectangle collapsed onto a line. The distinction is load-bearing: rectanglesOverlap
        // uses strict inequalities, so a zero-width rectangle whose pinned x falls INSIDE a sibling's
        // span would register as an overlap. The straddling probes below are the ones that catch that;
        // a probe placed clear of the element would pass either way and prove nothing.
        AssessmentNode zeroWidth = new AssessmentNode("zw", 100, 100, 0, 40, null, false, false,
                "ZW", 0.0, "img/zw.png", "top-left", 0.0, 200.0, 200.0);
        AssessmentNode zeroHeight = new AssessmentNode("zh", 100, 100, 40, 0, null, false, false,
                "ZH", 0.0, "img/zh.png", "top-left", 0.0, 200.0, 200.0);

        // Straddling probes: x in [90,110] contains the zero-width element's pinned x=100, and
        // y in [90,110] contains the zero-height element's pinned y=100.
        assertEquals("a zero-width element has no image rect, even for a probe straddling its edge",
                0, assessor.detectImageSiblingOverlap(
                        List.of(zeroWidth, node("probe", 90, 100, 20, 40))).count());
        assertEquals("a zero-height element has no image rect, even for a probe straddling its edge",
                0, assessor.detectImageSiblingOverlap(
                        List.of(zeroHeight, node("probe", 100, 90, 40, 20))).count());

        // The same holds for the containment axis, which reads the rect through overlayIconBounds.
        assertEquals("a degenerate ancestor contributes no icon rect", 0,
                assessor.detectOverlayIconCollision(List.of(
                        zeroWidth,
                        new AssessmentNode("child", 90, 100, 20, 40, "zw", false, false, "Child",
                                0.0, "img/child.png", "top-left", 0.0, 24.0, 24.0))).count());

        // And a probe placed clear of the element still reports nothing (the ordinary case).
        assertEquals("zero-width element cannot reach a probe outside it", 0,
                assessor.detectImageSiblingOverlap(
                        List.of(zeroWidth, node("probe", 200, 200, 20, 20))).count());
    }

    // ---- Overlay-icon collision (containment axis) tests ----

    /**
     * Builds an icon-bearing node. Coordinates are absolute (the assessor's contract), so a
     * "nested" child's x/y already include the parent offset. Natural dimensions are supplied
     * directly because the archive read that normally provides them is unavailable headless.
     */
    private static AssessmentNode iconNode(String id, double x, double y, double w, double h,
                                           String parentId, String iconPosition) {
        return new AssessmentNode(id, x, y, w, h, parentId, false, false, id, 0.0,
                "img/" + id + ".png", iconPosition, 0.0, 24.0, 24.0);
    }

    @Test
    public void detectOverlayIconCollision_shouldDetect_whenNestedChildIconSharesParentCorner() {
        // The live exemplar: a zone container with a bottom-left icon and a nested cluster
        // that carries its own bottom-left icon. Container icon rect is x[0,24] y[76,100];
        // the child's is x[10,34] y[68,92] — they overlap, and the sibling detector cannot
        // see the pair because the two nodes sit in different parentId buckets.
        List<AssessmentNode> nodes = List.of(
                iconNode("zone", 0, 0, 200, 100, null, "bottom-left"),
                iconNode("cluster", 10, 8, 180, 84, "zone", "bottom-left"));

        LayoutQualityAssessor.OverlayIconCollisionResult result =
                assessor.detectOverlayIconCollision(nodes);

        assertEquals(1, result.count());
        assertTrue(result.descriptions().get(0).contains("zone"));
        assertTrue(result.descriptions().get(0).contains("cluster"));
    }

    @Test
    public void detectOverlayIconCollision_shouldNotDetect_whenIconsInDifferentCorners() {
        // Same nesting, but the child's icon sits bottom-right: x[166,190] never reaches
        // the container's bottom-left band at x[0,24].
        List<AssessmentNode> nodes = List.of(
                iconNode("zone", 0, 0, 200, 100, null, "bottom-left"),
                iconNode("cluster", 10, 8, 180, 84, "zone", "bottom-right"));

        assertEquals(0, assessor.detectOverlayIconCollision(nodes).count());
    }

    @Test
    public void detectOverlayIconCollision_shouldNotDetect_whenNestedChildHasNoIcon() {
        // Ordinary containment: a child nested inside an iconed container is normal layout,
        // never a collision. Only icon-vs-icon counts.
        List<AssessmentNode> nodes = List.of(
                iconNode("zone", 0, 0, 200, 100, null, "bottom-left"),
                childNode("cluster", 10, 8, 180, 84, "zone"));

        assertEquals(0, assessor.detectOverlayIconCollision(nodes).count());
    }

    @Test
    public void detectOverlayIconCollision_shouldIgnoreFillImages_onEitherSide() {
        // A 'fill' image is a background, not an overlay icon, and its estimated rect is the
        // whole element box — without this guard every descendant of a fill-imaged container
        // would be flagged.
        List<AssessmentNode> parentFills = List.of(
                iconNode("zone", 0, 0, 200, 100, null, "fill"),
                iconNode("cluster", 10, 8, 180, 84, "zone", "bottom-left"));
        List<AssessmentNode> childFills = List.of(
                iconNode("zone", 0, 0, 200, 100, null, "bottom-left"),
                iconNode("cluster", 10, 8, 180, 84, "zone", "fill"));

        assertEquals(0, assessor.detectOverlayIconCollision(parentFills).count());
        assertEquals(0, assessor.detectOverlayIconCollision(childFills).count());
    }

    @Test
    public void detectOverlayIconCollision_shouldDetect_acrossTwoNestingLevels() {
        // The collision is with the GRANDparent, not the immediate parent: the middle node
        // carries no icon, so a direct-parent-only walk would miss this.
        List<AssessmentNode> nodes = List.of(
                iconNode("region", 0, 0, 400, 200, null, "bottom-left"),
                childNode("zone", 0, 100, 200, 100, "region"),
                iconNode("cluster", 0, 120, 180, 80, "zone", "bottom-left"));

        LayoutQualityAssessor.OverlayIconCollisionResult result =
                assessor.detectOverlayIconCollision(nodes);

        assertEquals(1, result.count());
        assertTrue(result.descriptions().get(0).contains("region"));
    }

    @Test
    public void detectOverlayIconCollision_shouldCountEachPairOnce() {
        // A 3-level chain whose icons all land on the same bottom-left band: the colliding
        // pairs are region/zone, region/cluster and zone/cluster = 3, not 6 (each pair is
        // counted once, not once per direction).
        List<AssessmentNode> nodes = List.of(
                iconNode("region", 0, 0, 400, 200, null, "bottom-left"),
                iconNode("zone", 0, 100, 200, 100, "region", "bottom-left"),
                iconNode("cluster", 0, 120, 180, 80, "zone", "bottom-left"));

        assertEquals(3, assessor.detectOverlayIconCollision(nodes).count());
    }

    /**
     * Pins the cycle-safety of the ancestor walk. A malformed model whose parent links form a
     * cycle must terminate rather than spin: without the visited set this loops forever, so the
     * timeout is the assertion that matters. The count is 2 because each node reaches the other
     * exactly once before the walk is cut off.
     */
    @Test(timeout = 5000)
    public void detectOverlayIconCollision_shouldTerminate_whenParentLinksFormACycle() {
        List<AssessmentNode> nodes = List.of(
                iconNode("a", 0, 0, 200, 100, "b", "bottom-left"),
                iconNode("b", 0, 0, 200, 100, "a", "bottom-left"));

        assertEquals(2, assessor.detectOverlayIconCollision(nodes).count());
    }

    @Test
    public void detectOverlayIconCollision_shouldNotPairTopLevelNodes_whenAnIdIsNull() {
        // A null-id node must not become the resolved parent of every top-level node (whose
        // parentId is also null). Both nodes here are top-level and share a corner, so without
        // the null-key guard they would be reported as a containment pair.
        List<AssessmentNode> nodes = List.of(
                iconNode(null, 0, 0, 200, 100, null, "bottom-left"),
                iconNode("other", 0, 0, 200, 100, null, "bottom-left"));

        assertEquals(0, assessor.detectOverlayIconCollision(nodes).count());
    }

    @Test
    public void detectOverlayIconCollision_shouldNotChangeImageSiblingOverlap() {
        // The containment-axis detector is additive: the shipped sibling counter must return
        // exactly what it returned before, for the very fixture the new detector flags.
        List<AssessmentNode> nodes = List.of(
                iconNode("zone", 0, 0, 200, 100, null, "bottom-left"),
                iconNode("cluster", 10, 8, 180, 84, "zone", "bottom-left"));

        assertEquals(1, assessor.detectOverlayIconCollision(nodes).count());
        assertEquals(0, assessor.detectImageSiblingOverlap(nodes).count());
    }

    @Test
    public void assess_overlayIconCollision_shouldNotAffectRating() {
        // Identical geometry, icons present vs absent. The collision is detected, yet every
        // rating output is byte-identical: overlay-icon collision is informational only.
        List<AssessmentNode> withIcons = List.of(
                iconNode("zone", 0, 0, 200, 100, null, "bottom-left"),
                iconNode("cluster", 10, 8, 180, 84, "zone", "bottom-left"));
        // The control differs ONLY in the image fields — same ids, names and geometry — so any
        // rating movement can only come from the new detector. (Using the bare node()/childNode()
        // factories here would also drop the names, which moves the rating-promoted
        // parentLabelObscured metric and would make this a two-variable comparison.)
        List<AssessmentNode> withoutIcons = List.of(
                new AssessmentNode("zone", 0, 0, 200, 100, null, false, false, "zone", 0.0,
                        null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("cluster", 10, 8, 180, 84, "zone", false, false, "cluster", 0.0,
                        null, null, 0.0, 0.0, 0.0));

        LayoutAssessmentResult withFields = assessor.assess(withIcons, List.of(), false);
        LayoutAssessmentResult withoutFields = assessor.assess(withoutIcons, List.of(), false);

        // The collision really was detected (otherwise the test proves nothing).
        assertEquals(1, withFields.overlayIconCollisionCount());
        assertEquals(0, withoutFields.overlayIconCollisionCount());
        // ...yet none of the rating outputs move.
        assertEquals(withoutFields.overallRating(), withFields.overallRating());
        assertEquals(withoutFields.layoutRating(), withFields.layoutRating());
        assertEquals(withoutFields.routingRating(), withFields.routingRating());
        assertEquals(withoutFields.ratingBreakdown(), withFields.ratingBreakdown());
    }

    // ---- Own-icon-over-own-label detection (the glyph buries the element's own title) ----
    //
    // The third icon axis. detectImageSiblingOverlap compares an icon against sibling BOXES;
    // detectOverlayIconCollision compares it against an ANCESTOR's icon. Because the icon rectangle
    // is clamped to its own element box, the icon and the title it covers both live inside that box
    // and are never compared — so a 64px specialization glyph can sit squarely on the element name
    // while both shipped counts read zero. Archi draws the title horizontally centred in a band at
    // the top of the element, so the reachable overlap is the icon rect against the CENTRED title
    // rect, not against the full top strip.

    /**
     * The live exemplar (a retail-bank layered view): a wide centred title on a narrow box carrying
     * a 64x64 specialization glyph. Only the box WIDTH varies between the positive case and its
     * negative control, so the flip is single-variable. Natural dimensions are supplied directly
     * because the archive read that normally provides them is unavailable headless.
     */
    private static AssessmentNode glyphedCard(double width, String iconPosition) {
        return new AssessmentNode("card", 0, 0, width, 55, null, false, false,
                "Meridian Rewards Credit Card", 100.0,
                "img/card.png", iconPosition, 0.0, 64.0, 64.0);
    }

    /**
     * THE FIXTURE FOR THE DIFFERENCE — a {@link #glyphedCard} that differs in exactly one component:
     * {@code textAlignment}.
     *
     * <p>It has to exist before the detector can be changed, for the same reason {@code zone()} had
     * to exist before {@code isContainer} could be trusted: every other fixture in this file leaves
     * {@code textAlignment} at CENTRE, so every one of them agrees with BOTH the old unconditional
     * centring and the new alignment-aware model. A pin built only on those proves nothing about
     * which model is in force.
     *
     * <p>The difference this isolates was measured at the render (Archi 5.10, SVG export of a probe
     * view placing ONE element three times, varying only this feature): a LEFT-aligned title's glyph
     * run starts at {@code x + 4}, a CENTRE one is centred on the box, and a RIGHT one ends at
     * {@code x + width - 20}. It is not hypothetical — Archi's own UI stamps LEFT on every
     * {@code Grouping} drawn from the palette, so the non-centred case is the COMMON one in any
     * model a human built, while objects created through this server keep the EMF default CENTRE.
     */
    private static AssessmentNode alignedGlyphedCard(double width, String iconPosition,
                                                      int textAlignment) {
        return new AssessmentNode("card", 0, 0, width, 55, null, false, false,
                "Meridian Rewards Credit Card", 100.0,
                "img/card.png", iconPosition, 0.0, 64.0, 64.0,
                false, null, false, textAlignment, AssessmentNode.TEXT_POSITION_TOP);
    }

    @Test
    public void detectOwnIconOverLabel_shouldDetect_whenTopRightGlyphReachesTheCentredTitle() {
        // 120x55 box: the 100px title centres on x=60, spanning x[10,110] in the 20px top band.
        // The 64x64 top-right glyph anchors at x=56 and clamps to the box, covering
        // x[56,120] y[0,55] — squarely over the last word of the name.
        LayoutQualityAssessor.OwnIconOverLabelResult result =
                assessor.detectOwnIconOverLabel(List.of(glyphedCard(120, "top-right")));

        assertEquals(1, result.count());
        assertTrue(result.descriptions().get(0).contains("Meridian Rewards Credit Card"));
        assertTrue(result.descriptions().get(0).contains("top-right"));
    }

    @Test
    public void detectOwnIconOverLabel_shouldNotDetect_whenTheBoxIsWideEnough() {
        // Single-variable widen, 120 -> 400: the centred title now spans x[150,250] while the
        // top-right glyph anchors at x=336. Nothing else about the element changed.
        assertEquals(0,
                assessor.detectOwnIconOverLabel(List.of(glyphedCard(400, "top-right"))).count());
    }

    // ---- The title's horizontal placement is the OBJECT's, not a constant ----
    //
    // On a 400px box carrying a 100px title, the three alignments put the glyph run in three
    // disjoint places, and a corner icon reaches exactly one of them:
    //
    //     LEFT   run x[  4,104]   hits a top-LEFT  icon x[  0, 64],  misses top-right
    //     CENTRE run x[150,250]   hits NEITHER  (this is the shipped negative control)
    //     RIGHT  run x[280,380]   hits a top-RIGHT icon x[336,400],  misses top-left
    //
    // So each case below is decided by the alignment alone: under the old unconditional centring
    // every one of them returns 0. Only the box's alignment differs between a positive case and
    // its control — same element, same name, same width, same icon.

    @Test
    public void detectOwnIconOverLabel_shouldDetect_whenALeftAlignedTitleMeetsATopLeftGlyph() {
        // The case Archi's own UI creates: it stamps LEFT on a Grouping drawn from the palette, so
        // this is the ORDINARY alignment in any human-authored model, not an exotic one. A centred
        // model puts the run at x[150,250] and reports a clean zero for a collision that renders.
        assertEquals(1, assessor.detectOwnIconOverLabel(List.of(
                alignedGlyphedCard(400, "top-left", AssessmentNode.TEXT_ALIGNMENT_LEFT))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldDetect_whenARightAlignedTitleMeetsATopRightGlyph() {
        // The mirror case, and the one the shipped centred model misses most often, because
        // top-right is Archi's DEFAULT image position and the specialization decorator's fixed one.
        assertEquals(1, assessor.detectOwnIconOverLabel(List.of(
                alignedGlyphedCard(400, "top-right", AssessmentNode.TEXT_ALIGNMENT_RIGHT))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldNotDetect_whenAlignmentCarriesTheTitleClearOfTheGlyph() {
        // NEGATIVE CONTROLS, one per positive above, differing ONLY in textAlignment. Without
        // these the change could be "flag more often" rather than "flag in the right place".
        assertEquals("a centred title on a wide box clears a top-left glyph", 0,
                assessor.detectOwnIconOverLabel(List.of(
                        alignedGlyphedCard(400, "top-left", AssessmentNode.TEXT_ALIGNMENT_CENTRE))).count());
        assertEquals("a left-aligned title clears a top-right glyph", 0,
                assessor.detectOwnIconOverLabel(List.of(
                        alignedGlyphedCard(400, "top-right", AssessmentNode.TEXT_ALIGNMENT_LEFT))).count());
        assertEquals("a right-aligned title clears a top-left glyph", 0,
                assessor.detectOwnIconOverLabel(List.of(
                        alignedGlyphedCard(400, "top-left", AssessmentNode.TEXT_ALIGNMENT_RIGHT))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldTreatAnUnknownAlignmentAsCentred() {
        // Degrade, do not throw. The value comes straight off the model; a future Archi alignment
        // constant must not fail an assessment, and centre is the EMF default to fall back to.
        //
        // Asserted against ABSOLUTE expected counts, not against another call. Comparing the
        // unknown value to CENTRE would be tautological: neither has an explicit `case`, so both
        // land in the same `default` arm and the comparison reduces to x == x — it would hold for
        // any implementation, including a broken default. The discriminating claim is that an
        // unknown alignment behaves like CENTRE and NOT like LEFT, and only the absolute values
        // say that: on a 400px box a top-left glyph reaches a LEFT title (1) and not a centred
        // one (0).
        assertEquals("an unrecognised alignment must NOT be treated as LEFT", 0,
                assessor.detectOwnIconOverLabel(List.of(
                        alignedGlyphedCard(400, "top-left", 99))).count());
        assertEquals("...and must NOT be treated as RIGHT either", 0,
                assessor.detectOwnIconOverLabel(List.of(
                        alignedGlyphedCard(400, "top-right", 99))).count());
        // The contrast that gives those zeros their meaning: the same geometry DOES flag under the
        // alignments the unknown value must not be mistaken for.
        assertEquals(1, assessor.detectOwnIconOverLabel(List.of(
                alignedGlyphedCard(400, "top-left", AssessmentNode.TEXT_ALIGNMENT_LEFT))).count());
        assertEquals(1, assessor.detectOwnIconOverLabel(List.of(
                alignedGlyphedCard(400, "top-right", AssessmentNode.TEXT_ALIGNMENT_RIGHT))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldStayStable_whenTheTitleNearlyFillsItsBox() {
        // These geometries DO drive ownLabelBounds' clamp, unlike the obvious ones: on a 120px box
        // a 110px RIGHT-aligned run wants to start at -10 (the 4+16 inset exceeds the 10px of
        // slack), and a 118px LEFT-aligned run wants to start at 4 when only 2 is available. Both
        // are pulled back inside the box, because a rectangle outside the element is geometry that
        // never renders — Archi clips a figure's contents to the figure.
        //
        // HONEST LIMIT OF THIS PIN: the clamp is DEFENSIVE and is not independently observable
        // through this detector's count. A title that nearly fills its box overlaps a corner glyph
        // whether or not the run was pulled back, so no count distinguishes the two. What is pinned
        // here is that these near-degenerate widths stay stable and keep flagging under every
        // alignment; the clamp itself is asserted by construction, not by outcome. Naming that
        // rather than implying a stronger pin — a test whose condition it cannot observe must say so.
        AssessmentNode rightNearFull = new AssessmentNode("card", 0, 0, 120, 55, null, false, false,
                "Meridian Rewards Credit Card", 110.0, "img/card.png", "top-right", 0.0, 64.0, 64.0,
                false, null, false, AssessmentNode.TEXT_ALIGNMENT_RIGHT, AssessmentNode.TEXT_POSITION_TOP);
        AssessmentNode leftNearFull = new AssessmentNode("card", 0, 0, 120, 55, null, false, false,
                "Meridian Rewards Credit Card", 118.0, "img/card.png", "top-left", 0.0, 64.0, 64.0,
                false, null, false, AssessmentNode.TEXT_ALIGNMENT_LEFT, AssessmentNode.TEXT_POSITION_TOP);

        assertEquals(1, assessor.detectOwnIconOverLabel(List.of(rightNearFull)).count());
        assertEquals(1, assessor.detectOwnIconOverLabel(List.of(leftNearFull)).count());
        // And the run is never reported as a gap — these titles WERE measured.
        assertFalse(assessor.detectOwnIconOverLabel(List.of(rightNearFull)).unmeasuredTitle());
    }

    @Test
    public void assess_ownIconOverLabel_isTheOnlyAxisThatSeesTheOwnLabelOverlap() {
        // The reported false-negative, end to end: a lone element on the view. The sibling detector
        // has no sibling to compare against and the containment detector has no ancestor, so both
        // shipped counts are honestly zero — which is exactly why the view read "0 icon collisions"
        // while the render showed the glyph on the title.
        LayoutAssessmentResult result =
                assessor.assess(List.of(glyphedCard(120, "top-right")), List.of(), false);

        assertEquals("the reported miss is now counted", 1, result.ownIconOverLabelCount());
        assertEquals("the sibling axis cannot see it", 0, result.imageSiblingOverlapCount());
        assertEquals("the containment axis cannot see it", 0, result.overlayIconCollisionCount());
        assertNotNull(result.ownIconOverLabelDescriptions());
        assertEquals(1, result.ownIconOverLabelDescriptions().size());
    }

    @Test
    public void detectOwnIconOverLabel_shouldSkip_fillImagesMissingImagesAndUnmeasuredLabels() {
        // A 'fill' image is a background, not an overlay glyph — the same guard the containment
        // detector applies. Its estimated rect IS the element box, so without this every titled
        // element with a background would be flagged.
        assertEquals("a fill image is a background, not a glyph", 0,
                assessor.detectOwnIconOverLabel(List.of(glyphedCard(120, "fill"))).count());
        // No image at all: nothing can be drawn over the title.
        assertEquals("no image, nothing to overlap the title", 0,
                assessor.detectOwnIconOverLabel(List.of(new AssessmentNode(
                        "card", 0, 0, 120, 55, null, false, false, "Meridian Rewards Credit Card",
                        100.0, null, null, 0.0, 64.0, 64.0))).count());
        // Label width never measured: there is no title rectangle to claim. Abstaining is the
        // correct answer — a fabricated width would manufacture findings out of nothing.
        assertEquals("an unmeasured label yields no title rect", 0,
                assessor.detectOwnIconOverLabel(List.of(new AssessmentNode(
                        "card", 0, 0, 120, 55, null, false, false, "Meridian Rewards Credit Card",
                        0.0, "img/card.png", "top-right", 0.0, 64.0, 64.0))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldReasonFromGeometryNotFromIconPosition() {
        // bottom-left on a TALL box: the glyph occupies y[136,200], nowhere near the title band.
        // Position alone proves nothing — this one is bottom-anchored and clean.
        assertEquals("a glyph that never reaches the title band is not flagged", 0,
                assessor.detectOwnIconOverLabel(List.of(new AssessmentNode(
                        "card", 0, 0, 120, 200, null, false, false, "Meridian Rewards Credit Card",
                        100.0, "img/card.png", "bottom-left", 0.0, 64.0, 64.0))).count());
        // middle-right on the SHORT box: the 64px glyph is taller than the 55px box, so clamping
        // pins it to y[0,55] and it reaches the band despite being vertically centre-anchored.
        assertEquals("a middle-anchored glyph that DOES reach the title band is flagged", 1,
                assessor.detectOwnIconOverLabel(List.of(glyphedCard(120, "middle-right"))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldUseTheWrappedTitleBand_whenTheNameWraps() {
        // A 200px title in a 120px box wraps (available width is 120 - 16 = 104), so the title
        // occupies TWO lines — a 40px band, not 20. The bottom-left glyph on an 89px-tall box
        // clamps to y[25,89]: it clears a single-line band and lands on the wrapped second line.
        assertEquals("a wrapped title occupies a doubled band the glyph reaches", 1,
                assessor.detectOwnIconOverLabel(List.of(new AssessmentNode(
                        "card", 0, 0, 120, 89, null, false, false, "Meridian Rewards Credit Card",
                        200.0, "img/card.png", "bottom-left", 0.0, 64.0, 64.0))).count());
        // Identical geometry, but a title that fits on one line (90 <= 104) leaves the glyph below
        // the 20px band. The doubling is the only difference between the two.
        assertEquals("a single-line title leaves the same glyph clear", 0,
                assessor.detectOwnIconOverLabel(List.of(new AssessmentNode(
                        "card", 0, 0, 120, 89, null, false, false, "Card", 90.0,
                        "img/card.png", "bottom-left", 0.0, 64.0, 64.0))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldFlag_whenAThinContainersWrappedBandOutgrowsItsOwnBox() {
        // A 250x30 container whose title wraps computes a 40px band — 10px deeper than the box. This
        // detector CANNOT observe that overhang, and the assertion below says only what it can see.
        //
        // Why it cannot: the rectangle this band is tested against comes from estimateImageBounds,
        // which clamps BOTH origin and extent into the element box and returns null for a degenerate
        // result. So the icon rect always satisfies iconY >= box top and iconY < box bottom, with
        // positive height. The vertical arm of the overlap test is
        //     iconY < bandTop + bandHeight   &&   iconY + iconH > bandTop      (bandTop = box top)
        // — the right-hand clause holds for any such rect, and the left-hand one already holds at
        // bandHeight = box height, so shrinking the band from 40 to 30 cannot flip either. A slice of
        // band BELOW the figure can never intersect a rectangle already clipped TO the figure.
        //
        // The consequence for whoever changes the band next: this pin is the outcome, not the band.
        // A test here asserting that clipping the band CHANGES a count would be green against a
        // clamp that never fired, which is the failure mode this file has already been bitten by.
        AssessmentNode thinIconedZone = new AssessmentNode("zone", 100, 10, 250,
                LayoutQualityAssessor.ESTIMATED_LABEL_HEIGHT * 1.5, null, false, false,
                "Regulatory Compliance And Reporting Services",
                250 - LayoutQualityAssessor.TYPE_ICON_WIDTH + 1.0,
                "img/badge.png", "top-left", 0.0, 64.0, 64.0,
                false, null, true, AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);

        assertEquals("the glyph sits on the container's own title whether the band is measured to"
                + " 40px or clipped to the 30px box — both reach it",
                1, assessor.detectOwnIconOverLabel(List.of(thinIconedZone)).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldCapDescriptions_withoutCappingTheCount() {
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            nodes.add(new AssessmentNode("card" + i, 0, i * 100, 120, 55, null, false, false,
                    "Card " + i, 100.0, "img/card.png", "top-right", 0.0, 64.0, 64.0));
        }

        LayoutQualityAssessor.OwnIconOverLabelResult result =
                assessor.detectOwnIconOverLabel(nodes);

        assertEquals("every offender is counted", 12, result.count());
        assertEquals("but only the first MAX_DESCRIPTIONS are described",
                10, result.descriptions().size());
    }

    @Test
    public void assess_ownIconOverLabel_shouldNameTheFindingInTheSuggestions() {
        // Single-variable pair: identical geometry, names and label widths; the control differs
        // ONLY in the image fields. Any new suggestion entry can therefore only have come from
        // the icon detector.
        List<AssessmentNode> withGlyph = List.of(
                glyphedCard(120, "top-right"),
                new AssessmentNode("other", 0, 200, 120, 55, null, false, false, "Other", 40.0,
                        null, null, 0.0, 0.0, 0.0));
        List<AssessmentNode> withoutGlyph = List.of(
                new AssessmentNode("card", 0, 0, 120, 55, null, false, false,
                        "Meridian Rewards Credit Card", 100.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("other", 0, 200, 120, 55, null, false, false, "Other", 40.0,
                        null, null, 0.0, 0.0, 0.0));

        List<String> withFields = assessor.assess(withGlyph, List.of(), false).suggestions();
        List<String> withoutFields = assessor.assess(withoutGlyph, List.of(), false).suggestions();

        String entry = withFields.stream()
                .filter(s -> s.contains("own icon"))
                .findFirst().orElse(null);
        assertNotNull("the detected collision must be named in the prose the agent reads: "
                + withFields, entry);
        assertTrue("a count without the number repeats the defect one layer up: " + entry,
                entry.contains("1 element"));
        assertTrue("a finding without a remedy is not actionable: " + entry,
                entry.contains("widen") || entry.contains("alignment"));
        assertTrue("the control must not carry it: " + withoutFields,
                withoutFields.stream().noneMatch(s -> s.contains("own icon")));
    }

    @Test
    public void assess_ownIconOverLabel_shouldDisplaceTheAllClear_onAnOtherwiseCleanView() {
        // The reachability case, measured before the fix: a connectionless view with generous
        // spacing, perfect alignment and no overlaps emits exactly one suggestion — the terminal
        // all-clear — while carrying a real icon-over-title collision. A tool that says "no
        // immediate improvements needed" over a detected defect is emitting an all-clear it did
        // not verify.
        List<AssessmentNode> nodes = List.of(
                glyphedCard(120, "top-right"),
                new AssessmentNode("b", 0, 200, 120, 55, null, false, false, "Beta", 40.0,
                        null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("c", 0, 400, 120, 55, null, false, false, "Gamma", 40.0,
                        null, null, 0.0, 0.0, 0.0));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("the fixture must really fire the detector, or it proves nothing",
                1, result.ownIconOverLabelCount());
        assertEquals("and must really be the otherwise-clean case", "excellent",
                result.overallRating());
        assertFalse("the all-clear must not stand over a detected collision: "
                        + result.suggestions(),
                result.suggestions().contains(
                        "Layout quality is good \u2014 no immediate improvements needed."));
    }

    @Test
    public void assess_ownIconOverLabel_shouldNotClaimTheDescriptionsNameThemAll_whenCapped() {
        // The description list is capped at ten and this dimension publishes NO violator-id key,
        // so on a view with more findings than the cap the remainder is recoverable from no field
        // in the response. Pointing at the list as though it held them all would be an unverified
        // claim in a structured field's clothing — the failure this whole suggestion exists to
        // stop. Measured on the live corpus: one view reports 17 with 10 descriptions.
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            nodes.add(new AssessmentNode("card" + i, 0, i * 100, 120, 55, null, false, false,
                    "Card " + i, 100.0, "img/card.png", "top-right", 0.0, 64.0, 64.0));
        }

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
        String entry = result.suggestions().stream()
                .filter(t -> t.contains("own icon"))
                .findFirst().orElse(null);

        assertNotNull("the fixture must fire the suggestion: " + result.suggestions(), entry);
        assertEquals("the fixture must really outrun the cap, or it proves nothing",
                12, result.ownIconOverLabelCount());
        assertEquals("...and the list must really be shorter than the count",
                10, result.ownIconOverLabelDescriptions().size());
        assertFalse("the capped list must not be offered as naming every object: " + entry,
                entry.contains("see assess-layout's ownIconOverLabelDescriptions for the objects"
                        + " affected"));
        assertTrue("the shortfall must be stated in the number the caller can act on: " + entry,
                entry.contains("names the first 10 of them"));
        assertTrue("...and the caller must be told the remainder is in no field: " + entry,
                entry.contains("remaining 2"));
    }

    @Test
    public void assess_ownIconOverLabel_shouldPointAtTheDescriptions_whenNotCapped() {
        // The complement: below the cap the list really does name them all, so the pointer is
        // honest and must survive. Without this pin the fix above could degrade every message.
        List<AssessmentNode> nodes = List.of(
                glyphedCard(120, "top-right"),
                new AssessmentNode("b", 0, 200, 120, 55, null, false, false, "Beta", 40.0,
                        null, null, 0.0, 0.0, 0.0));

        String entry = assessor.assess(nodes, List.of(), false).suggestions().stream()
                .filter(t -> t.contains("own icon"))
                .findFirst().orElseThrow();

        assertTrue("an uncapped list is named as complete, because it is: " + entry,
                entry.contains("see assess-layout's ownIconOverLabelDescriptions for the objects"
                        + " affected"));
        assertFalse("and no shortfall is invented: " + entry, entry.contains("names the first"));
    }

    @Test
    public void assess_ownIconOverLabel_shouldReadGrammaticallyOnTheSingularCase() {
        // The served prose is read by an agent; "1 element(s) have" is the kind of thing a
        // substring pin never catches, because "1 element" matches either spelling.
        List<AssessmentNode> nodes = List.of(
                glyphedCard(120, "top-right"),
                new AssessmentNode("b", 0, 200, 120, 55, null, false, false, "Beta", 40.0,
                        null, null, 0.0, 0.0, 0.0));

        String entry = assessor.assess(nodes, List.of(), false).suggestions().stream()
                .filter(t -> t.contains("own icon"))
                .findFirst().orElseThrow();

        assertTrue("the singular must agree: " + entry,
                entry.startsWith("1 element has its own icon"));
        assertFalse("the placeholder plural must be gone: " + entry, entry.contains("element(s)"));
    }

    @Test
    public void assess_ownIconOverLabel_shouldNotAffectRating() {
        // Identical geometry, names and label widths — the control differs ONLY in the image
        // fields, so any rating movement could only come from the new detector. (Dropping the
        // names instead would move the rating-promoted parentLabelObscured metric and make this
        // a two-variable comparison.)
        List<AssessmentNode> withGlyph = List.of(
                glyphedCard(120, "top-right"),
                new AssessmentNode("other", 0, 200, 120, 55, null, false, false, "Other", 40.0,
                        null, null, 0.0, 0.0, 0.0));
        List<AssessmentNode> withoutGlyph = List.of(
                new AssessmentNode("card", 0, 0, 120, 55, null, false, false,
                        "Meridian Rewards Credit Card", 100.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("other", 0, 200, 120, 55, null, false, false, "Other", 40.0,
                        null, null, 0.0, 0.0, 0.0));

        LayoutAssessmentResult withFields = assessor.assess(withGlyph, List.of(), false);
        LayoutAssessmentResult withoutFields = assessor.assess(withoutGlyph, List.of(), false);

        // The overlap really was detected (otherwise the test proves nothing).
        assertEquals(1, withFields.ownIconOverLabelCount());
        assertEquals(0, withoutFields.ownIconOverLabelCount());
        // ...yet none of the rating outputs move.
        assertEquals(withoutFields.overallRating(), withFields.overallRating());
        assertEquals(withoutFields.layoutRating(), withFields.layoutRating());
        assertEquals(withoutFields.routingRating(), withFields.routingRating());
        assertEquals(withoutFields.ratingBreakdown(), withFields.ratingBreakdown());
    }

    @Test
    public void detectOwnIconOverLabel_shouldDeclareAGap_whenANamedGroupCarriesAnIcon() {
        // A group renders a title band and CAN carry an overlay image, but label text is measured
        // only for non-group, non-note objects — so a group arrives with labelTextWidth 0 and there
        // is no title rect to test. The count is honestly 0, but a 0 that was never examined must
        // not read as certified clean, so the run is flagged as carrying an unmeasured title.
        AssessmentNode icongroup = new AssessmentNode("g", 0, 0, 300, 120, null, true, false,
                "Channel Layer", 0.0, "img/g.png", "top-right", 0.0, 64.0, 64.0);

        LayoutQualityAssessor.OwnIconOverLabelResult result =
                assessor.detectOwnIconOverLabel(List.of(icongroup));

        assertEquals("nothing measurable was found, so nothing is claimed", 0, result.count());
        assertTrue("but the gap is declared rather than hidden", result.unmeasuredTitle());
    }

    @Test
    public void detectOwnIconOverLabel_shouldNotDeclareAGap_whenTheGroupHasNoIconOrNoName() {
        // The gap is specific: only a group that actually carries an overlay icon leaves something
        // unexamined. A plain group has nothing to collide with its title, and an unnamed group has
        // no title at all — neither may downgrade coverage, or the signal becomes noise.
        AssessmentNode plainGroup = new AssessmentNode("g", 0, 0, 300, 120, null, true, false,
                "Channel Layer", 0.0, null, null, 0.0, 0.0, 0.0);
        AssessmentNode unnamedIconGroup = new AssessmentNode("g2", 0, 0, 300, 120, null, true, false,
                "", 0.0, "img/g2.png", "top-right", 0.0, 64.0, 64.0);

        assertFalse("a group with no icon leaves nothing unexamined",
                assessor.detectOwnIconOverLabel(List.of(plainGroup)).unmeasuredTitle());
        assertFalse("an unnamed group has no title to bury",
                assessor.detectOwnIconOverLabel(List.of(unnamedIconGroup)).unmeasuredTitle());
        // And a plain titled ELEMENT with an icon is fully measurable — never a gap.
        assertFalse("a measurable element is not a coverage gap",
                assessor.detectOwnIconOverLabel(
                        List.of(glyphedCard(400, "top-right"))).unmeasuredTitle());
    }

    // ---- The abstention is about MEASUREMENT, not about KIND ----
    //
    // The flag's original gate asked whether the node was a native GROUP. That answered the case
    // the detector was written against and nothing else: every OTHER way a title can arrive
    // unmeasured left the dimension certifying a node it never examined. The collector measures a
    // label inside a try/catch (AssessmentCollector: "Failed to measure text for '{}'"), so a
    // measurement failure yields a named, icon-bearing node with labelTextWidth 0 — reachable on a
    // plain leaf element and on an ArchiMate Grouping alike, neither of which is a native group.
    //
    // The sibling contextual downgrade 170 lines away already has the right shape: it asks
    // `parent.labelTextWidth() <= 0`. These fixtures isolate the DIFFERENCE between the two
    // predicates — a kind-shaped gate says "measured and clean", a measurement-shaped gate says
    // "never examined". Every fixture that existed before this block agrees with BOTH readings.

    /**
     * A titled, icon-bearing LEAF element whose label width is 0 — what the collector produces when
     * {@code ElementSizer.measureText} throws and its catch logs the failure. Not a group, not a
     * container: the one shape the kind-shaped gate cannot see and the measurement-shaped gate can.
     */
    private static AssessmentNode unmeasuredGlyphedCard() {
        return new AssessmentNode("card", 0, 0, 120, 55, null, false, false,
                "Meridian Rewards Credit Card", 0.0,
                "img/card.png", "top-right", 0.0, 64.0, 64.0);
    }

    /**
     * As {@link #unmeasuredGlyphedCard} but shaped as an ArchiMate {@code Grouping}
     * ({@code isGroup=false}, {@code isContainer=true}) — the kind the parent story taught this
     * file to distinguish. A Grouping IS normally measured, so this is specifically the
     * measurement-failure case, not the never-measured one a native group represents.
     */
    private static AssessmentNode unmeasuredGlyphedZone() {
        return new AssessmentNode("zone", 0, 0, 300, 120, null, false, false,
                "Channel Layer", 0.0, "img/z.png", "top-right", 0.0, 64.0, 64.0,
                false, null, true, AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
    }

    @Test
    public void detectOwnIconOverLabel_shouldDeclareAGap_whenALeafElementsTitleCouldNotBeMeasured() {
        // The defect, at the detector. The element carries an overlay icon and a name, so it IS a
        // candidate — but with no measured width there is no title rectangle to test it against, so
        // the icon was never compared to anything. The count is honestly 0; the claim that 0 means
        // "examined and clean" is not.
        LayoutQualityAssessor.OwnIconOverLabelResult result =
                assessor.detectOwnIconOverLabel(List.of(unmeasuredGlyphedCard()));

        assertEquals("nothing measurable was found, so nothing is claimed", 0, result.count());
        assertTrue("a leaf whose title could not be measured is a declared gap, not a clean zero",
                result.unmeasuredTitle());
    }

    @Test
    public void detectOwnIconOverLabel_shouldDeclareAGap_whenAGroupingsTitleCouldNotBeMeasured() {
        // Same defect on the container kind that is NOT a native group. This is the shape the
        // parent story's widening created and this gate never learned about.
        LayoutQualityAssessor.OwnIconOverLabelResult result =
                assessor.detectOwnIconOverLabel(List.of(unmeasuredGlyphedZone()));

        assertEquals(0, result.count());
        assertTrue("a Grouping whose title could not be measured is a declared gap",
                result.unmeasuredTitle());
    }

    @Test
    public void detectOwnIconOverLabel_shouldNotDeclareAGap_whenTheTitleWasMeasured() {
        // MANDATORY NEGATIVE CONTROL. Widening the abstention is only a fix if it still says
        // "checked" for the measured case — an abstention that fires on everything trades a false
        // all-clear for a permanent "partial", which tells an agent exactly as little.
        // Single-variable against unmeasuredGlyphedCard(): only labelTextWidth differs (0 -> 100).
        assertFalse("a measured title was genuinely examined",
                assessor.detectOwnIconOverLabel(
                        List.of(glyphedCard(120, "top-right"))).unmeasuredTitle());
        // ...and that measured case is the one that still produces a real finding.
        assertEquals(1, assessor.detectOwnIconOverLabel(
                List.of(glyphedCard(120, "top-right"))).count());
    }

    @Test
    public void coverage_ownIconOverLabel_downgradesToPartial_whenALeafTitleCouldNotBeMeasured() {
        // Consumer 1 of 2: buildCoverageMap, the normal >=2-object assessment path.
        List<AssessmentNode> nodes = List.of(unmeasuredGlyphedCard(), node("a", 400, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("the unexamined leaf downgrades the dimension",
                LayoutQualityAssessor.COVERAGE_PARTIAL,
                result.coverage().get("ownIconOverLabel"));
        assertEquals("and the count stays honestly zero", 0, result.ownIconOverLabelCount());
        // Scoped to this one dimension — no neighbour is dragged down with it.
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("overlayIconCollision"));
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("imageSiblingOverlap"));
    }

    @Test
    public void coverage_ownIconOverLabel_staysChecked_whenTheLeafTitleWasMeasured() {
        // NEGATIVE CONTROL for consumer 1, single-variable against the test above: the same
        // icon-bearing named leaf, differing only in that its title WAS measured.
        //
        // The pre-existing coverage_shouldDeclareOwnIconOverLabelChecked does not cover this. Its
        // nodes carry no icon and no name, so the detector skips them at the icon guard and never
        // reaches the abstention branch at all — it controls for "the detector ran", not for "a
        // measured title stays checked". Without this test the widened gate could downgrade every
        // icon-bearing run to `partial` on the normal assessment path and nothing would fail.
        List<AssessmentNode> nodes = List.of(glyphedCard(120, "top-right"), node("a", 400, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("a measured icon-bearing title keeps the dimension checked",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("ownIconOverLabel"));
        assertEquals("and it is the case that produces a real finding, not an empty one",
                1, result.ownIconOverLabelCount());
    }

    @Test
    public void coverage_ownIconOverLabel_downgradesToPartial_onTheDegeneratePath() {
        // Consumer 2 of 2: assessDegenerate, the <=1-object view. The detector DOES run here, and
        // this path writes its own coverage entry rather than going through buildCoverageMap — so
        // the same gate has to be right in two places. One node, so the view is degenerate.
        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(List.of(unmeasuredGlyphedCard()), List.of());

        assertEquals("the degenerate path must declare the same gap",
                LayoutQualityAssessor.COVERAGE_PARTIAL,
                result.coverage().get("ownIconOverLabel"));
        assertEquals(0, result.ownIconOverLabelCount());
    }

    @Test
    public void coverage_ownIconOverLabel_staysChecked_onTheDegeneratePath_whenMeasured() {
        // Negative control for consumer 2. Single-variable against the test above.
        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(List.of(glyphedCard(120, "top-right")), List.of());

        assertEquals("a measured single object is genuinely checked",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("ownIconOverLabel"));
        assertEquals("and it produces a real finding", 1, result.ownIconOverLabelCount());
    }

    @Test
    public void coverage_ownIconOverLabel_downgradesToPartial_whenAGroupCarriesAnIcon() {
        // End to end: the dimension declares "checked" as its baseline, but a run holding an
        // icon-bearing group reports "partial" — the consumer is told to render-verify instead of
        // reading a zero as an all-clear. Mirrors the labelOverlaps contextual downgrade.
        List<AssessmentNode> withIconGroup = List.of(
                new AssessmentNode("g", 0, 0, 300, 120, null, true, false, "Channel Layer", 0.0,
                        "img/g.png", "top-right", 0.0, 64.0, 64.0),
                node("a", 400, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(withIconGroup, List.of(), false);

        assertEquals("the unexamined group downgrades the dimension",
                LayoutQualityAssessor.COVERAGE_PARTIAL,
                result.coverage().get("ownIconOverLabel"));
        assertEquals("and the count stays honestly zero", 0, result.ownIconOverLabelCount());
        // The downgrade is scoped to this one dimension — no neighbour is dragged down with it.
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("overlayIconCollision"));
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("imageSiblingOverlap"));
    }

    @Test
    public void detectOwnIconOverLabel_shouldNotFlag_whenGeometryIsNotANumber() {
        // Defensive pin. NaN is not reachable through the collector (Archi geometry is integral and
        // degenerate boxes are dropped before a node is built), but a guard written as a `<= 0`
        // comparison is defeated by it silently, so the outcome is pinned here.
        //
        // The COUNT is no longer what makes this pass, and the distinction is the whole point of
        // the block 100 lines below. It used to hold only because rectanglesOverlap's strict
        // comparisons are false against NaN too — an accident, at a predicate that has nothing to
        // do with measurement. ownLabelBounds now rejects non-finite geometry explicitly and
        // returns no rectangle, so the zero is decided before any comparison happens. What this
        // test still adds is that the fix did not turn a suppressed finding into a reported one;
        // that it is ALSO now declared as a coverage gap is asserted where that claim belongs.
        assertEquals(0, assessor.detectOwnIconOverLabel(List.of(new AssessmentNode(
                "card", 0, 0, Double.NaN, 55, null, false, false, "Card", 100.0,
                "img/card.png", "top-right", 0.0, 64.0, 64.0))).count());
        assertEquals(0, assessor.detectOwnIconOverLabel(List.of(new AssessmentNode(
                "card", 0, 0, 120, 55, null, false, false, "Card", Double.NaN,
                "img/card.png", "top-right", 0.0, 64.0, 64.0))).count());
    }

    // ---- The abstention must be BY DESIGN, not by arithmetic accident ----
    //
    // Every guard in ownLabelBounds was a positivity comparison, and a non-finite value defeats
    // those silently: `NaN <= 0` is false, so a NaN width walked straight past `if (labelWidth <= 0)`,
    // and a NaN x never met a guard at all. The method then returned a rectangle whose own
    // coordinates were NaN, so the run reported zero findings AND coverage `checked` — a certified
    // all-clear for an icon that was never actually compared with anything. That is the precise
    // false-clean this dimension's abstention exists to prevent: the zero was not decided by the
    // geometry, it fell out of rectanglesOverlap's strict comparisons being false against NaN too.
    //
    // So the fix is the ABSTENTION, not the zero. Geometry that is not a number was not measured,
    // there is no rectangle to claim, and the dimension must say `partial`. Every fixture below
    // returned a NaN-valued rectangle before and returns null now; the count they report is
    // unchanged, which is exactly why the count could never have detected the defect.

    /**
     * A {@link #glyphedCard} whose four geometry numbers are supplied individually, so each
     * non-finite vector can be introduced ALONE. Name, height, icon and natural dimensions are held
     * equal to the measured positive case, leaving the number under test as the only variable.
     *
     * <p>{@code geometryGlyphedCard(0, 0, 120, 100)} is byte-equivalent to
     * {@code glyphedCard(120, "top-right")} — the case that produces a real finding — so every
     * assertion here reads against a control that is known to flag.
     */
    private static AssessmentNode geometryGlyphedCard(double x, double y, double width,
                                                      double labelTextWidth) {
        return new AssessmentNode("card", x, y, width, 55, null, false, false,
                "Meridian Rewards Credit Card", labelTextWidth,
                "img/card.png", "top-right", 0.0, 64.0, 64.0);
    }

    @Test
    public void detectOwnIconOverLabel_shouldDeclareAGap_whenTheGeometryIsNotANumber() {
        // FOUR vectors, because they defeat the old guard in two different ways. width and
        // labelTextWidth both flow through `Math.min` into labelWidth — the value the `<= 0` guard
        // was actually written to catch, and which it silently passed. x and y never reach that
        // guard at all: a finite width cleared it outright and the non-finite coordinate then
        // poisoned runX, clampedX and the returned rectangle's origin.
        assertTrue("a NaN width leaves the title unmeasurable",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(0, 0, Double.NaN, 100.0))).unmeasuredTitle());
        assertTrue("a NaN measured title width leaves it unmeasurable",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(0, 0, 120.0, Double.NaN))).unmeasuredTitle());
        assertTrue("a NaN x clears the width guard entirely and must still abstain",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(Double.NaN, 0, 120.0, 100.0))).unmeasuredTitle());
        assertTrue("a NaN y likewise — the band's origin is no less part of the rectangle",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(0, Double.NaN, 120.0, 100.0))).unmeasuredTitle());

        // The count is 0 on all four, exactly as before the guard existed. Asserted so the change
        // is visibly an abstention and not a suppression: nothing that used to be found is lost.
        assertEquals(0, assessor.detectOwnIconOverLabel(List.of(
                geometryGlyphedCard(0, 0, Double.NaN, 100.0))).count());
        assertEquals(0, assessor.detectOwnIconOverLabel(List.of(
                geometryGlyphedCard(0, 0, 120.0, Double.NaN))).count());
        assertEquals(0, assessor.detectOwnIconOverLabel(List.of(
                geometryGlyphedCard(Double.NaN, 0, 120.0, 100.0))).count());
        assertEquals(0, assessor.detectOwnIconOverLabel(List.of(
                geometryGlyphedCard(0, Double.NaN, 120.0, 100.0))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldDeclareAGap_whenTheGeometryIsInfinite() {
        // The other non-finite value, and it fails the OPPOSITE way round: `Infinity <= 0` is false
        // like NaN, but Infinity's comparisons are not — an infinite width would satisfy
        // rectanglesOverlap and MANUFACTURE a finding rather than suppress one. A guard written as
        // "is this a number I can compute with" covers both; one written as `Double.isNaN` alone
        // would leave this vector reporting a fabricated overlap.
        assertTrue("an infinite width is not a measurement either",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(0, 0, Double.POSITIVE_INFINITY, 100.0))).unmeasuredTitle());
        assertEquals("and it must not fabricate a finding", 0,
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(0, 0, Double.POSITIVE_INFINITY, 100.0))).count());
        assertTrue("an infinite x is not a position",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(Double.NEGATIVE_INFINITY, 0, 120.0, 100.0))).unmeasuredTitle());
    }

    @Test
    public void detectOwnIconOverLabel_shouldKeepRejectingZeroAndNegativeWidths() {
        // THE NON-WIDENING PIN, and it belongs in this block rather than beside the fix, because
        // the hazard is a later "simplification" of the guard rather than the guard itself. The new
        // check may reject strictly MORE than `labelWidth <= 0` did; it may not start ACCEPTING
        // anything that guard rejected. A zero or negative measured width is not a title rectangle
        // and never was — these two cases must keep returning null, and so keep declaring the gap.
        assertTrue("a zero measured width is still no rectangle",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(0, 0, 120.0, 0.0))).unmeasuredTitle());
        assertTrue("a negative measured width is still no rectangle",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(0, 0, 120.0, -5.0))).unmeasuredTitle());
        assertEquals(0, assessor.detectOwnIconOverLabel(List.of(
                geometryGlyphedCard(0, 0, 120.0, 0.0))).count());
        // A negative BOX width is deliberately NOT asserted as a declared gap: measured, it never
        // reaches this guard at all. estimateImageBounds collapses the icon rectangle first
        // (`x2 - x1 <= 0` holds for a real negative), the detector skips the node at its icon
        // guard, and no title is ever examined or claimed. Asserting a gap here would pin a
        // behaviour this method does not own — and would silently start passing if that earlier
        // guard were ever loosened, which is the opposite of what a pin is for.
        assertFalse("a collapsed icon rectangle is skipped before any title is claimed",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(0, 0, -120.0, 100.0))).unmeasuredTitle());
    }

    @Test
    public void detectOwnIconOverLabel_shouldNotDeclareAGap_whenTheGeometryIsOrdinary() {
        // MANDATORY NEGATIVE CONTROL for the whole block, single-variable against every fixture
        // above: the same card with four ordinary finite numbers. A guard that abstains on
        // everything trades an accidental clean for a permanent `partial`, which tells an agent
        // exactly as little. A NEGATIVE COORDINATE is included deliberately — it is finite, it is
        // ordinary (Archi canvases carry negative coordinates), and a guard that confused
        // "negative" with "not a number" would reject it.
        assertFalse("ordinary finite geometry was genuinely examined",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(0, 0, 120.0, 100.0))).unmeasuredTitle());
        assertEquals("and it is still the case that produces a real finding", 1,
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(0, 0, 120.0, 100.0))).count());
        assertFalse("a negative origin is a position, not a measurement failure",
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(-400.0, -250.0, 120.0, 100.0))).unmeasuredTitle());
        assertEquals("and it still flags, at its own coordinates", 1,
                assessor.detectOwnIconOverLabel(List.of(
                        geometryGlyphedCard(-400.0, -250.0, 120.0, 100.0))).count());
    }

    @Test
    public void coverage_ownIconOverLabel_downgradesToPartial_whenTheGeometryIsNotANumber() {
        // THE OBSERVABLE CHANGE, and the reason this fix is not merely cosmetic. Before the guard
        // the count was 0 and coverage read `checked`: the shipped response told an agent that this
        // element's icon had been examined against its title and found clean. It had not been
        // examined at all. Consumer 1 of 2 — buildCoverageMap, the normal >=2-object path.
        List<AssessmentNode> nodes = List.of(
                geometryGlyphedCard(Double.NaN, 0, 120.0, 100.0), node("a", 400, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("geometry that is not a number was not examined",
                LayoutQualityAssessor.COVERAGE_PARTIAL,
                result.coverage().get("ownIconOverLabel"));
        assertEquals("and the count stays honestly zero", 0, result.ownIconOverLabelCount());
        // Scoped to this one dimension — no neighbour is dragged down with it.
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("overlayIconCollision"));
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("imageSiblingOverlap"));
    }

    @Test
    public void coverage_ownIconOverLabel_staysChecked_whenTheGeometryIsOrdinary() {
        // Negative control for the coverage claim, single-variable against the test above: the same
        // two nodes with the card's x finite. Without this the guard could downgrade every run and
        // the test above would still pass.
        List<AssessmentNode> nodes = List.of(
                geometryGlyphedCard(0, 0, 120.0, 100.0), node("a", 400, 0, 100, 50));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("ordinary geometry keeps the dimension checked",
                LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("ownIconOverLabel"));
        assertEquals("and it is the case that produces a real finding", 1,
                result.ownIconOverLabelCount());
    }

    @Test
    public void coverage_ownIconOverLabel_downgradesToPartial_onTheDegeneratePath_whenNotANumber() {
        // Consumer 2 of 2: assessDegenerate, the <=1-object view, which writes its own coverage
        // entry rather than going through buildCoverageMap — so the same gate has to be right in
        // two places, exactly as the measurement-failure pair above establishes.
        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(
                        List.of(geometryGlyphedCard(Double.NaN, 0, 120.0, 100.0)), List.of());

        assertEquals("the degenerate path must declare the same gap",
                LayoutQualityAssessor.COVERAGE_PARTIAL,
                result.coverage().get("ownIconOverLabel"));
        assertEquals(0, result.ownIconOverLabelCount());
    }

    // ---- The title's VERTICAL placement is the OBJECT's too, not a constant ----
    //
    // The horizontal axis was taught to read the object's own textAlignment; the vertical one was
    // left anchored at node.y() unconditionally. Both are the SAME per-object mechanism, not merely
    // analogous ones — Archi's figure builds ONE GridData(hAlign, vAlign, true, true) and reads its
    // two arguments from getTextAlignment() and getTextPosition() respectively — and this server
    // publishes a verticalTextAlignment parameter that writes it. So a title in the middle or at
    // the foot of its box is reachable through this server, and against it the top-anchored model
    // reports a false negative (a real collision at the foot, missed) AND a false positive (a
    // top-anchored icon blamed for covering a title that is not there).
    //
    // MEASURED at the render, Archi 5.10, SVG export of a probe view placing one element eight
    // times and varying only this feature (ink read from the glyph path outlines — Batik converts
    // text to vector outlines, so there are no <text> elements to read). With a 400x120 box, a
    // 15px single-line band and a 30px wrapped one, the ink landed on the predicted arithmetic in
    // all eight cases, to the tenth of a pixel:
    //
    //     band cell = [y + 4, y + height - 4]        (4 = Archi's getTextControlMarginHeight())
    //     TOP     band y = cell top
    //     CENTRE  band y = cell top + (cellHeight - bandHeight) / 2
    //     BOTTOM  band y = cell bottom - bandHeight
    //
    // The detector keeps its own band model — anchored on the BOX edge rather than the 4px inset
    // cell, which is the approximation the top-anchored model already made and which the parent
    // story ruled acceptable. What changes is that the anchor is now chosen by textPosition
    // instead of assumed, so the same ~4px approximation applies at whichever edge the title
    // actually renders against, rather than at the top edge only.

    /**
     * THE FIXTURE FOR THE DIFFERENCE — a glyphed card that differs from its siblings in exactly one
     * component: {@code textPosition}. No fixture in this file had a non-TOP one before, so every
     * one of them agrees with BOTH the old unconditional top-anchoring and the new position-aware
     * model, and none of them can tell which is in force.
     *
     * <p>The box is 400x300 rather than the usual 120x55 so the three bands are DISJOINT and a
     * 64px icon can reach exactly one of them. With a 100px centred title the glyph run spans
     * x[150,250] and the centred icon spans x[168,232], so the horizontal always overlaps and the
     * VERTICAL alone decides every case below — which is what makes the 3x3 matrix single-variable:
     *
     * <pre>
     *     band        TOP y[  0, 20]   CENTRE y[140,160]   BOTTOM y[280,300]
     *     icon  top-centre y[  0, 64]  middle-centre y[118,182]  bottom-centre y[236,300]
     * </pre>
     *
     * Each icon meets its own row's band and clears the other two.
     */
    private static AssessmentNode positionedGlyphedCard(String iconPosition, int textPosition) {
        return new AssessmentNode("card", 0, 0, 400, 300, null, false, false,
                "Meridian Rewards Credit Card", 100.0,
                "img/card.png", iconPosition, 0.0, 64.0, 64.0,
                false, null, false, AssessmentNode.TEXT_ALIGNMENT_CENTRE, textPosition);
    }

    @Test
    public void detectOwnIconOverLabel_shouldDetect_whenTheIconMeetsTheTitleAtItsOwnVerticalPosition() {
        // The diagonal: each title position met by the icon that reaches it. Under the old
        // top-anchored model the CENTRE and BOTTOM rows report a clean zero for a collision that
        // renders — a false negative on a title an agent cannot see.
        assertEquals("a top-anchored icon meets a TOP title", 1,
                assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                        "top-centre", AssessmentNode.TEXT_POSITION_TOP))).count());
        assertEquals("a middle icon meets a CENTRE title", 1,
                assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                        "middle-centre", AssessmentNode.TEXT_POSITION_CENTRE))).count());
        assertEquals("a bottom icon meets a BOTTOM title", 1,
                assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                        "bottom-centre", AssessmentNode.TEXT_POSITION_BOTTOM))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldNotDetect_whenTheTitleSitsAtADifferentVerticalPosition() {
        // The six off-diagonal cells, and they carry the other half of the defect: the top-anchored
        // model FABRICATES a finding for a top-anchored icon on an element whose title is not at
        // the top. Without these the change could be "flag more often" rather than "flag where the
        // title actually is".
        assertEquals("a top icon does not reach a CENTRE title", 0,
                assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                        "top-centre", AssessmentNode.TEXT_POSITION_CENTRE))).count());
        assertEquals("a top icon does not reach a BOTTOM title", 0,
                assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                        "top-centre", AssessmentNode.TEXT_POSITION_BOTTOM))).count());
        assertEquals("a middle icon does not reach a TOP title", 0,
                assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                        "middle-centre", AssessmentNode.TEXT_POSITION_TOP))).count());
        assertEquals("a middle icon does not reach a BOTTOM title", 0,
                assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                        "middle-centre", AssessmentNode.TEXT_POSITION_BOTTOM))).count());
        assertEquals("a bottom icon does not reach a TOP title", 0,
                assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                        "bottom-centre", AssessmentNode.TEXT_POSITION_TOP))).count());
        assertEquals("a bottom icon does not reach a CENTRE title", 0,
                assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                        "bottom-centre", AssessmentNode.TEXT_POSITION_CENTRE))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldTreatAnUnknownTextPositionAsTop() {
        // Degrade, do not throw — the same contract the unknown ALIGNMENT case holds, and asserted
        // the same way: against absolute expected counts rather than against another call, because
        // comparing the unknown value to TOP would reduce to x == x and hold for any
        // implementation. The discriminating claim is that an unknown position behaves like TOP and
        // NOT like CENTRE or BOTTOM, and only the absolute values say that. TOP is the right
        // fallback because it is Archi's own EMF default (TEXT_POSITION_TOP == 0).
        assertEquals("an unrecognised position must behave like TOP", 1,
                assessor.detectOwnIconOverLabel(List.of(
                        positionedGlyphedCard("top-centre", 99))).count());
        assertEquals("...and must NOT be treated as CENTRE", 0,
                assessor.detectOwnIconOverLabel(List.of(
                        positionedGlyphedCard("middle-centre", 99))).count());
        assertEquals("...nor as BOTTOM", 0,
                assessor.detectOwnIconOverLabel(List.of(
                        positionedGlyphedCard("bottom-centre", 99))).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldKeepTheWrappedBandAtItsOwnVerticalPosition() {
        // The band's HEIGHT and its ANCHOR are independent, and a wrapped title must grow from
        // whichever edge it is anchored to. A 300px-wide box with a title measured wider than its
        // available width (400 > 300-16) wraps, so estimateLabelBandHeight returns the two-line
        // band of 40 — not deeper than the 300px box, so nothing is clipped. Anchored at the bottom
        // that band occupies y[260,300], which a bottom icon (y[236,300]) meets and a middle icon
        // (y[118,182]) still does not.
        AssessmentNode wrappedBottom = new AssessmentNode("card", 0, 0, 300, 300, null, false, false,
                "Meridian Rewards Credit Card", 400.0,
                "img/card.png", "bottom-centre", 0.0, 64.0, 64.0,
                false, null, false, AssessmentNode.TEXT_ALIGNMENT_CENTRE,
                AssessmentNode.TEXT_POSITION_BOTTOM);
        AssessmentNode wrappedBottomMiddleIcon = new AssessmentNode("card", 0, 0, 300, 300, null,
                false, false, "Meridian Rewards Credit Card", 400.0,
                "img/card.png", "middle-centre", 0.0, 64.0, 64.0,
                false, null, false, AssessmentNode.TEXT_ALIGNMENT_CENTRE,
                AssessmentNode.TEXT_POSITION_BOTTOM);

        assertEquals("a wrapped bottom title is met by a bottom icon", 1,
                assessor.detectOwnIconOverLabel(List.of(wrappedBottom)).count());
        assertEquals("and the wrapped band still does not reach the middle", 0,
                assessor.detectOwnIconOverLabel(List.of(wrappedBottomMiddleIcon)).count());
    }

    /**
     * A {@link #positionedGlyphedCard} with the box HEIGHT as the variable, because the non-TOP
     * anchors are the only thing in this method that reads it. Everything else is held equal to the
     * measured positive case.
     */
    private static AssessmentNode heightedGlyphedCard(double height, int textPosition) {
        return new AssessmentNode("card", 0, 0, 400, height, null, false, false,
                "Meridian Rewards Credit Card", 100.0,
                "img/card.png", "bottom-centre", 0.0, 64.0, 64.0,
                false, null, false, AssessmentNode.TEXT_ALIGNMENT_CENTRE, textPosition);
    }

    @Test
    public void detectOwnIconOverLabel_shouldDeclareAGap_whenANonTopBandHasNoUsableHeight() {
        // A TOP band does not need the height to be placed — it starts at the object's own y, and
        // estimateLabelBandHeight deliberately hands back a finite band whatever it is given. The
        // CENTRE and BOTTOM anchors are different in kind: both measure from the box's FAR edge, so
        // both read the height DIRECTLY, and neither can be placed at all when that number is not a
        // usable one. Computing them anyway is how the guard above gets defeated a second time —
        // a non-finite height makes the band's own origin non-finite, and the run then reports zero
        // findings and coverage `checked` for a title that was never located.
        for (double h : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            for (int tp : new int[]{AssessmentNode.TEXT_POSITION_CENTRE,
                                    AssessmentNode.TEXT_POSITION_BOTTOM}) {
                LayoutQualityAssessor.OwnIconOverLabelResult r =
                        assessor.detectOwnIconOverLabel(List.of(heightedGlyphedCard(h, tp)));
                assertTrue("a non-TOP band cannot be placed on height " + h
                        + " (textPosition " + tp + ") and must abstain", r.unmeasuredTitle());
                assertEquals("and must claim nothing", 0, r.count());
            }
        }
    }

    @Test
    public void detectOwnIconOverLabel_shouldSkipADegenerateBoxBeforeAnyBandIsPlaced() {
        // MEASURED, and deliberately NOT asserted as a declared gap. A zero, negative or negatively
        // infinite height is rejected EARLIER than the guard above: estimateImageBounds collapses
        // the icon rectangle against such a box (`y2 - y1 <= 0`), the detector skips the node at its
        // icon guard, and ownLabelBounds is never called at all. So no title is examined and none is
        // claimed — for EVERY text position, not just the non-TOP ones.
        //
        // The band arithmetic for those heights would indeed place a non-TOP band outside the box
        // (bottom-anchored, `y + height - band` sits ABOVE the top edge when height is 0), which is
        // why the guard above rejects them too. But that is defensive depth, not observable
        // behaviour, and pinning it as though the detector reported it would assert an outcome this
        // method cannot produce — the same distinction the negative-box-width pin above draws.
        for (double h : new double[]{0.0, -50.0, Double.NEGATIVE_INFINITY}) {
            for (int tp : new int[]{AssessmentNode.TEXT_POSITION_TOP,
                                    AssessmentNode.TEXT_POSITION_CENTRE,
                                    AssessmentNode.TEXT_POSITION_BOTTOM}) {
                LayoutQualityAssessor.OwnIconOverLabelResult r =
                        assessor.detectOwnIconOverLabel(List.of(heightedGlyphedCard(h, tp)));
                assertFalse("a collapsed box is skipped before any title is claimed (height " + h
                        + ", textPosition " + tp + ")", r.unmeasuredTitle());
                assertEquals("and nothing is found", 0, r.count());
            }
        }
    }

    @Test
    public void detectOwnIconOverLabel_shouldLeaveTheTopAnchorAloneOnADegenerateHeight() {
        // THE NON-WIDENING CONTROL for the test above, and the reason its guard is scoped to the
        // non-TOP anchors rather than applied to every node. A TOP band on a degenerate height is
        // PRE-EXISTING behaviour that this story did not set out to change: estimateLabelBandHeight
        // documents its `height > 0` fallback as deliberate, because a rating-bearing sibling
        // detector reads it, and the ruling there is explicit that the degenerate case belongs at
        // whichever seam its meaning is local to. This one is that seam — so the fix abstains only
        // where the geometry genuinely cannot be computed, and leaves the top anchor exactly as it
        // was found.
        for (double h : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0.0, -50.0}) {
            assertFalse("a TOP band does not need the height and must not start abstaining",
                    assessor.detectOwnIconOverLabel(List.of(
                            heightedGlyphedCard(h, AssessmentNode.TEXT_POSITION_TOP)))
                            .unmeasuredTitle());
        }
    }

    @Test
    public void detectOwnIconOverLabel_shouldKeepANonTopBandInsideItsOwnBox() {
        // The band must not escape the box on the axis this story added. With a usable height,
        // estimateLabelBandHeight clips the band to the box, so BOTTOM lands at y+height-band
        // (never above y) and CENTRE at y+(height-band)/2 (never above y, never past y+height).
        // Asserted through the icon, which is the only observable: a 64px icon flush with the box
        // TOP must not be reported against a BOTTOM-anchored title on a box tall enough to separate
        // them — if the band escaped upward, it would meet that icon and flag.
        AssessmentNode tallBottomWithTopIcon = new AssessmentNode("card", 0, 0, 400, 300, null,
                false, false, "Meridian Rewards Credit Card", 100.0,
                "img/card.png", "top-centre", 0.0, 64.0, 64.0,
                false, null, false, AssessmentNode.TEXT_ALIGNMENT_CENTRE,
                AssessmentNode.TEXT_POSITION_BOTTOM);
        assertEquals("a bottom-anchored band must not reach a top-flush icon", 0,
                assessor.detectOwnIconOverLabel(List.of(tallBottomWithTopIcon)).count());

        // ...and the band really is down there, met by an icon at the bottom. Without this the
        // zero above would be satisfied by a detector that found no band at all.
        AssessmentNode tallBottomWithBottomIcon = new AssessmentNode("card", 0, 0, 400, 300, null,
                false, false, "Meridian Rewards Credit Card", 100.0,
                "img/card.png", "bottom-centre", 0.0, 64.0, 64.0,
                false, null, false, AssessmentNode.TEXT_ALIGNMENT_CENTRE,
                AssessmentNode.TEXT_POSITION_BOTTOM);
        assertEquals("but a bottom icon does meet it", 1,
                assessor.detectOwnIconOverLabel(List.of(tallBottomWithBottomIcon)).count());
    }

    @Test
    public void detectOwnIconOverLabel_shouldNameTheVerticalPositionInItsDescription() {
        // Every other test on this axis asserts only the COUNT, which cannot catch a description
        // that names the wrong feature. On a vertically-caused overlap the horizontal alignment
        // plays no part in the collision at all — the fixture's 3x3 matrix is decided by the
        // vertical alone — so a description naming only the alignment would send the caller to the
        // wrong remedy, and the shipped tool description promises it names what it found.
        String bottom = assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                "bottom-centre", AssessmentNode.TEXT_POSITION_BOTTOM))).descriptions().get(0);
        assertTrue("the description must name the vertical position it actually used: " + bottom,
                bottom.contains("bottom"));
        assertTrue("...and still name the horizontal alignment", bottom.contains("centre-aligned"));

        String centre = assessor.detectOwnIconOverLabel(List.of(positionedGlyphedCard(
                "middle-centre", AssessmentNode.TEXT_POSITION_CENTRE))).descriptions().get(0);
        assertTrue("a centred title must be reported as centred, not as top: " + centre,
                centre.contains("centre of the box"));

        // The discriminating control: the TOP case must say "top", so the field is genuinely read
        // rather than a constant string that happens to match one case.
        String top = assessor.detectOwnIconOverLabel(List.of(
                glyphedCard(120, "top-right"))).descriptions().get(0);
        assertTrue("a top-anchored title must be reported as top: " + top,
                top.contains("top of the box"));
    }

    @Test
    public void detectOwnIconOverLabel_shouldNotChangeTheTopAnchoredCase() {
        // THE REGRESSION CONTROL. TOP is Archi's EMF default and the overwhelming majority of real
        // objects, so the position-aware band must leave that case byte-identical — every existing
        // fixture in this file is TOP and would be the first casualty of an arithmetic slip. The
        // shipped exemplar and its negative control are re-asserted here through the new code path.
        assertEquals(1, assessor.detectOwnIconOverLabel(
                List.of(glyphedCard(120, "top-right"))).count());
        assertEquals(0, assessor.detectOwnIconOverLabel(
                List.of(glyphedCard(400, "top-right"))).count());
    }

    @Test
    public void coverage_shouldDeclareOwnIconOverLabelChecked() {
        // The dimension must be on the canonical registry (so the map grows by exactly one) AND
        // report "checked" — a detector that runs but never declares itself leaves the consumer
        // unable to tell a clean view from an unexamined one.
        boolean registered = false;
        for (LayoutQualityAssessor.CoverageDimension dim
                : LayoutQualityAssessor.CoverageDimension.values()) {
            if ("ownIconOverLabel".equals(dim.id)) {
                registered = true;
                assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, dim.coverage);
            }
        }
        assertTrue("ownIconOverLabel must be a registry dimension", registered);

        LayoutAssessmentResult result = assessor.assess(
                List.of(node("a", 0, 0, 100, 50), node("b", 200, 0, 100, 50)), List.of(), false);

        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                result.coverage().get("ownIconOverLabel"));
    }

    // ---- Rating regression test (REPLACED under M6) ----

    @Test
    @Ignore("M6 — REPLACED by assess_withStylingAndLabelFields_shouldChangeRating_underM6Promotions. "
            + "Pre-redesign the styling and label fields had no rating impact. Under M6 parentLabelObscuredCount is "
            + "promoted Tier 1L and labelTruncationCount is promoted Tier 2R, so the OPPOSITE "
            + "assertion is now correct.")
    public void assess_withStylingAndLabelFields_shouldNotChangeRating() {
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

    // ---- Container transparency: the two false-positive sites the accessor-level fixtures
    // ---- structurally cannot reach (no fixture there carries an image, and every fixture's
    // ---- connection is horizontal). Pinned here, at the detector, where the geometry is dictated.

    @Test
    public void connectionThroughVisuals_shouldNotFlagAContainersImage_whenTheContainerIsNotANativeGroup() {
        // A zone drawn with a full-bleed background image is still a transparent container: the
        // route crosses a backdrop, not an obstruction. The detector skipped a native group's image
        // and not a Grouping's, so the identical picture flagged or did not depending on which
        // container kind held it.
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 90, 60, 40),
                zoneWithImage("zone", 200, 0, 300, 220),
                node("tgt", 700, 90, 60, 40));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "src", "tgt",
                        List.of(new double[]{30, 110}, new double[]{730, 110}), "", 1));

        LayoutQualityAssessor.ConnectionThroughVisualResult result =
                assessor.detectConnectionThroughVisuals(connections, nodes, List.of());

        assertEquals("a container's image is a backdrop, not a visual the route penetrates",
                0, result.count());
    }

    @Test
    public void connectionThroughVisuals_shouldStillFlagALeafElementsImage() {
        // Negative control: the same geometry with a leaf host must still flag, so the test above
        // cannot pass because the detector stopped seeing images altogether.
        AssessmentNode imageLeaf = new AssessmentNode("leaf", 200, 0, 300, 220, null,
                false, false, null, 0.0, "images/leaf.png", "fill", 0.0, 0.0, 0.0, false, null, false,
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
        List<AssessmentNode> nodes = List.of(
                node("src", 0, 90, 60, 40), imageLeaf, node("tgt", 700, 90, 60, 40));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "src", "tgt",
                        List.of(new double[]{30, 110}, new double[]{730, 110}), "", 1));

        assertEquals("a leaf element's image is still an obstruction", 1,
                assessor.detectConnectionThroughVisuals(connections, nodes, List.of()).count());
    }

    @Test
    public void labelOverlaps_shouldNotReportExhaustedLabelPositions_againstAContainerOnAVerticalSegment() {
        // The vertical-segment arm asks "is every label position taken?" by scanning neighbours. A
        // transparent container is not a neighbour that can take a position away, so a label beside
        // one has not run out of room.
        List<AssessmentNode> nodes = List.of(
                node("src", 300, 0, 60, 40),
                node("tgt", 300, 600, 60, 40),
                zone("zone", 200, 200, 300, 260));
        // Vertical hosting segment: both endpoints share an x, so the midpoint label sits beside the
        // zone with nothing else nearby.
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "src", "tgt",
                        List.of(new double[]{330, 40}, new double[]{330, 600}), "flows to", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);

        assertFalse("a transparent container does not exhaust a label's positions: " + result.descriptions(),
                result.descriptions().stream().anyMatch(d -> d.contains("no clear label position")));
    }

    @Test
    public void labelOverlaps_shouldStillReportExhaustedLabelPositions_againstALeafElement() {
        // Negative control for the arm above — same geometry, leaf instead of container.
        List<AssessmentNode> nodes = List.of(
                node("src", 300, 0, 60, 40),
                node("tgt", 300, 600, 60, 40),
                node("blocker", 200, 200, 300, 260));
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "src", "tgt",
                        List.of(new double[]{330, 40}, new double[]{330, 600}), "flows to", 1));

        LayoutQualityAssessor.LabelOverlapResult result =
                assessor.countLabelOverlaps(connections, nodes);

        assertTrue("a solid neighbour DOES exhaust the label's positions: " + result.descriptions(),
                result.descriptions().stream().anyMatch(d -> d.contains("no clear label position")));
    }

    @Test
    public void noteOverlaps_shouldNameAGroupingContainerAsAnElement_notAsAGroup() {
        // Deliberate, and easy to "tidy" into a bug: the containment SKIP above this description
        // reads the container flag, while the description itself reads the native-group flag. That
        // is not a half-finished migration. get-view-contents reports a native group under `groups`
        // and an ArchiMate Grouping among the ELEMENTS, so naming a Grouping "group" would send a
        // caller to a bucket that structurally cannot hold its id.
        AssessmentNode note = new AssessmentNode("n1", 210, 210, 80, 40, null,
                false, true, null, 0.0, null, null, 0.0, 0.0, 0.0);
        List<AssessmentNode> layoutNodes = List.of(zone("zone", 200, 200, 300, 260));

        LayoutQualityAssessor.NoteOverlapResult result =
                assessor.countNoteOverlaps(List.of(note), layoutNodes);

        assertEquals(1, result.count());
        assertTrue("a Grouping must be named by the bucket a caller can look it up in: "
                        + result.descriptions(),
                result.descriptions().get(0).contains("overlaps element 'zone'"));
    }

    @Test
    public void noteOverlaps_shouldNameANativeGroupAsAGroup() {
        AssessmentNode note = new AssessmentNode("n1", 210, 210, 80, 40, null,
                false, true, null, 0.0, null, null, 0.0, 0.0, 0.0);
        List<AssessmentNode> layoutNodes = List.of(group("g1", 200, 200, 300, 260));

        LayoutQualityAssessor.NoteOverlapResult result =
                assessor.countNoteOverlaps(List.of(note), layoutNodes);

        assertTrue("negative control: a native group is still named a group: " + result.descriptions(),
                result.descriptions().get(0).contains("overlaps group 'g1'"));
    }

    // ---- A finding with no remedy is named even on a view that already has prose ----
    //
    // Execution mode: headless, no display. Pure assessor over hand-built nodes.
    //
    // The disclosure used to be gated on "no other prose exists at all", so the moment any explained
    // defect fired, a co-occurring finding with no remedy was named nowhere. It is now sourced from
    // what THIS run left unexplained, recorded as each sentence is added.

    private static String disclosureIn(List<String> suggestions) {
        return suggestions.stream()
                .filter(t -> t.contains("carries no specific remedy above")
                        || t.contains("carry no specific remedy above"))
                .findFirst().orElse(null);
    }

    @Test
    public void unexplainedFinding_isNamed_evenWhenAnotherDefectAlreadyHasProse() {
        // The combination case. The ladder fires diagonal terminals, which HAS a remedy, alongside
        // three edge crossings, which do not at this count. Both must reach the caller: the older
        // gate emitted the first and said nothing at all about the second.
        LayoutAssessmentResult result = assessor.assess(threeCrossingLadder(),
                threeCrossingConnections(), false);

        assertEquals("the fixture must carry an unexplained finding", 3, result.edgeCrossingCount());
        assertTrue("...alongside a defect that DOES have prose: " + result.suggestions(),
                result.suggestions().stream().anyMatch(t -> t.contains("diagonal terminal segments")));

        String disclosure = disclosureIn(result.suggestions());
        assertNotNull("the finding no prose accounted for must still be named when other prose"
                + " fired: " + result.suggestions(), disclosure);
        assertTrue("with its name and its count: " + disclosure,
                disclosure.contains("edgeCrossings (3)"));
    }

    @Test
    public void unexplainedSet_isSourcedFromTheRun_notFromWhichMetricsHaveProse() {
        // edgeCrossings is registered AND has a remedy — above CROSSING_SUGGESTION_THRESHOLD. A
        // static "these metrics carry prose" mapping would therefore mark it explained on every
        // run and stay silent here, where the branch that would have explained it did not fire.
        // This is the fixture that separates a per-run record from a per-metric table.
        LayoutAssessmentResult result = assessor.assess(threeCrossingLadder(),
                threeCrossingConnections(), false);

        assertTrue("the fixture must sit BELOW the threshold that gives crossings a remedy",
                result.edgeCrossingCount() > 0 && result.edgeCrossingCount() <= 10);
        assertTrue("no branch may have written a crossings remedy at this count: "
                        + result.suggestions(),
                result.suggestions().stream().noneMatch(t -> t.contains("edge crossings —")));
        assertNotNull("so the disclosure must name it: " + result.suggestions(),
                disclosureIn(result.suggestions()));
    }

    @Test
    public void unexplainedDisclosure_staysSilent_whenEveryFindingCarriesItsOwnRemedy() {
        // The backstop must not become a second prose surface. A note overlap now has a remedy of
        // its own, so nothing is left over and the disclosure has nothing to say.
        List<AssessmentNode> nodes = new ArrayList<>(cleanTriple());
        nodes.add(note("n1", 420, 20, 100, 40));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("the fixture must fire the metric", 1, result.noteOverlapCount());
        assertNull("a finding its own branch explained must not be repeated by the backstop: "
                + result.suggestions(), disclosureIn(result.suggestions()));
    }

    @Test
    public void noteOverlap_andAnElementOverlap_areBothNamed_onTheSameView() {
        // The case the older gate lost outright: an explained defect and an informational finding
        // on one view. Before the informational remedy existed, this view's response mentioned the
        // element overlap and nothing whatever about the note.
        List<AssessmentNode> nodes = new ArrayList<>(List.of(
                node("A", 400, 0, 120, 60),
                node("B", 400, 200, 120, 60),
                node("C", 400, 400, 120, 60),
                node("D", 440, 220, 120, 60)));
        nodes.add(note("n1", 420, 20, 100, 40));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("the fixture must carry a rated overlap", 1, result.overlapCount());
        assertEquals("...and an informational note overlap", 1, result.noteOverlapCount());
        assertTrue("the rated overlap keeps its prose: " + result.suggestions(),
                result.suggestions().stream().anyMatch(t -> t.contains("overlapping element pairs")));
        assertTrue("and the note overlap is named too: " + result.suggestions(),
                result.suggestions().stream().anyMatch(t -> t.contains("note-over-object overlap")));
    }

    // ---- The thirteen informational remedies ----

    @Test
    public void noteOverlapRemedy_carriesTheLeverPublishedInItsOwnDescriptionBlock() {
        // The lever is LIFTED from this tool's served description block rather than invented here.
        // A remedy authored in this file that disagreed with the block would fork the two surfaces,
        // and one naming a tool that cannot move the cause is worse than the silence it replaces.
        List<AssessmentNode> nodes = new ArrayList<>(cleanTriple());
        nodes.add(note("n1", 420, 20, 100, 40));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        String prose = result.suggestions().stream()
                .filter(t -> t.contains("note-over-object overlap"))
                .findFirst().orElseThrow();
        assertTrue("the published lever must be the one offered: " + prose,
                prose.contains("Move the note clear with update-view-object, or nest it inside the"
                        + " container it belongs to"));
        assertTrue("and the caller must be told where the objects are named: " + prose,
                prose.contains("see assess-layout's noteOverlapDescriptions for the objects affected"));
    }

    @Test
    public void noteOverlapRemedy_countsPairs_andSaysSo_ratherThanClaimingThatManyNotes() {
        // ONE note lying across eleven elements. The detector counts (note, object) PAIRS, which
        // the field name does not say — so an opening clause reading "11 notes overlap" would be a
        // false statement about a view holding a single note.
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            nodes.add(node("e" + i, 400, i * 100, 120, 60));
        }
        nodes.add(note("n", 410, 10, 100, 1050));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("one note across eleven elements is eleven pairs",
                11, result.noteOverlapCount());
        String prose = result.suggestions().stream()
                .filter(t -> t.contains("note-over-object overlap"))
                .findFirst().orElseThrow();
        assertTrue("the subject must be the pairs, not the notes: " + prose,
                prose.contains("11 note-over-object overlaps were measured"));
        assertFalse("the view holds ONE note, so the prose must not say eleven: " + prose,
                prose.contains("11 notes"));
    }

    @Test
    public void informationalRemedy_statesTheShortfall_andItsSize_whenTheCountOutrunsTheCap() {
        // The description list caps at ten and this dimension publishes no violator-id key, so past
        // the cap the remainder sits in no field at all. Pointing at the list as though it named
        // them all would be the unverified claim the whole disclosure exists to stop making.
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            nodes.add(node("e" + i, 400, i * 100, 120, 60));
        }
        nodes.add(note("n", 410, 10, 100, 1050));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        String prose = result.suggestions().stream()
                .filter(t -> t.contains("note-over-object overlap"))
                .findFirst().orElseThrow();
        assertTrue("the shortfall and its size must be stated: " + prose,
                prose.contains("noteOverlapDescriptions names the first 10 of them"));
        assertTrue("...including that nothing else can recover the rest: " + prose,
                prose.contains("publishes no violator-id list, so the remaining 1 has to be found"
                        + " in the render"));
    }

    @Test
    public void informationalRemedy_claimsNoShortfall_whenTheListIsComplete() {
        // The honest case must not degrade. Below the cap the list DOES name them all, and saying
        // otherwise sends the caller to the render for objects already in the response.
        List<AssessmentNode> nodes = new ArrayList<>(cleanTriple());
        nodes.add(note("n1", 420, 20, 100, 40));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        String prose = result.suggestions().stream()
                .filter(t -> t.contains("note-over-object overlap"))
                .findFirst().orElseThrow();
        assertFalse("a complete list must not be described as short: " + prose,
                prose.contains("have to be found in the render")
                        || prose.contains("has to be found in the render"));
    }

    @Test
    public void parallelGapNarrowRemedy_pointsAtTheViolatorKey_becauseItHasNoDescriptionList() {
        // This metric publishes no description list at all, so the conventional
        // "<metric>Descriptions" pointer would be a dead field name. It points at the violator key
        // instead, and states the precondition — the detail object is null unless the ids were
        // requested, so promising it unconditionally would be a second dead pointer.
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("vA", "src", "tgt",
                        List.of(new double[]{100, 0}, new double[]{100, 200}), "", 1),
                new AssessmentConnection("vB", "src", "tgt",
                        List.of(new double[]{120, 0}, new double[]{120, 200}), "", 1));
        List<AssessmentNode> nodes = List.of(
                node("src", -10000, -10000, 10, 10),
                node("tgt", 10000, 10000, 10, 10));

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

        assertEquals("the fixture must fire the narrow-gap count",
                2, result.vAxisParallelGapNarrow25Count());
        String prose = result.suggestions().stream()
                .filter(t -> t.contains("nearest parallel segment"))
                .findFirst().orElseThrow();
        assertTrue("the violator key is where the ids actually are: " + prose,
                prose.contains("violatorIds key parallelConnectionGapV"));
        assertTrue("and the detail object's precondition must travel with it: " + prose,
                prose.contains("in its parallelConnectionGapDetail — both returned only when"
                        + " assess-layout is called with includeViolatorIds"));
        assertFalse("there is no description list for this metric, so none may be named: " + prose,
                prose.contains("Descriptions"));
    }

    @Test
    public void parallelGapNarrowRemedy_doesNotOfferASpacingTool_whichItsOwnBlockRulesOut() {
        // The served block says outright that convenience spacing tools cannot mitigate a
        // narrow-corridor floor. Naming one here would be a remedy pointing at the wrong lever,
        // which is worse than the silence it replaced: the caller spends a call and learns nothing.
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("vA", "src", "tgt",
                        List.of(new double[]{100, 0}, new double[]{100, 200}), "", 1),
                new AssessmentConnection("vB", "src", "tgt",
                        List.of(new double[]{120, 0}, new double[]{120, 200}), "", 1));
        List<AssessmentNode> nodes = List.of(
                node("src", -10000, -10000, 10, 10),
                node("tgt", 10000, 10000, 10, 10));

        LayoutAssessmentResult result = assessor.assess(nodes, connections, false);

        String prose = result.suggestions().stream()
                .filter(t -> t.contains("nearest parallel segment"))
                .findFirst().orElseThrow();
        assertFalse("adjust-view-spacing cannot move a narrow-corridor floor: " + prose,
                prose.contains("adjust-view-spacing"));
        assertTrue("the published lever is a topology or bendpoint change: " + prose,
                prose.contains("redesign the topology")
                        && prose.contains("update-view-connection"));
    }

    // ---- Every informational remedy is pinned against the output it actually produces ----
    //
    // Execution mode: headless, no display. Pure assessor over hand-built nodes.
    //
    // Each remedy is a string built from a count, a ternary and a shortfall clause, matched against
    // the caller by nothing but its own text. A source-scanning parity guard can prove that a
    // remedy for a metric EXISTS; only a fixture can prove the sentence it emits carries the right
    // lever, the right field pointer and the right arithmetic. Without these, a swapped ternary, a
    // dead field name or a dropped shortfall clause passes the whole suite.

    /** The one suggestion containing {@code needle}, or a failure naming everything that was said. */
    private static String remedyIn(LayoutAssessmentResult result, String needle) {
        return result.suggestions().stream()
                .filter(t -> t.contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no suggestion contains \"" + needle
                        + "\"; the run said: " + result.suggestions()));
    }

    @Test
    public void everyFieldPointerInTheProse_namesTheToolThatPublishesIt() {
        // THE CROSS-SURFACE RULE. This suggestion list is republished verbatim by
        // auto-layout-and-route and adjust-view-spacing, whose result types carry no description
        // lists, no violator-id map and no coverage map. A bare "see noteOverlapDescriptions" is
        // therefore a pointer at a field that is not on the response in front of those callers, and
        // includeViolatorIds is a parameter only assess-layout takes. Every pointer must name the
        // tool that publishes what it points at.
        List<AssessmentNode> nodes = new ArrayList<>(cleanTriple());
        nodes.add(note("n1", 420, 20, 100, 40));
        nodes.add(noteNode("clipped", 800, 0, 120, 40, 90.0));
        nodes.add(cfNode("zone", 1200, 0, 300, 200, null, true, "#80FF80"));
        nodes.add(cfNode("blob", 1220, 20, 100, 50, "zone", false, "#80FF80"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), true);

        for (String suggestion : result.suggestions()) {
            boolean pointsAtAField = suggestion.contains("Descriptions")
                    || suggestion.contains("violatorIds")
                    || suggestion.contains("includeViolatorIds")
                    || suggestion.contains("cousinOverlaps for the objects");
            if (pointsAtAField) {
                assertTrue("this sentence sends the caller to a field but never says which tool"
                        + " publishes it, so it resolves on neither of the two tools that"
                        + " republish this list: " + suggestion,
                        suggestion.contains("assess-layout"));
            }
        }
        assertTrue("the fixture must produce at least one field-pointing sentence, or this guard"
                        + " certifies nothing: " + result.suggestions(),
                result.suggestions().stream().anyMatch(t -> t.contains("Descriptions")));
    }

    @Test
    public void noteClipRemedy_carriesItsPublishedLever_andItsDescriptionPointer() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 50),
                noteNode("n1", 400, 0, 120, 40, 90.0));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("the fixture must fire the metric", 1, result.noteClipCount());
        String prose = remedyIn(result, "more height than its box provides");
        assertTrue("the published lever must be offered: " + prose,
                prose.contains("Re-send the note's text (or its width) through update-view-object"
                        + " with height omitted"));
        assertTrue("...and the objects must be locatable: " + prose,
                prose.contains("see assess-layout's noteClipDescriptions for the objects affected"));
    }

    @Test
    public void noteClipRemedy_statesTheShortfall_whenTheCountOutrunsTheCap() {
        // One of the five dimensions publishing NO violator-id key, so past the cap the remainder
        // sits in no field at all and the prose has to say so.
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(node("a", 0, 0, 100, 50));
        for (int i = 0; i < 11; i++) {
            nodes.add(noteNode("n" + i, 400, i * 100, 120, 40, 90.0));
        }

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("the fixture must outrun the cap", 11, result.noteClipCount());
        String prose = remedyIn(result, "more height than their boxes provide");
        assertTrue("the shortfall and its size must be stated: " + prose,
                prose.contains("assess-layout's noteClipDescriptions names the first 10 of them"));
        assertTrue("...including that nothing can recover the rest: " + prose,
                prose.contains("publishes no violator-id list, so the remaining 1 has to be found"
                        + " in the render"));
    }

    @Test
    public void imageSiblingOverlapRemedy_carriesItsPublishedLever_cappedAndUncapped() {
        List<AssessmentNode> one = List.of(
                new AssessmentNode("img", 0, 0, 120, 55, null, false, false, "FillImg", 60.0,
                        "img/bg.png", "fill", 0.0, 0.0, 0.0),
                new AssessmentNode("over", 50, 10, 120, 55, null, false, false, "Overlapper", 60.0,
                        null, null, 0.0, 0.0, 0.0));

        LayoutAssessmentResult uncapped = assessor.assess(one, List.of(), false);
        assertEquals("the fixture must fire the metric", 1, uncapped.imageSiblingOverlapCount());
        String prose = remedyIn(uncapped, "image area overlapped by a sibling");
        assertTrue("the published lever must be offered: " + prose,
                prose.contains("Increase element spacing, reposition the image, or shrink the"
                        + " icon"));
        assertTrue("...and the objects must be locatable: " + prose,
                prose.contains("see assess-layout's imageSiblingOverlapDescriptions for the objects"
                        + " affected"));
        assertFalse("a complete list must not be described as short: " + prose,
                prose.contains("to be found in the render"));

        List<AssessmentNode> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(new AssessmentNode("img" + i, 0, i * 200, 120, 55, null, false, false,
                    "FillImg", 60.0, "img/bg.png", "fill", 0.0, 0.0, 0.0));
            many.add(new AssessmentNode("over" + i, 50, i * 200 + 10, 120, 55, null, false, false,
                    "Overlapper", 60.0, null, null, 0.0, 0.0, 0.0));
        }
        LayoutAssessmentResult capped = assessor.assess(many, List.of(), false);
        assertEquals("the capped fixture must outrun the cap", 11,
                capped.imageSiblingOverlapCount());
        String cappedProse = remedyIn(capped, "image area overlapped by a sibling");
        assertTrue("the shortfall and its size must be stated: " + cappedProse,
                cappedProse.contains("assess-layout's imageSiblingOverlapDescriptions names the"
                        + " first 10 of them"));
        assertTrue("...including that nothing can recover the rest: " + cappedProse,
                cappedProse.contains("publishes no violator-id list, so the remaining 1 has to be"
                        + " found in the render"));
    }

    @Test
    public void overlayIconCollisionRemedy_carriesItsPublishedLever_cappedAndUncapped() {
        LayoutAssessmentResult uncapped = assessor.assess(overlayIconPairs(1), List.of(), false);
        assertEquals("the fixture must fire the metric", 1,
                uncapped.overlayIconCollisionCount());
        String prose = remedyIn(uncapped, "collides with the icon of an element that contains it");
        assertTrue("the published lever must be offered: " + prose,
                prose.contains("Move the nested element, put one icon in a different corner, or"
                        + " shrink it"));
        assertTrue("...and the objects must be locatable: " + prose,
                prose.contains("see assess-layout's overlayIconCollisionDescriptions for the"
                        + " objects affected"));

        LayoutAssessmentResult capped = assessor.assess(overlayIconPairs(11), List.of(), false);
        assertEquals("the capped fixture must outrun the cap", 11,
                capped.overlayIconCollisionCount());
        String cappedProse = remedyIn(capped, "overlay icon colliding with the icon of an element");
        assertTrue("the shortfall and its size must be stated: " + cappedProse,
                cappedProse.contains("assess-layout's overlayIconCollisionDescriptions names the"
                        + " first 10 of them"));
        assertTrue("...including that nothing can recover the rest: " + cappedProse,
                cappedProse.contains("publishes no violator-id list, so the remaining 1 has to be"
                        + " found in the render"));
    }

    /** {@code n} container/child pairs whose top-left overlay icons collide. */
    private static List<AssessmentNode> overlayIconPairs(int n) {
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nodes.add(new AssessmentNode("zone" + i, 0, i * 400, 300, 200, null, false, false,
                    "Zone", 0.0, "img/zone.png", "top-left", 0.0, 24.0, 24.0));
            nodes.add(new AssessmentNode("kid" + i, 4, i * 400 + 4, 120, 60, "zone" + i, false,
                    false, "Child", 0.0, "img/child.png", "top-left", 0.0, 24.0, 24.0));
        }
        return nodes;
    }

    @Test
    public void connectionGrazesVisualRemedy_carriesItsPublishedLever_cappedAndUncapped() {
        List<AssessmentNode> one = List.of(
                node("src", 0, 0, 100, 50), node("tgt", 400, 0, 100, 50),
                noteObstacle("cap", 200, 20, 100, 80));
        LayoutAssessmentResult uncapped = assessor.assess(one,
                List.of(straightConn("c1", "src", "tgt", 50, 450, 25)), false);

        assertEquals("the fixture must fire the metric", 1,
                uncapped.connectionGrazesVisualCount());
        String prose = remedyIn(uncapped, "touches or clips the BORDER");
        assertTrue("the published lever must be offered: " + prose,
                prose.contains("Reroute the connection or move the note/image clear"));
        assertTrue("...and the objects must be locatable: " + prose,
                prose.contains("see assess-layout's connectionGrazesVisualDescriptions for the"
                        + " objects affected"));

        List<AssessmentNode> many = new ArrayList<>();
        List<AssessmentConnection> conns = new ArrayList<>();
        many.add(node("src", 0, 0, 100, 50));
        many.add(node("tgt", 400, 0, 100, 50));
        for (int i = 0; i < 11; i++) {
            many.add(noteObstacle("cap" + i, 150 + i * 20, 20, 15, 80));
        }
        conns.add(straightConn("c1", "src", "tgt", 50, 450, 25));
        LayoutAssessmentResult capped = assessor.assess(many, conns, false);
        assertEquals("the capped fixture must outrun the cap", 11,
                capped.connectionGrazesVisualCount());
        String cappedProse = remedyIn(capped, "touch or clip the BORDER");
        assertTrue("the shortfall and its size must be stated: " + cappedProse,
                cappedProse.contains("assess-layout's connectionGrazesVisualDescriptions names the"
                        + " first 10 of them"));
        assertTrue("...including that nothing can recover the rest: " + cappedProse,
                cappedProse.contains("publishes no violator-id list, so the remaining 1 has to be"
                        + " found in the render"));
    }

    @Test
    public void redundantBendpointRemedy_carriesItsPublishedLever_andAgreesInNumber() {
        AssessmentConnection conn = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{0, 100}, new double[]{50, 100}, new double[]{100, 100},
                        new double[]{0, 0}, new double[]{0, 1}, new double[]{50, 1}), "", 1);
        LayoutAssessmentResult result = assessor.assess(
                List.of(node("src", 0, 0, 100, 50), node("tgt", 200, 0, 100, 50)),
                List.of(conn), true);

        assertEquals("the fixture must fire the metric", 1,
                result.connectionRedundantBendpointCount());
        String prose = remedyIn(result, "collinear along a horizontal or vertical segment");
        assertTrue("the published lever must be offered: " + prose,
                prose.contains("Straighten the route or re-run auto-route-connections"));
        // The whole singular clause is quoted, opening noun phrase included, rather than the two
        // predicate halves alone. A pin reading only the halves stayed GREEN when the opening was
        // corrupted to "1 bendpoints are collinear ..." — the fragments it checked were in a
        // different part of the same arm, so the mutation changed nothing it could see.
        assertTrue("a single bendpoint takes singular forms from the noun phrase onward: " + prose,
                prose.contains("1 bendpoint is collinear along a horizontal or vertical segment and"
                        + " lies between its neighbours, so removing it would not change the"
                        + " orthogonal route. The reported point is genuinely removable"));
        assertFalse("...and must not carry the plural forms: " + prose,
                prose.contains("bendpoints are collinear") || prose.contains("removing them")
                        || prose.contains("points are genuinely"));
        assertTrue("this dimension HAS a violator key, so the pointer must offer it: " + prose,
                prose.contains("see assess-layout's connectionRedundantBendpointDescriptions"));
    }

    @Test
    public void containerFillRemedy_carriesItsPublishedLever_andAgreesInNumber() {
        LayoutAssessmentResult one = assessor.assess(List.of(
                cfNode("zone", 0, 0, 300, 200, null, true, "#80FF80"),
                cfNode("blob", 20, 20, 100, 50, "zone", false, "#80FF80")), List.of(), false);

        assertEquals("the fixture must fire the metric", 1, one.containerFillEqualsChildCount());
        String prose = remedyIn(one, "authored fill colour equal to a nested child's");
        assertTrue("the published lever must be offered: " + prose,
                prose.contains("Give that container a distinct (lighter) fill"));

        List<AssessmentNode> many = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            many.add(cfNode("zone" + i, 0, i * 400, 300, 200, null, true, "#80FF80"));
            many.add(cfNode("blob" + i, 20, i * 400 + 20, 100, 50, "zone" + i, false, "#80FF80"));
        }
        LayoutAssessmentResult several = assessor.assess(many, List.of(), false);
        assertEquals("the plural fixture must fire more than once", 3,
                several.containerFillEqualsChildCount());
        String pluralProse = remedyIn(several, "authored fill colour equal to a nested child's");
        assertTrue("the plural arm must not describe N containers with singular referents: "
                        + pluralProse,
                pluralProse.contains("each of them merges with its children")
                        && pluralProse.contains("Give each a distinct (lighter) fill"));
        assertFalse("...and must not keep the singular remedy: " + pluralProse,
                pluralProse.contains("Give that container a distinct"));
    }

    @Test
    public void labelOnNoteRemedy_carriesItsPublishedLever() {
        LayoutAssessmentResult result = assessor.assess(
                List.of(node("a", 0, 0, 100, 50), node("b", 400, 0, 100, 50),
                        noteObstacle("cap", 200, 0, 100, 50)),
                List.of(labeledConn("c1", "a", "b", 50, 450, 25, "Accesses")), true);

        assertEquals("the fixture must fire the metric", 1, result.labelOnNoteCount());
        String prose = remedyIn(result, "rendered on a note's rectangle");
        assertTrue("the published lever must be offered: " + prose,
                prose.contains("Reposition the label (apply a Label Offset, or run"
                        + " auto-route-connections) or move the note clear"));
        assertTrue("this dimension HAS a violator key, so the pointer must offer it: " + prose,
                prose.contains("see assess-layout's labelOnNoteDescriptions"));
    }

    @Test
    public void labelOnGroupRemedy_carriesItsPublishedLever() {
        LayoutAssessmentResult result = assessor.assess(
                List.of(node("a", 0, 0, 100, 50), node("b", 700, 0, 100, 50),
                        groupNode("zone", 100, 10, 400, 200, "Layer A")),
                List.of(labeledConn("c1", "a", "b", 50, 750, 25, "Accesses")), true);

        assertEquals("the fixture must fire the metric", 1, result.labelOnGroupCount());
        String prose = remedyIn(result, "rendered on a container's title band");
        assertTrue("the published lever must be offered: " + prose,
                prose.contains("Reposition the label or reroute the connection clear of the"
                        + " container title"));
        assertTrue("this dimension HAS a violator key, so the pointer must offer it: " + prose,
                prose.contains("see assess-layout's labelOnGroupDescriptions"));
    }

    @Test
    public void coincidentFacePortRemedy_carriesItsPublishedLever() {
        AssessmentNode hub = node("hub", 200, 100, 100, 200);
        AssessmentNode p1 = node("p1", 0, 110, 50, 20);
        AssessmentNode p2 = node("p2", 0, 160, 50, 20);
        AssessmentNode p3 = node("p3", 0, 230, 50, 20);
        AssessmentNode p4 = node("p4", 0, 280, 50, 20);
        LayoutAssessmentResult result = assessor.assess(List.of(hub, p1, p2, p3, p4),
                List.of(connToHubLeft("c1", p1, hub, 200), connToHubLeft("c2", p2, hub, 200),
                        connToHubLeft("c3", p3, hub, 200), connToHubLeft("c4", p4, hub, 200)),
                true);

        assertEquals("the fixture must fire the metric", 1, result.coincidentFacePortCount());
        String prose = remedyIn(result, "colliding onto one perimeter port");
        assertTrue("the published lever must be offered: " + prose,
                prose.contains("Spread the terminals across the face with auto-route-connections"));
        assertTrue("this dimension HAS a violator key, so the pointer must offer it: " + prose,
                prose.contains("see assess-layout's coincidentFacePortDescriptions"));
    }

    @Test
    public void cousinOverlapRemedy_carriesTheLeverPublishedInItsOwnDescriptionBlock() {
        // The story's evidence claimed every one of these metrics had a lever already published in
        // the served description block. For this one it did not — the block described what the
        // count means and stopped, so the remedy was authored here instead, which is exactly the
        // surface fork the "lift, do not invent" rule exists to stop. The lever is now published in
        // the block and lifted from it, and this pin holds the two together by reading the served
        // source rather than a copy of it.
        String served = readSource("net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/handlers/"
                + "ViewPlacementHandler.java")
                .replaceAll("\"\\s*\\+\\s*\"", "");
        String lever = "Reposition one object of each pair to separate them";
        assertTrue("the served description block must publish the lever this remedy lifts",
                served.contains(lever));

        LayoutAssessmentResult result = assessor.assess(List.of(
                group("zone", 0, 0, 200, 120),
                childNode("escapee", 150, 20, 200, 60, "zone"),
                node("neighbour", 300, 20, 120, 60),
                node("distant", 0, 400, 120, 60)), List.of(), false);

        String prose = remedyIn(result, "cross-branch overlapping pair");
        assertTrue("...and the emitted remedy must be that same lever, not one authored beside the"
                        + " detector: " + prose, prose.contains(lever));
    }

    // ---- A companion does not repeat its principal ----

    @Test
    public void cousinOverlaps_areNamed_whenTheirPrincipalIsClean() {
        // A child escapes its group and lands on a top-level element. The group itself does not
        // overlap that element, so the rated same-parent count stays clean and the cross-branch
        // pair is the only report of the collision a reader can see.
        List<AssessmentNode> nodes = List.of(
                group("zone", 0, 0, 200, 120),
                childNode("escapee", 150, 20, 200, 60, "zone"),
                node("neighbour", 300, 20, 120, 60),
                node("distant", 0, 400, 120, 60));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("the principal must be clean for this direction to mean anything",
                0, result.overlapCount());
        assertEquals("and the companion must have fired", 1, result.cousinOverlapCount());
        assertTrue("so the companion must be named: " + result.suggestions(),
                result.suggestions().stream()
                        .anyMatch(t -> t.contains("cross-branch overlapping pair")));
    }

    @Test
    public void cousinOverlaps_areSuppressed_whenTheirPrincipalAlreadyFired() {
        // One visible collision between two nested objects usually yields several cross-branch
        // pairs, because each object also overlaps the other's container. With the rated overlap
        // sentence already on the list, a second sentence reports one collision twice — with a
        // larger number, which reads as a second, worse defect.
        List<AssessmentNode> nodes = List.of(
                group("zoneLeft", 0, 0, 200, 120),
                childNode("nestedLeft", 20, 20, 100, 60, "zoneLeft"),
                group("zoneRight", 100, 0, 200, 120),
                childNode("nestedRight", 120, 20, 100, 60, "zoneRight"));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertTrue("the principal must have fired for this direction to mean anything",
                result.overlapCount() > 0);
        assertTrue("and the companion must be nonzero, or the suppression is untested",
                result.cousinOverlapCount() > 0);
        assertFalse("the companion must not repeat the collision: " + result.suggestions(),
                result.suggestions().stream()
                        .anyMatch(t -> t.contains("cross-branch overlapping pair")));
        assertNull("nor may the backstop name it instead — suppressed is accounted for, not"
                + " forgotten: " + result.suggestions(), disclosureIn(result.suggestions()));
    }

    @Test
    public void grazedElementTotal_isStated_whenItExceedsTheConnectionCount() {
        // The grazed-element companion has NO reachable standalone case: the detector records a
        // graze and increments the per-connection tally in the same block, so it is nonzero only
        // where its principal is too. It is therefore reported inside the principal's own sentence,
        // and only where it adds a number the caller could not otherwise derive.
        AssessmentConnection trunk = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{100, 148}, new double[]{500, 148},
                        new double[]{625, 25}), "", 1);
        LayoutAssessmentResult result = assessor.assess(
                List.of(node("src", 0, 0, 50, 50), node("tgt", 600, 0, 50, 50),
                        node("fA", 110, 150, 80, 100), node("fB", 250, 150, 80, 100),
                        node("fC", 400, 150, 80, 100)),
                List.of(trunk), false);

        assertEquals("one connection", 1, result.connectionEdgeCoincidenceCount());
        assertEquals("three distinct element edges", 3, result.edgeCoincidenceGrazedElementCount());
        List<String> hugging = result.suggestions().stream()
                .filter(t -> t.contains("consider channel offset"))
                .toList();
        assertEquals("exactly one sentence may describe the hugging: " + result.suggestions(),
                1, hugging.size());
        assertTrue("and it must carry the distinct-edge total: " + hugging.get(0),
                hugging.get(0).contains("reaches 3 distinct element edges in total"));
        assertNull("the companion is accounted for by that sentence, so the backstop must not name"
                + " it: " + result.suggestions(), disclosureIn(result.suggestions()));
    }

    @Test
    public void grazedElementTotal_isOmitted_whenItMerelyRepeatsTheConnectionCount() {
        // The other direction. Where each connection grazes exactly one element the two numbers
        // agree, and restating the count as though it were a second measurement is noise.
        AssessmentConnection trunk = new AssessmentConnection("c1", "src", "tgt",
                List.of(new double[]{25, 25}, new double[]{100, 148}, new double[]{500, 148},
                        new double[]{625, 25}), "", 1);
        LayoutAssessmentResult result = assessor.assess(
                List.of(node("src", 0, 0, 50, 50), node("tgt", 600, 0, 50, 50),
                        node("foreign", 200, 150, 200, 100)),
                List.of(trunk), false);

        assertEquals("the two counts must agree for this direction to mean anything",
                result.connectionEdgeCoincidenceCount(),
                result.edgeCoincidenceGrazedElementCount());
        String hugging = result.suggestions().stream()
                .filter(t -> t.contains("consider channel offset"))
                .findFirst().orElseThrow();
        assertFalse("a total equal to the count adds nothing and must be left out: " + hugging,
                hugging.contains("distinct element edges in total"));
        assertNull("the companion is still accounted for: " + result.suggestions(),
                disclosureIn(result.suggestions()));
    }

    // ---- Ratings are untouched ----

    @Test
    public void informationalCount_movesNoRating_noteOverlap() {
        // A disclosure story must not move a single assessed view's rating. The two states differ
        // in exactly one informational count and in nothing else.
        List<AssessmentNode> withNote = new ArrayList<>(cleanTriple());
        withNote.add(note("n1", 420, 20, 100, 40));

        LayoutAssessmentResult clean = assessor.assess(cleanTriple(), List.of(), false);
        LayoutAssessmentResult noted = assessor.assess(withNote, List.of(), false);

        assertEquals("the fixture must differ in the metric", 0, clean.noteOverlapCount());
        assertEquals("...and only in the metric", 1, noted.noteOverlapCount());
        assertEquals("overall", clean.overallRating(), noted.overallRating());
        assertEquals("layout", clean.layoutRating(), noted.layoutRating());
        assertEquals("routing", clean.routingRating(), noted.routingRating());
    }

    @Test
    public void informationalCount_movesNoRating_parallelGapNarrow() {
        // A second pair on a connection-level metric, so the pin is not resting on one detector.
        // The two runs differ only in how far apart the two parallel segments sit.
        List<AssessmentNode> nodes = List.of(
                node("src", -10000, -10000, 10, 10),
                node("tgt", 10000, 10000, 10, 10));
        List<AssessmentConnection> narrow = List.of(
                new AssessmentConnection("vA", "src", "tgt",
                        List.of(new double[]{100, 0}, new double[]{100, 200}), "", 1),
                new AssessmentConnection("vB", "src", "tgt",
                        List.of(new double[]{120, 0}, new double[]{120, 200}), "", 1));
        List<AssessmentConnection> wide = List.of(
                new AssessmentConnection("vA", "src", "tgt",
                        List.of(new double[]{100, 0}, new double[]{100, 200}), "", 1),
                new AssessmentConnection("vB", "src", "tgt",
                        List.of(new double[]{400, 0}, new double[]{400, 200}), "", 1));

        LayoutAssessmentResult narrowResult = assessor.assess(nodes, narrow, false);
        LayoutAssessmentResult wideResult = assessor.assess(nodes, wide, false);

        assertEquals("the narrow run must fire the metric",
                2, narrowResult.vAxisParallelGapNarrow25Count());
        assertEquals("the wide run must not", 0, wideResult.vAxisParallelGapNarrow25Count());
        assertEquals("overall", wideResult.overallRating(), narrowResult.overallRating());
        assertEquals("layout", wideResult.layoutRating(), narrowResult.layoutRating());
        assertEquals("routing", wideResult.routingRating(), narrowResult.routingRating());
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

    /**
     * A container that is NOT a native group — the shape an ArchiMate {@code Grouping} collects as:
     * {@code isGroup=false}, {@code isContainer=true}.
     *
     * <p>This combination is what every detector that reasons about transparency has to handle, and
     * it is unreachable through {@link #group} or {@link #node}, both of which leave the two flags
     * equal. A detector that reads the native-group flag where it should read the container flag
     * looks correct against every other helper here and wrong only against this one.
     */
    private static AssessmentNode zone(String id, double x, double y,
                                        double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null,
                0.0, 0.0, 0.0, false, null, true, AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
    }

    /** As {@link #zone}, carrying a full-bleed image so the image-vs-connection detector sees a rect. */
    private static AssessmentNode zoneWithImage(String id, double x, double y,
                                                 double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0,
                "images/zone.png", "fill", 0.0, 0.0, 0.0, false, null, true, AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
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

    // ---- The terminal verdict is sourced from what was MEASURED, not from what has prose ----
    //
    // Execution mode: headless, no display. Pure assessor over hand-built nodes.
    //
    // The verdict used to read `suggestions.isEmpty()`. Only the metrics with remedy prose can put
    // anything in that list, so a run whose findings were all among the metrics without prose left
    // it empty and the verdict stated that nothing was found on dimensions that were examined and
    // did find something. These fixtures are built so that exactly one silent metric is nonzero and
    // every metric with prose is clean, which is the state that made the sentence false.

    /** Three well-spaced, aligned elements — nothing for any detector to report. */
    private List<AssessmentNode> cleanTriple() {
        return List.of(
                node("A", 400, 0, 120, 60),
                node("B", 400, 200, 120, 60),
                node("C", 400, 400, 120, 60));
    }

    /**
     * Six elements in two facing columns, wired so the three connections cross each other three
     * times — above zero and below {@code CROSSING_SUGGESTION_THRESHOLD}, the band in which
     * {@code edgeCrossings} is measured and registered but no branch writes a remedy for it.
     */
    private List<AssessmentNode> threeCrossingLadder() {
        return List.of(
                node("leftTop", 0, 0, 80, 40),
                node("leftMid", 0, 200, 80, 40),
                node("leftLow", 0, 400, 80, 40),
                node("rightLow", 600, 400, 80, 40),
                node("rightMid", 600, 200, 80, 40),
                node("rightTop", 600, 0, 80, 40));
    }

    private List<AssessmentConnection> threeCrossingConnections() {
        return List.of(
                new AssessmentConnection("x1", "leftTop", "rightLow",
                        List.of(new double[]{80, 20}, new double[]{600, 420}), "", 1),
                new AssessmentConnection("x2", "leftMid", "rightMid",
                        List.of(new double[]{80, 220}, new double[]{600, 220}), "", 1),
                new AssessmentConnection("x3", "leftLow", "rightTop",
                        List.of(new double[]{80, 420}, new double[]{600, 20}), "", 1));
    }

    private static String verdictIn(List<String> suggestions) {
        return suggestions.stream()
                .filter(t -> t.startsWith("No defects were found"))
                .findFirst().orElse(null);
    }

    @Test
    public void terminalVerdict_isAbsent_whenASilentMetricFoundSomething() {
        // A note lying on an element. Notes are held apart from the scoring node set, so this moves
        // noteOverlapCount WITHOUT moving overlapCount — the single-variable fixture that isolates a
        // metric with no prose of its own. Every metric that does have prose stays clean.
        List<AssessmentNode> nodes = new ArrayList<>(cleanTriple());
        nodes.add(note("n1", 420, 20, 100, 40));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("the fixture must move the silent metric", 1, result.noteOverlapCount());
        assertEquals("...and no metric that already has prose", 0, result.overlapCount());
        assertNull("a run that found something must NOT say no defects were found: "
                + result.suggestions(), verdictIn(result.suggestions()));
    }

    @Test
    public void terminalVerdict_replacement_namesTheMetricAndTheCount_notMerelyThatSomethingFired() {
        // A count or a flag is not a report of state. "Some dimensions reported findings" would
        // repeat the defect one layer up, so the disclosure has to carry the name AND the number.
        //
        // The fixture is edge crossings BELOW the threshold that gives them a remedy, because that
        // is what "a finding nothing explained" now means. This test used to reach the same state
        // through a note overlap; note overlaps now carry a remedy of their own, so that fixture
        // would exercise the remedy rather than the disclosure and prove nothing about it.
        LayoutAssessmentResult result = assessor.assess(threeCrossingLadder(),
                threeCrossingConnections(), false);

        assertEquals("the fixture must fire a metric that no branch explains at this count",
                3, result.edgeCrossingCount());
        String named = result.suggestions().stream()
                .filter(t -> t.contains("carries no specific remedy above"))
                .findFirst().orElse(null);
        assertNotNull("a finding no prose accounted for must be named: " + result.suggestions(),
                named);
        assertTrue("the metric must be named: " + named, named.contains("edgeCrossings"));
        assertTrue("the COUNT must travel with the name, not just the fact: " + named,
                named.contains("edgeCrossings (3)"));
    }

    @Test
    public void terminalVerdict_stillFires_onAGenuinelyCleanRun_withItsCoverageArithmeticIntact() {
        // The sibling qualification must survive this change. A clean run still gets the verdict,
        // still scoped to what was examined, and still carrying the two coverage numbers it is
        // computed from rather than a re-derived pair.
        LayoutAssessmentResult result = assessor.assess(cleanTriple(), List.of(), false);

        String verdict = verdictIn(result.suggestions());
        assertNotNull("a clean run must still receive the scoped verdict: " + result.suggestions(),
                verdict);
        long notFullyExamined = result.coverage().values().stream()
                .filter(level -> !LayoutQualityAssessor.COVERAGE_CHECKED.equals(level))
                .count();
        assertTrue("the verdict must state the dimensions it could not certify, from the same map"
                        + " the caller can read back: " + verdict,
                verdict.contains(notFullyExamined + " of " + result.coverage().size()
                        + " coverage dimensions were not fully examined"));
    }

    @Test
    public void terminalVerdict_isNotSwallowed_byASuggestionThatSaysNoActionIsNeeded() {
        // The containment note reports the view behaving as INTENDED — its own text ends "No action
        // needed." It nonetheless made the list non-empty, which withheld the coverage
        // qualification from a view that has nothing wrong with it. A sentence that says a view is
        // fine must not be what suppresses the disclosure that the view was not fully examined.
        List<AssessmentNode> nodes = List.of(
                group("g", 0, 0, 300, 200),
                new AssessmentNode("g-child", 20, 60, 80, 40, "g", false, false,
                        null, 0.0, null, null, 0.0, 0.0, 0.0),
                node("A", 600, 0, 120, 60),
                node("B", 600, 200, 120, 60));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), true);

        assertTrue("the fixture must produce the expected-state note",
                result.containmentOverlapCount() > 0);
        assertTrue("...and nothing else", result.suggestions().stream()
                .anyMatch(t -> t.contains("No action needed")));
        assertNotNull("the coverage qualification must still reach the caller: "
                + result.suggestions(), verdictIn(result.suggestions()));
    }

    @Test
    public void terminalVerdict_containmentOverlaps_areNotNamedAsAFinding() {
        // The exclusion has a direction. Counting the expected-state overlap as a finding would make
        // the replacement sentence name something this same method calls expected — which is the
        // false-report defect inverted rather than fixed.
        List<AssessmentNode> nodes = List.of(
                group("g", 0, 0, 300, 200),
                new AssessmentNode("g-child", 20, 60, 80, 40, "g", false, false,
                        null, 0.0, null, null, 0.0, 0.0, 0.0),
                node("A", 600, 0, 120, 60),
                node("B", 600, 200, 120, 60));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), true);

        assertTrue("no suggestion may name containmentOverlaps as a finding: "
                        + result.suggestions(),
                result.suggestions().stream().noneMatch(t -> t.contains("containmentOverlaps (")));
    }

    // ---- The five metrics that move a rating and used to explain none of it ----

    @Test
    public void ratingBearingSilentMetrics_parentLabelObscured_namesThePaddingLever() {
        List<AssessmentNode> nodes = List.of(
                namedGroup("g", 0, 0, 120, 80, LONG_TITLE, 540.0),
                childOf("g", 10, 25),
                node("A", 400, 0, 120, 60),
                node("B", 400, 200, 120, 60));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), true);

        assertEquals("the fixture must move the metric", 1, result.parentLabelObscuredCount());
        String prose = result.suggestions().stream()
                .filter(t -> t.contains("topmost child"))
                .findFirst().orElse(null);
        assertNotNull("a metric that vetoes the rating must name its cause: " + result.suggestions(),
                prose);
        assertTrue("the lever published for this dimension is the parent's top padding: " + prose,
                prose.contains("Move children down or increase parent top padding"));
    }

    @Test
    public void ratingBearingSilentMetrics_labelTruncation_namesTheWidthLever() {
        List<AssessmentNode> nodes = List.of(
                namedNode("A", 400, 0, 120, 60, LONG_TITLE, 540.0),
                node("B", 400, 200, 120, 60),
                node("C", 400, 400, 120, 60));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);

        assertEquals("the fixture must move the metric", 1, result.labelTruncationCount());
        String prose = result.suggestions().stream()
                .filter(t -> t.contains("label is truncated"))
                .findFirst().orElse(null);
        assertNotNull("a metric that caps the rating must name its cause: " + result.suggestions(),
                prose);
        assertTrue("the lever published for this dimension is the element width: " + prose,
                prose.contains("resize-elements-to-fit"));
        assertNull("and the verdict must be gone: " + result.suggestions(),
                verdictIn(result.suggestions()));
    }

    @Test
    public void ratingBearingSilentMetrics_ratingsAreUntouched() {
        // This is a disclosure change, not a rating change. Two runs differing ONLY in a silent
        // informational metric's count must rate identically — and the pair has to be able to
        // differ, or the assertion would hold whatever the code did.
        List<AssessmentNode> without = cleanTriple();
        List<AssessmentNode> with = new ArrayList<>(without);
        with.add(note("n1", 420, 20, 100, 40));

        LayoutAssessmentResult clean = assessor.assess(without, List.of(), false);
        LayoutAssessmentResult flagged = assessor.assess(with, List.of(), false);

        assertEquals("the fixtures must genuinely differ on the metric",
                0, clean.noteOverlapCount());
        assertEquals(1, flagged.noteOverlapCount());
        assertEquals("an informational metric must move no rating",
                clean.overallRating(), flagged.overallRating());
        assertEquals(clean.layoutRating(), flagged.layoutRating());
        assertEquals(clean.routingRating(), flagged.routingRating());
        assertEquals("nor any breakdown entry",
                clean.ratingBreakdown(), flagged.ratingBreakdown());
    }

    @Test
    public void descriptionClause_statesTheShortfall_whenTheCountOutrunsTheCappedList() {
        // The description list caps at 10 and parentLabelObscured publishes no violator-id key, so
        // past the cap the remainder sits in no field at all. Pointing at the list as though it
        // named them all would be the same unverified claim this prose exists to stop making.
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            nodes.add(namedGroup("g" + i, i * 400, 0, 120, 80, LONG_TITLE, 540.0));
            nodes.add(childOf("g" + i, 10, 25));
        }

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), true);

        assertEquals("the fixture must outrun the cap", 12, result.parentLabelObscuredCount());
        String prose = result.suggestions().stream()
                .filter(t -> t.contains("topmost child"))
                .findFirst().orElseThrow();
        assertTrue("the shortfall and its size must be stated: " + prose,
                prose.contains("names the first 10 of them"));
        assertTrue("...including that nothing else can recover the rest: " + prose,
                prose.contains("publishes no violator-id list, so the remaining 2"));
    }

    @Test
    public void descriptionClause_doesNotInventAShortfall_whenTheListIsComplete() {
        // The honest case must not degrade. Below the cap the list DOES name them all, and saying
        // otherwise would send the caller to the render for objects that are already in the field.
        List<AssessmentNode> nodes = List.of(
                namedGroup("g", 0, 0, 120, 80, LONG_TITLE, 540.0),
                childOf("g", 10, 25),
                node("A", 400, 0, 120, 60));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(), true);

        String prose = result.suggestions().stream()
                .filter(t -> t.contains("topmost child"))
                .findFirst().orElseThrow();
        assertTrue("a complete list must be offered as complete: " + prose,
                prose.contains("see assess-layout's parentLabelObscuredDescriptions for the objects"
                        + " affected"));
        assertFalse("and must claim no shortfall: " + prose,
                prose.contains("have to be found in the render"));
    }

    @Test
    public void descriptionClause_everyFieldNameItPointsAt_mustResolveOnThePublishedResult() {
        // The prose hands the caller a field name to go and read. Those names are STRING literals,
        // so a later rename of the record component leaves the sentence pointing at a field that no
        // longer exists — and no compiler and no substring assertion would notice. The published
        // surface would keep confidently naming a dead field.
        //
        // BOTH sides are derived, neither is typed here. The names come from the actual
        // descriptionClause call sites in the production source, and the valid set comes from the
        // result record's own components. An earlier version of this test held the names in a list
        // written by hand: it stayed GREEN when a call site was changed to a nonexistent field,
        // because it was only ever checking its own list against the record. A pin that cannot see
        // the thing it is pinning certifies nothing.
        String source = readSource(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/LayoutQualityAssessor.java");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("descriptionClause\\(\"([A-Za-z0-9_]+)\"")
                .matcher(source);

        Set<String> published = new HashSet<>();
        for (java.lang.reflect.RecordComponent component
                : LayoutAssessmentResult.class.getRecordComponents()) {
            published.add(component.getName());
        }

        int checked = 0;
        while (matcher.find()) {
            String field = matcher.group(1);
            checked++;
            assertTrue("the suggestion prose sends the caller to '" + field + "', which is not a"
                    + " component of the published result — the pointer is dead",
                    published.contains(field));
        }

        // A regex that stops matching would otherwise pass this test by checking nothing. The floor
        // tracks the real call-site count — 17 when this line was last raised — because a floor
        // left far below it certifies a fraction of what the assertion above claims to cover.
        assertTrue("only " + checked + " descriptionClause call sites were found — the scan has"
                + " lost most of its target, so this guard is certifying far less than it claims",
                checked >= 17);
    }

    /** Reads a production source file by walking up to the checkout root. */
    private static String readSource(String relative) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Paths.get("").toAbsolutePath());
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
        // Stored-final-point variant:
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

    // ---- M6: Non-orthogonal terminals — Tier 2R cap fair (promoted) ----

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

    /**
     * The description list must honour its cap on the connection that fills it.
     *
     * <p>Every other capped detector in this class re-reads {@code descriptions.size()} at the
     * point of the add. This one took a single boolean snapshot at the top of the connection loop
     * and reused it for two adds, so a connection that BOTH crosses an unrelated element AND
     * routes through its own target contributed two entries off one stale read. The overshoot is
     * reachable only from a list standing at exactly one below the cap on entry to that
     * connection — anything lower has room for both, anything higher is already capped — and it is
     * bounded at one over. That narrowness is why 17 direct tests of this detector never saw it.</p>
     *
     * <p>The fixture drives the real detector rather than asserting on a hand-built record: the
     * defect lives in the loop's own bookkeeping, so a fabricated result cannot express it. Nine
     * cross-element-only connections bring the list to nine; the tenth is the both-ways case.</p>
     */
    @Test
    public void detectPassThroughs_shouldCapDescriptionsAtMax() {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();

        // Nine connections that each contribute exactly one cross-element description.
        for (int i = 0; i < 9; i++) {
            double y = i * 200;
            nodes.add(node("a" + i, 0, 90 + y, 50, 50));
            nodes.add(node("b" + i, 150, 90 + y, 50, 50));
            nodes.add(node("c" + i, 400, 90 + y, 50, 50));
            connections.add(new AssessmentConnection("x" + i, "a" + i, "c" + i,
                    List.of(new double[]{25, 115 + y}, new double[]{175, 115 + y},
                            new double[]{425, 115 + y}), "", 1));
        }

        // The tenth crosses unrelated 'bm' AND overshoots past its own target 'cm', so it reaches
        // both adds in one pass — with the list standing at nine.
        double ym = 9 * 200;
        nodes.add(node("am", 0, 100 + ym, 50, 50));
        nodes.add(node("bm", 200, 100 + ym, 50, 50));
        nodes.add(node("cm", 400, 100 + ym, 100, 50));
        connections.add(new AssessmentConnection("xm", "am", "cm",
                List.of(new double[]{25, 125 + ym}, new double[]{225, 125 + ym},
                        new double[]{600, 125 + ym}, new double[]{450, 125 + ym}), "", 2));

        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, false);

        // The cap constant is private; the sibling cap pins in this file assert the literal too.
        assertEquals("the last connection reaches both adds off one cap read, so the list must be"
                        + " re-measured between them",
                10, result.descriptions().size());
        // Same fixture, before and after the cap fix: the charged count does not move. Measured at
        // 10 against the pre-fix method, which returned 11 descriptions beside this same 10.
        assertEquals("re-measuring the list must not move the number the rating charges",
                10, result.crossElementCount());
    }

    /**
     * Capping the description list must not move the number the rating charges.
     *
     * <p>The cap has only ever governed the description list; {@code crossElementCount} is
     * incremented outside it, and the rating reads the count, not the list size. A fix that
     * tightened the wrong gate would silently under-charge every view with more than ten
     * pass-throughs, which no assertion on the list itself can see.</p>
     *
     * <p><strong>The fixture must charge MORE than the cap, or this pin is vacuous.</strong> With
     * exactly ten crossings the gate never excludes an add, so the count reads ten whether it is
     * incremented inside the gate or outside it, and moving the increment inside — the precise
     * regression this test exists to catch — leaves the whole suite green. Thirteen crossings
     * against a cap of ten is what makes the two placements observably different.</p>
     */
    @Test
    public void detectPassThroughs_cappingDescriptions_shouldNotCapTheChargedCount() {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        // Twelve cross-element-only connections — two past the cap, so the last two reach the add
        // with the list already full and are charged without being described.
        for (int i = 0; i < 12; i++) {
            double y = i * 200;
            nodes.add(node("a" + i, 0, 90 + y, 50, 50));
            nodes.add(node("b" + i, 150, 90 + y, 50, 50));
            nodes.add(node("c" + i, 400, 90 + y, 50, 50));
            connections.add(new AssessmentConnection("x" + i, "a" + i, "c" + i,
                    List.of(new double[]{25, 115 + y}, new double[]{175, 115 + y},
                            new double[]{425, 115 + y}), "", 1));
        }
        double ym = 12 * 200;
        nodes.add(node("am", 0, 100 + ym, 50, 50));
        nodes.add(node("bm", 200, 100 + ym, 50, 50));
        nodes.add(node("cm", 400, 100 + ym, 100, 50));
        connections.add(new AssessmentConnection("xm", "am", "cm",
                List.of(new double[]{25, 125 + ym}, new double[]{225, 125 + ym},
                        new double[]{600, 125 + ym}, new double[]{450, 125 + ym}), "", 2));

        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, false);

        assertEquals("the description list still stops at the cap",
                10, result.descriptions().size());
        assertEquals("every crossing is charged, cap or no cap — 13 crossings against a cap of 10",
                13, result.crossElementCount());
        assertEquals("...and every violating connection is still named for the caller",
                13, result.violatorIds().size());
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

    /**
     * A 2-point (zero-bendpoint) diagonal whose SOURCE terminal is suppressed by the perimeter
     * guard — the target's centre falls inside the wide source rect — but whose TARGET terminal
     * flags. The two branches clip against different rectangles, so the flag can be raised on the
     * target side of a path that carries no bendpoints at all. Such a connection is a straight
     * line between two element centres and belongs in the zero-bendpoint subset; filing it in the
     * routed subset would tell the agent to re-route a route that does not exist.
     */
    @Test
    public void shouldClassifyTwoPointDiagonalAsZeroBendpoint_whenPerimeterGuardSuppressesSourceTerminal() {
        // Source is a wide container-ish box; the target sits INSIDE its rect, so path[1]
        // (the target centre) is on-or-inside the source and the source branch never fires.
        List<AssessmentNode> nodes = List.of(
                node("wide-source", 0, 0, 400, 400),
                node("small-target", 250, 250, 40, 40));
        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("c-two-point", "wide-source", "small-target", List.of(
                        new double[]{200, 200}, new double[]{270, 270}), "", 0));

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(conns, nodes, true);

        assertEquals("target branch flags the diagonal", 1, result.count());
        assertTrue("flagged connection is a violator", result.violatorIds().contains("c-two-point"));
        assertEquals("a 2-point path carries no bendpoints, whichever branch flagged it",
                1, result.zeroBendpointCount());
        assertTrue("and it lands in the zero-bendpoint subset",
                result.zeroBendpointViolatorIds().contains("c-two-point"));
        assertEquals("so the routed subset stays empty — there is no route to re-route",
                0, result.routedCount());
        assertTrue(result.routedViolatorIds().isEmpty());
    }

    /**
     * The two subsets partition the flagged population: they are disjoint, they cover it, and the
     * union violator key keeps reporting the whole population rather than being narrowed to
     * either half.
     */
    @Test
    public void shouldPartitionFlaggedTerminalsIntoTwoDisjointSubsets_whenBothKindsArePresent() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 50, 50),
                node("b", 200, 200, 50, 50),
                node("c", 400, 0, 50, 50));
        List<AssessmentConnection> conns = List.of(
                // Straight line between two element centres — no bendpoints.
                new AssessmentConnection("c-straight", "a", "b", List.of(
                        new double[]{25, 25}, new double[]{225, 225}), "", 1),
                // Carries a stored route, with a diagonal target terminal.
                new AssessmentConnection("c-routed", "a", "c", List.of(
                        new double[]{25, 25}, new double[]{25, 200}, new double[]{225, 425}), "", 1),
                // Orthogonal throughout — flagged by neither branch.
                new AssessmentConnection("c-clean", "a", "c", List.of(
                        new double[]{25, 25}, new double[]{425, 25}), "", 1));

        LayoutQualityAssessor.NonOrthogonalTerminalResult result =
                assessor.countNonOrthogonalTerminals(conns, nodes, true);

        assertEquals(1, result.zeroBendpointCount());
        assertEquals(1, result.routedCount());
        assertEquals("the two subsets sum to the total, with nothing left over",
                result.count(), result.zeroBendpointCount() + result.routedCount());

        assertEquals(Set.of("c-straight"), result.zeroBendpointViolatorIds());
        assertEquals(Set.of("c-routed"), result.routedViolatorIds());
        assertTrue("the subsets share no connection",
                java.util.Collections.disjoint(
                        result.zeroBendpointViolatorIds(), result.routedViolatorIds()));

        Set<String> union = new java.util.HashSet<>(result.zeroBendpointViolatorIds());
        union.addAll(result.routedViolatorIds());
        assertEquals("the pre-existing key still carries the whole population",
                union, result.violatorIds());
        assertFalse("and the connection neither branch flagged is in none of them",
                result.violatorIds().contains("c-clean"));
    }

    /**
     * The partition survives the trip through {@code assess}: the two counts reach the assessment
     * result, they sum to the unchanged total, and the two subset violator keys appear beside the
     * union key rather than replacing it.
     */
    @Test
    public void shouldPublishBothHalvesOfTheTerminalPartition_whenAssessRunsOnAMixedView() {
        LayoutAssessmentResult result = assessor.assess(mixedTerminalNodes(),
                mixedTerminalConnections(), true);

        assertEquals(2, result.nonOrthogonalTerminalCount());
        assertEquals(1, result.zeroBendpointNonOrthogonalTerminalCount());
        assertEquals(1, result.routedNonOrthogonalTerminalCount());
        assertEquals("the published halves account for the published total exactly",
                result.nonOrthogonalTerminalCount(),
                result.zeroBendpointNonOrthogonalTerminalCount()
                        + result.routedNonOrthogonalTerminalCount());

        Map<String, Set<String>> violators = result.violatorIds();
        assertEquals(Set.of("c-straight"),
                violators.get("nonOrthogonalTerminalsZeroBendpoint"));
        assertEquals(Set.of("c-routed"), violators.get("nonOrthogonalTerminalsRouted"));

        Set<String> union = new java.util.HashSet<>(
                violators.get("nonOrthogonalTerminalsZeroBendpoint"));
        union.addAll(violators.get("nonOrthogonalTerminalsRouted"));
        assertEquals("the existing key is added to, never narrowed",
                union, violators.get("nonOrthogonalTerminals"));
    }

    /**
     * The partition is a REPORTING split. Publishing it must not move any rating surface: the
     * measured halves feed the suggestion text only, never the rating inputs, which continue to
     * read the unchanged total. The expected values below were measured on the unmodified
     * assessor and re-measured after the split was introduced; they are identical.
     */
    @Test
    public void shouldLeaveEveryRatingSurfaceUnmoved_whenTheTerminalPartitionIsPublished() {
        LayoutAssessmentResult result = assessor.assess(mixedTerminalNodes(),
                mixedTerminalConnections(), true);

        assertEquals("fair", result.overallRating());
        assertEquals("excellent", result.layoutRating());
        assertEquals("fair", result.routingRating());
        assertEquals("poor", result.ratingBreakdown().get("nonOrthogonalTerminals"));
        assertEquals("excellent",
                result.ratingBreakdown().get("overallExcludingAcceptedCosmetics"));
        assertEquals(2, result.nonOrthogonalTerminalCount());
    }

    /** One straight-line terminal, one routed terminal, one clean connection. */
    private static List<AssessmentNode> mixedTerminalNodes() {
        return List.of(
                node("a", 0, 0, 50, 50),
                node("b", 200, 200, 50, 50),
                node("c", 400, 0, 50, 50));
    }

    private static List<AssessmentConnection> mixedTerminalConnections() {
        return List.of(
                new AssessmentConnection("c-straight", "a", "b", List.of(
                        new double[]{25, 25}, new double[]{225, 225}), "", 1),
                new AssessmentConnection("c-routed", "a", "c", List.of(
                        new double[]{25, 25}, new double[]{25, 200}, new double[]{225, 425}), "", 1));
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
        // Should also have re-route advice for the routed portion. On a mixed view that advice is
        // SCOPED: the ids of the routed half are handed to auto-route-connections as connectionIds
        // so the call cannot reach the zero-bendpoint half, whose own entry warns against exactly
        // that. An unscoped re-route here would be the two entries contradicting each other.
        boolean hasScopedReRoute = result.suggestions().stream()
                .anyMatch(s -> s.contains("pass exactly these connection IDs to"
                        + " auto-route-connections as connectionIds"));
        assertTrue("routed half's remedy is scoped to its own ids", hasScopedReRoute);
        // Neither entry may send the agent on a view-wide re-route while the other is present.
        boolean hasUnscopedReRoute = result.suggestions().stream()
                .anyMatch(s -> s.contains("re-run auto-route-connections"));
        assertFalse("no unscoped re-route while both halves are present", hasUnscopedReRoute);

        // Each entry states which half it is, out of the shared total, so the two read as one
        // partition rather than as two unrelated findings.
        assertTrue("zero-bendpoint half names itself against the total",
                result.suggestions().stream().anyMatch(s -> s.contains(
                        "1 of 2 connections with diagonal terminal segments carry no bendpoints")));
        assertTrue("routed half names itself against the same total",
                result.suggestions().stream().anyMatch(s -> s.contains(
                        "1 of 2 connections with diagonal terminal segments carry a routed body")));

        // Each entry names the violator key carrying exactly its own ids, and the precondition
        // for receiving them — a remedy that says "scope your call with these ids" against a
        // response that carries no violatorIds map sends the agent after a field that is absent.
        assertTrue("zero-bendpoint half names its key and the precondition",
                result.suggestions().stream().anyMatch(s ->
                        s.contains("nonOrthogonalTerminalsZeroBendpoint")
                                && s.contains("includeViolatorIds is true")));
        assertTrue("routed half names its key and the precondition",
                result.suggestions().stream().anyMatch(s ->
                        s.contains("nonOrthogonalTerminalsRouted")
                                && s.contains("includeViolatorIds is true")));
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
    // M1-M6 unit tests
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

    // When terminal BPs are exterior to the
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
        // Pre-clamp ratio = 11/4 = 2.75; post-clamp = 1.0.
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

    // ---- M2: assess_withStylingAndLabelFields_shouldChangeRating_underM6Promotions (REPLACES old test) ----
    //
    // The previous `assess_withStylingAndLabelFields_shouldNotChangeRating` (REPLACED 2026-04-26) asserted
    // that those informational fields had NO rating impact. Under M6 the OPPOSITE is required:
    // parentLabelObscuredCount and labelTruncationCount are explicitly promoted (Tier 1L and
    // Tier 2R respectively) — when they're nonzero the rating MUST move.
    @Test
    public void assess_withStylingAndLabelFields_shouldChangeRating_underM6Promotions() {
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
    // Tier-1R so overall reads "poor" (ratified guardrail beside the egress-lift router fix).
    // Synthetic counts isolate the escalation logic in computeRoutingTierLevel; detection is
    // covered by the m4_* tests above. No existing fixture has M4 >= 7, so no prior overall pin
    // moves (Task-1.3 re-pin reduces to "verify none break").
    @Test
    public void aGated_m4EgregiousCount_escalatesOverallToPoor() {
        // Clean baseline (same arg shape as assess_withStylingAndLabelFields cleanResult = "excellent"),
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

    // ====================================================================
    // BLOCK: NESTING DOES NOT DEPRESS THE DENSITY METRIC — THE MEASUREMENT
    // ====================================================================
    //
    // A standing proposal held that a deeply-nested, connection-light inventory is INHERENTLY
    // capped at a "fair" spacing rating however clean it is, because "the density metric penalises
    // the tight spacing nesting REQUIRES", and that it therefore needs a carve-out before it can be
    // scored fairly. These tests are the measurement that proposal was never given, and they
    // record the answer where it cannot quietly be forgotten.
    //
    // The claim is false, and the reason is in the metric's own code: containment pairs are
    // excluded from the average at EVERY ancestor level, not just for the immediate parent. So the
    // distances that survive into the average are sibling and cousin gaps — exactly the distances a
    // reader perceives as crowding — and nesting contributes none of them. Declaring a containment
    // can only RAISE a view's average spacing, never lower it, which is the opposite direction from
    // the one the proposal needs.
    //
    // What remains true is narrower and is not a defect: a view whose SIBLINGS are packed tightly
    // does read "fair", nested or not. That is the metric working. A carve-out keyed on "this view
    // is nested" would excuse exactly the layouts a reader would call crowded.

    /**
     * Two containers side by side inside a band, each holding two leaves, with one gap value used
     * at every level.
     *
     * <p>EVERY rectangle is DERIVED from the gap rather than hard-coded, and that is not tidiness.
     * An earlier version of this fixture fixed the container width at 240 px while spacing the
     * leaves by the gap, so at gap=60 each container's second leaf ran 80 px past its own parent's
     * right edge and into the neighbouring container's box. That made the "clean" fixture not
     * clean, and it went unnoticed because a cross-branch overlap — two nodes with different
     * parents and no ancestor relationship — is counted by NEITHER {@code overlapCount} (which
     * requires the same {@code parentId}) NOR {@code containmentOverlapCount} (which requires a
     * containment pair). The nested reading was consequently depressed by zero-distance pairs that
     * no assertion could see. Deriving the sizes makes the geometry correct by construction at any
     * gap, and the resulting average is then exactly the gap — a number that is obviously right
     * rather than one that has to be trusted.</p>
     */
    private static List<AssessmentNode> nestedInventory(double gap) {
        double leafW = 100;
        double leafH = 60;
        double titleBand = 20;
        double containerW = 3 * gap + 2 * leafW;
        double containerH = 2 * gap + leafH + titleBand;
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(group("band", 0, 0,
                3 * gap + 2 * containerW, 2 * gap + containerH + titleBand));
        for (int c = 0; c < 2; c++) {
            String containerId = "container-" + c;
            double containerX = gap + c * (containerW + gap);
            double containerY = gap + titleBand;
            nodes.add(new AssessmentNode(containerId, containerX, containerY, containerW, containerH,
                    "band", false, false, "Container " + c, 0.0, null, null, 0.0, 0.0, 0.0));
            for (int l = 0; l < 2; l++) {
                nodes.add(childNode(containerId + "-leaf-" + l,
                        containerX + gap + l * (leafW + gap), containerY + gap + titleBand,
                        leafW, leafH, containerId));
            }
        }
        return nodes;
    }

    @Test
    public void nestedInventoryFixture_isGeometricallyClean_atEveryGap() {
        // The fixture guards itself. If the derivation above ever drifts, this fails before the
        // measurements that depend on it start reporting numbers nobody can check.
        for (double gap : new double[]{8, 60}) {
            List<AssessmentNode> nodes = nestedInventory(gap);
            Map<String, AssessmentNode> byId = new LinkedHashMap<>();
            for (AssessmentNode n : nodes) {
                byId.put(n.id(), n);
            }
            for (AssessmentNode n : nodes) {
                if (n.parentId() == null) {
                    continue;
                }
                AssessmentNode p = byId.get(n.parentId());
                assertTrue("gap=" + gap + ": " + n.id() + " must sit inside " + p.id(),
                        n.x() >= p.x() && n.y() >= p.y()
                                && n.x() + n.width() <= p.x() + p.width()
                                && n.y() + n.height() <= p.y() + p.height());
            }
        }
    }

    @Test
    public void nestedInventory_withGenerousSiblingGaps_isNotCappedOnSpacing() {
        // The shape a recent full end-to-end run actually produced: three levels of nesting, no
        // connections, siblings spaced generously. If nesting inherently capped the rating, this
        // could not clear the excellent-spacing threshold. It does.
        LayoutAssessmentResult result = assessor.assess(nestedInventory(60), List.of(), false);

        // The fixture's every gap is 60, and containment is excluded at every ancestor level, so
        // the average is EXACTLY the gap. An approximate assertion here would have hidden the
        // broken-fixture defect that an earlier version of this test carried.
        assertEquals("the average must be exactly the sibling gap — nesting contributes nothing",
                60.0, result.averageSpacing(), 0.001);
        assertTrue("and that clears the excellent-spacing threshold",
                result.averageSpacing() > LayoutQualityAssessor.EXCELLENT_MIN_SPACING);
        assertEquals("a cleanly nested inventory is not capped on spacing",
                "pass", result.ratingBreakdown().get("spacing"));
        assertEquals("and the nesting itself is not counted as overlap", 0, result.overlapCount());
        assertTrue("the containment is seen — it is simply not charged for",
                result.containmentOverlapCount() > 0);
    }

    @Test
    public void nestedInventory_withTightSiblingGaps_readsFair_andThatIsTheMetricWorking() {
        // Identical nesting depth, identical structure, siblings packed. This DOES read fair — but
        // the variable that moved is sibling spacing, not nesting. A nesting-keyed carve-out would
        // excuse this layout, which a reader would call crowded.
        LayoutAssessmentResult result = assessor.assess(nestedInventory(8), List.of(), false);

        assertEquals("the average is exactly the tightened gap", 8.0,
                result.averageSpacing(), 0.001);
        assertEquals("tight siblings read fair at the same nesting depth",
                "fair", result.ratingBreakdown().get("spacing"));
    }

    @Test
    public void declaringContainment_raisesAverageSpacing_neverLowersIt() {
        // THE DECISIVE MEASUREMENT. The same rectangles, twice: once with the parent links declared
        // and once with every node reported as top-level. If nesting were what depressed the
        // metric, the declared-containment reading would be the worse of the two. It is the better
        // one, because every ancestor:descendant pair is excluded from the average — a container
        // and the child inside it are never measured against each other, at any depth.
        List<AssessmentNode> nested = nestedInventory(60);
        List<AssessmentNode> flattened = new ArrayList<>();
        for (AssessmentNode n : nested) {
            flattened.add(new AssessmentNode(n.id(), n.x(), n.y(), n.width(), n.height(),
                    null, n.isGroup(), n.isNote(), n.name(), n.labelTextWidth(),
                    null, null, 0.0, 0.0, 0.0));
        }

        double withContainment = assessor.assess(nested, List.of(), false).averageSpacing();
        double withoutContainment = assessor.assess(flattened, List.of(), false).averageSpacing();

        assertEquals("declared containment reads the true sibling gap", 60.0, withContainment, 0.001);
        assertEquals("undeclared, every container reads as a zero-distance overlap of its own "
                + "children", 0.0, withoutContainment, 0.001);
        assertTrue("declaring containment must not lower the average (declared="
                        + withContainment + ", undeclared=" + withoutContainment + ")",
                withContainment >= withoutContainment);
    }

    @Test
    public void deepeningTheNesting_doesNotDepressTheRating() {
        // Adding a level of containment over an already-clean layout changes the nesting depth and
        // nothing a reader would call crowding. The spacing verdict must not move.
        List<AssessmentNode> threeLevel = nestedInventory(60);
        List<AssessmentNode> fourLevel = new ArrayList<>(threeLevel);
        fourLevel.add(0, group("outer", -40, -40,
                threeLevel.get(0).width() + 80, threeLevel.get(0).height() + 80));
        fourLevel.set(1, new AssessmentNode("band", 0, 0,
                threeLevel.get(0).width(), threeLevel.get(0).height(),
                "outer", true, false, null, 0.0, null, null, 0.0, 0.0, 0.0));

        assertEquals("a fourth level of containment must not change the spacing verdict",
                assessor.assess(threeLevel, List.of(), false).ratingBreakdown().get("spacing"),
                assessor.assess(fourLevel, List.of(), false).ratingBreakdown().get("spacing"));
    }

    // ---------------------------------------------------------------------------------------
    // Cross-branch ("cousin") overlaps: what the same-parent exclusion in computeOverlaps does
    // and does not hide. These pin the exclusion's justification so it cannot be quietly
    // re-litigated: the claim that a cousin overlap is counted NOWHERE is false, and these
    // tests are what make that refutation executable rather than a paragraph.
    //
    // Fixtures here derive their rectangles from their variables and self-guard their own
    // containment before any count is read. The tests that establish the RULING — where a
    // cross-branch overlap does and does not show up — assert overlapCount, boundaryViolations
    // and containmentOverlapCount together, because asserting a single count in isolation is
    // precisely how a geometrically broken fixture once passed for the wrong reason. The later
    // tests target one named property each (the description cap, the coverage declaration, the
    // parent-kind wording) and assert that property; they are not making a claim about the other
    // counts and do not pretend to.
    // ---------------------------------------------------------------------------------------

    /** An element container (isGroup=false) that nonetheless holds children. */
    private static AssessmentNode elementContainer(String id, double x, double y,
                                                    double w, double h, String parentId) {
        return new AssessmentNode(id, x, y, w, h, parentId, false, false,
                id, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    /** Fails the test unless every node with a resolvable parent sits inside that parent. */
    private static void assertContainmentClean(String label, List<AssessmentNode> nodes) {
        Map<String, AssessmentNode> byId = new LinkedHashMap<>();
        for (AssessmentNode n : nodes) {
            byId.put(n.id(), n);
        }
        for (AssessmentNode c : nodes) {
            if (c.parentId() == null) {
                continue;
            }
            AssessmentNode p = byId.get(c.parentId());
            if (p == null) {
                continue;
            }
            assertTrue(label + ": " + c.id() + " must sit inside " + p.id(),
                    c.x() >= p.x() && c.y() >= p.y()
                            && c.x() + c.width() <= p.x() + p.width()
                            && c.y() + c.height() <= p.y() + p.height());
        }
    }

    /** Fails the test unless the two named nodes' rectangles genuinely intersect. */
    private static void assertRectanglesOverlap(String label, List<AssessmentNode> nodes,
                                                 String idA, String idB) {
        AssessmentNode a = null;
        AssessmentNode b = null;
        for (AssessmentNode n : nodes) {
            if (n.id().equals(idA)) {
                a = n;
            } else if (n.id().equals(idB)) {
                b = n;
            }
        }
        assertNotNull(label + ": fixture must contain " + idA, a);
        assertNotNull(label + ": fixture must contain " + idB, b);
        assertTrue(label + ": " + idA + " and " + idB + " must actually overlap",
                a.x() < b.x() + b.width() && a.x() + a.width() > b.x()
                        && a.y() < b.y() + b.height() && a.y() + a.height() > b.y());
    }

    /**
     * Two sibling containers side by side inside a band, where the FIRST container's second
     * leaf runs {@code escape} px past its own parent's right edge and into the neighbouring
     * container. This is the geometry of the nested-container fixture whose accidental
     * cross-branch overlap went unnoticed because a single count was asserted on it.
     */
    private static List<AssessmentNode> escapedLeafFixture(double gap, double escape) {
        double leafW = 100;
        double leafH = 60;
        double titleBand = 20;
        double containerW = 3 * gap + 2 * leafW;
        double containerH = 2 * gap + leafH + titleBand;
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(group("band", 0, 0, 3 * gap + 2 * containerW,
                2 * gap + containerH + titleBand));
        for (int c = 0; c < 2; c++) {
            String containerId = "container-" + c;
            double containerX = gap + c * (containerW + gap);
            double containerY = gap + titleBand;
            nodes.add(elementContainer(containerId, containerX, containerY,
                    containerW, containerH, "band"));
            for (int l = 0; l < 2; l++) {
                double leafX = containerX + gap + l * (leafW + gap);
                if (c == 0 && l == 1) {
                    leafX = containerX + containerW + escape - leafW;
                }
                nodes.add(childNode(containerId + "-leaf-" + l, leafX,
                        containerY + gap + titleBand, leafW, leafH, containerId));
            }
        }
        return nodes;
    }

    /**
     * Two top-level containers overlapping by {@code containerOverlap} px, each holding one leaf
     * fully inside itself, positioned so the two leaves meet inside the containers' intersection.
     * Containment is clean everywhere — this is the shape that decides whether a cousin overlap
     * can hide from every counter at once.
     */
    private static List<AssessmentNode> cousinCleanContainmentFixture(double containerOverlap) {
        double leafW = 100;
        double leafH = 60;
        double pad = 10;
        double titleBand = 20;
        double containerW = 2 * pad + leafW;
        double containerH = pad + titleBand + leafH + pad;
        double bX = containerW - containerOverlap;
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(group("cont-a", 0, 0, containerW, containerH));
        nodes.add(group("cont-b", bX, 0, containerW, containerH));
        // leaf-a hugs cont-a's right inner edge; leaf-b hugs cont-b's left inner edge.
        nodes.add(childNode("leaf-a", containerW - pad - leafW, pad + titleBand,
                leafW, leafH, "cont-a"));
        nodes.add(childNode("leaf-b", bX + pad, pad + titleBand, leafW, leafH, "cont-b"));
        return nodes;
    }

    @Test
    public void cousinOverlap_escapedChild_isCarriedByBoundaryViolationsNotOverlapCount() {
        double escape = 80;
        List<AssessmentNode> nodes = escapedLeafFixture(8, escape);
        // The escaped leaf really does reach into the neighbouring container's box.
        assertRectanglesOverlap("escaped leaf", nodes, "container-0-leaf-1", "container-1");

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        assertEquals("a cross-branch overlap is NOT a same-parent overlap, so overlapCount is 0",
                0, r.overlapCount());
        assertTrue("but the escape that produced it IS reported, in the same no-cap severity band",
                r.boundaryViolations().size() > 0);
        // band:container ×2, band:leaf ×4, container:own-leaf ×4 = 10 ancestor-descendant pairs,
        // minus the escaped leaf which no longer overlaps its own container's box... it still
        // does (it starts inside), so all 10 stand. Derived, not copied off a previous run.
        int containers = 2;
        int leavesPerContainer = 2;
        int expectedContainment = containers                       // band : each container
                + containers * leavesPerContainer                  // band : each leaf
                + containers * leavesPerContainer;                 // container : its own leaves
        assertEquals("ancestor-descendant overlaps stay in their own informational bucket",
                expectedContainment, r.containmentOverlapCount());
        assertEquals("and the pair the reader sees is named by the cross-branch metric",
                2, r.cousinOverlapCount());
        // The two together are the whole point: the view is still driven to poor.
        assertEquals("boundaryViolations drives the layout tier regardless of overlapCount",
                "poor", r.ratingBreakdown().get("boundaryViolations"));
    }

    @Test
    public void cousinOverlap_withCleanContainment_alwaysCoOccursWithACountedSiblingOverlap() {
        // The decisive case. If a cousin overlap could exist with boundaryViolations == 0 AND
        // overlapCount == 0, the exclusion would be hiding a defect outright. It cannot: when
        // every child sits inside its parent, two overlapping cousins force their containers to
        // overlap too, and containers that share a parent ARE siblings.
        List<AssessmentNode> nodes = cousinCleanContainmentFixture(40);
        assertContainmentClean("cousin/clean", nodes);
        assertRectanglesOverlap("cousin/clean", nodes, "leaf-a", "leaf-b");

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        assertEquals("no child escaped its parent", 0, r.boundaryViolations().size());
        assertEquals("the containers overlap because their children do, and they are siblings",
                1, r.overlapCount());
        assertEquals("each leaf overlaps its own container", 2, r.containmentOverlapCount());
        // ...and this is the attribution cost the exclusion carries: the pair NAMED is the
        // container pair, never the leaves a reader actually sees colliding.
        assertEquals(1, r.overlaps().size());
        assertTrue("overlapCount names the ancestors, not the colliding pair",
                r.overlaps().get(0).contains("cont-a") && r.overlaps().get(0).contains("cont-b"));
        assertEquals("the colliding leaves, plus each leaf against the other's container",
                3, r.cousinOverlapCount());
    }

    @Test
    public void cousinOverlap_atThreeLevels_isLiftedToTheAncestorSiblingPair() {
        // Uncle/nephew: 'a' is a child of 'x'; 'b' is a grandchild of 'x' through 'y'. The lift
        // must fire on (a, y) — proving the ancestor lift is not an artefact of the top level.
        double leafW = 100;
        double leafH = 60;
        double pad = 10;
        double titleBand = 20;
        double overlap = 40;
        double yW = 2 * pad + leafW;
        double yH = pad + titleBand + leafH + pad;
        double xW = 3 * pad + leafW + yW;
        double xH = pad + titleBand + yH + pad;
        double yX = xW - pad - yW;
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(group("x", 0, 0, xW, xH));
        nodes.add(childGroup("y", yX, pad + titleBand, yW, yH, "x"));
        nodes.add(childNode("b", yX + pad, pad + 2 * titleBand, leafW, leafH, "y"));
        nodes.add(childNode("a", yX + overlap - leafW, pad + 2 * titleBand, leafW, leafH, "x"));
        assertContainmentClean("uncle/nephew", nodes);
        assertRectanglesOverlap("uncle/nephew", nodes, "a", "b");

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        assertEquals("no child escaped its parent", 0, r.boundaryViolations().size());
        assertEquals("the lift fires on (a, y), which share the parent x", 1, r.overlapCount());
        assertEquals(4, r.containmentOverlapCount());
        assertTrue("the counted pair is the uncle and the nephew's CONTAINER",
                r.overlaps().get(0).contains("'y'") && r.overlaps().get(0).contains("'a'"));
        assertEquals("while the uncle/nephew pair itself is what the cross-branch metric names",
                1, r.cousinOverlapCount());
    }

    @Test
    public void topLevelObjects_shareANullParent_andAreThereforeSiblings() {
        // Objects.equals(null, null) is true, so two parentless objects are siblings by the
        // same-parent test. Without this, no top-level overlap would ever be counted.
        double w = 100;
        double h = 60;
        double shift = w * 0.4; // < w, so the two boxes must intersect by construction
        List<AssessmentNode> nodes = List.of(
                node("top-a", 0, 0, w, h),
                node("top-b", shift, 0, w, h));
        assertRectanglesOverlap("top-level", nodes, "top-a", "top-b");

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        assertEquals("two parentless objects count as siblings", 1, r.overlapCount());
        assertEquals(0, r.boundaryViolations().size());
        assertEquals(0, r.containmentOverlapCount());
    }

    @Test
    public void ancestorDescendantOverlap_landsInContainmentAndNeverInOverlapCount() {
        // A child inside its parent overlaps it by design. That must never reach overlapCount.
        double childW = 100;
        double childH = 60;
        double pad = 20;
        double titleBand = 20;
        List<AssessmentNode> nodes = List.of(
                group("g", 0, 0, 2 * pad + childW, pad + titleBand + childH + pad),
                childNode("child", pad, pad + titleBand, childW, childH, "g"));
        assertContainmentClean("containment", nodes);
        assertRectanglesOverlap("containment", nodes, "g", "child");

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        assertEquals("containment is expected, not a layout problem", 0, r.overlapCount());
        assertEquals(1, r.containmentOverlapCount());
        assertEquals(0, r.boundaryViolations().size());
    }

    @Test
    public void danglingParentId_isTheOneShapeWhereOnlyTheCrossBranchMetricReportsTheOverlap() {
        // The single construction under which a cross-branch overlap escapes every RATED counter:
        // a parentId naming an object that is not in the node set. detectBoundaryViolations skips
        // an unresolvable parent, and the same-parent test sees two different parent ids — so
        // both no-cap counters read zero while the objects visibly overlap. The cross-branch
        // metric is the only thing left reporting it, which is exactly why it is worth having.
        //
        // This is reachable through this package-visible seam ONLY. The collector always sets
        // parentId to the id of an object it emits in the same pass, and its zero-bounds skip
        // prunes a child's whole subtree rather than orphaning it — so production cannot build
        // this shape. The test exists to bound the claim, not to report a live defect.
        List<AssessmentNode> nodes = List.of(
                new AssessmentNode("orphan", 0, 0, 100, 60, "ghost", false, false,
                        null, 0.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("other", 50, 0, 100, 60, "ghost-2", false, false,
                        null, 0.0, null, null, 0.0, 0.0, 0.0));
        assertRectanglesOverlap("dangling", nodes, "orphan", "other");

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        assertEquals("both rated counters are blind here", 0, r.overlapCount());
        assertEquals(0, r.boundaryViolations().size());
        assertEquals(0, r.containmentOverlapCount());
        assertEquals("only the cross-branch metric sees it", 1, r.cousinOverlapCount());
    }

    @Test
    public void adjacentContainers_withLeavesFlushToTheSharedEdge_produceNoOverlapOfAnyKind() {
        // computeOverlaps uses a strict (open) intersection while the boundary check uses closed
        // containment, so it is worth asking whether two children could strictly overlap while
        // their containers only touched. They cannot — a strict overlap of the children is an
        // open region necessarily shared by both containers.
        //
        // This fixture is built to put that on a knife edge rather than to assert it from a safe
        // distance: the leaves are flush to their containers' shared edge, so leaf-a ENDS at
        // exactly the x where leaf-b BEGINS. Nothing here overlaps under a strict test, and
        // everything here would overlap under a non-strict one — so if the predicate ever
        // loosened, every assertion below flips.
        double leafW = 100;
        double leafH = 60;
        double titleBand = 20;
        double containerW = leafW;          // no padding: the leaf spans its container's width
        double containerH = titleBand + leafH;
        List<AssessmentNode> nodes = List.of(
                group("cont-a", 0, 0, containerW, containerH),
                group("cont-b", containerW, 0, containerW, containerH),
                childNode("leaf-a", containerW - leafW, titleBand, leafW, leafH, "cont-a"),
                childNode("leaf-b", containerW, titleBand, leafW, leafH, "cont-b"));
        assertContainmentClean("flush", nodes);
        // Guard the knife edge itself: the two leaves must share an edge exactly.
        assertEquals("leaf-a must end exactly where leaf-b begins",
                nodes.get(2).x() + nodes.get(2).width(), nodes.get(3).x(), 0.0001);

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        assertEquals("touching containers do not overlap", 0, r.overlapCount());
        assertEquals("nothing escaped", 0, r.boundaryViolations().size());
        assertEquals("each leaf still sits inside its own container", 2,
                r.containmentOverlapCount());
        assertEquals("and a shared edge is not a cross-branch collision",
                0, r.cousinOverlapCount());
    }

    @Test
    public void boundaryViolationDescription_namesTheParentByWhatItActuallyIs() {
        // Element-to-element nesting has shipped, so a boundary violator's parent is not always a
        // group. Calling every parent a "group" misnames the container the reader has to go find.
        List<AssessmentNode> groupParent = List.of(
                group("g", 0, 0, 200, 140),
                childNode("escapee", 150, 40, 100, 60, "g"));
        List<AssessmentNode> elementParent = List.of(
                elementContainer("e", 0, 0, 200, 140, null),
                childNode("escapee", 150, 40, 100, 60, "e"));

        String groupDesc = assessor.detectBoundaryViolations(groupParent, false)
                .descriptions().get(0);
        String elementDesc = assessor.detectBoundaryViolations(elementParent, false)
                .descriptions().get(0);

        assertTrue("a group parent should still be called a group: " + groupDesc,
                groupDesc.contains("parent group 'g'"));
        assertTrue("an element container must not be called a group: " + elementDesc,
                elementDesc.contains("parent element 'e'"));
    }

    /** A group holding {@code escapees} children, every one of them hanging outside its right edge. */
    private static List<AssessmentNode> manyEscapeesFixture(int escapees) {
        double leafW = 40;
        double leafH = 20;
        double pad = 5;
        double groupW = 2 * pad + leafW;
        double groupH = 2 * pad + escapees * (leafH + pad);
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(group("g", 0, 0, groupW, groupH));
        for (int i = 0; i < escapees; i++) {
            // Each child starts inside and runs past the group's right edge by exactly leafW.
            nodes.add(childNode("escapee-" + i, groupW - leafW, pad + i * (leafH + pad),
                    2 * leafW, leafH, "g"));
        }
        return nodes;
    }

    @Test
    public void boundaryViolationCount_isTrueAndUncapped_whileDescriptionsStayCapped() {
        int escapees = 40;
        List<AssessmentNode> nodes = manyEscapeesFixture(escapees);

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        assertEquals("the count must state the real size of the problem",
                escapees, r.boundaryViolationCount());
        assertEquals("descriptions stay capped so the payload cannot blow up",
                10, r.boundaryViolations().size());
        assertTrue("the capped list understates the count — that is exactly why the count exists",
                r.boundaryViolations().size() < r.boundaryViolationCount());
        // The rating input is binary (>0 → poor), so switching the rating from the capped size to
        // the true count cannot move any rating. It DOES fix the number the user is told.
        assertEquals("poor", r.ratingBreakdown().get("boundaryViolations"));
        assertTrue("the suggestion must quote the true count, not the capped one",
                r.suggestions().stream().anyMatch(s -> s.contains(escapees
                        + " elements extending outside their parent containers")));
    }

    @Test
    public void cousinOverlapCount_namesTheActualCollidingPair_whichOverlapCountCannot() {
        List<AssessmentNode> nodes = cousinCleanContainmentFixture(40);
        assertContainmentClean("cousin/clean", nodes);

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        // overlapCount reports the containers; the cousin count reports the leaves the reader sees.
        assertTrue("overlapCount names the ancestors",
                r.overlaps().get(0).contains("cont-a") && r.overlaps().get(0).contains("cont-b"));
        assertEquals("cont-a/leaf-b, cont-b/leaf-a and leaf-a/leaf-b all cross a container boundary",
                3, r.cousinOverlapCount());
        assertEquals(3, r.cousinOverlaps().size());
        assertTrue("the colliding leaves must be named somewhere in the cousin descriptions",
                r.cousinOverlaps().stream().anyMatch(
                        d -> d.contains("'leaf-a'") && d.contains("'leaf-b'")));
    }

    @Test
    public void cousinOverlaps_areCappedLikeEveryOtherDescriptionList() {
        // Two rows of interleaved cousins produce far more than MAX_DESCRIPTIONS pairs.
        int perSide = 12;
        double w = 100;
        double h = 60;
        double step = 50;
        List<AssessmentNode> nodes = new ArrayList<>();
        nodes.add(group("left", 0, 0, perSide * step + w, h + 40));
        nodes.add(group("right", 0, 0, perSide * step + w, h + 40));
        for (int i = 0; i < perSide; i++) {
            nodes.add(childNode("l-" + i, i * step, 20, w, h, "left"));
            nodes.add(childNode("r-" + i, i * step, 20, w, h, "right"));
        }

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        assertTrue("the fixture must actually produce more pairs than the cap",
                r.cousinOverlapCount() > 10);
        assertEquals("descriptions capped, count uncapped", 10, r.cousinOverlaps().size());
    }

    @Test
    public void cousinOverlapCount_appearsInNoRatingBreakdownKeyAndInNoTier() {
        // Single-variable contrast. The two node sets are GEOMETRICALLY IDENTICAL — same
        // positions, same sizes, so spacing, alignment and every other measurement is the same.
        // The only thing that differs is the parent linkage, which decides whether the pair is
        // classified cross-branch or same-parent. A control that also moved the rectangles would
        // change spacing too and could not tell the two effects apart.
        AssessmentNode a1 = new AssessmentNode("a", 0, 0, 100, 60, "ghost", false, false,
                null, 0.0, null, null, 0.0, 0.0, 0.0);
        List<AssessmentNode> asCousins = List.of(a1,
                new AssessmentNode("b", 50, 0, 100, 60, "ghost-2", false, false,
                        null, 0.0, null, null, 0.0, 0.0, 0.0));
        List<AssessmentNode> asSiblings = List.of(a1,
                new AssessmentNode("b", 50, 0, 100, 60, "ghost", false, false,
                        null, 0.0, null, null, 0.0, 0.0, 0.0));

        LayoutAssessmentResult cousins = assessor.assess(asCousins, List.of(), false);
        LayoutAssessmentResult siblings = assessor.assess(asSiblings, List.of(), false);

        assertEquals("classified cross-branch", 1, cousins.cousinOverlapCount());
        assertEquals("same geometry, classified same-parent", 0, siblings.cousinOverlapCount());
        assertEquals(0, cousins.overlapCount());
        assertEquals(1, siblings.overlapCount());

        // No band of its own.
        for (String key : cousins.ratingBreakdown().keySet()) {
            assertFalse("no rating breakdown key may mention the cross-branch metric: " + key,
                    key.toLowerCase(java.util.Locale.ROOT).contains("cousin"));
        }
        assertEquals("the breakdown key set must not depend on the classification",
                siblings.ratingBreakdown().keySet(), cousins.ratingBreakdown().keySet());

        // Not folded into the overlap band either: if it were, identical geometry would rate the
        // same both ways. It must not — the cross-branch case is clean on overlaps.
        assertEquals("pass", cousins.ratingBreakdown().get("overlaps"));
        assertEquals("poor", siblings.ratingBreakdown().get("overlaps"));
        assertFalse("a cross-branch overlap must not drive the layout tier the way a sibling does",
                cousins.layoutRating().equals(siblings.layoutRating()));
        assertEquals("and it must not touch the routing tier at all",
                siblings.routingRating(), cousins.routingRating());
    }

    @Test
    public void escapedChild_overlappingATopLevelObject_isCarriedByBoundaryViolationsToo() {
        // The sibling of the escaped-into-a-container case: the escapee lands on a TOP-LEVEL
        // object instead. Its parent is a group, the other object's parent is null, so the
        // same-parent test still drops the pair — and boundaryViolations still carries it.
        double leafW = 100;
        double leafH = 60;
        double gap = 8;
        double titleBand = 20;
        double escape = 80;
        double bandW = 2 * gap + leafW;
        double bandH = 2 * gap + leafH + titleBand;
        List<AssessmentNode> nodes = List.of(
                group("band", 0, 0, bandW, bandH),
                childNode("leaf", bandW + escape - leafW, gap + titleBand, leafW, leafH, "band"),
                node("top", bandW + gap, gap + titleBand, leafW, leafH));
        assertRectanglesOverlap("escaped vs top-level", nodes, "leaf", "top");

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), false);

        assertEquals("different parents (a group vs null) — not a same-parent overlap",
                0, r.overlapCount());
        assertEquals("the escape is reported", 1, r.boundaryViolations().size());
        assertEquals(1, r.containmentOverlapCount());
        assertEquals("and the colliding pair is named", 1, r.cousinOverlapCount());
    }

    @Test
    public void parentIdCycleThroughTheSeam_terminatesInsteadOfHanging() {
        // EMF containment is a tree, so production cannot build these. The seam can, and the
        // ancestor walkers used to have no visited set — a self-parent or a mutual pair spun
        // forever without allocating, which surfaces as a hung suite rather than a failure.
        List<AssessmentNode> selfParent = List.of(
                new AssessmentNode("a", 0, 0, 100, 60, "a", false, false,
                        null, 0.0, null, null, 0.0, 0.0, 0.0));
        List<AssessmentNode> mutual = List.of(
                new AssessmentNode("a", 0, 0, 100, 60, "b", false, false,
                        null, 0.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("b", 40, 0, 100, 60, "a", false, false,
                        null, 0.0, null, null, 0.0, 0.0, 0.0));

        assertNotNull("a self-parent must not hang the assessor",
                assessor.assess(selfParent, List.of(), false));
        assertNotNull("a mutual parent pair must not hang the assessor",
                assessor.assess(mutual, List.of(), false));
    }

    @Test
    public void cousinOverlaps_exposeViolatorIds_soTheyStayActionablePastTheDescriptionCap() {
        // The count is uncapped and the description list is not, so without a violator key a view
        // with many cross-branch pairs reports a number nobody can act on.
        List<AssessmentNode> nodes = cousinCleanContainmentFixture(40);

        LayoutAssessmentResult r = assessor.assess(nodes, List.of(), true);

        assertNotNull(r.violatorIds());
        Set<String> cousinViolators = new java.util.HashSet<>(
                r.violatorIds().getOrDefault("cousinOverlaps", Set.of()));
        assertTrue("the colliding leaves must both be listed",
                cousinViolators.contains("leaf-a") && cousinViolators.contains("leaf-b"));
    }

    @Test
    public void theSameParentRuleIsStatedCorrectlyAtEveryDocumentedSite() {
        // The tool description is pinned through the registered tool elsewhere. These three sites
        // are javadoc and an inline comment, which no runtime assertion can reach — so pin the
        // source text, or the corrected wording can rot back to the claim it replaced.
        String result = readProductionSource("model/LayoutAssessmentResult.java");
        String dto = readProductionSource("response/dto/AssessLayoutResultDto.java");
        String assessor = readProductionSource("model/LayoutQualityAssessor.java");

        assertFalse("the result javadoc must not describe overlapCount as covering "
                        + "'genuine layout problems'",
                result.contains("only sibling overlaps (genuine layout problems)"));
        assertTrue("the result javadoc must state the same-parent rule",
                result.contains("contains only SAME-PARENT overlaps"));
        assertFalse("the DTO javadoc must not repeat the old claim",
                dto.contains("only sibling overlaps (genuine layout problems)"));
        assertTrue("the DTO javadoc must state the same-parent rule",
                dto.contains("contains only SAME-PARENT overlaps"));

        assertFalse("the exclusion comment must no longer justify itself by boundary proximity "
                        + "alone",
                assessor.contains("they are cross-group boundary proximity"));
        assertTrue("the exclusion comment must record that the exclusion is deliberate",
                assessor.contains("excluded DELIBERATELY AND PERMANENTLY"));
        assertTrue("...and must record the ancestor-lift reason it is safe",
                assessor.contains("lowest common ancestor to overlap as well"));
        assertTrue("...and must name the attribution cost it carries",
                assessor.contains("What the exclusion does cost is ATTRIBUTION"));
    }

    @Test
    public void cousinOverlaps_areDeclaredInTheCoverageSpine() {
        // A metric that computes but never declares is a metric nobody can tell was checked.
        List<AssessmentNode> nodes = cousinCleanContainmentFixture(40);
        Map<String, String> coverage = assessor.assess(nodes, List.of(), false).coverage();

        assertTrue("the cross-branch dimension must declare itself",
                coverage.containsKey("cousinOverlaps"));
        assertEquals("checked", coverage.get("cousinOverlaps"));
    }
    // ---- The terminal verdict: what it claims, and what it declines to claim ----

    /** A run whose only peculiarity is an icon-bearing object whose title was never measured. */
    private static List<AssessmentNode> unmeasuredTitleRun() {
        return List.of(unmeasuredGlyphedCard(), node("a", 400, 0, 100, 50));
    }

    /** The same shape with the title MEASURED — single-variable control for the run above. */
    private static List<AssessmentNode> measuredTitleRun() {
        return List.of(glyphedCard(120, "top-right"), node("a", 400, 0, 100, 50));
    }

    /** A run carrying a parent, with children, whose title band width was never measured. */
    private static List<AssessmentNode> unmeasuredParentBandRun() {
        return List.of(namedGroup("g", 0, 0, 120, 80, LONG_TITLE, 0.0), childOf("g", 10, 25));
    }

    private static String verdictLine(LayoutAssessmentResult result) {
        return result.suggestions().stream()
                .filter(s -> s.contains("coverage dimensions were not fully examined"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected a scoped verdict in: " + result.suggestions()));
    }

    private static boolean namesDimension(LayoutAssessmentResult result, String dimension) {
        return result.suggestions().stream()
                .anyMatch(s -> s.contains("The " + dimension + " dimension could not be fully"
                        + " examined"));
    }

    private static int nonCheckedCount(Map<String, String> coverage) {
        int count = 0;
        for (String level : coverage.values()) {
            if (!LayoutQualityAssessor.COVERAGE_CHECKED.equals(level)) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void assess_terminalVerdict_shouldCountExactlyWhatItsOwnCoverageMapReports() {
        // The number in the prose is only trustworthy if it is arithmetic over the map the same
        // response publishes. Two runs whose counts DIFFER — a bare clean run and one carrying a
        // contextual downgrade — so a hard-coded constant cannot satisfy both.
        LayoutAssessmentResult bare = assessor.assess(
                List.of(node("a", 0, 0, 100, 50), node("b", 0, 200, 100, 50)), List.of(), false);
        LayoutAssessmentResult downgraded = assessor.assess(unmeasuredTitleRun(), List.of(), false);

        int bareCount = nonCheckedCount(bare.coverage());
        int downgradedCount = nonCheckedCount(downgraded.coverage());
        assertEquals("the two runs must differ, or this pin cannot discriminate",
                bareCount + 1, downgradedCount);

        assertTrue("the bare run must quote its own map (" + bareCount + "): " + verdictLine(bare),
                verdictLine(bare).contains(
                        bareCount + " of " + bare.coverage().size() + " coverage dimensions"));
        assertTrue("the downgraded run must quote its own map (" + downgradedCount + "): "
                        + verdictLine(downgraded),
                verdictLine(downgraded).contains(downgradedCount + " of "
                        + downgraded.coverage().size() + " coverage dimensions"));
    }

    @Test
    public void assess_terminalVerdict_shouldQuoteTheCoverageMapNotTheRatingBreakdown() {
        // The declaration is passed to the suggestion emitter as a purpose-built type precisely
        // because ratingBreakdown is the same raw Map type and is in scope at the call site. The
        // type makes that miswire a compile error; this pin makes it a test failure too, by
        // asserting the denominator is the coverage map's size and that the breakdown's size is a
        // DIFFERENT number, so a substituted map could not produce the same sentence.
        LayoutAssessmentResult result = assessor.assess(
                List.of(node("a", 0, 0, 100, 50), node("b", 0, 200, 100, 50)), List.of(), false);

        assertNotEquals("the two maps must differ in size, or this pin proves nothing",
                result.coverage().size(), result.ratingBreakdown().size());
        assertTrue("the denominator must be the coverage map's size: " + verdictLine(result),
                verdictLine(result).contains(" of " + result.coverage().size()
                        + " coverage dimensions"));
    }

    @Test
    public void coverage_labelOverlaps_contextualPartialIsNamedEvenBesideOtherFindings() {
        // This dimension downgrades on exactly the condition that also emits the short-segment
        // suggestion, so its suggestion list is NEVER empty when it is downgraded. A disclosure
        // confined to the empty-list case could not name it on any run at all — which is why the
        // naming is emitted independently of whether anything else was found.
        LayoutAssessmentResult result = assessor.assess(
                List.of(node("a", 0, 0, 100, 50), node("b", 400, 0, 100, 50)),
                labelExceedsSegmentConn("VeryLongLabelName"), false);

        assertEquals("precondition: the dimension really is downgraded on this run",
                LayoutQualityAssessor.COVERAGE_PARTIAL, result.coverage().get("labelOverlaps"));
        assertFalse("precondition: this run reports a defect, so the list is not empty",
                result.suggestions().isEmpty());
        assertTrue("the downgrade must be named beside the defect: " + result.suggestions(),
                namesDimension(result, "labelOverlaps"));
    }

    @Test
    public void coverage_ownIconOverLabel_contextualPartialIsNamedOnAnOtherwiseCleanRun() {
        // The reachability case: a genuinely clean view whose only fault is that one object was
        // never examined. Before this, the entire prose was an unqualified all-clear.
        LayoutAssessmentResult result = assessor.assess(unmeasuredTitleRun(), List.of(), false);

        assertEquals("precondition: downgraded", LayoutQualityAssessor.COVERAGE_PARTIAL,
                result.coverage().get("ownIconOverLabel"));
        assertEquals("precondition: and its count is honestly zero", 0,
                result.ownIconOverLabelCount());
        assertTrue("the unexamined dimension must be named: " + result.suggestions(),
                namesDimension(result, "ownIconOverLabel"));
        assertTrue("...with the reason it could not be certified: " + result.suggestions(),
                result.suggestions().stream().anyMatch(
                        t -> t.contains("title width could not be measured")));
    }

    @Test
    public void coverage_parentLabelObscured_contextualPartialIsNamed() {
        LayoutAssessmentResult result = assessor.assess(unmeasuredParentBandRun(), List.of(), false);

        assertEquals("precondition: downgraded", LayoutQualityAssessor.COVERAGE_PARTIAL,
                result.coverage().get("parentLabelObscured"));
        assertTrue("the unexamined dimension must be named: " + result.suggestions(),
                namesDimension(result, "parentLabelObscured"));
    }

    @Test
    public void coverage_terminalVerdict_shouldNameNoDimensionThatDidNotDowngrade() {
        // The negative half of the claim, and the one a positive-only suite cannot make: an
        // emitter that named all three unconditionally would pass every test above.
        LayoutAssessmentResult clean = assessor.assess(measuredTitleRun(), List.of(), false);

        assertNoContextualPartial(clean.coverage());
        for (String dimension : List.of("labelOverlaps", "ownIconOverLabel",
                "parentLabelObscured")) {
            assertFalse("nothing downgraded, so nothing may be named: " + dimension + " in "
                            + clean.suggestions(),
                    namesDimension(clean, dimension));
        }
    }

    @Test
    public void coverage_terminalVerdict_shouldNameOnlyTheDimensionThatActuallyDowngraded() {
        // Scoped naming: a run that downgrades one dimension must not name its two siblings. The
        // test above proves none is named when none fires; this proves the emitter discriminates
        // BETWEEN them rather than naming the whole contextual set whenever any of them fires.
        LayoutAssessmentResult result = assessor.assess(unmeasuredTitleRun(), List.of(), false);

        assertTrue(namesDimension(result, "ownIconOverLabel"));
        assertFalse("labelOverlaps did not downgrade on this run: " + result.suggestions(),
                namesDimension(result, "labelOverlaps"));
        assertFalse("parentLabelObscured did not downgrade on this run: " + result.suggestions(),
                namesDimension(result, "parentLabelObscured"));
    }

    @Test
    public void coverage_terminalVerdict_shouldNotNamePermanentlyPartialDimensions() {
        // Every fully-assessed run carries two permanent partials and one standing not-checked, by
        // construction and before anything about the view is considered. Naming those on every
        // response would be a constant wearing the clothes of news, and noise in a close-out
        // verdict is what stops the real entries being read. They are disclosed as a COUNT instead.
        LayoutAssessmentResult result = assessor.assess(
                List.of(node("a", 0, 0, 100, 50), node("b", 0, 200, 100, 50)), List.of(), false);

        assertEquals("precondition: these really are partial on this run",
                LayoutQualityAssessor.COVERAGE_PARTIAL, result.coverage().get("labelTruncations"));
        for (String permanent : List.of("labelTruncations", "edgeCoincidence",
                "corridorCentering")) {
            assertFalse("a permanent declaration must not be named as this run's news: " + permanent,
                    namesDimension(result, permanent));
        }
    }

    @Test
    public void coverage_contextualClassification_mustBeDerivedFromTheRegistry() {
        // The classification has one home. This drives each trigger in turn and asserts that the
        // dimension the REGISTRY marks with that trigger is exactly the one whose level moves —
        // so a dimension downgraded by the builder but unmarked in the registry, or marked but
        // never downgraded, fails here rather than drifting quietly.
        Map<LayoutQualityAssessor.ContextualTrigger, Map<String, String>> byTrigger = Map.of(
                LayoutQualityAssessor.ContextualTrigger.LABEL_EXCEEDS_SEGMENT,
                LayoutQualityAssessor.buildCoverageMap(true, false, false),
                LayoutQualityAssessor.ContextualTrigger.UNMEASURED_TITLE,
                LayoutQualityAssessor.buildCoverageMap(false, true, false),
                LayoutQualityAssessor.ContextualTrigger.UNMEASURED_PARENT_BAND,
                LayoutQualityAssessor.buildCoverageMap(false, false, true));
        Map<String, String> nothingFired = LayoutQualityAssessor.buildCoverageMap(false, false,
                false);

        for (Map.Entry<LayoutQualityAssessor.ContextualTrigger, Map<String, String>> fired
                : byTrigger.entrySet()) {
            for (LayoutQualityAssessor.CoverageDimension dim
                    : LayoutQualityAssessor.CoverageDimension.values()) {
                String level = fired.getValue().get(dim.id);
                if (dim.contextualTrigger == fired.getKey()) {
                    assertEquals("the registry marks " + dim.id + " with " + fired.getKey()
                                    + ", so firing it must downgrade exactly that dimension",
                            LayoutQualityAssessor.COVERAGE_PARTIAL, level);
                } else {
                    assertEquals("firing " + fired.getKey() + " must not move " + dim.id,
                            nothingFired.get(dim.id), level);
                }
            }
        }
    }

    @Test
    public void coverage_contextualClassification_everyMarkedDimensionDeclaresAReason() {
        // The prose is read from the trigger, so a marked dimension with no reason would emit a
        // truncated sentence rather than fail. NONE is the only trigger allowed to carry none.
        for (LayoutQualityAssessor.ContextualTrigger trigger
                : LayoutQualityAssessor.ContextualTrigger.values()) {
            if (trigger == LayoutQualityAssessor.ContextualTrigger.NONE) {
                assertNull("the no-op trigger has nothing to explain", trigger.reason);
            } else {
                assertNotNull("a downgrading trigger must explain itself: " + trigger,
                        trigger.reason);
                assertFalse("...with real prose: " + trigger, trigger.reason.isBlank());
            }
        }
    }

    @Test
    public void coverage_declaration_mustBeBuiltOnceSoTheMapAndTheProseMoveTogether() {
        // One source of truth. Flipping a run into a contextual downgrade must move the published
        // map entry AND the prose. A pin that passes when only one of them moves is not this pin,
        // so both directions are asserted on both runs.
        LayoutAssessmentResult measured = assessor.assess(measuredTitleRun(), List.of(), false);
        LayoutAssessmentResult unmeasured = assessor.assess(unmeasuredTitleRun(), List.of(), false);

        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED,
                measured.coverage().get("ownIconOverLabel"));
        assertFalse("the map says checked, so the prose must not name it",
                namesDimension(measured, "ownIconOverLabel"));

        assertEquals(LayoutQualityAssessor.COVERAGE_PARTIAL,
                unmeasured.coverage().get("ownIconOverLabel"));
        assertTrue("the map says partial, so the prose must name it",
                namesDimension(unmeasured, "ownIconOverLabel"));

        assertEquals("and the verdict's own count must move with the map",
                nonCheckedCount(unmeasured.coverage()),
                nonCheckedCount(measured.coverage()) + 1);
    }

    @Test
    public void coverage_terminalVerdict_mustNotMakeCoverageRatingBearing() {
        // Coverage is informational. The concern is not that the ratings were changed on purpose
        // but that a run carrying a downgrade could pick up a different rating by accident, so
        // each contextual trigger is fired in turn against its own single-variable control and
        // every published rating surface is compared.
        LayoutAssessmentResult measured = assessor.assess(measuredTitleRun(), List.of(), false);
        LayoutAssessmentResult unmeasured = assessor.assess(unmeasuredTitleRun(), List.of(), false);

        assertEquals("overall rating must not move with coverage",
                measured.overallRating(), unmeasured.overallRating());
        assertEquals("layout tier must not move with coverage",
                measured.layoutRating(), unmeasured.layoutRating());
        assertEquals("routing tier must not move with coverage",
                measured.routingRating(), unmeasured.routingRating());
        assertEquals("and the breakdown must be identical entry for entry",
                measured.ratingBreakdown(), unmeasured.ratingBreakdown());
    }

    @Test
    public void degenerate_terminalVerdict_mustNeverEmitTheFullyAssessedVerdict() {
        // The degenerate path abstains rather than certifying, and it did so already: its base line
        // declines to rate at all. The ruling on it is therefore "no change" — pinned so that
        // remains a decision rather than an omission. A one-object view CAN carry a partial, so
        // this fixture is the one that does.
        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(List.of(unmeasuredGlyphedCard()), List.of());

        assertEquals("precondition: this degenerate run really does carry a partial",
                LayoutQualityAssessor.COVERAGE_PARTIAL, result.coverage().get("ownIconOverLabel"));
        assertTrue("the abstention must survive: " + result.suggestions(),
                result.suggestions().stream().anyMatch(
                        s -> s.contains("layout assessment is not applicable")));
        for (String line : result.suggestions()) {
            assertFalse("the fully-assessed verdict is unreachable here: " + line,
                    line.contains("coverage dimensions were not fully examined"));
            assertFalse("and so is the retired all-clear: " + line,
                    line.contains("no immediate improvements needed"));
        }
    }

    @Test
    public void degenerate_contextualPartial_mustStillBeDerivableForTheWholeModelSweep() {
        // The degenerate PROSE is unchanged — it already abstains — but its coverage map is not
        // silent, and a whole-model sweep calls this path for every one-object view in the model.
        // If the derivation missed the degenerate map, such a view would report an empty
        // unexamined list beside a map that says otherwise, and the sweep would read its silence
        // as a clean result. That is the same false all-clear one surface along.
        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(List.of(unmeasuredGlyphedCard()), List.of());

        assertEquals("the degenerate map's contextual downgrade must be derivable",
                List.of("ownIconOverLabel"),
                LayoutQualityAssessor.contextualPartialDimensions(result.coverage()));
    }

    @Test
    public void degenerate_contextualPartial_mustBeEmptyWhenTheLoneObjectWasMeasured() {
        // Single-variable control for the test above.
        LayoutQualityAssessor.DegenerateAssessment result =
                assessor.assessDegenerate(List.of(glyphedCard(120, "top-right")), List.of());

        assertEquals("a measured lone object leaves nothing unexamined", List.of(),
                LayoutQualityAssessor.contextualPartialDimensions(result.coverage()));
    }

    @Test
    public void coverage_contextualPartialDimensions_mustBeEmptyForALegacyEmptyMap() {
        // The back-compat ladder declares no coverage at all. The derivation must return an empty
        // list there rather than throwing, and must not be read as "nothing was unexamined" —
        // which is why the DTO's own contract ties this field's emptiness to the map's.
        assertEquals(List.of(), LayoutQualityAssessor.contextualPartialDimensions(Map.of()));
        assertEquals(List.of(), LayoutQualityAssessor.contextualPartialDimensions(null));
    }

    @Test
    public void suggestions_mustNotPointAtACoverageMapWithoutSayingWhichToolPublishesIt() {
        // This list does not belong to assess-layout alone. The accessor republishes it into
        // auto-layout-and-route's quality-target summary and into adjust-view-spacing on three
        // paths, and NEITHER of those DTOs carries a coverage map. So a sentence here that says
        // "read the coverage map" sends the caller to a field that is not in front of them on
        // three of the four surfaces the sentence reaches. Naming the tool keeps the pointer
        // resolvable everywhere: a caller holding an adjust-view-spacing response can act on it.
        //
        // Checked across a clean run and each contextual downgrade, because the two sentences that
        // mention the map are emitted from different branches.
        List<List<AssessmentNode>> runs = List.of(
                List.of(node("a", 0, 0, 100, 50), node("b", 0, 200, 100, 50)),
                unmeasuredTitleRun(),
                unmeasuredParentBandRun());

        for (List<AssessmentNode> nodes : runs) {
            for (String suggestion : assessor.assess(nodes, List.of(), false).suggestions()) {
                if (suggestion.contains("coverage map")) {
                    assertTrue("a coverage-map reference must name the tool that publishes it,"
                                    + " because this list is republished by tools that carry no"
                                    + " coverage map: " + suggestion,
                            suggestion.contains("assess-layout's coverage map"));
                    // The tool name is lowercase, so naming it is one rewrite away from opening a
                    // sentence with a lowercase word. Quote the boundary: a pin that only checks
                    // the phrase is present cannot see the punctuation around it.
                    assertFalse("a sentence must not open with the lowercase tool name: "
                            + suggestion, suggestion.contains(". assess-layout"));
                }
            }
        }
    }

    @Test
    public void suggestions_everyNamedDimensionMustCarryItsReason() {
        // The naming sentence used to look its reason up by dimension id and fall back to an empty
        // clause when nothing matched, which produced a shorter but entirely plausible sentence —
        // a soft failure that reads as a complete result. The reason is now read straight off the
        // registry entry, so this asserts the clause is actually there on every named dimension.
        for (List<AssessmentNode> nodes : List.of(unmeasuredTitleRun(), unmeasuredParentBandRun())) {
            LayoutAssessmentResult result = assessor.assess(nodes, List.of(), false);
            // Scoped to the CONTEXTUAL set. Asserting merely that some value is "partial" would
            // be vacuous — two dimensions declare it permanently on every run, so that
            // precondition is satisfied by a run in which nothing was contextually downgraded and
            // the loop below would then inspect nothing.
            assertFalse("precondition: a contextual downgrade must actually have fired",
                    LayoutQualityAssessor.contextualPartialDimensions(result.coverage()).isEmpty());
            for (String suggestion : result.suggestions()) {
                if (suggestion.contains("could not be fully examined")) {
                    assertTrue("a named dimension must say WHY it could not be certified: "
                            + suggestion, suggestion.contains(" because "));
                    assertFalse("...and must not stop at the bare claim: " + suggestion,
                            suggestion.contains("could not be fully examined."));
                }
            }
        }
    }

}
