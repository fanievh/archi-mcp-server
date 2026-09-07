package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.graphics.ImageData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIconic;
import com.archimatetool.model.IProfile;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Stateless image validation, read, apply, and coverage helpers.
 *
 * <p>Extracted following the {@link StylingHelper} pattern to keep image-related
 * EMF logic in one place. Package-visible — only ArchiModelAccessorImpl and
 * UpdateViewObjectCommand should use this class.</p>
 */
final class ImageHelper {

    private ImageHelper() {}

    private static final Logger logger = LoggerFactory.getLogger(ImageHelper.class);

    /**
     * Icon-band size in px reserved at a corner of a container Node that
     * carries both a corner-anchored icon AND nested children, so the icon
     * cannot visually collide with a child. Sized as
     * {@code 16 (icon) + 8 (margin) = 24}, by parity with
     * {@code GROUP_LABEL_HEIGHT = 24} at
     * {@code ArchiModelAccessorImpl :8955} — same "reserve room for the
     * thing that must render here" idiom, different edge.
     *
     * <p>The symmetric
     * cousin of the text-band reservation
     * (commit {@code 979ca76}, 2026-05-20).</p>
     */
    static final int ICON_BAND_HEIGHT = 24;

    /** Icon px size of Archi's cloud-icon family — the first term of {@link #ICON_BAND_HEIGHT}. */
    static final int ICON_SIZE = 16;

    /** Safety margin between the icon and an adjacent child — the second term. */
    static final int ICON_MARGIN = 8;

    /**
     * The one image position Archi scales to the element box instead of drawing at natural
     * size and clipping. Kebab-case, matching {@link ImageParams#positionToString}.
     */
    private static final String FILL_POSITION = "fill";

    /**
     * Validates image parameters, throwing {@link ModelAccessException} on invalid values.
     */
    static void validateImageParams(ImageParams imageParams) {
        if (imageParams.imagePosition() != null) {
            try {
                ImageParams.positionToInt(imageParams.imagePosition());
            } catch (IllegalArgumentException e) {
                throw new ModelAccessException(
                    "Invalid image position: '" + imageParams.imagePosition() + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Valid positions: top-left, top-centre, top-right, middle-left, middle-centre, "
                        + "middle-right, bottom-left, bottom-centre, bottom-right, fill",
                    null);
            }
        }
        if (imageParams.showIcon() != null) {
            try {
                ImageParams.showIconToInt(imageParams.showIcon());
            } catch (IllegalArgumentException e) {
                throw new ModelAccessException(
                    "Invalid showIcon value: '" + imageParams.showIcon() + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Valid showIcon values: if-no-image, always, never",
                    null);
            }
        }
    }

    /**
     * Applies image parameters to a newly created view object (at creation time).
     * For ArchiMate elements, automatically sets imageSource to CUSTOM when imagePath is set.
     */
    static void applyImageToNewObject(IDiagramModelObject diagramObj, ImageParams imageParams) {
        if (imageParams == null || !imageParams.hasAnyValue()) return;

        validateImageParams(imageParams);

        if (!(diagramObj instanceof IIconic iconic)) return;

        if (imageParams.imagePath() != null) {
            iconic.setImagePath(imageParams.imagePath().isEmpty() ? null : imageParams.imagePath());
            toggleImageSource(diagramObj, imageParams.imagePath());
        }
        if (imageParams.imagePosition() != null) {
            iconic.setImagePosition(ImageParams.positionToInt(imageParams.imagePosition()));
        }
        if (imageParams.showIcon() != null) {
            applyShowIcon(diagramObj, ImageParams.showIconToInt(imageParams.showIcon()));
        }
    }

    // ---- Read image state from EMF objects ----

    /**
     * Reads the image path from a view object, or null if no custom image.
     */
    static String readImagePath(IDiagramModelObject obj) {
        if (obj instanceof IIconic iconic) {
            return iconic.getImagePath();
        }
        return null;
    }

    /**
     * Reads the image position as a kebab-case string, or null if default (top-right).
     */
    static String readImagePosition(IDiagramModelObject obj) {
        if (obj instanceof IIconic iconic) {
            int pos = iconic.getImagePosition();
            // top-right (2) is the Archi default — omit from DTO for sparse response
            if (pos == 2) return null;
            return ImageParams.positionToString(pos);
        }
        return null;
    }

