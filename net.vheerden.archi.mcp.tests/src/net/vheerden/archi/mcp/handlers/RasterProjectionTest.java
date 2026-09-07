package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import org.junit.Test;

import net.vheerden.archi.mcp.model.ContentBounds;

/**
 * Unit tests for {@link RasterProjection}.
 *
 * <p>The projection is checked against a reference corpus of eleven views whose
 * content bounds were read from the stored model and whose rendered dimensions
 * were read from the exported images on disk. That corpus is the only available
 * oracle on the formula: the render itself needs a display and cannot run here.</p>
 */
public class RasterProjectionTest {

    /**
     * One reference view: the bounds the accessor reports, the scale it was
     * exported at, the dimensions this projection produces, and the dimensions
     * the renderer actually produced.
     */
    private record Reference(String view, int boundsWidth, int boundsHeight, double scale,
            int projectedWidth, int projectedHeight, int renderedWidth, int renderedHeight) {}

    /**
     * Eight views on which the accessor's bounds frame covers everything the
     * renderer measured, so the projection reproduces the rendered image exactly.
     */
    private static final Reference[] EXACT = {
        new Reference("A", 1568, 2212, 1.0, 1588, 2232, 1588, 2232),
        new Reference("C", 3800, 1700, 1.0, 3820, 1720, 3820, 1720),
        new Reference("E", 1820, 1680, 1.0, 1840, 1700, 1840, 1700),
        new Reference("F", 2622, 3021, 1.0, 2642, 3041, 2642, 3041),
        new Reference("G", 6777, 6538, 0.6, 4085, 3942, 4085, 3942),
        new Reference("H", 1420, 3026, 1.0, 1440, 3046, 1440, 3046),
        new Reference("I", 3430, 1180, 1.0, 3450, 1200, 3450, 1200),
        new Reference("J", 1888, 1177, 1.0, 1908, 1197, 1908, 1197),
    };

    /**
     * Three views on which the renderer measured more than the accessor's frame
     * reports — it counts every printable figure, while the frame excludes notes
     * and covers connections only through their stored waypoints. Recorded so the
     * shortfall stays visible and bounded rather than being discovered by a user.
     */
    private static final Reference[] SHORT = {
        new Reference("B", 4730, 1534, 1.0, 4750, 1554, 4750, 1572),
        new Reference("D", 2404, 1670, 1.0, 2424, 1690, 2434, 1691),
        new Reference("K", 5010,  938, 1.0, 5030,  958, 5030,  960),
    };

    @Test
    public void shouldReproduceRenderedDimensions_whenFrameCoversEveryPrintableFigure() {
        for (Reference r : EXACT) {
            RasterProjection p = RasterProjection.project(
                    new ContentBounds(0, 0, r.boundsWidth(), r.boundsHeight()), r.scale());
            assertEquals("width for view " + r.view(), r.renderedWidth(), p.width());
            assertEquals("height for view " + r.view(), r.renderedHeight(), p.height());
        }
    }

    @Test
    public void shouldStayWithinTheRecordedShortfall_whenFrameMissesPrintableContent() {
        for (Reference r : SHORT) {
            RasterProjection p = RasterProjection.project(
                    new ContentBounds(0, 0, r.boundsWidth(), r.boundsHeight()), r.scale());
            assertEquals("width for view " + r.view(), r.projectedWidth(), p.width());
            assertEquals("height for view " + r.view(), r.projectedHeight(), p.height());
            assertTrue("view " + r.view() + " must not be projected larger than it rendered",
                    p.width() <= r.renderedWidth() && p.height() <= r.renderedHeight());
        }
    }

    @Test
    public void shouldNeverOverstateTheRenderedRaster_acrossTheReferenceCorpus() {
        for (Reference[] group : new Reference[][] { EXACT, SHORT }) {
            for (Reference r : group) {
                RasterProjection p = RasterProjection.project(
                        new ContentBounds(0, 0, r.boundsWidth(), r.boundsHeight()), r.scale());
                assertTrue("view " + r.view() + " width " + p.width()
                        + " must not exceed the rendered " + r.renderedWidth(),
                        p.width() <= r.renderedWidth());
                assertTrue("view " + r.view() + " height " + p.height()
                        + " must not exceed the rendered " + r.renderedHeight(),
                        p.height() <= r.renderedHeight());
            }
        }
    }

