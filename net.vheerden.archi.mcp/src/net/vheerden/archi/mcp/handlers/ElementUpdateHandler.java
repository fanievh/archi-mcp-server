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
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for the update-element tool.
 *
 * <p>Updates existing ArchiMate element and relationship fields (name, documentation, properties).
 * Supports both GUI-attached (immediate) and batch (queued) operational modes.</p>
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
     * Registers: update-element, update-relationship.
     */
    public void registerTools() {
        registry.registerTool(buildUpdateElementSpec());
        registry.registerTool(buildUpdateRelationshipSpec());
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
}
