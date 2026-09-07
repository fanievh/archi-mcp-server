package net.vheerden.archi.mcp.model.routing;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic helper: categorises each coincident segment by its location in the
 * view (terminal approach, inter-group gap crossing, within-group, or uncategorised).
 *
 * <p>Purpose: support successor scoping. After a spike showed that inter-group gap
 * Y-levels are already fully diversified by the corridor occupancy tracker, we need to
 * know where the remaining coincident segments actually live. This classifier
 * tags each segment so we can tally categories and scope the right successor
 * work (hub-resize / terminal-cluster guard / bounded intra-group re-grouping).</p>
 *
 * <p>Pure-geometry, no EMF/SWT dependencies. Caller builds rectangles from its
 * own source of truth (e.g. LayoutQualityAssessor builds them from
 * AssessmentNode).</p>
 *
 * <p>Activation: gated by caller. Typical pattern:
 * <pre>{@code
 * if (Boolean.getBoolean("archi.mcp.diag.coincident")) {
 *     CoincidentSegmentDiagnostic.emit(pairs, sources, targets, groups);
 * }
 * }</pre></p>
 */
public final class CoincidentSegmentDiagnostic {

    private static final Logger logger = LoggerFactory.getLogger(CoincidentSegmentDiagnostic.class);

    /** Distance (px) within which an endpoint counts as a terminal approach. */
    static final int TERMINAL_APPROACH_DISTANCE = 30;

    /** Category assigned to a single segment. */
    public enum Category {
        TERMINAL_APPROACH,
        GAP_CROSSING,
        WITHIN_GROUP,
        UNCATEGORIZED
    }

    /** Bounding box of an element (for terminal-proximity check). */
    public record ElementRect(String id, double x, double y, double width, double height) {
        double right() { return x + width; }
        double bottom() { return y + height; }
    }

    /** Bounding box of a top-level group (for gap-crossing / within-group check). */
    public record GroupRect(String id, double x, double y, double width, double height) {
        double right() { return x + width; }
        double bottom() { return y + height; }
        boolean contains(double px, double py) {
            return px >= x && px <= right() && py >= y && py <= bottom();
        }
    }

    private CoincidentSegmentDiagnostic() {}

    /**
     * Classifies a single segment. Priority order:
     * <ol>
     *   <li>TERMINAL_APPROACH — either endpoint within {@code TERMINAL_APPROACH_DISTANCE}
     *       of the segment's connection source or target element rectangle.</li>
     *   <li>GAP_CROSSING — endpoints in two different top-level groups (or one inside,
     *       one outside any group), implying the segment crosses an inter-group gap.</li>
     *   <li>WITHIN_GROUP — both endpoints inside the SAME top-level group.</li>
     *   <li>UNCATEGORIZED — fallback (no groups present, or segment is entirely
     *       outside all groups on both endpoints).</li>
     * </ol>
     *
     * @param seg the segment to classify
     * @param source owning connection's source element (nullable)
     * @param target owning connection's target element (nullable)
     * @param topLevelGroups top-level groups (parentId == null, isGroup == true)
     * @return the category
     */
    public static Category classify(PathOrderer.Segment seg,
                                    ElementRect source,
                                    ElementRect target,
                                    List<GroupRect> topLevelGroups) {
        if (isTerminalApproach(seg, source) || isTerminalApproach(seg, target)) {
            return Category.TERMINAL_APPROACH;
        }

        GroupRect groupAtStart = findContainingGroup(seg.x1(), seg.y1(), topLevelGroups);
        GroupRect groupAtEnd = findContainingGroup(seg.x2(), seg.y2(), topLevelGroups);

        if (groupAtStart != null && groupAtEnd != null
                && groupAtStart.id().equals(groupAtEnd.id())) {
            return Category.WITHIN_GROUP;
        }

        // Endpoints in different groups, or one inside / one outside → crosses a gap.
        if ((groupAtStart != null) != (groupAtEnd != null)
                || (groupAtStart != null && !groupAtStart.id().equals(groupAtEnd.id()))) {
            return Category.GAP_CROSSING;
        }

        return Category.UNCATEGORIZED;
    }

