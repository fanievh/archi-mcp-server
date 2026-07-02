package net.vheerden.archi.mcp.model;

/**
 * A view object's geometry for layout quality assessment.
 * All coordinates are absolute canvas coordinates (parent offsets accumulated).
 * Includes parentId for boundary violation detection, isGroup to distinguish
 * container groups from leaf elements, and isNote to identify
 * annotation notes that should be excluded from layout scoring.
 * name, labelTextWidth, imagePath, imagePosition for informational detection.
 * noteRequiredHeight is the wrapped height (px) the note's content needs at its
 * current width — pre-computed for notes only (0.0 for non-notes or when
 * unavailable) so {@code LayoutQualityAssessor} can detect clipped notes by pure
 * geometry without an SWT dependency.
 * imageNaturalWidth/imageNaturalHeight are the image's true archive pixel size,
 * pre-computed for image-bearing elements (0.0 when there is no image or the
 * archive read is unavailable) so the overlap detector can size the image rect
 * from what actually renders instead of a fixed icon assumption — again keeping
 * {@code LayoutQualityAssessor} free of archive/SWT dependencies.
 * isJunction marks an ArchiMate Junction — a solid dark shape with no usable
 * interior, so the own-endpoint label-overlap check applies a near-zero overlap
 * bar to it (any label on its fill is unreadable) instead of the box-tolerant bar.
 * fillColor is the view object's authored fill (#RRGGBB) or null when unauthored
 * (the EMF default our code never stamps) — carried so the assessor can flag a
 * container whose authored fill equals a nested child's, the residual flat-blob the
 * container-recession emitter is contractually forbidden to touch.
 */
record AssessmentNode(String id, double x, double y, double width, double height,
                      String parentId, boolean isGroup, boolean isNote,
                      String name, double labelTextWidth,
                      String imagePath, String imagePosition,
                      double noteRequiredHeight,
                      double imageNaturalWidth, double imageNaturalHeight,
                      boolean isJunction, String fillColor) {
    /**
     * Delegating constructor with the pre-junction 15-component signature: builds a non-junction,
     * null-fill node. Keeps every existing call site (tests, spikes, and builders that do not see
     * the model concept) compiling byte-identical.
     */
    AssessmentNode(String id, double x, double y, double width, double height,
                   String parentId, boolean isGroup, boolean isNote,
                   String name, double labelTextWidth,
                   String imagePath, String imagePosition,
                   double noteRequiredHeight,
                   double imageNaturalWidth, double imageNaturalHeight) {
        this(id, x, y, width, height, parentId, isGroup, isNote, name, labelTextWidth,
                imagePath, imagePosition, noteRequiredHeight, imageNaturalWidth, imageNaturalHeight,
                false, null);
    }

    /**
     * Delegating constructor with the pre-fill 16-component signature (ends at isJunction):
     * builds a null-fill node. Keeps junction-aware call sites that do not carry fill (synthetic
     * nodes, tests) compiling byte-identical while the real collector sets the fill.
     */
    AssessmentNode(String id, double x, double y, double width, double height,
                   String parentId, boolean isGroup, boolean isNote,
                   String name, double labelTextWidth,
                   String imagePath, String imagePosition,
                   double noteRequiredHeight,
                   double imageNaturalWidth, double imageNaturalHeight,
                   boolean isJunction) {
        this(id, x, y, width, height, parentId, isGroup, isNote, name, labelTextWidth,
                imagePath, imagePosition, noteRequiredHeight, imageNaturalWidth, imageNaturalHeight,
                isJunction, null);
    }
}
