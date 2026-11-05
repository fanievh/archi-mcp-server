package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Every connection-label detector scores the label where Archi draws it, including an applied
 * "Label Offset" ({@code relativePosition}).
 *
 * <p><strong>The renderer's rule.</strong> Archi draws a Middle label with draw2d's
 * {@code ConnectionLocator}, whose {@code AbstractLocator.getNewBounds} centres the label on the
 * reference point and then, for each axis the anchor names, moves it by half its own size plus the
 * locator's gap of 5 px, so it sits 5 px clear of the point on that side. NORTH wins over SOUTH and
 * WEST over EAST when both bits are set. Source and Target labels use
 * {@code ConnectionEndpointLocator}, which ignores the anchor altogether.</p>
 *
 * <p><strong>Geometry used throughout.</strong> "Accesses" (8 characters) is
 * {@code 8 * 8 * 1.35 + 10 = 96.4} px wide and {@code 14 + 6 = 20} px high. On the Middle of
 * {@code (50,25) -> (450,25)} it centres at (250,25): x 201.8..298.2, y 15..35. The drawn shift is
 * {@code floor(96.4 / 2) + 5 = 53} px on x and {@code floor(20 / 2) + 5 = 15} px on y.
 * {@code insetRectOverlap} insets the label by {@code min(10, w/3)} and {@code min(10, h/3) = 6.67},
 * so the CENTER test rectangle is x 211.8..288.2, y 21.67..28.33. Proximity expands the OTHER
 * rectangle by 5 px and tests the raw label box.</p>
 *
 * <p>Each pin's comment gives the count before the drawn box existed (when only the own-endpoint check
 * moved the box, by a flat 40 px, and every other detector scored the un-offset box) and after.
 * Each obstacle is one that only the detector under test sees.</p>
 */
public class LayoutQualityAssessorLabelOffsetTest {

    private static final int CENTER = 2;
    private static final int NORTH = 1;
    private static final int SOUTH = 4;
    private static final int WEST = 8;
    private static final int EAST = 16;
    private static final int NORTH_WEST = NORTH | WEST;

    private static final int SOURCE = 0;
    private static final int MIDDLE = 1;

    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() {
        assessor = new LayoutQualityAssessor();
    }

    // ---- Third-party element ----

    @Test
    public void thirdPartyElement_northOffsetLiftsMiddleLabelOntoElementAbove_isReported() {
        // Element x 200..300, y -20..10. CENTER: inset y 21.67..28.33 misses it, and the raw box y 15..35
        // misses the proximity ring y -25..15 (15 < 15 is false): 0. NORTH: box y 0..20, inset 6.67..13.33
        // overlaps y -20..10: 1. Before: 0 (this loop scored the un-offset box).
        List<AssessmentNode> nodes = List.of(node("x", 200, -20, 100, 30));

        LayoutQualityAssessor.LabelOverlapResult center =
                assessor.countLabelOverlaps(List.of(accesses("c1", MIDDLE, CENTER)), nodes);
        LayoutQualityAssessor.LabelOverlapResult north =
                assessor.countLabelOverlaps(List.of(accesses("c1", MIDDLE, NORTH)), nodes);

        assertEquals(0, center.count());
        assertEquals(1, north.count());
        assertEquals(List.of("Label on connection 'c1' overlaps element 'x'"), north.descriptions());
    }

    @Test
    public void thirdPartyElement_northOffsetLiftsMiddleLabelOffElementOnTheLine_isNotReported() {
        // Element x 200..300, y 26..56. CENTER: inset y 21.67..28.33 overlaps it: 1. NORTH: box y 0..20
        // misses the proximity ring y 21..61: 0. Before: 1.
        List<AssessmentNode> nodes = List.of(node("x", 200, 26, 100, 30));

        LayoutQualityAssessor.LabelOverlapResult center =
                assessor.countLabelOverlaps(List.of(accesses("c1", MIDDLE, CENTER)), nodes);
        LayoutQualityAssessor.LabelOverlapResult north =
                assessor.countLabelOverlaps(List.of(accesses("c1", MIDDLE, NORTH)), nodes);

        assertEquals(1, center.count());
        assertEquals(List.of("Label on connection 'c1' overlaps element 'x'"), center.descriptions());
        assertEquals(0, north.count());
        assertEquals(List.of(), north.descriptions());
    }

