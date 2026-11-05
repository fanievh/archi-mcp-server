package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Display;
import org.junit.Assume;
import org.junit.Test;

/**
 * Every font string the server writes is one SWT reads: Archi draws a view object's or connection's font
 * through {@code new FontData(String)}, and on a refusal draws its default font instead. A string the server
 * builds or merges must therefore parse, and parse to the name, height and style it was built from. The
 * version-0 form the server used to write is refused, which is why those fonts were never drawn.
 */
public class FontStringDrawnFormTest {

    private static final DefaultViewFont.Spec DEFAULTS = new DefaultViewFont.Spec("Lucida Grande", 12);

    @Test
    public void everyBuiltOrMergedString_shouldParseToWhatItWasBuiltFrom() {
        Object[][] cases = {
                {StylingHelper.assembleFontString(null, "Courier New", 20, "italic", DEFAULTS), "Courier New", 20, SWT.ITALIC},
                {StylingHelper.assembleFontString(null, null, null, "bold", DEFAULTS), "Lucida Grande", 12, SWT.BOLD},
                {StylingHelper.assembleFontString(null, null, 16, null, DEFAULTS), "Lucida Grande", 16, SWT.NORMAL},
                {StylingHelper.assembleFontString("Arial|14|1", null, null, "bold-italic", DEFAULTS), "Arial", 14,
                        SWT.BOLD | SWT.ITALIC},
                {StylingHelper.assembleFontString("1|Arial|10.0|1|COCOA|1|Arial-BoldMT", "Courier New", null, "normal",
                        DEFAULTS), "Courier New", 10, SWT.NORMAL},
                {StylingHelper.assembleFontString(null, null, null, null, DefaultViewFont.UNKNOWN), "", 9, SWT.NORMAL},
                // A stored version-1 string with a cell SWT cannot read is repaired, not rewritten as is.
                {StylingHelper.assembleFontString("1|Arial||0|", null, null, "bold", DEFAULTS), "Arial", 12, SWT.BOLD},
                {StylingHelper.assembleFontString("1|Arial|abc|x|", null, 16, null, DEFAULTS), "Arial", 16, SWT.NORMAL},
        };
        for (Object[] c : cases) {
            FontData fd = new FontData((String) c[0]);
            assertEquals(c[0] + " name", c[1], fd.getName());
            assertEquals(c[0] + " height", c[2], fd.getHeight());
            assertEquals(c[0] + " style", c[3], fd.getStyle());
        }
    }

    @Test
    public void theVersion0FormTheServerUsedToWrite_shouldBeRefused() {
        // Measured on macOS (cocoa); whether the other platforms' FontData refuses version 0 is not measured.
        Assume.assumeTrue("cocoa".equals(SWT.getPlatform()));
        for (String v0 : List.of("Arial|14|1", "|20|2", "Courier New|20|2")) {
            try {
                new FontData(v0);
                fail(v0 + " parsed");
            } catch (IllegalArgumentException expected) {
                // SWT.error(SWT.ERROR_INVALID_ARGUMENT): Archi falls back to its default font.
            }
        }
    }

    @Test
    public void aBuiltString_shouldMakeAFontOfThatSizeAndStyle() {
        Display display = Display.getDefault();
        Font font = new Font(display, new FontData(StylingHelper.assembleFontString(null, null, 20, "italic", DEFAULTS)));
        try {
            FontData drawn = font.getFontData()[0];
            assertEquals(20, drawn.getHeight());
            assertTrue("italic", (drawn.getStyle() & SWT.ITALIC) != 0);
        } finally {
            font.dispose();
        }
    }
}
