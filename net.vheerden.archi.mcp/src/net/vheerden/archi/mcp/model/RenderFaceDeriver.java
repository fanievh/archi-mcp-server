package net.vheerden.archi.mcp.model;

/**
 * Derives the element face a connection is drawn leaving or entering, from the anchor Archi
 * actually installs.
 *
 * <p><strong>Why a derivation is needed at all.</strong> Nothing a view response publishes names
 * the point where a line meets its element. The stored bendpoints are waypoints the line is aimed
 * at, and the anchor points are element centres — reference points the renderer aims <em>from</em>,
 * never attachment points. The attachment is computed at paint time by a connection anchor whose
 * algorithm the caller cannot run, from geometry the caller does not hold at full precision.
 *
 * <p><strong>Which anchor.</strong> On a default Archi installation every ArchiMate element figure
 * gets {@code OrthogonalAnchor}: {@code AbstractConnectedEditPart.getSourceConnectionAnchor}
 * returns one whenever {@code canUseOrthogonalAnchor()} and the {@code orthogonalAnchor}
 * preference are both true, and that preference's default is {@code true}. Exactly one edit part
 * in the product overrides {@code canUseOrthogonalAnchor()} to false, and its only user is the
 * Junction. With the preference turned off each figure falls back to its own default anchor, which
 * for a plain rectangle is draw2d's {@code ChopboxAnchor} — a different algorithm entirely, modelled
 * here as {@link AnchorModel#CHOPBOX} so the two can be compared rather than conflated.
 *
 * <p><strong>What the orthogonal anchor does.</strong> It is not a ray intersection. It bands the
 * reference point against the element's own — unexpanded — box on each axis independently, and the
 * pair of bands selects the attachment directly. For a plain rectangle the bands are
 * {@code LEFT / MIDDLE / RIGHT} and {@code TOP / CENTER / BOTTOM}, half-open, and the nine
 * combinations resolve to four faces, four corners and one interior case that attaches nowhere.
 * Before banding, the reference is replaced when it lies at the centre of the figure at the other
 * end: the replacement is the midpoint of the two boxes' per-axis overlap, and it is what makes
 * Archi draw a horizontal line between two aligned boxes instead of a sloped one.
 *
 * <p><strong>Rounded figures are not a rare case.</strong> A figure's corner dimension widens two
 * corner bands per axis, and it is non-zero for the <em>default</em> figure of twenty ArchiMate
 * types — every Service, Process, Function, Interaction and Event, plus Work Package, Capability,
 * Course of Action and Value Stream. Treating a rounded figure as underivable would therefore
 * discard a large part of the population rather than a corner case. It is not necessary either:
 * the four face arms
 * return {@code (ref.x, box.y)} and {@code (box.x, ref.y)} whatever the corner dimension is, so
 * only the arms that land on the corner arc itself depend on it, and only those abstain.
 *
 * <p>Pure geometry: no EMF, no SWT, no preference store. Everything the derivation needs — both
 * boxes, the reference point and the corner dimension — is passed in, so the caller decides where
 * those come from and this class stays runnable on a display-less runner.
 */
final class RenderFaceDeriver {

    private RenderFaceDeriver() {}

    /** The anchor algorithm in force, which the {@code orthogonalAnchor} preference selects. */
    enum AnchorModel {
        /** Installed by default on every element figure but the Junction. */
        ORTHOGONAL,
        /** draw2d's ray intersection, used when the preference is off and the figure is plain. */
        CHOPBOX
    }

