package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.Collections;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * Tests for {@link ApprovalHandler} (Story 7-6).
 */
public class ApprovalHandlerTest {

    private ApprovalHandler handler;
    private CommandRegistry registry;
    private TestMutationDispatcher dispatcher;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        ResponseFormatter formatter = new ResponseFormatter();
        objectMapper = new ObjectMapper();

        dispatcher = new TestMutationDispatcher();
        ApprovalStubAccessor accessor = new ApprovalStubAccessor(dispatcher);

        handler = new ApprovalHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- set-approval-mode tests ----

    @Test
    public void shouldRegisterThreeTools() {
        assertEquals(3, registry.getToolSpecifications().size());
    }

    @Test
    public void shouldEnableApprovalMode() throws Exception {
        McpSchema.CallToolResult result = invokeTool("set-approval-mode",
                Map.of("enabled", true));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> data = (Map<String, Object>) envelope.get("result");
        assertEquals(true, data.get("approvalMode"));
        assertTrue(dispatcher.isApprovalRequired("default"));
    }

    @Test
    public void shouldDisableApprovalMode() throws Exception {
        dispatcher.setApprovalRequired("default", true);

        McpSchema.CallToolResult result = invokeTool("set-approval-mode",
                Map.of("enabled", false));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> data = (Map<String, Object>) envelope.get("result");
        assertEquals(false, data.get("approvalMode"));
        assertFalse(dispatcher.isApprovalRequired("default"));
    }

