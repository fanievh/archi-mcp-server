package net.vheerden.archi.mcp.model;

import java.util.Locale;

/**
 * Pure-unit-testable decision for the density-aware default-resolution code
 * path inside {@code adjustViewSpacing}
 * (RoutingPreconditions.InterElement.DensityAwareDefault).
 *
 * <p>When {@code interElementDelta} is omitted (parameter is {@code null}) AND
 * the view has a problematic spacing-related metric, the tool derives a
 * heuristic-driven default rather than 0. This record encapsulates the
 * decision so the JUnit pin can exercise the trigger / no-trigger / boundary
 * / no-connections / already-meets-target branches without an OSGi context.</p>
 *
 * <p>Mirrors {@link ApplyElementSpacingDecision} pattern (the convenience-tool
 * sibling's decision record) — both share the
 * {@link ElementSpacingHeuristic#targetSpacingForConnectionCount(int, boolean)}
 * + {@code clamp-non-negative} heuristic core. The heuristic-cross-class
 * consistency check is the multi-class tripwire that protects the shared
 * single-source-of-truth.</p>
 *
 * <p><strong>Trigger thresholds (advisory-placeholder values, pinned
 * by JUnit):</strong></p>
 * <ul>
 *   <li>{@code coincidentSegmentCount > 2}, OR</li>
 *   <li>{@code connectionEdgeCoincidenceCount > 4}.</li>
 * </ul>
 *
 * <p>Revising the trigger thresholds requires a coordinated edit across THREE
 * artefacts: (1) this record, (2) the JUnit test {@code AdjustViewSpacingDefaultResolutionTest},
 * (3) the {@code adjust-view-spacing} tool description.</p>
 *
 * @param fired           true when the trigger fired (the gate's
 *                        problematic-metric condition was met) — INCLUDING
 *                        the already-meets-target case (4.6) where the
 *                        heuristic clamps {@code resolvedDelta} to 0 because
 *                        {@code currentSpacingPx >= targetSpacingPx}; the
 *                        {@code reason} field is still populated for
 *                        transparency in that case. False on every other
 *                        branch: caller provided an explicit value, no
 *                        groups, no group with 2+ children, zero connections,
 *                        or trigger-not-fired. The early-zero short-circuit
 *                        at the call site folds case (4.6) into a no-mutation
 *                        response (because all-zero deltas hit the existing
 *                        short-circuit), but {@code fired=true} preserves
 *                        the signal for the response DTO's
 *                        {@code defaultResolutionReason}.
 * @param resolvedDelta   the delta the caller's omitted parameter resolves to
 *                        (always 0 when caller-provided null AND no trigger;
 *                        the heuristic-computed delta when the trigger fires;
 *                        the caller's explicit value passed through unchanged
 *                        when caller provided non-null)
 * @param reason          populated when default-resolution fired (delta &gt; 0)
 *                        OR when an informational no-fire condition warrants
 *                        transparency (no connections, trigger-but-already-
 *                        meets-target). Null on caller-provided + clean-view
 *                        no-fire path.
 * @param triggerMetric   which metric tripped the gate (NONE on no-fire)
 * @param triggerValue    the metric's measured value at the time the gate
 *                        evaluated (0 on no-fire)
 */
