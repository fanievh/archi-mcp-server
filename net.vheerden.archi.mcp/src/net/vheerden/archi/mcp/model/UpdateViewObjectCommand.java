package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFontAttribute;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.IIconic;
import com.archimatetool.model.ILineObject;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.ITextContent;
import com.archimatetool.model.ITextPosition;

/**
 * GEF Command that updates the visual bounds (position and size) of a
 * diagram object on a view, and optionally updates text and styling
 * for groups/notes/elements (Stories 7-8, 8-6, 11-2).
 *
 * <p>Captures the old bounds, text, and styling at construction time
 * for undo support. The caller merges partial updates (only provided
 * fields) before creating this command — the command always receives
 * complete bounds and merged styling values.</p>
 *
 * <p><strong>Text update support:</strong> Added optional text update support.
 * Groups use {@code setName()} for their label; notes use
 * {@code setContent()} via {@link ITextContent}.</p>
 *
 * <p><strong>Styling support:</strong> Added optional styling support
 * (fillColor, lineColor, fontColor, opacity, lineWidth). Uses sentinel
 * value {@code null} in StylingParams fields to indicate "no change".</p>
 *
 * <p><strong>Group/element styling surface:</strong> Added
 * optional {@code figureType} (mapped to {@code IBorderType.setBorderType(int)}
 * for native groups or {@code IDiagramModelArchimateObject.setType(int)} for
 * ArchiMate elements), {@code textAlignment} (mapped to
 * {@code ITextAlignment.setTextAlignment(int)}), and {@code verticalTextAlignment}
 * (mapped to {@code ITextPosition.setTextPosition(int)}) to the styling rail.
 * All three ride the existing {@link StylingParams} parameter — same single-
 * undo-unit guarantee as the colour/opacity/lineWidth fields.</p>
 *
 * <p><strong>Label-expression rail:</strong> Added the fifth rail — optional
 * {@code labelExpression}, Archi's per-view-object dynamic label template
 * (e.g. {@code "${name}"}, {@code "${property:KEY}"}). Stored as a generic
 * {@link com.archimatetool.model.IFeatures} entry under key {@code "labelExpression"}
 * — NOT a typed EMF setter (EMF finding). Null leaves
 * unchanged; empty-string clears via {@link com.archimatetool.model.IFeaturesEList#remove(String)};
 * non-empty sets via {@link com.archimatetool.model.IFeaturesEList#putString(String, String)}.
 * Same single-undo-unit guarantee as the other four rails.</p>
 *
 * <p><strong>Typography &amp; styling extensions:</strong> Extended the styling rail (NOT a new rail —
 * fields ride the existing {@link StylingParams} parameter) with typography
 * (fontName / fontSize / fontStyle merged through {@code IFontAttribute.setFont}),
 * {@code gradient} (typed {@code IDiagramModelObject.setGradient(int)}, default
 * {@code GRADIENT_NONE=-1}), note-only {@code borderType} (typed
 * {@code IBorderType.setBorderType(int)} on {@code IDiagramModelNote}),
 * {@code deriveLineColor} (typed {@code setDeriveElementLineColor(boolean)}, Archi
 * default {@code true}), and {@code outlineOpacity} (typed {@code setLineAlpha(int)},
 * Archi default 255). All discovered in an EMF spike — typed setters
 * on Archi 5.8, no IFeatures-backed storage needed. Constructor arity unchanged at 8 args.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class UpdateViewObjectCommand extends Command {

    private final IDiagramModelObject diagramObject;
    private final int oldX;
    private final int oldY;
    private final int oldWidth;
    private final int oldHeight;
    private final int newX;
    private final int newY;
    private final int newWidth;
    private final int newHeight;

    // Text update support
    private final String oldText;
    private final String newText;
    private final boolean hasTextChange;

    // Styling update support
    private final String oldFillColor;
    private final String newFillColor;
    private final String oldLineColor;
    private final String newLineColor;
    private final String oldFontColor;
    private final String newFontColor;
    private final int oldAlpha;
    private final int newAlpha;
    private final int oldLineWidth;
    private final int newLineWidth;
    private final boolean hasStylingChange;

    // Figure type + horizontal + vertical text alignment.
    // Integer rather than int: null means "not applicable for this target type" (figureType is null
    // for objects that are neither group nor archimateObject; the two alignment fields can in principle
    // be captured for any IDiagramModelObject but we follow the same null-when-unapplicable shape).
    private final Integer oldFigureType;
    private final Integer newFigureType;
    private final Integer oldTextAlignment;
    private final Integer newTextAlignment;
    private final Integer oldVerticalTextAlignment;
    private final Integer newVerticalTextAlignment;

    // Image update support
    private final String oldImagePath;
    private final String newImagePath;
    private final int oldImagePosition;
    private final int newImagePosition;
    private final int oldImageSource;
    private final int newImageSource;
    private final int oldShowIcon;
    private final int newShowIcon;
    private final boolean hasImageChange;

    // Label-expression rail. Stored as a generic IFeatures
    // entry on the diagram object (key "labelExpression"), NOT a typed EMF
    // getter/setter (EMF finding).
    // hasLabelExpressionChange = true when caller passed a non-null value
    // (including empty string, which clears the feature).
    private final String oldLabelExpression;
    private final String newLabelExpression;
    private final boolean hasLabelExpressionChange;

    /** Feature-list key for the Archi-side label-expression renderer (matches Archi's own constant). */
    static final String LABEL_EXPRESSION_FEATURE = "labelExpression";

    // Anchor rail — view-anchored positioning. Four generic IFeatures entries on the
    // child object recording that its position is relative to a target container
    // (anchorTarget id + anchorEdge + anchorDx/anchorDy) rather than a frozen absolute
    // snapshot. Same single-undo-unit + capture-at-construction discipline as the
    // label-expression rail. hasAnchorChange = true when the caller passed a non-null
    // anchorTarget (empty string clears all four entries).
    private final String oldAnchorTarget;
    private final String newAnchorTarget;
    private final String oldAnchorEdge;
    private final String newAnchorEdge;
    private final String oldAnchorDx;
    private final String newAnchorDx;
    private final String oldAnchorDy;
    private final String newAnchorDy;
    private final boolean hasAnchorChange;

    // Styling extensions — all ride the same hasStylingChange boundary.
    // Captured-old / captured-new pairs. The font composite string is captured as ONE
    // value; the merge happens through StylingHelper.assembleFontString.
    private final String oldFont;
    private final String newFont;
    // hasFontChange = true only when at least one typography field (fontName/Size/Style)
    // was provided in styling. Prevents idempotent setFont(null) writes during prior
    // styling-only updates (byte-identical discipline).
    private final boolean hasFontChange;
    private final int oldGradient;
    private final int newGradient;
    private final Integer oldBorderType;          // null on non-note objects
    private final Integer newBorderType;
    private final boolean oldDeriveLineColor;
    private final boolean newDeriveLineColor;
    private final int oldOutlineOpacity;
    private final int newOutlineOpacity;
    private final int oldLineStyle;
    private final int newLineStyle;

    /**
     * Creates a command to update a diagram object's bounds (no text or styling change).
     *
     * @param diagramObject the diagram object to update
     * @param newX          the new X coordinate
     * @param newY          the new Y coordinate
     * @param newWidth      the new width
     * @param newHeight     the new height
     */
    public UpdateViewObjectCommand(IDiagramModelObject diagramObject,
                                    int newX, int newY, int newWidth, int newHeight) {
        this(diagramObject, newX, newY, newWidth, newHeight, null, null);
    }

    /**
     * Creates a command to update a diagram object's bounds and optionally its text.
     *
     * @param diagramObject the diagram object to update
     * @param newX          the new X coordinate
     * @param newY          the new Y coordinate
     * @param newWidth      the new width
     * @param newHeight     the new height
     * @param newText       new text for groups (label) or notes (content), null to leave unchanged
     */
    public UpdateViewObjectCommand(IDiagramModelObject diagramObject,
                                    int newX, int newY, int newWidth, int newHeight,
                                    String newText) {
        this(diagramObject, newX, newY, newWidth, newHeight, newText, null);
    }

    /**
     * Creates a command to update a diagram object's bounds, text, and styling.
     *
     * @param diagramObject the diagram object to update
     * @param newX          the new X coordinate
     * @param newY          the new Y coordinate
     * @param newWidth      the new width
     * @param newHeight     the new height
     * @param newText       new text for groups (label) or notes (content), null to leave unchanged
     * @param styling       styling parameters to apply, null or StylingParams.NONE for no styling change
     */
    public UpdateViewObjectCommand(IDiagramModelObject diagramObject,
                                    int newX, int newY, int newWidth, int newHeight,
                                    String newText, StylingParams styling) {
        this(diagramObject, newX, newY, newWidth, newHeight, newText, styling, null);
    }

    /**
     * Creates a command to update a diagram object's bounds, text, styling, and image
     * (without a label-expression change).
     *
     * @param diagramObject the diagram object to update
     * @param newX          the new X coordinate
     * @param newY          the new Y coordinate
     * @param newWidth      the new width
     * @param newHeight     the new height
     * @param newText       new text for groups (label) or notes (content), null to leave unchanged
     * @param styling       styling parameters to apply, null or StylingParams.NONE for no styling change
     * @param imageParams   image parameters to apply, null or ImageParams.NONE for no image change
     */
    public UpdateViewObjectCommand(IDiagramModelObject diagramObject,
                                    int newX, int newY, int newWidth, int newHeight,
                                    String newText, StylingParams styling,
                                    ImageParams imageParams) {
        this(diagramObject, newX, newY, newWidth, newHeight,
                newText, styling, imageParams, null);
    }

    /**
     * Creates a command to update a diagram object's bounds, text, styling, image,
     * and label expression.
     *
     * <p>{@code newLabelExpression} semantics:
     * <ul>
     *   <li>{@code null} — leave the current label expression unchanged (omit-means-no-change).</li>
     *   <li>{@code ""} (empty string) — clear the label expression (removes the "labelExpression"
     *       feature entry; Archi falls back to rendering the element's static name).</li>
     *   <li>Any other string — set the label expression verbatim (no token validation; Archi
     *       owns the grammar).</li>
     * </ul>
     *
     * @param diagramObject       the diagram object to update
     * @param newX                the new X coordinate
     * @param newY                the new Y coordinate
     * @param newWidth            the new width
     * @param newHeight           the new height
     * @param newText             new text for groups (label) or notes (content), null to leave unchanged
     * @param styling             styling parameters to apply, null or StylingParams.NONE for no styling change
     * @param imageParams         image parameters to apply, null or ImageParams.NONE for no image change
     * @param newLabelExpression  new label expression (e.g. {@code "${name}"} or
     *                            {@code "${property:Owner}"}); null leaves unchanged; empty clears
     */
    public UpdateViewObjectCommand(IDiagramModelObject diagramObject,
                                    int newX, int newY, int newWidth, int newHeight,
                                    String newText, StylingParams styling,
                                    ImageParams imageParams, String newLabelExpression) {
        this(diagramObject, newX, newY, newWidth, newHeight, newText, styling,
                imageParams, newLabelExpression, null, null, null, null);
    }

    /**
     * Creates a command to update a diagram object's bounds, text, styling, image,
     * label expression, and view-anchor.
     *
     * <p>{@code anchorTarget} semantics: {@code null} leaves the anchor unchanged;
     * empty string clears the anchor (removes all four anchor feature entries); any
     * other string records an anchor to that target view-object id. {@code anchorEdge}
     * defaults to {@code "below"} when null/empty; {@code anchorDx}/{@code anchorDy}
     * default to {@code 0}. The caller is responsible for having resolved the concrete
     * {@code newX}/{@code newY} from the target's current bounds.</p>
     */
    public UpdateViewObjectCommand(IDiagramModelObject diagramObject,
                                    int newX, int newY, int newWidth, int newHeight,
                                    String newText, StylingParams styling,
                                    ImageParams imageParams, String newLabelExpression,
                                    String anchorTarget, String anchorEdge,
                                    Integer anchorDx, Integer anchorDy) {
        this.diagramObject = diagramObject;
        this.oldX = diagramObject.getBounds().getX();
        this.oldY = diagramObject.getBounds().getY();
        this.oldWidth = diagramObject.getBounds().getWidth();
        this.oldHeight = diagramObject.getBounds().getHeight();
        this.newX = newX;
        this.newY = newY;
        this.newWidth = newWidth;
        this.newHeight = newHeight;
        this.newText = newText;
        this.hasTextChange = (newText != null);

        // Capture old text for undo
        if (newText != null) {
            if (diagramObject instanceof IDiagramModelGroup group) {
                this.oldText = group.getName();
            } else if (diagramObject instanceof ITextContent textContent) {
                this.oldText = textContent.getContent();
            } else {
                this.oldText = null;
            }
        } else {
            this.oldText = null;
        }

        // Styling support
        this.hasStylingChange = (styling != null && styling.hasAnyValue());

        if (hasStylingChange) {
            // Capture old values for undo
            this.oldFillColor = diagramObject.getFillColor();
            this.oldLineColor = (diagramObject instanceof ILineObject lo) ? lo.getLineColor() : null;
            this.oldFontColor = (diagramObject instanceof IFontAttribute fa) ? fa.getFontColor() : null;
            this.oldAlpha = diagramObject.getAlpha();
            this.oldLineWidth = (diagramObject instanceof ILineObject lo) ? lo.getLineWidth() : 0;
            this.oldFigureType = readFigureTypeInt(diagramObject);
            this.oldTextAlignment = (diagramObject instanceof ITextAlignment ta) ? ta.getTextAlignment() : null;
            this.oldVerticalTextAlignment = (diagramObject instanceof ITextPosition tp) ? tp.getTextPosition() : null;

            // Compute new values — null in StylingParams means "no change", keep old value
            this.newFillColor = (styling.fillColor() != null) ? emptyToNull(styling.fillColor()) : oldFillColor;
            this.newLineColor = (styling.lineColor() != null)
                ? ((diagramObject instanceof ILineObject) ? emptyToNull(styling.lineColor()) : oldLineColor)
                : oldLineColor;
            this.newFontColor = (styling.fontColor() != null)
                ? ((diagramObject instanceof IFontAttribute) ? emptyToNull(styling.fontColor()) : oldFontColor)
                : oldFontColor;
            this.newAlpha = (styling.opacity() != null) ? styling.opacity() : oldAlpha;
            this.newLineWidth = (styling.lineWidth() != null)
                ? ((diagramObject instanceof ILineObject) ? styling.lineWidth() : oldLineWidth)
                : oldLineWidth;
            // Integer.valueOf wrapping avoids JLS §15.25 ternary type-unification unboxing the
            // (potentially-null) old* Integer when the int-returning branch is the other arm.
            this.newFigureType = (styling.figureType() != null && !styling.figureType().isEmpty() && oldFigureType != null)
                ? Integer.valueOf(StylingHelper.mapFigureTypeToInt(styling.figureType()))
                : oldFigureType;
            this.newTextAlignment = (styling.textAlignment() != null && !styling.textAlignment().isEmpty() && oldTextAlignment != null)
                ? Integer.valueOf(StylingHelper.mapTextAlignmentToInt(styling.textAlignment()))
                : oldTextAlignment;
            this.newVerticalTextAlignment = (styling.verticalTextAlignment() != null && !styling.verticalTextAlignment().isEmpty() && oldVerticalTextAlignment != null)
                ? Integer.valueOf(StylingHelper.mapVerticalTextAlignmentToInt(styling.verticalTextAlignment()))
                : oldVerticalTextAlignment;

            // Typography composite-string (one EMF field for all 3 sub-knobs).
            // hasFontChange guard prevents idempotent setFont(null) writes when prior styling
            // triggers hasStylingChange but no typography field was provided.
            this.oldFont = (diagramObject instanceof IFontAttribute fa) ? fa.getFont() : null;
            boolean typographyProvided = styling.fontName() != null
                    || styling.fontSize() != null
                    || styling.fontStyle() != null;
            this.hasFontChange = (diagramObject instanceof IFontAttribute) && typographyProvided;
            if (hasFontChange) {
                this.newFont = StylingHelper.assembleFontString(
                        oldFont, styling.fontName(), styling.fontSize(), styling.fontStyle());
            } else {
                this.newFont = oldFont;
            }

            // Gradient (typed setter, always available on IDiagramModelObject).
            this.oldGradient = diagramObject.getGradient();
            this.newGradient = computeNewGradient(oldGradient, styling.gradient());

            // Note-only borderType — instanceof IDiagramModelNote, NOT IBorderType
            // (which would also match IDiagramModelGroup and collide with predecessor figureType).
            this.oldBorderType = (diagramObject instanceof IDiagramModelNote note) ? note.getBorderType() : null;
            this.newBorderType = computeNewBorderType(oldBorderType, styling.borderType());

            // deriveLineColor (typed boolean setter, Archi default true).
            this.oldDeriveLineColor = diagramObject.getDeriveElementLineColor();
            this.newDeriveLineColor = (styling.deriveLineColor() != null)
                    ? styling.deriveLineColor() : oldDeriveLineColor;

            // outlineOpacity (typed int setter, sibling of setAlpha).
            this.oldOutlineOpacity = diagramObject.getLineAlpha();
            this.newOutlineOpacity = (styling.outlineOpacity() != null)
                    ? styling.outlineOpacity() : oldOutlineOpacity;

            // lineStyle (typed int setter on IDiagramModelObject).
            // Empty-string clears to LINE_STYLE_DEFAULT (-1). Non-null applies mapped int.
            this.oldLineStyle = diagramObject.getLineStyle();
            this.newLineStyle = computeNewLineStyle(oldLineStyle, styling.lineStyle());
        } else {
            this.oldFillColor = null;
            this.newFillColor = null;
            this.oldLineColor = null;
            this.newLineColor = null;
            this.oldFontColor = null;
            this.newFontColor = null;
            this.oldAlpha = 0;
            this.newAlpha = 0;
            this.oldLineWidth = 0;
            this.newLineWidth = 0;
            this.oldFigureType = null;
            this.newFigureType = null;
            this.oldTextAlignment = null;
            this.newTextAlignment = null;
            this.oldVerticalTextAlignment = null;
            this.newVerticalTextAlignment = null;
            this.oldFont = null;
            this.newFont = null;
            this.hasFontChange = false;
            this.oldGradient = 0;
            this.newGradient = 0;
            this.oldBorderType = null;
            this.newBorderType = null;
            this.oldDeriveLineColor = false;
            this.newDeriveLineColor = false;
            this.oldOutlineOpacity = 0;
            this.newOutlineOpacity = 0;
            this.oldLineStyle = 0;
            this.newLineStyle = 0;
        }

        // Image support
        boolean imageChangeRequested = (imageParams != null && imageParams.hasAnyValue());

        if (imageChangeRequested && diagramObject instanceof IIconic iconic) {
            this.hasImageChange = true;
            // Capture old values for undo
            this.oldImagePath = iconic.getImagePath();
            this.oldImagePosition = iconic.getImagePosition();
            this.oldShowIcon = ImageHelper.readShowIconInt(diagramObject);
            this.oldImageSource = (diagramObject instanceof IDiagramModelArchimateObject archiObj)
                ? archiObj.getImageSource() : 0;

            // Compute new values — null in ImageParams means "no change", keep old value
            this.newImagePath = (imageParams.imagePath() != null)
                ? (imageParams.imagePath().isEmpty() ? null : imageParams.imagePath())
                : oldImagePath;
            this.newImagePosition = (imageParams.imagePosition() != null)
                ? ImageParams.positionToInt(imageParams.imagePosition())
                : oldImagePosition;
            this.newShowIcon = (imageParams.showIcon() != null)
                ? ImageParams.showIconToInt(imageParams.showIcon())
                : oldShowIcon;
            // Compute imageSource based on whether image is being set or cleared
            if (imageParams.imagePath() != null) {
                this.newImageSource = (newImagePath != null && !newImagePath.isEmpty())
                    ? IDiagramModelArchimateObject.IMAGE_SOURCE_CUSTOM
                    : IDiagramModelArchimateObject.IMAGE_SOURCE_PROFILE;
            } else {
                this.newImageSource = oldImageSource;
            }
        } else {
            this.hasImageChange = false;
            this.oldImagePath = null;
            this.newImagePath = null;
            this.oldImagePosition = 0;
            this.newImagePosition = 0;
            this.oldImageSource = 0;
            this.newImageSource = 0;
            this.oldShowIcon = 0;
            this.newShowIcon = 0;
        }

        // Label-expression rail. Generic IFeatures storage,
        // not a typed setter — see class-level note.
        this.hasLabelExpressionChange = (newLabelExpression != null);
        if (hasLabelExpressionChange) {
            this.oldLabelExpression = diagramObject.getFeatures().getString(LABEL_EXPRESSION_FEATURE, null);
            this.newLabelExpression = emptyToNull(newLabelExpression);
        } else {
            this.oldLabelExpression = null;
            this.newLabelExpression = null;
        }

        // Anchor rail. Generic IFeatures storage (four keys), mirroring the label-expression
        // rail. Non-null anchorTarget = change requested; empty string clears all four.
        this.hasAnchorChange = (anchorTarget != null);
        if (hasAnchorChange) {
            this.oldAnchorTarget = diagramObject.getFeatures().getString(AnchorResolver.ANCHOR_TARGET_FEATURE, null);
            this.oldAnchorEdge = diagramObject.getFeatures().getString(AnchorResolver.ANCHOR_EDGE_FEATURE, null);
            this.oldAnchorDx = diagramObject.getFeatures().getString(AnchorResolver.ANCHOR_DX_FEATURE, null);
            this.oldAnchorDy = diagramObject.getFeatures().getString(AnchorResolver.ANCHOR_DY_FEATURE, null);
            if (anchorTarget.isEmpty()) {
                // Clear — remove all four entries.
                this.newAnchorTarget = null;
                this.newAnchorEdge = null;
                this.newAnchorDx = null;
                this.newAnchorDy = null;
            } else {
                this.newAnchorTarget = anchorTarget;
                this.newAnchorEdge = (anchorEdge != null && !anchorEdge.isEmpty())
                        ? anchorEdge : AnchorResolver.DEFAULT_EDGE;
                this.newAnchorDx = String.valueOf(anchorDx != null ? anchorDx : 0);
                this.newAnchorDy = String.valueOf(anchorDy != null ? anchorDy : 0);
            }
        } else {
            this.oldAnchorTarget = null;
            this.newAnchorTarget = null;
            this.oldAnchorEdge = null;
            this.newAnchorEdge = null;
            this.oldAnchorDx = null;
            this.newAnchorDx = null;
            this.oldAnchorDy = null;
            this.newAnchorDy = null;
        }

        setLabel("Update view object");
    }

    @Override
    public void execute() {
        diagramObject.setBounds(newX, newY, newWidth, newHeight);
        applyText(newText);
        if (hasStylingChange) {
            applyStyling(newFillColor, newLineColor, newFontColor, newAlpha, newLineWidth,
                    newFigureType, newTextAlignment, newVerticalTextAlignment,
                    newFont, newGradient, newBorderType, newDeriveLineColor, newOutlineOpacity,
                    newLineStyle);
        }
        if (hasImageChange) {
            applyImage(newImagePath, newImagePosition, newImageSource, newShowIcon);
        }
        if (hasLabelExpressionChange) {
            applyLabelExpression(newLabelExpression);
        }
        if (hasAnchorChange) {
            applyAnchor(newAnchorTarget, newAnchorEdge, newAnchorDx, newAnchorDy);
        }
    }

    @Override
    public void undo() {
        diagramObject.setBounds(oldX, oldY, oldWidth, oldHeight);
        applyText(oldText);
        if (hasStylingChange) {
            applyStyling(oldFillColor, oldLineColor, oldFontColor, oldAlpha, oldLineWidth,
                    oldFigureType, oldTextAlignment, oldVerticalTextAlignment,
                    oldFont, oldGradient, oldBorderType, oldDeriveLineColor, oldOutlineOpacity,
                    oldLineStyle);
        }
        if (hasImageChange) {
            applyImage(oldImagePath, oldImagePosition, oldImageSource, oldShowIcon);
        }
        if (hasLabelExpressionChange) {
            applyLabelExpression(oldLabelExpression);
        }
        if (hasAnchorChange) {
            applyAnchor(oldAnchorTarget, oldAnchorEdge, oldAnchorDx, oldAnchorDy);
        }
    }

    private void applyText(String text) {
        if (!hasTextChange) return;
        if (diagramObject instanceof IDiagramModelGroup group) {
            group.setName(text);
        } else if (diagramObject instanceof ITextContent textContent) {
            textContent.setContent(text);
        }
    }

    private void applyStyling(String fillColor, String lineColor, String fontColor,
                               int alpha, int lineWidth,
                               Integer figureType, Integer textAlignment, Integer verticalTextAlignment,
                               String font, int gradient, Integer borderType,
                               boolean deriveLineColor, int outlineOpacity, int lineStyle) {
        diagramObject.setFillColor(fillColor);
        diagramObject.setAlpha(alpha);
        if (diagramObject instanceof ILineObject lo) {
            lo.setLineColor(lineColor);
            lo.setLineWidth(lineWidth);
        }
        if (diagramObject instanceof IFontAttribute fa) {
            fa.setFontColor(fontColor);
            // Typography composite-string (merged via assembleFontString at capture).
            // Guarded so prior styling-only updates do NOT call setFont with a null/unchanged
            // value (preserves byte-identical EMF dirty-flag semantics).
            if (hasFontChange) {
                fa.setFont(font);
            }
        }
        if (figureType != null) {
            if (diagramObject instanceof IDiagramModelGroup g) {
                g.setBorderType(figureType);
            } else if (diagramObject instanceof IDiagramModelArchimateObject a
                    && a.getArchimateElement() instanceof IGrouping) {
                a.setType(figureType);
            }
        }
        if (textAlignment != null && diagramObject instanceof ITextAlignment ta) {
            ta.setTextAlignment(textAlignment);
        }
        if (verticalTextAlignment != null && diagramObject instanceof ITextPosition tp) {
            tp.setTextPosition(verticalTextAlignment);
        }
        // Typed setters on IDiagramModelObject.
        diagramObject.setGradient(gradient);
        diagramObject.setDeriveElementLineColor(deriveLineColor);
        diagramObject.setLineAlpha(outlineOpacity);
        diagramObject.setLineStyle(lineStyle);
        if (borderType != null && diagramObject instanceof IDiagramModelNote note) {
            note.setBorderType(borderType);
        }
    }

    /**
     * Computes the new lineStyle int from the captured old + the StylingParams lineStyle string.
     * Null = unchanged (returns old); empty = clear to default (LINE_STYLE_DEFAULT = -1);
     * otherwise mapped via {@link StylingHelper#mapLineStyleToInt}.
     */
    private static int computeNewLineStyle(int oldLineStyle, String stylingLineStyle) {
        if (stylingLineStyle == null) return oldLineStyle;
        if (stylingLineStyle.isEmpty()) return IDiagramModelObject.LINE_STYLE_DEFAULT;
        return StylingHelper.mapLineStyleToInt(stylingLineStyle);
    }

    /**
     * Computes the new gradient int from the captured old + the StylingParams gradient string.
     * Null = unchanged (returns old); empty = clear to default (-1); otherwise mapped via
     * {@link StylingHelper#mapGradientToInt}.
     */
    private static int computeNewGradient(int oldGradient, String stylingGradient) {
        if (stylingGradient == null) return oldGradient;
        if (stylingGradient.isEmpty()) return IDiagramModelObject.GRADIENT_NONE;
        return StylingHelper.mapGradientToInt(stylingGradient);
    }

    /**
     * Computes the new borderType Integer for IDiagramModelNote targets. Returns null when
     * the target is not a note (no-op). Null styling value = unchanged; empty = back to dogear default.
     */
    private static Integer computeNewBorderType(Integer oldBorderType, String stylingBorderType) {
        if (oldBorderType == null) return null;   // not a note — no-op
        if (stylingBorderType == null) return oldBorderType;
        if (stylingBorderType.isEmpty()) return IDiagramModelNote.BORDER_DOGEAR;
        return StylingHelper.mapBorderTypeToInt(stylingBorderType);
    }

    private static Integer readFigureTypeInt(IDiagramModelObject obj) {
        if (obj instanceof IDiagramModelGroup g) return g.getBorderType();
        if (obj instanceof IDiagramModelArchimateObject a
                && a.getArchimateElement() instanceof IGrouping) {
            return a.getType();
        }
        return null;
    }

    private void applyImage(String imagePath, int imagePosition, int imageSource, int showIcon) {
        if (diagramObject instanceof IIconic iconic) {
            iconic.setImagePath(imagePath);
            iconic.setImagePosition(imagePosition);
        }
        if (diagramObject instanceof IDiagramModelArchimateObject archiObj) {
            archiObj.setImageSource(imageSource);
        }
        // showIcon (iconVisibleState) is on IDiagramModelObject, available for all types
        diagramObject.setIconVisibleState(showIcon);
    }

    /**
     * Writes or clears the "labelExpression" feature entry on the diagram object.
     * Null clears (Archi falls back to the element's static name); non-null sets verbatim
     * (no token validation — Archi owns the grammar; a literal HTML/XML entity is rejected
     * upstream in the accessor before this command is built).
     */
    private void applyLabelExpression(String value) {
        if (value == null) {
            diagramObject.getFeatures().remove(LABEL_EXPRESSION_FEATURE);
        } else {
            diagramObject.getFeatures().putString(LABEL_EXPRESSION_FEATURE, value);
        }
    }

    /**
     * Writes or clears the four anchor feature entries. A null component removes that entry
     * (Archi falls back to the object's absolute bounds); a non-null component sets it verbatim.
     */
    private void applyAnchor(String target, String edge, String dx, String dy) {
        applyAnchorFeature(AnchorResolver.ANCHOR_TARGET_FEATURE, target);
        applyAnchorFeature(AnchorResolver.ANCHOR_EDGE_FEATURE, edge);
        applyAnchorFeature(AnchorResolver.ANCHOR_DX_FEATURE, dx);
        applyAnchorFeature(AnchorResolver.ANCHOR_DY_FEATURE, dy);
    }

    private void applyAnchorFeature(String key, String value) {
        if (value == null) {
            diagramObject.getFeatures().remove(key);
        } else {
            diagramObject.getFeatures().putString(key, value);
        }
    }

    /**
     * Converts empty string to null (Archi EMF stores null for "use default").
     *
     * <p>Package-visible so the view-scoped label-expression command shares the exact
     * same empty-clears semantic instead of duplicating it.</p>
     */
    static String emptyToNull(String value) {
        return (value != null && value.isEmpty()) ? null : value;
    }

    /** Package-visible for testing. */
    IDiagramModelObject getDiagramObject() { return diagramObject; }

    /** Package-visible for testing. */
    int getOldX() { return oldX; }

    /** Package-visible for testing. */
    int getOldY() { return oldY; }

    /** Package-visible for testing. */
    int getOldWidth() { return oldWidth; }

    /** Package-visible for testing. */
    int getOldHeight() { return oldHeight; }

    /** Package-visible for testing. */
    int getNewX() { return newX; }

    /** Package-visible for testing. */
    int getNewY() { return newY; }

    /** Package-visible for testing. */
    int getNewWidth() { return newWidth; }

    /** Package-visible for testing. */
    int getNewHeight() { return newHeight; }

    /** Package-visible for testing. */
    String getOldText() { return oldText; }

    /** Package-visible for testing. */
    String getNewText() { return newText; }

    /** Package-visible for testing. */
    boolean hasStylingChange() { return hasStylingChange; }

    /** Package-visible for testing. */
    String getOldFillColor() { return oldFillColor; }

    /** Package-visible for testing. */
    String getNewFillColor() { return newFillColor; }

    /** Package-visible for testing. */
    String getOldLineColor() { return oldLineColor; }

    /** Package-visible for testing. */
    String getNewLineColor() { return newLineColor; }

    /** Package-visible for testing. */
    String getOldFontColor() { return oldFontColor; }

    /** Package-visible for testing. */
    String getNewFontColor() { return newFontColor; }

    /** Package-visible for testing. */
    int getOldAlpha() { return oldAlpha; }

    /** Package-visible for testing. */
    int getNewAlpha() { return newAlpha; }

    /** Package-visible for testing. */
    int getOldLineWidth() { return oldLineWidth; }

    /** Package-visible for testing. */
    int getNewLineWidth() { return newLineWidth; }

    /** Package-visible for testing. */
    Integer getOldFigureType() { return oldFigureType; }

    /** Package-visible for testing. */
    Integer getNewFigureType() { return newFigureType; }

    /** Package-visible for testing. */
    Integer getOldTextAlignment() { return oldTextAlignment; }

    /** Package-visible for testing. */
    Integer getNewTextAlignment() { return newTextAlignment; }

    /** Package-visible for testing. */
    Integer getOldVerticalTextAlignment() { return oldVerticalTextAlignment; }

    /** Package-visible for testing. */
    Integer getNewVerticalTextAlignment() { return newVerticalTextAlignment; }

    /** Package-visible for testing. */
    boolean hasImageChange() { return hasImageChange; }

    /** Package-visible for testing. */
    String getOldImagePath() { return oldImagePath; }

    /** Package-visible for testing. */
    String getNewImagePath() { return newImagePath; }

    /** Package-visible for testing. */
    int getOldImagePosition() { return oldImagePosition; }

    /** Package-visible for testing. */
    int getNewImagePosition() { return newImagePosition; }

    /** Package-visible for testing. */
    int getOldImageSource() { return oldImageSource; }

    /** Package-visible for testing. */
    int getNewImageSource() { return newImageSource; }

    /** Package-visible for testing. */
    int getOldShowIcon() { return oldShowIcon; }

    /** Package-visible for testing. */
    int getNewShowIcon() { return newShowIcon; }

    /** Package-visible for testing. */
    boolean hasLabelExpressionChange() { return hasLabelExpressionChange; }

    /** Package-visible for testing. */
    String getOldLabelExpression() { return oldLabelExpression; }

    /** Package-visible for testing. */
    String getNewLabelExpression() { return newLabelExpression; }

    /** Package-visible for testing. */
    boolean hasAnchorChange() { return hasAnchorChange; }

    /** Package-visible for testing. */
    String getOldAnchorTarget() { return oldAnchorTarget; }

    /** Package-visible for testing. */
    String getNewAnchorTarget() { return newAnchorTarget; }

    /** Package-visible for testing. */
    String getNewAnchorEdge() { return newAnchorEdge; }

    /** Package-visible for testing. */
    String getNewAnchorDx() { return newAnchorDx; }

    /** Package-visible for testing. */
    String getNewAnchorDy() { return newAnchorDy; }

    /** Package-visible for testing. */
    String getOldFont() { return oldFont; }

    /** Package-visible for testing. */
    String getNewFont() { return newFont; }

    /** Package-visible for testing. */
    int getOldGradient() { return oldGradient; }

    /** Package-visible for testing. */
    int getNewGradient() { return newGradient; }

    /** Package-visible for testing. */
    Integer getOldBorderType() { return oldBorderType; }

    /** Package-visible for testing. */
    Integer getNewBorderType() { return newBorderType; }

    /** Package-visible for testing. */
    boolean getOldDeriveLineColor() { return oldDeriveLineColor; }

    /** Package-visible for testing. */
    boolean getNewDeriveLineColor() { return newDeriveLineColor; }

    /** Package-visible for testing. */
    int getOldOutlineOpacity() { return oldOutlineOpacity; }

    /** Package-visible for testing. */
    int getNewOutlineOpacity() { return newOutlineOpacity; }

    /** Package-visible for testing. */
    int getOldLineStyle() { return oldLineStyle; }

    /** Package-visible for testing. */
    int getNewLineStyle() { return newLineStyle; }
}
