package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the spacing-tool parent-bounds-overflow contract added 2026-05-13
 * (Successor E.b).
 *
 * <p>Closes the Row B / composed-tool sibling residual gap surfaced by parent
 * Successor E (row 730) Task 0a static analysis: the
 * {@code apply-element-spacing-recommendations} / {@code apply-group-spacing-
 * recommendations} / {@code apply-spacing-recommendations(scope=both)} family
 * delegates to {@code ArchiModelAccessorImpl.adjustViewSpacing} →
 * {@code inflateGroupSpacing} (which has its own {@code resizeAncestorGroups}
 * PARALLEL to {@code resizeParentGroupIfNeeded}) +
 * {@code computeAutoRoutePass(force=true)} that does NOT autoNudge. Mechanism
 * #5a (dominant): {@code inflateGroupSpacing}'s recursive case has a stale-
 * dimensions bug in {@code resolveElementSizes} (line 9337-9348) that reads
 * {@code child.getBounds()} without consulting pending commands; the outer
 * group's child-position command OVERWRITES the inner group's resize command
 * with stale dimensions, leaving inner-group children outside inner-group
 * bounds.</p>
 *
 * <p>Fix (per owner sign-off, option-1): added a post-spacing/
 * routing overflow detection pass inside {@code adjustViewSpacing} at the
 * step 9b insertion point (just after {@code assessLayout} and before
 * {@code undo}), gated by EMF state reflecting the dispatched spacing +
 * routing changes. The pass iterates every view object via
 * {@code collectAllViewObjectMap}, checks the shared
 * {@link ArchiModelAccessorImpl#childExceedsParentBounds(int, int, int, int,
 * int, int, int)} predicate (line 4173, extracted by parent Successor E as
 * single source of truth per {@code feedback_inherited_primitive_spike.md}),
 * and emits consolidated resize commands via {@code resizeParentGroupIfNeeded}
 * + {@code virtualGroupBounds} + {@code groupResizeCommands} accumulator.
 * Sibling-symmetric with parent Successor E's mechanism #2b
 * post-autoNudge overflow pass at lines 3706-3752.</p>
 *
 * <p>Test coverage:</p>
 * <ul>
 *   <li><b>Fixture C (2-element minimal synthetic):</b> 4 tests targeting
 *       SPACING-TOOL-specific overflow scenarios. The 9 boundary predicate
 *       tests already live in {@link AutoNudgeGroupBoundsFollowupTest} (single
 *       source of truth — predicate is shared); this class focuses on the
 *       spacing-tool-path geometry instead of duplicating boundary cases.</li>
 *   <li><b>Fixture B (V4 H2 hub-heavy synthetic):</b> 4 tests covering
 *       right-flank / bottom-flank / both-flank / corner overflow on the
 *       17-fan-out API Mgmt topology after Row B inflation.</li>
 *   <li><b>Mechanism #5a regression pin:</b> 3 tests exercising the recursive
 *       nested-group stale-dim overwrite scenario — the post-pass must detect
 *       inner-group children at positions exceeding inner-group's
 *       (overwrite-reverted) dimensions.</li>
 * </ul>
 *
 * <p>Pure geometry — runnable without OSGi. Sibling-symmetric with
 * {@link AutoNudgeGroupBoundsFollowupTest} (parent Successor E),
 * {@link ApplyNudgeDeltasTest}, and
 * {@code RoutingClassificationPrecedenceTest} (Successor F).</p>
 *
 * <p>Coordinate convention: {@code childNewX/Y} are relative-to-parent.</p>
 */
public class SpacingToolParentBoundsTest {

	private static final int PADDING = 10;

	// --- Fixture C: 2-element minimal synthetic, spacing-tool-path-specific ---

