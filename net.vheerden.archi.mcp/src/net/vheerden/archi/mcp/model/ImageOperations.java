package net.vheerden.archi.mcp.model;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.eclipse.swt.graphics.ImageData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.IArchimateModel;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AddImageResultDto;
import net.vheerden.archi.mcp.response.dto.ModelImageDto;

/**
 * Model-side image operations: add an image to the model archive (from raw
 * bytes or a local file) and list the archive's images.
 *
 * <p>A cohesive cluster peeled out of {@code ArchiModelAccessorImpl} behind its
 * unchanged interface: the accessor keeps one-line forwards to an instance of
 * this class. The methods here reach the model only through the injected
 * {@code Supplier<IArchimateModel>} (the same seam pattern the accessor uses for
 * {@code MutationDispatcher}) plus {@code IArchiveManager} — no back-reference to
 * the accessor, no shared mutable state.</p>
 *
 * <p>Archive writes are not undoable (no command-stack entry); this matches
 * Archi's own behaviour — see {@link #storeImageFile}.</p>
 *
 * <p>Note: the URL-fetch path ({@code addImageFromUrl}) deliberately stays in the
 * accessor — its bounded streaming download is a separately unit-tested static
 * seam, and relocating it is out of scope for this behaviour-preserving move.
 * Its pure <em>extension derivation</em> does live here, in
 * {@link #extensionFromUrlPath}: that is string work with no I/O, it shares a
 * locale rule with {@link #detectFormat} that the two must not disagree on, and
 * inline in {@code addImageFromUrl} it could not be tested at all.</p>
 */
final class ImageOperations {

    private static final Logger logger = LoggerFactory.getLogger(ImageOperations.class);

    /**
     * The SVG condition, stated once for every image remediation in this package.
     *
     * <p>Reading an SVG is not an Archi feature — it is the host platform's image loader. SWT gained
     * an optional rasterizer fragment ({@code org.eclipse.swt.svg}, backed by JSVG) in the Eclipse
     * 2026-06 platform; with it present {@code new ImageData("….svg")} returns real pixels, and
     * without it the same call throws {@code SWTException}. Archi 5.10 rebased onto that platform
     * (Eclipse 4.40) and ships the fragment; Archi 5.9 is on Eclipse 4.32 and does not. Archi's own
     * history records the dependency directly: SVG import was added, reverted alongside a reverted
     * platform bump, and re-added once the platform returned, so 5.8 and 5.9 both carry the revert.</p>
     *
     * <p>The sentence deliberately does not name the bundle: Archi's own help states the boundary as a
     * version, and a bundle id is something an LLM caller cannot act on. It says "from 5.10 onward"
     * rather than "verified on 5.10", so it stays true at the next release.</p>
     *
     * <p>It states a <b>general property of the system</b>, never a diagnosis of the call in hand. Of
     * the four errors it is appended to, three fire when an image could not be loaded — for any
     * reason, so naming SVG as the cause would be a guess — and the fourth fires when the caller sent
     * no bytes at all, where a sentence about what failed to load would be nonsense. A rule holds at
     * all four; a diagnosis holds at none.</p>
     *
     * <p>{@code ImageHandler} carries the same three facts for the schema descriptions. That is a
     * second definition of one sentence, forced by the package boundary — {@code handlers/} cannot see
     * a package-private type in {@code model/} — not a deliberate difference. The two are held
     * together by asserting the same fact list on both surfaces rather than by sharing a reference.</p>
     */
    static final String SVG_HOST_SUPPORT_NOTE =
            "SVG is accepted only where the host supports it: Archi carries SVG from 5.10 onward and rejects "
                    + "it on every earlier release, and a model carrying SVG images can lose them when it is "
                    + "opened and re-saved on an older Archi — prefer PNG when the model must travel.";

    /** Supplies the currently active model, validating one is loaded (throws if none). */
    private final Supplier<IArchimateModel> modelSupplier;

    ImageOperations(Supplier<IArchimateModel> modelSupplier) {
        this.modelSupplier = modelSupplier;
    }

