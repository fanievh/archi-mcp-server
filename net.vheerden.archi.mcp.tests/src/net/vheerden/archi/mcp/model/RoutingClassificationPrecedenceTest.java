package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Pin test for the {@code countZigzags} ↔ {@code detectPassThroughs} classification
 * precedence contract: a connection already classified as a cross-element passthrough
 * must not also be counted as a zigzag (Successor F of the classification verdict).
 *
 * <p>Pure-geometry tests per CLAUDE.md Eclipse Testing Workflow — no OSGi runtime
 * required.</p>
 */
public class RoutingClassificationPrecedenceTest {

    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() {
        assessor = new LayoutQualityAssessor();
    }

    @Test
    public void failedDetourPattern_zigzagYieldsToPassthrough() {
        // Three-node fixture: source, obstacle, target horizontally aligned.
        // Connection c1 attempts a detour around the obstacle but the detour fails:
        // it overshoots into the obstacle's interior at y=150 (within obstacle y-range
        // 100-200), then reverses back toward the target. The reversal triggers
        // isZigzagTriple at i=1 ((175,150) → (250,150) → (200,150): shared Y=150,
        // dx +75 then -50, opposite signs both > 1px). Simultaneously, segment
        // (175,150) → (250,150) crosses the obstacle's inset rect, so the path
        // is also a cross-element passthrough.
        //
        // Before the precedence guard: both crossElementCount==1 AND zigzag count==1
        // (double-labeling). After: passthrough takes precedence, zigzag count drops
        // to 0.
        List<AssessmentNode> nodes = List.of(
                node("source", 0, 100, 50, 100),
                node("obstacle", 200, 100, 100, 100),
                node("target", 400, 100, 50, 100));

        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "source", "target",
                        List.of(new double[]{25, 150}, new double[]{175, 150},
                                new double[]{250, 150}, new double[]{200, 150},
                                new double[]{425, 150}), "", 3));

        LayoutQualityAssessor.PassThroughResult passThroughResult =
                assessor.detectPassThroughs(connections, nodes, true);

        assertEquals("Failed-detour should produce one cross-element passthrough",
                1, passThroughResult.crossElementCount());
        assertTrue("violatorIds should contain c1",
                passThroughResult.violatorIds().contains("c1"));

        LayoutQualityAssessor.ZigzagResult zigzagResult =
                assessor.countZigzags(connections, passThroughResult.violatorIds(), true);

        assertEquals("Passthrough takes precedence — zigzag count should be 0",
                0, zigzagResult.count());
        assertTrue("Zigzag violatorIds should be empty when passthrough wins",
                zigzagResult.violatorIds().isEmpty());
    }

    @Test
    public void cleanZigzagWithoutPassthrough_stillCounts() {
        // Two-node fixture: source and target only, no obstacle in the picture.
        // Connection c1 has a clean zigzag triple at i=1
        // ((100,125) → (200,125) → (150,125): shared Y=125, dx +100 then -50,
        // opposite signs both > 1px) with no path-passes-through-element pattern.
        // Asserts the precedence guard does NOT over-fire on an empty violator set.
        List<AssessmentNode> nodes = List.of(
                node("source", 0, 100, 50, 50),
                node("target", 400, 100, 50, 50));

        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "source", "target",
                        List.of(new double[]{25, 125}, new double[]{100, 125},
                                new double[]{200, 125}, new double[]{150, 125},
                                new double[]{425, 125}), "", 3));

        LayoutQualityAssessor.PassThroughResult passThroughResult =
                assessor.detectPassThroughs(connections, nodes, true);

        assertEquals("No third node — cross-element count should be 0",
                0, passThroughResult.crossElementCount());
        assertTrue("violatorIds should be empty",
                passThroughResult.violatorIds().isEmpty());

        LayoutQualityAssessor.ZigzagResult zigzagResult =
                assessor.countZigzags(connections, passThroughResult.violatorIds(), true);

        assertEquals("Clean zigzag should still count when violator set is empty",
                1, zigzagResult.count());
        assertTrue("Zigzag violatorIds should contain c1",
                zigzagResult.violatorIds().contains("c1"));
    }

    @Test
    public void unconditionalViolatorCollection_assertsBehaviour() {
        // Reuses the failed-detour geometry from test 1, but invokes
        // detectPassThroughs with collectViolatorIds=false. Per the precedence-guard
        // contract, the cross-element violator set is collected unconditionally
        // so the guard works regardless of whether the assessor was invoked with
        // includeViolatorIds=false. Asserts violatorIds() is non-empty even when
        // collectViolatorIds=false.
        List<AssessmentNode> nodes = List.of(
                node("source", 0, 100, 50, 100),
                node("obstacle", 200, 100, 100, 100),
                node("target", 400, 100, 50, 100));

        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "source", "target",
                        List.of(new double[]{25, 150}, new double[]{175, 150},
                                new double[]{250, 150}, new double[]{200, 150},
                                new double[]{425, 150}), "", 3));

        LayoutQualityAssessor.PassThroughResult result =
                assessor.detectPassThroughs(connections, nodes, false);

        assertEquals("crossElementCount must be 1 even when collectViolatorIds=false",
                1, result.crossElementCount());
        Set<String> violatorIds = result.violatorIds();
        assertNotNull("violatorIds should not be null", violatorIds);
        assertFalse("violatorIds should be non-empty even when collectViolatorIds=false",
                violatorIds.isEmpty());
        assertTrue("violatorIds should contain c1", violatorIds.contains("c1"));
    }

    @Test
    public void failedDetourPattern_guardWorksWithoutViolatorCollection() {
        // Verifies the precedence guard in the rating-only path: collectViolatorIds=false
        // for both detectPassThroughs and countZigzags, matching assess() when
        // includeViolatorIds=false. The guard must still reduce zigzag count to 0.
        List<AssessmentNode> nodes = List.of(
                node("source", 0, 100, 50, 100),
                node("obstacle", 200, 100, 100, 100),
                node("target", 400, 100, 50, 100));

        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "source", "target",
                        List.of(new double[]{25, 150}, new double[]{175, 150},
                                new double[]{250, 150}, new double[]{200, 150},
                                new double[]{425, 150}), "", 3));

        LayoutQualityAssessor.PassThroughResult passThroughResult =
                assessor.detectPassThroughs(connections, nodes, false);

        assertEquals("crossElementCount must be 1 even when collectViolatorIds=false",
                1, passThroughResult.crossElementCount());
        assertFalse("violatorIds must be non-empty even when collectViolatorIds=false",
                passThroughResult.violatorIds().isEmpty());

        LayoutQualityAssessor.ZigzagResult zigzagResult =
                assessor.countZigzags(connections, passThroughResult.violatorIds(), false);

        assertEquals("Precedence guard must fire in rating-only path — zigzag count should be 0",
                0, zigzagResult.count());
        // Empty because the passthrough guard skipped the connection before any zigzag was found —
        // NOT because the flag suppressed collection. countZigzags now collects its violator IDs
        // unconditionally, for the same reason detectPassThroughs already did: it is itself a
        // precedence skip-set (for countLateralJogReversals), and a skip-set that empties when the
        // caller declines violator IDs would silently let one connection be counted under two
        // reversal dimensions on the rating-only path.
        assertTrue("no zigzag was found here, so nothing was collected",
                zigzagResult.violatorIds().isEmpty());
    }

    /**
     * {@code countZigzags} must publish its violators whether or not the caller wants them, because
     * {@code countLateralJogReversals} consumes that set as a precedence skip-set. A connection
     * carrying a real zigzag proves the collection is unconditional; the test above only shows the
     * set is empty when nothing was found.
     */
    @Test
    public void zigzagViolatorIds_collectedEvenWhenNotRequested() {
        List<AssessmentNode> nodes = List.of(
                node("source", 0, 100, 50, 100),
                node("target", 400, 100, 50, 100));
        // A plain shared-X reversal, with no obstacle to make it a passthrough.
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "source", "target",
                        List.of(new double[]{100, 100}, new double[]{100, 200},
                                new double[]{100, 150}, new double[]{425, 150}), "", 3));

        LayoutQualityAssessor.ZigzagResult zigzagResult =
                assessor.countZigzags(connections, Set.of(), false);

        assertEquals(1, zigzagResult.count());
        assertTrue("the skip-set must be populated for the downstream precedence guard",
                zigzagResult.violatorIds().contains("c1"));
    }

    /**
     * A connection classified as a pass-through is not counted as a lateral-jog reversal either.
     * The pass-through label is the visually correct one, exactly as it is for zigzags.
     */
    @Test
    public void lateralJogReversal_yieldsToPassthrough() {
        List<AssessmentNode> nodes = List.of(
                node("source", 0, 100, 50, 100),
                node("obstacle", 200, 100, 100, 100),
                node("target", 400, 100, 50, 100));
        // A lateral-jog reversal whose long arm drives straight through the obstacle.
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "source", "target",
                        List.of(new double[]{25, 150}, new double[]{250, 150},
                                new double[]{250, 156}, new double[]{100, 156},
                                new double[]{425, 150}), "", 3));

        LayoutQualityAssessor.PassThroughResult passThrough =
                assessor.detectPassThroughs(connections, nodes, true);
        assertTrue("fixture must actually be a passthrough",
                passThrough.violatorIds().contains("c1"));

        LayoutQualityAssessor.LateralJogReversalResult withGuard =
                assessor.countLateralJogReversals(connections, passThrough.violatorIds(), Set.of(), true);
        assertEquals("passthrough takes precedence", 0, withGuard.count());

        LayoutQualityAssessor.LateralJogReversalResult withoutGuard =
                assessor.countLateralJogReversals(connections, Set.of(), Set.of(), true);
        assertEquals("and the shape really is there — the guard is what suppressed it",
                1, withoutGuard.count());
    }

    /** A connection already counted as a zigzag is not counted as a lateral-jog reversal too. */
    @Test
    public void lateralJogReversal_yieldsToZigzag() {
        // Both shapes on one path: a shared-X reversal, then a narrow-jog reversal.
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("c1", "source", "target",
                        List.of(new double[]{100, 100}, new double[]{100, 200},
                                new double[]{100, 150}, new double[]{100, 250},
                                new double[]{107, 250}, new double[]{107, 180}), "", 3));

        LayoutQualityAssessor.ZigzagResult zigzag =
                assessor.countZigzags(connections, Set.of(), true);
        assertEquals(1, zigzag.count());

        LayoutQualityAssessor.LateralJogReversalResult withGuard =
                assessor.countLateralJogReversals(connections, Set.of(), zigzag.violatorIds(), true);
        assertEquals("zigzag takes precedence", 0, withGuard.count());

        LayoutQualityAssessor.LateralJogReversalResult withoutGuard =
                assessor.countLateralJogReversals(connections, Set.of(), Set.of(), true);
        assertEquals("and the shape really is there — the guard is what suppressed it",
                1, withoutGuard.count());
    }

    // ---- Helpers ----

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }
}
