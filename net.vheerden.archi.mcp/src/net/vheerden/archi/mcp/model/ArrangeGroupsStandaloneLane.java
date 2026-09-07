package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.ICommunicationNetwork;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDevice;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.INode;
import com.archimatetool.model.IPath;

/**
 * Standalone-element lane for arrange-groups (Option A — reserve centre lane).
 *
 * <p>Classifies a view's top-level children that are not themselves arrangement targets as
 * qualifying lane targets for
 * {@code arrange-groups}, assigns each qualifier to an inter-group gap, and computes
 * lane widths/heights along the layout axis. The lane reserves a corridor between
 * groups in topology order so the qualifier sits where the recipe text already
 * promises it would: {@code technology-deployment.md:35} "place it between the zones"
 * and {@code application-integration.md:37} "the hub sits between them".</p>
 *
 * <p>Wired only when {@code arrangement="topology"} is in effect at {@code arrangeGroups}
 * (and the resulting layout axis is row or column, not grid). Direct row/column/grid
 * calls and zero-qualifier views preserve byte-identical behaviour.</p>
 *
 * <p>A qualifier is a top-level non-Note {@code IDiagramModelArchimateObject} that is not itself
 * an arrangement target, whose {@code IArchimateConcept} is {@link INode}, {@link IDevice},
 * {@link IPath}, or {@link ICommunicationNetwork}, AND that has direct view connections (source or
 * target) reaching ≥ 2 of the arranged target groups. A connection reaches a group both when it
 * terminates on an element inside it and when it terminates on the group's own box — see
 * {@link #arrangedContainerReachedBy} for the enumerated cases.</p>
 *
 * <p>Being a target and being a lane candidate are mutually exclusive roles: a container the
 * arrangement is about to position cannot also be a loose element the arrangement drops into a gap
 * between positions. The exclusion therefore asks {@link TopLevelGroupTargets#isTarget} rather than
 * naming one concrete type, so it stays correct if the qualifier set or the target set widens.</p>
 *
 * <p><b>Two exclusions, and neither subsumes the other.</b> Asking the shared predicate covers a
 * widening of the predicate itself, but not a widening that happens per call: {@code groupIds} can
 * name a container the default predicate declines, and {@code isTarget} still answers false for it.
 * Such a container was classified as a lane qualifier as well, so it received an update command
 * from the arrangement and a second from the lane in the same compound — and because the lane's is
 * added later, the lane position silently won and the arrangement the caller asked for was
 * discarded. The walk therefore also skips anything present in {@code targetGroups}. The predicate
 * check excludes a container the caller filtered <em>out</em> of this call, which is still not a
 * loose element; the containment check excludes what this call is arranging. Neither is redundant.</p>
 *
 * <p>{@code IDevice} is a sibling of {@code INode} in the Archi metamodel
 * ({@code IDevice extends ITechnologyElement} directly, NOT {@code INode}). It is listed
 * alongside {@code Node} in the technology-deployment recipe element-subset
 * ({@code technology-deployment.md:11}), so a top-level Device wired to ≥ 2 zone groups
 * qualifies for the lane placement.</p>
 */
final class ArrangeGroupsStandaloneLane {

    private ArrangeGroupsStandaloneLane() {}

    /** A qualifying standalone element + the target-group IDs it connects to (in encounter order). */
    record QualifyingStandaloneElement(
            IDiagramModelObject element,
            LinkedHashSet<String> connectedTargetGroupIds) {}

