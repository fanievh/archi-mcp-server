package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@code ArchiModelAccessorImpl#applyNudgeDeltas(List, Map)}.
 * Validates that nudge deltas are applied to assessment nodes without
 * mutating the EMF model (fixes SWTException: Invalid thread access).
 * Pure geometry — runnable without OSGi.
 */
public class ApplyNudgeDeltasTest {

	// --- Helper ---

	private static AssessmentNode element(String id, double x, double y, double w, double h) {
		return new AssessmentNode(id, x, y, w, h, null, false, false);
	}

	private static AssessmentNode group(String id, double x, double y, double w, double h) {
		return new AssessmentNode(id, x, y, w, h, null, true, false);
	}

	private static AssessmentNode note(String id, double x, double y, double w, double h) {
		return new AssessmentNode(id, x, y, w, h, null, false, true);
	}

	private static AssessmentNode elementInGroup(String id, double x, double y, double w, double h,
			String parentId) {
		return new AssessmentNode(id, x, y, w, h, parentId, false, false);
	}

	private static AssessmentNode groupInGroup(String id, double x, double y, double w, double h,
			String parentId) {
		return new AssessmentNode(id, x, y, w, h, parentId, true, false);
	}

	// --- Tests (2-param overload) ---

	@Test
	public void shouldReturnSameList_whenNoDeltasProvided() {
		List<AssessmentNode> nodes = List.of(
				element("e1", 100, 200, 120, 55));
		Map<String, int[]> deltas = new LinkedHashMap<>();

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		assertSame("Empty deltas should return same list instance", nodes, result);
	}

	@Test
	public void shouldApplyDeltaToMatchingElement() {
		List<AssessmentNode> nodes = List.of(
				element("e1", 100, 200, 120, 55));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e1", new int[]{-30, 45});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		assertEquals(1, result.size());
		AssessmentNode adjusted = result.get(0);
		assertEquals("e1", adjusted.id());
		assertEquals(70.0, adjusted.x(), 0.001);
		assertEquals(245.0, adjusted.y(), 0.001);
		assertEquals(120.0, adjusted.width(), 0.001);
		assertEquals(55.0, adjusted.height(), 0.001);
	}

	@Test
	public void shouldNotModifyNonMatchingElements() {
		AssessmentNode unchanged = element("e2", 300, 400, 140, 60);
		List<AssessmentNode> nodes = List.of(
				element("e1", 100, 200, 120, 55),
				unchanged);
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e1", new int[]{10, 20});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		assertEquals(2, result.size());
		assertSame("Non-nudged node should be same instance", unchanged, result.get(1));
		assertEquals(300.0, result.get(1).x(), 0.001);
		assertEquals(400.0, result.get(1).y(), 0.001);
	}

	@Test
	public void shouldApplyMultipleDeltas() {
		List<AssessmentNode> nodes = List.of(
				element("e1", 100, 200, 120, 55),
				element("e2", 300, 400, 140, 60),
				element("e3", 500, 600, 100, 50));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e1", new int[]{-30, 0});
		deltas.put("e3", new int[]{0, 45});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		assertEquals(3, result.size());
		assertEquals(70.0, result.get(0).x(), 0.001);   // e1 nudged
		assertEquals(300.0, result.get(1).x(), 0.001);   // e2 unchanged
		assertEquals(645.0, result.get(2).y(), 0.001);   // e3 nudged
	}

	@Test
	public void shouldPreserveParentIdAndFlags() {
		List<AssessmentNode> nodes = List.of(
				new AssessmentNode("e1", 100, 200, 120, 55, "g1", false, false));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e1", new int[]{10, 20});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		AssessmentNode adjusted = result.get(0);
		assertEquals("g1", adjusted.parentId());
		assertEquals(false, adjusted.isGroup());
		assertEquals(false, adjusted.isNote());
	}

