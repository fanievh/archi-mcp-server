package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RenderFaceDeriver.AnchorModel;
import net.vheerden.archi.mcp.model.RenderFaceDeriver.Anchoring;
import net.vheerden.archi.mcp.model.RenderFaceDeriver.Fallback;
import net.vheerden.archi.mcp.model.RenderFaceDeriver.Outcome;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Pins the render-face derivation arm by arm, on geometry whose answer is known without running it.
 *
 * <p>The coverage census next door measures how often the derivation produces a value over a corpus.
 * This class measures whether each value is <em>right</em>, and every case here was chosen so that a
 * plausible wrong implementation gives a different answer: a step omitted, a band widened by one
 * pixel, a truncation replaced by a floor, or a bound reconstructed from a centre instead of read.
 */
public class RenderFaceDerivationTest {

    /** A plain rectangular element: the chopbox fallback, no corner. */
    private static final Anchoring PLAIN = RenderFaceDeriver.anchoringOf("ApplicationComponent", 0, 0, 0);

    /** Far enough away that the reference replacement cannot fire and confuse a reading. */
    private static final int[] ELSEWHERE = { 100000, 100000, 10, 10 };

    // --------------------------------------------------------------- the reference replacement

    /**
     * The reference is replaced when it sits at the centre of the figure at the other end, and the
     * replacement changes the answer.
     *
     * <p>The two boxes overlap by ten pixels in x. Aimed at the remote centre the reference is past
     * the right edge <em>and</em> past the bottom, which is a corner; replaced by the midpoint of
     * the x-overlap it is inside the horizontal extent, which is the bottom face — and the bottom
     * face is what Archi draws, a line straight down out of the overlap. A derivation that skipped
     * the replacement would answer corner and publish nothing.
     */
    @Test
    public void shouldReachADifferentFace_whenTheReferenceReplacementIsApplied() {
        int[] own = { 0, 0, 100, 100 };
        int[] remote = { 90, 300, 100, 100 };
        int[] remoteCentre = RenderFaceDeriver.centreOf(remote);
        assertArrayEquals("the reference this arm aims at", new int[] { 140, 350 }, remoteCentre);

        assertEquals("with the replacement, which is what the anchor does",
                Outcome.FACE_BOTTOM,
                RenderFaceDeriver.derive(own, remote, remoteCentre[0], remoteCentre[1],
                        PLAIN.corner(), AnchorModel.ORTHOGONAL));
        assertEquals("the same point, with no remote box able to trigger the replacement",
                Outcome.CORNER_BOTTOM_RIGHT,
                RenderFaceDeriver.derive(own, ELSEWHERE, remoteCentre[0], remoteCentre[1],
                        PLAIN.corner(), AnchorModel.ORTHOGONAL));
    }

    /**
     * The rectangle the replacement test uses is five pixels across on an odd dimension and six on
     * an even one, because the shrink halves on integer division.
     *
     * <p>Calling it "a 5x5 rectangle at the centre", which is what the anchor's own comment calls
     * it, would put the boundary one pixel out on every even-sized figure — and one pixel at this
     * boundary decides whether the replacement fires at all.
     */
    @Test
    public void shouldSpanSixPixels_whenTheRemoteDimensionIsEven() {
        int[] own = { 0, 0, 40, 100 };
        // Width 120 shrinks by (120 - 5) / 2 = 57, leaving [x + 57, x + 63) — six pixels.
        int[] even = { 0, 300, 120, 120 };
        assertEquals("the near edge of the six-pixel band triggers the replacement",
                Outcome.FACE_BOTTOM,
                RenderFaceDeriver.derive(own, even, 57, 362, PLAIN.corner(), AnchorModel.ORTHOGONAL));
        assertEquals("one pixel past its far edge does not, and the answer changes",
                Outcome.CORNER_BOTTOM_RIGHT,
                RenderFaceDeriver.derive(own, even, 63, 362, PLAIN.corner(), AnchorModel.ORTHOGONAL));

        // Width 121 shrinks by 58, leaving [x + 58, x + 63) — five. The same reference that fired
        // on the even box does not fire here, which is what makes the halving load-bearing.
        int[] odd = { 0, 300, 121, 120 };
        assertEquals("the same reference against an odd-width remote misses the band",
                Outcome.CORNER_BOTTOM_RIGHT,
                RenderFaceDeriver.derive(own, odd, 57, 362, PLAIN.corner(), AnchorModel.ORTHOGONAL));
        assertEquals("one pixel further in, it fires again",
                Outcome.FACE_BOTTOM,
                RenderFaceDeriver.derive(own, odd, 58, 362, PLAIN.corner(), AnchorModel.ORTHOGONAL));
    }

