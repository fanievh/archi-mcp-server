package net.vheerden.archi.mcp.session;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link SessionManager}.
 *
 * <p>Pure Java — no MCP SDK or OSGi runtime required.</p>
 */
public class SessionManagerTest {

    private static final Set<String> VALID_TYPES = Set.of(
            "ApplicationComponent", "BusinessProcess", "Node");
    private static final Set<String> VALID_LAYERS = Set.of(
            "Business", "Application", "Technology");

    private SessionManager sessionManager;

    @Before
    public void setUp() {
        sessionManager = new SessionManager(VALID_TYPES, VALID_LAYERS);
    }

    // ---- Task 10.1 ----
    @Test
    public void shouldStoreSessionFilter_whenTypeProvided() {
        SessionManager.SessionState state = sessionManager.setSessionFilter(
                "session-1", "ApplicationComponent", null);

        assertNotNull(state);
        assertEquals("ApplicationComponent", state.typeFilter());
        assertNull(state.layerFilter());
        assertNotNull(state.lastAccessed());
    }

    // ---- Task 10.2 ----
    @Test
    public void shouldStoreSessionFilter_whenLayerProvided() {
        SessionManager.SessionState state = sessionManager.setSessionFilter(
                "session-1", null, "Application");

        assertNotNull(state);
        assertNull(state.typeFilter());
        assertEquals("Application", state.layerFilter());
    }

    // ---- Task 10.3 ----
    @Test
    public void shouldStoreSessionFilter_whenBothTypeAndLayer() {
        SessionManager.SessionState state = sessionManager.setSessionFilter(
                "session-1", "BusinessProcess", "Business");

        assertNotNull(state);
        assertEquals("BusinessProcess", state.typeFilter());
        assertEquals("Business", state.layerFilter());
    }

    // ---- Task 10.4 ----
    @Test
    public void shouldClearSessionFilter_whenClearCalled() {
        sessionManager.setSessionFilter("session-1", "ApplicationComponent", "Application");
        sessionManager.clearSessionFilter("session-1");

        Optional<SessionManager.SessionState> result = sessionManager.getSessionFilter("session-1");
        assertFalse(result.isPresent());
    }

    // ---- Task 10.5 ----
    @Test
    public void shouldReturnEmpty_whenNoFilterSet() {
        Optional<SessionManager.SessionState> result = sessionManager.getSessionFilter("unknown-session");
        assertFalse(result.isPresent());
    }

    // ---- Task 10.6 ----
    @Test
    public void shouldUpdateExistingFilter_whenSetCalledAgain() {
        sessionManager.setSessionFilter("session-1", "ApplicationComponent", "Application");
        SessionManager.SessionState updated = sessionManager.setSessionFilter(
                "session-1", "BusinessProcess", null);

        // Type updated, layer preserved from previous
        assertEquals("BusinessProcess", updated.typeFilter());
        assertEquals("Application", updated.layerFilter());
    }

    // ---- Task 10.7 ----
    @Test
    public void shouldMaintainSeparateSessions_whenDifferentSessionIds() {
        sessionManager.setSessionFilter("session-A", "ApplicationComponent", null);
        sessionManager.setSessionFilter("session-B", "BusinessProcess", "Business");

        Optional<SessionManager.SessionState> stateA = sessionManager.getSessionFilter("session-A");
        Optional<SessionManager.SessionState> stateB = sessionManager.getSessionFilter("session-B");

        assertTrue(stateA.isPresent());
        assertEquals("ApplicationComponent", stateA.get().typeFilter());
        assertNull(stateA.get().layerFilter());

        assertTrue(stateB.isPresent());
        assertEquals("BusinessProcess", stateB.get().typeFilter());
        assertEquals("Business", stateB.get().layerFilter());
    }

    // ---- Task 10.8 ----
    @Test
    public void shouldReturnPerQueryType_whenBothSessionAndPerQueryProvided() {
        sessionManager.setSessionFilter("session-1", "ApplicationComponent", null);

        String effective = sessionManager.getEffectiveType("session-1", "BusinessProcess");
        assertEquals("BusinessProcess", effective);
    }

