# ArchiMate View Patterns and Layout Guidance

## Recommended LLM Model Size

This MCP server exposes 60 tools for ArchiMate model manipulation. Model size impacts reliability:

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
6. Repeat until pass-throughs are eliminated. Note: edge crossings (connections crossing each other, not through elements) are often structurally unavoidable in dense many-to-many views — the severity-tiered rating caps their impact at "fair" so they do not mask structural quality.

**Key insight:** `auto-route-connections` works correctly with groups. When routing quality is poor, the issue is element/group positioning (tight spacing, overlapping bounding boxes), not the presence of groups. Give the router space to work — dense layouts produce poor routes.

**When to use which routing approach:**

| Approach | Use When |
|----------|----------|
| `auto-route-connections` | **PRIMARY** — any connected view (flat or grouped) needing clean orthogonal paths |
| `auto-layout-and-route` (ELK) | You want the algorithm to control element positions in one step. Best for flat views. **For grouped views:** ELK positions elements well but inter-group connection routing may produce diagonal/non-orthogonal paths — follow up with `auto-route-connections` to fix routing. |
| Manhattan (`connectionRouterType: "manhattan"`) | **CAUTION** — only for ≤5 connections on simple structure views with no inter-group routing. Produces pass-throughs on dense views. |

## Adding Connections

Use `auto-connect-view` to batch-create visual connections for all existing model relationships between elements already on the view. This is the **primary** approach — it handles all connections in one call.

**Filtering options** for `auto-connect-view`:
- `relationshipTypes`: Only connect specific types (e.g., `["ServingRelationship", "FlowRelationship"]`)
- `elementIds`: Only consider relationships involving specific elements
- Both filters can be combined for precise control

Use `add-connection-to-view` as a **fallback** for individual connections — e.g., when you need to connect specific relationships one at a time. Both `add-connection-to-view` and `update-view-connection` accept optional styling (`lineColor`, `fontColor`, `lineWidth`), `showLabel: false` to suppress the relationship name label, and `labelPosition` (`"source"`, `"middle"`, or `"target"`) to control where the label sits along the connection path. Use `labelPosition` to reduce label overlaps on dense diagrams — e.g., place labels near the target end when source-end labels collide with nearby elements.

**Anti-pattern:** Do NOT use `bulk-mutate` with repeated `add-connection-to-view` operations when `auto-connect-view` can do it in one call.

**Composition relationships and visual nesting:** When elements are visually nested inside parent elements or groups (e.g., availability zones inside a cloud region), the visual containment already communicates the composition. Do NOT create visual connections for `CompositionRelationship` on such views — use the `relationshipTypes` filter on `auto-connect-view` to exclude them (e.g., `relationshipTypes: ["ServingRelationship", "FlowRelationship", "AssignmentRelationship"]`). Showing composition arrows on top of visual nesting is redundant and clutters the diagram.

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

### Containment & Parent Movement

Child coordinates are **relative** to their parent's top-left corner. When you reposition a parent (group or element) via `update-view-object`, all contained children move with it automatically — their relative positions are preserved.

**This means you only need to position top-level containers.** Children inside groups do not need individual repositioning when moving the group. This dramatically reduces coordinate computations.

Elements contained inside groups or other elements should have a **composition relationship** to the parent. This is standard ArchiMate modelling practice and reinforces the visual containment in the model.

## Pre-Layout Planning Checklist

Before calling any layout or routing tools, complete this analysis. Skipping these steps is the primary cause of quality variance between runs.

### 1. Hub Identification

1. Count connections per element from the model (use `get-relationships` or `detect-hub-elements` after adding elements to the view)
2. Elements with 5+ connections are **hubs** — they need 2-3x the default spacing
3. Place hubs near the geometric center of the view or their group
4. Resize hubs before routing: `height = baseDimension + 15px × (connectionCount - 6)` for elements with >6 connections

### 2. Spacing Heuristics

| Total connections on view | Element spacing | Group spacing (connected) | Group spacing (unconnected) |
|--------------------------|----------------|--------------------------|----------------------------|
| ≤15 | 60px | 80px | 40px |
| 16–30 | 80px | 100px | 40px |
| 30+ | 100px | 120px | 60px |

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

## Which `get-view-contents` Format to Use

| Format | Use When | Returns |
|--------|----------|---------|
| `json` (default) | Full element/relationship detail, field selection needed | Standard result with elements, relationships, visualMetadata, groups, notes |
| `tree` | **Group discovery before layout** — find group viewObjectIds and their children | Compact containment hierarchy with `tree` array + `stats` (totalGroups, ungroupedElements, etc.) |
| `graph` | Deduplicated node/edge analysis | `nodes`/`edges` structure |
| `summary` | Quick natural language overview | Condensed text summary |

