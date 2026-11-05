package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;

/**
 * Two models of Archi's {@code OrthogonalAnchor} live in this codebase and must not drift:
 * {@link GeometryUtils#orthogonalExitPoint(double, double, double, double, double, double, double[])},
 * which returns the anchor POINT every assessor consumer measures from, and
 * {@link RenderFaceDeriver}, which returns the ARM the published {@code renderedFace} is read from.
 *
 * <p>For every integer reference on a grid around a plain rectangle — every band, both boundary
 * lines per axis — and on a grid around each remote element's centre, so that the reference
 * replacement fires on odd and on even remote sizes, two things must hold. The point must lie on
 * the part of the boundary the derived arm names: a corner arm is that corner exactly, a face arm
 * a point on that face line within its half-open extent, the inside arm the box centre. And it
 * must equal, exactly, the point {@code OrthogonalAnchor.getLocation} computes in Archi's own
 * integer arithmetic ({@link #archiPoint}) — the arm alone cannot see a replaced reference that
 * lands on the same face at a different coordinate.
 *
 * <p>The second owner box is odd-sized and lies below zero, where an overlap sum is odd and
 * negative: a midpoint halved in doubles is then half a pixel off Archi's, which truncates toward
 * zero, and at a box edge lands in the next band. The one deliberate difference is the inside
 * arm's centre, kept in doubles, which on an odd dimension sits half a pixel past Archi's.
 */
public class GeometryUtilsAnchorParityTest {

    private static final int[] NO_CORNER = {0, 0};

    /**
     * Owner boxes, each with its remote boxes: odd and even sizes on each axis, one overlapping
     * the owner on x only, one on y only, one on neither, one overlapping on both, and one small
     * enough that the centre-region shrink is negative.
     */
    private static final int[][][] CASES = {
        {
            {100, 200, 60, 40},
            {300, 205, 41, 30},  // right, y overlap, odd width / even height
            {120, 320, 30, 31},  // below, x overlap, even width / odd height
            {10, 60, 20, 20},    // up-left, no overlap, even / even
            {130, 210, 15, 11},  // inside the owner, odd / odd
            {200, 100, 3, 4},    // tiny: (size - 5) / 2 is negative
        },
        {
            {-31, -17, 45, 27},  // odd / odd, below zero
            {20, -16, 31, 21},   // right, y overlap, odd negative sum
            {-32, 30, 18, 20},   // below, x overlap from left of the owner, odd negative sum
            {-90, -60, 25, 15},  // up-left, no overlap, odd / odd
            {-30, -12, 9, 7},    // inside the owner, odd / odd
            {-60, 10, 3, 4},     // tiny, below zero
        },
    };

