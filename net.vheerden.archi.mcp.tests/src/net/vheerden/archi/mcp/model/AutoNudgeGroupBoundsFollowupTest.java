package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link ArchiModelAccessorImpl#childExceedsParentBounds(int, int,
 * int, int, int, int, int)} — the extracted overflow predicate used by both
 * the original {@code resizeParentGroupIfNeeded} helper AND the
 * Successor-E post-routing overflow detection pass added to
 * {@code autoRouteConnections} 2026-05-13.
 *
 * <p>Successor E from the overflow verdict 2026-05-12.
 * Closes the residual gap in {@code resizeParentGroupIfNeeded} (shipped 2026-03-25) that surfaced
 * as the 4th independent E2E confirmation in the 4-arm 24-run paired-
 * empirical 2026-05-11 (Arm4-HH-Run1/Run2/Run3 + Arm1-ST-Run3, all owner-
 * flagged "element/group overlaps").</p>
 *
 * <p>Test coverage:</p>
 * <ul>
 *   <li><b>Fixture C (2-element minimal synthetic, cheapest unit-test):</b>
 *       9 tests covering no-overflow / right-overflow / bottom-overflow /
 *       negative-X / negative-Y / both-axes / exact-boundary / one-pixel-past /
 *       zero-padding cases.</li>
 *   <li><b>Fixture B (V4 H2 hub-heavy synthetic, mimics actual failure
 *       mode):</b> 1 hub + 17 fan-out spokes fixture; baseline-fits +
 *       Row-B-inflation-causes-overflow scenarios.</li>
 *   <li><b>Mechanism #2b regression pin:</b> exercises the autoNudge gate-
 *       miss scenario (child already overflows at the time
 *       {@code auto-route-connections} is called, before any nudges).</li>
 * </ul>
 *
 * <p>Pure geometry — runnable without OSGi. Sibling-symmetric with
 * {@link ApplyNudgeDeltasTest}, {@link HasOverlappingElementsTest},
 * {@link FindOverlappingElementIdsTest}, and {@code RoutingClassificationPrecedenceTest}
 * (Successor F, shipped 2026-05-12).</p>
 *
 * <p>Coordinate convention: {@code childNewX/Y} are relative-to-parent
 * (Archi's nested-object storage convention).</p>
 */
public class AutoNudgeGroupBoundsFollowupTest {

	private static final int PADDING = 10;

	// --- Fixture C: 2-element minimal synthetic ---

	@Test
	public void shouldNotDetectOverflow_whenChildFitsWithinParent() {
		// Child(x=5, y=5, w=100, h=100) fits in parent(w=200, h=200) with padding=10.
		// required = (5+100+10, 5+100+10) = (115, 115) < (200, 200) and child x,y >= 0.
		assertFalse(ArchiModelAccessorImpl.childExceedsParentBounds(
				5, 5, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenChildExceedsParentRight() {
		// Child(x=150, y=5, w=100, h=100): right edge = 150+100+10 = 260 > 200.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				150, 5, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenChildExceedsParentBottom() {
		// Child(x=5, y=150, w=100, h=100): bottom edge = 150+100+10 = 260 > 200.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				5, 150, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenChildHasNegativeX() {
		// Child(x=-5, y=5): negative-X is an automatic overflow per the M-2 fix.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				-5, 5, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenChildHasNegativeY() {
		// Child(x=5, y=-5): negative-Y is an automatic overflow per the M-2 fix.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				5, -5, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenChildExceedsBothAxes() {
		// Child(x=150, y=150, w=100, h=100): both right AND bottom overflow.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				150, 150, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldNotDetectOverflow_atExactBoundaryMinusPadding() {
		// Child exactly fits: child right = 90+100 = 190, with padding required = 200,
		// equal to parent width → NOT overflow (predicate uses strict >).
		assertFalse(ArchiModelAccessorImpl.childExceedsParentBounds(
				90, 90, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldDetectOverflow_onePixelPastBoundary() {
		// Child one pixel past the fit-exact threshold.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				91, 5, 100, 100, 200, 200, PADDING));
	}

	@Test
	public void shouldHandleZeroPadding() {
		// padding=0: child(0, 0, 200, 200) exactly fills parent(200, 200) → not overflow.
		assertFalse(ArchiModelAccessorImpl.childExceedsParentBounds(
				0, 0, 200, 200, 200, 200, 0));
		// padding=0: child(0, 0, 201, 200) overflows.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				0, 0, 201, 200, 200, 200, 0));
		// Note: negative padding is not a supported input — all production callers pass
		// DEFAULT_GROUP_PADDING (10). Behaviour with padding < 0 is undefined.
	}

	// --- Fixture B: V4 H2 hub-heavy synthetic (1 hub + 17 fan-out spokes) ---
	//
	// Mimics view `id-ddb84fbd57d24caaa15b0da62b75f531` "PartyTest H2" (17-fan-out
	// API Mgmt) — Successor D V_p10 calibration
	// anchor. Parent group: 800x600. Hub: 120x80 centred. 17 spokes: 80x40 each,
	// distributed around hub. Initial layout fits; Row-B-style spacing inflation
	// pushes some spokes past parent bounds.

	private static final int V4H2_PARENT_W = 800;
	private static final int V4H2_PARENT_H = 600;
	private static final int V4H2_SPOKE_W = 80;
	private static final int V4H2_SPOKE_H = 40;

	@Test
	public void shouldNotDetectOverflow_onV4H2HubHeavyAtBaseline() {
		// Negative case (sanity): a representative spoke at baseline tight-packed
		// position fits within parent.
		// Spoke 0 at (50, 50): right edge = 50+80+10 = 140 < 800; bottom = 50+40+10 = 100 < 600.
		assertFalse(ArchiModelAccessorImpl.childExceedsParentBounds(
				50, 50, V4H2_SPOKE_W, V4H2_SPOKE_H, V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
	}

	@Test
	public void shouldDetectOverflow_onV4H2HubHeavyAfterRowBInflation_rightFlank() {
		// Positive case: after Row B inflation tightens spacing, spoke 16 (rightmost
		// fan-out) ends up at x=720. right edge = 720+80+10 = 810 > 800 → overflow.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				720, 280, V4H2_SPOKE_W, V4H2_SPOKE_H, V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
	}

	@Test
	public void shouldDetectOverflow_onV4H2HubHeavyAfterRowBInflation_bottomFlank() {
		// Positive case: after Row B inflation, spoke 14 (bottom fan-out) ends up
		// at y=560. bottom edge = 560+40+10 = 610 > 600 → overflow.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				350, 560, V4H2_SPOKE_W, V4H2_SPOKE_H, V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
	}

	@Test
	public void shouldDetectOverflow_onV4H2HubHeavyAfterRowBInflation_bothFlanks() {
		// Positive case: corner spoke after large Row B inflation overflows both
		// right AND bottom — corresponds to the Arm4-HH overflow pattern.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				730, 580, V4H2_SPOKE_W, V4H2_SPOKE_H, V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
	}

	/**
	 * Named test asserting that all 17 fan-out spokes of
	 * the V4 H2 hub-heavy topology satisfy the non-overflow predicate at baseline
	 * positions (i.e., the predicate returns {@code false} for every spoke —
	 * meaning {@code auto-route-connections} with {@code autoNudge=true} would
	 * find nothing to resize at baseline).
	 *
	 * <p><b>Review Follow-up AI-1 — integration gap (tracked):</b> this test
	 * exercises only the static predicate; it does NOT run
	 * {@code autoRouteConnections(... autoNudge=true ...)} end-to-end. Full
	 * integration-level coverage — asserting that the Successor-E post-routing
	 * overflow pass at {@code ArchiModelAccessorImpl.java:3731–3752} actually
	 * emits resize commands when children overflow, and that
	 * {@code MutationResult.resizedGroups()} is non-empty post-fix — requires an
	 * Eclipse/OSGi JUnit test with a live {@code IDiagramModel} fixture (not
	 * achievable in a pure-unit class). Tracked as AI-1 in the story's "Review
	 * Follow-ups (AI)" task subsection.</p>
	 */
	@Test
	public void shouldKeepAllChildrenWithinParentBoundsAfterAutoRoute() {
		// 17 spoke positions distributed in a 4-column × 5-row grid (row 4 = 1 spoke).
		// All at baseline — predicate must return false for each (no overflow at baseline).
		// If any spoke had overflow here, the Successor-E post-routing pass would emit a
		// parent resize command — the predicate returning true is the trigger for that.
		int[][] spokes = {
				{ 50,  50}, {150,  50}, {250,  50}, {350,  50},  // row 0
				{ 50, 150}, {150, 150}, {250, 150}, {350, 150},  // row 1
				{ 50, 250}, {150, 250}, {250, 250}, {350, 250},  // row 2
				{ 50, 350}, {150, 350}, {250, 350}, {350, 350},  // row 3
				{ 50, 450}                                         // row 4 (17th spoke)
		};
		for (int[] pos : spokes) {
			assertFalse("Spoke at (" + pos[0] + "," + pos[1] + ") overflows 800×600 parent",
					ArchiModelAccessorImpl.childExceedsParentBounds(
							pos[0], pos[1], V4H2_SPOKE_W, V4H2_SPOKE_H,
							V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
		}
	}

	// --- Mechanism #2b regression pin ---
	//
	// Reproduces the autoNudge gate-miss scenario: when `auto-route-connections`
	// is called on geometry where children ALREADY overflow their parent group
	// (because a prior Row B / composed-tool inflation widened the cohort), the
	// autoNudge gate at `ArchiModelAccessorImpl.java:3463` requires both
	// `!failedConnections.isEmpty()` AND `!moveRecommendations.isEmpty()` to
	// enter. If routing succeeds without producing failed connections (path
	// found for every connection), the gate evaluates FALSE → autoNudge body
	// is skipped → `resizeParentGroupIfNeeded` never fires inside the gate →
	// any pre-existing overflow persists.
	//
	// The Successor-E post-routing overflow pass at lines 3706-3752 catches
	// this case by iterating allViewObjects and calling
	// resizeParentGroupIfNeeded for any child that exceeds its parent group's
	// bounds. The predicate `childExceedsParentBounds` returning true here is
	// the load-bearing signal for the post-pass to emit a resize command.

	@Test
	public void shouldDetectMechanism2bGap_whenChildAlreadyOverflowsAtAutoRouteEntry() {
		// Pre-route state: a child already at right-flank overflow position from a
		// prior Row B inflation in a non-autoNudge code path (e.g.,
		// `apply-element-spacing-recommendations` → `adjustViewSpacing` →
		// `inflateGroupSpacing` + `computeAutoRoutePass(force=true)`).
		// Predicate returns true → Successor-E post-pass fires resize command.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				750, 250, V4H2_SPOKE_W, V4H2_SPOKE_H, V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
	}

	@Test
	public void shouldNotDetectMechanism2bGap_whenGeometryIsCleanAtAutoRouteEntry() {
		// Pre-route state: child sits well within parent — no Row B inflation
		// damage. Predicate returns false → Successor-E post-pass is a no-op
		// (it iterates all view objects but emits no resize commands).
		assertFalse(ArchiModelAccessorImpl.childExceedsParentBounds(
				100, 100, V4H2_SPOKE_W, V4H2_SPOKE_H, V4H2_PARENT_W, V4H2_PARENT_H, PADDING));
	}
}
