package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;

/**
 * Census of the terminal ends M1 ({@code nonOrthogonalTerminalCount}) can flag on the three oracle
 * fixtures, classified by where the terminal bendpoint sits relative to its element.
 *
 * <p>Archi draws a connection end through {@code OrthogonalAnchor}, whose reference is the first
 * (source end) or last (target end) bendpoint. Where that bendpoint lies relative to the element
 * decides what is drawn:
 * <ul>
 *   <li><b>FACE</b> — exactly one of its coordinates lies within the element's extent. Archi
 *       projects that coordinate onto the nearest edge, so the drawn stub is perpendicular to the
 *       face: axis-aligned by construction.</li>
 *   <li><b>CORNER</b> — both coordinates lie outside the extent. Archi anchors a plain rectangle
 *       at the corner itself, so the drawn stub can be diagonal.</li>
 *   <li><b>INSIDE</b> — on or inside the element; nothing outside it is drawn, never flagged.</li>
 * </ul>
 *
 * <p>For every routed end (paths with at least one bendpoint) the census evaluates three models of
 * "the terminal segment" and reports, per model, how many ends and connections it flags:
 * <ul>
 *   <li><b>centre</b> — the angle of element centre → bendpoint, length clipped by a ray from the
 *       centre (a ChopboxAnchor model).</li>
 *   <li><b>lengthOnly</b> — the length of the drawn stub, but the angle still from the centre.</li>
 *   <li><b>drawn</b> — angle and length both taken from the drawn stub {@code [anchor, bendpoint]},
 *       where {@code anchor} is the orthogonal-anchor point of
 *       {@link GeometryUtils#clipPathToRectEdges}.</li>
 * </ul>
 * plus {@code drawnNoGate}, the drawn model without the minimum-visible-length gate, which shows
 * how many ends that gate still suppresses. Zero-bendpoint paths are reported separately, tested
 * as production tests them: on the anchor-to-anchor line Archi draws.
 *
 * <p>Zones use Archi's half-open extent: a coordinate equal to the left or top edge is within, one
 * equal to the right or bottom edge is outside.
 *
 * <p>The census asserts that its {@code drawn} model selects exactly the connections production M1
 * reports (so it cannot drift from the metric it describes), that no FACE end is flagged under it,
 * and the per-fixture totals it measured — including that {@code lengthOnly} flags the same ends as
 * {@code centre} and {@code drawnNoGate} the same ends as {@code drawn}, i.e. that on these
 * fixtures neither a drawn-stub length nor the minimum-visible-length gate suppresses any end.
 */
public class NonOrthogonalTerminalZoneCensusTest {

    private static final double ANGLE_THRESHOLD_DEG = 5.0;

    enum Zone { FACE, CORNER, INSIDE }

    /** One model's tallies: flagged ends per zone and the set of flagged connections. */
    static final class Tally {
        final Map<Zone, Integer> ends = new EnumMap<>(Zone.class);
        final Set<String> connections = new TreeSet<>();

        Tally() {
            for (Zone z : Zone.values()) ends.put(z, 0);
        }

        void flag(Zone zone, String connId) {
            ends.merge(zone, 1, Integer::sum);
            connections.add(connId);
        }

        int endTotal() {
            return ends.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public String toString() {
            return "connections=" + connections.size() + " ends=" + endTotal()
                    + " (FACE=" + ends.get(Zone.FACE) + " CORNER=" + ends.get(Zone.CORNER)
                    + " INSIDE=" + ends.get(Zone.INSIDE) + ")";
        }
    }

    /** Everything the census measured on one fixture. */
    record Census(String name, LayoutAssessmentResult result,
                  LayoutQualityAssessor.NonOrthogonalTerminalResult production,
                  Map<Zone, Integer> routedEnds, Tally centre, Tally lengthOnly, Tally drawn,
                  Tally drawnNoGate, Set<String> zeroBendpointFlagged) {

        /** Connections M1 flags under the drawn model: routed ends plus the zero-bendpoint half. */
        Set<String> drawnM1() {
            Set<String> all = new LinkedHashSet<>(drawn.connections);
            all.addAll(zeroBendpointFlagged);
            return all;
        }
    }

    @Test
    public void v4OracleFixture_census() throws IOException {
        Census c = census("v4", "testdata/v4-integration-architecture-oracle-fixture.json");
        assertDrawnModelIsProductionAndNeverFlagsAFace(c);
        assertMeasured(c, 28, 3, 3, 3);
    }

    /** A source clone of the V4 oracle: the same routes, so the same figures. */
    @Test
    public void hhSourceCloneFixture_census() throws IOException {
        Census c = census("hh", "testdata/hh-source-clone-fixture.json");
        assertDrawnModelIsProductionAndNeverFlagsAFace(c);
        assertMeasured(c, 28, 3, 3, 3);
    }

