package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
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

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Pins the frame the label optimiser reasons in.
 *
 * <p>The optimiser scores a candidate label box against the connection's path and the surrounding
 * element rectangles. Those rectangles are absolute canvas coordinates. The path has to be too, or
 * the clearances computed between them are comparisons between two different coordinate systems and
 * mean nothing.
 *
 * <p>A bendpoint is not stored as a canvas coordinate: Archi holds it as two offsets, one from each
 * endpoint centre. Reading the source-anchored offset and using it as a canvas point displaces the
 * whole path by the source centre — for the element used here, by several hundred pixels, which is
 * far enough that the path leaves the region the rectangles occupy entirely.
 *
 * <p>The fixture deliberately places the source element well away from the canvas origin and gives
 * the connection bendpoints. Both matter: the optimiser builds its rectangle as source centre → path
 * → target centre, so a connection with no bendpoints has an empty path and is unaffected by the
 * frame at all. A fixture without bendpoints cannot detect this.
 *
 * <p>Pure EMF, no figure realized, connection left unnamed so nothing reaches text measurement —
 * which throws a caught exception on macOS but an uncaught error on Linux, so a green local run
 * would otherwise not establish headless safety.
 */
public class LabelOptimizationPassTest {

    /** Source box, deliberately far from the canvas origin: centre (660, 327). */
    private static final int SRC_X = 600;
    private static final int SRC_Y = 300;
    private static final int SRC_W = 120;
    private static final int SRC_H = 55;
    /** Target box: centre (1060, 527). */
    private static final int TGT_X = 1000;
    private static final int TGT_Y = 500;
    private static final int TGT_W = 120;
    private static final int TGT_H = 55;

    private static final int SRC_CENTRE_X = SRC_X + SRC_W / 2;
    private static final int SRC_CENTRE_Y = SRC_Y + SRC_H / 2;
    private static final int TGT_CENTRE_X = TGT_X + TGT_W / 2;
    private static final int TGT_CENTRE_Y = TGT_Y + TGT_H / 2;

    private IArchimateFactory factory;
    private IDiagramModelArchimateConnection connection;
    private AssessmentNode srcNode;
    private AssessmentNode tgtNode;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IArchimateElement source = factory.createApplicationComponent();
        IArchimateElement target = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(source);
        model.getFolder(FolderType.APPLICATION).getElements().add(target);

        IArchimateRelationship rel = factory.createServingRelationship();
        rel.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IDiagramModelArchimateObject sourceViewObj = factory.createDiagramModelArchimateObject();
        sourceViewObj.setId("src");
        sourceViewObj.setArchimateElement(source);
        sourceViewObj.setBounds(SRC_X, SRC_Y, SRC_W, SRC_H);
        view.getChildren().add(sourceViewObj);

        IDiagramModelArchimateObject targetViewObj = factory.createDiagramModelArchimateObject();
        targetViewObj.setId("tgt");
        targetViewObj.setArchimateElement(target);
        targetViewObj.setBounds(TGT_X, TGT_Y, TGT_W, TGT_H);
        view.getChildren().add(targetViewObj);

        connection = factory.createDiagramModelArchimateConnection();
        connection.setId("c1");
        connection.setArchimateRelationship(rel);
        connection.setNameVisible(false);
        connection.connect(sourceViewObj, targetViewObj);

