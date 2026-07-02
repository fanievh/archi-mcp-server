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
 * Tests for {@link RequestBodyUtf8ValidationHandler} — the default-on guard that rejects a
 * {@code POST} whose body bytes are not well-formed UTF-8 (an <em>undeclared</em> mis-encoded body,
 * e.g. ISO-8859-1/Windows-1252 bytes sent with no charset).
 *
 * <p>Two layers, mirroring {@link RequestCharsetValidationHandlerTest}:</p>
 * <ul>
 *   <li><b>Pure-unit</b> — the {@code isWellFormedUtf8(...)} byte-level decision matrix, including the
 *       critical anti-false-positive case (a valid UTF-8 body that legitimately contains a genuine
 *       U+FFFD must be accepted), plus the enforcing-flag check.</li>
 *   <li><b>HTTP-level</b> — start the real {@link TransportConfig} server (which wires this guard
 *       innermost) and assert 415-vs-pass on {@code /mcp} using a raw socket {@code POST} carrying a
 *       chosen raw <em>body</em> (the byte sequence is what this guard inspects).</li>
 * </ul>
 *
 * <p>Lives in the {@code server} package (NOT in {@code tools/osgi-excluded-tests.txt}), so it runs
 * headlessly in the {@code ci-junit} lane — "CI proves the boundary fix".</p>
 */
public class RequestBodyUtf8ValidationHandlerTest {

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

    // ---- Pure-unit: isWellFormedUtf8(...) decision matrix ----

    @Test
    public void shouldReject_whenBodyIsNotValidUtf8() {
        // ISO-8859-1 / Windows-1252 'ä' = 0xE4 (a UTF-8 3-byte lead) not followed by continuations.
        assertFalse(RequestBodyUtf8ValidationHandler.isWellFormedUtf8(new byte[] {(byte) 0xE4, 0x20}));
        assertFalse(RequestBodyUtf8ValidationHandler.isWellFormedUtf8(
                new byte[] {'w', 'i', 'd', 'g', 'e', 't', (byte) 0xE4})); // truncated trailing lead
        assertFalse(RequestBodyUtf8ValidationHandler.isWellFormedUtf8(new byte[] {(byte) 0x80})); // lone continuation
        assertFalse(RequestBodyUtf8ValidationHandler.isWellFormedUtf8(new byte[] {(byte) 0xC3})); // truncated 2-byte lead
        // Note a known, accepted gap: a UTF-16LE body of purely ASCII text ("AB" => 41 00 42 00) is
        // all valid UTF-8 bytes (the NULs decode fine), so it passes this guard and reaches the
        // servlet — where the NULs cause a loud JSON parse error rather than silent mojibake, which is
        // acceptable. We therefore assert on a clearly-invalid high-byte run a Latin-1 client produces.
        assertFalse(RequestBodyUtf8ValidationHandler.isWellFormedUtf8(
                new byte[] {(byte) 0xE9, (byte) 0xE8, (byte) 0xEA})); // Latin-1 "éèê" raw bytes
    }

    @Test
    public void shouldAccept_whenBodyIsValidUtf8() {
        assertTrue(RequestBodyUtf8ValidationHandler.isWellFormedUtf8(
                "{\"name\":\"widget\"}".getBytes(StandardCharsets.UTF_8))); // ASCII JSON
        assertTrue(RequestBodyUtf8ValidationHandler.isWellFormedUtf8(
                "äÄñ中文Москва".getBytes(StandardCharsets.UTF_8))); // multibyte: Latin-1 + CJK + Cyrillic
        assertTrue(RequestBodyUtf8ValidationHandler.isWellFormedUtf8(new byte[0])); // empty body
    }

    @Test
    public void shouldAccept_whenBodyContainsGenuineReplacementCharacter() {
        // THE anti-false-positive case: U+FFFD encodes as the valid 3-byte sequence EF BF BD. A body
        // that correctly UTF-8-encodes a real U+FFFD is well-formed and MUST pass — a naive scan for
        // the '�' character (rather than a well-formedness check) would wrongly reject this.
        byte[] body = "label�here".getBytes(StandardCharsets.UTF_8);
        // sanity: the bytes really do contain EF BF BD
        assertTrue(containsSequence(body, new byte[] {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD}));
        assertTrue(RequestBodyUtf8ValidationHandler.isWellFormedUtf8(body));
    }

    @Test
    public void shouldNotEnforce_whenDisabled() {
        assertFalse(new RequestBodyUtf8ValidationHandler(null, false).isEnforcing());
        assertTrue(new RequestBodyUtf8ValidationHandler(null, true).isEnforcing());
    }

