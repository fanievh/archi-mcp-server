package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link OverlapResolver#findOverlappingElementIds(List)}.
 *
 * <p>Pure geometry — runnable without OSGi. Paired with
 * {@link HasOverlappingElementsTest} under the "paired test class"
 * allowance (the {@code ArchiModelAccessorImpl.autoRouteConnections} method is
 * OSGi-dependent and tested via E2E integration plus live-MCP smoke per Task
 * 6, not via this unit-test class).</p>
 *
 * <p>Mirrors the {@link OverlapResolver#hasOverlappingElements(List)} loop
 * exactly — same group/note exclusions, same containment exclusions, same
 * short-circuit behaviour — so the helper returning the first-detected pair
 * is the semantic source of the {@code remediationViolatorIds} field
 * surfaced via {@code structuredWarnings} on the {@code auto-route-connections}
 * response DTO.</p>
 */
public class FindOverlappingElementIdsTest {

	// --- Helper (mirrors HasOverlappingElementsTest patterns) ---

	private static AssessmentNode element(String id, double x, double y, double w, double h) {
		return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
	}

	private static AssessmentNode elementWithParent(String id, double x, double y, double w, double h, String parentId) {
		return new AssessmentNode(id, x, y, w, h, parentId, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
	}

	private static AssessmentNode group(String id, double x, double y, double w, double h) {
		return new AssessmentNode(id, x, y, w, h, null, true, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
	}

	private static AssessmentNode note(String id, double x, double y, double w, double h) {
		return new AssessmentNode(id, x, y, w, h, null, false, true, null, 0.0, null, null, 0.0, 0.0, 0.0);
	}

	// --- Empty result cases ---

	@Test
	public void shouldReturnEmpty_whenNoElements() {
		assertTrue(OverlapResolver.findOverlappingElementIds(List.of()).isEmpty());
	}

	@Test
	public void shouldReturnEmpty_whenSingleElement() {
		List<AssessmentNode> nodes = List.of(element("a", 100, 100, 140, 70));
		assertTrue(OverlapResolver.findOverlappingElementIds(nodes).isEmpty());
	}

	@Test
	public void shouldReturnEmpty_whenElementsWellSeparated() {
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 140, 70),
				element("b", 300, 100, 140, 70),
				element("c", 100, 300, 140, 70));
		assertTrue(OverlapResolver.findOverlappingElementIds(nodes).isEmpty());
	}

	// --- Overlap cases ---

	@Test
	public void shouldReturnPair_whenElementsOverlapHorizontally() {
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 140, 70),
				element("b", 200, 100, 140, 70));   // overlaps a
		List<String> result = OverlapResolver.findOverlappingElementIds(nodes);
		assertEquals(List.of("a", "b"), result);
	}

	@Test
	public void shouldReturnPair_whenElementsOverlapVertically() {
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 140, 70),
				element("b", 100, 150, 140, 70));   // overlaps a
		List<String> result = OverlapResolver.findOverlappingElementIds(nodes);
		assertEquals(List.of("a", "b"), result);
	}

	// --- First-pair-only short-circuit (Task 1.5 design decision) ---

	@Test
	public void shouldReturnFirstPair_whenMultipleOverlapsExist() {
		// Three pairwise overlaps exist among (a,b), (a,c), (b,c).
		// The helper short-circuits on the FIRST iteration that finds an overlap
		// (Task 1.5 first-pair design decision).
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 200, 200),
				element("b", 150, 150, 200, 200),
				element("c", 200, 200, 200, 200));
		List<String> result = OverlapResolver.findOverlappingElementIds(nodes);
		assertEquals("first-pair short-circuit must return exactly two element IDs",
				2, result.size());
		assertEquals("a", result.get(0));
		assertEquals("b", result.get(1));
	}

	// --- Exclusions (mirror predicate behaviour) ---

	@Test
	public void shouldReturnEmpty_whenOnlyGroupsOverlap() {
		// Group-vs-group overlaps don't count (only sibling element overlaps).
		List<AssessmentNode> nodes = List.of(
				group("g1", 100, 100, 300, 200),
				group("g2", 200, 100, 300, 200));
		assertTrue(OverlapResolver.findOverlappingElementIds(nodes).isEmpty());
	}

	@Test
	public void shouldReturnEmpty_whenOnlyNotesOverlap() {
		List<AssessmentNode> nodes = List.of(
				note("n1", 100, 100, 200, 100),
				note("n2", 150, 100, 200, 100));
		assertTrue(OverlapResolver.findOverlappingElementIds(nodes).isEmpty());
	}

	@Test
	public void shouldReturnEmpty_whenChildNestedInsideParent() {
		// Containment overlap (parent ↑ child) is intentional, not degenerate.
		List<AssessmentNode> nodes = List.of(
				element("parent", 100, 100, 400, 300),
				elementWithParent("child", 150, 150, 100, 100, "parent"));
		assertTrue(OverlapResolver.findOverlappingElementIds(nodes).isEmpty());
	}

	@Test
	public void shouldReturnPair_whenSiblingsOverlapInsideParent() {
		// Two siblings whose bounds overlap inside the same parent — degenerate.
		List<AssessmentNode> nodes = List.of(
				element("parent", 0, 0, 600, 400),
				elementWithParent("childA", 100, 100, 200, 100, "parent"),
				elementWithParent("childB", 150, 100, 200, 100, "parent"));
		List<String> result = OverlapResolver.findOverlappingElementIds(nodes);
		assertEquals(List.of("childA", "childB"), result);
	}
}
