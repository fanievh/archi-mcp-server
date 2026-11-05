package net.vheerden.archi.mcp.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    /** Run on the handler's thread once each tool call ends, returned or thrown; null for none. */
    private volatile Runnable callExitHook;

    /** Opened around every tool call; null for none. */
    private volatile ToolCallScope callScope;

    /** Applied to each tool definition as it is registered; null for none. */
    private volatile UnaryOperator<McpSchema.Tool> toolDecorator;

    /** Decides, after decoration, whether a tool is registered at all; null admits every tool. */
    private volatile Predicate<McpSchema.Tool> toolAdmission;

    /** Names of the tools the admission refused since the tools were last cleared. */
    private final Set<String> withheldToolNames = ConcurrentHashMap.newKeySet();

    /** Matches any withheld tool name as a whole token, or null while none is withheld. */
    private volatile Pattern withheldNamePattern;

    /**
     * Registers a tool specification for MCP tool discovery and invocation.
     *
     * <p>If the MCP servers are already built (post-startup), the tool is also
     * added at runtime via {@link McpSyncServer#addTool(McpServerFeatures.SyncToolSpecification)}
     * and clients are notified of the change.</p>
     *
     * <p>A tool the admission refuses is not registered: it is neither listed nor callable, and its
     * name is recorded in {@link #getWithheldToolNames()}.</p>
     *
     * @param toolSpec the tool specification to register
     * @throws NullPointerException if toolSpec is null
     * @throws IllegalStateException if a tool with the same name is already registered
     */
    public void registerTool(McpServerFeatures.SyncToolSpecification toolSpec) {
        Objects.requireNonNull(toolSpec, "toolSpec must not be null");

        String toolName = toolSpec.tool() != null ? toolSpec.tool().name() : null;
        rejectDuplicateName(toolName);

        UnaryOperator<McpSchema.Tool> decorator = this.toolDecorator;
        if (decorator != null && toolSpec.tool() != null) {
            toolSpec = McpServerFeatures.SyncToolSpecification.builder()
                    .tool(decorator.apply(toolSpec.tool()))
                    .callHandler(toolSpec.callHandler())
                    .build();
        }
        Predicate<McpSchema.Tool> admission = this.toolAdmission;
        if (admission != null && toolSpec.tool() != null && !admission.test(toolSpec.tool())) {
            withheldToolNames.add(toolName);
            withheldNamePattern = wholeTokenPattern(withheldToolNames);
            logger.debug("Withheld MCP tool: {}", toolName);
            return;
        }
        McpServerFeatures.SyncToolSpecification wrapped = wrapWithTiming(toolSpec);
        toolSpecs.add(wrapped);

        logger.info("Registered MCP tool: {}", toolName != null ? toolName : "unknown");

        // If servers are already built, add at runtime
        addToolToRunningServers(wrapped);
    }

    /**
     * Enforces tool-name uniqueness at the source, before the spec is added.
     *
     * <p>Every MCP tool is registered exactly once, at startup, through this registry
     * (see {@code HandlerRegistrar.registerAll}); {@link #clearTools()} handles the
     * restart case. A second registration of an already-known name is therefore always
     * a wiring bug — two handlers, or one handler twice, claiming the same name — and is
     * rejected here so it fails fast at the offending call rather than being caught only
     * after the fact when the assembled tool set is inspected.</p>
     *
     * <p>Uniqueness is keyed on the tool name; a spec with no resolvable name is left to
     * the existing null-tolerant handling and not deduplicated.</p>
     *
     * @param toolName the name of the tool being registered
     * @throws IllegalStateException if a tool with this name is already registered
     */
    private void rejectDuplicateName(String toolName) {
        if (toolName == null) {
            // A spec with no resolvable name is not deduplicated — uniqueness is
            // keyed on the name, and a null name carries no identity to compare.
            return;
        }
        for (McpServerFeatures.SyncToolSpecification existing : toolSpecs) {
            if (existing.tool() != null && toolName.equals(existing.tool().name())) {
                throw new IllegalStateException(
                        "Duplicate MCP tool name: '" + toolName + "' is already registered");
            }
        }
        // A withheld tool still claims its name, so a wiring bug is caught whichever tools are served.
        if (withheldToolNames.contains(toolName)) {
            throw new IllegalStateException(
                    "Duplicate MCP tool name: " + toolName + " is already registered and withheld");
        }
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
     * Sets the action run once after every tool call, on the thread that ran the handler, whether the
     * handler returned or threw. Replaced on each start and cleared by {@link #clearTools()}.
     *
     * @param hook the action, or null for none
     */
    public void setCallExitHook(Runnable hook) {
        this.callExitHook = hook;
    }

    /**
     * Sets the scope opened around every tool call: before the handler runs, the scope may answer the
     * call itself; after it returns, the scope's fields are added to the response's {@code _meta}; and
     * it is closed whether the handler returned or threw. Replaced on each start and cleared by
     * {@link #clearTools()}.
     *
     * @param scope the scope, or null for none
     */
    public void setCallScope(ToolCallScope scope) {
        this.callScope = scope;
    }

    /**
     * Sets a transformation applied to each tool definition as it is registered, so a property shared
     * by many tools is declared once rather than in every handler. Applies to tools registered after
     * it is set, and is cleared by {@link #clearTools()}.
     *
     * @param decorator the transformation, or null for none
     */
    public void setToolDecorator(UnaryOperator<McpSchema.Tool> decorator) {
        this.toolDecorator = decorator;
    }

    /**
     * Sets the rule that decides whether a tool is registered at all. It is applied after the tool
     * decorator, so it judges the definition as it would be served. Applies to tools registered after
     * it is set, and is cleared by {@link #clearTools()}.
     *
     * @param admission true for a tool to register; null to register every tool
     */
    public void setToolAdmission(Predicate<McpSchema.Tool> admission) {
        this.toolAdmission = admission;
    }

    /**
     * Returns the names of the tools the admission refused since the tools were last cleared.
     *
     * @return an unmodifiable snapshot of the withheld names
     */
    public Set<String> getWithheldToolNames() {
        return Set.copyOf(withheldToolNames);
    }

    /**
     * Removes all registered tool specifications.
     *
     * <p>Called during server shutdown so that a subsequent restart
     * does not accumulate duplicate tool registrations. Also drops the call-exit
     * hook, the call scope, the tool decorator, the admission and the withheld names, so a stopped
     * server keeps nothing it captured reachable.</p>
     */
    public void clearTools() {
        toolSpecs.clear();
        withheldToolNames.clear();
        withheldNamePattern = null;
        toolAdmission = null;
        callExitHook = null;
        callScope = null;
        toolDecorator = null;
    }

    private McpServerFeatures.SyncToolSpecification wrapWithTiming(
            McpServerFeatures.SyncToolSpecification original) {
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
                originalHandler = original.callHandler();
        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
                timedHandler = (exchange, request) -> {
            long startNanos = System.nanoTime();
            ToolCallScope scope = this.callScope;
            try {
                McpSchema.CallToolResult refusal = scope == null ? null : scope.enter(exchange, request);
                McpSchema.CallToolResult result = refusal != null ? refusal
                        : originalHandler.apply(exchange, request);
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                return injectMeta(result, durationMs, scope == null ? null : scope.responseMeta(),
                        this.withheldNamePattern);
            } finally {
                if (scope != null) {
                    exitScope(scope);
                }
                runCallExitHook();
            }
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
    static McpSchema.CallToolResult injectDurationMs(
            McpSchema.CallToolResult result, long durationMs) {
        return injectMeta(result, durationMs, null);
    }

    /**
     * As {@link #injectDurationMs}, also adding {@code extra}'s fields to {@code _meta}.
     *
     * @param extra fields to add, or null for none
     */
    static McpSchema.CallToolResult injectMeta(
            McpSchema.CallToolResult result, long durationMs, Map<String, Object> extra) {
        return injectMeta(result, durationMs, extra, null);
    }

    /**
     * As {@link #injectMeta(McpSchema.CallToolResult, long, Map)}, also dropping from a success response's
     * {@code nextSteps} each text entry that {@code withheld} finds, so a response never suggests a tool
     * this server does not offer. The rest keep their order; the array stays, empty if nothing is left.
     *
     * @param withheld matches the name of a tool this server withholds, or null to keep every entry
     */
    @SuppressWarnings("unchecked")
    static McpSchema.CallToolResult injectMeta(McpSchema.CallToolResult result, long durationMs,
            Map<String, Object> extra, Pattern withheld) {
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
            if (extra != null) {
                meta.putAll(extra);
            }
            if (withheld != null && !Boolean.TRUE.equals(result.isError())
                    && envelope.get("nextSteps") instanceof List<?> steps) {
                envelope.put("nextSteps", steps.stream()
                        .filter(step -> !(step instanceof String text && withheld.matcher(text).find()))
                        .collect(Collectors.toCollection(ArrayList::new)));
            }

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

    /** A pattern matching any of {@code names} as a whole tool-name token, or null when there are none. */
    private static Pattern wholeTokenPattern(Set<String> names) {
        if (names.isEmpty()) {
            return null;
        }
        return Pattern.compile("(?<![a-z-])(?:" + names.stream().sorted().map(Pattern::quote)
                .collect(Collectors.joining("|")) + ")(?![a-z-])");
    }

    /** Closes the call scope; a scope that throws never replaces the call's own outcome. */
    private static void exitScope(ToolCallScope scope) {
        try {
            scope.exit();
        } catch (RuntimeException e) {
            logger.warn("Tool call scope threw on exit (ignored)", e);
        }
    }

    /** Runs the call-exit hook, if any; a hook that throws never replaces the call's own outcome. */
    private void runCallExitHook() {
        Runnable hook = this.callExitHook;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException e) {
            logger.warn("Tool call-exit hook threw (ignored)", e);
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
