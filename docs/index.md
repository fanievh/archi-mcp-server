# Technical Documentation

Comprehensive technical documentation for the ArchiMate MCP Server plugin. These documents cover internal architecture, algorithms, and extension patterns for developers who want to understand, modify, or extend the plugin.

For installation, configuration, and the complete tool catalog, see the [README](../README.md).

## Documents

### [Glossary](glossary.md)

Definitions of the layout-quality metrics (M1–M6, R8, `parallelConnectionGap_V_p10`, HPQ), routing terms (corridor, clearance, perimeter, hub, channel nudging, pass-through, zigzag), and rating tiers (Tier 1/2/3, 1L/1R) used across the tools, resources, and these documents.

### [Architecture Overview](architecture.md)

The 4-layer architecture model (Protocol, Handlers, Model, UI), package-to-layer mapping, import rules, plugin lifecycle, threading model, and dependency summary.

### [Coordinate Model](coordinate-model.md)

Absolute vs relative coordinate systems, nested element coordinate conversion, bendpoint types and conversion formulas, view object hierarchy, and auto-placement logic.

### [Routing Pipeline](routing-pipeline.md)

The multi-stage orthogonal connection routing system: visibility graph construction, A* path search with clearance weighting, corridor directionality, and corridor diversity (occupancy-aware routing), path ordering, edge nudging, coincident segment detection, label clearance, terminal edge attachment, center-termination and interior BP correction, orthogonality enforcement, and the recommendation engine.

### [Layout Engine](layout-engine.md)

ELK Layered layout and routing integration, the container vocabulary the layout tools share and where they deliberately differ, group-aware layout tools (layout-within-group, arrange-groups, optimize-group-order), element auto-sizing (autoSize and resize-elements-to-fit), the multi-metric quality assessment framework, and auto-layout-and-route with target rating iteration.

### [Recipe Relationship Audit](recipe-relationship-audit.md)

Every relationship the viewpoint recipe library prescribes, checked against the relationship matrix Archi enforces: the pairs the recipes name in full, the universal claims they make, and the clauses that name a relationship type without naming its endpoints — with the number of endpoint readings each one admits and how many of those the metamodel rejects.

### [Mutation Model](mutation-model.md)

The PreparedMutation pattern, CommandStack integration, operational modes (GUI-attached, batch, approval), undo/redo, the approval workflow and the disclosure contract its cards are held to, bulk-mutate with back-references, and error handling.

### [MCP Integration](mcp-integration.md)

Tool registration via CommandRegistry, the standard response envelope format, structured error responses, session management (filters, caching, field selection), MCP resources, and transport layer configuration (HTTP, SSE, TLS, bearer-token authentication).

### [Ordering Hazards](ordering-hazards.md)

The placement rule for ordering hazards — a caveat whose remedy is a change in sequence must be stated in the description of the tool that is unsafe to call at the wrong time, not only in a reference page — the scope test that decides which tools it applies to, and the registry of the hazards currently placed, with the build-fired guard that keeps the two in step.

### [Extension Guide](extension-guide.md)

Step-by-step instructions for adding new tools, creating handler classes, adding layout algorithms, and registering MCP resources. Includes code examples and an extension checklist.

### [Bibliography](bibliography.md)

The numbered reference list the layout and routing documents cite into by number (`[4]`), plus the per-stage map that says which pipeline stage rests on which paper — and which stages are marked **empirical**, meaning the implementation is a project-specific contribution or a vendor-compatibility adaptation with no academic basis claimed.
