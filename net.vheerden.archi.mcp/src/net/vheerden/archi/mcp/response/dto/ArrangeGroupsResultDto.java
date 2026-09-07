package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The outcome of an {@code arrange-groups} call.
 *
 * <p>{@code groupsPositioned} counts what moved; {@code positionedContainers} says where each one
 * landed. The count alone was the whole report, and a count is an index into information the caller
 * does not have: an agent that cannot see the canvas learned that four containers moved and could
 * not learn where any of them went. That is how a run which silently skipped fifteen containers
 * still read as a success.</p>
 *
 * <p>{@code positionedContainers} is read back from the model after the arrangement is applied, so
 * it reports the rectangle the model holds rather than the one the layout asked for. It is empty
 * when nothing has been applied yet — a queued or proposed call has no effective geometry to
 * report, and inventing one there would re-create the divergence the deferral exists to declare.</p>
 *
 * <p>{@code topLevelObjects} is the denominator the other counters are measured against, and it is
 * the field that makes any of them falsifiable. Three counters with nothing to sum against cannot
 * disagree with each other: "8" was never wrong on a view of nine containers, because no reported
 * value contradicted it. A shortfall was undetectable from the response by construction — the
 * caller's only recourse was to re-derive this tool's own traversal and diff it.</p>
 *
 * <p>{@code unhandled} closes the population between the buckets. Every direct child of the view
 * that none of the three other buckets claimed appears here with a reason, a note and a view
 * reference included: filtering a class of object out before counting is precisely the move that
 * left seven elements stacked on one another while the container arithmetic read clean. The four
 * buckets partition the view's direct children exactly, so
 * {@code groupsPositioned + standaloneElementsPlaced + skippedContainers + unhandled} equals
 * {@code topLevelObjects} on every call.</p>
 *
 * <p>{@code nestedContainersArranged} sits <b>outside</b> that identity and is counted by none of
 * the four. It reports containers this call positioned inside a host rather than on the canvas,
 * whose coordinates are relative to that host and which are not among the view's direct children
 * at all. Adding them to {@code groupsPositioned} would make the equality above arithmetically
 * false on exactly the views the field exists for.</p>
 *
 * <p>Both are decided from the view as read rather than from the write, so unlike
 * {@code positionedContainers} they are honest — and populated — on a queued or proposed call.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArrangeGroupsResultDto(
    String viewId,
    int groupsPositioned,
    int layoutWidth,
    int layoutHeight,
    Integer columnsUsed,
    String arrangement,
    Integer resolvedSpacing,
    String defaultResolutionReason,
    int standaloneElementsPlaced,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> positionedContainers,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SkippedContainerDto> skippedContainers,
    int topLevelObjects,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SkippedContainerDto> unhandled,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<NestedContainerDto> nestedContainersArranged
) {
    /**
     * Normalizes null lists to empty so the fields are simply absent rather than null in JSON.
     *
     * <p>{@code topLevelObjects} is a primitive and therefore always serialized, including when it
     * is 0. That is deliberate: a count that disappears beside a description list reads as an
     * all-clear rather than as the absence of a measurement, and this field exists precisely so a
     * caller can tell those two apart. {@code unhandled} follows {@code skippedContainers} and is
     * omitted when empty — an empty list there is not a claim, it is the ordinary case.</p>
     */
    public ArrangeGroupsResultDto {
        positionedContainers = positionedContainers != null ? positionedContainers : List.of();
        skippedContainers = skippedContainers != null ? skippedContainers : List.of();
        unhandled = unhandled != null ? unhandled : List.of();
        nestedContainersArranged =
                nestedContainersArranged != null ? nestedContainersArranged : List.of();
    }

    /**
     * Backwards-compatible constructor for callers that pre-date {@code positionedContainers}.
     * Reports no effective geometry, which is the honest answer before anything has been applied.
     */
    public ArrangeGroupsResultDto(
            String viewId,
            int groupsPositioned,
            int layoutWidth,
            int layoutHeight,
            Integer columnsUsed,
            String arrangement,
            Integer resolvedSpacing,
            String defaultResolutionReason,
            int standaloneElementsPlaced) {
        this(viewId, groupsPositioned, layoutWidth, layoutHeight, columnsUsed,
                arrangement, resolvedSpacing, defaultResolutionReason,
                standaloneElementsPlaced, List.of(), List.of(), 0, List.of(), List.of());
    }

    /**
     * The full report: every bucket, plus the denominator they are measured against.
     *
     * <p>The effective geometry is deliberately absent here and added afterwards by
     * {@link #withEffectiveGeometry}, because the two halves are established at different times —
     * everything in this constructor is read from the view before anything is applied.</p>
     */
    public ArrangeGroupsResultDto(
            String viewId,
            int groupsPositioned,
            int layoutWidth,
            int layoutHeight,
            Integer columnsUsed,
            String arrangement,
            Integer resolvedSpacing,
            String defaultResolutionReason,
            int standaloneElementsPlaced,
            List<SkippedContainerDto> skippedContainers,
            int topLevelObjects,
            List<SkippedContainerDto> unhandled) {
        this(viewId, groupsPositioned, layoutWidth, layoutHeight, columnsUsed,
                arrangement, resolvedSpacing, defaultResolutionReason,
                standaloneElementsPlaced, List.of(), skippedContainers, topLevelObjects,
                unhandled, List.of());
    }

    /**
     * Backwards-compatible constructor.
     * Pre-existing callers that pre-date the {@code standaloneElementsPlaced} field default it to 0.
     */
    public ArrangeGroupsResultDto(
            String viewId,
            int groupsPositioned,
            int layoutWidth,
            int layoutHeight,
            Integer columnsUsed,
            String arrangement,
            Integer resolvedSpacing,
            String defaultResolutionReason) {
        this(viewId, groupsPositioned, layoutWidth, layoutHeight, columnsUsed,
                arrangement, resolvedSpacing, defaultResolutionReason, 0, List.of(), List.of(),
                0, List.of(), List.of());
    }

    /**
     * The same result, naming the containers this call arranged inside a host.
     *
     * <p>Like {@link #withEffectiveGeometry}, applied only on the path that actually wrote: the
     * geometry here is read back from the model, and a queued or proposed call has moved nothing
     * to read.</p>
     */
    public ArrangeGroupsResultDto withNestedContainers(List<NestedContainerDto> nested) {
        return new ArrangeGroupsResultDto(viewId, groupsPositioned, layoutWidth, layoutHeight,
                columnsUsed, arrangement, resolvedSpacing, defaultResolutionReason,
                standaloneElementsPlaced, positionedContainers, skippedContainers, topLevelObjects,
                unhandled, nested);
    }

    /** The same result, carrying the geometry read back from the model after the write. */
    public ArrangeGroupsResultDto withEffectiveGeometry(List<MovedViewObjectDto> positioned) {
        return new ArrangeGroupsResultDto(viewId, groupsPositioned, layoutWidth, layoutHeight,
                columnsUsed, arrangement, resolvedSpacing, defaultResolutionReason,
                standaloneElementsPlaced, positioned, skippedContainers, topLevelObjects,
                unhandled, nestedContainersArranged);
    }

    /**
     * The same result, naming the populated top-level containers this call deliberately left
     * standing. Separate from {@link #withEffectiveGeometry} because the two are established at
     * different times: what was skipped is known before anything is applied, and on a queued or
     * proposed call it is the only half that can honestly be reported.
     */
    public ArrangeGroupsResultDto withSkippedContainers(List<SkippedContainerDto> skipped) {
        return new ArrangeGroupsResultDto(viewId, groupsPositioned, layoutWidth, layoutHeight,
                columnsUsed, arrangement, resolvedSpacing, defaultResolutionReason,
                standaloneElementsPlaced, positionedContainers, skipped, topLevelObjects,
                unhandled, nestedContainersArranged);
    }
}
