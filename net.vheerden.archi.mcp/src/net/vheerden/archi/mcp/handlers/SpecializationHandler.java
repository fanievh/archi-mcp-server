package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
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
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for specialization (profile) management tools (Story C3c).
 *
 * <p>Provides four tools:</p>
 * <ul>
 *   <li>{@code create-specialization} — define a new profile (idempotent)</li>
 *   <li>{@code update-specialization} — rename a profile (collision-rejecting)</li>
 *   <li>{@code delete-specialization} — remove a profile (refuse-on-use, with force flag)</li>
 *   <li>{@code get-specialization-usage} — pure query, returns elements + relationships
 *       referencing a profile</li>
 * </ul>
 *
 * <p>The mutation tools route through the standard mutation pipeline (immediate /
 * batch / approval modes). The query tool talks to the accessor directly.</p>
 *
 * <p>Relationship to inline specialization on element/relationship mutations
 * (Story C3b): {@code create-element}/{@code create-relationship} accept an
 * inline {@code specialization} parameter that auto-creates the profile if
 * needed. The dedicated tools in this handler exist for explicit lifecycle
 * management — pre-registering vocabulary, renaming for clarity, deleting
 * unused profiles, and auditing usage before refactoring.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import any
 * EMF, GEF, SWT, or ArchimateTool model types. All mutation logic goes through
 * {@link ArchiModelAccessor}.</p>
 */
public class SpecializationHandler {

    private static final Logger logger = LoggerFactory.getLogger(SpecializationHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    // nullable — null in test mode without session management
    private final SessionManager sessionManager;

    public SpecializationHandler(ArchiModelAccessor accessor,
                                 ResponseFormatter formatter,
                                 CommandRegistry registry,
                                 SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager;
    }

    /**
     * Registers all four specialization tools with the command registry.
     */
    public void registerTools() {
        registry.registerTool(buildCreateSpecializationSpec());
        registry.registerTool(buildUpdateSpecializationSpec());
        registry.registerTool(buildDeleteSpecializationSpec());
        registry.registerTool(buildGetSpecializationUsageSpec());
    }

    // ---- create-specialization ----

    private McpServerFeatures.SyncToolSpecification buildCreateSpecializationSpec() {
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "Specialization name (e.g., 'Cloud Server', 'Microservice'). "
                + "Case-insensitive lookup, but the original casing is preserved on creation.");

        Map<String, Object> conceptTypeProp = new LinkedHashMap<>();
        conceptTypeProp.put("type", "string");
        conceptTypeProp.put("description", "ArchiMate concept EClass name this specialization "
                + "binds to (e.g., 'Node', 'BusinessActor', 'ApplicationComponent', "
                + "'FlowRelationship'). Must be a concrete (non-abstract) ArchiMate concept type.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        properties.put("conceptType", conceptTypeProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("name", "conceptType"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("create-specialization")
                .description("[Mutation] Define a new specialization (profile) in the model's "
                        + "vocabulary. Idempotent: if a specialization with the same name and "
                        + "conceptType already exists (case-insensitive), it is returned with "
                        + "'created: false' and no model change occurs. "
                        + "Use this to pre-register a vocabulary at the start of a modeling "
                        + "session, or to enforce a style guide. For one-shot creation of an "
                        + "element with a specialization, use create-element with the inline "
                        + "'specialization' parameter — that auto-creates the profile and the "
                        + "element in a single undoable operation. "
                        + "Required: name, conceptType. "
                        + "Related: list-specializations (browse), get-specialization-usage "
                        + "(audit), update-specialization (rename), delete-specialization "
                        + "(remove), create-element/create-relationship (inline auto-create).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleCreateSpecialization)
                .build();
    }

    McpSchema.CallToolResult handleCreateSpecialization(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling create-specialization request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String name = HandlerUtils.requireStringParam(args, "name");
            String conceptType = HandlerUtils.requireStringParam(args, "conceptType");

            MutationResult<Map<String, Object>> result = accessor.createSpecialization(
                    sessionId, name, conceptType);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildCreateNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling create-specialization", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while creating specialization");
        }
    }

