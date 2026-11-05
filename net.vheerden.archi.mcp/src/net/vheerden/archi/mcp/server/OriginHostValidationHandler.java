package net.vheerden.archi.mcp.server;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jetty core {@link Handler.Wrapper} that rejects HTTP requests whose {@code Host} (and
 * {@code Origin}, when present) headers are not on a loopback allowlist, returning {@code 403}
 * before the request reaches either MCP transport servlet.
 *
 * <p><b>Why this exists (DNS-rebinding defense).</b> The server binds loopback by default, which
 * is not reachable from the network. The residual vector is DNS-rebinding: a web page the user
 * visits resolves an attacker-controlled domain to {@code 127.0.0.1} and {@code fetch()}es
 * {@code http://attacker-domain:18090/mcp}. The browser's same-origin policy does not stop the
 * <em>request</em> from being sent — only the response <em>read</em> — but an MCP tool call
 * (delete model, write file) has already executed server-side by then. The defense the MCP
 * transport spec (2025-06-18) prescribes for local HTTP servers is to reject any request whose
 * {@code Host} is not a known-loopback name (and any present {@code Origin} that is not loopback).</p>
 *
 * <p><b>Decision rules.</b></p>
 * <ul>
 *   <li>{@code Host} is the always-checked guard: a missing/blank {@code Host} or a {@code Host}
 *       whose host-part is not on the allowlist is rejected. HTTP/1.1 mandates {@code Host}.</li>
 *   <li>{@code Origin} is validated only when present and non-blank — non-browser MCP clients
 *       (Claude CLI, mcp-proxy, Cline) never send it, and the DNS-rebinding vector requires a
 *       browser origin. A present {@code Origin} must be loopback; the literal {@code Origin: null}
 *       (sandboxed/{@code file://} origins) is rejected.</li>
 *   <li>The allowlist is the lowercased loopback literals {@code localhost}, {@code 127.0.0.1},
 *       {@code ::1}, plus the host-part of the configured bind address. No preference, no UI.</li>
 *   <li>When the bind is {@linkplain BindExposure#EXPOSED exposed} (a deliberate, owner-warned
 *       LAN/{@code 0.0.0.0} bind), header validation is relaxed to pass-through and a single WARN
 *       line is logged — DNS-rebinding is a loopback-only threat, and silently 403-ing the user's
 *       own LAN access would be a surprising breakage. Such a deployment is bounded by the
 *       bind-address exposure warning and opt-in token auth. A bind that cannot be classified
 *       ({@link BindExposure#UNKNOWN}) enforces: that is the closed answer for this guard.</li>
 * </ul>
 *
 * <p>Mirrors {@link JsonErrorHandler}: it lives in the {@code server/} package and touches only
 * Jetty types (never Jackson). The 403 is emitted via {@link Response#writeError} so the server's
 * configured {@link JsonErrorHandler} renders a JSON-RPC error envelope, identical in shape to
 * every other HTTP-level error (4xx &rarr; {@code -32600}, {@code data.httpStatus: 403}).</p>
 */
public class OriginHostValidationHandler extends Handler.Wrapper {

    private static final Logger logger = LoggerFactory.getLogger(OriginHostValidationHandler.class);

    /** Loopback literals always on the allowlist (lowercased). */
    private static final Set<String> LOOPBACK_LITERALS = Set.of("localhost", "127.0.0.1", "::1");

    private final Set<String> allowlist;

    /**
     * Whether header validation is enforced: {@link BindExposure#enforcesOriginHost()} of the bind's
     * classification. True for a loopback bind (the default and the actual DNS-rebinding attack
     * surface) and for one that cannot be classified; false for a deliberate exposed bind, in which
     * case the guard passes every request through.
     */
    private final boolean enforcing;

    /**
     * Classifies {@code bindAddress} itself. For a guard built outside the server's start path; the
     * server hands the classification it already made to the three-argument constructor instead.
     */
    OriginHostValidationHandler(Handler wrapped, String bindAddress) {
        this(wrapped, bindAddress, BindExposure.of(bindAddress));
    }

    /**
     * @param wrapped     the downstream handler (the servlet context holding both transports)
     * @param bindAddress the effective bind address the server is starting on; its host part joins
     *                    the allowlist
     * @param exposure    how the server classified that bind as it started; the guard enforces
     *                    exactly when {@link BindExposure#enforcesOriginHost()} says so
     */
    public OriginHostValidationHandler(Handler wrapped, String bindAddress, BindExposure exposure) {
        super(wrapped);

        Set<String> hosts = new HashSet<>(LOOPBACK_LITERALS);
        String bindHost = hostPart(bindAddress);
        // Never add a wildcard bind specifier ("0.0.0.0" / "::") to the allowlist: it is not a
        // connectable host and would be a latent bypass if enforcement were ever turned on for a
        // wildcard bind. (Harmless today because a wildcard bind is non-loopback => not enforced.)
        if (bindHost != null && !bindHost.isEmpty()
                && !bindHost.equals("0.0.0.0") && !bindHost.equals("::")) {
            hosts.add(bindHost);
        }
        this.allowlist = Set.copyOf(hosts);
        this.enforcing = java.util.Objects.requireNonNull(exposure, "exposure").enforcesOriginHost();

        if (!enforcing) {
            // Disabling a security control warrants WARN, not INFO.
            logger.warn("Origin/Host validation DISABLED: server bound to non-loopback address '{}'. "
                    + "DNS-rebinding header validation is not enforced for this bind; rely on network/"
                    + "firewall controls and enable bearer-token auth (opt-in) for this deployment.", bindAddress);
        }
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        if (!enforcing) {
            return super.handle(request, response, callback);
        }

        // Single-value semantics are guaranteed by Jetty's HttpParser, which rejects a request
        // carrying more than one Host header with a 400 before it reaches this handler (RFC 7230
        // §5.4); .get(...) therefore returns the sole Host value, not an attacker-smuggled second one.
        String host = request.getHeaders().get(HttpHeader.HOST);
        String origin = request.getHeaders().get(HttpHeader.ORIGIN);

        if (isAllowed(host, origin)) {
            return super.handle(request, response, callback);
        }

        String uri = request.getHttpURI() != null ? request.getHttpURI().toString() : "<unknown>";
        logger.warn("Rejected request with disallowed Host/Origin (403): Host={}, Origin={}, uri={}",
                host, origin, uri);

        // Route through the server's configured JsonErrorHandler so the 403 body is a JSON-RPC
        // envelope (403 -> 4xx -> -32600, data.httpStatus:403), consistent with all other errors.
        // This wrapper runs ABOVE the ServletContextHandler, so request.getContext() resolves to the
        // server's default context and writeError(...) dispatches to jettyServer's JsonErrorHandler.
        Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "Origin/Host not allowed");
        return true;
    }

    /**
     * The allowlist decision, Jetty-free for direct unit testing (mirrors
     * {@link JsonErrorHandler#mapHttpStatusToJsonRpcCode}).
     *
     * @param hostHeader   the raw {@code Host} header value (may be null)
     * @param originHeader the raw {@code Origin} header value (may be null)
     * @return true if the request is allowed to reach the transport servlet
     */
    boolean isAllowed(String hostHeader, String originHeader) {
        // Host is always required and must be on the allowlist (HTTP/1.1 mandates Host).
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostPart(hostHeader);
        if (host == null || host.isEmpty() || !allowlist.contains(host)) {
            return false;
        }

        // Origin is validated only when present and non-blank.
        if (originHeader != null && !originHeader.isBlank()) {
            // The literal "null" origin (sandboxed iframe / file://) is never loopback.
            if (originHeader.trim().equalsIgnoreCase("null")) {
                return false;
            }
            String origin = hostPart(originHeader);
            if (origin == null || origin.isEmpty() || !allowlist.contains(origin)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Extracts the lowercased host from a {@code Host} header ({@code host[:port]} /
     * {@code [ipv6]:port}) or an {@code Origin} URL ({@code scheme://host[:port][/path]}).
     *
     * <p>Strips any scheme, path, surrounding IPv6 brackets, and a numeric {@code :port} suffix.
     * Returns {@code null} for null input and {@code ""} for blank input.</p>
     *
     * <p><b>Fail-closed by design.</b> This is a deliberately narrow parser, not a general URL
     * normaliser. Exotic / non-canonical forms are returned verbatim and therefore do NOT match any
     * loopback allowlist entry, so they are rejected rather than parsed permissively: {@code userinfo}
     * (e.g. {@code http://evil@localhost}), IPv6 zone IDs ({@code [::1%25lo]}), IPv4-mapped IPv6
     * ({@code [::ffff:127.0.0.1]}), trailing-dot FQDNs ({@code localhost.}), and bare unbracketed
     * IPv6 in a {@code Host} header (which RFC 7230 §5.4 forbids and Jetty normalises) are all
     * rejected. No legitimate loopback client (Claude CLI, Cline, mcp-proxy) sends these, so there is
     * no breakage; the bare unbracketed {@code ::1} form is only expected for the {@code bindAddress}
     * argument, never for a wire {@code Host} header.</p>
     *
     * @param value a {@code Host} header value or an {@code Origin} URL
     * @return the lowercased host-part, or null/empty for null/blank input
     */
    static String hostPart(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return "";
        }

        // Strip scheme (Origin URLs: scheme://host...).
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }

        // Strip path/query (anything from the first '/').
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        if (s.isEmpty()) {
            return "";
        }

        // Bracketed IPv6: take the literal inside the brackets, ignore any :port that follows.
        if (s.charAt(0) == '[') {
            int close = s.indexOf(']');
            return close > 1 ? s.substring(1, close) : s.substring(1);
        }

        // Strip a trailing :port only when the suffix after the (single) ':' is numeric.
        int colon = s.indexOf(':');
        if (colon >= 0) {
            String suffix = s.substring(colon + 1);
            if (!suffix.isEmpty() && suffix.chars().allMatch(c -> c >= '0' && c <= '9')) {
                s = s.substring(0, colon);
            }
        }

        return s;
    }

    /** True if header validation is enforced for this bind (a loopback bind, or one that could not be classified). */
    boolean isEnforcing() {
        return enforcing;
    }

    /** Package-visible for assertions in tests. */
    Set<String> allowlist() {
        return allowlist;
    }
}