	@Test
	public void shouldNotAdjustGroupsOrNotes() {
		// Groups and notes should only be adjusted if they appear in the deltas map.
		// In practice, only elements get nudge recommendations, but the method
		// doesn't discriminate — it applies deltas by ID regardless of type.
		List<AssessmentNode> nodes = List.of(
				group("g1", 0, 0, 500, 400),
				note("n1", 10, 10, 200, 50),
				element("e1", 100, 200, 120, 55));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e1", new int[]{15, -10});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		assertEquals(3, result.size());
		// Group and note unchanged
		assertEquals(0.0, result.get(0).x(), 0.001);
		assertEquals(10.0, result.get(1).x(), 0.001);
		// Element adjusted
		assertEquals(115.0, result.get(2).x(), 0.001);
		assertEquals(190.0, result.get(2).y(), 0.001);
	}

	@Test
	public void shouldHandleNegativeResultCoordinates() {
		List<AssessmentNode> nodes = List.of(
				element("e1", 10, 20, 120, 55));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e1", new int[]{-30, -50});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		assertEquals(-20.0, result.get(0).x(), 0.001);
		assertEquals(-30.0, result.get(0).y(), 0.001);
	}

	@Test
	public void shouldHandleEmptyNodeList() {
		List<AssessmentNode> nodes = new ArrayList<>();
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e1", new int[]{10, 20});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		assertEquals(0, result.size());
	}

	@Test
	public void shouldHandleDeltaForNonExistentElement() {
		List<AssessmentNode> nodes = List.of(
				element("e1", 100, 200, 120, 55));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e999", new int[]{10, 20});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		assertEquals(1, result.size());
		assertEquals(100.0, result.get(0).x(), 0.001);
	}

	@Test
	public void shouldApplyCumulativeDeltasAcrossIterations() {
		// Simulates multi-iteration nudge: iteration 1 nudges e1 by (+30, +10),
		// iteration 2 nudges e1 again by (+20, -5). Cumulative delta = (+50, +5).
		// applyNudgeDeltas should produce the correct virtual position when called
		// with the accumulated map (as the production code does).
		List<AssessmentNode> nodes = List.of(
				element("e1", 100, 200, 120, 55));

		Map<String, int[]> deltas = new LinkedHashMap<>();
		// Iteration 1
		deltas.merge("e1", new int[]{30, 10},
				(old, neu) -> new int[]{old[0] + neu[0], old[1] + neu[1]});
		// Iteration 2
		deltas.merge("e1", new int[]{20, -5},
				(old, neu) -> new int[]{old[0] + neu[0], old[1] + neu[1]});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		assertEquals(1, result.size());
		// 100 + 50 = 150, 200 + 5 = 205
		assertEquals(150.0, result.get(0).x(), 0.001);
		assertEquals(205.0, result.get(0).y(), 0.001);
	}

	@Test
	public void shouldPreserveListSize() {
		List<AssessmentNode> nodes = List.of(
				element("e1", 100, 200, 120, 55),
				element("e2", 300, 400, 140, 60),
				group("g1", 0, 0, 600, 500),
				note("n1", 10, 10, 200, 50),
				element("e3", 500, 600, 100, 50));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e2", new int[]{5, 5});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(nodes, deltas);

		assertEquals(nodes.size(), result.size());
	}

	// --- Tests (3-param overload: with virtualGroupBounds) (backlog-b15) ---

	@Test
	public void shouldReturnSameList_whenAllMapsEmpty_threeParam() {
		List<AssessmentNode> nodes = List.of(
				group("g1", 100, 100, 400, 300),
				elementInGroup("e1", 150, 150, 60, 40, "g1"));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		Map<String, int[]> groupBounds = new LinkedHashMap<>();

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(
				nodes, deltas, groupBounds);

		assertSame("Empty maps should return same list instance", nodes, result);
	}

	@Test
	public void shouldAdjustGroupSize_whenGroupResized() {
		// Group originally 400x300, resized to 450x300 (right expansion)
		List<AssessmentNode> nodes = List.of(
				group("g1", 100, 100, 400, 300),
				elementInGroup("e1", 150, 150, 60, 40, "g1"));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		Map<String, int[]> groupBounds = new LinkedHashMap<>();
		groupBounds.put("g1", new int[]{100, 100, 450, 300}); // x,y unchanged, width grew

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(
				nodes, deltas, groupBounds);

		assertEquals(2, result.size());
		AssessmentNode adjustedGroup = result.get(0);
		assertEquals("g1", adjustedGroup.id());
		assertEquals(100.0, adjustedGroup.x(), 0.001);  // x unchanged
		assertEquals(100.0, adjustedGroup.y(), 0.001);  // y unchanged
		assertEquals(450.0, adjustedGroup.width(), 0.001);  // width updated
		assertEquals(300.0, adjustedGroup.height(), 0.001); // height unchanged
		assertTrue(adjustedGroup.isGroup());
	}

