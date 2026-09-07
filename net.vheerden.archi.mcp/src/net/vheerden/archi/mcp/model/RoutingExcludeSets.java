package net.vheerden.archi.mcp.model;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ancestry lookups used to build the per-connection obstacle-exclusion sets the routing and
 * label passes share.
 *
 * <p>A connection must not treat its own endpoints' ancestors or descendants as blocking obstacles:
 * a connection leaving a nested element inevitably crosses the boxes that contain it, and anything
 * nested inside an endpoint — at any depth — sits between that endpoint and everything else.
 * Excluding them is what stops the router and the label optimizer from reporting a collision that
 * is structurally unavoidable.</p>
 *
 * <p>Both directions are transitive, and they have to be for the same reason. A connection
 * terminating on a populated container ends at that container's centre, which is frequently inside
 * a node two levels down; treating that node as an obstacle makes the connection unroutable, and an
 * unrouted connection has no command emitted for it, so the route is discarded rather than merely
 * reported.</p>
 *
 * <p><b>Membership is selected by containment and then FILTERED BY GEOMETRY against the endpoint.</b>
 * Containment alone is not the rule, because {@code parentId} is set unconditionally from EMF
 * containment while the rectangles are free: Archi lets a user drag a nested child clear of its
 * parent, and {@code LayoutQualityAssessor.detectBoundaryViolations} exists precisely to report
 * that state. A node selected by {@code parentId} but whose rectangle is fully disjoint from the
 * endpoint's cannot be in the structurally-unavoidable position the exemption is granted for, so it
 * stays an obstacle — otherwise the router draws straight through a real element and calls the
 * route clean while the boundary detector, one class away, is already reporting the same node as
 * escaped.</p>
 *
 * <p>The filter is <b>strict overlap, not containment</b>. A descendant that overflows its endpoint
 * by any amount but whose rectangle still meets it over a positive area keeps the exemption: it is
 * still between that endpoint and the connection's terminus, and "any overflow &rarr; obstacle"
 * would make a populated container unroutable again. Overlap here is {@link
 * OverlapResolver#rectsOverlap}'s <em>strict interior</em> test, so a rectangle merely flush against
 * an edge does not qualify — that is a stricter reading than the everyday sense of "touching", and
 * it is deliberate: it agrees edge-for-edge with {@code detectBoundaryViolations}, which uses
 * {@code >} on the same edge. The comparison is against the ENDPOINT's rectangle once, never per
 * link up the chain — the router's only question is "could this be between my endpoint and its
 * centre", and a per-link test answers a different one and gives incoherent results on a partially
 * drifted chain.</p>
 *
 * <p><b>Where there is no rectangle to compare against, both methods fail OPEN — but the two do so
 * at different points, and only one of them can actually protect anything.</b> Failing closed would
 * push an endpoint's real content back into the obstacle set on exactly the path that has the least
 * information, so neither does it.
 * <ul>
 *   <li>{@code descendantIds} seeds its walk from the raw id, so it collects a subtree even for a
 *       seed that has no node. That collection is then returned <em>unfiltered</em> — this is the
 *       branch where failing open is load-bearing.</li>
 *   <li>{@code ancestorIds} can only walk upward <em>from</em> the seed's node. A seed absent from
 *       the map means the walk never started, so its "unfiltered" result is necessarily
 *       <b>empty</b>. The fail-open there is real but <em>vacuous</em>; do not read it as a
 *       guarantee that ancestors survive a missing seed, because there are none to survive.</li>
 *   <li>Separately, an individual <em>candidate</em> whose id resolves to no node is kept by both
 *       methods, for the same reason: no rectangle, no filter.</li>
 * </ul>
 * </p>
 *
 * <p>Stateless helper over {@link AssessmentNode} — no EMF, no commands.</p>
 */
final class RoutingExcludeSets {

    private RoutingExcludeSets() {
        // static helper
    }

    /**
     * Gets the ancestor IDs of a node that a connection terminating on it cannot avoid crossing:
     * the {@code parentId} chain, filtered to those whose rectangle still overlaps the node's.
     * Used for excluding ancestor groups and containers from routing obstacles.
     *
     * <p>A "parent" that has been dragged clear of its child no longer encloses it, so a connection
     * terminating on the child does not have to cross it; exempting it would let the router run a
     * line straight through a real box. It is dropped from the set and becomes an obstacle like any
     * other element. One that still overlaps — including one the child merely overflows — is kept,
     * for the reason given on the class.</p>
     *
     * <p>The walk carries the same cycle guard as its sibling: {@code visited} stops a hand-built
     * {@code parentId} cycle instead of spinning. That guard and the geometric filter are
     * deliberately applied to <b>different sets</b> — folding the filter into the {@code add}
     * condition would let a rejected node re-enter the walk forever, defeating the guard on exactly
     * the input it exists for.</p>
     *
     * <p>A seed absent from {@code nodeMap} returns an <b>empty</b> set, not an unfiltered one:
     * the upward walk starts at the seed's own node, so with no node there is nothing to collect.
     * See the class note — this half's fail-open is vacuous, unlike {@code descendantIds}'.</p>
     */
    static Set<String> ancestorIds(String nodeId, Map<String, AssessmentNode> nodeMap) {
        Set<String> visited = new HashSet<>();
        AssessmentNode node = nodeMap.get(nodeId);
        while (node != null && node.parentId() != null && visited.add(node.parentId())) {
            node = nodeMap.get(node.parentId());
        }

        // Same lookup as `node` above, so this is reached only when the walk never ran and
        // `visited` is provably empty. Kept as an explicit branch rather than collapsed to
        // Set.of(): it states the fail-open rule in the one place a reader looks for it, and it
        // stays correct if the walk is ever seeded independently of the seed's own node.
        AssessmentNode seed = nodeMap.get(nodeId);
        if (seed == null) {
            return visited;
        }
        Set<String> kept = new HashSet<>();
        for (String id : visited) {
            AssessmentNode candidate = nodeMap.get(id);
            if (candidate == null || overlaps(candidate, seed)) {
                kept.add(id);
            }
        }
        return kept;
    }

    /**
     * Gets the visual descendant IDs nested inside a node — children, grandchildren, and deeper —
     * filtered to those whose rectangle still overlaps that node's. Used for excluding nested
     * elements from routing obstacles when the node is a source/target: connections terminating on
     * a populated container must not be blocked by its own contents, at any depth.
     *
     * <p>A descendant dragged clear of the endpoint is not the endpoint's interior any more, whatever
     * {@code parentId} still says, and {@code LayoutQualityAssessor.detectBoundaryViolations}
     * reports the very same node from the other side. It is dropped from the set and becomes an
     * obstacle. One that merely overflows the endpoint keeps the exemption.</p>
     *
     * <p>Seeds a frontier and iterates to a fixed point rather than recursing, so a hand-built
     * cyclic {@code parentId} graph terminates instead of spinning: the visited set is what the
     * frontier is tested against, and a node already in it never re-enters. <b>The geometric filter
     * runs after the traversal, over a separate set</b> — pruning during the walk would lose a
     * drifted node's own children even when those children do still overlap the endpoint, and
     * filtering inside the {@code add} condition would defeat the cycle guard.</p>
     */
    static Set<String> descendantIds(String nodeId, List<AssessmentNode> nodes) {
        AssessmentNode seed = null;
        for (AssessmentNode node : nodes) {
            if (node.id() != null && node.id().equals(nodeId)) {
                seed = node;
                break;
            }
        }

        Set<String> visited = new HashSet<>();
        Set<String> frontier = new HashSet<>();
        frontier.add(nodeId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (AssessmentNode node : nodes) {
                if (node.parentId() != null && frontier.contains(node.parentId())
                        && visited.add(node.id())) {
                    frontier.add(node.id());
                    changed = true;
                }
            }
        }

        if (seed == null) {
            return visited;
        }
        Set<String> kept = new HashSet<>();
        for (AssessmentNode node : nodes) {
            if (visited.contains(node.id()) && overlaps(node, seed)) {
                kept.add(node.id());
            }
        }
        return kept;
    }

    /**
     * Strict interior overlap between a candidate and the endpoint it was selected against.
     * Delegates to {@link OverlapResolver#rectsOverlap} rather than restating the test — this
     * package has already paid twice for holding two definitions of one idea.
     */
    private static boolean overlaps(AssessmentNode candidate, AssessmentNode seed) {
        return OverlapResolver.rectsOverlap(
                candidate.x(), candidate.y(), candidate.width(), candidate.height(),
                seed.x(), seed.y(), seed.width(), seed.height());
    }
}
