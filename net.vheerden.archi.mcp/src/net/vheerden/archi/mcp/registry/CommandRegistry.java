package net.vheerden.archi.mcp.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Central registry for MCP command handlers.
 *
 * <p>Handlers register their tool definitions at startup. The registry provides
 * these specifications to {@link net.vheerden.archi.mcp.server.TransportConfig}
 * for MCP server wiring, and supports runtime additions after the servers are built.</p>
 *
 * <p>Thread-safe: uses {@link CopyOnWriteArrayList} for tool specs accessed
 * from Jetty threads and the UI thread.</p>
 *
 * <p>Follows the open/closed principle — new handlers self-register
 * without modifying existing code.</p>
 */
public class CommandRegistry {

    private static final Logger logger = LoggerFactory.getLogger(CommandRegistry.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final List<McpServerFeatures.SyncToolSpecification> toolSpecs = new CopyOnWriteArrayList<>();

    private volatile McpSyncServer streamableMcpServer;
    private volatile McpSyncServer sseMcpServer;

    /**
     * Registers a tool specification for MCP tool discovery and invocation.
     *
     * <p>If the MCP servers are already built (post-startup), the tool is also
     * added at runtime via {@link McpSyncServer#addTool(McpServerFeatures.SyncToolSpecification)}
     * and clients are notified of the change.</p>
     *
     * @param toolSpec the tool specification to register
     * @throws NullPointerException if toolSpec is null
     */
    public void registerTool(McpServerFeatures.SyncToolSpecification toolSpec) {
        Objects.requireNonNull(toolSpec, "toolSpec must not be null");
        McpServerFeatures.SyncToolSpecification wrapped = wrapWithTiming(toolSpec);
        toolSpecs.add(wrapped);

        String toolName = toolSpec.tool() != null ? toolSpec.tool().name() : "unknown";
        logger.info("Registered MCP tool: {}", toolName);

        // If servers are already built, add at runtime
        addToolToRunningServers(wrapped);
    }

    /**
     * Returns an unmodifiable view of all registered tool specifications.
     *
     * <p>Used by {@link net.vheerden.archi.mcp.server.TransportConfig} at build time
     * to pass tool specs to the MCP server builders.</p>
     *
     * @return unmodifiable list of tool specifications
     */
    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        return Collections.unmodifiableList(toolSpecs);
    }

    /**
     * Returns the number of registered tools.
     *
     * @return tool count
     */
    public int getToolCount() {
        return toolSpecs.size();
    }

    /**
     * Sets the MCP server instances for runtime tool addition.
     *
     * <p>Called by {@link net.vheerden.archi.mcp.server.McpServerManager} after
     * TransportConfig has built the servers.</p>
     *
     * @param streamable the Streamable-HTTP MCP server
     * @param sse the SSE MCP server
     */
    public void setMcpServers(McpSyncServer streamable, McpSyncServer sse) {
        this.streamableMcpServer = streamable;
        this.sseMcpServer = sse;
    }

    /**
     * Clears server references on shutdown.
     */
    public void clearMcpServers() {
        this.streamableMcpServer = null;
        this.sseMcpServer = null;
    }

    /**
     * Removes all registered tool specifications.
     *
     * <p>Called during server shutdown so that a subsequent restart
     * does not accumulate duplicate tool registrations.</p>
     */
    public void clearTools() {
        toolSpecs.clear();
    }

    private McpServerFeatures.SyncToolSpecification wrapWithTiming(
            McpServerFeatures.SyncToolSpecification original) {
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
                originalHandler = original.callHandler();
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
                timedHandler = (exchange, request) -> {
            long startNanos = System.nanoTime();
            McpSchema.CallToolResult result = originalHandler.apply(exchange, request);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            return injectDurationMs(result, durationMs);
        };
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(original.tool())
                .callHandler(timedHandler)
                .build();
    }

    /**
     * Injects {@code durationMs} into the {@code _meta} section of a tool response.
     *
     * <p>Parses the JSON text from the first {@link McpSchema.TextContent} in the result,
     * adds or creates the {@code _meta} map with the {@code durationMs} field, re-serialises,
     * and rebuilds the {@link McpSchema.CallToolResult}. All other content items (e.g.
     * ImageContent from export-view) are preserved.</p>
     */
    @SuppressWarnings("unchecked")
    static McpSchema.CallToolResult injectDurationMs(
            McpSchema.CallToolResult result, long durationMs) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return result;
        }

        McpSchema.Content firstContent = result.content().get(0);
        if (!(firstContent instanceof McpSchema.TextContent textContent)) {
            logger.warn("injectDurationMs: first content is not TextContent, skipping injection");
            return result;
        }

        String json = textContent.text();
        if (json == null || json.isBlank()) {
            return result;
        }

        try {
            Map<String, Object> envelope = OBJECT_MAPPER.readValue(json,
                    OBJECT_MAPPER.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class, Object.class));

            Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
            if (meta == null) {
                meta = new LinkedHashMap<>();
                envelope.put("_meta", meta);
            }
            meta.put("durationMs", durationMs);

            String updatedJson = OBJECT_MAPPER.writeValueAsString(envelope);

            // Preserve all content items — replace only the first TextContent
            List<McpSchema.Content> updatedContent = new ArrayList<>(result.content());
            updatedContent.set(0, new McpSchema.TextContent(updatedJson));

            return McpSchema.CallToolResult.builder()
                    .content(updatedContent)
                    .isError(result.isError() != null ? result.isError() : false)
                    .build();
        } catch (JsonProcessingException e) {
            logger.warn("injectDurationMs: failed to parse/re-serialise JSON, skipping injection", e);
            return result;
        }
    }

    private void addToolToRunningServers(McpServerFeatures.SyncToolSpecification toolSpec) {
        McpSyncServer streamable = this.streamableMcpServer;
        McpSyncServer sse = this.sseMcpServer;

        if (streamable != null) {
            try {
                streamable.addTool(toolSpec);
                streamable.notifyToolsListChanged();
            } catch (Exception e) {
                logger.warn("Failed to add tool to streamable MCP server at runtime", e);
            }
        }

        if (sse != null) {
            try {
                sse.addTool(toolSpec);
                sse.notifyToolsListChanged();
            } catch (Exception e) {
                logger.warn("Failed to add tool to SSE MCP server at runtime", e);
            }
        }
    }
}