    /**
     * Classifies top-level children of {@code viewChildren} that are not arrangement targets as
     * qualifying lane targets.
     *
     * @param viewChildren the view's top-level children (the iteration source used by
     *     {@code arrangeGroups} at {@code ArchiModelAccessorImpl.java:10530})
     * @param targetGroups groups currently scheduled for arrangement (order does not matter here;
     *     gap-assignment in {@link #assignToGaps} uses the post-orderer order). Which container an
     *     endpoint sits in is resolved against this set by
     *     {@link TopLevelGroupTargets#containerOf}, the same walk the inter-container counts use,
     *     so the lane and the topology weights cannot disagree about where an object is.
     */
    static List<QualifyingStandaloneElement> classify(
            List<IDiagramModelObject> viewChildren,
            List<IDiagramModelObject> targetGroups) {
        Set<String> targetGroupIds = new HashSet<>();
        for (IDiagramModelObject g : targetGroups) {
            targetGroupIds.add(g.getId());
        }

        List<QualifyingStandaloneElement> qualifiers = new ArrayList<>();
        for (IDiagramModelObject child : viewChildren) {
            if (TopLevelGroupTargets.isTarget(child)) continue;
            if (targetGroupIds.contains(child.getId())) continue;
            if (!isLaneEligibleType(child)) continue;
            LinkedHashSet<String> connected =
                    collectConnectedTargetGroupIds(child, targetGroupIds);
            if (connected.size() < 2) continue;
            qualifiers.add(new QualifyingStandaloneElement(child, connected));
        }
        return qualifiers;
    }

    /**
     * Whether the lane would ever place this object, judged on type alone.
     *
     * <p>Shared with the response assembly so a caller is told the right thing about why an object
     * was not placed: an element of a type the lane never admits and one that admits it but was
     * wired to a single container are different findings, and only this predicate separates them.
     * Package-visible for that reason — the classification it feeds is unchanged.</p>
     */
    static boolean isLaneEligibleType(IDiagramModelObject obj) {
        if (!(obj instanceof IDiagramModelArchimateObject archiObj)) return false;
        IArchimateConcept concept = archiObj.getArchimateConcept();
        if (concept == null) return false;
        // IDevice is a sibling of INode in the Archi metamodel (both extend
        // ITechnologyElement directly); the recipe element-subset lists them together,
        // so a top-level Device wired to ≥ 2 groups qualifies for lane placement.
        return (concept instanceof INode)
                || (concept instanceof IDevice)
                || (concept instanceof IPath)
                || (concept instanceof ICommunicationNetwork);
    }

    /**
     * The arranged containers this object's own connections reach, in encounter order. Every far
     * endpoint is put through {@link #arrangedContainerReachedBy} and nothing else, so the
     * question is asked in exactly one place and two endpoints resolving to the same container
     * contribute one id rather than two.
     */
    private static LinkedHashSet<String> collectConnectedTargetGroupIds(
            IDiagramModelObject obj,
            Set<String> targetGroupIds) {
        LinkedHashSet<String> connected = new LinkedHashSet<>();
        if (obj instanceof IConnectable connectable) {
            List<IConnectable> farEndpoints = new ArrayList<>();
            for (IDiagramModelConnection conn : connectable.getSourceConnections()) {
                farEndpoints.add(conn.getTarget());
            }
            for (IDiagramModelConnection conn : connectable.getTargetConnections()) {
                farEndpoints.add(conn.getSource());
            }
            for (IConnectable endpoint : farEndpoints) {
                String reached = arrangedContainerReachedBy(endpoint, targetGroupIds);
                if (reached != null) {
                    connected.add(reached);
                }
            }
        }
        return connected;
    }