    /**
     * Reads the image position as an int constant.
     */
    static int readImagePositionInt(IDiagramModelObject obj) {
        if (obj instanceof IIconic iconic) {
            return iconic.getImagePosition();
        }
        return 2; // default: top-right
    }

    /**
     * Reads the specialization-icon image path displayed for an ArchiMate element
     * whose image comes from its profile (rather than a custom image), or null.
     *
     * <p>A specialization image lives on the element's {@link IProfile}, not on the
     * diagram object, so {@link #readImagePath} returns null for it. When the object's
     * image source is the profile, the displayed icon is the first profile that
     * carries an image. Returns null for custom-image or image-less objects, and for
     * non-ArchiMate objects.</p>
     *
     * <p>Note: the profile image source is the EMF default, so most ordinary elements
     * pass the source check; the effective gate is the per-profile non-empty image-path
     * test below. Together these match exactly when Archi renders the profile icon —
     * source is profile AND a profile actually carries an image — so resolving that path
     * reflects what is on screen.</p>
     */
    static String readProfileImagePath(IDiagramModelObject obj) {
        if (!(obj instanceof IDiagramModelArchimateObject archiObj)) return null;
        if (archiObj.getImageSource() != IDiagramModelArchimateObject.IMAGE_SOURCE_PROFILE) {
            return null;
        }
        IArchimateConcept concept = archiObj.getArchimateConcept();
        if (concept == null) return null;
        for (IProfile profile : concept.getProfiles()) {
            String path = profile.getImagePath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
        }
        return null;
    }

    /**
     * Reads the natural (archive) pixel dimensions {@code {width, height}} of an
     * image, or null when unavailable (no archive manager — e.g. headless — or the
     * image bytes cannot be decoded). Used to size an element's image rect from
     * what actually renders instead of a fixed icon assumption.
     */
    static int[] readNaturalImageDimensions(IArchimateModel model, String imagePath) {
        if (model == null || imagePath == null) return null;
        try {
            IArchiveManager archiveManager =
                    (IArchiveManager) model.getAdapter(IArchiveManager.class);
            if (archiveManager == null) return null;
            ImageData data = archiveManager.createImageData(imagePath);
            if (data == null) return null;
            return new int[] { data.width, data.height };
        } catch (Exception e) {
            logger.debug("Failed to read natural dimensions for image '{}': {}",
                    imagePath, e.getMessage());
            return null;
        }
    }

    /**
     * Reads the showIcon value as a kebab-case string, or null if default (if-no-image).
     * Available on all IDiagramModelObject types (not just ArchiMate elements).
     */
    static String readShowIcon(IDiagramModelObject obj) {
        int val = obj.getIconVisibleState();
        // if-no-image (0) is the default — omit from DTO
        if (val == 0) return null;
        return ImageParams.showIconToString(val);
    }

    /**
     * Reads the showIcon value as an int constant.
     */
    static int readShowIconInt(IDiagramModelObject obj) {
        return obj.getIconVisibleState();
    }

    // ---- Post-execution image fields for DTO construction ----

    /**
     * The image path the object will hold once the requested update has executed: the requested
     * value when one was given, the persisted value otherwise. An empty requested path is the
     * clear sentinel and reports as null.
     *
     * <p>Lives beside the readers it defers to rather than on the caller, so both update prepares
     * describe a post-update image the same way and neither can drift from the other.</p>
     */
    static String computePostImagePath(IDiagramModelObject diagramObj, ImageParams imageParams) {
        if (imageParams == null || imageParams.imagePath() == null) {
            return readImagePath(diagramObj);
        }
        return imageParams.imagePath().isEmpty() ? null : imageParams.imagePath();
    }

    /** As {@link #computePostImagePath}, for the image position. */
    static String computePostImagePosition(IDiagramModelObject diagramObj, ImageParams imageParams) {
        if (imageParams == null || imageParams.imagePosition() == null) {
            return readImagePosition(diagramObj);
        }
        return imageParams.imagePosition();
    }

