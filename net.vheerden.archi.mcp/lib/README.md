# Third-Party Library Dependencies

This directory contains bundled third-party JARs required by the ArchiMate MCP Server plugin.

## MCP SDK Wiring & Transport Architecture

### How the MCP Server Works

The plugin embeds a Jetty HTTP server that hosts two MCP protocol transports, each backed by its own `McpSyncServer` instance from the MCP Java SDK:

```
LLM Client (Claude CLI, Cline, etc.)
        |
        | HTTP
        v
+------------------+
| Jetty 12 (ee10)  |    Port/bind from preferences (default 127.0.0.1:18090)
|                  |
|  /mcp/*  --------+---> HttpServletStreamableServerTransportProvider
|                  |         |
|                  |         +--> McpSyncServer (Streamable-HTTP)
|                  |
|  /sse/*  --------+---> HttpServletSseServerTransportProvider
|                  |         |
|                  |         +--> McpSyncServer (SSE)
+------------------+
```

### Dual Transport Design

| Transport | Servlet Path | MCP Spec | Primary Client |
|-----------|-------------|----------|----------------|
| Streamable-HTTP | `/mcp/*` | v2025-06-18 | Claude CLI, modern MCP clients |
| SSE | `/sse/*` (event stream), `/sse/message` (client POST) | v2024-11-05 compat | Cline, older MCP clients |

Both transports are registered as async-enabled servlets on the same Jetty server. Each gets its own `McpSyncServer` so they operate independently. Tools registered on one transport must also be registered on the other (handled by `McpServerManager` in Story 1.4+).

### Key Technical Decisions

1. **`McpSyncServer` over `McpAsyncServer`** — Synchronous API chosen because EMF model access is thread-bound and simpler to reason about in the Eclipse PDE/OSGi context. Reactor's async path works but adds unnecessary complexity for MVP read-only operations.

2. **`JacksonMcpJsonMapper` wrapper required** — The MCP SDK transport builders accept `jsonMapper(McpJsonMapper)`, not `objectMapper(ObjectMapper)`. The Jackson ObjectMapper must be wrapped: `new JacksonMcpJsonMapper(new ObjectMapper())`.

3. **Separate `McpSyncServer` per transport** — The SDK's `McpServer.sync()` factory accepts either `McpStreamableServerTransportProvider` or `McpServerTransportProvider` (different interfaces), so each transport gets its own server instance.

4. **Jakarta Servlet API 6.0 must be explicitly bundled** — Neither Jetty nor the MCP SDK JARs include `jakarta.servlet-api`. The MCP SDK's servlet transports extend `jakarta.servlet.http.HttpServlet`, requiring this JAR at both compile-time and runtime.

5. **Async support mandatory on servlet holders** — `ServletHolder.setAsyncSupported(true)` must be called (inherited from `Holder` parent class). The MCP SDK uses Servlet 6.0 async contexts internally.

6. **`TransportConfig` provides a testable `startServer(int, String)` overload** — Avoids requiring `McpPlugin` activation in unit tests. The no-arg `startServer()` reads from Eclipse preferences.

### MCP SDK API Quick Reference (v0.17.2)

```java
// Create JSON mapper
McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

// Create transport provider (it IS a HttpServlet)
var transport = HttpServletStreamableServerTransportProvider.builder()
    .jsonMapper(jsonMapper)
    .build();

// Create MCP server wired to transport
McpSyncServer server = McpServer.sync(transport)
    .serverInfo("ArchiMate MCP Server", "1.0.0")
    .capabilities(ServerCapabilities.builder().tools(true).build())
    .build();

// Register transport as servlet in Jetty
ServletHolder holder = new ServletHolder("mcp", transport);
holder.setAsyncSupported(true);
context.addServlet(holder, "/mcp/*");
```

### Jetty 12 ee10 API Notes

- Use `org.eclipse.jetty.ee10.servlet.ServletContextHandler` (NOT `org.eclipse.jetty.servlet`)
- `Server` extends `Handler.Wrapper` — `setHandler()`, `isRunning()`, `stop()` are inherited
- `setAsyncSupported(true)` is on `Holder` parent, not `ServletHolder` directly

---

## Required Libraries

### MCP Java SDK (`lib/mcp-sdk/`)
- **Version:** 0.17.2
- **Purpose:** MCP protocol implementation
- **Download:** https://central.sonatype.com/namespace/io.modelcontextprotocol.sdk