    // ---- Task 10.9 ----
    @Test
    public void shouldReturnSessionType_whenPerQueryIsNull() {
        sessionManager.setSessionFilter("session-1", "ApplicationComponent", null);

        String effective = sessionManager.getEffectiveType("session-1", null);
        assertEquals("ApplicationComponent", effective);
    }

    // ---- Task 10.10 ----
    @Test
    public void shouldReturnNull_whenNeitherSessionNorPerQueryProvided() {
        String effective = sessionManager.getEffectiveType("no-such-session", null);
        assertNull(effective);
    }

    // ---- Task 10.8/10.9 for Layer ----
    @Test
    public void shouldReturnPerQueryLayer_whenBothSessionAndPerQueryProvided() {
        sessionManager.setSessionFilter("session-1", null, "Application");

        String effective = sessionManager.getEffectiveLayer("session-1", "Technology");
        assertEquals("Technology", effective);
    }

    @Test
    public void shouldReturnSessionLayer_whenPerQueryIsNull() {
        sessionManager.setSessionFilter("session-1", null, "Application");

        String effective = sessionManager.getEffectiveLayer("session-1", null);
        assertEquals("Application", effective);
    }

    @Test
    public void shouldReturnNullLayer_whenNeitherSessionNorPerQueryProvided() {
        String effective = sessionManager.getEffectiveLayer("no-such-session", null);
        assertNull(effective);
    }

    // ---- Task 10.11 ----
    @Test
    public void shouldClearAllSessions_whenModelChanges() {
        sessionManager.setSessionFilter("session-A", "ApplicationComponent", null);
        sessionManager.setSessionFilter("session-B", "BusinessProcess", "Business");

        sessionManager.onModelChanged("New Model", "model-id-123");

        assertFalse(sessionManager.getSessionFilter("session-A").isPresent());
        assertFalse(sessionManager.getSessionFilter("session-B").isPresent());
    }

    // ---- Task 10.12 ----
    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidType_whenSettingFilter() {
        sessionManager.setSessionFilter("session-1", "FakeType", null);
    }

    // ---- Task 10.13 ----
    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidLayer_whenSettingFilter() {
        sessionManager.setSessionFilter("session-1", null, "FakeLayer");
    }

    // ---- Task 10.14 ----
    @Test
    public void shouldHandleNullSessionId_gracefully() {
        // null session ID should use default session
        sessionManager.setSessionFilter(null, "ApplicationComponent", null);

        Optional<SessionManager.SessionState> state = sessionManager.getSessionFilter(null);
        assertTrue(state.isPresent());
        assertEquals("ApplicationComponent", state.get().typeFilter());

        String effective = sessionManager.getEffectiveType(null, null);
        assertEquals("ApplicationComponent", effective);
    }

    // ---- extractSessionId tests ----
    @Test
    public void shouldReturnDefaultSessionId_whenExchangeIsNull() {
        assertEquals(SessionManager.DEFAULT_SESSION_ID,
                SessionManager.extractSessionId(null));
    }

    // ---- dispose test ----
    @Test
    public void shouldClearAllSessions_whenDisposed() {
        sessionManager.setSessionFilter("session-1", "ApplicationComponent", null);
        sessionManager.dispose();
        assertFalse(sessionManager.getSessionFilter("session-1").isPresent());
    }

    // ---- Story 5.2: Field selection extension tests ----

    @Test
    public void shouldStoreFieldsPreset_whenFieldsProvided() {
        SessionManager.SessionState state = sessionManager.setSessionFilter(
                "session-1", null, null, "minimal", null);

        assertNotNull(state);
        assertEquals("minimal", state.fieldsPreset());
        assertNull(state.excludeFields());
    }

