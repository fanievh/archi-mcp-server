package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

/**
 * Tests for {@link SetViewLabelExpressionResultDto} record.
 */
public class SetViewLabelExpressionResultDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        SetViewLabelExpressionResultDto dto = new SetViewLabelExpressionResultDto(
                "view-1", "Context View", "${name} ${property:evidenceMark}", 12, 3);

        assertEquals("view-1", dto.viewId());
        assertEquals("Context View", dto.viewName());
        assertEquals("${name} ${property:evidenceMark}", dto.labelExpression());
        assertEquals(12, dto.appliedCount());
        assertEquals(3, dto.skippedCount());
    }

    @Test
    public void shouldSupportEquality() {
        SetViewLabelExpressionResultDto a = new SetViewLabelExpressionResultDto(
                "view-1", "V", "${name}", 1, 0);
        SetViewLabelExpressionResultDto b = new SetViewLabelExpressionResultDto(
                "view-1", "V", "${name}", 1, 0);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void shouldSerializeCountsAsJson() throws Exception {
        SetViewLabelExpressionResultDto dto = new SetViewLabelExpressionResultDto(
                "view-1", "V", "${name}", 12, 3);

        String json = new ObjectMapper().writeValueAsString(dto);

        assertTrue(json.contains("\"appliedCount\":12"));
        assertTrue(json.contains("\"skippedCount\":3"));
    }
}
