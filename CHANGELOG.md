# Changelog

## v1.2.0 (2026-04-07)

Quality, completeness, and routing diversity cycle. Tool count grew from 57 to 60. Corridor diversity routing reduces coincident segments by spreading connections across available corridors. Auto-sizing ensures element labels are never truncated. Full CRUD coverage achieved for all core model objects.

### New Tools

- **clone-view** — Duplicate an existing view with all visual contents (elements, groups, notes, connections, bendpoints, styling). The clone references the same model objects — useful for layout experiments or presenting alternative arrangements for comparison.
- **update-relationship** — Update relationship name, documentation, or properties. Source, target, and type are immutable (delete and recreate to change those). Completes full CRUD coverage for relationships (previously only create/delete existed).
- **resize-elements-to-fit** — Resize all (or selected) elements on a view to fit their labels using SWT font metrics. Two-pass algorithm: children sized first, then parents sized to contain children + own label + padding. Recommended after placing elements without `autoSize` or when element names change.

### New Capabilities

- **Auto-size at placement** — `add-to-view` accepts `autoSize: true` to compute element dimensions from label text at placement time using SWT font metrics with aspect-ratio-aware sizing (target 1.5:1, range [1.2:1, 2.5:1]). Short names (≤15 chars) keep default 120x55. Explicit `width`/`height` take precedence. Eliminates the need for a post-placement resize pass on flat views.
- **Corridor diversity routing** — `CorridorOccupancyTracker` records which corridors are used by previously routed connections. The A* cost function applies a multiplicative penalty for occupied corridors (`effectiveDistance *= 1 + occupancyWeight * occupancy`, default weight 0.75), encouraging route diversity and reducing coincident segments without post-processing.

### Routing Pipeline Improvements

- **Occupancy-aware A* routing** — New `CorridorOccupancyTracker` (pure-geometry class) records axis-aligned corridor usage after each connection is routed. Corridor keys use tolerance-aware grouping (`H:y` / `V:x`, 2px tolerance) matching the `CoincidentSegmentDetector` and `PathOrderer` formats. The A* router queries occupancy per edge and applies a multiplicative cost: `effectiveDistance *= (1 + occupancyWeight * occupancy)`. This steers later connections away from corridors already carrying traffic, producing visually diverse paths and reducing the need for post-routing coincident segment resolution.

### Bug Fixes

- Fix autoNudge false positive on containment overlaps — `OverlapResolver.hasOverlappingElements()` now excludes containment overlaps (parent-child nesting, e.g., ApplicationFunction inside ApplicationComponent). Previously, legitimate visual nesting was incorrectly detected as sibling overlap, causing autoNudge to skip routing and report degenerate geometry.
- Fix grouped layout group overlap and boundary violations — `arrange-groups` and `layout-within-group` now correctly prevent group-on-group overlaps and child elements extending outside parent group boundaries after layout operations.
- Fix Claude Code MCP config `type` field — README client configuration example corrected from `"type": "streamable-http"` to `"type": "http"`. Added Claude Desktop configuration instructions with `mcp-proxy` for both Windows and macOS.

### Resource Updates

- **archimate-view-patterns.md** — Added `autoSize: true` guidance to all view composition workflow branches (Branch 1, 2, 3). Added "Auto-Sizing Elements to Fit Labels" section with decision table for when to use `autoSize` vs `resize-elements-to-fit` vs `layout-within-group` `autoWidth`. Added `clone-view` guidance to Tips section for layout experiments and alternative arrangement comparison.

### Documentation

- Routing pipeline documentation updated with corridor diversity section (CorridorOccupancyTracker, occupancy-aware A* cost formula, configuration constants).
- Layout engine documentation updated with auto-size and resize-elements-to-fit section.
- Architecture documentation updated with CorridorOccupancyTracker in pure-geometry routing subpackage, updated handler tool counts.
- README updated with complete 60-tool catalog, new tool entries for clone-view, update-relationship, and resize-elements-to-fit.

---

## v1.1.0 (2026-04-03)