public record AdjustViewSpacingDefaultResolutionDecision(
        boolean fired,
        int resolvedDelta,
        String reason,
        TriggerMetric triggerMetric,
        int triggerValue) {

    /**
     * Trigger metric enum — which problematic spacing-related signal tripped
     * the default-resolution gate.
     */
    public enum TriggerMetric {
        /** No metric tripped the gate (clean view, or caller-provided value). */
        NONE,
        /** {@code coincidentSegmentCount > 2} fired first. */
        COINCIDENT_SEGMENTS,
        /** {@code connectionEdgeCoincidenceCount > 4} fired first. */
        EDGE_COINCIDENCE
    }

    /**
     * Computes the decision from upstream-collected inputs. Pure-unit; no
     * EMF / OSGi dependencies.
     *
     * <p>Branch order (first match wins):</p>
     * <ol>
     *   <li>Caller provided a non-null delta → no-fire, return caller's value.</li>
     *   <li>No groups → no-fire (the existing flat-view exception in
     *       {@code adjustViewSpacing} fires before this decision is reached
     *       in production; this branch is defence-in-depth).</li>
     *   <li>No group with 2+ non-note children → no-fire (current spacing
     *       undefined; mirrors the convenience-tool sibling's short-circuit).</li>
     *   <li>Zero connections → no-fire with informational reason (the
     *       heuristic is connection-count-driven; zero connections means the
     *       default is undefined).</li>
     *   <li>Trigger evaluation: COINCIDENT_SEGMENTS &gt; 2 first, then
     *       EDGE_COINCIDENCE &gt; 4. If neither fires → no-fire, no reason.</li>
     *   <li>Heuristic computation: {@code max(0, target - current)}. When
     *       target &le; current the decision still fires (transparency:
     *       caller asked for default and the trigger DID fire) but
     *       resolvedDelta is 0 → call-site folds into the existing all-zero
     *       short-circuit response.</li>
     * </ol>
     *
     * @param callerProvidedDelta            the caller's interElementDelta
     *                                       (null = omitted = default-
     *                                       resolution candidate; non-null =
     *                                       caller decided, including 0)
     * @param coincidentSegmentCount         coincident-segment metric from assessLayout
     * @param connectionEdgeCoincidenceCount edge-coincidence metric from
     *                                       assessLayout
     * @param connectionCount                total visible connections on the
     *                                       view (sourced from
     *                                       {@code AssessLayoutResultDto.connectionCount()})
     * @param currentSpacingPx               min per-group spacing across top-
     *                                       level groups (most-tight wins);
     *                                       use 0 when undefined (no group
     *                                       with 2+ children — caller passes
     *                                       hasGroupWithMultipleChildren=false)
     * @param hasGroups                      true when the view has at least
     *                                       one top-level group with children
     *                                       (post-flat-view-guard)
     * @param hasGroupWithMultipleChildren   true when at least one top-level
     *                                       group has 2+ non-note children
     * @param hasLargeHubs                   true when the view has at least
     *                                       one element at more than 6
     *                                       connections; forwarded to
     *                                       {@link ElementSpacingHeuristic}
     *                                       to select the hub-aware tier
     *                                       (Row C of the 2026-05-06 rating-
     *                                       signal investigation).
     *                                       Caller-provided input — the
     *                                       record stays pure-unit and does
     *                                       NOT scan view contents.
     * @return decision describing the chosen branch + computed delta
     */
    public static AdjustViewSpacingDefaultResolutionDecision decide(
            Integer callerProvidedDelta,
            int coincidentSegmentCount,
            int connectionEdgeCoincidenceCount,
            int connectionCount,
            int currentSpacingPx,
            boolean hasGroups,
            boolean hasGroupWithMultipleChildren,
            boolean hasLargeHubs) {

        // 1. caller provided (including 0): no default resolution
        if (callerProvidedDelta != null) {
            return new AdjustViewSpacingDefaultResolutionDecision(
                    false, callerProvidedDelta, null, TriggerMetric.NONE, 0);
        }

        // 2. no groups: defence-in-depth (the flat-view exception fires first
        //    in production); return no-fire so the existing exception path
        //    propagates unchanged.
        if (!hasGroups) {
            return new AdjustViewSpacingDefaultResolutionDecision(
                    false, 0, null, TriggerMetric.NONE, 0);
        }

        // 3. no group with 2+ children: spacing undefined; no-fire.
        if (!hasGroupWithMultipleChildren) {
            return new AdjustViewSpacingDefaultResolutionDecision(
                    false, 0, null, TriggerMetric.NONE, 0);
        }

        // 4. no connections: heuristic is connection-count-driven; surface
        //    informational reason for transparency.
        if (connectionCount == 0) {
            return new AdjustViewSpacingDefaultResolutionDecision(
                    false, 0,
                    "interElementDelta omitted; view has no connections; "
                    + "density-aware default not applicable",
                    TriggerMetric.NONE, 0);
        }

        // 5. trigger evaluation (advisory-placeholder thresholds)
        TriggerMetric trigger;
        int triggerValue;
        if (coincidentSegmentCount > 2) {
            trigger = TriggerMetric.COINCIDENT_SEGMENTS;
            triggerValue = coincidentSegmentCount;
        } else if (connectionEdgeCoincidenceCount > 4) {
            trigger = TriggerMetric.EDGE_COINCIDENCE;
            triggerValue = connectionEdgeCoincidenceCount;
        } else {
            return new AdjustViewSpacingDefaultResolutionDecision(
                    false, 0, null, TriggerMetric.NONE, 0);
        }

        // 6. heuristic computation (Row C: hub-aware)
        int target = ElementSpacingHeuristic
                .targetSpacingForConnectionCount(connectionCount, hasLargeHubs);
        int delta = Math.max(0, target - currentSpacingPx);
        String triggerLabel = (trigger == TriggerMetric.COINCIDENT_SEGMENTS)
                ? "coincidentSegmentCount" : "connectionEdgeCoincidenceCount";
        int triggerThreshold = (trigger == TriggerMetric.COINCIDENT_SEGMENTS)
                ? 2 : 4;
        String reason = String.format(Locale.ROOT,
                "interElementDelta omitted; %s=%d > %d trigger; "
                + "heuristic for connectionCount=%d => target=%d; "
                + "currentSpacingPx=%d => delta=%d",
                triggerLabel, triggerValue, triggerThreshold,
                connectionCount, target, currentSpacingPx, delta);
        return new AdjustViewSpacingDefaultResolutionDecision(
                true, delta, reason, trigger, triggerValue);
    }
}
