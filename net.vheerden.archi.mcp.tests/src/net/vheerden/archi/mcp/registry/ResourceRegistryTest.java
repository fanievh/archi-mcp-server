package net.vheerden.archi.mcp.registry;

import static org.junit.Assert.*;

import java.util.List;
import java.util.function.BiFunction;

import org.junit.Before;
import org.junit.Test;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Unit tests for {@link ResourceRegistry}.
 */
public class ResourceRegistryTest {

	private ResourceRegistry registry;

	@Before
	public void setUp() {
		registry = new ResourceRegistry();
	}

	@Test
	public void shouldReturnEmptyList_whenNoResourcesRegistered() {
		List<McpServerFeatures.SyncResourceSpecification> specs = registry.getResourceSpecifications();
		assertNotNull(specs);
		assertTrue(specs.isEmpty());
	}

	@Test
	public void shouldReturnZeroCount_whenNoResourcesRegistered() {
		assertEquals(0, registry.getResourceCount());
	}

	@Test
	public void shouldRegisterResource_andReturnIt() {
		McpServerFeatures.SyncResourceSpecification spec = createResourceSpec(
				"archimate://test/resource", "Test Resource", "A test resource");
		registry.registerResource(spec);

		assertEquals(1, registry.getResourceCount());
		List<McpServerFeatures.SyncResourceSpecification> specs = registry.getResourceSpecifications();
		assertEquals(1, specs.size());
		assertEquals("archimate://test/resource", specs.get(0).resource().uri());
	}

	@Test
	public void shouldRegisterMultipleResources() {
		registry.registerResource(createResourceSpec(
				"archimate://test/a", "Resource A", "Description A"));
		registry.registerResource(createResourceSpec(
				"archimate://test/b", "Resource B", "Description B"));
		registry.registerResource(createResourceSpec(
				"archimate://test/c", "Resource C", "Description C"));

		assertEquals(3, registry.getResourceCount());
	}

	@Test
	public void shouldReturnUnmodifiableList() {
		registry.registerResource(createResourceSpec(
				"archimate://test/resource", "Test Resource", "A test resource"));
		List<McpServerFeatures.SyncResourceSpecification> specs = registry.getResourceSpecifications();

		try {
			specs.add(createResourceSpec(
					"archimate://test/another", "Another", "Another resource"));
			fail("Expected UnsupportedOperationException for unmodifiable list");
		} catch (UnsupportedOperationException e) {
			// expected
		}
	}

	@Test(expected = NullPointerException.class)
	public void shouldRejectNullResourceSpec() {
		registry.registerResource(null);
	}

	@Test
	public void shouldClearMcpServersWithoutError() {
		registry.clearMcpServers();
	}

	@Test
	public void shouldSetAndClearMcpServers() {
		registry.setMcpServers(null, null);
		registry.clearMcpServers();

		McpServerFeatures.SyncResourceSpecification spec = createResourceSpec(
				"archimate://test/post-clear", "Post Clear", "Resource after clear");
		registry.registerResource(spec);
		assertEquals(1, registry.getResourceCount());
		assertEquals("archimate://test/post-clear",
				registry.getResourceSpecifications().get(0).resource().uri());
	}

	@Test
	public void shouldClearAllResources() {
		registry.registerResource(createResourceSpec(
				"archimate://test/a", "Resource A", "Description A"));
		registry.registerResource(createResourceSpec(
				"archimate://test/b", "Resource B", "Description B"));
		assertEquals(2, registry.getResourceCount());

		registry.clearResources();

		assertEquals(0, registry.getResourceCount());
		assertTrue(registry.getResourceSpecifications().isEmpty());
	}

	@Test
	public void shouldAllowReRegistration_afterClearResources() {
		registry.registerResource(createResourceSpec(
				"archimate://test/x", "Resource X", "Description X"));
		registry.clearResources();

		registry.registerResource(createResourceSpec(
				"archimate://test/y", "Resource Y", "Description Y"));

		assertEquals(1, registry.getResourceCount());
		assertEquals("archimate://test/y",
				registry.getResourceSpecifications().get(0).resource().uri());
	}

	@Test
	public void shouldPreserveResourceMetadata() {
		McpServerFeatures.SyncResourceSpecification spec = createResourceSpec(
				"archimate://reference/test", "Test Reference", "A test reference document");
		registry.registerResource(spec);

		McpSchema.Resource resource = registry.getResourceSpecifications().get(0).resource();
		assertEquals("archimate://reference/test", resource.uri());
		assertEquals("Test Reference", resource.name());
		assertEquals("A test reference document", resource.description());
		assertEquals("text/markdown", resource.mimeType());
	}

	// ---- Helper ----

	private McpServerFeatures.SyncResourceSpecification createResourceSpec(
			String uri, String name, String description) {
		McpSchema.Resource resource = McpSchema.Resource.builder()
				.uri(uri)
				.name(name)
				.description(description)
				.mimeType("text/markdown")
				.build();

		BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> handler =
				(exchange, request) -> new McpSchema.ReadResourceResult(List.of(
						new McpSchema.TextResourceContents(request.uri(), "text/markdown", "test content")));

		return new McpServerFeatures.SyncResourceSpecification(resource, handler);
	}
}
