package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link RelationshipDto} record.
 */
public class RelationshipDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        RelationshipDto dto = new RelationshipDto(
                "rel-1", "Uses", "ServingRelationship", "src-1", "tgt-1");

        assertEquals("rel-1", dto.id());
        assertEquals("Uses", dto.name());
        assertEquals("ServingRelationship", dto.type());
        assertEquals("src-1", dto.sourceId());
        assertEquals("tgt-1", dto.targetId());
    }

    @Test
    public void shouldSupportNullName() {
        RelationshipDto dto = new RelationshipDto("rel-1", null, "AssociationRelationship", "a", "b");
        assertNull(dto.name());
    }

    @Test
    public void shouldIncludeSpecializationWhenPresent() {
        RelationshipDto dto = new RelationshipDto(
                "rel-1", "Data Flow", "FlowRelationship", "Material Flow",
                "src-1", "tgt-1", false, null, null, null, null);

        assertEquals("Material Flow", dto.specialization());
    }

    @Test
    public void shouldHaveNullSpecializationInConvenienceConstructor() {
        RelationshipDto dto = new RelationshipDto("rel-1", "Uses", "ServingRelationship", "a", "b");
        assertNull(dto.specialization());
    }

    @Test
    public void shouldSupportEquality() {
        RelationshipDto dto1 = new RelationshipDto("r-1", "R", "Flow", "a", "b");
        RelationshipDto dto2 = new RelationshipDto("r-1", "R", "Flow", "a", "b");
        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }
}
