package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for {@link MoveResultDto} JSON serialization (Story 8-5).
 */
public class MoveResultDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void shouldSerializeAllFields() throws Exception {
        MoveResultDto dto = new MoveResultDto(
                "elem-1", "My Element", "Element", "BusinessActor",
                "Business/Processes", "Business/Archived");

        String json = objectMapper.writeValueAsString(dto);

        assertTrue(json.contains("\"id\":\"elem-1\""));
        assertTrue(json.contains("\"name\":\"My Element\""));
        assertTrue(json.contains("\"objectType\":\"Element\""));
        assertTrue(json.contains("\"elementType\":\"BusinessActor\""));
        assertTrue(json.contains("\"sourceFolderPath\":\"Business/Processes\""));
        assertTrue(json.contains("\"targetFolderPath\":\"Business/Archived\""));
    }

    @Test
    public void shouldOmitNullElementType() throws Exception {
        MoveResultDto dto = new MoveResultDto(
                "folder-1", "My Folder", "Folder", null,
                "Business", "Application");

        String json = objectMapper.writeValueAsString(dto);

        assertFalse("elementType should be omitted when null",
                json.contains("elementType"));
        assertTrue(json.contains("\"objectType\":\"Folder\""));
    }
}
