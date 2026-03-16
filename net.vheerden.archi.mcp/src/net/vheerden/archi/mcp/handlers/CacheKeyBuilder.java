package net.vheerden.archi.mcp.handlers;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared cache key builder for handler cache integration (Story 5.4).
 *
 * <p>Constructs deterministic cache keys from command name + effective parameters.
 * Used by all query handlers to avoid duplicating key construction logic.</p>
 */
final class CacheKeyBuilder {

    private CacheKeyBuilder() {} // utility class

    /**
     * Builds a cache key from a command name and parameter parts.
     * Format: {@code command|part1|part2|...} with null rendered as "null".
     */
    static String buildCacheKey(String command, Object... parts) {
        StringBuilder sb = new StringBuilder(command);
        for (Object part : parts) {
            sb.append('|');
            sb.append(part != null ? part.toString() : "null");
        }
        return sb.toString();
    }

    /**
     * Converts a set of strings into a sorted, comma-separated key component.
     * Returns empty string for null or empty sets.
     */
    static String sortedSetKey(Set<String> set) {
        if (set == null || set.isEmpty()) return "";
        return set.stream().sorted().collect(Collectors.joining(","));
    }
}
