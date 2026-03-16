package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.zest.layouts.LayoutEntity;
import org.eclipse.zest.layouts.LayoutRelationship;
import org.eclipse.zest.layouts.LayoutStyles;
import org.eclipse.zest.layouts.algorithms.AbstractLayoutAlgorithm;
import org.eclipse.zest.layouts.algorithms.DirectedGraphLayoutAlgorithm;
import org.eclipse.zest.layouts.algorithms.GridLayoutAlgorithm;
import org.eclipse.zest.layouts.algorithms.HorizontalTreeLayoutAlgorithm;
import org.eclipse.zest.layouts.algorithms.RadialLayoutAlgorithm;
import org.eclipse.zest.layouts.algorithms.SpringLayoutAlgorithm;
import org.eclipse.zest.layouts.algorithms.TreeLayoutAlgorithm;
import org.eclipse.zest.layouts.exampleStructures.SimpleNode;
import org.eclipse.zest.layouts.exampleStructures.SimpleRelationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Computes layout positions for view elements using Zest layout algorithms.
 * Package-visible — only used by {@link ArchiModelAccessorImpl}.
 */
class LayoutEngine {

	private static final Logger logger = LoggerFactory.getLogger(LayoutEngine.class);

	private static final double DEFAULT_SPACING = 50.0;
	private static final double CANVAS_PADDING = 20.0;

	private static final Map<String, String> ALGORITHM_DESCRIPTIONS;

	static {
		ALGORITHM_DESCRIPTIONS = new LinkedHashMap<>();
		ALGORITHM_DESCRIPTIONS.put("tree", "top-down hierarchical tree");
		ALGORITHM_DESCRIPTIONS.put("spring", "force-directed/spring-based");
		ALGORITHM_DESCRIPTIONS.put("directed", "Sugiyama-style layered hierarchy");
		ALGORITHM_DESCRIPTIONS.put("radial", "concentric circles");
		ALGORITHM_DESCRIPTIONS.put("grid", "regular grid arrangement");
		ALGORITHM_DESCRIPTIONS.put("horizontal-tree", "left-to-right tree");
	}

	/**
	 * Computes layout positions for the given nodes using the specified algorithm.
	 *
	 * @param nodes         view objects with current positions
	 * @param edges         connections between view objects
	 * @param algorithmName name of the layout algorithm
	 * @param options       optional configuration (e.g., "spacing")
	 * @return computed positions for all nodes
	 */
	List<ViewPositionSpec> computeLayout(List<LayoutNode> nodes, List<LayoutEdge> edges, String algorithmName,
			Map<String, Object> options) {
		logger.info("Computing layout: algorithm={}, nodes={}, edges={}", algorithmName, nodes.size(), edges.size());

		if (nodes.isEmpty()) {
			throw new ModelAccessException("No nodes to layout", ErrorCode.INVALID_PARAMETER);
		}

		// Compute spacing once (used for algorithm config, canvas bounds, and overlap resolution)
		double spacing = resolveSpacing(options);

		AbstractLayoutAlgorithm algorithm = resolveAlgorithm(algorithmName, spacing);

		// Build Zest entities
		Map<String, SimpleNode> nodeMap = new HashMap<>();
		List<SimpleNode> simpleNodes = new ArrayList<>();
		for (LayoutNode node : nodes) {
			SimpleNode sn = new SimpleNode(node.viewObjectId(), node.x(), node.y(), node.width(), node.height());
			nodeMap.put(node.viewObjectId(), sn);
			simpleNodes.add(sn);
		}

		List<SimpleRelationship> simpleRels = new ArrayList<>();
		for (LayoutEdge edge : edges) {
			SimpleNode source = nodeMap.get(edge.sourceViewObjectId());
			SimpleNode target = nodeMap.get(edge.targetViewObjectId());
			if (source != null && target != null) {
				simpleRels.add(new SimpleRelationship(source, target, false));
			}
		}

		LayoutEntity[] entities = simpleNodes.toArray(new LayoutEntity[0]);
		LayoutRelationship[] relationships = simpleRels.toArray(new LayoutRelationship[0]);
		double avgWidth = nodes.stream().mapToDouble(LayoutNode::width).average().orElse(120);
		double avgHeight = nodes.stream().mapToDouble(LayoutNode::height).average().orElse(55);
		int side = (int) Math.ceil(Math.sqrt(nodes.size()));
		double canvasWidth = side * (avgWidth + spacing) + CANVAS_PADDING * 2;
		double canvasHeight = side * (avgHeight + spacing) + CANVAS_PADDING * 2;

		// Run layout algorithm
		try {
			algorithm.applyLayout(entities, relationships, CANVAS_PADDING, CANVAS_PADDING, canvasWidth, canvasHeight,
					false, false);
		} catch (Exception e) {
			throw new ModelAccessException("Layout algorithm '" + algorithmName + "' failed: " + e.getMessage(), e,
					ErrorCode.INTERNAL_ERROR);
		}

		// Extract computed positions
		List<ViewPositionSpec> positions = new ArrayList<>();
		for (SimpleNode sn : simpleNodes) {
			String id = (String) sn.getRealObject();
			int newX = (int) Math.round(sn.getXInLayout());
			int newY = (int) Math.round(sn.getYInLayout());
			int newW = (int) Math.round(sn.getWidthInLayout());
			int newH = (int) Math.round(sn.getHeightInLayout());

			// Ensure non-negative coordinates
			if (newX < 0)
				newX = 0;
			if (newY < 0)
				newY = 0;

			positions.add(new ViewPositionSpec(id, newX, newY, newW, newH));
		}

		// Post-layout overlap resolution pass
		OverlapResolver resolver = new OverlapResolver();
		positions = resolver.resolve(positions, nodes, spacing);

		logger.info("Layout computed: {} positions", positions.size());
		return positions;
	}

	/**
	 * Returns a formatted string listing all valid algorithm names with
	 * descriptions.
	 */
	String listAlgorithms() {
		StringBuilder sb = new StringBuilder();
		ALGORITHM_DESCRIPTIONS.forEach((name, desc) -> {
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(name).append(" (").append(desc).append(")");
		});
		return sb.toString();
	}

	private AbstractLayoutAlgorithm resolveAlgorithm(String name, double spacing) {
		int style = LayoutStyles.NO_LAYOUT_NODE_RESIZING;

		switch (name) {
		case "tree":
			return new TreeLayoutAlgorithm(style);
		case "spring":
			SpringLayoutAlgorithm spring = new SpringLayoutAlgorithm(style);
			spring.setSpringLength(spacing);
			return spring;
		case "directed":
			return new DirectedGraphLayoutAlgorithm(style);
		case "radial":
			return new RadialLayoutAlgorithm(style);
		case "grid":
			GridLayoutAlgorithm grid = new GridLayoutAlgorithm(style);
			grid.setRowPadding((int) spacing);
			return grid;
		case "horizontal-tree":
			return new HorizontalTreeLayoutAlgorithm(style);
		default:
			throw new ModelAccessException(
					"Invalid algorithm '" + name + "'. Valid algorithms: " + listAlgorithms(),
					ErrorCode.INVALID_PARAMETER);
		}
	}

	private double resolveSpacing(Map<String, Object> options) {
		if (options == null)
			return DEFAULT_SPACING;
		Object spacingObj = options.get("spacing");
		if (spacingObj instanceof Number n) {
			return n.doubleValue();
		}
		return DEFAULT_SPACING;
	}

}