Post-release enhancement cycle: 84 commits, 12 Epic 13 stories, 41+ backlog items. Tool count grew from 51 to 56. Routing pipeline refined through 19 quality iterations (B31-B46) achieving clearance-weighted pathfinding with corridor directionality, group-wall awareness, path straightening, terminal orthogonality enforcement, and severity-tiered quality assessment.

### New Tools

- **search-relationships** — Search all relationships by text, type, and source/target element layer without needing an element ID first. Mirrors the search-elements pattern with pagination and field selection.
- **detect-hub-elements** — Identify high-connectivity elements on a view, sorted by connection count. Returns sizing suggestions using the hub element formula for elements with >6 connections.
- **layout-flat-view** — Automatic layout for flat (non-grouped) views with row, column, or grid arrangement. Supports `sortBy` (name/type/layer) and `categoryField` (type/layer) for organized grouping.
- **add-image-to-model** — Import images into the model archive for use on view objects. Images are deduplicated — re-importing the same bytes returns the existing path.
- **list-model-images** — List all images stored in the model archive with paths and dimensions.

### New Capabilities

- **Grouped-view layout mode** — `auto-layout-and-route` with `mode: "grouped"` orchestrates the full grouped-view workflow (layout-within-group + arrange-groups + optimize-group-order + auto-route-connections) in a single atomic tool call with quality iteration. Replaces a manual 5-7 step sequence.
- **Custom images on elements, groups, and notes** — `add-to-view`, `add-group-to-view`, `add-note-to-view`, and `update-view-object` accept `imagePath`, `imagePosition`, and `showIcon` parameters for custom 16x16 icons.
- **Connection label positioning** — `add-connection-to-view` and `update-view-connection` accept `labelPosition` (`"source"`, `"middle"`, `"target"`) to control where labels sit along the connection path.
- **Connection label suppression** — `add-connection-to-view`, `update-view-connection`, and `auto-connect-view` accept `showLabel: false` to suppress relationship name labels.
- **Connection styling at creation** — `add-connection-to-view` accepts `lineColor`, `fontColor`, and `lineWidth` for styling connections when they are first placed.
- **View containment tree format** — `get-view-contents` with `format=tree` returns a compact group hierarchy for token-efficient group discovery before layout operations.
- **Image export directory control** — `export-view` accepts `outputDirectory` to control where PNG/SVG files are saved (auto-creates directories).
- **Topology group arrangement** — `arrange-groups` with `arrangement: "topology"` orders groups by inter-group connection density to minimize long-range crossings. Supports `direction: "horizontal"` for left-to-right flow patterns.
- **Duplicate relationship prevention** — `create-relationship` is now idempotent — creating a relationship that already exists returns the existing one instead of producing a duplicate.

### Routing Pipeline Improvements

