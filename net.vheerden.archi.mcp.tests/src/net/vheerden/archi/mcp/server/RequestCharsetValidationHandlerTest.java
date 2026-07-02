package net.vheerden.archi.mcp.server;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

/**
 * Tests for {@link RequestCharsetValidationHandler} — the default-on guard that rejects a request
 * declaring a non-UTF-8 {@code Content-Type} charset.
 *
 * <p>Two layers, mirroring {@link BearerTokenAuthHandlerTest} / {@link OriginHostValidationHandlerTest}:</p>
 * <ul>
 *   <li><b>Pure-unit</b> — the {@code isAcceptableCharset(...)} decision matrix, constructed without
 *       starting Jetty (the {@code enforcing} flag is injected via the package-visible constructor so
 *       the tests never depend on the live system property), plus the disabled (kill-switch-off)
 *       all-pass case.</li>
 *   <li><b>HTTP-level</b> — start the real {@link TransportConfig} server (which wires this guard
 *       innermost) and assert 415-vs-pass on {@code /mcp} using a raw socket {@code POST} carrying a
 *       chosen {@code Content-Type}.</li>
 * </ul>
 *
 * <p>Lives in the {@code server} package (NOT in {@code tools/osgi-excluded-tests.txt}), so it runs
 * headlessly in the {@code ci-junit} lane — "CI proves the boundary fix".</p>
 */
public class RequestCharsetValidationHandlerTest {

    private static final String LOOPBACK = "127.0.0.1";

    private final TransportConfig transportConfig = new TransportConfig();

    @After
    public void tearDown() {
        transportConfig.stopServer();
    }

