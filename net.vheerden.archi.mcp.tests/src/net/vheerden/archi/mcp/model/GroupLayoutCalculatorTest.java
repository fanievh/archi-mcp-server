package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link GroupLayoutCalculator} — pure geometry group layout
 * position computation (Story B30). No EMF or SWT runtime required.
 */
public class GroupLayoutCalculatorTest {

    // ---- computeAutoWidth ----

    @Test
    public void computeAutoWidth_shouldReturnDefaultForNullName() {
        assertEquals(GroupLayoutCalculator.DEFAULT_ELEMENT_WIDTH,
                GroupLayoutCalculator.computeAutoWidth(null));
    }

    @Test
    public void computeAutoWidth_shouldReturnDefaultForEmptyName() {
        assertEquals(GroupLayoutCalculator.DEFAULT_ELEMENT_WIDTH,
                GroupLayoutCalculator.computeAutoWidth(""));
    }

    @Test
    public void computeAutoWidth_shouldComputeFromLabelLength() {
        // "Business Actor" = 14 chars → 14*8 + 30 = 142
        int width = GroupLayoutCalculator.computeAutoWidth("Business Actor");
        assertEquals(142, width);
    }

    @Test
    public void computeAutoWidth_shouldApplyMinFloor() {
        // "Hi" = 2 chars → 2*8 + 30 = 46 → MIN_AUTO_WIDTH (60) floor
        int width = GroupLayoutCalculator.computeAutoWidth("Hi");
        assertEquals(GroupLayoutCalculator.MIN_AUTO_WIDTH, width);
    }

    // ---- Row layout with spacing ----

    @Test
    public void rowLayout_shouldHonorSpacing100() {
        // AC-1, AC-6: spacing=100 produces 100px gaps
        List<int[]> sizes = List.of(
                new int[]{120, 55},
                new int[]{150, 55},
                new int[]{100, 55});

        List<int[]> positions = GroupLayoutCalculator.computeRowLayout(sizes, 10, 34, 100);

        assertEquals(3, positions.size());
        // Element 0: x=10
        assertEquals(10, positions.get(0)[0]);
        assertEquals(120, positions.get(0)[2]); // width preserved
        // Element 1: x = 10 + 120 + 100 = 230
        assertEquals(230, positions.get(1)[0]);
        // Gap between element 0 right edge (10+120=130) and element 1 left edge (230) = 100
        int gap01 = positions.get(1)[0] - (positions.get(0)[0] + positions.get(0)[2]);
        assertEquals(100, gap01);
        // Element 2: x = 230 + 150 + 100 = 480
        assertEquals(480, positions.get(2)[0]);
        int gap12 = positions.get(2)[0] - (positions.get(1)[0] + positions.get(1)[2]);
        assertEquals(100, gap12);
    }

    @Test
    public void rowLayout_shouldPreserveHeights() {
        List<int[]> sizes = List.of(
                new int[]{120, 55},
                new int[]{120, 70});

        List<int[]> positions = GroupLayoutCalculator.computeRowLayout(sizes, 10, 34, 40);

        assertEquals(55, positions.get(0)[3]);
        assertEquals(70, positions.get(1)[3]);
    }

    @Test
    public void rowLayout_shouldUseStartCoordinates() {
        List<int[]> sizes = List.of(new int[]{100, 50});
        List<int[]> positions = GroupLayoutCalculator.computeRowLayout(sizes, 20, 44, 40);

        assertEquals(20, positions.get(0)[0]);
        assertEquals(44, positions.get(0)[1]);
    }

    // ---- Column layout with spacing ----