    /**
     * Every way a terminal can resolve: four faces, four corners, and the reasons a face cannot be
     * named. The census name is the bucket a coverage table counts under; the published value is
     * what reaches the wire, and is null for every abstention.
     */
    enum Outcome {
        FACE_TOP("face-top", "top"),
        FACE_BOTTOM("face-bottom", "bottom"),
        FACE_LEFT("face-left", "left"),
        FACE_RIGHT("face-right", "right"),
        CORNER_TOP_LEFT("corner-top-left", null),
        CORNER_TOP_RIGHT("corner-top-right", null),
        CORNER_BOTTOM_LEFT("corner-bottom-left", null),
        CORNER_BOTTOM_RIGHT("corner-bottom-right", null),
        /** The reference sits inside the box: the anchor returns the centre, which is on no face. */
        ABSTAIN_REFERENCE_INSIDE_BOX("abstain-reference-inside-box", null),
        /** The attachment lands on a rounded corner's arc, which belongs to neither face it joins. */
        ABSTAIN_ROUNDED_CORNER_ARC("abstain-rounded-corner-arc", null),
        /** A Junction: an ellipse anchor, whatever the preference says. A face is undefined. */
        ABSTAIN_ELLIPSE_ANCHOR("abstain-ellipse-anchor", null),
        /** A degenerate box has no interior, so no band can select a face over a corner. */
        ABSTAIN_ZERO_SIZE_ELEMENT("abstain-zero-size-element", null),
        /** Bounds could not be read for this endpoint or the one it is aimed at. */
        ABSTAIN_UNREADABLE_BOUNDS("abstain-unreadable-bounds", null),
        /** The preference-off anchor for this figure cannot be modelled from the model alone. */
        ABSTAIN_UNKNOWN_FALLBACK_ANCHOR("abstain-unknown-fallback-anchor", null),
        /** The two configurations Archi can be in name different faces here. */
        ABSTAIN_ANCHOR_MODELS_DISAGREE("abstain-anchor-models-disagree", null);

        private final String censusName;
        private final String publishedValue;

        Outcome(String censusName, String publishedValue) {
            this.censusName = censusName;
            this.publishedValue = publishedValue;
        }

        /** The coverage-table bucket this outcome counts under. */
        String censusName() {
            return censusName;
        }

        /**
         * The value published on the wire, or null when this outcome abstains.
         *
         * <p>A corner publishes nothing, and that is a measured decision rather than a cautious
         * one. Over the three fixtures that record real stored geometry no terminal resolves to a
         * corner at all, so an eight-value vocabulary mixing faces with corners would buy no
         * coverage there; and the 184 corners the full corpus does produce fall entirely on the
         * four fixtures that record no bendpoints key, where "this connection has no bendpoints" is
         * a fact about the capture and not about any view. A vocabulary published from the first
         * release cannot be sized on a population the corpus cannot show exists. The point itself
         * is real — it lies on two face lines at once — and nothing in its own position chooses
         * between them, which is the same reason the boundary face is the published word and a
         * corner is not one of its values. The census counts the four corners separately, so this
         * stays visible and reversible on evidence.
         */
        String publishedValue() {
            return publishedValue;
        }

        /** Whether this outcome names something on the element's boundary. */
        boolean isAttached() {
            return publishedValue != null || name().startsWith("CORNER_");
        }
    }

    // The band constants, and their combinations, are OrthogonalAnchor's own.
    private static final int LEFT = 1;
    private static final int LEFT_CORNER = 2;
    private static final int MIDDLE = 4;
    private static final int RIGHT_CORNER = 8;
    private static final int RIGHT = 16;
    private static final int TOP = 32;
    private static final int TOP_CORNER = 64;
    private static final int CENTER = 128;
    private static final int BOTTOM_CORNER = 256;
    private static final int BOTTOM = 512;

    /** The constant arc every rounded-rectangle figure delegate carries. */
    static final int ROUNDED_ARC = 20;

    /**
     * The anchor a figure falls back to when the {@code orthogonalAnchor} preference is off.
     *
     * <p>It is not always {@code ChopboxAnchor}. A figure drawing through a rounded delegate
     * installs {@code RoundedRectangleAnchor}, which is a strict narrowing of the chopbox answer:
     * it computes the chopbox point and then keeps it only when it did not land in one of the four
     * corner zones. Treating those figures as plain chopbox would therefore over-report agreement
     * on exactly the population that most needs it.
     */
    enum Fallback {
        /** draw2d's ray intersection: every plain rectangular figure. */
        CHOPBOX,
        /** The chopbox point, kept only outside the corner zones. */
        ROUNDED_RECTANGLE,
        /** A point on an ellipse, which belongs to no face. */
        ELLIPSE,
        /** A shifted anchor whose offset is figure state this derivation cannot read. */
        UNKNOWN
    }

    /**
     * How one element anchors: the corner dimension the installed orthogonal anchor bands against,
     * and the anchor the preference-off configuration would use instead.
     *
     * @param corner   {@code {width, height}}, or null when no orthogonal anchor is installed
     * @param fallback the preference-off anchor
     */
    record Anchoring(int[] corner, Fallback fallback) {}

