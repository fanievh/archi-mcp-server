package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;

/**
 * Tests for {@link MoveRecommendation} and {@link RoutingRecommendationEngine} (Stories 10-31, 10-33).
 */
public class RoutingRecommendationEngineTest {

    // --- MoveRecommendation record tests (Task 1.2) ---

    @Test
    public void shouldConstructMoveRecommendation_whenAllFieldsProvided() {
        MoveRecommendation rec = new MoveRecommendation("e1", "Element 1", 0, -85, "Move up", 2);
        assertEquals("e1", rec.elementId());
        assertEquals("Element 1", rec.elementName());
        assertEquals(0, rec.dx());
        assertEquals(-85, rec.dy());
        assertEquals("Move up", rec.reason());
        assertEquals(2, rec.connectionsUnblocked());
    }

    // --- RoutingRecommendationEngine tests (Task 2.7) ---

    @Test
    public void shouldReturnEmptyList_whenNoFailures() {
        RoutingRecommendationEngine engine = new RoutingRecommendationEngine();
        List<MoveRecommendation> result = engine.recommend(
                List.of(), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldReturnEmptyList_whenNullFailures() {
        RoutingRecommendationEngine engine = new RoutingRecommendationEngine();
        List<MoveRecommendation> result = engine.recommend(
                null, List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldRecommendSingleMove_whenOneBlockingObstacle() {
        // Source at (0,100), Target at (400,100) — horizontal line
        // Blocking obstacle at (180,60,40,80,"obs1") — straddles the line
        RoutingRect source = new RoutingRect(0, 80, 40, 40, "src");
        RoutingRect target = new RoutingRect(380, 80, 40, 40, "tgt");
        RoutingRect obstacle = new RoutingRect(180, 60, 40, 80, "obs1");

        RoutingPipeline.ConnectionEndpoints ep = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, List.of(obstacle), "", 0);

        FailedConnection fc = new FailedConnection("conn1", "src", "tgt", "element_crossing");

        RoutingRecommendationEngine engine = new RoutingRecommendationEngine();
        List<MoveRecommendation> recs = engine.recommend(
                List.of(fc), List.of(ep));

        assertEquals(1, recs.size());
        MoveRecommendation rec = recs.get(0);
        assertEquals("obs1", rec.elementId());
        assertEquals(0, rec.dx()); // Horizontal line → vertical move
        assertTrue("Should move vertically", rec.dy() != 0);
        assertEquals(1, rec.connectionsUnblocked());
        assertTrue(rec.reason().contains("1 connection"));
    }

    @Test
    public void shouldConsolidateRecommendations_whenSameObstacleBlocksMultipleConnections() {
        // Two connections, both blocked by the same obstacle
        RoutingRect source1 = new RoutingRect(0, 80, 40, 40, "src1");
        RoutingRect target1 = new RoutingRect(380, 80, 40, 40, "tgt1");
        RoutingRect source2 = new RoutingRect(0, 120, 40, 40, "src2");
        RoutingRect target2 = new RoutingRect(380, 120, 40, 40, "tgt2");
        RoutingRect obstacle = new RoutingRect(180, 60, 40, 120, "obs1");

        RoutingPipeline.ConnectionEndpoints ep1 = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source1, target1, List.of(obstacle), "", 0);
        RoutingPipeline.ConnectionEndpoints ep2 = new RoutingPipeline.ConnectionEndpoints(
                "conn2", source2, target2, List.of(obstacle), "", 0);

        FailedConnection fc1 = new FailedConnection("conn1", "src1", "tgt1", "element_crossing");
        FailedConnection fc2 = new FailedConnection("conn2", "src2", "tgt2", "element_crossing");

        RoutingRecommendationEngine engine = new RoutingRecommendationEngine();
        List<MoveRecommendation> recs = engine.recommend(
                List.of(fc1, fc2), List.of(ep1, ep2));

        // Should consolidate into single recommendation
        assertEquals(1, recs.size());
        assertEquals("obs1", recs.get(0).elementId());
        assertEquals(2, recs.get(0).connectionsUnblocked());
        assertTrue(recs.get(0).reason().contains("2 connections"));
    }

    @Test
    public void shouldReturnTopThree_whenMoreThanThreeBlockingElements() {
        // 4 different obstacles blocking connections with varying impact
        // All connections are horizontal at y=100 (center), obstacles positioned to straddle the line
        List<FailedConnection> failures = new ArrayList<>();
        List<RoutingPipeline.ConnectionEndpoints> endpoints = new ArrayList<>();

        // Obstacles at different x positions, all spanning y=60..140 (straddle y=100 line)
        RoutingRect obsA = new RoutingRect(100, 60, 40, 80, "obs1");
        RoutingRect obsB = new RoutingRect(200, 60, 40, 80, "obs2");
        RoutingRect obsC = new RoutingRect(300, 60, 40, 80, "obs3");
        RoutingRect obsD = new RoutingRect(400, 60, 40, 80, "obs4");

        // obs4 blocks 4 connections (highest impact)
        for (int i = 0; i < 4; i++) {
            String connId = "connD" + i;
            RoutingRect src = new RoutingRect(0, 80, 40, 40, "srcD" + i);
            RoutingRect tgt = new RoutingRect(500, 80, 40, 40, "tgtD" + i);
            endpoints.add(new RoutingPipeline.ConnectionEndpoints(
                    connId, src, tgt, List.of(obsD), "", 0));
            failures.add(new FailedConnection(connId, src.id(), tgt.id(), "element_crossing"));
        }
        // obs3 blocks 3 connections
        for (int i = 0; i < 3; i++) {
            String connId = "connC" + i;
            RoutingRect src = new RoutingRect(0, 80, 40, 40, "srcC" + i);
            RoutingRect tgt = new RoutingRect(500, 80, 40, 40, "tgtC" + i);
            endpoints.add(new RoutingPipeline.ConnectionEndpoints(
                    connId, src, tgt, List.of(obsC), "", 0));
            failures.add(new FailedConnection(connId, src.id(), tgt.id(), "element_crossing"));
        }
        // obs2 blocks 2 connections
        for (int i = 0; i < 2; i++) {
            String connId = "connB" + i;
            RoutingRect src = new RoutingRect(0, 80, 40, 40, "srcB" + i);
            RoutingRect tgt = new RoutingRect(500, 80, 40, 40, "tgtB" + i);
            endpoints.add(new RoutingPipeline.ConnectionEndpoints(
                    connId, src, tgt, List.of(obsB), "", 0));
            failures.add(new FailedConnection(connId, src.id(), tgt.id(), "element_crossing"));
        }
        // obs1 blocks 1 connection
        {
            String connId = "connA0";
            RoutingRect src = new RoutingRect(0, 80, 40, 40, "srcA0");
            RoutingRect tgt = new RoutingRect(500, 80, 40, 40, "tgtA0");
            endpoints.add(new RoutingPipeline.ConnectionEndpoints(
                    connId, src, tgt, List.of(obsA), "", 0));
            failures.add(new FailedConnection(connId, src.id(), tgt.id(), "element_crossing"));
        }

        RoutingRecommendationEngine engine = new RoutingRecommendationEngine();
        List<MoveRecommendation> recs = engine.recommend(
                failures, endpoints);

        assertEquals(3, recs.size());
        // Sorted by impact descending
        assertEquals(4, recs.get(0).connectionsUnblocked());
        assertEquals("obs4", recs.get(0).elementId());
        assertEquals(3, recs.get(1).connectionsUnblocked());
        assertEquals("obs3", recs.get(1).elementId());
        assertEquals(2, recs.get(2).connectionsUnblocked());
        assertEquals("obs2", recs.get(2).elementId());
    }

    @Test
    public void shouldReturnEmptyList_whenNoBlockingObstacleIdentified() {
        // Failed connection but obstacle list is empty (edge case — obstacle was excluded)
        RoutingRect source = new RoutingRect(0, 80, 40, 40, "src");
        RoutingRect target = new RoutingRect(380, 80, 40, 40, "tgt");

        RoutingPipeline.ConnectionEndpoints ep = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, List.of(), "", 0);

        FailedConnection fc = new FailedConnection("conn1", "src", "tgt", "element_crossing");

        RoutingRecommendationEngine engine = new RoutingRecommendationEngine();
        List<MoveRecommendation> recs = engine.recommend(
                List.of(fc), List.of(ep));

        assertTrue(recs.isEmpty());
    }

    @Test
    public void shouldRecommendHorizontalMove_whenVerticalLine() {
        // Source at (200,0), Target at (200,400) — vertical line
        // Blocking obstacle straddles the line
        RoutingRect source = new RoutingRect(180, 0, 40, 40, "src");
        RoutingRect target = new RoutingRect(180, 380, 40, 40, "tgt");
        RoutingRect obstacle = new RoutingRect(160, 180, 80, 40, "obs1");

        RoutingPipeline.ConnectionEndpoints ep = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, List.of(obstacle), "", 0);

        FailedConnection fc = new FailedConnection("conn1", "src", "tgt", "element_crossing");

        RoutingRecommendationEngine engine = new RoutingRecommendationEngine();
        List<MoveRecommendation> recs = engine.recommend(
                List.of(fc), List.of(ep));

        assertEquals(1, recs.size());
        MoveRecommendation rec = recs.get(0);
        assertTrue("Should move horizontally for vertical line", rec.dx() != 0);
        assertEquals(0, rec.dy());
    }