    @Test
    public void everyReferencePoint_shouldLandOnTheArmTheRenderFaceDeriverNames() {
        int checked = 0;
        for (int[][] c : CASES) {
            int[] own = c[0];
            Set<RenderFaceDeriver.Outcome> seen = EnumSet.noneOf(RenderFaceDeriver.Outcome.class);
            int replaced = 0;
            for (int i = 1; i < c.length; i++) {
                int[] remote = c[i];
                int rcx = remote[0] + remote[2] / 2;
                int rcy = remote[1] + remote[3] / 2;
                for (int pass = 0; pass < 2; pass++) {
                    int x0 = pass == 0 ? own[0] - 12 : rcx - 6;
                    int x1 = pass == 0 ? own[0] + own[2] + 12 : rcx + 6;
                    int y0 = pass == 0 ? own[1] - 12 : rcy - 6;
                    int y1 = pass == 0 ? own[1] + own[3] + 12 : rcy + 6;
                    for (int x = x0; x <= x1; x++) {
                        for (int y = y0; y <= y1; y++) {
                            RenderFaceDeriver.Outcome arm = RenderFaceDeriver.derive(own, remote,
                                    x, y, NO_CORNER, RenderFaceDeriver.AnchorModel.ORTHOGONAL);
                            double[] p = GeometryUtils.orthogonalExitPoint(own[0], own[1], own[2],
                                    own[3], x, y, toDouble(remote));
                            assertOnArm(own, arm, p, x, y, remote);
                            int[] archi = archiPoint(own, remote, x, y);
                            if (archi[2] == 1) replaced++;
                            // the inside arm's centre is kept in doubles; Archi truncates it
                            boolean same = archi[3] == 1
                                    ? Math.floor(p[0]) == archi[0] && Math.floor(p[1]) == archi[1]
                                    : p[0] == archi[0] && p[1] == archi[1];
                            if (!same) {
                                fail(String.format("owner [%d,%d,%d,%d], reference (%d,%d), remote"
                                        + " [%d,%d,%d,%d]: Archi anchors at (%d,%d), GeometryUtils"
                                        + " at (%.1f,%.1f)", own[0], own[1], own[2], own[3], x, y,
                                        remote[0], remote[1], remote[2], remote[3], archi[0],
                                        archi[1], p[0], p[1]));
                            }
                            seen.add(arm);
                            checked++;
                        }
                    }
                }
            }
            assertEquals("the grid must reach every arm a plain rectangle has",
                    EnumSet.of(RenderFaceDeriver.Outcome.FACE_TOP,
                            RenderFaceDeriver.Outcome.FACE_BOTTOM,
                            RenderFaceDeriver.Outcome.FACE_LEFT,
                            RenderFaceDeriver.Outcome.FACE_RIGHT,
                            RenderFaceDeriver.Outcome.CORNER_TOP_LEFT,
                            RenderFaceDeriver.Outcome.CORNER_TOP_RIGHT,
                            RenderFaceDeriver.Outcome.CORNER_BOTTOM_LEFT,
                            RenderFaceDeriver.Outcome.CORNER_BOTTOM_RIGHT,
                            RenderFaceDeriver.Outcome.ABSTAIN_REFERENCE_INSIDE_BOX),
                    seen);
            assertTrue("the grid must exercise the reference replacement, got " + replaced,
                    replaced > 0);
        }
        assertTrue(checked > 20_000);
    }

    /**
     * A zero-width or zero-height owner. With {@code width == 0} no reference is within on x:
     * {@code ref.x < x} is left and everything else is right, so the centre arm is unreachable on
     * that axis and a reference at the box's x lands on the face at its own y, not at the centre.
     */
    @Test
    public void zeroSizeOwner_shouldAnchorAsArchiDoesWithNoCentreArm() {
        int[] zeroW = {100, 200, 0, 40};
        // x = 100 is right of a zero-width box, y within: the right face at y = 215, not the
        // centre (100, 220)
        assertAnchor(zeroW, null, 100, 215, 100, 215);
        // left, y within: the left face, the same line
        assertAnchor(zeroW, null, 90, 210, 100, 210);
        // right and above: the top-right corner, which is the top-left too
        assertAnchor(zeroW, null, 110, 190, 100, 200);
        // left and below: the bottom corner
        assertAnchor(zeroW, null, 90, 250, 100, 240);

        int[] zeroH = {100, 200, 60, 0};
        // y = 200 is below a zero-height box, x within: the bottom face at x = 125, not the
        // centre (130, 200)
        assertAnchor(zeroH, null, 125, 200, 125, 200);
        assertAnchor(zeroH, null, 125, 190, 125, 200);
        assertAnchor(zeroH, null, 170, 210, 160, 200);

        // odd and below zero: x = -31 is right of the box, so the face at y = -10, not the
        // centre (-31, -3.5)
        int[] zeroWNeg = {-31, -17, 0, 27};
        assertAnchor(zeroWNeg, null, -31, -10, -31, -10);
        assertAnchor(zeroWNeg, null, -40, 20, -31, 10);

        // zero on both axes: every reference is outside on both, so a corner, which is the point
        assertAnchor(new int[]{100, 200, 0, 0}, null, 100, 200, 100, 200);

        // with a remote, as a zero-bendpoint connection has: the remote [300,205,41,30] centre
        // region [318,323) x [217,223) holds (320,220), replaced by ((300 + 100) / 2,
        // (205 + 235) / 2) = (200, 220) over the inverted x span — right of the zero-width box, so
        // its face at y = 220
        assertAnchor(zeroW, new int[]{300, 205, 41, 30}, 320, 220, 100, 220);
    }

