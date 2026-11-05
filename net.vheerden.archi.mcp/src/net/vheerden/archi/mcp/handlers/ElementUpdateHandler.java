package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.CallerText;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.ToolArguments;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto;
import net.vheerden.archi.mcp.response.dto.ChangeRelationshipTypeResultDto;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;
import net.vheerden.archi.mcp.response.dto.RelationshipTypeChangeRequest;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for the update-element, update-relationship, merge-elements, change-element-type and
 * change-relationship-type tools.
 *
 * <p>Updates existing ArchiMate element and relationship fields (name, documentation, properties),
 * collapses duplicate elements into one, and changes an element's or a relationship's type in
 * place. The updates support both GUI-attached (immediate) and batch (queued) operational modes. A
 * merge is queued too, and closes the batch to every later operation but another merge; a type
 * change is refused inside a batch.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF, GEF, SWT, or ArchimateTool model types. All mutation logic
 * goes through {@link ArchiModelAccessor}.</p>
 */
public class ElementUpdateHandler {

    private static final Logger logger = LoggerFactory.getLogger(ElementUpdateHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    // nullable — null in test mode without session management
    private final SessionManager sessionManager;

    /**
     * Creates an ElementUpdateHandler with its required dependencies.
     *
     * @param accessor       the model accessor for updating ArchiMate objects
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager for session ID extraction, may be null
     */
    public ElementUpdateHandler(ArchiModelAccessor accessor,
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
     * Registers: update-element, update-relationship, merge-elements, change-element-type,
     * change-relationship-type.
     */
    public void registerTools() {
        registry.registerTool(buildUpdateElementSpec());
        registry.registerTool(buildUpdateRelationshipSpec());
        registry.registerTool(buildMergeElementsSpec());
        registry.registerTool(buildChangeElementTypeSpec());
        registry.registerTool(buildChangeRelationshipTypeSpec());
    }

    // ---- update-element ----

    private McpServerFeatures.SyncToolSpecification buildUpdateElementSpec() {
        Map<String, Object> idProp = new LinkedHashMap<>();
        idProp.put("type", "string");
        idProp.put("description", "ID of the element to update");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "New display name for the element (omit to leave unchanged)");

        Map<String, Object> documentationProp = new LinkedHashMap<>();
        documentationProp.put("type", "string");
        documentationProp.put("description",
                "New documentation text for the element (omit to leave unchanged)");

        Map<String, Object> propertiesValueSchema = new LinkedHashMap<>();
        propertiesValueSchema.put("type", "string");
        propertiesValueSchema.put("nullable", true);
        Map<String, Object> propertiesProp = new LinkedHashMap<>();
        propertiesProp.put("type", "object");
        propertiesProp.put("description",
                "Properties to add, update, or remove. Set value to a string to add/update, "
                + "set value to null to remove the property key. Omit to leave properties unchanged.");
        propertiesProp.put("additionalProperties", propertiesValueSchema);

        Map<String, Object> specializationProp = new LinkedHashMap<>();
        specializationProp.put("type", "string");
        specializationProp.put("description",
                "New specialization (profile) name for the element. Profile lookup is "
                + "case-insensitive and scoped by element type; auto-creates the profile if "
                + "absent. Setting a value REPLACES any existing specialization. Empty string "
                + "(\"\") clears all specializations from the element. Omit to leave the "
                + "current specialization unchanged.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", idProp);
        properties.put("name", nameProp);
        properties.put("documentation", documentationProp);
        properties.put("properties", propertiesProp);
        properties.put("specialization", specializationProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("id"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update-element")
                .description("[Mutation] Update an existing ArchiMate element. "
                        + "Requires id. Optional: name (new display name), documentation "
                        + "(new description text), properties (object with key-value pairs; "
                        + "set value to null to remove a property), specialization "
                        + "(new profile name; empty string clears). Only provided fields are "
                        + "modified; omitted fields remain unchanged. If a property key appears "
                        + "multiple times on the element, only the first occurrence is updated. "
                        + "Specialization semantics: providing a name REPLACES any existing "
                        + "specialization (auto-creating the profile if needed); empty string "
                        + "removes all specializations; omitting leaves them unchanged. "
                        + "Provenance: this tool takes no source map — create-element and "
                        + "create-relationship accept one at create time only. To add or correct "
                        + "source traceability afterwards, put the keys in properties with the "
                        + "'mcp.source.' prefix spelled out (e.g. 'mcp.source.tool'); that is the "
                        + "same storage a create-time source map produces. "
                        + "Returns the updated id, name, type, layer, documentation, "
                        + "properties; specialization only when set. "
                        + "An element reports documentation and properties only when it has "
                        + "them, so an absent field means the element carries nothing there "
                        + "rather than that it was left out. In a batch or pending "
                        + "approval the values are pre-update. "
                        + "Related: get-element (inspect before/after), search-elements "
                        + "(find element to update), create-element (create new elements), "
                        + "list-specializations.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleUpdateElement)
                .build();
    }

    McpSchema.CallToolResult handleUpdateElement(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling update-element request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String id = HandlerUtils.requireStringParam(args, "id");
            String name = HandlerUtils.optionalStringParam(args, "name");
            String documentation = HandlerUtils.optionalStringParam(args, "documentation");
            Map<String, String> properties = HandlerUtils.optionalMapParamWithNulls(args, "properties");
            // Specialization clear semantics: empty string means "clear all profiles".
            // Use AllowEmpty so we can distinguish absent (null = no change) from "" (clear).
            String specialization = HandlerUtils.optionalStringParamAllowEmpty(args, "specialization");

            MutationResult<ElementDto> result = accessor.updateElement(
                    sessionId, id, name, documentation, properties, specialization);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildUpdateElementNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling update-element", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while updating element");
        }
    }

    private List<String> buildUpdateElementNextSteps(MutationResult<ElementDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        String id = result.entity().id();
        return List.of(
                "Use get-element with id '" + id + "' to verify the updated element",
                "Use get-relationships to check element connections",
                "Use search-elements to find related elements");
    }

    // ---- update-relationship ----

    private McpServerFeatures.SyncToolSpecification buildUpdateRelationshipSpec() {
        Map<String, Object> idProp = new LinkedHashMap<>();
        idProp.put("type", "string");
        idProp.put("description", "ID of the relationship to update");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description",
                "New display name for the relationship (omit to leave unchanged). "
                + "Empty string clears the name (relationships can be unnamed in ArchiMate).");

        Map<String, Object> documentationProp = new LinkedHashMap<>();
        documentationProp.put("type", "string");
        documentationProp.put("description",
                "New documentation text for the relationship (omit to leave unchanged). "
                + "Empty string clears documentation.");

        Map<String, Object> propertiesValueSchema = new LinkedHashMap<>();
        propertiesValueSchema.put("type", "string");
        propertiesValueSchema.put("nullable", true);
        Map<String, Object> propertiesProp = new LinkedHashMap<>();
        propertiesProp.put("type", "object");
        propertiesProp.put("description",
                "Properties to add, update, or remove. Set value to a string to add/update, "
                + "set value to null to remove the property key. Omit to leave properties unchanged.");
        propertiesProp.put("additionalProperties", propertiesValueSchema);

        Map<String, Object> specializationProp = new LinkedHashMap<>();
        specializationProp.put("type", "string");
        specializationProp.put("description",
                "New specialization (profile) name for the relationship. Profile lookup is "
                + "case-insensitive and scoped by relationship type; auto-creates the profile "
                + "if absent. Setting a value REPLACES any existing specialization. Empty string "
                + "(\"\") clears all specializations. Omit to leave the current specialization "
                + "unchanged.");

        // ArchiMate semantic attributes (type-conditional)
        Map<String, Object> accessTypeProp = new LinkedHashMap<>();
        accessTypeProp.put("type", "string");
        accessTypeProp.put("enum", List.of("access", "read", "write", "readwrite"));
        accessTypeProp.put("description",
                "New access type for AccessRelationship only. 'access' = unspecified; "
                + "'read' = data read by source; 'write' = data written by source; "
                + "'readwrite' = bidirectional. Omit to leave unchanged. REJECTED if "
                + "the target relationship is not AccessRelationship.");

        Map<String, Object> associationDirectedProp = new LinkedHashMap<>();
        associationDirectedProp.put("type", "boolean");
        associationDirectedProp.put("description",
                "New directed flag for AssociationRelationship only. true = directional, "
                + "false = undirected. Omit to leave unchanged. REJECTED if the target "
                + "relationship is not AssociationRelationship.");

        Map<String, Object> influenceStrengthProp = new LinkedHashMap<>();
        influenceStrengthProp.put("type", "string");
        influenceStrengthProp.put("description",
                "New free-text strength qualifier for InfluenceRelationship only. Empty "
                + "string clears the value. Max 255 characters. Omit to leave unchanged. "
                + "REJECTED if the target relationship is not InfluenceRelationship.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", idProp);
        properties.put("name", nameProp);
        properties.put("documentation", documentationProp);
        properties.put("properties", propertiesProp);
        properties.put("specialization", specializationProp);
        properties.put("accessType", accessTypeProp);
        properties.put("associationDirected", associationDirectedProp);
        properties.put("influenceStrength", influenceStrengthProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("id"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update-relationship")
                .description("[Mutation] Update an existing ArchiMate relationship's name, "
                        + "documentation, properties, or specialization. Requires id. Only "
                        + "provided fields are modified; omitted fields remain unchanged. Source, "
                        + "target, and type CANNOT be changed — to change these, delete and "
                        + "recreate the relationship. Specialization semantics: providing a name "
                        + "REPLACES any existing specialization (auto-creating the profile if "
                        + "needed); empty string removes all specializations; omitting leaves "
                        + "them unchanged. "
                        + "Optional ArchiMate semantic attributes (type-conditional — each "
                        + "applies only when the relationship is the matching subtype, otherwise "
                        + "rejected): accessType (AccessRelationship — 'access' / 'read' / 'write' "
                        + "/ 'readwrite'; use 'access' to set back to unspecified), "
                        + "associationDirected (AssociationRelationship — boolean), "
                        + "influenceStrength (InfluenceRelationship — free text, max 255 chars; "
                        + "empty string clears). Omit to leave unchanged. "
                        + "Provenance: this tool takes no source map — create-element and "
                        + "create-relationship accept one at create time only. To add or correct "
                        + "source traceability afterwards, put the keys in properties with the "
                        + "'mcp.source.' prefix spelled out (e.g. 'mcp.source.tool'); that is the "
                        + "same storage a create-time source map produces. "
                        + "Returns id, name, type, sourceId, targetId and documentation; the "
                        + "resolved endpoint names sourceName and targetName whenever the "
                        + "relationship still has that endpoint; specialization only "
                        + "when set, and properties only when the relationship has any. The "
                        + "values are re-read from the model after the write, so clearing "
                        + "documentation returns it as an empty string rather than omitting the "
                        + "field. In a "
                        + "batch or pending approval the values are pre-update. "
                        + "Related: get-relationships (verify changes), search-relationships "
                        + "(find relationship to update), get-view-contents (check visual "
                        + "representation), list-specializations.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleUpdateRelationship)
                .build();
    }

    McpSchema.CallToolResult handleUpdateRelationship(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling update-relationship request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String id = HandlerUtils.requireStringParam(args, "id");
            String name = HandlerUtils.optionalStringParamAllowEmpty(args, "name");
            String documentation = HandlerUtils.optionalStringParamAllowEmpty(args, "documentation");
            Map<String, String> properties = HandlerUtils.optionalMapParamWithNulls(args, "properties");
            // Specialization clear semantics: empty string means "clear all profiles".
            String specialization = HandlerUtils.optionalStringParamAllowEmpty(args, "specialization");
            // Relationship name/documentation are read above with AllowEmpty because this tool's
            // schema promises an empty string clears them. update-element promises no such thing
            // and keeps the blank-stripping reader.
            // semantic attributes (type-conditional)
            RelationshipSemanticAttributes semanticAttributes =
                    ElementCreationHandler.readSemanticAttributes(args);

            MutationResult<RelationshipDto> result = accessor.updateRelationship(
                    sessionId, id, name, documentation, properties, specialization,
                    semanticAttributes);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildUpdateRelationshipNextSteps(result), accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling update-relationship", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while updating relationship");
        }
    }

    private List<String> buildUpdateRelationshipNextSteps(MutationResult<RelationshipDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");
        }
        return List.of(
                "Use get-relationships to verify the updated relationship",
                "Use get-view-contents to check visual representation of the relationship");
    }

    // ---- merge-elements ----

    private McpServerFeatures.SyncToolSpecification buildMergeElementsSpec() {
        Map<String, Object> targetIdProp = new LinkedHashMap<>();
        targetIdProp.put("type", "string");
        targetIdProp.put("description", "ID of the element that survives. It keeps its id, name, "
                + "folder, specialization and features.");

        Map<String, Object> sourceIdItem = new LinkedHashMap<>();
        sourceIdItem.put("type", "string");
        Map<String, Object> sourceIdsProp = new LinkedHashMap<>();
        sourceIdsProp.put("type", "array");
        sourceIdsProp.put("items", sourceIdItem);
        sourceIdsProp.put("minItems", 1);
        sourceIdsProp.put("description", "IDs of the duplicates to merge into targetId. Each must have "
                + "the target's type. They are deleted once everything that referred to them points at "
                + "the target.");

        Map<String, Object> clusterProps = new LinkedHashMap<>();
        clusterProps.put("targetId", targetIdProp);
        clusterProps.put("sourceIds", sourceIdsProp);
        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("type", "object");
        cluster.put("properties", clusterProps);
        cluster.put("required", List.of("targetId", "sourceIds"));
        cluster.put("additionalProperties", false);

        Map<String, Object> mergesProp = new LinkedHashMap<>();
        mergesProp.put("type", "array");
        mergesProp.put("items", cluster);
        mergesProp.put("minItems", 1);
        mergesProp.put("description", "One entry per cluster of duplicates. Many clusters in one call "
                + "make one approval card and one undo step. An element may appear once in the whole "
                + "call, as a target or as a source.");

        Map<String, Object> propertyPolicyProp = new LinkedHashMap<>();
        propertyPolicyProp.put("type", "string");
        propertyPolicyProp.put("enum", List.of("union", "keep-target", "append"));
        propertyPolicyProp.put("description", "How the duplicates' properties and documentation reach "
                + "the target. 'union' (default): a key the target lacks is copied, an exact key/value "
                + "pair it already has is skipped, a key it holds with another value keeps the target's "
                + "value and is reported in propertyConflicts ('mergedFrom' lineage is copied whatever "
                + "the target holds). 'keep-target': the duplicates' properties and documentation are "
                + "discarded (counted in propertiesDiscarded) and the target's are left as they are, "
                + "apart from what recordMergedIds adds. 'append': every property is copied, repeated "
                + "keys included. "
                + "Under union and append a duplicate's documentation is appended to the target's, "
                + "headed 'from <name> [<id>]:', unless it is blank, the same as the target's, or "
                + "already appended.");

        Map<String, Object> selfProp = new LinkedHashMap<>();
        selfProp.put("type", "string");
        selfProp.put("enum", List.of("drop", "keep"));
        selfProp.put("description", "What happens to a relationship between two members of one "
                + "cluster, which would become a relationship of the target with itself. A "
                + "relationship a duplicate already had with itself is not one of these: it moves to "
                + "the target like any other. 'drop' "
                + "(default): it is deleted with its view connections and whatever is attached to it, "
                + "as deleting it in Archi would. 'keep': it is kept when Archi allows that relationship "
                + "type from the target's type to itself, otherwise dropped with reason INVALID_SELF.");

        Map<String, Object> recordProp = new LinkedHashMap<>();
        recordProp.put("type", "boolean");
        recordProp.put("description", "When true, the target gets one property per merged duplicate: "
                + "key 'mergedFrom', value the duplicate's id. Default false.");

        Map<String, Object> collapseProp = new LinkedHashMap<>();
        collapseProp.put("type", "boolean");
        collapseProp.put("description", "When true, relationships the merge makes parallel (one type, "
                + "same source, same target) are folded into one survivor per group: the one already on "
                + "the target, else the first by merges and sourceIds order, then model order. A "
                + "re-pointed relationship folds only when its name, documentation, properties and attributes "
                + "would not be lost: each name "
                + "and each documentation is blank on one side or exactly equal, the property lists are "
                + "empty on one side or the same key/value pairs (order ignored, repeats counted), and "
                + "the type's attributes (access type, influence strength, association direction) and "
                + "specializations are equal (features are not compared; the survivor keeps its own). "
                + "The survivor's blank name or documentation, or empty "
                + "property list, takes the folded one's. Each view connection of a folded relationship "
                + "is re-pointed to the survivor (keeping its id, bendpoints and style), or removed where "
                + "the survivor is already drawn between the same two objects; relationships and "
                + "connections that hung on it move to the survivor. Reported per cluster in "
                + "parallelsCollapsed and parallelsNotCollapsed (with reasons NAME_DIFFERS, "
                + "DOCUMENTATION_DIFFERS, PROPERTIES_DIFFER, ATTRIBUTE_DIFFERS, JOINED_TO_SURVIVOR when a "
                + "relationship joins it to the survivor, or CREATED_BY_COLLAPSE "
                + "for a pair only a fold's move made parallel, which is not folded again), and in "
                + "relationshipIdMap (each folded id to its survivor). Default false.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("merges", mergesProp);
        properties.put("propertyPolicy", propertyPolicyProp);
        properties.put("selfRelationships", selfProp);
        properties.put("recordMergedIds", recordProp);
        properties.put("collapseParallel", collapseProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("merges"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("merge-elements")
                .description("[Mutation] Collapse duplicate ArchiMate elements into one. For each "
                        + "cluster, everything that referred to a duplicate is re-pointed to the target "
                        + "in place, never recreated: relationships keep their ids, names, documentation, "
                        + "properties and attributes; diagram objects keep their ids, bounds, nesting, "
                        + "styling and connections (with bendpoints and label positions) and now show the "
                        + "target. The emptied duplicates are then deleted. Only the listed ids merge, "
                        + "and only elements of one type: a duplicate of another type is refused (change "
                        + "its type first with change-element-type), as are an and-junction with an "
                        + "or-junction, an id listed twice "
                        + "anywhere in the call, a target listed as a source, and relationship, view or "
                        + "unknown ids. Every cluster is checked before anything is written, in this "
                        + "order: policies, cluster shape, repeated ids, ids that resolve, types; the "
                        + "first problem is reported and the model is left unchanged. "
                        + "Reported: parallelRelationships (two relationships of one type that share both "
                        + "ends after the call; both are kept, and only collapseParallel folds any) and "
                        + "doublePlacements (a view that now shows the target in more than one box; every "
                        + "box is kept). Also reported per cluster: relationshipsRepointed (relationships still in the model with an end "
                        + "moved, kept self-relationships included), diagramObjectsRepointed, "
                        + "selfRelationshipsDropped (with their connections), cascadedRelationshipIds and "
                        + "cascadedConnectionIds (removed with a dropped relationship), propertiesCopied, "
                        + "specializationDiffers (a duplicate's specialization is not copied) and "
                        + "namesDropped (a duplicate's name that is now gone). idMap maps each duplicate "
                        + "id to its target, for references held outside the model. The numbers are "
                        + "what the write did; the merge is decided again when it runs, so an approved "
                        + "proposal acts on the model as it is then, but deletes nothing the card did not "
                        + "list: if it would, it writes nothing and says why. Inside a batch the call is "
                        + "queued as one operation and decided at commit, after the operations queued "
                        + "before it, so it sees what they did; its preview counts the model before the "
                        + "batch commits. Queue merges last: once a merge is queued, every later operation "
                        + "but another merge is refused until end-batch, and a later merge may not name an "
                        + "earlier merge's duplicates; it may name an earlier merge's target as a duplicate, "
                        + "which merges that target too. A target or duplicate created in the same batch is "
                        + "refused. The whole call is one undo step: one Ctrl+Z in Archi or one undo call "
                        + "restores the model exactly. Respects approval mode (one card per call). "
                        + "Related: search-elements (find duplicates), get-relationships (inspect what "
                        + "moves), delete-element (remove without merging).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleMergeElements)
                .build();
    }

    McpSchema.CallToolResult handleMergeElements(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling merge-elements request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            List<MergeClusterRequest> merges = readMerges(args);
            String propertyPolicy = readPolicy(args, "propertyPolicy");
            String selfRelationships = readPolicy(args, "selfRelationships");
            boolean recordMergedIds = HandlerUtils.optionalBooleanParam(args, "recordMergedIds");
            boolean collapseParallel = HandlerUtils.optionalBooleanParam(args, "collapseParallel");

            MutationResult<MergeElementsResultDto> result = accessor.mergeElements(sessionId, merges,
                    propertyPolicy, selfRelationships, recordMergedIds, collapseParallel);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildMergeElementsNextSteps(result),
                    List.of("The preview counts what the model holds now; the merge is read again when "
                            + "it is approved, so a relationship added to a duplicate meanwhile moves too"),
                    accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling merge-elements", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while merging elements");
        }
    }

    /**
     * The clusters as sent. A cluster missing a field is passed on as null for the accessor to
     * refuse; a value of the wrong kind, or a key a cluster does not take, is refused here, since
     * reading it as absent would merge something other than what was asked.
     */
    static List<MergeClusterRequest> readMerges(Map<String, Object> args) {
        Object value = args == null ? null : args.get("merges");
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw invalidMerges("merges must be an array of {targetId, sourceIds} objects");
        }
        List<MergeClusterRequest> merges = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> entry)) {
                throw invalidMerges("merges[" + i + "] must be an object with targetId and sourceIds");
            }
            for (Object key : entry.keySet()) {
                if (!"targetId".equals(key) && !"sourceIds".equals(key)) {
                    throw invalidMerges("merges[" + i + "] has an unknown key "
                            + CallerText.quoted(String.valueOf(key)) + "; a cluster takes targetId and sourceIds");
                }
            }
            Object targetId = entry.get("targetId");
            if (targetId != null && !(targetId instanceof String)) {
                throw invalidMerges("merges[" + i + "].targetId must be a string");
            }
            Object sources = entry.get("sourceIds");
            List<String> sourceIds = null;
            if (sources != null) {
                if (!(sources instanceof List<?> sourceList)) {
                    throw invalidMerges("merges[" + i + "].sourceIds must be an array of strings");
                }
                sourceIds = new ArrayList<>(sourceList.size());
                for (int j = 0; j < sourceList.size(); j++) {
                    if (!(sourceList.get(j) instanceof String id)) {
                        throw invalidMerges("merges[" + i + "].sourceIds[" + j + "] must be a string");
                    }
                    sourceIds.add(id);
                }
            }
            merges.add(new MergeClusterRequest((String) targetId, sourceIds));
        }
        return merges;
    }

    private static String readPolicy(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value != null && !(value instanceof String)) {
            throw ToolArguments.notAString(key, value, "Pass one of the listed values, or omit " + key
                    + " for the default.");
        }
        return (String) value;
    }

    private static ModelAccessException invalidMerges(String message) {
        return new ModelAccessException(message + ". Nothing was changed.", ErrorCode.INVALID_PARAMETER, null,
                "Pass merges: [{\"targetId\": \"<id>\", \"sourceIds\": [\"<id>\", ...]}, ...].", null);
    }

    private List<String> buildMergeElementsNextSteps(MutationResult<MergeElementsResultDto> result) {
        if (result.isBatched()) {
            return List.of(
                    "Merge queued as operation #" + result.batchSequenceNumber() + " in current batch; the "
                            + "preview counts the model before the batch commits, and the merge is decided "
                            + "at commit, after the operations queued before it",
                    "Queue only further merge-elements calls now: any other operation is refused until "
                            + "end-batch",
                    "Use end-batch to commit; its skippedOperations names a merge that wrote nothing. Then "
                            + "re-read the target with get-element",
                    "Use preview.idMap to update any reference to a merged id held outside the model; it "
                            + "holds unless the merge is skipped at commit, and a later merge in this batch "
                            + "that names this target as a duplicate re-maps it, so apply each queued merge's "
                            + "idMap in queue order. Which parallel relationships fold is decided at commit, "
                            + "so re-read them after it");
        }
        MergeElementsResultDto merged = result.entity();
        List<String> steps = new ArrayList<>();
        steps.add("Use get-element with id '" + merged.clusters().get(0).targetId()
                + "' to inspect a merged target");
        steps.add("Use idMap to update any reference to a merged id held outside the model");
        if (merged.totals().parallelRelationshipsCollapsed() != null
                && merged.totals().parallelRelationshipsCollapsed() > 0) {
            steps.add("Use relationshipIdMap to update any reference to a folded relationship id held "
                    + "outside the model");
        }
        if (merged.clusters().stream().anyMatch(c -> c.parallelsNotCollapsed() != null
                && !c.parallelsNotCollapsed().isEmpty())) {
            steps.add("Review parallelsNotCollapsed: each was kept for the reasons listed, and no later merge "
                    + "folds it; delete-relationship removes one you do not want");
        }
        if (merged.totals().parallelRelationshipPairs() > 0) {
            steps.add("Review parallelRelationships: both of each pair were kept; delete-relationship "
                    + "removes one you do not want");
        }
        if (merged.totals().doublePlacements() > 0) {
            steps.add("Review doublePlacements: each view kept every box; remove-from-view takes off a box "
                    + "you do not want");
        }
        if (merged.totals().propertyConflicts() > 0) {
            steps.add("Review propertyConflicts: the target kept its own value for each key");
        }
        steps.add("Use undo (or Ctrl+Z in Archi) to reverse the whole merge in one step");
        return steps;
    }

    // ---- change-element-type ----

    private McpServerFeatures.SyncToolSpecification buildChangeElementTypeSpec() {
        Map<String, Object> elementIdProp = new LinkedHashMap<>();
        elementIdProp.put("type", "string");
        elementIdProp.put("description", "ID of the element whose type changes. It keeps this id.");

        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("description", "The ArchiMate element type it should have (e.g., 'BusinessRole', "
                + "'ApplicationComponent', 'Node'), as create-element takes. Must be a concrete element "
                + "type other than the element's own, and not Junction.");

        Map<String, Object> changeProps = new LinkedHashMap<>();
        changeProps.put("elementId", elementIdProp);
        changeProps.put("type", typeProp);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("type", "object");
        change.put("properties", changeProps);
        change.put("required", List.of("elementId", "type"));
        change.put("additionalProperties", false);

        Map<String, Object> changesProp = new LinkedHashMap<>();
        changesProp.put("type", "array");
        changesProp.put("items", change);
        changesProp.put("minItems", 1);
        changesProp.put("description", "One entry per element; each may take its own type. Many elements "
                + "in one call make one approval card and one undo step. An element may appear once.");

        Map<String, Object> invalidRelationshipsProp = new LinkedHashMap<>();
        invalidRelationshipsProp.put("type", "string");
        invalidRelationshipsProp.put("enum", List.of("refuse", "to-association"));
        invalidRelationshipsProp.put("description", "What happens to a relationship the new types would make "
                + "invalid in ArchiMate. 'refuse' (default): the call is refused, every such relationship is "
                + "named, and nothing changes. 'to-association': each becomes an Association, as Archi's own "
                + "Change Type does once it has asked. It keeps its id, name, documentation, properties, its "
                + "place in its folder, every view connection (with its id, bendpoints, styling and label "
                + "position) and every relationship attached to it. Dropped and reported: the old type's "
                + "attributes (attributesDropped: accessType, influenceStrength) and its specialization "
                + "(specializationDropped; the specialization stays in the model). When Archi's 'add "
                + "documentation note' preference is on, Archi's documentation note '(Changed from <type>)' "
                + "is put above its documentation, as Archi does (documentationNoteAdded).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("changes", changesProp);
        properties.put("invalidRelationships", invalidRelationshipsProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("changes"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("change-element-type")
                .description("[Mutation] Change the ArchiMate type of existing elements in place, as "
                        + "Archi's own Change Type does. Each element keeps its id, name, documentation, "
                        + "properties and features; every relationship keeps its id, name, documentation, "
                        + "properties and attributes with the element still at its end; every diagram "
                        + "object keeps its id, bounds, nesting, styling and connections (with bendpoints "
                        + "and label positions) and now shows the new type. The element stays in its "
                        + "folder at its place when that folder belongs to the new type's layer, and moves "
                        + "to the end of the new type's default folder otherwise (folderChanged). Dropped: "
                        + "the element's specialization, which belongs to the old type "
                        + "(specializationDropped; the specialization stays in the model). Reset: each box's "
                        + "figure, to the new type's default figure as Archi does (figureTypesReset). "
                        + "Refused, with nothing changed: a relationship the new types would make invalid "
                        + "in ArchiMate (every one is named; delete it first or pick another type; the "
                        + "check uses the new type of every listed element, so two related elements can "
                        + "change together), unless invalidRelationships is 'to-association', which turns "
                        + "each such relationship into an Association as Archi does (relationshipsConverted; "
                        + "like Archi, it does not check that a junction it joins still holds relationships "
                        + "of one type), a Junction or the type Junction, the element's own type, an "
                        + "unknown or abstract type, an element listed twice, and relationship, view or "
                        + "unknown ids. Every entry is checked before anything is written, in this order: "
                        + "entry shape, repeated ids, ids that resolve, types, relationships; the first "
                        + "problem is reported. Reported per element: resultingId, fromType, toType, "
                        + "relationshipsRepointed, diagramObjectsRepointed, folderId; idMap maps each id "
                        + "passed to its id now (the same id), for a later call such as merge-elements. "
                        + "The numbers are what the write did; the change is decided again when it runs, "
                        + "so an approved proposal acts on the model as it is then, and writes nothing if a "
                        + "relationship added meanwhile would be invalid. Refused inside a batch (end-batch "
                        + "first). The whole call is one undo step: one Ctrl+Z in Archi or one undo call "
                        + "restores the model exactly. Respects approval mode (one card per call). "
                        + "Related: merge-elements (merge duplicates once they share a type), "
                        + "update-element (name, documentation, properties, specialization), "
                        + "get-relationships (inspect what must stay valid).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleChangeElementType)
                .build();
    }

    McpSchema.CallToolResult handleChangeElementType(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling change-element-type request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            List<ElementTypeChangeRequest> changes = readChanges(request.arguments());
            String invalidRelationships = readInvalidRelationships(request.arguments());

            MutationResult<ChangeElementTypeResultDto> result = accessor.changeElementType(sessionId, changes,
                    invalidRelationships);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildChangeElementTypeNextSteps(result),
                    List.of("The preview counts what the model holds now; the change is read again when it "
                            + "is approved, so a relationship added to an element meanwhile moves too"),
                    accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling change-element-type", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while changing element types");
        }
    }

    /**
     * The entries as sent. An entry missing a field is passed on as null for the accessor to
     * refuse; a value of the wrong kind, or a key an entry does not take, is refused here, since
     * reading it as absent would change something other than what was asked.
     */
    static List<ElementTypeChangeRequest> readChanges(Map<String, Object> args) {
        Object value = args == null ? null : args.get("changes");
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw invalidChanges("changes must be an array of {elementId, type} objects");
        }
        List<ElementTypeChangeRequest> changes = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> entry)) {
                throw invalidChanges("changes[" + i + "] must be an object with elementId and type");
            }
            for (Object key : entry.keySet()) {
                if (!"elementId".equals(key) && !"type".equals(key)) {
                    throw invalidChanges("changes[" + i + "] has an unknown key "
                            + CallerText.quoted(String.valueOf(key)) + "; an entry takes elementId and type");
                }
            }
            Object elementId = entry.get("elementId");
            if (elementId != null && !(elementId instanceof String)) {
                throw invalidChanges("changes[" + i + "].elementId must be a string");
            }
            Object type = entry.get("type");
            if (type != null && !(type instanceof String)) {
                throw invalidChanges("changes[" + i + "].type must be a string");
            }
            changes.add(new ElementTypeChangeRequest((String) elementId, (String) type));
        }
        return changes;
    }

    /** The choice as sent; a value of another kind is refused here, a string is the accessor's to check. */
    static String readInvalidRelationships(Map<String, Object> args) {
        Object value = args == null ? null : args.get("invalidRelationships");
        if (value != null && !(value instanceof String)) {
            throw new ModelAccessException("invalidRelationships must be a string: refuse or to-association, got "
                    + ToolArguments.describe(value) + ". Nothing was changed.", ErrorCode.INVALID_PARAMETER, null,
                    "Pass invalidRelationships: \"to-association\" to convert, or omit it to refuse.", null);
        }
        return (String) value;
    }

    private static ModelAccessException invalidChanges(String message) {
        return new ModelAccessException(message + ". Nothing was changed.", ErrorCode.INVALID_PARAMETER, null,
                "Pass changes: [{\"elementId\": \"<id>\", \"type\": \"<ElementType>\"}, ...].", null);
    }

    private List<String> buildChangeElementTypeNextSteps(MutationResult<ChangeElementTypeResultDto> result) {
        ChangeElementTypeResultDto changed = result.entity();
        List<String> steps = new ArrayList<>();
        steps.add("Use get-element with id '" + changed.changes().get(0).resultingId()
                + "' to inspect a changed element");
        steps.add("If this was to merge a duplicate of another type, call merge-elements with the ids in idMap");
        if (changed.totals().specializationsDropped() > 0) {
            steps.add("Review specializationDropped: update-element with specialization gives an element a "
                    + "specialization of its new type");
        }
        if (changed.totals().relationshipsConverted() > 0) {
            steps.add("Review relationshipsConverted: get-relationships shows the new Associations");
        }
        steps.add("Use undo (or Ctrl+Z in Archi) to reverse the whole change in one step");
        return steps;
    }

    // ---- change-relationship-type ----

    /** The relationship types that carry an attribute of their own, which update-relationship sets. */
    private static final Set<String> TYPES_WITH_AN_ATTRIBUTE = Set.of(
            "AccessRelationship", "AssociationRelationship", "InfluenceRelationship");

    private McpServerFeatures.SyncToolSpecification buildChangeRelationshipTypeSpec() {
        Map<String, Object> relationshipIdProp = new LinkedHashMap<>();
        relationshipIdProp.put("type", "string");
        relationshipIdProp.put("description", "ID of the relationship whose type changes. It keeps this id.");

        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("description", "The ArchiMate relationship type it should have (e.g., "
                + "'TriggeringRelationship', 'ServingRelationship', 'RealizationRelationship'), as "
                + "create-relationship takes. Must be a concrete relationship type other than the "
                + "relationship's own.");

        Map<String, Object> changeProps = new LinkedHashMap<>();
        changeProps.put("relationshipId", relationshipIdProp);
        changeProps.put("type", typeProp);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("type", "object");
        change.put("properties", changeProps);
        change.put("required", List.of("relationshipId", "type"));
        change.put("additionalProperties", false);

        Map<String, Object> changesProp = new LinkedHashMap<>();
        changesProp.put("type", "array");
        changesProp.put("items", change);
        changesProp.put("minItems", 1);
        changesProp.put("description", "One entry per relationship; each may take its own type. Many "
                + "relationships in one call make one approval card and one undo step. A relationship may "
                + "appear once.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("changes", changesProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("changes"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("change-relationship-type")
                .description("[Mutation] Change the ArchiMate type of existing relationships in place, as "
                        + "Archi's own Change Type does for a relationship. Each relationship keeps its id, "
                        + "name, documentation (no note is added), properties and features, its source and "
                        + "target, and its place in its folder; every view connection that draws it keeps its "
                        + "id, ends, bendpoints, styling, label position and its place among its ends' "
                        + "connections, and now draws the new type; every relationship attached to it stays "
                        + "attached. Dropped and reported: the old type's own attribute (attributesDropped: "
                        + "accessType, associationDirected when true, influenceStrength when set) and the "
                        + "specialization (specializationDropped; the specialization stays in the model). "
                        + "Reset: the new type's own attribute takes its default, reported as the model holds "
                        + "it (attributes: an Access is 'write', an Association undirected). Refused, with "
                        + "nothing changed: a type ArchiMate does not allow between the ends or with a "
                        + "relationship attached to it (every one is named; the check uses the new type of "
                        + "every listed relationship, so two joined relationships can change together), a "
                        + "relationship at a Junction unless the call lists every one there with one type "
                        + "(every relationship at a Junction must share one type, judged by the type each will "
                        + "have, so a whole Junction can change in one call; Archi's own Change Type judges "
                        + "each relationship alone and never changes one at a Junction), the relationship's own "
                        + "type, an unknown, element or abstract type, a relationship listed twice, and element, "
                        + "view, view connection or unknown ids. Every "
                        + "entry is checked before anything is written, in this order: entry shape, repeated "
                        + "ids, ids that resolve, types, allowed types; the first problem is reported. "
                        + "Reported per relationship: resultingId, fromType, toType, sourceId, targetId, "
                        + "connectionsRepointed, relationshipsRepointed, folderId, and attributesDropped, "
                        + "attributes and specializationDropped when they hold something; idMap maps each id passed to "
                        + "its id now (the same id). The numbers are what the write did; the change is decided "
                        + "again when it runs, so an approved proposal acts on the model as it is then. "
                        + "Refused inside a batch (end-batch first). The whole call is one undo step: one "
                        + "Ctrl+Z in Archi or one undo call restores the model exactly. Respects approval mode "
                        + "(one card per call). "
                        + "Related: update-relationship (set the new type's attributes), get-relationships, "
                        + "change-element-type")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleChangeRelationshipType)
                .build();
    }

    McpSchema.CallToolResult handleChangeRelationshipType(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling change-relationship-type request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            List<RelationshipTypeChangeRequest> changes = readRelationshipChanges(request.arguments());

            MutationResult<ChangeRelationshipTypeResultDto> result = accessor.changeRelationshipType(sessionId,
                    changes);

            return HandlerUtils.formatMutationResponse(result.entity(), result,
                    buildChangeRelationshipTypeNextSteps(result),
                    List.of("The preview counts what the model holds now; the change is read again when it "
                            + "is approved, so a connection drawn meanwhile moves too"),
                    accessor, formatter);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling change-relationship-type", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while changing relationship types");
        }
    }

    /**
     * The entries as sent. An entry missing a field is passed on as null for the accessor to
     * refuse; a value of the wrong kind, or a key an entry does not take, is refused here, since
     * reading it as absent would change something other than what was asked.
     */
    static List<RelationshipTypeChangeRequest> readRelationshipChanges(Map<String, Object> args) {
        Object value = args == null ? null : args.get("changes");
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw invalidRelationshipChanges("changes must be an array of {relationshipId, type} objects");
        }
        List<RelationshipTypeChangeRequest> changes = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> entry)) {
                throw invalidRelationshipChanges("changes[" + i + "] must be an object with relationshipId and type");
            }
            for (Object key : entry.keySet()) {
                if (!"relationshipId".equals(key) && !"type".equals(key)) {
                    throw invalidRelationshipChanges("changes[" + i + "] has an unknown key "
                            + CallerText.quoted(String.valueOf(key)) + "; an entry takes relationshipId and type");
                }
            }
            Object relationshipId = entry.get("relationshipId");
            if (relationshipId != null && !(relationshipId instanceof String)) {
                throw invalidRelationshipChanges("changes[" + i + "].relationshipId must be a string");
            }
            Object type = entry.get("type");
            if (type != null && !(type instanceof String)) {
                throw invalidRelationshipChanges("changes[" + i + "].type must be a string");
            }
            changes.add(new RelationshipTypeChangeRequest((String) relationshipId, (String) type));
        }
        return changes;
    }

    private static ModelAccessException invalidRelationshipChanges(String message) {
        return new ModelAccessException(message + ". Nothing was changed.", ErrorCode.INVALID_PARAMETER, null,
                "Pass changes: [{\"relationshipId\": \"<id>\", \"type\": \"<RelationshipType>\"}, ...].", null);
    }

    private List<String> buildChangeRelationshipTypeNextSteps(
            MutationResult<ChangeRelationshipTypeResultDto> result) {
        ChangeRelationshipTypeResultDto changed = result.entity();
        List<String> steps = new ArrayList<>();
        steps.add("Use get-relationships to re-read a changed relationship, such as '"
                + changed.changes().get(0).resultingId() + "'");
        boolean attributes = changed.changes().stream().anyMatch(c -> TYPES_WITH_AN_ATTRIBUTE.contains(c.toType()));
        if (attributes) {
            steps.add("A new Access, Association or Influence relationship has its type's default (an Access "
                    + "is write, an Association undirected, an Influence has no strength): set it with "
                    + "update-relationship (accessType, associationDirected, influenceStrength)");
        }
        if (changed.totals().specializationsDropped() > 0) {
            steps.add("Review specializationDropped: update-relationship with specialization gives a "
                    + "relationship a specialization of its new type");
        }
        steps.add("Use undo (or Ctrl+Z in Archi) to reverse the whole change in one step");
        return steps;
    }
}
