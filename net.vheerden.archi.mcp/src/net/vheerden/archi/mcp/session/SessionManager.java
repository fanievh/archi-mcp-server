package net.vheerden.archi.mcp.session;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.CallerText;
import net.vheerden.archi.mcp.model.ModelChangeListener;
import net.vheerden.archi.mcp.model.ModelVersionTracker;
import net.vheerden.archi.mcp.response.FieldSelector;

/**
 * Manages session-scoped filters for MCP conversations.
 *
 * <p>Each MCP session can have persistent type/layer filters and field selection
 * preferences (preset and exclude list) that apply to subsequent queries.
 * Filters are stored in a thread-safe map keyed by session ID.</p>
 *
 * <p>Also manages per-session query result caches.
 * Each session gets a {@link SessionCache} instance for storing
 * JSON results keyed by command + effective parameters.</p>
 *
 * <p>Caches and version tracking are keyed by the model instance a call acts on, so one session can
 * read several models without one model's answers being served for another. A model change never
 * clears anything: filters are a client's preference, and another session opening or closing a model
 * must not reconfigure this one. Closing a model drops only the entries keyed by that instance.</p>
 *
 * <p><strong>Thread safety:</strong> All operations use {@link ConcurrentHashMap}
 * with atomic {@code compute()} for safe concurrent access from Jetty threads.</p>
 */
