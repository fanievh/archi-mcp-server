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

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.UndoRedoResultDto;

/**
 * Tests for {@link CommandStackHandler} undo/redo tools (Story 11-1).
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
		public UndoRedoResultDto undo(int steps) {
			lastUndoSteps = steps;
			if (undoResult != null) {
				return undoResult;
			}
			return new UndoRedoResultDto(steps, 0, Collections.emptyList(), false, false);
		}

		@Override
		public UndoRedoResultDto redo(int steps) {
			lastRedoSteps = steps;
			if (redoResult != null) {
				return redoResult;
			}
			return new UndoRedoResultDto(steps, 0, Collections.emptyList(), false, false);
		}
	}
}