    /** As {@link #computePostImagePath}, for the icon visibility. */
    static String computePostShowIcon(IDiagramModelObject diagramObj, ImageParams imageParams) {
        if (imageParams == null || imageParams.showIcon() == null) {
            return readShowIcon(diagramObj);
        }
        return imageParams.showIcon();
    }

    // ---- Coverage calculation ----

    /**
     * Drawn-coverage percentage above which the image is likely to obscure the element
     * name. A legibility threshold applied to a legibility measurement: coverage counts
     * only pixels Archi actually paints inside the element box, so a quarter of the box
     * being covered means a quarter of the label area is genuinely at risk. Compared
     * exclusively — exactly this value does not warn.
     */
    private static final double COVERAGE_WARNING_THRESHOLD_PERCENT = 25.0;

    /**
     * Calculates the percentage of the element's area that the image actually paints.
     *
     * <p>Archi <strong>clips</strong> an image to the element box — an image larger than
     * its element is cut off at the box edge, never drawn outside it. The rendered area is
     * therefore the intersection of the image rectangle with the element box, which for
     * every anchored position is {@code min(imageWidth, elementWidth) ×
     * min(imageHeight, elementHeight)}. That holds on each axis independently: an anchored
     * image is placed flush against an edge (giving an overlap of {@code min(image, element)}
     * directly) or centred on that axis, where the overlap of {@code [c - i/2, c + i/2]} with
     * the box of extent {@code e} centred on the same {@code c} is likewise
     * {@code min(i, e)}. So the anchor shifts <em>where</em> the intersection sits but never
     * its size, and the result is capped at 100.0 by construction.</p>
     *
     * <p>A {@code fill} image is scaled to the element box rather than clipped, so it covers
     * exactly 100% whatever its natural size. Any other position — <em>including {@code null}</em>,
     * which denotes the Archi default of top-right rather than an unknown position — is treated
     * as anchored.</p>
     *
     * <p>The degenerate guards below are deliberately evaluated <em>before</em> the position is
     * considered, so a non-positive image or element dimension yields 0.0 for every position
     * including {@code fill}: with no decodable image, or no box to draw it in, nothing renders
     * and 100% would be a false claim. {@code fill} means "scaled to the box", not
     * "assumed present".</p>
     *
     * <p>Because the value is capped, "the image is bigger than its element and will be cut
     * off" is no longer expressible as a coverage above 100%; that fact is reported separately
     * via {@link #exceedsElement} and surfaced in {@link #coverageWarning}.</p>
     *
     * <p><strong>Note:</strong> Assumes 1:1 mapping between image pixels and Archi logical
     * units. On HiDPI displays, ImageData may report physical pixels (2x), which would
     * overestimate coverage. Acceptable for advisory warnings on typical 16x16 icons.</p>
     *
     * @param imageWidth   image width in pixels
     * @param imageHeight  image height in pixels
     * @param elementWidth element width in logical units
     * @param elementHeight element height in logical units
     * @param imagePosition kebab-case image position; {@code "fill"} scales to the box, any
     *                      other value (including null = the top-right default) is anchored
     * @return drawn coverage percentage (0.0 to 100.0 inclusive)
     */
    static double calculateCoverage(int imageWidth, int imageHeight,
                                     int elementWidth, int elementHeight,
                                     String imagePosition) {
        if (elementWidth <= 0 || elementHeight <= 0) return 0.0;
        if (imageWidth <= 0 || imageHeight <= 0) return 0.0;
        if (FILL_POSITION.equals(imagePosition)) return 100.0;
        double drawnWidth = Math.min(imageWidth, elementWidth);
        double drawnHeight = Math.min(imageHeight, elementHeight);
        return (drawnWidth * drawnHeight / ((double) elementWidth * elementHeight)) * 100.0;
    }

    /**
     * Calculates drawn coverage for an anchored image — equivalent to
     * {@link #calculateCoverage(int, int, int, int, String)} with a null position.
     */
    static double calculateCoverage(int imageWidth, int imageHeight,
                                     int elementWidth, int elementHeight) {
        return calculateCoverage(imageWidth, imageHeight, elementWidth, elementHeight, null);
    }

