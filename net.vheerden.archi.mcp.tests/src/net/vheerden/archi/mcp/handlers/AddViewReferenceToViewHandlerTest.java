package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.StylingParams;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;

/**
 * Tests for {@code add-view-reference-to-view} in {@link ViewPlacementHandler}.
 *
 * <p>Uses a stub-accessor that records the args passed to
 * {@code addViewReferenceToView(...)} and returns a canned DTO or throws a
 * {@link ModelAccessException}; avoids EMF/GEF runtime dependencies in the
 * handler layer.</p>
 */
public class AddViewReferenceToViewHandlerTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubAccessor accessor;
    private ViewPlacementHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        accessor = new StubAccessor(true);
        handler = new ViewPlacementHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Tool registration ----

    @Test
    public void shouldRegisterAddViewReferenceToViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "add-view-reference-to-view".equals(spec.tool().name()));
        assertTrue("add-view-reference-to-view tool should be registered", found);
    }

    // ---- Validation ----

    @Test
    public void shouldRejectMissingViewId() throws Exception {
        McpSchema.CallToolResult result = call(Map.of("referencedViewId", "v-ref"));

        assertTrue(result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("error references viewId", content.contains("viewId"));
    }

    @Test
    public void shouldRejectMissingReferencedViewId() throws Exception {
        McpSchema.CallToolResult result = call(Map.of("viewId", "v-target"));

        assertTrue(result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("error references referencedViewId",
                content.contains("referencedViewId"));
    }

    @Test
    public void shouldRejectViewIdNotFound() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            throw new ModelAccessException("View not found: " + vId,
                    ErrorCode.VIEW_NOT_FOUND, null,
                    "Use get-views to find valid view IDs", null);
        });

        McpSchema.CallToolResult result = call(Map.of(
                "viewId", "v-missing",
                "referencedViewId", "v-ref"));

        assertTrue(result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
    }

    @Test
    public void shouldRejectReferencedViewIdNotFound() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            throw new ModelAccessException(
                    "Referenced view not found or is not an ArchiMate view: " + refVId,
                    ErrorCode.VIEW_NOT_FOUND, null,
                    "Use get-views to find valid view IDs", null);
        });

        McpSchema.CallToolResult result = call(Map.of(
                "viewId", "v-target",
                "referencedViewId", "v-missing"));

        assertTrue(result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("VIEW_NOT_FOUND"));
        assertTrue("Should mention the not-an-ArchiMate-view variant",
                content.contains("not an ArchiMate view"));
    }

    @Test
    public void shouldRejectReferencedViewIdResolvingToFolder() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            throw new ModelAccessException(
                    "Referenced view not found or is not an ArchiMate view: " + refVId,
                    ErrorCode.VIEW_NOT_FOUND, null,
                    "Use get-views to find valid view IDs", null);
        });

        McpSchema.CallToolResult result = call(Map.of(
                "viewId", "v-target",
                "referencedViewId", "folder-id"));

        assertTrue(result.isError());
    }

    @Test
    public void shouldRejectXWithoutY() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            throw new ModelAccessException(
                    "Both x and y must be specified together, or both omitted for auto-placement",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Provide both x and y coordinates, or omit both for auto-placement", null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "v-target");
        args.put("referencedViewId", "v-ref");
        args.put("x", 100);

        McpSchema.CallToolResult result = call(args);

        assertTrue(result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
        assertTrue(content.contains("x and y"));
    }

    @Test
    public void shouldRejectNonPositiveDimensions() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            throw new ModelAccessException(
                    "width must be positive (was: " + w + ")",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Provide a positive width value", null);
        });

        McpSchema.CallToolResult result = call(Map.of(
                "viewId", "v-target",
                "referencedViewId", "v-ref",
                "width", 0,
                "height", 80));

        assertTrue(result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("INVALID_PARAMETER"));
    }

    // ---- Successful placements ----

    @Test
    public void shouldPlaceTopLevelViewReference_withAutoPlacement() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            EmbeddedViewDto dto = new EmbeddedViewDto(
                    "vo-new", refVId, 50, 50, 185, 80, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> parsed = callAndParse(Map.of(
                "viewId", "v-target",
                "referencedViewId", "v-ref"));

        Map<String, Object> entity = getResult(parsed);
        assertEquals("vo-new", entity.get("viewObjectId"));
        assertEquals("v-ref", entity.get("referencedViewId"));
        assertEquals(50, entity.get("x"));
        assertEquals(50, entity.get("y"));
        assertNull("Top-level — parentViewObjectId omitted",
                entity.get("parentViewObjectId"));
    }

    @Test
    public void shouldPlaceTopLevelViewReference_withExplicitBounds() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            EmbeddedViewDto dto = new EmbeddedViewDto(
                    "vo-new", refVId, x, y, w, h, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> parsed = callAndParse(Map.of(
                "viewId", "v-target",
                "referencedViewId", "v-ref",
                "x", 100,
                "y", 200,
                "width", 300,
                "height", 150));

        Map<String, Object> entity = getResult(parsed);
        assertEquals(100, entity.get("x"));
        assertEquals(200, entity.get("y"));
        assertEquals(300, entity.get("width"));
        assertEquals(150, entity.get("height"));
    }

    @Test
    public void shouldPlaceNestedViewReference_inParentGroup() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            // EmbeddedViewDto's x/y/width/height are primitive int. The real accessor defaults
            // omitted width/height (120x55 per the tool schema) before building the DTO; the stub
            // must do the same, else it NPEs auto-unboxing the null Integer width/height this
            // call omits (sibling tests pass concrete values).
            EmbeddedViewDto dto = new EmbeddedViewDto(
                    "vo-new", refVId,
                    x != null ? x : 0, y != null ? y : 0,
                    w != null ? w : 120, h != null ? h : 55, pvoId);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> parsed = callAndParse(Map.of(
                "viewId", "v-target",
                "referencedViewId", "v-ref",
                "x", 30,
                "y", 30,
                "parentViewObjectId", "vo-group"));

        Map<String, Object> entity = getResult(parsed);
        assertEquals("vo-group", entity.get("parentViewObjectId"));
        assertEquals(30, entity.get("x"));
    }

    @Test
    public void shouldApplyStyling_atCreationTime() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            // Echo the styling fields back through the DTO
            EmbeddedViewDto dto = new EmbeddedViewDto(
                    "vo-new", refVId, 0, 0, 185, 80, null,
                    st != null ? st.fillColor() : null,
                    st != null ? st.lineColor() : null,
                    null, null,
                    st != null ? st.lineWidth() : null,
                    null, null,
                    st != null ? st.fontStyle() : null,
                    null, null, null,
                    st != null ? st.lineStyle() : null,
                    null, null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> parsed = callAndParse(Map.of(
                "viewId", "v-target",
                "referencedViewId", "v-ref",
                "fillColor", "#FFE4B5",
                "lineColor", "#8B4513",
                "lineWidth", 2,
                "fontStyle", "bold",
                "lineStyle", "dashed"));

        Map<String, Object> entity = getResult(parsed);
        assertEquals("#FFE4B5", entity.get("fillColor"));
        assertEquals("#8B4513", entity.get("lineColor"));
        assertEquals(2, entity.get("lineWidth"));
        assertEquals("bold", entity.get("fontStyle"));
        assertEquals("dashed", entity.get("lineStyle"));
    }

    @Test
    public void shouldAcceptSelfReference_perTask0Disposition() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            // Mirror Task 0.6: Archi accepts self-reference; we accept too.
            EmbeddedViewDto dto = new EmbeddedViewDto(
                    "vo-self", refVId, 0, 0, 185, 80, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> parsed = callAndParse(Map.of(
                "viewId", "v-same",
                "referencedViewId", "v-same"));

        Map<String, Object> entity = getResult(parsed);
        assertEquals("v-same", entity.get("referencedViewId"));
    }

    // ---- Next-steps ----

    @Test
    public void shouldEmitStandardNextSteps() throws Exception {
        accessor.setBehavior((sid, vId, refVId, x, y, w, h, pvoId, st) -> {
            EmbeddedViewDto dto = new EmbeddedViewDto(
                    "vo-new", refVId, 50, 50, 185, 80, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> result = callAndParse(Map.of(
                "viewId", "v-target",
                "referencedViewId", "v-ref"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(3, nextSteps.size());
        assertTrue("Step 1 mentions get-view-contents",
                nextSteps.get(0).contains("get-view-contents"));
        assertTrue("Step 2 mentions update-view-object",
                nextSteps.get(1).contains("update-view-object"));
        assertTrue("Step 3 mentions add-view-reference-to-view or add-to-view",
                nextSteps.get(2).contains("add-view-reference-to-view")
                        || nextSteps.get(2).contains("add-to-view"));
    }

    // ---- Model-not-loaded path ----

    @Test
    public void shouldReturnModelNotLoadedError_forAddViewReferenceToView() throws Exception {
        StubAccessor noModel = new StubAccessor(false);
        ViewPlacementHandler noModelHandler = new ViewPlacementHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-view-reference-to-view")
                .arguments(Map.of("viewId", "v-1", "referencedViewId", "v-2"))
                .build();

        McpSchema.CallToolResult result =
                noModelHandler.handleAddViewReferenceToView(null, request);

        assertTrue(result.isError());
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(content.contains("MODEL_NOT_LOADED"));
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult call(Map<String, Object> args) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("add-view-reference-to-view")
                .arguments(args)
                .build();
        return handler.handleAddViewReferenceToView(null, request);
    }

    private Map<String, Object> callAndParse(Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = call(args);
        assertFalse("Response should not be error: " + result.content(), result.isError());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getResult(Map<String, Object> parsed) {
        Object inner = parsed.get("result");
        if (inner instanceof Map) {
            return (Map<String, Object>) inner;
        }
        return parsed;
    }

    // ---- Stub accessor ----

    @FunctionalInterface
    private interface AddViewReferenceBehavior {
        MutationResult<EmbeddedViewDto> apply(String sessionId, String viewId,
                String referencedViewId, Integer x, Integer y,
                Integer width, Integer height, String parentViewObjectId,
                StylingParams styling);
    }

    private static class StubAccessor extends BaseTestAccessor {

        private AddViewReferenceBehavior behavior;

        StubAccessor(boolean modelLoaded) {
            super(modelLoaded);
            this.behavior = (sid, vId, refVId, x, y, w, h, pvoId, st) -> {
                throw new UnsupportedOperationException(
                        "No behavior configured for addViewReferenceToView");
            };
        }

        void setBehavior(AddViewReferenceBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public MutationResult<EmbeddedViewDto> addViewReferenceToView(String sessionId,
                String viewId, String referencedViewId, Integer x, Integer y,
                Integer width, Integer height, String parentViewObjectId,
                StylingParams styling) {
            return behavior.apply(sessionId, viewId, referencedViewId, x, y,
                    width, height, parentViewObjectId, styling);
        }
    }
}