    @Test
    public void columnLayout_shouldHonorSpacing100() {
        List<int[]> sizes = List.of(
                new int[]{120, 55},
                new int[]{120, 70},
                new int[]{120, 55});

        List<int[]> positions = GroupLayoutCalculator.computeColumnLayout(sizes, 10, 34, 100);

        assertEquals(3, positions.size());
        // Element 0: y=34
        assertEquals(34, positions.get(0)[1]);
        // Element 1: y = 34 + 55 + 100 = 189
        assertEquals(189, positions.get(1)[1]);
        int gap01 = positions.get(1)[1] - (positions.get(0)[1] + positions.get(0)[3]);
        assertEquals(100, gap01);
        // Element 2: y = 189 + 70 + 100 = 359
        assertEquals(359, positions.get(2)[1]);
        int gap12 = positions.get(2)[1] - (positions.get(1)[1] + positions.get(1)[3]);
        assertEquals(100, gap12);
    }

    @Test
    public void columnLayout_shouldUseConstantX() {
        List<int[]> sizes = List.of(
                new int[]{120, 55},
                new int[]{150, 55});

        List<int[]> positions = GroupLayoutCalculator.computeColumnLayout(sizes, 10, 34, 40);

        assertEquals(10, positions.get(0)[0]);
        assertEquals(10, positions.get(1)[0]);
    }

    // ---- Grid layout with spacing ----

    @Test
    public void gridLayout_shouldHonorSpacing80InBothAxes() {
        // AC-2, AC-6: grid with spacing=80 produces 80px gaps in both axes
        // 4 elements in 2 columns
        List<int[]> sizes = List.of(
                new int[]{100, 50},
                new int[]{100, 50},
                new int[]{100, 50},
                new int[]{100, 50});

        GroupLayoutCalculator.GridLayoutResult result =
                GroupLayoutCalculator.computeGridLayout(sizes, 10, 34, 80, 10, 500, 2);

        assertEquals(2, result.columnsUsed());
        List<int[]> positions = result.positions();
        assertEquals(4, positions.size());

        // Row 0: x=10, x=10+100+80=190
        assertEquals(10, positions.get(0)[0]);
        assertEquals(190, positions.get(1)[0]);
        // Horizontal gap = 190 - (10 + 100) = 80
        int hGap = positions.get(1)[0] - (positions.get(0)[0] + positions.get(0)[2]);
        assertEquals(80, hGap);

        // Row 1: y=34+50+80=164
        assertEquals(164, positions.get(2)[1]);
        // Vertical gap = 164 - (34 + 50) = 80
        int vGap = positions.get(2)[1] - (positions.get(0)[1] + positions.get(0)[3]);
        assertEquals(80, vGap);
    }

    @Test
    public void gridLayout_shouldAutoDetectColumns() {
        // Group width 500, padding 10, available = 480
        // Element width 100, spacing 40 → (480 + 40) / (100 + 40) = 520/140 = 3
        List<int[]> sizes = List.of(
                new int[]{100, 50},
                new int[]{100, 50},
                new int[]{100, 50},
                new int[]{100, 50},
                new int[]{100, 50});

        GroupLayoutCalculator.GridLayoutResult result =
                GroupLayoutCalculator.computeGridLayout(sizes, 10, 34, 40, 10, 500, null);

        assertEquals(3, result.columnsUsed());
    }

    @Test
    public void gridLayout_shouldCapColumnsAtElementCount() {
        List<int[]> sizes = List.of(
                new int[]{100, 50},
                new int[]{100, 50});

        GroupLayoutCalculator.GridLayoutResult result =
                GroupLayoutCalculator.computeGridLayout(sizes, 10, 34, 40, 10, 500, 5);

        assertEquals(2, result.columnsUsed());
    }

    @Test
    public void gridLayout_shouldUseUniformMaxWidth() {
        // Elements with different widths should all get max width in grid
        List<int[]> sizes = List.of(
                new int[]{80, 50},
                new int[]{120, 50},
                new int[]{100, 50});

        GroupLayoutCalculator.GridLayoutResult result =
                GroupLayoutCalculator.computeGridLayout(sizes, 10, 34, 40, 10, 600, 3);

        // All elements should have width 120 (the max)
        for (int[] pos : result.positions()) {
            assertEquals(120, pos[2]);
        }
    }

