package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.TerminalEgressClearancePass;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The off-face hug is decided on the point Archi draws, on both sides.
 *
 * <p>The router decides over the whole-pixel bendpoints it writes. The assessor's collector blends a
 * stored bendpoint's two reconstructions against untruncated centres, so on an odd-sized element its
 * analysis point sits a fraction of a pixel from the drawn one — enough to move a terminal across
 * the 8 px clearance. These cases drive the real collector (pure EMF, no figure, unnamed connection
 * so nothing reaches text measurement) and pin that the hug reads the drawn point, and the drawn-point
 * arithmetic itself against draw2d's measured rounding.
 */
public class OffFaceHugDrawnGeometryTest {

    /**
     * A terminal Archi draws exactly 8 px above an odd-height source's top face, running parallel
     * to it: clear. The analysis blend puts it 7.625 px above — a hug, by the same rule — because the
     * source's centre is half a pixel lower than the whole-pixel reference point draw2d anchors to.
     * The router, reading the written integer route, says clear; so must the assessor.
     */
    @Test
    public void terminalDrawnExactlyAtTheClearance_isClearOnBothSides_thoughTheAnalysisBlendReadsAHug()
            throws Exception {
        // Source (20,440) 120x55: top face y=440; draw2d reference centre (80, 467), analysis (80, 467.5).
        // Target (300,600) 120x60: reference centre (360, 630), whole on both.
        Fixture fx = new Fixture(20, 440, 120, 55, 300, 600, 120, 60);
        // Drawn route: (60,432) -> (360,432) along the source's top, then down to the target's top
        // margin line (360,596) — a perpendicular, clean arrival.
        fx.bendpoint(60 - 80, 432 - 467, 60 - 360, 432 - 630);
        fx.bendpoint(360 - 80, 432 - 467, 360 - 360, 432 - 630);
        fx.bendpoint(360 - 80, 596 - 467, 360 - 360, 596 - 630);

        AssessmentConnection collected = fx.collect();

        // Preconditions: the analysis blend is fractional here, and the drawn point is whole.
        assertEquals("analysis blend of the terminal, y", 432.375, collected.pathPoints().get(1)[1], 1e-9);
        assertNotNull(collected.drawnBendpoints());
        assertEquals("drawn terminal, x", 60.0, collected.drawnBendpoints().get(0)[0], 0.0);
        assertEquals("drawn terminal, y", 432.0, collected.drawnBendpoints().get(0)[1], 0.0);

        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        assertEquals("the assessor judges the drawn point: 8 px clear, no hug", 0,
                assessor.countOffFaceParallelTerminals(List.of(collected), fx.nodes(), false).count());

        // The same connection judged on the analysis blend (the arithmetic before this rule) reads a hug.
        AssessmentConnection analysisOnly = new AssessmentConnection(collected.id(),
                collected.sourceNodeId(), collected.targetNodeId(), collected.pathPoints(),
                collected.labelText(), collected.textPosition(), collected.relativePosition(),
                collected.anchorDriftX(), collected.anchorDriftY());
        assertEquals("on the analysis blend the terminal reads 7.6 px out — a hug", 1,
                assessor.countOffFaceParallelTerminals(List.of(analysisOnly), fx.nodes(), false).count());

        // And the router, on the integer route it would have written, agrees with the drawn reading.
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints("c1",
                new RoutingRect(20, 440, 120, 55, "s"), new RoutingRect(300, 600, 120, 60, "t"),
                List.of(), "", 0);
        List<AbsoluteBendpointDto> route = List.of(new AbsoluteBendpointDto(60, 432),
                new AbsoluteBendpointDto(360, 432), new AbsoluteBendpointDto(360, 596));
        assertFalse("router: 8 px clear", routerTerminalHugs(conn, route, true));
    }

    /** Agreeing reconstructions on an odd-sized element: the drawn point is the whole pixel. */
    @Test
    public void drawnCoordinate_dropsTheHalfPixelOfAnOddSizedElement() {
        // Element at 440, height 55: draw2d's reference is 440 + 27 = 467, the analysis centre 467.5.
        assertEquals(432, GeometryUtils.drawnBendpointCoordinate(-35, -198, 440, 55, 600, 60, 0, 3));
    }

    /**
     * Two agreeing reconstructions draw exactly where they agree, at every weight. Measured on a live
     * route: bendpoint 4 of 5 (weight 5/6) at x=1229. Taking the float weight times the int sum in
     * float arithmetic rounds the product to 1228.99993 and floors a pixel low; draw2d widens the
     * weight to double before multiplying.
     */
    @Test
    public void drawnCoordinate_ofAgreeingReconstructions_isWhereTheyAgree() {
        assertEquals(1229, GeometryUtils.drawnBendpointCoordinate(549, 154, 360, 640, 925, 300, 4, 5));
        for (int count = 1; count <= 12; count++) {
            for (int index = 0; index < count; index++) {
                for (int at : new int[]{-2917, -1229, 0, 1, 1229, 2917, 9973}) {
                    assertEquals("at " + at + ", bendpoint " + index + " of " + count, at,
                            GeometryUtils.drawnBendpointCoordinate(at, at, 0, 0, 0, 0, index, count));
                }
            }
        }
    }