    // ------------------------------------------------------------------------ the four faces

    /** Each face is reachable, on references placed where only one answer is possible. */
    @Test
    public void shouldNameTheFace_whenTheReferenceIsSquarelyBeyondOneEdge() {
        int[] own = { 100, 100, 80, 60 };
        assertEquals(Outcome.FACE_TOP, orth(own, 140, 20));
        assertEquals(Outcome.FACE_BOTTOM, orth(own, 140, 400));
        assertEquals(Outcome.FACE_LEFT, orth(own, 20, 130));
        assertEquals(Outcome.FACE_RIGHT, orth(own, 400, 130));
    }

    /** The bands are half-open, so the edge itself belongs to the interior and not to the face. */
    @Test
    public void shouldTreatTheFarEdgeAsOutside_whenTheReferenceIsExactlyOnIt() {
        int[] own = { 100, 100, 80, 60 };
        assertEquals("one pixel inside the right edge is still the interior",
                Outcome.ABSTAIN_REFERENCE_INSIDE_BOX, orth(own, 179, 130));
        assertEquals("the right edge itself is outside", Outcome.FACE_RIGHT, orth(own, 180, 130));
        assertEquals("the left edge itself is inside",
                Outcome.ABSTAIN_REFERENCE_INSIDE_BOX, orth(own, 100, 130));
        assertEquals("one pixel left of it is outside", Outcome.FACE_LEFT, orth(own, 99, 130));
    }

    /** Every corner arm is reachable and every one of them publishes nothing. */
    @Test
    public void shouldPublishNothing_whenTheAttachmentIsACorner() {
        int[] own = { 100, 100, 80, 60 };
        assertEquals(Outcome.CORNER_TOP_LEFT, orth(own, 20, 20));
        assertEquals(Outcome.CORNER_TOP_RIGHT, orth(own, 400, 20));
        assertEquals(Outcome.CORNER_BOTTOM_LEFT, orth(own, 20, 400));
        assertEquals(Outcome.CORNER_BOTTOM_RIGHT, orth(own, 400, 400));
        assertNull(Outcome.CORNER_TOP_LEFT.publishedValue());
        assertNull(Outcome.CORNER_TOP_RIGHT.publishedValue());
        assertNull(Outcome.CORNER_BOTTOM_LEFT.publishedValue());
        assertNull(Outcome.CORNER_BOTTOM_RIGHT.publishedValue());
        assertNull("and the gate publishes nothing for one either",
                RenderFaceDeriver.publishedFace(own, ELSEWHERE, 20, 20, PLAIN));
    }

    // --------------------------------------------------------------------------- the abstentions

    @Test
    public void shouldAbstain_whenTheElementHasNoSize() {
        assertEquals(Outcome.ABSTAIN_ZERO_SIZE_ELEMENT,
                RenderFaceDeriver.classify(new int[] { 50, 50, 0, 0 }, ELSEWHERE, 10, 10, PLAIN));
        assertEquals(Outcome.ABSTAIN_ZERO_SIZE_ELEMENT,
                RenderFaceDeriver.classify(new int[] { 50, 50, 80, 0 }, ELSEWHERE, 10, 10, PLAIN));
    }

    @Test
    public void shouldAbstain_whenTheReferenceLiesInsideTheBox() {
        assertEquals(Outcome.ABSTAIN_REFERENCE_INSIDE_BOX,
                RenderFaceDeriver.classify(new int[] { 0, 0, 100, 100 }, ELSEWHERE, 50, 50, PLAIN));
    }

    @Test
    public void shouldAbstain_whenBoundsCannotBeRead() {
        assertEquals(Outcome.ABSTAIN_UNREADABLE_BOUNDS,
                RenderFaceDeriver.classify(null, ELSEWHERE, 10, 10, PLAIN));
        assertEquals(Outcome.ABSTAIN_UNREADABLE_BOUNDS,
                RenderFaceDeriver.classify(new int[] { 0, 0, 10, 10 }, null, 10, 10, PLAIN));
    }

