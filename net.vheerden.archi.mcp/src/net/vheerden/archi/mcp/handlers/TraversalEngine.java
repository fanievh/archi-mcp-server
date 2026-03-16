package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.response.FieldSelector;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;

/**
 * Pure BFS traversal engine for multi-hop relationship chain discovery.
 *
 * <p>Extracted from TraversalHandler (Story 7.0a) to isolate the BFS algorithm
 * from handler orchestration concerns (parameter validation, caching, format
 * routing, MCP protocol types).</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import MCP SDK
 * types, session/cache types, or response formatting types beyond FieldSelector
 * (needed for element summaries). Progress notifications cross the boundary via
 * the {@link ProgressCallback} functional interface.</p>
 */
public class TraversalEngine {

    // BFS-specific constants (moved from TraversalHandler)
    static final int MAX_TRAVERSAL_ELEMENTS = 200;
    static final int DEFAULT_MAX_DEPTH = 3;
    static final int MIN_MAX_DEPTH = 1;
    static final int MAX_MAX_DEPTH = 5;
    static final String DEFAULT_DIRECTION = "both";
    static final Set<String> VALID_DIRECTIONS = Set.of("outgoing", "incoming", "both");
    static final long PROGRESS_TIME_THRESHOLD_MS = 500;

    private final ArchiModelAccessor accessor;

