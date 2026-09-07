package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManager;
import io.modelcontextprotocol.util.McpUriTemplateManager;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.registry.ResourceRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Unit tests for {@link ResourceHandler}.
 *
 * <p>Uses a {@link TestableResourceHandler} subclass that overrides
 * {@code loadResourceFile} to provide test content without depending
 * on actual classpath resources.</p>
 */
public class ResourceHandlerTest {

	private ResourceRegistry registry;

	@Before
	public void setUp() {
		registry = new ResourceRegistry();
	}

	// ---- Registration Tests ----

	@Test
	public void shouldRegisterAllResources_whenFilesExist() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		// 8 prompts/reference resources + the 6-file viewpoint recipe library (recipes/index + 5 family pages); count bumped.
		assertEquals(14, registry.getResourceCount());
	}

	@Test
	public void shouldRegisterZeroResources_whenFilesNotFound() {
		TestableResourceHandler handler = new TestableResourceHandler(false);
		handler.registerResources(registry);

		assertEquals(0, registry.getResourceCount());
	}

	@Test
	public void shouldCacheAllLoadedResources() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		// 8 prompts/reference + 6 recipe-library resources; see shouldRegisterAllResources_whenFilesExist.
		assertEquals(14, handler.getCachedResourceCount());
	}

	@Test
	public void shouldCacheZeroResources_whenFilesNotFound() {
		TestableResourceHandler handler = new TestableResourceHandler(false);
		handler.registerResources(registry);

		assertEquals(0, handler.getCachedResourceCount());
	}

	// ---- Resource URI Tests ----

	@Test
	public void shouldUseArchimateUriScheme() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		List<McpServerFeatures.SyncResourceSpecification> specs =
				registry.getResourceSpecifications();
		for (McpServerFeatures.SyncResourceSpecification spec : specs) {
			assertTrue("URI should start with archimate://: " + spec.resource().uri(),
					spec.resource().uri().startsWith("archimate://"));
		}
	}

	@Test
	public void shouldRegisterModelExplorationGuide() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		assertResourceRegistered("archimate://prompts/model-exploration-guide");
	}

	@Test
	public void shouldRegisterExploreDependencies() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		assertResourceRegistered("archimate://prompts/explore-dependencies");
	}

	@Test
	public void shouldRegisterLandscapeOverview() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		assertResourceRegistered("archimate://prompts/landscape-overview");
	}

	@Test
	public void shouldRegisterArchimateLayersReference() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		assertResourceRegistered("archimate://reference/archimate-layers");
	}

	@Test
	public void shouldRegisterArchimateRelationshipsReference() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		assertResourceRegistered("archimate://reference/archimate-relationships");
	}

	@Test
	public void shouldRegisterArchimateSpecializationsReference() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		assertResourceRegistered("archimate://reference/archimate-specializations");

		McpServerFeatures.SyncResourceSpecification spec = registry.getResourceSpecifications().stream()
				.filter(s -> "archimate://reference/archimate-specializations".equals(s.resource().uri()))
				.findFirst().orElseThrow();
		assertEquals("ArchiMate Specializations Reference", spec.resource().name());
		assertTrue("Description should mention specialization",
				spec.resource().description().toLowerCase().contains("specialization"));
	}

	@Test
	public void shouldRegisterViewPatternsResource() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		assertResourceRegistered("archimate://reference/archimate-view-patterns");

		McpServerFeatures.SyncResourceSpecification spec = registry.getResourceSpecifications().stream()
				.filter(s -> "archimate://reference/archimate-view-patterns".equals(s.resource().uri()))
				.findFirst().orElseThrow();
		assertEquals("ArchiMate View Patterns", spec.resource().name());
		assertTrue("Description should mention layout or viewpoint",
				spec.resource().description().contains("layout")
						|| spec.resource().description().contains("viewpoint"));
	}

	// ---- Resource Metadata Tests ----

	@Test
	public void shouldSetMarkdownMimeType() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		List<McpServerFeatures.SyncResourceSpecification> specs =
				registry.getResourceSpecifications();
		for (McpServerFeatures.SyncResourceSpecification spec : specs) {
			assertEquals("text/markdown", spec.resource().mimeType());
		}
	}

	@Test
	public void shouldSetNonEmptyName() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		List<McpServerFeatures.SyncResourceSpecification> specs =
				registry.getResourceSpecifications();
		for (McpServerFeatures.SyncResourceSpecification spec : specs) {
			assertNotNull("Name should not be null", spec.resource().name());
			assertFalse("Name should not be empty", spec.resource().name().isEmpty());
		}
	}

	@Test
	public void shouldSetNonEmptyDescription() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		List<McpServerFeatures.SyncResourceSpecification> specs =
				registry.getResourceSpecifications();
		for (McpServerFeatures.SyncResourceSpecification spec : specs) {
			assertNotNull("Description should not be null", spec.resource().description());
			assertFalse("Description should not be empty", spec.resource().description().isEmpty());
		}
	}

	// ---- Read Handler Tests ----

	@Test
	public void shouldReturnContent_whenValidUriRequested() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		McpSchema.ReadResourceRequest request = new McpSchema.ReadResourceRequest(
				"archimate://prompts/model-exploration-guide");
		McpSchema.ReadResourceResult result = handler.handleReadResource(null, request);

		assertNotNull(result);
		assertNotNull(result.contents());
		assertEquals(1, result.contents().size());
		assertTrue(result.contents().get(0) instanceof McpSchema.TextResourceContents);

		McpSchema.TextResourceContents textContent =
				(McpSchema.TextResourceContents) result.contents().get(0);
		assertEquals("archimate://prompts/model-exploration-guide", textContent.uri());
		assertEquals("text/markdown", textContent.mimeType());
		assertNotNull(textContent.text());
		assertFalse(textContent.text().isEmpty());
	}

	@Test
	public void shouldReturnEmptyResult_whenUnknownUriRequested() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		McpSchema.ReadResourceRequest request = new McpSchema.ReadResourceRequest(
				"archimate://nonexistent/resource");
		McpSchema.ReadResourceResult result = handler.handleReadResource(null, request);

		assertNotNull(result);
		assertNotNull(result.contents());
		assertTrue(result.contents().isEmpty());
	}

	@Test
	public void shouldReturnEmptyResult_whenNoResourcesLoaded() {
		TestableResourceHandler handler = new TestableResourceHandler(false);
		handler.registerResources(registry);

		McpSchema.ReadResourceRequest request = new McpSchema.ReadResourceRequest(
				"archimate://prompts/model-exploration-guide");
		McpSchema.ReadResourceResult result = handler.handleReadResource(null, request);

		assertNotNull(result);
		assertTrue(result.contents().isEmpty());
	}

	// ---- File Loading Tests ----

	@Test
	public void shouldReturnNull_whenFileNotFound() {
		ResourceHandler handler = new ResourceHandler();
		String result = handler.loadResourceFile("nonexistent/file.md");
		assertNull(result);
	}

	@Test
	public void shouldReturnNull_whenPathIsEmpty() {
		ResourceHandler handler = new ResourceHandler();
		String result = handler.loadResourceFile("");
		assertNull(result);
	}

	// ---- Resource Content Verification ----

	@Test
	public void shouldLoadActualResourceFiles_whenOnClasspath() {
		ResourceHandler handler = new ResourceHandler();
		handler.registerResources(registry);

		// Skip if resource files are not on the classpath (outside PDE environment)
		Assume.assumeTrue("Resource files only available in PDE environment",
				handler.getCachedResourceCount() > 0);

		// 8 prompts/reference + 6 recipe-library resources (recipes/index + 5 family pages); count bumped.
		assertEquals("All 14 resource files should load in PDE", 14, handler.getCachedResourceCount());
		assertEquals(14, registry.getResourceCount());

		// Verify model-exploration-guide content
		McpSchema.ReadResourceRequest request = new McpSchema.ReadResourceRequest(
				"archimate://prompts/model-exploration-guide");
		McpSchema.ReadResourceResult result = handler.handleReadResource(null, request);
		McpSchema.TextResourceContents content =
				(McpSchema.TextResourceContents) result.contents().get(0);
		assertTrue("Should contain tool pipeline section",
				content.text().contains("get-model-info"));
		assertTrue("Should contain scale heuristics",
				content.text().contains("Small Model"));

		// Verify archimate-view-patterns content
		McpSchema.ReadResourceRequest viewPatternsRequest = new McpSchema.ReadResourceRequest(
				"archimate://reference/archimate-view-patterns");
		McpSchema.ReadResourceResult viewPatternsResult = handler.handleReadResource(null, viewPatternsRequest);
		McpSchema.TextResourceContents viewPatternsContent =
				(McpSchema.TextResourceContents) viewPatternsResult.contents().get(0);
		assertTrue("Should contain layout algorithm reference",
				viewPatternsContent.text().contains("auto-layout-and-route"));
		assertTrue("Should contain viewpoint patterns section",
				viewPatternsContent.text().contains("Common Viewpoint Patterns"));
		assertTrue("Should contain view composition workflow section",
				viewPatternsContent.text().contains("View Composition Workflow"));
		assertTrue("Should contain connection routing section",
				viewPatternsContent.text().contains("Manhattan"));
		assertTrue("Should contain group composition section",
				viewPatternsContent.text().contains("Group Composition"));
		assertTrue("Should contain algorithm reference section",
				viewPatternsContent.text().contains("Algorithm Reference"));

		// The resource must not merely forbid connection lineStyle — it must name the idiom that
		// works. An agent told only "no" substitutes something arbitrary; a real run substituted
		// lineColor + lineWidth unaided, which is exactly what this entry now prescribes.
		assertTrue("Should carry the connection line style entry",
				viewPatternsContent.text().contains("Connection line style"));
		assertTrue("Connection line style entry must name lineColor as the supported idiom",
				viewPatternsContent.text().contains("`lineColor` + `lineWidth`"));
		assertTrue("Connection line style entry must state that lineStyle is rejected, not ignored",
				viewPatternsContent.text().contains("INVALID_PARAMETER"));
		// Scoped to the connection claim: "silently ignored" is still correct prose elsewhere in
		// this resource (borderType on non-note objects), so a bare substring ban would go red on
		// a true sentence about a different field.
		assertFalse("The resource must not still claim connection lineStyle is silently ignored",
				viewPatternsContent.text().lines().anyMatch(line ->
						line.contains("silently ignored")
								&& line.contains("lineStyle")));

		// Regression pins: control-loop stop-signal phrase in Pre-Layout Planning §2 Spacing Heuristics.
		// (Updated 2026-05-25: the stop-signal text was rewritten from the row-735 narrow-numeric
		// "more than three spacing tool calls -> stop at fair" phrasing to the control-loop
		// "let the control loop decide / density_floor_reflow_required" phrasing by the density-aware
		// control-loop work; this pin tracks the current resource text.)
		assertTrue("Should contain control-loop stop signal sentinel",
				viewPatternsContent.text().contains("Stop signal"));
		assertEquals("Stop signal sentinel must appear exactly once",
				1, viewPatternsContent.text().split("Stop signal", -1).length - 1);
		assertTrue("Should defer termination to the self-terminating control loop",
				viewPatternsContent.text().contains("let the control loop decide")
						&& viewPatternsContent.text().contains("density_floor_reflow_required"));
		assertTrue("Should redirect to composed apply-spacing-recommendations(scope=both)",
				viewPatternsContent.text().contains("apply-spacing-recommendations(scope=both)"));
		assertTrue("Should name +80px element knee-clamp constant",
				viewPatternsContent.text().contains("+80px"));
		assertTrue("Should name +100px inter-group knee-clamp constant",
				viewPatternsContent.text().contains("+100px"));
	}

	// ---- Resource Template Tests ----
	//
	// Some MCP clients implement only the template half of the resource capability. The templates
	// below are the parameterised form of the SAME URIs the static registrations already publish —
	// no URI is invented, renamed, or given a placeholder parameter, because the guidance pointers
	// compiled into tool descriptions and runtime strings name the concrete two-segment form.

	@Test
	public void shouldRegisterOneTemplatePerNamespace_whenResourcesAreRegistered() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		assertEquals(3, registry.getResourceTemplateCount());

		List<String> templates = registry.getResourceTemplateSpecifications().stream()
				.map(spec -> spec.resourceTemplate().uriTemplate())
				.sorted()
				.collect(Collectors.toList());
		assertEquals(List.of(
				"archimate://prompts/{name}",
				"archimate://recipes/{name}",
				"archimate://reference/{name}"), templates);
	}

	/**
	 * The load-bearing assertion of the template arm: every URI that already ships must be matched
	 * by a registered template, per URI and not by spot-check. If this fails, a template-only client
	 * still cannot reach the guidance the pointers name.
	 */
	@Test
	public void shouldMatchEveryStaticResourceUri_withSomeRegisteredTemplate() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		List<McpUriTemplateManager> matchers = registry.getResourceTemplateSpecifications().stream()
				.map(spec -> (McpUriTemplateManager)
						new DefaultMcpUriTemplateManager(spec.resourceTemplate().uriTemplate()))
				.collect(Collectors.toList());
		assertFalse("no templates registered", matchers.isEmpty());

		for (McpServerFeatures.SyncResourceSpecification spec : registry.getResourceSpecifications()) {
			String uri = spec.resource().uri();
			boolean matched = matchers.stream().anyMatch(m -> m.matches(uri));
			assertTrue("no registered template expands to the shipped URI " + uri, matched);
		}
	}

	@Test
	public void shouldKeepEveryStaticResource_whenTemplatesAreAlsoRegistered() {
		TestableResourceHandler handler = new TestableResourceHandler(true);
		handler.registerResources(registry);

		assertEquals("templates must be additive, never a replacement",
				14, registry.getResourceCount());
		for (McpServerFeatures.SyncResourceSpecification spec : registry.getResourceSpecifications()) {
			McpSchema.ReadResourceResult read = handler.handleReadResource(
					null, new McpSchema.ReadResourceRequest(spec.resource().uri()));
			assertEquals("static read must still serve a body for " + spec.resource().uri(),
					1, read.contents().size());
		}
	}

	// ---- get-guidance Tool Tests ----
	//
	// Tools are the one MCP surface every client implements, so the guidance bodies are reachable
	// through a tool as well as through the resource capability. These assert through the REGISTERED
	// TOOL — a handler method returning the right object proves the value was computed, not sent.

	@Test
	public void shouldRegisterGetGuidance_throughTheProductionRegistrar() {
		CommandRegistry commands = productionRegistry();

		assertTrue("get-guidance must register through HandlerRegistrar, so the contract tests "
				+ "that enumerate through it can see and classify it",
				toolNames(commands).contains("get-guidance"));
	}

	@Test
	public void shouldDeclareUriAsOptional_soTheToolCanAlsoListTheCatalogue() {
		McpSchema.Tool tool = toolSpec(productionRegistry(), "get-guidance").tool();

		Map<String, Object> properties = tool.inputSchema().properties();
		assertTrue("schema must accept a uri argument", properties.containsKey("uri"));
		List<String> required = tool.inputSchema().required();
		assertTrue("uri must be OPTIONAL — omitting it lists the catalogue",
				required == null || !required.contains("uri"));
	}

	@Test
	public void shouldReturnTheResourceBody_whenCalledThroughTheRegisteredTool() throws Exception {
		assumeGuidanceContentAvailable();
		CommandRegistry commands = productionRegistry();

		for (String uri : List.of(
				"archimate://prompts/routing-preconditions-checklist",
				"archimate://reference/archimate-layers",
				"archimate://recipes/index")) {
			Map<String, Object> envelope = invoke(commands, Map.of("uri", uri));
			Map<?, ?> result = (Map<?, ?>) envelope.get("result");

			assertNotNull("envelope must carry a result for " + uri, result);
			assertEquals(uri, result.get("uri"));
			assertEquals("text/markdown", result.get("mimeType"));
			String content = (String) result.get("content");
			assertNotNull("no content on the wire for " + uri, content);
			assertFalse("empty content on the wire for " + uri, content.isBlank());
		}
	}

	/**
	 * The instance that serves the tool is NOT the instance that registered the MCP resources —
	 * {@code HandlerRegistrar} constructs its own. If the content cache only ever filled as a side
	 * effect of {@code registerResources}, this call would answer "not found" for all fourteen URIs
	 * while every unit test that registers resources itself carried on passing.
	 */
	@Test
	public void shouldServeContent_evenThoughTheToolInstanceNeverRegisteredResources() throws Exception {
		assumeGuidanceContentAvailable();
		CommandRegistry commands = productionRegistry();

		Map<String, Object> envelope = invoke(commands, Map.of());
		Map<?, ?> result = (Map<?, ?>) envelope.get("result");
		List<?> resources = (List<?>) result.get("resources");

		assertEquals("the tool-side handler must load its own content, not depend on the "
				+ "resource-side instance having filled a cache it cannot see",
				14, resources.size());
	}

	@Test
	public void shouldListTheCatalogueWithoutBodies_whenNoUriIsGiven() throws Exception {
		assumeGuidanceContentAvailable();
		CommandRegistry commands = productionRegistry();

		Map<String, Object> envelope = invoke(commands, Map.of());
		Map<?, ?> result = (Map<?, ?>) envelope.get("result");

		assertEquals(14, ((Number) result.get("count")).intValue());
		List<?> resources = (List<?>) result.get("resources");
		assertEquals(14, resources.size());
		for (Object entry : resources) {
			Map<?, ?> row = (Map<?, ?>) entry;
			assertTrue("catalogue row must name its uri", ((String) row.get("uri")).startsWith("archimate://"));
			assertNotNull("catalogue row must carry a name", row.get("name"));
			assertNotNull("catalogue row must carry a description", row.get("description"));
			assertFalse("the catalogue is an index, not a payload — bodies must not be inlined",
					row.containsKey("content"));
		}
	}

	@Test
	public void shouldReturnAStructuredError_whenTheRequestedUriIsUnknown() throws Exception {
		assumeGuidanceContentAvailable();
		CommandRegistry commands = productionRegistry();

		Map<String, Object> envelope = invoke(commands, Map.of("uri", "archimate://reference/does-not-exist"));

		assertNull("an unknown URI is an error, not an empty success", envelope.get("result"));
		Map<?, ?> error = (Map<?, ?>) envelope.get("error");
		assertNotNull("must return a structured error", error);
		assertEquals("INVALID_PARAMETER", error.get("code"));
		assertTrue("the error must name the URI that failed",
				String.valueOf(error.get("message")).contains("archimate://reference/does-not-exist"));
		assertNotNull("the error must tell the agent how to recover",
				error.get("suggestedCorrection"));
	}

	/**
	 * A `uri` of the wrong JSON type is an error, not an omission. Falling through to the catalogue
	 * would hand a caller that mis-serialized the argument a different, valid-looking response, and
	 * leave it unable to tell "I forgot uri" from "my uri never arrived".
	 */
	@Test
	public void shouldRejectAUriOfTheWrongType_ratherThanSilentlyListingTheCatalogue() throws Exception {
		assumeGuidanceContentAvailable();
		CommandRegistry commands = productionRegistry();

		for (Object wrong : List.of(42, true, List.of("archimate://recipes/index"))) {
			Map<String, Object> envelope = invoke(commands, Map.of("uri", wrong));

			assertNull("a " + wrong.getClass().getSimpleName() + " uri must not return a result",
					envelope.get("result"));
			Map<?, ?> error = (Map<?, ?>) envelope.get("error");
			assertNotNull("a " + wrong.getClass().getSimpleName() + " uri must return an error", error);
			assertEquals("INVALID_PARAMETER", error.get("code"));
		}
	}

	// ---- Helper ----

	/** A registry populated exactly the way the running server populates it. */
	private static CommandRegistry productionRegistry() {
		CommandRegistry commands = new CommandRegistry();
		HandlerRegistrar.registerAll(
				new BaseTestAccessor(),
				new ResponseFormatter(),
				commands,
				new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS));
		return commands;
	}

	private static Set<String> toolNames(CommandRegistry commands) {
		Set<String> names = new LinkedHashSet<>();
		commands.getToolSpecifications().forEach(spec -> names.add(spec.tool().name()));
		return names;
	}

	private static McpServerFeatures.SyncToolSpecification toolSpec(CommandRegistry commands, String name) {
		return commands.getToolSpecifications().stream()
				.filter(s -> s.tool().name().equals(name))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Tool not registered: " + name));
	}

	private static Map<String, Object> invoke(CommandRegistry commands, Map<String, Object> args)
			throws Exception {
		McpSchema.CallToolResult result = toolSpec(commands, "get-guidance").callHandler()
				.apply(null, new McpSchema.CallToolRequest("get-guidance", args));
		McpSchema.TextContent text = (McpSchema.TextContent) result.content().get(0);
		return new ObjectMapper().readValue(text.text(), new TypeReference<Map<String, Object>>() {});
	}

	/**
	 * The resource bodies are classpath files. Outside a packaged run they are absent, and the
	 * assertions below would be measuring the harness rather than the code.
	 *
	 * <p>The probe reads the classpath DIRECTLY rather than asking the tool, so that a broken tool
	 * reports as a failure. A guard that asked the subject under test whether it worked would
	 * convert every real defect into a green skip.</p>
	 */
	private static void assumeGuidanceContentAvailable() {
		Assume.assumeTrue("Resource files only available when packaged on the classpath",
				new ResourceHandler().getCachedResourceCount() > 0);
	}

	private void assertResourceRegistered(String uri) {
		List<McpServerFeatures.SyncResourceSpecification> specs =
				registry.getResourceSpecifications();
		boolean found = specs.stream()
				.anyMatch(spec -> uri.equals(spec.resource().uri()));
		assertTrue("Resource should be registered: " + uri, found);
	}

	/**
	 * Test subclass that overrides file loading to provide deterministic content
	 * without depending on classpath resources.
	 */
	private static class TestableResourceHandler extends ResourceHandler {
		private final boolean filesExist;

		TestableResourceHandler(boolean filesExist) {
			this.filesExist = filesExist;
		}

		@Override
		String loadResourceFile(String filePath) {
			if (!filesExist) {
				return null;
			}
			return "# Test Content\n\nTest content for " + filePath + "\n";
		}
	}
}
