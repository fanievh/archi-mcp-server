package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link ModelVersionTracker}.
 *
 * <p>Pure Java — no MCP SDK or OSGi runtime required.</p>
 */
public class ModelVersionTrackerTest {

    private ModelVersionTracker tracker;

    @Before
    public void setUp() {
        tracker = new ModelVersionTracker();
    }

    // ---- Task 1.2: Change detection tests ----

    @Test
    public void shouldReturnFalse_whenFirstCallForSession() {
        boolean changed = tracker.checkAndUpdateVersion("session-1", "1");

        assertFalse("First call for a session should return false (no prior version)", changed);
    }

    @Test
    public void shouldReturnFalse_whenVersionUnchanged() {
        tracker.checkAndUpdateVersion("session-1", "5");

        boolean changed = tracker.checkAndUpdateVersion("session-1", "5");

        assertFalse("Same version should return false", changed);
    }

    @Test
    public void shouldReturnTrue_whenVersionChanged() {
        tracker.checkAndUpdateVersion("session-1", "1");

        boolean changed = tracker.checkAndUpdateVersion("session-1", "2");

        assertTrue("Different version should return true", changed);
    }

    @Test
    public void shouldReturnFalse_afterVersionChangeAcknowledged() {
        tracker.checkAndUpdateVersion("session-1", "1");
        tracker.checkAndUpdateVersion("session-1", "2"); // true — change detected

        boolean changed = tracker.checkAndUpdateVersion("session-1", "2");

        assertFalse("Subsequent call with same version should return false", changed);
    }

    @Test
    public void shouldDetectMultipleVersionChanges() {
        tracker.checkAndUpdateVersion("session-1", "1");

        assertTrue(tracker.checkAndUpdateVersion("session-1", "2"));
        assertTrue(tracker.checkAndUpdateVersion("session-1", "3"));
        assertFalse(tracker.checkAndUpdateVersion("session-1", "3"));
        assertTrue(tracker.checkAndUpdateVersion("session-1", "4"));
    }

    // ---- Task 1.2: Multi-session isolation tests ----

    @Test
    public void shouldTrackSessionsIndependently() {
        tracker.checkAndUpdateVersion("session-A", "1");
        tracker.checkAndUpdateVersion("session-B", "1");

        // Change version — only session-A should see it as changed
        boolean changedA = tracker.checkAndUpdateVersion("session-A", "2");
        boolean changedB = tracker.checkAndUpdateVersion("session-B", "1");

        assertTrue("Session A should detect version change", changedA);
        assertFalse("Session B should not detect change (same version)", changedB);
    }

    @Test
    public void shouldNotAffectOtherSessions_whenOneSessionCleared() {
        tracker.checkAndUpdateVersion("session-A", "1");
        tracker.checkAndUpdateVersion("session-B", "1");

        tracker.clearSession("session-A");

        // Session A cleared: first call returns false
        assertFalse(tracker.checkAndUpdateVersion("session-A", "5"));
        // Session B unaffected: same version returns false
        assertFalse(tracker.checkAndUpdateVersion("session-B", "1"));
    }

    // ---- Task 1.2: clearSession tests ----

    @Test
    public void shouldResetSessionState_whenCleared() {
        tracker.checkAndUpdateVersion("session-1", "1");
        tracker.clearSession("session-1");

        // After clear, next call is treated as first call — returns false
        boolean changed = tracker.checkAndUpdateVersion("session-1", "99");

        assertFalse("First call after clear should return false", changed);
    }

    @Test
    public void shouldHandleClearOfNonexistentSession_gracefully() {
        // Should not throw
        tracker.clearSession("nonexistent-session");
    }

    // ---- Task 1.2: clearAll tests ----

    @Test
    public void shouldClearAllSessions_whenClearAllCalled() {
        tracker.checkAndUpdateVersion("session-A", "1");
        tracker.checkAndUpdateVersion("session-B", "2");
        tracker.checkAndUpdateVersion("session-C", "3");

        tracker.clearAll();

        // All sessions reset — first call returns false
        assertFalse(tracker.checkAndUpdateVersion("session-A", "100"));
        assertFalse(tracker.checkAndUpdateVersion("session-B", "100"));
        assertFalse(tracker.checkAndUpdateVersion("session-C", "100"));
    }

    // ---- Code review fix M1: Null validation tests ----

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullSessionId_inCheckAndUpdateVersion() {
        tracker.checkAndUpdateVersion(null, "1");
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullCurrentVersion_inCheckAndUpdateVersion() {
        tracker.checkAndUpdateVersion("session-1", null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullSessionId_inClearSession() {
        tracker.clearSession(null);
    }
}
