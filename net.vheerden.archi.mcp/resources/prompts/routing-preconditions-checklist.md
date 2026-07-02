# Routing Preconditions Checklist

Fetch this checklist before invoking `auto-route-connections` or `auto-layout-and-route` on any non-trivial view. The routing pipeline cannot recover from missing preconditions — it can only route the geometry the LLM agent has set up. This is the canonical LLM-facing playbook for routing/layout setup.

## Decision tree (start here)

```text
1. assess-layout the view.
   ├─ Layout tier "poor" (overlaps, boundary violations, parentLabelObscured)?
   │  → Resolve layout defects FIRST (preconditions 1-3 below). Routing cannot recover from layout-tier failure.
   │
   ├─ assess-layout `nextSteps[]` names a tool with violator IDs?
   │  → Act on `nextSteps` directly (skip to "Following `assess-layout` nextSteps").
   │
   ├─ structuredWarnings[] contains AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP?
   │  → Run `layout-within-group` on the parent of remediationViolatorIds BEFORE re-routing.
   │
   ├─ A spacing convenience tool returned terminationReason `density_floor_reflow_required`
   │  OR `density_precondition_infeasible_reflow_required`?
   │  → The view is provably too dense for spacing to fix (one is in-loop PASS-HONEST,
   │    the other is a pre-loop SOUND infeasibility certificate; act on either the same way).
   │    Do NOT loop the spacing tools. Surface the tool's reflow offer to the user and wait for
   │    consent (see "When a spacing tool says the view needs a structural reflow").
   │
   ├─ A spacing convenience tool returned terminationReason `reroute_degraded_input_baseline`?
   │  → The spacing tool's internal reroute would have degraded the input; the bare input was
   │    returned untouched (no mutation). Either keep the safe untouched input, OR call
   │    `auto-route-connections` first then re-call spacing (see "When a spacing tool says it
   │    would have degraded the input"). NOT evidence that the prescribed order is wrong.
   │
   ├─ `parallelConnectionGap.vAxisParallelGapP10` < 6 (narrow-corridor regime)?
   │  → Convenience spacing tools cannot mitigate. Reduce hub fan-out, split the view, or apply manual bendpoint surgery via update-view-connection.
   │
   └─ All preconditions met → auto-route-connections (or auto-layout-and-route).

2. After routing → assess-layout again. Verify M6 `(layoutTier, routingTier)` improved or held. If layoutTier regressed, return to step 1.
   │
   └─ `overallRating` is `fair` but `ratingBreakdown.overallExcludingAcceptedCosmetics` is `good`/`excellent`?
      → The `fair` is accepted ELK terminal-diagonal cosmetics only. Run auto-route-connections mode `terminals-only` to clear it, or accept it — do NOT keep inflating spacing. When the two readings are EQUAL, the rating is a real defect; keep fixing.
```

> **Final close-out:** to triage every diagram in one call, run `assess-layout` with `scope: all-views` — it returns a compact per-view map (`overallRating`, the de-noised `overallExcludingAcceptedCosmetics`, and the key counts). Drill into any `fair`/`poor` view with a single-scope call. If you also exported a view for a visual check, compare the export's `modelVersion` against the latest `assess-layout` `_meta.modelVersion` (numerically) — a lower export value means the image predates a later change and should be re-exported.

## What is a precondition?

A precondition is a property of the view's geometry that must hold before routing starts. The pipeline can refine routes, but it cannot:

- Resize a hub element to relieve port congestion
- Inflate intra-group element spacing to widen routing corridors
- Push groups apart to create inter-group corridors
- Reorder elements between groups to reduce crossings

If a precondition is missing when routing starts, the routing output will reflect that — not because the pipeline is broken, but because the input geometry constrains what is achievable.

A high-density view can reach a point where **no amount of spacing inflation can satisfy the precondition** — the elements are simply too many for the view's area. This is a *layout-precondition failure*, not a routing-algorithm limit. The spacing convenience tools detect this case soundly and report it rather than churning (see "When a spacing tool says the view needs a structural reflow").

## The three preconditions

Before calling `auto-route-connections` or `auto-layout-and-route` on a non-trivial view, verify each precondition:

### 1. Hub elements sized for connection fan-out

Hub elements (≥ 5 connections, the canonical `HUB_DETECTION_THRESHOLD`) must have enough perimeter face length to distribute their connection ports without collapsing onto a single attachment point.

