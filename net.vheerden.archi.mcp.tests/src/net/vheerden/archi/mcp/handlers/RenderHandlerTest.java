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
        assertTrue(props.containsKey("outputDirectory"));

        assertNotNull(schema.required());
        assertTrue(schema.required().contains("viewId"));
        // quality prop added to schema; not in required list
        assertTrue("Schema should include quality prop",
                props.containsKey("quality"));
        assertFalse("quality should not be required",
                schema.required().contains("quality"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldExposeFourFormatEnum_inSchema_Story14_4() {
        StubAccessor accessor = new StubAccessor(true);
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("export-view").tool();
        Map<String, Object> props = tool.inputSchema().properties();
        Map<String, Object> formatProp = (Map<String, Object>) props.get("format");
        java.util.List<String> enumValues = (java.util.List<String>) formatProp.get("enum");
        assertEquals("Schema should expose 4 formats", 4, enumValues.size());
        assertTrue(enumValues.contains("png"));
        assertTrue(enumValues.contains("jpg"));
        assertTrue(enumValues.contains("svg"));
        assertTrue(enumValues.contains("pdf"));
    }

    // ---- Inline PNG export ----

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

    // ---- Inline SVG export ----

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

    // ---- File output ----

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

        // Verify nextSteps are present and meaningful
        assertNotNull("nextSteps should be present", envelope.get("nextSteps"));
        @SuppressWarnings("unchecked")
        java.util.List<String> nextSteps = (java.util.List<String>) envelope.get("nextSteps");
        assertFalse("nextSteps should not be empty", nextSteps.isEmpty());
    }

    // ---- Default parameters ----

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

    // ---- Error handling ----

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

    // ---- outputDirectory parameter ----

    @Test
    public void shouldPassOutputDirectory_whenFileExport() {
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "png", "image/png", 800, 600,
                "/custom/dir/view-1_12345.png", 200);
        ExportResult exportResult = new ExportResult(metadata, null, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "png");
        args.put("inline", false);
        args.put("outputDirectory", "/custom/dir");

        invokeExportViewWithArgs(args);

        assertEquals("/custom/dir", accessor.lastOutputDirectory);
        assertFalse(accessor.lastInline);
    }

    @Test
    public void shouldPassNullOutputDirectory_whenOmitted() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("inline", false);

        invokeExportViewWithArgs(args);

        assertNull("outputDirectory should be null when not provided",
                accessor.lastOutputDirectory);
    }

    @Test
    public void shouldTreatEmptyStringAsNull_forOutputDirectory() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("inline", false);
        args.put("outputDirectory", "");

        invokeExportViewWithArgs(args);

        assertNull("Empty outputDirectory should be treated as null",
                accessor.lastOutputDirectory);
    }

    @Test
    public void shouldTreatBlankStringAsNull_forOutputDirectory() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("inline", false);
        args.put("outputDirectory", "   ");

        invokeExportViewWithArgs(args);

        assertNull("Blank outputDirectory should be treated as null",
                accessor.lastOutputDirectory);
    }

    @Test
    public void shouldIgnoreOutputDirectory_whenInlineTrue() throws Exception {
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "png", "image/png", 800, 600, null, 100);
        ExportResult exportResult = new ExportResult(metadata, pngBytes, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "png");
        args.put("inline", true);
        args.put("outputDirectory", "/some/dir");

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertFalse(result.isError());
        // outputDirectory should NOT be passed to accessor when inline=true
        assertNull("outputDirectory should be null when inline is true",
                accessor.lastOutputDirectory);
        assertTrue("Should still be inline", accessor.lastInline);
    }

    @Test
    public void shouldIncludeNote_whenOutputDirectoryIgnored() throws Exception {
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "png", "image/png", 800, 600, null, 100);
        ExportResult exportResult = new ExportResult(metadata, pngBytes, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "png");
        args.put("inline", true);
        args.put("outputDirectory", "/some/dir");

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertFalse(result.isError());
        // First content item is metadata JSON — should contain "note" field
        McpSchema.TextContent textContent =
                (McpSchema.TextContent) result.content().get(0);
        Map<String, Object> parsed = objectMapper.readValue(textContent.text(),
                new TypeReference<Map<String, Object>>() {});
        assertEquals("outputDirectory is ignored when inline is true",
                parsed.get("note"));
    }

    @Test
    public void shouldReturnError_whenOutputDirectoryNotWritable() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        accessor.throwOnExport = new ModelAccessException(
                "Output directory is not writable: /forbidden/dir",
                ErrorCode.INVALID_PARAMETER,
                null,
                "Provide a writable directory path or omit outputDirectory to use the temp directory",
                null);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "png");
        args.put("inline", false);
        args.put("outputDirectory", "/forbidden/dir");

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldNotIncludeNote_whenNoOutputDirectoryAndInline() throws Exception {
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "png", "image/png", 800, 600, null, 100);
        ExportResult exportResult = new ExportResult(metadata, pngBytes, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", null, null);

        assertFalse(result.isError());
        McpSchema.TextContent textContent =
                (McpSchema.TextContent) result.content().get(0);
        Map<String, Object> parsed = objectMapper.readValue(textContent.text(),
                new TypeReference<Map<String, Object>>() {});
        assertNull("No note should be present when outputDirectory not provided",
                parsed.get("note"));
    }

    // ---- Scale validation ----

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

    // ---- JPG routing + alias + content type ----

    @Test
    public void shouldRouteFormatJpg_toJpegRenderer_whenInvoked_AC3() {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(90));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        invokeExportView("view-1", "jpg", null, null);

        assertEquals("jpg", accessor.lastFormat);
    }

    @Test
    public void shouldNormaliseJpegAliasToJpg_whenFormatIsJpeg_AC3() {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(90));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        invokeExportView("view-1", "jpeg", null, null);

        assertEquals("Handler should normalise 'jpeg' to 'jpg' before accessor dispatch",
                "jpg", accessor.lastFormat);
    }

    @Test
    public void shouldReturnImageContentJpeg_whenInlineJpg_AC5() {
        byte[] jpgBytes = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "jpg", "image/jpeg", 800, 600, null, 120);
        ExportResult exportResult = new ExportResult(metadata, jpgBytes, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "jpg", null, null);

        assertFalse(result.isError());
        assertEquals(2, result.content().size());
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent);
        assertTrue(result.content().get(1) instanceof McpSchema.ImageContent);
        McpSchema.ImageContent imageContent =
                (McpSchema.ImageContent) result.content().get(1);
        assertEquals("image/jpeg", imageContent.mimeType());
        assertEquals(Base64.getEncoder().encodeToString(jpgBytes), imageContent.data());
    }

    // ---- PDF routing + EmbeddedResource ----

    @Test
    public void shouldRouteFormatPdf_toPdfRenderer_whenInvoked_AC2() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPdfResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        invokeExportView("view-1", "pdf", null, null);

        assertEquals("pdf", accessor.lastFormat);
    }

    @Test
    public void shouldReturnEmbeddedResourcePdf_whenInlinePdf_AC5() {
        byte[] pdfBytes = new byte[] { '%', 'P', 'D', 'F', '-', '1', '.', '4' };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-pdf-1", "Pdf View", "pdf", "application/pdf", null, null, null, 250);
        ExportResult exportResult = new ExportResult(metadata, pdfBytes, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-pdf-1", "pdf", null, null);

        assertFalse(result.isError());
        assertEquals(2, result.content().size());
        assertTrue("First content should be TextContent (metadata)",
                result.content().get(0) instanceof McpSchema.TextContent);
        assertTrue("Second content should be EmbeddedResource for PDF",
                result.content().get(1) instanceof McpSchema.EmbeddedResource);
        McpSchema.EmbeddedResource embedded =
                (McpSchema.EmbeddedResource) result.content().get(1);
        assertTrue("Embedded resource should wrap BlobResourceContents",
                embedded.resource() instanceof McpSchema.BlobResourceContents);
        McpSchema.BlobResourceContents blob =
                (McpSchema.BlobResourceContents) embedded.resource();
        assertEquals("application/pdf", blob.mimeType());
        assertEquals(Base64.getEncoder().encodeToString(pdfBytes), blob.blob());
    }

    @Test
    public void shouldUseArchiExportUri_inPdfBlobResource_AC5() {
        byte[] pdfBytes = new byte[] { '%', 'P', 'D', 'F', '-' };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "abc-123", "View", "pdf", "application/pdf", null, null, null, 50);
        ExportResult exportResult = new ExportResult(metadata, pdfBytes, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("abc-123", "pdf", null, null);

        McpSchema.EmbeddedResource embedded =
                (McpSchema.EmbeddedResource) result.content().get(1);
        McpSchema.BlobResourceContents blob =
                (McpSchema.BlobResourceContents) embedded.resource();
        assertEquals("archi://export/abc-123.pdf", blob.uri());
    }

    // ---- quality param validation + default ----

    @Test
    public void shouldRejectQualityZero_whenFormatJpg_AC4() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(90));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "jpg");
        args.put("quality", 0);

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldRejectQualityAboveHundred_whenFormatJpg_AC4() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(90));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "jpg");
        args.put("quality", 101);

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldAcceptQualityOne_whenFormatJpg_AC4() {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(1));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "jpg");
        args.put("quality", 1);

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertFalse(result.isError());
        assertEquals(1, accessor.lastQuality);
    }

    @Test
    public void shouldAcceptQualityHundred_whenFormatJpg_AC4() {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(100));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "jpg");
        args.put("quality", 100);

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertFalse(result.isError());
        assertEquals(100, accessor.lastQuality);
    }

    @Test
    public void shouldDefaultQualityToNinety_whenFormatJpgAndQualityOmitted_AC4() {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(90));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        invokeExportView("view-1", "jpg", null, null);

        assertEquals("Default quality should be 90 when omitted",
                90, accessor.lastQuality);
    }

    @Test
    public void shouldIgnoreQuality_whenFormatPng_AC4() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "png");
        args.put("quality", 50);

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertFalse("PNG should accept quality param without error", result.isError());
        // quality is passed through but is irrelevant for PNG; lossless rendering ignores it
        assertEquals(50, accessor.lastQuality);
    }

    @Test
    public void shouldIgnoreQuality_whenFormatPdf_AC4() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPdfResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "pdf");
        args.put("quality", 50);

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertFalse("PDF should accept quality param without error", result.isError());
        assertEquals(50, accessor.lastQuality);
    }

    @Test
    public void shouldIgnoreQuality_whenFormatSvg_AC4() {
        String svgXml = "<svg/>";
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "svg", "image/svg+xml", null, null, null, 80);
        ExportResult exportResult = new ExportResult(metadata, null, svgXml);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "svg");
        args.put("quality", 50);

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertFalse("SVG should accept quality param without error", result.isError());
        assertEquals(50, accessor.lastQuality);
    }

    // ---- Unsupported format rejection ----

    @Test
    public void shouldRejectUnsupportedFormat_AC9() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        // Accessor's INVALID_PARAMETER for unknown formats; the handler propagates it.
        accessor.throwOnExport = new ModelAccessException(
                "Unsupported export format: tiff. Supported formats: png, jpg, svg, pdf",
                ErrorCode.INVALID_PARAMETER,
                null,
                "Use one of: png, jpg, svg, pdf",
                null);
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "tiff", null, null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- per-format nextSteps ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldEmitPdfInlineNextSteps_whenInlinePdf_AC10() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPdfResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "pdf", null, null);

        assertFalse(result.isError());
        McpSchema.TextContent meta = (McpSchema.TextContent) result.content().get(0);
        Map<String, Object> parsed = objectMapper.readValue(meta.text(),
                new TypeReference<Map<String, Object>>() {});
        java.util.List<String> nextSteps = (java.util.List<String>) parsed.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        boolean mentionsPng = nextSteps.stream()
                .anyMatch(s -> s.contains("format 'png'"));
        assertTrue("PDF inline nextSteps should mention 'format \\'png\\'' for vision review",
                mentionsPng);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldEmitJpgFileNextSteps_whenInlineFalseJpg_AC10() throws Exception {
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "View", "jpg", "image/jpeg", 800, 600,
                "/tmp/archi-mcp-export/view-1.jpg", 100);
        ExportResult exportResult = new ExportResult(metadata, null, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "jpg");
        args.put("inline", false);

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        Map<String, Object> envelope = parseJson(result);
        java.util.List<String> nextSteps = (java.util.List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        boolean mentionsPng = nextSteps.stream()
                .anyMatch(s -> s.contains("'png'"));
        assertTrue("JPG file nextSteps should suggest PNG alternative", mentionsPng);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldEmitPdfFileNextSteps_whenInlineFalsePdf_AC10() throws Exception {
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "View", "pdf", "application/pdf", null, null,
                "/tmp/archi-mcp-export/view-1.pdf", 200);
        ExportResult exportResult = new ExportResult(metadata, null, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);

        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "pdf");
        args.put("inline", false);

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        Map<String, Object> envelope = parseJson(result);
        java.util.List<String> nextSteps = (java.util.List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        boolean mentionsPng = nextSteps.stream()
                .anyMatch(s -> s.contains("'png'"));
        assertTrue("PDF file nextSteps should suggest PNG alternative for vision review",
                mentionsPng);
    }

    // ---- model mutation stamp (staleness detection) ----

    @Test
    public void shouldStampModelVersion_whenInlinePngExport() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", null, null);

        assertFalse(result.isError());
        Map<String, Object> wrapper = parseInlineWrapper(result);
        assertEquals("Inline PNG response should carry the model mutation stamp",
                "42", wrapper.get("modelVersion"));
    }

    @Test
    public void shouldStampModelVersion_whenInlineJpgExport() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(90));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "jpg", null, null);

        assertFalse(result.isError());
        Map<String, Object> wrapper = parseInlineWrapper(result);
        assertEquals("Inline JPG response should carry the model mutation stamp",
                "42", wrapper.get("modelVersion"));
    }

    @Test
    public void shouldStampModelVersion_whenInlineSvgExport() throws Exception {
        String svgXml = "<svg/>";
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "svg", "image/svg+xml", null, null, null, 80);
        ExportResult exportResult = new ExportResult(metadata, null, svgXml);
        StubAccessor accessor = new StubAccessor(true, exportResult);
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "svg", null, null);

        assertFalse(result.isError());
        Map<String, Object> wrapper = parseInlineWrapper(result);
        assertEquals("Inline SVG response should carry the model mutation stamp",
                "42", wrapper.get("modelVersion"));
    }

    @Test
    public void shouldStampModelVersion_whenInlinePdfExport() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPdfResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "pdf", null, null);

        assertFalse(result.isError());
        Map<String, Object> wrapper = parseInlineWrapper(result);
        assertEquals("Inline PDF response should carry the model mutation stamp",
                "42", wrapper.get("modelVersion"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldStampModelVersionInMeta_whenFileExport() throws Exception {
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "png", "image/png", 800, 600,
                "/tmp/archi-mcp-export/view-1.png", 200);
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
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull("File export envelope should have _meta", meta);
        assertEquals("File export _meta should carry the model mutation stamp",
                "42", meta.get("modelVersion"));
    }

    @Test
    public void shouldStampModelVersion_alongsideNote_whenOutputDirIgnored() throws Exception {
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "png", "image/png", 800, 600, null, 100);
        ExportResult exportResult = new ExportResult(metadata, pngBytes, null);
        StubAccessor accessor = new StubAccessor(true, exportResult);
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("format", "png");
        args.put("inline", true);
        args.put("outputDirectory", "/some/dir");

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertFalse(result.isError());
        Map<String, Object> wrapper = parseInlineWrapper(result);
        // The stamp must survive alongside the conditional outputDirIgnored note.
        assertEquals("42", wrapper.get("modelVersion"));
        assertEquals("outputDirectory is ignored when inline is true",
                wrapper.get("note"));
    }

    @Test
    public void shouldReflectNewModelVersion_afterMutation() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        // Export captures the stamp at the model state it rendered against.
        accessor.modelVersion = "7";
        McpSchema.CallToolResult before = invokeExportView("view-1", "png", null, null);
        Object stampBefore = parseInlineWrapper(before).get("modelVersion");

        // A mutation bumps the monotonic counter; a fresh export reflects the new state.
        accessor.modelVersion = "8";
        McpSchema.CallToolResult after = invokeExportView("view-1", "png", null, null);
        Object stampAfter = parseInlineWrapper(after).get("modelVersion");

        assertEquals("7", stampBefore);
        assertEquals("8", stampAfter);
        assertNotEquals("A render taken before a mutation must be distinguishable from one after",
                stampBefore, stampAfter);
    }

    // ---- Helpers ----

    private Map<String, Object> parseInlineWrapper(McpSchema.CallToolResult result)
            throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().get(0);
        return objectMapper.readValue(content.text(),
                new TypeReference<Map<String, Object>>() {});
    }

    private ExportResult createDefaultJpgResult(int quality) {
        byte[] jpgBytes = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "jpg", "image/jpeg", 800, 600, null, 100);
        // quality is captured by the accessor stub, not encoded into the metadata
        return new ExportResult(metadata, jpgBytes, null);
    }

    private ExportResult createDefaultPdfResult() {
        byte[] pdfBytes = new byte[] { '%', 'P', 'D', 'F', '-', '1', '.', '4' };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "pdf", "application/pdf", null, null, null, 200);
        return new ExportResult(metadata, pdfBytes, null);
    }

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
        int lastQuality;
        boolean lastInline;
        String lastOutputDirectory;
        String modelVersion = "42";

        StubAccessor(boolean modelLoaded) {
            super(modelLoaded);
        }

        StubAccessor(boolean modelLoaded, ExportResult exportResult) {
            super(modelLoaded);
            this.exportResult = exportResult;
        }

        @Override
        public String getModelVersion() {
            return modelVersion;
        }

        @Override
        public ExportResult exportView(String viewId, String format,
                                        double scale, int quality, boolean inline,
                                        String outputDirectory) {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            if (throwOnExport != null) {
                throw throwOnExport;
            }
            this.lastViewId = viewId;
            this.lastFormat = format;
            this.lastScale = scale;
            this.lastQuality = quality;
            this.lastInline = inline;
            this.lastOutputDirectory = outputDirectory;
            return exportResult;
        }
    }
}