    @Test
    public void shouldTruncateTheMarginExpansion_ratherThanCarryItsFraction() {
        // The margin is expanded through an integer overload, so at a scale that
        // does not divide it evenly the fraction is lost before scaling. Treating
        // the expansion as exact gives (6777 + 2 * 10/0.6) * 0.6 = 4086.2 -> 4086;
        // the renderer produced 4085.
        RasterProjection p =
                RasterProjection.project(new ContentBounds(12, 12, 6777, 6538), 0.6);

        assertEquals(4085, p.width());
        assertNotEquals("the non-truncating form would give 4086", 4086, p.width());
    }

    @Test
    public void shouldReproduceTheAllocationThatExhaustedTheMachine() {
        RasterProjection p =
                RasterProjection.project(new ContentBounds(12, 12, 6777, 6538), 2.0);

        assertEquals(13574, p.width());
        assertEquals(13096, p.height());
        assertEquals(711_060_416L, p.bytes());
    }

    @Test
    public void shouldProjectTheBlankFallback_whenViewIsEmpty() {
        // An empty view renders as a fixed blank square; the fallback replaces the
        // bounds outright, so no margin is added to it.
        RasterProjection p = RasterProjection.project(null, 1.0);

        assertEquals(100, p.width());
        assertEquals(100, p.height());
        assertEquals(40_000L, p.bytes());
    }

    @Test
    public void shouldScaleTheBlankFallback_whenViewIsEmptyAndScaleIsNotOne() {
        RasterProjection p = RasterProjection.project(null, 2.0);

        assertEquals(200, p.width());
        assertEquals(200, p.height());
    }

    @Test
    public void shouldRoundContentExtentUp_whenBoundsAreFractional() {
        // Bendpoint extents make the bounds fractional. The fraction is spent on
        // the conservative side, because the frame already runs short.
        RasterProjection exact = RasterProjection.project(new ContentBounds(0, 0, 100, 100), 1.0);
        RasterProjection fractional =
                RasterProjection.project(new ContentBounds(0, 0, 100.001, 100.001), 1.0);

        assertEquals(120, exact.width());
        assertEquals(121, fractional.width());
        assertEquals(121, fractional.height());
    }

    @Test
    public void shouldCountFourBytesPerPixel() {
        RasterProjection p = RasterProjection.project(new ContentBounds(0, 0, 80, 80), 1.0);

        assertEquals(100, p.width());
        assertEquals(100, p.height());
        assertEquals(100L * 100L * 4L, p.bytes());
    }

    @Test
    public void shouldSaturateRatherThanWrap_whenTheRasterCannotBeCounted() {
        // Both axes saturate at Integer.MAX_VALUE, and the byte count then exceeds
        // what a long can hold. Multiplying it out wraps NEGATIVE, and every caller
        // compares the count against a budget with <=, so a wrapped figure reads as
        // "fits" and waves through the very allocation this class exists to refuse.
        ContentBounds absurd = new ContentBounds(0, 0, 6.0e8, 6.0e8);

        RasterProjection p = RasterProjection.project(absurd, 4.0);

        assertEquals(Integer.MAX_VALUE, p.width());
        assertEquals(Integer.MAX_VALUE, p.height());
        assertTrue("a saturated raster must never report a negative byte count",
                p.bytes() > 0L);
        assertEquals(Long.MAX_VALUE, p.bytes());
        assertFalse("it must not compare as fitting any budget",
                p.bytes() <= PhysicalMemoryBudget.singleBitmapBudget(Long.MAX_VALUE));
    }