    /**
     * The arranged container one connection endpoint puts the candidate in touch with, or null
     * when it puts it in touch with none.
     *
     * <p>One question, asked once, decided against an enumerated list of the endpoint kinds a view
     * can hold. It was previously written against a single example — an element drawn inside a
     * zone — and resolved every endpoint through the element-to-container map alone. That map is
     * built from each container's children and never holds a container's own id, so a connection
     * drawn to the box resolved to nothing, and an element associated with two zones was counted
     * at one and told it had reached fewer than two.</p>
     *
     * <p>The cases, and what each one answers:</p>
     * <ul>
     *   <li><b>An element nested inside an arranged container</b> — <b>counts</b>, keyed to that
     *       container. The long-standing case.</li>
     *   <li><b>An arranged container itself</b>, whether a native group, an ArchiMate
     *       {@code Grouping}, or a host this call arranges only because the caller named it in
     *       {@code groupIds} — <b>counts</b>, keyed to its own id. This is the shape the
     *       technology-deployment guidance prescribes: each infrastructure node is drawn as a
     *       container box, and the nodes are then associated to the network rather than linked to
     *       each other, so the endpoint the guidance asks for is a container box.</li>
     *   <li><b>A container nested inside an arranged container</b> — <b>counts</b>, keyed to the
     *       OUTERMOST arranged ancestor rather than to itself, because the walk returns the
     *       outermost arranged container that holds it. Its own id is not an arranged id, so
     *       keying it to itself would contribute nothing.</li>
     *   <li><b>A note drawn inside an arranged container</b> — <b>counts</b>, keyed to that
     *       container. A note inside the zone is inside the zone, and reaches it as any other
     *       child does.</li>
     *   <li><b>An element inside a container this call is not arranging</b> — <b>does not
     *       count</b>, whether the caller filtered that container out through {@code groupIds} or
     *       it is a host no call named. The walk admits only the arranged set, so nothing under an
     *       unarranged container resolves.</li>
     *   <li><b>A loose element at the view's own top level</b> — <b>does not count</b>. It sits
     *       in no container, so it cannot put the candidate in touch with one.</li>
     *   <li><b>The candidate itself</b>, on a connection that loops back to it — <b>does not
     *       count</b>. A candidate is excluded from the arranged set by {@link #classify}, so its
     *       own id is never an arranged id and neither test below admits it.</li>
     * </ul>
     *
     * <p>Both tests end at the same place: an id is contributed only when {@code targetGroupIds}
     * already holds it. That is what keeps every id a qualifier carries an id
     * {@link #assignToGaps} has an index for.</p>
     */
    private static String arrangedContainerReachedBy(
            IConnectable endpoint,
            Set<String> targetGroupIds) {
        if (endpoint == null) return null;
        // The endpoint IS one of the containers being arranged: its own id is the key. Asked
        // first because the containment walk starts above the object and so never returns it.
        if (targetGroupIds.contains(endpoint.getId())) {
            return endpoint.getId();
        }
        // Otherwise the arranged container holding it, which for a descendant at any depth is the
        // outermost one. A connection endpoint that is itself a connection sits in no container.
        if (!(endpoint instanceof IDiagramModelObject drawn)) {
            return null;
        }
        IDiagramModelObject container = TopLevelGroupTargets.containerOf(drawn, targetGroupIds);
        return container != null ? container.getId() : null;
    }