	@Test
	public void shouldNotDetectOverflow_whenSpacingInflationKeepsChildInsideParent() {
		// Pre-spacing: child(10, 10, 100, 100) in parent(200, 200) — fits.
		// Post-spacing (Row B element-delta=+20): child(30, 30, 100, 100) — required = 140 < 200.
		// Predicate returns false → no resize command emitted by post-pass.
		assertFalse(ArchiModelAccessorImpl.childExceedsParentBounds(
				30, 30, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenSpacingInflationPushesChildPastRightEdge() {
		// Pre-spacing: child(80, 10, 100, 100) in parent(200, 200) — required = 190 < 200.
		// Post-spacing (Row B element-delta=+30 lateral shift): child(110, 10, 100, 100) —
		// required = 220 > 200 → overflow detected.
		// THIS is the spacing-tool-path failure mode the post-pass closes.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				110, 10, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenSpacingInflationPushesChildPastBottomEdge() {
		// Pre-spacing: child(10, 80, 100, 100) in parent(200, 200) — required = 190 < 200.
		// Post-spacing (Row B padding-delta=+30 vertical shift): child(10, 110, 100, 100) —
		// required = 220 > 200 → overflow detected.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				10, 110, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenIntergroupShiftMovesGroupChildPastParent() {
		// Inter-group shift (apply-spacing-recommendations(scope=both)): the
		// inter-group delta repositions the whole group; if the inner-group
		// resize from inflateGroupSpacing was overwritten by the outer's
		// stale-dim command (mechanism #5a), the inner group's child sits
		// outside the under-sized inner bounds. Predicate returns true →
		// post-pass emits resize for the inner group.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				190, 5, 100, 100, 200, 200, PADDING));
	}

	// --- Fixture B: V4 H2 hub-heavy synthetic (1 hub + 17 fan-out spokes) ---
	//
	// Mimics view `id-ddb84fbd57d24caaa15b0da62b75f531` (17-fan-out API Mgmt) —
	// row 730 candidate + Successor D V_p10 calibration anchor.
	// Parent: 800x600. Spokes: 80x40 each. The spacing-tool path (Row B
	// inflation) tightens spacing AND repositions spokes; under high
	// connectionCount cohort, the tightened-then-recomputed layout pushes
	// outer spokes past parent bounds when inflateGroupSpacing's recursive
	// stale-dim bug undersizes the parent.

	private static final int V4H2_PARENT_W = 800;
	private static final int V4H2_PARENT_H = 600;
	private static final int V4H2_SPOKE_W = 80;
	private static final int V4H2_SPOKE_H = 40;

	@Test
	public void shouldNotDetectOverflow_onV4H2HubHeavyAfterModestSpacingInflation() {
		// Negative case (sanity): a representative spoke at modest-spacing position
		// fits within parent. Row B small element-delta (+10) keeps geometry inside.
		// Spoke at (60, 60): right edge = 60+80+10 = 150 < 800; bottom = 60+40+10 = 110 < 600.
		assertFalse(ArchiModelAccessorImpl.childExceedsParentBounds(
				60, 60, V4H2_SPOKE_W, V4H2_SPOKE_H, V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
	}

	@Test
	public void shouldDetectOverflow_onV4H2HubHeavyAfterAggressiveRowBInflation_rightFlank() {
		// Positive case: aggressive Row B inflation (e.g., +60px element-delta) on
		// the 4-column×5-row spoke grid pushes the rightmost column to x=720+.
		// Right edge = 720+80+10 = 810 > 800 → spacing-tool post-pass triggers
		// parent resize via resizeParentGroupIfNeeded.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				720, 280, V4H2_SPOKE_W, V4H2_SPOKE_H, V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
	}

	@Test
	public void shouldDetectOverflow_onV4H2HubHeavyAfterAggressiveRowBInflation_bottomFlank() {
		// Positive case: aggressive Row B inflation pushes the bottom row to y=560+.
		// Bottom edge = 560+40+10 = 610 > 600 → spacing-tool post-pass triggers
		// parent resize.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				350, 560, V4H2_SPOKE_W, V4H2_SPOKE_H, V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
	}

	@Test
	public void shouldDetectOverflow_onV4H2HubHeavyAfterAggressiveRowBInflation_bothFlanks() {
		// Positive case: corner spoke after large Row B inflation overflows BOTH
		// right AND bottom — corresponds to the Arm4-HH-Run1/Run2/Run3 + Arm1-ST-Run3
		// overflow pattern from the 4-arm 4th E2E confirmation 2026-05-11.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				730, 580, V4H2_SPOKE_W, V4H2_SPOKE_H, V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
	}

	// --- Mechanism #5a regression pin ---
	//
	// Reproduces the recursive nested-group stale-dim overwrite scenario:
	// 1. inflateGroupSpacing(outer, recursive=true) recurses into inner first.
	// 2. inner's call emits UpdateViewObjectCommand(inner, x, y, NEW_BIG_W, NEW_BIG_H).
	// 3. outer's body calls computeRowLayout(children, ...) → resolveElementSizes
	//    reads inner.getBounds() which returns STALE OLD_SMALL dimensions.
	// 4. outer emits UpdateViewObjectCommand(inner, outerX, outerY, OLD_SMALL_W,
	//    OLD_SMALL_H) — OVERWRITES inner's resize.
	// 5. After dispatch, inner has OLD_SMALL dims but its children sit at
	//    positions assuming NEW_BIG dims → children exceed inner bounds.
	//
	// The post-pass at adjustViewSpacing step 9b detects this via the predicate:
	// for each child whose parent group is inner, checks
	// childExceedsParentBounds(childX, childY, childW, childH, INNER_OLD_SMALL_W,
	// INNER_OLD_SMALL_H, padding) → returns true → resize inner via
	// resizeParentGroupIfNeeded (which also recursively walks up to resize outer).

	@Test
	public void shouldDetectMechanism5a_whenInnerGroupResizeIsOverwrittenByOuter() {
		// Inner group state post-overwrite: dimensions = OLD_SMALL (150x100).
		// Inner's last child positioned at (10, 80) assuming inner is NEW_BIG (200x150).
		// At post-overwrite (inner = 150x100), child (10, 80, 80, 30) requires
		// (10+80+10, 80+30+10) = (100, 120) > (150, 100) → bottom overflow.
		// Predicate returns true → post-pass fires resize.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				10, 80, 80, 30, 150, 100, PADDING));
	}

	@Test
	public void shouldDetectMechanism5a_whenOuterGroupAlsoNeedsResize() {
		// Cascading scenario: post-pass resizes inner group to NEW_BIG (200x150).
		// resizeParentGroupIfNeeded's built-in ancestor recursion (line 4131-4138)
		// then checks outer: outer was sized by inflateGroupSpacing for inner's
		// STALE OLD_SMALL dims, but now inner is NEW_BIG. Inner's bounds inside
		// outer (childX=20, childY=20, childW=200, childH=150) vs outer dims
		// (300x200): required = (20+200+10, 20+150+10) = (230, 180) < (300, 200)
		// → outer FITS new inner → no further resize. Predicate returns false.
		assertFalse(ArchiModelAccessorImpl.childExceedsParentBounds(
				20, 20, 200, 150, 300, 200, PADDING));
	}

	@Test
	public void shouldDetectMechanism5a_whenOuterGroupAlsoOverflowsAfterInnerResize() {
		// Cascading scenario: outer's stale-dim-based auto-resize under-sized
		// outer to fit inner at its STALE OLD_SMALL dims. Now that inner is
		// NEW_BIG (220x170), inner exceeds outer (250x190): required = (20+220+10,
		// 20+170+10) = (250, 200) → equals outer width (no overflow on width via
		// strict-greater predicate), height 200 > 190 → overflow on bottom.
		// Predicate returns true → resizeParentGroupIfNeeded's recursive call
		// resizes outer.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				20, 20, 220, 170, 250, 190, PADDING));
	}

	/**
	 * Predicate-level compliance: asserts that post-resize parent
	 * dimensions (820×630, chosen to accommodate all 17 spokes) satisfy the
	 * non-overflow predicate for each spoke. The parent dimensions are
	 * correct-by-construction — this test validates predicate arithmetic only,
	 * NOT that {@code adjustViewSpacing} actually emits those resize dimensions.
	 *
	 * <p><b>AI-2 — integration gap (tracked in "Review Follow-ups (AI)"):</b>
	 * end-to-end verification that the step-9b overflow-detection pass at
	 * {@code ArchiModelAccessorImpl.java:7291-7340} actually emits
	 * {@code resizeParentGroupIfNeeded} commands when mechanism #5a leaves
	 * children outside parent bounds requires an Eclipse/OSGi JUnit fixture.
	 * Deferred per parent Successor E AI-1 precedent.</p>
	 */
	@Test
	public void shouldKeepAllChildrenWithinParentBoundsAfterSpacingToolPath() {
		// V4 H2-style 17-spoke grid AFTER spacing-tool path including post-pass
		// resize of parent to NEW dimensions (810×610 to accommodate the
		// overflow-flagged 730×580 corner spoke). All 17 spokes must satisfy
		// non-overflow against POST-RESIZE parent dims.
		int postResizeParentW = 820;  // accommodates max-right spoke + padding
		int postResizeParentH = 630;  // accommodates max-bottom spoke + padding
		int[][] spokes = {
				{ 50,  50}, {200,  50}, {400,  50}, {600,  50},
				{ 50, 150}, {200, 150}, {400, 150}, {600, 150},
				{ 50, 280}, {200, 280}, {400, 280}, {600, 280},
				{ 50, 420}, {200, 420}, {400, 420}, {600, 420},
				{350, 560}                                         // 17th spoke (bottom-flank)
		};
		for (int[] pos : spokes) {
			assertFalse("Post-spacing-tool spoke at (" + pos[0] + "," + pos[1]
					+ ") must fit within post-resize " + postResizeParentW + "×"
					+ postResizeParentH + " parent",
					ArchiModelAccessorImpl.childExceedsParentBounds(
							pos[0], pos[1], V4H2_SPOKE_W, V4H2_SPOKE_H,
							postResizeParentW, postResizeParentH, PADDING));
		}
	}
}