**Verify and fix:**

- [ ] Run `detect-hub-elements` to identify candidates.
- [ ] For each suggested hub (> 6 connections), call `update-view-object` to set the dimension perpendicular to the connection flow direction. Formula: `dimension = baseDimension + 15px × (connectionCount − 6)`.
- [ ] After resizing, re-run `layout-within-group` on the affected group(s) to prevent hub-on-sibling overlap.

**Do NOT use `resize-elements-to-fit` for this.** That tool sizes for label legibility only and ignores connection count — it is a name-trap for the hub-fan-out problem.

### 2. Inter-element spacing matches connection density

Within each group, the gap between sibling elements must be wide enough to accommodate the routes that pass between them. When density is high, the routing pipeline runs out of channel room and produces edge-coincident segments and zigzag patterns.

**Verify and fix — pick one of three paths:**

- [ ] **Composed-tool path (recommended when BOTH inter-element and inter-group spacing need adjustment):** call `apply-spacing-recommendations` with `scope: "both"` (default). It runs two coordinated control loops (element arm, then inter-group arm) that inflate spacing in small steps and stop honestly — see "The control loop inside the convenience tools". Returns before/after `assess-layout` snapshots plus per-arm `elementTerminationReason` / `groupTerminationReason`, `elementIterationCount` / `groupIterationCount`, and `elementAppliedDeltas[]` / `groupAppliedDeltas[]`.
- [ ] **Single-arm one-call path:** call `apply-element-spacing-recommendations` with `dryRun: true` to preview, then `dryRun: false` to apply. Use this when only inter-element spacing needs adjustment (no inter-group corridor changes). It runs the same embedded control loop as the composed tool, single-arm.
- [ ] **Manual path:** run `assess-layout` and check `coincidentSegmentCount` and `connectionEdgeCoincidenceCount`. If either is non-trivial, call `adjust-view-spacing` with an explicit `interElementDelta` chosen from the heuristics table below. `adjust-view-spacing` is the underlying single-shot primitive — it does **not** run the control loop.
- [ ] **Manual path, default-driven:** alternatively, omit `interElementDelta` entirely. When both spacing-related metrics are problematic, `adjust-view-spacing` derives the same heuristic-driven default automatically. The response DTO's `defaultResolutionReason` field reports which trigger and tier the tool used.

**Heuristics table (per-group element spacing target):**

| Total connections on view | Target element spacing (no-hub) | Target element spacing (hub-aware) |
|---|---|---|
| ≤ 15 | 60 px | 80 px |
| 16–30 | 80 px | 100 px |
| > 30 | 100 px | 120 px |

The hub-aware column applies when `detect-hub-elements` returns one or more hub candidates (≥ 5 connections). The composed and convenience tools select the right column automatically; if you are computing manually with `adjust-view-spacing`, pick from the column that matches the view's hub population.

The `adjust-view-spacing` operation (inflate + re-route) is a single undo step. Each convenience-tool call — regardless of how many internal control-loop iterations it runs — is also a single undo step.

### 3. Group arrangement set with topology and corridor-friendly spacing

On grouped views, the relative positions of groups determine which inter-group connections share corridors. Without topology-driven arrangement, heavily-connected group pairs end up far apart and produce long crossing chains.

**Verify and fix (grouped views with inter-group connections only):**

- [ ] Call `arrange-groups` with `arrangement: "topology"` to order groups by inter-group connection density. Pass an explicit `spacing` value or omit it — when omitted, the tool derives a heuristic-driven default from connection count (≤ 15 → 80 px, 16–30 → 100 px, > 30 → 120 px). Pass an explicit `spacing` (including 0 or 40) to suppress default-resolution.
- [ ] If element ordering inside groups affects crossing density, run `optimize-group-order` BEFORE the final `arrange-groups` call (reordering can change group sizes; always re-arrange after).
- [ ] After topology arrangement, if `assess-layout` still reports `connectionEdgeCoincidenceCount > 4` on inter-group connections, widen the corridors by calling `apply-group-spacing-recommendations` (one-call path) or `adjust-view-spacing` with `interGroupDelta` (manual path).

**Heuristics table (inter-group spacing target):**

| Total connections on view | Connected pair gap (no-hub) | Connected pair gap (hub-aware) | Unconnected pair gap |
|---|---|---|---|
| ≤ 15 | 80 px | 100 px | 40 px |
| 16–30 | 100 px | 140 px | 40 px |
| > 30 | 120 px | 160 px | 60 px |

