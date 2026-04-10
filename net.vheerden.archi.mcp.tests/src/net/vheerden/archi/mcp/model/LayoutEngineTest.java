package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
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

import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Tests for {@link LayoutEngine} (Story 9-1).
 */
public class LayoutEngineTest {

	private LayoutEngine engine;

	@Before
	public void setUp() {
		engine = new LayoutEngine();
	}

	private List<LayoutNode> createTestNodes(int count) {
		List<LayoutNode> nodes = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			nodes.add(new LayoutNode("node-" + i, 50 + i * 10, 50 + i * 10, 120, 55, null));
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

	@Test
	public void computeLayout_withTreeAlgorithm_shouldPositionNodes() {
		List<LayoutNode> nodes = createTestNodes(5);
		List<LayoutEdge> edges = createChainEdges(5);

		List<ViewPositionSpec> result = engine.computeLayout(nodes, edges, "tree", Map.of());

		assertNotNull(result);
		assertEquals(5, result.size());
		assertNonNegativePositions(result);
	}

	@Test
	public void computeLayout_withSpringAlgorithm_shouldPositionNodes() {
		List<LayoutNode> nodes = createTestNodes(4);
		List<LayoutEdge> edges = createChainEdges(4);

		List<ViewPositionSpec> result = engine.computeLayout(nodes, edges, "spring", Map.of());

		assertNotNull(result);
		assertEquals(4, result.size());
		assertNonNegativePositions(result);
	}

	@Test
	public void computeLayout_withDirectedAlgorithm_shouldPositionNodes() {
		List<LayoutNode> nodes = createTestNodes(4);
		List<LayoutEdge> edges = createChainEdges(4);

		List<ViewPositionSpec> result = engine.computeLayout(nodes, edges, "directed", Map.of());

		assertNotNull(result);
		assertEquals(4, result.size());
		assertNonNegativePositions(result);
	}

	@Test
	public void computeLayout_withRadialAlgorithm_shouldPositionNodes() {
		List<LayoutNode> nodes = createTestNodes(5);
		List<LayoutEdge> edges = createChainEdges(5);

		List<ViewPositionSpec> result = engine.computeLayout(nodes, edges, "radial", Map.of());

		assertNotNull(result);
		assertEquals(5, result.size());
		assertNonNegativePositions(result);
	}

	@Test
	public void computeLayout_withGridAlgorithm_shouldPositionNodes() {
		List<LayoutNode> nodes = createTestNodes(6);
		List<LayoutEdge> edges = List.of();

		List<ViewPositionSpec> result = engine.computeLayout(nodes, edges, "grid", Map.of());

		assertNotNull(result);
		assertEquals(6, result.size());
		assertNonNegativePositions(result);
	}

	@Test
	public void computeLayout_withHorizontalTreeAlgorithm_shouldPositionNodes() {
		List<LayoutNode> nodes = createTestNodes(4);
		List<LayoutEdge> edges = createChainEdges(4);

		List<ViewPositionSpec> result = engine.computeLayout(nodes, edges, "horizontal-tree", Map.of());

		assertNotNull(result);
		assertEquals(4, result.size());
		assertNonNegativePositions(result);
	}

	@Test(expected = ModelAccessException.class)
	public void computeLayout_withInvalidAlgorithm_shouldThrow() {
		List<LayoutNode> nodes = createTestNodes(3);
		engine.computeLayout(nodes, List.of(), "banana", Map.of());
	}

	@Test
	public void computeLayout_withInvalidAlgorithm_shouldListValidAlgorithms() {
		List<LayoutNode> nodes = createTestNodes(3);
		try {
			engine.computeLayout(nodes, List.of(), "banana", Map.of());
			fail("Should have thrown ModelAccessException");
		} catch (ModelAccessException e) {
			assertTrue("Error should mention 'banana'",
					e.getMessage().contains("banana"));
			assertTrue("Error should list valid algorithms",
					e.getMessage().contains("tree") && e.getMessage().contains("spring"));
		}
	}

	@Test
	public void computeLayout_withSpacingOption_shouldInfluenceLayout() {
		List<LayoutNode> nodes = createTestNodes(4);
		List<LayoutEdge> edges = createChainEdges(4);

		List<ViewPositionSpec> tightResult = engine.computeLayout(nodes, edges, "grid", Map.of("spacing", 20));
		List<ViewPositionSpec> wideResult = engine.computeLayout(nodes, edges, "grid", Map.of("spacing", 100));

		// Both should succeed and return positions
		assertNotNull(tightResult);
		assertNotNull(wideResult);
		assertEquals(4, tightResult.size());
		assertEquals(4, wideResult.size());

		// At least one coordinate should differ between tight and wide spacing
		boolean anyDifference = false;
		for (int i = 0; i < tightResult.size(); i++) {
			ViewPositionSpec tight = tightResult.get(i);
			ViewPositionSpec wide = wideResult.get(i);
			if (!tight.x().equals(wide.x()) || !tight.y().equals(wide.y())) {
				anyDifference = true;
				break;
			}
		}
		assertTrue("Different spacing should produce different positions", anyDifference);
	}

	@Test(expected = ModelAccessException.class)
	public void computeLayout_withEmptyNodes_shouldThrow() {
		engine.computeLayout(List.of(), List.of(), "tree", Map.of());
	}

	@Test
	public void computeLayout_shouldHandleSingleNode() {
		List<LayoutNode> nodes = List.of(new LayoutNode("single", 0, 0, 120, 55, null));

		List<ViewPositionSpec> result = engine.computeLayout(nodes, List.of(), "tree", Map.of());

		assertNotNull(result);
		assertEquals(1, result.size());
		assertEquals("single", result.get(0).viewObjectId());
		assertNonNegativePositions(result);
	}

	@Test
	public void computeLayout_shouldPreserveNodeIds() {
		List<LayoutNode> nodes = List.of(
				new LayoutNode("alpha", 10, 10, 120, 55, null),
				new LayoutNode("beta", 200, 10, 120, 55, null),
				new LayoutNode("gamma", 10, 200, 120, 55, null));

		List<ViewPositionSpec> result = engine.computeLayout(nodes, List.of(), "grid", Map.of());

		Set<String> resultIds = new HashSet<>();
		for (ViewPositionSpec pos : result) {
			resultIds.add(pos.viewObjectId());
		}
		assertTrue("Should contain 'alpha'", resultIds.contains("alpha"));
		assertTrue("Should contain 'beta'", resultIds.contains("beta"));
		assertTrue("Should contain 'gamma'", resultIds.contains("gamma"));
	}

	@Test
	public void computeLayout_withDisconnectedNodes_shouldSucceed() {
		List<LayoutNode> nodes = createTestNodes(5);
		// No edges — all disconnected
		List<ViewPositionSpec> result = engine.computeLayout(nodes, List.of(), "tree", Map.of());

		assertNotNull(result);
		assertEquals(5, result.size());
	}

	@Test
	public void listAlgorithms_shouldReturnAllAlgorithms() {
		String algorithms = engine.listAlgorithms();
		assertTrue("Should contain tree", algorithms.contains("tree"));
		assertTrue("Should contain spring", algorithms.contains("spring"));
		assertTrue("Should contain directed", algorithms.contains("directed"));
		assertTrue("Should contain radial", algorithms.contains("radial"));
		assertTrue("Should contain grid", algorithms.contains("grid"));
		assertTrue("Should contain horizontal-tree", algorithms.contains("horizontal-tree"));
	}

	// --- Integration tests: overlap resolution cross-validated with LayoutQualityAssessor (Story 10-18) ---

	private List<AssessmentNode> toAssessmentNodes(List<ViewPositionSpec> positions) {
		List<AssessmentNode> nodes = new ArrayList<>();
		for (ViewPositionSpec pos : positions) {
			nodes.add(new AssessmentNode(pos.viewObjectId(),
					pos.x(), pos.y(), pos.width(), pos.height(), null, false, false, null, 0.0, null, null));
		}
		return nodes;
	}

	@Test
	public void computeLayout_withDirectedAlgorithm_shouldProduceZeroOverlapsOnDenseInput() {
		// 28 nodes in a tight cluster — matches AC #2 element count
		List<LayoutNode> nodes = new ArrayList<>();
		for (int i = 0; i < 28; i++) {
			nodes.add(new LayoutNode("d-" + i, 50 + i * 5, 50 + i * 5, 120, 55, null));
		}
		List<LayoutEdge> dEdges = new ArrayList<>();
		for (int i = 0; i < 27; i++) {
			dEdges.add(new LayoutEdge("d-" + i, "d-" + (i + 1), null));
		}

		List<ViewPositionSpec> result = engine.computeLayout(nodes, dEdges, "directed", Map.of("spacing", 120));

		// Cross-validate with LayoutQualityAssessor
		LayoutQualityAssessor assessor = new LayoutQualityAssessor();
		List<AssessmentNode> assessmentNodes = toAssessmentNodes(result);
		LayoutQualityAssessor.OverlapResult overlapResult = assessor.computeOverlaps(assessmentNodes, Set.of(), false);
		assertEquals("directed layout should have 0 overlaps after resolution", 0, overlapResult.siblingCount());

		// AC #2: average spacing must be positive (not 0.0 as before overlap resolution)
		double avgSpacing = assessor.computeAverageSpacing(assessmentNodes, Set.of());
		assertTrue("directed layout average spacing should be > 0, was " + avgSpacing,
				avgSpacing > 0.0);
	}

	@Test
	public void computeLayout_withTreeAlgorithm_shouldProduceZeroOverlaps() {
		List<LayoutNode> nodes = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			nodes.add(new LayoutNode("t-" + i, 20 + i * 8, 20 + i * 8, 120, 55, null));
		}
		List<LayoutEdge> tEdges = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			tEdges.add(new LayoutEdge("t-" + i, "t-" + (i + 1), null));
		}

