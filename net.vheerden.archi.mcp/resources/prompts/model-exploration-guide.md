# ArchiMate Model Exploration Guide

## Recommended Tool Pipeline

Follow this sequence when exploring any ArchiMate model:

1. **`get-model-info`** — Always call first. Returns model name, element/relationship/view counts, type distributions, layer distributions, and model-size-aware `nextSteps` suggestions.
2. **Targeted search** — Use `search-elements` with text queries and type/layer filters to find specific elements.
3. **Traverse from anchors** — Use `get-relationships` on discovered elements to map dependencies and connections.
4. **View contents as needed** — Use `get-view-contents` to see how elements are arranged in specific diagrams.

## Interpreting get-model-info Statistics

The `get-model-info` response provides planning data:

| Field | Purpose |
|-------|---------|
| `elementCount` | Total elements — determines exploration strategy (see scale heuristics below) |
| `relationshipCount` | Total relationships — high ratios to elements indicate dense connectivity |
| `viewCount` | Total views — indicates how much visual documentation exists |
| `elementTypeDistribution` | Count per ArchiMate type (e.g., `"ApplicationComponent": 42`) — shows where model mass is |
| `relationshipTypeDistribution` | Count per relationship type (e.g., `"ServingRelationship": 30`) — shows what connections exist before committing to traversal |
| `layerDistribution` | Count per ArchiMate layer (e.g., `"Application": 87`) — shows which architectural layers are most documented |

## Scale-Based Exploration Heuristics

### Small Model (< 100 elements)
- **Primary strategy:** View-first exploration via `get-views`
- The entire model can be explored through views
- Unfiltered `get-views` is safe — result sets will be small
- `search-elements` works as a targeted alternative

### Medium Model (100-500 elements)
- **Primary strategy:** Filtered search via `search-elements` with `type` or `layer` parameters
- Use `get-views` with a `viewpoint` filter to narrow down relevant diagrams
- Consider `set-session-filter` to scope ongoing analysis to a specific layer or type
- Avoid unfiltered listings of all elements

### Large Model (> 500 elements)
- **Primary strategy:** Targeted search with specific text queries
- Always use `set-session-filter` to scope all subsequent queries to a specific layer or type
- Use `get-views` only with filters — large models may have many views
- Avoid unfiltered listing commands — they will return large result sets that waste tokens
- Use `fields: "minimal"` for initial exploration, then `fields: "standard"` for elements of interest

## Token-Cost Awareness

### Field Selection (`fields` parameter)
- **`"minimal"`** — Returns only `id` and `name`. Use for initial discovery, element lists, and building inventories.
- **`"standard"`** — Returns `id`, `name`, `type`, `layer`, `documentation`, `properties`. Default and sufficient for most analysis.
- **`"full"`** — Returns all available fields. Use only when you need every detail.

### Exclude Patterns (`exclude` parameter)
- Use `exclude: ["documentation"]` when you only need element metadata, not full descriptions
- Use `exclude: ["properties"]` when properties are not relevant to your analysis
- Use `exclude: ["documentation", "properties"]` for maximum token savings on large result sets

### Session Filters (`set-session-filter`)
- Set once, applies to all subsequent queries in the session
- Use `type` filter when analysing a specific element type (e.g., only ApplicationComponents)
- Use `layer` filter when focussing on one architectural layer (e.g., only Technology)
- Per-query filters override session filters for that parameter
- Use `clear: true` to remove all session filters when changing focus

## Tool Selection Decision Guide

| Goal | Preferred Tool | Why |
|------|---------------|-----|
| Understand model scope | `get-model-info` | Provides counts, distributions, and scale-aware next steps |
| Find elements by name/description | `search-elements` with `query` | Full-text search across names, docs, properties |
| Find elements by type | `search-elements` with `type` | Filtered search returns only matching types |
| List all views/diagrams | `get-views` | Returns view names, viewpoints, folder paths |
| See what's in a diagram | `get-view-contents` with `viewId` | Returns elements and relationships in a specific view |
| Get full element details | `get-element` with `id` | Returns complete element data including documentation |
| Get multiple elements at once | `get-element` with `ids` array | Batch retrieval — more efficient than individual calls |
| Explore element connections | `get-relationships` with `elementId` | Configurable depth (0-3) for relationship detail |
| Map dependency chains | `get-relationships` with `traverse: true` | Follows relationships transitively across multiple hops |
| Focus on specific dependencies | `get-relationships` with `includeTypes`/`excludeTypes`/`filterLayer` | Constrained traversal for targeted analysis |
| Scope all queries to a subset | `set-session-filter` | Persistent filter — no need to repeat on every query |
| Check active filters | `get-session-filters` | View current session filter state |

## Dynamic nextSteps

The `get-model-info` response includes a `nextSteps` array with model-size-aware recommendations. These suggestions are tailored to the actual model's element count and composition, providing contextually appropriate guidance at the point of first contact. Follow these suggestions as your starting point.
