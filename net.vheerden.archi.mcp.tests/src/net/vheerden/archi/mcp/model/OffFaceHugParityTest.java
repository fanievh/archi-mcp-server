package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.OffFaceHug;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.TerminalEgressClearancePass;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The router and the layout assessor must name the same terminals as hugging the face they depart.
 *
 * <p>Each row is one terminal case, put through both entry points: the router's per-terminal check
 * (the one its egress-lift keep-signal counts and its pre-gate report collapses to connection ids)
 * and the assessor's {@code countOffFaceParallelTerminals}. Every row is built so only the end under
 * test can hug (the other end leaves its face perpendicularly), so the assessor's per-connection count
 * is that terminal's verdict. The table covers each way the two sides once disagreed: a terminal on the
 * face line, on the router's margin line, between them and beyond it; a corner; trunk length and
 * slant; and a single-bendpoint route. Each row's expected verdict is the one the rendered view
 * supports. Mismatches are collected over the whole table before failing, so one bad row cannot hide
 * the rest.
 */
public class OffFaceHugParityTest {

    /** Source element: TOP face y=100, BOTTOM y=160, LEFT x=100, RIGHT x=220; centre (160, 130). */
    private static final RoutingRect SRC = new RoutingRect(100, 100, 120, 60, "src");
    /** Target element, far below-right; its TOP margin line is y=496. */
    private static final RoutingRect TGT = new RoutingRect(700, 500, 80, 40, "tgt");
    /** A target level with the source's top face, for the single-bendpoint row; centre (740, 100). */
    private static final RoutingRect LEVEL = new RoutingRect(700, 80, 80, 40, "level");

    private record Row(String label, int tx, int ty, int nx, int ny, boolean hugs, String face,
                       double stub) {}

    private static final List<Row> ROWS = List.of(
            new Row("on the face line", 150, 100, 210, 100, true, "TOP", 0),
            new Row("on the router's margin line, 4 px out", 150, 96, 210, 96, true, "TOP", 4),
            new Row("1 px out", 150, 99, 210, 99, true, "TOP", 1),
            new Row("3 px out (1 px off the margin line)", 150, 97, 210, 97, true, "TOP", 3),
            new Row("7 px out", 150, 93, 210, 93, true, "TOP", 7),
            new Row("8 px out: clear", 150, 92, 210, 92, false, "TOP", 8),
            new Row("bottom face, 3 px out, trunk running left", 150, 163, 60, 163, true, "BOTTOM", 3),
            new Row("left face, 4 px out, trunk 3.6 degrees off vertical", 96, 110, 102, 204, true,
                    "LEFT", 4),
            new Row("beyond the corner, on both margin lines", 96, 96, 156, 96, true, "TOP", 4),
            new Row("on the corner, trunk along the top edge", 100, 100, 160, 100, true, "TOP", 0),
            new Row("1 px trunk: a jog, not a run", 150, 96, 151, 96, false, null, 0),
            new Row("2 px trunk", 150, 96, 152, 96, true, "TOP", 4),
            new Row("4 degree trunk", 150, 96, 250, 89, true, "TOP", 4),
            new Row("6 degree trunk", 150, 96, 250, 85, false, null, 0),
            new Row("perpendicular exit", 150, 96, 150, 40, false, null, 0),
            new Row("inside the element", 150, 120, 210, 120, false, null, 0));