		List<ViewPositionSpec> result = engine.computeLayout(nodes, tEdges, "tree", Map.of());

		LayoutQualityAssessor assessor = new LayoutQualityAssessor();
		LayoutQualityAssessor.OverlapResult overlapResult = assessor.computeOverlaps(
				toAssessmentNodes(result), Set.of(), false);
		assertEquals("tree layout should have 0 overlaps after resolution", 0, overlapResult.siblingCount());
	}

	@Test
	public void computeLayout_withSpaciousPreset_shouldProduceZeroOverlaps() {
		// Simulate spacious preset: spring algorithm with spacing=120, 28 elements with varying widths (AC #3)
		int[] widths = {80, 120, 160, 200, 100, 140, 90, 180, 110, 150,
				70, 130, 170, 120, 95, 145, 85, 175, 105, 155,
				115, 135, 125, 165, 75, 190, 100, 140};
		List<LayoutNode> nodes = new ArrayList<>();
		for (int i = 0; i < 28; i++) {
			nodes.add(new LayoutNode("s-" + i, 10 + i * 3, 10 + i * 3, widths[i], 55, null));
		}
		List<LayoutEdge> sEdges = new ArrayList<>();
		for (int i = 0; i < 27; i++) {
			sEdges.add(new LayoutEdge("s-" + i, "s-" + (i + 1), null));
		}

		List<ViewPositionSpec> result = engine.computeLayout(nodes, sEdges, "spring", Map.of("spacing", 120));

		LayoutQualityAssessor assessor = new LayoutQualityAssessor();
		LayoutQualityAssessor.OverlapResult overlapResult = assessor.computeOverlaps(
				toAssessmentNodes(result), Set.of(), false);
		assertEquals("spacious preset should have 0 overlaps after resolution", 0, overlapResult.siblingCount());
	}

	@Test
	public void computeLayout_withSpacing_shouldAffectAverageGap() {
		List<LayoutNode> nodes = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			nodes.add(new LayoutNode("g-" + i, 10 + i * 5, 10, 120, 55, null));
		}

		List<ViewPositionSpec> tightResult = engine.computeLayout(nodes, List.of(), "grid", Map.of("spacing", 20));
		List<ViewPositionSpec> wideResult = engine.computeLayout(nodes, List.of(), "grid", Map.of("spacing", 100));

		LayoutQualityAssessor assessor = new LayoutQualityAssessor();
		double tightSpacing = assessor.computeAverageSpacing(toAssessmentNodes(tightResult), Set.of());
		double wideSpacing = assessor.computeAverageSpacing(toAssessmentNodes(wideResult), Set.of());

		assertTrue("Wider spacing option should produce larger average gap, tight=" + tightSpacing + " wide=" + wideSpacing,
				wideSpacing > tightSpacing);
	}

	private void assertNonNegativePositions(List<ViewPositionSpec> positions) {
		for (ViewPositionSpec pos : positions) {
			assertTrue("x should be non-negative for " + pos.viewObjectId(),
					pos.x() != null && pos.x() >= 0);
			assertTrue("y should be non-negative for " + pos.viewObjectId(),
					pos.y() != null && pos.y() >= 0);
		}
	}
}
