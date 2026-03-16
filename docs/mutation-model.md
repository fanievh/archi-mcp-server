# Mutation Model

This document describes how the ArchiMate MCP Server handles model mutations, including the PreparedMutation pattern, CommandStack integration, operational modes, and the approval workflow.

## Table of Contents

- [Mutation Flow Overview](#mutation-flow-overview)
- [PreparedMutation Pattern](#preparedmutation-pattern)
- [MutationDispatcher](#mutationdispatcher)
- [Operational Modes](#operational-modes)
- [Undo and Redo](#undo-and-redo)
- [Approval Workflow](#approval-workflow)
- [Batch Mode](#batch-mode)
- [Bulk Mutate](#bulk-mutate)
- [Error Handling](#error-handling)

## Mutation Flow Overview

All model mutations follow a strict path from handler through CommandStack to the EMF model.

```mermaid
sequenceDiagram
    participant H as Handler
    participant A as ArchiModelAccessor
    participant PM as PreparedMutation
    participant D as MutationDispatcher
    participant SWT as SWT Thread
    participant CS as CommandStack

    H->>A: createElement(request)
    A->>A: Validate parameters
    A->>A: Build GEF Command
    A->>PM: Create PreparedMutation<ElementDto>
    PM-->>H: Return PreparedMutation

    alt GUI-Attached Mode
        H->>D: dispatchImmediate(command)
        D->>SWT: Display.syncExec()
        SWT->>CS: CommandStack.execute(command)
        CS-->>SWT: Done
        SWT-->>D: Result via AtomicReference
        D-->>H: Success
    else Batch Mode
        H->>D: Queue PreparedMutation
        D-->>H: Batch info (queued count)
    else Approval Mode
        H->>D: Store as proposal
        D-->>H: Proposal ID
    end
```

**Key invariant:** All mutations go through GEF `CommandStack.execute()` on the SWT UI thread. Direct EMF modification corrupts the model and breaks undo/redo.

## PreparedMutation Pattern

`PreparedMutation<T>` is an immutable record that encapsulates the complete state of a mutation before dispatch. It enables two-phase execution: prepare (validate) first, execute second.

```java
record PreparedMutation<T>(
    Command command,      // GEF Command ready for CommandStack
    T entity,             // DTO representation of the result
    String entityId,      // Unique identifier of created/updated entity
    Object rawObject      // Raw EMF object (for bulk back-references)
)
```

### Two-Phase Execution

- **Phase 1 (Preparation):** The handler calls `ArchiModelAccessor`, which validates parameters, creates the EMF object and GEF Command, and returns a `PreparedMutation<T>` — but does not execute.
- **Phase 2 (Dispatch):** The handler checks the operational mode and either dispatches immediately, queues for batch, or stores as a proposal.

### Why Two Phases

Bulk operations pre-validate **all** mutations before executing **any**. If any operation fails validation, the entire bulk operation is rejected (all-or-nothing). This prevents partial model corruption from mid-batch failures.

### Generic Type Parameter

The type parameter `<T>` constrains to the appropriate DTO type:

- `PreparedMutation<ElementDto>` for element creation
- `PreparedMutation<RelationshipDto>` for relationship creation
- `PreparedMutation<ViewDto>` for view creation
- `PreparedMutation<MutationResultDto>` for updates and deletions

**Source:** `model/PreparedMutation.java`

## MutationDispatcher

The `MutationDispatcher` is the single point where Jetty threads cross to the SWT UI thread.

### Thread Crossing

```java
dispatchImmediate(Command command) {
    Display.syncExec(() -> {
        CommandStack.execute(command);
    });
    // Result passed back via AtomicReference
}
```

### Version Tracking

After each dispatch, `ModelVersionTracker` increments the model version. This invalidates session caches and enables the `_meta.modelChanged` flag in responses.

### Per-Session State

`MutationDispatcher` maintains per-session operational mode and batch state via `ConcurrentHashMap<String, MutationContext>`.

**Source:** `model/MutationDispatcher.java`

## Operational Modes

```mermaid
stateDiagram-v2
    [*] --> GUI_ATTACHED: Default
    GUI_ATTACHED --> BATCH: begin-batch
    GUI_ATTACHED --> APPROVAL: set-approval-mode(true)
    BATCH --> GUI_ATTACHED: end-batch
    APPROVAL --> GUI_ATTACHED: set-approval-mode(false)
```

| Mode | Behavior | Undo Granularity |
|------|----------|------------------|
| **GUI-ATTACHED** | Mutations execute immediately, UI updates in real-time | Each mutation is a separate undo unit |
| **BATCH** | Mutations are queued; committed atomically on `end-batch` | Entire batch is a single undo unit |
| **APPROVAL** | Mutations become proposals; executed only on explicit approval | Each approved mutation is a separate undo unit |

## Undo and Redo

The `CommandStackHandler` exposes Archi's native GEF CommandStack as MCP tools.

### undo

- **Parameters:** `steps` (integer, default 1, minimum 1)
- Pops N commands from the undo stack
- Returns list of undone command labels
- Standard sequential undo (most recent first)

### redo

- **Parameters:** `steps` (integer, default 1, minimum 1)
- Pushes N commands back from the redo stack
- Redo stack is cleared on any new mutation post-undo

### Experimental Workflow

Undo/redo enables speculative layout workflows:

```text
1. auto-layout-and-route (apply layout)
2. assess-layout (check quality)
3. If unsatisfied: undo (revert to previous state)
4. Try different parameters and repeat
```

**Source:** `handlers/CommandStackHandler.java`

## Approval Workflow

The approval workflow provides human-in-the-loop control for experimental or high-risk mutations.

### set-approval-mode

- **Parameters:** `enabled` (boolean, required)
- When enabled: all mutations become proposals (queued, not executed)
- When disabled: mutations apply immediately
- State is per-session

### list-pending-approvals

- Returns all pending proposals for the current session
- Each proposal includes: proposalId, tool name, description, parameters

### decide-mutation

- **Parameters:**
  - `proposalId` (string): `"p-1"`, `"p-2"`, etc., or `"all"` for batch decision
  - `decision` (string): `"approve"` or `"reject"`
  - `reason` (optional string): explanation
- **Single approval:** execute the mutation immediately
- **Single rejection:** discard the mutation
- **Approve all:** process in order, stop on first stale proposal
- **Reject all:** discard all pending
- **Stale proposals:** if the model changed since proposal creation, the proposal cannot be approved (prevents applying outdated mutations to a changed model)

### Workflow Example

```text
1. set-approval-mode(enabled=true)
2. create-element(...)          → returns proposal p-1
3. create-relationship(...)     → returns proposal p-2
4. list-pending-approvals       → shows p-1, p-2
5. decide-mutation(p-1, approve) → element created
6. decide-mutation(p-2, reject)  → relationship discarded
```

**Source:** `handlers/ApprovalHandler.java`

## Batch Mode

Batch mode groups multiple mutations into a single atomic, undoable operation.

### begin-batch

- **Parameters:** `description` (optional string)
- Transitions session from GUI-attached to BATCH mode
- Subsequent mutations are queued, not executed
- Error if batch already active

### end-batch

- **Parameters:** `rollback` (optional boolean, default false)
- **Commit** (`rollback=false`): execute all queued mutations as a single `NonNotifyingCompoundCommand` — one undo unit
- **Rollback** (`rollback=true`): discard all queued mutations, model unchanged

### get-batch-status

- Returns current mode (GUI_ATTACHED or BATCH) and queued count

**Source:** `handlers/MutationHandler.java`

## Bulk Mutate

The `bulk-mutate` tool executes multiple mutations in a single request.

### Parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `operations` | Yes | Array of mutation objects |
| `description` | No | Undo history label |
| `continueOnError` | No | `false` = all-or-nothing (default), `true` = partial failure |

### Supported Operations

22 tools are supported in bulk: create, update, view placement, folder, and deletion tools. Query tools, undo/redo, approval tools, and session tools are not supported.

### Back-Reference Syntax

Operations can reference results from earlier operations using `$N.id`:

```json
{
  "operations": [
    {"tool": "create-element", "args": {"type": "ApplicationComponent", "name": "Service A"}},
    {"tool": "create-element", "args": {"type": "ApplicationComponent", "name": "Service B"}},
    {"tool": "create-relationship", "args": {
      "type": "ServingRelationship",
      "sourceId": "$0.id",
      "targetId": "$1.id"
    }}
  ]
}
```

### Failure Semantics

**All-or-nothing** (`continueOnError=false`, default):
- Pre-validate all operations before executing any
- First failure rejects the entire bulk operation
- No model changes if any operation fails

**Partial failure** (`continueOnError=true`):
- Execute all valid operations, report failures
- Failed operations invalidate dependent back-references (cascading failures)
- Response includes both `succeeded` and `failed` arrays

### Maximum Operations

150 per `bulk-mutate` call.

### Integration with Other Modes

- **Approval mode:** wraps entire bulk result in a proposal
- **Batch mode:** queues the compound operation
- **GUI-attached:** executes immediately as a single undo unit

**Source:** `handlers/MutationHandler.java`

## Error Handling

### Error Response Structure

```json
{
  "error": {
    "code": "RELATIONSHIP_NOT_ALLOWED",
    "message": "ServingRelationship is not valid between BusinessActor and ApplicationComponent",
    "details": "Archi's validation rules do not permit this relationship type",
    "suggestedCorrection": "Use AccessRelationship instead",
    "archiMateReference": null
  }
}
```

### Common Error Codes

| Code | Meaning |
|------|---------|
| `ELEMENT_NOT_FOUND` | Element/relationship/view not found by ID |
| `RELATIONSHIP_NOT_ALLOWED` | Violates ArchiMate relationship validation |
| `MUTATION_FAILED` | Command execution failed |
| `BATCH_NOT_ACTIVE` | Tried to end batch when not in batch mode |
| `BATCH_ALREADY_ACTIVE` | Tried to begin batch when already in batch |
| `APPROVAL_NOT_ACTIVE` | Tried to decide when not in approval mode |
| `PROPOSAL_STALE` | Model changed since proposal creation |
| `BULK_VALIDATION_FAILED` | Pre-validation failed for bulk operation |
| `INVALID_PARAMETER` | Parameter validation failure |
| `MODEL_NOT_LOADED` | No ArchiMate model open |

### Validation Sync Principle

Relationship validation delegates to Archi's own `ArchimateModelUtils.isValidRelationship()`. The MCP server is never stricter nor more forgiving than Archi itself. If Archi allows it, the server allows it. If Archi rejects it, the server rejects it.

---

**See also:** [MCP Integration](mcp-integration.md) | [Architecture Overview](architecture.md) | [Extension Guide](extension-guide.md)
