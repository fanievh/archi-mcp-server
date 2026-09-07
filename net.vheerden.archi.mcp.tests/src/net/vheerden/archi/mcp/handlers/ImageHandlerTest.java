package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.ArrayList;
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
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AddImageResultDto;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Unit tests for {@link ImageHandler} — filePath, url, imageData mutual exclusivity
 * and dispatch routing.
 *
 * <p>Uses a stub ArchiModelAccessor — no EMF/OSGi runtime required.</p>
 */
public class ImageHandlerTest {

    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private ObjectMapper objectMapper;
    private SessionManager sessionManager;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        objectMapper = new ObjectMapper();
        // ImageHandler's ctor requires a non-null SessionManager (Objects.requireNonNull) and
        // its handlers call HandlerUtils.extractSessionId(sessionManager, …) — so a real instance
        // is required, not null (mirrors ModelQueryHandlerTest / SessionHandlerTest).
        sessionManager = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
    }

    // ---- Tool Registration ----

    @Test
    public void shouldRegisterBothTools() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        assertEquals(2, registry.getToolCount());
        assertNotNull(findToolSpec("add-image-to-model"));
        assertNotNull(findToolSpec("list-model-images"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveFilePathAndUrlParams() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
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
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("add-image-to-model").tool();
        assertTrue(tool.description().contains("filePath"));
        assertTrue(tool.description().contains("url"));
        assertTrue(tool.description().contains("Preferred"));
    }

    // ---- Mutual Exclusivity ----

    @Test
    public void shouldReturnError_whenNoParamsProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of());

        assertTrue(result.isError());
        String text = getTextContent(result);
        assertTrue(text.contains("Exactly one"));
    }

    @Test
    public void shouldReturnError_whenTwoParamsProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
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
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
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
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
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
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("url", "https://example.com/test.png");
        args.put("imageData", "base64data");

        McpSchema.CallToolResult result = invokeAddImage(args);

        assertTrue(result.isError());
        String text = getTextContent(result);
        assertTrue(text.contains("Only one"));
    }

    // ---- FilePath Dispatch ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRouteToFilePath_whenFilePathProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
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

    // ---- URL Dispatch ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRouteToUrl_whenUrlProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
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

    // ---- Base64 Dispatch ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRouteToBase64_whenImageDataProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
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

    // ---- Base64 Size Validation ----

    @Test
    public void shouldReturnError_whenBase64TooLarge() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
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
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of("imageData", "not-valid-base64!!!"));

        assertTrue(result.isError());
        String text = getTextContent(result);
        assertTrue(text.contains("base64"));
    }

    // ---- NextSteps ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeFilePathInNextSteps() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
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
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of("filePath", "/tmp/test.png"));

        assertTrue(result.isError());
    }

    // ---- Batch Import (images array) ----

    /** 1x1 red pixel PNG — valid base64, small enough to pass every size guard. */
    private static final String VALID_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==";

    @SuppressWarnings("unchecked")
    @Test
    public void shouldExposeImagesParam_whenToolRegistered() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("add-image-to-model").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertTrue("add-image-to-model should expose an 'images' batch param",
                properties.containsKey("images"));

        Map<String, Object> images = (Map<String, Object>) properties.get("images");
        assertEquals("array", images.get("type"));

        // The batch form is an alternative to the single form, not a replacement — so the
        // tool still has no required parameter and no existing caller is forced to change.
        List<String> required = tool.inputSchema().required();
        assertTrue(required == null || required.isEmpty());
    }

    @Test
    public void shouldDocumentBatchImportAndBulkExclusion_whenToolRegistered() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        String description = findToolSpec("add-image-to-model").tool().description();
        assertTrue("description must point at the batch form",
                description.contains("'images'"));
        assertTrue("description must name the tool that cannot carry this import",
                description.contains("bulk-mutate"));
        assertTrue("description must give the reason the import cannot be batched there",
                description.contains("not undoable"));
    }

    /**
     * Every other mutation reshapes its response while a batch is open — queued, previewed, and
     * decided at end-batch. This one does not: it holds no command and never consults batch state,
     * so inside an open batch it writes immediately and reports plain success for something
     * end-batch cannot roll back.
     *
     * <p>The response itself is not lying — the write really is effective, and always will be — so
     * there is no per-call divergence to declare and nothing to nest under a preview. What is
     * missing is that the agent is never told this tool sits outside the batching contract the
     * other tools follow. That is a documentation defect, and documentation is where it is fixed.</p>
     */
    @Test
    public void shouldDocumentThatTheArchiveWriteIgnoresAnOpenBatch_whenToolRegistered() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        String description = findToolSpec("add-image-to-model").tool().description();
        assertTrue("description must name the batching mechanism it sits outside",
                description.contains("begin-batch"));
        assertTrue("description must say the write happens immediately, not at end-batch",
                description.contains("immediately"));
    }

    // ---- the SVG condition: asserted per source, on both surfaces ----

    /**
     * {@code add-image-to-model} takes three mutually-exclusive sources and the SVG condition
     * previously reached two of them: {@code filePath} stated it, {@code imageData} carried only a
     * cross-reference to {@code filePath}, and {@code url} said nothing at all.
     *
     * <p>These assertions are deliberately <b>per source</b>. A single {@code contains} over the
     * whole serialized spec would pass while two of the three sources were silent — which is exactly
     * the state the tree was in, and exactly what a whole-spec assertion would have failed to
     * notice.</p>
     *
     * <p>The phrases pinned are the ones the ruled wording added, not merely the token "SVG": an
     * older text that named a version without naming the condition ("verified on Archi 5.10")
     * contains "SVG" and would keep a weaker pin green.</p>
     */
    @Test
    public void shouldStateTheSvgCondition_onEveryImportSource() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        for (String source : List.of("filePath", "url", "imageData")) {
            String description = sourceDescription(source);
            assertTrue(source + " must name SVG at all", description.contains("SVG"));
            assertTrue(source + " must name the condition — the host's loader, not this server",
                    description.contains("host Archi's image loader, not by this server"));
            assertTrue(source + " must state the boundary as a range, not a single verified version",
                    description.contains("from 5.10 onward"));
            assertTrue(source + " must state the portability consequence",
                    description.contains("re-saved on an older Archi"));
        }
    }

    /**
     * Each source property is built once and installed twice — into the batch {@code images} entry
     * schema and into the top-level {@code properties}. This asserts the batch surface carries the
     * same text, so a future edit that reaches only one of the two installs is caught.
     */
    @Test
    public void shouldStateTheSvgCondition_onTheBatchEntrySurfaceToo() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        for (String source : List.of("filePath", "url", "imageData")) {
            assertEquals("batch entry '" + source + "' must carry the same text as the top-level one",
                    sourceDescription(source), batchEntryDescription(source));
        }
    }

    /**
     * Every source names the formats it accepts. {@code url} previously named none, so a caller
     * reading only that property learned nothing about what the server would take.
     */
    @Test
    public void shouldEnumerateSupportedFormats_onEveryImportSource() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        for (String source : List.of("filePath", "url", "imageData")) {
            assertTrue(source + " must enumerate the supported formats",
                    sourceDescription(source).contains("PNG, JPEG, GIF, BMP, ICO, TIFF"));
        }
    }

    /**
     * The archive path Archi mints is {@code images/} + a generated identifier + the source file's
     * extension — not a content hash, and not always {@code .png}. This tool returns that value, so
     * it must never publish a shape a caller could build or validate against.
     */
    @Test
    public void shouldNotPublishAConstructableArchivePathShape() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        String spec = fullSpecText();
        assertFalse("no tool may describe the archive path as a content hash", spec.contains("sha1"));
        assertFalse("no tool may publish a constructable archive-path format",
                spec.contains("images/<"));
    }

    /**
     * The three facts are stated on two surfaces — these schema descriptions and the remediation
     * constant in {@code ImageOperations} — by two constants, because {@code handlers/} cannot see a
     * package-private type in {@code model/}. No single test can see both, so each surface is pinned
     * against the <b>same fact list</b>. {@code ImageFormatDetectionTest} asserts the other half; if
     * these two lists ever diverge, the surfaces have drifted.
     */
    @Test
    public void shouldStateAllThreeSvgFacts_inTheSchemaSurface() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        for (String source : List.of("filePath", "url", "imageData")) {
            String description = sourceDescription(source);
            assertTrue(source + " — fact 1: the capability is host-supplied",
                    description.contains("host Archi's image loader, not by this server"));
            assertTrue(source + " — fact 2: the boundary",
                    description.contains("from 5.10 onward"));
            assertTrue(source + " — fact 3: the portability consequence",
                    description.contains("re-saved on an older Archi"));
        }
    }

    /**
     * The size advice is sound for the icon path and was stated unconditionally, which made it false
     * for {@code add-image-to-view} — a sibling tool that does take {@code width} and {@code height}.
     * The advice must survive; only its unconditional reach was wrong.
     */
    @Test
    public void shouldScopeTheNoWidthClaimToTheIconPath() {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        String description = sourceDescription("filePath");
        assertFalse("the width claim must not be stated unconditionally",
                description.contains("there is no width parameter"));
        assertTrue("the claim must be scoped by naming the tools it is true of",
                description.contains("add-to-view, add-group-to-view, add-note-to-view and update-view-object "
                        + "take no width parameter"));
        assertTrue("the sized alternative must be named",
                description.contains("add-image-to-view"));
        assertTrue("the sized alternative's parameters must be named",
                description.contains("width and height"));
        assertTrue("the 16x16 corner-badge advice must survive", description.contains("16x16"));
        assertTrue("the 48x48 fill advice must survive", description.contains("48x48"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldImportEveryEntry_whenImagesArrayProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        List<Map<String, Object>> images = List.of(
                Map.of("filePath", "/tmp/a.png"),
                Map.of("url", "https://example.com/b.png"),
                Map.of("imageData", VALID_PNG_BASE64, "filename", "c.png"));

        McpSchema.CallToolResult result = invokeAddImage(Map.of("images", images));

        assertFalse(result.isError());
        Map<String, Object> resultMap = (Map<String, Object>) parseJson(result).get("result");
        assertNotNull(resultMap);
        assertEquals(3, ((Number) resultMap.get("requestedCount")).intValue());
        assertEquals(3, ((Number) resultMap.get("importedCount")).intValue());
        assertEquals(0, ((Number) resultMap.get("failedCount")).intValue());

        // Effective state per entry — the archive path each import actually landed at, mapped
        // back to the caller's request index. A count alone would leave the agent unable to say
        // which icon got which path.
        List<Map<String, Object>> imported = (List<Map<String, Object>>) resultMap.get("images");
        assertEquals(3, imported.size());
        assertEquals(0, ((Number) imported.get(0).get("index")).intValue());
        assertEquals("images/from-file.png", imported.get(0).get("imagePath"));
        assertEquals("images/from-url.png", imported.get(1).get("imagePath"));
        assertEquals("images/from-base64.png", imported.get(2).get("imagePath"));
        assertEquals(16, ((Number) imported.get(0).get("width")).intValue());
        assertEquals("PNG", imported.get(0).get("formatDetected"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReportPerEntryFailure_whenOneEntryIsInvalid() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        List<Map<String, Object>> images = new ArrayList<>();
        images.add(Map.of("filePath", "/tmp/a.png"));
        images.add(Map.of());                                                   // no source at all
        images.add(Map.of("filePath", "/tmp/c.png", "url", "https://x/c.png")); // two sources

        McpSchema.CallToolResult result = invokeAddImage(Map.of("images", images));

        // A partial batch is a truthful success, not an error: the archive write was never
        // transactional, so the entries that landed really did land and must be reported.
        assertFalse(result.isError());
        Map<String, Object> resultMap = (Map<String, Object>) parseJson(result).get("result");
        assertEquals(3, ((Number) resultMap.get("requestedCount")).intValue());
        assertEquals(1, ((Number) resultMap.get("importedCount")).intValue());
        assertEquals(2, ((Number) resultMap.get("failedCount")).intValue());

        List<Map<String, Object>> imported = (List<Map<String, Object>>) resultMap.get("images");
        assertEquals(1, imported.size());
        assertEquals("the surviving entry keeps its request index, not its result position",
                0, ((Number) imported.get(0).get("index")).intValue());

        List<Map<String, Object>> failures = (List<Map<String, Object>>) resultMap.get("failures");
        assertEquals(2, failures.size());
        assertEquals(1, ((Number) failures.get(0).get("index")).intValue());
        assertTrue(((String) failures.get(0).get("message")).contains("Exactly one"));
        assertEquals(2, ((Number) failures.get(1).get("index")).intValue());
        assertTrue(((String) failures.get(1).get("message")).contains("Only one"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReportEntryFailure_whenEntryBase64ExceedsLimit() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        // Proves the per-entry path re-enters the base64 guards rather than bypassing them.
        List<Map<String, Object>> images = List.of(Map.of("imageData", "A".repeat(1_500_000)));

        McpSchema.CallToolResult result = invokeAddImage(Map.of("images", images));

        assertFalse(result.isError());
        Map<String, Object> resultMap = (Map<String, Object>) parseJson(result).get("result");
        assertEquals(1, ((Number) resultMap.get("failedCount")).intValue());
        List<Map<String, Object>> failures = (List<Map<String, Object>>) resultMap.get("failures");
        assertTrue(((String) failures.get(0).get("message")).contains("1MB"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReportEntryFailure_whenEntryIsNotAnObject() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of("images", List.of("/tmp/a.png")));

        assertFalse(result.isError());
        Map<String, Object> resultMap = (Map<String, Object>) parseJson(result).get("result");
        assertEquals(1, ((Number) resultMap.get("failedCount")).intValue());
        assertEquals(0, ((Number) resultMap.get("importedCount")).intValue());
    }

    /**
     * The batch promises never to lose what already landed, and an archive write cannot be taken
     * back, so that promise has to hold for every way an entry can fail — including the model
     * being closed part-way through. A 150-entry import doing HTTP downloads takes real time, and
     * the model supplier is re-entered on every entry, so this is reachable rather than theoretical.
     *
     * <p>{@code NoModelLoadedException} is a sibling of {@code ModelAccessException}, not a
     * subclass, so a per-entry catch of the latter does not cover it. Left uncaught it unwinds the
     * whole batch and the caller is told only that no model is loaded — while the images written
     * before that point sit in the archive, unreported and unremovable.</p>
     */
    @SuppressWarnings("unchecked")
    @Test
    public void shouldStillReportWhatLanded_whenTheModelClosesPartWayThroughTheBatch()
            throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        accessor.failWithNoModelLoadedFromCall = 2; // entry 0 lands, entry 1 hits the closed model
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        List<Map<String, Object>> images = List.of(
                Map.of("filePath", "/tmp/a.png"),
                Map.of("filePath", "/tmp/b.png"),
                Map.of("filePath", "/tmp/c.png"));

        McpSchema.CallToolResult result = invokeAddImage(Map.of("images", images));

        assertFalse("losing the batch would hide an irreversible write", result.isError());
        Map<String, Object> resultMap = (Map<String, Object>) parseJson(result).get("result");

        List<Map<String, Object>> imported = (List<Map<String, Object>>) resultMap.get("images");
        assertEquals("the entry that landed must still be reported", 1, imported.size());
        assertEquals(0, ((Number) imported.get(0).get("index")).intValue());
        assertEquals("images/from-file.png", imported.get(0).get("imagePath"));

        // Every requested entry is accounted for: nothing is silently dropped.
        assertEquals(3, ((Number) resultMap.get("requestedCount")).intValue());
        assertEquals(1, ((Number) resultMap.get("importedCount")).intValue());
        assertEquals(2, ((Number) resultMap.get("failedCount")).intValue());
        List<Map<String, Object>> failures = (List<Map<String, Object>>) resultMap.get("failures");
        assertEquals("the failing entry and the unattempted one must both be named", 2, failures.size());
        assertEquals(1, ((Number) failures.get(0).get("index")).intValue());
        assertEquals(2, ((Number) failures.get(1).get("index")).intValue());

        // The counts must sum to the request, or the breakdown is not a breakdown.
        assertEquals(((Number) resultMap.get("requestedCount")).intValue(),
                ((Number) resultMap.get("importedCount")).intValue()
                        + ((Number) resultMap.get("failedCount")).intValue());

        assertEquals("the remaining entries must not be re-attempted against a closed model",
                2, accessor.importCallCount);
    }

    /**
     * A top-level filename cannot apply to a batch — each entry carries its own — so accepting it
     * and ignoring it lets a caller believe a name was honoured that never was.
     */
    @Test
    public void shouldReturnError_whenTopLevelFilenameAccompaniesImages() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("images", List.of(Map.of("imageData", VALID_PNG_BASE64)));
        args.put("filename", "ignored.png");

        McpSchema.CallToolResult result = invokeAddImage(args);

        assertTrue(result.isError());
        assertTrue(getTextContent(result).contains("filename"));
    }

    /** Pins the envelope counts for a partial batch: nothing was truncated, and totals are honest. */
    @SuppressWarnings("unchecked")
    @Test
    public void shouldReportEnvelopeCountsHonestly_whenSomeEntriesFail() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of("images", List.of(
                Map.of("filePath", "/tmp/a.png"),
                Map.of())));

        Map<String, Object> meta = (Map<String, Object>) parseJson(result).get("_meta");
        assertEquals("resultCount is what landed", 1, ((Number) meta.get("resultCount")).intValue());
        assertEquals("totalCount is what was asked for", 2, ((Number) meta.get("totalCount")).intValue());
        assertEquals("a partial batch is not a truncated one — every entry is accounted for",
                Boolean.FALSE, meta.get("isTruncated"));
    }

    @Test
    public void shouldReturnError_whenImagesCombinedWithSingleForm() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        Map<String, Object> args = new HashMap<>();
        args.put("images", List.of(Map.of("filePath", "/tmp/a.png")));
        args.put("filePath", "/tmp/b.png");

        McpSchema.CallToolResult result = invokeAddImage(args);

        assertTrue(result.isError());
        assertTrue(getTextContent(result).contains("'images'"));
    }

    @Test
    public void shouldReturnError_whenImagesArrayIsEmpty() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeAddImage(Map.of("images", List.of()));

        assertTrue(result.isError());
        assertTrue(getTextContent(result).contains("at least one"));
    }

    @Test
    public void shouldReturnError_whenImagesArrayExceedsCap() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ImageHandler handler = new ImageHandler(accessor, formatter, registry, sessionManager);
        handler.registerTools();

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= BulkOperation.MAX_OPERATIONS; i++) {
            tooMany.add(Map.of("filePath", "/tmp/" + i + ".png"));
        }

        McpSchema.CallToolResult result = invokeAddImage(Map.of("images", tooMany));

        assertTrue(result.isError());
        // Pins the cap to bulk-mutate's constant so the two batch limits cannot drift apart.
        assertTrue(getTextContent(result).contains(String.valueOf(BulkOperation.MAX_OPERATIONS)));
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult invokeAddImage(Map<String, Object> args) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("add-image-to-model");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("add-image-to-model", args);
        return spec.callHandler().apply(null, request);
    }

    /** The top-level description of one of add-image-to-model's three import sources. */
    @SuppressWarnings("unchecked")
    private String sourceDescription(String source) {
        Map<String, Object> properties =
                findToolSpec("add-image-to-model").tool().inputSchema().properties();
        Map<String, Object> prop = (Map<String, Object>) properties.get(source);
        assertNotNull("add-image-to-model must expose a '" + source + "' source", prop);
        return (String) prop.get("description");
    }

    /** The same source's description as installed into the batch 'images' entry schema. */
    @SuppressWarnings("unchecked")
    private String batchEntryDescription(String source) {
        Map<String, Object> properties =
                findToolSpec("add-image-to-model").tool().inputSchema().properties();
        Map<String, Object> images = (Map<String, Object>) properties.get("images");
        Map<String, Object> entrySchema = (Map<String, Object>) images.get("items");
        Map<String, Object> entryProps = (Map<String, Object>) entrySchema.get("properties");
        Map<String, Object> prop = (Map<String, Object>) entryProps.get(source);
        assertNotNull("the batch entry must expose a '" + source + "' source", prop);
        return (String) prop.get("description");
    }

    /** Tool description plus every property description, for whole-spec absence assertions. */
    private String fullSpecText() {
        McpSchema.Tool tool = findToolSpec("add-image-to-model").tool();
        return tool.description() + " " + tool.inputSchema().properties().toString();
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

        /** Counts every import attempt, so a test can prove the batch stopped rather than looped on. */
        int importCallCount;

        /**
         * 1-based import call from which the model is treated as closed. 0 disables it. Models the
         * user closing the model in Archi while a long batch is still running — the model supplier
         * is re-entered on every entry, so this is reachable, not theoretical.
         */
        int failWithNoModelLoadedFromCall;

        StubAccessor(boolean modelLoaded) {
            super(modelLoaded);
        }

        private void countCallAndMaybeCloseModel() {
            importCallCount++;
            if (failWithNoModelLoadedFromCall > 0 && importCallCount >= failWithNoModelLoadedFromCall) {
                throw new NoModelLoadedException();
            }
        }

        @Override
        public AddImageResultDto addImageToModel(String sessionId, byte[] imageData, String filenameHint) {
            countCallAndMaybeCloseModel();
            lastImportMethod = "base64";
            return new AddImageResultDto("images/from-base64.png", 16, 16, "PNG");
        }

        @Override
        public AddImageResultDto addImageFromFilePath(String sessionId, String filePath) {
            countCallAndMaybeCloseModel();
            lastImportMethod = "filePath";
            lastFilePath = filePath;
            return new AddImageResultDto("images/from-file.png", 16, 16, "PNG");
        }

        @Override
        public AddImageResultDto addImageFromUrl(String sessionId, String url) {
            countCallAndMaybeCloseModel();
            lastImportMethod = "url";
            lastUrl = url;
            return new AddImageResultDto("images/from-url.png", 16, 16, "PNG");
        }
    }
}