    // ---- Label-label ----

    @Test
    public void labelLabel_northOffsetLiftsFirstLabelOntoLabelAbove_isReported() {
        // c2 "Accesses" CENTER on y = 0: box y -10..10. c1 CENTER inset y 21.67..28.33 misses it and its
        // raw box y 15..35 misses the ring y -15..15: 0. c1 NORTH: inset 6.67..13.33 overlaps -10..10: 1.
        // Before: 0.
        AssessmentConnection above = horizontal("c2", 0, "Accesses", MIDDLE, CENTER);

        LayoutQualityAssessor.LabelOverlapResult center = assessor.countLabelOverlaps(
                List.of(accesses("c1", MIDDLE, CENTER), above), List.of());
        LayoutQualityAssessor.LabelOverlapResult north = assessor.countLabelOverlaps(
                List.of(accesses("c1", MIDDLE, NORTH), above), List.of());

        assertEquals(0, center.count());
        assertEquals(1, north.count());
        assertEquals(List.of("Label on connection 'c1' overlaps label on connection 'c2'"),
                north.descriptions());
    }

    @Test
    public void labelLabel_northOffsetLiftsSecondLabelOntoLabelAbove_isReported() {
        // The same pair with the offset label second in the list, so it is the pair's OTHER rectangle:
        // c2 (y -10..10) is now the inset one, y -3.33..3.33, and c1 NORTH (y 0..20) is the raw one: they
        // overlap. CENTER c1 (y 15..35) misses the inset c2 and the ring around it: 0. Before: 0.
        AssessmentConnection above = horizontal("c2", 0, "Accesses", MIDDLE, CENTER);

        LayoutQualityAssessor.LabelOverlapResult center = assessor.countLabelOverlaps(
                List.of(above, accesses("c1", MIDDLE, CENTER)), List.of());
        LayoutQualityAssessor.LabelOverlapResult north = assessor.countLabelOverlaps(
                List.of(above, accesses("c1", MIDDLE, NORTH)), List.of());

        assertEquals(0, center.count());
        assertEquals(1, north.count());
        assertEquals(List.of("Label on connection 'c2' overlaps label on connection 'c1'"),
                north.descriptions());
    }

    @Test
    public void labelLabel_northOffsetLiftsLabelOffLabelBelow_isNotReported() {
        // c2 "Accesses" CENTER on y = 36: box y 26..46. c1 CENTER inset y 21.67..28.33 overlaps it: 1.
        // c1 NORTH: box y 0..20 misses the ring y 21..51: 0. Before: 1.
        AssessmentConnection below = horizontal("c2", 36, "Accesses", MIDDLE, CENTER);

        LayoutQualityAssessor.LabelOverlapResult center = assessor.countLabelOverlaps(
                List.of(accesses("c1", MIDDLE, CENTER), below), List.of());
        LayoutQualityAssessor.LabelOverlapResult north = assessor.countLabelOverlaps(
                List.of(accesses("c1", MIDDLE, NORTH), below), List.of());

        assertEquals(1, center.count());
        assertEquals(List.of("Label on connection 'c1' overlaps label on connection 'c2'"),
                center.descriptions());
        assertEquals(0, north.count());
        assertEquals(List.of(), north.descriptions());
    }

    // ---- Own endpoint ----

