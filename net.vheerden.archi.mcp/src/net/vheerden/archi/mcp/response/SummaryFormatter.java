package net.vheerden.archi.mcp.response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Generates natural language summaries from query results (Story 6.3, FR22).
 *
 * <p>Used by query handlers when {@code format=summary} is requested.
 * Summaries are concise (1-3 sentences) and actionable — they include
 * information that helps the LLM plan next queries.</p>
 *
 * <p>Thread-safe: all methods are stateless and static.</p>
 */
public final class SummaryFormatter {

    private SummaryFormatter() {} // utility class

    /**
     * Summarizes search results with type and layer distribution.
     *
     * @param elements the full result set (before pagination)
     * @param searchContext the search query (e.g., "auth"), or null
     * @return natural language summary string
     */
    public static String summarizeElements(List<ElementDto> elements, String searchContext) {
        if (elements == null || elements.isEmpty()) {
            if (searchContext != null) {
                return "No elements found matching '" + searchContext + "'.";
            }
            return "No elements found.";
        }

        int count = elements.size();
        String contextPart = (searchContext != null)
                ? " matching '" + searchContext + "'" : "";

        // Type distribution
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        Map<String, Integer> layerCounts = new LinkedHashMap<>();
        for (ElementDto e : elements) {
            if (e.type() != null) {
                typeCounts.merge(e.type(), 1, Integer::sum);
            }
            if (e.layer() != null) {
                layerCounts.merge(e.layer(), 1, Integer::sum);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (count == 1) {
            ElementDto e = elements.get(0);
            sb.append("Found 1 element").append(contextPart).append(": ");
            sb.append("1 ").append(e.type() != null ? e.type() : "unknown type");
            if (e.layer() != null) {
                sb.append(". Layer: ").append(e.layer());
            }
            sb.append(".");
        } else {
            sb.append("Found ").append(count).append(" elements").append(contextPart).append(": ");
            sb.append(formatDistribution(typeCounts));
            sb.append(".");
            if (!layerCounts.isEmpty()) {
                sb.append(" Layers: ").append(formatCountMap(layerCounts)).append(".");
            }
        }
        return sb.toString();
    }

    /**
     * Summarizes view list results with viewpoint distribution (no name context).
     *
     * @param views the full result set (before pagination)
     * @return natural language summary string
     */
    public static String summarizeViews(List<ViewDto> views) {
        return summarizeViews(views, null);
    }

    /**
     * Summarizes view list results with viewpoint distribution.
     *
     * @param views the full result set (before pagination)
     * @param nameContext the name filter used (e.g., "landscape"), or null
     * @return natural language summary string
     */
    public static String summarizeViews(List<ViewDto> views, String nameContext) {
        if (views == null || views.isEmpty()) {
            if (nameContext != null && !nameContext.isEmpty()) {
                return "No views found matching '" + nameContext + "'.";
            }
            return "No views found in model.";
        }

        int count = views.size();
        String contextPart = (nameContext != null && !nameContext.isEmpty())
                ? " matching '" + nameContext + "'" : " in model";

        Map<String, Integer> viewpointCounts = new LinkedHashMap<>();
        for (ViewDto v : views) {
            String vp = v.viewpointType();
            viewpointCounts.merge(vp != null ? vp : "Unspecified", 1, Integer::sum);
        }

        if (count == 1) {
            ViewDto v = views.get(0);
            return "1 view" + contextPart + ": '" + v.name() + "'"
                    + (v.viewpointType() != null ? " (" + v.viewpointType() + ")" : "") + ".";
        }

        return count + " views" + contextPart + ": " + formatDistribution(viewpointCounts) + ".";
    }

    /**
     * Summarizes view contents with element and relationship counts and distributions.
     *
     * @param viewContents the view contents DTO
     * @return natural language summary string
     */
    public static String summarizeViewContents(ViewContentsDto viewContents) {
        if (viewContents == null) {
            return "No view contents available.";
        }

        String viewName = viewContents.viewName();
        String viewpoint = viewContents.viewpoint();
        int elementCount = viewContents.elements() != null ? viewContents.elements().size() : 0;
        int relCount = viewContents.relationships() != null ? viewContents.relationships().size() : 0;
        int connCount = viewContents.connections() != null ? viewContents.connections().size() : 0;
        int groupCount = viewContents.groups() != null ? viewContents.groups().size() : 0;
        int noteCount = viewContents.notes() != null ? viewContents.notes().size() : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("View '").append(viewName).append("'");
        if (viewpoint != null) {
            sb.append(" (").append(viewpoint).append(")");
        }
        sb.append(": ").append(elementCount).append(" elements, ")
                .append(relCount).append(" relationships, ")
                .append(connCount).append(" connections.");
        if (groupCount > 0 || noteCount > 0) {
            sb.append(" Visual annotations: ");
            List<String> annParts = new ArrayList<>();
            if (groupCount > 0) {
                annParts.add(groupCount + " " + (groupCount == 1 ? "group" : "groups"));
            }
            if (noteCount > 0) {
                annParts.add(noteCount + " " + (noteCount == 1 ? "note" : "notes"));
            }
            sb.append(String.join(", ", annParts)).append(".");
        }

        // Element type distribution
        if (elementCount > 0) {
            Map<String, Integer> typeCounts = new LinkedHashMap<>();
            for (ElementDto e : viewContents.elements()) {
                if (e.type() != null) {
                    typeCounts.merge(e.type(), 1, Integer::sum);
                }
            }
            if (!typeCounts.isEmpty()) {
                sb.append(" Elements: ").append(formatDistribution(typeCounts)).append(".");
            }
        }

        // Relationship type distribution
        if (relCount > 0) {
            Map<String, Integer> relTypeCounts = new LinkedHashMap<>();
            for (RelationshipDto r : viewContents.relationships()) {
                if (r.type() != null) {
                    relTypeCounts.merge(r.type(), 1, Integer::sum);
                }
            }
            if (!relTypeCounts.isEmpty()) {
                sb.append(" Relationships: ").append(formatDistribution(relTypeCounts)).append(".");
            }
        }

        return sb.toString();
    }

    /**
     * Summarizes depth-mode relationship results.
     *
     * @param relationships  the relationships for the queried element
     * @param queriedElement the element whose relationships were queried
     * @return natural language summary string
     */
    public static String summarizeDepthRelationships(
            List<RelationshipDto> relationships, ElementDto queriedElement) {
        if (relationships == null || relationships.isEmpty()) {
            String name = (queriedElement != null) ? queriedElement.name() : "element";
            return "'" + name + "' has no relationships.";
        }

        String name = (queriedElement != null) ? queriedElement.name() : "element";
        String elementId = (queriedElement != null) ? queriedElement.id() : null;
        int count = relationships.size();

        Map<String, Integer> relTypeCounts = new LinkedHashMap<>();
        Set<String> connectedIds = new HashSet<>();
        for (RelationshipDto r : relationships) {
            if (r.type() != null) {
                relTypeCounts.merge(r.type(), 1, Integer::sum);
            }
            // Count unique connected elements
            if (r.sourceId() != null && !r.sourceId().equals(elementId)) {
                connectedIds.add(r.sourceId());
            }
            if (r.targetId() != null && !r.targetId().equals(elementId)) {
                connectedIds.add(r.targetId());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("'").append(name).append("' has ").append(count)
                .append(" relationship").append(count != 1 ? "s" : "").append(": ")
                .append(formatDistribution(relTypeCounts)).append(".");
        if (!connectedIds.isEmpty()) {
            sb.append(" Connected to ").append(connectedIds.size())
                    .append(" unique element").append(connectedIds.size() != 1 ? "s" : "").append(".");
        }
        return sb.toString();
    }

    /**
     * Summarizes traverse-mode results.
     *
     * @param traversalResult the traverse result map (with traversalSummary, hops)
     * @param startElement    the start element of the traversal
     * @return natural language summary string
     */
    @SuppressWarnings("unchecked")
    public static String summarizeTraversal(
            Map<String, Object> traversalResult, ElementDto startElement) {
        if (traversalResult == null) {
            return "No traversal results.";
        }

        String name = (startElement != null) ? startElement.name() : "element";

        Map<String, Object> summary =
                (Map<String, Object>) traversalResult.get("traversalSummary");
        if (summary == null) {
            return "Traversal from '" + name + "': no summary available.";
        }

        int totalElements = ((Number) summary.get("totalElementsDiscovered")).intValue();
        int totalRels = ((Number) summary.get("totalRelationships")).intValue();
        int maxDepth = ((Number) summary.get("maxDepthReached")).intValue();
        boolean cycles = (Boolean) summary.get("cyclesDetected");

        StringBuilder sb = new StringBuilder();
        sb.append("Traversal from '").append(name).append("': ");
        sb.append(totalElements).append(" element").append(totalElements != 1 ? "s" : "");
        sb.append(" across ").append(maxDepth).append(" hop").append(maxDepth != 1 ? "s" : "");
        sb.append(", ").append(totalRels).append(" relationship").append(totalRels != 1 ? "s" : "");
        sb.append(".");
        if (cycles) {
            sb.append(" Cycles detected.");
        }

        return sb.toString();
    }

    // ---- Private Helpers ----

    /**
     * Formats a distribution map as "N TypeA, M TypeB, and K others".
     * Shows top 3 entries sorted by count descending, remainder as "N others".
     */
    private static String formatDistribution(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "none";

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());

        int top = Math.min(3, sorted.size());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < top; i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            parts.add(e.getValue() + " " + e.getKey());
        }

        int othersCount = 0;
        for (int i = top; i < sorted.size(); i++) {
            othersCount += sorted.get(i).getValue();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0 && i == parts.size() - 1 && othersCount == 0) {
                sb.append(", and ");
            } else if (i > 0) {
                sb.append(", ");
            }
            sb.append(parts.get(i));
        }
        if (othersCount > 0) {
            sb.append(", and ").append(othersCount).append(" other").append(othersCount != 1 ? "s" : "");
        }

        return sb.toString();
    }

    /**
     * Formats a count map as "Key1 (N), Key2 (M)".
     */
    private static String formatCountMap(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append(", ");
            Map.Entry<String, Integer> e = sorted.get(i);
            sb.append(e.getKey()).append(" (").append(e.getValue()).append(")");
        }
        return sb.toString();
    }
}
