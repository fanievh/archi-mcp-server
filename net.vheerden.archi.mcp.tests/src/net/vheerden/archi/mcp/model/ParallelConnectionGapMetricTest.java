package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Pin tests for the {@code parallelConnectionGap} metric (2026-05-12).
 *
 * <p>Three calibration-anchor regression tests lock the
 * perception-aligned ordering of V_p10 across the reference views:</p>
 * <ul>
 *   <li>V4 manual gold (id-3b2665e3ff6840708dbed2b3d1415613) — V_p10 = 13.30 &plusmn; 0.5
 *       (perception-anchor primary signal).</li>
 *   <li>HH source baseline (id-20f7f4e77d324b698bb3fe3f939d5f40) — V_p10 = 6.00 &plusmn; 0.5
 *       (ordering anchor; must be &lt; V4 gold).</li>
 *   <li>H2 (id-ddb84fbd57d24caaa15b0da62b75f531) — V_p10 = 0.00 &plusmn; 0.5
 *       (poor anchor; must be &lt; HH source).</li>
 * </ul>
 *
 * <p>Seven algorithm-correctness pins cover the edge cases of the
 * segment-extraction + nearest-parallel-overlapping-neighbour computation +
 * violator-ID surfacing.</p>
 *
 * <p>Bendpoint geometry inlined verbatim from the calibration workspace
 * ({@code compute_parallel_gap.py:70} runs at {@code AXIS_TOL = 2}, identical to
 * {@link LayoutQualityAssessor#PARALLEL_GAP_AXIS_TOLERANCE_PX}).</p>
 *
 * <p>Pure-geometry — no Eclipse / EMF / OSGi dependency.</p>
 */
public class ParallelConnectionGapMetricTest {

    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() {
        assessor = new LayoutQualityAssessor();
    }

    // ---- Calibration-anchor pins ----

    @Test
    public void v4ManualGold_perceptionAnchorPin_vP10() {
        List<AssessmentConnection> connections = new ArrayList<>();
        connections.add(conn("v4_c0",
                        new double[]{641, 219},
                        new double[]{467, 219},
                        new double[]{468, 168},
                        new double[]{175, 168},
                        new double[]{177, 150}));
        connections.add(conn("v4_c1",
                        new double[]{641, 249},
                        new double[]{157, 249},
                        new double[]{157, 298}));
        connections.add(conn("v4_c2",
                        new double[]{395, 200},
                        new double[]{395, 532},
                        new double[]{181, 531}));
        connections.add(conn("v4_c3",
                        new double[]{276, 180},
                        new double[]{276, 737}));
        connections.add(conn("v4_c4",
                        new double[]{466, 219},
                        new double[]{466, 839},
                        new double[]{177, 839}));
        connections.add(conn("v4_c5",
                        new double[]{323, 265},
                        new double[]{325, 1160},
                        new double[]{294, 1159}));
        connections.add(conn("v4_c6",
                        new double[]{641, 286},
                        new double[]{525, 286},
                        new double[]{525, 1364},
                        new double[]{261, 1364}));
        connections.add(conn("v4_c7",
                        new double[]{943, 754},
                        new double[]{1233, 754},
                        new double[]{1233, 1174},
                        new double[]{1291, 1174}));
        connections.add(conn("v4_c8",
                        new double[]{943, 784},
                        new double[]{1177, 784},
                        new double[]{1177, 1595}));
        connections.add(conn("v4_c9",
                        new double[]{943, 784},
                        new double[]{1157, 784},
                        new double[]{1157, 1807}));
        connections.add(conn("v4_c10",
                        new double[]{943, 799},
                        new double[]{1273, 799},
                        new double[]{1272, 1391},
                        new double[]{1282, 1390}));
        connections.add(conn("v4_c11",
                        new double[]{943, 829},
                        new double[]{1262, 829},
                        new double[]{1262, 980},
                        new double[]{1291, 980}));
        connections.add(conn("v4_c12",
                        new double[]{923, 1261},
                        new double[]{977, 1261},
                        new double[]{980, 478},
                        new double[]{687, 476},
                        new double[]{688, 345}));
        connections.add(conn("v4_c13",
                        new double[]{923, 1468},
                        new double[]{1544, 1468},
                        new double[]{1544, 867},
                        new double[]{1407, 867},
                        new double[]{1407, 804}));
        connections.add(conn("v4_c14",
                        new double[]{1383, -3},
                        new double[]{494, -3},
                        new double[]{492, 792}));
        connections.add(conn("v4_c15",
                        new double[]{1475, 121},
                        new double[]{1596, 121},
                        new double[]{1596, 1518},
                        new double[]{923, 1518}));
        connections.add(conn("v4_c16",
                        new double[]{1291, 121},
                        new double[]{1117, 121},
                        new double[]{1117, 159},
                        new double[]{943, 159}));
        connections.add(conn("v4_c17",
                        new double[]{1395, 368},
                        new double[]{1396, 395},
                        new double[]{1115, 397},
                        new double[]{1117, 814},
                        new double[]{943, 814}));
        connections.add(conn("v4_c18",
                        new double[]{1291, 366},
                        new double[]{1175, 366},
                        new double[]{1177, 1436},
                        new double[]{736, 1431},
                        new double[]{737, 1448}));
        connections.add(conn("v4_c19",
                        new double[]{1291, 299},
                        new double[]{971, 299},
                        new double[]{971, 279},
                        new double[]{943, 279}));
        connections.add(conn("v4_c20",
                        new double[]{1407, 586},
                        new double[]{1407, 618},
                        new double[]{1145, 619},
                        new double[]{1146, 739},
                        new double[]{943, 739}));
        connections.add(conn("v4_c21",
                        new double[]{1291, 551},
                        new double[]{1204, 551},
                        new double[]{1204, 1416},
                        new double[]{824, 1414},
                        new double[]{827, 1448}));
        connections.add(conn("v4_c22",
                        new double[]{1407, 516},
                        new double[]{1406, 451},
                        new double[]{731, 453},
                        new double[]{730, 345}));
        connections.add(conn("v4_c23",
                        new double[]{1291, 735},
                        new double[]{1000, 735},
                        new double[]{1000, 219},
                        new double[]{943, 219}));
        connections.add(conn("v4_c24",
                        new double[]{1291, 980},
                        new double[]{1291, 345},
                        new double[]{771, 345}));
        connections.add(conn("v4_c25",
                        new double[]{1291, 1185},
                        new double[]{437, 1185},
                        new double[]{437, 219}));
        connections.add(conn("v4_c26",
                        new double[]{1291, 1390},
                        new double[]{1029, 1390},
                        new double[]{1029, 432},
                        new double[]{813, 431},
                        new double[]{813, 345}));
        connections.add(conn("v4_c27",
                        new double[]{1291, 1807},
                        new double[]{1058, 1807},
                        new double[]{1060, 412},
                        new double[]{854, 413},
                        new double[]{854, 345}));
        connections.add(conn("v4_c28",
                        new double[]{1017, 2025},
                        new double[]{1017, 924},
                        new double[]{792, 924}));
        connections.add(conn("v4_c29",
                        new double[]{1291, 2025},
                        new double[]{1087, 2025},
                        new double[]{1088, 375},
                        new double[]{896, 374},
                        new double[]{896, 345}));

        LayoutAssessmentResult result = assessor.assess(twoPlaceholderNodes(), connections, true);

        assertNotNull("V4 manual gold: vAxisParallelGapP10 must be non-null", result.vAxisParallelGapP10());
        assertEquals("V4 manual gold V_p10 perception-anchor regression (calibration ref = 13.30)",
                13.30, result.vAxisParallelGapP10(), 0.5);
    }

    @Test
    public void hhSourceBaseline_orderingAnchor_vP10() {
        List<AssessmentConnection> connections = new ArrayList<>();
        connections.add(conn("hh_c0",
                        new double[]{350, 219},
                        new double[]{350, 150},
                        new double[]{177, 150}));
        // hh_c1 has a single bendpoint — no segments; included for fixture completeness.
        connections.add(conn("hh_c1",
                        new double[]{157, 219}));
        connections.add(conn("hh_c2",
                        new double[]{467, 219},
                        new double[]{467, 531}));
        connections.add(conn("hh_c3",
                        new double[]{261, 219},
                        new double[]{261, 736}));
        connections.add(conn("hh_c4",
                        new double[]{525, 219},
                        new double[]{525, 913},
                        new double[]{177, 913}));
        connections.add(conn("hh_c5",
                        new double[]{641, 219},
                        new double[]{641, 261},
                        new double[]{294, 261},
                        new double[]{294, 1159}));
        connections.add(conn("hh_c6",
                        new double[]{583, 219},
                        new double[]{583, 1364}));
        connections.add(conn("hh_c7",
                        new double[]{792, 861},
                        new double[]{1101, 861},
                        new double[]{1101, 1185}));
        connections.add(conn("hh_c8",
                        new double[]{1132, 784},
                        new double[]{1132, 1595}));
        connections.add(conn("hh_c9",
                        new double[]{1164, 784},
                        new double[]{1164, 1807}));
        connections.add(conn("hh_c10",
                        new double[]{943, 784},
                        new double[]{943, 817},
                        new double[]{1282, 817},
                        new double[]{1282, 1390}));
        connections.add(conn("hh_c11",
                        new double[]{943, 784},
                        new double[]{943, 980}));
        connections.add(conn("hh_c12",
                        new double[]{593, 1261},
                        new double[]{593, 345},
                        new double[]{792, 345}));
        connections.add(conn("hh_c13",
                        new double[]{1498, 1504},
                        new double[]{1498, 867},
                        new double[]{1407, 867}));
        connections.add(conn("hh_c14",
                        new double[]{1383, 84},
                        new double[]{408, 84},
                        new double[]{408, 784}));
        connections.add(conn("hh_c15",
                        new double[]{1570, 121},
                        new double[]{1570, 1504}));
        connections.add(conn("hh_c16",
                        new double[]{943, 121},
                        new double[]{943, 219}));
        connections.add(conn("hh_c17",
                        new double[]{1395, 368},
                        new double[]{1152, 368},
                        new double[]{1152, 784}));
        connections.add(conn("hh_c18",
                        new double[]{1228, 333},
                        new double[]{1228, 1349},
                        new double[]{782, 1349}));
        connections.add(conn("hh_c19",
                        new double[]{1012, 333},
                        new double[]{1012, 219}));
        connections.add(conn("hh_c20",
                        new double[]{1222, 551},
                        new double[]{1222, 784}));
        connections.add(conn("hh_c21",
                        new double[]{1260, 551},
                        new double[]{1260, 1399},
                        new double[]{782, 1399}));
        connections.add(conn("hh_c22",
                        new double[]{1407, 469},
                        new double[]{942, 469},
                        new double[]{942, 345},
                        new double[]{792, 345}));
        connections.add(conn("hh_c23",
                        new double[]{1082, 769},
                        new double[]{1082, 219}));
        connections.add(conn("hh_c24",
                        new double[]{973, 980},
                        new double[]{973, 374},
                        new double[]{792, 374}));
        connections.add(conn("hh_c25",
                        new double[]{1291, 1185},
                        new double[]{1291, 219}));
        connections.add(conn("hh_c26",
                        new double[]{1005, 1390},
                        new double[]{1005, 404},
                        new double[]{792, 404}));
        connections.add(conn("hh_c27",
                        new double[]{1037, 1807},
                        new double[]{1037, 434},
                        new double[]{792, 434}));
        connections.add(conn("hh_c28",
                        new double[]{1196, 2025},
                        new double[]{1196, 850},
                        new double[]{792, 850}));
        connections.add(conn("hh_c29",
                        new double[]{1069, 2025},
                        new double[]{1069, 464},
                        new double[]{792, 464}));

        LayoutAssessmentResult result = assessor.assess(twoPlaceholderNodes(), connections, true);

        assertNotNull("HH source: vAxisParallelGapP10 must be non-null", result.vAxisParallelGapP10());
        assertEquals("HH source baseline V_p10 ordering anchor (calibration ref = 6.00)",
                6.00, result.vAxisParallelGapP10(), 0.5);
        assertTrue("HH source V_p10 (" + result.vAxisParallelGapP10()
                        + ") must be < V4 gold (13.30) — monotonic perception ordering",
                result.vAxisParallelGapP10() < 13.30);
    }

    @Test
    public void partyTestH2_poorAnchor_vP10() {
        List<AssessmentConnection> connections = new ArrayList<>();
        connections.add(conn("h2_c0",
                        new double[]{641, 150},
                        new double[]{463, 150},
                        new double[]{463, 121},
                        new double[]{285, 121}));
        connections.add(conn("h2_c1",
                        new double[]{641, 403},
                        new double[]{443, 403},
                        new double[]{443, 326},
                        new double[]{245, 326}));
        connections.add(conn("h2_c2",
                        new double[]{641, 453},
                        new double[]{125, 453},
                        new double[]{125, 503}));
        connections.add(conn("h2_c3",
                        new double[]{641, 200},
                        new double[]{451, 200},
                        new double[]{451, 736},
                        new double[]{261, 736}));
        connections.add(conn("h2_c4",
                        new double[]{478, 301},
                        new double[]{478, 839},
                        new double[]{177, 839}));
        connections.add(conn("h2_c5",
                        new double[]{641, 302},
                        new double[]{294, 302},
                        new double[]{294, 1159}));
        connections.add(conn("h2_c6",
                        new double[]{641, 352},
                        new double[]{467, 352},
                        new double[]{467, 1364},
                        new double[]{261, 1364}));
        connections.add(conn("h2_c7",
                        new double[]{792, 906},
                        new double[]{1175, 906},
                        new double[]{1175, 1178},
                        new double[]{1291, 1178}));
        connections.add(conn("h2_c8",
                        new double[]{792, 644},
                        new double[]{1094, 644},
                        new double[]{1094, 1595}));
        connections.add(conn("h2_c9",
                        new double[]{943, 771},
                        new double[]{1204, 771},
                        new double[]{1204, 1763},
                        new double[]{1411, 1763}));
        connections.add(conn("h2_c10",
                        new double[]{1560, 806},
                        new double[]{1560, 1288},
                        new double[]{1391, 1288}));
        connections.add(conn("h2_c11",
                        new double[]{943, 806},
                        new double[]{1351, 806},
                        new double[]{1351, 952}));
        connections.add(conn("h2_c12",
                        new double[]{923, 1261},
                        new double[]{952, 1261},
                        new double[]{952, 301}));
        connections.add(conn("h2_c13",
                        new double[]{923, 1468},
                        new double[]{1548, 1468},
                        new double[]{1548, 878},
                        new double[]{1407, 878},
                        new double[]{1407, 804}));
        connections.add(conn("h2_c14",
                        new double[]{1291, 154},
                        new double[]{1000, 154},
                        new double[]{1000, 842},
                        new double[]{943, 842}));
        connections.add(conn("h2_c15",
                        new double[]{1475, 121},
                        new double[]{1596, 121},
                        new double[]{1596, 1518},
                        new double[]{923, 1518}));
        connections.add(conn("h2_c16",
                        new double[]{1291, 87},
                        new double[]{971, 87},
                        new double[]{971, 150},
                        new double[]{943, 150}));
        connections.add(conn("h2_c17",
                        new double[]{1291, 333},
                        new double[]{1146, 333},
                        new double[]{1146, 865},
                        new double[]{943, 865}));
        connections.add(conn("h2_c18",
                        new double[]{1071, 333},
                        new double[]{1071, 1374},
                        new double[]{782, 1374}));
        connections.add(conn("h2_c19",
                        new double[]{1291, 319},
                        new double[]{952, 319},
                        new double[]{952, 301}));
        connections.add(conn("h2_c20",
                        new double[]{1291, 584},
                        new double[]{1233, 584},
                        new double[]{1233, 748},
                        new double[]{943, 748}));
        connections.add(conn("h2_c21",
                        new double[]{1407, 652},
                        new double[]{417, 652},
                        new double[]{417, 1504}));
        connections.add(conn("h2_c22",
                        new double[]{1291, 517},
                        new double[]{1029, 517},
                        new double[]{1029, 251},
                        new double[]{943, 251}));
        connections.add(conn("h2_c23",
                        new double[]{1291, 769},
                        new double[]{1232, 769},
                        new double[]{1232, 301}));
        connections.add(conn("h2_c24",
                        new double[]{1291, 980},
                        new double[]{1058, 980},
                        new double[]{1058, 352},
                        new double[]{943, 352}));
        connections.add(conn("h2_c25",
                        new double[]{1291, 1185},
                        new double[]{1087, 1185},
                        new double[]{1087, 403},
                        new double[]{943, 403}));
        connections.add(conn("h2_c26",
                        new double[]{1291, 1390},
                        new double[]{1117, 1390},
                        new double[]{1117, 453},
                        new double[]{943, 453}));
        connections.add(conn("h2_c27",
                        new double[]{1291, 1807},
                        new double[]{952, 1807},
                        new double[]{952, 301}));
        connections.add(conn("h2_c28",
                        new double[]{1291, 2025},
                        new double[]{1262, 2025},
                        new double[]{1262, 895},
                        new double[]{744, 895}));
        connections.add(conn("h2_c29",
                        new double[]{1291, 2025},
                        new double[]{952, 2025},
                        new double[]{952, 301}));

        LayoutAssessmentResult result = assessor.assess(twoPlaceholderNodes(), connections, true);

        assertNotNull("PartyTest H2: vAxisParallelGapP10 must be non-null", result.vAxisParallelGapP10());
        assertEquals("PartyTest H2 V_p10 poor anchor (calibration ref = 0.00)",
                0.00, result.vAxisParallelGapP10(), 0.5);
        assertTrue("PartyTest H2 V_p10 (" + result.vAxisParallelGapP10()
                        + ") must be < HH source (6.00) — monotonic poor-anchor ordering",
                result.vAxisParallelGapP10() < 6.00);
    }

    // ---- Algorithm-correctness pins ----

    @Test
    public void emptyConnections_returnsNullPrimarySignals() {
        LayoutAssessmentResult result = assessor.assess(
                twoPlaceholderNodes(), List.<AssessmentConnection>of(), true);

        assertNull("V_p10 must be null when no connections exist", result.vAxisParallelGapP10());
        assertEquals("V_narrow25Count must be 0 when no connections exist",
                0, result.vAxisParallelGapNarrow25Count());
    }

    @Test
    public void pureDiagonalConnection_yieldsNoSegmentClassification() {
        // Single segment with Δx = 100, Δy = 100 — both well above
        // PARALLEL_GAP_AXIS_TOLERANCE_PX = 2.0; classified as diagonal and dropped.
        AssessmentConnection diag = conn("diag",
                new double[]{0, 0},
                new double[]{100, 100});

        LayoutAssessmentResult result = assessor.assess(
                twoPlaceholderNodes(), List.of(diag), true);

        assertNull("Diagonal-only connection must produce no V qualifying segments",
                result.vAxisParallelGapP10());
        assertEquals("Diagonal-only connection must yield zero V narrow gap count",
                0, result.vAxisParallelGapNarrow25Count());
        LayoutAssessmentResult.ParallelConnectionGapDetail detail = result.parallelConnectionGapDetail();
        assertNotNull("Detail record must be populated when includeViolatorIds=true", detail);
        assertEquals("V axis qualifyingSegmentCount must be 0", 0, detail.vAxis().qualifyingSegmentCount());
        assertEquals("H axis qualifyingSegmentCount must be 0", 0, detail.hAxis().qualifyingSegmentCount());
    }

    @Test
    public void twoParallelVerticalSegments_overlapping_gapEqualsDistance() {
        // Two V segments at x=100 and x=120 (perpendicular distance 20 px), both spanning
        // y ∈ [0, 200] (full overlap).
        AssessmentConnection a = conn("vA",
                new double[]{100, 0},
                new double[]{100, 200});
        AssessmentConnection b = conn("vB",
                new double[]{120, 0},
                new double[]{120, 200});

        LayoutAssessmentResult result = assessor.assess(
                twoPlaceholderNodes(), List.of(a, b), true);

        LayoutAssessmentResult.ParallelConnectionGapAxisDetail v = result.parallelConnectionGapDetail().vAxis();
        assertEquals("Two overlapping parallel V segments: qualifyingSegmentCount = 2",
                2, v.qualifyingSegmentCount());
        assertEquals("V min equals the perpendicular distance (20)", 20.0, v.min(), 0.001);
        assertEquals("V mean equals the perpendicular distance (20)", 20.0, v.mean(), 0.001);
        assertEquals("V narrowGapCount@15 = 0 (gap 20 > 15)", 0, v.narrowGapCount15());
        assertEquals("V narrowGapCount@25 = 2 (gap 20 < 25, both segments contribute)",
                2, v.narrowGapCount25());
        assertEquals("V narrowGapCount@40 = 2 (gap 20 < 40, both segments contribute)",
                2, v.narrowGapCount40());
    }

    @Test
    public void coincidentAxisOverlap_yieldsZeroGap() {
        // Two V segments at IDENTICAL x=100, both spanning y ∈ [0, 200]. Same-fixed-coord
        // overlapping → gap = 0 per Python reference lines 119-124.
        AssessmentConnection a = conn("vA",
                new double[]{100, 0},
                new double[]{100, 200});
        AssessmentConnection b = conn("vB",
                new double[]{100, 0},
                new double[]{100, 200});

        LayoutAssessmentResult result = assessor.assess(
                twoPlaceholderNodes(), List.of(a, b), true);

        LayoutAssessmentResult.ParallelConnectionGapAxisDetail v = result.parallelConnectionGapDetail().vAxis();
        assertEquals("Coincident-axis V segments: qualifyingSegmentCount = 2",
                2, v.qualifyingSegmentCount());
        assertEquals("V min must be 0.0 (same-fixed-coord coincident case)",
                0.0, v.min(), 0.001);
    }

    @Test
    public void nonOverlappingParallelSegments_dontQualify() {
        // Two V segments at x=100 and x=120 — but y-spans do NOT overlap
        // (y∈[0,100] and y∈[200,300]).
        AssessmentConnection a = conn("vA",
                new double[]{100, 0},
                new double[]{100, 100});
        AssessmentConnection b = conn("vB",
                new double[]{120, 200},
                new double[]{120, 300});

        LayoutAssessmentResult result = assessor.assess(
                twoPlaceholderNodes(), List.of(a, b), true);

        assertNull("Non-overlapping parallel V segments: V_p10 must be null",
                result.vAxisParallelGapP10());
        LayoutAssessmentResult.ParallelConnectionGapAxisDetail v = result.parallelConnectionGapDetail().vAxis();
        assertEquals("Non-overlapping parallel V segments: qualifyingSegmentCount = 0",
                0, v.qualifyingSegmentCount());
        assertNull("Non-overlapping parallel V segments: p10 = null", v.p10());
    }

    @Test
    public void violatorIdsPopulatedWhenIncluded() {
        // Two V segments at x=100 and x=110 (perpendicular distance 10 px < T25 = 25),
        // both spanning y ∈ [0, 200]. Both connection IDs should appear in violatorIds map.
        AssessmentConnection a = conn("conn-violator-A",
                new double[]{100, 0},
                new double[]{100, 200});
        AssessmentConnection b = conn("conn-violator-B",
                new double[]{110, 0},
                new double[]{110, 200});

        LayoutAssessmentResult result = assessor.assess(
                twoPlaceholderNodes(), List.of(a, b), true);

        Map<String, Set<String>> violators = result.violatorIds();
        assertNotNull("violatorIds map must be populated when includeViolatorIds=true", violators);
        Set<String> vViolators = violators.get("parallelConnectionGapV");
        assertNotNull("parallelConnectionGapV key must be present in violatorIds", vViolators);
        assertTrue("conn-violator-A must be flagged (gap 10 < T25 = 25)",
                vViolators.contains("conn-violator-A"));
        assertTrue("conn-violator-B must be flagged (gap 10 < T25 = 25)",
                vViolators.contains("conn-violator-B"));
    }

    @Test
    public void violatorIdsOmittedWhenSuppressed() {
        AssessmentConnection a = conn("conn-violator-A",
                new double[]{100, 0},
                new double[]{100, 200});
        AssessmentConnection b = conn("conn-violator-B",
                new double[]{110, 0},
                new double[]{110, 200});

        LayoutAssessmentResult result = assessor.assess(
                twoPlaceholderNodes(), List.of(a, b), false);

        Map<String, Set<String>> violators = result.violatorIds();
        // Invariant: violatorIds map is null when includeViolatorIds=false.
        assertTrue("violatorIds must be null OR not contain parallelConnectionGapV when includeViolatorIds=false",
                violators == null || !violators.containsKey("parallelConnectionGapV"));
    }

    // ---- Helpers ----

    private static AssessmentConnection conn(String id, double[]... points) {
        return new AssessmentConnection(id, "src", "tgt", List.of(points), "", 1);
    }

    private static List<AssessmentNode> twoPlaceholderNodes() {
        return List.of(node("src", -10000, -10000, 10, 10),
                       node("tgt", 10000, 10000, 10, 10));
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }
}
