package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator.Face;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link EdgeAttachmentCalculator} (Story 10-16).
 * Pure-geometry tests — no OSGi runtime required.
 */
public class EdgeAttachmentCalculatorTest {

    private EdgeAttachmentCalculator calculator;

    @Before
    public void setUp() {
        calculator = new EdgeAttachmentCalculator();
    }

    // =============================================
    // Task 4.1: determineFace() for each cardinal direction
    // =============================================

    @Test
    public void shouldReturnTop_whenBendpointAboveElement() {
        // Element at (100, 100, 120, 80) → center (160, 140)
        // Bendpoint above at (160, 50)
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        Face face = calculator.determineFace(element, 160, 50);
        assertEquals(Face.TOP, face);
    }

    @Test
    public void shouldReturnBottom_whenBendpointBelowElement() {
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        Face face = calculator.determineFace(element, 160, 250);
        assertEquals(Face.BOTTOM, face);
    }

    @Test
    public void shouldReturnLeft_whenBendpointLeftOfElement() {
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        Face face = calculator.determineFace(element, 10, 140);
        assertEquals(Face.LEFT, face);
    }

    @Test
    public void shouldReturnRight_whenBendpointRightOfElement() {
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        Face face = calculator.determineFace(element, 300, 140);
        assertEquals(Face.RIGHT, face);
    }

    @Test
    public void shouldReturnVerticalFace_whenDiagonalTieBreak() {
        // When |dx| == |dy|, tie-breaks to vertical (TOP/BOTTOM)
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        // center (160, 140), bendpoint at (210, 190) → dx=50, dy=50 → tie → BOTTOM
        Face face = calculator.determineFace(element, 210, 190);
        assertEquals(Face.BOTTOM, face);
    }

    @Test
    public void shouldReturnBottom_whenBendpointAtElementCenter() {
        // Edge case: bendpoint at exact center (dx=0, dy=0) → tie-breaks to BOTTOM
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        Face face = calculator.determineFace(element, 160, 140); // exact center
        assertEquals(Face.BOTTOM, face);
    }

    @Test
    public void shouldReturnTop_whenBendpointDirectlyAbove() {
        // Bendpoint directly above center (same x)
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        Face face = calculator.determineFace(element, 160, 0);
        assertEquals(Face.TOP, face);
    }

    // =============================================
    // Task 4.2: computeAttachmentPoint() single connection → face midpoint
    // =============================================

    @Test
    public void shouldReturnBottomMidpoint_whenSingleConnectionOnBottomFace() {
        // Element at (100, 100, 120, 80) → bottom edge at y=180, 1px outside → y=181
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        int[] point = calculator.computeAttachmentPoint(element, Face.BOTTOM, 0, 1);
        assertEquals(160, point[0]); // midpoint x
        assertEquals(181, point[1]); // bottom edge y + 1px outside
    }

    @Test
    public void shouldReturnTopMidpoint_whenSingleConnectionOnTopFace() {
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        int[] point = calculator.computeAttachmentPoint(element, Face.TOP, 0, 1);
        assertEquals(160, point[0]); // midpoint x
        assertEquals(99, point[1]); // top edge y - 1px outside
    }

    @Test
    public void shouldReturnLeftMidpoint_whenSingleConnectionOnLeftFace() {
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        int[] point = calculator.computeAttachmentPoint(element, Face.LEFT, 0, 1);
        assertEquals(99, point[0]); // left edge x - 1px outside
        assertEquals(140, point[1]); // midpoint y
    }

    @Test
    public void shouldReturnRightMidpoint_whenSingleConnectionOnRightFace() {
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");
        int[] point = calculator.computeAttachmentPoint(element, Face.RIGHT, 0, 1);
        assertEquals(221, point[0]); // right edge x = 100 + 120 + 1px outside
        assertEquals(140, point[1]); // midpoint y
    }

    // =============================================
    // Task 4.3: computeAttachmentPoint() multiple connections → distributed
    // =============================================

