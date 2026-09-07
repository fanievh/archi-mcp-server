package net.vheerden.archi.mcp.model;

/**
 * Pure-unit-testable decision for the {@code apply-spacing-recommendations}
 * composed convenience tool: given the inputs the tool can compute upstream,
 * dispatches on the {@code scope} parameter (both / element / group), clamps
 * each per-arm proposed delta to the inflation-knee guard
 * ({@value #ELEMENT_KNEE_LIMIT_PX}px / {@value #GROUP_KNEE_LIMIT_PX}px from
 * the view's current spacing baselines), decides whether to short-circuit
 * (and which envelope to return) or to call {@code adjustViewSpacing} once
 * with the scope-appropriate non-null arms.
 *
 * <p>Sibling-symmetric with {@link ApplyElementSpacingDecision} +
 * {@link ApplyGroupSpacingDecision} — same architectural-separation rule
 * (extracted from {@link ArchiModelAccessorImpl#applySpacingRecommendations}
 * so the short-circuit / clamp / scope-dispatch branches can be exercised by
 * JUnit without an OSGi context).</p>
 *
 * <p><strong>Inflation-knee guard.</strong> The clamp values
 * ({@value #ELEMENT_KNEE_LIMIT_PX}px element / {@value #GROUP_KNEE_LIMIT_PX}px
 * inter-group) come from the spacing diagnostic
 * ("Post-empirical diagnostic", 2026-05-06):
 * cumulative +80 elem / +100 group / +60 pad drove the diagnosed view's coincSeg
 * from 10 → 1; beyond that cumulative-from-current point, passThroughs /
 * nonOrthogonalTerminals / xings-per-connection regressed. The composed
 * tool's distinctive value-prop is enforcing the knee per-call; the
 * existing sibling tools do NOT clamp.</p>
 *
 * <p><strong>Per-call clamp, not session-tracked.</strong> The clamp is
 * computed against the current call's detected spacing baseline. The tool
 * does NOT persist a per-view inflation ledger across calls (documented as a
 * non-goal in v1). Successive calls on
 * the same view each re-detect current spacing, compute proposed delta
 * against the heuristic, and clamp per-call.</p>
 *
 * <p>Pinned by {@code ApplySpacingRecommendationsToolTest}.</p>
 *
 * @param interElementDelta            the (clamped) element delta the tool
 *                                     would pass to {@code adjustViewSpacing}
 *                                     (0 when scope excludes element OR on
 *                                     element-arm short-circuit)
 * @param interGroupDelta              the (clamped) group delta the tool
 *                                     would pass to {@code adjustViewSpacing}
 *                                     (0 when scope excludes group OR on
 *                                     group-arm short-circuit)
 * @param proposedElementDelta         pre-clamp element delta (for response
 *                                     DTO transparency); equal to
 *                                     {@code interElementDelta} when the
 *                                     element-arm clamp did not fire
 * @param proposedGroupDelta           pre-clamp group delta (for response
 *                                     DTO transparency)
 * @param elementKneeClampApplied      true when
 *                                     {@code interElementDelta <
 *                                     proposedElementDelta} (clamp fired);
 *                                     surfaced in response DTO
 * @param groupKneeClampApplied        true when
 *                                     {@code interGroupDelta <
 *                                     proposedGroupDelta} (clamp fired);
 *                                     surfaced in response DTO
 * @param shouldCallAdjustViewSpacing  true on the apply path, false on every
 *                                     short-circuit: both-zero structural-
 *                                     impossibility, both-zero corridor-
 *                                     between-containers-neither-arm-
 *                                     positions, both-zero heuristic-
 *                                     already-met, dryRun
 * @param noChangeReason               populated on every short-circuit branch
 *                                     except dryRun (dryRun is a deliberate
 *                                     preview — it has a recommendation but
 *                                     does not apply it); null on dryRun +
 *                                     apply paths
 */
