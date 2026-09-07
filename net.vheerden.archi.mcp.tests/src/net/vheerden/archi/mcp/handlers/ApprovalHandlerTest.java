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
 * Tests for {@link ApprovalHandler} (control plane moved to the human).
 *
 * <p>The handler exposes only the read-only {@code list-pending-approvals}
 * observation tool. The former {@code set-approval-mode} (toggle) and {@code decide-mutation}
 * (approve/reject) MCP tools are removed — the agent cannot move its own gate or approve its own
 * queued change. Approve/reject now lives in
 * {@link net.vheerden.archi.mcp.server.ApprovalService} (see {@code ApprovalServiceTest}).</p>
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

    // ---- Response-envelope documentation pins ----

    @Test
    public void listPendingApprovals_descriptionShouldDocumentApprovalEnvelopeReshape() {
        String desc = registry.getToolSpecifications().stream()
                .filter(s -> "list-pending-approvals".equals(s.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found"))
                .tool().description();
        assertTrue("must state where the result moves under the gate",
                desc.contains("result.preview"));
        assertTrue("must name the proposal sibling", desc.contains("result.proposal"));
        // The proposal holds a deferred rebuild handle, so approving creates a different object.
        assertTrue("must mark a previewed created id as provisional",
                desc.contains("provisional"));
        assertTrue("must give the reason the id changes",
                desc.contains("rebuilt when the human approves"));
        // Three tools do not follow this shape; stating it unconditionally would be false.
        assertTrue("must route the divergent tools to their own descriptions",
                desc.contains("shape this differently"));
    }

    // ---- Surface shape: only the read-only observation tool remains ----

    @Test
    public void shouldRegisterOnlyListPendingApprovalsTool() {
        var names = registry.getToolSpecifications().stream()
                .map(s -> s.tool().name())
                .toList();
        assertEquals(1, names.size());
        assertTrue("list-pending-approvals must remain", names.contains("list-pending-approvals"));
        assertFalse("set-approval-mode must be removed", names.contains("set-approval-mode"));
        assertFalse("decide-mutation must be removed", names.contains("decide-mutation"));
    }

    // ---- list-pending-approvals tests (retained + truthful) ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReturnEmptyList_whenNoProposals() throws Exception {
        McpSchema.CallToolResult result = invokeTool("list-pending-approvals",
                Collections.emptyMap());

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> data = (Map<String, Object>) envelope.get("result");
        assertEquals(0, data.get("pendingCount"));
        List<?> proposals = (List<?>) data.get("proposals");
        assertTrue(proposals.isEmpty());

        assertNoRemovedToolStrings(envelope);
    }

    @Test
    @SuppressWarnings("unchecked")
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

        assertNoRemovedToolStrings(envelope);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReportApprovalMode_fromGlobalProvider() throws Exception {
        // The gate is global now: list-pending-approvals reflects the injected provider, not
        // any per-session flag the agent could have set.
        dispatcher.setApprovalModeProvider(() -> true);
        Map<String, Object> on = (Map<String, Object>) parseJson(
                invokeTool("list-pending-approvals", Collections.emptyMap())).get("result");
        assertEquals(Boolean.TRUE, on.get("approvalMode"));

        dispatcher.setApprovalModeProvider(() -> false);
        Map<String, Object> off = (Map<String, Object>) parseJson(
                invokeTool("list-pending-approvals", Collections.emptyMap())).get("result");
        assertEquals(Boolean.FALSE, off.get("approvalMode"));
    }

    // ---- Helper Methods ----

    /** Asserts the response never tells the agent to call a removed control-plane tool. */
    private void assertNoRemovedToolStrings(Map<String, Object> envelope) {
        String json = envelope.toString();
        assertFalse("response must not reference set-approval-mode", json.contains("set-approval-mode"));
        assertFalse("response must not reference decide-mutation", json.contains("decide-mutation"));
    }

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
