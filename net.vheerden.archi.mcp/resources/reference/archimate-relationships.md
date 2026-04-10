# ArchiMate Relationship Types

## Overview

ArchiMate defines relationship types organised into four categories: Structural, Dependency, Dynamic, and Other. Each relationship connects a source element to a target element.

## Structural Relationships

Structural relationships model the static construction of elements.

### CompositionRelationship
- **Meaning:** The target is an integral part of the source (whole-part, strong ownership)
- **Direction:** Source contains target
- **Example:** An ApplicationComponent is composed of sub-components
- **Valid between:** Active structure elements of the same type/layer; passive structure elements of the same type/layer
- **Key rule:** Removing the source implies removing the target

### AggregationRelationship
- **Meaning:** The target is grouped within the source (collection, weak ownership)
- **Direction:** Source groups target
- **Example:** A BusinessRole aggregates multiple BusinessActors
- **Valid between:** Active structure elements; passive structure elements
- **Key rule:** Target can exist independently of the source

### AssignmentRelationship
- **Meaning:** The source performs or is responsible for the target behaviour
- **Direction:** Source is assigned to target
- **Example:** An ApplicationComponent is assigned to an ApplicationFunction
- **Valid between:** Active structure elements to behaviour elements; nodes to artifacts
- **Key rule:** Links "who/what" to "does"

### RealizationRelationship
- **Meaning:** The source realizes or implements the target
- **Direction:** Source realizes target
- **Example:** An ApplicationComponent realizes an ApplicationService
- **Valid between:** Behaviour/structure to service; structure to passive structure; many cross-layer combinations
- **Key rule:** Links "implementation" to "specification"

## Dependency Relationships

Dependency relationships model how elements depend on each other.

### ServingRelationship
- **Meaning:** The source provides functionality to the target
- **Direction:** Source serves target
- **Example:** An ApplicationService serves a BusinessProcess
- **Valid between:** Any active structure or behaviour element to any active structure or behaviour element
- **Key rule:** Most common cross-layer relationship; indicates service provision

### AccessRelationship
- **Meaning:** The source accesses or manipulates the target data
- **Direction:** Source accesses target
- **Example:** An ApplicationFunction accesses a DataObject
- **Valid between:** Behaviour elements to passive structure elements
- **Key rule:** Can indicate read, write, or read-write access

### InfluenceRelationship
- **Meaning:** The source influences the target (positive, negative, or neutral)
- **Direction:** Source influences target
- **Example:** A Requirement influences a Goal
- **Valid between:** Any motivation element to any motivation element; any core element to motivation element
- **Key rule:** Optionally annotated with strength (+, ++, -, --)

## Dynamic Relationships

Dynamic relationships model behaviour and interaction over time.

### TriggeringRelationship
- **Meaning:** The source triggers the start of the target
- **Direction:** Source triggers target
- **Example:** A BusinessEvent triggers a BusinessProcess
- **Valid between:** Behaviour elements to behaviour elements; events to behaviour
- **Key rule:** Indicates temporal causality

### FlowRelationship
- **Meaning:** Something (data, material, information) flows from source to target
- **Direction:** Source sends to target
- **Example:** A BusinessProcess flows data to another BusinessProcess
- **Valid between:** Behaviour elements to behaviour elements
- **Key rule:** Optionally labeled with what flows

## Other Relationships

### SpecializationRelationship
- **Meaning:** The source is a specialization (subtype) of the target
- **Direction:** Source specializes target
- **Example:** An "Online Order Process" specializes a generic "Order Process"
- **Valid between:** Elements of the same type or related types
- **Key rule:** Inheritance/subtype relationship

### AssociationRelationship
- **Meaning:** A generic, unclassified relationship between elements
- **Direction:** Bidirectional or unspecified
- **Example:** A Stakeholder is associated with a Driver
- **Valid between:** Any element to any element
- **Key rule:** Use when no more specific relationship type applies; weakest semantic meaning

## Relationship Specializations

Any relationship type can carry a **specialization** name to differentiate semantically meaningful subtypes (e.g. distinguishing a "Data Flow" from an "Event Flow" without inventing new relationship types). The inline `specialization` param on `create-relationship` and `update-relationship` auto-creates the specialization if it does not yet exist.

Use `list-specializations` to browse the catalog, `create-specialization` to pre-register a vocabulary, and `get-specialization-usage` to audit before renaming or deleting. See `archimate-specializations.md` for the full reference.

### FlowRelationship
Data Flow, Event Flow, Material Flow, Financial Flow, Message Flow

### AccessRelationship
Read Access, Write Access, Read-Write Access

### ServingRelationship
API Serving, UI Serving, Batch Serving

### TriggeringRelationship
Synchronous Trigger, Asynchronous Trigger

### AssociationRelationship
Owns, Depends On, Operated By (use only when no more specific relationship type fits)

## Junction Elements

Junctions are not relationships but connectors used to combine or split relationships.

| Junction Type | Purpose |
|--------------|---------|
| AndJunction | All incoming relationships must be satisfied for outgoing to activate |
| OrJunction | Any incoming relationship is sufficient for outgoing to activate |

## Common Mistakes

These errors occur frequently when LLMs create ArchiMate models. Check this section before creating relationships.

| Mistake | Why It's Wrong | Correct Relationship |
|---------|---------------|---------------------|
| `CompositionRelationship` between `ApplicationComponent` → `ApplicationFunction` | Composition requires same-type elements (e.g., component→sub-component). Functions are **behaviour**, not structural parts. | `AssignmentRelationship` — assigns the component (who/what) to the function (does) |
| `CompositionRelationship` between `Node` → `SystemSoftware` | Nodes don't structurally contain software in ArchiMate's type system. | `AssignmentRelationship` — the node is assigned to run the software |
| `CompositionRelationship` between `BusinessActor` → `BusinessRole` | Actors don't structurally compose roles; they perform them. | `AssignmentRelationship` — the actor is assigned to the role |
| `ServingRelationship` where `FlowRelationship` is needed | Serving = "provides capability to." Flow = "something moves from A to B." | Use `FlowRelationship` when data/messages/events move between elements. Use `ServingRelationship` when one element provides a service consumed by another. |

**Rule of thumb:** If you're thinking "A contains B" but A and B are different element types (e.g., component vs function), use `AssignmentRelationship` instead of `CompositionRelationship`. Composition is for same-type structural decomposition (component → sub-component).

## Relationship Usage Guide

### For Dependency Analysis
- Focus on: **ServingRelationship**, **RealizationRelationship**, **AssignmentRelationship**
- These reveal how elements depend on and provide for each other
- Use `includeTypes` filter to isolate these types during traversal

### For Data Flow Analysis
- Focus on: **FlowRelationship**, **AccessRelationship**
- These reveal how information moves through the architecture
- Use `includeTypes: ["FlowRelationship", "AccessRelationship"]`

### For Impact Analysis
- Focus on: **ServingRelationship** (incoming direction) — what depends on this element?
- Follow `direction: "incoming"` with `traverse: true` to find all upstream dependents

### For Structure Analysis
- Focus on: **CompositionRelationship**, **AggregationRelationship**
- These reveal hierarchical structure and component decomposition

### Filtering Tips
- Use `excludeTypes: ["AssociationRelationship"]` to remove weak/generic links from analysis
- Use `filterLayer` to see only cross-layer dependencies
- Use `includeTypes` for focused analysis (whitelist approach is more precise than excludeTypes)
