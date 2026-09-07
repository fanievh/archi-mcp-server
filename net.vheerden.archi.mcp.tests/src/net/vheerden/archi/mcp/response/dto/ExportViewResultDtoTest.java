package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure JUnit tests for {@link ExportViewResultDto} JSON serialization shape.
 *
 * <p>No OSGi/EMF runtime required — exercises Jackson record serialization
 * with the {@code @JsonInclude(NON_NULL)} contract for vector-format outputs
 * for vector-format outputs.</p>
 */
public class ExportViewResultDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void shouldPopulateMimeType_forJpgFormat() throws Exception {
        ExportViewResultDto dto = new ExportViewResultDto(
                "view-1", "View", "jpg", "image/jpeg", 800, 600, null, 100);
        String json = mapper.writeValueAsString(dto);
        assertTrue("JSON should carry jpg format", json.contains("\"format\":\"jpg\""));
        assertTrue("JSON should carry image/jpeg mimeType",
                json.contains("\"mimeType\":\"image/jpeg\""));
    }

    @Test
    public void shouldPopulateMimeType_forPdfFormat() throws Exception {
        ExportViewResultDto dto = new ExportViewResultDto(
                "view-1", "View", "pdf", "application/pdf", null, null, null, 200);
        String json = mapper.writeValueAsString(dto);
        assertTrue("JSON should carry pdf format", json.contains("\"format\":\"pdf\""));
        assertTrue("JSON should carry application/pdf mimeType",
                json.contains("\"mimeType\":\"application/pdf\""));
    }

    @Test
    public void shouldOmitWidthHeightFromJson_whenPdfFormat() throws Exception {
        ExportViewResultDto dto = new ExportViewResultDto(
                "view-1", "View", "pdf", "application/pdf", null, null, null, 200);
        String json = mapper.writeValueAsString(dto);
        assertFalse("PDF JSON should omit width", json.contains("\"width\""));
        assertFalse("PDF JSON should omit height", json.contains("\"height\""));
        assertFalse("PDF JSON should omit filePath (null)",
                json.contains("\"filePath\""));
    }

    @Test
    public void shouldOmitWidthHeightFromJson_whenSvgFormat() throws Exception {
        ExportViewResultDto dto = new ExportViewResultDto(
                "view-1", "View", "svg", "image/svg+xml", null, null, null, 80);
        String json = mapper.writeValueAsString(dto);
        assertFalse("SVG JSON should omit width", json.contains("\"width\""));
        assertFalse("SVG JSON should omit height", json.contains("\"height\""));
    }

    @Test
    public void shouldRetainWidthHeight_whenPngFormat() throws Exception {
        ExportViewResultDto dto = new ExportViewResultDto(
                "view-1", "View", "png", "image/png", 800, 600, null, 100);
        String json = mapper.writeValueAsString(dto);
        assertTrue("PNG JSON should retain width", json.contains("\"width\":800"));
        assertTrue("PNG JSON should retain height", json.contains("\"height\":600"));
    }

    @Test
    public void shouldRoundTripJpgRecord() throws Exception {
        ExportViewResultDto original = new ExportViewResultDto(
                "view-jpg", "View", "jpg", "image/jpeg", 1024, 768,
                "/tmp/view.jpg", 150);
        String json = mapper.writeValueAsString(original);
        ExportViewResultDto parsed = mapper.readValue(json, ExportViewResultDto.class);
        assertEquals(original, parsed);
    }
}
