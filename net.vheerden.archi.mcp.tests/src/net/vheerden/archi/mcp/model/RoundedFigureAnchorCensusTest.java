package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Test;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;
import net.vheerden.archi.mcp.model.routing.ViewFixture;

/**
 * Census of what a rounded figure would change in the shared orthogonal-anchor model, measured on
 * views that record each element's ArchiMate type and figure variant.
 *
 * <p>{@link GeometryUtils#orthogonalExitPoint(double, double, double, double, double, double, double[])}
 * treats every element as a plain rectangle. Archi 5.10.0's {@code OrthogonalAnchor} bands a
 * figure against its corner dimension instead ({@code getCornerDimension}: 20x20 for every figure
 * drawn through {@code RoundedRectangleFigureDelegate}, the pill of a service on its alternate
 * figure, the whole box for a Value ellipse), and anchors on the arc in two places:
 * <ul>
 *   <li><b>corner</b> — a reference outside the box on both axes: the 45-degree point of the arc,
 *       {@code corner/2 - (int)(cos(pi/4) * corner/2.0)} in from the box corner per axis.</li>
 *   <li><b>band</b> — a reference outside on one axis and within {@code corner/2} of an edge on the
 *       other: the stub stays axis-aligned, but ends on the arc, up to {@code corner/2} in from the
 *       face line.</li>
 * </ul>
 * Each end is classified by production {@link RenderFaceDeriver#anchoringOf} and Archi's bands,
 * and the point Archi draws ({@link #archiPoint}, a transcription of {@code getLocation} in its own
 * integer arithmetic) is compared with the point the shared model returns. The reference is the
 * integer point Archi hands the anchor ({@link #archiReference}), computed from the stored relative
 * bendpoints, not the assessor's interpolation: a fractional reference is never what Archi
 * anchors. An end whose point does not move cannot change any consumer. For one that moves, the stub {@code [anchor, bendpoint]} is
 * tested both ways against the diagonal-terminal rule and against every other element's rect at
 * three insets — every consumer of the shared clip (diagonal terminals, pass-through, note/image
 * crossings and grazes, corridor warnings) reads only that clipped path, so a stub whose verdict is
 * unchanged at every inset cannot move any of them — the corridor warnings included, which read the
 * same clip through {@code ConnectionThroughNoteDetector}.
 *
 * <p>The nodes carry each object's geometry and kind (group, note, {@code Grouping} container,
 * Junction) as {@code AssessmentCollector} sets them, but none of the measured inputs it reads from
 * a live view — label text widths, a note's wrapped height, an image's natural size, the per-object
 * text alignment — so the consumer counts pinned here are this census's, not what
 * {@code assess-layout} reports on the live view.
 *
 * <p>The figures are printed and every column is pinned: each figure kind by zone, then the moves
 * and the consumer counts.
 */
public class RoundedFigureAnchorCensusTest {

    static final String[] VIEWS = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"};

    /** Insets a moved stub is tested at: the pass-through inset, the bare box, and a grown box. */
    private static final double[] INSETS = {LayoutQualityAssessor.PASS_THROUGH_INSET, 0.0, -10.0};

    private static final double COSPI4 = Math.cos(Math.PI / 4);

    /** Figure kinds, in column order. */
    enum Kind { PLAIN, ARC, PILL, ELLIPSE, JUNCTION }

    /** Zones, in column order. */
    enum Zone { FACE, BAND, CORNER, INSIDE }

    /** What one view measured. */
    record Row(String name, int connections, int[][] ends, int movedEnds, int movedCorner,
               int movedBand, int m1, int m1Flips, int passThroughs, int throughVisual, int grazes,
               int crossingFlips, List<String> details) {

        int ends(Kind k) {
            int n = 0;
            for (int z = 0; z < Zone.values().length; z++) n += ends[k.ordinal()][z];
            return n;
        }

        String table() {
            StringBuilder s = new StringBuilder(String.format("%-4s conns=%d", name, connections));
            for (Kind k : Kind.values()) {
                int[] z = ends[k.ordinal()];
                s.append(String.format(" | %s %d (face %d band %d corner %d inside %d)",
                        k.name().toLowerCase(), ends(k), z[0], z[1], z[2], z[3]));
            }
            s.append(String.format(" | moved=%d (corner %d band %d) | M1=%d flips=%d"
                    + " | passThroughs=%d throughVisual=%d grazes=%d crossingFlips=%d",
                    movedEnds, movedCorner, movedBand, m1, m1Flips, passThroughs, throughVisual,
                    grazes, crossingFlips));
            return s.toString();
        }

        /** The pinned columns, in {@link #COLUMNS} order. */
        int[] figures() {
            int[] f = new int[COLUMNS.length];
            int i = 0;
            f[i++] = connections;
            for (Kind k : Kind.values()) {
                for (Zone z : Zone.values()) f[i++] = ends[k.ordinal()][z.ordinal()];
            }
            for (int v : new int[]{movedEnds, movedCorner, movedBand, m1, m1Flips, passThroughs,
                    throughVisual, grazes, crossingFlips}) {
                f[i++] = v;
            }
            return f;
        }
    }

