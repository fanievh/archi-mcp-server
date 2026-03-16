package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.math.KVector;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.PortAlignment;
import org.eclipse.elk.core.options.SizeConstraint;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkBendPoint;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.elk.core.data.LayoutMetaDataService;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Computes element positions and connection routes using ELK Layered algorithm.
 * Pure-geometry class — no EMF/SWT/OSGi dependencies. ELK operates on its own
 * ElkGraph model.
 *
 * <p>Unlike {@link LayoutEngine} (Zest, positions only), this engine computes
 * BOTH element positions AND connection routes simultaneously using the
 * ELK Layered (Sugiyama) algorithm.</p>
 *
 * <p>Package-visible — only used by {@link ArchiModelAccessorImpl}.</p>
 */
class ElkLayoutEngine {

	private static final Logger logger = LoggerFactory.getLogger(ElkLayoutEngine.class);

	private static final double DEFAULT_SPACING = 50.0;

	private static volatile boolean elkProvidersRegistered = false;

	/**
	 * Computes layout positions and connection routes for the given nodes and edges.
	 *
	 * @param nodes     view objects with current positions and sizes
	 * @param edges     connections between view objects
	 * @param direction layout direction: DOWN, RIGHT, UP, LEFT (default DOWN)
	 * @param spacing   inter-element spacing in pixels (default 50)
	 * @return ElkLayoutResult with element positions and connection bendpoints
	 */
	ElkLayoutResult computeLayout(List<LayoutNode> nodes, List<LayoutEdge> edges,
			String direction, int spacing) {
		logger.info("ELK layout: nodes={}, edges={}, direction={}, spacing={}",
				nodes.size(), edges.size(), direction, spacing);

		// Lazily register ELK algorithm providers so that
		// RecursiveGraphLayoutEngine can resolve "org.eclipse.elk.layered".
		// In OSGi, ServiceLoader cannot discover META-INF/services inside
		// Bundle-ClassPath JARs — explicit registration is required.
		if (!elkProvidersRegistered) {
			LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(
					new CoreOptions(),
					new LayeredOptions());
			elkProvidersRegistered = true;
		}

		if (nodes.isEmpty()) {
			throw new ModelAccessException("No nodes to layout", ErrorCode.INVALID_PARAMETER);
		}

		double effectiveSpacing = spacing > 0 ? spacing : DEFAULT_SPACING;
		Direction dir = resolveDirection(direction);

		// Build ELK graph
		ElkNode rootGraph = ElkGraphUtil.createGraph();
		rootGraph.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
		rootGraph.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
		rootGraph.setProperty(LayeredOptions.PORT_ALIGNMENT_DEFAULT, PortAlignment.DISTRIBUTED);
		rootGraph.setProperty(CoreOptions.DIRECTION, dir);
		rootGraph.setProperty(CoreOptions.SPACING_NODE_NODE, effectiveSpacing);
		rootGraph.setProperty(CoreOptions.SPACING_EDGE_NODE, effectiveSpacing / 2);
		rootGraph.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, effectiveSpacing);

		// Build node hierarchy: separate top-level and nested nodes
		Map<String, ElkNode> elkNodes = new LinkedHashMap<>();
		Map<String, LayoutNode> nodeById = new LinkedHashMap<>();
		for (LayoutNode node : nodes) {
			nodeById.put(node.viewObjectId(), node);
		}

		// Pre-identify parent IDs (nodes that have children)
		Set<String> parentIds = new HashSet<>();
		for (LayoutNode node : nodes) {
			if (node.parentId() != null) {
				parentIds.add(node.parentId());
			}
		}

		// Scale group padding with spacing — groups need internal breathing room.
		// Top padding is larger to accommodate the group label text (Archi renders
		// labels at the top of container elements; GROUP_LABEL_HEIGHT ≈ 24px).
		double topPad = Math.max(25, 24 + effectiveSpacing * 0.3);
		double sidePad = Math.max(12, effectiveSpacing * 0.25);
		ElkPadding groupPadding = new ElkPadding(topPad, sidePad, sidePad, sidePad);

