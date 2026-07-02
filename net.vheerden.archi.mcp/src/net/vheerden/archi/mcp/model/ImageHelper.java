package net.vheerden.archi.mcp.model;

import java.util.List;

import org.eclipse.swt.graphics.ImageData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
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

    // ---- Coverage calculation ----

    /**
     * Calculates the percentage of element area covered by the image at native size.
     *
     * <p><strong>Note:</strong> Assumes 1:1 mapping between image pixels and Archi logical
     * units. On HiDPI displays, ImageData may report physical pixels (2x), which would
     * overestimate coverage. Acceptable for advisory warnings on typical 16x16 icons.</p>
     *
     * @param imageWidth   image width in pixels
     * @param imageHeight  image height in pixels
     * @param elementWidth element width in logical units
     * @param elementHeight element height in logical units
     * @return coverage percentage (0.0 to 100.0+)
     */
    static double calculateCoverage(int imageWidth, int imageHeight,
                                     int elementWidth, int elementHeight) {
        if (elementWidth <= 0 || elementHeight <= 0) return 0.0;
        if (imageWidth <= 0 || imageHeight <= 0) return 0.0;
        return ((double) imageWidth * imageHeight / ((double) elementWidth * elementHeight)) * 100.0;
    }

    /**
     * Returns a warning message if coverage exceeds 25%, otherwise null.
     */
    static String coverageWarning(double coveragePercent) {
        if (coveragePercent > 25.0) {
            return String.format(
                "Image covers %.1f%% of element area — may obscure element name. "
                    + "Consider using a smaller image or 'fill' position.",
                coveragePercent);
        }
        return null;
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
