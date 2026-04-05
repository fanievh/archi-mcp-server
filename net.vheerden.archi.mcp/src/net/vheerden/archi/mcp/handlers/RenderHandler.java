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
 * Handler for view rendering/export tools: export-view (Story 8-1).
 *
 * <p>Renders ArchiMate views as images (PNG/SVG) and returns them either
 * inline (as MCP {@link McpSchema.ImageContent} or {@link McpSchema.TextContent})
 * or written to a file.</p>
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
     * Registers: export-view (Story 8-1).
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
                "Output format: 'png' (always available) or 'svg' "
                        + "(requires optional SVG export plugin). Default: 'png'");
        formatProp.put("enum", List.of("png", "svg"));

        Map<String, Object> scaleProp = new LinkedHashMap<>();
        scaleProp.put("type", "number");
        scaleProp.put("description",
                "Rendering scale factor (0.1 to 4.0). "
                        + "0.5 = half size, 2.0 = double size. Default: 1.0");

        Map<String, Object> inlineProp = new LinkedHashMap<>();
        inlineProp.put("type", "boolean");
        inlineProp.put("description",
                "Return image data in the response (true) or write "
                        + "to a file and return the path (false). Inline mode returns "
                        + "PNG as ImageContent for LLM vision analysis. Default: true");

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
        properties.put("inline", inlineProp);
        properties.put("outputDirectory", outputDirProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("export-view")
                .description("[Rendering] Renders an ArchiMate view as an image. "
                        + "Returns image data inline (default) or writes to file. "
                        + "PNG uses Archi's native renderer which computes connection "
                        + "endpoint positions at element perimeter intersections "
                        + "(ChopboxAnchor) — the rendered image shows true visual "
                        + "attachment points, not the center-based reference coordinates "
                        + "returned by get-view-contents. SVG requires the optional "
                        + "SVG export plugin. Use for visual verification of layout "
                        + "changes and connection routing via LLM vision capabilities.")
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
            double scale = HandlerUtils.optionalDoubleParam(args, "scale", 1.0);
            if (!Double.isFinite(scale) || scale < 0.1 || scale > 4.0) {
                throw new ModelAccessException(
                        "Scale must be between 0.1 and 4.0, got: " + scale,
                        net.vheerden.archi.mcp.response.ErrorCode.INVALID_PARAMETER,
                        null,
                        "Provide a scale value between 0.1 and 4.0 (1.0 = 100%)",
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

            ExportResult result = accessor.exportView(viewId, format, scale, inline,
                    effectiveOutputDir);

            if (inline && "png".equals(format)) {
                return buildInlinePngResponse(result, outputDirIgnored);
            } else if (inline && "svg".equals(format)) {
                return buildInlineSvgResponse(result, outputDirIgnored);
            } else {
                return buildFileResponse(result, format);
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
                                                              boolean outputDirIgnored) {
        String base64 = Base64.getEncoder().encodeToString(result.imageBytes());

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("metadata", result.metadata());
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

    private McpSchema.CallToolResult buildInlineSvgResponse(ExportResult result,
                                                              boolean outputDirIgnored) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("metadata", result.metadata());
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

    private McpSchema.CallToolResult buildFileResponse(ExportResult result, String format) {
        Map<String, Object> envelope = formatter.formatSuccess(
                result.metadata(),
                buildFileNextSteps(format),
                null, 1, 1, false);
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
        return List.of(
                "Use get-view-contents to see element details and positions",
                "Use export-view with format 'png' for bitmap rendering",
                "Use export-view with inline false to save to file");
    }

    private List<String> buildFileNextSteps(String format) {
        return List.of(
                "File written to local server filesystem — use export-view with "
                        + "inline true to retrieve image data directly for analysis",
                "Use get-view-contents to see element details and positions",
                "Use export-view with format '"
                        + ("png".equals(format) ? "svg" : "png") + "' for "
                        + ("png".equals(format) ? "scalable vector" : "bitmap") + " output");
    }
}
