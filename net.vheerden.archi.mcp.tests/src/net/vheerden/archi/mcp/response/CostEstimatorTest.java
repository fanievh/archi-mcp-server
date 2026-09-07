package net.vheerden.archi.mcp.response;

import static org.junit.Assert.*;

import org.junit.Test;

import net.vheerden.archi.mcp.response.CostEstimator.ItemType;
import net.vheerden.archi.mcp.response.FieldSelector.FieldPreset;

/**
 * Tests for {@link CostEstimator} — token estimation and recommendation logic.
 */
public class CostEstimatorTest {

	// ---- estimateTokens: Element types ----

	@Test
	public void shouldEstimateTokens_forElementMinimal() {
		int tokens = CostEstimator.estimateTokens(10, FieldPreset.MINIMAL, ItemType.ELEMENT);
		// 10 * 80 + 280 = 1080 chars / 4 = 270 tokens (rounded up).
		// Moved from 200: the per-element minimal width was measured for the first time (81 chars
		// for a 35-char id plus a 26-char name) and the old 60 was 26% low.
		assertEquals(270, tokens);
	}

	@Test
	public void shouldEstimateTokens_forElementStandard() {
		int tokens = CostEstimator.estimateTokens(10, FieldPreset.STANDARD, ItemType.ELEMENT);
		// 10 * 250 + 280 = 2780 chars / 4 = 695 tokens (rounded up)
		assertEquals(695, tokens);
	}

	@Test
	public void shouldEstimateTokens_forElementFull() {
		int tokens = CostEstimator.estimateTokens(10, FieldPreset.FULL, ItemType.ELEMENT);
		// 10 * 250 + 280 = 2780 chars / 4 = 695 tokens (rounded up)
		assertEquals(695, tokens);
	}

	// ---- estimateTokens: View type ----

	@Test
	public void shouldEstimateTokens_forViewMinimal() {
		int tokens = CostEstimator.estimateTokens(20, FieldPreset.MINIMAL, ItemType.VIEW);
		// 20 * 90 + 280 = 2080 chars / 4 = 520 tokens (rounded up).
		// Moved from 350: the per-view minimal width was re-measured against the serializer
		// (91 chars for a 35-char id plus a 37-char name) and the old 60 was 34% low.
		assertEquals(520, tokens);
	}

	@Test
	public void shouldEstimateTokens_forViewStandard() {
		int tokens = CostEstimator.estimateTokens(20, FieldPreset.STANDARD, ItemType.VIEW);
		// 20 * 175 + 280 = 3780 chars / 4 = 945 tokens (rounded up).
		// Moved from 650: measured 181 chars for a view carrying a viewpoint, against the
		// old estimate of 120.
		assertEquals(945, tokens);
	}

	@Test
	public void shouldEstimateTokens_forViewFull() {
		int tokens = CostEstimator.estimateTokens(20, FieldPreset.FULL, ItemType.VIEW);
		// 20 * 385 + 280 = 7980 chars / 4 = 1995 tokens (rounded up).
		// New pin: the full preset adds documentation and properties to a view, which the
		// estimate previously collapsed onto the standard width.
		assertEquals(1995, tokens);
	}

	@Test
	public void shouldEstimateTokens_forView_risesWithPreset() {
		int minimal = CostEstimator.estimateTokens(20, FieldPreset.MINIMAL, ItemType.VIEW);
		int standard = CostEstimator.estimateTokens(20, FieldPreset.STANDARD, ItemType.VIEW);
		int full = CostEstimator.estimateTokens(20, FieldPreset.FULL, ItemType.VIEW);
		assertTrue("view minimal must cost less than standard", minimal < standard);
		assertTrue("view standard must cost less than full", standard < full);
	}

	// ---- estimateTokens: Relationship type ----

	@Test
	public void shouldEstimateTokens_forRelationshipMinimal() {
		int tokens = CostEstimator.estimateTokens(50, FieldPreset.MINIMAL, ItemType.RELATIONSHIP);
		// 50 * 55 + 280 = 3030 chars / 4 = 758 tokens (rounded up)
		assertEquals(758, tokens);
	}

