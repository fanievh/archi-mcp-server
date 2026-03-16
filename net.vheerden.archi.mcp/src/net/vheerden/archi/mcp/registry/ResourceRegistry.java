package net.vheerden.archi.mcp.registry;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;

/**
 * Central registry for MCP resource specifications.
 *
 * <p>Resources are static reference materials (ArchiMate guides, relationship
 * references, workflow templates) registered at server startup. The registry
 * provides these specifications to {@link net.vheerden.archi.mcp.server.TransportConfig}
 * for MCP server wiring at build time.</p>
 *
 * <p>Thread-safe: uses {@link CopyOnWriteArrayList} for resource specs accessed
 * from Jetty threads and the UI thread.</p>
 *
 * <p>Parallel to {@link CommandRegistry} — follows the same pattern for
 * resource specifications that CommandRegistry uses for tool specifications.</p>
 */
public class ResourceRegistry {

	private static final Logger logger = LoggerFactory.getLogger(ResourceRegistry.class);

	private final List<McpServerFeatures.SyncResourceSpecification> resourceSpecs = new CopyOnWriteArrayList<>();

	private volatile McpSyncServer streamableMcpServer;
	private volatile McpSyncServer sseMcpServer;

	/**
	 * Registers a resource specification for MCP resource discovery and reading.
	 *
	 * <p>If the MCP servers are already built (post-startup), the resource is also
	 * added at runtime via {@link McpSyncServer#addResource(McpServerFeatures.SyncResourceSpecification)}
	 * and clients are notified of the change.</p>
	 *
	 * @param resourceSpec the resource specification to register
	 * @throws NullPointerException if resourceSpec is null
	 */
	public void registerResource(McpServerFeatures.SyncResourceSpecification resourceSpec) {
		Objects.requireNonNull(resourceSpec, "resourceSpec must not be null");
		resourceSpecs.add(resourceSpec);

		String resourceUri = resourceSpec.resource() != null ? resourceSpec.resource().uri() : "unknown";
		logger.info("Registered MCP resource: {}", resourceUri);

		addResourceToRunningServers(resourceSpec);
	}

	/**
	 * Returns an unmodifiable view of all registered resource specifications.
	 *
	 * <p>Used by {@link net.vheerden.archi.mcp.server.TransportConfig} at build time
	 * to pass resource specs to the MCP server builders.</p>
	 *
	 * @return unmodifiable list of resource specifications
	 */
	public List<McpServerFeatures.SyncResourceSpecification> getResourceSpecifications() {
		return Collections.unmodifiableList(resourceSpecs);
	}

	/**
	 * Returns the number of registered resources.
	 *
	 * @return resource count
	 */
	public int getResourceCount() {
		return resourceSpecs.size();
	}

	/**
	 * Sets the MCP server instances for runtime resource addition.
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
	 * Removes all registered resource specifications.
	 *
	 * <p>Called during server shutdown so that a subsequent restart
	 * does not accumulate duplicate resource registrations.</p>
	 */
	public void clearResources() {
		resourceSpecs.clear();
	}

	private void addResourceToRunningServers(McpServerFeatures.SyncResourceSpecification resourceSpec) {
		McpSyncServer streamable = this.streamableMcpServer;
		McpSyncServer sse = this.sseMcpServer;

		if (streamable != null) {
			try {
				streamable.addResource(resourceSpec);
				streamable.notifyResourcesListChanged();
			} catch (Exception e) {
				logger.warn("Failed to add resource to streamable MCP server at runtime", e);
			}
		}

		if (sse != null) {
			try {
				sse.addResource(resourceSpec);
				sse.notifyResourcesListChanged();
			} catch (Exception e) {
				logger.warn("Failed to add resource to SSE MCP server at runtime", e);
			}
		}
	}
}
