package net.vheerden.archi.mcp.model;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-session lazy version comparison for model change detection (Story 5.3 FR28).
 *
 * <p>Tracks the last-seen model version for each MCP session. When a handler
 * calls {@link #checkAndUpdateVersion(String, String)}, the tracker compares
 * the current version against the session's previously stored version and
 * atomically updates the stored value.</p>
 *
 * <p><strong>Design rationale:</strong> Lazy detection at query time is simpler
 * than proactive notification. The {@code versionCounter} in
 * {@code ArchiModelAccessorImpl} is already incremented on every
 * {@code PROPERTY_ECORE_EVENT}, so the version is always current. This class
 * only needs to compare "what did this session last see?" vs "what is current?".</p>
 *
 * <p><strong>Thread safety:</strong> All operations use {@link ConcurrentHashMap}
 * — {@code put()} is atomic, safe for concurrent handler calls from Jetty threads.</p>
 */
public class ModelVersionTracker {

    private static final Logger logger = LoggerFactory.getLogger(ModelVersionTracker.class);

    private final ConcurrentHashMap<String, String> sessionVersions = new ConcurrentHashMap<>();

    /**
     * Checks whether the model version has changed since this session's last query.
     * Also atomically updates the stored version for this session.
     *
     * <p>First call for a session always returns {@code false} (no prior version to compare).</p>
     *
     * @param sessionId      the MCP session identifier
     * @param currentVersion the current model version string
     * @return {@code true} if the version changed since the session's last check
     */
    public boolean checkAndUpdateVersion(String sessionId, String currentVersion) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(currentVersion, "currentVersion must not be null");
        String previousVersion = sessionVersions.put(sessionId, currentVersion);
        boolean changed = previousVersion != null && !previousVersion.equals(currentVersion);
        if (changed) {
            logger.debug("Model version changed for session {}: {} -> {}", sessionId,
                    previousVersion, currentVersion);
        }
        return changed;
    }

    /**
     * Clears the stored version for a specific session.
     *
     * @param sessionId the MCP session identifier
     */
    public void clearSession(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        sessionVersions.remove(sessionId);
    }

    /**
     * Clears all stored session versions. Called on model lifecycle change
     * (open/close/switch) — clean slate for all sessions.
     */
    public void clearAll() {
        sessionVersions.clear();
        logger.debug("All session versions cleared");
    }
}
