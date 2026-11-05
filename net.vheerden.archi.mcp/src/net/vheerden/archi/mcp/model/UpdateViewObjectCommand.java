package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImageProvider;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFontAttribute;
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
 * <p><strong>When it reads the object.</strong> The constructor keeps only what the caller asked
 * for and which fields this kind of object holds. Everything undo restores is read in
 * {@link #execute()}, from the object as it stands when the update runs. The two moments differ
 * whenever something runs between them: a proposal is built when the agent proposes and runs when
 * the human approves, and a batched update is built when it is queued and runs when the batch
 * commits. A snapshot taken when the command was built made Undo throw away whatever ran in
 * between — the agent's own later change, an edit the human made while the batch was open, or an
 * earlier update in the same batch.</p>
 *
 * <p><strong>Only what was named is written.</strong> A styling or image field is written only
 * when the caller named it and this kind of object holds it, and undo restores only the fields
 * that were written. A field the caller did not name is neither written nor restored, so two
 * queued updates on one object each keep what the other set. The caller merges partial bounds
 * into a complete rectangle before creating this command, and says which of its four axes the
 * update sets ({@link NamedAxes}); an axis it does not set keeps the value the object holds when the
 * update runs. Every constructor without that argument sets all four.</p>
 *
 * <p><strong>The snapshot is re-armed by undo.</strong> {@code execute()} takes its snapshot when
 * the command is armed: it is armed when built and again by every {@code undo()}. A second
 * {@code execute()} with no undo between keeps the snapshot it has, because the object then holds
 * what this command wrote. Re-arming matters because the layout tools run and undo these commands
 * outside the command stack while they measure candidates, before the real run; a snapshot kept
 * from such a run would make Undo land on the speculative state. That is where this command differs
 * from {@link UpdateViewConnectionCommand}, which keeps its first snapshot for redo. {@code redo()}
 * is GEF's default — run {@code execute()} again — and after an undo it reads the state that undo
 * left.</p>
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
 * undo-unit guarantee as the colour/opacity/lineWidth fields. For these three an empty string
 * means "no change".</p>
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
 * (fontName / fontSize / fontStyle merged through {@code IFontAttribute.setFont} into the font the
 * object holds when the update runs),
 * {@code gradient} (typed {@code IDiagramModelObject.setGradient(int)}, default
 * {@code GRADIENT_NONE=-1}), note-only {@code borderType} (typed
 * {@code IBorderType.setBorderType(int)} on {@code IDiagramModelNote}),
 * {@code deriveLineColor} (typed {@code setDeriveElementLineColor(boolean)}, Archi
 * default {@code true}), and {@code outlineOpacity} (typed {@code setLineAlpha(int)},
 * Archi default 255). All discovered in an EMF spike — typed setters
 * on Archi 5.8, no IFeatures-backed storage needed. For gradient, border type and line style an
 * empty string resets to the default.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class UpdateViewObjectCommand extends Command {

    private final IDiagramModelObject diagramObject;
    private final int newX;
    private final int newY;
    private final int newWidth;
    private final int newHeight;
    // Which of the four the update sets; the rest keep what the object holds when it runs.
    private final NamedAxes axes;

    // Text update support
    private final String newText;
    private final boolean hasTextChange;

    // Styling: what the caller named, decided once because an object's kind never changes. A
    // String field carries a *Given gate because null is a value it can write (an empty string
    // clears it); a boxed field is null when the caller did not name it or this kind of object
    // does not hold it.
    private final boolean hasStylingChange;
    private final boolean fillColorGiven;
    private final String newFillColor;
    private final boolean lineColorGiven;
    private final String newLineColor;
    private final boolean fontColorGiven;
    private final String newFontColor;
    private final Integer newAlpha;
    private final Integer newLineWidth;
    private final Integer newFigureType;
    private final Integer newTextAlignment;
    private final Integer newVerticalTextAlignment;
    // hasFontChange = true only when at least one typography field (fontName/Size/Style) was
    // named on an object that holds a font. The three are merged into the font the object holds
    // when the update runs, so an earlier change to another typography field survives.
    private final boolean hasFontChange;
    private final String fontName;
    private final Integer fontSize;
    private final String fontStyle;
    private final Integer newGradient;
    private final Integer newBorderType;          // note-only
    private final Boolean newDeriveLineColor;
    private final Integer newOutlineOpacity;
    private final Integer newLineStyle;

    // Image: the same rule. The image source is never named: it follows a named image path
    // (custom when a path is set, the profile's when it is cleared) and is not written otherwise.
    private final boolean hasImageChange;
    private final boolean imagePathGiven;
    private final String newImagePath;
    private final Integer newImagePosition;
    private final Integer newImageSource;
    private final Integer newShowIcon;

    // Label-expression rail. Stored as a generic IFeatures
    // entry on the diagram object (key "labelExpression"), NOT a typed EMF
    // getter/setter (EMF finding).
    // hasLabelExpressionChange = true when caller passed a non-null value
    // (including empty string, which clears the feature).
    private final String newLabelExpression;
    private final boolean hasLabelExpressionChange;

    /** Feature-list key for the Archi-side label-expression renderer (matches Archi's own constant). */
    static final String LABEL_EXPRESSION_FEATURE = "labelExpression";

    // Anchor rail — view-anchored positioning. Four generic IFeatures entries on the
    // child object recording that its position is relative to a target container
    // (anchorTarget id + anchorEdge + anchorDx/anchorDy) rather than a frozen absolute
    // snapshot. Same single-undo-unit discipline as the label-expression rail, and the same
    // execute-time snapshot. hasAnchorChange = true when the caller passed a non-null
    // anchorTarget (empty string clears all four entries).
    private final String newAnchorTarget;
    private final String newAnchorEdge;
    private final String newAnchorDx;
    private final String newAnchorDy;
    private final boolean hasAnchorChange;

    // The anchor target's bounds as they stood when this command was built, or null when the
    // target did not resolve in the same diagram then (which includes every command that sets no
    // anchor at all — see newAnchorTarget, which gates the whole rail). This is NOT undo state:
    // it is the prepare-time comparison below, and stays a build-time read on purpose.
    //
    // The caller resolves an anchor into a concrete newX/newY while preparing the request. On the
    // deferred paths — a queued batch — nothing has executed yet, which breaks that resolution in
    // two distinct ways, and execute() has to tell them apart:
    //
    //   * The target resolved at prepare, but a command queued earlier moves or resizes it before
    //     this one runs. Comparing the live target against this snapshot separates "has not moved"
    //     (write the prepared position verbatim — every non-deferred call lands here) from "moved"
    //     (resolve the edge again).
    //   * The target did not resolve at prepare AT ALL, because either object was still detached:
    //     every add-*-to-view defers containment to its own command's execute(), and the lookup
    //     walks committed containment from the anchored object's diagram. There is no snapshot to
    //     compare against, so the has-not-moved test is skipped and the edge is resolved outright.
    //     Queue order guarantees the adds have run by then, so both objects are attached and the
    //     information the prepare lacked is available.
    private final int[] anchorTargetBoundsAtPrepare;

    // ---- the snapshot undo restores, read by execute() from the object as it meets it -------
    // Each field is read only when the field it restores is written. Before the first execute the
    // getters answer null, 0 or false.

    /** Whether execute has taken the snapshot that undo restores; undo clears it (re-arms). */
    private boolean snapshotTaken;
    private int oldX;
    private int oldY;
    private int oldWidth;
    private int oldHeight;
    private String oldText;
    private String oldFillColor;
    private String oldLineColor;
    private String oldFontColor;
    private int oldAlpha;
    private int oldLineWidth;
    private Integer oldFigureType;
    /**
     * Whether the object takes a figure type when this update runs. It is read with the snapshot, not
     * when built: whether an ArchiMate box takes one depends on its element being a Grouping, and an
     * earlier queued change can make it something else. Undo writes back exactly what execute wrote.
     */
    private boolean figureTypeHeld;
    private Integer oldTextAlignment;
    private Integer oldVerticalTextAlignment;
    private String oldFont;
    /** The font the last execute wrote: the named typography merged into the font it met. */
    private String newFont;
    private int oldGradient;
    private Integer oldBorderType;
    private boolean oldDeriveLineColor;
    private int oldOutlineOpacity;
    private int oldLineStyle;
    private String oldImagePath;
    private int oldImagePosition;
    private int oldImageSource;
    private int oldShowIcon;
    private String oldLabelExpression;
    private String oldAnchorTarget;
    private String oldAnchorEdge;
    private String oldAnchorDx;
    private String oldAnchorDy;

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
        this(diagramObject, newX, newY, newWidth, newHeight, newText, styling, imageParams,
                newLabelExpression, anchorTarget, anchorEdge, anchorDx, anchorDy, NamedAxes.ALL);
    }

    /**
     * As the label-expression constructor, setting only the axes {@code axes} names: the merged
     * rectangle supplies a value for every axis, and an axis not named keeps, when the update runs,
     * the value the object holds then.
     */
    public UpdateViewObjectCommand(IDiagramModelObject diagramObject,
                                    int newX, int newY, int newWidth, int newHeight,
                                    String newText, StylingParams styling,
                                    ImageParams imageParams, String newLabelExpression,
                                    NamedAxes axes) {
        this(diagramObject, newX, newY, newWidth, newHeight, newText, styling, imageParams,
                newLabelExpression, null, null, null, null, axes);
    }

    /**
     * As the anchor constructor, setting only the axes {@code axes} names. See
     * {@link NamedAxes#forUpdate} for which axes a partial update sets.
     */
    public UpdateViewObjectCommand(IDiagramModelObject diagramObject,
                                    int newX, int newY, int newWidth, int newHeight,
                                    String newText, StylingParams styling,
                                    ImageParams imageParams, String newLabelExpression,
                                    String anchorTarget, String anchorEdge,
                                    Integer anchorDx, Integer anchorDy, NamedAxes axes) {
        this.diagramObject = diagramObject;
        this.axes = axes;
        this.newX = newX;
        this.newY = newY;
        this.newWidth = newWidth;
        this.newHeight = newHeight;
        this.newText = newText;
        this.hasTextChange = (newText != null);

        // Styling: each field is named when the caller passed it and this kind of object holds it.
        this.hasStylingChange = (styling != null && styling.hasAnyValue());
        StylingParams s = hasStylingChange ? styling : StylingParams.NONE;
        this.fillColorGiven = s.fillColor() != null;
        this.newFillColor = emptyToNull(s.fillColor());
        this.lineColorGiven = s.lineColor() != null && diagramObject instanceof ILineObject;
        this.newLineColor = lineColorGiven ? emptyToNull(s.lineColor()) : null;
        this.fontColorGiven = s.fontColor() != null && diagramObject instanceof IFontAttribute;
        this.newFontColor = fontColorGiven ? emptyToNull(s.fontColor()) : null;
        this.newAlpha = s.opacity();
        this.newLineWidth = (diagramObject instanceof ILineObject) ? s.lineWidth() : null;
        // For figure type and the two alignments an empty string means "no change".
        this.newFigureType = (named(s.figureType()) && ViewObjectFieldsByKind.holdsFigureType(diagramObject))
                ? Integer.valueOf(StylingHelper.mapFigureTypeToInt(s.figureType())) : null;
        this.newTextAlignment = (named(s.textAlignment()) && diagramObject instanceof ITextAlignment)
                ? Integer.valueOf(StylingHelper.mapTextAlignmentToInt(s.textAlignment())) : null;
        this.newVerticalTextAlignment = (named(s.verticalTextAlignment())
                && ViewObjectFieldsByKind.holdsVerticalTextAlignment(diagramObject))
                ? Integer.valueOf(StylingHelper.mapVerticalTextAlignmentToInt(s.verticalTextAlignment())) : null;
        this.hasFontChange = (diagramObject instanceof IFontAttribute)
                && (s.fontName() != null || s.fontSize() != null || s.fontStyle() != null);
        this.fontName = s.fontName();
        this.fontSize = s.fontSize();
        this.fontStyle = s.fontStyle();
        // For gradient, border type and line style an empty string resets to the default.
        this.newGradient = (s.gradient() == null) ? null
                : s.gradient().isEmpty() ? Integer.valueOf(IDiagramModelObject.GRADIENT_NONE)
                : Integer.valueOf(StylingHelper.mapGradientToInt(s.gradient()));
        // Note-only borderType — a note, NOT any IBorderType
        // (which would also match IDiagramModelGroup and collide with figureType).
        this.newBorderType = (s.borderType() == null || !ViewObjectFieldsByKind.holdsBorderType(diagramObject)) ? null
                : s.borderType().isEmpty() ? Integer.valueOf(IDiagramModelNote.BORDER_DOGEAR)
                : Integer.valueOf(StylingHelper.mapBorderTypeToInt(s.borderType()));
        this.newDeriveLineColor = s.deriveLineColor();
        this.newOutlineOpacity = s.outlineOpacity();
        this.newLineStyle = (s.lineStyle() == null) ? null
                : s.lineStyle().isEmpty() ? Integer.valueOf(IDiagramModelObject.LINE_STYLE_DEFAULT)
                : Integer.valueOf(StylingHelper.mapLineStyleToInt(s.lineStyle()));

        // An object with an icon holds an icon path; a standalone image visual holds its picture on the
        // same attribute, which the update replaces. Its other image fields are refused before this
        // command is built, so for an image only the path ever changes.
        this.hasImageChange = imageParams != null && imageParams.hasAnyValue()
                && diagramObject instanceof IDiagramModelImageProvider;
        ImageParams i = hasImageChange ? imageParams : ImageParams.NONE;
        this.imagePathGiven = i.imagePath() != null;
        this.newImagePath = emptyToNull(i.imagePath());
        this.newImagePosition = (i.imagePosition() != null && diagramObject instanceof IIconic)
                ? Integer.valueOf(ImageParams.positionToInt(i.imagePosition())) : null;
        this.newShowIcon = (i.showIcon() != null) ? Integer.valueOf(ImageParams.showIconToInt(i.showIcon())) : null;
        this.newImageSource = (imagePathGiven && diagramObject instanceof IDiagramModelArchimateObject)
                ? Integer.valueOf(newImagePath != null
                        ? IDiagramModelArchimateObject.IMAGE_SOURCE_CUSTOM
                        : IDiagramModelArchimateObject.IMAGE_SOURCE_PROFILE)
                : null;

        // Label-expression rail. Generic IFeatures storage,
        // not a typed setter — see class-level note.
        this.hasLabelExpressionChange = (newLabelExpression != null);
        this.newLabelExpression = emptyToNull(newLabelExpression);

        // Anchor rail. Generic IFeatures storage (four keys), mirroring the label-expression
        // rail. Non-null anchorTarget = change requested; empty string clears all four.
        this.hasAnchorChange = (anchorTarget != null);
        if (hasAnchorChange && !anchorTarget.isEmpty()) {
            this.newAnchorTarget = anchorTarget;
            this.newAnchorEdge = (anchorEdge != null && !anchorEdge.isEmpty())
                    ? anchorEdge : AnchorResolver.DEFAULT_EDGE;
            this.newAnchorDx = String.valueOf(anchorDx != null ? anchorDx : 0);
            this.newAnchorDy = String.valueOf(anchorDy != null ? anchorDy : 0);
        } else {
            // No anchor change, or a clear — which removes all four entries.
            this.newAnchorTarget = null;
            this.newAnchorEdge = null;
            this.newAnchorDx = null;
            this.newAnchorDy = null;
        }
        this.anchorTargetBoundsAtPrepare = captureBounds(
                AnchorResolver.findInSameDiagram(diagramObject, this.newAnchorTarget));

        setLabel("Update view object");
    }

    private static boolean named(String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * Snapshots, from the object as it is now, every field this update writes — unless a snapshot
     * is already held because execute ran with no undo since — then writes them. See the class
     * comment for why the snapshot is taken here and not in the constructor.
     */
    @Override
    public void execute() {
        if (!snapshotTaken) {
            takeSnapshot();
            snapshotTaken = true;
        }
        // An axis the update does not set keeps what the object holds now, which the snapshot
        // just read (or, on a second run with no undo between, what the first run left there).
        int execX = axes.x() ? newX : oldX;
        int execY = axes.y() ? newY : oldY;
        int execWidth = axes.width() ? newWidth : oldWidth;
        int execHeight = axes.height() ? newHeight : oldHeight;
        // Gated on the anchor being set rather than on the snapshot existing: a null snapshot is
        // also what "the target was unresolvable at prepare" looks like, and that case still has
        // to be resolved here. A snapshot can only be non-null when newAnchorTarget is, so this
        // strictly widens the old condition — commands built with the short constructors, and
        // every anchor-clearing update, leave newAnchorTarget null and never enter.
        if (newAnchorTarget != null) {
            int[] moved = resolveAgainstMovedAnchorTarget(execWidth, execHeight);
            if (moved != null) {
                execX = moved[0];
                execY = moved[1];
            }
        }
        diagramObject.setBounds(execX, execY, execWidth, execHeight);
        if (hasTextChange) {
            applyText(newText);
        }
        if (hasStylingChange) {
            if (hasFontChange) {
                newFont = StylingHelper.assembleFontString(oldFont, fontName, fontSize, fontStyle);
            }
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

    /**
     * Restores what execute wrote, as the snapshot found it, and re-arms the snapshot for the next
     * execute. A bounds axis the update does not set is left as it is now: execute kept it, so
     * restoring it would only undo a change made to it since.
     */
    @Override
    public void undo() {
        if (!snapshotTaken) {
            return; // nothing was written, so there is nothing to restore
        }
        IBounds now = diagramObject.getBounds();
        diagramObject.setBounds(axes.x() ? oldX : now.getX(), axes.y() ? oldY : now.getY(),
                axes.width() ? oldWidth : now.getWidth(), axes.height() ? oldHeight : now.getHeight());
        if (hasTextChange) {
            applyText(oldText);
        }
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
        snapshotTaken = false;
    }

    /** Reads the old value of every field this update writes, from the object as it is now. */
    private void takeSnapshot() {
        IBounds b = diagramObject.getBounds();
        oldX = b.getX();
        oldY = b.getY();
        oldWidth = b.getWidth();
        oldHeight = b.getHeight();
        if (hasTextChange) {
            if (diagramObject instanceof IDiagramModelGroup group) {
                oldText = group.getName();
            } else if (diagramObject instanceof ITextContent textContent) {
                oldText = textContent.getContent();
            }
        }
        if (hasStylingChange) {
            if (fillColorGiven) oldFillColor = diagramObject.getFillColor();
            if (lineColorGiven) oldLineColor = ((ILineObject) diagramObject).getLineColor();
            if (fontColorGiven) oldFontColor = ((IFontAttribute) diagramObject).getFontColor();
            if (newAlpha != null) oldAlpha = diagramObject.getAlpha();
            if (newLineWidth != null) oldLineWidth = ((ILineObject) diagramObject).getLineWidth();
            figureTypeHeld = newFigureType != null && ViewObjectFieldsByKind.holdsFigureType(diagramObject);
            if (figureTypeHeld) oldFigureType = readFigureTypeInt(diagramObject);
            if (newTextAlignment != null) oldTextAlignment = ((ITextAlignment) diagramObject).getTextAlignment();
            if (newVerticalTextAlignment != null) {
                oldVerticalTextAlignment = ((ITextPosition) diagramObject).getTextPosition();
            }
            if (hasFontChange) oldFont = ((IFontAttribute) diagramObject).getFont();
            if (newGradient != null) oldGradient = diagramObject.getGradient();
            if (newBorderType != null) oldBorderType = ((IDiagramModelNote) diagramObject).getBorderType();
            if (newDeriveLineColor != null) oldDeriveLineColor = diagramObject.getDeriveElementLineColor();
            if (newOutlineOpacity != null) oldOutlineOpacity = diagramObject.getLineAlpha();
            if (newLineStyle != null) oldLineStyle = diagramObject.getLineStyle();
        }
        if (hasImageChange) {
            if (imagePathGiven) oldImagePath = ((IDiagramModelImageProvider) diagramObject).getImagePath();
            if (newImagePosition != null) oldImagePosition = ((IIconic) diagramObject).getImagePosition();
            if (newImageSource != null) {
                oldImageSource = ((IDiagramModelArchimateObject) diagramObject).getImageSource();
            }
            if (newShowIcon != null) oldShowIcon = ImageHelper.readShowIconInt(diagramObject);
        }
        if (hasLabelExpressionChange) {
            oldLabelExpression = diagramObject.getFeatures().getString(LABEL_EXPRESSION_FEATURE, null);
        }
        if (hasAnchorChange) {
            oldAnchorTarget = diagramObject.getFeatures().getString(AnchorResolver.ANCHOR_TARGET_FEATURE, null);
            oldAnchorEdge = diagramObject.getFeatures().getString(AnchorResolver.ANCHOR_EDGE_FEATURE, null);
            oldAnchorDx = diagramObject.getFeatures().getString(AnchorResolver.ANCHOR_DX_FEATURE, null);
            oldAnchorDy = diagramObject.getFeatures().getString(AnchorResolver.ANCHOR_DY_FEATURE, null);
        }
    }

    /**
     * The anchor edge re-resolved against the target's live bounds, or {@code null} to keep the
     * position computed when the command was built.
     *
     * <p>Null is returned — deliberately, never an exception — when the target has since left the
     * diagram, is the object itself, sits in a different container (bounds are stored relative to
     * the immediate parent, so resolving across coordinate spaces would write the wrong numbers),
     * or has not moved at all. The same-space and self-anchor conditions are rejected up front
     * when the request is validated; repeating them here is a fallback, not a second validation
     * site, so a target that changed shape after validation degrades to the prepared position
     * rather than failing a commit that is already under way. That degrade-never-throw rule is
     * what lets the no-snapshot case share this method: a pairing the prepare could not even see —
     * so could not validate — reaches these same fallbacks instead of a late exception.</p>
     *
     * <p>With no snapshot there is nothing to have moved relative to, so the has-not-moved test is
     * skipped and whatever the target now holds is resolved outright. The prepared position it
     * replaces was computed with the anchor ignored, so keeping it would silently drop the
     * caller's request.</p>
     */
    private int[] resolveAgainstMovedAnchorTarget(int width, int height) {
        IDiagramModelObject target = AnchorResolver.findInSameDiagram(diagramObject, newAnchorTarget);
        if (target == null || target == diagramObject
                || target.eContainer() != diagramObject.eContainer()) {
            return null;
        }
        IBounds live = target.getBounds();
        if (anchorTargetBoundsAtPrepare != null
                && live.getX() == anchorTargetBoundsAtPrepare[0]
                && live.getY() == anchorTargetBoundsAtPrepare[1]
                && live.getWidth() == anchorTargetBoundsAtPrepare[2]
                && live.getHeight() == anchorTargetBoundsAtPrepare[3]) {
            return null;
        }
        return AnchorResolver.resolveByEdge(newAnchorEdge,
                live.getX(), live.getY(), live.getWidth(), live.getHeight(),
                width, height,
                AnchorResolver.parseOffset(newAnchorDx), AnchorResolver.parseOffset(newAnchorDy));
    }

    private static int[] captureBounds(IDiagramModelObject obj) {
        if (obj == null) {
            return null;
        }
        IBounds b = obj.getBounds();
        return new int[] { b.getX(), b.getY(), b.getWidth(), b.getHeight() };
    }

    private void applyText(String text) {
        if (diagramObject instanceof IDiagramModelGroup group) {
            group.setName(text);
        } else if (diagramObject instanceof ITextContent textContent) {
            textContent.setContent(text);
        }
    }

    /**
     * Writes the styling fields this update names, and no other: the gates are the fields of the
     * caller's request, so execute and undo write the same set. The values are either the caller's
     * or the snapshot's.
     */
    private void applyStyling(String fillColor, String lineColor, String fontColor,
                               Integer alpha, Integer lineWidth,
                               Integer figureType, Integer textAlignment, Integer verticalTextAlignment,
                               String font, Integer gradient, Integer borderType,
                               Boolean deriveLineColor, Integer outlineOpacity, Integer lineStyle) {
        if (fillColorGiven) diagramObject.setFillColor(fillColor);
        if (newAlpha != null) diagramObject.setAlpha(alpha);
        if (lineColorGiven) ((ILineObject) diagramObject).setLineColor(lineColor);
        if (newLineWidth != null) ((ILineObject) diagramObject).setLineWidth(lineWidth);
        if (fontColorGiven) ((IFontAttribute) diagramObject).setFontColor(fontColor);
        if (hasFontChange) ((IFontAttribute) diagramObject).setFont(font);
        if (figureTypeHeld) {
            if (diagramObject instanceof IDiagramModelGroup g) {
                g.setBorderType(figureType);
            } else {
                ((IDiagramModelArchimateObject) diagramObject).setType(figureType);
            }
        }
        if (newTextAlignment != null) ((ITextAlignment) diagramObject).setTextAlignment(textAlignment);
        if (newVerticalTextAlignment != null) ((ITextPosition) diagramObject).setTextPosition(verticalTextAlignment);
        if (newGradient != null) diagramObject.setGradient(gradient);
        if (newDeriveLineColor != null) diagramObject.setDeriveElementLineColor(deriveLineColor);
        if (newOutlineOpacity != null) diagramObject.setLineAlpha(outlineOpacity);
        if (newLineStyle != null) diagramObject.setLineStyle(lineStyle);
        if (newBorderType != null) ((IDiagramModelNote) diagramObject).setBorderType(borderType);
    }

    private static Integer readFigureTypeInt(IDiagramModelObject obj) {
        if (obj instanceof IDiagramModelGroup g) return g.getBorderType();
        return ((IDiagramModelArchimateObject) obj).getType();
    }

    /** Writes the image fields this update names, and no other. */
    private void applyImage(String imagePath, Integer imagePosition, Integer imageSource, Integer showIcon) {
        if (imagePathGiven) ((IDiagramModelImageProvider) diagramObject).setImagePath(imagePath);
        if (newImagePosition != null) ((IIconic) diagramObject).setImagePosition(imagePosition);
        if (newImageSource != null) ((IDiagramModelArchimateObject) diagramObject).setImageSource(imageSource);
        // showIcon (iconVisibleState) is on IDiagramModelObject, available for all types
        if (newShowIcon != null) diagramObject.setIconVisibleState(showIcon);
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

    // ---- getters. The getOld* getters answer the last execute's snapshot, and null, 0 or false
    // before the first execute or for a field the update does not write. getNewX/Y/Width/Height and
    // the anchor getters are the caller's intent, which the batch readers project queued geometry
    // from; the styling getNew* getters are the caller's intent too, null when not named.

    /** Package-visible for testing. */
    IDiagramModelObject getDiagramObject() { return diagramObject; }

    /** The text this update gives the object (a group's name, a note's content), or null when it leaves it alone. */
    String getNewText() { return hasTextChange ? newText : null; }

    /** The value the last run's snapshot read; package-visible for testing. */
    int getOldX() { return oldX; }

    /** The value the last run's snapshot read; package-visible for testing. */
    int getOldY() { return oldY; }

    /** The value the last run's snapshot read; package-visible for testing. */
    int getOldWidth() { return oldWidth; }

    /** The value the last run's snapshot read; package-visible for testing. */
    int getOldHeight() { return oldHeight; }

    /** The x the caller asked for, which the batch readers project queued geometry from. */
    int getNewX() { return newX; }

    /** The y the caller asked for, which the batch readers project queued geometry from. */
    int getNewY() { return newY; }

    /** The width the caller asked for, which the batch readers project queued geometry from. */
    int getNewWidth() { return newWidth; }

    /** The height the caller asked for, which the batch readers project queued geometry from. */
    int getNewHeight() { return newHeight; }

    /** Which of the four bounds this update writes when it runs; read by the batch readers. */
    NamedAxes getAxes() { return axes; }

    /** Package-visible for testing. */
    boolean hasStylingChange() { return hasStylingChange; }

    /** The value the last run's snapshot read; package-visible for testing. */
    String getOldFillColor() { return oldFillColor; }

    /** Package-visible for testing; null when the caller did not name it (or cleared it). */
    String getNewFillColor() { return newFillColor; }

    /** The value the last run's snapshot read; package-visible for testing. */
    String getOldLineColor() { return oldLineColor; }

    /** Package-visible for testing; null when the caller did not name it (or cleared it). */
    String getNewLineColor() { return newLineColor; }

    /** The value the last run's snapshot read; package-visible for testing. */
    Integer getOldFigureType() { return oldFigureType; }

    /** Package-visible for testing. */
    Integer getNewFigureType() { return newFigureType; }

    /** Package-visible for testing. */
    boolean hasLabelExpressionChange() { return hasLabelExpressionChange; }

    /** The value the last run's snapshot read; package-visible for testing. */
    String getOldLabelExpression() { return oldLabelExpression; }

    /** Package-visible for testing. */
    String getNewLabelExpression() { return newLabelExpression; }

    /** Whether this update sets or clears the anchor; read by the batch readers. */
    boolean hasAnchorChange() { return hasAnchorChange; }

    /** Package-visible for testing. */
    String getNewAnchorTarget() { return newAnchorTarget; }

    /** Package-visible for testing. */
    String getNewAnchorEdge() { return newAnchorEdge; }

    /** Package-visible for testing. */
    String getNewAnchorDx() { return newAnchorDx; }

    /** Package-visible for testing. */
    String getNewAnchorDy() { return newAnchorDy; }

    /** The value the last run's snapshot read; package-visible for testing. */
    int getOldGradient() { return oldGradient; }

    /** Package-visible for testing. */
    Integer getNewGradient() { return newGradient; }

    /** The value the last run's snapshot read; package-visible for testing. */
    Integer getOldBorderType() { return oldBorderType; }

    /** Package-visible for testing. */
    Integer getNewBorderType() { return newBorderType; }

    /** The value the last run's snapshot read; package-visible for testing. */
    boolean getOldDeriveLineColor() { return oldDeriveLineColor; }

    /** Package-visible for testing. */
    Boolean getNewDeriveLineColor() { return newDeriveLineColor; }

    /** The value the last run's snapshot read; package-visible for testing. */
    int getOldOutlineOpacity() { return oldOutlineOpacity; }

    /** Package-visible for testing. */
    Integer getNewOutlineOpacity() { return newOutlineOpacity; }
}
