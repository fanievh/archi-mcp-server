package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the human-facing approval-card prose for {@code update-view-connection}.
 *
 * <p>Split out of {@link ArchiModelAccessorImpl} for the same two reasons
 * {@link UpdateViewObjectCardText} and {@link DeleteApprovalCardText} were: the facade approval
 * branch stays a thin call and does not pay its size ratchet for card prose, and the string logic
 * is unit-tested headlessly here rather than only through the accessor.</p>
 *
 * <p><strong>Why this class exists at all.</strong> Both of the card's prose fields were written
 * once, unconditionally, and both named the polyline — {@code "Update bendpoints for connection
 * (…)"} and {@code "Connection bendpoints ready for update."} — while the same method also writes
 * styling, label visibility and label position. So a call that only hid a label announced, and
 * validated, a change to the route. That is not an incomplete card; it is a card describing an
 * operation the approval will not perform.</p>
 *
 * <p><strong>One computation feeds both fields.</strong> {@link #description} is the mechanical
 * sentence and {@link #validationSummary} the "what was checked" line beside it, and they read the
 * same aspect list. Written separately, one card could announce a restyle and validate a reroute —
 * the same false claim one field over.</p>
 *
 * <p><strong>The prose is derived from the disclosure, not from the parameters.</strong> Both
 * fields read the very {@code proposedChanges} map the card carries, so they can never drift from
 * it: an aspect that reaches the map is named, and one the map omits cannot be named. Note the map
 * keys are {@code bendpointCount} / {@code absoluteBendpointCount}, not the parameter names — a
 * reader keyed on the parameters would silently never fire. {@code ProposalBuilder#putStyling}
 * writes no key at all for an empty styling projection, which is what makes reading the map
 * incapable of over-claiming where reading the parameters would.</p>
 */
final class UpdateViewConnectionCardText {

    private UpdateViewConnectionCardText() {
    }

    /**
     * The two bendpoint keys, reported together as one aspect: relative and absolute bendpoints are
     * two ways of stating one decision about one polyline, so an absolute-only call must not read
     * differently from a relative-only one.
     */
    private static final List<String> BENDPOINT_KEYS =
            List.of("bendpointCount", "absoluteBendpointCount");

    /**
     * The {@code update-view-connection} card sentence, naming the aspects the call actually changes.
     *
     * <p>A bendpoint-only call keeps its established wording ({@code "Update bendpoints for
     * connection (<type>) <viewClause>"}); a call touching anything else names what it touches
     * instead, joined in a fixed order so two calls changing the same aspects always read the same
     * way. A call that sets nothing at all degrades to a bare {@code "Update connection (<type>)"}
     * rather than claiming a change it will not make.</p>
     *
     * <p>This sentence is also the fallback the card's {@code effectDescription} degrades to when
     * the relationship's endpoints or type cannot be resolved, so it is what a human who never
     * expands the card reads on exactly that path. The type is echoed as the branch found it —
     * resolving an unresolvable type is a separate matter this collaborator does not touch.</p>
     *
     * @param relType         the underlying relationship's type, used as the parenthetical
     * @param viewClause      a pre-formatted {@code "in view 'X'"} clause, or null when the owning
     *                        view could not be resolved
     * @param proposedChanges the card's change map, already fully populated
     */
    static String description(String relType, String viewClause,
            Map<String, Object> proposedChanges) {
        String aspects = aspects(proposedChanges);
        return "Update " + (aspects.isEmpty() ? "" : aspects + " for ")
                + "connection (" + relType + ")"
                + (viewClause != null ? " " + viewClause : "");
    }

    /**
     * The {@code update-view-connection} card's "what was checked" line, naming the same aspects
     * {@link #description} names.
     *
     * <p>A bendpoint-only call keeps its established wording ({@code "Connection bendpoints ready
     * for update."}); anything else names what it actually touches, and a call disclosing nothing
     * degrades to the neutral {@code "Connection ready for update."} rather than asserting a change.
     * This field does not reach the card's collapsed row — it reaches the expanded Technical-details
     * panel and the {@code list-pending-approvals} wire, where an agent reads it verbatim and relays
     * it to the human who will approve.</p>
     */
    static String validationSummary(Map<String, Object> proposedChanges) {
        String aspects = aspects(proposedChanges);
        return "Connection" + (aspects.isEmpty() ? "" : " " + aspects) + " ready for update.";
    }

    /**
     * Names the aspects present in the disclosure, in a fixed order. Returns an empty string when
     * the map discloses nothing recognised, so neither field asserts a change the map does not
     * carry; both callers supply their own neutral wording for that case.
     */
    private static String aspects(Map<String, Object> proposedChanges) {
        List<String> parts = new ArrayList<>();
        for (String key : BENDPOINT_KEYS) {
            if (proposedChanges.containsKey(key)) {
                parts.add("bendpoints");
                break;
            }
        }
        if (proposedChanges.containsKey("showLabel")) {
            parts.add("label visibility");
        }
        if (proposedChanges.containsKey("textPosition")) {
            parts.add("label position");
        }
        if (proposedChanges.containsKey("styling")) {
            parts.add("styling");
        }
        if (parts.isEmpty()) {
            return "";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }
}
