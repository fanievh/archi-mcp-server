# Archi MCP Server

An Eclipse PDE plugin for [Archi](https://www.archimatetool.com/) that exposes ArchiMate models through the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/), enabling LLMs to query, analyse, and modify enterprise architecture models through natural language.

## What It Does

Archi MCP Server embeds an HTTP server inside Archi that speaks MCP. Once running, any MCP-compatible LLM client (Claude, Cline, LM Studio, etc.) can connect and interact with the currently open ArchiMate model — asking questions, searching elements, traversing relationships, composing view diagrams, and even creating or modifying model content.

The server provides **65 MCP tools** across querying, searching, creating, layout, routing, assessment, batch operations, images, specializations, and more — plus **7 MCP resources** with ArchiMate reference material and workflow guides for LLMs.

**Example conversation:**

> **You:** "What applications support the Customer Portal capability?"
>
> **LLM:** Searches elements, traverses relationships, and returns: *"7 applications support Customer Portal: OrderService, PaymentGateway, ..."*

## Requirements

| Requirement | Version |
|---|---|
| [Archi](https://www.archimatetool.com/) | 5.7+ |
| Java | 21+ |
| An MCP-compatible LLM client | Claude CLI, Cline, LM Studio, etc. |

**LLM model size recommendation:** 8B+ parameters minimum, 14B+ for reliable tool calling, 70B+ for complex view composition workflows.

## Installation

1. Download the latest `.archiplugin` from the [Releases](../../releases) page (or the `bin/` directory for pre-built artifacts)
2. In Archi: **Help > Manage Plug-ins > Install New...** or copy to Archi's `dropins/` folder
3. Restart Archi

## Getting Started

### 1. Start the Server

Open an ArchiMate model in Archi, then:

**Menu:** `MCP Server > Start MCP Server`

The menu toggles between Start/Stop. The default endpoint is `http://127.0.0.1:18090`.

### 2. Configure Your LLM Client

#### Claude Code (CLI)

In your project's `.mcp.json` or `~/.claude.json`:

```json
{
  "mcpServers": {
    "archi": {
      "type": "http",
      "url": "http://127.0.0.1:18090/mcp"
    }
  }
}
```

Or via the CLI:

```bash
claude mcp add --transport http archi http://127.0.0.1:18090/mcp
```

#### Claude Desktop

Claude Desktop does not natively support Streamable HTTP, so a proxy is required. Install [uv](https://docs.astral.sh/uv/) (a single Rust binary), then add to your `claude_desktop_config.json`:

**Windows:**
```json
{
  "mcpServers": {
    "archi": {
      "command": "C:\\Users\\YOUR_USER\\.local\\bin\\uvx.exe",
      "args": ["mcp-proxy", "--transport", "streamablehttp", "http://127.0.0.1:18090/mcp"]
    }
  }
}
```

**macOS:**
```json
{
  "mcpServers": {
    "archi": {
      "command": "uvx",
      "args": ["mcp-proxy", "--transport", "streamablehttp", "http://127.0.0.1:18090/mcp"]
    }
  }
}
```

#### Cline / Other MCP Clients

Point your MCP client at the Streamable-HTTP endpoint:

```
http://127.0.0.1:18090/mcp
```

SSE transport is also available at `/sse` for older clients.

### 3. Start Querying

With the server running and your LLM client connected, you can ask questions in natural language:

- *"Give me an overview of this architecture model"*
- *"Find all Application Services in the model"*
- *"What does the Order Processing component depend on?"*
- *"Show me the relationships between the CRM and ERP systems"*
- *"Create a new view showing the payment processing flow"*
- *"Auto-layout and route the connections on this view"*

## Configuration

Access via **Window > Preferences > MCP Server** in Archi.

| Setting | Default | Description |
|---|---|---|
| **Port** | `18090` | HTTP(S) server port |
| **Bind Address** | `127.0.0.1` | Network interface (localhost only by default) |
| **Auto-Start** | `false` | Start the server automatically when Archi launches |
| **Log Level** | `INFO` | Logging verbosity: `DEBUG`, `INFO`, `WARN`, `ERROR` |
| **Enable TLS** | `false` | Use HTTPS with TLS encryption |
| **Keystore File** | *(empty)* | Path to PKCS12/JKS keystore (auto-generated if using self-signed) |
| **Keystore Password** | *(empty)* | Password for the keystore file |

### TLS / HTTPS

The server supports optional TLS encryption. To enable:

1. In preferences, check **Enable TLS (HTTPS)**
2. Click **Generate Self-Signed Certificate** to create a keystore automatically
3. Restart the server — the endpoint changes to `https://127.0.0.1:18090`

Clients must trust the self-signed certificate. For `curl` testing, use the `-k` flag. For LLM clients, import the certificate into the client's trust store or the JVM `cacerts`.

## Available Tools

The server exposes **65 MCP tools** organised into functional categories.

### Query & Model Inspection (5 tools)

| Tool | Description |
|---|---|
| `get-model-info` | Model overview — name, purpose, element/relationship/view counts by type and layer, plus specialization count |
| `get-element` | Retrieve element(s) by ID (single via `id` or batch via `ids` array) |
| `get-views` | List views with optional viewpoint type or name filtering |
| `get-view-contents` | View diagram contents — elements, relationships, visual positions, connection routing |
| `get-relationships` | Traverse relationships with configurable depth (0-3 hops) or multi-hop chain traversal with direction/type/layer filters |

### Search & Discovery (4 tools)

| Tool | Description |
|---|---|
| `search-elements` | Full-text search across element names, documentation, and properties with optional type, layer, and `specialization` filters |
| `search-relationships` | Search all relationships by text, type, source/target element layer, and `specialization` — no element ID needed |
| `get-or-create-element` | Discovery-first — returns existing element if exact name+type match exists, otherwise creates new |
| `search-and-create` | Combined search + conditional create with duplicate candidate display |

### Element & Relationship Creation (4 tools)

| Tool | Description |
|---|---|
| `create-element` | Create an ArchiMate element with type validation and duplicate detection. Optional `specialization` parameter auto-creates the specialization on first use |
| `create-relationship` | Create a relationship with ArchiMate specification rule enforcement. Optional `specialization` parameter auto-creates the specialization on first use |
| `create-view` | Create a new diagram view with optional viewpoint and connection router type |
| `clone-view` | Duplicate an existing view with all visual contents (elements, groups, notes, connections, bendpoints, styling). The clone references the same model objects |

### Element, Relationship & View Updates (3 tools)

| Tool | Description |
|---|---|
| `update-element` | Update element name, documentation, properties, or `specialization` (pass `""` to clear) |
| `update-relationship` | Update relationship name, documentation, properties, or `specialization` (pass `""` to clear). Source, target, and type are immutable |
| `update-view` | Update view name, viewpoint, documentation, properties, or connection router type |

### ArchiMate Specializations (5 tools)

Specializations are IS-A subtypes of ArchiMate concept types (e.g. "Microservice" is a kind of `ApplicationComponent`, "Cloud Server" is a kind of `Node`). Use them to classify the *kind of thing* an element is — not for per-instance attributes like environment or version. See `archimate://reference/archimate-specializations` for the full guide.

| Tool | Description |
|---|---|
| `list-specializations` | List every specialization defined on the model with `(name, conceptType, layer, usageCount)`. Optional `conceptType` filter |
| `create-specialization` | Define a specialization explicitly without creating any element. Idempotent on duplicate `(name, conceptType)` — useful for pre-registering vocabulary at session start |
| `update-specialization` | Rename a specialization. Refuses to merge into an existing target name. Existing references move with the rename |
| `delete-specialization` | Delete a specialization. Refuses by default if any concept uses it; pass `force: true` to detach references and delete in one atomic command |
| `get-specialization-usage` | Audit query — lists every element and relationship referencing a specialization. Call before rename or delete |

### View Composition (7 tools)

| Tool | Description |
|---|---|
| `add-to-view` | Place a model element onto a view diagram (same element can appear on multiple views). Optional `imagePath`, `imagePosition`, and `showIcon` for custom images |
| `add-group-to-view` | Add a visual grouping rectangle (pure visual container, no model representation). Optional `imagePath`, `imagePosition`, and `showIcon` for custom images |
| `add-note-to-view` | Add a text note annotation (pure visual, no model representation). Optional `imagePath`, `imagePosition`, and `showIcon` for custom images |
| `add-connection-to-view` | Add a visual connection representing an existing model relationship, with optional styling, label suppression, and label positioning |
| `update-view-object` | Update position, size, styling, and/or image of a visual element on a view. Optional `imagePath`, `imagePosition`, and `showIcon` for custom images |
| `update-view-connection` | Replace bendpoints, update styling, toggle label visibility, and/or set label position of a connection on a view |
| `apply-positions` | Apply a complete visual layout atomically (up to 10,000 entries per call) |

### View Cleanup (2 tools)

| Tool | Description |
|---|---|
| `remove-from-view` | Remove a visual element or connection from a view (model object preserved) |
| `clear-view` | Remove all visual elements and connections from a view (model objects preserved) |

### Layout & Routing (8 tools)

| Tool | Description |
|---|---|
| `compute-layout` | Apply an automatic layout algorithm (tree, spring, directed, radial, grid) to a view |
| `auto-route-connections` | Orthogonal connection routing using clearance-weighted visibility-graph A* pathfinding with corridor directionality, corridor diversity, group-wall awareness, and post-routing path straightening. Optional `autoNudge` mode automatically moves blocking elements and re-routes failed connections in a single atomic operation |
| `auto-layout-and-route` | Two modes: `auto` (default) uses ELK Layered to compute positions AND routes in one operation; `grouped` orchestrates the full grouped-view workflow (layout-within-group + arrange-groups + optimize-group-order + auto-route-connections) atomically |
| `layout-within-group` | Arrange child elements within a group using row, column, or grid patterns |
| `layout-flat-view` | Automatic layout for flat (non-grouped) views — row, column, or grid arrangement with optional sorting by name/type/layer and category grouping |
| `arrange-groups` | Position top-level groups relative to each other in grid, row, or column layout |
| `optimize-group-order` | Reorder elements within groups to minimise inter-group edge crossings |
| `resize-elements-to-fit` | Resize all (or selected) elements on a view to fit their labels using SWT font metrics. Two-pass algorithm for nested containment: children first, then parents |

### Layout Assessment & Analysis (2 tools)

| Tool | Description |
|---|---|
| `assess-layout` | Assess view layout quality with severity-tiered rating across 8 metrics — overlaps, crossings, pass-throughs, coincident segments, non-orthogonal terminals, spacing, alignment, label overlaps — plus informational detections for label truncation, parent-label obscured by child, and image sibling overlap. Self-element pass-throughs are reported but excluded from rating. Optional `includeViolatorIds` returns per-metric visual object IDs for targeted fixes |
| `detect-hub-elements` | Identify hub elements by counting visual connections per element, sorted descending. Includes sizing suggestions for elements with >6 connections based on the hub element formula |

### View Operations (1 tool)

| Tool | Description |
|---|---|
| `auto-connect-view` | Create visual connections for all existing model relationships between elements already placed on a view. Optional `showLabel: false` to suppress labels, and optional `lineColor`, `fontColor`, `lineWidth` to batch-style every connection it creates (combine with `relationshipTypes` filter to style by type) |

### Folder Management (5 tools)

| Tool | Description |
|---|---|
| `get-folders` | List folders (root-level by default, or children of a specific folder) |
| `get-folder-tree` | Folder hierarchy as a nested tree structure |
| `create-folder` | Create a new subfolder |
| `update-folder` | Update folder name, documentation, or properties |
| `move-to-folder` | Move a model object (element, relationship, view, or folder) to a different parent folder |

### Deletion (4 tools)

| Tool | Description |
|---|---|
| `delete-element` | Delete an element — cascades relationships and view references across all views |
| `delete-relationship` | Delete a relationship — cascades view connections across all views |
| `delete-view` | Delete a view and its visual contents (model elements preserved) |
| `delete-folder` | Delete a folder (requires `force: true` for non-empty folders) |

### Export (1 tool)

| Tool | Description |
|---|---|
| `export-view` | Render a view as PNG or SVG — returned inline (base64) or written to file. Optional `outputDirectory` to control where files are saved (auto-creates directories; defaults to temp) |

### Images (2 tools)

| Tool | Description |
|---|---|
| `add-image-to-model` | Import an image (icon) into the model archive. Preferred: `filePath` (local file) or `url` (HTTP download) — these bypass LLM text channel and avoid base64 corruption. Fallback: `imageData` (base64). Provide exactly ONE. Returns the archive `imagePath` for use with view composition tools. Images are deduplicated |
| `list-model-images` | List all images stored in the model archive with their paths and dimensions. Use the `imagePath` values with view composition tools to set images on elements, groups, or notes without re-importing |

### Batch & Mutation Control (4 tools)

| Tool | Description |
|---|---|
| `begin-batch` | Start batch mode — mutations are queued instead of applied immediately |
| `end-batch` | Commit all queued mutations atomically, or rollback (discard all) |
| `get-batch-status` | Check operational mode and queued operation count |
| `bulk-mutate` | Execute multiple mutations as a single compound command with back-references and optional `continueOnError` |

### Undo / Redo (2 tools)

| Tool | Description |
|---|---|
| `undo` | Undo the most recent mutation operation(s) with optional step count |
| `redo` | Redo previously undone operation(s) |

### Approval Workflow (3 tools)

| Tool | Description |
|---|---|
| `set-approval-mode` | Enable or disable human-in-the-loop approval for mutations |
| `list-pending-approvals` | List all pending mutation proposals awaiting approval |
| `decide-mutation` | Approve or reject a pending mutation proposal |

### Session Management (2 tools)

| Tool | Description |
|---|---|
| `set-session-filter` | Set persistent filters and field selection that apply to all subsequent queries |
| `get-session-filters` | Retrieve currently active session-scoped filters and field selection |

### Response Control

All query tools support response optimisation parameters:

- **Field selection:** `fields` param with presets (`minimal`, `standard`, `full`) or custom field arrays
- **Field exclusion:** `exclude` param to omit specific fields
- **Pagination:** Automatic for large result sets, with cursor-based continuation

## MCP Resources

The server provides 7 reference resources accessible to LLM clients.

### Prompts

| URI | Description |
|---|---|
| `archimate://prompts/model-exploration-guide` | Strategy guide for LLMs on how to efficiently search and traverse ArchiMate models |
| `archimate://prompts/explore-dependencies` | Workflow template for systematic dependency analysis of ArchiMate elements |
| `archimate://prompts/landscape-overview` | Workflow template for generating architecture landscape summaries |

### References

| URI | Description |
|---|---|
| `archimate://reference/archimate-layers` | Comprehensive mapping of ArchiMate layers to element types with descriptions |
| `archimate://reference/archimate-relationships` | All ArchiMate relationship types with valid source/target combinations and usage guidance |
| `archimate://reference/archimate-specializations` | Specialization (IS-A subtype) vocabulary: when to use specializations vs properties, common patterns per layer, and the discovery/create/audit/delete tool pipeline |
| `archimate://reference/archimate-view-patterns` | Curated viewpoint patterns, layout algorithm guidance, and diagramming best practices for composing ArchiMate views |

## Mutation Safety

All write operations integrate with Archi's **CommandStack**, making every mutation **undoable** via `Ctrl+Z` in Archi or the `undo` / `redo` tools.

**Approval mode** adds a human-in-the-loop gate:

```
set-approval-mode(true)
  → LLM proposes mutations → they queue as "pending"
  → list-pending-approvals → review what's proposed
  → decide-mutation(id, "approve") → apply, or "reject" → discard
```

**Batch mode** groups mutations into atomic transactions:

```
begin-batch()
  → create-element(...)
  → create-relationship(...)
  → add-to-view(...)
end-batch()
  → All succeed together or all roll back
```

## Troubleshooting

**Server won't start**
- Check if port 18090 is already in use: `lsof -i :18090`
- Verify the bind address in preferences is valid

**LLM client can't connect**
- Confirm the server is running (menu shows "Stop MCP Server")
- Verify the port matches your client config
- Test connectivity: `curl -X POST http://127.0.0.1:18090/mcp -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'`

**Model appears empty**
- Ensure an ArchiMate model is open in Archi before connecting
- If the model was opened after the session started, reconnect the LLM client

**Mutations fail with validation error**
- Check the `archiMateReference` field in the error — it cites the relevant ArchiMate spec section
- Use `search-and-create` instead of `create-element` to avoid duplicates

**TLS connection issues**
- Verify the keystore file exists and the password is correct
- For self-signed certificates, ensure the client trusts the certificate or use `-k` with `curl`

---

# Developer Guide

The following sections are for developers who want to fork, extend, or contribute to the plugin.

For comprehensive technical documentation covering architecture internals, coordinate model, routing pipeline, and extension patterns, see [docs/](docs/).

## Project Structure

```
arch-mcp-server/
├── net.vheerden.archi.mcp/          # Main plugin bundle
│   ├── META-INF/MANIFEST.MF         # OSGi bundle configuration
│   ├── plugin.xml                    # Eclipse extension points
│   ├── src/net/vheerden/archi/mcp/
│   │   ├── McpPlugin.java           # Plugin lifecycle & preferences
│   │   ├── server/                   # Jetty + MCP SDK wiring
│   │   ├── handlers/                 # MCP tool implementations (19 handler classes)
│   │   ├── model/                    # EMF model access layer
│   │   │   ├── geometry/             # Geometry utilities
│   │   │   └── routing/              # Connection routing pipeline
│   │   ├── search/                   # Full-text search engine
│   │   ├── response/                 # Response formatting & DTOs
│   │   ├── registry/                 # Tool & resource registries
│   │   ├── session/                  # Session management & caching
│   │   ├── logging/                  # SLF4J to Eclipse ILog bridge
│   │   └── ui/                       # Preferences, menus, startup
│   ├── resources/                    # MCP resource content files
│   └── lib/                          # Bundled dependencies
│       ├── mcp-sdk/                  # MCP Java SDK 0.17.2
│       ├── jetty/                    # Jetty 12.0.18 (ee10)
│       ├── jackson/                  # Jackson 2.16.1
│       ├── elk/                      # Eclipse Layout Kernel 0.11.0
│       └── slf4j/                    # SLF4J 2.0.11
├── net.vheerden.archi.mcp.tests/    # Test fragment (OSGi)
└── README.md
```

## Architecture Layers

The codebase enforces strict layer boundaries to keep concerns separated:

```
┌─────────────────────────────────────────────────────┐
│  Layer 1 — Protocol          server/, registry/     │
│  Only MCP SDK + Jetty types. No EMF imports.        │
├─────────────────────────────────────────────────────┤
│  Layer 2 — Handlers          handlers/              │
│  DTOs + ArchiModelAccessor interface only.           │
│  No EMF or SWT imports.                             │
├─────────────────────────────────────────────────────┤
│  Layer 3 — Model             model/                 │
│  ONLY package that imports EMF / ArchimateTool.     │
│  Returns DTOs, never EObjects.                      │
├─────────────────────────────────────────────────────┤
│  Layer 4 — UI                ui/                    │
│  SWT/Eclipse UI only. Preferences, menus, status.   │
│  Never blocks Jetty threads.                        │
└─────────────────────────────────────────────────────┘
```

**Key rule:** Handlers never see EMF objects. All model access goes through the `ArchiModelAccessor` interface, which returns DTOs.

## Key Architecture Decisions

### Transport

- **Dual transport:** Streamable-HTTP (`/mcp`) + SSE (`/sse`) for backward compatibility
- Each transport gets its own `McpSyncServer` instance
- `HttpServletStreamableServerTransportProvider` and `HttpServletSseServerTransportProvider` both extend `HttpServlet`

### Threading

- **Reads:** Direct EMF access from Jetty threads (thread-safe for read-only)
- **Mutations:** Dispatched to the SWT UI thread via `Display.syncExec()` for CommandStack consistency
- UI thread is never blocked by read operations

### Response Envelope

Every tool response follows a standard structure:

```json
{
  "result": { },
  "nextSteps": ["Use get-relationships to explore connections", "..."],
  "_meta": { "totalCount": 42, "isTruncated": false, "durationMs": 12 }
}
```

### Error Handling

Structured errors with actionable guidance:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Relationship type not valid between these elements",
    "details": "ServingRelationship requires ApplicationComponent as source",
    "suggestedCorrection": "Use an ApplicationComponent or change relationship type",
    "archiMateReference": "ArchiMate 3.2 § 5.1.2"
  }
}
```

### Mutation Pattern

All mutation tools use the `PreparedMutation<T>` pattern:
1. Validate inputs and build the command (on Jetty thread)
2. Dispatch execution to UI thread via `Display.syncExec()`
3. Execute through Archi's CommandStack (enables undo/redo)
4. Return result DTO

For connections, `redo()` must null-then-reconnect due to Archi's `connect()` early-return guard.

## Adding a New Tool

1. **Create or extend a handler** in `handlers/`
2. **Register the tool** in `registerTools()` — define the JSON schema, description, and call handler
3. **Add model access** if needed — new method on `ArchiModelAccessor` interface, implemented in `ArchiModelAccessorImpl` (returns DTOs, not EObjects)
4. **Format responses** using `ResponseFormatter` with the standard envelope
5. **Handle errors** — catch at the handler boundary, translate to structured `ErrorResponse`
6. **Write tests** — mock `ArchiModelAccessor` for handler tests (no EMF runtime needed)

## Testing

The test bundle (`net.vheerden.archi.mcp.tests`) is an OSGi fragment with `Fragment-Host: net.vheerden.archi.mcp`, giving it full access to main plugin classes.

- **Run tests** in Eclipse as "JUnit Plug-in Test", one class at a time
- **Pure-Java tests** (geometry, layout algorithms, routing) can also run as standard JUnit tests without the Eclipse runtime
- Handler tests mock `ArchiModelAccessor` — no Archi installation needed
- Tests needing Archi runtime (preferences, EMF model) require the full Eclipse environment

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| MCP Java SDK | 0.17.2 | Model Context Protocol implementation |
| Jetty | 12.0.18 (ee10) | Embedded HTTP server |
| Jackson | 2.16.1 | JSON serialization |
| Eclipse Layout Kernel (ELK) | 0.11.0 | Layered graph layout algorithms |
| SLF4J | 2.0.11 | Logging (bridged to Eclipse ILog) |
| Jakarta Servlet API | 6.0.0 | Servlet API for Jetty ee10 |
| Project Reactor | 3.7.0 | Async support (MCP SDK transitive) |

Eclipse/Archi runtime dependencies: `org.eclipse.ui`, `org.eclipse.core.runtime`, `org.eclipse.swt`, `org.eclipse.jface`, `org.eclipse.zest.layouts`, `com.archimatetool.model`, `com.archimatetool.editor`.

## PDE Build Notes

This is a pure Eclipse PDE project — no Maven or Gradle. Key files:

- `MANIFEST.MF` — OSGi bundle metadata, `Bundle-ClassPath` lists all JARs
- `build.properties` — PDE build includes
- `.classpath` — Eclipse project classpath

When adding a new JAR dependency, update **three places**: the `lib/` directory, `MANIFEST.MF` `Bundle-ClassPath`, and `.classpath`.

## Acknowledgments

- [Archi](https://www.archimatetool.com) - Archi® modelling toolkit
- [ArchiMate](https://publications.opengroup.org/archimate-library) - ArchiMate® Specification
- [Eclipse IDE](https://eclipseide.org) - Eclipse IDE™
- [MCP](https://modelcontextprotocol.io/) — Model Context Protocol™
- [ELK](https://github.com/eclipse-elk/elk) - Eclipse Layout Kernel™
- [Jackson](https://github.com/FasterXML/jackson) - Jackson JSON Library
- [Jetty](https://github.com/jetty/jetty.project) - Eclipse Jetty® - Web Container & Clients
- [SLF4J](https://github.com/qos-ch/slf4j) - Simple Logging Facade for Java

## License

This project is licensed under the MIT License.
See [LICENSE](LICENSE) for details.