    @Test
    public void shouldDistributeEvenly_whenMultipleConnectionsOnBottomFace() {
        // Element width 120, corner margin 5 → usable width 110, from x=105 to x=215
        // 3 connections → spacing = 110 / 4 = 27.5
        // Positions: 105 + 27.5 = 132.5 → 133, 105 + 55 = 160, 105 + 82.5 = 187.5 → 188
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");

        int[] p0 = calculator.computeAttachmentPoint(element, Face.BOTTOM, 0, 3);
        int[] p1 = calculator.computeAttachmentPoint(element, Face.BOTTOM, 1, 3);
        int[] p2 = calculator.computeAttachmentPoint(element, Face.BOTTOM, 2, 3);

        // All on bottom edge + 1px outside
        assertEquals(181, p0[1]);
        assertEquals(181, p1[1]);
        assertEquals(181, p2[1]);

        // Distributed left-to-right
        assertTrue("Points should be ordered left-to-right", p0[0] < p1[0]);
        assertTrue("Points should be ordered left-to-right", p1[0] < p2[0]);

        // Middle point should be at/near center
        assertEquals("Middle point should be near center", 160, p1[0]);

        // Near-symmetry: first and last roughly equidistant from center (±1 from rounding)
        assertTrue("First and last should be nearly equidistant from center",
                Math.abs((p2[0] - 160) - (160 - p0[0])) <= 1);
    }

    @Test
    public void shouldDistributeEvenly_whenMultipleConnectionsOnLeftFace() {
        // Element height 80, corner margin 5 → usable height 70, from y=105 to y=175
        // 2 connections → spacing = 70 / 3 ≈ 23.3
        // Positions: 105 + 23.3 = 128.3 → 128, 105 + 46.7 = 151.7 → 152
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");

        int[] p0 = calculator.computeAttachmentPoint(element, Face.LEFT, 0, 2);
        int[] p1 = calculator.computeAttachmentPoint(element, Face.LEFT, 1, 2);

        // Both on left edge - 1px outside
        assertEquals(99, p0[0]);
        assertEquals(99, p1[0]);

        // Distributed top-to-bottom
        assertTrue("Points should be ordered top-to-bottom", p0[1] < p1[1]);
    }

    // =============================================
    // Task 4.4: Minimum spacing when face is too narrow
    // =============================================

    @Test
    public void shouldHandleNarrowFace_whenManyConnectionsOnSmallElement() {
        // Very small element: 20px wide
        // Corner margin 5 → usable width 10
        // 5 connections with usable spacing = 10/6 ≈ 1.7 < minSpacing 8
        // neededWidth for minSpacing = 8 * 4 = 32 > 20 → can't enforce minSpacing
        // Falls back to full width: spacing = 20/6 ≈ 3.3
        RoutingRect smallElement = new RoutingRect(100, 100, 20, 80, "small");

        int[] p0 = calculator.computeAttachmentPoint(smallElement, Face.BOTTOM, 0, 5);
        int[] p4 = calculator.computeAttachmentPoint(smallElement, Face.BOTTOM, 4, 5);

        // Both should be within element bounds
        assertTrue("Attachment x should be >= element left", p0[0] >= 100);
        assertTrue("Attachment x should be <= element right", p4[0] <= 120);
        assertEquals("All on bottom edge + 1px", 181, p0[1]);
        assertEquals("All on bottom edge + 1px", 181, p4[1]);
    }

    @Test
    public void shouldEnforceMinSpacing_whenFaceIsWideEnough() {
        // Element width 120, 5 connections
        // Corner margin spacing = 110 / 6 ≈ 18.3 → above minSpacing 8, uses normal distribution
        // Now test with 15 connections: 110/16 ≈ 6.9 < minSpacing 8
        // neededWidth = 8 * 14 = 112 ≤ 120 → enforce minSpacing, centered
        // center = 100 + 60 = 160, groupStart = 160 - 56 = 104
        RoutingRect element = new RoutingRect(100, 100, 120, 80, "e1");

        int[] p0 = calculator.computeAttachmentPoint(element, Face.BOTTOM, 0, 15);
        int[] p1 = calculator.computeAttachmentPoint(element, Face.BOTTOM, 1, 15);

        int spacing = p1[0] - p0[0];
        assertEquals("Spacing should be minSpacing (8px)", 8, spacing);
    }

