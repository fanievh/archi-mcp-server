package net.vheerden.archi.mcp.response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link StringSimilarity} utility (Story 7-4, Task 1).
 */
public class StringSimilarityTest {

    private static final double DELTA = 0.01;

    // ---- normalizedLevenshtein tests ----

    @Test
    public void shouldReturnOne_whenIdenticalStrings() {
        assertEquals(1.0, StringSimilarity.normalizedLevenshtein("Customer", "Customer"), DELTA);
    }

    @Test
    public void shouldReturnOne_whenIdenticalCaseInsensitive() {
        assertEquals(1.0, StringSimilarity.normalizedLevenshtein("customer", "CUSTOMER"), DELTA);
    }

    @Test
    public void shouldReturnZero_whenNullInput() {
        assertEquals(0.0, StringSimilarity.normalizedLevenshtein(null, "test"), DELTA);
        assertEquals(0.0, StringSimilarity.normalizedLevenshtein("test", null), DELTA);
        assertEquals(0.0, StringSimilarity.normalizedLevenshtein(null, null), DELTA);
    }

    @Test
    public void shouldReturnZero_whenEmptyInput() {
        assertEquals(0.0, StringSimilarity.normalizedLevenshtein("", "test"), DELTA);
        assertEquals(0.0, StringSimilarity.normalizedLevenshtein("test", ""), DELTA);
    }

    @Test
    public void shouldReturnHighScore_whenSimilarStrings() {
        double score = StringSimilarity.normalizedLevenshtein("Customer Service", "Customer Services");
        assertTrue("Similar strings should score high, got: " + score, score > 0.9);
    }

    @Test
    public void shouldReturnLowScore_whenCompletelyDifferent() {
        double score = StringSimilarity.normalizedLevenshtein("Alpha", "Zebra");
        assertTrue("Completely different strings should score low, got: " + score, score < 0.5);
    }

    @Test
    public void shouldHandleWhitespace() {
        assertEquals(1.0, StringSimilarity.normalizedLevenshtein("  test  ", "test"), DELTA);
    }

    // ---- tokenOverlap tests ----

    @Test
    public void shouldReturnOne_whenIdenticalTokens() {
        assertEquals(1.0, StringSimilarity.tokenOverlap("Customer Service", "Customer Service"), DELTA);
    }

    @Test
    public void shouldReturnOne_whenTokensReordered() {
        assertEquals(1.0, StringSimilarity.tokenOverlap("Payment Service", "Service Payment"), DELTA);
    }

    @Test
    public void shouldReturnZero_whenNoCommonTokens() {
        assertEquals(0.0, StringSimilarity.tokenOverlap("Alpha Beta", "Gamma Delta"), DELTA);
    }

    @Test
    public void shouldReturnZero_whenNullTokenInput() {
        assertEquals(0.0, StringSimilarity.tokenOverlap(null, "test"), DELTA);
        assertEquals(0.0, StringSimilarity.tokenOverlap("test", null), DELTA);
    }

    @Test
    public void shouldReturnZero_whenEmptyTokenInput() {
        assertEquals(0.0, StringSimilarity.tokenOverlap("", "test"), DELTA);
        assertEquals(0.0, StringSimilarity.tokenOverlap("test", ""), DELTA);
    }

    @Test
    public void shouldReturnPartialOverlap_whenSomeTokensMatch() {
        // "Customer Service" vs "Customer Portal" → intersection={customer}, union={customer,service,portal}
        double score = StringSimilarity.tokenOverlap("Customer Service", "Customer Portal");
        assertEquals(1.0 / 3.0, score, DELTA);
    }

    @Test
    public void shouldBeCaseInsensitive_forTokenOverlap() {
        assertEquals(1.0, StringSimilarity.tokenOverlap("CUSTOMER SERVICE", "customer service"), DELTA);
    }

    // ---- compositeSimilarity tests ----

    @Test
    public void shouldReturnOne_whenIdenticalComposite() {
        assertEquals(1.0, StringSimilarity.compositeSimilarity("Customer Service", "Customer Service"), DELTA);
    }

    @Test
    public void shouldBeAboveThreshold_whenSimilarNames() {
        // 3-word names with minor diff: shared tokens boost composite above threshold
        double score = StringSimilarity.compositeSimilarity(
                "Customer Order Service", "Customer Order Services");
        assertTrue("Similar names should be above threshold, got: " + score,
                score >= StringSimilarity.DUPLICATE_THRESHOLD);
    }

    @Test
    public void shouldBeBelowThreshold_whenDifferentConcepts() {
        double score = StringSimilarity.compositeSimilarity("Customer Service", "Payment Gateway");
        assertTrue("Different concepts should be below threshold, got: " + score,
                score < StringSimilarity.DUPLICATE_THRESHOLD);
    }

    @Test
    public void shouldScoreHigherThanLevenshteinAlone_whenTokensReordered() {
        // Token overlap is 1.0 for reordered words, boosting the composite score
        double levenshtein = StringSimilarity.normalizedLevenshtein("Payment Service", "Service Payment");
        double composite = StringSimilarity.compositeSimilarity("Payment Service", "Service Payment");
        assertTrue("Composite should be higher than Levenshtein alone for reordered words",
                composite > levenshtein);
    }

    @Test
    public void shouldReturnZero_whenBothNull() {
        assertEquals(0.0, StringSimilarity.compositeSimilarity(null, null), DELTA);
    }

    @Test
    public void shouldScoreReasonably_whenSingleWordNamesSlightlyDiffer() {
        // Single-word names get 0.0 from token overlap (different tokens),
        // so composite is only 0.6 * Levenshtein. Still a meaningful score.
        double score = StringSimilarity.compositeSimilarity("Customer", "Customers");
        assertTrue("Single word similar names should score > 0.5, got: " + score, score > 0.5);
    }

    // ---- threshold constant test ----

    @Test
    public void shouldHaveThresholdOfPointSeven() {
        assertEquals(0.7, StringSimilarity.DUPLICATE_THRESHOLD, DELTA);
    }
}
