---
title: Glossary
description: Definitions of the layout-quality metrics, routing terms, and rating tiers used across the ArchiMate MCP Server tools, resources, and documentation.
---

# Glossary

This glossary is the single source of truth for the metric acronyms (M1–M6, R8, `parallelConnectionGap_V_p10`), routing terms, and rating tiers that appear in tool descriptions, MCP resources, the [README](../README.md), and the rest of the technical documentation. When a term is used elsewhere without a definition, look it up here.

## Layout-quality metrics

`assess-layout` reports these metrics. Each one names a specific, perceptible defect class so an LLM agent can act on it directly. For the full rating model and thresholds, see [Layout Engine — Assessor Redesign](layout-engine.md).

| Metric | Result field | What it measures |
|---|---|---|
| **M1** | `nonOrthogonalTerminalCount` | Diagonal connection segments at an element terminal. The metric counts only the *visible* portion of the segment, so diagonals that Archi clips inside an element's bounds are not over-reported. |
| **M2** | `interiorTerminatingCount` | Connections whose endpoint lands *inside* an element's body instead of on its perimeter face. |
| **M3** | `zigzagCount` | Route shapes that backtrack or reverse along an axis. A connection already classified as a pass-through is not also counted as a zigzag. |
| **M4** | `connectionEdgeCoincidenceCount` | Connection segments running parallel to, and within a few pixels of, another element's edge — so the line reads as if it is stuck to the box. The informational `edgeCoincidenceGrazedElementCount` / `edgeCoincidenceGrazedElements` enumerate *every* distinct grazed `(connection, element)` pair; the rating-bearing `connectionEdgeCoincidenceCount` and its connection-id `edgeCoincidence` key are byte-identical, fired once per connection via a `legacyFlagged` guard. |
| **M5** | `hubPortQualityScore` (**HPQ**) | How evenly a hub element's connections are distributed across its perimeter faces, scored 0–1. A low score means many connections collapse onto one attachment point. |
| **M6** | `(layoutTier, routingTier)` | The two-dimensional overall rating — see [Rating tiers](#rating-tiers) below. |
| **R8** | `corridorUtilisationScore` | How well wide corridors carry connections in proportion to their width, scored 0–1. |
| **`parallelConnectionGap_V_p10`** | `vAxisParallelGapP10` | Informational only (no rating impact). The 10th-percentile gap between parallel connection segments on the vertical axis, in pixels. A small value signals the narrow-corridor regime, where more spacing cannot help and the remedy is structural. |
| **Hub-to-neighbour crowding** | `hubNeighbourClearanceMin` | The smallest clearance, in pixels, between a hub element's edge and the row of spoke neighbours packed against it (measured only on a hub face carrying ≥ 3 overlapping spoke neighbours; `-1.0` = no measurable hub). Rating-affecting: a value below the 60 px crowding floor caps the layout tier, so a hub enlarged until it crowds its neighbours cannot rate `good`. |
| **Connection-through-note/image** | `connectionThroughNoteCount` | Connections whose route runs through a note's box or an element's rendered image rectangle. Rating-affecting (routing tier, Tier-3R, binary presence): any nonzero count caps the routing tier at `good`. Complements `connectionPassThroughs` (which the routing tier takes the worse of, so no double penalty). |
| **Non-orthogonal interior segment** | `nonOrthogonalInteriorSegmentCount` | M1 extended from the terminal segments to the route interior: an off-cardinal (> ~5°) segment between the two terminal segments. Rating-affecting (routing tier, Tier-2R, ratio-bucketed like M1 and combined with it by the worse). |
| **Off-face parallel terminal** | `offFaceParallelTerminalCount` | A connection whose terminal route *departs* an element face then runs parallel to and hugging it — the first exterior segment travels along the departed face within 8 px of it. Informational only (no rating impact). Closes a blind spot in the M1 angular check, where a sub-pixel-off exit stub is below the visible-length threshold and so is not reported even though the parallel hugging trunk is plainly visible. It is the oracle the router's terminal egress-clearance work drives to zero. |
| **Coincident face port** | `coincidentFacePortCount` | An element face on which two or more connection terminals collide onto the *same* perimeter port (within ~1 px along the face axis), so two edges appear to leave one point. Informational only (no rating impact). Closes a blind spot in M5 (`hubPortQualityScore`), whose per-face guard only scores a face carrying four or more connections — so a face with two or three coincident terminals reads a vacuous `1.0` despite the collision. It is the oracle the router's coincident-port dissolution pass drives to zero. Distinct from `coincidentSegmentCount` (two *connections* overlapping in a corridor) and from HPQ (*even distribution* across faces). |
| **Redundant bendpoint** | `connectionRedundantBendpointCount` | A bendpoint lying on — and between — its neighbours along a *horizontal or vertical* run, so removing it would not change the rendered orthogonal route (an axis-aligned collinearity test, not any-angle). Informational (no rating impact). Near-collinear *diagonal* micro-jogs are excluded (removing them would diagonalise an orthogonal segment), and router-pinned terminal egress-stub ports (a first/last bendpoint on its element's perimeter face) are excluded because a full re-route keeps them — so a nonzero count is a genuinely collapsible *interior* point that a `terminals-only` re-route removes. Distinct from M3: a collinear out-and-back spike is a zigzag, not a redundant point. |
| **Container fill equals child** | `containerFillEqualsChildCount` | A container whose *authored* fill colour equals a nested child's, so parent and children merge into one flat block. Informational (no rating impact). Backstop for the auto-recede backdrop, which already handles unauthored fills. |
| **Connection grazes visual** | `connectionGrazesVisualCount` | A connection touching or clipping a note's or image's *border band* — the outer strip the through-visual interior test discards — including a visual too small to inset. Informational (no rating impact). Disjoint from `connectionThroughNoteCount`: a crossing is classified as exactly one of *through* or *graze*. |
| **Label on note** | `labelOnNoteCount` | A connection *label* (not its route) rendered on a note's rectangle — the caption collision the route detectors cannot see, since a label is positioned independently of the line. Informational (no rating impact). |
| **Label on group** | `labelOnGroupCount` | A connection label rendered on a visual group's *title band* (top strip only) — the title collision the label-overlap detector skips, as it treats groups as transparent containers. A label in the group body is not flagged. Informational (no rating impact). |

### Related metric terms

- **HPQ** — Hub-port quality. The conversational name for the M5 `hubPortQualityScore`.
- **Hub-to-neighbour crowding / clearance floor** — The `hubNeighbourClearanceMin` signal and its 60 px crowding floor. Distinct from HPQ: HPQ scores how evenly a hub's ports are *distributed*; clearance measures whether the enlarged hub box leaves *room* for the neighbours it spreads onto. The two are orthogonal — a hub can score a perfect HPQ while crowding its neighbours.
- **V_p10** — Shorthand for the `parallelConnectionGap_V_p10` informational signal (the `vAxisParallelGapP10` field).
- **coincSeg / coincident segment** — A segment counted by the legacy `coincidentSegmentCount` metric: two *connections* running parallel and overlapping in the same corridor. Distinct from M4, which measures a connection coinciding with an *element edge*.
- **pass-through** — A connection whose route crosses *through* an element's body. A *self* pass-through (through its own source or target) is reported but does not affect the rating; a *cross-element* pass-through does.
- **coverage map / coverage declaration** — The `coverage` map `assess-layout` returns, declaring per defect dimension whether the run actually evaluated it: `checked` (detector ran and fully covers the dimension — absence means clean), `partial` (a detector ran but covers only *some* failure modes — render-verify the uncovered ones before certifying clean), `not-checked` (no detector — absence is unknown, not clean), or `not-applicable` (the view structurally cannot exhibit it). Informational; never affects a rating. A done-gate reads both `coverage` and `ratingBreakdown` — a dimension is clean only when `checked` *and* its breakdown passes. Most dimensions report a fixed registry level, but two are special: `labelOverlaps` is **contextual** (see below) and `corridorCentering` is a standing `not-checked`.
- **contextual coverage (`labelOverlaps`)** — A coverage value that depends on the run's findings rather than a fixed registry flag. `labelOverlaps` is *declared* `checked`, but the map downgrades it to `partial` on any run where a label is wider than its hosting segment (`shortSegmentCount > 0`), because a label can crowd a neighbour box while still clearing it geometrically — the overlap count is honestly zero, yet the crowding mode is unverified. The first registry dimension with a result-dependent coverage value.
- **`corridorCentering` coverage** — A dimension shipped as a standing `not-checked`, making explicit that the R8 `corridorUtilisationScore` measures multi-occupant corridor *spread*, not whether a single route centres in its corridor band versus hugs an edge (a single-occupant corridor scores a vacuous 1.0; multi-occupant wall-hugging clamps to 1.0). `corridorUtilisation` itself stays `checked` — it fully covers its own scoped question.
- **de-noised headline / `overallExcludingAcceptedCosmetics`** — A key in `ratingBreakdown`: the overall rating recomputed with the `nonOrthogonalTerminals` (diagonal ELK terminal-segment) contribution removed. A *floor, never a lift* — it can only equal or improve `overallRating`. When it is better than `overallRating`, the headline `fair` is accepted terminal cosmetics only (clear with `auto-route-connections` mode `terminals-only`, or accept); when the two are equal, the rating reflects a real defect.

## Rating tiers

The overall rating uses a severity-tiered model so cosmetic issues cannot mask structural ones. Each metric belongs to a tier, and each tier caps how far it can pull the rating down.

| Tier | Severity | Effect on rating |
|---|---|---|
| **Tier 1** | Critical | Drives the overall rating directly (no cap). |
| **Tier 2** | Moderate | Capped at `fair`. |
| **Tier 3** | Cosmetic | Capped at `good`. |

Tier 1 is split by what the defect affects:

- **Tier 1L** — A critical *layout* defect (for example, sibling overlap, or a parent's label obscured by a child). Caps the **layout tier** at `poor`.
- **Tier 1R** — A critical *routing* defect (M2 interior terminations, M3 zigzags, M4 edge coincidence). Caps the **routing tier** at `poor`.

Rating levels run `excellent` → `good` → `fair` → `poor`. The **M6** overall rating reports the layout tier and routing tier separately and takes the worse of the two, so a routing fix on a well-laid-out view does not drag its layout tier down (and vice versa).

## Routing and layout terms

- **Hub element** — An element with many connections (5 or more is the detection threshold). Hubs need enough perimeter length to spread their connection ports; `detect-hub-elements` suggests a size.
- **Perimeter / perimeter-terminal** — The boundary face of an element where a connection attaches. A perimeter-terminal is a connection endpoint locked to a face slot on that boundary.
- **Corridor** — The axis-aligned channel of free space between elements (or between groups) through which connections are routed. Wider corridors carry more parallel routes without crowding.
- **Clearance** — The perpendicular distance between a routed segment and the nearest obstacle. The router weights paths to keep clearance high, so lines do not hug element edges.
- **Channel nudging** — A post-routing pass that shifts parallel segments sharing a channel so they spread out evenly instead of overlapping.
- **Bendpoint** — A corner point on an orthogonal route where the connection changes axis.
- **Zigzag** — A route that backtracks or reverses direction along an axis; counted by M3 (`zigzagCount`).
- **Visibility graph** — The graph of obstacle-free straight-line connections the router searches over.
- **A\* pathfinding** — The shortest-path search the router runs over the visibility graph, weighted by clearance and corridor preferences.
- **ELK (Eclipse Layout Kernel)** — The external layout library used for automatic hierarchical (`Layered`) placement of elements.
- **Precondition** — A property of the view's geometry (hub sizing, inter-element spacing, inter-group arrangement) that must hold *before* routing. The pipeline can refine routes but cannot create these conditions; see the `archimate://prompts/routing-preconditions-checklist` resource.
- **Density floor / reflow** — The point at which a view has too many elements for its area and no amount of spacing can satisfy a precondition. The spacing tools detect this and offer a structural *reflow* (repositioning elements) rather than churning.

## Tool behaviour terms

- **Control loop** — The internal `observe → decide → terminate` cycle the spacing convenience tools run: take a small spacing step, re-run `assess-layout`, then continue, escalate, or stop. One tool call is one undo-stack entry regardless of how many iterations run.
- **Density-aware termination** — The control loop's stop logic. It stops when quality is reached, when more spacing provably cannot help (density floor), or when the iteration budget is exhausted; a step that degrades the view is always reverted.
- **`terminationReason`** — The field a spacing tool returns explaining *why* its control loop stopped (for example, target reached, budget exhausted, or a structural reflow is required).
- **Infeasibility certificate** — A sound, pre-loop check that proves a view's spacing precondition cannot be satisfied on its current canvas. When it fires, the tool returns the view untouched and offers a structural reflow.
- **autoNudge** — An `auto-route-connections` mode that automatically moves a blocking element (and resizes its parent group to contain it), then re-routes, in one atomic operation.
- **`scope: all-views`** — An `assess-layout` mode (vs the default `single`) that assesses every diagram in the model and returns a compact per-view summary map (`name`, `overallRating`, `overallExcludingAcceptedCosmetics`, and the key counts) instead of one full payload per view — a one-call close-out triage, after which the agent drills into any `fair`/`poor` view with a single-scope call.

## Reference baseline

- **Manual-routed reference (oracle)** — A hand-routed ArchiMate view used to calibrate metric thresholds and to pin them with regression tests. Metric anchor values quoted in the documentation (for example, the `parallelConnectionGap_V_p10` anchor of 13.30 px) are measured against this reference.
