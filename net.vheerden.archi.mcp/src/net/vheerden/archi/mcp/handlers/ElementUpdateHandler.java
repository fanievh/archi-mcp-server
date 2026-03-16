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
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for the update-element tool (Story 7-3).
 *
 * <p>Updates existing ArchiMate element fields (name, documentation, properties).
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
     * Registers: update-element.
     */
    public void registerTools() {
        registry.registerTool(buildUpdateElementSpec());
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

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", idProp);
        properties.put("name", nameProp);
        properties.put("documentation", documentationProp);
        properties.put("properties", propertiesProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("id"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("update-element")
                .description("[Mutation] Update an existing ArchiMate element. "
                        + "Requires id. Optional: name (new display name), documentation "
                        + "(new description text), properties (object with key-value pairs; "
                        + "set value to null to remove a property). Only provided fields are "
                        + "modified; omitted fields remain unchanged. If a property key appears "
                        + "multiple times on the element, only the first occurrence is updated. "
                        + "Related: get-element (inspect before/after), search-elements "
                        + "(find element to update), create-element (create new elements).")
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

            MutationResult<ElementDto> result = accessor.updateElement(
                    sessionId, id, name, documentation, properties);

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
}
