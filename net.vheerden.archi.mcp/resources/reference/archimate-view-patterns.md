# ArchiMate View Patterns and Layout Guidance

## Recommended LLM Model Size

This MCP server exposes 50+ tools for ArchiMate model manipulation. Model size impacts reliability:

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

For clean orthogonal routing, use `auto-route-connections` after placing elements. It computes obstacle-aware paths that avoid element crossings using a visibility graph + A* algorithm. **This is the primary routing approach for ALL connected views, including views with groups.**

**IMPORTANT — Manhattan routing is almost never the right choice:** Manhattan (`connectionRouterType: "manhattan"`) draws right-angle paths but does **NOT** avoid element obstacles — connections pass straight through sibling elements, producing poor visual quality. **Do NOT use Manhattan if the view has more than 5 connections or any inter-group connections.** Only use Manhattan for simple structure/catalogue views with very few connections (≤5) where routing quality is not critical.

**`auto-route-connections` produces orthogonal (right-angle) paths** — visually identical to Manhattan but with obstacle avoidance. When this tool switches the view from Manhattan to bendpoint mode, only the storage format changes — connections remain right-angle/orthogonal. There is no visual quality tradeoff.

**Routing workflow — iterate until satisfied:**

1. Place/reposition elements with generous spacing (40px+ for grouped views, 100px+ for dense flat layouts)
2. Run `auto-route-connections` to compute clean orthogonal paths
3. Run `assess-layout` to check for pass-throughs and violations
4. If pass-throughs exist: increase element spacing (re-run `layout-within-group` with larger `elementSpacing`, e.g., 60-80px) and/or reposition groups with `apply-positions`
5. Re-run `auto-route-connections` and `assess-layout`
6. Repeat until pass-throughs are eliminated. Note: edge crossings (connections crossing each other, not through elements) are often structurally unavoidable in dense many-to-many views and do not necessarily indicate a layout problem.

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

Use `add-connection-to-view` as a **fallback** for individual connections — e.g., when you need to connect specific relationships one at a time.

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
- **`spacing`** (default 40): Gap in pixels between groups. Use larger values (80-100) for views with many inter-group connections — this creates **routing corridors** (whitespace channels between groups) that `auto-route-connections` can use for cleaner orthogonal paths. Dense hub-and-spoke topologies benefit significantly from wider inter-group spacing.
- **`groupIds`** (optional): Arrange only specific groups, leaving others in place.
- **Anti-pattern:** Do NOT manually compute x/y coordinates for groups when `arrange-groups` can do it automatically.

**Typical workflow:** `layout-within-group` per group (sizes groups to fit contents) → `arrange-groups` (positions groups relative to each other) → `auto-connect-view` → `optimize-group-order` → `arrange-groups` (fix any group overlaps from reordering) → resize hub elements → `auto-route-connections`.

### Optimising Hub Elements for Dense Views

On views with hub-and-spoke topologies (e.g., integration architecture with an ESB or API gateway), elements with many connections (>6) create **port congestion** — all connections compete for attachment points on a small perimeter. This produces bundled, overlapping connections that are hard to read.

**Hub element dimension increase:** After layout, identify elements with high connection counts and increase the element dimension **perpendicular to the primary connection flow direction** using `update-view-object`. Larger elements provide more perimeter space for connection attachment points, spreading connections across a larger surface.

- **Which dimension to increase:** For horizontal layouts (left→right groups), increase **height** — this creates more vertical perimeter for side-facing connections. For vertical layouts (top→bottom groups), increase **width** — this creates more horizontal perimeter for top/bottom connections. For true hub elements receiving connections from all directions, increase **both**.
- **Rule of thumb:** For elements with more than 6 connections, increase the relevant dimension proportionally: `dimension = baseDimension + 15px × (connectionCount - 6)`. For example, an element with 14 connections in a horizontal layout: `height = 55 + 15 × 8 = 175px`.
- Use `get-view-contents` to identify which elements have the most connections, then `update-view-object` to set their height and/or width.
- After resizing, re-run `auto-route-connections` — the larger element surfaces give the router more attachment point options.