    /**
     * ArchiMate types whose <em>default</em> figure draws through a rounded-rectangle delegate.
     *
     * <p>Read from the figure classes rather than assumed: each returns its rounded delegate when
     * the diagram object's figure variant is 0 and no delegate at all otherwise, so the rounded
     * shape is the default and the plain rectangle is the alternate — the opposite way round from
     * what the ArchiMate notation's own shapes suggest.
     */
    private static final java.util.Set<String> ROUNDED_AT_DEFAULT_FIGURE = java.util.Set.of(
            "BusinessService", "ApplicationService", "TechnologyService",
            "BusinessProcess", "ApplicationProcess", "TechnologyProcess",
            "BusinessFunction", "ApplicationFunction", "TechnologyFunction",
            "BusinessInteraction", "ApplicationInteraction", "TechnologyInteraction",
            "BusinessEvent", "ApplicationEvent", "TechnologyEvent", "ImplementationEvent",
            "WorkPackage", "Capability", "CourseOfAction", "ValueStream");

    /** Services keep a rounded delegate on the alternate figure too, with a size-derived arc. */
    private static final java.util.Set<String> SERVICE_TYPES = java.util.Set.of(
            "BusinessService", "ApplicationService", "TechnologyService");

    /**
     * How an element anchors, from the model alone.
     *
     * <p>Derived from the ArchiMate type and the diagram object's figure variant, never from a live
     * figure. Asking a figure would mean instantiating Archi's UI provider factory, which cannot
     * class-initialise without a display and would strand this derivation in a lane no automated
     * runner executes.
     *
     * @param elementType the ArchiMate type name, as {@code eClass().getName()} spells it
     * @param figureType  the diagram object's figure variant; 0 is the default figure
     * @param width       the element's width, for the size-derived service arc
     * @param height      the element's height
     */
    static Anchoring anchoringOf(String elementType, int figureType, int width, int height) {
        if (elementType == null) {
            return new Anchoring(new int[] { 0, 0 }, Fallback.CHOPBOX);
        }
        if ("Junction".equals(elementType)) {
            // The only edit part in the product that refuses the orthogonal anchor, whatever the
            // preference says. Its figure anchors on an ellipse, which has no faces.
            return new Anchoring(null, Fallback.ELLIPSE);
        }
        if ("Value".equals(elementType) && figureType == 1) {
            // The whole figure is the corner, so every band but a one-pixel sliver is a corner one.
            return new Anchoring(new int[] { width, height }, Fallback.ELLIPSE);
        }
        if (figureType == 0 && ROUNDED_AT_DEFAULT_FIGURE.contains(elementType)) {
            return new Anchoring(new int[] { ROUNDED_ARC, ROUNDED_ARC },
                    Fallback.ROUNDED_RECTANGLE);
        }
        if (figureType != 0 && SERVICE_TYPES.contains(elementType)) {
            // The service delegate's own arc: as tall as the figure, and as wide as the lesser of
            // the height and four fifths of the width — the pill the notation draws.
            return new Anchoring(new int[] { Math.min(height, width * 8 / 10), height },
                    Fallback.ROUNDED_RECTANGLE);
        }
        if ("Grouping".equals(elementType) && figureType != 0) {
            // The alternate grouping figure shifts its fallback anchor down by the tab height,
            // which is figure state and not model state. The orthogonal anchor is unaffected, but
            // the two configurations cannot be compared, so this endpoint declines.
            return new Anchoring(new int[] { 0, 0 }, Fallback.UNKNOWN);
        }
        return new Anchoring(new int[] { 0, 0 }, Fallback.CHOPBOX);
    }

    /**
     * The anchor's reference point: the centre of the box at the other end.
     *
     * <p>{@code ChopboxAnchor.getReferencePoint} is {@code Rectangle.getCenter()}, which is
     * {@code x + width / 2} on integer division, and {@code OrthogonalAnchor} inherits it
     * unchanged. That is the same truncated centre a connection response already publishes as
     * {@code sourceAnchor} / {@code targetAnchor}.
     */
    static int[] centreOf(int[] box) {
        return new int[] { box[0] + box[2] / 2, box[1] + box[3] / 2 };
    }

