package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.UndoRedoResultDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Tests for {@link CommandStackHandler} undo/redo tools.
 */
public class CommandStackHandlerTest {

	private ObjectMapper objectMapper;
	private CommandRegistry registry;
	private ResponseFormatter formatter;
	private StubUndoRedoAccessor accessor;
	private CommandStackHandler handler;

	@Before
	public void setUp() {
		objectMapper = new ObjectMapper();
		registry = new CommandRegistry();
		formatter = new ResponseFormatter();
		accessor = new StubUndoRedoAccessor();
		handler = new CommandStackHandler(accessor, formatter, registry);
		handler.registerTools();
	}

	// ---- Registration tests ----

	@Test
	public void shouldRegisterTwoTools() {
		assertEquals(2, registry.getToolSpecifications().size());
	}

	@Test
	public void shouldRegisterUndoTool() {
		boolean found = registry.getToolSpecifications().stream()
				.anyMatch(spec -> "undo".equals(spec.tool().name()));
		assertTrue("undo tool should be registered", found);
	}

	@Test
	public void shouldRegisterRedoTool() {
		boolean found = registry.getToolSpecifications().stream()
				.anyMatch(spec -> "redo".equals(spec.tool().name()));
		assertTrue("redo tool should be registered", found);
	}

	// ---- Undo tests ----

	@Test
	public void shouldUndoSingleOperation_whenStepsOmitted() throws Exception {
		accessor.setUndoResult(new UndoRedoResultDto(1, 1,
				List.of("Create Element: MyActor"), true, true));

		Map<String, Object> result = callAndParse("undo", Map.of());

		Map<String, Object> entity = getResult(result);
		assertEquals(1, entity.get("operationsRequested"));
		assertEquals(1, entity.get("operationsPerformed"));
		@SuppressWarnings("unchecked")
		List<String> labels = (List<String>) entity.get("labels");
		assertEquals(1, labels.size());
		assertEquals("Create Element: MyActor", labels.get(0));
	}

	@Test
	public void shouldUndoMultipleOperations_whenStepsProvided() throws Exception {
		accessor.setUndoResult(new UndoRedoResultDto(3, 3,
				List.of("Op 3", "Op 2", "Op 1"), false, true));

		Map<String, Object> args = new HashMap<>();
		args.put("steps", 3);
		Map<String, Object> result = callAndParse("undo", args);

		Map<String, Object> entity = getResult(result);
		assertEquals(3, entity.get("operationsRequested"));
		assertEquals(3, entity.get("operationsPerformed"));
		@SuppressWarnings("unchecked")
		List<String> labels = (List<String>) entity.get("labels");
		assertEquals(3, labels.size());
	}

	@Test
	public void shouldPassTheLargestStepCountThrough_toUndoAndRedo_uncapped() throws Exception {
		accessor.setUndoResult(new UndoRedoResultDto(Integer.MAX_VALUE, 2, List.of("Op 2", "Op 1"), false, true));
		accessor.setRedoResult(new UndoRedoResultDto(Integer.MAX_VALUE, 2, List.of("Op 1", "Op 2"), true, false));

		Map<String, Object> undone = getResult(callAndParse("undo", Map.of("steps", Integer.MAX_VALUE)));
		Map<String, Object> redone = getResult(callAndParse("redo", Map.of("steps", Integer.MAX_VALUE)));

		assertEquals(Integer.MAX_VALUE, accessor.lastUndoSteps);
		assertEquals(Integer.MAX_VALUE, accessor.lastRedoSteps);
		assertEquals(Integer.MAX_VALUE, undone.get("operationsRequested"));
		assertEquals(2, undone.get("operationsPerformed"));
		assertEquals(Integer.MAX_VALUE, redone.get("operationsRequested"));
	}

	@Test
	public void shouldReturnPartialUndo_whenFewerAvailable() throws Exception {
		accessor.setUndoResult(new UndoRedoResultDto(5, 2,
				List.of("Op 2", "Op 1"), false, true));

		Map<String, Object> args = new HashMap<>();
		args.put("steps", 5);
		Map<String, Object> result = callAndParse("undo", args);

		Map<String, Object> entity = getResult(result);
		assertEquals(5, entity.get("operationsRequested"));
		assertEquals(2, entity.get("operationsPerformed"));
	}

