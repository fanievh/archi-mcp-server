# Technical Documentation

Comprehensive technical documentation for the ArchiMate MCP Server plugin. These documents cover internal architecture, algorithms, and extension patterns for developers who want to understand, modify, or extend the plugin.

For installation, configuration, and the complete tool catalog, see the [README](../README.md).

## Documents

### [Architecture Overview](architecture.md)

The 4-layer architecture model (Protocol, Handlers, Model, UI), package-to-layer mapping, import rules, plugin lifecycle, threading model, and dependency summary.

### [Coordinate Model](coordinate-model.md)

Absolute vs relative coordinate systems, nested element coordinate conversion, bendpoint types and conversion formulas, view object hierarchy, and auto-placement logic.

### [Routing Pipeline](routing-pipeline.md)

The multi-stage orthogonal connection routing system: visibility graph construction, A* path search with clearance weighting and corridor directionality, path ordering, edge nudging, coincident segment detection, label clearance, terminal edge attachment, center-termination and interior BP correction, orthogonality enforcement, and the recommendation engine.

### [Layout Engine](layout-engine.md)

Zest-based layout algorithms, ELK Layered integration, layout presets, group-aware layout tools (layout-within-group, arrange-groups, optimize-group-order), the multi-metric quality assessment framework, and auto-layout-and-route with target rating iteration.

### [Mutation Model](mutation-model.md)

The PreparedMutation pattern, CommandStack integration, operational modes (GUI-attached, batch, approval), undo/redo, the approval workflow, bulk-mutate with back-references, and error handling.

### [MCP Integration](mcp-integration.md)

Tool registration via CommandRegistry, the standard response envelope format, structured error responses, session management (filters, caching, field selection), MCP resources, and transport layer configuration (HTTP, SSE, TLS).

### [Extension Guide](extension-guide.md)

Step-by-step instructions for adding new tools, creating handler classes, adding layout algorithms, and registering MCP resources. Includes code examples and an extension checklist.