    @Test
    public void shouldStoreExcludeFields_whenExcludeProvided() {
        SessionManager.SessionState state = sessionManager.setSessionFilter(
                "session-1", null, null, null, Set.of("documentation", "properties"));

        assertNotNull(state);
        assertNull(state.fieldsPreset());
        assertNotNull(state.excludeFields());
        assertTrue(state.excludeFields().contains("documentation"));
        assertTrue(state.excludeFields().contains("properties"));
    }

    @Test
    public void shouldClearFieldPreferences_whenClearCalled() {
        sessionManager.setSessionFilter("session-1", "ApplicationComponent", null, "minimal",
                Set.of("documentation"));
        sessionManager.clearSessionFilter("session-1");

        Optional<SessionManager.SessionState> result = sessionManager.getSessionFilter("session-1");
        assertFalse(result.isPresent());
    }

    @Test
    public void shouldReturnPerQueryPreset_whenBothSessionAndPerQueryProvided() {
        sessionManager.setSessionFilter("session-1", null, null, "minimal", null);

        String effective = sessionManager.getEffectiveFieldsPreset("session-1", "full");
        assertEquals("full", effective);
    }

    @Test
    public void shouldReturnSessionPreset_whenPerQueryIsNull() {
        sessionManager.setSessionFilter("session-1", null, null, "minimal", null);

        String effective = sessionManager.getEffectiveFieldsPreset("session-1", null);
        assertEquals("minimal", effective);
    }