    /**
     * The face to publish for one endpoint, or null when nothing can honestly be published.
     *
     * <p>The value is the face <em>both</em> anchor algorithms Archi can be configured to use name,
     * and nothing otherwise. That is what removes the configuration axis instead of declaring it:
     * the attachment depends on a per-installation preference the model file does not carry, so a
     * value derived from only the default configuration would be silently wrong for anyone who
     * turned the preference off, with nothing on the wire to tell the two apart. Requiring
     * agreement costs nothing measurable — over both census denominators the number of terminals
     * the two models agree on is <em>exactly</em> the number the installed anchor names a face for,
     * so gating on agreement removed no value that would otherwise have been published.
     *
     * @param own       the endpoint's own absolute bounds as {@code {x, y, width, height}}
     * @param remote    the other endpoint's absolute bounds, which the reference replacement needs
     * @param refX      the reference point the anchor aims at, in absolute canvas coordinates
     * @param refY      as {@code refX}
     * @param anchoring how this endpoint's figure anchors, from {@link #anchoringOf}
     */
    static String publishedFace(int[] own, int[] remote, int refX, int refY, Anchoring anchoring) {
        return classify(own, remote, refX, refY, anchoring).publishedValue();
    }

    /**
     * The outcome for one endpoint under both configurations, resolved to a single verdict.
     *
     * <p>Package-visible so the coverage census can count the reasons rather than only the values:
     * a table of abstentions with no reason beside them cannot tell a corpus apart from a broken
     * classifier.
     */
    static Outcome classify(int[] own, int[] remote, int refX, int refY, Anchoring anchoring) {
        if (own == null || remote == null) {
            return Outcome.ABSTAIN_UNREADABLE_BOUNDS;
        }
        if (anchoring == null || anchoring.corner() == null) {
            return Outcome.ABSTAIN_ELLIPSE_ANCHOR;
        }
        if (own[2] <= 0 || own[3] <= 0) {
            return Outcome.ABSTAIN_ZERO_SIZE_ELEMENT;
        }
        Outcome installed = deriveOrthogonal(own, remote, refX, refY, anchoring.corner());
        if (installed.publishedValue() == null) {
            // Already declining: the fallback cannot rescue it and asking would only invent a
            // second reason for the same abstention.
            return installed;
        }
        Outcome fallback = deriveFallback(own, refX, refY, anchoring);
        if (fallback == Outcome.ABSTAIN_UNKNOWN_FALLBACK_ANCHOR
                || fallback == Outcome.ABSTAIN_ELLIPSE_ANCHOR) {
            // The other configuration cannot be modelled at all here, so there is nothing to
            // compare. Naming that as a disagreement would report the wrong reason.
            return fallback;
        }
        return installed == fallback ? installed : Outcome.ABSTAIN_ANCHOR_MODELS_DISAGREE;
    }

    /**
     * The outcome under one named anchor model, without the agreement gate.
     *
     * <p>Package-visible for the census, which measures the two models separately in order to
     * establish what the gate costs. Production reads {@link #classify}.
     */
    static Outcome derive(int[] own, int[] remote, int refX, int refY, int[] corner,
                          AnchorModel model) {
        if (own == null || remote == null) {
            return Outcome.ABSTAIN_UNREADABLE_BOUNDS;
        }
        if (corner == null) {
            return Outcome.ABSTAIN_ELLIPSE_ANCHOR;
        }
        if (own[2] <= 0 || own[3] <= 0) {
            return Outcome.ABSTAIN_ZERO_SIZE_ELEMENT;
        }
        return model == AnchorModel.ORTHOGONAL
                ? deriveOrthogonal(own, remote, refX, refY, corner)
                : deriveChopbox(own, refX, refY);
    }

    private static Outcome deriveFallback(int[] own, int refX, int refY, Anchoring anchoring) {
        switch (anchoring.fallback()) {
            case CHOPBOX:
                return deriveChopbox(own, refX, refY);
            case ROUNDED_RECTANGLE:
                return deriveRoundedRectangle(own, refX, refY, anchoring.corner());
            case ELLIPSE:
                return Outcome.ABSTAIN_ELLIPSE_ANCHOR;
            default:
                return Outcome.ABSTAIN_UNKNOWN_FALLBACK_ANCHOR;
        }
    }

