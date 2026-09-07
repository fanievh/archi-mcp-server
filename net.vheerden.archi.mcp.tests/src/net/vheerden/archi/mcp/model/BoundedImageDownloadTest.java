package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Headless tests for the bounded URL image download seam
 * ({@link ArchiModelAccessorImpl#downloadBoundedBytes}). Drives the seam against a real
 * loopback {@link HttpServer} (JDK-bundled, no new dependency, no EMF/OSGi/SWT) so every
 * bound is proven in the standard JUnit lane — the method's {@code requireAndCaptureModel()}
 * wrapper makes the full {@code addImageFromUrl} untestable offline, so the fetch logic was
 * lifted into this package-private seam (the "pure core" discipline), which is what gives the
 * download bounds automated coverage at all — before the lift, nothing exercised them.
 */
public class BoundedImageDownloadTest {

    // Reference the production constant directly so the test self-corrects if the cap ever changes.
    private static final long CAP = ArchiModelAccessorImpl.MAX_IMAGE_BYTES;
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private HttpServer server;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Registers a handler at {@code /img} and returns its absolute URL. */
    private String register(HttpHandler handler) {
        server.createContext("/img", handler);
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/img";
    }

    private static HttpRequest get(String url, Duration requestTimeout) {
        return HttpRequest.newBuilder().uri(URI.create(url)).timeout(requestTimeout).GET().build();
    }

    private static byte[] download(String url, long maxBytes, Duration deadline)
            throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            return ArchiModelAccessorImpl.downloadBoundedBytes(
                    client, get(url, Duration.ofSeconds(10)), maxBytes, deadline, url);
        }
    }

    // ---- in-bounds body downloads and returns the exact bytes ----------------------

    @Test
    public void shouldReturnExactBytes_whenBodyInBounds() throws Exception {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        String url = register(exchange -> {
            exchange.sendResponseHeaders(200, payload.length); // declares Content-Length
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });

        byte[] result = download(url, CAP, DEADLINE);

        assertArrayEquals("in-bounds body must round-trip byte-for-byte", payload, result);
    }

    @Test
    public void shouldAcceptBody_whenExactlyAtCap() throws Exception {
        long smallCap = 8192L;
        byte[] payload = new byte[(int) smallCap]; // exactly the cap — old `> cap` check allowed this
        String url = register(exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });

        byte[] result = download(url, smallCap, DEADLINE);

        assertEquals("a body of exactly the cap must be accepted", payload.length, result.length);
    }

    // ---- oversized body WITH Content-Length is rejected at the cap ------------------

    @Test
    public void shouldReject_whenBodyExceedsCapWithContentLength() throws Exception {
        long smallCap = 4096L;
        byte[] payload = new byte[(int) smallCap + 1]; // one byte over
        String url = register(exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });

        try {
            download(url, smallCap, DEADLINE);
            fail("expected ModelAccessException for an oversized body");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("message must name the 1MB limit", e.getMessage().contains("1MB limit"));
            assertNotNull("must carry the smaller-image suggestedCorrection", e.getSuggestedCorrection());
        }
    }

    // ---- oversized CHUNKED / no-Content-Length body is rejected (the OOM vector) ----

    @Test
    public void shouldReject_whenOversizedChunkedNoContentLength() throws Exception {
        long smallCap = 4096L;
        final long intendedBytes = 8L * 1024 * 1024; // ~8 MB the server tries to send
        java.util.concurrent.atomic.AtomicLong written = new java.util.concurrent.atomic.AtomicLong();
        // sendResponseHeaders(200, 0) => unknown length => chunked transfer, NO Content-Length.
        // The body is far larger than the cap; under the old ofByteArray() path this would be
        // fully buffered into the heap (the OOM vector). The seam must abort at the cap without
        // reading it all.
        String url = register(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                byte[] chunk = new byte[8192];
                for (long sent = 0; sent < intendedBytes; sent += chunk.length) {
                    os.write(chunk);
                    os.flush();
                    written.addAndGet(chunk.length);
                }
            } catch (IOException expectedBrokenPipe) {
                // client aborted at the cap and closed the stream — exactly the desired behavior
            }
        });

        try {
            download(url, smallCap, DEADLINE);
            fail("expected ModelAccessException for an oversized chunked body");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("message must name the 1MB limit", e.getMessage().contains("1MB limit"));
        }
        // Prove the OOM vector is closed: the client aborted early, so the server never managed
        // to push anywhere near the full ~8 MB before the connection was torn down. (A generous
        // bound that tolerates socket send-buffer slack while still proving "did not read it all".)
        assertTrue("download must abort early, not buffer the whole body (server wrote "
                + written.get() + " bytes)", written.get() < intendedBytes / 2);
    }

    // ---- a slow-drip / never-completing body trips the deadline, not a hang ---------

    @Test
    public void shouldTripDeadline_whenBodyTrickles() throws Exception {
        // Chunked body that trickles one byte at a time, sleeping between writes, forever.
        // The per-read deadline check must abort it well before any indefinite hang.
        String url = register(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (int i = 0; i < 10_000; i++) {
                    os.write(new byte[] {1});
                    os.flush();
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException expected) {
                // client aborted at the deadline
            }
        });

        long start = System.nanoTime();
        try {
            download(url, CAP, Duration.ofMillis(400)); // generous cap, tiny deadline
            fail("expected HttpTimeoutException when the body trickles past the deadline");
        } catch (HttpTimeoutException e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue("must abort near the deadline, not hang (elapsed=" + elapsedMs + "ms)",
                    elapsedMs < 5_000);
            // Assert it was the download DEADLINE that fired, not the 10s per-request timeout —
            // otherwise the test could pass via the wrong mechanism.
            assertNotNull("deadline exception must carry a message", e.getMessage());
            assertTrue("must be the download-deadline path, not the request timeout (msg=\""
                    + e.getMessage() + "\")", e.getMessage().contains("Download exceeded deadline of"));
        }
    }

    // ---- non-200 status and empty body map to their existing errors -----------------

    @Test
    public void shouldMapNon200_whenServerReturnsError() throws Exception {
        String url = register(exchange -> {
            exchange.sendResponseHeaders(404, -1); // -1 => no response body
            exchange.close();
        });

        try {
            download(url, CAP, DEADLINE);
            fail("expected ModelAccessException for a non-200 status");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
            assertTrue("message must report the HTTP status", e.getMessage().contains("404"));
        }
    }

    @Test
    public void shouldMapEmptyBody_whenServerReturnsNoBytes() throws Exception {
        String url = register(exchange -> {
            exchange.sendResponseHeaders(200, -1); // 200 OK, but zero bytes of body
            exchange.close();
        });

        try {
            download(url, CAP, DEADLINE);
            fail("expected ModelAccessException for an empty body");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INTERNAL_ERROR, e.getErrorCode());
            assertTrue("message must flag the empty body", e.getMessage().contains("Empty response body"));
        }
    }
}
