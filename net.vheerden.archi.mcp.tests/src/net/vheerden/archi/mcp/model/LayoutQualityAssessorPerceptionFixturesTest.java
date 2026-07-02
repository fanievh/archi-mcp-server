package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Rejection-finding fixtures (2026-04-26).
 *
 * <p>Four named connections from the rejection finding (2026-04-25 PM) —
 * pure-geometry fixtures constructed inline with the exact bendpoint sets
 * recorded in the rejection finding. These are the canonical regression test
 * cases for the perception-aligned metric redesign.</p>
 *
 * <p>Each fixture tests the per-fixture metric profile defined in the
 * validation fixtures. The four fixtures are:
 * <ol>
 *   <li>API Mgmt → Internet Banking Portal (Oracle, identical v1.3) — M4 case</li>
 *   <li>API Mgmt → Corporate Banking Portal (Oracle) — M3 zigzag + M1</li>
 *   <li>Core Banking System → API Mgmt (Oracle) — M5 hub-port quality improvement</li>
 *   <li>API Mgmt BOTTOM-face distribution (both pipelines) — M5 quality 0.25</li>
 * </ol>
 *
 * <p>No live model dependency — Eclipse-MCP-safe. Pure {@code AssessmentNode} +
 * {@code AssessmentConnection} record construction.</p>
 */
public class LayoutQualityAssessorPerceptionFixturesTest {

    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() {
        assessor = new LayoutQualityAssessor();
    }

