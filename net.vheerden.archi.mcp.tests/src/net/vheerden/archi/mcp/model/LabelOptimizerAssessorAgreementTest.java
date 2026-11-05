package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.LabelBoxEstimate;
import net.vheerden.archi.mcp.model.routing.LabelPositionOptimizer;
import net.vheerden.archi.mcp.model.routing.OptimizerScores;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The label optimizer and the layout-quality assessor agree on where a label is clear: the optimizer
 * scores the box the assessor measures, so the slot or offset it picks is one the assessor counts as
 * clear. Each fixture puts a blocker in the strip between the raw char-count box and the
 * render-calibrated one, where a narrower scoring box would call the label clear.
 *
 * <p>The label is 30 characters: it reserves 250 px and renders 334 px, 42 px more on each side. Each
 * blocker below is 15 px clear of the reserved box and inside the rendered one, and each test first
 * shows the assessor counting the label on the slot or offset the optimizer turns down.</p>
 */
public class LabelOptimizerAssessorAgreementTest {

    private static final String LABEL = "abcdefghijklmnopqrstuvwxyzabcd";
    private static final int CENTRE = RelativePositionFeature.CENTER;
    /** Archi's text-anchor bits for a label lifted West or East of its line. */
    private static final int WEST = 8;
    private static final int EAST = 16;

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    @Test
    public void optimizer_movesOffAMiddleSlotTheAssessorCountsOnABox() {
        // Source (0,0 100x50) to target (1000,0 100x50) along y = 25. The Middle label centres on x 550:
        // it renders x 383..717 (inset 393..707), onto the blocker at x 690..720, while its raw box
        // (x 425..675) is 15 px clear of it. The Source slot renders x 33..367: on no third party, and
        // only a fifth of it on its own source box (under the 0.30 bar). Ties keep the current slot, so
        // only a scorer that sees the blocker moves the label.
        RoutingRect source = new RoutingRect(0, 0, 100, 50, "s");
        RoutingRect target = new RoutingRect(1000, 0, 100, 50, "t");
        RoutingRect blocker = new RoutingRect(690, 27, 30, 50, "blocker");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(blocker), LABEL, 1);
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(100, 25), new AbsoluteBendpointDto(1000, 25));

        LabelPositionOptimizer.MultiTrialResult result = new LabelPositionOptimizer().optimizeMultiTrial(
                List.of(conn), List.of(path), List.of(source, target, blocker),
                Map.of("c1", Set.of("s", "t")), 1, new Random(0));
        int chosen = result.allPositions().get("c1");
        int offset = result.offsets().getOrDefault("c1", CENTRE);

        assertEquals("the assessor counts the Middle label on the blocker", 1,
                assessedOverlaps(conn, path, 1, CENTRE, source, target, blocker));
        assertEquals("the assessor counts nothing on the chosen slot", 0,
                assessedOverlaps(conn, path, chosen, offset, source, target, blocker));
        assertEquals("the label moves to Source", 0, chosen);
        assertEquals("and needs no offset there", CENTRE, offset);
    }

    @Test
    public void optimizer_offsetsAMiddleLabelWhereTheAssessorScoresItClear() {
        // A vertical connection (source 400,0 100x60; target 400,400 100x60) with its Middle label on a
        // small box at the line (x 439..446), so the label needs an offset. On a vertical host West is
        // tried first. West draws the rendered label at x 111..445, onto the box at x 140..180; the raw
        // box would draw at x 195..445, 15 px clear of it. East draws it at x 455..789, clear of both
        // boxes and 9 px from the small one, outside the 5 px near-miss band.
        RoutingRect source = new RoutingRect(400, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(400, 400, 100, 60, "t");
        RoutingRect onLine = new RoutingRect(439, 222, 7, 16, "onLine");
        RoutingRect west = new RoutingRect(140, 200, 40, 60, "west");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(onLine, west), LABEL, 1);
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(450, 60), new AbsoluteBendpointDto(450, 400));

        Map<String, Integer> offsets = new LabelPositionOptimizer().computeOffsetsForPositions(
                List.of(conn), Map.of("c1", path), List.of(source, target, onLine, west),
                Map.of("c1", Set.of("s", "t")), Map.of("c1", 1));
        int offset = offsets.getOrDefault("c1", CENTRE);

        assertEquals("the assessor counts a West label on both boxes", 2,
                assessedOverlaps(conn, path, 1, WEST, source, target, onLine, west));
        assertEquals("the assessor counts nothing where the offset draws the label", 0,
                assessedOverlaps(conn, path, 1, offset, source, target, onLine, west));
        assertEquals("the label is offset East, not West, which only a narrower box clears", EAST, offset);
    }

    @Test
    public void elkEdgeLabelHeight_isTheSharedLabelHeight() throws Exception {
        Field height = ElkLayoutEngine.class.getDeclaredField("EDGE_LABEL_HEIGHT");
        height.setAccessible(true);
        assertEquals(LabelBoxEstimate.HEIGHT, height.getDouble(null), 0.0);
    }

    /**
     * The two agree on which other connections' lines a label lies on: for every labelled connection, at the
     * slot the optimizer picks and at the other two, the lines the optimizer charges it for are the lines the
     * assessor names. The optimizer's charge for one line is its total score with that line added, less its
     * score with the label alone.
     */
    @Test
    public void foreignLines_theOptimizerChargesTheLinesTheAssessorNames() {
        RoutingRect a = new RoutingRect(0, 400, 100, 60, "A");
        RoutingRect b = new RoutingRect(500, 400, 100, 60, "B");
        RoutingRect c = new RoutingRect(270, 100, 60, 60, "c");
        RoutingRect d = new RoutingRect(270, 700, 60, 60, "d");
        RoutingRect cClear = new RoutingRect(380, 100, 60, 60, "c");
        RoutingRect dClear = new RoutingRect(380, 700, 60, 60, "d");
        RoutingRect x = new RoutingRect(0, 420, 100, 60, "X");
        RoutingRect hub = new RoutingRect(500, 400, 100, 100, "H");
        RoutingRect w = new RoutingRect(390, 700, 60, 60, "W");

        Map<String, List<Entry>> fixtures = new LinkedHashMap<>();
        // A label at Middle on an unlabelled line down x 300; Source and Target are clear of it.
        fixtures.put("on a line", List.of(new Entry(endpoints("l", a, b, "sends orders", 1), List.of()),
                new Entry(endpoints("f", c, d, "", 1), List.of())));
        // Both labelled: each one's Middle is on the other's line.
        fixtures.put("both labelled", List.of(new Entry(endpoints("l", a, b, "sends orders", 1), List.of()),
                new Entry(endpoints("f", c, d, "routes cash", 1), List.of())));
        // A Target label on the hub's west end, crossed by a sibling leaving the same face 12 px lower.
        fixtures.put("hub", List.of(new Entry(endpoints("l", x, hub, "sends orders", 2), List.of()),
                new Entry(endpoints("s", hub, w, "", 1), List.of(new AbsoluteBendpointDto(420, 462)))));
        // The line moved to x 410: clear of the Middle and Source slots, through the Target slot.
        fixtures.put("clear", List.of(new Entry(endpoints("l", a, b, "sends orders", 1), List.of()),
                new Entry(endpoints("f", cClear, dClear, "", 1), List.of())));

        List<String> mismatches = new ArrayList<>();
        Set<String> hits = new TreeSet<>();
        for (Map.Entry<String, List<Entry>> fixture : fixtures.entrySet()) {
            List<Entry> entries = fixture.getValue();
            Map<String, Integer> picked = pick(entries);
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).conn().labelText().isEmpty()) {
                    continue;
                }
                for (int position : new int[]{picked.get(entries.get(i).conn().connectionId()), 0, 1, 2}) {
                    Set<String> charged = chargedLines(entries, i, position);
                    Set<String> named = namedLines(entries, i, position);
                    if (!named.isEmpty()) {
                        hits.add(fixture.getKey() + " " + entries.get(i).conn().connectionId() + "@" + position
                                + " " + named);
                    }
                    if (!charged.equals(named)) {
                        mismatches.add(fixture.getKey() + " " + entries.get(i).conn().connectionId() + "@"
                                + position + ": optimizer " + charged + ", assessor " + named);
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches);
        assertEquals("the table must hold hits, not only clear slots",
                Set.of("both labelled f@1 [l]", "both labelled l@1 [f]", "clear l@2 [f]", "hub l@2 [s]",
                        "on a line l@1 [f]"),
                hits);
    }

    @Test
    public void foreignLines_theOptimizerPicksASlotTheAssessorFindsClear() {
        RoutingRect a = new RoutingRect(0, 400, 100, 60, "A");
        RoutingRect b = new RoutingRect(500, 400, 100, 60, "B");
        List<Entry> entries = List.of(new Entry(endpoints("l", a, b, "sends orders", 1), List.of()),
                new Entry(endpoints("f", new RoutingRect(270, 100, 60, 60, "c"),
                        new RoutingRect(270, 700, 60, 60, "d"), "", 1), List.of()));

        int chosen = pick(entries).get("l");

        assertEquals("the assessor names the line at Middle", Set.of("f"), namedLines(entries, 0, 1));
        assertEquals("and nothing where the optimizer puts the label", Set.of(), namedLines(entries, 0, chosen));
        assertEquals("the label moves to Source", 0, chosen);
    }

    /** One optimizer entry and its bendpoints. */
    private record Entry(RoutingPipeline.ConnectionEndpoints conn, List<AbsoluteBendpointDto> path) {}

    private static RoutingPipeline.ConnectionEndpoints endpoints(String id, RoutingRect source, RoutingRect target,
            String label, int textPosition) {
        return new RoutingPipeline.ConnectionEndpoints(id, source, target, List.of(), label, textPosition);
    }

    /** The optimizer's picks over the fixture, every endpoint box an obstacle. */
    private static Map<String, Integer> pick(List<Entry> entries) {
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        Map<String, RoutingRect> boxes = new LinkedHashMap<>();
        Map<String, Set<String>> excludes = new HashMap<>();
        for (Entry e : entries) {
            conns.add(e.conn());
            paths.add(e.path());
            boxes.put(e.conn().source().id(), e.conn().source());
            boxes.put(e.conn().target().id(), e.conn().target());
            excludes.put(e.conn().connectionId(), Set.of(e.conn().source().id(), e.conn().target().id()));
        }
        return new LabelPositionOptimizer().optimizeMultiTrial(conns, paths, new ArrayList<>(boxes.values()),
                excludes, 5, new Random(1)).allPositions();
    }

    /** The other connections whose line the optimizer charges entry {@code i}'s label for at {@code position}. */
    private static Set<String> chargedLines(List<Entry> entries, int i, int position) {
        Entry own = entries.get(i);
        String id = own.conn().connectionId();
        Map<String, Integer> at = Map.of(id, position);
        Map<String, Set<String>> excludes = Map.of(id, Set.of());
        double alone = OptimizerScores.totalScore(at, List.of(own.conn()), List.of(own.path()), List.of(),
                excludes);
        Set<String> charged = new TreeSet<>();
        for (int j = 0; j < entries.size(); j++) {
            if (j == i) {
                continue;
            }
            Entry other = entries.get(j);
            RoutingPipeline.ConnectionEndpoints line = endpoints(other.conn().connectionId(),
                    other.conn().source(), other.conn().target(), "", 1);
            double with = OptimizerScores.totalScore(at, List.of(own.conn(), line),
                    List.of(own.path(), other.path()), List.of(), excludes);
            if (with - alone == 1.0) {
                charged.add(line.connectionId());
            } else if (with != alone) {
                charged.add(line.connectionId() + " charged " + (with - alone));
            }
        }
        return charged;
    }

    /** The other connections whose line the assessor names entry {@code i}'s label on, at {@code position}. */
    private Set<String> namedLines(List<Entry> entries, int i, int position) {
        List<AssessmentConnection> assessed = new ArrayList<>();
        Map<String, AssessmentNode> endpointById = new HashMap<>();
        for (int j = 0; j < entries.size(); j++) {
            RoutingPipeline.ConnectionEndpoints conn = entries.get(j).conn();
            List<double[]> points = new ArrayList<>();
            points.add(new double[]{conn.source().centerX(), conn.source().centerY()});
            for (AbsoluteBendpointDto bp : entries.get(j).path()) {
                points.add(new double[]{bp.x(), bp.y()});
            }
            points.add(new double[]{conn.target().centerX(), conn.target().centerY()});
            assessed.add(new AssessmentConnection(conn.connectionId(), conn.source().id(), conn.target().id(),
                    points, j == i ? conn.labelText() : "", j == i ? position : 1, CENTRE));
            for (RoutingRect box : List.of(conn.source(), conn.target())) {
                endpointById.put(box.id(), new AssessmentNode(box.id(), box.x(), box.y(), box.width(),
                        box.height(), null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0));
            }
        }
        Set<String> named = new TreeSet<>();
        String prefix = "' lies on the line of connection '";
        for (String description : assessor.countLabelOnForeignLine(assessed, false, endpointById).descriptions()) {
            int at = description.indexOf(prefix);
            named.add(description.substring(at + prefix.length(), description.length() - 1));
        }
        return named;
    }

    /**
     * The two move an offset Middle label by the same whole pixels on each axis, for every label length. A
     * label of n characters renders 10.8n + 10 px wide; for n = 2, 7, 12 that width rounds up to an even
     * number from an odd floor (31.6, 85.6, 139.6), which is where halving the rounded width and halving the
     * unrounded one part by a pixel. 5 is a length where they agree either way; 4 and 9 (53.2, 107.2) have an
     * odd floor and a fraction under a half, so a rule that rounds the width up parts from the assessor there. The shift is measured (drawn
     * box less the Centre box), not the position, because the two boxes' own x differs by under half a pixel
     * (whole-pixel versus unrounded) on every label, offset or not.
     */
    @Test
    public void offsetShift_isTheSameWholePixelsInTheOptimizerAndTheAssessor() {
        RoutingRect source = new RoutingRect(0, 0, 100, 50, "s");
        RoutingRect target = new RoutingRect(400, 0, 100, 50, "t");
        Map<String, AssessmentNode> ends = Map.of("s", node(source), "t", node(target));
        int[] anchors = {EAST, WEST, NORTH, NORTH | EAST};
        String[] names = {"EAST", "WEST", "NORTH", "NORTH_EAST"};

        List<String> mismatches = new ArrayList<>();
        for (int length : new int[]{2, 4, 5, 7, 9, 12}) {
            String label = "x".repeat(length);
            RoutingRect optimizerCentre = OptimizerScores.drawnMiddleLabel(List.of(), source, target, label,
                    CENTRE);
            LayoutQualityAssessor.LabelBounds assessorCentre = assessor.drawnLabelBounds(
                    middle(label, CENTRE, source, target), ends);
            for (int a = 0; a < anchors.length; a++) {
                RoutingRect optimizer = OptimizerScores.drawnMiddleLabel(List.of(), source, target, label,
                        anchors[a]);
                LayoutQualityAssessor.LabelBounds assessed = assessor.drawnLabelBounds(
                        middle(label, anchors[a], source, target), ends);
                double optimizerDx = optimizer.x() - optimizerCentre.x();
                double optimizerDy = optimizer.y() - optimizerCentre.y();
                double assessorDx = assessed.x() - assessorCentre.x();
                double assessorDy = assessed.y() - assessorCentre.y();
                if (optimizerDx != assessorDx || optimizerDy != assessorDy) {
                    mismatches.add("length " + length + " " + names[a] + ": optimizer (" + optimizerDx + ", "
                            + optimizerDy + "), assessor (" + assessorDx + ", " + assessorDy + ")");
                }
            }
        }
        assertEquals(List.of(), mismatches);
    }

    /**
     * A 7-character label (85.6 px, 86 whole) on the Middle of a 300 px line, offset East, beside a box whose
     * left edge is 1 px inside where the optimizer's drawn inset ends. The optimizer draws it 86 / 2 + 5 =
     * 48 px East: x 255..341, inset to 331, on the box at x 330. Halving the unrounded width would draw it
     * 47 px East, inset to 329.8, clear. Both must count it on the box.
     */
    @Test
    public void offsetLabelAtABoxEdge_isOnTheBoxForBoth() {
        RoutingRect source = new RoutingRect(0, 0, 100, 50, "s");
        RoutingRect target = new RoutingRect(400, 0, 100, 50, "t");
        RoutingRect box = new RoutingRect(330, 0, 40, 50, "box");
        String label = "x".repeat(7);

        RoutingRect optimizer = OptimizerScores.drawnMiddleLabel(List.of(), source, target, label, EAST);
        assertEquals(new RoutingRect(255, 15, 86, 20, null), optimizer);
        assertEquals("the optimizer scores it on the box", true, OptimizerScores.onBox(optimizer, box));
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(box), label, 1);
        // The assessor's count also takes a near miss, so it is the description that says which: on the box
        // (the optimizer's 1.0), not merely too close to it (0.5).
        assertEquals("the assessor counts it on the box",
                List.of("Label on connection 'c1' overlaps element 'box'"),
                assessedDescriptions(conn, List.of(), 1, EAST, source, target, box));
    }

    private static final int NORTH = 1;

    private static AssessmentConnection middle(String label, int anchor, RoutingRect source, RoutingRect target) {
        return new AssessmentConnection("c1", source.id(), target.id(),
                List.of(new double[]{source.centerX(), source.centerY()},
                        new double[]{target.centerX(), target.centerY()}),
                label, 1, anchor);
    }

    private static AssessmentNode node(RoutingRect box) {
        return new AssessmentNode(box.id(), box.x(), box.y(), box.width(), box.height(),
                null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    /** The assessor's label count for the connection at the given slot and offset. */
    private int assessedOverlaps(RoutingPipeline.ConnectionEndpoints conn, List<AbsoluteBendpointDto> path,
            int textPosition, int offset, RoutingRect... boxes) {
        return assessedResult(conn, path, textPosition, offset, boxes).count();
    }

    private List<String> assessedDescriptions(RoutingPipeline.ConnectionEndpoints conn,
            List<AbsoluteBendpointDto> path, int textPosition, int offset, RoutingRect... boxes) {
        return assessedResult(conn, path, textPosition, offset, boxes).descriptions();
    }

    private LayoutQualityAssessor.LabelOverlapResult assessedResult(RoutingPipeline.ConnectionEndpoints conn,
            List<AbsoluteBendpointDto> path, int textPosition, int offset, RoutingRect... boxes) {
        List<double[]> points = new java.util.ArrayList<>();
        points.add(new double[]{conn.source().centerX(), conn.source().centerY()});
        for (AbsoluteBendpointDto bp : path) {
            points.add(new double[]{bp.x(), bp.y()});
        }
        points.add(new double[]{conn.target().centerX(), conn.target().centerY()});
        AssessmentConnection assessed = new AssessmentConnection(conn.connectionId(),
                conn.source().id(), conn.target().id(), points, conn.labelText(), textPosition, offset);

        List<AssessmentNode> nodes = new java.util.ArrayList<>();
        for (RoutingRect box : boxes) {
            nodes.add(new AssessmentNode(box.id(), box.x(), box.y(), box.width(), box.height(),
                    null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0));
        }
        return assessor.countLabelOverlaps(List.of(assessed), nodes);
    }
}
