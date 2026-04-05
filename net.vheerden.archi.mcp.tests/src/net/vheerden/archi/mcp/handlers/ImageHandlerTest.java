package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AddImageResultDto;

/**
 * Unit tests for {@link ImageHandler} — Story C5: filePath, url, imageData mutual exclusivity
 * and dispatch routing.
 *
 * <p>Uses a stub ArchiModelAccessor — no EMF/OSGi runtime required.</p>
 */
public class ImageHandlerTest {

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
    public void shouldRegisterBothTools() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        assertEquals(2, registry.getToolCount());
        assertNotNull(findToolSpec("add-image-to-model"));
        assertNotNull(findToolSpec("list-model-images"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveFilePathAndUrlParams() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("add-image-to-model").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertTrue(properties.containsKey("filePath"));
        assertTrue(properties.containsKey("url"));
        assertTrue(properties.containsKey("imageData"));
        assertTrue(properties.containsKey("filename"));

        // No required params (mutual exclusivity enforced in handler)
        List<String> required = tool.inputSchema().required();
        assertTrue(required == null || required.isEmpty());
    }

    @Test
    public void shouldMentionFilePathAndUrlInDescription() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("add-image-to-model").tool();
        assertTrue(tool.description().contains("filePath"));
        assertTrue(tool.description().contains("url"));
        assertTrue(tool.description().contains("Preferred"));
    }

    // ---- Mutual Exclusivity (AC-3) ----

    @Test
    public void shouldReturnError_whenNoParamsProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of());

        assertTrue(result.isError());
        String text = getTextContent(result);
        assertTrue(text.contains("Exactly one"));
    }

    @Test
    public void shouldReturnError_whenTwoParamsProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("filePath", "/tmp/test.png");
        args.put("url", "https://example.com/test.png");

        McpSchema.CallToolResult result = invokeAddImage(args);

        assertTrue(result.isError());
        String text = getTextContent(result);
        assertTrue(text.contains("Only one"));
        assertTrue(text.contains("2"));
    }

    @Test
    public void shouldReturnError_whenThreeParamsProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("filePath", "/tmp/test.png");
        args.put("url", "https://example.com/test.png");
        args.put("imageData", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

        McpSchema.CallToolResult result = invokeAddImage(args);

        assertTrue(result.isError());
        String text = getTextContent(result);
        assertTrue(text.contains("Only one"));
        assertTrue(text.contains("3"));
    }

    @Test
    public void shouldReturnError_whenFilePathAndImageData() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("filePath", "/tmp/test.png");
        args.put("imageData", "base64data");

        McpSchema.CallToolResult result = invokeAddImage(args);

        assertTrue(result.isError());
        String text = getTextContent(result);
        assertTrue(text.contains("Only one"));
    }

    @Test
    public void shouldReturnError_whenUrlAndImageData() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("url", "https://example.com/test.png");
        args.put("imageData", "base64data");

        McpSchema.CallToolResult result = invokeAddImage(args);

        assertTrue(result.isError());
        String text = getTextContent(result);
        assertTrue(text.contains("Only one"));
    }

    // ---- FilePath Dispatch (AC-1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRouteToFilePath_whenFilePathProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of("filePath", "/tmp/test.png"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");
        assertNotNull(resultMap);
        assertEquals("images/from-file.png", resultMap.get("imagePath"));
        assertEquals("filePath", accessor.lastImportMethod);
        assertEquals("/tmp/test.png", accessor.lastFilePath);
    }

    // ---- URL Dispatch (AC-2) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRouteToUrl_whenUrlProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of("url", "https://example.com/icon.png"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");
        assertNotNull(resultMap);
        assertEquals("images/from-url.png", resultMap.get("imagePath"));
        assertEquals("url", accessor.lastImportMethod);
        assertEquals("https://example.com/icon.png", accessor.lastUrl);
    }

    // ---- Base64 Dispatch (AC-6) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRouteToBase64_whenImageDataProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // 1x1 red pixel PNG in valid base64
        String base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==";
        Map<String, Object> args = new HashMap<>();
        args.put("imageData", base64);
        args.put("filename", "icon.png");

        McpSchema.CallToolResult result = invokeAddImage(args);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");
        assertNotNull(resultMap);
        assertEquals("images/from-base64.png", resultMap.get("imagePath"));
        assertEquals("base64", accessor.lastImportMethod);
    }

    // ---- Base64 Size Validation (AC-6 preserved) ----

    @Test
    public void shouldReturnError_whenBase64TooLarge() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Create a base64 string > 1.4MB
        String oversized = "A".repeat(1_500_000);
        McpSchema.CallToolResult result = invokeAddImage(Map.of("imageData", oversized));

        assertTrue(result.isError());
        String text = getTextContent(result);
        assertTrue(text.contains("1MB"));
    }

    @Test
    public void shouldReturnError_whenBase64Invalid() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of("imageData", "not-valid-base64!!!"));

        assertTrue(result.isError());
        String text = getTextContent(result);
        assertTrue(text.contains("base64"));
    }

    // ---- NextSteps (AC-5) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeFilePathInNextSteps() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of("filePath", "/tmp/test.png"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("filePath")));
    }

    // ---- No Model Loaded ----

    @Test
    public void shouldReturnError_whenNoModelLoaded() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of("filePath", "/tmp/test.png"));

        assertTrue(result.isError());
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult invokeAddImage(Map<String, Object> args) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("add-image-to-model");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("add-image-to-model", args);
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

    private String getTextContent(McpSchema.CallToolResult result) {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().get(0);
        return content.text();
    }

    // ---- Test Stub ----

    static class StubAccessor extends BaseTestAccessor {

        String lastImportMethod;
        String lastFilePath;
        String lastUrl;

        StubAccessor(boolean modelLoaded) {
            super(modelLoaded);
        }

        @Override
        public AddImageResultDto addImageToModel(String sessionId, byte[] imageData, String filenameHint) {
            lastImportMethod = "base64";
            return new AddImageResultDto("images/from-base64.png", 16, 16, "PNG");
        }

        @Override
        public AddImageResultDto addImageFromFilePath(String sessionId, String filePath) {
            lastImportMethod = "filePath";
            lastFilePath = filePath;
            return new AddImageResultDto("images/from-file.png", 16, 16, "PNG");
        }

        @Override
        public AddImageResultDto addImageFromUrl(String sessionId, String url) {
            lastImportMethod = "url";
            lastUrl = url;
            return new AddImageResultDto("images/from-url.png", 16, 16, "PNG");
        }
    }
}
