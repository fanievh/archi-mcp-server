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
   │    returned untouched (no mutation). Route the view first: call `auto-route-connections`,
   │    then re-call spacing (see "When a spacing tool says it would have degraded the input").
   │
   ├─ `parallelConnectionGap.vAxisParallelGapP10` < 6 (narrow-corridor regime)?
   │  → Convenience spacing tools cannot mitigate. Reduce hub fan-out, split the view, or apply manual bendpoint surgery via update-view-connection.
   │
   └─ All preconditions met → auto-route-connections (or auto-layout-and-route).

2. After routing → assess-layout again. Verify M6 `(layoutTier, routingTier)` improved or held. If layoutTier regressed, return to step 1.
   │
   └─ `overallRating` is `fair` but `ratingBreakdown.overallExcludingAcceptedCosmetics` is `good`/`excellent`?
      → The `fair` is accepted ELK terminal-diagonal cosmetics only. Run auto-route-connections mode `terminals-only` to clear it, or accept it — do NOT keep inflating spacing. When the two readings are EQUAL, the rating is a real defect; keep fixing.
      → NOTE `offFaceParallelTerminalCount` is NOT part of the accepted-cosmetics carve-out: any nonzero count caps the routing tier at `fair` in BOTH readings, because a face-hug is a real defect. If `EGRESS_LIFT_LAYOUT_BOUND` also fired, the remedy is spreading the elements, not another re-route.