    /**
     * Assigns each qualifier to an inter-group gap in the topology-ordered
     * {@code orderedTargetGroups} list. Gap-i is the gap between
     * {@code orderedTargetGroups[i]} and {@code orderedTargetGroups[i+1]}
     * (0 ≤ i &lt; size − 1).
     *
     * <p>Assignment rule: the qualifier's connected groups have indices in
     * {@code [minIdx, maxIdx]} along the ordered list; the qualifier is placed in
     * gap {@code floor((minIdx + maxIdx − 1) / 2)} — the lower-middle gap inside that
     * range. For two-group connectivity this is unambiguous; for three or more groups
     * spanning a wider range, the rule prefers the lower-middle gap so the qualifier
     * still sits visually between the connected clusters.</p>
     *
     * <p><b>Why the index lookup can never come up empty, and why that is not a reason code.</b>
     * A qualifier's connected ids come from {@link #arrangedContainerReachedBy}, which returns an
     * id only after testing it against the arranged set, and both call sites pass that same list
     * here as {@code orderedTargetGroups}. So every id a qualifier carries has an index,
     * {@code minIdx} cannot stay at its sentinel, and two distinct ids cannot share one index —
     * a qualified element can never be silently dropped by the guard below. The guard is kept
     * against a future caller that classifies against one list and assigns against another; it is
     * not published as an outcome, because a code for a state no caller can be in is a wire
     * contract that can only mislead. The invariant is asserted in the lane's tests rather than
     * left standing as this paragraph alone.</p>
     */
    static Map<Integer, List<QualifyingStandaloneElement>> assignToGaps(
            List<QualifyingStandaloneElement> qualifiers,
            List<IDiagramModelObject> orderedTargetGroups) {
        Map<String, Integer> groupIdToIndex = new LinkedHashMap<>();
        for (int i = 0; i < orderedTargetGroups.size(); i++) {
            groupIdToIndex.put(orderedTargetGroups.get(i).getId(), i);
        }

        Map<Integer, List<QualifyingStandaloneElement>> gapMap = new LinkedHashMap<>();
        for (QualifyingStandaloneElement q : qualifiers) {
            int minIdx = Integer.MAX_VALUE;
            int maxIdx = Integer.MIN_VALUE;
            for (String gid : q.connectedTargetGroupIds()) {
                Integer idx = groupIdToIndex.get(gid);
                if (idx == null) continue;
                if (idx < minIdx) minIdx = idx;
                if (idx > maxIdx) maxIdx = idx;
            }
            if (minIdx == Integer.MAX_VALUE || maxIdx == minIdx) continue;
            int gapIdx = (minIdx + maxIdx - 1) / 2;
            gapMap.computeIfAbsent(gapIdx, k -> new ArrayList<>()).add(q);
        }
        return gapMap;
    }

    /**
     * Computes the per-gap lane size along the layout axis. Returns a list of length
     * {@code max(0, numGroups − 1)}; non-qualifying gaps return 0 (caller uses
     * {@code resolvedSpacing} for those, preserving back-compat).
     *
     * <p>A qualifying gap reserves {@code qualifierAxisSize + 2*resolvedSpacing} so the
     * qualifier has {@code resolvedSpacing} clearance from each neighbouring group.
     * Multiple qualifiers in the same gap are stacked along the layout axis with
     * {@code resolvedSpacing} between them, so their axis sizes sum.</p>
     *
     * <p><strong>Invariant with {@link #placeQualifiers}:</strong> for n qualifiers in a
     * gap with axis-sizes {@code w_1..w_n}, lane size =
     * {@code (w_1 + .. + w_n) + (n-1)*resolvedSpacing + 2*resolvedSpacing}, i.e.
     * {@code sum(w_i) + (n+1)*resolvedSpacing}. {@code placeQualifiers} positions the
     * first qualifier at {@code laneLeft = groupLeftEnd + resolvedSpacing} and advances by
     * {@code w_i + resolvedSpacing} after each placement, so the last qualifier's right
     * edge sits at {@code laneLeft + sum(w_i) + (n-1)*resolvedSpacing}, leaving exactly
     * {@code resolvedSpacing} margin between it and the next group. Verified
     * algebraically for n=1 and n=2; generalises by induction. If you change either
     * method, re-verify the invariant.</p>
     *
     * @param horizontalAxis {@code true} for row layout (lane width along X);
     *     {@code false} for column layout (lane height along Y)
     */
    static List<Integer> computeLaneSizes(
            Map<Integer, List<QualifyingStandaloneElement>> gapAssignments,
            int numGroups,
            int resolvedSpacing,
            boolean horizontalAxis) {
        List<Integer> laneSizes = new ArrayList<>();
        for (int gapIdx = 0; gapIdx < Math.max(0, numGroups - 1); gapIdx++) {
            List<QualifyingStandaloneElement> qsInGap = gapAssignments.get(gapIdx);
            if (qsInGap == null || qsInGap.isEmpty()) {
                laneSizes.add(0);
                continue;
            }
            int sizeAlongAxis = 0;
            for (int i = 0; i < qsInGap.size(); i++) {
                IDiagramModelObject el = qsInGap.get(i).element();
                int dim = horizontalAxis
                        ? el.getBounds().getWidth()
                        : el.getBounds().getHeight();
                sizeAlongAxis += dim;
                if (i > 0) sizeAlongAxis += resolvedSpacing;
            }
            laneSizes.add(sizeAlongAxis + 2 * resolvedSpacing);
        }
        return laneSizes;
    }

