package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ElementDto;

/**
 * Tests for {@link DiscoveryHandler}.
 *
 * <p>Uses a StubDiscoveryAccessor that provides configurable behavior
 * for findExactMatch, searchElements, and createElement.</p>
 */
public class DiscoveryHandlerTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubDiscoveryAccessor accessor;
    private DiscoveryHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        accessor = new StubDiscoveryAccessor();
        handler = new DiscoveryHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Response-envelope documentation pins ----

    private String descriptionOf(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName))
                .tool().description();
    }

    /**
     * An {@code Optional:} enumeration claims by omission: a parameter it does not name reads as a
     * parameter the tool does not take, however plainly the schema declares it. {@code
     * search-and-create} shipped exactly that way — its schema declared {@code createSource} and
     * its own sentence listed four names, not five — so an agent reading the description had no
     * way to know provenance could be supplied at all.
     *
     * <p>Derived from the schema's own keys rather than a hand-written expected list, so the two
     * cannot drift apart again: adding a parameter without naming it fails here, and so does
     * naming one the schema does not declare.</p>
     */
    @Test
    public void shouldEnumerateEveryOptionalSchemaParameter_inBothDiscoveryToolDescriptions() {
        for (String toolName : List.of("get-or-create-element", "search-and-create")) {
            McpSchema.Tool tool = registry.getToolSpecifications().stream()
                    .filter(spec -> toolName.equals(spec.tool().name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Tool not found: " + toolName))
                    .tool();

            java.util.Set<String> declared = new java.util.LinkedHashSet<>(
                    tool.inputSchema().properties().keySet());
            declared.removeAll(tool.inputSchema().required());

            assertEquals(toolName + " must enumerate exactly the optional parameters it declares",
                    declared, enumeratedOptionalParameters(tool.description()));
        }
    }

    /**
     * The enumeration parser's own pin. Its dangerous failure mode is not an error but a SILENT
     * truncation — a gloss containing a period-space used to end the scan early, yielding a
     * plausible smaller set and therefore a wrong assertion rather than a broken one. These are the
     * shapes that used to break it, fed in directly because no current description exercises them
     * and a parser only latently correct is a parser nobody has tested.
     */
    @Test
    public void shouldParseTheOptionalEnumeration_acrossGlossesThatUsedToBreakIt() {
        assertEquals("a period-space inside a gloss must not end the enumeration",
                java.util.Set.of("type", "createDocumentation", "createSource"),
                enumeratedOptionalParameters(
                        "Optional: type (e.g. a search filter), createDocumentation, "
                        + "createSource. Trailing prose."));

        assertEquals("a comma inside a gloss must not split into a spurious name",
                java.util.Set.of("type", "createFolderId"),
                enumeratedOptionalParameters(
                        "Optional: type (search filter, case-insensitive), createFolderId. More."));

        assertEquals("an enumeration ending the description must terminate cleanly",
                java.util.Set.of("documentation", "source"),
                enumeratedOptionalParameters("Required: type. Optional: documentation, source."));

        assertEquals("a nested parenthetical must not confuse the depth count",
                java.util.Set.of("a", "b"),
                enumeratedOptionalParameters("Optional: a (x (y. z), w), b. Tail."));
    }

    /**
     * Reads the names out of a description's {@code Optional: … .} sentence. Each name is matched
     * whole — a parenthetical gloss such as {@code type (search filter)} is stripped — so a
     * {@code type} is never satisfied by a {@code createType} that merely contains it.
     *
     * <p>Scanned character by character with parenthesis depth tracked, rather than split on the
     * first {@code ". "}. A sentence-ending period is only honoured at depth zero, so a gloss
     * containing {@code e.g. } cannot end the scan early and drop every name after it — that
     * failure mode would surface as a plausible-looking smaller set, i.e. a WRONG assertion rather
     * than a broken one. Commas are separators only at depth zero for the same reason, and a
     * sentence that runs to the end of the description terminates cleanly instead of erroring.</p>
     */
    private java.util.Set<String> enumeratedOptionalParameters(String description) {
        int start = description.indexOf("Optional: ");
        assertTrue("description must carry an Optional: enumeration", start >= 0);
        start += "Optional: ".length();

        java.util.Set<String> named = new java.util.LinkedHashSet<>();
        StringBuilder token = new StringBuilder();
        int depth = 0;
        for (int i = start; i < description.length(); i++) {
            char c = description.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            boolean sentenceEnd = c == '.' && depth == 0
                    && (i + 1 == description.length() || description.charAt(i + 1) == ' ');
            if (sentenceEnd || (c == ',' && depth == 0)) {
                addEnumeratedName(named, token);
                if (sentenceEnd) {
                    return named;
                }
                continue;
            }
            token.append(c);
        }
        fail("the Optional: enumeration must terminate with a sentence-ending period");
        return named;
    }

    /**
     * Adds one trimmed, gloss-stripped name from the enumeration and resets the buffer. A blank
     * token is dropped rather than added, so trailing separators cannot inject an empty name.
     */
    private void addEnumeratedName(java.util.Set<String> named, StringBuilder token) {
        String name = token.toString().trim();
        token.setLength(0);
        int gloss = name.indexOf(" (");
        if (gloss >= 0) {
            name = name.substring(0, gloss).trim();
        }
        if (!name.isEmpty()) {
            named.add(name);
        }
    }

    /**
     * Both discovery tools split their result differently from the shared
     * formatMutationResponse shape: under batch the element is a sibling of preview, under the
     * approval gate it is nested inside preview. Documenting only the shared shape would be
     * wrong for both branches, so each branch is pinned separately.
     */
    private void assertDocumentsSplitEnvelope(String toolName) {
        String desc = descriptionOf(toolName);
        assertTrue(toolName + " must flag that it differs from other mutations",
                desc.contains("splits its result differently"));
        assertTrue(toolName + " must place the element at result.element under batch",
                desc.contains("batch mode the element is at result.element"));
        assertTrue(toolName + " must say preview holds only the action marker under batch",
                desc.contains("only the action marker"));
        assertTrue(toolName + " must nest the element under preview for the approval gate",
                desc.contains("result.preview.element"));
        // Both tools route through accessor.createElement, whose approval branch stores a
        // deferred rebuild handle — so a created id previewed here never resolves. The general
        // homes refer readers to these descriptions, so the caveat has to be present here too.
        assertTrue(toolName + " must mark a previewed created id provisional",
                desc.contains("provisional, because the object is rebuilt on approval"));
    }

    @Test
    public void getOrCreateElement_descriptionShouldDocumentSplitEnvelope() {
        assertDocumentsSplitEnvelope("get-or-create-element");
    }

    @Test
    public void searchAndCreate_descriptionShouldDocumentSplitEnvelope() {
        assertDocumentsSplitEnvelope("search-and-create");
    }

    // ---- Tool registration tests ----

    @Test
    public void shouldRegisterTwoTools_whenHandlerRegistered() {
        assertEquals(2, registry.getToolSpecifications().size());
    }

    @Test
    public void shouldRegisterGetOrCreateElementTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "get-or-create-element".equals(spec.tool().name()));
        assertTrue("get-or-create-element tool should be registered", found);
    }

    @Test
    public void shouldRegisterSearchAndCreateTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "search-and-create".equals(spec.tool().name()));
        assertTrue("search-and-create tool should be registered", found);
    }

    @Test
    public void shouldHaveDiscoveryPrefix_inToolDescriptions() {
        registry.getToolSpecifications().forEach(spec -> {
            assertTrue(spec.tool().name() + " description should start with [Discovery]",
                    spec.tool().description().startsWith("[Discovery]"));
        });
    }

    // ---- get-or-create-element tests: found existing ----

    @Test
    public void shouldReturnFoundExisting_whenExactMatchExists() throws Exception {
        accessor.setExactMatchResult(Optional.of(
                ElementDto.standard("elem-1", "Customer Service", "BusinessProcess", null, "Business", null, null)));

        Map<String, Object> result = callAndParse("get-or-create-element",
                Map.of("type", "BusinessProcess", "name", "Customer Service"));

        Map<String, Object> entity = getResult(result);
        assertEquals("found_existing", entity.get("action"));
        assertNotNull("Should have element", entity.get("element"));
    }

    @Test
    public void shouldNotCallCreate_whenExactMatchFound() throws Exception {
        accessor.setExactMatchResult(Optional.of(
                ElementDto.standard("elem-1", "Customer", "BusinessActor", null, "Business", null, null)));

        callAndParse("get-or-create-element",
                Map.of("type", "BusinessActor", "name", "Customer"));

        assertFalse("createElement should not have been called", accessor.createElementCalled);
    }

    @Test
    public void shouldReturnNextSteps_whenFoundExisting() throws Exception {
        accessor.setExactMatchResult(Optional.of(
                ElementDto.standard("elem-1", "Customer", "BusinessActor", null, "Business", null, null)));

        Map<String, Object> result = callAndParse("get-or-create-element",
                Map.of("type", "BusinessActor", "name", "Customer"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-relationships")));
    }

    // ---- get-or-create-element tests: created new ----

    @Test
    public void shouldReturnCreatedNew_whenNoExactMatch() throws Exception {
        accessor.setExactMatchResult(Optional.empty());

        Map<String, Object> result = callAndParse("get-or-create-element",
                Map.of("type", "BusinessActor", "name", "New Actor"));

        Map<String, Object> entity = getResult(result);
        assertEquals("created_new", entity.get("action"));
        assertNotNull("Should have element", entity.get("element"));
    }

    @Test
    public void shouldCallCreate_whenNoExactMatch() throws Exception {
        accessor.setExactMatchResult(Optional.empty());

        callAndParse("get-or-create-element",
                Map.of("type", "BusinessActor", "name", "New Actor"));

        assertTrue("createElement should have been called", accessor.createElementCalled);
    }

    @Test
    public void shouldReturnModelChanged_whenCreatedNew() throws Exception {
        accessor.setExactMatchResult(Optional.empty());

        Map<String, Object> result = callAndParse("get-or-create-element",
                Map.of("type", "BusinessActor", "name", "New Actor"));

        Map<String, Object> entity = getResult(result);
        assertEquals(true, entity.get("modelChanged"));
    }

    @Test
    public void shouldReturnBatchInfo_whenCreatedNewInBatchMode() throws Exception {
        accessor.setExactMatchResult(Optional.empty());
        accessor.setBatchMode(true);

        Map<String, Object> result = callAndParse("get-or-create-element",
                Map.of("type", "BusinessActor", "name", "New Actor"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have batch info", entity.get("batch"));
    }

    // ---- get-or-create-element error tests ----

    @Test
    public void shouldReturnModelNotLoaded_whenNoModelForGetOrCreate() throws Exception {
        StubDiscoveryAccessor noModel = new StubDiscoveryAccessor(false);
        DiscoveryHandler noModelHandler = new DiscoveryHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolResult result = noModelHandler.handleGetOrCreateElement(null,
                McpSchema.CallToolRequest.builder().name("get-or-create-element")
                        .arguments(Map.of("type", "BusinessActor", "name", "Test")).build());

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameter_whenTypeMissingForGetOrCreate() throws Exception {
        McpSchema.CallToolResult result = callTool("get-or-create-element",
                Map.of("name", "Test"));

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameter_whenNameMissingForGetOrCreate() throws Exception {
        McpSchema.CallToolResult result = callTool("get-or-create-element",
                Map.of("type", "BusinessActor"));

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- search-and-create tests: found results ----

    @Test
    public void shouldReturnFoundExisting_whenSearchHasResults() throws Exception {
        accessor.setSearchResults(List.of(
                ElementDto.standard("elem-1", "Customer Service", "BusinessProcess", null, "Business", null, null),
                ElementDto.standard("elem-2", "Client Service", "BusinessProcess", null, "Business", null, null)));

        Map<String, Object> result = callAndParse("search-and-create",
                Map.of("query", "service", "createType", "BusinessProcess", "createName", "New Service"));

        Map<String, Object> entity = getResult(result);
        assertEquals("found_existing", entity.get("action"));
        assertEquals(2, entity.get("searchResultCount"));
        assertNotNull("Should have elements", entity.get("elements"));
    }

    @Test
    public void shouldNotCreate_whenSearchHasResults() throws Exception {
        accessor.setSearchResults(List.of(
                ElementDto.standard("elem-1", "Something", "BusinessProcess", null, "Business", null, null)));

        callAndParse("search-and-create",
                Map.of("query", "something", "createType", "BusinessProcess", "createName", "New Thing"));

        assertFalse("createElement should not have been called", accessor.createElementCalled);
    }

    @Test
    public void shouldReturnNextSteps_whenSearchHasResults() throws Exception {
        accessor.setSearchResults(List.of(
                ElementDto.standard("elem-1", "Something", "BusinessProcess", null, "Business", null, null)));

        Map<String, Object> result = callAndParse("search-and-create",
                Map.of("query", "something", "createType", "BusinessProcess", "createName", "New Thing"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-element")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("force: true")));
    }

    // ---- search-and-create tests: no results, create new ----

    @Test
    public void shouldReturnCreatedNew_whenSearchHasNoResults() throws Exception {
        accessor.setSearchResults(Collections.emptyList());

        Map<String, Object> result = callAndParse("search-and-create",
                Map.of("query", "nonexistent", "createType", "BusinessActor", "createName", "New Actor"));

        Map<String, Object> entity = getResult(result);
        assertEquals("created_new", entity.get("action"));
        assertEquals(0, entity.get("searchResultCount"));
        assertNotNull("Should have element", entity.get("element"));
    }

    @Test
    public void shouldCallCreate_whenSearchHasNoResults() throws Exception {
        accessor.setSearchResults(Collections.emptyList());

        callAndParse("search-and-create",
                Map.of("query", "nonexistent", "createType", "BusinessActor", "createName", "New Actor"));

        assertTrue("createElement should have been called", accessor.createElementCalled);
    }

    @Test
    public void shouldReturnModelChanged_whenSearchCreatesNew() throws Exception {
        accessor.setSearchResults(Collections.emptyList());

        Map<String, Object> result = callAndParse("search-and-create",
                Map.of("query", "nonexistent", "createType", "BusinessActor", "createName", "New Actor"));

        Map<String, Object> entity = getResult(result);
        assertEquals(true, entity.get("modelChanged"));
    }

    @Test
    public void shouldReturnBatchInfo_whenSearchCreatesInBatchMode() throws Exception {
        accessor.setSearchResults(Collections.emptyList());
        accessor.setBatchMode(true);

        Map<String, Object> result = callAndParse("search-and-create",
                Map.of("query", "nonexistent", "createType", "BusinessActor", "createName", "New Actor"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have batch info", entity.get("batch"));
    }

    // ---- search-and-create error tests ----

    @Test
    public void shouldReturnModelNotLoaded_whenNoModelForSearchAndCreate() throws Exception {
        StubDiscoveryAccessor noModel = new StubDiscoveryAccessor(false);
        DiscoveryHandler noModelHandler = new DiscoveryHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolResult result = noModelHandler.handleSearchAndCreate(null,
                McpSchema.CallToolRequest.builder().name("search-and-create")
                        .arguments(Map.of("query", "test", "createType", "BusinessActor",
                                "createName", "Test")).build());

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameter_whenQueryMissingForSearchAndCreate() throws Exception {
        McpSchema.CallToolResult result = callTool("search-and-create",
                Map.of("createType", "BusinessActor", "createName", "Test"));

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameter_whenCreateTypeMissingForSearchAndCreate() throws Exception {
        McpSchema.CallToolResult result = callTool("search-and-create",
                Map.of("query", "test", "createName", "Test"));

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameter_whenCreateNameMissingForSearchAndCreate() throws Exception {
        McpSchema.CallToolResult result = callTool("search-and-create",
                Map.of("query", "test", "createType", "BusinessActor"));

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- Source traceability tests ----

    @Test
    public void shouldPassSourceWhenCreating_inGetOrCreate() throws Exception {
        accessor.setExactMatchResult(Optional.empty());

        Map<String, Object> args = new HashMap<>();
        args.put("type", "BusinessActor");
        args.put("name", "New Actor");
        args.put("source", Map.of("tool", "migration", "version", "2.0"));

        callAndParse("get-or-create-element", args);

        assertTrue("createElement should have been called", accessor.createElementCalled);
        assertNotNull("Source should be captured", accessor.capturedSource);
        assertEquals("migration", accessor.capturedSource.get("tool"));
        assertEquals("2.0", accessor.capturedSource.get("version"));
    }

    @Test
    public void shouldPassNullSource_whenSourceOmittedInGetOrCreate() throws Exception {
        accessor.setExactMatchResult(Optional.empty());

        callAndParse("get-or-create-element",
                Map.of("type", "BusinessActor", "name", "New Actor"));

        assertTrue("createElement should have been called", accessor.createElementCalled);
        assertNull("Source should be null when not provided", accessor.capturedSource);
    }

    @Test
    public void shouldPassCreateSourceWhenCreating_inSearchAndCreate() throws Exception {
        accessor.setSearchResults(Collections.emptyList());

        Map<String, Object> args = new HashMap<>();
        args.put("query", "nonexistent");
        args.put("createType", "BusinessActor");
        args.put("createName", "New Actor");
        args.put("createSource", Map.of("origin", "csv-import"));

        callAndParse("search-and-create", args);

        assertTrue("createElement should have been called", accessor.createElementCalled);
        assertNotNull("Source should be captured", accessor.capturedSource);
        assertEquals("csv-import", accessor.capturedSource.get("origin"));
    }

    // ---- Approval mode tests ----

    @Test
    public void shouldReturnProposalResponse_whenGetOrCreateInApprovalMode() throws Exception {
        accessor.setExactMatchResult(Optional.empty());
        ProposalContext proposalCtx = new ProposalContext("p-disc-1",
                "Create BusinessActor: New Actor", Instant.now());
        accessor.setCreateElementBehavior((sid, t, n, d, p, f) -> {
            ElementDto dto = ElementDto.standard("preview-1", n, t, null, "Business", d, null);
            return new MutationResult<>(dto, null, proposalCtx);
        });

        Map<String, Object> result = callAndParse("get-or-create-element",
                Map.of("type", "BusinessActor", "name", "New Actor"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-disc-1", proposal.get("proposalId"));
        assertEquals("pending", proposal.get("status"));

        assertNotNull("Should have preview", entity.get("preview"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
    }

    @Test
    public void shouldReturnProposalResponse_whenSearchAndCreateInApprovalMode() throws Exception {
        accessor.setSearchResults(Collections.emptyList());
        ProposalContext proposalCtx = new ProposalContext("p-disc-2",
                "Create BusinessActor: New Actor", Instant.now());
        accessor.setCreateElementBehavior((sid, t, n, d, p, f) -> {
            ElementDto dto = ElementDto.standard("preview-2", n, t, null, "Business", d, null);
            return new MutationResult<>(dto, null, proposalCtx);
        });

        Map<String, Object> result = callAndParse("search-and-create",
                Map.of("query", "nonexistent", "createType", "BusinessActor",
                        "createName", "New Actor"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-disc-2", proposal.get("proposalId"));
        assertEquals("pending", proposal.get("status"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
    }

    // ---- MutationException tests ----

    @Test
    public void shouldReturnMutationFailed_whenGetOrCreateCreateThrows() throws Exception {
        accessor.setExactMatchResult(Optional.empty());
        accessor.setCreateElementBehavior((sid, t, n, d, p, f) -> {
            throw new MutationException("Command stack error");
        });

        McpSchema.CallToolResult result = callTool("get-or-create-element",
                Map.of("type", "BusinessActor", "name", "New Actor"));

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MUTATION_FAILED", error.get("code"));
    }

    @Test
    public void shouldReturnMutationFailed_whenSearchAndCreateCreateThrows() throws Exception {
        accessor.setSearchResults(Collections.emptyList());
        accessor.setCreateElementBehavior((sid, t, n, d, p, f) -> {
            throw new MutationException("Command stack error");
        });

        McpSchema.CallToolResult result = callTool("search-and-create",
                Map.of("query", "nonexistent", "createType", "BusinessActor", "createName", "New Actor"));

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MUTATION_FAILED", error.get("code"));
    }

    // ---- Helper methods ----

    private McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(args)
                .build();

        return switch (toolName) {
            case "get-or-create-element" -> handler.handleGetOrCreateElement(null, request);
            case "search-and-create" -> handler.handleSearchAndCreate(null, request);
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }

    private Map<String, Object> callAndParse(String toolName, Map<String, Object> args)
            throws Exception {
        McpSchema.CallToolResult result = callTool(toolName, args);
        assertFalse("Should not be an error for tool " + toolName, result.isError());
        return parseResult(result);
    }

    private Map<String, Object> parseResult(McpSchema.CallToolResult result) throws Exception {
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        return objectMapper.readValue(content, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getResult(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("result");
    }

    // ---- Test stubs ----

    @FunctionalInterface
    private interface CreateElementBehavior {
        MutationResult<ElementDto> create(String sessionId, String type, String name,
                String documentation, Map<String, String> properties, String folderId);
    }

    private static class StubDiscoveryAccessor extends BaseTestAccessor {

        private Optional<ElementDto> exactMatchResult = Optional.empty();
        private List<ElementDto> searchResults = Collections.emptyList();
        private boolean batchMode = false;
        boolean createElementCalled = false;
        Map<String, String> capturedSource;
        private CreateElementBehavior createBehavior = null;

        private final StubMutationDispatcher dispatcher;

        StubDiscoveryAccessor() {
            super(true);
            this.dispatcher = new StubMutationDispatcher();
        }

        StubDiscoveryAccessor(boolean modelLoaded) {
            super(modelLoaded);
            this.dispatcher = modelLoaded ? new StubMutationDispatcher() : null;
        }

        void setExactMatchResult(Optional<ElementDto> result) {
            this.exactMatchResult = result;
        }

        void setSearchResults(List<ElementDto> results) {
            this.searchResults = results;
        }

        void setBatchMode(boolean batch) {
            this.batchMode = batch;
        }

        void setCreateElementBehavior(CreateElementBehavior behavior) {
            this.createBehavior = behavior;
        }

        @Override
        public Optional<ElementDto> findExactMatch(String type, String name) {
            return exactMatchResult;
        }

        @Override
        public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter,
                                               String specializationFilter) {
            return searchResults;
        }

        @Override
        public MutationResult<ElementDto> createElement(String sessionId, String type,
                String name, String documentation, Map<String, String> properties,
                String folderId, String specialization) {
            createElementCalled = true;
            if (createBehavior != null) {
                return createBehavior.create(sessionId, type, name, documentation, properties, folderId);
            }
            ElementDto dto = ElementDto.standard(
                    "elem-created-1", name, type, null, "Business", documentation, null);
            return new MutationResult<>(dto, batchMode ? 1 : null);
        }

        @Override
        public MutationResult<ElementDto> createElement(String sessionId, String type,
                String name, String documentation, Map<String, String> properties,
                String folderId, Map<String, String> source, String specialization) {
            capturedSource = source;
            return createElement(sessionId, type, name, documentation, properties, folderId, specialization);
        }

        @Override
        public MutationDispatcher getMutationDispatcher() {
            return dispatcher;
        }
    }

    private static class StubMutationDispatcher extends MutationDispatcher {
        StubMutationDispatcher() {
            super(() -> null);
        }

        @Override
        protected void dispatchCommand(org.eclipse.gef.commands.Command command)
                throws MutationException {
            // no-op for handler tests
        }
    }
}