```

> **Final close-out:** to triage every diagram in one call, run `assess-layout` with `scope: all-views` — it returns a compact per-view map (`overallRating`, the de-noised `overallExcludingAcceptedCosmetics`, and the key counts). Drill into any `fair`/`poor` view with a single-scope call. If you also exported a view for a visual check, compare the export's `modelVersion` against the latest `assess-layout` `_meta.modelVersion` (numerically) — a lower export value means the image predates a later change and should be re-exported.

## What is a precondition?

A precondition is a property of the view's geometry that must hold before routing starts. The pipeline can refine routes, but it cannot:

- Resize a hub element to relieve port congestion
- Inflate intra-group element spacing to widen routing corridors
- Push groups apart to create inter-group corridors
- Reorder elements between groups to reduce crossings

If a precondition is missing when routing starts, the routing output will reflect that — not because the pipeline is broken, but because the input geometry constrains what is achievable.

> **"Group" here means either kind of container.** Archi renders two different objects as containers: a **native view group** (`add-group-to-view`) and an **ArchiMate `Grouping` element** (`create-element` + `add-to-view`). **The arrangement family treats them alike.** So a view built from `Grouping` zones — the shape the technology-and-deployment and application-integration recipes prescribe — is a fully supported grouped view: `arrange-groups`, `optimize-group-order`, `adjust-view-spacing` and `auto-layout-and-route mode:"grouped"` all act on it, the router treats a `Grouping` as a soft wall to keep clear of rather than a solid obstacle to detour around, and `assess-layout` reports `hasGroups: true` for it. One thing to watch when reading ids back: `get-view-contents` returns a `Grouping` among the **elements**, not in the `groups` bucket, because it is a real model concept — but the `topLevelGroups` / `totalGroups` stats count both kinds, and every container node in `tree` and `graph` carries `isGroup: true`. That parity is about container **kind**. On **depth** the four part company. Only `arrange-groups` positions a zone drawn inside a host — inside that host, in its coordinate space, reported in `nestedContainersArranged`. The other three still gate and position on the view's own containers; what they will no longer do is call such a view groupless, since each now refuses it by naming the containers it holds and pointing at `arrange-groups`. The `apply-spacing-recommendations` / `apply-group-spacing-recommendations` / `apply-element-spacing-recommendations` convenience tools do count such a zone in the figures they publish, and they now **decline to recommend a delta across it**: a corridor between containers the tool's own step does not position is reported as `structural_no_change_containers_not_positioned_by_this_tool` with a zero delta, rather than as a recommendation the applied path could never honour.
>
> **One tool this checklist names does NOT treat the two kinds alike. Do not generalise from the paragraph above to it.**
>
> - **`layout-within-group`** accepts *either* kind as the container you name — and is in fact wider than the arrangement family, accepting any element that holds children (a `Node`, an `ApplicationComponent`). But its **upward** re-fit (`recursive: true`) walks **native view groups only, at both ends**: name a `Grouping` and the pass never starts; run it inside one and the walk stops there, leaving that `Grouping` too small for the child it just grew. You are told which happened on every call — read `ancestorPropagation`, specifically the codes `container-not-a-native-group` and `stopped-at-non-native-ancestor`, and re-run on the named parent. The overflow also shows up as `boundaryViolationCount` in `assess-layout`.
>
> **`resize-elements-to-fit` was the second and is no longer.** It sizes **elements** to their labels, and a `Grouping` is a zone rather than a label-bearing element: the tool **grows** one to contain its children — which is exactly what makes it the `boundaryViolationCount` remedy below — and never shrinks one or sizes one to its own name. A childless zone comes back untouched; name it in `elementIds` and the response says so in `skippedContainers`, with the reason. A native view group is never **collected as a target** — the walk descends through it to reach the elements inside — but it is still **grown** by the parent-fit cascade when a child this pass widened overflows it, and that growth is reported in `resizedGroups`. So the tool is safe to aim at a whole view built from zones.
>
> The full per-tool table lives in the layout-engine documentation under "What counts as a container".

A high-density view can reach a point where **no amount of spacing inflation can satisfy the precondition** — the elements are simply too many for the view's area. This is a *layout-precondition failure*, not a routing-algorithm limit. The spacing convenience tools detect this case soundly and report it rather than churning (see "When a spacing tool says the view needs a structural reflow").

## The three preconditions

Before calling `auto-route-connections` or `auto-layout-and-route` on a non-trivial view, verify each precondition:

### 1. Hub elements sized for connection fan-out

Hub elements (≥ 5 connections, the canonical `HUB_DETECTION_THRESHOLD`) must have enough perimeter face length to distribute their connection ports without collapsing onto a single attachment point.

**Verify and fix:**

- [ ] Call `assess-layout`. Its `unsizedHubs` block names every element already measured as too small for its fan-out, with the box it has and the box it needs. This works on a view that has never been routed, which is the only moment sizing a hub is cheap.
- [ ] Run `detect-hub-elements` to see the full per-element connection census and its sizing suggestions.
- [ ] For each suggested hub (> 6 connections), call `update-view-object` to set the dimension perpendicular to the connection flow direction. Formula: `dimension = baseDimension + 15px × (connectionCount − 6)`.
- [ ] After resizing, re-run `layout-within-group` on the affected group(s) to prevent hub-on-sibling overlap.

**Two different numbers, and they answer different questions.** `unsizedHubs` reports an **absolute floor** — `300 + 10 × max(0, count − 7)` wide and `250 + 8 × max(0, count − 7)` tall — which an element can meet and go on meeting. `detect-hub-elements`' suggestion is a **growth step** off the element's current size, so an element resized to it produces a larger suggestion next call. Use the floor to decide whether a hub needs attention at all; use the suggestion when you want to grow one further. The connection count in both is the same number, produced by the same walk.

**Do NOT use `resize-elements-to-fit` for this.** That tool sizes for label legibility only and ignores connection count — it is a name-trap for the hub-fan-out problem.

**Do not wait for `hubPortQualityScore` to tell you.** On a view with no stored routing that metric is vacuously `1.0` — no element face carries the four terminals its guard needs — so a grossly undersized hub rates `pass` on it right up until the routes exist. By then the resize costs a re-route and an undo. When the score *is* meaningful, any value below `0.75` (the `fair` and `poor` bands, both of which cap the view's routing tier) now carries an explicit hub remedy, so a capped view is no longer capped in silence.

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

The hub-aware column applies when the view carries at least one element with **more than 6 connections**. The composed and convenience tools measure that themselves and select the right column automatically; if you are computing manually with `adjust-view-spacing`, pick from the column that matches the view's hub population. Note that `detect-hub-elements` lists every element carrying at least one connection — its result being non-empty does **not** mean the view has a large hub, so read the per-element `connectionCount` rather than the length of the list.

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

The hub-aware column applies when the view carries at least one element with **more than 6 connections** — the same signal as the element table above, read from the same predicate. `apply-spacing-recommendations` and `apply-group-spacing-recommendations` select the column automatically.

## The control loop inside the convenience tools

The three convenience tools — `apply-element-spacing-recommendations`, `apply-group-spacing-recommendations`, and `apply-spacing-recommendations` — do **not** apply a single spacing delta and return. Each runs an embedded **observe → decide → density-aware-terminate** control loop. You call the tool once; it iterates internally and reports what it did.

**Per iteration**, the loop takes a small spacing step (a `+10 px`-per-step monotone ladder while the view is improving; a larger step when escalating), re-runs `assess-layout`, and classifies the result on a 2×2 of *aggregate-trend* × *spacing-regime-position*:

| Aggregate quality trend | View below the prescribed ~100–124 px / fan-out-sized-hub regime | View already at/above the prescribed regime |
|---|---|---|
| Still climbing | **CONTINUE** — take another monotone step | **CONTINUE** — take another monotone step |
| Stalled | **ESCALATE** — inflate toward the ~112 px mid-band in a few large steps plus a one-shot hub-resize | **PASS-HONEST** — more spacing cannot help; stop and report (see next section) |

A degrading step is reverted, but only as the loop's own step scalar measures it — and that scalar is narrower than the rating the tools report. It has six inputs over `[0, 12]`: three correctness bits (boundary violations, pass-throughs, overlaps) plus three graded band credits (edge-coincidence, coincident segments, hub-port quality). Element overlaps enter it as **one binary bit**, however many there are, and cousin overlaps, off-canvas placement, content bounds and `overallRating` itself are **not inputs at all**. Inflating spacing reliably improves edge-coincidence and coincident segments — that is what it is for — so a step can lose the single overlap bit, gain two or three band credits, score net-positive and be accepted while overlaps appear and elements leave the canvas. **These tools can therefore return a view rated worse than the one they were handed.** They now measure exactly that: the `before` and `after` `assess-layout` snapshots are compared on the overall rating (band first, then the tier-weighted score, so a same-band regression is caught too), and a regression is reported as a `SPACING_RATING_REGRESSED` structured warning naming both ratings and every metric that moved, plus a `nextSteps` line. The spacing stays applied — the caller decides. On a **queued (batch)** call the comparison is structurally unavailable, because the accepted commands are queued rather than executed and the loop has already reset the model, so `after` re-reads the unmutated view; `nextSteps` says so rather than staying silent. All accepted iterations from a single call wrap in one compound command, so one tool call is always one undo-stack entry — which is why one `undo` reverses a whole run.

**`iterationBudget`** defaults to 5 for the single-arm tools and 8 for the composed tool (split across the two arms), caller-tunable in the range `[1, 20]`. Set `dryRun: true` to preview the recommendation without mutating.

The response DTO reports `terminationReason` (one of **ten** branches — seven in-loop branches plus **three** pre-loop guards: `dry_run_recommendation_not_applied` + `reroute_degraded_input_baseline` + `density_precondition_infeasible_reflow_required`), `iterationCount`, and `appliedDeltas[]` (the per-iteration steps in pixels). The composed tool reports these per arm (`elementTerminationReason` / `groupTerminationReason`, etc.). The ten termination branches:

> **This is the only surface that states a number, deliberately.** A live test loads this file and fails the build if the prose count and the reflectively-enumerated `REASON_*` constants disagree, so the number here cannot go stale unnoticed. Every other surface — the tool descriptions aside — names the branches without counting them, because a hand-maintained tally with no pin behind it has now been wrong on three of them.

| `terminationReason` | Meaning |
|---|---|
| `goal_reached_at_iteration_N` | Target quality envelope met. **Not reachable through these three tools.** The loop's goal predicate defaults to *false* and the spacing tools' callback factory never overrides it, so no spacing call can terminate here. It is listed because the constant exists and the loop would emit it if a caller supplied a goal predicate; treat it as unreachable when reading a spacing response. |
| `budget_exhausted_after_N_iterations` | `iterationBudget` cap hit; the last accepted step commits. |
| `aggregate_threshold_regressed_at_iteration_N_reverted_to_iteration_M` | Back-off fired; reverted to the best non-degraded state. |
| `structural_no_change_<reason>` | Nothing to inflate (no groups / no groups with 2+ children / no connections / every container the corridor runs between is drawn inside a host, so the step this tool applies positions none of them — `arrange-groups` positions those, and `layout-within-group` spaces the elements inside one). |
| `heuristic_already_met_no_change` | Current spacing already ≥ target at iteration 0. |
| `dry_run_recommendation_not_applied` | `dryRun: true` short-circuit; no mutation; `iterationCount = 0`. |
| `iteration_apply_failed_at_iteration_N_reverted_after_M_accepted_iterations` | A contained mutation threw mid-application; best-effort rollback applied, prior accepted iterations preserved. |
| `density_floor_reflow_required` | **PASS-HONEST (in-loop).** The loop reached an in-regime density floor — more spacing inside the loop's bounded ladder cannot help. Act on the reflow offer (see "When a spacing tool says the view needs a structural reflow"). |
| `reroute_degraded_input_baseline` | **Pre-loop accessor-layer safety net** (sibling to `dry_run_recommendation_not_applied`). The tool's internal pre-loop reroute pass scored a strictly lower aggregate `thresholdsMet()` than the bare input baseline, indicating the reroute would have degraded the input. The bare input is returned untouched (`iterationCount = 0`, `appliedDeltas = []`, no mutation, no view damage). See "When a spacing tool says it would have degraded the input" below. |
| `density_precondition_infeasible_reflow_required` | **Pre-loop SOUND infeasibility certificate** (sibling to `reroute_degraded_input_baseline`). Honestly distinct from the in-loop `density_floor_reflow_required`: this is *"the input precondition is infeasible on its current canvas — the loop was never entered"*, NOT *"the loop reached an in-regime density floor"*. A SOUND one-sided test (`idealUniformAvg = sqrt(unionArea/N) − avgBox < 100`) — zero false-positives by construction. The view is returned untouched (`iterationCount = 0`, `appliedDeltas = []`, no mutation, no view damage); the DTO carries a `densityFloorDiagnosis` string + a consent-gated structural-reflow OFFER (see "When a spacing tool says the view needs a structural reflow"). |

> **What `N` counts in the three `_iterations` tokens.** In `budget_exhausted_after_N_iterations`, `aggregate_threshold_regressed_at_iteration_N_reverted_to_iteration_M` and `iteration_apply_failed_at_iteration_N_reverted_after_M_accepted_iterations`, the interpolated number is the count of accepted **commands**, not of accepted iterations — despite what the tokens' own nouns say. The two differ because an **escalate** iteration pushes *two* commands: the one-shot hub-resize plus that iteration's spacing command. So `N` exceeds `iterationCount` by exactly the number of hub resizes the run performed. A response reading `budget_exhausted_after_3_iterations` beside `elementIterationCount: 2` is **consistent**, not contradictory: three commands, two iterations, one hub resize. Use `iterationCount` (`elementIterationCount` / `groupIterationCount` on the composed tool) whenever you want iterations; the token's number is not that. The token shapes are published and unchanged.

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

   **Read the offer the tool actually returned — it is viewpoint-aware.** On an `implementation_migration` or `migration` viewpoint a full reflow would reorder the mandatory chronological x-axis those viewpoints require, so the tool returns an **axis-preserving** remedy naming the oversized hub as the root cause instead of the generic reflow. Do not substitute the generic script above on those viewpoints, and do not offer to re-run ELK "to see if it helps" — reordering is illegal there, not merely undesirable. Detection and `terminationReason` are identical across viewpoints; only the remedy differs, so the reason code alone will not tell you which offer you got.
3. **Wait for explicit consent before any structural reflow** (e.g. `auto-layout-and-route` from scratch, or splitting the view). Reflow discards manual placement intent — it is the user's decision, not the agent's.

This is the difference between the two sound `*_reflow_required` signals and the informational `parallelConnectionGap.vAxisParallelGapP10` signal: the former two are deterministic stops computed from the view's own geometry (one in-loop, one pre-loop); the latter is a heuristic narrow-corridor indicator. All three point at the same remedy class (structural change, not more spacing), but the two `*_reflow_required` reasons are the authoritative ones to act on.

**You do not need to manually count spacing-tool invocations.** The old guidance ("stop after 3 spacing calls") is superseded — the control loop self-terminates honestly via `density_floor_reflow_required` (in-loop) / `density_precondition_infeasible_reflow_required` (pre-loop) / `aggregate_threshold_regressed`. Manually re-driving single-shot `adjust-view-spacing` in a loop bypasses that protection; prefer the convenience tools.

## When a spacing tool says it would have degraded the input

`terminationReason: reroute_degraded_input_baseline` is a **pre-loop accessor-layer safety net**, sibling to `dry_run_recommendation_not_applied` — both fire **before** the control loop is entered. It is a deliberate "guard, don't veto" mechanism: before the loop runs, the accessor temp-routes the bare input on the SAME routing basis the loop uses to measure each per-step `postState`; if that route-normalized assessment scores a strictly lower aggregate `thresholdsMet()` than the bare input, the spacing tool's internal reroute would have made things worse. The tool **returns the bare input untouched** (`iterationCount = 0`, `appliedDeltas = []`, no mutation, no view damage) and surfaces this reason so the agent knows what happened.

That measurement runs on a **detached copy** of the diagram, routed off the command stack, so a short-circuited call is a genuine no-op: it leaves no undo entry, no stray redo, and — importantly for you — **does not advance `_meta.modelVersion` or set `modelChanged`**. You can therefore trust those two signals to distinguish a real mutation from a call that decided to change nothing. (Before this, the probe routed the live diagram and rolled back, which applied nothing but still bumped the counter by over a hundred and left the model looking dirty.)

**What the comparison actually is:**

- The comparison is a route-normalized-vs-bare `LayoutMetrics.thresholdsMet()` comparison performed by the accessor; both sides are measured the same way, just on different routing states of the same view.

**What you (the agent) should do:**

1. **Your view is safe.** The bare input was returned untouched — no mutation occurred, no view state was damaged. You can keep working from the same starting point.
2. **Route first, then re-call spacing — that is the default order, not a workaround.** Call `auto-route-connections` (or `auto-layout-and-route` if the layout-tier preconditions are also unmet) before re-calling the spacing convenience tool. The reroute pass the spacing tool runs internally then starts from a routed baseline. This is not a special recovery for this signal: the disposition matrix below puts the routing pass before spacing on every row that has a spacing step.
3. **Expect this on a view whose connections carry no stored routing.** The guard is one comparison: it scores the bare input as it stands against the same view as the tool's internal reroute pass would leave it, and fires whenever the rerouted side scores lower. It does **not** test whether the input was routed, so a view whose existing route is already good can reach it too — that is the case the closing paragraph below describes. What differs on an input that has never been routed — connections still straight from placement or `auto-connect-view` — is that the two sides are then separated by an entire routing pass rather than by a refinement of one. Route the view first (step 2 above) and the comparison is between two routed states.

This is the difference between the sound `density_floor_reflow_required` certificate (the view is provably too dense — structural change is the only remedy) and this `reroute_degraded_input_baseline` signal (a specific spacing call would have made things worse on this input — try again with a different starting state, or accept that this view's current routed state is already at a local optimum the spacing pass cannot improve).

## Disposition matrix — apply preconditions in this order

| View shape | Order |
|---|---|
| Flat view (no groups) | hub sizing → routing |
| Grouped view, no inter-group connections | hub sizing → routing → inter-element spacing → routing |
| Grouped view, with inter-group connections | `arrange-groups` topology → hub sizing → routing → inter-element spacing → inter-group spacing → routing |
| Hub-and-spoke topology with dense inter-group flow | `arrange-groups` topology (with `spacing >= 100`) → hub sizing → routing → inter-element spacing → inter-group spacing → routing |

Annotations — notes, view-references and standalone images — go **after** the last routing pass on every row. That rule is necessary and it is not sufficient: after routing is precisely when there are corridors to land in, so each of `add-note-to-view`, `add-view-reference-to-view` and `add-image-to-view` measures the rectangle it is about to place against the routes already on the view and returns an `ANNOTATION_PLACED_IN_ROUTED_CORRIDOR` structured warning naming every connection it crosses. Move the annotation with `update-view-object` rather than re-routing around it. A note lands in the informational `connectionThroughNoteCount`; a view-reference or an image lands in the **rated** `connectionPassThroughs` and can take the view to `poor`.

The routing pass **before** spacing is not optional and is not a recovery step. Each spacing arm runs one route-normalized check before its loop starts, scoring the input it was handed against what its own reroute pass would produce; on connections carrying no stored routing that check returns `reroute_degraded_input_baseline`, the loop is never entered, and the spacing step therefore does nothing at all. Routing first gives that check two routed states to compare. The trailing pass is needed because spacing moves elements, which leaves the stored routes drifted and worth recomputing — not because a spacing step removes them, which is also why one leading pass covers both spacing steps on the rows that have two.

## Following `assess-layout` nextSteps

`assess-layout` emits a `nextSteps[]` envelope that names the right precondition tool with violator IDs already attached when these conditions trigger. Act on `nextSteps` directly — the heuristics and violator IDs are pre-computed.

| Violation | Tool named in `nextSteps` |
|---|---|
| `overlapCount > 0` (sibling overlaps) | `layout-within-group` on the offending parent (with sibling-pair violator IDs) |
| `cousinOverlapCount > 0` (cross-branch overlaps) | Informational, no rating weight — but read it before acting on `overlapCount`. `overlapCount` is a **same-parent** count, so a collision between two objects in different branches of the containment tree is reported through their *containers*; `cousinOverlaps` names the differently-parented objects whose rectangles actually intersect. Reposition or re-parent from those IDs rather than laying out the container the sibling count blamed |
| `boundaryViolationCount > 0` (children outside parent group bounds) | Composite remedy `nextSteps` entry — call the named tool sequence directly (typically `update-view-object` to reposition the child, or `resize-elements-to-fit` on the parent, which grows a container around its contents and never shrinks one) |
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

- **`labelOverlapCount`** flags a connection label on an element box, including a Middle label on its **own** endpoint. On Archi 5.10 the routing tools clear the own-endpoint case automatically via the connection Label Offset (just run `auto-route-connections`); otherwise move the label with `labelPosition` or hide it with `showLabel: false`. When labels collide at *every* position, `auto-route-connections` (and `auto-layout-and-route`, in grouped mode or with `targetRating`) with `labelPolicy: "auto-hide-on-collision"` hides just those and reports each one by connection ID in `hiddenLabels` (opt-in; omitted means nothing is hidden).
- **`noteClipCount`** flags a note whose text is taller than its box, which can only happen when the height was explicitly pinned — a fitted note is by construction tall enough. Re-send the note's `text` (or its `width`) through `update-view-object` with `height` omitted and the server re-fits it, raise the height, or reduce the font size. Omitting `height` on its own is not a request the tool accepts: at least one field must change.
- **`imageSiblingOverlapCount`** flags a custom image or specialization icon overlapping a sibling. Increase spacing, reposition the image, or shrink the icon. Archi **clips** an image to its element box, so the measured rectangle is the intersection of the anchored image with the box — an oversized icon's overhang is not drawn and is not counted.
- **`overlayIconCollisionCount`** flags a container's corner icon overlapping a *nested child's* own corner icon — the containment axis the sibling detector above cannot see, because it buckets objects by parent. Move one of the two icons to a different corner (`imagePosition`), or grow the container so the two bands separate. Newly-placed objects reserve a second icon band automatically; a nonzero count on an older view means the geometry predates that reservation and needs fixing by hand.

## Auto-route structured warnings

`auto-route-connections` returns `structuredWarnings[]` alongside the free-text `warnings[]`. Each entry carries `{code, message, remediationTool, remediationViolatorIds}` for deterministic iteration. **Key off the `code`, never off the free-text list** — several unrelated producers write to `warnings[]`, so its contents tell you nothing about which condition fired.

- **`AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP`** — emitted when `autoNudge: true` cannot proceed because two sibling elements overlap. The skip is a hard gate; re-running `auto-route-connections` without first separating the siblings will reproduce the same skip. Action: call the named `remediationTool` (`layout-within-group`) on the parent of `remediationViolatorIds` BEFORE re-routing.
- **`AUTO_ROUTE_CROSSINGS_REGRESSED`** — emitted when a **full** re-route left the view with *more* edge crossings than it started with. The message names both counts. This fires only when the input was already routed, so it never second-guesses a first-time route. **The signal holds two integers — the crossing count before and after — and nothing else**, so read it as a measurement rather than a verdict on the view. Edge crossings are the *last* of the thirteen metrics the composite rating weights, below the pass-throughs, interior terminations and coincident segments a full re-route most often clears, so a re-route that raises crossings can still lift the view's overall rating; review the view before recovering. If you do want the previous geometry back, read the named `remediationTool`, which is scoped to the arm the call took — `undo` once the re-route has been applied (which reverts the whole routing pass, not just the crossings), `end-batch` (with `rollback:true` to discard it) while it is queued in an open batch, and no tool at all while it awaits a human's decision, where rejecting the change in Archi leaves the previous paths as they are. Recover by that route, then re-route with `mode: "terminals-only"`, which refuses crossing-adding rectifications by design. Do **not** re-run full mode expecting a different result, and do **not** `undo` a re-route that has not been applied — it reverts whichever command is really on top of the stack.
- **`EGRESS_LIFT_LAYOUT_BOUND`** — the router generated then rolled back an off-face egress lift because applying it would narrow a parallel-connection gap below the 15 px healthy floor. The residual hug is **layout-bound**: re-routing cannot clear it. Action: spread the elements (the matching `nextSteps` entry names the tool), then re-route.
- **`CONNECTION_NOT_FOUND`** — one or more ids passed in `connectionIds` did not resolve on the view. Every missing id is listed in `remediationViolatorIds`; re-read the view with `get-view-contents` rather than parsing prose.
- **`AUTO_NUDGE_NET_ZERO`** — `autoNudge` moved one or more elements and each ended at the position it started from (summed displacement `0,0`). Such an element is **not** listed in `nudgedElements` and **not** counted in `nextSteps`: reporting it as nudged contradicted the same response's `failed` array and its `recommendations`, which repeat the identical move. Two causes produce the zero — a move a later iteration reversed, and a move the parent-containment clamp absorbed — and the summed deltas cannot tell them apart, so the message names the outcome and says so rather than claiming the loop oscillated. Action: the named `remediationTool` is `apply-spacing-recommendations`. Do **not** re-run `auto-route-connections` expecting a different result — both causes are properties of the geometry it was handed, so it recomputes the same recommendation and reproduces the same zero. Note that a **`resizedGroups` entry can still appear** on such a call: the parent-fit cascade is grow-only, so a group grown for a move that was later reversed is not shrunk back.
- **`CONNECTION_ROUTED_THROUGH_NOTE`** — one or more applied routes pass through a note. This is a **disclosure, not a failure**: a note is deliberately excluded from the obstacle set, so the router routes through one that sits in a corridor, exactly as its description says. Exactly one entry per call, naming every (connection, note) pair with the pair count in the message and never truncated. The two share one geometry, so they agree on every pair **this call routed** — but `connectionThroughNoteCount` is a **whole-view** figure and can legitimately be higher: it also counts notes crossed by connections this call did not route (a partial `connectionIds` call), and it counts element-embedded **images**, which this warning does not cover at all. A higher count is therefore not a disagreement; diff the pairs, not the totals. Action: the named `remediationTool` is `update-view-object` — move the **note**, not the route. Do **not** undo: the route is not the thing that is wrong, and undoing discards a good route while leaving the note exactly where it was. Then re-run `assess-layout`, because moving a note changes the routes around it and the count after the move is not predictable from the count before it.

**Note placement ordering.** That last code is the one worth planning around rather than reacting to. The `auto-route-connections` tool description states the rule and the reason — read it there rather than re-deriving it here — and the practical consequence is the ordering every conversion prompt in this repo already enforces: **position notes after routing, then re-assess.** A note placed first is an obstacle the router will not avoid and a rectangle later layout passes can grow underneath.

## The narrow-corridor floor

`parallelConnectionGap.vAxisParallelGapP10` measures the 10th-percentile parallel-segment gap on the V axis. An ArchiMate manual-routed reference view anchors at 13.30 px. When this signal drops below ~6 px, the view is in the narrow-corridor regime: convenience spacing tools cannot mitigate further because the structural floor is determined by topology, not by router input. This is the informational counterpart to the sound `density_floor_reflow_required` certificate — both indicate that the remedy is structural change, not more spacing. Mitigation paths:

- Reduce hub fan-out (split a hub into two specialised hubs).
- Split the view across two narrower scopes.
- Apply manual bendpoint surgery via `update-view-connection` to relocate specific high-cost segments.

## After routing — verify, don't iterate blindly

Always run `assess-layout` after routing. The `M6` two-dimensional rating reports `(layoutTier, routingTier)` so you can see whether a routing change improved the routing tier without dragging the layout tier down. If the layout tier regressed, the precondition setup was insufficient — go back to step 1, do not re-route in a loop.

**Two tools tell you about a regression without being asked.** Read these before you re-assess, not instead of it:

- `auto-layout-and-route` with a `targetRating` reports `ratingBefore` beside `achievedRating`, and raises `AUTO_LAYOUT_RATING_REGRESSED` in `structuredWarnings` when the applied result is worse — naming every metric that moved with its before and after values. Once the run has been applied, **`undo` is the remedy and one undo is enough** — it commits as a single compound operation; a queued or awaiting-approval run has nothing to undo yet, and the warning names the remedy for that arm instead. Do not read `achievedRating` alone as evidence the call helped: meeting a target is *at least as good as*, so an `excellent` view downgraded to a `good` target reports goal reached and is still a regression.
- `auto-route-connections` raises `AUTO_ROUTE_CROSSINGS_REGRESSED` when a full re-route increased the view's own edge-crossing count, pointing at `mode: "terminals-only"`, which refuses crossing-adding rectifications. Unlike the entry above it, this one is scoped to **one metric** — the lowest-weighted of the thirteen — so it is not a claim that the view got worse. Re-assess before acting on it.

**A drifted route is not a shape defect — do not straighten it.** `anchorDriftCount` flags a connection whose stored route was computed for geometry that has since moved: the two per-endpoint reconstructions of each bendpoint no longer agree, so the drawn line is *sheared*, worst at the terminals. The stored shape was correct when it was written, so the remedy is to **re-route the connection**, not to run a straightener at it. `lateralJogReversalCount` is the sibling detection — a connection doubling back through a sidestep too narrow to be routing around anything. Both are informational: they never change the rating, so a `poor` view will not become `fair` by clearing them, and they will not appear in `ratingBreakdown`.

**Read `coverage` before you certify a view clean.** The response's `coverage` map says, per defect dimension, whether a detector actually evaluated it: `checked` (ran and covers the dimension, so a zero is genuinely clean), `partial` (ran but covers only *some* of the dimension's failure modes — the uncovered ones must be render-verified), `not-checked` (nothing evaluated it, so absence of a finding is not evidence of absence), or `not-applicable`. A dimension counts as clean only when `coverage == checked` **and** its `ratingBreakdown` entry passes. Two dimensions report `partial` on **every** run, because the gap is structural rather than run-dependent:

- **`labelTruncations`** — a visual group's title is never measured, and an element whose label measurement failed is skipped too. A zero certifies only that every *measured element* label fits. Export the view and eyeball group titles before calling truncation clean.
- **`edgeCoincidence`** — the detector classifies each segment as horizontal or vertical and skips everything else, so a diagonal segment is never compared against an element edge.

Two more downgrade *contextually*, only on runs that trigger them: `labelOverlaps` when a label is wider than its hosting segment, and `parentLabelObscured` when a parent's title band could not be measured (a visual group, or an element whose label measurement failed) — that parent still gets a verdict, but its band is sized as a single line however long the title is, so a title wrapping onto a second row is compared against only its first. A **degenerate view** (at most one object) does not receive a rating at all — spacing and alignment have no data below two objects — but it still runs and declares the detectors that are computable on one object.

## Related references

- `archimate://reference/archimate-view-patterns` — full Pre-Layout Planning Checklist, Group Composition Patterns, ArchiMate Modelling & Aesthetic Best Practices, and viewpoint-specific workflow recipes.
- `detect-hub-elements` tool description — hub thresholds (≥ 5 = candidate, > 6 = sizing suggestion emitted; `M5_FACE_GUARD_MIN_CONNECTIONS = 4` is a separate internal per-face guard for the M5 hub-port-quality metric, not a hub-detection threshold). For high-fan-out hubs (> 12 connections) the tool also emits a 2D-resize suggestion that distributes ports across all four edges.
- `adjust-view-spacing` tool description — the single-shot primitive: three additive deltas (`interElementDelta` / `paddingDelta` / `interGroupDelta`) and density-aware default behaviour. Does not run the control loop.
- `apply-spacing-recommendations` tool description — composed convenience tool: two coordinated control loops, `scope` parameter (`both` / `element` / `group`), per-arm `terminationReason` / `iterationCount` / `appliedDeltas[]`, and the legacy `elementKneeClampApplied` / `groupKneeClampApplied` flags (still emitted when an arm's per-iteration step cap fires).
- `apply-element-spacing-recommendations` tool description — single-arm convenience surface for precondition 2 (embedded control loop).
- `apply-group-spacing-recommendations` tool description — single-arm convenience surface for precondition 3 corridor-widening half (embedded control loop).
- `arrange-groups` tool description — `grid` / `row` / `column` / `topology` arrangements and density-aware spacing default.
