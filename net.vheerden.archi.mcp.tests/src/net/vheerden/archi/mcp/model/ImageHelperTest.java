package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IProfile;

/**
 * Tests for {@link ImageHelper}.
 *
 * <p>Coverage calculation tests are pure geometry — no EMF required.
 * Apply/read round-trip tests require EMF and run as PDE JUnit.</p>
 */
public class ImageHelperTest {

    // ---- Coverage calculation ----

    @Test
    public void shouldCalculateCoverage_whenNormalDimensions() {
        // 16x16 icon on 120x55 element = 256/6600 = 3.879%
        double coverage = ImageHelper.calculateCoverage(16, 16, 120, 55);
        assertEquals(3.88, coverage, 0.01);
    }

    @Test
    public void shouldReturnZeroCoverage_whenElementHasZeroWidth() {
        assertEquals(0.0, ImageHelper.calculateCoverage(16, 16, 0, 55), 0.001);
    }

    @Test
    public void shouldReturnZeroCoverage_whenElementHasZeroHeight() {
        assertEquals(0.0, ImageHelper.calculateCoverage(16, 16, 120, 0), 0.001);
    }

    @Test
    public void shouldReturnZeroCoverage_whenElementHasNegativeWidth() {
        assertEquals(0.0, ImageHelper.calculateCoverage(16, 16, -10, 55), 0.001);
    }

    @Test
    public void shouldReturnZeroCoverage_whenImageHasZeroDimensions() {
        assertEquals(0.0, ImageHelper.calculateCoverage(0, 0, 120, 55), 0.001);
    }

    @Test
    public void shouldReturnZeroCoverage_whenImageHasNegativeWidth() {
        assertEquals(0.0, ImageHelper.calculateCoverage(-16, 16, 120, 55), 0.001);
    }

    @Test
    public void shouldReturnZeroCoverage_whenImageHasNegativeHeight() {
        assertEquals(0.0, ImageHelper.calculateCoverage(16, -16, 120, 55), 0.001);
    }

    @Test
    public void shouldReturnHighCoverage_whenLargeImage() {
        // 100x100 image on 120x55 = 10000/6600 = 151.5%
        double coverage = ImageHelper.calculateCoverage(100, 100, 120, 55);
        assertTrue(coverage > 100.0);
    }

    // ---- Coverage warning ----

    @Test
    public void shouldReturnNull_whenCoverageBelowThreshold() {
        assertNull(ImageHelper.coverageWarning(3.88));
    }

    @Test
    public void shouldReturnNull_whenCoverageExactly25() {
        assertNull(ImageHelper.coverageWarning(25.0));
    }

    @Test
    public void shouldReturnWarning_whenCoverageAbove25() {
        String warning = ImageHelper.coverageWarning(45.0);
        assertTrue(warning.contains("45.0%"));
        assertTrue(warning.contains("obscure element name"));
    }

    @Test
    public void shouldReturnWarning_whenCoverageOver100() {
        String warning = ImageHelper.coverageWarning(151.5);
        assertTrue(warning.contains("151.5%"));
    }

    // ---- Validation ----

    @Test
    public void shouldAcceptValidImageParams() {
        ImageParams params = new ImageParams("images/abc.png", "bottom-left", "always");
        ImageHelper.validateImageParams(params); // should not throw
    }

    @Test
    public void shouldAcceptNullFields() {
        ImageHelper.validateImageParams(ImageParams.NONE); // should not throw
    }

    @Test
    public void shouldAcceptEmptyImagePath() {
        ImageParams params = new ImageParams("", null, null);
        ImageHelper.validateImageParams(params); // empty string = clear, valid
    }

    @Test(expected = ModelAccessException.class)
    public void shouldThrow_whenInvalidPosition() {
        ImageParams params = new ImageParams(null, "invalid-pos", null);
        ImageHelper.validateImageParams(params);
    }

    @Test(expected = ModelAccessException.class)
    public void shouldThrow_whenInvalidShowIcon() {
        ImageParams params = new ImageParams(null, null, "invalid-icon");
        ImageHelper.validateImageParams(params);
    }

    // ---- pure-geometry icon-band reservation tests. Test seam — no SWT,
    // no EMF, callable headless. ----

    // ---- reservedIconBandForCorner: 4 corners + 6 non-corners + enum coverage ----

    @Test
    public void w2_reservedIconBand_topLeftCornerReturns24() {
        // Per ImageParams.positionToInt: top-left=0
        assertEquals(24, ImageHelper.reservedIconBandForCorner(0, 16, 8));
    }

