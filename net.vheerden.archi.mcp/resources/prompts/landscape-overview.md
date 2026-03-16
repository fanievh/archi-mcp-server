# Architecture Landscape Overview Workflow

## Purpose
Generate a comprehensive summary of an ArchiMate model's architecture landscape, covering scope, structure, key components, and cross-layer relationships.

## Steps

### Step 1: Get Model Overview
- Call `get-model-info` to understand model scale and composition
- Note `elementCount`, `viewCount`, `layerDistribution`, and `elementTypeDistribution`
- Use this data to frame the landscape narrative (e.g., "This model contains 250 elements across 3 layers, with the Application layer being most documented")

### Step 2: Identify Key Views
- Call `get-views` to list all diagrams
- Focus on views with broad viewpoints (e.g., Layered, Organization, Application Cooperation)
- Select 2-4 key views that represent the most important architectural perspectives

### Step 3: Explore Key Views
- Call `get-view-contents` for each selected view
- Document the major elements and their relationships within each view
- Note clusters of related elements and cross-view patterns

### Step 4: Layer-by-Layer Summary
- For each ArchiMate layer present in the model (check `layerDistribution`):
  - Use `search-elements` with `layer` filter to find elements in that layer
  - Use `fields: "minimal"` for initial inventory, then drill into key elements
  - Identify the most important elements by their relationship count or documentation richness

### Step 5: Cross-Layer Connections
- For key elements identified above, use `get-relationships` with `depth: 1` to map connections
- Focus on cross-layer relationships (e.g., Application to Technology, Business to Application)
- These connections reveal how the architecture layers interact

### Step 6: Synthesize Narrative
- Combine findings into a structured landscape summary:
  - **Scope:** Model name, total elements, layers covered
  - **Business Layer:** Key processes, actors, services
  - **Application Layer:** Core applications, interfaces, services
  - **Technology Layer:** Infrastructure, platforms, networks
  - **Cross-Layer Dependencies:** How layers connect and depend on each other
  - **Key Observations:** Patterns, risks, or gaps identified

## Tips
- Use `set-session-filter` when focusing on each layer to avoid cross-layer noise
- Clear filters between layers with `set-session-filter` with `clear: true`
- For large models, prioritise views over exhaustive element listing
- Use `exclude: ["properties"]` to reduce token usage when properties are not relevant to the summary
