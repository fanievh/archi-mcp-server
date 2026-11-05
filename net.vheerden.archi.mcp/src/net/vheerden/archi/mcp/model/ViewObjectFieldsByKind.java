package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.IIconic;
import com.archimatetool.model.ITextPosition;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Which styling, label and image fields each kind of view object takes, and the one place a field a
 * kind does not take is refused.
 *
 * <p>Archi decides what its Properties view offers per kind. An image is shown only its line width,
 * border colour, opacity, outline opacity, line style and picture: no fill, line colour, font, text
 * alignment, label expression or icon. A view reference is shown everything but a figure type and a
 * border type, which only groups, Grouping elements and notes carry. Every view object stores the fill,
 * font and alignment attributes in EMF all the same, so a write would land where nothing draws it; a
 * success response over such a write tells the caller something is on the canvas that is not. The
 * server is never more forgiving than Archi, so on an image and a view reference such a field is
 * refused by name, the empty clear value included.</p>
 *
 * <p>Notes, groups and elements keep their published behaviour: a field the kind cannot hold
 * ({@code borderType} on a group, {@code figureType} on a note) is ignored. What changes for them is
 * the report: the {@code holds…} predicates here are the tests the update command writes with, and the
 * post-update reconcilers ask the same ones, so a field the object cannot hold is reported as the
 * object holds it rather than as the caller sent it.</p>
 */
final class ViewObjectFieldsByKind {

    private ViewObjectFieldsByKind() {
    }

    // ---- what each kind holds ----

    /** A group, or an element view object whose element is a Grouping: the two kinds with a figure type. */
    static boolean holdsFigureType(IDiagramModelObject obj) {
        return obj instanceof IDiagramModelGroup
                || (obj instanceof IDiagramModelArchimateObject a && a.getArchimateElement() instanceof IGrouping);
    }

    static boolean holdsVerticalTextAlignment(IDiagramModelObject obj) {
        return obj instanceof ITextPosition;
    }

    /** Only a note has a border type; a group's border type attribute is its figure type. */
    static boolean holdsBorderType(IDiagramModelObject obj) {
        return obj instanceof IDiagramModelNote;
    }

    /** Whether the object carries an icon: its position and visibility, and an icon image path. */
    static boolean holdsIcon(IDiagramModelObject obj) {
        return obj instanceof IIconic;
    }

    // ---- the refusals ----

    /**
     * Accepts an update's text and fields for {@code target}: refuses {@code text} on a kind with no text
     * of its own (as {@link TextUtils#acceptTextFor} always has), then refuses every field the kind does
     * not take. Answers the interpreted text.
     *
     * <p>Only what the caller sent is examined. The layout tools that reach the update prepares send no
     * text, styling, image or label expression, so they can never be refused here.</p>
     *
     * @throws ModelAccessException naming every field the kind does not take, in published order
     */
    static String accept(IDiagramModelObject target, String text, StylingParams styling,
            ImageParams imageParams, String labelExpression) {
        String accepted = TextUtils.acceptTextFor(target, text);
        refuseUnheld(target, styling, imageParams, labelExpression);
        if (target instanceof IDiagramModelImage && imageParams != null && "".equals(imageParams.imagePath())) {
            throw new ModelAccessException(
                    "Cannot clear the picture of an image",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "An image always shows a picture. Pass another archive imagePath to replace it "
                            + "(list-model-images lists them), or remove the image with remove-from-view.",
                    null);
        }
        return accepted;
    }

    /**
     * Applies placement styling to a new object, after refusing every field its kind does not take, so
     * placing and updating an object refuse the same fields.
     */
    static void applyStylingToNew(IDiagramModelObject target, StylingParams styling) {
        refuseUnheld(target, styling, null, null);
        StylingHelper.applyStylingToNewObject(target, styling);
    }

    private static void refuseUnheld(IDiagramModelObject target, StylingParams styling,
            ImageParams imageParams, String labelExpression) {
        if (target instanceof IDiagramModelImage) {
            List<String> sent = new ArrayList<>();
            sentIf(sent, labelExpression, "labelExpression");
            if (styling != null) {
                sentIf(sent, styling.fillColor(), "fillColor");
                sentIf(sent, styling.lineColor(), "lineColor");
                sentIf(sent, styling.fontColor(), "fontColor");
                sentIf(sent, styling.figureType(), "figureType");
                sentIf(sent, styling.textAlignment(), "textAlignment");
                sentIf(sent, styling.verticalTextAlignment(), "verticalTextAlignment");
                sentIf(sent, styling.fontName(), "fontName");
                sentIf(sent, styling.fontSize(), "fontSize");
                sentIf(sent, styling.fontStyle(), "fontStyle");
                sentIf(sent, styling.gradient(), "gradient");
                sentIf(sent, styling.borderType(), "borderType");
                sentIf(sent, styling.deriveLineColor(), "deriveLineColor");
            }
            if (imageParams != null) {
                sentIf(sent, imageParams.imagePosition(), "imagePosition");
                sentIf(sent, imageParams.showIcon(), "showIcon");
            }
            refuse(sent, "an image", "An image takes opacity, outlineOpacity, lineWidth, lineStyle and "
                    + "imagePath, which replaces its picture. Archi draws no fill, line colour, text, label or "
                    + "icon on an image, so the other styling fields have nothing to change.");
        } else if (target instanceof IDiagramModelReference && styling != null) {
            List<String> sent = new ArrayList<>();
            sentIf(sent, styling.figureType(), "figureType");
            sentIf(sent, styling.borderType(), "borderType");
            refuse(sent, "a view reference", "A view reference has no figure type or border type: figureType "
                    + "is for groups and Grouping elements, borderType for notes. Every other styling field, "
                    + "and an icon, applies to a view reference.");
        }
    }

    private static void sentIf(List<String> sent, Object value, String field) {
        if (value != null) {
            sent.add(field);
        }
    }

    private static void refuse(List<String> fields, String kind, String suggestion) {
        if (fields.isEmpty()) {
            return;
        }
        throw new ModelAccessException(
                "Cannot set " + joined(fields) + " on " + kind,
                ErrorCode.INVALID_PARAMETER,
                null,
                suggestion,
                null);
    }

    /** {@code a}, {@code a and b}, {@code a, b and c}. */
    private static String joined(List<String> fields) {
        if (fields.size() == 1) {
            return fields.get(0);
        }
        return String.join(", ", fields.subList(0, fields.size() - 1)) + " and " + fields.get(fields.size() - 1);
    }
}
