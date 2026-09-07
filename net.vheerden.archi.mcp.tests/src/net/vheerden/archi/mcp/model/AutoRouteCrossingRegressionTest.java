package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Pins the non-monotonic re-route signal against the two real views that motivated it:
 * already-tidy ELK layouts whose only routing defect was diagonal terminals, and whose
 * full re-route made them measurably worse.
 *
 * <p>Lives in {@code net.vheerden.archi.mcp.model} deliberately, so both sides of the
 * comparison run through the SAME production counter the accessor uses
 * ({@link LayoutQualityAssessor#countPathCrossings}, package-private). A test-local
 * re-implementation could drift from production and quietly invalidate the pin.</p>
 *
 * <p><strong>Why fixtures and not unit tests alone.</strong> The defect this guards was
 * never a wrong comparison — it was the absence of any comparison. A pure unit test on the
 * predicate cannot fail for that. These tests route real geometry and assert the signal
 * fires on it.</p>
 */
public class AutoRouteCrossingRegressionTest {

    private static final String VIEW_A =
            "testdata/retail-bank-business-architecture-fixture.json";
    private static final String VIEW_G =
            "testdata/retail-bank-application-collaboration-fixture.json";

    /** Crossings among the view's stored geometry — the accessor's {@code crossingsBefore}. */
    private int storedCrossings(ViewFixture fixture) {
        return LayoutQualityAssessor.countPathCrossings(fixture.buildStoredPaths());
    }

    /**
     * Routes every connection and counts crossings on the result — the accessor's
     * {@code crossingsAfter}. Mirrors its path construction: a connection the router
     * declines keeps its stored path, so both counts always span the same connection set.
     */
    private int routedCrossings(ViewFixture fixture) {
        List<RoutingPipeline.ConnectionEndpoints> endpoints = fixture.buildConnectionEndpoints();
        RoutingResult result = new RoutingPipeline()
                .routeAllConnections(endpoints, fixture.buildAllObstacles());
        Map<String, List<AbsoluteBendpointDto>> routed = result.routed();

        List<List<double[]>> afterPaths = new ArrayList<>();
        for (ViewFixture.FixtureConnection conn : fixture.getConnections()) {
            List<AbsoluteBendpointDto> bps = routed.get(conn.id());
            if (bps == null) {
                afterPaths.add(fixture.buildStoredPath(conn));
                continue;
            }
            ViewFixture.FixtureElement src = fixture.getElementById(conn.sourceId());
            ViewFixture.FixtureElement tgt = fixture.getElementById(conn.targetId());
            List<double[]> path = new ArrayList<>();
            path.add(new double[]{src.x() + src.w() / 2.0, src.y() + src.h() / 2.0});
            for (AbsoluteBendpointDto bp : bps) {
                path.add(new double[]{bp.x(), bp.y()});
            }
            path.add(new double[]{tgt.x() + tgt.w() / 2.0, tgt.y() + tgt.h() / 2.0});
            afterPaths.add(path);
        }
        return LayoutQualityAssessor.countPathCrossings(afterPaths);
    }

    private List<StructuredWarningDto> warningsFor(int before, int after, int straightLine) {
        List<StructuredWarningDto> structured = new ArrayList<>();
        RoutingPipeline.appendCrossingWarnings(before, after, straightLine, true,
                DispatchArm.APPLIED, new ArrayList<String>(), structured);
        return structured;
    }

    private boolean hasRegressionCode(List<StructuredWarningDto> structured) {
        return structured.stream().anyMatch(
                w -> StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED.equals(w.code()));
    }

    // ---- Calibration: the fixtures still carry the geometry this story was written against ----

    @Test
    public void shouldPinStoredBaseline_businessArchitecture() throws IOException {
        // 6 crossings is the terminals-only baseline the modeller kept after undoing the
        // full re-route. If this drifts, the fixture no longer represents the case.
        assertEquals("View A stored-geometry baseline",
                6, storedCrossings(ViewFixture.load(VIEW_A)));
    }

    @Test
    public void shouldPinStoredBaseline_applicationCollaboration() throws IOException {
        assertEquals("View G stored-geometry baseline",
                2, storedCrossings(ViewFixture.load(VIEW_G)));
    }

    // ---- The regression is real, and the signal catches it ----

    @Test
    public void shouldWarn_whenFullRerouteRegressesBusinessArchitecture() throws IOException {
        ViewFixture fixture = ViewFixture.load(VIEW_A);
        int before = storedCrossings(fixture);
        int after = routedCrossings(fixture);

        assertTrue("A full re-route of this tidy ELK layout should still regress crossings "
                        + "(before=" + before + ", after=" + after + ")",
                after > before);
        assertTrue("the regression must be flagged",
                hasRegressionCode(warningsFor(before, after, before)));
    }

    @Test
    public void shouldWarn_whenFullRerouteRegressesApplicationCollaboration() throws IOException {
        ViewFixture fixture = ViewFixture.load(VIEW_G);
        int before = storedCrossings(fixture);
        int after = routedCrossings(fixture);

        assertTrue("full re-route should regress crossings (before=" + before
                        + ", after=" + after + ")",
                after > before);
        assertTrue("the regression must be flagged",
                hasRegressionCode(warningsFor(before, after, before)));
    }

    /**
     * The application-collaboration view is the case that shipped silently: every
     * connection runs centre-to-centre without crossing another, so the straight-line
     * estimate is 0 and the density signal short-circuits before it can say anything.
     */
    @Test
    public void shouldWarn_whereDensitySignalIsStructurallySilent() throws IOException {
        ViewFixture fixture = ViewFixture.load(VIEW_G);

        // The straight-line estimate is the crossing count over bare centre-to-centre
        // paths — what RoutingPipeline.computeStraightLineCrossings measures. Rebuilt here
        // through countPathCrossings because that helper is package-private to the routing
        // package; both apply the same strict proper-intersection rule.
        List<List<double[]>> centreToCentre = new ArrayList<>();
        for (ViewFixture.FixtureConnection conn : fixture.getConnections()) {
            List<double[]> stored = fixture.buildStoredPath(conn);
            centreToCentre.add(List.of(stored.get(0), stored.get(stored.size() - 1)));
        }
        int straightLine = LayoutQualityAssessor.countPathCrossings(centreToCentre);
        assertEquals("View G's straight-line estimate is zero", 0, straightLine);

        int before = storedCrossings(fixture);
        int after = routedCrossings(fixture);
        assertNull("the density signal cannot fire on a zero estimate",
                RoutingPipeline.buildCrossingInflationWarning(after, straightLine));
        assertNotNull("the before/after signal still fires",
                RoutingPipeline.buildCrossingsRegressedWarning(before, after, DispatchArm.APPLIED));

        List<StructuredWarningDto> structured = warningsFor(before, after, straightLine);
        assertTrue("and it reaches structuredWarnings", hasRegressionCode(structured));
        assertTrue("naming the actual counts",
                structured.get(0).message().contains(String.valueOf(before))
                        && structured.get(0).message().contains(String.valueOf(after)));
    }

    // ---- No over-correction ----

    @Test
    public void shouldNotWarn_whenRouteHoldsOrImprovesCrossings() throws IOException {
        // Guard clause on real numbers: with before/after swapped, after <= before, so the
        // predicate must stay silent AND leave the free-text list untouched. Deliberately a
        // cheap boundary check on appendCrossingWarnings' plumbing — the substantive
        // "improving route" case is covered by the unrouted-input test below, which routes
        // real geometry.
        ViewFixture fixture = ViewFixture.load(VIEW_A);
        int tidy = storedCrossings(fixture);
        int churned = routedCrossings(fixture);

        List<String> free = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();
        RoutingPipeline.appendCrossingWarnings(churned, tidy, 0, true, DispatchArm.APPLIED, free, structured);

        assertFalse("an improving re-route must not be flagged", hasRegressionCode(structured));
        assertTrue("and emits no crossing warning at all", free.isEmpty());
    }

    /**
     * The suppression is a property of the GATE, not of the arm — and until this test it was
     * only ever asserted on one.
     *
     * <p>Every other {@code DispatchArm} reference in this class is {@code APPLIED}, and
     * tree-wide only two calls passed {@code inputWasRouted == false}, both of them applied. So
     * "the unrouted-input suppression is pinned on all three arms" was a claim nothing checked:
     * moving the gate inside the arm switch would have left every existing test green while a
     * queued or awaiting-approval first route of an unrouted view acquired a warning telling the
     * caller to discard it.</p>
     *
     * <p>The {@code true} half is what makes the {@code false} half mean anything. Without it a
     * suppression test passes on an emitter that has stopped emitting at all.</p>
     */
    @Test
    public void shouldSuppressUnroutedInputOnEveryArm_andWarnOnEveryArmWhenItWasRouted() {
        for (DispatchArm arm : DispatchArm.values()) {
            List<String> free = new ArrayList<>();
            List<StructuredWarningDto> structured = new ArrayList<>();
            RoutingPipeline.appendCrossingWarnings(46, 83, 0, false, arm, free, structured);

            assertTrue(arm + ": an unrouted input must emit no regression code", structured.isEmpty());
            assertTrue(arm + ": and no regression free-text either", free.isEmpty());

            List<String> routedFree = new ArrayList<>();
            List<StructuredWarningDto> routedStructured = new ArrayList<>();
            RoutingPipeline.appendCrossingWarnings(46, 83, 0, true, arm, routedFree, routedStructured);

            assertTrue(arm + ": the identical counts on a ROUTED input must warn, or the "
                    + "suppression above proves nothing", hasRegressionCode(routedStructured));
            assertEquals(arm + ": and the free-text sink carries the same string",
                    routedStructured.get(0).message(), routedFree.get(0));
        }
    }

    /**
     * The false-positive that would otherwise have shipped. An unrouted view has no
     * bendpoints, so every connection is a straight centre-to-centre line — near
     * crossing-minimal, because an orthogonal route must detour around obstacles. Routing
     * it for the first time (the single most common use of this tool) therefore RAISES the
     * crossing count while plainly improving the diagram.
     *
     * <p>Without the {@code inputWasRouted} gate this view would be flagged as a
     * regression and the caller told to undo a perfectly good first route, back to
     * diagonals. Every committed pre-existing fixture exhibits this: measured
     * before→after of 46→83, 86→123, 86→123 and 37→95 across the four of them.</p>
     */
    @Test
    public void shouldNotWarn_whenRoutingAnUnroutedView() throws IOException {
        ViewFixture fixture = ViewFixture.load("testdata/app-architecture-view-fixture.json");
        for (ViewFixture.FixtureConnection conn : fixture.getConnections()) {
            assertTrue("fixture precondition: the view is unrouted",
                    conn.bendpoints().isEmpty());
        }

        int before = storedCrossings(fixture);
        int after = routedCrossings(fixture);
        assertTrue("precondition: routing this unrouted view does raise crossings "
                        + "(before=" + before + ", after=" + after + ")",
                after > before);

        // The raw predicate sees a "regression" …
        assertNotNull("the bare comparison alone would flag this",
                RoutingPipeline.buildCrossingsRegressedWarning(before, after, DispatchArm.APPLIED));
        // … but a first route of an unrouted view must never be reported as one.
        List<String> free = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();
        RoutingPipeline.appendCrossingWarnings(before, after, 0, false, DispatchArm.APPLIED, free, structured);
        assertFalse("an unrouted input must not be flagged as a regression",
                hasRegressionCode(structured));
        assertTrue("and must emit no regression free-text either", free.isEmpty());
    }
}
