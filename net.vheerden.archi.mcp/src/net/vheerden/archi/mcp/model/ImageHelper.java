package net.vheerden.archi.mcp.model;

import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIconic;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Stateless image validation, read, apply, and coverage helpers (Story C4).
 *
 * <p>Extracted following the {@link StylingHelper} pattern to keep image-related
 * EMF logic in one place. Package-visible — only ArchiModelAccessorImpl and
 * UpdateViewObjectCommand should use this class.</p>
 */
final class ImageHelper {

    private ImageHelper() {}

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

    // ---- Copy helpers (Story C2: clone-view) ----

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