	@Test
	public void shouldEstimateTokens_forRelationship() {
		int tokens = CostEstimator.estimateTokens(50, FieldPreset.STANDARD, ItemType.RELATIONSHIP);
		// 50 * 185 + 280 = 9530 chars / 4 = 2383 tokens (rounded up).
		// Moved from 1300: the old flat 100 chars per relationship was measured at 183 for a
		// standard row (two 35-char endpoint ids dominate), an under-report of ~1.8x on the
		// default call.
		assertEquals(2383, tokens);
	}

	@Test
	public void shouldEstimateTokens_forRelationshipFull() {
		int tokens = CostEstimator.estimateTokens(50, FieldPreset.FULL, ItemType.RELATIONSHIP);
		// 50 * 365 + 280 = 18530 chars / 4 = 4633 tokens (rounded up)
		assertEquals(4633, tokens);
	}

	@Test
	public void shouldEstimateTokens_forRelationship_risesWithPreset() {
		int minimal = CostEstimator.estimateTokens(50, FieldPreset.MINIMAL, ItemType.RELATIONSHIP);
		int standard = CostEstimator.estimateTokens(50, FieldPreset.STANDARD, ItemType.RELATIONSHIP);
		int full = CostEstimator.estimateTokens(50, FieldPreset.FULL, ItemType.RELATIONSHIP);
		assertTrue("relationship minimal must cost less than standard", minimal < standard);
		assertTrue("relationship standard must cost less than full", standard < full);
	}

	// ---- estimateTokens: Edge cases ----

	@Test
	public void shouldEstimateTokens_whenZeroItems() {
		int tokens = CostEstimator.estimateTokens(0, FieldPreset.STANDARD, ItemType.ELEMENT);
		// 0 items → envelope overhead only: 280 / 4 = 70 tokens (rounded up)
		assertEquals(70, tokens);
	}

	@Test
	public void shouldEstimateTokens_whenNegativeItems() {
		int tokens = CostEstimator.estimateTokens(-5, FieldPreset.STANDARD, ItemType.ELEMENT);
		// Treated same as 0: the envelope alone, 280 / 4 = 70 tokens (rounded up)
		assertEquals(70, tokens);
	}

	@Test
	public void shouldEstimateTokens_forLargeCount() {
		int tokens = CostEstimator.estimateTokens(1000, FieldPreset.STANDARD, ItemType.ELEMENT);
		// 1000 * 250 + 280 = 250280 chars / 4 = 62570 tokens (rounded up)
		assertEquals(62570, tokens);
	}

	// ---- estimateTokensForViewContents: View contents ----

	/**
	 * The concept halves alone, with every visual array at zero. Most pins below are about how the
	 * element and relationship terms behave, and stating five zeros at each of them would bury the
	 * quantity under test. The composite -- what the estimate does once the visual arrays are
	 * non-empty -- is pinned by its own tests below and measured against the serializer in
	 * {@code CostEstimatorPayloadWidthContractTest}.
	 */
	private static int conceptsOnly(int elementCount, int relationshipCount, FieldPreset preset) {
		return CostEstimator.estimateTokensForViewContents(
				new CostEstimator.ViewContentsCounts(elementCount, relationshipCount, 0, 0, 0, 0, 0),
				preset);
	}

	@Test
	public void shouldEstimateTokensForViewContents_conceptHalvesOnly() {
		int tokens = conceptsOnly(15, 10, FieldPreset.STANDARD);
		// 15 * 250 + 10 * 185 + 280 = 3750 + 1850 + 280 = 5880 chars / 4 = 1470 tokens (rounded up).
		// Unmoved by the seven-array change: with every visual count at zero the five new terms
		// contribute nothing, which is the property this pin now also asserts.
		assertEquals(1470, tokens);
	}

