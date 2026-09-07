package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for the batch form of add-image-to-model ({@code images} array).
 *
 * <p>An archive import is not undoable and not transactional — Archi's
 * {@code IArchiveManager} exposes no removal API, so an entry that lands stays landed
 * even if a later entry fails. A batch therefore has no all-or-nothing semantics to
 * report, and partial success is the only truthful outcome shape: every entry that
 * succeeded is reported with the archive path it actually landed at, and every entry
 * that failed is reported with the reason and its request index.</p>
 *
 * <p>Entries carry the caller's request {@code index} rather than their position in the
 * result list. With failures removed the two diverge, and only the request index lets a
 * caller map an archive path back to the file it asked to import.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddImagesResultDto(
    int requestedCount,
    int importedCount,
    int failedCount,
    List<ImportedImage> images,
    List<FailedImage> failures
) {

    /** One successfully imported image, with the effective archive path and detected geometry. */
    public record ImportedImage(
        int index,
        String imagePath,
        int width,
        int height,
        String formatDetected
    ) {}

    /** One rejected entry, with the caller's request index and the reason it was rejected. */
    public record FailedImage(
        int index,
        String message
    ) {}
}
