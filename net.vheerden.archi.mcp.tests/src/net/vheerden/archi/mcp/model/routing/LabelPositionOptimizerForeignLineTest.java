package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The label optimizer scores a label lying on another connection's drawn line ({@link ForeignLineHits}), in the
 * greedy pick and in the multi-trial total, never against the label's own line, and without making the position
 * unplaceable.
 *
 * <p>The main fixture is a labelled connection from a (0, 100) to b (500, 100), both 100 x 60, drawn along y 130
 * from x 100 to x 500. Its 12-character label renders 140 px wide: Middle x 230..370, y 120..140; Source (below the
 * line, just beyond a's EAST face) x 114..254, y 134..154; Target (just before b's WEST face) x 346..486,
 * y 134..154. An unlabelled connection runs straight down at x 300, through the Middle label only.</p>
 */
public class LabelPositionOptimizerForeignLineTest {

    private static final String LABEL = "sends orders";
    private static final int SOURCE = 0;
    private static final int MIDDLE = 1;
    private static final int TARGET = 2;

    private static final RoutingRect A = new RoutingRect(0, 100, 100, 60, "a");
    private static final RoutingRect B = new RoutingRect(500, 100, 100, 60, "b");

    private final LabelPositionOptimizer optimizer = new LabelPositionOptimizer();

    // ==================== the greedy pick ====================

    @Test
    public void middleOnAnUnlabelledLine_movesToTheClearSource() {
        // Before foreign lines were scored every slot scored 0 here and the tie kept Middle.
        LabelPositionOptimizer.LabelOptimizationResult result = single(
                List.of(labelled("l", A, B, MIDDLE), line("f", vertical(300, "c"), vertical(300, "d", 400))));

        assertEquals(Map.of("l", SOURCE), result.changedPositions());
        assertEquals(Set.of(), result.unresolvableLabels());
    }

    @Test
    public void positiveControl_lineFortyPixelsClear_keepsMiddle() {
        // x 410 is 40 px right of the Middle label (x 230..370). It crosses the Target slot instead, so Middle
        // and Source tie at 0 and the current position is kept.
        LabelPositionOptimizer.LabelOptimizationResult result = single(
                List.of(labelled("l", A, B, MIDDLE), line("f", vertical(410, "c"), vertical(410, "d", 400))));

        assertEquals(Map.of(), result.changedPositions());
    }

    @Test
    public void ownLineControl_theLabelsOwnLine_isNeverForeign() {
        // The Middle label is centred on its own line. Counted, it would score 1 there and move to Source.
        LabelPositionOptimizer.LabelOptimizationResult result = single(List.of(labelled("l", A, B, MIDDLE)));

        assertEquals(Map.of(), result.changedPositions());
    }

    @Test
    public void ownLineControl_aUTurnThroughTheOwnLabel_isNeverForeign() {
        // The route runs right to x 450, turns back left along y 180 to x 150, drops, and runs right again
        // under b before rising into it. The Middle label is centred on the corner (150, 180), and two of the
        // route's own segments run through it: the leg back along y 180 and the drop at x 150. None is foreign.
        List<AbsoluteBendpointDto> uTurn = List.of(bp(450, 130), bp(450, 180), bp(150, 180), bp(150, 250),
                bp(550, 250));
        RoutingPipeline.ConnectionEndpoints l = labelled("l", A, B, MIDDLE);
        RoutingRect middle = LabelPositionOptimizer.scoredLabelRect(uTurn, A, B, LABEL, MIDDLE);
        assertTrue("fixture: the route crosses its own Middle label", ForeignLineHits.crosses(middle.x(), middle.y(),
                middle.width(), middle.height(), drawn(uTurn, A, B)));

        assertEquals(0.0, optimizer.computeTotalScore(Map.of("l", MIDDLE), List.of(l), List.of(uTurn),
                List.of(A, B), Map.of("l", Set.of("a", "b"))), 0.0);
    }

    // ==================== the multi-trial total ====================

