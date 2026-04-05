package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IFontAttribute;
import com.archimatetool.model.ILineObject;

/**
 * GEF Command that replaces the bendpoints of a connection on a view,
 * and optionally updates styling (Stories 7-8, 11-2).
 *
 * <p>Captures the old bendpoints and styling as defensive copies at
 * construction time. On execute, clears existing bendpoints and adds
 * the new set. On undo, restores the original bendpoints and styling.</p>
 *
 * <p><strong>Story 11-2:</strong> Added optional styling support
 * (lineColor, lineWidth, fontColor) for connections.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class UpdateViewConnectionCommand extends Command {

    private final IDiagramModelArchimateConnection connection;
    private final List<IDiagramModelBendpoint> oldBendpoints;
    private final List<IDiagramModelBendpoint> newBendpoints;

    // Styling update support (Story 11-2)
    private final String oldLineColor;
    private final String newLineColor;
    private final String oldFontColor;
    private final String newFontColor;
    private final int oldLineWidth;
    private final int newLineWidth;
    private final boolean hasStylingChange;

    // Label visibility support (Story 13-1)
    private final Boolean oldNameVisible;
    private final Boolean newNameVisible;
    private final boolean hasNameVisibleChange;

    // Label position support (Story 13-11)
    private final int oldTextPosition;
    private final int newTextPosition;
    private final boolean hasTextPositionChange;

    /**
     * Creates a command to replace a connection's bendpoints (no styling change).
     *
     * @param connection    the connection to update
     * @param newBendpoints the new set of bendpoints (may be empty to clear)
     */
    public UpdateViewConnectionCommand(IDiagramModelArchimateConnection connection,
                                        List<IDiagramModelBendpoint> newBendpoints) {
        this(connection, newBendpoints, null, null, null);
    }

    /**
     * Creates a command to replace a connection's bendpoints and optionally update styling.
     *
     * @param connection    the connection to update
     * @param newBendpoints the new set of bendpoints (may be empty to clear)
     * @param styling       styling parameters to apply, null for no styling change
     */
    public UpdateViewConnectionCommand(IDiagramModelArchimateConnection connection,
                                        List<IDiagramModelBendpoint> newBendpoints,
                                        StylingParams styling) {
        this(connection, newBendpoints, styling, null, null);
    }

    /**
     * Creates a command to replace bendpoints, optionally update styling, and optionally
     * toggle label visibility (Story 13-1).
     *
     * @param connection    the connection to update
     * @param newBendpoints the new set of bendpoints (may be empty to clear)
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
     * @param newBendpoints the new set of bendpoints (may be empty to clear)
     * @param styling       styling parameters to apply, null for no styling change
     * @param showLabel     label visibility override, null for no change
     * @param textPosition  label position (0=source, 1=middle, 2=target), null for no change
     */
    public UpdateViewConnectionCommand(IDiagramModelArchimateConnection connection,
                                        List<IDiagramModelBendpoint> newBendpoints,
                                        StylingParams styling, Boolean showLabel,
                                        Integer textPosition) {
        this.connection = connection;
        this.oldBendpoints = new ArrayList<>(connection.getBendpoints());
        this.newBendpoints = new ArrayList<>(newBendpoints);

        // Styling support (Story 11-2) — connections support lineColor, lineWidth, fontColor
        this.hasStylingChange = (styling != null &&
                (styling.lineColor() != null || styling.lineWidth() != null || styling.fontColor() != null));

        if (hasStylingChange) {
            this.oldLineColor = connection.getLineColor();
            this.oldFontColor = connection.getFontColor();
            this.oldLineWidth = connection.getLineWidth();

            this.newLineColor = (styling.lineColor() != null) ? emptyToNull(styling.lineColor()) : oldLineColor;
            this.newFontColor = (styling.fontColor() != null) ? emptyToNull(styling.fontColor()) : oldFontColor;
            this.newLineWidth = (styling.lineWidth() != null) ? styling.lineWidth() : oldLineWidth;
        } else {
            this.oldLineColor = null;
            this.newLineColor = null;
            this.oldFontColor = null;
            this.newFontColor = null;
            this.oldLineWidth = 0;
            this.newLineWidth = 0;
        }

        // Label visibility support (Story 13-1)
        this.hasNameVisibleChange = (showLabel != null);
        if (hasNameVisibleChange) {
            this.oldNameVisible = connection.isNameVisible();
            this.newNameVisible = showLabel;
        } else {
            this.oldNameVisible = null;
            this.newNameVisible = null;
        }

        // Label position support (Story 13-11)
        this.hasTextPositionChange = (textPosition != null);
        if (hasTextPositionChange) {
            this.oldTextPosition = connection.getTextPosition();
            this.newTextPosition = textPosition;
        } else {
            this.oldTextPosition = 0;
            this.newTextPosition = 0;
        }

        setLabel("Update connection");
    }

    @Override
    public void execute() {
        connection.getBendpoints().clear();
        connection.getBendpoints().addAll(newBendpoints);
        if (hasStylingChange) {
            applyStyling(newLineColor, newFontColor, newLineWidth);
        }
        if (hasNameVisibleChange) {
            connection.setNameVisible(newNameVisible);
        }
        if (hasTextPositionChange) {
            connection.setTextPosition(newTextPosition);
        }
    }

    @Override
    public void undo() {
        connection.getBendpoints().clear();
        connection.getBendpoints().addAll(oldBendpoints);
        if (hasStylingChange) {
            applyStyling(oldLineColor, oldFontColor, oldLineWidth);
        }
        if (hasNameVisibleChange) {
            connection.setNameVisible(oldNameVisible);
        }
        if (hasTextPositionChange) {
            connection.setTextPosition(oldTextPosition);
        }
    }

    private void applyStyling(String lineColor, String fontColor, int lineWidth) {
        connection.setLineColor(lineColor);
        connection.setLineWidth(lineWidth);
        connection.setFontColor(fontColor);
    }

    /**
     * Converts empty string to null (Archi EMF stores null for "use default").
     */
    private static String emptyToNull(String value) {
        return (value != null && value.isEmpty()) ? null : value;
    }

    /** Package-visible for testing. */
    IDiagramModelArchimateConnection getConnection() { return connection; }

    /** Package-visible for testing. */
    List<IDiagramModelBendpoint> getOldBendpoints() { return oldBendpoints; }

    /** Package-visible for testing. */
    List<IDiagramModelBendpoint> getNewBendpoints() { return newBendpoints; }

    /** Package-visible for testing. */
    boolean hasStylingChange() { return hasStylingChange; }

    /** Package-visible for testing. */
    String getOldLineColor() { return oldLineColor; }

    /** Package-visible for testing. */
    String getNewLineColor() { return newLineColor; }

    /** Package-visible for testing. */
    String getOldFontColor() { return oldFontColor; }

    /** Package-visible for testing. */
    String getNewFontColor() { return newFontColor; }

    /** Package-visible for testing. */
    int getOldLineWidth() { return oldLineWidth; }

    /** Package-visible for testing. */
    int getNewLineWidth() { return newLineWidth; }

    /** Package-visible for testing. */
    boolean hasNameVisibleChange() { return hasNameVisibleChange; }

    /** Package-visible for testing. */
    Boolean getOldNameVisible() { return oldNameVisible; }

    /** Package-visible for testing. */
    Boolean getNewNameVisible() { return newNameVisible; }

    /** Package-visible for testing. */
    boolean hasTextPositionChange() { return hasTextPositionChange; }

    /** Package-visible for testing. */
    int getOldTextPosition() { return oldTextPosition; }

    /** Package-visible for testing. */
    int getNewTextPosition() { return newTextPosition; }
}