    /**
     * Returns true when the image is larger than its element on either axis and will
     * therefore be rendered cut off.
     *
     * <p>Independent of drawn coverage: an image can exceed its element while covering only
     * a small fraction of it (a tall narrow image on a short wide box), and can cover a large
     * fraction without exceeding it. A {@code fill} image is scaled rather than clipped, so it
     * never exceeds its element regardless of its natural size.</p>
     */
    static boolean exceedsElement(int imageWidth, int imageHeight,
                                   int elementWidth, int elementHeight,
                                   String imagePosition) {
        if (elementWidth <= 0 || elementHeight <= 0) return false;
        if (imageWidth <= 0 || imageHeight <= 0) return false;
        if (FILL_POSITION.equals(imagePosition)) return false;
        return imageWidth > elementWidth || imageHeight > elementHeight;
    }

    /**
     * Returns an advisory message about the image's fit, or null when there is nothing to
     * report. Two independent conditions are reported, separately or together:
     * <ul>
     *   <li><em>legibility</em> — drawn coverage above
     *       {@link #COVERAGE_WARNING_THRESHOLD_PERCENT}, so the image may obscure the name;</li>
     *   <li><em>fidelity</em> — the image is larger than its element and will be cut off.</li>
     * </ul>
     *
     * <p>Neither implies the other. An oversized image anchored on a short element is cut off
     * while covering only a small part of it: fidelity fires, legibility does not.</p>
     *
     * <p>The legibility <em>remedy</em> depends on the position, because under {@code fill} the
     * usual advice is inert. A {@code fill} image is scaled to the element box rather than
     * anchored and clipped, so its natural size has no effect on what is drawn — suggesting a
     * smaller image would change nothing, and suggesting {@code fill} to a caller already at
     * {@code fill} is circular. Repositioning the label is equally inert there: the image covers
     * the whole box, so no label position escapes it. Reducing the object's opacity does not
     * work either — that value is the figure's fill alpha and is not applied to a custom image.</p>
     *
     * <p>The two remedies offered under {@code fill} are deliberately of different kinds, and the
     * message says which is which. Because coverage under {@code fill} is unconditionally 100%,
     * <strong>leaving {@code fill} is the only action that can clear this warning</strong>;
     * lowering the image's contrast genuinely improves legibility but changes no dimension, so the
     * warning still fires afterwards. Saying so is the point: an agent told only to "use a lighter
     * image" would swap images and re-check forever, waiting for a warning that cannot clear —
     * the same undischargeable-advisory trap this branch exists to remove.</p>
     *
     * @param imagePosition kebab-case image position; {@code "fill"} selects the scaled-image
     *                      remedies, any other value (including null = the top-right default)
     *                      is anchored
     */
    static String coverageWarning(double coveragePercent, boolean exceedsElement,
                                   String imagePosition) {
        boolean mayObscure = coveragePercent > COVERAGE_WARNING_THRESHOLD_PERCENT;
        if (!mayObscure && !exceedsElement) return null;
        StringBuilder message = new StringBuilder();
        if (mayObscure) {
            message.append(String.format(
                FILL_POSITION.equals(imagePosition)
                    ? "Image covers %.1f%% of element area — may obscure element name. "
                        + "'fill' scales the image to the element box, so its natural size is "
                        + "irrelevant and this warning persists for as long as 'fill' is set: "
                        + "switch to an anchored imagePosition with a small icon to clear it, "
                        + "or keep the scaled image and use a lighter, lower-contrast one so "
                        + "the label reads over it."
                    : "Image covers %.1f%% of element area — may obscure element name. "
                        + "Consider using a smaller image or 'fill' position.",
                coveragePercent));
        }
        if (exceedsElement) {
            if (message.length() > 0) message.append(' ');
            // No "only %.1f%%" here: an image that overruns one axis while matching the other
            // exactly is cut off AND draws 100% of the box, and "only 100.0%" reads as a
            // contradiction. State the clipping as its own fact rather than qualifying it
            // with a figure that does not always support it.
            message.append("Image is larger than its element and will be rendered cut off at "
                + "the element edge. Consider a smaller image, a larger element, or 'fill' "
                + "position to scale the image to the box.");
        }
        return message.toString();
    }

