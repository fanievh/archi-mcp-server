package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Tests for {@link ElkLayoutEngine} (Story 10-29).
 * Pure-geometry tests — no OSGi runtime required.
 */
public class ElkLayoutEngineTest {

	private ElkLayoutEngine engine;

	@Before
	public void setUp() {
		engine = new ElkLayoutEngine();
	}

	// --- Helper methods ---

	private List<LayoutNode> createTestNodes(int count) {
		List<LayoutNode> nodes = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			nodes.add(new LayoutNode("node-" + i, 50 + i * 150, 50, 120, 55, null));
		}
		return nodes;
	}

	private List<LayoutEdge> createChainEdges(int nodeCount) {
		List<LayoutEdge> edges = new ArrayList<>();
		for (int i = 0; i < nodeCount - 1; i++) {
			edges.add(new LayoutEdge("node-" + i, "node-" + (i + 1), null));
		}
		return edges;
	}

	// --- Task 6.2: Test basic layout ---

	@Test
	public void shouldRepositionElements_whenLayoutComputed() {
		List<LayoutNode> nodes = createTestNodes(5);
		List<LayoutEdge> edges = createChainEdges(5);

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 50);

		assertNotNull(result);
		assertEquals(5, result.positions().size());
		assertEquals(5, result.elementsRepositioned());
		assertNonNegativePositions(result.positions());
	}

	@Test
	public void shouldComputeBendpoints_whenConnectionsExist() {
		List<LayoutNode> nodes = createTestNodes(4);
		List<LayoutEdge> edges = createChainEdges(4);

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 50);

		assertNotNull(result.connectionBendpoints());
		// ELK should route all connections
		assertTrue("Should route at least some connections",
				result.connectionsRouted() > 0);

		// Each routed connection has a bendpoint list (may be empty for straight
		// connections). ELK only stores INTERMEDIATE bendpoints — start/end points
		// are omitted because Archi's ChopboxAnchor computes edge-to-element
		// intersections automatically. A straight vertical/horizontal connection
		// between adjacent layers has zero intermediate bendpoints.
		for (Map.Entry<String, List<AbsoluteBendpointDto>> entry :
				result.connectionBendpoints().entrySet()) {
			assertNotNull("Connection " + entry.getKey() + " should have non-null bendpoint list",
					entry.getValue());
		}
	}

	// --- Task 6.3: Test nested elements ---

	@Test
	public void shouldPreserveNesting_whenChildrenInsideParents() {
		List<LayoutNode> nodes = new ArrayList<>();
		// Parent container
		nodes.add(new LayoutNode("parent", 50, 50, 400, 300, null));
		// Children inside parent
		nodes.add(new LayoutNode("child-1", 10, 30, 120, 55, "parent"));
		nodes.add(new LayoutNode("child-2", 150, 30, 120, 55, "parent"));
		// Another top-level element
		nodes.add(new LayoutNode("external", 500, 50, 120, 55, null));

		List<LayoutEdge> edges = new ArrayList<>();
		edges.add(new LayoutEdge("child-1", "child-2", null));
		edges.add(new LayoutEdge("parent", "external", null));

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 50);

		assertNotNull(result);
		assertEquals(4, result.positions().size());

		// Find parent and child positions
		ViewPositionSpec parentPos = findPosition(result.positions(), "parent");
		ViewPositionSpec child1Pos = findPosition(result.positions(), "child-1");
		ViewPositionSpec child2Pos = findPosition(result.positions(), "child-2");

		assertNotNull("Parent should have position", parentPos);
		assertNotNull("Child 1 should have position", child1Pos);
		assertNotNull("Child 2 should have position", child2Pos);

		// ELK returns child positions relative to parent —
		// children should have reasonable (non-huge) coordinates
		assertTrue("Child 1 X should be reasonable (< parent width * 2)",
				child1Pos.x() < parentPos.width() * 2);
		assertTrue("Child 1 Y should be reasonable (< parent height * 2)",
				child1Pos.y() < parentPos.height() * 2);
	}

	// --- Task 6.4: Test direction parameter ---

	@Test
	public void shouldProduceDifferentLayouts_whenDirectionChanges() {
		List<LayoutNode> nodes = createTestNodes(4);
		List<LayoutEdge> edges = createChainEdges(4);

		ElkLayoutResult downResult = engine.computeLayout(nodes, edges, "DOWN", 50);
		ElkLayoutResult rightResult = engine.computeLayout(nodes, edges, "RIGHT", 50);

		// Both should succeed
		assertNotNull(downResult);
		assertNotNull(rightResult);
		assertEquals(4, downResult.positions().size());
		assertEquals(4, rightResult.positions().size());

		// DOWN layout should be taller than wide (or at least different from RIGHT)
		// RIGHT layout should be wider than tall
		boolean anyDifference = false;
		for (int i = 0; i < downResult.positions().size(); i++) {
			ViewPositionSpec downPos = downResult.positions().get(i);
			ViewPositionSpec rightPos = rightResult.positions().get(i);
			if (!downPos.x().equals(rightPos.x()) || !downPos.y().equals(rightPos.y())) {
				anyDifference = true;
				break;
			}
		}
		assertTrue("DOWN and RIGHT directions should produce different layouts",
				anyDifference);
	}

	@Test
	public void shouldSupportAllDirections() {
		List<LayoutNode> nodes = createTestNodes(3);
		List<LayoutEdge> edges = createChainEdges(3);

		for (String dir : new String[]{"DOWN", "RIGHT", "UP", "LEFT"}) {
			ElkLayoutResult result = engine.computeLayout(nodes, edges, dir, 50);
			assertNotNull("Direction " + dir + " should produce result", result);
			assertEquals("Direction " + dir + " should position all nodes",
					3, result.positions().size());
		}
	}

	@Test(expected = ModelAccessException.class)
	public void shouldThrow_whenInvalidDirection() {
		List<LayoutNode> nodes = createTestNodes(3);
		engine.computeLayout(nodes, List.of(), "DIAGONAL", 50);
	}

	// --- Task 6.5: Test spacing parameter ---

	@Test
	public void shouldProduceMoreSpreadLayout_whenLargerSpacing() {
		// Use a branching graph so ELK has multiple nodes per layer,
		// making node-node spacing differences visible in the bounding box.
		List<LayoutNode> nodes = new ArrayList<>();
		nodes.add(new LayoutNode("root", 50, 50, 120, 55, null));
		for (int i = 0; i < 6; i++) {
			nodes.add(new LayoutNode("leaf-" + i, 50 + i * 150, 200, 120, 55, null));
		}
		List<LayoutEdge> edges = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			edges.add(new LayoutEdge("root", "leaf-" + i, null));
		}

		ElkLayoutResult tightResult = engine.computeLayout(nodes, edges, "DOWN", 10);
		ElkLayoutResult wideResult = engine.computeLayout(nodes, edges, "DOWN", 200);

		assertNotNull(tightResult);
		assertNotNull(wideResult);

		// Compute bounding box for each result
		int[] tightBounds = computeBoundingBox(tightResult.positions());
		int[] wideBounds = computeBoundingBox(wideResult.positions());

		// Wider spacing should produce a wider layout (more horizontal spread)
		assertTrue("Wider spacing should produce wider layout: tight="
				+ tightBounds[0] + " wide=" + wideBounds[0],
				wideBounds[0] > tightBounds[0]);
	}

	// --- Task 6.6: Test edge cases ---

	@Test
	public void shouldHandleSingleElement() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("single", 100, 100, 120, 55, null));

		ElkLayoutResult result = engine.computeLayout(nodes, List.of(), "DOWN", 50);

		assertNotNull(result);
		assertEquals(1, result.positions().size());
		assertEquals("single", result.positions().get(0).viewObjectId());
		assertEquals(0, result.connectionsRouted());
	}

	@Test
	public void shouldHandleNoConnections() {
		List<LayoutNode> nodes = createTestNodes(5);

		ElkLayoutResult result = engine.computeLayout(nodes, List.of(), "DOWN", 50);

		assertNotNull(result);
		assertEquals(5, result.positions().size());
		assertEquals(0, result.connectionsRouted());
		assertTrue("No connections should produce empty bendpoint map",
				result.connectionBendpoints().isEmpty());
	}

	@Test(expected = ModelAccessException.class)
	public void shouldThrow_whenEmptyView() {
		engine.computeLayout(List.of(), List.of(), "DOWN", 50);
	}

	@Test
	public void shouldDefaultDirection_whenNull() {
		List<LayoutNode> nodes = createTestNodes(3);
		List<LayoutEdge> edges = createChainEdges(3);

		ElkLayoutResult result = engine.computeLayout(nodes, edges, null, 50);
		assertNotNull(result);
		assertEquals(3, result.positions().size());
	}

	@Test
	public void shouldPreserveAllNodeIds() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("alpha", 10, 10, 120, 55, null),
				new LayoutNode("beta", 200, 10, 120, 55, null),
				new LayoutNode("gamma", 10, 200, 120, 55, null));

		ElkLayoutResult result = engine.computeLayout(nodes, List.of(), "DOWN", 50);

		Set<String> resultIds = new HashSet<>();
		for (ViewPositionSpec pos : result.positions()) {
			resultIds.add(pos.viewObjectId());
		}
		assertTrue("Should contain 'alpha'", resultIds.contains("alpha"));
		assertTrue("Should contain 'beta'", resultIds.contains("beta"));
		assertTrue("Should contain 'gamma'", resultIds.contains("gamma"));
	}

	@Test
	public void shouldProduceOrthogonalBendpoints() {
		// ELK Layered with ORTHOGONAL edge routing should produce right-angle paths
		List<LayoutNode> nodes = createTestNodes(4);
		List<LayoutEdge> edges = createChainEdges(4);

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 50);

		for (Map.Entry<String, List<AbsoluteBendpointDto>> entry :
				result.connectionBendpoints().entrySet()) {
			List<AbsoluteBendpointDto> bps = entry.getValue();
			for (int i = 0; i < bps.size() - 1; i++) {
				AbsoluteBendpointDto a = bps.get(i);
				AbsoluteBendpointDto b = bps.get(i + 1);
				boolean horizontal = a.y() == b.y();
				boolean vertical = a.x() == b.x();
				assertTrue("Segment " + i + " of " + entry.getKey()
						+ " should be orthogonal (H or V): ("
						+ a.x() + "," + a.y() + ") -> ("
						+ b.x() + "," + b.y() + ")",
						horizontal || vertical);
			}
		}
	}

	// --- M2: Multiple connections between same source/target pair ---

	@Test
	public void shouldRouteAllConnections_whenMultipleBetweenSamePair() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("A", 50, 50, 120, 55, null),
				new LayoutNode("B", 50, 200, 120, 55, null));

		// Two connections A→B with different connectionIds
		List<LayoutEdge> edges = List.of(
				new LayoutEdge("A", "B", "conn-1"),
				new LayoutEdge("A", "B", "conn-2"));

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 50);

		assertNotNull(result);
		// Both connections should be routed with distinct keys
		assertEquals("Both connections should be routed",
				2, result.connectionsRouted());
		assertTrue("conn-1 should have bendpoints",
				result.connectionBendpoints().containsKey("conn-1"));
		assertTrue("conn-2 should have bendpoints",
				result.connectionBendpoints().containsKey("conn-2"));
	}

	// --- M3: Deeply nested hierarchy (grandchild nodes) ---

	@Test
	public void shouldHandleDeeplyNestedHierarchy() {
		List<LayoutNode> nodes = new ArrayList<>();
		// Grandparent → Parent → Child
		nodes.add(new LayoutNode("grandparent", 50, 50, 500, 400, null));
		nodes.add(new LayoutNode("parent", 10, 30, 300, 250, "grandparent"));
		nodes.add(new LayoutNode("child-1", 10, 30, 120, 55, "parent"));
		nodes.add(new LayoutNode("child-2", 150, 30, 120, 55, "parent"));
		nodes.add(new LayoutNode("external", 600, 50, 120, 55, null));

		List<LayoutEdge> edges = List.of(
				new LayoutEdge("child-1", "child-2", null),
				new LayoutEdge("grandparent", "external", null));

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 50);

		assertNotNull(result);
		assertEquals(5, result.positions().size());

		// All nodes should have non-negative positions
		assertNonNegativePositions(result.positions());

		// Grandchild coordinates should be relative to parent (reasonable range)
		ViewPositionSpec child1Pos = findPosition(result.positions(), "child-1");
		assertNotNull("Grandchild should have position", child1Pos);
	}

	@Test
	public void shouldUseDefaultSpacing_whenZeroProvided() {
		List<LayoutNode> nodes = createTestNodes(3);
		List<LayoutEdge> edges = createChainEdges(3);

		// spacing=0 should use default (50)
		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 0);
		assertNotNull(result);
		assertEquals(3, result.positions().size());
	}

	// --- Helper methods ---

	private ViewPositionSpec findPosition(List<ViewPositionSpec> positions, String id) {
		for (ViewPositionSpec pos : positions) {
			if (pos.viewObjectId().equals(id)) {
				return pos;
			}
		}
		return null;
	}

	private int[] computeBoundingBox(List<ViewPositionSpec> positions) {
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
		for (ViewPositionSpec pos : positions) {
			minX = Math.min(minX, pos.x());
			minY = Math.min(minY, pos.y());
			maxX = Math.max(maxX, pos.x() + pos.width());
			maxY = Math.max(maxY, pos.y() + pos.height());
		}
		return new int[]{maxX - minX, maxY - minY};
	}

	private void assertNonNegativePositions(List<ViewPositionSpec> positions) {
		for (ViewPositionSpec pos : positions) {
			assertTrue("x should be non-negative for " + pos.viewObjectId(),
					pos.x() != null && pos.x() >= 0);
			assertTrue("y should be non-negative for " + pos.viewObjectId(),
					pos.y() != null && pos.y() >= 0);
		}
	}

	// --- Story 11-20 Spike: Inter-group connections ---

	@Test
	public void shouldRouteInterGroupConnections() {
		// Two groups, each with 2 elements, connections crossing group boundaries
		List<LayoutNode> nodes = new ArrayList<>();
		// Group 1
		nodes.add(new LayoutNode("group1", 50, 50, 300, 200, null));
		nodes.add(new LayoutNode("app1", 10, 30, 120, 55, "group1"));
		nodes.add(new LayoutNode("func1", 10, 100, 120, 55, "group1"));
		// Group 2
		nodes.add(new LayoutNode("group2", 450, 50, 300, 200, null));
		nodes.add(new LayoutNode("app2", 10, 30, 120, 55, "group2"));
		nodes.add(new LayoutNode("func2", 10, 100, 120, 55, "group2"));

		// Inter-group connections: func1 → app2, app1 → func2
		List<LayoutEdge> edges = List.of(
				new LayoutEdge("func1", "app2", "conn-cross-1"),
				new LayoutEdge("app1", "func2", "conn-cross-2"));

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "RIGHT", 50);

		assertNotNull(result);
		assertEquals(6, result.positions().size());

		// Both inter-group connections should be routed
		assertEquals("Both inter-group connections should be routed",
				2, result.connectionsRouted());
		assertTrue("conn-cross-1 should have bendpoints",
				result.connectionBendpoints().containsKey("conn-cross-1"));
		assertTrue("conn-cross-2 should have bendpoints",
				result.connectionBendpoints().containsKey("conn-cross-2"));

		// All positions should be non-negative
		assertNonNegativePositions(result.positions());

		// Groups should have been resized to fit children (MINIMUM_SIZE constraint)
		ViewPositionSpec group1 = findPosition(result.positions(), "group1");
		ViewPositionSpec group2 = findPosition(result.positions(), "group2");
		assertNotNull("group1 should have position", group1);
		assertNotNull("group2 should have position", group2);
		assertTrue("group1 should have width >= original (300)",
				group1.width() >= 300);
		assertTrue("group2 should have width >= original (300)",
				group2.width() >= 300);
	}

	@Test
	public void shouldRouteConnectionsBetweenDifferentGroupChildren() {
		// Realistic scenario: 3 groups with elements connected across groups
		List<LayoutNode> nodes = new ArrayList<>();
		// Application group
		nodes.add(new LayoutNode("appGroup", 50, 50, 400, 200, null));
		nodes.add(new LayoutNode("crm", 10, 30, 120, 55, "appGroup"));
		nodes.add(new LayoutNode("erp", 200, 30, 120, 55, "appGroup"));
		// Technology group
		nodes.add(new LayoutNode("techGroup", 50, 350, 400, 200, null));
		nodes.add(new LayoutNode("db", 10, 30, 120, 55, "techGroup"));
		nodes.add(new LayoutNode("server", 200, 30, 120, 55, "techGroup"));

		// Cross-group connections: crm → db, erp → server, crm → server
		List<LayoutEdge> edges = List.of(
				new LayoutEdge("crm", "db", "conn-1"),
				new LayoutEdge("erp", "server", "conn-2"),
				new LayoutEdge("crm", "server", "conn-3"));

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 50);

		assertNotNull(result);
		assertEquals(6, result.positions().size());
		assertEquals("All 3 cross-group connections should be routed",
				3, result.connectionsRouted());

		// Verify orthogonal routing for cross-group connections
		for (Map.Entry<String, List<AbsoluteBendpointDto>> entry :
				result.connectionBendpoints().entrySet()) {
			List<AbsoluteBendpointDto> bps = entry.getValue();
			for (int i = 0; i < bps.size() - 1; i++) {
				AbsoluteBendpointDto a = bps.get(i);
				AbsoluteBendpointDto b = bps.get(i + 1);
				boolean horizontal = a.y() == b.y();
				boolean vertical = a.x() == b.x();
				assertTrue("Segment " + i + " of " + entry.getKey()
						+ " should be orthogonal: ("
						+ a.x() + "," + a.y() + ") -> ("
						+ b.x() + "," + b.y() + ")",
						horizontal || vertical);
			}
		}
	}

	// --- Bug fix: element sizes should be preserved (SizeConstraint.FIXED) ---

	@Test
	public void shouldPreserveElementSizes_whenLayoutComputed() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("A", 50, 50, 220, 110, null),
				new LayoutNode("B", 300, 50, 220, 110, null),
				new LayoutNode("C", 550, 50, 180, 80, null));
		List<LayoutEdge> edges = List.of(
				new LayoutEdge("A", "B", "conn-1"),
				new LayoutEdge("B", "C", "conn-2"));

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 50);

		ViewPositionSpec posA = findPosition(result.positions(), "A");
		ViewPositionSpec posB = findPosition(result.positions(), "B");
		ViewPositionSpec posC = findPosition(result.positions(), "C");

		// Leaf node sizes must be preserved exactly
		assertEquals("A width should be preserved", 220, posA.width().intValue());
		assertEquals("A height should be preserved", 110, posA.height().intValue());
		assertEquals("B width should be preserved", 220, posB.width().intValue());
		assertEquals("B height should be preserved", 110, posB.height().intValue());
		assertEquals("C width should be preserved", 180, posC.width().intValue());
		assertEquals("C height should be preserved", 80, posC.height().intValue());
	}

	@Test
	public void shouldPreserveChildSizes_whenNestedElements() {
		List<LayoutNode> nodes = new ArrayList<>();
		nodes.add(new LayoutNode("parent", 50, 50, 400, 300, null));
		nodes.add(new LayoutNode("child-1", 10, 30, 150, 45, "parent"));
		nodes.add(new LayoutNode("child-2", 180, 30, 150, 45, "parent"));

		List<LayoutEdge> edges = List.of(
				new LayoutEdge("child-1", "child-2", null));

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 50);

		ViewPositionSpec child1 = findPosition(result.positions(), "child-1");
		ViewPositionSpec child2 = findPosition(result.positions(), "child-2");

		// Child sizes must be preserved
		assertEquals("child-1 width preserved", 150, child1.width().intValue());
		assertEquals("child-1 height preserved", 45, child1.height().intValue());
		assertEquals("child-2 width preserved", 150, child2.width().intValue());
		assertEquals("child-2 height preserved", 45, child2.height().intValue());
	}

	// --- Story 11-21: Group padding scales with spacing ---

	@Test
	public void shouldProduceMoreIntraGroupSpacing_whenLargerSpacing() {
		// Two groups, each with 2 children — compare child spacing at 50 vs 150
		List<LayoutNode> tightNodes = createGroupedNodes();
		List<LayoutNode> wideNodes = createGroupedNodes();
		List<LayoutEdge> edges = List.of(
				new LayoutEdge("child-a1", "child-a2", "conn-1"),
				new LayoutEdge("child-b1", "child-b2", "conn-2"));

		ElkLayoutResult tightResult = engine.computeLayout(tightNodes, edges, "DOWN", 50);
		ElkLayoutResult wideResult = engine.computeLayout(wideNodes, edges, "DOWN", 150);

		// Find child positions within group A for both results
		ViewPositionSpec tightChild1 = findPosition(tightResult.positions(), "child-a1");
		ViewPositionSpec tightChild2 = findPosition(tightResult.positions(), "child-a2");
		ViewPositionSpec wideChild1 = findPosition(wideResult.positions(), "child-a1");
		ViewPositionSpec wideChild2 = findPosition(wideResult.positions(), "child-a2");

		assertNotNull("tight child-a1", tightChild1);
		assertNotNull("tight child-a2", tightChild2);
		assertNotNull("wide child-a1", wideChild1);
		assertNotNull("wide child-a2", wideChild2);

		// Calculate vertical or horizontal distance between children
		int tightDist = Math.abs(tightChild2.y() - tightChild1.y())
				+ Math.abs(tightChild2.x() - tightChild1.x());
		int wideDist = Math.abs(wideChild2.y() - wideChild1.y())
				+ Math.abs(wideChild2.x() - wideChild1.x());

		assertTrue("Wider spacing should produce more intra-group distance: tight="
				+ tightDist + " wide=" + wideDist, wideDist > tightDist);
	}

	@Test
	public void shouldProduceReasonableSpacing_withDefaultSpacing() {
		// Default spacing (50) should not produce overly tight children
		List<LayoutNode> nodes = createGroupedNodes();
		List<LayoutEdge> edges = List.of(
				new LayoutEdge("child-a1", "child-a2", "conn-1"));

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 50);

		// Group should have been resized — check it grew beyond minimum
		ViewPositionSpec groupA = findPosition(result.positions(), "groupA");
		assertNotNull("groupA should have position", groupA);
		assertTrue("groupA should have reasonable width (>= 200)",
				groupA.width() >= 200);

		// Verify actual child-to-child spacing is adequate (>= 30px)
		ViewPositionSpec child1 = findPosition(result.positions(), "child-a1");
		ViewPositionSpec child2 = findPosition(result.positions(), "child-a2");
		int childDist = Math.abs(child2.y() - child1.y())
				+ Math.abs(child2.x() - child1.x());
		assertTrue("Default spacing should produce child distance >= 30px, got "
				+ childDist, childDist >= 30);
	}

	@Test
	public void shouldNotProduceNegativePadding_withVerySmallSpacing() {
		// Very small spacing should use floor values (top=25, sides=12)
		List<LayoutNode> nodes = createGroupedNodes();
		List<LayoutEdge> edges = List.of(
				new LayoutEdge("child-a1", "child-a2", "conn-1"));

		ElkLayoutResult result = engine.computeLayout(nodes, edges, "DOWN", 5);

		assertNotNull(result);
		// Children should have non-negative positions (proves padding didn't go negative)
		ViewPositionSpec child1 = findPosition(result.positions(), "child-a1");
		ViewPositionSpec child2 = findPosition(result.positions(), "child-a2");
		assertNotNull("child-a1", child1);
		assertNotNull("child-a2", child2);
		assertTrue("child-a1 x >= 0", child1.x() >= 0);
		assertTrue("child-a1 y >= 0", child1.y() >= 0);
		assertTrue("child-a2 x >= 0", child2.x() >= 0);
		assertTrue("child-a2 y >= 0", child2.y() >= 0);

		// Floor padding values should still produce a group that's larger than
		// its children. Floor: top=25, sides=12, so group must be >= child width + 2*12
		ViewPositionSpec groupA = findPosition(result.positions(), "groupA");
		assertNotNull("groupA", groupA);
		assertTrue("Group width should account for floor side padding (>= child width + 24)",
				groupA.width() >= 120 + 24);
	}

	private List<LayoutNode> createGroupedNodes() {
		List<LayoutNode> nodes = new ArrayList<>();
		// Group A with 2 children
		nodes.add(new LayoutNode("groupA", 50, 50, 300, 200, null));
		nodes.add(new LayoutNode("child-a1", 10, 30, 120, 55, "groupA"));
		nodes.add(new LayoutNode("child-a2", 10, 100, 120, 55, "groupA"));
		// Group B with 2 children
		nodes.add(new LayoutNode("groupB", 450, 50, 300, 200, null));
		nodes.add(new LayoutNode("child-b1", 10, 30, 120, 55, "groupB"));
		nodes.add(new LayoutNode("child-b2", 10, 100, 120, 55, "groupB"));
		return nodes;
	}

	@Test
	public void shouldSpaceDisconnectedChildren_withinGroup() {
		// Children with no edges between them are separate connected components —
		// SPACING_COMPONENT_COMPONENT must be set to prevent stacking
		List<LayoutNode> nodes = new ArrayList<>();
		nodes.add(new LayoutNode("group", 50, 50, 400, 300, null));
		nodes.add(new LayoutNode("child-1", 10, 30, 120, 55, "group"));
		nodes.add(new LayoutNode("child-2", 10, 100, 120, 55, "group"));
		nodes.add(new LayoutNode("child-3", 10, 170, 120, 55, "group"));

		// No edges between children — they are disconnected components
		ElkLayoutResult result = engine.computeLayout(nodes, List.of(), "DOWN", 50);

		ViewPositionSpec c1 = findPosition(result.positions(), "child-1");
		ViewPositionSpec c2 = findPosition(result.positions(), "child-2");
		ViewPositionSpec c3 = findPosition(result.positions(), "child-3");
		assertNotNull("child-1", c1);
		assertNotNull("child-2", c2);
		assertNotNull("child-3", c3);

		// Children should not be stacked on top of each other
		boolean allSamePos = (c1.x().equals(c2.x()) && c1.y().equals(c2.y()))
				&& (c2.x().equals(c3.x()) && c2.y().equals(c3.y()));
		assertFalse("Disconnected children should not be stacked at same position",
				allSamePos);
	}

	@Test
	public void shouldApplyScaledPadding_toDeeplyNestedParents() {
		// Grandparent → Parent → Children: intermediate parent (Parent) must
		// also get scaled padding and subgraph properties
		List<LayoutNode> nodes = new ArrayList<>();
		nodes.add(new LayoutNode("grandparent", 50, 50, 600, 500, null));
		nodes.add(new LayoutNode("parent", 10, 30, 400, 300, "grandparent"));
		nodes.add(new LayoutNode("child-1", 10, 30, 120, 55, "parent"));
		nodes.add(new LayoutNode("child-2", 150, 30, 120, 55, "parent"));

		List<LayoutEdge> edges = List.of(
				new LayoutEdge("child-1", "child-2", "conn-1"));

		// Compare tight vs wide spacing to verify intermediate parent scales
		ElkLayoutResult tightResult = engine.computeLayout(nodes, edges, "DOWN", 50);
		ElkLayoutResult wideResult = engine.computeLayout(nodes, edges, "DOWN", 150);

		ViewPositionSpec tightC1 = findPosition(tightResult.positions(), "child-1");
		ViewPositionSpec tightC2 = findPosition(tightResult.positions(), "child-2");
		ViewPositionSpec wideC1 = findPosition(wideResult.positions(), "child-1");
		ViewPositionSpec wideC2 = findPosition(wideResult.positions(), "child-2");

		int tightDist = Math.abs(tightC2.y() - tightC1.y())
				+ Math.abs(tightC2.x() - tightC1.x());
		int wideDist = Math.abs(wideC2.y() - wideC1.y())
				+ Math.abs(wideC2.x() - wideC1.x());

		assertTrue("Wider spacing should produce more distance in deeply nested layout: tight="
				+ tightDist + " wide=" + wideDist, wideDist > tightDist);
	}

	// --- Story 11-21: Plateau detection ---

	@Test
	public void shouldNotDetectPlateau_whenSpacingImproves() {
		// Same rating, same score, but spacing improved by >1.0px → not a plateau
		assertFalse("Spacing improvement should prevent plateau",
				ArchiModelAccessorImpl.isPlateauReached(
						"poor", "poor", 0, 0, 25.0, 20.0));
	}

	@Test
	public void shouldDetectPlateau_whenAllMetricsUnchanged() {
		// Same rating, same score, same spacing → plateau
		assertTrue("Unchanged metrics should trigger plateau",
				ArchiModelAccessorImpl.isPlateauReached(
						"poor", "poor", 0, 0, 20.0, 20.0));
	}

	@Test
	public void shouldDetectPlateau_whenSpacingIsZero() {
		// avgSpacing=0 (no neighbor data) should not block plateau detection
		assertTrue("Zero avgSpacing should not prevent plateau",
				ArchiModelAccessorImpl.isPlateauReached(
						"poor", "poor", 0, 0, 0.0, 0.0));
	}

	@Test
	public void shouldNotDetectPlateau_whenRatingChanges() {
		// Different rating → not a plateau
		assertFalse("Changed rating should prevent plateau",
				ArchiModelAccessorImpl.isPlateauReached(
						"fair", "poor", 0, 0, 20.0, 20.0));
	}

	@Test
	public void shouldNotDetectPlateau_whenScoreChanges() {
		// Different score → not a plateau
		assertFalse("Changed score should prevent plateau",
				ArchiModelAccessorImpl.isPlateauReached(
						"poor", "poor", 2, 5, 20.0, 20.0));
	}

	@Test
	public void shouldDetectPlateau_whenSpacingChangeBelowThreshold() {
		// Spacing changed by only 0.5px (< 1.0 threshold) → still a plateau
		assertTrue("Sub-threshold spacing change should still trigger plateau",
				ArchiModelAccessorImpl.isPlateauReached(
						"poor", "poor", 0, 0, 20.5, 20.0));
	}

	// --- Bug fix: computeElkAbsoluteCenters correctness ---

	@Test
	public void shouldComputeCorrectAbsoluteCenters_forTopLevelElements() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("A", 100, 200, 220, 110, null),
				new LayoutNode("B", 400, 200, 180, 80, null));

		Map<String, ViewPositionSpec> posById = new java.util.LinkedHashMap<>();
		posById.put("A", new ViewPositionSpec("A", 100, 200, 220, 110));
		posById.put("B", new ViewPositionSpec("B", 400, 200, 180, 80));

		Map<String, int[]> centers = ArchiModelAccessorImpl.computeElkAbsoluteCenters(
				posById, nodes);

		int[] centerA = centers.get("A");
		int[] centerB = centers.get("B");
		assertNotNull("A center should exist", centerA);
		assertNotNull("B center should exist", centerB);
		// A center: 100 + 220/2 = 210, 200 + 110/2 = 255
		assertEquals("A centerX", 210, centerA[0]);
		assertEquals("A centerY", 255, centerA[1]);
		// B center: 400 + 180/2 = 490, 200 + 80/2 = 240
		assertEquals("B centerX", 490, centerB[0]);
		assertEquals("B centerY", 240, centerB[1]);
	}

	@Test
	public void shouldComputeCorrectAbsoluteCenters_forNestedElements() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("parent", 100, 50, 400, 300, null),
				new LayoutNode("child", 20, 40, 150, 45, "parent"));

		Map<String, ViewPositionSpec> posById = new java.util.LinkedHashMap<>();
		posById.put("parent", new ViewPositionSpec("parent", 100, 50, 400, 300));
		posById.put("child", new ViewPositionSpec("child", 20, 40, 150, 45));

		Map<String, int[]> centers = ArchiModelAccessorImpl.computeElkAbsoluteCenters(
				posById, nodes);

		int[] parentCenter = centers.get("parent");
		int[] childCenter = centers.get("child");
		assertNotNull("parent center should exist", parentCenter);
		assertNotNull("child center should exist", childCenter);
		// Parent center: 100 + 400/2 = 300, 50 + 300/2 = 200
		assertEquals("parent centerX", 300, parentCenter[0]);
		assertEquals("parent centerY", 200, parentCenter[1]);
		// Child center: 20 + 150/2 + 100 (parent x) = 195, 40 + 45/2 + 50 (parent y) = 112
		assertEquals("child centerX", 195, childCenter[0]);
		assertEquals("child centerY", 112, childCenter[1]);
	}
}
