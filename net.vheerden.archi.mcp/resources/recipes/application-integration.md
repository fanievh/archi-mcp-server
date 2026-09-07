# Recipe Family — Application Integration

Topology archetype: **hub-and-spoke** around an integration component. Apply the invariant build sequence from the index; only the deltas below change.

(Two application shapes need no recipe at all, and both are Step 0 cases in the index: a flat *landscape* inventory grouped by domain — the "ArchiMate Modelling & Aesthetic Best Practices" principles in `archimate://reference/archimate-view-patterns` already cover its clustered grid — and a flat, estate-wide *interaction* topology, where the connections are the point and crossing minimisation is what the layout needs. Use this recipe only when there is a real integration hub.)

## Application Cooperation View (`viewpoint: "application_cooperation"`)

- **When to use:** show how applications integrate and exchange data, usually around an integration component (ESB / API gateway / broker).
- **Element subset:** `ApplicationComponent`, `ApplicationService`, `ApplicationInterface`; the integration component is the **hub**. **Always omit `ApplicationCollaboration` / team elements and their ownership link to the hub.** A model that aggregates the hub into an owning `ApplicationCollaboration` — or associates a business team with it — is *not* a reason to show it: that is ownership metadata, and a cooperation view shows data exchange. Include the owning collaboration *only* when the user's request itself contains an explicit instruction to depict ownership/governance (e.g. the user writes "show who owns/operates the integration"); in that case nest the hub inside the collaboration and still do not draw the aggregation. Default and tie-breaker: omit.
- **Relationship subset:**
  - **Draw:** `FlowRelationship`, `ServingRelationship`.
  - **Imply by nesting, exclude from the filter:** `CompositionRelationship` (a sub-component that is part of a larger component → nest it inside the parent, do not draw the composition arrow); `AggregationRelationship` from an owning `ApplicationCollaboration` to a component (omit the collaboration, or nest) — ArchiMate permits no Assignment into an `ApplicationComponent`, so there is no assignment here to exclude.
- **Topology:**

```text
        [Channel cluster]              [Partner cluster]
         WebPortal  MobileApp           PartnerGW
              \            \             /
               \            ▼           ▼
                ┌─────────────────────────────┐
                │   Integration hub (ESB)      │  ← detect-hub-elements
                │   sized for fan-out (>6)     │     → update-view-object resize
                └─────────────────────────────┘
               /            ▲           ▲
              /            /             \
        [Core cluster]                 [Data cluster]
         CoreBanking                    DataWarehouse
          └ PaymentEngine (nested:       CustomerMDM
            Composition, not drawn)

Rule: the integration component is central and the largest; everything else
is grouped into domain clusters around it; flows run cluster → hub → cluster.
```

- **Deltas vs. the invariant sequence:**
  - Step 2: one `Grouping` per domain cluster (e.g. "Channel", "Core", "Data"); the hub sits on the view between them (not inside a cluster).
  - Step 3: nest a part-component inside its parent (e.g. `PaymentEngine` inside `CoreBanking`) via `parentViewObjectId`.
  - Step 4: `relationshipTypes: ["FlowRelationship", "ServingRelationship"]` (Composition/Aggregation excluded — conveyed by nesting / omitted). When the view needs a **directional** slice a type filter cannot express — a strict producer → hub → consumer cut where same-type reverse flows must stay off the canvas — pass the explicit `relationshipIds` allow-list instead of falling back to one `add-connection-to-view` per edge; it is AND-composed with the type filter, and an unknown id fails the whole call before anything is drawn.
  - Step 7: `arrange-groups` `arrangement: "topology"`, `direction: "horizontal"` (producer → hub → consumer reads left-to-right). **Position the hub yourself.** Auto-placement into the reserved inter-group lane admits only a top-level `Node`, `Device`, `Path` or `CommunicationNetwork` reaching ≥ 2 of the arranged groups — a connection reaches a group both when it terminates on an element **inside** it and when it terminates on the group's **own box**, and two connections to the same group count once. An ESB, API gateway or broker modelled as an `ApplicationComponent`, which is what this recipe's hub always is, does **not** qualify. Place it between the producer and consumer clusters with `update-view-object` after arranging the groups. (The lane does fire for a Technology-layer hub — see `archimate://recipes/technology-deployment`.) If no qualifier exists, group arrangement is unchanged (back-compat). The qualifier predicate is automatic; there is no opt-in parameter.
  - Step 8: the integration component is the expected hub — resize it before routing.
- **Source:** Cookbook "Application Cooperation / Integration" pattern; reference [17].
