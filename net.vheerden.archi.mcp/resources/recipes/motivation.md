# Recipe Family — Motivation

Topology archetype: **a directed influence chain in stacked bands** — *why* the architecture exists, read top-down from stakeholders to concrete requirements. Apply the invariant build sequence from the index; only the deltas below change.

This recipe exists because the generic principles do not encode the motivation chain's spatial form: without it a fresh model lays motivation elements out as an undifferentiated grid and the stakeholder→requirement reading order is lost. Match the topology block — it is the payload.

## Motivation View (`viewpoint: "motivation"`)

- **When to use:** show *why* — the stakeholders, what drives them, the goals that answer those drivers, and the requirements/principles that make the goals concrete. Used for goal-modelling, requirements traceability, and compliance rationale.
- **Element subset:** `Stakeholder`, `Driver`, `Assessment`, `Goal`, `Outcome`, `Requirement`, `Constraint`, `Principle`; `Meaning` / `Value` only when the diagram is specifically about them. The chain's spine is **Stakeholder → Driver → Assessment → Goal → Requirement**; `Principle` and `Constraint` qualify the requirement band.
- **Relationship subset:**
  - **Draw:** `InfluenceRelationship` (the spine — driver/assessment influence goals, principles influence requirements), `RealizationRelationship` (requirement/outcome realizes goal — the "closure" link), `AssociationRelationship` (stakeholder ↔ driver, and other untyped motivation links).
  - **Imply by nesting, exclude from the filter:** `AggregationRelationship` of a `Driver` into sub-drivers or a `Goal` into sub-goals — nest the part; do not draw the aggregation arrow. **Same type only:** ArchiMate permits no Aggregation between a `Driver` and a `Goal` in either direction; the link between those two is the `InfluenceRelationship` above.
- **Topology:**

```text
┌ Stakeholders ───────────────────────────────────────────┐  band 1 (top)
│  Regulator        Customer        Board                   │
└──────│────────────────│───────────────│──────────────────┘
       │ association     │ association    │           ▼ influence
┌ Drivers & Assessments ──────────────────────────────────┐  band 2
│  Compliance Pressure ──▶ Current Gaps (Assessment)        │
└──────────────────────────────────│───────────────────────┘
                                    │ influence ▼
┌ Goals & Outcomes ───────────────────────────────────────┐  band 3
│        Achieve Compliance ◀─ realization ─ (from below)   │
└────────────────────────────────│──────────────────────────┘
                                  │ influence / realization ▲
┌ Requirements, Constraints, Principles ───────────────────┐  band 4 (bottom)
│  Encrypt PII at rest ◀─ influence ─ Data Minimisation     │
│  (Requirement)                       (Principle)          │
└──────────────────────────────────────────────────────────┘

Rule: four horizontal bands stacked top-to-bottom — Stakeholders, then
Drivers/Assessments, then Goals/Outcomes, then Requirements/Constraints/
Principles. The influence/realization chain runs vertically down the spine;
realization arrows from a requirement point UP into the goal it makes
concrete. Read top (who cares and why) to bottom (what we must do).
```

- **Deltas vs. the invariant sequence:**
  - Step 1: `create-view` with `viewpoint: "motivation"`.
  - Step 2: four `Grouping` bands — "Stakeholders", "Drivers & Assessments", "Goals & Outcomes", "Requirements & Principles". Place each element in its role's band; a `Principle` goes in the requirements band (it influences requirements).
  - Step 3: nest a sub-driver under its parent `Driver`, or a sub-goal under its parent `Goal`, via `parentViewObjectId` — only when an `Aggregation` makes it a part, and only within the same type; otherwise elements sit directly in their band.
  - Step 4: `relationshipTypes: ["InfluenceRelationship", "RealizationRelationship", "AssociationRelationship"]` (Aggregation excluded — conveyed by nesting where used).
  - Step 6: `layout-within-group` `row` per band (elements of the same role sit side-by-side in the band).
  - Step 7: `arrange-groups` `arrangement: "column"`, `direction: "vertical"` (the four bands stack top-to-bottom; the influence chain reads downward).
  - Step 8: hubs are uncommon here — a `Goal` with many influencing drivers can exceed the fan-out threshold; resize it if `detect-hub-elements` flags it, otherwise skip.
- **Source:** Cookbook "Motivation / Goal modelling" pattern; reference [17].