	@Test
	public void shouldApplyBothElementDeltaAndGroupResize() {
		// Element nudged right, parent group expanded
		List<AssessmentNode> nodes = List.of(
				group("g1", 100, 100, 400, 300),
				elementInGroup("e1", 450, 150, 60, 40, "g1"));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e1", new int[]{30, 0}); // nudged right by 30
		Map<String, int[]> groupBounds = new LinkedHashMap<>();
		groupBounds.put("g1", new int[]{100, 100, 450, 300}); // group grew to accommodate

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(
				nodes, deltas, groupBounds);

		assertEquals(2, result.size());
		// Group resized
		assertEquals(450.0, result.get(0).width(), 0.001);
		// Element nudged
		assertEquals(480.0, result.get(1).x(), 0.001);  // 450 + 30
		assertEquals(150.0, result.get(1).y(), 0.001);  // unchanged
	}

	@Test
	public void shouldNotAffectNonGroupNodes_whenOnlyGroupBoundsProvided() {
		AssessmentNode origNote = note("n1", 50, 50, 200, 30);
		List<AssessmentNode> nodes = List.of(
				group("g1", 100, 100, 400, 300),
				origNote,
				elementInGroup("e1", 150, 150, 60, 40, "g1"));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		Map<String, int[]> groupBounds = new LinkedHashMap<>();
		groupBounds.put("g1", new int[]{100, 100, 500, 350});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(
				nodes, deltas, groupBounds);

		// Note and element unchanged (only group size adjusted)
		assertSame(origNote, result.get(1));
		assertSame(nodes.get(2), result.get(2));
	}

	@Test
	public void shouldFallbackToTwoParamOverload_whenGroupBoundsNull() {
		List<AssessmentNode> nodes = List.of(
				element("e1", 100, 200, 120, 55));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e1", new int[]{10, 20});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(
				nodes, deltas, null);

		assertEquals(1, result.size());
		assertEquals(110.0, result.get(0).x(), 0.001);
		assertEquals(220.0, result.get(0).y(), 0.001);
	}

	@Test
	public void shouldHandleMultipleGroupsResized() {
		// Two groups both resized
		List<AssessmentNode> nodes = List.of(
				group("g1", 50, 50, 300, 200),
				group("g2", 400, 50, 300, 200),
				elementInGroup("e1", 100, 100, 60, 40, "g1"),
				elementInGroup("e2", 450, 100, 60, 40, "g2"));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		Map<String, int[]> groupBounds = new LinkedHashMap<>();
		groupBounds.put("g1", new int[]{50, 50, 350, 200});
		groupBounds.put("g2", new int[]{400, 50, 300, 250});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(
				nodes, deltas, groupBounds);

		assertEquals(4, result.size());
		assertEquals(350.0, result.get(0).width(), 0.001); // g1 wider
		assertEquals(250.0, result.get(1).height(), 0.001); // g2 taller
	}

	@Test
	public void shouldPreserveListSize_threeParam() {
		List<AssessmentNode> nodes = List.of(
				group("g1", 100, 100, 400, 300),
				elementInGroup("e1", 150, 150, 60, 40, "g1"),
				elementInGroup("e2", 200, 200, 60, 40, "g1"),
				note("n1", 50, 50, 200, 30));
		Map<String, int[]> deltas = new LinkedHashMap<>();
		deltas.put("e1", new int[]{30, 0});
		Map<String, int[]> groupBounds = new LinkedHashMap<>();
		groupBounds.put("g1", new int[]{100, 100, 450, 300});

		List<AssessmentNode> result = ArchiModelAccessorImpl.applyNudgeDeltas(
				nodes, deltas, groupBounds);

		assertEquals(nodes.size(), result.size());
	}
}
