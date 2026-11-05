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

import org.junit.After;
import org.junit.Test;

/**
 * Tests for {@link OriginHostValidationHandler} — the default-on Origin/Host validation guard
 * (the cheap half of the loopback-exposure hardening).
 *
 * <p>Two layers:</p>
 * <ul>
 *   <li><b>Pure-unit</b> — the {@code isAllowed(...)} / {@code hostPart(...)} decision matrix,
 *       constructed without starting Jetty (mirrors the {@code JsonErrorHandler} unit style).</li>
 *   <li><b>HTTP-level</b> — start the real {@link TransportConfig} server (which wires the guard
 *       unconditionally) and assert 403-vs-pass on both {@code /mcp} and {@code /sse} using a raw
 *       socket, since {@code java.net.http.HttpClient} forbids setting the restricted
 *       {@code Host} header.</li>
 * </ul>
 *
 * <p>Lives in the {@code server} package (NOT in {@code tools/osgi-excluded-tests.txt}), so it
 * runs headlessly in the {@code ci-junit} lane — "CI proves the security fix".</p>
 */
public class OriginHostValidationHandlerTest {

    private static final String LOOPBACK = "127.0.0.1";

    private final TransportConfig transportConfig = new TransportConfig();

    @After
    public void tearDown() {
        transportConfig.stopServer();
    }

    // A guard wired to the default loopback bind (enforcing). The wrapped Handler is null because
    // these unit tests exercise only the decision logic (isAllowed / hostPart / isEnforcing) and
    // never call handle() — passing null here is intentional and safe for that purpose only.
    private OriginHostValidationHandler loopbackGuard() {
        return new OriginHostValidationHandler(null, LOOPBACK);
    }

    // ---- Pure-unit: isAllowed(...) decision matrix ----

    @Test
    public void shouldAllow_whenLoopbackHostNoOrigin() {
        OriginHostValidationHandler guard = loopbackGuard();
        assertTrue(guard.isAllowed("127.0.0.1:18090", null));
        assertTrue(guard.isAllowed("localhost:18090", null));
        assertTrue(guard.isAllowed("[::1]:18090", null));
        assertTrue(guard.isAllowed("127.0.0.1", null)); // no port
    }

    @Test
    public void shouldReject_whenForeignHost() {
        OriginHostValidationHandler guard = loopbackGuard();
        assertFalse(guard.isAllowed("evil.attacker.com:18090", null));
        assertFalse(guard.isAllowed("evil.attacker.com", null));
    }

    @Test
    public void shouldReject_whenHostMissingOrBlank() {
        OriginHostValidationHandler guard = loopbackGuard();
        assertFalse(guard.isAllowed(null, null));
        assertFalse(guard.isAllowed("", null));
        assertFalse(guard.isAllowed("   ", null));
    }

    @Test
    public void shouldReject_whenLoopbackHostButForeignOrigin() {
        OriginHostValidationHandler guard = loopbackGuard();
        assertFalse(guard.isAllowed("127.0.0.1:18090", "https://evil.example"));
        assertFalse(guard.isAllowed("localhost:18090", "http://evil.example:8080"));
    }

    @Test
    public void shouldReject_whenOriginIsLiteralNull() {
        OriginHostValidationHandler guard = loopbackGuard();
        assertFalse(guard.isAllowed("127.0.0.1:18090", "null"));
        assertFalse(guard.isAllowed("127.0.0.1:18090", "NULL"));
    }

    @Test
    public void shouldAllow_whenLoopbackHostAndLoopbackOrigin() {
        OriginHostValidationHandler guard = loopbackGuard();
        assertTrue(guard.isAllowed("localhost:18090", "http://localhost:18090"));
        assertTrue(guard.isAllowed("127.0.0.1:18090", "http://127.0.0.1:18090"));
        assertTrue(guard.isAllowed("[::1]:18090", "http://[::1]:18090"));
    }

    @Test
    public void shouldAllow_whenMissingOrBlankOrigin() {
        OriginHostValidationHandler guard = loopbackGuard();
        assertTrue(guard.isAllowed("localhost:18090", null)); // non-browser client
        assertTrue(guard.isAllowed("localhost:18090", "   ")); // blank treated as absent
    }

    @Test
    public void shouldAllow_whenIpv6LoopbackHost() {
        OriginHostValidationHandler guard = loopbackGuard();
        assertTrue(guard.isAllowed("[::1]:18090", null));
        assertTrue(guard.isAllowed("[::1]", null));
    }

    @Test
    public void shouldBeCaseInsensitive() {
        OriginHostValidationHandler guard = loopbackGuard();
        assertTrue(guard.isAllowed("LOCALHOST:18090", null));
        assertTrue(guard.isAllowed("LocalHost", null));
        assertTrue(guard.isAllowed("localhost", "HTTP://LOCALHOST:18090"));
    }

