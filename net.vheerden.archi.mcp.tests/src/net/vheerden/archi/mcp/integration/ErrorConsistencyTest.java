package net.vheerden.archi.mcp.integration;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.ApprovalHandler;
import net.vheerden.archi.mcp.handlers.DiscoveryHandler;
import net.vheerden.archi.mcp.handlers.ElementCreationHandler;
import net.vheerden.archi.mcp.handlers.ElementUpdateHandler;
import net.vheerden.archi.mcp.handlers.FolderHandler;
import net.vheerden.archi.mcp.handlers.ModelQueryHandler;
import net.vheerden.archi.mcp.handlers.MutationHandler;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.handlers.TraversalHandler;
import net.vheerden.archi.mcp.handlers.ViewHandler;
import net.vheerden.archi.mcp.handlers.ViewPlacementHandler;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.DuplicateCandidate;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Integration test verifying error response consistency across all tools.
 * Tests AC #3: all tools produce structured errors in identical format.
 *
 * <p>Uses three accessor configurations: no model loaded, model loaded (for not-found),
 * and exploding accessor (for internal errors).</p>
 */
public class ErrorConsistencyTest {

    private CommandRegistry registryNoModel;
    private CommandRegistry registryWithModel;
    private CommandRegistry registryExploding;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        ResponseFormatter formatter = new ResponseFormatter();

        // Setup 1: No model loaded (reuse shared stub with modelLoaded=false)
        registryNoModel = new CommandRegistry();
        ArchiModelAccessor noModelAccessor = new IntegrationStubAccessor(false);
        new ModelQueryHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();
        new ViewHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();
        new SearchHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();
        new TraversalHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();
        new FolderHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();
        new MutationHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();
        new ElementCreationHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();
        new ElementUpdateHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();
        new DiscoveryHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();
        new ApprovalHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();
        new ViewPlacementHandler(noModelAccessor, formatter, registryNoModel, null).registerTools();

        // Setup 2: Model loaded (for not-found errors — returns empty for unknown IDs)
        registryWithModel = new CommandRegistry();
        ArchiModelAccessor loadedAccessor = new LoadedStubAccessor();
        new ModelQueryHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();
        new ViewHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();
        new SearchHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();
        new TraversalHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();
        new FolderHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();
        new MutationHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();
        new ElementCreationHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();
        new ElementUpdateHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();
        new DiscoveryHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();
        new ApprovalHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();
        new ViewPlacementHandler(loadedAccessor, formatter, registryWithModel, null).registerTools();

