package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins how a note's (and a group label band's) fitted height counts a hard line break: the text is
 * split into paragraphs at each line feed, each paragraph wraps on its own, and the line counts add
 * up. A break used to be counted twice, once as the space the word wrap read it as and again as an
 * extra line, so hard lines that each fit the width were sized as if they wrapped.
 *
 * <p>Fixed metrics through the {@code FromMetrics} overload, so nothing measures text: 14 px lines, a
 * 4 px space, no padding and no floor, so each answer reads as {@code lines × 14}.</p>
 */
public class NoteFitHardLineBreakTest {

    private static final int LINE = 14;

    private static ElementSizer.FontMetrics metrics(int... wordWidths) {
        return new ElementSizer.FontMetrics(0, LINE, 4, wordWidths);
    }

    /** Word widths for {@code text} as {@code measureText} sizes them: one per whitespace token, 6 px a char. */
    private static int[] widthsOf(String text) {
        String[] words = text.split("\\s+");
        int[] widths = new int[words.length];
        for (int i = 0; i < words.length; i++) {
            widths[i] = words[i].length() * 6;
        }
        return widths;
    }

    private static int lines(String text, int width) {
        return ElementSizer.fitTextBoxHeightToContentFromMetrics(text, width, metrics(widthsOf(text)), 0, 0,
                100000) / LINE;
    }

    private static int lines(String text, int width, int... wordWidths) {
        return ElementSizer.fitTextBoxHeightToContentFromMetrics(text, width, metrics(wordWidths), 0, 0, 100000)
                / LINE;
    }

    private static String repeat(String line, int times) {
        return String.join("\n", java.util.Collections.nCopies(times, line));
    }

    @Test
    public void shouldCountEachHardLineOnce_whenEveryLineFitsTheWidth() {
        String line = "This line is about sixty characters long, give or take a bit.";
        assertEquals("six hard lines, each narrower than the width", 6, lines(repeat(line, 6), 428));
        assertEquals(6, lines(repeat("Short line of thirty chars ok.", 6), 428));
    }

    @Test
    public void shouldGiveTheSameCount_toHardLinesThatDifferOnlyInLength() {
        assertEquals(lines(repeat("Short line of thirty chars ok.", 6), 428),
                lines(repeat("This line is about sixty characters long, give or take a bit.", 6), 428));
    }

    @Test
    public void shouldAddTheWrapOfEachParagraph() {
        // second paragraph: 3 words of 100 at width 220 wrap to 2 lines
        assertEquals(1 + 2, lines("Title\nwordA wordB wordC", 220, 30, 100, 100, 100));
    }

    @Test
    public void shouldCountAnEmptyParagraphAsOneLine() {
        assertEquals(3, lines("A\n\nB", 400));
    }

    @Test
    public void shouldCountACarriageReturnLineFeedAsOneBreak() {
        assertEquals(2, lines("A\r\nB", 400));
    }

    @Test
    public void shouldNotBreakAtALoneCarriageReturn() {
        assertEquals("a lone carriage return is whitespace, as the word wrap reads it", 1, lines("A\rB", 400));
    }

    /** A trailing line feed opens an empty last line, which the note draws, so it is counted: the fit errs tall, not short. */
    @Test
    public void shouldCountTheEmptyLineATrailingLineFeedOpens() {
        assertEquals(2, lines("A\n", 400));
        assertEquals(1, lines("A", 400));
    }

    /**
     * The word widths are one array for the whole text, so the paragraphs share one cursor into it:
     * the second paragraph wraps on ITS widths (100, 100 at 150 → 2 lines), not on the first
     * paragraph's (10, 100 → 1 line).
     */
    @Test
    public void shouldWrapEachParagraphOnItsOwnWordWidths() {
        assertEquals(1 + 2, lines("aaaa\nbbbb bbbb", 150, 10, 100, 100));
    }

    /**
     * Leading whitespace splits to an empty first token that takes a width slot in the whole-text
     * array; it must not shift the paragraphs' words onto the wrong widths.
     */
    @Test
    public void shouldNotShiftTheWordCursor_forLeadingWhitespace() {
        assertEquals(1 + 2, lines(" aaaa\nbbbb bbbb", 150, 0, 10, 100, 100));
        assertEquals("leading whitespace on a later paragraph takes no slot", 1 + 2,
                lines("aaaa\n bbbb bbbb", 150, 10, 100, 100));
    }

    @Test
    public void shouldCountOneLinePerEmptyParagraph_forTextOfOnlyLineFeeds() {
        assertEquals(3, lines("\n\n", 400));
    }

    @Test
    public void shouldLeaveASingleParagraphAsTheWordWrapCountsIt() {
        String paragraph = "word ".repeat(60).trim();
        assertEquals(ElementSizer.simulateWordWrap(paragraph, widthsOf(paragraph), 4, 428), lines(paragraph, 428));
    }

    /**
     * The element sizers never added the break term and are not touched: their answers on a name
     * holding a line feed are the literals they gave before this change (measured on the unchanged
     * code with these fixed metrics).
     */
    @Test
    public void shouldLeaveTheElementSizersAlone_onANameHoldingALineFeed() {
        String name = "First line\nSecond line of the element name";
        ElementSizer.FontMetrics m = new ElementSizer.FontMetrics(240, 14, 4, widthsOf(name));

        assertEquals(67, ElementSizer.computeLabelHeightFromMetrics(name, m, 120));
        assertArrayEquals(new int[] {120, 76}, ElementSizer.computeWrapFitDimensionsFromMetrics(name, m, 120));
        assertArrayEquals(new int[] {120, 76}, ElementSizer.computeDimensions(name, m));
    }
}
