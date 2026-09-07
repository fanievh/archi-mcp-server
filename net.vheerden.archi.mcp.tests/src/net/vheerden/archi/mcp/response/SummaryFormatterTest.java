package net.vheerden.archi.mcp.response;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;

/**
 * Tests for {@link SummaryFormatter}.
 */
public class SummaryFormatterTest {

    // ---- summarizeElements ----

    @Test
    public void summarizeElements_empty_shouldReturnNoElementsFound() {
        assertEquals("No elements found.", SummaryFormatter.summarizeElements(List.of(), null));
    }

    @Test
    public void summarizeElements_emptyWithContext_shouldIncludeSearchTerm() {
        String result = SummaryFormatter.summarizeElements(List.of(), "auth");
        assertTrue(result.contains("'auth'"));
        assertTrue(result.contains("No elements found"));
    }

    @Test
    public void summarizeElements_null_shouldReturnNoElementsFound() {
        assertEquals("No elements found.", SummaryFormatter.summarizeElements(null, null));
    }

    @Test
    public void summarizeElements_singleElement_shouldShowTypeAndLayer() {
        ElementDto e = ElementDto.standard("id1", "Portal",
                "ApplicationComponent", null, "Application", null, null);
        String result = SummaryFormatter.summarizeElements(List.of(e), null);
        assertTrue(result.contains("Found 1 element"));
        assertTrue(result.contains("ApplicationComponent"));
        assertTrue(result.contains("Application"));
    }

    @Test
    public void summarizeElements_multipleElements_shouldShowTypeDistribution() {
        List<ElementDto> elements = List.of(
                ElementDto.standard("1", "A", "ApplicationComponent", null, "Application", null, null),
                ElementDto.standard("2", "B", "ApplicationComponent", null, "Application", null, null),
                ElementDto.standard("3", "C", "BusinessProcess", null, "Business", null, null));
        String result = SummaryFormatter.summarizeElements(elements, "test");
        assertTrue(result.contains("Found 3 elements"));
        assertTrue(result.contains("'test'"));
        assertTrue(result.contains("2 ApplicationComponent"));
        assertTrue(result.contains("1 BusinessProcess"));
    }

    @Test
    public void summarizeElements_manyTypes_shouldShowTop3AndOthers() {
        List<ElementDto> elements = List.of(
                ElementDto.standard("1", "A", "ApplicationComponent", null, "Application", null, null),
                ElementDto.standard("2", "B", "ApplicationComponent", null, "Application", null, null),
                ElementDto.standard("3", "C", "BusinessProcess", null, "Business", null, null),
                ElementDto.standard("4", "D", "BusinessProcess", null, "Business", null, null),
                ElementDto.standard("5", "E", "Node", null, "Technology", null, null),
                ElementDto.standard("6", "F", "Stakeholder", null, "Motivation", null, null),
                ElementDto.standard("7", "G", "Driver", null, "Motivation", null, null));
        String result = SummaryFormatter.summarizeElements(elements, null);
        assertTrue(result.contains("Found 7 elements"));
        // Top 3 types shown, rest as "others"
        assertTrue(result.contains("other"));
    }

    // ---- summarizeViews ----

    @Test
    public void summarizeViews_empty_shouldReturnNoViews() {
        assertEquals("No views found in model.", SummaryFormatter.summarizeViews(List.of()));
    }

    @Test
    public void summarizeViews_null_shouldReturnNoViews() {
        assertEquals("No views found in model.", SummaryFormatter.summarizeViews(null));
    }

    @Test
    public void summarizeViews_singleView_shouldShowNameAndViewpoint() {
        ViewDto v = new ViewDto("v1", "Architecture Overview", "Layered", null);
        String result = SummaryFormatter.summarizeViews(List.of(v));
        assertTrue(result.contains("1 view"));
        assertTrue(result.contains("Architecture Overview"));
        assertTrue(result.contains("Layered"));
    }

    @Test
    public void summarizeViews_multipleViews_shouldShowViewpointDistribution() {
        List<ViewDto> views = List.of(
                new ViewDto("v1", "View 1", "Layered", null),
                new ViewDto("v2", "View 2", "Layered", null),
                new ViewDto("v3", "View 3", "Application Usage", null));
        String result = SummaryFormatter.summarizeViews(views);
        assertTrue(result.contains("3 views"));
        assertTrue(result.contains("2 Layered"));
    }