    // ---- Fixture 1: API Mgmt → Internet Banking Portal (Oracle) ----
    // Identical on v1.3. Pre-existing pipeline issue, surfaced by M4 metric gap.
    // Source: API Mgmt (rect: 641, 219, 303, 126; centerX=792, centerY=282)
    // Target: Internet Banking (rect: 80, 80, 180, 68; centerX=170, centerY=114)
    // Bendpoints: [(350, 219), (350, 150), (177, 150)]
    //   pathPoints = [(792,282), (350,219), (350,150), (177,150), (170,114)]
    // Expected: M4 edge-coincidence = 1 (horizontal at y=150 within 3px of Internet
    //           Banking BOTTOM at y=148, gap 2px).
    @Test
    public void fixture1_shouldFlagEdgeCoincidence_apiMgmtToInternetBanking_oracle() {
        AssessmentNode apiMgmt = node("apiMgmt", 641, 219, 303, 126);
        AssessmentNode internetBanking = node("internetBanking", 80, 80, 180, 68);

        AssessmentConnection conn = new AssessmentConnection(
                "id-74e3ee1e02a84721a3db682cb1b6fb24", "apiMgmt", "internetBanking",
                List.of(new double[]{792, 282},
                        new double[]{350, 219},
                        new double[]{350, 150},
                        new double[]{177, 150},
                        new double[]{170, 114}),
                "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(apiMgmt, internetBanking), List.of(conn), false);

        // Self-exclusion removed (2026-04-27): horizontal at y=150 hugs Internet Banking BOTTOM at y=148, target is now in scope, M4 flags.
        assertEquals("M4 flags edge-coincidence with target's own BOTTOM face (post-removal)",
                1, result.connectionEdgeCoincidenceCount());

        // M2 interior: BP_first (350,219) on TOP face line of source but OUTSIDE x-extent
        // of source rect → not strictly inside. BP_last (177,150) outside target rect.
        // → M2 = 0.
        assertEquals("M2 interior should NOT flag — neither BP strictly inside elements",
                0, result.interiorTerminationCount());

        // M3 zigzag: no triple with shared-axis reversal → 0.
        assertEquals("M3 zigzag should not flag — no reversal triple",
                0, result.zigzagCount());
    }

    // ---- Fixture 2: API Mgmt → Corporate Banking Portal (Oracle) ----
    // Trades v1.3's edge-coincident segment for a zigzag pattern.
    // Source: API Mgmt (LEFT face at x=641, rect: 641, 219, 303, 126)
    // Target: Corporate Banking (rect: 400, 1100, 120, 60; centerX=460, centerY=1130)
    // Bendpoints: [(641, 259), (403, 259), (403, 219), (403, 261), (437, 261), (437, 1159)]
    //   pathPoints = [(792,282), (641,259), (403,259), (403,219), (403,261), (437,261), (437,1159), (460,1130)]
    // Expected:
    //   - M1 nonOrth = 0 (BP1 (641,259) on LEFT perimeter)
    //   - M3 zigzag = 1 (triple (403,259) → (403,219) → (403,261), x=403 shared,
    //     Δy = -40, +42 opposite signs > 1px)
    @Test
    public void fixture2_shouldFlagZigzag_apiMgmtToCorpBanking_oracleC1() {
        AssessmentNode apiMgmt = node("apiMgmt", 641, 219, 303, 126);
        AssessmentNode corpBanking = node("corpBanking", 400, 1100, 120, 60);

        AssessmentConnection conn = new AssessmentConnection(
                "id-3795e46e72a049e596b618b1ce948441", "apiMgmt", "corpBanking",
                List.of(new double[]{792, 282},
                        new double[]{641, 259},
                        new double[]{403, 259},
                        new double[]{403, 219},
                        new double[]{403, 261},
                        new double[]{437, 261},
                        new double[]{437, 1159},
                        new double[]{460, 1130}),
                "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(apiMgmt, corpBanking), List.of(conn), false);

        // M1 corrected nonOrth must report 0 — BP1 (641,259) on source LEFT
        // perimeter (x=641), visible post-clip segment is zero-length and trivially orthogonal.
        assertEquals("AC-1: M1 nonOrth must be 0 — BP1 on LEFT perimeter, visible segment zero",
                0, result.nonOrthogonalTerminalCount());

        // M3: zigzag triple (403,259) → (403,219) → (403,261) at x=403 — Δy=-40 then +42.
        assertEquals("M3 zigzag must flag the (403,259→403,219→403,261) reversal",
                1, result.zigzagCount());

        // The interior-termination check on the target side: BP_last (437, 1159) inside
        // target rect (400-520, 1100-1160) → M2 flags. This is consistent with the spec
        // (target-side BP routed 1px inside the target body — a routing artefact).
        assertEquals("M2 interior must flag BP_last (437,1159) strictly inside target body",
                1, result.interiorTerminationCount());
    }

    // ---- Fixture 3: Core Banking System → API Mgmt (Oracle, fixture rect) ----
    // Geometric corner case: segment ends exactly at target TOP (y=219), giving
    // zero y-overlap with target RIGHT face's y-range [219,345]. M4 correctly
    // does NOT flag — independent of self-exclusion. This fixture protects the
    // 10px-overlap threshold guard.
    // For the LIVE-oracle geometry of this connection (where API Mgmt's full
    // y-range [94,344] gives 98px overlap), see fixture5_liveOracleGeometry_*.
    // Source: Core Banking (rect: 900, 60, 100, 60; centerX=950, centerY=90)
    // Target: API Mgmt (RIGHT face at x=944, rect: 641, 219, 303, 126)
    // v1.3 Bendpoints: [(943, 121), (943, 219)]
    //   pathPoints = [(950,90), (943,121), (943,219), (792,282)]
    @Test
    public void fixture3_v13_terminalAtCorner_zeroOverlap_shouldNotFlag() {
        AssessmentNode coreBanking = node("coreBanking", 900, 60, 100, 60);
        AssessmentNode apiMgmt = node("apiMgmt", 641, 219, 303, 126);

        AssessmentConnection conn = new AssessmentConnection(
                "core-to-api-v13", "coreBanking", "apiMgmt",
                List.of(new double[]{950, 90},
                        new double[]{943, 121},
                        new double[]{943, 219},
                        new double[]{792, 282}),
                "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(coreBanking, apiMgmt), List.of(conn), false);

        // Geometric guard: segment ends at target TOP, y-overlap = 0px below 10px threshold.
        assertEquals("M4 should NOT flag — segment y-overlap with target RIGHT is 0px (below 10px threshold)",
                0, result.connectionEdgeCoincidenceCount());

        // Single connection on API Mgmt RIGHT face — below M5_FACE_GUARD_MIN_CONNECTIONS.
        // Hub-port quality remains 1.0 (no hub face exists).
        assertEquals("M5 hub-port quality aggregate should be 1.0 (no hub face present)",
                1.0, result.hubPortQualityScore(), 1e-9);
    }

    // ---- Fixture 4: API Mgmt BOTTOM-face distribution (both pipelines) ----
    // Both pipelines fail to distribute ports on BOTTOM face.
    // Hub element: API Mgmt (BOTTOM face at y=345, x range 641-944)
    // Connections: 4 connections, all final-BP at (792, 345)
    @Test
    public void fixture4_shouldReportLowHubPortQuality_apiMgmtBottomFace_bothPipelines() {
        AssessmentNode apiMgmt = node("apiMgmt", 641, 219, 303, 126);
        AssessmentNode downstream1 = node("downstream1", 100, 800, 80, 50);
        AssessmentNode downstream2 = node("downstream2", 300, 800, 80, 50);
        AssessmentNode downstream3 = node("downstream3", 500, 800, 80, 50);
        AssessmentNode downstream4 = node("downstream4", 700, 800, 80, 50);

        // 4 connections from API Mgmt BOTTOM face — all entering at the same slot (x=792).
        // Each connection: source center (792, 282) → BP1 (792, 345) on BOTTOM face → ... → target.
        AssessmentConnection c1 = makeBottomFaceConnection("c1", "downstream1", 100, 800, 80, 50);
        AssessmentConnection c2 = makeBottomFaceConnection("c2", "downstream2", 300, 800, 80, 50);
        AssessmentConnection c3 = makeBottomFaceConnection("c3", "downstream3", 500, 800, 80, 50);
        AssessmentConnection c4 = makeBottomFaceConnection("c4", "downstream4", 700, 800, 80, 50);

        LayoutAssessmentResult result = assessor.assess(
                List.of(apiMgmt, downstream1, downstream2, downstream3, downstream4),
                List.of(c1, c2, c3, c4), true);

        // All 4 connections enter the API Mgmt BOTTOM face at the same x=792 slot.
        // distinct=1 / total=4 → quality = 0.25.
        assertEquals("M5 hub-port quality on API Mgmt BOTTOM face must be 1/4 = 0.25",
                0.25, result.hubPortQualityScore(), 1e-9);

        // Per-face details should expose the failing face when violator IDs requested.
        assertNotNull("HubFaceDetail list must be populated when includeViolatorIds=true",
                result.hubPortQualityFaces());
        assertFalse("HubFaceDetail list must contain at least one face",
                result.hubPortQualityFaces().isEmpty());
        LayoutAssessmentResult.HubFaceDetail apiMgmtFace = result.hubPortQualityFaces().stream()
                .filter(d -> "apiMgmt".equals(d.elementId()) && "BOTTOM".equals(d.face()))
                .findFirst().orElse(null);
        assertNotNull("Per-face detail for apiMgmt BOTTOM face must be present", apiMgmtFace);
        assertEquals("apiMgmt BOTTOM face must have 4 connections", 4, apiMgmtFace.connectionsOnFace());
        assertEquals("apiMgmt BOTTOM face must have 1 distinct slot", 1, apiMgmtFace.distinctSlots());
        assertEquals("apiMgmt BOTTOM face quality = 1/4", 0.25, apiMgmtFace.quality(), 1e-9);
    }

    // ---- Fixture 5: Core Banking → API Mgmt (LIVE v1.3 oracle geometry) ----
    // Reproduces the actual v1.3 oracle visual defect that motivated removing
    // M4 self-exclusion (2026-04-27). Live viewConnection id-9eb889f55b3349c0a185ed4200217de1.
    // Source: Core Banking System (rect: x=1292, y=94, w=182, h=55) — Consumers group, top-left.
    // Target: API Mgmt Platform (rect: x=642, y=94, w=300, h=250) — Integration group, top-left.
    //   RIGHT face at x=942, y-range [94, 344].
    // Bendpoints: [(1383,121), (943,121), (943,219), (792,219)]
    //   pathPoints = [(1383,121), (943,121), (943,219), (792,219)]
    //   (no diagonal endpoints — Archi clips at perimeter; sourceAnchor (1383,121) IS BP0)
    // Expected: M4 = 1 — vertical segment at x=943 (gap 1px from RIGHT at x=942)
    // overlaps target y-range [94,344] by 98px (segment y-range [121,219]).
    // Pre-change (with self-exclusion): suppressed because API Mgmt is target.
    // Post-change (self-exclusion removed): flagged.
    @Test
    public void fixture5_liveOracleGeometry_shouldFlagEdgeCoincidence_coreBankingToApiMgmtRight() {
        AssessmentNode coreBanking = node("coreBanking", 1292, 94, 182, 55);
        AssessmentNode apiMgmt = node("apiMgmt", 642, 94, 300, 250);

        AssessmentConnection conn = new AssessmentConnection(
                "id-9eb889f55b3349c0a185ed4200217de1", "coreBanking", "apiMgmt",
                List.of(new double[]{1383, 121},
                        new double[]{943, 121},
                        new double[]{943, 219},
                        new double[]{792, 219}),
                "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(coreBanking, apiMgmt), List.of(conn), false);

        assertEquals("M4 must flag the 98px vertical at x=943 hugging API Mgmt RIGHT (target)",
                1, result.connectionEdgeCoincidenceCount());
    }

    // ---- Fixture 6: Manual-oracle M1 calibration (2026-04-27) ----
    // Real geometry from V4 manual oracle violator id-3eb9f44e263440d3a303df2871b77606
    // (API Mgmt → Mobile Banking App).
    // Source-side flag, BP1 1px LEFT of source LEFT face → visible 1.02px diagonal.
    // This is the canonical regression-test case pinning the new VISIBLE_DIAGONAL_MIN_PX
    // guard against a real hand-routed connection in production.
    //
    // Source: API Mgmt Platform (rect: x=642, y=94, w=300, h=250) — Integration group.
    // Target: Mobile Banking App (rect: x=70, y=299, w=174, h=55) — Producers group.
    // sourceAnchor (792, 219) — APIM center.
    // absoluteBendpoints: [(641, 249), (157, 249), (157, 298)].
    // targetAnchor (157, 326).
    //   pathPoints = [(792,219), (641,249), (157,249), (157,298), (157,326)]
    //
    // Pre-guard: M1 = 1 — BP1 (641,249) is 1px outside APIM LEFT face (x=642), and the
    //   geometric segment (792,219)→(641,249) has 11.2° deviation → predicate 2 fires.
    //   The visible post-clip segment (642, 248.80)→(641, 249) is 1.02px long.
    // Post-guard: visible 1.02 < 3.0 → suppressed → M1 = 0.
    @Test
    public void fixture6_manualOracleNonOrth_pixelImperceptible_subThresholdShouldNotFlag() {
        AssessmentNode apiMgmt = node("apiMgmt", 642, 94, 300, 250);    // Center (792, 219)
        AssessmentNode mobile = node("mobile", 70, 299, 174, 55);        // Center (157, 326.5)

        AssessmentConnection conn = new AssessmentConnection(
                "id-3eb9f44e263440d3a303df2871b77606", "apiMgmt", "mobile",
                List.of(new double[]{792, 219},   // sourceAnchor (APIM center)
                        new double[]{641, 249},   // BP1: 1px LEFT of LEFT face → visible 1.02px
                        new double[]{157, 249},
                        new double[]{157, 298},   // 1px above target TOP face (orthogonal seg)
                        new double[]{157, 326}),  // targetAnchor
                "", 1);

        LayoutAssessmentResult result = assessor.assess(
                List.of(apiMgmt, mobile), List.of(conn), false);

        // Calibration: the manual-oracle's 1px-outside BP1 produces a sub-perceptible
        // visible diagonal that the user cannot resolve. Pre-guard would flag (1);
        // post-guard suppresses (0). This pins the calibration against a real oracle case.
        assertEquals("Manual-oracle violator with sub-perceptible visible length must NOT flag M1",
                0, result.nonOrthogonalTerminalCount());

        // Sibling metrics unaffected by the M1 guard (sanity).
        assertEquals("M2 interior must be 0 — no BP strictly inside source/target",
                0, result.interiorTerminationCount());
        assertEquals("M3 zigzag must be 0 — no shared-axis reversal triple in this path",
                0, result.zigzagCount());
    }

    // ---- Fixture 7: overlap-binary calibration substrate ----
    // Reproduces the sibling-overlap pair from a clone view
    // (id-ddb84fbd57d24caaa15b0da62b75f531). Perceived "poor" vs pre-change assessor "fair"
    // — the canonical calibration mismatch that motivated the binary >0 → poor re-anchor.
    //
    // Empirical pair: API Management Platform overlaps Enterprise Service Bus
    // inside the "Integration & Middleware Layer" parent group — 15px vertical overlap.
    //
    // Geometry from live get-view-contents: parent group at (592, 20, 400, 1589), API Mgmt child
    // at relative (50, 74, 300, 415), ESB child at relative (50, 474, 280, 75).
    @Test
    public void fixture7_overlapBinary_h2_2026_05_06_clone_shouldRatePoor() {
        AssessmentNode integrationGroup = new AssessmentNode(
                "integration", 592, 20, 400, 1589, null, true, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
        AssessmentNode apiMgmt = new AssessmentNode(
                "apiMgmt", 50, 74, 300, 415, "integration", false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
        AssessmentNode esb = new AssessmentNode(
                "esb", 50, 474, 280, 75, "integration", false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);

        LayoutAssessmentResult result = assessor.assess(
                List.of(integrationGroup, apiMgmt, esb), List.of(), false);

        assertEquals("H2 sibling-overlap pair must register overlapCount=1",
                1, result.overlapCount());
        assertEquals("Post-re-anchor: overlap=1 → ratingBreakdown.overlaps=poor (was fair)",
                "poor", result.ratingBreakdown().get("overlaps"));
        assertEquals("L1 binary >0 → layoutRating=poor",
                "poor", result.layoutRating());
        assertEquals("max(layoutRating=poor, routingRating=excellent) = overallRating=poor",
                "poor", result.overallRating());
    }

    // ---- Fixture 8: overlap-binary calibration substrate ----
    // Reproduces the sibling-overlap pair from a clone view
    // (id-04019469961e4ab9b8bc9bf88a5c605b). Perceived "poor (fail)" vs pre-change assessor
    // "fair" — the second clean data point.
    //
    // Empirical pair: "Integration & Middleware Layer" group overlaps "Consumers
    // (Backend Systems)" group at their boundary — 5px horizontal overlap (Integration right=781,
    // Consumers left=776). Group-group sibling-overlap (distinct topology shape from the element-
    // element overlap; the binary cut-point fires identically).
    //
    // Geometry from live get-view-contents: Integration group at (382, 20, 399, 991), Consumers
    // group at (776, 20, 258, 1379).
    @Test
    public void fixture8_overlapBinary_s3_2026_05_06_clone_shouldRatePoor() {
        AssessmentNode integrationGroup = new AssessmentNode(
                "integration", 382, 20, 399, 991, null, true, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
        AssessmentNode consumersGroup = new AssessmentNode(
                "consumers", 776, 20, 258, 1379, null, true, false, null, 0.0, null, null, 0.0, 0.0, 0.0);

        LayoutAssessmentResult result = assessor.assess(
                List.of(integrationGroup, consumersGroup), List.of(), false);

        assertEquals("S3 group-group overlap must register overlapCount=1",
                1, result.overlapCount());
        assertEquals("Post-re-anchor: overlap=1 → ratingBreakdown.overlaps=poor (was fair)",
                "poor", result.ratingBreakdown().get("overlaps"));
        assertEquals("L1 binary >0 → layoutRating=poor",
                "poor", result.layoutRating());
        assertEquals("max(layoutRating=poor, routingRating=excellent) = overallRating=poor",
                "poor", result.overallRating());
    }

    /** All connections share BP1 (792, 345) — same slot on API Mgmt BOTTOM face. */
    private static AssessmentConnection makeBottomFaceConnection(
            String connId, String targetId, double tx, double ty, double tw, double th) {
        double targetCenterX = tx + tw / 2.0;
        double targetCenterY = ty + th / 2.0;
        return new AssessmentConnection(
                connId, "apiMgmt", targetId,
                List.of(new double[]{792, 282},
                        new double[]{792, 345},
                        new double[]{targetCenterX, 345},
                        new double[]{targetCenterX, ty},
                        new double[]{targetCenterX, targetCenterY}),
                "", 1);
    }

    /** Creates a top-level leaf element (non-group, no parent). */
    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }
}
