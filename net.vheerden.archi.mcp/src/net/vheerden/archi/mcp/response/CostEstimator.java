package net.vheerden.archi.mcp.response;

import net.vheerden.archi.mcp.response.FieldSelector.FieldPreset;

/**
 * Token estimation utility for dry-run cost estimation (Story 6.2, FR20).
 *
 * <p>Estimates approximate token counts for query results based on item count,
 * field preset, and item type. Uses character-per-item heuristics divided by
 * a standard chars-per-token ratio.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class imports only
 * {@link FieldSelector.FieldPreset}. It has no handler, model, or session dependencies.</p>
 */
public final class CostEstimator {

	// --- Per-item character estimates by field preset ---

	static final int ELEMENT_MINIMAL_CHARS = 60;
	static final int ELEMENT_STANDARD_CHARS = 250;
	static final int ELEMENT_FULL_CHARS = 250;

	static final int VIEW_MINIMAL_CHARS = 60;
	static final int VIEW_STANDARD_CHARS = 120;

	static final int RELATIONSHIP_CHARS = 100;

	static final int ENVELOPE_OVERHEAD_CHARS = 200;
	static final int CHARS_PER_TOKEN = 4;

	// --- Depth-mode per-relationship character estimates ---

	static final int DEPTH_0_CHARS = 100;
	static final int DEPTH_1_CHARS = 220;
	static final int DEPTH_2_CHARS = 600;
	static final int DEPTH_3_CHARS = 1200;

	// --- Recommendation thresholds ---

	public static final int THRESHOLD_COMFORTABLE = 2000;
	public static final int THRESHOLD_LARGE = 8000;

	/**
	 * Item types for cost estimation.
	 */
	public enum ItemType {
		ELEMENT, VIEW, RELATIONSHIP, MIXED
	}

	private CostEstimator() {
	}

	/**
	 * Estimates token count for a homogeneous result set.
	 *
	 * @param itemCount number of items in the result
	 * @param preset    the field preset used for the query
	 * @param itemType  the type of items in the result
	 * @return estimated token count
	 */
	public static int estimateTokens(int itemCount, FieldPreset preset, ItemType itemType) {
		if (itemCount <= 0) {
			return charsToTokens(ENVELOPE_OVERHEAD_CHARS);
		}
		int charsPerItem = getCharsPerItem(preset, itemType);
		int totalChars = (itemCount * charsPerItem) + ENVELOPE_OVERHEAD_CHARS;
		return charsToTokens(totalChars);
	}

	/**
	 * Estimates token count for a mixed result set (elements + relationships), used
	 * by get-view-contents dry-run.
	 *
	 * @param elementCount      number of elements
	 * @param relationshipCount number of relationships
	 * @param preset            the field preset used for the query
	 * @return estimated token count
	 */
	public static int estimateTokensMixed(int elementCount, int relationshipCount, FieldPreset preset) {
		int elementChars = elementCount * getCharsPerItem(preset, ItemType.ELEMENT);
		int relationshipChars = relationshipCount * RELATIONSHIP_CHARS;
		int totalChars = elementChars + relationshipChars + ENVELOPE_OVERHEAD_CHARS;
		return charsToTokens(totalChars);
	}

	/**
	 * Estimates token count for get-relationships depth mode based on the expansion
	 * depth.
	 *
	 * @param relationshipCount number of relationships
	 * @param depth             expansion depth (0-3)
	 * @return estimated token count
	 */
	public static int estimateTokensForDepth(int relationshipCount, int depth) {
		if (relationshipCount <= 0) {
			return charsToTokens(ENVELOPE_OVERHEAD_CHARS);
		}
		int charsPerRelationship = switch (depth) {
		case 0 -> DEPTH_0_CHARS;
		case 1 -> DEPTH_1_CHARS;
		case 2 -> DEPTH_2_CHARS;
		case 3 -> DEPTH_3_CHARS;
		default -> DEPTH_1_CHARS;
		};
		int totalChars = (relationshipCount * charsPerRelationship) + ENVELOPE_OVERHEAD_CHARS;
		return charsToTokens(totalChars);
	}