**⚠️ IMPORTANT:** The `mcp` artifact is a BOM (Bill of Materials) only - it contains NO Java classes!
You must download the actual implementation JARs:

| Artifact | Maven Coordinates | Purpose |
|----------|-------------------|---------|
| mcp-core | `io.modelcontextprotocol.sdk:mcp-core:0.17.2` | Core protocol implementation (includes Servlet transport) |
| mcp-json | `io.modelcontextprotocol.sdk:mcp-json:0.17.2` | JSON abstraction layer |
| mcp-jackson2 | `io.modelcontextprotocol.sdk:mcp-jackson2:0.17.2` | Jackson 2.x JSON binding |

**Additionally required (transitive dependencies of mcp-core):**

| Artifact | Maven Coordinates | Purpose |
|----------|-------------------|---------|
| reactor-core | `io.projectreactor:reactor-core:3.7.0` | Async support |
| reactive-streams | `org.reactivestreams:reactive-streams:1.0.4` | Reactive Streams API |

**Download links:**
- https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp-core
- https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp-json
- https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp-jackson2

**Delete the incorrect file:** `mcp-0.17.2.jar` (this is just a BOM, not usable)

### Jetty (`lib/jetty/`)
- **Version:** 12.0.18 (stay on 12.0.x - do NOT use 12.1.x due to binary incompatibility)
- **Maven artifacts:**
  - `org.eclipse.jetty:jetty-server:12.0.18`
  - `org.eclipse.jetty:jetty-http:12.0.18`
  - `org.eclipse.jetty:jetty-io:12.0.18`
  - `org.eclipse.jetty:jetty-util:12.0.18`
  - `org.eclipse.jetty:jetty-security:12.0.18`
  - `org.eclipse.jetty.ee10:jetty-ee10-servlet:12.0.18` (note different groupId for EE10)
  - `jakarta.servlet:jakarta.servlet-api:6.0.0` (required by MCP SDK servlet transports)
- **Purpose:** Embedded HTTP server for MCP transport
- **Download:**
  - Core: https://central.sonatype.com/namespace/org.eclipse.jetty
  - EE10: https://central.sonatype.com/namespace/org.eclipse.jetty.ee10
  - Servlet API: https://central.sonatype.com/artifact/jakarta.servlet/jakarta.servlet-api
- **Note:** Jetty 12 uses environment-specific modules. Use `jetty-ee10-*` for Jakarta EE 10 / Servlet 6.0 support (required by MCP SDK)

**⚠️ IMPORTANT:** `jakarta.servlet-api-6.0.0.jar` must be bundled explicitly. Neither Jetty nor the MCP SDK includes it, but the MCP SDK's servlet transport classes extend `jakarta.servlet.http.HttpServlet`.

### Jackson (`lib/jackson/`)
- **Version:** 2.16.1 (or transitive from MCP SDK)
- **Maven:** `com.fasterxml.jackson.core:jackson-core`, `jackson-databind`, `jackson-annotations`
- **Purpose:** JSON serialization
- **Download:** https://central.sonatype.com/namespace/com.fasterxml.jackson.core

### SLF4J (`lib/slf4j/`)
- **Version:** 2.0.11
- **Purpose:** Logging facade (bridged to Eclipse ILog)
- **Download:** https://central.sonatype.com/namespace/org.slf4j

**Required JARs:**

| Artifact | Maven Coordinates | Purpose |
|----------|-------------------|---------|
| slf4j-api | `org.slf4j:slf4j-api:2.0.11` | Logging API |
| slf4j-simple | `org.slf4j:slf4j-simple:2.0.11` | Simple binding for initial testing |

**⚠️ NOTE:** `slf4j-api` alone is NOT sufficient! You need a binding JAR.
For production, consider creating a custom Eclipse ILog adapter or use `slf4j-simple` for MVP.

## Installation Instructions

1. Download each JAR from Maven Central
2. Place in the appropriate subdirectory
3. Verify MANIFEST.MF Bundle-ClassPath includes all JAR paths
4. Verify build.properties includes lib/ in bin.includes

## Version Compatibility

Check the MCP Java SDK pom.xml for exact compatible versions of transitive dependencies.
The versions listed above are recommendations - use versions compatible with MCP SDK 0.17.2.

## OSGi Classloading Note

If Project Reactor classes fail to load in OSGi context, fall back to MCP SDK's
synchronous API (`McpSyncServer`) instead of the async path.
