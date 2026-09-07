package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The rollback policy every terminal-anchoring wrap site applies to
 * {@link TerminalAnchoring}'s verdict.
 *
 * <p>{@link TerminalAnchoring} answers one question about one path: is this
 * terminal on its faceline? It is deliberately unaware of what a wrap site does
 * with the answer. This class holds that second half — the <em>policy</em> —
 * once, so the sites cannot drift apart:
 *
 * <ul>
 *   <li><strong>The flip rule.</strong> A mutation is rejected on account of an
 *       end that was on its faceline before the mutation and is not after it.
 *       An end that arrived off-face is not rejected on that account, because
 *       the mutation did not put it there.</li>
 *   <li><strong>The off-face pin.</strong> At a site whose write range can reach
 *       a terminal and is not otherwise bounded, an end that arrived
 *       <em>off</em> its faceline must additionally be left byte-identical. A
 *       perpendicular shift of such a terminal moves the point the
 *       ChopboxAnchor ray terminates at, so the render changes; separating the
 *       corridor without moving a terminal is a different stage's job.</li>
 *   <li><strong>Per end.</strong> {@link TerminalAnchoring#preservesEndpoints}
 *       conjoins the two ends, which makes the conjunction useless as a policy
 *       input in both directions: compared before and after it leaves one end
 *       unguarded once the other already reads {@code false}, and judged on the
 *       post-state alone it lets an end the mutation never touched veto a
 *       mutation at the far end of the path. Each end is decided on its own
 *       instead, reusing the predicate's null-per-end idiom rather than
 *       changing the predicate.</li>
 * </ul>
 *
 * <p>The two arms <strong>partition</strong> the cases rather than overlap: an
 * end that arrived on-face is decided by the flip rule alone, an end that
 * arrived off-face by the pin alone.
 *
 * <p>Callers supply the <em>view</em> the criterion is evaluated against, not
 * the raw path. {@link PathStraightener} passes the interior of an augmented
 * frame; {@link CoincidentSegmentDetector} passes the whole path, its frame
 * being unaugmented at both of its call sites.
 *
 * @see PathStraightener#checkAnchoringWrap
 * @see CoincidentSegmentDetector#applyOffsets
 */
final class TerminalAnchoringRollbackPolicy {

    private TerminalAnchoringRollbackPolicy() {
        // Static utility class
    }

    /**
     * The whole policy for one end: reject when the mutation flipped this
     * terminal off a faceline it was on, or — at a pinning site — when it moved
     * a terminal that had already arrived off-face.
     *
     * <p>An end with no anchoring context is unchecked, matching
     * {@link TerminalAnchoring#preservesEndpoints}' null-per-end contract. A
     * caller that must not consult an end at all (because its mutation could
     * not reach that terminal) omits the call rather than passing nulls.
     *
     * @param fromTargetEnd         evaluate the last point of the view rather
     *                              than the first
     * @param pinsOffFaceTerminals  whether this site's write range can reach a
     *                              terminal with nothing else bounding it
     */
    static boolean rejects(
            TerminalAnchoring anchoring, RoutingRect rect, int[] center,
            List<AbsoluteBendpointDto> beforeView, List<AbsoluteBendpointDto> afterView,
            boolean fromTargetEnd, boolean pinsOffFaceTerminals) {
        if (flipsTerminalOffFaceline(anchoring, rect, center, beforeView, afterView, fromTargetEnd)) {
            return true;
        }
        return pinsOffFaceTerminals
                && movesAnOffFaceTerminal(anchoring, rect, center, beforeView, afterView, fromTargetEnd);
    }

    /**
     * True when this end was on its faceline before the mutation and is not
     * after it. An end with no anchoring context is unchecked.
     *
     * @param fromTargetEnd evaluate the last point of the view rather than the
     *                      first, by reusing the source-side predicate on a
     *                      reversed copy — the same idiom the predicate itself
     *                      uses, so no predicate change is needed
     */
    static boolean flipsTerminalOffFaceline(
            TerminalAnchoring anchoring, RoutingRect rect, int[] center,
            List<AbsoluteBendpointDto> beforeView, List<AbsoluteBendpointDto> afterView,
            boolean fromTargetEnd) {
        // The centre is not read past this point — the faceline predicate stopped taking one. It
        // is retained as part of this guard, and only as that: a caller that supplies no centre
        // is a legacy caller whose end must go on being unchecked, so dropping it from the
        // condition would arm a rollback arm those callers deliberately do not run.
        if (anchoring == null || rect == null || center == null) {
            return false;
        }
        boolean onFacelineBefore = terminalOnFaceline(anchoring, rect,
                beforeView, fromTargetEnd);
        boolean onFacelineAfter = terminalOnFaceline(anchoring, rect,
                afterView, fromTargetEnd);
        return onFacelineBefore && !onFacelineAfter;
    }

    /**
     * True when this end arrived <em>off</em> its faceline and the mutation did not
     * leave that terminal byte-identical — it rewrote it, pushed it further off, or
     * deleted it and let the next interior point inherit the slot.
     *
     * <p>An end that arrived <em>on</em> its faceline returns {@code false} here: that
     * end is already governed by {@link #flipsTerminalOffFaceline}, and this arm must
     * not second-guess it.
     */
    static boolean movesAnOffFaceTerminal(
            TerminalAnchoring anchoring, RoutingRect rect, int[] center,
            List<AbsoluteBendpointDto> beforeView, List<AbsoluteBendpointDto> afterView,
            boolean fromTargetEnd) {
        if (anchoring == null || rect == null || center == null) {
            return false;
        }
        if (terminalOnFaceline(anchoring, rect, beforeView, fromTargetEnd)) {
            return false;
        }
        AbsoluteBendpointDto arrived = terminalPoint(beforeView, fromTargetEnd);
        if (arrived == null) {
            return false;
        }
        return !arrived.equals(terminalPoint(afterView, fromTargetEnd));
    }

    static boolean terminalOnFaceline(
            TerminalAnchoring anchoring, RoutingRect rect,
            List<AbsoluteBendpointDto> view, boolean fromTargetEnd) {
        List<AbsoluteBendpointDto> oriented = view;
        if (fromTargetEnd) {
            oriented = new ArrayList<>(view);
            Collections.reverse(oriented);
        }
        return TerminalAnchoring.preservesTerminalAnchoring(anchoring, rect, oriented);
    }

    static AbsoluteBendpointDto terminalPoint(
            List<AbsoluteBendpointDto> view, boolean fromTargetEnd) {
        if (view.isEmpty()) {
            return null;
        }
        return fromTargetEnd ? view.get(view.size() - 1) : view.get(0);
    }
}
