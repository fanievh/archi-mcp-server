package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Tests for the typography and decoration styling extensions to {@link StylingHelper}:
 * typography (fontName / fontSize / fontStyle), connection lineStyle,
 * gradient, note borderType, deriveLineColor, outlineOpacity.
 *
 * <p>Pure JUnit 4 — no OSGi runtime needed. Validates the per-field validators,
 * int mappers, SWT FontData composite-string parser/assembler, read-helpers, and
 * applyStylingToNewObject extension.
 */
public class StylingHelperTypographyAndDecorationTest {

    // ------------------------------------------------------------------
    // validateStylingParams — fontStyle / gradient / borderType / outlineOpacity
    // ------------------------------------------------------------------

    @Test
    public void validateFontStyle_acceptsAllEnumValues() {
        StylingHelper.validateFontStyle("normal");
        StylingHelper.validateFontStyle("bold");
        StylingHelper.validateFontStyle("italic");
        StylingHelper.validateFontStyle("bold-italic");
        StylingHelper.validateFontStyle("BOLD");           // case-insensitive
        StylingHelper.validateFontStyle("Bold-Italic");    // case-insensitive
    }

    @Test
    public void validateFontStyle_acceptsNullOrEmpty() {
        StylingHelper.validateFontStyle(null);
        StylingHelper.validateFontStyle("");
    }

