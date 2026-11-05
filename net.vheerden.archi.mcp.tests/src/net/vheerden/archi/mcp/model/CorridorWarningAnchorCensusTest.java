package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;
import net.vheerden.archi.mcp.model.routing.ConnectionThroughNoteDetector;
import net.vheerden.archi.mcp.model.routing.ViewFixture;

/**
 * Census of the (connection, note) pairs {@link ConnectionThroughNoteDetector#detect} returns — the
 * one method behind the corridor warning on {@code add-note-to-view}, {@code add-image-to-view} and
 * {@code add-view-reference-to-view}, and behind {@code auto-route-connections}' crossed-note
 * warning — on every fixture view: the 11 typed Meridian views, the seven stored-geometry corpus
 * views and the three routed oracles. Two pairs of these share their geometry — the corpus
 * {@code hh-source-clone} and {@code v4-integration-architecture-oracle} views, and the routed v4
 * and hh oracles — so 19 of the 21 rows are distinct.
 *
 * <p>Paths and rectangles are built as production builds them: {@code [sourceCentre,
 * …bendpoints…, targetCentre]} in absolute doubles, each endpoint's rectangle its node's
 * {@code {x, y, width, height}}, tested at {@link LayoutQualityAssessor#PASS_THROUGH_INSET}. A
 * connection with an endpoint that is not on the view is skipped, as production skips it; no
 * fixture has one.
 *
 * <p>Two measurements per view:
 * <ul>
 *   <li><b>real notes</b> — the view's own notes against its connections. Each Meridian view
 *       carries exactly one note and every one is the title banner above the content, so no
 *       connection reaches it: the zero here is forced by where the fixture's notes sit, not a
 *       measurement of the anchor model. The corpus and oracle views carry no notes. The columns
 *       are exercised by {@link #measure_shouldCountARealNoteOnAConnection}.</li>
 *   <li><b>placement sweep</b> — a pending note of the default size, 185 x 80, its top-left
 *       corner on every point of the absolute 10 px lattice from which the note can overlap the
 *       view's extent: the union of every node that is not a note and every point of every path,
 *       since a route can leave every element's box. The sweep counts the placements tried, the
 *       placements with at least one pair, and the pairs. Every connection is measured, as the
 *       note warning measures it. The image and view-reference warnings additionally skip a
 *       connection whose source or target is in the annotation's host chain, which is empty for a
 *       placement at top level, so these figures hold for all three kinds there. Meridian E and F
 *       carry no connections, so nothing is swept on them: their zeros are forced too.</li>
 * </ul>
 *
 * <p>The anchor model moves only the clipped first and last point of a path, so a pair can change
 * only where a placement's inset rectangle meets the stretch between the old and the new terminal.
 * Against the centre-ray anchor model (a corner-zone end on the ray from the centre, inclusive
 * bands, no reference replacement) the sweep reads 382,028 pairs on 178,550 of the 422,506
 * placements; the pinned rows below read 374,185 on 172,702: 21,013 pairs gained and 28,856 lost.
 * Over the 19 distinct views that is 293,482 on 144,212 of 346,339 placements before, 287,891 on
 * 140,606 now, 12,954 gained and 18,545 lost. Per view, placements with a pair and pairs before,
 * then pairs gained and lost: Meridian A 13,149 / 22,025, +0 -42; G 26,614 / 44,727, +1 -13; H
 * 3,609 / 7,010, +0 -22; the corpus in the order above 8,548 / 18,351, +1,905 -2,999; 15,140 /
 * 42,120, +8,051 -10,267; 5,756 / 7,232, +57 -123; 5,615 / 11,780, +44 -182; 6,798 / 9,268, +85
 * -234; 5,953 / 20,109, +2,771 -4,435; 15,140 / 42,120, +8,051 -10,267; the oracles 19,198 /
 * 46,426, +8 -44; 19,198 / 46,426, +8 -44; 6,513 / 22,522, +32 -184; every other view unmoved.
 * Each changed pair moves under one change applied alone: the corner anchor 19,048 gained and
 * 25,816 lost, the overlap-midpoint replacement 678 and 1,262, and 1,287 and 1,778 move under
 * either one alone (zero-bendpoint connections whose ends are outside on both axes). The half-open
 * bands move none. On the typed Meridian views, the closest to a live model here, 77 pairs are lost
 * and 1 gained, all from the corner anchor.
 *
 * <p>The figures are printed, and every column of each view's row is pinned.
 */
