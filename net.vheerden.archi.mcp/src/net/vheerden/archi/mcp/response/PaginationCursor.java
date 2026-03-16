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
 *   "v": 1,
 *   "mv": "modelVersion",
 *   "offset": 50,
 *   "limit": 50,
 *   "total": 250,
 *   "params": { "query": "auth", "type": "ApplicationComponent" }
 * }
 * </pre>
 *
 * <p>Thread-safe: uses a single shared {@link ObjectMapper} instance.</p>
 */
public final class PaginationCursor {

    /** Current cursor format version. */
    public static final int CURSOR_VERSION = 1;

    /** Default page size when no limit parameter is provided. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** Maximum allowed page size. */
    public static final int MAX_PAGE_SIZE = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private PaginationCursor() {} // utility class

    /**
     * Decoded cursor data record.
     *
     * @param version      cursor format version
     * @param modelVersion model version at cursor creation time
     * @param offset       starting offset for the next page
     * @param limit        page size
     * @param total        total result count at cursor creation time
     * @param params       original query parameters (never null, may be empty)
     */
    public record CursorData(
            int version,
            String modelVersion,
            int offset,
            int limit,
            int total,
            Map<String, String> params) {
    }

    /**
     * Encodes pagination state into a Base64 cursor token.
     *
     * @param modelVersion current model version
     * @param offset       starting offset for the next page
     * @param limit        page size
     * @param total        total result count
     * @param params       original query parameters (may be null or empty)
     * @return Base64-encoded cursor string
     */
    public static String encode(String modelVersion, int offset, int limit,
                                int total, Map<String, String> params) {
        Map<String, Object> cursorMap = new LinkedHashMap<>();
        cursorMap.put("v", CURSOR_VERSION);
        cursorMap.put("mv", modelVersion);
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

        return new CursorData(version, modelVersion, offset, limit, total, params);
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
