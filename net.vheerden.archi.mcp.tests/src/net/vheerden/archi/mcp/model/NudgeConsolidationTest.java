package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.NudgedElementDto;

/**
 * Headless coverage for the autoNudge consolidation seam.
 *
 * <p>The seam exists because the accessor's nudge loop is unreachable from any automated test: the
 * loop body only runs when A* leaves a connection unroutable, and every blocker geometry the
 * headless harness can build is routed around successfully. A test written against
 * {@code autoRouteConnections} is therefore green with the defect fully present. Consolidation is a
 * pure function of the cumulative-delta map, so it is pinned here directly, on a hand-built map.</p>
 */
public class NudgeConsolidationTest {

    /** (a) genuinely moved, (b) net-zero, (c) single non-zero delta. */
    private static Map<String, int[]> threeElementMap() {
        Map<String, int[]> deltas = new LinkedHashMap<>();
        deltas.put("vo-moved", new int[]{50, -20});
        deltas.put("vo-netzero", new int[]{0, 0});
        deltas.put("vo-single", new int[]{0, -50});
        return deltas;
    }

    private static Map<String, String> threeElementNames() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("vo-moved", "Moved Element");
        names.put("vo-netzero", "internal API GW");
        names.put("vo-single", "Single Move Element");
        return names;
    }

    @Test
    public void consolidate_whenAnElementEndedWhereItStarted_omitsItFromTheReportedNudges() {
        NudgeConsolidation.Result result =
                NudgeConsolidation.consolidate(threeElementMap(), threeElementNames());

        assertEquals("only elements whose net displacement is non-zero are reported",
                List.of("vo-moved", "vo-single"),
                result.moved().stream().map(NudgedElementDto::viewObjectId).toList());
    }

    @Test
    public void consolidate_whenAnElementEndedWhereItStarted_namesItAsNetZero() {
        NudgeConsolidation.Result result =
                NudgeConsolidation.consolidate(threeElementMap(), threeElementNames());

        assertEquals("the net-zero element is named, not silently dropped",
                List.of("vo-netzero"),
                result.netZero().stream().map(NudgedElementDto::viewObjectId).toList());
        assertEquals("the net-zero entry carries the element name for the warning message",
                "internal API GW", result.netZero().get(0).elementName());
    }

    @Test
    public void consolidate_whenDeltasAreNonZero_carriesTheSummedDisplacementVerbatim() {
        NudgeConsolidation.Result result =
                NudgeConsolidation.consolidate(threeElementMap(), threeElementNames());

        NudgedElementDto moved = result.moved().get(0);
        assertEquals("vo-moved", moved.viewObjectId());
        assertEquals("Moved Element", moved.elementName());
        assertEquals(50, moved.deltaX());
        assertEquals(-20, moved.deltaY());
        NudgedElementDto single = result.moved().get(1);
        assertEquals(0, single.deltaX());
        assertEquals(-50, single.deltaY());
    }

    @Test
    public void consolidate_whenGivenTheDeltaMap_doesNotModifyIt() {
        Map<String, int[]> deltas = threeElementMap();

        NudgeConsolidation.consolidate(deltas, threeElementNames());

        assertEquals("the map that drives applied geometry keeps every key", 3, deltas.size());
        assertTrue("the net-zero key is still present for the geometry consumers",
                deltas.containsKey("vo-netzero"));
        assertEquals(0, deltas.get("vo-netzero")[0]);
        assertEquals(0, deltas.get("vo-netzero")[1]);
        assertEquals(50, deltas.get("vo-moved")[0]);
    }

    @Test
    public void consolidate_whenNoElementNettedToZero_reportsAnEmptyNetZeroList() {
        Map<String, int[]> deltas = new LinkedHashMap<>();
        deltas.put("vo-a", new int[]{10, 0});
        Map<String, String> names = new LinkedHashMap<>();
        names.put("vo-a", "A");

        NudgeConsolidation.Result result = NudgeConsolidation.consolidate(deltas, names);

        assertEquals(1, result.moved().size());
        assertTrue("no net-zero signal when every element actually moved",
                result.netZero().isEmpty());
    }

    @Test
    public void consolidate_whenTheMapIsEmpty_reportsBothListsEmpty() {
        NudgeConsolidation.Result result =
                NudgeConsolidation.consolidate(new LinkedHashMap<>(), new LinkedHashMap<>());

        assertTrue(result.moved().isEmpty());
        assertTrue(result.netZero().isEmpty());
    }
}
