# ArchiMate Viewpoint Recipes — Index

Read this page first. It does two things: it states the **invariant build sequence once**, and it routes you to the **one** thing the generic guidance cannot give per viewpoint — the **topology** (the spatial shape).

Most ArchiMate viewpoints lay out well from the modelling and aesthetic principles already shipped in `archimate://reference/archimate-view-patterns` plus the element-type decision aid in `archimate://reference/archimate-layers`. Empirically (clean-context measurement, 2026-05-19) a capable model produces near-faithful plans for the *conventional* shapes — layered band-stack, clustered grid, hierarchy/tree, object bands — from those principles alone. A **recipe exists only where a real topology gap was measured**: the non-conventional shapes whose spatial form the principles cannot encode.

## Step 0 — is your diagram conventional or non-conventional?

| Your diagram's shape | Action |
|---|---|
| **Layered** (Business→Application→Technology bands) | **No recipe.** Apply `archimate://reference/archimate-view-patterns` best-practice rules 1 (layer top-to-bottom), 4 (nest parts), 8 (viewpoint element subset); `arrange-groups arrangement:"column"`. |
| **Application landscape / inventory** (*what* the estate contains, grouped by domain, no integration hub — the connections are not the point) | **No recipe.** Clustered grid: one Grouping per domain, `arrange-groups arrangement:"grid"`. Do **not** invent a hub. |
| **Application interaction / collaboration topology** (*how* the whole estate interacts — flat, no domain bands, no integration hub; the connections **are** the point) | **No recipe.** Lay it out with `auto-layout-and-route` `mode:"auto"` (ELK Layered) — crossing minimisation on a dense app-to-app graph is what that algorithm is for. Do **not** create Groupings, and do **not** take the integration row in Step 2: that one is the narrow hub-centred slice. |
| **Specialization hierarchy** (a type tree via SpecializationRelationship) | **No recipe.** `tree` layout algorithm, root at top; draw only `SpecializationRelationship`. |
| **Organization structure** (org units / roles by containment) | **No recipe.** Nested-containment hierarchy; `tree`/column. Exclude by nesting (rule 4): `CompositionRelationship` of a `BusinessActor` into sub-actors or a `BusinessRole` into sub-roles — same type only, ArchiMate permits no Composition between an actor and a role in either direction; and `AssignmentRelationship` `BusinessActor`→`BusinessRole` — that direction only, since no concept here may be assigned to its own type and a role may not be assigned to an actor. |
| **Information structure** (business objects + data objects) | **No recipe.** Two stacked bands (BusinessObjects above, DataObjects below), `arrange-groups arrangement:"column"`; draw the `RealizationRelationship` `DataObject`→`BusinessObject` — that direction only: ArchiMate permits no Realization from a `BusinessObject` to a `DataObject`, nor between two objects of the same type. |
| **Anything below** | **Fetch the one matching recipe page.** Its topology block is the payload — match it. |

If your diagram is conventional, stop here and apply the principles — you do not need a recipe.

## Step 1 — the invariant build sequence (every recipe runs this)

A recipe gives only the per-family **deltas** — the parameters that change. Do not re-derive this; apply it as the engine.

1. `create-view` (with the family's `viewpoint` param, or none for general-purpose).
2. One container per group the topology calls for. Two kinds qualify, and the arrangement tools this sequence uses — `arrange-groups`, `optimize-group-order`, `adjust-view-spacing` — treat the two **kinds** alike (they differ on **depth**: only `arrange-groups` positions a container drawn inside a host): an ArchiMate `Grouping` element (`create-element` then `add-to-view`) or a native view group (`add-group-to-view`). **Prefer the `Grouping` element** wherever a family page names one — it is a real concept, so it participates in relationships and answers questions like "what runs in this zone?", where a native group exists only on the canvas. Use a native group for a purely visual box you never intend to query. Parity stops at the arrangement tools: `layout-within-group`'s upward re-fit (`recursive: true`) walks native view groups only and tells you so via `ancestorPropagation`, and `resize-elements-to-fit` sizes **elements** to their labels while treating a `Grouping` as a zone — it grows one to contain its children and never shrinks one or sizes one to its own name, so it is safe to aim at a view built from zones. Step 8 rules it out for a different reason: hub fan-out, where the problem is connection-port congestion rather than label clipping.
3. `add-to-view` each element with `parentViewObjectId` set per the topology, `autoSize: true`. Nest a part inside its parent; never draw the part-of relationship (`archimate://reference/archimate-view-patterns` best-practice rule 4).
4. `auto-connect-view` filtered to the family's **draw** relationship types only.
5. `get-view-contents` `format=tree` to discover container viewObjectIds.
6. `layout-within-group` per container — or once per top-level container with `recursiveChildren: true` when the topology nests more than one level deep (a container holding element-containers that hold their own children), which arranges every level post-order in a single atomic command.
7. `arrange-groups` with the family's arrangement + direction. It positions every top-level container, of either kind, and preserves each one's width and height; the response reports where each one landed, so you do not need a `get-view-contents` round trip to find out. A container nested inside another **container of the same kind** is a member of it, not a target, and moves with it. A container nested inside a **host** is nobody's member and is arranged — inside that host, in the host's own coordinate space, reported in `nestedContainersArranged` with the `hostViewObjectId` its coordinates are relative to.
8. `detect-hub-elements`; resize any hub via `update-view-object` (never `resize-elements-to-fit`).
9. `apply-spacing-recommendations` `scope: "both"` → `auto-route-connections` `autoNudge: true` → `assess-layout`. On `terminationReason: density_floor_reflow_required`, surface the `densityFloorDiagnosis` and the consent-gated reflow per `archimate://prompts/routing-preconditions-checklist` — do not loop the spacing tools, do not auto-reflow.

## Step 2 — fetch your non-conventional family page

| What the diagram must communicate | Family page to fetch | Covers |
|---|---|---|
| How applications integrate around an **actual integration component** (ESB / API gateway / broker) — the narrow hub-centred slice, **not** the estate-wide interaction picture, which is a Step 0 case | `archimate://recipes/application-integration` | Application Cooperation / Integration |
| A process flow, its hand-offs, who performs each step; or an outside-in customer journey over support layers | `archimate://recipes/behaviour-process-flow` | Business Process Cooperation; Service Design / Customer Journey |
| Why the architecture exists — stakeholders, drivers, goals, requirements and their influence chain | `archimate://recipes/motivation` | Motivation |
| What runs where — software deployed onto infrastructure nodes | `archimate://recipes/technology-deployment` | Technology / Deployment |
| Change over time — current/target states, what moves between them | `archimate://recipes/roadmap-migration` | Implementation & Migration / Roadmap |

Fetch **only** the one page that matches. You do not need to read the others, and there is no per-viewpoint page for the conventional shapes in Step 0 by design.

## How to read a family page

Each recipe has the same five parts:

- **When to use** — the concern the diagram answers.
- **Element subset** — the ArchiMate element types to place (the Cookbook "80% rule").
- **Relationship subset** — which types to `auto-connect-view`, and which to **imply by nesting and exclude** from the filter.
- **Topology** — a text block diagram of the spatial shape. **This is the payload — match it.**
- **Deltas vs. the invariant sequence** — the 2–3 step parameters that differ (arrangement, direction, the relationship filter, nesting parentage).

Source for the patterns: the *ArchiMate Cookbook* (reference [17] in `docs/bibliography.md`). The recipes are this project's curated, version-pinned interpretation — self-contained; no external document needs to be fetched.
