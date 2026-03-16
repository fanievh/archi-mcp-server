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
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.UndoRedoResultDto;

/**
 * Handler for command stack undo/redo MCP tools (Story 11-1).
 *
 * <p>Exposes Archi's native GEF CommandStack undo/redo as MCP tools,
 * enabling LLM agents to experiment with layouts and roll back
 * unsuccessful changes.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF, GEF, SWT, or ArchimateTool model types. All command stack
 * interaction goes through {@link ArchiModelAccessor}.</p>
 */
public class CommandStackHandler {

	private static final Logger logger = LoggerFactory.getLogger(CommandStackHandler.class);

	private final ArchiModelAccessor accessor;
	private final ResponseFormatter formatter;
	private final CommandRegistry registry;

	/**
	 * Creates a CommandStackHandler with its required dependencies.
	 *
	 * @param accessor  the model accessor for undo/redo operations
	 * @param formatter the response formatter for building JSON envelopes
	 * @param registry  the command registry for tool registration
	 */
	public CommandStackHandler(ArchiModelAccessor accessor,
							   ResponseFormatter formatter,
							   CommandRegistry registry) {
		this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
		this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
		this.registry = Objects.requireNonNull(registry, "registry must not be null");
	}

	/**
	 * Registers undo and redo tools with the command registry.
	 */
	public void registerTools() {
		registry.registerTool(buildUndoSpec());
		registry.registerTool(buildRedoSpec());
	}

	// ---- Tool specifications ----

	private McpServerFeatures.SyncToolSpecification buildUndoSpec() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("steps", Map.of(
				"type", "integer",
				"description", "Number of operations to undo (default: 1). Each step undoes one top-level "
						+ "CommandStack entry — a bulk-mutate with 10 sub-operations is still ONE undo step.",
				"minimum", 1));

		McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
				"object", properties, null, null, null, null);

		McpSchema.Tool tool = McpSchema.Tool.builder()
				.name("undo")
				.description("[Mutation] Undo the most recent mutation operation(s). Rolls back changes made by "
						+ "apply-positions, bulk-mutate, auto-route-connections, create-element, etc. "
						+ "Use `steps` to undo multiple operations in one call (e.g., undo layout + routing "
						+ "together with steps=2). Standard sequential undo — most recent operation first. "
						+ "SPECULATIVE EXECUTION: Use undo as a deliberate experimentation strategy — apply "
						+ "layout or routing, run assess-layout to evaluate the result, then undo if "
						+ "unsatisfied. This is the recommended way to 'preview' the effect of any mutation.")
				.inputSchema(inputSchema)
				.build();

		return McpServerFeatures.SyncToolSpecification.builder()
				.tool(tool)
				.callHandler(this::handleUndo)
				.build();
	}

	private McpServerFeatures.SyncToolSpecification buildRedoSpec() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("steps", Map.of(
				"type", "integer",
				"description", "Number of operations to redo (default: 1). Re-applies previously undone operations.",
				"minimum", 1));

		McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
				"object", properties, null, null, null, null);

		McpSchema.Tool tool = McpSchema.Tool.builder()
				.name("redo")
				.description("[Mutation] Redo previously undone operation(s). Re-applies changes that were rolled back "
						+ "by undo. The redo stack is cleared when a new mutation is performed after an undo "
						+ "(standard undo/redo semantics).")
				.inputSchema(inputSchema)
				.build();

		return McpServerFeatures.SyncToolSpecification.builder()
				.tool(tool)
				.callHandler(this::handleRedo)
				.build();
	}

	// ---- Handler methods ----

	McpSchema.CallToolResult handleUndo(
			McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
		logger.info("Handling undo request");
		try {
			HandlerUtils.requireModelLoaded(accessor);

			int steps = 1;
			if (request.arguments() != null) {
				Object stepsObj = request.arguments().get("steps");
				if (stepsObj instanceof Number n) {
					steps = Math.max(1, n.intValue());
				}
			}

			UndoRedoResultDto result = accessor.undo(steps);

			if (result.operationsPerformed() == 0) {
				ErrorResponse error = new ErrorResponse(
						ErrorCode.MUTATION_FAILED, "Nothing to undo",
						"The command stack has no undoable operations.",
						"Perform mutation operations first before calling undo.", null);
				return HandlerUtils.buildResult(
						formatter.toJsonString(formatter.formatError(error)), true);
			}

			List<String> nextSteps = List.of(
					"Use redo to re-apply undone operations",
					"Use assess-layout to evaluate the current view state",
					"Use get-view-contents to inspect the current state");

			Map<String, Object> envelope = formatter.formatSuccess(
					result, nextSteps, accessor.getModelVersion(), 1, 1, false);

			return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

		} catch (NoModelLoadedException e) {
			return HandlerUtils.buildModelNotLoadedError(formatter, e);
		} catch (ModelAccessException e) {
			return HandlerUtils.buildModelAccessError(formatter, e);
		} catch (Exception e) {
			logger.error("Unexpected error handling undo", e);
			return HandlerUtils.buildInternalError(formatter,
					"Unexpected error during undo: " + e.getMessage());
		}
	}

	McpSchema.CallToolResult handleRedo(
			McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
		logger.info("Handling redo request");
		try {
			HandlerUtils.requireModelLoaded(accessor);

			int steps = 1;
			if (request.arguments() != null) {
				Object stepsObj = request.arguments().get("steps");
				if (stepsObj instanceof Number n) {
					steps = Math.max(1, n.intValue());
				}
			}

			UndoRedoResultDto result = accessor.redo(steps);

			if (result.operationsPerformed() == 0) {
				ErrorResponse error = new ErrorResponse(
						ErrorCode.MUTATION_FAILED, "Nothing to redo",
						"The command stack has no redoable operations.",
						"Use undo first, then redo to re-apply undone operations.", null);
				return HandlerUtils.buildResult(
						formatter.toJsonString(formatter.formatError(error)), true);
			}

			List<String> nextSteps = List.of(
					"Use undo to roll back the redone operations",
					"Use assess-layout to evaluate the current view state",
					"Use get-view-contents to inspect the current state");

			Map<String, Object> envelope = formatter.formatSuccess(
					result, nextSteps, accessor.getModelVersion(), 1, 1, false);

			return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

		} catch (NoModelLoadedException e) {
			return HandlerUtils.buildModelNotLoadedError(formatter, e);
		} catch (ModelAccessException e) {
			return HandlerUtils.buildModelAccessError(formatter, e);
		} catch (Exception e) {
			logger.error("Unexpected error handling redo", e);
			return HandlerUtils.buildInternalError(formatter,
					"Unexpected error during redo: " + e.getMessage());
		}
	}
}