	@Test
	public void shouldReturnError_whenNothingToUndo() throws Exception {
		accessor.setUndoResult(new UndoRedoResultDto(1, 0,
				Collections.emptyList(), false, false));

		McpSchema.CallToolResult result = callTool("undo", Map.of());
		assertTrue("Should be an error", result.isError());

		Map<String, Object> parsed = parseResult(result);
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals("MUTATION_FAILED", error.get("code"));
		assertTrue(((String) error.get("message")).contains("Nothing to undo"));
	}

	@Test
	public void shouldReturnBetrayalError_whenUndoBlockedByHumanEdit() throws Exception {
		// operationsPerformed == 0 AND a block reason present → the betrayal guard, NOT empty stack.
		accessor.setUndoResult(new UndoRedoResultDto(1, 0,
				Collections.emptyList(), true, false,
				net.vheerden.archi.mcp.model.MutationDispatcher.BLOCK_REASON_HUMAN_EDIT));

		McpSchema.CallToolResult result = callTool("undo", Map.of());
		assertTrue("Should be an error", result.isError());

		Map<String, Object> parsed = parseResult(result);
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals("MUTATION_FAILED", error.get("code"));
		String message = (String) error.get("message");
		assertTrue("betrayal message, not the empty-stack message",
				message.contains("the human's"));
		assertFalse("must NOT collapse into the generic empty-stack message",
				message.contains("Nothing to undo"));
		assertTrue("actionable correction points at the proposal path",
				((String) error.get("suggestedCorrection")).contains("proposed change"));
	}

	@Test
	public void shouldReturnBetrayalError_whenRedoBlockedByHumanEdit() throws Exception {
		accessor.setRedoResult(new UndoRedoResultDto(1, 0,
				Collections.emptyList(), false, true,
				net.vheerden.archi.mcp.model.MutationDispatcher.BLOCK_REASON_HUMAN_EDIT));

		McpSchema.CallToolResult result = callTool("redo", Map.of());
		assertTrue("Should be an error", result.isError());

		Map<String, Object> parsed = parseResult(result);
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals("MUTATION_FAILED", error.get("code"));
		String message = (String) error.get("message");
		assertTrue("betrayal message", message.contains("the human's"));
		assertFalse("must NOT collapse into the generic empty-stack message",
				message.contains("Nothing to redo"));
	}

	@Test
	public void shouldReturnNextSteps_afterSuccessfulUndo() throws Exception {
		accessor.setUndoResult(new UndoRedoResultDto(1, 1,
				List.of("Op 1"), true, true));

		Map<String, Object> result = callAndParse("undo", Map.of());

		@SuppressWarnings("unchecked")
		List<String> nextSteps = (List<String>) result.get("nextSteps");
		assertNotNull(nextSteps);
		assertFalse(nextSteps.isEmpty());
	}

	@Test
	public void shouldReturnEnvelope_afterUndo() throws Exception {
		accessor.setUndoResult(new UndoRedoResultDto(1, 1,
				List.of("Op 1"), true, true));

		Map<String, Object> result = callAndParse("undo", Map.of());

		assertNotNull(result.get("result"));
		assertNotNull(result.get("nextSteps"));
		assertNotNull(result.get("_meta"));
	}

	@Test
	public void shouldReportCanUndoAndCanRedo_afterUndo() throws Exception {
		accessor.setUndoResult(new UndoRedoResultDto(1, 1,
				List.of("Op 1"), true, true));

		Map<String, Object> result = callAndParse("undo", Map.of());

		Map<String, Object> entity = getResult(result);
		assertEquals(true, entity.get("canUndoAfter"));
		assertEquals(true, entity.get("canRedoAfter"));
	}

	// ---- Redo tests ----