    @Test
    public void shouldFallbackToFullWidth_whenUsableLengthIsNegative() {
        // Element only 6px wide with corner margin 5 → usable = -4, fallback to full width
        RoutingRect tinyElement = new RoutingRect(100, 100, 6, 80, "tiny");

        int[] p0 = calculator.computeAttachmentPoint(tinyElement, Face.BOTTOM, 0, 2);
        int[] p1 = calculator.computeAttachmentPoint(tinyElement, Face.BOTTOM, 1, 2);

        // Should still produce valid points
        assertTrue("First point should be >= element left", p0[0] >= 100);
        assertTrue("Second point should be <= element right", p1[0] <= 106);
    }

    // =============================================
    // applyEdgeAttachments: integration tests
    // =============================================

    @Test
    public void shouldAddTerminalAndAlignmentBendpoints_whenConnectionHasIntermediateBendpoints() {
        // Source at (0, 170, 100, 60) center (50, 200)
        // Target at (400, 170, 100, 60) center (450, 200)
        // Two intermediate bendpoints above both elements
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // Source face = RIGHT (|dx|=150 > |dy|=100), terminal at (100, 200)
        // Alignment needed: Y 200≠100 → insert (200, 200) after source terminal
        // Target face = LEFT (|dx|=150 > |dy|=100), terminal at (400, 200)
        // Alignment needed: Y 200≠100 → insert (300, 200) before target terminal
        // Result: src(100,200), align(200,200), (200,100), (300,100), align(300,200), tgt(400,200)
        assertEquals(6, result.size());

        // First bendpoint should be on the source element edge
        AbsoluteBendpointDto sourceTerminal = result.get(0);
        assertTrue("Source terminal should be on source element edge",
                isOnElementEdge(sourceTerminal, source));

        // Last bendpoint should be on the target element edge
        AbsoluteBendpointDto targetTerminal = result.get(result.size() - 1);
        assertTrue("Target terminal should be on target element edge",
                isOnElementEdge(targetTerminal, target));

        // First segment (source terminal → alignment) should be perpendicular to RIGHT face (horizontal)
        AbsoluteBendpointDto srcAlignment = result.get(1);
        assertEquals("Source exit segment should be horizontal (same Y)",
                sourceTerminal.y(), srcAlignment.y());

        // Last segment (alignment → target terminal) should be perpendicular to LEFT face (horizontal)
        AbsoluteBendpointDto tgtAlignment = result.get(result.size() - 2);
        assertEquals("Target entry segment should be horizontal (same Y)",
                targetTerminal.y(), tgtAlignment.y());
    }

    @Test
    public void shouldAddTwoTerminalBendpoints_whenZeroBendpointConnection() {
        // Direct connection: no intermediate bendpoints
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>());

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        assertEquals("Zero-bendpoint connection should get 2 terminal bendpoints", 2, result.size());

