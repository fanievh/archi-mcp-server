package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.LabelPositionOptimizer;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.ViewFixture;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelBendpoint;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Measures, per view, what reading a connection's path in the right frame does to the label metric
 * the rating is computed from.
 *
 * <p>The label optimiser picks where each connection's label sits. It used to be handed a path built
 * by reading each bendpoint's source-anchored offset as though it were a canvas coordinate, which
 * displaces the whole path by the source centre — hundreds of pixels for any element away from the
 * origin. It chose positions against that phantom path; the assessor then scored those positions
 * against the real one.
 *
 * <p>Both halves of that are reproduced here and compared with everything else held fixed. The
 * optimiser is run twice over identical geometry, identical obstacles and an identically seeded
 * trial order — once on the displaced path, once on the reconstructed one — and the two resulting
 * position sets are scored by the same assessor over the same unchanged polyline. The only variable
 * is the frame the optimiser read, so the difference is attributable to it and to nothing else.
 *
 * <p>That is the measurement, and it does not need the routing pass that normally invokes the
 * optimiser: re-routing would replace the very geometry the metric is computed over and make the
 * comparison meaningless. Nothing here needs a model or a display — the optimiser is pure geometry,
 * and the assessor's label box is arithmetic on the label's character count, not a text measurement.
 */
public class LabelFrameRatingImpactTest {

    /** Every fixture, so the per-view report has no gaps to hide a regression in. */
    private static final List<String> VIEWS = List.of(
            "retail-bank-application-collaboration",
            "retail-bank-drifted-anchors",
            "app-architecture-view",
            "hh-source-clone",
            "st-source-clone",
            "v4-integration-architecture-oracle",
            "retail-bank-business-architecture");

    /**
     * A connection label placed at the middle of its path. Deliberately not borrowed from
     * {@code AssessmentNode}'s text-position constants: those name an ELEMENT's vertical text
     * placement (top / centre / bottom), whereas a connection's value selects a fraction ALONG the
     * path — 0 near the source, 1 the middle, 2 near the target. The numbers coincide; the meanings
     * do not, and reusing the element name here would mislead.
     */
    private static final int LABEL_AT_MIDDLE_OF_PATH = 1;

    /** Quality levels, worst first, so a rating move can be compared rather than only detected. */
    private static final List<String> RATING_ORDER =
            List.of("poor", "fair", "good", "excellent");

    /**
     * Coverage levels, weakest first. A dimension reports {@code partial} when it could not judge
     * everything it was asked to — for label overlaps, when a label had to be hosted on a segment
     * too short to carry it. That is a weaker claim than {@code checked}, so a move in either
     * direction is meaningful and has to be compared rather than merely detected.
     */
    private static final List<String> COVERAGE_ORDER =
            List.of("not-checked", "partial", "checked");

    private record ViewOutcome(int labelOverlapCount, String rating, String labelCoverage) {}

    private record Measurement(ViewOutcome beforeFix, ViewOutcome afterFix,
                               int labelledConnections, int labelledAndBent) {}

    // ---------------------------------------------------------------- the two frames

    /**
     * What the optimiser is handed now — obtained by calling the production method, not by
     * restating it, so this measurement moves if that method ever does.
     *
     * <p>Only the bendpoint list is read off the connection, so the EMF object needs nothing else:
     * no relationship, no endpoints, and deliberately no name, since a named connection is the one
     * thing that could reach text measurement and text measurement is not headless-safe.
     */
    private static List<AbsoluteBendpointDto> reconstructedPath(
            ViewFixture.FixtureConnection conn, ViewFixture.FixtureElement src,
            ViewFixture.FixtureElement tgt) {
        IDiagramModelArchimateConnection emf =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        for (ViewFixture.FixtureBendpoint bp : conn.bendpoints()) {
            IDiagramModelBendpoint b = IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
            b.setStartX(bp.startX());
            b.setStartY(bp.startY());
            b.setEndX(bp.endX());
            b.setEndY(bp.endY());
            emf.getBendpoints().add(b);
        }
        AssessmentNode srcNode = new AssessmentNode(src.id(), src.x(), src.y(), src.w(), src.h(),
                null, false, false, src.name(), 0.0, null, null, 0.0, 0.0, 0.0);
        AssessmentNode tgtNode = new AssessmentNode(tgt.id(), tgt.x(), tgt.y(), tgt.w(), tgt.h(),
                null, false, false, tgt.name(), 0.0, null, null, 0.0, 0.0, 0.0);
        return LabelOptimizationPass.absolutePathOf(emf, srcNode, tgtNode);
    }

    /** What it used to be handed: the raw source-anchored offset, read as if it were absolute. */
    private static List<AbsoluteBendpointDto> displacedPath(ViewFixture.FixtureConnection conn) {
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        for (ViewFixture.FixtureBendpoint bp : conn.bendpoints()) {
            path.add(new AbsoluteBendpointDto(bp.startX(), bp.startY()));
        }
        return path;
    }