    /**
     * Creates a TraversalEngine with the model accessor dependency.
     *
     * @param accessor the model accessor for querying relationships and elements
     */
    public TraversalEngine(ArchiModelAccessor accessor) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
    }

    /**
     * Executes BFS traversal from a start element, following relationship chains
     * across multiple hops with direction filtering, cycle detection, type/layer
     * filtering, and truncation.
     *
     * @param startElementId   the ID of the starting element
     * @param startElement     the starting element DTO (for summary building)
     * @param maxDepth         maximum number of hops (1-5)
     * @param direction        traversal direction: "outgoing", "incoming", or "both"
     * @param filters          relationship type and layer filters
     * @param fieldPreset      field verbosity preset for element summaries
     * @param excludeFields    fields to exclude from element summaries (may be null)
     * @param progressCallback callback for progress notifications (may be null)
     * @return the traversal result containing hops, metadata, and truncation status
     */
    public TraversalResult execute(
            String startElementId, ElementDto startElement,
            int maxDepth, String direction, TraversalHandler.RelationshipFilters filters,
            FieldSelector.FieldPreset fieldPreset, Set<String> excludeFields,
            ProgressCallback progressCallback) {

        Set<String> visited = new HashSet<>();
        visited.add(startElementId);
        List<String> currentFrontier = new ArrayList<>(List.of(startElementId));
        List<Map<String, Object>> hops = new ArrayList<>();
        boolean cyclesDetected = false;
        int totalRelationships = 0;
        boolean truncated = false;
        long startTimeNanos = System.nanoTime();

        for (int hopLevel = 1; hopLevel <= maxDepth; hopLevel++) {
            // Candidate edges: relationship + connected element ID (before layer filter)
            List<CandidateEdge> candidates = new ArrayList<>();
            Set<String> candidateConnectedIds = new LinkedHashSet<>();

            for (String elemId : currentFrontier) {
                List<RelationshipDto> rels = accessor.getRelationshipsForElement(elemId);
                for (RelationshipDto rel : rels) {
                    // Apply type filters before direction check
                    if (!passesTypeFilter(rel, filters)) continue;

                    String connectedId = getConnectedElementId(rel, elemId, direction);
                    if (connectedId == null) continue;

                    if (visited.contains(connectedId)) {
                        cyclesDetected = true;
                        continue;
                    }

                    candidates.add(new CandidateEdge(rel, connectedId));
                    candidateConnectedIds.add(connectedId);
                }
            }

            if (candidates.isEmpty()) {
                // Clear frontier so post-loop truncation check doesn't misfire.
                currentFrontier = List.of();
                break;
            }

            // Batch-lookup connected elements (needed for layer filter and summaries)
            Map<String, ElementDto> elementMap = new HashMap<>();
            if (!candidateConnectedIds.isEmpty()) {
                List<ElementDto> elements = accessor.getElementsByIds(
                        new ArrayList<>(candidateConnectedIds));
                for (ElementDto e : elements) {
                    elementMap.put(e.id(), e);
                }
            }

            // Apply layer filter and build hop relationships
            List<Map<String, Object>> hopRelationships = new ArrayList<>();
            List<String> hopConnectedIds = new ArrayList<>();
            Set<String> nextFrontier = new LinkedHashSet<>();

            for (CandidateEdge candidate : candidates) {
                String connectedId = candidate.connectedId;

                // Apply layer filter on connected element
                if (filters.filterLayer() != null) {
                    ElementDto connected = elementMap.get(connectedId);
                    if (connected == null || !filters.filterLayer().equals(connected.layer())) {
                        continue;
                    }
                }

                // Skip if already visited (may happen if same element reached via different candidates)
                if (visited.contains(connectedId)) {
                    cyclesDetected = true;
                    continue;
                }

                visited.add(connectedId);
                nextFrontier.add(connectedId);
                hopRelationships.add(buildHopRelationship(candidate.relationship));
                hopConnectedIds.add(connectedId);

                if (visited.size() > MAX_TRAVERSAL_ELEMENTS) {
                    truncated = true;
                    break;
                }
            }

            if (!hopRelationships.isEmpty()) {
                // Fill in connectedElement summaries
                for (int i = 0; i < hopRelationships.size(); i++) {
                    ElementDto element = elementMap.get(hopConnectedIds.get(i));
                    if (element != null) {
                        hopRelationships.get(i).put("connectedElement",
                                buildSummary(element, fieldPreset, excludeFields));
                    }
                }

                Map<String, Object> hop = new LinkedHashMap<>();
                hop.put("hopLevel", hopLevel);
                hop.put("relationships", hopRelationships);
                hops.add(hop);
                totalRelationships += hopRelationships.size();
            }

            // Send progress notification after each hop if time threshold exceeded
            long elapsedMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
            if (progressCallback != null && elapsedMs >= PROGRESS_TIME_THRESHOLD_MS) {
                progressCallback.onProgress(
                        (double) hopLevel, (double) maxDepth,
                        "Traversing relationships: hop " + hopLevel + "/" + maxDepth
                                + ", " + (visited.size() - 1) + " elements discovered");
            }

            currentFrontier = new ArrayList<>(nextFrontier);
            if (currentFrontier.isEmpty() || truncated) break;
        }

        // Truncated if maxDepth reached with non-empty frontier
        if (!truncated && !currentFrontier.isEmpty()) {
            truncated = true;
        }

        int totalElementsDiscovered = visited.size() - 1;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startElement", buildSummary(startElement, fieldPreset, excludeFields));
        result.put("hops", hops);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalElementsDiscovered", totalElementsDiscovered);
        summary.put("totalRelationships", totalRelationships);
        summary.put("maxDepthReached", hops.size());
        summary.put("cyclesDetected", cyclesDetected);
        result.put("traversalSummary", summary);

        return new TraversalResult(result, truncated, totalElementsDiscovered,
                totalRelationships, cyclesDetected);
    }

    /**
     * Resolves the connected element ID based on traversal direction.
     */
    private String getConnectedElementId(RelationshipDto rel,
                                  String fromElementId, String direction) {
        switch (direction) {
            case "outgoing":
                return fromElementId.equals(rel.sourceId()) ? rel.targetId() : null;
            case "incoming":
                return fromElementId.equals(rel.targetId()) ? rel.sourceId() : null;
            case "both":
            default:
                if (fromElementId.equals(rel.sourceId())) return rel.targetId();
                if (fromElementId.equals(rel.targetId())) return rel.sourceId();
                return null;
        }
    }

    /**
     * Checks if a relationship passes the type filters.
     */
    private boolean passesTypeFilter(RelationshipDto rel, TraversalHandler.RelationshipFilters filters) {
        if (filters.includeTypes() != null && !filters.includeTypes().contains(rel.type())) {
            return false;
        }
        if (filters.excludeTypes() != null && filters.excludeTypes().contains(rel.type())) {
            return false;
        }
        return true;
    }

    /**
     * Builds an element summary map with field selection applied.
     */
    private Map<String, Object> buildSummary(ElementDto element,
                                      FieldSelector.FieldPreset fieldPreset,
                                      Set<String> excludeFields) {
        Map<String, Object> summary = FieldSelector.elementDtoToMap(element);
        Set<String> summaryFields = (fieldPreset == FieldSelector.FieldPreset.MINIMAL)
                ? Set.of("id", "name")
                : Set.of("id", "name", "type");
        return FieldSelector.filterMap(summary, summaryFields, excludeFields);
    }

    /**
     * Converts a RelationshipDto to a map for hop relationship entries.
     */
    private Map<String, Object> buildHopRelationship(RelationshipDto rel) {
        Map<String, Object> hopRel = new LinkedHashMap<>();
        hopRel.put("id", rel.id());
        hopRel.put("name", rel.name());
        hopRel.put("type", rel.type());
        hopRel.put("sourceId", rel.sourceId());
        hopRel.put("targetId", rel.targetId());
        return hopRel;
    }

    // ---- Records ----

    /**
     * Immutable result of BFS traversal with top-level metadata fields.
     */
    public record TraversalResult(
            Map<String, Object> data,
            boolean truncated,
            int totalElementsDiscovered,
            int totalRelationships,
            boolean cyclesDetected) {}

    /**
     * Callback for progress notifications during traversal.
     * Engine checks null before invoking — callers may pass null safely.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(double current, double total, String message);
    }

    /**
     * Candidate edge discovered during BFS before layer filtering.
     */
    record CandidateEdge(RelationshipDto relationship, String connectedId) {}
}
