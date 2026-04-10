# Extension Guide

This document provides step-by-step instructions for extending the ArchiMate MCP Server plugin: adding new tools, creating handler classes, adding layout algorithms, and registering MCP resources.

## Table of Contents

- [Adding a New Tool](#adding-a-new-tool)
- [Creating a New Handler Class](#creating-a-new-handler-class)
- [Adding a Layout Algorithm](#adding-a-layout-algorithm)
- [Adding an MCP Resource](#adding-an-mcp-resource)
- [Checklist](#checklist)

## Adding a New Tool

This is the most common extension point. You add a new tool to an existing handler class.

### Step 1: Define the Tool Specification

Create a `buildYourToolSpec()` method in the handler:

```java
private McpServerFeatures.SyncToolSpecification buildYourToolSpec() {
    // Define parameters
    Map<String, Object> properties = new LinkedHashMap<>();

    Map<String, Object> idParam = new LinkedHashMap<>();
    idParam.put("type", "string");
    idParam.put("description", "The unique identifier of the target element");
    properties.put("id", idParam);

    Map<String, Object> nameParam = new LinkedHashMap<>();
    nameParam.put("type", "string");
    nameParam.put("description", "Optional display name filter");
    properties.put("name", nameParam);

    // Build input schema
    McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
            "object", properties, List.of("id"), null, null, null);

    // Build tool definition
    McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("your-tool-name")
            .description("[Category] Brief description of what this tool does. "
                + "Detailed parameter documentation. Related: other-tool, another-tool")
            .inputSchema(inputSchema)
            .build();

    // Wire the handler
    return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler(this::handleYourTool)
            .build();
}
```

**Naming conventions:**
- Tool names are kebab-case: `your-tool-name`
- Description starts with `[Category]` tag: `[Query]`, `[Mutation]`, `[Layout]`, etc.
- Description includes parameter documentation and related tools

### Step 2: Implement the Handler Method

```java
private McpSchema.CallToolResult handleYourTool(
        McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    logger.info("Handling your-tool-name request");

    try {
        Map<String, Object> args = request.arguments();

        // Validate required parameters
        if (args == null || !args.containsKey("id")) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INVALID_PARAMETER,
                    "The 'id' parameter is required",
                    null,
                    "Provide an element ID from get-element or search-elements",
                    null);
            return HandlerUtils.buildResult(
                    formatter.toJsonString(formatter.formatError(error)), true);
        }

        String id = (String) args.get("id");

        // Use accessor for all model operations
        Optional<ElementDto> result = accessor.getElementById(id);

        if (result.isEmpty()) {
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.ELEMENT_NOT_FOUND,
                    "No element found with ID '" + id + "'",
                    null,
                    "Use search-elements to find elements by name",
                    null);
            return HandlerUtils.buildResult(
                    formatter.toJsonString(formatter.formatError(error)), true);
        }

        // Build success response
        List<String> nextSteps = List.of(
                "Use get-relationships to explore connections",
                "Use update-element to modify properties");

        Map<String, Object> envelope = formatter.formatSuccess(
                result.get(),
                nextSteps,
                accessor.getModelVersion(),
                1, 1, false);

        return HandlerUtils.buildResult(
                formatter.toJsonString(envelope), false);

    } catch (NoModelLoadedException e) {
        ErrorResponse error = new ErrorResponse(
                ErrorCode.MODEL_NOT_LOADED, e.getMessage());
        return HandlerUtils.buildResult(
                formatter.toJsonString(formatter.formatError(error)), true);

    } catch (Exception e) {
        logger.error("Unexpected error in your-tool-name", e);
        ErrorResponse error = new ErrorResponse(
                ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred");
        return HandlerUtils.buildResult(
                formatter.toJsonString(formatter.formatError(error)), true);
    }
}
```

### Step 3: Register in registerTools()

```java
public void registerTools() {
    registry.registerTool(buildExistingToolSpec());
    registry.registerTool(buildYourToolSpec());  // Add here
}
```

**That's it.** The CommandRegistry handles timing injection, server notification, and client discovery automatically.

## Creating a New Handler Class

Create a new handler when you have a group of related tools that form a distinct domain.

### Handler Template

```java
package net.vheerden.archi.mcp.handlers;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

public class YourDomainHandler {

    private static final Logger logger =
            LoggerFactory.getLogger(YourDomainHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;

    public YourDomainHandler(ArchiModelAccessor accessor,
                             ResponseFormatter formatter,
                             CommandRegistry registry,
                             SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor);
        this.formatter = Objects.requireNonNull(formatter);
        this.registry = Objects.requireNonNull(registry);
        this.sessionManager = sessionManager; // May be null
    }

    public void registerTools() {
        registry.registerTool(buildToolOneSpec());
        registry.registerTool(buildToolTwoSpec());
    }

    // Tool spec and handler methods...
}
```

### Register in McpServerManager

Add to the `initializeHandlers()` method in `McpServerManager.java`:

```java
private void initializeHandlers() {
    // ... existing handlers ...

    YourDomainHandler yourHandler = new YourDomainHandler(
            modelAccessor, formatter, commandRegistry, sessionManager);
    yourHandler.registerTools();

    logger.info("YourDomainHandler initialized");
}
```

### Architecture Boundaries

Your handler class **must not** import:
- `com.archimatetool.model.*` (EMF model types)
- `org.eclipse.emf.*` (EMF framework)
- `org.eclipse.swt.*` (SWT/UI toolkit)
- `org.eclipse.jface.*` (JFace)

All model access goes through the `ArchiModelAccessor` interface. This boundary ensures handlers are testable with mocks.

### Real-World Example: SpecializationHandler

`handlers/SpecializationHandler.java` is a small, focused handler that registers four related tools (`create-specialization`, `update-specialization`, `delete-specialization`, `get-specialization-usage`). It is a good template for adding a new domain-focused handler:

- All four tools share the same constructor pattern (accessor + formatter + registry + nullable session manager)
- Each `build*Spec()` method defines its own input schema and wires the handler method
- Mutation tools route through the standard mutation pipeline (immediate / batch / approval) — the handler does not call the SWT thread directly
- Pure-query tools (`get-specialization-usage`) call the accessor and format the result without entering the mutation pipeline at all
- The class doc string explicitly notes the architecture boundary and the relationship to inline mutation parameters declared on other handlers

## Adding a Layout Algorithm

### Step 1: Add Algorithm Description

In `LayoutEngine.java`, add to the static `ALGORITHM_DESCRIPTIONS` map:

```java
static {
    ALGORITHM_DESCRIPTIONS = new LinkedHashMap<>();
    ALGORITHM_DESCRIPTIONS.put("tree", "top-down hierarchical tree");
    ALGORITHM_DESCRIPTIONS.put("spring", "force-directed/spring-based");
    // ... existing algorithms ...
    ALGORITHM_DESCRIPTIONS.put("your-algo", "brief description here");
}
```

### Step 2: Add Algorithm Resolution

In the `resolveAlgorithm()` method, add a case:

```java
private AbstractLayoutAlgorithm resolveAlgorithm(String name, double spacing) {
    int style = LayoutStyles.NO_LAYOUT_NODE_RESIZING;
    switch (name) {
        case "tree":
            return new TreeLayoutAlgorithm(style);
        // ... existing cases ...
        case "your-algo":
            YourAlgorithm algo = new YourAlgorithm(style);
            algo.setSpacing((int) spacing);
            return algo;
        default:
            throw new ModelAccessException(
                    "Invalid algorithm. Valid: " + listAlgorithms(),
                    ErrorCode.INVALID_PARAMETER);
    }
}
```

**Requirements:**
- The algorithm must extend `AbstractLayoutAlgorithm` from Eclipse Zest
- Use `NO_LAYOUT_NODE_RESIZING` style to prevent node resizing
- The LayoutEngine handles overlap resolution after algorithm execution

### Optional: Add a Preset

In `LayoutPreset.java`, map a semantic name to your algorithm:

```java
PRESETS.put("your-preset", new LayoutPreset("your-algo", 50));
```

## Adding an MCP Resource

MCP Resources are static reference materials served to LLM clients.

### Step 1: Create the Resource File

Create a markdown file in the resources directory:

```text
net.vheerden.archi.mcp/resources/reference/your-topic.md
```

Write the content in standard markdown with tables, code blocks, and lists.

### Step 2: Register the Resource

In `ResourceHandler.java`, add to the `RESOURCE_DEFINITIONS` map:

```java
private static final Map<String, ResourceDefinition> RESOURCE_DEFINITIONS = Map.of(
    // ... existing resources ...
    "reference/your-topic", new ResourceDefinition(
            "Your Topic Reference",
            "Human-readable description of what this resource contains",
            "reference/your-topic.md")
);
```

### Step 3: Update build.properties

Ensure the `resources/` directory is included in `build.properties`:

```text
bin.includes = META-INF/,\
               .,\
               lib/,\
               resources/
```

The resource is loaded from the classpath at server startup, cached in memory, and served at `archimate://reference/your-topic`.

## Checklist

Use this checklist when extending the plugin:

**Adding a tool:**

- [ ] Tool spec with name, description, and input schema
- [ ] Handler method with validation, model access via accessor, response formatting
- [ ] Error handling for NoModelLoadedException and unexpected exceptions
- [ ] Registration in handler's `registerTools()` method
- [ ] Tool description includes `[Category]` tag, parameter docs, and related tools

**Adding a handler:**

- [ ] Constructor takes ArchiModelAccessor, ResponseFormatter, CommandRegistry, SessionManager
- [ ] No EMF/SWT/ArchimateTool imports (Layer 2 boundary)
- [ ] `registerTools()` method registers all tools
- [ ] Handler instantiated and registered in `McpServerManager.initializeHandlers()`

**Adding a layout algorithm:**

- [ ] Description added to `ALGORITHM_DESCRIPTIONS` map
- [ ] Case added to `resolveAlgorithm()` switch
- [ ] Algorithm extends `AbstractLayoutAlgorithm` from Zest
- [ ] Optional: preset mapping in `LayoutPreset.java`

**Adding an MCP resource:**

- [ ] Markdown file created in `resources/` directory
- [ ] Resource definition added to `RESOURCE_DEFINITIONS` map
- [ ] `build.properties` includes `resources/` directory
- [ ] URI follows `archimate://category/name` pattern

**General:**

- [ ] All JSON field names are camelCase
- [ ] All tool names are kebab-case
- [ ] Response uses standard envelope (result/error, nextSteps, _meta)
- [ ] Errors use structured ErrorResponse with ErrorCode
- [ ] Logging uses SLF4J (never `System.out.println()`)
- [ ] New DTO types are immutable Java records

---

**See also:** [Architecture Overview](architecture.md) | [MCP Integration](mcp-integration.md) | [Mutation Model](mutation-model.md)