    /**
     * A Junction anchors on an ellipse whatever the preference says: it is the only edit part in
     * the product that refuses the orthogonal anchor.
     */
    @Test
    public void shouldAbstain_whenTheEndpointIsAJunction() {
        Anchoring junction = RenderFaceDeriver.anchoringOf("Junction", 0, 15, 15);
        assertNull("no orthogonal anchor is installed at all", junction.corner());
        assertEquals(Outcome.ABSTAIN_ELLIPSE_ANCHOR,
                RenderFaceDeriver.classify(new int[] { 0, 0, 15, 15 }, ELSEWHERE, -50, 7, junction));
    }

    /**
     * The alternate grouping figure shifts its preference-off anchor by a tab height that is figure
     * state rather than model state, so the two configurations cannot be compared and the endpoint
     * declines rather than assuming one of them.
     */
    @Test
    public void shouldAbstain_whenTheGroupingUsesItsAlternateFigure() {
        Anchoring alternate = RenderFaceDeriver.anchoringOf("Grouping", 1, 200, 100);
        assertEquals(Fallback.UNKNOWN, alternate.fallback());
        assertEquals(Outcome.ABSTAIN_UNKNOWN_FALLBACK_ANCHOR,
                RenderFaceDeriver.classify(new int[] { 0, 0, 200, 100 }, ELSEWHERE, -50, 50, alternate));
        assertEquals("the default grouping figure is an ordinary rectangle",
                Fallback.CHOPBOX, RenderFaceDeriver.anchoringOf("Grouping", 0, 200, 100).fallback());
    }

    /**
     * Where the two anchor algorithms name different faces, nothing is published.
     *
     * <p>This is the case the configuration ruling exists for. The boxes overlap by four pixels in
     * y and not at all in x. The installed anchor replaces the reference with the midpoint of that
     * overlap and answers left — a horizontal line out of the overlapping strip, which is what
     * Archi draws. The fallback anchor uses the unreplaced reference, whose aspect-normalised ray
     * leaves through the top. Both are true of their own configuration; neither is true of both,
     * so the field carries nothing.
     */
    @Test
    public void shouldAbstain_whenTheTwoAnchorModelsNameDifferentFaces() {
        int[] own = { 0, 0, 40, 30 };
        int[] remote = { -130, -116, 120, 120 };
        int[] ref = RenderFaceDeriver.centreOf(remote);
        assertArrayEquals(new int[] { -70, -56 }, ref);

        assertEquals("the anchor Archi installs", Outcome.FACE_LEFT,
                RenderFaceDeriver.derive(own, remote, ref[0], ref[1], PLAIN.corner(),
                        AnchorModel.ORTHOGONAL));
        assertEquals("the anchor the preference-off configuration installs", Outcome.FACE_TOP,
                RenderFaceDeriver.derive(own, remote, ref[0], ref[1], PLAIN.corner(),
                        AnchorModel.CHOPBOX));
        assertEquals(Outcome.ABSTAIN_ANCHOR_MODELS_DISAGREE,
                RenderFaceDeriver.classify(own, remote, ref[0], ref[1], PLAIN));
        assertNull(RenderFaceDeriver.publishedFace(own, remote, ref[0], ref[1], PLAIN));
    }

    // ------------------------------------------------------------------ the rounded figure arm

    /**
     * The rounded delegate is the <em>default</em> figure for twenty ArchiMate types, not an
     * alternate one, and its arc is the constant every such delegate carries.
     */
    @Test
    public void shouldCarryTheRoundedArc_whenTheFigureIsAtItsDefaultVariant() {
        for (String type : List.of("BusinessService", "ApplicationService", "TechnologyService",
                "BusinessProcess", "ApplicationProcess", "TechnologyProcess",
                "BusinessFunction", "ApplicationFunction", "TechnologyFunction",
                "BusinessInteraction", "ApplicationInteraction", "TechnologyInteraction",
                "BusinessEvent", "ApplicationEvent", "TechnologyEvent", "ImplementationEvent",
                "WorkPackage", "Capability", "CourseOfAction", "ValueStream")) {
            Anchoring a = RenderFaceDeriver.anchoringOf(type, 0, 120, 55);
            assertArrayEquals(type + " draws rounded by default", new int[] { 20, 20 }, a.corner());
            assertEquals(type + " falls back to the rounded anchor",
                    Fallback.ROUNDED_RECTANGLE, a.fallback());
        }
        assertArrayEquals("an element with no rounded delegate", new int[] { 0, 0 },
                RenderFaceDeriver.anchoringOf("ApplicationComponent", 0, 120, 55).corner());
    }

