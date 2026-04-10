package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.MutationResultDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for discovery-first creation tools: get-or-create-element,
 * search-and-create (Story 7-4).
 *
 * <p>These tools combine search/lookup with element creation to reduce
 * duplicate elements in the model. They search first and only create
 * if no existing match is found.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF, GEF, SWT, or ArchimateTool model types. All model access
 * goes through {@link ArchiModelAccessor}.</p>
 */
public class DiscoveryHandler {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    // nullable — null in test mode without session management
    private final SessionManager sessionManager;

    public DiscoveryHandler(ArchiModelAccessor accessor,
                            ResponseFormatter formatter,
                            CommandRegistry registry,
                            SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager;
    }

    public void registerTools() {
        registry.registerTool(buildGetOrCreateElementSpec());
        registry.registerTool(buildSearchAndCreateSpec());
    }

    // ---- get-or-create-element ----

    private McpServerFeatures.SyncToolSpecification buildGetOrCreateElementSpec() {
        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("description",
                "ArchiMate element type (e.g., 'BusinessActor', 'ApplicationComponent')");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description", "Element name to find or create");

        Map<String, Object> documentationProp = new LinkedHashMap<>();
        documentationProp.put("type", "string");
        documentationProp.put("description",
                "Optional documentation text (only used if creating a new element)");

        Map<String, Object> propertiesStringValues = new LinkedHashMap<>();
        propertiesStringValues.put("type", "string");
        Map<String, Object> propertiesProp = new LinkedHashMap<>();
        propertiesProp.put("type", "object");
        propertiesProp.put("description",
                "Optional key-value properties (only used if creating a new element)");
        propertiesProp.put("additionalProperties", propertiesStringValues);

        Map<String, Object> folderIdProp = new LinkedHashMap<>();
        folderIdProp.put("type", "string");
        folderIdProp.put("description",
                "Optional folder ID (only used if creating a new element). "
                + "The folder must be under the correct root folder for the element's "
                + "ArchiMate layer (e.g., Strategy elements in Strategy subfolders). "
                + "If omitted, element is placed in the default folder for its type.");

        Map<String, Object> sourceStringValues = new LinkedHashMap<>();
        sourceStringValues.put("type", "string");
        Map<String, Object> sourceProp = new LinkedHashMap<>();
        sourceProp.put("type", "object");
        sourceProp.put("description",
                "Optional source traceability map (only used if creating). "
                + "Keys auto-prefixed with 'mcp.source.' in element properties.");
        sourceProp.put("additionalProperties", sourceStringValues);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", typeProp);
        properties.put("name", nameProp);
        properties.put("documentation", documentationProp);
        properties.put("properties", propertiesProp);
        properties.put("folderId", folderIdProp);
        properties.put("source", sourceProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("type", "name"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-or-create-element")
                .description("[Discovery] Get an existing element or create a new one. "
                        + "Returns the existing element if an exact name+type match exists "
                        + "(case-insensitive), otherwise creates and returns a new element. "
                        + "Ideal for idempotent model building. Required: type, name. "
                        + "Optional: documentation, properties, folderId, source. "
                        + "Related: create-element (explicit creation with duplicate warnings), "
                        + "search-elements (broader search).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetOrCreateElement)
                .build();
    }

    McpSchema.CallToolResult handleGetOrCreateElement(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-or-create-element request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String type = HandlerUtils.requireStringParam(args, "type");
            String name = HandlerUtils.requireStringParam(args, "name");

            // Check for exact match first
            Optional<ElementDto> existing = accessor.findExactMatch(type, name);
            if (existing.isPresent()) {
                return buildFoundExistingResponse(existing.get());
            }

            // No match — create new element
            String documentation = HandlerUtils.optionalStringParam(args, "documentation");
            Map<String, String> propertiesMap = HandlerUtils.optionalMapParam(args, "properties");
            String folderId = HandlerUtils.optionalStringParam(args, "folderId");
            Map<String, String> source = HandlerUtils.optionalMapParam(args, "source");

            MutationResult<ElementDto> result = accessor.createElement(
                    sessionId, type, name, documentation, propertiesMap, folderId, source, null);

            return buildCreatedNewResponse(result);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling get-or-create-element", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred in get-or-create-element");
        }
    }

    private McpSchema.CallToolResult buildFoundExistingResponse(ElementDto element) {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("action", "found_existing");
        resultMap.put("element", element);

        List<String> nextSteps = List.of(
                "Use get-relationships with elementId '" + element.id() + "' to explore connections",
                "Use update-element to modify properties or documentation",
                "Use search-elements to find related elements");

        String modelVersion = accessor.getModelVersion();
        Map<String, Object> envelope = formatter.formatSuccess(
                resultMap, nextSteps, modelVersion, 1, 1, false);

        return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
    }

    private McpSchema.CallToolResult buildCreatedNewResponse(MutationResult<ElementDto> result) {
        String modelVersion = accessor.getModelVersion();

        // Approval mode: return proposal response (Story 7-6)
        if (result.isProposal()) {
            Map<String, Object> previewMap = new LinkedHashMap<>();
            previewMap.put("action", "created_new");
            previewMap.put("element", result.entity());

            return HandlerUtils.formatProposalResponse(previewMap, result.proposalContext(),
                    modelVersion, formatter);
        }

        if (result.isBatched()) {
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("action", "created_new");

            MutationResultDto batchDto = new MutationResultDto(
                    true, "Mutation queued for batch commit", result.batchSequenceNumber());
            Map<String, Object> batchResponse = new LinkedHashMap<>();
            batchResponse.put("batch", batchDto);
            batchResponse.put("preview", resultMap);
            batchResponse.put("element", result.entity());

            List<String> nextSteps = List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");

            Map<String, Object> envelope = formatter.formatSuccess(
                    batchResponse, nextSteps, modelVersion, 1, 1, false);
            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
        }

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("action", "created_new");
        resultMap.put("element", result.entity());
        resultMap.put("modelChanged", true);

        String id = result.entity().id();
        List<String> nextSteps = List.of(
                "Use get-element with id '" + id + "' to verify the new element",
                "Use create-relationship to connect to other elements",
                "Use search-elements to find related elements");

        Map<String, Object> envelope = formatter.formatSuccess(
                resultMap, nextSteps, modelVersion, 1, 1, false);

        return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
    }

    // ---- search-and-create ----

    private McpServerFeatures.SyncToolSpecification buildSearchAndCreateSpec() {
        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "Search query to find existing elements");

        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("description", "Optional type filter for the search");

        Map<String, Object> createTypeProp = new LinkedHashMap<>();
        createTypeProp.put("type", "string");
        createTypeProp.put("description",
                "ArchiMate element type for the new element if search returns no results");

        Map<String, Object> createNameProp = new LinkedHashMap<>();
        createNameProp.put("type", "string");
        createNameProp.put("description",
                "Name for the new element if search returns no results");

        Map<String, Object> createDocProp = new LinkedHashMap<>();
        createDocProp.put("type", "string");
        createDocProp.put("description",
                "Optional documentation for the new element");

        Map<String, Object> propertiesStringValues = new LinkedHashMap<>();
        propertiesStringValues.put("type", "string");
        Map<String, Object> createPropertiesProp = new LinkedHashMap<>();
        createPropertiesProp.put("type", "object");
        createPropertiesProp.put("description",
                "Optional properties for the new element");
        createPropertiesProp.put("additionalProperties", propertiesStringValues);

        Map<String, Object> createFolderIdProp = new LinkedHashMap<>();
        createFolderIdProp.put("type", "string");
        createFolderIdProp.put("description",
                "Optional folder ID for the new element. "
                + "The folder must be under the correct root folder for the element's "
                + "ArchiMate layer (e.g., Strategy elements in Strategy subfolders). "
                + "If omitted, element is placed in the default folder for its type.");

        Map<String, Object> createSourceStringValues = new LinkedHashMap<>();
        createSourceStringValues.put("type", "string");
        Map<String, Object> createSourceProp = new LinkedHashMap<>();
        createSourceProp.put("type", "object");
        createSourceProp.put("description",
                "Optional source traceability map for the new element. "
                + "Keys auto-prefixed with 'mcp.source.' in element properties.");
        createSourceProp.put("additionalProperties", createSourceStringValues);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", queryProp);
        properties.put("type", typeProp);
        properties.put("createType", createTypeProp);
        properties.put("createName", createNameProp);
        properties.put("createDocumentation", createDocProp);
        properties.put("createProperties", createPropertiesProp);
        properties.put("createFolderId", createFolderIdProp);
        properties.put("createSource", createSourceProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("query", "createType", "createName"),
                null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("search-and-create")
                .description("[Discovery] Search for existing elements and create one if none found. "
                        + "Searches first using the query; if results are found, returns them "
                        + "without creating. If no results, creates the specified element automatically. "
                        + "Required: query, createType, createName. Optional: type (search filter), "
                        + "createDocumentation, createProperties, createFolderId. "
                        + "Related: search-elements (search only), create-element (create only).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleSearchAndCreate)
                .build();
    }

    // Design note: searchElements uses broad matching (name substring + doc substring)
    // intentionally — the search-and-create tool's purpose is to surface any potentially
    // relevant existing elements before creating new ones. Narrowing the search would
    // defeat the duplicate-avoidance goal.
    McpSchema.CallToolResult handleSearchAndCreate(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling search-and-create request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String query = HandlerUtils.requireStringParam(args, "query");
            String typeFilter = HandlerUtils.optionalStringParam(args, "type");
            String createType = HandlerUtils.requireStringParam(args, "createType");
            String createName = HandlerUtils.requireStringParam(args, "createName");

            // Search phase (always immediate, read-only)
            List<ElementDto> searchResults = accessor.searchElements(query, typeFilter, null, null);

            if (!searchResults.isEmpty()) {
                return buildSearchFoundResponse(searchResults, query);
            }

            // Create phase — no results found
            String createDoc = HandlerUtils.optionalStringParam(args, "createDocumentation");
            Map<String, String> createProps = HandlerUtils.optionalMapParam(args, "createProperties");
            String createFolderId = HandlerUtils.optionalStringParam(args, "createFolderId");
            Map<String, String> createSource = HandlerUtils.optionalMapParam(args, "createSource");

            MutationResult<ElementDto> result = accessor.createElement(
                    sessionId, createType, createName, createDoc, createProps,
                    createFolderId, createSource, null);

            return buildSearchCreatedResponse(result, query);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling search-and-create", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred in search-and-create");
        }
    }