    /**
     * Returns the advisory for an image at an anchored position — equivalent to
     * {@link #coverageWarning(double, boolean, String)} with a null position.
     *
     * <p>Anchored is the correct default: {@code null} denotes the Archi top-right default, not
     * an unknown position. A {@code fill} image must NOT reach this overload, or it will be
     * advised to use a smaller image and to switch to {@code fill} — both no-ops under
     * {@code fill}. Production callers reach the three-argument form via
     * {@link #coverageReport}.</p>
     */
    static String coverageWarning(double coveragePercent, boolean exceedsElement) {
        return coverageWarning(coveragePercent, exceedsElement, null);
    }

    /**
     * Returns a legibility-only warning, for an image already known to FIT its element.
     *
     * <p><strong>Not for production use — call {@link #coverageReport} instead.</strong> This
     * overload hard-codes {@code exceedsElement = false}, so on an oversized image it silently
     * omits the "will be rendered cut off" clause and under-reports. It takes a coverage figure
     * with no way to check that assumption, and the compiler cannot warn when the assumption is
     * wrong. {@link #coverageReport} derives both facts from the same dimensions and cannot
     * disagree with itself; every production path goes through it.</p>
     */
    static String coverageWarning(double coveragePercent) {
        return coverageWarning(coveragePercent, false);
    }

    /**
     * The drawn coverage percentage and its advisory warning for one image on one element.
     * Both fields are null in {@link #NONE}, used when the image dimensions cannot be read.
     */
    record CoverageReport(Double percent, String warning) {
        static final CoverageReport NONE = new CoverageReport(null, null);
    }

    /**
     * Builds the coverage report for an image of the given natural size on an element of the
     * given size. The percentage is rounded to one decimal first, so the number reported to
     * the caller and the number quoted in the warning are always the same value.
     */
    static CoverageReport coverageReport(int imageWidth, int imageHeight,
            int elementWidth, int elementHeight, String imagePosition) {
        double coverage = calculateCoverage(imageWidth, imageHeight,
                elementWidth, elementHeight, imagePosition);
        double rounded = Math.round(coverage * 10.0) / 10.0;
        return new CoverageReport(rounded, coverageWarning(rounded,
                exceedsElement(imageWidth, imageHeight, elementWidth, elementHeight, imagePosition),
                imagePosition));
    }

    // ---- Copy helpers (clone-view) ----

    /**
     * Copies image properties from source to target view object.
     * Handles imagePath, imagePosition, showIcon, and imageSource.
     */
    static void copyImageProperties(IDiagramModelObject source, IDiagramModelObject target) {
        // Image path and position (IIconic)
        if (source instanceof IIconic srcIconic && target instanceof IIconic tgtIconic) {
            String imagePath = srcIconic.getImagePath();
            if (imagePath != null && !imagePath.isEmpty()) {
                tgtIconic.setImagePath(imagePath);
                toggleImageSource(target, imagePath);
            }
            tgtIconic.setImagePosition(srcIconic.getImagePosition());
        }

        // Show icon state (available on all IDiagramModelObject)
        target.setIconVisibleState(source.getIconVisibleState());
    }

    // ---- Reserved icon-band geometry ----

    /**
     * Returns the inset (in px) that a container Node should reserve at the
     * specified image-position corner to accommodate a corner-anchored icon,
     * or 0 when the position is not a corner.
     *
     * <p>Pure geometry — no SWT, no EMF — directly unit-testable from
     * {@code ImageHelperTest} without an Archi runtime (test-seam).</p>
     *
     * <p>Returns {@code iconSize + margin} for the four corner positions
     * (0=top-left, 2=top-right, 6=bottom-left, 8=bottom-right per
     * {@link ImageParams#positionToInt}); returns 0 for the six non-corner
     * positions (1, 3, 4, 5, 7, and fill=9) and any unrecognised int.</p>
     *
     * <p><strong>Note:</strong> this method is pure-geometry and returns
     * non-zero for all four corners including {@code 2} (top-right). The
     * accessor-layer wiring in {@code ArchiModelAccessorImpl} restricts
     * <em>firing</em> the lever to non-default corners (i.e. excluding the
     * Archi default at 2) to preserve byte-identical bounds for the
     * "no explicit image position" case (Case A).</p>
     *
     * @param imagePositionInt {@link ImageParams} position int (0..9)
     * @param iconSize         icon size in px (typically 16)
     * @param margin           safety margin in px (typically 8)
     * @return {@code iconSize + margin} for corners; 0 otherwise
     */
    static int reservedIconBandForCorner(int imagePositionInt, int iconSize, int margin) {
        if (imagePositionInt == 0      // top-left
                || imagePositionInt == 2   // top-right
                || imagePositionInt == 6   // bottom-left
                || imagePositionInt == 8) { // bottom-right
            return iconSize + margin;
        }
        return 0;
    }

