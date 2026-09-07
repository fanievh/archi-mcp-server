package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import net.vheerden.archi.mcp.response.FieldSelector;
import net.vheerden.archi.mcp.response.ResponseFormatter;

import org.eclipse.gef.commands.Command;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.DiagramImageDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
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

    // ---- Tool Registration Tests ----

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

    // ---- Success Path Tests ----

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

        // Verify first view fields: id, name, viewpointType, folderPath
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

    // ---- Viewpoint Filter Tests ----

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

    // ---- Pagination Tests ----

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

    // ---- get-view-contents: Tool Registration Tests ----

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

    // ---- get-view-contents: Success Path Tests ----

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

        // Verify elements as ElementDto
        List<Map<String, Object>> elements = (List<Map<String, Object>>) contents.get("elements");
        assertNotNull(elements);
        assertEquals(2, elements.size());
        assertEquals("elem-1", elements.get(0).get("id"));
        assertEquals("Customer Portal", elements.get(0).get("name"));
        assertEquals("ApplicationComponent", elements.get(0).get("type"));
        assertEquals("Application", elements.get(0).get("layer"));

        // Verify relationships: RelationshipDto with sourceId, targetId, type, name
        List<Map<String, Object>> relationships = (List<Map<String, Object>>) contents.get("relationships");
        assertNotNull(relationships);
        assertEquals(1, relationships.size());
        assertEquals("rel-1", relationships.get(0).get("id"));
        assertEquals("Serves", relationships.get(0).get("name"));
        assertEquals("ServingRelationship", relationships.get(0).get("type"));
        assertEquals("elem-1", relationships.get(0).get("sourceId"));
        assertEquals("elem-2", relationships.get(0).get("targetId"));

        // Verify visual metadata: positions/sizes
        List<Map<String, Object>> visualMetadata = (List<Map<String, Object>>) contents.get("visualMetadata");
        assertNotNull(visualMetadata);
        assertEquals(2, visualMetadata.size());
        assertEquals("elem-1", visualMetadata.get(0).get("elementId"));
        assertEquals(100, visualMetadata.get(0).get("x"));
        assertEquals(50, visualMetadata.get(0).get("y"));
        assertEquals(120, visualMetadata.get(0).get("width"));
        assertEquals(55, visualMetadata.get(0).get("height"));

        // Verify nextSteps
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

    // ---- get-view-contents: Error Path Tests ----

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

    // ---- Field Selection Integration Tests ----

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

    // ---- Model Version Change Detection Tests ----

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

    // ---- Session Cache Tests ----

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

    // ---- DryRun Tests ----

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
    private int dryRunTokens(String tool, Map<String, Object> extraArgs, String preset) throws Exception {
        Map<String, Object> args = new HashMap<>(extraArgs);
        args.put("dryRun", true);
        args.put("fields", preset);
        McpSchema.CallToolResult result = findToolSpec(tool).callHandler()
                .apply(null, new McpSchema.CallToolRequest(tool, args));
        assertFalse(result.isError());
        Map<String, Object> dryRun = (Map<String, Object>) parseJson(result).get("dryRun");
        assertNotNull("dryRun key must be present at " + preset, dryRun);
        return ((Number) dryRun.get("estimatedTokens")).intValue();
    }

    @Test
    public void shouldRaiseDryRunEstimate_whenFieldsIsFull_forGetViews() throws Exception {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        // The full preset adds documentation and properties to a view row, so the dry-run
        // estimate must be larger than the same query at standard.
        Map<String, Object> none = new HashMap<>();
        int minimal = dryRunTokens("get-views", none, "minimal");
        int standard = dryRunTokens("get-views", none, "standard");
        int full = dryRunTokens("get-views", none, "full");
        assertTrue("minimal (" + minimal + ") must be cheaper than standard (" + standard + ")",
                minimal < standard);
        assertTrue("full (" + full + ") must exceed standard (" + standard + ")", standard < full);
    }

    @Test
    public void shouldRaiseDryRunEstimate_whenFieldsIsFull_forGetViewContents() throws Exception {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        // View contents carry relationships, whose full row is wider than the standard one,
        // so the estimate must follow the preset even though its element half cannot.
        Map<String, Object> viewArg = new HashMap<>();
        viewArg.put("viewId", "view-1");
        int minimal = dryRunTokens("get-view-contents", viewArg, "minimal");
        int standard = dryRunTokens("get-view-contents", viewArg, "standard");
        int full = dryRunTokens("get-view-contents", viewArg, "full");
        assertTrue("minimal (" + minimal + ") must be cheaper than standard (" + standard + ")",
                minimal < standard);
        assertTrue("full (" + full + ") must exceed standard (" + standard + ")", standard < full);
    }

    /**
     * The companion the ordering pin above needs, and the reason it needs one.
     *
     * <p>The ordering assertion was written when this estimate had two terms and both followed the
     * preset, so it was a check on essentially the whole quantity. Now five of the seven arrays
     * are charged and none of them moves with the preset, so the ordering still holds while the
     * part of the estimate it can see has shrunk to a minority of the total -- it would stay green
     * over a fix that charged the visual arrays at any width at all, including zero. A pin that
     * survives a change by becoming insensitive to it has not passed it.</p>
     *
     * <p>So this asserts the MAGNITUDE the ordering cannot: on a view that carries visual rows,
     * narrowing the preset reaches two arrays of seven and the saving is a minority of the
     * payload. Before the visual arrays were charged, this same view advertised a 50% saving; it
     * is really about 18%, and the difference is a reduction the caller was promised and did not
     * receive. The bound goes red if the visual terms stop being charged, which is the defect.</p>
     */
    /**
     * Every array the response carries is charged, pinned end to end through the handler.
     *
     * <p>The saving bound below cannot see one array going missing: with the connection term
     * still charged, dropping the node term moves the advertised saving by four points and stays
     * inside any bound loose enough to be a bound. Only the arithmetic itself distinguishes six
     * charged arrays from five, so the arithmetic is what this asserts.</p>
     */
    @Test
    public void shouldChargeEveryArrayTheResponseCarries_forGetViewContents() throws Exception {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        // view-1 returns 2 element rows, 1 relationship row, 2 visualMetadata rows and 2
        // connection rows -- two connections for the one relationship, because the element and
        // relationship arrays are deduplicated by concept id and the visual arrays are not. Its
        // groups, notes and images arrays are null, so those three terms contribute nothing.
        //
        //   standard: 2*250 + 1*185 + 2*205 + 2*640 + 280 = 2655 chars -> 664 tokens
        //   minimal : 2* 80 + 1* 55 + 2*205 + 2*640 + 280 = 2185 chars -> 547 tokens
        //   full    : 2*250 + 1*365 + 2*205 + 2*640 + 280 = 2835 chars -> 709 tokens
        //
        // The element width is the same at standard and full, so only the relationship half
        // separates those two.
        Map<String, Object> viewArg = new HashMap<>();
        viewArg.put("viewId", "view-1");
        assertEquals(547, dryRunTokens("get-view-contents", viewArg, "minimal"));
        assertEquals(664, dryRunTokens("get-view-contents", viewArg, "standard"));
        assertEquals(709, dryRunTokens("get-view-contents", viewArg, "full"));
    }

    /**
     * The three arrays that arrive null when empty, charged on a view that actually has them.
     *
     * <p>Every other view in this class is built with the convenience constructor that leaves
     * groups, notes and images null, which is why the three views the defect was originally
     * measured on showed four arrays rather than seven -- and why a fix that charged only the four
     * would have looked complete. It also pins the null-guard the other direction: on those other
     * views these three are null, and an unguarded {@code size()} would fail the dry-run branch
     * where it used to work.</p>
     */
    @Test
    public void shouldChargeGroupsNotesAndImages_whenTheViewCarriesThem() throws Exception {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        Map<String, Object> viewArg = new HashMap<>();
        viewArg.put("viewId", "view-decorated");
        // 1 element, no relationships, 1 node row, no connections, and one row in each of the
        // three arrays that are null on every other view here:
        //   1*250 + 1*205 + 1*435 + 1*430 + 1*345 + 280 = 1945 chars -> 487 tokens
        assertEquals(487, dryRunTokens("get-view-contents", viewArg, "standard"));

        Map<String, Object> excluded = new HashMap<>(viewArg);
        excluded.put("exclude", List.of("groups", "notes", "images"));
        // 1*250 + 1*205 + 280 = 735 chars -> 184 tokens
        assertEquals(184, dryRunTokens("get-view-contents", excluded, "standard"));
    }

    /**
     * The caller who followed the tool's own advice.
     *
     * <p>The dry-run suggestions recommend {@code exclude=['visualMetadata','connections']}, and
     * the two arrays that removes are two of the five this estimate now charges for. An estimate
     * blind to the exclusion would quote a caller for arrays they will not receive -- turning a
     * large under-report into a large over-report for exactly the caller who did the right thing.
     * Nothing else in this class exercises the dry-run branch with an exclusion, so without this
     * the handler could ignore the parameter entirely and every test would stay green.</p>
     */
    @Test
    public void shouldChargeNothingForAnExcludedArray_forGetViewContents() throws Exception {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        Map<String, Object> viewArg = new HashMap<>();
        viewArg.put("viewId", "view-1");
        Map<String, Object> excluded = new HashMap<>(viewArg);
        excluded.put("exclude", List.of("visualMetadata", "connections"));

        // Both visual arrays gone: 2*250 + 1*185 + 280 = 965 chars -> 242 tokens.
        assertEquals(242, dryRunTokens("get-view-contents", excluded, "standard"));
        assertTrue("excluding the two visual arrays must reduce the estimate",
                dryRunTokens("get-view-contents", excluded, "standard")
                        < dryRunTokens("get-view-contents", viewArg, "standard"));

        // And one at a time, so a fix that honours the parameter only when both are named fails.
        Map<String, Object> nodesOnly = new HashMap<>(viewArg);
        nodesOnly.put("exclude", List.of("visualMetadata"));
        // 2*250 + 1*185 + 2*640 + 280 = 2245 chars -> 562 tokens.
        assertEquals(562, dryRunTokens("get-view-contents", nodesOnly, "standard"));

        Map<String, Object> connectionsOnly = new HashMap<>(viewArg);
        connectionsOnly.put("exclude", List.of("connections"));
        // 2*250 + 1*185 + 2*205 + 280 = 1375 chars -> 344 tokens.
        assertEquals(344, dryRunTokens("get-view-contents", connectionsOnly, "standard"));
    }

    /**
     * The exclusion suggestion's gate, including the partial case nothing pinned before.
     *
     * <p>The middle two cases are the ones that decide the gate's shape, and they decide it
     * against tightening: a caller who has dropped only {@code visualMetadata} must still be
     * offered the exclusion, because a connection row is about three times a node row and is the
     * larger of the two savings. A conjunction of negations reads tidier and would withdraw the
     * suggestion the moment either array went, taking the bigger saving with it.</p>
     */
    @Test
    public void shouldOfferTheExclusionWhileEitherArrayIsStillReturned_forGetViewContents()
            throws Exception {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        String suggestion = "Use exclude=['visualMetadata','connections'] to omit position and routing data";

        assertTrue("a caller excluding nothing must be offered the exclusion",
                dryRunNextSteps(null).contains(suggestion));
        assertTrue("a caller who has dropped only visualMetadata must still be offered it -- the "
                + "connection rows they are still receiving are the wider of the two",
                dryRunNextSteps(List.of("visualMetadata")).contains(suggestion));
        assertTrue("and likewise a caller who has dropped only connections",
                dryRunNextSteps(List.of("connections")).contains(suggestion));
        assertFalse("but once both are gone there is nothing left to suggest",
                dryRunNextSteps(List.of("visualMetadata", "connections")).contains(suggestion));
    }

    @SuppressWarnings("unchecked")
    private List<String> dryRunNextSteps(List<String> exclude) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("dryRun", true);
        if (exclude != null) {
            args.put("exclude", exclude);
        }
        McpSchema.CallToolResult result = findToolSpec("get-view-contents").callHandler()
                .apply(null, new McpSchema.CallToolRequest("get-view-contents", args));
        assertFalse(result.isError());
        return (List<String>) parseJson(result).get("nextSteps");
    }

    @Test
    public void shouldNotAdvertiseAMajoritySaving_whenTheViewCarriesVisualRows() throws Exception {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        // view-1 draws 2 elements and 2 connections for 1 relationship. The preset reaches the
        // element and relationship rows only: 2*(250-80) + 1*(185-55) = 470 chars of the 2655 a
        // standard response costs, so a little under a fifth.
        Map<String, Object> viewArg = new HashMap<>();
        viewArg.put("viewId", "view-1");
        int minimal = dryRunTokens("get-view-contents", viewArg, "minimal");
        int standard = dryRunTokens("get-view-contents", viewArg, "standard");
        double saving = 1.0 - (minimal / (double) standard);
        assertTrue("narrowing the preset must still save something (minimal " + minimal
                + ", standard " + standard + ")", saving > 0);
        assertTrue("five of the seven arrays do not follow the preset, so the saving on a view "
                + "carrying visual rows must be a minority of the payload -- it was " + saving
                + ", which is the majority saving the two-term estimate used to advertise",
                saving < 0.30);
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

    // ---- Name Filtering Tests ----

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

    // ---- Read-payload width on get-view-contents, per preset ----------------------------

    /**
     * A view whose relationship carries every optional field populated.
     *
     * <p>The production mapper left all four hardcoded null until this surface's preset was
     * honoured, so nothing here could ever prove what {@code get-view-contents} does with them.
     * Supplying them is what pins this tool independently of the mapper — the nested
     * {@code relationships} array goes through the same {@code FieldSelector} path as
     * {@code get-relationships}, and must agree with it preset for preset.</p>
     */
    private static class WideViewContentsAccessor extends StubAccessor {
        WideViewContentsAccessor() {
            super(true);
        }

        @Override
        public Optional<ViewContentsDto> getViewContents(String viewId) {
            return Optional.of(new ViewContentsDto(
                    "view-1", "Application Landscape", "Application Usage",
                    List.of(ElementDto.standard("elem-1", "Customer Portal",
                            "ApplicationComponent", null, "Application", "Main web app", List.of())),
                    List.of(new RelationshipDto("rel-1", "Serves", "ServingRelationship",
                            "Critical", "elem-1", "elem-2", false,
                            "view relationship documentation",
                            List.of(Map.of("key", "owner", "value", "ops")),
                            "Customer Portal", "API Gateway", null, null, null)),
                    List.of(), List.of()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> viewContentsRelationshipRow(String fieldsPreset) throws Exception {
        registry = new CommandRegistry();
        ViewHandler handler = new ViewHandler(
                new WideViewContentsAccessor(), formatter, registry, null);
        handler.registerTools();
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        if (fieldsPreset != null) {
            args.put("fields", fieldsPreset);
        }
        Map<String, Object> envelope = parseJson(findToolSpec("get-view-contents").callHandler()
                .apply(null, new McpSchema.CallToolRequest("get-view-contents", args)));
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull("get-view-contents must return a result: " + envelope, result);
        List<Map<String, Object>> rels = (List<Map<String, Object>>) result.get("relationships");
        assertNotNull("and must carry a relationships array: " + envelope, rels);
        return rels.get(0);
    }

    @Test
    public void getViewContents_shouldOmitTheFourOptionalFields_atTheDefaultPreset() throws Exception {
        Map<String, Object> row = viewContentsRelationshipRow(null);

        assertEquals("the default preset decides the width of every relationship row this view "
                + "returns; it was: " + row.keySet(),
                java.util.Set.of("id", "name", "type", "sourceId", "targetId"), row.keySet());
    }

    @Test
    public void getViewContents_shouldOmitTheFourOptionalFields_atStandard() throws Exception {
        Map<String, Object> row = viewContentsRelationshipRow("standard");

        assertEquals("standard must stay identical to the default; it was: " + row.keySet(),
                java.util.Set.of("id", "name", "type", "sourceId", "targetId"), row.keySet());
    }

    @Test
    public void getViewContents_shouldReturnOnlyIdAndName_atMinimal() throws Exception {
        Map<String, Object> row = viewContentsRelationshipRow("minimal");

        assertEquals("minimal must stay two fields wide; it was: " + row.keySet(),
                java.util.Set.of("id", "name"), row.keySet());
    }

    @Test
    public void getViewContents_shouldDeliverTheFourOptionalFields_atFull() throws Exception {
        Map<String, Object> row = viewContentsRelationshipRow("full");

        assertTrue("full must deliver the fields its preset names; it was: " + row.keySet(),
                row.keySet().containsAll(java.util.Set.of(
                        "documentation", "properties", "sourceName", "targetName",
                        "specialization")));
        assertEquals("view relationship documentation", row.get("documentation"));
        assertEquals("Customer Portal", row.get("sourceName"));
        assertEquals("API Gateway", row.get("targetName"));
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

    // ---- update-view tool registration tests ----

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

    // ---- update-view success tests ----

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

    // ---- update-view error tests ----

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

    // ---- update-view approval mode tests ----

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

    // ---- update-view batch mode tests ----

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

    // ---- update-view connectionRouterType tests ----

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
            return new ModelInfoDto("Test Model", 10, 5, views.size(), 0, Map.of(), Map.of(), Map.of());
        }

        @Override
        public Optional<ViewContentsDto> getViewContents(String viewId) {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            if ("view-1".equals(viewId)) {
                List<ElementDto> elements = List.of(
                        ElementDto.standard("elem-1", "Customer Portal", "ApplicationComponent",
                                null, "Application", "Main web app", List.of()),
                        ElementDto.standard("elem-2", "API Gateway", "ApplicationComponent",
                                null, "Application", "REST API gateway", List.of()));
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
            if ("view-decorated".equals(viewId)) {
                // The three arrays that arrive null when a view has none of that object. Every
                // other view here uses the convenience constructor that leaves all three null, so
                // without this view their terms are unreachable from a handler test and an
                // estimate that dropped them would look correct.
                return Optional.of(new ViewContentsDto(
                        "view-decorated", "Decorated View", null, "manual",
                        List.of(ElementDto.standard("elem-1", "Customer Portal",
                                "ApplicationComponent", null, "Application", "Main web app",
                                List.of())),
                        List.of(),
                        List.of(new ViewNodeDto("vo-1", "elem-1", 100, 50, 120, 55)),
                        List.of(),
                        List.of(new ViewGroupDto("grp-1", "Channels", 20, 20, 400, 300, null,
                                List.of("vo-1"))),
                        List.of(new ViewNoteDto("note-1", "Reviewed 2026-09", 460, 20, 200, 80,
                                null)),
                        List.of(new DiagramImageDto("img-1", "images/_BRJagKIjEfGmoYmHSR0RJQ.png",
                                700, 20, 180, 120, null, null, null))));
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
     * Stub accessor that counts method invocations for cache hit/miss verification.
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

    // ---- MutationStubAccessor for update-view tests ----

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

    // ---- The reach of the 'fields' preset over the response's seven arrays -----------------------

    /**
     * The clause that carries the disclosure, held as one constant so the pin and its two mutation
     * directions all move together.
     *
     * <p>It names the boundary itself — which rows the preset reaches — rather than any one array,
     * because a caller who reads only "element and relationship rows" has already been told what
     * {@code minimal} will and will not shrink.</p>
     */
    private static final String PRESET_REACH_CLAUSE =
            "the preset reaches the element and relationship rows only";

    /** The five arrays the preset does not reach, in the order the response emits them. */
    private static final List<String> VISUAL_ARRAYS =
            List.of("visualMetadata", "connections", "groups", "notes", "images");

    /**
     * The sentence both tool-description pins anchor on before asserting anything.
     *
     * <p>Single-sourced deliberately: two methods guard prose in the same passage, and a copy of the
     * anchor in each is a copy that can be re-pointed in one and left stale in the other, leaving a
     * "the prose has moved" message pointing at prose that did not move.</p>
     */
    private static final String PROJECTION_ANCHOR = "Use 'fields' to control response verbosity";

    /**
     * The reach clause lives in the {@code fields} property description, where the caller picks the
     * preset — not merely somewhere in the served text.
     *
     * <p>Asserted against the property description <em>alone</em>. A clause that migrated up into
     * the tool description would still be served, and would still read as true, but it would no
     * longer sit beside the parameter whose reach it qualifies — which is exactly where the caller
     * who set {@code fields: "minimal"} and then measured the payload was looking.</p>
     */
    @Test
    public void shouldStateTheReachOfThePreset_insideTheFieldsPropertyDescription() {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        String fieldsDescription = propertyDescriptionOf("get-view-contents", "fields");

        assertTrue("The 'fields' description no longer describes a verbosity preset at all, so the "
                + "assertions below are guarding prose that has moved. Re-point them — do not "
                + "delete them. It read: " + fieldsDescription,
                fieldsDescription.contains("Field verbosity preset"));

        assertTrue("The 'fields' description does not say which of the response's seven arrays the "
                + "preset reaches. A caller sizing a large view reads this string to decide, and "
                + "'minimal returns only id and name' reads as covering the whole response when it "
                + "governs two arrays of seven. Expected: \"" + PRESET_REACH_CLAUSE + "\". It read: "
                + fieldsDescription,
                fieldsDescription.toLowerCase(java.util.Locale.ROOT).contains(PRESET_REACH_CLAUSE));

        List<String> unnamed = new ArrayList<>();
        for (String array : VISUAL_ARRAYS) {
            if (!fieldsDescription.contains(array)) {
                unnamed.add(array);
            }
        }
        assertTrue("The reach clause has to name the arrays it excludes, or the caller cannot tell "
                + "which part of the payload 'exclude' is for. Unnamed: " + unnamed,
                unnamed.isEmpty());

        assertTrue("The reach clause states what the preset does NOT move without naming a "
                + "parameter that does, which leaves the caller with a diagnosis and no remedy.",
                fieldsDescription.contains("Use exclude"));

        assertTrue("The reach clause is stated as a fact about the preset, but handleGetViewContents "
                + "returns before the field selector runs on format=summary and format=tree, so on "
                + "those two the preset reaches NOTHING — not even the rows this clause says it does. "
                + "An unscoped sentence is false on half the format enum.",
                fieldsDescription.contains("format=summary") && fieldsDescription.contains("format=tree"));
    }

    /**
     * The negative control, and the reason it exists: a planning row asked for the sentence "there
     * is no field projection" to be published here. Both parameters have been declared and
     * documented on this tool throughout, so that sentence would have been false at the moment it
     * was written.
     *
     * <p><strong>Reach limit.</strong> This pin matches known spellings of the claim, not the claim
     * in every possible sentence form — there is no general reader for "asserts an absence" over
     * English prose. It covers the row's own wording, the two paraphrases nearest to it, and the
     * bare-negation form. A future rewording could evade it; the sibling reach pin above is what
     * makes such a sentence contradict the surface rather than merely sit beside it.</p>
     */
    @Test
    public void shouldNeverClaimTheToolHasNoFieldProjection_onAnyServedSurface() {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        String surface = servedSurfaceOf("get-view-contents");

        assertTrue("get-view-contents no longer publishes its field-selection parameters at all, "
                + "so this absence check would pass vacuously. Re-point it — do not delete it.",
                surface.contains("'fields'") && surface.contains("'exclude'"));

        List<String> falseClaims = List.of(
                "no field projection",
                "does not support fields",
                "has no exclude",
                "has no 'exclude'",
                "does not support field selection",
                "no field selection",
                "there is no projection",
                "does not project fields",
                "cannot select fields",
                "field selection is unsupported",
                "field selection is not available",
                "field selection is not supported",
                "without field selection");
        List<String> found = new ArrayList<>();
        for (String claim : falseClaims) {
            if (surface.toLowerCase(java.util.Locale.ROOT).contains(claim)) {
                found.add(claim);
            }
        }

        assertTrue("A served surface of get-view-contents claims the tool has no field projection. "
                + "It has had both 'fields' and 'exclude' since they were declared, so this is a "
                + "false statement reaching an LLM agent that is being asked to act on it. Found: "
                + found, found.isEmpty());
    }

    /**
     * The mechanism, not the sentence.
     *
     * <p>A phrase pin goes red when someone deletes the disclosure. It stays green when someone
     * makes the disclosure false — routes one of the five visual arrays through the preset, and the
     * published sentence quietly stops describing the code. This drives the real
     * {@link FieldSelector} at two presets and asserts the boundary itself: the five visual arrays
     * come back identical, and the two preset-aware arrays do not.</p>
     *
     * <p>Every one of the seven arrays is populated in the fixture. An empty array is identical to
     * itself at every preset, so a fixture that left any of the five empty would assert the
     * boundary vacuously for that array.</p>
     *
     * <p>All three presets are visited, not the two that bracket the interesting case. The
     * published sentence says the five arrays come back in full at <em>every</em> preset, and a
     * change that enriched them at {@code full} alone would leave {@code minimal} and
     * {@code standard} agreeing with each other while the sentence stopped being true.</p>
     */
    @Test
    public void shouldReturnTheFiveVisualArraysUnchanged_acrossEveryPreset() {
        ViewContentsDto dto = sevenArrayViewContents();

        Map<String, Object> minimal = viewContentsAt(dto, FieldSelector.FieldPreset.MINIMAL);
        Map<String, Object> standard = viewContentsAt(dto, FieldSelector.FieldPreset.STANDARD);
        Map<String, Object> full = viewContentsAt(dto, FieldSelector.FieldPreset.FULL);

        for (String array : VISUAL_ARRAYS) {
            assertNotNull("the fixture must populate " + array + " — an absent array is identical "
                    + "at every preset and would pin nothing", standard.get(array));
            assertEquals("The published 'fields' description tells a caller that " + array
                    + " comes back in full at every preset. It differed between minimal and "
                    + "standard, so that sentence is now false. Either the disclosure or this "
                    + "change is wrong; do not simply re-point the assertion.",
                    standard.get(array), minimal.get(array));
            assertEquals("The published 'fields' description says every preset, and " + array
                    + " differed at full. A preset now reaches a visual array, so the sentence is "
                    + "false at one end of the range even though the other two presets agree.",
                    standard.get(array), full.get(array));
        }

        assertNotEquals("the preset must still reach the element rows — if it reaches nothing, the "
                + "clause naming what it does reach is no longer true either",
                standard.get("elements"), minimal.get("elements"));
        assertNotEquals("and the relationship rows, the other half of the documented reach",
                standard.get("relationships"), minimal.get("relationships"));
    }

    /**
     * The arm the populated fixture cannot reach: three of the five arrays arrive <em>null</em>, not
     * empty.
     *
     * <p>{@code ArchiModelAccessorImpl.getViewContents} maps each of {@code groups}, {@code notes}
     * and {@code images} through {@code isEmpty() ? null : …}, so a view carrying none of that
     * object hands the selector a null — and the selector's outer {@code if (dto.X() != null)} guard
     * then omits the key entirely. That is the shape of most real views: the live gate for this
     * change measured a 47-element view whose {@code notes} and {@code images} came back absent.</p>
     *
     * <p>The published sentence has to hold there too. Pinned as <em>absent at every preset</em>
     * rather than as equal values, because a key that is missing from all three maps is exactly what
     * "returned in full at every preset" means when there is nothing to return — and because a
     * change making the null guard preset-dependent (say {@code dto.groups() != null && preset !=
     * MINIMAL}) would leave the populated fixture green while the disclosure quietly went false.</p>
     */
    @Test
    public void shouldTreatAnAbsentVisualArrayIdentically_atEveryPreset() {
        ViewContentsDto dto = new ViewContentsDto(
                "view-1", "Application Landscape", "Application Usage", "manual",
                List.of(ElementDto.standard("elem-1", "Customer Portal", "ApplicationComponent",
                        null, "Application", "Main web app", List.of())),
                List.of(new RelationshipDto("rel-1", "Serves", "ServingRelationship", "Critical",
                        "elem-1", "elem-2", false, "doc", List.of(Map.of("key", "k", "value", "v")),
                        "Customer Portal", "API Gateway", null, null, null)),
                List.of(new ViewNodeDto("vo-1", "elem-1", 100, 50, 120, 55)),
                List.of(new ViewConnectionDto("vc-1", "rel-1", "ServingRelationship",
                        "vo-1", "vo-2", List.of())),
                null, null, null);

        assertNull("the fixture must hand the selector a NULL groups array — an empty list is a "
                + "different arm and is covered by the populated fixture", dto.groups());

        Map<String, Object> minimal = viewContentsAt(dto, FieldSelector.FieldPreset.MINIMAL);
        Map<String, Object> standard = viewContentsAt(dto, FieldSelector.FieldPreset.STANDARD);
        Map<String, Object> full = viewContentsAt(dto, FieldSelector.FieldPreset.FULL);

        for (String array : List.of("groups", "notes", "images")) {
            assertFalse("An absent " + array + " array must stay absent at minimal. If a preset can "
                    + "add or drop the key, the published sentence about what every preset returns "
                    + "is false on the view shape most callers actually have.",
                    minimal.containsKey(array));
            assertFalse("and at standard", standard.containsKey(array));
            assertFalse("and at full", full.containsKey(array));
        }

        for (String array : List.of("visualMetadata", "connections")) {
            assertNotNull("the fixture must still populate " + array + ", or this test would assert "
                    + "nothing about the arrays that ARE present alongside the absent ones",
                    standard.get(array));
            assertEquals(array + " is present on this view and must still be preset-independent",
                    standard.get(array), minimal.get(array));
            assertEquals(array + " must be preset-independent at the full end too",
                    standard.get(array), full.get(array));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> viewContentsAt(ViewContentsDto dto,
            FieldSelector.FieldPreset preset) {
        Object result = FieldSelector.applyFieldSelection(dto, preset, null);
        assertTrue("applyFieldSelection no longer returns a map for a ViewContentsDto, so the "
                + "assertions that read the arrays by name cannot run. Re-point them — do not "
                + "delete them. At " + preset + " it returned: " + result,
                result instanceof Map);
        return (Map<String, Object>) result;
    }

    /**
     * The other half of the disclosure, on the other surface.
     *
     * <p>The tool description coordinated the two parameters in one sentence — "use 'fields' to
     * control response verbosity and 'exclude' to omit specific fields" — which reads as two levers
     * on one axis. They are not: one narrows two arrays of seven, the other drops five. The reach
     * clause on the {@code fields} property is what a caller reads when picking a preset; this is
     * what they read when deciding whether a preset is the right instrument at all.</p>
     *
     * <p>Kept a separate method from the worked-example pin below deliberately. They fail for
     * different reasons — a missing qualification and a missing remedy — and folding them would let
     * either hide behind the other.</p>
     */
    @Test
    public void shouldQualifyTheProjectionSentence_inTheToolDescription() {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        String description = findToolSpec("get-view-contents").tool().description();

        assertTrue("The get-view-contents description no longer coordinates 'fields' and 'exclude' "
                + "at all, so this pin is guarding prose that has moved. Re-point it — do not "
                + "delete it.",
                description.contains(PROJECTION_ANCHOR));

        assertTrue("The description still presents 'fields' and 'exclude' as interchangeable levers "
                + "on one axis. A caller sizing a large view picks 'fields' on that reading and "
                + "narrows two arrays of seven. The description has to say that the preset reaches "
                + "the element and relationship rows only.",
                description.contains("'fields' narrows the element")
                        && description.contains("relationship rows only"));

        assertTrue("The description does not name 'exclude' as the parameter that moves response "
                + "size on a view with connections, which is the whole of the remedy — the "
                + "qualification without it tells the caller their instrument is wrong and not "
                + "which one is right.",
                description.contains("'exclude'")
                        && description.contains("moves the response size"));

        assertFalse("The size claim must stay HEDGED. Which parameter dominates depends on the "
                + "view: excluding connections beat narrowing the preset on the measured view, but "
                + "a connection-light view carrying heavily documented elements inverts that. An "
                + "unconditional 'exclude is THE parameter' is a claim the code cannot keep, and "
                + "pinning the unconditional spelling would turn a future correction red.",
                description.contains("'exclude' is the parameter that moves the response size"));

        assertTrue("format=summary and format=tree return before the field selector runs, so the "
                + "coordination sentence has to say both parameters are inert there.",
                description.contains("ignored by format=summary and format=tree"));
    }

    /**
     * The worked example that names the two widest arrays by name. It is the shortest path from
     * "my response is too big" to a call that fixes it, and it predates this story's reach clause.
     */
    @Test
    public void shouldKeepTheExcludeExample_inTheToolDescription() {
        new ViewHandler(new StubAccessor(true), formatter, registry, null).registerTools();
        String description = findToolSpec("get-view-contents").tool().description();

        assertTrue("The get-view-contents description no longer coordinates 'fields' and 'exclude' "
                + "at all, so this pin is guarding prose that has moved. Re-point it — do not "
                + "delete it.",
                description.contains(PROJECTION_ANCHOR));

        assertTrue("The worked exclude example has left the description. It names the two widest "
                + "arrays a view response carries and is the concrete half of the reach "
                + "disclosure — the clause that says 'exclude' moves the size is advice without it.",
                description.contains(
                        "Use exclude=['visualMetadata','connections'] to omit position and routing data"));
    }

    /**
     * The served format table, which an agent reads before it picks a format.
     *
     * <p>Its {@code json} row listed five of the seven arrays: it omitted {@code images}, and it
     * omitted {@code connections} — the widest row the response carries. A page whose job is
     * "which format should I use" understated the default format by leaving out its largest
     * component, and said "field selection needed" without saying what field selection reaches.</p>
     */
    @Test
    public void shouldNameAllSevenArrays_inTheServedFormatTable() {
        String page = readViewPatternsPage();

        assertTrue("The 'Which get-view-contents Format to Use' table has left "
                + "archimate-view-patterns.md, so this pin is guarding prose that has moved. "
                + "Re-point it — do not delete it.",
                page.contains("Which `get-view-contents` Format to Use"));

        String jsonRow = tableRowContaining(
                sectionOf(page, "Which `get-view-contents` Format to Use"), "`json` (default)");
        List<String> missing = new ArrayList<>();
        for (String array : List.of("elements", "relationships")) {
            if (!jsonRow.contains(array)) {
                missing.add(array);
            }
        }
        for (String array : VISUAL_ARRAYS) {
            if (!jsonRow.contains(array)) {
                missing.add(array);
            }
        }

        assertTrue("The json row of the served format table does not name all seven arrays the "
                + "format returns. An agent choosing a format off this page is told the default "
                + "returns less than it does, and the omitted names are the ones 'exclude' takes. "
                + "Missing: " + missing + ". The row read: " + jsonRow,
                missing.isEmpty());

        assertFalse("The row still says 'field selection needed' without saying what field "
                + "selection reaches — which is the phrase that sent a caller to 'fields' for a "
                + "saving only 'exclude' could deliver.",
                jsonRow.contains("field selection needed"));
    }

    // ---- harness for the reach pins ------------------------------------------------------------

    /** A view carrying all seven arrays, each non-empty, with rows the preset can visibly narrow. */
    private static ViewContentsDto sevenArrayViewContents() {
        List<ElementDto> elements = List.of(
                ElementDto.standard("elem-1", "Customer Portal", "ApplicationComponent",
                        null, "Application", "Main web app",
                        List.of(Map.of("key", "owner", "value", "ops"))),
                ElementDto.standard("elem-2", "API Gateway", "ApplicationComponent",
                        null, "Application", "REST API gateway", List.of()));
        List<RelationshipDto> relationships = List.of(
                new RelationshipDto("rel-1", "Serves", "ServingRelationship", "Critical",
                        "elem-1", "elem-2", false, "view relationship documentation",
                        List.of(Map.of("key", "owner", "value", "ops")),
                        "Customer Portal", "API Gateway", null, null, null));
        return new ViewContentsDto(
                "view-1", "Application Landscape", "Application Usage", "manual",
                elements, relationships,
                List.of(new ViewNodeDto("vo-1", "elem-1", 100, 50, 120, 55),
                        new ViewNodeDto("vo-2", "elem-2", 300, 50, 120, 55)),
                List.of(new ViewConnectionDto("vc-1", "rel-1", "ServingRelationship",
                        "vo-1", "vo-2", List.of(new BendpointDto(60, 0, -60, 0)))),
                List.of(new ViewGroupDto("grp-1", "Channels", 20, 20, 400, 300, null,
                        List.of("vo-1"))),
                List.of(new ViewNoteDto("note-1", "Reviewed", 460, 20, 200, 80, null)),
                List.of(new DiagramImageDto("img-1", "images/icon.png", 700, 20, 180, 120,
                        null, null, null)));
    }

    /** Description plus every input-schema property description — all of what a client is served. */
    private String servedSurfaceOf(String toolName) {
        McpSchema.Tool tool = findToolSpec(toolName).tool();
        StringBuilder surface = new StringBuilder(tool.description());
        Map<String, Object> props = tool.inputSchema() == null ? null : tool.inputSchema().properties();
        if (props != null) {
            for (Object value : props.values()) {
                surface.append(' ').append(schemaDescriptionOf(value));
            }
        }
        return surface.toString();
    }

    /** One named property's description alone, so a clause that migrated elsewhere fails the pin. */
    private String propertyDescriptionOf(String toolName, String property) {
        Map<String, Object> props = findToolSpec(toolName).tool().inputSchema().properties();
        Object prop = props.get(property);
        if (prop == null) {
            throw new AssertionError(toolName + " no longer registers a '" + property + "' input "
                    + "property — if the preset was removed this check should go with it, not pass "
                    + "vacuously on its absence.");
        }
        return schemaDescriptionOf(prop);
    }

    @SuppressWarnings("unchecked")
    private String schemaDescriptionOf(Object schemaProperty) {
        if (!(schemaProperty instanceof Map)) {
            return "";
        }
        Object description = ((Map<String, Object>) schemaProperty).get("description");
        return description == null ? "" : description.toString();
    }

    /**
     * The one table body row carrying the given fragment.
     *
     * <p>Row-scoped, and body rows only: the fragment appearing somewhere on the page says nothing
     * about whether the format table carries it, and a header row would satisfy a uniqueness check
     * while answering a different question.</p>
     */
    private static String tableRowContaining(String page, String fragment) {
        List<String> matches = new ArrayList<>();
        boolean inBody = false;
        for (String line : page.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) {
                inBody = false;
                continue;
            }
            if (trimmed.replace("|", "").trim().matches("^[-: ]*$")) {
                inBody = true;
                continue;
            }
            if (inBody && trimmed.contains(fragment)) {
                matches.add(trimmed);
            }
        }
        assertEquals("Expected exactly one table body row matching \"" + fragment + "\"; found "
                + matches.size() + ": " + matches, 1, matches.size());
        return matches.get(0);
    }

    /**
     * The slice of a Markdown page under one heading, up to the next heading of the same or higher
     * level.
     *
     * <p>The row lookup is scoped through this rather than run over the whole page: the uniqueness
     * assertion below is what makes the match trustworthy, and an unrelated table elsewhere on the
     * page growing a row with the same fragment would break it for a reason that has nothing to do
     * with the format table.</p>
     */
    private static String sectionOf(String page, String heading) {
        int start = page.indexOf("## " + heading);
        assertTrue("The \"" + heading + "\" section has left archimate-view-patterns.md, so this "
                + "pin is guarding prose that has moved. Re-point it — do not delete it.", start >= 0);
        int next = page.indexOf("\n## ", start + 1);
        return next < 0 ? page.substring(start) : page.substring(start, next);
    }

    private static final String[] VIEW_PATTERNS_CANDIDATES = {
            "net.vheerden.archi.mcp/resources/reference/archimate-view-patterns.md",
            "../net.vheerden.archi.mcp/resources/reference/archimate-view-patterns.md",
    };

    private static String readViewPatternsPage() {
        for (String candidate : VIEW_PATTERNS_CANDIDATES) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                try {
                    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new AssertionError("archimate-view-patterns.md resolved from none of "
                + String.join(", ", VIEW_PATTERNS_CANDIDATES) + " (cwd "
                + Paths.get("").toAbsolutePath() + ") — this pin cannot silently pass over a file "
                + "it never read.");
    }

}
