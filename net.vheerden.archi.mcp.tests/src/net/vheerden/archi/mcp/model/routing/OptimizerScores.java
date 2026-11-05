package net.vheerden.archi.mcp.model.routing;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Test bridge to the label optimizer's package-private total score, for tests outside this package that check
 * it against the layout-quality assessor.
 */
public final class OptimizerScores {

    private OptimizerScores() {
    }

    /** {@link LabelPositionOptimizer#computeTotalScore} for the given positions. */
    public static double totalScore(Map<String, Integer> positions,
            List<RoutingPipeline.ConnectionEndpoints> connections, List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> obstacles, Map<String, Set<String>> excludeSets) {
        return new LabelPositionOptimizer().computeTotalScore(positions, connections, paths, obstacles,
                excludeSets);
    }

    /**
     * The box the optimizer scores a Middle label in with {@code anchor} applied: its scored rect, moved where
     * Archi draws that anchor ({@link LabelOffsetDirection#drawn(RoutingRect, int)}).
     */
    public static RoutingRect drawnMiddleLabel(List<AbsoluteBendpointDto> path, RoutingRect source,
            RoutingRect target, String labelText, int anchor) {
        return LabelOffsetDirection.drawn(LabelPositionOptimizer.scoredLabelRect(path, source, target, labelText,
                LabelPositionOptimizer.TEXT_POSITION_MIDDLE), anchor);
    }

    /** {@link LabelPositionOptimizer#insetRectOverlap}: the optimizer's test for a label on a box. */
    public static boolean onBox(RoutingRect label, RoutingRect box) {
        return LabelPositionOptimizer.insetRectOverlap(label, box);
    }
}
