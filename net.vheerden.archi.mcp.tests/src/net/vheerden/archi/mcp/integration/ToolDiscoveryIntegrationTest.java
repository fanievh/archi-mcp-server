package net.vheerden.archi.mcp.integration;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.ApprovalHandler;
import net.vheerden.archi.mcp.handlers.DeletionHandler;
import net.vheerden.archi.mcp.handlers.DiscoveryHandler;
import net.vheerden.archi.mcp.handlers.ElementCreationHandler;
import net.vheerden.archi.mcp.handlers.ElementUpdateHandler;
import net.vheerden.archi.mcp.handlers.FolderHandler;
import net.vheerden.archi.mcp.handlers.FolderMutationHandler;
import net.vheerden.archi.mcp.handlers.ModelQueryHandler;
import net.vheerden.archi.mcp.handlers.MutationHandler;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.handlers.TraversalHandler;
import net.vheerden.archi.mcp.handlers.SessionHandler;
import net.vheerden.archi.mcp.handlers.RenderHandler;
import net.vheerden.archi.mcp.handlers.ViewHandler;
import net.vheerden.archi.mcp.handlers.CommandStackHandler;
import net.vheerden.archi.mcp.handlers.ViewPlacementHandler;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Integration test verifying all tools are discoverable via CommandRegistry.
 * Tests that tool discovery returns all registered tools with correct schemas and kebab-case names.
 *
 * <p>Uses real CommandRegistry, real handlers, stub accessor — tests the WIRING,
 * not individual handler logic.</p>
 */
public class ToolDiscoveryIntegrationTest {

