package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of a single operation within a bulk-mutate response.
 *
 * @param index        the 0-based position of this operation in the bulk array
 * @param tool         the tool that was executed
 * @param action       "created" or "updated"
 * @param entityId     the ID of the created/updated entity
 * @param entityType   the ArchiMate type of the entity
 * @param entityName   the name of the entity, as of the end of the call rather than as of the
 *                     moment this operation was prepared. On a call that was applied it is re-read
 *                     from the model once every operation has run, so an operation that renames, or
 *                     that is followed by another rename of the same entity, reports the final
 *                     name. <strong>A deletion is the exception, and it is not re-read:</strong>
 *                     its subject no longer exists to be resolved, so the name is the one the
 *                     deleting command recorded at the instant it destroyed it — which is the same
 *                     value whenever nothing renamed the entity first, and the only true one when
 *                     something did. A deletion that destroyed nothing recorded nothing — one that
 *                     declined, and one whose subject an earlier operation in the same call had
 *                     already deleted — so it falls back to the ordinary rules above. Where nothing has been
 *                     written at all — a batched or awaiting-approval call — it is the name as
 *                     prepared, because there is no applied state to read and nothing has run to
 *                     record one. Null and omitted for the operations that name nothing, such as a
 *                     placed connection or note, or a removal.
 * @param appliedCount for fan-out operations (e.g. set-view-label-expression), the number of
 *                     objects affected; null and omitted for single-entity operations
 * @param skippedCount for fan-out operations, the number of objects left untouched; null and
 *                     omitted for single-entity operations
 * @param effectiveBounds for operations on a view object, the geometry the model holds once the
 *                     whole bulk call has been applied; null and omitted otherwise
 * @param deletion     for deletion operations, the same cascade report the standalone deletion
 *                     tool returns; null and omitted otherwise
 * @param resizedAncestors objects this operation re-sized that it was not asked to touch — a
 *                     container grown to clear the corner its icon renders into, and any group
 *                     grown to keep containing it. Empty and omitted otherwise. Carried here rather
 *                     than left to {@code effectiveBounds} because that field describes the
 *                     operation's own entity, and these are objects no operation named.
 * @param movedObjects objects this operation displaced that it was not asked to touch. Empty and
 *                     omitted otherwise, for the same reason as {@code resizedAncestors}.
 * @param effectiveConnection for operations on a view connection, the state the connection holds
 *                     once the whole bulk call has been applied — styling, anchors, bendpoints and
 *                     endpoints, the same report the single-tool caller is given. Null and omitted
 *                     otherwise. It is a re-read of the model after dispatch, not an echo of the
 *                     operation's parameters: its anchors are absolute canvas centres derived from
 *                     the endpoints' live geometry, so an operation later in the same call can move
 *                     an endpoint and change them without ever naming the connection. Absent, for
 *                     the same reason, whenever nothing has been written — a batched or
 *                     awaiting-approval call — because there is no state to read there.
 * @param effectiveRelationship for operations on an ArchiMate relationship, the state it holds once
 *                     the whole bulk call has been applied — including the semantic attributes
 *                     (accessType, associationDirected, influenceStrength) a bulk caller can set
 *                     and was previously told nothing about. Composed from the same readers the
 *                     single-tool caller's response is, so a subtype that cannot hold an attribute
 *                     omits it here exactly as it does there. Null and omitted otherwise, and
 *                     absent whenever nothing has been written, as {@code effectiveConnection} is.
 * @param effectiveElement for operations on an ArchiMate element, the same, including the
 *                     documentation a bulk caller can rewrite and was previously told nothing
 *                     about. Null and omitted otherwise.
 * @param parentViewObjectId for operations on a view object, the container it sits in once the
 *                     whole bulk call has been applied, spelt the way the standalone response and
 *                     the read path both spell it. Null and omitted for an object on the view
 *                     itself, and absent whenever nothing has been written, as
 *                     {@code effectiveBounds} is. It is what makes {@code effectiveBounds}
 *                     readable: a nested object's x/y are relative to its immediate parent's
 *                     top-left corner, so a rectangle reported without its origin cannot be
 *                     placed. Read after dispatch rather than taken from the operation's request,
 *                     because a request names a parent as a reference still to be resolved — one
 *                     that resolved to a container the caller did not intend is exactly the case
 *                     this field exists to expose, and echoing the request there would repeat the
 *                     caller's own mistake back as confirmation.
 */
