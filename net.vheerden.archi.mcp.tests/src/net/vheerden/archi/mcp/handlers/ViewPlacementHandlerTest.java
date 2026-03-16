package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.StylingParams;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAssessmentSummaryDto;
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.RoutingViolationDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionSpec;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Tests for {@link ViewPlacementHandler} (Story 7-7).
 *
 * <p>Uses a StubViewPlacementAccessor that returns canned DTOs,
 * avoiding EMF/GEF dependencies in handler tests.</p>
 */
public class ViewPlacementHandlerTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubViewPlacementAccessor accessor;
    private ViewPlacementHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        accessor = new StubViewPlacementAccessor();
        handler = new ViewPlacementHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Tool registration ----

    @Test
    public void shouldRegisterSeventeenTools() {
        assertEquals(17, registry.getToolSpecifications().size());
    }

    @Test
    public void shouldRegisterAddToViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "add-to-view".equals(spec.tool().name()));
        assertTrue("add-to-view tool should be registered", found);
    }

    @Test
    public void shouldRegisterAddConnectionToViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "add-connection-to-view".equals(spec.tool().name()));
        assertTrue("add-connection-to-view tool should be registered", found);
    }

    @Test
    public void shouldRegisterUpdateViewObjectTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "update-view-object".equals(spec.tool().name()));
        assertTrue("update-view-object tool should be registered", found);
    }

    @Test
    public void shouldRegisterUpdateViewConnectionTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "update-view-connection".equals(spec.tool().name()));
        assertTrue("update-view-connection tool should be registered", found);
    }

    @Test
    public void shouldRegisterRemoveFromViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "remove-from-view".equals(spec.tool().name()));
        assertTrue("remove-from-view tool should be registered", found);
    }

    @Test
    public void shouldHaveMutationPrefix_inMutationToolDescriptions() {
        registry.getToolSpecifications().stream()
                .filter(spec -> !"assess-layout".equals(spec.tool().name()))
                .forEach(spec -> {
                    assertTrue(spec.tool().name() + " description should start with [Mutation]",
                            spec.tool().description().startsWith("[Mutation]"));
                });
    }

    @Test
    public void shouldRegisterAssessLayoutTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "assess-layout".equals(spec.tool().name()));
        assertTrue("assess-layout tool should be registered", found);
    }

    @Test
    public void shouldRegisterAutoRouteConnectionsTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "auto-route-connections".equals(spec.tool().name()));
        assertTrue("auto-route-connections tool should be registered", found);
    }

    @Test
    public void assessLayout_shouldNotHaveMutationPrefix() {
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "assess-layout".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool()
                .description();
        assertTrue("assess-layout should not start with [Mutation]",
                !desc.startsWith("[Mutation]"));
    }

    // ---- add-to-view tests ----

    @Test
    public void shouldReturnViewObjectDto_whenAddToViewSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1", "x", 100, "y", 200));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        @SuppressWarnings("unchecked")
        Map<String, Object> viewObj = (Map<String, Object>) entity.get("viewObject");
        assertEquals("vo-1", viewObj.get("viewObjectId"));
        assertEquals("e-1", viewObj.get("elementId"));
    }

    @Test
    public void shouldReturnAutoPlacement_whenXYOmitted() throws Exception {
        Map<String, Object> result = callAndParse("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        @SuppressWarnings("unchecked")
        Map<String, Object> viewObj = (Map<String, Object>) entity.get("viewObject");
        // Default auto-placement returns 50, 50
        assertEquals(50, ((Number) viewObj.get("x")).intValue());
        assertEquals(50, ((Number) viewObj.get("y")).intValue());
    }

    @Test
    public void shouldReturnAutoConnections_whenAutoConnectTrue() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            ViewObjectDto vo = new ViewObjectDto("vo-1", eId, "Name", "Type", 50, 50, 120, 55);
            ViewConnectionDto conn = new ViewConnectionDto(
                    "vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);
            return new MutationResult<>(new AddToViewResultDto(vo, List.of(conn)), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("autoConnect", true);
        Map<String, Object> result = callAndParse("add-to-view", args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Object> autoConns = (List<Object>) entity.get("autoConnections");
        assertNotNull(autoConns);
        assertEquals(1, autoConns.size());
    }

    @Test
    public void shouldIncludeCapWarning_whenAutoConnectCapped() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            ViewObjectDto vo = new ViewObjectDto("vo-1", eId, "Name", "Type", 50, 50, 120, 55);
            ViewConnectionDto conn = new ViewConnectionDto(
                    "vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);
            return new MutationResult<>(
                    new AddToViewResultDto(vo, List.of(conn), 5), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("autoConnect", true);
        Map<String, Object> result = callAndParse("add-to-view", args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        boolean hasCapWarning = nextSteps.stream()
                .anyMatch(s -> s.contains("capped at 50") && s.contains("5 additional"));
        assertTrue("Should include cap warning in nextSteps", hasCapWarning);
    }

    @Test
    public void shouldSuggestAutoConnectView_inAddToViewNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1", "x", 100, "y", 200));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Should recommend auto-connect-view",
                nextSteps.stream().anyMatch(s -> s.contains("auto-connect-view")));
        assertTrue("Should mention add-connection-to-view as fallback",
                nextSteps.stream().anyMatch(s -> s.contains("add-connection-to-view")));
    }

    @Test
    public void shouldReturnError_whenViewNotFound() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new ModelAccessException("View not found", ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-to-view",
                Map.of("viewId", "bad", "elementId", "e-1"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void shouldReturnError_whenElementNotFound() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new ModelAccessException("Element not found", ErrorCode.ELEMENT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-to-view",
                Map.of("viewId", "v-1", "elementId", "bad"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("ELEMENT_NOT_FOUND"));
    }

    @Test
    public void shouldReturnError_whenPartialCoordinates() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new ModelAccessException(
                    "Both x and y must be specified together", ErrorCode.INVALID_PARAMETER);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("x", 50);
        // y intentionally omitted

        McpSchema.CallToolResult result = callTool("add-to-view", args);

        assertTrue("Should be error", result.isError());
    }

    @Test
    public void shouldReturnModelNotLoadedError_forAddToView() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-to-view")
                .arguments(Map.of("viewId", "v-1", "elementId", "e-1"))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleAddToView(null, request);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_whenApprovalModeActive() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            ViewObjectDto vo = new ViewObjectDto("vo-1", eId, "Name", "Type", 50, 50, 120, 55);
            ProposalContext ctx = new ProposalContext("prop-1", "Add element to view",
                    Instant.parse("2026-01-01T00:00:00Z"));
            return new MutationResult<>(new AddToViewResultDto(vo, null), null, ctx);
        });

        Map<String, Object> result = callAndParse("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
        assertEquals("prop-1", proposal.get("proposalId"));
    }

    @Test
    public void shouldReturnBatchSeq_whenBatchModeActive() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            ViewObjectDto vo = new ViewObjectDto("vo-1", eId, "Name", "Type", 50, 50, 120, 55);
            return new MutationResult<>(new AddToViewResultDto(vo, null), 3);
        });

        Map<String, Object> result = callAndParse("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> batch = (Map<String, Object>) entity.get("batch");
        assertNotNull(batch);
        assertTrue((Boolean) batch.get("success"));
    }

    // ---- add-group-to-view tests (Story 8-6) ----

    @Test
    public void shouldRegisterAddGroupToViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "add-group-to-view".equals(spec.tool().name()));
        assertTrue("add-group-to-view tool should be registered", found);
    }

    @Test
    public void shouldAddGroupToView() throws Exception {
        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "My Group",
                        "x", 100, "y", 200, "width", 400, "height", 300));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vg-1", entity.get("viewObjectId"));
        assertEquals("My Group", entity.get("label"));
        assertEquals(100, ((Number) entity.get("x")).intValue());
        assertEquals(200, ((Number) entity.get("y")).intValue());
        assertEquals(400, ((Number) entity.get("width")).intValue());
        assertEquals(300, ((Number) entity.get("height")).intValue());
    }

    @Test
    public void shouldAddGroupWithDefaults() throws Exception {
        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Default Group"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals(300, ((Number) entity.get("width")).intValue());
        assertEquals(200, ((Number) entity.get("height")).intValue());
    }

    @Test
    public void shouldReturnNotFound_whenViewMissing_forAddGroup() throws Exception {
        accessor.setAddGroupToViewBehavior((sid, vId, label, x, y, w, h, pvoId) -> {
            throw new ModelAccessException("View not found", ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-group-to-view",
                Map.of("viewId", "nonexistent", "label", "Test"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void shouldReturnModelNotLoaded_forAddGroup() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-group-to-view")
                .arguments(Map.of("viewId", "v-1", "label", "Test"))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleAddGroupToView(null, request);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forAddGroup_whenApprovalActive() throws Exception {
        accessor.setAddGroupToViewBehavior((sid, vId, label, x, y, w, h, pvoId) -> {
            ViewGroupDto dto = new ViewGroupDto("vg-1", label, 50, 50, 300, 200, null, null);
            ProposalContext ctx = new ProposalContext("p-1", "Add group to view", Instant.now());
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Test Group"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
    }

    @Test
    public void shouldReturnBatchSeq_forAddGroup_whenBatchActive() throws Exception {
        accessor.setAddGroupToViewBehavior((sid, vId, label, x, y, w, h, pvoId) -> {
            ViewGroupDto dto = new ViewGroupDto("vg-1", label, 50, 50, 300, 200, null, null);
            return new MutationResult<>(dto, 3);
        });

        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Test Group"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("Should mention batch", nextSteps.get(0).contains("batch"));
    }

    @Test
    public void shouldIncludeGroupNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Channel Apps"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("Should mention parentViewObjectId",
                nextSteps.stream().anyMatch(s -> s.contains("parentViewObjectId")));
    }

    @Test
    public void shouldSuggestLayoutWithinGroup_inAddGroupNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("add-group-to-view",
                Map.of("viewId", "v-1", "label", "Channel Apps"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Should recommend layout-within-group",
                nextSteps.stream().anyMatch(s -> s.contains("layout-within-group")));
    }

    // ---- add-note-to-view tests (Story 8-6) ----

    @Test
    public void shouldRegisterAddNoteToViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "add-note-to-view".equals(spec.tool().name()));
        assertTrue("add-note-to-view tool should be registered", found);
    }

    @Test
    public void shouldAddNoteToView() throws Exception {
        Map<String, Object> result = callAndParse("add-note-to-view",
                Map.of("viewId", "v-1", "content", "Design decision: use REST",
                        "x", 500, "y", 100, "width", 200, "height", 100));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vn-1", entity.get("viewObjectId"));
        assertEquals("Design decision: use REST", entity.get("content"));
        assertEquals(500, ((Number) entity.get("x")).intValue());
        assertEquals(100, ((Number) entity.get("y")).intValue());
        assertEquals(200, ((Number) entity.get("width")).intValue());
        assertEquals(100, ((Number) entity.get("height")).intValue());
    }

    @Test
    public void shouldAddNoteWithDefaults() throws Exception {
        Map<String, Object> result = callAndParse("add-note-to-view",
                Map.of("viewId", "v-1", "content", "A note"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals(185, ((Number) entity.get("width")).intValue());
        assertEquals(80, ((Number) entity.get("height")).intValue());
    }

    @Test
    public void shouldReturnNotFound_whenViewMissing_forAddNote() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, x, y, w, h, pvoId) -> {
            throw new ModelAccessException("View not found", ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-note-to-view",
                Map.of("viewId", "nonexistent", "content", "Test"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void shouldReturnModelNotLoaded_forAddNote() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-note-to-view")
                .arguments(Map.of("viewId", "v-1", "content", "Test"))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleAddNoteToView(null, request);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forAddNote_whenApprovalActive() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, x, y, w, h, pvoId) -> {
            ViewNoteDto dto = new ViewNoteDto("vn-1", content, 50, 50, 185, 80, null);
            ProposalContext ctx = new ProposalContext("p-1", "Add note to view", Instant.now());
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> result = callAndParse("add-note-to-view",
                Map.of("viewId", "v-1", "content", "Test note"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
    }

    @Test
    public void shouldReturnBatchSeq_forAddNote_whenBatchActive() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, x, y, w, h, pvoId) -> {
            ViewNoteDto dto = new ViewNoteDto("vn-1", content, 50, 50, 185, 80, null);
            return new MutationResult<>(dto, 5);
        });

        Map<String, Object> result = callAndParse("add-note-to-view",
                Map.of("viewId", "v-1", "content", "Test note"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("Should mention batch", nextSteps.get(0).contains("batch"));
    }

    @Test
    public void shouldIncludeNoteNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("add-note-to-view",
                Map.of("viewId", "v-1", "content", "Test note"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("Should mention update-view-object",
                nextSteps.stream().anyMatch(s -> s.contains("update-view-object")));
    }

    // ---- add-connection-to-view tests ----

    @Test
    public void shouldReturnConnectionDto_whenAddConnectionSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vc-1", entity.get("viewConnectionId"));
        assertEquals("rel-1", entity.get("relationshipId"));
    }

    @Test
    public void shouldReturnBendpoints_whenProvided() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto(
                    "vc-1", relId, "Serving", src, tgt, bps);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("relationshipId", "rel-1");
        args.put("sourceViewObjectId", "vo-1");
        args.put("targetViewObjectId", "vo-2");
        args.put("bendpoints", List.of(
                Map.of("startX", 10, "startY", 20, "endX", 30, "endY", 40)));

        Map<String, Object> result = callAndParse("add-connection-to-view", args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Object> bps = (List<Object>) entity.get("bendpoints");
        assertNotNull(bps);
        assertEquals(1, bps.size());
    }

    @Test
    public void shouldReturnError_whenRelationshipNotFound() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            throw new ModelAccessException("Not found", ErrorCode.RELATIONSHIP_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "bad",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("RELATIONSHIP_NOT_FOUND"));
    }

    @Test
    public void shouldReturnError_whenViewObjectNotFound() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            throw new ModelAccessException("Not found", ErrorCode.VIEW_OBJECT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "bad", "targetViewObjectId", "vo-2"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_OBJECT_NOT_FOUND"));
    }

    @Test
    public void shouldReturnError_whenRelationshipMismatch() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            throw new ModelAccessException("Mismatch", ErrorCode.RELATIONSHIP_MISMATCH);
        });

        McpSchema.CallToolResult result = callTool("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("RELATIONSHIP_MISMATCH"));
    }

    @Test
    public void shouldReturnError_whenConnectionAlreadyOnView() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            throw new ModelAccessException("Already exists", ErrorCode.CONNECTION_ALREADY_ON_VIEW);
        });

        McpSchema.CallToolResult result = callTool("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("CONNECTION_ALREADY_ON_VIEW"));
    }

    @Test
    public void shouldReturnModelNotLoadedError_forAddConnection() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-connection-to-view")
                .arguments(Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleAddConnectionToView(null, request);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forAddConnection_whenApprovalActive() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto("vc-1", relId, "Serving", src, tgt, null);
            ProposalContext ctx = new ProposalContext("prop-2", "Add connection",
                    Instant.parse("2026-01-01T00:00:00Z"));
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> result = callAndParse("add-connection-to-view",
                Map.of("viewId", "v-1", "relationshipId", "rel-1",
                        "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
        assertEquals("prop-2", proposal.get("proposalId"));
    }

    @Test
    public void shouldReturnMutationError_whenDispatchFails() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new MutationException("Dispatch failed");
        });

        McpSchema.CallToolResult result = callTool("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MUTATION_FAILED"));
    }

    @Test
    public void shouldReturnInternalError_whenUnexpectedExceptionOccurs() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new RuntimeException("Unexpected");
        });

        McpSchema.CallToolResult result = callTool("add-to-view",
                Map.of("viewId", "v-1", "elementId", "e-1"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INTERNAL_ERROR"));
    }

    // ---- update-view-object tests (Story 7-8) ----

    @Test
    public void shouldReturnUpdatedDto_whenUpdateViewObjectSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("update-view-object",
                Map.of("viewObjectId", "vo-1", "x", 200, "y", 100, "width", 150, "height", 70));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vo-1", entity.get("viewObjectId"));
        assertEquals(200, ((Number) entity.get("x")).intValue());
        assertEquals(100, ((Number) entity.get("y")).intValue());
    }

    @Test
    public void shouldReturnPartialUpdate_whenOnlyXProvided() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("x", 300);
        Map<String, Object> result = callAndParse("update-view-object", args);

        Map<String, Object> entity = getResult(result);
        assertEquals(300, ((Number) entity.get("x")).intValue());
    }

    @Test
    public void shouldReturnError_whenNoFieldsProvided_forUpdateViewObject() throws Exception {
        accessor.setUpdateViewObjectBehavior((sid, voId, x, y, w, h, txt) -> {
            throw new ModelAccessException(
                    "At least one of x, y, width, height must be provided",
                    ErrorCode.INVALID_PARAMETER);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");

        McpSchema.CallToolResult result = callTool("update-view-object", args);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void shouldReturnError_whenViewObjectNotFound_forUpdate() throws Exception {
        accessor.setUpdateViewObjectBehavior((sid, voId, x, y, w, h, txt) -> {
            throw new ModelAccessException("Not found", ErrorCode.VIEW_OBJECT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("update-view-object",
                Map.of("viewObjectId", "bad", "x", 50));
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_OBJECT_NOT_FOUND"));
    }

    @Test
    public void shouldReturnModelNotLoadedError_forUpdateViewObject() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("update-view-object")
                .arguments(Map.of("viewObjectId", "vo-1", "x", 50))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleUpdateViewObject(null, request);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forUpdateViewObject_whenApprovalActive() throws Exception {
        accessor.setUpdateViewObjectBehavior((sid, voId, x, y, w, h, txt) -> {
            ViewObjectDto dto = new ViewObjectDto(voId, "e-1", "Name", "Type", 200, 100, 150, 70);
            ProposalContext ctx = new ProposalContext("prop-3", "Update bounds",
                    Instant.parse("2026-01-01T00:00:00Z"));
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> result = callAndParse("update-view-object",
                Map.of("viewObjectId", "vo-1", "x", 200));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
        assertEquals("prop-3", proposal.get("proposalId"));
    }

    @Test
    public void shouldReturnBatchSeq_forUpdateViewObject_whenBatchActive() throws Exception {
        accessor.setUpdateViewObjectBehavior((sid, voId, x, y, w, h, txt) -> {
            ViewObjectDto dto = new ViewObjectDto(voId, "e-1", "Name", "Type", 200, 100, 150, 70);
            return new MutationResult<>(dto, 5);
        });

        Map<String, Object> result = callAndParse("update-view-object",
                Map.of("viewObjectId", "vo-1", "x", 200));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> batch = (Map<String, Object>) entity.get("batch");
        assertNotNull(batch);
        assertTrue((Boolean) batch.get("success"));
    }

    // ---- Story 11-2: Styling parameter tests ----

    @Test
    public void shouldPassStylingParams_whenUpdateViewObjectWithStylingOnly() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewObjectId", "vo-1");
        args.put("fillColor", "#FF0000");

        Map<String, Object> result = callAndParse("update-view-object", args);
        Map<String, Object> entity = getResult(result);
        assertNotNull("Styling-only update should succeed", entity);
        assertEquals("vo-1", entity.get("viewObjectId"));

        // Verify styling params were correctly extracted and passed to accessor
        StylingParams captured = ((StubViewPlacementAccessor) accessor).lastUpdateViewObjectStyling;
        assertNotNull("Styling params should be captured", captured);
        assertEquals("#FF0000", captured.fillColor());
        assertNull("lineColor should be null when not provided", captured.lineColor());
    }

    @Test
    public void shouldIncludeStylingProperties_inUpdateViewObjectSpec() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "update-view-object".equals(s.tool().name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        assertTrue("Should have fillColor property", props.containsKey("fillColor"));
        assertTrue("Should have lineColor property", props.containsKey("lineColor"));
        assertTrue("Should have fontColor property", props.containsKey("fontColor"));
        assertTrue("Should have opacity property", props.containsKey("opacity"));
        assertTrue("Should have lineWidth property", props.containsKey("lineWidth"));
    }

    @Test
    public void shouldIncludeStylingProperties_inAddToViewSpec() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "add-to-view".equals(s.tool().name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        assertTrue("Should have fillColor property", props.containsKey("fillColor"));
        assertTrue("Should have lineColor property", props.containsKey("lineColor"));
        assertTrue("Should have fontColor property", props.containsKey("fontColor"));
    }

    @Test
    public void shouldIncludeConnectionStylingProperties_inUpdateViewConnectionSpec() {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> "update-view-connection".equals(s.tool().name()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) spec.tool().inputSchema().properties();
        assertTrue("Should have lineColor property", props.containsKey("lineColor"));
        assertTrue("Should have fontColor property", props.containsKey("fontColor"));
        assertTrue("Should have lineWidth property", props.containsKey("lineWidth"));
        assertFalse("Should NOT have fillColor property", props.containsKey("fillColor"));
        assertFalse("Should NOT have opacity property", props.containsKey("opacity"));
    }

    @Test
    public void shouldPassStylingParams_whenAddToViewWithStyling() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("fillColor", "#00FF00");
        args.put("opacity", 128);

        Map<String, Object> result = callAndParse("add-to-view", args);
        Map<String, Object> entity = getResult(result);
        assertNotNull("add-to-view with styling should succeed", entity);

        // Verify styling params were correctly extracted and passed to accessor
        StylingParams captured = ((StubViewPlacementAccessor) accessor).lastAddToViewStyling;
        assertNotNull("Styling params should be captured", captured);
        assertEquals("#00FF00", captured.fillColor());
        assertEquals(Integer.valueOf(128), captured.opacity());
        assertNull("lineColor should be null when not provided", captured.lineColor());
    }

    @Test
    public void shouldPassStylingParams_whenUpdateViewConnectionWithStyling() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "conn-1");
        args.put("lineColor", "#0000FF");
        args.put("lineWidth", 2);

        Map<String, Object> result = callAndParse("update-view-connection", args);
        Map<String, Object> entity = getResult(result);
        assertNotNull("update-view-connection with styling should succeed", entity);

        // Verify styling params were correctly extracted and passed to accessor
        StylingParams captured = ((StubViewPlacementAccessor) accessor).lastUpdateViewConnectionStyling;
        assertNotNull("Connection styling params should be captured", captured);
        assertEquals("#0000FF", captured.lineColor());
        assertEquals(Integer.valueOf(2), captured.lineWidth());
        assertNull("fillColor should be null for connections", captured.fillColor());
    }

    // ---- update-view-connection tests (Story 7-8) ----

    @Test
    public void shouldReturnUpdatedDto_whenUpdateConnectionSucceeds() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("bendpoints", List.of(
                Map.of("startX", 60, "startY", 0, "endX", -60, "endY", 0)));

        Map<String, Object> result = callAndParse("update-view-connection", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vc-1", entity.get("viewConnectionId"));
    }

    @Test
    public void shouldClearBendpoints_whenEmptyArrayProvided() throws Exception {
        accessor.setUpdateViewConnectionBehavior((sid, vcId, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto(
                    vcId, "rel-1", "Serving", "vo-1", "vo-2", List.of());
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("bendpoints", List.of());

        Map<String, Object> result = callAndParse("update-view-connection", args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Object> bps = (List<Object>) entity.get("bendpoints");
        assertNotNull(bps);
        assertEquals(0, bps.size());
    }

    @Test
    public void shouldReturnError_whenConnectionNotFound_forUpdate() throws Exception {
        accessor.setUpdateViewConnectionBehavior((sid, vcId, bps, absBps) -> {
            throw new ModelAccessException("Not found", ErrorCode.VIEW_OBJECT_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "bad");
        args.put("bendpoints", List.of());

        McpSchema.CallToolResult result = callTool("update-view-connection", args);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_OBJECT_NOT_FOUND"));
    }

    @Test
    public void shouldReturnModelNotLoadedError_forUpdateConnection() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("bendpoints", List.of());

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("update-view-connection")
                .arguments(args)
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleUpdateViewConnection(null, request);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forUpdateConnection_whenApprovalActive() throws Exception {
        accessor.setUpdateViewConnectionBehavior((sid, vcId, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto(
                    vcId, "rel-1", "Serving", "vo-1", "vo-2", bps);
            ProposalContext ctx = new ProposalContext("prop-4", "Update bendpoints",
                    Instant.parse("2026-01-01T00:00:00Z"));
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("bendpoints", List.of());

        Map<String, Object> result = callAndParse("update-view-connection", args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
        assertEquals("prop-4", proposal.get("proposalId"));
    }

    // ---- absolute bendpoints tests (Story 8-0d) ----

    @Test
    public void shouldAcceptAbsoluteBendpoints_forAddConnection() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            assertNull("relative bendpoints should be null when absolute provided", bps);
            assertNotNull("absolute bendpoints should be forwarded", absBps);
            assertEquals(1, absBps.size());
            assertEquals(300, absBps.get(0).x());
            assertEquals(150, absBps.get(0).y());
            ViewConnectionDto dto = new ViewConnectionDto(
                    "vc-1", relId, "Serving", src, tgt, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("relationshipId", "rel-1");
        args.put("sourceViewObjectId", "vo-1");
        args.put("targetViewObjectId", "vo-2");
        args.put("absoluteBendpoints", List.of(
                Map.of("x", 300, "y", 150)));

        Map<String, Object> result = callAndParse("add-connection-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vc-1", entity.get("viewConnectionId"));
    }

    @Test
    public void shouldAcceptAbsoluteBendpoints_forUpdateConnection() throws Exception {
        accessor.setUpdateViewConnectionBehavior((sid, vcId, bps, absBps) -> {
            assertNull("relative bendpoints should be null when absolute provided", bps);
            assertNotNull("absolute bendpoints should be forwarded", absBps);
            assertEquals(1, absBps.size());
            assertEquals(300, absBps.get(0).x());
            assertEquals(150, absBps.get(0).y());
            ViewConnectionDto dto = new ViewConnectionDto(
                    vcId, "rel-1", "Serving", "vo-1", "vo-2", null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("absoluteBendpoints", List.of(
                Map.of("x", 300, "y", 150)));

        Map<String, Object> result = callAndParse("update-view-connection", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vc-1", entity.get("viewConnectionId"));
    }

    @Test
    public void shouldRejectBothFormats_forAddConnection() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("relationshipId", "rel-1");
        args.put("sourceViewObjectId", "vo-1");
        args.put("targetViewObjectId", "vo-2");
        args.put("bendpoints", List.of(
                Map.of("startX", 10, "startY", 20, "endX", 30, "endY", 40)));
        args.put("absoluteBendpoints", List.of(
                Map.of("x", 300, "y", 150)));

        McpSchema.CallToolResult result = callTool("add-connection-to-view", args);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void shouldRejectBothFormats_forUpdateConnection() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");
        args.put("bendpoints", List.of(
                Map.of("startX", 10, "startY", 20, "endX", 30, "endY", 40)));
        args.put("absoluteBendpoints", List.of(
                Map.of("x", 300, "y", 150)));

        McpSchema.CallToolResult result = callTool("update-view-connection", args);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void shouldClearBendpoints_whenNeitherFormatProvided() throws Exception {
        accessor.setUpdateViewConnectionBehavior((sid, vcId, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto(
                    vcId, "rel-1", "Serving", "vo-1", "vo-2", List.of());
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewConnectionId", "vc-1");

        Map<String, Object> result = callAndParse("update-view-connection", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
    }

    @Test
    public void shouldStillAcceptRelativeBendpoints_forBackwardsCompat() throws Exception {
        accessor.setAddConnectionBehavior((sid, vId, relId, src, tgt, bps, absBps) -> {
            ViewConnectionDto dto = new ViewConnectionDto(
                    "vc-1", relId, "Serving", src, tgt, bps);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("relationshipId", "rel-1");
        args.put("sourceViewObjectId", "vo-1");
        args.put("targetViewObjectId", "vo-2");
        args.put("bendpoints", List.of(
                Map.of("startX", 10, "startY", 20, "endX", 30, "endY", 40)));

        Map<String, Object> result = callAndParse("add-connection-to-view", args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<Object> bps = (List<Object>) entity.get("bendpoints");
        assertNotNull(bps);
        assertEquals(1, bps.size());
    }

    // ---- remove-from-view tests (Story 7-8) ----

    @Test
    public void shouldReturnDto_whenRemoveElementSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "vo-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vo-1", entity.get("removedObjectId"));
        assertEquals("viewObject", entity.get("removedObjectType"));
    }

    @Test
    public void shouldReturnCascadeIds_whenRemovingElementWithConnections() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    voId, "viewObject", List.of("vc-1", "vc-2"));
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> result = callAndParse("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "vo-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        List<String> cascadeIds = (List<String>) entity.get("cascadeRemovedConnectionIds");
        assertNotNull(cascadeIds);
        assertEquals(2, cascadeIds.size());
    }

    @Test
    public void shouldReturnDto_whenRemoveConnectionSucceeds() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    voId, "viewConnection", null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> result = callAndParse("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "vc-1"));

        Map<String, Object> entity = getResult(result);
        assertEquals("viewConnection", entity.get("removedObjectType"));
        assertNull(entity.get("cascadeRemovedConnectionIds"));
    }

    @Test
    public void shouldReturnError_whenViewNotFound_forRemove() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            throw new ModelAccessException("View not found", ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("remove-from-view",
                Map.of("viewId", "bad", "viewObjectId", "vo-1"));
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void shouldReturnError_whenViewObjectNotFound_forRemove() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            throw new ModelAccessException("Not found", ErrorCode.VIEW_OBJECT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "bad"));
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_OBJECT_NOT_FOUND"));
    }

    @Test
    public void shouldReturnModelNotLoadedError_forRemoveFromView() throws Exception {
        StubViewPlacementAccessor noModel = new StubViewPlacementAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("remove-from-view")
                .arguments(Map.of("viewId", "v-1", "viewObjectId", "vo-1"))
                .build();

        McpSchema.CallToolResult result = noModelHandler.handleRemoveFromView(null, request);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    @Test
    public void shouldReturnProposal_forRemoveFromView_whenApprovalActive() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    voId, "viewObject", null);
            ProposalContext ctx = new ProposalContext("prop-5", "Remove from view",
                    Instant.parse("2026-01-01T00:00:00Z"));
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> result = callAndParse("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "vo-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertNotNull(proposal);
        assertEquals("prop-5", proposal.get("proposalId"));
    }

    @Test
    public void shouldReturnBatchSeq_forRemoveFromView_whenBatchActive() throws Exception {
        accessor.setRemoveFromViewBehavior((sid, vId, voId) -> {
            RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                    voId, "viewObject", null);
            return new MutationResult<>(dto, 7);
        });

        Map<String, Object> result = callAndParse("remove-from-view",
                Map.of("viewId", "v-1", "viewObjectId", "vo-1"));

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> batch = (Map<String, Object>) entity.get("batch");
        assertNotNull(batch);
        assertTrue((Boolean) batch.get("success"));
    }

    // ---- clear-view tests (Story 8-0c) ----

    @Test
    public void shouldReturnDto_whenClearViewSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("clear-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals("Test View", entity.get("viewName"));
        assertEquals(3, entity.get("elementsRemoved"));
        assertEquals(1, entity.get("connectionsRemoved"));
    }

    @Test
    public void shouldReturnSuccessForEmptyView() throws Exception {
        accessor.setClearViewBehavior((sid, vId) -> {
            ClearViewResultDto dto = new ClearViewResultDto(vId, "Empty View", 0, 0, 0);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> result = callAndParse("clear-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertEquals(0, entity.get("elementsRemoved"));
        assertEquals(0, entity.get("connectionsRemoved"));
    }

    @Test
    public void shouldRequireViewId_forClearView() throws Exception {
        McpSchema.CallToolResult result = callTool("clear-view", Map.of());
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void shouldHandleModelAccessException_forClearView() throws Exception {
        accessor.setClearViewBehavior((sid, vId) -> {
            throw new ModelAccessException("View not found", ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("clear-view",
                Map.of("viewId", "bad"));
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    // ---- apply-positions tests (Story 9-0a, renamed 11-8) ----

    @Test
    public void applyViewLayout_shouldParsePositionsAndCallAccessor() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("positions", List.of(
                Map.of("viewObjectId", "vo-1", "x", 100, "y", 200),
                Map.of("viewObjectId", "vo-2", "x", 300, "y", 200, "width", 150, "height", 70)));

        Map<String, Object> result = callAndParse("apply-positions", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals(2, ((Number) entity.get("positionsUpdated")).intValue());
        assertEquals(0, ((Number) entity.get("connectionsUpdated")).intValue());
        assertEquals(2, ((Number) entity.get("totalOperations")).intValue());
    }

    @Test
    public void applyViewLayout_shouldParseConnectionsWithAbsoluteBendpoints() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("connections", List.of(
                Map.of("viewConnectionId", "vc-1",
                        "absoluteBendpoints", List.of(
                                Map.of("x", 150, "y", 100),
                                Map.of("x", 250, "y", 100)))));

        Map<String, Object> result = callAndParse("apply-positions", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals(0, ((Number) entity.get("positionsUpdated")).intValue());
        assertEquals(1, ((Number) entity.get("connectionsUpdated")).intValue());
    }

    @Test
    public void applyViewLayout_shouldParseConnectionsWithRelativeBendpoints() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("connections", List.of(
                Map.of("viewConnectionId", "vc-1",
                        "bendpoints", List.of(
                                Map.of("startX", 0, "startY", -50, "endX", 0, "endY", -50)))));

        Map<String, Object> result = callAndParse("apply-positions", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals(1, ((Number) entity.get("connectionsUpdated")).intValue());
    }

    @Test
    public void applyViewLayout_shouldRequireViewId() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("positions", List.of(Map.of("viewObjectId", "vo-1", "x", 100)));

        McpSchema.CallToolResult result = callTool("apply-positions", args);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void applyViewLayout_shouldIncludeNextSteps() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("positions", List.of(Map.of("viewObjectId", "vo-1", "x", 100, "y", 200)));

        Map<String, Object> result = callAndParse("apply-positions", args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-view-contents")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("export-view")));
    }

    @Test
    public void applyViewLayout_shouldRejectMutuallyExclusiveBendpoints() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("connections", List.of(
                Map.of("viewConnectionId", "vc-1",
                        "bendpoints", List.of(
                                Map.of("startX", 0, "startY", 0, "endX", 0, "endY", 0)),
                        "absoluteBendpoints", List.of(
                                Map.of("x", 100, "y", 100)))));

        McpSchema.CallToolResult result = callTool("apply-positions", args);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void applyViewLayout_shouldHandleApprovalMode() throws Exception {
        accessor.setApplyViewLayoutBehavior((sid, vId, pos, conns, desc) -> {
            int posCount = (pos != null) ? pos.size() : 0;
            int connCount = (conns != null) ? conns.size() : 0;
            ApplyViewLayoutResultDto dto = new ApplyViewLayoutResultDto(
                    vId, posCount, connCount, posCount + connCount);
            ProposalContext ctx = new ProposalContext(
                    "p-layout-1", "View layout ready for application.", Instant.now());
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("positions", List.of(Map.of("viewObjectId", "vo-1", "x", 100, "y", 200)));

        Map<String, Object> result = callAndParse("apply-positions", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal info", entity.get("proposal"));
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-layout-1", proposal.get("proposalId"));
        assertEquals("pending", proposal.get("status"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("decide-mutation")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
    }

    @Test
    public void applyViewLayout_shouldHandleBatchMode() throws Exception {
        accessor.setApplyViewLayoutBehavior((sid, vId, pos, conns, desc) -> {
            int posCount = (pos != null) ? pos.size() : 0;
            int connCount = (conns != null) ? conns.size() : 0;
            ApplyViewLayoutResultDto dto = new ApplyViewLayoutResultDto(
                    vId, posCount, connCount, posCount + connCount);
            return new MutationResult<>(dto, 5);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("positions", List.of(Map.of("viewObjectId", "vo-1", "x", 100, "y", 200)));

        Map<String, Object> result = callAndParse("apply-positions", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have batch info", entity.get("batch"));
    }

    // ---- compute-layout (Story 9-1, renamed 11-8) ----

    @Test
    public void shouldRegisterLayoutViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "compute-layout".equals(spec.tool().name()));
        assertTrue("compute-layout tool should be registered", found);
    }

    @Test
    public void layoutView_shouldParseAlgorithmAndCallAccessor() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("algorithm", "tree");

        Map<String, Object> result = callAndParse("compute-layout", args);

        Map<String, Object> entity = getResult(result);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals("tree", entity.get("algorithmUsed"));
    }

    @Test
    public void layoutView_shouldParsePresetAndCallAccessor() throws Exception {
        accessor.setLayoutViewBehavior((sid, vId, algo, preset, opts) -> {
            LayoutViewResultDto dto = new LayoutViewResultDto(
                    vId, "grid", preset, 4, 2, 6);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("preset", "compact");

        Map<String, Object> result = callAndParse("compute-layout", args);

        Map<String, Object> entity = getResult(result);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals("compact", entity.get("presetUsed"));
        assertEquals("grid", entity.get("algorithmUsed"));
    }

    @Test
    public void layoutView_shouldPassSpacingOption() throws Exception {
        accessor.setLayoutViewBehavior((sid, vId, algo, preset, opts) -> {
            assertNotNull("Options should not be null", opts);
            assertEquals("Spacing should be 80", 80, opts.get("spacing"));
            LayoutViewResultDto dto = new LayoutViewResultDto(
                    vId, algo, preset, 3, 1, 4);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("algorithm", "tree");
        args.put("spacing", 80);

        callAndParse("compute-layout", args);
    }

    @Test
    public void layoutView_shouldRequireViewId() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("algorithm", "tree");

        McpSchema.CallToolResult result = callTool("compute-layout", args);

        assertTrue("Should be error", result.isError());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void layoutView_shouldIncludeNextSteps() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("algorithm", "tree");

        Map<String, Object> result = callAndParse("compute-layout", args);

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull("Should have nextSteps", nextSteps);
        assertTrue("Should suggest export-view",
                nextSteps.stream().anyMatch(s -> s.contains("export-view")));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void layoutView_shouldHandleApprovalMode() throws Exception {
        accessor.setLayoutViewBehavior((sid, vId, algo, preset, opts) -> {
            LayoutViewResultDto dto = new LayoutViewResultDto(
                    vId, algo, preset, 6, 4, 10);
            ProposalContext ctx = new ProposalContext(
                    "p-compute-layout-1", "View layout computed and ready for application.", Instant.now());
            return new MutationResult<>(dto, null, ctx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("algorithm", "tree");

        Map<String, Object> result = callAndParse("compute-layout", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal info", entity.get("proposal"));
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-compute-layout-1", proposal.get("proposalId"));
        assertEquals("pending", proposal.get("status"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("decide-mutation")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
    }

    @Test
    public void layoutView_shouldHandleBatchMode() throws Exception {
        accessor.setLayoutViewBehavior((sid, vId, algo, preset, opts) -> {
            LayoutViewResultDto dto = new LayoutViewResultDto(
                    vId, algo, preset, 5, 3, 8);
            return new MutationResult<>(dto, 7);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("algorithm", "grid");

        Map<String, Object> result = callAndParse("compute-layout", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have batch info", entity.get("batch"));
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args)
            throws Exception {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(args)
                .build();

        return switch (toolName) {
            case "add-to-view" -> handler.handleAddToView(null, request);
            case "add-group-to-view" -> handler.handleAddGroupToView(null, request);
            case "add-note-to-view" -> handler.handleAddNoteToView(null, request);
            case "add-connection-to-view" -> handler.handleAddConnectionToView(null, request);
            case "update-view-object" -> handler.handleUpdateViewObject(null, request);
            case "update-view-connection" -> handler.handleUpdateViewConnection(null, request);
            case "remove-from-view" -> handler.handleRemoveFromView(null, request);
            case "clear-view" -> handler.handleClearView(null, request);
            case "apply-positions" -> handler.handleApplyViewLayout(null, request);
            case "compute-layout" -> handler.handleLayoutView(null, request);
            case "assess-layout" -> handler.handleAssessLayout(null, request);
            case "auto-route-connections" -> handler.handleAutoRouteConnections(null, request);
            case "auto-connect-view" -> handler.handleAutoConnectView(null, request);
            case "layout-within-group" -> handler.handleLayoutWithinGroup(null, request);
            case "auto-layout-and-route" -> handler.handleAutoLayoutAndRoute(null, request);
            case "arrange-groups" -> handler.handleArrangeGroups(null, request);
            case "optimize-group-order" -> handler.handleOptimizeGroupOrder(null, request);
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }

    private Map<String, Object> callAndParse(String toolName, Map<String, Object> args)
            throws Exception {
        McpSchema.CallToolResult result = callTool(toolName, args);
        return parseResult(result);
    }

    private Map<String, Object> parseResult(McpSchema.CallToolResult result) throws Exception {
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        return objectMapper.readValue(content, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getResult(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("result");
    }

    // ---- assess-layout (Story 9-2) ----

    @Test
    public void assessLayout_shouldParseViewIdAndCallAccessor() throws Exception {
        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals(5, entity.get("elementCount"));
        assertEquals(3, entity.get("connectionCount"));
        assertEquals("good", entity.get("overallRating"));
    }

    @Test
    public void assessLayout_shouldRequireViewId() throws Exception {
        McpSchema.CallToolResult result = callTool("assess-layout",
                new HashMap<>());

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void assessLayout_shouldIncludeNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("export-view")));
    }

    @Test
    public void assessLayout_shouldIncludeLayoutSuggestionForPoorRating() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 10, 5, 4, 0, 15, 3.0, 8.0, 20, "poor", null,
                List.of("Element 'a' overlaps with element 'b'"),
                null, null, null, 0, null, 0, null, 0, null, false, 0, null,
                List.of("Found 4 overlapping element pairs — use auto-layout-and-route")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        // Story 11-22: poor rating suggests auto-layout-and-route (no compute-layout)
        assertTrue("Should suggest auto-layout-and-route for poor rating",
                nextSteps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        assertFalse("Should NOT mention compute-layout (Story 11-22)",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_shouldFormatAsReadOnlyResponse() throws Exception {
        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        // Read-only response should NOT have mutation-specific fields
        assertNull(result.get("batched"));
        assertNull(result.get("proposal"));
    }

    @Test
    public void assessLayout_shouldIncludeContainmentOverlapsInResponse() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 2, 0, 5, 1, 0.5, 40.0, 65, "good", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, null,
                List.of("Layout quality is good — no immediate improvements needed.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("result");
        assertEquals(0, ((Number) data.get("overlapCount")).intValue());
        assertEquals(5, ((Number) data.get("containmentOverlaps")).intValue());
    }

    @Test
    public void assessLayout_shouldIncludeRatingBreakdownInResponse() throws Exception {
        // Story 11-19 review: verify ratingBreakdown serializes through handler→formatter→JSON
        Map<String, String> breakdown = new java.util.LinkedHashMap<>();
        breakdown.put("overlaps", "pass");
        breakdown.put("edgeCrossings", "good");
        breakdown.put("spacing", "pass");
        breakdown.put("alignment", "pass");
        breakdown.put("labelOverlaps", "pass");
        breakdown.put("passThroughs", "pass");
        breakdown.put("overall", "good");
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 0, 0, 12, 3.0, 45.0, 70, "good", breakdown,
                null, null, null, null, 0, null, 0, null, 0, null, true, 0, null,
                List.of("Layout quality is good — no immediate improvements needed.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("result");
        @SuppressWarnings("unchecked")
        Map<String, String> returnedBreakdown = (Map<String, String>) data.get("ratingBreakdown");
        assertNotNull("ratingBreakdown should be present in JSON response", returnedBreakdown);
        assertEquals("good", returnedBreakdown.get("overall"));
        assertEquals("pass", returnedBreakdown.get("overlaps"));
        assertEquals("good", returnedBreakdown.get("edgeCrossings"));
        assertEquals(7, returnedBreakdown.size());
    }

    @Test
    public void assessLayout_shouldReturnErrorOnViewNotFound() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> {
            throw new ModelAccessException("View not found: " + vId,
                    ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("assess-layout",
                Map.of("viewId", "bad-id"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    // ---- Story 10-14: orphan detection in assess-layout ----

    @Test
    public void assessLayout_shouldReportOrphanedConnections() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 5, 3, 0, 0, 0, 0.0, 50.0, 80, "good", null,
                null, null, null, null, 0, null, 2,
                List.of("Connection 'c-1' references missing view object(s): source=obj-x target=obj-y"),
                0, null, false, 0, null,
                List.of("Layout quality is good — no immediate improvements needed.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("result");
        assertEquals(2, ((Number) data.get("orphanedConnections")).intValue());
        @SuppressWarnings("unchecked")
        List<String> orphanDescs = (List<String>) data.get("orphanedConnectionDescriptions");
        assertNotNull(orphanDescs);
        assertEquals(1, orphanDescs.size());
        assertTrue(orphanDescs.get(0).contains("missing view object"));
    }

    @Test
    public void assessLayout_shouldOmitOrphanFieldsWhenZero() throws Exception {
        // Default behavior has 0 orphans and null descriptions
        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("result");
        // orphanedConnections is 0 (int, always present)
        assertEquals(0, ((Number) data.get("orphanedConnections")).intValue());
        // orphanedConnectionDescriptions should be null/absent (NON_NULL)
        assertNull(data.get("orphanedConnectionDescriptions"));
    }

    @Test
    public void assessLayout_shouldSuggestClearViewForOrphans() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 5, 3, 0, 0, 0, 0.0, 50.0, 80, "good", null,
                null, null, null, null, 0, null, 3,
                List.of("Connection 'c-1' references missing view object(s)"),
                0, null, false, 0, null,
                List.of("Layout quality is good.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Should suggest clear-view for orphans",
                nextSteps.stream().anyMatch(s -> s.contains("orphaned") && s.contains("clear-view")));
    }

    // ---- Story 11-17: context-aware graduated nextSteps ----

    @Test
    public void assessLayout_excellentRating_shouldOnlyRecommendExportView() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 0, 0, 0, 0.0, 80.0, 90, "excellent", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, null,
                List.of("No issues detected.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals("Excellent should only have export-view step", 1, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("export-view"));
    }

    @Test
    public void assessLayout_goodWithEdgeCrossings_shouldRecommendAutoRoute() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 0, 0, 5, 1.25, 60.0, 80, "good", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, null,
                List.of("Some crossings.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Good + crossings should recommend auto-route-connections",
                nextSteps.stream().anyMatch(s -> s.contains("auto-route-connections")));
        assertFalse("Good rating should NOT recommend compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_goodWithSpacingIssuesAndGroups_shouldRecommendLayoutWithinGroup() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 10, 3, 2, 0, 0, 0.0, 25.0, 60, "good", null,
                List.of("overlap1", "overlap2"),
                null, null, null, 0, null, 0, null, 0, null, true, 0, null,
                List.of("Use layout-within-group.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Grouped view should recommend layout-within-group",
                nextSteps.stream().anyMatch(s -> s.contains("layout-within-group")));
        assertFalse("Good + grouped should NOT recommend compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_fairRating_shouldRecommendAutoLayoutAndRoute() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 12, 6, 3, 0, 8, 1.33, 30.0, 50, "fair", null,
                List.of("overlap"), null, null, null, 0, null, 0, null, 0, null, false, 0, null,
                List.of("Multiple issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Fair should recommend auto-layout-and-route",
                nextSteps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        assertTrue("Fair should mention auto-route-connections as lighter alternative",
                nextSteps.stream().anyMatch(s -> s.contains("auto-route-connections")));
        assertFalse("Fair should NOT recommend compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_poorRating_shouldRecommendAutoLayoutNoLayoutView() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 15, 8, 6, 0, 20, 2.5, 15.0, 30, "poor", null,
                List.of("many overlaps"), null, null, null, 0, null, 0, null, 0, null, false, 0, null,
                List.of("Major issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Poor should recommend auto-layout-and-route",
                nextSteps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        assertFalse("Poor should NOT mention compute-layout (Story 11-22)",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_poorWithGroups_shouldNotRecommendLayoutView() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 15, 8, 6, 0, 20, 2.5, 15.0, 30, "poor", null,
                List.of("many overlaps"), null, null, null, 0, null, 0, null, 0, null, true, 0, null,
                List.of("Major issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Poor + grouped should recommend auto-layout-and-route",
                nextSteps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        assertFalse("Poor + grouped should NOT recommend compute-layout (Story 11-22)",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_orphanedConnections_shouldPreserveClearViewGuidance() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 0, 0, 0, 0.0, 80.0, 90, "excellent", null,
                null, null, null, null, 0, null, 2,
                List.of("orphan1"), 0, null, false, 0, null,
                List.of("No issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Orphaned connections should still recommend clear-view",
                nextSteps.stream().anyMatch(s -> s.contains("orphaned") && s.contains("clear-view")));
    }

    @Test
    public void assessLayout_goodWithSpacingIssuesFlat_shouldRecommendApplyViewLayout() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 10, 3, 2, 0, 0, 0.0, 25.0, 60, "good", null,
                List.of("overlap1", "overlap2"),
                null, null, null, 0, null, 0, null, 0, null, false, 0, null,
                List.of("Spacing is tight.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Flat view should recommend apply-positions",
                nextSteps.stream().anyMatch(s -> s.contains("apply-positions")));
        assertFalse("Good + flat should NOT recommend compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_goodWithNoIssues_shouldOnlyRecommendExportView() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 8, 4, 0, 0, 0, 0.0, 80.0, 90, "good", null,
                null, null, null, null, 0, null, 0, null, 0, null, false, 0, null,
                List.of("No issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals("Good with no issues should only have export-view", 1, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("export-view"));
    }

    @Test
    public void assessLayout_fairWithGroups_shouldNotRecommendLayoutView() throws Exception {
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 12, 6, 3, 0, 8, 1.33, 30.0, 50, "fair", null,
                List.of("overlap"), null, null, null, 0, null, 0, null, 0, null, true, 0, null,
                List.of("Multiple issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Fair + grouped should recommend auto-layout-and-route",
                nextSteps.stream().anyMatch(s -> s.contains("auto-layout-and-route")));
        assertFalse("Fair + grouped should NOT recommend compute-layout",
                nextSteps.stream().anyMatch(s -> s.contains("compute-layout")));
    }

    @Test
    public void assessLayout_anyRating_shouldAlwaysEndWithExportView() throws Exception {
        // Test with fair rating (has multiple steps)
        accessor.setAssessLayoutBehavior(vId -> new AssessLayoutResultDto(
                vId, 12, 6, 3, 0, 8, 1.33, 30.0, 50, "fair", null,
                List.of("overlap"), null, null, null, 0, null, 0, null, 0, null, false, 0, null,
                List.of("Issues.")));

        Map<String, Object> result = callAndParse("assess-layout",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Last step should always be export-view",
                nextSteps.get(nextSteps.size() - 1).contains("export-view"));
    }

    // ---- auto-route-connections tests ----

    @Test
    public void autoRoute_shouldRouteAllConnections_defaultStrategy() throws Exception {
        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
        assertEquals("v-1", data.get("viewId"));
        assertEquals(5, ((Number) data.get("connectionsRouted")).intValue());
        assertEquals("orthogonal", data.get("strategy"));
    }

    @Test
    public void autoRoute_shouldReturnErrorWhenViewIdMissing() throws Exception {
        McpSchema.CallToolResult result = callTool("auto-route-connections",
                Map.of());

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("viewId"));
    }

    @Test
    public void autoRoute_shouldUseClearStrategy() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 3, "clear", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "strategy", "clear"));

        Map<String, Object> data = getResult(result);
        assertEquals("clear", data.get("strategy"));
        assertEquals(3, ((Number) data.get("connectionsRouted")).intValue());
    }

    @Test
    public void autoRoute_shouldPassConnectionIdsFilter() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) -> {
            int count = (connIds != null) ? connIds.size() : 0;
            return new MutationResult<>(new AutoRouteResultDto(vId, count, "orthogonal", false), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds", List.of("c-1", "c-2")));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsRouted")).intValue());
    }

    @Test
    public void autoRoute_shouldReturnErrorOnInvalidStrategy() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) -> {
            throw new ModelAccessException("Invalid strategy: 'bogus'. Valid: orthogonal, clear",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("auto-route-connections",
                Map.of("viewId", "v-1", "strategy", "bogus"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void autoRoute_shouldReturnProposalInApprovalMode() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(null, null, new ProposalContext("p-99",
                        "Auto-route connections on view " + vId,
                        Instant.parse("2026-03-04T00:00:00Z"))));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-99", proposal.get("proposalId"));
    }

    // ---- auto-route Story 10-11: routerTypeSwitched ----

    @Test
    public void autoRoute_shouldIncludeRouterTypeSwitchedTrue() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 10, "orthogonal", true), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals(true, data.get("routerTypeSwitched"));
        // nextSteps should mention the switch
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should mention router type switch",
                nextSteps.stream().anyMatch(s -> s.contains("bendpoint mode")));
    }

    @Test
    public void autoRoute_shouldIncludeRouterTypeSwitchedFalse() throws Exception {
        // Explicitly set up a scenario where routerTypeSwitched is false
        // (view already in bendpoint mode — no switch needed)
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals(false, data.get("routerTypeSwitched"));
    }

    @Test
    public void autoRoute_clearStrategy_shouldNotSwitchRouterType() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 3, "clear", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "strategy", "clear"));

        Map<String, Object> data = getResult(result);
        assertEquals(false, data.get("routerTypeSwitched"));
    }

    // ---- auto-route Story 10-21: selective routing & partial success ----

    @Test
    public void autoRoute_shouldRouteOnlySpecifiedConnections_preservingOthers() throws Exception {
        // When connectionIds are specified, only those connections should be routed
        // The count should reflect only the specified connections
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) -> {
            assertNotNull("connectionIds should be passed through", connIds);
            assertEquals(2, connIds.size());
            assertEquals("c-1", connIds.get(0));
            assertEquals("c-3", connIds.get(1));
            return new MutationResult<>(new AutoRouteResultDto(vId, 2, "orthogonal", false), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds", List.of("c-1", "c-3")));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsRouted")).intValue());
    }

    @Test
    public void autoRoute_shouldRouteAllConnections_whenConnectionIdsOmitted() throws Exception {
        // When connectionIds is omitted, all connections should be routed (backward compat)
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) -> {
            assertNull("connectionIds should be null when omitted", connIds);
            return new MutationResult<>(new AutoRouteResultDto(vId, 20, "orthogonal", false), null);
        });

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals(20, ((Number) data.get("connectionsRouted")).intValue());
    }

    @Test
    public void autoRoute_shouldIncludeWarningsForInvalidIds() throws Exception {
        // Partial success: valid connections routed, invalid IDs reported as warnings
        List<String> testWarnings = List.of(
                "Connection not found on view: bad-id-1",
                "Connection not found on view: bad-id-2");
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 1, "orthogonal", false, testWarnings), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds",
                        List.of("c-valid", "bad-id-1", "bad-id-2")));

        Map<String, Object> data = getResult(result);
        assertEquals(1, ((Number) data.get("connectionsRouted")).intValue());
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) data.get("warnings");
        assertNotNull("Should have warnings", warnings);
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("bad-id-1"));
        assertTrue(warnings.get(1).contains("bad-id-2"));
    }

    @Test
    public void autoRoute_shouldReturnError_whenAllConnectionIdsInvalid() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) -> {
            throw new ModelAccessException(
                    "None of the specified connection IDs were found on the view",
                    ErrorCode.ELEMENT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds", List.of("bad-1", "bad-2")));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("ELEMENT_NOT_FOUND"));
        assertTrue(content.contains("None of the specified connection IDs"));
    }

    @Test
    public void autoRoute_shouldOmitWarningsWhenEmpty() throws Exception {
        // When no warnings, the field should be absent from JSON (NON_EMPTY)
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertNull("warnings should be absent when empty", data.get("warnings"));
    }

    @Test
    public void autoRoute_shouldIncludeWarningsNextStep() throws Exception {
        List<String> testWarnings = List.of("Connection not found on view: bad-id");
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 2, "orthogonal", false, testWarnings), null));

        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1", "connectionIds", List.of("c-1", "c-2", "bad-id")));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should mention warnings",
                nextSteps.stream().anyMatch(s -> s.contains("warnings")));
    }

    @Test
    public void autoRoute_shouldMentionSelectiveRoutingInNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should mention selective routing with connectionIds",
                nextSteps.stream().anyMatch(s -> s.contains("connectionIds")));
    }

    // ---- auto-route Story 10-32: force mode ----

    @Test
    public void autoRoute_shouldDefaultForceToFalse() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) -> {
            assertFalse("force should default to false", force);
            return new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null);
        });
        callAndParse("auto-route-connections", Map.of("viewId", "v-1"));
    }

    @Test
    public void autoRoute_shouldPassForceTrueToAccessor() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) -> {
            assertTrue("force should be true", force);
            return new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null);
        });
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("force", true);
        callAndParse("auto-route-connections", args);
    }

    @Test
    public void autoRoute_shouldIncludeViolationsInForceMode() throws Exception {
        List<RoutingViolationDto> violations = List.of(
                new RoutingViolationDto("c-1", "Src", "Tgt", "element_crossing", "warning"));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 5, 0, "orthogonal", false,
                        List.of(), List.of(), List.of(), violations), null));
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("force", true);
        Map<String, Object> result = callAndParse("auto-route-connections", args);
        Map<String, Object> data = getResult(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violationList = (List<Map<String, Object>>) data.get("violations");
        assertNotNull("violations should be present", violationList);
        assertEquals(1, violationList.size());
        assertEquals("element_crossing", violationList.get(0).get("constraintViolated"));
        assertEquals("warning", violationList.get(0).get("severity"));
    }

    @Test
    public void autoRoute_shouldIncludeViolationNextSteps_whenForceMode() throws Exception {
        List<RoutingViolationDto> violations = List.of(
                new RoutingViolationDto("c-1", "Src", "Tgt", "element_crossing", "warning"));
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(new AutoRouteResultDto(
                        vId, 5, 0, "orthogonal", false,
                        List.of(), List.of(), List.of(), violations), null));
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("force", true);
        Map<String, Object> result = callAndParse("auto-route-connections", args);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("nextSteps should mention constraint violations",
                nextSteps.stream().anyMatch(s -> s.contains("constraint violation")));
    }

    @Test
    public void autoRoute_shouldOmitViolationsInDefaultMode() throws Exception {
        accessor.setAutoRouteConnectionsBehavior((sid, vId, connIds, strategy, force) ->
                new MutationResult<>(new AutoRouteResultDto(vId, 5, "orthogonal", false), null));
        Map<String, Object> result = callAndParse("auto-route-connections",
                Map.of("viewId", "v-1"));
        Map<String, Object> data = getResult(result);
        assertNull("violations should be absent in default mode", data.get("violations"));
    }

    // ---- auto-layout-and-route (Story 10-29, targetRating Story 11-16) ----

    @Test
    public void autoLayoutAndRoute_shouldReturnResultWithoutTargetRating() throws Exception {
        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals("v-1", data.get("viewId"));
        assertEquals("DOWN", data.get("direction"));
        assertEquals(50, ((Number) data.get("spacing")).intValue());
        assertEquals(5, ((Number) data.get("elementsRepositioned")).intValue());
        assertEquals(3, ((Number) data.get("connectionsRouted")).intValue());
        // targetRating fields should be absent (null → omitted by @JsonInclude)
        assertNull("targetRating should be absent", data.get("targetRating"));
        assertNull("achievedRating should be absent", data.get("achievedRating"));
        assertNull("iterationsPerformed should be absent", data.get("iterationsPerformed"));
        assertNull("assessmentSummary should be absent", data.get("assessmentSummary"));
    }

    @Test
    public void autoLayoutAndRoute_shouldRejectInvalidTargetRating() throws Exception {
        McpSchema.CallToolResult result = callTool("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "poor"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
        assertTrue(content.contains("targetRating"));
    }

    @Test
    public void autoLayoutAndRoute_shouldRejectNotApplicableTargetRating() throws Exception {
        McpSchema.CallToolResult result = callTool("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "not-applicable"));

        assertTrue("Should be error", result.isError());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldIncludeQualityFieldsWhenTargetRatingUsed() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8,
                        tr, "good", 2,
                        new AutoLayoutAssessmentSummaryDto(
                                0, 5, 45.5, 70, "good",
                                List.of("No improvements needed."))), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        Map<String, Object> data = getResult(result);
        assertEquals("good", data.get("targetRating"));
        assertEquals("good", data.get("achievedRating"));
        assertEquals(2, ((Number) data.get("iterationsPerformed")).intValue());
        Map<String, Object> summary = (Map<String, Object>) data.get("assessmentSummary");
        assertNotNull("assessmentSummary should be present", summary);
        assertEquals(0, ((Number) summary.get("overlapCount")).intValue());
        assertEquals(5, ((Number) summary.get("edgeCrossingCount")).intValue());
    }

    @Test
    public void autoLayoutAndRoute_shouldOmitAssessLayoutFromNextStepsWhenTargetRatingUsed() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8,
                        tr, "good", 1,
                        new AutoLayoutAssessmentSummaryDto(
                                0, 2, 50.0, 80, "good", null)), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        boolean hasAssessLayout = nextSteps.stream()
                .anyMatch(s -> s.contains("assess-layout"));
        assertFalse("Should NOT suggest assess-layout when targetRating used", hasAssessLayout);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldIncludeTargetMissGuidanceInNextSteps() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8,
                        tr, "fair", 5,
                        new AutoLayoutAssessmentSummaryDto(
                                1, 15, 30.0, 50, "fair",
                                List.of("Increase spacing."))), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "good"));

        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        boolean hasTargetMiss = nextSteps.stream()
                .anyMatch(s -> s.contains("not achieved") && s.contains("fair"));
        assertTrue("Should include target miss guidance", hasTargetMiss);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void autoLayoutAndRoute_shouldReturnResultWithTargetMetOnFirstIteration() throws Exception {
        accessor.setAutoLayoutAndRouteBehavior((sid, vId, dir, sp, tr) ->
                new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, "DOWN", sp, 5, 3, false, 8,
                        tr, "good", 1,
                        new AutoLayoutAssessmentSummaryDto(
                                0, 3, 50.0, 75, "good", null)), null));

        Map<String, Object> result = callAndParse("auto-layout-and-route",
                Map.of("viewId", "v-1", "targetRating", "fair"));

        Map<String, Object> data = getResult(result);
        assertEquals("fair", data.get("targetRating"));
        assertEquals("good", data.get("achievedRating"));
        assertEquals(1, ((Number) data.get("iterationsPerformed")).intValue());

        // When target is exceeded on first iteration, nextSteps should NOT contain
        // target miss guidance and should NOT suggest assess-layout
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        boolean hasTargetMiss = nextSteps.stream()
                .anyMatch(s -> s.contains("not achieved"));
        assertFalse("Should NOT include target miss guidance when target exceeded", hasTargetMiss);
        boolean hasAssessLayout = nextSteps.stream()
                .anyMatch(s -> s.contains("assess-layout"));
        assertFalse("Should NOT suggest assess-layout when targetRating used", hasAssessLayout);
    }

    // ---- auto-connect-view (Story 9-6) ----

    @Test
    public void shouldRegisterAutoConnectViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "auto-connect-view".equals(spec.tool().name()));
        assertTrue("auto-connect-view tool should be registered", found);
    }

    @Test
    public void autoConnect_shouldConnectAllRelationships() throws Exception {
        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
        assertEquals("v-1", data.get("viewId"));
        assertEquals(3, ((Number) data.get("connectionsCreated")).intValue());
        assertEquals(1, ((Number) data.get("connectionsSkipped")).intValue());
        @SuppressWarnings("unchecked")
        List<String> relIds = (List<String>) data.get("relationshipIdsConnected");
        assertEquals(3, relIds.size());
    }

    @Test
    public void autoConnect_shouldReturnErrorWhenViewIdMissing() throws Exception {
        McpSchema.CallToolResult result = callTool("auto-connect-view",
                Map.of());

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("viewId"));
    }

    @Test
    public void autoConnect_shouldPassElementIdsFilter() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes) -> {
            int count = (elemIds != null) ? elemIds.size() : 0;
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, count, 0, List.of("r-1")), null);
        });

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1", "elementIds", List.of("e-1", "e-2")));

        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("connectionsCreated")).intValue());
    }

    @Test
    public void autoConnect_shouldPassRelationshipTypesFilter() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes) -> {
            int count = (relTypes != null) ? relTypes.size() : 0;
            return new MutationResult<>(new AutoConnectResultDto(
                    vId, count, 0, List.of("r-1")), null);
        });

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1", "relationshipTypes",
                        List.of("ServingRelationship")));

        Map<String, Object> data = getResult(result);
        assertEquals(1, ((Number) data.get("connectionsCreated")).intValue());
    }

    @Test
    public void autoConnect_shouldReturnZeroWhenNoConnections() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes) ->
                new MutationResult<>(new AutoConnectResultDto(
                        vId, 0, 0, List.of()), null));

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> data = getResult(result);
        assertEquals(0, ((Number) data.get("connectionsCreated")).intValue());
        assertEquals(0, ((Number) data.get("connectionsSkipped")).intValue());
    }

    @Test
    public void autoConnect_shouldReturnProposalInApprovalMode() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes) ->
                new MutationResult<>(null, null, new ProposalContext("p-42",
                        "Auto-connect view " + vId,
                        Instant.parse("2026-03-04T00:00:00Z"))));

        Map<String, Object> result = callAndParse("auto-connect-view",
                Map.of("viewId", "v-1"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-42", proposal.get("proposalId"));
    }

    @Test
    public void autoConnect_shouldReturnErrorOnInvalidRelationshipType() throws Exception {
        accessor.setAutoConnectViewBehavior((sid, vId, elemIds, relTypes) -> {
            throw new ModelAccessException(
                    "Invalid ArchiMate relationship type: BogusRelationship",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("auto-connect-view",
                Map.of("viewId", "v-1", "relationshipTypes",
                        List.of("BogusRelationship")));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    // ---- layout-within-group (Story 9-9) ----

    @Test
    public void shouldRegisterLayoutWithinGroupTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "layout-within-group".equals(spec.tool().name()));
        assertTrue("layout-within-group tool should be registered", found);
    }

    @Test
    public void layoutWithinGroup_shouldParseRowArrangementAndCallAccessor() throws Exception {
        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
        assertEquals("v-1", data.get("viewId"));
        assertEquals("g-1", data.get("groupViewObjectId"));
        assertEquals("row", data.get("arrangement"));
        assertEquals(4, ((Number) data.get("elementsRepositioned")).intValue());
    }

    @Test
    public void layoutWithinGroup_shouldParseColumnArrangement() throws Exception {
        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "column"));

        Map<String, Object> data = getResult(result);
        assertEquals("column", data.get("arrangement"));
    }

    @Test
    public void layoutWithinGroup_shouldParseGridArrangement() throws Exception {
        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "grid"));

        Map<String, Object> data = getResult(result);
        assertEquals("grid", data.get("arrangement"));
    }

    @Test
    public void layoutWithinGroup_shouldRequireViewId() throws Exception {
        McpSchema.CallToolResult result = callTool("layout-within-group",
                Map.of("groupViewObjectId", "g-1", "arrangement", "row"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("viewId"));
    }

    @Test
    public void layoutWithinGroup_shouldRequireGroupViewObjectId() throws Exception {
        McpSchema.CallToolResult result = callTool("layout-within-group",
                Map.of("viewId", "v-1", "arrangement", "row"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("groupViewObjectId"));
    }

    @Test
    public void layoutWithinGroup_shouldRequireArrangement() throws Exception {
        McpSchema.CallToolResult result = callTool("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("arrangement"));
    }

    @Test
    public void layoutWithinGroup_shouldRejectInvalidArrangement() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            throw new ModelAccessException(
                    "Invalid arrangement: 'bogus'. Valid values: row, column, grid.",
                    ErrorCode.INVALID_PARAMETER);
        });

        McpSchema.CallToolResult result = callTool("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "bogus"));

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void layoutWithinGroup_shouldPassOptionalParams() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            // Verify optional params were passed through
            assertEquals(Integer.valueOf(15), sp);
            assertEquals(Integer.valueOf(5), pad);
            assertEquals(Integer.valueOf(120), ew);
            assertEquals(Integer.valueOf(55), eh);
            assertTrue(ar);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 3, true, 300, 200, false, false, null, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "row");
        args.put("spacing", 15);
        args.put("padding", 5);
        args.put("elementWidth", 120);
        args.put("elementHeight", 55);
        args.put("autoResize", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(true, data.get("groupResized"));
        assertEquals(300, ((Number) data.get("newGroupWidth")).intValue());
        assertEquals(200, ((Number) data.get("newGroupHeight")).intValue());
    }

    @Test
    public void layoutWithinGroup_shouldHandleApprovalMode() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) ->
                new MutationResult<>(null, null, new ProposalContext("p-50",
                        "Layout within group " + gvoId,
                        Instant.parse("2026-03-04T00:00:00Z"))));

        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-50", proposal.get("proposalId"));
    }

    @Test
    public void layoutWithinGroup_shouldHandleBatchMode() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) ->
                new MutationResult<>(new LayoutWithinGroupResultDto(
                        vId, gvoId, arr, 4, false, null, null, false, false, null, 0), 7));

        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);

        // Check nextSteps mentions batch
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue("Should mention batch",
                nextSteps.stream().anyMatch(s -> s.contains("batch")));
    }

    @Test
    public void layoutWithinGroup_shouldIncludeNextSteps() throws Exception {
        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "row"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull("Should have nextSteps", nextSteps);
        assertTrue("Should mention export-view",
                nextSteps.stream().anyMatch(s -> s.contains("export-view")));
    }

    // ---- Story 11-14: autoWidth tests ----

    @Test
    public void layoutWithinGroup_shouldParseAutoWidthParam() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            assertTrue("autoWidth should be true", aw);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, true, null, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "row");
        args.put("autoWidth", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(true, data.get("autoWidth"));
    }

    @Test
    public void layoutWithinGroup_shouldPassAutoWidthWithElementWidthOverride() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            // Handler passes both; accessor decides precedence
            assertEquals(Integer.valueOf(150), ew);
            assertTrue("autoWidth should be true from handler", aw);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, false, null, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "row");
        args.put("elementWidth", 150);
        args.put("autoWidth", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        assertNotNull(result);
    }

    @Test
    public void layoutWithinGroup_shouldReportAutoWidthInResponse() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            assertTrue("autoWidth should be passed as true from handler", aw);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, aw, null, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "column");
        args.put("autoWidth", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(true, data.get("autoWidth"));
    }

    // ---- Story 11-18: columns + recursive tests ----

    @Test
    public void layoutWithinGroup_shouldPassColumnsParam() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            assertEquals(Integer.valueOf(4), cols);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 12, false, null, null, false, false, 4, 0), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "grid");
        args.put("columns", 4);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(4, ((Number) data.get("columnsUsed")).intValue());
    }

    @Test
    public void layoutWithinGroup_shouldPassRecursiveParam() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            assertTrue("recursive should be true", rec);
            assertTrue("autoResize should be true", ar);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, true, 300, 200, false, false, null, 2), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "row");
        args.put("autoResize", true);
        args.put("recursive", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(2, ((Number) data.get("ancestorsResized")).intValue());
    }

    @Test
    public void layoutWithinGroup_shouldDefaultColumnsToNull() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) -> {
            assertNull("columns should be null by default", cols);
            assertFalse("recursive should be false by default", rec);
            return new MutationResult<>(new LayoutWithinGroupResultDto(
                    vId, gvoId, arr, 4, false, null, null, false, false, null, 0), null);
        });

        Map<String, Object> result = callAndParse("layout-within-group",
                Map.of("viewId", "v-1", "groupViewObjectId", "g-1", "arrangement", "grid"));

        Map<String, Object> data = getResult(result);
        assertNotNull(data);
    }

    @Test
    public void layoutWithinGroup_shouldReportAncestorsResizedInResponse() throws Exception {
        accessor.setLayoutWithinGroupBehavior((sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) ->
                new MutationResult<>(new LayoutWithinGroupResultDto(
                        vId, gvoId, arr, 4, true, 300, 200, false, false, null, 3), null));

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("groupViewObjectId", "g-1");
        args.put("arrangement", "column");
        args.put("autoResize", true);
        args.put("recursive", true);

        Map<String, Object> result = callAndParse("layout-within-group", args);
        Map<String, Object> data = getResult(result);
        assertEquals(3, ((Number) data.get("ancestorsResized")).intValue());
        assertEquals(true, data.get("groupResized"));
    }

    // ---- Story 10-20: Element-to-element nesting tests ----

    @Test
    public void shouldAddToView_withElementParent() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            assertEquals("vo-parent", pvoId);
            ViewObjectDto vo = new ViewObjectDto(
                    "vo-child", eId, "Child Element", "ApplicationFunction", 30, 30, 120, 55);
            return new MutationResult<>(new AddToViewResultDto(vo, null), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-child");
        args.put("x", 30);
        args.put("y", 30);
        args.put("parentViewObjectId", "vo-parent");
        Map<String, Object> result = callAndParse("add-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        @SuppressWarnings("unchecked")
        Map<String, Object> viewObject = (Map<String, Object>) entity.get("viewObject");
        assertNotNull(viewObject);
        assertEquals("vo-child", viewObject.get("viewObjectId"));
    }

    @Test
    public void shouldReturnError_whenParentIsNote() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new ModelAccessException(
                    "Parent view object must be a group or element: " + pvoId,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "parentViewObjectId must reference a group or element view object, not a DiagramModelNote",
                    null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("parentViewObjectId", "note-1");
        McpSchema.CallToolResult result = callTool("add-to-view", args);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("Should mention INVALID_PARAMETER", content.contains("INVALID_PARAMETER"));
        assertTrue("Should mention group or element", content.contains("group or element"));
    }

    @Test
    public void shouldReturnError_whenParentIsConnection() throws Exception {
        accessor.setAddToViewBehavior((sid, vId, eId, x, y, w, h, ac, pvoId) -> {
            throw new ModelAccessException(
                    "Parent view object must be a group or element: " + pvoId,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "parentViewObjectId must reference a group or element view object, not a DiagramModelConnection",
                    null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("elementId", "e-1");
        args.put("parentViewObjectId", "conn-1");
        McpSchema.CallToolResult result = callTool("add-to-view", args);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("Should mention INVALID_PARAMETER", content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void shouldAddGroupToView_withElementParent() throws Exception {
        accessor.setAddGroupToViewBehavior((sid, vId, label, x, y, w, h, pvoId) -> {
            assertEquals("vo-element-parent", pvoId);
            ViewGroupDto dto = new ViewGroupDto(
                    "vg-1", label, 30, 30, 300, 200, "vo-element-parent", null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("label", "Nested Group");
        args.put("x", 30);
        args.put("y", 30);
        args.put("parentViewObjectId", "vo-element-parent");
        Map<String, Object> result = callAndParse("add-group-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vo-element-parent", entity.get("parentViewObjectId"));
    }

    @Test
    public void shouldAddNoteToView_withElementParent() throws Exception {
        accessor.setAddNoteToViewBehavior((sid, vId, content, x, y, w, h, pvoId) -> {
            assertEquals("vo-element-parent", pvoId);
            ViewNoteDto dto = new ViewNoteDto(
                    "vn-1", content, 30, 30, 185, 80, "vo-element-parent");
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("content", "Nested Note");
        args.put("x", 30);
        args.put("y", 30);
        args.put("parentViewObjectId", "vo-element-parent");
        Map<String, Object> result = callAndParse("add-note-to-view", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("vo-element-parent", entity.get("parentViewObjectId"));
    }

    // ---- arrange-groups tests (Story 11-20) ----

    @Test
    public void shouldRegisterArrangeGroupsTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "arrange-groups".equals(spec.tool().name()));
        assertTrue("arrange-groups tool should be registered", found);
    }

    @Test
    public void arrangeGroups_shouldReturnResult() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "grid");
        args.put("columns", 3);
        args.put("spacing", 80);
        Map<String, Object> result = callAndParse("arrange-groups", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull(entity);
        assertEquals("v-1", entity.get("viewId"));
        assertEquals(6, entity.get("groupsPositioned"));
        assertEquals(800, entity.get("layoutWidth"));
        assertEquals(600, entity.get("layoutHeight"));
        assertEquals(3, entity.get("columnsUsed"));
        assertEquals("grid", entity.get("arrangement"));
    }

    @Test
    public void arrangeGroups_viewNotFound_shouldReturnError() throws Exception {
        accessor.setArrangeGroupsBehavior((sid, vId, arr, cols, sp, gids) -> {
            throw new ModelAccessException("View not found: " + vId, ErrorCode.VIEW_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "nonexistent");
        args.put("arrangement", "row");
        McpSchema.CallToolResult result = callTool("arrange-groups", args);

        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void arrangeGroupsNextSteps_shouldIncludeLayoutWithinGroupGuidance() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "row");
        Map<String, Object> result = callAndParse("arrange-groups", args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Should mention layout-within-group",
                nextSteps.stream().anyMatch(s -> s.contains("layout-within-group")));
        assertTrue("Should mention auto-route-connections",
                nextSteps.stream().anyMatch(s -> s.contains("auto-route-connections")));
    }

    @Test
    public void arrangeGroups_shouldRequireViewId() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("arrangement", "grid");
        McpSchema.CallToolResult result = callTool("arrange-groups", args);
        assertTrue("Should be error for missing viewId", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void arrangeGroups_shouldRequireArrangement() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        McpSchema.CallToolResult result = callTool("arrange-groups", args);
        assertTrue("Should be error for missing arrangement", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    // ---- optimize-group-order (Story 11-25) ----

    @Test
    public void shouldRegisterOptimizeGroupOrderTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "optimize-group-order".equals(spec.tool().name()));
        assertTrue("optimize-group-order tool should be registered", found);
    }

    @Test
    public void optimizeGroupOrder_shouldReturnResult() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "row");

        Map<String, Object> result = callAndParse("optimize-group-order", args);
        Map<String, Object> entity = getResult(result);

        assertEquals("v-1", entity.get("viewId"));
        assertNotNull(entity.get("crossingsBefore"));
        assertNotNull(entity.get("crossingsAfter"));
        assertNotNull(entity.get("reductionPercent"));
    }

    @Test
    public void optimizeGroupOrder_shouldRequireViewId() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("arrangement", "row");
        McpSchema.CallToolResult result = callTool("optimize-group-order", args);
        assertTrue("Should be error for missing viewId", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void optimizeGroupOrder_shouldRequireArrangement() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        McpSchema.CallToolResult result = callTool("optimize-group-order", args);
        assertTrue("Should be error for missing arrangement", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    @Test
    public void optimizeGroupOrder_viewNotFound_shouldReturnError() throws Exception {
        accessor.setOptimizeGroupOrderBehavior((sid, vId, arr, sp, pad, ew, eh, aw, cols) -> {
            throw new ModelAccessException("View not found: " + vId, ErrorCode.VIEW_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "nonexistent");
        args.put("arrangement", "row");

        McpSchema.CallToolResult result = callTool("optimize-group-order", args);
        assertTrue("Should be error", result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void optimizeGroupOrder_nextStepsShouldIncludeAutoRouteGuidance() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-1");
        args.put("arrangement", "row");

        Map<String, Object> result = callAndParse("optimize-group-order", args);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull("nextSteps should not be null", nextSteps);
        assertTrue("nextSteps should mention auto-route-connections",
                nextSteps.stream().anyMatch(s -> s.contains("auto-route-connections")));
    }

    // ---- Stubs ----

    @FunctionalInterface
    interface ArrangeGroupsBehavior {
        MutationResult<ArrangeGroupsResultDto> apply(String sessionId, String viewId,
                String arrangement, Integer columns, Integer spacing, List<String> groupIds);
    }

    @FunctionalInterface
    interface OptimizeGroupOrderBehavior {
        MutationResult<OptimizeGroupOrderResultDto> apply(String sessionId, String viewId,
                String arrangement, Integer spacing, Integer padding,
                Integer elementWidth, Integer elementHeight, boolean autoWidth, Integer columns);
    }

    interface LayoutWithinGroupBehavior {
        MutationResult<LayoutWithinGroupResultDto> apply(String sessionId, String viewId,
                String groupViewObjectId, String arrangement, Integer spacing,
                Integer padding, Integer elementWidth, Integer elementHeight,
                boolean autoResize, boolean autoWidth, Integer columns, boolean recursive);
    }

    @FunctionalInterface
    interface AutoConnectViewBehavior {
        MutationResult<AutoConnectResultDto> apply(String sessionId, String viewId,
                List<String> elementIds, List<String> relationshipTypes);
    }

    @FunctionalInterface
    interface AutoLayoutAndRouteBehavior {
        MutationResult<AutoLayoutAndRouteResultDto> apply(String sessionId, String viewId,
                String direction, int spacing, String targetRating);
    }

    @FunctionalInterface
    interface AutoRouteConnectionsBehavior {
        MutationResult<AutoRouteResultDto> apply(String sessionId, String viewId,
                List<String> connectionIds, String strategy, boolean force);
    }

    @FunctionalInterface
    interface AssessLayoutBehavior {
        AssessLayoutResultDto apply(String viewId);
    }

    @FunctionalInterface
    interface LayoutViewBehavior {
        MutationResult<LayoutViewResultDto> apply(String sessionId, String viewId,
                String algorithm, String preset, Map<String, Object> options);
    }

    @FunctionalInterface
    interface AddToViewBehavior {
        MutationResult<AddToViewResultDto> apply(String sessionId, String viewId,
                String elementId, Integer x, Integer y, Integer width, Integer height,
                boolean autoConnect, String parentViewObjectId);
    }

    @FunctionalInterface
    interface AddGroupToViewBehavior {
        MutationResult<ViewGroupDto> apply(String sessionId, String viewId,
                String label, Integer x, Integer y, Integer width, Integer height,
                String parentViewObjectId);
    }

    @FunctionalInterface
    interface AddNoteToViewBehavior {
        MutationResult<ViewNoteDto> apply(String sessionId, String viewId,
                String content, Integer x, Integer y, Integer width, Integer height,
                String parentViewObjectId);
    }

    @FunctionalInterface
    interface AddConnectionBehavior {
        MutationResult<ViewConnectionDto> apply(String sessionId, String viewId,
                String relationshipId, String sourceViewObjectId, String targetViewObjectId,
                List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints);
    }

    @FunctionalInterface
    interface UpdateViewObjectBehavior {
        MutationResult<ViewObjectDto> apply(String sessionId, String viewObjectId,
                Integer x, Integer y, Integer width, Integer height, String text);
    }

    @FunctionalInterface
    interface UpdateViewConnectionBehavior {
        MutationResult<ViewConnectionDto> apply(String sessionId, String viewConnectionId,
                List<BendpointDto> bendpoints, List<AbsoluteBendpointDto> absoluteBendpoints);
    }

    @FunctionalInterface
    interface RemoveFromViewBehavior {
        MutationResult<RemoveFromViewResultDto> apply(String sessionId, String viewId,
                String viewObjectId);
    }

    @FunctionalInterface
    interface ClearViewBehavior {
        MutationResult<ClearViewResultDto> apply(String sessionId, String viewId);
    }

    @FunctionalInterface
    interface ApplyViewLayoutBehavior {
        MutationResult<ApplyViewLayoutResultDto> apply(String sessionId, String viewId,
                List<ViewPositionSpec> positions, List<ViewConnectionSpec> connections,
                String description);
    }

    private static class StubViewPlacementAccessor extends BaseTestAccessor {

        private AddToViewBehavior addToViewBehavior;
        private AddGroupToViewBehavior addGroupToViewBehavior;
        private AddNoteToViewBehavior addNoteToViewBehavior;
        private AddConnectionBehavior addConnectionBehavior;
        private UpdateViewObjectBehavior updateViewObjectBehavior;
        private UpdateViewConnectionBehavior updateViewConnectionBehavior;
        private RemoveFromViewBehavior removeFromViewBehavior;
        private ClearViewBehavior clearViewBehavior;
        private ApplyViewLayoutBehavior applyViewLayoutBehavior;
        private LayoutViewBehavior layoutViewBehavior;
        private AssessLayoutBehavior assessLayoutBehavior;
        private AutoConnectViewBehavior autoConnectViewBehavior;
        private AutoLayoutAndRouteBehavior autoLayoutAndRouteBehavior;
        private AutoRouteConnectionsBehavior autoRouteConnectionsBehavior;
        private LayoutWithinGroupBehavior layoutWithinGroupBehavior;
        private ArrangeGroupsBehavior arrangeGroupsBehavior;
        private OptimizeGroupOrderBehavior optimizeGroupOrderBehavior;

        // Capture last styling params passed to each method (for assertion in tests)
        StylingParams lastUpdateViewObjectStyling;
        StylingParams lastAddToViewStyling;
        StylingParams lastAddGroupToViewStyling;
        StylingParams lastAddNoteToViewStyling;
        StylingParams lastUpdateViewConnectionStyling;

        StubViewPlacementAccessor() {
            super(true);
            resetBehaviors();
        }

        StubViewPlacementAccessor(boolean modelLoaded) {
            super(modelLoaded);
            resetBehaviors();
        }

        void setAddToViewBehavior(AddToViewBehavior behavior) {
            this.addToViewBehavior = behavior;
        }

        void setAddGroupToViewBehavior(AddGroupToViewBehavior behavior) {
            this.addGroupToViewBehavior = behavior;
        }

        void setAddNoteToViewBehavior(AddNoteToViewBehavior behavior) {
            this.addNoteToViewBehavior = behavior;
        }

        void setAddConnectionBehavior(AddConnectionBehavior behavior) {
            this.addConnectionBehavior = behavior;
        }

        void setUpdateViewObjectBehavior(UpdateViewObjectBehavior behavior) {
            this.updateViewObjectBehavior = behavior;
        }

        void setUpdateViewConnectionBehavior(UpdateViewConnectionBehavior behavior) {
            this.updateViewConnectionBehavior = behavior;
        }

        void setRemoveFromViewBehavior(RemoveFromViewBehavior behavior) {
            this.removeFromViewBehavior = behavior;
        }

        void setClearViewBehavior(ClearViewBehavior behavior) {
            this.clearViewBehavior = behavior;
        }

        void setApplyViewLayoutBehavior(ApplyViewLayoutBehavior behavior) {
            this.applyViewLayoutBehavior = behavior;
        }

        void setLayoutViewBehavior(LayoutViewBehavior behavior) {
            this.layoutViewBehavior = behavior;
        }

        void setAssessLayoutBehavior(AssessLayoutBehavior behavior) {
            this.assessLayoutBehavior = behavior;
        }

        void setAutoConnectViewBehavior(AutoConnectViewBehavior behavior) {
            this.autoConnectViewBehavior = behavior;
        }

        void setAutoLayoutAndRouteBehavior(AutoLayoutAndRouteBehavior behavior) {
            this.autoLayoutAndRouteBehavior = behavior;
        }

        void setAutoRouteConnectionsBehavior(AutoRouteConnectionsBehavior behavior) {
            this.autoRouteConnectionsBehavior = behavior;
        }

        void setLayoutWithinGroupBehavior(LayoutWithinGroupBehavior behavior) {
            this.layoutWithinGroupBehavior = behavior;
        }

        void setArrangeGroupsBehavior(ArrangeGroupsBehavior behavior) {
            this.arrangeGroupsBehavior = behavior;
        }

        void setOptimizeGroupOrderBehavior(OptimizeGroupOrderBehavior behavior) {
            this.optimizeGroupOrderBehavior = behavior;
        }

        private void resetBehaviors() {
            this.addToViewBehavior = (sid, vId, eId, x, y, w, h, ac, pvoId) -> {
                int rx = (x != null) ? x : 50;
                int ry = (y != null) ? y : 50;
                int rw = (w != null) ? w : 120;
                int rh = (h != null) ? h : 55;
                ViewObjectDto vo = new ViewObjectDto(
                        "vo-1", eId, "Element Name", "BusinessActor", rx, ry, rw, rh);
                return new MutationResult<>(new AddToViewResultDto(vo, null), null);
            };
            this.addGroupToViewBehavior = (sid, vId, label, x, y, w, h, pvoId) -> {
                int rx = (x != null) ? x : 50;
                int ry = (y != null) ? y : 50;
                int rw = (w != null) ? w : 300;
                int rh = (h != null) ? h : 200;
                ViewGroupDto dto = new ViewGroupDto("vg-1", label, rx, ry, rw, rh, null, null);
                return new MutationResult<>(dto, null);
            };
            this.addNoteToViewBehavior = (sid, vId, content, x, y, w, h, pvoId) -> {
                int rx = (x != null) ? x : 50;
                int ry = (y != null) ? y : 50;
                int rw = (w != null) ? w : 185;
                int rh = (h != null) ? h : 80;
                ViewNoteDto dto = new ViewNoteDto("vn-1", content, rx, ry, rw, rh, null);
                return new MutationResult<>(dto, null);
            };
            this.addConnectionBehavior = (sid, vId, relId, src, tgt, bps, absBps) -> {
                ViewConnectionDto dto = new ViewConnectionDto(
                        "vc-1", relId, "ServingRelationship", src, tgt, null);
                return new MutationResult<>(dto, null);
            };
            this.updateViewObjectBehavior = (sid, voId, x, y, w, h, txt) -> {
                int rx = (x != null) ? x : 50;
                int ry = (y != null) ? y : 50;
                int rw = (w != null) ? w : 120;
                int rh = (h != null) ? h : 55;
                ViewObjectDto dto = new ViewObjectDto(
                        voId, "e-1", "Element Name", "BusinessActor", rx, ry, rw, rh);
                return new MutationResult<>(dto, null);
            };
            this.updateViewConnectionBehavior = (sid, vcId, bps, absBps) -> {
                ViewConnectionDto dto = new ViewConnectionDto(
                        vcId, "rel-1", "ServingRelationship", "vo-1", "vo-2", bps);
                return new MutationResult<>(dto, null);
            };
            this.removeFromViewBehavior = (sid, vId, voId) -> {
                RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                        voId, "viewObject", null);
                return new MutationResult<>(dto, null);
            };
            this.clearViewBehavior = (sid, vId) -> {
                ClearViewResultDto dto = new ClearViewResultDto(
                        vId, "Test View", 3, 1, 0);
                return new MutationResult<>(dto, null);
            };
            this.applyViewLayoutBehavior = (sid, vId, pos, conns, desc) -> {
                int posCount = (pos != null) ? pos.size() : 0;
                int connCount = (conns != null) ? conns.size() : 0;
                ApplyViewLayoutResultDto dto = new ApplyViewLayoutResultDto(
                        vId, posCount, connCount, posCount + connCount);
                return new MutationResult<>(dto, null);
            };
            this.layoutViewBehavior = (sid, vId, algo, preset, opts) -> {
                String usedAlgo = (algo != null) ? algo : "tree";
                LayoutViewResultDto dto = new LayoutViewResultDto(
                        vId, usedAlgo, preset, 5, 3, 8);
                return new MutationResult<>(dto, null);
            };
            this.assessLayoutBehavior = (vId) -> new AssessLayoutResultDto(
                    vId, 5, 3, 0, 0, 2, 0.67, 45.5, 70, "good", null,
                    null, null, null, null, 0, null, 0, null, 0, null, false, 0, null,
                    List.of("Layout quality is good — no immediate improvements needed."));
            this.autoLayoutAndRouteBehavior = (sid, vId, dir, sp, tr) -> {
                String d = (dir != null) ? dir.toUpperCase() : "DOWN";
                int s = sp > 0 ? sp : 50;
                return new MutationResult<>(new AutoLayoutAndRouteResultDto(
                        vId, d, s, 5, 3, false, 8), null);
            };
            this.autoRouteConnectionsBehavior = (sid, vId, connIds, strategy, force) -> {
                String s = (strategy != null) ? strategy : "orthogonal";
                return new MutationResult<>(new AutoRouteResultDto(vId, 5, s, false), null);
            };
            this.autoConnectViewBehavior = (sid, vId, elemIds, relTypes) ->
                    new MutationResult<>(new AutoConnectResultDto(
                            vId, 3, 1, List.of("r-1", "r-2", "r-3")), null);
            this.layoutWithinGroupBehavior = (sid, vId, gvoId, arr, sp, pad, ew, eh, ar, aw, cols, rec) ->
                    new MutationResult<>(new LayoutWithinGroupResultDto(
                            vId, gvoId, arr, 4, ar, ar ? 300 : null, ar ? 200 : null, false, aw, null, 0), null);
            this.arrangeGroupsBehavior = (sid, vId, arr, cols, sp, gids) ->
                    new MutationResult<>(new ArrangeGroupsResultDto(
                            vId, 6, 800, 600,
                            "grid".equals(arr) ? (cols != null ? cols : 3) : null,
                            arr), null);
            this.optimizeGroupOrderBehavior = (sid, vId, arr, sp, pad, ew, eh, aw, cols) ->
                    new MutationResult<>(new OptimizeGroupOrderResultDto(
                            vId, 5, 2, 60.0, 2, 4, List.of()), null);
        }

        @Override
        public MutationResult<AddToViewResultDto> addToView(String sessionId, String viewId,
                String elementId, Integer x, Integer y, Integer width, Integer height,
                boolean autoConnect, String parentViewObjectId, StylingParams styling) {
            this.lastAddToViewStyling = styling;
            return addToViewBehavior.apply(sessionId, viewId, elementId, x, y, width, height,
                    autoConnect, parentViewObjectId);
        }

        @Override
        public MutationResult<ViewGroupDto> addGroupToView(String sessionId, String viewId,
                String label, Integer x, Integer y, Integer width, Integer height,
                String parentViewObjectId, StylingParams styling) {
            this.lastAddGroupToViewStyling = styling;
            return addGroupToViewBehavior.apply(sessionId, viewId, label, x, y, width, height,
                    parentViewObjectId);
        }

        @Override
        public MutationResult<ViewNoteDto> addNoteToView(String sessionId, String viewId,
                String content, Integer x, Integer y, Integer width, Integer height,
                String parentViewObjectId, StylingParams styling) {
            this.lastAddNoteToViewStyling = styling;
            return addNoteToViewBehavior.apply(sessionId, viewId, content, x, y, width, height,
                    parentViewObjectId);
        }

        @Override
        public MutationResult<ViewConnectionDto> addConnectionToView(String sessionId,
                String viewId, String relationshipId, String sourceViewObjectId,
                String targetViewObjectId, List<BendpointDto> bendpoints,
                List<AbsoluteBendpointDto> absoluteBendpoints) {
            return addConnectionBehavior.apply(sessionId, viewId, relationshipId,
                    sourceViewObjectId, targetViewObjectId, bendpoints, absoluteBendpoints);
        }

        @Override
        public MutationResult<ViewObjectDto> updateViewObject(String sessionId,
                String viewObjectId, Integer x, Integer y, Integer width, Integer height,
                String text, StylingParams styling) {
            this.lastUpdateViewObjectStyling = styling;
            return updateViewObjectBehavior.apply(sessionId, viewObjectId, x, y, width, height,
                    text);
        }

        @Override
        public MutationResult<ViewConnectionDto> updateViewConnection(String sessionId,
                String viewConnectionId, List<BendpointDto> bendpoints,
                List<AbsoluteBendpointDto> absoluteBendpoints, StylingParams styling) {
            this.lastUpdateViewConnectionStyling = styling;
            return updateViewConnectionBehavior.apply(sessionId, viewConnectionId,
                    bendpoints, absoluteBendpoints);
        }

        @Override
        public MutationResult<RemoveFromViewResultDto> removeFromView(String sessionId,
                String viewId, String viewObjectId) {
            return removeFromViewBehavior.apply(sessionId, viewId, viewObjectId);
        }

        @Override
        public MutationResult<ClearViewResultDto> clearView(String sessionId, String viewId) {
            return clearViewBehavior.apply(sessionId, viewId);
        }

        @Override
        public MutationResult<ApplyViewLayoutResultDto> applyViewLayout(String sessionId,
                String viewId, List<ViewPositionSpec> positions,
                List<ViewConnectionSpec> connections, String description) {
            return applyViewLayoutBehavior.apply(sessionId, viewId, positions, connections,
                    description);
        }

        @Override
        public MutationResult<LayoutViewResultDto> layoutView(String sessionId,
                String viewId, String algorithm, String preset,
                Map<String, Object> options) {
            return layoutViewBehavior.apply(sessionId, viewId, algorithm, preset, options);
        }

        @Override
        public AssessLayoutResultDto assessLayout(String viewId) {
            return assessLayoutBehavior.apply(viewId);
        }

        @Override
        public MutationResult<AutoLayoutAndRouteResultDto> autoLayoutAndRoute(
                String sessionId, String viewId,
                String direction, int spacing, String targetRating) {
            return autoLayoutAndRouteBehavior.apply(sessionId, viewId, direction, spacing, targetRating);
        }

        @Override
        public MutationResult<AutoRouteResultDto> autoRouteConnections(
                String sessionId, String viewId,
                List<String> connectionIds, String strategy, boolean force) {
            return autoRouteConnectionsBehavior.apply(sessionId, viewId, connectionIds, strategy, force);
        }

        @Override
        public MutationResult<AutoConnectResultDto> autoConnectView(
                String sessionId, String viewId,
                List<String> elementIds, List<String> relationshipTypes) {
            return autoConnectViewBehavior.apply(sessionId, viewId, elementIds, relationshipTypes);
        }

        @Override
        public MutationResult<LayoutWithinGroupResultDto> layoutWithinGroup(
                String sessionId, String viewId, String groupViewObjectId,
                String arrangement, Integer spacing, Integer padding,
                Integer elementWidth, Integer elementHeight, boolean autoResize,
                boolean autoWidth, Integer columns, boolean recursive) {
            return layoutWithinGroupBehavior.apply(sessionId, viewId, groupViewObjectId,
                    arrangement, spacing, padding, elementWidth, elementHeight, autoResize,
                    autoWidth, columns, recursive);
        }

        @Override
        public MutationResult<ArrangeGroupsResultDto> arrangeGroups(
                String sessionId, String viewId, String arrangement,
                Integer columns, Integer spacing, List<String> groupIds) {
            return arrangeGroupsBehavior.apply(sessionId, viewId, arrangement,
                    columns, spacing, groupIds);
        }

        @Override
        public MutationResult<OptimizeGroupOrderResultDto> optimizeGroupOrder(
                String sessionId, String viewId, String arrangement,
                Integer spacing, Integer padding, Integer elementWidth,
                Integer elementHeight, boolean autoWidth, Integer columns) {
            return optimizeGroupOrderBehavior.apply(sessionId, viewId, arrangement,
                    spacing, padding, elementWidth, elementHeight, autoWidth, columns);
        }
    }
}
