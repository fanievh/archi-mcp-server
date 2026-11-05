package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * GEF Command that replaces the bendpoints of a connection on a view,
 * and optionally updates styling (Stories 7-8, 11-2).
 *
 * <p><strong>What undo restores is read when the command runs, not when it is built.</strong> On
 * execute it copies the bendpoints, and every styling, label-visibility and label-position field it
 * is about to write, from the connection as it meets it; undo restores exactly that. The two moments
 * differ whenever something runs between them: a proposal is built when the agent proposes and runs
 * when the human approves, and a batched update is built when it is queued and runs when the batch
 * commits. A snapshot taken at construction made Undo throw away whatever ran in between — the
 * agent's own later change, an earlier update in the same batch, or an edit the human made while the
 * batch was open.</p>
 *
 * <p><strong>Only the fields the caller named are written.</strong> A styling field the caller did not
 * name is neither written nor restored, and the font is merged from the font the connection holds
 * when the command runs. Filling the unnamed fields from a construction-time copy wrote that copy back
 * over anything that had changed them since, exactly the bendpoint trap described next.</p>
 *
 * <p><strong>Redo replays the first execute.</strong> {@link #redo()} writes the values the first
 * execute computed and keeps its snapshot, rather than reading the connection again.</p>
 *
 * <p><strong>Bendpoints are gated like every other field.</strong> A null
 * {@code newBendpoints} means the caller asked for no bendpoint change and the
 * command leaves them alone; an empty list means clear, and still does. The two
 * were indistinguishable while the write was unconditional, so a styling-only
 * update built from a prepare-time snapshot wrote that snapshot back over
 * whatever had run in between — inside one batch or one bulk call, a second
 * update silently discarded the first's bendpoints. The prepare has always known
 * which of the two the caller meant; only this command could not be told.</p>
 *
 * <p><strong>Absolute bendpoints are converted when the command runs.</strong> Archi stores
 * each bendpoint as two offsets, one from each end's centre, so a canvas point means something
 * only against where the ends are. Given the caller's absolute points, this command converts them
 * in {@link #execute()}, against the ends as they stand then: by that time every command ahead of
 * it in the same compound or batch has run, so a position the same call or an earlier queued
 * operation gave an end, a group move that carried a nested end, or an anchored child that moved
 * with its target is already in the bounds it reads. Converted when the command was built, the
 * offsets were measured from where the ends were before any of that, and the two halves of the
 * stored route stopped meeting. The first conversion is kept, so a redo writes exactly what the
 * first execute wrote. A connection that is no longer on a view by then, or whose ends are no
 * longer ArchiMate view objects, gets the list converted when the command was built, which is what
 * every caller got before; throwing here would abort the whole compound.</p>
 *
 * <p><strong>Styling support:</strong> Added optional styling support
 * (lineColor, lineWidth, fontColor) for connections.</p>
 *
 * <p><strong>Typography:</strong> Extended the styling rail with typography
 * (fontName / fontSize / fontStyle merged through the composite-string
 * {@code IFontAttribute.setFont(String)}). Note: {@code lineStyle} is a view-object
 * property in Archi 5.8 — applied by {@link UpdateViewObjectCommand}, NOT on
 * connections (empirical correction).
 * Constructor arity unchanged — new fields ride the existing {@link StylingParams} parameter.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class UpdateViewConnectionCommand extends Command {

    private final IDiagramModelArchimateConnection connection;
    /** What the connection held when the command last ran; null until then. */
    private List<IDiagramModelBendpoint> oldBendpoints;
    private final List<IDiagramModelBendpoint> newBendpoints;
    private final boolean hasBendpointChange;
    /** The caller's canvas points, or null when the bendpoints were given in Archi's relative form. */
    private final List<AbsoluteBendpointDto> absoluteBendpoints;
    /** What the first execute converted {@link #absoluteBendpoints} to; null until then. */
    private List<IDiagramModelBendpoint> convertedBendpoints;

    // Styling: what the caller named (the *Given gates) and its values. The old values are read
    // when the command runs, so they are null / 0 until then.
    private final boolean lineColorGiven;
    private final String newLineColor;
    private final boolean fontColorGiven;
    private final String newFontColor;
    private final boolean lineWidthGiven;
    private final int newLineWidth;
    private final boolean hasStylingChange;
    private String oldLineColor;
    private String oldFontColor;
    private int oldLineWidth;

    // Typography: the caller's font parts, merged at execute through StylingHelper.assembleFontString
    // into the font the connection holds then. lineStyle is view-object-only in Archi 5.8 and is not
    // written on connections — see UpdateViewObjectCommand.
    private final boolean fontGiven;
    private final String fontName;
    private final Integer fontSize;
    private final String fontStyle;
    private String oldFont;
    /** The font the first execute wrote; null until then, or when no font part was given. */
    private String newFont;

    // Label visibility support
    private Boolean oldNameVisible;
    private final Boolean newNameVisible;
    private final boolean hasNameVisibleChange;

    // Label position support
    private int oldTextPosition;
    private final int newTextPosition;
    private final boolean hasTextPositionChange;

    /** Whether execute has taken its snapshot and computed what it writes; redo replays those. */
    private boolean executed;

    /**
     * Creates a command to replace a connection's bendpoints (no styling change).
     *
     * @param connection    the connection to update
     * @param newBendpoints the new set of bendpoints, empty to clear, null for no change
     */
    public UpdateViewConnectionCommand(IDiagramModelArchimateConnection connection,
                                        List<IDiagramModelBendpoint> newBendpoints) {
        this(connection, newBendpoints, null, null, null);
    }

    /**
     * Creates a command to replace a connection's bendpoints and optionally update styling.
     *
     * @param connection    the connection to update
     * @param newBendpoints the new set of bendpoints, empty to clear, null for no change
     * @param styling       styling parameters to apply, null for no styling change
     */
    public UpdateViewConnectionCommand(IDiagramModelArchimateConnection connection,
                                        List<IDiagramModelBendpoint> newBendpoints,
                                        StylingParams styling) {
        this(connection, newBendpoints, styling, null, null);
    }

    /**
     * Creates a command to replace bendpoints, optionally update styling, and optionally
     * toggle label visibility.
     *
     * @param connection    the connection to update
     * @param newBendpoints the new set of bendpoints, empty to clear, null for no change
     * @param styling       styling parameters to apply, null for no styling change
     * @param showLabel     label visibility override, null for no change
     */
    public UpdateViewConnectionCommand(IDiagramModelArchimateConnection connection,
                                        List<IDiagramModelBendpoint> newBendpoints,
                                        StylingParams styling, Boolean showLabel) {
        this(connection, newBendpoints, styling, showLabel, null);
    }

    /**
     * Creates a command to replace bendpoints, optionally update styling, toggle label
     * visibility, and/or set label position (Stories 13-1, 13-11).
     *
     * @param connection    the connection to update
     * @param newBendpoints the new set of bendpoints, empty to clear, null for no change
     * @param styling       styling parameters to apply, null for no styling change
     * @param showLabel     label visibility override, null for no change
     * @param textPosition  label position (0=source, 1=middle, 2=target), null for no change
     */
    public UpdateViewConnectionCommand(IDiagramModelArchimateConnection connection,
                                        List<IDiagramModelBendpoint> newBendpoints,
                                        StylingParams styling, Boolean showLabel,
                                        Integer textPosition) {
        this(connection, newBendpoints, null, styling, showLabel, textPosition);
    }

    /**
     * Creates a command whose bendpoints may have been given as canvas points, converted when the
     * command runs rather than now (see the class comment).
     *
     * @param connection         the connection to update
     * @param newBendpoints      the bendpoints in Archi's relative form, empty to clear, null for
     *                           no change; with absolute points, their conversion against the ends
     *                           as they stood when this command was built, written only if the
     *                           conversion cannot be repeated when it runs
     * @param absoluteBendpoints the caller's canvas points, converted at execute; null or empty when
     *                           the bendpoints were given in the relative form
     * @param styling            styling parameters to apply, null for no styling change
     * @param showLabel          label visibility override, null for no change
     * @param textPosition       label position (0=source, 1=middle, 2=target), null for no change
     */
    public UpdateViewConnectionCommand(IDiagramModelArchimateConnection connection,
                                        List<IDiagramModelBendpoint> newBendpoints,
                                        List<AbsoluteBendpointDto> absoluteBendpoints,
                                        StylingParams styling, Boolean showLabel,
                                        Integer textPosition) {
        this.connection = connection;
        // Null means the caller asked for no bendpoint change; an empty list means clear.
        this.hasBendpointChange = (newBendpoints != null);
        this.newBendpoints = hasBendpointChange
                ? new ArrayList<>(newBendpoints) : new ArrayList<>();
        this.absoluteBendpoints = (hasBendpointChange && absoluteBendpoints != null
                && !absoluteBendpoints.isEmpty()) ? List.copyOf(absoluteBendpoints) : null;

        // Styling support — connections support lineColor, lineWidth,
        // fontColor, and typography (fontName/Size/Style). lineStyle is view-object-only.
        this.hasStylingChange = (styling != null &&
                (styling.lineColor() != null || styling.lineWidth() != null || styling.fontColor() != null
                 || styling.fontName() != null || styling.fontSize() != null || styling.fontStyle() != null));

        this.lineColorGiven = hasStylingChange && styling.lineColor() != null;
        this.newLineColor = lineColorGiven ? emptyToNull(styling.lineColor()) : null;
        this.fontColorGiven = hasStylingChange && styling.fontColor() != null;
        this.newFontColor = fontColorGiven ? emptyToNull(styling.fontColor()) : null;
        this.lineWidthGiven = hasStylingChange && styling.lineWidth() != null;
        this.newLineWidth = lineWidthGiven ? styling.lineWidth() : 0;
        this.fontGiven = hasStylingChange
                && (styling.fontName() != null || styling.fontSize() != null || styling.fontStyle() != null);
        this.fontName = fontGiven ? styling.fontName() : null;
        this.fontSize = fontGiven ? styling.fontSize() : null;
        this.fontStyle = fontGiven ? styling.fontStyle() : null;

        this.hasNameVisibleChange = (showLabel != null);
        this.newNameVisible = showLabel;

        this.hasTextPositionChange = (textPosition != null);
        this.newTextPosition = hasTextPositionChange ? textPosition : 0;

        setLabel("Update connection");
    }

    /**
     * Snapshots what undo restores from the connection as it is now, computes what to write — the
     * font merged into the font it holds now — and writes it. See the class comment for why both
     * happen here and not in the constructor.
     */
    @Override
    public void execute() {
        if (executed) {
            // Run again without an undo between: what is there now is what this command wrote, so a
            // fresh snapshot would make undo restore it. Keep the first snapshot and replay.
            write();
            return;
        }
        if (hasBendpointChange) {
            oldBendpoints = new ArrayList<>(connection.getBendpoints());
        }
        if (lineColorGiven) {
            oldLineColor = connection.getLineColor();
        }
        if (fontColorGiven) {
            oldFontColor = connection.getFontColor();
        }
        if (lineWidthGiven) {
            oldLineWidth = connection.getLineWidth();
        }
        if (fontGiven) {
            oldFont = connection.getFont();
            newFont = StylingHelper.assembleFontString(oldFont, fontName, fontSize, fontStyle);
        }
        if (hasNameVisibleChange) {
            oldNameVisible = connection.isNameVisible();
        }
        if (hasTextPositionChange) {
            oldTextPosition = connection.getTextPosition();
        }
        executed = true;
        write();
    }

    /**
     * Writes exactly what the first execute wrote, keeping its snapshot: the state an undo leaves is
     * the one that snapshot was read from, so reading it again could only agree or be wrong.
     */
    @Override
    public void redo() {
        execute();
    }

    @Override
    public void undo() {
        if (!executed) {
            return; // nothing was written, so there is nothing to restore
        }
        if (hasBendpointChange) {
            connection.getBendpoints().clear();
            connection.getBendpoints().addAll(oldBendpoints);
        }
        if (lineColorGiven) {
            connection.setLineColor(oldLineColor);
        }
        if (lineWidthGiven) {
            connection.setLineWidth(oldLineWidth);
        }
        if (fontColorGiven) {
            connection.setFontColor(oldFontColor);
        }
        if (fontGiven) {
            connection.setFont(oldFont);
        }
        if (hasNameVisibleChange) {
            connection.setNameVisible(oldNameVisible);
        }
        if (hasTextPositionChange) {
            connection.setTextPosition(oldTextPosition);
        }
    }

    /** Writes every field the caller named, and no other. */
    private void write() {
        if (hasBendpointChange) {
            connection.getBendpoints().clear();
            connection.getBendpoints().addAll(bendpointsToWrite());
        }
        if (lineColorGiven) {
            connection.setLineColor(newLineColor);
        }
        if (lineWidthGiven) {
            connection.setLineWidth(newLineWidth);
        }
        if (fontColorGiven) {
            connection.setFontColor(newFontColor);
        }
        if (fontGiven) {
            connection.setFont(newFont);
        }
        if (hasNameVisibleChange) {
            connection.setNameVisible(newNameVisible);
        }
        if (hasTextPositionChange) {
            connection.setTextPosition(newTextPosition);
        }
    }

    /** The relative bendpoints this execute writes: converted once from canvas points, or as given. */
    private List<IDiagramModelBendpoint> bendpointsToWrite() {
        if (absoluteBendpoints == null) {
            return newBendpoints;
        }
        if (convertedBendpoints == null) {
            convertedBendpoints = (connection.getDiagramModel() != null
                    && connection.getSource() instanceof IDiagramModelArchimateObject source
                    && connection.getTarget() instanceof IDiagramModelArchimateObject target)
                    ? ConnectionResponseBuilder.createEmfBendpoints(
                            ConnectionResponseBuilder.convertAbsoluteToRelative(absoluteBendpoints, source, target))
                    : newBendpoints;
        }
        return convertedBendpoints;
    }

    /**
     * Converts empty string to null (Archi EMF stores null for "use default").
     */
    private static String emptyToNull(String value) {
        return (value != null && value.isEmpty()) ? null : value;
    }

    /** Package-visible for testing. */
    IDiagramModelArchimateConnection getConnection() { return connection; }

    /** The bendpoints the last execute found, which undo restores; null before the first execute. */
    List<IDiagramModelBendpoint> getOldBendpoints() { return oldBendpoints; }

    /**
     * The bendpoints as prepared. With canvas points this is their conversion against the ends as
     * they stood when the command was built, written only when execute cannot convert again, so it
     * is not necessarily what was stored. Package-visible for testing.
     */
    List<IDiagramModelBendpoint> getNewBendpoints() { return newBendpoints; }

    /** The canvas points converted at execute, or null when the bendpoints were given relative. */
    List<AbsoluteBendpointDto> getAbsoluteBendpoints() { return absoluteBendpoints; }

    /**
     * Whether this command writes the bendpoints at all; {@link #getNewBendpoints()} is empty both for
     * a clear and for no change.
     */
    boolean hasBendpointChange() { return hasBendpointChange; }

    /** Package-visible for testing. */
    boolean hasStylingChange() { return hasStylingChange; }

    /**
     * The line colour the last execute found; null before the first execute or when not named.
     * Package-visible for testing.
     */
    String getOldLineColor() { return oldLineColor; }

    /** The caller's line colour; null when not named. Package-visible for testing. */
    String getNewLineColor() { return newLineColor; }

    /**
     * The font colour the last execute found; null before the first execute or when not named.
     * Package-visible for testing.
     */
    String getOldFontColor() { return oldFontColor; }

    /** The caller's font colour; null when not named. Package-visible for testing. */
    String getNewFontColor() { return newFontColor; }

    /**
     * The line width the last execute found; 0 before the first execute or when not named. Package-
     * visible for testing.
     */
    int getOldLineWidth() { return oldLineWidth; }

    /** The caller's line width; 0 when not named. Package-visible for testing. */
    int getNewLineWidth() { return newLineWidth; }

    /** Package-visible for testing. */
    boolean hasNameVisibleChange() { return hasNameVisibleChange; }

    /**
     * The label visibility the last execute found; null before the first execute or when not named.
     * Package-visible for testing.
     */
    Boolean getOldNameVisible() { return oldNameVisible; }

    /** Package-visible for testing. */
    Boolean getNewNameVisible() { return newNameVisible; }

    /** Package-visible for testing. */
    boolean hasTextPositionChange() { return hasTextPositionChange; }

    /**
     * The label position the last execute found; 0 before the first execute or when not named.
     * Package-visible for testing.
     */
    int getOldTextPosition() { return oldTextPosition; }

    /** Package-visible for testing. */
    int getNewTextPosition() { return newTextPosition; }

    /**
     * The font the last execute found; null before the first execute or when no font part was named.
     * Package-visible for testing.
     */
    String getOldFont() { return oldFont; }

    /**
     * The font the first execute wrote, merged into the font it found; null before then, or when no
     * font part was named. Package-visible for testing.
     */
    String getNewFont() { return newFont; }
}