    // ---- Default spacing (40px) ----

    @Test
    public void rowLayout_defaultSpacing40_shouldProduceCorrectGaps() {
        // AC-4, AC-6: default spacing (40) produces 40px gaps
        List<int[]> sizes = List.of(
                new int[]{120, 55},
                new int[]{120, 55});

        List<int[]> positions = GroupLayoutCalculator.computeRowLayout(sizes, 10, 34, 40);

        int gap = positions.get(1)[0] - (positions.get(0)[0] + positions.get(0)[2]);
        assertEquals(40, gap);
    }

    @Test
    public void columnLayout_defaultSpacing40_shouldProduceCorrectGaps() {
        List<int[]> sizes = List.of(
                new int[]{120, 55},
                new int[]{120, 55});

        List<int[]> positions = GroupLayoutCalculator.computeColumnLayout(sizes, 10, 34, 40);

        int gap = positions.get(1)[1] - (positions.get(0)[1] + positions.get(0)[3]);
        assertEquals(40, gap);
    }

    @Test
    public void gridLayout_defaultSpacing40_shouldProduceCorrectGaps() {
        // AC-4, AC-6: grid with default spacing (40) produces 40px gaps
        List<int[]> sizes = List.of(
                new int[]{100, 50},
                new int[]{100, 50},
                new int[]{100, 50},
                new int[]{100, 50});

        GroupLayoutCalculator.GridLayoutResult result =
                GroupLayoutCalculator.computeGridLayout(sizes, 10, 34, 40, 10, 500, 2);

        List<int[]> positions = result.positions();
        // Horizontal gap: 10+100+40=150 → gap = 150 - (10+100) = 40
        int hGap = positions.get(1)[0] - (positions.get(0)[0] + positions.get(0)[2]);
        assertEquals(40, hGap);
        // Vertical gap: 34+50+40=124 → gap = 124 - (34+50) = 40
        int vGap = positions.get(2)[1] - (positions.get(0)[1] + positions.get(0)[3]);
        assertEquals(40, vGap);
    }

    // ---- Auto-resize ----

    @Test
    public void autoResize_shouldExpandToFitSpacing() {
        // AC-3, AC-6: group expands to accommodate spacing
        // 3 elements of width 120, spacing 100, padding 10
        // Row: x=10, x=10+120+100=230, x=230+120+100=450
        // Last element right edge: 450+120=570, plus padding=10 → width=580
        List<int[]> sizes = List.of(
                new int[]{120, 55},
                new int[]{120, 55},
                new int[]{120, 55});

        List<int[]> positions = GroupLayoutCalculator.computeRowLayout(sizes, 10, 34, 100);
        int[] dims = GroupLayoutCalculator.computeAutoResizeDimensions(positions, 10);

        // Width: maxRight (450+120=570) + padding (10) = 580
        assertEquals(580, dims[0]);
        // Height: maxBottom (34+55=89) + padding (10) = 99
        assertEquals(99, dims[1]);
    }

    @Test
    public void autoResize_shouldHandleLargeSpacing() {
        // With spacing=200, positions spread much further
        List<int[]> sizes = List.of(
                new int[]{100, 50},
                new int[]{100, 50});

        List<int[]> positions = GroupLayoutCalculator.computeRowLayout(sizes, 10, 34, 200);
        int[] dims = GroupLayoutCalculator.computeAutoResizeDimensions(positions, 10);

        // Element 0: x=10, w=100 → right=110
        // Element 1: x=10+100+200=310, w=100 → right=410
        // Width = 410 + 10(padding) = 420
        assertEquals(420, dims[0]);
    }

    // ---- Edge cases ----

