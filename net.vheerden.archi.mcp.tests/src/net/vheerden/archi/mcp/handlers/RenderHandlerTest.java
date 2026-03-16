package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ExportResult;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ExportViewResultDto;

/**
 * Unit tests for {@link RenderHandler}.
 *
 * <p>Uses a stub ArchiModelAccessor — no EMF/SWT/OSGi runtime required.</p>
 */
public class RenderHandlerTest {

    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        objectMapper = new ObjectMapper();
    }

    // ---- Tool Registration ----

    @Test
    public void shouldRegisterExportViewTool() {
        StubAccessor accessor = new StubAccessor(true);
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        assertEquals(1, registry.getToolCount());
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("export-view");
        assertEquals("export-view", spec.tool().name());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveCorrectSchema() {
        StubAccessor accessor = new StubAccessor(true);
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("export-view").tool();
        McpSchema.JsonSchema schema = tool.inputSchema();
        assertNotNull(schema);
        assertEquals("object", schema.type());

        Map<String, Object> props = schema.properties();
        assertTrue(props.containsKey("viewId"));
        assertTrue(props.containsKey("format"));
        assertTrue(props.containsKey("scale"));
        assertTrue(props.containsKey("inline"));

        assertNotNull(schema.required());
        assertTrue(schema.required().contains("viewId"));
    }

    // ---- Inline PNG export (AC1, AC5) ----

    @Test
    public void shouldReturnImageContent_whenInlinePngExport() {
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 }; // PNG magic
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "png", "image/png", 800, 600, null, 150);
        ExportResult exportResult = new ExportResult(metadata, pngBytes, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", null, null);

        assertFalse(result.isError());
        assertEquals("view-1", accessor.lastViewId);
        assertEquals("Should have 2 content items (text + image)", 2, result.content().size());

        // First content: metadata JSON
        assertTrue("First content should be TextContent",
                result.content().get(0) instanceof McpSchema.TextContent);

        // Second content: ImageContent with base64 PNG
        assertTrue("Second content should be ImageContent",
                result.content().get(1) instanceof McpSchema.ImageContent);
        McpSchema.ImageContent imageContent =
                (McpSchema.ImageContent) result.content().get(1);
        assertEquals("image/png", imageContent.mimeType());
        String expectedBase64 = Base64.getEncoder().encodeToString(pngBytes);
        assertEquals(expectedBase64, imageContent.data());
    }

    // ---- Inline SVG export (AC2, AC6) ----

    @Test
    public void shouldReturnTextContent_whenInlineSvgExport() {
        String svgXml = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>";
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "svg", "image/svg+xml", 800, 600, null, 100);
        ExportResult exportResult = new ExportResult(metadata, null, svgXml);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "svg", null, null);

        assertFalse(result.isError());
        assertEquals("Should have 2 content items (metadata + SVG)", 2, result.content().size());

        // Both should be TextContent
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent);
        assertTrue(result.content().get(1) instanceof McpSchema.TextContent);

        McpSchema.TextContent svgContent =
                (McpSchema.TextContent) result.content().get(1);
        assertEquals(svgXml, svgContent.text());
    }

    // ---- File output (AC1) ----

    @Test
    public void shouldReturnFilePath_whenFileExport() throws Exception {
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "png", "image/png", 800, 600,
                "/tmp/archi-mcp-export/view-1_12345.png", 200);
        ExportResult exportResult = new ExportResult(metadata, null, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "png");
        args.put("inline", false);

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertFalse(result.isError());
        // File output returns single TextContent with JSON envelope
        assertEquals(1, result.content().size());
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent);

        Map<String, Object> envelope = parseJson(result);
        assertNotNull(envelope.get("result"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");
        assertEquals("/tmp/archi-mcp-export/view-1_12345.png", resultMap.get("filePath"));

        // Verify nextSteps are present and meaningful (Finding 13)
        assertNotNull("nextSteps should be present", envelope.get("nextSteps"));
        @SuppressWarnings("unchecked")
        java.util.List<String> nextSteps = (java.util.List<String>) envelope.get("nextSteps");
        assertFalse("nextSteps should not be empty", nextSteps.isEmpty());
    }

    // ---- Default parameters (AC4, AC7) ----

    @Test
    public void shouldUseDefaultFormat_whenFormatOmitted() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", null, null, null);

        assertFalse(result.isError());
        assertEquals("png", accessor.lastFormat);
    }

    @Test
    public void shouldUseDefaultScale_whenScaleOmitted() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", null, null);

        assertFalse(result.isError());
        assertEquals(1.0, accessor.lastScale, 0.001);
    }

    @Test
    public void shouldUseDefaultInline_whenInlineOmitted() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", null, null);

        assertFalse(result.isError());
        assertTrue("Default inline should be true", accessor.lastInline);
    }

    @Test
    public void shouldPassScaleToAccessor() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", 2.0, null);

        assertFalse(result.isError());
        assertEquals(2.0, accessor.lastScale, 0.001);
    }

    // ---- Error handling (AC3) ----

    @Test
    public void shouldReturnError_whenViewNotFound() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        accessor.throwOnExport = new ModelAccessException(
                "View not found: bad-id",
                ErrorCode.ELEMENT_NOT_FOUND,
                null, "Use get-views to list available view IDs", null);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("bad-id", "png", null, null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("ELEMENT_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldReturnError_whenFormatNotAvailable() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        accessor.throwOnExport = new ModelAccessException(
                "SVG export is not available",
                ErrorCode.FORMAT_NOT_AVAILABLE,
                null, "Use export-view with format 'png'", null);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "svg", null, null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("FORMAT_NOT_AVAILABLE", error.get("code"));
    }

    @Test
    public void shouldRequireViewId() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        // Invoke with no viewId
        McpSchema.CallToolResult result = invokeExportViewWithArgs(Collections.emptyMap());

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnError_whenModelNotLoaded() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", null, null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    // ---- Scale validation (Finding 3/4) ----

    @Test
    public void shouldReturnError_whenScaleTooLow() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", 0.0, null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnError_whenScaleTooHigh() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", 5.0, null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnError_whenScaleNaN() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", Double.NaN, null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- Helpers ----

    private ExportResult createDefaultPngResult() {
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "png", "image/png", 800, 600, null, 100);
        return new ExportResult(metadata, pngBytes, null);
    }

    private McpSchema.CallToolResult invokeExportView(
            String viewId, String format, Double scale, Boolean inline) {
        Map<String, Object> args = new HashMap<>();
        if (viewId != null) args.put("viewId", viewId);
        if (format != null) args.put("format", format);
        if (scale != null) args.put("scale", scale);
        if (inline != null) args.put("inline", inline);
        return invokeExportViewWithArgs(args);
    }

    private McpSchema.CallToolResult invokeExportViewWithArgs(Map<String, Object> args) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("export-view");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("export-view", args);
        return spec.callHandler().apply(null, request);
    }

    private McpServerFeatures.SyncToolSpecification findToolSpec(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
    }

    private Map<String, Object> parseJson(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().get(0);
        return objectMapper.readValue(content.text(),
                new TypeReference<Map<String, Object>>() {});
    }

    // ---- Stub ----

    private static class StubAccessor extends BaseTestAccessor {
        private ExportResult exportResult;
        ModelAccessException throwOnExport;
        String lastViewId;
        String lastFormat;
        double lastScale;
        boolean lastInline;

        StubAccessor(boolean modelLoaded) {
            super(modelLoaded);
        }

        StubAccessor(boolean modelLoaded, ExportResult exportResult) {
            super(modelLoaded);
            this.exportResult = exportResult;
        }

        @Override
        public ExportResult exportView(String viewId, String format,
                                        double scale, boolean inline) {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            if (throwOnExport != null) {
                throw throwOnExport;
            }
            this.lastViewId = viewId;
            this.lastFormat = format;
            this.lastScale = scale;
            this.lastInline = inline;
            return exportResult;
        }
    }
}