public record ApplySpacingDecision(
        int interElementDelta,
        int interGroupDelta,
        int proposedElementDelta,
        int proposedGroupDelta,
        boolean elementKneeClampApplied,
        boolean groupKneeClampApplied,
        boolean shouldCallAdjustViewSpacing,
        String noChangeReason) {

    /** Maximum element-spacing delta this tool will apply in a single call
     *  (from the spacing diagnostic 2026-05-06). Any future
     *  revision MUST edit {@code ApplySpacingRecommendationsToolTest}. */
    public static final int ELEMENT_KNEE_LIMIT_PX = 80;

    /** Maximum inter-group-spacing delta this tool will apply in a single
     *  call (from the spacing diagnostic 2026-05-06). Any
     *  future revision MUST edit {@code ApplySpacingRecommendationsToolTest}. */
    public static final int GROUP_KNEE_LIMIT_PX = 100;

    /** Valid scope values. */
    public static final String SCOPE_BOTH = "both";
    public static final String SCOPE_ELEMENT = "element";
    public static final String SCOPE_GROUP = "group";

    /**
     * Computes the decision from the upstream-collected inputs. Throws
     * {@link IllegalArgumentException} if {@code scope} is not one of
     * {@link #SCOPE_BOTH}, {@link #SCOPE_ELEMENT}, {@link #SCOPE_GROUP} —
     * the handler layer translates this to the
     * {@code error.code = "invalid_argument"} MCP envelope.
     *
     * <p><strong>Short-circuit precedence.</strong> Within each in-scope arm: (1) structural
     * impossibility {@literal >} (2) heuristic already met. Then the
     * combined-arm rule: (3) call {@code adjustViewSpacing} only when AT
     * LEAST one arm has a non-zero clamped delta AND {@code !dryRun}.
     * (4) dryRun preserves the recommendation envelope without mutating.</p>
     *
     * @param scope                         one of "both" / "element" / "group"
     * @param connectionCount               total view connection count
     * @param interGroupConnectionCount     count of connections crossing a
     *                                      top-level container boundary — a
     *                                      native view group and an ArchiMate
     *                                      {@code Grouping} element alike
     * @param currentElementSpacingPx       min per-group element spacing (most-
     *                                      tight wins); irrelevant when
     *                                      {@code !hasGroupWithMultipleChildren}
     * @param currentGroupSpacingPx         min inter-group spacing (most-tight
     *                                      pair wins); irrelevant when
     *                                      {@code !hasAtLeast2TopLevelGroups}
     * @param elementTargetSpacingPx        heuristic-or-override target element
     *                                      spacing
     * @param groupTargetSpacingPx          heuristic-or-override target inter-
     *                                      group spacing
     * @param dryRun                        preview-without-mutation flag
     * @param hasNonEmptyGroups             true when at least one top-level
     *                                      group has at least one child
     * @param hasGroupWithMultipleChildren  true when at least one top-level
     *                                      group has at least 2 non-note
     *                                      children
     * @param hasAtLeast2TopLevelGroups     true when the view has at least 2
     *                                      top-level groups
     * @param isConnected                   true when
     *                                      {@code interGroupConnectionCount >
     *                                      0}; affects the group-arm no-change-
     *                                      reason wording
     * @param hasElementTargetOverride      true when caller provided explicit
     *                                      {@code elementTargetSpacing}
     * @param hasGroupTargetOverride        true when caller provided explicit
     *                                      {@code groupTargetSpacing}
     * @param elementContainersNotPositionedReason the element arm's refusal,
     *                                      built by
     *                                      {@code SpacingEntryGuardTermination},
     *                                      when this tool's element step has
     *                                      none of the view's own containers
     *                                      to space inside; null otherwise
     * @param groupContainersNotPositionedReason the group arm's refusal, built
     *                                      the same way, when the corridor
     *                                      lies between containers this tool's
     *                                      group step does not move; null
     *                                      otherwise. Per-arm because the two
     *                                      steps need different numbers of the
     *                                      view's own containers before they
     *                                      have work — one to space inside,
     *                                      two to widen between
     * @return decision describing the chosen branches + clamped deltas
     */
    public static ApplySpacingDecision decide(
            String scope,
            int connectionCount,
            int interGroupConnectionCount,
            int currentElementSpacingPx,
            int currentGroupSpacingPx,
            int elementTargetSpacingPx,
            int groupTargetSpacingPx,
            boolean dryRun,
            boolean hasNonEmptyGroups,
            boolean hasGroupWithMultipleChildren,
            boolean hasAtLeast2TopLevelGroups,
            boolean isConnected,
            boolean hasElementTargetOverride,
            boolean hasGroupTargetOverride,
            String elementContainersNotPositionedReason,
            String groupContainersNotPositionedReason) {

        if (scope == null
                || (!SCOPE_BOTH.equals(scope)
                        && !SCOPE_ELEMENT.equals(scope)
                        && !SCOPE_GROUP.equals(scope))) {
            throw new IllegalArgumentException(
                    "scope must be one of 'both', 'element', 'group'; got: "
                    + scope);
        }

        boolean elementInScope =
                SCOPE_BOTH.equals(scope) || SCOPE_ELEMENT.equals(scope);
        boolean groupInScope =
                SCOPE_BOTH.equals(scope) || SCOPE_GROUP.equals(scope);

        // ---- Element arm ----
        int proposedElementDelta = 0;
        int clampedElementDelta = 0;
        boolean elementKneeClamp = false;
        String elementReason = null;

        if (elementInScope) {
            // First in the arm, for the reason ApplyElementSpacingDecision inverts its own order:
            // both branches below are computed from the view's own populated children and are
            // therefore already true on a view whose zones all sit inside a host, where they say
            // something false about it.
            if (elementContainersNotPositionedReason != null) {
                elementReason = "element: " + elementContainersNotPositionedReason;
            } else if (!hasNonEmptyGroups) {
                elementReason = "element: view has no groups; spacing not "
                        + "applicable on flat views (use update-view-object "
                        + "for surgical edits or arrange-groups to introduce "
                        + "groups first)";
            } else if (!hasGroupWithMultipleChildren) {
                elementReason = "element: no groups with 2+ elements detected; "
                        + "inter-element spacing not adjustable (a group needs "
                        + "at least two children before there is a "
                        + "between-element gap to inflate)";
            } else if (connectionCount == 0) {
                elementReason = "element: view has no connections; spacing "
                        + "recommendation not applicable";
            } else {
                proposedElementDelta = Math.max(0,
                        elementTargetSpacingPx - currentElementSpacingPx);
                clampedElementDelta = Math.min(proposedElementDelta,
                        ELEMENT_KNEE_LIMIT_PX);
                elementKneeClamp = clampedElementDelta < proposedElementDelta;
                if (clampedElementDelta == 0) {
                    String src = hasElementTargetOverride
                            ? "explicit target "
                            : "heuristic target ";
                    elementReason = "element: current spacing "
                            + currentElementSpacingPx
                            + "px already meets or exceeds " + src
                            + elementTargetSpacingPx + "px";
                }
            }
        }

        // ---- Group arm ----
        int proposedGroupDelta = 0;
        int clampedGroupDelta = 0;
        boolean groupKneeClamp = false;
        String groupReason = null;

        if (groupInScope) {
            if (!hasAtLeast2TopLevelGroups) {
                groupReason = "group: view has fewer than 2 top-level groups; "
                        + "inter-group spacing not adjustable (a view needs "
                        + "at least two top-level groups before there is a "
                        + "between-group corridor to widen)";
            } else if (groupContainersNotPositionedReason != null) {
                // After the fewer-than-two test and before the delta, mirroring
                // ApplyGroupSpacingDecision exactly — the two must not order one view's
                // structural refusals differently.
                groupReason = "group: " + groupContainersNotPositionedReason;
            } else {
                proposedGroupDelta = Math.max(0,
                        groupTargetSpacingPx - currentGroupSpacingPx);
                clampedGroupDelta = Math.min(proposedGroupDelta,
                        GROUP_KNEE_LIMIT_PX);
                groupKneeClamp = clampedGroupDelta < proposedGroupDelta;
                if (clampedGroupDelta == 0) {
                    String src = hasGroupTargetOverride
                            ? "explicit target "
                            : "heuristic target ";
                    String columnLabel = isConnected
                            ? "connected" : "unconnected";
                    groupReason = "group: current inter-group spacing "
                            + currentGroupSpacingPx
                            + "px already meets or exceeds " + src
                            + groupTargetSpacingPx + "px (" + columnLabel
                            + " column, " + interGroupConnectionCount
                            + " inter-group connection"
                            + (interGroupConnectionCount == 1 ? "" : "s")
                            + ")";
                }
            }
        }

        // ---- Short-circuit when BOTH arms produce zero change ----
        boolean anyArmProducesChange =
                (clampedElementDelta > 0) || (clampedGroupDelta > 0);

        if (!anyArmProducesChange) {
            String reason = composeNoChangeReason(scope,
                    elementReason, groupReason);
            return new ApplySpacingDecision(0, 0,
                    proposedElementDelta, proposedGroupDelta,
                    elementKneeClamp, groupKneeClamp,
                    false, reason);
        }

        // ---- dryRun: recommendation present, no mutation ----
        if (dryRun) {
            return new ApplySpacingDecision(
                    clampedElementDelta, clampedGroupDelta,
                    proposedElementDelta, proposedGroupDelta,
                    elementKneeClamp, groupKneeClamp,
                    false, null);
        }

        // ---- Apply path: at least one arm has a non-zero clamped delta ----
        return new ApplySpacingDecision(
                clampedElementDelta, clampedGroupDelta,
                proposedElementDelta, proposedGroupDelta,
                elementKneeClamp, groupKneeClamp,
                true, null);
    }

    private static String composeNoChangeReason(
            String scope, String elementReason, String groupReason) {
        if (SCOPE_ELEMENT.equals(scope)) {
            return elementReason;
        }
        if (SCOPE_GROUP.equals(scope)) {
            return groupReason;
        }
        // scope == "both": combine non-null reasons
        if (elementReason != null && groupReason != null) {
            return elementReason + "; " + groupReason;
        }
        return (elementReason != null) ? elementReason : groupReason;
    }
}
