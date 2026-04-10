# ArchiMate Specializations

## What Specializations Are

A **specialization** is an IS-A subtype of an ArchiMate concept. For example, "Cloud Server" is a kind of `Node`; "Microservice" is a kind of `ApplicationComponent`. A specialization classifies a *type* of element or relationship — it does not carry per-instance data.

Specializations are bound to a single concrete concept type. The same specialization name on a different concept type is a different specialization (e.g. a "Customer" `BusinessActor` and a "Customer" `BusinessRole` are two distinct specializations).

- **Identity:** `(name, conceptType)` pair, case-insensitive name match.
- **Type binding:** every specialization targets one concrete EClass (e.g. `Node`, `BusinessActor`, `FlowRelationship`). Abstract bases (`ArchimateConcept`, `ArchimateElement`, `ArchimateRelationship`) are rejected.
- **Layer:** derived from the concept type — never set directly.

(Internally Archi calls these `IProfile` instances; the MCP surface uniformly uses "specialization".)

## When to Use Specializations vs Properties

| Use a **specialization** when… | Use a **property** when… |
|---|---|
| You want to classify the *kind of thing* an element is (e.g. "API Gateway") | You want to record an *attribute of an instance* (e.g. `region=us-east-1`) |
| The same classification will appear on many elements | The value is unique or near-unique to one element |
| You will later filter or audit by this category | The value is incidental metadata |

**Do NOT use specializations for:**

- **Environment** ("Production", "Staging", "Dev") — environment is an instance attribute. Use a property.
- **Version** ("v1.2", "v2.0") — instance attribute. Use a property.
- **Owner / team / cost centre** — instance attribute. Use a property.
- **Status** ("active", "deprecated") — instance attribute. Use a property.

If you find yourself creating dozens of specializations that differ only by a numeric or per-instance value, you have built properties in the wrong slot.

## Tool Pipeline

| Step | Tool | Purpose |
|------|------|---------|
| 1. Discover | `get-model-info` | Returns `specializationCount` — non-zero means the model uses a custom vocabulary |
| 2. Browse | `list-specializations` | Lists every specialization with `(name, conceptType, layer, usageCount)` |
| 3. Create inline | `create-element` / `create-relationship` with `specialization` param | Auto-creates the specialization if missing, then creates the concept in one CompoundCommand |
| 4. Update inline | `update-element` / `update-relationship` with `specialization` param | Reassigns the specialization on an existing concept. Pass empty string `""` to clear all specializations |
| 5. Pre-register | `create-specialization` | Defines a specialization explicitly without creating any element. Idempotent — returns the existing one on duplicate |
| 6. Filter | `search-elements` / `search-relationships` with `specialization` filter | Finds all concepts of a specialized type |
| 7. Audit | `get-specialization-usage` | Lists every element and relationship referencing a specialization (call before rename or delete) |
| 8. Rename | `update-specialization` | Renames a specialization. Refuses to merge into an existing target name |
| 9. Delete | `delete-specialization` | Refuses by default if any concept uses it. Pass `force: true` to detach and delete in one atomic command |

The DTO field exposed on `ElementDto` and `RelationshipDto` is `specialization` — the *primary* specialization name only (see Multi-Profile Caveat).

## Common Element Specializations by Layer

These are starting-point patterns. Use them as inspiration, not as a fixed taxonomy.

### Strategy
- **`Capability`:** Core Capability, Differentiating Capability, Supporting Capability
- **`Resource`:** Strategic Asset, Commodity Resource

### Business
- **`BusinessProcess`:** Customer-Facing Process, Back-Office Function, Compliance Process
- **`BusinessActor`:** External Partner, Internal Stakeholder, Regulator
- **`BusinessRole`:** Approver, Reviewer, Operator

### Application
- **`ApplicationComponent`:** Microservice, API Gateway, Message Broker, Data Pipeline, Legacy System, SaaS Application
- **`ApplicationService`:** REST API, GraphQL API, Event Stream
- **`DataObject`:** Master Data, Reference Data, Transactional Record

### Technology
- **`Node`:** Cloud Server, Database Server, Load Balancer, Firewall, Container Platform, Kubernetes Cluster
- **`SystemSoftware`:** Container Runtime, Service Mesh, Observability Stack
- **`CommunicationNetwork`:** Public Internet, Private VPC, Internal LAN

### Physical
- **`Facility`:** Data Centre, Edge Site, Manufacturing Line
- **`Equipment`:** Robot Cell, Sensor Array, Inspection Station

### Motivation
- **`Requirement`:** Security Requirement, Performance Goal, Availability Target
- **`Constraint`:** Compliance Constraint, Budget Constraint, Technical Constraint
- **`Driver`:** Market Driver, Regulatory Driver, Technology Driver

### Implementation & Migration
- **`WorkPackage`:** Migration Work Package, Decommissioning Work Package, Build Work Package
- **`Plateau`:** Steady-State Plateau, Transition Plateau

