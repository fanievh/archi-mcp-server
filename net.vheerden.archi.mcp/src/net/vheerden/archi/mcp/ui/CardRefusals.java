package net.vheerden.archi.mcp.ui;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The reason each pending card was refused on approve. Held by the view rather than the card widget:
 * a refused approval can itself change the queue (the card is taken off and put back), and the redraw
 * that follows would otherwise drop the reason the human has not yet read and re-enable Approve.
 * Proposal ids are minted per session, so a card is keyed by both. Not thread-safe: SWT thread only.
 */
final class CardRefusals {

    private final Map<String, String> reasons = new HashMap<>();

    static String key(String sessionId, String proposalId) {
        return sessionId + '\u0000' + proposalId;
    }

    void record(String sessionId, String proposalId, String reason) {
        reasons.put(key(sessionId, proposalId), reason);
    }

    /** The reason this card was last refused, or null when it was not. */
    String reasonFor(String sessionId, String proposalId) {
        return reasons.get(key(sessionId, proposalId));
    }

    /** Forgets every card whose {@link #key} is not among {@code queued}. */
    void keepOnly(Collection<String> queued) {
        reasons.keySet().retainAll(queued);
    }

    void clear() {
        reasons.clear();
    }
}