    /**
     * Returns true iff at least one child rectangle intersects the icon-band
     * rectangle for the supplied corner. This is the Case B predicate
     * — the tightest non-vacuous gate that fires only when an icon
     * would actually collide with a child.
     *
     * <p>Pure geometry — no SWT, no EMF — directly unit-testable from
     * {@code ImageHelperTest} (test-seam).</p>
     *
     * <p>Icon-band rectangle (relative-to-parent, all in px; let
     * {@code band = iconSize + margin}):</p>
     * <ul>
     *   <li>top-left     (0): {@code (0,         0,         band, band)}</li>
     *   <li>top-right    (2): {@code (parentW-band, 0,      band, band)}</li>
     *   <li>bottom-left  (6): {@code (0,         parentH-band, band, band)}</li>
     *   <li>bottom-right (8): {@code (parentW-band, parentH-band, band, band)}</li>
     * </ul>
     *
     * @param parentW             parent width in px
     * @param parentH             parent height in px
     * @param imagePositionInt    {@link ImageParams} position int (0..9)
     * @param iconSize            icon size in px
     * @param margin              safety margin in px
     * <p><strong>Half-open interval semantics:</strong> the intersection test
     * is strict — a child whose edge exactly <em>touches</em> the band's edge
     * (e.g. {@code child.x + child.width == band.x}) is treated as NO overlap,
     * matching standard raster-display conventions where touching edges do
     * not visibly collide. Only a genuine <em>interior</em> overlap (at least
     * one shared pixel) triggers the predicate.</p>
     *
     * @param childRectsRelative  each {@code int[]} is {@code [x, y, w, h]}
     *                            relative-to-parent; null or empty returns false
     * @return true if any child rectangle has an interior overlap with the
     *         icon-band rectangle (any shared pixel counts);
     *         false otherwise (or when the position is not a corner, or when
     *         a child edge exactly touches the band edge without intruding)
     */
    static boolean anyChildOccupiesIconBand(int parentW, int parentH,
            int imagePositionInt, int iconSize, int margin,
            List<int[]> childRectsRelative) {
        if (childRectsRelative == null || childRectsRelative.isEmpty()) return false;
        int band = iconSize + margin;
        int bandX;
        int bandY;
        switch (imagePositionInt) {
            case 0: // top-left
                bandX = 0;
                bandY = 0;
                break;
            case 2: // top-right
                bandX = parentW - band;
                bandY = 0;
                break;
            case 6: // bottom-left
                bandX = 0;
                bandY = parentH - band;
                break;
            case 8: // bottom-right
                bandX = parentW - band;
                bandY = parentH - band;
                break;
            default:
                return false; // non-corner
        }
        for (int[] r : childRectsRelative) {
            if (r == null || r.length < 4) continue;
            int cx = r[0];
            int cy = r[1];
            int cw = r[2];
            int ch = r[3];
            // Standard rectangle-intersection test — any overlap counts.
            boolean noOverlap = cx + cw <= bandX
                    || bandX + band <= cx
                    || cy + ch <= bandY
                    || bandY + band <= cy;
            if (!noOverlap) return true;
        }
        return false;
    }

    /**
     * Returns a child's icon-corner position int when the child carries a
     * (non-empty) custom image, or {@code -1} when it has no such image.
     *
     * <p>Used to thread a nested child's OWN icon corner into
     * {@link #anySameCornerIconChildOccupiesIconBand} so the same-corner
     * collision gate can distinguish a child that actually renders a
     * corner icon from one whose stored image position is merely the
     * default sentinel with no image. Valid corner returns are 0/2/6/8 (and
     * the six non-corner ints 1/3/4/5/7/9); {@code -1} means "no icon".</p>
     *
     * @param obj a diagram object (may or may not be {@link IIconic})
     * @return the icon position int (0..9) if the object carries a non-empty
     *         image path, otherwise {@code -1}
     */
    static int iconCornerOrNone(IDiagramModelObject obj) {
        if (obj instanceof IIconic iconic) {
            String path = iconic.getImagePath();
            if (path != null && !path.isEmpty()) {
                return iconic.getImagePosition();
            }
        }
        return -1;
    }

