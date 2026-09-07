package net.vheerden.archi.mcp.model;

/**
 * Pure-unit-testable decision for the density-aware default-resolution code
 * path inside {@code arrangeGroups}
 * (RoutingPreconditions.InterGroup.DensityAwareDefault).
 *
 * <p>When {@code spacing} is omitted (parameter is {@code null}) AND the view
 * has at least 2 top-level groups AND the view has at least one inter-group
 * connection, the tool derives a heuristic-driven default spacing from
 * {@link GroupSpacingHeuristic#targetSpacingForConnectionCount(int, boolean, boolean)}
 * rather than using the static {@code DEFAULT_ARRANGE_GROUPS_SPACING = 40}.
 * This record encapsulates the decision so the JUnit pin can exercise the
 * trigger / no-trigger / boundary / fewer-than-2-groups / zero-connections
 * branches without an OSGi context.</p>
 *
 * <p>Mirrors {@link AdjustViewSpacingDefaultResolutionDecision} pattern (the
 * inter-element sibling's decision record) — both share the
 * {@code caller-provided-short-circuit + structural-guard +
 * trigger-evaluation + heuristic-call} core. The only structural difference
 * is {@code resolvedSpacing} (absolute, in pixels — {@code arrangeGroups}
 * positions groups from scratch) vs the sibling's {@code resolvedDelta}
 * (relative, in pixels — {@code adjustViewSpacing} inflates from the
 * existing layout). The heuristic-cross-class consistency check is the
 * multi-class tripwire that protects the shared single-source-of-truth.</p>
 *
 * <p><strong>Trigger model (Model B — advisory-literal,
 * pinned by JUnit):</strong> the trigger fires when
 * {@code isConnected == true} (the view has at least one connection crossing
 * a top-level group boundary). All connected grouped views with omitted
 * {@code spacing} get the heuristic default; unconnected views keep the
 * static 40 default unchanged.</p>
 *
 * <p>Revising the trigger model requires a coordinated edit across THREE
 * artefacts: (1) this record, (2) the JUnit test
 * {@code ArrangeGroupsDefaultResolutionTest}, (3) the {@code arrange-groups}
 * tool description.</p>
 *
 * @param fired           true when the default-resolution fired (the
 *                        omitted-spacing path resolved to a heuristic-driven
 *                        value). False on every other branch: caller
 *                        provided an explicit value, fewer than 2 top-level
 *                        groups, zero connections, or the view is not
 *                        connected (no inter-group connection).
 * @param resolvedSpacing the spacing the caller's omitted parameter resolves
 *                        to (the static {@code DEFAULT_ARRANGE_GROUPS_SPACING}
 *                        when no trigger fires; the heuristic-computed
 *                        spacing when the trigger fires; the caller's
 *                        explicit value passed through unchanged when caller
 *                        provided non-null).
 * @param reason          populated when default-resolution fired OR when an
 *                        informational no-fire condition warrants
 *                        transparency (fewer-than-2-groups, no-connections,
 *                        unconnected-view). Null on caller-provided path.
 * @param triggerCondition which structural condition tripped the gate
 *                        (NONE on caller-provided OR no-fire informational
 *                        branches; IS_CONNECTED on Model B fire branch).
 * @param triggerValue    the metric's measured value at the time the gate
 *                        evaluated (under Model B: the inter-group
 *                        connection count; 0 on no-fire / caller-provided).
 */