    @Test
    public void shouldReturnPerQueryExclude_whenBothSessionAndPerQueryProvided() {
        sessionManager.setSessionFilter("session-1", null, null, null,
                Set.of("documentation"));

        Set<String> effective = sessionManager.getEffectiveExcludeFields(
                "session-1", List.of("properties"));
        assertEquals(Set.of("properties"), effective); // Full replacement, not merge
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidFieldsPreset_whenInvalidValue() {
        sessionManager.setSessionFilter("session-1", null, null, "compact", null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidExcludeField_whenInvalidFieldName() {
        sessionManager.setSessionFilter("session-1", null, null, null,
                Set.of("invalidField"));
    }

    @Test
    public void shouldPreserveExistingTypeLayerFilters_whenSettingFieldPreferences() {
        sessionManager.setSessionFilter("session-1", "ApplicationComponent", "Application");
        sessionManager.setSessionFilter("session-1", null, null, "minimal",
                Set.of("documentation"));

        Optional<SessionManager.SessionState> state = sessionManager.getSessionFilter("session-1");
        assertTrue(state.isPresent());
        assertEquals("ApplicationComponent", state.get().typeFilter());
        assertEquals("Application", state.get().layerFilter());
        assertEquals("minimal", state.get().fieldsPreset());
        assertTrue(state.get().excludeFields().contains("documentation"));
    }

    @Test
    public void shouldReturnNullPreset_whenNoSessionFieldsSet() {
        String effective = sessionManager.getEffectiveFieldsPreset("no-such-session", null);
        assertNull(effective);
    }

    @Test
    public void shouldReturnNullExclude_whenNoSessionExcludeSet() {
        Set<String> effective = sessionManager.getEffectiveExcludeFields("no-such-session", null);
        assertNull(effective);
    }

    // ---- Story 5.3: Model version tracking integration tests ----

    @Test
    public void shouldReturnFalse_whenFirstVersionCheck() {
        boolean changed = sessionManager.checkModelVersionChanged("session-1", "1");
        assertFalse("First version check should return false", changed);
    }

    @Test
    public void shouldReturnTrue_whenModelVersionChanges() {
        sessionManager.checkModelVersionChanged("session-1", "1");
        boolean changed = sessionManager.checkModelVersionChanged("session-1", "2");
        assertTrue("Changed version should return true", changed);
    }

    @Test
    public void shouldReturnFalse_whenModelVersionUnchanged() {
        sessionManager.checkModelVersionChanged("session-1", "5");
        boolean changed = sessionManager.checkModelVersionChanged("session-1", "5");
        assertFalse("Same version should return false", changed);
    }

    @Test
    public void shouldClearVersionTracking_whenModelChanges() {
        sessionManager.checkModelVersionChanged("session-1", "1");
        sessionManager.onModelChanged("New Model", "model-id-123");

        // After model change clearAll, first call returns false (no prior version)
        boolean changed = sessionManager.checkModelVersionChanged("session-1", "99");
        assertFalse("First check after model change should return false", changed);
    }

    @Test
    public void shouldClearVersionTracking_whenDisposed() {
        sessionManager.checkModelVersionChanged("session-1", "1");
        sessionManager.dispose();

        // After dispose, first call returns false
        boolean changed = sessionManager.checkModelVersionChanged("session-1", "99");
        assertFalse("First check after dispose should return false", changed);
    }

    // ---- Story 5.4: Session caching integration tests ----

    @Test
    public void shouldReturnNull_whenNoCacheEntryExists() {
        assertNull(sessionManager.getCacheEntry("session-1", "search|query:test"));
    }

    @Test
    public void shouldStoreCacheEntry_andReturnOnGet() {
        sessionManager.putCacheEntry("session-1", "search|query:test", "{\"result\":\"data\"}");
        assertEquals("{\"result\":\"data\"}",
                sessionManager.getCacheEntry("session-1", "search|query:test"));
    }

    @Test
    public void shouldMaintainSeparateCachesPerSession() {
        sessionManager.putCacheEntry("session-A", "key-1", "data-A");
        sessionManager.putCacheEntry("session-B", "key-1", "data-B");

        assertEquals("data-A", sessionManager.getCacheEntry("session-A", "key-1"));
        assertEquals("data-B", sessionManager.getCacheEntry("session-B", "key-1"));
    }

    @Test
    public void shouldInvalidateSessionCache_whenCalled() {
        sessionManager.putCacheEntry("session-1", "key-1", "data-1");
        sessionManager.putCacheEntry("session-1", "key-2", "data-2");

        sessionManager.invalidateSessionCache("session-1");

        assertNull(sessionManager.getCacheEntry("session-1", "key-1"));
        assertNull(sessionManager.getCacheEntry("session-1", "key-2"));
    }

    @Test
    public void shouldNotAffectOtherSessions_whenOneInvalidated() {
        sessionManager.putCacheEntry("session-A", "key-1", "data-A");
        sessionManager.putCacheEntry("session-B", "key-1", "data-B");

        sessionManager.invalidateSessionCache("session-A");

        assertNull(sessionManager.getCacheEntry("session-A", "key-1"));
        assertEquals("data-B", sessionManager.getCacheEntry("session-B", "key-1"));
    }

    @Test
    public void shouldClearAllCaches_whenModelChanges() {
        sessionManager.putCacheEntry("session-A", "key-1", "data-A");
        sessionManager.putCacheEntry("session-B", "key-1", "data-B");

        sessionManager.onModelChanged("New Model", "model-id-123");

        assertNull(sessionManager.getCacheEntry("session-A", "key-1"));
        assertNull(sessionManager.getCacheEntry("session-B", "key-1"));
    }

    @Test
    public void shouldClearAllCaches_whenDisposed() {
        sessionManager.putCacheEntry("session-1", "key-1", "data-1");
        sessionManager.dispose();

        assertNull(sessionManager.getCacheEntry("session-1", "key-1"));
    }

    @Test
    public void shouldHandleNullSessionId_inCacheOperations() {
        sessionManager.putCacheEntry(null, "key-1", "data-1");
        assertEquals("data-1", sessionManager.getCacheEntry(null, "key-1"));
        sessionManager.invalidateSessionCache(null);
        assertNull(sessionManager.getCacheEntry(null, "key-1"));
    }

    @Test
    public void shouldNotThrow_whenInvalidatingNonexistentSession() {
        sessionManager.invalidateSessionCache("no-such-session");
        // No exception = pass
    }
}