    /** Away from the default variant the delegate falls away and the figure is a plain rectangle. */
    @Test
    public void shouldBecomePlain_whenTheFigureLeavesItsDefaultVariant() {
        Anchoring a = RenderFaceDeriver.anchoringOf("BusinessProcess", 1, 120, 55);
        assertArrayEquals(new int[] { 0, 0 }, a.corner());
        assertEquals(Fallback.CHOPBOX, a.fallback());
    }

    /** A service keeps a rounded delegate on its alternate figure, with a size-derived arc. */
    @Test
    public void shouldDeriveTheArcFromTheSize_whenAServiceLeavesItsDefaultVariant() {
        assertArrayEquals("height wins when it is the lesser", new int[] { 55, 55 },
                RenderFaceDeriver.anchoringOf("ApplicationService", 1, 120, 55).corner());
        assertArrayEquals("four fifths of the width wins when the figure is tall",
                new int[] { 96, 300 },
                RenderFaceDeriver.anchoringOf("ApplicationService", 1, 120, 300).corner());
    }

    /**
     * A rounded figure still names its faces; only the attachments landing on the arc decline.
     *
     * <p>Twenty types draw rounded by default, so treating the whole population as underivable —
     * the cautious reading — would have discarded a large part of it. It is not necessary: the face
     * arms return the reference's own coordinate projected onto the edge, whatever the corner
     * dimension is.
     */
    @Test
    public void shouldStillNameTheFace_whenTheFigureDrawsRounded() {
        Anchoring rounded = RenderFaceDeriver.anchoringOf("ApplicationFunction", 0, 120, 55);
        int[] own = { 100, 100, 120, 55 };
        assertEquals("left", RenderFaceDeriver.publishedFace(own, ELSEWHERE, 20, 127, rounded));
        assertEquals("right", RenderFaceDeriver.publishedFace(own, ELSEWHERE, 400, 127, rounded));
        assertEquals("top", RenderFaceDeriver.publishedFace(own, ELSEWHERE, 160, 10, rounded));
        assertEquals("bottom", RenderFaceDeriver.publishedFace(own, ELSEWHERE, 160, 400, rounded));
    }

    /**
     * Within a corner's arc the attachment is a point on the curve, which belongs to neither face
     * that meets there, so nothing is published.
     *
     * <p>The corner bands are the two the arc widens: on a 120-wide figure with a 20-pixel arc the
     * middle band runs from x + 10 to x + 110, and a reference at x + 5 sits in the left corner
     * band rather than the middle one. On a plain rectangle those bands are empty and the same
     * reference names the top face, which is the whole difference the corner dimension makes.
     */
    @Test
    public void shouldAbstain_whenTheAttachmentLandsOnARoundedCornerArc() {
        Anchoring rounded = RenderFaceDeriver.anchoringOf("ApplicationFunction", 0, 120, 55);
        int[] own = { 100, 100, 120, 55 };
        assertEquals(Outcome.ABSTAIN_ROUNDED_CORNER_ARC,
                RenderFaceDeriver.classify(own, ELSEWHERE, 105, 10, rounded));
        assertNull(RenderFaceDeriver.publishedFace(own, ELSEWHERE, 105, 10, rounded));
        assertEquals("the same reference on a plain rectangle names the top face",
                "top", RenderFaceDeriver.publishedFace(own, ELSEWHERE, 105, 10, PLAIN));
    }

