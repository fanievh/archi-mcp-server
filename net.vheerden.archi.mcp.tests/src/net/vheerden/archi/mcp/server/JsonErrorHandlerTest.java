package net.vheerden.archi.mcp.server;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

/**
 * Unit tests for {@link JsonErrorHandler}.
 *
 * <p>Tests JSON-RPC error code mapping, JSON envelope building, JSON escaping, and the logging
 * rule. The real {@code handle} method is driven through proxies of Jetty 12's {@code Request} and
 * {@code Response} interfaces, so the wiring from the cause Jetty sets to the log is pinned
 * without a running server.</p>
 */
public class JsonErrorHandlerTest {

    private JsonErrorHandler handler;

    @Before
    public void setUp() {
        handler = new JsonErrorHandler();
    }

    // ---- HTTP to JSON-RPC code mapping ----

    @Test
    public void shouldMap400_toInvalidRequest() {
        assertEquals(JsonErrorHandler.JSONRPC_INVALID_REQUEST, handler.mapHttpStatusToJsonRpcCode(400));
    }

    @Test
    public void shouldMap404_toMethodNotFound() {
        assertEquals(JsonErrorHandler.JSONRPC_METHOD_NOT_FOUND, handler.mapHttpStatusToJsonRpcCode(404));
    }

    @Test
    public void shouldMap405_toMethodNotFound() {
        assertEquals(JsonErrorHandler.JSONRPC_METHOD_NOT_FOUND, handler.mapHttpStatusToJsonRpcCode(405));
    }

    @Test
    public void shouldMap415_toInvalidRequest() {
        assertEquals(JsonErrorHandler.JSONRPC_INVALID_REQUEST, handler.mapHttpStatusToJsonRpcCode(415));
    }

    @Test
    public void shouldMap500_toInternalError() {
        assertEquals(JsonErrorHandler.JSONRPC_INTERNAL_ERROR, handler.mapHttpStatusToJsonRpcCode(500));
    }

    @Test
    public void shouldMapOther4xx_toInvalidRequest() {
        assertEquals(JsonErrorHandler.JSONRPC_INVALID_REQUEST, handler.mapHttpStatusToJsonRpcCode(403));
        assertEquals(JsonErrorHandler.JSONRPC_INVALID_REQUEST, handler.mapHttpStatusToJsonRpcCode(409));
        assertEquals(JsonErrorHandler.JSONRPC_INVALID_REQUEST, handler.mapHttpStatusToJsonRpcCode(422));
    }

    @Test
    public void shouldMapOther5xx_toServerError() {
        assertEquals(JsonErrorHandler.JSONRPC_SERVER_ERROR, handler.mapHttpStatusToJsonRpcCode(502));
        assertEquals(JsonErrorHandler.JSONRPC_SERVER_ERROR, handler.mapHttpStatusToJsonRpcCode(503));
    }

    // ---- JSON envelope building ----

