package net.vheerden.archi.mcp.server;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default-on Jetty core {@link Handler.Wrapper} that rejects a {@code POST} request whose body bytes
 * are <em>not well-formed UTF-8</em> — returning {@code 415 Unsupported Media Type} before the
 * mis-encoded content can be decoded downstream and persisted as corrupted text.
 *
 * <p><b>Why this exists (and why the header check is not enough).</b> A sibling guard
 * ({@link RequestCharsetValidationHandler}) already rejects a request that <em>declares</em> a
 * non-UTF-8 {@code Content-Type} charset. But the more common real-world corruption comes from a
 * client that declares <em>no</em> charset (or falsely declares {@code utf-8}) while actually
 * sending bytes in a legacy single-byte codepage (ISO-8859-1 / Windows-1252). The transport servlet
 * reads the body through a character {@code Reader}; with no declared charset the servlet decodes
 * with a permissive default that never fails on bad bytes, so the corruption is silently turned into
 * replacement characters and stored faithfully — surfacing only later as mojibake. This guard makes
 * that failure explicit and diagnosable at the boundary where it belongs. It is defense-in-depth:
 * the bytes are already broken on the wire (a client encoding mistake), not a server defect.</p>
 *
 * <p><b>The rule is well-formedness, not a replacement-character scan.</b> The check tests whether
 * the raw body bytes decode as strict UTF-8 (a {@link CharsetDecoder} configured to
 * {@link CodingErrorAction#REPORT} malformed and unmappable input). It deliberately does <em>not</em>
 * scan the decoded text for the U+FFFD replacement character: a body that is correctly UTF-8 encoded
 * may legitimately <em>contain</em> a genuine U+FFFD (the valid 3-byte sequence {@code EF BF BD}),
 * and rejecting that would break a conformant client. Only bytes that are not valid UTF-8 at all are
 * rejected — which is exactly the mis-encoded-codepage case, and never a real UTF-8 body.</p>
 *
 * <p><b>How it works.</b> Only {@code POST} requests carry a body on the MCP transports; every other
 * method (the long-lived {@code GET} event streams, session {@code DELETE}s) passes straight through
 * untouched. For a {@code POST}, the body is read once into a bounded buffer (the read is performed
 * through the outer {@code SizeLimitHandler}, so an oversized body still raises the normal {@code 413}
 * — this guard never lifts that cap), validated, and then <em>replayed</em> to the servlet via a
 * {@link Request.Wrapper} so the downstream read sees the identical bytes. A malformed body is
 * rejected via {@link Response#writeError} so the server's {@link JsonErrorHandler} renders the same
 * JSON-RPC envelope as every other boundary error (415 &rarr; {@code -32600},
 * {@code data.httpStatus: 415}).</p>
 *
 * <p><b>Chain position.</b> Innermost, just above the servlet context and inside
 * {@link RequestCharsetValidationHandler}: a declared-bad-charset is rejected by the cheaper
 * header-only check first, and only an otherwise-acceptable {@code POST} is buffered and byte-scanned
 * here. Like its siblings it is a request-correctness gate (not a denial-of-service gate), so it runs
 * after the security gates — an unauthenticated / bad-origin client receives {@code 401}/{@code 403}
 * first and never reaches this code.</p>
 *
 * <p><b>Default-on with an opt-out kill switch.</b> The rule never false-rejects a conformant client
 * (a valid UTF-8 body — including one containing a genuine U+FFFD — always passes), so it enforces by
 * default. Setting {@code -Darchi.mcp.scanBodyUtf8=false} disables enforcement (every body passes
 * through unscanned), as a safety valve. The switch is independent of the header-charset guard's
 * {@code archi.mcp.rejectNonUtf8} so the two can be toggled separately.</p>
 *
 * <p>Mirrors {@link RequestCharsetValidationHandler} / {@link BearerTokenAuthHandler}: it lives in the
 * {@code server/} package, touches only Jetty/JDK types (never Jackson), and exposes a Jetty-free
 * package-visible decision method ({@link #isWellFormedUtf8(ByteBuffer)}) for unit testing.</p>
 */
public class RequestBodyUtf8ValidationHandler extends Handler.Wrapper {

    private static final Logger logger = LoggerFactory.getLogger(RequestBodyUtf8ValidationHandler.class);

    /**
     * JVM system property that disables this control. Default-on: enforcement is active unless this
     * property is explicitly set to {@code "false"} (case-insensitive). Absent or any other value
     * enforces. Independent of {@link RequestCharsetValidationHandler}'s switch.
     */
    static final String DISABLE_PROPERTY = "archi.mcp.scanBodyUtf8";

    /** Whether the UTF-8 body rule is enforced. False only when disabled via the kill switch. */
    private final boolean enforcing;

    /**
     * Production constructor: resolves the {@code enforcing} flag from the kill-switch system
     * property (default-on).
     *
     * @param wrapped the downstream handler (the servlet context holding both transports)
     */
    public RequestBodyUtf8ValidationHandler(Handler wrapped) {
        this(wrapped, isEnforcingFromProperty());
    }

    /**
     * Test-visible constructor: injects the {@code enforcing} flag directly so unit tests never
     * depend on (or mutate) the live JVM system property.
     *
     * @param wrapped   the downstream handler (may be {@code null} for pure-unit decision tests)
     * @param enforcing whether the UTF-8 body rule is enforced
     */
    RequestBodyUtf8ValidationHandler(Handler wrapped, boolean enforcing) {
        super(wrapped);
        this.enforcing = enforcing;
        if (!enforcing) {
            logger.info("Request body UTF-8 validation DISABLED via -D{}=false: request bodies will "
                    + "be passed through without an encoding check.", DISABLE_PROPERTY);
        }
    }

    /** Default-on: enforce unless the property is explicitly the string {@code "false"}. */
    private static boolean isEnforcingFromProperty() {
        String value = System.getProperty(DISABLE_PROPERTY);
        return value == null || !value.equalsIgnoreCase("false");
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        // Only POST carries a body on the MCP transports; never touch the long-lived GET event
        // streams or session DELETEs, and skip entirely when disabled.
        if (!enforcing || !HttpMethod.POST.is(request.getMethod())) {
            return super.handle(request, response, callback);
        }

        // Read the whole body once. The read goes through the outer SizeLimitHandler, so an oversized
        // body raises the usual 413 here (propagated, not swallowed) — this guard never lifts the cap.
        ByteBuffer body = Content.Source.asByteBuffer(request);

        // isWellFormedUtf8 does not consume the buffer, so body stays at position 0 for replay.
        if (!isWellFormedUtf8(body)) {
            String uri = request.getHttpURI() != null ? request.getHttpURI().toString() : "<unknown>";
            logger.warn("Rejected request with a body that is not valid UTF-8 (415): uri={}", uri);
            Response.writeError(request, response, callback, HttpStatus.UNSUPPORTED_MEDIA_TYPE_415,
                    "Request body must be valid UTF-8");
            return true;
        }

        // Valid: replay the buffered bytes to the servlet unchanged.
        return super.handle(new BufferedContentRequest(request, body), response, callback);
    }

    /**
     * The body-encoding decision, Jetty-free for direct unit testing (mirrors
     * {@link RequestCharsetValidationHandler#isAcceptableCharset}).
     *
     * <p>Returns {@code true} iff the bytes are well-formed UTF-8 — a strict decode with
     * {@link CodingErrorAction#REPORT} for both malformed and unmappable input. An empty body is
     * well-formed. A body containing the genuine U+FFFD replacement character (bytes
     * {@code EF BF BD}) is well-formed and accepted; only bytes that are not valid UTF-8 (e.g. an
     * ISO-8859-1 / Windows-1252 high byte not forming a valid sequence, or a lone continuation byte)
     * return {@code false}.</p>
     *
     * <p>Does <b>not</b> consume the argument: it decodes a {@link ByteBuffer#duplicate()} internally,
     * so the caller's buffer keeps its position/limit and can be replayed to the servlet afterwards.</p>
     *
     * @param bytes the raw request body bytes
     * @return true if the bytes are valid UTF-8
     */
    static boolean isWellFormedUtf8(ByteBuffer bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(bytes.duplicate());
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Convenience overload for the pure-unit tests. */
    static boolean isWellFormedUtf8(byte[] bytes) {
        return isWellFormedUtf8(ByteBuffer.wrap(bytes));
    }

    /** True if the UTF-8 body rule is enforced for this wrapper. Package-visible for tests. */
    boolean isEnforcing() {
        return enforcing;
    }

    /**
     * A {@link Request.Wrapper} that re-presents a fully-buffered body to the downstream handler.
     * Everything except content reading is inherited from the wrapped request (headers, method, URI),
     * so the servlet sees an identical request; only the content source is redirected to the validated
     * buffer. {@link #getLength()} is overridden to report the buffered byte count so the wrapper is
     * self-consistent even for a chunked request, whose wrapped {@code getLength()} would be -1.
     */
    private static final class BufferedContentRequest extends Request.Wrapper {

        private final Content.Source replay;

        BufferedContentRequest(Request wrapped, ByteBuffer body) {
            super(wrapped);
            this.replay = Content.Source.from(body);
        }

        @Override
        public long getLength() {
            return replay.getLength();
        }

        @Override
        public Content.Chunk read() {
            return replay.read();
        }

        @Override
        public void demand(Runnable demandCallback) {
            replay.demand(demandCallback);
        }

        @Override
        public void fail(Throwable failure) {
            replay.fail(failure);
        }
    }
}
