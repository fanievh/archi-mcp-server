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
import net.vheerden.archi.mcp.model.ExportResult;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * Handler for view rendering/export tools: export-view (extended
 * for PDF + JPG + SVG support).
 *
 * <p>Renders ArchiMate views as PNG, JPG, SVG, or PDF and returns them either
 * inline (as MCP {@link McpSchema.ImageContent} for raster formats,
 * {@link McpSchema.TextContent} for SVG, or {@link McpSchema.EmbeddedResource}
 * wrapping {@link McpSchema.BlobResourceContents} for PDF) or written to a
 * file. SVG and PDF require the optional {@code com.archimatetool.export.svg}
 * bundle (declared {@code resolution:=optional} in MANIFEST.MF).</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import any
 * EMF, SWT, GEF, or ArchimateTool model types. All rendering is performed
 * by {@link ArchiModelAccessor#exportView}. This handler only handles
 * base64 encoding and MCP content type selection.</p>
 */
public class RenderHandler {

    private static final Logger logger = LoggerFactory.getLogger(RenderHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;

    /**
     * Creates a RenderHandler with its required dependencies.
     *
     * @param accessor  the model accessor for rendering views
     * @param formatter the response formatter for building JSON envelopes
     * @param registry  the command registry for tool registration
     */
    public RenderHandler(ArchiModelAccessor accessor,
                         ResponseFormatter formatter,
                         CommandRegistry registry) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     * Registers: export-view.
     */
    public void registerTools() {
        registry.registerTool(buildExportViewSpec());
    }

    private McpServerFeatures.SyncToolSpecification buildExportViewSpec() {
        Map<String, Object> viewIdProp = new LinkedHashMap<>();
        viewIdProp.put("type", "string");
        viewIdProp.put("description", "The unique identifier of the view to export");

        Map<String, Object> formatProp = new LinkedHashMap<>();
        formatProp.put("type", "string");
        formatProp.put("description",
                "Output format: 'png' (lossless raster, default — ideal for LLM "
                        + "vision review), 'jpg' (lossy raster, smaller files; alias "
                        + "'jpeg' is accepted), 'svg' (vector text, scales infinitely), "
                        + "or 'pdf' (vector, print-ready). SVG and PDF require the "
                        + "optional Archi SVG export plugin (ships with Archi 5.7+).");
        formatProp.put("enum", List.of("png", "jpg", "svg", "pdf"));

        Map<String, Object> scaleProp = new LinkedHashMap<>();
        scaleProp.put("type", "number");
        scaleProp.put("description",
                "Rendering scale factor (0.1 to 4.0). "
                        + "0.5 = half size, 2.0 = double size. Applies to raster "
                        + "formats (PNG/JPG); vector formats (SVG/PDF) are resolution-"
                        + "independent and ignore scale. Default: 1.0");

        Map<String, Object> qualityProp = new LinkedHashMap<>();
        qualityProp.put("type", "integer");
        qualityProp.put("description",
                "JPEG encoding quality (1-100). Applies only to format 'jpg' — "
                        + "silently ignored for png/svg/pdf. Higher = better quality, "
                        + "larger file. Default: 90");
        qualityProp.put("minimum", 1);
        qualityProp.put("maximum", 100);

        Map<String, Object> inlineProp = new LinkedHashMap<>();
        inlineProp.put("type", "boolean");
        inlineProp.put("description",
                "Return image data in the response (true) or write "
                        + "to a file and return the path (false). Inline mode returns "
                        + "PNG/JPG as ImageContent for LLM vision analysis, SVG as "
                        + "TextContent (raw XML), and PDF as EmbeddedResource "
                        + "(application/pdf blob). Default: true");

        Map<String, Object> outputDirProp = new LinkedHashMap<>();
        outputDirProp.put("type", "string");
        outputDirProp.put("description",
                "Absolute path to write the exported image file. Only used when "
                        + "inline is false. If omitted, files are written to a temporary "
                        + "directory. The directory is created if it does not exist.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", viewIdProp);
        properties.put("format", formatProp);
        properties.put("scale", scaleProp);
        properties.put("quality", qualityProp);
        properties.put("inline", inlineProp);
        properties.put("outputDirectory", outputDirProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("export-view")
                .description("[Rendering] Renders an ArchiMate view as an image in "
                        + "one of four formats: png (lossless raster), jpg (lossy "
                        + "raster, with quality knob), svg (vector text), pdf "
                        + "(vector, print-ready). Returns image data inline (default) "
                        + "or writes to file. Inline mode returns PNG/JPG as ImageContent, "
                        + "SVG as TextContent, PDF as EmbeddedResource(application/pdf). "
                        + "The 'jpeg' alias is accepted as a synonym for 'jpg'. The "
                        + "raster renderers (PNG/JPG) use Archi's native renderer which "
                        + "computes connection endpoint positions at element perimeter "
                        + "intersections (ChopboxAnchor) — the rendered image shows true "
                        + "visual attachment points, not the center-based reference "
                        + "coordinates returned by get-view-contents. SVG and PDF require "
                        + "the optional Archi SVG export plugin (ships with Archi 5.7+; "
                        + "PDF is provided by the same bundle). Use for visual verification "
                        + "of layout changes and connection routing via LLM vision "
                        + "capabilities, and for embedding diagrams in reports/wikis. "
                        + "The response carries the model's monotonic mutation counter as "
                        + "modelVersion (inline mode: top-level field; file mode: in _meta). "
                        + "It is a decimal-encoded counter — compare it as an integer, not a "
                        + "string. Against a later assess-layout _meta.modelVersion: if the "
                        + "export's modelVersion is numerically lower, the model changed after "
                        + "this render and the image is stale — re-export before relying on it. "
                        + "Related: get-view-contents (reference vs rendered coords).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleExportView)
                .build();
    }

    McpSchema.CallToolResult handleExportView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling export-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            String format = HandlerUtils.optionalStringParam(args, "format");
            if (format == null) {
                format = "png";
            }
            // "jpeg" alias normalised at the handler boundary so the accessor's
            // dispatcher has a single case for the raster lossy format.
            if ("jpeg".equals(format)) {
                format = "jpg";
            }
            double scale = HandlerUtils.optionalDoubleParam(args, "scale", 1.0);
            if (!Double.isFinite(scale) || scale < 0.1 || scale > 4.0) {
                throw new ModelAccessException(
                        "Scale must be between 0.1 and 4.0, got: " + scale,
                        net.vheerden.archi.mcp.response.ErrorCode.INVALID_PARAMETER,
                        null,
                        "Provide a scale value between 0.1 and 4.0 (1.0 = 100%)",
                        null);
            }
            Integer qualityBoxed = HandlerUtils.optionalIntegerParam(args, "quality");
            int quality = (qualityBoxed != null) ? qualityBoxed : 90;
            if (qualityBoxed != null && (quality < 1 || quality > 100)) {
                throw new ModelAccessException(
                        "JPEG quality must be between 1 and 100, got: " + quality,
                        net.vheerden.archi.mcp.response.ErrorCode.INVALID_PARAMETER,
                        null,
                        "Provide a quality value between 1 (lowest) and 100 (highest)",
                        null);
            }
            boolean inline = HandlerUtils.optionalBooleanParam(args, "inline", true);
            String outputDirectory = HandlerUtils.optionalStringParam(args, "outputDirectory");
            if (outputDirectory != null && outputDirectory.isBlank()) {
                outputDirectory = null;
            }

            // outputDirectory only applies to file output mode
            boolean outputDirIgnored = inline && outputDirectory != null;
            String effectiveOutputDir = inline ? null : outputDirectory;

            ExportResult result = accessor.exportView(viewId, format, scale, quality, inline,
                    effectiveOutputDir);

            // Stamp the response with the model's monotonic mutation counter so a consumer
            // can compare it against a later assess-layout modelVersion and detect that this
            // render predates a subsequent model change (i.e. the exported image is stale).
            // Read once, after the export, so every response path reports the same value.
            String modelVersion = accessor.getModelVersion();

            if (inline && "png".equals(format)) {
                return buildInlinePngResponse(result, outputDirIgnored, modelVersion);
            } else if (inline && "jpg".equals(format)) {
                return buildInlineJpgResponse(result, outputDirIgnored, modelVersion);
            } else if (inline && "svg".equals(format)) {
                return buildInlineSvgResponse(result, outputDirIgnored, modelVersion);
            } else if (inline && "pdf".equals(format)) {
                return buildInlinePdfResponse(result, outputDirIgnored, modelVersion);
            } else {
                return buildFileResponse(result, format, modelVersion);
            }
        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (Exception e) {
            logger.error("Error handling export-view request", e);
            return HandlerUtils.buildInternalError(formatter, e.getMessage());
        }
    }

    private McpSchema.CallToolResult buildInlinePngResponse(ExportResult result,
                                                              boolean outputDirIgnored,
                                                              String modelVersion) {
        String base64 = Base64.getEncoder().encodeToString(result.imageBytes());

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("metadata", result.metadata());
        wrapper.put("modelVersion", modelVersion);
        if (outputDirIgnored) {
            wrapper.put("note", "outputDirectory is ignored when inline is true");
        }
        wrapper.put("nextSteps", buildInlineNextSteps("png"));
        String metadataJson = formatter.toJsonString(wrapper);
        McpSchema.TextContent textContent = new McpSchema.TextContent(metadataJson);
        McpSchema.ImageContent imageContent =
                new McpSchema.ImageContent(null, base64, "image/png");

        return McpSchema.CallToolResult.builder()
                .content(List.of(textContent, imageContent))
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult buildInlineJpgResponse(ExportResult result,
                                                              boolean outputDirIgnored,
                                                              String modelVersion) {
        String base64 = Base64.getEncoder().encodeToString(result.imageBytes());

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("metadata", result.metadata());
        wrapper.put("modelVersion", modelVersion);
        if (outputDirIgnored) {
            wrapper.put("note", "outputDirectory is ignored when inline is true");
        }
        wrapper.put("nextSteps", buildInlineNextSteps("jpg"));
        String metadataJson = formatter.toJsonString(wrapper);
        McpSchema.TextContent textContent = new McpSchema.TextContent(metadataJson);
        McpSchema.ImageContent imageContent =
                new McpSchema.ImageContent(null, base64, "image/jpeg");

        return McpSchema.CallToolResult.builder()
                .content(List.of(textContent, imageContent))
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult buildInlineSvgResponse(ExportResult result,
                                                              boolean outputDirIgnored,
                                                              String modelVersion) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("metadata", result.metadata());
        wrapper.put("modelVersion", modelVersion);
        if (outputDirIgnored) {
            wrapper.put("note", "outputDirectory is ignored when inline is true");
        }
        wrapper.put("nextSteps", buildInlineNextSteps("svg"));
        String metadataJson = formatter.toJsonString(wrapper);
        McpSchema.TextContent metaContent = new McpSchema.TextContent(metadataJson);
        McpSchema.TextContent svgContent = new McpSchema.TextContent(result.svgContent());

        return McpSchema.CallToolResult.builder()
                .content(List.of(metaContent, svgContent))
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult buildInlinePdfResponse(ExportResult result,
                                                              boolean outputDirIgnored,
                                                              String modelVersion) {
        String base64 = Base64.getEncoder().encodeToString(result.imageBytes());

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("metadata", result.metadata());
        wrapper.put("modelVersion", modelVersion);
        if (outputDirIgnored) {
            wrapper.put("note", "outputDirectory is ignored when inline is true");
        }
        wrapper.put("nextSteps", buildInlineNextSteps("pdf"));
        String metadataJson = formatter.toJsonString(wrapper);
        McpSchema.TextContent textContent = new McpSchema.TextContent(metadataJson);

        String viewId = result.metadata().viewId();
        McpSchema.BlobResourceContents blob = new McpSchema.BlobResourceContents(
                "archi://export/" + viewId + ".pdf",
                "application/pdf",
                base64);
        McpSchema.EmbeddedResource pdfResource =
                new McpSchema.EmbeddedResource(null, blob);

        return McpSchema.CallToolResult.builder()
                .content(List.of(textContent, pdfResource))
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult buildFileResponse(ExportResult result, String format,
                                                        String modelVersion) {
        Map<String, Object> envelope = formatter.formatSuccess(
                result.metadata(),
                buildFileNextSteps(format),
                modelVersion, 1, 1, false);
        return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
    }

    private List<String> buildInlineNextSteps(String format) {
        if ("png".equals(format)) {
            return List.of(
                    "Use LLM vision to verify layout quality (overlaps, alignment, spacing)",
                    "Use get-view-contents to see element details and positions",
                    "Use export-view with scale 2.0 for higher resolution",
                    "Use export-view with inline false to save to file");
        }
        if ("jpg".equals(format)) {
            return List.of(
                    "Use LLM vision to verify layout quality (note: JPEG lossy compression "
                            + "may obscure fine alignment details — use 'png' for precise verification)",
                    "Use get-view-contents to see element details and positions",
                    "Use export-view with quality 100 for highest fidelity",
                    "Use export-view with format 'png' for lossless raster");
        }
        if ("pdf".equals(format)) {
            return List.of(
                    "Inline PDF is returned as an EmbeddedResource (application/pdf) — "
                            + "use export-view with inline false to write the file to disk for "
                            + "viewing or embedding in reports/wikis",
                    "Use get-view-contents to see element details and positions",
                    "Use export-view with format 'png' for LLM vision review (some MCP clients "
                            + "cannot render PDF embedded resources inline)",
                    "Use export-view with scale 2.0 for a larger PDF page");
        }
        // svg
        return List.of(
                "Use get-view-contents to see element details and positions",
                "Use export-view with format 'png' for bitmap rendering for LLM vision review",
                "Use export-view with format 'pdf' for print-ready vector output",
                "Use export-view with inline false to save to file");
    }

    private List<String> buildFileNextSteps(String format) {
        String alternativeFormat;
        String alternativeKind;
        if ("png".equals(format)) {
            alternativeFormat = "svg";
            alternativeKind = "scalable vector";
        } else if ("jpg".equals(format)) {
            alternativeFormat = "png";
            alternativeKind = "lossless bitmap";
        } else if ("svg".equals(format)) {
            alternativeFormat = "png";
            alternativeKind = "bitmap";
        } else { // pdf
            alternativeFormat = "png";
            alternativeKind = "bitmap (for LLM vision review)";
        }
        return List.of(
                "File written to local server filesystem — use export-view with "
                        + "inline true to retrieve image data directly for analysis",
                "Use get-view-contents to see element details and positions",
                "Use export-view with format '" + alternativeFormat + "' for "
                        + alternativeKind + " output");
    }
}