    @Test
    public void shouldIncludeBindAddressInAllowlist() {
        // A LAN bind is non-loopback, so build a guard whose bind IS a loopback alias to prove the
        // bind host-part lands in the allowlist alongside the literals.
        OriginHostValidationHandler guard = new OriginHostValidationHandler(null, "127.0.0.1");
        assertTrue(guard.allowlist().contains("127.0.0.1"));
        assertTrue(guard.allowlist().contains("localhost"));
        assertTrue(guard.allowlist().contains("::1"));
    }

    @Test
    public void shouldNotIncludeWildcardBindInAllowlist() {
        // A wildcard bind specifier is not a connectable host — it must never land on the allowlist
        // (latent-bypass guard), even though a wildcard bind is non-enforcing anyway.
        assertFalse(new OriginHostValidationHandler(null, "0.0.0.0").allowlist().contains("0.0.0.0"));
        assertFalse(new OriginHostValidationHandler(null, "::").allowlist().contains("::"));
    }

    @Test
    public void shouldRejectFailClosed_whenHostOrOriginHasUserinfoOrExoticForm() {
        // Fail-closed invariant: non-canonical forms are NOT parsed permissively — they fall through
        // to "not in allowlist" and are rejected. No legitimate loopback client sends these.
        OriginHostValidationHandler guard = loopbackGuard();
        assertFalse(guard.isAllowed("evil.com@localhost", null));             // userinfo in Host
        assertFalse(guard.isAllowed("localhost@evil.com", null));
        assertFalse(guard.isAllowed("localhost.", null));                     // trailing-dot FQDN
        assertFalse(guard.isAllowed("127.0.0.1.evil.com", null));            // suffix-confusion
        assertFalse(guard.isAllowed("127.0.0.1", "http://evil.com@localhost")); // userinfo in Origin
        assertFalse(guard.isAllowed("127.0.0.1", "http://[::ffff:127.0.0.1]")); // IPv4-mapped, not on allowlist
    }

    // ---- Pure-unit: non-loopback bind pass-through ----

    @Test
    public void shouldRelaxEnforcement_whenNonLoopbackBind() {
        assertFalse(new OriginHostValidationHandler(null, "0.0.0.0").isEnforcing());
        assertFalse(new OriginHostValidationHandler(null, "10.0.0.5").isEnforcing());
    }

    @Test
    public void shouldEnforce_whenLoopbackBind() {
        assertTrue(new OriginHostValidationHandler(null, "127.0.0.1").isEnforcing());
        assertTrue(new OriginHostValidationHandler(null, "localhost").isEnforcing());
        assertTrue(new OriginHostValidationHandler(null, "::1").isEnforcing());
    }

    // ---- Pure-unit: the guard reads the server's one classification ----
    // The guard no longer classifies a bind itself: it reads BindExposure, the classification the
    // preference page's warning and the server-file-access decision read too (see BindExposureTest).

    @Test
    public void shouldEnforce_exactlyWhenTheClassificationSaysSo_forTheBindItIsGiven() {
        for (BindExposure exposure : BindExposure.values()) {
            assertEquals(exposure.name(), exposure.enforcesOriginHost(),
                    new OriginHostValidationHandler(null, "192.168.1.10", exposure).isEnforcing());
        }
    }

    @Test
    public void shouldEnforce_whenTheBindCannotBeClassified() {
        assertTrue(new OriginHostValidationHandler(null, null).isEnforcing());
        assertTrue(new OriginHostValidationHandler(null, "").isEnforcing());
        assertTrue(new OriginHostValidationHandler(null, "no.such.host.invalid").isEnforcing());
    }

    @Test
    public void shouldShowExposureWarningIffEnforcementOff_acrossMatrix() {
        // Invariant across the full matrix: the page's warning (BindExposure.warnsOperator) is shown
        // EXACTLY when the guard stops enforcing (isEnforcing() == false) for that same bind.
        for (String bind : BindExposureTest.BINDS) {
            boolean enforcing = new OriginHostValidationHandler(null, bind).isEnforcing();
            assertEquals("exposure warning must be shown iff enforcement is OFF for " + bind,
                    !enforcing, BindExposure.of(bind).warnsOperator());
        }
    }

    // ---- Pure-unit: hostPart(...) edge cases ----