**Grouped view tip:** Start with `format=tree` to discover group viewObjectIds and containment structure before calling `layout-within-group`, `arrange-groups`, or `optimize-group-order`. The tree format is much more token-efficient than `json` for this purpose — it returns only viewObjectId, type, name/label, and children.

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
│   │   └── NO  → add elements (autoSize=true) → layout-flat-view (grid/row/column) or compute-layout (tree/spring/directed)
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
3. **If flat:** `add-to-view` with `autoSize: true` for each element → `layout-flat-view` with `arrangement: "grid"` (or `compute-layout` with `grid`/`tree` algorithm for graph-aware positioning)
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

**IMPORTANT — ELK routing limitation on grouped views:** ELK positions elements well in hierarchical layouts, but its inter-group connection routing operates at the group boundary level — it does not see individual elements inside groups as obstacles. This produces diagonal (non-orthogonal) connections that pass through elements. **Follow ELK with `auto-route-connections`** on grouped views to get clean orthogonal paths with element-aware obstacle avoidance. For flat views, ELK routing is generally adequate on its own. **However:** after running `auto-route-connections`, always run `assess-layout` to check if crossings improved — in some cases (dense hub-and-spoke topologies), the obstacle-aware router produces *more* crossings than ELK's simpler routing. If crossings increased, `undo` the auto-route and keep ELK's routing.

**When to use Branch 3 (ELK) vs Branch 2 (manual):** Choose based on **structural intent**, not connection count alone. Use **Branch 2** when the LLM needs a specific layout structure (layered groups, directional flows like producer→middleware→consumer, specific group positioning). Use **Branch 3 (ELK)** when no specific structure is required and you want the best quality score — ELK achieves "good" in 1-2 iterations on dense views. Connection count is a secondary heuristic: dense views (>15 connections) are harder to achieve good quality on with Branch 2, so iterate more aggressively on spacing/sizing rather than abandoning the structural intent. For grouped views, both branches require `auto-route-connections` as the final routing step.

### Tips

- Run `assess-layout` before and after layout changes to measure improvement
- If quality is poor after one algorithm, try a different one — `spring` and `directed` often complement each other
- Use `apply-positions` for fine-tuning individual element positions without re-running the full algorithm
- When initial placement will be refined by routing iteration, use approximate coordinates — don't waste effort on precision that will be overridden
- **Note placement:** Notes are excluded from layout algorithms (`compute-layout`, `auto-layout-and-route`, `layout-within-group`) and do not affect `assess-layout` quality scoring. Use `position: "above-content"` on `add-note-to-view` after layout is complete to place title notes automatically above diagram content. Note-element overlaps are reported informatively by `assess-layout` but do not penalize the rating
- **View cloning for layout experiments:** Before trying a fundamentally different layout approach (switching algorithm, restructuring groups, changing direction), use `clone-view` to preserve the current state. Experiment on the clone — if the new approach is worse, delete the clone and keep the original. This is safer than relying on multiple `undo` operations across a complex layout sequence. Also useful for presenting alternative layouts to the user for comparison (e.g., clone a view, apply ELK to the clone, keep the original grouped layout — let the user choose)

## Images & Icons on Elements

When adding custom images (icons) to elements via `add-image-to-model` + `update-view-object`:

- **Avoid `top-right` position:** Archi displays the element's ArchiMate type icon in the top-right corner by default. Placing a custom image there will be obscured by the type icon.
- **Recommended position:** Use `bottom-left` for small icons (16x16 or 32x32). This keeps the type icon visible and places the custom image in an unoccupied corner.
- **To show both type icon and custom image:** Place them in different corners (e.g., type icon stays top-right, custom image in bottom-left).
- **To hide the type icon:** Set `showIcon: "never"` on the view object. This frees up top-right for custom images if needed.
- **Icon size:** 16x16 icons may be barely visible on large elements (120+ px wide). Consider 32x32 or larger icons for better visibility, or use `fill` position to stretch the image.
- **Workflow:** Import images with `add-image-to-model` first, then apply to view objects. Set `imagePosition` and `showIcon` in the same `update-view-object` call to avoid intermediate states where both icons overlap.

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

## Title Notes

Always add title notes AFTER completing layout, routing, and assessment. Use `position: "above-content"` on `add-note-to-view` to automatically place notes above the diagram content bounds — this eliminates note-to-element overlaps caused by hardcoded coordinates.

**Recommended workflow:**
1. Complete all layout, routing, and assessment iterations
2. `add-note-to-view` with `content: "View Title"` and `position: "above-content"`
3. Optionally adjust `gap` (default 10px) for more or less spacing between note and content

**Anti-pattern:** Do NOT place notes at hardcoded coordinates (e.g., x=10, y=10) before layout — ELK and other algorithms will reposition elements into the note's space, causing overlaps.
