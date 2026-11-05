package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The shared connection-label size estimate: 8 px per character plus 10 px padding, 20 px high, with the
 * 1.35 render factor on the glyph run of the scored width only. These literals are the values every
 * consumer read before the estimate was shared, so a change here moves the assessor, the label optimizer
 * and the reservers together and has to be made on purpose.
 */
public class LabelBoxEstimateTest {

    private static final double EPS = 1e-9;

    @Test
    public void renderedWidth_scalesTheGlyphRunButNotThePadding() {
        assertEquals(4 * 8 * 1.35 + 10, LabelBoxEstimate.renderedWidth("flow"), EPS);       // 53.2
        assertEquals(6 * 8 * 1.35 + 10, LabelBoxEstimate.renderedWidth("serves"), EPS);     // 74.8
        assertEquals(96.4, LabelBoxEstimate.renderedWidth("Accesses"), EPS);
        assertEquals(442.0, LabelBoxEstimate.renderedWidth(fortyChars()), EPS);
    }

    @Test
    public void reservedWidth_isTheRawCharCountEstimate() {
        assertEquals(42.0, LabelBoxEstimate.reservedWidth("flow"), EPS);
        assertEquals(58.0, LabelBoxEstimate.reservedWidth("serves"), EPS);
        assertEquals(74.0, LabelBoxEstimate.reservedWidth("Accesses"), EPS);
        assertEquals(330.0, LabelBoxEstimate.reservedWidth(fortyChars()), EPS);
    }

    @Test
    public void height_isOneLineOfTextPlusPadding() {
        assertEquals(20.0, LabelBoxEstimate.HEIGHT, EPS);
        assertEquals(LabelBoxEstimate.CHAR_HEIGHT + LabelBoxEstimate.PADDING_Y, LabelBoxEstimate.HEIGHT, EPS);
    }

    @Test
    public void baseConstants_holdTheirValues() {
        assertEquals(8.0, LabelBoxEstimate.CHAR_WIDTH, EPS);
        assertEquals(14.0, LabelBoxEstimate.CHAR_HEIGHT, EPS);
        assertEquals(10.0, LabelBoxEstimate.PADDING_X, EPS);
        assertEquals(6.0, LabelBoxEstimate.PADDING_Y, EPS);
        assertEquals(1.35, LabelBoxEstimate.RENDER_WIDTH_FACTOR, EPS);
    }

    private static String fortyChars() {
        String s = "abcdefghijklmnopqrstuvwxyzabcdefghijklmn";
        assertEquals(40, s.length());
        return s;
    }
}
