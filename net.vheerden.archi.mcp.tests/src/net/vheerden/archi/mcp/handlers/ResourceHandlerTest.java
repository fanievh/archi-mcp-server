package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.registry.ResourceRegistry;

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

		assertEquals(7, registry.getResourceCount());
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

		assertEquals(7, handler.getCachedResourceCount());
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

		assertEquals("All 7 resource files should load in PDE", 7, handler.getCachedResourceCount());
		assertEquals(7, registry.getResourceCount());

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
				viewPatternsContent.text().contains("compute-layout"));
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
	}

	// ---- Helper ----

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
