package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
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
import com.archimatetool.model.IDiagramModelConnection;

/**
 * Exercises the anchor-drift measurement <strong>where it actually happens</strong> — in
 * {@link AssessmentCollector}, against real EMF bendpoints.
 *
 * <p>Why this class exists separately from the detector tests: those construct
 * {@link AssessmentConnection} directly and hand it literal drift values, so they pin the
 * <em>detector's</em> threshold behaviour but never execute the collector's arithmetic. A collector
 * that averaged the per-bendpoint disagreements instead of taking their maximum, or that subtracted
 * the wrong pair of anchors, would leave every one of those tests green.
 *
 * <p>Pure EMF: the view is built through {@link IArchimateFactory}, no figure is ever realized, and
 * {@code collectAssessmentConnections} is handed a hand-built node list so the SWT label-measurement
 * path in {@code collectAssessmentNodes} is never entered. Connections are left <strong>unnamed</strong>
 * — a named connection reaches text measurement, which raises a caught {@code SWTException} on macOS
 * but an uncaught {@code SWTError} on Linux, so a green local run would not establish headless safety.
 */
public class AnchorDriftCollectorTest {

    /** Source box (20,440,120,55) → centre (80.0, 467.5). */
    private static final double SRC_CENTRE_X = 80.0;
    private static final double SRC_CENTRE_Y = 467.5;
    /** Target box (210,430,121,84) → centre (270.5, 472.0). */
    private static final double TGT_CENTRE_X = 270.5;
    private static final double TGT_CENTRE_Y = 472.0;

    private IArchimateFactory factory;
    private IArchimateDiagramModel view;
    private IDiagramModelArchimateObject sourceViewObj;
    private IDiagramModelArchimateConnection connection;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        view = factory.createArchimateDiagramModel();
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IArchimateElement source = factory.createApplicationComponent();
        IArchimateElement target = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(source);
        model.getFolder(FolderType.APPLICATION).getElements().add(target);

        // Unnamed relationship, label suppressed — nothing here may reach text measurement.
        IArchimateRelationship rel = factory.createServingRelationship();
        rel.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        sourceViewObj = factory.createDiagramModelArchimateObject();
        sourceViewObj.setId("mobile");
        sourceViewObj.setArchimateElement(source);
        sourceViewObj.setBounds(20, 440, 120, 55);
        view.getChildren().add(sourceViewObj);

        IDiagramModelArchimateObject targetViewObj = factory.createDiagramModelArchimateObject();
        targetViewObj.setId("mno");
        targetViewObj.setArchimateElement(target);
        targetViewObj.setBounds(210, 430, 121, 84);
        view.getChildren().add(targetViewObj);

