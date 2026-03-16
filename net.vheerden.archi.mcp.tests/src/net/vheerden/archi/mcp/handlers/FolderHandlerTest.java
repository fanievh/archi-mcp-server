package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;

/**
 * Tests for {@link FolderHandler} covering get-folders and get-folder-tree tools.
 *
 * <p>Uses {@link BaseTestAccessor} with a FolderStubAccessor subclass that provides
 * a test folder hierarchy: Business (2 subfolders, 5 elements),
 * Application (1 subfolder, 3 elements), empty Technology folder.</p>
 */
public class FolderHandlerTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private FolderHandler handler;
    private FolderStubAccessor accessor;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        accessor = new FolderStubAccessor();
        handler = new FolderHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Tool registration tests (AC 10) ----

    @Test
    public void shouldRegisterGetFoldersTool_whenHandlerRegistered() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "get-folders".equals(spec.tool().name()));
        assertTrue("get-folders tool should be registered", found);
    }

    @Test
    public void shouldRegisterGetFolderTreeTool_whenHandlerRegistered() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "get-folder-tree".equals(spec.tool().name()));
        assertTrue("get-folder-tree tool should be registered", found);
    }

    // ---- get-folders: no params → root folders (AC 1) ----

    @Test
    public void shouldReturnRootFolders_whenNoParams() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of());

        Map<String, Object> envelope = parseResult(result);
        assertFalse("Should not be error", result.isError() != null && result.isError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) envelope.get("result");
        assertEquals("Should return 3 root folders", 3, folders.size());

        // Verify first root folder shape
        Map<String, Object> business = folders.get(0);
        assertEquals("business-folder-id", business.get("id"));
        assertEquals("Business", business.get("name"));
        assertEquals("BUSINESS", business.get("type"));
        assertEquals(5, business.get("elementCount"));
        assertEquals(2, business.get("subfolderCount"));
    }

    @Test
    public void shouldIncludeNextSteps_whenRootFolders() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of());
        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull("Should include nextSteps", nextSteps);
        assertFalse("nextSteps should not be empty", nextSteps.isEmpty());
    }

    @Test
    public void shouldIncludeMeta_whenRootFolders() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of());
        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull("Should include _meta", meta);
        assertEquals("Should have 3 results", 3, meta.get("resultCount"));
        assertEquals("Should have 3 total", 3, meta.get("totalCount"));
        assertEquals("Should not be truncated", false, meta.get("isTruncated"));
    }

    // ---- get-folders: with parentId → children (AC 2) ----

    @Test
    public void shouldReturnChildren_whenParentIdProvided() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of("parentId", "business-folder-id"));

        Map<String, Object> envelope = parseResult(result);
        assertFalse("Should not be error", result.isError() != null && result.isError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) envelope.get("result");
        assertEquals("Business folder has 2 children", 2, folders.size());
        assertEquals("Processes", folders.get(0).get("name"));
        assertEquals("Organization", folders.get(1).get("name"));
    }

    @Test
    public void shouldReturnEmptyList_whenParentHasNoChildren() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of("parentId", "technology-folder-id"));

        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) envelope.get("result");
        assertEquals("Technology folder has no children", 0, folders.size());
    }

    // ---- get-folders: with name filter (AC 3) ----

    @Test
    public void shouldReturnMatchingFolders_whenNameFilterProvided() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of("name", "business"));

        Map<String, Object> envelope = parseResult(result);
        assertFalse("Should not be error", result.isError() != null && result.isError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) envelope.get("result");
        assertTrue("Should find at least one folder", folders.size() >= 1);
        // Should find "Business" root folder
        assertTrue("Should contain Business folder",
                folders.stream().anyMatch(f -> "Business".equals(f.get("name"))));
    }

    @Test
    public void shouldBeCaseInsensitive_whenNameFilterProvided() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of("name", "BUSINESS"));

        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) envelope.get("result");
        assertTrue("Case-insensitive search should find Business folder", folders.size() >= 1);
    }

    @Test
    public void shouldSearchRecursively_whenNameFilterWithoutParentId() throws Exception {
        // "Processes" is a subfolder of Business, should be found by recursive search
        McpSchema.CallToolResult result = callGetFolders(Map.of("name", "Processes"));

        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) envelope.get("result");
        assertEquals("Should find Processes subfolder", 1, folders.size());
        assertEquals("Processes", folders.get(0).get("name"));
    }

    // ---- get-folders: invalid parentId → FOLDER_NOT_FOUND (AC 4) ----

    @Test
    public void shouldReturnFolderNotFound_whenInvalidParentId() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of("parentId", "nonexistent-id"));

        assertTrue("Should be error", result.isError() != null && result.isError());
        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull("Should have error", error);
        assertEquals("FOLDER_NOT_FOUND", error.get("code"));
        assertNotNull("Should have suggestedCorrection", error.get("suggestedCorrection"));
    }

    // ---- get-folders: no model loaded → MODEL_NOT_LOADED (AC 8) ----

    @Test
    public void shouldReturnModelNotLoaded_whenNoModel() throws Exception {
        FolderStubAccessor noModelAccessor = new FolderStubAccessor(false);
        CommandRegistry noModelRegistry = new CommandRegistry();
        FolderHandler noModelHandler = new FolderHandler(
                noModelAccessor, formatter, noModelRegistry, null);
        noModelHandler.registerTools();

        McpSchema.CallToolResult result = callTool(noModelRegistry, "get-folders", Map.of());

        assertTrue("Should be error", result.isError() != null && result.isError());
        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    // ---- get-folder-tree: no params → full tree (AC 5) ----

    @Test
    public void shouldReturnFullTree_whenNoParams() throws Exception {
        McpSchema.CallToolResult result = callGetFolderTree(Map.of());

        Map<String, Object> envelope = parseResult(result);
        assertFalse("Should not be error", result.isError() != null && result.isError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tree = (List<Map<String, Object>>) envelope.get("result");
        assertEquals("Should return 3 root nodes", 3, tree.size());

        // Business should have children
        Map<String, Object> businessNode = tree.get(0);
        assertEquals("Business", businessNode.get("name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) businessNode.get("children");
        assertNotNull("Business should have children", children);
        assertEquals("Business has 2 children", 2, children.size());
    }

    // ---- get-folder-tree: with rootId → subtree (AC 6) ----

    @Test
    public void shouldReturnSubtree_whenRootIdProvided() throws Exception {
        McpSchema.CallToolResult result = callGetFolderTree(Map.of("rootId", "business-folder-id"));

        Map<String, Object> envelope = parseResult(result);
        assertFalse("Should not be error", result.isError() != null && result.isError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tree = (List<Map<String, Object>>) envelope.get("result");
        assertEquals("Subtree should have 1 root", 1, tree.size());
        assertEquals("Business", tree.get(0).get("name"));
    }

    // ---- get-folder-tree: with depth limit (AC 7) ----

    @Test
    public void shouldLimitDepth_whenDepthProvided() throws Exception {
        McpSchema.CallToolResult result = callGetFolderTree(Map.of("depth", 1));

        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tree = (List<Map<String, Object>>) envelope.get("result");
        // Root nodes should have children (depth 1), but children should not have children
        Map<String, Object> businessNode = tree.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) businessNode.get("children");
        if (children != null) {
            for (Map<String, Object> child : children) {
                assertNull("Depth 1 children should not have grandchildren",
                        child.get("children"));
            }
        }
    }

    // ---- get-folder-tree: negative depth → INVALID_PARAMETER (M3) ----

    @Test
    public void shouldReturnInvalidParameter_whenNegativeDepth() throws Exception {
        McpSchema.CallToolResult result = callGetFolderTree(Map.of("depth", -1));

        assertTrue("Should be error", result.isError() != null && result.isError());
        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue("Should mention depth",
                ((String) error.get("message")).contains("depth"));
    }

    // ---- get-folder-tree: invalid rootId → FOLDER_NOT_FOUND ----

    @Test
    public void shouldReturnFolderNotFound_whenInvalidRootId() throws Exception {
        McpSchema.CallToolResult result = callGetFolderTree(Map.of("rootId", "nonexistent-id"));

        assertTrue("Should be error", result.isError() != null && result.isError());
        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("FOLDER_NOT_FOUND", error.get("code"));
    }

    // ---- get-folder-tree: no model loaded → MODEL_NOT_LOADED (AC 8) ----

    @Test
    public void shouldReturnModelNotLoaded_whenNoModelForTree() throws Exception {
        FolderStubAccessor noModelAccessor = new FolderStubAccessor(false);
        CommandRegistry noModelRegistry = new CommandRegistry();
        FolderHandler noModelHandler = new FolderHandler(
                noModelAccessor, formatter, noModelRegistry, null);
        noModelHandler.registerTools();

        McpSchema.CallToolResult result = callTool(noModelRegistry, "get-folder-tree", Map.of());

        assertTrue("Should be error", result.isError() != null && result.isError());
        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    // ---- get-folder-tree: with name filter → pruned tree (AC 3 analogue) ----

    @Test
    public void shouldPruneTree_whenNameFilterProvided() throws Exception {
        McpSchema.CallToolResult result = callGetFolderTree(Map.of("name", "Processes"));

        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tree = (List<Map<String, Object>>) envelope.get("result");
        // Should include Business (parent of Processes) but not Technology/Application
        assertTrue("Pruned tree should not be empty", tree.size() >= 1);
        assertEquals("Business", tree.get(0).get("name"));
    }

    // ---- Field selection (AC 1, Task 6.16) ----

    @Test
    public void shouldReturnMinimalFields_whenFieldsMinimal() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of("fields", "minimal"));

        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) envelope.get("result");
        Map<String, Object> first = folders.get(0);
        assertTrue("Should have id", first.containsKey("id"));
        assertTrue("Should have name", first.containsKey("name"));
        assertFalse("Should not have type in minimal", first.containsKey("type"));
        assertFalse("Should not have path in minimal", first.containsKey("path"));
    }

    @Test
    public void shouldReturnAllFields_whenFieldsStandard() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of("fields", "standard"));

        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) envelope.get("result");
        Map<String, Object> first = folders.get(0);
        assertTrue("Should have id", first.containsKey("id"));
        assertTrue("Should have name", first.containsKey("name"));
        assertTrue("Should have type", first.containsKey("type"));
        assertTrue("Should have elementCount", first.containsKey("elementCount"));
        assertTrue("Should have subfolderCount", first.containsKey("subfolderCount"));
    }

    // ---- Response envelope structure (Task 6.15) ----

    @Test
    public void shouldHaveStandardEnvelopeStructure_whenSuccess() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(Map.of());
        Map<String, Object> envelope = parseResult(result);

        assertTrue("Should have result", envelope.containsKey("result"));
        assertTrue("Should have nextSteps", envelope.containsKey("nextSteps"));
        assertTrue("Should have _meta", envelope.containsKey("_meta"));

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertTrue("_meta should have modelVersion", meta.containsKey("modelVersion"));
        assertTrue("_meta should have resultCount", meta.containsKey("resultCount"));
        assertTrue("_meta should have totalCount", meta.containsKey("totalCount"));
    }

    // ---- Exclude parameter (M2 fix) ----

    @Test
    public void shouldExcludeFields_whenExcludeProvided() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(
                Map.of("fields", "standard", "exclude", List.of("type")));

        Map<String, Object> envelope = parseResult(result);
        assertFalse("Should not be error", result.isError() != null && result.isError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) envelope.get("result");
        Map<String, Object> first = folders.get(0);
        assertTrue("Should have id", first.containsKey("id"));
        assertTrue("Should have name", first.containsKey("name"));
        assertFalse("Should not have type (excluded)", first.containsKey("type"));
        assertTrue("Should still have path (not excluded)", first.containsKey("path"));
    }

    @Test
    public void shouldReturnInvalidParameter_whenInvalidExcludeField() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(
                Map.of("exclude", List.of("bogusField")));

        assertTrue("Should be error", result.isError() != null && result.isError());
        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue("Should mention invalid field",
                ((String) error.get("message")).contains("bogusField"));
    }

    // ---- Name filter with parentId (combined) ----

    @Test
    public void shouldFilterChildrenByName_whenParentIdAndNameProvided() throws Exception {
        McpSchema.CallToolResult result = callGetFolders(
                Map.of("parentId", "business-folder-id", "name", "org"));

        Map<String, Object> envelope = parseResult(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) envelope.get("result");
        assertEquals("Should find Organization subfolder", 1, folders.size());
        assertEquals("Organization", folders.get(0).get("name"));
    }

    // ---- Helper methods ----

    private McpSchema.CallToolResult callGetFolders(Map<String, Object> args) {
        return callTool(registry, "get-folders", args);
    }

    private McpSchema.CallToolResult callGetFolderTree(Map<String, Object> args) {
        return callTool(registry, "get-folder-tree", args);
    }

    private McpSchema.CallToolResult callTool(CommandRegistry reg, String toolName, Map<String, Object> args) {
        return reg.getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName))
                .callHandler()
                .apply(null, new McpSchema.CallToolRequest(toolName, args));
    }

    private Map<String, Object> parseResult(McpSchema.CallToolResult result) throws Exception {
        String json = ((McpSchema.TextContent) result.content().get(0)).text();
        return objectMapper.readValue(json, MAP_TYPE_REF);
    }

    // ---- Test data: Stub accessor with folder hierarchy ----

    /**
     * Folder hierarchy for testing:
     * <pre>
     * Business (id: business-folder-id, type: BUSINESS, 5 elements, 2 subfolders)
     *   ├── Processes (id: processes-folder-id, type: USER, 3 elements, 0 subfolders)
     *   └── Organization (id: org-folder-id, type: USER, 2 elements, 0 subfolders)
     * Application (id: app-folder-id, type: APPLICATION, 3 elements, 1 subfolder)
     *   └── Services (id: services-folder-id, type: USER, 1 element, 0 subfolders)
     * Technology (id: technology-folder-id, type: TECHNOLOGY, 0 elements, 0 subfolders)
     * </pre>
     */
    private static class FolderStubAccessor extends BaseTestAccessor {

        // Root folders
        private static final FolderDto BUSINESS = new FolderDto(
                "business-folder-id", "Business", "BUSINESS", "Business", 5, 2);
        private static final FolderDto APPLICATION = new FolderDto(
                "app-folder-id", "Application", "APPLICATION", "Application", 3, 1);
        private static final FolderDto TECHNOLOGY = new FolderDto(
                "technology-folder-id", "Technology", "TECHNOLOGY", "Technology", 0, 0);

        // Subfolders
        private static final FolderDto PROCESSES = new FolderDto(
                "processes-folder-id", "Processes", "USER", "Business/Processes", 3, 0);
        private static final FolderDto ORGANIZATION = new FolderDto(
                "org-folder-id", "Organization", "USER", "Business/Organization", 2, 0);
        private static final FolderDto SERVICES = new FolderDto(
                "services-folder-id", "Services", "USER", "Application/Services", 1, 0);

        private static final List<FolderDto> ROOT_FOLDERS = List.of(BUSINESS, APPLICATION, TECHNOLOGY);
        private static final List<FolderDto> ALL_FOLDERS = List.of(
                BUSINESS, PROCESSES, ORGANIZATION, APPLICATION, SERVICES, TECHNOLOGY);

        FolderStubAccessor() {
            super(true);
        }

        FolderStubAccessor(boolean modelLoaded) {
            super(modelLoaded);
        }

        @Override
        public List<FolderDto> getRootFolders() {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            return ROOT_FOLDERS;
        }

        @Override
        public Optional<FolderDto> getFolderById(String id) {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            return ALL_FOLDERS.stream()
                    .filter(f -> f.id().equals(id))
                    .findFirst();
        }

        @Override
        public List<FolderDto> getFolderChildren(String parentId) {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            if ("business-folder-id".equals(parentId)) {
                return List.of(PROCESSES, ORGANIZATION);
            }
            if ("app-folder-id".equals(parentId)) {
                return List.of(SERVICES);
            }
            return List.of();
        }

        @Override
        public List<FolderTreeDto> getFolderTree(String rootId, int maxDepth) {
            if (!isModelLoaded()) throw new NoModelLoadedException();

            if (rootId != null) {
                FolderTreeDto node = buildTreeNode(rootId, maxDepth, 0);
                return node != null ? List.of(node) : List.of();
            }

            List<FolderTreeDto> result = new ArrayList<>();
            result.add(buildTreeNode("business-folder-id", maxDepth, 0));
            result.add(buildTreeNode("app-folder-id", maxDepth, 0));
            result.add(buildTreeNode("technology-folder-id", maxDepth, 0));
            return result;
        }

        @Override
        public List<FolderDto> searchFolders(String nameQuery) {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            String lower = nameQuery.toLowerCase();
            return ALL_FOLDERS.stream()
                    .filter(f -> f.name() != null && f.name().toLowerCase().contains(lower))
                    .toList();
        }

        private FolderTreeDto buildTreeNode(String folderId, int maxDepth, int currentDepth) {
            FolderDto folder = getFolderById(folderId).orElse(null);
            if (folder == null) return null;

            List<FolderTreeDto> children = null;
            if (maxDepth <= 0 || currentDepth < maxDepth) {
                List<FolderDto> childFolders = getFolderChildren(folderId);
                if (!childFolders.isEmpty()) {
                    children = new ArrayList<>();
                    for (FolderDto child : childFolders) {
                        FolderTreeDto childNode = buildTreeNode(child.id(), maxDepth, currentDepth + 1);
                        if (childNode != null) {
                            children.add(childNode);
                        }
                    }
                }
            }

            return new FolderTreeDto(
                    folder.id(), folder.name(), folder.type(), folder.path(),
                    folder.elementCount(), folder.subfolderCount(), children);
        }
    }
}
