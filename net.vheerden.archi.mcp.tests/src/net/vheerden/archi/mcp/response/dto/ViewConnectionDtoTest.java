package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link ViewConnectionDto} record.
 */
public class ViewConnectionDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        List<BendpointDto> bps = List.of(new BendpointDto(10, 20, 30, 40));
        ViewConnectionDto dto = new ViewConnectionDto(
                "vc-1", "rel-1", "ServingRelationship", "vo-1", "vo-2", bps);

        assertEquals("vc-1", dto.viewConnectionId());
        assertEquals("rel-1", dto.relationshipId());
        assertEquals("ServingRelationship", dto.relationshipType());
        assertEquals("vo-1", dto.sourceViewObjectId());
        assertEquals("vo-2", dto.targetViewObjectId());
        assertEquals(1, dto.bendpoints().size());
        assertEquals(10, dto.bendpoints().get(0).startX());
    }

    @Test
    public void shouldSupportNullBendpoints() {
        ViewConnectionDto dto = new ViewConnectionDto(
                "vc-1", "rel-1", "ServingRelationship", "vo-1", "vo-2", null);

        assertNull(dto.bendpoints());
    }

    @Test
    public void shouldSupportEquality() {
        ViewConnectionDto dto1 = new ViewConnectionDto(
                "vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);
        ViewConnectionDto dto2 = new ViewConnectionDto(
                "vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }

    @Test
    public void shouldCreateWithCanonicalConstructor_allNineFields() {
        List<BendpointDto> bps = List.of(new BendpointDto(10, 20, 30, 40));
        List<AbsoluteBendpointDto> absBps = List.of(new AbsoluteBendpointDto(300, 150));
        AnchorPointDto srcAnchor = new AnchorPointDto(110, 77);
        AnchorPointDto tgtAnchor = new AnchorPointDto(310, 77);

        ViewConnectionDto dto = new ViewConnectionDto(
                "vc-1", "rel-1", "ServingRelationship", "vo-1", "vo-2",
                bps, absBps, srcAnchor, tgtAnchor, null);

        assertEquals("vc-1", dto.viewConnectionId());
        assertEquals(1, dto.absoluteBendpoints().size());
        assertEquals(300, dto.absoluteBendpoints().get(0).x());
        assertEquals(150, dto.absoluteBendpoints().get(0).y());
        assertEquals(110, dto.sourceAnchor().x());
        assertEquals(77, dto.sourceAnchor().y());
        assertEquals(310, dto.targetAnchor().x());
        assertEquals(77, dto.targetAnchor().y());
    }

    @Test
    public void shouldDefaultNewFieldsToNull_inConvenienceConstructor() {
        ViewConnectionDto dto = new ViewConnectionDto(
                "vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);

        assertNull(dto.absoluteBendpoints());
        assertNull(dto.sourceAnchor());
        assertNull(dto.targetAnchor());
    }

    @Test
    public void shouldIncludeTextPosition_whenNonNull() {
        ViewConnectionDto dto = new ViewConnectionDto(
                "vc-1", "rel-1", "ServingRelationship", "vo-1", "vo-2",
                null, null, null, null, 2);

        assertEquals(Integer.valueOf(2), dto.textPosition());
    }

    @Test
    public void shouldDefaultTextPositionToNull_inConvenienceConstructor() {
        ViewConnectionDto dto = new ViewConnectionDto(
                "vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);

        assertNull(dto.textPosition());
    }

    @Test
    public void shouldSupportEquality_withAbsoluteFields() {
        AnchorPointDto anchor = new AnchorPointDto(110, 77);
        ViewConnectionDto dto1 = new ViewConnectionDto(
                "vc-1", "rel-1", "Serving", "vo-1", "vo-2",
                null, null, anchor, null, null);
        ViewConnectionDto dto2 = new ViewConnectionDto(
                "vc-1", "rel-1", "Serving", "vo-1", "vo-2",
                null, null, anchor, null, null);

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }
}