	@Test
	public void shouldRedoSingleOperation_whenStepsOmitted() throws Exception {
		accessor.setRedoResult(new UndoRedoResultDto(1, 1,
				List.of("Create Element: MyActor"), true, false));

		Map<String, Object> result = callAndParse("redo", Map.of());

		Map<String, Object> entity = getResult(result);
		assertEquals(1, entity.get("operationsRequested"));
		assertEquals(1, entity.get("operationsPerformed"));
	}

	@Test
	public void shouldRedoMultipleOperations_whenStepsProvided() throws Exception {
		accessor.setRedoResult(new UndoRedoResultDto(3, 3,
				List.of("Op 1", "Op 2", "Op 3"), true, false));

		Map<String, Object> args = new HashMap<>();
		args.put("steps", 3);
		Map<String, Object> result = callAndParse("redo", args);

		Map<String, Object> entity = getResult(result);
		assertEquals(3, entity.get("operationsPerformed"));
	}

	@Test
	public void shouldReturnError_whenNothingToRedo() throws Exception {
		accessor.setRedoResult(new UndoRedoResultDto(1, 0,
				Collections.emptyList(), true, false));

		McpSchema.CallToolResult result = callTool("redo", Map.of());
		assertTrue("Should be an error", result.isError());

		Map<String, Object> parsed = parseResult(result);
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals("MUTATION_FAILED", error.get("code"));
		assertTrue(((String) error.get("message")).contains("Nothing to redo"));
	}

	@Test
	public void shouldReturnPartialRedo_whenFewerAvailable() throws Exception {
		accessor.setRedoResult(new UndoRedoResultDto(5, 2,
				List.of("Op 1", "Op 2"), true, false));

		Map<String, Object> args = new HashMap<>();
		args.put("steps", 5);
		Map<String, Object> result = callAndParse("redo", args);

		Map<String, Object> entity = getResult(result);
		assertEquals(5, entity.get("operationsRequested"));
		assertEquals(2, entity.get("operationsPerformed"));
	}

	// ---- Default steps=1 tests ----

	@Test
	public void shouldDefaultToOneStep_whenUndoCalledWithNoArgs() throws Exception {
		accessor.setUndoResult(new UndoRedoResultDto(1, 1,
				List.of("Op 1"), true, true));

		callAndParse("undo", Map.of());

		assertEquals(1, accessor.lastUndoSteps);
	}

	@Test
	public void shouldDefaultToOneStep_whenRedoCalledWithNoArgs() throws Exception {
		accessor.setRedoResult(new UndoRedoResultDto(1, 1,
				List.of("Op 1"), true, false));

		callAndParse("redo", Map.of());

		assertEquals(1, accessor.lastRedoSteps);
	}

	// ---- Session ----

	/** An exchange carrying a session id, as the transport supplies one. */
	private static McpSyncServerExchange exchangeFor(String sessionId) {
		return new McpSyncServerExchange(null) {
			@Override
			public String sessionId() {
				return sessionId;
			}
		};
	}

