package net.vheerden.archi.mcp.handlers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for deletion tools: delete-element, delete-relationship,
 * delete-view, and delete-folder (Story 8-4).
 *
 * <p>Each tool cascade-removes dependent model objects as appropriate:
 * <ul>
 *   <li>delete-element: cascades relationships + view references</li>
 *   <li>delete-relationship: cascades view connections</li>
 *   <li>delete-view: removes visual contents only, NOT model objects</li>
 *   <li>delete-folder: requires force for non-empty, rejects default folders</li>
 * </ul>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF, GEF, SWT, or ArchimateTool model types. All mutation logic
 * goes through {@link ArchiModelAccessor}.</p>
 */
public class DeletionHandler {

    private static final Logger logger = LoggerFactory.getLogger(DeletionHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    // nullable — null in test mode without session management
    private final SessionManager sessionManager;

    /**
     * Creates a DeletionHandler with its required dependencies.
     *
     * @param accessor       the model accessor for deleting ArchiMate objects
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager for session ID extraction, may be null
     */
    public DeletionHandler(ArchiModelAccessor accessor,
                           ResponseFormatter formatter,
                           CommandRegistry registry,
                           SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager;
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     * Registers: delete-element, delete-relationship, delete-view, delete-folder.
     */
    public void registerTools() {
        registry.registerTool(buildDeleteElementSpec());
        registry.registerTool(buildDeleteRelationshipSpec());
        registry.registerTool(buildDeleteViewSpec());
        registry.registerTool(buildDeleteFolderSpec());
    }

    // ---- delete-element ----

    private McpServerFeatures.SyncToolSpecification buildDeleteElementSpec() {
        Map<String, Object> elementIdProp = new LinkedHashMap<>();
        elementIdProp.put("type", "string");
        elementIdProp.put("description", "The unique identifier of the element to delete");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("elementId", elementIdProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("elementId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("delete-element")
                .description("[Mutation] Delete an ArchiMate element from the model. "
                        + "Cascades: removes all relationships involving this element and all "
                        + "view references (diagram objects) across all views. Returns deletion "
                        + "confirmation with cascade counts. Warning: highly-connected elements "
                        + "(many relationships or view references) may produce large cascades. "
                        + "Use get-relationships to check dependencies before deleting. "
                        + "Fully undoable via Ctrl+Z in Archi. "
                        + "Respects approval mode (set-approval-mode). "
                        + "Related: get-element (inspect before deleting), get-relationships "
                        + "(check dependencies), search-elements (find elements to delete).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleDeleteElement)
                .build();
    }

    McpSchema.CallToolResult handleDeleteElement(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling delete-element request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String elementId = HandlerUtils.requireStringParam(args, "elementId");

            MutationResult<DeleteResultDto> result = accessor.deleteElement(sessionId, elementId);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildDeleteElementNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling delete-element", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while deleting element");
        }
    }

    private List<String> buildDeleteElementNextSteps(MutationResult<DeleteResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Element and all cascade targets have been removed",
                "Use Ctrl+Z in Archi to undo if needed",
                "Use search-elements to verify the element is gone");
    }

    // ---- delete-relationship ----

    private McpServerFeatures.SyncToolSpecification buildDeleteRelationshipSpec() {
        Map<String, Object> relationshipIdProp = new LinkedHashMap<>();
        relationshipIdProp.put("type", "string");
        relationshipIdProp.put("description", "The unique identifier of the relationship to delete");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("relationshipId", relationshipIdProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("relationshipId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("delete-relationship")
                .description("[Mutation] Delete an ArchiMate relationship from the model. "
                        + "Cascades: removes all view connections representing this relationship "
                        + "across all views. The connected elements are NOT deleted. Returns "
                        + "deletion confirmation with cascade counts. Fully undoable via Ctrl+Z "
                        + "in Archi. Respects approval mode (set-approval-mode). "
                        + "Related: get-relationships (inspect before deleting), get-element "
                        + "(check connected elements).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleDeleteRelationship)
                .build();
    }

    McpSchema.CallToolResult handleDeleteRelationship(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling delete-relationship request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String relationshipId = HandlerUtils.requireStringParam(args, "relationshipId");

            MutationResult<DeleteResultDto> result = accessor.deleteRelationship(
                    sessionId, relationshipId);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildDeleteRelationshipNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling delete-relationship", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while deleting relationship");
        }
    }

    private List<String> buildDeleteRelationshipNextSteps(MutationResult<DeleteResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Relationship and all view connections have been removed",
                "Use Ctrl+Z in Archi to undo if needed",
                "Use get-relationships to verify removal");
    }

    // ---- delete-view ----

    private McpServerFeatures.SyncToolSpecification buildDeleteViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "The unique identifier of the view to delete");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("delete-view")
                .description("[Mutation] Delete an ArchiMate view (diagram) from the model. "
                        + "Removes the view and all its visual contents. The underlying model "
                        + "elements and relationships are NOT deleted. Fully undoable via Ctrl+Z "
                        + "in Archi. Respects approval mode (set-approval-mode). "
                        + "Related: get-views (list views), get-view-contents "
                        + "(inspect before deleting).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleDeleteView)
                .build();
    }

    McpSchema.CallToolResult handleDeleteView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling delete-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");

            MutationResult<DeleteResultDto> result = accessor.deleteView(sessionId, viewId);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildDeleteViewNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling delete-view", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while deleting view");
        }
    }

    private List<String> buildDeleteViewNextSteps(MutationResult<DeleteResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "View has been removed (model elements/relationships are intact)",
                "Use Ctrl+Z in Archi to undo if needed",
                "Use get-views to verify removal");
    }

    // ---- delete-folder ----

    private McpServerFeatures.SyncToolSpecification buildDeleteFolderSpec() {
        Map<String, Object> folderIdProp = new LinkedHashMap<>();
        folderIdProp.put("type", "string");
        folderIdProp.put("description", "The unique identifier of the folder to delete");

        Map<String, Object> forceProp = new LinkedHashMap<>();
        forceProp.put("type", "boolean");
        forceProp.put("description",
                "If true, cascade-delete all contents (elements, relationships, views, subfolders). "
                + "Required for non-empty folders. Default: false");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("folderId", folderIdProp);
        properties.put("force", forceProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("folderId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("delete-folder")
                .description("[Mutation] Delete a folder from the model. "
                        + "Empty folders are deleted immediately. Non-empty folders require "
                        + "force: true to cascade-delete all contents (elements, relationships, "
                        + "views, subfolders). Top-level default ArchiMate folders (e.g., "
                        + "'Business', 'Application', 'Views') cannot be deleted. Fully undoable "
                        + "via Ctrl+Z in Archi. Respects approval mode (set-approval-mode). "
                        + "Related: get-folders (inspect folder contents), get-folder-tree "
                        + "(view hierarchy).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleDeleteFolder)
                .build();
    }

    McpSchema.CallToolResult handleDeleteFolder(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling delete-folder request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String folderId = HandlerUtils.requireStringParam(args, "folderId");
            boolean force = HandlerUtils.optionalBooleanParam(args, "force");

            MutationResult<DeleteResultDto> result = accessor.deleteFolder(
                    sessionId, folderId, force);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildDeleteFolderNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling delete-folder", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while deleting folder");
        }
    }

    private List<String> buildDeleteFolderNextSteps(MutationResult<DeleteResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Folder has been deleted",
                "Use Ctrl+Z in Archi to undo if needed",
                "Use get-folder-tree to verify removal");
    }
}