        // Setup 3: Exploding accessor (for internal errors)
        registryExploding = new CommandRegistry();
        ArchiModelAccessor explodingAccessor = new ExplodingAccessor();
        new ModelQueryHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
        new ViewHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
        new SearchHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
        new TraversalHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
        new FolderHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
        new MutationHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
        new ElementCreationHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
        new ElementUpdateHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
        new DiscoveryHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
        new ApprovalHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
        new ViewPlacementHandler(explodingAccessor, formatter, registryExploding, null).registerTools();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnConsistentModelNotLoadedError() throws Exception {
        // All non-session tools with no model loaded should return MODEL_NOT_LOADED
        // Note: Map.of() maxes at 10 entries, use Map.ofEntries for larger maps
        Map<String, Map<String, Object>> toolArgs = Map.ofEntries(
                Map.entry("get-model-info", Collections.emptyMap()),
                Map.entry("get-element", Map.of("id", "any-id")),
                Map.entry("get-views", Collections.emptyMap()),
                Map.entry("get-view-contents", Map.of("viewId", "any-view")),
                Map.entry("search-elements", Map.of("query", "anything")),
                Map.entry("get-relationships", Map.of("elementId", "any-id")),
                Map.entry("get-folders", Collections.emptyMap()),
                Map.entry("get-folder-tree", Collections.emptyMap()),
                Map.entry("begin-batch", Collections.emptyMap()),
                Map.entry("end-batch", Collections.emptyMap()),
                Map.entry("get-batch-status", Collections.emptyMap()),
                Map.entry("create-element", Map.of("type", "BusinessActor", "name", "Test")),
                Map.entry("create-relationship", Map.of("type", "ServingRelationship", "sourceId", "s1", "targetId", "t1")),
                Map.entry("create-view", Map.of("name", "Test View")),
                Map.entry("update-element", Map.of("id", "elem-1", "name", "Updated")),
                Map.entry("get-or-create-element", Map.of("type", "BusinessActor", "name", "Test")),
                Map.entry("search-and-create", Map.of("query", "test", "createType", "BusinessActor", "createName", "Test")),
                Map.entry("bulk-mutate", Map.of("operations", List.of(Map.of("tool", "create-element", "params", Map.of("type", "BusinessActor", "name", "Test"))))),
                Map.entry("set-approval-mode", Map.of("enabled", true)),
                Map.entry("list-pending-approvals", Collections.emptyMap()),
                Map.entry("decide-mutation", Map.of("proposalId", "p-1", "decision", "approve")),
                Map.entry("add-to-view", Map.of("viewId", "v-1", "elementId", "e-1")),
                Map.entry("add-connection-to-view", Map.of("viewId", "v-1", "relationshipId", "r-1", "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2")),
                Map.entry("update-view-object", Map.of("viewObjectId", "vo-1", "x", 100)),
                Map.entry("update-view-connection", Map.of("viewConnectionId", "vc-1", "bendpoints", List.of())),
                Map.entry("remove-from-view", Map.of("viewId", "v-1", "viewObjectId", "vo-1")));

        for (Map.Entry<String, Map<String, Object>> entry : toolArgs.entrySet()) {
            String toolName = entry.getKey();
            McpSchema.CallToolResult result = invokeTool(registryNoModel, toolName, entry.getValue());
            assertTrue(toolName + " should be an error", result.isError());

            Map<String, Object> envelope = parseJson(result);
            Map<String, Object> error = (Map<String, Object>) envelope.get("error");
            assertNotNull(toolName + " should have error object", error);
            assertEquals(toolName + " should have MODEL_NOT_LOADED code",
                    "MODEL_NOT_LOADED", error.get("code"));
            assertNotNull(toolName + " should have message", error.get("message"));
            assertEquals(toolName + " should have consistent suggestedCorrection",
                    "Open an ArchiMate model in ArchimateTool", error.get("suggestedCorrection"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnConsistentNotFoundErrors() throws Exception {
        // get-element with invalid ID → ELEMENT_NOT_FOUND
        McpSchema.CallToolResult elementResult = invokeTool(registryWithModel, "get-element",
                Map.of("id", "nonexistent-id"));
        assertTrue("get-element should be an error", elementResult.isError());
        Map<String, Object> elementEnvelope = parseJson(elementResult);
        Map<String, Object> elementError = (Map<String, Object>) elementEnvelope.get("error");
        assertNotNull("get-element should have error object", elementError);
        assertEquals("ELEMENT_NOT_FOUND", elementError.get("code"));
        assertNotNull("ELEMENT_NOT_FOUND should have message", elementError.get("message"));
        assertNotNull("ELEMENT_NOT_FOUND should have suggestedCorrection",
                elementError.get("suggestedCorrection"));

        // get-view-contents with invalid viewId → VIEW_NOT_FOUND
        McpSchema.CallToolResult viewResult = invokeTool(registryWithModel, "get-view-contents",
                Map.of("viewId", "nonexistent-view"));
        assertTrue("get-view-contents should be an error", viewResult.isError());
        Map<String, Object> viewEnvelope = parseJson(viewResult);
        Map<String, Object> viewError = (Map<String, Object>) viewEnvelope.get("error");
        assertNotNull("get-view-contents should have error object", viewError);
        assertEquals("VIEW_NOT_FOUND", viewError.get("code"));
        assertNotNull("VIEW_NOT_FOUND should have message", viewError.get("message"));
        assertNotNull("VIEW_NOT_FOUND should have suggestedCorrection",
                viewError.get("suggestedCorrection"));

        // get-relationships with invalid elementId → ELEMENT_NOT_FOUND
        McpSchema.CallToolResult relResult = invokeTool(registryWithModel, "get-relationships",
                Map.of("elementId", "nonexistent-id"));
        assertTrue("get-relationships should be an error", relResult.isError());
        Map<String, Object> relEnvelope = parseJson(relResult);
        Map<String, Object> relError = (Map<String, Object>) relEnvelope.get("error");
        assertNotNull("get-relationships should have error object", relError);
        assertEquals("ELEMENT_NOT_FOUND", relError.get("code"));
        assertNotNull("ELEMENT_NOT_FOUND should have message", relError.get("message"));
        assertNotNull("ELEMENT_NOT_FOUND should have suggestedCorrection",
                relError.get("suggestedCorrection"));

        // get-folders with invalid parentId → FOLDER_NOT_FOUND
        McpSchema.CallToolResult folderResult = invokeTool(registryWithModel, "get-folders",
                Map.of("parentId", "nonexistent-folder"));
        assertTrue("get-folders should be an error", folderResult.isError());
        Map<String, Object> folderEnvelope = parseJson(folderResult);
        Map<String, Object> folderError = (Map<String, Object>) folderEnvelope.get("error");
        assertNotNull("get-folders should have error object", folderError);
        assertEquals("FOLDER_NOT_FOUND", folderError.get("code"));
        assertNotNull("FOLDER_NOT_FOUND should have message", folderError.get("message"));
        assertNotNull("FOLDER_NOT_FOUND should have suggestedCorrection",
                folderError.get("suggestedCorrection"));

        // get-folder-tree with invalid rootId → FOLDER_NOT_FOUND
        McpSchema.CallToolResult folderTreeResult = invokeTool(registryWithModel, "get-folder-tree",
                Map.of("rootId", "nonexistent-folder"));
        assertTrue("get-folder-tree should be an error", folderTreeResult.isError());
        Map<String, Object> folderTreeEnvelope = parseJson(folderTreeResult);
        Map<String, Object> folderTreeError = (Map<String, Object>) folderTreeEnvelope.get("error");
        assertNotNull("get-folder-tree should have error object", folderTreeError);
        assertEquals("FOLDER_NOT_FOUND", folderTreeError.get("code"));
        assertNotNull("FOLDER_NOT_FOUND should have message", folderTreeError.get("message"));
        assertNotNull("FOLDER_NOT_FOUND should have suggestedCorrection",
                folderTreeError.get("suggestedCorrection"));

        // All not-found errors wrap in "error" envelope with code + message + suggestedCorrection
        // (optional fields like 'details' may differ between error types — that's acceptable)
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnConsistentInvalidParameterErrors() throws Exception {
        // get-element with missing id
        McpSchema.CallToolResult elementResult = invokeTool(registryWithModel, "get-element",
                Collections.emptyMap());
        assertTrue("get-element should be an error", elementResult.isError());
        Map<String, Object> elementEnvelope = parseJson(elementResult);
        Map<String, Object> elementError = (Map<String, Object>) elementEnvelope.get("error");
        assertNotNull(elementError);
        assertEquals("INVALID_PARAMETER", elementError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", elementError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                elementError.get("suggestedCorrection"));

        // get-view-contents with missing viewId
        McpSchema.CallToolResult viewResult = invokeTool(registryWithModel, "get-view-contents",
                Collections.emptyMap());
        assertTrue("get-view-contents should be an error", viewResult.isError());
        Map<String, Object> viewEnvelope = parseJson(viewResult);
        Map<String, Object> viewError = (Map<String, Object>) viewEnvelope.get("error");
        assertNotNull(viewError);
        assertEquals("INVALID_PARAMETER", viewError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", viewError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                viewError.get("suggestedCorrection"));

        // search-elements with missing query
        McpSchema.CallToolResult searchResult = invokeTool(registryWithModel, "search-elements",
                Collections.emptyMap());
        assertTrue("search-elements should be an error", searchResult.isError());
        Map<String, Object> searchEnvelope = parseJson(searchResult);
        Map<String, Object> searchError = (Map<String, Object>) searchEnvelope.get("error");
        assertNotNull(searchError);
        assertEquals("INVALID_PARAMETER", searchError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", searchError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                searchError.get("suggestedCorrection"));

        // get-relationships with missing elementId
        McpSchema.CallToolResult relResult = invokeTool(registryWithModel, "get-relationships",
                Collections.emptyMap());
        assertTrue("get-relationships should be an error", relResult.isError());
        Map<String, Object> relEnvelope = parseJson(relResult);
        Map<String, Object> relError = (Map<String, Object>) relEnvelope.get("error");
        assertNotNull(relError);
        assertEquals("INVALID_PARAMETER", relError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", relError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                relError.get("suggestedCorrection"));

        // create-element with missing type
        McpSchema.CallToolResult createElemResult = invokeTool(registryWithModel, "create-element",
                Map.of("name", "Test"));
        assertTrue("create-element should be an error", createElemResult.isError());
        Map<String, Object> createElemEnvelope = parseJson(createElemResult);
        Map<String, Object> createElemError = (Map<String, Object>) createElemEnvelope.get("error");
        assertNotNull(createElemError);
        assertEquals("INVALID_PARAMETER", createElemError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", createElemError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                createElemError.get("suggestedCorrection"));

        // create-relationship with missing sourceId
        McpSchema.CallToolResult createRelResult = invokeTool(registryWithModel, "create-relationship",
                Map.of("type", "ServingRelationship", "targetId", "t1"));
        assertTrue("create-relationship should be an error", createRelResult.isError());
        Map<String, Object> createRelEnvelope = parseJson(createRelResult);
        Map<String, Object> createRelError = (Map<String, Object>) createRelEnvelope.get("error");
        assertNotNull(createRelError);
        assertEquals("INVALID_PARAMETER", createRelError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", createRelError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                createRelError.get("suggestedCorrection"));

        // create-view with missing name
        McpSchema.CallToolResult createViewResult = invokeTool(registryWithModel, "create-view",
                Collections.emptyMap());
        assertTrue("create-view should be an error", createViewResult.isError());
        Map<String, Object> createViewEnvelope = parseJson(createViewResult);
        Map<String, Object> createViewError = (Map<String, Object>) createViewEnvelope.get("error");
        assertNotNull(createViewError);
        assertEquals("INVALID_PARAMETER", createViewError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", createViewError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                createViewError.get("suggestedCorrection"));

        // update-element with missing id
        McpSchema.CallToolResult updateResult = invokeTool(registryWithModel, "update-element",
                Map.of("name", "Updated"));
        assertTrue("update-element should be an error", updateResult.isError());
        Map<String, Object> updateEnvelope = parseJson(updateResult);
        Map<String, Object> updateError = (Map<String, Object>) updateEnvelope.get("error");
        assertNotNull(updateError);
        assertEquals("INVALID_PARAMETER", updateError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", updateError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                updateError.get("suggestedCorrection"));

        // get-or-create-element with missing type
        McpSchema.CallToolResult getOrCreateResult = invokeTool(registryWithModel, "get-or-create-element",
                Map.of("name", "Test"));
        assertTrue("get-or-create-element should be an error", getOrCreateResult.isError());
        Map<String, Object> getOrCreateEnvelope = parseJson(getOrCreateResult);
        Map<String, Object> getOrCreateError = (Map<String, Object>) getOrCreateEnvelope.get("error");
        assertNotNull(getOrCreateError);
        assertEquals("INVALID_PARAMETER", getOrCreateError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", getOrCreateError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                getOrCreateError.get("suggestedCorrection"));

        // search-and-create with missing query
        McpSchema.CallToolResult searchAndCreateResult = invokeTool(registryWithModel, "search-and-create",
                Map.of("createType", "BusinessActor", "createName", "Test"));
        assertTrue("search-and-create should be an error", searchAndCreateResult.isError());
        Map<String, Object> searchAndCreateEnvelope = parseJson(searchAndCreateResult);
        Map<String, Object> searchAndCreateError = (Map<String, Object>) searchAndCreateEnvelope.get("error");
        assertNotNull(searchAndCreateError);
        assertEquals("INVALID_PARAMETER", searchAndCreateError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", searchAndCreateError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                searchAndCreateError.get("suggestedCorrection"));

        // bulk-mutate with empty operations
        McpSchema.CallToolResult bulkResult = invokeTool(registryWithModel, "bulk-mutate",
                Map.of("operations", List.of()));
        assertTrue("bulk-mutate should be an error", bulkResult.isError());
        Map<String, Object> bulkEnvelope = parseJson(bulkResult);
        Map<String, Object> bulkError = (Map<String, Object>) bulkEnvelope.get("error");
        assertNotNull(bulkError);
        assertEquals("INVALID_PARAMETER", bulkError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", bulkError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                bulkError.get("suggestedCorrection"));

        // add-to-view with missing viewId
        McpSchema.CallToolResult addToViewResult = invokeTool(registryWithModel, "add-to-view",
                Map.of("elementId", "e-1"));
        assertTrue("add-to-view should be an error", addToViewResult.isError());
        Map<String, Object> addToViewEnvelope = parseJson(addToViewResult);
        Map<String, Object> addToViewError = (Map<String, Object>) addToViewEnvelope.get("error");
        assertNotNull(addToViewError);
        assertEquals("INVALID_PARAMETER", addToViewError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", addToViewError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                addToViewError.get("suggestedCorrection"));

        // add-connection-to-view with missing relationshipId
        McpSchema.CallToolResult addConnResult = invokeTool(registryWithModel, "add-connection-to-view",
                Map.of("viewId", "v-1", "sourceViewObjectId", "vo-1", "targetViewObjectId", "vo-2"));
        assertTrue("add-connection-to-view should be an error", addConnResult.isError());
        Map<String, Object> addConnEnvelope = parseJson(addConnResult);
        Map<String, Object> addConnError = (Map<String, Object>) addConnEnvelope.get("error");
        assertNotNull(addConnError);
        assertEquals("INVALID_PARAMETER", addConnError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", addConnError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                addConnError.get("suggestedCorrection"));

        // update-view-object with missing viewObjectId
        McpSchema.CallToolResult updateVoResult = invokeTool(registryWithModel, "update-view-object",
                Map.of("x", 100));
        assertTrue("update-view-object should be an error", updateVoResult.isError());
        Map<String, Object> updateVoEnvelope = parseJson(updateVoResult);
        Map<String, Object> updateVoError = (Map<String, Object>) updateVoEnvelope.get("error");
        assertNotNull(updateVoError);
        assertEquals("INVALID_PARAMETER", updateVoError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", updateVoError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                updateVoError.get("suggestedCorrection"));

        // update-view-connection with missing viewConnectionId
        McpSchema.CallToolResult updateVcResult = invokeTool(registryWithModel, "update-view-connection",
                Map.of("bendpoints", List.of()));
        assertTrue("update-view-connection should be an error", updateVcResult.isError());
        Map<String, Object> updateVcEnvelope = parseJson(updateVcResult);
        Map<String, Object> updateVcError = (Map<String, Object>) updateVcEnvelope.get("error");
        assertNotNull(updateVcError);
        assertEquals("INVALID_PARAMETER", updateVcError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", updateVcError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                updateVcError.get("suggestedCorrection"));

        // remove-from-view with missing viewId
        McpSchema.CallToolResult removeResult = invokeTool(registryWithModel, "remove-from-view",
                Map.of("viewObjectId", "vo-1"));
        assertTrue("remove-from-view should be an error", removeResult.isError());
        Map<String, Object> removeEnvelope = parseJson(removeResult);
        Map<String, Object> removeError = (Map<String, Object>) removeEnvelope.get("error");
        assertNotNull(removeError);
        assertEquals("INVALID_PARAMETER", removeError.get("code"));
        assertNotNull("INVALID_PARAMETER should have message", removeError.get("message"));
        assertNotNull("INVALID_PARAMETER should have suggestedCorrection",
                removeError.get("suggestedCorrection"));

        // All INVALID_PARAMETER errors wrap in "error" envelope with code + message + suggestedCorrection
        // (optional fields like 'details' may differ between handlers — that's acceptable)
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnConsistentInternalErrors() throws Exception {
        // All 8 query tools with exploding accessor should return INTERNAL_ERROR
        // (Mutation tools don't use exploding accessor — they test batch state, not model queries)
        Map<String, Map<String, Object>> toolArgs = Map.of(
                "get-model-info", Collections.emptyMap(),
                "get-element", Map.of("id", "any-id"),
                "get-views", Collections.emptyMap(),
                "get-view-contents", Map.of("viewId", "any-view"),
                "search-elements", Map.of("query", "anything"),
                "get-relationships", Map.of("elementId", "any-id"),
                "get-folders", Collections.emptyMap(),
                "get-folder-tree", Collections.emptyMap());

        for (Map.Entry<String, Map<String, Object>> entry : toolArgs.entrySet()) {
            String toolName = entry.getKey();
            McpSchema.CallToolResult result = invokeTool(registryExploding, toolName, entry.getValue());
            assertTrue(toolName + " should be an error", result.isError());

            Map<String, Object> envelope = parseJson(result);
            Map<String, Object> error = (Map<String, Object>) envelope.get("error");
            assertNotNull(toolName + " should have error object", error);
            assertEquals(toolName + " should have INTERNAL_ERROR code",
                    "INTERNAL_ERROR", error.get("code"));
            assertNotNull(toolName + " should have message", error.get("message"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNeverExposeJavaExceptionMessages() throws Exception {
        // Internal errors should NOT contain Java class names or "Boom" (the RuntimeException message)
        Map<String, Map<String, Object>> toolArgs = Map.of(
                "get-model-info", Collections.emptyMap(),
                "get-element", Map.of("id", "any-id"),
                "get-views", Collections.emptyMap(),
                "get-view-contents", Map.of("viewId", "any-view"),
                "search-elements", Map.of("query", "anything"),
                "get-relationships", Map.of("elementId", "any-id"),
                "get-folders", Collections.emptyMap(),
                "get-folder-tree", Collections.emptyMap());

        for (Map.Entry<String, Map<String, Object>> entry : toolArgs.entrySet()) {
            String toolName = entry.getKey();
            McpSchema.CallToolResult result = invokeTool(registryExploding, toolName, entry.getValue());

            Map<String, Object> envelope = parseJson(result);
            Map<String, Object> error = (Map<String, Object>) envelope.get("error");
            String message = (String) error.get("message");
            String details = (String) error.get("details");
            String fullErrorText = message + (details != null ? " " + details : "");

            assertFalse(toolName + " should not expose 'Boom' exception message",
                    fullErrorText.contains("Boom"));
            assertFalse(toolName + " should not expose RuntimeException class name",
                    fullErrorText.contains("RuntimeException"));
            assertFalse(toolName + " should not expose stack traces",
                    fullErrorText.contains(".java:"));
            assertFalse(toolName + " should not expose 'at ' stack trace prefix",
                    fullErrorText.contains("\tat "));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnConsistentBatchErrors() throws Exception {
        // end-batch when not in batch → BATCH_NOT_ACTIVE
        McpSchema.CallToolResult endResult = invokeTool(registryWithModel, "end-batch",
                Collections.emptyMap());
        assertTrue("end-batch should be an error", endResult.isError());
        Map<String, Object> endEnvelope = parseJson(endResult);
        Map<String, Object> endError = (Map<String, Object>) endEnvelope.get("error");
        assertNotNull("end-batch should have error object", endError);
        assertEquals("BATCH_NOT_ACTIVE", endError.get("code"));
        assertNotNull("BATCH_NOT_ACTIVE should have message", endError.get("message"));
        assertNotNull("BATCH_NOT_ACTIVE should have suggestedCorrection",
                endError.get("suggestedCorrection"));

        // begin-batch → then begin-batch again → BATCH_ALREADY_ACTIVE
        invokeTool(registryWithModel, "begin-batch", Collections.emptyMap());
        McpSchema.CallToolResult beginResult = invokeTool(registryWithModel, "begin-batch",
                Collections.emptyMap());
        assertTrue("begin-batch should be an error", beginResult.isError());
        Map<String, Object> beginEnvelope = parseJson(beginResult);
        Map<String, Object> beginError = (Map<String, Object>) beginEnvelope.get("error");
        assertNotNull("begin-batch should have error object", beginError);
        assertEquals("BATCH_ALREADY_ACTIVE", beginError.get("code"));
        assertNotNull("BATCH_ALREADY_ACTIVE should have message", beginError.get("message"));
        assertNotNull("BATCH_ALREADY_ACTIVE should have suggestedCorrection",
                beginError.get("suggestedCorrection"));

        // Clean up: end the batch so it doesn't affect other tests
        invokeTool(registryWithModel, "end-batch", Map.of("rollback", true));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnConsistentPotentialDuplicatesError() throws Exception {
        // create-element without force → POTENTIAL_DUPLICATES (LoadedStubAccessor returns a canned duplicate)
        McpSchema.CallToolResult result = invokeTool(registryWithModel, "create-element",
                Map.of("type", "BusinessActor", "name", "Test Element"));
        assertTrue("create-element should be an error when duplicates found", result.isError());

        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull("Should have error object", error);
        assertEquals("POTENTIAL_DUPLICATES", error.get("code"));
        assertNotNull("Should have message", error.get("message"));
        assertNotNull("Should have suggestedCorrection", error.get("suggestedCorrection"));

        // Verify duplicates array in error
        List<Map<String, Object>> duplicates = (List<Map<String, Object>>) error.get("duplicates");
        assertNotNull("Should have duplicates array", duplicates);
        assertFalse("Duplicates should not be empty", duplicates.isEmpty());
        assertEquals("dup-1", duplicates.get(0).get("id"));

        // Verify nextSteps
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull("Should have nextSteps", nextSteps);
        assertTrue("nextSteps should mention get-element",
                nextSteps.stream().anyMatch(s -> s.contains("get-element")));
        assertTrue("nextSteps should mention force: true",
                nextSteps.stream().anyMatch(s -> s.contains("force: true")));

        // Verify _meta with modelVersion
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull("Should have _meta", meta);
        assertNotNull("Should have modelVersion in _meta", meta.get("modelVersion"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnConsistentBulkValidationFailedError() throws Exception {
        // bulk-mutate with operations that trigger BULK_VALIDATION_FAILED from accessor
        McpSchema.CallToolResult result = invokeTool(registryWithModel, "bulk-mutate",
                Map.of("operations", List.of(
                        Map.of("tool", "create-element",
                                "params", Map.of("type", "FakeType", "name", "Invalid")))));
        assertTrue("bulk-mutate should be an error", result.isError());

        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull("Should have error object", error);
        assertEquals("BULK_VALIDATION_FAILED", error.get("code"));
        assertNotNull("BULK_VALIDATION_FAILED should have message", error.get("message"));
        assertNotNull("BULK_VALIDATION_FAILED should have suggestedCorrection",
                error.get("suggestedCorrection"));
        assertNotNull("BULK_VALIDATION_FAILED should have details", error.get("details"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnConsistentApprovalErrors() throws Exception {
        // decide-mutation with non-existent proposal → PROPOSAL_NOT_FOUND
        McpSchema.CallToolResult decideResult = invokeTool(registryWithModel, "decide-mutation",
                Map.of("proposalId", "p-999", "decision", "approve"));
        assertTrue("decide-mutation should be an error", decideResult.isError());
        Map<String, Object> decideEnvelope = parseJson(decideResult);
        Map<String, Object> decideError = (Map<String, Object>) decideEnvelope.get("error");
        assertNotNull("decide-mutation should have error object", decideError);
        assertEquals("PROPOSAL_NOT_FOUND", decideError.get("code"));
        assertNotNull("PROPOSAL_NOT_FOUND should have message", decideError.get("message"));
        assertNotNull("PROPOSAL_NOT_FOUND should have suggestedCorrection",
                decideError.get("suggestedCorrection"));

        // decide-mutation with invalid decision string → INVALID_PARAMETER
        McpSchema.CallToolResult invalidDecisionResult = invokeTool(registryWithModel, "decide-mutation",
                Map.of("proposalId", "p-1", "decision", "maybe"));
        assertTrue("decide-mutation should be an error for invalid decision", invalidDecisionResult.isError());
        Map<String, Object> invalidDecisionEnvelope = parseJson(invalidDecisionResult);
        Map<String, Object> invalidDecisionError = (Map<String, Object>) invalidDecisionEnvelope.get("error");
        assertNotNull(invalidDecisionError);
        assertEquals("INVALID_PARAMETER", invalidDecisionError.get("code"));

        // decide-mutation approve-all with no pending → APPROVAL_NOT_ACTIVE
        McpSchema.CallToolResult approveAllResult = invokeTool(registryWithModel, "decide-mutation",
                Map.of("proposalId", "all", "decision", "approve"));
        assertTrue("decide-mutation approve-all should be an error", approveAllResult.isError());
        Map<String, Object> approveAllEnvelope = parseJson(approveAllResult);
        Map<String, Object> approveAllError = (Map<String, Object>) approveAllEnvelope.get("error");
        assertNotNull(approveAllError);
        assertEquals("APPROVAL_NOT_ACTIVE", approveAllError.get("code"));
        assertNotNull("APPROVAL_NOT_ACTIVE should have message", approveAllError.get("message"));
        assertNotNull("APPROVAL_NOT_ACTIVE should have suggestedCorrection",
                approveAllError.get("suggestedCorrection"));

        // set-approval-mode with missing enabled → INVALID_PARAMETER
        McpSchema.CallToolResult setModeResult = invokeTool(registryWithModel, "set-approval-mode",
                Collections.emptyMap());
        assertTrue("set-approval-mode should be an error for missing enabled", setModeResult.isError());
        Map<String, Object> setModeEnvelope = parseJson(setModeResult);
        Map<String, Object> setModeError = (Map<String, Object>) setModeEnvelope.get("error");
        assertNotNull(setModeError);
        assertEquals("INVALID_PARAMETER", setModeError.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnConsistentInvalidParameterErrors_forApprovalTools() throws Exception {
        // decide-mutation with missing proposalId
        McpSchema.CallToolResult missingIdResult = invokeTool(registryWithModel, "decide-mutation",
                Map.of("decision", "approve"));
        assertTrue("decide-mutation should be an error for missing proposalId",
                missingIdResult.isError());
        Map<String, Object> missingIdEnvelope = parseJson(missingIdResult);
        Map<String, Object> missingIdError = (Map<String, Object>) missingIdEnvelope.get("error");
        assertNotNull(missingIdError);
        assertEquals("INVALID_PARAMETER", missingIdError.get("code"));

        // decide-mutation with missing decision
        McpSchema.CallToolResult missingDecisionResult = invokeTool(registryWithModel, "decide-mutation",
                Map.of("proposalId", "p-1"));
        assertTrue("decide-mutation should be an error for missing decision",
                missingDecisionResult.isError());
        Map<String, Object> missingDecisionEnvelope = parseJson(missingDecisionResult);
        Map<String, Object> missingDecisionError =
                (Map<String, Object>) missingDecisionEnvelope.get("error");
        assertNotNull(missingDecisionError);
        assertEquals("INVALID_PARAMETER", missingDecisionError.get("code"));
    }

    // ---- Helper Methods ----

    private McpSchema.CallToolResult invokeTool(CommandRegistry reg, String toolName,
                                                 Map<String, Object> args) {
        McpServerFeatures.SyncToolSpecification spec = reg.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
        return spec.callHandler().apply(null, request);
    }

    private Map<String, Object> parseJson(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().get(0);
        return objectMapper.readValue(content.text(),
                new TypeReference<Map<String, Object>>() {});
    }

    // ---- Stub Accessors ----

    /**
     * Accessor with model loaded but returns empty for unknown IDs.
     * Used to test NOT_FOUND and INVALID_PARAMETER error consistency.
     */
    static class LoadedStubAccessor extends BaseTestAccessor {
        private final MutationDispatcher dispatcher = new MutationDispatcher(() -> null) {
            @Override
            protected void dispatchCommand(org.eclipse.gef.commands.Command command)
                    throws MutationException {
                // no-op for error consistency tests
            }
        };
        @Override public ModelInfoDto getModelInfo() {
            return new ModelInfoDto("Error Test Model", 0, 0, 0, Map.of(), Map.of(), Map.of());
        }
        @Override public MutationDispatcher getMutationDispatcher() { return dispatcher; }
        @Override public String getModelVersion() { return "test-v1"; }
        @Override public Optional<String> getCurrentModelName() { return Optional.of("Error Test Model"); }
        @Override public Optional<String> getCurrentModelId() { return Optional.of("error-test-id"); }
        @Override public List<DuplicateCandidate> findDuplicates(String type, String name) {
            return List.of(new DuplicateCandidate("dup-1", "Existing Element", type, 0.85));
        }
        @Override public BulkMutationResult executeBulk(String sessionId,
                List<BulkOperation> operations, String description, boolean continueOnError) {
            throw new ModelAccessException(
                    "Operation 0 (create-element): Invalid ArchiMate element type: FakeType",
                    ErrorCode.BULK_VALIDATION_FAILED,
                    "failedOperationIndex=0, failedTool=create-element",
                    "Fix the failed operation and retry the entire bulk-mutate call",
                    null);
        }
    }

    /**
     * Accessor that throws RuntimeException on all query methods.
     * Used to test INTERNAL_ERROR handling consistency.
     */
    static class ExplodingAccessor extends BaseTestAccessor {
        @Override public ModelInfoDto getModelInfo() { throw new RuntimeException("Boom"); }
        @Override public Optional<ElementDto> getElementById(String id) { throw new RuntimeException("Boom"); }
        @Override public List<ElementDto> getElementsByIds(List<String> ids) { throw new RuntimeException("Boom"); }
        @Override public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter) { throw new RuntimeException("Boom"); }
        @Override public List<RelationshipDto> getRelationshipsForElement(String elementId) { throw new RuntimeException("Boom"); }
        @Override public List<ViewDto> getViews(String filter) { throw new RuntimeException("Boom"); }
        @Override public Optional<ViewContentsDto> getViewContents(String viewId) { throw new RuntimeException("Boom"); }
        @Override public List<FolderDto> getRootFolders() { throw new RuntimeException("Boom"); }
        @Override public Optional<FolderDto> getFolderById(String id) { throw new RuntimeException("Boom"); }
        @Override public List<FolderDto> getFolderChildren(String parentId) { throw new RuntimeException("Boom"); }
        @Override public List<FolderTreeDto> getFolderTree(String rootId, int maxDepth) { throw new RuntimeException("Boom"); }
        @Override public List<FolderDto> searchFolders(String nameQuery) { throw new RuntimeException("Boom"); }
        @Override public String getModelVersion() { return "exploding"; }
        @Override public Optional<String> getCurrentModelName() { return Optional.of("Exploding Model"); }
        @Override public Optional<String> getCurrentModelId() { return Optional.of("exploding-id"); }
    }
}
