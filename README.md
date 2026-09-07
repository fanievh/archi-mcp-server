# Archi MCP Server

An Eclipse PDE plugin for [Archi](https://www.archimatetool.com/) that exposes ArchiMate models through the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/), enabling LLMs to query, analyse, and modify enterprise architecture models through natural language.

## What It Does

Archi MCP Server embeds an HTTP server inside Archi that speaks MCP. Once running, any MCP-compatible LLM client (Claude, Cline, LM Studio, etc.) can connect and interact with the currently open ArchiMate model — asking questions, searching elements, traversing relationships, composing view diagrams, and even creating or modifying model content.

The server provides **70 MCP tools** across querying, searching, creating, layout, routing, assessment, batch operations, images, specializations, and more — plus **14 MCP resources** with ArchiMate reference material, workflow guides, and a viewpoint recipe library for LLMs.

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

The **MCP Server** entry in Archi's menu bar has three items:

| Menu item | What it does |
|---|---|
| **Start MCP Server** | Starts the embedded server; toggles to **Stop MCP Server** while running. |
| **Approval Mode** | Checkable toggle for the human-owned approval gate. When on, the agent's changes queue for your review instead of applying immediately — see [Mutation Safety](#mutation-safety). |
| **Pending approvals (N)** | Opens the **Pending Approvals** dock view; `N` is the live count of changes awaiting your decision. |

Server behaviour (port, bind address, TLS, authentication, …) is configured separately under **Window > Preferences > MCP Server** — see [Configuration](#configuration). The default endpoint is `http://127.0.0.1:18090`.

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
| **Keystore Password** | *(empty)* | Password for the keystore file, stored in your OS keychain via Equinox secure storage (no separate password to manage) — never written to disk in cleartext |
| **Enable bearer-token authentication** | `false` | Require an `Authorization: Bearer <token>` header on every request (see below) |

### TLS / HTTPS

The server supports optional TLS encryption. To enable:

1. In preferences, check **Enable TLS (HTTPS)**
2. Click **Generate Self-Signed Certificate** to create a keystore automatically
3. Restart the server — the endpoint changes to `https://127.0.0.1:18090`

Clients must trust the self-signed certificate. For `curl` testing, use the `-k` flag. For LLM clients, import the certificate into the client's trust store or the JVM `cacerts`.

The keystore password (whether you type it or generate it with the button) is stored in your OS keychain via Equinox secure storage — the same store the bearer token and Archi itself use — not in the plaintext preference file. If you had a keystore password set in an earlier version, it is migrated automatically on first start.

### Enabling authentication (bearer token)

By default the server requires no authentication — anything that can reach the port can call the tools, which is why the default bind is loopback only. If you bind to a non-loopback address, or simply want a secret required even on loopback, enable an **opt-in bearer token**:

1. In **Window > Preferences > MCP Server > Authentication**, check **Enable bearer-token authentication**. A 256-bit token is generated automatically on first opt-in and stored in your OS keychain (via Equinox secure storage — no separate password to manage).
2. Click **Copy** to copy the token, or **Generate / Regenerate token** to roll it (regenerating invalidates clients still using the old token).
3. Restart the server. Every request to `/mcp` and `/sse` must now send `Authorization: Bearer <token>`; a missing, malformed, or wrong token gets `401`.

Configure your clients to send the header:

**Claude Code (CLI):**
```bash
claude mcp add --transport http archi http://127.0.0.1:18090/mcp --header "Authorization: Bearer <token>"
```

**Claude Code (`.mcp.json` / `~/.claude.json`):**
```json
{
  "mcpServers": {
    "archi": {
      "type": "http",
      "url": "http://127.0.0.1:18090/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

**Claude Desktop (via `mcp-proxy`)** — pass the header through the proxy:
```json
{
  "mcpServers": {
    "archi": {
      "command": "uvx",
      "args": ["mcp-proxy", "--transport", "streamablehttp", "--headers", "Authorization", "Bearer <token>", "http://127.0.0.1:18090/mcp"]
    }
  }
}
```

**`curl` (testing):**
```bash
curl -X POST http://127.0.0.1:18090/mcp -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
```

Authentication is **off by default** — existing configs without an `Authorization` header keep working unchanged until you enable it. On a non-loopback bind, also enable TLS so the token is not sent in cleartext.

### Security model

The server exposes mutating and file-touching tools over HTTP, so the trust
boundary is worth understanding. [`SECURITY.md`](SECURITY.md) states what the
server protects against by default (loopback bind, Origin/Host validation,
request/session/image resource limits, UTF-8 request-body enforcement, the
human-owned approval gate) versus
what is your responsibility as the operator (transport encryption, client
authentication, securing a non-loopback bind), and how to report a
vulnerability. Read it before binding off-loopback or pointing an agent at
untrusted input.

## Available Tools

The server exposes **70 MCP tools** organised into functional categories.

### Query & Model Inspection (6 tools)

| Tool | Description |
|---|---|
| `get-model-info` | Model overview — name, `purpose`, custom `properties`, element/relationship/view counts by type and layer, plus specialization count. Read counterpart to `update-model` |
| `get-element` | Retrieve element(s) by ID (single via `id` or batch via `ids` array) |
| `get-views` | List views with optional viewpoint type or name filtering |
| `get-view-contents` | View diagram contents — elements, relationships, visual positions, connection routing, image visuals, and the full styling surface (typography, gradient, alignment, line style, `labelExpression`) so styling mutations can be read back and verified. `format: "tree"` returns the containment hierarchy, descending both visual groups **and** ArchiMate-element containers — nested children appear in the parent node's `children` array with a `childCount`. **The group stats count both container kinds** the layout family arranges — a native view group *and* an ArchiMate `Grouping` element — so `topLevelGroups` matches what `arrange-groups` will position on the canvas rather than under-reporting a `Grouping`-built view as flat. `topLevelGroups` counts **depth** as well as kind: a container drawn inside a host is `nestedGroups`, and `arrange-groups` arranges it anyway — inside that host, reported in `nestedContainersArranged` — so on such a view this stat is the lower of the two numbers. Every container node carries `isGroup: true` at any depth in `tree` and `graph`, `format: "summary"` counts both under its `Containers:` clause, and `ungroupedElements` excludes containers (a container is what loose elements are placed *into*). `groups()` itself is unchanged, so `exclude: ['groups']` behaves as before. Graph nodes and edges also carry their visual identifiers (`viewObjectId` on nodes, `viewConnectionId` on edges) so a returned visual can be fed straight into `remove-from-view` / `update-view-object` / `update-view-connection` without a second lookup. On Archi 5.10, connections also report `relativePosition` — the connection "Label Offset" anchor — so an offset applied by `auto-route-connections` can be read back programmatically (omitted on Archi 5.7 and for the un-offset default). Connections also carry `sourceRenderFace` / `targetRenderFace` — the element face the line is actually **drawn** leaving and entering (`top` / `bottom` / `left` / `right`), which nothing else in the payload gives you: the anchors are element *centres* the renderer aims from, and a bendpoint is a waypoint it aims *at*, so neither is the point where the line meets the box. The field is **omitted whenever a face cannot be established** — a corner attachment, a reference inside the box, an attachment on a rounded figure's arc, a zero-size element, a Junction, a `Grouping` on its alternate figure, or a configuration under which Archi's two anchor algorithms would disagree — and its absence never means "no face". `absoluteBendpoints` is likewise **derived, not stored**: Archi holds each bendpoint twice, relative to each endpoint's centre, and the response interpolates between the two at the weight Archi renders with, `(i + 1) / (n + 1)` for bendpoint `i` of `n`. While the two reconstructions agree the point is exact; once an endpoint has moved they diverge, the drawn line is *sheared*, and the reported point follows that shear — which is the drift `assess-layout` reports as `anchorDriftCount` |
| `get-relationships` | Traverse relationships with configurable depth (0-3 hops) or multi-hop chain traversal with direction/type/layer filters |
| `find-concept-usage` | Reverse where-used lookup — given an element or relationship ID, returns every view and visual object/connection that references it. Inverse of `get-view-contents`. Use before `delete-element` / `delete-relationship` / rename / re-type to see the cross-view footprint in one round-trip |

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
| `create-element` | Create an ArchiMate element with type validation and duplicate detection. Optional `specialization` parameter auto-creates the specialization on first use. A `folderId` that points at a folder whose ArchiMate layer is illegal for the element type is rejected up front with `FOLDER_LAYER_MISMATCH` (delegated to Archi's own folder rules) rather than failing later when Archi saves the model. Optional `source` map tags the element with provenance (keys auto-prefixed `mcp.source.`) and is honoured on **every** path the tool has — direct, batch-queued, approval and `bulk-mutate` alike. It is create-time only: to add or correct provenance afterwards, write the same keys through `update-element`'s `properties` map with the prefix spelled out. A source key that already carries the prefix, or whose prefixed form collides with a `properties` entry of the same name, is rejected rather than silently double-prefixed or overwriting the caller's value |
| `create-relationship` | Create a relationship with ArchiMate specification rule enforcement. Optional `specialization` parameter auto-creates the specialization on first use. Optional type-conditional semantic attributes: `accessType` (AccessRelationship), `associationDirected` (AssociationRelationship), `influenceStrength` (InfluenceRelationship) |
| `create-view` | Create a new diagram view with optional viewpoint and connection router type |
| `clone-view` | Duplicate an existing view with all visual contents (elements, groups, notes, connections, bendpoints, styling). The clone references the same model objects |

### Element, Relationship, View & Model Updates (4 tools)

| Tool | Description |
|---|---|
| `update-element` | Update element name, documentation, properties, or `specialization` (pass `""` to clear) |
| `update-relationship` | Update relationship name, documentation, properties, or `specialization` (pass `""` to clear). `name` and `documentation` also honour the empty-string clear — `""` clears the field rather than meaning "leave unchanged", on the standalone tool and inside `bulk-mutate` alike, and a lone `{"documentation": ""}` is a valid call. (`update-element` differs deliberately: its schema makes no empty-clear promise for those two fields, so a blank value there is ignored.) Optional type-conditional semantic attributes: `accessType` (AccessRelationship), `associationDirected` (AssociationRelationship), `influenceStrength` (InfluenceRelationship). Source, target, and type are immutable |
| `update-view` | Update view name, viewpoint, documentation, properties, or connection router type |
| `update-model` | Update the loaded model's own `name`, `purpose`, and custom `properties` as a single undo unit. At least one of the three must be provided; omitted fields stay unchanged. Empty-string `purpose` clears the field; null property values remove a key |

### ArchiMate Specializations (5 tools)

Specializations are IS-A subtypes of ArchiMate concept types (e.g. "Microservice" is a kind of `ApplicationComponent`, "Cloud Server" is a kind of `Node`). Use them to classify the *kind of thing* an element is — not for per-instance attributes like environment or version. See `archimate://reference/archimate-specializations` for the full guide.

| Tool | Description |
|---|---|
| `list-specializations` | List every specialization defined on the model with `(name, conceptType, layer, usageCount, imagePath)`. Optional `conceptType` filter |
| `create-specialization` | Define a specialization explicitly without creating any element. Idempotent on duplicate `(name, conceptType)` — useful for pre-registering vocabulary at session start. Optional `imagePath` sets the specialization's icon (Archi renders it on every concept of that specialization) |
| `update-specialization` | Rename a specialization and/or set its icon. Refuses to merge into an existing target name. Existing references move with the rename. Optional `imagePath` (set/change icon) and `clearImagePath: true` (explicit clear) — mutually exclusive. At least one of `newName`, `imagePath`, or `clearImagePath` must be supplied |
| `delete-specialization` | Delete a specialization. Refuses by default if any concept uses it; pass `force: true` to detach references and delete in one atomic command |
| `get-specialization-usage` | Audit query — lists every element and relationship referencing a specialization. Call before rename or delete |

### View Composition (9 tools)

All view-composition tools that place a new visual object (`add-to-view`, `add-group-to-view`, `add-note-to-view`, `add-view-reference-to-view`, `add-image-to-view`) and `update-view-object` accept the full visual styling surface: fill / line / font colour, opacity, line width, line style (`solid` / `dashed` / `dotted` / `none`), typography (`fontName`, `fontSize`, `fontStyle`), gradient, `deriveLineColor`, `outlineOpacity`, `figureType` (`rectangular` / `tabbed`), `textAlignment`, `verticalTextAlignment`, plus note-specific `borderType` (`dogear` / `rectangle` / `none`). Existing calls are byte-identical; supply only the fields you want to set. Every one of these fields reads back through `get-view-contents`, so a styling mutation can be verified after the write.

**Title alignment now matches the palette on the three types where Archi's own default is a fixed constant.** Archi's diagram factory stamps a type's UI-provider defaults onto anything drawn from the palette; this server builds objects straight from the EMF factory, which stamps nothing, so an ArchiMate `Grouping`, a native group and a note used to come out **centre** where a hand-drawn one is **left** — on a 400 px box, a title starting a few pixels from the edge versus one sitting ~170 px away. Those three types are now stamped `left` at creation, before any styling is applied, so an object created with no styling at all matches the palette while an explicit `textAlignment` still wins. **Scope:** a *plain ArchiMate element*'s default is derived by Archi from a user preference rather than a constant, so this server leaves it at `centre` and a host whose preference was changed can still differ — pass `textAlignment` explicitly when the alignment matters. Connections, images and view references already carried their own providers' values and are untouched. `clone-view` copies both `textAlignment` and `verticalTextAlignment`, so a cloned object keeps its title band rather than silently reverting to `top`.

When `add-to-view` or `add-group-to-view` nests a child *inside* a container whose fill colour you have not explicitly set, the parent's fill auto-recedes to a subtle backdrop (`#F4F4F4`) so the nesting reads as depth instead of a flat block. The recession is provenance-gated and idempotent (only an unauthored fill is touched; an explicitly coloured container is left untouched), rides the placement as one undo unit, and excludes the root view. Pass `recede: false` to suppress it for a call.

A view object can also **anchor** to a target container via `update-view-object`, recording an `{anchorTarget, anchorEdge, dx, dy}` so its position resolves from the target's current bounds at *commit* time rather than a frozen snapshot — edge `below` (default) tracks the target's growing bottom, `above`/`right`/`left` the corresponding edge. When a later mutation moves or grows the target, every anchored object is repositioned within the same undo unit. Target and object must share a coordinate space; self-anchors and cross-space anchors are rejected. `add-note-to-view`'s content-relative placement uses the same edge resolver.

| Tool | Description |
|---|---|
| `add-to-view` | Place a model element onto a view diagram (same element can appear on multiple views). Optional `imagePath`, `imagePosition`, `showIcon` for custom icon overlays, `labelExpression` for a per-view-object dynamic label template (e.g. `"${name}"`, `"${property:Owner}"`), and `recede: false` to suppress the auto-backdrop when nesting inside an unauthored-fill container |
| `add-group-to-view` | Add a visual grouping rectangle (pure visual container, no model representation). Optional `imagePath`, `imagePosition`, `showIcon` for custom icon overlays, and `recede: false` to suppress the auto-backdrop when nesting inside an unauthored-fill container. Pass `label: ""` to create an **untitled** group — use that for a container your source material never named, rather than inventing a placeholder; Archi renders it with an empty tab and substitutes nothing. The key itself is still required: omitting it is an error |
| `add-note-to-view` | Add a text note annotation (pure visual, no model representation). Optional `imagePath`, `imagePosition`, `showIcon`, and note-specific `borderType`. Omit `height` and the server fits it to the note's wrapped text by real glyph measurement, with 80 as the floor and 600 as the cap — 80 is what short content resolves to, not a fixed default; supply a height only to pin a fixed size. Pass `content: ""` to create an empty note — a shaped placeholder you fill in later. Placing the annotation onto a rectangle that a route already drawn on the view passes through returns an `ANNOTATION_PLACED_IN_ROUTED_CORRIDOR` structured warning naming every crossed connection, on the immediate, batched and approval arms alike. Placing *after* `auto-route-connections`, as the ordering guidance says, does not avoid this — after routing is when the corridors exist to land in — so the disclosure is a measurement of this rectangle against these routes rather than more advice. A crossing here lands in the informational `connectionThroughNoteCount`, which caps the view at `good`. |
| `add-view-reference-to-view` | Embed another ArchiMate view as a clickable thumbnail (the agent-driven equivalent of Archi GUI's drag-view-onto-view). Requires `viewId` (target) and `referencedViewId` (source). The referenced view's name is not stored on the visual — renaming the referenced view auto-updates every embedding. Placing the annotation onto a rectangle that a route already drawn on the view passes through returns an `ANNOTATION_PLACED_IN_ROUTED_CORRIDOR` structured warning naming every crossed connection, on the immediate, batched and approval arms alike. Placing *after* `auto-route-connections`, as the ordering guidance says, does not avoid this — after routing is when the corridors exist to land in — so the disclosure is a measurement of this rectangle against these routes rather than more advice. A crossing here lands in the **rated** `connectionPassThroughs`, which can drive the view to `poor` — unlike a note, this visual is an ordinary layout node to the assessor. |
| `add-image-to-view` | Add a standalone image as a first-class diagram node (sibling to notes, groups, and view-references). Requires `viewId` and an `imagePath` returned by `add-image-to-model` or `list-model-images`. Default size is the image's natural dimensions; typo'd paths are rejected with `IMAGE_NOT_FOUND`. Distinct from `update-view-object`'s `imagePath`, which sets an icon overlay on an existing element. Placing the annotation onto a rectangle that a route already drawn on the view passes through returns an `ANNOTATION_PLACED_IN_ROUTED_CORRIDOR` structured warning naming every crossed connection, on the immediate, batched and approval arms alike. Placing *after* `auto-route-connections`, as the ordering guidance says, does not avoid this — after routing is when the corridors exist to land in — so the disclosure is a measurement of this rectangle against these routes rather than more advice. A crossing here lands in the **rated** `connectionPassThroughs`, which can drive the view to `poor` — unlike a note, this visual is an ordinary layout node to the assessor. |
| `add-connection-to-view` | Add a visual connection representing an existing model relationship, with optional styling, label suppression, and label positioning |
| `update-view-object` | Update position, size, styling, image, `labelExpression`, and/or `anchor` of a visual element on a view. Optional anchoring (`anchorTarget`, `anchorEdge` `below`/`above`/`right`/`left` — default `below`, `anchorDx`/`anchorDy`) makes the object *follow* a target container's edge, resolved from the target's current bounds at commit time instead of a frozen absolute position — so a note stays below a group as the group grows. Target and object must share a coordinate space; pass an empty `anchorTarget` to clear. `text` sets a group's label or a note's content; pass `text: ""` to **clear** it, leaving the group untitled or the note empty. Omitted and empty are different requests — omitting leaves the text unchanged. An empty string is still rejected for element view objects, where `update-element` is the tool that renames a concept. **Note height is the one exception to "omitted means unchanged":** a request that changes a note's `text` or `width` while omitting `height` re-fits the height to the wrapped content — the same auto-fit `add-note-to-view` applies, so the same content at the same width resolves to the same number on either path. A request that only moves the note never re-fits it, and supplying `height` pins a fixed size |
| `update-view-connection` | Replace bendpoints, update styling, toggle label visibility, and/or set label position of a connection on a view. `lineStyle` is **rejected** on a connection rather than silently ignored: an ArchiMate connection carries no settable line style in Archi — its dash is derived from the relationship type at render time — so the refusal names the reason and points at `lineColor` + `lineWidth`, which do apply. (View-object `lineStyle`, on `update-view-object`, is unaffected) |
| `apply-positions` | Apply a complete visual layout atomically (up to 10,000 entries per call). Carries the same note exception as `update-view-object`: supplying a note's `width` while omitting `height` re-fits the height to the wrapped text. **Containers grow with their contents:** a position that pushes an object past its container's bounds re-fits that container and every ancestor above it, and carries along any object anchored to something the call repositions. Each container is measured once per call against what the same call has already decided about it, so two entries needing different axes of one group both survive and the arrival order does not change the result — and the re-fits reach the model as one command per group rather than one per entry that grew it. They are committed after every position lands, so naming a container in `positions` and asking for it *smaller* than its children need loses to the fit, while asking for it *bigger* wins and retires the fit. The response reports counts only, never the geometry those containers ended at — re-read with `get-view-contents` before computing from a container's size |

### View Cleanup (2 tools)

| Tool | Description |
|---|---|
| `remove-from-view` | Remove a visual element or connection from a view (model object preserved) |
| `clear-view` | Remove all visual elements and connections from a view (model objects preserved) |

### Layout & Routing (11 tools)

> **LLM agents:** Fetch `archimate://prompts/routing-preconditions-checklist` before invoking `auto-route-connections` or `auto-layout-and-route` on any non-trivial view. The routing pipeline cannot recover from missing preconditions — it can only route the geometry the agent has set up. The spacing convenience tools self-terminate honestly: if a view is provably too dense for spacing to fix they return `terminationReason: density_floor_reflow_required` and offer a user-consentable structural reflow — surface that to the user instead of looping the spacing tools.

| Tool | Description |
|---|---|
| `auto-route-connections` | Orthogonal connection routing using clearance-weighted visibility-graph A* pathfinding with corridor directionality, corridor diversity, group-wall awareness, channel-global ordered nudging, and post-routing path straightening. Two modes: `mode: "full"` (default) re-routes whole connections via visibility-graph A*; `mode: "terminals-only"` rectifies only the first/last bendpoint of each connection to make terminal segments orthogonal — best after ELK on grouped views to fix diagonal terminal entries without the crossing inflation a full re-route causes; it also sweeps redundant *interior* collinear bendpoints (a terminal L-bend that lands collinear with the existing trunk) so a re-route drives `connectionRedundantBendpointCount` toward zero without touching the pinned terminal egress anchors. Both modes now clear an **off-face terminal hug** — a connection that leaves an element face then runs a pixel or two parallel hugging it — by pushing the first trunk clear of the face: the full pipeline uses a dual-axis healthy-floor egress pass (a 15 px healthy parallel gap enforced on *both* axes, plus an own-face short-circuit that catches sub-10 px micro-hugs), and `terminals-only` mirrors the same floor as pure geometry with a per-side orthogonality backstop that declines any lift which would introduce a diagonal (detection fires at the assessor's 8 px oracle, so an already-clear terminal is byte-identical). This drives the `offFaceParallelTerminalCount` signal to zero. A gated final pass also **dissolves a coincident same-face hub port** — two terminals a downstream stage collapsed onto one perimeter port of a low-degree element face (the `coincidentFacePortCount` defect `assess-layout` reports) — by moving whichever terminal can move to a free along-face slot; it is a byte-identical no-op unless there is a real collision with a clear slot, so it never disturbs a dense hub's own distribution or adds a crossing. Optional `autoNudge` mode automatically moves blocking elements (and resizes parent groups to contain them) and re-routes failed connections in a single atomic operation; a nudged child is floored at its parent's **title band** rather than at the container padding, so a routing lift can never push a child under its parent's title (the floor applies to an ArchiMate-element container as well as a visual group, and never exceeds the child's own pre-nudge position, so it can only shorten an upward move). On Archi 5.10, when a Middle label still renders on its own endpoint box after position selection, the router applies the connection "Label Offset" (`relativePosition`) to lift it clear — the only channel that can clear own-endpoint label bleed on a position-preserving route (runtime-guarded; a silent no-op on Archi 5.7). Optional `labelPolicy: "auto-hide-on-collision"` hides exactly those connection labels that collide at *every* candidate position — the router already scores all three positions per connection, so this acts on a measured verdict rather than a guess — and lists each hide in `hiddenLabels` by `connectionId` with a reason, never truncated, so every one is auditable and reversible with `update-view-connection(showLabel: true)`. It is **off by default**; omitting it leaves label visibility byte-identical. A label you hid yourself is never overridden nor claimed as a policy hide, the hides ride the routing pass's own compound so one undo reverts them with the routes, and a Middle label the Archi 5.10 Label Offset can still lift clear is not treated as unplaceable. The response carries a `structuredWarnings: List<{code, message, remediationTool, remediationViolatorIds}>` field for deterministic LLM iteration (`AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP` instructs the agent to run `layout-within-group` on the parent before re-routing; `EGRESS_LIFT_LAYOUT_BOUND` reports that the router generated then rolled back off-face egress lifts because applying them would narrow a parallel-connection gap below the 15 px healthy floor — the residual hug is layout-bound, so a matching `nextSteps` entry names spreading the elements instead of declining silently; `AUTO_ROUTE_CROSSINGS_REGRESSED` reports that a full re-route *increased* the view's own edge-crossing count, naming both the before and after figures and pointing at `mode: "terminals-only"` — it is gated on the input having been routed, so a first-time route of an unrouted view is never advised to undo a good result, and its `remediationTool` names a recovery of *this* call rather than the tool that caused the regression, scoped to the arm the call took: `undo` once the re-route has been applied, `end-batch` (with `rollback:true` to discard it) while it is queued in an open batch, and no tool at all while it awaits a human's decision, since rejecting it in Archi is what restores the previous paths; `CONNECTION_NOT_FOUND` aggregates every unresolved id from `connectionIds` in `remediationViolatorIds`, so the matching next step now fires only when connection ids genuinely went missing instead of on any warning at all; `CONNECTION_ROUTED_THROUGH_NOTE` discloses every (connection, note) pair this call routed straight through a note — a note is excluded from the A* obstacle set on purpose, so the crossing is a disclosure and not a violation, and the code exists to name *which* note was crossed on the call that crossed it instead of leaving that to a follow-up `assess-layout`; its `remediationViolatorIds` carry the *note* ids and its `remediationTool` is `update-view-object`, because the note is what the caller moves, not the route; `AUTO_NUDGE_NET_ZERO` reports an element that `autoNudge` moved and that ended at the position it started from — such an element is no longer listed in `nudgedElements` nor counted in `nextSteps`, because reporting it as nudged contradicted the same response's `failed` array and its `recommendations`, which repeated the identical move. Two causes produce that zero — a move a later iteration reversed, and one the parent-containment clamp absorbed — and the summed deltas cannot tell them apart, so the message names the outcome and says so rather than claiming the loop oscillated; the remedy is a spacing lever, never another route). **Terminals are held on the element face the router assigned them** for the whole pipeline: a terminal displaced by a clearance stage's micro-jog cleanup is realigned a second time immediately after that cleanup, a terminal whose own segment crosses an obstacle is *slid along its own face* to align with its neighbour (clamped to the face's extent, kept only if that clears the crossing) rather than deleted outright with its neighbour promoted to the terminal index on whatever off-face coordinate it happened to hold, and a stage that deliberately re-selects a face re-derives the anchoring record instead of leaving it naming the face the terminal just left. Every one of those refuses to guess in the same direction — a terminal on no face line, or exactly on a corner where two face lines hold equally, yields no face rather than an invented one |
| `auto-layout-and-route` | Two modes: `auto` (default) uses ELK Layered to compute positions AND routes in one operation; `grouped` orchestrates the full grouped-view workflow (layout-within-group + arrange-groups + optimize-group-order + auto-route-connections) atomically. `grouped` requires the view to have at least one top-level container, and **both container kinds qualify** — a native view group and an ArchiMate `Grouping` element — so a `Grouping`-built view is a valid target rather than being rejected as having no groups. `grouped` lays out the **whole subtree**, not one level: each top-level group is delegated to the same post-order recursion `layout-within-group`'s `recursiveChildren` drives, so a child that is itself a container is sized from its contents (with its title band reserved) instead of from its own label text and then descended into — `elementsRepositioned` counts every descendant moved, each re-fitted nested container is reported in `nestedContainersFitted` with the rectangle it landed at, and the depth cap surfaces as a response flag. Smart iteration when `targetRating` is set — factor-aware iteration tunes the right knob per tier, with plateau detection to exit early when iterations stop improving the dominant tier, and a `terminationReason` on the response saying which of the loop's stopping conditions actually fired. `labelPolicy` is honoured wherever a routing pass runs (grouped mode, and flat mode with a `targetRating`); flat mode *without* a `targetRating` takes ELK's own edge routes and never invokes the label optimizer, so it **rejects** the parameter with a remedy rather than accepting it and doing nothing. **The loop keeps the best attempt it *produced*, which can still be worse than the view you handed it** — so every quality-loop run reports `ratingBefore` (the rating measured before any mutation) beside `achievedRating`, and a committed result that is worse raises an `AUTO_LAYOUT_RATING_REGRESSED` structured warning naming each metric that moved with before/after values, `remediationTool: undo`. It is not gated on the target being missed: meeting a target is *at least as good as*, so an already-`excellent` view can be downgraded to a `good` target, report goal reached, and still be a regression. Do not read `achievedRating` alone as evidence the call helped |
| `layout-within-group` | Arrange child elements inside a container using row, column, or grid patterns. The container may be a visual group **or** an ArchiMate-element container (`ApplicationComponent`, `Node`, `ApplicationFunction`, etc.) that holds nested children — the same `arrangement` / `spacing` / `padding` / `columns` / `autoResize` / `autoWidth` semantics apply to both. **`recursive` is the exception**: the upward pass walks native view groups only, so it neither starts from nor continues past an ArchiMate-element container. That is deliberate — growing an element as a side effect of arranging its child is a semantic act, not a geometric one — and it is no longer silent: `ancestorPropagation` ships on every response, saying as a stable code which situation produced the count you got — including the two that can re-fit some ancestors and *then* stop at a parent they could not grow, where a positive count reads as success and is not. Optional `recursiveChildren` descends into nested containers, arranging every level post-order in one atomic command with a single undo — for multi-level inventories (functions in components in domains, or region → availability zone → node → artifact) that otherwise need hand-computed coordinates. Under the recursive descent each grid column is sized from its **own** widest member rather than from the widest element in the whole grid, so one over-wide descendant no longer inflates its siblings, their container, and that container's siblings a level up; columns still line up across rows, and the non-recursive default is unchanged. The response reports what the pass actually did in both directions: `resizedAncestors` / `ancestorsResized` name the containers it grew *upward*, `nestedContainersFitted` names every descendant container it re-fitted *downward* (id plus landed rectangle, omitted when empty), `resizedElements` names every leaf child whose **size** actually changed with the rectangle it ended at — this call writes a full rectangle to every child and in a grid that rectangle is its *cell*, so a narrow element takes its column's width whether or not you set `autoWidth`, and the move count alone never disclosed it — the depth cap surfaces as a response flag, and `groupResized` reflects a measured change rather than the `autoResize` you asked for, so a repeated layout that moves nothing honestly returns `false`. Notes, view-references, and connections are rejected as containers |
| `layout-flat-view` | Automatic layout for flat (non-grouped) views — row, column, or grid arrangement with optional sorting by name/type/layer and category grouping |
| `arrange-groups` | Position top-level containers relative to each other in grid, row, or column layout. **Two kinds of container are arranged and both count as a "group" here:** a native view group from `add-group-to-view`, and an ArchiMate `Grouping` element from `create-element` + `add-to-view` — so a view built entirely from `Grouping` zones (the shape the technology and deployment recipes prescribe) is arranged like any other. **Density-aware default:** when `spacing` is omitted on a view with inter-group connections, the tool derives a connection-count-aware default (≤ 15 → 80 px, 16–30 → 100 px, > 30 → 120 px). Pass an explicit `spacing` to suppress. The response reports `positionedContainers` — each container's id and the rectangle it actually landed at, read back after the write — beside the `groupsPositioned` count, so a container the pass never touched is visible rather than hidden inside a number. It also **accounts for every direct child of the view**: `skippedContainers` names each populated top-level container left standing with the type that made it ineligible, `unhandled` closes the population between the buckets, and `groupsPositioned + standaloneElementsPlaced + skippedContainers + unhandled` equals the view's direct-child count — an invariant the build enforces, so a shortfall can no longer hide inside a count with nothing to sum against. Naming a container's `viewObjectId` in `groupIds` arranges it alongside the zones **whatever its element type** (you can see the canvas and this tool cannot), which counts it in `groupsPositioned`, returns its rectangle in `positionedContainers` and removes it from `skippedContainers`. A wrong `groupIds` entry is reported on its own rather than voiding the others. **A container drawn inside a host is arranged too, and this is a visible behaviour change:** a `Grouping` inside a `Node` typing a cloud region has no zone above it, so it is top-level — the view is no longer refused with *No top-level groups found*. It cannot join the canvas grid, because its coordinates are stored relative to the host, so it is arranged **inside that host, in the host's own coordinate space**, and reported in `nestedContainersArranged` with the `hostViewObjectId` those coordinates are measured from. That field sits **outside** the four-bucket identity, which still partitions the view's direct children exactly. A host too small for the arrangement is declined whole — this tool never grows an ArchiMate element to make room — and the host itself is still listed in `skippedContainers`, with a reason saying the containers inside it were arranged. A container that is a **member** of another arrangement target is still denied as *not top-level* rather than *not found*: it moves with the target holding it |
| `optimize-group-order` | Reorder elements within groups to minimise inter-group edge crossings. Works on **all** top-level containers in the view simultaneously — native view groups and ArchiMate `Grouping` elements alike. That parity is about container **kind**; on **depth** it is narrower than `arrange-groups`, reordering the view's own containers only. A view whose containers are all drawn inside a host is refused with a message that says so and points at `arrange-groups`, rather than the former *View has no groups* |
| `resize-elements-to-fit` | Resize all (or selected) elements on a view to fit their labels using SWT font metrics. Two-pass algorithm for nested containment: children first, then parents. Optional `wrapFit: true` keeps each element's width and grows only its height so a long label wraps onto extra lines instead of being clipped or forced into an over-wide box — useful for nested labels in fixed-width containers (after a parent grows to contain a wrapped child, follow with `auto-route-connections`). **Sizes for label legibility only — not for connection fan-out.** For hub elements with high connection counts, use `detect-hub-elements` plus `update-view-object`. When a resize displaces objects anchored to its target, the enclosing group is fitted around the rectangles those children *land on*, not just around the object you named — so an anchored child can no longer be left hanging outside the group that was grown for it (the fit runs once, after every target in the pass has landed). The response reports both the groups it grew and the objects it displaced |
| `adjust-view-spacing` | Inflate inter-element and inter-group spacing on an existing view, then re-route in a single atomic operation. Use when a view is correctly laid out but visually cramped, without re-running ELK from scratch and losing manual placement intent. A container here is **either** a native view group **or** an ArchiMate `Grouping` element; both are inflated. **Density-aware default:** when `interElementDelta` is omitted on a view with `coincidentSegmentCount > 2` or `connectionEdgeCoincidenceCount > 4`, the tool derives a connection-count-aware default (≤ 15 → 60 px target, 16–30 → 80 px, > 30 → 100 px). Pass `interElementDelta: 0` to suppress. After spacing inflation, a post-pass detects any child element that overflows its parent group bounds and resizes the parent |
| `apply-element-spacing-recommendations` | Convenience tool that runs an embedded observe → decide → density-aware-terminate control loop to inflate within-group element spacing. Per iteration it takes a small monotone step, re-runs `assess-layout`, and continues / escalates / stops; a degrading step is reverted as the loop's own six-input step scalar measures it — that scalar cannot see cousin overlaps, off-canvas placement or `overallRating`, so the tool can return a view rated worse than the one it was handed and reports that as a `SPACING_RATING_REGRESSED` structured warning rather than leaving it silent. Hub-aware tier selection (80/100/120 px) when some element on the view carries **more than 6** connections. Note this is a higher cut than the `≥ 5` hub-*candidate* threshold below: a non-empty `detect-hub-elements` result means only that the view is connected, not that it has a large hub. Returns before/after `assess-layout` snapshots plus `terminationReason` / `iterationCount` / `appliedDeltas[]`. One call = one undo step. Set `dryRun: true` to preview |
| `apply-group-spacing-recommendations` | Sibling-symmetric convenience tool that runs the same embedded control loop to widen inter-group corridors only — preserves group ordering and topology. Hub-aware connected-pair tier rises to 100/140/160 px. Returns before/after `assess-layout` snapshots plus `terminationReason` / `iterationCount` / `appliedDeltas[]`. `dryRun: true` previews. With hub sizing and `apply-element-spacing-recommendations` this completes the routing-preconditions triad |
| `apply-spacing-recommendations` | Composed convenience tool that runs TWO coordinated control loops (element arm, then inter-group arm) in one transactional call. The `scope` parameter (`both` / `element` / `group`) selects which arm(s) run. The inflation-knee constants are **per-iteration step caps** (+80 px element / +100 px inter-group per iteration), preventing cumulative-inflation-past-the-knee without a fixed per-call ceiling; `elementKneeClampApplied` / `groupKneeClampApplied` surface when a step cap fires. Reports per-arm `terminationReason` / iteration counts / applied deltas. Use when both axes need adjustment; single-arm siblings cover single-axis changes |

### Layout Assessment & Analysis (2 tools)

> Metric acronyms below — M1–M6, R8, `parallelConnectionGap_V_p10` (V_p10), HPQ — are defined in the [glossary](docs/glossary.md).

| Tool | Description |
|---|---|
| `assess-layout` | Assess view layout quality across the legacy 8-metric severity-tiered rating (overlaps, crossings, pass-throughs, coincident segments, non-orthogonal terminals, spacing, alignment, label overlaps) plus the perception-aligned metrics (M1 visible-segment-length non-orthogonal terminals, M2 interior terminations, M3 zigzags, M4 connection-vs-edge coincidence, M5 hub-port quality, R8 corridor utilisation) and the M6 two-dimensional rating (layout tier × routing tier). Also reports the informational `parallelConnectionGap_V_p10` signal (10th-percentile parallel-segment gap on the V axis, anchored at 13.30 px on the ArchiMate reference) for narrow-corridor regime detection. Informational detections cover label truncation, parent-label obscured by child, image sibling overlap (`imageSiblingOverlapCount` — now also examines specialization icons and sizes images from their true pixel dimensions, **clipped to the element box**, because Archi cuts an oversized image off at the box edge rather than drawing it outside, so an icon's phantom overhang no longer generates image crossings, border grazes or sibling overlaps in space where nothing is drawn), overlay-icon collisions across a containment pair (`overlayIconCollisionCount` / `overlayIconCollisionDescriptions` — a container's corner icon overlapping a nested child's own corner icon, which the parent-bucketed sibling detector above is structurally unable to see; `fill` images excluded on both sides, informational with zero rating impact), clipped notes (`noteClipCount` — a note whose text needs more height than its box, typically an explicit `height` that defeats auto-fit), and an element's own icon drawn over its own title (`ownIconOverLabelCount` / `ownIconOverLabelDescriptions` — the third icon axis, structurally invisible to the two above because the icon rect and the title share one element box; the remedy is per **view object**, so it must be repeated on every view showing the element). Being informational governs the rating, not the prose: every informational count is named in `suggestions` on any run where it is nonzero — with the remedy published for it and a pointer to the field or violator key listing the objects — and `ownIconOverLabelCount` is additionally named in `nextSteps`, while none of them enters a rating. A finding that no suggestion accounted for is named inline with its count by a terminal disclosure, whether or not other defects were reported; the mapping is recorded as each suggestion is added rather than read from a table of which metrics have remedies, because a threshold-gated remedy (`edgeCrossings` fires only above ten) is unexplained on the runs below its threshold. Connection-label overlap detection is render-calibrated (glyph box widened 1.35× to match Archi's rendering) and now flags a label rendered on its own endpoint box (the channel `auto-route-connections` clears via the Archi 5.10 Label Offset), while correctly ignoring labels suppressed with `showLabel: false` and labels already lifted clear by an applied Label Offset (offset-aware). The own-endpoint test combines an area-fraction rule with a box-coverage rule (catching a label blanketing a tiny endpoint box such as a junction), a short-segment promotion (lowering the bar when the label is wider than the first/last segment it anchors to), and a junction near-zero bar (a Junction renders as a solid dark shape with no readable interior, so a label grazing its own junction endpoint is flagged at ~5% — catching an oversized junction the box-coverage rule, which only fires on a tiny one, misses). A rating-affecting `hubNeighbourClearanceMin` (px) reports the tightest hub-edge-to-spoke-row clearance on any crowded hub face; a value below the 60 px crowding floor caps the layout tier so a hub enlarged until it crowds its neighbours can no longer rate `good` (`-1.0` = no measurable hub, no rating impact). Two route-quality detections affect the routing tier: `connectionThroughNoteCount` flags a connection routed straight through a note's box or an element's rendered image rectangle (binary-presence Tier-3R cap-good; complements the box-based `connectionPassThroughs`, which the routing tier takes the worse of so there is no double penalty), and `nonOrthogonalInteriorSegmentCount` extends the non-orthogonal-terminal check to mid-route segments (Tier-2R cap-fair, ratio-bucketed like its terminal sibling and combined with it by the worse). The terminal count is also published as its two disjoint halves — `zeroBendpointNonOrthogonalTerminalCount` (connections drawn as a straight line between two element centres, the ELK auto-layout signature, with no stored route to preserve) and `routedNonOrthogonalTerminalCount` (connections carrying stored bendpoints) — which sum to the unchanged `nonOrthogonalTerminalCount`, with matching `nonOrthogonalTerminalsZeroBendpoint` / `nonOrthogonalTerminalsRouted` violator keys beside the unchanged whole-population `nonOrthogonalTerminals` key. Each half is MEASURED where its connection is flagged rather than derived by subtracting the other from the total, which matters because the source and target terminal tests guard against different rectangles: a bendpoint-free diagonal suppressed on the source side and flagged on the target side would otherwise be filed as routed. The split is published because the halves have opposite remedies — the straight-line half is cleared with `mode='terminals-only'` and a full re-route would likely increase its crossings, while the routed half is re-routed by passing exactly its IDs to `auto-route-connections` as `connectionIds`, which confines the call to that half and leaves every other connection's bendpoints untouched. Two informational detections cover the route defects that survive a geometry change: `anchorDriftCount` / `anchorDriftDescriptions` flags a connection whose *stored route no longer matches the geometry it was computed for* — every bendpoint is stored twice, relative to the source centre and relative to the target centre, and both describe the same point at the moment the route was written, so moving or resizing an endpoint afterwards leaves the two reconstructions disagreeing by however far the element travelled (flagged above a 1 px representable-precision floor, with the measured drift reported per axis rather than as a flag). No shape-based dimension can see this and none ever could: the collector blends the two reconstructions into the single polyline every detector is handed, so the disagreement is *structurally* invisible rather than under-tuned — and the stored shape was correct when written, which is why the remedy names re-routing and deliberately does **not** name the straightener. That blend now uses **Archi's own render weight**, `(i + 1) / (n + 1)`, so all 21 path-reading detectors measure the polyline a reader actually sees: while a connection is drifted the drawn line is *sheared* rather than displaced — each point pushed off the halfway position by `(weight − 0.5) × drift`, furthest at the two terminal bendpoints, which is exactly where the terminal-geometry dimensions look. The collector keeps its element centres untruncated (rounding them would quantise away the sub-pixel disagreement `anchorDriftCount` exists to report), so the same bendpoint can differ between `assess-layout` and `get-view-contents` by up to `0.5 + n/(n + 1)` px — 1.0 at one bendpoint, 1.3 at four, approaching but never reaching 1.5. `lateralJogReversalCount` / `lateralJogReversalDescriptions` flags a connection that *doubles back through a sidestep too narrow to be routing around anything* — a four-point window whose two outer arms run in opposite directions along one axis, separated by a perpendicular jog of at most 8 px (mirroring `OFF_FACE_MIN_STUB_PX`, the clearance that already separates a hugging exit from a legitimate one) — reporting the four coordinates of each window. It is distinct from `zigzagCount` rather than a looser version of it: M3 needs three consecutive points sharing *one* axis, and the sidestep puts the two arms on two parallel lines, so no triple in the window shares an axis at any jog width. A connection already reported as a pass-through or a zigzag is skipped, so no route is counted under two reversal dimensions. Two further informational detections cover redundant collinear bendpoints (`connectionRedundantBendpointCount` — a removable point on a *horizontal or vertical* run whose deletion would not change the rendered orthogonal shape, distinct from `zigzagCount`; near-collinear diagonal micro-jogs and router-pinned terminal egress-stub ports are excluded, so a nonzero count means a genuinely collapsible interior point that a `terminals-only` re-route removes) and same-colour container/child "flat-blob" fills (`containerFillEqualsChildCount` — backstop for the auto-recede backdrop, firing only on an *authored* same-colour fill). Three further informational detections close the label/border blind spots: `connectionGrazesVisualCount` flags a connection touching or clipping a note's or image's *border band* (the outer strip the through-visual interior test discards, including visuals too small to inset — disjoint from `connectionThroughNoteCount`, so a crossing is classified as exactly one of through or graze); `labelOnNoteCount` flags a connection *label* rendered on a note's rectangle (the caption collision the route detectors cannot see, since a label is positioned independently of the line); and `labelOnGroupCount` flags a connection label on a visual group's *title band* (the title-strip collision the label-overlap detector skips, as it treats groups as transparent containers — a label in the group body is not flagged). Two more detections sharpen the terminal/edge picture — the first of them rating-bearing, the second informational: `offFaceParallelTerminalCount` flags a connection whose terminal route *departs* an element face then runs parallel hugging it (the first exterior segment travels along the departed face within 8 px of it) — the sub-pixel-off exit stub the visible-length guard suppresses in `nonOrthogonalTerminalCount`, resolved via the same terminal-slot face attribution, and the oracle `auto-route-connections` drives to zero. It is now **rating-bearing**: a Tier-2R entry capped at `fair` on binary presence, so a view carrying a real face-hug can no longer read `good` at the headline (ratio-bucketing was rejected — a low-ratio hug would still read `good`). It is deliberately *not* carved out of `overallExcludingAcceptedCosmetics`: a layout-bound hug is a real, actionable defect, not an accepted cosmetic; `coincidentFacePortCount` flags an element face on which two or more connection terminals collide onto the *same* perimeter port (two edges appear to leave one point) — the blind spot in `hubPortQualityScore` (M5), whose per-face guard never scores a face carrying only two or three coincident connections, so it reads a vacuous `1.0` despite the collision (informational, M5 untouched; the oracle the router's coincident-port dissolution drives to zero); and the M4 edge-coincidence detector now enumerates *every* grazed element via `edgeCoincidenceGrazedElementCount` with an `edgeCoincidenceGrazedElements` element-id violator key, while the rating-bearing `connectionEdgeCoincidenceCount` and its connection-id `edgeCoincidence` key stay byte-identical (counted once per connection). The headline rating is also de-noised: `ratingBreakdown.overallExcludingAcceptedCosmetics` recomputes `overallRating` with the `nonOrthogonalTerminals` contribution removed (diagonal terminal segments are the straight-line signature of ELK auto-layout), as a floor that can only equal or improve `overallRating` — when the two differ, the gap is terminal cosmetics only (clear with `auto-route-connections` mode `terminals-only`, or accept); when equal, the rating reflects a real defect. A `coverage` map declares, per defect dimension, whether the run actually evaluated it (`checked` — detector ran and fully covers the dimension; `partial` — covers only some failure modes, so render-verify the rest; `not-checked` — no detector, absence is unknown; `not-applicable`), so a done-gate can tell "checked and clean" from "partially checked" from "never looked" by reading both `coverage` and `ratingBreakdown`; informational, never affects a rating. Most dimensions report a fixed registry level; the exceptions come in two kinds. *Contextual* — declared `checked`, downgraded to `partial` only on a run that triggers them: `labelOverlaps` on any run where a label is wider than its hosting segment (a label can crowd a neighbour box while still clearing it geometrically, so the overlap count is honestly zero yet the crowding mode is unverified), `ownIconOverLabel` on any run carrying a named group with an overlay icon (a group's title band has no measured width to test against, so that object was never examined), and `parentLabelObscured` on any run carrying a parent whose title band width was never measured — a visual group, or an element whose label measurement failed (that parent *is* examined and does get a verdict, but its band is sized as a single line however long the title is, so a title that wraps onto further rows is compared against only its first row and a child under the rest is not flagged; render-verify such a parent's title against its topmost child). *Permanent* — reported on every run because the gap is structural: `edgeCoincidence` is always `partial` (its detector classifies each segment as horizontal or vertical and skips everything else, so a diagonal segment is never compared against an element edge); `labelTruncations` is always `partial` (a visual group's title is never measured and the node is skipped before its box is consulted, as is an element whose label width could not be measured — so a zero certifies only that every *measured* element label fits, and a done-gate must render-verify group titles); and `corridorCentering` is a standing `not-checked`, making explicit that `corridorUtilisationScore` measures multi-occupant corridor *spread*, not whether a single route centres versus hugs an edge. M3 (zigzag) skips connections already classified as pass-throughs so a single connection is not double-labelled. Self-element pass-throughs are reported but excluded from rating. Two overlap/boundary readings are worth knowing apart: `overlapCount` is a **same-parent** count, so a collision between two objects in different branches of the containment tree is reported through their *containers* rather than through the objects themselves — the informational `cousinOverlapCount` / `cousinOverlaps` (with its own violator key) names every pair of differently-parented, non-nested objects whose rectangles intersect, counted in the same pass and carrying no rating weight; and `boundaryViolations` is a *capped* description list, so `boundaryViolationCount` is the true uncapped number and is the one the rating, the Tier-1L regression veto and the iteration loop read. `connectionPassThroughs` is likewise a *capped* description list and additionally names the self-element pass-throughs the rating does not charge, so `crossElementPassThroughCount` is the uncapped cross-element number, and it is the one the rating, the Tier-1R regression veto, the iteration score and the spacing loop's step scalar read. A **degenerate view** (at most one object) still does not receive a rating — spacing and alignment have no data below two objects, and scoring them would rate a pristine one-object view `fair` for having nothing to compare against — but it is no longer silent: the detectors that *are* computable on a single object (`offCanvas`, `labelTruncations`, `ownIconOverLabel`, `noteClip`) run and report, and every coverage dimension is declared, `not-applicable` only where the failure mode structurally needs two or more objects. The `nextSteps[]` envelope names the right precondition tool with violator IDs attached when an overlap, boundary violation, low hub-port-quality score, or grouped-view spacing/crossing breach is detected — and, for a near-saturated container-nested-hub view, emits a single diagnostic resize-vs-reposition step (gated on the clearance reading) instead of generic spacing inflation. The off-face parallel-terminal remedy is router-consistent and honest: a confident layout fix when the local corridor is below the healthy floor, otherwise a deferral to `auto-route-connections` (cross-referencing the `EGRESS_LIFT_LAYOUT_BOUND` warning) rather than over-promising a perpendicular re-route, and on a contested hub face (two or more connections sharing it) it names *spreading the connections* rather than a plain widen. **Both container kinds are assessed as containers.** Every detector that reasons about what the canvas *shows* — `hasGroups`, alignment participation, corridor walls, hub detection, pass-throughs, image-vs-connection, label overlaps, label-position exhaustion, `labelOnGroupCount`, own-endpoint overlap fractions, note containment and the pre-route stacked-position warning — treats a native view group and an ArchiMate `Grouping` element alike, so a view of `Grouping` zones is no longer reported as having no groups while its zone rectangles are counted as pass-throughs and label overlaps. Detectors that reason about *measurement* keep the narrower reading, because a `Grouping` carries a measured label width and a native group does not. **Title geometry is read from the object, not assumed.** The band a title occupies is placed from that object's own `textAlignment` *and* `verticalTextAlignment` rather than being modelled as centred-and-top, and it is clipped to the object's own height — Archi clips a figure's contents to the figure, so a wrapped title on a thin container cannot be reported as extending past its own bottom edge. A container with no usable height has no title band at all. Where a title was never measured — a group's title, or an element whose label measurement failed — the dimension reports `partial` instead of certifying a clean result it did not earn. Optional `includeViolatorIds` returns per-metric visual object IDs for targeted fixes. Optional `scope` selects `single` (default — the one `viewId`) or `all-views`, which assesses every diagram and returns a compact per-view map (`{name, overallRating, overallExcludingAcceptedCosmetics, elementCount, connectionCount, overlapCount, cousinOverlapCount, ownIconOverLabelCount, boundaryViolationCount, parentLabelObscuredCount, nonOrthogonalTerminalCount, crossElementPassThroughCount, contextualPartialDimensions}`) for a one-call close-out sweep, then drill into any `fair`/`poor` view with a single-scope call. `contextualPartialDimensions` names the dimensions that view's run could not fully examine, so every zero beside it on that view is honest yet certifies nothing — it lists only the *contextual* downgrades, since the permanent `partial` declarations and the standing `not-checked` are present on every run and are read from the full `coverage` map. The key is **always present**; an empty list means nothing was left unexamined on that view, which is a measured result and not a missing field, so a close-out sweep can tell "nothing to render-verify" from "the sweep did not say" **Fan-out sizing preconditions.** The response carries an `unsizedHubs` block naming every element whose connection count is above the fan-out gate and whose box is below the absolute floor for that count (`300 + 10 × max(0, count − 7)` wide, `250 + 8 × max(0, count − 7)` tall), each row carrying the element and view-object ids, the name, the connection count, the box it has and the box it needs. Deliberately the floor and **not** `detect-hub-elements`' growth suggestion, which is relative to the current size and so can never be satisfied. The block answers on a view with **zero stored bendpoints**, which is where `hubPortQualityScore` is vacuously 1.0 — so it is the one signal available at the moment sizing a hub is still cheap, before the first `auto-route-connections`. It feeds no rating: no breakdown entry, no tier, no coverage row. |
| `detect-hub-elements` | Identify hub elements by counting visual connections per element, sorted descending. Hub thresholds: ≥ 5 connections is a hub candidate; > 6 connections gets an explicit 1D sizing suggestion `baseDimension + 15px × (connectionCount − 6)`; for elements with > 12 connections the response also surfaces a 2D-resize suggestion (`width += 15 × ⌈excess/2⌉`, `height += 15 × ⌊excess/2⌋`) so the agent can distribute ports across all four edges. The assessor's internal `M5_FACE_GUARD_MIN_CONNECTIONS = 4` is a separate per-face guard for the M5 hub-port-quality metric, not a hub-detection threshold |

### View Operations (1 tool)

| Tool | Description |
|---|---|
| `auto-connect-view` | Create visual connections for all existing model relationships between elements already placed on a view. Optional `showLabel: false` to suppress labels, and optional `lineColor`, `fontColor`, `lineWidth` to batch-style every connection it creates (combine with `relationshipTypes` filter to style by type). Optional `relationshipIds` is an explicit allow-list for a curated slice — a strict producer → hub → consumer cut that a type filter cannot express — AND-composed with `elementIds` and `relationshipTypes`; an unknown id fails fast with `RELATIONSHIP_NOT_FOUND` **before any mutation**, while a valid-but-undrawable id keeps the existing silent skip |

### Folder Management (5 tools)

| Tool | Description |
|---|---|
| `get-folders` | List folders (root-level by default, or children of a specific folder) |
| `get-folder-tree` | Folder hierarchy as a nested tree structure |
| `create-folder` | Create a new subfolder |
| `update-folder` | Update folder name, documentation, or properties |
| `move-to-folder` | Move a model object (element, relationship, view, or folder) to a different parent folder. A move into a folder whose ArchiMate layer is illegal for the object is rejected with `FOLDER_LAYER_MISMATCH`, the same fail-fast check `create-element` applies |

### Deletion (4 tools)

| Tool | Description |
|---|---|
| `delete-element` | Delete an element — cascades relationships and view references across all views |
| `delete-relationship` | Delete a relationship — cascades view connections across all views |
| `delete-view` | Delete a view and its visual contents (model elements preserved). Reports the cascade it performs: `viewConnectionsRemoved` (recursive and de-duplicated, so a connection nested inside a group is counted) and `viewReferencesRemoved` (placeholders pointing at this view, scrubbed from the other views that held them — those placeholders are disconnected, so no dangling connection is left behind in a surviving view) |
| `delete-folder` | Delete a folder (requires `force: true` for non-empty folders). A `force` cascade covers sketch and canvas views as well as ArchiMate diagrams, and folds a contained view's own external placeholders and connections into the reported totals |

### Export (1 tool)

| Tool | Description |
|---|---|
| `export-view` | Render a view as PNG, JPG, SVG, or PDF — returned inline (base64 / blob) or written to file. Optional `quality` (1–100, default 90) for JPEG encoding; ignored for other formats. Vector formats (SVG, PDF) leave `width`/`height` unset because they are resolution-independent. Optional `outputDirectory` controls where files are saved (auto-creates directories; defaults to temp). SVG and PDF require the `com.archimatetool.export.svg` bundle that ships with Archi 5.7+. A raster export is projected to its pixel dimensions and byte estimate **before** any bitmap is allocated and refused when the machine cannot back it — Archi renders one bitmap covering the whole diagram, so a `scale` well inside the accepted 0.1–4.0 range can still ask for a buffer larger than free memory, which is a native fault rather than a catchable error. The refusal is an `INVALID_PARAMETER` naming the projected dimensions, the estimate, the budget, and the largest `scale` that would have fitted; `svg` and `pdf` allocate no bitmap and are never refused this way. Each response carries the model's monotonic mutation counter as `modelVersion` (inline mode: top-level field; file mode: in `_meta`) — compare it *numerically* against a later `assess-layout` `_meta.modelVersion`: a lower export value means the model changed after the render, so the image is stale and should be re-exported before a visual close-out |

### Images (2 tools)

| Tool | Description |
|---|---|
| `add-image-to-model` | Import an image (icon) into the model archive. Preferred: `filePath` (local file) or `url` (HTTP download) — these bypass LLM text channel and avoid base64 corruption. Fallback: `imageData` (base64). Provide exactly ONE per image. Importing a set of icons? Pass an `images` array instead of calling the tool once per icon — one call imports them all and reports each image's archive path plus any entry that failed, so a partial batch never needs re-sending whole. Not available inside `bulk-mutate`: an archive write is not undoable, so it can neither be rolled back nor deferred. Returns the archive `imagePath` for use with view composition tools. Images are deduplicated |
| `list-model-images` | List all images stored in the model archive with their paths and dimensions. Use the `imagePath` values with view composition tools to set images on elements, groups, or notes without re-importing |

### Batch & Mutation Control (4 tools)

| Tool | Description |
|---|---|
| `begin-batch` | Start batch mode — mutations are queued instead of applied immediately. A batch can build on what it has already queued: create a view and add objects to it, create an element and place it on a view, update or re-parent an object the same batch created, and anchor to an object that has not been written yet. Connect two elements the batch just placed, update a connection it just added, embed a view it just created, and lay the whole result out before committing — `add-connection-to-view` resolves all four of its ids against the queue, `apply-view-layout` all three of its, and a queued endpoint is scoped to the view the connection lands on. Every parent-fit cascade in the batch is measured against one shared same-batch geometry, so a size queued earlier survives a later pass over the same view; anchors are resolved when the command runs, not when it was queued. A `bulk-mutate` may be nested inside the batch: it is queued like any other operation, and an id the batch has queued resolves there on every tool that places something on a view — as the view placed on, the parent nested into, and the element placed — and as an `update-view-object` or `update-view-connection` target, and in all four of `add-connection-to-view`'s ids whether or not that operation also uses a back-reference of its own; tools that update, delete or re-file a concept report such an id as not found |
| `end-batch` | Commit all queued mutations atomically, or rollback (discard all) |
| `get-batch-status` | Check operational mode and queued operation count. `approvalRequired` is either `true` or absent (never `false`), `pendingApprovalCount` is absent rather than `0` on an empty queue, and queue fields are absent outside batch mode |
| `bulk-mutate` | Execute multiple mutations as a single compound command with back-references and optional `continueOnError`. A back-reference names an earlier operation either by position — `$N.id`, 0-indexed — or by a name that operation declares with `as`, written `$name.id`; a create tool cannot reference its own not-yet-created result. A reference must be the **whole value** of a top-level `params` entry — `"$0.id"` is substituted, `"grp-$0.id"` is not, and neither is one nested inside an array or object; anything not substituted is passed through as the literal text, which is not an id, so the operation fails naming the `$0.id` it was handed. Prefer the name in a long call: every position below the current one is a legal earlier operation, so a mistyped position resolves silently into a different, legal container, while a mistyped name matches nothing and is refused — including inside an open batch and while awaiting approval, where no response field reports the container at all. An operation carrying a top-level key other than `tool`, `params` and `as` is refused naming the key, rather than having it discarded in silence. Such a reference to an `add-group-to-view` or an `add-note-to-view` works as the `viewObjectId` of a later `update-view-object` in the same call, so a group can be created and then re-sized (or renamed via `text`) and a note created and then re-texted — which re-fits its height — without ending the call. A note is such a target but is never a valid `parentViewObjectId`: only groups and element view objects can contain anything. Each operation reports `effectiveBounds` re-read from the model after the write, plus `resizedAncestors` (containers the operation grew) and `movedObjects` (anchored children it displaced); an operation on a view connection reports `effectiveConnection` — the styling, bendpoints, endpoints and anchor points the connection holds afterwards, the same report the single-tool caller gets, so batching work no longer costs the connection facts calling one at a time would have given you; an operation on an ArchiMate concept reports `effectiveRelationship` or `effectiveElement` the same way, which is how a bulk caller who sets `accessType`, `associationDirected` or `influenceStrength` — or rewrites an element's `documentation` — is told what those became rather than only that the call succeeded; deletions carry the standalone tool's own cascade counts; and `entityName` is re-read once the whole call has been applied, so a rename — or a clear to `""` — is confirmed by the name the entity ends up with rather than the one it had when the operation was prepared (a delete is the exception, since its id stops resolving the instant it applies). An operation declined by an execute-time integrity guard is reported in `skippedOperations` with a named reason and flips `allSucceeded`, rather than coming back as done. A call queued into an open batch or parked awaiting approval reports **no** `allSucceeded` verdict at all — nothing has executed, so its operations can still decline at commit — and says so through its `batch` or `proposal` sibling instead. Among its operations is the view-scoped `set-view-label-expression`, which stamps one `labelExpression` template (e.g. `${name} ${property:evidenceMark}`) onto every eligible object on a view in a single atomic op — retroactive, idempotent, and type-filterable (default scope is ArchiMate **element** objects; optional `objectTypes` widens to notes/groups). A non-blank-name guard skips a blank-named object when *setting* (no half-empty glyphs) but is bypassed when *clearing*; the per-op result reports `appliedCount` / `skippedCount`. One sweep = one undo unit |

### Undo / Redo (2 tools)

| Tool | Description |
|---|---|
| `undo` | Undo the most recent mutation operation(s) with optional step count. **Scoped to the agent's own changes:** stops at — and refuses to cross — any human edit on the stack, so the agent can never silently revert hand-drawn work (to undo a human change it must submit a new proposal). The human's native Ctrl+Z is unscoped and still undoes anything |
| `redo` | Redo previously undone operation(s). Scoped to the agent's own changes, symmetric to `undo` |

### Approval Workflow (1 tool)

The approval **gate is owned by the human** in Archi (a desktop toggle), not by the agent. The MCP surface exposes only a read-only observation tool — the agent can see that changes are gated but cannot enable/disable the gate or approve its own queued changes.

| Tool | Description |
|---|---|
| `list-pending-approvals` | List pending mutation proposals awaiting the human's approval (read-only) |

### Session Management (2 tools)

| Tool | Description |
|---|---|
| `set-session-filter` | Set persistent filters and field selection that apply to all subsequent queries |
| `get-session-filters` | Retrieve currently active session-scoped filters and field selection |

### Reference & Guidance (1 tool)

| Tool | Description |
|---|---|
| `get-guidance` | Read the ArchiMate reference material, workflow guides and viewpoint recipes this server ships. Call with no arguments to list every available `archimate://` URI; call with `uri` for the full body. Same content as the [MCP resources](#mcp-resources), reachable by clients that do not expose resource reads to the model (read-only) |

### Response Control

All query tools support response optimisation parameters:

- **Field selection:** `fields` param with presets (`minimal`, `standard`, `full`) or custom field arrays
- **Field exclusion:** `exclude` param to omit specific fields
- **Pagination:** Automatic for large result sets, with cursor-based continuation

**How far a preset reaches on `get-view-contents`.** A view response carries seven arrays, and `fields`
routes only two of them — the element and relationship rows — through its verbosity filter.
`visualMetadata`, `connections`, `groups`, `notes` and `images` go in whole at every preset, and on a
view carrying connections those five are usually the larger half, so **`exclude` rather than `fields`
is normally the parameter that moves the response size**. Both apply to `format: "json"` and
`format: "graph"` only: `format: "summary"` and `format: "tree"` return before the field selector
runs, so neither parameter reaches anything there.

## MCP Resources

The server provides 14 reference resources accessible to LLM clients.

They are published three ways so that every client can reach them: as static MCP resources, as
the equivalent resource templates (`archimate://prompts/{name}`, `archimate://reference/{name}`,
`archimate://recipes/{name}`), and through the `get-guidance` tool. Clients that do not expose
MCP resource reads to the model can call `get-guidance` with no arguments to list every URI, or
with a `uri` to read one. See [MCP Resources](docs/mcp-integration.md#mcp-resources) for detail.

### Prompts

| URI | Description |
|---|---|
| `archimate://prompts/model-exploration-guide` | Strategy guide for LLMs on how to efficiently search and traverse ArchiMate models |
| `archimate://prompts/explore-dependencies` | Workflow template for systematic dependency analysis of ArchiMate elements |
| `archimate://prompts/landscape-overview` | Workflow template for generating architecture landscape summaries |
| `archimate://prompts/routing-preconditions-checklist` | Single-page checklist for LLM agents to fetch before invoking `auto-route-connections` or `auto-layout-and-route` on any non-trivial view. Three preconditions (hub sizing, inter-element spacing, group arrangement + corridor-friendly spacing), one verification each, plus a disposition matrix mapping view shape to precondition order |

### References

| URI | Description |
|---|---|
| `archimate://reference/archimate-layers` | Comprehensive mapping of ArchiMate layers to element types with descriptions, plus a concept-to-element-type decision aid (Component vs Service, Process vs Function, Node vs Component, Actor vs Role) |
| `archimate://reference/archimate-relationships` | All ArchiMate relationship types with valid source/target combinations and usage guidance |
| `archimate://reference/archimate-specializations` | Specialization (IS-A subtype) vocabulary: when to use specializations vs properties, common patterns per layer, and the discovery/create/audit/delete tool pipeline |
| `archimate://reference/archimate-view-patterns` | Curated viewpoint patterns, layout algorithm guidance, and diagramming best practices for composing ArchiMate views |

### Viewpoint Recipes

A progressive-disclosure recipe library for laying out non-conventional ArchiMate viewpoints. Fetch the index first; it states the invariant build sequence once, routes conventional viewpoints (layered, landscape, hierarchy, information structure) to the view-patterns principles, and points non-conventional viewpoints to a single full recipe page with the topology block to match.

| URI | Description |
|---|---|
| `archimate://recipes/index` | Selector — fetch first. Conventional-vs-non-conventional decision table, the invariant build sequence, and the family selector |
| `archimate://recipes/application-integration` | Hub-and-spoke recipe for Application Cooperation / Integration views |
| `archimate://recipes/behaviour-process-flow` | Swimlane recipe for Business Process Cooperation, plus the Service Design / Customer Journey band layout |
| `archimate://recipes/motivation` | Directed influence-chain recipe for Motivation views (stakeholder → driver → assessment → goal → requirement) |
| `archimate://recipes/technology-deployment` | Nested-deployment recipe — nodes as containers with deployed software/artifacts, wired by network paths |
| `archimate://recipes/roadmap-migration` | Left-to-right plateau-timeline recipe for Implementation & Migration views |

## Prompt Library

Separate from the MCP resources above (which an LLM client fetches over the protocol), the top-level [`prompts/`](prompts/) folder holds **ready-to-use prompts you paste into an agent session** that has the Archi MCP tools connected. Each file is self-contained: replace any `{{PLACEHOLDER}}` tokens and run.

| Prompt | Purpose |
|---|---|
| [`prompts/repo-to-archimate-model.md`](prompts/repo-to-archimate-model.md) | Reverse-engineer a code repository into a full, evidence-marked **ArchiMate 3.2** model — elements, relationships, folders, and a Context → Landscape → Component stack of views, with every concept marked by evidence strength. Iterative and resumable (the open Archi model is the checkpoint); can target a specific tag, release, branch, or commit. |
| [`prompts/drawio-to-archimate-model.md`](prompts/drawio-to-archimate-model.md) | Replicate a **draw.io** architecture diagram as an ArchiMate model — elements, relationships and a view, with every concept marked by whether it was **read from the source or derived by rule**. Parses the mxGraph XML directly (no rendering), infers containment from geometry, maps cloud-stencil tokens to `(type, specialization)` pairs, and reports what it could *not* resolve rather than inventing it. Two layout modes: `semantic` (laid out from the ArchiMate view recipes) and `mirror` (laid out to resemble the source drawing). Takes the diagram's accompanying design document as an optional second input — which is what turns unlabelled arrows into real relationship types — and vendor icon packages as optional further inputs. The diagram may be a **path, pasted mxGraph XML, or an attachment on a Confluence page**, and the companion document likewise a path, a pasted body, or that same page; the wiki route documents both draw.io macro generations (the Forge/ADF `diagram-name` and the legacy `diagramName`) because a grep for one returns zero on the other, treats a page as a *set* of diagrams needing an exact filename match, and marks its attachment-download half unverified. A deflate+base64 `<diagram>` payload is detected before the first trap rather than read as an empty drawing. |
| [`prompts/archimate-model-to-pdf-report.md`](prompts/archimate-model-to-pdf-report.md) | The read-side companion: turn any open Archi model into a print-ready **PDF architecture report** — a cover page with honest provenance (the model's own version / date / author properties plus a generation timestamp), an executive summary, every view in an optimal reading sequence with its exported diagram, per-view element tables and notes, then a full element catalogue and relationship register. Optionally embeds the model's own generating prompt as an appendix. **Read-only** — query tools only, never mutates. Tool-agnostic (WeasyPrint preferred for page-numbered footers; a Chromium browser works as a fallback). |

`archimate-model-to-pdf-report.md` also ships with an optional reference implementation, [`prompts/archimate-model-to-pdf-report.py`](prompts/archimate-model-to-pdf-report.py) — a standalone generator that talks to the MCP server over its loopback HTTP endpoint, harvests the model, exports each view and renders the PDF in one command, with `--full` for complete element and relationship coverage including concepts placed on no view. It needs only the Python standard library plus the `weasyprint` CLI.

Two of these are also distributed as Claude Code slash commands, each embedding a **self-contained copy** of its prompt plus an argument-parsing preamble: `repo-to-archimate-model.md` as **`/repo-to-archi`** (adding a repository argument and a clone step), and `drawio-to-archimate-model.md` as **`/drawio-to-archi`** (taking the diagram source — a path, pasted mxGraph XML or a Confluence page — the layout mode, an optional companion document and optional icon packages). The command copies do not auto-update — `prompts/` is the source of truth, so re-sync after editing a prompt here. For `/drawio-to-archi` the sync is mechanical: run [`tools/sync-drawio-command.sh`](tools/sync-drawio-command.sh) to regenerate the copy, or `--check` to fail a build when it has drifted.

## Mutation Safety

All write operations integrate with Archi's **CommandStack**, making every mutation **undoable** via `Ctrl+Z` in Archi or the `undo` / `redo` tools.

**Verbatim-store validation.** A name, label, view-object text, or `labelExpression` containing a well-formed HTML/XML entity token (`&amp;`, `&lt;`, `&#160;`, `&amp;amp;`, …) is **rejected with a corrective hint** rather than silently stored as the escaped literal — the recurring `&amp;` footgun. Bare ampersands and non-entity text (`R&D`, `A & B`, `<tag>`, `${name} & ${property:x}`) are accepted and stored byte-for-byte. The rule covers `create-*` / `update-*` / `add-*` and their `bulk-mutate` equivalents.

**Integrity guards hold on deferred paths.** A precondition checked when a command is *built* is worthless once the command is *queued*, because a sibling operation in the same batch can invalidate it before the command runs. Folder cycles, view-hierarchy and folder-layer placement, and specialization usage are therefore re-checked at **execute time**, which makes them path-agnostic — the same guard protects the immediate, batch, bulk and approval paths. A guard that trips **declines the single operation with a named reason** (surfaced in the batch summary and in `bulk-mutate`'s `skippedOperations`) instead of proceeding: a folder move that would form a containment cycle, for instance, previously threw nothing and left the affected folders unreachable from the model root. A declined nested folder is also protected from its own ancestor, so an outer cascade cannot remove by containment what an inner decline just saved. The same rule covers the *container a create or a placement targets*: `create-view`, `create-folder` and `clone-view` resolve a folder when the request is prepared and create into it later, and `add-to-view` resolves a parent container the same way — so an object whose destination was deleted earlier in the same request is now declined rather than created unreachable from the model root (really written, gone on reload, and reported as a success). A view-nesting `parentId` belonging to a *different* view is rejected with it, as are the connections a placement draws for itself: `add-to-view` with `autoConnect` and `auto-connect-view` both scan the view at prepare time, so a preceding `remove-from-view` could otherwise leave a connection joined to a detached endpoint.

**Approval mode** adds a human-in-the-loop gate that the **human owns** — the agent operates tools but can never move the gate or approve its own changes:

```
Human toggles "Approval Mode" ON in Archi (MCP Server menu)
  → agent's mutations queue as "pending" (not applied)
  → agent calls list-pending-approvals → sees what's gated, tells the user to confirm in Archi
  → human approves/rejects in Archi → apply, or discard
```

The toggle lives only on the human side: there is no MCP tool to enable/disable approval or to approve a proposal. Your choice is **remembered across restarts** (stored in MCP preferences); a fresh install defaults to **GATED** (fail safe). Turning the gate **off** requires a confirmation in Archi; turning it **on** is a single click. Approval mode is a single global switch (one human, one desktop, one gate), and the agent can read the current state via `get-model-info` (`approvalMode`).

**Pending Approvals view.** When approval mode is on, you review and apply the agent's queued changes in the **Pending Approvals** dock view inside Archi. Open it from **Window → Show View → MCP Server → Pending Approvals**, or from the **MCP Server → `Pending approvals (N)`** menu item (which also shows the live count). Each gated tool-call — including a whole `bulk-mutate` — is **one card**, showing a plain-language effect rollup with destructive counts (deletes/removals) called out in amber. **`Show changes`** expands the card to named, verb-prefixed rows (deletes hoisted to the top) with a `Technical details` disclosure for the raw parameters. **`Approve`** applies the change (a card containing a delete stays disabled until you expand it once); **`Reject`** discards it with no change to the model. Toolbar **`Approve all safe`** clears the purely-additive cards in one click, and **`Approve all ⚠`** drains everything after one confirmation that names the deletion count. New gated changes appear live without a refresh.

The card's effect text is **generated by the server from the model's own truth**, so you can decide from the card alone without opening `Technical details`: relationships name both endpoints (`Create ServingRelationship: 'Payment Gateway' → 'Fraud Engine'`, and a delete adds its cascade), a `delete-view` or `delete-folder` card shows the blast radius the agent was given (`(cascade: 11 view connections, 1 view reference)`, `(force cascade: 1 view)`) rather than the bare target id, and a `bulk-mutate` card's rows are named per operation (`+ Create "Payment Gateway" (ApplicationComponent)`, `↔ Connect "Payment Gateway" → "Fraud Engine" (ServingRelationship)`). Agents may pass an **optional `intent`** on `begin-batch` or `bulk-mutate` (a plain-language "why"); when present it shows as a quiet `agent's note:` line **below** the effect — it never replaces or outranks the server effect, and an empty or generic note is dropped. Effect is non-spoofable server ground truth; intent is a separate, lower-trust claim the server never depends on.

**The card describes the whole write, not part of it.** Everything you see on a card — the headline sentence, the expanded rows and the `Technical details` disclosure — is derived from one map the server builds when the change is proposed, and that map is also what an agent reads back through `list-pending-approvals`. Two things follow, and both are now enforced rather than maintained by hand. **Every parameter the approval will apply is in that map:** a tool that accepts a parameter and writes it must disclose it, so you are never authorising an aspect of the change the card kept quiet about. A contract test parses every proposal site in the server and fails the build unless each one is complete or carries a registry line naming the exact parameters it exempts and why. **And the card's own sentence names the aspects that will change**, derived from that same map rather than written by hand: renaming a box is announced as a rename, restyling a connection is not announced — or validated — as a reroute, and a call that discloses nothing says so instead of claiming a change it will not make. Under-disclosure hides a write; over-disclosure invents one, and the card is held to both ends.

**The gate refuses rather than surprising you.** The card is built when the change is proposed, while the command that runs is rebuilt against the current model at approve — so if the blast radius has *grown* in between (contents dragged into a folder already proposed for a force-delete, say), approval is refused and both numbers are named, instead of deleting five things against a card that said two. A refusal raised while rebuilding is now surfaced with its own reason and remedy (`FOLDER_NOT_EMPTY` … "Use `force: true` to cascade-delete all contents") rather than the generic "a targeted object was changed or removed", and **Approve All** reports that same reason when it stops instead of guessing. Cards also say **which kind of gate you are looking at**: most proposals re-run their preparation against the current model when you click Approve, but fourteen — the layout, routing and `bulk-mutate` passes — apply the exact compound you reviewed, because re-running a layout can legitimately produce a different result from the one on the card.

**Batch mode** groups mutations into atomic transactions:

```
begin-batch()
  → create-element(...)
  → create-relationship(...)
  → add-to-view(...)
end-batch()
  → All succeed together or all roll back
```

A batch can **build on what it has already queued**. Inside one `begin-batch` / `end-batch` window
you can create a view and add objects to it, create an element and place it on that view, update a
view object the same batch created, use a container the same batch created as a `parentId`, and
anchor to an object the batch has queued but not yet written — from `bulk-mutate` running inside the
batch as well as from the standalone tools. Every parent-fit cascade is measured against one shared
same-batch geometry, so two independently-queued cascades can no longer emit competing absolute
resizes of the same grandparent, and a queued size survives a later pass over the same view. Editing
the same object twice in one batch applies only the fields each edit names — and a second edit that
changes only a label or styling no longer discards bendpoints the first edit supplied. Where an id
genuinely cannot be resolved the error names the mechanism — that the object exists but is owned by
the open batch — rather than reporting it as not found.

That covers the whole script an agent runs to draw a view in one unit of work. `add-connection-to-view`
resolves **all four** ids it takes (both endpoints, the relationship and the view), so a batch can place
two elements and connect them; `apply-view-layout` resolves **all three** of its (the view, each
positioned view object, each connection), so the result can be laid out before the batch ends;
`add-view-reference-to-view` resolves the *referenced* view as well as the host; and
`update-view-connection` can update a connection the same batch added. A queued endpoint is scoped to
the view the connection lands on, so a batch cannot draw a connection whose two ends sit on different
diagrams. Tools that update, delete or re-file a *concept* still report a queued id as not found — the
standalone call inside the same batch is equally blind, because both callers share one prepare.

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

**Troubleshooting: secure storage ("Secure storage unavailable" when saving the token or keystore password)**

The bearer token and the TLS keystore password are stored in Equinox secure storage, encrypted under an OS-protected master password (the **macOS Keychain** on Mac, the **Windows Credential Store / DPAPI** on Windows). If that OS-protected master password entry is lost or locked, saving fails with *"Secure storage unavailable."* This is an environment/credential-store state issue, not a data problem — the app **never** falls back to storing the secret in cleartext, and any value already configured is left untouched. (On macOS the underlying error in the log reads `SecurityException: Could not obtain password. Result: -25300`; on Windows it surfaces as a `StorageException` reporting the master password could not be obtained.)

To recover, reset secure storage so Equinox can create a fresh master password:

1. **Quit Archi.**
2. Move aside the secure-storage file (a backup, so it's reversible). It lives in the Archi instance area:
   - **macOS:**
     ```bash
     mv ~/Library/Application\ Support/Archi/secure_storage \
        ~/Library/Application\ Support/Archi/secure_storage.bak
     ```
   - **Windows** (PowerShell):
     ```powershell
     Move-Item "$env:APPDATA\Archi\secure_storage" "$env:APPDATA\Archi\secure_storage.bak"
     ```
   If the path differs on your setup, the exact location is shown in the `.metadata/.log` file referenced below.
3. **Restart Archi.** Equinox recreates the store automatically (you may get a one-time OS prompt to allow access to the Keychain / Credential Store — allow it).
4. Re-enter any secrets that lived in the old store: the **bearer token** (Generate / Regenerate in preferences), the **TLS keystore password** (re-type it or use Generate Self-Signed Certificate), and — if you use them — any other Archi credentials (e.g. coArchi model-repository logins).

> Note: Archi (unlike the full Eclipse IDE) does not expose the **Error Log** view in its menus on any platform. To read the full stack trace, open the `.metadata/.log` file inside your Archi instance area directly (`~/Library/Application Support/Archi/.metadata/.log` on macOS, `%APPDATA%\Archi\.metadata\.log` on Windows).

---

# Developer Guide

The following sections are for developers who want to fork, extend, or contribute to the plugin.

For comprehensive technical documentation covering architecture internals, coordinate model, routing pipeline, and extension patterns, see [docs/](docs/). Metric acronyms and routing terms used throughout this README (M1–M6, R8, `parallelConnectionGap_V_p10`, HPQ, corridor, clearance, perimeter) are defined in the [glossary](docs/glossary.md).

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

### Effective State

A mutating tool's success response reports **what the model now holds**, never the values the caller
passed in. This matters because Archi silently auto-fits a container to its children and cascades a
fit up the containment chain, so a requested `width` / `height` / `x` / `y` may not survive the write
— and the client is an agent that cannot see the canvas, so the response *is* its only ground truth.
Geometry is re-read after the write (`effectiveBounds`), a connection's styling, bendpoints and
anchor points are re-read with it (`effectiveConnection`), an ArchiMate concept's own state is
re-read beside them (`effectiveRelationship` / `effectiveElement`, which carry the semantic
attributes and documentation a bulk caller can change), an entity's reported name is re-read once
the whole call has been applied (`entityName`, so a rename or a clear is confirmed by the value the
model ends up holding), and the collateral is named rather than counted: `resizedAncestors` and
`resizedGroups` carry the containers a call grew *with their new rectangles*, `nestedContainersFitted`
carries the descendant containers a recursive pass re-fitted, `resizedElements` carries the leaf
children a layout pass re-sized without descending into them, and `movedObjects` carries the anchored
children it displaced with their new positions. A count or a flag on its own does not satisfy this —
`resizedCount: 3` says something moved and leaves the agent unable to say where — and a field named
as an outcome is never sourced from the request that asked for it. The same rule reaches a count of
**zero**: `layout-within-group`'s `ancestorsResized: 0` was true and undecodable, so that tool now
also reports `ancestorPropagation`, a stable code saying which situation produced the number. The
count cannot stand in for it in either direction: only `propagated` guarantees a count above zero,
five codes always report zero, and two — `stopped-at-non-native-ancestor` and `depth-cap-reached` —
arrive with either. Those two are the ones that leave a container above yours too small, so a
positive count there reads as success and is not. A response field that reports an
*outcome* is also omitted where nothing has run: a `bulk-mutate` queued into a batch or parked
awaiting approval reports no `allSucceeded` verdict, because the operations it describes can still
decline at commit.

In **batch** and **approval** modes nothing is effective by construction, so the entity is nested
under `preview` beside a `batch` or `proposal` sibling naming the deferred state, and never appears
at the top level of `result` where an agent would read it as state the model holds.

The rule is enforced, not merely intended: a contract test walks every registered tool and requires
each to classify exactly once as read-only, covered by an oracle, or listed in a committed gap
registry with a stated reason. **A tool classified nowhere fails the build**, and the registry's
entry count is a lower-only ceiling — so shipping a tool that cannot report effective state takes a
deliberate, reviewable admission rather than silence.

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

The same identifier is deliberately spelled differently across the surface — an element's model id is
`id` on `update-element`, `elementId` on `add-to-view` and `delete-element`, and `objectId` on
`move-to-folder` — and no tool schema declares `additionalProperties`, so a key spelled a sibling
tool's way is dropped before the handler sees it. A missing-parameter error therefore **names the
near miss**: it reports the spelling that was supplied alongside the one this tool requires, on the
standalone read and on `bulk-mutate`'s per-operation read alike, so the caller is not told a
parameter is missing while holding it under a name one synonym away. Inside `bulk-mutate` the same
failure now carries its own correction rather than the generic "fix the failed operation and retry
the entire call", which on a 150-operation call is the least useful advice available.

When more than one entry fails, `bulk-mutate` and `apply-positions` return a `failed` array whose rows
carry each entry's own index, its `errorCode`, its `message` and its `suggestedCorrection`. **Rows do
not repeat what they share.** A long ending common to every row of one error code is published once
beside the array — in `messages` and `corrections`, keyed by that error code — and the row names it
with `messageRef` or `correctionRef`. Nothing is truncated: a split row keeps its own head under the
field's own name, and head followed by the shared string is byte-for-byte what the row used to carry.
A `message` is never taken away whole, because it is the row's own account of what went wrong; a row
that shares the *whole* of its `suggestedCorrection` carries `correctionRef` and no
`suggestedCorrection` at all — absent rather than shortened, so a client that ignores the reference
finds a gap rather than a fragment it would read as complete. Whether a key is present on a row
depends only on that row's own content, so rows stay independently parseable.

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

- **Headless one-command run (recommended):** `tools/run-tests.sh` compiles both projects from source against your local Archi/Eclipse install and runs the headless-safe majority of the suite (~95% of classes), asserting `testsRun > 0` per class and emitting JUnit XML to `build/test-results/`. No Eclipse IDE, no hand-maintained class list — the run set is auto-discovered by scanning for `*Test.java` minus the exclusion manifest `tools/osgi-excluded-tests.txt`. See [`tools/README.md`](tools/README.md). Override locations with `ARCHI_HOME` / `ECLIPSE_HOME` / `M2_REPO` (so CI can reuse the same script). Use `tools/run-tests.sh --swt <Class>` for SWT/display-required classes.
- **Run the OSGi-only bucket** in Eclipse as a single "JUnit Plug-in Test" (the `AllPluginTestsRunner` launch) — the OSGi runtime it provides is required for the classes the headless harness excludes (listed in `tools/osgi-excluded-tests.txt`). Individual classes can also be run one at a time.
- **Pure-Java tests** (geometry, layout algorithms, routing) can also run as standard JUnit tests without the Eclipse runtime
- Handler tests mock `ArchiModelAccessor` — no Archi installation needed
- **Tests requiring the Eclipse/OSGi runtime** — three classes touch Archi/Eclipse runtime singletons that only initialise under OSGi, so they **must** be run as a JUnit Plug-in Test (a headless JVM cannot initialise them and they are guarded to *skip* there rather than fail):
  - `McpPreferenceInitializerTest` — `org.eclipse.core.internal.preferences.ConfigurationPreferences`
  - `McpServerManagerTest` — `com.archimatetool.editor.model.IEditorModelManager`
  - `ArchiModelAccessorImplTest` (the two specialization-relationship cases) — `com.archimatetool.model.util.RelationshipsMatrix`

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