    @Test
    public void rowLayout_singleElement_shouldPlaceAtStart() {
        List<int[]> sizes = List.of(new int[]{120, 55});
        List<int[]> positions = GroupLayoutCalculator.computeRowLayout(sizes, 10, 34, 100);

        assertEquals(1, positions.size());
        assertEquals(10, positions.get(0)[0]);
        assertEquals(34, positions.get(0)[1]);
        assertEquals(120, positions.get(0)[2]);
        assertEquals(55, positions.get(0)[3]);
    }

    @Test
    public void rowLayout_zeroSpacing_shouldPackTightly() {
        List<int[]> sizes = List.of(
                new int[]{100, 50},
                new int[]{100, 50});

        List<int[]> positions = GroupLayoutCalculator.computeRowLayout(sizes, 10, 34, 0);

        assertEquals(10, positions.get(0)[0]);
        assertEquals(110, positions.get(1)[0]); // 10 + 100 + 0
        int gap = positions.get(1)[0] - (positions.get(0)[0] + positions.get(0)[2]);
        assertEquals(0, gap);
    }

    @Test
    public void gridLayout_singleColumn_shouldStackVertically() {
        List<int[]> sizes = List.of(
                new int[]{100, 50},
                new int[]{100, 50},
                new int[]{100, 50});

        GroupLayoutCalculator.GridLayoutResult result =
                GroupLayoutCalculator.computeGridLayout(sizes, 10, 34, 80, 10, 500, 1);

        assertEquals(1, result.columnsUsed());
        // All at x=10, y increments by 50+80=130
        assertEquals(10, result.positions().get(0)[0]);
        assertEquals(34, result.positions().get(0)[1]);
        assertEquals(10, result.positions().get(1)[0]);
        assertEquals(164, result.positions().get(1)[1]); // 34 + 50 + 80
        assertEquals(10, result.positions().get(2)[0]);
        assertEquals(294, result.positions().get(2)[1]); // 164 + 50 + 80
    }

    @Test
    public void rowLayout_emptyList_shouldReturnEmptyPositions() {
        List<int[]> positions = GroupLayoutCalculator.computeRowLayout(
                List.of(), 10, 34, 40);
        assertTrue(positions.isEmpty());
    }

    @Test
    public void columnLayout_emptyList_shouldReturnEmptyPositions() {
        List<int[]> positions = GroupLayoutCalculator.computeColumnLayout(
                List.of(), 10, 34, 40);
        assertTrue(positions.isEmpty());
    }

    @Test
    public void gridLayout_emptyList_shouldReturnEmptyPositions() {
        GroupLayoutCalculator.GridLayoutResult result =
                GroupLayoutCalculator.computeGridLayout(
                        List.of(), 10, 34, 40, 10, 500, 2);
        assertTrue(result.positions().isEmpty());
    }

    @Test
    public void autoResize_shouldComputeFromLargestExtents() {
        // Column layout with varying heights
        List<int[]> sizes = List.of(
                new int[]{120, 40},
                new int[]{150, 60},
                new int[]{100, 50});

        List<int[]> positions = GroupLayoutCalculator.computeColumnLayout(sizes, 10, 34, 40);
        int[] dims = GroupLayoutCalculator.computeAutoResizeDimensions(positions, 10);

        // Width: max(10+120, 10+150, 10+100) = 160, +10 padding = 170
        assertEquals(170, dims[0]);
        // Height: element 2 bottom = (34 + 40 + 40) + (60 + 40) + 50 = 264, +10 padding = 274
        // Let me compute properly:
        // e0: y=34, h=40 → bottom=74
        // e1: y=34+40+40=114, h=60 → bottom=174
        // e2: y=114+60+40=214, h=50 → bottom=264
        // maxBottom=264, +10=274
        assertEquals(274, dims[1]);
    }

    // ---- chooseIntraGroupArrangement (B51) ----

    @Test
    public void chooseIntraGroupArrangement_directionDown_1element_shouldReturnRow() {
        assertEquals("row", GroupLayoutCalculator.chooseIntraGroupArrangement(1, "DOWN"));
    }

