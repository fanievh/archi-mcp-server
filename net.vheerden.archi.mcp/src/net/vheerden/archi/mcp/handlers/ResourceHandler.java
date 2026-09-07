package net.vheerden.archi.mcp.handlers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.registry.ResourceRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * Loads ArchiMate reference materials and workflow templates from the
 * {@code resources/} directory and publishes them over both delivery routes.
 *
 * <p>Resources are static markdown files that provide LLMs with ArchiMate
 * domain knowledge and exploration strategies. Content is loaded once on first
 * use and cached in memory.</p>
 *
 * <p><strong>Two routes, one catalogue.</strong> The same bodies are published as MCP resources
 * (static URIs plus the equivalent URI templates) and through the {@code get-guidance} tool.
 * That redundancy is deliberate: the resource capability is the natural home for reference
 * material, but not every MCP client exposes resource reads to the model, and this server's tool
 * descriptions and runtime messages point the agent at {@code archimate://} URIs from inside
 * workflows. Tools are the one surface every client implements, so the tool route is what makes
 * those pointers followable everywhere.</p>
 *
 * <p>This handler has no dependency on {@link net.vheerden.archi.mcp.model.ArchiModelAccessor}
 * and touches no model type — the content is markdown on the classpath, not model-derived JSON.
 * It takes a {@link net.vheerden.archi.mcp.response.ResponseFormatter} only to build the standard
 * envelope for its tool, and takes it per call rather than as a field.</p>
 */
public class ResourceHandler {

	private static final Logger logger = LoggerFactory.getLogger(ResourceHandler.class);

	private static final String RESOURCE_BASE_PATH = "resources/";
	private static final String MIME_TYPE = "text/markdown";
	private static final String URI_PREFIX = "archimate://";
	private static final String GUIDANCE_TOOL = "get-guidance";

	private static final Map<String, ResourceDefinition> RESOURCE_DEFINITIONS = Map.ofEntries(
			Map.entry("prompts/model-exploration-guide", new ResourceDefinition(
					"Model Exploration Guide",
					"Strategy guide for LLMs on how to efficiently search and traverse ArchiMate models",
					"prompts/model-exploration-guide.md")),
			Map.entry("prompts/explore-dependencies", new ResourceDefinition(
					"Explore Dependencies",
					"Workflow template for systematic dependency analysis of ArchiMate elements",
					"prompts/explore-dependencies.md")),
			Map.entry("prompts/landscape-overview", new ResourceDefinition(
					"Landscape Overview",
					"Workflow template for generating architecture landscape summaries",
					"prompts/landscape-overview.md")),
			Map.entry("prompts/routing-preconditions-checklist", new ResourceDefinition(
					"Routing Preconditions Checklist",
					"Three-precondition checklist (hub sizing / inter-element spacing / inter-group arrangement) to verify BEFORE calling auto-route-connections or auto-layout-and-route on any non-trivial view.",
					"prompts/routing-preconditions-checklist.md")),
			Map.entry("reference/archimate-layers", new ResourceDefinition(
					"ArchiMate Layers Reference",
					"Comprehensive mapping of ArchiMate layers to element types with descriptions, plus a concept-to-element-type decision aid (Component vs Service, Process vs Function, Node vs Component, Actor vs Role)",
					"reference/archimate-layers.md")),
			Map.entry("reference/archimate-relationships", new ResourceDefinition(
					"ArchiMate Relationships Reference",
					"All ArchiMate relationship types with valid source/target combinations and usage guidance",
					"reference/archimate-relationships.md")),
			Map.entry("reference/archimate-specializations", new ResourceDefinition(
					"ArchiMate Specializations Reference",
					"Specialization (IS-A subtype) vocabulary: when to use specializations vs properties, common patterns per layer, and the discovery/create/audit/delete tool pipeline",
					"reference/archimate-specializations.md")),
			Map.entry("reference/archimate-view-patterns", new ResourceDefinition(
					"ArchiMate View Patterns",
					"Curated viewpoint patterns, layout algorithm guidance, and diagramming best practices for composing ArchiMate views",
					"reference/archimate-view-patterns.md")),
			Map.entry("recipes/index", new ResourceDefinition(
					"ArchiMate Viewpoint Recipes Index",
					"Selector for the viewpoint recipe library: states the invariant build sequence once, routes conventional viewpoints to the principles section, and points non-conventional viewpoints to their full recipe page. Fetch this first.",
					"recipes/index.md")),
			Map.entry("recipes/application-integration", new ResourceDefinition(
					"Recipe — Application Integration",
					"Hub-and-spoke recipe for Application Cooperation / Integration views: element + relationship subset, the hub-and-spoke topology block, and the per-family deltas vs the invariant build sequence.",
					"recipes/application-integration.md")),
			Map.entry("recipes/behaviour-process-flow", new ResourceDefinition(
					"Recipe — Behaviour & Process Flow",
					"Swimlane recipe for Business Process Cooperation views plus the Service Design / Customer Journey band layout.",
					"recipes/behaviour-process-flow.md")),
			Map.entry("recipes/motivation", new ResourceDefinition(
					"Recipe — Motivation",
					"Directed influence-chain recipe for Motivation views: stakeholder -> driver -> assessment -> goal -> requirement banded topology.",
					"recipes/motivation.md")),
			Map.entry("recipes/technology-deployment", new ResourceDefinition(
					"Recipe — Technology / Deployment",
					"Nested-deployment recipe: infrastructure nodes as containers with deployed software/artifacts nested inside, wired by network paths.",
					"recipes/technology-deployment.md")),
			Map.entry("recipes/roadmap-migration", new ResourceDefinition(
					"Recipe — Roadmap / Migration",
					"Left-to-right plateau-timeline recipe for Implementation & Migration views: plateaus and gap elements on a time axis with work packages below.",
					"recipes/roadmap-migration.md")));

	/** What each URI namespace holds, for the resource templates that advertise it. */
	private static final Map<String, String> TEMPLATE_DESCRIPTIONS = Map.of(
			"prompts", "Workflow guides and preflight checklists for driving this server: model "
					+ "exploration strategy, dependency analysis, landscape summaries, and the "
					+ "routing preconditions to verify before laying out a non-trivial view.",
			"reference", "ArchiMate language reference: layers and element types, relationship "
					+ "validity, specialization vocabulary, and view/layout patterns.",
			"recipes", "Viewpoint recipe library: fetch the index first, then the recipe for the "
					+ "viewpoint family you are building.");

	/**
	 * Loaded resource bodies by URI.
	 *
	 * <p>Concurrent because the guidance tool's call handler reads this map from Jetty worker
	 * threads, several of which can be in flight at once.</p>
	 */
	private final Map<String, String> cachedContent = new ConcurrentHashMap<>();

	/**
	 * Set <strong>only after</strong> {@link #cachedContent} is fully populated, and volatile, so
	 * that a thread taking the fast path in {@link #ensureContentLoaded()} is guaranteed to see
	 * every entry the loading thread wrote.
	 */
	private volatile boolean contentLoaded;

	/**
	 * Loads every resource body into {@link #cachedContent} exactly once.
	 *
	 * <p><strong>Why this is lazy rather than done in the constructor or only in
	 * {@link #registerResources}:</strong> the content cache is per-instance, and this class is
	 * constructed in two places — once to publish the MCP resources and once to publish the
	 * guidance tool. If loading happened only inside {@code registerResources}, the tool-side
	 * instance would serve an empty cache and answer every lookup with "not found". Loading on
	 * first use makes any instance self-sufficient. It cannot move into the constructor because
	 * {@link #loadResourceFile} is overridden by tests, and a constructor calling an overridable
	 * method would run before the subclass is initialised.</p>
	 *
	 * <p><strong>Thread safety is load-bearing here, not defensive.</strong> The tool-side instance
	 * loads on its first {@code get-guidance} call, which arrives on a Jetty worker thread, and
	 * further calls can arrive on other workers while that load is still doing classpath I/O. The
	 * guard flag is therefore written <em>after</em> the map is fully populated, never before: a
	 * flag set first would let a second worker take the fast path and read an empty map, and answer
	 * that a resource the server does ship cannot be found.</p>
	 */
	private void ensureContentLoaded() {
		if (contentLoaded) {
			return;
		}
		synchronized (this) {
			if (contentLoaded) {
				return;
			}

			logger.info("Loading MCP resource files...");
			for (Map.Entry<String, ResourceDefinition> entry : RESOURCE_DEFINITIONS.entrySet()) {
				ResourceDefinition def = entry.getValue();
				String content = loadResourceFile(def.filePath());
				if (content != null) {
					cachedContent.put(URI_PREFIX + entry.getKey(), content);
				} else {
					logger.warn("Resource file not found, skipping: {}", def.filePath());
				}
			}
			logger.info("Loaded {} MCP resource bodies", cachedContent.size());

			contentLoaded = true;
		}
	}

	/**
	 * Loads all resource files and registers them with the given registry.
	 *
	 * <p>Each resource file is loaded from the classpath, cached in memory,
	 * and registered as a {@link SyncResourceSpecification}. Files that cannot
	 * be found or loaded are skipped with a warning.</p>
	 *
	 * @param registry the resource registry to register with
	 */
	public void registerResources(ResourceRegistry registry) {
		ensureContentLoaded();
		int registered = 0;

		for (Map.Entry<String, ResourceDefinition> entry : RESOURCE_DEFINITIONS.entrySet()) {
			String uri = URI_PREFIX + entry.getKey();
			ResourceDefinition def = entry.getValue();

			if (cachedContent.containsKey(uri)) {
				McpSchema.Resource resource = McpSchema.Resource.builder()
						.uri(uri)
						.name(def.name())
						.description(def.description())
						.mimeType(MIME_TYPE)
						.build();

				SyncResourceSpecification spec = new SyncResourceSpecification(
						resource, this::handleReadResource);
				registry.registerResource(spec);
				registered++;
			}
		}

		logger.info("Registered {} MCP resources", registered);

		registerResourceTemplates(registry);
	}

	/**
	 * Registers the parameterised form of the resource URIs, one template per namespace.
	 *
	 * <p>Some MCP clients implement {@code resources/templates/list} but never expose a plain
	 * static-resource read to the model. A template gives those clients a route to the same
	 * bodies.</p>
	 *
	 * <p><strong>The templates expand to the URIs that already ship — no URI is invented.</strong>
	 * Every resource URI is exactly two segments ({@code archimate://namespace/name}), and a URI
	 * template variable matches a single path segment, so {@code archimate://prompts/{name}}
	 * matches {@code archimate://prompts/routing-preconditions-checklist} verbatim. Adding a
	 * placeholder parameter to make a "new" templated URI would be actively harmful: the guidance
	 * pointers compiled into tool descriptions and runtime messages name the concrete two-segment
	 * form, and a template that does not match them would leave every one of those unreachable.</p>
	 *
	 * <p>Only namespaces with content actually loaded get a template, so the advertised surface
	 * never promises more than the server can serve.</p>
	 *
	 * @param registry the resource registry to register with
	 */
	void registerResourceTemplates(ResourceRegistry registry) {
		Map<String, String> namespaces = new TreeMap<>();
		for (String uri : cachedContent.keySet()) {
			String path = uri.substring(URI_PREFIX.length());
			int slash = path.indexOf('/');
			if (slash > 0) {
				String namespace = path.substring(0, slash);
				namespaces.putIfAbsent(namespace, TEMPLATE_DESCRIPTIONS.getOrDefault(namespace,
						"Guidance resources under the " + namespace + " namespace."));
			}
		}

		for (Map.Entry<String, String> namespace : namespaces.entrySet()) {
			McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
					.uriTemplate(URI_PREFIX + namespace.getKey() + "/{name}")
					.name(namespace.getKey())
					.description(namespace.getValue())
					.mimeType(MIME_TYPE)
					.build();

			registry.registerResourceTemplate(
					new SyncResourceTemplateSpecification(template, this::handleReadResource));
		}

		logger.info("Registered {} MCP resource templates", namespaces.size());
	}

	/**
	 * Registers the guidance-lookup tool with the command registry.
	 *
	 * <p>The reference material is published as MCP resources, but not every client exposes
	 * resource reads to the model. Tools are the one surface every client implements, so the same
	 * bodies are reachable through a tool call as well.</p>
	 *
	 * <p>Dependencies are passed per call rather than held as fields, matching
	 * {@link #registerResources(ResourceRegistry)}. This handler deliberately depends on neither
	 * the model accessor nor any model type — the content is markdown on the classpath.</p>
	 *
	 * @param formatter response formatter for the standard MCP envelope
	 * @param registry  the command registry to register with
	 * @throws NullPointerException if either argument is null
	 */
	public void registerTools(ResponseFormatter formatter, CommandRegistry registry) {
		Objects.requireNonNull(formatter, "formatter must not be null");
		Objects.requireNonNull(registry, "registry must not be null");

		registry.registerTool(buildGetGuidanceSpec(formatter));
	}

	private McpServerFeatures.SyncToolSpecification buildGetGuidanceSpec(ResponseFormatter formatter) {
		Map<String, Object> uriProp = new LinkedHashMap<>();
		uriProp.put("type", "string");
		uriProp.put("description", "The guidance URI to read, e.g. "
				+ "'archimate://prompts/routing-preconditions-checklist'. "
				+ "Omit this to list every available URI with its description instead of reading one.");

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("uri", uriProp);

		McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
				"object", properties, null, null, null, null);

		McpSchema.Tool tool = McpSchema.Tool.builder()
				.name(GUIDANCE_TOOL)
				.description("[Reference] Read the ArchiMate reference material, workflow guides and "
						+ "viewpoint recipes this server ships. Call with no arguments to list every "
						+ "available uri with a one-line description; call with uri to get the full "
						+ "markdown body. "
						+ "The same content is also published over the MCP resource capability under "
						+ "the same archimate:// URIs — use whichever your client supports. When a "
						+ "tool description or a tool response tells you to consult an archimate:// "
						+ "URI, this tool is how you read it if resource access is unavailable. "
						+ "Read-only: touches no model state.")
				.inputSchema(inputSchema)
				.build();

		return McpServerFeatures.SyncToolSpecification.builder()
				.tool(tool)
				.callHandler((exchange, request) -> handleGetGuidance(formatter, request))
				.build();
	}

	private McpSchema.CallToolResult handleGetGuidance(
			ResponseFormatter formatter, McpSchema.CallToolRequest request) {
		ensureContentLoaded();

		Map<String, Object> args = request.arguments();
		Object requested = args == null ? null : args.get("uri");

		// A supplied-but-unusable uri is an error, not an omission. Silently falling through to the
		// catalogue would answer a caller that mis-serialized the argument with a different, valid
		// -looking response, and leave it unable to tell "I forgot uri" from "my uri did not arrive".
		if (requested != null && !(requested instanceof String)) {
			return buildBadUriTypeError(formatter, requested);
		}
		if (requested instanceof String supplied && !supplied.isBlank()) {
			return buildBodyResult(formatter, supplied.trim());
		}
		return buildCatalogueResult(formatter);
	}

	private McpSchema.CallToolResult buildCatalogueResult(ResponseFormatter formatter) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (GuidanceEntry entry : guidanceCatalogue()) {
			if (!cachedContent.containsKey(entry.uri())) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("uri", entry.uri());
			row.put("name", entry.name());
			row.put("description", entry.description());
			rows.add(row);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", rows.size());
		result.put("resources", rows);

		List<String> nextSteps = List.of(
				"Call get-guidance with one of the listed uri values to read its markdown body.",
				"Start with archimate://recipes/index when composing a view — it routes you to the "
						+ "right recipe before you place anything.");

		return HandlerUtils.buildResult(formatter.toJsonString(
				formatter.formatSuccess(result, nextSteps, null, rows.size(), rows.size(), false)),
				false);
	}

	private McpSchema.CallToolResult buildBodyResult(ResponseFormatter formatter, String uri) {
		String content = cachedContent.get(uri);
		if (content == null) {
			return buildUnknownUriError(formatter, uri);
		}

		String key = uri.substring(URI_PREFIX.length());
		ResourceDefinition def = RESOURCE_DEFINITIONS.get(key);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("uri", uri);
		result.put("name", def != null ? def.name() : key);
		result.put("description", def != null ? def.description() : null);
		result.put("mimeType", MIME_TYPE);
		result.put("content", content);

		List<String> nextSteps = List.of(
				"Apply the guidance above before the tool call that prompted you to read it.",
				"Call get-guidance with no arguments to see what else is available.");

		return HandlerUtils.buildResult(formatter.toJsonString(
				formatter.formatSuccess(result, nextSteps, null, 1, 1, false)), false);
	}

	private McpSchema.CallToolResult buildBadUriTypeError(ResponseFormatter formatter, Object supplied) {
		ErrorResponse error = new ErrorResponse(
				ErrorCode.INVALID_PARAMETER,
				"'uri' must be a string, but a " + supplied.getClass().getSimpleName() + " was supplied",
				null,
				"Pass uri as a plain string, e.g. 'archimate://recipes/index', or omit it entirely to "
						+ "list every available uri.",
				null);

		return HandlerUtils.buildResult(formatter.toJsonString(formatter.formatError(error)), true);
	}

	private McpSchema.CallToolResult buildUnknownUriError(ResponseFormatter formatter, String uri) {
		List<String> known = new ArrayList<>();
		for (GuidanceEntry entry : guidanceCatalogue()) {
			if (cachedContent.containsKey(entry.uri())) {
				known.add(entry.uri());
			}
		}

		logger.warn("Guidance not found for URI: {}", uri);

		ErrorResponse error = new ErrorResponse(
				ErrorCode.INVALID_PARAMETER,
				"No guidance is published at " + uri,
				"Known URIs: " + String.join(", ", known),
				"Call get-guidance with no arguments to list every available uri, then retry with "
						+ "one of them.",
				null);

		return HandlerUtils.buildResult(formatter.toJsonString(formatter.formatError(error)), true);
	}

	/**
	 * Returns the number of cached resource contents.
	 * Package-visible for testing.
	 *
	 * @return the number of cached resources
	 */
	int getCachedResourceCount() {
		ensureContentLoaded();
		return cachedContent.size();
	}

	/**
	 * The URIs whose bodies actually loaded — what the server can genuinely serve, as opposed to
	 * what {@link #guidanceCatalogue()} declares.
	 *
	 * <p>The two differ exactly when a declaration outlives its file (deleted, renamed, or its path
	 * mistyped), which is the case a check against the declaration alone cannot see.
	 * Package-visible for testing.</p>
	 *
	 * @return the loaded URIs
	 */
	Set<String> loadedUris() {
		ensureContentLoaded();
		return Set.copyOf(cachedContent.keySet());
	}

	/**
	 * Handles a read-resource request by looking up cached content for the
	 * requested URI.
	 *
	 * @param exchange the MCP server exchange
	 * @param request  the read resource request containing the URI
	 * @return the resource content, or an empty result for unknown URIs
	 */
	McpSchema.ReadResourceResult handleReadResource(
			McpSyncServerExchange exchange, McpSchema.ReadResourceRequest request) {
		logger.debug("Serving resource content for URI: {}", request.uri());

		String content = cachedContent.get(request.uri());
		if (content == null) {
			logger.warn("Resource not found for URI: {}", request.uri());
			return new McpSchema.ReadResourceResult(List.of());
		}

		McpSchema.TextResourceContents textContents =
				new McpSchema.TextResourceContents(request.uri(), MIME_TYPE, content);
		return new McpSchema.ReadResourceResult(List.of(textContents));
	}

	/**
	 * Loads a resource file from the classpath.
	 * Package-visible for testing.
	 *
	 * @param filePath the file path relative to the resources/ directory
	 * @return the file content as a string, or null if not found
	 */
	String loadResourceFile(String filePath) {
		String fullPath = RESOURCE_BASE_PATH + filePath;
		try (InputStream is = getClass().getClassLoader().getResourceAsStream(fullPath)) {
			if (is == null) {
				return null;
			}
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			logger.error("Failed to load resource file: {}", fullPath, e);
			return null;
		}
	}

	/**
	 * The guidance catalogue: every URI this server can serve, with its display name and
	 * description, ordered by URI.
	 *
	 * <p>This is the single declaration of what exists. Both delivery routes read it — the MCP
	 * resource registrations and the guidance tool — and so does the contract test that asserts
	 * every {@code archimate://} pointer in shipped source resolves. Deriving all three from one
	 * map is what stops a pointer, a resource and a tool answer from drifting apart.</p>
	 *
	 * @return the catalogue entries, ordered by URI
	 */
	public static List<GuidanceEntry> guidanceCatalogue() {
		Map<String, GuidanceEntry> ordered = new TreeMap<>();
		for (Map.Entry<String, ResourceDefinition> entry : RESOURCE_DEFINITIONS.entrySet()) {
			String uri = URI_PREFIX + entry.getKey();
			ordered.put(uri, new GuidanceEntry(
					uri, entry.getValue().name(), entry.getValue().description()));
		}
		return List.copyOf(ordered.values());
	}

	/**
	 * Definition of an MCP resource file: maps a display name and description
	 * to a file path under the resources/ directory.
	 */
	record ResourceDefinition(String name, String description, String filePath) {
	}

	/**
	 * One catalogue row: what an agent needs to decide whether to fetch a body, without fetching
	 * fourteen of them first.
	 */
	public record GuidanceEntry(String uri, String name, String description) {
	}
}
