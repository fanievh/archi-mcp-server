package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.ToolArguments;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.registry.ToolCallScope;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * Refuses a tool call that carries an argument the tool does not read, before its handler runs, by the
 * rule in {@link ToolArguments}. Opened around the model scope, which answers first: a bad
 * {@code modelId} is refused as such, and the response of a refused call still names its model.
 */
final class ArgumentCallScope implements ToolCallScope {

    private final ToolCallScope inner;
    private final CommandRegistry registry;
    private final ResponseFormatter formatter;

    /** @param inner the scope opened first, or null for none */
    ArgumentCallScope(ToolCallScope inner, CommandRegistry registry, ResponseFormatter formatter) {
        this.inner = inner;
        this.registry = registry;
        this.formatter = formatter;
    }

    @Override
    public McpSchema.CallToolResult enter(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        McpSchema.CallToolResult refusal = inner == null ? null : inner.enter(exchange, request);
        if (refusal != null || request == null) {
            return refusal;
        }
        try {
            ToolArguments.refuseUnknown(shapeOf(registry, request.name()), request.arguments());
            return null;
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        }
    }

    @Override
    public Map<String, Object> responseMeta() {
        return inner == null ? null : inner.responseMeta();
    }

    @Override
    public void exit() {
        if (inner != null) {
            inner.exit();
        }
    }

    /** The keys the registered tool {@code name} reads, as its served schema declares them; null when none is registered. */
    static ToolArguments.Shape shapeOf(CommandRegistry registry, String name) {
        for (McpServerFeatures.SyncToolSpecification spec : registry.getToolSpecifications()) {
            McpSchema.Tool tool = spec.tool();
            if (tool != null && tool.name().equals(name)) {
                McpSchema.JsonSchema schema = tool.inputSchema();
                return new ToolArguments.Shape(name,
                        schema == null || schema.properties() == null ? new ArrayList<>()
                                : new ArrayList<>(schema.properties().keySet()),
                        schema == null ? null : schema.required());
            }
        }
        return null;
    }
}
