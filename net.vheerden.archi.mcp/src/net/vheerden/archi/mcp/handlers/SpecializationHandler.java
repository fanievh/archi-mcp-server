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
import net.vheerden.archi.mcp.model.DispatchArm;
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
 * Handler for specialization (profile) management tools.
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
 * {@code create-element}/{@code create-relationship} accept an
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

        Map<String, Object> imagePathProp = new LinkedHashMap<>();
        imagePathProp.put("type", "string");
        imagePathProp.put("description",
                "Optional archive imagePath returned by add-image-to-model or "
                + "list-model-images. When set, Archi renders this image as the "
                + "specialization's icon on every element/relationship of this "
                + "specialization. The value is opaque and server-generated — pass it "
                + "back exactly as received; never construct or parse one. Must resolve to "
                + "existing bytes in the model archive — typo'd paths are rejected "
                + "with IMAGE_NOT_FOUND. Omit to leave the specialization without "
                + "an icon (Archi falls back to the default ArchiMate icon for "
                + "the conceptType).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        properties.put("conceptType", conceptTypeProp);
        properties.put("imagePath", imagePathProp);

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
                        + "Optionally accepts imagePath to set the specialization's icon "
                        + "(must be an archive path from add-image-to-model or "
                        + "list-model-images). Idempotent re-creation by name+conceptType "
                        + "STILL returns 'created: false' even if the supplied imagePath "
                        + "differs from the existing one — use update-specialization to "
                        + "change the icon on an existing specialization. "
                        + "Related: list-specializations (browse), get-specialization-usage "
                        + "(audit), update-specialization (rename / set icon), "
                        + "delete-specialization (remove), create-element/create-relationship "
                        + "(inline auto-create).")
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
            String imagePath = HandlerUtils.optionalStringParam(args, "imagePath");

            MutationResult<Map<String, Object>> result = accessor.createSpecialization(
                    sessionId, name, conceptType, imagePath);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildCreateNextSteps(result),
                    buildCreateApprovalDisclosures(result), accessor, formatter);

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

    /**
     * The three lines every queued response ends with.
     *
     * <p>{@code get-batch-status} used to be missing from all three of this handler's batched
     * arms — the only handler in the tree with batched arms and no occurrence of it, two lines
     * where every other handler emits three. Nothing makes the call unavailable for a
     * specialization mutation, so the omission read as a difference an agent had to account for
     * and was not one.</p>
     */
    private static List<String> queueTail(MutationResult<?> result) {
        return List.of(
                "Mutation queued as operation #" + result.batchSequenceNumber()
                        + " in current batch",
                "Use get-batch-status to check batch progress",
                "Use end-batch to commit all queued mutations");
    }

    /**
     * What a create discloses once the specialization exists — or will.
     *
     * <p>The name is safe to quote on every arm: the accessor reads it back off the profile the
     * command will store rather than off the request, so it is the name the model ends up holding
     * whichever way the write is dispatched.</p>
     *
     * <p>The already-existed branch has no deferred wording here on purpose. That outcome is
     * produced on exactly one path — an existing profile returned with a no-op command — and the
     * accessor short-circuits on that command above both the approval gate and the queue, so a
     * caller cannot be on a deferred arm and see it. Note the second-order fact, because it reads
     * the other way round at a glance: "the batched arm" and "called while a batch is open" are
     * not the same set for this tool. A duplicate create inside an open batch comes back in the
     * applied-arm envelope, because nothing was queued.</p>
     */
    private List<String> createDisclosures(Map<String, Object> entity, DispatchArm arm) {
        if (entity == null) {
            return List.of();
        }
        String name = String.valueOf(entity.get("name"));
        String instantiate = "use create-element with specialization='" + name
                + "' to instantiate an element of this specialization";
        return switch (arm) {
            case APPLIED -> List.of(
                    "Use create-element with specialization='" + name
                            + "' to instantiate an element of this specialization",
                    "Use list-specializations to browse the model's vocabulary",
                    "Use get-specialization-usage to audit usage later");
            case QUEUED -> List.of(
                    "Once this batch commits, " + instantiate,
                    "Use list-specializations to browse the model's vocabulary — it reads the "
                            + "committed model, so this specialization appears there after "
                            + "end-batch.",
                    "Use get-specialization-usage to audit usage later");
            case AWAITING_APPROVAL -> List.of(
                    "Once this change is approved, " + instantiate,
                    "Use list-specializations to browse the model's vocabulary — it reads the "
                            + "committed model, so this specialization appears there once the "
                            + "change is approved.",
                    "Use get-specialization-usage to audit usage later");
        };
    }

    /** What a create-specialization response carries when the write is waiting on a human. */
    private List<String> buildCreateApprovalDisclosures(
            MutationResult<Map<String, Object>> result) {
        Map<String, Object> entity = result.entity();
        if (entity != null && Boolean.FALSE.equals(entity.get("created"))) {
            return List.of();
        }
        return createDisclosures(entity, DispatchArm.AWAITING_APPROVAL);
    }

    private List<String> buildCreateNextSteps(MutationResult<Map<String, Object>> result) {
        Map<String, Object> entity = result.entity();
        boolean alreadyExisted = entity != null && Boolean.FALSE.equals(entity.get("created"));
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>(
                    alreadyExisted ? List.of() : createDisclosures(entity, DispatchArm.QUEUED));
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        if (alreadyExisted) {
            return List.of(
                    "Specialization already existed — no model change",
                    "Use list-specializations to see all defined specializations",
                    "Use create-element with this specialization to instantiate it");
        }
        return createDisclosures(entity, DispatchArm.APPLIED);
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
        newNameProp.put("description", "Optional new specialization name. "
                + "Refuses to merge: if a specialization named '<newName>' already exists for "
                + "this conceptType, the operation fails. Omit to leave the name unchanged. "
                + "newName is optional — at least one of newName / imagePath / clearImagePath "
                + "must be supplied.");

        Map<String, Object> imagePathProp = new LinkedHashMap<>();
        imagePathProp.put("type", "string");
        imagePathProp.put("description",
                "Optional. Set or change the specialization's icon. Archive imagePath returned "
                + "by add-image-to-model or list-model-images. The value is opaque and "
                + "server-generated — pass it back exactly as received; never construct or parse one. "
                + "Must resolve to existing bytes "
                + "in the model archive — typo'd paths are rejected with IMAGE_NOT_FOUND. "
                + "Omit to leave the icon unchanged. Mutually exclusive with clearImagePath.");

        Map<String, Object> clearImagePathProp = new LinkedHashMap<>();
        clearImagePathProp.put("type", "boolean");
        clearImagePathProp.put("description",
                "Optional. Set to true to explicitly clear the specialization's icon — the "
                + "specialization falls back to the default ArchiMate icon for the conceptType. "
                + "Mutually exclusive with imagePath. Mirrors the clearViewpoint / clearPurpose "
                + "pattern on update-view / update-model.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        properties.put("conceptType", conceptTypeProp);
        properties.put("newName", newNameProp);
        properties.put("imagePath", imagePathProp);
        properties.put("clearImagePath", clearImagePathProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("name", "conceptType"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update-specialization")
                .description("[Mutation] Update an existing specialization (profile) — rename "
                        + "and/or change its icon. Updates the profile *definition*; every "
                        + "element and relationship that references this specialization "
                        + "automatically reflects the changes. "
                        + "Refuses to merge: if a specialization named '<newName>' already "
                        + "exists for this conceptType, the operation fails with "
                        + "INVALID_PARAMETER. To merge two specializations, manually re-assign "
                        + "the affected elements via update-element first, then delete the "
                        + "now-empty profile. "
                        + "Required: name, conceptType. At least one of newName, imagePath, "
                        + "or clearImagePath=true must be supplied. "
                        + "imagePath sets/changes the icon; clearImagePath=true explicitly "
                        + "clears it. Mutually exclusive — providing both is rejected with "
                        + "INVALID_PARAMETER. Omit both to leave the icon unchanged. "
                        + "Returns the effective post-update name and conceptType, plus "
                        + "imagePath only while an icon is set — its absence after "
                        + "clearImagePath confirms removal. "
                        + "Related: get-specialization-usage (preview impact), "
                        + "list-specializations (now surfaces imagePath), "
                        + "delete-specialization, add-image-to-model (import an icon first).")
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
            // newName is optional — at-least-one-of guard enforced at accessor.
            String newName = HandlerUtils.optionalStringParam(args, "newName");
            String imagePath = HandlerUtils.optionalStringParam(args, "imagePath");
            boolean clearImagePath = HandlerUtils.optionalBooleanParam(args, "clearImagePath");

            MutationResult<Map<String, Object>> result = accessor.updateSpecialization(
                    sessionId, name, conceptType, newName, imagePath, clearImagePath);

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
            // No deferred-arm disclosure of its own: this builder emits no conditional step, and
            // both of its applied lines name a call to make once the rename has landed. It shares
            // the queue tail so the handler no longer reports batch progress differently from
            // every other handler.
            return queueTail(result);
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
                        + "On success returns name, conceptType, deleted: true and "
                        + "clearedFromConcepts (concepts detached). usageCount is NOT in that "
                        + "success result — a refusal returns it in the error details. "
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
                    buildDeleteNextSteps(result),
                    buildDeleteApprovalDisclosures(result), accessor, formatter);

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

    /**
     * How many concepts lose the specialization — exact on one arm, a reading on the others.
     *
     * <p>The number is a snapshot taken at prepare time, and this command re-derives at execute in
     * <em>both</em> directions, which is why the deferred wording fixes no bound at either end.
     * The compound is one {@code ClearSpecializationCommand} per prepare-time usage followed by one
     * {@code DeleteProfileCommand}:</p>
     *
     * <ul>
     * <li><b>Upward.</b> The delete command re-reads the usages and clears any concept that
     *     acquired the specialization after the prepare — beyond the counted set.</li>
     * <li><b>Downward.</b> Each individual clear re-checks too, and declines for a concept that has
     *     since acquired a <em>different</em> specialization, because removing it would be
     *     collateral loss this deletion never authorised. Every prepare-time usage can decline that
     *     way, so the number cleared can be anywhere from zero to more than the count.</li>
     * </ul>
     *
     * <p>On the applied arm prepare and execute are the same moment with nothing interleaved, and
     * the prepare-time guard has already refused any usage carrying a second specialization — so
     * there the count is exact and says so. Only a deferred arm has a gap for either movement to
     * happen in, and what it owes is the reading plus both directions, not a bound. A floor was the
     * first wording here and it was wrong: it promised a minimum the commit can miss.</p>
     */
    private static String clearedFromConceptsStep(int count, DispatchArm arm) {
        String concepts = count + " concept" + (count == 1 ? "" : "s");
        // The deferred wordings lead with the noun phrase, so the verb has to agree with it. The
        // applied arm does not — "Cleared specialization from 1 concept" reads correctly either way.
        String carry = count == 1 ? " carries " : " carry ";
        return switch (arm) {
            case APPLIED -> "Cleared specialization from " + concepts;
            case QUEUED -> concepts + " carried this specialization when the deletion was queued. "
                    + "That is a reading, not a promise: at commit each clear re-checks and is "
                    + "declined for any concept that has since acquired a different "
                    + "specialization, so fewer can be cleared — while a concept that acquires "
                    + "this one is cleared as well. end-batch names every operation it skipped, "
                    + "with the reason.";
            case AWAITING_APPROVAL -> concepts + carry + "this specialization now. That is a "
                    + "reading, not a promise: the deletion is re-prepared when it is approved and "
                    + "each clear re-checks as it runs, so fewer can be cleared if a concept has "
                    + "acquired a different specialization by then — while a concept that acquires "
                    + "this one is cleared as well.";
        };
    }

    private static String verifyDeletionStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "Use list-specializations to verify the deletion";
            case QUEUED -> "Use list-specializations after end-batch to verify the deletion — it "
                    + "reads the committed model, which still holds this specialization until then.";
            case AWAITING_APPROVAL -> "Use list-specializations once the human has approved the "
                    + "change to verify the deletion — it reads the committed model, which still "
                    + "holds this specialization until then.";
        };
    }

    /**
     * How to take the deletion back, which is a different call on every arm.
     *
     * <p>The applied wording sat below the batched arm's early return, so letting the conditional
     * step through newly exposes it. On a queued call nothing has been deleted and {@code undo}
     * would revert whichever command is on top of the stack — somebody else's. Awaiting approval
     * there is no tool at all: the human rejects it in Archi.</p>
     */
    private static String deletionReversalStep(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> "Use undo to revert if this was unintentional";
            case QUEUED -> "Nothing has been deleted yet, so undo would revert whichever command "
                    + "is on top of the stack; discard this deletion with end-batch rollback:true "
                    + "instead.";
            case AWAITING_APPROVAL -> "Nothing has been deleted yet — rejecting the change in "
                    + "Archi leaves the specialization in place, and no tool the agent can call "
                    + "recovers it.";
        };
    }

    private List<String> deleteDisclosures(Map<String, Object> entity, DispatchArm arm) {
        List<String> steps = new ArrayList<>();
        if (entity == null) {
            return steps;
        }
        if (entity.get("clearedFromConcepts") instanceof Number n && n.intValue() > 0) {
            steps.add(clearedFromConceptsStep(n.intValue(), arm));
        }
        steps.add(verifyDeletionStep(arm));
        steps.add(deletionReversalStep(arm));
        return steps;
    }

    /** What a delete-specialization response carries when the deletion is waiting on a human. */
    private List<String> buildDeleteApprovalDisclosures(
            MutationResult<Map<String, Object>> result) {
        return deleteDisclosures(result.entity(), DispatchArm.AWAITING_APPROVAL);
    }

    private List<String> buildDeleteNextSteps(MutationResult<Map<String, Object>> result) {
        if (result.isBatched()) {
            List<String> batchSteps = new ArrayList<>(
                    deleteDisclosures(result.entity(), DispatchArm.QUEUED));
            batchSteps.addAll(queueTail(result));
            return batchSteps;
        }
        return deleteDisclosures(result.entity(), DispatchArm.APPLIED);
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