    /** Connections, then ends per figure kind by zone, then the moves and the consumer counts. */
    static final String[] COLUMNS = columns();

    private static String[] columns() {
        List<String> c = new ArrayList<>(List.of("connections"));
        for (Kind k : Kind.values()) {
            for (Zone z : Zone.values()) c.add(k.name().toLowerCase() + " " + z.name().toLowerCase());
        }
        c.addAll(List.of("moved ends", "moved corner", "moved band", "M1", "M1 flips",
                "connectionPassThroughs", "through a note or image", "grazes", "crossing flips"));
        return c.toArray(new String[0]);
    }

    /**
     * Measured per Meridian view. Every diagram object there is at figure 0, so the only rounded
     * figure present is the 20 px arc, and every one of its 91 connection ends is a face-zone end:
     * no end anchors on the arc, so nothing a consumer reports can move on this corpus.
     */
    static final Map<String, int[]> MEASURED = Map.ofEntries(
            Map.entry("A", new int[]{29, 27, 0, 2, 0, 29, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("B", new int[]{26, 52, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("C", new int[]{13, 0, 0, 0, 0, 26, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("D", new int[]{12, 0, 0, 0, 0, 24, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("E", new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("F", new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("G", new int[]{25, 49, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0}),
            Map.entry("H", new int[]{15, 29, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("I", new int[]{6, 6, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("J", new int[]{2, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
            Map.entry("K", new int[]{14, 22, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));

    @Test
    public void census() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String v : VIEWS) {
            ViewFixture f = ViewFixture.load("testdata/meridian-" + v + "-typed-fixture.json");
            rows.add(measure(v.toUpperCase(), f));
        }
        int[] total = new int[COLUMNS.length];
        for (Row r : rows) {
            System.out.println("[rounded-census] " + r.table());
            int[] got = r.figures();
            for (int i = 0; i < got.length; i++) total[i] += got[i];
        }
        StringBuilder t = new StringBuilder("[rounded-census] TOTAL");
        for (int i = 0; i < COLUMNS.length; i++) t.append(" ").append(COLUMNS[i]).append('=').append(total[i]);
        System.out.println(t);
        for (Row r : rows) {
            for (String d : r.details()) System.out.println("[rounded-census] " + r.name() + " " + d);
        }
        assertEquals(MEASURED.size(), rows.size());
        for (Row r : rows) {
            assertPinned(r, MEASURED.get(r.name()));
        }
    }

    /**
     * Every arm the census counts, on a hand-built view: a plain source, one bendpoint, and a
     * rounded target per case, each end derived by hand from {@code OrthogonalAnchor}.
     * <ul>
     *   <li>20 px arc [50,240,206,56], bendpoint (257,221), 1 px past the right edge and 19 px
     *       above: the box corner (256,240) gives a 3.0-degree stub, not counted; the arc point
     *       (253,243) a 10.3-degree, 22.4 px stub, counted — the one diagonal-terminal flip.</li>
     *   <li>20 px arc [400,300,120,60], bendpoint (400,270): band, (400,300) to (400,310).</li>
     *   <li>Service pill [700,100,120,56] on figure 1, corner 56x56, bendpoint (650,110): band,
     *       (700,110) to (707,110).</li>
     *   <li>Value ellipse [700,300,120,56] on figure 1, bendpoint (730,250): band, (730,300) to
     *       (730,304).</li>
     *   <li>20 px arc [400,500,120,60], bendpoint (540,580) off the bottom-right corner: (520,560)
     *       to (517,557), 45 degrees either way.</li>
     * </ul>
     * Every source end is a plain face end. Two corner moves, three band moves, one flip.
     */
    @Test
    public void measure_shouldCountEveryArmOnAHandBuiltView() throws IOException {
        Row r = measure("probe", ViewFixture.load("testdata/rounded-figure-anchor-probe-fixture.json"));
        System.out.println("[rounded-census] " + r.table());
        for (String d : r.details()) System.out.println("[rounded-census] probe " + d);
        int[][] ends = new int[Kind.values().length][Zone.values().length];
        ends[Kind.PLAIN.ordinal()][Zone.FACE.ordinal()] = 5;
        ends[Kind.ARC.ordinal()][Zone.BAND.ordinal()] = 1;
        ends[Kind.ARC.ordinal()][Zone.CORNER.ordinal()] = 2;
        ends[Kind.PILL.ordinal()][Zone.BAND.ordinal()] = 1;
        ends[Kind.ELLIPSE.ordinal()][Zone.BAND.ordinal()] = 1;
        // M1 is the shared clip's own count: only the 45-degree bottom-right end
        Row want = new Row("probe", 5, ends, 5, 2, 3, 1, 1, 0, 0, 0, 0, List.of());
        assertPinned(r, want.figures());
    }

    /**
     * Measured per view: connections; ends whose reference differs between the assessor and
     * Archi — a terminal bendpoint Archi rounds to another point, or, with no bendpoint, the far
     * centre on its half pixel (a bendpoint shared by both ends of a one-bendpoint connection
     * counts once per end); M1 on the assessor's path; M1 on Archi's; and connections whose
     * verdict differs. 403 ends differ, mostly by the half pixel of an odd-sized centre, and no
     * verdict does. So the claim that Archi's rounding of the reference matters only once a
     * rounded-figure model is built is measured here for the plain-rectangle diagonal-terminal
     * count, on these views, not inferred.
     */
    static final Map<String, int[]> REFERENCE_REACH = Map.ofEntries(
            Map.entry("A", new int[]{29, 58, 0, 0, 0}),
            Map.entry("B", new int[]{26, 37, 0, 0, 0}),
            Map.entry("C", new int[]{13, 25, 0, 0, 0}),
            Map.entry("D", new int[]{12, 24, 0, 0, 0}),
            Map.entry("E", new int[]{0, 0, 0, 0, 0}),
            Map.entry("F", new int[]{0, 0, 0, 0, 0}),
            Map.entry("G", new int[]{25, 37, 1, 1, 0}),
            Map.entry("H", new int[]{15, 22, 0, 0, 0}),
            Map.entry("I", new int[]{6, 2, 0, 0, 0}),
            Map.entry("J", new int[]{2, 1, 0, 0, 0}),
            Map.entry("K", new int[]{14, 20, 0, 0, 0}),
            Map.entry("app-architecture-view", new int[]{31, 0, 16, 16, 0}),
            Map.entry("hh-source-clone", new int[]{30, 17, 25, 25, 0}),
            Map.entry("retail-bank-application-collaboration", new int[]{17, 32, 0, 0, 0}),
            Map.entry("retail-bank-business-architecture", new int[]{32, 56, 0, 0, 0}),
            Map.entry("retail-bank-drifted-anchors", new int[]{20, 38, 0, 0, 0}),
            Map.entry("st-source-clone", new int[]{30, 17, 25, 25, 0}),
            Map.entry("v4-integration-architecture-oracle", new int[]{30, 17, 25, 25, 0}));

    /**
     * Whether the rounding matters for the plain-rectangle model: the diagonal-terminal count,
     * computed by production {@code countNonOrthogonalTerminals}, on the stored path as the
     * assessor builds it (half-pixel centres, {@code double} weight, unrounded) and on the path
     * Archi draws through ({@link #archiReference} for each bendpoint, integer centres), over the
     * typed Meridian views and the untyped corpus. A connection whose verdict differs is listed.
     */
    @Test
    public void plainModelDiagonalTerminals_shouldNotDependOnTheReferenceRounding()
            throws IOException {
        List<String> mismatches = new ArrayList<>();
        Map<String, int[]> got = new TreeMap<>();
        for (String v : VIEWS) {
            ViewFixture f = ViewFixture.load("testdata/meridian-" + v + "-typed-fixture.json");
            got.put(v.toUpperCase(), referenceReach(f, typedNodes(f), mismatches));
        }
        for (String view : AnchorModelConsumerCensusTest.CORPUS) {
            got.put(view, referenceReach(ViewFixture.load("testdata/" + view + "-fixture.json"),
                    AnchorModelConsumerCensusTest.corpusNodes(view), mismatches));
        }
        for (Map.Entry<String, int[]> e : got.entrySet()) {
            System.out.println("[reference-reach] " + e.getKey() + " "
                    + Arrays.toString(e.getValue()));
        }
        for (String m : mismatches) System.out.println("[reference-reach] " + m);
        for (Map.Entry<String, int[]> e : got.entrySet()) {
            assertEquals(e.getKey(), Arrays.toString(REFERENCE_REACH.get(e.getKey())),
                    Arrays.toString(e.getValue()));
        }
        assertEquals(REFERENCE_REACH.size(), got.size());
    }

    private static int[] referenceReach(ViewFixture f, List<AssessmentNode> nodes,
            List<String> mismatches) {
        List<AssessmentConnection> ours = new ArrayList<>();
        List<AssessmentConnection> archi = new ArrayList<>();
        int movedEnds = 0;
        for (ViewFixture.FixtureConnection c : f.getConnections()) {
            ViewFixture.FixtureElement src = f.getElementById(c.sourceId());
            ViewFixture.FixtureElement tgt = f.getElementById(c.targetId());
            List<double[]> stored = f.buildStoredPath(c);
            List<double[]> drawn = new ArrayList<>();
            int[] cs = centre(src);
            int[] ct = centre(tgt);
            drawn.add(new double[]{cs[0], cs[1]});
            for (int i = 0; i < c.bendpoints().size(); i++) {
                int[] r = archiReference(src, tgt, c.bendpoints(), i);
                drawn.add(new double[]{r[0], r[1]});
            }
            drawn.add(new double[]{ct[0], ct[1]});
            int last = stored.size() - 1;
            for (int[] pair : new int[][]{{1, 0}, {last - 1, last}}) {
                // each end's reference: its terminal bendpoint, or with none the far centre
                int i = stored.size() > 2 ? pair[0] : last - pair[1];
                if (stored.get(i)[0] != drawn.get(i)[0] || stored.get(i)[1] != drawn.get(i)[1]) {
                    movedEnds++;
                }
            }
            ours.add(new AssessmentConnection(c.id(), c.sourceId(), c.targetId(), stored, "", 1));
            archi.add(new AssessmentConnection(c.id(), c.sourceId(), c.targetId(), drawn, "", 1));
        }
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        Set<String> before = assessor.countNonOrthogonalTerminals(ours, nodes, true).violatorIds();
        Set<String> after = assessor.countNonOrthogonalTerminals(archi, nodes, true).violatorIds();
        Set<String> flips = new TreeSet<>(before);
        flips.addAll(after);
        Set<String> both = new HashSet<>(before);
        both.retainAll(after);
        flips.removeAll(both);
        for (String id : flips) {
            mismatches.add(f.getViewName() + " " + id + " " + before.contains(id) + "->"
                    + after.contains(id));
        }
        return new int[]{ours.size(), movedEnds, before.size(), after.size(), flips.size()};
    }

    private static void assertPinned(Row r, int[] want) {
        org.junit.Assert.assertNotNull("no pinned row for view " + r.name(), want);
        int[] got = r.figures();
        for (int i = 0; i < COLUMNS.length; i++) {
            assertEquals(r.name() + " " + COLUMNS[i], want[i], got[i]);
        }
    }

    /**
     * The transcription agrees with what Archi 5.10.0 drew: four probes exported to PNG at scale 1
     * with the orthogonal-anchor preference on, each line's end read from the pixels. An
     * axis-aligned line's last drawn pixel is one short of its end point, as SWT draws lines.
     */
    @Test
    public void archiPoint_shouldMatchTheEndsArchiDrew() {
        // 20 px arc, reference off the top-left corner: the arc's 45-degree point, 3 px in
        assertArray(new int[]{403, 103, Zone.CORNER.ordinal()},
                archiPoint(new int[]{400, 100, 120, 60}, null, 380, 70, new int[]{20, 20}));
        // 20 px arc, reference above the left edge: acos(1) = 0, so the arc's foot 10 px down
        assertArray(new int[]{400, 310, Zone.BAND.ordinal()},
                archiPoint(new int[]{400, 300, 120, 60}, null, 400, 270, new int[]{20, 20}));
        // service pill 56x56 on 120x56, reference left at y + 10: 28 - (int) 21.45 = 7 px in
        assertArray(new int[]{707, 110, Zone.BAND.ordinal()},
                archiPoint(new int[]{700, 100, 120, 56}, null, 650, 110, new int[]{56, 56}));
        // Value ellipse 120x56, reference above at x + 30: 28 - (int) 24.25 = 4 px down
        assertArray(new int[]{730, 304, Zone.BAND.ordinal()},
                archiPoint(new int[]{700, 300, 120, 56}, null, 730, 250, new int[]{120, 56}));
    }

    /**
     * The arms the pixel probes did not reach, each derived by hand from {@code OrthogonalAnchor}:
     * the right and bottom arms, an odd corner dimension (integer {@code cw/2} in the bands, double
     * {@code cw/2.0} in the trig), the remote-centre replacement and the inside arm.
     */
    @Test
    public void archiPoint_shouldReproduceTheRemainingArms() {
        int[] arc = {20, 20};
        // off the bottom-right corner: 520 - 10 + (int) 7.07 = 517, 160 - 10 + 7 = 157
        assertArray(new int[]{517, 157, Zone.CORNER.ordinal()},
                archiPoint(new int[]{400, 100, 120, 60}, null, 530, 170, arc));
        // right of the box, 5 px below its top: asin(5/10) = 30 deg, (int) (cos * 10) = 8, x = 518
        assertArray(new int[]{518, 105, Zone.BAND.ordinal()},
                archiPoint(new int[]{400, 100, 120, 60}, null, 530, 105, arc));
        // below the box, 5 px left of its right edge: acos(-0.5) = 120 deg, (int) (sin * 10) = 8,
        // y = 160 - 10 + 8 = 158
        assertArray(new int[]{515, 158, Zone.BAND.ordinal()},
                archiPoint(new int[]{400, 100, 120, 60}, null, 515, 170, arc));
        // service pill on 120x55, corner 55x55 (odd): 27 - (int) (0.7071 * 27.5) = 27 - 19 = 8 px in
        assertArray(new int[]{708, 108, Zone.CORNER.ordinal()},
                archiPoint(new int[]{700, 100, 120, 55}, null, 690, 90, new int[]{55, 55}));
        // Value ellipse 120x55: 60 - (int) 42.43 = 18 px across, 27 - (int) 19.45 = 8 px down
        assertArray(new int[]{718, 108, Zone.CORNER.ordinal()},
                archiPoint(new int[]{700, 100, 120, 55}, null, 690, 90, new int[]{120, 55}));
        // the remote's centre region [317,323) x [117,123) holds the reference, which becomes the
        // overlap midpoint ((300 + 160) / 2, (110 + 130) / 2) = (230, 120): right of the box, in
        // its middle band, so the right face
        assertArray(new int[]{160, 120, Zone.FACE.ordinal()},
                archiPoint(new int[]{100, 100, 60, 40}, new int[]{300, 110, 40, 20}, 320, 120, arc));
        // inside on both axes: the centre
        assertArray(new int[]{130, 120, Zone.INSIDE.ordinal()},
                archiPoint(new int[]{100, 100, 60, 40}, null, 135, 125, arc));
    }

    /**
     * The reference in Archi's arithmetic, each case derived by hand.
     * <ul>
     *   <li>Odd boxes [400,100,121,61] and [400,300,121,61], integer centres (460,130) and
     *       (460,330), one bendpoint (0,169)/(0,-30) at weight 0.5: (299 + 300) / 2 = 299.5,
     *       floored to 299. With the half-pixel centres (460.5, 330.5) it would blend to 300.</li>
     *   <li>Below zero, [-400,500,120,60] to [-120,500,120,60], centres (-340,530) and (-60,530),
     *       offsets (219,0)/(-60,0): (-121 + -120) / 2 = -120.5, floored to -121; truncated toward
     *       zero it would be -120.</li>
     *   <li>Two bendpoints, [700,300,120,60] to [700,100,120,60], centres (760,330) and (760,130).
     *       The first, (0,-29)/(0,168), blends 301 and 298 at the {@code float} weight 1/3f =
     *       0.33333334: 301 - 3 * 0.33333334 = 299.99999997, floored to 299; the {@code double}
     *       weight 1/3 gives 300. The second, (0,-130)/(0,70), agrees at (760,200) and returns
     *       it.</li>
     * </ul>
     */
    @Test
    public void archiReference_shouldBlendAndRoundAsDraw2dDoes() {
        ViewFixture.FixtureElement a = element(400, 100, 121, 61);
        ViewFixture.FixtureElement b = element(400, 300, 121, 61);
        assertArray(new int[]{460, 299}, archiReference(a, b,
                List.of(new ViewFixture.FixtureBendpoint(0, 169, 0, -30)), 0));

        ViewFixture.FixtureElement d = element(-400, 500, 120, 60);
        ViewFixture.FixtureElement c = element(-120, 500, 120, 60);
        assertArray(new int[]{-121, 530}, archiReference(d, c,
                List.of(new ViewFixture.FixtureBendpoint(219, 0, -60, 0)), 0));

        ViewFixture.FixtureElement e = element(700, 300, 120, 60);
        ViewFixture.FixtureElement g = element(700, 100, 120, 60);
        List<ViewFixture.FixtureBendpoint> two = List.of(
                new ViewFixture.FixtureBendpoint(0, -29, 0, 168),
                new ViewFixture.FixtureBendpoint(0, -130, 0, 70));
        assertArray(new int[]{760, 299}, archiReference(e, g, two, 0));
        assertArray(new int[]{760, 200}, archiReference(e, g, two, 1));
    }

    private static ViewFixture.FixtureElement element(int x, int y, int w, int h) {
        return new ViewFixture.FixtureElement("e" + x + "_" + y, "", x, y, w, h, false, null);
    }

    private static void assertArray(int[] want, int[] got) {
        assertEquals(java.util.Arrays.toString(want), java.util.Arrays.toString(got));
    }

    // ---- measurement ----

    static Row measure(String name, ViewFixture f) {
        List<AssessmentNode> nodes = typedNodes(f);
        Map<String, AssessmentNode> byId = new HashMap<>();
        for (AssessmentNode n : nodes) byId.put(n.id(), n);
        Map<String, int[]> corner = new HashMap<>();
        Map<String, Kind> kind = new HashMap<>();
        for (ViewFixture.FixtureElement e : f.getElements()) {
            RenderFaceDeriver.Anchoring a = RenderFaceDeriver.anchoringOf(
                    archimateType(e.type()), e.figureType(), e.w(), e.h());
            corner.put(e.id(), a.corner());
            kind.put(e.id(), kindOf(e, a));
        }
        List<AssessmentConnection> conns = new ArrayList<>();
        Map<String, ViewFixture.FixtureConnection> stored = new HashMap<>();
        for (ViewFixture.FixtureConnection c : f.getConnections()) {
            conns.add(new AssessmentConnection(c.id(), c.sourceId(), c.targetId(),
                    f.buildStoredPath(c), "", 1));
            if (stored.put(c.id(), c) != null) {
                throw new IllegalArgumentException("duplicate connection id " + c.id());
            }
        }
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        LayoutAssessmentResult result = assessor.assess(nodes, conns, true);
        LayoutQualityAssessor.NonOrthogonalTerminalResult m1 =
                assessor.countNonOrthogonalTerminals(conns, nodes, true);

        int[][] ends = new int[Kind.values().length][Zone.values().length];
        int moved = 0, movedCorner = 0, movedBand = 0, m1Flips = 0, crossingFlips = 0;
        List<String> details = new ArrayList<>();
        for (AssessmentConnection conn : conns) {
            List<double[]> path = conn.pathPoints();
            AssessmentNode src = byId.get(conn.sourceNodeId());
            AssessmentNode tgt = byId.get(conn.targetNodeId());
            int last = path.size() - 1;
            List<double[]> clipped = GeometryUtils.clipPathToRectEdges(path, rect(src), rect(tgt));
            ViewFixture.FixtureConnection fc = Objects.requireNonNull(stored.get(conn.id()),
                    conn.id());
            for (int side = 0; side < 2; side++) {
                AssessmentNode own = side == 0 ? src : tgt;
                AssessmentNode remote = side == 0 ? tgt : src;
                double[] ref = path.size() > 2
                        ? (side == 0 ? path.get(1) : path.get(last - 1))
                        : (side == 0 ? path.get(last) : path.get(0));
                // the integer point Archi hands the anchor; with no bendpoint, the remote centre
                int[] archiRef = path.size() > 2
                        ? archiReference(f.getElementById(fc.sourceId()),
                                f.getElementById(fc.targetId()), fc.bendpoints(),
                                side == 0 ? 0 : fc.bendpoints().size() - 1)
                        : centre(f.getElementById(side == 0 ? fc.targetId() : fc.sourceId()));
                // the far end of the drawn stub: the bendpoint as Archi places it, or the other
                // end's anchor
                double[] far = path.size() > 2 ? new double[]{archiRef[0], archiRef[1]}
                        : clipped.get(side == 0 ? last : 0);
                Kind k = kind.get(own.id());
                int[] cd = corner.get(own.id());
                // the replacement is tested for every reference, as the shared clip and Archi do
                int[] remoteBox = box(remote);
                int[] archi = archiPoint(box(own), remoteBox, archiRef[0], archiRef[1],
                        cd == null ? new int[]{0, 0} : cd);
                Zone z = Zone.values()[archi[2]];
                ends[k.ordinal()][z.ordinal()]++;
                if (k != Kind.ARC && k != Kind.PILL && k != Kind.ELLIPSE) continue;
                if (z == Zone.FACE || z == Zone.INSIDE) continue;
                // the arc's own effect: the same reference in the same arithmetic with no corner
                int[] square = archiPoint(box(own), remoteBox, archiRef[0], archiRef[1],
                        new int[]{0, 0});
                if (archi[0] == square[0] && archi[1] == square[1]) continue;
                moved++;
                if (z == Zone.CORNER) movedCorner++; else movedBand++;
                double[] arc = {archi[0], archi[1]};
                double[] plain = {square[0], square[1]};
                boolean before = visibleDiagonal(plain, far);
                boolean after = visibleDiagonal(arc, far);
                if (before != after) m1Flips++;
                List<String> flipped = new ArrayList<>();
                for (AssessmentNode other : nodes) {
                    if (other == own || other == remote) continue;
                    for (double inset : INSETS) {
                        boolean b = GeometryUtils.pathPassesThroughRect(List.of(plain, far),
                                other.x(), other.y(), other.width(), other.height(), inset);
                        boolean a = GeometryUtils.pathPassesThroughRect(List.of(arc, far),
                                other.x(), other.y(), other.width(), other.height(), inset);
                        if (a != b) flipped.add(other.id() + "@" + inset);
                    }
                }
                if (!flipped.isEmpty()) crossingFlips++;
                // ref is the assessor's interpolation, printed beside Archi's for comparison
                details.add(String.format("moved %s %s %s %s box=[%.0f,%.0f,%.0f,%.0f] corner=%dx%d"
                        + " ref=(%.1f,%.1f) archiRef=(%d,%d) plain=(%.1f,%.1f) arc=(%d,%d)"
                        + " stub %.1f°/%.1fpx -> %.1f°/%.1fpx m1 %s->%s crossings %s", conn.id(),
                        side == 0 ? "source" : "target", k, z, own.x(), own.y(), own.width(),
                        own.height(), cd[0], cd[1], ref[0], ref[1], archiRef[0], archiRef[1],
                        plain[0], plain[1], archi[0], archi[1], deviation(plain, far), dist(plain, far), deviation(arc, far),
                        dist(arc, far), before, after, flipped));
            }
        }
        return new Row(name, conns.size(), ends, moved, movedCorner, movedBand, m1.count(),
                m1Flips, result.connectionPassThroughs().size(),
                result.connectionThroughNoteCount(), result.connectionGrazesVisualCount(),
                crossingFlips, details);
    }

    /**
     * {@code OrthogonalAnchor.getLocation} of Archi {@code release_5.10.0} with a corner dimension,
     * in its own arithmetic: the remote centre-region replacement (skipped for a null remote), bands
     * on integer {@code corner/2}, the arc arms in doubles on {@code corner/2.0} with the
     * {@code (int)} cast over the whole product. Returns {@code {x, y, zone ordinal}}.
     */
    static int[] archiPoint(int[] own, int[] remote, int refX, int refY, int[] corner) {
        if (remote != null) {
            int shrinkH = (remote[2] - 5) / 2;
            int shrinkV = (remote[3] - 5) / 2;
            int cx = Math.min(remote[0] + remote[2] / 2, remote[0] + shrinkH);
            int cw = Math.max(0, remote[2] - 2 * shrinkH);
            int cy = Math.min(remote[1] + remote[3] / 2, remote[1] + shrinkV);
            int ch = Math.max(0, remote[3] - 2 * shrinkV);
            if (refX >= cx && refX < cx + cw && refY >= cy && refY < cy + ch) {
                refX = (Math.max(remote[0], own[0])
                        + Math.min(remote[0] + remote[2], own[0] + own[2])) / 2;
                refY = (Math.max(remote[1], own[1])
                        + Math.min(remote[1] + remote[3], own[1] + own[3])) / 2;
            }
        }
        int bx = own[0], by = own[1], bw = own[2], bh = own[3];
        int cw = corner[0], ch = corner[1];
        // bands: 0 outside-low, 1 low corner, 2 middle, 3 high corner, 4 outside-high
        int hb = refX < bx ? 0 : refX < bx + cw / 2 ? 1 : refX < bx + bw - cw / 2 ? 2
                : refX < bx + bw ? 3 : 4;
        int vb = refY < by ? 0 : refY < by + ch / 2 ? 1 : refY < by + bh - ch / 2 ? 2
                : refY < by + bh ? 3 : 4;
        double hw = cw / 2.0, hh = ch / 2.0;
        boolean hOut = hb == 0 || hb == 4;
        boolean vOut = vb == 0 || vb == 4;
        if (hOut && vOut) {
            int x = hb == 0 ? bx + cw / 2 - (int) (COSPI4 * hw) : bx + bw - cw / 2 + (int) (COSPI4 * hw);
            int y = vb == 0 ? by + ch / 2 - (int) (COSPI4 * hh) : by + bh - ch / 2 + (int) (COSPI4 * hh);
            return new int[]{x, y, Zone.CORNER.ordinal()};
        }
        if (vOut && (hb == 1 || hb == 3)) {
            double c = hb == 1 ? (bx + hw - refX) / hw : (bx + bw - hw - refX) / hw;
            int d = (int) (Math.sin(Math.acos(c)) * hh);
            int y = vb == 0 ? by + ch / 2 - d : by + bh - ch / 2 + d;
            return new int[]{refX, y, Zone.BAND.ordinal()};
        }
        if (hOut && (vb == 1 || vb == 3)) {
            double s = vb == 1 ? (by + hh - refY) / hh : (by + bh - hh - refY) / hh;
            int d = (int) (Math.cos(Math.asin(s)) * hw);
            int x = hb == 0 ? bx + cw / 2 - d : bx + bw - cw / 2 + d;
            return new int[]{x, refY, Zone.BAND.ordinal()};
        }
        if (vOut) return new int[]{refX, vb == 0 ? by : by + bh, Zone.FACE.ordinal()};
        if (hOut) return new int[]{hb == 0 ? bx : bx + bw, refY, Zone.FACE.ordinal()};
        return new int[]{bx + bw / 2, by + bh / 2, Zone.INSIDE.ordinal()};
    }

    /**
     * The integer reference Archi hands {@code OrthogonalAnchor} for bendpoint {@code index}, from
     * the stored relative offsets. Archi {@code release_5.10.0}
     * {@code DiagramConnectionEditPart.refreshBendpoints} gives the bendpoint the {@code float}
     * weight {@code (i + 1) / ((float) n + 1)}; draw2d {@code 3.23.100}
     * {@code RelativeBendpoint.getLocation} blends the two reconstructions in doubles from the
     * anchors' reference points, {@code Rectangle.getCenter()} on integer division; the
     * {@code PrecisionPoint} it returns keeps {@code (int) Math.floor(d + 1e-9)}, and
     * {@code BendpointConnectionRouter} hands that integer point to the anchor. Measured on
     * pixels: a reference half a pixel above a face lands on the face, one at {@code -120.5} left
     * of a box whose edge is {@code -120} lands on its left face, and a {@code float} weight that
     * blends to 299.99999997 where a {@code double} one gives 300 lands on the face above 300.
     */
    static int[] archiReference(ViewFixture.FixtureElement src, ViewFixture.FixtureElement tgt,
            List<ViewFixture.FixtureBendpoint> bendpoints, int index) {
        ViewFixture.FixtureBendpoint bp = bendpoints.get(index);
        int[] cs = centre(src);
        int[] ct = centre(tgt);
        double w = (index + 1) / ((float) bendpoints.size() + 1);
        double x = (cs[0] + bp.startX()) * (1 - w) + w * (ct[0] + bp.endX());
        double y = (cs[1] + bp.startY()) * (1 - w) + w * (ct[1] + bp.endY());
        return new int[]{(int) Math.floor(x + 1e-9), (int) Math.floor(y + 1e-9)};
    }

    /** {@code Rectangle.getCenter()}: the centre on integer division. */
    private static int[] centre(ViewFixture.FixtureElement e) {
        return new int[]{e.x() + e.w() / 2, e.y() + e.h() / 2};
    }

    // ---- typed fixture loading ----

    /** Nodes as {@code AssessmentCollector} builds them, from the typed fixture. */
    static List<AssessmentNode> typedNodes(ViewFixture f) {
        List<AssessmentNode> nodes = new ArrayList<>();
        for (ViewFixture.FixtureElement e : f.getElements()) {
            boolean group = "Group".equals(e.type());
            boolean note = "Note".equals(e.type());
            boolean container = group || "Grouping".equals(e.type());
            nodes.add(new AssessmentNode(e.id(), e.x(), e.y(), e.w(), e.h(), e.parentId(), group,
                    note, e.name(), 0.0, null, null, 0.0, 0.0, 0.0, "Junction".equals(e.type()),
                    null, container, AssessmentNode.TEXT_ALIGNMENT_CENTRE,
                    AssessmentNode.TEXT_POSITION_TOP));
        }
        return nodes;
    }

    /** The ArchiMate type {@code anchoringOf} expects; a group or note has none. */
    private static String archimateType(String type) {
        return "Group".equals(type) || "Note".equals(type) ? null : type;
    }

    private static Kind kindOf(ViewFixture.FixtureElement e, RenderFaceDeriver.Anchoring a) {
        if (a.corner() == null) return Kind.JUNCTION;
        if (a.corner()[0] == 0 && a.corner()[1] == 0) return Kind.PLAIN;
        if ("Value".equals(e.type())) return Kind.ELLIPSE;
        if (a.corner()[0] == RenderFaceDeriver.ROUNDED_ARC
                && a.corner()[1] == RenderFaceDeriver.ROUNDED_ARC && e.figureType() == 0) {
            return Kind.ARC;
        }
        return Kind.PILL;
    }

    private static int[] box(AssessmentNode n) {
        return new int[]{(int) n.x(), (int) n.y(), (int) n.width(), (int) n.height()};
    }

    private static double[] rect(AssessmentNode n) {
        return n == null ? null : new double[]{n.x(), n.y(), n.width(), n.height()};
    }

    /** The diagonal-terminal rule: off-axis by more than 5 degrees and at least 3 px long. */
    private static boolean visibleDiagonal(double[] anchor, double[] bp) {
        return deviation(anchor, bp) > 5.0
                && dist(anchor, bp) >= LayoutQualityAssessor.VISIBLE_DIAGONAL_MIN_PX;
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private static double deviation(double[] p1, double[] p2) {
        double dx = Math.abs(p1[0] - p2[0]);
        double dy = Math.abs(p1[1] - p2[1]);
        if (dx < 1e-9 && dy < 1e-9) return 0.0;
        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
        return Math.min(angleDeg, 90.0 - angleDeg);
    }
}