	@Test
	public void shouldChargeEveryVisualArray_forViewContents() {
		// One row in each of the five arrays the estimate used to ignore, on top of the concept
		// halves above. 5880 + 205 + 640 + 435 + 430 + 345 = 7935 chars / 4 = 1984 tokens.
		int tokens = CostEstimator.estimateTokensForViewContents(
				new CostEstimator.ViewContentsCounts(15, 10, 1, 1, 1, 1, 1), FieldPreset.STANDARD);
		assertEquals(1984, tokens);
	}

	@Test
	public void shouldNotMoveTheVisualArraysWithThePreset_forViewContents() {
		// The field selector emits these five raw, with no preset argument, so their contribution
		// is identical at every preset. Asserted as a DIFFERENCE against the same counts with the
		// visual arrays zeroed: comparing whole estimates would be satisfied by the concept halves
		// not moving either, which is false and is not what this pin is about.
		//
		// 20 * 205 + 20 * 640 + 3 * 435 + 2 * 430 + 1 * 345
		//   = 4100 + 12800 + 1305 + 860 + 345 = 19410 chars = 4852 tokens.
		//
		// The difference is compared against that derived figure rather than against whichever
		// preset ran first, and it is allowed one token either way. Both estimates are rounded UP
		// to a whole token before the subtraction, so their difference carries up to a token of
		// rounding that has nothing to do with the widths: the same 19410 chars really do come out
		// as 4852 at standard and 4853 at minimal. Pinning the first observed value instead would
		// have pinned that artefact and called it the property.
		int expected = ((20 * CostEstimator.VISUAL_NODE_CHARS)
				+ (20 * CostEstimator.VIEW_CONNECTION_CHARS)
				+ (3 * CostEstimator.VIEW_GROUP_CHARS)
				+ (2 * CostEstimator.VIEW_NOTE_CHARS)
				+ CostEstimator.DIAGRAM_IMAGE_CHARS) / CostEstimator.CHARS_PER_TOKEN;
		assertEquals("the derived figure this pin is anchored on", 4852, expected);
		for (FieldPreset preset : FieldPreset.values()) {
			int withVisuals = CostEstimator.estimateTokensForViewContents(
					new CostEstimator.ViewContentsCounts(15, 10, 20, 20, 3, 2, 1), preset);
			int withoutVisuals = CostEstimator.estimateTokensForViewContents(
					new CostEstimator.ViewContentsCounts(15, 10, 0, 0, 0, 0, 0), preset);
			int delta = withVisuals - withoutVisuals;
			assertTrue("the visual arrays carry no preset-dependent field, so their contribution "
					+ "must be the measured widths at " + preset + " -- expected " + expected
					+ " tokens give or take the rounding, got " + delta,
					Math.abs(delta - expected) <= 1);
		}
	}

	@Test
	public void shouldSwampThePresetSaving_whenTheVisualArraysDominate() {
		// Why the estimate had to change rather than merely being scaled up. On a view whose
		// visual arrays are non-empty, narrowing the preset reaches two of seven terms, so the
		// saving a caller is offered is a small fraction of the payload -- which is the lie the
		// old two-term estimate told, only in the opposite direction and much larger.
		CostEstimator.ViewContentsCounts counts =
				new CostEstimator.ViewContentsCounts(30, 25, 30, 25, 4, 1, 0);
		int atStandard = CostEstimator.estimateTokensForViewContents(counts, FieldPreset.STANDARD);
		int atMinimal = CostEstimator.estimateTokensForViewContents(counts, FieldPreset.MINIMAL);
		assertTrue("narrowing the preset must still reduce the estimate", atMinimal < atStandard);
		double saving = 1.0 - (atMinimal / (double) atStandard);
		assertTrue("the saving on a view carrying visual rows must be well under half, because "
				+ "five of the seven arrays do not move with the preset (was " + saving + ")",
				saving < 0.35);
	}

