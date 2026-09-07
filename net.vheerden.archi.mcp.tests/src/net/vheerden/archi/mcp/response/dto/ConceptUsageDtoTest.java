package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.ConceptUsageDto.ViewReferenceDto;
import net.vheerden.archi.mcp.response.dto.ConceptUsageDto.VisualObjectReferenceDto;

/**
 * Tests for {@link ConceptUsageDto} (G10).
 *
 * <p>Pure-JUnit — no EMF / OSGi runtime. Validates the record's Jackson
 * serialisation invariants (omit-null, ordering pass-through, embedding hook).</p>
 */
public class ConceptUsageDtoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void shouldSerialiseElementUsage_withMultipleViews() throws Exception {
        VisualObjectReferenceDto v1 = new VisualObjectReferenceDto("vo-1", "object");
        VisualObjectReferenceDto v2 = new VisualObjectReferenceDto("vo-2", "object");
        ViewReferenceDto vr1 = new ViewReferenceDto(
                "view-1", "Application Cooperation", "Application Cooperation", "archimate",
                List.of(v1));
        ViewReferenceDto vr2 = new ViewReferenceDto(
                "view-2", "Layered", "Layered", "archimate",
                List.of(v2));
        ConceptUsageDto dto = new ConceptUsageDto(
                "concept-id", "Payment Service", "ApplicationComponent", "element",
                2, 2, List.of(vr1, vr2), null);

        String json = MAPPER.writeValueAsString(dto);
        assertTrue("conceptId present", json.contains("\"conceptId\":\"concept-id\""));
        assertTrue("conceptKind=element", json.contains("\"conceptKind\":\"element\""));
        assertTrue("viewReferenceCount=2", json.contains("\"viewReferenceCount\":2"));
        assertTrue("visualReferenceCount=2", json.contains("\"visualReferenceCount\":2"));
        assertFalse("embeddingViewReferences omitted when null",
                json.contains("embeddingViewReferences"));
    }

    @Test
    public void shouldSerialiseRelationshipUsage_withSingleView() throws Exception {
        VisualObjectReferenceDto v1 = new VisualObjectReferenceDto("conn-1", "connection");
        ViewReferenceDto vr1 = new ViewReferenceDto(
                "view-X", "View X", "Application Cooperation", "archimate",
                List.of(v1));
        ConceptUsageDto dto = new ConceptUsageDto(
                "rel-id", "serves", "ServingRelationship", "relationship",
                1, 1, List.of(vr1), null);

        String json = MAPPER.writeValueAsString(dto);
        assertTrue("conceptKind=relationship", json.contains("\"conceptKind\":\"relationship\""));
        assertTrue("kind=connection", json.contains("\"kind\":\"connection\""));
    }

    @Test
    public void shouldOrderViewReferencesByName_thenId() {
        // The DTO doesn't sort itself — ordering is the producer's responsibility
        // (ArchiModelAccessorImpl.buildConceptUsageDto). This test pins the contract
        // by constructing a pre-sorted list and asserting record-level fidelity.
        VisualObjectReferenceDto v = new VisualObjectReferenceDto("vo-X", "object");
        ViewReferenceDto a = new ViewReferenceDto("id-A", "Alpha", null, "archimate", List.of(v));
        ViewReferenceDto m = new ViewReferenceDto("id-M", "Mid", null, "archimate", List.of(v));
        ViewReferenceDto z = new ViewReferenceDto("id-Z", "Zeta", null, "archimate", List.of(v));
        ConceptUsageDto dto = new ConceptUsageDto(
                "c1", "Name", "Type", "element", 3, 3,
                List.of(a, m, z), null);

        assertEquals("Alpha", dto.viewReferences().get(0).viewName());
        assertEquals("Mid", dto.viewReferences().get(1).viewName());
        assertEquals("Zeta", dto.viewReferences().get(2).viewName());
    }

    @Test
    public void shouldOrderVisualObjectsByViewObjectId() {
        VisualObjectReferenceDto a = new VisualObjectReferenceDto("a-1", "object");
        VisualObjectReferenceDto b = new VisualObjectReferenceDto("b-2", "object");
        ViewReferenceDto vr = new ViewReferenceDto(
                "v1", "V1", null, "archimate", List.of(a, b));
        assertEquals("a-1", vr.visualObjects().get(0).viewObjectId());
        assertEquals("b-2", vr.visualObjects().get(1).viewObjectId());
    }

    @Test
    public void shouldSerialiseEmptyResult_zeroViewReferences() throws Exception {
        ConceptUsageDto dto = new ConceptUsageDto(
                "orphan-id", "Orphan", "BusinessActor", "element",
                0, 0, List.of(), null);
        String json = MAPPER.writeValueAsString(dto);
        assertTrue("viewReferenceCount=0", json.contains("\"viewReferenceCount\":0"));
        assertTrue("visualReferenceCount=0", json.contains("\"visualReferenceCount\":0"));
        assertTrue("viewReferences is empty array", json.contains("\"viewReferences\":[]"));
    }

    @Test
    public void shouldOmitNullViewpointType_fromJson() throws Exception {
        ViewReferenceDto vr = new ViewReferenceDto(
                "v1", "Sketch View", null, "sketch", List.of());
        String json = MAPPER.writeValueAsString(vr);
        assertFalse("viewpointType omitted when null", json.contains("viewpointType"));
    }

    @Test
    public void shouldOmitEmbeddingViewReferences_whenNull() throws Exception {
        ConceptUsageDto dto = new ConceptUsageDto(
                "c", "n", "ApplicationComponent", "element",
                0, 0, List.of(), null);
        String json = MAPPER.writeValueAsString(dto);
        assertFalse("embeddingViewReferences omitted when null",
                json.contains("embeddingViewReferences"));
    }

    @Test
    public void shouldCountVisualReferences_acrossMultiplePlacementsInSameView() {
        VisualObjectReferenceDto v1 = new VisualObjectReferenceDto("vo-a", "object");
        VisualObjectReferenceDto v2 = new VisualObjectReferenceDto("vo-b", "object");
        ViewReferenceDto vr = new ViewReferenceDto(
                "view-1", "View", null, "archimate", List.of(v1, v2));
        // 1 view, 2 placements
        ConceptUsageDto dto = new ConceptUsageDto(
                "c1", "Shared", "ApplicationComponent", "element",
                1, 2, List.of(vr), null);
        assertEquals(1, dto.viewReferenceCount());
        assertEquals(2, dto.visualReferenceCount());
        assertEquals(2, dto.viewReferences().get(0).visualObjects().size());
    }
}