    /**
     * A remote zero on one axis only: draw2d's shrink grows that axis to 4 px, {@code [x - 2, x + 2)},
     * while the other keeps its ordinary region. Owner [100,200,60,40], remote [300,205,0,30]: the
     * region is [298,302) x [217,223), the replaced reference ((300 + 160) / 2, (205 + 235) / 2) =
     * (230, 220), the right face at y = 220.
     */
    @Test
    public void remoteZeroOnOneAxis_shouldGrowOnlyThatAxis() {
        int[] own = {100, 200, 60, 40};
        int[] remote = {300, 205, 0, 30};
        assertAnchor(own, remote, 297, 218, 160, 218);
        assertAnchor(own, remote, 298, 218, 160, 220);
        assertAnchor(own, remote, 301, 222, 160, 220);
        assertAnchor(own, remote, 302, 222, 160, 222);
        assertAnchor(own, remote, 300, 216, 160, 216);
        assertAnchor(own, remote, 300, 223, 160, 223);
    }

    /**
     * The overlap midpoint halves an odd, negative sum toward zero, as Archi's integer division
     * does. Owner [-91,-17,45,27] ends at x = -46; the 3x3 remote at (-47,-5) has the centre region
     * [-48,-43) x [-6,-1), which holds (-46,-4). The replaced reference is ((-47 + -46) / 2,
     * (-5 + -2) / 2) = (-46, -3): -46 is the owner's right edge line, so outside, and the anchor is
     * the right face at y = -3. Halved by floor instead, (-47, -4) is inside the owner on both axes
     * and the anchor would be its centre.
     */
    @Test
    public void overlapMidpoint_shouldHalveANegativeOddSumTowardZero() {
        assertAnchor(new int[]{-91, -17, 45, 27}, new int[]{-47, -5, 3, 3}, -46, -4, -46, -3);
    }

    /**
     * A zero-size remote. Its shrink is {@code (0 - 5) / 2 = -2}, so draw2d's shrink grows it to
     * the half-open region {@code [x - 2, x + 2) x [y - 2, y + 2)}, 4 px across. A reference just
     * inside each side is replaced by the overlap midpoint; one 1 px outside is not.
     */
    @Test
    public void zeroSizeRemote_shouldReplaceOnlyInsideItsClampedCentreRegion() {
        // owner [100,200,60,40], remote (300,205): region [298,302) x [203,207). The replaced
        // reference is ((300 + 160) / 2, (205 + 205) / 2) = (230, 205): right face at y = 205.
        int[] own = {100, 200, 60, 40};
        int[] remote = {300, 205, 0, 0};
        assertAnchor(own, remote, 297, 204, 160, 204);
        assertAnchor(own, remote, 298, 204, 160, 205);
        assertAnchor(own, remote, 301, 204, 160, 205);
        assertAnchor(own, remote, 302, 204, 160, 204);
        assertAnchor(own, remote, 300, 202, 160, 202);
        assertAnchor(own, remote, 300, 203, 160, 205);
        assertAnchor(own, remote, 300, 206, 160, 205);
        assertAnchor(own, remote, 300, 207, 160, 207);

        // odd owner [-31,-17,45,27] below zero, remote (-61,-5): region [-63,-59) x [-7,-3). The
        // replaced reference is ((-31 + -61) / 2, (-5 + -5) / 2) = (-46, -5): left face at y = -5.
        int[] ownNeg = {-31, -17, 45, 27};
        int[] remoteNeg = {-61, -5, 0, 0};
        assertAnchor(ownNeg, remoteNeg, -64, -6, -31, -6);
        assertAnchor(ownNeg, remoteNeg, -63, -6, -31, -5);
        assertAnchor(ownNeg, remoteNeg, -60, -6, -31, -5);
        assertAnchor(ownNeg, remoteNeg, -59, -6, -31, -6);
        assertAnchor(ownNeg, remoteNeg, -61, -8, -31, -8);
        assertAnchor(ownNeg, remoteNeg, -61, -7, -31, -5);
        assertAnchor(ownNeg, remoteNeg, -61, -4, -31, -5);
        assertAnchor(ownNeg, remoteNeg, -61, -3, -31, -3);
    }