    @Test
    public void validateFontStyle_rejectsInvalid() {
        try {
            StylingHelper.validateFontStyle("strikethrough");
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void validateFontSize_acceptsPositiveAndNull() {
        StylingHelper.validateFontSize(null);
        StylingHelper.validateFontSize(1);
        StylingHelper.validateFontSize(9);
        StylingHelper.validateFontSize(1000);
    }

    @Test
    public void validateFontSize_rejectsZeroAndNegative() {
        try {
            StylingHelper.validateFontSize(0);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
        try {
            StylingHelper.validateFontSize(-5);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void validateLineStyle_acceptsEnumValues() {
        StylingHelper.validateLineStyle("solid");
        StylingHelper.validateLineStyle("dashed");
        StylingHelper.validateLineStyle("dotted");
        StylingHelper.validateLineStyle("none");
        StylingHelper.validateLineStyle("DASHED");
        StylingHelper.validateLineStyle("None");
        StylingHelper.validateLineStyle(null);
        StylingHelper.validateLineStyle("");
    }

    @Test
    public void validateLineStyle_rejectsInvalid() {
        try {
            StylingHelper.validateLineStyle("zigzag");
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void validateGradient_acceptsEnumValues() {
        StylingHelper.validateGradient("none");
        StylingHelper.validateGradient("top-bottom");
        StylingHelper.validateGradient("bottom-top");
        StylingHelper.validateGradient("left-right");
        StylingHelper.validateGradient("right-left");
        StylingHelper.validateGradient("Top-Bottom");
    }

    @Test
    public void validateGradient_rejectsInvalid() {
        try {
            StylingHelper.validateGradient("radial");
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void validateBorderType_acceptsEnumValues() {
        StylingHelper.validateBorderType("dogear");
        StylingHelper.validateBorderType("rectangle");
        StylingHelper.validateBorderType("none");
    }

    @Test
    public void validateBorderType_rejectsInvalid() {
        try {
            StylingHelper.validateBorderType("circle");
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void validateOutlineOpacity_acceptsRange() {
        StylingHelper.validateOutlineOpacity(0);
        StylingHelper.validateOutlineOpacity(128);
        StylingHelper.validateOutlineOpacity(255);
        StylingHelper.validateOutlineOpacity(null);
    }

    @Test
    public void validateOutlineOpacity_rejectsOutOfRange() {
        try {
            StylingHelper.validateOutlineOpacity(-1);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
        try {
            StylingHelper.validateOutlineOpacity(256);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    // ------------------------------------------------------------------
    // Int mappers — bitmask discipline
    // ------------------------------------------------------------------

    @Test
    public void mapFontStyleToInt_swtBitmask() {
        assertEquals(0, StylingHelper.mapFontStyleToInt("normal"));
        assertEquals(1, StylingHelper.mapFontStyleToInt("bold"));
        assertEquals(2, StylingHelper.mapFontStyleToInt("italic"));
        assertEquals(3, StylingHelper.mapFontStyleToInt("bold-italic"));
        assertEquals(1, StylingHelper.mapFontStyleToInt("BOLD"));
    }

    @Test
    public void mapIntToFontStyle_reversesBitmask() {
        assertNull(StylingHelper.mapIntToFontStyle(0));
        assertEquals("bold", StylingHelper.mapIntToFontStyle(1));
        assertEquals("italic", StylingHelper.mapIntToFontStyle(2));
        assertEquals("bold-italic", StylingHelper.mapIntToFontStyle(3));
    }

    @Test
    public void mapLineStyleToInt_viewObjectConstants() {
        // Task-9 empirical correction: lineStyle is a VIEW-OBJECT property in Archi 5.8.
        assertEquals(IDiagramModelObject.LINE_STYLE_SOLID, StylingHelper.mapLineStyleToInt("solid"));
        assertEquals(IDiagramModelObject.LINE_STYLE_DASHED, StylingHelper.mapLineStyleToInt("dashed"));
        assertEquals(IDiagramModelObject.LINE_STYLE_DOTTED, StylingHelper.mapLineStyleToInt("dotted"));
        assertEquals(IDiagramModelObject.LINE_STYLE_NONE, StylingHelper.mapLineStyleToInt("none"));
    }

    @Test
    public void mapIntToLineStyle_reverses() {
        assertNull(StylingHelper.mapIntToLineStyle(IDiagramModelObject.LINE_STYLE_DEFAULT));  // -1 → null
        assertNull(StylingHelper.mapIntToLineStyle(IDiagramModelObject.LINE_STYLE_SOLID));    // 0 (default) → null
        assertEquals("dashed", StylingHelper.mapIntToLineStyle(IDiagramModelObject.LINE_STYLE_DASHED));
        assertEquals("dotted", StylingHelper.mapIntToLineStyle(IDiagramModelObject.LINE_STYLE_DOTTED));
        assertEquals("none", StylingHelper.mapIntToLineStyle(IDiagramModelObject.LINE_STYLE_NONE));
    }

    @Test
    public void mapGradientToInt_combosFromComboBytecode() {
        // Combo selection-index - 1 mapping decoded from GradientComposite bytecode.
        assertEquals(IDiagramModelObject.GRADIENT_NONE, StylingHelper.mapGradientToInt("none"));  // -1
        assertEquals(0, StylingHelper.mapGradientToInt("top-bottom"));
        assertEquals(1, StylingHelper.mapGradientToInt("left-right"));
        assertEquals(2, StylingHelper.mapGradientToInt("right-left"));
        assertEquals(3, StylingHelper.mapGradientToInt("bottom-top"));
    }

    @Test
    public void mapIntToGradient_reverses() {
        assertNull(StylingHelper.mapIntToGradient(-1));   // GRADIENT_NONE → DTO null
        assertEquals("top-bottom", StylingHelper.mapIntToGradient(0));
        assertEquals("left-right", StylingHelper.mapIntToGradient(1));
        assertEquals("right-left", StylingHelper.mapIntToGradient(2));
        assertEquals("bottom-top", StylingHelper.mapIntToGradient(3));
    }

    @Test
    public void mapBorderTypeToInt_iDiagramModelNoteConstants() {
        assertEquals(IDiagramModelNote.BORDER_DOGEAR, StylingHelper.mapBorderTypeToInt("dogear"));
        assertEquals(IDiagramModelNote.BORDER_RECTANGLE, StylingHelper.mapBorderTypeToInt("rectangle"));
        assertEquals(IDiagramModelNote.BORDER_NONE, StylingHelper.mapBorderTypeToInt("none"));
    }

    @Test
    public void mapIntToBorderType_reverses() {
        assertNull(StylingHelper.mapIntToBorderType(IDiagramModelNote.BORDER_DOGEAR));  // default → null
        assertEquals("rectangle", StylingHelper.mapIntToBorderType(IDiagramModelNote.BORDER_RECTANGLE));
        assertEquals("none", StylingHelper.mapIntToBorderType(IDiagramModelNote.BORDER_NONE));
    }

    // ------------------------------------------------------------------
    // assembleFontString — partial-update permutations (merge discipline)
    // ------------------------------------------------------------------

    @Test
    public void assembleFontString_buildsFromScratchVersion0_whenNoExisting() {
        String result = StylingHelper.assembleFontString(null, "Segoe UI", 12, "bold");
        // Version-0 minimal output: "Segoe UI|12|1"
        assertEquals("Segoe UI|12|1", result);
    }

    @Test
    public void assembleFontString_emptyExistingTreatedAsNoExisting() {
        String result = StylingHelper.assembleFontString("", "Arial", 9, "normal");
        assertEquals("Arial|9|0", result);
    }

    @Test
    public void assembleFontString_buildsFromScratchDefaults_whenAllArgsNull() {
        // No existing + nothing supplied → minimal default (empty name, size 9, NORMAL).
        String result = StylingHelper.assembleFontString(null, null, null, null);
        assertEquals("|9|0", result);
    }

    @Test
    public void assembleFontString_mergesNameOnly_intoVersion0Existing() {
        // v0 existing: "Segoe UI|9|1" (bold, 9pt). Override name only.
        String result = StylingHelper.assembleFontString("Segoe UI|9|1", "Arial", null, null);
        assertEquals("Arial|9|1", result);
    }

    @Test
    public void assembleFontString_mergesSizeOnly_intoVersion0Existing() {
        String result = StylingHelper.assembleFontString("Segoe UI|9|1", null, 14, null);
        assertEquals("Segoe UI|14|1", result);
    }

    @Test
    public void assembleFontString_mergesStyleOnly_intoVersion0Existing() {
        String result = StylingHelper.assembleFontString("Segoe UI|9|0", null, null, "italic");
        assertEquals("Segoe UI|9|2", result);
    }

    @Test
    public void assembleFontString_mergesAllThree_intoVersion0Existing() {
        String result = StylingHelper.assembleFontString("Segoe UI|9|0", "Comic Sans MS", 16, "bold-italic");
        assertEquals("Comic Sans MS|16|3", result);
    }

    @Test
    public void assembleFontString_preservesVersion1Format_andPlatformCells() {
        // v1 input with platform/extra cells: "1|Segoe UI|9.0|0|WINDOWS|1|" (style 0 = NORMAL).
        String existing = "1|Segoe UI|9.0|0|WINDOWS|1|";
        String result = StylingHelper.assembleFontString(existing, "Arial", 14, "bold");
        // Expect v1 format preserved, height kept as float "14.0", platform cells untouched.
        assertEquals("1|Arial|14.0|1|WINDOWS|1|", result);
    }

    @Test
    public void assembleFontString_clearName_setsEmptyNameCell() {
        // Empty string for name clears to system default — preserved as empty cell.
        String result = StylingHelper.assembleFontString("Segoe UI|9|0", "", null, null);
        assertEquals("|9|0", result);
    }

    // ------------------------------------------------------------------
    // parseFontName/Size/Style — both versions
    // ------------------------------------------------------------------

    @Test
    public void parseFontName_returnsNullForNullEmpty() {
        assertNull(StylingHelper.parseFontName(null));
        assertNull(StylingHelper.parseFontName(""));
    }

    @Test
    public void parseFontName_handlesVersion0() {
        assertEquals("Segoe UI", StylingHelper.parseFontName("Segoe UI|9|0"));
    }

    @Test
    public void parseFontName_handlesVersion1() {
        assertEquals("Arial", StylingHelper.parseFontName("1|Arial|14.0|1|WINDOWS|1|"));
    }

    @Test
    public void parseFontName_returnsNullForEmptyNameCell() {
        // Empty name cell = "use Archi default font" — DTO field should be omitted.
        assertNull(StylingHelper.parseFontName("|9|0"));
        assertNull(StylingHelper.parseFontName("1||9.0|0"));
    }

    @Test
    public void parseFontSize_handlesVersion0() {
        assertEquals(Integer.valueOf(9), StylingHelper.parseFontSize("Segoe UI|9|0"));
        assertEquals(Integer.valueOf(14), StylingHelper.parseFontSize("Segoe UI|14|1"));
    }

    @Test
    public void parseFontSize_handlesVersion1Float() {
        assertEquals(Integer.valueOf(9), StylingHelper.parseFontSize("1|Segoe UI|9.0|0"));
        assertEquals(Integer.valueOf(14), StylingHelper.parseFontSize("1|Arial|14.0|1|WINDOWS"));
    }

    @Test
    public void parseFontSize_returnsNullForUnparseable() {
        assertNull(StylingHelper.parseFontSize(null));
        assertNull(StylingHelper.parseFontSize(""));
    }

    @Test
    public void parseFontStyle_returnsNullForNormal_DTOOmit() {
        // NORMAL is Archi's default — DTO omits.
        assertNull(StylingHelper.parseFontStyle("Segoe UI|9|0"));
        assertNull(StylingHelper.parseFontStyle("1|Arial|9.0|0|WINDOWS"));
    }

    @Test
    public void parseFontStyle_recognisesBoldItalicBoldItalic() {
        assertEquals("bold", StylingHelper.parseFontStyle("Segoe UI|9|1"));
        assertEquals("italic", StylingHelper.parseFontStyle("Segoe UI|9|2"));
        assertEquals("bold-italic", StylingHelper.parseFontStyle("Segoe UI|9|3"));
        assertEquals("bold-italic", StylingHelper.parseFontStyle("1|Arial|14.0|3|WINDOWS"));
    }

    // ------------------------------------------------------------------
    // applyStylingToNewObject — typography and decoration fields applied to real EMF objects
    // ------------------------------------------------------------------

    @Test
    public void applyStylingToNewObject_setsGradient_onIDiagramModelObject() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        // Default
        assertEquals(IDiagramModelObject.GRADIENT_NONE, group.getGradient());

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, null, "top-bottom", null, null, null);
        StylingHelper.applyStylingToNewObject(group, styling);
        assertEquals(0, group.getGradient());  // "top-bottom" → 0 per Task-0 finding
    }

    @Test
    public void applyStylingToNewObject_clearsGradient_onEmptyString() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setGradient(2);  // pre-set to "right-left"

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, null, "", null, null, null);  // empty clears
        StylingHelper.applyStylingToNewObject(group, styling);
        assertEquals(IDiagramModelObject.GRADIENT_NONE, group.getGradient());
    }

    @Test
    public void applyStylingToNewObject_setsBorderType_onNoteOnly() {
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, "rectangle", null, null);
        StylingHelper.applyStylingToNewObject(note, styling);
        assertEquals(IDiagramModelNote.BORDER_RECTANGLE, note.getBorderType());
    }

    @Test
    public void applyStylingToNewObject_ignoresBorderType_onGroup() {
        // borderType is note-only — applying to a group must NOT call setBorderType (which
        // would conflict with figureType's tabbed/rectangular semantics on groups).
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        int oldBorderType = group.getBorderType();
        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, "rectangle", null, null);
        StylingHelper.applyStylingToNewObject(group, styling);
        assertEquals(oldBorderType, group.getBorderType());  // unchanged
    }

    @Test
    public void applyStylingToNewObject_setsDeriveLineColor() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        // Archi default is true.
        assertTrue(group.getDeriveElementLineColor());

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, Boolean.FALSE, null);
        StylingHelper.applyStylingToNewObject(group, styling);
        assertEquals(false, group.getDeriveElementLineColor());
    }

    @Test
    public void applyStylingToNewObject_setsOutlineOpacity() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        // Default 255.
        assertEquals(IDiagramModelObject.FEATURE_LINE_ALPHA_DEFAULT, group.getLineAlpha());

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 64);
        StylingHelper.applyStylingToNewObject(group, styling);
        assertEquals(64, group.getLineAlpha());
    }

    @Test
    public void applyStylingToNewObject_mergesFont_onIFontAttribute() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        // Empty/null font initially.
        assertNull(group.getFont());

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                "Arial", 14, "bold", null, null, null, null, null);
        StylingHelper.applyStylingToNewObject(group, styling);
        // Version-0 minimal composite emitted.
        assertEquals("Arial|14|1", group.getFont());
    }

    // ------------------------------------------------------------------
    // Read-helpers — DTO null-omit discipline
    // ------------------------------------------------------------------

    @Test
    public void readGradient_returnsNullAtDefault() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        assertNull(StylingHelper.readGradient(group));  // default = GRADIENT_NONE
        group.setGradient(0);
        assertEquals("top-bottom", StylingHelper.readGradient(group));
    }

    @Test
    public void readBorderType_returnsNullForNonNote() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        assertNull(StylingHelper.readBorderType(group));  // not a note → null
    }

