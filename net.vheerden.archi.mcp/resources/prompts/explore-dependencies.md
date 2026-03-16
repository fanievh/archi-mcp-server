# Dependency Analysis Workflow

## Purpose
Systematically discover and map dependencies for an ArchiMate element, answering questions like "What does this component depend on?" or "What would be affected if this service changed?"

## Steps

### Step 1: Identify the Target Element
- Use `search-elements` with a text query to find the element of interest
- Or use `get-element` if you already have the element ID
- Note the element's type and layer — this informs which relationship types are relevant

### Step 2: Get Direct Relationships
- Call `get-relationships` with the element's ID and `depth: 1` for summaries
- Review the relationship types present (e.g., ServingRelationship, CompositionRelationship)
- Identify which directions matter: `direction: "outgoing"` for "depends on", `direction: "incoming"` for "depended upon by"

### Step 3: Traverse Dependency Chains
- Call `get-relationships` with `traverse: true` and `maxDepth: 3` to follow transitive dependencies
- Set `direction` based on your analysis goal:
  - `"outgoing"` — What does this element ultimately rely on? (downstream dependencies)
  - `"incoming"` — What would be affected if this element changed? (upstream impact)
  - `"both"` — Full dependency map in all directions

### Step 4: Apply Semantic Filters
- Use `excludeTypes` to remove noise (e.g., `["AssociationRelationship"]` to skip loose associations)
- Use `includeTypes` for focused analysis (e.g., `["ServingRelationship", "FlowRelationship"]` for service dependencies)
- Use `filterLayer` to see only cross-layer dependencies (e.g., `"Technology"` to see what infrastructure an application needs)

### Step 5: Interpret and Report
- Summarize the dependency chain: direct dependencies, transitive dependencies, critical paths
- Note any cycles detected in the traversal (indicated in response metadata)
- Identify single points of failure or highly-connected hub elements
- Use `get-element` on key nodes for full documentation and properties

## Tips
- Start with `depth: 1` to understand the local neighbourhood before traversing deeply
- Use `fields: "minimal"` during traversal to reduce token usage, then `get-element` for details on important nodes
- Set a `set-session-filter` if you're analysing dependencies within a specific layer
- Check `_meta.isTruncated` — if true, the traversal hit limits and deeper paths may exist
