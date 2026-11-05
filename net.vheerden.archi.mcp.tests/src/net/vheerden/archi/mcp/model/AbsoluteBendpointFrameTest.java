package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;
import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Pins the frame in which an absolute bendpoint is reported, and the residual between the two
 * surfaces that report one.
 *
 * <p>A stored bendpoint carries two reconstructions of the same point, one anchored on the source
 * centre and one on the target centre. Two arithmetics over those two numbers appear in this
 * project, and they do not agree:
 *
 * <ul>
 *   <li>{@code ConnectionResponseBuilder} — whole-pixel centres, blended with the renderer's own
 *       arithmetic through {@code GeometryUtils.drawnBendpointCoordinate}. This is what a connection
 *       response reports, and it is the pixel Archi draws.</li>
 *   <li>{@code AssessmentCollector} — the same weight, but against untruncated {@code double}
 *       centres and with no final rounding. This is what the layout assessor measures over.</li>
 *   <li>Archi's renderer (draw2d's {@code RelativeBendpoint}) — whole-pixel {@code ChopboxAnchor}
 *       reference points, weight {@code (i + 1) / (n + 1)} kept as a {@code float} but widened to
 *       {@code double} for both products, and the result floored as {@code (int) Math.floor(d +
 *       1e-9)}. This is what a reader sees. It is reproduced once, by {@code
 *       GeometryUtils.drawnBendpointCoordinate}, and both the builder and this class use that rather
 *       than a copy.</li>
 * </ul>
 *
 * <p>The builder is driven directly — every reported coordinate below comes out of the production
 * method, not out of a restatement of it. The assessor's arithmetic is <em>modelled</em> here rather
 * than driven, because the collector needs an EMF connection to run; that model is kept honest by
 * {@code AnchorDriftCollectorTest}, which drives the real collector and pins the same weight. What
 * this class measures is the residual <em>between</em> the two, which needs both in one place.
 *
 * <p>Nothing here needs a model or a display: both reconstructions are pure functions of the two
 * centres, the four stored offsets, and the bendpoint's index in its list.
 */
public class AbsoluteBendpointFrameTest {

    /** Source and target box dimensions swept by the exhaustive cases. */
    private static final int MAX_DIMENSION = 39;
    /** Origin offsets swept, small enough that a negative offset can drive the sum below zero. */
    private static final int MAX_ORIGIN = 6;
    /** Stored offset range swept, per axis. */
    private static final int OFFSET_RANGE = 9;

    /**
     * Largest disagreement, per axis, between the two reporting surfaces' reconstruction of one
     * bendpoint, as a function of how many bendpoints the connection carries.
     *
     * <p>Derived, not fitted, and it has two independent components.
     *
     * <p><strong>The centres contribute at most 0.5 px.</strong> A connection response truncates
     * each element centre to a whole pixel; the assessor keeps it exact. A centre is half-integral
     * whenever the box dimension is odd, so each of the two anchors can be understated by 0.5 px.
     * The blend is a convex combination of the two, and the weights sum to one, so their weighted
     * error is at most 0.5 px however the weight falls. This component is the same quantity as the
     * assessor's anchor-drift noise floor — both are the largest error a half-integral centre
     * stored through an integer offset can introduce — and it does not depend on {@code n}.
     *
     * <p><strong>The floor contributes up to {@code n / (n + 1)}, or one whole pixel.</strong> A
     * connection response floors the blend, as the renderer does. The exact blend is a multiple of
     * {@code 1 / (n + 1)}, so flooring it discards at most {@code n / (n + 1)} of a pixel. That is
     * the whole story only when every weight {@code (i + 1) / (n + 1)} is exact in a {@code float},
     * which is when {@code n + 1} is a power of two. At any other count the float weight can land a
     * whole-number blend a few millionths low, past the renderer's {@code 1e-9} nudge, and the floor
     * then discards a whole pixel.
     *
     * <p>So the bound is {@code 0.5 + n / (n + 1)} at one, three and seven bendpoints (1.0, 1.25 and
     * 1.375 px), and one and a half pixels at every other count, exceeded only by the last bits of
     * the {@code double} the assessor blends in. Both surfaces agree with what is drawn as often as
     * they can: the second component is the renderer's own rounding, reported faithfully.
     */
    private static double maxSurfaceResidualPx(int bendpointCount) {
        boolean exactFloatWeights = Integer.bitCount(bendpointCount + 1) == 1;
        return exactFloatWeights ? 0.5 + (double) bendpointCount / (bendpointCount + 1) : 1.5;
    }

    /** The last bits a {@code double} blend of whole pixels can carry past the bound. */
    private static final double BLEND_ULPS = 1e-9;

    // ---------------------------------------------------------------- the reporting surfaces

    /**
     * Models the assessor's reconstruction: the same render weight, but against untruncated centres
     * and with no final rounding. Mirrors {@code AssessmentCollector}; the real thing is driven by
     * {@code AnchorDriftCollectorTest}.
     */
    private static double collectorBlend(int storedFromSource, int storedFromTarget,
                                         double srcCentre, double tgtCentre, int index, int count) {
        double weight = (index + 1.0) / (count + 1.0);
        return (storedFromSource + srcCentre) * (1.0 - weight)
                + (storedFromTarget + tgtCentre) * weight;
    }

