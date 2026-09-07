package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link GroupLayoutCalculator} — pure geometry group layout
 * position computation. No EMF or SWT runtime required.
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
        // spacing=100 produces 100px gaps
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
        // grid with spacing=80 produces 80px gaps in both axes
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
        // default spacing (40) produces 40px gaps
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
        // grid with default spacing (40) produces 40px gaps
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
        // group expands to accommodate spacing
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

    // ---- chooseIntraGroupArrangement ----

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

    // ---- computeGridColumns ----

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

    // ---- detectSpacingFromPositions ----

    @Test
    public void detectSpacing_row_shouldDetectMinHorizontalGap() {
        // 3 elements in a row: x=10 w=120, x=230 w=150, x=480 w=100
        // Gaps: 230-(10+120)=100, 480-(230+150)=100
        List<int[]> positions = List.of(
                new int[]{10, 34, 120, 55},
                new int[]{230, 34, 150, 55},
                new int[]{480, 34, 100, 55});

        assertEquals(100, GroupLayoutCalculator.detectSpacingFromPositions(positions, "row"));
    }

    @Test
    public void detectSpacing_row_variableSizes_shouldDetectSmallestGap() {
        // Elements with different widths, gaps of 40 and 60
        List<int[]> positions = List.of(
                new int[]{10, 34, 100, 55},
                new int[]{150, 34, 80, 55},   // gap = 150 - 110 = 40
                new int[]{290, 34, 120, 55});  // gap = 290 - 230 = 60

        assertEquals(40, GroupLayoutCalculator.detectSpacingFromPositions(positions, "row"));
    }

    @Test
    public void detectSpacing_column_shouldDetectMinVerticalGap() {
        // 3 elements in a column: y=34 h=55, y=129 h=70, y=239 h=55
        // Gaps: 129-(34+55)=40, 239-(129+70)=40
        List<int[]> positions = List.of(
                new int[]{10, 34, 120, 55},
                new int[]{10, 129, 120, 70},
                new int[]{10, 239, 120, 55});

        assertEquals(40, GroupLayoutCalculator.detectSpacingFromPositions(positions, "column"));
    }

    @Test
    public void detectSpacing_grid_shouldDetectMinOfBothAxes() {
        // 4 elements in 2x2 grid, horizontal gap=80, vertical gap=60
        List<int[]> positions = List.of(
                new int[]{10, 34, 100, 50},
                new int[]{190, 34, 100, 50},   // hGap = 190-110 = 80
                new int[]{10, 144, 100, 50},   // vGap = 144-84 = 60
                new int[]{190, 144, 100, 50});

        assertEquals(60, GroupLayoutCalculator.detectSpacingFromPositions(positions, "grid"));
    }

    @Test
    public void detectSpacing_singleElement_shouldReturnDefault() {
        List<int[]> positions = List.of(new int[]{10, 34, 120, 55});

        assertEquals(GroupLayoutCalculator.DEFAULT_DETECTED_SPACING,
                GroupLayoutCalculator.detectSpacingFromPositions(positions, "row"));
    }

    @Test
    public void detectSpacing_emptyList_shouldReturnDefault() {
        assertEquals(GroupLayoutCalculator.DEFAULT_DETECTED_SPACING,
                GroupLayoutCalculator.detectSpacingFromPositions(List.of(), "row"));
    }

    // ---- detectPaddingFromPositions ----

    @Test
    public void detectPadding_standardPadding10_shouldDetect10() {
        // Group 300x200, children start at x=10, y=34 (10 + 24 label height)
        // Last child: x=10+120=130 → rightPadding = 300-130=170
        // Bottom child: y=34+55=89 → bottomPadding = 200-89=111
        // leftPadding=10, topPadding=34-24=10
        List<int[]> positions = List.of(new int[]{10, 34, 120, 55});

        assertEquals(10, GroupLayoutCalculator.detectPaddingFromPositions(positions, 300, 200));
    }

    @Test
    public void detectPadding_padding20_shouldDetect20() {
        // Children start at x=20, y=44 (20 + 24), group 400x300
        List<int[]> positions = List.of(
                new int[]{20, 44, 120, 55},
                new int[]{20, 139, 120, 55});  // y=44+55+40=139

        // leftPadding=20, topPadding=44-24=20
        assertEquals(20, GroupLayoutCalculator.detectPaddingFromPositions(positions, 400, 300));
    }

    @Test
    public void detectPadding_emptyPositions_shouldReturnDefault() {
        assertEquals(GroupLayoutCalculator.DEFAULT_DETECTED_PADDING,
                GroupLayoutCalculator.detectPaddingFromPositions(List.of(), 300, 200));
    }

    // ---- computeInterGroupShifts ----

    @Test
    public void interGroupShifts_horizontalGroups_shouldShiftInX() {
        // 3 groups side by side: x=20, x=300, x=580
        List<int[]> groups = List.of(
                new int[]{20, 20, 260, 400},
                new int[]{300, 20, 260, 400},
                new int[]{580, 20, 260, 400});

        List<int[]> shifted = GroupLayoutCalculator.computeInterGroupShifts(groups, 40);

        // Group 0: x stays at 20 (rank 0, shift=0)
        assertEquals(20, shifted.get(0)[0]);
        // Group 1: x = 300 + 1*40 = 340
        assertEquals(340, shifted.get(1)[0]);
        // Group 2: x = 580 + 2*40 = 660
        assertEquals(660, shifted.get(2)[0]);
        // Y unchanged for all
        assertEquals(20, shifted.get(0)[1]);
        assertEquals(20, shifted.get(1)[1]);
        assertEquals(20, shifted.get(2)[1]);
        // Dimensions preserved
        assertEquals(260, shifted.get(0)[2]);
        assertEquals(400, shifted.get(0)[3]);
    }

    @Test
    public void interGroupShifts_verticalGroups_shouldShiftInY() {
        // 3 groups stacked vertically: y=20, y=300, y=580
        List<int[]> groups = List.of(
                new int[]{20, 20, 400, 260},
                new int[]{20, 300, 400, 260},
                new int[]{20, 580, 400, 260});

        List<int[]> shifted = GroupLayoutCalculator.computeInterGroupShifts(groups, 60);

        // X unchanged for all
        assertEquals(20, shifted.get(0)[0]);
        assertEquals(20, shifted.get(1)[0]);
        assertEquals(20, shifted.get(2)[0]);
        // Group 0: y stays at 20
        assertEquals(20, shifted.get(0)[1]);
        // Group 1: y = 300 + 1*60 = 360
        assertEquals(360, shifted.get(1)[1]);
        // Group 2: y = 580 + 2*60 = 700
        assertEquals(700, shifted.get(2)[1]);
    }

    @Test
    public void interGroupShifts_singleGroup_shouldReturnUnchanged() {
        List<int[]> groups = List.of(new int[]{20, 20, 260, 400});

        List<int[]> shifted = GroupLayoutCalculator.computeInterGroupShifts(groups, 40);

        assertEquals(1, shifted.size());
        assertEquals(20, shifted.get(0)[0]);
        assertEquals(20, shifted.get(0)[1]);
    }

    @Test
    public void interGroupShifts_zeroDelta_shouldReturnUnchanged() {
        List<int[]> groups = List.of(
                new int[]{20, 20, 260, 400},
                new int[]{300, 20, 260, 400});

        List<int[]> shifted = GroupLayoutCalculator.computeInterGroupShifts(groups, 0);

        assertEquals(20, shifted.get(0)[0]);
        assertEquals(300, shifted.get(1)[0]);
    }

    @Test
    public void interGroupShifts_unsortedInput_shouldSortByDominantAxis() {
        // Groups provided out of order by x
        List<int[]> groups = List.of(
                new int[]{580, 20, 260, 400},  // rightmost
                new int[]{20, 20, 260, 400},   // leftmost
                new int[]{300, 20, 260, 400}); // middle

        List<int[]> shifted = GroupLayoutCalculator.computeInterGroupShifts(groups, 40);

        // Index 0 (originally x=580) is rank 2 → shift 2*40=80
        assertEquals(660, shifted.get(0)[0]);
        // Index 1 (originally x=20) is rank 0 → shift 0
        assertEquals(20, shifted.get(1)[0]);
        // Index 2 (originally x=300) is rank 1 → shift 1*40=40
        assertEquals(340, shifted.get(2)[0]);
    }

    // ---- detectInterGroupSpacing (RoutingPreconditions.InterGroup) ----

    @Test
    public void detectInterGroupSpacing_twoHorizontalGroupsGap80_shouldDetect80() {
        // Group A at x=0 width=100 (right edge=100), group B at x=180 (gap=80)
        List<int[]> groups = List.of(
                new int[]{0, 0, 100, 200},
                new int[]{180, 0, 100, 200});

        assertEquals(80, GroupLayoutCalculator.detectInterGroupSpacing(groups));
    }

    @Test
    public void detectInterGroupSpacing_threeVerticalGroupsMinGap40_shouldDetect40() {
        // Three groups stacked vertically: gap 60, gap 40 → MIN = 40 wins
        // Y-spread (0..280) > X-spread (0..200) → vertical dominant
        List<int[]> groups = List.of(
                new int[]{0, 0, 200, 100},     // bottom edge=100
                new int[]{0, 160, 200, 80},    // top edge=160 (gap from prev=60)
                new int[]{0, 280, 200, 100});  // top edge=280 (gap from prev=40)

        assertEquals(40, GroupLayoutCalculator.detectInterGroupSpacing(groups));
    }

    @Test
    public void detectInterGroupSpacing_singleGroup_shouldReturnDefault() {
        List<int[]> groups = List.of(new int[]{0, 0, 100, 100});

        assertEquals(GroupLayoutCalculator.DEFAULT_DETECTED_SPACING,
                GroupLayoutCalculator.detectInterGroupSpacing(groups));
    }

    @Test
    public void detectInterGroupSpacing_overlappingGroups_shouldReturnDefault() {
        // Two groups fully overlapping on dominant axis (vertical here, since
        // y-spread=200 > x-spread=180). The single adjacent pair has a
        // negative gap on the dominant axis, which detectMinGapOnAxis skips.
        // With no positive gaps observed, the contract returns
        // DEFAULT_DETECTED_SPACING (sibling-symmetric with
        // detectSpacingFromPositions). Overlap is a degenerate case in
        // practice — Archi's UI doesn't auto-produce overlapping top-level
        // groups, and the convenience tool's heuristic relies on valid
        // layouts. The 40px "as if default" reading is benign: the tool's
        // delta computation will still produce a positive delta against
        // tier targets ≥ 40, and overlap-driven layouts have bigger problems
        // than spacing.
        List<int[]> groups = List.of(
                new int[]{0, 0, 100, 200},
                new int[]{80, 0, 100, 200});

        assertEquals(GroupLayoutCalculator.DEFAULT_DETECTED_SPACING,
                GroupLayoutCalculator.detectInterGroupSpacing(groups));
    }

    @Test
    public void detectInterGroupSpacing_emptyList_shouldReturnDefault() {
        assertEquals(GroupLayoutCalculator.DEFAULT_DETECTED_SPACING,
                GroupLayoutCalculator.detectInterGroupSpacing(List.of()));
    }

    @Test
    public void detectInterGroupSpacing_unsortedHorizontalInput_shouldDetectByPosition() {
        // Groups provided out of x-order: gaps (sorted) are 80 then 50 → MIN=50
        List<int[]> groups = List.of(
                new int[]{330, 0, 100, 200},    // rightmost (left edge=330)
                new int[]{0, 0, 100, 200},      // leftmost (right edge=100)
                new int[]{180, 0, 100, 200});   // middle (180→280; sorted: 0..100, gap 80, 180..280, gap 50, 330..)

        assertEquals(50, GroupLayoutCalculator.detectInterGroupSpacing(groups));
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

    // ====================================================================
    // BLOCK: GRID CELL WIDTH — UNIFORM (DEFAULT) VS PER-COLUMN (OPT-IN)
    // ====================================================================
    //
    // computeGridLayout has nine direct callers, so the default may not move: uniform cell width
    // stays exactly what it was, and the per-column behaviour is reachable only through the new
    // parameter. These tests pin BOTH arms, so the additive-ness is executable rather than
    // asserted in a commit message.

    @Test
    public void computeGridLayout_defaultOverload_shouldStillGiveEveryCellTheWidestWidth() {
        List<int[]> sizes = List.of(
                new int[]{100, 50}, new int[]{420, 50},
                new int[]{80, 50}, new int[]{120, 50});

        List<int[]> uniform = GroupLayoutCalculator
                .computeGridLayout(sizes, 10, 34, 20, 10, 0, 2).positions();

        for (int[] p : uniform) {
            assertEquals("the seven-arg overload's behaviour must not move", 420, p[2]);
        }
        assertEquals("second column still starts after the widest width", 10 + 420 + 20, uniform.get(1)[0]);
    }

    @Test
    public void computeGridLayout_perColumn_shouldSizeEachColumnFromItsOwnWidestMember() {
        List<int[]> sizes = List.of(
                new int[]{100, 50}, new int[]{420, 50},
                new int[]{80, 50}, new int[]{120, 50});

        List<int[]> perColumn = GroupLayoutCalculator
                .computeGridLayout(sizes, 10, 34, 20, 10, 0, 2, false).positions();

        assertEquals("column 0 is max(100, 80)", 100, perColumn.get(0)[2]);
        assertEquals("column 1 is max(420, 120)", 420, perColumn.get(1)[2]);
        assertEquals("column 0, row 2 keeps the column's width", 100, perColumn.get(2)[2]);
        assertEquals("column 1, row 2 keeps the column's width", 420, perColumn.get(3)[2]);
        assertEquals("columns still align across rows",
                perColumn.get(1)[0], perColumn.get(3)[0]);
    }

    @Test
    public void computeGridLayout_perColumn_shouldNarrowTheFittedContainer() {
        // The point of the change, stated as the number that matters: the container the grid is
        // fitted into. Uniform cells make it as wide as two copies of the widest element.
        List<int[]> sizes = List.of(
                new int[]{100, 50}, new int[]{420, 50});

        int uniformWidth = GroupLayoutCalculator.computeAutoResizeDimensions(
                GroupLayoutCalculator.computeGridLayout(sizes, 10, 34, 20, 10, 0, 2).positions(), 10)[0];
        int perColumnWidth = GroupLayoutCalculator.computeAutoResizeDimensions(
                GroupLayoutCalculator.computeGridLayout(sizes, 10, 34, 20, 10, 0, 2, false).positions(), 10)[0];

        assertEquals(10 + 420 + 20 + 420 + 10, uniformWidth);
        assertEquals(10 + 100 + 20 + 420 + 10, perColumnWidth);
    }

    @Test
    public void computeGridLayout_perColumn_shouldHandleASingleColumn() {
        List<int[]> sizes = List.of(new int[]{100, 50}, new int[]{420, 50});

        List<int[]> positions = GroupLayoutCalculator
                .computeGridLayout(sizes, 10, 34, 20, 10, 0, 1, false).positions();

        assertEquals("a one-column grid sizes that column from all its members", 420, positions.get(0)[2]);
        assertEquals(420, positions.get(1)[2]);
    }
}