    @Test
    public void readBorderType_returnsNullAtDefaultForNote() {
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        assertNull(StylingHelper.readBorderType(note));  // default dogear → null
        note.setBorderType(IDiagramModelNote.BORDER_RECTANGLE);
        assertEquals("rectangle", StylingHelper.readBorderType(note));
    }

    @Test
    public void readDeriveLineColor_returnsNullAtDefault() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        // Default is true → DTO null.
        assertNull(StylingHelper.readDeriveLineColor(group));
        group.setDeriveElementLineColor(false);
        assertEquals(Boolean.FALSE, StylingHelper.readDeriveLineColor(group));
    }

    @Test
    public void readOutlineOpacity_returnsNullAtDefault() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        assertNull(StylingHelper.readOutlineOpacity(group));  // default 255 → null
        group.setLineAlpha(128);
        assertEquals(Integer.valueOf(128), StylingHelper.readOutlineOpacity(group));
    }

    @Test
    public void readFontName_returnsNullAtDefault() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        assertNull(StylingHelper.readFontName(group));  // no font set → null
        group.setFont("Arial|9|0");
        assertEquals("Arial", StylingHelper.readFontName(group));
    }

    // ------------------------------------------------------------------
    // View-object lineStyle (post Task-9 empirical correction)
    // ------------------------------------------------------------------

    @Test
    public void applyStylingToNewObject_setsLineStyle_onViewObject() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        assertEquals(IDiagramModelObject.LINE_STYLE_DEFAULT, group.getLineStyle());

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, "dashed", null, null, null, null);
        StylingHelper.applyStylingToNewObject(group, styling);
        assertEquals(IDiagramModelObject.LINE_STYLE_DASHED, group.getLineStyle());
    }

    @Test
    public void applyStylingToNewObject_clearsLineStyleToDefault_onEmptyString() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setLineStyle(IDiagramModelObject.LINE_STYLE_DOTTED);

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, "", null, null, null, null);
        StylingHelper.applyStylingToNewObject(group, styling);
        assertEquals(IDiagramModelObject.LINE_STYLE_DEFAULT, group.getLineStyle());
    }

    @Test
    public void readLineStyle_returnsNullAtDefault() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        assertNull(StylingHelper.readLineStyle(group));   // -1 (default) → null
        group.setLineStyle(IDiagramModelObject.LINE_STYLE_DASHED);
        assertEquals("dashed", StylingHelper.readLineStyle(group));
        group.setLineStyle(IDiagramModelObject.LINE_STYLE_NONE);
        assertEquals("none", StylingHelper.readLineStyle(group));
        group.setLineStyle(IDiagramModelObject.LINE_STYLE_SOLID);
        assertNull(StylingHelper.readLineStyle(group));   // 0 (solid) → null (DTO omits)
    }

    @Test
    public void applyConnectionStyling_silentlyIgnoresLineStyle() {
        // Connections do not support lineStyle in Archi 5.8 (Task-9 empirical correction).
        // applyConnectionStyling now ignores styling.lineStyle() entirely.
        IDiagramModelConnection conn = IArchimateFactory.eINSTANCE.createDiagramModelConnection();
        int initialType = conn.getType();

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, "dashed", null, null, null, null);
        StylingHelper.applyConnectionStyling(conn, styling);

        // Connection type unchanged — lineStyle ignored.
        assertEquals(initialType, conn.getType());
    }

    // ------------------------------------------------------------------
    // StylingParams back-compat constructors
    // ------------------------------------------------------------------

    @Test
    public void stylingParams_5ArgCtor_keepsCompiling() {
        // The 5-arg back-compat ctor still works after the 8→16 extension.
        StylingParams sp = new StylingParams("#FF0000", null, null, 128, 2);
        assertEquals("#FF0000", sp.fillColor());
        assertEquals(Integer.valueOf(128), sp.opacity());
        assertEquals(Integer.valueOf(2), sp.lineWidth());
        // New typography and decoration fields default to null.
        assertNull(sp.fontName());
        assertNull(sp.gradient());
        assertNull(sp.deriveLineColor());
        assertNull(sp.outlineOpacity());
    }

    @Test
    public void stylingParams_8ArgCtor_keepsCompiling() {
        // Post-predecessor back-compat ctor.
        StylingParams sp = new StylingParams(
                "#FF0000", "#00FF00", "#0000FF", 128, 2,
                "rectangular", "left", "centre");
        assertEquals("rectangular", sp.figureType());
        assertEquals("left", sp.textAlignment());
        assertEquals("centre", sp.verticalTextAlignment());
        // Typography and decoration fields null.
        assertNull(sp.fontName());
        assertNull(sp.lineStyle());
    }

    @Test
    public void stylingParams_hasAnyValue_returnsTrueForAnyTypographyOrDecorationField() {
        // None of the new typography or decoration fields should be invisible to hasAnyValue().
        assertTrue(new StylingParams(null, null, null, null, null, null, null, null,
                "Arial", null, null, null, null, null, null, null).hasAnyValue());
        assertTrue(new StylingParams(null, null, null, null, null, null, null, null,
                null, 14, null, null, null, null, null, null).hasAnyValue());
        assertTrue(new StylingParams(null, null, null, null, null, null, null, null,
                null, null, null, "dashed", null, null, null, null).hasAnyValue());
        assertTrue(new StylingParams(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, Boolean.FALSE, null).hasAnyValue());
        // NONE has no value.
        assertEquals(false, StylingParams.NONE.hasAnyValue());
    }

    @Test
    public void stylingParams_NONE_isAllNull() {
        StylingParams none = StylingParams.NONE;
        assertNotNull(none);
        assertNull(none.fontName());
        assertNull(none.fontSize());
        assertNull(none.fontStyle());
        assertNull(none.lineStyle());
        assertNull(none.gradient());
        assertNull(none.borderType());
        assertNull(none.deriveLineColor());
        assertNull(none.outlineOpacity());
    }
}