- **Clearance-weighted A* routing** — A* cost function includes perpendicular clearance penalty (`clearanceWeight / max(clearance, 1.0)`, default weight 75.0) that steers the router toward open space. `computePerpendicularClearance()` measures distance from each graph edge to the nearest obstacle boundary. E2E result: crossings -41%, pass-throughs eliminated on test views.
- **Post-routing path straightening** — New `PathStraightener` class applies four correction passes after routing: snap-to-straight alignment for near-aligned segments, direction reversal elimination, staircase jog collapse, and redundant bend removal. Terminal anchors are protected throughout.
- **Post-simplification path shortcutting** — Late-stage greedy simplification (Stage 4.7g) finds farthest reachable point via straight line, horizontal-first L-turn, or vertical-first L-turn. Terminal bendpoints preserved as chain anchors. E2E result: crossings -27%.
- **Post-simplification coincident segment resolver** — Redesigned from endpoint-based (~350 lines) to corridor-based reuse of `CoincidentSegmentDetector` (~12 lines). Catches coincidences introduced by all post-processing stages. E2E result: coincident segments -50%.
- **Proportional corridor spacing** — Replaced fixed 10px offset delta with proportional gap distribution. Three-pass architecture: collect corridor groups, compute perpendicular gap via obstacle scanning, distribute segments proportionally with 8px minimum separation floor. Falls back to fixed-delta when corridor is too narrow. E2E result: coincident segments -80%.
- **Exterior perimeter routing** — Split obstacle clearance (10px) from perimeter boundary margin (50px), enabling routes to travel around the outside of element clusters. E2E result: routing success 12% to 100%.
- **Pass-through-aware face selection** — Phase 1.3 in `EdgeAttachmentCalculator` builds trial paths with current face assignments and checks for self-element pass-throughs. When detected, tries alternative faces in angular proximity order. Phase B re-routes terminal segments after face swap. E2E result: pass-throughs -75%.
- **Router corridor re-route** — Stage 5a re-routes failed connections (element crossings) using fresh A* search with the full pipeline cleanup sequence.
- **Terminal approach direction** — Phase 1.2 in `EdgeAttachmentCalculator` prefers natural entry direction for nearly-aligned elements (dominant axis > 2x minor axis). Hub elements excluded to preserve distributed port allocation.
- **Self-element pass-through correction** — Two-phase fix: face swap (B34) + comprehensive face selection with trial path validation (B35). Safety net re-run of `correctEndpointPassThroughs()` retained as defense-in-depth.
- **Corridor directionality penalty** — Cosine-based `computeCorridorDirectionalityCost()` penalizes A* edges that move perpendicular or away from the target (`directionalityWeight * (1 - cos(angle)) / 2`, default weight 30.0). Steers connections toward direct approach paths. E2E result: non-orthogonal terminals reduced from 33 to 2 on Application Collaboration view.
- **Clearance cap** — `MAX_EFFECTIVE_CLEARANCE` (60px) caps the clearance benefit to prevent exterior corridors with unlimited space from becoming artificially attractive ("perimeter suction"). Clearance cost formula: `clearanceWeight / max(min(clearance, 60), 1.0)`.
- **Group-wall clearance cost** — `computeGroupWallClearance()` measures perpendicular distance from A* edges to the nearest group boundary. Edges running inside groups (close to group walls) are penalized, steering the router toward inter-group gaps rather than inside-group-wall corridors. Uses the same clearance weight and MAX_EFFECTIVE_CLEARANCE cap as obstacle clearance.
- **Center-termination fix** — Stage 4.7k `fixCenterTerminatedPath()` detects bendpoints at exact element center coordinates (zero-length ChopboxAnchor ray → visual center termination) and repositions them 1px outside the correct edge face midpoint. Applied twice: after edge attachment and as defense-in-depth after cleanup.
- **Post-nudge center-termination gap** — Re-runs `fixCenterTerminatedPath()` after edge nudging to catch center-terminations introduced by nudge position shifts.
- **Interior terminal BP fix** — Stage 4.7m `fixInteriorTerminalBPs()` detects and fixes all bendpoints inside source/target element bounds (not just at exact center). Terminal BPs are repositioned to edge face midpoints; intermediate interior BPs are removed. L-bends are inserted where repositioning breaks orthogonality.
- **Post-pipeline orthogonality enforcement** — Stage 4.7n `enforceOrthogonalPaths()` detects diagonal segments remaining after all processing and inserts horizontal-first L-turn bendpoints to restore orthogonality. Catches edge cases where cleanup stages remove BPs without reinserting L-bends.
- **Approach direction threshold relaxation** — Phase 1.2 `correctApproachDirection()` threshold relaxed from 2:1 to 1.2:1 dominant-to-minor axis ratio. More connections now receive natural approach direction correction, reducing diagonal terminal segments.

### Assessment Improvements

- **Severity-tiered rating recalibration** — Overall quality rating uses a three-tier severity system instead of worst-metric-wins. Tier 1 (critical): overlaps, pass-throughs, coincident segments — drives overall rating directly. Tier 2 (moderate): crossings, non-orthogonal terminals — contribution capped at "fair". Tier 3 (cosmetic): spacing, alignment, label overlaps — contribution capped at "good".
- **Non-orthogonal terminal metric** — New `nonOrthogonalTerminalCount` metric with per-metric rating in the assessment breakdown (0 = "pass", 1-3 = "fair", 4+ = "poor").
- **Coincident segment rating** — `coincidentSegmentCount` now has its own per-metric rating (0 = "pass", 1-3 = "good", 4-8 = "fair", 9+ = "poor").
- **Relaxed leniency gate** — Grouped-view crossing leniency allows up to 3 pass-throughs (previously required 0).