public class SessionManager implements ModelChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    /** Default session ID used when exchange is null (testing) or has no session. */
    public static final String DEFAULT_SESSION_ID = "default";

    /**
     * Idle time-to-live for per-session state. A session whose last
     * request — filter read/write or cache read/write — is older than this is evicted from
     * {@link #sessions}, {@link #sessionCaches}, and {@link #lastAccess} on the next opportunistic
     * sweep. 24h is generous for a single-user desktop conversation: every access refreshes the
     * timestamp ({@link #touch}/{@link #touchIfTracked}), so an actively-queried session is never
     * evicted mid-conversation, yet idle state cannot accumulate (slow leak) and a client that
     * fabricates many session ids cannot grow the heap without bound. Mirrors the opportunistic-sweep
     * idiom of {@code MutationDispatcher.PROPOSAL_TTL} — deliberately no background/timer thread
     * (no OSGi shutdown burden, no SWT-thread hazard).
     */
    static final Duration SESSION_IDLE_TTL = Duration.ofHours(24);

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionCache> sessionCaches = new ConcurrentHashMap<>();
    /**
     * Single source of truth for per-session wall-clock last-access. Keyed independently of
     * {@code sessions}/{@code sessionCaches} so it covers cache-only sessions too: a {@code SessionState}
     * carries its own {@code Instant lastAccessed}, but a session that only ever caches (never sets a
     * filter) has no {@code SessionState}, and {@code SessionCache.CacheEntry.lastAccessed()} is a
     * {@code System.nanoTime()} value (monotonic, not a 24h-comparable wall clock). Every state-creating
     * write ({@code setSessionFilter}/{@code putCacheEntry}) registers a key here; reads refresh an
     * existing key only. The sweep evicts the matching entries from all three maps in lockstep.
     */
    private final ConcurrentHashMap<String, Instant> lastAccess = new ConcurrentHashMap<>();
    /**
     * Wall clock driving the {@link #lastAccess} timestamps and opportunistic sweeps. Defaults to
     * {@code Instant::now}; package-private {@link #setClock} lets a test advance time deterministically so
     * the read-path keep-alive can be proven without sleeping (the package-private
     * {@link #sweepExpired(Instant)} already injects {@code now} directly).
     */
    private volatile Supplier<Instant> clock = Instant::now;
    private final ModelVersionTracker versionTracker = new ModelVersionTracker();
    /** Separates a session id or cache key from the model key it is scoped to. */
    private static final char MODEL_SEPARATOR = '\u0001';
    /**
     * Names the model instance the current call acts on: empty outside a tool call (one shared key),
     * null when the call's model cannot be resolved (nothing is cached for it).
     */
    private volatile Supplier<String> modelKey = () -> "";
    /** Told each session id the idle sweep evicts; null for none. */
    private volatile java.util.function.Consumer<String> evictionListener;
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
     * @throws SessionFilterRefusal if type, layer, fieldsPreset, or excludeFields is invalid, naming which
     */
    public SessionState setSessionFilter(String sessionId, String type, String layer,
                                          String fieldsPreset, Set<String> excludeFields) {
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        if (type != null && !validTypes.contains(type)) {
            throw new SessionFilterRefusal(SessionFilterRefusal.Parameter.TYPE,
                    "Invalid element type: " + CallerText.quoted(type));
        }
        if (layer != null && !validLayers.contains(layer)) {
            throw new SessionFilterRefusal(SessionFilterRefusal.Parameter.LAYER,
                    "Invalid layer: " + CallerText.quoted(layer));
        }
        if (fieldsPreset != null && FieldSelector.FieldPreset.fromString(fieldsPreset).isEmpty()) {
            throw new SessionFilterRefusal(SessionFilterRefusal.Parameter.FIELDS,
                    "Invalid fields preset: " + CallerText.quoted(fieldsPreset)
                    + ". Valid presets: minimal, standard, full");
        }
        if (excludeFields != null) {
            for (String field : excludeFields) {
                if (!FieldSelector.VALID_EXCLUDE_FIELDS.contains(field)) {
                    throw new SessionFilterRefusal(SessionFilterRefusal.Parameter.EXCLUDE,
                            "Invalid exclude field: " + CallerText.quoted(field)
                            + ". Valid fields: " + String.join(", ",
                                    FieldSelector.VALID_EXCLUDE_FIELDS.stream().sorted().toList()));
                }
            }
        }

        final String finalSessionId = sessionId;
        final String finalType = type;
        final String finalLayer = layer;
        final String finalFieldsPreset = fieldsPreset;
        final Set<String> finalExcludeFields = excludeFields != null ? Set.copyOf(excludeFields) : null;
        SessionState result = sessions.compute(sessionId, (key, existing) -> {
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
        // A filter write is a session access — register/refresh last-access, then opportunistically
        // sweep idle sessions (write-path trigger only; mirrors storeProposal→proposal-sweep). Done OUTSIDE
        // the sessions.compute lambda above — sweepExpired mutates `sessions`, so nesting would be unsafe.
        touch(finalSessionId);
        sweepExpired(now());
        return result;
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
        // Keep the last-access clock in lockstep with the maps, so the sweep never logs a session
        // that no longer holds any state as "idle-evicted". Drop the clock entry ONLY when no cache still
        // references this session — a cache-only remnant must stay TTL-tracked, else the sweep (which
        // iterates lastAccess) could never reclaim it.
        if (!sessionCaches.containsKey(sessionId)) {
            lastAccess.remove(sessionId);
        }
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
        touchIfTracked(sessionId); // a filter read refreshes the unified last-access clock too
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
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        touchIfTracked(sessionId); // every effective-filter read refreshes the last-access clock
        if (perQueryType != null) {
            return perQueryType;
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
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        touchIfTracked(sessionId); // every effective-filter read refreshes the last-access clock
        if (perQueryLayer != null) {
            return perQueryLayer;
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
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        touchIfTracked(sessionId); // every effective-filter read refreshes the last-access clock
        if (perQueryPreset != null) {
            return perQueryPreset;
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
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        touchIfTracked(sessionId); // every effective-filter read refreshes the last-access clock
        if (perQueryExclude != null) {
            return Set.copyOf(perQueryExclude);
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

    /**
     * Sets how the model instance the current call acts on is named, so caches and version tracking
     * are scoped to it.
     *
     * @param modelKey the current call's model key: empty for none, null when it cannot be resolved
     */
    public void setModelKeySupplier(Supplier<String> modelKey) {
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey must not be null");
    }

    /**
     * Sets what is told each session id the idle sweep evicts, so state kept for a session elsewhere
     * can go with it.
     *
     * @param listener the listener, or null for none
     */
    public void setSessionEvictionListener(java.util.function.Consumer<String> listener) {
        this.evictionListener = listener;
    }

    /** {@code key} scoped to the current call's model, or null when that model cannot be resolved. */
    private String scoped(String key) {
        String model = modelKey.get();
        return model == null ? null : model.isEmpty() ? key : model + MODEL_SEPARATOR + key;
    }

    // ---- Session caching ----

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
        touchIfTracked(sessionId); // a cache read keeps a cache-only session alive
        String key = scoped(cacheKey);
        SessionCache cache = sessionCaches.get(sessionId);
        return cache != null && key != null ? cache.get(key) : null;
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
        String key = scoped(cacheKey);
        if (key == null) {
            return; // the call's model could not be resolved: nothing to key the answer to
        }
        sessionCaches.computeIfAbsent(sessionId, k -> new SessionCache())
                .put(key, jsonResult);
        // A cache write is a session access (and creates a cache-only session with no SessionState),
        // so register last-access here, then opportunistically sweep idle sessions (write-path trigger).
        touch(sessionId);
        sweepExpired(now());
    }

    /**
     * Invalidates a single session's cache for the model the current call acts on; outside a tool call,
     * the whole of that session's cache.
     *
     * @param sessionId the session identifier
     */
    public void invalidateSessionCache(String sessionId) {
        if (sessionId == null) {
            sessionId = DEFAULT_SESSION_ID;
        }
        String model = modelKey.get();
        if (model != null && !model.isEmpty()) {
            SessionCache scopedCache = sessionCaches.get(sessionId);
            if (scopedCache != null) {
                scopedCache.removeKeysStartingWith(model + MODEL_SEPARATOR);
            }
            return;
        }
        SessionCache cache = sessionCaches.remove(sessionId);
        if (cache != null) {
            cache.clear();
            logger.debug("Session cache invalidated for session: {}", sessionId);
        }
        // Keep the last-access clock in lockstep — drop the clock entry ONLY when no filter state
        // still references this session (a filter-only remnant must stay TTL-tracked for the sweep).
        if (!sessions.containsKey(sessionId)) {
            lastAccess.remove(sessionId);
        }
    }

    // ---- Model version tracking ----

    /**
     * Checks whether the model version has changed since this session's last query.
     * Delegates to {@link ModelVersionTracker#checkAndUpdateVersion(String, String)}.
     *
     * @param sessionId      the MCP session identifier
     * @param currentVersion the current model version string, or {@code null} when no model resolves for
     *                       the call: nothing is compared or recorded, and the call's own model access
     *                       reports why there is none
     * @return {@code true} if the version changed since the session's last check
     */
    public boolean checkModelVersionChanged(String sessionId, String currentVersion) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (currentVersion == null) {
            return false; // no model resolved for this call: nothing to compare, nothing recorded
        }
        String model = modelKey.get();
        String key = model == null || model.isEmpty() ? sessionId : sessionId + MODEL_SEPARATOR + model;
        return versionTracker.checkAndUpdateVersion(key, currentVersion);
    }

    // ---- ModelChangeListener ----

    /**
     * Clears nothing: caches and versions are keyed by model instance, so a different active model is
     * simply a different key, and filters belong to their session whatever another session opens.
     */
    @Override
    public void onModelChanged(String modelName, String modelId) {
        logger.debug("Active model changed to '{}'; session state is kept, keyed by model", modelName);
    }

    /** Drops the cache entries and version tracking keyed by one closed model instance, and nothing else. */
    @Override
    public void onModelClosed(String modelKey) {
        String suffix = MODEL_SEPARATOR + modelKey;
        String prefix = modelKey + MODEL_SEPARATOR;
        versionTracker.clearMatching(key -> key.endsWith(suffix));
        for (SessionCache cache : sessionCaches.values()) {
            cache.removeKeysStartingWith(prefix);
        }
        logger.debug("Session caches and versions for closed model instance {} dropped", modelKey);
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
        lastAccess.clear(); // keep the last-access clock in lockstep with the wholesale clear
        logger.info("SessionManager disposed ({} sessions invalidated)", sessionCount);
    }

    // ---- Idle-TTL eviction ----

    /**
     * Registers/refreshes a session's wall-clock last-access. Called from the state-creating write
     * paths ({@link #setSessionFilter}, {@link #putCacheEntry}) so {@link #lastAccess} keys stay a
     * superset of {@code sessions} ∪ {@code sessionCaches} keys (the sweep iterates {@code lastAccess}).
     */
    private void touch(String sessionId) {
        lastAccess.put(sessionId, now());
    }

    /**
     * Refreshes last-access for an <em>already-tracked</em> session; a no-op for an unknown id. Called
     * from the read paths so an actively-queried session is never evicted mid-conversation, without
     * minting phantom entries for sessions that hold no state (which would reopen the fabricated-id
     * growth vector on the read path, where the sweep is not triggered).
     */
    private void touchIfTracked(String sessionId) {
        lastAccess.computeIfPresent(sessionId, (k, v) -> now());
    }

    /** Current wall-clock instant via the (test-overridable) {@link #clock}. */
    private Instant now() {
        return clock.get();
    }

    /**
     * Evicts every tracked session idle longer than {@link #SESSION_IDLE_TTL} from {@link #sessions},
     * {@link #sessionCaches}, and {@link #lastAccess} together. The {@link #DEFAULT_SESSION_ID default
     * session} is never evicted regardless of idle time. {@code now} is injected so the TTL is
     * unit-testable without sleeping (mirrors {@code MutationContext.isExpired}/{@code sweepExpired}).
     *
     * <p>Pure map work — no EMF/model or SWT access — so it is safe on any Jetty thread. Uses
     * {@link ConcurrentHashMap} iterator removal (no full-map lock, no {@code ConcurrentModificationException});
     * an empty map is a clean no-op. Eviction is surfaced (INFO log of the swept count), never silent.
     * Per the codebase's "one session ↔ one Jetty thread at a time" model the touch/sweep race is benign
     * (worst case: one extra session lingers one cycle, or is swept one cycle early — never corruption).</p>
     *
     * @param now the current instant (injected for testability)
     * @return the session ids that were evicted (empty if none)
     */
    List<String> sweepExpired(Instant now) {
        List<String> evicted = new ArrayList<>();
        var it = lastAccess.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            String sessionId = entry.getKey();
            if (DEFAULT_SESSION_ID.equals(sessionId)) {
                continue; // the shared fallback session is never evicted
            }
            if (isIdle(entry.getValue(), now, SESSION_IDLE_TTL)) {
                it.remove();
                sessions.remove(sessionId);
                SessionCache cache = sessionCaches.remove(sessionId);
                if (cache != null) {
                    cache.clear();
                }
                String scopedPrefix = sessionId + MODEL_SEPARATOR;
                versionTracker.clearMatching(key -> key.equals(sessionId) || key.startsWith(scopedPrefix));
                evicted.add(sessionId);
            }
        }
        if (!evicted.isEmpty()) {
            logger.info("Swept {} idle session(s) past the {}h TTL: {}",
                    evicted.size(), SESSION_IDLE_TTL.toHours(), evicted);
            java.util.function.Consumer<String> listener = evictionListener;
            if (listener != null) {
                for (String sessionId : evicted) {
                    try {
                        listener.accept(sessionId);
                    } catch (RuntimeException e) {
                        logger.warn("Session eviction listener threw (ignored)", e);
                    }
                }
            }
        }
        return evicted;
    }

    /**
     * Public no-arg sweep delegating with the current wall-clock instant, for a non-test caller that
     * wants to force eviction. The request paths trigger {@link #sweepExpired(Instant)} opportunistically;
     * this overload supplies {@link #now()} and returns the evicted ids (so a caller can react).
     *
     * @return the session ids that were evicted (empty if none)
     */
    public List<String> sweepExpired() {
        return sweepExpired(now());
    }

    /**
     * Pure idle predicate mirroring {@code MutationContext.isExpired}. A session is idle when
     * its age ({@code now - lastAccessed}) strictly exceeds {@code ttl}. A null timestamp is treated
     * as not-idle (defensive — a tracked session always has one).
     */
    static boolean isIdle(Instant lastAccessed, Instant now, Duration ttl) {
        if (lastAccessed == null || now == null || ttl == null) {
            return false;
        }
        return Duration.between(lastAccessed, now).compareTo(ttl) > 0;
    }

    // ---- Package-private test accessors ----

    /** @return number of sessions with a tracked wall-clock last-access (the sweep's working set). */
    int trackedSessionCount() {
        return lastAccess.size();
    }

    /** @return number of sessions holding filter/field {@link SessionState}. */
    int sessionStateCount() {
        return sessions.size();
    }

    /** @return number of sessions holding a {@link SessionCache}. */
    int cachedSessionCount() {
        return sessionCaches.size();
    }

    /**
     * Test seam: overrides the wall {@link #clock} so a test can advance time deterministically and prove
     * the read-path keep-alive without sleeping. No production caller sets this.
     */
    void setClock(Supplier<Instant> clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }
}