public record ArrangeGroupsDefaultResolutionDecision(
        boolean fired,
        int resolvedSpacing,
        String reason,
        TriggerCondition triggerCondition,
        int triggerValue) {

    /** Default static spacing applied when no trigger fires. Mirrors the
     *  accessor-side {@code DEFAULT_ARRANGE_GROUPS_SPACING} constant; kept
     *  in sync via JUnit. */
    public static final int DEFAULT_ARRANGE_GROUPS_SPACING = 40;

    /**
     * Trigger condition enum — which structural condition tripped the
     * default-resolution gate.
     */
    public enum TriggerCondition {
        /** No condition tripped the gate (caller-provided value, OR
         *  informational no-fire branch like fewer-than-2-groups). */
        NONE,
        /** Model B: {@code isConnected == true} — the view has at least
         *  one inter-group connection. */
        IS_CONNECTED
    }

    /**
     * Computes the decision from upstream-collected inputs. Pure-unit; no
     * EMF / OSGi dependencies.
     *
     * <p>Branch order (first match wins):</p>
     * <ol>
     *   <li>Caller provided a non-null spacing (including 0) → no-fire,
     *       return caller's value with reason=null.</li>
     *   <li>Fewer than 2 top-level groups → no-fire with informational
     *       reason (no inter-group corridor exists).</li>
     *   <li>Zero connections on view → no-fire with informational reason
     *       (the heuristic is connection-count-driven; isConnected forced
     *       to false).</li>
     *   <li>Not connected (no inter-group connection) → no-fire with
     *       informational reason (Model B: trigger requires
     *       {@code isConnected == true}).</li>
     *   <li>Otherwise → fire: heuristic computation
     *       {@code GroupSpacingHeuristic.targetSpacingForConnectionCount(
     *       connectionCount, isConnected=true)}.</li>
     * </ol>
     *
     * @param callerProvidedSpacing      the caller's spacing parameter
     *                                   (null = omitted = default-resolution
     *                                   candidate; non-null = caller decided,
     *                                   including 0)
     * @param connectionCount            total visible connections on the view
     *                                   (sourced from
     *                                   {@code AssessLayoutResultDto.connectionCount()})
     * @param interGroupConnectionCount  count of connections that cross a
     *                                   top-level container boundary — a
     *                                   native view group and an ArchiMate
     *                                   {@code Grouping} element alike;
     *                                   informs the trigger value + reason
     *                                   wording
     * @param isConnected                true when at least one connection
     *                                   crosses a top-level container
     *                                   boundary
     *                                   (Model B trigger; selects the
     *                                   connected column on the heuristic
     *                                   table)
     * @param hasAtLeast2TopLevelGroups  true when the view has at least 2
     *                                   top-level groups (fewer than 2 means
     *                                   no inter-group corridor exists)
     * @param hasLargeHubs               true when the view has at least one
     *                                   element at more than 6 connections;
     *                                   forwarded to
     *                                   {@link GroupSpacingHeuristic} to
     *                                   select the hub-aware connected tier
     *                                   (Row C of the 2026-05-06 rating-
     *                                   signal investigation).
     *                                   Caller-provided input — the record
     *                                   stays pure-unit and does NOT scan
     *                                   view contents.
     * @return decision describing the chosen branch + computed spacing
     */
    public static ArrangeGroupsDefaultResolutionDecision decide(
            Integer callerProvidedSpacing,
            int connectionCount,
            int interGroupConnectionCount,
            boolean isConnected,
            boolean hasAtLeast2TopLevelGroups,
            boolean hasLargeHubs) {

        // 1. caller provided (including 0): no default resolution
        if (callerProvidedSpacing != null) {
            return new ArrangeGroupsDefaultResolutionDecision(
                    false, callerProvidedSpacing, null,
                    TriggerCondition.NONE, 0);
        }

        // 2. fewer than 2 top-level groups: no inter-group corridor
        if (!hasAtLeast2TopLevelGroups) {
            return new ArrangeGroupsDefaultResolutionDecision(
                    false, DEFAULT_ARRANGE_GROUPS_SPACING,
                    "spacing omitted; view has fewer than 2 top-level "
                    + "groups; inter-group spacing default unchanged",
                    TriggerCondition.NONE, 0);
        }

        // 3. zero connections: heuristic is connection-count-driven; surface
        //    informational reason for transparency.
        if (connectionCount == 0) {
            return new ArrangeGroupsDefaultResolutionDecision(
                    false, DEFAULT_ARRANGE_GROUPS_SPACING,
                    "spacing omitted; view has no connections; "
                    + "density-aware default not applicable",
                    TriggerCondition.NONE, 0);
        }

        // 4. not connected (Model B trigger): no inter-group connection
        if (!isConnected) {
            return new ArrangeGroupsDefaultResolutionDecision(
                    false, DEFAULT_ARRANGE_GROUPS_SPACING,
                    "spacing omitted; no connection crosses between the arranged containers — a "
                    + "connection counts when it joins objects inside two different containers, "
                    + "and one drawn directly between two container boxes is not counted at all "
                    + "because a container is not inside itself; density-aware default not "
                    + "applicable",
                    TriggerCondition.NONE, 0);
        }

        // 5. fire: heuristic computation (Model B — connected column;
        //    Row C — hub-aware variant when hasLargeHubs=true)
        int target = GroupSpacingHeuristic
                .targetSpacingForConnectionCount(
                        connectionCount, true, hasLargeHubs);
        String reason = String.format(
                "spacing omitted; isConnected=true with %d inter-group "
                + "connection%s; heuristic for connectionCount=%d => "
                + "target=%d px (connected column)",
                interGroupConnectionCount,
                interGroupConnectionCount == 1 ? "" : "s",
                connectionCount, target);
        return new ArrangeGroupsDefaultResolutionDecision(
                true, target, reason,
                TriggerCondition.IS_CONNECTED, interGroupConnectionCount);
    }
}
