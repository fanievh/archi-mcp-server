package net.vheerden.archi.mcp.session;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link SessionCache} (Story 5.4).
 *
 * <p>Pure Java — no MCP SDK or OSGi runtime required.</p>
 */
public class SessionCacheTest {

    private SessionCache cache;

    @Before
    public void setUp() {
        cache = new SessionCache();
    }

    // ---- Basic get/put ----

    @Test
    public void shouldReturnNull_whenKeyNotPresent() {
        assertNull(cache.get("nonexistent-key"));
    }

    @Test
    public void shouldReturnCachedValue_whenKeyPresent() {
        cache.put("key-1", "{\"result\":\"data\"}");
        assertEquals("{\"result\":\"data\"}", cache.get("key-1"));
    }

    @Test
    public void shouldOverwriteExistingEntry_whenSameKeyUsed() {
        cache.put("key-1", "old-data");
        cache.put("key-1", "new-data");
        assertEquals("new-data", cache.get("key-1"));
        assertEquals(1, cache.size());
    }

    @Test
    public void shouldStoreMultipleEntries() {
        cache.put("key-1", "data-1");
        cache.put("key-2", "data-2");
        cache.put("key-3", "data-3");
        assertEquals(3, cache.size());
        assertEquals("data-1", cache.get("key-1"));
        assertEquals("data-2", cache.get("key-2"));
        assertEquals("data-3", cache.get("key-3"));
    }

    // ---- Clear ----

    @Test
    public void shouldClearAllEntries() {
        cache.put("key-1", "data-1");
        cache.put("key-2", "data-2");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("key-1"));
        assertNull(cache.get("key-2"));
    }

    // ---- LRU eviction ----

    @Test
    public void shouldEvictLruEntry_whenMaxExceeded() {
        SessionCache smallCache = new SessionCache(3);

        smallCache.put("key-1", "data-1");
        smallCache.put("key-2", "data-2");
        smallCache.put("key-3", "data-3");
        assertEquals(3, smallCache.size());

        // Adding a 4th entry should evict the LRU (key-1)
        smallCache.put("key-4", "data-4");
        assertEquals(3, smallCache.size());
        assertNull("LRU entry should be evicted", smallCache.get("key-1"));
        assertEquals("data-4", smallCache.get("key-4"));
    }

    @Test
    public void shouldEvictCorrectEntry_whenAccessedEntryIsRetained() throws InterruptedException {
        SessionCache smallCache = new SessionCache(3);

        smallCache.put("key-1", "data-1");
        Thread.sleep(5); // ensure different nanoTime
        smallCache.put("key-2", "data-2");
        Thread.sleep(5);
        smallCache.put("key-3", "data-3");

        // Touch key-1 to make it recently used
        Thread.sleep(5);
        smallCache.get("key-1");

        // Add key-4 — should evict key-2 (least recently accessed)
        Thread.sleep(5);
        smallCache.put("key-4", "data-4");
        assertEquals(3, smallCache.size());
        assertNotNull("Recently accessed key-1 should be retained", smallCache.get("key-1"));
        assertNull("LRU key-2 should be evicted", smallCache.get("key-2"));
        assertNotNull("key-3 should be retained", smallCache.get("key-3"));
        assertNotNull("new key-4 should be present", smallCache.get("key-4"));
    }

    @Test
    public void shouldNotExceedMaxEntries_whenManyInserted() {
        SessionCache smallCache = new SessionCache(5);
        for (int i = 0; i < 20; i++) {
            smallCache.put("key-" + i, "data-" + i);
        }
        assertEquals(5, smallCache.size());
    }

    // ---- Edge cases ----

    @Test
    public void shouldWorkWithMaxEntriesOfOne() {
        SessionCache singleCache = new SessionCache(1);

        singleCache.put("key-1", "data-1");
        assertEquals(1, singleCache.size());

        singleCache.put("key-2", "data-2");
        assertEquals(1, singleCache.size());
        assertNull(singleCache.get("key-1"));
        assertEquals("data-2", singleCache.get("key-2"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectZeroMaxEntries() {
        new SessionCache(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeMaxEntries() {
        new SessionCache(-1);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullCacheKey() {
        cache.put(null, "data");
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullJsonResult() {
        cache.put("key", null);
    }

    @Test
    public void shouldReturnCorrectSize_afterOperations() {
        assertEquals(0, cache.size());
        cache.put("key-1", "data-1");
        assertEquals(1, cache.size());
        cache.put("key-2", "data-2");
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    public void shouldUseDefaultMaxEntries() {
        assertEquals(SessionCache.DEFAULT_MAX_ENTRIES, cache.getMaxEntries());
    }
}