**When to apply:** After `optimize-group-order` and before `auto-route-connections` in the grouped view workflow. Hub heightening + wide inter-group spacing (80-100px+ via `arrange-groups`) work together — taller hubs spread attachment points while wider spacing creates routing corridors.

### Containment & Parent Movement

Child coordinates are **relative** to their parent's top-left corner. When you reposition a parent (group or element) via `update-view-object`, all contained children move with it automatically — their relative positions are preserved.

**This means you only need to position top-level containers.** Children inside groups do not need individual repositioning when moving the group. This dramatically reduces coordinate computations.

Elements contained inside groups or other elements should have a **composition relationship** to the parent. This is standard ArchiMate modelling practice and reinforces the visual containment in the model.

## View Composition Workflow (Decision Tree)

Choose the right workflow based on your view type:

```
Is the view connected (showing relationships)?
│
├── NO (catalogue/inventory view)
│   ├── Will elements be in groups?
│   │   ├── YES → create groups → add elements with parentViewObjectId
│   │   │        → layout-within-group for each group (autoResize=true)
│   │   └── NO  → add elements → compute-layout with grid/tree algorithm
│   └── export-view to verify
│
└── YES (relationship view)
    ├── Will elements be in groups? (Branch 2 — RECOMMENDED for grouped views)
    │   └── create-view (do NOT set manhattan) → add groups → add elements
    │        → layout-within-group per group (spacing 40px+, autoResize=true)
    │        → arrange-groups (spacing 80-100px+ for routing corridors)
    │        → auto-connect-view → optimize-group-order
    │        → heighten hub elements (>6 connections)
    │        → auto-route-connections → assess-layout
    │        → IF poor: increase spacing → re-route → re-assess → iterate
    │
    ├── Flat view, LLM-managed positions? (Branch 2 flat)
    │   └── place elements → auto-connect-view → auto-route-connections
    │        → assess-layout → reposition if needed → re-route → iterate
    │
    └── Algorithmic positions? (Branch 3 — ELK)
        ├── Flat view: place elements → auto-connect-view
        │    → auto-layout-and-route (ELK)
        └── Grouped view: create groups → nest elements
             → auto-connect-view → auto-layout-and-route (ELK)
             → auto-route-connections (fixes inter-group routing)
             → assess-layout → iterate if needed
```

### Branch 1: Non-Connected View (Catalogue/Inventory)

No relationships shown — focus on element organisation.

1. `create-view` with name and optional viewpoint
2. **If grouped:** `add-group-to-view` for each group → `add-to-view` with `parentViewObjectId` for each element → `layout-within-group` per group with `autoResize: true`
3. **If flat:** `add-to-view` for each element → `compute-layout` with `grid` or `tree` algorithm
4. `export-view` to verify

### Branch 2: Connected View, LLM-Managed Positions (RECOMMENDED for grouped connected views)

The LLM controls element/group placement. Use this for any connected view where you want groups for visual organisation (layers, zones, clusters).

**For grouped views:**

1. `create-view` with name (do NOT set `connectionRouterType: "manhattan"`)
2. `add-group-to-view` for each group
3. `add-to-view` with `parentViewObjectId` to nest elements in groups
4. `layout-within-group` per group with `autoResize: true` and `elementSpacing: 40`+
5. `arrange-groups` to position groups relative to each other (use `grid`, `row`, or `column` arrangement; use `spacing: 80`+ for views with many inter-group connections to create routing corridors)
6. `auto-connect-view` to batch-create all connections
7. `optimize-group-order` to minimize inter-group edge crossings by reordering elements within groups — then re-run `arrange-groups` to fix any group-on-group overlaps caused by reordering
8. **Resize hub elements:** identify elements with >6 connections and increase their height and/or width via `update-view-object` (see "Optimising Hub Elements" section) — increase the dimension perpendicular to the primary connection flow direction
9. `auto-route-connections` to compute clean orthogonal paths
10. `assess-layout` to identify violations
11. **If poor rating or pass-throughs:** re-run `layout-within-group` with increased `elementSpacing` (60-80+) → re-run steps 7-10 → repeat until "fair" or better
12. `export-view` to verify

