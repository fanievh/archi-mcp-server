package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link ViewNoteDto} record.
 */
public class ViewNoteDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        ViewNoteDto dto = new ViewNoteDto(
                "vo-note-1", "Design decision: use event-driven pattern",
                150, 250, 200, 80,
                "vo-parent-1");

        assertEquals("vo-note-1", dto.viewObjectId());
        assertEquals("Design decision: use event-driven pattern", dto.content());
        assertEquals(150, dto.x());
        assertEquals(250, dto.y());
        assertEquals(200, dto.width());
        assertEquals(80, dto.height());
        assertEquals("vo-parent-1", dto.parentViewObjectId());
    }

    @Test
    public void shouldSupportNullParentViewObjectId() {
        ViewNoteDto dto = new ViewNoteDto(
                "vo-note-2", "Standalone note",
                0, 0, 120, 55,
                null);

        assertNull(dto.parentViewObjectId());
    }

    @Test
    public void shouldSupportEquality() {
        ViewNoteDto dto1 = new ViewNoteDto(
                "vo-1", "Note text", 10, 20, 120, 55, "p-1");
        ViewNoteDto dto2 = new ViewNoteDto(
                "vo-1", "Note text", 10, 20, 120, 55, "p-1");

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }

    // ---- Story B16: note field tests ----

    @Test
    public void shouldStoreNoteField_whenCanonicalConstructor() {
        ViewNoteDto dto = new ViewNoteDto(
                "vo-1", "Title", 100, 10, 185, 80, null,
                null, null, null, null, null,
                "position='above-content' takes precedence over explicit x/y coordinates");

        assertEquals("position='above-content' takes precedence over explicit x/y coordinates",
                dto.note());
    }

    @Test
    public void shouldDefaultNoteToNull_whenStylingConstructor() {
        ViewNoteDto dto = new ViewNoteDto(
                "vo-1", "Title", 100, 10, 185, 80, null,
                "#FFFF00", null, null, null, null);

        assertNull("note should be null when using 12-param styling constructor", dto.note());
    }

    @Test
    public void shouldDefaultNoteToNull_whenBasicConstructor() {
        ViewNoteDto dto = new ViewNoteDto(
                "vo-1", "Title", 100, 10, 185, 80, null);

        assertNull("note should be null when using 7-param basic constructor", dto.note());
    }
}