    /**
     * A remote equal to the owner, as a self-connection with no bendpoints gives it: the reference
     * is the owner's own centre, which lies in its own centre region, so it is replaced by the
     * self-overlap midpoint {@code ((2x + w) / 2, (2y + h) / 2)}. Both the centre and the midpoint
     * are inside on both axes, so the result is the centre arm whether or not the replacement
     * fires: what is pinned here is where both ends land, not the replacement, which the
     * transcription alone records.
     */
    @Test
    public void remoteEqualToOwner_shouldAnchorAtTheCentre() {
        // even [100,100,120,60]: centre (160,130), region [157,163) x [127,133), midpoint
        // (160,130), the centre arm (160,130) — Archi's integer centre exactly
        int[] even = {100, 100, 120, 60};
        double[] p = GeometryUtils.orthogonalExitPoint(100, 100, 120, 60, 160, 130,
                toDouble(even));
        assertEquals(160.0, p[0], 0.0);
        assertEquals(130.0, p[1], 0.0);
        int[] archi = archiPoint(even, even, 160, 130);
        assertEquals(List.of(160, 130, 1, 1), List.of(archi[0], archi[1], archi[2], archi[3]));

        // odd below zero [-51,-31,121,61]: centre (9.5,-0.5) in doubles, region [7,12) x [-3,2),
        // midpoint ((-51 + 70) / 2, (-31 + 30) / 2) = (9, 0) on integer division, inside, so the
        // centre arm (9.5,-0.5). Archi's centre is (x + w / 2, y + h / 2) = (9,-1) in integers, the
        // floor of the double centre: the half pixel is the one deliberate difference, the inside
        // arm's centre kept in doubles.
        int[] odd = {-51, -31, 121, 61};
        p = GeometryUtils.orthogonalExitPoint(-51, -31, 121, 61, 9.5, -0.5, toDouble(odd));
        assertEquals(9.5, p[0], 0.0);
        assertEquals(-0.5, p[1], 0.0);
        archi = archiPoint(odd, odd, 9, -1);
        assertEquals(List.of(9, -1, 1, 1), List.of(archi[0], archi[1], archi[2], archi[3]));
        assertEquals(archi[0], (int) Math.floor(p[0]));
        assertEquals(archi[1], (int) Math.floor(p[1]));
    }

    /**
     * A negative size is outside the contract — no caller produces one, and Archi resolves its
     * {@code -1} default-size sentinel before anchoring — but the helper must still return a
     * finite point rather than throw.
     */
    @Test
    public void negativeSize_shouldReturnAFinitePointWithoutThrowing() {
        double[][] boxes = {{100, 200, -20, 40}, {100, 200, 60, -10}, {-31, -17, -1, -1}};
        for (double[] b : boxes) {
            for (int x = -60; x <= 200; x += 7) {
                for (int y = -60; y <= 260; y += 7) {
                    for (double[] remote : new double[][]{null, b, {300, 205, -3, -3}}) {
                        double[] p = GeometryUtils.orthogonalExitPoint(b[0], b[1], b[2], b[3], x,
                                y, remote);
                        assertTrue(Double.isFinite(p[0]) && Double.isFinite(p[1]));
                    }
                }
            }
        }
    }