public class CorridorWarningAnchorCensusTest {

    static final String[] MERIDIAN = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"};

    /** The default note size {@code add-note-to-view} places. */
    static final int NOTE_WIDTH = 185;
    static final int NOTE_HEIGHT = 80;

    /** Grid stride of the placement sweep. */
    static final int STRIDE = 10;

    /** What one view measured. */
    record Row(String name, int connections, int notes, int notePairs, int placements,
               int placementsHit, int sweepPairs) {

        String table() {
            return String.format("%-52s conns=%d | notes=%d pairs=%d | placements=%d hit=%d"
                    + " pairs=%d", name, connections, notes, notePairs, placements,
                    placementsHit, sweepPairs);
        }

        int[] figures() {
            return new int[]{connections, notes, notePairs, placements, placementsHit, sweepPairs};
        }
    }

    private static final String[] COLUMNS = {
        "connections", "real notes", "real-note pairs", "placements", "placements with a pair",
        "sweep pairs",
    };

    static final Map<String, int[]> MEASURED = Map.ofEntries(
            Map.entry("meridian A",
                    new int[]{29, 1, 0, 23095, 13146, 21983}),
            Map.entry("meridian B",
                    new int[]{26, 1, 0, 12282, 7216, 14254}),
            Map.entry("meridian C",
                    new int[]{13, 1, 0, 20272, 4611, 6045}),
            Map.entry("meridian D",
                    new int[]{12, 1, 0, 10728, 5963, 8679}),
            Map.entry("meridian E",
                    new int[]{0, 1, 0, 0, 0, 0}),
            Map.entry("meridian F",
                    new int[]{0, 1, 0, 0, 0, 0}),
            Map.entry("meridian G",
                    new int[]{25, 1, 0, 65552, 26615, 44715}),
            Map.entry("meridian H",
                    new int[]{15, 1, 0, 8515, 3587, 6988}),
            Map.entry("meridian I",
                    new int[]{6, 1, 0, 14877, 3897, 5770}),
            Map.entry("meridian J",
                    new int[]{2, 1, 0, 12298, 1152, 1152}),
            Map.entry("meridian K",
                    new int[]{14, 1, 0, 10260, 4480, 6012}),
            Map.entry("corpus app-architecture-view",
                    new int[]{31, 0, 0, 18054, 8219, 17257}),
            Map.entry("corpus hh-source-clone",
                    new int[]{30, 0, 0, 37975, 12942, 39904}),
            Map.entry("corpus retail-bank-application-collaboration",
                    new int[]{17, 0, 0, 18063, 5713, 7166}),
            Map.entry("corpus retail-bank-business-architecture",
                    new int[]{32, 0, 0, 11250, 5563, 11642}),
            Map.entry("corpus retail-bank-drifted-anchors",
                    new int[]{20, 0, 0, 18306, 6714, 9119}),
            Map.entry("corpus st-source-clone",
                    new int[]{30, 0, 0, 13310, 5216, 18445}),
            Map.entry("corpus v4-integration-architecture-oracle",
                    new int[]{30, 0, 0, 37975, 12942, 39904}),
            Map.entry("oracle v4",
                    new int[]{30, 0, 0, 38192, 19154, 46390}),
            Map.entry("oracle hh",
                    new int[]{30, 0, 0, 38192, 19154, 46390}),
            Map.entry("oracle st",
                    new int[]{30, 0, 0, 13310, 6418, 22370}));

