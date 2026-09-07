package net.vheerden.archi.mcp.response.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the adjust-view-spacing tool.
 * Combines spacing inflation results with routing metrics and assessment summary
 * so the LLM gets everything in one response.
 *
 * <p><strong>Density-aware default-resolution transparency (Story
 * RoutingPreconditions.InterElement.DensityAwareDefault):</strong> the last
 * two fields surface whether the tool's default-resolution code path fired
 * for an omitted {@code interElementDelta}. {@code resolvedInterElementDelta}
 * always reports the actual delta applied (caller value or default-resolved
 * value); {@code defaultResolutionReason} is populated when default-resolution
 * fired or when an informational no-fire condition warrants transparency
 * (e.g. zero connections, trigger-but-already-meets-target).</p>
 *
 * <p>{@code groupsAdjusted} and {@code resizedAncestors} describe two different things and must not
 * be read as one. The first counts the groups this tool <em>deliberately</em> spaced, because the
 * caller asked it to. The second names the groups that grew <em>underneath</em> that work: an
 * enclosing group has to keep containing a child the spacing pass pushed past its edge, so the
 * parent-fit cascade widens it. Nobody asked for those, and the client is an agent that cannot see
 * the canvas — an object that changed for a reason the response never mentions is one it will keep
 * planning against at the rectangle it last saw. Hence a landed rectangle per group rather than a
 * second count.</p>
 *
 * <p>{@code resizedElements} is the third of these, and it is the only one that is not about a
 * mechanism: it names every object whose size this call changed, wherever in the view it sits and
 * whichever write range changed it. The call writes a full rectangle to every child it places, so a
 * grid arrangement hands every cell the width of the widest element in it and a nested container
 * the same pass has just re-fitted is written at its new size; it also re-fits each container to
 * its own inflated contents, and for a TOP-LEVEL container that is usually the largest single
 * resize the call makes. Nothing was asked for and none of it used to appear anywhere in the response —
 * {@code elementsRepositioned} counts children placed, moved or not, resized or not, so it cannot
 * distinguish them.</p>
 *
 * <p>Because the meaning is an observation rather than a mechanism, this list is projected from the
 * merged compound the call is about to dispatch rather than accumulated as the passes run. Every
 * rectangle the call writes is in that compound whether or not a pass remembered to record it,
 * which is the property an accumulator does not have: the top-level container's re-fit went
 * unreported for exactly as long as the report was built by remembering.</p>
 *
 * <p>{@code resizedElements} and {@code resizedAncestors} are therefore NOT a partition and may
 * name the same object: a nested group the spacing pass re-fits and the cascade then grows again
 * appears in both. Where they overlap they carry the SAME rectangle — the one the model ends up
 * holding — because the compound's last command for an object is the one the dispatch leaves in
 * place. Reporting an earlier pass's own numbers would put a rectangle the model never holds into a
 * structured geometry field.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdjustViewSpacingResultDto(
        String viewId,
        int groupsAdjusted,
        int elementsRepositioned,
        int connectionsRouted,
        int connectionsFailed,
        int crossingsBefore,
        int crossingsAfter,
        String overallRating,
        Map<String, String> ratingBreakdown,
        int coincidentSegmentCount,
        int nonOrthogonalTerminalCount,
        double averageSpacing,
        List<String> suggestions,
        Integer resolvedInterElementDelta,
        String defaultResolutionReason,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> resizedAncestors,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> resizedElements) {

    /**
     * Constructor matching the prior 15-field shape. Delegates with both lists empty; each is
     * omitted from JSON, so a call that resized nothing serializes byte-identically to before the
     * fields existed.
     */
    public AdjustViewSpacingResultDto(
            String viewId, int groupsAdjusted, int elementsRepositioned,
            int connectionsRouted, int connectionsFailed,
            int crossingsBefore, int crossingsAfter,
            String overallRating, Map<String, String> ratingBreakdown,
            int coincidentSegmentCount, int nonOrthogonalTerminalCount,
            double averageSpacing, List<String> suggestions,
            Integer resolvedInterElementDelta, String defaultResolutionReason) {
        this(viewId, groupsAdjusted, elementsRepositioned, connectionsRouted, connectionsFailed,
                crossingsBefore, crossingsAfter, overallRating, ratingBreakdown,
                coincidentSegmentCount, nonOrthogonalTerminalCount, averageSpacing, suggestions,
                resolvedInterElementDelta, defaultResolutionReason, List.of(), List.of());
    }

    /** Constructor matching the prior 16-field shape, before {@code resizedElements} existed. */
    public AdjustViewSpacingResultDto(
            String viewId, int groupsAdjusted, int elementsRepositioned,
            int connectionsRouted, int connectionsFailed,
            int crossingsBefore, int crossingsAfter,
            String overallRating, Map<String, String> ratingBreakdown,
            int coincidentSegmentCount, int nonOrthogonalTerminalCount,
            double averageSpacing, List<String> suggestions,
            Integer resolvedInterElementDelta, String defaultResolutionReason,
            List<MovedViewObjectDto> resizedAncestors) {
        this(viewId, groupsAdjusted, elementsRepositioned, connectionsRouted, connectionsFailed,
                crossingsBefore, crossingsAfter, overallRating, ratingBreakdown,
                coincidentSegmentCount, nonOrthogonalTerminalCount, averageSpacing, suggestions,
                resolvedInterElementDelta, defaultResolutionReason, resizedAncestors, List.of());
    }

    /** Never null: the canonical constructor normalizes a null list to empty. */
    public AdjustViewSpacingResultDto {
        resizedAncestors = resizedAncestors != null ? resizedAncestors : List.of();
        resizedElements = resizedElements != null ? resizedElements : List.of();
    }
}