    @Test
    public void ownEndpoint_eastOffsetMovesMiddleLabelOntoItsTarget_isReported() {
        // "VeryLongLabelThatBleeds" (23 chars) is 23 * 8 * 1.35 + 10 = 258.4 wide; on the Middle of
        // (50,25) -> (500,25) it spans x 145.8..404.2, clear of a (0..100) and b (450..550): 0. The drawn
        // EAST shift is floor(129.2) + 5 = 134: x 279.8..538.2, which puts 88.2 x 20 of its 5168 px^2 on b
        // = 0.341, over the 0.30 bar (the label fits its 450 px segment, so the bar is not lowered): 1.
        // Before: the flat 40 px moved it to x 185.8..444.2, still clear: 0.
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50), node("b", 450, 0, 100, 50));
        AssessmentConnection centre = conn("c1", "a", "b", 50, 25, 500, 25, LONG, MIDDLE, CENTER);
        AssessmentConnection east = conn("c1", "a", "b", 50, 25, 500, 25, LONG, MIDDLE, EAST);

        assertEquals(0, assessor.countLabelOverlaps(List.of(centre), nodes).count());
        LayoutQualityAssessor.LabelOverlapResult r = assessor.countLabelOverlaps(List.of(east), nodes);
        assertEquals(1, r.count());
        assertEquals(List.of("Label on connection 'c1' is rendered on its own endpoint 'b'"), r.descriptions());
    }

    @Test
    public void ownEndpoint_eastOffsetClearsWideSourceWhereFortyPixelsWouldNot_isNotReported() {
        // Seven points, the middle one (379.2,25), reached by a 325 px vertical leg (so the label fits the
        // leg it hangs from): Archi centres the 258.4 px Middle label on that corner, x 250..508.4. Source a
        // spans x 0..400, so 150 x 20 / 5168 = 0.58 of the label is on it: 1. The drawn EAST shift of 134 gives x 384..642.4: 16 px on a = 0.062, under the bar, and
        // b sits far away: 0. Before: the flat 40 px gave x 290..548.4, 110 px on a = 0.43: still 1. This
        // is the East/West case where half the label's width clears and 40 px does not.
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 400, 50), node("b", 1000, 0, 100, 50));
        List<double[]> path = List.of(new double[]{200, 25}, new double[]{200, -300}, new double[]{379.2, -300},
                new double[]{379.2, 25}, new double[]{379.2, 300}, new double[]{1050, 300}, new double[]{1050, 25});
        AssessmentConnection centre = new AssessmentConnection("c1", "a", "b", path, LONG, MIDDLE, CENTER);
        AssessmentConnection east = new AssessmentConnection("c1", "a", "b", path, LONG, MIDDLE, EAST);

        LayoutQualityAssessor.LabelOverlapResult c = assessor.countLabelOverlaps(List.of(centre), nodes);
        assertEquals(1, c.count());
        assertEquals(List.of("Label on connection 'c1' is rendered on its own endpoint 'a'"), c.descriptions());
        LayoutQualityAssessor.LabelOverlapResult e = assessor.countLabelOverlaps(List.of(east), nodes);
        assertEquals(0, e.count());
        assertEquals(List.of(), e.descriptions());
    }

    @Test
    public void ownEndpoint_northOffsetOfFifteenPixelsLeavesWideLabelOnBothBoxes_isReported() {
        // The 258.4 x 20 label on the Middle of (50,25) -> (250,25) spans x 20.8..279.2, y 15..35. The
        // drawn NORTH shift is floor(10) + 5 = 15: y 0..20, still inside a (0..100 x 0..50) and b. On a:
        // 79.2 x 20 / 5168 = 0.3065. The label is wider than its 200 px segment, so the bar is 0.15: on.
        // Before: the flat 40 px gave y -25..-5, clear of both: 0. This is the North/South case where
        // 40 px would clear and the drawn lift does not.
        List<AssessmentNode> nodes = List.of(node("a", 0, 0, 100, 50), node("b", 200, 0, 100, 50));
        AssessmentConnection north = conn("c1", "a", "b", 50, 25, 250, 25, LONG, MIDDLE, NORTH);

        LayoutQualityAssessor.LabelOverlapResult r = assessor.countLabelOverlaps(List.of(north), nodes);
        assertEquals(1, r.count());
        // The second line is the short-segment guidance, which reads width and segment, not position.
        assertEquals(List.of("Label on connection 'c1' is rendered on its own endpoint 'a'",
                "Label on connection 'c1' exceeds segment length — increase spacing between a and b"),
                r.descriptions());
    }

    @Test
    public void ownEndpoint_sourceLabelIgnoresOffset_scoredAsCenter() {
        // a (0..100 x 10..40) and b (120..180 x 38..68) overlap on y 38..40, so Archi draws the line at y 39
        // from (100,39) to (120,39). The Source label starts 14 px beyond a and hangs 4 px below the line
        // (draw2d ConnectionEndpointLocator): box x 114..372.4, y 43..63. On b: 60 x 20 / 5168 = 0.232, over
        // the 0.15 bar the 20 px gap promotes it to: on. Were the NORTH offset applied, the 15 px lift (y
        // 28..48) would leave 60 x 10 / 5168 = 0.116 on b, under that bar, and cover a third of b, under the
        // box bar. Archi does not move a Source label for an offset, so NORTH must score exactly as CENTER: 1.
        List<AssessmentNode> nodes = List.of(node("a", 0, 10, 100, 30), node("b", 120, 38, 60, 30));
        AssessmentConnection centre = conn("c1", "a", "b", 50, 25, 150, 53, LONG, SOURCE, CENTER);
        AssessmentConnection north = conn("c1", "a", "b", 50, 25, 150, 53, LONG, SOURCE, NORTH);

        LayoutQualityAssessor.LabelOverlapResult c = assessor.countLabelOverlaps(List.of(centre), nodes);
        LayoutQualityAssessor.LabelOverlapResult n = assessor.countLabelOverlaps(List.of(north), nodes);
        assertEquals(1, c.count());
        assertEquals(List.of("Label on connection 'c1' is rendered on its own endpoint 'b'",
                "Label on connection 'c1' exceeds segment length — increase spacing between a and b"),
                c.descriptions());
        assertEquals(c.count(), n.count());
        assertEquals(c.descriptions(), n.descriptions());
    }

    @Test
    public void thirdPartyElement_targetLabelIgnoresOffset_scoredAsCenter() {
        // With no endpoint boxes the line ends at (450,25) and the Target label ends 14 px short of it,
        // 4 px below the line: "Accesses" box x 339.6..436, y 29..49. Element t spans x 350..430, y -20..10:
        // CENTER misses it (ring y -25..15). Were the NORTH offset applied, the box would be y 14..34, inside
        // that ring. Archi ignores the offset on a Target label, so both score 0.
        List<AssessmentNode> nodes = List.of(node("t", 350, -20, 80, 30));
        AssessmentConnection centre = conn("c1", "a", "b", 50, 25, 450, 25, "Accesses", 2, CENTER);
        AssessmentConnection north = conn("c1", "a", "b", 50, 25, 450, 25, "Accesses", 2, NORTH);

        assertEquals(0, assessor.countLabelOverlaps(List.of(centre), nodes).count());
        LayoutQualityAssessor.LabelOverlapResult n = assessor.countLabelOverlaps(List.of(north), nodes);
        assertEquals(0, n.count());
        assertEquals(List.of(), n.descriptions());
    }

    // ---- The vertical-host "no clear label position" hint ----

    @Test
    public void verticalHostHint_westOffsetMovesLabelOntoElement_isReported() {
        // Vertical path (300,0) -> (300,400): "Accesses" centres at (300,200), x 251.8..348.2,
        // y 190..210. Element x spans 180..240, y 180..220. CENTER: the inset x 261.8..338.2 misses it and
        // the raw box misses the ring x 175..245: no overlap, no hint. WEST shifts x by 53: 198.8..295.2,
        // inset 208.8..285.2, which overlaps it: the element loop counts it and the hint fires. Before:
        // count 0, no hint.
        List<AssessmentNode> nodes = List.of(node("x", 180, 180, 60, 40));
        AssessmentConnection centre = conn("c1", "a", "b", 300, 0, 300, 400, "Accesses", MIDDLE, CENTER);
        AssessmentConnection west = conn("c1", "a", "b", 300, 0, 300, 400, "Accesses", MIDDLE, WEST);

        LayoutQualityAssessor.LabelOverlapResult c = assessor.countLabelOverlaps(List.of(centre), nodes);
        assertEquals(0, c.count());
        assertEquals(List.of(), c.descriptions());
        LayoutQualityAssessor.LabelOverlapResult w = assessor.countLabelOverlaps(List.of(west), nodes);
        assertEquals(1, w.count());
        assertEquals(List.of(
                "Label on connection 'c1' overlaps element 'x'",
                "Label on connection 'c1' has no clear label position — consider repositioning nearby elements"),
                w.descriptions());
    }

    @Test
    public void verticalHostHint_westOffsetMovesLabelOffElement_isNotReported() {
        // The same vertical path with the element at x 302..342, y 180..220, just right of the line.
        // CENTER: the inset x 261.8..338.2 overlaps it: count 1 and the hint. WEST: box x 198.8..295.2
        // ends before the ring x 297..347: 0, no hint. Before: 1 and the hint.
        List<AssessmentNode> nodes = List.of(node("x", 302, 180, 40, 40));
        AssessmentConnection centre = conn("c1", "a", "b", 300, 0, 300, 400, "Accesses", MIDDLE, CENTER);
        AssessmentConnection west = conn("c1", "a", "b", 300, 0, 300, 400, "Accesses", MIDDLE, WEST);

        LayoutQualityAssessor.LabelOverlapResult c = assessor.countLabelOverlaps(List.of(centre), nodes);
        assertEquals(1, c.count());
        assertTrue(c.descriptions().contains(
                "Label on connection 'c1' has no clear label position — consider repositioning nearby elements"));
        LayoutQualityAssessor.LabelOverlapResult w = assessor.countLabelOverlaps(List.of(west), nodes);
        assertEquals(0, w.count());
        assertEquals(List.of(), w.descriptions());
    }

    // ---- Label on note ----

    @Test
    public void labelOnNote_northOffsetLiftsLabelOntoNoteAbove_isReported() {
        // Note x 200..300, y -20..10: CENTER inset y 21.67..28.33 misses it: 0. NORTH box y 0..20, inset
        // 6.67..13.33, overlaps: 1. Before: 0.
        List<AssessmentNode> notes = List.of(note("n", 200, -20, 100, 30));

        assertEquals(0, assessor.countLabelOnNote(List.of(accesses("c1", MIDDLE, CENTER)), notes, true).count());
        LayoutQualityAssessor.LabelOnNoteResult r =
                assessor.countLabelOnNote(List.of(accesses("c1", MIDDLE, NORTH)), notes, true);
        assertEquals(1, r.count());
        assertEquals(List.of("Label on connection 'c1' overlaps note 'n'"), r.descriptions());
        assertEquals(java.util.Set.of("n"), r.violatorIds());
    }

    @Test
    public void labelOnNote_northOffsetLiftsLabelOffNoteOnTheLine_isNotReported() {
        // Note x 200..300, y 26..56: CENTER inset y 21.67..28.33 overlaps: 1. NORTH box y 0..20, inset
        // 6.67..13.33, misses (this detector has no proximity arm): 0. Before: 1.
        List<AssessmentNode> notes = List.of(note("n", 200, 26, 100, 30));

        LayoutQualityAssessor.LabelOnNoteResult c =
                assessor.countLabelOnNote(List.of(accesses("c1", MIDDLE, CENTER)), notes, true);
        assertEquals(1, c.count());
        assertEquals(List.of("Label on connection 'c1' overlaps note 'n'"), c.descriptions());
        LayoutQualityAssessor.LabelOnNoteResult r =
                assessor.countLabelOnNote(List.of(accesses("c1", MIDDLE, NORTH)), notes, true);
        assertEquals(0, r.count());
        assertEquals(List.of(), r.descriptions());
        assertEquals(java.util.Set.of(), r.violatorIds());
    }

    @Test
    public void labelOnNote_northWestOffsetAppliesBothAxes() {
        // NORTH_WEST (9) moves the box by x -53 and y -15: x 148.8..245.2, y 0..20, inset x 158.8..235.2,
        // y 6.67..13.33. The note x 150..190, y -20..10 is reached only when BOTH factors apply: NORTH
        // alone leaves the inset at x 211.8..288.2 (misses x 150..190), WEST alone leaves it at
        // y 21.67..28.33 (misses y -20..10). Before: 0 for all four.
        List<AssessmentNode> notes = List.of(note("n", 150, -20, 40, 30));

        assertEquals(0, assessor.countLabelOnNote(List.of(accesses("c1", MIDDLE, CENTER)), notes, false).count());
        assertEquals(0, assessor.countLabelOnNote(List.of(accesses("c1", MIDDLE, NORTH)), notes, false).count());
        assertEquals(0, assessor.countLabelOnNote(List.of(accesses("c1", MIDDLE, WEST)), notes, false).count());
        assertEquals(1, assessor.countLabelOnNote(List.of(accesses("c1", MIDDLE, NORTH_WEST)), notes, false).count());
    }

    // ---- Label on group title band ----

    @Test
    public void labelOnGroup_southOffsetDropsLabelOntoTitleBandBelow_isReported() {
        // Named group at (100,40), 400 x 200: title band y 40..60. CENTER inset y 21.67..28.33 misses it:
        // 0. SOUTH box y 30..50, inset 36.67..43.33, overlaps the band: 1. Before: 0.
        List<AssessmentNode> nodes = List.of(namedGroup("g", 100, 40, 400, 200, "Layer"));

        assertEquals(0, assessor.countLabelOnGroup(List.of(accesses("c1", MIDDLE, CENTER)), nodes, true).count());
        LayoutQualityAssessor.LabelOnGroupResult r =
                assessor.countLabelOnGroup(List.of(accesses("c1", MIDDLE, SOUTH)), nodes, true);
        assertEquals(1, r.count());
        assertEquals(List.of("Label on connection 'c1' overlaps the title band of group 'g'"), r.descriptions());
        assertEquals(java.util.Set.of("g"), r.violatorIds());
    }

    @Test
    public void labelOnGroup_southOffsetDropsLabelOffTitleBand_isNotReported() {
        // Named group at (100,10), 400 x 200: title band y 10..30. CENTER inset y 21.67..28.33 overlaps
        // it: 1. SOUTH box y 30..50, inset 36.67..43.33, misses it: 0. Before: 1.
        List<AssessmentNode> nodes = List.of(namedGroup("g", 100, 10, 400, 200, "Layer"));

        LayoutQualityAssessor.LabelOnGroupResult c =
                assessor.countLabelOnGroup(List.of(accesses("c1", MIDDLE, CENTER)), nodes, true);
        assertEquals(1, c.count());
        assertEquals(List.of("Label on connection 'c1' overlaps the title band of group 'g'"), c.descriptions());
        LayoutQualityAssessor.LabelOnGroupResult r =
                assessor.countLabelOnGroup(List.of(accesses("c1", MIDDLE, SOUTH)), nodes, true);
        assertEquals(0, r.count());
        assertEquals(List.of(), r.descriptions());
        assertFalse(r.violatorIds().contains("g"));
    }

    // ---- The drawn box itself ----
    //
    // "Accesses" on the Middle of (50,25) -> (450,25) is x 201.8, y 15, 96.4 x 20 un-offset. These read
    // the box directly, so each literal is the renderer's rule applied to that estimate.

    @Test
    public void drawnBox_eachCardinalMovesHalfTheLabelPlusTheGap() {
        assertBox(201.8, 0, assessor.drawnLabelBounds(accesses("c1", MIDDLE, NORTH)));
        assertBox(201.8, 30, assessor.drawnLabelBounds(accesses("c1", MIDDLE, SOUTH)));
        assertBox(148.8, 15, assessor.drawnLabelBounds(accesses("c1", MIDDLE, WEST)));
        assertBox(254.8, 15, assessor.drawnLabelBounds(accesses("c1", MIDDLE, EAST)));
    }

    @Test
    public void drawnBox_diagonalMovesBothAxes() {
        assertBox(148.8, 0, assessor.drawnLabelBounds(accesses("c1", MIDDLE, NORTH_WEST)));
        assertBox(254.8, 30, assessor.drawnLabelBounds(accesses("c1", MIDDLE, SOUTH | EAST)));
    }

    @Test
    public void drawnBox_northWinsOverSouthAndWestOverEast() {
        // draw2d tests NORTH before SOUTH and WEST before EAST, so a mask naming both moves one way only.
        assertBox(201.8, 0, assessor.drawnLabelBounds(accesses("c1", MIDDLE, NORTH | SOUTH)));
        assertBox(148.8, 15, assessor.drawnLabelBounds(accesses("c1", MIDDLE, WEST | EAST)));
    }

    @Test
    public void drawnBox_centerAndMasksWithNoTestedBitDoNotMove() {
        assertBox(201.8, 15, assessor.drawnLabelBounds(accesses("c1", MIDDLE, CENTER)));
        assertBox(201.8, 15, assessor.drawnLabelBounds(accesses("c1", MIDDLE, 0)));
        assertBox(201.8, 15, assessor.drawnLabelBounds(accesses("c1", MIDDLE, 32)));
    }

    @Test
    public void drawnBox_sourceAndTargetLabelsIgnoreTheOffset() {
        // With no endpoint boxes the line's ends stay at (50,25) and (450,25), and the end segment decides
        // the face (draw2d ConnectionEndpointLocator): Source on EAST starts 14 px out, Target on WEST ends
        // 14 px short (450 - 14 - 96.4), both 4 px below the line.
        assertBox(64, 29, assessor.drawnLabelBounds(accesses("c1", SOURCE, NORTH_WEST)));
        assertBox(339.6, 29, assessor.drawnLabelBounds(accesses("c1", 2, SOUTH | EAST)));
    }

    @Test
    public void drawnBox_matchesTheEstimateExceptForTheShift() {
        // Width, height and id come from the estimate unchanged; an unlabelled connection has no box.
        LayoutQualityAssessor.LabelBounds drawn = assessor.drawnLabelBounds(accesses("c1", MIDDLE, EAST));
        LayoutQualityAssessor.LabelBounds estimate = assessor.estimateLabelBounds(accesses("c1", MIDDLE, EAST));
        assertEquals(estimate.width(), drawn.width(), 1e-9);
        assertEquals(estimate.height(), drawn.height(), 1e-9);
        assertEquals("c1", drawn.connectionId());
        assertEquals(null, assessor.drawnLabelBounds(conn("c1", "a", "b", 50, 25, 450, 25, "", MIDDLE, NORTH)));
    }

    private static void assertBox(double x, double y, LayoutQualityAssessor.LabelBounds box) {
        assertEquals("x", x, box.x(), 1e-9);
        assertEquals("y", y, box.y(), 1e-9);
        assertEquals("width", 96.4, box.width(), 1e-9);
        assertEquals("height", 20.0, box.height(), 1e-9);
    }

    // ---- fixtures ----

    private static final String LONG = "VeryLongLabelThatBleeds";

    /** "Accesses" on the Middle of (50,25) -> (450,25), between endpoints that are not on the view. */
    private static AssessmentConnection accesses(String id, int textPosition, int anchor) {
        return conn(id, "a", "b", 50, 25, 450, 25, "Accesses", textPosition, anchor);
    }

    /** A labelled horizontal connection from x 50 to x 450 at the given y. */
    private static AssessmentConnection horizontal(String id, double y, String label, int textPosition,
            int anchor) {
        return conn(id, id + "-s", id + "-t", 50, y, 450, y, label, textPosition, anchor);
    }

    private static AssessmentConnection conn(String id, String src, String tgt, double x1, double y1,
            double x2, double y2, String label, int textPosition, int anchor) {
        return new AssessmentConnection(id, src, tgt,
                List.of(new double[]{x1, y1}, new double[]{x2, y2}), label, textPosition, anchor);
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    private static AssessmentNode note(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, true, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    private static AssessmentNode namedGroup(String id, double x, double y, double w, double h, String name) {
        return new AssessmentNode(id, x, y, w, h, null, true, false, name, 0.0, null, null, 0.0, 0.0, 0.0);
    }
}