**For flat views (no groups):**

1. `create-view` with name
2. Place elements with approximate positions
3. `auto-connect-view` to batch-create all connections
4. `auto-route-connections` to compute clean orthogonal paths
5. `assess-layout` → reposition if needed → re-route → repeat
6. `export-view` to verify

**Tip:** Start with generous spacing (40px+ within groups, 100px+ between groups). Dense layouts cause routing problems. You can always tighten spacing later.

### Branch 3: Connected View, Algorithmic Positions (ELK)

Let the ELK algorithm control element positioning. Best for quick professional results.

**For flat views (no groups):**

1. `create-view` with name
2. `add-to-view` for each element (positions don't matter — ELK will override them)
3. `auto-connect-view` to batch-create all connections
4. `auto-layout-and-route` with desired direction, spacing, and `targetRating: "good"` (recommended)
5. `export-view` to verify

**For grouped views:**

1. `create-view` with name
2. `add-group-to-view` for each group
3. `add-to-view` with `parentViewObjectId` to nest elements in groups (ELK preserves containment — children stay inside their parents during layout)
4. `auto-connect-view` to batch-create all connections
5. `auto-layout-and-route` with desired direction, spacing, and `targetRating: "good"` (recommended)
6. **`auto-route-connections`** to re-route with obstacle-aware orthogonal paths (**critical for grouped views** — see note below)
7. `assess-layout` to verify quality
8. `export-view` to verify

**Quality target (recommended):** Use `targetRating` on `auto-layout-and-route` to automate the quality iteration loop. The tool internally runs `assess-layout` and iterates with increasing spacing until the target rating is achieved (up to 5 attempts). This eliminates the need for manual assess → adjust → re-layout loops. Use `targetRating: "good"` for most views, or `"excellent"` for presentation-quality diagrams.

**IMPORTANT — ELK routing limitation on grouped views:** ELK positions elements well in hierarchical layouts, but its inter-group connection routing operates at the group boundary level — it does not see individual elements inside groups as obstacles. This produces diagonal (non-orthogonal) connections that pass through elements. **Follow ELK with `auto-route-connections`** on grouped views to get clean orthogonal paths with element-aware obstacle avoidance. For flat views, ELK routing is generally adequate on its own. **However:** after running `auto-route-connections`, always run `assess-layout` to check if crossings improved — in some cases (dense hub-and-spoke topologies), the obstacle-aware router produces *more* crossings than ELK's simpler routing. If crossings increased, `undo` the auto-route and keep ELK's routing.

**When to use Branch 3 (ELK) vs Branch 2 (manual):** ELK is the fastest path to professional element positioning — one tool call positions all elements and groups. Use Branch 2 when you want fine-grained control over group positioning (e.g., specific grid arrangements via `arrange-groups`) or when iterative spacing adjustments are needed. For grouped views, both branches require `auto-route-connections` as the final routing step.

### Tips

- Run `assess-layout` before and after layout changes to measure improvement
- If quality is poor after one algorithm, try a different one — `spring` and `directed` often complement each other
- Use `apply-positions` for fine-tuning individual element positions without re-running the full algorithm
- When initial placement will be refined by routing iteration, use approximate coordinates — don't waste effort on precision that will be overridden
- **Note placement:** Notes are excluded from layout algorithms (`compute-layout`, `auto-layout-and-route`, `layout-within-group`) and do not affect `assess-layout` quality scoring. Notes can be added at any time — before or after layout. Place title notes at canvas position (10, 10) or in a known empty area. Note-element overlaps are reported informatively by `assess-layout` but do not penalize the rating
