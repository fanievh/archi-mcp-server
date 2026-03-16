package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.FieldSelector;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for folder navigation tools: get-folders, get-folder-tree (Story 7-0b).
 *
 * <p>Provides folder browsing and search capabilities so LLM clients can discover
 * valid folder IDs for element placement (required by Story 7-2 create-element).</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF or ArchimateTool model types. All model access goes through
 * {@link ArchiModelAccessor}.</p>
 */
public class FolderHandler {

    private static final Logger logger = LoggerFactory.getLogger(FolderHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;

    /**
     * Creates a FolderHandler with its required dependencies.
     *
     * @param accessor       the model accessor for querying ArchiMate data
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager for session-scoped filters, may be null
     */
    public FolderHandler(ArchiModelAccessor accessor,
                         ResponseFormatter formatter,
                         CommandRegistry registry,
                         SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager;
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     * Registers: get-folders, get-folder-tree.
     */
    public void registerTools() {
        registry.registerTool(buildGetFoldersSpec());
        registry.registerTool(buildGetFolderTreeSpec());
    }

    // ---- get-folders ----

    private McpServerFeatures.SyncToolSpecification buildGetFoldersSpec() {
        Map<String, Object> parentIdProp = new LinkedHashMap<>();
        parentIdProp.put("type", "string");
        parentIdProp.put("description",
                "ID of parent folder to list children of. Omit for root-level folders.");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description",
                "Case-insensitive substring filter on folder name. "
                + "When used without parentId, searches ALL folders recursively.");

        Map<String, Object> fieldsProp = new LinkedHashMap<>();
        fieldsProp.put("type", "string");
        fieldsProp.put("description", "Field verbosity preset. "
                + "'minimal' returns only id and name. "
                + "'standard' (default) returns all fields. "
                + "'full' returns all fields.");
        fieldsProp.put("enum", List.of("minimal", "standard", "full"));

        Map<String, Object> excludeProp = new LinkedHashMap<>();
        excludeProp.put("type", "array");
        Map<String, Object> excludeItems = new LinkedHashMap<>();
        excludeItems.put("type", "string");
        excludeProp.put("items", excludeItems);
        excludeProp.put("description", "Fields to exclude from folder data. "
                + "Applied after fields preset. "
                + "Valid values: documentation, properties, layer, type, "
                + "viewpointType, folderPath, visualMetadata. "
                + "Note: id and name cannot be excluded.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("parentId", parentIdProp);
        properties.put("name", nameProp);
        properties.put("fields", fieldsProp);
        properties.put("exclude", excludeProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-folders")
                .description("[Folders] List folders in the ArchiMate model. "
                        + "Returns root-level folders by default, or children of a specific folder "
                        + "when parentId is provided. Supports name filtering for searching folders "
                        + "across the hierarchy. "
                        + "Related: get-folder-tree (full hierarchy view), "
                        + "get-element (element details), create-element (needs folderId).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetFolders)
                .build();
    }

    private McpSchema.CallToolResult handleGetFolders(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-folders request");
        try {
            String parentId = null;
            String nameFilter = null;
            String fieldsParam = null;
            List<String> excludeParam = null;

            if (request.arguments() != null) {
                Object parentIdObj = request.arguments().get("parentId");
                if (parentIdObj instanceof String p && !p.isBlank()) {
                    parentId = p;
                }
                Object nameObj = request.arguments().get("name");
                if (nameObj instanceof String n && !n.isBlank()) {
                    nameFilter = n;
                }
                Object fieldsObj = request.arguments().get("fields");
                if (fieldsObj instanceof String f && !f.isBlank()) {
                    fieldsParam = f;
                }
                Object excludeObj = request.arguments().get("exclude");
                if (excludeObj instanceof List<?> el && !el.isEmpty()) {
                    excludeParam = new ArrayList<>();
                    for (Object item : el) {
                        if (item instanceof String s && !s.isBlank()) excludeParam.add(s);
                    }
                    if (excludeParam.isEmpty()) excludeParam = null;
                }
            }

            // Validate exclude field names
            if (excludeParam != null) {
                for (String field : excludeParam) {
                    if (!FieldSelector.VALID_EXCLUDE_FIELDS.contains(field)) {
                        ErrorResponse error = new ErrorResponse(
                                ErrorCode.INVALID_PARAMETER,
                                "Invalid exclude field: '" + field + "'",
                                null,
                                "Valid exclude fields: documentation, properties, layer, type, "
                                        + "viewpointType, folderPath, visualMetadata",
                                null);
                        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                    }
                }
            }

            // Merge with session field selection
            String sessionId = (sessionManager != null)
                    ? SessionManager.extractSessionId(exchange) : null;
            String effectiveFieldsPreset = fieldsParam;
            Set<String> effectiveExclude = excludeParam != null ? Set.copyOf(excludeParam) : null;
            if (sessionManager != null) {
                effectiveFieldsPreset = sessionManager.getEffectiveFieldsPreset(sessionId, fieldsParam);
                effectiveExclude = sessionManager.getEffectiveExcludeFields(sessionId, excludeParam);
            }

            // Parse preset with fallback
            FieldSelector.FieldPreset preset = FieldSelector.FieldPreset.STANDARD;
            String warningMessage = null;
            if (effectiveFieldsPreset != null) {
                Optional<FieldSelector.FieldPreset> parsed = FieldSelector.FieldPreset.fromString(effectiveFieldsPreset);
                if (parsed.isPresent()) {
                    preset = parsed.get();
                } else {
                    warningMessage = "Invalid fields preset '" + effectiveFieldsPreset
                            + "', using 'standard'. Valid presets: minimal, standard, full";
                }
            }

            String modelVersion = accessor.getModelVersion();
            List<FolderDto> folders;

            if (nameFilter != null && parentId == null) {
                // Name filter without parentId: search ALL folders recursively
                logger.debug("get-folders with name='{}' (recursive search)", nameFilter);
                folders = accessor.searchFolders(nameFilter);
            } else if (parentId != null) {
                // List children of specific folder
                Optional<FolderDto> parent = accessor.getFolderById(parentId);
                if (parent.isEmpty()) {
                    int rootCount;
                    try {
                        rootCount = accessor.getRootFolders().size();
                    } catch (Exception ex) {
                        rootCount = 0;
                    }
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.FOLDER_NOT_FOUND,
                            "No folder found with ID '" + parentId + "'",
                            "The model contains " + rootCount + " root folders.",
                            "Use get-folders without parentId to see root folders",
                            null);
                    return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                }
                folders = accessor.getFolderChildren(parentId);

                // Apply name post-filter if both parentId and name specified
                if (nameFilter != null) {
                    logger.debug("get-folders with parentId='{}', name='{}'", parentId, nameFilter);
                    String lowerName = nameFilter.toLowerCase();
                    folders = folders.stream()
                            .filter(f -> f.name() != null && f.name().toLowerCase().contains(lowerName))
                            .toList();
                }
            } else {
                // No parentId, no name filter: return root folders
                folders = accessor.getRootFolders();
            }

            int totalCount = folders.size();

            // Apply field selection
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filteredResult =
                    (List<Map<String, Object>>) FieldSelector.applyFieldSelection(folders, preset, effectiveExclude);

            // Build nextSteps
            List<String> nextSteps = new ArrayList<>();
            if (parentId == null && nameFilter == null) {
                nextSteps.add("Use get-folders with parentId to drill into a specific folder");
                nextSteps.add("Use get-folder-tree for the full folder hierarchy");
            } else if (parentId != null) {
                nextSteps.add("Use get-folders with parentId to drill deeper into subfolders");
                nextSteps.add("Use get-element with an element ID for element details");
            } else {
                nextSteps.add("Use get-folders with parentId to explore a matching folder");
                nextSteps.add("Use get-folder-tree for the full folder hierarchy");
            }

            Map<String, Object> envelope = formatter.formatSuccess(
                    filteredResult, nextSteps, modelVersion,
                    totalCount, totalCount, false);

            if (warningMessage != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
                meta.put("warning", warningMessage);
            }

            return buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.MODEL_NOT_LOADED,
                    e.getMessage(),
                    null,
                    "Open an ArchiMate model in ArchimateTool",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);

        } catch (Exception e) {
            logger.error("Unexpected error handling get-folders", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while retrieving folders");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    // ---- get-folder-tree ----

    private McpServerFeatures.SyncToolSpecification buildGetFolderTreeSpec() {
        Map<String, Object> rootIdProp = new LinkedHashMap<>();
        rootIdProp.put("type", "string");
        rootIdProp.put("description",
                "ID of root folder for subtree. Omit for full tree.");

        Map<String, Object> nameProp = new LinkedHashMap<>();
        nameProp.put("type", "string");
        nameProp.put("description",
                "Case-insensitive name filter. Only branches containing matching folders are shown.");

        Map<String, Object> depthProp = new LinkedHashMap<>();
        depthProp.put("type", "integer");
        depthProp.put("description",
                "Maximum tree depth. Default: unlimited.");

        Map<String, Object> fieldsProp = new LinkedHashMap<>();
        fieldsProp.put("type", "string");
        fieldsProp.put("description", "Field verbosity preset. "
                + "'minimal' returns only id and name per node. "
                + "'standard' (default) returns all fields per node. "
                + "'full' returns all fields per node.");
        fieldsProp.put("enum", List.of("minimal", "standard", "full"));

        Map<String, Object> excludeProp = new LinkedHashMap<>();
        excludeProp.put("type", "array");
        Map<String, Object> excludeItems = new LinkedHashMap<>();
        excludeItems.put("type", "string");
        excludeProp.put("items", excludeItems);
        excludeProp.put("description", "Fields to exclude from folder data. "
                + "Applied after fields preset. "
                + "Valid values: documentation, properties, layer, type, "
                + "viewpointType, folderPath, visualMetadata. "
                + "Note: id and name cannot be excluded.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("rootId", rootIdProp);
        properties.put("name", nameProp);
        properties.put("depth", depthProp);
        properties.put("fields", fieldsProp);
        properties.put("exclude", excludeProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-folder-tree")
                .description("[Folders] Returns the folder hierarchy as a nested tree structure. "
                        + "Shows all root folders and their subfolders by default, or a subtree "
                        + "starting from a specific folder. Useful for understanding model "
                        + "organization before creating elements. "
                        + "Related: get-folders (flat listing with details), "
                        + "search-elements (find elements by name), create-element (needs folderId).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetFolderTree)
                .build();
    }

    private McpSchema.CallToolResult handleGetFolderTree(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-folder-tree request");
        try {
            String rootId = null;
            String nameFilter = null;
            int depth = 0; // 0 = unlimited
            String fieldsParam = null;
            List<String> excludeParam = null;

            if (request.arguments() != null) {
                Object rootIdObj = request.arguments().get("rootId");
                if (rootIdObj instanceof String r && !r.isBlank()) {
                    rootId = r;
                }
                Object nameObj = request.arguments().get("name");
                if (nameObj instanceof String n && !n.isBlank()) {
                    nameFilter = n;
                }
                Object depthObj = request.arguments().get("depth");
                if (depthObj instanceof Number d) {
                    depth = d.intValue();
                }
                Object fieldsObj = request.arguments().get("fields");
                if (fieldsObj instanceof String f && !f.isBlank()) {
                    fieldsParam = f;
                }
                Object excludeObj = request.arguments().get("exclude");
                if (excludeObj instanceof List<?> el && !el.isEmpty()) {
                    excludeParam = new ArrayList<>();
                    for (Object item : el) {
                        if (item instanceof String s && !s.isBlank()) excludeParam.add(s);
                    }
                    if (excludeParam.isEmpty()) excludeParam = null;
                }
            }

            // Validate depth
            if (depth < 0) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "The 'depth' parameter must be 0 (unlimited) or a positive integer",
                        null,
                        "Use depth=1 for immediate children only, or omit for full tree",
                        null);
                return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Validate exclude field names
            if (excludeParam != null) {
                for (String field : excludeParam) {
                    if (!FieldSelector.VALID_EXCLUDE_FIELDS.contains(field)) {
                        ErrorResponse error = new ErrorResponse(
                                ErrorCode.INVALID_PARAMETER,
                                "Invalid exclude field: '" + field + "'",
                                null,
                                "Valid exclude fields: documentation, properties, layer, type, "
                                        + "viewpointType, folderPath, visualMetadata",
                                null);
                        return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                    }
                }
            }

            // Merge with session field selection
            String sessionId = (sessionManager != null)
                    ? SessionManager.extractSessionId(exchange) : null;
            String effectiveFieldsPreset = fieldsParam;
            Set<String> effectiveExclude = excludeParam != null ? Set.copyOf(excludeParam) : null;
            if (sessionManager != null) {
                effectiveFieldsPreset = sessionManager.getEffectiveFieldsPreset(sessionId, fieldsParam);
                effectiveExclude = sessionManager.getEffectiveExcludeFields(sessionId, excludeParam);
            }

            // Parse preset with fallback
            FieldSelector.FieldPreset preset = FieldSelector.FieldPreset.STANDARD;
            String warningMessage = null;
            if (effectiveFieldsPreset != null) {
                Optional<FieldSelector.FieldPreset> parsed = FieldSelector.FieldPreset.fromString(effectiveFieldsPreset);
                if (parsed.isPresent()) {
                    preset = parsed.get();
                } else {
                    warningMessage = "Invalid fields preset '" + effectiveFieldsPreset
                            + "', using 'standard'. Valid presets: minimal, standard, full";
                }
            }

            // Validate rootId if provided
            if (rootId != null) {
                Optional<FolderDto> rootFolder = accessor.getFolderById(rootId);
                if (rootFolder.isEmpty()) {
                    int rootCount;
                    try {
                        rootCount = accessor.getRootFolders().size();
                    } catch (Exception ex) {
                        rootCount = 0;
                    }
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.FOLDER_NOT_FOUND,
                            "No folder found with ID '" + rootId + "'",
                            "The model contains " + rootCount + " root folders.",
                            "Use get-folders without parentId to see root folders",
                            null);
                    return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
                }
            }

            String modelVersion = accessor.getModelVersion();
            List<FolderTreeDto> tree = accessor.getFolderTree(rootId, depth);

            // Apply name filter: prune branches that don't contain matching folders
            if (nameFilter != null && !nameFilter.isEmpty()) {
                logger.debug("get-folder-tree with name='{}'", nameFilter);
                String lowerName = nameFilter.toLowerCase();
                tree = pruneTree(tree, lowerName);
            }

            // Apply field selection via FieldSelector (handles recursive children)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> filteredResult =
                    (List<Map<String, Object>>) FieldSelector.applyFieldSelection(tree, preset, effectiveExclude);

            int totalCount = countTreeNodes(tree);

            // Build nextSteps
            List<String> nextSteps = List.of(
                    "Use get-folders with parentId for a detailed listing of a specific folder",
                    "Use search-elements with layer filter to find elements in a specific layer's folders");

            Map<String, Object> envelope = formatter.formatSuccess(
                    filteredResult, nextSteps, modelVersion,
                    totalCount, totalCount, false);

            if (warningMessage != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
                meta.put("warning", warningMessage);
            }

            return buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.MODEL_NOT_LOADED,
                    e.getMessage(),
                    null,
                    "Open an ArchiMate model in ArchimateTool",
                    null);
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);

        } catch (Exception e) {
            logger.error("Unexpected error handling get-folder-tree", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while retrieving folder tree");
            return buildResult(formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    // ---- Tree pruning for name filter ----

    /**
     * Prunes a tree to only include branches containing folders whose names
     * match the given filter (case-insensitive substring).
     */
    private List<FolderTreeDto> pruneTree(List<FolderTreeDto> tree, String lowerNameFilter) {
        List<FolderTreeDto> result = new ArrayList<>();
        for (FolderTreeDto node : tree) {
            FolderTreeDto pruned = pruneNode(node, lowerNameFilter);
            if (pruned != null) {
                result.add(pruned);
            }
        }
        return result;
    }

    private FolderTreeDto pruneNode(FolderTreeDto node, String lowerNameFilter) {
        boolean nameMatches = node.name() != null
                && node.name().toLowerCase().contains(lowerNameFilter);

        // Recursively prune children
        List<FolderTreeDto> prunedChildren = null;
        if (node.children() != null) {
            prunedChildren = new ArrayList<>();
            for (FolderTreeDto child : node.children()) {
                FolderTreeDto prunedChild = pruneNode(child, lowerNameFilter);
                if (prunedChild != null) {
                    prunedChildren.add(prunedChild);
                }
            }
            if (prunedChildren.isEmpty()) {
                prunedChildren = null;
            }
        }

        // Include this node if it matches OR has matching descendants
        if (nameMatches || prunedChildren != null) {
            return new FolderTreeDto(
                    node.id(), node.name(), node.type(), node.path(),
                    node.elementCount(), node.subfolderCount(),
                    prunedChildren);
        }
        return null;
    }

    private int countTreeNodes(List<FolderTreeDto> tree) {
        int count = 0;
        for (FolderTreeDto node : tree) {
            count += countNodes(node);
        }
        return count;
    }

    private int countNodes(FolderTreeDto node) {
        int count = 1;
        if (node.children() != null) {
            for (FolderTreeDto child : node.children()) {
                count += countNodes(child);
            }
        }
        return count;
    }

    private McpSchema.CallToolResult buildResult(String json, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(isError)
                .build();
    }
}
