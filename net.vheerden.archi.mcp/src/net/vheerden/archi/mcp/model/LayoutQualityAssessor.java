package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;
import net.vheerden.archi.mcp.model.routing.CoincidentSegmentDetector;

/**
 * Stateless pure-geometry computation for layout quality assessment (Story 9-2).
 * No EMF imports — operates on {@link AssessmentNode} and {@link AssessmentConnection} records.
 *
 * <p>All coordinates are expected to be in absolute canvas space. The accessor is
 * responsible for converting nested element coordinates by accumulating parent offsets.</p>
 *
 * <p>Ancestor-descendant containment relationships (groups containing elements,
 * including nested groups) are handled specially: overlap, spacing, and alignment
 * metrics exclude containment pairs to avoid false positives from intentional nesting.</p>
 */
class LayoutQualityAssessor {

    private static final int MAX_DESCRIPTIONS = 10;
    private static final double ALIGNMENT_TOLERANCE = 5.0;
    private static final double OFF_CANVAS_THRESHOLD = 10000.0;

    // Suggestion thresholds (Finding #11: named constants with documented rationale)
    /** Edge crossings above this count trigger a suggestion to use hierarchical layout. */
    static final int CROSSING_SUGGESTION_THRESHOLD = 10;
    /** Average spacing below this (px) triggers a "too tight" suggestion. */
    static final double SPACING_SUGGESTION_THRESHOLD = 15.0;
    /** Alignment score below this triggers a "poor alignment" suggestion. */
    static final int ALIGNMENT_SUGGESTION_THRESHOLD = 30;

    // Overall rating thresholds
    static final int EXCELLENT_MAX_CROSSINGS = 5;
    static final double EXCELLENT_MIN_SPACING = 30.0;
    static final int EXCELLENT_MIN_ALIGNMENT = 60;
    static final int GOOD_MAX_CROSSINGS = 20;
    static final double GOOD_MIN_SPACING = 15.0;
    static final int GOOD_MIN_ALIGNMENT = 30;
    static final int FAIR_MAX_OVERLAPS = 3;
    static final int FAIR_MAX_CROSSINGS = 30;
    static final int FAIR_MAX_PASS_THROUGHS = 3;

    // Density-aware crossing thresholds (Story 11-12, 11-22)
    /** Crossings per connection ratio: moderate impact threshold. */
    static final double CROSSING_RATIO_MODERATE = 4.0;
    /** Story 11-22: crossings/connection ratio below this is "good" quality. */
    static final double CROSSING_RATIO_GOOD = 1.5;

    /**
     * Inset (px) applied to obstacle rectangles before pass-through intersection tests.
     * Accounts for OrthogonalAnchor corner-arc imprecision in diagonal exit zones
     * where the simplified ChopboxAnchor fallback deviates from Archi's actual
     * corner arc calculation (using COSPI4). Typical deviation: 10-15px.
     */
    static final double PASS_THROUGH_INSET = 10.0;

    /** Above this element count, add a performance warning to suggestions. */
    static final int LARGE_VIEW_WARNING_THRESHOLD = 500;

    private final CoincidentSegmentDetector coincidentDetector;

    LayoutQualityAssessor() {
        this.coincidentDetector = new CoincidentSegmentDetector();
    }

    /**
     * Runs full layout quality assessment on the given nodes and connections.
     */
    LayoutAssessmentResult assess(List<AssessmentNode> nodes,
                                   List<AssessmentConnection> connections) {
        // Story 11-15: Separate notes from layout nodes.
        // Notes are excluded from all scoring metrics but used for informational overlap detection.
        List<AssessmentNode> layoutNodes = new ArrayList<>();
        List<AssessmentNode> noteNodes = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (node.isNote()) {
                noteNodes.add(node);
            } else {
                layoutNodes.add(node);
            }
        }

        // Build transitive containment set for exclusions (Story 9-0d: transitive closure)
        Set<String> containmentPairs = buildContainmentPairs(layoutNodes);

        // Single-pass overlap detection: sibling + containment counts (notes excluded)
        OverlapResult overlapResult = computeOverlaps(layoutNodes, containmentPairs);
        int crossingCount = countEdgeCrossings(connections);
        double avgSpacing = computeAverageSpacing(layoutNodes, containmentPairs);
        int alignment = computeAlignmentScore(layoutNodes);
        // Label overlap detection (Story 10-8) — must precede rating/suggestions
        LabelOverlapResult labelResult = countLabelOverlaps(connections, layoutNodes);
        List<String> boundaryViolations = detectBoundaryViolations(layoutNodes);
        List<String> passThroughs = detectPassThroughs(connections, layoutNodes);
        // Story 11-12: count groups for group-aware suggestions
        boolean hasGroups = false;
        for (AssessmentNode node : layoutNodes) {
            if (node.isGroup()) {
                hasGroups = true;
                break;
            }
        }
        // Story 11-23: Coincident segment detection
        int coincidentSegmentCount = coincidentDetector.countCoincidentSegments(connections);

        // Rating and suggestions use sibling overlaps only (Story 9-0d)
        // Story 11-19: use breakdown-aware rating with grouped-view leniency
        RatingResult ratingResult = computeRatingWithBreakdown(
                overlapResult.siblingCount(), crossingCount, avgSpacing, alignment,
                labelResult.count(), passThroughs.size(), connections.size(), hasGroups);
        String rating = ratingResult.rating();
        Map<String, String> ratingBreakdown = ratingResult.breakdown();
        List<String> offCanvas = detectOffCanvas(layoutNodes);
        List<String> suggestions = generateSuggestions(
                overlapResult.siblingCount(), crossingCount, avgSpacing, alignment,
                boundaryViolations.size(), offCanvas.size(), layoutNodes.size(),
                labelResult.count(), hasGroups, connections.size(), coincidentSegmentCount,
                labelResult.shortSegmentCount());

        // Story 11-12: density-aware crossing metric
        double crossingsPerConnection = connections.size() > 0
                ? (double) crossingCount / connections.size() : 0.0;

        // Story 11-15: informational note-overlap detection (notes vs layout nodes)
        NoteOverlapResult noteOverlapResult = countNoteOverlaps(noteNodes, layoutNodes);

        // Story 11-29: Compute bounding box of ALL visual content (elements + groups + notes)
        ContentBounds contentBounds = computeContentBounds(nodes);