### Routing Quality Improvements

- **Fallback edge port routing** — When the primary port leads to a failed route, the router tries up to 3 alternative source/target port combinations before giving up.
- **Auto-nudge on route failure** — `auto-route-connections` with `autoNudge: true` automatically moves blocking elements and re-routes failed connections in a single atomic operation (up to 2 iterations).
- **Auto-nudge group boundary awareness** — When elements are nudged, parent groups resize to accommodate the new positions.
- **Router exit point centering** — Connection terminal segments now exit from the nearest edge center instead of using ray-to-first-bendpoint geometry, producing cleaner perpendicular exits.
- **Router snap-to-straight threshold** — Eliminates Z-bends for port offsets of 20px or less, producing straight connections where near-alignment exists.
- **Hub port distribution** — Edge attachment points for high-connectivity elements are distributed evenly across the element face, reducing connection bundling.
- **Routing quality guardrails** — Crossing inflation detection and 8px minimum bendpoint clearance enforcement prevent quality regressions.
- **Route crossing delta response** — Routing responses now include before/after crossing metrics for quality comparison.

### Layout Improvements

- **Label-aware hub sizing** — Hub sizing suggestions account for label dimensions when recommending element size increases.
- **ELK group non-overlap constraint** — AABB sweep-line correction prevents ELK from overlapping groups in hierarchical layouts.
- **ELK limiting factor response** — Layout responses now report which constraint (spacing, hierarchy depth, group count) limited the result quality.
- **Multi-trial label optimizer** — Label position optimization runs multiple trials with targetRating fallback to find positions that minimize overlaps.
- **Per-group arrangement preservation** — `optimize-group-order` preserves internal arrangement patterns (row/column/grid) when reordering elements to minimize crossings.
- **Adjacent-swap local search** — `CrossingMinimizer` uses adjacent-swap local search as a secondary strategy when barycentric heuristic stalls.
- **Flat view embedded children** — `layout-flat-view` correctly repositions elements with embedded children by treating them as larger boxes.

### Bug Fixes

- Fix autoNudge `INTERNAL_ERROR` on overlapping elements — overlapping geometry is now detected and reported cleanly instead of crashing.
- Fix autoNudge `SWTException` threading error — nudge operations properly dispatch to the SWT UI thread.
- Fix multi-iteration nudge position accumulation bug — deltas are now computed from original positions.
- Fix groups and notes missing from `get-view-contents` — FieldSelector bug that filtered out non-element view objects.
- Fix orphaned relationship structural fix — `connect()` timing corrected to prevent relationships from losing their source/target references.
- Fix relationship validation error messages — valid relationship types now appear in the main error message rather than requiring a separate lookup.

### Resource Updates

- **archimate-relationships.md** — Added Common Mistakes section documenting frequent LLM errors (e.g., using Composition where Assignment is correct).
- **archimate-view-patterns.md** — Updated decision trees for grouped-view layout, added flat-view workflow, added topology arrangement guidance, note placement best practices, and hub sizing workflow.

### Documentation

- Routing pipeline documentation updated with stages 4.7g-4.7n, 5a; clearance-weighted A* cost function with corridor directionality and group-wall clearance; proportional corridor spacing; path straightening; center-termination fix; interior terminal BP correction; orthogonality enforcement safety net; and updated configuration constants.
- Layout engine documentation updated with severity-tiered rating system, new assessment metrics (`nonOrthogonalTerminals`, `coincidentSegments` rating), and tier capping logic.
- Architecture documentation updated with `PathStraightener` in pure-geometry routing subpackage.
- README updated with complete 56-tool catalog, image management section, and grouped layout mode.

---

## v1.0.0 (2026-03-16)

Initial release. 51 MCP tools across querying, searching, creating, layout, routing, assessment, batch operations, and more. 6 MCP resources with ArchiMate reference material and workflow guides.

See [README.md](README.md) for the complete tool catalog and documentation.
