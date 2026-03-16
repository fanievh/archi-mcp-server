package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;

import org.eclipse.gef.commands.Command;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Unit tests for {@link ViewHandler}.
 *
 * <p>Uses a stub ArchiModelAccessor — no EMF/OSGi runtime required.</p>
 */
public class ViewHandlerTest {

    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        objectMapper = new ObjectMapper();
    }

    // ---- Tool Registration Tests (AC #4) ----

    @Test
    public void shouldRegisterGetViewsTool() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        assertEquals(3, registry.getToolCount());
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        assertEquals("get-views", spec.tool().name());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveOptionalViewpointParameterInSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-views").tool();
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().type());

        Map<String, Object> properties = tool.inputSchema().properties();
        assertNotNull(properties);
        assertTrue(properties.containsKey("viewpoint"));

        Map<String, Object> vpProp = (Map<String, Object>) properties.get("viewpoint");
        assertEquals("string", vpProp.get("type"));
        assertNotNull(vpProp.get("description"));

        // viewpoint is optional — required should be null or empty
        List<String> required = tool.inputSchema().required();
        assertTrue(required == null || required.isEmpty());
    }

    @Test
    public void shouldHaveDescriptionInToolSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-views").tool();
        assertNotNull(tool.description());
        assertTrue(tool.description().contains("views"));
    }

    // ---- Success Path Tests (AC #1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnViews_whenModelLoaded() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViews(null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // Verify result is a list of views
        assertNotNull(envelope.get("result"));
        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(3, views.size());

        // Verify first view fields (AC #1: id, name, viewpointType, folderPath)
        Map<String, Object> firstView = views.get(0);
        assertEquals("view-1", firstView.get("id"));
        assertEquals("Application Landscape", firstView.get("name"));
        assertEquals("Application Usage", firstView.get("viewpointType"));
        assertEquals("Views", firstView.get("folderPath"));

        // Verify second view (null viewpoint should be omitted from JSON by NON_NULL)
        Map<String, Object> secondView = views.get(1);
        assertEquals("view-2", secondView.get("id"));
        assertEquals("Business Processes", secondView.get("name"));
        assertFalse("viewpointType should be omitted from JSON when null",
                secondView.containsKey("viewpointType"));
        assertEquals("Views/Business", secondView.get("folderPath"));

        // Verify nextSteps
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.get(0).contains("get-view-contents"));

        // Verify _meta
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals("42", meta.get("modelVersion"));
        assertEquals(3, meta.get("resultCount"));
        assertEquals(3, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyList_whenNoViews() throws Exception {
        StubAccessor accessor = new StubAccessor(true, List.of());
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViews(null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(views);
        assertEquals(0, views.size());

        // Verify _meta
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(0, meta.get("resultCount"));
        assertEquals(0, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));

        // Verify nextSteps for empty result
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.get(0).contains("get-model-info"));
    }

    // ---- Viewpoint Filter Tests (AC #3) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFilterByViewpoint_whenViewpointProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViews("Application Usage");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, views.size());
        assertEquals("view-1", views.get(0).get("id"));
        assertEquals("Application Usage", views.get(0).get("viewpointType"));

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(1, meta.get("resultCount"));
        assertEquals(1, meta.get("totalCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnAllViews_whenNoViewpointFilter() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViews(null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(3, views.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldTreatBlankViewpointAsNoFilter() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Invoke with blank string — should be treated as no filter
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = Map.of("viewpoint", "   ");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-views", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(3, views.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyList_whenViewpointMatchesNothing() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViews("Nonexistent Viewpoint");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(views);
        assertEquals(0, views.size());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(0, meta.get("resultCount"));
        assertEquals(0, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));

        // Empty filtered result should suggest get-model-info
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.get(0).contains("get-model-info"));
    }

    // ---- Pagination Tests (Story 6.1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnAllViews_whenBelowLimit() throws Exception {
        // 3 views with default limit=50 → no pagination
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViews(null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(3, views.size());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(3, meta.get("resultCount"));
        assertEquals(3, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));
        assertFalse("No cursor when all results fit", meta.containsKey("cursor"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnFirstPage_whenAboveLimit() throws Exception {
        // Create 60 views to exceed DEFAULT_VIEWS_LIMIT (50)
        List<ViewDto> manyViews = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            manyViews.add(new ViewDto("view-" + i, "View " + i, null, "Views"));
        }
        StubAccessor accessor = new StubAccessor(true, manyViews);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViews(null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(ViewHandler.DEFAULT_VIEWS_LIMIT, views.size());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(ViewHandler.DEFAULT_VIEWS_LIMIT, meta.get("resultCount"));
        assertEquals(60, meta.get("totalCount"));
        assertEquals(true, meta.get("isTruncated"));
        assertNotNull("Cursor should be present when truncated", meta.get("cursor"));

        // Verify pagination nextSteps
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.get(0).contains("cursor"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnNextPage_whenValidCursor() throws Exception {
        // Create 5 views, use limit=2 to paginate
        List<ViewDto> views = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            views.add(new ViewDto("view-" + i, "View " + i, null, "Views"));
        }
        StubAccessor accessor = new StubAccessor(true, views);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");

        // First page: limit=2
        Map<String, Object> args1 = new java.util.HashMap<>();
        args1.put("limit", 2);
        McpSchema.CallToolResult result1 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args1));
        Map<String, Object> env1 = parseJson(result1);
        Map<String, Object> meta1 = (Map<String, Object>) env1.get("_meta");
        String cursor = (String) meta1.get("cursor");
        assertNotNull(cursor);

        // Second page: use cursor
        Map<String, Object> args2 = new java.util.HashMap<>();
        args2.put("cursor", cursor);
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args2));

        assertFalse(result2.isError());
        Map<String, Object> env2 = parseJson(result2);
        List<Map<String, Object>> resultList2 = (List<Map<String, Object>>) env2.get("result");
        assertEquals(2, resultList2.size());
        // Should be views 2 and 3 (offset=2, limit=2)
        assertEquals("view-2", resultList2.get(0).get("id"));
        assertEquals("view-3", resultList2.get(1).get("id"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnLastPage_whenCursorReachesFinalPage() throws Exception {
        List<ViewDto> views = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            views.add(new ViewDto("view-" + i, "View " + i, null, "Views"));
        }
        StubAccessor accessor = new StubAccessor(true, views);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");

        // Page through all 5 views with limit=2
        String cursor = null;
        int totalResults = 0;
        for (int page = 0; page < 3; page++) {
            Map<String, Object> args = new java.util.HashMap<>();
            args.put("limit", 2);
            if (cursor != null) args.put("cursor", cursor);
            McpSchema.CallToolResult result = spec.callHandler().apply(null,
                    new McpSchema.CallToolRequest("get-views", args));
            Map<String, Object> env = parseJson(result);
            List<Map<String, Object>> resultList = (List<Map<String, Object>>) env.get("result");
            totalResults += resultList.size();
            Map<String, Object> meta = (Map<String, Object>) env.get("_meta");
            cursor = (String) meta.get("cursor");
        }

        assertEquals(5, totalResults);
        assertNull("No cursor on last page", cursor);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidCursor_whenModelVersionChanged() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");

        // Get a cursor
        Map<String, Object> args1 = new java.util.HashMap<>();
        args1.put("limit", 1);
        McpSchema.CallToolResult result1 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args1));
        Map<String, Object> env1 = parseJson(result1);
        String cursor = (String) ((Map<String, Object>) env1.get("_meta")).get("cursor");
        assertNotNull(cursor);

        // Change model version
        accessor.setVersion("99");

        // Use stale cursor
        Map<String, Object> args2 = new java.util.HashMap<>();
        args2.put("cursor", cursor);
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args2));

        assertTrue(result2.isError());
        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> error = (Map<String, Object>) env2.get("error");
        assertEquals("INVALID_CURSOR", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidCursor_whenCursorMalformed() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("cursor", "bad-cursor-string");
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) env.get("error");
        assertEquals("INVALID_CURSOR", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenLimitTooLarge_getViews() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("limit", 1000);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) env.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("limit"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveLimitAndCursorInGetViewsSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-views").tool();
        Map<String, Object> properties = tool.inputSchema().properties();

        assertTrue("Should have limit property", properties.containsKey("limit"));
        Map<String, Object> limitProp = (Map<String, Object>) properties.get("limit");
        assertEquals("integer", limitProp.get("type"));

        assertTrue("Should have cursor property", properties.containsKey("cursor"));
        Map<String, Object> cursorProp = (Map<String, Object>) properties.get("cursor");
        assertEquals("string", cursorProp.get("type"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotPaginate_getViewContents() throws Exception {
        // get-view-contents should NOT have cursor/limit params
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-view-contents").tool();
        Map<String, Object> properties = tool.inputSchema().properties();

        assertFalse("get-view-contents should not have limit", properties.containsKey("limit"));
        assertFalse("get-view-contents should not have cursor", properties.containsKey("cursor"));
    }

    // ---- Error Path Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnModelNotLoadedError_whenNoModel() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViews(null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
        assertEquals("Open an ArchiMate model in ArchimateTool", error.get("suggestedCorrection"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInternalError_whenUnexpectedException() throws Exception {
        ExplodingAccessor accessor = new ExplodingAccessor();
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViews(null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INTERNAL_ERROR", error.get("code"));
    }

    // ---- get-view-contents: Tool Registration Tests (AC #4) ----

    @Test
    public void shouldRegisterGetViewContentsTool() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        assertEquals(3, registry.getToolCount());
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-view-contents");
        assertEquals("get-view-contents", spec.tool().name());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRequireViewIdParameterInGetViewContentsSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-view-contents").tool();
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().type());

        Map<String, Object> properties = tool.inputSchema().properties();
        assertNotNull(properties);
        assertTrue(properties.containsKey("viewId"));

        Map<String, Object> viewIdProp = (Map<String, Object>) properties.get("viewId");
        assertEquals("string", viewIdProp.get("type"));
        assertNotNull(viewIdProp.get("description"));

        // viewId is required
        List<String> required = tool.inputSchema().required();
        assertNotNull(required);
        assertTrue(required.contains("viewId"));
    }

    // ---- get-view-contents: Success Path Tests (AC #1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnViewContents_whenViewExists() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViewContents("view-1");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // Verify result structure
        assertNotNull(envelope.get("result"));
        Map<String, Object> contents = (Map<String, Object>) envelope.get("result");

        // Verify view metadata
        assertEquals("view-1", contents.get("viewId"));
        assertEquals("Application Landscape", contents.get("viewName"));
        assertEquals("Application Usage", contents.get("viewpoint"));

        // Verify elements (AC #1: elements as ElementDto)
        List<Map<String, Object>> elements = (List<Map<String, Object>>) contents.get("elements");
        assertNotNull(elements);
        assertEquals(2, elements.size());
        assertEquals("elem-1", elements.get(0).get("id"));
        assertEquals("Customer Portal", elements.get(0).get("name"));
        assertEquals("ApplicationComponent", elements.get(0).get("type"));
        assertEquals("Application", elements.get(0).get("layer"));

        // Verify relationships (AC #1: RelationshipDto with sourceId, targetId, type, name)
        List<Map<String, Object>> relationships = (List<Map<String, Object>>) contents.get("relationships");
        assertNotNull(relationships);
        assertEquals(1, relationships.size());
        assertEquals("rel-1", relationships.get(0).get("id"));
        assertEquals("Serves", relationships.get(0).get("name"));
        assertEquals("ServingRelationship", relationships.get(0).get("type"));
        assertEquals("elem-1", relationships.get(0).get("sourceId"));
        assertEquals("elem-2", relationships.get(0).get("targetId"));

        // Verify visual metadata (AC #1: positions/sizes)
        List<Map<String, Object>> visualMetadata = (List<Map<String, Object>>) contents.get("visualMetadata");
        assertNotNull(visualMetadata);
        assertEquals(2, visualMetadata.size());
        assertEquals("elem-1", visualMetadata.get(0).get("elementId"));
        assertEquals(100, visualMetadata.get(0).get("x"));
        assertEquals(50, visualMetadata.get(0).get("y"));
        assertEquals(120, visualMetadata.get(0).get("width"));
        assertEquals(55, visualMetadata.get(0).get("height"));

        // Verify nextSteps (AC #1)
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(2, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("get-element"));
        assertTrue(nextSteps.get(1).contains("get-relationships"));

        // Verify _meta
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals("42", meta.get("modelVersion"));
        assertEquals(3, meta.get("resultCount")); // 2 elements + 1 relationship
        assertEquals(3, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyContents_whenViewHasNoElements() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViewContents("view-empty");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        Map<String, Object> contents = (Map<String, Object>) envelope.get("result");
        assertEquals("view-empty", contents.get("viewId"));
        assertEquals("Empty View", contents.get("viewName"));
        assertFalse("viewpoint should be omitted from JSON when null",
                contents.containsKey("viewpoint"));

        List<Map<String, Object>> elements = (List<Map<String, Object>>) contents.get("elements");
        assertNotNull(elements);
        assertEquals(0, elements.size());

        List<Map<String, Object>> relationships = (List<Map<String, Object>>) contents.get("relationships");
        assertNotNull(relationships);
        assertEquals(0, relationships.size());

        List<Map<String, Object>> visualMetadata = (List<Map<String, Object>>) contents.get("visualMetadata");
        assertNotNull(visualMetadata);
        assertEquals(0, visualMetadata.size());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(0, meta.get("resultCount"));
        assertEquals(0, meta.get("totalCount"));
    }

    // ---- get-view-contents: Error Path Tests (AC #2) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnViewNotFoundError_whenViewIdInvalid() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViewContents("nonexistent");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("VIEW_NOT_FOUND", error.get("code"));
        assertNotNull(error.get("message"));
        assertTrue(((String) error.get("message")).contains("nonexistent"));
        assertTrue(((String) error.get("message")).contains("3 views")); // StubAccessor has 3 views
        assertEquals("Use get-views to list available view IDs", error.get("suggestedCorrection"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnModelNotLoadedError_whenNoModelForGetViewContents() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViewContents("view-1");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
        assertEquals("Open an ArchiMate model in ArchimateTool", error.get("suggestedCorrection"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInternalError_whenUnexpectedExceptionInGetViewContents() throws Exception {
        ExplodingAccessor accessor = new ExplodingAccessor();
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViewContents("view-1");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INTERNAL_ERROR", error.get("code"));
    }

    // ---- get-view-contents: Parameter Validation Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenViewIdMissing() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Invoke with empty args (no viewId key)
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-view-contents");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-view-contents", Collections.emptyMap());
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertEquals("Use get-views to discover available view IDs", error.get("suggestedCorrection"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenViewIdBlank() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetViewContents("   ");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenViewIdNotString() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-view-contents");
        Map<String, Object> args = Map.of("viewId", 12345);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-view-contents", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenArgumentsNullForGetViewContents() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-view-contents");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-view-contents", null);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- Field Selection Integration Tests (Story 5.2, Task 11.5-11.6) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnMinimalViews_whenFieldsMinimal() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("fields", "minimal");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-views", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(3, resultList.size());

        Map<String, Object> view = resultList.get(0);
        assertEquals("view-1", view.get("id"));
        assertEquals("Application Landscape", view.get("name"));
        // MINIMAL: only id and name
        assertNull("viewpointType should be excluded in minimal", view.get("viewpointType"));
        assertNull("folderPath should be excluded in minimal", view.get("folderPath"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldExcludeVisualMetadata_whenExcludeParam() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-view-contents");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("viewId", "view-1");
        args.put("exclude", List.of("visualMetadata"));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-view-contents", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> contents = (Map<String, Object>) envelope.get("result");
        assertNotNull(contents);
        assertEquals("view-1", contents.get("viewId"));
        assertNotNull("elements should still be present", contents.get("elements"));
        assertNotNull("relationships should still be present", contents.get("relationships"));
        assertNull("visualMetadata should be excluded", contents.get("visualMetadata"));
    }

    // ---- Constructor Validation ----

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullAccessor() {
        new ViewHandler(null, formatter, registry, null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullFormatter() {
        new ViewHandler(new StubAccessor(true), null, registry, null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullRegistry() {
        new ViewHandler(new StubAccessor(true), formatter, null, null);
    }

    // ---- Story 5.3: Model Version Change Detection Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotIncludeModelChanged_whenVersionStable() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version, no change
        McpSchema.CallToolResult result = invokeGetViews(null);
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse("modelChanged should not be present on first call",
                meta.containsKey("modelChanged"));

        // Second call — same version, no change
        McpSchema.CallToolResult result2 = invokeGetViews(null);
        Map<String, Object> envelope2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) envelope2.get("_meta");
        assertFalse("modelChanged should not be present when version unchanged",
                meta2.containsKey("modelChanged"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeModelChanged_whenVersionChanges_getViews() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version
        invokeGetViews(null);

        // Bump version
        accessor.setVersion("43");

        // Second call — detects change
        McpSchema.CallToolResult result = invokeGetViews(null);
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("modelChanged"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeModelChanged_whenVersionChanges_getViewContents() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version
        invokeGetViewContents("view-1");

        // Bump version
        accessor.setVersion("43");

        // Second call — detects change
        McpSchema.CallToolResult result = invokeGetViewContents("view-1");
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("modelChanged"));
    }

    // ---- Story 5.4: Session Cache Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldCacheGetViewsResult_andReturnCacheHitOnSecondCall() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — cache miss
        McpSchema.CallToolResult result1 = invokeGetViews(null);
        Map<String, Object> env1 = parseJson(result1);
        Map<String, Object> meta1 = (Map<String, Object>) env1.get("_meta");
        assertFalse("First call should not have cacheHit", meta1.containsKey("cacheHit"));
        assertEquals(1, accessor.getViewsCount);

        // Second call — cache hit
        McpSchema.CallToolResult result2 = invokeGetViews(null);
        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) env2.get("_meta");
        assertEquals("Second call should have cacheHit", true, meta2.get("cacheHit"));
        assertEquals("Accessor should not be called again", 1, accessor.getViewsCount);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldCacheGetViewContentsResult_andReturnCacheHitOnSecondCall() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — cache miss
        McpSchema.CallToolResult result1 = invokeGetViewContents("view-1");
        Map<String, Object> env1 = parseJson(result1);
        Map<String, Object> meta1 = (Map<String, Object>) env1.get("_meta");
        assertFalse("First call should not have cacheHit", meta1.containsKey("cacheHit"));
        assertEquals(1, accessor.getViewContentsCount);

        // Second call — cache hit
        McpSchema.CallToolResult result2 = invokeGetViewContents("view-1");
        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) env2.get("_meta");
        assertEquals("Second call should have cacheHit", true, meta2.get("cacheHit"));
        assertEquals("Accessor should not be called again", 1, accessor.getViewContentsCount);
    }

    // ---- Story 6.2: DryRun Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnDryRunEstimate_forGetViews() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new HashMap<>();
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        assertNotNull("dryRun key must be present", dryRun);
        assertEquals(3, dryRun.get("estimatedResultCount"));
        assertTrue((int) dryRun.get("estimatedTokens") > 0);
        assertNotNull(dryRun.get("recommendedPreset"));
        assertNotNull(dryRun.get("recommendation"));

        assertFalse("No result key in dryRun response", envelope.containsKey("result"));

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("dryRun"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnDryRunEstimate_forGetViewContents() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-view-contents");
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-view-contents", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        assertNotNull("dryRun key must be present", dryRun);
        // view-1 has 2 elements + 1 relationship = 3 total
        assertEquals(3, dryRun.get("estimatedResultCount"));
        assertTrue((int) dryRun.get("estimatedTokens") > 0);
        assertNotNull(dryRun.get("recommendedPreset"));

        assertFalse("No result key in dryRun response", envelope.containsKey("result"));

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("dryRun"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIgnoreCursorAndLimit_forGetViewsDryRun() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new HashMap<>();
        args.put("dryRun", true);
        args.put("limit", 1);
        args.put("cursor", "invalid-cursor");
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertFalse("dryRun should not error on invalid cursor", result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        assertEquals(3, dryRun.get("estimatedResultCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnViewNotFound_whenDryRunWithInvalidViewId() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-view-contents");
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "nonexistent");
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-view-contents", args));

        assertTrue("Invalid viewId should still error in dryRun", result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("VIEW_NOT_FOUND", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveDryRunParameter_inGetViewsSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-views").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertTrue("get-views should have dryRun property", properties.containsKey("dryRun"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveDryRunParameter_inGetViewContentsSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-view-contents").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertTrue("get-view-contents should have dryRun property", properties.containsKey("dryRun"));
    }

    // ---- Story 6.4: Name Filtering Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFilterViewsByName_caseInsensitive() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("name", "app");
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, views.size());
        assertEquals("Application Landscape", views.get(0).get("name"));

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(1, meta.get("totalCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFilterViewsByName_andViewpoint() throws Exception {
        // "Application Landscape" has viewpoint "Application Usage"
        // name="landscape" + viewpoint="Application Usage" → only 1 match (AND logic)
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("name", "landscape");
        args.put("viewpoint", "Application Usage");
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, views.size());
        assertEquals("Application Landscape", views.get(0).get("name"));

        // Now try name + viewpoint that don't overlap
        Map<String, Object> args2 = new java.util.HashMap<>();
        args2.put("name", "landscape");
        args2.put("viewpoint", "Technology Usage");
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args2));

        assertFalse(result2.isError());
        Map<String, Object> envelope2 = parseJson(result2);
        List<Map<String, Object>> views2 = (List<Map<String, Object>>) envelope2.get("result");
        assertEquals(0, views2.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyResult_whenNameMatchesNoViews() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("name", "nonexistent");
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(0, views.size());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(0, meta.get("totalCount"));

        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.get(0).contains("nonexistent"));
        assertTrue(nextSteps.get(0).contains("search-elements"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnAllViews_whenNameNotProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // No name parameter at all
        McpSchema.CallToolResult result = invokeGetViews(null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(3, views.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnAllViews_whenNameIsEmpty() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("name", "");
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> views = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(3, views.size());
    }

    @Test
    public void shouldIncludeNameInCacheKey() throws Exception {
        CountingAccessor countingAccessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ViewHandler handler = new ViewHandler(countingAccessor, formatter, registry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");

        // Call with name="app"
        Map<String, Object> args1 = new java.util.HashMap<>();
        args1.put("name", "app");
        spec.callHandler().apply(null, new McpSchema.CallToolRequest("get-views", args1));
        assertEquals(1, countingAccessor.getViewsCount);

        // Call again with same name — should hit cache
        spec.callHandler().apply(null, new McpSchema.CallToolRequest("get-views", args1));
        assertEquals("Same name should hit cache", 1, countingAccessor.getViewsCount);

        // Call with different name — should miss cache
        Map<String, Object> args2 = new java.util.HashMap<>();
        args2.put("name", "infra");
        spec.callHandler().apply(null, new McpSchema.CallToolRequest("get-views", args2));
        assertEquals("Different name should miss cache", 2, countingAccessor.getViewsCount);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldStoreNameInCursor() throws Exception {
        // Create 5 views, 3 matching name filter, use limit=2 to paginate
        List<ViewDto> views = List.of(
                new ViewDto("v-1", "App View 1", null, "Views"),
                new ViewDto("v-2", "App View 2", null, "Views"),
                new ViewDto("v-3", "App View 3", null, "Views"),
                new ViewDto("v-4", "Other View", null, "Views"),
                new ViewDto("v-5", "Another", null, "Views"));
        StubAccessor accessor = new StubAccessor(true, views);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");

        // First page: name="App", limit=2
        Map<String, Object> args1 = new java.util.HashMap<>();
        args1.put("name", "App");
        args1.put("limit", 2);
        McpSchema.CallToolResult result1 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args1));
        Map<String, Object> env1 = parseJson(result1);
        List<Map<String, Object>> page1 = (List<Map<String, Object>>) env1.get("result");
        assertEquals(2, page1.size());
        assertEquals("App View 1", page1.get(0).get("name"));
        assertEquals("App View 2", page1.get(1).get("name"));

        Map<String, Object> meta1 = (Map<String, Object>) env1.get("_meta");
        assertEquals(3, meta1.get("totalCount"));
        String cursor = (String) meta1.get("cursor");
        assertNotNull("Cursor should be present for paginated name-filtered results", cursor);

        // Second page: use cursor (name stored in cursor)
        Map<String, Object> args2 = new java.util.HashMap<>();
        args2.put("cursor", cursor);
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args2));
        Map<String, Object> env2 = parseJson(result2);
        List<Map<String, Object>> page2 = (List<Map<String, Object>>) env2.get("result");
        assertEquals(1, page2.size());
        assertEquals("App View 3", page2.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeNameInDryRunEstimate() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("name", "app");
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        assertNotNull(dryRun);
        // name="app" matches only "Application Landscape" (1 of 3 default views)
        assertEquals(1, dryRun.get("estimatedResultCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveNameInToolSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-views").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertTrue("get-views should have name property", properties.containsKey("name"));

        Map<String, Object> nameProp = (Map<String, Object>) properties.get("name");
        assertEquals("string", nameProp.get("type"));
        assertNotNull(nameProp.get("description"));
        assertTrue(((String) nameProp.get("description")).contains("case-insensitive"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptySummary_whenNameMatchesNoViews() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("name", "nonexistent");
        args.put("format", "summary");
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        String summary = (String) envelope.get("summary");
        assertNotNull(summary);
        assertTrue("Summary should mention the name filter",
                summary.contains("nonexistent"));

        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("nextSteps should mention the name filter",
                nextSteps.get(0).contains("nonexistent"));
        assertTrue("nextSteps should suggest search-elements",
                nextSteps.get(0).contains("search-elements"));
    }

    @Test
    public void shouldIncludeNameContextInSummary() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("name", "app");
        args.put("format", "summary");
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-views", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        String summary = (String) envelope.get("summary");
        assertNotNull(summary);
        assertTrue("Summary should mention the name filter",
                summary.contains("matching 'app'"));
    }

    // ---- Helper Methods ----

    private McpSchema.CallToolResult invokeGetViews(String viewpoint) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-views");
        Map<String, Object> args = (viewpoint != null)
                ? Map.of("viewpoint", viewpoint)
                : Collections.emptyMap();
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-views", args);
        return spec.callHandler().apply(null, request);
    }

    private McpSchema.CallToolResult invokeGetViewContents(String viewId) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-view-contents");
        Map<String, Object> args = (viewId != null)
                ? Map.of("viewId", viewId)
                : Collections.emptyMap();
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-view-contents", args);
        return spec.callHandler().apply(null, request);
    }

    private McpServerFeatures.SyncToolSpecification findToolSpec(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
    }

    private Map<String, Object> parseJson(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().get(0);
        return objectMapper.readValue(content.text(),
                new TypeReference<Map<String, Object>>() {});
    }

    // ---- update-view tool registration tests (Story 8-7) ----

    @Test
    public void shouldRegisterThreeTools_includingUpdateView() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        assertEquals(3, registry.getToolCount());
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("update-view");
        assertEquals("update-view", spec.tool().name());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveViewIdAsRequiredParam_inUpdateViewSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("update-view").tool();
        assertTrue(tool.description().startsWith("[Mutation]"));
        Map<String, Object> properties = tool.inputSchema().properties();
        assertTrue(properties.containsKey("viewId"));
        assertTrue(properties.containsKey("name"));
        assertTrue(properties.containsKey("viewpoint"));
        assertTrue(properties.containsKey("documentation"));
        assertTrue(properties.containsKey("properties"));
        assertTrue(tool.inputSchema().required().contains("viewId"));
        assertEquals(1, tool.inputSchema().required().size());
    }

    // ---- update-view success tests (Story 8-7) ----

    @Test
    public void shouldReturnUpdatedViewDto_whenNameUpdated() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("name", "Updated View Name");
        Map<String, Object> result = callUpdateViewAndParse(handler, args);

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) result.get("result");
        assertEquals("view-1", entity.get("id"));
        assertEquals("Updated View Name", entity.get("name"));
    }

    @Test
    public void shouldReturnUpdatedViewDto_whenViewpointUpdated() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("viewpoint", "layered");
        Map<String, Object> result = callUpdateViewAndParse(handler, args);

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) result.get("result");
        assertEquals("view-1", entity.get("id"));
        assertEquals("layered", entity.get("viewpointType"));
    }

    @Test
    public void shouldPassEmptyStringViewpoint_whenClearingViewpoint() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        // Capture the viewpoint value actually passed to the accessor
        final String[] capturedViewpoint = {null};
        accessor.setUpdateViewBehavior((sessionId, id, name, viewpoint, doc, props,
                connRouterType) -> {
            capturedViewpoint[0] = viewpoint;
            ViewDto dto = new ViewDto(id, "Test View", null, "Views");
            return new MutationResult<>(dto, null);
        });
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("viewpoint", "");  // empty string = clear viewpoint
        callUpdateViewAndParse(handler, args);

        // The handler must preserve the empty string (not convert to null)
        assertEquals("", capturedViewpoint[0]);
    }

    @Test
    public void shouldReturnUpdatedViewDto_whenDocumentationUpdated() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("documentation", "Updated documentation");
        Map<String, Object> result = callUpdateViewAndParse(handler, args);

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) result.get("result");
        assertEquals("view-1", entity.get("id"));
        assertEquals("Updated documentation", entity.get("documentation"));
    }

    @Test
    public void shouldReturnUpdatedViewDto_whenPropertiesUpdated() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("properties", Map.of("status", "active"));
        Map<String, Object> result = callUpdateViewAndParse(handler, args);

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) result.get("result");
        assertEquals("view-1", entity.get("id"));
        @SuppressWarnings("unchecked")
        Map<String, String> returnedProps = (Map<String, String>) entity.get("properties");
        assertNotNull(returnedProps);
        assertEquals("active", returnedProps.get("status"));
    }

    @Test
    public void shouldIncludeNextSteps_whenUpdateViewSucceeds() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("name", "New Name");
        Map<String, Object> result = callUpdateViewAndParse(handler, args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-views")));
    }

    // ---- update-view error tests (Story 8-7) ----

    @Test
    public void shouldReturnError_whenViewNotFound() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        accessor.setUpdateViewBehavior((sessionId, id, name, viewpoint, doc, props,
                connRouterType) -> {
            throw new ModelAccessException("View not found: " + id, ErrorCode.VIEW_NOT_FOUND);
        });
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "nonexistent");
        args.put("name", "New Name");

        McpSchema.CallToolResult toolResult = callUpdateView(handler, args);
        assertTrue(toolResult.isError());
    }

    @Test
    public void shouldReturnError_whenAllFieldsNull() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        accessor.setUpdateViewBehavior((sessionId, id, name, viewpoint, doc, props,
                connRouterType) -> {
            throw new ModelAccessException(
                    "No fields to update", ErrorCode.INVALID_PARAMETER);
        });
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");

        McpSchema.CallToolResult toolResult = callUpdateView(handler, args);
        assertTrue(toolResult.isError());
    }

    // ---- update-view approval mode tests (Story 8-7 / AC8) ----

    @Test
    public void shouldReturnProposalInfo_whenApprovalModeEnabled() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        ProposalContext proposalCtx = new ProposalContext("p-view-1",
                "Update view: view-1", Instant.now());
        accessor.setUpdateViewBehavior((sessionId, id, name, viewpoint, doc, props,
                connRouterType) -> {
            ViewDto dto = new ViewDto(id, name != null ? name : "Test View",
                    viewpoint, "Views");
            return new MutationResult<>(dto, null, proposalCtx);
        });
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("name", "Approved Name");
        Map<String, Object> result = callUpdateViewAndParse(handler, args);

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) result.get("result");
        assertNotNull("Should have proposal info", entity.get("proposal"));
    }

    // ---- update-view batch mode tests (Story 8-7) ----

    @Test
    public void shouldReturnBatchInfo_whenUpdateViewInBatchMode() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        accessor.setBatchMode(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("name", "Batched Name");
        Map<String, Object> result = callUpdateViewAndParse(handler, args);

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) result.get("result");
        assertNotNull("Should have batch info", entity.get("batch"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("batch")));
    }

    // ---- update-view connectionRouterType tests (Story 9-0c) ----

    @Test
    public void shouldPassConnectionRouterType_whenProvided() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        final String[] capturedRouterType = {null};
        accessor.setUpdateViewBehavior((sessionId, id, name, viewpoint, doc, props,
                connRouterType) -> {
            capturedRouterType[0] = connRouterType;
            ViewDto dto = new ViewDto(id, "Test View", null, "manhattan", "Views", null, null);
            return new MutationResult<>(dto, null);
        });
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("connectionRouterType", "manhattan");
        callUpdateViewAndParse(handler, args);

        assertEquals("manhattan", capturedRouterType[0]);
    }

    @Test
    public void shouldPassNullRouterType_whenOmitted() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        final String[] capturedRouterType = {"SENTINEL"};
        accessor.setUpdateViewBehavior((sessionId, id, name, viewpoint, doc, props,
                connRouterType) -> {
            capturedRouterType[0] = connRouterType;
            ViewDto dto = new ViewDto(id, "Test View", null, "Views");
            return new MutationResult<>(dto, null);
        });
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("name", "Updated Name");
        // connectionRouterType NOT provided
        callUpdateViewAndParse(handler, args);

        assertNull("Router type should be null when omitted", capturedRouterType[0]);
    }

    @Test
    public void shouldPassEmptyStringRouterType_whenClearing() throws Exception {
        MutationStubAccessor accessor = new MutationStubAccessor();
        final String[] capturedRouterType = {null};
        accessor.setUpdateViewBehavior((sessionId, id, name, viewpoint, doc, props,
                connRouterType) -> {
            capturedRouterType[0] = connRouterType;
            ViewDto dto = new ViewDto(id, "Test View", null, "Views");
            return new MutationResult<>(dto, null);
        });
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("connectionRouterType", "");  // empty string = clear/revert
        callUpdateViewAndParse(handler, args);

        assertEquals("", capturedRouterType[0]);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveConnectionRouterTypeInUpdateViewSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("update-view").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertTrue("update-view should have connectionRouterType property",
                properties.containsKey("connectionRouterType"));

        Map<String, Object> routerProp = (Map<String, Object>) properties.get("connectionRouterType");
        assertEquals("string", routerProp.get("type"));
        assertNotNull(routerProp.get("description"));
    }

    // ---- update-view helpers ----

    private McpSchema.CallToolResult callUpdateView(ViewHandler handler,
            Map<String, Object> args) throws Exception {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("update-view");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("update-view", args);
        return spec.callHandler().apply(null, request);
    }

    private Map<String, Object> callUpdateViewAndParse(ViewHandler handler,
            Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = callUpdateView(handler, args);
        assertFalse("Should not be error", result.isError());
        return parseJson(result);
    }

    // ---- Stub Implementations ----

    /**
     * Stub ArchiModelAccessor that returns canned view data or throws
     * NoModelLoadedException based on constructor flag.
     */
    private static class StubAccessor extends BaseTestAccessor {
        private final List<ViewDto> views;

        StubAccessor(boolean modelLoaded) {
            this(modelLoaded, createDefaultViews());
        }

        StubAccessor(boolean modelLoaded, List<ViewDto> views) {
            super(modelLoaded);
            this.views = views;
        }

        private static List<ViewDto> createDefaultViews() {
            return List.of(
                    new ViewDto("view-1", "Application Landscape", "Application Usage", "Views"),
                    new ViewDto("view-2", "Business Processes", null, "Views/Business"),
                    new ViewDto("view-3", "Infrastructure Overview", "Technology Usage", "Views/Technology"));
        }

        @Override
        public List<ViewDto> getViews(String viewpointFilter) {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            if (viewpointFilter == null) {
                return views;
            }
            return views.stream()
                    .filter(v -> viewpointFilter.equals(v.viewpointType()))
                    .toList();
        }

        @Override
        public ModelInfoDto getModelInfo() {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            return new ModelInfoDto("Test Model", 10, 5, views.size(), Map.of(), Map.of(), Map.of());
        }

        @Override
        public Optional<ViewContentsDto> getViewContents(String viewId) {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            if ("view-1".equals(viewId)) {
                List<ElementDto> elements = List.of(
                        ElementDto.standard("elem-1", "Customer Portal", "ApplicationComponent",
                                "Application", "Main web app", List.of()),
                        ElementDto.standard("elem-2", "API Gateway", "ApplicationComponent",
                                "Application", "REST API gateway", List.of()));
                List<RelationshipDto> relationships = List.of(
                        new RelationshipDto("rel-1", "Serves", "ServingRelationship", "elem-1", "elem-2"));
                List<ViewNodeDto> visualMetadata = List.of(
                        new ViewNodeDto("vo-1", "elem-1", 100, 50, 120, 55),
                        new ViewNodeDto("vo-2", "elem-2", 300, 50, 120, 55));
                List<ViewConnectionDto> connections = List.of(
                        new ViewConnectionDto("vc-1", "rel-1", "ServingRelationship",
                                "vo-1", "vo-2", List.of(new BendpointDto(60, 0, -60, 0))),
                        new ViewConnectionDto("vc-2", "rel-1", "ServingRelationship",
                                "vo-1", "vo-2", List.of()));
                return Optional.of(new ViewContentsDto(
                        "view-1", "Application Landscape", "Application Usage",
                        elements, relationships, visualMetadata, connections));
            }
            if ("view-empty".equals(viewId)) {
                return Optional.of(new ViewContentsDto(
                        "view-empty", "Empty View", null,
                        List.of(), List.of(), List.of(), List.of()));
            }
            return Optional.empty();
        }

    }

    /**
     * Stub accessor with mutable model version for testing version change detection.
     */
    private static class VersionBumpAccessor extends StubAccessor {
        private String version = "42";

        VersionBumpAccessor() {
            super(true);
        }

        @Override
        public String getModelVersion() {
            return version;
        }

        void setVersion(String version) {
            this.version = version;
        }
    }

    /**
     * Accessor that throws RuntimeException to test unexpected error handling.
     */
    private static class ExplodingAccessor extends StubAccessor {
        ExplodingAccessor() {
            super(true);
        }

        @Override
        public List<ViewDto> getViews(String viewpointFilter) {
            throw new RuntimeException("Simulated EMF explosion");
        }

        @Override
        public Optional<ViewContentsDto> getViewContents(String viewId) {
            throw new RuntimeException("Simulated EMF explosion");
        }
    }

    /**
     * Stub accessor that counts method invocations for cache hit/miss verification (Story 5.4).
     */
    private static class CountingAccessor extends StubAccessor {
        int getViewsCount = 0;
        int getViewContentsCount = 0;

        CountingAccessor() {
            super(true);
        }

        @Override
        public List<ViewDto> getViews(String viewpointFilter) {
            getViewsCount++;
            return super.getViews(viewpointFilter);
        }

        @Override
        public Optional<ViewContentsDto> getViewContents(String viewId) {
            getViewContentsCount++;
            return super.getViewContents(viewId);
        }
    }

    // ---- MutationStubAccessor for update-view tests (Story 8-7) ----

    @FunctionalInterface
    interface UpdateViewBehavior {
        MutationResult<ViewDto> apply(String sessionId, String id, String name,
                String viewpoint, String documentation, Map<String, String> properties,
                String connectionRouterType);
    }

    private static class MutationStubAccessor extends StubAccessor {

        private final StubMutationDispatcher dispatcher;
        private boolean batchMode = false;
        private UpdateViewBehavior updateViewBehavior;

        MutationStubAccessor() {
            super(true);
            this.dispatcher = new StubMutationDispatcher();
            resetBehaviors();
        }

        void setBatchMode(boolean batch) {
            this.batchMode = batch;
        }

        void setUpdateViewBehavior(UpdateViewBehavior behavior) {
            this.updateViewBehavior = behavior;
        }

        private void resetBehaviors() {
            this.updateViewBehavior = (sessionId, id, name, viewpoint, doc, props,
                    connectionRouterType) -> {
                String displayName = name != null ? name : "Test View";
                String vp = viewpoint != null ? viewpoint : "Application Usage";
                ViewDto dto = new ViewDto(id, displayName, vp, null, "Views", doc, props);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
        }

        @Override
        public MutationResult<ViewDto> updateView(String sessionId, String id, String name,
                String viewpoint, String documentation, Map<String, String> properties,
                String connectionRouterType) {
            return updateViewBehavior.apply(sessionId, id, name, viewpoint, documentation,
                    properties, connectionRouterType);
        }

        @Override
        public MutationDispatcher getMutationDispatcher() {
            return dispatcher;
        }
    }

    private static class StubMutationDispatcher extends MutationDispatcher {

        StubMutationDispatcher() {
            super(() -> null);
        }

        @Override
        protected void dispatchCommand(Command command) throws MutationException {
            // no-op for handler tests
        }
    }
}