    /** Final per-qualifier placement (element + bounds) emitted by {@link #placeQualifiers}. */
    record QualifierPlacement(IDiagramModelObject element, int x, int y, int width, int height) {}

    /**
     * Computes the final placement for each qualifier given its assigned gap and the
     * surrounding groups' positions and per-arrangement dimensions. Returns placements
     * in source order across all gaps. Empty list when there are no qualifying gaps.
     *
     * <p>Horizontal axis (row layout): qualifier x = lane.left + cumulative; y = vertical
     * midpoint of the union of the two adjacent groups' bounds, minus qualifier.height/2.
     * Vertical axis (column layout): transposed.</p>
     *
     * <p>{@code groupDims.get(i)} = {@code [width, height]} of group i AS LAID OUT BY
     * THE CALLER — for {@code arrangeGroups} primary site this is {@code group.getBounds()};
     * for {@code computeGroupedLayoutPass} this is the virtual post-resize dimensions.</p>
     *
     * @param gapAssignments output of {@link #assignToGaps}
     * @param numGroups number of arranged groups
     * @param positions per-group {@code [x, y]} from the position calculator (size = numGroups)
     * @param groupDims per-group {@code [width, height]} for the arrangement (size = numGroups)
     * @param resolvedSpacing layout spacing (used for the lane-internal clearance)
     * @param horizontalAxis true for row layout, false for column layout
     */
    static List<QualifierPlacement> placeQualifiers(
            Map<Integer, List<QualifyingStandaloneElement>> gapAssignments,
            int numGroups,
            List<int[]> positions,
            List<int[]> groupDims,
            int resolvedSpacing,
            boolean horizontalAxis) {
        List<QualifierPlacement> placements = new ArrayList<>();
        for (int gapIdx = 0; gapIdx < Math.max(0, numGroups - 1); gapIdx++) {
            List<QualifyingStandaloneElement> qs = gapAssignments.get(gapIdx);
            if (qs == null || qs.isEmpty()) continue;
            int[] leftPos = positions.get(gapIdx);
            int[] leftDim = groupDims.get(gapIdx);
            int[] rightPos = positions.get(gapIdx + 1);
            int[] rightDim = groupDims.get(gapIdx + 1);
            if (horizontalAxis) {
                int laneLeft = leftPos[0] + leftDim[0] + resolvedSpacing;
                int unionTop = Math.min(leftPos[1], rightPos[1]);
                int unionBottom = Math.max(
                        leftPos[1] + leftDim[1],
                        rightPos[1] + rightDim[1]);
                int unionMidY = (unionTop + unionBottom) / 2;
                int cursorX = laneLeft;
                for (QualifyingStandaloneElement q : qs) {
                    int w = q.element().getBounds().getWidth();
                    int h = q.element().getBounds().getHeight();
                    placements.add(new QualifierPlacement(q.element(),
                            cursorX, unionMidY - h / 2, w, h));
                    cursorX += w + resolvedSpacing;
                }
            } else {
                int laneTop = leftPos[1] + leftDim[1] + resolvedSpacing;
                int unionLeft = Math.min(leftPos[0], rightPos[0]);
                int unionRight = Math.max(
                        leftPos[0] + leftDim[0],
                        rightPos[0] + rightDim[0]);
                int unionMidX = (unionLeft + unionRight) / 2;
                int cursorY = laneTop;
                for (QualifyingStandaloneElement q : qs) {
                    int w = q.element().getBounds().getWidth();
                    int h = q.element().getBounds().getHeight();
                    placements.add(new QualifierPlacement(q.element(),
                            unionMidX - w / 2, cursorY, w, h));
                    cursorY += h + resolvedSpacing;
                }
            }
        }
        return placements;
    }
}
