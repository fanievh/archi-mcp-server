package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for the result of an add-to-view operation.
 *
 * <p>Contains the created view object and optional list of auto-created
 * connections (when autoConnect=true). The autoConnections list is omitted
 * from JSON when null (NON_NULL annotation). When auto-connect is capped
 * at the maximum, skippedAutoConnections reports the count not created.</p>
 *
 * <p>{@code resizedAncestors} carries the objects the placement grew without being asked to. A
 * container whose icon is anchored in the corner the new child would occupy is grown to clear it,
 * and a container that grows must still fit inside the group above it, so a single add can change
 * the bounds of two objects the caller never named. The client here is an agent that cannot see the
 * canvas: an object that changed size for a reason the response does not mention is an object it
 * will keep planning against at dimensions that no longer exist. Each entry carries the landed
 * rectangle rather than a flag, because what the agent needs is where the object now <em>is</em>.
 * Omitted from JSON when empty, so a placement that grew nothing says nothing rather than saying
 * "none" in a way that reads as measured.</p>
 *
 * <p>Two lists name the connections auto-connect declined to draw, because a count of them is not
 * something an agent can act on. {@code skippedDueToNesting} carries the pairs suppressed because
 * the placement nested the new object inside an endpoint it has a relationship with — drawing that
 * line would leave a box and re-enter the same box, and the nesting already says what the line
 * would have said. {@code skippedByCap} carries the ones the fifty-connection cap excluded, which
 * {@code skippedAutoConnections} counts and never named: an agent told only <em>that</em> eight
 * connections were dropped cannot draw any of them, whereas one told <em>which</em> can place them
 * with {@code add-connection-to-view}. Both are omitted when empty.</p>
 *
 * @param viewObject             the view object that was created
 * @param autoConnections        connections auto-created alongside it, or null
 * @param skippedAutoConnections how many eligible connections the cap excluded, or null. A count
 *                               of {@code skippedByCap} and nothing else — it is kept because it
 *                               was published, and it is no longer the only account of them
 * @param resizedAncestors       the container and any groups above it that this placement grew,
 *                               with the bounds each ended at
 * @param skippedDueToNesting    pairs whose connection was not drawn because this placement put one
 *                               endpoint inside the other. The FIELD carries the sibling
 *                               {@code auto-connect-view}'s name so one concept ships under one
 *                               name; the entry type is deliberately not that tool's, because a
 *                               placement can also name the relationship id and that tool cannot
 * @param skippedByCap           the pairs {@code skippedAutoConnections} counts
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddToViewResultDto(
    ViewObjectDto viewObject,
    List<ViewConnectionDto> autoConnections,
    Integer skippedAutoConnections,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> resizedAncestors,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SkippedConnection> skippedDueToNesting,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SkippedConnection> skippedByCap
) {

    /** Never null: the canonical constructor normalizes null lists to empty. */
    public AddToViewResultDto {
        skippedDueToNesting = skippedDueToNesting != null ? skippedDueToNesting : List.of();
        skippedByCap = skippedByCap != null ? skippedByCap : List.of();
    }

    /**
     * One connection this placement's auto-connect could have drawn and did not, and why.
     *
     * <p>Ids are view-object ids, and {@code relationshipId} is the model relationship — which is
     * preserved either way, so nothing here reports a deletion. Together they are everything
     * {@code add-connection-to-view} needs, so an agent that disagrees with a skip can draw the
     * connection itself without a lookup.</p>
     *
     * @param sourceViewObjectId view-object id of the connection's source endpoint
     * @param targetViewObjectId view-object id of the connection's target endpoint
     * @param relationshipType   ArchiMate class name (e.g. {@code "AssignmentRelationship"})
     * @param relationshipId     id of the model relationship, still present on the model
     * @param reason             {@code "ancestor_descendant_on_view"} — the placement nested one
     *                           endpoint inside the other — or {@code "auto_connect_cap_reached"}
     */
    public static record SkippedConnection(
            String sourceViewObjectId,
            String targetViewObjectId,
            String relationshipType,
            String relationshipId,
            String reason) {}
    /**
     * Convenience constructor without skipped count (no cap hit).
     */
    public AddToViewResultDto(ViewObjectDto viewObject, List<ViewConnectionDto> autoConnections) {
        this(viewObject, autoConnections, null, List.of());
    }

    /**
     * Back-compatible constructor for the shape that predates naming what auto-connect declined to
     * draw. Delegates with two empty lists, both omitted from JSON, so every existing construction
     * site serializes byte-identically to before the fields existed.
     */
    public AddToViewResultDto(ViewObjectDto viewObject, List<ViewConnectionDto> autoConnections,
            Integer skippedAutoConnections, List<MovedViewObjectDto> resizedAncestors) {
        this(viewObject, autoConnections, skippedAutoConnections, resizedAncestors,
                List.of(), List.of());
    }

    /**
     * Back-compatible constructor preserving the previous arity, for callers that grew no ancestor.
     */
    public AddToViewResultDto(ViewObjectDto viewObject, List<ViewConnectionDto> autoConnections,
            Integer skippedAutoConnections) {
        this(viewObject, autoConnections, skippedAutoConnections, List.of());
    }
}