    @Test
    public void chooseIntraGroupArrangement_directionDown_3elements_shouldReturnRow() {
        assertEquals("row", GroupLayoutCalculator.chooseIntraGroupArrangement(3, "DOWN"));
    }

    @Test
    public void chooseIntraGroupArrangement_directionDown_4elements_shouldReturnGrid() {
        assertEquals("grid", GroupLayoutCalculator.chooseIntraGroupArrangement(4, "DOWN"));
    }

    @Test
    public void chooseIntraGroupArrangement_directionDown_9elements_shouldReturnGrid() {
        assertEquals("grid", GroupLayoutCalculator.chooseIntraGroupArrangement(9, "DOWN"));
    }

    @Test
    public void chooseIntraGroupArrangement_directionRight_1element_shouldReturnColumn() {
        assertEquals("column", GroupLayoutCalculator.chooseIntraGroupArrangement(1, "RIGHT"));
    }

    @Test
    public void chooseIntraGroupArrangement_directionRight_5elements_shouldReturnColumn() {
        assertEquals("column", GroupLayoutCalculator.chooseIntraGroupArrangement(5, "RIGHT"));
    }

    @Test
    public void chooseIntraGroupArrangement_directionUp_4elements_shouldReturnGrid() {
        assertEquals("grid", GroupLayoutCalculator.chooseIntraGroupArrangement(4, "UP"));
    }

    @Test
    public void chooseIntraGroupArrangement_directionLeft_4elements_shouldReturnColumn() {
        assertEquals("column", GroupLayoutCalculator.chooseIntraGroupArrangement(4, "LEFT"));
    }

    @Test
    public void chooseIntraGroupArrangement_nullDirection_shouldTreatAsDown() {
        // null direction defaults to vertical flow behavior
        assertEquals("row", GroupLayoutCalculator.chooseIntraGroupArrangement(2, null));
        assertEquals("grid", GroupLayoutCalculator.chooseIntraGroupArrangement(5, null));
    }

    @Test
    public void chooseIntraGroupArrangement_directionLowercase_shouldWork() {
        assertEquals("row", GroupLayoutCalculator.chooseIntraGroupArrangement(2, "down"));
        assertEquals("column", GroupLayoutCalculator.chooseIntraGroupArrangement(5, "right"));
        assertEquals("grid", GroupLayoutCalculator.chooseIntraGroupArrangement(4, "up"));
        assertEquals("column", GroupLayoutCalculator.chooseIntraGroupArrangement(4, "left"));
    }

    @Test
    public void chooseIntraGroupArrangement_directionMixedCase_shouldWork() {
        assertEquals("grid", GroupLayoutCalculator.chooseIntraGroupArrangement(6, "Down"));
        assertEquals("column", GroupLayoutCalculator.chooseIntraGroupArrangement(6, "Right"));
    }

    // ---- computeGridColumns (B51) ----

    @Test
    public void computeGridColumns_0elements_shouldReturn1() {
        // Guard: elementCount < 4 returns max(1, count)
        assertEquals(1, GroupLayoutCalculator.computeGridColumns(0));
    }

    @Test
    public void computeGridColumns_1element_shouldReturn1() {
        assertEquals(1, GroupLayoutCalculator.computeGridColumns(1));
    }

    @Test
    public void computeGridColumns_3elements_shouldReturn3() {
        assertEquals(3, GroupLayoutCalculator.computeGridColumns(3));
    }

    @Test
    public void computeGridColumns_4elements_shouldReturn2columns() {
        // 4 elements → 2 rows → ceil(4/2) = 2 columns
        assertEquals(2, GroupLayoutCalculator.computeGridColumns(4));
    }

    @Test
    public void computeGridColumns_5elements_shouldReturn3columns() {
        // 5 elements → 2 rows → ceil(5/2) = 3 columns
        assertEquals(3, GroupLayoutCalculator.computeGridColumns(5));
    }