    @Test
    public void shouldExtractHostPart_acrossFormats() {
        assertNull(OriginHostValidationHandler.hostPart(null));
        assertEquals("", OriginHostValidationHandler.hostPart("   "));
        assertEquals("127.0.0.1", OriginHostValidationHandler.hostPart("127.0.0.1:18090"));
        assertEquals("localhost", OriginHostValidationHandler.hostPart("localhost"));
        assertEquals("::1", OriginHostValidationHandler.hostPart("[::1]:18090"));
        assertEquals("::1", OriginHostValidationHandler.hostPart("[::1]"));
        assertEquals("localhost", OriginHostValidationHandler.hostPart("http://localhost:18090"));
        assertEquals("localhost", OriginHostValidationHandler.hostPart("http://localhost:18090/sse/message"));
        assertEquals("evil.example", OriginHostValidationHandler.hostPart("https://evil.example"));
        assertEquals("localhost", OriginHostValidationHandler.hostPart("LOCALHOST"));
        // Bare unbracketed ::1 (the bindAddress form; a wire Host header would be bracketed) — the
        // leading ':' has a non-numeric suffix so no port is stripped and the literal is preserved.
        assertEquals("::1", OriginHostValidationHandler.hostPart("::1"));
        // Non-numeric ":suffix" (e.g. "foo:bar") is returned verbatim → not in allowlist → rejected.
        // Safe: an unrecognised format defaults to denial rather than a permissive parse.
        assertEquals("foo:bar", OriginHostValidationHandler.hostPart("foo:bar"));
    }

    // ---- HTTP-level: real server, raw socket, both transports ----

    @Test
    public void shouldReturn403_whenForeignHostOnMcp() throws Exception {
        transportConfig.startServer(0, LOOPBACK);
        int port = transportConfig.getPort();
        assertEquals(403, rawRequest(port, "/mcp", "evil.attacker.com:" + port, null).status);
    }

    @Test
    public void shouldReturn403_whenForeignHostOnSse() throws Exception {
        transportConfig.startServer(0, LOOPBACK);
        int port = transportConfig.getPort();
        assertEquals(403, rawRequest(port, "/sse", "evil.attacker.com:" + port, null).status);
    }

    @Test
    public void shouldReturn403_whenForeignOriginOnMcp() throws Exception {
        transportConfig.startServer(0, LOOPBACK);
        int port = transportConfig.getPort();
        // Loopback Host but a cross-origin web Origin — the rebinding-page shape.
        Response resp = rawRequest(port, "/mcp", LOOPBACK + ":" + port, "https://evil.example");
        assertEquals(403, resp.status);
    }

    @Test
    public void shouldReachServlet_whenLoopbackHostOnMcp() throws Exception {
        transportConfig.startServer(0, LOOPBACK);
        int port = transportConfig.getPort();
        // The transport servlet may answer 200/400/405 for a bare GET — the point is it is NOT 403,
        // i.e. the request passed the guard and reached the servlet.
        assertNotEquals(403, rawRequest(port, "/mcp", LOOPBACK + ":" + port, null).status);
    }

    @Test
    public void shouldReachServlet_whenLoopbackHostOnSse() throws Exception {
        transportConfig.startServer(0, LOOPBACK);
        int port = transportConfig.getPort();
        assertNotEquals(403, rawRequest(port, "/sse", LOOPBACK + ":" + port, null).status);
    }

    @Test
    public void shouldReturnJsonRpcEnvelope_whenRejected() throws Exception {
        transportConfig.startServer(0, LOOPBACK);
        int port = transportConfig.getPort();
        Response resp = rawRequest(port, "/mcp", "evil.attacker.com:" + port, null);

        assertEquals(403, resp.status);
        assertTrue("content-type should be JSON: " + resp.contentType,
                resp.contentType != null && resp.contentType.toLowerCase().contains("application/json"));
        // JSON-RPC envelope shape from JsonErrorHandler: 403 -> -32600, data.httpStatus:403.
        assertTrue("body should be a JSON-RPC error: " + resp.body, resp.body.contains("\"jsonrpc\":\"2.0\""));
        assertTrue("body should carry -32600: " + resp.body, resp.body.contains("-32600"));
        assertTrue("body should carry httpStatus 403: " + resp.body, resp.body.contains("\"httpStatus\":403"));
        assertFalse("body must not be an HTML error page: " + resp.body, resp.body.contains("<html"));
    }

    // ---- Raw HTTP/1.1 helper (full control of Host/Origin headers) ----

    private Response rawRequest(int port, String path, String hostHeader, String originHeader) throws IOException {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(LOOPBACK, port), 3000);
            sock.setSoTimeout(5000);

            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(hostHeader).append("\r\n");
            if (originHeader != null) {
                req.append("Origin: ").append(originHeader).append("\r\n");
            }
            req.append("Accept: */*\r\n");
            req.append("Connection: close\r\n");
            req.append("\r\n");

            OutputStream out = sock.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
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
        // "HTTP/1.1 403 Forbidden"
        String[] parts = statusLine.split(" ", 3);
        resp.status = parts.length >= 2 ? Integer.parseInt(parts[1]) : -1;

        // Headers until blank line.
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-type")) {
                resp.contentType = line.substring(colon + 1).trim();
            }
        }

        // Body (best-effort; Connection: close means the stream ends at EOF). Avoid hanging on
        // streaming endpoints by reading only what is already available / until close or limit.
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
    }
}
