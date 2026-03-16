package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Tests for {@link OverlapResolver} (Story 10-18).
 * Pure geometry — runnable without OSGi.
 */
public class OverlapResolverTest {

	private OverlapResolver resolver;

	@Before
	public void setUp() {
		resolver = new OverlapResolver();
	}

	// --- Helper methods ---

	private List<ViewPositionSpec> toPositions(List<LayoutNode> nodes) {
		List<ViewPositionSpec> positions = new ArrayList<>();
		for (LayoutNode n : nodes) {
			positions.add(new ViewPositionSpec(n.viewObjectId(),
					(int) n.x(), (int) n.y(), (int) n.width(), (int) n.height()));
		}
		return positions;
	}

	private boolean hasOverlaps(List<ViewPositionSpec> positions) {
		for (int i = 0; i < positions.size(); i++) {
			for (int j = i + 1; j < positions.size(); j++) {
				ViewPositionSpec a = positions.get(i);
				ViewPositionSpec b = positions.get(j);
				if (a.x() < b.x() + b.width()
						&& a.x() + a.width() > b.x()
						&& a.y() < b.y() + b.height()
						&& a.y() + a.height() > b.y()) {
					return true;
				}
			}
		}
		return false;
	}

	private int countOverlaps(List<ViewPositionSpec> positions) {
		int count = 0;
		for (int i = 0; i < positions.size(); i++) {
			for (int j = i + 1; j < positions.size(); j++) {
				ViewPositionSpec a = positions.get(i);
				ViewPositionSpec b = positions.get(j);
				if (a.x() < b.x() + b.width()
						&& a.x() + a.width() > b.x()
						&& a.y() < b.y() + b.height()
						&& a.y() + a.height() > b.y()) {
					count++;
				}
			}
		}
		return count;
	}

	private double minEdgeToEdgeGap(List<ViewPositionSpec> positions) {
		double minGap = Double.MAX_VALUE;
		for (int i = 0; i < positions.size(); i++) {
			for (int j = i + 1; j < positions.size(); j++) {
				ViewPositionSpec a = positions.get(i);
				ViewPositionSpec b = positions.get(j);
				double dx = Math.max(0, Math.max(b.x() - (a.x() + a.width()),
						a.x() - (b.x() + b.width())));
				double dy = Math.max(0, Math.max(b.y() - (a.y() + a.height()),
						a.y() - (b.y() + b.height())));
				double gap = Math.sqrt(dx * dx + dy * dy);
				if (gap < minGap) {
					minGap = gap;
				}
			}
		}
		return minGap;
	}

	// --- Test 4.1: Non-overlapping elements remain unchanged ---

