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
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for folder mutation tools: create-folder, update-folder,
 * move-to-folder (Story 8-5).
 *
 * <p>Maintains read/write separation from the existing {@code FolderHandler}
 * which handles read-only folder queries (get-folders, get-folder-tree).</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF, GEF, SWT, or ArchimateTool model types. All mutation logic
 * goes through {@link ArchiModelAccessor}.</p>
 */
public class FolderMutationHandler {

    private static final Logger logger = LoggerFactory.getLogger(FolderMutationHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;

    public FolderMutationHandler(ArchiModelAccessor accessor,
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
     * Registers: create-folder, update-folder, move-to-folder.
     */
    public void registerTools() {
        registry.registerTool(buildCreateFolderSpec());
        registry.registerTool(buildUpdateFolderSpec());
        registry.registerTool(buildMoveToFolderSpec());
    }

    // ---- create-folder ----

    private McpServerFeatures.SyncToolSpecification buildCreateFolderSpec() {
        Map<String, Object> parentIdProp = new LinkedHashMap<>();
        parentIdProp.put("type", "string");
        parentIdProp.put("description",
                "The ID of the parent folder to create the new folder under");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "Name for the new folder");

        Map<String, Object> docProp = new LinkedHashMap<>();
        docProp.put("type", "string");
        docProp.put("description", "Optional documentation text for the folder");

        Map<String, Object> propsProp = new LinkedHashMap<>();
        propsProp.put("type", "object");
        propsProp.put("description",
                "Optional key-value properties map (e.g., {\"status\": \"active\"})");
        Map<String, Object> propsAdditional = new LinkedHashMap<>();
        propsAdditional.put("type", "string");
        propsProp.put("additionalProperties", propsAdditional);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("parentId", parentIdProp);
        properties.put("name", nameProp);
        properties.put("documentation", docProp);
        properties.put("properties", propsProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("parentId", "name"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("create-folder")
                .description("[Mutation] Create a new folder (subfolder) in the ArchiMate model. "
                        + "Requires parentId (the folder to create under) and name. "
                        + "Top-level ArchiMate layer folders are model-managed and cannot be "
                        + "created manually. Fully undoable via Ctrl+Z in Archi. "
                        + "Respects approval mode (set-approval-mode). "
                        + "Related: get-folders (find parent folder IDs), get-folder-tree "
                        + "(view hierarchy), update-folder (rename/annotate), "
                        + "move-to-folder (reorganize).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleCreateFolder)
                .build();
    }

    McpSchema.CallToolResult handleCreateFolder(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling create-folder request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String parentId = HandlerUtils.requireStringParam(args, "parentId");
            String name = HandlerUtils.requireStringParam(args, "name");
            String documentation = HandlerUtils.optionalStringParam(args, "documentation");
            Map<String, String> properties = HandlerUtils.optionalMapParam(args, "properties");

            MutationResult<FolderDto> result = accessor.createFolder(
                    sessionId, parentId, name, documentation, properties);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildCreateFolderNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling create-folder", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while creating folder");
        }
    }

    private List<String> buildCreateFolderNextSteps(MutationResult<FolderDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Use get-folders with parentId to verify the new folder",
                "Use move-to-folder to organize elements into the new folder",
                "Use Ctrl+Z in Archi to undo if needed");
    }

    // ---- update-folder ----

    private McpServerFeatures.SyncToolSpecification buildUpdateFolderSpec() {
        Map<String, Object> idProp = new LinkedHashMap<>();
        idProp.put("type", "string");
        idProp.put("description", "The unique identifier of the folder to update");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "New name for the folder (omit to leave unchanged)");

        Map<String, Object> docProp = new LinkedHashMap<>();
        docProp.put("type", "string");
        docProp.put("description",
                "New documentation text for the folder (omit to leave unchanged)");

        Map<String, Object> propsProp = new LinkedHashMap<>();
        propsProp.put("type", "object");
        propsProp.put("description",
                "Properties to add, update, or remove. Set value to a string to add/update, "
                + "set value to null to remove the property key. Omit to leave properties unchanged.");
        Map<String, Object> propsAdditional = new LinkedHashMap<>();
        propsAdditional.put("type", "string");
        propsAdditional.put("nullable", true);
        propsProp.put("additionalProperties", propsAdditional);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", idProp);
        properties.put("name", nameProp);
        properties.put("documentation", docProp);
        properties.put("properties", propsProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("id"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update-folder")
                .description("[Mutation] Update an existing folder's metadata. Requires id. "
                        + "Optional: name (rename), documentation (set/clear description), "
                        + "properties (key-value pairs; set value to null to remove a property). "
                        + "Only provided fields are modified; omitted fields remain unchanged. "
                        + "Fully undoable via Ctrl+Z in Archi. "
                        + "Respects approval mode (set-approval-mode). "
                        + "Related: get-folders (inspect folder), create-folder (create new), "
                        + "move-to-folder (reorganize).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleUpdateFolder)
                .build();
    }

    McpSchema.CallToolResult handleUpdateFolder(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling update-folder request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String id = HandlerUtils.requireStringParam(args, "id");
            String name = HandlerUtils.optionalStringParam(args, "name");
            String documentation = HandlerUtils.optionalStringParam(args, "documentation");
            Map<String, String> properties = HandlerUtils.optionalMapParamWithNulls(
                    args, "properties");

            MutationResult<FolderDto> result = accessor.updateFolder(
                    sessionId, id, name, documentation, properties);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildUpdateFolderNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling update-folder", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while updating folder");
        }
    }

    private List<String> buildUpdateFolderNextSteps(MutationResult<FolderDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Use get-folders to verify the updated folder",
                "Use Ctrl+Z in Archi to undo if needed");
    }

    // ---- move-to-folder ----

    private McpServerFeatures.SyncToolSpecification buildMoveToFolderSpec() {
        Map<String, Object> objectIdProp = new LinkedHashMap<>();
        objectIdProp.put("type", "string");
        objectIdProp.put("description",
                "The ID of the object to move (element, relationship, view, or folder)");

        Map<String, Object> targetFolderIdProp = new LinkedHashMap<>();
        targetFolderIdProp.put("type", "string");
        targetFolderIdProp.put("description",
                "The ID of the target folder to move the object into");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("objectId", objectIdProp);
        properties.put("targetFolderId", targetFolderIdProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("objectId", "targetFolderId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("move-to-folder")
                .description("[Mutation] Move a model object (element, relationship, view, or folder) "
                        + "to a different parent folder. The object is removed from its current "
                        + "folder and placed in the target folder. When moving a folder, all its "
                        + "contents move with it. Cannot move top-level default ArchiMate folders "
                        + "or create circular folder references. Fully undoable via Ctrl+Z in Archi. "
                        + "Respects approval mode (set-approval-mode). "
                        + "Related: get-folders (find target folder IDs), get-folder-tree "
                        + "(view hierarchy), create-folder (create destination folders first).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleMoveToFolder)
                .build();
    }

    McpSchema.CallToolResult handleMoveToFolder(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling move-to-folder request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String objectId = HandlerUtils.requireStringParam(args, "objectId");
            String targetFolderId = HandlerUtils.requireStringParam(args, "targetFolderId");

            MutationResult<MoveResultDto> result = accessor.moveToFolder(
                    sessionId, objectId, targetFolderId);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildMoveToFolderNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling move-to-folder", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while moving object to folder");
        }
    }

    private List<String> buildMoveToFolderNextSteps(MutationResult<MoveResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Use get-folders or get-folder-tree to verify the move",
                "Use Ctrl+Z in Archi to undo if needed");
    }
}
