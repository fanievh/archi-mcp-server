package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Before;
import org.junit.Test;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Every tool the server registers is classified READ or WRITE exactly once in {@link ToolAccess}, the
 * table names no tool that is not registered, an unclassified tool cannot be registered, and the tool
 * hints are derived from the table. Registration goes through {@link HandlerRegistrar}, the same entry
 * point production uses, so a tool added behind a new handler is seen here too.
 */
public class ToolAccessContractTest {

    private CommandRegistry registry;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        HandlerRegistrar.registerAll(new BaseTestAccessor(true), new ResponseFormatter(), registry,
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS));
    }

    private Set<String> registeredNames() {
        Set<String> names = new LinkedHashSet<>();
        registry.getToolSpecifications().forEach(spec -> names.add(spec.tool().name()));
        return names;
    }

    @Test
    public void shouldClassifyEveryRegisteredTool() {
        Set<String> unclassified = new TreeSet<>(registeredNames());
        unclassified.removeAll(ToolAccess.table().keySet());
        assertEquals("registered tools with no access classification", Collections.emptySet(), unclassified);
    }

    @Test
    public void shouldNameNoToolThatIsNotRegistered() {
        Set<String> stale = new TreeSet<>(ToolAccess.table().keySet());
        stale.removeAll(registeredNames());
        assertEquals("classified tools that are not registered", Collections.emptySet(), stale);
    }

    @Test
    public void shouldHoldTheExpectedReadSet() {
        Set<String> read = new TreeSet<>();
        ToolAccess.table().forEach((name, entry) -> {
            if (entry.access() == ToolAccess.READ) {
                read.add(name);
            }
        });
        assertEquals(new TreeSet<>(Set.of("assess-layout", "detect-hub-elements", "export-view",
                "find-concept-usage", "get-element", "get-folder-tree", "get-folders", "get-guidance",
                "get-model-info", "get-models", "get-relationships", "get-session-filters",
                "get-specialization-usage", "get-view-contents", "get-views", "list-model-images",
                "list-specializations", "search-elements", "search-relationships", "set-session-filter")),
                read);
        assertEquals(74, ToolAccess.table().size());
    }

    @Test
    public void shouldNeverMarkAReadToolDestructive() {
        ToolAccess.table().forEach((name, entry) -> {
            if (entry.access() == ToolAccess.READ) {
                assertTrue(name + " is READ and destructive", !entry.destructive());
            }
        });
    }

    @Test
    public void shouldRefuseToRegisterAToolWithNoClassification() {
        McpServerFeatures.SyncToolSpecification unclassified = McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder().name("not-a-classified-tool").description("x")
                        .inputSchema(new McpSchema.JsonSchema("object", Collections.emptyMap(), null, null,
                                null, null))
                        .build())
                .callHandler((exchange, request) -> null)
                .build();
        int before = registry.getToolCount();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.registerTool(unclassified));

        assertTrue("the refusal names the tool: " + e.getMessage(),
                e.getMessage().contains("MCP tool not-a-classified-tool has no access classification"));
        assertEquals("a refused tool is not added", before, registry.getToolCount());
    }

    @Test
    public void shouldRefuseAnUnclassifiedNameWhenAnnotating() {
        McpSchema.Tool tool = McpSchema.Tool.builder().name("not-a-classified-tool").description("x")
                .inputSchema(new McpSchema.JsonSchema("object", Collections.emptyMap(), null, null, null, null))
                .build();
        assertThrows(IllegalStateException.class, () -> ToolAccess.annotate(tool));
        assertThrows(IllegalStateException.class, () -> ToolAccess.of(null));
    }

    @Test
    public void shouldDeriveTheHintsOfEveryRegisteredToolFromItsClassification() {
        for (McpServerFeatures.SyncToolSpecification spec : registry.getToolSpecifications()) {
            McpSchema.Tool tool = spec.tool();
            ToolAccess.Entry entry = ToolAccess.of(tool.name());
            McpSchema.ToolAnnotations annotations = tool.annotations();
            assertNotNull(tool.name() + " carries no annotations", annotations);
            assertEquals(tool.name() + " readOnlyHint", entry.access() == ToolAccess.READ,
                    annotations.readOnlyHint());
            if (entry.access() == ToolAccess.READ) {
                assertNull(tool.name() + " is READ, so destructiveHint has no meaning",
                        annotations.destructiveHint());
            } else {
                assertEquals(tool.name() + " destructiveHint", entry.destructive(),
                        annotations.destructiveHint());
            }
            assertNull(tool.name() + " title hint", annotations.title());
            assertNull(tool.name() + " idempotentHint", annotations.idempotentHint());
            assertNull(tool.name() + " openWorldHint", annotations.openWorldHint());
            assertNull(tool.name() + " returnDirect", annotations.returnDirect());
        }
    }

    @Test
    public void shouldKeepTheModelIdPropertyAlongsideTheHints() {
        McpSchema.Tool getElement = registry.getToolSpecifications().stream()
                .map(McpServerFeatures.SyncToolSpecification::tool)
                .filter(t -> t.name().equals("get-element")).findFirst().orElseThrow();
        assertTrue(getElement.inputSchema().properties().containsKey("modelId"));
    }

    @Test
    public void shouldKeepAnAnnotationAToolAlreadyCarries() {
        McpSchema.Tool tool = McpSchema.Tool.builder().name("get-views").description("x")
                .inputSchema(new McpSchema.JsonSchema("object", Collections.emptyMap(), null, null, null, null))
                .annotations(new McpSchema.ToolAnnotations("Views", false, true, true, false, false))
                .build();

        McpSchema.ToolAnnotations annotated = ToolAccess.annotate(tool).annotations();

        assertEquals("Views", annotated.title());
        assertEquals(Boolean.TRUE, annotated.readOnlyHint());
        assertNull(annotated.destructiveHint());
        assertEquals(Boolean.TRUE, annotated.idempotentHint());
        assertEquals(Boolean.FALSE, annotated.openWorldHint());
        assertEquals(Boolean.FALSE, annotated.returnDirect());
    }

    @Test
    public void shouldRegisterExactlyTheReadTools_whenReadOnly() {
        CommandRegistry readOnly = new CommandRegistry();
        HandlerRegistrar.registerAll(new BaseTestAccessor(true), new ResponseFormatter(), readOnly,
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS), true);

        Set<String> expectedRead = new TreeSet<>();
        Set<String> expectedWithheld = new TreeSet<>();
        ToolAccess.table().forEach((name, entry) -> (entry.access() == ToolAccess.READ
                ? expectedRead : expectedWithheld).add(name));
        Set<String> registered = new TreeSet<>();
        readOnly.getToolSpecifications().forEach(spec -> {
            registered.add(spec.tool().name());
            assertEquals(spec.tool().name() + " readOnlyHint", Boolean.TRUE,
                    spec.tool().annotations().readOnlyHint());
        });

        assertEquals(expectedRead, registered);
        assertEquals(20, registered.size());
        assertEquals(expectedWithheld, new TreeSet<>(readOnly.getWithheldToolNames()));
    }

    @Test
    public void shouldRegisterEveryTool_whenNotReadOnly() {
        CommandRegistry full = new CommandRegistry();
        HandlerRegistrar.registerAll(new BaseTestAccessor(true), new ResponseFormatter(), full,
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS), false);

        Set<String> registered = new TreeSet<>();
        full.getToolSpecifications().forEach(spec -> registered.add(spec.tool().name()));
        assertEquals(new TreeSet<>(ToolAccess.table().keySet()), registered);
        assertEquals(new TreeSet<>(registeredNames()), registered);
        assertTrue(full.getWithheldToolNames().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldTellExportViewTheServerIsReadOnly_whenRegisteringForAReadOnlyServer() {
        CommandRegistry readOnly = new CommandRegistry();
        HandlerRegistrar.registerAll(new BaseTestAccessor(true), new ResponseFormatter(), readOnly,
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS), true);
        McpSchema.Tool exportView = readOnly.getToolSpecifications().stream()
                .map(McpServerFeatures.SyncToolSpecification::tool)
                .filter(t -> t.name().equals("export-view")).findFirst().orElseThrow();
        Object inline = ((java.util.Map<String, Object>) exportView.inputSchema().properties().get("inline"))
                .get("description");
        assertTrue(String.valueOf(inline), String.valueOf(inline).contains(RenderHandler.READ_ONLY_NO_FILES));

        McpSchema.Tool fullExportView = registry.getToolSpecifications().stream()
                .map(McpServerFeatures.SyncToolSpecification::tool)
                .filter(t -> t.name().equals("export-view")).findFirst().orElseThrow();
        assertTrue(!String.valueOf(fullExportView.inputSchema().properties().get("inline"))
                .contains(RenderHandler.READ_ONLY_NO_FILES));
    }

    @Test
    public void shouldTellExportViewToRefuseFiles_whenTheRegistrarRefusesServerSources() {
        CommandRegistry reachable = new CommandRegistry();
        HandlerRegistrar.registerAll(new BaseTestAccessor(true), new ResponseFormatter(), reachable,
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS), false, true);
        McpSchema.Tool exportView = reachable.getToolSpecifications().stream()
                .map(McpServerFeatures.SyncToolSpecification::tool)
                .filter(t -> t.name().equals("export-view")).findFirst().orElseThrow();
        assertTrue(String.valueOf(exportView.inputSchema().properties().get("inline"))
                .contains(RenderHandler.SERVER_FILES_REFUSED));

        McpSchema.Tool fullExportView = registry.getToolSpecifications().stream()
                .map(McpServerFeatures.SyncToolSpecification::tool)
                .filter(t -> t.name().equals("export-view")).findFirst().orElseThrow();
        assertTrue(!String.valueOf(fullExportView.inputSchema().properties().get("inline"))
                .contains(RenderHandler.SERVER_FILES_REFUSED));
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> listModelImagesNextSteps(boolean readOnly) throws Exception {
        CommandRegistry r = new CommandRegistry();
        HandlerRegistrar.registerAll(new BaseTestAccessor(true) {
            @Override
            public java.util.List<net.vheerden.archi.mcp.response.dto.ModelImageDto> listModelImages(
                    String sessionId) {
                return java.util.List.of();
            }
        }, new ResponseFormatter(), r, new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS),
                readOnly);
        McpServerFeatures.SyncToolSpecification spec = r.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("list-model-images")).findFirst().orElseThrow();
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("list-model-images", java.util.Map.of()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        return (java.util.List<Object>) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(text, java.util.Map.class).get("nextSteps");
    }

    @Test
    public void shouldNotSuggestAWithheldTool_inARealReadToolsResponse_whenReadOnly() throws Exception {
        java.util.List<Object> full = listModelImagesNextSteps(false);
        assertEquals(1, full.size());
        assertTrue(String.valueOf(full), String.valueOf(full.get(0)).contains("add-image-to-model"));

        assertEquals(java.util.List.of(), listModelImagesNextSteps(true));
    }
}
