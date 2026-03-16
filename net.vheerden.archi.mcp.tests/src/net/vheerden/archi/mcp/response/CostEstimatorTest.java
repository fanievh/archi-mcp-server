package net.vheerden.archi.mcp.response;

import static org.junit.Assert.*;

import org.junit.Test;

import net.vheerden.archi.mcp.response.CostEstimator.ItemType;
import net.vheerden.archi.mcp.response.FieldSelector.FieldPreset;

/**
 * Tests for {@link CostEstimator} — token estimation and recommendation logic (Story 6.2).
 */
public class CostEstimatorTest {

	// ---- estimateTokens: Element types ----

	@Test
	public void shouldEstimateTokens_forElementMinimal() {
		int tokens = CostEstimator.estimateTokens(10, FieldPreset.MINIMAL, ItemType.ELEMENT);
		// 10 * 60 + 200 = 800 chars / 4 = 200 tokens
		assertEquals(200, tokens);
	}

	@Test
	public void shouldEstimateTokens_forElementStandard() {
		int tokens = CostEstimator.estimateTokens(10, FieldPreset.STANDARD, ItemType.ELEMENT);
		// 10 * 250 + 200 = 2700 chars / 4 = 675 tokens
		assertEquals(675, tokens);
	}

	@Test
	public void shouldEstimateTokens_forElementFull() {
		int tokens = CostEstimator.estimateTokens(10, FieldPreset.FULL, ItemType.ELEMENT);
		// 10 * 250 + 200 = 2700 chars / 4 = 675 tokens
		assertEquals(675, tokens);
	}

	// ---- estimateTokens: View type ----

	@Test
	public void shouldEstimateTokens_forViewMinimal() {
		int tokens = CostEstimator.estimateTokens(20, FieldPreset.MINIMAL, ItemType.VIEW);
		// 20 * 60 + 200 = 1400 chars / 4 = 350 tokens
		assertEquals(350, tokens);
	}

	@Test
	public void shouldEstimateTokens_forViewStandard() {
		int tokens = CostEstimator.estimateTokens(20, FieldPreset.STANDARD, ItemType.VIEW);
		// 20 * 120 + 200 = 2600 chars / 4 = 650 tokens
		assertEquals(650, tokens);
	}

	// ---- estimateTokens: Relationship type ----

	@Test
	public void shouldEstimateTokens_forRelationship() {
		int tokens = CostEstimator.estimateTokens(50, FieldPreset.STANDARD, ItemType.RELATIONSHIP);
		// 50 * 100 + 200 = 5200 chars / 4 = 1300 tokens
		assertEquals(1300, tokens);
	}

	// ---- estimateTokens: Edge cases ----

	@Test
	public void shouldEstimateTokens_whenZeroItems() {
		int tokens = CostEstimator.estimateTokens(0, FieldPreset.STANDARD, ItemType.ELEMENT);
		// 0 items → envelope overhead only: 200 / 4 = 50 tokens
		assertEquals(50, tokens);
	}

	@Test
	public void shouldEstimateTokens_whenNegativeItems() {
		int tokens = CostEstimator.estimateTokens(-5, FieldPreset.STANDARD, ItemType.ELEMENT);
		// Treated same as 0
		assertEquals(50, tokens);
	}

	@Test
	public void shouldEstimateTokens_forLargeCount() {
		int tokens = CostEstimator.estimateTokens(1000, FieldPreset.STANDARD, ItemType.ELEMENT);
		// 1000 * 250 + 200 = 250200 chars / 4 = 62550 tokens
		assertEquals(62550, tokens);
	}

	// ---- estimateTokensMixed: View contents ----

	@Test
	public void shouldEstimateTokensMixed_forViewContents() {
		int tokens = CostEstimator.estimateTokensMixed(15, 10, FieldPreset.STANDARD);
		// 15 * 250 + 10 * 100 + 200 = 3750 + 1000 + 200 = 4950 chars / 4 = 1238 tokens (rounded up)
		assertEquals(1238, tokens);
	}

	@Test
	public void shouldEstimateTokensMixed_withMinimalPreset() {
		int tokens = CostEstimator.estimateTokensMixed(15, 10, FieldPreset.MINIMAL);
		// 15 * 60 + 10 * 100 + 200 = 900 + 1000 + 200 = 2100 chars / 4 = 525 tokens
		assertEquals(525, tokens);
	}

