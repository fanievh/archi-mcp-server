package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import org.eclipse.emf.ecore.EClassifier;

import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIconic;
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
    public void shouldReportDrawnArea_whenImageExceedsElement() {
        // Archi CLIPS an image to the element box, so only the intersection renders.
        // 40x40 image on a 120x15 element draws 40x15 = 600 of 1800 = 33.3%.
        // The natural-area ratio would claim 88.9% for the same pixels.
        assertEquals(33.33, ImageHelper.calculateCoverage(40, 40, 120, 15), 0.01);
    }

    @Test
    public void shouldReturnHighCoverage_whenLargeImage() {
        // 100x100 image on 120x55: clipped to 100x55 = 5500/6600 = 83.3% drawn.
        // The image still EXCEEDS its element on the height axis, and that fact is
        // reported by the warning rather than by an above-100 coverage number.
        double coverage = ImageHelper.calculateCoverage(100, 100, 120, 55);
        assertEquals(83.33, coverage, 0.01);
        assertTrue(ImageHelper.exceedsElement(100, 100, 120, 55, null));
        assertTrue(ImageHelper.coverageWarning(coverage, true).contains("cut off"));
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

    // Drawn coverage is capped at 100 by construction, so no value above 100 can reach
    // coverageWarning from calculateCoverage any more. The oversize case that used to be
    // expressed as "coverage over 100%" is now expressed by the exceedsElement flag, and the
    // test that pinned the unreachable input is re-authored here to pin the reachable one.
    @Test
    public void shouldReturnWarning_whenImageExceedsElement() {
        // Replaces a test that fed coverageWarning a >100 value, which capped semantics make
        // unreachable. Uses a LOW drawn coverage so it pins the oversize channel on its own,
        // distinct from the both-clauses case below.
        String warning = ImageHelper.coverageWarning(8.3, true);
        assertTrue(warning.contains("cut off"));
        assertFalse(warning.contains("may obscure element name"));
    }

    @Test
    public void shouldNeverExceed100_whenImageIsLargerThanElementOnBothAxes() {
        assertEquals(100.0, ImageHelper.calculateCoverage(500, 500, 120, 55), 0.001);
    }

    // ---- fill position: Archi scales the image to the box rather than clipping it ----

    @Test
    public void shouldReturnFullCoverage_whenFillPosition() {
        // Natural size is irrelevant under fill — the image is scaled to the element box.
        assertEquals(100.0, ImageHelper.calculateCoverage(40, 40, 120, 15, "fill"), 0.001);
        assertEquals(100.0, ImageHelper.calculateCoverage(4, 4, 120, 15, "fill"), 0.001);
    }

    @Test
    public void shouldNotReportExceeds_whenFillPosition() {
        // A fill image is scaled, never cut off, however large its natural size.
        assertFalse(ImageHelper.exceedsElement(500, 500, 120, 15, "fill"));
    }

    @Test
    public void shouldTreatNullPositionAsAnchored_notUnknown() {
        // null denotes the Archi default (top-right), which is anchored and therefore
        // clipped — it must NOT be treated as an unknown position or as fill.
        assertEquals(ImageHelper.calculateCoverage(40, 40, 120, 15),
                ImageHelper.calculateCoverage(40, 40, 120, 15, null), 0.001);
        assertEquals(33.33, ImageHelper.calculateCoverage(40, 40, 120, 15, null), 0.01);
        assertTrue(ImageHelper.exceedsElement(40, 40, 120, 15, null));
    }

    @Test
    public void shouldReportSameDrawnCoverage_forEveryAnchoredPosition() {
        // The anchored rectangle and the element box always share the anchor corner, so the
        // intersection's SIZE is independent of which corner the image is anchored to.
        for (String pos : new String[] {"top-left", "top-centre", "top-right", "middle-left",
                "middle-centre", "middle-right", "bottom-left", "bottom-centre", "bottom-right"}) {
            assertEquals("position " + pos,
                    33.33, ImageHelper.calculateCoverage(40, 40, 120, 15, pos), 0.01);
        }
    }

    // ---- exceedsElement is INDEPENDENT of drawn coverage: all four combinations ----

    @Test
    public void shouldReportExceeds_whenLargerOnEitherAxisAlone() {
        assertTrue("wider only", ImageHelper.exceedsElement(200, 10, 120, 55, null));
        assertTrue("taller only", ImageHelper.exceedsElement(10, 200, 120, 55, null));
        assertFalse("fits both axes", ImageHelper.exceedsElement(16, 16, 120, 55, null));
        assertFalse("exactly the box", ImageHelper.exceedsElement(120, 55, 120, 55, null));
    }

    @Test
    public void shouldWarnAboutBoth_forA40x40ImageOnA120x15Element() {
        // The case that motivated the change: previously reported 88.9% (natural area) with a
        // "may obscure element name" claim, when only 33.3% is actually painted. Both claims
        // are TRUE here and both fire — 33.3% is above the 25% legibility threshold AND the
        // image overruns the element's height. The fix is that the NUMBER is now 33.3 not 88.9,
        // and that the cut-off fact is stated explicitly instead of being implied by a figure
        // above 100. Fidelity firing WITHOUT legibility needs a lower-coverage case, pinned by
        // shouldWarnAboutClippingOnly_whenOversizedButLowDrawnCoverage below.
        double coverage = ImageHelper.calculateCoverage(40, 40, 120, 15, "top-left");
        assertEquals(33.33, coverage, 0.01);
        assertTrue(ImageHelper.exceedsElement(40, 40, 120, 15, "top-left"));
        String warning = ImageHelper.coverageWarning(coverage, true);
        assertTrue(warning.contains("may obscure element name"));
        assertTrue(warning.contains("cut off"));
    }

    @Test
    public void shouldWarnAboutClippingOnly_whenOversizedButLowDrawnCoverage() {
        // A tall narrow image on a short wide box: cut off on the height axis while covering
        // very little of the element, so fidelity fires and legibility does not. This is the
        // combination the natural-area ratio could not express at all.
        double coverage = ImageHelper.calculateCoverage(10, 200, 120, 55, null);
        assertEquals(8.33, coverage, 0.01); // drawn 10x55 = 550 of 6600
        String warning = ImageHelper.coverageWarning(coverage, true);
        assertTrue("must state the image is cut off", warning.contains("cut off"));
        assertFalse("must NOT claim the name is obscured",
                warning.contains("may obscure element name"));
    }

    @Test
    public void shouldWarnAboutLegibilityOnly_whenLargeCoverageButFits() {
        String warning = ImageHelper.coverageWarning(45.0, false);
        assertTrue(warning.contains("may obscure element name"));
        assertFalse(warning.contains("cut off"));
    }

    @Test
    public void shouldWarnAboutBoth_whenOversizedAndHighDrawnCoverage() {
        String warning = ImageHelper.coverageWarning(83.3, true);
        assertTrue(warning.contains("may obscure element name"));
        assertTrue(warning.contains("cut off"));
    }

    @Test
    public void shouldReturnNull_whenNeitherConditionHolds() {
        assertNull(ImageHelper.coverageWarning(10.0, false));
    }

    @Test
    public void shouldWarnAboutLegibilityOnly_whenFillPosition() {
        // fill always covers 100% and is never cut off — legibility only, coherently.
        // Goes through coverageReport, the PRODUCTION path: it is the only caller that knows the
        // position, and routing this through the two-argument coverageWarning would silently
        // exercise the ANCHORED branch while still passing these two assertions.
        String warning = ImageHelper.coverageReport(500, 500, 120, 15, "fill").warning();
        assertTrue(warning.contains("may obscure element name"));
        assertFalse(warning.contains("cut off"));
    }

    @Test
    public void shouldOfferOnlyRemediesThatWork_whenFillPosition() {
        // Both of the anchored remedies are no-ops under fill, and an advisory an agent cannot
        // discharge by any action it names is worse than none: "use a smaller image" cannot help
        // because fill SCALES to the box, and "use 'fill'" is circular when fill is already set.
        String warning = ImageHelper.coverageReport(500, 500, 120, 15, "fill").warning();
        assertFalse("fill scales to the box, so a smaller image changes nothing",
                warning.contains("smaller image"));
        assertFalse("the caller is already at fill — circular advice",
                warning.contains("'fill' position"));
        assertTrue("must offer a contrast remedy", warning.contains("lower-contrast"));
        assertTrue("must offer leaving fill for an anchored position",
                warning.contains("anchored imagePosition"));
        // Not an assertion about coverage — a guard against re-introducing a remedy that was
        // tested against a real render and found inert: opacity is the figure's fill alpha and
        // does not dim a custom image (an image at opacity 0 still renders fully opaque). This
        // cannot fail today because the string has never mentioned opacity; it exists to fail
        // the day someone adds it back.
        assertFalse("opacity does not dim a custom image", warning.contains("opacity"));
    }

    @Test
    public void shouldSayTheWarningPersists_whenFillPositionCannotClearIt() {
        // Coverage under fill is unconditionally 100%, so lowering the image's contrast — the
        // remedy that keeps fill — cannot silence this warning, only leaving fill can. Advertising
        // both as if either discharges the advisory would recreate the loop this branch removes:
        // an agent would swap in lighter images forever waiting for a warning that never clears.
        String warning = ImageHelper.coverageReport(500, 500, 120, 15, "fill").warning();
        assertTrue("must warn that the advisory persists while fill is set",
                warning.contains("persists for as long as 'fill' is set"));
        assertTrue("must name the remedy that actually clears it",
                warning.contains("to clear it"));
        // The claim above must stay true of the code: no image size clears the warning under fill.
        assertNotNull(ImageHelper.coverageReport(4, 4, 120, 15, "fill").warning());
        assertNotNull(ImageHelper.coverageReport(4000, 4000, 120, 15, "fill").warning());
    }

    @Test
    public void shouldKeepTheDiagnosis_whenFillPosition() {
        // The diagnosis is CORRECT and must not be suppressed — a 100%-coverage background
        // genuinely can hide the name. Only the remedy tail is position-dependent.
        ImageHelper.CoverageReport report = ImageHelper.coverageReport(500, 500, 120, 15, "fill");
        assertEquals(100.0, report.percent(), 0.001);
        assertNotNull("silencing the fill warning would be a regression", report.warning());
        assertTrue(report.warning().contains("100.0%"));
    }

    // The anchored message is frozen byte-for-byte. The fill branch must not bleed into it.
    private static final String ANCHORED_LEGIBILITY_45 =
            "Image covers 45.0% of element area — may obscure element name. "
                + "Consider using a smaller image or 'fill' position.";

    @Test
    public void shouldLeaveTheAnchoredAdviceByteIdentical_forEveryAnchoredPosition() {
        // null is the Archi default (top-right) — anchored, NOT unknown and NOT fill.
        assertEquals("null must be treated as anchored",
                ANCHORED_LEGIBILITY_45, ImageHelper.coverageWarning(45.0, false, null));
        for (String pos : new String[] {"top-left", "top-centre", "top-right", "middle-left",
                "middle-centre", "middle-right", "bottom-left", "bottom-centre", "bottom-right"}) {
            assertEquals("position " + pos,
                    ANCHORED_LEGIBILITY_45, ImageHelper.coverageWarning(45.0, false, pos));
        }
        // The two-argument overload is the anchored one.
        assertEquals(ANCHORED_LEGIBILITY_45, ImageHelper.coverageWarning(45.0, false));
    }

    @Test
    public void shouldThreadPositionFromCoverageReport_notJustFromCoverageNumber() {
        // The end-to-end pin: a fill image and an anchored image that BOTH read 100.0% must get
        // DIFFERENT advice. If coverageReport ever stops passing imagePosition down, the number
        // is unchanged and only this assertion catches it.
        String fill = ImageHelper.coverageReport(500, 500, 120, 15, "fill").warning();
        String anchored = ImageHelper.coverageReport(120, 55, 120, 55, "top-left").warning();
        assertEquals(100.0, ImageHelper.coverageReport(120, 55, 120, 55, "top-left").percent(),
                0.001);
        assertTrue("anchored keeps the original advice", anchored.contains("smaller image"));
        assertFalse("fill must not receive the anchored advice", fill.contains("smaller image"));
    }

    // ---- coverageReport: the value actually reported, rounded to one decimal ----

    @Test
    public void shouldRoundReportedCoverage_andQuoteTheSameValueInTheWarning() {
        ImageHelper.CoverageReport report =
                ImageHelper.coverageReport(40, 40, 120, 15, "top-left");
        assertEquals(33.3, report.percent(), 0.001);
        assertTrue("warning must quote the same rounded number it reports",
                report.warning().contains("33.3%"));
    }

    @Test
    public void shouldReportNoCoverage_whenDimensionsUnavailable() {
        assertNull(ImageHelper.CoverageReport.NONE.percent());
        assertNull(ImageHelper.CoverageReport.NONE.warning());
    }

    @Test
    public void shouldNotSayOnly_whenClippedImageStillCoversTheWholeBox() {
        // An image overrunning ONE axis while matching the other exactly is cut off and still
        // paints 100% of the box. The clipping message must not qualify that with a figure
        // implying something is missing ("only 100.0%" would contradict itself).
        double coverage = ImageHelper.calculateCoverage(121, 55, 120, 55, null);
        assertEquals(100.0, coverage, 0.001);
        assertTrue(ImageHelper.exceedsElement(121, 55, 120, 55, null));
        String warning = ImageHelper.coverageWarning(coverage, true);
        assertTrue(warning.contains("cut off"));
        assertFalse("must not read 'only 100.0%'", warning.contains("only"));
    }

    @Test
    public void shouldReportZeroCoverage_whenFillPositionButNoDecodableImage() {
        // fill means "scaled to the box", not "assumed present": with no image dimensions
        // nothing renders, so 0.0 is reported rather than a false 100.0. The degenerate
        // guards deliberately precede the position check.
        assertEquals(0.0, ImageHelper.calculateCoverage(0, 0, 120, 55, "fill"), 0.001);
        assertEquals(0.0, ImageHelper.calculateCoverage(40, 40, 0, 55, "fill"), 0.001);
        assertFalse(ImageHelper.exceedsElement(0, 0, 120, 55, "fill"));
    }

    @Test
    public void shouldDecideTheThresholdOnTheSameValueItReports() {
        // The threshold is evaluated on the ROUNDED percentage — the one the caller is shown —
        // so the number reported and the number reasoned about can never disagree. A raw
        // coverage just above 25 that rounds to 25.0 therefore does NOT warn: warning about a
        // figure displayed as exactly "25.0%" would contradict the documented "above 25%" rule.
        // This preserves the pre-existing contract, which also rounded before warning.
        double raw = ImageHelper.calculateCoverage(2501, 3, 10000, 3, null);
        assertTrue("raw value is above the threshold", raw > 25.0);
        ImageHelper.CoverageReport report =
                ImageHelper.coverageReport(2501, 3, 10000, 3, null);
        assertEquals(25.0, report.percent(), 0.001);
        assertNull("reported as exactly 25.0%, so it must not claim to be above 25%",
                report.warning());
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

    // ---- anySameCornerIconChildOccupiesIconBand: nested child's OWN icon vs
    //      the container's same-corner icon. This gate fires the SECOND
    //      reserved band (2× total) so the two icon tiles clear each other.
    //      The rectangle-occupancy predicate above only clears the child RECT,
    //      leaving the two icons within a tile-height (the residual collision). ----

    @Test
    public void sameCornerIcon_reproductionRegionAzCluster_bottomLeftBothIconedReturnsTrue() {
        // Region→AZ→Cluster reproduction. AZ container 200×100 with a
        // bottom-left (6) icon; nested cluster nearly fills it vertically at
        // (10, 8, 180, 84) → childBottom=92, so its rectangle occupies the
        // bottom-left band (y 76..100). The cluster ALSO carries its own
        // bottom-left (6) icon → the two icons collide → gate returns true.
        List<int[]> rects = List.of(new int[] {10, 8, 180, 84, 6});
        assertTrue("AZ bottom-left icon × cluster bottom-left icon collide",
                ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, 6, 16, 8, rects));
    }

    @Test
    public void sameCornerIcon_bottomRightSymmetricReturnsTrue() {
        // Symmetric bottom-right (8) case. Band x 176..200, y 76..100.
        // Child (10, 8, 185, 84) → right edge 195, bottom 92 → occupies band;
        // child carries a bottom-right (8) icon → collide → true.
        List<int[]> rects = List.of(new int[] {10, 8, 185, 84, 8});
        assertTrue(ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, 8, 16, 8, rects));
    }

    @Test
    public void sameCornerIcon_differentCornerChildIconReturnsFalse() {
        // A child whose OWN icon is at a DIFFERENT corner must NOT be
        // perturbed. Same occupying rectangle, but child icon is bottom-right
        // (8) while the parent icon is bottom-left (6) → no same-corner
        // collision → false → no second band reserved (byte-identical).
        List<int[]> rects = List.of(new int[] {10, 8, 180, 84, 8});
        assertFalse("Parent bottom-left × child bottom-right icons do NOT collide",
                ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, 6, 16, 8, rects));

        // A child top-left (0) icon under a bottom-left (6) parent likewise
        // does not collide.
        List<int[]> topLeftChild = List.of(new int[] {10, 8, 180, 84, 0});
        assertFalse(ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, 6, 16, 8, topLeftChild));
    }

    @Test
    public void sameCornerIcon_childCarriesNoIconReturnsFalse() {
        // Child rectangle occupies the band but carries NO icon (-1 sentinel)
        // → this is exactly the db6bc8b rect-only case → no second band → false.
        List<int[]> rects = List.of(new int[] {10, 8, 180, 84, -1});
        assertFalse(ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, 6, 16, 8, rects));
    }

    @Test
    public void sameCornerIcon_sameCornerIconButNotOccupyingReturnsFalse() {
        // Child carries a same-corner (6) icon but sits in the TOP half
        // (10, 8, 40, 30) → childBottom=38 < 76 → rectangle does not occupy
        // the band → no collision → false.
        List<int[]> rects = List.of(new int[] {10, 8, 40, 30, 6});
        assertFalse(ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, 6, 16, 8, rects));
    }

    @Test
    public void sameCornerIcon_multipleChildrenAnyOneSameCornerTriggers() {
        // Three children: one elsewhere, one occupying with a DIFFERENT-corner
        // icon, one occupying with a SAME-corner icon → any-one → true.
        List<int[]> rects = List.of(
                new int[] {100, 8, 30, 20, -1},   // top-centre, no icon
                new int[] {10, 8, 180, 84, 8},    // occupies, but bottom-right icon (no collide)
                new int[] {12, 8, 20, 84, 6}      // occupies with bottom-left icon → collide
        );
        assertTrue(ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, 6, 16, 8, rects));
    }

    @Test
    public void sameCornerIcon_nonCornerParentReturnsFalse() {
        // Non-corner parent positions never define an icon band → always false,
        // even with an occupying same-corner-int child.
        List<int[]> rects = List.of(new int[] {0, 0, 200, 100, 4});
        for (int nonCorner : new int[] {1, 3, 4, 5, 7, 9}) {
            assertFalse("non-corner parent " + nonCorner,
                    ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, nonCorner, 16, 8, rects));
        }
    }

    @Test
    public void sameCornerIcon_nullEmptyAndShortArraysReturnFalse() {
        assertFalse(ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, 6, 16, 8, null));
        assertFalse(ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, 6, 16, 8, Collections.emptyList()));
        // A 4-element array (no icon field) is treated as carrying no icon.
        List<int[]> fourElem = List.of(new int[] {10, 8, 180, 84});
        assertFalse(ImageHelper.anySameCornerIconChildOccupiesIconBand(200, 100, 6, 16, 8, fourElem));
    }

    @Test
    public void sameCornerIcon_baseRectPredicateIgnoresIconField_db6bc8bByteIdenticalCarryForward() {
        // Carry-forward: the rectangle-occupancy predicate reads only
        // [0..3], so a 5-element array with an icon field produces the SAME
        // result as the equivalent 4-element array — the base db6bc8b
        // reservation is byte-identical whether or not the icon field rides along.
        List<int[]> fourElem = List.of(new int[] {10, 80, 30, 15});
        List<int[]> fiveElem = List.of(new int[] {10, 80, 30, 15, 6});
        assertEquals(
                ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, fourElem),
                ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, fiveElem));
        assertTrue(ImageHelper.anyChildOccupiesIconBand(200, 100, 6, 16, 8, fiveElem));
    }

    // ---- iconCornerOrNone: reads a child's OWN icon corner (or -1 when the
    //      child carries no custom image). Threads child-icon presence into the
    //      same-corner collision gate. Requires EMF (runs as PDE JUnit). ----

    @Test
    public void iconCornerOrNone_returnsPosition_whenObjectCarriesImagePath() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject dmo = f.createDiagramModelArchimateObject();
        dmo.setArchimateElement(f.createNode());
        dmo.setImagePath("images/eks.png");
        dmo.setImagePosition(6); // bottom-left
        assertEquals(6, ImageHelper.iconCornerOrNone(dmo));
    }

    @Test
    public void iconCornerOrNone_returnsMinusOne_whenNoImagePath() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject dmo = f.createDiagramModelArchimateObject();
        dmo.setArchimateElement(f.createNode());
        dmo.setImagePosition(6); // position set, but NO image path → not an icon
        assertEquals(-1, ImageHelper.iconCornerOrNone(dmo));
    }

    @Test
    public void iconCornerOrNone_returnsMinusOne_whenNotIconic() {
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        assertEquals(-1, ImageHelper.iconCornerOrNone(note));
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

    // ---- iconBandGrownHeight: the gate shared by both update paths ----

    /**
     * MEASURED, not argued: the shared gate's {@code IIconic} test is a no-op for every target that
     * can actually reach it.
     *
     * <p>The two update paths used to carry byte-identical copies of this gate with one difference —
     * the primary path also tested {@code instanceof IIconic}, the bulk back-reference path did not.
     * Folding them into one implementation keeps the stricter test, which is only behaviour-preserving
     * for the bulk path if no reachable target can be a container without being iconic. That is a
     * claim about Archi's metamodel, so it is checked against the metamodel rather than reasoned about:
     * every classifier that is both an {@code IDiagramModelObject} (what the prepare accepts) and an
     * {@code IDiagramModelContainer} (what the gate requires) must also be {@code IIconic}.</p>
     *
     * <p>This is a forward guard as much as a regression pin: were a future Archi to add a
     * non-iconic container, the fold would silently start skipping the band on the bulk path, and this
     * test is what would catch it.</p>
     */
    @Test
    public void shouldNeverHaveANonIconicContainer_amongDiagramObjectTypes() {
        List<String> offenders = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (EClassifier classifier : IArchimatePackage.eINSTANCE.getEClassifiers()) {
            Class<?> java = classifier.getInstanceClass();
            if (java == null
                    || !IDiagramModelObject.class.isAssignableFrom(java)
                    || !IDiagramModelContainer.class.isAssignableFrom(java)) {
                continue;
            }
            checked.add(java.getSimpleName());
            if (!IIconic.class.isAssignableFrom(java)) {
                offenders.add(java.getName());
            }
        }
        // Non-vacuity guard: an empty loop would pass this test while checking nothing. The
        // metamodel currently offers exactly three such types; assert we saw them.
        assertTrue("the scan must actually find the diagram containers, not silently check none: "
                + checked, checked.containsAll(List.of(
                        "IDiagramModelGroup", "IDiagramModelArchimateObject", "ISketchModelSticky")));
        assertTrue("a container that is not IIconic would make the shared icon-band gate skip the "
                + "band on the bulk path, where the old code did not: " + offenders,
                offenders.isEmpty());
    }

    /**
     * The gate can only ever grow a target, never shrink it, and returns the height it was handed
     * whenever nothing is staged. Both update paths derive "did the band fire?" by comparing the
     * returned height with the one passed in, so a reserve that could be negative — or a path that
     * returned something else on a no-op — would silently flip that flag and, through it, the
     * parent-fit cascade's gate.
     */
    @Test
    public void shouldNeverShrinkTarget_whenIconBandGateRuns() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setBounds(0, 0, 200, 200);

        assertEquals("no image params staged leaves the height untouched",
                200, ImageHelper.iconBandGrownHeight(group, null, 200, 200, 16, 8));
        assertEquals("no position staged leaves the height untouched",
                200, ImageHelper.iconBandGrownHeight(group, new ImageParams("p.png", null, null),
                        200, 200, 16, 8));
        assertEquals("a top corner is out of scope and leaves the height untouched",
                200, ImageHelper.iconBandGrownHeight(group, new ImageParams(null, "top-left", null),
                        200, 200, 16, 8));
        assertEquals("an empty bottom corner reserves nothing",
                200, ImageHelper.iconBandGrownHeight(group, new ImageParams(null, "bottom-left", null),
                        200, 200, 16, 8));

        IDiagramModelNote occupant = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        occupant.setBounds(0, 160, 60, 40); // sits in the bottom-left corner
        group.getChildren().add(occupant);
        int grown = ImageHelper.iconBandGrownHeight(group, new ImageParams(null, "bottom-left", null),
                200, 200, 16, 8);
        assertTrue("an occupied corner reserves a band, and never a negative one", grown > 200);

        assertFalse("a non-container target can never be grown",
                ImageHelper.iconBandGrownHeight(
                        IArchimateFactory.eINSTANCE.createDiagramModelNote(),
                        new ImageParams(null, "bottom-left", null), 200, 200, 16, 8) != 200);
    }
}