public record BulkOperationResult(
    int index,
    String tool,
    String action,
    String entityId,
    String entityType,
    String entityName,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer appliedCount,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer skippedCount,
    @JsonInclude(JsonInclude.Include.NON_NULL) EffectiveBounds effectiveBounds,
    @JsonInclude(JsonInclude.Include.NON_NULL) DeleteResultDto deletion,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> resizedAncestors,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> movedObjects,
    @JsonInclude(JsonInclude.Include.NON_NULL) ViewConnectionDto effectiveConnection,
    @JsonInclude(JsonInclude.Include.NON_NULL) RelationshipDto effectiveRelationship,
    @JsonInclude(JsonInclude.Include.NON_NULL) ElementDto effectiveElement,
    @JsonInclude(JsonInclude.Include.NON_NULL) String parentViewObjectId,
    /**
     * Placement-time disclosures the operation's own entity carried.
     *
     * <p>Projected rather than dropped because the same prepare runs on the single-tool path and
     * here, so the measurement exists either way — publishing it on one entry point and not its
     * sibling would make the disclosure's absence uninformative, which is worse than not having
     * it at all. Empty on every operation that carries none.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<StructuredWarningDto> structuredWarnings,
    /**
     * The horizontal label alignment the object holds once the whole call has been applied, or
     * null when it sits at Archi's CENTRE default and the key is therefore omitted.
     *
     * <p>Reported because a placement can carry an alignment the caller never asked for: a group,
     * a note and a {@code Grouping} are stamped with their Archi type default when they are
     * created, so the model holds {@code "left"} on an operation whose request said nothing about
     * styling. That is the one styling value on this record the server chose rather than echoed,
     * which is why it is here and the rest of the styling family is not — the others are either
     * named by the request or never moved off the EMF default, and reporting a value back to the
     * caller who supplied it adds nothing.</p>
     *
     * <p>Read after dispatch rather than when the operation was prepared, for the reason
     * {@code effectiveBounds} is: a later operation in the same call can re-align the same object,
     * so a value captured at prepare time would describe neither the request nor the result.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL) String textAlignment
) {

    /**
     * Compact constructor: null disclosures normalise to an empty list.
     *
     * <p>So a caller reading the field never has to distinguish "no warning" from "built by a
     * constructor that predates the field" — every copier below forwards the component, and this
     * makes the one thing they cannot forward, a null handed in from outside, harmless.</p>
     */
    public BulkOperationResult {
        structuredWarnings = structuredWarnings != null ? structuredWarnings : List.of();
    }

    /** Back-compatible constructor for callers built before per-operation disclosures. */
    public BulkOperationResult(int index, String tool, String action,
            String entityId, String entityType, String entityName,
            Integer appliedCount, Integer skippedCount, EffectiveBounds effectiveBounds,
            DeleteResultDto deletion, List<MovedViewObjectDto> resizedAncestors,
            List<MovedViewObjectDto> movedObjects, ViewConnectionDto effectiveConnection,
            RelationshipDto effectiveRelationship, ElementDto effectiveElement,
            String parentViewObjectId) {
        this(index, tool, action, entityId, entityType, entityName, appliedCount, skippedCount,
                effectiveBounds, deletion, resizedAncestors, movedObjects, effectiveConnection,
                effectiveRelationship, effectiveElement, parentViewObjectId, null, null);
    }

    /**
     * Back-compatible constructor preserving the arity that predates concept reporting.
     */
    public BulkOperationResult(int index, String tool, String action,
            String entityId, String entityType, String entityName,
            Integer appliedCount, Integer skippedCount, EffectiveBounds effectiveBounds,
            DeleteResultDto deletion, List<MovedViewObjectDto> resizedAncestors,
            List<MovedViewObjectDto> movedObjects, ViewConnectionDto effectiveConnection) {
        this(index, tool, action, entityId, entityType, entityName, appliedCount, skippedCount,
                effectiveBounds, deletion, resizedAncestors, movedObjects, effectiveConnection,
                null, null, null);
    }

    /**
     * Back-compatible constructor preserving the arity that predates connection reporting, so every
     * existing construction site keeps compiling and keeps producing the wire bytes it produced
     * before.
     */
    public BulkOperationResult(int index, String tool, String action,
            String entityId, String entityType, String entityName,
            Integer appliedCount, Integer skippedCount, EffectiveBounds effectiveBounds,
            DeleteResultDto deletion, List<MovedViewObjectDto> resizedAncestors,
            List<MovedViewObjectDto> movedObjects) {
        this(index, tool, action, entityId, entityType, entityName, appliedCount, skippedCount,
                effectiveBounds, deletion, resizedAncestors, movedObjects, null, null, null, null);
    }

    /** Back-compatible constructor for the shape without displaced-object reporting. */
    public BulkOperationResult(int index, String tool, String action,
            String entityId, String entityType, String entityName,
            Integer appliedCount, Integer skippedCount, EffectiveBounds effectiveBounds,
            DeleteResultDto deletion, List<MovedViewObjectDto> resizedAncestors) {
        this(index, tool, action, entityId, entityType, entityName, appliedCount, skippedCount,
                effectiveBounds, deletion, resizedAncestors, List.of());
    }
    /**
     * Back-compatible constructor for single-entity operations (no fan-out counts).
     */
    public BulkOperationResult(int index, String tool, String action,
            String entityId, String entityType, String entityName) {
        this(index, tool, action, entityId, entityType, entityName, null, null, null, null, List.of(), List.of());
    }

    /**
     * Back-compatible constructor for results built before the model has been written, when no
     * effective geometry exists to report yet.
     */
    public BulkOperationResult(int index, String tool, String action,
            String entityId, String entityType, String entityName,
            Integer appliedCount, Integer skippedCount) {
        this(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, null, null, List.of());
    }

    /**
     * Back-compatible constructor for non-deletion operations.
     */
    public BulkOperationResult(int index, String tool, String action,
            String entityId, String entityType, String entityName,
            Integer appliedCount, Integer skippedCount, EffectiveBounds effectiveBounds) {
        this(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, effectiveBounds, null, List.of());
    }

    /**
     * Back-compatible constructor preserving the previous arity, for operations that re-sized
     * nothing they were not asked to touch.
     */
    public BulkOperationResult(int index, String tool, String action,
            String entityId, String entityType, String entityName,
            Integer appliedCount, Integer skippedCount, EffectiveBounds effectiveBounds,
            DeleteResultDto deletion) {
        this(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, effectiveBounds, deletion, List.of());
    }

    /**
     * Returns a copy of this result carrying the given post-execution geometry.
     *
     * <p>Every component is named explicitly rather than left to a back-compatible constructor.
     * Those constructors default the optional components away, and these copiers run <em>after</em>
     * the components are populated, so delegating to one silently discarded them — the displaced-
     * object list was computed, attached, and then thrown away on every dispatched operation. The
     * same hazard applies to every component added here since, which is why every copier names
     * every component.</p>
     */
    public BulkOperationResult withEffectiveBounds(EffectiveBounds bounds) {
        return new BulkOperationResult(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, bounds, deletion, resizedAncestors, movedObjects,
                effectiveConnection, effectiveRelationship, effectiveElement, parentViewObjectId,
                structuredWarnings, textAlignment);
    }

    /** Returns a copy of this result carrying the given re-sized ancestors. */
    public BulkOperationResult withResizedAncestors(List<MovedViewObjectDto> ancestors) {
        return new BulkOperationResult(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, effectiveBounds, deletion, ancestors, movedObjects,
                effectiveConnection, effectiveRelationship, effectiveElement, parentViewObjectId,
                structuredWarnings, textAlignment);
    }

    /** As {@link #withResizedAncestors}, for the objects an operation displaced rather than grew. */
    public BulkOperationResult withMovedObjects(List<MovedViewObjectDto> moved) {
        return new BulkOperationResult(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, effectiveBounds, deletion, resizedAncestors, moved,
                effectiveConnection, effectiveRelationship, effectiveElement, parentViewObjectId,
                structuredWarnings, textAlignment);
    }

    /**
     * Returns a copy of this result carrying the connection's post-execution state.
     *
     * <p>Applied by the after-dispatch pass, so a result reaches this copier only when something
     * has actually been written. The retraction pass that follows reaches this component through
     * {@link #withResizedAncestors} and {@link #withMovedObjects} rather than through this copier,
     * which is why those two must forward it and why a test asserts that they do.</p>
     */
    public BulkOperationResult withEffectiveConnection(ViewConnectionDto connection) {
        return new BulkOperationResult(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, effectiveBounds, deletion, resizedAncestors,
                movedObjects, connection, effectiveRelationship, effectiveElement, parentViewObjectId,
                structuredWarnings, textAlignment);
    }

    /**
     * Returns a copy of this result carrying the name the entity holds after the whole call ran.
     *
     * <p>Applied by the same after-dispatch pass as the connection report, and exposed the same
     * way: the retraction that follows reaches the result through {@link #withResizedAncestors}
     * and {@link #withMovedObjects}, so either of those dropping the name would silently put the
     * pre-write value back for every operation that declined.</p>
     */
    public BulkOperationResult withEntityName(String name) {
        return new BulkOperationResult(index, tool, action, entityId, entityType, name,
                appliedCount, skippedCount, effectiveBounds, deletion, resizedAncestors,
                movedObjects, effectiveConnection, effectiveRelationship, effectiveElement,
                parentViewObjectId,
                structuredWarnings, textAlignment);
    }

    /**
     * Returns a copy of this result carrying the given cascade report.
     *
     * <p>Applied after dispatch to correct the name a deletion was prepared with, which is the one
     * value in the whole response that a re-read of the model cannot reach: the entity is gone, so
     * the report is the only surviving description of it. Named alongside
     * {@link #withEntityName(String)} rather than folded into it, because the two carry the same
     * name in different places and a deletion has to correct both — {@code entityName} in the
     * envelope and {@code deletion.name} one field over — or it puts the same wrong value on the
     * wire twice.</p>
     *
     * <p>Every component is named, for the reason {@link #withEffectiveBounds} records.</p>
     */
    public BulkOperationResult withDeletion(DeleteResultDto replacement) {
        return new BulkOperationResult(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, effectiveBounds, replacement, resizedAncestors,
                movedObjects, effectiveConnection, effectiveRelationship, effectiveElement,
                parentViewObjectId,
                structuredWarnings, textAlignment);
    }

    /**
     * Returns a copy of this result carrying the relationship's post-execution state.
     *
     * <p>Exposed exactly as the connection report is, and forwarded by every other copier for the
     * same reason: the retraction pass reaches the result through {@link #withResizedAncestors} and
     * {@link #withMovedObjects}, so either of those dropping it would take it off the wire for
     * every operation that declined.</p>
     */
    public BulkOperationResult withEffectiveRelationship(RelationshipDto relationship) {
        return new BulkOperationResult(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, effectiveBounds, deletion, resizedAncestors,
                movedObjects, effectiveConnection, relationship, effectiveElement,
                parentViewObjectId,
                structuredWarnings, textAlignment);
    }

    /**
     * Returns a copy of this result naming the container its view object sits in.
     *
     * <p>Applied by the same after-dispatch pass as the geometry it qualifies, and forwarded by
     * every other copier for the reason {@link #withEffectiveConnection} gives: the retraction that
     * follows reaches the result through {@link #withResizedAncestors} and
     * {@link #withMovedObjects}, so either of those dropping it would take it off the wire for
     * every operation that declined.</p>
     */
    public BulkOperationResult withParentViewObjectId(String parent) {
        return new BulkOperationResult(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, effectiveBounds, deletion, resizedAncestors,
                movedObjects, effectiveConnection, effectiveRelationship, effectiveElement,
                parent,
                structuredWarnings, textAlignment);
    }

    /**
     * Returns a copy of this result carrying the label alignment its view object now holds.
     *
     * <p>Applied by the same after-dispatch pass as the geometry beside it, and forwarded by every
     * other copier for the reason {@link #withEffectiveConnection} gives: the retraction that
     * follows reaches the result through {@link #withResizedAncestors} and
     * {@link #withMovedObjects}, so either of those dropping it would take it off the wire for
     * every operation that declined.</p>
     */
    public BulkOperationResult withTextAlignment(String alignment) {
        return new BulkOperationResult(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, effectiveBounds, deletion, resizedAncestors,
                movedObjects, effectiveConnection, effectiveRelationship, effectiveElement,
                parentViewObjectId,
                structuredWarnings, alignment);
    }

    /** As {@link #withEffectiveRelationship}, for an operation whose entity is an element. */
    public BulkOperationResult withEffectiveElement(ElementDto element) {
        return new BulkOperationResult(index, tool, action, entityId, entityType, entityName,
                appliedCount, skippedCount, effectiveBounds, deletion, resizedAncestors,
                movedObjects, effectiveConnection, effectiveRelationship, element,
                parentViewObjectId,
                structuredWarnings, textAlignment);
    }

    /**
     * The geometry a view object holds after the entire bulk call has been applied.
     *
     * <p>Distinct from the values the operation requested. Operations are prepared in sequence
     * before any of them executes, so an operation that sets a group's size cannot know that a
     * later operation in the same call will move a child past it and force the group to grow. The
     * client is an agent that cannot see the canvas, so without this it is told the operation
     * succeeded and left to assume the size it asked for is the size it got.</p>
     */
    public record EffectiveBounds(int x, int y, int width, int height) {}
}