    @Test
    public void shouldRefuse_whenBoundsAreNotAFiniteNonNegativeExtent() {
        // (long) Math.ceil(NaN) is 0, which would report a raster of no size and
        // wave the render through. A projection that cannot be computed must fail
        // closed.
        for (ContentBounds broken : new ContentBounds[] {
                new ContentBounds(0, 0, Double.NaN, 100),
                new ContentBounds(0, 0, 100, Double.NaN),
                new ContentBounds(0, 0, Double.POSITIVE_INFINITY, 100),
                new ContentBounds(0, 0, -5, 100),
                new ContentBounds(0, 0, 100, -5) }) {
            RasterProjection p = RasterProjection.project(broken, 1.0);
            assertTrue("must not report a zero-size raster for " + broken,
                    p.bytes() > 0L);
            assertFalse("must not compare as fitting for " + broken,
                    p.bytes() <= PhysicalMemoryBudget.singleBitmapBudget(Long.MAX_VALUE));
        }
    }

    @Test
    public void shouldOnlyEverSuggestACleanInRangeScale_acrossTheWholeRange() {
        // Sweeps every budget boundary the search can land on, and checks two things
        // about the value published to the caller as a retry: it lies inside the
        // accepted range, and it renders as the clean two-decimal string a caller
        // would type back. A scale printed as 1.1300000000000001 would be a defect
        // in a message whose whole point is that the retry is one call away.
        ContentBounds bounds = new ContentBounds(0, 0, 2000, 1500);
        int checked = 0;
        for (int hundredths = 10; hundredths <= 400; hundredths++) {
            double target = hundredths / 100.0;
            long budget = RasterProjection.project(bounds, target).bytes();

            double suggested = RasterProjection.largestScaleWithin(bounds, budget, 0.1, 4.0);

            assertTrue("suggested " + suggested + " below the accepted minimum",
                    suggested >= 0.1);
            assertTrue("suggested " + suggested + " above the accepted maximum",
                    suggested <= 4.0);
            assertTrue("the suggested scale must itself fit the budget it was chosen for",
                    RasterProjection.project(bounds, suggested).bytes() <= budget);
            String printed = String.valueOf(suggested);
            assertTrue("suggested scale must print cleanly, got: " + printed,
                    printed.matches("\\d+\\.\\d{1,2}"));
            checked++;
        }
        assertEquals("every hundredth in the accepted range must be swept", 391, checked);
    }

    @Test
    public void shouldNotSuggestAScaleOutsideARangeThatIsNotWholeHundredths() {
        // The loop limits are derived from doubles that need not land on a whole
        // hundredth. The returned scale must respect the range regardless.
        ContentBounds bounds = new ContentBounds(0, 0, 100, 100);

        double largest = RasterProjection.largestScaleWithin(
                bounds, Long.MAX_VALUE, 0.104, 3.996);

        assertTrue("must not exceed a maximum that is not a whole hundredth",
                largest <= 3.996);
        assertTrue("must not fall below a minimum that is not a whole hundredth",
                largest >= 0.104);
    }

    @Test
    public void shouldSuggestTheLargestScaleThatFits_whenBudgetIsTight() {
        ContentBounds bounds = new ContentBounds(12, 12, 6777, 6538);
        long budget = PhysicalMemoryBudget.singleBitmapBudget(422_256L * 1024L);

        double largest = RasterProjection.largestScaleWithin(bounds, budget, 0.1, 4.0);

        assertEquals(1.1, largest, 0.0001);
        assertTrue("the suggested scale must itself fit the budget",
                RasterProjection.project(bounds, largest).bytes() <= budget);
        assertTrue("one hundredth above the suggestion must not fit",
                RasterProjection.project(bounds, largest + 0.01).bytes() > budget);
    }

    @Test
    public void shouldReturnZero_whenNoAcceptedScaleFitsTheBudget() {
        ContentBounds bounds = new ContentBounds(0, 0, 6777, 6538);

        double largest = RasterProjection.largestScaleWithin(bounds, 1024L, 0.1, 4.0);

        assertEquals(0.0, largest, 0.0001);
    }

    @Test
    public void shouldNeverSuggestAScaleAboveTheAcceptedMaximum() {
        ContentBounds bounds = new ContentBounds(0, 0, 10, 10);

        double largest = RasterProjection.largestScaleWithin(bounds, Long.MAX_VALUE, 0.1, 4.0);

        assertEquals(4.0, largest, 0.0001);
    }
}