	@Test
	public void shouldEstimateTokensMixed_whenZeroCounts() {
		int tokens = CostEstimator.estimateTokensMixed(0, 0, FieldPreset.STANDARD);
		// Envelope only: 200 / 4 = 50
		assertEquals(50, tokens);
	}

	// ---- estimateTokensForDepth ----

	@Test
	public void shouldEstimateTokensForDepth_depth0() {
		int tokens = CostEstimator.estimateTokensForDepth(10, 0);
		// 10 * 100 + 200 = 1200 / 4 = 300
		assertEquals(300, tokens);
	}

	@Test
	public void shouldEstimateTokensForDepth_depth1() {
		int tokens = CostEstimator.estimateTokensForDepth(10, 1);
		// 10 * 220 + 200 = 2400 / 4 = 600
		assertEquals(600, tokens);
	}

	@Test
	public void shouldEstimateTokensForDepth_depth2() {
		int tokens = CostEstimator.estimateTokensForDepth(10, 2);
		// 10 * 600 + 200 = 6200 / 4 = 1550
		assertEquals(1550, tokens);
	}

	@Test
	public void shouldEstimateTokensForDepth_depth3() {
		int tokens = CostEstimator.estimateTokensForDepth(10, 3);
		// 10 * 1200 + 200 = 12200 / 4 = 3050
		assertEquals(3050, tokens);
	}

	@Test
	public void shouldEstimateTokensForDepth_zeroRelationships() {
		int tokens = CostEstimator.estimateTokensForDepth(0, 1);
		assertEquals(50, tokens);
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
		String rec = CostEstimator.buildRecommendation(5, 500, FieldPreset.STANDARD);
		assertTrue(rec.contains("Small result set"));
		assertTrue(rec.contains("5 items"));
		assertTrue(rec.contains("500 tokens"));
		assertTrue(rec.contains("Safe to execute"));
	}

	@Test
	public void shouldBuildRecommendation_forMediumResult() {
		String rec = CostEstimator.buildRecommendation(30, 5000, FieldPreset.STANDARD);
		assertTrue(rec.contains("Medium result set"));
		assertTrue(rec.contains("30 items"));
		assertTrue(rec.contains("limit"));
	}

	@Test
	public void shouldBuildRecommendation_forLargeResult() {
		String rec = CostEstimator.buildRecommendation(247, 15438, FieldPreset.STANDARD);
		assertTrue(rec.contains("Large result set"));
		assertTrue(rec.contains("247 items"));
		assertTrue(rec.contains("fields=minimal"));
		assertTrue(rec.contains("reduction"));
	}

	@Test
	public void shouldBuildRecommendation_withCurrentPresetName() {
		String rec = CostEstimator.buildRecommendation(10, 3000, FieldPreset.MINIMAL);
		assertTrue(rec.contains("minimal"));
	}

	@Test
	public void shouldBuildRecommendation_withItemTypeOverload_forViews() {
		// Large result with VIEW type — savings should use VIEW_MINIMAL_CHARS not ELEMENT_MINIMAL_CHARS
		String recView = CostEstimator.buildRecommendation(100, 10000, FieldPreset.STANDARD, ItemType.VIEW);
		assertTrue(recView.contains("Large result set"));
		assertTrue(recView.contains("reduction"));

		// Compare: same count with ELEMENT type should give different savings %
		String recElement = CostEstimator.buildRecommendation(100, 10000, FieldPreset.STANDARD, ItemType.ELEMENT);
		// Both should mention reduction but percentages may differ due to different minimal char costs
		assertTrue(recElement.contains("reduction"));
	}

	// ---- getCharsPerItem: MIXED respects preset ----

	@Test
	public void shouldEstimateTokens_forMixedMinimal_lowerThanStandard() {
		int tokensMinimal = CostEstimator.estimateTokens(10, FieldPreset.MINIMAL, ItemType.MIXED);
		int tokensStandard = CostEstimator.estimateTokens(10, FieldPreset.STANDARD, ItemType.MIXED);
		assertTrue("MIXED minimal should produce fewer tokens than standard",
				tokensMinimal < tokensStandard);
	}
}
