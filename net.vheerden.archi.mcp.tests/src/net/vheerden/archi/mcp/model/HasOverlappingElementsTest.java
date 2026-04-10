package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link OverlapResolver#hasOverlappingElements(List)} (Story 13-9).
 * Pure geometry — runnable without OSGi.
 */
public class HasOverlappingElementsTest {

	// --- Helper ---

	private static AssessmentNode element(String id, double x, double y, double w, double h) {
		return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null);
	}

	private static AssessmentNode elementWithParent(String id, double x, double y, double w, double h, String parentId) {
		return new AssessmentNode(id, x, y, w, h, parentId, false, false, null, 0.0, null, null);
	}

	private static AssessmentNode group(String id, double x, double y, double w, double h) {
		return new AssessmentNode(id, x, y, w, h, null, true, false, null, 0.0, null, null);
	}

	private static AssessmentNode groupWithParent(String id, double x, double y, double w, double h, String parentId) {
		return new AssessmentNode(id, x, y, w, h, parentId, true, false, null, 0.0, null, null);
	}

	private static AssessmentNode note(String id, double x, double y, double w, double h) {
		return new AssessmentNode(id, x, y, w, h, null, false, true, null, 0.0, null, null);
	}

	// --- No overlap cases ---

	@Test
	public void shouldReturnFalse_whenNoElements() {
		assertFalse(OverlapResolver.hasOverlappingElements(List.of()));
	}

	@Test
	public void shouldReturnFalse_whenSingleElement() {
		List<AssessmentNode> nodes = List.of(element("a", 100, 100, 140, 70));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnFalse_whenElementsWellSeparated() {
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 140, 70),
				element("b", 300, 100, 140, 70),
				element("c", 100, 300, 140, 70));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnFalse_whenElementsTouching() {
		// Zero-gap: right edge of A touches left edge of B (x+w == x2)
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 140, 70),
				element("b", 240, 100, 140, 70));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnFalse_whenElementsTouchingVertically() {
		// Zero-gap: bottom edge of A touches top edge of B
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 140, 70),
				element("b", 100, 170, 140, 70));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	// --- Overlap cases ---

	@Test
	public void shouldReturnTrue_whenElementsOverlapHorizontally() {
		// 10px horizontal overlap
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 140, 70),
				element("b", 230, 100, 140, 70));
		assertTrue(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnTrue_whenElementsOverlapVertically() {
		// 10px vertical overlap
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 140, 70),
				element("b", 100, 160, 140, 70));
		assertTrue(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnTrue_whenElementsOverlapOnAllSides() {
		// Reproduction from story: hub element with 10px overlap on all sides
		List<AssessmentNode> nodes = List.of(
				element("hub", 300, 250, 140, 70),
				element("right", 430, 250, 140, 70),   // overlaps right by 10
				element("left", 170, 250, 140, 70),    // overlaps left by 10
				element("top", 300, 190, 140, 70),     // overlaps top by 10
				element("bottom", 300, 310, 140, 70),  // overlaps bottom by 10
				element("far", 700, 50, 140, 70));     // no overlap
		assertTrue(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnTrue_whenElementsCompletelyContained() {
		// Element B entirely inside element A
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 300, 300),
				element("b", 150, 150, 50, 50));
		assertTrue(OverlapResolver.hasOverlappingElements(nodes));
	}

	// --- Groups and notes excluded ---

	@Test
	public void shouldReturnFalse_whenOnlyGroupsOverlap() {
		// Groups are excluded from overlap check
		List<AssessmentNode> nodes = List.of(
				group("g1", 100, 100, 200, 200),
				group("g2", 150, 150, 200, 200));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnFalse_whenOnlyNotesOverlap() {
		// Notes are excluded from overlap check
		List<AssessmentNode> nodes = List.of(
				note("n1", 100, 100, 200, 100),
				note("n2", 150, 150, 200, 100));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnFalse_whenElementOverlapsGroupOnly() {
		// Element overlaps a group but not another element — no problem
		List<AssessmentNode> nodes = List.of(
				element("a", 150, 150, 140, 70),
				group("g1", 100, 100, 300, 300));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnTrue_whenElementsOverlapAmidGroupsAndNotes() {
		// Elements overlap even though groups and notes are present
		List<AssessmentNode> nodes = List.of(
				group("g1", 0, 0, 500, 500),
				element("a", 100, 100, 140, 70),
				element("b", 230, 100, 140, 70),  // overlaps A by 10px
				note("n1", 100, 200, 200, 50));
		assertTrue(OverlapResolver.hasOverlappingElements(nodes));
	}

	// --- Edge case: 1px overlap ---

	@Test
	public void shouldReturnTrue_whenMinimalOverlap() {
		// 1px overlap (239 < 240 but 240 > 239)
		List<AssessmentNode> nodes = List.of(
				element("a", 100, 100, 140, 70),
				element("b", 239, 100, 140, 70));
		assertTrue(OverlapResolver.hasOverlappingElements(nodes));
	}

	// --- Containment overlap exclusion (Bug 11.1: autoNudge false positive) ---

	@Test
	public void shouldReturnFalse_whenChildNestedInsideParent() {
		// Child element inside parent element — containment, not sibling overlap
		List<AssessmentNode> nodes = List.of(
				element("parent", 100, 100, 300, 200),
				elementWithParent("child", 120, 130, 120, 55, "parent"));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnFalse_whenMultipleChildrenInsideParent() {
		// Multiple children nested inside parent — all containment overlaps
		List<AssessmentNode> nodes = List.of(
				element("parent", 100, 100, 300, 300),
				elementWithParent("child1", 120, 130, 120, 55, "parent"),
				elementWithParent("child2", 120, 200, 120, 55, "parent"));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnTrue_whenSiblingsOverlapInsideParent() {
		// Two children overlap each other (siblings) — genuine overlap even though both have same parent
		List<AssessmentNode> nodes = List.of(
				element("parent", 100, 100, 300, 300),
				elementWithParent("child1", 120, 130, 120, 55, "parent"),
				elementWithParent("child2", 120, 140, 120, 55, "parent"));  // overlaps child1
		assertTrue(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnFalse_whenTransitiveContainment() {
		// Grandchild inside group inside grandparent — transitive containment
		List<AssessmentNode> nodes = List.of(
				group("grandparent", 50, 50, 500, 500),
				groupWithParent("parent-group", 70, 70, 400, 400, "grandparent"),
				elementWithParent("grandchild", 100, 100, 120, 55, "parent-group"));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnFalse_whenFlatViewWithEmbeddedFunctions() {
		// Reproduces the E2E bug: ApplicationComponent with nested ApplicationFunction
		// on a flat view — containment overlaps, not sibling overlaps
		List<AssessmentNode> nodes = List.of(
				element("app1", 100, 100, 160, 80),
				elementWithParent("func1", 110, 130, 100, 40, "app1"),
				element("app2", 400, 100, 160, 80),
				elementWithParent("func2", 410, 130, 100, 40, "app2"));
		assertFalse(OverlapResolver.hasOverlappingElements(nodes));
	}

	@Test
	public void shouldReturnTrue_whenSiblingOverlapAmidContainment() {
		// Genuine sibling overlap exists alongside containment overlaps
		List<AssessmentNode> nodes = List.of(
				element("app1", 100, 100, 160, 80),
				elementWithParent("func1", 110, 130, 100, 40, "app1"),
				element("app2", 200, 100, 160, 80),  // overlaps app1 (siblings, not containment)
				elementWithParent("func2", 210, 130, 100, 40, "app2"));
		assertTrue(OverlapResolver.hasOverlappingElements(nodes));
	}
}
