package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

/**
 * Tests for {@link BulkOperationResult} — specifically the optional fan-out counts
 * ({@code appliedCount}/{@code skippedCount}) added for the set-view-label-expression op.
 */
public class BulkOperationResultTest {

    @Test
    public void shouldOmitCounts_whenSingleEntityConstructorUsed() throws Exception {
        BulkOperationResult dto = new BulkOperationResult(
                0, "create-element", "created", "id-1", "ApplicationComponent", "Alpha");

        assertNull(dto.appliedCount());
        assertNull(dto.skippedCount());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("null counts must be omitted", json.contains("appliedCount"));
        assertFalse("null counts must be omitted", json.contains("skippedCount"));
    }

    @Test
    public void shouldCarryCounts_whenFanOutConstructorUsed() throws Exception {
        BulkOperationResult dto = new BulkOperationResult(
                0, "set-view-label-expression", "updated", "view-1", "DiagramModel",
                "Context View", 12, 3);

        assertEquals(Integer.valueOf(12), dto.appliedCount());
        assertEquals(Integer.valueOf(3), dto.skippedCount());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue(json.contains("\"appliedCount\":12"));
        assertTrue(json.contains("\"skippedCount\":3"));
    }
}
