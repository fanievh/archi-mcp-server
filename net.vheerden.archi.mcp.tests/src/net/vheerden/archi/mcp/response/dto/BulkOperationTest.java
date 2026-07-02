package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link BulkOperation} DTO validation.
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

    // ---- G1 ----

    @Test
    public void shouldAcceptG1ParamsInCreateRelationshipBulkOp_AC2() {
        BulkOperation op = new BulkOperation("create-relationship",
                Map.of("type", "AccessRelationship",
                        "sourceId", "ba-1",
                        "targetId", "bo-1",
                        "accessType", "read"));
        // BulkOperation.validate only checks tool membership + non-empty params; G1 params
        // ride through unchecked at this layer (validation happens at the prepare boundary).
        op.validate();
        assertEquals("read", op.params().get("accessType"));
    }

    @Test
    public void shouldAcceptG1ParamsInUpdateRelationshipBulkOp_AC3() {
        BulkOperation op = new BulkOperation("update-relationship",
                Map.of("id", "rel-1",
                        "accessType", "readwrite",
                        "associationDirected", Boolean.TRUE,
                        "influenceStrength", "+/-"));
        op.validate();
        assertEquals(Boolean.TRUE, op.params().get("associationDirected"));
        assertEquals("+/-", op.params().get("influenceStrength"));
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
    public void shouldAcceptSetViewLabelExpressionTool() {
        BulkOperation op = new BulkOperation("set-view-label-expression",
                Map.of("viewId", "v1", "labelExpression", "${name}"));
        op.validate();
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("set-view-label-expression"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS_ORDERED.contains("set-view-label-expression"));
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
    public void shouldAcceptCreateSpecializationTool() {
        BulkOperation op = new BulkOperation("create-specialization",
                Map.of("name", "Cloud Server", "conceptType", "Node"));
        op.validate();
    }

    @Test
    public void shouldAcceptUpdateSpecializationTool() {
        BulkOperation op = new BulkOperation("update-specialization",
                Map.of("name", "Old", "conceptType", "Node", "newName", "New"));
        op.validate();
    }

    @Test
    public void shouldAcceptDeleteSpecializationTool() {
        BulkOperation op = new BulkOperation("delete-specialization",
                Map.of("name", "Cloud Server", "conceptType", "Node"));
        op.validate();
    }

    @Test
    public void shouldHaveTwentyEightSupportedTools() {
        // G16: added add-image-to-view (26→27). Then set-view-label-expression (27→28).
        assertEquals(28, BulkOperation.SUPPORTED_TOOLS.size());
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("set-view-label-expression"));
        // add-image-to-view (standalone IDiagramModelImage visuals)
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("add-image-to-view"));
        // specialization profile management tools
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("create-specialization"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-specialization"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("delete-specialization"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("create-element"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("create-relationship"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("create-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-element"));
        // relationship update tool
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-relationship"));
        // view update tool
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-view"));
        // G6: model metadata update tool
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-model"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("add-to-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("add-connection-to-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("remove-from-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-view-object"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-view-connection"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("clear-view"));
        // delete tools
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("delete-element"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("delete-relationship"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("delete-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("delete-folder"));
        // folder mutation tools
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("create-folder"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("update-folder"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("move-to-folder"));
        // visual grouping tools
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("add-group-to-view"));
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("add-note-to-view"));
        // G8: view-reference placement tool
        assertTrue(BulkOperation.SUPPORTED_TOOLS.contains("add-view-reference-to-view"));
    }

    @Test
    public void shouldHaveMaxOperationsOf150() {
        assertEquals(150, BulkOperation.MAX_OPERATIONS);
    }
}