    /** Drives the production builder for a single bendpoint and returns the reported x. */
    private static int reportedX(int startX, int endX, int srcCentre, int tgtCentre) {
        List<AbsoluteBendpointDto> out = ConnectionResponseBuilder.convertRelativeToAbsolute(
                List.of(new BendpointDto(startX, 0, endX, 0)), srcCentre, 0, tgtCentre, 0);
        return out.get(0).x();
    }

    /**
     * Drives the production builder for a connection of {@code count} identical bendpoints and
     * returns the one reported at {@code index}. Identical offsets keep the two reconstructions
     * fixed while the weight varies with the index, which is what isolates the divisor's effect.
     */
    private static int reportedXAt(int startX, int endX, int srcCentre, int tgtCentre,
                                   int index, int count) {
        List<BendpointDto> stored = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            stored.add(new BendpointDto(startX, 0, endX, 0));
        }
        return ConnectionResponseBuilder.convertRelativeToAbsolute(
                stored, srcCentre, 0, tgtCentre, 0).get(index).x();
    }

    // ---------------------------------------------------------------- the residual, derived

    /**
     * The residual between the two surfaces is a closed form, not an empirical distribution.
     *
     * <p>Write {@code a = 0.5} when the source box dimension is odd and {@code 0} otherwise, {@code
     * b} likewise for the target, and {@code T} for the sum of both whole-pixel reconstructions.
     * Then the assessor reads higher than a connection response by exactly
     * {@code (a + b) / 2 + (T / 2.0 - floor(T / 2.0))}, where the second term is what the floor
     * discards. A lone bendpoint's weight of one half is exact in a {@code float}, so nothing else
     * enters. Asserted against the real producers over every combination, so it is a statement about
     * the code and not about a model of it.
     */
    @Test
    public void shouldMatchTheClosedFormResidual_acrossEverySingleBendpointCombination() {
        long total = 0;
        long mismatches = 0;
        for (int srcW = 0; srcW <= MAX_DIMENSION; srcW++) {
            for (int tgtW = 0; tgtW <= MAX_DIMENSION; tgtW++) {
                for (int srcX = 0; srcX <= MAX_ORIGIN; srcX++) {
                    for (int tgtX = 0; tgtX <= MAX_ORIGIN; tgtX++) {
                        int srcCentre = srcX + srcW / 2;
                        int tgtCentre = tgtX + tgtW / 2;
                        double srcCentreExact = srcX + srcW / 2.0;
                        double tgtCentreExact = tgtX + tgtW / 2.0;
                        for (int startX = -OFFSET_RANGE; startX <= OFFSET_RANGE; startX++) {
                            for (int endX = -OFFSET_RANGE; endX <= OFFSET_RANGE; endX++) {
                                total++;
                                int reported = reportedX(startX, endX, srcCentre, tgtCentre);
                                double assessed = collectorBlend(
                                        startX, endX, srcCentreExact, tgtCentreExact, 0, 1);

                                int sum = startX + srcCentre + endX + tgtCentre;
                                double oddSource = (srcW % 2 != 0) ? 0.5 : 0.0;
                                double oddTarget = (tgtW % 2 != 0) ? 0.5 : 0.0;
                                double predicted = (oddSource + oddTarget) / 2.0
                                        + (sum / 2.0 - Math.floorDiv(sum, 2));

                                if (Math.abs((assessed - reported) - predicted) > 1e-9) {
                                    mismatches++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals("every combination must be swept", 28_302_400L, total);
        assertEquals("the residual is a closed form, not a fitted distribution", 0L, mismatches);
    }

    /**
     * The residual never exceeds the derived bound, and it never goes negative, including where the
     * two whole-pixel reconstructions sum below zero.
     *
     * <p>Both of a connection response's roundings go down: the centres are truncated from a
     * non-negative half dimension, and the blend is floored as the renderer floors it. So the assessor
     * never reads lower than a response. That was not so while the response truncated the blend
     * toward zero: on a negative sum it read <em>higher</em> than the assessor, on 144,432 points of
     * this sweep, and that sign was the trace of reporting a pixel the renderer does not draw.
     */
    @Test
    public void shouldBoundTheResidual_andNeverLetItGoNegative_evenWhereTheSumIs() {
        double worst = 0.0;
        long negativeResidual = 0;
        long negativeSum = 0;
        long total = 0;
        for (int srcW = 0; srcW <= MAX_DIMENSION; srcW++) {
            for (int tgtW = 0; tgtW <= MAX_DIMENSION; tgtW++) {
                for (int srcX = 0; srcX <= MAX_ORIGIN; srcX++) {
                    for (int tgtX = 0; tgtX <= MAX_ORIGIN; tgtX++) {
                        int srcCentre = srcX + srcW / 2;
                        int tgtCentre = tgtX + tgtW / 2;
                        double srcCentreExact = srcX + srcW / 2.0;
                        double tgtCentreExact = tgtX + tgtW / 2.0;
                        for (int startX = -OFFSET_RANGE; startX <= OFFSET_RANGE; startX++) {
                            for (int endX = -OFFSET_RANGE; endX <= OFFSET_RANGE; endX++) {
                                total++;
                                int reported = reportedX(startX, endX, srcCentre, tgtCentre);
                                double residual = collectorBlend(
                                        startX, endX, srcCentreExact, tgtCentreExact, 0, 1) - reported;
                                worst = Math.max(worst, Math.abs(residual));
                                if (startX + srcCentre + endX + tgtCentre < 0) negativeSum++;
                                if (residual < 0) negativeResidual++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals("at one bendpoint the bound is exactly one pixel",
                maxSurfaceResidualPx(1), worst, 1e-9);
        assertTrue("the negative-sum region must be exercised", negativeSum > 0);
        assertEquals("the floor never reads above the assessor, on a negative sum either",
                0L, negativeResidual);
        assertEquals("every combination must be swept", 28_302_400L, total);
    }

    /**
     * The residual between the two reporting surfaces depends on the bendpoint count, and the bound
     * is the one {@link #maxSurfaceResidualPx} derives.
     *
     * <p>This is the case a single-bendpoint sweep cannot see. A flat one-half blend always divided by
     * two, so the surfaces could not part by more than 1.0 px whatever the connection looked like.
     * Flooring a blend at {@code (i + 1) / (n + 1)} discards up to {@code n / (n + 1)}, and where the
     * renderer's float weight is inexact it can discard a whole pixel, so a two-bendpoint connection
     * can read one and a half pixels apart while a three-bendpoint one cannot pass 1.25.
     *
     * <p>Asserted two ways at each count: never above the derived bound, and actually reaching it, so
     * the bound is shown to be tight rather than merely safe. A bound that is never approached would
     * not prove the derivation, only that nothing contradicted it. Counts one to eight cover both
     * kinds: three of them have exact float weights and five do not.
     */
    @Test
    public void shouldBoundTheSurfaceResidual_byWhetherTheFloatWeightIsExact() {
        for (int count = 1; count <= 8; count++) {
            double worst = 0.0;
            for (int srcW = 0; srcW <= MAX_DIMENSION; srcW++) {
                for (int tgtW = 0; tgtW <= MAX_DIMENSION; tgtW++) {
                    int srcCentre = srcW / 2;
                    int tgtCentre = 6 + tgtW / 2;
                    double srcCentreExact = srcW / 2.0;
                    double tgtCentreExact = 6 + tgtW / 2.0;
                    for (int startX = -OFFSET_RANGE; startX <= OFFSET_RANGE; startX++) {
                        for (int endX = -OFFSET_RANGE; endX <= OFFSET_RANGE; endX++) {
                            for (int index = 0; index < count; index++) {
                                int reported =
                                        reportedXAt(startX, endX, srcCentre, tgtCentre, index, count);
                                double assessed = collectorBlend(startX, endX,
                                        srcCentreExact, tgtCentreExact, index, count);
                                worst = Math.max(worst, Math.abs(assessed - reported));
                            }
                        }
                    }
                }
            }
            assertTrue("at " + count + " bendpoints the residual must not exceed the derived bound "
                            + maxSurfaceResidualPx(count) + ", but reached " + worst,
                    worst <= maxSurfaceResidualPx(count) + BLEND_ULPS);
            assertEquals("at " + count + " bendpoints the derived bound must be REACHED, or it is "
                            + "not the bound", maxSurfaceResidualPx(count), worst, BLEND_ULPS);
        }

        // The single-bendpoint case is the only one that lands on a whole pixel. Stating it here
        // keeps the old flat "1.0 px for every connection" claim from creeping back.
        assertEquals(1.0, maxSurfaceResidualPx(1), 1e-9);
        assertEquals("an exact float weight keeps the bound below one and a half pixels",
                1.375, maxSurfaceResidualPx(7), 1e-9);
        assertEquals("an inexact one reaches it", 1.5, maxSurfaceResidualPx(2), 1e-9);
    }

    // ---------------------------------------------------------------- the reported frame

    /**
     * A lone bendpoint sits at weight one half, which a {@code float} holds exactly, so it is reported
     * at the floored half sum of its two reconstructions, where the renderer draws it. Swept over both
     * reconstructions rather than sampled.
     *
     * <p>The render weight left every single-bendpoint connection where a flat blend put it. The
     * floor does not, on a negative odd sum: it reports a pixel lower than the old truncation toward
     * zero did. That is a quarter of this sweep, counted here so the move is stated, not inferred.
     */
    @Test
    public void shouldPlaceEverySingleBendpointAtTheFlooredHalfSum() {
        long total = 0;
        long movedOffTheTruncation = 0;
        for (int fromSource = -400; fromSource <= 400; fromSource++) {
            for (int fromTarget = -400; fromTarget <= 400; fromTarget++) {
                total++;
                int reported = reportedX(fromSource, fromTarget, 0, 0);
                assertEquals("a single bendpoint sits at weight one half, floored",
                        Math.floorDiv(fromSource + fromTarget, 2), reported);
                if (reported != (fromSource + fromTarget) / 2) movedOffTheTruncation++;
            }
        }
        assertEquals(641_601L, total);
        assertEquals("the negative odd sums, each a pixel lower than truncation put them",
                160_400L, movedOffTheTruncation);
    }

    /**
     * With more than one bendpoint and two reconstructions that disagree, the reported polyline is
     * sheared rather than translated: the weight moves the terminal bendpoints furthest and the
     * middle least. Both conditions are load-bearing — either alone leaves the reported point where
     * the flat blend put it, so a case with only one of them cannot detect the weight at all.
     */
    @Test
    public void shouldShearThePolyline_whenBendpointsExceedOneAndTheAnchorsDisagree() {
        int srcCentre = 100;
        int tgtCentre = 100;
        int drift = 60;

        // Four bendpoints, each reconstructing to 200 from the source and 260 from the target.
        List<BendpointDto> stored = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            stored.add(new BendpointDto(200 - srcCentre, 0, 200 + drift - tgtCentre, 0));
        }

        List<AbsoluteBendpointDto> reported = ConnectionResponseBuilder.convertRelativeToAbsolute(
                stored, srcCentre, 0, tgtCentre, 0);

        // weight (i+1)/5 of the way from 200 to 260
        assertEquals(212, reported.get(0).x());
        assertEquals(224, reported.get(1).x());
        assertEquals(236, reported.get(2).x());
        assertEquals(248, reported.get(3).x());

        // The flat blend put every one of them at the midpoint, and could not tell them apart.
        for (AbsoluteBendpointDto bp : reported) {
            assertNotEquals("the flat blend reported one coordinate for all four",
                    230, bp.x());
        }

        // The displacement from the midpoint is (weight - 0.5) x drift, largest at the terminals.
        assertEquals(-18, reported.get(0).x() - 230);
        assertEquals(-6, reported.get(1).x() - 230);
        assertEquals(6, reported.get(2).x() - 230);
        assertEquals(18, reported.get(3).x() - 230);
    }

    /**
     * A connection response reports every bendpoint at the pixel Archi draws it, on a drifted
     * connection too.
     *
     * <p>The oracle is {@link GeometryUtils#drawnBendpointCoordinate}, the project's single
     * reproduction of draw2d 3.23's {@code RelativeBendpoint.getLocation}. It is pinned by {@code
     * OffFaceHugDrawnGeometryTest}, and live PNG probes confirm it. draw2d keeps the weight {@code
     * (i + 1) / (n + 1)} as a {@code float}, widens it to {@code double} before either product, blends
     * the two reconstructions about whole-pixel reference points, and keeps {@code (int)
     * Math.floor(d + 1e-9)}. The builder now calls the same helper, so nothing here has a tolerance.
     *
     * <p>The sweep keeps the arithmetic the builder used before, the exact rational weight truncated
     * once toward zero, and counts where it parts from the drawn point. Two causes did that, and both
     * need the reconstructions to disagree: a negative blend that is not a whole number, which
     * truncation lifts and the floor does not; and a whole-number blend that draw2d's float weight
     * lands a few millionths low, which the floor drops a pixel. Those counts are what make "every
     * point equal" a claim with teeth: a builder that went back to its old arithmetic misses 480,450
     * of the points compared here.
     *
     * <p>Where the two reconstructions agree, both arithmetics give the drawn point at every weight.
     * That covers every route the router writes, because the router derives both stored offsets from
     * one absolute point, and it is asserted over the sweep's agreeing points and at a point measured
     * on a live route.
     */
    @Test
    public void shouldReportTheDrawnPoint_acrossADriftedSweep() {
        long compared = 0;
        long equal = 0;
        long oldNegativeTruncation = 0;
        long oldFloatWeight = 0;
        long agreeing = 0;
        long agreeingThatTheOldArithmeticMissed = 0;
        for (int count = 1; count <= 8; count++) {
            for (int fromSource = -600; fromSource <= 600; fromSource += 7) {
                for (int fromTarget = -600; fromTarget <= 600; fromTarget += 7) {
                    List<BendpointDto> stored = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        stored.add(new BendpointDto(fromSource, 0, fromTarget, 0));
                    }
                    List<AbsoluteBendpointDto> reported =
                            ConnectionResponseBuilder.convertRelativeToAbsolute(
                                    stored, 0, 0, 0, 0);
                    for (int i = 0; i < count; i++) {
                        compared++;
                        int drawn = GeometryUtils.drawnBendpointCoordinate(
                                fromSource, fromTarget, 0, 0, 0, 0, i, count);
                        if (reported.get(i).x() == drawn) equal++;
                        // The old arithmetic: the exact blend is numerator / (count + 1), and Java's
                        // long division truncates it toward zero.
                        long numerator = (long) fromSource * (count - i) + (long) fromTarget * (i + 1);
                        long divisor = count + 1L;
                        long truncated = numerator / divisor;
                        if (truncated != drawn) {
                            if (numerator % divisor == 0) oldFloatWeight++; else oldNegativeTruncation++;
                        }
                        if (fromSource == fromTarget) {
                            agreeing++;
                            if (truncated != drawn) agreeingThatTheOldArithmeticMissed++;
                        }
                    }
                }
            }
        }

        // Measured on a live route (OffFaceHugDrawnGeometryTest): bendpoint index 4 of 5, weight
        // 5/6, both reconstructions at 1229. Written here in the sweep's frame, with both reference
        // points at the origin.
        int liveReconstruction = 1229;
        List<BendpointDto> live = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            live.add(new BendpointDto(liveReconstruction, 0, liveReconstruction, 0));
        }
        int liveReported =
                ConnectionResponseBuilder.convertRelativeToAbsolute(live, 0, 0, 0, 0).get(4).x();
        int liveDrawn = GeometryUtils.drawnBendpointCoordinate(
                liveReconstruction, liveReconstruction, 0, 0, 0, 0, 4, 5);

        // Every claim is checked and every failure listed together, so one break shows each arm
        // it turns red rather than only the first.
        List<String> failures = new ArrayList<>();
        check(failures, "points compared", 1_065_024L, compared);
        check(failures, "reported == drawn, at every point", compared, equal);
        check(failures, "the old arithmetic: a negative fractional blend truncated toward zero",
                337_544L, oldNegativeTruncation);
        check(failures, "the old arithmetic: a whole-number blend the float weight drops",
                142_906L, oldFloatWeight);
        check(failures, "agreeing points in the sweep", 6_192L, agreeing);
        check(failures, "agreeing reconstructions are drawn where either arithmetic reports them",
                0L, agreeingThatTheOldArithmeticMissed);
        check(failures, "live point: reported", 1229L, liveReported);
        check(failures, "live point: drawn", 1229L, liveDrawn);
        assertEquals("the builder reports the pixel draw2d draws", List.of(), failures);
    }

    private static void check(List<String> failures, String what, long expected, long actual) {
        if (expected != actual) {
            failures.add(what + ": expected " + expected + " but was " + actual);
        }
    }

    /**
     * Writing an absolute point back through both anchors makes the two reconstructions agree by
     * construction, and reading it again returns it unchanged at every weight.
     *
     * <p>Note what this does <em>not</em> pin. Because both reconstructions agree, every weight —
     * the flat one half included — returns the same coordinate, so this test was green before the
     * render weight was applied and is green after it. It is a pin on the conversion being lossless,
     * never a pin on the frame; the shear and drawn-point tests above are what hold the frame.
     *
     * <p>It does hold the arithmetic to one thing: a blend that lost a last bit on a weight like
     * three sevenths, and then rounded down, would land a whole pixel low on a point whose
     * reconstructions agree exactly. The renderer's {@code 1e-9} nudge is what keeps the floor clear
     * of that, and this sweep is what shows it does.
     */
    @Test
    public void shouldRoundTripEveryWeightExactly_becauseBothReconstructionsAgree() {
        for (int count = 1; count <= 12; count++) {
            for (int x = -4000; x <= 4000; x += 3) {
                List<AbsoluteBendpointDto> absolute = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    absolute.add(new AbsoluteBendpointDto(x, x));
                }
                List<BendpointDto> relative = ConnectionResponseBuilder.convertAbsoluteToRelative(
                        absolute, 137, 241, -89, 613);
                List<AbsoluteBendpointDto> back = ConnectionResponseBuilder
                        .convertRelativeToAbsolute(relative, 137, 241, -89, 613);
                for (int i = 0; i < count; i++) {
                    assertEquals("x must survive the round trip at weight "
                            + (i + 1) + "/" + (count + 1), x, back.get(i).x());
                    assertEquals("y must survive the round trip at weight "
                            + (i + 1) + "/" + (count + 1), x, back.get(i).y());
                }
            }
        }
    }

    // ---------------------------------------------------------------- the corpus

    /**
     * The drifted fixture DOES discriminate, and every coordinate it moves matches the shear the
     * derivation predicts.
     *
     * <p>The router-written corpus cannot see a weight at all — every path there was written from a
     * single absolute point, so its two reconstructions agree and all weights coincide. This fixture
     * is the corpus member built to break that. It takes a real view's element geometry and its real
     * routes, adds two three-bendpoint and one four-bendpoint route written the same faithful way,
     * and then does what a person does: drags four elements, resizes a fifth, and re-routes nothing.
     * The stored offsets are untouched; the centres move; the two reconstructions part company by
     * exactly the RELATIVE displacement of the two endpoints.
     *
     * <p>That construction is what makes the controls trustworthy. Seven connections end up drifted
     * and seven do not, and the undrifted seven include a connection whose <em>both</em> endpoints
     * were dragged by the same (30, 30) — proof that drift measures relative displacement and not
     * movement, asserted below against the undisplaced fixture rather than asserted from a comment.
     *
     * <p>Note what this fixture is not: a routing-quality baseline. Its parent is an ELK
     * terminals-only baseline whose terminal segments are already diagonal — 45% of that fixture's
     * segments are non-orthogonal before anything here touches it — and drift then shears the rest.
     * Use it for anchor and frame work, never as an orthogonality reference.
     */
    @Test
    public void shouldMoveCoordinatesOnTheDriftedFixture_whereTheRouterWrittenCorpusCannot()
            throws IOException {
        ViewFixture drifted = ViewFixture.load("testdata/retail-bank-drifted-anchors-fixture.json");
        Map<String, ViewFixture.FixtureElement> byId = new LinkedHashMap<>();
        for (ViewFixture.FixtureElement e : drifted.getElements()) {
            byId.put(e.id(), e);
        }

        int bent = 0;
        int bendpoints = 0;
        int driftedConnections = 0;
        int undriftedConnections = 0;
        int movedCoordinates = 0;
        int maxMovement = 0;
        double worstShearMismatch = 0;
        int movedOffTheOldArithmetic = 0;
        Map<Integer, Integer> countDistribution = new LinkedHashMap<>();

        for (ViewFixture.FixtureConnection conn : drifted.getConnections()) {
            List<ViewFixture.FixtureBendpoint> stored = conn.bendpoints();
            if (stored.isEmpty()) continue;
            bent++;
            int n = stored.size();
            countDistribution.merge(n, 1, Integer::sum);

            ViewFixture.FixtureElement src = byId.get(conn.sourceId());
            ViewFixture.FixtureElement tgt = byId.get(conn.targetId());
            int srcCentreX = src.x() + src.w() / 2;
            int srcCentreY = src.y() + src.h() / 2;
            int tgtCentreX = tgt.x() + tgt.w() / 2;
            int tgtCentreY = tgt.y() + tgt.h() / 2;

            List<BendpointDto> relative = new ArrayList<>();
            boolean isDrifted = false;
            for (ViewFixture.FixtureBendpoint bp : stored) {
                bendpoints++;
                relative.add(new BendpointDto(bp.startX(), bp.startY(), bp.endX(), bp.endY()));
                if (bp.startX() + srcCentreX != bp.endX() + tgtCentreX
                        || bp.startY() + srcCentreY != bp.endY() + tgtCentreY) {
                    isDrifted = true;
                }
            }
            if (isDrifted) driftedConnections++; else undriftedConnections++;

            List<AbsoluteBendpointDto> reported =
                    ConnectionResponseBuilder.convertRelativeToAbsolute(
                            relative, srcCentreX, srcCentreY, tgtCentreX, tgtCentreY);

            int movedOnThisConnection = 0;
            for (int i = 0; i < n; i++) {
                BendpointDto bp = relative.get(i);
                int sourceAnchored = bp.startX() + srcCentreX;
                int targetAnchored = bp.endX() + tgtCentreX;
                int sourceAnchoredY = bp.startY() + srcCentreY;
                int targetAnchoredY = bp.endY() + tgtCentreY;

                int flatX = (sourceAnchored + targetAnchored) / 2;
                int flatY = (sourceAnchoredY + targetAnchoredY) / 2;
                int dx = reported.get(i).x() - flatX;
                int dy = reported.get(i).y() - flatY;
                if (dx != 0 || dy != 0) {
                    movedCoordinates++;
                    movedOnThisConnection++;
                    maxMovement = Math.max(maxMovement, Math.max(Math.abs(dx), Math.abs(dy)));
                }

                // The shear, from the derivation: a point is pulled off the halfway position by
                // (weight - 0.5) x the SIGNED disagreement between its two reconstructions. The
                // halfway position and the reported point are each rounded down once. Flooring the
                // exact blend E costs a fraction in [0, 1); flooring the half sum H costs 0 or a
                // half; so dx - predicted = (H - floor H) - (E - floor E) lies in (-1, 0.5]. Where
                // draw2d's float weight drops a whole-number E a pixel, the E term is exactly 1 and
                // the difference lies in [-1, -0.5]. Either way it never passes one pixel.
                double weight = (i + 1.0) / (n + 1.0);
                double predictedX = (targetAnchored - sourceAnchored) * (weight - 0.5);
                double predictedY = (targetAnchoredY - sourceAnchoredY) * (weight - 0.5);
                worstShearMismatch = Math.max(worstShearMismatch,
                        Math.abs(dx - predictedX));
                worstShearMismatch = Math.max(worstShearMismatch,
                        Math.abs(dy - predictedY));

                // Against the arithmetic the builder used before: the exact rational weight,
                // truncated once. On this fixture it parts from the drawn pixel only where the blend
                // is a whole number and the float weight drops it.
                long divisor = n + 1L;
                long numeratorX = (long) sourceAnchored * (n - i) + (long) targetAnchored * (i + 1);
                long numeratorY = (long) sourceAnchoredY * (n - i) + (long) targetAnchoredY * (i + 1);
                if (numeratorX / divisor != reported.get(i).x()) {
                    movedOffTheOldArithmetic++;
                    assertEquals("a whole-number blend, dropped by the float weight",
                            0L, numeratorX % divisor);
                    assertEquals(numeratorX / divisor - 1, reported.get(i).x());
                }
                if (numeratorY / divisor != reported.get(i).y()) {
                    movedOffTheOldArithmetic++;
                    assertEquals("a whole-number blend, dropped by the float weight",
                            0L, numeratorY % divisor);
                    assertEquals(numeratorY / divisor - 1, reported.get(i).y());
                }
            }

            if (!isDrifted) {
                assertEquals("a connection whose two reconstructions agree must not move at any "
                                + "weight — " + conn.sourceId() + " -> " + conn.targetId(),
                        0, movedOnThisConnection);
            }
        }

        // Population. Stated so a later edit to the fixture cannot quietly drain its discriminating
        // power without failing here.
        assertEquals("elements", 22, drifted.getElements().size());
        assertEquals("connections", 20, drifted.getConnections().size());
        assertEquals("connections carrying at least one bendpoint", 14, bent);
        assertEquals("bendpoints", 32, bendpoints);
        assertEquals("bendpoint counts present — the corpus has no route past two without this one",
                Map.of(2, 11, 3, 2, 4, 1), countDistribution);

        assertEquals("drifted connections", 7, driftedConnections);
        assertEquals("undrifted controls", 7, undriftedConnections);

        // The discrimination this fixture exists to provide.
        assertEquals("coordinates that move under the render weight", 16, movedCoordinates);
        assertEquals("largest movement, px", 15, maxMovement);
        assertTrue("every movement must match the predicted shear to within the one pixel two "
                        + "roundings down can cost, but was off by " + worstShearMismatch,
                worstShearMismatch <= 1 + BLEND_ULPS);
        assertEquals("coordinates the drawn-pixel arithmetic reports a pixel off the old one",
                6, movedOffTheOldArithmetic);
    }

    /**
     * Drift is RELATIVE displacement, not movement — proved against the fixture this one is derived
     * from rather than asserted from a comment.
     *
     * <p>Two elements were dragged by the same (30, 30). Both their centres therefore differ from
     * the undisplaced fixture, yet the connection between them is one of the seven undrifted
     * controls: its two reconstructions moved together and never parted. An endpoint that moved is
     * not evidence of drift, and that is the sharpest control the fixture carries.
     *
     * <p>What this does NOT pin: it is green under a change to the blend, deliberately. It asserts a
     * property of the fixture's construction — that the displacement was applied equally to both
     * endpoints — not a property of the arithmetic. Its job is to stop a later edit from quietly
     * draining the control away, so nobody cites it as coverage of the frame.
     */
    @Test
    public void shouldLeaveDriftAtZero_whenBothEndpointsWereDisplacedEqually() throws IOException {
        ViewFixture original =
                ViewFixture.load("testdata/retail-bank-application-collaboration-fixture.json");
        ViewFixture drifted =
                ViewFixture.load("testdata/retail-bank-drifted-anchors-fixture.json");

        Map<String, ViewFixture.FixtureElement> before = new LinkedHashMap<>();
        for (ViewFixture.FixtureElement e : original.getElements()) before.put(e.id(), e);
        Map<String, ViewFixture.FixtureElement> after = new LinkedHashMap<>();
        for (ViewFixture.FixtureElement e : drifted.getElements()) after.put(e.id(), e);

        int bothEndpointsMovedYetUndrifted = 0;
        for (ViewFixture.FixtureConnection conn : drifted.getConnections()) {
            if (conn.bendpoints().isEmpty()) continue;
            ViewFixture.FixtureElement srcBefore = before.get(conn.sourceId());
            ViewFixture.FixtureElement tgtBefore = before.get(conn.targetId());
            if (srcBefore == null || tgtBefore == null) continue;   // authored route, no original
            ViewFixture.FixtureElement src = after.get(conn.sourceId());
            ViewFixture.FixtureElement tgt = after.get(conn.targetId());

            boolean srcMoved = src.x() != srcBefore.x() || src.y() != srcBefore.y()
                    || src.w() != srcBefore.w() || src.h() != srcBefore.h();
            boolean tgtMoved = tgt.x() != tgtBefore.x() || tgt.y() != tgtBefore.y()
                    || tgt.w() != tgtBefore.w() || tgt.h() != tgtBefore.h();
            if (!srcMoved || !tgtMoved) continue;

            int srcCentreX = src.x() + src.w() / 2;
            int srcCentreY = src.y() + src.h() / 2;
            int tgtCentreX = tgt.x() + tgt.w() / 2;
            int tgtCentreY = tgt.y() + tgt.h() / 2;
            boolean isDrifted = false;
            for (ViewFixture.FixtureBendpoint bp : conn.bendpoints()) {
                if (bp.startX() + srcCentreX != bp.endX() + tgtCentreX
                        || bp.startY() + srcCentreY != bp.endY() + tgtCentreY) {
                    isDrifted = true;
                }
            }
            if (!isDrifted) bothEndpointsMovedYetUndrifted++;
        }

        assertEquals("a connection whose endpoints both moved by the same amount must stay undrifted",
                1, bothEndpointsMovedYetUndrifted);
    }

    /**
     * The corpus cannot discriminate this change, and says so with numbers.
     *
     * <p>Every fixture path was written by the router, which derives both stored offsets from one
     * absolute point, so the two reconstructions of every bendpoint in the corpus agree exactly. A
     * weight only moves a point once they disagree, so no reported coordinate in the corpus moves —
     * which is a measurement of the corpus, not evidence that the change is inert. This test exists
     * to keep that stated: if a fixture ever gains a drifted connection, the counts below change and
     * the corpus becomes able to discriminate.
     */
    @Test
    public void shouldMoveNoCoordinateInTheCorpus_becauseNoStoredPathThereIsDrifted() throws IOException {
        String[] fixtures = {
            "app-architecture-view",
            "v4-integration-architecture-oracle",
            "hh-source-clone",
            "st-source-clone",
            "retail-bank-business-architecture",
            "retail-bank-application-collaboration",
        };

        int elements = 0;
        int bendpoints = 0;
        int bentConnections = 0;
        int driftedConnections = 0;
        int movedCoordinates = 0;
        int maxMovement = 0;
        Map<String, Integer> elementsPerFixture = new LinkedHashMap<>();
        Map<String, List<int[]>> substrates = new LinkedHashMap<>();

        for (String name : fixtures) {
            ViewFixture fixture = ViewFixture.load("testdata/" + name + "-fixture.json");
            Map<String, ViewFixture.FixtureElement> byId = new LinkedHashMap<>();
            for (ViewFixture.FixtureElement e : fixture.getElements()) {
                byId.put(e.id(), e);
            }
            elements += fixture.getElements().size();
            elementsPerFixture.put(name, fixture.getElements().size());

            // Key a substrate by its multiset of element geometry, so two fixtures laid out over
            // identical boxes collapse to one entry however their connections differ.
            List<int[]> geometry = new ArrayList<>();
            for (ViewFixture.FixtureElement e : fixture.getElements()) {
                geometry.add(new int[]{e.x(), e.y(), e.w(), e.h()});
            }
            geometry.sort((l, r) -> {
                for (int k = 0; k < 4; k++) {
                    if (l[k] != r[k]) return Integer.compare(l[k], r[k]);
                }
                return 0;
            });
            StringBuilder signature = new StringBuilder();
            for (int[] g : geometry) {
                signature.append(g[0]).append(',').append(g[1]).append(',')
                        .append(g[2]).append(',').append(g[3]).append(';');
            }
            substrates.put(signature.toString(), geometry);

            for (ViewFixture.FixtureConnection conn : fixture.getConnections()) {
                List<ViewFixture.FixtureBendpoint> stored = conn.bendpoints();
                if (stored.isEmpty()) continue;
                bentConnections++;

                ViewFixture.FixtureElement src = byId.get(conn.sourceId());
                ViewFixture.FixtureElement tgt = byId.get(conn.targetId());
                if (src == null || tgt == null) continue;
                int srcCentreX = src.x() + src.w() / 2;
                int srcCentreY = src.y() + src.h() / 2;
                int tgtCentreX = tgt.x() + tgt.w() / 2;
                int tgtCentreY = tgt.y() + tgt.h() / 2;

                List<BendpointDto> relative = new ArrayList<>();
                boolean drifted = false;
                for (ViewFixture.FixtureBendpoint bp : stored) {
                    bendpoints++;
                    relative.add(new BendpointDto(bp.startX(), bp.startY(), bp.endX(), bp.endY()));
                    if (bp.startX() + srcCentreX != bp.endX() + tgtCentreX
                            || bp.startY() + srcCentreY != bp.endY() + tgtCentreY) {
                        drifted = true;
                    }
                }
                if (drifted) driftedConnections++;

                List<AbsoluteBendpointDto> reported =
                        ConnectionResponseBuilder.convertRelativeToAbsolute(
                                relative, srcCentreX, srcCentreY, tgtCentreX, tgtCentreY);
                for (int i = 0; i < relative.size(); i++) {
                    BendpointDto bp = relative.get(i);
                    int flatX = (bp.startX() + srcCentreX + bp.endX() + tgtCentreX) / 2;
                    int flatY = (bp.startY() + srcCentreY + bp.endY() + tgtCentreY) / 2;
                    int dx = Math.abs(reported.get(i).x() - flatX);
                    int dy = Math.abs(reported.get(i).y() - flatY);
                    if (dx != 0 || dy != 0) {
                        movedCoordinates++;
                        maxMovement = Math.max(maxMovement, Math.max(dx, dy));
                    }
                }
            }
        }

        // Raw totals across the six fixtures, and the deduplicated total beside them. Two fixtures —
        // hh-source-clone and v4-integration-architecture-oracle — carry identical element geometry,
        // so reporting only the raw figure double-counts one substrate. Both numbers are computed
        // here rather than asserted from a comment.
        assertEquals("raw element count across six fixtures", 169, elements);
        assertEquals("distinct element-geometry substrates", 5, substrates.size());
        int deduplicated = 0;
        for (List<int[]> substrate : substrates.values()) {
            deduplicated += substrate.size();
        }
        assertEquals("deduplicated element count over the distinct substrates", 143, deduplicated);
        assertEquals("the duplicated pair really is a duplicate, not merely equal in size",
                elementsPerFixture.get("hh-source-clone"),
                elementsPerFixture.get("v4-integration-architecture-oracle"));
        assertEquals("connections carrying at least one bendpoint", 30, bentConnections);
        assertEquals("bendpoints in the corpus", 60, bendpoints);

        assertEquals("no stored path in the corpus is drifted", 0, driftedConnections);
        assertEquals("so no reported coordinate moves", 0, movedCoordinates);
        assertEquals("and the largest movement is nothing", 0, maxMovement);
    }
}