    /** The polyline the assessor measures over — unchanged between the two runs, by construction. */
    private static List<double[]> assessedPath(ViewFixture.FixtureConnection conn,
                                               ViewFixture.FixtureElement src,
                                               ViewFixture.FixtureElement tgt) {
        double srcCx = src.x() + src.w() / 2.0;
        double srcCy = src.y() + src.h() / 2.0;
        double tgtCx = tgt.x() + tgt.w() / 2.0;
        double tgtCy = tgt.y() + tgt.h() / 2.0;

        List<double[]> path = new ArrayList<>();
        path.add(new double[]{srcCx, srcCy});
        int count = conn.bendpoints().size();
        int index = 0;
        for (ViewFixture.FixtureBendpoint bp : conn.bendpoints()) {
            double weight = (index + 1.0) / (count + 1.0);
            index++;
            path.add(new double[]{
                    (bp.startX() + srcCx) * (1.0 - weight) + (bp.endX() + tgtCx) * weight,
                    (bp.startY() + srcCy) * (1.0 - weight) + (bp.endY() + tgtCy) * weight});
        }
        path.add(new double[]{tgtCx, tgtCy});
        return path;
    }

    // ---------------------------------------------------------------- the measurement

    private static Measurement measure(String view) throws IOException {
        ViewFixture fixture = ViewFixture.load("testdata/" + view + "-fixture.json");
        Map<String, ViewFixture.FixtureElement> byId = new LinkedHashMap<>();
        for (ViewFixture.FixtureElement e : fixture.getElements()) {
            byId.put(e.id(), e);
        }

        List<AssessmentNode> nodes = new ArrayList<>();
        List<RoutingRect> obstacles = new ArrayList<>();
        for (ViewFixture.FixtureElement e : fixture.getElements()) {
            nodes.add(new AssessmentNode(e.id(), e.x(), e.y(), e.w(), e.h(), e.parentId(),
                    false, false, e.name(), 0.0, null, null, 0.0, 0.0, 0.0));
            obstacles.add(new RoutingRect(e.x(), e.y(), e.w(), e.h(), e.id()));
        }

        List<RoutingPipeline.ConnectionEndpoints> endpoints = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> pathsAfterFix = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> pathsBeforeFix = new ArrayList<>();
        List<ViewFixture.FixtureConnection> labelled = new ArrayList<>();
        int labelledAndBent = 0;

        for (ViewFixture.FixtureConnection conn : fixture.getConnections()) {
            if (conn.label() == null || conn.label().isEmpty()) continue;
            ViewFixture.FixtureElement src = byId.get(conn.sourceId());
            ViewFixture.FixtureElement tgt = byId.get(conn.targetId());
            if (src == null || tgt == null) continue;
            if (!conn.bendpoints().isEmpty()) labelledAndBent++;

            endpoints.add(new RoutingPipeline.ConnectionEndpoints(conn.id(),
                    new RoutingRect(src.x(), src.y(), src.w(), src.h(), src.id()),
                    new RoutingRect(tgt.x(), tgt.y(), tgt.w(), tgt.h(), tgt.id()),
                    List.of(), conn.label(), LABEL_AT_MIDDLE_OF_PATH));
            pathsAfterFix.add(reconstructedPath(conn, src, tgt));
            pathsBeforeFix.add(displacedPath(conn));
            labelled.add(conn);
        }

        if (endpoints.isEmpty()) {
            return new Measurement(null, null, 0, 0);
        }

        Map<String, Set<String>> excludeSets = new LinkedHashMap<>();
        for (RoutingPipeline.ConnectionEndpoints e : endpoints) {
            excludeSets.put(e.connectionId(), Set.of());
        }

        // Same optimiser, same obstacles, same seed — so the trial ORDER is identical and the only
        // thing that differs between the two runs is the path each trial reads.
        long seed = endpoints.size() * 31L + obstacles.size();
        LabelPositionOptimizer optimizer = new LabelPositionOptimizer();
        Map<String, Integer> chosenAfter = optimizer.optimizeMultiTrial(
                endpoints, pathsAfterFix, obstacles, excludeSets, 10, new Random(seed))
                .allPositions();
        Map<String, Integer> chosenBefore = optimizer.optimizeMultiTrial(
                endpoints, pathsBeforeFix, obstacles, excludeSets, 10, new Random(seed))
                .allPositions();

        return new Measurement(
                score(view, nodes, labelled, byId, chosenBefore),
                score(view, nodes, labelled, byId, chosenAfter),
                labelled.size(), labelledAndBent);
    }

    /** Scores one set of chosen label positions over the unchanged assessed polyline. */
    private static ViewOutcome score(String view, List<AssessmentNode> nodes,
                                     List<ViewFixture.FixtureConnection> labelled,
                                     Map<String, ViewFixture.FixtureElement> byId,
                                     Map<String, Integer> chosen) {
        List<AssessmentConnection> connections = new ArrayList<>();
        for (ViewFixture.FixtureConnection conn : labelled) {
            ViewFixture.FixtureElement src = byId.get(conn.sourceId());
            ViewFixture.FixtureElement tgt = byId.get(conn.targetId());
            connections.add(new AssessmentConnection(conn.id(), conn.sourceId(), conn.targetId(),
                    assessedPath(conn, src, tgt), conn.label(),
                    chosen.getOrDefault(conn.id(), LABEL_AT_MIDDLE_OF_PATH)));
        }
        LayoutAssessmentResult result = new LayoutQualityAssessor().assess(nodes, connections, true);
        return new ViewOutcome(result.labelOverlapCount(), result.overallRating(),
                result.coverage().get("labelOverlaps"));
    }

