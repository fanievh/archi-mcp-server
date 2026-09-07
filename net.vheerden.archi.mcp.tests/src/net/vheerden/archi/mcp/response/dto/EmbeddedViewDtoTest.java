package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

/**
 * Tests for {@link EmbeddedViewDto} record (G8).
 */
public class EmbeddedViewDtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void shouldSerialiseAllFields() throws Exception {
        EmbeddedViewDto dto = new EmbeddedViewDto(
                "vo-ref-1", "view-source-id",
                100, 200, 185, 80,
                "vo-parent-1",
                "#FFE4B5", "#8B4513", "#000000",
                255, 2,
                "Arial", 14, "bold",
                "top-bottom", false, 128, "dashed",
                "centre", "top",
                "auto-placement note");

        String json = MAPPER.writeValueAsString(dto);

        assertTrue("viewObjectId present", json.contains("\"viewObjectId\":\"vo-ref-1\""));
        assertTrue("referencedViewId present",
                json.contains("\"referencedViewId\":\"view-source-id\""));
        assertTrue("x present", json.contains("\"x\":100"));
        assertTrue("y present", json.contains("\"y\":200"));
        assertTrue("width present", json.contains("\"width\":185"));
        assertTrue("height present", json.contains("\"height\":80"));
        assertTrue("parentViewObjectId present",
                json.contains("\"parentViewObjectId\":\"vo-parent-1\""));
        assertTrue("fillColor present", json.contains("\"fillColor\":\"#FFE4B5\""));
        assertTrue("lineStyle present", json.contains("\"lineStyle\":\"dashed\""));
        assertTrue("fontStyle present", json.contains("\"fontStyle\":\"bold\""));
        assertTrue("gradient present", json.contains("\"gradient\":\"top-bottom\""));
        assertTrue("deriveLineColor present", json.contains("\"deriveLineColor\":false"));
        assertTrue("note present", json.contains("\"note\":\"auto-placement note\""));
    }

    @Test
    public void shouldOmitNullStyling_fromJson() throws Exception {
        EmbeddedViewDto dto = new EmbeddedViewDto(
                "vo-ref-2", "view-source-id",
                10, 20, 185, 80,
                null);

        String json = MAPPER.writeValueAsString(dto);

        assertFalse("fillColor must be omitted when null", json.contains("fillColor"));
        assertFalse("lineColor must be omitted when null", json.contains("lineColor"));
        assertFalse("fontColor must be omitted when null", json.contains("fontColor"));
        assertFalse("opacity must be omitted when null", json.contains("opacity"));
        assertFalse("lineWidth must be omitted when null", json.contains("lineWidth"));
        assertFalse("fontName must be omitted when null", json.contains("fontName"));
        assertFalse("fontSize must be omitted when null", json.contains("fontSize"));
        assertFalse("fontStyle must be omitted when null", json.contains("fontStyle"));
        assertFalse("gradient must be omitted when null", json.contains("gradient"));
        assertFalse("deriveLineColor must be omitted when null", json.contains("deriveLineColor"));
        assertFalse("outlineOpacity must be omitted when null", json.contains("outlineOpacity"));
        assertFalse("lineStyle must be omitted when null", json.contains("lineStyle"));
        assertFalse("textAlignment must be omitted when null", json.contains("textAlignment"));
        assertFalse("verticalTextAlignment must be omitted when null",
                json.contains("verticalTextAlignment"));
        assertFalse("note must be omitted when null", json.contains("\"note\""));
    }

    @Test
    public void shouldOmitParentViewObjectId_whenTopLevel() throws Exception {
        EmbeddedViewDto dto = new EmbeddedViewDto(
                "vo-ref-3", "view-source-id",
                50, 50, 185, 80,
                null);

        String json = MAPPER.writeValueAsString(dto);

        assertFalse("parentViewObjectId must be omitted when null",
                json.contains("parentViewObjectId"));
    }

    @Test
    public void shouldOmitNote_whenAbsent() throws Exception {
        EmbeddedViewDto dto = new EmbeddedViewDto(
                "vo-ref-4", "view-source-id",
                0, 0, 185, 80,
                "vo-parent",
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null);

        String json = MAPPER.writeValueAsString(dto);

        assertFalse("note field must be omitted when null", json.contains("\"note\""));
    }

    @Test
    public void shouldRoundTripJson() throws Exception {
        EmbeddedViewDto original = new EmbeddedViewDto(
                "vo-ref-5", "view-source-id",
                100, 200, 185, 80,
                "vo-parent",
                "#FFFFFF", "#000000", null,
                200, 1,
                null, null, "italic",
                null, null, null, "solid",
                null, null,
                null);

        String json = MAPPER.writeValueAsString(original);
        EmbeddedViewDto roundTripped = MAPPER.readValue(json, EmbeddedViewDto.class);

        assertEquals(original.viewObjectId(), roundTripped.viewObjectId());
        assertEquals(original.referencedViewId(), roundTripped.referencedViewId());
        assertEquals(original.x(), roundTripped.x());
        assertEquals(original.width(), roundTripped.width());
        assertEquals(original.parentViewObjectId(), roundTripped.parentViewObjectId());
        assertEquals(original.fillColor(), roundTripped.fillColor());
        assertEquals(original.fontStyle(), roundTripped.fontStyle());
        assertEquals(original.lineStyle(), roundTripped.lineStyle());
        assertNull("null fontColor round-trips as null", roundTripped.fontColor());
    }
}
