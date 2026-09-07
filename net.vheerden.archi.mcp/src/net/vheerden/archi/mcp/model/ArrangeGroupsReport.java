package net.vheerden.archi.mcp.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.SkippedContainerDto;

/**
 * Assembles the {@code arrange-groups} response so that every direct child of the view is
 * accounted for exactly once.
 *
 * <p>Direct child is the whole population here, and deliberately so. A container the same call
 * arranged inside a host is not one of the view's children and is reported separately, outside
 * these four buckets: folding it in would make a published equality arithmetically false without
 * saying so, on exactly the views that field exists for.
 *
 * <p>The tool reports four buckets — the containers it arranged, the standalone elements the lane
 * placed, the populated containers it deliberately left standing, and everything else. Their
 * predicates were written at three different times and none of them was ever measured against the
 * view's own child list, so an object could satisfy two of them or none. Both failures are silent
 * from the caller's side: the counters have nothing to sum against, so no reported value can
 * contradict another.
 *
 * <p>Assembling them in one place fixes the order they are decided in. The arranged set is taken
 * first, then the lane's placements, then the skipped containers minus anything the lane moved, and
 * whatever the view still holds is the residual. That makes the four sets a partition of
 * {@code view.getChildren()} by construction rather than by three separate predicates happening to
 * agree, and it is why {@link TopLevelGroupTargets#describeUnhandled} subtracts rather than tests.
 *
 * <p>Everything here is read from the view before the arrangement is applied, so the whole report
 * is honest on a queued or proposed call — unlike the effective geometry, which is added afterwards
 * and only on the applied path. Keep it that way: moving any of this behind the approval gate would
 * turn a fact about the view into a claim about a write that has not happened.
 */
final class ArrangeGroupsReport {

    private ArrangeGroupsReport() {}

    /**
     * The response for one {@code arrange-groups} call, minus the effective geometry.
     *
     * @param view the view being arranged, and the source of the denominator
     * @param targetGroups the containers this call arranged, after any {@code groupIds} filter
     * @param placements the standalone elements the lane positioned; empty when it did not run
     * @param reportedArrangement the arrangement the caller asked for, normalized
     * @param resolvedArrangement the axis it resolved to, which for {@code topology} is decided by
     *     {@code direction} and {@code columns} and is what says whether the lane could run at all
     * @param hostsWithArrangedZones the ids of hosts whose nested zones this call arranged. Those
     *     hosts are still left standing themselves, so they still belong in the skipped bucket —
     *     but telling one it was "left where it is" and saying nothing about the zones just moved
     *     inside it would leave the caller re-deriving the change from a later read.
     * @param hostsWithDeclinedZones the ids of hosts whose zones this call measured and could not
     *     fit, which is a third outcome and not the absence of the second
     */
    static ArrangeGroupsResultDto describe(
            IDiagramModelContainer view,
            String viewId,
            List<IDiagramModelObject> targetGroups,
            List<ArrangeGroupsStandaloneLane.QualifierPlacement> placements,
            int layoutWidth,
            int layoutHeight,
            Integer columnsUsed,
            String reportedArrangement,
            String resolvedArrangement,
            Integer resolvedSpacing,
            String defaultResolutionReason,
            Set<String> hostsWithArrangedZones,
            Set<String> hostsWithDeclinedZones) {
        Set<String> claimed = new LinkedHashSet<>();
        for (IDiagramModelObject target : targetGroups) {
            claimed.add(target.getId());
        }
        Set<String> movedByLane = new LinkedHashSet<>();
        for (ArrangeGroupsStandaloneLane.QualifierPlacement placement : placements) {
            movedByLane.add(placement.element().getId());
        }
        claimed.addAll(movedByLane);

        // Every id this call positioned, arranged and lane-placed alike — not only the lane's.
        // A container the caller named in groupIds is arranged despite not being a default target,
        // so it satisfies the skipped predicate too and would otherwise be reported as left
        // standing by the same call that moved it.
        List<SkippedContainerDto> skipped =
                TopLevelGroupTargets.describeSkipped(view, claimed,
                        hostsWithArrangedZones, hostsWithDeclinedZones);
        for (SkippedContainerDto entry : skipped) {
            claimed.add(entry.viewObjectId());
        }

        return new ArrangeGroupsResultDto(viewId, targetGroups.size(), layoutWidth, layoutHeight,
                columnsUsed, reportedArrangement, resolvedSpacing, defaultResolutionReason,
                placements.size(), skipped, view.getChildren().size(),
                TopLevelGroupTargets.describeUnhandled(view, claimed,
                        laneRan(reportedArrangement, resolvedArrangement),
                        targetGroups.size()));
    }

    /**
     * Whether the standalone lane was offered this call's children at all.
     *
     * <p>Asks the same pair of conditions the classification site does rather than a flag passed
     * alongside it: the lane runs only for a topology request, and only once that request has
     * resolved to a single axis. A topology call carrying {@code columns} becomes a grid, where
     * "between two containers" has no single meaning, and the lane is skipped — an element left
     * unplaced there fell through for that reason and not for want of connections.
     */
    private static boolean laneRan(String reportedArrangement, String resolvedArrangement) {
        return "topology".equals(reportedArrangement)
                && ("row".equals(resolvedArrangement) || "column".equals(resolvedArrangement));
    }
}
