package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link BulkOperation} DTO validation (Story 7-5).
 */
public class BulkOperationTest {

    @Test
    public void shouldAcceptCreateElementTool() {
        BulkOperation op = new BulkOperation("create-element", Map.of("type", "BusinessActor"));
        op.validate(); // should not throw
        assertEquals("create-element", op.tool());
    }

    @Test
    public void shouldAcceptCreateRelationshipTool() {
        BulkOperation op = new BulkOperation("create-relationship",
                Map.of("type", "ServingRelationship"));
        op.validate();
    }

    @Test
    public void shouldAcceptCreateViewTool() {
        BulkOperation op = new BulkOperation("create-view", Map.of("name", "Test View"));
        op.validate();
    }

    @Test
    public void shouldAcceptUpdateElementTool() {
        BulkOperation op = new BulkOperation("update-element", Map.of("id", "abc-123"));
        op.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectUnsupportedTool() {
        BulkOperation op = new BulkOperation("search-elements", Map.of());
        op.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullTool() {
        BulkOperation op = new BulkOperation(null, Map.of());
        op.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlankTool() {
        BulkOperation op = new BulkOperation("   ", Map.of());
        op.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNullParams() {
        BulkOperation op = new BulkOperation("create-element", null);
        op.validate();
    }

    @Test
    public void shouldAcceptAddToViewTool() {
        BulkOperation op = new BulkOperation("add-to-view",
                Map.of("viewId", "v1", "elementId", "e1"));
        op.validate();
    }

    @Test
    public void shouldAcceptAddConnectionToViewTool() {
        BulkOperation op = new BulkOperation("add-connection-to-view",
                Map.of("viewId", "v1", "relationshipId", "r1"));
        op.validate();
    }

    @Test
    public void shouldAcceptRemoveFromViewTool() {
        BulkOperation op = new BulkOperation("remove-from-view",
                Map.of("viewId", "v1", "viewObjectId", "vo1"));
        op.validate();
    }

    @Test
    public void shouldAcceptUpdateViewObjectTool() {
        BulkOperation op = new BulkOperation("update-view-object",
                Map.of("viewObjectId", "vo1"));
        op.validate();
    }

    @Test
    public void shouldAcceptUpdateViewConnectionTool() {
        BulkOperation op = new BulkOperation("update-view-connection",
                Map.of("viewConnectionId", "vc1"));
        op.validate();
    }

    @Test
    public void shouldAcceptClearViewTool() {
        BulkOperation op = new BulkOperation("clear-view",
                Map.of("viewId", "v1"));
        op.validate();
    }

    @Test
    public void shouldAcceptUpdateViewTool() {
        BulkOperation op = new BulkOperation("update-view",
                Map.of("viewId", "v1", "name", "Updated View"));
        op.validate();
    }

    @Test
    public void shouldHaveTwentySupportedTools() {
        assertEquals(20, BulkOperation.SUPPORTED_TOOLS.size());
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("create-element"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("create-relationship"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("create-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-element"));
        // Story 8-7: view update tool
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("add-to-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("add-connection-to-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("remove-from-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-view-object"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-view-connection"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("clear-view"));
        // Story 8-4: delete tools
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("delete-element"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("delete-relationship"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("delete-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("delete-folder"));
        // Story 8-5: folder mutation tools
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("create-folder"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-folder"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("move-to-folder"));
        // Story 8-6: visual grouping tools
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("add-group-to-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("add-note-to-view"));
    }

    @Test
    public void shouldHaveMaxOperationsOf150() {
        assertEquals(150, BulkOperation.MAX_OPERATIONS);
    }
}
