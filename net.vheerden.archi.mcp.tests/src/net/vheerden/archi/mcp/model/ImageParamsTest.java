package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link ImageParams} (Story C4).
 *
 * <p>Pure-geometry/value-object test — no EMF or OSGi required.
 * Run as standard JUnit test.</p>
 */
public class ImageParamsTest {

    // ---- NONE sentinel ----

    @Test
    public void shouldHaveNoValues_whenNoneSentinel() {
        assertFalse(ImageParams.NONE.hasAnyValue());
        assertNull(ImageParams.NONE.imagePath());
        assertNull(ImageParams.NONE.imagePosition());
        assertNull(ImageParams.NONE.showIcon());
    }

    // ---- hasAnyValue ----

    @Test
    public void shouldReturnTrue_whenImagePathSet() {
        ImageParams params = new ImageParams("images/abc.png", null, null);
        assertTrue(params.hasAnyValue());
    }

    @Test
    public void shouldReturnTrue_whenImagePositionSet() {
        ImageParams params = new ImageParams(null, "bottom-left", null);
        assertTrue(params.hasAnyValue());
    }

    @Test
    public void shouldReturnTrue_whenShowIconSet() {
        ImageParams params = new ImageParams(null, null, "always");
        assertTrue(params.hasAnyValue());
    }

    @Test
    public void shouldReturnTrue_whenEmptyStringImagePath() {
        // Empty string means "clear" — still counts as a value
        ImageParams params = new ImageParams("", null, null);
        assertTrue(params.hasAnyValue());
    }

    @Test
    public void shouldReturnFalse_whenAllNull() {
        ImageParams params = new ImageParams(null, null, null);
        assertFalse(params.hasAnyValue());
    }

    // ---- Position mapping ----

    @Test
    public void shouldMapAllPositionsToInt() {
        assertEquals(0, ImageParams.positionToInt("top-left"));
        assertEquals(1, ImageParams.positionToInt("top-centre"));
        assertEquals(2, ImageParams.positionToInt("top-right"));
        assertEquals(3, ImageParams.positionToInt("middle-left"));
        assertEquals(4, ImageParams.positionToInt("middle-centre"));
        assertEquals(5, ImageParams.positionToInt("middle-right"));
        assertEquals(6, ImageParams.positionToInt("bottom-left"));
        assertEquals(7, ImageParams.positionToInt("bottom-centre"));
        assertEquals(8, ImageParams.positionToInt("bottom-right"));
        assertEquals(9, ImageParams.positionToInt("fill"));
    }

    @Test
    public void shouldMapAllIntsToPositionString() {
        assertEquals("top-left", ImageParams.positionToString(0));
        assertEquals("top-centre", ImageParams.positionToString(1));
        assertEquals("top-right", ImageParams.positionToString(2));
        assertEquals("middle-left", ImageParams.positionToString(3));
        assertEquals("middle-centre", ImageParams.positionToString(4));
        assertEquals("middle-right", ImageParams.positionToString(5));
        assertEquals("bottom-left", ImageParams.positionToString(6));
        assertEquals("bottom-centre", ImageParams.positionToString(7));
        assertEquals("bottom-right", ImageParams.positionToString(8));
        assertEquals("fill", ImageParams.positionToString(9));
    }

    @Test
    public void shouldReturnNull_whenPositionIntOutOfRange() {
        assertNull(ImageParams.positionToString(-1));
        assertNull(ImageParams.positionToString(10));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrow_whenInvalidPositionString() {
        ImageParams.positionToInt("invalid-position");
    }

    // ---- Show icon mapping ----

    @Test
    public void shouldMapAllShowIconValuesToInt() {
        assertEquals(0, ImageParams.showIconToInt("if-no-image"));
        assertEquals(1, ImageParams.showIconToInt("always"));
        assertEquals(2, ImageParams.showIconToInt("never"));
    }

    @Test
    public void shouldMapAllIntsToShowIconString() {
        assertEquals("if-no-image", ImageParams.showIconToString(0));
        assertEquals("always", ImageParams.showIconToString(1));
        assertEquals("never", ImageParams.showIconToString(2));
    }

    @Test
    public void shouldReturnNull_whenShowIconIntOutOfRange() {
        assertNull(ImageParams.showIconToString(-1));
        assertNull(ImageParams.showIconToString(3));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrow_whenInvalidShowIconString() {
        ImageParams.showIconToInt("invalid");
    }

    // ---- Round-trip consistency ----

    @Test
    public void shouldRoundTripAllPositions() {
        for (int i = 0; i <= 9; i++) {
            String str = ImageParams.positionToString(i);
            assertEquals(i, ImageParams.positionToInt(str));
        }
    }

    @Test
    public void shouldRoundTripAllShowIconValues() {
        for (int i = 0; i <= 2; i++) {
            String str = ImageParams.showIconToString(i);
            assertEquals(i, ImageParams.showIconToInt(str));
        }
    }
}
