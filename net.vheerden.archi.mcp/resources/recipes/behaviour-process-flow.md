# Recipe Family — Behaviour & Process Flow

Topology archetype: **swimlanes** (process cooperation) or **a journey band over support layers** (service design). Apply the invariant build sequence from the index; only the deltas below change.

## Business Process Cooperation View (`viewpoint: "business_process_cooperation"`)

- **When to use:** show a process flow, its hand-offs, and who performs each step.
- **Element subset:** `BusinessProcess`, `BusinessService`, `BusinessRole` / `BusinessActor`; `TriggeringRelationship` / `FlowRelationship` for the flow.
- **Relationship subset:**
  - **Draw:** `TriggeringRelationship`, `FlowRelationship` (the process flow between steps).
  - **Imply by nesting, exclude from the filter:** `AssignmentRelationship` role→process — **nest the process inside its performing role's swimlane** instead of drawing the assignment. This is the Cookbook's explicit space-saving, crossing-reducing choice.
- **Topology:**

```text
┌ Role: Customer ──────────────────────────────────────┐
│   (Step1) ───────────────────────────▶ (Step5)        │
└───────────│──────────────────────────────▲────────────┘
            │ trigger/flow                  │
┌ Role: Sales ──────────────────────────────│────────────┐
│        (Step2) ─────────▶ (Step3)          │            │
└────────────────────────────│───────────────┘            │
                             │ trigger/flow                │
┌ Role: Fulfilment ──────────▼────────────────────────────┐
│              (Step4) ────────────────────────────────────│ ▶ back to Customer
└──────────────────────────────────────────────────────────┘

Rule: each ROLE is a horizontal swimlane (a large container); its PROCESS
steps are nested inside it; the flow runs left-to-right and crosses lanes
vertically at hand-offs. Assignment is implied by containment, never drawn.
```

- **Deltas vs. the invariant sequence:**
  - Step 2: one group per role/actor — these are the swimlanes (large containers).
  - Step 3: nest each process under its performing role via `parentViewObjectId`.
  - Step 4: `relationshipTypes: ["TriggeringRelationship", "FlowRelationship"]` (Assignment excluded — implied by nesting).
  - Step 6: `layout-within-group` `row` (steps sit in flow order within the lane).
  - Step 7: `arrange-groups` `arrangement: "topology"`, `direction: "horizontal"`.
- **Caveat — a return/back Flow edge reverses ELK's direction.** ELK derives the flow direction from edge direction, so a cycle edge (e.g. a `response`/`ack` Flow from the final step back to the first) flips the whole layout right-to-left. Keep the spine acyclic *for layout*: lay out and route from the forward `TriggeringRelationship` chain only; if a genuine return must be shown, add it **after** layout (or carry it in a note / a short labelled Flow), and never let the back-edge feed `arrange-groups` / ELK `direction`. Numbered step labels anchor the reader's order even if a late edge nudges the layout.
- **Source:** Cookbook "Business Process Cooperation" + swimlane/nesting pattern; reference [17].

## Service Design / Customer Journey View (general-purpose — omit `viewpoint`)

- **When to use:** an outside-in view — the customer journey on top, the organisation and systems supporting it beneath.
- **Element subset:** journey steps (`BusinessProcess` / `BusinessService` on the path), supporting `BusinessService` / `ApplicationService`, supporting `ApplicationComponent`; `ServingRelationship` from the support layers up to the journey.
- **Relationship subset:**
  - **Draw:** `ServingRelationship` (support seams, drawn sparingly — the journey order carries the narrative).
  - **Imply by nesting, exclude:** `CompositionRelationship` / `AssignmentRelationship` within the support layers.
- **Topology:**

```text
┌ Customer Journey ───────────────────────────────────────┐  top band
│  Aware ─▶ Evaluate ─▶ Buy ─▶ Onboard ─▶ Use ─▶ Support    │  (left→right)
└───────│────────│───────│───────│────────│────────│────────┘
        ▼        ▼       ▼       ▼         ▼        ▼   serving (upward)
┌ Business support ───────────────────────────────────────┐  middle band
│  business services that fulfil each journey step          │
└──────────────────────────────────────────────────────────┘
        ▲                                                   serving
┌ Applications ───────────────────────────────────────────┐  bottom band
│  application services / components                        │
└──────────────────────────────────────────────────────────┘

Rule: the journey is the TOP swimlane (left-to-right); support layers stack
beneath it; serving links run vertically upward into the journey step served.
```

- **Deltas vs. the invariant sequence:**
  - Step 2: three bands — "Customer Journey" (top), "Business support" (middle), "Applications" (bottom).
  - Step 3: journey steps into the top band in order; support elements into the lower bands.
  - Step 4: `relationshipTypes: ["ServingRelationship"]`.
  - Step 6: `layout-within-group` `row` for the journey band, `grid` for the support bands.
  - Step 7: `arrange-groups` `arrangement: "column"` (journey → business → applications, top-to-bottom).
- **Source:** Cookbook "Service Design / Customer Journey" pattern; reference [17].