    @Test
    public void shouldReturnError_whenEnabledMissing() throws Exception {
        McpSchema.CallToolResult result = invokeTool("set-approval-mode",
                Collections.emptyMap());

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- list-pending-approvals tests ----

    @Test
    public void shouldReturnEmptyList_whenNoProposals() throws Exception {
        McpSchema.CallToolResult result = invokeTool("list-pending-approvals",
                Collections.emptyMap());

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> data = (Map<String, Object>) envelope.get("result");
        assertEquals(0, data.get("pendingCount"));
        List<?> proposals = (List<?>) data.get("proposals");
        assertTrue(proposals.isEmpty());
    }

    @Test
    public void shouldReturnProposals_whenPending() throws Exception {
        // Store a proposal via the dispatcher's public API
        dispatcher.storeProposal("default", "create-element", "Create BusinessActor: Test",
                new StubCommand("cmd"), "entityDto", null,
                Map.of("type", "BusinessActor", "name", "Test"),
                "Type valid", Instant.now());

        McpSchema.CallToolResult result = invokeTool("list-pending-approvals",
                Collections.emptyMap());

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> data = (Map<String, Object>) envelope.get("result");
        assertEquals(1, data.get("pendingCount"));
        List<Map<String, Object>> proposals = (List<Map<String, Object>>) data.get("proposals");
        assertEquals(1, proposals.size());
        assertEquals("create-element", proposals.get(0).get("tool"));
        assertEquals("pending", proposals.get(0).get("status"));
    }

    // ---- decide-mutation approve tests ----

    @Test
    public void shouldApproveSingleProposal() throws Exception {
        String id = dispatcher.storeProposal("default", "create-element", "Create Node",
                new StubCommand("approve-cmd"), "nodeDto", null,
                Map.of("type", "Node"), "Valid", Instant.now());

        McpSchema.CallToolResult result = invokeTool("decide-mutation",
                Map.of("proposalId", id, "decision", "approve"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> data = (Map<String, Object>) envelope.get("result");
        assertEquals("approved", data.get("decision"));
        assertEquals(id, data.get("proposalId"));
        assertEquals("nodeDto", data.get("entity"));
        // Command should have been dispatched
        assertEquals(1, dispatcher.dispatchedCommands.size());
    }

    @Test
    public void shouldReturnError_whenApprovingNonExistentProposal() throws Exception {
        McpSchema.CallToolResult result = invokeTool("decide-mutation",
                Map.of("proposalId", "p-999", "decision", "approve"));

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("PROPOSAL_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldReturnError_whenInvalidDecision() throws Exception {
        McpSchema.CallToolResult result = invokeTool("decide-mutation",
                Map.of("proposalId", "p-1", "decision", "maybe"));

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- decide-mutation reject tests ----

    @Test
    public void shouldRejectSingleProposal() throws Exception {
        String id = dispatcher.storeProposal("default", "create-view", "Create view",
                new StubCommand("reject-cmd"), "viewDto", null,
                Map.of("name", "My View"), "Valid", Instant.now());

        McpSchema.CallToolResult result = invokeTool("decide-mutation",
                Map.of("proposalId", id, "decision", "reject", "reason", "Not needed"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> data = (Map<String, Object>) envelope.get("result");
        assertEquals("rejected", data.get("decision"));
        assertEquals(id, data.get("proposalId"));
        assertEquals("Not needed", data.get("reason"));
        // No command should have been dispatched
        assertEquals(0, dispatcher.dispatchedCommands.size());
    }

    @Test
    public void shouldReturnError_whenRejectingNonExistentProposal() throws Exception {
        McpSchema.CallToolResult result = invokeTool("decide-mutation",
                Map.of("proposalId", "p-999", "decision", "reject"));

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("PROPOSAL_NOT_FOUND", error.get("code"));
    }

    // ---- decide-mutation approve-all tests ----

    @Test
    public void shouldApproveAllProposals() throws Exception {
        dispatcher.storeProposal("default", "create-element", "Create A",
                new StubCommand("c1"), "dto1", null, Map.of(), "v", Instant.now());
        dispatcher.storeProposal("default", "create-element", "Create B",
                new StubCommand("c2"), "dto2", null, Map.of(), "v", Instant.now());

        McpSchema.CallToolResult result = invokeTool("decide-mutation",
                Map.of("proposalId", "all", "decision", "approve"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> data = (Map<String, Object>) envelope.get("result");
        assertEquals("approve-all", data.get("decision"));
        assertEquals(2, data.get("totalProcessed"));
        assertEquals(2, dispatcher.dispatchedCommands.size());
    }

    @Test
    public void shouldReturnError_whenApproveAllWithNoProposals() throws Exception {
        McpSchema.CallToolResult result = invokeTool("decide-mutation",
                Map.of("proposalId", "all", "decision", "approve"));

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("APPROVAL_NOT_ACTIVE", error.get("code"));
    }

    // ---- decide-mutation reject-all tests ----

    @Test
    public void shouldRejectAllProposals() throws Exception {
        dispatcher.storeProposal("default", "create-element", "Create A",
                new StubCommand("c1"), "dto1", null, Map.of(), "v", Instant.now());
        dispatcher.storeProposal("default", "create-view", "Create View",
                new StubCommand("c2"), "dto2", null, Map.of(), "v", Instant.now());

        McpSchema.CallToolResult result = invokeTool("decide-mutation",
                Map.of("proposalId", "all", "decision", "reject"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> data = (Map<String, Object>) envelope.get("result");
        assertEquals("reject-all", data.get("decision"));
        assertEquals(2, data.get("totalRejected"));
        assertEquals(0, dispatcher.dispatchedCommands.size());
        // All should be removed
        assertTrue(dispatcher.getPendingProposals("default").isEmpty());
    }

    @Test
    public void shouldReturnError_whenRejectAllWithNoProposals() throws Exception {
        McpSchema.CallToolResult result = invokeTool("decide-mutation",
                Map.of("proposalId", "all", "decision", "reject"));

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("APPROVAL_NOT_ACTIVE", error.get("code"));
    }

    // ---- Helper Methods ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().get(0);
        return objectMapper.readValue(content.text(),
                new TypeReference<Map<String, Object>>() {});
    }

    private McpSchema.CallToolResult invokeTool(String toolName, Map<String, Object> args) {
        var spec = registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
        return spec.callHandler().apply(null, request);
    }

    // ---- Test support classes ----

    /**
     * Stub accessor with a real MutationDispatcher (test subclass).
     */
    private static class ApprovalStubAccessor extends BaseTestAccessor {
        private final MutationDispatcher dispatcher;
        ApprovalStubAccessor(MutationDispatcher dispatcher) {
            super(true);
            this.dispatcher = dispatcher;
        }
        @Override public MutationDispatcher getMutationDispatcher() { return dispatcher; }
        @Override public String getModelVersion() { return "test-v1"; }
        @Override public Optional<String> getCurrentModelName() { return Optional.of("Test Model"); }
        @Override public Optional<String> getCurrentModelId() { return Optional.of("test-id"); }
    }

    /**
     * Test MutationDispatcher that avoids Display.syncExec.
     */
    private static class TestMutationDispatcher extends MutationDispatcher {
        final java.util.List<org.eclipse.gef.commands.Command> dispatchedCommands =
                new java.util.ArrayList<>();

        TestMutationDispatcher() {
            super(() -> null);
        }

        @Override
        protected void dispatchCommand(org.eclipse.gef.commands.Command command)
                throws MutationException {
            dispatchedCommands.add(command);
        }
    }

    /**
     * Minimal Command stub.
     */
    private static class StubCommand extends org.eclipse.gef.commands.Command {
        StubCommand(String label) { super(label); }
        @Override public void execute() { /* no-op */ }
    }
}
