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
 * isContainer marks every object that RENDERS as a transparent labelled box holding
 * other objects — a native view group, and equally an ArchiMate {@code Grouping}
 * element, which is a diagram archimate object and so leaves isGroup false. It is a
 * strict SUPERSET of isGroup: every group is a container, not every container is a
 * group. Detectors that reason about what the canvas SHOWS (a transparent rectangle
 * is not an obstruction, does not host a label, is not an alignment participant) must
 * read isContainer; the few that reason about what can be MEASURED or about published
 * vocabulary must keep reading isGroup, because those two properties genuinely differ
 * between the kinds — a Grouping carries a measured labelTextWidth and a native group
 * does not, and {@code get-view-contents} reports the two in different buckets.
 * textAlignment is the object's HORIZONTAL label alignment, mirroring Archi's
 * {@code ITextAlignment} values ({@link #TEXT_ALIGNMENT_LEFT} 1, {@link #TEXT_ALIGNMENT_CENTRE} 2,
 * {@link #TEXT_ALIGNMENT_RIGHT} 4) as a plain int so this record stays EMF-free. It is a
 * PER-OBJECT feature, not a per-type one: Archi places the glyph run inside the figure through a
 * {@code GridData} constraint whose horizontal alignment is read from this very value, so two
 * objects of the same type render their titles in different places. Two consequences that are easy
 * to get wrong: the same kind can arrive with different alignments depending on who authored it and
 * when — a {@code Grouping}, group or note is stamped LEFT at creation by both Archi's palette and
 * this server, a plain element is left at CENTRE here while Archi derives its default from a user
 * preference, and one this server created before it stamped anything keeps CENTRE regardless, so a
 * model built across that change carries several populations; and this server's own
 * {@code textAlignment} parameter can set any value on any object, so a left-aligned title is
 * reachable on a plain element too. Anything outside the three known values is treated as CENTRE
 * by the consumer rather than rejected here — an unrecognised alignment should degrade a single
 * informational rectangle, not fail an assessment.
 * textPosition is the object's VERTICAL label position, mirroring Archi's {@code ITextPosition}
 * values ({@link #TEXT_POSITION_TOP} 0, {@link #TEXT_POSITION_CENTRE} 1, {@link #TEXT_POSITION_BOTTOM} 2)
 * as a plain int, for the same EMF-free reason as textAlignment. The two are the SAME per-object
 * mechanism and not merely analogous ones: Archi's figure builds a single
 * {@code GridData(horizontalAlignment, verticalAlignment, true, true)} whose two arguments are read
 * from {@code getTextAlignment()} and {@code getTextPosition()} respectively, so an object whose
 * title is centred vertically is exactly as ordinary as one whose title is left-aligned. This
 * server publishes a {@code verticalTextAlignment} parameter that writes it on any object, so a
 * non-TOP title is reachable through this server just as a non-CENTRE alignment is. TOP is Archi's
 * EMF default and by far the common case, which is why it was possible to model the band as
 * unconditionally top-anchored for as long as it was; an unrecognised value degrades to TOP at the
 * consumer, matching how an unrecognised alignment degrades to CENTRE.
 *
 * <p>There are delegating constructors for the 15- and 16-component forms but deliberately
 * NOT for the 20-component canonical one. Of its three production call sites, TWO rebuild a node from
 * an existing node (the nudge-delta reconstruction pair) and the third builds from the EMF
 * child; a delegate would let the rebuilding pair keep compiling while silently dropping
 * isContainer back to isGroup — stripping containerhood from every node on the post-nudge
 * re-assessment path, which is precisely where such a loss would be invisible.
 * Leaving the form uncovered turns that hazard into a compile error that has to be answered.
 * The shorter delegates carry no such risk: their callers build nodes from scratch and have
 * no container knowledge to lose.</p>
 */
record AssessmentNode(String id, double x, double y, double width, double height,
                      String parentId, boolean isGroup, boolean isNote,
                      String name, double labelTextWidth,
                      String imagePath, String imagePosition,
                      double noteRequiredHeight,
                      double imageNaturalWidth, double imageNaturalHeight,
                      boolean isJunction, String fillColor, boolean isContainer,
                      int textAlignment, int textPosition) {

    /** Horizontal label alignment: glyph run against the left inner edge. Archi's {@code ITextAlignment} value. */
    static final int TEXT_ALIGNMENT_LEFT = 1;

    /** Horizontal label alignment: glyph run centred in the figure. Archi's EMF default. */
    static final int TEXT_ALIGNMENT_CENTRE = 2;

    /** Horizontal label alignment: glyph run against the right inner edge. Archi's {@code ITextAlignment} value. */
    static final int TEXT_ALIGNMENT_RIGHT = 4;

    /** Vertical label position: band against the figure's top inner edge. Archi's {@code ITextPosition} value, and its EMF default. */
    static final int TEXT_POSITION_TOP = 0;

    /** Vertical label position: band centred in the figure. Archi's {@code ITextPosition} value. */
    static final int TEXT_POSITION_CENTRE = 1;

    /** Vertical label position: band against the figure's bottom inner edge. Archi's {@code ITextPosition} value. */
    static final int TEXT_POSITION_BOTTOM = 2;

    /**
     * Enforces the superset relationship the class note states: every native group IS a container.
     *
     * <p>Omitting the 17-component delegate makes "forgot to pass isContainer" a compile error, but
     * it cannot catch "passed the wrong boolean" — a call site copied from the nudge-rebuild pattern
     * that threads the fields of the wrong node, say. Documentation alone does not hold an
     * invariant; this does, at the one place every node must pass through. The check is a single
     * boolean test on a record already allocated per view object, so it costs nothing measurable.
     */
    AssessmentNode {
        if (isGroup && !isContainer) {
            throw new IllegalArgumentException(
                    "isContainer must be true for a native group (id=" + id + "): it is a superset "
                            + "of isGroup, so a group that is not a container is a construction bug");
        }
    }

    /**
     * Delegating constructor with the pre-junction 15-component signature: builds a non-junction,
     * null-fill node. Keeps every existing call site (tests, spikes, and builders that do not see
     * the model concept) compiling byte-identical.
     *
     * <p>{@code isContainer} defaults to {@code isGroup} rather than to {@code false}. A caller on
     * this signature has no model concept to consult, so the group flag is the only container
     * signal it carries; defaulting to false would silently reclassify every synthetic group as a
     * leaf. {@code textAlignment} defaults to {@link #TEXT_ALIGNMENT_CENTRE} and
     * {@code textPosition} to {@link #TEXT_POSITION_TOP}, each of which is both Archi's EMF default
     * and the value every caller on this signature implicitly assumed before the component existed
     * — so no existing fixture changes meaning. There is deliberately NO delegate
     * for the full 20-component form — see the class note on why that one has to be a compile error.</p>
     */
    AssessmentNode(String id, double x, double y, double width, double height,
                   String parentId, boolean isGroup, boolean isNote,
                   String name, double labelTextWidth,
                   String imagePath, String imagePosition,
                   double noteRequiredHeight,
                   double imageNaturalWidth, double imageNaturalHeight) {
        this(id, x, y, width, height, parentId, isGroup, isNote, name, labelTextWidth,
                imagePath, imagePosition, noteRequiredHeight, imageNaturalWidth, imageNaturalHeight,
                false, null, isGroup, TEXT_ALIGNMENT_CENTRE, TEXT_POSITION_TOP);
    }

    /**
     * Delegating constructor with the pre-fill 16-component signature (ends at isJunction):
     * builds a null-fill node. Keeps junction-aware call sites that do not carry fill (synthetic
     * nodes, tests) compiling byte-identical while the real collector sets the fill.
     *
     * <p>{@code isContainer} defaults to {@code isGroup}, {@code textAlignment} to
     * {@link #TEXT_ALIGNMENT_CENTRE} and {@code textPosition} to {@link #TEXT_POSITION_TOP}, for
     * the reasons given on the 15-component delegate above.</p>
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
                isJunction, null, isGroup, TEXT_ALIGNMENT_CENTRE, TEXT_POSITION_TOP);
    }
}
