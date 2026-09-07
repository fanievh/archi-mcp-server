# Recipe Family — Roadmap / Migration

Topology archetype: **a left-to-right time axis of plateaus, gap elements between them, work packages on a band below**. Apply the invariant build sequence from the index; only the deltas below change.

This recipe exists because a roadmap is the one ArchiMate shape whose primary axis is **time**, not layer or flow. The generic principles have no timeline form — without this recipe a fresh model stacks plateaus into a column or grid and the chronological reading is lost. Match the topology block.

## Roadmap / Migration View (ArchiMate "Implementation and Migration" viewpoint)

- **When to use:** show change over time — the current state, one or more transition states, the target state, the differences between them, and the work that closes those differences. Used for transformation roadmaps and migration plans.
- **`viewpoint` parameter:** use your tool's identifier for the ArchiMate *Implementation and Migration* viewpoint (commonly `implementation_migration`); if the tool rejects it, omit `viewpoint` and build a general-purpose view — the topology below is what matters, not the viewpoint tag.
- **Element subset:** `Plateau` (the states on the time axis), `Gap` (an **element** — the difference between two adjacent plateaus, placed *between* them), `WorkPackage`, `Deliverable`, `ImplementationEvent`. The **plateau sequence is the spine**; gaps are elements sitting between consecutive plateaus; work packages/deliverables sit on a band beneath the gap they close.
- **Relationship subset:**
  - **Draw:** `AssociationRelationship` (each `Gap` element is associated with the two `Plateau`s it sits between — this is how ArchiMate ties a gap to its from/to states), `RealizationRelationship` (a `WorkPackage` or `Deliverable` realizes the `Plateau` it brings about — the link that closes a gap).
  - **Imply by nesting, exclude from the filter:** `CompositionRelationship` of a `WorkPackage` into sub-packages or a `Deliverable` into sub-deliverables — nest the part.
  - **Not a part-of relationship:** an `ImplementationEvent` is **not** part of a `WorkPackage` — ArchiMate permits no Composition or Aggregation between them, in either direction. Model the link as `TriggeringRelationship` (`ImplementationEvent` → `WorkPackage`): the deadline paces the package. Place the event inside the package's box if it reads better — that nesting is a **layout choice, not an implied relationship** — and leave `TriggeringRelationship` out of the Step-4 filter, because a connector between a box and the box it sits in is unreadable and this server's own auto-connect declines it.
- **Topology:**

```text
   TIME ────────────────────────────────────────────────────────▶

┌ Plateaus & Gaps (top band) ──────────────────────────────────────┐
│ ┌Plateau ┐  ┌Gap┐  ┌Plateau   ┐  ┌Gap┐  ┌Plateau┐                 │
│ │Baseline│──│ 1 │──│Transition│──│ 2 │──│ Target │                 │
│ └────────┘  └─┬─┘  └──────────┘  └─┬─┘  └────────┘                 │
└───────────────│──────────────────-─│──────────────────────────────┘
                │ realization ▲       │ realization ▲
┌ Work packages (bottom band) ─────────────────────────────────────┐
│   WP: Migrate data                  WP: Cutover                    │
│   └ Deliverable: ETL pipeline       └ Deliverable: go-live runbook │
└────────────────────────────────────────────────────────────────────┘

Rule: Plateaus AND Gaps are placed elements on a single TOP band, left-to-
right in chronological order: Plateau, Gap, Plateau, Gap, Plateau. Each Gap
is a box (not a line) between its two Plateaus, associated with both. Work
packages sit on a BOTTOM band, each aligned beneath the Gap it closes;
realization arrows point UP from a work package into the Plateau it brings
about. The horizontal axis is time — never let a reorder break it.
```

- **Deltas vs. the invariant sequence:**
  - Step 1: `create-view` with the Implementation-and-Migration `viewpoint` (or none — see above).
  - Step 2: two `Grouping` bands — "Plateaus & Gaps" (top) and "Work packages" (bottom).
  - Step 3: `add-to-view` the Plateaus **and** the Gap elements into the top band, in strict chronological order (Plateau, Gap, Plateau, Gap, Plateau). Nest sub-deliverables / sub-work-packages inside their parent via `parentViewObjectId`; an `ImplementationEvent` may be placed inside its work package the same way — that nesting is a **layout choice, not an implied relationship**.
  - Step 4: `relationshipTypes: ["AssociationRelationship", "RealizationRelationship"]` (Composition excluded — conveyed by nesting; the event `TriggeringRelationship` is outside the filter too — see the relationship subset).
  - Step 6: `layout-within-group` `row` for both bands — `row` preserves the insertion order, which is why Step 3 inserts chronologically.
  - Step 7: `arrange-groups` `arrangement: "column"`, `direction: "vertical"` (the two bands stack: plateaus/gaps over work packages). Do **not** use `arrangement: "tree"` or `"topology"` — those reorder by connectivity and would scramble the chronology that the Step-3 insertion order encodes.
  - Step 8: hubs are uncommon; a `Plateau` realized by many work packages can exceed the fan-out threshold — resize it if `detect-hub-elements` flags it.
- **Source:** Cookbook "Implementation & Migration / Roadmap" pattern; reference [17].
