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
 * <p><strong>Story 8-6:</strong> Added groups and notes arrays for
 * visual grouping rectangles and text annotations on diagrams.</p>
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
    List<ViewNoteDto> notes
) {
    /**
     * Convenience constructor without groups, notes, and connectionRouterType (backward compat).
     */
    public ViewContentsDto(String viewId, String viewName, String viewpoint,
            List<ElementDto> elements, List<RelationshipDto> relationships,
            List<ViewNodeDto> visualMetadata, List<ViewConnectionDto> connections) {
        this(viewId, viewName, viewpoint, null, elements, relationships,
                visualMetadata, connections, null, null);
    }
}