	@Test
	public void resolve_shouldNotMoveNonOverlappingElements() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("a", 0, 0, 100, 50, null),
				new LayoutNode("b", 200, 0, 100, 50, null),
				new LayoutNode("c", 0, 200, 100, 50, null));
		List<ViewPositionSpec> positions = toPositions(nodes);

		List<ViewPositionSpec> result = resolver.resolve(positions, nodes, 20.0);

		assertEquals(3, result.size());
		for (int i = 0; i < result.size(); i++) {
			assertEquals(positions.get(i).x(), result.get(i).x());
			assertEquals(positions.get(i).y(), result.get(i).y());
		}
	}

	// --- Test 4.2: Two overlapping elements pushed apart ---

	@Test
	public void resolve_shouldPushApartTwoOverlappingElements() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("a", 0, 0, 100, 50, null),
				new LayoutNode("b", 50, 10, 100, 50, null));
		List<ViewPositionSpec> positions = toPositions(nodes);

		List<ViewPositionSpec> result = resolver.resolve(positions, nodes, 20.0);

		assertEquals(0, countOverlaps(result));
	}

	// --- Test 4.3: Chain of overlapping elements ---

	@Test
	public void resolve_shouldResolveChainOfOverlaps() {
		// A overlaps B, B overlaps C — all at similar positions
		List<LayoutNode> nodes = List.of(
				new LayoutNode("a", 0, 0, 100, 50, null),
				new LayoutNode("b", 30, 10, 100, 50, null),
				new LayoutNode("c", 60, 20, 100, 50, null));
		List<ViewPositionSpec> positions = toPositions(nodes);

		List<ViewPositionSpec> result = resolver.resolve(positions, nodes, 10.0);

		assertEquals(0, countOverlaps(result));
	}

	// --- Test 4.4: Sibling-only resolution ---

	@Test
	public void resolve_shouldNotCompareDifferentParentGroups() {
		// "a" in group1, "b" in group2 — they overlap but should NOT be compared
		List<LayoutNode> nodes = List.of(
				new LayoutNode("a", 0, 0, 100, 50, "group1"),
				new LayoutNode("b", 50, 10, 100, 50, "group2"));
		List<ViewPositionSpec> positions = toPositions(nodes);

		List<ViewPositionSpec> result = resolver.resolve(positions, nodes, 20.0);

		// Positions should remain unchanged since elements are in different groups
		assertEquals(Integer.valueOf(0), result.get(0).x());
		assertEquals(Integer.valueOf(0), result.get(0).y());
		assertEquals(Integer.valueOf(50), result.get(1).x());
		assertEquals(Integer.valueOf(10), result.get(1).y());
	}

	// --- Test 4.5: 28 heavily-overlapping elements ---

	@Test
	public void resolve_shouldConvergeFor28OverlappingElements() {
		List<LayoutNode> nodes = new ArrayList<>();
		for (int i = 0; i < 28; i++) {
			// All elements stacked on top of each other
			nodes.add(new LayoutNode("node-" + i, 10 + i * 5, 10 + i * 3, 120, 55, null));
		}
		List<ViewPositionSpec> positions = toPositions(nodes);

		List<ViewPositionSpec> result = resolver.resolve(positions, nodes, 20.0);

		assertEquals(28, result.size());
		assertEquals(0, countOverlaps(result));
	}

	// --- Test 4.6: Spacing parameter honored ---

	@Test
	public void resolve_shouldHonorSpacingAsMinimumGap() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("a", 0, 0, 100, 50, null),
				new LayoutNode("b", 50, 0, 100, 50, null));
		List<ViewPositionSpec> positions = toPositions(nodes);

		double spacing = 30.0;
		List<ViewPositionSpec> result = resolver.resolve(positions, nodes, spacing);

		assertEquals(0, countOverlaps(result));
		// After resolution, b.x should be at least a.x + a.width + spacing
		ViewPositionSpec a = result.get(0);
		ViewPositionSpec b = result.get(1);
		// Find which is left-most
		ViewPositionSpec left = a.x() <= b.x() ? a : b;
		ViewPositionSpec right = a.x() <= b.x() ? b : a;
		assertTrue("Spacing should be honored",
				right.x() >= left.x() + left.width() + spacing - 1); // -1 for rounding
	}

	// --- Test 4.7: Varying sizes ---

	@Test
	public void resolve_shouldHandleVaryingSizes() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("narrow", 0, 0, 60, 30, null),
				new LayoutNode("wide", 20, 10, 200, 80, null),
				new LayoutNode("tall", 40, 5, 80, 150, null));
		List<ViewPositionSpec> positions = toPositions(nodes);

		List<ViewPositionSpec> result = resolver.resolve(positions, nodes, 15.0);

		assertEquals(0, countOverlaps(result));
	}

	// --- Test 4.8: Max iterations reached ---

	@Test
	public void resolve_shouldReturnBestEffortWithoutThrowing() {
		// Create a pathological case: many elements in a tight cluster
		List<LayoutNode> nodes = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			nodes.add(new LayoutNode("n-" + i, i % 5, i / 5, 100, 50, null));
		}
		List<ViewPositionSpec> positions = toPositions(nodes);

		int initialOverlaps = countOverlaps(positions);
		assertTrue("Test setup should have overlaps", initialOverlaps > 0);

		// Should not throw regardless of convergence
		List<ViewPositionSpec> result = resolver.resolve(positions, nodes, 10.0);

		assertEquals(50, result.size());
		int finalOverlaps = countOverlaps(result);
		assertTrue("Overlap count should decrease from " + initialOverlaps + " to at most " + initialOverlaps + ", was " + finalOverlaps,
				finalOverlaps < initialOverlaps);
	}

	// --- Additional: single element ---

	@Test
	public void resolve_shouldReturnSingleElementUnchanged() {
		List<LayoutNode> nodes = List.of(new LayoutNode("only", 50, 50, 120, 55, null));
		List<ViewPositionSpec> positions = toPositions(nodes);

		List<ViewPositionSpec> result = resolver.resolve(positions, nodes, 20.0);

		assertEquals(1, result.size());
		assertEquals(Integer.valueOf(50), result.get(0).x());
		assertEquals(Integer.valueOf(50), result.get(0).y());
	}

	// --- Siblings within same group are resolved ---

	@Test
	public void resolve_shouldResolveSiblingsWithinSameGroup() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("a", 0, 0, 100, 50, "group1"),
				new LayoutNode("b", 50, 10, 100, 50, "group1"),
				new LayoutNode("c", 80, 20, 100, 50, "group1"));
		List<ViewPositionSpec> positions = toPositions(nodes);

		List<ViewPositionSpec> result = resolver.resolve(positions, nodes, 10.0);

		assertEquals(0, countOverlaps(result));
	}
}
