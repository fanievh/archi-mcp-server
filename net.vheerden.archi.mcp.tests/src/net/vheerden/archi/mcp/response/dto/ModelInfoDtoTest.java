package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link ModelInfoDto} record.
 */
public class ModelInfoDtoTest {

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
}
