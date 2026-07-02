package net.vheerden.archi.mcp.server;

import java.util.Locale;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default-on Jetty core {@link Handler.Wrapper} that rejects a request whose {@code Content-Type}
 * header <em>declares</em> a body charset that is not UTF-8 (e.g. {@code charset=iso-8859-1} or
 * {@code charset=utf-16}), returning {@code 415 Unsupported Media Type} before the request reaches
 * either MCP transport servlet.
 *
 * <p><b>Why this exists.</b> JSON-RPC 2.0, the MCP transport spec, and RFC 8259 §8.1 all mandate
 * that JSON text be encoded in UTF-8. A client that announces a different charset is already
 * non-conformant; decoding such a body as UTF-8 downstream would silently produce mojibake or a
 * confusing parse error. Rejecting at the boundary makes the failure explicit and early.</p>
 *
 * <p><b>The rule is deliberately narrow (header-only).</b> The check inspects only the
 * <em>declared</em> charset parameter — it never reads, buffers, or blocks on the request body, so
 * it composes cleanly with the asynchronous transport servlets and the {@code SizeLimitHandler}:</p>
 * <ul>
 *   <li>no {@code Content-Type} header, or a {@code Content-Type} with <b>no</b> charset parameter
 *       (the common case for {@code application/json}, whose media type carries no charset) &rarr;
 *       <b>pass through</b>;</li>
 *   <li>charset present and equal to {@code utf-8} / {@code utf8} (case-insensitive) &rarr;
 *       <b>pass through</b>;</li>
 *   <li>charset present and anything else &rarr; <b>415</b>.</li>
 * </ul>
 *
 * <p>Scanning the actual body bytes for invalid-UTF-8 / replacement characters to catch an
 * <em>undeclared</em> mis-encoded body is intentionally out of scope: it is a heuristic, requires
 * decoding the whole body in the boundary layer, and risks false-rejecting lenient clients.</p>
 *
 * <p><b>Chain position.</b> This wrapper is the innermost guard (just above the servlet context),
 * inside the bearer-token and Origin/Host guards. A charset reject is a request-correctness gate,
 * not a denial-of-service gate, so it runs <em>after</em> the security gates — an unauthenticated
 * or bad-origin client receives {@code 401}/{@code 403} first and never learns this rule exists.
 * (The opposite of {@code SizeLimitHandler}, which is outermost precisely because heap-exhaustion
 * prevention must be unconditional.)</p>
 *
 * <p><b>Default-on with an opt-out kill switch.</b> The rule is safe by construction (no conformant
 * client declares a non-UTF-8 charset), so it enforces by default. Setting the JVM system property
 * {@code -Darchi.mcp.rejectNonUtf8=false} disables enforcement (every request passes through), as a
 * safety valve should some unforeseen lenient client need to be tolerated.</p>
 *
 * <p>Mirrors {@link BearerTokenAuthHandler} / {@link OriginHostValidationHandler}: it lives in the
 * {@code server/} package, touches only Jetty/JDK types (never Jackson), and exposes a Jetty-free
 * package-visible decision method ({@link #isAcceptableCharset(String)}) for unit testing. The 415
 * is emitted via {@link Response#writeError} so the server's configured {@link JsonErrorHandler}
 * renders a JSON-RPC error envelope identical in shape to every other HTTP-level error
 * (415 &rarr; {@code -32600}, {@code data.httpStatus: 415}).</p>
 */
public class RequestCharsetValidationHandler extends Handler.Wrapper {

    private static final Logger logger = LoggerFactory.getLogger(RequestCharsetValidationHandler.class);

    /**
     * JVM system property that disables this control. Default-on: enforcement is active unless this
     * property is explicitly set to {@code "false"} (case-insensitive). Any other value, or absence,
     * enforces.
     */
    static final String DISABLE_PROPERTY = "archi.mcp.rejectNonUtf8";

    /** Whether the UTF-8 charset rule is enforced. False only when disabled via the kill switch. */
    private final boolean enforcing;

    /**
     * Production constructor: resolves the {@code enforcing} flag from the kill-switch system
     * property (default-on).
     *
     * @param wrapped the downstream handler (the servlet context holding both transports)
     */
    public RequestCharsetValidationHandler(Handler wrapped) {
        this(wrapped, isEnforcingFromProperty());
    }

    /**
     * Test-visible constructor: injects the {@code enforcing} flag directly so unit tests never
     * depend on (or mutate) the live JVM system property.
     *
     * @param wrapped   the downstream handler (may be {@code null} for pure-unit decision tests)
     * @param enforcing whether the UTF-8 charset rule is enforced
     */
    RequestCharsetValidationHandler(Handler wrapped, boolean enforcing) {
        super(wrapped);
        this.enforcing = enforcing;
        if (!enforcing) {
            logger.info("Request charset validation DISABLED via -D{}=false: requests declaring a "
                    + "non-UTF-8 Content-Type charset will be passed through unchecked.", DISABLE_PROPERTY);
        }
    }

    /** Default-on: enforce unless the property is explicitly the string {@code "false"}. */
    private static boolean isEnforcingFromProperty() {
        String value = System.getProperty(DISABLE_PROPERTY);
        return value == null || !value.equalsIgnoreCase("false");
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        if (!enforcing) {
            return super.handle(request, response, callback);
        }

        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (isAcceptableCharset(contentType)) {
            return super.handle(request, response, callback);
        }

        String uri = request.getHttpURI() != null ? request.getHttpURI().toString() : "<unknown>";
        logger.warn("Rejected request declaring a non-UTF-8 Content-Type charset (415): "
                + "contentType={}, uri={}", contentType, uri);

        // Route through the server's configured JsonErrorHandler so the 415 body is a JSON-RPC
        // envelope (415 -> 4xx -> -32600, data.httpStatus:415), consistent with all other errors.
        Response.writeError(request, response, callback, HttpStatus.UNSUPPORTED_MEDIA_TYPE_415,
                "Request body charset must be UTF-8");
        return true;
    }

    /**
     * The charset decision, Jetty-free for direct unit testing (mirrors
     * {@link BearerTokenAuthHandler#isAuthorized} / {@link OriginHostValidationHandler#isAllowed}).
     *
     * <p>Accepts (returns {@code true}) when not enforcing, when the header is null/blank, when the
     * {@code Content-Type} carries no charset parameter, or when the declared charset is
     * {@code utf-8}/{@code utf8} (case-insensitive). Returns {@code false} only for a present,
     * non-UTF-8 charset.</p>
     *
     * <p>Charset extraction is delegated to Jetty's
     * {@link MimeTypes#getCharsetFromContentType(String)}, which handles parameter ordering, quoting,
     * and whitespace, lowercases the charset value, and returns {@code null} when no charset is
     * present. That method matches only the lowercase parameter name {@code charset=}; since RFC 7231
     * §3.1.1.1 makes parameter names case-insensitive, the header is lowercased first so a
     * mixed/upper-case {@code CHARSET=} cannot slip through as "no charset". Lowercasing the value too
     * is harmless (the UTF-8 comparison is already case-insensitive); the header is ASCII, so
     * {@link Locale#ROOT} is correct.</p>
     *
     * @param contentType the raw {@code Content-Type} header value (may be null)
     * @return true if the request is allowed to reach the transport servlet
     */
    boolean isAcceptableCharset(String contentType) {
        if (!enforcing) {
            return true;
        }
        if (contentType == null || contentType.isBlank()) {
            return true; // nothing declared
        }
        String charset = MimeTypes.getCharsetFromContentType(contentType.toLowerCase(Locale.ROOT));
        if (charset == null || charset.isBlank()) {
            return true; // no charset parameter => lenient accept
        }
        return charset.equalsIgnoreCase("utf-8") || charset.equalsIgnoreCase("utf8");
    }

    /** True if the UTF-8 charset rule is enforced for this wrapper. Package-visible for tests. */
    boolean isEnforcing() {
        return enforcing;
    }
}
