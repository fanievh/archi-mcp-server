# ArchiMate Model Exploration Guide

## Recommended Tool Pipeline

Follow this sequence when exploring any ArchiMate model:

1. **`get-model-info`** — Always call first. Returns model name, element/relationship/view counts, type distributions, layer distributions, `specializationCount`, and model-size-aware `nextSteps` suggestions.
2. **`list-specializations` (conditional)** — If `specializationCount > 0`, call this next to learn the model's specialization vocabulary before searching or creating elements. The catalog changes how subsequent searches and creations should be framed.
3. **Targeted search** — Use `search-elements` with text queries and type/layer/specialization filters to find specific elements.
4. **Traverse from anchors** — Use `get-relationships` on discovered elements to map dependencies and connections.
5. **View contents as needed** — Use `get-view-contents` to see how elements are arranged in specific diagrams.

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
| `specializationCount` | Total specialization definitions — when > 0, the model uses a custom IS-A vocabulary; call `list-specializations` to enumerate and `get-specialization-usage` to audit per-specialization usage |

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
| Browse specialization catalog | `list-specializations` | Returns all specializations with `(name, conceptType, layer, usageCount)` |
| Find elements by specialization | `search-elements` with `specialization` filter | Filtered by the IS-A vocabulary, not just type |
| Audit a specialization before delete/rename | `get-specialization-usage` | Returns elements + relationships referencing the specialization |

## Dynamic nextSteps

The `get-model-info` response includes a `nextSteps` array with model-size-aware recommendations. These suggestions are tailored to the actual model's element count and composition, providing contextually appropriate guidance at the point of first contact. Follow these suggestions as your starting point.

## Specialization-Aware Creation

Prefer the inline `specialization` param on `create-element` / `create-relationship` over a two-step "create generic concept, then assign specialization" pattern. The inline path uses a single CompoundCommand for atomic undo and auto-creates the specialization if it does not yet exist.

For pre-registering a vocabulary at the start of a session, use `bulk-mutate` with a sequence of `create-specialization` operations followed by element-creation operations that reference those names — all in one atomic batch with a single undo step. `create-specialization` is idempotent, so the pre-registration block is safe to retry. See `archimate-specializations.md` for the full pipeline and a worked end-to-end example.
