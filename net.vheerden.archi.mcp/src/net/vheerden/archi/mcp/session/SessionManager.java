package net.vheerden.archi.mcp.session;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.ModelChangeListener;
import net.vheerden.archi.mcp.model.ModelVersionTracker;
import net.vheerden.archi.mcp.response.FieldSelector;

/**
 * Manages session-scoped filters for MCP conversations (Story 5.1 FR15, Story 5.2 FR17/FR18).
 *
 * <p>Each MCP session can have persistent type/layer filters and field selection
 * preferences (preset and exclude list) that apply to subsequent queries.
 * Filters are stored in a thread-safe map keyed by session ID.</p>
 *
 * <p>Also manages per-session query result caches (Story 5.4 FR38).
 * Each session gets a {@link SessionCache} instance for storing
 * JSON results keyed by command + effective parameters.</p>
 *
 * <p>Implements {@link ModelChangeListener} to clear all sessions when the
 * active ArchiMate model changes.</p>
 *
 * <p><strong>Thread safety:</strong> All operations use {@link ConcurrentHashMap}
 * with atomic {@code compute()} for safe concurrent access from Jetty threads.</p>
 */
public class SessionManager implements ModelChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    /** Default session ID used when exchange is null (testing) or has no session. */
    public static final String DEFAULT_SESSION_ID = "default";

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionCache> sessionCaches = new ConcurrentHashMap<>();
    private final ModelVersionTracker versionTracker = new ModelVersionTracker();
    private final Set<String> validTypes;
    private final Set<String> validLayers;

    /**
     * Immutable session state holding type/layer filters and field selection preferences.
     *
     * @param typeFilter    the ArchiMate element type filter, or null if not set
     * @param layerFilter   the ArchiMate layer filter, or null if not set
     * @param fieldsPreset  the field verbosity preset ("minimal"/"standard"/"full"), or null
     * @param excludeFields immutable set of field names to exclude, or null
     * @param lastAccessed  when this session was last accessed
     */
    public record SessionState(String typeFilter, String layerFilter,
                                String fieldsPreset, Set<String> excludeFields,
                                Instant lastAccessed) {}

    /**
     * Creates a SessionManager with validation sets for type and layer filters.
     *
     * @param validTypes  valid ArchiMate element type names
     * @param validLayers valid ArchiMate layer names
     */
    public SessionManager(Set<String> validTypes, Set<String> validLayers) {
        this.validTypes = Set.copyOf(validTypes);
        this.validLayers = Set.copyOf(validLayers);
    }

    /**
     * Sets or updates session filters. Each parameter is independently optional;
     * non-null values update that filter, null values preserve existing values.
     *
     * @param sessionId the session identifier
     * @param type      the element type filter to set, or null to keep existing
     * @param layer     the layer filter to set, or null to keep existing
     * @return the updated session state
     * @throws IllegalArgumentException if type or layer is invalid
     */
    public SessionState setSessionFilter(String sessionId, String type, String layer) {
        return setSessionFilter(sessionId, type, layer, null, null);
    }

    /**
     * Sets or updates session filters including field selection preferences.
     * Each parameter is independently optional; non-null values update that setting,
     * null values preserve existing values.
     *
     * @param sessionId     the session identifier
     * @param type          the element type filter to set, or null to keep existing
     * @param layer         the layer filter to set, or null to keep existing
     * @param fieldsPreset  the field preset ("minimal"/"standard"/"full"), or null to keep existing
     * @param excludeFields field names to exclude, or null to keep existing
     * @return the updated session state
     * @throws IllegalArgumentException if type, layer, fieldsPreset, or excludeFields is invalid
     */
    public SessionState setSessionFilter(String sessionId, String type, String layer,
                                          String fieldsPreset, Set<String> excludeFields) {
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        if (type != null && !validTypes.contains(type)) {
            throw new IllegalArgumentException("Invalid element type: '" + type + "'");
        }
        if (layer != null && !validLayers.contains(layer)) {
            throw new IllegalArgumentException("Invalid layer: '" + layer + "'");
        }
        if (fieldsPreset != null && FieldSelector.FieldPreset.fromString(fieldsPreset).isEmpty()) {
            throw new IllegalArgumentException("Invalid fields preset: '" + fieldsPreset
                    + "'. Valid presets: minimal, standard, full");
        }
        if (excludeFields != null) {
            for (String field : excludeFields) {
                if (!FieldSelector.VALID_EXCLUDE_FIELDS.contains(field)) {
                    throw new IllegalArgumentException("Invalid exclude field: '" + field
                            + "'. Valid fields: " + String.join(", ",
                                    FieldSelector.VALID_EXCLUDE_FIELDS.stream().sorted().toList()));
                }
            }
        }

        final String finalSessionId = sessionId;
        final String finalType = type;
        final String finalLayer = layer;
        final String finalFieldsPreset = fieldsPreset;
        final Set<String> finalExcludeFields = excludeFields != null ? Set.copyOf(excludeFields) : null;
        return sessions.compute(sessionId, (key, existing) -> {
                if (existing == null) {
                    logger.info("Session created: {}", finalSessionId);
                }
                return new SessionState(
                        finalType != null ? finalType : (existing != null ? existing.typeFilter() : null),
                        finalLayer != null ? finalLayer : (existing != null ? existing.layerFilter() : null),
                        finalFieldsPreset != null ? finalFieldsPreset : (existing != null ? existing.fieldsPreset() : null),
                        finalExcludeFields != null ? finalExcludeFields : (existing != null ? existing.excludeFields() : null),
                        Instant.now()
                );
        });
    }

    /**
     * Clears all filters for a session.
     *
     * @param sessionId the session identifier
     */
    public void clearSessionFilter(String sessionId) {
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        sessions.remove(sessionId);
        logger.info("Session filters cleared: {}", sessionId);
    }

    /**
     * Retrieves the current session state, updating lastAccessed.
     *
     * @param sessionId the session identifier
     * @return the session state, or empty if no filters are set
     */
    public Optional<SessionState> getSessionFilter(String sessionId) {
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        // Atomically update lastAccessed and return the updated state.
        // compute() returns null if the key was absent, which we map to Optional.empty().
        SessionState updated = sessions.computeIfPresent(sessionId, (key, existing) ->
                new SessionState(existing.typeFilter(), existing.layerFilter(),
                        existing.fieldsPreset(), existing.excludeFields(), Instant.now()));
        return Optional.ofNullable(updated);
    }

    /**
     * Returns the effective type filter: per-query overrides session.
     *
     * @param sessionId    the session identifier
     * @param perQueryType the per-query type filter, or null
     * @return the effective type filter, or null if neither is set
     */
    public String getEffectiveType(String sessionId, String perQueryType) {
        if (perQueryType != null) {
            return perQueryType;
        }
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        SessionState state = sessions.get(sessionId);
        return state != null ? state.typeFilter() : null;
    }

    /**
     * Returns the effective layer filter: per-query overrides session.
     *
     * @param sessionId     the session identifier
     * @param perQueryLayer the per-query layer filter, or null
     * @return the effective layer filter, or null if neither is set
     */
    public String getEffectiveLayer(String sessionId, String perQueryLayer) {
        if (perQueryLayer != null) {
            return perQueryLayer;
        }
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        SessionState state = sessions.get(sessionId);
        return state != null ? state.layerFilter() : null;
    }

    /**
     * Returns the effective fields preset: per-query overrides session.
     *
     * @param sessionId      the session identifier
     * @param perQueryPreset the per-query fields preset, or null
     * @return the effective fields preset string, or null if neither is set
     */
    public String getEffectiveFieldsPreset(String sessionId, String perQueryPreset) {
        if (perQueryPreset != null) {
            return perQueryPreset;
        }
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        SessionState state = sessions.get(sessionId);
        return state != null ? state.fieldsPreset() : null;
    }

    /**
     * Returns the effective exclude fields: per-query fully replaces session (not merged).
     *
     * @param sessionId       the session identifier
     * @param perQueryExclude the per-query exclude field list, or null
     * @return the effective exclude fields set, or null if neither is set
     */
    public Set<String> getEffectiveExcludeFields(String sessionId, List<String> perQueryExclude) {
        if (perQueryExclude != null) {
            return Set.copyOf(perQueryExclude);
        }
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        SessionState state = sessions.get(sessionId);
        return state != null ? state.excludeFields() : null;
    }

    /**
     * Returns the set of valid element types for filter validation.
     *
     * @return unmodifiable set of valid type names
     */
    public Set<String> getValidTypes() {
        return validTypes;
    }

    /**
     * Returns the set of valid layers for filter validation.
     *
     * @return unmodifiable set of valid layer names
     */
    public Set<String> getValidLayers() {
        return validLayers;
    }

    /**
     * Extracts session ID from an MCP exchange object.
     * Falls back to {@link #DEFAULT_SESSION_ID} when exchange is null (tests)
     * or session ID is unavailable.
     *
     * @param exchange the MCP exchange, may be null
     * @return a stable session identifier
     */
    public static String extractSessionId(io.modelcontextprotocol.server.McpSyncServerExchange exchange) {
        if (exchange == null) {
            return DEFAULT_SESSION_ID;
        }
        String sessionId = exchange.sessionId();
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : DEFAULT_SESSION_ID;
    }

    // ---- Session caching (Story 5.4) ----

    /**
     * Gets a cached query result for a session.
     *
     * @param sessionId the session identifier
     * @param cacheKey  the cache key encoding command + effective parameters
     * @return the cached JSON result string, or {@code null} if not cached
     */
    public String getCacheEntry(String sessionId, String cacheKey) {
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        SessionCache cache = sessionCaches.get(sessionId);
        return cache != null ? cache.get(cacheKey) : null;
    }

    /**
     * Stores a query result in the session cache.
     *
     * @param sessionId  the session identifier
     * @param cacheKey   the cache key encoding command + effective parameters
     * @param jsonResult the JSON result string to cache
     */
    public void putCacheEntry(String sessionId, String cacheKey, String jsonResult) {
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        sessionCaches.computeIfAbsent(sessionId, k -> new SessionCache())
                .put(cacheKey, jsonResult);
    }

    /**
     * Invalidates (clears) a single session's cache.
     *
     * @param sessionId the session identifier
     */
    public void invalidateSessionCache(String sessionId) {
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        SessionCache cache = sessionCaches.remove(sessionId);
        if (cache != null) {
            cache.clear();
            logger.debug("Session cache invalidated for session: {}", sessionId);
        }
    }

    // ---- Model version tracking (Story 5.3) ----

    /**
     * Checks whether the model version has changed since this session's last query.
     * Delegates to {@link ModelVersionTracker#checkAndUpdateVersion(String, String)}.
     *
     * @param sessionId      the MCP session identifier
     * @param currentVersion the current model version string
     * @return {@code true} if the version changed since the session's last check
     */
    public boolean checkModelVersionChanged(String sessionId, String currentVersion) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(currentVersion, "currentVersion must not be null");
        return versionTracker.checkAndUpdateVersion(sessionId, currentVersion);
    }

    // ---- ModelChangeListener ----

    @Override
    public void onModelChanged(String modelName, String modelId) {
        sessions.clear();
        versionTracker.clearAll();
        sessionCaches.clear();
        logger.info("All session state cleared due to model change (new model: {})", modelName);
    }

    /**
     * Disposes all session state. Called on server stop.
     */
    public void dispose() {
        // Snapshot keys before clearing for accurate count (avoids race between size() and clear())
        int sessionCount = sessions.keySet().toArray().length;
        sessions.clear();
        versionTracker.clearAll();
        sessionCaches.clear();
        logger.info("SessionManager disposed ({} sessions invalidated)", sessionCount);
    }
}
