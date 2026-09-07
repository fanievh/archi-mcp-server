package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;

/**
 * Single-undo-unit pins for the typography styling-rail extensions to
 * {@link UpdateViewConnectionCommand}: typography (font composite-string merge).
 *
 * <p>Note: lineStyle is a view-object property in Archi 5.8 (established empirically, against
 * the earlier assumption that it was a connection property) — see
 * {@link UpdateViewObjectCommandTypographyAndDecorationTest} for view-object lineStyle pins.</p>
 */
public class UpdateViewConnectionCommandTypographyTest {

    private static IDiagramModelArchimateConnection freshConnection() {
        return IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
    }

    private static StylingParams connectionTypographyStyling(String fontName, Integer fontSize,
                                                             String fontStyle) {
        return new StylingParams(
                null, null, null, null, null, null, null, null,
                fontName, fontSize, fontStyle, null, null, null, null, null);
    }

    // ------------------------------------------------------------------
    // Typography — merge into composite-string
    // ------------------------------------------------------------------

    @Test
    public void shouldApplyFont_onConnection() {
        IDiagramModelArchimateConnection conn = freshConnection();
        assertNull(conn.getFont());

        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                conn, List.of(), connectionTypographyStyling("Verdana", 11, "italic"));
        cmd.execute();

        assertEquals("Verdana|11|2", conn.getFont());

        cmd.undo();
        assertNull(conn.getFont());
    }

    @Test
    public void shouldMergeFontStyleOnly_intoExistingComposite() {
        IDiagramModelArchimateConnection conn = freshConnection();
        conn.setFont("Segoe UI|10|0");  // NORMAL

        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                conn, List.of(), connectionTypographyStyling(null, null, "bold"));
        cmd.execute();

        assertEquals("Segoe UI|10|1", conn.getFont());

        cmd.undo();
        assertEquals("Segoe UI|10|0", conn.getFont());
    }

    // ------------------------------------------------------------------
    // back-compat: a pre-existing styling-only call leaves font alone
    // ------------------------------------------------------------------

    @Test
    public void shouldLeaveFontUntouched_whenNoTypographyFieldsProvided() {
        IDiagramModelArchimateConnection conn = freshConnection();
        conn.setFont("PreExistingFont|12|2");

        // Pre-existing styling fields only.
        StylingParams preExistingStyling = new StylingParams(
                null, "#FF0000", null, null, 2, null, null, null,
                null, null, null, null, null, null, null, null);

        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                conn, List.of(), preExistingStyling);
        cmd.execute();

        // hasStylingChange triggered by lineColor + lineWidth → applyStyling block runs,
        // but newFont == oldFont (no typography fields in styling) — setFont writes through
        // with same value, no state change.
        assertEquals("PreExistingFont|12|2", conn.getFont());
    }

    // ------------------------------------------------------------------
    // single-undo-unit covering typography + the pre-existing styling fields
    // ------------------------------------------------------------------

    @Test
    public void shouldUndoAllConnectionStylingTogether() {
        IDiagramModelArchimateConnection conn = freshConnection();
        conn.setLineColor("#000000");
        conn.setFont("Segoe UI|9|0");

        StylingParams styling = new StylingParams(
                null, "#FF6600", null, null, 3, null, null, null,
                "Comic Sans MS", 16, "bold", null, null, null, null, null);

        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                conn, List.of(), styling);
        cmd.execute();

        assertEquals("#FF6600", conn.getLineColor());
        assertEquals(3, conn.getLineWidth());
        assertEquals("Comic Sans MS|16|1", conn.getFont());

        // One undo restores everything.
        cmd.undo();
        assertEquals("#000000", conn.getLineColor());
        assertEquals("Segoe UI|9|0", conn.getFont());
    }

    // ------------------------------------------------------------------
    // Captured-state accessors
    // ------------------------------------------------------------------

    @Test
    public void shouldExposeCapturedConnectionTypographyState_forTesting() {
        IDiagramModelArchimateConnection conn = freshConnection();
        conn.setFont("Arial|10|0");

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, "bold", null, null, null, null, null);

        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(
                conn, List.of(), styling);

        assertEquals("Arial|10|0", cmd.getOldFont());
        assertEquals("Arial|10|1", cmd.getNewFont());  // style flipped to BOLD
    }
}
