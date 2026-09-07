package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for a standalone image visual on an ArchiMate view.
 *
 * <p>Represents an {@code IDiagramModelImage} — a first-class image visual
 * node placed directly on a view (sibling to notes, groups, view-references).
 * Distinct from {@code IIconic}-based imagePath fields on element/group/note
 * view-objects (which are icon overlays on existing elements).</p>
 *
 * <p>The {@code imagePath} resolves to bytes stored in the model archive
 * (use {@code list-model-images} or {@code add-image-to-model} for
 * round-trip).</p>
 *
 * <p>Field surface intentionally minimal per Open Question 5 disposition:
 * only the bounds + identifier core plus the two fields
 * {@code IDiagramModelImage} actually surfaces in EMF
 * ({@code borderColor} via {@code IBorderObject}, {@code documentation}
 * via {@code IDocumentable}). The {@code add-image-to-view} schema advertises
 * the full 16-field styling surface (uniform sibling schemas), but
 * Archi's image renderer silently ignores most font/gradient fields —
 * this DTO omits them to avoid round-trip surprises.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramImageDto(
    String viewObjectId,        // EMF object ID of the image visual
    String imagePath,            // opaque archive path minted by Archi — pass back verbatim, never construct or parse; non-null in a populated DTO
    int x,
    int y,
    int width,
    int height,
    String parentViewObjectId,   // null when top-level on the view; non-null when nested in a group/element
    String borderColor,          // #RRGGBB hex; null when default
    String documentation,        // null when empty (mirror existing convention)
    /**
     * Placement-time disclosures for THIS call — currently the one that says the rectangle just
     * placed lands on a route already drawn on the view.
     *
     * <p>Present only on the response to a placement call, and on every arm of one, so approval
     * mode — which discards {@code nextSteps} wholesale — cannot lose it. A route crossing a
     * standalone image is a RATED {@code connectionPassThroughs}, not the informational note
     * metric: an image visual is an ordinary layout node to the assessor.</p>
     */
    java.util.List<StructuredWarningDto> structuredWarnings
) {

    /** The read-path shape: a view's stored image visual carries no placement-time disclosure. */
    public DiagramImageDto(String viewObjectId, String imagePath, int x, int y,
            int width, int height, String parentViewObjectId,
            String borderColor, String documentation) {
        this(viewObjectId, imagePath, x, y, width, height, parentViewObjectId,
                borderColor, documentation, null);
    }
}