    // ---------------------------------------------------------------- the acceptance report

    /**
     * The per-view report: label overlaps and rating, before and after, on every fixture.
     *
     * <p>Reported per view and never aggregated, because a total can hide one view degrading while
     * another improves. Two views improve, four are unchanged, and none gets worse — on any of the
     * three things worth watching: the count, the rating, and the coverage level the count is
     * declared at.
     */
    @Test
    public void shouldImproveTwoViewsAndDegradeNone() throws IOException {
        Map<String, Measurement> report = new LinkedHashMap<>();
        for (String view : VIEWS) {
            report.put(view, measure(view));
        }

        int improved = 0;
        int unchanged = 0;
        for (Map.Entry<String, Measurement> entry : report.entrySet()) {
            String view = entry.getKey();
            Measurement m = entry.getValue();
            if (m.beforeFix() == null) continue;          // no labelled connection to place

            assertTrue(view + ": label overlaps must not increase — "
                            + m.beforeFix().labelOverlapCount() + " -> "
                            + m.afterFix().labelOverlapCount(),
                    m.afterFix().labelOverlapCount() <= m.beforeFix().labelOverlapCount());

            assertTrue(view + ": the rating must not drop — "
                            + m.beforeFix().rating() + " -> " + m.afterFix().rating(),
                    RATING_ORDER.indexOf(m.afterFix().rating())
                            >= RATING_ORDER.indexOf(m.beforeFix().rating()));

            assertTrue(view + ": the label dimension must not fall to a weaker coverage level — "
                            + m.beforeFix().labelCoverage() + " -> " + m.afterFix().labelCoverage(),
                    COVERAGE_ORDER.indexOf(m.afterFix().labelCoverage())
                            >= COVERAGE_ORDER.indexOf(m.beforeFix().labelCoverage()));

            if (m.afterFix().labelOverlapCount() < m.beforeFix().labelOverlapCount()) improved++;
            else unchanged++;
        }

        assertEquals("views whose label overlaps improve", 2, improved);
        assertEquals("views left unchanged", 4, unchanged);
    }

    /**
     * The two views that improve, named, with their numbers.
     *
     * <p>Four label overlaps become none on each. The mechanism is visible in the chosen positions:
     * reading the displaced path, the optimiser put two labels at their source end, and the assessor
     * — scoring against the real polyline — found them colliding.
     */
    @Test
    public void shouldClearEveryLabelOverlap_onTheTwoViewsCarryingBentLabelledConnections()
            throws IOException {
        for (String view : List.of("retail-bank-application-collaboration",
                                   "retail-bank-drifted-anchors")) {
            Measurement m = measure(view);
            assertEquals(view + ": overlaps before the fix", 4, m.beforeFix().labelOverlapCount());
            assertEquals(view + ": overlaps after the fix", 0, m.afterFix().labelOverlapCount());
            assertEquals(view + ": rating before", "fair", m.beforeFix().rating());
            assertEquals(view + ": rating after", "fair", m.afterFix().rating());

            // A third improvement, and the one the acceptance criteria singled out to watch: the
            // displaced path pushed labels onto hosting segments too short to carry them, which
            // downgrades what the dimension is willing to claim. Reading the real path clears it.
            assertEquals(view + ": coverage before", "partial", m.beforeFix().labelCoverage());
            assertEquals(view + ": coverage after", "checked", m.afterFix().labelCoverage());
        }
    }

    /**
     * The four unchanged views are unchanged for a stated reason, not by luck.
     *
     * <p>The optimiser closes its label rectangle with source centre → path → target centre, so a
     * connection with no bendpoints contributes an empty path and cannot be displaced by a frame
     * error at all. Every one of these views carries labelled connections, and not one of those
     * connections is bent — so there was nothing for the fix to change. Asserting the reason, and
     * not merely the sameness, is what stops "four of six unchanged" being read as weak evidence.
     */
    @Test
    public void shouldLeaveAViewUntouched_exactlyWhenNoLabelledConnectionIsBent() throws IOException {
        for (String view : VIEWS) {
            Measurement m = measure(view);
            if (m.beforeFix() == null) {
                assertEquals(view + ": a view with no labelled connection has nothing to place",
                        0, m.labelledConnections());
                continue;
            }
            boolean moved = m.afterFix().labelOverlapCount() != m.beforeFix().labelOverlapCount();
            assertEquals(view + ": a view moves if and only if it carries a bent labelled connection",
                    moved, m.labelledAndBent() > 0);
        }
    }
}
