package net.vheerden.archi.mcp.model;

/**
 * Pure-unit-testable decision for the {@code apply-group-spacing-
 * recommendations} convenience tool: given the inputs the tool can compute
 * upstream, decides whether to short-circuit (and which envelope to return)
 * or to call {@code adjustViewSpacing} with the computed inter-group delta.
 *
 * <p>Extracted from {@link ArchiModelAccessorImpl#applyGroupSpacingRecommendations}
 * specifically so the short-circuit guards (no top-level groups / fewer-than-2
 * top-level groups / a corridor between containers this tool does not position /
 * delta=0 / dryRun) can be exercised by JUnit without an OSGi context. The
 * production method calls
 * {@link #decide(int, int, int, int, boolean, boolean, boolean, boolean, String)}
 * once and dispatches on {@link #shouldCallAdjustViewSpacing()}.</p>
 *
 * <p>This pattern mirrors {@link ApplyElementSpacingDecision} verbatim —
 * sibling-symmetric. The only structural differences are the additional
 * {@code interGroupConnectionCount} input (for context-only — the
 * isConnected determination is supplied via {@code isConnected}) and the
 * group-tier-specific short-circuit reasons. Pinned by JUnit
 * {@code ApplyGroupSpacingRecommendationsToolTest}.</p>
 *
 * @param interGroupDelta            the delta the tool would pass to
 *                                   {@code adjustViewSpacing}; 0 on every
 *                                   short-circuit branch
 * @param shouldCallAdjustViewSpacing true on the apply path, false on every
 *                                   short-circuit (no-groups, fewer-than-2-
 *                                   top-level-groups, a-corridor-between-
 *                                   containers-this-tool-does-not-position,
 *                                   delta=0, dryRun)
 * @param noChangeReason             populated on every short-circuit branch
 *                                   except dryRun (dryRun is a deliberate
 *                                   preview, not a "no change recommended"
 *                                   — it has a recommendation but does not
 *                                   apply it); null on dryRun + apply paths
 */
public record ApplyGroupSpacingDecision(
        int interGroupDelta,
        boolean shouldCallAdjustViewSpacing,
        String noChangeReason) {

    /**
     * Computes the decision from the upstream-collected inputs.
     *
     * @param connectionCount             total view connection count
     *                                    (sourced from assess-layout)
     * @param interGroupConnectionCount   count of connections that cross a
     *                                    top-level container boundary — a
     *                                    native view group and an ArchiMate
     *                                    {@code Grouping} element alike;
     *                                    informs
     *                                    no-change-reason wording on the
     *                                    unconnected-view short-circuit; the
     *                                    isConnected determination is
     *                                    supplied separately so callers can
     *                                    encode the boundary semantics
     *                                    (e.g., one-side-grouped pairings)
     *                                    consistently with their counting
     * @param currentSpacingPx            min inter-group spacing across
     *                                    adjacent top-level groups (most-
     *                                    tight pair wins); irrelevant when
     *                                    {@code !hasAtLeast2TopLevelGroups}
     * @param targetSpacingPx             heuristic-or-override target spacing
     * @param dryRun                      preview-without-mutation flag
     * @param hasAtLeast2TopLevelGroups   true when the view has at least 2
     *                                    top-level groups (fewer than 2 means
     *                                    no inter-group corridor exists)
     * @param isConnected                 true when the view has at least one
     *                                    inter-group connection (selects the
     *                                    connected column on the heuristic
     *                                    table; affects this decision via
     *                                    the no-change-reason wording on the
     *                                    delta=0 path)
     * @param hasTargetSpacingOverride    true when the caller provided an
     *                                    explicit {@code targetSpacing}
     *                                    parameter (changes the wording of
     *                                    the delta=0 short-circuit reason)
     * @param containersNotPositionedReason the refusal built by
     *                                    {@code SpacingEntryGuardTermination}
     *                                    when this view's corridor lies
     *                                    between containers this tool's own
     *                                    positioning step does not move; null
     *                                    when the step has a corridor of its
     *                                    own. Passed as the built sentence
     *                                    rather than as a flag because it
     *                                    names the view and counts its
     *                                    containers, and because three tools
     *                                    publishing one refusal must not each
     *                                    hold their own copy of the wording
     * @return decision describing the chosen branch + computed delta
     */
    public static ApplyGroupSpacingDecision decide(
            int connectionCount,
            int interGroupConnectionCount,
            int currentSpacingPx,
            int targetSpacingPx,
            boolean dryRun,
            boolean hasAtLeast2TopLevelGroups,
            boolean isConnected,
            boolean hasTargetSpacingOverride,
            String containersNotPositionedReason) {

        if (!hasAtLeast2TopLevelGroups) {
            return new ApplyGroupSpacingDecision(0, false,
                    "view has fewer than 2 top-level groups; "
                    + "inter-group spacing not adjustable (a view needs at "
                    + "least two top-level groups before there is a between-"
                    + "group corridor to widen)");
        }

        // A structural impossibility, and it outranks the already-met test below: a view whose
        // zones happen to sit at the target would otherwise be told its spacing already meets it,
        // implying a corridor was measured that this tool could have widened. It sits AFTER the
        // fewer-than-two test because on a view holding a single container both sentences are
        // true and that one is the more actionable — there is no corridor at all, whoever
        // positions it.
        if (containersNotPositionedReason != null) {
            return new ApplyGroupSpacingDecision(0, false,
                    containersNotPositionedReason);
        }

        int delta = Math.max(0, targetSpacingPx - currentSpacingPx);
        if (delta == 0) {
            String columnLabel = isConnected ? "connected" : "unconnected";
            String reason = hasTargetSpacingOverride
                    ? "current group spacing " + currentSpacingPx
                            + "px already meets or exceeds explicit target "
                            + targetSpacingPx + "px"
                    : "current group spacing " + currentSpacingPx
                            + "px already meets or exceeds heuristic target "
                            + targetSpacingPx + "px for connection count "
                            + connectionCount + " (" + columnLabel
                            + " column, " + interGroupConnectionCount
                            + " inter-group connection"
                            + (interGroupConnectionCount == 1 ? "" : "s")
                            + ")";
            return new ApplyGroupSpacingDecision(0, false, reason);
        }

        if (dryRun) {
            // Recommendation present, no mutation — noChangeReason intentionally null
            return new ApplyGroupSpacingDecision(delta, false, null);
        }

        return new ApplyGroupSpacingDecision(delta, true, null);
    }
}