    // ---- HTTP-level: real server, raw socket POST with a chosen raw body ----

    @Test
    public void shouldReturn415_whenBodyIsNotValidUtf8OnMcp() throws Exception {
        int port = freePort();
        transportConfig.startServer(port, LOOPBACK);
        // A client that serialized in a legacy codepage with no charset declared: "name":"widget�"
        // where the last byte is a raw Latin-1 'ä' (0xE4) — invalid UTF-8.
        byte[] body = concat("{\"name\":\"widget".getBytes(StandardCharsets.US_ASCII),
                new byte[] {(byte) 0xE4}, "\"}".getBytes(StandardCharsets.US_ASCII));
        assertEquals(415, rawPost(port, "/mcp", "application/json", body).status);
    }

    @Test
    public void shouldReachServlet_whenBodyIsValidUtf8OnMcp() throws Exception {
        int port = freePort();
        transportConfig.startServer(port, LOOPBACK);
        // A normal, valid UTF-8 body must reach the servlet (NOT 415), and not crash it (NOT 500) —
        // proving both the well-formedness pass AND that the buffer-and-replay delivers the body.
        int status = rawPost(port, "/mcp", "application/json",
                "{}".getBytes(StandardCharsets.UTF_8)).status;
        assertNotEquals("valid UTF-8 must not be rejected by the body guard", 415, status);
        assertNotEquals("must reach the servlet, not crash the server", 500, status);
    }

    @Test
    public void shouldReachServlet_whenBodyContainsGenuineReplacementCharacterOnMcp() throws Exception {
        int port = freePort();
        transportConfig.startServer(port, LOOPBACK);
        // A correctly UTF-8-encoded body that contains a genuine U+FFFD character must pass through.
        byte[] body = "{\"name\":\"label�\"}".getBytes(StandardCharsets.UTF_8);
        int status = rawPost(port, "/mcp", "application/json", body).status;
        assertNotEquals("a genuine U+FFFD in valid UTF-8 must not be rejected", 415, status);
        assertNotEquals("must reach the servlet, not crash the server", 500, status);
    }

    @Test
    public void shouldReturnJsonRpcEnvelope_whenBodyRejected() throws Exception {
        int port = freePort();
        transportConfig.startServer(port, LOOPBACK);
        byte[] body = concat("{\"name\":\"".getBytes(StandardCharsets.US_ASCII),
                new byte[] {(byte) 0xE4}, "\"}".getBytes(StandardCharsets.US_ASCII));
        Response resp = rawPost(port, "/mcp", "application/json", body);

        assertEquals(415, resp.status);
        assertTrue("content-type should be JSON: " + resp.contentType,
                resp.contentType != null && resp.contentType.toLowerCase().contains("application/json"));
        // JSON-RPC envelope shape from JsonErrorHandler: 415 -> -32600, data.httpStatus:415.
        assertTrue("body should be a JSON-RPC error: " + resp.body, resp.body.contains("\"jsonrpc\":\"2.0\""));
        assertTrue("body should carry -32600: " + resp.body, resp.body.contains("-32600"));
        assertTrue("body should carry httpStatus 415: " + resp.body, resp.body.contains("\"httpStatus\":415"));
        assertFalse("body must not be an HTML error page: " + resp.body, resp.body.contains("<html"));
    }

    @Test
    public void shouldPassMalformedBodyThrough_whenDisabledViaSystemProperty() throws Exception {
        // End-to-end proof the kill switch wires through: with -Darchi.mcp.scanBodyUtf8=false, the
        // guard short-circuits and a malformed body reaches the servlet (NOT 415). The property is
        // read when TransportConfig builds the handler, so it must be set before startServer.
        String key = "archi.mcp.scanBodyUtf8";
        String previous = System.getProperty(key);
        System.setProperty(key, "false");
        try {
            int port = freePort();
            transportConfig.startServer(port, LOOPBACK);
            byte[] body = concat("{\"name\":\"".getBytes(StandardCharsets.US_ASCII),
                    new byte[] {(byte) 0xE4}, "\"}".getBytes(StandardCharsets.US_ASCII));
            int status = rawPost(port, "/mcp", "application/json", body).status;
            assertNotEquals("disabled guard must not reject a malformed body", 415, status);
            assertNotEquals("must reach the servlet, not crash the server", 500, status);
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    // ---- Helpers ----

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static boolean containsSequence(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // Raw HTTP/1.1 POST with full control of the body bytes (the guard inspects the body, not headers).
    private Response rawPost(int port, String path, String contentType, byte[] body) throws IOException {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(LOOPBACK, port), 3000);
            sock.setSoTimeout(5000);

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
