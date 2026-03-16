package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link ProposalDto} (Story 7-6).
 */
public class ProposalDtoTest {

    @Test
    public void shouldConstructWithAllFields() {
        Map<String, Object> currentState = new LinkedHashMap<>();
        currentState.put("name", "Old Name");
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("name", "New Name");

        ProposalDto dto = new ProposalDto(
                "p-1", "update-element", "pending",
                "Update element: abc123", currentState, changes,
                "Element exists. All changes valid.", "2026-02-24T10:00:00Z");

        assertEquals("p-1", dto.proposalId());
        assertEquals("update-element", dto.tool());
        assertEquals("pending", dto.status());
        assertEquals("Update element: abc123", dto.description());
        assertNotNull(dto.currentState());
        assertEquals("Old Name", dto.currentState().get("name"));
        assertNotNull(dto.proposedChanges());
        assertEquals("New Name", dto.proposedChanges().get("name"));
        assertEquals("Element exists. All changes valid.", dto.validationSummary());
        assertEquals("2026-02-24T10:00:00Z", dto.createdAt());
    }

    @Test
    public void shouldAllowNullCurrentState_forCreateOperations() {
        ProposalDto dto = new ProposalDto(
                "p-2", "create-element", "pending",
                "Create BusinessActor: Node", null,
                Map.of("type", "BusinessActor", "name", "Node"),
                "Type valid.", "2026-02-24T10:01:00Z");

        assertNull(dto.currentState());
        assertNotNull(dto.proposedChanges());
    }

    @Test
    public void shouldAllowRejectedStatus() {
        ProposalDto dto = new ProposalDto(
                "p-3", "create-element", "rejected",
                "Create Node", null, Map.of("name", "Node"),
                "Type valid.", "2026-02-24T10:02:00Z");

        assertEquals("rejected", dto.status());
    }
}
