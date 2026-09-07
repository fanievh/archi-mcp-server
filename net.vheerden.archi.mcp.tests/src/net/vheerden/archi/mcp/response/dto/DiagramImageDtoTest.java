package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure-JUnit tests for {@link DiagramImageDto} (G16).
 *
 * <p>Verifies record construction, NON_NULL JSON omission discipline, and
 * round-trip serialisation. No OSGi/EMF dependencies.</p>
 */
public class DiagramImageDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void shouldDefaultAllNullableFieldsToNull() {
        DiagramImageDto dto = new DiagramImageDto(
                "vo-1", "images/abc.png", 10, 20, 64, 64,
                null, null, null);
        assertEquals("vo-1", dto.viewObjectId());
        assertEquals("images/abc.png", dto.imagePath());
        assertEquals(10, dto.x());
        assertEquals(20, dto.y());
        assertEquals(64, dto.width());
        assertEquals(64, dto.height());
        assertNull(dto.parentViewObjectId());
        assertNull(dto.borderColor());
        assertNull(dto.documentation());
    }

    @Test
    public void shouldSerialiseRequiredFields_omittingNullableNulls() throws Exception {
        DiagramImageDto dto = new DiagramImageDto(
                "vo-1", "images/abc.png", 10, 20, 64, 64,
                null, null, null);
        String json = mapper.writeValueAsString(dto);
        // Required (always serialised) fields
        assertTrue("viewObjectId should appear", json.contains("\"viewObjectId\":\"vo-1\""));
        assertTrue("imagePath should appear", json.contains("\"imagePath\":\"images/abc.png\""));
        assertTrue("x should appear", json.contains("\"x\":10"));
        assertTrue("y should appear", json.contains("\"y\":20"));
        assertTrue("width should appear", json.contains("\"width\":64"));
        assertTrue("height should appear", json.contains("\"height\":64"));
        // NON_NULL: null fields omitted
        assertFalse("parentViewObjectId should be omitted", json.contains("parentViewObjectId"));
        assertFalse("borderColor should be omitted", json.contains("borderColor"));
        assertFalse("documentation should be omitted", json.contains("documentation"));
    }

    @Test
    public void shouldRoundTripJson() throws Exception {
        DiagramImageDto original = new DiagramImageDto(
                "vo-7", "images/diagram.svg", 100, 200, 320, 240,
                "parent-group-1", "#FF0000", "Architecture sketch");
        String json = mapper.writeValueAsString(original);
        DiagramImageDto roundTripped = mapper.readValue(json, DiagramImageDto.class);
        assertEquals(original, roundTripped);
    }

    @Test
    public void shouldSerialiseBorderColorWhenSet() throws Exception {
        DiagramImageDto dto = new DiagramImageDto(
                "vo-2", "images/x.png", 0, 0, 50, 50,
                null, "#00FF00", null);
        String json = mapper.writeValueAsString(dto);
        assertTrue("borderColor should appear when set",
                json.contains("\"borderColor\":\"#00FF00\""));
        assertFalse("documentation should be omitted when null",
                json.contains("documentation"));
    }

    @Test
    public void shouldOmitDocumentationWhenNull() throws Exception {
        DiagramImageDto dto = new DiagramImageDto(
                "vo-3", "images/y.png", 5, 5, 16, 16,
                "grp-1", null, null);
        String json = mapper.writeValueAsString(dto);
        assertTrue("parentViewObjectId should appear when set",
                json.contains("\"parentViewObjectId\":\"grp-1\""));
        assertFalse("documentation should be omitted under NON_NULL",
                json.contains("documentation"));
        assertFalse("borderColor should be omitted under NON_NULL",
                json.contains("borderColor"));
    }
}