	@Test
	public void shouldEstimateTokensForViewContents_withMinimalPreset() {
		int tokens = conceptsOnly(15, 10, FieldPreset.MINIMAL);
		// 15 * 80 + 10 * 55 + 280 = 1200 + 550 + 280 = 2030 chars / 4 = 508 tokens (rounded up).
		// Moved UP from 413 with the element minimal width; the relationship half is unchanged.
		// Moved DOWN from 525 because the old number was itself the defect: it charged 10
		// relationships the full 100-char width while the caller had asked for the minimal
		// preset, which emits id and name only. The relationship half now follows the preset.
		assertEquals(508, tokens);
	}

	@Test
	public void shouldPriceBothConceptHalvesAsRealDtoRows_forViewContents() {
		// Re-anchored for the caller split. This method used to serve two callers and was correct
		// for only one of them; it now serves get-view-contents alone, which really does return
		// element and relationship rows through field selection. Both halves therefore price real
		// DTO rows and both follow the preset.
		int relMinimal = conceptsOnly(0, 10, FieldPreset.MINIMAL);
		int relFull = conceptsOnly(0, 10, FieldPreset.FULL);
		assertTrue("the relationship half must follow the preset", relMinimal < relFull);
		for (FieldPreset p : FieldPreset.values()) {
			assertEquals("the element half must equal the element estimate at " + p,
					CostEstimator.estimateTokens(15, p, ItemType.ELEMENT),
					conceptsOnly(15, 0, p));
		}
	}

	@Test
	public void shouldPriceTraverseDifferentlyFromViewContents_inBothDirections() {
		// The reason the two callers cannot share one estimate, and it cuts BOTH ways -- which is
		// why "the traverse estimate is lower now" would be the wrong pin. A traversal returns
		// element summaries nested inside hand-built hop relationships; get-view-contents returns
		// full DTO rows through field selection.
		//
		// Above the minimal preset the element half dominates and the shared estimate charged a
		// 250-char element row for a 105-char summary, so traverse costs LESS once split. At
		// minimal the relationship half dominates instead: the shared estimate charged 55 chars
		// for a hop relationship that is 184 whatever the preset, so traverse costs MORE. A caller
		// at fields=minimal was being told a traversal was cheap when it was not.
		assertTrue("traverse must cost more at minimal, where the shared estimate under-charged "
				+ "the hop relationships",
				CostEstimator.estimateTokensForTraversal(15, 10, FieldPreset.MINIMAL)
						> conceptsOnly(15, 10, FieldPreset.MINIMAL));
		for (FieldPreset p : new FieldPreset[] { FieldPreset.STANDARD, FieldPreset.FULL }) {
			assertTrue("traverse must cost less at " + p + ", where the shared estimate charged a "
					+ "full element row for a summary",
					CostEstimator.estimateTokensForTraversal(15, 10, p)
							< conceptsOnly(15, 10, p));
		}
	}

	@Test
	public void shouldNotMoveTheTraverseRelationshipHalf_withThePreset() {
		// The hop relationship is hand-built by the traversal engine and never consults the
		// preset. Before the split this half was charged three different widths -- 55 at minimal
		// and 365 at full -- for a row that is neither.
		int standard = CostEstimator.estimateTokensForTraversal(0, 10, FieldPreset.STANDARD);
		for (FieldPreset p : FieldPreset.values()) {
			assertEquals("the traverse relationship half must not move at " + p, standard,
					CostEstimator.estimateTokensForTraversal(0, 10, p));
		}
	}

	@Test
	public void shouldEstimateTokensForViewContents_whenZeroCounts() {
		int tokens = conceptsOnly(0, 0, FieldPreset.STANDARD);
		// Envelope only: 280 / 4 = 70 (rounded up)
		assertEquals(70, tokens);
	}

	// ---- estimateTokensForDepth ----

	@Test
	public void shouldEstimateTokensForDepth_depth0() {
		int tokens = CostEstimator.estimateTokensForDepth(10, 0, FieldPreset.STANDARD);
		// 10 * 185 + 280 = 2130 / 4 = 533 (rounded up).
		// Moved from 300: depth 0 no longer carries a constant of its own. It returns the same
		// flat relationship rows search-relationships does, so it delegates to the relationship
		// estimate rather than duplicating a number that must move with it.
		assertEquals(533, tokens);
	}

