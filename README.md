# Archi MCP Server

An Eclipse PDE plugin for [Archi](https://www.archimatetool.com/) that exposes ArchiMate models through the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/), enabling LLMs to query, analyse, and modify enterprise architecture models through natural language.

## What It Does

Archi MCP Server embeds an HTTP server inside Archi that speaks MCP. Once running, any MCP-compatible LLM client (Claude, Cline, LM Studio, etc.) can connect and interact with the currently open ArchiMate model — asking questions, searching elements, traversing relationships, composing view diagrams, and even creating or modifying model content.

The server provides **69 MCP tools** across querying, searching, creating, layout, routing, assessment, batch operations, images, specializations, and more — plus **14 MCP resources** with ArchiMate reference material, workflow guides, and a viewpoint recipe library for LLMs.

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

The server exposes **69 MCP tools** organised into functional categories.

### Query & Model Inspection (6 tools)

| Tool | Description |
|---|---|
| `get-model-info` | Model overview — name, `purpose`, custom `properties`, element/relationship/view counts by type and layer, plus specialization count. Read counterpart to `update-model` |
| `get-element` | Retrieve element(s) by ID (single via `id` or batch via `ids` array) |
| `get-views` | List views with optional viewpoint type or name filtering |
| `get-view-contents` | View diagram contents — elements, relationships, visual positions, connection routing, image visuals, and the full styling surface (typography, gradient, alignment, line style, `labelExpression`) so styling mutations can be read back and verified. `format: "tree"` returns the containment hierarchy, descending both visual groups **and** ArchiMate-element containers — nested children appear in the parent node's `children` array with a `childCount`. Graph nodes and edges also carry their visual identifiers (`viewObjectId` on nodes, `viewConnectionId` on edges) so a returned visual can be fed straight into `remove-from-view` / `update-view-object` / `update-view-connection` without a second lookup. On Archi 5.10, connections also report `relativePosition` — the connection "Label Offset" anchor — so an offset applied by `auto-route-connections` can be read back programmatically (omitted on Archi 5.7 and for the un-offset default) |
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
| `create-element` | Create an ArchiMate element with type validation and duplicate detection. Optional `specialization` parameter auto-creates the specialization on first use. A `folderId` that points at a folder whose ArchiMate layer is illegal for the element type is rejected up front with `FOLDER_LAYER_MISMATCH` (delegated to Archi's own folder rules) rather than failing later when Archi saves the model |
| `create-relationship` | Create a relationship with ArchiMate specification rule enforcement. Optional `specialization` parameter auto-creates the specialization on first use. Optional type-conditional semantic attributes: `accessType` (AccessRelationship), `associationDirected` (AssociationRelationship), `influenceStrength` (InfluenceRelationship) |
| `create-view` | Create a new diagram view with optional viewpoint and connection router type |
| `clone-view` | Duplicate an existing view with all visual contents (elements, groups, notes, connections, bendpoints, styling). The clone references the same model objects |

### Element, Relationship, View & Model Updates (4 tools)

| Tool | Description |
|---|---|
| `update-element` | Update element name, documentation, properties, or `specialization` (pass `""` to clear) |
| `update-relationship` | Update relationship name, documentation, properties, or `specialization` (pass `""` to clear). Optional type-conditional semantic attributes: `accessType` (AccessRelationship), `associationDirected` (AssociationRelationship), `influenceStrength` (InfluenceRelationship). Source, target, and type are immutable |
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

When `add-to-view` or `add-group-to-view` nests a child *inside* a container whose fill colour you have not explicitly set, the parent's fill auto-recedes to a subtle backdrop (`#F4F4F4`) so the nesting reads as depth instead of a flat block. The recession is provenance-gated and idempotent (only an unauthored fill is touched; an explicitly coloured container is left untouched), rides the placement as one undo unit, and excludes the root view. Pass `recede: false` to suppress it for a call.

A view object can also **anchor** to a target container via `update-view-object`, recording an `{anchorTarget, anchorEdge, dx, dy}` so its position resolves from the target's current bounds at *commit* time rather than a frozen snapshot — edge `below` (default) tracks the target's growing bottom, `above`/`right`/`left` the corresponding edge. When a later mutation moves or grows the target, every anchored object is repositioned within the same undo unit. Target and object must share a coordinate space; self-anchors and cross-space anchors are rejected. `add-note-to-view`'s content-relative placement uses the same edge resolver.

| Tool | Description |
|---|---|
| `add-to-view` | Place a model element onto a view diagram (same element can appear on multiple views). Optional `imagePath`, `imagePosition`, `showIcon` for custom icon overlays, `labelExpression` for a per-view-object dynamic label template (e.g. `"${name}"`, `"${property:Owner}"`), and `recede: false` to suppress the auto-backdrop when nesting inside an unauthored-fill container |
| `add-group-to-view` | Add a visual grouping rectangle (pure visual container, no model representation). Optional `imagePath`, `imagePosition`, `showIcon` for custom icon overlays, and `recede: false` to suppress the auto-backdrop when nesting inside an unauthored-fill container |
| `add-note-to-view` | Add a text note annotation (pure visual, no model representation). Optional `imagePath`, `imagePosition`, `showIcon`, and note-specific `borderType` |
| `add-view-reference-to-view` | Embed another ArchiMate view as a clickable thumbnail (the agent-driven equivalent of Archi GUI's drag-view-onto-view). Requires `viewId` (target) and `referencedViewId` (source). The referenced view's name is not stored on the visual — renaming the referenced view auto-updates every embedding |
| `add-image-to-view` | Add a standalone image as a first-class diagram node (sibling to notes, groups, and view-references). Requires `viewId` and an `imagePath` returned by `add-image-to-model` or `list-model-images`. Default size is the image's natural dimensions; typo'd paths are rejected with `IMAGE_NOT_FOUND`. Distinct from `update-view-object`'s `imagePath`, which sets an icon overlay on an existing element |
| `add-connection-to-view` | Add a visual connection representing an existing model relationship, with optional styling, label suppression, and label positioning |
| `update-view-object` | Update position, size, styling, image, `labelExpression`, and/or `anchor` of a visual element on a view. Optional anchoring (`anchorTarget`, `anchorEdge` `below`/`above`/`right`/`left` — default `below`, `anchorDx`/`anchorDy`) makes the object *follow* a target container's edge, resolved from the target's current bounds at commit time instead of a frozen absolute position — so a note stays below a group as the group grows. Target and object must share a coordinate space; pass an empty `anchorTarget` to clear |
| `update-view-connection` | Replace bendpoints, update styling, toggle label visibility, and/or set label position of a connection on a view |
| `apply-positions` | Apply a complete visual layout atomically (up to 10,000 entries per call) |

### View Cleanup (2 tools)

| Tool | Description |
|---|---|
| `remove-from-view` | Remove a visual element or connection from a view (model object preserved) |
| `clear-view` | Remove all visual elements and connections from a view (model objects preserved) |

### Layout & Routing (11 tools)

> **LLM agents:** Fetch `archimate://prompts/routing-preconditions-checklist` before invoking `auto-route-connections` or `auto-layout-and-route` on any non-trivial view. The routing pipeline cannot recover from missing preconditions — it can only route the geometry the agent has set up. The spacing convenience tools self-terminate honestly: if a view is provably too dense for spacing to fix they return `terminationReason: density_floor_reflow_required` and offer a user-consentable structural reflow — surface that to the user instead of looping the spacing tools.

| Tool | Description |
|---|---|
| `auto-route-connections` | Orthogonal connection routing using clearance-weighted visibility-graph A* pathfinding with corridor directionality, corridor diversity, group-wall awareness, channel-global ordered nudging, and post-routing path straightening. Two modes: `mode: "full"` (default) re-routes whole connections via visibility-graph A*; `mode: "terminals-only"` rectifies only the first/last bendpoint of each connection to make terminal segments orthogonal — best after ELK on grouped views to fix diagonal terminal entries without the crossing inflation a full re-route causes; it also sweeps redundant *interior* collinear bendpoints (a terminal L-bend that lands collinear with the existing trunk) so a re-route drives `connectionRedundantBendpointCount` toward zero without touching the pinned terminal egress anchors. Both modes now clear an **off-face terminal hug** — a connection that leaves an element face then runs a pixel or two parallel hugging it — by pushing the first trunk clear of the face: the full pipeline uses a dual-axis healthy-floor egress pass (a 15 px healthy parallel gap enforced on *both* axes, plus an own-face short-circuit that catches sub-10 px micro-hugs), and `terminals-only` mirrors the same floor as pure geometry with a per-side orthogonality backstop that declines any lift which would introduce a diagonal (detection fires at the assessor's 8 px oracle, so an already-clear terminal is byte-identical). This drives the `offFaceParallelTerminalCount` signal to zero. A gated final pass also **dissolves a coincident same-face hub port** — two terminals a downstream stage collapsed onto one perimeter port of a low-degree element face (the `coincidentFacePortCount` defect `assess-layout` reports) — by moving whichever terminal can move to a free along-face slot; it is a byte-identical no-op unless there is a real collision with a clear slot, so it never disturbs a dense hub's own distribution or adds a crossing. Optional `autoNudge` mode automatically moves blocking elements (and resizes parent groups to contain them) and re-routes failed connections in a single atomic operation. On Archi 5.10, when a Middle label still renders on its own endpoint box after position selection, the router applies the connection "Label Offset" (`relativePosition`) to lift it clear — the only channel that can clear own-endpoint label bleed on a position-preserving route (runtime-guarded; a silent no-op on Archi 5.7). The response carries a `structuredWarnings: List<{code, message, remediationTool, remediationViolatorIds}>` field for deterministic LLM iteration (`AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP` instructs the agent to run `layout-within-group` on the parent before re-routing; `EGRESS_LIFT_LAYOUT_BOUND` reports that the router generated then rolled back off-face egress lifts because applying them would narrow a parallel-connection gap below the 15 px healthy floor — the residual hug is layout-bound, so a matching `nextSteps` entry names spreading the elements instead of declining silently) |
| `auto-layout-and-route` | Two modes: `auto` (default) uses ELK Layered to compute positions AND routes in one operation; `grouped` orchestrates the full grouped-view workflow (layout-within-group + arrange-groups + optimize-group-order + auto-route-connections) atomically. Smart iteration when `targetRating` is set — factor-aware iteration tunes the right knob per tier, with plateau detection to exit early when iterations stop improving the dominant tier |
| `layout-within-group` | Arrange child elements inside a container using row, column, or grid patterns. The container may be a visual group **or** an ArchiMate-element container (`ApplicationComponent`, `Node`, `ApplicationFunction`, etc.) that holds nested children — the same `arrangement` / `spacing` / `padding` / `columns` / `autoResize` / `autoWidth` semantics apply to both. Notes, view-references, and connections are rejected as containers |
| `layout-flat-view` | Automatic layout for flat (non-grouped) views — row, column, or grid arrangement with optional sorting by name/type/layer and category grouping |
| `arrange-groups` | Position top-level groups relative to each other in grid, row, or column layout. **Density-aware default:** when `spacing` is omitted on a view with inter-group connections, the tool derives a connection-count-aware default (≤ 15 → 80 px, 16–30 → 100 px, > 30 → 120 px). Pass an explicit `spacing` to suppress |
| `optimize-group-order` | Reorder elements within groups to minimise inter-group edge crossings |
| `resize-elements-to-fit` | Resize all (or selected) elements on a view to fit their labels using SWT font metrics. Two-pass algorithm for nested containment: children first, then parents. Optional `wrapFit: true` keeps each element's width and grows only its height so a long label wraps onto extra lines instead of being clipped or forced into an over-wide box — useful for nested labels in fixed-width containers (after a parent grows to contain a wrapped child, follow with `auto-route-connections`). **Sizes for label legibility only — not for connection fan-out.** For hub elements with high connection counts, use `detect-hub-elements` plus `update-view-object` |
| `adjust-view-spacing` | Inflate inter-element and inter-group spacing on an existing view, then re-route in a single atomic operation. Use when a view is correctly laid out but visually cramped, without re-running ELK from scratch and losing manual placement intent. **Density-aware default:** when `interElementDelta` is omitted on a view with `coincidentSegmentCount > 2` or `connectionEdgeCoincidenceCount > 4`, the tool derives a connection-count-aware default (≤ 15 → 60 px target, 16–30 → 80 px, > 30 → 100 px). Pass `interElementDelta: 0` to suppress. After spacing inflation, a post-pass detects any child element that overflows its parent group bounds and resizes the parent |
| `apply-element-spacing-recommendations` | Convenience tool that runs an embedded observe → decide → density-aware-terminate control loop to inflate within-group element spacing. Per iteration it takes a small monotone step, re-runs `assess-layout`, and continues / escalates / stops; a degrading step is always reverted. Hub-aware tier selection (80/100/120 px) when `detect-hub-elements` reports candidates. Returns before/after `assess-layout` snapshots plus `terminationReason` / `iterationCount` / `appliedDeltas[]`. One call = one undo step. Set `dryRun: true` to preview |
| `apply-group-spacing-recommendations` | Sibling-symmetric convenience tool that runs the same embedded control loop to widen inter-group corridors only — preserves group ordering and topology. Hub-aware connected-pair tier rises to 100/140/160 px. Returns before/after `assess-layout` snapshots plus `terminationReason` / `iterationCount` / `appliedDeltas[]`. `dryRun: true` previews. With hub sizing and `apply-element-spacing-recommendations` this completes the routing-preconditions triad |
| `apply-spacing-recommendations` | Composed convenience tool that runs TWO coordinated control loops (element arm, then inter-group arm) in one transactional call. The `scope` parameter (`both` / `element` / `group`) selects which arm(s) run. The inflation-knee constants are **per-iteration step caps** (+80 px element / +100 px inter-group per iteration), preventing cumulative-inflation-past-the-knee without a fixed per-call ceiling; `elementKneeClampApplied` / `groupKneeClampApplied` surface when a step cap fires. Reports per-arm `terminationReason` / iteration counts / applied deltas. Use when both axes need adjustment; single-arm siblings cover single-axis changes |

### Layout Assessment & Analysis (2 tools)

> Metric acronyms below — M1–M6, R8, `parallelConnectionGap_V_p10` (V_p10), HPQ — are defined in the [glossary](docs/glossary.md).

| Tool | Description |
|---|---|
| `assess-layout` | Assess view layout quality across the legacy 8-metric severity-tiered rating (overlaps, crossings, pass-throughs, coincident segments, non-orthogonal terminals, spacing, alignment, label overlaps) plus the perception-aligned metrics (M1 visible-segment-length non-orthogonal terminals, M2 interior terminations, M3 zigzags, M4 connection-vs-edge coincidence, M5 hub-port quality, R8 corridor utilisation) and the M6 two-dimensional rating (layout tier × routing tier). Also reports the informational `parallelConnectionGap_V_p10` signal (10th-percentile parallel-segment gap on the V axis, anchored at 13.30 px on the ArchiMate reference) for narrow-corridor regime detection. Informational detections cover label truncation, parent-label obscured by child, image sibling overlap (`imageSiblingOverlapCount` — now also examines specialization icons and sizes images from their true pixel dimensions), and clipped notes (`noteClipCount` — a note whose text needs more height than its box, typically an explicit `height` that defeats auto-fit). Connection-label overlap detection is render-calibrated (glyph box widened 1.35× to match Archi's rendering) and now flags a label rendered on its own endpoint box (the channel `auto-route-connections` clears via the Archi 5.10 Label Offset), while correctly ignoring labels suppressed with `showLabel: false` and labels already lifted clear by an applied Label Offset (offset-aware). The own-endpoint test combines an area-fraction rule with a box-coverage rule (catching a label blanketing a tiny endpoint box such as a junction), a short-segment promotion (lowering the bar when the label is wider than the first/last segment it anchors to), and a junction near-zero bar (a Junction renders as a solid dark shape with no readable interior, so a label grazing its own junction endpoint is flagged at ~5% — catching an oversized junction the box-coverage rule, which only fires on a tiny one, misses). A rating-affecting `hubNeighbourClearanceMin` (px) reports the tightest hub-edge-to-spoke-row clearance on any crowded hub face; a value below the 60 px crowding floor caps the layout tier so a hub enlarged until it crowds its neighbours can no longer rate `good` (`-1.0` = no measurable hub, no rating impact). Two route-quality detections affect the routing tier: `connectionThroughNoteCount` flags a connection routed straight through a note's box or an element's rendered image rectangle (binary-presence Tier-3R cap-good; complements the box-based `connectionPassThroughs`, which the routing tier takes the worse of so there is no double penalty), and `nonOrthogonalInteriorSegmentCount` extends the non-orthogonal-terminal check to mid-route segments (Tier-2R cap-fair, ratio-bucketed like its terminal sibling and combined with it by the worse). Two further informational detections cover redundant collinear bendpoints (`connectionRedundantBendpointCount` — a removable point on a *horizontal or vertical* run whose deletion would not change the rendered orthogonal shape, distinct from `zigzagCount`; near-collinear diagonal micro-jogs and router-pinned terminal egress-stub ports are excluded, so a nonzero count means a genuinely collapsible interior point that a `terminals-only` re-route removes) and same-colour container/child "flat-blob" fills (`containerFillEqualsChildCount` — backstop for the auto-recede backdrop, firing only on an *authored* same-colour fill). Three further informational detections close the label/border blind spots: `connectionGrazesVisualCount` flags a connection touching or clipping a note's or image's *border band* (the outer strip the through-visual interior test discards, including visuals too small to inset — disjoint from `connectionThroughNoteCount`, so a crossing is classified as exactly one of through or graze); `labelOnNoteCount` flags a connection *label* rendered on a note's rectangle (the caption collision the route detectors cannot see, since a label is positioned independently of the line); and `labelOnGroupCount` flags a connection label on a visual group's *title band* (the title-strip collision the label-overlap detector skips, as it treats groups as transparent containers — a label in the group body is not flagged). Two more informational detections sharpen the terminal/edge picture: `offFaceParallelTerminalCount` flags a connection whose terminal route *departs* an element face then runs parallel hugging it (the first exterior segment travels along the departed face within 8 px of it) — the sub-pixel-off exit stub the visible-length guard suppresses in `nonOrthogonalTerminalCount`, resolved via the same terminal-slot face attribution and never feeding the rating (it is the oracle `auto-route-connections` drives to zero); `coincidentFacePortCount` flags an element face on which two or more connection terminals collide onto the *same* perimeter port (two edges appear to leave one point) — the blind spot in `hubPortQualityScore` (M5), whose per-face guard never scores a face carrying only two or three coincident connections, so it reads a vacuous `1.0` despite the collision (informational, M5 untouched; the oracle the router's coincident-port dissolution drives to zero); and the M4 edge-coincidence detector now enumerates *every* grazed element via `edgeCoincidenceGrazedElementCount` with an `edgeCoincidenceGrazedElements` element-id violator key, while the rating-bearing `connectionEdgeCoincidenceCount` and its connection-id `edgeCoincidence` key stay byte-identical (counted once per connection). The headline rating is also de-noised: `ratingBreakdown.overallExcludingAcceptedCosmetics` recomputes `overallRating` with the `nonOrthogonalTerminals` contribution removed (diagonal terminal segments are the straight-line signature of ELK auto-layout), as a floor that can only equal or improve `overallRating` — when the two differ, the gap is terminal cosmetics only (clear with `auto-route-connections` mode `terminals-only`, or accept); when equal, the rating reflects a real defect. A `coverage` map declares, per defect dimension, whether the run actually evaluated it (`checked` — detector ran and fully covers the dimension; `partial` — covers only some failure modes, so render-verify the rest; `not-checked` — no detector, absence is unknown; `not-applicable`), so a done-gate can tell "checked and clean" from "partially checked" from "never looked" by reading both `coverage` and `ratingBreakdown`; informational, never affects a rating. Most dimensions report a fixed registry level, but `labelOverlaps` is *contextual* — declared `checked`, it downgrades to `partial` on any run where a label is wider than its hosting segment (a label can crowd a neighbour box while still clearing it geometrically, so the overlap count is honestly zero yet the crowding mode is unverified); and `corridorCentering` is a standing `not-checked`, making explicit that `corridorUtilisationScore` measures multi-occupant corridor *spread*, not whether a single route centres versus hugs an edge. M3 (zigzag) skips connections already classified as pass-throughs so a single connection is not double-labelled. Self-element pass-throughs are reported but excluded from rating. The `nextSteps[]` envelope names the right precondition tool with violator IDs attached when an overlap, boundary violation, low hub-port-quality score, or grouped-view spacing/crossing breach is detected — and, for a near-saturated container-nested-hub view, emits a single diagnostic resize-vs-reposition step (gated on the clearance reading) instead of generic spacing inflation. The off-face parallel-terminal remedy is router-consistent and honest: a confident layout fix when the local corridor is below the healthy floor, otherwise a deferral to `auto-route-connections` (cross-referencing the `EGRESS_LIFT_LAYOUT_BOUND` warning) rather than over-promising a perpendicular re-route, and on a contested hub face (two or more connections sharing it) it names *spreading the connections* rather than a plain widen. Optional `includeViolatorIds` returns per-metric visual object IDs for targeted fixes. Optional `scope` selects `single` (default — the one `viewId`) or `all-views`, which assesses every diagram and returns a compact per-view map (`{name, overallRating, overallExcludingAcceptedCosmetics, elementCount, connectionCount, overlapCount, nonOrthogonalTerminalCount, connectionPassThroughCount}`) for a one-call close-out sweep, then drill into any `fair`/`poor` view with a single-scope call |
| `detect-hub-elements` | Identify hub elements by counting visual connections per element, sorted descending. Hub thresholds: ≥ 5 connections is a hub candidate; > 6 connections gets an explicit 1D sizing suggestion `baseDimension + 15px × (connectionCount − 6)`; for elements with > 12 connections the response also surfaces a 2D-resize suggestion (`width += 15 × ⌈excess/2⌉`, `height += 15 × ⌊excess/2⌋`) so the agent can distribute ports across all four edges. The assessor's internal `M5_FACE_GUARD_MIN_CONNECTIONS = 4` is a separate per-face guard for the M5 hub-port-quality metric, not a hub-detection threshold |

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
| `move-to-folder` | Move a model object (element, relationship, view, or folder) to a different parent folder. A move into a folder whose ArchiMate layer is illegal for the object is rejected with `FOLDER_LAYER_MISMATCH`, the same fail-fast check `create-element` applies |

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
| `export-view` | Render a view as PNG, JPG, SVG, or PDF — returned inline (base64 / blob) or written to file. Optional `quality` (1–100, default 90) for JPEG encoding; ignored for other formats. Vector formats (SVG, PDF) leave `width`/`height` unset because they are resolution-independent. Optional `outputDirectory` controls where files are saved (auto-creates directories; defaults to temp). SVG and PDF require the `com.archimatetool.export.svg` bundle that ships with Archi 5.7+. Each response carries the model's monotonic mutation counter as `modelVersion` (inline mode: top-level field; file mode: in `_meta`) — compare it *numerically* against a later `assess-layout` `_meta.modelVersion`: a lower export value means the model changed after the render, so the image is stale and should be re-exported before a visual close-out |

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
| `bulk-mutate` | Execute multiple mutations as a single compound command with 0-indexed back-references (`$N.id` resolves to the result of operation N; a create tool cannot reference its own not-yet-created result) and optional `continueOnError`. Among its operations is the view-scoped `set-view-label-expression`, which stamps one `labelExpression` template (e.g. `${name} ${property:evidenceMark}`) onto every eligible object on a view in a single atomic op — retroactive, idempotent, and type-filterable (default scope is ArchiMate **element** objects; optional `objectTypes` widens to notes/groups). A non-blank-name guard skips a blank-named object when *setting* (no half-empty glyphs) but is bypassed when *clearing*; the per-op result reports `appliedCount` / `skippedCount`. One sweep = one undo unit |

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

### Response Control

All query tools support response optimisation parameters:

- **Field selection:** `fields` param with presets (`minimal`, `standard`, `full`) or custom field arrays
- **Field exclusion:** `exclude` param to omit specific fields
- **Pagination:** Automatic for large result sets, with cursor-based continuation

## MCP Resources

The server provides 14 reference resources accessible to LLM clients.

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

`repo-to-archimate-model.md` is also distributed as the **`/repo-to-archi`** Claude Code slash command, which embeds a copy of the prompt plus a repository argument and a clone step. The command copies do not auto-update — `prompts/` is the source of truth, so re-sync the command copies after editing a prompt here.

## Mutation Safety

All write operations integrate with Archi's **CommandStack**, making every mutation **undoable** via `Ctrl+Z` in Archi or the `undo` / `redo` tools.

**Verbatim-store validation.** A name, label, view-object text, or `labelExpression` containing a well-formed HTML/XML entity token (`&amp;`, `&lt;`, `&#160;`, `&amp;amp;`, …) is **rejected with a corrective hint** rather than silently stored as the escaped literal — the recurring `&amp;` footgun. Bare ampersands and non-entity text (`R&D`, `A & B`, `<tag>`, `${name} & ${property:x}`) are accepted and stored byte-for-byte. The rule covers `create-*` / `update-*` / `add-*` and their `bulk-mutate` equivalents.

**Approval mode** adds a human-in-the-loop gate that the **human owns** — the agent operates tools but can never move the gate or approve its own changes:

```
Human toggles "Approval Mode" ON in Archi (MCP Server menu)
  → agent's mutations queue as "pending" (not applied)
  → agent calls list-pending-approvals → sees what's gated, tells the user to confirm in Archi
  → human approves/rejects in Archi → apply, or discard
```

The toggle lives only on the human side: there is no MCP tool to enable/disable approval or to approve a proposal. Your choice is **remembered across restarts** (stored in MCP preferences); a fresh install defaults to **GATED** (fail safe). Turning the gate **off** requires a confirmation in Archi; turning it **on** is a single click. Approval mode is a single global switch (one human, one desktop, one gate), and the agent can read the current state via `get-model-info` (`approvalMode`).

**Pending Approvals view.** When approval mode is on, you review and apply the agent's queued changes in the **Pending Approvals** dock view inside Archi. Open it from **Window → Show View → MCP Server → Pending Approvals**, or from the **MCP Server → `Pending approvals (N)`** menu item (which also shows the live count). Each gated tool-call — including a whole `bulk-mutate` — is **one card**, showing a plain-language effect rollup with destructive counts (deletes/removals) called out in amber. **`Show changes`** expands the card to named, verb-prefixed rows (deletes hoisted to the top) with a `Technical details` disclosure for the raw parameters. **`Approve`** applies the change (a card containing a delete stays disabled until you expand it once); **`Reject`** discards it with no change to the model. Toolbar **`Approve all safe`** clears the purely-additive cards in one click, and **`Approve all ⚠`** drains everything after one confirmation that names the deletion count. New gated changes appear live without a refresh.

The card's effect text is **generated by the server from the model's own truth**, so you can decide from the card alone without opening `Technical details`: relationships name both endpoints (`Create ServingRelationship: 'Payment Gateway' → 'Fraud Engine'`, and a delete adds its cascade), and a `bulk-mutate` card's rows are named per operation (`+ Create "Payment Gateway" (ApplicationComponent)`, `↔ Connect "Payment Gateway" → "Fraud Engine" (ServingRelationship)`). Agents may pass an **optional `intent`** on `begin-batch` or `bulk-mutate` (a plain-language "why"); when present it shows as a quiet `agent's note:` line **below** the effect — it never replaces or outranks the server effect, and an empty or generic note is dropped. Effect is non-spoofable server ground truth; intent is a separate, lower-trust claim the server never depends on.

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