    private List<String> buildCreateNextSteps(MutationResult<Map<String, Object>> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use end-batch to commit all queued mutations");
        }
        Map<String, Object> entity = result.entity();
        Object createdFlag = entity.get("created");
        if (Boolean.FALSE.equals(createdFlag)) {
            return List.of(
                    "Specialization already existed — no model change",
                    "Use list-specializations to see all defined specializations",
                    "Use create-element with this specialization to instantiate it");
        }
        return List.of(
                "Use create-element with specialization='" + entity.get("name")
                        + "' to instantiate an element of this specialization",
                "Use list-specializations to browse the model's vocabulary",
                "Use get-specialization-usage to audit usage later");
    }

    // ---- update-specialization ----

    private McpServerFeatures.SyncToolSpecification buildUpdateSpecializationSpec() {
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "Current specialization name");

        Map<String, Object> conceptTypeProp = new LinkedHashMap<>();
        conceptTypeProp.put("type", "string");
        conceptTypeProp.put("description", "ArchiMate concept EClass name (e.g., 'Node'). "
                + "Identifies the specialization together with its name.");

        Map<String, Object> newNameProp = new LinkedHashMap<>();
        newNameProp.put("type", "string");
        newNameProp.put("description", "New specialization name (must be non-blank). "
                + "Refuses to merge: if a specialization named '<newName>' already exists for "
                + "this conceptType, the operation fails.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        properties.put("conceptType", conceptTypeProp);
        properties.put("newName", newNameProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("name", "conceptType", "newName"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update-specialization")
                .description("[Mutation] Rename an existing specialization (profile). "
                        + "Updates the profile *definition*; every element and relationship that "
                        + "references this specialization automatically reflects the new name. "
                        + "Refuses to merge: if a specialization named '<newName>' already "
                        + "exists for this conceptType, the operation fails with "
                        + "INVALID_PARAMETER. To merge two specializations, manually re-assign "
                        + "the affected elements via update-element first, then delete the "
                        + "now-empty profile. "
                        + "Required: name, conceptType, newName. "
                        + "Related: get-specialization-usage (preview impact), "
                        + "list-specializations, delete-specialization.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleUpdateSpecialization)
                .build();
    }

    McpSchema.CallToolResult handleUpdateSpecialization(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling update-specialization request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String name = HandlerUtils.requireStringParam(args, "name");
            String conceptType = HandlerUtils.requireStringParam(args, "conceptType");
            String newName = HandlerUtils.requireStringParam(args, "newName");

            MutationResult<Map<String, Object>> result = accessor.updateSpecialization(
                    sessionId, name, conceptType, newName);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildUpdateNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling update-specialization", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while renaming specialization");
        }
    }

    private List<String> buildUpdateNextSteps(MutationResult<Map<String, Object>> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Use list-specializations to verify the rename",
                "Use search-elements with the new specialization name to find affected elements");
    }

    // ---- delete-specialization ----

    private McpServerFeatures.SyncToolSpecification buildDeleteSpecializationSpec() {
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "Specialization name to delete");

        Map<String, Object> conceptTypeProp = new LinkedHashMap<>();
        conceptTypeProp.put("type", "string");
        conceptTypeProp.put("description", "ArchiMate concept EClass name (e.g., 'Node')");

        Map<String, Object> forceProp = new LinkedHashMap<>();
        forceProp.put("type", "boolean");
        forceProp.put("description", "If true, clear the specialization from every concept that "
                + "references it and then delete the profile in one undoable operation. If false "
                + "(default), the operation is refused with usageCount when the profile is in use. "
                + "Force-delete is refused if any usage concept holds multiple specializations "
                + "(safety guard against silent loss of co-existing profiles).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        properties.put("conceptType", conceptTypeProp);
        properties.put("force", forceProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("name", "conceptType"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("delete-specialization")
                .description("[Mutation] Remove a specialization (profile) definition from the "
                        + "model. By default refuses if the specialization is in use, returning "
                        + "the usageCount so you can decide. Pass force=true to clear the "
                        + "specialization from every referencing concept and delete it in one "
                        + "atomic, undoable operation. "
                        + "Force-delete is refused if any referenced concept holds more than one "
                        + "specialization (safety guard) — detach the others manually first. "
                        + "Required: name, conceptType. Optional: force (default false). "
                        + "Recommended workflow: call get-specialization-usage first to inspect "
                        + "impact before force-deleting. "
                        + "Related: get-specialization-usage (preview), update-specialization "
                        + "(rename instead of delete), list-specializations.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleDeleteSpecialization)
                .build();
    }

    McpSchema.CallToolResult handleDeleteSpecialization(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling delete-specialization request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String name = HandlerUtils.requireStringParam(args, "name");
            String conceptType = HandlerUtils.requireStringParam(args, "conceptType");
            boolean force = HandlerUtils.optionalBooleanParam(args, "force");

            MutationResult<Map<String, Object>> result = accessor.deleteSpecialization(
                    sessionId, name, conceptType, force);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildDeleteNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling delete-specialization", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while deleting specialization");
        }
    }

    private List<String> buildDeleteNextSteps(MutationResult<Map<String, Object>> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber()
                            + " in current batch",
                    "Use end-batch to commit all queued mutations");
        }
        Object cleared = result.entity().get("clearedFromConcepts");
        List<String> steps = new ArrayList<>();
        if (cleared instanceof Number n && n.intValue() > 0) {
            steps.add("Cleared specialization from " + n + " concept" + (n.intValue() == 1 ? "" : "s"));
        }
        steps.add("Use list-specializations to verify the deletion");
        steps.add("Use undo to revert if this was unintentional");
        return steps;
    }

    // ---- get-specialization-usage ----

    private McpServerFeatures.SyncToolSpecification buildGetSpecializationUsageSpec() {
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "Specialization name");

        Map<String, Object> conceptTypeProp = new LinkedHashMap<>();
        conceptTypeProp.put("type", "string");
        conceptTypeProp.put("description", "ArchiMate concept EClass name (e.g., 'Node')");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        properties.put("conceptType", conceptTypeProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("name", "conceptType"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-specialization-usage")
                .description("[Query] Audit where a specialization is used in the model. "
                        + "Returns the specialization plus two arrays: 'elements' and "
                        + "'relationships', each containing {id, name, type} for every concept "
                        + "that references the specialization. Use this BEFORE delete-specialization "
                        + "to inspect impact, or before update-specialization (rename) to "
                        + "communicate the scope of the change. "
                        + "Required: name, conceptType. "
                        + "Related: list-specializations (browse all), delete-specialization, "
                        + "update-specialization, search-elements (filter by specialization).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetSpecializationUsage)
                .build();
    }

    McpSchema.CallToolResult handleGetSpecializationUsage(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-specialization-usage request");
        try {
            HandlerUtils.requireModelLoaded(accessor);

            Map<String, Object> args = request.arguments();
            String name = HandlerUtils.requireStringParam(args, "name");
            String conceptType = HandlerUtils.requireStringParam(args, "conceptType");

            Map<String, Object> usage = accessor.getSpecializationUsage(name, conceptType);
            String modelVersion = accessor.getModelVersion();

            int totalCount = 0;
            Object total = usage.get("totalUsageCount");
            if (total instanceof Number n) {
                totalCount = n.intValue();
            }

            List<String> nextSteps = new ArrayList<>();
            if (totalCount == 0) {
                nextSteps.add("Specialization is unused — safe to delete-specialization");
            } else {
                nextSteps.add("Use delete-specialization with force=true to clear all "
                        + totalCount + " usages and remove the profile in one operation");
                nextSteps.add("Use update-specialization to rename instead of delete");
            }

            Map<String, Object> envelope = formatter.formatSuccess(
                    usage, nextSteps, modelVersion, 1, 1, false);
            return buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling get-specialization-usage", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "Error retrieving specialization usage: " + e.getMessage(),
                    null, null, null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    // ---- helpers ----

    private McpSchema.CallToolResult buildResult(String json, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(isError)
                .build();
    }
}
