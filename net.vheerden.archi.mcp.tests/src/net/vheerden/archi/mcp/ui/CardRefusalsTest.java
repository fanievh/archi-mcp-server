package net.vheerden.archi.mcp.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Set;

import org.junit.Test;

/**
 * The refusal a card was given on approve, kept by the Pending Approvals view so the reason and the
 * disabled Approve button survive the view being redrawn, until the card leaves the queue or the
 * human refreshes.
 */
public class CardRefusalsTest {

    private final CardRefusals refusals = new CardRefusals();

    @Test
    public void shouldKeepTheReason_perSessionAndProposal() {
        refusals.record("s1", "p-1", "wrote nothing");

        assertEquals("wrote nothing", refusals.reasonFor("s1", "p-1"));
        assertNull("another session's p-1 is another card", refusals.reasonFor("s2", "p-1"));
        assertNull(refusals.reasonFor("s1", "p-2"));
    }

    @Test
    public void shouldForgetACard_thatIsNoLongerQueued() {
        refusals.record("s1", "p-1", "first");
        refusals.record("s1", "p-2", "second");

        refusals.keepOnly(Set.of(CardRefusals.key("s1", "p-2")));

        assertNull(refusals.reasonFor("s1", "p-1"));
        assertEquals("second", refusals.reasonFor("s1", "p-2"));
    }

    @Test
    public void shouldForgetEverything_onClear() {
        refusals.record("s1", "p-1", "first");

        refusals.clear();

        assertNull(refusals.reasonFor("s1", "p-1"));
    }

    @Test
    public void shouldKeepTheLatestReason_whenACardIsRefusedAgain() {
        refusals.record("s1", "p-1", "first");
        refusals.record("s1", "p-1", "second");

        assertEquals("second", refusals.reasonFor("s1", "p-1"));
    }
}