    /**
     * Archi never anchors a fractional reference: draw2d's {@code RelativeBendpoint} blends in
     * doubles, then {@code PrecisionPoint} keeps {@code (int) Math.floor(d + 1e-9)}, and the router
     * hands {@code OrthogonalAnchor} that integer point. The helper keeps the fraction. On
     * whole-pixel boxes {@code x < n} and {@code x >= n} hold exactly when they hold for
     * {@code floor(x)}, so for every reference here the helper must take Archi's decision on the
     * floored point — replacement, arm, which face — and return Archi's point on the classified
     * axes; on an axis the face arm copies, the helper keeps the fraction, so the two differ by
     * exactly {@code ref - floor(ref)}, along the stub and never across it. The inside arm is the
     * helper's double centre, whose floor is Archi's integer centre. The references sit at
     * {@code k}, {@code k + 0.1}, {@code k + 0.5} and {@code k + 0.9} for every {@code k} within
     * 2 px of each band edge, the owner's centre and the remote's centre region, so every edge is
     * approached from 0.1 and 0.5 below and above; each owner is swept with each remote and with
     * none. The added owner at x = -61 puts {@code -61.5} on its left edge: floored to -62 it is
     * left of the box; truncated toward zero to -61 it would be within.
     */
    @Test
    public void fractionalReference_shouldBeClassifiedAsArchiClassifiesItsFlooredPoint() {
        int[][][] cases = Arrays.copyOf(CASES, CASES.length + 1);
        // owner left edge on -61, remote centre region [-61,-56) x [32,37)
        int[] edgeOn61 = {-61, -40, 31, 21};
        cases[CASES.length] = new int[][]{edgeOn61, {-63, 30, 9, 9}};
        double[] fractions = {0.0, 0.1, 0.5, 0.9};
        List<String> mismatches = new ArrayList<>();
        int checked = 0, copied = 0, inside = 0, corner = 0, replacedOutside = 0;
        int replacedInside = 0, at615 = 0;
        for (int[][] c : cases) {
            int[] own = c[0];
            for (int i = 0; i < c.length; i++) {
                // i == 0: no remote, so nothing is replaced
                int[] remote = i == 0 ? null : c[i];
                int[] region = remote == null ? new int[]{own[0], own[1], 0, 0}
                        : archiRemoteCentre(remote);
                double[] xs = references(fractions, own[0], own[0] + own[2], own[0] + own[2] / 2,
                        region[0], region[0] + region[2]);
                double[] ys = references(fractions, own[1], own[1] + own[3], own[1] + own[3] / 2,
                        region[1], region[1] + region[3]);
                for (double x : xs) {
                    for (double y : ys) {
                        int fx = (int) Math.floor(x);
                        int fy = (int) Math.floor(y);
                        int[] archi = archiPoint(own, remote, fx, fy);
                        double[] p = GeometryUtils.orthogonalExitPoint(own[0], own[1], own[2],
                                own[3], x, y, remote == null ? null : toDouble(remote));
                        double wantX = archi[0];
                        double wantY = archi[1];
                        boolean ok;
                        if (archi[3] == 1) {
                            // the inside arm: the helper's double centre, floored to Archi's
                            wantX = own[0] + own[2] / 2.0;
                            wantY = own[1] + own[3] / 2.0;
                            ok = p[0] == wantX && p[1] == wantY
                                    && Math.floor(p[0]) == archi[0] && Math.floor(p[1]) == archi[1];
                            if (archi[2] == 1) replacedInside++; else inside++;
                        } else if (archi[2] == 0) {
                            // not replaced: an axis within the box is the copied one
                            boolean xIn = fx >= own[0] && fx < own[0] + own[2];
                            boolean yIn = fy >= own[1] && fy < own[1] + own[3];
                            if (xIn) wantX += x - fx;
                            if (yIn) wantY += y - fy;
                            if ((xIn && x != fx) || (yIn && y != fy)) copied++;
                            if (!xIn && !yIn) corner++;
                            ok = p[0] == wantX && p[1] == wantY;
                        } else {
                            // replaced by the integer midpoint and outside: Archi's point exactly
                            replacedOutside++;
                            ok = p[0] == wantX && p[1] == wantY;
                        }
                        if (own == edgeOn61 && x == -61.5) at615++;
                        if (!ok) {
                            mismatches.add(String.format("owner [%d,%d,%d,%d], remote %s,"
                                    + " reference (%s,%s): want (%s,%s), helper (%s,%s)", own[0],
                                    own[1], own[2], own[3], Arrays.toString(remote), x, y, wantX,
                                    wantY, p[0], p[1]));
                        }
                        checked++;
                    }
                }
            }
        }
        if (!mismatches.isEmpty()) {
            fail(mismatches.size() + " of " + checked + " references disagree, first: "
                    + mismatches.subList(0, Math.min(5, mismatches.size())));
        }
        // the reach of the sweep, pinned so a narrowed grid cannot pass unnoticed
        assertEquals(List.of(108_096, 37_860, 34_016, 19_280, 3_520, 800, 160),
                List.of(checked, copied, inside, corner, replacedOutside, replacedInside, at615));
    }

