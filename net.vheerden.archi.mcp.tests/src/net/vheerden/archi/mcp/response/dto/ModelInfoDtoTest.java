package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for {@link ModelInfoDto} record.
 *
 * <p>G6: extended with purpose + properties read-side parity tests.</p>
 */
public class ModelInfoDtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void shouldCreateWithAllFields() {
        Map<String, Integer> distribution = Map.of(
                "BusinessActor", 5,
                "ApplicationComponent", 3);

        Map<String, Integer> relDistribution = Map.of(
                "ServingRelationship", 2,
                "FlowRelationship", 2);
        Map<String, Integer> layerDistribution = Map.of(
                "Business", 5,
                "Application", 3);

        ModelInfoDto dto = new ModelInfoDto("Test Model", 8, 4, 2, 0,
                distribution, relDistribution, layerDistribution);

        assertEquals("Test Model", dto.name());
        assertEquals(8, dto.elementCount());
        assertEquals(4, dto.relationshipCount());
        assertEquals(2, dto.viewCount());
        assertEquals(distribution, dto.elementTypeDistribution());
        assertEquals(relDistribution, dto.relationshipTypeDistribution());
        assertEquals(layerDistribution, dto.layerDistribution());
    }

    @Test
    public void shouldSupportNullName() {
        ModelInfoDto dto = new ModelInfoDto(null, 0, 0, 0, 0, Map.of(), Map.of(), Map.of());
        assertNull(dto.name());
    }

    @Test
    public void shouldSupportEmptyDistribution() {
        ModelInfoDto dto = new ModelInfoDto("Model", 0, 0, 0, 0, Map.of(), Map.of(), Map.of());
        assertTrue(dto.elementTypeDistribution().isEmpty());
        assertTrue(dto.relationshipTypeDistribution().isEmpty());
        assertTrue(dto.layerDistribution().isEmpty());
    }

    @Test
    public void shouldSupportEquality() {
        Map<String, Integer> dist = Map.of("BusinessActor", 5);
        Map<String, Integer> relDist = Map.of("ServingRelationship", 3);
        Map<String, Integer> layerDist = Map.of("Business", 5);
        ModelInfoDto dto1 = new ModelInfoDto("Model", 5, 3, 1, 0, dist, relDist, layerDist);
        ModelInfoDto dto2 = new ModelInfoDto("Model", 5, 3, 1, 0, dist, relDist, layerDist);
        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }

    // ---- G6: purpose + properties + back-compat ctor ----

    @Test
    public void shouldOmitPurposeFromJson_whenNull() throws Exception {
        // Legacy 8-arg ctor → purpose + properties default to null
        ModelInfoDto dto = new ModelInfoDto("Model", 5, 3, 1, 0,
                Map.of(), Map.of(), Map.of());
        String json = MAPPER.writeValueAsString(dto);
        assertFalse("purpose=null should be omitted via @JsonInclude(NON_NULL)",
                json.contains("\"purpose\""));
        assertFalse("properties=null should be omitted via @JsonInclude(NON_NULL)",
                json.contains("\"properties\""));
    }

    @Test
    public void shouldIncludePurposeInJson_whenPopulated() throws Exception {
        ModelInfoDto dto = new ModelInfoDto("Model", "Strategic EA", null,
                5, 3, 1, 0, Map.of(), Map.of(), Map.of());
        String json = MAPPER.writeValueAsString(dto);
        assertTrue("purpose value should appear in JSON when populated",
                json.contains("\"purpose\":\"Strategic EA\""));
    }

    @Test
    public void shouldOmitPropertiesFromJson_whenNull() throws Exception {
        // Populated purpose but null properties — only purpose should serialize
        ModelInfoDto dto = new ModelInfoDto("Model", "Purpose only", null,
                5, 3, 1, 0, Map.of(), Map.of(), Map.of());
        String json = MAPPER.writeValueAsString(dto);
        assertTrue(json.contains("\"purpose\":\"Purpose only\""));
        assertFalse("properties=null should be omitted", json.contains("\"properties\""));
    }

    @Test
    public void shouldDelegateToCanonicalCtor_whenBackCompat8FieldCtorUsed() {
        // The 8-arg back-compat ctor delegates to the canonical 10-arg form with null purpose/properties
        ModelInfoDto dto = new ModelInfoDto("Model", 5, 3, 1, 0,
                Map.of("Actor", 5), Map.of("Serving", 3), Map.of("Business", 5));

        assertEquals("Model", dto.name());
        assertNull("8-arg ctor should set purpose to null", dto.purpose());
        assertNull("8-arg ctor should set properties to null", dto.properties());
        assertEquals(5, dto.elementCount());
        assertEquals(3, dto.relationshipCount());
        assertEquals(1, dto.viewCount());
        assertEquals(Map.of("Actor", 5), dto.elementTypeDistribution());
    }

    @Test
    public void shouldIncludePropertiesInJson_whenPopulated() throws Exception {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("Author", "Jane");
        ModelInfoDto dto = new ModelInfoDto("Model", null, props,
                5, 3, 1, 0, Map.of(), Map.of(), Map.of());
        String json = MAPPER.writeValueAsString(dto);
        assertTrue("populated properties should appear in JSON",
                json.contains("\"properties\":{\"Author\":\"Jane\"}"));
    }
}
