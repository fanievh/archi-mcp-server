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
import net.vheerden.archi.mcp.model.ToolArguments;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.UndoRedoResultDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for command stack undo/redo MCP tools.
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
	private final SessionManager sessionManager;

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
		this(accessor, formatter, registry, null);
	}

	/**
	 * Creates a CommandStackHandler that resolves the calling session, so undo and redo are held
	 * to that session's open batch.
	 *
	 * @param sessionManager the session manager, nullable (every call then uses the default session)
	 */
	public CommandStackHandler(ArchiModelAccessor accessor,
							   ResponseFormatter formatter,
							   CommandRegistry registry,
							   SessionManager sessionManager) {
		this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
		this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
		this.registry = Objects.requireNonNull(registry, "registry must not be null");
		this.sessionManager = sessionManager;
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
						+ "CommandStack entry — a bulk-mutate with 10 sub-operations is still ONE undo step. "
						+ "A count larger than the history undoes what there is, and stops at a change made by hand.",
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
						+ "unsatisfied. This is the recommended way to 'preview' the effect of any mutation. "
						+ "Undo stops at, and does not cross, a change made by hand in Archi. If an operation "
						+ "fails while it is undone, the rest of it is still reversed, it leaves the undo "
						+ "history, and the call fails with MUTATION_FAILED naming what was undone before it "
						+ "and saying part of it may still be in the model. Refused with "
						+ "MODEL_SHARED while another session is working on the same model, because Archi keeps "
						+ "one undo history per model, not per session.")
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
				"description", "Number of operations to redo (default: 1). Re-applies previously undone operations. "
						+ "A count larger than the history redoes what there is, and stops at a change made by hand.",
				"minimum", 1));

		McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
				"object", properties, null, null, null, null);

		McpSchema.Tool tool = McpSchema.Tool.builder()
				.name("redo")
				.description("[Mutation] Redo previously undone operation(s). Re-applies changes that were rolled back "
						+ "by undo. The redo stack is cleared when a new mutation is performed after an undo "
						+ "(standard undo/redo semantics). If an operation fails while it is redone, what it "
						+ "had redone is put back, it leaves the history, and the call fails with "
						+ "MUTATION_FAILED naming what was redone before it and saying part of it may still be "
						+ "in the model. Refused with MODEL_SHARED while another session is "
						+ "working on the same model, as undo is.")
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

			// A step count below 1 is refused rather than raised to 1.
			Integer stepsParam = ToolArguments.wholeNumberWithin(request.arguments(), "steps", 1, Integer.MAX_VALUE);
			int steps = stepsParam != null ? stepsParam : 1;

			UndoRedoResultDto result = accessor.undo(
					HandlerUtils.extractSessionId(sessionManager, exchange), steps);

			if (result.operationsPerformed() == 0 && result.blockedReason() != null) {
				// Betrayal guard: the top of the stack is the human's change.
				// Distinct from an empty stack — the agent must not silently revert hand-work.
				ErrorResponse error = new ErrorResponse(
						ErrorCode.MUTATION_FAILED,
						"Cannot undo — the most recent change is the human's.",
						"Agent undo is limited to its own changes and stops at the human's work; "
								+ "it will never silently evaporate a hand-drawn change.",
						"To revert the human's change, submit a new proposed change for them to "
								+ "approve. The human's native Ctrl+Z can still undo it.", null);
				return HandlerUtils.buildResult(
						formatter.toJsonString(formatter.formatError(error)), true);
			}

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

			// A step count below 1 is refused rather than raised to 1.
			Integer stepsParam = ToolArguments.wholeNumberWithin(request.arguments(), "steps", 1, Integer.MAX_VALUE);
			int steps = stepsParam != null ? stepsParam : 1;

			UndoRedoResultDto result = accessor.redo(
					HandlerUtils.extractSessionId(sessionManager, exchange), steps);

			if (result.operationsPerformed() == 0 && result.blockedReason() != null) {
				// Betrayal guard: the top redo entry is the human's change.
				// A human change must never be re-applied (or evaporated) by the agent.
				ErrorResponse error = new ErrorResponse(
						ErrorCode.MUTATION_FAILED,
						"Cannot redo — the most recent undone change is the human's.",
						"Agent redo is limited to its own changes and stops at the human's work.",
						"To re-apply the human's change, submit a new proposed change for them to "
								+ "approve. The human's native Ctrl+Y can still redo it.", null);
				return HandlerUtils.buildResult(
						formatter.toJsonString(formatter.formatError(error)), true);
			}

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
