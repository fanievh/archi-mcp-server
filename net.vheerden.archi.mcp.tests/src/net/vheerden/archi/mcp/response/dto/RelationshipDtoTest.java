package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    // ---- relationship semantic attributes ----

    @Test
    public void shouldSerialiseSemanticAttributeFields_whenSet() throws Exception {
        RelationshipDto dto = new RelationshipDto(
                "rel-1", "Customer→Order", "AccessRelationship", null,
                "src-1", "tgt-1", false, null, null, null, null,
                "read", null, null);
        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue("expected accessType in JSON: " + json,
                json.contains("\"accessType\":\"read\""));
    }

    @Test
    public void shouldOmitSemanticAttributeFields_whenNull() throws Exception {
        RelationshipDto dto = new RelationshipDto(
                "rel-1", "Uses", "ServingRelationship", "src-1", "tgt-1");
        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("accessType should be omitted: " + json, json.contains("accessType"));
        assertFalse("associationDirected should be omitted: " + json, json.contains("associationDirected"));
        assertFalse("influenceStrength should be omitted: " + json, json.contains("influenceStrength"));
    }

    @Test
    public void shouldRoundTripSemanticAttributeFields() throws Exception {
        RelationshipDto original = new RelationshipDto(
                "rel-1", "Customer→Order", "AccessRelationship", null,
                "src-1", "tgt-1", false, null, null, null, null,
                "readwrite", Boolean.TRUE, "+/-");
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(original);
        RelationshipDto parsed = mapper.readValue(json, RelationshipDto.class);
        assertEquals("readwrite", parsed.accessType());
        assertEquals(Boolean.TRUE, parsed.associationDirected());
        assertEquals("+/-", parsed.influenceStrength());
    }

    @Test
    public void shouldPreserveBackCompatCtors() throws Exception {
        // 5-arg back-compat ctor — semantic-attribute fields must default to null + be omitted from JSON
        RelationshipDto dto5 = new RelationshipDto("rel-1", "Uses", "ServingRelationship", "a", "b");
        assertNull(dto5.accessType());
        assertNull(dto5.associationDirected());
        assertNull(dto5.influenceStrength());

        // 6-arg back-compat ctor — same
        RelationshipDto dto6 = new RelationshipDto(
                "rel-1", "Uses", "ServingRelationship", "a", "b", true);
        assertTrue(dto6.alreadyExisted());
        assertNull(dto6.accessType());

        // 11-arg back-compat ctor (the canonical shape before semantic attributes) — same
        RelationshipDto dto11 = new RelationshipDto(
                "rel-1", "Data Flow", "FlowRelationship", "Material Flow",
                "src-1", "tgt-1", false, null, null, null, null);
        assertEquals("Material Flow", dto11.specialization());
        assertNull(dto11.accessType());
        assertNull(dto11.associationDirected());
        assertNull(dto11.influenceStrength());
    }

    @Test
    public void shouldOmitDefaultAlreadyExistedButPopulateAccessType() throws Exception {
        // NON_DEFAULT on alreadyExisted means false is omitted; NON_NULL on accessType
        // means it IS populated. Sanity check that the new field annotations didn't
        // shift existing behaviour.
        RelationshipDto dto = new RelationshipDto(
                "rel-1", "Customer→Order", "AccessRelationship", null,
                "src-1", "tgt-1", false, null, null, null, null,
                "write", null, null);
        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("alreadyExisted=false should be omitted (NON_DEFAULT): " + json,
                json.contains("alreadyExisted"));
        assertTrue("accessType=write should be present (NON_NULL): " + json,
                json.contains("\"accessType\":\"write\""));
    }
}
