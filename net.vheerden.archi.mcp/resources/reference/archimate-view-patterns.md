# ArchiMate View Patterns and Layout Guidance

## Recommended LLM Model Size

This MCP server exposes 69 tools for ArchiMate model manipulation. Model size impacts reliability:

- **8B+ parameters (minimum):** Handles basic queries (get-element, search-elements, get-views). May struggle with multi-step workflows or complex tool sequences.
- **14B+ parameters (recommended):** Reliable tool calling, multi-tool workflows, view composition, and layout operations. Handles complex sequences like create-view → add elements → layout → assess → refine.
- **70B+ parameters:** Best for ambitious tasks — full architecture diagram generation, cross-view analysis, bulk model restructuring.

Smaller models may produce malformed tool arguments or lose context during multi-step operations. If experiencing issues, try a larger model before debugging your prompts.

## Common Viewpoint Patterns

| Viewpoint | `viewpoint` param | Purpose | Typical Elements |
|-----------|------------------|---------|-----------------|
| Application Landscape | — (no formal viewpoint) | Overview of all applications and their interactions | ApplicationComponent, ApplicationService, ServingRelationship |
| Application Cooperation | `application_cooperation` | How applications integrate and exchange data | ApplicationComponent, ApplicationInterface, FlowRelationship |
| Technology Usage | `technology_usage` | Deployment of applications onto infrastructure | Node, Device, SystemSoftware, Artifact, AssignmentRelationship |
| Layered | `layered` | Cross-layer dependencies from business to technology | Elements from Business, Application, and Technology layers |
| Organization | `organization` | Business structure and actor responsibilities | BusinessActor, BusinessRole, BusinessCollaboration, AssignmentRelationship |
| Business Process Cooperation | `business_process_cooperation` | Process interactions and handoffs | BusinessProcess, BusinessService, TriggeringRelationship, FlowRelationship |
| Motivation | `motivation` | Goals, requirements, and stakeholder concerns | Stakeholder, Goal, Requirement, Principle, InfluenceRelationship |
| Information Structure | `information_structure` | Data objects and their relationships | DataObject, BusinessObject, AccessRelationship, AssociationRelationship |
| Implementation & Migration | `implementation_migration` | Planned work and transition states | WorkPackage, Plateau, Gap, Deliverable |
| Specialization Hierarchy | — (no formal viewpoint) | Visualise IS-A type hierarchies for a domain vocabulary (see `archimate-specializations.md`) | Any element type, `SpecializationRelationship` |

Pass the `viewpoint` param value to `create-view` to set the formal ArchiMate viewpoint. Use "—" entries as general-purpose views (omit `viewpoint`).

## Layout Conventions by Viewpoint

| Viewpoint Style | Recommended Algorithm | Recommended Preset | Notes |
|----------------|----------------------|-------------------|-------|
| Layered (cross-layer) | `tree` or `directed` | `hierarchical` | Top-down: Business at top, Technology at bottom. Use groups per layer. |
| Landscape (flat overview) | `grid` | `compact` | Uniform grid, no hierarchy implied. Good for inventories. |
| Process flow | `horizontal-tree` or `directed` | `spacious` | Left-to-right flow. `directed` for complex branching. |
| Dependency graph | `directed` | `hierarchical` | Sugiyama layering minimises edge crossings. |
| Cluster/organic | `spring` | `organic` | Force-directed finds natural groupings. Non-deterministic. |
| Radial (central focus) | `radial` | — (use algorithm directly) | Central element radiates outward. Use for impact analysis views. |
| Capability map | `grid` | `compact` | Regular grid. Use groups to represent capability domains. |
| Specialization hierarchy | `tree` | `hierarchical` | Abstract parent at top, concrete specializations below. Use `SpecializationRelationship` for the IS-A edges. Pair with the inline `specialization` param on `create-element` to also tag elements with the catalog vocabulary. |

## ArchiMate Modelling & Aesthetic Best Practices

These are the established ArchiMate diagramming conventions an LLM agent should apply **when setting up a view, before routing**. They come from the two most widely-cited practitioner sources — the *ArchiMate Cookbook* (Hosiaisluoma) and *Mastering ArchiMate* (Wierda); see "Sources" below. Each rule here is one an agent can act on with this server's tools. Following them improves perceived aesthetics far more than re-running the router, because most aesthetic problems are decided by element placement, sizing, and grouping — not by route geometry.

### Apply these before routing

1. **Layer top-to-bottom (strongest rule).** Stack layers vertically in the conventional ArchiMate order: customers/actors at the very top, then Business, then Application, then Technology at the bottom. Strategy/Motivation sit above Business when present. Use one group per layer and arrange them in a column: `arrange-groups` with `arrangement: "column"` (or `"topology"`), or `auto-layout-and-route` with a top-down direction. This single convention does more for readability than any other.
2. **Put the primary flow on the top band.** For process / customer-journey / service-design views, place the journey or process flow on the topmost band and the supporting applications on the bottom band, with performers on either side of the process. This keeps the "outside-in" reading order an architect expects.
3. **Draw hubs and swimlanes large, and nest their members.** A role/actor/component that owns many children is a swimlane: size it large and nest its members inside it (`add-to-view` / `add-group-to-view` with `parentViewObjectId`). The Cookbook is explicit that this "saves space and minimizes crossing lines." This is the same instinct as the hub-sizing precondition — large containers give the router more perimeter and remove visible connectors.
4. **Nest a part inside its parent — do NOT draw the structural relationship.** When an element is part of another (`CompositionRelationship` / `AggregationRelationship`), or an actor/role/collaboration owns or performs it (`AssignmentRelationship` from a team/role/collaboration to the element), show that by **visual containment** — `add-to-view` / `add-group-to-view` with `parentViewObjectId` — and **exclude that relationship type from the `auto-connect-view` filter**. Drawing a Composition, Aggregation, or Assignment connector between a parent and its own nested part is an **error**: containment already states it, and the duplicate connector clutters the view. Caveat (Wierda): nesting is *ambiguous in general* — a bare box-in-box does not reveal which structural relationship it implies — so for a cross-structure relationship whose *type* the reader must see, keep the explicit connector. The parent→part case is **not** ambiguous: nest it, never draw it (see "Adding Connections").
5. **Use whitespace to express structure.** Negative space communicates grouping and layering without extra connectors. This is exactly what the inter-element vs inter-group spacing precondition operationalizes — wider inter-group corridors read as "different layer/domain"; tighter intra-group spacing reads as "belongs together." Let the spacing heuristics (Pre-Layout Planning §2) set these.
6. **Cluster with the Grouping element and route one connector to the group.** When several elements share a relationship to an external element, group them and connect the group once instead of drawing N parallel connectors. Fewer connectors is the single biggest lever on crossing count.
7. **Use the conventional layer colors; avoid custom element colors.** ArchiMate's de-facto palette is yellow for Business, light blue/turquoise for Application, light green for Technology. These colors carry built-in meaning; custom per-element colors dilute it. Reserve color for a deliberate signal (e.g. red for an at-risk flow), applied sparingly.
8. **Select only the viewpoint's element subset.** Each diagram type prescribes which elements belong on it (the Cookbook's "80% rule"). Placing fewer, viewpoint-relevant elements lowers density *before* routing — the most reliable way to avoid the narrow-corridor floor. Use the `relationshipTypes` / `elementIds` filters on `auto-connect-view` and split overloaded views.
9. **Align to a grid and to each other.** Grid and mutual alignment make a diagram read as deliberate. The layout tools (`layout-within-group`, `arrange-groups`, ELK) already grid-align — prefer them over hand-placed coordinates.
10. **Minimize line crossings.** Both sources name this as an explicit objective. It is the routing engine's job, but the agent enables it by doing 1–8 first; `optimize-group-order` then reduces residual inter-group crossings.