    @Test
    public void w2_reservedIconBand_topRightCornerReturns24() {
        // top-right=2 — pure geometry returns the band; accessor layer is the one
        // that distinguishes "explicit top-right" from "Archi default".
        assertEquals(24, ImageHelper.reservedIconBandForCorner(2, 16, 8));
    }

    @Test
    public void w2_reservedIconBand_bottomLeftCornerReturns24() {
        // bottom-left=6 — the retail-bank bug case
        assertEquals(24, ImageHelper.reservedIconBandForCorner(6, 16, 8));
    }

    @Test
    public void w2_reservedIconBand_bottomRightCornerReturns24() {
        // bottom-right=8
        assertEquals(24, ImageHelper.reservedIconBandForCorner(8, 16, 8));
    }

    @Test
    public void w2_reservedIconBand_nonCornersReturnZero() {
        // Non-corner positions: 1 (top-centre), 3 (middle-left), 4 (middle-centre),
        // 5 (middle-right), 7 (bottom-centre), 9 (fill) — none collide with children.
        assertEquals(0, ImageHelper.reservedIconBandForCorner(1, 16, 8));
        assertEquals(0, ImageHelper.reservedIconBandForCorner(3, 16, 8));
        assertEquals(0, ImageHelper.reservedIconBandForCorner(4, 16, 8));
        assertEquals(0, ImageHelper.reservedIconBandForCorner(5, 16, 8));
        assertEquals(0, ImageHelper.reservedIconBandForCorner(7, 16, 8));
        assertEquals(0, ImageHelper.reservedIconBandForCorner(9, 16, 8));
    }

    @Test
    public void w2_reservedIconBand_enumCoverageAllTenPositions() {
        // Future-proof pin: adding a new enum value to ImageParams must NOT
        // silently introduce a regression. All 10 current positions (0..9)
        // must map to a defined inset — corner→24, non-corner→0.
        for (int pos = 0; pos <= 9; pos++) {
            int expected;
            if (pos == 0 || pos == 2 || pos == 6 || pos == 8) {
                expected = 24;
            } else {
                expected = 0;
            }
            assertEquals("position " + pos + " inset",
                    expected, ImageHelper.reservedIconBandForCorner(pos, 16, 8));
        }
    }

    @Test
    public void w2_reservedIconBand_unrecognisedIntReturnsZero() {
        // Defensive: any int outside 0..9 (e.g. -1, 99) returns 0 — no NPE, no fire.
        assertEquals(0, ImageHelper.reservedIconBandForCorner(-1, 16, 8));
        assertEquals(0, ImageHelper.reservedIconBandForCorner(99, 16, 8));
    }

    @Test
    public void w2_reservedIconBand_respectsCallerSuppliedIconAndMargin() {
        // Helper does NOT hard-code 24 — caller supplies iconSize + margin.
        assertEquals(32, ImageHelper.reservedIconBandForCorner(6, 24, 8));
        assertEquals(20, ImageHelper.reservedIconBandForCorner(6, 16, 4));
        assertEquals(16, ImageHelper.reservedIconBandForCorner(6, 16, 0));
    }

    // ---- anyChildOccupiesIconBand: 4 corners × {occupied / empty} = 8 cases
    //      + Case A (no children) + Case B (corner empty pin) ----