The hub-aware column applies when `detect-hub-elements` returns one or more hub candidates on the view. `apply-spacing-recommendations` and `apply-group-spacing-recommendations` select the column automatically.

## The control loop inside the convenience tools

The three convenience tools — `apply-element-spacing-recommendations`, `apply-group-spacing-recommendations`, and `apply-spacing-recommendations` — do **not** apply a single spacing delta and return. Each runs an embedded **observe → decide → density-aware-terminate** control loop. You call the tool once; it iterates internally and reports what it did.

**Per iteration**, the loop takes a small spacing step (a `+10 px`-per-step monotone ladder while the view is improving; a larger step when escalating), re-runs `assess-layout`, and classifies the result on a 2×2 of *aggregate-trend* × *spacing-regime-position*:

| Aggregate quality trend | View below the prescribed ~100–124 px / fan-out-sized-hub regime | View already at/above the prescribed regime |
|---|---|---|
| Still climbing | **CONTINUE** — take another monotone step | **CONTINUE** — take another monotone step |
| Stalled | **ESCALATE** — inflate toward the ~112 px mid-band in a few large steps plus a one-shot hub-resize | **PASS-HONEST** — more spacing cannot help; stop and report (see next section) |

A degrading step is always reverted, so the loop never presents a silently-degraded view. All accepted iterations from a single call wrap in one compound command, so one tool call is always one undo-stack entry. The loop's objective is the aggregate `thresholds_met` scalar only.

**`iterationBudget`** defaults to 5 for the single-arm tools and 8 for the composed tool (split across the two arms), caller-tunable in the range `[1, 20]`. Set `dryRun: true` to preview the recommendation without mutating.

The response DTO reports `terminationReason` (one of **ten** branches — seven in-loop branches plus **three** pre-loop guards: `dry_run_recommendation_not_applied` + `reroute_degraded_input_baseline` + `density_precondition_infeasible_reflow_required`), `iterationCount`, and `appliedDeltas[]` (the per-iteration steps in pixels). The composed tool reports these per arm (`elementTerminationReason` / `groupTerminationReason`, etc.). The ten termination branches:

| `terminationReason` | Meaning |
|---|---|
| `goal_reached_at_iteration_N` | Target quality envelope met. |
| `budget_exhausted_after_N_iterations` | `iterationBudget` cap hit; the last accepted step commits. |
| `aggregate_threshold_regressed_at_iteration_N_reverted_to_iteration_M` | Back-off fired; reverted to the best non-degraded state. |
| `structural_no_change_<reason>` | Nothing to inflate (no groups / no groups with 2+ children / no connections). |
| `heuristic_already_met_no_change` | Current spacing already ≥ target at iteration 0. |
| `dry_run_recommendation_not_applied` | `dryRun: true` short-circuit; no mutation; `iterationCount = 0`. |
| `iteration_apply_failed_at_iteration_N_reverted_after_M_accepted_iterations` | A contained mutation threw mid-application; best-effort rollback applied, prior accepted iterations preserved. |
| `density_floor_reflow_required` | **PASS-HONEST (in-loop).** The loop reached an in-regime density floor — more spacing inside the loop's bounded ladder cannot help. Act on the reflow offer (see "When a spacing tool says the view needs a structural reflow"). |
| `reroute_degraded_input_baseline` | **Pre-loop accessor-layer safety net** (sibling to `dry_run_recommendation_not_applied`). The tool's internal pre-loop reroute pass scored a strictly lower aggregate `thresholdsMet()` than the bare input baseline, indicating the reroute would have degraded the input. The bare input is returned untouched (`iterationCount = 0`, `appliedDeltas = []`, no mutation, no view damage). **NOT a request to re-order calls** — see "When a spacing tool says it would have degraded the input" below. |
| `density_precondition_infeasible_reflow_required` | **Pre-loop SOUND infeasibility certificate** (sibling to `reroute_degraded_input_baseline`). Honestly distinct from the in-loop `density_floor_reflow_required`: this is *"the input precondition is infeasible on its current canvas — the loop was never entered"*, NOT *"the loop reached an in-regime density floor"*. A SOUND one-sided test (`idealUniformAvg = sqrt(unionArea/N) − avgBox < 100`) — zero false-positives by construction. The view is returned untouched (`iterationCount = 0`, `appliedDeltas = []`, no mutation, no view damage); the DTO carries a `densityFloorDiagnosis` string + a consent-gated structural-reflow OFFER (see "When a spacing tool says the view needs a structural reflow"). |

