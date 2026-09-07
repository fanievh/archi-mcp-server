package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator.Face;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * TerminalAnchoring path-straightener invariant: parameterised
 * generator matrix.
 *
 * <p><strong>Membership rule:</strong>
 *
 * <blockquote>
 * This predicate is enforced exclusively at {@code path[0]} / {@code path[last]}
 * mutator sites within {@link PathStraightener} and
 * {@link CoincidentSegmentDetector#applyOffsets}. Stages that legitimately
 * shape terminal bendpoints by contract (4.7h/k/m/o) are outside this
 * predicate's domain by construction.
 * </blockquote>
 *
 * <p>The generator builds 80 rows = 4 faces × 4 rect shapes × 5 centerline
 * offsets, plus 1 hand-pinned V4 Relationship Manager LEFT slot 3/7 row, for
 * a total of 81 rows. Each row is dispatched through all five {@link WrapSite}
 * enum constants via {@link TerminalAnchoringAssertion}, yielding
 * 5 × 81 = <strong>405 parameterised assertions</strong>.
 *
 * <p>The cartesian dispatch is the test-side
 * encoding of the wrap-site rule: any future sixth mutator that joins the
 * rule auto-qualifies through {@link WrapSite} without scope renegotiation.
 *
 * <p><strong>Scope: the predicate, not the rollback policy.</strong> Every
 * assertion here calls {@link TerminalAnchoringAssertion#preservesEndpoints},
 * which forwards to the predicate and never invokes a mutator, and the helper
 * returns the same verdict for all five {@link WrapSite} constants. The matrix
 * therefore pins the claim that the five sites share one predicate — it
 * contributes no discrimination between them, and it cannot observe what any
 * site does with a negative verdict. All five now roll back on a per-end
 * true-to-false flip, three of them also refusing to move a terminal that
 * arrived off-face ({@code SNAP_TO_STRAIGHT},
 * {@code COLLAPSE_STAIRCASE_JOGS}, {@code APPLY_OFFSETS}); <em>none of that is
 * visible from here</em>. The policy is pinned separately, by driving the
 * mutators — {@code TerminalAnchoringRollbackPolicyTest} for the four
 * {@link PathStraightener} sites, {@code CoincidentSegmentDetectorTest} for
 * {@code APPLY_OFFSETS}.
 */
public class ChopboxAnchorDegeneracyTest {

    /** Generator row + expected predicate verdict. */
    private record Row(
            String label,
            RoutingRect source, int[] sourceCenter,
            TerminalAnchoring anchoring,
            List<AbsoluteBendpointDto> path,
            boolean expectReject) {}

    private enum Shape {
        SMALL(40, 40),
        TALL(20, 80),
        WIDE(80, 20),
        SQUARE(60, 60);

        final int w;
        final int h;

        Shape(int w, int h) {
            this.w = w;
            this.h = h;
        }
    }

    /** Five centerline offsets. */
    private static final int[] CENTERLINE_OFFSETS = {0, -1, 1, -50, 50};

    /**
     * Builds the 80 generator rows. By construction each generator row is a
     * legitimate slot — bp[0] sits on the face line at {@code (centerline +
     * offset)}, so the predicate must PRESERVE for every row. The 1 V4
     * hand-pinned row exercises the only REJECTION path in this matrix.
     */
    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>(81);
        for (Face face : Face.values()) {
            for (Shape shape : Shape.values()) {
                for (int offset : CENTERLINE_OFFSETS) {
                    rows.add(buildGeneratorRow(face, shape, offset));
                }
            }
        }
        // 1 hand-pinned V4 Rel Mgr LEFT slot 3/7 row (Task 5.5)
        rows.add(buildV4HandPinnedRow());
        return rows;
    }

    private Row buildGeneratorRow(Face face, Shape shape, int offset) {
        // Anchor each rect at (200, 200) for the parallel-axis arithmetic
        int rx = 200;
        int ry = 200;
        RoutingRect source = new RoutingRect(rx, ry, shape.w, shape.h, "n");
        int[] center = {source.centerX(), source.centerY()};
        TerminalAnchoring anchoring = new TerminalAnchoring(face);

        // Slot coordinate on the face line, applied along the parallel axis
        AbsoluteBendpointDto slot;
        AbsoluteBendpointDto far; // arbitrary second BP — the predicate looks only at bp[0]
        switch (face) {
            case LEFT: {
                int x = source.x() - 1;
                int y = source.centerY() + offset;
                slot = new AbsoluteBendpointDto(x, y);
                far = new AbsoluteBendpointDto(0, y);
                break;
            }
            case RIGHT: {
                int x = source.x() + source.width() + 1;
                int y = source.centerY() + offset;
                slot = new AbsoluteBendpointDto(x, y);
                far = new AbsoluteBendpointDto(1000, y);
                break;
            }
            case TOP: {
                int y = source.y() - 1;
                int x = source.centerX() + offset;
                slot = new AbsoluteBendpointDto(x, y);
                far = new AbsoluteBendpointDto(x, 0);
                break;
            }
            case BOTTOM: {
                int y = source.y() + source.height() + 1;
                int x = source.centerX() + offset;
                slot = new AbsoluteBendpointDto(x, y);
                far = new AbsoluteBendpointDto(x, 1000);
                break;
            }
            default:
                throw new IllegalStateException("Unhandled face: " + face);
        }
        String label = String.format("face=%s shape=%s offset=%+d", face, shape, offset);
        return new Row(label, source, center, anchoring, List.of(slot, far), false);
    }

    /**
     * The headline V4 fixture: source rect (472, 67, 107, 200), Face.LEFT,
     * collapsed path {@code [(364, 167), (364, 559), (144, 559)]}. After a
     * straightener mutator collapses the legitimate distributed slot 3/7
     * out of the path, bp[0] sits on the parallel axis through the source
     * center but NOT on the LEFT face line — the predicate must REJECT.
     */
    private Row buildV4HandPinnedRow() {
        RoutingRect source = new RoutingRect(472, 67, 107, 200, "rel-mgr");
        int[] center = {525, 167};
        return new Row(
                "V4 Rel Mgr LEFT slot 3/7 (hand-pinned)",
                source, center,
                new TerminalAnchoring(Face.LEFT),
                List.of(
                        new AbsoluteBendpointDto(364, 167),
                        new AbsoluteBendpointDto(364, 559),
                        new AbsoluteBendpointDto(144, 559)),
                true);
    }

    // ---------------------------------------------------------------------
    // Cartesian dispatch — 5 wrap sites × 81 rows = 405 assertions
    // ---------------------------------------------------------------------

    @Test
    public void predicateMatrix_5x81_assertions() {
        List<Row> rows = buildRows();
        assertEquals("expected exactly 81 rows (80 generator + 1 hand-pinned)",
                81, rows.size());

        int totalAssertions = 0;
        for (WrapSite site : WrapSite.values()) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                boolean expectedHolds = !row.expectReject();
                boolean actualHolds = TerminalAnchoringAssertion.preservesEndpoints(
                        site,
                        row.anchoring(), row.source(), row.sourceCenter(),
                        null, null, null,
                        row.path());
                assertEquals(
                        String.format("[site=%s row#%d %s] predicate verdict mismatch",
                                site, i, row.label()),
                        expectedHolds, actualHolds);
                totalAssertions++;
            }
        }
        assertEquals("total assertion count must be 5 wrap sites × 81 rows",
                405, totalAssertions);
    }
}
