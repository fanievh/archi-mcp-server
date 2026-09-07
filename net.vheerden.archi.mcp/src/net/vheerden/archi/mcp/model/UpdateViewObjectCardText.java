package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the human-facing approval-card sentence for {@code update-view-object}.
 *
 * <p>Split out of {@link ArchiModelAccessorImpl} for the same two reasons
 * {@link DeleteApprovalCardText} was: the facade approval branch stays a thin call and does not
 * pay its size ratchet for card prose, and the string logic is unit-tested headlessly here rather
 * than only through the accessor.</p>
 *
 * <p><strong>Why this class exists at all.</strong> The card's visible change row reads the
 * mechanical {@code description}; the {@code proposedChanges} map surfaces only in the collapsed
 * Technical-details / Copy-JSON disclosure. So for a human who does not expand the card, the
 * sentence <em>is</em> the card. {@code update-view-object} announced every call as
 * {@code "Update view object bounds for …"} while the same method also writes text, a label
 * expression, styling, image parameters and four anchor fields — so a caller renaming a box was
 * announcing a move. That is not an incomplete card; it is a card describing the wrong operation.</p>
 *
 * <p><strong>Both of the card's prose fields come from here.</strong> {@link #description} is the
 * mechanical sentence and {@link #validationSummary} is the "what was checked" line beside it, and
 * both read the same aspect computation. That is not tidiness: written separately, one card could
 * announce a rename and validate a move, which is the same false claim one field over. The sibling
 * {@link UpdateViewConnectionCardText} carries the identical pair for {@code update-view-connection}.</p>
 *
 * <p><strong>The prose is derived from the disclosure, not from the parameters.</strong>
 * Both fields read the very {@code proposedChanges} map the card carries, so they can
 * never drift from it or from each other: a field that reaches the map is named in the sentence, and a field the map omits
 * cannot be named. Adding a key to the map is therefore the only thing needed to keep the sentence
 * honest.</p>
 */
final class UpdateViewObjectCardText {

    private UpdateViewObjectCardText() {
    }

    /** The four geometry keys {@link ProposalBuilder#putBounds} contributes. */
    private static final List<String> BOUNDS_KEYS = List.of("x", "y", "width", "height");

    /** The four anchor keys, reported together as one aspect — an anchor is one placement decision. */
    private static final List<String> ANCHOR_KEYS =
            List.of("anchorTarget", "anchorEdge", "anchorDx", "anchorDy");

    /**
     * The {@code update-view-object} card sentence, naming the aspects the call actually changes.
     *
     * <p>A bounds-only call keeps its established wording ({@code "Update view object bounds for
     * <type> '<name>' <viewClause>"}); a call touching anything else names what it touches instead,
     * joined in a fixed order so two calls changing the same aspects always read the same way. A
     * call that sets nothing at all degrades to the neutral {@code "Update view object"} rather
     * than claiming a change it will not make.</p>
     *
     * @param elementType     the view object's type, used as the noun phrase's head
     * @param elementName     the object's name; blank or null degrades to {@code "(untitled)"}
     * @param viewClause      a pre-formatted {@code "in view 'X'"} clause, or null when the owning
     *                        view could not be resolved
     * @param proposedChanges the card's change map, already fully populated
     */
    static String description(String elementType, String elementName, String viewClause,
            Map<String, Object> proposedChanges) {
        return "Update " + aspects(proposedChanges) + " for " + elementType
                + (elementName == null || elementName.isBlank() ? " (untitled)" : " '" + elementName + "'")
                + (viewClause != null ? " " + viewClause : "");
    }

    /**
     * The {@code update-view-object} card's "what was checked" line, naming the same aspects
     * {@link #description} names.
     *
     * <p>A bounds-only call keeps its established wording ({@code "View object bounds ready for
     * update."}); anything else names what it actually touches, and a call disclosing nothing
     * degrades to the neutral {@code "View object ready for update."} rather than asserting a
     * change. This field does not reach the card's collapsed row — it reaches the expanded
     * Technical-details panel and the {@code list-pending-approvals} wire, where an agent reads it
     * verbatim and relays it to the human who will approve.</p>
     *
     * <p>The leading capitalisation relies on {@link #aspects} never returning an empty string —
     * its empty case yields the neutral {@code "view object"}. The sibling
     * {@link UpdateViewConnectionCardText#aspects} deliberately returns {@code ""} instead, and its
     * two callers each supply their own neutral wording. The conventions differ on purpose; making
     * them agree means moving this capitalisation, not just editing the helper.</p>
     */
    static String validationSummary(Map<String, Object> proposedChanges) {
        String aspects = aspects(proposedChanges);
        return Character.toUpperCase(aspects.charAt(0)) + aspects.substring(1) + " ready for update.";
    }

    /**
     * Names the aspects present in the disclosure, in a fixed order. Returns the neutral
     * {@code "view object"} when the map discloses nothing recognised, so the sentence never
     * asserts a change the map does not carry.
     */
    private static String aspects(Map<String, Object> proposedChanges) {
        List<String> parts = new ArrayList<>();
        if (containsAny(proposedChanges, BOUNDS_KEYS)) {
            parts.add("view object bounds");
        }
        if (proposedChanges.containsKey("text")) {
            parts.add("text");
        }
        if (proposedChanges.containsKey("labelExpression")) {
            parts.add("label expression");
        }
        if (proposedChanges.containsKey("styling")) {
            parts.add("styling");
        }
        if (proposedChanges.containsKey("imageParams")) {
            parts.add("image");
        }
        if (containsAny(proposedChanges, ANCHOR_KEYS)) {
            parts.add("anchoring");
        }
        if (parts.isEmpty()) {
            return "view object";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }

    private static boolean containsAny(Map<String, Object> proposedChanges, List<String> keys) {
        for (String key : keys) {
            if (proposedChanges.containsKey(key)) {
                return true;
            }
        }
        return false;
    }
}