## When a spacing tool says the view needs a structural reflow

Two distinct `terminationReason` values surface this signal — both are **sound** and you act on them the same way:

- **`density_floor_reflow_required`** (in-loop, PASS-HONEST) — the loop ran, took some accepted steps, and reached an in-regime density floor: the average element spacing is already in the prescribed 100–124 px band (and the hub is sized for its connection count) but the aggregate quality has stalled. *More spacing inside the loop's bounded ladder cannot help.*
- **`density_precondition_infeasible_reflow_required`** (pre-loop, SOUND certificate) — the loop was NEVER entered because a SOUND one-sided closed-form test (`idealUniformAvg = sqrt(unionArea/N) − avgBox < 100`) proved the input precondition is infeasible on the current canvas; zero false-positives by construction. The pre-loop certificate is honestly DISTINCT from the in-loop reason: it says *"this input geometry can never reach the prescribed regime by spacing/hub adjustment alone — the loop was not entered"*, not *"the loop reached an in-regime density floor"*. Both arrive at the same user-facing remedy.

In either case the view is too dense for spacing to fix on its current canvas — the elements are too many for the view's area. This is a layout-precondition failure, not a routing-algorithm limit.

What the tool does in this case:

- It **stops** without degrading the view (the best non-degraded state is preserved).
- It returns a `densityFloorDiagnosis` string naming the violated precondition: the measured average spacing vs the 100–124 px band, and the hub width × height vs its connection count. (The composed tool returns this per arm as `elementDensityFloorDiagnosis` / `groupDensityFloorDiagnosis`.)
- It **never auto-reflows.** A structural reflow moves user-placed elements, so the tool surfaces the reflow as an explicit, user-consentable next step — *surface + offer + wait for consent, never surface + act*.

What you (the agent) should do:

1. **Do not re-invoke the spacing tools in a loop.** Both signals (`density_floor_reflow_required` and `density_precondition_infeasible_reflow_required`) are sound — repeating the call will reproduce the same result.
2. **Surface the `densityFloorDiagnosis` and the reflow offer to the user.** Present it as a choice, e.g.: *"This view is too dense for spacing adjustments to fix — the elements need more area than the current layout provides. I can perform a structural reflow (this will reposition elements), or you can split the view / reduce connections. Which would you prefer?"*
3. **Wait for explicit consent before any structural reflow** (e.g. `auto-layout-and-route` from scratch, or splitting the view). Reflow discards manual placement intent — it is the user's decision, not the agent's.

This is the difference between the two sound `*_reflow_required` signals and the informational `parallelConnectionGap.vAxisParallelGapP10` signal: the former two are deterministic stops computed from the view's own geometry (one in-loop, one pre-loop); the latter is a heuristic narrow-corridor indicator. All three point at the same remedy class (structural change, not more spacing), but the two `*_reflow_required` reasons are the authoritative ones to act on.

**You do not need to manually count spacing-tool invocations.** The old guidance ("stop after 3 spacing calls") is superseded — the control loop self-terminates honestly via `density_floor_reflow_required` (in-loop) / `density_precondition_infeasible_reflow_required` (pre-loop) / `aggregate_threshold_regressed`. Manually re-driving single-shot `adjust-view-spacing` in a loop bypasses that protection; prefer the convenience tools.

## When a spacing tool says it would have degraded the input

`terminationReason: reroute_degraded_input_baseline` is a **pre-loop accessor-layer safety net**, sibling to `dry_run_recommendation_not_applied` — both fire **before** the control loop is entered. It is a deliberate "guard, don't veto" mechanism: before the loop runs, the accessor temp-routes the bare input on the SAME routing basis the loop uses to measure each per-step `postState`; if that route-normalized assessment scores a strictly lower aggregate `thresholdsMet()` than the bare input, the spacing tool's internal reroute would have made things worse. The tool **returns the bare input untouched** (`iterationCount = 0`, `appliedDeltas = []`, no mutation, no view damage) and surfaces this reason so the agent knows what happened.

**What this signal is NOT:**