    private CommandRegistry registry;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        ResponseFormatter formatter = new ResponseFormatter();
        ArchiModelAccessor accessor = new IntegrationStubAccessor(true);

        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);

        ModelQueryHandler mqh = new ModelQueryHandler(accessor, formatter, registry, sm);
        ViewHandler vh = new ViewHandler(accessor, formatter, registry, sm);
        SearchHandler sh = new SearchHandler(accessor, formatter, registry, sm);
        TraversalHandler th = new TraversalHandler(accessor, formatter, registry, sm);
        SessionHandler sessionH = new SessionHandler(sm, formatter, registry);
        FolderHandler fh = new FolderHandler(accessor, formatter, registry, sm);
        MutationHandler mh = new MutationHandler(accessor, formatter, registry, sm);
        mqh.registerTools();
        vh.registerTools();
        sh.registerTools();
        th.registerTools();
        sessionH.registerTools();
        fh.registerTools();
        mh.registerTools();
        ElementCreationHandler ech = new ElementCreationHandler(accessor, formatter, registry, sm);
        ech.registerTools();
        ElementUpdateHandler euh = new ElementUpdateHandler(accessor, formatter, registry, sm);
        euh.registerTools();
        DiscoveryHandler dh = new DiscoveryHandler(accessor, formatter, registry, sm);
        dh.registerTools();
        ApprovalHandler ah = new ApprovalHandler(accessor, formatter, registry, sm);
        ah.registerTools();
        ViewPlacementHandler vph = new ViewPlacementHandler(accessor, formatter, registry, sm);
        vph.registerTools();
        RenderHandler rh = new RenderHandler(accessor, formatter, registry);
        rh.registerTools();
        DeletionHandler delh = new DeletionHandler(accessor, formatter, registry, sm);
        delh.registerTools();
        FolderMutationHandler fmh = new FolderMutationHandler(accessor, formatter, registry, sm);
        fmh.registerTools();
        CommandStackHandler csh = new CommandStackHandler(accessor, formatter, registry);
        csh.registerTools();
    }

    @Test
    public void shouldDiscoverAllRegisteredTools() {
        List<McpServerFeatures.SyncToolSpecification> tools = registry.getToolSpecifications();
        assertEquals("Expected exactly 56 tools (2 ModelQuery + 3 View + 2 Search + 1 Traversal + 2 Session + 2 Folder + 4 Mutation + 4 Creation + 2 Update + 2 Discovery + 3 Approval + 19 ViewPlacement + 1 Render + 4 Deletion + 3 FolderMutation + 2 CommandStack)", 56, tools.size());
    }

    @Test
    public void shouldReturnCorrectToolNames() {
        Set<String> toolNames = registry.getToolSpecifications().stream()
                .map(s -> s.tool().name())
                .collect(Collectors.toSet());

        assertTrue("Missing get-model-info", toolNames.contains("get-model-info"));
        assertTrue("Missing get-element", toolNames.contains("get-element"));
        assertTrue("Missing get-views", toolNames.contains("get-views"));
        assertTrue("Missing get-view-contents", toolNames.contains("get-view-contents"));
        assertTrue("Missing search-elements", toolNames.contains("search-elements"));
        assertTrue("Missing search-relationships", toolNames.contains("search-relationships"));
        assertTrue("Missing get-relationships", toolNames.contains("get-relationships"));
        assertTrue("Missing set-session-filter", toolNames.contains("set-session-filter"));
        assertTrue("Missing get-session-filters", toolNames.contains("get-session-filters"));
        assertTrue("Missing get-folders", toolNames.contains("get-folders"));
        assertTrue("Missing get-folder-tree", toolNames.contains("get-folder-tree"));
        assertTrue("Missing begin-batch", toolNames.contains("begin-batch"));
        assertTrue("Missing end-batch", toolNames.contains("end-batch"));
        assertTrue("Missing get-batch-status", toolNames.contains("get-batch-status"));
        assertTrue("Missing create-element", toolNames.contains("create-element"));
        assertTrue("Missing create-relationship", toolNames.contains("create-relationship"));
        assertTrue("Missing create-view", toolNames.contains("create-view"));
        assertTrue("Missing update-element", toolNames.contains("update-element"));
        assertTrue("Missing update-relationship", toolNames.contains("update-relationship"));
        assertTrue("Missing get-or-create-element", toolNames.contains("get-or-create-element"));
        assertTrue("Missing search-and-create", toolNames.contains("search-and-create"));
        assertTrue("Missing bulk-mutate", toolNames.contains("bulk-mutate"));
        // Story 7-6: Approval tools
        assertTrue("Missing set-approval-mode", toolNames.contains("set-approval-mode"));
        assertTrue("Missing list-pending-approvals", toolNames.contains("list-pending-approvals"));
        assertTrue("Missing decide-mutation", toolNames.contains("decide-mutation"));
        // Story 7-7: View placement tools
        assertTrue("Missing add-to-view", toolNames.contains("add-to-view"));
        assertTrue("Missing add-connection-to-view", toolNames.contains("add-connection-to-view"));
        // Story 7-8: View editing and removal tools
        assertTrue("Missing update-view-object", toolNames.contains("update-view-object"));
        assertTrue("Missing update-view-connection", toolNames.contains("update-view-connection"));
        assertTrue("Missing remove-from-view", toolNames.contains("remove-from-view"));
        // Story 8-0c: Clear view tool
        assertTrue("Missing clear-view", toolNames.contains("clear-view"));
        // Story 8-1: Render/export tool
        assertTrue("Missing export-view", toolNames.contains("export-view"));
        // Story 8-4: Deletion tools
        assertTrue("Missing delete-element", toolNames.contains("delete-element"));
        assertTrue("Missing delete-relationship", toolNames.contains("delete-relationship"));
        assertTrue("Missing delete-view", toolNames.contains("delete-view"));
        assertTrue("Missing delete-folder", toolNames.contains("delete-folder"));
        // Story 8-5: Folder mutation tools
        assertTrue("Missing create-folder", toolNames.contains("create-folder"));
        assertTrue("Missing update-folder", toolNames.contains("update-folder"));
        assertTrue("Missing move-to-folder", toolNames.contains("move-to-folder"));
        // Story 8-6: Visual grouping tools
        assertTrue("Missing add-group-to-view", toolNames.contains("add-group-to-view"));
        assertTrue("Missing add-note-to-view", toolNames.contains("add-note-to-view"));
        // Story 8-7: View update tool
        assertTrue("Missing update-view", toolNames.contains("update-view"));
        // Story 9-0a / 11-8: Apply positions tool (renamed from apply-view-layout)
        assertTrue("Missing apply-positions", toolNames.contains("apply-positions"));
        // Story 9-1 / 11-8: Compute layout tool (renamed from layout-view)
        assertTrue("Missing compute-layout", toolNames.contains("compute-layout"));
        assertTrue("Missing assess-layout", toolNames.contains("assess-layout"));
        // Story 9-5: Auto-route connections tool
        assertTrue("Missing auto-route-connections", toolNames.contains("auto-route-connections"));
        // Story 9-6: Auto-connect view tool
        assertTrue("Missing auto-connect-view", toolNames.contains("auto-connect-view"));
        // Story 9-9: Layout within group tool
        assertTrue("Missing layout-within-group", toolNames.contains("layout-within-group"));
        // Story 10-29: ELK combined layout+routing tool
        assertTrue("Missing auto-layout-and-route", toolNames.contains("auto-layout-and-route"));
        // Story 11-1: Undo/redo tools
        assertTrue("Missing undo", toolNames.contains("undo"));
        assertTrue("Missing redo", toolNames.contains("redo"));
        // Story 11-20: Group positioning tool
        assertTrue("Missing arrange-groups", toolNames.contains("arrange-groups"));
        // Story 11-25: Element order optimization tool
        assertTrue("Missing optimize-group-order", toolNames.contains("optimize-group-order"));
        // Story 13-2: Hub element detection tool
        assertTrue("Missing detect-hub-elements", toolNames.contains("detect-hub-elements"));
        // Story 13-6: Flat view layout tool
        assertTrue("Missing layout-flat-view", toolNames.contains("layout-flat-view"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveAccurateInputSchemas() {
        // get-model-info: no required params
        McpSchema.JsonSchema modelInfoSchema = findTool("get-model-info").inputSchema();
        assertNotNull(modelInfoSchema);
        assertEquals("object", modelInfoSchema.type());
        assertTrue("get-model-info should have empty properties",
                modelInfoSchema.properties() == null || modelInfoSchema.properties().isEmpty());

        // get-element: has "id" (string) and "ids" (array), neither required
        McpSchema.JsonSchema elementSchema = findTool("get-element").inputSchema();
        assertNotNull(elementSchema);
        assertTrue("get-element should have 'id' property",
                elementSchema.properties().containsKey("id"));
        assertTrue("get-element should have 'ids' property",
                elementSchema.properties().containsKey("ids"));
        // Neither id nor ids is required in schema — handler validates
        assertTrue("get-element should not require any params in schema",
                elementSchema.required() == null || elementSchema.required().isEmpty());

        // get-views: optional "viewpoint"
        McpSchema.JsonSchema viewsSchema = findTool("get-views").inputSchema();
        assertNotNull(viewsSchema);
        assertTrue("get-views should have 'viewpoint' property",
                viewsSchema.properties().containsKey("viewpoint"));
        // viewpoint is optional — should NOT be in required list (or required is null/empty)
        boolean viewpointRequired = viewsSchema.required() != null
                && viewsSchema.required().contains("viewpoint");
        assertFalse("get-views 'viewpoint' should be optional", viewpointRequired);

        // get-view-contents: requires "viewId"
        McpSchema.JsonSchema viewContentsSchema = findTool("get-view-contents").inputSchema();
        assertNotNull(viewContentsSchema);
        assertTrue("get-view-contents should have 'viewId' property",
                viewContentsSchema.properties().containsKey("viewId"));
        assertNotNull("get-view-contents should have required list",
                viewContentsSchema.required());
        assertTrue("get-view-contents should require 'viewId'",
                viewContentsSchema.required().contains("viewId"));

        // search-elements: requires "query", optional "type" and "layer"
        McpSchema.JsonSchema searchSchema = findTool("search-elements").inputSchema();
        assertNotNull(searchSchema);
        assertTrue("search-elements should have 'query' property",
                searchSchema.properties().containsKey("query"));
        assertTrue("search-elements should have 'type' property",
                searchSchema.properties().containsKey("type"));
        assertTrue("search-elements should have 'layer' property",
                searchSchema.properties().containsKey("layer"));
        assertNotNull("search-elements should have required list",
                searchSchema.required());
        assertTrue("search-elements should require 'query'",
                searchSchema.required().contains("query"));
        assertFalse("search-elements 'type' should be optional",
                searchSchema.required().contains("type"));
        assertFalse("search-elements 'layer' should be optional",
                searchSchema.required().contains("layer"));

        // search-relationships: requires "query", optional "type", "sourceLayer", "targetLayer"
        McpSchema.JsonSchema searchRelSchema = findTool("search-relationships").inputSchema();
        assertNotNull(searchRelSchema);
        assertTrue("search-relationships should have 'query' property",
                searchRelSchema.properties().containsKey("query"));
        assertTrue("search-relationships should have 'type' property",
                searchRelSchema.properties().containsKey("type"));
        assertTrue("search-relationships should have 'sourceLayer' property",
                searchRelSchema.properties().containsKey("sourceLayer"));
        assertTrue("search-relationships should have 'targetLayer' property",
                searchRelSchema.properties().containsKey("targetLayer"));
        assertNotNull("search-relationships should have required list",
                searchRelSchema.required());
        assertTrue("search-relationships should require 'query'",
                searchRelSchema.required().contains("query"));
        assertFalse("search-relationships 'type' should be optional",
                searchRelSchema.required().contains("type"));
        assertFalse("search-relationships 'sourceLayer' should be optional",
                searchRelSchema.required().contains("sourceLayer"));
        assertFalse("search-relationships 'targetLayer' should be optional",
                searchRelSchema.required().contains("targetLayer"));

        // get-relationships: requires "elementId", optional "depth", "traverse", "maxDepth", "direction"
        McpSchema.JsonSchema relSchema = findTool("get-relationships").inputSchema();
        assertNotNull(relSchema);
        assertTrue("get-relationships should have 'elementId' property",
                relSchema.properties().containsKey("elementId"));
        assertTrue("get-relationships should have 'depth' property",
                relSchema.properties().containsKey("depth"));
        assertTrue("get-relationships should have 'traverse' property",
                relSchema.properties().containsKey("traverse"));
        assertTrue("get-relationships should have 'maxDepth' property",
                relSchema.properties().containsKey("maxDepth"));
        assertTrue("get-relationships should have 'direction' property",
                relSchema.properties().containsKey("direction"));
        assertNotNull("get-relationships should have required list",
                relSchema.required());
        assertTrue("get-relationships should require 'elementId'",
                relSchema.required().contains("elementId"));
        assertFalse("get-relationships 'depth' should be optional",
                relSchema.required().contains("depth"));
        assertFalse("get-relationships 'traverse' should be optional",
                relSchema.required().contains("traverse"));
        assertFalse("get-relationships 'maxDepth' should be optional",
                relSchema.required().contains("maxDepth"));
        assertFalse("get-relationships 'direction' should be optional",
                relSchema.required().contains("direction"));

        // Story 4.3: filter parameters
        assertTrue("get-relationships should have 'excludeTypes' property",
                relSchema.properties().containsKey("excludeTypes"));
        assertTrue("get-relationships should have 'includeTypes' property",
                relSchema.properties().containsKey("includeTypes"));
        assertTrue("get-relationships should have 'filterLayer' property",
                relSchema.properties().containsKey("filterLayer"));
        assertFalse("get-relationships 'excludeTypes' should be optional",
                relSchema.required().contains("excludeTypes"));
        assertFalse("get-relationships 'includeTypes' should be optional",
                relSchema.required().contains("includeTypes"));
        assertFalse("get-relationships 'filterLayer' should be optional",
                relSchema.required().contains("filterLayer"));

        // Story 5.1: Session tools
        // set-session-filter: optional type, layer, clear
        McpSchema.JsonSchema setFilterSchema = findTool("set-session-filter").inputSchema();
        assertNotNull(setFilterSchema);
        assertTrue("set-session-filter should have 'type' property",
                setFilterSchema.properties().containsKey("type"));
        assertTrue("set-session-filter should have 'layer' property",
                setFilterSchema.properties().containsKey("layer"));
        assertTrue("set-session-filter should have 'clear' property",
                setFilterSchema.properties().containsKey("clear"));
        Map<String, Object> setFilterTypeProp =
                (Map<String, Object>) setFilterSchema.properties().get("type");
        assertEquals("set-session-filter type should be string", "string", setFilterTypeProp.get("type"));
        Map<String, Object> setFilterLayerProp =
                (Map<String, Object>) setFilterSchema.properties().get("layer");
        assertEquals("set-session-filter layer should be string", "string", setFilterLayerProp.get("type"));
        Map<String, Object> setFilterClearProp =
                (Map<String, Object>) setFilterSchema.properties().get("clear");
        assertEquals("set-session-filter clear should be boolean", "boolean", setFilterClearProp.get("type"));
        assertTrue("set-session-filter should have no required params",
                setFilterSchema.required() == null || setFilterSchema.required().isEmpty());

        // get-session-filters: no required params
        McpSchema.JsonSchema getFiltersSchema = findTool("get-session-filters").inputSchema();
        assertNotNull(getFiltersSchema);
        assertTrue("get-session-filters should have no required params",
                getFiltersSchema.required() == null || getFiltersSchema.required().isEmpty());

        // Verify filter param types
        Map<String, Object> excludeTypesProp =
                (Map<String, Object>) relSchema.properties().get("excludeTypes");
        assertEquals("excludeTypes should be array type", "array", excludeTypesProp.get("type"));
        Map<String, Object> includeTypesProp =
                (Map<String, Object>) relSchema.properties().get("includeTypes");
        assertEquals("includeTypes should be array type", "array", includeTypesProp.get("type"));
        Map<String, Object> filterLayerProp =
                (Map<String, Object>) relSchema.properties().get("filterLayer");
        assertEquals("filterLayer should be string type", "string", filterLayerProp.get("type"));

        // Story 5.2: Field selection parameters on all query commands
        assertFieldSelectionParams("search-elements", searchSchema);
        assertFieldSelectionParams("search-relationships", searchRelSchema);
        assertFieldSelectionParams("get-element", elementSchema);
        assertFieldSelectionParams("get-views", viewsSchema);
        assertFieldSelectionParams("get-view-contents", viewContentsSchema);
        assertFieldSelectionParams("get-relationships", relSchema);
        assertFieldSelectionParams("set-session-filter", setFilterSchema);

        // Story 7-0b: Folder tools
        // get-folders: optional parentId, name, fields, exclude
        McpSchema.JsonSchema foldersSchema = findTool("get-folders").inputSchema();
        assertNotNull(foldersSchema);
        assertTrue("get-folders should have 'parentId' property",
                foldersSchema.properties().containsKey("parentId"));
        assertTrue("get-folders should have 'name' property",
                foldersSchema.properties().containsKey("name"));
        assertTrue("get-folders should have no required params",
                foldersSchema.required() == null || foldersSchema.required().isEmpty());
        assertFieldSelectionParams("get-folders", foldersSchema);

        // get-folder-tree: optional rootId, name, depth, fields, exclude
        McpSchema.JsonSchema folderTreeSchema = findTool("get-folder-tree").inputSchema();
        assertNotNull(folderTreeSchema);
        assertTrue("get-folder-tree should have 'rootId' property",
                folderTreeSchema.properties().containsKey("rootId"));
        assertTrue("get-folder-tree should have 'name' property",
                folderTreeSchema.properties().containsKey("name"));
        assertTrue("get-folder-tree should have 'depth' property",
                folderTreeSchema.properties().containsKey("depth"));
        assertTrue("get-folder-tree should have no required params",
                folderTreeSchema.required() == null || folderTreeSchema.required().isEmpty());
        assertFieldSelectionParams("get-folder-tree", folderTreeSchema);

        // get-model-info should NOT have fields/exclude
        assertFalse("get-model-info should NOT have 'fields' property",
                modelInfoSchema.properties() != null
                        && modelInfoSchema.properties().containsKey("fields"));

        // Story 7-1: Mutation tools
        // begin-batch: optional description
        McpSchema.JsonSchema beginBatchSchema = findTool("begin-batch").inputSchema();
        assertNotNull(beginBatchSchema);
        assertTrue("begin-batch should have 'description' property",
                beginBatchSchema.properties().containsKey("description"));
        assertTrue("begin-batch should have no required params",
                beginBatchSchema.required() == null || beginBatchSchema.required().isEmpty());

        // end-batch: optional rollback
        McpSchema.JsonSchema endBatchSchema = findTool("end-batch").inputSchema();
        assertNotNull(endBatchSchema);
        assertTrue("end-batch should have 'rollback' property",
                endBatchSchema.properties().containsKey("rollback"));
        Map<String, Object> rollbackProp =
                (Map<String, Object>) endBatchSchema.properties().get("rollback");
        assertEquals("end-batch 'rollback' should be boolean", "boolean", rollbackProp.get("type"));
        assertTrue("end-batch should have no required params",
                endBatchSchema.required() == null || endBatchSchema.required().isEmpty());

        // get-batch-status: no params
        McpSchema.JsonSchema batchStatusSchema = findTool("get-batch-status").inputSchema();
        assertNotNull(batchStatusSchema);
        assertTrue("get-batch-status should have no required params",
                batchStatusSchema.required() == null || batchStatusSchema.required().isEmpty());

        // Story 7-2: Element creation tools
        // create-element: requires type, name; optional documentation, properties, folderId
        McpSchema.JsonSchema createElementSchema = findTool("create-element").inputSchema();
        assertNotNull(createElementSchema);
        assertTrue("create-element should have 'type' property",
                createElementSchema.properties().containsKey("type"));
        assertTrue("create-element should have 'name' property",
                createElementSchema.properties().containsKey("name"));
        assertTrue("create-element should have 'documentation' property",
                createElementSchema.properties().containsKey("documentation"));
        assertTrue("create-element should have 'properties' property",
                createElementSchema.properties().containsKey("properties"));
        assertTrue("create-element should have 'folderId' property",
                createElementSchema.properties().containsKey("folderId"));
        assertNotNull("create-element should have required list",
                createElementSchema.required());
        assertTrue("create-element should require 'type'",
                createElementSchema.required().contains("type"));
        assertTrue("create-element should require 'name'",
                createElementSchema.required().contains("name"));
        assertFalse("create-element 'documentation' should be optional",
                createElementSchema.required().contains("documentation"));

        // create-relationship: requires type, sourceId, targetId; optional name
        McpSchema.JsonSchema createRelSchema = findTool("create-relationship").inputSchema();
        assertNotNull(createRelSchema);
        assertTrue("create-relationship should have 'type' property",
                createRelSchema.properties().containsKey("type"));
        assertTrue("create-relationship should have 'sourceId' property",
                createRelSchema.properties().containsKey("sourceId"));
        assertTrue("create-relationship should have 'targetId' property",
                createRelSchema.properties().containsKey("targetId"));
        assertTrue("create-relationship should have 'name' property",
                createRelSchema.properties().containsKey("name"));
        assertNotNull("create-relationship should have required list",
                createRelSchema.required());
        assertTrue("create-relationship should require 'type'",
                createRelSchema.required().contains("type"));
        assertTrue("create-relationship should require 'sourceId'",
                createRelSchema.required().contains("sourceId"));
        assertTrue("create-relationship should require 'targetId'",
                createRelSchema.required().contains("targetId"));
        assertFalse("create-relationship 'name' should be optional",
                createRelSchema.required().contains("name"));

        // create-view: requires name; optional viewpoint, folderId
        McpSchema.JsonSchema createViewSchema = findTool("create-view").inputSchema();
        assertNotNull(createViewSchema);
        assertTrue("create-view should have 'name' property",
                createViewSchema.properties().containsKey("name"));
        assertTrue("create-view should have 'viewpoint' property",
                createViewSchema.properties().containsKey("viewpoint"));
        assertTrue("create-view should have 'folderId' property",
                createViewSchema.properties().containsKey("folderId"));
        assertNotNull("create-view should have required list",
                createViewSchema.required());
        assertTrue("create-view should require 'name'",
                createViewSchema.required().contains("name"));
        assertFalse("create-view 'viewpoint' should be optional",
                createViewSchema.required().contains("viewpoint"));
        assertFalse("create-view 'folderId' should be optional",
                createViewSchema.required().contains("folderId"));

        // Story 7-3: Element update tool
        // update-element: requires id; optional name, documentation, properties
        McpSchema.JsonSchema updateElementSchema = findTool("update-element").inputSchema();
        assertNotNull(updateElementSchema);
        assertTrue("update-element should have 'id' property",
                updateElementSchema.properties().containsKey("id"));
        assertTrue("update-element should have 'name' property",
                updateElementSchema.properties().containsKey("name"));
        assertTrue("update-element should have 'documentation' property",
                updateElementSchema.properties().containsKey("documentation"));
        assertTrue("update-element should have 'properties' property",
                updateElementSchema.properties().containsKey("properties"));
        assertNotNull("update-element should have required list",
                updateElementSchema.required());
        assertTrue("update-element should require 'id'",
                updateElementSchema.required().contains("id"));
        assertFalse("update-element 'name' should be optional",
                updateElementSchema.required().contains("name"));
        assertFalse("update-element 'documentation' should be optional",
                updateElementSchema.required().contains("documentation"));
        assertFalse("update-element 'properties' should be optional",
                updateElementSchema.required().contains("properties"));

        // Story C10: Relationship update tool
        // update-relationship: requires id; optional name, documentation, properties
        McpSchema.JsonSchema updateRelSchema = findTool("update-relationship").inputSchema();
        assertNotNull(updateRelSchema);
        assertTrue("update-relationship should have 'id' property",
                updateRelSchema.properties().containsKey("id"));
        assertTrue("update-relationship should have 'name' property",
                updateRelSchema.properties().containsKey("name"));
        assertTrue("update-relationship should have 'documentation' property",
                updateRelSchema.properties().containsKey("documentation"));
        assertTrue("update-relationship should have 'properties' property",
                updateRelSchema.properties().containsKey("properties"));
        assertNotNull("update-relationship should have required list",
                updateRelSchema.required());
        assertTrue("update-relationship should require 'id'",
                updateRelSchema.required().contains("id"));
        assertFalse("update-relationship 'name' should be optional",
                updateRelSchema.required().contains("name"));
        assertFalse("update-relationship 'documentation' should be optional",
                updateRelSchema.required().contains("documentation"));
        assertFalse("update-relationship 'properties' should be optional",
                updateRelSchema.required().contains("properties"));

        // Story 7-4: create-element force parameter
        assertTrue("create-element should have 'force' property",
                createElementSchema.properties().containsKey("force"));
        Map<String, Object> forceProp =
                (Map<String, Object>) createElementSchema.properties().get("force");
        assertEquals("create-element 'force' should be boolean", "boolean", forceProp.get("type"));
        assertFalse("create-element 'force' should be optional",
                createElementSchema.required().contains("force"));

        // Story 7-6: create-element source parameter
        assertTrue("create-element should have 'source' property",
                createElementSchema.properties().containsKey("source"));
        Map<String, Object> createElementSourceProp =
                (Map<String, Object>) createElementSchema.properties().get("source");
        assertEquals("create-element 'source' should be object", "object",
                createElementSourceProp.get("type"));
        assertFalse("create-element 'source' should be optional",
                createElementSchema.required().contains("source"));

        // Story 7-4: Discovery tools
        // get-or-create-element: requires type, name; optional documentation, properties, folderId
        McpSchema.JsonSchema getOrCreateSchema = findTool("get-or-create-element").inputSchema();
        assertNotNull(getOrCreateSchema);
        assertTrue("get-or-create-element should have 'type' property",
                getOrCreateSchema.properties().containsKey("type"));
        assertTrue("get-or-create-element should have 'name' property",
                getOrCreateSchema.properties().containsKey("name"));
        assertTrue("get-or-create-element should have 'documentation' property",
                getOrCreateSchema.properties().containsKey("documentation"));
        assertTrue("get-or-create-element should have 'properties' property",
                getOrCreateSchema.properties().containsKey("properties"));
        assertTrue("get-or-create-element should have 'folderId' property",
                getOrCreateSchema.properties().containsKey("folderId"));
        assertNotNull("get-or-create-element should have required list",
                getOrCreateSchema.required());
        assertTrue("get-or-create-element should require 'type'",
                getOrCreateSchema.required().contains("type"));
        assertTrue("get-or-create-element should require 'name'",
                getOrCreateSchema.required().contains("name"));
        assertFalse("get-or-create-element 'documentation' should be optional",
                getOrCreateSchema.required().contains("documentation"));
        // Story 7-6: get-or-create-element source parameter
        assertTrue("get-or-create-element should have 'source' property",
                getOrCreateSchema.properties().containsKey("source"));
        Map<String, Object> getOrCreateSourceProp =
                (Map<String, Object>) getOrCreateSchema.properties().get("source");
        assertEquals("get-or-create-element 'source' should be object", "object",
                getOrCreateSourceProp.get("type"));
        assertFalse("get-or-create-element 'source' should be optional",
                getOrCreateSchema.required().contains("source"));

        // search-and-create: requires query, createType, createName; optional type, createDocumentation, etc.
        McpSchema.JsonSchema searchAndCreateSchema = findTool("search-and-create").inputSchema();
        assertNotNull(searchAndCreateSchema);
        assertTrue("search-and-create should have 'query' property",
                searchAndCreateSchema.properties().containsKey("query"));
        assertTrue("search-and-create should have 'createType' property",
                searchAndCreateSchema.properties().containsKey("createType"));
        assertTrue("search-and-create should have 'createName' property",
                searchAndCreateSchema.properties().containsKey("createName"));
        assertTrue("search-and-create should have 'type' property",
                searchAndCreateSchema.properties().containsKey("type"));
        assertNotNull("search-and-create should have required list",
                searchAndCreateSchema.required());
        assertTrue("search-and-create should require 'query'",
                searchAndCreateSchema.required().contains("query"));
        assertTrue("search-and-create should require 'createType'",
                searchAndCreateSchema.required().contains("createType"));
        assertTrue("search-and-create should require 'createName'",
                searchAndCreateSchema.required().contains("createName"));
        assertFalse("search-and-create 'type' should be optional",
                searchAndCreateSchema.required().contains("type"));
        // Story 7-6: search-and-create createSource parameter
        assertTrue("search-and-create should have 'createSource' property",
                searchAndCreateSchema.properties().containsKey("createSource"));
        Map<String, Object> searchCreateSourceProp =
                (Map<String, Object>) searchAndCreateSchema.properties().get("createSource");
        assertEquals("search-and-create 'createSource' should be object", "object",
                searchCreateSourceProp.get("type"));
        assertFalse("search-and-create 'createSource' should be optional",
                searchAndCreateSchema.required().contains("createSource"));

        // Story 7-5: Bulk mutation tool
        // bulk-mutate: requires operations; optional description
        McpSchema.JsonSchema bulkMutateSchema = findTool("bulk-mutate").inputSchema();
        assertNotNull(bulkMutateSchema);
        assertTrue("bulk-mutate should have 'operations' property",
                bulkMutateSchema.properties().containsKey("operations"));
        assertTrue("bulk-mutate should have 'description' property",
                bulkMutateSchema.properties().containsKey("description"));
        assertNotNull("bulk-mutate should have required list",
                bulkMutateSchema.required());
        assertTrue("bulk-mutate should require 'operations'",
                bulkMutateSchema.required().contains("operations"));
        assertFalse("bulk-mutate 'description' should be optional",
                bulkMutateSchema.required().contains("description"));
        Map<String, Object> operationsProp =
                (Map<String, Object>) bulkMutateSchema.properties().get("operations");
        assertEquals("bulk-mutate 'operations' should be array type", "array", operationsProp.get("type"));

        // Story 7-7: View placement tools
        // add-to-view: requires viewId, elementId; optional x, y, width, height, autoConnect
        McpSchema.JsonSchema addToViewSchema = findTool("add-to-view").inputSchema();
        assertNotNull(addToViewSchema);
        assertTrue("add-to-view should have 'viewId' property",
                addToViewSchema.properties().containsKey("viewId"));
        assertTrue("add-to-view should have 'elementId' property",
                addToViewSchema.properties().containsKey("elementId"));
        assertTrue("add-to-view should have 'x' property",
                addToViewSchema.properties().containsKey("x"));
        assertTrue("add-to-view should have 'y' property",
                addToViewSchema.properties().containsKey("y"));
        assertTrue("add-to-view should have 'width' property",
                addToViewSchema.properties().containsKey("width"));
        assertTrue("add-to-view should have 'height' property",
                addToViewSchema.properties().containsKey("height"));
        assertTrue("add-to-view should have 'autoConnect' property",
                addToViewSchema.properties().containsKey("autoConnect"));
        assertNotNull("add-to-view should have required list",
                addToViewSchema.required());
        assertTrue("add-to-view should require 'viewId'",
                addToViewSchema.required().contains("viewId"));
        assertTrue("add-to-view should require 'elementId'",
                addToViewSchema.required().contains("elementId"));
        assertFalse("add-to-view 'x' should be optional",
                addToViewSchema.required().contains("x"));
        assertFalse("add-to-view 'y' should be optional",
                addToViewSchema.required().contains("y"));
        assertFalse("add-to-view 'width' should be optional",
                addToViewSchema.required().contains("width"));
        assertFalse("add-to-view 'height' should be optional",
                addToViewSchema.required().contains("height"));
        assertFalse("add-to-view 'autoConnect' should be optional",
                addToViewSchema.required().contains("autoConnect"));
        Map<String, Object> autoConnectProp =
                (Map<String, Object>) addToViewSchema.properties().get("autoConnect");
        assertEquals("add-to-view 'autoConnect' should be boolean", "boolean", autoConnectProp.get("type"));
        Map<String, Object> xProp =
                (Map<String, Object>) addToViewSchema.properties().get("x");
        assertEquals("add-to-view 'x' should be integer", "integer", xProp.get("type"));

        // add-connection-to-view: requires viewId, relationshipId, sourceViewObjectId, targetViewObjectId; optional bendpoints
        McpSchema.JsonSchema addConnSchema = findTool("add-connection-to-view").inputSchema();
        assertNotNull(addConnSchema);
        assertTrue("add-connection-to-view should have 'viewId' property",
                addConnSchema.properties().containsKey("viewId"));
        assertTrue("add-connection-to-view should have 'relationshipId' property",
                addConnSchema.properties().containsKey("relationshipId"));
        assertTrue("add-connection-to-view should have 'sourceViewObjectId' property",
                addConnSchema.properties().containsKey("sourceViewObjectId"));
        assertTrue("add-connection-to-view should have 'targetViewObjectId' property",
                addConnSchema.properties().containsKey("targetViewObjectId"));
        assertTrue("add-connection-to-view should have 'bendpoints' property",
                addConnSchema.properties().containsKey("bendpoints"));
        assertNotNull("add-connection-to-view should have required list",
                addConnSchema.required());
        assertTrue("add-connection-to-view should require 'viewId'",
                addConnSchema.required().contains("viewId"));
        assertTrue("add-connection-to-view should require 'relationshipId'",
                addConnSchema.required().contains("relationshipId"));
        assertTrue("add-connection-to-view should require 'sourceViewObjectId'",
                addConnSchema.required().contains("sourceViewObjectId"));
        assertTrue("add-connection-to-view should require 'targetViewObjectId'",
                addConnSchema.required().contains("targetViewObjectId"));
        assertFalse("add-connection-to-view 'bendpoints' should be optional",
                addConnSchema.required().contains("bendpoints"));
        Map<String, Object> bendpointsProp =
                (Map<String, Object>) addConnSchema.properties().get("bendpoints");
        assertEquals("add-connection-to-view 'bendpoints' should be array type", "array",
                bendpointsProp.get("type"));
        // Story 8-0d: absoluteBendpoints should be present and optional
        assertTrue("add-connection-to-view should have 'absoluteBendpoints' property",
                addConnSchema.properties().containsKey("absoluteBendpoints"));
        assertFalse("add-connection-to-view 'absoluteBendpoints' should be optional",
                addConnSchema.required().contains("absoluteBendpoints"));
        @SuppressWarnings("unchecked")
        Map<String, Object> addConnAbsBpProp =
                (Map<String, Object>) addConnSchema.properties().get("absoluteBendpoints");
        assertEquals("add-connection-to-view 'absoluteBendpoints' should be array type", "array",
                addConnAbsBpProp.get("type"));

        // Story 7-8: View editing and removal tools
        // update-view-object: requires viewObjectId; optional x, y, width, height
        McpSchema.JsonSchema updateVoSchema = findTool("update-view-object").inputSchema();
        assertNotNull(updateVoSchema);
        assertTrue("update-view-object should have 'viewObjectId' property",
                updateVoSchema.properties().containsKey("viewObjectId"));
        assertTrue("update-view-object should have 'x' property",
                updateVoSchema.properties().containsKey("x"));
        assertTrue("update-view-object should have 'y' property",
                updateVoSchema.properties().containsKey("y"));
        assertTrue("update-view-object should have 'width' property",
                updateVoSchema.properties().containsKey("width"));
        assertTrue("update-view-object should have 'height' property",
                updateVoSchema.properties().containsKey("height"));
        assertNotNull("update-view-object should have required list",
                updateVoSchema.required());
        assertTrue("update-view-object should require 'viewObjectId'",
                updateVoSchema.required().contains("viewObjectId"));
        assertFalse("update-view-object 'x' should be optional",
                updateVoSchema.required().contains("x"));
        assertFalse("update-view-object 'y' should be optional",
                updateVoSchema.required().contains("y"));

        // update-view-connection: requires viewConnectionId; optional bendpoints, absoluteBendpoints
        McpSchema.JsonSchema updateVcSchema = findTool("update-view-connection").inputSchema();
        assertNotNull(updateVcSchema);
        assertTrue("update-view-connection should have 'viewConnectionId' property",
                updateVcSchema.properties().containsKey("viewConnectionId"));
        assertTrue("update-view-connection should have 'bendpoints' property",
                updateVcSchema.properties().containsKey("bendpoints"));
        assertNotNull("update-view-connection should have required list",
                updateVcSchema.required());
        assertTrue("update-view-connection should require 'viewConnectionId'",
                updateVcSchema.required().contains("viewConnectionId"));
        // Story 8-0d: bendpoints is now optional (was required)
        assertFalse("update-view-connection 'bendpoints' should be optional",
                updateVcSchema.required().contains("bendpoints"));
        Map<String, Object> updateBpProp =
                (Map<String, Object>) updateVcSchema.properties().get("bendpoints");
        assertEquals("update-view-connection 'bendpoints' should be array type", "array",
                updateBpProp.get("type"));
        // Story 8-0d: absoluteBendpoints should be present and optional
        assertTrue("update-view-connection should have 'absoluteBendpoints' property",
                updateVcSchema.properties().containsKey("absoluteBendpoints"));
        assertFalse("update-view-connection 'absoluteBendpoints' should be optional",
                updateVcSchema.required().contains("absoluteBendpoints"));
        @SuppressWarnings("unchecked")
        Map<String, Object> updateAbsBpProp =
                (Map<String, Object>) updateVcSchema.properties().get("absoluteBendpoints");
        assertEquals("update-view-connection 'absoluteBendpoints' should be array type", "array",
                updateAbsBpProp.get("type"));

        // remove-from-view: requires viewId, viewObjectId
        McpSchema.JsonSchema removeSchema = findTool("remove-from-view").inputSchema();
        assertNotNull(removeSchema);
        assertTrue("remove-from-view should have 'viewId' property",
                removeSchema.properties().containsKey("viewId"));
        assertTrue("remove-from-view should have 'viewObjectId' property",
                removeSchema.properties().containsKey("viewObjectId"));
        assertNotNull("remove-from-view should have required list",
                removeSchema.required());
        assertTrue("remove-from-view should require 'viewId'",
                removeSchema.required().contains("viewId"));
        assertTrue("remove-from-view should require 'viewObjectId'",
                removeSchema.required().contains("viewObjectId"));

        // Story 8-0c: clear-view: requires viewId
        McpSchema.JsonSchema clearViewSchema = findTool("clear-view").inputSchema();
        assertNotNull(clearViewSchema);
        assertTrue("clear-view should have 'viewId' property",
                clearViewSchema.properties().containsKey("viewId"));
        assertNotNull("clear-view should have required list",
                clearViewSchema.required());
        assertTrue("clear-view should require 'viewId'",
                clearViewSchema.required().contains("viewId"));

        // Story 7-6: Approval tools
        // set-approval-mode: requires enabled (boolean)
        McpSchema.JsonSchema setApprovalSchema = findTool("set-approval-mode").inputSchema();
        assertNotNull(setApprovalSchema);
        assertTrue("set-approval-mode should have 'enabled' property",
                setApprovalSchema.properties().containsKey("enabled"));
        Map<String, Object> enabledProp =
                (Map<String, Object>) setApprovalSchema.properties().get("enabled");
        assertEquals("set-approval-mode 'enabled' should be boolean", "boolean", enabledProp.get("type"));
        assertNotNull("set-approval-mode should have required list",
                setApprovalSchema.required());
        assertTrue("set-approval-mode should require 'enabled'",
                setApprovalSchema.required().contains("enabled"));

        // list-pending-approvals: no required params
        McpSchema.JsonSchema listApprovalsSchema = findTool("list-pending-approvals").inputSchema();
        assertNotNull(listApprovalsSchema);
        assertTrue("list-pending-approvals should have no required params",
                listApprovalsSchema.required() == null || listApprovalsSchema.required().isEmpty());

        // decide-mutation: requires proposalId, decision; optional reason
        McpSchema.JsonSchema decideMutationSchema = findTool("decide-mutation").inputSchema();
        assertNotNull(decideMutationSchema);
        assertTrue("decide-mutation should have 'proposalId' property",
                decideMutationSchema.properties().containsKey("proposalId"));
        assertTrue("decide-mutation should have 'decision' property",
                decideMutationSchema.properties().containsKey("decision"));
        assertTrue("decide-mutation should have 'reason' property",
                decideMutationSchema.properties().containsKey("reason"));
        assertNotNull("decide-mutation should have required list",
                decideMutationSchema.required());
        assertTrue("decide-mutation should require 'proposalId'",
                decideMutationSchema.required().contains("proposalId"));
        assertTrue("decide-mutation should require 'decision'",
                decideMutationSchema.required().contains("decision"));
        assertFalse("decide-mutation 'reason' should be optional",
                decideMutationSchema.required().contains("reason"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveExportViewSchema() {
        McpSchema.JsonSchema schema = findTool("export-view").inputSchema();
        assertNotNull(schema);

        Map<String, Object> props = schema.properties();
        assertTrue("export-view should have 'viewId' property",
                props.containsKey("viewId"));
        assertTrue("export-view should have 'format' property",
                props.containsKey("format"));
        assertTrue("export-view should have 'scale' property",
                props.containsKey("scale"));
        assertTrue("export-view should have 'inline' property",
                props.containsKey("inline"));

        assertNotNull("export-view should have required list", schema.required());
        assertTrue("export-view should require 'viewId'",
                schema.required().contains("viewId"));
        assertFalse("export-view 'format' should be optional",
                schema.required().contains("format"));
        assertFalse("export-view 'scale' should be optional",
                schema.required().contains("scale"));
        assertFalse("export-view 'inline' should be optional",
                schema.required().contains("inline"));

        // Verify format enum values
        Map<String, Object> formatProp = (Map<String, Object>) props.get("format");
        assertNotNull("format property should have enum", formatProp.get("enum"));
        List<String> formatEnum = (List<String>) formatProp.get("enum");
        assertTrue("format enum should contain 'png'", formatEnum.contains("png"));
        assertTrue("format enum should contain 'svg'", formatEnum.contains("svg"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveUpdateViewSchema() {
        McpSchema.JsonSchema schema = findTool("update-view").inputSchema();
        assertNotNull(schema);

        Map<String, Object> props = schema.properties();
        assertTrue("update-view should have 'viewId' property",
                props.containsKey("viewId"));
        assertTrue("update-view should have 'name' property",
                props.containsKey("name"));
        assertTrue("update-view should have 'viewpoint' property",
                props.containsKey("viewpoint"));
        assertTrue("update-view should have 'documentation' property",
                props.containsKey("documentation"));
        assertTrue("update-view should have 'properties' property",
                props.containsKey("properties"));

        assertNotNull("update-view should have required list", schema.required());
        assertTrue("update-view should require 'viewId'",
                schema.required().contains("viewId"));
        assertEquals("update-view should require only 'viewId'",
                1, schema.required().size());
    }

    @Test
    public void shouldHaveNonEmptyDescriptions() {
        for (McpServerFeatures.SyncToolSpecification spec : registry.getToolSpecifications()) {
            String toolName = spec.tool().name();
            String description = spec.tool().description();
            assertNotNull("Description null for " + toolName, description);
            assertFalse("Description empty for " + toolName, description.isBlank());
        }
    }

    // ---- Helper Methods ----

    @SuppressWarnings("unchecked")
    private void assertFieldSelectionParams(String toolName, McpSchema.JsonSchema schema) {
        assertTrue(toolName + " should have 'fields' property",
                schema.properties().containsKey("fields"));
        Map<String, Object> fieldsProp =
                (Map<String, Object>) schema.properties().get("fields");
        assertEquals(toolName + " 'fields' should be string type",
                "string", fieldsProp.get("type"));

        assertTrue(toolName + " should have 'exclude' property",
                schema.properties().containsKey("exclude"));
        Map<String, Object> excludeProp =
                (Map<String, Object>) schema.properties().get("exclude");
        assertEquals(toolName + " 'exclude' should be array type",
                "array", excludeProp.get("type"));
    }

    private McpSchema.Tool findTool(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName))
                .tool();
    }

}