    // ---- summarizeViewContents ----

    @Test
    public void summarizeViewContents_null_shouldReturnNoContents() {
        assertEquals("No view contents available.", SummaryFormatter.summarizeViewContents(null));
    }

    @Test
    public void summarizeViewContents_shouldShowCountsAndDistributions() {
        List<ElementDto> elements = List.of(
                ElementDto.standard("e1", "Portal", "ApplicationComponent", null, "Application", null, null),
                ElementDto.standard("e2", "Auth", "ApplicationService", null, "Application", null, null));
        List<RelationshipDto> rels = List.of(
                new RelationshipDto("r1", "serves", "ServingRelationship", "e1", "e2"));
        List<ViewConnectionDto> connections = List.of(
                new ViewConnectionDto("vc-1", "r1", "ServingRelationship", "vo-1", "vo-2", List.of()));
        ViewContentsDto contents = new ViewContentsDto("v1", "My View", "Layered",
                elements, rels, null, connections);

        String result = SummaryFormatter.summarizeViewContents(contents);
        assertTrue(result.contains("My View"));
        assertTrue(result.contains("Layered"));
        assertTrue(result.contains("2 elements"));
        assertTrue(result.contains("1 relationship"));
        assertTrue(result.contains("1 connections"));
        assertTrue(result.contains("ApplicationComponent"));
        assertTrue(result.contains("ServingRelationship"));
    }

    @Test
    public void summarizeViewContents_shouldCountGroupingContainers_whenNoNativeGroupExists() {
        // A view built the way the deployment guidance prescribes: containers are ArchiMate
        // Grouping elements, not native view groups. Sizing the count from the groups() bucket
        // reported none of them, so changing one parameter from tree to summary took the agent
        // back to the false zero the tree format no longer gives.
        List<ElementDto> elements = List.of(
                ElementDto.standard("z1", "Ingress Zone", "Grouping", null, "Other", null, null),
                ElementDto.standard("z2", "Data Zone", "Grouping", null, "Other", null, null),
                ElementDto.standard("n1", "Load Balancer", "Node", null, "Technology", null, null));
        List<ViewNodeDto> visualMetadata = List.of(
                new ViewNodeDto("vo-z1", "z1", 40, 40, 260, 180, null),
                new ViewNodeDto("vo-z2", "z2", 340, 40, 260, 180, null),
                new ViewNodeDto("vo-n1", "n1", 30, 60, 120, 55, "vo-z1"));
        ViewContentsDto contents = new ViewContentsDto("v1", "Zones View", null, null,
                elements, List.of(), visualMetadata, List.of(), List.of(), List.of());

        String result = SummaryFormatter.summarizeViewContents(contents);

        assertTrue("summary must report the Grouping containers: " + result,
                result.contains("Containers: 2 groups"));
    }

    @Test
    public void summarizeViewContents_shouldNotCallAGroupingAVisualAnnotation() {
        // A Grouping is a model concept that participates in relationships. Filing it under the
        // same label as a sticky note would misdescribe it, so containers get their own clause.
        List<ElementDto> elements = List.of(
                ElementDto.standard("z1", "Ingress Zone", "Grouping", null, "Other", null, null));
        List<ViewNodeDto> visualMetadata = List.of(
                new ViewNodeDto("vo-z1", "z1", 40, 40, 260, 180, null));
        ViewContentsDto contents = new ViewContentsDto("v1", "Zones View", null, null,
                elements, List.of(), visualMetadata, List.of(), List.of(), List.of());

        String result = SummaryFormatter.summarizeViewContents(contents);

        assertTrue(result.contains("Containers: 1 group"));
        assertFalse("no notes on this view, so no annotations clause at all: " + result,
                result.contains("Visual annotations"));
    }

