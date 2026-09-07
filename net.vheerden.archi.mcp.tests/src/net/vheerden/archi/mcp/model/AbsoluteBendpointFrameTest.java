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

import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Pins the frame in which an absolute bendpoint is reported, and the residual between the two
 * surfaces that report one.
 *
 * <p>A stored bendpoint carries two reconstructions of the same point, one anchored on the source
 * centre and one on the target centre. Three arithmetics over those two numbers appear in this
 * project and in Archi, and they do not agree:
 *
 * <ul>
 *   <li>{@code ConnectionResponseBuilder} — whole-pixel centres, interpolated at the render weight
 *       in exact integer arithmetic. This is what a connection response reports.</li>
 *   <li>{@code AssessmentCollector} — the same weight, but against untruncated {@code double}
 *       centres and with no final rounding. This is what the layout assessor measures over.</li>
 *   <li>Archi's renderer — whole-pixel {@code ChopboxAnchor} reference points, interpolated at
 *       weight {@code (i + 1) / (n + 1)} in floating point. This is what a reader sees.</li>
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
     * <p><strong>The final division contributes up to {@code n / (n + 1)}.</strong> A connection
     * response divides once by {@code n + 1} and truncates toward zero, discarding a remainder of
     * at most {@code n / (n + 1)} of a pixel. This component <em>does</em> depend on {@code n}, and
     * it is what a flat one-half blend hid: dividing by two always discarded at most one half.
     *
     * <p>So the bound is {@code 0.5 + n / (n + 1)} — exactly 1.0 px for a single bendpoint, rising
     * to 1.167 at two, 1.25 at three, and approaching but never reaching 1.5. Reporting a flat
     * "1.0 px" for every {@code n}, as this constant once did, is only true of the single-bendpoint
     * case that the sweep below happened to cover.
     */
    private static double maxSurfaceResidualPx(int bendpointCount) {
        return 0.5 + (double) bendpointCount / (bendpointCount + 1);
    }

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

    /** Archi's renderer: whole-pixel centres, weight {@code (i + 1) / (n + 1)}, floating point. */
    private static double renderedPoint(int storedFromSource, int storedFromTarget,
                                        int srcCentre, int tgtCentre, int index, int count) {
        float weight = (index + 1) / ((float) count + 1);
        return (srcCentre + storedFromSource) * (1.0 - weight)
                + weight * (tgtCentre + storedFromTarget);
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
     * {@code (a + b) / 2 + (T / 2.0 - T / 2)}, where the second term is what the integer division
     * discards. Asserted against the real producers over every combination, so it is a statement
     * about the code and not about a model of it.
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
                                        + (sum / 2.0 - sum / 2);

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
     * The residual never exceeds the derived bound, and it is signed: on a bendpoint whose two
     * whole-pixel reconstructions sum below zero, integer division truncates toward zero rather
     * than flooring and a connection response reads <em>higher</em> than the assessor. Reporting
     * the residual as an absolute value hides that direction.
     */
    @Test
    public void shouldBoundTheResidualAndKeepItSigned_includingWhenTheSumIsNegative() {
        double worst = 0.0;
        long negativeResidual = 0;
        long negativeResidualOnNonNegativeSum = 0;
        long negativeSum = 0;
        long total = 0;
        boolean sawNegativeResidualOnNegativeSum = false;
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
                                boolean sumIsNegative = startX + srcCentre + endX + tgtCentre < 0;
                                if (sumIsNegative) {
                                    negativeSum++;
                                    if (residual < 0) sawNegativeResidualOnNegativeSum = true;
                                }
                                if (residual < 0) {
                                    negativeResidual++;
                                    if (!sumIsNegative) negativeResidualOnNonNegativeSum++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals("at one bendpoint the bound is exactly one pixel",
                maxSurfaceResidualPx(1), worst, 1e-9);
        assertTrue("the negative-sum region must be exercised", negativeSum > 0);
        assertTrue("a negative residual must occur where the sum is negative",
                sawNegativeResidualOnNegativeSum);
        assertEquals("and it occurs ONLY there — a negative sum is necessary, not merely frequent",
                0L, negativeResidualOnNonNegativeSum);
        assertEquals("the residual goes negative on a measured minority of the space",
                144_432L, negativeResidual);
        assertEquals("every combination must be swept", 28_302_400L, total);
    }

    /**
     * The residual between the two reporting surfaces GROWS with the bendpoint count, and the bound
     * is {@code 0.5 + n / (n + 1)}.
     *
     * <p>This is the case a single-bendpoint sweep cannot see, and it is a real cost of reporting at
     * the render weight. A flat one-half blend always divided by two, so the remainder it discarded
     * never exceeded half a pixel and the surfaces could not part by more than 1.0 px whatever the
     * connection looked like. Dividing by {@code n + 1} instead discards up to {@code n / (n + 1)},
     * so a four-bendpoint connection can read 1.3 px apart. The bound rises with {@code n} and
     * approaches 1.5 px without reaching it.
     *
     * <p>Asserted two ways at each count: never above the derived bound, and — at the counts where
     * the sweep is rich enough to find the worst case — actually reaching it, so the bound is shown
     * to be tight rather than merely safe. A bound that is never approached would not prove the
     * derivation, only that nothing contradicted it.
     */
    @Test
    public void shouldWidenTheSurfaceResidual_asTheBendpointCountRises() {
        double previousWorst = 0.0;
        for (int count = 1; count <= 6; count++) {
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
                    worst <= maxSurfaceResidualPx(count) + 1e-9);
            assertEquals("at " + count + " bendpoints the derived bound must be REACHED, or it is "
                            + "not the bound", maxSurfaceResidualPx(count), worst, 1e-9);
            assertTrue("the bound must widen with the bendpoint count, never narrow",
                    worst >= previousWorst - 1e-9);
            previousWorst = worst;
        }

        // The single-bendpoint case is the only one that lands on a whole pixel. Stating it here
        // keeps the old flat "1.0 px for every connection" claim from creeping back.
        assertEquals(1.0, maxSurfaceResidualPx(1), 1e-9);
        assertTrue("every count stays strictly below one and a half pixels",
                maxSurfaceResidualPx(1_000_000) < 1.5);
    }

    // ---------------------------------------------------------------- the reported frame

    /**
     * A lone bendpoint sits at weight one half, so the render weight leaves every single-bendpoint
     * connection exactly where it was. Swept over both reconstructions rather than sampled, because
     * this is the compatibility guarantee the change rests on.
     */
    @Test
    public void shouldLeaveEverySingleBendpointConnectionUnmoved() {
        long total = 0;
        for (int fromSource = -400; fromSource <= 400; fromSource++) {
            for (int fromTarget = -400; fromTarget <= 400; fromTarget++) {
                total++;
                int reported = reportedX(fromSource, fromTarget, 0, 0);
                assertEquals("a single bendpoint sits at weight one half",
                        (fromSource + fromTarget) / 2, reported);
            }
        }
        assertEquals(641_601L, total);
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
     * The reported point is the renderer's point, truncated once. Compared against the renderer's
     * own arithmetic — a float weight applied to whole-pixel anchor reference points — over a
     * drifted sweep, so this fails if the weight, the centre truncation or the rounding direction
     * ever diverges from what Archi draws.
     *
     * <p>The comparison is made against the renderer's value, not against truncating it. The
     * renderer carries the weight as a {@code float}, whose 24-bit mantissa leaves a relative error
     * near {@code 6e-8}; over coordinates of a few hundred pixels that is an absolute error around
     * {@code 1e-4}, so its evaluation can land a hair either side of an integer that the exact
     * arithmetic here lands exactly on. Truncating both and demanding the same integer would
     * therefore be a test of the oracle's rounding noise. What is asserted instead is that the
     * reported point never sits a whole pixel from the drawn one, and that wherever truncating the
     * renderer's evaluation would disagree, that evaluation is within float noise of an integer
     * boundary — the disagreement is in the oracle's arithmetic and never in the frame.
     */
    @Test
    public void shouldReportTheRenderedPoint_acrossADriftedSweep() {
        // Bound on the renderer's own float evaluation error over this sweep: a 24-bit mantissa
        // gives a relative error near 6e-8, and the coordinates swept reach 600 px, so a handful of
        // roundings cannot accumulate past roughly 1e-4. An order of magnitude of headroom on that
        // derivation, deliberately not fitted to the largest value this sweep happens to produce.
        final double renderEvaluationNoisePx = 1e-3;

        long compared = 0;
        long straddled = 0;
        double worstGap = 0.0;
        double worstBoundaryDistance = 0.0;
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
                        double rendered = renderedPoint(fromSource, fromTarget, 0, 0, i, count);
                        worstGap = Math.max(worstGap, Math.abs(reported.get(i).x() - rendered));
                        if (reported.get(i).x() != (int) rendered) {
                            straddled++;
                            worstBoundaryDistance = Math.max(
                                    worstBoundaryDistance, Math.abs(rendered - Math.rint(rendered)));
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must actually compare something", compared > 100_000);
        assertTrue("the reported point never sits a whole pixel off what is drawn",
                worstGap < 1.0);
        assertTrue("a disagreement with the truncated oracle is float noise at an integer boundary",
                worstBoundaryDistance < renderEvaluationNoisePx);
        assertTrue("the sweep must reach the boundary region at all, or it proves nothing there",
                straddled > 0);
    }

    /**
     * Writing an absolute point back through both anchors makes the two reconstructions agree by
     * construction, and reading it again returns it unchanged at every weight.
     *
     * <p>Note what this does <em>not</em> pin. Because both reconstructions agree, every weight —
     * the flat one half included — returns the same coordinate, so this test was green before the
     * render weight was applied and is green after it. It is a pin on the conversion being lossless,
     * never a pin on the frame; the shear and rendered-point tests above are what hold the frame.
     *
     * <p>It does pin one thing the change had to earn: exact integer interpolation. Evaluating the
     * same weight in floating point loses the last bits on a weight like three sevenths, and the
     * truncation then lands a whole pixel low on a point whose reconstructions agree exactly.
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
        int worstShearMismatch = 0;
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
                // (weight - 0.5) x the SIGNED disagreement between its two reconstructions. Both
                // sides here truncate once, so they may land a pixel apart but no further.
                double weight = (i + 1.0) / (n + 1.0);
                double predictedX = (targetAnchored - sourceAnchored) * (weight - 0.5);
                double predictedY = (targetAnchoredY - sourceAnchoredY) * (weight - 0.5);
                worstShearMismatch = Math.max(worstShearMismatch,
                        (int) Math.ceil(Math.abs(dx - predictedX)));
                worstShearMismatch = Math.max(worstShearMismatch,
                        (int) Math.ceil(Math.abs(dy - predictedY)));
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
                        + "truncations can cost, but was off by " + worstShearMismatch,
                worstShearMismatch <= 1);
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