- It is **not** evidence that the prescribed order `arrange-groups → hub-sizing → inter-element-spacing → inter-group-spacing → routing` is wrong. The prescribed order is correct in general; this guard is the explicit exception.
- It is **not** caused by "the baseline was measured on un-routed straight-line geometry so crossings looked artificially good." The actual mechanism is a route-normalized-vs-bare `LayoutMetrics.thresholdsMet()` comparison performed by the accessor; both sides of the comparison are measured the same way, just on different routing states of the same view.

**What you (the agent) should do:**

1. **Your view is safe.** The bare input was returned untouched — no mutation occurred, no view state was damaged. You can keep working from the same starting point.
2. **If you want spacing to engage, give it a non-degrading baseline first.** Call `auto-route-connections` (or `auto-layout-and-route` if the layout-tier preconditions are also unmet) before re-calling the spacing convenience tool. The reroute pass that the spacing tool runs internally will then start from a routed baseline rather than degrading a pre-existing routed state.
3. **Do not generalise.** This is rare (the in-the-wild observation that drove the documentation update was the FIRST observed firing across many comprehensive test runs). Treat it as the explicit exception; the prescribed precondition order remains the default playbook.

This is the difference between the sound `density_floor_reflow_required` certificate (the view is provably too dense — structural change is the only remedy) and this `reroute_degraded_input_baseline` signal (a specific spacing call would have made things worse on this input — try again with a different starting state, or accept that this view's current routed state is already at a local optimum the spacing pass cannot improve).

## Disposition matrix — apply preconditions in this order

| View shape | Order |
|---|---|
| Flat view (no groups) | hub sizing → routing |
| Grouped view, no inter-group connections | hub sizing → inter-element spacing → routing |
| Grouped view, with inter-group connections | `arrange-groups` topology → hub sizing → inter-element spacing → inter-group spacing → routing |
| Hub-and-spoke topology with dense inter-group flow | `arrange-groups` topology (with `spacing >= 100`) → hub sizing → inter-element spacing → inter-group spacing → routing |

## Following `assess-layout` nextSteps

`assess-layout` emits a `nextSteps[]` envelope that names the right precondition tool with violator IDs already attached when these conditions trigger. Act on `nextSteps` directly — the heuristics and violator IDs are pre-computed.

| Violation | Tool named in `nextSteps` |
|---|---|
| `overlapCount > 0` (sibling overlaps) | `layout-within-group` on the offending parent (with sibling-pair violator IDs) |
| `boundaryViolationCount > 0` (children outside parent group bounds) | Composite remedy `nextSteps` entry — call the named tool sequence directly (typically `update-view-object` to reposition the child, or `resize-elements-to-fit` on the parent) |
| `parentLabelObscuredCount > 0` | `update-view-object` to reposition the offending child (with violator IDs) |
| `hubPortQualityScore < 0.5` | `detect-hub-elements` (with violator hub IDs) — then `update-view-object` to resize per the suggestion |
| `coincidentSegmentCount > 2` OR `connectionEdgeCoincidenceCount > 4` on a grouped view | `apply-spacing-recommendations(scope=both)` (composed, control-loop) — or the single-arm convenience siblings if only one axis needs change |
| Same coincidence pressure with `corridorUtilisationScore >= 0.9` AND `hubPortQualityScore >= 0.5` (saturated container-nested-hub) | `detect-hub-elements` first, then the **resize-vs-reposition** branch below — NOT generic spacing inflation. `assess-layout` emits this as a single diagnostic step that supersedes the spacing row above. |
| `crossingsPerConnection > 4.0` on a grouped view | `arrange-groups` (topology) and/or `optimize-group-order` |

### Saturated container-nested-hub — resize vs reposition

When a view nests components inside a container element with a central hub and corridors are saturated (`corridorUtilisationScore >= 0.9`) while `hubPortQualityScore` is fine (`>= 0.5`, so the hub-sizing row does not apply), generic spacing inflation does not fit. Run `detect-hub-elements` to confirm a hub, then choose by **density** — the two levers diverge, and a metric can disagree with the render:

- **Sparse (spare room around the hub):** enlarge the hub in **both** dimensions with `update-view-object`, then `auto-route-connections`. Re-routing alone is **inert** here (it cannot open corridor headroom). A high `hubPortQualityScore` does **not** mean enlarging won't help — port distribution is orthogonal to corridor headroom.
- **Dense (enlarging would crowd neighbours):** do **not** resize. Revert the hub to its normal size, run `auto-layout-and-route` (ELK) to re-place elements, then a **full** `auto-route-connections`.
- **ELK traps:** (1) an **oversized** hub left in before ELK produces **interior terminations** (connections land *inside* the big box) — revert the hub to normal size first. (2) After ELK, `terminals-only` is the **wrong** follow-up — it vetoes terminations that would land inside the re-placed elements; use a **full** `auto-route-connections`.
- **Acceptance is render-authoritative:** the rating number alone can score a crowded resize `good` (no metric penalises hub-to-neighbour crowding yet). Confirm with `export-view` and look — do not accept on the rating.

### Informational label/visual detections (no rating impact)

These do **not** drive the rating and are not in `nextSteps`, but they tell you a diagram will not read cleanly to a human — act on them before presenting a view:

- **`labelOverlapCount`** flags a connection label on an element box, including a Middle label on its **own** endpoint. On Archi 5.10 the routing tools clear the own-endpoint case automatically via the connection Label Offset (just run `auto-route-connections`); otherwise move the label with `labelPosition` or hide it with `showLabel: false`.
- **`noteClipCount`** flags a note whose text is taller than its box. Omit the note `height` so the server auto-fits it, raise the height, or reduce the font size.
- **`imageSiblingOverlapCount`** flags a custom image or specialization icon overlapping a sibling. Increase spacing, reposition the image, or shrink the icon.

## Auto-route structured warnings

`auto-route-connections` returns `structuredWarnings[]` alongside the free-text `warnings[]`. Each entry carries `{code, message, remediationTool, remediationViolatorIds}` for deterministic iteration. The most common one:

- **`AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP`** — emitted when `autoNudge: true` cannot proceed because two sibling elements overlap. The skip is a hard gate; re-running `auto-route-connections` without first separating the siblings will reproduce the same skip. Action: call the named `remediationTool` (`layout-within-group`) on the parent of `remediationViolatorIds` BEFORE re-routing.

## The narrow-corridor floor

`parallelConnectionGap.vAxisParallelGapP10` measures the 10th-percentile parallel-segment gap on the V axis. An ArchiMate manual-routed reference view anchors at 13.30 px. When this signal drops below ~6 px, the view is in the narrow-corridor regime: convenience spacing tools cannot mitigate further because the structural floor is determined by topology, not by router input. This is the informational counterpart to the sound `density_floor_reflow_required` certificate — both indicate that the remedy is structural change, not more spacing. Mitigation paths:

- Reduce hub fan-out (split a hub into two specialised hubs).
- Split the view across two narrower scopes.
- Apply manual bendpoint surgery via `update-view-connection` to relocate specific high-cost segments.

## After routing — verify, don't iterate blindly

Always run `assess-layout` after routing. The `M6` two-dimensional rating reports `(layoutTier, routingTier)` so you can see whether a routing change improved the routing tier without dragging the layout tier down. If the layout tier regressed, the precondition setup was insufficient — go back to step 1, do not re-route in a loop.

## Related references

- `archimate://reference/archimate-view-patterns` — full Pre-Layout Planning Checklist, Group Composition Patterns, ArchiMate Modelling & Aesthetic Best Practices, and viewpoint-specific workflow recipes.
- `detect-hub-elements` tool description — hub thresholds (≥ 5 = candidate, > 6 = sizing suggestion emitted; `M5_FACE_GUARD_MIN_CONNECTIONS = 4` is a separate internal per-face guard for the M5 hub-port-quality metric, not a hub-detection threshold). For high-fan-out hubs (> 12 connections) the tool also emits a 2D-resize suggestion that distributes ports across all four edges.
- `adjust-view-spacing` tool description — the single-shot primitive: three additive deltas (`interElementDelta` / `paddingDelta` / `interGroupDelta`) and density-aware default behaviour. Does not run the control loop.
- `apply-spacing-recommendations` tool description — composed convenience tool: two coordinated control loops, `scope` parameter (`both` / `element` / `group`), per-arm `terminationReason` / `iterationCount` / `appliedDeltas[]`, and the legacy `elementKneeClampApplied` / `groupKneeClampApplied` flags (still emitted when an arm's per-iteration step cap fires).
- `apply-element-spacing-recommendations` tool description — single-arm convenience surface for precondition 2 (embedded control loop).
- `apply-group-spacing-recommendations` tool description — single-arm convenience surface for precondition 3 corridor-widening half (embedded control loop).
- `arrange-groups` tool description — `grid` / `row` / `column` / `topology` arrangements and density-aware spacing default.