    private McpSchema.CallToolResult buildSearchFoundResponse(
            List<ElementDto> results, String query) {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("action", "found_existing");
        resultMap.put("searchResultCount", results.size());
        resultMap.put("elements", results);

        List<String> nextSteps = new ArrayList<>();
        int limit = Math.min(results.size(), 3);
        for (int i = 0; i < limit; i++) {
            ElementDto el = results.get(i);
            nextSteps.add("Use get-element with id '" + el.id()
                    + "' to inspect '" + el.name() + "'");
        }
        nextSteps.add("Use create-element with force: true if no match is suitable");
        nextSteps.add("Use search-elements with different criteria to refine search");

        String modelVersion = accessor.getModelVersion();
        Map<String, Object> envelope = formatter.formatSuccess(
                resultMap, nextSteps, modelVersion,
                results.size(), results.size(), false);

        return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
    }

    private McpSchema.CallToolResult buildSearchCreatedResponse(
            MutationResult<ElementDto> result, String query) {
        String modelVersion = accessor.getModelVersion();

        // Approval mode: return proposal response (Story 7-6)
        if (result.isProposal()) {
            Map<String, Object> previewMap = new LinkedHashMap<>();
            previewMap.put("action", "created_new");
            previewMap.put("searchResultCount", 0);
            previewMap.put("element", result.entity());

            return HandlerUtils.formatProposalResponse(previewMap, result.proposalContext(),
                    modelVersion, formatter);
        }

        if (result.isBatched()) {
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("action", "created_new");
            resultMap.put("searchResultCount", 0);

            MutationResultDto batchDto = new MutationResultDto(
                    true, "Mutation queued for batch commit", result.batchSequenceNumber());
            Map<String, Object> batchResponse = new LinkedHashMap<>();
            batchResponse.put("batch", batchDto);
            batchResponse.put("preview", resultMap);
            batchResponse.put("element", result.entity());

            List<String> nextSteps = List.of(
                    "Mutation queued as operation #" + result.batchSequenceNumber() + " in current batch",
                    "Use get-batch-status to check batch progress",
                    "Use end-batch to commit all queued mutations");

            Map<String, Object> envelope = formatter.formatSuccess(
                    batchResponse, nextSteps, modelVersion, 1, 1, false);
            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
        }

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("action", "created_new");
        resultMap.put("searchResultCount", 0);
        resultMap.put("element", result.entity());
        resultMap.put("modelChanged", true);

        String id = result.entity().id();
        List<String> nextSteps = List.of(
                "Use get-element with id '" + id + "' to verify the new element",
                "Use create-relationship to connect to other elements",
                "Search returned 0 results for '" + query + "' before creating");

        Map<String, Object> envelope = formatter.formatSuccess(
                resultMap, nextSteps, modelVersion, 1, 1, false);

        return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
    }
}