    @Test
    public void w2_anyChildOccupiesIconBand_bottomLeftWithChildInCornerReturnsTrue() {
        // Parent 200×100. Bottom-left icon band: x=0..24, y=76..100.
        // Child rect at (10, 80, 30, 15) — overlaps the band (the retail-bank bug case).
        List<int[]> rects = List.of(new int[] {10, 80, 30, 15});
        assertTrue(ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, rects));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_bottomLeftWithChildOnlyInTopHalfReturnsFalse() {
        // Case B byte-identical pin: container with bottom-left icon
        // + child only in top half → corner empty → lever short-circuits.
        // Parent 200×100. Bottom-left band: x=0..24, y=76..100.
        // Child at (10, 10, 30, 30) — entirely in top half (y=10..40).
        List<int[]> rects = List.of(new int[] {10, 10, 30, 30});
        assertFalse(ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, rects));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_topLeftWithChildInCornerReturnsTrue() {
        // Parent 200×100. Top-left band: x=0..24, y=0..24.
        // Child at (5, 5, 40, 40) — overlaps.
        List<int[]> rects = List.of(new int[] {5, 5, 40, 40});
        assertTrue(ImageHelper.anyChildOccupiesIconBand(200, 100, 0, 16, 8, rects));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_topLeftWithChildElsewhereReturnsFalse() {
        // Parent 200×100. Top-left band: x=0..24, y=0..24.
        // Child at (100, 50, 40, 40) — far from top-left.
        List<int[]> rects = List.of(new int[] {100, 50, 40, 40});
        assertFalse(ImageHelper.anyChildOccupiesIconBand(200, 100, 0, 16, 8, rects));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_topRightWithChildInCornerReturnsTrue() {
        // Parent 200×100. Top-right band: x=176..200, y=0..24.
        // Child at (180, 5, 40, 20) — overlaps.
        List<int[]> rects = List.of(new int[] {180, 5, 40, 20});
        assertTrue(ImageHelper.anyChildOccupiesIconBand(200, 100, 2, 16, 8, rects));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_topRightWithChildElsewhereReturnsFalse() {
        List<int[]> rects = List.of(new int[] {10, 50, 40, 40});
        assertFalse(ImageHelper.anyChildOccupiesIconBand(200, 100, 2, 16, 8, rects));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_bottomRightWithChildInCornerReturnsTrue() {
        // Parent 200×100. Bottom-right band: x=176..200, y=76..100.
        // Child at (180, 80, 30, 30) — overlaps.
        List<int[]> rects = List.of(new int[] {180, 80, 30, 30});
        assertTrue(ImageHelper.anyChildOccupiesIconBand(200, 100, 8, 16, 8, rects));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_bottomRightWithChildElsewhereReturnsFalse() {
        List<int[]> rects = List.of(new int[] {10, 10, 40, 40});
        assertFalse(ImageHelper.anyChildOccupiesIconBand(200, 100, 8, 16, 8, rects));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_partialOverlapStillCountsAsTrue() {
        // A child rectangle that grazes the icon-band by a single pixel still counts.
        // Parent 200×100, bottom-left band x=0..24, y=76..100.
        // Child at (23, 76, 50, 1) — top-left of child grazes (23,76), inside band.
        List<int[]> rects = List.of(new int[] {23, 76, 50, 1});
        assertTrue(ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, rects));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_touchingEdgeIsNotOverlap_halfOpenIntervalBoundaryPin() {
        // Review finding L-2 (Sonnet 4.6 adversarial review, 2026-05-20): pin the
        // half-open interval semantics — a child whose right edge exactly touches
        // the band's left edge (cx + cw == bandX, zero shared pixels) returns FALSE.
        // Parent 200×100, bottom-left band x=0..24, y=76..100.
        // Child at (10, 76, 14, 24): cx+cw=24=bandX → strict touch on the right edge.
        // Y range overlaps (76..100), X range touches but does not intrude (10..24
        // ends at x=24, band starts at x=0 and ends at x=24 — touching x=24 is the
        // half-open right boundary of the band).
        List<int[]> touchingRight = List.of(new int[] {-14, 76, 14, 24});
        assertFalse("Touching the band's left edge (cx+cw == bandX, zero shared pixels) is NOT overlap",
                ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, touchingRight));

        // Symmetric: child top edge exactly at band bottom (cy+ch == bandY).
        // Bottom-left band: y range 76..100. Child y range 60..76 touches at y=76.
        List<int[]> touchingBottom = List.of(new int[] {0, 60, 24, 16});
        assertFalse("Touching the band's top edge (cy+ch == bandY, zero shared pixels) is NOT overlap",
                ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, touchingBottom));

        // Sanity: one-pixel deeper IS an overlap (1-px shared region).
        List<int[]> intrudingOnePixel = List.of(new int[] {0, 60, 24, 17});  // cy+ch=77, into band
        assertTrue("One pixel interior overlap (cy+ch = bandY + 1) IS overlap",
                ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, intrudingOnePixel));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_emptyChildListReturnsFalse() {
        // Vacuous-empty case.
        assertFalse(ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, Collections.emptyList()));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_nullChildListReturnsFalse() {
        // Defensive: null treated as empty.
        assertFalse(ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, null));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_nonCornerPositionReturnsFalse() {
        // For non-corner positions the helper short-circuits to false even with
        // a child in the box centre — the lever simply does not apply.
        List<int[]> rects = List.of(new int[] {0, 0, 200, 100});
        for (int nonCorner : new int[] {1, 3, 4, 5, 7, 9}) {
            assertFalse("non-corner " + nonCorner,
                    ImageHelper.anyChildOccupiesIconBand(200, 100, nonCorner, 16, 8, rects));
        }
    }

    @Test
    public void w2_anyChildOccupiesIconBand_multipleChildrenAnyOneOverlapTriggers() {
        // Two children: one in the icon corner, one elsewhere. Result is true.
        List<int[]> rects = List.of(
                new int[] {100, 10, 30, 20},  // top centre — does NOT overlap bottom-left band
                new int[] {5, 80, 15, 15}     // bottom-left — OVERLAPS the band x=0..24, y=76..100
        );
        assertTrue(ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, rects));
    }

    @Test
    public void w2_anyChildOccupiesIconBand_iconBandSizeRespectsCallerArgs() {
        // Pass icon=24, margin=8 → band=32 instead of 24.
        // Parent 200×100, bottom-left band: x=0..32, y=68..100.
        // Child at (30, 70, 5, 5) — within the (x=0..32, y=68..100) band.
        List<int[]> rects = List.of(new int[] {30, 70, 5, 5});
        assertTrue(ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 24, 8, rects));
        // Same child at (30, 70, 5, 5) with icon=16, margin=0 → band=16, x=0..16, y=84..100
        // → child is outside the band on both axes.
        assertFalse(ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 0, rects));
    }

    @Test
    public void w2_ICON_BAND_HEIGHT_isParityWithGroupLabelHeight() {
        // Sanity-pin the constant — must stay 16+8=24, by parity with
        // GROUP_LABEL_HEIGHT=24 at ArchiModelAccessorImpl:8955.
        assertEquals(24, ImageHelper.ICON_BAND_HEIGHT);
    }

    // ---- Profile (specialization) image path resolution ----
    // A specialization image lives on the element's profile, not on the diagram
    // object; readImagePath returns null for it. readProfileImagePath resolves it
    // when the object's image source is the profile, so the overlap detector can
    // see specialization icons.

    private static IDiagramModelArchimateObject archimateObjectWithProfileImage(
            String imagePath, int imageSource) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject dmo = f.createDiagramModelArchimateObject();
        IArchimateElement element = f.createBusinessActor();
        dmo.setArchimateElement(element);
        dmo.setImageSource(imageSource);
        if (imagePath != null) {
            IProfile profile = f.createProfile();
            profile.setImagePath(imagePath);
            element.getProfiles().add(profile);
        }
        return dmo;
    }

