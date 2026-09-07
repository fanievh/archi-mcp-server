package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AddImageResultDto;
import net.vheerden.archi.mcp.response.dto.AddImagesResultDto;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ModelImageDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for image management tools: add-image-to-model, list-model-images.
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import any
 * EMF, SWT, GEF, or ArchimateTool model types. All image operations are performed
 * by {@link ArchiModelAccessor}.</p>
 */
public class ImageHandler {

    private static final Logger logger = LoggerFactory.getLogger(ImageHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;

    /**
     * The SVG condition, stated once and carried by all three import sources.
     *
     * <p>Reading an SVG is the host platform's job, not this server's: SWT's optional rasterizer
     * fragment arrived with the Eclipse 2026-06 platform, which Archi 5.10 rebased onto and Archi 5.9
     * (Eclipse 4.32) predates. Archi's own help publishes the same 5.10 boundary and the same
     * re-save data-loss consequence.</p>
     *
     * <p>One constant rather than three literals because this surface has already drifted once: the
     * caveat previously reached {@code filePath} and {@code imageData} and missed {@code url}, and
     * {@code imageData} carried only a cross-reference to {@code filePath} — unresolvable for an
     * agent reading a single property.</p>
     */
    private static final String SVG_HOST_CAVEAT =
            "SVG is read by the host Archi's image loader, not by this server: Archi supports it from 5.10 "
                    + "onward and rejects it on every earlier release. A model carrying SVG images can lose "
                    + "them when it is opened and re-saved on an older Archi, so prefer PNG when the model "
                    + "must travel across Archi versions.";

    public ImageHandler(ArchiModelAccessor accessor,
                        ResponseFormatter formatter,
                        CommandRegistry registry,
                        SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
    }

    public void registerTools() {
        registry.registerTool(buildAddImageToModelSpec());
        registry.registerTool(buildListModelImagesSpec());
    }

    private McpServerFeatures.SyncToolSpecification buildAddImageToModelSpec() {
        Map<String, Object> filePathProp = new LinkedHashMap<>();
        filePathProp.put("type", "string");
        filePathProp.put("description",
                "PREFERRED. Absolute path to a local image file (e.g., /Users/me/icons/aws-eks.png). "
                        + "Server reads the file directly — no base64 encoding needed. "
                        + "Supported formats: PNG, JPEG, GIF, BMP, ICO, TIFF. Max 1MB. "
                        + SVG_HOST_CAVEAT + " "
                        + "Drawn as an icon on an element, group or note, the image renders at its own size: "
                        + "add-to-view, add-group-to-view, add-note-to-view and update-view-object take no width "
                        + "parameter. So author a corner icon at the size you want it drawn — 16x16 renders as a "
                        + "corner badge, 48x48 fills the element. "
                        + "add-image-to-view is the exception: it places a standalone image visual on the view "
                        + "rather than an icon on an object, and does take width and height.");

        Map<String, Object> urlProp = new LinkedHashMap<>();
        urlProp.put("type", "string");
        urlProp.put("description",
                "PREFERRED. HTTP or HTTPS URL to download the image from (e.g., https://example.com/icon.png). "
                        + "Server downloads directly — no base64 encoding needed. "
                        + "Supported formats: PNG, JPEG, GIF, BMP, ICO, TIFF. "
                        + "10s connect timeout, 30s read timeout. Max 1MB. Redirects followed. "
                        + SVG_HOST_CAVEAT);

        Map<String, Object> imageDataProp = new LinkedHashMap<>();
        imageDataProp.put("type", "string");
        imageDataProp.put("description",
                "FALLBACK. Base64-encoded image data. Use filePath or url instead when possible — "
                        + "base64 data can be corrupted passing through LLM text channels. "
                        + "Supported formats: PNG, JPEG, GIF, BMP, ICO, TIFF. Max 1MB decoded. "
                        + SVG_HOST_CAVEAT);

        Map<String, Object> filenameProp = new LinkedHashMap<>();
        filenameProp.put("type", "string");
        filenameProp.put("description",
                "Filename hint for format detection (e.g., 'aws-lambda.png'). "
                        + "Only used with imageData. The extension determines format detection. Default: 'image.png'. "
                        + "The extension is the text after the LAST dot, so a dot earlier in the "
                        + "name captures the rest of it: 'v1.2/logo' yields '2/logo'. A derived "
                        + "extension containing a path separator or a NUL cannot name a file and "
                        + "is rejected with INVALID_PARAMETER rather than silently defaulted.");

        Map<String, Object> entryProps = new LinkedHashMap<>();
        entryProps.put("filePath", filePathProp);
        entryProps.put("url", urlProp);
        entryProps.put("imageData", imageDataProp);
        entryProps.put("filename", filenameProp);

        Map<String, Object> entrySchema = new LinkedHashMap<>();
        entrySchema.put("type", "object");
        entrySchema.put("properties", entryProps);

        Map<String, Object> imagesProp = new LinkedHashMap<>();
        imagesProp.put("type", "array");
        imagesProp.put("items", entrySchema);
        imagesProp.put("maxItems", BulkOperation.MAX_OPERATIONS);
        imagesProp.put("description",
                "BATCH FORM. Import many images in one call — each entry takes the same "
                        + "filePath / url / imageData / filename parameters as the single form, and "
                        + "exactly one source per entry. Use this instead of calling the tool once "
                        + "per icon. Max " + BulkOperation.MAX_OPERATIONS + " entries (the same cap "
                        + "bulk-mutate uses); the 1MB limit still applies to each image. "
                        + "Mutually exclusive with the top-level filePath/url/imageData. "
                        + "Entries are independent: the response reports every image that landed "
                        + "with its archive path, and every entry that failed with its reason, so a "
                        + "partial batch never needs re-sending whole. "
                        + "Entries are imported one after another, and a url entry can wait out its "
                        + "connect and download timeouts before failing — so a large batch of urls "
                        + "is a long-running call. Prefer filePath, and download first, when "
                        + "importing many.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("images", imagesProp);
        properties.put("filePath", filePathProp);
        properties.put("url", urlProp);
        properties.put("imageData", imageDataProp);
        properties.put("filename", filenameProp);

        // No required params — either 'images' (batch) or exactly one of filePath/url/imageData
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("add-image-to-model")
                .description("[Images] Import an image (icon) into the model archive for use on view objects. "
                        + "Preferred: filePath (local file) or url (HTTP download) — these bypass LLM text channel "
                        + "and avoid base64 corruption. Fallback: imageData (base64). "
                        + "Provide exactly ONE of filePath, url, or imageData per image. "
                        + "Importing several images? Pass the 'images' array instead of calling this "
                        + "tool once per icon — one call imports up to " + BulkOperation.MAX_OPERATIONS
                        + " images and reports each one's archive path. "
                        + "This tool is deliberately NOT available inside bulk-mutate: an archive "
                        + "write is not undoable (Archi exposes no removal API), so it can neither be "
                        + "rolled back with a declined proposal nor deferred to execute time. Use the "
                        + "'images' array for batching, and import before the bulk-mutate call that "
                        + "places the images. "
                        + "For the same reason this tool is unaffected by begin-batch: the import "
                        + "runs immediately even while a batch is open, it is not queued, and "
                        + "end-batch --rollback will not remove it. The imagePath it returns is "
                        + "usable at once. "
                        + "Returns the archive imagePath to pass to update-view-object, add-to-view, "
                        + "add-group-to-view, or add-note-to-view. Images are deduplicated — "
                        + "re-importing the same bytes returns the existing path. "
                        + "Use list-model-images to see images already in the model. "
                        + "NOTE: Archi displays the element type icon in the top-right corner by default. "
                        + "Avoid placing custom images at top-right — they will be obscured by the type icon. "
                        + "Use bottom-left (recommended for small icons) or another corner instead. "
                        + "To show both the type icon and a custom image, place them in different corners. "
                        + "To hide the type icon entirely, set showIcon='never' on the view object.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleAddImageToModel)
                .build();
    }

    private McpServerFeatures.SyncToolSpecification buildListModelImagesSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("list-model-images")
                .description("[Images] List all images stored in the model archive with their paths and dimensions. "
                        + "Use the imagePath values with update-view-object or creation tools "
                        + "to set images on elements, groups, or notes without re-importing.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleListModelImages)
                .build();
    }

    McpSchema.CallToolResult handleAddImageToModel(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling add-image-to-model request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            Map<String, Object> args = request.arguments();
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            String filePath = HandlerUtils.optionalStringParam(args, "filePath");
            String url = HandlerUtils.optionalStringParam(args, "url");
            String imageDataBase64 = HandlerUtils.optionalStringParam(args, "imageData");
            String filename = HandlerUtils.optionalStringParam(args, "filename");

            Object imagesRaw = args == null ? null : args.get("images");
            if (imagesRaw != null) {
                if (filePath != null || url != null || imageDataBase64 != null) {
                    return HandlerUtils.buildInternalError(formatter,
                            "Provide either the 'images' array (batch import) or a single "
                                    + "filePath/url/imageData — not both.");
                }
                if (filename != null) {
                    // Accepting it would silently drop it: a batch entry carries its own filename,
                    // and there is no single image here for a top-level one to name.
                    return HandlerUtils.buildInternalError(formatter,
                            "'filename' cannot be combined with 'images' — it names a single "
                                    + "image. Put a filename on the entry it belongs to instead.");
                }
                return importBatch(sessionId, imagesRaw);
            }

            AddImageResultDto result = importOne(sessionId, filePath, url, imageDataBase64, filename);

            List<String> nextSteps = List.of(
                    "Use the returned imagePath with update-view-object to set image on an existing element",
                    "Or pass imagePath to add-to-view / add-group-to-view / add-note-to-view when creating new view objects",
                    "Set imagePosition to control placement: bottom-left (recommended), top-right (default), fill, etc.",
                    "Download icons locally first, then use filePath for reliable import in batch workflows");

            String modelVersion = accessor.getModelVersion();
            Map<String, Object> envelope = formatter.formatSuccess(
                    result, nextSteps, modelVersion, 1, 1, false);
            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
        } catch (ImageEntryRejected e) {
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (Exception e) {
            logger.error("Error handling add-image-to-model request", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    /**
     * Validates one image source and imports it. Shared by the single form and by every entry of
     * the batch form, so the mutual-exclusivity rule and the base64 size guards are entered once
     * per image on both paths instead of being duplicated per call shape.
     *
     * @throws ImageEntryRejected if the entry's own parameters are invalid
     */
    private AddImageResultDto importOne(String sessionId, String filePath, String url,
                                        String imageDataBase64, String filename) {
        // Mutual exclusivity: exactly one of filePath, url, imageData
        int providedCount = (filePath != null ? 1 : 0) + (url != null ? 1 : 0) + (imageDataBase64 != null ? 1 : 0);
        if (providedCount == 0) {
            throw new ImageEntryRejected(
                    "Exactly one of 'filePath', 'url', or 'imageData' must be provided.");
        }
        if (providedCount > 1) {
            throw new ImageEntryRejected(
                    "Only one of 'filePath', 'url', or 'imageData' may be provided. Got " + providedCount + ".");
        }

        if (filePath != null) {
            return accessor.addImageFromFilePath(sessionId, filePath);
        }
        if (url != null) {
            return accessor.addImageFromUrl(sessionId, url);
        }

        // Base64 path (existing behavior)
        // Reject obviously oversized payloads before decoding (1MB decoded ≈ 1.37MB base64)
        if (imageDataBase64.length() > 1_400_000) {
            throw new ImageEntryRejected("Image data exceeds 1MB limit. Provide a smaller image.");
        }

        byte[] imageData;
        try {
            imageData = Base64.getDecoder().decode(imageDataBase64);
        } catch (IllegalArgumentException e) {
            throw new ImageEntryRejected(
                    "Invalid base64 encoding in imageData. Provide valid base64-encoded image data.");
        }

        if (imageData.length > 1_048_576) {
            throw new ImageEntryRejected(
                    "Image data exceeds 1MB limit (" + imageData.length + " bytes). Provide a smaller image.");
        }

        return accessor.addImageToModel(sessionId, imageData, filename);
    }

    /**
     * Imports every entry of the {@code images} array.
     *
     * <p>An archive write has no inverse — Archi's {@code IArchiveManager} exposes no removal
     * API — so a failing entry cannot unwind the entries that already landed. Aborting the batch
     * would therefore leave images in the archive while reporting failure, which is exactly the
     * kind of response an agent cannot act on. Every entry is attempted, and the response names
     * both what landed and what did not.</p>
     */
    private McpSchema.CallToolResult importBatch(String sessionId, Object imagesRaw) {
        if (!(imagesRaw instanceof List<?> entries)) {
            return HandlerUtils.buildInternalError(formatter,
                    "'images' must be an array of objects, each providing one of "
                            + "'filePath', 'url' or 'imageData'.");
        }
        if (entries.isEmpty()) {
            return HandlerUtils.buildInternalError(formatter,
                    "'images' must contain at least one entry.");
        }
        if (entries.size() > BulkOperation.MAX_OPERATIONS) {
            return HandlerUtils.buildInternalError(formatter,
                    "'images' accepts at most " + BulkOperation.MAX_OPERATIONS
                            + " entries per call (the same cap bulk-mutate uses). Got "
                            + entries.size() + ". Split the import across several calls.");
        }

        List<AddImagesResultDto.ImportedImage> imported = new ArrayList<>();
        List<AddImagesResultDto.FailedImage> failures = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            Object raw = entries.get(i);
            if (!(raw instanceof Map<?, ?> entry)) {
                failures.add(new AddImagesResultDto.FailedImage(i,
                        "Entry must be an object providing one of 'filePath', 'url' or 'imageData'."));
                continue;
            }
            try {
                AddImageResultDto imageResult = importOne(sessionId,
                        entryString(entry, "filePath"),
                        entryString(entry, "url"),
                        entryString(entry, "imageData"),
                        entryString(entry, "filename"));
                imported.add(new AddImagesResultDto.ImportedImage(i, imageResult.imagePath(),
                        imageResult.width(), imageResult.height(), imageResult.formatDetected()));
            } catch (ImageEntryRejected | ModelAccessException e) {
                failures.add(new AddImagesResultDto.FailedImage(i, e.getMessage()));
            } catch (NoModelLoadedException e) {
                // The model went away part-way through — the supplier is re-entered on every entry,
                // so a long batch can outlive it. Letting this escape would unwind the whole call
                // and report only "no model loaded", while everything already written sat in the
                // archive unreported and unremovable. Stop attempting (every remaining entry would
                // fail identically) but still name what landed and what did not.
                failures.add(new AddImagesResultDto.FailedImage(i,
                        "The model was closed before this entry was imported."));
                for (int notAttempted = i + 1; notAttempted < entries.size(); notAttempted++) {
                    failures.add(new AddImagesResultDto.FailedImage(notAttempted,
                            "Not attempted — the model was closed earlier in this batch."));
                }
                break;
            }
        }

        AddImagesResultDto result = new AddImagesResultDto(
                entries.size(), imported.size(), failures.size(), imported, failures);

        List<String> nextSteps = new ArrayList<>(List.of(
                "Use each returned imagePath with add-to-view / add-group-to-view / add-note-to-view "
                        + "or update-view-object to place the image",
                "Set imagePosition to control placement: bottom-left (recommended), top-right (default), fill, etc.",
                "Images are deduplicated — re-importing the same bytes returns the existing path"));
        if (!failures.isEmpty()) {
            nextSteps.add("Retry only the indices listed in failures — the imported images are "
                    + "already in the archive and re-sending them is unnecessary");
        }

        String modelVersion = accessor.getModelVersion();
        Map<String, Object> envelope = formatter.formatSuccess(
                result, nextSteps, modelVersion, imported.size(), entries.size(), false);
        return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
    }

    /** Reads one string field of a batch entry, treating blank as absent (as the single form does). */
    private static String entryString(Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        return (value instanceof String str && !str.isBlank()) ? str : null;
    }

    /**
     * A caller-facing rejection of one image's parameters. Carries the message the caller sees —
     * as the single form's error, or as one entry's {@code failures} message in a batch.
     */
    private static final class ImageEntryRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ImageEntryRejected(String message) {
            super(message);
        }
    }

    McpSchema.CallToolResult handleListModelImages(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling list-model-images request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            List<ModelImageDto> images = accessor.listModelImages(sessionId);

            List<String> nextSteps;
            if (images.isEmpty()) {
                nextSteps = List.of(
                        "No images in model. Use add-image-to-model with filePath (preferred) or url to import an image.");
            } else {
                nextSteps = List.of(
                        "Use any imagePath with update-view-object to set image on a view object",
                        "Or pass imagePath to add-to-view / add-group-to-view / add-note-to-view");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("images", images);
            result.put("count", images.size());

            String modelVersion = accessor.getModelVersion();
            Map<String, Object> envelope = formatter.formatSuccess(
                    result, nextSteps, modelVersion, images.size(), images.size(), false);
            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (Exception e) {
            logger.error("Error handling list-model-images request", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }
}
