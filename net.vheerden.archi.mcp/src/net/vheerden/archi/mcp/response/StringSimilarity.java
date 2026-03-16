package net.vheerden.archi.mcp.response;

import java.util.HashSet;
import java.util.Set;

/**
 * Pure-Java string similarity utilities for ArchiMate element name matching.
 *
 * <p>Provides normalized Levenshtein distance (character-level) and Jaccard
 * token overlap (word-level), combined into a composite score suitable for
 * detecting potential duplicate element names.</p>
 */
public final class StringSimilarity {

    /** Similarity score threshold above which elements are considered potential duplicates. */
    public static final double DUPLICATE_THRESHOLD = 0.7;

    private StringSimilarity() {
        // utility class
    }

    /**
     * Normalized Levenshtein similarity: 1.0 = identical, 0.0 = completely different.
     * Case-insensitive, trims whitespace.
     */
    public static double normalizedLevenshtein(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        String la = a.toLowerCase().trim();
        String lb = b.toLowerCase().trim();
        if (la.equals(lb)) {
            return 1.0;
        }
        if (la.isEmpty() || lb.isEmpty()) {
            return 0.0;
        }
        int maxLen = Math.max(la.length(), lb.length());
        int distance = levenshteinDistance(la, lb);
        return 1.0 - ((double) distance / maxLen);
    }

    /**
     * Jaccard token overlap similarity on whitespace-split tokens.
     * 1.0 = identical token sets, 0.0 = no tokens in common.
     * Case-insensitive, trims whitespace.
     */
    public static double tokenOverlap(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        Set<String> tokensA = tokenize(a.toLowerCase().trim());
        Set<String> tokensB = tokenize(b.toLowerCase().trim());
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);
        return (double) intersection.size() / union.size();
    }

    /**
     * Composite similarity: weighted blend of character-level and token-level.
     * 0.6 * normalizedLevenshtein + 0.4 * tokenOverlap.
     */
    public static double compositeSimilarity(String a, String b) {
        return 0.6 * normalizedLevenshtein(a, b) + 0.4 * tokenOverlap(a, b);
    }

    private static int levenshteinDistance(String a, String b) {
        int lenA = a.length();
        int lenB = b.length();
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];

        for (int j = 0; j <= lenB; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            for (int j = 1; j <= lenB; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                    Math.min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                );
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
        return prev[lenB];
    }

    private static Set<String> tokenize(String s) {
        Set<String> tokens = new HashSet<>();
        if (s == null || s.isEmpty()) {
            return tokens;
        }
        for (String token : s.split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