    private static int freePort() throws IOException {
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    // An enforcing guard. The wrapped Handler is null because the pure-unit tests exercise only
    // isAcceptableCharset(...) / isEnforcing() and never call handle() — safe for that purpose only.
    private RequestCharsetValidationHandler enforcingGuard() {
        return new RequestCharsetValidationHandler(null, true);
    }

    // ---- Pure-unit: isAcceptableCharset(...) decision matrix ----

    @Test
    public void shouldReject_whenNonUtf8CharsetDeclared() {
        RequestCharsetValidationHandler guard = enforcingGuard();
        assertFalse(guard.isAcceptableCharset("application/json; charset=iso-8859-1"));
        assertFalse(guard.isAcceptableCharset("application/json; charset=utf-16"));
        assertFalse(guard.isAcceptableCharset("application/json; charset=us-ascii"));
        assertFalse(guard.isAcceptableCharset("text/plain; charset=windows-1252"));
    }

    @Test
    public void shouldReject_whenNonUtf8CharsetWithMixedCaseParameterName() {
        // RFC 7231 §3.1.1.1: parameter names are case-insensitive, so an upper/mixed-case CHARSET=
        // declaration is a valid non-UTF-8 declaration and must NOT slip through as "no charset".
        RequestCharsetValidationHandler guard = enforcingGuard();
        assertFalse(guard.isAcceptableCharset("application/json; CHARSET=iso-8859-1"));
        assertFalse(guard.isAcceptableCharset("application/json; Charset=iso-8859-1"));
        assertFalse(guard.isAcceptableCharset("application/json; ChArSeT=utf-16"));
    }

    @Test
    public void shouldAccept_whenNoContentTypeHeader() {
        RequestCharsetValidationHandler guard = enforcingGuard();
        assertTrue(guard.isAcceptableCharset(null));
        assertTrue(guard.isAcceptableCharset(""));
        assertTrue(guard.isAcceptableCharset("   "));
    }

    @Test
    public void shouldAccept_whenNoCharsetParameter() {
        RequestCharsetValidationHandler guard = enforcingGuard();
        // application/json's media type carries no charset parameter — the common, correct case.
        assertTrue(guard.isAcceptableCharset("application/json"));
        assertTrue(guard.isAcceptableCharset("application/json; foo=bar"));
        assertTrue(guard.isAcceptableCharset("text/event-stream"));
        // An empty charset value parses as "no charset" (Jetty returns null) => lenient accept.
        assertTrue(guard.isAcceptableCharset("application/json; charset="));
    }

    @Test
    public void shouldAccept_whenUtf8Declared() {
        RequestCharsetValidationHandler guard = enforcingGuard();
        assertTrue(guard.isAcceptableCharset("application/json; charset=utf-8"));
        assertTrue(guard.isAcceptableCharset("application/json; charset=UTF-8"));
        assertTrue(guard.isAcceptableCharset("application/json; charset=utf8"));
        assertTrue(guard.isAcceptableCharset("application/json;charset=Utf-8")); // no space, mixed case
    }

    @Test
    public void shouldAccept_whenUtf8DeclaredWithQuotingOrWhitespaceOrReordering() {
        RequestCharsetValidationHandler guard = enforcingGuard();
        assertTrue(guard.isAcceptableCharset("application/json; charset=\"utf-8\"")); // quoted value
        assertTrue(guard.isAcceptableCharset("application/json ;  charset = utf-8")); // loose whitespace
        assertTrue(guard.isAcceptableCharset("application/json; foo=bar; charset=utf-8")); // reordered params
    }

    @Test
    public void shouldReject_whenNonUtf8DeclaredAmongOtherParameters() {
        RequestCharsetValidationHandler guard = enforcingGuard();
        assertFalse(guard.isAcceptableCharset("application/json; charset=iso-8859-1; foo=bar"));
        assertFalse(guard.isAcceptableCharset("application/json; foo=bar; charset=iso-8859-1"));
    }

    // ---- Pure-unit: disabled (kill switch off) => all pass ----

    @Test
    public void shouldNotEnforce_whenDisabled() {
        assertFalse(new RequestCharsetValidationHandler(null, false).isEnforcing());
        assertTrue(new RequestCharsetValidationHandler(null, true).isEnforcing());
    }

    @Test
    public void shouldAcceptEverything_whenDisabled() {
        // A disabled guard passes every header through, including an otherwise-rejected non-UTF-8 one.
        RequestCharsetValidationHandler disabled = new RequestCharsetValidationHandler(null, false);
        assertTrue(disabled.isAcceptableCharset("application/json; charset=iso-8859-1"));
        assertTrue(disabled.isAcceptableCharset("application/json; charset=utf-16"));
        assertTrue(disabled.isAcceptableCharset(null));
    }

    // ---- HTTP-level: real server, raw socket POST with a chosen Content-Type ----

    @Test
    public void shouldReturn415_whenNonUtf8CharsetOnMcp() throws Exception {
        int port = freePort();
        transportConfig.startServer(port, LOOPBACK);
        assertEquals(415, rawPost(port, "/mcp", "application/json; charset=iso-8859-1").status);
    }

    @Test
    public void shouldReachServlet_whenUtf8CharsetOnMcp() throws Exception {
        int port = freePort();
        transportConfig.startServer(port, LOOPBACK);
        // The transport servlet may answer 200/400/405 for this POST — the point is it is NOT 415,
        // i.e. the request passed the charset guard and reached the servlet. Asserting NOT 500 too
        // distinguishes a genuine servlet response from a server crash that would also be "not 415".
        int status = rawPost(port, "/mcp", "application/json; charset=utf-8").status;
        assertNotEquals("must not be rejected by the charset guard", 415, status);
        assertNotEquals("must reach the servlet, not crash the server", 500, status);
    }

    @Test
    public void shouldReachServlet_whenNoCharsetOnMcp() throws Exception {
        int port = freePort();
        transportConfig.startServer(port, LOOPBACK);
        int status = rawPost(port, "/mcp", "application/json").status;
        assertNotEquals("must not be rejected by the charset guard", 415, status);
        assertNotEquals("must reach the servlet, not crash the server", 500, status);
    }

    @Test
    public void shouldReturnJsonRpcEnvelope_whenRejected() throws Exception {
        int port = freePort();
        transportConfig.startServer(port, LOOPBACK);
        Response resp = rawPost(port, "/mcp", "application/json; charset=iso-8859-1");

        assertEquals(415, resp.status);
        assertTrue("content-type should be JSON: " + resp.contentType,
                resp.contentType != null && resp.contentType.toLowerCase().contains("application/json"));
        // JSON-RPC envelope shape from JsonErrorHandler: 415 -> -32600, data.httpStatus:415.
        assertTrue("body should be a JSON-RPC error: " + resp.body, resp.body.contains("\"jsonrpc\":\"2.0\""));
        assertTrue("body should carry -32600: " + resp.body, resp.body.contains("-32600"));
        assertTrue("body should carry httpStatus 415: " + resp.body, resp.body.contains("\"httpStatus\":415"));
        assertFalse("body must not be an HTML error page: " + resp.body, resp.body.contains("<html"));
    }

    // ---- Raw HTTP/1.1 POST helper (full control of the Content-Type header) ----

    private Response rawPost(int port, String path, String contentType) throws IOException {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(LOOPBACK, port), 3000);
            sock.setSoTimeout(5000);

            // A minimal, well-formed body; its bytes are never read by this guard (header-only check).
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

            StringBuilder req = new StringBuilder();
            req.append("POST ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(LOOPBACK).append(":").append(port).append("\r\n");
            if (contentType != null) {
                req.append("Content-Type: ").append(contentType).append("\r\n");
            }
            req.append("Content-Length: ").append(body.length).append("\r\n");
            req.append("Accept: */*\r\n");
            req.append("Connection: close\r\n");
            req.append("\r\n");

            OutputStream out = sock.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();

            return readResponse(sock.getInputStream());
        }
    }

    private Response readResponse(InputStream rawIn) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(rawIn, StandardCharsets.UTF_8));

        String statusLine = in.readLine();
        Response resp = new Response();
        if (statusLine == null) {
            resp.status = -1;
            return resp;
        }
        // "HTTP/1.1 415 Unsupported Media Type"
        String[] parts = statusLine.split(" ", 3);
        resp.status = parts.length >= 2 ? Integer.parseInt(parts[1]) : -1;

        // Headers until blank line.
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                resp.headers.put(name, value);
                if (name.equals("content-type")) {
                    resp.contentType = value;
                }
            }
        }

        // Body (best-effort; Connection: close means the stream ends at EOF).
        StringBuilder body = new StringBuilder();
        char[] buf = new char[1024];
        int n;
        try {
            while (body.length() < 8192 && (n = in.read(buf)) != -1) {
                body.append(buf, 0, n);
            }
        } catch (IOException ignored) {
            // socket timeout on a streaming endpoint — whatever we read is enough for assertions
        }
        resp.body = body.toString();
        return resp;
    }

    private static final class Response {
        int status;
        String contentType;
        String body = "";
        final Map<String, String> headers = new HashMap<>();
    }
}