	@Test
	public void shouldEstimateTokensForDepth_depth0_equalsTheRelationshipEstimate() {
		for (FieldPreset p : FieldPreset.values()) {
			assertEquals("depth 0 must delegate to the relationship estimate at " + p,
					CostEstimator.estimateTokens(10, p, ItemType.RELATIONSHIP),
					CostEstimator.estimateTokensForDepth(10, 0, p));
		}
	}

	@Test
	public void shouldEstimateTokensForDepth_depthAboveZero_isSameForStandardAndFull() {
		// A real invariant, not an approximation: above depth 0 the relationship half of an
		// expanded row carries no preset-dependent fields, and the elements embedded in it are
		// filtered by the element preset, whose standard and full field sets are the same set.
		for (int depth = 1; depth <= 3; depth++) {
			assertEquals("depth " + depth + " must be identical at standard and full",
					CostEstimator.estimateTokensForDepth(10, depth, FieldPreset.STANDARD),
					CostEstimator.estimateTokensForDepth(10, depth, FieldPreset.FULL));
		}
	}

	@Test
	public void shouldEstimateTokensForDepth_depthAboveZero_followsTheMinimalPreset() {
		// Re-anchored from the pin that asserted the opposite. The embedded elements shrink at the
		// minimal preset at every depth -- the traversal handler filters them to id and name -- and
		// the estimate now follows, where before it charged the standard width whatever the caller
		// asked for. Strictly narrower, not merely different: an equal figure here would mean the
		// composition had collapsed back onto one column.
		for (int depth = 1; depth <= 3; depth++) {
			assertTrue("depth " + depth + " must cost strictly less at the minimal preset",
					CostEstimator.estimateTokensForDepth(10, depth, FieldPreset.MINIMAL)
							< CostEstimator.estimateTokensForDepth(10, depth, FieldPreset.STANDARD));
		}
	}

	@Test
	public void shouldEstimateTokensForDepth_depth1() {
		int tokens = CostEstimator.estimateTokensForDepth(10, 1, FieldPreset.STANDARD);
		// 10 * (105 + 2 * 105) + 280 = 3430 / 4 = 858 (rounded up).
		// Moved from 617 with the depth width: an expanded row is a 105-char relationship header
		// plus two embedded element summaries, and at depth 1 a summary is id, name and type.
		assertEquals(858, tokens);
	}

	@Test
	public void shouldEstimateTokensForDepth_depth2() {
		int tokens = CostEstimator.estimateTokensForDepth(10, 2, FieldPreset.STANDARD);
		// 10 * (105 + 2 * 250) + 280 = 6330 / 4 = 1583 (rounded up).
		// Depth 2 embeds the full element row rather than a summary, so it reads the element width.
		assertEquals(1583, tokens);
	}

	@Test
	public void shouldEstimateTokensForDepth_depth3() {
		int tokens = CostEstimator.estimateTokensForDepth(10, 3, FieldPreset.STANDARD);
		// 10 * (105 + 2 * (250 + 460)) + 280 = 15530 / 4 = 3883 (rounded up).
		// Depth 3 adds each embedded element's own relationships on top of its row.
		assertEquals(3883, tokens);
	}

	@Test
	public void shouldEstimateTokensForDepth_zeroRelationships() {
		int tokens = CostEstimator.estimateTokensForDepth(0, 1, FieldPreset.STANDARD);
		// Envelope only: 280 / 4 = 70 (rounded up)
		assertEquals(70, tokens);
	}

	// ---- recommendPreset ----

	@Test
	public void shouldRecommendStandard_whenSmallResult() {
		assertEquals("standard", CostEstimator.recommendPreset(500));
		assertEquals("standard", CostEstimator.recommendPreset(1999));
	}

	@Test
	public void shouldRecommendStandard_whenMediumResult() {
		assertEquals("standard", CostEstimator.recommendPreset(2000));
		assertEquals("standard", CostEstimator.recommendPreset(5000));
		assertEquals("standard", CostEstimator.recommendPreset(8000));
	}

