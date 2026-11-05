package net.vheerden.archi.mcp.response;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stateless cursor-based pagination utility for MCP tool responses.
 *
 * <p>Cursors are self-describing Base64-encoded JSON tokens that contain
 * all information needed to retrieve the next page of results. No server-side
 * cursor storage is required.</p>
 *
 * <p>Cursor format:</p>
 * <pre>
 * {
 *   "v": 2,
 *   "mv": "modelVersion",
 *   "mid": "modelId",
 *   "mkey": "modelInstanceKey",
 *   "mname": "Model name",
 *   "offset": 50,
 *   "limit": 50,
 *   "total": 250,
 *   "params": { "query": "auth", "type": "ApplicationComponent" }
 * }
 * </pre>
 *
 * <p>{@code mid}, {@code mkey} and {@code mname} name the model instance the cursor pages, so that a
 * cursor carried to a call reading another model, or a later instance of the same one, can be refused
 * rather than answered with that model's page at this cursor's offset. They are absent when the cursor
 * was issued with no model to name, and a blank value decodes as absent: a cursor with no identity is
 * checked by its version alone, never matched against an identity that names nothing. A version-1
 * cursor carries no identity and is refused as unsupported.</p>
 *
 * <p>Thread-safe: uses a single shared {@link ObjectMapper} instance.</p>
 */
public final class PaginationCursor {

    /** Current cursor format version. */
    public static final int CURSOR_VERSION = 2;

    /** Default page size when no limit parameter is provided. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** Maximum allowed page size. */
    public static final int MAX_PAGE_SIZE = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private PaginationCursor() {} // utility class

    /**
     * The model instance a cursor pages.
     *
     * @param id   the model's id
     * @param name the model's name, so a refusal can name it after it has closed
     * @param key  the instance key: a reopened file, or a copy open beside its original, has the
     *             same id and a different key
     */
    public record CursorModel(String id, String name, String key) {
    }

    /**
     * Decoded cursor data record.
     *
     * @param version      cursor format version
     * @param modelVersion model version at cursor creation time
     * @param modelId      id of the model the cursor pages, or null when it names none
     * @param modelKey     instance key of that model, or null when it names none
     * @param modelName    name of that model, or null when it names none
     * @param offset       starting offset for the next page
     * @param limit        page size
     * @param total        total result count at cursor creation time
     * @param params       original query parameters (never null, may be empty)
     */
    public record CursorData(
            int version,
            String modelVersion,
            String modelId,
            String modelKey,
            String modelName,
            int offset,
            int limit,
            int total,
            Map<String, String> params) {
    }

    /**
     * Encodes pagination state into a Base64 cursor token.
     *
     * @param modelVersion current model version
     * @param model        the model instance the cursor pages, or null when there is none to name
     * @param offset       starting offset for the next page
     * @param limit        page size
     * @param total        total result count
     * @param params       original query parameters (may be null or empty)
     * @return Base64-encoded cursor string
     */
    public static String encode(String modelVersion, CursorModel model, int offset, int limit,
                                int total, Map<String, String> params) {
        Map<String, Object> cursorMap = new LinkedHashMap<>();
        cursorMap.put("v", CURSOR_VERSION);
        cursorMap.put("mv", modelVersion);
        if (model != null) {
            cursorMap.put("mid", model.id());
            cursorMap.put("mkey", model.key());
            cursorMap.put("mname", model.name());
        }
        cursorMap.put("offset", offset);
        cursorMap.put("limit", limit);
        cursorMap.put("total", total);
        cursorMap.put("params", params != null ? params : Collections.emptyMap());

        try {
            String json = MAPPER.writeValueAsString(cursorMap);
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode pagination cursor", e);
        }
    }

    /**
     * Decodes a Base64 cursor token into structured cursor data.
     *
     * @param cursorString the Base64-encoded cursor token
     * @return decoded cursor data
     * @throws InvalidCursorException if the cursor is malformed, expired, or invalid
     */
    public static CursorData decode(String cursorString) throws InvalidCursorException {
        if (cursorString == null || cursorString.isBlank()) {
            throw new InvalidCursorException("The pagination cursor is empty");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(cursorString);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("The pagination cursor is malformed");
        }

        Map<String, Object> cursorMap;
        try {
            String json = new String(decoded, StandardCharsets.UTF_8);
            cursorMap = MAPPER.readValue(json, MAP_TYPE_REF);
        } catch (Exception e) {
            throw new InvalidCursorException("The pagination cursor is malformed");
        }

        // Version check
        Object vObj = cursorMap.get("v");
        int version = (vObj instanceof Number n) ? n.intValue() : -1;
        if (version != CURSOR_VERSION) {
            throw new InvalidCursorException("The cursor version is not supported");
        }

        // Model version
        Object mvObj = cursorMap.get("mv");
        if (!(mvObj instanceof String modelVersion) || modelVersion.isBlank()) {
            throw new InvalidCursorException("The pagination cursor is malformed");
        }

        // Model identity (absent, or blank, means none)
        String modelId = optionalText(cursorMap.get("mid"));
        String modelKey = optionalText(cursorMap.get("mkey"));
        String modelName = optionalText(cursorMap.get("mname"));

        // Offset
        Object offsetObj = cursorMap.get("offset");
        int offset = (offsetObj instanceof Number n) ? n.intValue() : -1;
        if (offset < 0) {
            throw new InvalidCursorException("The pagination cursor is malformed");
        }

        // Limit
        Object limitObj = cursorMap.get("limit");
        int limit = (limitObj instanceof Number n) ? n.intValue() : 0;
        if (limit <= 0) {
            throw new InvalidCursorException("The pagination cursor is malformed");
        }

        // Total
        Object totalObj = cursorMap.get("total");
        int total = (totalObj instanceof Number n) ? n.intValue() : 0;
        if (total < 0) {
            throw new InvalidCursorException("The pagination cursor is malformed");
        }

        // Params
        Map<String, String> params = Collections.emptyMap();
        Object paramsObj = cursorMap.get("params");
        if (paramsObj instanceof Map<?, ?> rawMap && !rawMap.isEmpty()) {
            Map<String, String> typedParams = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String value = entry.getValue() != null ? String.valueOf(entry.getValue()) : null;
                typedParams.put(key, value);
            }
            params = Collections.unmodifiableMap(typedParams);
        }

        return new CursorData(version, modelVersion, modelId, modelKey, modelName,
                offset, limit, total, params);
    }

    private static String optionalText(Object value) {
        return (value instanceof String text && !text.isBlank()) ? text : null;
    }

    /**
     * Exception thrown when a cursor token is invalid, malformed, or expired.
     */
    public static class InvalidCursorException extends Exception {
        private static final long serialVersionUID = 1L;

        public InvalidCursorException(String message) {
            super(message);
        }
    }
}