    @Test
    public void shouldRecommendVerticalMove_whenHorizontalLine() {
        // Source at (0,200), Target at (400,200) — horizontal line
        // Blocking obstacle straddles the line
        RoutingRect source = new RoutingRect(0, 180, 40, 40, "src");
        RoutingRect target = new RoutingRect(380, 180, 40, 40, "tgt");
        RoutingRect obstacle = new RoutingRect(180, 160, 40, 80, "obs1");

        RoutingPipeline.ConnectionEndpoints ep = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, List.of(obstacle), "", 0);

        FailedConnection fc = new FailedConnection("conn1", "src", "tgt", "element_crossing");

        RoutingRecommendationEngine engine = new RoutingRecommendationEngine();
        List<MoveRecommendation> recs = engine.recommend(
                List.of(fc), List.of(ep));

        assertEquals(1, recs.size());
        MoveRecommendation rec = recs.get(0);
        assertEquals(0, rec.dx());
        assertTrue("Should move vertically for horizontal line", rec.dy() != 0);
    }

    @Test
    public void shouldSkipFailedConnection_whenNoMatchingEndpoints() {
        // FailedConnection references a connectionId with no matching ConnectionEndpoints
        FailedConnection fc = new FailedConnection("unknown-conn", "src", "tgt", "element_crossing");

        RoutingRecommendationEngine engine = new RoutingRecommendationEngine();
        List<MoveRecommendation> recs = engine.recommend(
                List.of(fc), List.of());

        assertTrue(recs.isEmpty());
    }

    @Test
    public void shouldSkipObstaclesWithNullId() {
        RoutingRect source = new RoutingRect(0, 80, 40, 40, "src");
        RoutingRect target = new RoutingRect(380, 80, 40, 40, "tgt");
        RoutingRect obstacle = new RoutingRect(180, 60, 40, 80, null);

        RoutingPipeline.ConnectionEndpoints ep = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, List.of(obstacle), "", 0);

        FailedConnection fc = new FailedConnection("conn1", "src", "tgt", "element_crossing");

        RoutingRecommendationEngine engine = new RoutingRecommendationEngine();
        List<MoveRecommendation> recs = engine.recommend(
                List.of(fc), List.of(ep));

        assertTrue(recs.isEmpty());
    }

    @Test
    public void shouldComputeCorrectDisplacement_forHorizontalLine() {
        // Horizontal line from (20,100) to (380,100), obstacle at (180,60,40,80)
        // Obstacle center at (200,100) — on the line
        // Expected: vertical move, obs.height/2 + margin = 40 + 10 = 50
        int[] disp = RoutingRecommendationEngine.computeDisplacement(
                20, 100, 380, 100, new RoutingRect(180, 60, 40, 80, "obs"));
        assertEquals(0, disp[0]);
        assertEquals(50, Math.abs(disp[1])); // height/2 + margin = 40 + 10
    }

    @Test
    public void shouldComputeCorrectDisplacement_forVerticalLine() {
        // Vertical line from (200,20) to (200,380), obstacle at (160,180,80,40)
        // Obstacle center at (200,200) — on the line
        // Expected: horizontal move, obs.width/2 + margin = 40 + 10 = 50
        int[] disp = RoutingRecommendationEngine.computeDisplacement(
                200, 20, 200, 380, new RoutingRect(160, 180, 80, 40, "obs"));
        assertEquals(50, Math.abs(disp[0])); // width/2 + margin = 40 + 10
        assertEquals(0, disp[1]);
    }

    // --- Neighbor-aware recommendation tests (Story 10-33) ---

    @Test
    public void shouldClampRecommendation_whenDestinationOverlapsNeighbor() {
        // Horizontal line: source at left, target at right. Obstacle straddles the line.
        // A neighbor sits just below the obstacle — raw displacement (down) would overlap neighbor.
        RoutingRect source = new RoutingRect(0, 180, 40, 40, "src");
        RoutingRect target = new RoutingRect(400, 180, 40, 40, "tgt");
        RoutingRect obstacle = new RoutingRect(180, 160, 40, 80, "obs1"); // center at (200,200)
        // Neighbor 30px below obstacle bottom edge (160+80=240, neighbor at y=270, gap=30)
        // Raw displacement would be height/2 + margin = 40 + 10 = 50 (down)
        // Destination bottom = 160 + 50 + 80 = 290, which overlaps neighbor at 270 with 20px gap
        RoutingRect neighbor = new RoutingRect(170, 270, 60, 40, "neighbor1");

        RoutingPipeline.ConnectionEndpoints ep = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, List.of(obstacle), "", 0);
        FailedConnection fc = new FailedConnection("conn1", "src", "tgt", "element_crossing");

        List<RoutingRect> allElements = List.of(source, target, obstacle, neighbor);
        List<MoveRecommendation> recs = RoutingRecommendationEngine.recommend(
                List.of(fc), List.of(ep), allElements);

        assertEquals(1, recs.size());
        MoveRecommendation rec = recs.get(0);
        // Displacement should be clamped: neighbor.y - gap - (obs.y + obs.height) = 270 - 20 - 240 = 10
        assertEquals(0, rec.dx());
        assertTrue("Displacement should be positive (down)", rec.dy() > 0);
        // Destination bottom edge should not reach within 20px of neighbor top edge
        int destBottom = obstacle.y() + rec.dy() + obstacle.height();
        assertTrue("Should maintain gap from neighbor",
                destBottom <= neighbor.y() - RoutingRecommendationEngine.MIN_RECOMMENDATION_GAP);
    }

    @Test
    public void shouldOmitRecommendation_whenNoSafeSpaceExists() {
        // Obstacle wedged between two neighbors — no room to move in any direction
        RoutingRect source = new RoutingRect(0, 180, 40, 40, "src");
        RoutingRect target = new RoutingRect(400, 180, 40, 40, "tgt");
        // Obstacle at (180,160,40,80) — raw move would be down ~50px
        RoutingRect obstacle = new RoutingRect(180, 160, 40, 80, "obs1");
        // Neighbor immediately below (gap < MIN_RECOMMENDATION_GAP)
        RoutingRect neighborBelow = new RoutingRect(170, 245, 60, 40, "n1");

        RoutingPipeline.ConnectionEndpoints ep = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, List.of(obstacle), "", 0);
        FailedConnection fc = new FailedConnection("conn1", "src", "tgt", "element_crossing");

        List<RoutingRect> allElements = List.of(source, target, obstacle, neighborBelow);
        List<MoveRecommendation> recs = RoutingRecommendationEngine.recommend(
                List.of(fc), List.of(ep), allElements);

        // Clamped displacement should be 0 → recommendation omitted
        assertTrue("Should omit when no safe space", recs.isEmpty());
    }

    @Test
    public void shouldClampToCanvasBounds_whenMoveWouldGoNegative() {
        // Vertical line: source at top, target at bottom. Obstacle straddles the line.
        // Obstacle near left edge — raw displacement would push it to negative x.
        RoutingRect source = new RoutingRect(10, 0, 40, 40, "src");
        RoutingRect target = new RoutingRect(10, 400, 40, 40, "tgt");
        // Obstacle at x=5 — raw horizontal displacement left would be -(width/2 + margin) = -(20+10) = -30
        // That would put it at x = 5 + (-30) = -25, which is off-canvas
        RoutingRect obstacle = new RoutingRect(5, 180, 40, 40, "obs1");

        RoutingPipeline.ConnectionEndpoints ep = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, List.of(obstacle), "", 0);
        FailedConnection fc = new FailedConnection("conn1", "src", "tgt", "element_crossing");

        List<RoutingRect> allElements = List.of(source, target, obstacle);
        List<MoveRecommendation> recs = RoutingRecommendationEngine.recommend(
                List.of(fc), List.of(ep), allElements);

        // Raw displacement would be dx=-30 (left), clamped to dx=-5 (canvas bound at x=0)
        assertEquals("Should produce clamped recommendation", 1, recs.size());
        MoveRecommendation rec = recs.get(0);
        int destX = obstacle.x() + rec.dx();
        int destY = obstacle.y() + rec.dy();
        assertTrue("x should be >= 0 after canvas clamping", destX >= 0);
        assertTrue("y should be >= 0", destY >= 0);
        assertTrue("dx should be negative (moving left)", rec.dx() < 0);
        assertTrue("dx should be clamped (less magnitude than raw -30)",
                Math.abs(rec.dx()) < 30);
    }

    @Test
    public void shouldResolveInterRecommendationConflict_whenMovesOverlap() {
        // Test resolveInterRecommendationConflicts directly — bypasses neighbor collision
        // to isolate the conflict resolution logic.
        // obsA: (100,100,40,80) moves down 50 → dest (100,150,40,80)
        // obsB: (100,300,40,40) moves up 120 → dest (100,180,40,40)
        // Dest rects: obsA(100,150,40,80) bottom=230, obsB(100,180,40,40) top=180
        // With 20px gap, they overlap: 150+80+20=250 > 180
        RoutingRect obsA = new RoutingRect(100, 100, 40, 80, "obsA");
        RoutingRect obsB = new RoutingRect(100, 300, 40, 40, "obsB");

        java.util.Map<String, RoutingRecommendationEngine.BlockingInfo> blockingMap =
                new java.util.LinkedHashMap<>();
        RoutingRecommendationEngine.BlockingInfo infoA =
                new RoutingRecommendationEngine.BlockingInfo(obsA);
        infoA.addBlocking(0, 50, "src1", "tgt1");
        infoA.addBlocking(0, 50, "src2", "tgt2");
        blockingMap.put("obsA", infoA);

        RoutingRecommendationEngine.BlockingInfo infoB =
                new RoutingRecommendationEngine.BlockingInfo(obsB);
        infoB.addBlocking(0, -120, "src3", "tgt3");
        blockingMap.put("obsB", infoB);

        List<MoveRecommendation> recommendations = new ArrayList<>();
        recommendations.add(new MoveRecommendation("obsA", "obsA", 0, 50,
                "Move down 50px to clear corridor (blocks 2 connections)", 2));
        recommendations.add(new MoveRecommendation("obsB", "obsB", 0, -120,
                "Move up 120px to clear corridor (blocks 1 connection)", 1));

        RoutingRecommendationEngine.resolveInterRecommendationConflicts(
                recommendations, blockingMap, List.of(obsA, obsB));

        // obsA has higher impact (2 connections) — obsB should be removed
        assertEquals("Should remove lower-impact conflicting recommendation", 1, recommendations.size());
        assertEquals("obsA", recommendations.get(0).elementId());
        assertEquals(2, recommendations.get(0).connectionsUnblocked());
    }

    // --- Story 10-34: Visual-child containment skip ---

    @Test
    public void shouldSkipContainedChildren_inClampForNeighborCollisions() {
        // Parent element (220x110) with a contained child (190x45 at offset 15,45)
        // This reproduces the exact bug: Payment Engine with Payment Routing Function inside
        RoutingRect parent = new RoutingRect(590, 600, 220, 110, "parent");
        RoutingRect child = new RoutingRect(605, 645, 190, 45, "child"); // inside parent

        // Try to move parent right by 30px
        int[] result = RoutingRecommendationEngine.clampForNeighborCollisions(
                parent, 30, 0,
                List.of(parent, child),
                java.util.Set.of());

        // Child is geometrically contained within parent — should be skipped
        assertEquals("dx should not be clamped by contained child", 30, result[0]);
        assertEquals(0, result[1]);
    }

    @Test
    public void shouldGenerateRecommendation_whenOnlyChildWouldHaveBlocked() {
        // Full integration: source→target line crosses a parent element with a child inside.
        // Without the fix, the child clamps the recommendation to zero.
        // With the fix, the recommendation is generated.
        RoutingRect source = new RoutingRect(590, 760, 220, 110, "src"); // below parent
        RoutingRect target = new RoutingRect(450, 300, 220, 110, "tgt"); // above parent
        // Parent (blocker) with contained child
        RoutingRect parent = new RoutingRect(590, 600, 220, 110, "blocker");
        RoutingRect child = new RoutingRect(605, 645, 190, 45, "child");
        // A real neighbor to the right that should clamp but not kill the recommendation
        RoutingRect neighbor = new RoutingRect(860, 600, 220, 110, "neighbor");

        RoutingPipeline.ConnectionEndpoints ep = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, List.of(parent), "", 0);
        FailedConnection fc = new FailedConnection("conn1", "src", "tgt", "element_crossing");

        List<RoutingRect> allElements = List.of(source, target, parent, child, neighbor);
        List<MoveRecommendation> recs = RoutingRecommendationEngine.recommend(
                List.of(fc), List.of(ep), allElements);

        // Should produce a recommendation (clamped by neighbor but not killed by child)
        assertEquals("Should produce 1 recommendation", 1, recs.size());
        assertEquals("blocker", recs.get(0).elementId());
        assertTrue("dx should be positive (move right)", recs.get(0).dx() > 0);
    }

    @Test
    public void shouldStillClampForRealNeighbors_afterContainmentFix() {
        // Ensure the containment fix doesn't break real neighbor clamping.
        // Parent with a real neighbor next to it (not contained).
        RoutingRect parent = new RoutingRect(100, 100, 220, 110, "parent");
        RoutingRect realNeighbor = new RoutingRect(350, 100, 220, 110, "neighbor"); // to the right

        // Try to move parent right by 200px — should be clamped by real neighbor
        int[] result = RoutingRecommendationEngine.clampForNeighborCollisions(
                parent, 200, 0,
                List.of(parent, realNeighbor),
                java.util.Set.of());

        // maxDisplacement = 350 - 20 - (100 + 220) = 10
        assertTrue("dx should be clamped by real neighbor", result[0] < 200);
        assertTrue("dx should still be positive", result[0] > 0);
        assertEquals(10, result[0]);
    }

    @Test
    public void shouldPreserveExistingBehavior_whenNoNeighborsNearby() {
        // Same setup as shouldRecommendSingleMove_whenOneBlockingObstacle
        // but with allElements containing only source, target, obstacle (no nearby neighbors)
        RoutingRect source = new RoutingRect(0, 80, 40, 40, "src");
        RoutingRect target = new RoutingRect(380, 80, 40, 40, "tgt");
        RoutingRect obstacle = new RoutingRect(180, 60, 40, 80, "obs1");

        RoutingPipeline.ConnectionEndpoints ep = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, List.of(obstacle), "", 0);
        FailedConnection fc = new FailedConnection("conn1", "src", "tgt", "element_crossing");

        // Call with allElements — source/target are excluded as connection endpoints
        List<RoutingRect> allElements = List.of(source, target, obstacle);
        List<MoveRecommendation> recsWithNeighbors = RoutingRecommendationEngine.recommend(
                List.of(fc), List.of(ep), allElements);

        // Call without allElements (legacy)
        List<MoveRecommendation> recsLegacy = RoutingRecommendationEngine.recommend(
                List.of(fc), List.of(ep));

        // Both should produce the same recommendation
        assertEquals(recsLegacy.size(), recsWithNeighbors.size());
        assertEquals(1, recsWithNeighbors.size());
        assertEquals(recsLegacy.get(0).dx(), recsWithNeighbors.get(0).dx());
        assertEquals(recsLegacy.get(0).dy(), recsWithNeighbors.get(0).dy());
        assertEquals(recsLegacy.get(0).connectionsUnblocked(), recsWithNeighbors.get(0).connectionsUnblocked());
    }
}
