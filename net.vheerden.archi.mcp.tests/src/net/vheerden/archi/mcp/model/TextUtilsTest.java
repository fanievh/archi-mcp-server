package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;
import org.junit.Test;

public class TextUtilsTest {

    @Test
    public void testNullInput() {
        assertNull(TextUtils.interpretEscapes(null));
    }

    @Test
    public void testEmptyString() {
        assertEquals("", TextUtils.interpretEscapes(""));
    }

    @Test
    public void testNoEscapes() {
        assertEquals("Hello World", TextUtils.interpretEscapes("Hello World"));
    }

    @Test
    public void testNewlineEscape() {
        assertEquals("Line 1\nLine 2", TextUtils.interpretEscapes("Line 1\\nLine 2"));
    }

    @Test
    public void testMultipleNewlines() {
        assertEquals("A\nB\nC\nD",
                TextUtils.interpretEscapes("A\\nB\\nC\\nD"));
    }

    @Test
    public void testTabEscape() {
        assertEquals("Col1\tCol2", TextUtils.interpretEscapes("Col1\\tCol2"));
    }

    @Test
    public void testCarriageReturnNewline() {
        assertEquals("Line 1\r\nLine 2",
                TextUtils.interpretEscapes("Line 1\\r\\nLine 2"));
    }

    @Test
    public void testEscapedBackslash() {
        assertEquals("path\\to\\file",
                TextUtils.interpretEscapes("path\\\\to\\\\file"));
    }

    @Test
    public void testEscapedBackslashBeforeN() {
        // \\n → backslash followed by literal n (not a newline)
        assertEquals("\\n", TextUtils.interpretEscapes("\\\\n"));
    }

    @Test
    public void testActualNewlinePreserved() {
        // Real newline (U+000A) should pass through unchanged
        assertEquals("Line 1\nLine 2",
                TextUtils.interpretEscapes("Line 1\nLine 2"));
    }

    @Test
    public void testMixedRealAndEscapedNewlines() {
        // Real newline + escaped newline
        assertEquals("A\nB\nC",
                TextUtils.interpretEscapes("A\nB\\nC"));
    }

    @Test
    public void testUnrecognisedEscapePreserved() {
        // \x is not a recognised escape — backslash preserved, x preserved
        assertEquals("\\x", TextUtils.interpretEscapes("\\x"));
    }

    @Test
    public void testTrailingBackslashPreserved() {
        assertEquals("end\\", TextUtils.interpretEscapes("end\\"));
    }

    @Test
    public void testComplexMixedContent() {
        assertEquals("Title\n\nBullet 1\nBullet 2\n\nEnd",
                TextUtils.interpretEscapes("Title\\n\\nBullet 1\\nBullet 2\\n\\nEnd"));
    }
}