        srcNode = node("src", SRC_X, SRC_Y, SRC_W, SRC_H);
        tgtNode = node("tgt", TGT_X, TGT_Y, TGT_W, TGT_H);
    }

    /**
     * The reconstructed path lands in canvas space, beside the elements it will be scored against.
     *
     * <p>Two bendpoints are written through both anchors from known absolute points, so their two
     * stored reconstructions agree and the weight cannot affect the answer — this pins the frame,
     * not the interpolation.
     */
    @Test
    public void shouldReconstructBendpointsAsCanvasPoints_notAsRawSourceOffsets() {
        addBendpointAt(660, 527);
        addBendpointAt(900, 527);

        List<AbsoluteBendpointDto> path =
                LabelOptimizationPass.absolutePathOf(connection, srcNode, tgtNode);

        assertEquals(2, path.size());
        assertEquals(660, path.get(0).x());
        assertEquals(527, path.get(0).y());
        assertEquals(900, path.get(1).x());
        assertEquals(527, path.get(1).y());
    }

    /**
     * The path shares a frame with the rectangles the optimiser scores against.
     *
     * <p>Stated as a containment claim rather than as coordinates, because that is the property the
     * optimiser actually depends on. Reading the raw source offset would put every point within a
     * couple of hundred pixels of the origin while the elements sit past x = 600, so the path would
     * miss the region entirely and every clearance computed from it would be spurious.
     */
    @Test
    public void shouldPlaceThePathInsideTheRegionTheElementsOccupy() {
        addBendpointAt(660, 527);
        addBendpointAt(900, 527);

        List<AbsoluteBendpointDto> path =
                LabelOptimizationPass.absolutePathOf(connection, srcNode, tgtNode);

        int left = Math.min(SRC_X, TGT_X);
        int right = Math.max(SRC_X + SRC_W, TGT_X + TGT_W);
        int top = Math.min(SRC_Y, TGT_Y);
        int bottom = Math.max(SRC_Y + SRC_H, TGT_Y + TGT_H);
        for (AbsoluteBendpointDto bp : path) {
            assertTrue("x=" + bp.x() + " must lie between the two elements",
                    bp.x() >= left && bp.x() <= right);
            assertTrue("y=" + bp.y() + " must lie between the two elements",
                    bp.y() >= top && bp.y() <= bottom);
        }
    }

    /**
     * With no bendpoints the path is empty, and the optimiser closes it with the two element
     * centres on its own. This case was already correct before the frame was fixed, which is why a
     * fixture without bendpoints proves nothing about it.
     */
    @Test
    public void shouldReturnAnEmptyPath_whenTheConnectionIsStraight() {
        List<AbsoluteBendpointDto> path =
                LabelOptimizationPass.absolutePathOf(connection, srcNode, tgtNode);

        assertTrue("a straight connection stores no bendpoints", path.isEmpty());
    }

    /**
     * A drifted multi-bendpoint route resolves at the weight Archi renders with, so the optimiser
     * scores the polyline a reader sees rather than an unsheared midpoint path.
     */
    @Test
    public void shouldFollowTheRenderWeight_whenTheStoredAnchorsDisagree() {
        // Both bendpoints reconstruct to x = 700 from the source anchor and x = 760 from the target.
        addBendpoint(700 - SRC_CENTRE_X, 527 - SRC_CENTRE_Y, 760 - TGT_CENTRE_X, 527 - TGT_CENTRE_Y);
        addBendpoint(700 - SRC_CENTRE_X, 527 - SRC_CENTRE_Y, 760 - TGT_CENTRE_X, 527 - TGT_CENTRE_Y);

        List<AbsoluteBendpointDto> path =
                LabelOptimizationPass.absolutePathOf(connection, srcNode, tgtNode);

        assertEquals(2, path.size());
        // weight 1/3 and 2/3 of the way from 700 to 760, not 730 twice
        assertEquals(720, path.get(0).x());
        assertEquals(740, path.get(1).x());
    }

    // ---------------------------------------------------------------- fixture helpers

    /** Writes a bendpoint through both anchors, the way an absolute position is stored. */
    private void addBendpointAt(int absoluteX, int absoluteY) {
        addBendpoint(absoluteX - SRC_CENTRE_X, absoluteY - SRC_CENTRE_Y,
                absoluteX - TGT_CENTRE_X, absoluteY - TGT_CENTRE_Y);
    }

    private void addBendpoint(int startX, int startY, int endX, int endY) {
        IDiagramModelBendpoint bp = factory.createDiagramModelBendpoint();
        bp.setStartX(startX);
        bp.setStartY(startY);
        bp.setEndX(endX);
        bp.setEndY(endY);
        connection.getBendpoints().add(bp);
    }

    private static AssessmentNode node(String id, int x, int y, int w, int h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, "", 0.0,
                null, null, 0.0, 0.0, 0.0);
    }
}