    /**
     * {@code -61.5} by hand, the value the truncation question was asked about. Owner
     * [-61,-40,31,21], reference (-61.5,-30): Archi floors x to -62, left of the box, so the left
     * face at y = -30. The helper tests {@code -61.5 < -61}, also left: the same face and point. Had
     * the reference been truncated toward zero to -61 it would be within on both axes and anchor at
     * the centre (-46,-30).
     */
    @Test
    public void negativeHalfPixelReference_shouldTakeTheFlooredBand() {
        double[] p = GeometryUtils.orthogonalExitPoint(-61, -40, 31, 21, -61.5, -30);
        assertEquals(-61.0, p[0], 0.0);
        assertEquals(-30.0, p[1], 0.0);
        int[] archi = archiPoint(new int[]{-61, -40, 31, 21}, null, -62, -30);
        assertEquals(List.of(-61, -30, 0, 0), List.of(archi[0], archi[1], archi[2], archi[3]));
        int[] truncated = archiPoint(new int[]{-61, -40, 31, 21}, null, -61, -30);
        assertEquals(List.of(-46, -30, 0, 1),
                List.of(truncated[0], truncated[1], truncated[2], truncated[3]));
    }

    /**
     * The one known disagreement, left as it is: within {@code 1e-9} below an integer, draw2d's
     * epsilon rounds the reference up and the helper does not. Archi's own blend never lands
     * there — its weight is a {@code float} and its centres are integers — but the assessor's
     * reference, a {@code double}-weighted blend of half-pixel centres, does, a few ulps below an
     * integer. Two bendpoints, the second blending 1.0 and 7.0 at the weight 2/3: 1/3 + 14/3 is 5,
     * computed as 4.999999999999999. Against a box whose left edge is 5 the helper calls that left
     * and anchors on the left face; Archi, given {@code floor(4.999999999999999 + 1e-9) = 5}, would
     * call it within. The same holds on the right edge, where {@code x >= n} is false for the
     * helper and true for Archi, and on y. Pinned, not fixed: moving the helper would move every
     * consumer of the shared clip; what it does to the diagonal-terminal count is measured by
     * {@code RoundedFigureAnchorCensusTest}.
     */
    @Test
    public void referenceWithinTheEpsilonBelowAnEdge_shouldDisagreeWithArchi() {
        double w = 2 / 3.0;
        double x = 1.0 * (1 - w) + 7.0 * w;
        assertEquals(4.999999999999999, x, 0.0);
        assertEquals(5, (int) Math.floor(x + 1e-9));
        // left edge on 5, y within: Archi's centre arm, the helper's left face
        assertEquals(List.of(35, 220, 0, 1), asList(archiPoint(new int[]{5, 200, 60, 40}, null, 5,
                210)));
        assertArrayEquals(new double[]{5, 210},
                GeometryUtils.orthogonalExitPoint(5, 200, 60, 40, x, 210), 0.0);
        // right edge on 5 (box [-55,200,60,40]): Archi's right face at y = 210, the helper's
        // centre (-25,220)
        assertEquals(List.of(5, 210, 0, 0), asList(archiPoint(new int[]{-55, 200, 60, 40}, null, 5,
                210)));
        assertArrayEquals(new double[]{-25, 220},
                GeometryUtils.orthogonalExitPoint(-55, 200, 60, 40, x, 210), 0.0);
        // top edge on 5, x within: Archi's centre arm, the helper's top face
        assertEquals(List.of(130, 25, 0, 1), asList(archiPoint(new int[]{100, 5, 60, 40}, null, 110,
                5)));
        assertArrayEquals(new double[]{110, 5},
                GeometryUtils.orthogonalExitPoint(100, 5, 60, 40, 110, x), 0.0);
    }

