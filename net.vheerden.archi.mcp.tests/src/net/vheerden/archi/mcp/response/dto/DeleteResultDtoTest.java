package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for {@link DeleteResultDto} record (Story 8-4).
 */
public class DeleteResultDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void shouldCreateForElementDeletion() {
        DeleteResultDto dto = new DeleteResultDto(
                "elem-1", "My Element", "BusinessActor",
                3, 2, 0, null, null, null);

        assertEquals("elem-1", dto.id());
        assertEquals("My Element", dto.name());
        assertEquals("BusinessActor", dto.type());
        assertEquals(3, dto.relationshipsRemoved());
        assertEquals(2, dto.viewReferencesRemoved());
        assertEquals(0, dto.viewConnectionsRemoved());
        assertNull(dto.elementsRemoved());
        assertNull(dto.viewsRemoved());
        assertNull(dto.foldersRemoved());
    }

    @Test
    public void shouldCreateForRelationshipDeletion() {
        DeleteResultDto dto = new DeleteResultDto(
                "rel-1", "Serving", "ServingRelationship",
                0, 0, 4, null, null, null);

        assertEquals("rel-1", dto.id());
        assertEquals(0, dto.relationshipsRemoved());
        assertEquals(4, dto.viewConnectionsRemoved());
    }

    @Test
    public void shouldCreateForFolderForceDeletion() {
        DeleteResultDto dto = new DeleteResultDto(
                "folder-1", "My Folder", "Folder",
                5, 3, 2, 4, 1, 2);

        assertEquals("folder-1", dto.id());
        assertEquals("Folder", dto.type());
        assertEquals(Integer.valueOf(4), dto.elementsRemoved());
        assertEquals(Integer.valueOf(1), dto.viewsRemoved());
        assertEquals(Integer.valueOf(2), dto.foldersRemoved());
    }

    @Test
    public void shouldOmitNullFolderCounts_whenSerialized() throws Exception {
        DeleteResultDto dto = new DeleteResultDto(
                "elem-1", "Test", "BusinessActor",
                1, 0, 0, null, null, null);

        String json = objectMapper.writeValueAsString(dto);
        assertFalse("elementsRemoved should be omitted when null",
                json.contains("elementsRemoved"));
        assertFalse("viewsRemoved should be omitted when null",
                json.contains("viewsRemoved"));
        assertFalse("foldersRemoved should be omitted when null",
                json.contains("foldersRemoved"));
    }

    @Test
    public void shouldIncludeFolderCounts_whenNonNull() throws Exception {
        DeleteResultDto dto = new DeleteResultDto(
                "folder-1", "Test", "Folder",
                0, 0, 0, 3, 2, 1);

        String json = objectMapper.writeValueAsString(dto);
        assertTrue("elementsRemoved should be present",
                json.contains("\"elementsRemoved\":3"));
        assertTrue("viewsRemoved should be present",
                json.contains("\"viewsRemoved\":2"));
        assertTrue("foldersRemoved should be present",
                json.contains("\"foldersRemoved\":1"));
    }
}
