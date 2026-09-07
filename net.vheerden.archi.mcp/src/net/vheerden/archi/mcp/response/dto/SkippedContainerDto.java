package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A populated top-level container {@code arrange-groups} deliberately did not position.
 *
 * <p><b>The host is what was skipped, not necessarily its contents.</b> When the same call arranged
 * the containers drawn inside a host, that host still appears here — it was not itself moved — and
 * its {@code reason} says the containers inside it were, pointing at
 * {@code nestedContainersArranged}. Reading an entry here as "nothing under this changed" is what
 * the two-armed message exists to prevent.
 *
 * <p>{@code arrange-groups} arranges a native view group and an ArchiMate {@code Grouping}. A plain
 * element that happens to hold children — a {@code Node} typing a cloud region or account is the
 * common case on an infrastructure view — is treated as a host rather than a zone and is left where
 * it is. That decision is sound and it is invisible: the object is container-shaped on the canvas,
 * so a caller has no reason to expect it to sit still.</p>
 *
 * <p>Measured 2026-08-14: a view with nine populated top-level containers reported
 * {@code groupsPositioned: 8}. The ninth held an entire account branch and stayed stacked under
 * another container until it was placed by hand. Nothing in the response named it, so the only way
 * to detect the shortfall was to count the view's containers independently and diff — which is
 * asking the caller to re-derive what the tool already knew.</p>
 *
 * <p>Carries the reason rather than only the identity, so the entry reads as a design decision the
 * caller can act on instead of an unexplained omission that looks like a defect.</p>
 *
 * <p><strong>A second tool reports through this record.</strong> {@code resize-elements-to-fit}
 * uses it for an ArchiMate {@code Grouping} the caller named in {@code elementIds} that the pass
 * declined to size — a zone is grown around its children and never sized to its own label, so one
 * holding no element has nothing to size it by. Read the two fields accordingly: {@code elementType}
 * is whatever type made the object ineligible for the tool that reported it, {@code Grouping} here
 * and typically {@code Node} for {@code arrange-groups}, and the container is <em>populated</em>
 * only in the {@code arrange-groups} case. The {@code reason} is the field that says which
 * situation this entry describes, which is why it is prose rather than a code.</p>
 *
 * <p>The identity is the action. A caller that disagrees with the default — it can see the canvas,
 * and this tool cannot — passes the {@code viewObjectId} straight back in {@code groupIds}, and the
 * same container is arranged on the next call. Without that, the entry named the object precisely
 * and left the caller nothing to do with the name except call a different tool.</p>
 *
 * @param viewObjectId the skipped container's view object id — pass it back in arrange-groups'
 *                     groupIds to have it arranged, or to apply-positions / update-view-object to
 *                     place it at coordinates you choose
 * @param name         its display name, falling back to the id when the view cannot name it
 * @param elementType  the ArchiMate type that made it ineligible (e.g. {@code Node}), or null for
 *                     a non-ArchiMate object
 * @param reason       why it was not positioned, in words a caller can act on
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkippedContainerDto(
        String viewObjectId,
        String name,
        String elementType,
        String reason) {

    /** A container this call would have arranged, excluded by the caller's own groupIds. */
    public static final String REASON_NOT_REQUESTED = "not-requested";

    /** A note, image or view reference: it carries no ArchiMate concept, so no lane takes it. */
    public static final String REASON_NOT_AN_ARCHIMATE_ELEMENT = "not-an-archimate-element";

    /** An ArchiMate object whose type the standalone lane never places. */
    public static final String REASON_TYPE_NOT_LANE_ELIGIBLE = "type-not-lane-eligible";

    /** The call's arrangement never offered its children to the standalone lane. */
    public static final String REASON_LANE_NOT_RUN = "lane-not-run";

    /** One container was arranged, so the lane has no inter-container gap to place anything in. */
    public static final String REASON_NO_INTER_CONTAINER_GAP = "no-inter-container-gap";

    /** The lane ran and the element reaches fewer than two of the arranged containers. */
    public static final String REASON_INSUFFICIENT_CONNECTIONS = "insufficient-connections";

    /**
     * Every code an {@code arrange-groups} {@code unhandled} entry can lead with, in the order the
     * reasons are decided.
     *
     * <p>The reasons are composed from these constants and the guard over the served tool
     * description iterates this list, so a code added here and documented nowhere turns the build
     * red. A hard-coded copy of the codes cannot do that: it stays green over exactly the codes it
     * was written with, which is how a published enumeration falls silently behind the one the
     * response actually emits.</p>
     *
     * <p>It lives on this record rather than beside the classification because the classification
     * is package-private in the model layer and the surfaces that must agree with it — the tool
     * description among them — are not. This record is what carries the field to all of them.</p>
     */
    public static final List<String> ARRANGE_GROUPS_UNHANDLED_REASON_CODES = List.of(
            REASON_NOT_REQUESTED,
            REASON_NOT_AN_ARCHIMATE_ELEMENT,
            REASON_TYPE_NOT_LANE_ELIGIBLE,
            REASON_LANE_NOT_RUN,
            REASON_NO_INTER_CONTAINER_GAP,
            REASON_INSUFFICIENT_CONNECTIONS);
}
