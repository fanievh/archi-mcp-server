package net.vheerden.archi.mcp.session;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-session LRU cache for query results (Story 5.4 FR38).
 *
 * <p>Stores JSON result strings keyed by a cache key that encodes the
 * command name and effective parameters. Bounded by {@link #maxEntries}
 * with least-recently-used eviction.</p>
 *
 * <p><strong>Thread safety:</strong> Uses {@link ConcurrentHashMap} for
 * the entry store and {@code volatile} for access timestamps. Concurrent
 * eviction calls are safe — worst case, one extra entry is evicted.</p>
 */
public class SessionCache {

    private static final Logger logger = LoggerFactory.getLogger(SessionCache.class);

    /** Default maximum entries per session cache. */
    static final int DEFAULT_MAX_ENTRIES = 100;

    private final int maxEntries;
    private final ConcurrentHashMap<String, CacheEntry> entries = new ConcurrentHashMap<>();

    /**
     * Creates a SessionCache with the default maximum entries.
     */
    public SessionCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * Creates a SessionCache with a specified maximum entries limit.
     *
     * @param maxEntries the maximum number of cache entries before LRU eviction
     * @throws IllegalArgumentException if maxEntries is less than 1
     */
    public SessionCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1, got: " + maxEntries);
        }
        this.maxEntries = maxEntries;
    }

    /**
     * Gets a cached result by key, updating its access time for LRU tracking.
     *
     * @param cacheKey the cache key
     * @return the cached JSON result, or {@code null} if not present
     */
    public String get(String cacheKey) {
        CacheEntry entry = entries.get(cacheKey);
        if (entry != null) {
            entry.touch();
            return entry.jsonResult();
        }
        return null;
    }

    /**
     * Stores a result in the cache, evicting LRU entries if the cache is full.
     *
     * @param cacheKey   the cache key (must not be null)
     * @param jsonResult the JSON result string to cache (must not be null)
     */
    public void put(String cacheKey, String jsonResult) {
        Objects.requireNonNull(cacheKey, "cacheKey must not be null");
        Objects.requireNonNull(jsonResult, "jsonResult must not be null");
        entries.put(cacheKey, new CacheEntry(jsonResult));
        evictIfNeeded();
    }

    /**
     * Clears all entries from the cache.
     */
    public void clear() {
        entries.clear();
    }

    /**
     * Returns the current number of entries in the cache.
     *
     * @return the cache size
     */
    public int size() {
        return entries.size();
    }

    /**
     * Returns the maximum number of entries allowed.
     *
     * @return the max entries limit
     */
    int getMaxEntries() {
        return maxEntries;
    }

    private void evictIfNeeded() {
        while (entries.size() > maxEntries) {
            String lruKey = entries.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().lastAccessed()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (lruKey != null) {
                entries.remove(lruKey);
                logger.debug("LRU eviction: removed entry for key: {}", lruKey);
            } else {
                break;
            }
        }
    }

    /**
     * Internal cache entry storing the JSON result and LRU access timestamp.
     */
    static class CacheEntry {
        private final String jsonResult;
        private volatile long lastAccessed;

        CacheEntry(String jsonResult) {
            this.jsonResult = jsonResult;
            this.lastAccessed = System.nanoTime();
        }

        String jsonResult() {
            return jsonResult;
        }

        long lastAccessed() {
            return lastAccessed;
        }

        void touch() {
            this.lastAccessed = System.nanoTime();
        }
    }
}
