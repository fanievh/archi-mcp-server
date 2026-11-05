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
 * Tests for {@link BearerTokenAuthHandler} — the opt-in bearer-token guard (the token half of the
 * loopback-exposure hardening).
 *
 * <p>Two layers, mirroring {@link OriginHostValidationHandlerTest}:</p>
 * <ul>
 *   <li><b>Pure-unit</b> — the {@code isAuthorized(...)} decision matrix, constructed without
 *       starting Jetty, plus a constant-time-equality sanity check and the disabled (null-token)
 *       all-pass case.</li>
 *   <li><b>HTTP-level</b> — start the real {@link TransportConfig} server <em>with</em> a token (via
 *       the additive 6-arg overload) and assert 401-vs-pass on both {@code /mcp} and
 *       {@code /sse} using a raw socket, since {@code java.net.http.HttpClient} forbids/normalises
 *       some headers and we need full control of {@code Authorization}.</li>
 * </ul>
 *
 * <p>Lives in the {@code server} package (NOT in {@code tools/osgi-excluded-tests.txt}), so it runs
 * headlessly in the {@code ci-junit} lane — "CI proves the security fix". The token is injected via
 * the constructor / start overload, so no secure-storage provider is needed here.</p>
 */
public class BearerTokenAuthHandlerTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String TOKEN = "s3cr3t-T0ken_AbcDef-0123456789_xyzXYZ";

    private final TransportConfig transportConfig = new TransportConfig();

    @After
    public void tearDown() {
        transportConfig.stopServer();
    }

    // A guard enforcing TOKEN. The wrapped Handler is null because the pure-unit tests exercise only
    // isAuthorized(...) / isEnforcing() and never call handle() — safe for that purpose only.
    private BearerTokenAuthHandler enforcingGuard() {
        return new BearerTokenAuthHandler(null, TOKEN);
    }

    // ---- Pure-unit: isAuthorized(...) decision matrix ----

    @Test
    public void shouldReject_whenNoAuthorizationHeader() {
        assertFalse(enforcingGuard().isAuthorized(null));
    }

    @Test
    public void shouldAllow_whenCorrectBearerToken() {
        assertTrue(enforcingGuard().isAuthorized("Bearer " + TOKEN));
    }

    @Test
    public void shouldReject_whenWrongToken() {
        assertFalse(enforcingGuard().isAuthorized("Bearer not-the-token"));
    }

    @Test
    public void shouldAllow_whenSchemeKeywordCaseDiffers() {
        // RFC 7235: the auth-scheme keyword is case-insensitive.
        assertTrue(enforcingGuard().isAuthorized("bearer " + TOKEN));
        assertTrue(enforcingGuard().isAuthorized("BEARER " + TOKEN));
        assertTrue(enforcingGuard().isAuthorized("BeArEr " + TOKEN));
    }

    @Test
    public void shouldReject_whenTokenValueCaseDiffers() {
        // The token VALUE is matched exactly (case-sensitive, full-length).
        assertFalse(enforcingGuard().isAuthorized("Bearer " + TOKEN.toUpperCase()));
        assertFalse(enforcingGuard().isAuthorized("Bearer " + TOKEN.toLowerCase()));
    }

    @Test
    public void shouldReject_whenNonBearerScheme() {
        assertFalse(enforcingGuard().isAuthorized("Basic " + TOKEN));
        assertFalse(enforcingGuard().isAuthorized("Token " + TOKEN));
        assertFalse(enforcingGuard().isAuthorized(TOKEN)); // bare token, no scheme
    }

    @Test
    public void shouldReject_whenBlankOrSchemeOnly() {
        assertFalse(enforcingGuard().isAuthorized(""));
        assertFalse(enforcingGuard().isAuthorized("   "));
        assertFalse(enforcingGuard().isAuthorized("Bearer"));
        assertFalse(enforcingGuard().isAuthorized("Bearer "));
        assertFalse(enforcingGuard().isAuthorized("Bearer    "));
    }

    @Test
    public void shouldReject_whenTokenIsPrefixOrSuffixOfExpected() {
        // Length-differing near-matches must fail (constant-time digest compare, not prefix match).
        assertFalse(enforcingGuard().isAuthorized("Bearer " + TOKEN.substring(0, TOKEN.length() - 1)));
        assertFalse(enforcingGuard().isAuthorized("Bearer " + TOKEN + "x"));
    }

    // ---- Pure-unit: disabled (null/blank token) => all pass ----

    @Test
    public void shouldNotEnforce_whenTokenNullOrBlank() {
        assertFalse(new BearerTokenAuthHandler(null, null).isEnforcing());
        assertFalse(new BearerTokenAuthHandler(null, "").isEnforcing());
        assertFalse(new BearerTokenAuthHandler(null, "   ").isEnforcing());
        assertTrue(new BearerTokenAuthHandler(null, TOKEN).isEnforcing());
    }

    @Test
    public void shouldAllowEverything_whenDisabled() {
        // Disabled handler authorizes every header, including a missing one (pass-through).
        BearerTokenAuthHandler disabled = new BearerTokenAuthHandler(null, null);
        assertTrue(disabled.isAuthorized(null));
        assertTrue(disabled.isAuthorized("Bearer anything"));
        assertTrue(disabled.isAuthorized("Basic whatever"));
        assertTrue(disabled.isAuthorized(""));
    }

    // ---- Pure-unit: fail-closed token resolution ----
    // TransportConfig.resolveAuthToken is the never-fail-open policy: when enabled it must NEVER
    // return null (which would disable enforcement) — it returns the stored token or an unmatchable
    // fresh sentinel. The Supplier abstracts the secure-storage read so this is testable headlessly.

    @Test
    public void shouldResolveNull_whenAuthDisabled() {
        assertNull(TransportConfig.resolveAuthToken(false, () -> "anything"));
        assertNull("disabled must not enforce even if a token happens to be readable",
                TransportConfig.resolveAuthToken(false, () -> TOKEN));
    }

    @Test
    public void shouldResolveStoredToken_whenEnabledAndPresent() {
        assertEquals(TOKEN, TransportConfig.resolveAuthToken(true, () -> TOKEN));
    }

    @Test
    public void shouldFailClosed_whenEnabledButTokenAbsent() {
        // enabled + null/blank stored token => non-null unmatchable sentinel, NEVER null (fail-open).
        String onNull = TransportConfig.resolveAuthToken(true, () -> null);
        String onBlank = TransportConfig.resolveAuthToken(true, () -> "   ");
        assertNotNull("enabled+absent must fail closed, not fail open", onNull);
        assertFalse(onNull.isBlank());
        assertNotNull("enabled+blank must fail closed, not fail open", onBlank);
        assertFalse(onBlank.isBlank());
    }

    @Test
    public void shouldFailClosed_whenEnabledButStoreUnreadable() {
        String t = TransportConfig.resolveAuthToken(true, () -> {
            throw new BearerTokenStore.BearerTokenStoreException("simulated unreadable secure store");
        });
        assertNotNull("enabled+unreadable must fail closed (non-null sentinel), never fail open (null)", t);
        assertFalse(t.isBlank());
    }

    @Test
    public void shouldProduceUnguessableSentinel_whenFailingClosed() {
        // The sentinel is freshly generated, so two fail-closed resolutions differ — an adversary
        // cannot know or replay it, and it never equals a value the client could present.
        String s1 = TransportConfig.resolveAuthToken(true, () -> null);
        String s2 = TransportConfig.resolveAuthToken(true, () -> null);
        assertNotEquals("fail-closed sentinels must be freshly generated (unguessable)", s1, s2);
    }

    // ---- HTTP-level: real server, raw socket, both transports ----

    @Test
    public void shouldReturn401_whenNoTokenOnMcp() throws Exception {
        transportConfig.startServer(0, LOOPBACK, false, null, null, TOKEN);
        int port = transportConfig.getPort();
        assertEquals(401, rawRequest(port, "/mcp", null).status);
    }

    @Test
    public void shouldReturn401_whenNoTokenOnSse() throws Exception {
        transportConfig.startServer(0, LOOPBACK, false, null, null, TOKEN);
        int port = transportConfig.getPort();
        assertEquals(401, rawRequest(port, "/sse", null).status);
    }

    @Test
    public void shouldReturn401_whenWrongTokenOnMcp() throws Exception {
        transportConfig.startServer(0, LOOPBACK, false, null, null, TOKEN);
        int port = transportConfig.getPort();
        assertEquals(401, rawRequest(port, "/mcp", "Bearer wrong-token").status);
    }

    @Test
    public void shouldReturn401_whenWrongTokenOnSse() throws Exception {
        transportConfig.startServer(0, LOOPBACK, false, null, null, TOKEN);
        int port = transportConfig.getPort();
        assertEquals(401, rawRequest(port, "/sse", "Bearer wrong-token").status);
    }

    @Test
    public void shouldReachServlet_whenCorrectTokenOnMcp() throws Exception {
        transportConfig.startServer(0, LOOPBACK, false, null, null, TOKEN);
        int port = transportConfig.getPort();
        // The transport servlet may answer 200/400/405 for a bare GET — the point is it is NOT 401,
        // i.e. the request passed the guard and reached the servlet.
        assertNotEquals(401, rawRequest(port, "/mcp", "Bearer " + TOKEN).status);
    }

    @Test
    public void shouldReachServlet_whenCorrectTokenOnSse() throws Exception {
        transportConfig.startServer(0, LOOPBACK, false, null, null, TOKEN);
        int port = transportConfig.getPort();
        assertNotEquals(401, rawRequest(port, "/sse", "Bearer " + TOKEN).status);
    }

    @Test
    public void shouldReachServlet_whenNoTokenAndAuthDisabled() throws Exception {
        // A server started without a token (the 5-arg path / opt-in default) never 401s.
        transportConfig.startServer(0, LOOPBACK);
        int port = transportConfig.getPort();
        assertNotEquals(401, rawRequest(port, "/mcp", null).status);
        assertNotEquals(401, rawRequest(port, "/sse", null).status);
    }

    @Test
    public void shouldReturnJsonRpcEnvelopeAndChallenge_whenRejected() throws Exception {
        transportConfig.startServer(0, LOOPBACK, false, null, null, TOKEN);
        int port = transportConfig.getPort();
        Response resp = rawRequest(port, "/mcp", null);

        assertEquals(401, resp.status);
        assertTrue("content-type should be JSON: " + resp.contentType,
                resp.contentType != null && resp.contentType.toLowerCase().contains("application/json"));
        // JSON-RPC envelope shape from JsonErrorHandler: 401 -> -32600, data.httpStatus:401.
        assertTrue("body should be a JSON-RPC error: " + resp.body, resp.body.contains("\"jsonrpc\":\"2.0\""));
        assertTrue("body should carry -32600: " + resp.body, resp.body.contains("-32600"));
        assertTrue("body should carry httpStatus 401: " + resp.body, resp.body.contains("\"httpStatus\":401"));
        assertFalse("body must not be an HTML error page: " + resp.body, resp.body.contains("<html"));
        // RFC 7235 challenge header.
        String wwwAuth = resp.header("www-authenticate");
        assertNotNull("401 should carry a WWW-Authenticate header", wwwAuth);
        assertTrue("WWW-Authenticate should name the Bearer scheme: " + wwwAuth,
                wwwAuth.toLowerCase().contains("bearer"));
    }

    @Test
    public void shouldNotLeakToken_whenRejected() throws Exception {
        // The secret must never appear in a rejection body.
        transportConfig.startServer(0, LOOPBACK, false, null, null, TOKEN);
        int port = transportConfig.getPort();
        Response resp = rawRequest(port, "/mcp", "Bearer wrong-presented-credential");
        assertEquals(401, resp.status);
        assertFalse("rejection body must not contain the expected token", resp.body.contains(TOKEN));
        // Also prove the PRESENTED credential is not echoed back (an echo-back regression would
        // leak whatever the client sent — here a distinctive marker that must be absent).
        assertFalse("rejection body must not echo the presented credential",
                resp.body.contains("wrong-presented-credential"));
    }

    // ---- Raw HTTP/1.1 helper (full control of the Authorization header) ----

    private Response rawRequest(int port, String path, String authHeader) throws IOException {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(LOOPBACK, port), 3000);
            sock.setSoTimeout(5000);

            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(LOOPBACK).append(":").append(port).append("\r\n");
            if (authHeader != null) {
                req.append("Authorization: ").append(authHeader).append("\r\n");
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
        // "HTTP/1.1 401 Unauthorized"
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

        String header(String lowerCaseName) {
            return headers.get(lowerCaseName);
        }
    }
}
