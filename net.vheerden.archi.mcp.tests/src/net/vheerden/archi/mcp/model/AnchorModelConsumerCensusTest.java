package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.Test;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;
import net.vheerden.archi.mcp.model.routing.ViewFixture;

/**
 * Census of what the shared orthogonal-anchor model ({@link GeometryUtils#clipPathToRectEdges})
 * feeds, per consumer, on the three routed oracle fixtures and the seven stored-geometry corpus
 * views.
 *
 * <p>Three properties of Archi's {@code OrthogonalAnchor} decide where a connection end is drawn,
 * and each is counted here so a change to one of them can be attributed:
 * <ul>
 *   <li><b>corner</b> — a reference outside the element on both axes. Archi anchors a plain
 *       rectangle at the corner itself.</li>
 *   <li><b>edgeLine</b> — a reference exactly on the right or bottom edge line. Archi's bands are
 *       half-open ({@code x < left} is left, {@code x >= left + width} is right), so this
 *       reference is outside the element on that axis.</li>
 *   <li><b>replacement</b> — a zero-bendpoint connection, whose reference is the other element's
 *       centre. Archi replaces it with the midpoint of the two boxes' overlap on each axis, so
 *       two boxes overlapping on one axis are joined by a straight line.</li>
 * </ul>
 *
 * <p>Per consumer it reports M1 (total, routed, zero-bendpoint), the corner-zone ends with the
 * angle and length of the stub the shared clip produces, {@code connectionPassThroughs}, and the
 * note/image crossing and graze counts. The corpus carries no notes or images, so the last two are
 * zero there by construction; the shared clip they read is the one pass-through reads.
 *
 * <p>The figures are printed, and every column of each view's row is pinned, so the table cannot
 * silently drift from the code it describes.
 */
public class AnchorModelConsumerCensusTest {

    static final String[] ORACLES = {
        "v4:testdata/v4-integration-architecture-oracle-fixture.json",
        "hh:testdata/hh-source-clone-fixture.json",
        "st:testdata/st-source-clone-fixture.json",
    };

    static final String[] CORPUS = {
        "app-architecture-view", "hh-source-clone", "retail-bank-application-collaboration",
        "retail-bank-business-architecture", "retail-bank-drifted-anchors",
        "st-source-clone", "v4-integration-architecture-oracle",
    };

    /** What one view measured. */
    record Row(String name, int m1, int m1Routed, int m1ZeroBp, int routedEnds, int cornerEnds,
               int edgeLineEnds, int zeroBpPaths, int zeroBpAligned, int zeroBpDisjoint,
               int zeroBpOverlapping, int passThroughs, int throughVisual, int grazes,
               List<String> details) {

        String table() {
            return String.format("%-40s M1=%d (routed %d, zeroBp %d) | routedEnds=%d corner=%d"
                    + " edgeLine=%d | zeroBp=%d aligned=%d disjoint=%d overlapping=%d"
                    + " | passThroughs=%d throughVisual=%d grazes=%d",
                    name, m1, m1Routed, m1ZeroBp, routedEnds, cornerEnds, edgeLineEnds,
                    zeroBpPaths, zeroBpAligned, zeroBpDisjoint, zeroBpOverlapping, passThroughs,
                    throughVisual, grazes);
        }

        /** The pinned columns, in {@link #MEASURED} order. */
        int[] figures() {
            return new int[]{m1, m1Routed, m1ZeroBp, passThroughs, routedEnds, cornerEnds,
                    edgeLineEnds, zeroBpPaths, zeroBpAligned, zeroBpDisjoint, zeroBpOverlapping,
                    throughVisual, grazes};
        }
    }

    private static final String[] COLUMNS = {
        "M1", "M1 routed", "M1 zero-bendpoint", "connectionPassThroughs", "routed ends",
        "corner-zone ends", "edge-line ends", "zero-bendpoint paths", "aligned", "disjoint",
        "overlapping", "through a note or image", "grazes",
    };

