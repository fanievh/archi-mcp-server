package net.vheerden.archi.mcp.model;

import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Text processing utilities for view annotations.
 *
 * <p>Interprets common escape sequences in text content so that
 * LLM-generated strings containing literal {@code \n}, {@code \t},
 * {@code \r}, or {@code \\} produce actual whitespace characters
 * when stored in the ArchiMate model.</p>
 */
final class TextUtils {

    private TextUtils() {}

    /**
     * Accepts a {@code text} value for a view object: rejects it where it has no meaning, then
     * sanitises and interprets what remains.
     *
     * <p>Text is a group's label or a note's content. An ArchiMate element view object has neither
     * — it shows the name of the element behind it, which is changed through {@code update-element}
     * — so setting text on one is rejected rather than silently applied to a box whose name is
     * derived elsewhere.</p>
     *
     * <p>Lives here, and does the escape interpretation in the same call, because both
     * update-view-object prepares need exactly this pair and a caller that did one without the
     * other would either corrupt an element or store a literal backslash-n. One of the two paths
     * previously had no text parameter at all, so a group created and renamed in a single bulk
     * call silently kept its old name.</p>
     *
     * @param target the object the text is destined for
     * @param text   the requested text, or null to leave it unchanged
     * @return the interpreted text, or null when none was requested
     * @throws ModelAccessException if text was supplied for an element view object
     */
    static String acceptTextFor(IDiagramModelObject target, String text) {
        if (text != null && target instanceof IDiagramModelArchimateObject) {
            throw new ModelAccessException(
                    "Cannot set text on an ArchiMate element view object",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "The 'text' parameter is only valid for groups (label) and notes (content). "
                            + "Use update-element to change an element's name.",
                    null);
        }
        return interpretEscapes(InputValidation.reject(text, "text"));
    }

    /**
     * Re-fits a note's height to its wrapped content when the request changed the wrap and left
     * the height unpinned, mirroring the auto-fit {@code prepareAddNoteToView} applies at create
     * time so that "omit the height and the server fits it" means the same thing on both paths.
     * Before this existed the fit was create-time only, and a note whose body was replaced with a
     * longer one silently clipped while the clip diagnostic prescribed the very thing the caller
     * had already done.
     *
     * <p>Fires only for a note, and only when {@code height} is absent and the request supplies
     * {@code text} or {@code width} — the two inputs that change the wrap. A pure move therefore
     * never resizes. Groups are excluded deliberately: a group's create-time fit sizes a LABEL
     * BAND, a different quantity, and a group's height must also contain its children, so fitting
     * one to its label could shrink it away from what it holds. The declined case is that a group
     * renamed to a longer label still clips its label band.</p>
     *
     * <p>Symmetric, not grow-only: shorter text shrinks the note back towards {@code minHeight}.
     * The server records no positive provenance, so nothing distinguishes a height an author
     * pinned from one a previous fit produced — a caller who wants a fixed size supplies
     * {@code height}, which takes the fixed-size branch here exactly as it does on create.</p>
     *
     * <p>There is one piece of NEGATIVE provenance, and it is honoured at BOTH ends of the clamp:
     * a height outside {@code [minHeight, maxHeight]} cannot have come from this fit, which clamps
     * into that range, so it can only have been pinned deliberately. The rule is that a clamp must
     * never move the height in a direction the CONTENT does not justify — so on update the floor is
     * the lesser of {@code minHeight} and the height the note already holds, and the ceiling is the
     * greater of {@code maxHeight} and that same height. Without the widened floor, a note pinned
     * below the default would be raised to it for nothing; without the widened ceiling, a note
     * pinned above the cap would be clawed back down to it and start clipping. Every note inside
     * the range behaves exactly as the create path does.</p>
     *
     * <p>A LEGEND is excluded along with groups, and for the same reason: Archi sizes a legend from
     * its {@code ILegendOptions} item/column layout, not from its text, so fitting one to
     * {@code getContent()} measures the wrong quantity — a legend whose content is empty would
     * collapse to the floor.</p>
     *
     * <p>Lives here rather than in {@code ElementSizer} because it needs the EMF note type, and
     * {@code ElementSizer} is EMF-free.</p>
     *
     * @param target       the object being updated
     * @param text         the interpreted new text, or null when the request leaves it unchanged
     * @param width        the requested width, or null
     * @param height       a non-null value pins the size and declines the fit
     * @param mergedWidth  the width the object holds after the merge; the wrap is measured against
     *                     this less {@link ElementSizer#HORIZONTAL_TEXT_INSET}
     * @param mergedHeight the height the object would otherwise hold, returned unchanged when this
     *                     request does not re-fit
     * @param minHeight    floor for the fitted height (the note default)
     * @return the fitted height, or {@code mergedHeight} when the fit does not apply
     */
    static int refitNoteHeight(IDiagramModelObject target, String text, Integer width,
                               Integer height, int mergedWidth, int mergedHeight, int minHeight) {
        if (height != null || (text == null && width == null)
                || !(target instanceof IDiagramModelNote note) || note.isLegend()) {
            return mergedHeight;
        }
        // A null text means only the width changed, so the STORED content is what re-wraps — and it
        // is already the rendered form, because escapes are interpreted at write time. A non-null
        // text has been through interpretEscapes above for the same reason, so both measure as-is.
        return ElementSizer.fitTextBoxHeightToContentOrElse(
                (text != null) ? text : note.getContent(),
                Math.max(1, mergedWidth - ElementSizer.HORIZONTAL_TEXT_INSET),
                ElementSizer.LABEL_VERTICAL_PADDING, Math.min(minHeight, mergedHeight),
                Math.max(ElementSizer.MAX_NOTE_HEIGHT, mergedHeight), mergedHeight);
    }

    /**
     * Interprets escape sequences in the given text.
     *
     * <p>Converts:
     * <ul>
     *   <li>{@code \\n} (backslash + n) → newline (U+000A)</li>
     *   <li>{@code \\t} (backslash + t) → tab (U+0009)</li>
     *   <li>{@code \\r} (backslash + r) → carriage return (U+000D)</li>
     *   <li>{@code \\\\} (backslash + backslash) → single backslash</li>
     * </ul>
     *
     * <p>Returns {@code null} if the input is {@code null}.</p>
     *
     * @param text the input text potentially containing escape sequences
     * @return text with escape sequences interpreted, or null if input is null
     */
    static String interpretEscapes(String text) {
        if (text == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
