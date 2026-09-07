package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.List;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;

/**
 * Finds the (connection, note) pairs whose routed path penetrates a note.
 *
 * <p>A note is excluded from the A* obstacle set on purpose — notes are often large, and treating
 * one as solid would over-constrain routing — so a connection IS routed straight through a note
 * that sits in its corridor. That is deliberate, published behaviour and this class does not
 * change it. What this class produces is the <strong>disclosure</strong>: the pairs a caller needs
 * in order to decide whether to move a note, available on the call that created the crossing
 * instead of only from a later {@code assess-layout} whose ids live in prose.
 *
 * <p><strong>The geometry is the assessor's, deliberately, and not the router's.</strong> Detection
 * goes through {@link GeometryUtils#clipPathToRectEdges} and
 * {@link GeometryUtils#pathPassesThroughRect} — the same perimeter clip and inward inset that
 * {@code LayoutQualityAssessor} applies for {@code connectionThroughNoteCount}. The router's own
 * obstacle geometry grows a rectangle OUTWARD by its margin instead, so the two disagree by twice
 * the constant on every side: a route passing a few pixels outside a note is a crossing to the
 * router and clean to the assessor. Reporting the router's answer beside the assessor's metric
 * would tell a caller that one of its two instruments is wrong without telling it which, so this
 * class follows the metric.
 *
 * <p>The perimeter clip is not optional and is applied here rather than trusted to callers: a path
 * built as {@code [sourceCentre, …bendpoints…, targetCentre]} has first and last segments running
 * through the interiors of its own endpoints, which are never drawn. Left unclipped they
 * manufacture crossings for any note overlapping an endpoint — precisely where notes tend to sit.
 *
 * <p><strong>Scope boundary.</strong> {@code auto-layout-and-route} routes through the same
 * pipeline and therefore produces these crossings too, but does not call this detector yet.
 * Reporting them there means carrying a new field through that tool's own pass result across
 * several execution paths and modes, which is owned separately and deliberately not fused with
 * this one. This class lives here, in pure geometry with no EMF and no assessment types, so that
 * adopting it there is a call rather than a move.
 */
public final class ConnectionThroughNoteDetector {

    private ConnectionThroughNoteDetector() {}

    /** A note's rectangle in absolute canvas coordinates. */
    public record NoteRect(String id, double x, double y, double width, double height) {}

    /**
     * One connection's routed path, still centre-anchored.
     *
     * @param points     {@code [sourceCentre, …bendpoints…, targetCentre]}; clipped internally
     * @param sourceRect {@code [x, y, width, height]} of the source element, or null if unresolved
     * @param targetRect {@code [x, y, width, height]} of the target element, or null if unresolved
     */
    public record RoutedPath(String connectionId, List<double[]> points,
                             double[] sourceRect, double[] targetRect) {}

    /** One crossing. A connection crossing three notes yields three of these. */
    public record NoteCrossing(String connectionId, String noteId) {}

    /**
     * Returns every (connection, note) pair whose clipped path penetrates the note's inset
     * interior.
     *
     * <p>Enumerated per pair and never short-circuited on the first hit for a given connection —
     * the same cardinality {@code assess-layout} counts, so the two can be compared directly.
     * Ordering is deterministic: connections in the order supplied, notes in the order supplied.
     *
     * @param inset the inward shrink; pass the assessor's pass-through inset to stay in agreement
     */
    public static List<NoteCrossing> detect(List<RoutedPath> paths, List<NoteRect> notes,
                                            double inset) {
        List<NoteCrossing> crossings = new ArrayList<>();
        if (paths == null || notes == null || notes.isEmpty()) {
            return crossings;
        }
        for (RoutedPath path : paths) {
            if (path.points() == null || path.points().size() < 2) {
                continue;
            }
            List<double[]> clipped = GeometryUtils.clipPathToRectEdges(
                    path.points(), path.sourceRect(), path.targetRect());
            for (NoteRect note : notes) {
                if (GeometryUtils.pathPassesThroughRect(clipped,
                        note.x(), note.y(), note.width(), note.height(), inset)) {
                    crossings.add(new NoteCrossing(path.connectionId(), note.id()));
                }
            }
        }
        return crossings;
    }
}