    /**
     * Measured per view: {M1, M1 routed, M1 zero-bendpoint, connectionPassThroughs}, then the
     * ends and paths the anchor model sorts — {routed ends, corner-zone ends, edge-line ends,
     * zero-bendpoint paths, of them aligned, disjoint and overlapping} — and the note/image
     * crossing and graze counts. Against the
     * centre-ray model these read v4 3/3/0/0, hh 3/3/0/0, st 4/4/0/0 on the oracles and, on the
     * corpus in the order below, M1 29, 29, 0, 9, 1, 25, 29 and pass-throughs 10, 8, 0, 0, 3, 10,
     * 8. Every pass-through move comes from the corner anchor alone; the M1 moves come mostly
     * from the overlap-midpoint reference, through the zero-bendpoint line M1 now tests.
     */
    static final Map<String, int[]> MEASURED = Map.ofEntries(
            Map.entry("oracle v4",
                    new int[]{3, 3, 0, 0, 60, 3, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("oracle hh",
                    new int[]{3, 3, 0, 0, 60, 3, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("oracle st",
                    new int[]{4, 4, 0, 0, 60, 6, 1, 0, 0, 0, 0, 0, 0}),
            Map.entry("corpus app-architecture-view",
                    new int[]{16, 0, 16, 6, 0, 0, 0, 31, 14, 17, 0, 0, 0}),
            Map.entry("corpus hh-source-clone",
                    new int[]{25, 0, 25, 1, 0, 0, 0, 30, 5, 25, 0, 0, 0}),
            Map.entry("corpus retail-bank-application-collaboration",
                    new int[]{0, 0, 0, 0, 22, 0, 0, 6, 6, 0, 0, 0, 0}),
            Map.entry("corpus retail-bank-business-architecture",
                    new int[]{0, 0, 0, 0, 38, 0, 0, 13, 13, 0, 0, 0, 0}),
            Map.entry("corpus retail-bank-drifted-anchors",
                    new int[]{0, 0, 0, 3, 28, 0, 0, 6, 6, 0, 0, 0, 0}),
            Map.entry("corpus st-source-clone",
                    new int[]{25, 0, 25, 1, 0, 0, 0, 30, 5, 25, 0, 0, 0}),
            Map.entry("corpus v4-integration-architecture-oracle",
                    new int[]{25, 0, 25, 1, 0, 0, 0, 30, 5, 25, 0, 0, 0}));

    @Test
    public void census() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String spec : ORACLES) {
            String[] parts = spec.split(":", 2);
            OracleFixtureRouting.Inputs in = OracleFixtureRouting
                    .load(parts[1], "AnchorModelConsumerCensusTest:" + parts[0]).route();
            rows.add(measure("oracle " + parts[0], in.nodes(), in.connections()));
        }
        for (String view : CORPUS) {
            rows.add(measure("corpus " + view, corpusNodes(view), corpusConnections(view)));
        }
        for (Row r : rows) {
            System.out.println("[anchor-census] " + r.table());
        }
        for (Row r : rows) {
            for (String d : r.details()) {
                System.out.println("[anchor-census] " + r.name() + " " + d);
            }
        }
        assertEquals(MEASURED.size(), rows.size());
        for (Row r : rows) {
            int[] want = MEASURED.get(r.name());
            int[] got = r.figures();
            for (int i = 0; i < COLUMNS.length; i++) {
                assertEquals(r.name() + " " + COLUMNS[i], want[i], got[i]);
            }
        }
    }

    // ---- measurement ----

    static Row measure(String name, List<AssessmentNode> nodes,
            List<AssessmentConnection> connections) {
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        LayoutAssessmentResult result = assessor.assess(nodes, connections, true);
        LayoutQualityAssessor.NonOrthogonalTerminalResult m1 =
                assessor.countNonOrthogonalTerminals(connections, nodes, true);

        Map<String, AssessmentNode> byId = new HashMap<>();
        for (AssessmentNode n : nodes) byId.put(n.id(), n);

        int routedEnds = 0, corner = 0, edgeLine = 0;
        int zeroBp = 0, aligned = 0, disjoint = 0, overlapping = 0;
        List<String> details = new ArrayList<>();
        for (AssessmentConnection conn : connections) {
            List<double[]> path = conn.pathPoints();
            if (path.size() < 2) continue;
            AssessmentNode src = byId.get(conn.sourceNodeId());
            AssessmentNode tgt = byId.get(conn.targetNodeId());
            int last = path.size() - 1;
            List<double[]> clipped = GeometryUtils.clipPathToRectEdges(path, rect(src), rect(tgt));
            if (path.size() == 2) {
                if (src == null || tgt == null) continue;
                zeroBp++;
                boolean xOverlap = Math.max(src.x(), tgt.x())
                        < Math.min(src.x() + src.width(), tgt.x() + tgt.width());
                boolean yOverlap = Math.max(src.y(), tgt.y())
                        < Math.min(src.y() + src.height(), tgt.y() + tgt.height());
                String kind;
                if (xOverlap && yOverlap) {
                    overlapping++;
                    kind = "overlapping";
                } else if (xOverlap || yOverlap) {
                    aligned++;
                    kind = "aligned";
                } else {
                    disjoint++;
                    kind = "disjoint";
                }
                details.add(String.format("zeroBp %s %s clip=(%.1f,%.1f)->(%.1f,%.1f) angle=%.1f°"
                        + " m1=%s", conn.id(), kind, clipped.get(0)[0], clipped.get(0)[1],
                        clipped.get(last)[0], clipped.get(last)[1],
                        deviation(clipped.get(0), clipped.get(last)),
                        m1.violatorIds().contains(conn.id())));
                continue;
            }
            int[] counts = new int[2];
            end(conn.id(), "source", path.get(1), clipped.get(0), src, m1, counts, details);
            end(conn.id(), "target", path.get(last - 1), clipped.get(last), tgt, m1, counts,
                    details);
            routedEnds += (src != null ? 1 : 0) + (tgt != null ? 1 : 0);
            corner += counts[0];
            edgeLine += counts[1];
        }
        for (String p : new TreeSet<>(result.connectionPassThroughs())) {
            details.add("passThrough " + p);
        }
        return new Row(name, m1.count(), m1.routedCount(), m1.zeroBendpointCount(), routedEnds,
                corner, edgeLine, zeroBp, aligned, disjoint, overlapping,
                result.connectionPassThroughs().size(), result.connectionThroughNoteCount(),
                result.connectionGrazesVisualCount(), details);
    }

    /** Classifies one routed end by Archi's half-open bands; counts[0] corner, counts[1] edge line. */
    private static void end(String connId, String which, double[] bp, double[] anchor,
            AssessmentNode elem, LayoutQualityAssessor.NonOrthogonalTerminalResult m1,
            int[] counts, List<String> details) {
        if (elem == null) return;
        double right = elem.x() + elem.width();
        double bottom = elem.y() + elem.height();
        boolean xOut = bp[0] < elem.x() || bp[0] >= right;
        boolean yOut = bp[1] < elem.y() || bp[1] >= bottom;
        if (bp[0] == right || bp[1] == bottom) {
            counts[1]++;
            details.add(String.format("edgeLine %s %s bp=(%.1f,%.1f) elem=[%.0f..%.0f]x[%.0f..%.0f]"
                    + " anchor=(%.1f,%.1f)", connId, which, bp[0], bp[1], elem.x(), right,
                    elem.y(), bottom, anchor[0], anchor[1]));
        }
        if (xOut && yOut) {
            counts[0]++;
            details.add(String.format("corner %s %s bp=(%.1f,%.1f) elem=[%.0f..%.0f]x[%.0f..%.0f]"
                    + " anchor=(%.1f,%.1f) angle=%.1f° len=%.1f m1=%s", connId, which, bp[0],
                    bp[1], elem.x(), right, elem.y(), bottom, anchor[0], anchor[1],
                    deviation(anchor, bp), Math.hypot(bp[0] - anchor[0], bp[1] - anchor[1]),
                    m1.violatorIds().contains(connId)));
        }
    }

    // ---- corpus loading: the stored geometry, assessed exactly as the accessor assesses it ----

    static List<AssessmentNode> corpusNodes(String view) throws IOException {
        ViewFixture f = ViewFixture.load("testdata/" + view + "-fixture.json");
        List<AssessmentNode> nodes = new ArrayList<>();
        for (ViewFixture.FixtureElement e : f.getElements()) {
            nodes.add(new AssessmentNode(e.id(), e.x(), e.y(), e.w(), e.h(), e.parentId(),
                    false, false, e.name(), 0.0, null, null, 0.0, 0.0, 0.0));
        }
        return nodes;
    }

    static List<AssessmentConnection> corpusConnections(String view) throws IOException {
        ViewFixture f = ViewFixture.load("testdata/" + view + "-fixture.json");
        List<AssessmentConnection> conns = new ArrayList<>();
        for (ViewFixture.FixtureConnection c : f.getConnections()) {
            conns.add(new AssessmentConnection(c.id(), c.sourceId(), c.targetId(),
                    f.buildStoredPath(c), c.label() == null ? "" : c.label(), 1));
        }
        return conns;
    }

    private static double[] rect(AssessmentNode n) {
        return n == null ? null : new double[]{n.x(), n.y(), n.width(), n.height()};
    }

    /** Deviation in degrees of segment {@code [p1, p2]} from the nearest cardinal axis. */
    private static double deviation(double[] p1, double[] p2) {
        double dx = Math.abs(p1[0] - p2[0]);
        double dy = Math.abs(p1[1] - p2[1]);
        if (dx < 1e-9 && dy < 1e-9) return 0.0;
        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
        return Math.min(angleDeg, 90.0 - angleDeg);
    }
}