> Composite-layer concepts (`Grouping`, `Location`) are containers and are rarely specialized in practice.

## Common Relationship Specializations

Relationships also accept specializations. Use them when the relationship type alone does not capture the semantic distinction you need.

- **`FlowRelationship`:** Data Flow, Event Flow, Material Flow, Financial Flow, Message Flow
- **`AccessRelationship`:** Read Access, Write Access, Read-Write Access
- **`ServingRelationship`:** API Serving, UI Serving, Batch Serving
- **`TriggeringRelationship`:** Synchronous Trigger, Asynchronous Trigger
- **`AssociationRelationship`:** Owns, Depends On, Operated By (only when no more specific relationship type fits)

## Bulk-Mutate Pre-Registration Pattern

Use `bulk-mutate` to pre-register a specialization vocabulary at session start, then create elements that reference those names — all in one atomic batch with a single undo step.

Each operation in `bulk-mutate` has the shape `{ "tool": "<tool-name>", "params": { ... } }` — the tool name is *not* a flat field, and parameters are *nested* under `params`.

```json
{
  "operations": [
    { "tool": "create-specialization", "params": { "name": "Microservice",   "conceptType": "ApplicationComponent" } },
    { "tool": "create-specialization", "params": { "name": "API Gateway",    "conceptType": "ApplicationComponent" } },
    { "tool": "create-specialization", "params": { "name": "Message Broker", "conceptType": "ApplicationComponent" } },
    { "tool": "create-element", "params": { "type": "ApplicationComponent", "name": "Order Service",   "specialization": "Microservice" } },
    { "tool": "create-element", "params": { "type": "ApplicationComponent", "name": "Public API Edge", "specialization": "API Gateway" } },
    { "tool": "create-element", "params": { "type": "ApplicationComponent", "name": "Order Events",    "specialization": "Message Broker" } }
  ]
}
```

Pre-registration is safe to retry across sessions because `create-specialization` is idempotent (see the Tool Pipeline above).

## Multi-Profile Caveat

A concept can technically carry more than one `IProfile` in the underlying EMF model, but the MCP surface treats this as an edge case:

- The `specialization` field on `ElementDto` and `RelationshipDto` exposes only the **primary** (first) specialization.
- The `specialization` param on `create-element` / `update-element` / `create-relationship` / `update-relationship` reads and writes only the primary specialization.
- `delete-specialization` with `force: true` **refuses** to remove a specialization from any concept that has more than one specialization attached. Recovery: clear the extra specializations on those concepts via `update-element` / `update-relationship` (passing `specialization: ""` to clear) first, then retry the force delete.

If you need to model genuinely multi-faceted classification, prefer multiple specializations on different relationships, or use properties — do not stack specializations on a single concept.

## End-to-End Example: Cloud Server Technology Landscape

A complete walkthrough of the specialization tool surface using a small Technology landscape.

```jsonc
// 1. Pre-register the vocabulary (idempotent — safe to retry).
create-specialization { name: "Cloud Server",    conceptType: "Node" }
create-specialization { name: "Database Server", conceptType: "Node" }

// 2. Create elements with the specialization inline (auto-creates if missing).
create-element { type: "Node", name: "web-prod-01", specialization: "Cloud Server" }
create-element { type: "Node", name: "web-prod-02", specialization: "Cloud Server" }
create-element { type: "Node", name: "orders-db",   specialization: "Database Server" }

// 3. Browse the catalog to confirm.
list-specializations
// → [ { name: "Cloud Server", conceptType: "Node", layer: "Technology", usageCount: 2 }, … ]

// 4. Find every Cloud Server in the model.
search-elements { type: "Node", specialization: "Cloud Server" }
// → returns web-prod-01, web-prod-02

// 5. Rename the specialization. Refused if "Compute Node" already exists.
update-specialization { name: "Cloud Server", conceptType: "Node", newName: "Compute Node" }

// 6. Audit before deleting.
get-specialization-usage { name: "Database Server", conceptType: "Node" }
// → { elements: [orders-db], relationships: [] }

// 7a. Try a safe delete first — refused because the audit found a usage.
delete-specialization { name: "Database Server", conceptType: "Node" }
// → refused: 1 element uses this specialization. Pass force: true to detach and delete.

// 7b. Force the delete. Detach + delete happens in one atomic command.
delete-specialization { name: "Database Server", conceptType: "Node", force: true }
// → orders-db's specialization is cleared and the catalog entry is removed.
```

Every step here is undoable individually, and steps composed via `bulk-mutate` are undoable as a single atomic action.

## See Also

- `archimate-layers.md` — per-layer specialization starter sets
- `archimate-relationships.md` — relationship specialization examples
- `archimate-view-patterns.md` — Specialization Hierarchy viewpoint
- `model-exploration-guide.md` — when to call `list-specializations` in the discovery pipeline
