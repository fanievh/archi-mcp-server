package net.vheerden.archi.mcp.model;

import java.util.regex.Pattern;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFontAttribute;
import com.archimatetool.model.ILineObject;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Stateless styling validation, read, and post-computation helpers.
 *
 * <p>Extracted from ArchiModelAccessorImpl (Story 12-4) to improve cohesion.
 * Package-visible — only ArchiModelAccessorImpl should use this class.</p>
 */
final class StylingHelper {

    private StylingHelper() {}

    private static final Pattern HEX_COLOR_PATTERN =
            Pattern.compile("#[0-9A-Fa-f]{6}");

    static void validateStylingParams(StylingParams styling) {
        validateHexColor(styling.fillColor(), "fillColor");
        validateHexColor(styling.lineColor(), "lineColor");
        validateHexColor(styling.fontColor(), "fontColor");
        if (styling.opacity() != null && (styling.opacity() < 0 || styling.opacity() > 255)) {
            throw new ModelAccessException(
                    "opacity must be between 0 and 255, got: " + styling.opacity(),
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide an opacity value between 0 (fully transparent) and 255 (fully opaque).",
                    null);
        }
        if (styling.lineWidth() != null && (styling.lineWidth() < 1 || styling.lineWidth() > 3)) {
            throw new ModelAccessException(
                    "lineWidth must be between 1 and 3, got: " + styling.lineWidth(),
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a lineWidth of 1 (normal), 2 (medium), or 3 (heavy).",
                    null);
        }
    }

    static void validateConnectionStylingParams(StylingParams styling) {
        validateHexColor(styling.lineColor(), "lineColor");
        validateHexColor(styling.fontColor(), "fontColor");
        if (styling.lineWidth() != null && (styling.lineWidth() < 1 || styling.lineWidth() > 3)) {
            throw new ModelAccessException(
                    "lineWidth must be between 1 and 3, got: " + styling.lineWidth(),
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a lineWidth of 1 (normal), 2 (medium), or 3 (heavy).",
                    null);
        }
    }

    static void validateHexColor(String value, String fieldName) {
        if (value == null || value.isEmpty()) return;
        if (!HEX_COLOR_PATTERN.matcher(value).matches()) {
            throw new ModelAccessException(
                    "Invalid " + fieldName + " format: '" + value + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a hex colour in #RRGGBB format (e.g., '#FF6600') or empty string to clear.",
                    null);
        }
    }

    static void applyStylingToNewObject(IDiagramModelObject diagramObj, StylingParams styling) {
        if (styling == null || !styling.hasAnyValue()) return;

        validateStylingParams(styling);

        if (styling.fillColor() != null) {
            diagramObj.setFillColor(styling.fillColor().isEmpty() ? null : styling.fillColor());
        }
        if (styling.opacity() != null) {
            diagramObj.setAlpha(styling.opacity());
        }
        if (styling.lineColor() != null && diagramObj instanceof ILineObject lo) {
            lo.setLineColor(styling.lineColor().isEmpty() ? null : styling.lineColor());
        }
        if (styling.lineWidth() != null && diagramObj instanceof ILineObject lo) {
            lo.setLineWidth(styling.lineWidth());
        }
        if (styling.fontColor() != null && diagramObj instanceof IFontAttribute fa) {
            fa.setFontColor(styling.fontColor().isEmpty() ? null : styling.fontColor());
        }
    }

    // ---- Read styling from view objects ----

    static String readFillColor(IDiagramModelObject obj) {
        return obj.getFillColor();
    }

    static String readLineColor(IDiagramModelObject obj) {
        if (obj instanceof ILineObject lo) {
            return lo.getLineColor();
        }
        return null;
    }

    static String readFontColor(IDiagramModelObject obj) {
        if (obj instanceof IFontAttribute fa) {
            return fa.getFontColor();
        }
        return null;
    }

    static Integer readOpacity(IDiagramModelObject obj) {
        int alpha = obj.getAlpha();
        return (alpha != 255) ? alpha : null;
    }

    static Integer readLineWidth(IDiagramModelObject obj) {
        if (obj instanceof ILineObject lo) {
            int lw = lo.getLineWidth();
            return (lw != 1) ? lw : null;
        }
        return null;
    }

    // ---- Post-styling computation ----

    static String computePostStylingColor(String currentValue, String stylingValue) {
        if (stylingValue == null) return currentValue;
        return stylingValue.isEmpty() ? null : stylingValue;
    }

    static Integer computePostStylingOpacity(Integer currentValue, Integer stylingValue) {
        if (stylingValue == null) return currentValue;
        return (stylingValue == 255) ? null : stylingValue;
    }

    static Integer computePostStylingLineWidth(Integer currentValue, Integer stylingValue) {
        if (stylingValue == null) return currentValue;
        return (stylingValue == 1) ? null : stylingValue;
    }

    // ---- Connection styling ----

    static String readConnectionLineColor(IDiagramModelArchimateConnection conn) {
        return conn.getLineColor();
    }

    static String readConnectionFontColor(IDiagramModelArchimateConnection conn) {
        return conn.getFontColor();
    }

    static Integer readConnectionLineWidth(IDiagramModelArchimateConnection conn) {
        int lw = conn.getLineWidth();
        return (lw != 1) ? lw : null;
    }
}
