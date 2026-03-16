package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.response.FieldSelector;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;

/**
 * Unit tests for TraversalEngine BFS algorithm (Story 7.0a).
 *
 * <p>Uses a stub ArchiModelAccessor — no EMF/OSGi runtime required.
 * Tests cover: direction filtering, maxDepth limiting, cycle detection,
 * type/layer filter application, truncation, progress callbacks, and
 * empty frontier termination.</p>
 */
public class TraversalEngineTest {

    private TraversalEngine engine;
    private StubAccessor accessor;

    @Before
    public void setUp() {
        accessor = new StubAccessor();
        engine = new TraversalEngine(accessor);
    }

    // ---- Constructor Tests ----

    @Test(expected = NullPointerException.class)
    public void shouldThrowNPE_whenAccessorNull() {
        new TraversalEngine(null);
    }

    // ---- Single-Hop Outgoing Traversal (3.3) ----

    @Test
    public void shouldTraverseOutgoing_whenDirectionOutgoing() {
        // Chain A -> B -> C -> D, maxDepth=5 to avoid truncation from non-empty frontier
        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 5, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertNotNull(result);
        assertFalse(result.truncated());
        assertEquals(3, result.totalElementsDiscovered()); // B, C, D
        assertEquals(3, result.totalRelationships());
        assertFalse(result.cyclesDetected());

        // Verify hop structure: 3 hops for A->B, B->C, C->D
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) result.data().get("hops");
        assertEquals(3, hops.size());
        assertEquals(1, hops.get(0).get("hopLevel"));
    }

    // ---- Single-Hop Incoming Traversal (3.4) ----

    @Test
    public void shouldTraverseIncoming_whenDirectionIncoming() {
        // B has incoming from A (A->B)
        TraversalEngine.TraversalResult result = engine.execute(
                "B", elem("B"), 3, "incoming", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertNotNull(result);
        assertEquals(1, result.totalElementsDiscovered());
        assertEquals(1, result.totalRelationships());

        // Verify connected element is A (the source)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) result.data().get("hops");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rels = (List<Map<String, Object>>) hops.get(0).get("relationships");
        @SuppressWarnings("unchecked")
        Map<String, Object> connected = (Map<String, Object>) rels.get(0).get("connectedElement");
        assertEquals("A", connected.get("id"));
    }

    // ---- Multi-Hop Traversal (3.5) ----

    @Test
    public void shouldTraverseMultipleHops_whenMaxDepth3() {
        // Chain: A -> B -> C -> D
        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 3, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertEquals(3, result.totalElementsDiscovered()); // B, C, D
        assertEquals(3, result.totalRelationships());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) result.data().get("hops");
        assertEquals(3, hops.size());
        assertEquals(1, hops.get(0).get("hopLevel"));
        assertEquals(2, hops.get(1).get("hopLevel"));
        assertEquals(3, hops.get(2).get("hopLevel"));
    }

    // ---- Direction Both (3.6) ----

    @Test
    public void shouldTraverseBoth_whenDirectionBoth() {
        // From B: outgoing goes to C, incoming from A
        TraversalEngine.TraversalResult result = engine.execute(
                "B", elem("B"), 1, "both", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertEquals(2, result.totalElementsDiscovered()); // A and C
        assertEquals(2, result.totalRelationships());
    }

    // ---- Cycle Detection (3.7) ----

    @Test
    public void shouldDetectCycles_whenGraphHasCycle() {
        // Use cycle accessor: A -> B -> C -> A
        accessor = new CycleAccessor();
        engine = new TraversalEngine(accessor);

        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 5, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertTrue(result.cyclesDetected());
        assertEquals(2, result.totalElementsDiscovered()); // B, C (not A again)
    }

    // ---- Type Filter Application (3.8) ----

    @Test
    public void shouldApplyIncludeTypeFilter_whenIncludeTypesSet() {
        // Only include ServingRelationship (A->B), exclude FlowRelationship (B->C)
        TraversalHandler.RelationshipFilters filters = new TraversalHandler.RelationshipFilters(
                null, Set.of("ServingRelationship"), null, null);

        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 3, "outgoing", filters,
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertEquals(1, result.totalElementsDiscovered()); // Only B
        assertEquals(1, result.totalRelationships());
    }

    @Test
    public void shouldApplyExcludeTypeFilter_whenExcludeTypesSet() {
        // Exclude ServingRelationship — A->B is blocked
        TraversalHandler.RelationshipFilters filters = new TraversalHandler.RelationshipFilters(
                Set.of("ServingRelationship"), null, null, null);

        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 3, "outgoing", filters,
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertEquals(0, result.totalElementsDiscovered());
        assertEquals(0, result.totalRelationships());
    }

    // ---- Layer Filter Application (3.9) ----

    @Test
    public void shouldApplyLayerFilter_whenFilterLayerSet() {
        // Filter layer "Business" — B is Business, C is Technology
        TraversalHandler.RelationshipFilters filters = new TraversalHandler.RelationshipFilters(
                null, null, "Business", null);

        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 3, "outgoing", filters,
                FieldSelector.FieldPreset.STANDARD, null, null);

        // Only B (Business layer) passes, C (Technology) blocked
        assertEquals(1, result.totalElementsDiscovered());
    }

    // ---- Truncation at MAX_TRAVERSAL_ELEMENTS (3.10) ----

    @Test
    public void shouldTruncate_whenMaxElementsExceeded() {
        // Use wide accessor that returns many connections
        accessor = new WideAccessor();
        engine = new TraversalEngine(accessor);

        TraversalEngine.TraversalResult result = engine.execute(
                "root", elemWithId("root"), 5, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertTrue(result.truncated());
        // Should have stopped at or near MAX_TRAVERSAL_ELEMENTS
        assertTrue(result.totalElementsDiscovered() <= TraversalEngine.MAX_TRAVERSAL_ELEMENTS + 1);
    }

    // ---- Empty Frontier Termination (3.11) ----

    @Test
    public void shouldTerminate_whenNoRelationshipsFound() {
        // elem-isolated has no relationships
        TraversalEngine.TraversalResult result = engine.execute(
                "isolated", elemWithId("isolated"), 3, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertEquals(0, result.totalElementsDiscovered());
        assertEquals(0, result.totalRelationships());
        assertFalse(result.truncated());
        assertFalse(result.cyclesDetected());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) result.data().get("hops");
        assertTrue(hops.isEmpty());
    }

    // ---- Progress Callback Invocation (3.12) ----

    @Test
    public void shouldInvokeProgressCallback_whenTimeThresholdExceeded() {
        // Use slow accessor to exceed PROGRESS_TIME_THRESHOLD_MS
        accessor = new SlowAccessor();
        engine = new TraversalEngine(accessor);

        List<String> progressMessages = new ArrayList<>();
        TraversalEngine.ProgressCallback callback = (current, total, message) -> {
            progressMessages.add(message);
        };

        engine.execute("A", elem("A"), 3, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, callback);

        // SlowAccessor adds 300ms per getRelationshipsForElement call.
        // By hop 2 (~600ms elapsed), the 500ms threshold is exceeded,
        // so the progress callback should have been invoked at least once.
        assertFalse("Progress callback should have been invoked after time threshold exceeded",
                progressMessages.isEmpty());
    }

    // ---- Null Progress Callback (3.13) ----

    @Test
    public void shouldNotThrow_whenProgressCallbackNull() {
        // Should execute without NPE even with null callback
        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 3, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertNotNull(result);
    }

    // ---- Hop Structure Correctness (3.14) ----

    @Test
    public void shouldBuildCorrectHopStructure_whenMultiHop() {
        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 2, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) result.data().get("hops");

        // Hop 1: A -> B
        assertEquals(1, hops.get(0).get("hopLevel"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hop1Rels = (List<Map<String, Object>>) hops.get(0).get("relationships");
        assertEquals(1, hop1Rels.size());
        assertEquals("rel-AB", hop1Rels.get(0).get("id"));
        assertNotNull(hop1Rels.get(0).get("connectedElement"));

        // Hop 2: B -> C
        assertEquals(2, hops.get(1).get("hopLevel"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hop2Rels = (List<Map<String, Object>>) hops.get(1).get("relationships");
        assertEquals(1, hop2Rels.size());
        assertEquals("rel-BC", hop2Rels.get(0).get("id"));
    }

    // ---- Element Summary With Field Selection (3.15) ----

    @Test
    public void shouldBuildMinimalSummary_whenFieldPresetMinimal() {
        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 1, "outgoing", noFilters(),
                FieldSelector.FieldPreset.MINIMAL, null, null);

        // Start element summary should have only id and name (MINIMAL)
        @SuppressWarnings("unchecked")
        Map<String, Object> startElement = (Map<String, Object>) result.data().get("startElement");
        assertTrue(startElement.containsKey("id"));
        assertTrue(startElement.containsKey("name"));
        assertFalse(startElement.containsKey("type"));
    }

    @Test
    public void shouldBuildStandardSummary_whenFieldPresetStandard() {
        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 1, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        // Start element summary should have id, name, type (STANDARD)
        @SuppressWarnings("unchecked")
        Map<String, Object> startElement = (Map<String, Object>) result.data().get("startElement");
        assertTrue(startElement.containsKey("id"));
        assertTrue(startElement.containsKey("name"));
        assertTrue(startElement.containsKey("type"));
    }

    // ---- MaxDepth Limiting ----

    @Test
    public void shouldLimitDepth_whenMaxDepth1() {
        // Chain A -> B -> C -> D, but maxDepth=1
        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 1, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        assertEquals(1, result.totalElementsDiscovered()); // Only B
        assertTrue(result.truncated()); // More relationships exist beyond depth 1
    }

    // ---- Traversal Summary in Data Map ----

    @Test
    public void shouldIncludeTraversalSummary_inDataMap() {
        TraversalEngine.TraversalResult result = engine.execute(
                "A", elem("A"), 3, "outgoing", noFilters(),
                FieldSelector.FieldPreset.STANDARD, null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.data().get("traversalSummary");
        assertNotNull(summary);
        assertEquals(result.totalElementsDiscovered(), ((Number) summary.get("totalElementsDiscovered")).intValue());
        assertEquals(result.totalRelationships(), ((Number) summary.get("totalRelationships")).intValue());
        assertEquals(result.cyclesDetected(), summary.get("cyclesDetected"));
    }

    // ---- Helper Methods ----

    private TraversalHandler.RelationshipFilters noFilters() {
        return new TraversalHandler.RelationshipFilters(null, null, null, null);
    }

    private ElementDto elem(String id) {
        return accessor.getElementById(id).orElse(
                ElementDto.standard(id, "Element " + id, "ApplicationComponent",
                        "Application", null, List.of()));
    }

    private ElementDto elemWithId(String id) {
        return ElementDto.standard(id, "Element " + id, "ApplicationComponent",
                "Application", null, List.of());
    }

    // ---- Stub Accessors ----

    /**
     * Default stub: linear chain A -> B -> C -> D.
     * A, B are Application layer; C, D are Technology layer.
     */
    private static class StubAccessor extends BaseTestAccessor {
        private final Map<String, ElementDto> elements = new HashMap<>();
        private final Map<String, List<RelationshipDto>> relationships = new HashMap<>();

        StubAccessor() {
            super();
            elements.put("A", ElementDto.standard("A", "Component A", "ApplicationComponent",
                    "Application", "Test A", List.of()));
            elements.put("B", ElementDto.standard("B", "Process B", "BusinessProcess",
                    "Business", "Test B", List.of()));
            elements.put("C", ElementDto.standard("C", "Service C", "TechnologyService",
                    "Technology", "Test C", List.of()));
            elements.put("D", ElementDto.standard("D", "Node D", "Node",
                    "Technology", "Test D", List.of()));

            RelationshipDto relAB = new RelationshipDto("rel-AB", "Serves",
                    "ServingRelationship", "A", "B");
            RelationshipDto relBC = new RelationshipDto("rel-BC", "Flows",
                    "FlowRelationship", "B", "C");
            RelationshipDto relCD = new RelationshipDto("rel-CD", "Triggers",
                    "TriggeringRelationship", "C", "D");

            relationships.put("A", List.of(relAB));
            relationships.put("B", List.of(relAB, relBC));
            relationships.put("C", List.of(relBC, relCD));
            relationships.put("D", List.of(relCD));
        }

        @Override public Optional<ElementDto> getElementById(String id) {
            return Optional.ofNullable(elements.get(id));
        }
        @Override public List<ElementDto> getElementsByIds(List<String> ids) {
            return ids.stream().distinct()
                    .map(this::getElementById).filter(Optional::isPresent)
                    .map(Optional::get).toList();
        }
        @Override public List<RelationshipDto> getRelationshipsForElement(String elementId) {
            return relationships.getOrDefault(elementId, List.of());
        }
        @Override public String getModelVersion() { return "v1"; }
        @Override public Optional<String> getCurrentModelName() { return Optional.of("Test"); }
    }

    /**
     * Cycle accessor: A -> B -> C -> A (creates a cycle).
     */
    private static class CycleAccessor extends StubAccessor {
        CycleAccessor() {
            super();
        }
        @Override public List<RelationshipDto> getRelationshipsForElement(String elementId) {
            RelationshipDto relAB = new RelationshipDto("rel-AB", "Serves",
                    "ServingRelationship", "A", "B");
            RelationshipDto relBC = new RelationshipDto("rel-BC", "Flows",
                    "FlowRelationship", "B", "C");
            RelationshipDto relCA = new RelationshipDto("rel-CA", "Triggers",
                    "TriggeringRelationship", "C", "A");

            return switch (elementId) {
                case "A" -> List.of(relAB);
                case "B" -> List.of(relAB, relBC);
                case "C" -> List.of(relBC, relCA);
                default -> List.of();
            };
        }
    }

    /**
     * Wide accessor: root connects to 250+ elements (exceeds MAX_TRAVERSAL_ELEMENTS).
     */
    private static class WideAccessor extends StubAccessor {
        WideAccessor() {
            super();
        }
        @Override public Optional<ElementDto> getElementById(String id) {
            return Optional.of(ElementDto.standard(id, "Element " + id,
                    "ApplicationComponent", "Application", null, List.of()));
        }
        @Override public List<ElementDto> getElementsByIds(List<String> ids) {
            return ids.stream().distinct()
                    .map(id -> ElementDto.standard(id, "Element " + id,
                            "ApplicationComponent", "Application", null, List.of()))
                    .toList();
        }
        @Override public List<RelationshipDto> getRelationshipsForElement(String elementId) {
            if ("root".equals(elementId)) {
                List<RelationshipDto> rels = new ArrayList<>();
                for (int i = 0; i < 250; i++) {
                    rels.add(new RelationshipDto("rel-" + i, "Serves " + i,
                            "ServingRelationship", "root", "node-" + i));
                }
                return rels;
            }
            return List.of();
        }
    }

    /**
     * Slow accessor: adds delay to simulate time-consuming operations
     * for testing progress callback timing.
     */
    private static class SlowAccessor extends StubAccessor {
        SlowAccessor() {
            super();
        }
        @Override public List<RelationshipDto> getRelationshipsForElement(String elementId) {
            try {
                Thread.sleep(300); // Simulate slow model access
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.getRelationshipsForElement(elementId);
        }
    }
}