    @Test
    public void summarizeViewContents_shouldCountBothContainerKinds_onAMixedView() {
        List<ElementDto> elements = List.of(
                ElementDto.standard("z1", "Zone A", "Grouping", null, "Other", null, null),
                ElementDto.standard("c1", "Portal", "ApplicationComponent", null, "Application", null, null));
        List<ViewNodeDto> visualMetadata = List.of(
                new ViewNodeDto("vo-z1", "z1", 40, 40, 260, 180, null),
                new ViewNodeDto("vo-c1", "c1", 340, 40, 120, 55, null));
        List<ViewGroupDto> groups = List.of(
                new ViewGroupDto("grp-1", "Observability", 500, 40, 250, 160, null, List.of()));
        List<ViewNoteDto> notes = List.of(
                new ViewNoteDto("note-1", "Design note", 40, 300, 150, 40, null));
        ViewContentsDto contents = new ViewContentsDto("v1", "Mixed View", null, null,
                elements, List.of(), visualMetadata, List.of(), groups, notes);

        String result = SummaryFormatter.summarizeViewContents(contents);

        assertTrue("one native group plus one Grouping: " + result,
                result.contains("Containers: 2 groups"));
        assertTrue("the note is still reported, under its own label: " + result,
                result.contains("Visual annotations: 1 note"));
        assertFalse("a populated ApplicationComponent is a host, not a container: " + result,
                result.contains("Containers: 3 groups"));
    }

    @Test
    public void summarizeViewContents_shouldShowZeroConnections_whenNoConnections() {
        ViewContentsDto contents = new ViewContentsDto("v1", "Empty", null,
                List.of(), List.of(), null, List.of());
        String result = SummaryFormatter.summarizeViewContents(contents);
        assertTrue(result.contains("0 connections"));
    }

    // ---- summarizeDepthRelationships ----

    @Test
    public void summarizeDepthRelationships_empty_shouldMentionNoRelationships() {
        ElementDto element = ElementDto.standard("e1", "Portal", null, null, null, null, null);
        String result = SummaryFormatter.summarizeDepthRelationships(List.of(), element);
        assertTrue(result.contains("Portal"));
        assertTrue(result.contains("no relationships"));
    }

    @Test
    public void summarizeDepthRelationships_withRelationships_shouldShowTypeDistributionAndConnectedCount() {
        ElementDto element = ElementDto.standard("e1", "Portal", null, null, null, null, null);
        List<RelationshipDto> rels = List.of(
                new RelationshipDto("r1", null, "ServingRelationship", "e1", "e2"),
                new RelationshipDto("r2", null, "ServingRelationship", "e1", "e3"),
                new RelationshipDto("r3", null, "RealizationRelationship", "e4", "e1"));
        String result = SummaryFormatter.summarizeDepthRelationships(rels, element);
        assertTrue(result.contains("Portal"));
        assertTrue(result.contains("3 relationships"));
        assertTrue(result.contains("ServingRelationship"));
        assertTrue(result.contains("Connected to 3 unique elements"));
    }

    // ---- summarizeTraversal ----

    @Test
    public void summarizeTraversal_null_shouldReturnNoResults() {
        assertEquals("No traversal results.", SummaryFormatter.summarizeTraversal(null, null));
    }

    @Test
    public void summarizeTraversal_shouldShowHopAndElementCounts() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalElementsDiscovered", 5);
        summary.put("totalRelationships", 8);
        summary.put("maxDepthReached", 2);
        summary.put("cyclesDetected", false);

        Map<String, Object> traversalResult = new LinkedHashMap<>();
        traversalResult.put("traversalSummary", summary);

        ElementDto start = ElementDto.standard("e1", "Portal", null, null, null, null, null);
        String result = SummaryFormatter.summarizeTraversal(traversalResult, start);
        assertTrue(result.contains("Portal"));
        assertTrue(result.contains("5 elements"));
        assertTrue(result.contains("2 hops"));
        assertTrue(result.contains("8 relationships"));
        assertFalse(result.contains("Cycles"));
    }

    @Test
    public void summarizeTraversal_withCycles_shouldMentionCycles() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalElementsDiscovered", 3);
        summary.put("totalRelationships", 4);
        summary.put("maxDepthReached", 2);
        summary.put("cyclesDetected", true);

        Map<String, Object> traversalResult = new LinkedHashMap<>();
        traversalResult.put("traversalSummary", summary);

        ElementDto start = ElementDto.standard("e1", "DB", null, null, null, null, null);
        String result = SummaryFormatter.summarizeTraversal(traversalResult, start);
        assertTrue(result.contains("Cycles detected"));
    }

    @Test
    public void summarizeTraversal_noSummary_shouldReturnFallback() {
        Map<String, Object> traversalResult = new LinkedHashMap<>();
        // no traversalSummary key

        ElementDto start = ElementDto.standard("e1", "Portal", null, null, null, null, null);
        String result = SummaryFormatter.summarizeTraversal(traversalResult, start);
        assertTrue(result.contains("Portal"));
        assertTrue(result.contains("no summary"));
    }
}
