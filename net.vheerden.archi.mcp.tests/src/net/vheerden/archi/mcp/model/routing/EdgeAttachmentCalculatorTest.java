package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public void shouldForceAlignment_whenAllAlternativeOffsetsBlocked() {
        // B28: Large obstacle blocks all alternative offsets (±8 through ±96)
        // Verify forced fallback inserts alignment at offset=0 instead of skipping
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        // Very large obstacle covering Y=160-240 — blocks original (Y=200) and all offsets
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
        // B28: Source alignment now force-inserted (not skipped)
        // With both alignments: src terminal, src alignment, bp1, bp2, tgt alignment, tgt terminal = 6
        assertEquals("Source alignment should be force-inserted when all offsets blocked", 6, result.size());

        // Source alignment should create perpendicular segment (same Y as terminal)
        AbsoluteBendpointDto srcTerminal = result.get(0);
        AbsoluteBendpointDto srcAlignment = result.get(1);
        assertEquals("Source exit should be horizontal (same Y)", srcTerminal.y(), srcAlignment.y());

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
    // Hub port distribution tests (Story backlog-b9)
    // =============================================

    @Test
    public void shouldRedistributeToAdjacentFaces_whenHubElementHasOverloadedFace() {
        // Hub element at center with 8 connections all approaching from below (BOTTOM face)
        // After redistribution, some should move to LEFT/RIGHT faces
        RoutingRect hub = new RoutingRect(200, 200, 120, 80, "hub");

        // 8 source elements below the hub, all approaching from roughly below
        List<String> ids = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> connections = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            String id = "c" + i;
            ids.add(id);
            // Sources spread below hub, some to the left and some to the right
            int srcX = 100 + i * 40;
            RoutingRect src = new RoutingRect(srcX, 400, 60, 40, "src" + i);
            // Bendpoints approaching from below
            bendpointLists.add(new ArrayList<>(List.of(
                    new AbsoluteBendpointDto(srcX + 30, 350))));
            connections.add(new RoutingPipeline.ConnectionEndpoints(
                    id, src, hub, List.of(), "", 1));
        }

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        // Count which faces the target terminals ended up on
        Map<String, Integer> faceCounts = new HashMap<>();
        for (int i = 0; i < 8; i++) {
            List<AbsoluteBendpointDto> bps = bendpointLists.get(i);
            AbsoluteBendpointDto terminal = bps.get(bps.size() - 1);
            String face = classifyFace(terminal, hub);
            faceCounts.merge(face, 1, Integer::sum);
        }

        // Should have connections on more than just BOTTOM face
        assertTrue("Hub redistribution should spread connections to multiple faces, got: " + faceCounts,
                faceCounts.size() > 1);

        // No single face should have more than ceil(8/2) = 4 connections
        for (Map.Entry<String, Integer> e : faceCounts.entrySet()) {
            assertTrue("Face " + e.getKey() + " has " + e.getValue() +
                    " connections, expected <= 4", e.getValue() <= 4);
        }
    }

    @Test
    public void shouldNotRedistribute_whenNonHubElement() {
        // Element with only 4 connections (below threshold of 6) — no redistribution
        RoutingRect element = new RoutingRect(200, 200, 120, 80, "elem");

        List<String> ids = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> connections = new ArrayList<>();

        // 4 connections all from below (BOTTOM face)
        for (int i = 0; i < 4; i++) {
            String id = "c" + i;
            ids.add(id);
            int srcX = 180 + i * 40;
            RoutingRect src = new RoutingRect(srcX, 400, 60, 40, "src" + i);
            bendpointLists.add(new ArrayList<>(List.of(
                    new AbsoluteBendpointDto(srcX + 30, 350))));
            connections.add(new RoutingPipeline.ConnectionEndpoints(
                    id, src, element, List.of(), "", 1));
        }

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        // All terminals should be on BOTTOM face (no redistribution)
        for (int i = 0; i < 4; i++) {
            List<AbsoluteBendpointDto> bps = bendpointLists.get(i);
            AbsoluteBendpointDto terminal = bps.get(bps.size() - 1);
            assertEquals("Non-hub element should keep all connections on BOTTOM",
                    "BOTTOM", classifyFace(terminal, element));
        }
    }

    @Test
    public void shouldNotRedistribute_whenHubElementIsBalanced() {
        // Hub element with 12 connections already balanced (3 per face) — no redistribution needed
        RoutingRect hub = new RoutingRect(200, 200, 120, 80, "hub");

        List<String> ids = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> connections = new ArrayList<>();

        // 3 from above (TOP), 3 from below (BOTTOM), 3 from left (LEFT), 3 from right (RIGHT)
        int[][] approaches = {
                {260, 50}, {240, 50}, {280, 50},       // TOP
                {260, 450}, {240, 450}, {280, 450},     // BOTTOM
                {50, 240}, {50, 220}, {50, 260},        // LEFT
                {450, 240}, {450, 220}, {450, 260}      // RIGHT
        };

        for (int i = 0; i < 12; i++) {
            String id = "c" + i;
            ids.add(id);
            RoutingRect src = new RoutingRect(
                    approaches[i][0] - 30, approaches[i][1] - 20, 60, 40, "src" + i);
            bendpointLists.add(new ArrayList<>(List.of(
                    new AbsoluteBendpointDto(approaches[i][0], approaches[i][1]))));
            connections.add(new RoutingPipeline.ConnectionEndpoints(
                    id, src, hub, List.of(), "", 1));
        }

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        // Count faces — should remain balanced (3 per face, no redistribution)
        Map<String, Integer> faceCounts = new HashMap<>();
        for (int i = 0; i < 12; i++) {
            List<AbsoluteBendpointDto> bps = bendpointLists.get(i);
            AbsoluteBendpointDto terminal = bps.get(bps.size() - 1);
            String face = classifyFace(terminal, hub);
            faceCounts.merge(face, 1, Integer::sum);
        }

        assertEquals("Should have connections on all 4 faces", 4, faceCounts.size());
        for (int count : faceCounts.values()) {
            assertEquals("Each face should have 3 connections when balanced", 3, count);
        }
    }

    @Test
    public void shouldProducePerpendicularTerminal_whenConnectionRedistributed() {
        // Hub with 8 connections from below, verify redistributed ones have perpendicular terminals
        RoutingRect hub = new RoutingRect(200, 200, 120, 80, "hub");

        List<String> ids = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> connections = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            String id = "c" + i;
            ids.add(id);
            int srcX = 100 + i * 40;
            RoutingRect src = new RoutingRect(srcX, 400, 60, 40, "src" + i);
            bendpointLists.add(new ArrayList<>(List.of(
                    new AbsoluteBendpointDto(srcX + 30, 350))));
            connections.add(new RoutingPipeline.ConnectionEndpoints(
                    id, src, hub, List.of(), "", 1));
        }

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        // Verify every connection's last segment is perpendicular to its terminal face
        for (int i = 0; i < 8; i++) {
            List<AbsoluteBendpointDto> bps = bendpointLists.get(i);
            AbsoluteBendpointDto terminal = bps.get(bps.size() - 1);
            AbsoluteBendpointDto penultimate = bps.get(bps.size() - 2);
            String face = classifyFace(terminal, hub);

            if ("LEFT".equals(face) || "RIGHT".equals(face)) {
                // Horizontal face → last segment should be horizontal (same Y)
                assertEquals("Last segment to " + face + " face should be horizontal (conn " + i + ")",
                        terminal.y(), penultimate.y());
            } else {
                // Vertical face → last segment should be vertical (same X)
                assertEquals("Last segment to " + face + " face should be vertical (conn " + i + ")",
                        terminal.x(), penultimate.x());
            }
        }
    }

    @Test
    public void shouldRedistributeFollowingQuadrantLogic_whenBottomOverloaded() {
        // Hub with connections from bottom-left and bottom-right quadrants
        // Bottom-left should go to LEFT, bottom-right should go to RIGHT
        EdgeAttachmentCalculator calc = new EdgeAttachmentCalculator(5, 8, 6);
        RoutingRect hub = new RoutingRect(200, 200, 120, 80, "hub");

        List<String> ids = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> connections = new ArrayList<>();

        // 6 connections from below — 3 from bottom-left, 3 from bottom-right
        int[][] srcPositions = {
                {100, 400}, {120, 400}, {140, 400},  // bottom-left quadrant
                {320, 400}, {340, 400}, {360, 400}   // bottom-right quadrant
        };

        for (int i = 0; i < 6; i++) {
            String id = "c" + i;
            ids.add(id);
            RoutingRect src = new RoutingRect(srcPositions[i][0], srcPositions[i][1], 60, 40, "src" + i);
            bendpointLists.add(new ArrayList<>(List.of(
                    new AbsoluteBendpointDto(srcPositions[i][0] + 30, 370))));
            connections.add(new RoutingPipeline.ConnectionEndpoints(
                    id, src, hub, List.of(), "", 1));
        }

        calc.applyEdgeAttachments(ids, bendpointLists, connections);

        // Check that redistribution happened and respects quadrant logic
        Map<String, Integer> faceCounts = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            List<AbsoluteBendpointDto> bps = bendpointLists.get(i);
            AbsoluteBendpointDto terminal = bps.get(bps.size() - 1);
            faceCounts.merge(classifyFace(terminal, hub), 1, Integer::sum);
        }

        // Should have redistribution to LEFT and/or RIGHT
        assertTrue("Should redistribute to multiple faces, got: " + faceCounts,
                faceCounts.size() > 1);
    }

    @Test
    public void shouldParticipateAtExactThreshold_whenElementHasSixConnections() {
        // Element with exactly 6 connections on BOTTOM face — should participate
        EdgeAttachmentCalculator calc = new EdgeAttachmentCalculator(5, 8, 6);
        RoutingRect hub = new RoutingRect(200, 200, 120, 80, "hub");

        List<String> ids = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> connections = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            String id = "c" + i;
            ids.add(id);
            int srcX = 120 + i * 40;
            RoutingRect src = new RoutingRect(srcX, 400, 60, 40, "src" + i);
            bendpointLists.add(new ArrayList<>(List.of(
                    new AbsoluteBendpointDto(srcX + 30, 350))));
            connections.add(new RoutingPipeline.ConnectionEndpoints(
                    id, src, hub, List.of(), "", 1));
        }

        calc.applyEdgeAttachments(ids, bendpointLists, connections);

        Map<String, Integer> faceCounts = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            List<AbsoluteBendpointDto> bps = bendpointLists.get(i);
            AbsoluteBendpointDto terminal = bps.get(bps.size() - 1);
            faceCounts.merge(classifyFace(terminal, hub), 1, Integer::sum);
        }

        // Should redistribute (6 >= threshold of 6, and all 6 on one face > 60%)
        assertTrue("Element at threshold should participate in redistribution, got: " + faceCounts,
                faceCounts.size() > 1);
    }

    @Test
    public void shouldNotParticipate_whenElementHasFiveConnections() {
        // Element with 5 connections (below threshold) — no redistribution
        EdgeAttachmentCalculator calc = new EdgeAttachmentCalculator(5, 8, 6);
        RoutingRect element = new RoutingRect(200, 200, 120, 80, "elem");

        List<String> ids = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> connections = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            String id = "c" + i;
            ids.add(id);
            int srcX = 160 + i * 30;
            RoutingRect src = new RoutingRect(srcX, 400, 60, 40, "src" + i);
            bendpointLists.add(new ArrayList<>(List.of(
                    new AbsoluteBendpointDto(srcX + 30, 350))));
            connections.add(new RoutingPipeline.ConnectionEndpoints(
                    id, src, element, List.of(), "", 1));
        }

        calc.applyEdgeAttachments(ids, bendpointLists, connections);

        // All should stay on BOTTOM
        for (int i = 0; i < 5; i++) {
            List<AbsoluteBendpointDto> bps = bendpointLists.get(i);
            AbsoluteBendpointDto terminal = bps.get(bps.size() - 1);
            assertEquals("Below-threshold element should not redistribute",
                    "BOTTOM", classifyFace(terminal, element));
        }
    }

    @Test
    public void shouldNotIntroduceObstacleViolations_whenRedistributing() {
        // Hub with obstacles — redistributed connections should not clip through them
        RoutingRect hub = new RoutingRect(200, 200, 120, 80, "hub");
        RoutingRect obstacle = new RoutingRect(150, 150, 30, 30, "obs");

        List<String> ids = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> connections = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            String id = "c" + i;
            ids.add(id);
            int srcX = 100 + i * 40;
            RoutingRect src = new RoutingRect(srcX, 400, 60, 40, "src" + i);
            bendpointLists.add(new ArrayList<>(List.of(
                    new AbsoluteBendpointDto(srcX + 30, 350))));
            connections.add(new RoutingPipeline.ConnectionEndpoints(
                    id, src, hub, List.of(obstacle), "", 1));
        }

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        // Verify all connections have valid bendpoints and no segments clip through the obstacle
        for (int i = 0; i < 8; i++) {
            List<AbsoluteBendpointDto> bps = bendpointLists.get(i);
            assertTrue("Connection " + i + " should have bendpoints", bps.size() >= 2);
            for (int j = 0; j < bps.size() - 1; j++) {
                AbsoluteBendpointDto a = bps.get(j);
                AbsoluteBendpointDto b = bps.get(j + 1);
                assertFalse("Connection " + i + " segment " + j +
                        " (" + a.x() + "," + a.y() + ")->(" + b.x() + "," + b.y() +
                        ") should not intersect obstacle",
                        segmentIntersectsRect(a.x(), a.y(), b.x(), b.y(),
                                obstacle.x(), obstacle.y(), obstacle.width(), obstacle.height()));
            }
        }
    }

    /**
     * Classifies which face a terminal bendpoint is on, based on its position
     * relative to the element (1px outside logic).
     */
    private String classifyFace(AbsoluteBendpointDto terminal, RoutingRect element) {
        int x = terminal.x(), y = terminal.y();
        int left = element.x(), top = element.y();
        int right = left + element.width(), bottom = top + element.height();

        if (y == bottom + 1) return "BOTTOM";
        if (y == top - 1) return "TOP";
        if (x == left - 1) return "LEFT";
        if (x == right + 1) return "RIGHT";

        // Fallback: closest face
        int dTop = Math.abs(y - (top - 1));
        int dBottom = Math.abs(y - (bottom + 1));
        int dLeft = Math.abs(x - (left - 1));
        int dRight = Math.abs(x - (right + 1));
        int min = Math.min(Math.min(dTop, dBottom), Math.min(dLeft, dRight));
        if (min == dBottom) return "BOTTOM";
        if (min == dTop) return "TOP";
        if (min == dLeft) return "LEFT";
        return "RIGHT";
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

    // =============================================
    // B28: Extended offset and forced fallback tests
    // =============================================

    @Test
    public void shouldUseExtendedOffset_whenStandardOffsetsBlocked() {
        // B28: Obstacle blocks ±32 offsets but ±48 clears it (extended range)
        // Source exits RIGHT face. Terminal at (101, 200). Alignment at (200, 200+offset).
        // Obstacle at x=140-190, y=183-217:
        //   - ±32: terminal→alignment diagonal crosses obstacle at x=140 where y≈187-213. BLOCKED.
        //   - ±48: terminal→alignment passes entirely above/below obstacle. CLEAR.
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(140, 183, 50, 34, "obs");

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
        // Both alignments should be inserted: src terminal, src alignment, bp1, bp2, tgt alignment, tgt terminal = 6
        assertEquals("Both alignments should be inserted", 6, result.size());

        // Source alignment should use an extended offset (not at original Y=200)
        AbsoluteBendpointDto srcAlignment = result.get(1);
        assertNotEquals("Source alignment should use extended offset, not original Y=200",
                200, srcAlignment.y());
    }

    // =============================================
    // B32: Natural approach direction correction tests
    // =============================================

    @Test
    public void shouldCorrectTargetFaceToTop_whenSourceNearlyAboveTarget() {
        // B32 AC-2: Source nearly above target (84px horizontal offset, 200px vertical separation)
        // Treasury Operations → Foreign Exchange Service scenario
        // determineFace() from a side-approach BP would pick LEFT/RIGHT,
        // but natural direction is TOP entry on target
        RoutingRect source = new RoutingRect(100, 0, 120, 80, "src");   // center (160, 40)
        RoutingRect target = new RoutingRect(16, 200, 120, 80, "tgt");  // center (76, 240)
        // dx=84, dy=200 → dy > 2*dx → nearly vertical

        Face[] sourceFaces = new Face[]{Face.RIGHT};  // simulating bad bendpoint-derived face
        Face[] targetFaces = new Face[]{Face.RIGHT};   // simulating edge-hugging approach

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("Target should enter from TOP (source is above)", Face.TOP, targetFaces[0]);
        assertEquals("Source should exit from BOTTOM (target is below)", Face.BOTTOM, sourceFaces[0]);
    }

    @Test
    public void shouldCorrectToHorizontalApproach_whenNearlyHorizontallyAligned() {
        // B32 AC-2: Source nearly to the left of target (large dx, small dy)
        RoutingRect source = new RoutingRect(0, 100, 120, 80, "src");   // center (60, 140)
        RoutingRect target = new RoutingRect(300, 60, 120, 80, "tgt");  // center (360, 100)
        // dx=300, dy=40 → dx > 2*dy → nearly horizontal

        Face[] sourceFaces = new Face[]{Face.TOP};    // wrong: should be RIGHT
        Face[] targetFaces = new Face[]{Face.BOTTOM}; // wrong: should be LEFT

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("Source should exit RIGHT (target is to the right)", Face.RIGHT, sourceFaces[0]);
        assertEquals("Target should enter LEFT (source is to the left)", Face.LEFT, targetFaces[0]);
    }

    @Test
    public void shouldNotCorrect_whenDiagonalAlignmentWithMatchingFaces() {
        // B32/B46: ratio 1.25:1 → B46 classifies as "nearly horizontal" but faces
        // already match natural direction (RIGHT/LEFT), so no correction needed
        RoutingRect source = new RoutingRect(0, 0, 120, 80, "src");     // center (60, 40)
        RoutingRect target = new RoutingRect(200, 160, 120, 80, "tgt"); // center (260, 200)
        // dx=200, dy=160 → 5*200=1000 > 6*160=960 → nearly horizontal
        // Natural: source RIGHT, target LEFT — already correct

        Face[] sourceFaces = new Face[]{Face.RIGHT};
        Face[] targetFaces = new Face[]{Face.LEFT};

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("Source face should be unchanged (already matches natural)", Face.RIGHT, sourceFaces[0]);
        assertEquals("Target face should be unchanged (already matches natural)", Face.LEFT, targetFaces[0]);
    }

    @Test
    public void shouldNotCorrect_whenLargeHorizontalOffset() {
        // B32 AC-7 test 4: Large horizontal offset → no false correction to vertical
        RoutingRect source = new RoutingRect(0, 0, 120, 80, "src");     // center (60, 40)
        RoutingRect target = new RoutingRect(400, 80, 120, 80, "tgt");  // center (460, 120)
        // dx=400, dy=80 → dx > 2*dy → nearly horizontal, not vertical

        Face[] sourceFaces = new Face[]{Face.RIGHT};
        Face[] targetFaces = new Face[]{Face.LEFT};

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        // Faces already match natural horizontal alignment — should remain unchanged
        assertEquals("Source face should remain RIGHT", Face.RIGHT, sourceFaces[0]);
        assertEquals("Target face should remain LEFT", Face.LEFT, targetFaces[0]);
    }

    @Test
    public void shouldNotCorrect_whenFaceAlreadyMatchesNaturalDirection() {
        // B32 AC-4: If face already correct, no change
        RoutingRect source = new RoutingRect(100, 0, 120, 80, "src");   // center (160, 40)
        RoutingRect target = new RoutingRect(50, 200, 120, 80, "tgt");  // center (110, 240)
        // dx=50, dy=200 → dy > 2*dx → nearly vertical

        Face[] sourceFaces = new Face[]{Face.BOTTOM}; // already correct
        Face[] targetFaces = new Face[]{Face.TOP};     // already correct

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("Source face should remain BOTTOM", Face.BOTTOM, sourceFaces[0]);
        assertEquals("Target face should remain TOP", Face.TOP, targetFaces[0]);
    }

    @Test
    public void shouldCorrectHubElement_whenStrongAlignment() {
        // B46: Hub elements ARE corrected when alignment is strong (2:1+)
        RoutingRect hub = new RoutingRect(100, 0, 120, 80, "hub");     // center (160, 40)
        RoutingRect target = new RoutingRect(50, 200, 120, 80, "tgt"); // center (110, 240)
        // dx=50, dy=200 → ratio 4:1 → strong vertical alignment

        // Create 6 connections to/from hub to make it a hub element
        List<String> ids = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> connections = new ArrayList<>();
        Face[] sourceFaces = new Face[6];
        Face[] targetFaces = new Face[6];

        // First connection: hub → target with "wrong" face
        ids.add("c0");
        connections.add(new RoutingPipeline.ConnectionEndpoints("c0", hub, target, List.of(), "", 1));
        sourceFaces[0] = Face.RIGHT; // contradicts natural (should be BOTTOM)
        targetFaces[0] = Face.RIGHT; // contradicts natural (should be TOP)

        // 5 more connections from hub to various elements
        for (int i = 1; i <= 5; i++) {
            RoutingRect other = new RoutingRect(300 + i * 50, 0, 60, 40, "o" + i);
            ids.add("c" + i);
            connections.add(new RoutingPipeline.ConnectionEndpoints("c" + i, hub, other, List.of(), "", 1));
            sourceFaces[i] = Face.RIGHT;
            targetFaces[i] = Face.LEFT;
        }

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        // B46: Hub source SHOULD be corrected for strong alignment (4:1 ratio)
        assertEquals("Hub source face SHOULD be corrected for strong alignment", Face.BOTTOM, sourceFaces[0]);
        // Target is NOT a hub (only 1 connection) — should be corrected
        assertEquals("Non-hub target face SHOULD be corrected", Face.TOP, targetFaces[0]);
    }

    @Test
    public void shouldCorrectBothSourceAndTarget_whenBothContradict() {
        // B32: Both source and target faces contradict natural vertical alignment
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");     // center (50, 30)
        RoutingRect target = new RoutingRect(30, 300, 100, 60, "tgt");  // center (80, 330)
        // dx=30, dy=300 → dy > 2*dx → nearly vertical, source above

        Face[] sourceFaces = new Face[]{Face.LEFT};   // contradicts: should be BOTTOM
        Face[] targetFaces = new Face[]{Face.RIGHT};  // contradicts: should be TOP

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("Source should exit BOTTOM", Face.BOTTOM, sourceFaces[0]);
        assertEquals("Target should enter TOP", Face.TOP, targetFaces[0]);
    }

    @Test
    public void shouldCorrectToBottom_whenSourceBelowTarget() {
        // B32: Source below target → target enters from BOTTOM
        RoutingRect source = new RoutingRect(50, 300, 100, 60, "src");  // center (100, 330)
        RoutingRect target = new RoutingRect(30, 0, 100, 60, "tgt");   // center (80, 30)
        // dx=20, dy=300 → dy > 2*dx → nearly vertical, source below

        Face[] sourceFaces = new Face[]{Face.RIGHT};  // contradicts: should be TOP
        Face[] targetFaces = new Face[]{Face.LEFT};   // contradicts: should be BOTTOM

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("Source should exit TOP (target is above)", Face.TOP, sourceFaces[0]);
        assertEquals("Target should enter BOTTOM (source is below)", Face.BOTTOM, targetFaces[0]);
    }

    @Test
    public void shouldIntegrateWithApplyEdgeAttachments_whenEdgeHuggingPath() {
        // B32 AC-3: Full integration test — edge-hugging path corrected to natural direction
        // Source nearly above target, A* path approaches from the side
        RoutingRect source = new RoutingRect(100, 0, 120, 80, "src");   // center (160, 40)
        RoutingRect target = new RoutingRect(16, 200, 120, 80, "tgt");  // center (76, 240)
        // dx=84, dy=200 → nearly vertical

        // A* path that approaches target from the right side (edge-hugging)
        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(160, 120),  // below source, directly beneath
                new AbsoluteBendpointDto(200, 240)   // to the RIGHT of target center (76,240)
        )));
        // Without B32, determineFace(target, 200, 240) → dx=124, dy=0 → RIGHT face (edge-hugging)
        // With B32, should be corrected to TOP face (natural vertical alignment)

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);

        // Target terminal should be on TOP face (y = target.y - 1 = 199)
        AbsoluteBendpointDto targetTerminal = result.get(result.size() - 1);
        assertEquals("Target terminal should be on TOP face (y = target.y - 1)",
                199, targetTerminal.y());
        // Target terminal X should be within target's horizontal bounds
        assertTrue("Target terminal X should be within target bounds",
                targetTerminal.x() >= target.x() && targetTerminal.x() <= target.x() + target.width());
    }

    @Test
    public void shouldCorrect_whenRatioJustAboveTwoToOne() {
        // B32: Boundary condition — dy = 2*dx + 1 → strictly greater, triggers correction
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");     // center (50, 30)
        RoutingRect target = new RoutingRect(50, 101, 100, 60, "tgt");  // center (100, 131)
        // dx=50, dy=101 → dy=101 > 2*50=100 → triggers

        Face[] sourceFaces = new Face[]{Face.RIGHT};
        Face[] targetFaces = new Face[]{Face.LEFT};

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("Should correct source to BOTTOM at just above 2:1", Face.BOTTOM, sourceFaces[0]);
        assertEquals("Should correct target to TOP at just above 2:1", Face.TOP, targetFaces[0]);
    }

    @Test
    public void shouldCorrect_whenRatioExactlyTwoToOne() {
        // B46: 2:1 ratio now triggers correction (threshold relaxed from 2:1 to 1.2:1)
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");     // center (50, 30)
        RoutingRect target = new RoutingRect(50, 100, 100, 60, "tgt");  // center (100, 130)
        // dx=50, dy=100 → 5*100=500 > 6*50=300 → nearly vertical, triggers B46

        Face[] sourceFaces = new Face[]{Face.RIGHT};
        Face[] targetFaces = new Face[]{Face.LEFT};

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("Should correct source to BOTTOM at 2:1 (above B46 threshold)", Face.BOTTOM, sourceFaces[0]);
        assertEquals("Should correct target to TOP at 2:1 (above B46 threshold)", Face.TOP, targetFaces[0]);
    }

    @Test
    public void shouldProduceCenterAlignedTerminal_afterFaceCorrection_forChopboxAnchorCompatibility() {
        // B32 AC-7 / Task 3.6: Verify B29 ChopboxAnchor alignment compatibility.
        // After B32 corrects face from RIGHT to TOP, the terminal BP must be center-aligned
        // on the corrected face — this is the precondition for alignTerminalsWithCenter() (B29).
        RoutingRect source = new RoutingRect(100, 0, 120, 80, "src");   // center (160, 40)
        RoutingRect target = new RoutingRect(16, 200, 120, 80, "tgt");  // center (76, 240)
        // dx=84, dy=200 → nearly vertical, B32 will correct target to TOP face

        // A* path approaching from the side (triggers B32 correction)
        List<String> ids = List.of("c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(160, 120),
                new AbsoluteBendpointDto(200, 240)
        )));

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.applyEdgeAttachments(ids, bendpointLists, connections);

        List<AbsoluteBendpointDto> result = bendpointLists.get(0);
        AbsoluteBendpointDto targetTerminal = result.get(result.size() - 1);

        // Terminal must be on TOP face (y = target.y - 1 = 199)
        assertEquals("Target terminal on TOP face", 199, targetTerminal.y());
        // Terminal X must be at target center (76) for ChopboxAnchor compatibility.
        // With a single connection on the face, computeAttachmentPoint places it at center.
        assertEquals("Target terminal X at center for ChopboxAnchor alignment",
                target.centerX(), targetTerminal.x());
    }

    // =============================================
    // B46: Diagonal-gap approach direction tests (1.2:1 to 2:1 range)
    // =============================================

    @Test
    public void shouldCorrectApproachDirection_whenDiagonalAlignment() {
        // B46 AC-1: Elements at ~1.5:1 ratio (was skipped by B32's 2:1 threshold)
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");     // center (50, 30)
        RoutingRect target = new RoutingRect(80, 120, 100, 60, "tgt");  // center (130, 150)
        // dx=80, dy=120 → ratio 1.5:1. 5*120=600 > 6*80=480 → nearly vertical

        Face[] sourceFaces = new Face[]{Face.RIGHT};  // contradicts: should be BOTTOM
        Face[] targetFaces = new Face[]{Face.LEFT};   // contradicts: should be TOP

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("B46 should correct source to BOTTOM at 1.5:1 ratio", Face.BOTTOM, sourceFaces[0]);
        assertEquals("B46 should correct target to TOP at 1.5:1 ratio", Face.TOP, targetFaces[0]);
    }

    @Test
    public void shouldCorrectApproachDirection_whenNearDiagonalAlignment() {
        // B46 AC-1: Elements at ~1.25:1 ratio (just above 1.2:1 threshold)
        // Note: 1:1 ratio (dx=100, dy=100) would NOT trigger — need >1.2:1
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");       // center (50, 30)
        RoutingRect target = new RoutingRect(100, 125, 100, 60, "tgt");   // center (150, 155)
        // dx=100, dy=125 → ratio 1.25:1. 5*125=625 > 6*100=600 → nearly vertical

        Face[] sourceFaces = new Face[]{Face.RIGHT};
        Face[] targetFaces = new Face[]{Face.LEFT};

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("B46 should correct source at 1.25:1 ratio", Face.BOTTOM, sourceFaces[0]);
        assertEquals("B46 should correct target at 1.25:1 ratio", Face.TOP, targetFaces[0]);
    }

    @Test
    public void shouldNotCorrect_whenRatioBelowB46Threshold() {
        // B46: Elements at exactly 1.2:1 ratio (boundary — NOT triggered, need strictly greater)
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");     // center (50, 30)
        RoutingRect target = new RoutingRect(100, 120, 100, 60, "tgt"); // center (150, 150)
        // dx=100, dy=120 → 5*120=600, 6*100=600 → NOT strictly greater → skip
        // Also: 5*100=500, 6*120=720 → NOT strictly greater → skip

        Face[] sourceFaces = new Face[]{Face.RIGHT};
        Face[] targetFaces = new Face[]{Face.LEFT};

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("Should NOT correct at exact 1.2:1 boundary", Face.RIGHT, sourceFaces[0]);
        assertEquals("Should NOT correct at exact 1.2:1 boundary", Face.LEFT, targetFaces[0]);
    }

    @Test
    public void shouldNotCorrectHubElement_whenDiagonalAlignmentWithHub() {
        // B46 AC-3: Hub elements still skipped even in the B46 diagonal range
        RoutingRect hub = new RoutingRect(0, 0, 100, 60, "hub");       // center (50, 30)
        RoutingRect target = new RoutingRect(80, 120, 100, 60, "tgt"); // center (130, 150)
        // dx=80, dy=120 → ratio 1.5:1 → B46 range

        List<String> ids = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> connections = new ArrayList<>();
        Face[] sourceFaces = new Face[6];
        Face[] targetFaces = new Face[6];

        // First connection: hub → target with contradicting face
        ids.add("c0");
        connections.add(new RoutingPipeline.ConnectionEndpoints("c0", hub, target, List.of(), "", 1));
        sourceFaces[0] = Face.RIGHT;  // contradicts: should be BOTTOM
        targetFaces[0] = Face.LEFT;   // contradicts: should be TOP

        // 5 more connections to make hub a hub element (≥6 total)
        for (int i = 1; i <= 5; i++) {
            RoutingRect other = new RoutingRect(300 + i * 50, 0, 60, 40, "o" + i);
            ids.add("c" + i);
            connections.add(new RoutingPipeline.ConnectionEndpoints("c" + i, hub, other, List.of(), "", 1));
            sourceFaces[i] = Face.RIGHT;
            targetFaces[i] = Face.LEFT;
        }

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        // Hub source should NOT be corrected
        assertEquals("Hub source face should NOT be corrected in B46 range", Face.RIGHT, sourceFaces[0]);
        // Non-hub target SHOULD be corrected
        assertEquals("Non-hub target face SHOULD be corrected in B46 range", Face.TOP, targetFaces[0]);
    }

    @Test
    public void shouldNotCorrect_whenPerfectDiagonal() {
        // B46: Elements at exactly 1:1 ratio (perfect diagonal) — no correction
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");     // center (50, 30)
        RoutingRect target = new RoutingRect(150, 150, 100, 60, "tgt"); // center (200, 180)
        // dx=150, dy=150 → 5*150=750, 6*150=900 → 750 > 900? No. Neither axis dominates.

        Face[] sourceFaces = new Face[]{Face.RIGHT};
        Face[] targetFaces = new Face[]{Face.LEFT};

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("Should NOT correct at 1:1 (perfect diagonal)", Face.RIGHT, sourceFaces[0]);
        assertEquals("Should NOT correct at 1:1 (perfect diagonal)", Face.LEFT, targetFaces[0]);
    }

    @Test
    public void shouldPreserveB32Behavior_whenStrongAlignment() {
        // B46 AC-2: Existing 2:1+ cases corrected identically to B32
        RoutingRect source = new RoutingRect(100, 0, 120, 80, "src");   // center (160, 40)
        RoutingRect target = new RoutingRect(16, 200, 120, 80, "tgt");  // center (76, 240)
        // dx=84, dy=200 → ratio 2.38:1 → well above 2:1, B32 range

        Face[] sourceFaces = new Face[]{Face.RIGHT};
        Face[] targetFaces = new Face[]{Face.RIGHT};

        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        calculator.correctApproachDirection(ids, connections, sourceFaces, targetFaces);

        assertEquals("B32 strong alignment: source corrected to BOTTOM", Face.BOTTOM, sourceFaces[0]);
        assertEquals("B32 strong alignment: target corrected to TOP", Face.TOP, targetFaces[0]);
    }

    // =============================================
    // B35 Phase A: Self-element pass-through face validation
    // =============================================

    @Test
    public void shouldDetectSelfSourcePassThrough_whenFaceCausesPathThroughElement() {
        // Source at (200,200,120,80), center (260,240), inset (205,205,110,70)
        // Target at (400,200,120,80) directly to the right
        // Single BP at (260,350) — directly below source
        // TOP face forces terminal at (260,199) → vertical segment down through source to BP
        RoutingRect source = new RoutingRect(200, 200, 120, 80, "s1");
        RoutingRect target = new RoutingRect(400, 200, 120, 80, "t1");
        List<AbsoluteBendpointDto> bps = List.of(new AbsoluteBendpointDto(260, 350));

        boolean hasPT = calculator.hasSelfPassThrough(bps, source, target,
                Face.TOP, Face.LEFT, true);
        assertTrue("TOP face should cause source self-element pass-through", hasPT);
    }

    @Test
    public void shouldDetectSelfTargetPassThrough_whenFaceCausesPathThroughElement() {
        // Source at (200,200,120,80), target at (200,400,120,80)
        // Target center (260,440), inset (205,405,110,70)
        // BP at (260,350) — between source and target
        // BOTTOM target face: terminal at (260,481) — segment from BP goes down through target
        RoutingRect source = new RoutingRect(200, 200, 120, 80, "s1");
        RoutingRect target = new RoutingRect(200, 400, 120, 80, "t1");
        List<AbsoluteBendpointDto> bps = List.of(new AbsoluteBendpointDto(260, 350));

        boolean hasPT = calculator.hasSelfPassThrough(bps, source, target,
                Face.BOTTOM, Face.BOTTOM, false);
        assertTrue("BOTTOM target face should cause target self-element pass-through", hasPT);
    }

    @Test
    public void shouldNotDetectPassThrough_whenFaceIsClean() {
        // Same geometry as source PT test, but with BOTTOM source face
        // BOTTOM terminal at (260,281) → segment goes down from 281 to 350, all below inset (275)
        RoutingRect source = new RoutingRect(200, 200, 120, 80, "s1");
        RoutingRect target = new RoutingRect(400, 200, 120, 80, "t1");
        List<AbsoluteBendpointDto> bps = List.of(new AbsoluteBendpointDto(260, 350));

        boolean hasPT = calculator.hasSelfPassThrough(bps, source, target,
                Face.BOTTOM, Face.LEFT, true);
        assertFalse("BOTTOM face should not cause source self-element pass-through", hasPT);
    }

    @Test
    public void shouldNotDetectPassThrough_whenElementTooSmallForInset() {
        // Element so small (8x8) that inset rect has zero/negative dimensions
        RoutingRect source = new RoutingRect(200, 200, 8, 8, "s1");
        RoutingRect target = new RoutingRect(400, 200, 120, 80, "t1");
        List<AbsoluteBendpointDto> bps = List.of(new AbsoluteBendpointDto(260, 350));

        boolean hasPT = calculator.hasSelfPassThrough(bps, source, target,
                Face.TOP, Face.LEFT, true);
        assertFalse("Small element with zero inset rect should return false", hasPT);
    }

    @Test
    public void shouldTryAlternativeFacesInAngularProximityOrder() {
        // Source center (260,240), other center (460,240) — other is directly RIGHT
        // Current face: TOP
        // Expected order: RIGHT (nearest to 0°), then BOTTOM/LEFT (further away)
        RoutingRect element = new RoutingRect(200, 200, 120, 80, "s1");
        RoutingRect other = new RoutingRect(400, 200, 120, 80, "t1");

        Face[] alternatives = calculator.getAlternativeFacesInAngularOrder(element, other, Face.TOP);

        assertEquals("Should return 3 alternatives", 3, alternatives.length);
        assertEquals("First alternative should be RIGHT (closest to target direction)",
                Face.RIGHT, alternatives[0]);
        // BOTTOM and LEFT are further — BOTTOM at π/2 from 0°, LEFT at π from 0°
        assertEquals("Second alternative should be BOTTOM", Face.BOTTOM, alternatives[1]);
        assertEquals("Third alternative should be LEFT (opposite direction)", Face.LEFT, alternatives[2]);
    }

    @Test
    public void shouldOrderAlternatives_whenTargetIsBelow() {
        // Other is directly below — target angle = π/2 (BOTTOM direction)
        // Current face: RIGHT
        // Expected order: BOTTOM (0 diff), then TOP/LEFT
        RoutingRect element = new RoutingRect(200, 200, 120, 80, "s1");
        RoutingRect other = new RoutingRect(200, 400, 120, 80, "t1");

        Face[] alternatives = calculator.getAlternativeFacesInAngularOrder(element, other, Face.RIGHT);

        assertEquals(Face.BOTTOM, alternatives[0]);
        // TOP and LEFT are both π from BOTTOM direction — order depends on exact angles
        // TOP at -π/2 → diff from π/2 = π
        // LEFT at π → diff from π/2 = π/2
        assertEquals("LEFT should be second (π/2 from BOTTOM)", Face.LEFT, alternatives[1]);
        assertEquals("TOP should be third (π from BOTTOM)", Face.TOP, alternatives[2]);
    }

    @Test
    public void shouldReassignSourceFace_whenInitialFaceCausesSelfPassThrough() {
        // Source at (200,200,120,80), target at (400,200,120,80)
        // BP at (260,350) — below source
        // Face determination from BP: dx=0, dy=110 → BOTTOM. But we test Phase A by
        // artificially using applyEdgeAttachments where the face detection naturally
        // gives TOP (by placing first BP above-right at a tie-break point)
        //
        // Direct test of validateFacesForSelfPassThrough:
        RoutingRect source = new RoutingRect(200, 200, 120, 80, "s1");
        RoutingRect target = new RoutingRect(400, 200, 120, 80, "t1");
        List<AbsoluteBendpointDto> bps = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(260, 350)));
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(bps);
        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        Face[] sourceFaces = {Face.TOP};
        Face[] targetFaces = {Face.LEFT};

        calculator.validateFacesForSelfPassThrough(ids, bendpointLists, connections,
                sourceFaces, targetFaces);

        // TOP causes PT, RIGHT causes PT, BOTTOM is clean
        assertNotEquals("Source face should be changed from TOP", Face.TOP, sourceFaces[0]);
        assertEquals("Source face should be changed to BOTTOM (first clean alternative)",
                Face.BOTTOM, sourceFaces[0]);
    }

    @Test
    public void shouldReassignTargetFace_whenInitialFaceCausesSelfPassThrough() {
        RoutingRect source = new RoutingRect(200, 200, 120, 80, "s1");
        RoutingRect target = new RoutingRect(200, 400, 120, 80, "t1");
        List<AbsoluteBendpointDto> bps = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(260, 350)));
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(bps);
        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        Face[] sourceFaces = {Face.BOTTOM};
        Face[] targetFaces = {Face.BOTTOM};

        calculator.validateFacesForSelfPassThrough(ids, bendpointLists, connections,
                sourceFaces, targetFaces);

        // BOTTOM target face causes PT (segment from BP to terminal crosses target)
        // TOP target face is clean (terminal at 399, segment from 350→399 all above inset)
        assertEquals("Target face should be changed to TOP (first clean alternative)",
                Face.TOP, targetFaces[0]);
    }

    @Test
    public void shouldKeepOriginalFace_whenAllAlternativesCausePassThrough() {
        // Geometry where ALL faces cause source PT:
        // Source at (200,200,120,80), intermediate segments form a diagonal through source
        // BPs: (350,210) and (100,280) — long diagonal from right to left through source area
        RoutingRect source = new RoutingRect(200, 200, 120, 80, "s1");
        RoutingRect target = new RoutingRect(50, 400, 120, 80, "t1");
        List<AbsoluteBendpointDto> bps = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(350, 210),
                new AbsoluteBendpointDto(100, 280)));
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(bps);
        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        Face[] sourceFaces = {Face.RIGHT};
        Face[] targetFaces = {Face.TOP};

        calculator.validateFacesForSelfPassThrough(ids, bendpointLists, connections,
                sourceFaces, targetFaces);

        // All faces should cause PT due to the diagonal BP→BP segment through source
        assertEquals("Source face should remain RIGHT when no alternative is clean",
                Face.RIGHT, sourceFaces[0]);
    }

    @Test
    public void shouldNotChangeFace_whenNoPassThroughExists() {
        // Clean geometry: source exits BOTTOM, BP directly below, no PT
        RoutingRect source = new RoutingRect(200, 200, 120, 80, "s1");
        RoutingRect target = new RoutingRect(200, 500, 120, 80, "t1");
        List<AbsoluteBendpointDto> bps = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(260, 400)));
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(bps);
        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), "", 1));

        Face[] sourceFaces = {Face.BOTTOM};
        Face[] targetFaces = {Face.TOP};

        calculator.validateFacesForSelfPassThrough(ids, bendpointLists, connections,
                sourceFaces, targetFaces);

        assertEquals("Source face should remain BOTTOM", Face.BOTTOM, sourceFaces[0]);
        assertEquals("Target face should remain TOP", Face.TOP, targetFaces[0]);
    }
}