        connection = factory.createDiagramModelArchimateConnection();
        connection.setId("c1");
        connection.setArchimateRelationship(rel);
        connection.setNameVisible(false);
        connection.connect(sourceViewObj, targetViewObj);
    }

    /**
     * The real artefact, measured from real stored anchors rather than asserted in.
     *
     * <p>Every bendpoint of {@code Mobile App → MNO/ISP} disagrees by exactly (30.5, 10.5). The
     * fixture supplies only the four stored anchor quads; the drift is whatever the collector
     * computes from them.
     */
    @Test
    public void shouldMeasureTheStoredDisagreement_whenAnEndpointMovedAfterTheRouteWasWritten() {
        addBendpoint(61, 0, -99, -15);
        addBendpoint(92, 0, -68, -15);
        addBendpoint(92, 15, -68, 0);
        addBendpoint(99, 15, -61, 0);

        AssessmentConnection collected = collect();

        assertEquals(30.5, collected.anchorDriftX(), 1e-9);
        assertEquals(10.5, collected.anchorDriftY(), 1e-9);
    }

    /**
     * The disagreement is reported as the <strong>maximum</strong> over the bendpoints, not their
     * mean. One drifted point on an otherwise-agreeing route is still a drifted route, and a mean
     * would dilute it towards the noise floor in proportion to the route's length.
     *
     * <p>Three of the four bendpoints here sit at the 0.5 px floor and the third disagrees by 39.5.
     * That 0.5 is not slack in the fixture — it is the effect the floor is derived from, showing up
     * live: the source centre is whole (80.0) and the target centre is half-integral (270.5), and a
     * stored offset is an integer, so on this real geometry <em>exact</em> agreement is not
     * representable at all. The mean of the four is 10.25; the maximum is 39.5. A collector that
     * averaged would report a number ~4× smaller, which is what this pins.
     */
    @Test
    public void shouldReportTheWorstBendpointNotTheMean_whenOnlyOnePointDisagrees() {
        addBendpoint(0, 0, -190, -4);      // 0.5 — the representable floor on this geometry
        addBendpoint(10, 0, -180, -4);     // 0.5
        addBendpoint(50, 0, -180, -4);     // 39.5 — the one genuinely drifted point
        addBendpoint(30, 0, -200, -4);     // 0.5

        AssessmentConnection collected = collect();

        assertEquals("the worst bendpoint, not the average of the four", 39.5,
                collected.anchorDriftX(), 1e-9);
        assertNotEquals("a mean would read 10.25", 10.25, collected.anchorDriftX(), 1e-9);
    }

    /**
     * The structural claim, proved by construction rather than by reading the collector.
     *
     * <p>Two <em>different</em> stored anchor pairs that happen to share a midpoint reconstruct to
     * the <strong>identical</strong> polyline. Every detector downstream of the collector receives
     * only that polyline, so it cannot distinguish the two — the disagreement is structurally
     * invisible, not merely under-tuned, and no threshold anywhere in the assessor could surface it.
     * The two components this story adds are the only channel that survives the blend.
     */
    @Test
    public void shouldReconstructOneIdenticalPolyline_fromTwoDifferentAnchorPairs() {
        // Anchors as close to agreeing as this geometry allows: src-derived 141.0, tgt-derived 140.5.
        // The residual 0.5 is the representable floor, not fixture slack — see the test above.
        addBendpoint(61, 0, -130, -4);
        AssessmentConnection agreeing = collect();
        assertEquals("fixture sanity: these anchors sit at the floor", 0.5,
                agreeing.anchorDriftX(), 1e-9);

        // Now shift the two anchors in OPPOSITE directions by the same 30 px. That leaves the
        // midpoint of the pair untouched while opening the disagreement by 60.
        connection.getBendpoints().clear();
        addBendpoint(91, 0, -160, -4);
        AssessmentConnection drifting = collect();

        assertEquals("the disagreement is real and measured", 60.5, drifting.anchorDriftX(), 1e-9);
        assertEquals("yet the reconstructed polyline is byte-identical",
                pathOf(agreeing), pathOf(drifting));

        // And therefore every existing dimension reads the two the same way.
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        LayoutAssessmentResult withoutDrift = assessor.assess(nodes(), List.of(agreeing), true);
        LayoutAssessmentResult withDrift = assessor.assess(nodes(), List.of(drifting), true);
        assertEquals(withoutDrift.overallRating(), withDrift.overallRating());
        assertEquals(withoutDrift.zigzagCount(), withDrift.zigzagCount());
        assertEquals(withoutDrift.connectionRedundantBendpointCount(),
                withDrift.connectionRedundantBendpointCount());
        assertEquals("only the new dimension separates them", 0, withoutDrift.anchorDriftCount());
        assertEquals(1, withDrift.anchorDriftCount());
    }

    /** A connection with no stored bendpoints has no disagreement to carry, and reports none. */
    @Test
    public void shouldReportZeroDrift_whenTheConnectionHasNoBendpoints() {
        AssessmentConnection collected = collect();

        assertEquals(0.0, collected.anchorDriftX(), 1e-9);
        assertEquals(0.0, collected.anchorDriftY(), 1e-9);
    }

    /**
     * A self-referencing connection cannot exhibit drift, whatever its geometry does.
     *
     * <p>Source and target resolve to the same node, so the two centre terms are the same value and
     * cancel: the disagreement reduces to {@code |startX − endX|}, which is fixed at write time and
     * unaffected by any later move or resize. This is why the drift dimension is quiet on a lone
     * object — and equally why its degenerate-view coverage is still declared {@code not-checked}
     * rather than {@code not-applicable}: the immunity holds because a writer stores both offsets
     * consistently, which is a property of writers rather than of the shape, and this codebase
     * requires the weaker claim wherever applicability rests on such an argument.
     */
    @Test
    public void shouldReportZeroDrift_whenTheConnectionIsASelfLoop() {
        // A plain (non-ArchiMate) diagram connection: a self-loop needs no relationship, so this
        // avoids asking whether ArchiMate permits a self-referencing relationship — irrelevant here,
        // since the drift arithmetic reads only the geometry.
        IDiagramModelConnection selfLoop = factory.createDiagramModelConnection();
        selfLoop.setId("self");
        selfLoop.setNameVisible(false);
        selfLoop.connect(sourceViewObj, sourceViewObj);
        IDiagramModelBendpoint bp = factory.createDiagramModelBendpoint();
        bp.setStartX(40);
        bp.setStartY(-30);
        bp.setEndX(40);
        bp.setEndY(-30);
        selfLoop.getBendpoints().add(bp);

        List<AssessmentConnection> collected =
                AssessmentCollector.collectAssessmentConnections(view, nodes());
        AssessmentConnection self = collected.stream()
                .filter(c -> "self".equals(c.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("self-loop was not collected"));

        assertEquals(0.0, self.anchorDriftX(), 1e-9);
        assertEquals(0.0, self.anchorDriftY(), 1e-9);
        assertTrue("a lone object's self-loop IS collected — it is not written off",
                collected.size() >= 2);
    }

    // ---- Helpers ----

    /**
     * The reconstructed polyline is <strong>sheared</strong>, not translated, when the two stored
     * anchors disagree — because the collector interpolates at the weight Archi renders with,
     * {@code (i + 1) / (n + 1)}, rather than at a flat one half.
     *
     * <p>Both conditions here are load-bearing and neither alone suffices. With a single bendpoint
     * the weight <em>is</em> one half, so the two blends agree and nothing is observable. With
     * anchors that agree, every weight returns the same point, so again nothing is observable — and
     * that is the case for every path the router writes, which derives both offsets from one
     * absolute point. Only a multi-bendpoint route whose endpoints moved afterwards can see it,
     * which is exactly the population {@code anchorDriftCount} reports.
     *
     * <p>Four bendpoints reconstructing to 200.0 from the source anchor and 260.5 from the target
     * anchor. The half pixel is not fixture slack: the target centre is half-integral (270.5) and a
     * stored offset is an integer, so 260.0 is not reachable from that anchor at all — the same
     * representable floor the tests above are built on. The disagreement is therefore 60.5.
     *
     * <p>A flat blend would put all four at 230.25 and hand every detector one indistinguishable
     * point repeated four times. The render weight spreads them across the disagreement, displaced
     * from 230.25 by {@code (weight - 0.5) x 60.5}: furthest at the two terminal bendpoints, least
     * in the middle. That is what a reader sees, and the terminal segments are what the
     * terminal-geometry dimensions judge.
     */
    @Test
    public void shouldShearTheReconstructedPolyline_whenAnchorsDisagreeAcrossSeveralBendpoints() {
        // startX chosen so srcCentreX + startX == 200.0; endX so tgtCentreX + endX == 260.5.
        double toSourceAnchoredTwoHundred = 200.0 - SRC_CENTRE_X;
        double toTargetAnchoredTwoSixty = 260.5 - TGT_CENTRE_X;
        for (int i = 0; i < 4; i++) {
            addBendpoint(toSourceAnchoredTwoHundred, 0, toTargetAnchoredTwoSixty, 0);
        }

        AssessmentConnection collected = collect();

        assertEquals("the disagreement is real and measured", 60.5,
                collected.anchorDriftX(), 1e-9);

        List<double[]> path = collected.pathPoints();
        assertEquals("source centre, four bendpoints, target centre", 6, path.size());

        // weight (i + 1) / 5 of the way from 200.0 to 260.5
        assertEquals(212.1, path.get(1)[0], 1e-9);
        assertEquals(224.2, path.get(2)[0], 1e-9);
        assertEquals(236.3, path.get(3)[0], 1e-9);
        assertEquals(248.4, path.get(4)[0], 1e-9);

        // A flat one-half blend collapses all four onto the midpoint and cannot tell them apart.
        for (int i = 1; i <= 4; i++) {
            assertNotEquals("a flat blend reported one coordinate for all four",
                    230.25, path.get(i)[0], 1e-9);
        }

        // Sub-pixel precision is preserved: the centres are not rounded on the way through, which
        // is what keeps the drift above readable at the half pixel.
        assertEquals("the source centre survives untruncated", SRC_CENTRE_Y, path.get(0)[1], 1e-9);
        assertEquals("the target centre survives untruncated", TGT_CENTRE_X, path.get(5)[0], 1e-9);
    }

    private void addBendpoint(double startX, double startY, double endX, double endY) {
        IDiagramModelBendpoint bp = factory.createDiagramModelBendpoint();
        bp.setStartX((int) Math.round(startX));
        bp.setStartY((int) Math.round(startY));
        bp.setEndX((int) Math.round(endX));
        bp.setEndY((int) Math.round(endY));
        connection.getBendpoints().add(bp);
    }

    private AssessmentConnection collect() {
        List<AssessmentConnection> collected =
                AssessmentCollector.collectAssessmentConnections(view, nodes());
        return collected.stream()
                .filter(c -> "c1".equals(c.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("connection c1 was not collected"));
    }

    private static List<String> pathOf(AssessmentConnection conn) {
        List<String> out = new ArrayList<>();
        for (double[] p : conn.pathPoints()) {
            out.add(p[0] + "," + p[1]);
        }
        return out;
    }

    /** Nodes matching the EMF bounds above, so the collector's centres are the ones asserted here. */
    private static List<AssessmentNode> nodes() {
        return List.of(
                new AssessmentNode("mobile", 20, 440, 120, 55, null, false, false, "", 0.0,
                        null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("mno", 210, 430, 121, 84, null, false, false, "", 0.0,
                        null, null, 0.0, 0.0, 0.0));
    }

    static {
        // Guards the constants this class asserts against: if a fixture bound is ever edited without
        // updating the centre, the arithmetic below would silently be measuring something else.
        if (SRC_CENTRE_X != 20 + 120 / 2.0 || SRC_CENTRE_Y != 440 + 55 / 2.0
                || TGT_CENTRE_X != 210 + 121 / 2.0 || TGT_CENTRE_Y != 430 + 84 / 2.0) {
            throw new AssertionError("fixture centres do not match the fixture bounds");
        }
    }
}