	@Test
	public void shouldRecommendMinimalWithFilters_whenLargeResult() {
		assertEquals("minimal with filters", CostEstimator.recommendPreset(8001));
		assertEquals("minimal with filters", CostEstimator.recommendPreset(50000));
	}

	// ---- buildRecommendation ----

	@Test
	public void shouldBuildRecommendation_forSmallResult() {
		String rec = CostEstimator.buildRecommendation(5, 500, FieldPreset.STANDARD, 300);
		assertTrue(rec.contains("Small result set"));
		assertTrue(rec.contains("5 items"));
		assertTrue(rec.contains("500 tokens"));
		assertTrue(rec.contains("Safe to execute"));
	}

	@Test
	public void shouldBuildRecommendation_forMediumResult() {
		String rec = CostEstimator.buildRecommendation(30, 5000, FieldPreset.STANDARD, 2000);
		assertTrue(rec.contains("Medium result set"));
		assertTrue(rec.contains("30 items"));
		assertTrue(rec.contains("limit"));
	}

	@Test
	public void shouldBuildRecommendation_forLargeResult() {
		String rec = CostEstimator.buildRecommendation(247, 15438, FieldPreset.STANDARD,
				CostEstimator.estimateTokens(247, FieldPreset.MINIMAL, ItemType.ELEMENT));
		assertTrue(rec.contains("Large result set"));
		assertTrue(rec.contains("247 items"));
		assertTrue(rec.contains("fields=minimal"));
		assertTrue(rec.contains("reduction"));
	}

	@Test
	public void shouldBuildRecommendation_withCurrentPresetName() {
		String rec = CostEstimator.buildRecommendation(10, 3000, FieldPreset.MINIMAL, 800);
		assertTrue(rec.contains("minimal"));
	}

	@Test
	public void shouldBuildRecommendation_withItemTypeOverload_forViews() {
		// Large result with VIEW type — savings should use VIEW_MINIMAL_CHARS not ELEMENT_MINIMAL_CHARS
		String recView = CostEstimator.buildRecommendation(100, 10000, FieldPreset.STANDARD,
				CostEstimator.estimateTokens(100, FieldPreset.MINIMAL, ItemType.VIEW));
		assertTrue(recView.contains("Large result set"));
		assertTrue(recView.contains("reduction"));

		// Compare: same count with ELEMENT type should give different savings %
		String recElement = CostEstimator.buildRecommendation(100, 10000, FieldPreset.STANDARD,
				CostEstimator.estimateTokens(100, FieldPreset.MINIMAL, ItemType.ELEMENT));
		// Both should mention reduction but percentages may differ due to different minimal char costs
		assertTrue(recElement.contains("reduction"));
	}

	@Test
	public void shouldBuildRecommendation_savingsMatchTheItemTypeItWasGiven() {
		// 320 relationships at standard reaches the large-result branch.
		int estimatedTokens = CostEstimator.estimateTokens(320, FieldPreset.STANDARD, ItemType.RELATIONSHIP);
		assertTrue("fixture must reach the large-result branch", estimatedTokens > CostEstimator.THRESHOLD_LARGE);

		int minimalTokens = CostEstimator.estimateTokens(320, FieldPreset.MINIMAL, ItemType.RELATIONSHIP);
		int expectedPercent = (int) ((1.0 - (double) minimalTokens / estimatedTokens) * 100);
		assertTrue("a relationship result must publish a real, non-zero saving", expectedPercent > 0);

		String rec = CostEstimator.buildRecommendation(320, estimatedTokens, FieldPreset.STANDARD,
				minimalTokens);
		assertTrue("recommendation must quote the relationship minimal estimate, was: " + rec,
				rec.contains("~" + minimalTokens + " tokens"));
		assertTrue("recommendation must quote the relationship saving, was: " + rec,
				rec.contains("~" + expectedPercent + "% reduction"));
	}

