package net.vheerden.archi.mcp.handlers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.ModelSelection;
import net.vheerden.archi.mcp.registry.ToolCallScope;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Opens each tool call's model scope: which session is calling, and which model the call names with
 * {@code modelId}. A call that names a model that is not open, or that is open more than once, is
 * answered with that refusal before its handler runs. Every response then names the model the call
 * acted on and how it was resolved, as {@code _meta.model}.
 */
final class ModelCallScope implements ToolCallScope {

    /** The parameter every model-addressing tool accepts. */
    static final String MODEL_ID = "modelId";

    /**
     * Tools that do not take {@code modelId}: those that touch no model, those acting on state that is
     * already bound to one (a batch, a proposal), the catalogue itself, and the read-only tools whose
     * answer does not feed a change. Every other tool, including any added later, takes it.
     */
    static final Set<String> NOT_ADDRESSABLE = Set.of(
            "get-guidance", "get-session-filters", "set-session-filter", "get-batch-status",
            "end-batch", "list-pending-approvals", "get-models",
            "detect-hub-elements", "export-view", "get-specialization-usage");

    /**
     * Tools on which a {@code modelId} means nothing, so one sent anyway is ignored rather than checked:
     * they touch no model, act on state already bound to one (a batch), or are how a caller recovers from
     * a bad address. Refusing a stale id there could stop a batch being discarded. The read-only tools
     * that do act on a model honour a supplied {@code modelId} even though they do not declare it.
     */
    static final Set<String> IGNORES_MODEL_ID = Set.of(
            "get-guidance", "get-session-filters", "set-session-filter", "get-batch-status",
            "end-batch", "list-pending-approvals", "get-models");

    private final ModelSelection selection;
    private final ResponseFormatter formatter;
    private final SessionManager sessionManager;

    ModelCallScope(ModelSelection selection, ResponseFormatter formatter, SessionManager sessionManager) {
        this.selection = selection;
        this.formatter = formatter;
        this.sessionManager = sessionManager;
    }

    @Override
    public McpSchema.CallToolResult enter(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);
        boolean ignored = request == null || IGNORES_MODEL_ID.contains(request.name());
        Object requested = ignored || request.arguments() == null ? null : request.arguments().get(MODEL_ID);
        if (requested != null && (!(requested instanceof String id) || id.isBlank())) {
            selection.enter(sessionId, null);
            return HandlerUtils.buildResult(formatter.toJsonString(formatter.formatError(new ErrorResponse(
                    ErrorCode.INVALID_PARAMETER,
                    "modelId must be the id of an open model, as a non-empty string; got " + describeValue(requested),
                    null, "Pass a model id from get-models as modelId, or omit modelId.", null))), true);
        }
        ModelAccessException refusal = selection.enter(sessionId, (String) requested);
        return refusal == null ? null : HandlerUtils.buildModelAccessError(formatter, refusal);
    }

    private static String describeValue(Object value) {
        return value instanceof String ? "a blank string" : "a " + value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    @Override
    public Map<String, Object> responseMeta() {
        Map<String, Object> model = selection.responseModel();
        return model == null ? null : Map.of("model", model);
    }

    @Override
    public void exit() {
        selection.exit();
    }

    /** Declares {@code modelId} on a tool that addresses a model and does not already declare it. */
    static McpSchema.Tool withModelId(McpSchema.Tool tool) {
        McpSchema.JsonSchema schema = tool.inputSchema();
        if (NOT_ADDRESSABLE.contains(tool.name()) || schema == null
                || (schema.properties() != null && schema.properties().containsKey(MODEL_ID))) {
            return tool;
        }
        Map<String, Object> properties = schema.properties() != null
                ? new LinkedHashMap<>(schema.properties()) : new LinkedHashMap<>();
        properties.put(MODEL_ID, modelIdProperty("The id of the open model this call acts on (get-models "
                + "lists them). Optional while one model is open. With two or more open, a change that "
                + "names none is refused, while a read follows the model active in Archi. Inside a batch "
                + "it defaults to the batch's model; a change naming a different model is refused, while "
                + "a read may name any open model."));
        McpSchema.JsonSchema decorated = new McpSchema.JsonSchema(schema.type(), properties,
                schema.required(), schema.additionalProperties(), schema.defs(), schema.definitions());
        return new McpSchema.Tool(tool.name(), tool.title(), tool.description(), decorated,
                tool.outputSchema(), tool.annotations(), tool.meta());
    }

    /** The {@code modelId} property, with its description. */
    static Map<String, Object> modelIdProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    /** The open models as "'name' (id …)" items, for an error that must list them. */
    static String describe(List<net.vheerden.archi.mcp.response.dto.OpenModelDto> models) {
        if (models.isEmpty()) {
            return "No model is open.";
        }
        StringBuilder sb = new StringBuilder("Open models: ");
        for (int i = 0; i < models.size(); i++) {
            sb.append(i == 0 ? "" : "; ").append(i + 1).append(". '").append(models.get(i).name())
                    .append("' (id ").append(models.get(i).id()).append(')');
        }
        return sb.toString();
    }
}
