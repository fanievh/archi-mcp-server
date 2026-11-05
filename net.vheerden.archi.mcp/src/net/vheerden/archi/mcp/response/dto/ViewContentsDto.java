package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for the contents of an ArchiMate view (diagram).
 *
 * <p>Returned by the get-view-contents command. Contains the elements
 * and relationships present in a view, plus visual position metadata
 * for each element and connection routing metadata.</p>
 *
 * <p>Added groups and notes arrays for
 * visual grouping rectangles and text annotations on diagrams.</p>
 *
 * <p>Added images array for standalone
 * {@code IDiagramModelImage} visuals (sibling to notes / groups /
 * view-references). Populated only when the view contains image visuals;
 * null otherwise (NON_NULL omits from JSON).</p>
 *
 * <p>{@code viewReferences} lists every view reference ({@code IDiagramModelReference}) the view holds,
 * at any depth, each as the same row {@code add-view-reference-to-view} answers with. Null when the view
 * holds none, so a view without one serialises exactly as before the list existed.</p>
 *
 * <p>{@code nonArchimateConnections} lists every connection drawn on the view that is not an ArchiMate
 * one (no relationship stands behind it), so {@code connections} keeps its contract that every row
 * has a relationship. That includes an ArchiMate connection whose relationship is missing, which only
 * a damaged model holds. Null when the view holds none.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewContentsDto(
    String viewId,
    String viewName,
    String viewpoint,
    String connectionRouterType,
    List<ElementDto> elements,
    List<RelationshipDto> relationships,
    List<ViewNodeDto> visualMetadata,
    List<ViewConnectionDto> connections,
    List<ViewGroupDto> groups,
    List<ViewNoteDto> notes,
    List<DiagramImageDto> images,
    List<EmbeddedViewDto> viewReferences,
    List<NonArchimateConnectionDto> nonArchimateConnections
) {
    /**
     * Back-compat constructor: the shape before {@code nonArchimateConnections}, which it passes as
     * {@code null} (omitted from JSON).
     */
    public ViewContentsDto(String viewId, String viewName, String viewpoint,
            String connectionRouterType,
            List<ElementDto> elements, List<RelationshipDto> relationships,
            List<ViewNodeDto> visualMetadata, List<ViewConnectionDto> connections,
            List<ViewGroupDto> groups, List<ViewNoteDto> notes, List<DiagramImageDto> images,
            List<EmbeddedViewDto> viewReferences) {
        this(viewId, viewName, viewpoint, connectionRouterType, elements, relationships,
                visualMetadata, connections, groups, notes, images, viewReferences, null);
    }

    /**
     * Back-compat constructor: the shape before {@code viewReferences}, which it passes as
     * {@code null} (omitted from JSON), as it does {@code nonArchimateConnections}.
     */
    public ViewContentsDto(String viewId, String viewName, String viewpoint,
            String connectionRouterType,
            List<ElementDto> elements, List<RelationshipDto> relationships,
            List<ViewNodeDto> visualMetadata, List<ViewConnectionDto> connections,
            List<ViewGroupDto> groups, List<ViewNoteDto> notes, List<DiagramImageDto> images) {
        this(viewId, viewName, viewpoint, connectionRouterType, elements, relationships,
                visualMetadata, connections, groups, notes, images, null, null);
    }

    /**
     * Back-compat constructor — preserves the 10-arg ctor surface
     * by delegating with {@code null} for the new {@code images}
     * field (NON_NULL omits from JSON, so views without image visuals serialise
     * byte-identically to their prior form).
     */
    public ViewContentsDto(String viewId, String viewName, String viewpoint,
            String connectionRouterType,
            List<ElementDto> elements, List<RelationshipDto> relationships,
            List<ViewNodeDto> visualMetadata, List<ViewConnectionDto> connections,
            List<ViewGroupDto> groups, List<ViewNoteDto> notes) {
        this(viewId, viewName, viewpoint, connectionRouterType, elements, relationships,
                visualMetadata, connections, groups, notes, null, null, null);
    }

    /**
     * Convenience constructor without groups, notes, images, and
     * connectionRouterType (backward compat with earlier callers).
     */
    public ViewContentsDto(String viewId, String viewName, String viewpoint,
            List<ElementDto> elements, List<RelationshipDto> relationships,
            List<ViewNodeDto> visualMetadata, List<ViewConnectionDto> connections) {
        this(viewId, viewName, viewpoint, null, elements, relationships,
                visualMetadata, connections, null, null, null, null, null);
    }
}
