package net.vheerden.archi.mcp.model;

import java.util.regex.Pattern;

import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBorderType;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFontAttribute;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.ILineObject;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.ITextPosition;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Stateless styling validation, read, and post-computation helpers.
 *
 * <p>Extracted from ArchiModelAccessorImpl to improve cohesion.
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
        validateFigureType(styling.figureType());
        validateTextAlignment(styling.textAlignment());
        validateVerticalTextAlignment(styling.verticalTextAlignment());
        validateFontSize(styling.fontSize());
        validateFontStyle(styling.fontStyle());
        validateLineStyle(styling.lineStyle());
        validateGradient(styling.gradient());
        validateBorderType(styling.borderType());
        validateOutlineOpacity(styling.outlineOpacity());
        // fontName: any non-empty string accepted verbatim (Archi falls back at render time
        // when name is unknown); empty string clears to the system default. No validator.
        // deriveLineColor: Boolean; nothing to validate beyond null/true/false.
    }

    static void validateFigureType(String value) {
        if (value == null || value.isEmpty()) return;
        String normalized = value.toLowerCase();
        if (!normalized.equals("rectangular") && !normalized.equals("tabbed")) {
            throw new ModelAccessException(
                    "Invalid figureType value: '" + value + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide 'rectangular' or 'tabbed'.",
                    null);
        }
    }

    static void validateTextAlignment(String value) {
        if (value == null || value.isEmpty()) return;
        String normalized = value.toLowerCase();
        if (!normalized.equals("left") && !normalized.equals("centre")
                && !normalized.equals("center") && !normalized.equals("right")) {
            throw new ModelAccessException(
                    "Invalid textAlignment value: '" + value + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide 'left', 'centre' (or 'center'), or 'right'.",
                    null);
        }
    }

    static void validateVerticalTextAlignment(String value) {
        if (value == null || value.isEmpty()) return;
        String normalized = value.toLowerCase();
        if (!normalized.equals("top") && !normalized.equals("centre")
                && !normalized.equals("center") && !normalized.equals("bottom")) {
            throw new ModelAccessException(
                    "Invalid verticalTextAlignment value: '" + value + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide 'top', 'centre' (or 'center'), or 'bottom'.",
                    null);
        }
    }

    static int mapFigureTypeToInt(String value) {
        String normalized = value.toLowerCase();
        return switch (normalized) {
            case "rectangular" -> 1;
            case "tabbed" -> 0;
            default -> throw new IllegalArgumentException("Unmapped figureType: " + value);
        };
    }

    static int mapTextAlignmentToInt(String value) {
        String normalized = value.toLowerCase();
        return switch (normalized) {
            case "left" -> ITextAlignment.TEXT_ALIGNMENT_LEFT;
            case "centre", "center" -> ITextAlignment.TEXT_ALIGNMENT_CENTER;
            case "right" -> ITextAlignment.TEXT_ALIGNMENT_RIGHT;
            default -> throw new IllegalArgumentException("Unmapped textAlignment: " + value);
        };
    }

    static int mapVerticalTextAlignmentToInt(String value) {
        String normalized = value.toLowerCase();
        return switch (normalized) {
            case "top" -> ITextPosition.TEXT_POSITION_TOP;
            case "centre", "center" -> ITextPosition.TEXT_POSITION_CENTRE;
            case "bottom" -> ITextPosition.TEXT_POSITION_BOTTOM;
            default -> throw new IllegalArgumentException("Unmapped verticalTextAlignment: " + value);
        };
    }

    /** SWT FontData style bitmask: NORMAL=0, BOLD=1, ITALIC=2, BOLD|ITALIC=3. */
    static int mapFontStyleToInt(String value) {
        return switch (value.toLowerCase()) {
            case "normal" -> 0;
            case "bold" -> 1;
            case "italic" -> 2;
            case "bold-italic" -> 3;
            default -> throw new IllegalArgumentException("Unmapped fontStyle: " + value);
        };
    }

    /** Reverse of mapFontStyleToInt — used by readFontStyle. Returns null for NORMAL (default). */
    static String mapIntToFontStyle(int bits) {
        return switch (bits & 3) {
            case 1 -> "bold";
            case 2 -> "italic";
            case 3 -> "bold-italic";
            default -> null;
        };
    }

    /**
     * Maps the user-facing lineStyle enum to the int stored by
     * {@code IDiagramModelObject.setLineStyle(int)} (empirical correction —
     * lineStyle is a view-object property in Archi 5.8, NOT a connection property;
     * `IDiagramModelConnection.setType()` LINE_DASHED bits do not drive ArchiMate
     * connection figure rendering).
     */
    static int mapLineStyleToInt(String value) {
        return switch (value.toLowerCase()) {
            case "solid" -> IDiagramModelObject.LINE_STYLE_SOLID;   // 0
            case "dashed" -> IDiagramModelObject.LINE_STYLE_DASHED; // 1
            case "dotted" -> IDiagramModelObject.LINE_STYLE_DOTTED; // 2
            case "none" -> IDiagramModelObject.LINE_STYLE_NONE;     // 3
            default -> throw new IllegalArgumentException("Unmapped lineStyle: " + value);
        };
    }

    /**
     * Reverse of mapLineStyleToInt — used by readLineStyle. Returns null for BOTH
     * {@code LINE_STYLE_DEFAULT (-1)} and {@code LINE_STYLE_SOLID (0)} because Archi
     * renders both as a solid line; the DTO field is omitted when the rendered output
     * is the default (solid).
     */
    static String mapIntToLineStyle(int v) {
        if (v == IDiagramModelObject.LINE_STYLE_DEFAULT) return null; // -1 default → null
        return switch (v) {
            case IDiagramModelObject.LINE_STYLE_DASHED -> "dashed";
            case IDiagramModelObject.LINE_STYLE_DOTTED -> "dotted";
            case IDiagramModelObject.LINE_STYLE_NONE -> "none";
            // SOLID (0) is explicitly NULL (renders identical to LINE_STYLE_DEFAULT — DTO omits).
            // Any unrecognised int also falls here (defensive).
            default -> null;
        };
    }

    /**
     * Maps the user-facing gradient enum to the int stored by
     * {@code IDiagramModelObject.setGradient(int)} (Task 0 Finding 4 —
     * decoded from {@code GradientComposite}: combo.selectionIndex - 1,
     * combo items [None, Top, Left, Right, Bottom]).
     */
    static int mapGradientToInt(String value) {
        return switch (value.toLowerCase()) {
            case "none" -> IDiagramModelObject.GRADIENT_NONE; // -1
            case "top-bottom" -> 0; // gradient origin at Top
            case "left-right" -> 1; // gradient origin at Left
            case "right-left" -> 2; // gradient origin at Right
            case "bottom-top" -> 3; // gradient origin at Bottom
            default -> throw new IllegalArgumentException("Unmapped gradient: " + value);
        };
    }

    /** Reverse of mapGradientToInt — used by readGradient. Returns null when at default (-1). */
    static String mapIntToGradient(int v) {
        return switch (v) {
            case 0 -> "top-bottom";
            case 1 -> "left-right";
            case 2 -> "right-left";
            case 3 -> "bottom-top";
            default -> null; // -1 (none, default) or any unexpected value
        };
    }

    /**
     * Maps the user-facing note borderType enum to the int stored by
     * {@code IBorderType.setBorderType(int)} on {@code IDiagramModelNote}.
     * Constants: BORDER_DOGEAR=0 (default), BORDER_RECTANGLE=1, BORDER_NONE=2.
     */
    static int mapBorderTypeToInt(String value) {
        return switch (value.toLowerCase()) {
            case "dogear" -> IDiagramModelNote.BORDER_DOGEAR;
            case "rectangle" -> IDiagramModelNote.BORDER_RECTANGLE;
            case "none" -> IDiagramModelNote.BORDER_NONE;
            default -> throw new IllegalArgumentException("Unmapped borderType: " + value);
        };
    }

    /** Reverse of mapBorderTypeToInt — used by readBorderType. Returns null when at default (dogear). */
    static String mapIntToBorderType(int v) {
        return switch (v) {
            case IDiagramModelNote.BORDER_RECTANGLE -> "rectangle";
            case IDiagramModelNote.BORDER_NONE -> "none";
            default -> null; // BORDER_DOGEAR (0, default) or unexpected
        };
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
        validateFontSize(styling.fontSize());
        validateFontStyle(styling.fontStyle());
        // lineStyle is view-object-only — validated in validateStylingParams.
    }

    static void validateFontSize(Integer value) {
        if (value == null) return;
        if (value <= 0) {
            throw new ModelAccessException(
                    "fontSize must be a positive integer, got: " + value,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide a positive fontSize (e.g., 9, 12, 16).",
                    null);
        }
    }

    static void validateFontStyle(String value) {
        if (value == null || value.isEmpty()) return;
        String normalized = value.toLowerCase();
        if (!normalized.equals("normal") && !normalized.equals("bold")
                && !normalized.equals("italic") && !normalized.equals("bold-italic")) {
            throw new ModelAccessException(
                    "Invalid fontStyle value: '" + value + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide 'normal', 'bold', 'italic', or 'bold-italic'.",
                    null);
        }
    }

    static void validateLineStyle(String value) {
        if (value == null || value.isEmpty()) return;
        String normalized = value.toLowerCase();
        if (!normalized.equals("solid") && !normalized.equals("dashed")
                && !normalized.equals("dotted") && !normalized.equals("none")) {
            throw new ModelAccessException(
                    "Invalid lineStyle value: '" + value + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide 'solid', 'dashed', 'dotted', or 'none'.",
                    null);
        }
    }

    static void validateGradient(String value) {
        if (value == null || value.isEmpty()) return;
        String normalized = value.toLowerCase();
        if (!normalized.equals("none") && !normalized.equals("top-bottom")
                && !normalized.equals("bottom-top") && !normalized.equals("left-right")
                && !normalized.equals("right-left")) {
            throw new ModelAccessException(
                    "Invalid gradient value: '" + value + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide 'none', 'top-bottom', 'bottom-top', 'left-right', or 'right-left'.",
                    null);
        }
    }

    static void validateBorderType(String value) {
        if (value == null || value.isEmpty()) return;
        String normalized = value.toLowerCase();
        if (!normalized.equals("dogear") && !normalized.equals("rectangle")
                && !normalized.equals("none")) {
            throw new ModelAccessException(
                    "Invalid borderType value: '" + value + "'",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide 'dogear', 'rectangle', or 'none'.",
                    null);
        }
    }

    static void validateOutlineOpacity(Integer value) {
        if (value == null) return;
        if (value < 0 || value > 255) {
            throw new ModelAccessException(
                    "outlineOpacity must be between 0 and 255, got: " + value,
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Provide an outlineOpacity value between 0 (fully transparent) and 255 (fully opaque).",
                    null);
        }
    }

    // ---- SWT FontData composite-string parsing ----
    //
    // IFontAttribute.getFont()/setFont(String) round-trips the SWT FontData.toString() format
    // (Task 0 Finding 1). Two on-disk versions exist:
    //   version 0 (legacy): "<name>|<height_int>|<style>" + optional extras (3+ parts, parts[0]=name)
    //   version 1 (modern): "1|<name>|<height_float>|<style>|<platform>|<extra>..." (parts[0]="1")
    //
    // We avoid importing org.eclipse.swt.graphics.FontData into the model layer (architecture
    // boundary discipline). Parsing/assembly is done by splitting on '|' and editing the cells
    // in place, preserving any trailing platform/extra cells verbatim so the round-trip is safe.

    private static final int V1_NAME_IDX = 1;
    private static final int V1_HEIGHT_IDX = 2;
    private static final int V1_STYLE_IDX = 3;
    private static final int V0_NAME_IDX = 0;
    private static final int V0_HEIGHT_IDX = 1;
    private static final int V0_STYLE_IDX = 2;

    /** Returns true if {@code parts[0] == "1"} indicating SWT FontData version 1 format. */
    private static boolean isVersion1(String[] parts) {
        if (parts.length < 4 || parts[0] == null) return false;
        try {
            return Integer.parseInt(parts[0]) == 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Parses the font-family name from a composite font string. Returns null when the
     * composite is null/empty or the name cell is empty (= use Archi's default).
     */
    static String parseFontName(String composite) {
        if (composite == null || composite.isEmpty()) return null;
        String[] parts = composite.split("\\|", -1);
        int idx = isVersion1(parts) ? V1_NAME_IDX : V0_NAME_IDX;
        if (parts.length <= idx) return null;
        String name = parts[idx];
        return (name == null || name.isEmpty()) ? null : name;
    }

    /**
     * Parses the font height from a composite font string as an integer point size.
     * Handles both version-1 float ("9.0") and version-0 int ("9"). Returns null if
     * the composite is null/empty or the height cell is malformed.
     */
    static Integer parseFontSize(String composite) {
        if (composite == null || composite.isEmpty()) return null;
        String[] parts = composite.split("\\|", -1);
        int idx = isVersion1(parts) ? V1_HEIGHT_IDX : V0_HEIGHT_IDX;
        if (parts.length <= idx) return null;
        String cell = parts[idx];
        if (cell == null || cell.isEmpty()) return null;
        try {
            // Float-tolerant int parse: trim a trailing ".0" etc.
            int dot = cell.indexOf('.');
            String intPart = (dot >= 0) ? cell.substring(0, dot) : cell;
            return Integer.parseInt(intPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses the font style from a composite font string. Returns null for NORMAL (default)
     * or when the composite is null/empty. Maps the SWT bitmask via {@link #mapIntToFontStyle}.
     */
    static String parseFontStyle(String composite) {
        if (composite == null || composite.isEmpty()) return null;
        String[] parts = composite.split("\\|", -1);
        int idx = isVersion1(parts) ? V1_STYLE_IDX : V0_STYLE_IDX;
        if (parts.length <= idx) return null;
        String cell = parts[idx];
        if (cell == null || cell.isEmpty()) return null;
        try {
            return mapIntToFontStyle(Integer.parseInt(cell));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Assembles a composite font string from the existing string + any non-null override
     * components. Implements the "merge then write" semantic.
     *
     * <ul>
     *   <li>{@code existing == null/empty}: build a minimal version-0 string with the supplied
     *       components (defaults: name="", height=9, style=0).
     *   <li>{@code existing} has a recognised version: replace only the supplied components in
     *       place, preserving trailing platform/extra cells verbatim.
     *   <li>{@code newName != null && newName.isEmpty()}: clears name cell to empty (= "use
     *       system default font" — Archi's render falls back).
     * </ul>
     */
    static String assembleFontString(String existing, String newName, Integer newSize, String newStyle) {
        if (existing == null || existing.isEmpty()) {
            // No existing font — emit minimal version-0 string.
            String name = (newName == null) ? "" : newName;
            int height = (newSize != null) ? newSize : 9;
            int style = (newStyle != null) ? mapFontStyleToInt(newStyle) : 0;
            return name + "|" + height + "|" + style;
        }

        String[] parts = existing.split("\\|", -1);
        boolean v1 = isVersion1(parts);
        int nameIdx = v1 ? V1_NAME_IDX : V0_NAME_IDX;
        int heightIdx = v1 ? V1_HEIGHT_IDX : V0_HEIGHT_IDX;
        int styleIdx = v1 ? V1_STYLE_IDX : V0_STYLE_IDX;

        // Defensive: ensure parts is wide enough for the cells we may edit.
        int needed = styleIdx + 1;
        if (parts.length < needed) {
            String[] widened = new String[needed];
            System.arraycopy(parts, 0, widened, 0, parts.length);
            for (int i = parts.length; i < needed; i++) widened[i] = "";
            parts = widened;
        }

        if (newName != null) {
            parts[nameIdx] = newName; // empty string clears
        }
        if (newSize != null) {
            // Preserve existing version's height format (v1 = "9.0" float, v0 = "9" int).
            parts[heightIdx] = v1 ? (newSize + ".0") : String.valueOf(newSize.intValue());
        }
        if (newStyle != null) {
            parts[styleIdx] = String.valueOf(mapFontStyleToInt(newStyle));
        }
        return String.join("|", parts);
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
        // figureType — restricted to the targets where "tabbed/rectangular" vocabulary is honest:
        // native groups (IBorderType.BORDER_TABBED/BORDER_RECTANGLE) and ArchiMate Grouping
        // elements (IDiagramModelArchimateObject.setType for the Grouping subtype only). Per
        // owner decision 2026-05-20: other ArchiMate element classes (Actor, Component, Node,
        // ...) also have setType(int) alternates, but their "alternate" figures are NOT tabbed
        // vs rectangular (stick-vs-box, 3D-vs-flat, etc.) — applying figureType to them under
        // "tabbed/rectangular" naming would be misleading. Silently ignored on those targets;
        // per-class figure switching deferred to a future story with proper vocabulary.
        if (styling.figureType() != null && !styling.figureType().isEmpty()) {
            int figureInt = mapFigureTypeToInt(styling.figureType());
            if (diagramObj instanceof IDiagramModelGroup group) {
                group.setBorderType(figureInt);
            } else if (diagramObj instanceof IDiagramModelArchimateObject archi
                    && archi.getArchimateElement() instanceof IGrouping) {
                archi.setType(figureInt);
            }
            // Notes implement IBorderType too but with different semantics (dogear/rectangle/none)
            // — out of scope for this story; figureType is silently ignored on notes.
        }
        if (styling.textAlignment() != null && !styling.textAlignment().isEmpty()
                && diagramObj instanceof ITextAlignment ta) {
            ta.setTextAlignment(mapTextAlignmentToInt(styling.textAlignment()));
        }
        if (styling.verticalTextAlignment() != null && !styling.verticalTextAlignment().isEmpty()
                && diagramObj instanceof ITextPosition tp) {
            tp.setTextPosition(mapVerticalTextAlignmentToInt(styling.verticalTextAlignment()));
        }
        // Typography — fontName/fontSize/fontStyle ride on IFontAttribute.setFont(String).
        // The composite-string is merged via assembleFontString so partial updates preserve
        // unspecified components.
        if ((styling.fontName() != null || styling.fontSize() != null || styling.fontStyle() != null)
                && diagramObj instanceof IFontAttribute fa) {
            String existing = fa.getFont();
            String merged = assembleFontString(existing,
                    styling.fontName(), styling.fontSize(), styling.fontStyle());
            fa.setFont(merged);
        }
        // gradient — typed setter on IDiagramModelObject. Empty string clears to default (-1).
        if (styling.gradient() != null) {
            int g = styling.gradient().isEmpty()
                    ? IDiagramModelObject.GRADIENT_NONE
                    : mapGradientToInt(styling.gradient());
            diagramObj.setGradient(g);
        }
        // borderType — note-only. instanceof check on IDiagramModelNote specifically (NOT
        // IBorderType, which would also match IDiagramModelGroup and conflict with the
        // predecessor figureType semantic). Empty string clears to default (dogear).
        if (styling.borderType() != null && diagramObj instanceof IDiagramModelNote note) {
            int b = styling.borderType().isEmpty()
                    ? IDiagramModelNote.BORDER_DOGEAR
                    : mapBorderTypeToInt(styling.borderType());
            note.setBorderType(b);
        }
        // deriveLineColor — typed setter on IDiagramModelObject. Archi default is true.
        if (styling.deriveLineColor() != null) {
            diagramObj.setDeriveElementLineColor(styling.deriveLineColor());
        }
        // outlineOpacity — typed setter on IDiagramModelObject.setLineAlpha(int).
        if (styling.outlineOpacity() != null) {
            diagramObj.setLineAlpha(styling.outlineOpacity());
        }
        // lineStyle — view-object property (Archi 5.8 typed setter IDiagramModelObject.setLineStyle(int)).
        // Empty string clears to default (-1 → Archi renders as SOLID).
        if (styling.lineStyle() != null) {
            int ls = styling.lineStyle().isEmpty()
                    ? IDiagramModelObject.LINE_STYLE_DEFAULT
                    : mapLineStyleToInt(styling.lineStyle());
            diagramObj.setLineStyle(ls);
        }
    }

    /**
     * Applies connection-specific styling (font merge only — lineStyle is a view-object
     * property in Archi 5.8, not a connection property — see Task-9 empirical correction
     * captured at `EMPIRICAL-FINDINGS.md`).
     * Called from {@code UpdateViewConnectionCommand.applyStyling}.
     *
     * <p>The font path mirrors the view-object apply (composite string merged via
     * {@link #assembleFontString}).
     */
    static void applyConnectionStyling(IDiagramModelConnection conn, StylingParams styling) {
        if (styling == null) return;
        if ((styling.fontName() != null || styling.fontSize() != null || styling.fontStyle() != null)) {
            String existing = conn.getFont();
            String merged = assembleFontString(existing,
                    styling.fontName(), styling.fontSize(), styling.fontStyle());
            conn.setFont(merged);
        }
        // lineStyle: silently ignored on connections — applies to view objects only.
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

    /**
     * Reads figureType as a user-facing string. Returns null when at Archi default
     * (matches readOpacity / readLineWidth pattern — DTO field omitted for the common case).
     * Restricted to the targets where "tabbed/rectangular" vocabulary is honest: native
     * groups (IBorderType) and ArchiMate Grouping elements (IDiagramModelArchimateObject
     * wrapping an IGrouping). Convention: 0 = tabbed (default), 1 = rectangular. For
     * other ArchiMate elements (Actor, Component, Node, ...), notes, and connections,
     * returns null — those have setType but with element-specific alternate-figure semantics
     * that this surface doesn't expose.
     */
    static String readFigureType(IDiagramModelObject obj) {
        if (obj instanceof IDiagramModelGroup group) {
            int bt = group.getBorderType();
            return (bt == IDiagramModelGroup.BORDER_RECTANGLE) ? "rectangular" : null;
        }
        if (obj instanceof IDiagramModelArchimateObject archi
                && archi.getArchimateElement() instanceof IGrouping) {
            int t = archi.getType();
            return (t == 1) ? "rectangular" : null;
        }
        return null;
    }

    /**
     * Reads textAlignment as a user-facing string. Returns null when at Archi default
     * ({@link ITextAlignment#TEXT_ALIGNMENT_CENTER}). Returns "left" or "right" when
     * explicitly set; "centre" is the default and therefore omitted from the DTO.
     */
    static String readTextAlignment(IDiagramModelObject obj) {
        if (obj instanceof ITextAlignment ta) {
            int v = ta.getTextAlignment();
            if (v == ITextAlignment.TEXT_ALIGNMENT_LEFT) return "left";
            if (v == ITextAlignment.TEXT_ALIGNMENT_RIGHT) return "right";
            return null; // CENTER (default) — omit from DTO
        }
        return null;
    }

    /**
     * Reads verticalTextAlignment as a user-facing string. Returns null when at Archi default
     * ({@link ITextPosition#TEXT_POSITION_TOP} — confirmed empirically: a freshly-created
     * IDiagramModelGroup / IDiagramModelArchimateObject / IDiagramModelNote all return
     * {@code getTextPosition() == 0 == TEXT_POSITION_TOP}; the visible Archi UI convention
     * is that labels render in a top "header band" of the figure). Returns "centre" or
     * "bottom" when explicitly set.
     */
    static String readVerticalTextAlignment(IDiagramModelObject obj) {
        if (obj instanceof ITextPosition tp) {
            int v = tp.getTextPosition();
            if (v == ITextPosition.TEXT_POSITION_CENTRE) return "centre";
            if (v == ITextPosition.TEXT_POSITION_BOTTOM) return "bottom";
            return null; // TOP (default) — omit from DTO
        }
        return null;
    }

    /**
     * Reads the font family name from a view object's IFontAttribute composite string.
     * Returns null if the object has no font set or the name cell is empty (Archi
     * default — use system view font).
     */
    static String readFontName(IDiagramModelObject obj) {
        if (obj instanceof IFontAttribute fa) {
            return parseFontName(fa.getFont());
        }
        return null;
    }

    /**
     * Reads the font point size from a view object's IFontAttribute composite string.
     * Returns null if the object has no font set or the height cell is unparseable.
     */
    static Integer readFontSize(IDiagramModelObject obj) {
        if (obj instanceof IFontAttribute fa) {
            return parseFontSize(fa.getFont());
        }
        return null;
    }

    /**
     * Reads the font style as a user-facing string ("bold" / "italic" / "bold-italic").
     * Returns null for NORMAL (Archi default) — DTO omits.
     */
    static String readFontStyle(IDiagramModelObject obj) {
        if (obj instanceof IFontAttribute fa) {
            return parseFontStyle(fa.getFont());
        }
        return null;
    }

    /**
     * Reads gradient as a user-facing string. Returns null when at Archi default
     * (GRADIENT_NONE = -1).
     */
    static String readGradient(IDiagramModelObject obj) {
        return mapIntToGradient(obj.getGradient());
    }

    /**
     * Reads note borderType as a user-facing string. Returns null on non-note objects
     * or when at Archi default (BORDER_DOGEAR = 0).
     */
    static String readBorderType(IDiagramModelObject obj) {
        if (obj instanceof IDiagramModelNote note) {
            return mapIntToBorderType(note.getBorderType());
        }
        return null;
    }

    /**
     * Reads deriveLineColor. Archi default is {@code true} (line colour IS derived from
     * fill). Returns null when at default (true) — DTO omits — Boolean.FALSE when the
     * caller explicitly disabled derivation.
     */
    static Boolean readDeriveLineColor(IDiagramModelObject obj) {
        return obj.getDeriveElementLineColor() ? null : Boolean.FALSE;
    }

    /**
     * Reads outlineOpacity (lineAlpha). Returns null when at Archi default
     * (FEATURE_LINE_ALPHA_DEFAULT = 255).
     */
    static Integer readOutlineOpacity(IDiagramModelObject obj) {
        int la = obj.getLineAlpha();
        return (la != IDiagramModelObject.FEATURE_LINE_ALPHA_DEFAULT) ? la : null;
    }

    /**
     * Reads view-object lineStyle as a user-facing string. Returns null when at Archi
     * default (LINE_STYLE_DEFAULT=-1 or LINE_STYLE_SOLID=0 — both render as solid).
     */
    static String readLineStyle(IDiagramModelObject obj) {
        return mapIntToLineStyle(obj.getLineStyle());
    }

    /**
     * Reads the labelExpression IFeatures entry on a view object (labelExpression read-back,
     * surfaced via {@code get-view-contents} since v1.6). Returns null when the feature
     * is absent or stored as empty-string. Mirrors the empty-to-null normalization in
     * {@code ArchiModelAccessorImpl.computePostLabelExpression} so the DTO field is omitted
     * via {@code @JsonInclude(NON_NULL)} when the view-object is at Archi default.
     */
    static String readLabelExpression(IDiagramModelObject obj) {
        String value = obj.getFeatures().getString("labelExpression", null);
        return (value == null || value.isEmpty()) ? null : value;
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

    /**
     * Post-styling figureType DTO value. Empty styling input is treated as "unchanged"
     * (figureType has no symmetric "clear" semantics). Returns null when the
     * resolved figure type is Archi's default (tabbed) so the DTO field is omitted.
     */
    static String computePostStylingFigureType(String currentValue, String stylingValue) {
        if (stylingValue == null || stylingValue.isEmpty()) return currentValue;
        return stylingValue.toLowerCase().equals("rectangular") ? "rectangular" : null;
    }

    /**
     * Post-styling textAlignment DTO value. Returns null when the resolved alignment
     * is Archi's default ({@code TEXT_ALIGNMENT_CENTER}).
     */
    static String computePostStylingTextAlignment(String currentValue, String stylingValue) {
        if (stylingValue == null || stylingValue.isEmpty()) return currentValue;
        String n = stylingValue.toLowerCase();
        if (n.equals("left")) return "left";
        if (n.equals("right")) return "right";
        return null;
    }

    /**
     * Post-styling verticalTextAlignment DTO value. Returns null when the resolved
     * vertical alignment is Archi's default ({@code TEXT_POSITION_TOP}).
     */
    static String computePostStylingVerticalTextAlignment(String currentValue, String stylingValue) {
        if (stylingValue == null || stylingValue.isEmpty()) return currentValue;
        String n = stylingValue.toLowerCase();
        if (n.equals("centre") || n.equals("center")) return "centre";
        if (n.equals("bottom")) return "bottom";
        return null;
    }

    /** Post-styling fontName DTO value. Empty string clears (returns null). */
    static String computePostStylingFontName(String currentValue, String stylingValue) {
        if (stylingValue == null) return currentValue;
        return stylingValue.isEmpty() ? null : stylingValue;
    }

    /** Post-styling fontSize DTO value — straightforward override. */
    static Integer computePostStylingFontSize(Integer currentValue, Integer stylingValue) {
        if (stylingValue == null) return currentValue;
        return stylingValue;
    }

    /** Post-styling fontStyle DTO value. Returns null for "normal" (default). */
    static String computePostStylingFontStyle(String currentValue, String stylingValue) {
        if (stylingValue == null || stylingValue.isEmpty()) return currentValue;
        String n = stylingValue.toLowerCase();
        return n.equals("normal") ? null : n;
    }

    /**
     * Post-styling lineStyle DTO value (view-object — Task-9 empirical correction).
     * Returns null for "solid" or empty (both render as Archi default solid line).
     */
    static String computePostStylingLineStyle(String currentValue, String stylingValue) {
        if (stylingValue == null) return currentValue;
        if (stylingValue.isEmpty()) return null;
        String n = stylingValue.toLowerCase();
        return n.equals("solid") ? null : n;
    }

    /** Post-styling gradient DTO value. Returns null for "none" (default) and empty-clears. */
    static String computePostStylingGradient(String currentValue, String stylingValue) {
        if (stylingValue == null) return currentValue;
        if (stylingValue.isEmpty()) return null;
        String n = stylingValue.toLowerCase();
        return n.equals("none") ? null : n;
    }

    /** Post-styling borderType DTO value (note only). Returns null for "dogear" (default). */
    static String computePostStylingBorderType(String currentValue, String stylingValue) {
        if (stylingValue == null) return currentValue;
        if (stylingValue.isEmpty()) return null;
        String n = stylingValue.toLowerCase();
        return n.equals("dogear") ? null : n;
    }

    /** Post-styling deriveLineColor DTO value. Returns null when at default (true). */
    static Boolean computePostStylingDeriveLineColor(Boolean currentValue, Boolean stylingValue) {
        if (stylingValue == null) return currentValue;
        return stylingValue ? null : Boolean.FALSE;
    }

    /** Post-styling outlineOpacity DTO value. Returns null when at default (255). */
    static Integer computePostStylingOutlineOpacity(Integer currentValue, Integer stylingValue) {
        if (stylingValue == null) return currentValue;
        return (stylingValue == 255) ? null : stylingValue;
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

    /**
     * Reads the label visibility state of a connection.
     * Returns null when the label is visible (default), Boolean.FALSE when hidden.
     * This keeps the DTO field omitted from JSON for the common case.
     */
    static Boolean readConnectionNameVisible(IDiagramModelArchimateConnection conn) {
        return conn.isNameVisible() ? null : Boolean.FALSE;
    }

    // ---- Connection typography + line style ----

    static String readConnectionFontName(IDiagramModelConnection conn) {
        return parseFontName(conn.getFont());
    }

    static Integer readConnectionFontSize(IDiagramModelConnection conn) {
        return parseFontSize(conn.getFont());
    }

    static String readConnectionFontStyle(IDiagramModelConnection conn) {
        return parseFontStyle(conn.getFont());
    }

    /**
     * Reads the labelExpression IFeatures entry on a view connection (since v1.6).
     * {@code IDiagramModelConnection} transitively implements {@code IFeatures} via
     * {@code IConnectable → IDiagramModelComponent → IArchimateModelObject → IFeatures}
     * (verified via {@code javap} during a disambiguation gate). Returns null when the feature is absent or stored as
     * empty-string. Mirrors the empty-to-null normalization of {@link #readLabelExpression}.
     */
    static String readConnectionLabelExpression(IDiagramModelConnection conn) {
        String value = conn.getFeatures().getString("labelExpression", null);
        return (value == null || value.isEmpty()) ? null : value;
    }

    /**
     * Reads a connection's label-offset anchor (the "Label Offset" compass introduced on newer Archi,
     * backed by the {@code "textRelativePosition"} feature). Returns {@code null} when the running
     * platform lacks the feature (older Archi — the field is then omitted via NON_NULL, no wire cost and
     * no surprise) or when the anchor is the un-offset default ({@code CENTER}), mirroring the
     * omit-at-default discipline of the other connection readers. Otherwise returns the platform's anchor
     * bitmask (the offset directions NORTH=1/SOUTH=4/WEST=8/EAST=16/NE=17/NW=9/SE=20/SW=12; the remaining
     * value CENTER=2 is the un-offset default and is suppressed to null, so it never appears on the wire).
     * Read-only counterpart to the offset the routing optimizer writes via {@link RelativePositionFeature}.
     */
    static Integer readConnectionRelativePosition(IDiagramModelConnection conn) {
        if (!RelativePositionFeature.isSupported(conn)) {
            return null;
        }
        int value = RelativePositionFeature.get(conn);
        return value == RelativePositionFeature.CENTER ? null : Integer.valueOf(value);
    }

    /**
     * Resolves the displayed label text of a view connection for label-width
     * reservation during layout, mirroring the read path used elsewhere.
     *
     * <ul>
     *   <li>A manually suppressed label (name not visible) resolves to empty so
     *       it reserves no layout space — keeping manual suppression an effective
     *       escape hatch.</li>
     *   <li>When set, a {@code labelExpression} overrides the relationship name
     *       (consistent with {@link #readConnectionLabelExpression}). The raw
     *       expression string is used as the width basis since the layout path
     *       cannot evaluate the template headlessly — an approximation (a
     *       literal-heavy template over-reserves; a short variable template such
     *       as {@code ${name}} can under-reserve relative to its expansion, no
     *       worse than the pre-change zero-reservation baseline).</li>
     *   <li>Otherwise the underlying relationship name is used, falling back to
     *       the connection's own name.</li>
     * </ul>
     *
     * @return the label text (never null); empty string when nothing is displayed
     */
    static String resolveConnectionLabelText(IDiagramModelConnection conn) {
        if (!conn.isNameVisible()) {
            return "";
        }
        if (conn instanceof IDiagramModelArchimateConnection archConn) {
            String labelExpression = readConnectionLabelExpression(conn);
            if (labelExpression != null) {
                return labelExpression;
            }
            IArchimateRelationship rel = archConn.getArchimateRelationship();
            return (rel != null && rel.getName() != null) ? rel.getName() : "";
        }
        String name = conn.getName();
        return name != null ? name : "";
    }

}