    /**
     * {@code OrthogonalAnchor.getLocation}, reduced to which arm it returns from.
     *
     * <p>The arithmetic inside each arm is not reproduced — the arm identity is the whole answer,
     * because every arm returns a point on one named part of the boundary. What <em>is</em>
     * reproduced exactly is everything that selects the arm: the reference replacement, the
     * truncating halving of the corner dimension, and the half-open band comparisons.
     */
    private static Outcome deriveOrthogonal(int[] own, int[] remote, int refX, int refY,
                                            int[] corner) {
        int x = own[0];
        int y = own[1];
        int w = own[2];
        int h = own[3];

        if (isAtRemoteCentre(remote, refX, refY)) {
            refX = (Math.max(remote[0], x) + Math.min(remote[0] + remote[2], x + w)) / 2;
            refY = (Math.max(remote[1], y) + Math.min(remote[1] + remote[3], y + h)) / 2;
        }

        int halfCornerW = corner[0] / 2;
        int halfCornerH = corner[1] / 2;

        int pos;
        if (refX < x) {
            pos = LEFT;
        } else if (refX < x + halfCornerW) {
            pos = LEFT_CORNER;
        } else if (refX < x + w - halfCornerW) {
            pos = MIDDLE;
        } else if (refX < x + w) {
            pos = RIGHT_CORNER;
        } else {
            pos = RIGHT;
        }
        if (refY < y) {
            pos |= TOP;
        } else if (refY < y + halfCornerH) {
            pos |= TOP_CORNER;
        } else if (refY < y + h - halfCornerH) {
            pos |= CENTER;
        } else if (refY < y + h) {
            pos |= BOTTOM_CORNER;
        } else {
            pos |= BOTTOM;
        }

        switch (pos) {
            case MIDDLE | TOP:
                return Outcome.FACE_TOP;
            case MIDDLE | BOTTOM:
                return Outcome.FACE_BOTTOM;
            case LEFT | CENTER:
                return Outcome.FACE_LEFT;
            case RIGHT | CENTER:
                return Outcome.FACE_RIGHT;
            case LEFT | TOP:
                return Outcome.CORNER_TOP_LEFT;
            case RIGHT | TOP:
                return Outcome.CORNER_TOP_RIGHT;
            case LEFT | BOTTOM:
                return Outcome.CORNER_BOTTOM_LEFT;
            case RIGHT | BOTTOM:
                return Outcome.CORNER_BOTTOM_RIGHT;
            case LEFT_CORNER | TOP:
            case RIGHT_CORNER | TOP:
            case LEFT_CORNER | BOTTOM:
            case RIGHT_CORNER | BOTTOM:
            case LEFT | TOP_CORNER:
            case RIGHT | TOP_CORNER:
            case LEFT | BOTTOM_CORNER:
            case RIGHT | BOTTOM_CORNER:
                return Outcome.ABSTAIN_ROUNDED_CORNER_ARC;
            default:
                // Every remaining combination has the reference inside the box on both axes, and
                // the anchor's own switch has no case for it: it returns the box centre, a point
                // on no part of the boundary.
                return Outcome.ABSTAIN_REFERENCE_INSIDE_BOX;
        }
    }

    /**
     * Whether the reference sits close enough to the remote figure's centre for the anchor to
     * replace it.
     *
     * <p>The anchor does not compare against the centre directly; it shrinks the remote box toward
     * its centre and asks whether the reference is contained. The shrink is by
     * {@code (size - 5) / 2} per axis on integer division, so the surviving rectangle is five
     * pixels across on an odd dimension and <em>six</em> on an even one — replicated exactly here,
     * halving and clamping included, because a one-pixel difference in this test flips the whole
     * arm the derivation lands on.
     */
    private static boolean isAtRemoteCentre(int[] remote, int refX, int refY) {
        int rx = remote[0];
        int ry = remote[1];
        int rw = remote[2];
        int rh = remote[3];
        int shrinkH = (rw - 5) / 2;
        int shrinkV = (rh - 5) / 2;
        int nx = Math.min(rx + rw / 2, rx + shrinkH);
        int nw = Math.max(0, rw - 2 * shrinkH);
        int ny = Math.min(ry + rh / 2, ry + shrinkV);
        int nh = Math.max(0, rh - 2 * shrinkV);
        return refX >= nx && refX < nx + nw && refY >= ny && refY < ny + nh;
    }