    /**
     * The preference-off anchor for a rounded figure narrows the chopbox answer — and over a swept
     * range of geometry that narrowing never once bites where the installed anchor named a face.
     *
     * <p>{@code RoundedRectangleAnchor} keeps the chopbox point only outside the four corner zones,
     * so modelling it as plain chopbox would have over-reported agreement on the population that
     * most needed it. Sweeping four widths and three heights against every reference in a
     * 500-by-500 window — three million cases — the narrowing fires on none of the terminals where
     * the installed anchor named a face. The reason is a single pixel: the installed anchor's
     * middle band starts at {@code x + arc / 2} while the fallback's corner zone starts at
     * {@code x - 1 + arc / 2}, so the fallback is always the more permissive of the two. The
     * narrowing is kept because it is what the anchor does; this pin records that it costs nothing.
     */
    @Test
    public void shouldNeverNarrowAFaceAway_whenTheFallbackIsTheRoundedAnchor() {
        Anchoring rounded = RenderFaceDeriver.anchoringOf("ApplicationFunction", 0, 0, 0);
        int narrowedAway = 0;
        int facesSwept = 0;
        for (int w : new int[] { 40, 60, 120, 181 }) {
            for (int h : new int[] { 30, 55, 120 }) {
                int[] own = { 100, 100, w, h };
                for (int refX = -100; refX < 400; refX++) {
                    for (int refY = -100; refY < 400; refY++) {
                        Outcome installed = RenderFaceDeriver.derive(own, ELSEWHERE, refX, refY,
                                rounded.corner(), AnchorModel.ORTHOGONAL);
                        if (installed.publishedValue() == null) {
                            continue;
                        }
                        facesSwept++;
                        if (RenderFaceDeriver.classify(own, ELSEWHERE, refX, refY, rounded)
                                == Outcome.ABSTAIN_ROUNDED_CORNER_ARC) {
                            narrowedAway++;
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must actually reach faces", facesSwept > 100000);
        assertEquals("the rounded fallback narrows away no face the installed anchor named",
                0, narrowedAway);
    }

    // ---------------------------------------------------- untruncated bounds, and the one pixel

    /**
     * The face is derived from the element's own bounds, and a bound reconstructed from the
     * published centre gives a different answer on an odd width.
     *
     * <p>A caller holds the truncated centre, not the bounds. Reconstructing the box from it puts
     * the near edge exactly right — the same truncation is added back — and the far edge one pixel
     * short: 280 against a true 281 on a 181-wide element at x = 100. A reference on that true
     * edge is inside the box for the renderer and past it for the caller, so the caller's own test
     * answers with a corner where the line is drawn leaving the top. Over the fixture corpus 100 of
     * 191 elements carry an odd dimension, so this is not a rare shape.
     */
    @Test
    public void shouldDifferFromACentreReconstructedBox_whenTheWidthIsOdd() {
        int[] trueBounds = { 100, 100, 181, 100 };
        int centreX = trueBounds[0] + trueBounds[2] / 2;
        assertEquals("the centre a response publishes", 190, centreX);

        int[] reconstructed = { centreX - trueBounds[2] / 2, trueBounds[1],
                                2 * (trueBounds[2] / 2), trueBounds[3] };
        assertArrayEquals("the near edge survives, the far edge is a pixel short",
                new int[] { 100, 100, 180, 100 }, reconstructed);

        assertEquals("derived from the element's own bounds", Outcome.FACE_TOP,
                RenderFaceDeriver.classify(trueBounds, ELSEWHERE, 280, 20, PLAIN));
        assertEquals("derived from bounds a caller could rebuild", Outcome.CORNER_TOP_RIGHT,
                RenderFaceDeriver.classify(reconstructed, ELSEWHERE, 280, 20, PLAIN));
    }

    /**
     * The bendpoint reference this server publishes and the one the renderer computes can differ by
     * one pixel, and at a band boundary that pixel changes the answer.
     *
     * <p>{@code absoluteBendpoints} interpolates in exact integer arithmetic and its single
     * division truncates toward zero; the renderer blends in {@code double} and converts with
     * {@code floor}. They agree everywhere the blend is non-negative and part company on a negative
     * one. Here the true blend is minus a half: the published value is 0, on the box's own left
     * edge and therefore inside it, while the renderer's is -1, outside. The derivation uses the
     * published value and so declines, where the renderer attaches on the left. It under-claims
     * rather than guessing, which is the direction this field is allowed to be wrong in.
     */
    @Test
    public void shouldDiverge_whenTruncationAndFloorDisagreeAtABandBoundary() {
        List<BendpointDto> stored = List.of(new BendpointDto(0, 50, -1, 50));
        List<AbsoluteBendpointDto> published =
                ConnectionResponseBuilder.convertRelativeToAbsolute(stored, 0, 0, 0, 0);
        assertEquals("the published reference, truncated toward zero", 0, published.get(0).x());
        assertEquals(50, published.get(0).y());

        int rendererX = (int) Math.floor(-0.5d + 1e-9);
        assertEquals("the reference the renderer computes, floored", -1, rendererX);

        int[] own = { 0, 0, 100, 100 };
        assertEquals("from the published reference", Outcome.ABSTAIN_REFERENCE_INSIDE_BOX,
                RenderFaceDeriver.classify(own, ELSEWHERE, published.get(0).x(),
                        published.get(0).y(), PLAIN));
        assertEquals("from the reference the renderer would use", Outcome.FACE_LEFT,
                RenderFaceDeriver.classify(own, ELSEWHERE, rendererX,
                        published.get(0).y(), PLAIN));
        assertNotEquals("so the one pixel is not cosmetic here",
                RenderFaceDeriver.classify(own, ELSEWHERE, published.get(0).x(), 50, PLAIN),
                RenderFaceDeriver.classify(own, ELSEWHERE, rendererX, 50, PLAIN));
    }

    /**
     * The fallback anchor expands its box by a pixel before doing anything, and that expansion
     * decides the answer on the diagonal.
     *
     * <p>{@code ChopboxAnchor} translates the box by {@code (-1, -1)} and resizes it by
     * {@code (1, 1)} first, so the centre it measures from is {@code x + (w - 1) / 2f} — half a
     * pixel below the published centre on an even dimension — and the aspect normalisation divides
     * by {@code w + 1} rather than {@code w}. At the exact bottom-left corner of a 40-by-30 box the
     * expanded form still resolves to the bottom face, while an unexpanded one produces an exact
     * tie and lands on the corner. Since the installed anchor calls it the bottom face too, the
     * expansion is the difference between publishing a value and publishing nothing.
     */
    @Test
    public void shouldDependOnTheExpandedBox_whenTheFallbackRayLeavesDiagonally() {
        int[] own = { 100, 100, 40, 30 };
        assertEquals("the installed anchor", Outcome.FACE_BOTTOM, orth(own, 100, 130));
        assertEquals("the fallback, on the expanded box the anchor actually uses",
                Outcome.FACE_BOTTOM,
                RenderFaceDeriver.derive(own, ELSEWHERE, 100, 130, PLAIN.corner(),
                        AnchorModel.CHOPBOX));
        assertEquals("so the two configurations agree and the face is published",
                "bottom", RenderFaceDeriver.publishedFace(own, ELSEWHERE, 100, 130, PLAIN));

        assertEquals("and the same on the opposite diagonal",
                "right", RenderFaceDeriver.publishedFace(own, ELSEWHERE, 140, 100, PLAIN));

        // The resize half of the expansion matters on its own. On a 20-by-20 box a reference one
        // pixel outside a corner divides to an exact tie when normalised by the unexpanded width,
        // and lands on the corner; normalised by the width the anchor actually uses it resolves to
        // the same face the installed anchor names, and the value survives.
        int[] small = { 100, 100, 20, 20 };
        assertEquals("left", RenderFaceDeriver.publishedFace(small, ELSEWHERE, 99, 119, PLAIN));
        assertEquals("top", RenderFaceDeriver.publishedFace(small, ELSEWHERE, 119, 99, PLAIN));
    }

    /**
     * The corner dimension is halved before it widens a band, and dropping the halving moves the
     * boundary ten pixels on a default rounded figure.
     *
     * <p>With a 20-pixel arc the middle band starts at {@code x + 10}. A reference at {@code x + 15}
     * is inside it and names the top face; if the arc were used unhalved the same reference would
     * fall in the left corner band and the derivation would decline.
     */
    @Test
    public void shouldHalveTheCornerDimension_whenItWidensABand() {
        Anchoring rounded = RenderFaceDeriver.anchoringOf("ApplicationFunction", 0, 120, 55);
        int[] own = { 100, 100, 120, 55 };
        assertEquals("fifteen pixels in is past the halved arc and inside the middle band",
                "top", RenderFaceDeriver.publishedFace(own, ELSEWHERE, 115, 10, rounded));
        assertEquals("nine pixels in is still within it",
                Outcome.ABSTAIN_ROUNDED_CORNER_ARC,
                RenderFaceDeriver.classify(own, ELSEWHERE, 109, 10, rounded));
        assertEquals("and the boundary itself is the first middle pixel",
                "top", RenderFaceDeriver.publishedFace(own, ELSEWHERE, 110, 10, rounded));
    }

    private static Outcome orth(int[] own, int refX, int refY) {
        return RenderFaceDeriver.derive(own, ELSEWHERE, refX, refY, PLAIN.corner(),
                AnchorModel.ORTHOGONAL);
    }
}