    @Test
    public void routerAndAssessorAgreeOnEveryTerminalCase_andEachVerdictIsTheRuledOne() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (Row row : ROWS) {
            List<AbsoluteBendpointDto> route = List.of(
                    new AbsoluteBendpointDto(row.tx(), row.ty()),
                    new AbsoluteBendpointDto(row.nx(), row.ny()),
                    new AbsoluteBendpointDto(740, row.ny()),
                    new AbsoluteBendpointDto(740, 496));
            check(row.label(), row.hugs(), row.face(), row.stub(), SRC, TGT, route, true, mismatches);
        }
        if (!mismatches.isEmpty()) {
            fail(mismatches.size() + " of " + ROWS.size() + " rows disagree:\n  "
                    + String.join("\n  ", mismatches));
        }
    }

    /**
     * A single-bendpoint route: the segment after the bendpoint heads for the other element, so that
     * element's centre is the trunk's far end. Ruled: evaluated, as Archi draws the segment — here a
     * near-horizontal run 4 px above the source's top face toward a target level with it.
     */
    @Test
    public void oneBendpointRoute_isJudgedWithTheOtherElementsCentreAsTheTrunkEnd() throws Exception {
        List<String> mismatches = new ArrayList<>();
        check("one bendpoint, trunk toward the target's centre", true, "TOP", 4, SRC, LEVEL,
                List.of(new AbsoluteBendpointDto(150, 96)), true, mismatches);
        assertTrue(String.join("\n", mismatches), mismatches.isEmpty());
    }

    /** The target end is judged by the same rule: the route arrives running along the face. */
    @Test
    public void targetEnd_isJudgedByTheSameRule() throws Exception {
        List<String> mismatches = new ArrayList<>();
        // From the target's TOP margin line straight up (clean), then into the SOURCE's bottom face
        // from the right, 3 px below it: the source here is the route's TARGET end.
        List<AbsoluteBendpointDto> route = List.of(
                new AbsoluteBendpointDto(740, 496),
                new AbsoluteBendpointDto(740, 163),
                new AbsoluteBendpointDto(150, 163));
        check("target end, bottom face, 3 px out", true, "BOTTOM", 3, SRC, TGT, route, false,
                mismatches);
        assertTrue(String.join("\n", mismatches), mismatches.isEmpty());
    }

    /**
     * Fractional terminals reach only the assessor (the router's points are whole pixels), so these
     * rows pin the shared predicate's band and clearance edges directly: within half a pixel of a face
     * line is on it; 7.9 px still hugs, 8 px does not.
     */
    @Test
    public void fractionalEdges_ofTheBandAndTheClearance() {
        assertHug("half a pixel off the face line", 150, 99.5, 210, 99.5, "TOP", 0.5);
        assertHug("7.9 px out", 150, 92.1, 210, 92.1, "TOP", 7.9);
        assertEquals("8 px out is clear", null, OffFaceHug.evaluate(150, 92, 210, 92, 100, 100, 120, 60));
        assertEquals("8.4 px out is clear", null,
                OffFaceHug.evaluate(150, 91.6, 210, 91.6, 100, 100, 120, 60));
    }

    /**
     * A terminal on a square's diagonal, 4 px out on both margin lines: the ray from the centre
     * leaves exactly through the corner, so both faces it touches are tried, and a trunk running
     * right along the top hugs the top whichever face a fixed order would have named first.
     */
    @Test
    public void rayLeavingExactlyThroughACorner_triesBothFaces() {
        OffFaceHug.Hug hug = OffFaceHug.evaluate(296, 296, 356, 296, 300, 300, 60, 60);
        assertNotNull("a run along the top from the corner hugs it", hug);
        assertEquals("TOP", hug.face().name());
        assertEquals(4.0, hug.stub(), 1e-9);
        OffFaceHug.Hug down = OffFaceHug.evaluate(296, 296, 296, 356, 300, 300, 60, 60);
        assertNotNull("and a run down the left side hugs the left", down);
        assertEquals("LEFT", down.face().name());
    }

    // ---------------------------------------------------------------- helpers

    private static void assertHug(String label, double tx, double ty, double nx, double ny,
                                  String face, double stub) {
        OffFaceHug.Hug hug = OffFaceHug.evaluate(tx, ty, nx, ny, 100, 100, 120, 60);
        assertNotNull(label + ": expected a hug", hug);
        assertEquals(label + ": face", face, hug.face().name());
        assertEquals(label + ": stub", stub, hug.stub(), 1e-9);
    }

    /**
     * Puts one terminal through both sides and records any disagreement — with the row's ruled
     * verdict, and with each other. For a hugging row the precondition is that the predicate resolved
     * the intended face and clearance, and that the assessor's description names them, so a row that
     * hugs for some other reason cannot pass.
     */
    private static void check(String label, boolean expected, String face, double stub,
                              RoutingRect elem, RoutingRect other, List<AbsoluteBendpointDto> route,
                              boolean sourceEnd, List<String> mismatches) throws Exception {
        RoutingRect source = sourceEnd ? elem : other;
        RoutingRect target = sourceEnd ? other : elem;
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c", source, target, List.of(), "", 0);

        boolean router = routerTerminalHugs(conn, route, sourceEnd);

        // The assessor gets what the collector hands it: an analysis blend half a pixel off on both axes
        // (as beside odd-sized ends) and the drawn integer points. The verdict must come from the drawn
        // ones — on the blend the 8 px row would read 7.5 px, a hug.
        List<double[]> path = new ArrayList<>();
        List<double[]> drawn = new ArrayList<>();
        path.add(new double[]{source.x() + source.width() / 2.0, source.y() + source.height() / 2.0});
        for (AbsoluteBendpointDto bp : route) {
            path.add(new double[]{bp.x() + 0.5, bp.y() + 0.5});
            drawn.add(new double[]{bp.x(), bp.y()});
        }
        path.add(new double[]{target.x() + target.width() / 2.0, target.y() + target.height() / 2.0});
        AssessmentConnection ac = new AssessmentConnection("c", source.id(), target.id(), path, "", 0,
                0, 0.0, 0.0, drawn);
        LayoutQualityAssessor.OffFaceParallelTerminalResult result = new LayoutQualityAssessor()
                .countOffFaceParallelTerminals(List.of(ac), List.of(node(source), node(target)), true);
        boolean assessor = result.count() == 1;

        if (router != expected || assessor != expected) {
            mismatches.add(label + ": ruled " + expected + ", router " + router + ", assessor " + assessor);
            return;
        }
        if (expected) {
            AbsoluteBendpointDto term = sourceEnd ? route.get(0) : route.get(route.size() - 1);
            AbsoluteBendpointDto next = route.size() >= 2
                    ? (sourceEnd ? route.get(1) : route.get(route.size() - 2)) : null;
            double nx = next != null ? next.x() : other.x() + other.width() / 2.0;
            double ny = next != null ? next.y() : other.y() + other.height() / 2.0;
            OffFaceHug.Hug hug = OffFaceHug.evaluate(term.x(), term.y(), nx, ny,
                    elem.x(), elem.y(), elem.width(), elem.height());
            String desc = result.descriptions().isEmpty() ? "" : result.descriptions().get(0);
            String side = sourceEnd ? "source" : "target";
            String clearance = "(" + String.format(Locale.ROOT, "%.1f", stub) + "px clearance";
            if (hug == null || !face.equals(hug.face().name()) || Math.abs(hug.stub() - stub) > 1e-9
                    || !desc.contains(side + " terminal departs the " + face + " face")
                    || !desc.contains(clearance)) {
                mismatches.add(label + ": precondition — expected " + side + " " + face + " " + clearance
                        + ", predicate " + hug + ", description: " + desc);
            }
        }
    }

    private static boolean routerTerminalHugs(RoutingPipeline.ConnectionEndpoints conn,
                                              List<AbsoluteBendpointDto> route, boolean sourceEnd)
            throws Exception {
        Method m = TerminalEgressClearancePass.class.getDeclaredMethod("terminalHugs",
                RoutingPipeline.ConnectionEndpoints.class, List.class, boolean.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, conn, route, sourceEnd);
    }

    private static AssessmentNode node(RoutingRect r) {
        return new AssessmentNode(r.id(), r.x(), r.y(), r.width(), r.height(), null, false, false,
                "", 0.0, null, null, 0.0, 0.0, 0.0);
    }
}
