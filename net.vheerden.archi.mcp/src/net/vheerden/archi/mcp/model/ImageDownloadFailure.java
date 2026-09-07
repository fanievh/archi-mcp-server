package net.vheerden.archi.mcp.model;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Builds the error for a URL image import that failed for a reason with no more specific arm —
 * connection refused, DNS failure, a reset mid-body.
 *
 * <p>Lifted out of the accessor so it can be exercised: the full {@code addImageFromUrl} is awkward
 * to reach offline, and this is the arm most likely to be the one a caller actually meets.</p>
 */
final class ImageDownloadFailure {

    private ImageDownloadFailure() {
    }

    static ModelAccessException forCause(String url, Throwable cause) {
        // A refused connection or a DNS failure arrives with no message of its own, so the cause
        // cannot be the only thing reported — and it must never stand in place of the URL, which is
        // what made a null read as though the caller had passed a null URL.
        String detail = cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
        return new ModelAccessException(
                "Failed to download image from URL: " + url + " (" + detail + ")",
                ErrorCode.INTERNAL_ERROR, detail,
                "Check the URL is reachable and serves an image file. If it needs credentials or a "
                        + "browser session, download it locally and use filePath instead.",
                null);
    }
}