    /**
     * {@code ChopboxAnchor.getLocation}, reduced to which edge its ray leaves through.
     *
     * <p>Three details of the real method are load-bearing and kept. The box is expanded to
     * {@code (x - 1, y - 1, w + 1, h + 1)} before anything else, and the centre used is the
     * <em>float</em> centre of that expanded box — {@code x + (w - 1) / 2f}, which equals the
     * published integer centre only on an odd dimension and sits half a pixel lower on an even one.
     * The edge is then chosen by an aspect-normalised comparison rather than a raw {@code dx}
     * against {@code dy}. And an exact tie puts the point on a corner, which is a real outcome
     * rather than a theoretical one.
     */
    private static Outcome deriveChopbox(int[] own, int refX, int refY) {
        float boxW = own[2] + 1f;
        float boxH = own[3] + 1f;
        if (boxW <= 0 || boxH <= 0) {
            return Outcome.ABSTAIN_ZERO_SIZE_ELEMENT;
        }
        float centreX = (own[0] - 1f) + 0.5f * boxW;
        float centreY = (own[1] - 1f) + 0.5f * boxH;
        if (refX == (int) centreX && refY == (int) centreY) {
            return Outcome.ABSTAIN_REFERENCE_INSIDE_BOX;
        }

        float dx = refX - centreX;
        float dy = refY - centreY;
        float horizontal = Math.abs(dx) / boxW;
        float vertical = Math.abs(dy) / boxH;

        if (horizontal == vertical) {
            if (dx < 0) {
                return dy < 0 ? Outcome.CORNER_TOP_LEFT : Outcome.CORNER_BOTTOM_LEFT;
            }
            return dy < 0 ? Outcome.CORNER_TOP_RIGHT : Outcome.CORNER_BOTTOM_RIGHT;
        }
        if (horizontal > vertical) {
            return dx < 0 ? Outcome.FACE_LEFT : Outcome.FACE_RIGHT;
        }
        return dy < 0 ? Outcome.FACE_TOP : Outcome.FACE_BOTTOM;
    }

    /**
     * The chopbox attachment point itself, which the rounded-rectangle fallback bands.
     *
     * <p>Returned in the expanded box's own frame, because that is the frame
     * {@code RoundedRectangleAnchor} compares it in. Null on the degenerate arm, where the anchor
     * hands back its own centre rather than a boundary point.
     */
    private static int[] chopboxPoint(int[] own, int refX, int refY) {
        float boxX = own[0] - 1f;
        float boxY = own[1] - 1f;
        float boxW = own[2] + 1f;
        float boxH = own[3] + 1f;
        float centreX = boxX + 0.5f * boxW;
        float centreY = boxY + 0.5f * boxH;
        if (boxW <= 0 || boxH <= 0 || (refX == (int) centreX && refY == (int) centreY)) {
            return null;
        }
        float dx = refX - centreX;
        float dy = refY - centreY;
        float scale = 0.5f / Math.max(Math.abs(dx) / boxW, Math.abs(dy) / boxH);
        return new int[] { Math.round(centreX + dx * scale), Math.round(centreY + dy * scale) };
    }

    /**
     * {@code RoundedRectangleAnchor.getLocation}, which is a narrowing of the chopbox answer.
     *
     * <p>It computes the chopbox point first and then asks which zone of the expanded box that
     * point fell in. Outside the four corner zones it returns the point unchanged, so the face is
     * whatever chopbox chose. Inside one, it replaces the point with an intersection on the
     * corner's ellipse — a point on the arc, belonging to neither face that meets there.
     */
    private static Outcome deriveRoundedRectangle(int[] own, int refX, int refY, int[] corner) {
        int[] point = chopboxPoint(own, refX, refY);
        if (point == null) {
            return Outcome.ABSTAIN_REFERENCE_INSIDE_BOX;
        }
        int boxX = own[0] - 1;
        int boxY = own[1] - 1;
        int boxW = own[2] + 1;
        int boxH = own[3] + 1;
        int xLeft = boxX + corner[0] / 2;
        int xRight = boxX + boxW - corner[0] / 2;
        int yTop = boxY + corner[1] / 2;
        int yBottom = boxY + boxH - corner[1] / 2;

        boolean xMiddle = point[0] >= xLeft && point[0] <= xRight;
        boolean yCentre = point[1] >= yTop && point[1] <= yBottom;

        if (xMiddle && yCentre) {
            // The anchor's own comment calls this the case for a reference inside the figure; it
            // answers with a point on the left edge, which is an artefact of the degenerate arm
            // rather than a face this connection is drawn leaving.
            return Outcome.ABSTAIN_REFERENCE_INSIDE_BOX;
        }
        if (!xMiddle && !yCentre) {
            return Outcome.ABSTAIN_ROUNDED_CORNER_ARC;
        }
        return deriveChopbox(own, refX, refY);
    }
}