    @Test
    public void computeGridColumns_8elements_shouldReturn4columns() {
        // 8 elements → 2 rows → ceil(8/2) = 4 columns
        assertEquals(4, GroupLayoutCalculator.computeGridColumns(8));
    }

    @Test
    public void computeGridColumns_9elements_shouldReturn3columns() {
        // 9 elements → 3 rows → ceil(9/3) = 3 columns
        assertEquals(3, GroupLayoutCalculator.computeGridColumns(9));
    }

    @Test
    public void computeGridColumns_12elements_shouldReturn4columns() {
        // 12 elements → 3 rows → ceil(12/3) = 4 columns
        assertEquals(4, GroupLayoutCalculator.computeGridColumns(12));
    }

    @Test
    public void computeGridColumns_15elements_shouldReturn5columns() {
        // 15 elements → 3 rows → ceil(15/3) = 5 columns
        assertEquals(5, GroupLayoutCalculator.computeGridColumns(15));
    }

    // ---- validateGroupGaps ----

    @Test
    public void validateGaps_noOverlap_shouldReturnTrue() {
        // Two groups side by side with gap
        List<int[]> rects = List.of(
                new int[]{0, 0, 100, 100},
                new int[]{150, 0, 100, 100});

        assertTrue(GroupLayoutCalculator.validateGroupGaps(rects));
    }

    @Test
    public void validateGaps_partialOverlap_shouldReturnFalse() {
        // Second group overlaps the first by 20px
        List<int[]> rects = List.of(
                new int[]{0, 0, 100, 100},
                new int[]{80, 0, 100, 100});

        assertFalse(GroupLayoutCalculator.validateGroupGaps(rects));
    }

    @Test
    public void validateGaps_exactEdgeTouching_shouldReturnTrue() {
        // Groups share an edge but don't overlap (consistent with LayoutQualityAssessor AABB)
        List<int[]> rects = List.of(
                new int[]{0, 0, 100, 100},
                new int[]{100, 0, 100, 100});

        assertTrue(GroupLayoutCalculator.validateGroupGaps(rects));
    }

    @Test
    public void validateGaps_completeContainment_shouldReturnFalse() {
        // One group completely inside another (still an overlap geometrically)
        List<int[]> rects = List.of(
                new int[]{0, 0, 300, 300},
                new int[]{50, 50, 100, 100});

        assertFalse(GroupLayoutCalculator.validateGroupGaps(rects));
    }

    @Test
    public void validateGaps_singleGroup_shouldReturnTrue() {
        List<int[]> rects = List.of(new int[]{0, 0, 100, 100});
        assertTrue(GroupLayoutCalculator.validateGroupGaps(rects));
    }

    @Test
    public void validateGaps_emptyList_shouldReturnTrue() {
        assertTrue(GroupLayoutCalculator.validateGroupGaps(new ArrayList<>()));
    }

    @Test
    public void validateGaps_threeGroups_oneOverlap_shouldReturnFalse() {
        // Groups A and C overlap, B is separate
        List<int[]> rects = List.of(
                new int[]{0, 0, 100, 100},      // A
                new int[]{200, 0, 100, 100},     // B (no overlap)
                new int[]{50, 50, 100, 100});    // C (overlaps A)

        assertFalse(GroupLayoutCalculator.validateGroupGaps(rects));
    }

    @Test
    public void validateGaps_verticalOverlap_shouldReturnFalse() {
        // Groups overlap vertically but not horizontally... wait, they do overlap:
        // Group A at (0,0,100,100), Group B at (0,80,100,100)
        List<int[]> rects = List.of(
                new int[]{0, 0, 100, 100},
                new int[]{0, 80, 100, 100});

        assertFalse(GroupLayoutCalculator.validateGroupGaps(rects));
    }
}