    /**
     * A fractional rect is outside the contract — every producer passes Archi's integer bounds —
     * and this pin only characterises what the helper does with one: each coordinate is cast with
     * {@code (long)}, toward zero, neither floored, rounded nor ceiled. Owner [-70,100,40,40],
     * remote [-61.7,30.7,9,9], reference (-54.5,32). Cast, the remote is [-61,30,9,9]; its shrink
     * {@code (9 - 5) / 2 = 2} gives the centre region [-59,-54) x [32,37), which holds the
     * reference, so it is replaced by the overlap midpoint ((-61 + -52) / 2, (100 + 39) / 2) =
     * (-56, 69) on integer division: within on x, above, the top face at x = -56. Floored or
     * rounded, x would be -62 and the region [-60,-55); ceiled, y would be 31 and the region
     * [33,38): either way the reference is not replaced, and the top face is at x = -54.5.
     */
    @Test
    public void fractionalRemoteRect_isCastTowardZero() {
        double[] p = GeometryUtils.orthogonalExitPoint(-70, 100, 40, 40, -54.5, 32,
                new double[]{-61.7, 30.7, 9, 9});
        assertEquals(-56.0, p[0], 0.0);
        assertEquals(100.0, p[1], 0.0);
    }

    /**
     * Non-finite input is outside the contract — a reference is a blend of finite offsets and
     * every rect is Archi's integer bounds — so nothing is asserted about the point's coordinates,
     * which mean nothing, only that the helper returns one rather than throwing.
     */
    @Test
    public void nonFiniteInput_shouldNotThrow() {
        double[] odd = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double v : odd) {
            for (double[] remote : new double[][]{null, {300, 205, 41, 30}, {v, 205, 41, 30},
                    {300, v, 41, 30}, {300, 205, v, 30}, {300, 205, 41, v}}) {
                assertEquals(2, GeometryUtils.orthogonalExitPoint(100, 200, 60, 40, v, 210,
                        remote).length);
                assertEquals(2, GeometryUtils.orthogonalExitPoint(100, 200, 60, 40, 130, v,
                        remote).length);
                assertEquals(2, GeometryUtils.orthogonalExitPoint(100, 200, 60, 40, v, v,
                        remote).length);
                // a non-finite remote with a finite reference
                assertEquals(2, GeometryUtils.orthogonalExitPoint(100, 200, 60, 40, 320, 220,
                        remote).length);
                // a non-finite owner
                assertEquals(2, GeometryUtils.orthogonalExitPoint(v, 200, 60, 40, 130, 220,
                        remote).length);
                assertEquals(2, GeometryUtils.orthogonalExitPoint(100, 200, v, v, 130, 220,
                        remote).length);
            }
        }
    }

    private static List<Integer> asList(int[] a) {
        List<Integer> l = new ArrayList<>();
        for (int v : a) l.add(v);
        return l;
    }

    /** Every {@code k + fraction} for {@code k} within 2 px of each edge. */
    private static double[] references(double[] fractions, int... edges) {
        TreeSet<Double> refs = new TreeSet<>();
        for (int e : edges) {
            for (int k = e - 2; k <= e + 2; k++) {
                for (double f : fractions) refs.add(k + f);
            }
        }
        return refs.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** The anchor production returns equals both the hand-derived point and Archi's. */
    private static void assertAnchor(int[] own, int[] remote, int refX, int refY, int wantX,
            int wantY) {
        double[] p = GeometryUtils.orthogonalExitPoint(own[0], own[1], own[2], own[3], refX, refY,
                remote == null ? null : toDouble(remote));
        int[] archi = archiPoint(own, remote, refX, refY);
        String at = String.format("owner [%d,%d,%d,%d], reference (%d,%d)", own[0], own[1],
                own[2], own[3], refX, refY);
        assertEquals(at + " Archi x", wantX, archi[0]);
        assertEquals(at + " Archi y", wantY, archi[1]);
        assertEquals(at + " x", wantX, p[0], 0.0);
        assertEquals(at + " y", wantY, p[1], 0.0);
    }

    private static void assertOnArm(int[] own, RenderFaceDeriver.Outcome arm, double[] p,
            int refX, int refY, int[] remote) {
        double left = own[0];
        double right = own[0] + own[2];
        double top = own[1];
        double bottom = own[1] + own[3];
        boolean ok;
        switch (arm) {
            case CORNER_TOP_LEFT: ok = p[0] == left && p[1] == top; break;
            case CORNER_TOP_RIGHT: ok = p[0] == right && p[1] == top; break;
            case CORNER_BOTTOM_LEFT: ok = p[0] == left && p[1] == bottom; break;
            case CORNER_BOTTOM_RIGHT: ok = p[0] == right && p[1] == bottom; break;
            case FACE_TOP:
                ok = p[1] == top && p[0] >= left && p[0] < right;
                break;
            case FACE_BOTTOM:
                ok = p[1] == bottom && p[0] >= left && p[0] < right;
                break;
            case FACE_LEFT:
                ok = p[0] == left && p[1] >= top && p[1] < bottom;
                break;
            case FACE_RIGHT:
                ok = p[0] == right && p[1] >= top && p[1] < bottom;
                break;
            case ABSTAIN_REFERENCE_INSIDE_BOX:
                ok = p[0] == own[0] + own[2] / 2.0 && p[1] == own[1] + own[3] / 2.0;
                break;
            default:
                fail("a plain rectangle has no " + arm + " arm");
                return;
        }
        if (!ok) {
            fail(String.format("owner [%d,%d,%d,%d], reference (%d,%d), remote [%d,%d,%d,%d]:"
                    + " RenderFaceDeriver says %s, GeometryUtils anchors at (%.1f,%.1f)", own[0],
                    own[1], own[2], own[3], refX, refY, remote[0], remote[1], remote[2], remote[3],
                    arm, p[0], p[1]));
        }
    }

    /**
     * {@code OrthogonalAnchor.getLocation} of Archi {@code release_5.10.0} for a plain rectangle,
     * transcribed in its own integer arithmetic: the remote box shrunk by {@code (size - 5) / 2}
     * per axis (draw2d's shrink, clamped as {@link RenderFaceDeriver} clamps it) and tested with a
     * half-open {@code contains}, the overlap midpoint on integer division, half-open bands with a
     * {@code 0x0} corner, and the arms. Returns {@code {x, y, replaced ? 1 : 0, inside ? 1 : 0}}.
     */
    private static int[] archiPoint(int[] own, int[] remote, int refX, int refY) {
        int replaced = 0;
        if (remote != null && inArchiRemoteCentre(remote, refX, refY)) {
            refX = (Math.max(remote[0], own[0])
                    + Math.min(remote[0] + remote[2], own[0] + own[2])) / 2;
            refY = (Math.max(remote[1], own[1])
                    + Math.min(remote[1] + remote[3], own[1] + own[3])) / 2;
            replaced = 1;
        }
        int bx = own[0];
        int by = own[1];
        int bw = own[2];
        int bh = own[3];
        boolean xIn = refX >= bx && refX < bx + bw;
        boolean yIn = refY >= by && refY < by + bh;
        if (xIn && yIn) {
            // default arm: the figure's centre, Rectangle.getCenter() on integer division
            return new int[]{bx + bw / 2, by + bh / 2, replaced, 1};
        }
        int x = xIn ? refX : refX < bx ? bx : bx + bw;
        int y = yIn ? refY : refY < by ? by : by + bh;
        return new int[]{x, y, replaced, 0};
    }

    /** The remote centre region of {@link #archiPoint}: draw2d's clamped shrink, half-open. */
    private static boolean inArchiRemoteCentre(int[] remote, int refX, int refY) {
        int[] c = archiRemoteCentre(remote);
        return refX >= c[0] && refX < c[0] + c[2] && refY >= c[1] && refY < c[1] + c[3];
    }

    /** {@code [x, y, width, height]} of the remote centre region {@link #archiPoint} tests. */
    private static int[] archiRemoteCentre(int[] remote) {
        int shrinkH = (remote[2] - 5) / 2;
        int shrinkV = (remote[3] - 5) / 2;
        return new int[]{Math.min(remote[0] + remote[2] / 2, remote[0] + shrinkH),
                Math.min(remote[1] + remote[3] / 2, remote[1] + shrinkV),
                Math.max(0, remote[2] - 2 * shrinkH), Math.max(0, remote[3] - 2 * shrinkV)};
    }

    private static double[] toDouble(int[] r) {
        return new double[]{r[0], r[1], r[2], r[3]};
    }
}