    @Test
    public void readProfileImagePath_shouldResolvePath_whenProfileSourceAndIconBearingProfile() {
        IDiagramModelArchimateObject dmo = archimateObjectWithProfileImage(
                "images/specialization-icon.png",
                IDiagramModelArchimateObject.IMAGE_SOURCE_PROFILE);
        assertEquals("images/specialization-icon.png", ImageHelper.readProfileImagePath(dmo));
    }

    @Test
    public void readProfileImagePath_shouldReturnNull_whenImageSourceIsCustom() {
        // Custom image source → the profile icon is not displayed → not resolved here
        // (the custom image is read via readImagePath instead).
        IDiagramModelArchimateObject dmo = archimateObjectWithProfileImage(
                "images/specialization-icon.png",
                IDiagramModelArchimateObject.IMAGE_SOURCE_CUSTOM);
        assertNull(ImageHelper.readProfileImagePath(dmo));
    }

    @Test
    public void readProfileImagePath_shouldReturnNull_whenNoProfileHasImage() {
        IDiagramModelArchimateObject dmo = archimateObjectWithProfileImage(
                null, IDiagramModelArchimateObject.IMAGE_SOURCE_PROFILE);
        assertNull(ImageHelper.readProfileImagePath(dmo));
    }

    @Test
    public void readProfileImagePath_shouldReturnNull_whenNotArchimateObject() {
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        assertNull(ImageHelper.readProfileImagePath(note));
    }

    // ---- Natural image dimension read (archive) ----
    // Headless has no archive manager, so the read returns null and callers fall
    // back to the fixed icon size. These pin the null-safety contract.

    @Test
    public void readNaturalImageDimensions_shouldReturnNull_whenModelOrPathNull() {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        assertNull(ImageHelper.readNaturalImageDimensions(null, "img/x.png"));
        assertNull(ImageHelper.readNaturalImageDimensions(model, null));
    }

    @Test
    public void readNaturalImageDimensions_shouldReturnNull_whenNoArchiveManager() {
        // A bare EMF model has no archive manager adapter → null, never throws.
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        assertNull(ImageHelper.readNaturalImageDimensions(model, "img/missing.png"));
    }
}
