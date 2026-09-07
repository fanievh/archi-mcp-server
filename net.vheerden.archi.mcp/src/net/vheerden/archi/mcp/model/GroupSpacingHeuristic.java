package net.vheerden.archi.mcp.model;

/**
 * Inter-group spacing heuristic for the
 * {@code apply-group-spacing-recommendations} convenience tool.
 *
 * <p>Source of truth: archimate-view-patterns.md Pre-Layout Planning §2 lines
 * 182-187, inter-group-spacing columns. Boundaries match the table's
 * {@code ≤15 / 16-30 / >30} exactly. Three columns: <em>connected
 * no-large-hubs</em>, <em>connected has-large-hubs</em>, and
 * <em>unconnected</em> (hub-agnostic). The connected columns fire when the
 * view has at least one connection crossing a top-level group boundary.
 * The hub-aware column fires when the view has at least one element with
 * more than 6 connections — the large-hub threshold, which is a higher cut
 * than the {@code >= 5} hub-candidate threshold archimate-view-patterns.md
 * §1 defines. {@link HubSpacingSignal} owns the predicate. Pinned by JUnit
 * {@code GroupSpacingHeuristicTest} (pure-unit, all branches + boundary
 * tests) and {@code ApplyGroupSpacingRecommendationsToolTest}
 * (connected, unconnected, boundary).</p>
 *
 * <p>This is an EMF-free utility class so the JUnit pin can run as a pure-unit
 * test (no OSGi context required, no transitive class-loading of
 * {@link ArchiModelAccessorImpl}). The accessor delegates to it; this class
 * is the single source of truth for the heuristic-table mapping.</p>
 *
 * <p><strong>Coordination contract:</strong> any future revision to the
 * heuristic table requires a coordinated edit across FOUR artefacts —
 * (1) the markdown resource, (2) this class's
 * {@link #targetSpacingForConnectionCount(int, boolean, boolean)} method,
 * (3) the JUnit test, (4) the five production callsites that derive
 * {@code hasLargeHubs} upstream and forward it. All five live in
 * {@code ArchiModelAccessorImpl} — {@code computeAdjustViewSpacing},
 * {@code applyElementSpacingRecommendations},
 * {@code applyGroupSpacingRecommendations},
 * {@code applySpacingRecommendations} and {@code arrangeGroups} — and all
 * five derive the flag through {@link HubSpacingSignal}, which is where a
 * change to its meaning belongs. The two
 * {@code ...DefaultResolutionDecision.decide} methods take the boolean as a
 * parameter and do not derive it. Edit one without the others and the test
 * fails until they all agree. Sibling-symmetric with
 * {@link ElementSpacingHeuristic}.</p>
 */
public final class GroupSpacingHeuristic {

    private GroupSpacingHeuristic() {
        // Utility class — not instantiable
    }

    /**
     * Returns the recommended inter-group spacing in pixels for the given
     * total connection count on a view, picking the connected
     * no-large-hubs / connected has-large-hubs / unconnected column based on
     * whether the view has any inter-group connection and whether it
     * contains at least one element with more than 6 connections.
     *
     * <table border="1">
     *   <caption>Heuristic tiers (archimate-view-patterns.md Pre-Layout Planning §2)</caption>
     *   <tr>
     *     <th>Total connections</th>
     *     <th>Connected, no large hubs (px)</th>
     *     <th>Connected, has large hubs (px)</th>
     *     <th>Unconnected (px)</th>
     *   </tr>
     *   <tr><td>≤ 15</td><td>80</td><td>100</td><td>40</td></tr>
     *   <tr><td>16 – 30</td><td>100</td><td>140</td><td>40</td></tr>
     *   <tr><td>&gt; 30</td><td>120</td><td>160</td><td>60</td></tr>
     * </table>
     *
     * <p>The hub-aware connected column adds +20-40px per tier to account
     * for the corridor space that formula-resized hubs consume. Without it,
     * inter-group corridors stay too narrow post-hub-resize and coincSeg
     * residuals persist on inter-group connections (Row C of the 2026-05-06
     * rating-signal investigation). The unconnected column is
     * hub-agnostic — there are no inter-group corridors to widen.</p>
     *
     * @param connectionCount total visible connections on the view (sourced
     *                        from {@code AssessLayoutResultDto.connectionCount()})
     * @param isConnected     true when the view has at least one connection
     *                        crossing a top-level group boundary; selects the
     *                        connected column. False selects the unconnected
     *                        column (hub-agnostic).
     * @param hasLargeHubs    true when the view has at least one element at
     *                        more than 6 connections; combined with
     *                        {@code isConnected=true} selects the hub-aware
     *                        connected column. Ignored when
     *                        {@code isConnected=false}.
     * @return inter-group spacing target in pixels
     */
    public static int targetSpacingForConnectionCount(
            int connectionCount, boolean isConnected, boolean hasLargeHubs) {
        if (!isConnected) {
            return unconnectedColumnTarget(connectionCount);
        }
        if (hasLargeHubs) {
            return connectedHubAwareColumnTarget(connectionCount);
        }
        return connectedColumnTarget(connectionCount);
    }

    private static int connectedColumnTarget(int connectionCount) {
        if (connectionCount <= 15) return 80;
        if (connectionCount <= 30) return 100;
        return 120;
    }

    private static int connectedHubAwareColumnTarget(int connectionCount) {
        if (connectionCount <= 15) return 100;
        if (connectionCount <= 30) return 140;
        return 160;
    }

    private static int unconnectedColumnTarget(int connectionCount) {
        if (connectionCount <= 30) return 40;
        return 60;
    }
}