		// First pass: create top-level nodes, pre-configure parent properties
		// BEFORE children are added (ELK reads properties at layout time but
		// setting them before child creation ensures correct subgraph setup)
		for (LayoutNode node : nodes) {
			if (node.parentId() == null) {
				ElkNode elkNode = createElkNode(rootGraph, node);
				elkNodes.put(node.viewObjectId(), elkNode);

				// Configure parent subgraph properties if this node has children
				if (parentIds.contains(node.viewObjectId())) {
					configureParentSubgraph(elkNode, node, groupPadding,
							dir, effectiveSpacing);
				}
			}
		}

		// Second pass: create child nodes inside parents (hierarchical support)
		for (LayoutNode node : nodes) {
			if (node.parentId() != null) {
				ElkNode parentElk = elkNodes.get(node.parentId());
				if (parentElk != null) {
					// Child coordinates are already relative to parent in Archi
					ElkNode childElk = createElkNode(parentElk, node);
					elkNodes.put(node.viewObjectId(), childElk);

					// If this child is also a parent (intermediate nesting),
					// configure subgraph properties for its own children
					if (parentIds.contains(node.viewObjectId())) {
						configureParentSubgraph(childElk, node, groupPadding,
								dir, effectiveSpacing);
					}
				} else {
					// Parent not found — treat as top-level
					ElkNode elkNode = createElkNode(rootGraph, node);
					elkNodes.put(node.viewObjectId(), elkNode);
				}
			}
		}

		// Create edges — use connectionId as identifier when available
		// to avoid collisions when multiple connections exist between
		// the same source/target pair
		Map<String, ElkEdge> elkEdges = new LinkedHashMap<>();
		for (LayoutEdge edge : edges) {
			ElkNode srcNode = elkNodes.get(edge.sourceViewObjectId());
			ElkNode tgtNode = elkNodes.get(edge.targetViewObjectId());
			if (srcNode == null || tgtNode == null) {
				continue;
			}

			// Edges must be contained in the lowest common ancestor
			ElkNode container = findCommonAncestor(srcNode, tgtNode);
			ElkEdge elkEdge = ElkGraphUtil.createEdge(container);
			String edgeId = edge.connectionId() != null
					? edge.connectionId()
					: edge.sourceViewObjectId() + "->" + edge.targetViewObjectId();
			elkEdge.setIdentifier(edgeId);
			elkEdge.getSources().add(srcNode);
			elkEdge.getTargets().add(tgtNode);
			elkEdges.put(edgeId, elkEdge);
		}

		// Run ELK layout
		try {
			RecursiveGraphLayoutEngine engine = new RecursiveGraphLayoutEngine();
			engine.layout(rootGraph, new BasicProgressMonitor());
		} catch (Exception e) {
			throw new ModelAccessException(
					"ELK layout failed: " + e.getMessage(), e,
					ErrorCode.INTERNAL_ERROR);
		}

		// Extract computed positions
		List<ViewPositionSpec> positions = new ArrayList<>();
		for (LayoutNode node : nodes) {
			ElkNode elkNode = elkNodes.get(node.viewObjectId());
			if (elkNode == null) continue;

			int newX = (int) Math.round(elkNode.getX());
			int newY = (int) Math.round(elkNode.getY());
			int newW = (int) Math.round(elkNode.getWidth());
			int newH = (int) Math.round(elkNode.getHeight());

			// For nested elements, ELK positions are relative to parent — matches Archi's model
			if (newX < 0) newX = 0;
			if (newY < 0) newY = 0;

			positions.add(new ViewPositionSpec(node.viewObjectId(), newX, newY, newW, newH));
		}

		// Extract connection bendpoints
		Map<String, List<AbsoluteBendpointDto>> connectionBendpoints = new LinkedHashMap<>();
		extractBendpoints(rootGraph, connectionBendpoints);

		int connectionsRouted = connectionBendpoints.size();

		logger.info("ELK layout computed: {} positions, {} connections routed",
				positions.size(), connectionsRouted);

