package net.vheerden.archi.mcp.model;

/**
 * Inter-element spacing heuristic for the
 * {@code apply-element-spacing-recommendations} convenience tool.
 *
 * <p>Source of truth: archimate-view-patterns.md Pre-Layout Planning §2 lines
 * 182-187, element-spacing column. Boundaries match the table's
 * {@code ≤15 / 16-30 / >30} exactly. Pinned by JUnit
 * {@code ElementSpacingHeuristicTest} (pure-unit, all six branches + boundary
 * tests) and {@code ApplyElementSpacingRecommendationsToolTest}.</p>
 *
 * <p>This is an EMF-free utility class so the JUnit pin can run as a pure-unit
 * test (no OSGi context required, no transitive class-loading of
 * {@link ArchiModelAccessorImpl}). The accessor delegates to it; this class
 * is the single source of truth for the heuristic-table mapping.</p>
 *
 * <p><strong>Coordination contract:</strong> any future revision to the
 * heuristic table requires a coordinated edit across FOUR artefacts —
 * (1) the markdown resource, (2) this class's
 * {@link #targetSpacingForConnectionCount(int, boolean)} method, (3) the
 * JUnit test, (4) the five production callsites that derive
 * {@code hasLargeHubs} upstream and forward it. All five live in
 * {@code ArchiModelAccessorImpl} — {@code computeAdjustViewSpacing},
 * {@code applyElementSpacingRecommendations},
 * {@code applyGroupSpacingRecommendations},
 * {@code applySpacingRecommendations} and {@code arrangeGroups} — and all
 * five derive the flag through {@link HubSpacingSignal}, which is where a
 * change to its meaning belongs. The two
 * {@code ...DefaultResolutionDecision.decide} methods take the boolean as a
 * parameter and do not derive it. Edit one without the others and the test
 * fails until they all agree.</p>
 */
public final class ElementSpacingHeuristic {

    private ElementSpacingHeuristic() {
        // Utility class — not instantiable
    }

    /**
     * Returns the recommended element spacing in pixels for the given total
     * connection count on a view, picking the no-large-hubs or hub-aware
     * column based on whether the view contains at least one element with
     * more than 6 connections — the large-hub threshold, which is a higher
     * cut than the {@code >= 5} hub-candidate threshold
     * archimate-view-patterns.md §1 defines. {@link HubSpacingSignal} owns
     * the predicate; callers must not re-derive it.
     *
     * <table border="1">
     *   <caption>Heuristic tiers (archimate-view-patterns.md Pre-Layout Planning §2)</caption>
     *   <tr><th>Total connections</th><th>No large hubs (px)</th><th>Has large hubs (px)</th></tr>
     *   <tr><td>≤ 15</td><td>60</td><td>80</td></tr>
     *   <tr><td>16 – 30</td><td>80</td><td>100</td></tr>
     *   <tr><td>&gt; 30</td><td>100</td><td>120</td></tr>
     * </table>
     *
     * <p>The hub-aware column adds +20px per tier to account for the corridor
     * space that formula-resized hubs consume. Without it, the heuristic
     * undershoots post-hub-resize and coincSeg residuals persist (Row C of
     * the 2026-05-06 rating-signal investigation).</p>
     *
     * @param connectionCount total visible archimate-relationship connections
     *                        on the view (sourced from
     *                        {@code AssessLayoutResultDto.connectionCount()})
     * @param hasLargeHubs    true when the view has at least one element at
     *                        more than 6 connections; selects the hub-aware
     *                        column
     * @return element spacing target in pixels
     */
    public static int targetSpacingForConnectionCount(
            int connectionCount, boolean hasLargeHubs) {
        if (hasLargeHubs) {
            if (connectionCount <= 15) return 80;
            if (connectionCount <= 30) return 100;
            return 120;
        }
        if (connectionCount <= 15) return 60;
        if (connectionCount <= 30) return 80;
        return 100;
    }
}
