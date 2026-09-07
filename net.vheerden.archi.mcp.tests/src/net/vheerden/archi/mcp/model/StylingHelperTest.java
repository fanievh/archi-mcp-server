package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.ITextPosition;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Tests for {@link StylingHelper} validate / map / read / apply behaviour for the
 * three new styling fields (figureType, textAlignment, verticalTextAlignment).
 *
 * <p>All tests use real EMF objects via {@link IArchimateFactory#eINSTANCE} to validate
 * end-to-end mapping (string → int → setter / getter → string) on real Archi model
 * targets. JUnit 4 + {@code org.junit.Assert.*} (matches existing convention in this
 * fragment).</p>
 */
public class StylingHelperTest {

    // ------------------------------------------------------------------
    // validateStylingParams — figureType branch
    // ------------------------------------------------------------------

    @Test
    public void validateStylingParams_acceptsRectangularAndTabbedFigureType() {
        StylingHelper.validateStylingParams(new StylingParams(
                null, null, null, null, null, "rectangular", null, null));
        StylingHelper.validateStylingParams(new StylingParams(
                null, null, null, null, null, "tabbed", null, null));
        StylingHelper.validateStylingParams(new StylingParams(
                null, null, null, null, null, "RECTANGULAR", null, null));
        StylingHelper.validateStylingParams(new StylingParams(
                null, null, null, null, null, "Tabbed", null, null));
        // No exception expected.
    }

    @Test
    public void validateStylingParams_acceptsNullOrEmptyFigureType() {
        StylingHelper.validateStylingParams(new StylingParams(
                null, null, null, null, null, null, null, null));
        StylingHelper.validateStylingParams(new StylingParams(
                null, null, null, null, null, "", null, null));
        // No exception expected.
    }

    @Test
    public void validateStylingParams_rejectsInvalidFigureType() {
        try {
            StylingHelper.validateStylingParams(new StylingParams(
                    null, null, null, null, null, "folder", null, null));
            fail("Expected ModelAccessException for invalid figureType");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    // ------------------------------------------------------------------
    // validateStylingParams — textAlignment branch
    // ------------------------------------------------------------------

    @Test
    public void validateStylingParams_acceptsAllTextAlignmentValues() {
        for (String v : new String[] {"left", "centre", "center", "right", "LEFT", "Right"}) {
            StylingHelper.validateStylingParams(new StylingParams(
                    null, null, null, null, null, null, v, null));
        }
    }

    @Test
    public void validateStylingParams_rejectsInvalidTextAlignment() {
        try {
            StylingHelper.validateStylingParams(new StylingParams(
                    null, null, null, null, null, null, "justified", null));
            fail("Expected ModelAccessException for invalid textAlignment");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    // ------------------------------------------------------------------
    // validateStylingParams — verticalTextAlignment branch
    // ------------------------------------------------------------------

    @Test
    public void validateStylingParams_acceptsAllVerticalTextAlignmentValues() {
        for (String v : new String[] {"top", "centre", "center", "bottom", "TOP", "Bottom"}) {
            StylingHelper.validateStylingParams(new StylingParams(
                    null, null, null, null, null, null, null, v));
        }
    }

    @Test
    public void validateStylingParams_rejectsInvalidVerticalTextAlignment() {
        try {
            StylingHelper.validateStylingParams(new StylingParams(
                    null, null, null, null, null, null, null, "middle"));
            fail("Expected ModelAccessException for invalid verticalTextAlignment");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    // ------------------------------------------------------------------
    // map*ToInt — verified against Archi public constants
    // ------------------------------------------------------------------

    @Test
    public void mapFigureTypeToInt_returnsExpectedConstants() {
        // BORDER_TABBED = 0 / BORDER_RECTANGLE = 1 per IDiagramModelGroup; same convention
        // as IDiagramModelArchimateObject.setType(int) for elements with alternate figures.
        assertEquals(IDiagramModelGroup.BORDER_TABBED, StylingHelper.mapFigureTypeToInt("tabbed"));
        assertEquals(IDiagramModelGroup.BORDER_RECTANGLE, StylingHelper.mapFigureTypeToInt("rectangular"));
        assertEquals(IDiagramModelGroup.BORDER_TABBED, StylingHelper.mapFigureTypeToInt("TABBED"));
        assertEquals(IDiagramModelGroup.BORDER_RECTANGLE, StylingHelper.mapFigureTypeToInt("Rectangular"));
    }

    @Test
    public void mapTextAlignmentToInt_returnsExpectedSwtConstants() {
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_LEFT, StylingHelper.mapTextAlignmentToInt("left"));
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_CENTER, StylingHelper.mapTextAlignmentToInt("centre"));
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_CENTER, StylingHelper.mapTextAlignmentToInt("center"));
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_RIGHT, StylingHelper.mapTextAlignmentToInt("right"));
    }

    @Test
    public void mapVerticalTextAlignmentToInt_returnsExpectedConstants() {
        assertEquals(ITextPosition.TEXT_POSITION_TOP, StylingHelper.mapVerticalTextAlignmentToInt("top"));
        assertEquals(ITextPosition.TEXT_POSITION_CENTRE, StylingHelper.mapVerticalTextAlignmentToInt("centre"));
        assertEquals(ITextPosition.TEXT_POSITION_CENTRE, StylingHelper.mapVerticalTextAlignmentToInt("center"));
        assertEquals(ITextPosition.TEXT_POSITION_BOTTOM, StylingHelper.mapVerticalTextAlignmentToInt("bottom"));
    }

    // ------------------------------------------------------------------
    // read* helpers — return null at Archi default
    // ------------------------------------------------------------------

    @Test
    public void readFigureType_returnsNullForGroupAtDefault() {
        IDiagramModelGroup group = freshGroup();
        // EMF default for BorderType is 0 (BORDER_TABBED) for groups.
        assertEquals(IDiagramModelGroup.BORDER_TABBED, group.getBorderType());
        assertNull(StylingHelper.readFigureType(group));
    }

    @Test
    public void readFigureType_returnsRectangularForFlippedGroup() {
        IDiagramModelGroup group = freshGroup();
        group.setBorderType(IDiagramModelGroup.BORDER_RECTANGLE);
        assertEquals("rectangular", StylingHelper.readFigureType(group));
    }

    @Test
    public void readFigureType_returnsNullForGroupingElementAtDefault() {
        IDiagramModelArchimateObject obj = freshGroupingElement();
        assertEquals(0, obj.getType());
        assertNull(StylingHelper.readFigureType(obj));
    }

    @Test
    public void readFigureType_returnsRectangularForGroupingElementAtType1() {
        IDiagramModelArchimateObject obj = freshGroupingElement();
        obj.setType(1);
        assertEquals("rectangular", StylingHelper.readFigureType(obj));
    }

    @Test
    public void readFigureType_returnsNullForNonGroupingArchimateElement() {
        // ApplicationComponent has setType(int) too, but its alternate figure is
        // not "tabbed/rectangular" — the read helper deliberately returns null so
        // the DTO field is omitted for non-Grouping element classes.
        IDiagramModelArchimateObject obj = freshArchimateObject();
        obj.setType(1);
        assertNull(StylingHelper.readFigureType(obj));
    }

    @Test
    public void readFigureType_returnsNullForNote() {
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        assertNull(StylingHelper.readFigureType(note));
    }

    @Test
    public void readTextAlignment_returnsNullForCenterDefault() {
        IDiagramModelGroup group = freshGroup();
        // Archi EMF default for textAlignment is TEXT_ALIGNMENT_CENTER (2) on all objects.
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_CENTER, group.getTextAlignment());
        assertNull(StylingHelper.readTextAlignment(group));
    }

    @Test
    public void readTextAlignment_returnsLeftForExplicitLeft() {
        IDiagramModelGroup group = freshGroup();
        group.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_LEFT);
        assertEquals("left", StylingHelper.readTextAlignment(group));
    }

    @Test
    public void readTextAlignment_returnsRightForExplicitRight() {
        IDiagramModelGroup group = freshGroup();
        group.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_RIGHT);
        assertEquals("right", StylingHelper.readTextAlignment(group));
    }

    @Test
    public void readVerticalTextAlignment_returnsNullForTopDefault() {
        // Archi EMF default for textPosition on a freshly-created IDiagramModelGroup is
        // TEXT_POSITION_TOP = 0 (label renders in a top header band of the group).
        IDiagramModelGroup group = freshGroup();
        assertEquals(ITextPosition.TEXT_POSITION_TOP, group.getTextPosition());
        assertNull(StylingHelper.readVerticalTextAlignment(group));
    }

    @Test
    public void readVerticalTextAlignment_returnsCentreForExplicitCentre() {
        IDiagramModelGroup group = freshGroup();
        group.setTextPosition(ITextPosition.TEXT_POSITION_CENTRE);
        assertEquals("centre", StylingHelper.readVerticalTextAlignment(group));
    }

    @Test
    public void readVerticalTextAlignment_returnsBottomForExplicitBottom() {
        IDiagramModelGroup group = freshGroup();
        group.setTextPosition(ITextPosition.TEXT_POSITION_BOTTOM);
        assertEquals("bottom", StylingHelper.readVerticalTextAlignment(group));
    }

    // ------------------------------------------------------------------
    // applyStylingToNewObject — dispatches by target type
    // ------------------------------------------------------------------

    @Test
    public void applyStylingToNewObject_setsBorderTypeOnGroup() {
        IDiagramModelGroup group = freshGroup();
        StylingHelper.applyStylingToNewObject(group, new StylingParams(
                null, null, null, null, null, "rectangular", null, null));
        assertEquals(IDiagramModelGroup.BORDER_RECTANGLE, group.getBorderType());
    }

    @Test
    public void applyStylingToNewObject_setsSetTypeOnGroupingElement() {
        IDiagramModelArchimateObject obj = freshGroupingElement();
        StylingHelper.applyStylingToNewObject(obj, new StylingParams(
                null, null, null, null, null, "rectangular", null, null));
        assertEquals(1, obj.getType());
    }

    @Test
    public void applyStylingToNewObject_silentlyIgnoresFigureTypeOnNonGroupingElement() {
        IDiagramModelArchimateObject obj = freshArchimateObject();
        int before = obj.getType();
        StylingHelper.applyStylingToNewObject(obj, new StylingParams(
                null, null, null, null, null, "rectangular", null, null));
        assertEquals(before, obj.getType());
    }

    @Test
    public void applyStylingToNewObject_silentlyIgnoresFigureTypeOnNote() {
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        int beforeBorderType = note.getBorderType();
        StylingHelper.applyStylingToNewObject(note, new StylingParams(
                null, null, null, null, null, "rectangular", null, null));
        // Note's IBorderType is NOT routed by figureType (rectangle/dogear/none — different semantics).
        assertEquals(beforeBorderType, note.getBorderType());
    }

    @Test
    public void applyStylingToNewObject_setsTextAlignmentOnGroup() {
        IDiagramModelGroup group = freshGroup();
        StylingHelper.applyStylingToNewObject(group, new StylingParams(
                null, null, null, null, null, null, "left", null));
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_LEFT, group.getTextAlignment());
    }

    @Test
    public void applyStylingToNewObject_setsVerticalTextAlignmentOnGroup() {
        IDiagramModelGroup group = freshGroup();
        // TOP is the default — apply CENTRE which is a non-default value to verify the apply path.
        StylingHelper.applyStylingToNewObject(group, new StylingParams(
                null, null, null, null, null, null, null, "centre"));
        assertEquals(ITextPosition.TEXT_POSITION_CENTRE, group.getTextPosition());
    }

    /**
     * With no styling to apply, nothing the caller could have asked for may change. The type's own
     * Archi default is not something the caller asked for: it is written on every new object of a
     * defaulting type precisely so that an unstyled one matches what Archi's palette produces, so a
     * group's alignment is expected to become LEFT here. Both halves are asserted together, because
     * the guard is only meaningful if it distinguishes the two.
     */
    @Test
    public void applyStylingToNewObject_appliesOnlyTheTypeDefaultForNullStyling() {
        IDiagramModelGroup group = freshGroup();
        int beforeBorder = group.getBorderType();
        int beforePos = group.getTextPosition();

        StylingHelper.applyStylingToNewObject(group, null);
        StylingHelper.applyStylingToNewObject(group, StylingParams.NONE);
        StylingHelper.applyStylingToNewObject(group, new StylingParams(
                null, null, null, null, null, null, null, null));

        assertEquals("no caller styling means no border change", beforeBorder,
                group.getBorderType());
        assertEquals("nor any vertical placement change — every provider here defaults TOP",
                beforePos, group.getTextPosition());
        assertEquals("but a group's own Archi default IS written, so an unstyled group matches "
                + "one drawn from the palette",
                ITextAlignment.TEXT_ALIGNMENT_LEFT, group.getTextAlignment());
    }

    /**
     * The complementary half: a type with no LEFT default must come through an unstyled call
     * completely untouched. Without this, the test above would pass just as well if the stamp had
     * leaked to every object.
     */
    @Test
    public void applyStylingToNewObject_leavesANonDefaultingTypeUntouchedForNullStyling() {
        IDiagramModelArchimateObject element = freshArchimateObject();
        int beforeAlign = element.getTextAlignment();
        int beforePos = element.getTextPosition();

        StylingHelper.applyStylingToNewObject(element, null);
        StylingHelper.applyStylingToNewObject(element, StylingParams.NONE);

        assertEquals("a plain element's provider default is CENTRE, which it already holds",
                beforeAlign, element.getTextAlignment());
        assertEquals(beforePos, element.getTextPosition());
    }

    // ------------------------------------------------------------------
    // resolveConnectionLabelText — label-width reservation resolver
    // ------------------------------------------------------------------

    @Test
    public void resolveConnectionLabelText_returnsRelationshipName_whenVisible() {
        IDiagramModelArchimateConnection conn = freshArchimateConnection("serves");
        conn.setNameVisible(true);
        assertEquals("serves", StylingHelper.resolveConnectionLabelText(conn));
    }

    @Test
    public void resolveConnectionLabelText_returnsEmpty_whenSuppressed() {
        IDiagramModelArchimateConnection conn = freshArchimateConnection("serves");
        conn.setNameVisible(false);
        assertEquals("", StylingHelper.resolveConnectionLabelText(conn));
    }

    @Test
    public void resolveConnectionLabelText_honoursLabelExpression_overRelationshipName() {
        IDiagramModelArchimateConnection conn = freshArchimateConnection("serves");
        conn.setNameVisible(true);
        conn.getFeatures().putString("labelExpression", "${name} (flow)");
        assertEquals("${name} (flow)", StylingHelper.resolveConnectionLabelText(conn));
    }

    @Test
    public void resolveConnectionLabelText_returnsEmpty_whenRelationshipNameNull() {
        IDiagramModelArchimateConnection conn = freshArchimateConnection(null);
        conn.setNameVisible(true);
        assertEquals("", StylingHelper.resolveConnectionLabelText(conn));
    }

    @Test
    public void resolveConnectionLabelText_returnsConnectionName_forPlainConnection() {
        IDiagramModelConnection conn = IArchimateFactory.eINSTANCE.createDiagramModelConnection();
        conn.setName("manual-label");
        conn.setNameVisible(true);
        assertEquals("manual-label", StylingHelper.resolveConnectionLabelText(conn));
    }

    @Test
    public void resolveConnectionLabelText_returnsEmpty_whenPlainConnectionSuppressed() {
        IDiagramModelConnection conn = IArchimateFactory.eINSTANCE.createDiagramModelConnection();
        conn.setName("manual-label");
        conn.setNameVisible(false);
        assertEquals("", StylingHelper.resolveConnectionLabelText(conn));
    }

    // ------------------------------------------------------------------
    // readConnectionRelativePosition — Label Offset anchor read-back
    // ------------------------------------------------------------------

    /**
     * Default / un-offset → null, on EVERY platform: on older Archi the feature is absent
     * (unsupported → null); on newer Archi a fresh connection's anchor is the default CENTER
     * (omitted at default). Either way the reader returns null so the field is omitted.
     */
    @Test
    public void readConnectionRelativePosition_returnsNull_whenUnsetOrUnsupported() {
        IDiagramModelArchimateConnection conn = freshArchimateConnection("serves");
        assertNull(StylingHelper.readConnectionRelativePosition(conn));
    }

    /**
     * When the platform exposes the Label Offset feature, a non-default anchor reads back as its
     * exact bitmask. Assumption-skipped on an older target (mirrors the runtime no-op).
     */
    @Test
    public void readConnectionRelativePosition_returnsBitmask_whenOffsetSet() {
        IDiagramModelArchimateConnection conn = freshArchimateConnection("serves");
        assumeTrue("Label-offset feature is only present on newer platforms",
                RelativePositionFeature.isSupported(conn));

        final int south = 4;
        RelativePositionFeature.set(conn, south);
        assertEquals(Integer.valueOf(south), StylingHelper.readConnectionRelativePosition(conn));
    }

    /** An explicit CENTER anchor is treated as the un-offset default → null (feature-gated). */
    @Test
    public void readConnectionRelativePosition_returnsNull_atExplicitCenter() {
        IDiagramModelArchimateConnection conn = freshArchimateConnection("serves");
        assumeTrue("Label-offset feature is only present on newer platforms",
                RelativePositionFeature.isSupported(conn));

        RelativePositionFeature.set(conn, RelativePositionFeature.CENTER);
        assertNull(StylingHelper.readConnectionRelativePosition(conn));
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    private IDiagramModelArchimateConnection freshArchimateConnection(String relName) {
        IArchimateRelationship rel = IArchimateFactory.eINSTANCE.createServingRelationship();
        if (relName != null) {
            rel.setName(relName);
        }
        IDiagramModelArchimateConnection conn =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(rel);
        return conn;
    }

    private IDiagramModelGroup freshGroup() {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        return group;
    }

    // ------------------------------------------------------------------
    // validateConnectionStylingParams — lineStyle is rejected, not dropped
    //
    // An ArchiMate connection's line style is fixed by its relationship type: the platform's
    // connection figures hardcode their dash pattern per type and never consult the model, and
    // the metamodel carries no connection line-style attribute at all. A lineStyle passed to a
    // connection tool therefore cannot change anything. Before this seam existed it was parsed,
    // accepted, counted as a requested change, and then dropped — and the tool answered
    // "success". The caller is an agent that cannot see the canvas, so that success WAS its
    // ground truth. Rejecting is the only honest answer available.
    // ------------------------------------------------------------------

    /** Rejection carries the code, names the parameter, and gives the reason. */
    @Test
    public void shouldRejectLineStyle_whenAppliedToAConnection() {
        try {
            StylingHelper.validateConnectionStylingParams(connectionStyling("dashed"));
            fail("expected lineStyle on a connection to be rejected");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("message must name the parameter, got: " + e.getMessage(),
                    e.getMessage().contains("lineStyle"));
            assertTrue("message must give the reason (relationship type), got: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains("relationship type"));
        }
    }

    /**
     * The supported idiom has to be in the response, not merely the prohibition — an agent that
     * is only told "no" substitutes something arbitrary. lineColor + lineWidth is the pair a real
     * agent run reached for unaided after reading the old description.
     */
    @Test
    public void shouldNameTheSupportedIdiom_whenRejectingLineStyleOnAConnection() {
        try {
            StylingHelper.validateConnectionStylingParams(connectionStyling("dashed"));
            fail("expected lineStyle on a connection to be rejected");
        } catch (ModelAccessException e) {
            String correction = e.getSuggestedCorrection();
            assertNotNull("a rejection must carry a suggestedCorrection", correction);
            assertTrue("suggestedCorrection must name lineColor, got: " + correction,
                    correction.contains("lineColor"));
            assertTrue("suggestedCorrection must name lineWidth, got: " + correction,
                    correction.contains("lineWidth"));
        }
    }

    /**
     * Parity: a value that is a legal view-object enum and a value that is nonsense must produce
     * the SAME rejection. If "dashed" succeeded and "banana" produced an enum error, the caller
     * would reasonably conclude "dashed" had been applied.
     */
    @Test
    public void shouldRejectLineStyleIdentically_whenTheValueIsNonsenseAndWhenItIsAValidEnum() {
        ModelAccessException valid = captureRejection(connectionStyling("dashed"));
        ModelAccessException nonsense = captureRejection(connectionStyling("banana"));

        assertEquals(valid.getErrorCode(), nonsense.getErrorCode());
        assertEquals("the rejection must not depend on the value — otherwise one of the two "
                + "reads as though it were applied",
                valid.getMessage(), nonsense.getMessage());
        assertEquals(valid.getSuggestedCorrection(), nonsense.getSuggestedCorrection());
    }

    /**
     * The empty string is the boundary value, and it is refused like any other.
     *
     * <p>{@code ""} means "clear to default" everywhere else on the styling rail, and the handler
     * deliberately preserves it rather than folding it to null. On a connection there is no line
     * style to clear, so treating {@code ""} as a quiet success would re-open the hole this seam
     * closed — for exactly one value, which is the hardest kind to notice. Value-independence has
     * to hold at the boundary or it does not hold.</p>
     */
    @Test
    public void shouldRejectLineStyle_whenTheValueIsTheEmptyStringClearRequest() {
        ModelAccessException empty = captureRejection(connectionStyling(""));
        assertEquals(ErrorCode.INVALID_PARAMETER, empty.getErrorCode());
        assertEquals("the empty string must be refused exactly as a real value is",
                captureRejection(connectionStyling("dashed")).getMessage(), empty.getMessage());
    }

    /** No partial apply: a call carrying lineStyle beside supported fields is rejected whole. */
    @Test
    public void shouldRejectTheWholeCall_whenLineStyleRidesAlongsideValidLineColorAndLineWidth() {
        StylingParams mixed = new StylingParams(
                null, "#D35400", null, null, 2, null, null, null,
                null, null, null, "dashed", null, null, null, null);
        assertEquals(ErrorCode.INVALID_PARAMETER, captureRejection(mixed).getErrorCode());
    }

    /**
     * Ordering (both directions): the lineStyle rejection must fire before every other
     * connection-styling check, so the caller is never handed a different error that implies
     * lineStyle itself was acceptable.
     */
    @Test
    public void shouldReportTheLineStyleRejection_whenAnotherConnectionParamIsAlsoInvalid() {
        StylingParams alsoBadColor = new StylingParams(
                null, "not-a-hex", null, null, null, null, null, null,
                null, null, null, "dashed", null, null, null, null);
        assertTrue("lineStyle must win over the lineColor error",
                captureRejection(alsoBadColor).getMessage().contains("lineStyle"));

        StylingParams alsoBadWidth = new StylingParams(
                null, null, null, null, 99, null, null, null,
                null, null, null, "dashed", null, null, null, null);
        assertTrue("lineStyle must win over the lineWidth error",
                captureRejection(alsoBadWidth).getMessage().contains("lineStyle"));
    }

    /**
     * Negative control — this one runs the real path. A guard that rejects everything would pass
     * every test above; this proves the supported connection-styling rail still works, and that
     * the other validators downstream of the new check are still reached.
     */
    @Test
    public void shouldStillValidateSupportedConnectionStyling_whenNoLineStyleIsPresent() {
        StylingHelper.validateConnectionStylingParams(new StylingParams(
                null, "#D35400", "#000000", null, 2, null, null, null,
                "Arial", 10, "bold", null, null, null, null, null));

        // ...and the checks that follow the new one still fire on their own bad input.
        StylingParams badWidth = new StylingParams(null, null, null, null, 99);
        assertEquals(ErrorCode.INVALID_PARAMETER, captureRejection(badWidth).getErrorCode());
    }

    private static StylingParams connectionStyling(String lineStyle) {
        return new StylingParams(null, null, null, null, null, null, null, null,
                null, null, null, lineStyle, null, null, null, null);
    }

    private static ModelAccessException captureRejection(StylingParams styling) {
        try {
            StylingHelper.validateConnectionStylingParams(styling);
            throw new AssertionError("expected connection styling to be rejected: " + styling);
        } catch (ModelAccessException e) {
            return e;
        }
    }

    private IDiagramModelArchimateObject freshArchimateObject() {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        IArchimateElement element = IArchimateFactory.eINSTANCE.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        IDiagramModelArchimateObject obj = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        obj.setArchimateElement(element);
        return obj;
    }

    private IDiagramModelArchimateObject freshGroupingElement() {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        IArchimateElement grouping = IArchimateFactory.eINSTANCE.createGrouping();
        model.getFolder(FolderType.OTHER).getElements().add(grouping);
        IDiagramModelArchimateObject obj = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        obj.setArchimateElement(grouping);
        return obj;
    }
}