    @Test
    public void stSourceCloneFixture_census() throws IOException {
        Census c = census("st", "testdata/st-source-clone-fixture.json");
        assertDrawnModelIsProductionAndNeverFlagsAFace(c);
        // One CORNER end the centre model counts, `9e262703` source, has its bendpoint on the
        // element's bottom edge line (618, 338): outside on both axes, so Archi anchors at the
        // corner (614, 338) and draws a 4 px horizontal stub the drawn model does not flag.
        assertMeasured(c, 11, 6, 5, 4);
    }

    private static void assertDrawnModelIsProductionAndNeverFlagsAFace(Census c) {
        assertEquals(c.name() + ": the census's drawn model must select exactly the connections"
                        + " production M1 reports",
                new TreeSet<>(c.production().violatorIds()), new TreeSet<>(c.drawnM1()));
        assertEquals(c.name() + ": production M1 count", c.production().count(),
                c.result().nonOrthogonalTerminalCount());
        assertEquals(c.name() + ": a FACE end is drawn perpendicular to its face, so the drawn"
                        + " model can never flag one",
                Integer.valueOf(0), c.drawn().ends.get(Zone.FACE));
    }

    /**
     * The figures measured at the shipped terminal face margin: how many ends the centre model
     * counts in each zone (the FACE ones being stubs Archi draws perpendicular), how many CORNER
     * ends the drawn model still counts, and how many connections M1 reports now. A routing
     * change that moves an end between zones moves these.
     */
    private static void assertMeasured(Census c, int centreFaceEnds, int centreCornerEnds,
            int drawnCornerEnds, int m1) {
        assertEquals(c.name() + ": FACE-zone ends the centre model counts",
                Integer.valueOf(centreFaceEnds), c.centre().ends.get(Zone.FACE));
        assertEquals(c.name() + ": CORNER-zone ends the centre model counts",
                Integer.valueOf(centreCornerEnds), c.centre().ends.get(Zone.CORNER));
        assertEquals(c.name() + ": CORNER-zone ends the drawn model counts",
                Integer.valueOf(drawnCornerEnds), c.drawn().ends.get(Zone.CORNER));
        assertEquals(c.name() + ": M1", m1, c.result().nonOrthogonalTerminalCount());
        assertEquals(c.name() + ": a drawn-stub length with the centre angle suppresses no end",
                c.centre().ends, c.lengthOnly().ends);
        assertEquals(c.name() + ": the minimum-visible-length gate suppresses no end",
                c.drawn().ends, c.drawnNoGate().ends);
    }

    // ---- measurement ----

    static Census census(String name, String fixturePath) throws IOException {
        OracleFixtureRouting.Inputs in = OracleFixtureRouting
                .load(fixturePath, "NonOrthogonalTerminalZoneCensusTest:" + name).route();
        return measure(name, in);
    }