    /**
     * Returns true iff at least one child BOTH (a) has its rectangle occupying
     * the parent's icon band for {@code imagePositionInt} AND (b) carries its
     * OWN icon anchored to the SAME corner.
     *
     * <p>This is the residual-collision gate that {@link #anyChildOccupiesIconBand}
     * (the rectangle-occupancy predicate) does not cover: after the parent grows
     * by one {@link #ICON_BAND_HEIGHT} to push a child <em>rectangle</em> clear of
     * the icon corner, a child that carries its own same-corner icon still leaves
     * that icon's tile within a tile-height of the parent's icon tile, so the two
     * icons visually collide. Callers reserve one ADDITIONAL band when this fires
     * (2× total), which separates the two icon tiles by a full band.</p>
     *
     * <p>Pure geometry — no SWT, no EMF (test-seam). Fires only when the child's
     * icon corner equals the parent's icon corner (same-corner gate): a
     * child whose icon is at a different corner, or which carries no icon
     * ({@code childIconPositionInt == -1}), is ignored and left byte-identical.</p>
     *
     * @param parentW             parent width in px
     * @param parentH             parent height in px
     * @param imagePositionInt    parent {@link ImageParams} position int (0..9)
     * @param iconSize            icon size in px
     * @param margin              safety margin in px
     * @param childRectsWithIconPos each {@code int[]} is
     *        {@code [x, y, w, h, childIconPositionInt]} relative-to-parent, where
     *        {@code childIconPositionInt} is the child's icon corner int or a
     *        value {@code != imagePositionInt} (e.g. {@code -1}) when the child
     *        carries no same-corner icon. Arrays shorter than 5 are treated as
     *        carrying no icon. Null or empty returns false.
     * @return true iff some child occupies the band AND shares the parent icon corner
     */
    static boolean anySameCornerIconChildOccupiesIconBand(int parentW, int parentH,
            int imagePositionInt, int iconSize, int margin,
            List<int[]> childRectsWithIconPos) {
        if (childRectsWithIconPos == null || childRectsWithIconPos.isEmpty()) return false;
        // Only the four corners define an icon band; non-corner parents never fire.
        if (reservedIconBandForCorner(imagePositionInt, iconSize, margin) == 0) return false;
        for (int[] r : childRectsWithIconPos) {
            if (r == null || r.length < 5) continue;
            if (r[4] != imagePositionInt) continue; // different corner / no icon
            // Reuse the rectangle-occupancy predicate for THIS child alone.
            if (anyChildOccupiesIconBand(parentW, parentH, imagePositionInt,
                    iconSize, margin, List.of(new int[] {r[0], r[1], r[2], r[3]}))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the {@code [x, y, w, h, iconCorner]} child-rect list the icon-band
     * reservation gates consume, from a container's children — one place so the
     * three accessor fire moments (creation + two mutation paths) stay in sync.
     * The 5th element is each child's OWN icon corner via {@link #iconCornerOrNone}
     * ({@code -1} when the child carries no image).
     */
    static List<int[]> iconBandChildRects(List<? extends IDiagramModelObject> children) {
        List<int[]> rects = new ArrayList<>();
        for (IDiagramModelObject c : children) {
            IBounds b = c.getBounds();
            rects.add(new int[] {b.getX(), b.getY(), b.getWidth(), b.getHeight(),
                    iconCornerOrNone(c)});
        }
        return rects;
    }

    /**
     * Total px a container should reserve (grow its height by) at its icon
     * corner so a corner-anchored icon does not visually collide with nested
     * children — consolidating the two gates so accessor call sites add a
     * single value:
     * <ul>
     *   <li>{@code 0} when no child rectangle occupies the icon band (or the
     *       position is not a corner) — nothing to reserve, byte-identical;</li>
     *   <li>{@link #ICON_BAND_HEIGHT} when a child <em>rectangle</em> occupies
     *       the band (clears the child rectangle);</li>
     *   <li>{@code 2 ×} {@link #ICON_BAND_HEIGHT} when an occupying child also
     *       carries its OWN same-corner icon — the extra band separates the
     *       child's icon tile from the container's icon tile by a full band, so
     *       the two icons do not read as one.</li>
     * </ul>
     *
     * <p>Pure geometry — no SWT, no EMF (test-seam). Callers pass child rects as
     * {@code [x, y, w, h, childIconPositionInt]} (see
     * {@link #anySameCornerIconChildOccupiesIconBand}); a child with no
     * same-corner icon uses {@code -1} (or omits the 5th element) and never
     * trips the second band.</p>
     */
    static int iconBandReservePx(int parentW, int parentH, int imagePositionInt,
            int iconSize, int margin, List<int[]> childRectsWithIconPos) {
        if (!anyChildOccupiesIconBand(parentW, parentH, imagePositionInt,
                iconSize, margin, childRectsWithIconPos)) {
            return 0;
        }
        int reserve = ICON_BAND_HEIGHT;
        if (anySameCornerIconChildOccupiesIconBand(parentW, parentH, imagePositionInt,
                iconSize, margin, childRectsWithIconPos)) {
            reserve += ICON_BAND_HEIGHT;
        }
        return reserve;
    }

    /**
     * Returns the height a container must grow to so that a newly requested corner icon does not
     * collide with the children already occupying that corner — the icon-band reservation at the
     * MUTATION moment, applied identically on both {@code update-view-object} paths.
     *
     * <p>Short-circuits to the height it was given whenever nothing is staged: no image params, no
     * requested position, a target that cannot carry an icon or hold children, a position that is
     * not a bottom corner, or an empty corner. Top corners are excluded because clearing them would
     * require shifting children, and top-right is Archi's default sentinel rather than a choice.</p>
     *
     * <p>The two update paths — the primary prepare and the batch back-reference prepare — used to
     * carry byte-identical copies of this gate. Sharing one implementation is what stops them
     * drifting on <em>when</em> the band fires, which is the part a caller cannot see from its own
     * side.</p>
     *
     * @param target      the object being updated
     * @param imageParams the staged image params, may be null
     * @param mergedWidth  the post-merge width
     * @param mergedHeight the post-merge height
     * @param iconSize    the icon tile size in px
     * @param margin      the icon margin in px
     * @return the grown height, or {@code mergedHeight} unchanged when the band does not fire
     */
    static int iconBandGrownHeight(IDiagramModelObject target, ImageParams imageParams,
            int mergedWidth, int mergedHeight, int iconSize, int margin) {
        if (imageParams == null || imageParams.imagePosition() == null
                || !(target instanceof IIconic)
                || !(target instanceof IDiagramModelContainer container)) {
            return mergedHeight;
        }
        int requestedPos = ImageParams.positionToInt(imageParams.imagePosition());
        if (requestedPos != 6 && requestedPos != 8) {
            return mergedHeight;
        }
        // 5th rect element = each child's OWN icon corner, so a child's same-corner icon adds the
        // second reserved band.
        List<int[]> childRects = iconBandChildRects(container.getChildren());
        int reserve = iconBandReservePx(mergedWidth, mergedHeight,
                requestedPos, iconSize, margin, childRects);
        return mergedHeight + reserve;
    }

    // ---- Private helpers ----

    /**
     * For ArchiMate elements, toggles imageSource between CUSTOM (1) and PROFILE (0).
     */
    private static void toggleImageSource(IDiagramModelObject diagramObj, String imagePath) {
        if (diagramObj instanceof IDiagramModelArchimateObject archiObj) {
            if (imagePath != null && !imagePath.isEmpty()) {
                archiObj.setImageSource(IDiagramModelArchimateObject.IMAGE_SOURCE_CUSTOM);
            } else {
                archiObj.setImageSource(IDiagramModelArchimateObject.IMAGE_SOURCE_PROFILE);
            }
        }
    }

    /**
     * Applies the showIcon value. Available on all IDiagramModelObject types.
     */
    private static void applyShowIcon(IDiagramModelObject diagramObj, int showIconValue) {
        diagramObj.setIconVisibleState(showIconValue);
    }
}