	@Test
	public void shouldBuildRecommendation_savingsDifferByItemType() {
		// The same count and token figure must not produce the same saving for two item
		// types whose minimal widths differ -- that collapse is what a hardcoded item type
		// caused before the type was plumbed through from the call sites.
		int tokens = 20000;
		String asElement = CostEstimator.buildRecommendation(320, tokens, FieldPreset.STANDARD,
				CostEstimator.estimateTokens(320, FieldPreset.MINIMAL, ItemType.ELEMENT));
		String asRelationship = CostEstimator.buildRecommendation(320, tokens, FieldPreset.STANDARD,
				CostEstimator.estimateTokens(320, FieldPreset.MINIMAL, ItemType.RELATIONSHIP));
		assertNotEquals("savings must follow the projection the caller supplied", asElement, asRelationship);
	}

	@Test
	public void shouldWithholdThePresetSuggestion_whenNarrowingItWouldNotHelp() {
		// A caller who is ALREADY at the minimal preset, large enough to reach the large-result
		// branch. Every dry-run call site computes the projection at minimal and the estimate at
		// the caller's own preset, so for this caller the two are the same call and come back
		// equal -- there is no narrower preset left to suggest, and the sentence must not
		// advertise a reduction the caller would not get.
		//
		// This fixture replaces a depth-2 expansion, which was preset-invariant only because the
		// depth widths were flat. Now that they follow the preset, that fixture would assert an
		// equality the estimator no longer produces. A caller at minimal is invariant for a
		// reason that holds however the widths move.
		//
		// The precondition carrying this fixture is NOT that those two calls agree: they are the
		// same call, and two calls to a pure function agree whatever the estimator does, so
		// asserting it would pin nothing. It is that minimal really is the NARROWEST preset there
		// is -- that is what makes "already at minimal" mean "nothing left to narrow to", and it
		// stops being true the moment a width is mis-ordered.
		FieldPreset callerPreset = FieldPreset.MINIMAL;
		int count = 500;
		for (FieldPreset p : FieldPreset.values()) {
			assertTrue("minimal must be the narrowest preset, but " + p + " estimates no higher",
					CostEstimator.estimateTokens(count, FieldPreset.MINIMAL, ItemType.ELEMENT)
							<= CostEstimator.estimateTokens(count, p, ItemType.ELEMENT));
		}
		int estimated = CostEstimator.estimateTokens(count, callerPreset, ItemType.ELEMENT);
		int projected = CostEstimator.estimateTokens(count, FieldPreset.MINIMAL, ItemType.ELEMENT);
		assertTrue("fixture must reach the large-result branch", estimated > CostEstimator.THRESHOLD_LARGE);
		assertEquals("and so this caller's projection is its own estimate", estimated, projected);

		String rec = CostEstimator.buildRecommendation(count, estimated, callerPreset, projected);
		assertFalse("must not advertise a preset change that saves nothing: " + rec,
				rec.contains("fields=minimal"));
		assertFalse("must not quote a reduction figure at all: " + rec, rec.contains("reduction"));
		assertTrue("must still offer the remedy that does work: " + rec, rec.contains("filters"));
	}

	@Test
	public void shouldStillAdvertiseTheSaving_whenNarrowingThePresetDoesHelp() {
		// The positive control for the test above: where the projection IS lower, the sentence
		// must still carry the figure. A guard that only checks the withholding case would stay
		// green if the clause were deleted outright.
		int estimated = CostEstimator.estimateTokens(320, FieldPreset.STANDARD, ItemType.RELATIONSHIP);
		int projected = CostEstimator.estimateTokens(320, FieldPreset.MINIMAL, ItemType.RELATIONSHIP);
		assertTrue("fixture must reach the large-result branch", estimated > CostEstimator.THRESHOLD_LARGE);
		assertTrue("fixture must be one where the preset does move the estimate", projected < estimated);

		String rec = CostEstimator.buildRecommendation(320, estimated, FieldPreset.STANDARD, projected);
		assertTrue("must advertise the real saving: " + rec, rec.contains("fields=minimal"));
		assertTrue("must quote the reduction: " + rec, rec.contains("reduction"));
	}

}
