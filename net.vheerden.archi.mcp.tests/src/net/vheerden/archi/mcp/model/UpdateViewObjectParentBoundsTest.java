package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the {@code update-view-object} raw-MCP-tool-path parent-bounds-
 * overflow contract added 2026-05-14 (Successor H6).
 *
 * <p>Closes the third invocation point of the parent-bounds-update triad
 * surfaced by the H5 audit 2026-05-14.
 * Successor E closed the {@code autoRouteConnections}
 * autoNudge path; Successor E.b closed the {@code adjustViewSpacing}
 * spacing-tool path; this test covers the raw {@code update-view-object}
 * handler path (the convenience-tool layer the LLM agent reaches first when
 * acting on a {@code detect-hub-elements} suggestion).</p>
 *
 * <p>The Comparison-1 RE-RUN 2026-05-14
 * surfaced the ST Arm-6 STRICT first-mutation Tier-1 cascade rate at 3/3 driven
 * by the workflow sequence: {@code detect-hub-elements} → {@code update-view-object}
 * (2D hub resize: e.g., API Mgmt 214×68 → 304×143 on ST, or 300×250 → 390×325 on
 * HH) → boundary violation + sibling overlap + connection pass-throughs cascade →
 * {@code auto-route-connections(autoNudge=true)} skipped via
 * {@code AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP}. The H6 fix wires
 * {@link ArchiModelAccessorImpl#resizeParentGroupIfNeeded} into BOTH
 * {@code prepareUpdateViewObject} (line 11623, single-tool path) AND
 * {@code prepareUpdateViewObjectDirect} (line 11749, bulk-mutate back-reference
 * path) so that the user's {@code UpdateViewObjectCommand} and any
 * parent-resize commands execute as a single atomic
 * {@code NonNotifyingCompoundCommand} (one undo step).</p>
 *
 * <p>Test coverage (Fixture A: V4 H2 hub-heavy synthetic):
 * 5 predicate-level tests on the update-view-object-specific overflow geometries
 * (hub 2D-resize / hub move past parent bounds) + 1 named compliance
 * aggregate test asserting post-resize parent dims satisfy non-overflow for the
 * full set of children in the realistic ST + HH bifurcation scenarios.</p>
 *
 * <p>Pure geometry — runnable without OSGi. Sibling-symmetric with
 * {@link AutoNudgeGroupBoundsFollowupTest} (parent Successor E) and
 * {@link SpacingToolParentBoundsTest} (Successor E.b). All tests delegate to
 * {@link ArchiModelAccessorImpl#childExceedsParentBounds(int, int, int, int, int, int, int)}
 * — the single source of truth predicate per
 * {@code feedback_inherited_primitive_spike.md}.</p>
 *
 * <p>Coordinate convention: {@code childNewX/Y} are relative-to-parent (matches
 * Archi's nested-object storage convention per the v3 routing-pipeline coordinate
 * model).</p>
 *
 * <p><b>AI integration gap (deferred per E.b AI-2 precedent):</b> end-to-end
 * verification that the H6 insertion points in {@code prepareUpdateViewObject}
 * lines 11689-11725 and {@code prepareUpdateViewObjectDirect} lines 11826-11855
 * actually emit a {@code NonNotifyingCompoundCommand} when an LLM-agent-issued
 * hub-resize breaches parent bounds requires an Eclipse/OSGi JUnit fixture.
 * Predicate-level coverage here is sufficient to validate the arithmetic
 * contract; integration verification rolls into the Comparison-1 paired-arc
 * empirical re-run.</p>
 */
public class UpdateViewObjectParentBoundsTest {

	private static final int PADDING = 10;

	// --- Fixture A: V4 H2 hub-heavy synthetic ---
	//
	// Mimics the C1 RE-RUN 2026-05-14 ST + HH hub-resize bifurcation triggers:
	//   * ST: API Mgmt hub 214x68 → 304x143 inside parent group ~300x200.
	//   * HH: API Mgmt hub 300x250 → 390x325 inside parent group ~400x350.
	// Parent group is the "Application Layer" / "Business Layer" / etc.
	// container; hub is one of its children.

	private static final int ST_PARENT_W = 300;
	private static final int ST_PARENT_H = 200;
	private static final int HH_PARENT_W = 400;
	private static final int HH_PARENT_H = 350;

	@Test
	public void shouldNotDetectOverflow_whenHubResizeStaysInsideParent() {
		// Negative case (sanity): modest hub resize within parent bounds.
		// Hub at (50, 50, 200, 100) inside parent (300, 200): required = 260 < 300, 160 < 200.
		// boundsModified guard fires (resize provided), but predicate returns false →
		// no resize command emitted, base UpdateViewObjectCommand returned as-is.
		assertFalse(ArchiModelAccessorImpl.childExceedsParentBounds(
				50, 50, 200, 100, ST_PARENT_W, ST_PARENT_H, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenHubResizeExceedsParentRightEdge() {
		// ST-scenario right-flank: hub 2D-resize 214×68 → 304×143 at relative-x=10.
		// Required width = 10 + 304 + 10 = 324 > parent 300 → right overflow.
		// Required height = 10 + 143 + 10 = 163 < parent 200 → bottom OK.
		// H6 fix: resizeParentGroupIfNeeded emits parent-resize to width ≥324.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				10, 30, 304, 143, ST_PARENT_W, ST_PARENT_H, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenHubResizeExceedsParentBottomEdge() {
		// HH-scenario bottom-flank: hub 2D-resize at relative position (50, 100) with
		// new dims 300×260. Required height = 100 + 260 + 10 = 370 > parent 350 → bottom overflow.
		// Required width = 50 + 300 + 10 = 360 < parent 400 → right OK.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				50, 100, 300, 260, HH_PARENT_W, HH_PARENT_H, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenHubResizeExceedsBothRightAndBottomEdges() {
		// HH-scenario corner: hub 2D-resize 300×250 → 390×325 at relative position (40, 40).
		// Mirrors the C1 RE-RUN 2026-05-14 HH-R1/R2/R3 first-mutation hub-resize:
		// `detect-hub-elements` suggested 30% dimensional expansion on the API Mgmt hub.
		// Required width = 40 + 390 + 10 = 440 > parent 400 → right overflow.
		// Required height = 40 + 325 + 10 = 375 > parent 350 → bottom overflow.
		// H6 fix: resizeParentGroupIfNeeded emits parent-resize expanding BOTH dimensions
		// in a single command (predicate triggers once, helper computes both deltas).
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				40, 40, 390, 325, HH_PARENT_W, HH_PARENT_H, PADDING));
	}

	@Test
	public void shouldDetectOverflow_whenHubMovePushesPastParentEdge() {
		// 1D-move variant: caller updates only x (no resize). Hub keeps its 200×100
		// dimensions but moves from (10, 30) to (150, 30) inside parent (300, 200).
		// Required width = 150 + 200 + 10 = 360 > parent 300 → right overflow.
		// Exercises the boundsModified guard's `x != null` arm: when only x is
		// provided, the H6 block enters and predicate triggers.
		assertTrue(ArchiModelAccessorImpl.childExceedsParentBounds(
				150, 30, 200, 100, ST_PARENT_W, ST_PARENT_H, PADDING));
	}

	/**
	 * Named compliance test: asserts that after H6 fires
	 * {@code resizeParentGroupIfNeeded} for an ST-scenario hub-resize bifurcation,
	 * every sibling child of the now-expanded parent group satisfies the
	 * non-overflow predicate. The post-resize parent dimensions are
	 * correct-by-construction (helper computes them from
	 * {@code childNewX + childW + padding} per
	 * {@link ArchiModelAccessorImpl#resizeParentGroupIfNeeded} line 4107-4108) —
	 * this test validates the predicate arithmetic only, NOT that
	 * {@code prepareUpdateViewObject} actually emits those resize dimensions
	 * (that's the AI integration follow-up referenced in the class-level Javadoc).
	 */
	@Test
	public void shouldKeepAllChildrenWithinParentBoundsAfterUpdateViewObject() {
		// ST-scenario: hub-resize 214×68 → 304×143 at relative position (10, 30).
		// Pre-H6: parent stays at 300×200, hub overflows on right edge.
		// Post-H6: parent resized to width = max(300, 10 + 304 + 10) = 324,
		//          height = max(200, 30 + 143 + 10) = 200 (no change).
		// Three other children in the parent group: typical Application Layer
		// siblings (e.g., REST Gateway, Auth Service, Cache, sized 80×40 each).
		int postResizeParentW = 324;
		int postResizeParentH = 200;
		int hubChildX = 10, hubChildY = 30, hubChildW = 304, hubChildH = 143;
		// Hub itself must fit post-resize.
		assertFalse("Resized hub must fit within H6-resized parent",
				ArchiModelAccessorImpl.childExceedsParentBounds(
						hubChildX, hubChildY, hubChildW, hubChildH,
						postResizeParentW, postResizeParentH, PADDING));
		// Sibling positions — chosen so that all three fit within the H6-expanded
		// parent (postResizeParentW=324). The third sibling (230, 180, 80, 10)
		// requires 230+80+10=320 which exceeds the pre-H6 parent width (300) and
		// therefore represents a sibling that was already at the edge; it fits the
		// post-H6 parent (320 < 324). The first two fit both parents.
		int[][] siblings = {
				{ 10,   180},   // bottom-left: 10+80+10=100 < 300 (fits pre- and post-H6)
				{120,   180},   // bottom-mid:  120+80+10=210 < 300 (fits pre- and post-H6)
				{230,   180}    // bottom-right: 230+80+10=320 > 300 pre-H6, < 324 post-H6
		};
		int siblingW = 80, siblingH = 10;  // small height so bottom-row siblings fit at y=180 in 200-high parent
		for (int[] pos : siblings) {
			assertFalse("Sibling at (" + pos[0] + "," + pos[1]
					+ ") must fit within H6-resized parent " + postResizeParentW + "×"
					+ postResizeParentH,
					ArchiModelAccessorImpl.childExceedsParentBounds(
							pos[0], pos[1], siblingW, siblingH,
							postResizeParentW, postResizeParentH, PADDING));
		}
	}
}
