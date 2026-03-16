package net.vheerden.archi.mcp.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom Jetty error handler that returns JSON-RPC error envelopes instead of HTML error pages.
 *
 * <p>Intercepts all HTTP-level errors (404, 400, 500, etc.) at the Jetty container level
 * and returns structured JSON-RPC 2.0 error responses. This ensures LLM clients always
 * receive machine-parseable error responses, never HTML.</p>
 *
 * <p>Maps HTTP status codes to standard JSON-RPC 2.0 error codes:</p>
 * <ul>
 *   <li>400 Bad Request &rarr; -32600 (Invalid Request)</li>
 *   <li>404 Not Found &rarr; -32601 (Method Not Found)</li>
 *   <li>405 Method Not Allowed &rarr; -32601 (Method Not Found)</li>
 *   <li>415 Unsupported Media Type &rarr; -32600 (Invalid Request)</li>
 *   <li>500 Internal Server Error &rarr; -32603 (Internal Error)</li>
 *   <li>Other &rarr; -32000 (Server Error)</li>
 * </ul>
 *
 * <p>Note: JSON-RPC 2.0 also defines -32700 (Parse Error) for invalid JSON, but
 * at the Jetty error handler level we cannot distinguish parse errors from other
 * HTTP 400 responses. Parse error differentiation would require transport-level hooks.</p>
 *
 * @see <a href="https://www.jsonrpc.org/specification#error_object">JSON-RPC 2.0 Error Object</a>
 */
public class JsonErrorHandler extends ErrorHandler {

    private static final Logger logger = LoggerFactory.getLogger(JsonErrorHandler.class);

    /** JSON-RPC 2.0: The JSON sent is not a valid Request object. */
    static final int JSONRPC_INVALID_REQUEST = -32600;

    /** JSON-RPC 2.0: The method does not exist / is not available. */
    static final int JSONRPC_METHOD_NOT_FOUND = -32601;

    /** JSON-RPC 2.0: Internal JSON-RPC error. */
    static final int JSONRPC_INTERNAL_ERROR = -32603;

    /** JSON-RPC 2.0: Reserved for implementation-defined server-errors. */
    static final int JSONRPC_SERVER_ERROR = -32000;

    private static final String CONTENT_TYPE_JSON = "application/json;charset=utf-8";

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        int httpStatus = response.getStatus();
        String message = (String) request.getAttribute(ERROR_MESSAGE);
        Throwable cause = (Throwable) request.getAttribute(ERROR_EXCEPTION);

        if (message == null || message.isBlank()) {
            message = httpStatusMessage(httpStatus);
        }

        int jsonRpcCode = mapHttpStatusToJsonRpcCode(httpStatus);

        // Guard against null URI for severely malformed requests
        String uri = request.getHttpURI() != null ? request.getHttpURI().toString() : "<unknown>";

        logger.warn("HTTP error {} on {}: {} (JSON-RPC code: {})",
                httpStatus, uri, message, jsonRpcCode);
        if (cause != null) {
            logger.debug("Error cause", cause);
        }

        String json = buildJsonRpcError(jsonRpcCode, message, httpStatus, uri);

        response.setStatus(httpStatus);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, CONTENT_TYPE_JSON);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        response.write(true, ByteBuffer.wrap(bytes), callback);

        return true;
    }

    /**
     * Maps an HTTP status code to a JSON-RPC 2.0 error code.
     *
     * @param httpStatus the HTTP status code
     * @return the corresponding JSON-RPC error code
     */
    int mapHttpStatusToJsonRpcCode(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> JSONRPC_INVALID_REQUEST;
            case 404 -> JSONRPC_METHOD_NOT_FOUND;
            case 405 -> JSONRPC_METHOD_NOT_FOUND;
            case 415 -> JSONRPC_INVALID_REQUEST;
            case 500 -> JSONRPC_INTERNAL_ERROR;
            default -> {
                if (httpStatus >= 400 && httpStatus < 500) {
                    yield JSONRPC_INVALID_REQUEST;
                }
                yield JSONRPC_SERVER_ERROR;
            }
        };
    }

    /**
     * Builds a JSON-RPC 2.0 error envelope string.
     *
     * <p>Uses manual string building to avoid a dependency on Jackson in the server package
     * (architecture boundary: server/ touches only Jetty types).</p>
     *
     * @param jsonRpcCode the JSON-RPC error code
     * @param message     the error message
     * @param httpStatus  the HTTP status code (included in data for diagnostics)
     * @param requestUri  the request URI (included in data for diagnostics)
     * @return the JSON-RPC error envelope as a string
     */
    String buildJsonRpcError(int jsonRpcCode, String message, int httpStatus, String requestUri) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{");
        sb.append("\"code\":").append(jsonRpcCode);
        sb.append(",\"message\":\"").append(escapeJson(message)).append("\"");
        sb.append(",\"data\":{");
        sb.append("\"httpStatus\":").append(httpStatus);
        sb.append(",\"uri\":\"").append(escapeJson(requestUri)).append("\"");
        sb.append("}}}");
        return sb.toString();
    }

    /**
     * Returns a human-readable message for common HTTP status codes.
     */
    private String httpStatusMessage(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 415 -> "Unsupported Media Type";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "HTTP Error " + httpStatus;
        };
    }

    /**
     * Escapes a string for safe inclusion in a JSON value.
     *
     * <p>Handles standard JSON escapes (quotes, backslashes, control chars) and
     * correctly processes Unicode surrogate pairs. Lone surrogates (invalid UTF-16)
     * are replaced with the Unicode replacement character U+FFFD.</p>
     */
    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else if (Character.isHighSurrogate(ch)) {
                        // Handle surrogate pairs: high surrogate must be followed by low surrogate
                        if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                            sb.append(ch);
                            sb.append(value.charAt(++i));
                        } else {
                            // Lone high surrogate — replace with U+FFFD
                            sb.append('\uFFFD');
                        }
                    } else if (Character.isLowSurrogate(ch)) {
                        // Lone low surrogate — replace with U+FFFD
                        sb.append('\uFFFD');
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
