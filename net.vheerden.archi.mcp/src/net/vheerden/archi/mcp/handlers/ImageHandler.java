package net.vheerden.archi.mcp.handlers;

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
import net.vheerden.archi.mcp.response.dto.ModelImageDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for image management tools (Story C4, C5): add-image-to-model, list-model-images.
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
                        + "Supported formats: PNG, JPEG, GIF, BMP, ICO, TIFF. Max 1MB.");

        Map<String, Object> urlProp = new LinkedHashMap<>();
        urlProp.put("type", "string");
        urlProp.put("description",
                "PREFERRED. HTTP or HTTPS URL to download the image from (e.g., https://example.com/icon.png). "
                        + "Server downloads directly — no base64 encoding needed. "
                        + "10s connect timeout, 30s read timeout. Max 1MB. Redirects followed.");

        Map<String, Object> imageDataProp = new LinkedHashMap<>();
        imageDataProp.put("type", "string");
        imageDataProp.put("description",
                "FALLBACK. Base64-encoded image data. Use filePath or url instead when possible — "
                        + "base64 data can be corrupted passing through LLM text channels. "
                        + "Supported formats: PNG, JPEG, GIF, BMP, ICO, TIFF. SVG NOT supported. Max 1MB decoded.");

        Map<String, Object> filenameProp = new LinkedHashMap<>();
        filenameProp.put("type", "string");
        filenameProp.put("description",
                "Filename hint for format detection (e.g., 'aws-lambda.png'). "
                        + "Only used with imageData. The extension determines format detection. Default: 'image.png'.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", filePathProp);
        properties.put("url", urlProp);
        properties.put("imageData", imageDataProp);
        properties.put("filename", filenameProp);

        // No required params — exactly one of filePath/url/imageData must be provided
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("add-image-to-model")
                .description("[Images] Import an image (icon) into the model archive for use on view objects. "
                        + "Preferred: filePath (local file) or url (HTTP download) — these bypass LLM text channel "
                        + "and avoid base64 corruption. Fallback: imageData (base64). "
                        + "Provide exactly ONE of filePath, url, or imageData. "
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

            // Mutual exclusivity: exactly one of filePath, url, imageData
            int providedCount = (filePath != null ? 1 : 0) + (url != null ? 1 : 0) + (imageDataBase64 != null ? 1 : 0);
            if (providedCount == 0) {
                return HandlerUtils.buildInternalError(formatter,
                        "Exactly one of 'filePath', 'url', or 'imageData' must be provided.");
            }
            if (providedCount > 1) {
                return HandlerUtils.buildInternalError(formatter,
                        "Only one of 'filePath', 'url', or 'imageData' may be provided. Got " + providedCount + ".");
            }

            AddImageResultDto result;
            if (filePath != null) {
                result = accessor.addImageFromFilePath(sessionId, filePath);
            } else if (url != null) {
                result = accessor.addImageFromUrl(sessionId, url);
            } else {
                // Base64 path (existing behavior)
                // Reject obviously oversized payloads before decoding (1MB decoded ≈ 1.37MB base64)
                if (imageDataBase64.length() > 1_400_000) {
                    return HandlerUtils.buildInternalError(formatter,
                            "Image data exceeds 1MB limit. Provide a smaller image.");
                }

                byte[] imageData;
                try {
                    imageData = Base64.getDecoder().decode(imageDataBase64);
                } catch (IllegalArgumentException e) {
                    return HandlerUtils.buildInternalError(formatter,
                            "Invalid base64 encoding in imageData. Provide valid base64-encoded image data.");
                }

                if (imageData.length > 1_048_576) {
                    return HandlerUtils.buildInternalError(formatter,
                            "Image data exceeds 1MB limit (" + imageData.length + " bytes). Provide a smaller image.");
                }

                result = accessor.addImageToModel(sessionId, imageData, filename);
            }

            List<String> nextSteps = List.of(
                    "Use the returned imagePath with update-view-object to set image on an existing element",
                    "Or pass imagePath to add-to-view / add-group-to-view / add-note-to-view when creating new view objects",
                    "Set imagePosition to control placement: bottom-left (recommended), top-right (default), fill, etc.",
                    "Download icons locally first, then use filePath for reliable import in batch workflows");

            String modelVersion = accessor.getModelVersion();
            Map<String, Object> envelope = formatter.formatSuccess(
                    result, nextSteps, modelVersion, 1, 1, false);
            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (Exception e) {
            logger.error("Error handling add-image-to-model request", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
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