	/**
	 * Recommends a field preset based on estimated tokens at standard preset.
	 *
	 * @param estimatedTokensAtStandard token estimate calculated at STANDARD preset
	 * @return recommendation string: "standard", "minimal", or "minimal with
	 *         filters"
	 */
	public static String recommendPreset(int estimatedTokensAtStandard) {
		if (estimatedTokensAtStandard < THRESHOLD_COMFORTABLE) {
			return "standard";
		} else if (estimatedTokensAtStandard <= THRESHOLD_LARGE) {
			return "standard";
		} else {
			return "minimal with filters";
		}
	}

	/**
	 * Builds a human-readable recommendation string for the dry-run response.
	 *
	 * @param estimatedCount  number of items in the result
	 * @param estimatedTokens estimated token count at the current preset
	 * @param currentPreset   the field preset used for estimation
	 * @return recommendation text with actionable suggestions
	 */
	public static String buildRecommendation(int estimatedCount, int estimatedTokens, FieldPreset currentPreset) {
		return buildRecommendation(estimatedCount, estimatedTokens, currentPreset, ItemType.ELEMENT);
	}

	/**
	 * Builds a human-readable recommendation string for the dry-run response.
	 *
	 * @param estimatedCount  number of items in the result
	 * @param estimatedTokens estimated token count at the current preset
	 * @param currentPreset   the field preset used for estimation
	 * @param itemType        the type of items for savings estimation
	 * @return recommendation text with actionable suggestions
	 */
	public static String buildRecommendation(int estimatedCount, int estimatedTokens, FieldPreset currentPreset,
			ItemType itemType) {
		if (estimatedTokens < THRESHOLD_COMFORTABLE) {
			return String.format("Small result set (%d items, ~%d tokens at %s). Safe to execute as-is.", estimatedCount,
					estimatedTokens, currentPreset.value());
		} else if (estimatedTokens <= THRESHOLD_LARGE) {
			return String.format(
					"Medium result set (%d items, ~%d tokens at %s). Consider using limit parameter for paginated retrieval.",
					estimatedCount, estimatedTokens, currentPreset.value());
		} else {
			int minimalTokens = estimateTokens(estimatedCount, FieldPreset.MINIMAL, itemType);
			int savingsPercent = estimatedTokens > 0 ? (int) ((1.0 - (double) minimalTokens / estimatedTokens) * 100)
					: 0;
			return String.format(
					"Large result set (%d items, ~%d tokens at %s). Consider using fields=minimal (~%d tokens, ~%d%% reduction) or adding type/layer filters to narrow results.",
					estimatedCount, estimatedTokens, currentPreset.value(), minimalTokens, savingsPercent);
		}
	}

	private static int getCharsPerItem(FieldPreset preset, ItemType itemType) {
		return switch (itemType) {
		case ELEMENT -> switch (preset) {
		case MINIMAL -> ELEMENT_MINIMAL_CHARS;
		case STANDARD -> ELEMENT_STANDARD_CHARS;
		case FULL -> ELEMENT_FULL_CHARS;
		};
		case VIEW -> switch (preset) {
		case MINIMAL -> VIEW_MINIMAL_CHARS;
		case STANDARD, FULL -> VIEW_STANDARD_CHARS;
		};
		case RELATIONSHIP -> RELATIONSHIP_CHARS;
		case MIXED -> switch (preset) {
		case MINIMAL -> ELEMENT_MINIMAL_CHARS;
		case STANDARD -> ELEMENT_STANDARD_CHARS;
		case FULL -> ELEMENT_FULL_CHARS;
		};
		};
	}

	private static int charsToTokens(int chars) {
		return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
	}
}