	private CommandStackHandler sessionAwareHandler() {
		return new CommandStackHandler(accessor, formatter, new CommandRegistry(),
				new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS));
	}

	@Test
	public void shouldPassTheCallersSessionToUndoAndRedo() {
		CommandStackHandler sessionAware = sessionAwareHandler();
		McpSchema.CallToolRequest undo = McpSchema.CallToolRequest.builder().name("undo")
				.arguments(Map.of()).build();
		McpSchema.CallToolRequest redo = McpSchema.CallToolRequest.builder().name("redo")
				.arguments(Map.of()).build();

		sessionAware.handleUndo(exchangeFor("session-42"), undo);
		sessionAware.handleRedo(exchangeFor("session-43"), redo);

		assertEquals("session-42", accessor.lastUndoSession);
		assertEquals("session-43", accessor.lastRedoSession);
	}

	@Test
	public void shouldUseTheDefaultSession_whenNoSessionManagerIsWired() throws Exception {
		callAndParse("undo", Map.of());

		assertEquals("default", accessor.lastUndoSession);
	}

	@Test
	public void shouldReportTheBindingCode_whenUndoIsRefusedForTheSessionsBatch() throws Exception {
		accessor.undoRefusal = new ModelAccessException("'undo' refused: this batch is bound to model "
				+ "'Model A' (id model-a), but the active model is now 'Model B' (id model-b).",
				ErrorCode.BOUND_MODEL_NOT_ACTIVE);

		McpSchema.CallToolResult result = sessionAwareHandler().handleUndo(exchangeFor("session-42"),
				McpSchema.CallToolRequest.builder().name("undo").arguments(Map.of()).build());

		assertTrue(result.isError());
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) parseResult(result).get("error");
		assertEquals("BOUND_MODEL_NOT_ACTIVE", error.get("code"));
	}

	// ---- Model not loaded test ----

	@Test
	public void shouldReturnModelNotLoaded_whenNoModel() throws Exception {
		StubUndoRedoAccessor noModel = new StubUndoRedoAccessor(false);
		CommandStackHandler noModelHandler = new CommandStackHandler(
				noModel, formatter, new CommandRegistry());

		McpSchema.CallToolResult result = noModelHandler.handleUndo(null,
				McpSchema.CallToolRequest.builder().name("undo")
						.arguments(Map.of()).build());
		Map<String, Object> parsed = parseResult(result);

		assertTrue("Should be an error", result.isError());
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals("MODEL_NOT_LOADED", error.get("code"));
	}

	@Test
	public void shouldReturnModelNotLoaded_whenNoModelOnRedo() throws Exception {
		StubUndoRedoAccessor noModel = new StubUndoRedoAccessor(false);
		CommandStackHandler noModelHandler = new CommandStackHandler(
				noModel, formatter, new CommandRegistry());

		McpSchema.CallToolResult result = noModelHandler.handleRedo(null,
				McpSchema.CallToolRequest.builder().name("redo")
						.arguments(Map.of()).build());
		Map<String, Object> parsed = parseResult(result);

		assertTrue("Should be an error", result.isError());
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertEquals("MODEL_NOT_LOADED", error.get("code"));
	}

	// ---- Helper methods ----

	private McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args) {
		McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
				.name(toolName)
				.arguments(args)
				.build();

		return switch (toolName) {
			case "undo" -> handler.handleUndo(null, request);
			case "redo" -> handler.handleRedo(null, request);
			default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
		};
	}

	private Map<String, Object> callAndParse(String toolName, Map<String, Object> args)
			throws Exception {
		McpSchema.CallToolResult result = callTool(toolName, args);
		return parseResult(result);
	}

	private Map<String, Object> parseResult(McpSchema.CallToolResult result) throws Exception {
		String content = ((McpSchema.TextContent) result.content().get(0)).text();
		return objectMapper.readValue(content, new TypeReference<>() {});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getResult(Map<String, Object> envelope) {
		return (Map<String, Object>) envelope.get("result");
	}

	// ---- Test stub ----

	static class StubUndoRedoAccessor extends BaseTestAccessor {

		private UndoRedoResultDto undoResult;
		private UndoRedoResultDto redoResult;
		int lastUndoSteps;
		int lastRedoSteps;
		String lastUndoSession;
		String lastRedoSession;
		ModelAccessException undoRefusal;

		StubUndoRedoAccessor() {
			super(true);
		}

		StubUndoRedoAccessor(boolean modelLoaded) {
			super(modelLoaded);
		}

		void setUndoResult(UndoRedoResultDto result) {
			this.undoResult = result;
		}

		void setRedoResult(UndoRedoResultDto result) {
			this.redoResult = result;
		}

		@Override
		public UndoRedoResultDto undo(String sessionId, int steps) {
			lastUndoSession = sessionId;
			lastUndoSteps = steps;
			if (undoRefusal != null) {
				throw undoRefusal;
			}
			if (undoResult != null) {
				return undoResult;
			}
			return new UndoRedoResultDto(steps, 0, Collections.emptyList(), false, false);
		}

		@Override
		public UndoRedoResultDto redo(String sessionId, int steps) {
			lastRedoSession = sessionId;
			lastRedoSteps = steps;
			if (redoResult != null) {
				return redoResult;
			}
			return new UndoRedoResultDto(steps, 0, Collections.emptyList(), false, false);
		}
	}
}