    AddImageResultDto addImageToModel(String sessionId, byte[] imageData, String filenameHint) {
        logger.info("Adding image to model: filenameHint={}, dataSize={}", filenameHint, imageData != null ? imageData.length : 0);
        IArchimateModel model = modelSupplier.get();

        if (imageData == null || imageData.length == 0) {
            throw new ModelAccessException(
                    "Image data must not be empty",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide base64-encoded image data (PNG, JPEG, GIF, BMP, ICO, TIFF). " + SVG_HOST_SUPPORT_NOTE,
                    null);
        }

        if (filenameHint == null || filenameHint.isBlank()) {
            filenameHint = "image.png";
        }

        // Detect extension from filename hint
        String ext = extensionFromFileName(filenameHint);
        // Refuse an unusable suffix here, above the try, rather than letting it fail inside it.
        // A character that cannot name a file makes File.createTempFile throw, and that throw lands
        // in the generic catch below as INTERNAL_ERROR with a null suggestedCorrection — a fault the
        // caller typed, reported as a fault of the server, with nothing in the reply to act on.
        requireUsableExtension(filenameHint, ext);

        // Write to temp file, validate, and store via addImageFromFile
        File tempFile = null;
        try {
            tempFile = File.createTempFile("archi-mcp-image-", "." + ext);
            Files.write(tempFile.toPath(), imageData);
            return storeImageFile(tempFile, model);
        } catch (Exception e) {
            if (e instanceof ModelAccessException) throw (ModelAccessException) e;
            if (e instanceof org.eclipse.swt.SWTException) {
                throw new ModelAccessException(
                        "Invalid or unsupported image data: " + e.getMessage(),
                        ErrorCode.INVALID_PARAMETER,
                        e.getMessage(),
                        "Archi's image loader could not read this data. Supported: PNG, JPEG, GIF, BMP, ICO, TIFF. "
                                + SVG_HOST_SUPPORT_NOTE,
                        null);
            }
            throw new ModelAccessException(
                    "Failed to add image to model: " + e.getMessage(),
                    ErrorCode.INTERNAL_ERROR,
                    e.getMessage(),
                    null,
                    null);
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    AddImageResultDto addImageFromFilePath(String sessionId, String filePath) {
        logger.info("Adding image from file path: {}", filePath);
        IArchimateModel model = modelSupplier.get();

        if (filePath == null || filePath.isBlank()) {
            throw new ModelAccessException(
                    "filePath must not be empty",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide an absolute path to a local image file.",
                    null);
        }

        File file = new File(filePath);
        if (!file.isAbsolute()) {
            throw new ModelAccessException(
                    "filePath must be an absolute path: " + filePath,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide an absolute path (e.g., /Users/me/icons/aws-eks.png).",
                    null);
        }
        if (!file.exists()) {
            throw new ModelAccessException(
                    "File not found: " + filePath,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Check the file path and ensure the file exists.",
                    null);
        }
        if (!file.isFile()) {
            throw new ModelAccessException(
                    "Path is not a regular file: " + filePath,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a path to a file, not a directory.",
                    null);
        }
        if (!file.canRead()) {
            throw new ModelAccessException(
                    "File is not readable: " + filePath,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Check file permissions.",
                    null);
        }
        if (file.length() > 1_048_576) {
            throw new ModelAccessException(
                    "File exceeds 1MB limit (" + file.length() + " bytes): " + filePath,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a smaller image (max 1MB).",
                    null);
        }

        try {
            return storeImageFile(file, model);
        } catch (Exception e) {
            if (e instanceof ModelAccessException) throw (ModelAccessException) e;
            if (e instanceof org.eclipse.swt.SWTException) {
                throw new ModelAccessException(
                        "Invalid or unsupported image file: " + e.getMessage(),
                        ErrorCode.INVALID_PARAMETER,
                        e.getMessage(),
                        "Archi's image loader could not read this file. Supported: PNG, JPEG, GIF, BMP, ICO, TIFF. "
                                + SVG_HOST_SUPPORT_NOTE,
                        null);
            }
            throw new ModelAccessException(
                    "Failed to add image from file: " + e.getMessage(),
                    ErrorCode.INTERNAL_ERROR,
                    e.getMessage(),
                    null,
                    null);
        }
    }

    /**
     * Validates an image file and stores it in the model's archive via IArchiveManager.addImageFromFile().
     * Returns the result DTO with archive path, dimensions, and format.
     *
     * @param file  the image file to validate and store
     * @param model the model to store the image in (caller already validated via the model supplier)
     */
    // Package-visible: also reused by ArchiModelAccessorImpl.addImageFromUrl (which keeps its
    // separately-tested bounded download seam) via the accessor's imageOps field.
    AddImageResultDto storeImageFile(File file, IArchimateModel model) throws IOException {
        // Validate image by loading ImageData (throws SWTException for invalid/unsupported formats)
        ImageData imgData = new ImageData(file.getAbsolutePath());
        int width = imgData.width;
        int height = imgData.height;

        String formatDetected = detectFormat(file.getName());

        // Store via IArchiveManager.addImageFromFile — uses Archi's standard archive path naming.
        // NOTE: Archive writes are NOT undoable (no command stack entry). This matches
        // Archi's own behavior — images persist in the archive even if the referencing
        // view object change is undone. Deduplication prevents unbounded orphan growth.
        IArchiveManager archiveManager = (IArchiveManager) model.getAdapter(IArchiveManager.class);
        if (archiveManager == null) {
            throw new ModelAccessException(
                    "Archive manager not available — model may not be saved yet",
                    ErrorCode.INTERNAL_ERROR,
                    null,
                    "Save the model once in Archi before adding images.",
                    null);
        }
        String imagePath = archiveManager.addImageFromFile(file);

        logger.info("Image added to model: path={}, size={}x{}, format={}", imagePath, width, height, formatDetected);
        return new AddImageResultDto(imagePath, width, height, formatDetected);
    }

    List<ModelImageDto> listModelImages(String sessionId) {
        logger.info("Listing model images");
        IArchimateModel model = modelSupplier.get();

        IArchiveManager archiveManager = (IArchiveManager) model.getAdapter(IArchiveManager.class);
        if (archiveManager == null) {
            return List.of();
        }

        // Use getLoadedImagePaths() to include all images in the archive cache,
        // not just those currently referenced by model objects (getImagePaths()).
        // Newly added images live in the cache before any view object references them.
        java.util.Set<String> imagePaths = archiveManager.getLoadedImagePaths();
        if (imagePaths == null || imagePaths.isEmpty()) {
            return List.of();
        }

        List<ModelImageDto> result = new ArrayList<>();
        for (String path : imagePaths) {
            try {
                // Get image dimensions — use createImageData to avoid SWT Image disposal
                ImageData data = archiveManager.createImageData(path);
                if (data != null) {
                    result.add(new ModelImageDto(path, data.width, data.height));
                }
            } catch (Exception e) {
                // Skip images that can't be loaded — don't fail the whole list
                logger.warn("Could not load image dimensions for path: {}", path, e);
                result.add(new ModelImageDto(path, 0, 0));
            }
        }

        logger.info("Found {} images in model archive", result.size());
        return result;
    }

    /**
     * Derives the extension of a file name, lower-cased.
     *
     * <p>Returns {@code "png"} when the name contains no dot at all. A name whose <em>last</em>
     * character is a dot is a different case and returns the <b>empty string</b>, not {@code "png"} —
     * the split finds a dot and takes what follows it. That is the behaviour this method was
     * extracted from and it is preserved deliberately; it is benign downstream (an empty temp-file
     * suffix is legal, and {@code detectFormat} maps the result to its {@code "PNG"} default), but it
     * is not the same path as "no extension" and is pinned separately so the distinction cannot be
     * mistaken for the documented default.</p>
     *
     * <p>Lower-cased under {@link Locale#ROOT} for the same reason {@link #detectFormat} is, and the
     * two must agree: this method names the temp file that {@code storeImageFile} loads, and
     * {@code detectFormat} then reads that same name back to produce {@code formatDetected}. Under a
     * Turkish default locale {@code "TIFF".toLowerCase()} yields a dotless ı, so an uppercase
     * {@code .TIFF} hint would name the temp file {@code ….tıff}; the content sniff in
     * {@code new ImageData(path)} still loads it, {@code detectFormat} then matches no arm and falls
     * to its {@code "PNG"} default, and the response silently labels a TIFF as PNG. Four extensions
     * are actually mangled that way — TIFF, TIF, ICO and GIF, the ones containing an {@code I}.</p>
     */
    static String extensionFromFileName(String fileName) {
        return fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "png";
    }

    /**
     * Refuses a filename hint whose derived extension cannot name a file.
     *
     * <p>Exactly two characters are refused, and the list is measured rather than defensive.
     * {@code File.createTempFile} throws on a path separator and on a NUL, and succeeds on
     * everything else a filename hint can produce here: a backslash is a legal filename character
     * on this project's target platforms, an over-long suffix is silently truncated by the JDK
     * rather than rejected, and a trailing dot yields an empty suffix which is legal. Refusing
     * either of the latter would refuse input that demonstrably works, which is why this guard is
     * deliberately narrower than {@link #extensionFromUrlPath}'s fallback condition — that method
     * answers a different question, and its {@code length() > 5} clause is about producing a tidy
     * suffix, not about avoiding a crash.</p>
     *
     * <p>The check is on the DERIVED suffix, not on the whole hint. A hint like {@code a/b.png}
     * contains a separator and works perfectly well, because the last-dot split leaves it behind;
     * only a separator that survives into the suffix is fatal.</p>
     *
     * @param filenameHint the caller-supplied hint, named back so the caller can see what was read
     * @param ext          the suffix derived from it by {@link #extensionFromFileName}
     */
    static void requireUsableExtension(String filenameHint, String ext) {
        if (ext == null) {
            return;
        }
        String offending = null;
        if (ext.indexOf('/') >= 0) {
            offending = "a path separator ('/')";
        } else if (ext.indexOf('\0') >= 0) {
            offending = "a NUL character";
        }
        if (offending == null) {
            return;
        }
        throw new ModelAccessException(
                "The filename '" + filenameHint + "' yields the extension '" + ext
                        + "', which contains " + offending + " and cannot name a file",
                ErrorCode.INVALID_PARAMETER,
                "derived extension: '" + ext + "'",
                "The extension is taken from the text after the LAST dot in filename, so a dot "
                        + "earlier in the name captures the rest of it. Pass a filename whose final "
                        + "dot is the extension separator — 'logo.png' rather than 'v1.2/logo' — or "
                        + "omit filename to default to image.png.",
                null);
    }

    /**
     * Derives the temp-file extension for an image downloaded from a URL.
     *
     * <p>A last-dot split on a URL path is not a file-name split: the last dot can sit in a directory
     * segment ({@code /a.b/c} yields {@code b/c}) and the tail can be arbitrarily long. Either would
     * be an unusable temp-file suffix, so both fall back to {@code "png"} — the content sniff in
     * {@code new ImageData(path)} decides the real format regardless, and the suffix only has to be
     * benign.</p>
     *
     * <p>A NUL joins that set for the same reason and is reachable by the same route: a
     * percent-encoded {@code %00} survives {@code URI.create}, {@code getPath()} decodes it, and the
     * two clauses above pass a short separator-free suffix straight through to
     * {@code File.createTempFile}, which throws on it. It falls back here rather than being refused
     * as {@link #requireUsableExtension} refuses it, because the two inputs are not the same kind of
     * thing: a {@code filename} is a hint the caller typed and should be told about, while this
     * suffix is incidental debris from a URL path whose real subject is the resource being fetched.
     * This method's contract is already to cope silently; refusing here would change it.</p>
     */
    static String extensionFromUrlPath(String urlPath) {
        String ext = extensionFromFileName(urlPath);
        return ext.length() > 5 || ext.contains("/") || ext.indexOf('\0') >= 0 ? "png" : ext;
    }

    /**
     * Maps a file name to the format label reported as {@code formatDetected}.
     *
     * <p>Extension-based, not content-based: SVG is XML and carries no magic number, so a
     * byte sniff could not name it anyway. The {@code default} arm is {@code "PNG"}, which means
     * <b>an extension this switch does not name is reported as PNG rather than as unknown</b> —
     * a missing arm is invisible in the response. {@code ImageFormatDetectionTest} pins every arm
     * for that reason.</p>
     *
     * <p>Lower-cased under {@link Locale#ROOT} rather than the default locale: under a Turkish
     * locale {@code "TIFF".toLowerCase()} yields a dotless ı and would miss its own arm.</p>
     */
    static String detectFormat(String fileName) {
        String ext = fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "png";
        return switch (ext) {
            case "jpg", "jpeg" -> "JPEG";
            case "gif" -> "GIF";
            case "bmp" -> "BMP";
            case "ico" -> "ICO";
            case "tiff", "tif" -> "TIFF";
            case "svg" -> "SVG";
            default -> "PNG";
        };
    }
}