    /*
     * Two labelled lines and one unlabelled one. l1 runs straight down at x 270 from q (240, -300) to r (240, 552),
     * so its Middle label is centred on (270, 156), x 200..340, y 146..166: it overlaps l2's Source slot, and its
     * line crosses l2's Middle label. The unlabelled line at x 420 crosses l2's Target slot. l1 is the longer line,
     * so the first trial places it first: it keeps Middle (clear), and l2 then scores 1 at every slot (Source: l1's
     * label; Middle: l1's line; Target: the unlabelled line) and keeps Middle, on l1's line. A trial that places l2
     * first puts it at Source (clear), and l1 then leaves Middle for its own clear Source. Both trials overlap no
     * box and no label; they differ only in whether a label lies on a line, and the second changes two positions
     * to the first's none.
     */
    @Test
    public void multiTrial_theTrialWithTheLabelOffTheLine_wins() {
        RoutingRect q = new RoutingRect(240, -300, 60, 60, "q");
        RoutingRect r = new RoutingRect(240, 552, 60, 60, "r");
        List<RoutingPipeline.ConnectionEndpoints> entries = List.of(
                new RoutingPipeline.ConnectionEndpoints("l1", q, r, List.of(), "reads ledger", MIDDLE),
                labelled("l2", A, B, MIDDLE),
                line("f", vertical(420, "p1"), vertical(420, "p2", 540)));
        List<List<AbsoluteBendpointDto>> paths = List.of(List.of(), List.of(), List.of());
        List<RoutingRect> obstacles = List.of(A, B, q, r, vertical(420, "p1"), vertical(420, "p2", 540));
        Map<String, Set<String>> excludes = Map.of("l1", Set.of("q", "r"), "l2", Set.of("a", "b"),
                "f", Set.of("p1", "p2"));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                entries, paths, obstacles, excludes, 10, new Random(7));