    @Test
    public void census() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String v : MERIDIAN) {
            ViewFixture f = ViewFixture.load("testdata/meridian-" + v + "-typed-fixture.json");
            List<AssessmentConnection> conns = new ArrayList<>();
            for (ViewFixture.FixtureConnection c : f.getConnections()) {
                conns.add(new AssessmentConnection(c.id(), c.sourceId(), c.targetId(),
                        f.buildStoredPath(c), "", 1));
            }
            rows.add(measure("meridian " + v.toUpperCase(),
                    RoundedFigureAnchorCensusTest.typedNodes(f), conns));
        }
        for (String view : AnchorModelConsumerCensusTest.CORPUS) {
            rows.add(measure("corpus " + view, AnchorModelConsumerCensusTest.corpusNodes(view),
                    AnchorModelConsumerCensusTest.corpusConnections(view)));
        }
        for (String spec : AnchorModelConsumerCensusTest.ORACLES) {
            String[] parts = spec.split(":", 2);
            OracleFixtureRouting.Inputs in = OracleFixtureRouting
                    .load(parts[1], "CorridorWarningAnchorCensusTest:" + parts[0]).route();
            rows.add(measure("oracle " + parts[0], in.nodes(), in.connections()));
        }
        int[] total = new int[COLUMNS.length];
        for (Row r : rows) {
            System.out.println("[corridor-census] " + r.table());
            int[] got = r.figures();
            for (int i = 0; i < got.length; i++) total[i] += got[i];
        }
        StringBuilder t = new StringBuilder("[corridor-census] TOTAL");
        for (int i = 0; i < COLUMNS.length; i++) {
            t.append(" | ").append(COLUMNS[i]).append('=').append(total[i]);
        }
        System.out.println(t);
        assertEquals(MEASURED.size(), rows.size());
        for (Row r : rows) {
            int[] want = MEASURED.get(r.name());
            assertNotNull("no pinned row for view " + r.name(), want);
            int[] got = r.figures();
            for (int i = 0; i < COLUMNS.length; i++) {
                assertEquals(r.name() + " " + COLUMNS[i], want[i], got[i]);
            }
        }
    }

    /**
     * The real-note columns on a hand-built view, since every fixture's notes sit clear of its
     * connections. Elements at [0,0,100,60] and [400,0,100,60] joined by a straight connection,
     * clipped to (100,30)-(400,30); a note at [200,0,80,60], inset to [210,270) x [10,50), lies on
     * that line, and one at [200,200,80,60] does not. With one connection, every placement that
     * finds a pair finds exactly one.
     */
    @Test
    public void measure_shouldCountARealNoteOnAConnection() {
        List<AssessmentNode> nodes = List.of(
                node("a", 0, 0, 100, 60, false), node("b", 400, 0, 100, 60, false),
                node("on", 200, 0, 80, 60, true), node("off", 200, 200, 80, 60, true));
        List<AssessmentConnection> conns = List.of(new AssessmentConnection("c", "a", "b",
                List.of(new double[]{50, 30}, new double[]{450, 30}), "", 1));
        Row r = measure("probe", nodes, conns);
        System.out.println("[corridor-census] " + r.table());
        assertEquals(1, r.connections());
        assertEquals(2, r.notes());
        assertEquals(1, r.notePairs());
        assertTrue(r.placementsHit() > 0);
        assertEquals(r.placementsHit(), r.sweepPairs());
    }

    /**
     * A self-connection with no bendpoints, through every consumer of the shared clip. This server
     * now stores Archi's default loop on every self-connection it creates, but one can still be
     * stored without bendpoints by an older model, another tool or a human deleting them. The
     * geometry and the readings are those of a live probe on Archi 5.10.0 with the orthogonal anchor
     * on: an Application Component at [100,100,120,60] with an Association to itself, a Business
     * Process at [300,100,121,61] with a Triggering to itself, a note over each centre, one on the
     * component away from its centre, and one whose border band holds the process's centre.
     *
     * <p>The path is {@code [c, c]}. Each end's reference is the other end's centre, which lies in
     * the element's own centre region, so it is replaced by the self-overlap midpoint, inside on
     * both axes: both ends land on the centre and the clipped path has no length. What Archi drew
     * for it: nothing at all for the Association, and for the Triggering only its arrowhead, at the
     * centre. A path that draws no line crosses and grazes nothing, so neither the corridor
     * detector nor the assessor counts it; the arrowhead a decorated one paints is not measured,
     * and the assessor says so by reporting both route-versus-visual dimensions as partial.
     */
    @Test
    public void zeroBendpointSelfConnection_shouldLandOnTheCentreAndCrossNothing() {
        List<AssessmentNode> nodes = List.of(
                node("a", 100, 100, 120, 60, false), node("p", 300, 100, 121, 61, false),
                node("noteA", 130, 110, 60, 40, true), node("noteP", 330, 110, 60, 41, true),
                node("noteOff", 102, 132, 40, 26, true), node("noteBand", 355, 125, 40, 40, true));
        double[] a = {100, 100, 120, 60};
        double[] p = {300, 100, 121, 61};
        List<double[]> pathA = List.of(new double[]{160, 130}, new double[]{160, 130});
        List<double[]> pathP = List.of(new double[]{360.5, 130.5}, new double[]{360.5, 130.5});

        List<double[]> clipA = GeometryUtils.clipPathToRectEdges(pathA, a, a);
        assertEquals(List.of(160.0, 130.0, 160.0, 130.0),
                List.of(clipA.get(0)[0], clipA.get(0)[1], clipA.get(1)[0], clipA.get(1)[1]));
        // odd box: the centre arm in doubles, half a pixel past Archi's (360, 130)
        List<double[]> clipP = GeometryUtils.clipPathToRectEdges(pathP, p, p);
        assertEquals(List.of(360.5, 130.5, 360.5, 130.5),
                List.of(clipP.get(0)[0], clipP.get(0)[1], clipP.get(1)[0], clipP.get(1)[1]));
        assertTrue(GeometryUtils.drawsNoSegment(clipA));
        assertTrue(GeometryUtils.drawsNoSegment(clipP));

        double inset = LayoutQualityAssessor.PASS_THROUGH_INSET;
        List<ConnectionThroughNoteDetector.RoutedPath> paths = List.of(
                new ConnectionThroughNoteDetector.RoutedPath("assoc", pathA, a, a),
                new ConnectionThroughNoteDetector.RoutedPath("trig", pathP, p, p));
        List<ConnectionThroughNoteDetector.NoteRect> notes = List.of(
                new ConnectionThroughNoteDetector.NoteRect("noteA", 130, 110, 60, 40),
                new ConnectionThroughNoteDetector.NoteRect("noteP", 330, 110, 60, 41),
                new ConnectionThroughNoteDetector.NoteRect("noteOff", 102, 132, 40, 26),
                new ConnectionThroughNoteDetector.NoteRect("noteBand", 355, 125, 40, 40));
        assertEquals(List.of(), ConnectionThroughNoteDetector.detect(paths, notes, inset));

        List<AssessmentConnection> conns = List.of(
                new AssessmentConnection("assoc", "a", "a", pathA, "", 1),
                new AssessmentConnection("trig", "p", "p", pathP, "", 1));
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        LayoutAssessmentResult result = assessor.assess(nodes, conns, true);
        assertEquals(0, assessor.countNonOrthogonalTerminals(conns, nodes, true).count());
        assertEquals(0, result.connectionPassThroughs().size());
        assertEquals(0, result.connectionThroughNoteCount());
        // the band note was a graze before; a line that is not drawn grazes nothing either
        assertEquals(0, result.connectionGrazesVisualCount());
        // the decoration a decorated one paints at the centre is not measured, and is said to be
        assertEquals(LayoutQualityAssessor.COVERAGE_PARTIAL, result.coverage().get("connectionThroughNote"));
        assertEquals(LayoutQualityAssessor.COVERAGE_PARTIAL, result.coverage().get("connectionGrazesVisual"));
    }

    /**
     * The same undrawn self-connection on a view with no note and no image: the arrowhead it paints
     * has nothing to land on, so there is nothing unmeasured and both dimensions stay checked.
     */
    @Test
    public void zeroBendpointSelfConnection_onAViewWithNoNoteOrImage_shouldLeaveCoverageChecked() {
        List<AssessmentNode> nodes = List.of(node("a", 100, 100, 120, 60, false),
                node("b", 400, 100, 120, 60, false));
        List<double[]> pathA = List.of(new double[]{160, 130}, new double[]{160, 130});
        LayoutAssessmentResult result = new LayoutQualityAssessor().assess(nodes,
                List.of(new AssessmentConnection("assoc", "a", "a", pathA, "", 1)), true);
        assertEquals(0, result.connectionThroughNoteCount());
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, result.coverage().get("connectionThroughNote"));
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, result.coverage().get("connectionGrazesVisual"));
    }

    /**
     * The positive arm: the same self-connection stored with Archi's default loop draws a line, and
     * a note on that line is crossed. The gate is on the drawn line, not on source == target. The
     * loop is the one Archi's palette stores for [100,100,120,60] — offsets (0,60), (100,60),
     * (100,0) off the centre (160,130) — so it runs down to (160,190), across to (260,190) and up
     * to (260,130). A note at [190,175,50,30] insets to [200,230) x [185,195), which holds y = 190.
     */
    @Test
    public void defaultLoopSelfConnection_shouldCrossANoteOnItsLine_andLeaveCoverageChecked() {
        List<AssessmentNode> nodes = List.of(node("a", 100, 100, 120, 60, false),
                node("onLoop", 190, 175, 50, 30, true), node("atCentre", 130, 110, 60, 40, true));
        double[] a = {100, 100, 120, 60};
        List<double[]> loop = List.of(new double[]{160, 130}, new double[]{160, 190},
                new double[]{260, 190}, new double[]{260, 130}, new double[]{160, 130});

        assertEquals(List.of(new ConnectionThroughNoteDetector.NoteCrossing("loop", "onLoop")),
                ConnectionThroughNoteDetector.detect(
                        List.of(new ConnectionThroughNoteDetector.RoutedPath("loop", loop, a, a)),
                        List.of(new ConnectionThroughNoteDetector.NoteRect("onLoop", 190, 175, 50, 30),
                                new ConnectionThroughNoteDetector.NoteRect("atCentre", 130, 110, 60, 40)),
                        LayoutQualityAssessor.PASS_THROUGH_INSET));

        LayoutAssessmentResult result = new LayoutQualityAssessor().assess(nodes,
                List.of(new AssessmentConnection("loop", "a", "a", loop, "", 1)), true);
        assertEquals(1, result.connectionThroughNoteCount());
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, result.coverage().get("connectionThroughNote"));
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, result.coverage().get("connectionGrazesVisual"));
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h,
            boolean note) {
        return new AssessmentNode(id, x, y, w, h, null, false, note, id, 0.0, null, null, 0.0,
                0.0, 0.0, false, null, false, AssessmentNode.TEXT_ALIGNMENT_CENTRE,
                AssessmentNode.TEXT_POSITION_TOP);
    }

    // ---- measurement ----

    static Row measure(String name, List<AssessmentNode> nodes,
            List<AssessmentConnection> connections) {
        Map<String, AssessmentNode> byId = new HashMap<>();
        for (AssessmentNode n : nodes) byId.put(n.id(), n);
        List<ConnectionThroughNoteDetector.RoutedPath> paths = new ArrayList<>();
        for (AssessmentConnection c : connections) {
            AssessmentNode src = byId.get(c.sourceNodeId());
            AssessmentNode tgt = byId.get(c.targetNodeId());
            if (src == null || tgt == null) continue;
            paths.add(new ConnectionThroughNoteDetector.RoutedPath(c.id(), c.pathPoints(),
                    rect(src), rect(tgt)));
        }

        List<ConnectionThroughNoteDetector.NoteRect> notes = new ArrayList<>();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (AssessmentNode n : nodes) {
            if (n.isNote()) {
                notes.add(new ConnectionThroughNoteDetector.NoteRect(n.id(), n.x(), n.y(),
                        n.width(), n.height()));
                continue;
            }
            minX = Math.min(minX, n.x());
            minY = Math.min(minY, n.y());
            maxX = Math.max(maxX, n.x() + n.width());
            maxY = Math.max(maxY, n.y() + n.height());
        }
        // a route can leave every element's box (a detour, a bendpoint past the content's edge), and
        // a note placed over that stretch is a placement the warning sees
        for (ConnectionThroughNoteDetector.RoutedPath path : paths) {
            for (double[] pt : path.points()) {
                minX = Math.min(minX, pt[0]);
                minY = Math.min(minY, pt[1]);
                maxX = Math.max(maxX, pt[0]);
                maxY = Math.max(maxY, pt[1]);
            }
        }
        int notePairs = ConnectionThroughNoteDetector.detect(paths, notes,
                LayoutQualityAssessor.PASS_THROUGH_INSET).size();

        int placements = 0, hit = 0, pairs = 0;
        if (!paths.isEmpty()) {
            for (double y = lattice(minY - NOTE_HEIGHT); y < maxY; y += STRIDE) {
                for (double x = lattice(minX - NOTE_WIDTH); x < maxX; x += STRIDE) {
                    List<ConnectionThroughNoteDetector.NoteCrossing> found =
                            ConnectionThroughNoteDetector.detect(paths,
                                    List.of(new ConnectionThroughNoteDetector.NoteRect(
                                            "pending", x, y, NOTE_WIDTH, NOTE_HEIGHT)),
                                    LayoutQualityAssessor.PASS_THROUGH_INSET);
                    placements++;
                    if (!found.isEmpty()) hit++;
                    pairs += found.size();
                }
            }
        }
        return new Row(name, paths.size(), notes.size(), notePairs, placements, hit, pairs);
    }

    /** The first grid line at or before {@code v} on the absolute {@link #STRIDE} lattice. */
    private static double lattice(double v) {
        return Math.floor(v / STRIDE) * STRIDE;
    }

    private static double[] rect(AssessmentNode n) {
        return new double[]{n.x(), n.y(), n.width(), n.height()};
    }
}
