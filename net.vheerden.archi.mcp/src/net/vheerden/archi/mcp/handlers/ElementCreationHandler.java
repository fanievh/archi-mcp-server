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
import net.vheerden.archi.mcp.response.dto.DuplicateCandidate;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for element creation tools: create-element, create-relationship,
 * create-view (Story 7-2).
 *
 * <p>Creates new ArchiMate model objects via the accessor's mutation methods.
 * Supports both GUI-attached (immediate) and batch (queued) operational modes.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF, GEF, SWT, or ArchimateTool model types. All mutation logic
 * goes through {@link ArchiModelAccessor}.</p>
 */
public class ElementCreationHandler {

    private static final Logger logger = LoggerFactory.getLogger(ElementCreationHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    // nullable — null in test mode without session management
    private final SessionManager sessionManager;

    /**
     * Creates an ElementCreationHandler with its required dependencies.
     *
     * @param accessor       the model accessor for creating ArchiMate objects
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager for session ID extraction, may be null
     */
    public ElementCreationHandler(ArchiModelAccessor accessor,
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
     * Registers: create-element, create-relationship, create-view, clone-view.
     */
    public void registerTools() {
        registry.registerTool(buildCreateElementSpec());
        registry.registerTool(buildCreateRelationshipSpec());
        registry.registerTool(buildCreateViewSpec());
        registry.registerTool(buildCloneViewSpec());
    }

    // ---- create-element ----

    private McpServerFeatures.SyncToolSpecification buildCreateElementSpec() {
        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("description",
                "ArchiMate element type (e.g., 'BusinessActor', 'ApplicationComponent', "
                + "'Node', 'Stakeholder'). Must be a valid EClass name from IArchimatePackage.");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "Name for the new element");

        Map<String, Object> documentationProp = new LinkedHashMap<>();
        documentationProp.put("type", "string");
        documentationProp.put("description", "Optional documentation text for the element");

        Map<String, Object> propertiesStringValues = new LinkedHashMap<>();
        propertiesStringValues.put("type", "string");
        Map<String, Object> propertiesProp = new LinkedHashMap<>();
        propertiesProp.put("type", "object");
        propertiesProp.put("description",
                "Optional key-value properties map (e.g., {\"status\": \"active\"})");
        propertiesProp.put("additionalProperties", propertiesStringValues);

        Map<String, Object> folderIdProp = new LinkedHashMap<>();
        folderIdProp.put("type", "string");
        folderIdProp.put("description",
                "Optional folder ID to place the element in. Use get-folders to discover "
                + "available folders. The folder must be under the correct root folder for "
                + "the element's ArchiMate layer (e.g., Strategy elements in Strategy "
                + "subfolders, Business elements in Business subfolders). If omitted, "
                + "element is placed in the default folder for its type.");

        Map<String, Object> forceProp = new LinkedHashMap<>();
        forceProp.put("type", "boolean");
        forceProp.put("description",
                "Skip duplicate detection and create immediately. Use when you have already "
                + "reviewed potential duplicates and confirmed creation is needed.");

        Map<String, Object> sourceStringValues = new LinkedHashMap<>();
        sourceStringValues.put("type", "string");
        Map<String, Object> sourceProp = new LinkedHashMap<>();
        sourceProp.put("type", "object");
        sourceProp.put("description",
                "Optional source traceability map. Keys are auto-prefixed with 'mcp.source.' "
                + "in element properties (e.g., {\"tool\": \"import-script\"} becomes property "
                + "'mcp.source.tool' = 'import-script').");
        sourceProp.put("additionalProperties", sourceStringValues);

        Map<String, Object> specializationProp = new LinkedHashMap<>();
        specializationProp.put("type", "string");
        specializationProp.put("description",
                "Optional specialization (profile) name to assign as the element's primary "
                + "specialization (e.g., 'Cloud Server' for a Node, 'Microservice' for an "
                + "ApplicationComponent). Profile lookup is case-insensitive and scoped by "
                + "element type. If a profile with this name and type does not exist in the "
                + "model, it is auto-created. Profile creation and element creation are "
                + "wrapped in a single undoable operation.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", typeProp);
        properties.put("name", nameProp);
        properties.put("documentation", documentationProp);
        properties.put("properties", propertiesProp);
        properties.put("folderId", folderIdProp);
        properties.put("force", forceProp);
        properties.put("source", sourceProp);
        properties.put("specialization", specializationProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("type", "name"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("create-element")
                .description("[Mutation] Create a new ArchiMate element. "
                        + "Checks for potential duplicates of the same type before creating "
                        + "— if similar elements are found, returns them for review instead of "
                        + "creating. Use force: true to skip duplicate detection. "
                        + "Required: type, name. Optional: documentation, properties, folderId, "
                        + "force, source, specialization. "
                        + "Source traceability: provide source map to tag the element with "
                        + "provenance metadata (keys auto-prefixed with 'mcp.source.'). "
                        + "Specialization: provide a profile name to create the element as a "
                        + "domain-specific subtype (e.g., 'Cloud Server' Node). The profile is "
                        + "auto-created on first use and reused (case-insensitive) thereafter. "
                        + "Two elements with the same name but different specializations are "
                        + "NOT considered duplicates. "
                        + "Related: get-or-create-element (idempotent creation), "
                        + "search-elements (find existing), get-folders (discover folder IDs), "
                        + "create-relationship (connect elements), list-specializations.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleCreateElement)
                .build();
    }

    McpSchema.CallToolResult handleCreateElement(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling create-element request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String type = HandlerUtils.requireStringParam(args, "type");
            String name = HandlerUtils.requireStringParam(args, "name");
            String documentation = HandlerUtils.optionalStringParam(args, "documentation");
            Map<String, String> properties = HandlerUtils.optionalMapParam(args, "properties");
            String folderId = HandlerUtils.optionalStringParam(args, "folderId");
            boolean force = HandlerUtils.optionalBooleanParam(args, "force");
            Map<String, String> source = HandlerUtils.optionalMapParam(args, "source");
            String specialization = HandlerUtils.optionalStringParam(args, "specialization");

            // Duplicate detection (skip if force=true). Specialization-aware:
            // same name+type with different specialization is NOT a duplicate.
            if (!force) {
                List<DuplicateCandidate> duplicates = accessor.findDuplicates(type, name, specialization);
                if (!duplicates.isEmpty()) {
                    return buildDuplicateDetectionResponse(type, name, duplicates);
                }
            }

            MutationResult<ElementDto> result = accessor.createElement(
                    sessionId, type, name, documentation, properties, folderId, source, specialization);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildCreateElementNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling create-element", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while creating element");
        }
    }

    private List<String> buildCreateElementNextSteps(MutationResult<ElementDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        String id = result.entity().id();
        return List.of(
                "Use get-element with id '" + id + "' to verify the created element",
                "Use create-relationship to connect this element to others",
                "Use search-elements to find related elements");
    }

    // ---- create-relationship ----

    private McpServerFeatures.SyncToolSpecification buildCreateRelationshipSpec() {
        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("description",
                "ArchiMate relationship type (e.g., 'ServingRelationship', "
                + "'CompositionRelationship', 'AssociationRelationship'). "
                + "ArchiMate specification rules are enforced.");

        Map<String, Object> sourceIdProp = new LinkedHashMap<>();
        sourceIdProp.put("type", "string");
        sourceIdProp.put("description", "ID of the source element");

        Map<String, Object> targetIdProp = new LinkedHashMap<>();
        targetIdProp.put("type", "string");
        targetIdProp.put("description", "ID of the target element");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "Optional name for the relationship");

        Map<String, Object> specializationProp = new LinkedHashMap<>();
        specializationProp.put("type", "string");
        specializationProp.put("description",
                "Optional specialization (profile) name to assign as the relationship's primary "
                + "specialization (e.g., 'Data Flow' for a FlowRelationship). Profile lookup is "
                + "case-insensitive and scoped by relationship type. If a profile with this name "
                + "and type does not exist in the model, it is auto-created. Profile creation "
                + "and relationship creation are wrapped in a single undoable operation.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", typeProp);
        properties.put("sourceId", sourceIdProp);
        properties.put("targetId", targetIdProp);
        properties.put("name", nameProp);
        properties.put("specialization", specializationProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("type", "sourceId", "targetId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("create-relationship")
                .description("[Mutation] Create a relationship between two elements. "
                        + "Requires type (e.g., 'ServingRelationship', 'CompositionRelationship'), "
                        + "sourceId, and targetId. ArchiMate specification rules are enforced "
                        + "— invalid source/target/type combinations return detailed errors with "
                        + "valid alternatives. Optional: name, specialization. "
                        + "Specialization: provide a profile name to create the relationship as a "
                        + "domain-specific subtype (e.g., 'Data Flow' FlowRelationship). The "
                        + "profile is auto-created on first use and reused (case-insensitive) "
                        + "thereafter. "
                        + "Related: get-relationships (verify), get-element (check endpoints), "
                        + "create-element (create endpoints first), list-specializations.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleCreateRelationship)
                .build();
    }

    McpSchema.CallToolResult handleCreateRelationship(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling create-relationship request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String type = HandlerUtils.requireStringParam(args, "type");
            String sourceId = HandlerUtils.requireStringParam(args, "sourceId");
            String targetId = HandlerUtils.requireStringParam(args, "targetId");
            String name = HandlerUtils.optionalStringParam(args, "name");
            String specialization = HandlerUtils.optionalStringParam(args, "specialization");

            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    sessionId, type, sourceId, targetId, name, specialization);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildCreateRelationshipNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling create-relationship", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while creating relationship");
        }
    }

    private List<String> buildCreateRelationshipNextSteps(MutationResult<RelationshipDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        String id = result.entity().id();
        return List.of(
                "Use get-relationships for sourceId to verify the connection",
                "Use get-element with id '" + id + "' to inspect connected elements");
    }

    // ---- create-view ----

    private McpServerFeatures.SyncToolSpecification buildCreateViewSpec() {
        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "Name for the new view");

        Map<String, Object> viewpointProp = new LinkedHashMap<>();
        viewpointProp.put("type", "string");
        viewpointProp.put("description",
                "Optional viewpoint type (e.g., 'layered', 'application_cooperation')");

        Map<String, Object> folderIdProp = new LinkedHashMap<>();
        folderIdProp.put("type", "string");
        folderIdProp.put("description",
                "Optional folder ID to place the view in. Use get-folders to discover "
                + "available folders. If omitted, view is placed in the default Views folder.");

        Map<String, Object> connectionRouterTypeProp = new LinkedHashMap<>();
        connectionRouterTypeProp.put("type", "string");
        connectionRouterTypeProp.put("description",
                "Connection routing style for the view. 'manhattan' for orthogonal "
                + "right-angle paths, 'manual' for direct lines (default). Omit for default.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        properties.put("viewpoint", viewpointProp);
        properties.put("folderId", folderIdProp);
        properties.put("connectionRouterType", connectionRouterTypeProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("name"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("create-view")
                .description("[Mutation] Create a new ArchiMate view (diagram). "
                        + "Requires name. Optional: viewpoint (e.g., 'layered', "
                        + "'application_cooperation'), folderId, connectionRouterType "
                        + "('manhattan' or 'manual', default is 'manual'). "
                        + "ROUTING GUIDANCE: Do NOT set connectionRouterType 'manhattan' "
                        + "unless the view has 5 or fewer connections and no inter-group "
                        + "routing. Manhattan ignores obstacles and draws naive paths that "
                        + "cross through elements. For any view with connections, leave the "
                        + "default ('manual') and use auto-route-connections for clean "
                        + "obstacle-aware orthogonal routing. "
                        + "Related: get-views (list existing), get-view-contents "
                        + "(inspect created view), auto-route-connections (route connections "
                        + "after placement).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleCreateView)
                .build();
    }

    McpSchema.CallToolResult handleCreateView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling create-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String name = HandlerUtils.requireStringParam(args, "name");
            String viewpoint = HandlerUtils.optionalStringParam(args, "viewpoint");
            String folderId = HandlerUtils.optionalStringParam(args, "folderId");
            String connectionRouterType = HandlerUtils.optionalStringParam(args,
                    "connectionRouterType");

            MutationResult<ViewDto> result = accessor.createView(
                    sessionId, name, viewpoint, folderId, connectionRouterType);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildCreateViewNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling create-view", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while creating view");
        }
    }

    private List<String> buildCreateViewNextSteps(MutationResult<ViewDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        ViewDto view = result.entity();
        return List.of(
                "Use get-views to see the created view in the list",
                "Use get-view-contents with viewId '" + view.id() + "' to inspect the view");
    }

    // ---- clone-view (Story C2) ----

    private McpServerFeatures.SyncToolSpecification buildCloneViewSpec() {
        Map<String, Object> sourceViewIdProp = new LinkedHashMap<>();
        sourceViewIdProp.put("type", "string");
        sourceViewIdProp.put("description",
                "ID of the source view to clone. Use get-views to find view IDs.");

        Map<String, Object> newNameProp = new LinkedHashMap<>();
        newNameProp.put("type", "string");
        newNameProp.put("description", "Name for the cloned view");

        Map<String, Object> folderIdProp = new LinkedHashMap<>();
        folderIdProp.put("type", "string");
        folderIdProp.put("description",
                "Optional folder ID for the cloned view. If omitted, the clone is placed "
                + "in the same folder as the source view.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sourceViewId", sourceViewIdProp);
        properties.put("newName", newNameProp);
        properties.put("folderId", folderIdProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("sourceViewId", "newName"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("clone-view")
                .description("[Mutation] Clone an existing view (deep copy of visual layout). "
                        + "Creates a new view with identical element positions, connection routing, "
                        + "groups, notes, and styling. Model elements and relationships are "
                        + "REFERENCED (not duplicated) — the clone shares the same underlying "
                        + "model objects. Useful for creating before/after pairs, filtered subsets, "
                        + "or alternative layout experiments. "
                        + "Required: sourceViewId, newName. Optional: folderId. "
                        + "Supports undo (removes entire clone as single operation). "
                        + "Related: get-views (find source view ID), get-view-contents "
                        + "(inspect cloned view), remove-from-view (selectively remove elements "
                        + "from clone).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleCloneView)
                .build();
    }

    McpSchema.CallToolResult handleCloneView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling clone-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String sourceViewId = HandlerUtils.requireStringParam(args, "sourceViewId");
            String newName = HandlerUtils.requireStringParam(args, "newName");
            String folderId = HandlerUtils.optionalStringParam(args, "folderId");

            MutationResult<ViewDto> result = accessor.cloneView(
                    sessionId, sourceViewId, newName, folderId);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildCloneViewNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling clone-view", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while cloning view");
        }
    }

    private List<String> buildCloneViewNextSteps(MutationResult<ViewDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        ViewDto view = result.entity();
        return List.of(
                "Use get-view-contents with viewId '" + view.id() + "' to inspect the cloned view",
                "Use remove-from-view to selectively remove elements from the clone",
                "Use undo to remove the entire clone if needed");
    }

    // ---- Duplicate detection response ----

    private McpSchema.CallToolResult buildDuplicateDetectionResponse(
            String type, String name, List<DuplicateCandidate> duplicates) {
        // Build duplicates list for the response
        List<Map<String, Object>> duplicatesList = new ArrayList<>();
        for (DuplicateCandidate dup : duplicates) {
            Map<String, Object> dupMap = new LinkedHashMap<>();
            dupMap.put("id", dup.id());
            dupMap.put("name", dup.name());
            dupMap.put("type", dup.type());
            dupMap.put("similarityScore", Math.round(dup.similarityScore() * 100.0) / 100.0);
            duplicatesList.add(dupMap);
        }

        // Build error response via formatter
        ErrorResponse error = new ErrorResponse(
                ErrorCode.POTENTIAL_DUPLICATES,
                "Found " + duplicates.size()
                        + " existing element" + (duplicates.size() > 1 ? "s" : "")
                        + " similar to '" + name + "' of type " + type
                        + ". Review duplicates before creating.",
                null,
                "Use get-element to inspect potential duplicates, "
                        + "or add force: true to create anyway",
                null);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("duplicates", duplicatesList);

        List<String> nextSteps = new ArrayList<>();
        for (DuplicateCandidate dup : duplicates) {
            int pct = (int) Math.round(dup.similarityScore() * 100);
            nextSteps.add("Use get-element with id '" + dup.id()
                    + "' to inspect '" + dup.name() + "' (" + pct + "% similar)");
        }
        nextSteps.add("Use create-element with force: true to create despite potential duplicates");

        String modelVersion = accessor.getModelVersion();
        Map<String, Object> envelope = formatter.formatErrorWithExtras(
                error, extras, nextSteps, modelVersion);

        return HandlerUtils.buildResult(formatter.toJsonString(envelope), true);
    }
}
