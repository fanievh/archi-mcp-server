package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator.Face;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Perimeter-terminal immutability carrier.
 *
 * <p>The record carries exactly one field — the {@link Face}
 * on which the terminal bendpoint is anchored — because every other piece of
 * information the predicate needs (its axis and its face-line coordinate) can be
 * derived on demand from the connection's live source rect, keeping the predicate
 * axis-agnostic and free of coordinate bookkeeping bugs. The source centre used to be
 * listed here too; the predicate stopped reading it when the degeneracy check was
 * replaced, and it is no longer a parameter.
 *
 * <p>The predicate {@link #preservesTerminalAnchoring} is enforced
 * <strong>exclusively</strong> at {@code path[0]} / {@code path[last]} mutator sites
 * within {@link PathStraightener} and {@link CoincidentSegmentDetector#applyOffsets}.
 * Stages that legitimately shape terminal bendpoints by contract are outside this
 * predicate's domain by construction.
 *
 * <p>An earlier revision named those stages as 4.7h/k/m/o — the
 * {@code alignTerminalsWithCenter} family and {@link ChannelNudgingPass}. A census over
 * the fixture corpus, instrumented at every terminal-touching call and run end to end,
 * does not support that list. Of the four, only <strong>4.7o</strong> was observed to
 * exercise the licence; 4.7h, 4.7k and 4.7m took no terminal off its recorded face line,
 * and 4.7k never wrote a terminal at all. The two largest creators on that corpus —
 * micro-jog removal in the clearance cleanups, and the terminal deletion in obstacle
 * re-validation — are not on the list, and neither is on it now: a stage being outside
 * this predicate's domain is a statement about where the predicate is enforced, not a
 * roster of which stages happen to move a terminal. <strong>Read the census claim at its
 * real strength: not observed on this corpus is weaker than impossible.</strong> Three of
 * those stages are reachable in principle and simply did not fire here.
 *
 * <p>The five wrap sites share this predicate <strong>and</strong> the policy they
 * apply to its verdict, which lives once in
 * {@link TerminalAnchoringRollbackPolicy}: roll back on a true-to-false
 * <em>flip</em>, evaluated per end, and — at a site whose write range can reach a
 * terminal with nothing else bounding it — additionally refuse to move a terminal
 * that arrived off-face. Three sites pin on that rule: {@code snapToStraight},
 * {@code collapseStaircaseJogs} and
 * {@link CoincidentSegmentDetector#applyOffsets}. The predicate itself is
 * deliberately unaware of all of this — it answers one question about one path,
 * and the policy decides what to do with the answer.
 *
 * <p><strong>One check in the family is still a whole-path conjunction:</strong>
 * {@code CoincidentSegmentDetector.applyTerminalAnchoredReconciliation}, which
 * compares {@link #preservesEndpoints} before and after over both ends at once.
 * That is sound there and nowhere else, because that stage never touches a
 * terminal bendpoint — it separates a corridor by inserting a drop bendpoint —
 * so the verdict must be invariant and a change of verdict is a logic-bug
 * signal rather than a policy decision. Do not read it as the family's rule.
 */
public record TerminalAnchoring(Face face) {

    public enum Axis { X, Y }

    public Axis parallelAxis() {
        return (face == Face.LEFT || face == Face.RIGHT) ? Axis.Y : Axis.X;
    }

    public Axis orthogonalAxis() {
        return (face == Face.LEFT || face == Face.RIGHT) ? Axis.X : Axis.Y;
    }

    /**
     * Absolute-canvas coordinate of the face line, one pixel outside the source rect
     * (e.g., LEFT &rarr; {@code source.x() - 1}, RIGHT &rarr; {@code source.x() + source.width() + 1}).
     */
    public int lineCoordinate(RoutingRect source) {
        return switch (face) {
            case LEFT   -> source.x() - 1;
            case RIGHT  -> source.x() + source.width() + 1;
            case TOP    -> source.y() - 1;
            case BOTTOM -> source.y() + source.height() + 1;
        };
    }

    /**
     * Axis-agnostic form.
     *
     * <p>Rejects paths whose {@code path[0]} has left the face line, and nothing else.
     * Legitimate distributed slots (off-centre along the face) and classic centre-face exits
     * (on-centre along the face) both stay on the line and are both preserved; where the terminal
     * sits along the face is not this predicate's business. Empty paths return {@code true}
     * (fast-path).
     *
     * <p>An earlier form also rejected a {@code path[0]} collinear with the source centre on the
     * parallel axis — the ChopboxAnchor degeneracy signature — which is why this took a source
     * centre. That clause was replaced by the face-line test above, which subsumes it, and the
     * parameter went with it.
     */
    public static boolean preservesTerminalAnchoring(
            TerminalAnchoring before,
            RoutingRect source,
            List<AbsoluteBendpointDto> afterPath) {
        if (afterPath.isEmpty()) {
            return true;
        }
        AbsoluteBendpointDto bp0 = afterPath.get(0);
        Axis orthogonal = before.orthogonalAxis();
        int bp0Orthogonal = (orthogonal == Axis.X) ? bp0.x() : bp0.y();
        int faceLine      = before.lineCoordinate(source);
        // bp[0] must remain on the face line. The original
        // degeneracy-only check allowed lateral displacement when bp0's
        // parallel coordinate was nudged off centerY by the
        // slot-at-center fix. No wrap site should ever drag bp[0] off
        // the perimeter.
        return bp0Orthogonal == faceLine;
    }

    /**
     * Convenience wrap-site helper: checks {@link #preservesTerminalAnchoring} on
     * both terminal ends of {@code afterPath}. The source-side check runs against
     * {@code afterPath.get(0)}; the target-side check runs against
     * {@code afterPath.get(last)} by reusing the source-side predicate on a
     * reversed view.
     *
     * <p>Either anchoring may be {@code null}, in which case that end is
     * unchecked — this is the test-compat path used by legacy overloads of the
     * five wrap sites in {@link PathStraightener} and
     * {@link CoincidentSegmentDetector#applyOffsets} so that legacy callers
     * remain green when no anchoring is supplied.
     *
     * <p>{@code sourceCenter} and {@code targetCenter} are <strong>not</strong> passed on to the
     * predicate, which no longer takes them. They are retained here as part of that null guard,
     * and only as that: a legacy caller supplies no centre and its end must go on being skipped.
     * Dropping them would arm a check those callers deliberately do not run, which is a behaviour
     * change and not this one.
     */
    public static boolean preservesEndpoints(
            TerminalAnchoring sourceAnchoring, RoutingRect source, int[] sourceCenter,
            TerminalAnchoring targetAnchoring, RoutingRect target, int[] targetCenter,
            List<AbsoluteBendpointDto> afterPath) {
        if (sourceAnchoring != null && source != null && sourceCenter != null) {
            if (!preservesTerminalAnchoring(sourceAnchoring, source, afterPath)) {
                return false;
            }
        }
        if (targetAnchoring != null && target != null && targetCenter != null
                && !afterPath.isEmpty()) {
            List<AbsoluteBendpointDto> reversed = new ArrayList<>(afterPath);
            Collections.reverse(reversed);
            if (!preservesTerminalAnchoring(targetAnchoring, target, reversed)) {
                return false;
            }
        }
        return true;
    }
}