    /** Reconstructions -120 and -121 at weight one half: -120.5 floors to -121, not truncates to -120. */
    @Test
    public void drawnCoordinate_floorsANegativeHalfPixel() {
        assertEquals(-121, GeometryUtils.drawnBendpointCoordinate(-120, -121, 0, 0, 0, 0, 0, 1));
    }

    /**
     * The weight is a float, and that decides a pixel. Reconstructions 0 and 600 at weight 5/6
     * (bendpoint 4 of 5) blend exactly to 500 in real arithmetic, but {@code (float) 5/6} is
     * 0.83333331, so draw2d computes 499.99998807907104 and floors it to 499 — beyond the 1e-9 nudge,
     * which only absorbs a double's last-bit error. A double weight would draw 500.
     */
    @Test
    public void drawnCoordinate_carriesTheWeightAsAFloat() {
        double floatWeightBlend = 600 * (double) (5 / ((float) 5 + 1));
        assertTrue("precondition: the float-weight blend lands below 500 by more than the nudge, "
                + floatWeightBlend, floatWeightBlend < 500.0 - 1e-6);
        assertEquals(499, GeometryUtils.drawnBendpointCoordinate(0, 600, 0, 0, 0, 0, 4, 5));
    }

    // ---------------------------------------------------------------- fixture

    private static boolean routerTerminalHugs(RoutingPipeline.ConnectionEndpoints conn,
                                              List<AbsoluteBendpointDto> route, boolean sourceEnd)
            throws Exception {
        Method m = TerminalEgressClearancePass.class.getDeclaredMethod("terminalHugs",
                RoutingPipeline.ConnectionEndpoints.class, List.class, boolean.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, conn, route, sourceEnd);
    }

    private static final class Fixture {
        private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        private final IArchimateDiagramModel view;
        private final IDiagramModelArchimateConnection connection;
        private final int[] src;
        private final int[] tgt;

        Fixture(int sx, int sy, int sw, int sh, int tx, int ty, int tw, int th) {
            src = new int[]{sx, sy, sw, sh};
            tgt = new int[]{tx, ty, tw, th};
            IArchimateModel model = factory.createArchimateModel();
            model.setDefaults();
            view = factory.createArchimateDiagramModel();
            model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
            IArchimateElement source = factory.createApplicationComponent();
            IArchimateElement target = factory.createApplicationComponent();
            model.getFolder(FolderType.APPLICATION).getElements().add(source);
            model.getFolder(FolderType.APPLICATION).getElements().add(target);
            IArchimateRelationship rel = factory.createServingRelationship();
            rel.connect(source, target);
            model.getFolder(FolderType.RELATIONS).getElements().add(rel);

            IDiagramModelArchimateObject s = factory.createDiagramModelArchimateObject();
            s.setId("s");
            s.setArchimateElement(source);
            s.setBounds(sx, sy, sw, sh);
            view.getChildren().add(s);
            IDiagramModelArchimateObject t = factory.createDiagramModelArchimateObject();
            t.setId("t");
            t.setArchimateElement(target);
            t.setBounds(tx, ty, tw, th);
            view.getChildren().add(t);

            connection = factory.createDiagramModelArchimateConnection();
            connection.setId("c1");
            connection.setArchimateRelationship(rel);
            connection.setNameVisible(false);
            connection.connect(s, t);
        }

        void bendpoint(int startX, int startY, int endX, int endY) {
            IDiagramModelBendpoint bp = factory.createDiagramModelBendpoint();
            bp.setStartX(startX);
            bp.setStartY(startY);
            bp.setEndX(endX);
            bp.setEndY(endY);
            connection.getBendpoints().add(bp);
        }

        List<AssessmentNode> nodes() {
            return List.of(
                    new AssessmentNode("s", src[0], src[1], src[2], src[3], null, false, false, "", 0.0,
                            null, null, 0.0, 0.0, 0.0),
                    new AssessmentNode("t", tgt[0], tgt[1], tgt[2], tgt[3], null, false, false, "", 0.0,
                            null, null, 0.0, 0.0, 0.0));
        }

        AssessmentConnection collect() {
            return AssessmentCollector.collectAssessmentConnections(view, nodes()).stream()
                    .filter(c -> "c1".equals(c.id()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("connection c1 was not collected"));
        }
    }
}