### Modelling semantics — good practice, but not a layout/routing concern

These matter for a correct model but are **not** something this server's layout or routing tools act on; do not expect the router to enforce them: correct relationship-type choice (Composition vs Aggregation vs Assignment; Flow vs Trigger vs Access) and derivation rules; naming conventions (plural for collections, `<<stereotype>>` for abstractions); which viewpoint to model for a given stakeholder concern; line-width/line-color used to encode integration volume or mechanism; repository coherence and governance. Apply these when *creating* model content, not when laying a view out.

### Sources

- *ArchiMate® Cookbook — Patterns & Examples*, Eero Hosiaisluoma. Free PDF: <http://www.hosiaisluoma.fi/ArchiMate-Cookbook.pdf>. Companion blog: <https://www.hosiaisluoma.fi/blog/archimate/>. The source of the top-down layering, swimlane-nesting, Grouping-element, and layer-color conventions above.
- *Mastering ArchiMate* (Edition 3.x), Gerben Wierda. Book site: <https://ea.rna.nl/the-book/>. The source of the minimize-crossings, grid-alignment, whitespace-for-structure, and nesting-ambiguity guidance above (paraphrased — the book is paid).
- See `docs/bibliography.md` references [17] and [18] for the full citations and scope note.

## Layout Algorithm Reference

| Algorithm | Style | Best For |
|-----------|-------|----------|
| `tree` | Top-down hierarchical | Dependency trees, decomposition, layered architecture |
| `spring` | Force-directed clustering | Discovery, unknown structures, organic groupings |
| `directed` | Sugiyama layered graph | Process flows, complex dependency chains, minimising crossings |
| `radial` | Concentric circles | Central element with radiating dependencies, impact views |
| `grid` | Regular grid | Flat inventories, capability maps, element catalogues |
| `horizontal-tree` | Left-to-right tree | Data pipelines, left-to-right process flows |

## Layout Preset Reference

| Preset | Algorithm | Spacing | Best For |
|--------|-----------|---------|----------|
| `compact` | grid | 20px | Dense information displays, small views, dashboards |
| `spacious` | tree | 80px | Presentations, annotation room, readability |
| `hierarchical` | tree | 50px | Standard architecture layers, dependency views |
| `organic` | spring | 50px | Exploration, discovery, unknown structure |

## Connection Routing

For clean orthogonal routing, use `auto-route-connections` after placing elements. It computes obstacle-aware paths that avoid element crossings using a clearance-weighted visibility graph + A* algorithm with corridor directionality penalties, group-wall awareness, and post-routing path straightening. **This is the primary routing approach for ALL connected views, including views with groups.**

**IMPORTANT — Manhattan routing is almost never the right choice:** Manhattan (`connectionRouterType: "manhattan"`) draws right-angle paths but does **NOT** avoid element obstacles — connections pass straight through sibling elements, producing poor visual quality. **Do NOT use Manhattan if the view has more than 5 connections or any inter-group connections.** Only use Manhattan for simple structure/catalogue views with very few connections (≤5) where routing quality is not critical.

**`auto-route-connections` produces orthogonal (right-angle) paths** — visually identical to Manhattan but with obstacle avoidance. When this tool switches the view from Manhattan to bendpoint mode, only the storage format changes — connections remain right-angle/orthogonal. There is no visual quality tradeoff.

**Routing workflow — iterate until satisfied:**

1. Place/reposition elements with generous spacing (40px+ for grouped views, 100px+ for dense flat layouts). For flat views, use `layout-flat-view` to auto-position elements
2. Run `auto-route-connections` with `autoNudge: true` to compute clean orthogonal paths — autoNudge automatically moves blocking elements and re-routes failed connections in a single atomic operation
3. Run `assess-layout` to check for pass-throughs and violations
4. If pass-throughs persist: increase element spacing (re-run `layout-within-group` with larger `elementSpacing`, e.g., 60-80px, or `layout-flat-view` with larger `spacing`) and/or reposition groups with `apply-positions`
5. Re-run `auto-route-connections` and `assess-layout`
6. Repeat until pass-throughs are eliminated. Note: edge crossings (connections crossing each other, not through elements) are often structurally unavoidable in dense many-to-many views — the severity-tiered rating caps their impact at "fair" (Tier 2) so they do not mask structural quality. Non-orthogonal terminals (diagonal source/target entries on connections) sit in Tier 3 (cosmetic, caps at "good") and, below ~20°, are typically invisible to the human eye — **do not iterate on them once the view is otherwise clean**. Density-aware thresholds mean wider element spacing is the fix, not re-routing.

**Key insight:** `auto-route-connections` works correctly with groups. When routing quality is poor, the issue is element/group positioning (tight spacing, overlapping bounding boxes), not the presence of groups. Give the router space to work — dense layouts produce poor routes.

**When to use which routing approach:**

| Approach | Use When |
|----------|----------|
| `auto-route-connections` (default `mode: "full"`) | **PRIMARY** — any connected view (flat or grouped) needing clean orthogonal paths. Re-routes whole connections via visibility-graph A*. |
| `auto-route-connections` with `mode: "terminals-only"` | **After ELK on grouped views** when `assess-layout` reports `nonOrthogonalTerminals` but routing is otherwise good. Preserves all intermediate bendpoints and element positions; only rectifies the first and/or last bendpoint of each connection to make terminal segments orthogonal. Avoids the ~3× crossing inflation a full re-route causes on ELK-laid-out views. Each rectification is gated by an obstacle + crossing veto — connections whose L-bend would add a pass-through, element crossing, or new edge crossing are left unchanged and counted in `connectionsSkipped`. Mutually exclusive with `strategy: "clear"` and `autoNudge: true`. |
| `auto-layout-and-route` (ELK) | You want the algorithm to control element positions in one step. Best for flat views. **For grouped views:** ELK positions elements well but inter-group connection routing may produce diagonal terminal entries — follow up with `auto-route-connections` `mode: "terminals-only"` to rectify terminals without crossing inflation. |
| `adjust-view-spacing` | An existing view is correctly laid out but visually cramped. Inflates inter-element and inter-group spacing on the view, then re-routes in a single atomic operation. Use this instead of re-running ELK from scratch when the manual placement intent should be preserved. |
| Manhattan (`connectionRouterType: "manhattan"`) | **CAUTION** — only for ≤5 connections on simple structure views with no inter-group routing. Produces pass-throughs on dense views. |

## Adding Connections

Use `auto-connect-view` to batch-create visual connections for all existing model relationships between elements already on the view. This is the **primary** approach — it handles all connections in one call.

**Filtering options** for `auto-connect-view`:
- `relationshipTypes`: Only connect specific types (e.g., `["ServingRelationship", "FlowRelationship"]`)
- `elementIds`: Only consider relationships involving specific elements
- Both filters can be combined for precise control

**Batch styling on `auto-connect-view`:**
`auto-connect-view` accepts `lineColor`, `fontColor`, and `lineWidth` parameters that apply to every connection it creates. Combine with `relationshipTypes` to style connections by type in a single call — e.g., one call with `relationshipTypes: ["ServingRelationship"]` and `lineColor: "#0066CC"` for blue API calls, then a second call with `relationshipTypes: ["FlowRelationship"]` and `lineColor: "#FF8C00"` for orange domain events. This is more token-efficient than per-connection `update-view-connection` calls.

Use `add-connection-to-view` as a **fallback** for individual connections — e.g., when you need to connect specific relationships one at a time. Both `add-connection-to-view` and `update-view-connection` accept optional styling (`lineColor`, `fontColor`, `lineWidth`), `showLabel: false` to suppress the relationship name label, and `labelPosition` (`"source"`, `"middle"`, or `"target"`) to control where the label sits along the connection path. Use `labelPosition` to reduce label overlaps on dense diagrams — e.g., place labels near the target end when source-end labels collide with nearby elements.

