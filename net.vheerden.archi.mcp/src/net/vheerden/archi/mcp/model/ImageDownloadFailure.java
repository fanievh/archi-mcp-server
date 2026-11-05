package net.vheerden.archi.mcp.model;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Builds the errors of a URL image import, and decides whose fault each one is.
 *
 * <p>The decision is made by <em>phase</em>, not by exception type. Anything that goes wrong while
 * the bytes are being fetched (an HTTP error status, an empty body, a timeout, a refused
 * connection, a DNS or TLS failure, a reset) is the remote's or the URL's, and the only remedy a
 * caller has is in its input — another URL, or {@code filePath} with a local copy — so it is
 * answered {@code INVALID_PARAMETER}. Anything that goes wrong after the bytes arrived (the temp
 * file, the write, storing the image) is the server's own and stays {@code INTERNAL_ERROR}. The
 * type alone cannot tell them apart: a reset connection and a full temp disk are both a plain
 * {@link IOException}.</p>
 *
 * <p>An interrupted fetch is neither: it is the server cancelling its own work, so it stays
 * {@code INTERNAL_ERROR} and keeps the {@link InterruptedException} as its cause, which is what
 * lets {@link ToolErrorLog} log it without a stack.</p>
 *
 * <p>Lifted out of the accessor so it can be exercised: the full {@code addImageFromUrl} is awkward
 * to reach offline, and these are the arms a caller actually meets.</p>
 */
final class ImageDownloadFailure {

    private ImageDownloadFailure() {
    }

    /** The fetch phase of a URL import, as the accessor supplies it. */
    @FunctionalInterface
    interface Fetch {
        byte[] get() throws IOException, InterruptedException;
    }

    /**
     * Runs the fetch phase and maps how it failed. A {@link ModelAccessException} the fetch raises
     * itself (status, empty body, oversize) passes through untouched. The fetch should include
     * building the client and the request, so that nothing which can fail before the bytes arrive
     * is left to the post-fetch arm.
     */
    static byte[] fetch(String url, Fetch fetch) {
        try {
            return fetch.get();
        } catch (ModelAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            // Unchecked and before any bytes arrived: the server's fault, named as a download.
            throw duringFetch(url, e);
        } catch (HttpTimeoutException e) {
            throw new ModelAccessException(
                    "Timeout downloading image from URL: " + url,
                    e, ErrorCode.INVALID_PARAMETER,
                    e.getMessage(),
                    "The server did not respond within the timeout. Try again or use filePath with a locally downloaded file.",
                    null);
        } catch (InterruptedException e) {
            throw interrupted(url, e);
        } catch (IOException e) {
            // The body read reports an interrupt as an IOException; it is still a cancellation.
            if (e.getCause() instanceof InterruptedException
                    || e instanceof java.io.InterruptedIOException
                    || e instanceof java.nio.channels.ClosedByInterruptException
                    || Thread.currentThread().isInterrupted()) {
                InterruptedException cancelled = new InterruptedException(e.getMessage());
                cancelled.initCause(e);
                throw interrupted(url, cancelled);
            }
            throw forCause(url, e);
        }
    }

    private static ModelAccessException interrupted(String url, InterruptedException e) {
        // Restore the interrupt flag so cooperative shutdown (e.g. Jetty stopping its
        // workers) is not silently swallowed.
        Thread.currentThread().interrupt();
        return new ModelAccessException(
                "Image download interrupted: " + url,
                e, ErrorCode.INTERNAL_ERROR,
                e.getMessage(),
                "The download was interrupted. Try again.",
                null);
    }

    private static ModelAccessException duringFetch(String url, RuntimeException e) {
        String detail = detail(e);
        return new ModelAccessException(
                "Failed to download image from URL: " + url + " (" + detail + ")",
                e, ErrorCode.INTERNAL_ERROR, detail,
                "The server could not complete the download. Try again, or download the image "
                        + "locally and use filePath instead.",
                null);
    }

    /** The remote answered with a status other than 200. */
    static ModelAccessException forStatus(String url, int status) {
        return new ModelAccessException(
                "HTTP " + status + " downloading image from URL: " + url,
                ErrorCode.INVALID_PARAMETER,
                "HTTP status: " + status,
                "Check the URL is correct and the server is accessible.",
                null);
    }

    /** The remote answered 200 with no bytes. */
    static ModelAccessException emptyBody(String url) {
        return new ModelAccessException(
                "Empty response body from URL: " + url,
                ErrorCode.INVALID_PARAMETER,
                null,
                "The URL returned no data. Check the URL points to an image.",
                null);
    }

    /**
     * The fetch failed in transport, with no more specific arm: connection refused, DNS failure,
     * a TLS failure, a reset mid-body.
     */
    static ModelAccessException forCause(String url, Throwable cause) {
        String detail = detail(cause);
        return new ModelAccessException(
                "Failed to download image from URL: " + url + " (" + detail + ")",
                cause, ErrorCode.INVALID_PARAMETER, detail,
                "Check the URL is reachable and serves an image file. If it needs credentials or a "
                        + "browser session, download it locally and use filePath instead.",
                null);
    }

    /**
     * The bytes arrived, and storing them failed: the temp file, the write, the archive, or any
     * unexpected runtime fault. A server fault, whatever the exception type.
     */
    static ModelAccessException afterFetch(String url, Throwable cause) {
        String detail = detail(cause);
        return new ModelAccessException(
                "Failed to store image downloaded from URL: " + url + " (" + detail + ")",
                cause, ErrorCode.INTERNAL_ERROR, detail,
                "The image was downloaded but could not be stored on the server. Try again, or "
                        + "download it locally and use filePath instead.",
                null);
    }

    // A refused connection or a DNS failure arrives with no message of its own, so the cause
    // cannot be the only thing reported — and it must never stand in place of the URL, which is
    // what made a null read as though the caller had passed a null URL.
    private static String detail(Throwable cause) {
        return cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
    }
}