    /** Classifies every terminal end of {@code in} and assesses it with the production assessor. */
    static Census measure(String name, OracleFixtureRouting.Inputs in) {
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        LayoutAssessmentResult result = assessor.assess(in.nodes(), in.connections(), true);
        LayoutQualityAssessor.NonOrthogonalTerminalResult production =
                assessor.countNonOrthogonalTerminals(in.connections(), in.nodes(), true);

        Map<String, AssessmentNode> byId = new HashMap<>();
        for (AssessmentNode n : in.nodes()) byId.put(n.id(), n);

        Map<Zone, Integer> routedEnds = new EnumMap<>(Zone.class);
        for (Zone z : Zone.values()) routedEnds.put(z, 0);
        Tally centre = new Tally();
        Tally lengthOnly = new Tally();
        Tally drawn = new Tally();
        Tally drawnNoGate = new Tally();
        Set<String> zeroBendpointFlagged = new TreeSet<>();
        List<String> lines = new ArrayList<>();

        for (AssessmentConnection conn : in.connections()) {
            List<double[]> path = conn.pathPoints();
            AssessmentNode src = byId.get(conn.sourceNodeId());
            AssessmentNode tgt = byId.get(conn.targetNodeId());
            int last = path.size() - 1;
            if (path.size() == 2) {
                // Zero-bendpoint: the anchor-to-anchor line, each end skipped when the other
                // centre lies on or inside it, as production does.
                List<double[]> line = GeometryUtils.clipPathToRectEdges(path, rect(src), rect(tgt));
                boolean flagged = nonOrthogonal(line.get(0), line.get(1))
                        && Math.hypot(line.get(1)[0] - line.get(0)[0], line.get(1)[1] - line.get(0)[1])
                                >= LayoutQualityAssessor.VISIBLE_DIAGONAL_MIN_PX
                        && (!LayoutQualityAssessor.isOnOrInsideElement(path.get(1), src)
                                || !LayoutQualityAssessor.isOnOrInsideElement(path.get(0), tgt));
                if (flagged) zeroBendpointFlagged.add(conn.id());
                continue;
            }
            List<double[]> clipped = GeometryUtils.clipPathToRectEdges(path, rect(src), rect(tgt));
            measureEnd(conn.id(), "source", path.get(0), clipped.get(0), path.get(1), src,
                    routedEnds, centre, lengthOnly, drawn, drawnNoGate, lines);
            measureEnd(conn.id(), "target", path.get(last), clipped.get(last), path.get(last - 1),
                    tgt, routedEnds, centre, lengthOnly, drawn, drawnNoGate, lines);
        }

        System.out.println("[census " + name + "] routed ends examined: " + routedEnds);
        System.out.println("[census " + name + "] centre      " + centre);
        System.out.println("[census " + name + "] lengthOnly  " + lengthOnly);
        System.out.println("[census " + name + "] drawn       " + drawn);
        System.out.println("[census " + name + "] drawnNoGate " + drawnNoGate);
        System.out.println("[census " + name + "] zero-bendpoint flagged connections: "
                + zeroBendpointFlagged.size());
        System.out.println("[census " + name + "] production M1=" + production.count()
                + " (routed " + production.routedCount() + ", zero-bendpoint "
                + production.zeroBendpointCount() + ")");
        System.out.println("[census " + name + "] metrics: M1=" + result.nonOrthogonalTerminalCount()
                + " M4=" + result.connectionEdgeCoincidenceCount()
                + " coincidentSegments=" + result.coincidentSegmentCount()
                + " HPQ=" + result.hubPortQualityScore()
                + " R8=" + result.corridorUtilisationScore()
                + " VP10=" + result.vAxisParallelGapP10()
                + " overall=" + result.overallRating()
                + " routing=" + result.routingRating()
                + " layout=" + result.layoutRating()
                + " m1Rating=" + result.ratingBreakdown().get("nonOrthogonalTerminals"));
        for (String line : lines) {
            System.out.println("[census " + name + "] " + line);
        }
        return new Census(name, result, production, routedEnds, centre, lengthOnly, drawn,
                drawnNoGate, zeroBendpointFlagged);
    }

    private static void measureEnd(String connId, String end, double[] centre, double[] anchor,
            double[] bp, AssessmentNode elem, Map<Zone, Integer> routedEnds, Tally centreTally,
            Tally lengthOnlyTally, Tally drawnTally, Tally drawnNoGateTally, List<String> lines) {
        if (elem == null) return;
        Zone zone = zoneOf(bp, elem);
        routedEnds.merge(zone, 1, Integer::sum);
        if (zone == Zone.INSIDE) return;

        double min = LayoutQualityAssessor.VISIBLE_DIAGONAL_MIN_PX;
        double centreLen = LayoutQualityAssessor.visibleSegmentLength(centre, bp, elem);
        double drawnLen = Math.hypot(bp[0] - anchor[0], bp[1] - anchor[1]);
        boolean centreAngle = nonOrthogonal(centre, bp);
        boolean drawnAngle = nonOrthogonal(anchor, bp);

        boolean byCentre = centreAngle && centreLen >= min;
        if (byCentre) centreTally.flag(zone, connId);
        if (centreAngle && drawnLen >= min) lengthOnlyTally.flag(zone, connId);
        if (drawnAngle && drawnLen >= min) drawnTally.flag(zone, connId);
        if (drawnAngle) drawnNoGateTally.flag(zone, connId);
        if (byCentre || drawnAngle) {
            lines.add(String.format(
                    "%s %s zone=%s bp=(%.1f,%.1f) elem=[%.0f..%.0f]x[%.0f..%.0f]"
                            + " centre(angle=%.1f° len=%.2f) drawn(angle=%.1f° len=%.2f)",
                    connId, end, zone, bp[0], bp[1], elem.x(), elem.x() + elem.width(),
                    elem.y(), elem.y() + elem.height(), deviation(centre, bp), centreLen,
                    deviation(anchor, bp), drawnLen));
        }
    }

    /**
     * Zone of a terminal bendpoint, with Archi's half-open extent: a coordinate equal to the left
     * or top edge is within, one equal to the right or bottom edge is outside.
     */
    static Zone zoneOf(double[] bp, AssessmentNode elem) {
        if (LayoutQualityAssessor.isOnOrInsideElement(bp, elem)) return Zone.INSIDE;
        boolean xIn = bp[0] >= elem.x() && bp[0] < elem.x() + elem.width();
        boolean yIn = bp[1] >= elem.y() && bp[1] < elem.y() + elem.height();
        return (xIn || yIn) ? Zone.FACE : Zone.CORNER;
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

    private static boolean nonOrthogonal(double[] p1, double[] p2) {
        return deviation(p1, p2) > ANGLE_THRESHOLD_DEG;
    }
}