**Lifting a Middle label off its own box (Archi 5.10):** when a `"middle"` label still renders *on* its source or target box (the along-path positions can't always avoid it), `auto-route-connections` and `auto-layout-and-route` automatically apply the Archi 5.10 connection **Label Offset** (`relativePosition`) to nudge the label clear in the perpendicular direction — no parameter needed. On a hand-placed view you keep, `auto-route-connections` is the tool to run, since the offset is the only channel that clears own-endpoint bleed without moving elements. Confirm it via `get-view-contents` (`relativePosition`) and verify in the **live editor**, not `export-view` (the export renderer does not draw the offset). On Archi 5.7 this is a silent no-op — use `labelPosition` or `showLabel: false` instead.

**Anti-pattern:** Do NOT use `bulk-mutate` with repeated `add-connection-to-view` operations when `auto-connect-view` can do it in one call.

**Nesting implies the structural relationship — exclude it from the filter:** When an element is visually nested inside its parent element or group (e.g., a part-component inside its component, a process inside its performing role's swimlane, availability zones inside a cloud region), the containment **already states** the `CompositionRelationship` / `AggregationRelationship` / owner-`AssignmentRelationship`. Do NOT also draw a connector for it: pass an `auto-connect-view` `relationshipTypes` filter that lists only the relationships that carry information beyond containment (e.g. `relationshipTypes: ["ServingRelationship", "FlowRelationship"]`), and leave Composition/Aggregation/owner-Assignment out of that list. Drawing the structural arrow on top of the nesting is an error — it duplicates the containment and clutters the diagram.

**Deployment/topology views:** For views where visual nesting (groups within groups) conveys the deployment structure, consider whether connections add value. If the view's purpose is to show deployment topology (regions, zones, tiers), the nested group structure may be sufficient without any connections. Only add connections if they convey information beyond what containment shows (e.g., replication flows, network traffic).

## Group Composition Patterns

| Pattern | Use Case | Example |
|---------|----------|---------|
| **Layer groups** | Separate ArchiMate layers visually | Groups labelled "Business", "Application", "Technology" stacked vertically |
| **Zone groups** | Network or security zones | Groups labelled "DMZ", "Internal", "External" |
| **Cluster groups** | Functional grouping within a layer | Groups labelled "CRM", "ERP", "Data Platform" |
| **Nested groups** | Sub-grouping within a parent | Technology > "Production" > "Web Tier" |

Use `add-group-to-view` to create groups. Nest elements inside groups using `parentViewObjectId` parameter on `add-to-view`. Nest groups inside groups using `parentViewObjectId` on `add-group-to-view`.

### Positioning Elements Inside Groups

Use `layout-within-group` to auto-position elements inside a group. It computes coordinates server-side using row, column, or grid patterns — the LLM does not need to calculate coordinates manually.

- Pass `autoResize: true` to let the group resize to fit its contents
- Call once per group after adding all elements to it
- **Arrangement selection:** Use `grid` for groups with 4+ elements (keeps groups compact). Use `row` for 2-3 elements that should be side-by-side. Use `column` only for narrow vertical lists of 2-3 items. Avoid `column` for groups with many items — it produces very tall narrow groups that waste horizontal space and distort the overall view layout.
- **Anti-pattern:** Do NOT manually compute x/y coordinates for elements inside groups when `layout-within-group` can do it automatically

### Positioning Groups Relative to Each Other

Use `arrange-groups` to position top-level groups in a view using grid, row, or column arrangements. It computes group positions server-side — the LLM does not need to calculate coordinates manually.

- **`grid`** (default): Places groups in rows with configurable `columns` count. Best for 4+ groups.
- **`row`**: Places groups horizontally in a single row. Best for 2-3 groups.
- **`column`**: Places groups vertically in a single column. Best for layered views (e.g., Business → Application → Technology).
- **`topology`**: Analyzes inter-group connection density and orders groups to minimize long-range crossings. Heavily-connected groups are placed adjacent. Best for any view with inter-group connections. Defaults to vertical (column) layout; pass `direction: "horizontal"` for left-to-right layout (e.g., producer→middleware→consumer flow patterns), or `columns` for grid topology.
- **`spacing`** (default 40): Gap in pixels between groups. Use larger values (80-100) for views with many inter-group connections — this creates **routing corridors** (whitespace channels between groups) that `auto-route-connections` can use for cleaner orthogonal paths. Dense hub-and-spoke topologies benefit significantly from wider inter-group spacing.
- **`groupIds`** (optional): Arrange only specific groups, leaving others in place.
- **Anti-pattern:** Do NOT manually compute x/y coordinates for groups when `arrange-groups` can do it automatically.

**Typical workflow:** `layout-within-group` per group (sizes groups to fit contents) → `arrange-groups` with `arrangement: "topology"` (orders groups by connection density) → `auto-connect-view` → `optimize-group-order` → `arrange-groups` (fix any group overlaps from reordering) → resize hub elements → `auto-route-connections`.

### Optimising Hub Elements for Dense Views

On views with hub-and-spoke topologies (e.g., integration architecture with an ESB or API gateway), elements with many connections (>6) create **port congestion** — all connections compete for attachment points on a small perimeter. This produces bundled, overlapping connections that are hard to read.

**Hub element dimension increase:** After layout, identify elements with high connection counts and increase the element dimension **perpendicular to the primary connection flow direction** using `update-view-object`. Larger elements provide more perimeter space for connection attachment points, spreading connections across a larger surface.

- **Which dimension to increase:** For horizontal layouts (left→right groups), increase **height** — this creates more vertical perimeter for side-facing connections. For vertical layouts (top→bottom groups), increase **width** — this creates more horizontal perimeter for top/bottom connections. For true hub elements receiving connections from all directions, increase **both**.
- **Rule of thumb:** For elements with more than 6 connections, increase the relevant dimension proportionally: `dimension = baseDimension + 15px × (connectionCount - 6)`. For example, an element with 14 connections in a horizontal layout: `height = 55 + 15 × 8 = 175px`.
- Use `detect-hub-elements` to identify which elements have the most connections — it returns elements sorted by connection count with sizing suggestions. Then use `update-view-object` to set their height and/or width.
- **After resizing, re-run `layout-within-group`** on the group containing the resized hub — this prevents the enlarged hub from overlapping sibling elements. Then re-run `auto-route-connections` — the larger element surfaces give the router more attachment point options.

**When to apply:** After `optimize-group-order` and before `auto-route-connections` in the grouped view workflow. Hub heightening + wide inter-group spacing (80-100px+ via `arrange-groups`) work together — taller hubs spread attachment points while wider spacing creates routing corridors.

**Resize vs reposition on a saturated container-nested-hub view (and metric/readability divergence).** When components are nested inside a container with a central hub and corridors are saturated (`assess-layout` reports high `corridorUtilisationScore` with edge-coincidence pressure but `hubPortQualityScore` is fine), enlarging the hub is not always right. On a **sparse** view it opens corridors (edge-coincidence drops, the view stays roomy); on a **dense** view the same resize trades edge-coincidence for hub-to-neighbour **crowding** — and no metric penalises that crowding yet, so the crowded layout can still rate `good` while reading worse than an ELK reposition that only rates `fair`. Decide by density: sparse → resize the hub (both dimensions) then `auto-route-connections`; dense → revert the hub to normal size, `auto-layout-and-route` (ELK), then a **full** `auto-route-connections` (an oversized hub before ELK causes interior terminations; `terminals-only` after ELK vetoes interior-landing terminals). **Acceptance is render-authoritative — verify with `export-view`, not the rating alone.** `assess-layout` emits this as a diagnostic next-step; the full decision tree is in `archimate://prompts/routing-preconditions-checklist`.

**A shared dependency of many *nested siblings* → place it as a wide bar adjacent to the container, not inside it or off to one side.** When one element is the common dependency of many children nested together in a container (e.g. a facade/accessor that serves 8 nested handlers), routing it from *inside* the container or from *beside* it produces zigzags and a self-pass-through — every sibling's edge competes for the same perimeter. Instead give the shared element its own **wide, short bar placed directly beneath (or above) the container**, spanning its width: each nested sibling then drops a short near-vertical edge to the bar, crossings collapse (typically to ≤1), and the shared dependency reads as a base the siblings sit on. This is the nested-siblings analogue of the hub-sizing rule — adjacency and perimeter, not nesting or side-placement, resolve the fan-in.

**Caveat — `autoWidth` on an *outer* container shrinks *inner* sub-containers to their label width.** When a container nests its own sub-containers, running `layout-within-group autoWidth` on the outer container resizes each inner sub-container down to its label, clipping the grandchildren nested inside it. Lay out **inner-first**: run `layout-within-group` (with `autoWidth`) on the innermost containers, then run the outer container with `autoWidth` **off**, so the outer pass sizes to the already-correct inner boxes instead of collapsing them.

### Containment & Parent Movement

Child coordinates are **relative** to their parent's top-left corner. When you reposition a parent (group or element) via `update-view-object`, all contained children move with it automatically — their relative positions are preserved.

**This means you only need to position top-level containers.** Children inside groups do not need individual repositioning when moving the group. This dramatically reduces coordinate computations.

An element nested inside another element (not a Grouping) may have a `CompositionRelationship` to its parent **in the model** — that is standard ArchiMate modelling practice. This is a *model*-level statement only: do **not** draw that relationship as a connector on the view (the nesting already conveys it — see best-practice rule 4 and "Adding Connections"). Grouping elements are visual containers and need no model relationship to their members.

## Pre-Layout Planning Checklist

Before calling any layout or routing tools, complete this analysis. Skipping these steps is the primary cause of quality variance between runs.

### 1. Hub Identification

1. Count connections per element from the model (use `get-relationships` or `detect-hub-elements` after adding elements to the view)
2. **Hub thresholds:**
   - `≥ 5 connections` — hub candidate (`LayoutQualityAssessor.HUB_DETECTION_THRESHOLD`, public canonical; needs 2-3× default spacing)
   - `> 6 connections` — `detect-hub-elements` emits an explicit sizing suggestion (`HUB_DETECTION_THRESHOLD + 1`; the formula's growth term `15 × (count − 6)` is non-positive at exactly 5, so suggestions only emit one above the candidacy threshold)
   - `> 12 connections` — `detect-hub-elements` also surfaces a 2D-resize suggestion alongside the 1D suggestion (`width += 15 × ⌈excess/2⌉`, `height += 15 × ⌊excess/2⌋`) so the calling agent can distribute ports across all four edges instead of just two
   - The assessor's internal `M5_FACE_GUARD_MIN_CONNECTIONS = 4` is a separate per-face guard for the M5 hub-port-quality metric, not a hub-detection threshold
3. Place hubs near the geometric center of the view or their group
4. Resize hubs before routing: `height = baseDimension + 15px × (connectionCount - 6)` for elements with > 6 connections; for elements with > 12 connections, prefer the 2D suggestion from `detect-hub-elements` so connections spread across all four faces. **Use `update-view-object` for the resize — `resize-elements-to-fit` is label-driven only and is the wrong tool for connection-fan-out.**

### 2. Spacing Heuristics

| Total connections on view | Element spacing | Group spacing (connected) | Group spacing (unconnected) |
|--------------------------|----------------|--------------------------|----------------------------|
| ≤15 | 60px (80px if large hubs) | 80px (100px if large hubs) | 40px |
| 16–30 | 80px (100px if large hubs) | 100px (140px if large hubs) | 40px |
| > 30 | 100px (120px if large hubs) | 120px (160px if large hubs) | 60px |

> **Large hubs** = any element on the view with **> 6 connections** (the canonical hub-candidate threshold per § 1 above). The hub-aware tier values account for the corridor space that formula-resized hubs consume — without them, the heuristic UNDERSHOOTS post-hub-resize and coincSeg residuals persist. The convenience tools (`apply-element-spacing-recommendations` and `apply-group-spacing-recommendations`) automatically detect large hubs and apply the hub-aware tier; if you call `adjust-view-spacing` directly with explicit `interElementDelta` or `interGroupDelta`, you must factor in hub size manually. The unconnected column (40/40/60) is hub-agnostic — there are no inter-group corridors to widen.

> **Stop signal — let the control loop decide.** The convenience tools self-terminate honestly: they stop and report `terminationReason: density_floor_reflow_required` when the view is provably too dense for spacing to fix, and revert any degrading step. You do **not** need to manually count spacing-tool invocations. Prefer one `apply-spacing-recommendations(scope=both)` call (two coordinated control loops, per-iteration step caps of +80px element / +100px inter-group) over manually iterating single-shot `adjust-view-spacing` — driving the single-shot primitive in a loop bypasses the loop's self-termination protection and the sound infeasibility certificate. When a convenience tool returns `density_floor_reflow_required`, do not loop: surface its `densityFloorDiagnosis` and reflow offer to the user and wait for consent (see `archimate://prompts/routing-preconditions-checklist`, "When a spacing tool says the view needs a structural reflow").

> **Footnote — control-loop semantics inside the convenience tools.** The three convenience tools (`apply-element-spacing-recommendations`, `apply-group-spacing-recommendations`, `apply-spacing-recommendations`) embed an **observe → decide → density-aware-terminate control loop**. Per iteration they take a small `+10 px`-per-step monotone increment (a larger step when escalating), re-run `assess-layout`, and classify on a 2×2 of *aggregate-trend* × *spacing-regime-position*: still climbing → **CONTINUE**; stalled and below the prescribed ~100–124 px / fan-out-sized-hub regime → **ESCALATE** (large steps toward the ~112 px mid-band plus a one-shot hub-resize); stalled and already in-regime → **PASS-HONEST** (more spacing cannot help — stop and report). A degrading step is always reverted; all accepted iterations wrap in one compound command (one call = one undo entry). The +80px element / +100px inter-group constants are **per-iteration step caps**, not per-call total clamps. `iterationBudget` defaults to 5 (single-arm) / 8 (composed), range [1, 20]; `dryRun: true` previews without mutating. `terminationReason` names exactly one of **eight branches** (seven in-loop + one pre-loop dryRun guard): (a) `goal_reached_at_iteration_N`; (b) `budget_exhausted_after_N_iterations`; (c) `aggregate_threshold_regressed_at_iteration_N_reverted_to_iteration_M`; (d) `structural_no_change_<reason>`; (e) `heuristic_already_met_no_change`; (f) `dry_run_recommendation_not_applied`; (g) `iteration_apply_failed_at_iteration_N_reverted_after_M_accepted_iterations` (a contained mutation threw mid-application; best-effort rollback applied, prior accepted iterations preserved); (h) `density_floor_reflow_required` — **PASS-HONEST**: a sound pre-routing infeasibility certificate fired (average spacing already in the 100–124 px band and the hub sized for its connection count, but aggregate quality stalled). The tool stops without degrading the view, returns a `densityFloorDiagnosis` string, and **offers** a structural reflow as an explicit user-consentable next step — it never auto-reflows. On `density_floor_reflow_required`, surface the diagnosis and reflow offer to the user and wait for consent; do not loop the spacing tools.

### 3. Group Composition

1. Group elements by architectural layer or functional domain
2. Place the group with the most external connections in the center position
3. For producer→middleware→consumer patterns: always place middleware group between the other two
4. Limit groups to 8-10 elements; split larger groups into sub-groups

### 4. Connection Filtering

1. Only show relationship types meaningful for the view's perspective — use `relationshipTypes` on `auto-connect-view`
2. Fewer connections = better routing quality; prefer 20-30 connections per view
3. If a view needs 40+ connections, consider splitting into multiple focused views
4. Each additional relationship type increases crossing pressure multiplicatively

## Routing Preconditions Checklist

A condensed three-precondition checklist. The canonical form lives at `archimate://prompts/routing-preconditions-checklist` — fetch that for the full decision tree, structured-warning handling, and narrow-corridor floor guidance. Verify each precondition before calling `auto-route-connections` or `auto-layout-and-route` on any non-trivial view. The routing pipeline cannot recover from missing preconditions — it can only route the geometry the LLM agent has set up.

- [ ] **Hub elements (≥ 5 connections, `HUB_DETECTION_THRESHOLD`) sized for connection fan-out.** Use `detect-hub-elements` + `update-view-object` (NOT `resize-elements-to-fit` — that one is label-legibility only). For elements with > 12 connections, prefer the 2D-resize suggestion that `detect-hub-elements` emits alongside the 1D one.
- [ ] **Inter-element spacing inflated to match connection density.** Use `apply-spacing-recommendations(scope=both)` (composed, two coordinated control loops with per-iteration step caps) when BOTH element and inter-group spacing need adjustment — the response DTO surfaces per-arm `terminationReason` + `iterationCount` + `appliedDeltas[]` plus the legacy `elementKneeClampApplied` / `groupKneeClampApplied` flags (still emitted when an arm's per-iteration cap fires). Use the single-arm siblings (`apply-element-spacing-recommendations` / `apply-group-spacing-recommendations`) or `adjust-view-spacing` for single-axis changes. Heuristics: see Pre-Layout Planning §2 above (with hub-aware tier when `detect-hub-elements` returns candidates). See the §2 footnote for the control-loop semantics that now sit inside each convenience-tool call.
- [ ] **Group arrangement set with topology + corridor-friendly spacing** (grouped views only). Use `arrange-groups` with `arrangement: "topology"` and `spacing >= 80`. If element-order changes are needed, `optimize-group-order` then re-`arrange-groups` (reordering can change group sizes).

When `assess-layout` `nextSteps[]` already names a precondition tool with violator IDs, act on that entry directly — the heuristics and IDs are already attached. If `auto-route-connections` returns a `structuredWarnings[]` entry with `code: AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP`, run `layout-within-group` on the parent of `remediationViolatorIds` before re-routing — the skip is a hard gate that the pipeline cannot resolve on its own.

## Which `get-view-contents` Format to Use

| Format | Use When | Returns |
|--------|----------|---------|
| `json` (default) | Full element/relationship detail, field selection needed | Standard result with elements, relationships, visualMetadata, groups, notes |
| `tree` | **Group discovery before layout** — find group viewObjectIds and their children | Compact containment hierarchy with `tree` array + `stats` (totalGroups, ungroupedElements, etc.) — including ArchiMate-element-container nesting (e.g. ApplicationFunctions inside an ApplicationComponent, SystemSoftware inside a Node) |
| `graph` | Deduplicated node/edge analysis | `nodes`/`edges` structure |
| `summary` | Quick natural language overview | Condensed text summary |

**Grouped view tip:** Start with `format=tree` to discover group viewObjectIds and containment structure before calling `layout-within-group`, `arrange-groups`, or `optimize-group-order`. The tree format is much more token-efficient than `json` for this purpose — it returns only viewObjectId, type, name/label, and children. The `children` field appears on element nodes when they contain nested ArchiMate-element children (`parentViewObjectId` chain) — agents detect element-container nesting via `containsKey('children')`.

## View Composition Workflow (Decision Tree)

Choose the right workflow based on your view type:

```
START → Complete Pre-Layout Planning Checklist (hub ID, spacing, group composition, connection filtering)
│
Is the view connected (showing relationships)?
│
├── NO (catalogue/inventory view)
│   ├── Will elements be in groups?
│   │   ├── YES → create groups → add elements with parentViewObjectId (autoSize=true)
│   │   │        → layout-within-group for each group (autoResize=true)
│   │   └── NO  → add elements (autoSize=true) → layout-flat-view (grid/row/column) or auto-layout-and-route (graph-aware ELK layout + routing)
│   └── export-view to verify
│
└── YES (relationship view)
    ├── Will elements be in groups?
    │   ├── No specific layout structure needed? (Branch 3 — ELK)
    │   │   └── pre-layout analysis → create-view → add groups → nest elements (autoSize=true)
    │   │        → auto-connect-view (filtered) → auto-layout-and-route (ELK, target "good")
    │   │        → auto-route-connections (fixes inter-group routing)
    │   │        → assess-layout → iterate if needed
    │   │
    │   └── Specific layout structure needed? (Branch 2 — grouped workflow)
    │       ├── e.g., layered groups, specific group positioning, producer→middleware→consumer flow
    │       ├── **RECOMMENDED:** pre-layout analysis → create-view → add groups → add elements (autoSize=true)
    │       │    → auto-connect-view (filtered)
    │       │    → **auto-layout-and-route mode="grouped"** targetRating="good"
    │       │    → assess-layout → iterate if needed
    │       └── MANUAL alternative (same quality, more token-expensive):
    │            pre-layout analysis → create-view → add groups → add elements (autoSize=true)
    │            → **get-view-contents format=tree** (discover group viewObjectIds)
    │            → layout-within-group per group (spacing from heuristics)
    │            → arrange-groups topology (orders groups by connection density)
    │            → auto-connect-view (filtered) → optimize-group-order
    │            → detect-hub-elements → resize hubs → arrange-groups
    │            → auto-route-connections → assess-layout
    │            → IF poor: increase spacing → re-route → re-assess → iterate
    │
    ├── Flat view, LLM-managed positions? (Branch 2 flat — DEFAULT for flat connected views)
    │   └── add elements (autoSize=true) → **layout-flat-view** (grid/row/column, sortBy, categoryField)
    │        → auto-connect-view → auto-route-connections (autoNudge: true)
    │        → assess-layout → iterate if needed
    │
    └── Algorithmic positions? (Branch 3 — ELK)
        ├── Flat view: pre-layout analysis → add elements (autoSize=true) → resize hubs
        │    → auto-connect-view (filtered) → auto-layout-and-route (ELK, target "good")
        └── Grouped view: pre-layout analysis → create groups → nest elements (autoSize=true)
             → auto-connect-view (filtered) → auto-layout-and-route (ELK)
             → auto-route-connections (fixes inter-group routing)
             → assess-layout → iterate if needed
```

### Branch 1: Non-Connected View (Catalogue/Inventory)

No relationships shown — focus on element organisation.

1. `create-view` with name and optional viewpoint
2. **If grouped:** `add-group-to-view` for each group → `add-to-view` with `parentViewObjectId` and `autoSize: true` for each element → `layout-within-group` per group with `autoResize: true`
3. **If flat:** `add-to-view` with `autoSize: true` for each element → `layout-flat-view` with `arrangement: "grid"` (or `auto-layout-and-route` for graph-aware ELK positioning + routing)
4. `export-view` to verify

### Branch 2: Connected View, LLM-Managed Positions (structural intent)

The LLM controls element/group placement. Use this when you need a **specific layout structure** — e.g., layered groups (Business → Application → Technology), specific group positioning, or directional flow patterns (producer→middleware→consumer). The structural intent is the value proposition; connection count is a secondary consideration.

**If quality is poor, increase element spacing (60-80px+), resize hub elements, and increase inter-group spacing (100px+) before considering switching to ELK. Abandoning structural intent should be a last resort, not the first response to a poor rating.** Dense views (>15 connections) are harder to achieve good quality with manual positioning, so be prepared to iterate more aggressively on spacing/sizing.

**For grouped views — use `auto-layout-and-route mode="grouped"` (recommended):**

> **`auto-layout-and-route` with `mode="grouped"`** orchestrates the full Branch 2 workflow (layout-within-group → arrange-groups → optimize-group-order → auto-route-connections) in a single atomic tool call with quality iteration. This replaces the manual 5-7 step sequence below. Use `targetRating: "good"` for automated quality iteration.

1. **Pre-layout analysis:** count connections per element from the model, identify hubs (5+ connections), determine spacing from Pre-Layout Planning Checklist
2. `create-view` with name (do NOT set `connectionRouterType: "manhattan"`)
3. `add-group-to-view` for each group (topology-aware composition — see Pre-Layout Planning §3)
4. `add-to-view` with `parentViewObjectId` and `autoSize: true` to nest elements in groups — ensures labels are not truncated before layout runs
5. `auto-connect-view` with `relationshipTypes` filter (2-3 types max per view)
6. **`auto-layout-and-route` with `mode: "grouped"`, `targetRating: "good"`** — orchestrates layout, arrangement, optimization, and routing with quality iteration
7. `assess-layout` to verify, `export-view` to check visually

**Manual alternative (same quality, more control, more tokens):**

1. **Pre-layout analysis:** count connections per element from the model, identify hubs (5+ connections), determine spacing from Pre-Layout Planning Checklist
2. `create-view` with name (do NOT set `connectionRouterType: "manhattan"`)
3. `add-group-to-view` for each group (topology-aware composition — see Pre-Layout Planning §3)
4. `add-to-view` with `parentViewObjectId` and `autoSize: true` to nest elements in groups
5. **`get-view-contents` with `format=tree`** to discover group viewObjectIds and their children — compact hierarchy ideal for the next steps
6. `layout-within-group` per group with `autoResize: true` and spacing from heuristics table
7. `arrange-groups` with `arrangement: "topology"` to position groups based on inter-group connection density (use `spacing` from heuristics table for routing corridors). For left-to-right flow patterns (e.g., producer→middleware→consumer), add `direction: "horizontal"`
8. `auto-connect-view` with `relationshipTypes` filter (2-3 types max per view)
9. `optimize-group-order` to minimize inter-group edge crossings — then re-run `arrange-groups` to fix any group-on-group overlaps caused by reordering
10. **Resize hub elements:** use `detect-hub-elements` to identify elements with >6 connections, then increase their height and/or width via `update-view-object` (see "Optimising Hub Elements" section). **After resizing, re-run `layout-within-group`** on affected group(s) to prevent resized hubs from overlapping siblings
11. `arrange-groups` again (to accommodate resized groups)
12. `auto-route-connections` with `autoNudge: true` to compute clean orthogonal paths and automatically move blocking elements
13. `assess-layout` to identify violations
14. **If poor rating or pass-throughs:** re-run `layout-within-group` with increased `elementSpacing` (60-80+) → re-run steps 9-13 → repeat until "fair" or better
15. `export-view` to verify

**For flat views (no groups) — use `layout-flat-view` as default first choice:**

> **`layout-flat-view` is the preferred layout tool for flat (ungrouped) views.** It offers `sortBy` and `categoryField` options that organize elements by type or layer before positioning — something `auto-layout-and-route` (ELK) does not provide. Use ELK (Branch 3) only when you want the algorithm to also control connection routing in a single call.

1. `create-view` with name
2. Add elements with `add-to-view` and `autoSize: true` (positions don't matter — sizes do)
3. **`layout-flat-view`** with `arrangement: "grid"` (or `"row"`/`"column"`) — auto-positions all elements. Optional: `sortBy: "type"` or `categoryField: "layer"` for organized grouping
4. `auto-connect-view` to batch-create all connections
5. `auto-route-connections` with `autoNudge: true` to compute clean orthogonal paths and automatically fix blocked routes
6. `assess-layout` → if poor: increase spacing in `layout-flat-view` → re-route → repeat
7. `export-view` to verify

**Tip:** Start with generous spacing (40px+ within groups, 100px+ between groups). Dense layouts cause routing problems. You can always tighten spacing later.

### Branch 3: Connected View, Algorithmic Positions (ELK)

Let the ELK algorithm control element positioning. Use this when **no specific layout structure is required** and the goal is the best achievable quality score. ELK controls positioning algorithmically — you trade structural intent for quality optimization.

**For flat views (no groups):**

1. **Pre-layout analysis:** identify hub elements from model relationships (use `get-relationships` or plan to use `detect-hub-elements` after adding elements)
2. `create-view` with name
3. `add-to-view` with `autoSize: true` for each element (positions don't matter — ELK will override them, but sizes are preserved)
4. **Resize hub elements** before ELK: wider elements = more attachment points for connections. Use sizing from Pre-Layout Planning §1
5. `auto-connect-view` with `relationshipTypes` filter (2-3 types max)
6. `auto-layout-and-route` with desired direction, spacing, and `targetRating: "good"` (not "excellent" — avoids wasted iterations on dense views)
7. `assess-layout` — if label overlaps are the limiter, increase spacing and retry. For persistent overlaps on individual connections, use `update-view-connection` with `labelPosition` to manually reposition labels
8. `export-view` to verify

**For grouped views:**

1. **Pre-layout analysis:** count connections per element, identify hubs, determine spacing from Pre-Layout Planning Checklist
2. `create-view` with name
3. `add-group-to-view` for each group (topology-aware composition — see Pre-Layout Planning §3)
4. `add-to-view` with `parentViewObjectId` and `autoSize: true` to nest elements in groups (ELK preserves containment — children stay inside their parents during layout)
5. `auto-connect-view` with `relationshipTypes` filter (2-3 types max)
6. `auto-layout-and-route` with desired direction, spacing, and `targetRating: "good"` (recommended)
7. **`auto-route-connections`** to re-route with obstacle-aware orthogonal paths (**critical for grouped views** — see note below)
8. `assess-layout` to verify quality
9. `export-view` to verify

**Quality target (recommended):** Use `targetRating` on `auto-layout-and-route` to automate the quality iteration loop. The tool internally runs `assess-layout` and iterates with increasing spacing until the target rating is achieved (up to 5 attempts). This eliminates the need for manual assess → adjust → re-layout loops. Use `targetRating: "good"` for most views, or `"excellent"` for presentation-quality diagrams.

**IMPORTANT — ELK routing limitation on grouped views:** ELK positions elements well in hierarchical layouts, but its inter-group connection routing operates at the group boundary level — it does not see individual elements inside groups as obstacles. This produces diagonal terminal entries and, in some cases, connections that pass through elements.

**Preferred fix — `auto-route-connections` with `mode: "terminals-only"`:** rectifies the first/last bendpoint of each connection without re-routing the body, eliminating diagonal terminal entries while preserving ELK's overall path structure. This avoids the ~3× crossing inflation a full re-route causes on ELK views. Use this as the default follow-up step after `auto-layout-and-route` on grouped views.

**Full re-route — `auto-route-connections` (default `mode: "full"`):** only use if terminals-only leaves significant routing problems in the body of connections (e.g. genuine pass-throughs that ELK produced). **Always run `assess-layout` afterwards** — in dense hub-and-spoke topologies the obstacle-aware router may produce *more* crossings than ELK's simpler routing. If crossings increased, `undo` and either accept the residual non-orthogonal terminals (Tier 3, cosmetic) or increase element spacing and retry.

For flat views, ELK routing is generally adequate on its own and neither follow-up is needed unless `assess-layout` flags real structural issues.

**When to use Branch 3 (ELK) vs Branch 2 (manual):** Choose based on **structural intent**, not connection count alone. Use **Branch 2** when the LLM needs a specific layout structure (layered groups, directional flows like producer→middleware→consumer, specific group positioning). Use **Branch 3 (ELK)** when no specific structure is required and you want the best quality score — ELK achieves "good" in 1-2 iterations on dense views. Connection count is a secondary heuristic: dense views (>15 connections) are harder to achieve good quality on with Branch 2, so iterate more aggressively on spacing/sizing rather than abandoning the structural intent. For grouped views, both branches require `auto-route-connections` as the final routing step.

### Tips

- Run `assess-layout` before and after layout changes to measure improvement. Use `includeViolatorIds: true` to get the specific visual object IDs that violate each metric — this enables targeted fixes (e.g. moving only the overlapping pair, re-routing only the pass-through connection) instead of global re-layout
- If quality is poor after one algorithm, try a different one — `spring` and `directed` often complement each other
- Use `apply-positions` for fine-tuning individual element positions without re-running the full algorithm
- When initial placement will be refined by routing iteration, use approximate coordinates — don't waste effort on precision that will be overridden
- **Note placement:** Notes are excluded from layout algorithms (`auto-layout-and-route`, `layout-within-group`) and do not affect `assess-layout` quality scoring. Use `position: "above-content"` on `add-note-to-view` after layout is complete to place title notes automatically above diagram content. Note-element overlaps are reported informatively by `assess-layout` but do not penalize the rating
- **View cloning for layout experiments:** Before trying a fundamentally different layout approach (switching algorithm, restructuring groups, changing direction), use `clone-view` to preserve the current state. Experiment on the clone — if the new approach is worse, delete the clone and keep the original. This is safer than relying on multiple `undo` operations across a complex layout sequence. Also useful for presenting alternative layouts to the user for comparison (e.g., clone a view, apply ELK to the clone, keep the original grouped layout — let the user choose)
- **Tagging elements with specializations:** When creating elements that should carry a specialization (e.g., "Microservice", "Cloud Server"), pass the `specialization` parameter to `create-element` directly — the specialization is auto-created on first use, and the element + specialization land in one undo unit. For batch creation, use `bulk-mutate` to pre-register specializations with `create-specialization` followed by `create-element` operations referencing them. See `archimate://reference/archimate-specializations` for the full vocabulary management workflow.

## Images & Icons on Elements

When adding custom images (icons) to elements via `add-image-to-model` + `update-view-object`:

- **Avoid `top-right` position:** Archi displays the element's ArchiMate type icon in the top-right corner by default. Placing a custom image there will be obscured by the type icon.
- **Recommended position:** Use `bottom-left` for small icons (16x16 or 32x32). This keeps the type icon visible and places the custom image in an unoccupied corner.
- **To show both type icon and custom image:** Place them in different corners (e.g., type icon stays top-right, custom image in bottom-left).
- **To hide the type icon:** Set `showIcon: "never"` on the view object. This frees up top-right for custom images if needed.
- **Icon size:** 16x16 icons may be barely visible on large elements (120+ px wide). Consider 32x32 or larger icons for better visibility, or use `fill` position to stretch the image.
- **Workflow:** Import images with `add-image-to-model` first, then apply to view objects. Set `imagePosition` and `showIcon` in the same `update-view-object` call to avoid intermediate states where both icons overlap.

## Standalone Image Visuals (vs Icon Overlays)

There are two distinct ways an image can appear on a view — pick the one that matches your intent:

| Goal | Tool | Result |
|------|------|--------|
| Show a small icon on an existing ArchiMate element | `update-view-object` with `imagePath` + `imagePosition` + `showIcon` | Icon overlay on the element. Element type, name, and connections are unchanged |
| Embed a logo, screenshot, sketch, or reference image as its own diagram node | `add-image-to-view` | Standalone image visual (sibling to notes, groups, view-references). Has its own ID, position, and size; can be connected to elements visually but does not carry ArchiMate semantics |

Both pull from the same model image archive — call `add-image-to-model` (or `list-model-images` for what's already there) to get an `imagePath` of the form `images/<sha1>.png`, then pass that path to either tool.

A standalone image visual carries the same visual styling fields as a note (fill / line / opacity / line width / line style / typography / gradient / border). Archi's image renderer ignores some font and gradient fields at paint time, though the EMF state is preserved if you read the visual back via `get-view-contents` (in the new `images` array).

## View-Reference Visuals (Embedding One View in Another)

A view-reference visual embeds another ArchiMate view inside the current view as a clickable thumbnail (the agent-driven equivalent of Archi GUI's drag-view-onto-view). Use it to build:

- **Landscape views** that embed each layer view as a thumbnail.
- **Index views** that link every viewpoint in one place.
- **Cross-cutting documentation views** that reference detail views from a single overview.

Tool: `add-view-reference-to-view` with `viewId` (the target) and `referencedViewId` (the source). Both must be IDs of existing ArchiMate views in the same model. The referenced view's name is NOT stored on the visual — Archi reads it dynamically at render time, so renaming the referenced view via `update-view` updates every embedding automatically.

Cycle (A→B→A) and self-reference (X→X) are not rejected — Archi's model setter accepts them.

When you delete a view that other views embed, `delete-view` now removes every embedding placeholder pointing at it atomically with the view itself, so the resulting model saves and reloads cleanly.

## Auto-Sizing Elements to Fit Labels

Elements placed at default size (120x55) may truncate long names. Use auto-sizing to ensure labels are fully visible. **All workflow branches above include `autoSize: true` at the element placement step — this avoids a costly resize-then-relayout cycle later.**

**At placement time — `autoSize: true` on `add-to-view` (recommended):**
- Pass `autoSize: true` when placing individual elements on flat views or via `bulk-mutate` with `add-to-view` operations
- Computes dimensions using SWT font metrics with aspect-ratio-aware sizing (target 1.5:1, range [1.2:1, 2.5:1])
- Short names (≤15 chars) keep default 120x55 — auto-sizing only activates for longer names
- Explicit `width`/`height` take precedence over `autoSize`
- **Not needed within `layout-within-group`** — that tool has its own `autoWidth` parameter

**After placement — `resize-elements-to-fit`:**
- Resizes all (or selected) elements on an existing view to fit their labels
- Two-pass algorithm for nested containment: children sized first, then parents sized to contain children + own label + padding
- Recommended after placing elements without `autoSize` or when element names change

**When to use which:**

| Scenario | Approach |
|----------|----------|
| Placing elements on flat view | `add-to-view` with `autoSize: true` |
| Bulk-creating elements | `bulk-mutate` with `autoSize: true` per add-to-view op |
| Elements inside groups | `layout-within-group` with `autoWidth: true` (existing feature) |
| Existing view with truncated labels | `resize-elements-to-fit` on the view |

## Common Pitfalls

- **Targeting "excellent" on dense views:** Views with 30+ connections rarely achieve excellent. Target "good" to avoid wasted ELK iterations. Reserve "excellent" for views with ≤20 connections.
- **Routing before hub sizing:** Always detect and resize hub elements before routing. Post-resize routing produces significantly cleaner paths because the router has more attachment point options on larger element perimeters.
- **Title note at (10,10) before layout:** Use `add-note-to-view` with `position: "above-content"` after layout is complete. This automatically computes coordinates from the content bounding box, eliminating note-element overlaps. Do NOT place notes at hardcoded coordinates (e.g., x=10, y=10) before layout — ELK and other algorithms will reposition elements into the note's space.
- **Too many relationship types on one view:** Filter `auto-connect-view` to 2-3 relationship types per view. Each additional type increases crossing pressure multiplicatively. If you need to show all relationship types, use separate views per type.
- **Iterating spacing past the narrow-corridor floor:** `assess-layout` reports `parallelConnectionGap.vAxisParallelGapP10` as an informational signal. When this value is persistently low (below ~6 px on a view that has already had 2-3 spacing inflations), the view is in the narrow-corridor regime — convenience spacing tools cannot mitigate further because the structural floor is determined by topology, not by router input. Stop at the current `fair` rating, or redesign topology (split the view, reduce hub fan-out) or apply manual bendpoint surgery via `update-view-connection`.
- **Ignoring `structuredWarnings[]` from `auto-route-connections`:** when the response carries `code: AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP`, the autoNudge phase was a hard gate skip. Re-running `auto-route-connections` without first calling `layout-within-group` on the parent of `remediationViolatorIds` will reproduce the same skip.

## Title Notes

Always add title notes AFTER completing layout, routing, and assessment. Use `position: "above-content"` on `add-note-to-view` to automatically place notes above the diagram content bounds — this eliminates note-to-element overlaps caused by hardcoded coordinates.

**Recommended workflow:**
1. Complete all layout, routing, and assessment iterations
2. `add-note-to-view` with `content: "View Title"` and `position: "above-content"`
3. Optionally adjust `gap` (default 10px) for more or less spacing between note and content

**Anti-pattern:** Do NOT place notes at hardcoded coordinates (e.g., x=10, y=10) before layout — ELK and other algorithms will reposition elements into the note's space, causing overlaps.

## Label Expressions (`labelExpression` on `update-view-object` / `set-view-label-expression`)

A **label expression** is a dynamic rendering template stored on a view object that Archi evaluates at render time. Unlike `text` (which sets a literal stored label for groups and notes), `labelExpression` is the *computed* rendering instruction — when the underlying element changes, every view that uses the expression updates automatically.

**Two common token shapes:**

| Token | Renders | Example use |
|-------|---------|-------------|
| `${name}` | The element's current name | Show the latest element name on this view object, no manual sync |
| `${property:KEY}` | The value of the element property named `KEY` | `${property:Owner}` renders the value of an `Owner` property on the element |

Archi supports a richer grammar (model name, documentation, type, modifiers). See the Archi user-guide topic *"Label Expressions"* for the full token catalog. This MCP server does not parse the grammar — Archi owns it; unknown tokens render as the literal `${...}`. The one server-side check rejects a template containing a literal HTML/XML entity token (e.g. `&amp;amp;`, `&amp;lt;`, `&amp;#160;`) with a corrective hint, so use the actual character (`&`, `<`, a non-breaking space) rather than its escaped form — a bare `&` and `${name} & ${property:x}` are fine.

**Semantics on `update-view-object`:**

- Omit the `labelExpression` parameter → no change.
- Pass a non-empty string → set the expression verbatim.
- Pass `""` (empty string) → clear the expression; Archi falls back to rendering the element's static name.

**Stamping one template across a whole view (`set-view-label-expression`):**

To apply the *same* expression to every eligible object on a view in one shot — the common case when retro-fitting an evidence-mark or status glyph onto an existing diagram — use the `set-view-label-expression` `bulk-mutate` operation instead of one `update-view-object` call per object. It writes all matched objects in a single atomic command (one undo unit), is idempotent, and is type-filterable: the default scope is ArchiMate **element** objects, and an optional `objectTypes` array widens it to `note` / `group`. A blank-named object is **skipped** when *setting* a template (so it never renders a half-empty glyph) but **included** when *clearing* (an empty/blank `labelExpression` removes the override everywhere); the result reports `appliedCount` / `skippedCount`.

**`text` vs `labelExpression`:**

- `text` writes the literal stored label for **groups** (`setName(...)`) or **notes** (`setContent(...)`). Rejected for element view objects.
- `labelExpression` writes the rendering template for any view object. When both are set, Archi's `labelExpression` wins at render time.

**Scope:** label expressions are stored per-view-object and are exposed on `update-view-object` (per object) and `set-view-label-expression` (one template across a view). Setting a label expression on the underlying ArchiMate element (via `update-element`) is **not supported** — Archi's renderer reads label expressions from the view-object layer, not the model-element layer.

## Styling Completeness

The styling rail on `add-to-view` / `add-group-to-view` / `add-note-to-view` / `update-view-object` and `add-connection-to-view` / `update-view-connection` covers typography, line style, gradient, note border type, derived line colour, and outline opacity in addition to the basic colour / opacity / line-width fields.

**Typography (fontName / fontSize / fontStyle):**
- One composite EMF field (`IFontAttribute.setFont(String)`); the three knobs are merged server-side against the existing font string, so partial updates preserve the other components.
- `fontStyle` enum: `"normal"` / `"bold"` / `"italic"` / `"bold-italic"`.
- `fontName: ""` clears to the system default view font. Unknown fonts fall back at render time — no installed-font pre-check.

**View-object lineStyle (view objects only — elements, groups, notes):**
- Values: `"solid"` (Archi default) / `"dashed"` / `"dotted"` / `"none"` (no visible outline).
- Applied via `IDiagramModelObject.setLineStyle(int)` (Archi 5.8 typed setter; LINE_STYLE_DEFAULT=-1, SOLID=0, DASHED=1, DOTTED=2, NONE=3).
- **Note:** Connection line style is NOT separately controllable in Archi 5.8 — it is determined by the ArchiMate relationship type. Setting `lineStyle` on connection tools is silently ignored.

**Gradient (view objects):**
- Values: `"none"` (Archi default — flat fill) / `"top-bottom"` / `"bottom-top"` / `"left-right"` / `"right-left"`.
- Direction names describe the gradient's origin side (e.g. `"top-bottom"` = gradient starts at the top, fades toward the bottom).

**Note borderType (notes only):**
- Values: `"dogear"` (Archi default — folded-corner note) / `"rectangle"` / `"none"`.
- Distinct from `figureType` (which uses `tabbed/rectangular` vocabulary on groups + ArchiMate Grouping elements). `borderType` is silently ignored on non-note objects.

**deriveLineColor (view objects):**
- Default `true`: outline colour is derived from fill (typically a darker shade — Archi's convention).
- Set `false` to honour an explicit `lineColor` verbatim regardless of fill.

**outlineOpacity (view objects):**
- 0-255, default 255. Distinct from `opacity` (fill opacity) — controls just the outline line's alpha.

**Container fill recession (auto-backdrop):**
- When `add-to-view` / `add-group-to-view` nests a child *inside* a container whose fill you have **not** explicitly set, the parent's fill auto-recedes to a subtle backdrop (`#F4F4F4`) so the nesting reads as depth, not a flat single-colour block ("the container is the canvas, the children are the figures").
- Provenance-gated and idempotent: only an unauthored (null) fill is touched; an explicitly coloured container is left untouched; an already-receded parent is a no-op; the root view is excluded.
- The recede rides the placement as one undo unit. Pass `recede: false` to opt out for a call.
- Prefer letting the recession run over hand-colouring a container backdrop. If you *do* set a container fill, keep it distinct (lighter) from its children — `assess-layout` flags a container whose authored fill equals a child's (`containerFillEqualsChildCount`).

**Read-back discipline:** all styling fields are omitted from JSON when the underlying EMF state is at Archi's default. Set fields are echoed in the response DTO under the same names. All changes execute as a single undo unit alongside the basic styling fields.

**Note on connection line style:** Connection line style (dashed / dotted / etc.) is NOT separately controllable in Archi 5.8 — Archi derives it from the ArchiMate relationship type. The `lineStyle` parameter is a view-object property; setting it on `add-connection-to-view` or `update-view-connection` is silently ignored.
