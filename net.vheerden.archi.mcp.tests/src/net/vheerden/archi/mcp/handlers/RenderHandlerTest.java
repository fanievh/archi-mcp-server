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
import net.vheerden.archi.mcp.model.ContentBounds;
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

    // ---- Raster area guard ----

    /**
     * Content bounds of the largest view in the reference corpus — the one whose
     * scale-2.0 export exhausted the machine. Held here as the discriminating
     * input for the guard, in the frame the accessor reports.
     */
    private static final ContentBounds LARGE_VIEW_BOUNDS = new ContentBounds(12, 12, 6777, 6538);

    /** Physical memory that was actually free when that export died: 412 MB. */
    private static final long CRASH_SITE_MEMORY = 422256L * 1024L;

    /** A pool large enough that no projection in this suite can exhaust it. */
    private static final long GENEROUS_MEMORY = 32L * 1024L * 1024L * 1024L;

    @Test
    public void shouldReturnError_whenProjectedRasterExceedsBudget() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        accessor.contentBounds = LARGE_VIEW_BOUNDS;
        RenderHandler handler = new RenderHandler(accessor, formatter, registry,
                () -> CRASH_SITE_MEMORY);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", 2.0, null);

        assertTrue("A render projected at 711 MB against 412 MB of free memory must be refused",
                result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldNotDispatchRender_whenProjectedRasterExceedsBudget() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        accessor.contentBounds = LARGE_VIEW_BOUNDS;
        RenderHandler handler = new RenderHandler(accessor, formatter, registry,
                () -> CRASH_SITE_MEMORY);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", 2.0, null);

        assertTrue(result.isError());
        assertFalse("The refusal must happen before the render is dispatched",
                accessor.exportCalled);
    }

    @Test
    public void shouldReadBudget_whenSameViewAndScaleFacesDifferentPools() throws Exception {
        StubAccessor tight = new StubAccessor(true, createDefaultPngResult());
        tight.contentBounds = LARGE_VIEW_BOUNDS;
        RenderHandler tightHandler = new RenderHandler(tight, formatter, registry,
                () -> CRASH_SITE_MEMORY);
        tightHandler.registerTools();
        McpSchema.CallToolResult refused = invokeExportView("view-1", "png", 2.0, null);

        setUp();
        StubAccessor roomy = new StubAccessor(true, createDefaultPngResult());
        roomy.contentBounds = LARGE_VIEW_BOUNDS;
        RenderHandler roomyHandler = new RenderHandler(roomy, formatter, registry,
                () -> GENEROUS_MEMORY);
        roomyHandler.registerTools();
        McpSchema.CallToolResult accepted = invokeExportView("view-1", "png", 2.0, null);

        assertTrue("The same view at the same scale must be refused under a small pool",
                refused.isError());
        assertFalse("The same view at the same scale must be accepted under a large pool",
                accepted.isError());
        assertTrue("The accepted call must actually reach the renderer", roomy.exportCalled);
    }

    @Test
    public void shouldAllowMaximumScale_whenViewIsSmall() {
        // The guard is driven by projected area, not by the multiplier. A small
        // view at the largest accepted factor must still render.
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry,
                () -> CRASH_SITE_MEMORY);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", 4.0, null);

        assertFalse("A small view at the largest accepted scale must still export",
                result.isError());
        assertTrue(accessor.exportCalled);
        assertEquals(4.0, accessor.lastScale, 0.001);
    }

    @Test
    public void shouldAllowLargeView_whenBudgetAccommodatesIt() {
        // This view at this scale has demonstrably exported before. A guard that
        // refuses the models the tool exists for is not a fix.
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        accessor.contentBounds = LARGE_VIEW_BOUNDS;
        RenderHandler handler = new RenderHandler(accessor, formatter, registry,
                () -> GENEROUS_MEMORY);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", 1.0, null);

        assertFalse(result.isError());
        assertTrue(accessor.exportCalled);
    }

    @Test
    public void shouldNameProjectionBudgetAndFittingScale_whenRefusing() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        accessor.contentBounds = LARGE_VIEW_BOUNDS;
        RenderHandler handler = new RenderHandler(accessor, formatter, registry,
                () -> CRASH_SITE_MEMORY);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", 2.0, null);

        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        String message = (String) error.get("message");
        String details = (String) error.get("details");
        String suggestion = (String) error.get("suggestedCorrection");

        assertTrue("must name the projected dimensions: " + message,
                message.contains("13574 x 13096"));
        assertTrue("must name the byte estimate: " + message, message.contains("678.1 MB"));
        assertTrue("must name the budget it compared against: " + message,
                message.contains("206.2 MB"));
        assertTrue("must name the pool the budget came from: " + message,
                message.contains("412.4 MB"));
        assertTrue("details must carry the exact byte count: " + details,
                details.contains("711060416"));
        assertTrue("must name the largest scale that would fit: " + suggestion,
                suggestion.contains("1.1"));
        assertTrue("must offer the vector escape: " + suggestion,
                suggestion.contains("'svg'") && suggestion.contains("'pdf'"));
    }

    @Test
    public void shouldGuardTheJpegAlias_whenItNormalisesToJpg() {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(90));
        accessor.contentBounds = LARGE_VIEW_BOUNDS;
        RenderHandler handler = new RenderHandler(accessor, formatter, registry,
                () -> CRASH_SITE_MEMORY);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "jpeg", 2.0, null);

        assertTrue("the alias must reach the guard, not bypass it", result.isError());
        assertFalse(accessor.exportCalled);
    }

    @Test
    public void shouldNotGuardVectorFormats_whenRasterWouldBeRefused() {
        // SVG and PDF do not forward scale and allocate no bitmap, so the same
        // view at the same scale under the same pool must go straight through.
        for (String format : new String[] { "svg", "pdf" }) {
            setUp();
            ExportResult canned = "svg".equals(format)
                    ? new ExportResult(new ExportViewResultDto("view-1", "Test View", "svg",
                            "image/svg+xml", null, null, null, 10), null, "<svg/>")
                    : createDefaultPdfResult();
            StubAccessor accessor = new StubAccessor(true, canned);
            accessor.contentBounds = LARGE_VIEW_BOUNDS;
            RenderHandler handler = new RenderHandler(accessor, formatter, registry,
                    () -> CRASH_SITE_MEMORY);
            handler.registerTools();

            McpSchema.CallToolResult result = invokeExportView("view-1", format, 2.0, null);

            assertFalse(format + " must not be guarded", result.isError());
            assertTrue(format + " must reach the renderer", accessor.exportCalled);
        }
    }

    @Test
    public void shouldNotFail_whenViewHasNoContentToBound() {
        // An empty view has no content bounds; the renderer draws a fixed blank
        // square for it, and the guard must project that rather than throw.
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        accessor.contentBounds = null;
        RenderHandler handler = new RenderHandler(accessor, formatter, registry,
                () -> CRASH_SITE_MEMORY);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", 4.0, null);

        assertFalse(result.isError());
        assertTrue(accessor.exportCalled);
    }

    // ---- JPG routing + alias + content type ----

    @Test
    public void shouldRouteFormatJpg_toJpegRenderer_whenInvoked() {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(90));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        invokeExportView("view-1", "jpg", null, null);

        assertEquals("jpg", accessor.lastFormat);
    }

    @Test
    public void shouldNormaliseJpegAliasToJpg_whenFormatIsJpeg() {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(90));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        invokeExportView("view-1", "jpeg", null, null);

        assertEquals("Handler should normalise 'jpeg' to 'jpg' before accessor dispatch",
                "jpg", accessor.lastFormat);
    }

    @Test
    public void shouldReturnImageContentJpeg_whenInlineJpg() {
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
    public void shouldRouteFormatPdf_toPdfRenderer_whenInvoked() {
        StubAccessor accessor = new StubAccessor(true, createDefaultPdfResult());
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        invokeExportView("view-1", "pdf", null, null);

        assertEquals("pdf", accessor.lastFormat);
    }

    @Test
    public void shouldReturnEmbeddedResourcePdf_whenInlinePdf() {
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
    public void shouldUseArchiExportUri_inPdfBlobResource() {
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
    public void shouldRejectQualityZero_whenFormatJpg() throws Exception {
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
    public void shouldRejectQualityAboveHundred_whenFormatJpg() throws Exception {
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
    public void shouldAcceptQualityOne_whenFormatJpg() {
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
    public void shouldAcceptQualityHundred_whenFormatJpg() {
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
    public void shouldDefaultQualityToNinety_whenFormatJpgAndQualityOmitted() {
        StubAccessor accessor = new StubAccessor(true, createDefaultJpgResult(90));
        RenderHandler handler = new RenderHandler(accessor, formatter, registry);
        handler.registerTools();

        invokeExportView("view-1", "jpg", null, null);

        assertEquals("Default quality should be 90 when omitted",
                90, accessor.lastQuality);
    }

    @Test
    public void shouldIgnoreQuality_whenFormatPng() {
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
    public void shouldIgnoreQuality_whenFormatPdf() {
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
    public void shouldIgnoreQuality_whenFormatSvg() {
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
    public void shouldRejectUnsupportedFormat() throws Exception {
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
    public void shouldEmitPdfInlineNextSteps_whenInlinePdf() throws Exception {
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
    public void shouldEmitJpgFileNextSteps_whenInlineFalseJpg() throws Exception {
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
    public void shouldEmitPdfFileNextSteps_whenInlineFalsePdf() throws Exception {
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

    // ---- Read-only server ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRefuseFileOutputBeforeRendering_whenTheServerIsReadOnly() throws Exception {
        for (String outputDirectory : new String[] {null, "/custom/dir"}) {
            registry = new CommandRegistry();
            StubAccessor accessor = new StubAccessor(true);
            new RenderHandler(accessor, formatter, registry, true).registerTools();
            Map<String, Object> args = new HashMap<>();
            args.put("viewId", "view-1");
            args.put("format", "png");
            args.put("inline", false);
            if (outputDirectory != null) {
                args.put("outputDirectory", outputDirectory);
            }

            McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

            assertTrue(result.isError());
            Map<String, Object> error = (Map<String, Object>) parseJson(result).get("error");
            assertEquals("INVALID_PARAMETER", error.get("code"));
            assertTrue(String.valueOf(error.get("message")),
                    String.valueOf(error.get("message")).contains("This server is read-only"));
            assertFalse("nothing is rendered or written", accessor.exportCalled);
        }
    }

    @Test
    public void shouldStillReturnTheImageInline_whenTheServerIsReadOnly() throws Exception {
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        ExportViewResultDto metadata = new ExportViewResultDto(
                "view-1", "Test View", "png", "image/png", 800, 600, null, 150);
        StubAccessor accessor = new StubAccessor(true, new ExportResult(metadata, pngBytes, null));
        new RenderHandler(accessor, formatter, registry, true).registerTools();
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        args.put("outputDirectory", "/custom/dir");

        McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

        assertFalse(result.isError());
        assertTrue(accessor.exportCalled);
        assertTrue(accessor.lastInline);
        assertNull("an inline export never passes a directory on", accessor.lastOutputDirectory);
        assertTrue("the ignored directory is still disclosed",
                ((McpSchema.TextContent) result.content().get(0)).text()
                        .contains("outputDirectory is ignored when inline is true"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldSayInTheSchemaThatFilesAreNotWritten_onlyWhenTheServerIsReadOnly() {
        new RenderHandler(new StubAccessor(true), formatter, registry, true).registerTools();
        Map<String, Object> readOnlyProps = findToolSpec("export-view").tool().inputSchema().properties();
        String inline = String.valueOf(((Map<String, Object>) readOnlyProps.get("inline")).get("description"));
        String outputDirectory = String.valueOf(
                ((Map<String, Object>) readOnlyProps.get("outputDirectory")).get("description"));
        assertTrue(inline, inline.contains("This server is read-only"));
        assertTrue(outputDirectory, outputDirectory.contains("This server is read-only"));

        registry = new CommandRegistry();
        new RenderHandler(new StubAccessor(true), formatter, registry).registerTools();
        Map<String, Object> fullProps = findToolSpec("export-view").tool().inputSchema().properties();
        assertFalse(String.valueOf(fullProps.get("inline")).contains("read-only"));
        assertFalse(String.valueOf(fullProps.get("outputDirectory")).contains("read-only"));
    }

    // ---- The size of an inline response ----

    /** Raw bytes whose base64 encoding is exactly the inline cap. */
    private static final int RAW_AT_CAP = RenderHandler.MAX_INLINE_PAYLOAD_BYTES / 4 * 3;

    private StubAccessor exporting(String format, byte[] bytes, String svg) {
        String mime = switch (format) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "svg" -> "image/svg+xml";
            default -> "application/pdf";
        };
        return new StubAccessor(true, new ExportResult(
                new ExportViewResultDto("view-1", "Test View", format, mime, 800, 600, null, 10), bytes, svg));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> errorOf(McpSchema.CallToolResult result) throws Exception {
        assertTrue("expected an error response", result.isError());
        return (Map<String, Object>) parseJson(result).get("error");
    }

    @Test
    public void shouldRefuseAnInlinePngLargerThanTheCap_namingTheBytesTheCapAndAScale() throws Exception {
        new RenderHandler(exporting("png", new byte[RAW_AT_CAP + 1], null), formatter, registry).registerTools();

        Map<String, Object> error = errorOf(invokeExportView("view-1", "png", null, null));

        assertEquals("INVALID_PARAMETER", error.get("code"));
        String message = (String) error.get("message");
        assertTrue(message, message.contains("5242884 bytes"));
        assertTrue(message, message.contains("5242880 bytes"));
        String correction = (String) error.get("suggestedCorrection");
        assertTrue(correction, correction.contains("Retry at scale 0.99 or lower"));
        assertTrue("a full server can write the file instead: " + correction, correction.contains("inline"));
    }

    @Test
    public void shouldSuggestAScaleShrunkByTheSquareRootOfTheOverrun_forAnInlineJpg() throws Exception {
        new RenderHandler(exporting("jpg", new byte[RAW_AT_CAP * 2], null), formatter, registry).registerTools();

        Map<String, Object> error = errorOf(invokeExportView("view-1", "jpg", 2.0, null));

        String correction = (String) error.get("suggestedCorrection");
        assertTrue(correction, correction.contains("Retry at scale 1.41 or lower"));
        assertTrue((String) error.get("message"), ((String) error.get("message")).contains("10485760 bytes"));
    }

    @Test
    public void shouldFitTheScaleByTheSquareRootOfTheOverrun_evenBelowTheSmallestAccepted() {
        assertEquals(0.02, RenderHandler.scaleWithinInlineCap(0.2,
                RenderHandler.MAX_INLINE_PAYLOAD_BYTES * 100L), 0.0);
        assertEquals(0.5, RenderHandler.scaleWithinInlineCap(1.0,
                RenderHandler.MAX_INLINE_PAYLOAD_BYTES * 4L), 0.0);
    }

    @Test
    public void shouldSayNoAcceptedScaleFits_ratherThanSuggestOneThatStillOverruns() throws Exception {
        // At the smallest accepted scale, a payload four times the cap would need scale 0.05.
        new RenderHandler(exporting("png", new byte[RAW_AT_CAP * 4], null), formatter, registry).registerTools();

        String correction = (String) errorOf(invokeExportView("view-1", "png", RenderHandler.MIN_SCALE, null))
                .get("suggestedCorrection");

        assertTrue(correction, correction.startsWith("No accepted scale brings this view within the cap: the "
                + "smallest is 0.1."));
        assertFalse("no scale is suggested: " + correction, correction.contains("Retry at scale"));
        assertTrue("a full server can still write the file: " + correction, correction.contains("inline to false"));
    }

    @Test
    public void shouldSendAnInlineJpgAndPdfAtTheCap_andRefuseThemOneByteOver() {
        for (String format : new String[] {"jpg", "pdf"}) {
            registry = new net.vheerden.archi.mcp.registry.CommandRegistry();
            new RenderHandler(exporting(format, new byte[RAW_AT_CAP], null), formatter, registry).registerTools();
            assertFalse(format + " at the cap", invokeExportView("view-1", format, null, null).isError());

            registry = new net.vheerden.archi.mcp.registry.CommandRegistry();
            new RenderHandler(exporting(format, new byte[RAW_AT_CAP + 1], null), formatter, registry)
                    .registerTools();
            assertTrue(format + " one byte over", invokeExportView("view-1", format, null, null).isError());
        }
    }

    @Test
    public void shouldRefuseAnInlinePdfLargerThanTheCap_andPointToARasterAtALowerScale() throws Exception {
        new RenderHandler(exporting("pdf", new byte[RAW_AT_CAP + 1], null), formatter, registry).registerTools();

        Map<String, Object> error = errorOf(invokeExportView("view-1", "pdf", null, null));

        assertEquals("INVALID_PARAMETER", error.get("code"));
        String correction = (String) error.get("suggestedCorrection");
        assertTrue(correction, correction.contains("ignore scale"));
        assertTrue(correction, correction.contains("png"));
        assertFalse(correction, correction.contains("Retry at scale"));
    }

    @Test
    public void shouldCountAnInlineSvgInUtf8Bytes() throws Exception {
        String svg = "\u00e9".repeat(RenderHandler.MAX_INLINE_PAYLOAD_BYTES / 2 + 1);
        new RenderHandler(exporting("svg", null, svg), formatter, registry).registerTools();

        Map<String, Object> error = errorOf(invokeExportView("view-1", "svg", null, null));

        assertTrue((String) error.get("message"), ((String) error.get("message")).contains("5242882 bytes"));
    }

    @Test
    public void shouldRefuseAnInlineSvgOneByteOverTheCap() throws Exception {
        new RenderHandler(exporting("svg", null, "x".repeat(RenderHandler.MAX_INLINE_PAYLOAD_BYTES + 1)),
                formatter, registry).registerTools();

        assertEquals("INVALID_PARAMETER", errorOf(invokeExportView("view-1", "svg", null, null)).get("code"));
    }

    @Test
    public void shouldSendAnInlineSvgAtTheCapAndOneByteUnder() {
        for (int length : new int[] {RenderHandler.MAX_INLINE_PAYLOAD_BYTES, RenderHandler.MAX_INLINE_PAYLOAD_BYTES - 1}) {
            registry = new net.vheerden.archi.mcp.registry.CommandRegistry();
            new RenderHandler(exporting("svg", null, "x".repeat(length)), formatter, registry).registerTools();

            McpSchema.CallToolResult result = invokeExportView("view-1", "svg", null, null);

            assertFalse("length " + length, result.isError());
            assertEquals(length, ((McpSchema.TextContent) result.content().get(1)).text().length());
        }
    }

    @Test
    public void shouldSendAnInlinePngWhoseEncodingIsExactlyTheCap_orUnder() {
        for (int raw : new int[] {RAW_AT_CAP, RAW_AT_CAP - 1}) {
            registry = new net.vheerden.archi.mcp.registry.CommandRegistry();
            new RenderHandler(exporting("png", new byte[raw], null), formatter, registry).registerTools();

            McpSchema.CallToolResult result = invokeExportView("view-1", "png", null, null);

            assertFalse("raw " + raw, result.isError());
            assertEquals(RenderHandler.MAX_INLINE_PAYLOAD_BYTES,
                    ((McpSchema.ImageContent) result.content().get(1)).data().length());
        }
    }

    @Test
    public void shouldNotCapFileOutput() {
        new RenderHandler(exporting("png", new byte[RAW_AT_CAP + 1], null), formatter, registry).registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", null, false);

        assertFalse(result.isError());
    }

    @Test
    public void shouldNotSuggestWritingAFile_onAReadOnlyServer() throws Exception {
        new RenderHandler(exporting("png", new byte[RAW_AT_CAP + 1], null), formatter, registry, true)
                .registerTools();

        String correction = (String) errorOf(invokeExportView("view-1", "png", null, null))
                .get("suggestedCorrection");

        assertTrue(correction, correction.contains("Retry at scale 0.99 or lower"));
        assertFalse(correction, correction.contains("inline"));
    }

    // ---- File output on a server other machines can reach ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRefuseFileOutputBeforeRendering_whenTheServerIsReachableAndNotAllowed() throws Exception {
        for (String outputDirectory : new String[] {null, "/custom/dir"}) {
            registry = new CommandRegistry();
            StubAccessor accessor = new StubAccessor(true);
            new RenderHandler(accessor, formatter, registry, false, true).registerTools();
            Map<String, Object> args = new HashMap<>();
            args.put("viewId", "view-1");
            args.put("format", "png");
            args.put("inline", false);
            if (outputDirectory != null) {
                args.put("outputDirectory", outputDirectory);
            }

            McpSchema.CallToolResult result = invokeExportViewWithArgs(args);

            Map<String, Object> error = (Map<String, Object>) parseJson(result).get("error");
            assertTrue(result.isError());
            assertEquals("INVALID_PARAMETER", error.get("code"));
            assertEquals(RenderHandler.SERVER_FILES_REFUSED, error.get("message"));
            assertEquals("Omit inline, or set it to true, to receive the image in the response",
                    error.get("suggestedCorrection"));
            assertFalse("nothing is rendered or written", accessor.exportCalled);
        }
    }

    @Test
    public void shouldStillReturnTheImageInline_whenTheServerIsReachableAndNotAllowed() {
        byte[] pngBytes = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
        StubAccessor accessor = exporting("png", pngBytes, null);
        new RenderHandler(accessor, formatter, registry, false, true).registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", null, null);

        assertFalse(result.isError());
        assertTrue(accessor.lastInline);
    }

    @Test
    public void shouldNotSuggestWritingAFile_whenTheServerIsReachableAndNotAllowed() throws Exception {
        new RenderHandler(exporting("png", new byte[RAW_AT_CAP + 1], null), formatter, registry, false, true)
                .registerTools();

        String correction = (String) errorOf(invokeExportView("view-1", "png", null, null))
                .get("suggestedCorrection");

        assertTrue(correction, correction.contains("Retry at scale 0.99 or lower"));
        assertFalse(correction, correction.contains("inline"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldSayInTheSchemaThatFilesAreRefused_onlyWhenTheServerIsReachableAndNotAllowed() {
        new RenderHandler(new StubAccessor(true), formatter, registry, false, true).registerTools();
        Map<String, Object> props = findToolSpec("export-view").tool().inputSchema().properties();
        assertTrue(String.valueOf(props.get("inline")).contains(RenderHandler.SERVER_FILES_REFUSED));
        assertTrue(String.valueOf(props.get("outputDirectory")).contains(RenderHandler.SERVER_FILES_REFUSED));

        for (boolean readOnly : new boolean[] {false, true}) {
            registry = new CommandRegistry();
            new RenderHandler(new StubAccessor(true), formatter, registry, readOnly, readOnly).registerTools();
            Map<String, Object> other = findToolSpec("export-view").tool().inputSchema().properties();
            assertFalse("readOnly " + readOnly, String.valueOf(other.get("inline"))
                    .contains(RenderHandler.SERVER_FILES_REFUSED));
            assertFalse("readOnly " + readOnly, String.valueOf(other.get("outputDirectory"))
                    .contains(RenderHandler.SERVER_FILES_REFUSED));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldAnswerWithTheReadOnlyRefusal_onAReadOnlyServerThatIsAlsoReachable() throws Exception {
        new RenderHandler(new StubAccessor(true), formatter, registry, true, true).registerTools();

        Map<String, Object> error = errorOf(invokeExportView("view-1", "png", null, false));

        assertEquals(RenderHandler.READ_ONLY_NO_FILES, error.get("message"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldStateTheInlineCapInTheDescription() {
        new RenderHandler(new StubAccessor(true), formatter, registry).registerTools();

        Map<String, Object> inline = (Map<String, Object>) findToolSpec("export-view").tool().inputSchema()
                .properties().get("inline");
        assertTrue(String.valueOf(inline.get("description")),
                String.valueOf(inline.get("description")).contains("5242880 bytes (5 MiB)"));
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
        /**
         * Bounds the raster guard reads before dispatching a render. Defaults to a
         * view small enough that no existing test trips the guard: at the tool's
         * largest accepted scale it projects to about 31 MB, well inside any
         * budget a machine able to run the suite could report.
         */
        ContentBounds contentBounds = new ContentBounds(0, 0, 800, 600);
        boolean exportCalled;
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
        public ContentBounds getContentBounds(String viewId) {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            return contentBounds;
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
            this.exportCalled = true;
            this.lastViewId = viewId;
            this.lastFormat = format;
            this.lastScale = scale;
            this.lastQuality = quality;
            this.lastInline = inline;
            this.lastOutputDirectory = outputDirectory;
            return exportResult;
        }
    }

    // ---- The display's zoom ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> guardError(StubAccessor accessor) throws Exception {
        McpSchema.CallToolResult result = invokeExportView("view-1", "jpg", 2.0, null);
        assertTrue("expected the guard to refuse", result.isError());
        assertFalse("the refusal comes before the render", accessor.exportCalled);
        return (Map<String, Object>) parseJson(result).get("error");
    }

    @Test
    public void shouldRefuseABitmapThatFitsAtOneHundredPercentButNotAtTheDisplaysZoom() throws Exception {
        long plainBytes = RasterProjection.project(LARGE_VIEW_BOUNDS, 2.0).bytes();
        long free = 2 * 2 * plainBytes;  // the budget is half of free: twice the 100% bitmap, half the 200% one
        StubAccessor plain = new StubAccessor(true, createDefaultPngResult());
        plain.contentBounds = LARGE_VIEW_BOUNDS;
        new RenderHandler(plain, formatter, registry, () -> free, () -> 100).registerTools();
        assertFalse(invokeExportView("view-1", "jpg", 2.0, null).isError());

        registry = new net.vheerden.archi.mcp.registry.CommandRegistry();
        StubAccessor retina = new StubAccessor(true, createDefaultPngResult());
        retina.contentBounds = LARGE_VIEW_BOUNDS;
        new RenderHandler(retina, formatter, registry, () -> free, () -> 200).registerTools();

        Map<String, Object> error = guardError(retina);

        assertEquals("INVALID_PARAMETER", error.get("code"));
        String message = (String) error.get("message");
        assertTrue(message, message.contains("drawn at the display's 200% zoom as"));
    }

    @Test
    public void shouldRefuseTheCrashingExport_evenWithMemoryToSpare() throws Exception {
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        accessor.contentBounds = LARGE_VIEW_BOUNDS;
        new RenderHandler(accessor, formatter, registry, () -> GENEROUS_MEMORY, () -> 200).registerTools();

        Map<String, Object> error = guardError(accessor);

        String suggestion = (String) error.get("suggestedCorrection");
        double retry = Double.parseDouble(suggestion.replaceAll("^Retry at scale ([0-9.]+) .*$", "$1"));
        assertTrue("the suggested scale fits at 200%: " + suggestion,
                RasterProjection.project(LARGE_VIEW_BOUNDS, retry, 200).bytes() <= Integer.MAX_VALUE);
    }

    @Test
    public void shouldAdmitABitmapJustUnderWhatAnIntCounts_withMemoryToSpare() {
        // 256999 x 2089 pixels x 4 bytes = 2147483644, three bytes under Integer.MAX_VALUE.
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        accessor.contentBounds = new ContentBounds(0, 0, 256999 - 20, 2089 - 20);
        new RenderHandler(accessor, formatter, registry, () -> GENEROUS_MEMORY, () -> 100).registerTools();

        McpSchema.CallToolResult result = invokeExportView("view-1", "png", 1.0, null);

        assertFalse(result.isError());
        assertTrue(accessor.exportCalled);
    }

    @Test
    public void shouldCountAnUnreadableOrNonPositiveZoomAsTwoHundred() {
        assertEquals(200, DeviceZoom.effective(() -> 0));
        assertEquals(200, DeviceZoom.effective(() -> -100));
        assertEquals(200, DeviceZoom.effective(() -> {
            throw new IllegalStateException("no display");
        }));
        assertEquals(150, DeviceZoom.effective(() -> 150));
        assertEquals(100, DeviceZoom.effective(() -> 100));
    }

    @Test
    public void shouldRefuse_whenTheZoomCannotBeRead() throws Exception {
        long plainBytes = RasterProjection.project(LARGE_VIEW_BOUNDS, 2.0).bytes();
        StubAccessor accessor = new StubAccessor(true, createDefaultPngResult());
        accessor.contentBounds = LARGE_VIEW_BOUNDS;
        new RenderHandler(accessor, formatter, registry, () -> 4 * plainBytes, () -> 0).registerTools();

        guardError(accessor);
    }
}