        // Source terminal on right face, target terminal on left face (horizontal arrangement)
        AbsoluteBendpointDto srcTerm = result.get(0);
        AbsoluteBendpointDto tgtTerm = result.get(1);
        assertEquals("Source should exit from right face + 1px", 101, srcTerm.x()); // x=0+100+1
        assertEquals("Target should enter from left face - 1px", 399, tgtTerm.x()); // x=400-1
    }

    @Test
    public void shouldDistributeAttachments_whenMultipleConnectionsOnSameFace() {
        // Two connections arriving at the same target from the left
        RoutingRect src1 = new RoutingRect(0, 100, 80, 60, "s1");
        RoutingRect src2 = new RoutingRect(0, 300, 80, 60, "s2");
        RoutingRect target = new RoutingRect(400, 200, 120, 80, "tgt");

        List<String> ids = List.of("c1", "c2");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        // Both approach from the left
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(300, 220))));
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(300, 260))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", src1, target, List.of(), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c2", src2, target, List.of(), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        // Get target terminal bendpoints (last in each list)
        AbsoluteBendpointDto tgt1 = bendpointLists.get(0).get(bendpointLists.get(0).size() - 1);
        AbsoluteBendpointDto tgt2 = bendpointLists.get(1).get(bendpointLists.get(1).size() - 1);

        // Both on left face of target (x=400 - 1px outside = 399)
        assertEquals(399, tgt1.x());
        assertEquals(399, tgt2.x());

        // Different y positions (distributed)
        assertNotEquals("Attachment points should be distributed", tgt1.y(), tgt2.y());
    }

    @Test
    public void shouldPreserveIntermediateBendpoints_whenApplyingAttachments() {
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        AbsoluteBendpointDto intermediate1 = new AbsoluteBendpointDto(200, 100);
        AbsoluteBendpointDto intermediate2 = new AbsoluteBendpointDto(300, 100);
        bendpointLists.add(new ArrayList<>(List.of(intermediate1, intermediate2)));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // Intermediate bendpoints should be preserved (index may shift due to alignment points)
        boolean found1 = result.stream().anyMatch(bp -> bp.x() == 200 && bp.y() == 100);
        boolean found2 = result.stream().anyMatch(bp -> bp.x() == 300 && bp.y() == 100);
        assertTrue("Intermediate bendpoint (200,100) should be preserved", found1);
        assertTrue("Intermediate bendpoint (300,100) should be preserved", found2);
    }

    @Test
    public void shouldProducePerpendicularSegments_whenAlignmentNeeded() {
        // Source exits from RIGHT face, intermediate is above → alignment point ensures
        // horizontal exit segment (perpendicular to vertical right face)
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // Source terminal on RIGHT face at (100, 200)
        AbsoluteBendpointDto sourceTerminal = result.get(0);
        assertTrue("Source terminal should be on source edge",
                isOnElementEdge(sourceTerminal, source));

        // First segment: sourceTerminal → next point should be perpendicular to RIGHT face (horizontal = same Y)
        AbsoluteBendpointDto afterSource = result.get(1);
        assertEquals("First segment should be horizontal (same Y as source terminal)",
                sourceTerminal.y(), afterSource.y());

        // Target terminal on LEFT face at (400, 200)
        AbsoluteBendpointDto targetTerminal = result.get(result.size() - 1);
        assertTrue("Target terminal should be on target edge",
                isOnElementEdge(targetTerminal, target));

        // Last segment: preceding point → targetTerminal should be perpendicular to LEFT face (horizontal = same Y)
        AbsoluteBendpointDto beforeTarget = result.get(result.size() - 2);
        assertEquals("Last segment should be horizontal (same Y as target terminal)",
                targetTerminal.y(), beforeTarget.y());
    }

    // =============================================
    // Obstacle-aware alignment tests (Story 10-19b, updated 10-24)
    // =============================================

    @Test
    public void shouldUseAlternativeOffset_whenObstacleBlocksAlignmentSegment() {
        // Source at left, target at right, thin obstacle blocks original horizontal alignment
        // Source exits RIGHT face → alignment at Y=200 blocked → alternative offset clears it
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        // Thin obstacle at Y=199-201 blocks only the original alignment at Y=200
        RoutingRect obstacle = new RoutingRect(150, 199, 40, 2, "obs");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // With alternative offset: alignment should still be inserted (at shifted coordinate)
        // 6 bendpoints: src terminal, src alignment(shifted), bp1, bp2, tgt alignment, tgt terminal
        assertEquals("Alternative offset alignment should be inserted", 6, result.size());

        // Verify no segments pass through obstacle
        for (int i = 0; i < result.size() - 1; i++) {
            AbsoluteBendpointDto a = result.get(i);
            AbsoluteBendpointDto b = result.get(i + 1);
            assertFalse("Segment " + i + " should not pass through obstacle",
                    segmentIntersectsRect(a.x(), a.y(), b.x(), b.y(),
                            obstacle.x(), obstacle.y(), obstacle.width(), obstacle.height()));
        }
    }

    @Test
    public void shouldInsertAlignment_whenNoObstaclesNearAlignment() {
        // Same as existing test but with obstacles far away — alignment should still be inserted
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        // Obstacle far away — should not interfere
        RoutingRect farObstacle = new RoutingRect(600, 600, 40, 40, "far");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(farObstacle), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // With no nearby obstacles, alignments should be inserted normally
        // Result: src terminal, src alignment, bp1, bp2, tgt alignment, tgt terminal = 6
        assertEquals("Alignment bendpoints should be inserted when no obstacles nearby", 6, result.size());

        // Verify perpendicular segments exist
        AbsoluteBendpointDto srcTerminal = result.get(0);
        AbsoluteBendpointDto srcAlignment = result.get(1);
        assertEquals("Source exit should be horizontal (same Y)", srcTerminal.y(), srcAlignment.y());

        AbsoluteBendpointDto tgtTerminal = result.get(result.size() - 1);
        AbsoluteBendpointDto tgtAlignment = result.get(result.size() - 2);
        assertEquals("Target entry should be horizontal (same Y)", tgtTerminal.y(), tgtAlignment.y());
    }

    @Test
    public void shouldUseAlternativeSourceOffset_whenObstacleBlocksSourceSide() {
        // Thin obstacle near source alignment blocks only original Y=200
        // Target alignment unaffected (obstacle far from target side)
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        // Thin obstacle at Y=199-201, only on source side (x=150-190)
        RoutingRect obstacle = new RoutingRect(150, 199, 40, 2, "obs");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // Both source (shifted) AND target (original) alignments present = 6 bendpoints
        assertEquals("Both alignments should be present with alternative offset", 6, result.size());

        // Target perpendicular preserved (original offset, not shifted)
        AbsoluteBendpointDto tgtTerminal = result.get(result.size() - 1);
        AbsoluteBendpointDto tgtAlignment = result.get(result.size() - 2);
        assertEquals("Target entry should be horizontal", tgtTerminal.y(), tgtAlignment.y());

        // Verify no segments pass through obstacle
        for (int i = 0; i < result.size() - 1; i++) {
            AbsoluteBendpointDto a = result.get(i);
            AbsoluteBendpointDto b = result.get(i + 1);
            assertFalse("Segment " + i + " should not pass through obstacle",
                    segmentIntersectsRect(a.x(), a.y(), b.x(), b.y(),
                            obstacle.x(), obstacle.y(), obstacle.width(), obstacle.height()));
        }
    }

    @Test
    public void shouldUseAlternativeTargetOffset_whenObstacleBlocksTargetSide() {
        // Thin obstacle near target alignment blocks only original Y=200
        // Source alignment unaffected (obstacle far from source side)
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        // Thin obstacle at Y=199-201, only on target side (x=320-370)
        RoutingRect obstacle = new RoutingRect(320, 199, 50, 2, "obs");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // Both source (original) AND target (shifted) alignments present = 6 bendpoints
        assertEquals("Both alignments should be present with alternative offset", 6, result.size());

        // Source perpendicular preserved (original offset, not shifted)
        AbsoluteBendpointDto srcTerminal = result.get(0);
        AbsoluteBendpointDto srcAlignment = result.get(1);
        assertEquals("Source exit should be horizontal", srcTerminal.y(), srcAlignment.y());

        // Verify no segments pass through obstacle
        for (int i = 0; i < result.size() - 1; i++) {
            AbsoluteBendpointDto a = result.get(i);
            AbsoluteBendpointDto b = result.get(i + 1);
            assertFalse("Segment " + i + " should not pass through obstacle",
                    segmentIntersectsRect(a.x(), a.y(), b.x(), b.y(),
                            obstacle.x(), obstacle.y(), obstacle.width(), obstacle.height()));
        }
    }

    @Test
    public void shouldUseAlternativeOffset_whenVerticalConnectionWithHorizontalObstacle() {
        // Source above, target below — BOTTOM face → vertical perpendicular approach
        // Thin obstacle blocks original alignment at X=200, alternative X offset clears it
        RoutingRect source = new RoutingRect(170, 0, 60, 100, "src");
        RoutingRect target = new RoutingRect(170, 400, 60, 100, "tgt");
        // Thin obstacle at X=199-201, blocking original alignment X=200
        RoutingRect obstacle = new RoutingRect(199, 150, 2, 40, "obs");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        // Bendpoints to the left of source/target
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(100, 300))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // Alignment should be inserted at alternative X offset
        assertTrue("Should have alignment bendpoints inserted", result.size() >= 5);

        // Verify no segments pass through obstacle
        for (int i = 0; i < result.size() - 1; i++) {
            AbsoluteBendpointDto a = result.get(i);
            AbsoluteBendpointDto b = result.get(i + 1);
            assertFalse("Segment " + i + " should not pass through obstacle",
                    segmentIntersectsRect(a.x(), a.y(), b.x(), b.y(),
                            obstacle.x(), obstacle.y(), obstacle.width(), obstacle.height()));
        }
    }

    @Test
    public void shouldHandleObstacles_whenZeroBendpointConnection() {
        // Direct connection with obstacle — alignment for both source and target
        // Source at left, target at right, obstacle in between
        RoutingRect source = new RoutingRect(0, 100, 80, 60, "src");
        RoutingRect target = new RoutingRect(300, 200, 80, 60, "tgt");
        // Obstacle at center — would block alignment segments
        RoutingRect obstacle = new RoutingRect(150, 115, 40, 40, "obs");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>());

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // Should have at least 2 terminal bendpoints
        assertTrue("Should have at least terminal bendpoints", result.size() >= 2);
        // Verify no segments pass through obstacle
        for (int i = 0; i < result.size() - 1; i++) {
            AbsoluteBendpointDto a = result.get(i);
            AbsoluteBendpointDto b = result.get(i + 1);
            assertFalse("Segment " + i + " should not pass through obstacle",
                    segmentIntersectsRect(a.x(), a.y(), b.x(), b.y(),
                            obstacle.x(), obstacle.y(), obstacle.width(), obstacle.height()));
        }
    }

    // =============================================
    // New tests for alternative offset behavior (Story 10-24)
    // =============================================

    @Test
    public void shouldUseAlternativeOffset_whenOriginalAlignmentIsBlocked() {
        // Thin obstacle blocks original alignment at Y=200, shifted alignment clears it
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        // Thin obstacle at Y=199-201 blocking original alignment Y=200
        RoutingRect obstacle = new RoutingRect(120, 199, 60, 2, "obs");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // Source alignment present at alternative offset = 6 bendpoints
        assertEquals("Alignment should be inserted at alternative offset", 6, result.size());

        // Verify no segments pass through obstacle
        for (int i = 0; i < result.size() - 1; i++) {
            AbsoluteBendpointDto a = result.get(i);
            AbsoluteBendpointDto b = result.get(i + 1);
            assertFalse("Segment " + i + " should not pass through obstacle",
                    segmentIntersectsRect(a.x(), a.y(), b.x(), b.y(),
                            obstacle.x(), obstacle.y(), obstacle.width(), obstacle.height()));
        }

        // The source alignment Y should differ from original 200 (it was blocked)
        AbsoluteBendpointDto srcAlignment = result.get(1);
        assertNotEquals("Alignment should not be at original Y=200 (blocked)",
                200, srcAlignment.y());
    }

    @Test
    public void shouldSkipAlignment_whenAllAlternativeOffsetsBlocked() {
        // Large obstacle blocks all alternative offsets (±8 through ±32)
        // Verify fallback to skip behavior
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        // Very large obstacle covering Y=160-240 — blocks original (Y=200) and all ±32 offsets
        // Also wide enough (x=120-180) that diagonal terminal→alignment segments cross it
        RoutingRect obstacle = new RoutingRect(120, 160, 60, 80, "obs");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        // Source alignment skipped (all offsets blocked), target alignment inserted
        // Without source alignment: src terminal, bp1, bp2, tgt alignment, tgt terminal = 5
        assertEquals("Source alignment should be skipped when all offsets blocked", 5, result.size());

        // Target alignment should still be perpendicular (obstacle is far from target)
        AbsoluteBendpointDto tgtTerminal = result.get(result.size() - 1);
        AbsoluteBendpointDto tgtAlignment = result.get(result.size() - 2);
        assertEquals("Target entry should be horizontal", tgtTerminal.y(), tgtAlignment.y());
    }

    @Test
    public void shouldPreferSmallestOffset_whenMultipleAlternativesClear() {
        // Thin obstacle blocks original alignment but +8 offset clears it
        // Verify +8 is chosen (smallest displacement)
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        // Thin obstacle at Y=199-201 → blocks Y=200 but +8 (Y=208) clears it
        RoutingRect obstacle = new RoutingRect(120, 199, 60, 2, "obs");

        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        assertEquals("Alignment should be inserted at smallest offset", 6, result.size());

        // The alignment Y should be 208 (original 200 + first offset +8)
        // Note: terminal stays at Y=200, alignment shifts to Y=208 (near-perpendicular)
        AbsoluteBendpointDto srcAlignment = result.get(1);
        assertEquals("Should use +8 offset (smallest clear)", 208, srcAlignment.y());
    }

    // =============================================
    // Unified face groups (Story 10-28, AC #1)
    // =============================================

    @Test
    public void shouldUnifyFaceGroups_whenInboundAndOutboundShareSameFace() {
        // Element A has 2 outbound connections exiting RIGHT + 1 inbound entering RIGHT
        // Without unification: outbound totalOnFace=2, inbound totalOnFace=1 (overlap at midpoint)
        // With unification: totalOnFace=3 for all three connections on RIGHT face of A
        RoutingRect elementA = new RoutingRect(100, 100, 120, 80, "elemA"); // center (160, 140)
        RoutingRect elementB = new RoutingRect(400, 50, 100, 60, "elemB");  // right of A, above
        RoutingRect elementC = new RoutingRect(400, 150, 100, 60, "elemC"); // right of A, below
        RoutingRect elementD = new RoutingRect(400, 250, 100, 60, "elemD"); // right of A, far below

        // c1: A→B (exits A RIGHT face)
        // c2: A→C (exits A RIGHT face)
        // c3: D→A (enters A RIGHT face — approaching from the right)
        List<String> ids = List.of("c1", "c2", "c3");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        // c1: A→B, bendpoint to the right of A
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(300, 80))));
        // c2: A→C, bendpoint to the right of A
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(300, 180))));
        // c3: D→A, bendpoint to the right of A (entering from right side)
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(300, 140))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", elementA, elementB, List.of(), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c2", elementA, elementC, List.of(), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c3", elementD, elementA, List.of(), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        // Get the RIGHT face attachment points for element A:
        // c1 source terminal (first BP), c2 source terminal (first BP), c3 target terminal (last BP)
        AbsoluteBendpointDto c1SrcTerminal = bendpointLists.get(0).get(0);
        AbsoluteBendpointDto c2SrcTerminal = bendpointLists.get(1).get(0);
        AbsoluteBendpointDto c3TgtTerminal = bendpointLists.get(2).get(bendpointLists.get(2).size() - 1);

        // All three should be on the RIGHT face of element A (x = 100 + 120 + 1 = 221)
        assertEquals("c1 should exit RIGHT face of A", 221, c1SrcTerminal.x());
        assertEquals("c2 should exit RIGHT face of A", 221, c2SrcTerminal.x());
        assertEquals("c3 should enter RIGHT face of A", 221, c3TgtTerminal.x());

        // All three Y positions should be DIFFERENT (unified distribution with totalOnFace=3)
        assertTrue("c1 and c2 should have different Y (unified group)",
                c1SrcTerminal.y() != c2SrcTerminal.y());
        assertTrue("c1 and c3 should have different Y (unified group)",
                c1SrcTerminal.y() != c3TgtTerminal.y());
        assertTrue("c2 and c3 should have different Y (unified group)",
                c2SrcTerminal.y() != c3TgtTerminal.y());
    }

    @Test
    public void shouldDistributeThreeConnections_whenTwoOutboundOneInboundOnSameFace() {
        // Verify distribution positions with totalOnFace=3
        // Element A height 80, corner margin 5 → usable height 70
        // 3 connections → spacing = 70 / 4 = 17.5
        RoutingRect elementA = new RoutingRect(100, 100, 120, 80, "elemA");
        RoutingRect rightAbove = new RoutingRect(400, 50, 100, 60, "rAbove");
        RoutingRect rightBelow = new RoutingRect(400, 200, 100, 60, "rBelow");
        RoutingRect farRight = new RoutingRect(400, 120, 100, 60, "farR");

        List<String> ids = List.of("c1", "c2", "c3");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        // All approaching from right, sorted top-to-bottom
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(300, 80))));
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(300, 150))));
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(300, 230))));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", elementA, rightAbove, List.of(), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c2", farRight, elementA, List.of(), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c3", elementA, rightBelow, List.of(), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        // Extract RIGHT face Y values for element A
        int c1Y = bendpointLists.get(0).get(0).y();  // source terminal
        int c2Y = bendpointLists.get(1).get(bendpointLists.get(1).size() - 1).y();  // target terminal
        int c3Y = bendpointLists.get(2).get(0).y();  // source terminal

        // Should be ordered top-to-bottom with even spacing
        // Note: order depends on approach coordinate sorting, but all 3 should be distinct
        java.util.Set<Integer> uniqueYs = new java.util.HashSet<>();
        uniqueYs.add(c1Y);
        uniqueYs.add(c2Y);
        uniqueYs.add(c3Y);
        assertEquals("All three connections should have unique Y positions", 3, uniqueYs.size());

        // All should be within element A's Y range (100 to 180)
        for (int y : uniqueYs) {
            assertTrue("Y " + y + " should be within element A range",
                    y >= 100 && y <= 180);
        }
    }

    // =============================================
    // Helpers
    // =============================================

    /**
     * Simple Liang-Barsky segment-rectangle intersection for test assertions.
     */
    private boolean segmentIntersectsRect(int x1, int y1, int x2, int y2,
                                           int rx, int ry, int rw, int rh) {
        return EdgeAttachmentCalculator.lineSegmentIntersectsRect(
                x1, y1, x2, y2, rx, ry, rw, rh);
    }

    /**
     * Checks if a BP is 1px outside an element edge (the edge attachment offset).
     */
    private boolean isOnElementEdge(AbsoluteBendpointDto bp, RoutingRect rect) {
        int x = bp.x(), y = bp.y();
        int left = rect.x(), top = rect.y();
        int right = left + rect.width(), bottom = top + rect.height();

        boolean onTopEdge = y == top - 1 && x >= left && x <= right;
        boolean onBottomEdge = y == bottom + 1 && x >= left && x <= right;
        boolean onLeftEdge = x == left - 1 && y >= top && y <= bottom;
        boolean onRightEdge = x == right + 1 && y >= top && y <= bottom;

        return onTopEdge || onBottomEdge || onLeftEdge || onRightEdge;
    }
}