        // Orphan detection is done at EMF level in ArchiModelAccessorImpl, not here.
        // Pass 0/empty — the accessor merges orphan data into the DTO directly.
        return new LayoutAssessmentResult(
                overlapResult.siblingCount(), overlapResult.containmentCount(),
                crossingCount, avgSpacing, alignment, rating, ratingBreakdown,
                overlapResult.siblingDescriptions(), boundaryViolations, passThroughs,
                offCanvas, labelResult.count(), labelResult.descriptions(),
                0, List.of(), connections.size(), crossingsPerConnection,
                noteOverlapResult.count(), noteOverlapResult.descriptions(),
                hasGroups, coincidentSegmentCount, contentBounds, suggestions);
    }

    // ---- Containment relationship helpers (Story 9-0d: transitive closure) ----

    /**
     * Builds a set of ALL ancestor-descendant pairs (transitive closure) for
     * fast containment lookup. For each node, walks up the parentId chain and
     * adds pairs for EVERY ancestor, not just the direct parent.
     *
     * <p>Example: TopGroup → SubGroup → Element produces pairs:
     * "TopGroup:SubGroup", "SubGroup:Element", AND "TopGroup:Element".</p>
     */
    private Set<String> buildContainmentPairs(List<AssessmentNode> nodes) {
        Map<String, AssessmentNode> nodeMap = new HashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        Set<String> pairs = new HashSet<>();
        for (AssessmentNode node : nodes) {
            if (node.parentId() != null) {
                // Walk up the ancestor chain and add ALL ancestor:descendant pairs
                String descendantId = node.id();
                AssessmentNode current = nodeMap.get(node.parentId());
                while (current != null) {
                    pairs.add(current.id() + ":" + descendantId);
                    if (current.parentId() == null) break;
                    current = nodeMap.get(current.parentId());
                }
            }
        }
        return pairs;
    }

    private boolean isContainmentPair(AssessmentNode a, AssessmentNode b,
                                       Set<String> containmentPairs) {
        return containmentPairs.contains(a.id() + ":" + b.id())
                || containmentPairs.contains(b.id() + ":" + a.id());
    }

    /**
     * Collects all descendant IDs for a given node (children, grandchildren, etc.).
     */
    private Set<String> getDescendantIds(String nodeId, List<AssessmentNode> nodes) {
        Set<String> descendants = new HashSet<>();
        // Seed with direct children, then iteratively expand
        Set<String> frontier = new HashSet<>();
        frontier.add(nodeId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (AssessmentNode n : nodes) {
                if (n.parentId() != null && frontier.contains(n.parentId())
                        && descendants.add(n.id())) {
                    frontier.add(n.id());
                    changed = true;
                }
            }
        }
        return descendants;
    }

    /**
     * Collects all ancestor IDs for a given node by walking the parentId chain.
     */
    private Set<String> getAncestorIds(String nodeId,
                                        Map<String, AssessmentNode> nodeMap) {
        Set<String> ancestors = new HashSet<>();
        AssessmentNode current = nodeMap.get(nodeId);
        while (current != null && current.parentId() != null) {
            ancestors.add(current.parentId());
            current = nodeMap.get(current.parentId());
        }
        return ancestors;
    }

    // ---- Overlap Detection (Finding #2: exclude containment, #10: single pass, Story 9-0d: transitive) ----

    /** Combined sibling + containment counts and descriptions from a single pass. */
    record OverlapResult(int siblingCount, int containmentCount,
                         List<String> siblingDescriptions) {}

    OverlapResult computeOverlaps(List<AssessmentNode> nodes,
                                   Set<String> containmentPairs) {
        int siblingCount = 0;
        int containmentCount = 0;
        List<String> siblingDescriptions = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                AssessmentNode a = nodes.get(i);
                AssessmentNode b = nodes.get(j);
                if (!rectanglesOverlap(a, b)) {
                    continue;
                }
                // Containment pairs overlap by design — count separately
                if (isContainmentPair(a, b, containmentPairs)) {
                    containmentCount++;
                } else if (Objects.equals(a.parentId(), b.parentId())) {
                    // Story 11-26: Only count overlaps between siblings (same parent).
                    // Elements in different groups near a shared boundary are NOT
                    // sibling overlaps — they are cross-group boundary proximity.
                    siblingCount++;
                    if (siblingDescriptions.size() < MAX_DESCRIPTIONS) {
                        siblingDescriptions.add("Element '" + a.id()
                                + "' overlaps with element '" + b.id() + "'");
                    }
                }
            }
        }
        return new OverlapResult(siblingCount, containmentCount, siblingDescriptions);
    }

    private boolean rectanglesOverlap(AssessmentNode a, AssessmentNode b) {
        return a.x() < b.x() + b.width()
                && a.x() + a.width() > b.x()
                && a.y() < b.y() + b.height()
                && a.y() + a.height() > b.y();
    }

    // ---- Edge Crossing Detection (Finding #8: remove sharesEndpoint skip) ----

    int countEdgeCrossings(List<AssessmentConnection> connections) {
        int count = 0;
        for (int i = 0; i < connections.size(); i++) {
            for (int j = i + 1; j < connections.size(); j++) {
                // No sharesEndpoint skip — segmentsIntersect already returns false
                // for segments sharing an endpoint vertex (cross-product = 0).
                // Removing the skip allows detection of real crossings between
                // connections that share a source/target but cross elsewhere.
                count += countSegmentCrossings(
                        connections.get(i).pathPoints(),
                        connections.get(j).pathPoints());
            }
        }
        return count;
    }

    private int countSegmentCrossings(List<double[]> path1, List<double[]> path2) {
        int count = 0;
        for (int i = 0; i < path1.size() - 1; i++) {
            for (int j = 0; j < path2.size() - 1; j++) {
                if (segmentsIntersect(
                        path1.get(i)[0], path1.get(i)[1],
                        path1.get(i + 1)[0], path1.get(i + 1)[1],
                        path2.get(j)[0], path2.get(j)[1],
                        path2.get(j + 1)[0], path2.get(j + 1)[1])) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Line segment intersection test using cross-product orientation.
     * Returns true if segment P1-P2 properly intersects segment P3-P4.
     * Segments sharing an endpoint (P1=P3, etc.) produce a zero cross-product
     * and correctly return false.
     */
    static boolean segmentsIntersect(double p1x, double p1y, double p2x, double p2y,
                                      double p3x, double p3y, double p4x, double p4y) {
        double d1 = crossProduct(p3x, p3y, p4x, p4y, p1x, p1y);
        double d2 = crossProduct(p3x, p3y, p4x, p4y, p2x, p2y);
        double d3 = crossProduct(p1x, p1y, p2x, p2y, p3x, p3y);
        double d4 = crossProduct(p1x, p1y, p2x, p2y, p4x, p4y);

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }

        // Collinear cases — not counted as crossings
        return false;
    }

    /**
     * Cross product of vectors (bx-ax, by-ay) and (cx-ax, cy-ay).
     */
    private static double crossProduct(double ax, double ay, double bx, double by,
                                        double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    // ---- Average Spacing (Finding #4: exclude containment pairs) ----

    double computeAverageSpacing(List<AssessmentNode> nodes,
                                  Set<String> containmentPairs) {
        if (nodes.size() < 2) {
            return 0.0;
        }

        double totalMinGap = 0.0;
        int counted = 0;
        for (int i = 0; i < nodes.size(); i++) {
            double minGap = Double.MAX_VALUE;
            boolean hasNeighbor = false;
            for (int j = 0; j < nodes.size(); j++) {
                if (i == j) continue;
                // Skip containment pairs for spacing computation
                if (isContainmentPair(nodes.get(i), nodes.get(j), containmentPairs)) {
                    continue;
                }
                double gap = edgeToEdgeDistance(nodes.get(i), nodes.get(j));
                if (gap < minGap) {
                    minGap = gap;
                    hasNeighbor = true;
                }
            }
            if (hasNeighbor) {
                totalMinGap += minGap;
                counted++;
            }
        }
        return counted > 0 ? totalMinGap / counted : 0.0;
    }

    /**
     * Computes the minimum edge-to-edge distance between two axis-aligned rectangles.
     * Returns 0 if they overlap.
     */
    private double edgeToEdgeDistance(AssessmentNode a, AssessmentNode b) {
        double dx = Math.max(0, Math.max(b.x() - (a.x() + a.width()),
                a.x() - (b.x() + b.width())));
        double dy = Math.max(0, Math.max(b.y() - (a.y() + a.height()),
                a.y() - (b.y() + b.height())));

        if (dx == 0 && dy == 0) {
            return 0; // overlapping
        }
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ---- Alignment Score (Finding #9: exclude groups, #12: 0 for empty) ----

    int computeAlignmentScore(List<AssessmentNode> nodes) {
        // Filter to non-group (leaf) elements only for alignment scoring
        List<AssessmentNode> leafNodes = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (!node.isGroup()) {
                leafNodes.add(node);
            }
        }

        // Finding #12: return 0 for empty/single — no alignment data, not "perfect"
        if (leafNodes.size() < 2) {
            return 0;
        }

        Set<String> alignedElements = new HashSet<>();

        // Check left-edge alignment
        findAlignedGroups(leafNodes, n -> n.x(), alignedElements);
        // Check center-x alignment
        findAlignedGroups(leafNodes, n -> n.x() + n.width() / 2, alignedElements);
        // Check top-edge alignment
        findAlignedGroups(leafNodes, n -> n.y(), alignedElements);
        // Check center-y alignment
        findAlignedGroups(leafNodes, n -> n.y() + n.height() / 2, alignedElements);

        return Math.min(100, (int) ((alignedElements.size() * 100.0) / leafNodes.size()));
    }

    @FunctionalInterface
    interface CoordinateExtractor {
        double extract(AssessmentNode node);
    }

    private void findAlignedGroups(List<AssessmentNode> nodes,
                                    CoordinateExtractor extractor,
                                    Set<String> alignedElements) {
        for (int i = 0; i < nodes.size(); i++) {
            double coord = extractor.extract(nodes.get(i));
            for (int j = i + 1; j < nodes.size(); j++) {
                double otherCoord = extractor.extract(nodes.get(j));
                if (Math.abs(coord - otherCoord) <= ALIGNMENT_TOLERANCE) {
                    alignedElements.add(nodes.get(i).id());
                    alignedElements.add(nodes.get(j).id());
                }
            }
        }
    }

    // ---- Rating Comparison Utilities (Story 11-16) ----

    /**
     * Returns the ordinal value of a rating for comparison purposes.
     * Higher is better: excellent=4, good=3, fair=2, poor=1, not-applicable=0.
     */
    static int ratingOrdinal(String rating) {
        return switch (rating) {
            case "excellent" -> 4;
            case "good" -> 3;
            case "fair" -> 2;
            case "poor" -> 1;
            default -> 0; // "not-applicable" or unknown
        };
    }

    /**
     * Returns true if the achieved rating meets or exceeds the target rating.
     */
    static boolean meetsTarget(String achieved, String target) {
        return ratingOrdinal(achieved) >= ratingOrdinal(target);
    }

    // ---- Overall Rating (Finding #11: named constants) ----

    /** Result of rating computation including per-metric breakdown (Story 11-19). */
    record RatingResult(String rating, Map<String, String> breakdown) {}

    /**
     * Computes the overall quality rating with per-metric breakdown (Story 11-19).
     * Delegates to the breakdown-aware overload with {@code hasGroups=false}.
     *
     * @deprecated Use {@link #computeRatingWithBreakdown} to get both the rating
     *             and per-metric breakdown, and to enable grouped-view leniency.
     */
    @Deprecated
    String computeOverallRating(int overlaps, int crossings,
                                 double avgSpacing, int alignmentScore,
                                 int labelOverlapCount, int passThroughCount,
                                 int connectionCount) {
        return computeRatingWithBreakdown(overlaps, crossings, avgSpacing,
                alignmentScore, labelOverlapCount, passThroughCount,
                connectionCount, false).rating();
    }

    /**
     * Computes the overall quality rating with per-metric breakdown (Story 11-19).
     *
     * <p>Each metric contributes an individual rating: "pass" (no issue),
     * "excellent", "good", "fair", or "poor". The overall rating is the
     * worst individual rating, with a grouped-view bonus for edge crossings.</p>
     *
     * @param hasGroups when true and edge crossings are the ONLY quality issue,
     *                  the crossing contribution is capped at "good" (not "fair")
     */
    RatingResult computeRatingWithBreakdown(int overlaps, int crossings,
                                             double avgSpacing, int alignmentScore,
                                             int labelOverlapCount, int passThroughCount,
                                             int connectionCount, boolean hasGroups) {
        Map<String, String> breakdown = new LinkedHashMap<>();

        // 1. Overlaps rating
        if (overlaps == 0) {
            breakdown.put("overlaps", "pass");
        } else if (overlaps <= FAIR_MAX_OVERLAPS) {
            breakdown.put("overlaps", "fair");
        } else {
            breakdown.put("overlaps", "poor");
        }

        // 2. Edge crossings rating (density-aware — Stories 11-12, 11-22)
        double crossingRatio = connectionCount > 0
                ? (double) crossings / connectionCount : crossings;
        String crossingRating;
        if (crossings < EXCELLENT_MAX_CROSSINGS) {
            crossingRating = "pass";
        } else if (crossings < GOOD_MAX_CROSSINGS) {
            crossingRating = "good";
        } else if (connectionCount > 0 && crossingRatio <= CROSSING_RATIO_GOOD) {
            // Story 11-22: views with low density (≤1.5 crossings/conn) rate "good"
            // even when absolute count exceeds GOOD_MAX_CROSSINGS
            crossingRating = "good";
        } else if (connectionCount > 0 && crossingRatio <= CROSSING_RATIO_MODERATE) {
            crossingRating = "fair";
        } else if (crossings < FAIR_MAX_CROSSINGS) {
            crossingRating = "fair";
        } else {
            crossingRating = "poor";
        }
        // Story 11-22: grouped-view leniency — one-tier boost (not unconditional floor).
        // Cross-group connections produce topologically unavoidable crossings.
        if (hasGroups && overlaps == 0 && passThroughCount == 0
                && labelOverlapCount == 0 && alignmentScore > GOOD_MIN_ALIGNMENT
                && avgSpacing > GOOD_MIN_SPACING
                && ("fair".equals(crossingRating) || "poor".equals(crossingRating))) {
            crossingRating = "poor".equals(crossingRating) ? "fair" : "good";
        }
        breakdown.put("edgeCrossings", crossingRating);

        // 3. Spacing rating
        if (avgSpacing > EXCELLENT_MIN_SPACING) {
            breakdown.put("spacing", "pass");
        } else if (avgSpacing > GOOD_MIN_SPACING) {
            breakdown.put("spacing", "good");
        } else {
            breakdown.put("spacing", "fair");
        }

        // 4. Alignment rating
        if (alignmentScore > EXCELLENT_MIN_ALIGNMENT) {
            breakdown.put("alignment", "pass");
        } else if (alignmentScore > GOOD_MIN_ALIGNMENT) {
            breakdown.put("alignment", "good");
        } else {
            breakdown.put("alignment", "fair");
        }

        // 5. Label overlaps rating
        if (labelOverlapCount == 0) {
            breakdown.put("labelOverlaps", "pass");
        } else if (labelOverlapCount <= 2) {
            breakdown.put("labelOverlaps", "good");
        } else {
            breakdown.put("labelOverlaps", "fair");
        }

        // 6. Pass-throughs rating
        // Any pass-throughs block excellent/good — they indicate connections crossing elements
        if (passThroughCount == 0) {
            breakdown.put("passThroughs", "pass");
        } else if (passThroughCount <= FAIR_MAX_PASS_THROUGHS) {
            breakdown.put("passThroughs", "fair");
        } else {
            breakdown.put("passThroughs", "poor");
        }

        // Compute overall rating as worst individual contribution
        String overall = computeWorstRating(breakdown);
        breakdown.put("overall", overall);

        return new RatingResult(overall, breakdown);
    }

    /** Returns the worst rating from a breakdown map (ignoring the "overall" key). */
    private String computeWorstRating(Map<String, String> breakdown) {
        int worstLevel = 0; // 0=excellent/pass, 1=good, 2=fair, 3=poor
        for (Map.Entry<String, String> entry : breakdown.entrySet()) {
            if ("overall".equals(entry.getKey())) continue;
            int level = ratingLevel(entry.getValue());
            if (level > worstLevel) {
                worstLevel = level;
            }
        }
        return switch (worstLevel) {
            case 0 -> "excellent";
            case 1 -> "good";
            case 2 -> "fair";
            default -> "poor";
        };
    }

    private int ratingLevel(String rating) {
        return switch (rating) {
            case "pass", "excellent" -> 0;
            case "good" -> 1;
            case "fair" -> 2;
            case "poor" -> 3;
            default -> 2; // unknown ratings treated conservatively as "fair"
        };
    }

    // ---- Boundary Violation Detection ----

    List<String> detectBoundaryViolations(List<AssessmentNode> nodes) {
        Map<String, AssessmentNode> nodeMap = new HashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        List<String> violations = new ArrayList<>();
        for (AssessmentNode child : nodes) {
            if (child.parentId() == null) continue;

            AssessmentNode parent = nodeMap.get(child.parentId());
            if (parent == null) continue;

            // Both child and parent are in absolute coordinates,
            // so direct comparison is valid
            if (child.x() < parent.x()
                    || child.y() < parent.y()
                    || child.x() + child.width() > parent.x() + parent.width()
                    || child.y() + child.height() > parent.y() + parent.height()) {
                violations.add("Element '" + child.id()
                        + "' extends outside parent group '" + parent.id() + "'");
            }

            if (violations.size() >= MAX_DESCRIPTIONS) break;
        }
        return violations;
    }

    // ---- Connection Pass-Through Detection (Finding #3: exclude ancestor groups) ----

    List<String> detectPassThroughs(List<AssessmentConnection> connections,
                                     List<AssessmentNode> nodes) {
        // Build node map for ancestor lookups
        Map<String, AssessmentNode> nodeMap = new HashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        List<String> descriptions = new ArrayList<>();

        for (AssessmentConnection conn : connections) {
            if (descriptions.size() >= MAX_DESCRIPTIONS) break;

            // Collect IDs to exclude: source, target, ancestors, and ALL descendants of source/target
            Set<String> excludeIds = new HashSet<>();
            excludeIds.add(conn.sourceNodeId());
            excludeIds.add(conn.targetNodeId());
            excludeIds.addAll(getAncestorIds(conn.sourceNodeId(), nodeMap));
            excludeIds.addAll(getAncestorIds(conn.targetNodeId(), nodeMap));
            // Exclude all descendants of source/target — connections from a parent element
            // naturally pass through contained children/grandchildren; not a real pass-through
            excludeIds.addAll(getDescendantIds(conn.sourceNodeId(), nodes));
            excludeIds.addAll(getDescendantIds(conn.targetNodeId(), nodes));

            // Clip path from element centers to element edges (visual fidelity)
            List<double[]> clippedPath = clipPathToVisualEdges(
                    conn.pathPoints(),
                    nodeMap.get(conn.sourceNodeId()),
                    nodeMap.get(conn.targetNodeId()));

            for (AssessmentNode node : nodes) {
                if (descriptions.size() >= MAX_DESCRIPTIONS) break;

                // Skip source, target, ancestors, descendants, and groups (transparent containers, Story 10-22)
                if (excludeIds.contains(node.id()) || node.isGroup()) {
                    continue;
                }

                if (pathPassesThroughNode(clippedPath, node)) {
                    descriptions.add("Connection '" + conn.id()
                            + "' passes through element '" + node.id() + "'");
                    break; // Only report each connection once per element
                }
            }
        }
        return descriptions;
    }

    private boolean pathPassesThroughNode(List<double[]> path, AssessmentNode node) {
        // Shrink obstacle rect by PASS_THROUGH_INSET to absorb corner-arc imprecision
        double insetX = node.x() + PASS_THROUGH_INSET;
        double insetY = node.y() + PASS_THROUGH_INSET;
        double insetW = node.width() - 2 * PASS_THROUGH_INSET;
        double insetH = node.height() - 2 * PASS_THROUGH_INSET;
        if (insetW <= 0 || insetH <= 0) return false; // Element too small after inset

        for (int i = 0; i < path.size() - 1; i++) {
            if (lineSegmentIntersectsRect(
                    path.get(i)[0], path.get(i)[1],
                    path.get(i + 1)[0], path.get(i + 1)[1],
                    insetX, insetY, insetW, insetH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clips path endpoints from element centers to element perimeters.
     *
     * <p>Archi uses OrthogonalAnchor (default) which projects the reference point's
     * coordinate onto the nearest edge — fundamentally different from ChopboxAnchor's
     * ray-intersection approach. BendpointConnectionRouter uses the first bendpoint
     * as reference for the source anchor and the last bendpoint for the target anchor;
     * without bendpoints it falls back to the opposite endpoint's center.</p>
     */
    List<double[]> clipPathToVisualEdges(List<double[]> path,
                                          AssessmentNode srcNode,
                                          AssessmentNode tgtNode) {
        if (path.size() < 2) return path;

        List<double[]> clipped = new ArrayList<>(path);
        int last = path.size() - 1;

        // Reference for source: first bendpoint if exists, else target center
        double[] srcRef = path.size() > 2 ? path.get(1) : path.get(last);
        // Reference for target: last bendpoint if exists, else source center
        double[] tgtRef = path.size() > 2 ? path.get(last - 1) : path.get(0);

        if (srcNode != null) {
            double[] exit = orthogonalExitPoint(
                    srcNode.x(), srcNode.y(), srcNode.width(), srcNode.height(),
                    srcRef[0], srcRef[1]);
            if (exit != null) {
                clipped.set(0, exit);
            }
        }

        if (tgtNode != null) {
            double[] entry = orthogonalExitPoint(
                    tgtNode.x(), tgtNode.y(), tgtNode.width(), tgtNode.height(),
                    tgtRef[0], tgtRef[1]);
            if (entry != null) {
                clipped.set(last, entry);
            }
        }

        return clipped;
    }

    /**
     * Computes the perimeter exit point using Archi's OrthogonalAnchor model.
     *
     * <p>If the reference point's x or y falls within the element bounds,
     * the exit projects that coordinate onto the nearest edge (orthogonal exit).
     * For diagonal references (both x and y outside bounds), falls back to
     * ChopboxAnchor-style ray intersection since both anchors produce similar
     * results in corner zones.</p>
     */
    double[] orthogonalExitPoint(double rx, double ry, double rw, double rh,
                                  double refX, double refY) {
        double left = rx, right = rx + rw, top = ry, bottom = ry + rh;
        double cx = rx + rw / 2, cy = ry + rh / 2;

        boolean xInside = refX >= left && refX <= right;
        boolean yInside = refY >= top && refY <= bottom;

        if (xInside && !yInside) {
            // Reference directly above or below — exit from top/bottom edge at ref.x
            return new double[]{refX, refY < top ? top : bottom};
        } else if (!xInside && yInside) {
            // Reference directly left or right — exit from left/right edge at ref.y
            return new double[]{refX < left ? left : right, refY};
        } else if (!xInside) {
            // Diagonal — use ray intersection from center toward reference (ChopboxAnchor fallback)
            return rectExitPoint(cx, cy, refX, refY, rx, ry, rw, rh);
        }
        // Reference inside element — return center (degenerate case)
        return new double[]{cx, cy};
    }

    /**
     * Finds where a ray from (x1,y1) toward (x2,y2) exits the given rectangle.
     * Assumes (x1,y1) is inside the rectangle. Returns the exit point,
     * or null if the ray is degenerate (zero length).
     * Used as fallback for diagonal OrthogonalAnchor zones.
     */
    double[] rectExitPoint(double x1, double y1, double x2, double y2,
                            double rx, double ry, double rw, double rh) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (Math.abs(dx) < 1e-10 && Math.abs(dy) < 1e-10) return null;

        double tExit = Double.MAX_VALUE;

        if (Math.abs(dx) > 1e-10) {
            // Right edge
            double t = (rx + rw - x1) / dx;
            if (t > 1e-10) {
                double yAt = y1 + t * dy;
                if (yAt >= ry && yAt <= ry + rh && t < tExit) tExit = t;
            }
            // Left edge
            t = (rx - x1) / dx;
            if (t > 1e-10) {
                double yAt = y1 + t * dy;
                if (yAt >= ry && yAt <= ry + rh && t < tExit) tExit = t;
            }
        }
        if (Math.abs(dy) > 1e-10) {
            // Bottom edge
            double t = (ry + rh - y1) / dy;
            if (t > 1e-10) {
                double xAt = x1 + t * dx;
                if (xAt >= rx && xAt <= rx + rw && t < tExit) tExit = t;
            }
            // Top edge
            t = (ry - y1) / dy;
            if (t > 1e-10) {
                double xAt = x1 + t * dx;
                if (xAt >= rx && xAt <= rx + rw && t < tExit) tExit = t;
            }
        }

        if (tExit == Double.MAX_VALUE) return null;
        return new double[]{x1 + tExit * dx, y1 + tExit * dy};
    }

    /**
     * Tests if a line segment intersects an axis-aligned rectangle.
     * Delegates to {@link GeometryUtils#lineSegmentIntersectsRect(double, double, double, double, double, double, double, double)}.
     */
    static boolean lineSegmentIntersectsRect(double x1, double y1, double x2, double y2,
                                              double rx, double ry, double rw, double rh) {
        return GeometryUtils.lineSegmentIntersectsRect(x1, y1, x2, y2, rx, ry, rw, rh);
    }

    // ---- Off-Canvas Detection ----

    List<String> detectOffCanvas(List<AssessmentNode> nodes) {
        List<String> warnings = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (warnings.size() >= MAX_DESCRIPTIONS) break;

            if (node.x() < 0 || node.y() < 0) {
                warnings.add("Element '" + node.id()
                        + "' is at negative coordinates (" + (int) node.x()
                        + ", " + (int) node.y() + ")");
            } else if (node.x() > OFF_CANVAS_THRESHOLD || node.y() > OFF_CANVAS_THRESHOLD
                    || node.x() + node.width() > OFF_CANVAS_THRESHOLD
                    || node.y() + node.height() > OFF_CANVAS_THRESHOLD) {
                warnings.add("Element '" + node.id()
                        + "' extends beyond canvas bounds at (" + (int) node.x()
                        + ", " + (int) node.y() + ")");
            }
        }
        return warnings;
    }

    // ---- Label Overlap Detection (Story 10-8) ----

    // Keep in sync with LabelClearance.CHAR_WIDTH etc.
    // (duplicated due to architecture boundary: model vs model.routing)
    /** Estimated character width in pixels (Archi's default ~11pt font). */
    static final double LABEL_CHAR_WIDTH = 7.0;
    /** Estimated character height in pixels. */
    static final double LABEL_CHAR_HEIGHT = 14.0;
    /** Horizontal padding around label text. */
    static final double LABEL_PADDING_X = 10.0;
    /** Vertical padding around label text. */
    static final double LABEL_PADDING_Y = 6.0;
    /** Inset margin applied to label bounds before overlap checks.
     *  Labels must overlap by at least this much on each side to count.
     *  Prevents false positives from estimated bounding boxes barely touching. */
    static final double LABEL_OVERLAP_INSET = 10.0;

    /** Proximity threshold in pixels for label-to-element and label-to-label near-miss detection.
     *  Labels within this distance of an element or another label (but not technically overlapping
     *  after inset) are flagged as proximity issues. (Story 11-24) */
    static final double LABEL_PROXIMITY_THRESHOLD = 5.0;

    record LabelBounds(double x, double y, double width, double height, String connectionId) {}

    record LabelOverlapResult(int count, List<String> descriptions, int shortSegmentCount) {
        /** Backward-compatible constructor without shortSegmentCount. */
        LabelOverlapResult(int count, List<String> descriptions) {
            this(count, descriptions, 0);
        }
    }

    /**
     * Estimates the bounding box of a connection label based on its text position
     * along the path. Position 0=source (15%), 1=middle (50%), 2=target (85%).
     * Returns null if labelText is empty or path has fewer than 2 points.
     */
    LabelBounds estimateLabelBounds(AssessmentConnection conn) {
        String label = conn.labelText();
        if (label == null || label.isEmpty()) {
            return null;
        }
        List<double[]> path = conn.pathPoints();
        if (path.size() < 2) {
            return null;
        }

        double labelWidth = label.length() * LABEL_CHAR_WIDTH + LABEL_PADDING_X;
        double labelHeight = LABEL_CHAR_HEIGHT + LABEL_PADDING_Y;

        // Compute total path length
        double totalLength = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            double dx = path.get(i + 1)[0] - path.get(i)[0];
            double dy = path.get(i + 1)[1] - path.get(i)[1];
            totalLength += Math.sqrt(dx * dx + dy * dy);
        }

        if (totalLength < 1.0) {
            return null;
        }

        // Determine position along path
        double fraction;
        switch (conn.textPosition()) {
            case 0:  fraction = 0.15; break; // source
            case 2:  fraction = 0.85; break; // target
            default: fraction = 0.50; break; // middle (default)
        }

        double targetDist = totalLength * fraction;

        // Walk path to find the point at targetDist
        double accumulated = 0;
        double cx = path.get(0)[0];
        double cy = path.get(0)[1];

        for (int i = 0; i < path.size() - 1; i++) {
            double dx = path.get(i + 1)[0] - path.get(i)[0];
            double dy = path.get(i + 1)[1] - path.get(i)[1];
            double segLen = Math.sqrt(dx * dx + dy * dy);
            if (accumulated + segLen >= targetDist) {
                double remaining = targetDist - accumulated;
                double t = (segLen > 0) ? remaining / segLen : 0;
                cx = path.get(i)[0] + dx * t;
                cy = path.get(i)[1] + dy * t;
                break;
            }
            accumulated += segLen;
        }

        // Center label at the computed point
        return new LabelBounds(
                cx - labelWidth / 2, cy - labelHeight / 2,
                labelWidth, labelHeight, conn.id());
    }

    /**
     * Counts label overlaps: labels overlapping nodes and labels overlapping other labels.
     */
    LabelOverlapResult countLabelOverlaps(List<AssessmentConnection> connections,
                                           List<AssessmentNode> nodes) {
        List<LabelBounds> allLabels = new ArrayList<>();
        for (AssessmentConnection conn : connections) {
            LabelBounds lb = estimateLabelBounds(conn);
            if (lb != null) {
                allLabels.add(lb);
            }
        }

        int count = 0;
        List<String> descriptions = new ArrayList<>();

        // Build node map for ancestor/descendant lookups
        Map<String, AssessmentNode> nodeMap = new HashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        // Build per-connection exclusion sets: source, target, ancestors, descendants
        // (same logic as detectPassThroughs — labels naturally sit within ancestor groups)
        Map<String, Set<String>> connExcludeMap = new HashMap<>();
        for (AssessmentConnection conn : connections) {
            Set<String> excludeIds = new HashSet<>();
            excludeIds.add(conn.sourceNodeId());
            excludeIds.add(conn.targetNodeId());
            excludeIds.addAll(getAncestorIds(conn.sourceNodeId(), nodeMap));
            excludeIds.addAll(getAncestorIds(conn.targetNodeId(), nodeMap));
            excludeIds.addAll(getDescendantIds(conn.sourceNodeId(), nodes));
            excludeIds.addAll(getDescendantIds(conn.targetNodeId(), nodes));
            connExcludeMap.put(conn.id(), excludeIds);
        }

        // Check label-node overlaps and proximity (skip source, target, ancestors, descendants, and groups)
        // Apply inset margin to label bounds to avoid false positives from estimation error
        for (LabelBounds label : allLabels) {
            Set<String> excludeIds = connExcludeMap.getOrDefault(label.connectionId(), Set.of());
            for (AssessmentNode node : nodes) {
                // Skip source, target, ancestors, descendants, and groups (transparent containers)
                if (excludeIds.contains(node.id()) || node.isGroup()) {
                    continue;
                }
                if (insetRectOverlap(label, node.x(), node.y(), node.width(), node.height())) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Label on connection '" + label.connectionId()
                                + "' overlaps element '" + node.id() + "'");
                    }
                } else if (isWithinProximity(label, node.x(), node.y(), node.width(), node.height())) {
                    // Story 11-24: label-to-element proximity detection
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Label on connection '" + label.connectionId()
                                + "' is too close to element '" + node.id() + "'");
                    }
                }
            }
        }

        // Check label-label overlaps and near-misses (apply inset to both labels)
        for (int i = 0; i < allLabels.size(); i++) {
            for (int j = i + 1; j < allLabels.size(); j++) {
                LabelBounds a = allLabels.get(i);
                LabelBounds b = allLabels.get(j);
                if (insetRectOverlap(a, b.x(), b.y(), b.width(), b.height())) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Label on connection '" + a.connectionId()
                                + "' overlaps label on connection '" + b.connectionId() + "'");
                    }
                } else if (isWithinProximity(a, b.x(), b.y(), b.width(), b.height())) {
                    // Story 11-24: label-to-label near-miss detection
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Label on connection '" + a.connectionId()
                                + "' is too close to label on connection '" + b.connectionId() + "'");
                    }
                }
            }
        }

        // Story 11-31: Short-segment detection
        // When a label's hosting segment is shorter than the label width,
        // the label cannot fit regardless of position. Report specific guidance.
        int shortSegmentCount = 0;
        Map<String, AssessmentConnection> connMap = new HashMap<>();
        for (AssessmentConnection conn : connections) {
            connMap.put(conn.id(), conn);
        }
        for (LabelBounds label : allLabels) {
            AssessmentConnection conn = connMap.get(label.connectionId());
            if (conn == null) continue;
            List<double[]> path = conn.pathPoints();
            if (path.size() < 2) continue;

            double labelWidth = label.width();

            // Find the hosting segment (the segment containing the label center point)
            double fraction;
            switch (conn.textPosition()) {
                case 0:  fraction = 0.15; break;
                case 2:  fraction = 0.85; break;
                default: fraction = 0.50; break;
            }

            double totalLength = 0;
            for (int i = 0; i < path.size() - 1; i++) {
                double dx = path.get(i + 1)[0] - path.get(i)[0];
                double dy = path.get(i + 1)[1] - path.get(i)[1];
                totalLength += Math.sqrt(dx * dx + dy * dy);
            }
            double targetDist = totalLength * fraction;

            // Walk path to find hosting segment
            double accumulated = 0;
            for (int i = 0; i < path.size() - 1; i++) {
                double dx = path.get(i + 1)[0] - path.get(i)[0];
                double dy = path.get(i + 1)[1] - path.get(i)[1];
                double segLen = Math.sqrt(dx * dx + dy * dy);
                if (accumulated + segLen >= targetDist || i == path.size() - 2) {
                    // This is the hosting segment
                    boolean isHorizontal = Math.abs(dy) < 2.0;
                    boolean isVertical = Math.abs(dx) < 2.0;

                    if (isHorizontal && segLen < labelWidth) {
                        // Horizontal segment too short for label
                        shortSegmentCount++;
                        if (descriptions.size() < MAX_DESCRIPTIONS) {
                            String srcName = conn.sourceNodeId();
                            String tgtName = conn.targetNodeId();
                            descriptions.add("Label on connection '" + label.connectionId()
                                    + "' exceeds segment length — increase spacing between "
                                    + srcName + " and " + tgtName);
                        }
                    } else if (isVertical) {
                        // Vertical segment: check if all 3 positions produce overlaps
                        // (label optimizer already ran — if we still have an overlap for this connection,
                        // it means all positions were exhausted)
                        Set<String> excludeIds = connExcludeMap.getOrDefault(label.connectionId(), Set.of());
                        boolean hasOverlap = false;
                        for (AssessmentNode node : nodes) {
                            if (excludeIds.contains(node.id()) || node.isGroup()) continue;
                            if (insetRectOverlap(label, node.x(), node.y(), node.width(), node.height())
                                    || isWithinProximity(label, node.x(), node.y(), node.width(), node.height())) {
                                hasOverlap = true;
                                break;
                            }
                        }
                        if (hasOverlap) {
                            if (descriptions.size() < MAX_DESCRIPTIONS) {
                                descriptions.add("Label on connection '" + label.connectionId()
                                        + "' has no clear label position — consider repositioning nearby elements");
                            }
                        }
                    }
                    break;
                }
                accumulated += segLen;
            }
        }

        return new LabelOverlapResult(count, descriptions, shortSegmentCount);
    }

    /**
     * Checks if a label's inset bounding box overlaps another rectangle.
     * The label bounds are shrunk by LABEL_OVERLAP_INSET on each side to
     * avoid false positives from estimated bounding boxes barely touching.
     * The inset is capped at 1/3 of each dimension to prevent the bounds
     * from collapsing to zero (label height is typically only 20px).
     */
    private boolean insetRectOverlap(LabelBounds label,
                                      double x2, double y2, double w2, double h2) {
        double xInset = Math.min(LABEL_OVERLAP_INSET, label.width() / 3);
        double yInset = Math.min(LABEL_OVERLAP_INSET, label.height() / 3);
        double lx = label.x() + xInset;
        double ly = label.y() + yInset;
        double lw = label.width() - 2 * xInset;
        double lh = label.height() - 2 * yInset;
        if (lw <= 0 || lh <= 0) return false;
        return lx < x2 + w2 && lx + lw > x2 && ly < y2 + h2 && ly + lh > y2;
    }

    /**
     * Checks if a label's bounding box is within LABEL_PROXIMITY_THRESHOLD of another rectangle
     * without actually overlapping (after inset). This detects "near-miss" situations where
     * labels are too close to elements or other labels for comfortable reading. (Story 11-24)
     * <p>
     * Expands the target rectangle by the proximity threshold on each side, then checks
     * if the raw (non-inset) label bounds overlap the expanded rectangle.
     */
    private boolean isWithinProximity(LabelBounds label,
                                       double x2, double y2, double w2, double h2) {
        // Expand the target rectangle by the proximity threshold
        double ex = x2 - LABEL_PROXIMITY_THRESHOLD;
        double ey = y2 - LABEL_PROXIMITY_THRESHOLD;
        double ew = w2 + 2 * LABEL_PROXIMITY_THRESHOLD;
        double eh = h2 + 2 * LABEL_PROXIMITY_THRESHOLD;

        // Check if raw label bounds overlap the expanded rectangle
        return label.x() < ex + ew && label.x() + label.width() > ex
                && label.y() < ey + eh && label.y() + label.height() > ey;
    }

    // ---- Note Overlap Detection (Story 11-15: informational, not penalizing) ----

    /** Result of note-overlap detection. Informational only — does not affect rating. */
    record NoteOverlapResult(int count, List<String> descriptions) {}

    // ---- Content bounding box (Story 11-29) ----

    /**
     * Computes the axis-aligned bounding box of all visual content.
     * Includes elements, groups, and notes — everything the user sees on the canvas.
     * Returns {@code null} if there are no nodes.
     */
    private ContentBounds computeContentBounds(List<AssessmentNode> allNodes) {
        if (allNodes.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (AssessmentNode node : allNodes) {
            if (node.x() < minX) minX = node.x();
            if (node.y() < minY) minY = node.y();
            double right = node.x() + node.width();
            double bottom = node.y() + node.height();
            if (right > maxX) maxX = right;
            if (bottom > maxY) maxY = bottom;
        }
        return new ContentBounds(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * Detects overlaps between notes and non-note layout nodes.
     * These are informational only — they do NOT affect the quality rating.
     */
    NoteOverlapResult countNoteOverlaps(List<AssessmentNode> noteNodes,
                                         List<AssessmentNode> layoutNodes) {
        if (noteNodes.isEmpty()) {
            return new NoteOverlapResult(0, List.of());
        }
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        for (AssessmentNode note : noteNodes) {
            for (AssessmentNode element : layoutNodes) {
                // Skip if note is a child of this group (contained notes are expected)
                if (element.isGroup() && note.parentId() != null
                        && note.parentId().equals(element.id())) {
                    continue;
                }
                if (rectanglesOverlap(note, element)) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        String targetType = element.isGroup() ? "group" : "element";
                        descriptions.add("Note '" + note.id()
                                + "' overlaps " + targetType + " '" + element.id() + "'");
                    }
                }
            }
        }
        return new NoteOverlapResult(count, descriptions);
    }

    // ---- Suggestion Generation (Finding #7: performance warning, #11: named constants) ----

    private List<String> generateSuggestions(int overlaps, int crossings,
                                              double avgSpacing, int alignmentScore,
                                              int boundaryViolationCount, int offCanvasCount,
                                              int nodeCount, int labelOverlapCount,
                                              boolean hasGroups, int connectionCount,
                                              int coincidentSegmentCount,
                                              int shortSegmentCount) {
        List<String> suggestions = new ArrayList<>();

        // Finding #7: performance warning for large views
        if (nodeCount > LARGE_VIEW_WARNING_THRESHOLD) {
            suggestions.add("View has " + nodeCount + " elements (>" + LARGE_VIEW_WARNING_THRESHOLD
                    + ") — assessment metrics may be slow for very large views.");
        }

        // Story 11-12/11-22: group-aware suggestions.
        // Groups: suggest layout-within-group + auto-route-connections.
        // Non-grouped (flat or containment): auto-route-connections / auto-layout-and-route.
        // Story 11-22: compute-layout (formerly layout-view) removed from all suggestion paths.
        if (hasGroups) {
            if (overlaps > 0) {
                suggestions.add("Found " + overlaps
                        + " overlapping element pairs — use layout-within-group"
                        + " with increased spacing to spread elements apart,"
                        + " then re-run auto-route-connections");
            }
            if (crossings > CROSSING_SUGGESTION_THRESHOLD) {
                double ratio = connectionCount > 0
                        ? (double) crossings / connectionCount : crossings;
                suggestions.add("Found " + crossings
                        + " edge crossings (" + String.format("%.1f", ratio)
                        + " per connection) — increase element spacing within groups"
                        + " using layout-within-group and re-run auto-route-connections");
            }
            if (avgSpacing < SPACING_SUGGESTION_THRESHOLD && overlaps == 0) {
                suggestions.add("Average spacing is only " + Math.round(avgSpacing)
                        + "px — use layout-within-group with increased spacing"
                        + " for more breathing room, then re-run auto-route-connections");
            }
        } else {
            // Flat or containment view: suggest auto-route / auto-layout-and-route
            if (overlaps > 0) {
                suggestions.add("Found " + overlaps
                        + " overlapping element pairs — use auto-layout-and-route"
                        + " to reposition elements and fix routing");
            }
            if (crossings > CROSSING_SUGGESTION_THRESHOLD) {
                suggestions.add("Found " + crossings
                        + " edge crossings — try auto-route-connections first"
                        + " (preserves positions), then auto-layout-and-route"
                        + " with targetRating if crossings persist");
            }
            if (avgSpacing < SPACING_SUGGESTION_THRESHOLD && overlaps == 0) {
                suggestions.add("Average spacing is only " + Math.round(avgSpacing)
                        + "px — use auto-layout-and-route for better element spacing");
            }
        }
        if (alignmentScore < ALIGNMENT_SUGGESTION_THRESHOLD) {
            if (hasGroups) {
                suggestions.add("Alignment score is " + alignmentScore
                        + "/100 — use layout-within-group to improve alignment within each group");
            } else {
                suggestions.add("Alignment score is " + alignmentScore
                        + "/100 — use auto-layout-and-route for uniform alignment");
            }
        }
        if (boundaryViolationCount > 0) {
            suggestions.add("Found " + boundaryViolationCount
                    + " elements extending outside their parent groups"
                    + " — resize groups or reposition elements");
        }
        if (offCanvasCount > 0) {
            suggestions.add("Found " + offCanvasCount
                    + " elements at negative or extreme coordinates"
                    + " — reposition to visible canvas area");
        }
        if (labelOverlapCount > 0) {
            if (hasGroups) {
                suggestions.add(labelOverlapCount + " connection labels overlap or are too close to elements or other labels"
                        + " — increase spacing within groups using layout-within-group"
                        + " and re-run auto-route-connections");
            } else {
                suggestions.add(labelOverlapCount + " connection labels overlap or are too close to elements or other labels"
                        + " — use auto-layout-and-route with increased spacing");
            }
        }

        // Story 11-31: short-segment label suggestion (separate from general label overlap)
        if (shortSegmentCount > 0) {
            suggestions.add(shortSegmentCount + " connection labels exceed available segment length"
                    + " — increase element spacing");
        }

        // Story 11-23: coincident segment suggestion
        if (coincidentSegmentCount > 0) {
            if (hasGroups) {
                suggestions.add(coincidentSegmentCount + " overlapping connection segments detected"
                        + " — increase spacing within groups using layout-within-group"
                        + " and re-run auto-route-connections to separate coincident paths");
            } else {
                suggestions.add(coincidentSegmentCount + " overlapping connection segments detected"
                        + " — increase element spacing or use auto-layout-and-route"
                        + " to separate coincident paths");
            }
        }

        if (suggestions.isEmpty()) {
            suggestions.add("Layout quality is good — no immediate improvements needed.");
        }

        return suggestions;
    }
}
