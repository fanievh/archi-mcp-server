package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Post-layout sweep-line push-apart pass that guarantees zero element overlaps.
 * Pure geometry — no EMF, Zest, or SWT imports (Layer 3 compliance).
 *
 * <p>Groups elements by parentId (null = top-level siblings) and resolves
 * overlaps within each sibling group independently. Elements in different
 * groups are never compared or pushed apart.</p>
 */
class OverlapResolver {

	private static final Logger logger = LoggerFactory.getLogger(OverlapResolver.class);

	private static final int MAX_ITERATIONS = 10;

	/**
	 * Mutable position holder used during resolution.
	 */
	static class MutableRect {
		final String id;
		double x;
		double y;
		final double width;
		final double height;

		MutableRect(String id, double x, double y, double width, double height) {
			this.id = id;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}
	}

	/**
	 * Resolves overlaps among the given positions, grouped by parentId from the
	 * layout nodes. Returns updated positions with zero overlaps (best-effort if
	 * max iterations reached).
	 *
	 * @param positions computed layout positions
	 * @param nodes     original layout nodes (for parentId grouping)
	 * @param spacing   minimum gap between elements
	 * @return updated positions with overlaps resolved
	 */
	List<ViewPositionSpec> resolve(
			List<ViewPositionSpec> positions,
			List<LayoutNode> nodes, double spacing) {

		if (positions.size() <= 1) {
			return positions;
		}

		// Build parentId lookup from nodes
		Map<String, String> parentIdMap = new HashMap<>();
		for (LayoutNode node : nodes) {
			parentIdMap.put(node.viewObjectId(), node.parentId());
		}

		// Build mutable rects from positions
		Map<String, MutableRect> rectMap = new HashMap<>();
		for (var pos : positions) {
			rectMap.put(pos.viewObjectId(), new MutableRect(
					pos.viewObjectId(),
					pos.x() != null ? pos.x() : 0,
					pos.y() != null ? pos.y() : 0,
					pos.width() != null ? pos.width() : 120,
					pos.height() != null ? pos.height() : 55));
		}

		// Group by parentId
		Map<String, List<MutableRect>> groups = new HashMap<>();
		for (var pos : positions) {
			String parentId = parentIdMap.getOrDefault(pos.viewObjectId(), null);
			String key = parentId != null ? parentId : "__top_level__";
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(rectMap.get(pos.viewObjectId()));
		}

		// Resolve each group independently
		for (var entry : groups.entrySet()) {
			List<MutableRect> group = entry.getValue();
			if (group.size() <= 1) {
				continue;
			}
			resolveGroup(group, spacing);
		}

		// Build result positions from mutable rects
		List<ViewPositionSpec> result = new ArrayList<>();
		for (var pos : positions) {
			MutableRect rect = rectMap.get(pos.viewObjectId());
			result.add(new ViewPositionSpec(
					rect.id,
					(int) Math.round(rect.x),
					(int) Math.round(rect.y),
					(int) Math.round(rect.width),
					(int) Math.round(rect.height)));
		}
		return result;
	}

	private void resolveGroup(List<MutableRect> group, double spacing) {
		for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
			// Horizontal pass: sort by x, push right
			group.sort((a, b) -> Double.compare(a.x, b.x));
			for (int i = 0; i < group.size() - 1; i++) {
				MutableRect a = group.get(i);
				for (int j = i + 1; j < group.size(); j++) {
					MutableRect b = group.get(j);
					if (rectanglesOverlap(a, b)) {
						double requiredX = a.x + a.width + spacing;
						if (b.x < requiredX) {
							b.x = requiredX;
						}
					}
				}
			}

			// Vertical pass: sort by y, push down
			group.sort((a, b) -> Double.compare(a.y, b.y));
			for (int i = 0; i < group.size() - 1; i++) {
				MutableRect a = group.get(i);
				for (int j = i + 1; j < group.size(); j++) {
					MutableRect b = group.get(j);
					if (rectanglesOverlap(a, b)) {
						double requiredY = a.y + a.height + spacing;
						if (b.y < requiredY) {
							b.y = requiredY;
						}
					}
				}
			}

			// Check convergence
			int overlapCount = countOverlaps(group);
			if (overlapCount == 0) {
				logger.debug("Overlap resolution converged in {} iteration(s)", iteration + 1);
				return;
			}
		}

		int remaining = countOverlaps(group);
		logger.warn("Overlap resolution did not converge after {} iterations. {} overlaps remain.",
				MAX_ITERATIONS, remaining);
	}

	/**
	 * AABB overlap test — consistent with {@code LayoutQualityAssessor.rectanglesOverlap()}.
	 */
	private boolean rectanglesOverlap(MutableRect a, MutableRect b) {
		return a.x < b.x + b.width
				&& a.x + a.width > b.x
				&& a.y < b.y + b.height
				&& a.y + a.height > b.y;
	}

	private int countOverlaps(List<MutableRect> group) {
		int count = 0;
		for (int i = 0; i < group.size(); i++) {
			for (int j = i + 1; j < group.size(); j++) {
				if (rectanglesOverlap(group.get(i), group.get(j))) {
					count++;
				}
			}
		}
		return count;
	}
}