        assertEquals(Map.of("l1", SOURCE, "l2", SOURCE), result.allPositions());
        assertEquals(0.0, result.totalScore(), 0.0);
        // The first trial's assignment, scored the same way, carries the one line.
        assertEquals(1.0, optimizer.computeTotalScore(Map.of("l1", MIDDLE, "l2", MIDDLE), entries, paths,
                obstacles, excludes), 0.0);
    }

    @Test
    public void totalScore_countsOneHitPerLine_notPerSegment() {
        // An S-bend down at x 260 and back up at x 340: two segments through the Middle label, one line.
        RoutingRect c = new RoutingRect(230, -200, 60, 60, "c");
        RoutingRect d = new RoutingRect(310, -200, 60, 60, "d");
        List<RoutingPipeline.ConnectionEndpoints> entries = List.of(labelled("l", A, B, MIDDLE),
                new RoutingPipeline.ConnectionEndpoints("s", c, d, List.of(), "", MIDDLE));
        List<List<AbsoluteBendpointDto>> paths = List.of(List.of(), List.of(bp(260, 300), bp(340, 300)));

        assertEquals(1.0, optimizer.computeTotalScore(Map.of("l", MIDDLE), entries, paths, List.of(A, B, c, d),
                Map.of("l", Set.of("a", "b"))), 0.0);
    }

    // ==================== the residual ====================

    @Test
    public void everySlotOnALine_isNotUnplaceable() {
        // Lines at x 184 (Source), 300 (Middle) and 416 (Target): every slot is on one line and clear of every
        // box. The label is still legible, so it is not reported, and the hide policy cannot hide it. True before
        // foreign lines were scored (nothing saw them) and kept true.
        List<RoutingPipeline.ConnectionEndpoints> entries = List.of(labelled("l", A, B, MIDDLE),
                line("s", vertical(184, "s1"), vertical(184, "s2", 400)),
                line("m", vertical(300, "m1"), vertical(300, "m2", 400)),
                line("t", vertical(416, "t1"), vertical(416, "t2", 400)));

        LabelPositionOptimizer.LabelOptimizationResult single = single(entries);
        assertEquals(Set.of(), single.unresolvableLabels());
        assertEquals("every slot ties at one line: the current one is kept", Map.of(), single.changedPositions());

        LabelPositionOptimizer.MultiTrialResult multi = optimizer.optimizeMultiTrial(entries, emptyPaths(entries),
                obstacles(entries), excludes(entries), 5, new Random(3));
        assertEquals(Set.of(), multi.unresolvableLabels());
    }

    // ==================== churn ====================

    /**
     * A corpus view with no candidate on a foreign line gives byte-identical output to the optimizer that did not
     * score foreign lines. retail-bank-application-collaboration without one connection ({@code id-2c449e66…},
     * whose line crosses three of the other labels' slots and whose label slots are crossed): 16 lines, 4
     * labelled. Every label starts at Source, then at Target, so the pick moves one label each time. The expected
     * values were measured on the optimizer before foreign lines were scored.
     */
    @Test
    public void churnGuard_aViewWithNoCandidateOnAForeignLine_isUnchanged() throws Exception {
        String a = "id-081548aa30e74580a9100d5910c6a73b";
        String b = "id-92d02236cff24af981f1d6ab07b02307";
        String c = "id-584c4c99b6414505a367b769efe1a586";
        String d = "id-c40f6398e52a4cfd90b098bbc2c01102";
        List<String> mismatches = new ArrayList<>();
        churnRow(mismatches, SOURCE, Map.of(a, 0, b, 0, c, 0, d, 1), Map.of(d, 1));
        churnRow(mismatches, TARGET, Map.of(a, 2, b, 2, c, 1, d, 2), Map.of(c, 1));
        assertEquals("rows that moved: " + mismatches, List.of(), mismatches);
    }

    private void churnRow(List<String> mismatches, int start, Map<String, Integer> all, Map<String, Integer> changed)
            throws Exception {
        ViewFixture f = ViewFixture.load("testdata/retail-bank-application-collaboration-fixture.json");
        List<RoutingPipeline.ConnectionEndpoints> fixture = f.buildConnectionEndpoints();
        List<List<double[]>> stored = f.buildStoredPaths();
        List<RoutingPipeline.ConnectionEndpoints> entries = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        Map<String, List<AbsoluteBendpointDto>> pathsById = new LinkedHashMap<>();
        Map<String, Set<String>> excludes = new LinkedHashMap<>();
        for (int i = 0; i < fixture.size(); i++) {
            RoutingPipeline.ConnectionEndpoints e = fixture.get(i);
            if (e.connectionId().equals("id-2c449e6676984070a2eb4d9c0af4dfa1")) {
                continue;
            }
            List<AbsoluteBendpointDto> path = new ArrayList<>();
            for (int k = 1; k < stored.get(i).size() - 1; k++) {
                double[] p = stored.get(i).get(k);
                path.add(bp((int) Math.round(p[0]), (int) Math.round(p[1])));
            }
            entries.add(new RoutingPipeline.ConnectionEndpoints(e.connectionId(), e.source(), e.target(),
                    e.obstacles(), e.labelText(), start, e.groupBoundaries(), e.relativePosition()));
            paths.add(path);
            pathsById.put(e.connectionId(), path);
            excludes.put(e.connectionId(), Set.of(e.source().id(), e.target().id()));
        }
        assertEquals(16, entries.size());
        // The precondition: no candidate slot of any label is crossed by another line.
        for (int i = 0; i < entries.size(); i++) {
            RoutingPipeline.ConnectionEndpoints e = entries.get(i);
            for (int pos = 0; pos <= 2; pos++) {
                RoutingRect box = LabelPositionOptimizer.scoredLabelRect(paths.get(i), e.source(), e.target(),
                        e.labelText(), pos);
                for (int j = 0; box != null && j < entries.size(); j++) {
                    RoutingPipeline.ConnectionEndpoints o = entries.get(j);
                    if (j != i && ForeignLineHits.crosses(box.x(), box.y(), box.width(), box.height(),
                            drawn(paths.get(j), o.source(), o.target()))) {
                        mismatches.add("precondition: " + e.connectionId() + " slot " + pos + " is on "
                                + o.connectionId());
                    }
                }
            }
        }
        List<RoutingRect> obstacles = f.buildAllObstacles();
        LabelPositionOptimizer.MultiTrialResult multi = optimizer.optimizeMultiTrial(entries, paths, obstacles,
                excludes, 10, new Random(42));
        check(mismatches, start + " allPositions", all, multi.allPositions());
        check(mismatches, start + " changedPositions", changed, multi.changedPositions());
        check(mismatches, start + " offsets", Map.of(), multi.offsets());
        check(mismatches, start + " unresolvable", Set.of(), multi.unresolvableLabels());
        check(mismatches, start + " totalScore", 0.0, multi.totalScore());
        LabelPositionOptimizer.LabelOptimizationResult single = optimizer.optimizeWithResidual(entries, paths,
                obstacles, excludes);
        check(mismatches, start + " single changed", changed, single.changedPositions());
        check(mismatches, start + " single unresolvable", Set.of(), single.unresolvableLabels());
        check(mismatches, start + " position-preserving offsets", Map.of(),
                optimizer.computeOffsetsForPositions(entries, pathsById, obstacles, excludes, multi.allPositions()));
    }

    private static void check(List<String> mismatches, String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            mismatches.add(what + ": expected " + expected + " but was " + actual);
        }
    }

    // ==================== helpers ====================

    private LabelPositionOptimizer.LabelOptimizationResult single(List<RoutingPipeline.ConnectionEndpoints> entries) {
        return optimizer.optimizeWithResidual(entries, emptyPaths(entries), obstacles(entries), excludes(entries));
    }

    private static RoutingPipeline.ConnectionEndpoints labelled(String id, RoutingRect source, RoutingRect target,
            int textPosition) {
        return new RoutingPipeline.ConnectionEndpoints(id, source, target, List.of(), LABEL, textPosition);
    }

    /** A connection with no label: a line only. */
    private static RoutingPipeline.ConnectionEndpoints line(String id, RoutingRect source, RoutingRect target) {
        return new RoutingPipeline.ConnectionEndpoints(id, source, target, List.of(), "", MIDDLE);
    }

    /** A 60 x 60 box whose centre is at x {@code centreX}, with its top at y -200. */
    private static RoutingRect vertical(int centreX, String id) {
        return vertical(centreX, id, -200);
    }

    private static RoutingRect vertical(int centreX, String id, int y) {
        return new RoutingRect(centreX - 30, y, 60, 60, id);
    }

    private static List<List<AbsoluteBendpointDto>> emptyPaths(List<RoutingPipeline.ConnectionEndpoints> entries) {
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        entries.forEach(e -> paths.add(List.of()));
        return paths;
    }

    private static List<RoutingRect> obstacles(List<RoutingPipeline.ConnectionEndpoints> entries) {
        List<RoutingRect> boxes = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints e : entries) {
            boxes.add(e.source());
            boxes.add(e.target());
        }
        return boxes;
    }

    private static Map<String, Set<String>> excludes(List<RoutingPipeline.ConnectionEndpoints> entries) {
        Map<String, Set<String>> excludes = new LinkedHashMap<>();
        entries.forEach(e -> excludes.put(e.connectionId(), Set.of(e.source().id(), e.target().id())));
        return excludes;
    }

    private static AbsoluteBendpointDto bp(int x, int y) {
        return new AbsoluteBendpointDto(x, y);
    }

    private static List<double[]> drawn(List<AbsoluteBendpointDto> path, RoutingRect source, RoutingRect target) {
        return LabelAnchor.drawnPoints(LabelClearance.centrePath(path, new int[]{source.centerX(), source.centerY()},
                new int[]{target.centerX(), target.centerY()}), LabelClearance.rect(source), LabelClearance.rect(target));
    }
}