    /**
     * Runs coincident-pair detection and emits one log line per pair at INFO
     * level, followed by a tally summary. Unconditional — gate via system
     * property at the call site.
     *
     * <p>Hides the package-private {@code CoincidentPair} and {@code Segment}
     * types from callers in other packages (e.g. LayoutQualityAssessor).</p>
     *
     * @param detector      the coincident segment detector (reuses its pair-extraction logic)
     * @param connections   connections to analyse (implements CoincidentAssessable)
     * @param connectionIds connection index → connection ID (for log readability)
     * @param sources       connection index → source element rect
     * @param targets       connection index → target element rect
     * @param topLevelGroups top-level group rectangles
     */
    public static void emit(CoincidentSegmentDetector detector,
                            List<? extends CoincidentSegmentDetector.CoincidentAssessable> connections,
                            Map<Integer, String> connectionIds,
                            Map<Integer, ElementRect> sources,
                            Map<Integer, ElementRect> targets,
                            List<GroupRect> topLevelGroups) {
        List<CoincidentSegmentDetector.CoincidentPair> pairs = detector.detectPairs(connections);
        logger.info("=== Coincident-segment categorization (pairs={}) ===", pairs.size());
        int[] counts = new int[Category.values().length];

        for (int i = 0; i < pairs.size(); i++) {
            CoincidentSegmentDetector.CoincidentPair pair = pairs.get(i);
            PathOrderer.Segment a = pair.segA();
            PathOrderer.Segment b = pair.segB();

            Category catA = classify(a, sources.get(a.connectionIndex()),
                    targets.get(a.connectionIndex()), topLevelGroups);
            Category catB = classify(b, sources.get(b.connectionIndex()),
                    targets.get(b.connectionIndex()), topLevelGroups);

            // Tally by the stricter of the two (TERMINAL_APPROACH > GAP_CROSSING > WITHIN_GROUP > UNCATEGORIZED).
            Category pairCat = stricter(catA, catB);
            counts[pairCat.ordinal()]++;

            logger.info("pair#{} orient={} coord={} overlap=[{}..{}] "
                            + "segA(conn={} idx={} {},{}->{},{}) cat={} | "
                            + "segB(conn={} idx={} {},{}->{},{}) cat={} | pairCat={}",
                    i,
                    a.horizontal() ? "H" : "V",
                    a.sharedCoordinate(),
                    pair.overlapStart(), pair.overlapEnd(),
                    connIdOrIdx(connectionIds, a.connectionIndex()), a.segmentIndex(),
                    a.x1(), a.y1(), a.x2(), a.y2(), catA,
                    connIdOrIdx(connectionIds, b.connectionIndex()), b.segmentIndex(),
                    b.x1(), b.y1(), b.x2(), b.y2(), catB,
                    pairCat);
        }

        logger.info("=== Coincident-segment diagnostic: tally TERMINAL_APPROACH={} GAP_CROSSING={} WITHIN_GROUP={} UNCATEGORIZED={} ===",
                counts[Category.TERMINAL_APPROACH.ordinal()],
                counts[Category.GAP_CROSSING.ordinal()],
                counts[Category.WITHIN_GROUP.ordinal()],
                counts[Category.UNCATEGORIZED.ordinal()]);
    }

    static boolean isTerminalApproach(PathOrderer.Segment seg, ElementRect rect) {
        if (rect == null) return false;
        return distanceToRect(seg.x1(), seg.y1(), rect) <= TERMINAL_APPROACH_DISTANCE
                || distanceToRect(seg.x2(), seg.y2(), rect) <= TERMINAL_APPROACH_DISTANCE;
    }

    static double distanceToRect(double px, double py, ElementRect rect) {
        double dx = Math.max(0, Math.max(rect.x() - px, px - rect.right()));
        double dy = Math.max(0, Math.max(rect.y() - py, py - rect.bottom()));
        return Math.sqrt(dx * dx + dy * dy);
    }

    static GroupRect findContainingGroup(double px, double py, List<GroupRect> groups) {
        for (GroupRect g : groups) {
            if (g.contains(px, py)) return g;
        }
        return null;
    }

    private static Category stricter(Category a, Category b) {
        return a.ordinal() < b.ordinal() ? a : b;
    }

    private static String connIdOrIdx(Map<Integer, String> ids, int idx) {
        String id = ids.get(idx);
        return id != null ? id : ("#" + idx);
    }
}