    @Test
    public void shouldBuildValidJsonRpcErrorEnvelope() {
        String json = handler.buildJsonRpcError(-32600, "Bad Request", 400, "/invalid");

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":null"));
        assertTrue(json.contains("\"code\":-32600"));
        assertTrue(json.contains("\"message\":\"Bad Request\""));
        assertTrue(json.contains("\"httpStatus\":400"));
        assertTrue(json.contains("\"uri\":\"/invalid\""));
    }

    @Test
    public void shouldBuildEnvelope_for404() {
        String json = handler.buildJsonRpcError(-32601, "Not Found", 404, "/unknown/path");

        assertTrue(json.contains("\"code\":-32601"));
        assertTrue(json.contains("\"message\":\"Not Found\""));
        assertTrue(json.contains("\"httpStatus\":404"));
        assertTrue(json.contains("\"uri\":\"/unknown/path\""));
    }

    @Test
    public void shouldBuildEnvelope_for500() {
        String json = handler.buildJsonRpcError(-32603, "Internal Server Error", 500, "/mcp");

        assertTrue(json.contains("\"code\":-32603"));
        assertTrue(json.contains("\"message\":\"Internal Server Error\""));
        assertTrue(json.contains("\"httpStatus\":500"));
    }

    @Test
    public void shouldProduceValidJson_parseable() throws Exception {
        String json = handler.buildJsonRpcError(-32600, "Test message", 400, "/test");

        // Verify it's valid JSON by parsing with Jackson
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.Map<?, ?> parsed = mapper.readValue(json, java.util.Map.class);

        assertEquals("2.0", parsed.get("jsonrpc"));
        assertNull(parsed.get("id"));
        assertNotNull(parsed.get("error"));
    }

    @Test
    public void shouldNeverExposeStackTraces_inErrorEnvelope() {
        String json = handler.buildJsonRpcError(-32603, "Internal Server Error", 500, "/mcp");

        assertFalse("Should not contain stack trace markers", json.contains("at "));
        assertFalse("Should not contain exception class names", json.contains("Exception"));
        assertFalse("Should not contain .java references", json.contains(".java"));
    }

    // ---- JSON escaping (no raw data leaks) ----

    @Test
    public void shouldEscapeDoubleQuotes_inMessage() {
        String json = handler.buildJsonRpcError(-32600, "Invalid \"field\"", 400, "/test");
        assertTrue(json.contains("Invalid \\\"field\\\""));
    }

    @Test
    public void shouldEscapeBackslashes_inUri() {
        String json = handler.buildJsonRpcError(-32600, "Bad", 400, "/path\\with\\backslash");
        assertTrue(json.contains("/path\\\\with\\\\backslash"));
    }

    @Test
    public void shouldEscapeNewlines_inMessage() {
        String json = handler.buildJsonRpcError(-32600, "Line1\nLine2", 400, "/test");
        assertTrue(json.contains("Line1\\nLine2"));
    }

    @Test
    public void shouldEscapeControlChars_inMessage() {
        String json = handler.buildJsonRpcError(-32600, "Tab\there", 400, "/test");
        assertTrue(json.contains("Tab\\there"));
    }

    @Test
    public void shouldHandleNullMessage_inEscapeJson() {
        String result = JsonErrorHandler.escapeJson(null);
        assertEquals("", result);
    }

    @Test
    public void shouldHandleEmptyString_inEscapeJson() {
        String result = JsonErrorHandler.escapeJson("");
        assertEquals("", result);
    }

    @Test
    public void shouldNotEscapeNormalText() {
        String result = JsonErrorHandler.escapeJson("Normal text 123");
        assertEquals("Normal text 123", result);
    }

    // ---- Surrogate pair handling (Finding #5) ----

    @Test
    public void shouldHandleValidSurrogatePairs() {
        // U+1F600 (grinning face) = \uD83D\uDE00 in UTF-16
        String emoji = "\uD83D\uDE00";
        String result = JsonErrorHandler.escapeJson(emoji);
        assertEquals(emoji, result);
    }

    @Test
    public void shouldReplaceLoneHighSurrogate_withReplacementChar() {
        // Lone high surrogate without matching low surrogate
        String lone = "before\uD83Dafter";
        String result = JsonErrorHandler.escapeJson(lone);
        assertEquals("before\uFFFDafter", result);
    }

    @Test
    public void shouldReplaceLoneLowSurrogate_withReplacementChar() {
        // Lone low surrogate without preceding high surrogate
        String lone = "before\uDE00after";
        String result = JsonErrorHandler.escapeJson(lone);
        assertEquals("before\uFFFDafter", result);
    }

    // ---- Logging: a server fault keeps its cause, a client error does not log a stack ----

    private record Call(String method, List<Object> args) {
    }

    /** Records every logging call, with a varargs array flattened into the argument list. */
    private static Logger recordingLogger(List<Call> calls) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(), new Class<?>[] {Logger.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("is") && method.getReturnType() == boolean.class) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "recording";
                    }
                    List<Object> flat = new ArrayList<>();
                    for (Object arg : args == null ? new Object[0] : args) {
                        if (arg instanceof Object[] varargs) {
                            flat.addAll(Arrays.asList(varargs));
                        } else {
                            flat.add(arg);
                        }
                    }
                    calls.add(new Call(name, flat));
                    return null;
                });
    }

    private static List<String> methods(List<Call> calls) {
        return calls.stream().map(Call::method).toList();
    }

    @Test
    public void shouldLogErrorWithCause_when5xxCarriesACause() {
        List<Call> calls = new ArrayList<>();
        IOException cause = new IOException("handler blew up");

        JsonErrorHandler.logHttpError(recordingLogger(calls), 500, "/mcp", "Server Error",
                JsonErrorHandler.JSONRPC_INTERNAL_ERROR, cause);

        assertEquals("one ERROR line and nothing else: " + calls, List.of("error"), methods(calls));
        List<Object> args = calls.get(0).args();
        assertSame("the cause must be the LAST argument, so its stack is printed",
                cause, args.get(args.size() - 1));
        assertTrue("the line still names the status and the uri: " + args,
                args.contains(500) && args.contains("/mcp"));
    }

    @Test
    public void shouldWarnWithoutError_when5xxHasNoCause() {
        List<Call> calls = new ArrayList<>();

        JsonErrorHandler.logHttpError(recordingLogger(calls), 500, "/mcp", "Server Error",
                JsonErrorHandler.JSONRPC_INTERNAL_ERROR, null);

        assertEquals("nothing to print a stack from, so WARN as before: " + calls,
                List.of("warn"), methods(calls));
    }

    @Test
    public void shouldWarnAndKeepCauseAtDebug_when4xxCarriesACause() {
        List<Call> calls = new ArrayList<>();
        IOException cause = new IOException("not found");

        JsonErrorHandler.logHttpError(recordingLogger(calls), 404, "/nope", "Not Found",
                JsonErrorHandler.JSONRPC_METHOD_NOT_FOUND, cause);

        assertEquals("a client error must not log a stack above DEBUG: " + calls,
                List.of("warn", "debug"), methods(calls));
        assertFalse("the WARN line carries no throwable", calls.get(0).args().contains(cause));
        assertSame(cause, calls.get(1).args().get(calls.get(1).args().size() - 1));
    }

    // ---- The real handle(): the cause Jetty hands over reaches the log ----

    /** What the stand-in response captured. */
    private static final class Written {
        int status;
        final HttpFields.Mutable headers = HttpFields.build();
        String body;
        boolean completed;
    }

    private static Object defaultFor(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    /** Jetty 12's Request is an interface, so a proxy can stand in for the real one. */
    private static Request request(String uri, Map<String, Object> attributes) {
        HttpURI httpUri = HttpURI.from(uri);
        return (Request) Proxy.newProxyInstance(
                Request.class.getClassLoader(), new Class<?>[] {Request.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> attributes.get((String) args[0]);
                    case "getHttpURI" -> httpUri;
                    default -> defaultFor(method);
                });
    }

    private static Response response(int status, Written written) {
        written.status = status;
        return (Response) Proxy.newProxyInstance(
                Response.class.getClassLoader(), new Class<?>[] {Response.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getStatus":
                            return written.status;
                        case "setStatus":
                            written.status = (Integer) args[0];
                            return null;
                        case "getHeaders":
                            return written.headers;
                        case "write":
                            written.body = StandardCharsets.UTF_8.decode((ByteBuffer) args[1]).toString();
                            ((Callback) args[2]).succeeded();
                            return null;
                        default:
                            return defaultFor(method);
                    }
                });
    }

    private static Callback completion(Written written) {
        return new Callback() {
            @Override
            public void succeeded() {
                written.completed = true;
            }
        };
    }

    @Test
    public void shouldLogTheCauseJettyHandsOver_whenHandleAnswersA5xx() throws Exception {
        List<Call> calls = new ArrayList<>();
        IOException cause = new IOException("servlet blew up");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ErrorHandler.ERROR_EXCEPTION, cause);
        attributes.put(ErrorHandler.ERROR_MESSAGE, "Server Error");
        Written written = new Written();

        boolean handled = new JsonErrorHandler(recordingLogger(calls)).handle(
                request("http://127.0.0.1:18090/mcp", attributes), response(500, written),
                completion(written));

        List<Call> carrying = calls.stream()
                .filter(c -> !c.args().isEmpty() && c.args().get(c.args().size() - 1) == cause)
                .toList();
        assertEquals("the cause Jetty set must reach the log as the last argument: " + calls,
                1, carrying.size());
        assertEquals("and on the ERROR line, the only line: " + calls,
                List.of("error"), methods(calls));
        assertTrue(handled);
        assertTrue("the response must be completed", written.completed);
        assertEquals(500, written.status);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,"
                + "\"message\":\"Server Error\",\"data\":{\"httpStatus\":500,"
                + "\"uri\":\"http://127.0.0.1:18090/mcp\"}}}", written.body);
    }

    @Test
    public void shouldNotLogAnError_whenHandleAnswersA404() throws Exception {
        List<Call> calls = new ArrayList<>();
        Written written = new Written();

        new JsonErrorHandler(recordingLogger(calls)).handle(
                request("http://127.0.0.1:18090/nope", new HashMap<>()), response(404, written),
                completion(written));

        assertEquals("a client error is a WARN: " + calls, List.of("warn"), methods(calls));
        assertTrue(written.completed);
        assertTrue(written.body.contains("\"code\":-32601"));
    }
}