		return new ElkLayoutResult(positions, connectionBendpoints,
				positions.size(), connectionsRouted);
	}

	private ElkNode createElkNode(ElkNode parent, LayoutNode node) {
		ElkNode elkNode = ElkGraphUtil.createNode(parent);
		elkNode.setIdentifier(node.viewObjectId());
		elkNode.setX(node.x());
		elkNode.setY(node.y());
		elkNode.setWidth(node.width());
		elkNode.setHeight(node.height());
		// Preserve original element dimensions — prevent ELK from resizing
		// leaf nodes to fit labels/ports. Parent nodes get overridden
		// with MINIMUM_SIZE in the second pass to allow expansion for children.
		// SizeConstraint.fixed() returns an empty EnumSet = no size constraints = fixed size.
		elkNode.setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS, SizeConstraint.fixed());
		return elkNode;
	}

	/**
	 * Configures an ElkNode as a parent subgraph with scaled padding,
	 * layout algorithm, and spacing properties.
	 */
	private void configureParentSubgraph(ElkNode elkNode, LayoutNode node,
			ElkPadding padding, Direction dir, double effectiveSpacing) {
		elkNode.setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS,
				EnumSet.of(SizeConstraint.MINIMUM_SIZE));
		elkNode.setProperty(CoreOptions.NODE_SIZE_MINIMUM,
				new KVector(node.width(), node.height()));
		elkNode.setProperty(CoreOptions.PADDING, padding);
		elkNode.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
		elkNode.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
		elkNode.setProperty(CoreOptions.DIRECTION, dir);
		elkNode.setProperty(CoreOptions.SPACING_NODE_NODE, effectiveSpacing);
		elkNode.setProperty(CoreOptions.SPACING_EDGE_NODE, effectiveSpacing / 2);
		elkNode.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, effectiveSpacing);
		// Disconnected children are separate connected components —
		// their spacing is controlled by SPACING_COMPONENT_COMPONENT
		elkNode.setProperty(CoreOptions.SPACING_COMPONENT_COMPONENT, effectiveSpacing);
	}

	private ElkNode findCommonAncestor(ElkNode a, ElkNode b) {
		// Walk up from a and b to find common parent
		ElkNode nodeA = a.getParent();
		ElkNode nodeB = b.getParent();

		if (nodeA == nodeB) {
			return nodeA;
		}

		// Collect all ancestors of a
		List<ElkNode> ancestorsA = new ArrayList<>();
		ElkNode current = a.getParent();
		while (current != null) {
			ancestorsA.add(current);
			current = current.getParent();
		}

		// Walk up from b to find first match
		current = b.getParent();
		while (current != null) {
			if (ancestorsA.contains(current)) {
				return current;
			}
			current = current.getParent();
		}

		// Should not happen — both should at least share the root
		return a.getParent();
	}

	/**
	 * Recursively extracts bendpoints from all edges in the graph hierarchy.
	 */
	private void extractBendpoints(ElkNode container,
			Map<String, List<AbsoluteBendpointDto>> result) {
		for (ElkEdge edge : container.getContainedEdges()) {
			List<AbsoluteBendpointDto> bps = new ArrayList<>();
			for (ElkEdgeSection section : edge.getSections()) {
				// Only include INTERMEDIATE bendpoints — not start/end points.
				// ELK start/end points are edge attachment coords on the node
				// boundary. Archi's ChopboxAnchor computes edge-to-element
				// intersections automatically from the first/last bendpoint
				// direction. Including start/end as bendpoints would create
				// segments that terminate inside the element instead of at
				// the edge.
				for (ElkBendPoint bp : section.getBendPoints()) {
					bps.add(new AbsoluteBendpointDto(
							(int) Math.round(bp.getX()),
							(int) Math.round(bp.getY())));
				}
			}
			// Store bendpoints even if empty (clears any previous bendpoints)
			result.put(edge.getIdentifier(), bps);
		}

		// Recurse into child nodes for hierarchical graphs
		for (ElkNode child : container.getChildren()) {
			extractBendpoints(child, result);
		}
	}

	private Direction resolveDirection(String direction) {
		if (direction == null || direction.isBlank()) {
			return Direction.DOWN;
		}
		switch (direction.toUpperCase()) {
		case "DOWN":
			return Direction.DOWN;
		case "RIGHT":
			return Direction.RIGHT;
		case "UP":
			return Direction.UP;
		case "LEFT":
			return Direction.LEFT;
		default:
			throw new ModelAccessException(
					"Invalid direction '" + direction + "'. Valid: DOWN, RIGHT, UP, LEFT",
					ErrorCode.INVALID_PARAMETER);
		}
	}
}
