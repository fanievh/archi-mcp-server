package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.LabelPolicy;
import net.vheerden.archi.mcp.response.dto.HiddenLabelDto;

/**
 * The reporting contract for hidden labels: what the response is allowed to claim.
 *
 * <p>Each case here corresponds to a way the report could tell the caller something untrue — claim a
 * hide that did not happen, omit one that did, or attribute to the policy a hide the caller made
 * themselves.</p>
 */
public class LabelVisibilityReadbackTest {

    // --- merge: a second routing pass in the same call must not lose its hides ---

    @Test
    public void merge_shouldKeepHidesFromBothPasses() {
        // An auto-nudge retry re-routes failed connections and its commands really execute, so a
        // label it hides must still be named. Losing it would hide a label with no trace at all.
        List<String> merged = LabelVisibilityReadback.merge(List.of("a"), List.of("b"));

        assertEquals(List.of("a", "b"), merged);
    }

    @Test
    public void merge_shouldNotNameTheSameConnectionTwice() {
        List<String> merged = LabelVisibilityReadback.merge(List.of("a", "b"), List.of("b", "c"));

        assertEquals(List.of("a", "b", "c"), merged);
    }

    @Test
    public void merge_shouldTolerateNullAndEmptyOnEitherSide() {
        assertEquals(List.of("a"), LabelVisibilityReadback.merge(List.of("a"), null));
        assertEquals(List.of("a"), LabelVisibilityReadback.merge(List.of("a"), List.of()));
        assertEquals(List.of("b"), LabelVisibilityReadback.merge(null, List.of("b")));
        assertEquals(List.of("b"), LabelVisibilityReadback.merge(List.of(), List.of("b")));
        assertTrue(LabelVisibilityReadback.merge(null, null).isEmpty());
    }

    // --- excludeQueuedHides: never claim a hide the caller queued themselves ---

    @Test
    public void excludeQueuedHides_shouldDropAConnectionAQueuedCommandAlreadyHides() {
        // Inside a batch the routing pass reads a model none of the batch's commands have touched,
        // so a label an earlier queued operation already hid still reads as visible. Claiming it
        // would report a false cause for the caller's own decision.
        Map<String, Boolean> queued = new LinkedHashMap<>();
        queued.put("already-hidden", Boolean.FALSE);

        List<String> owned = LabelVisibilityReadback.excludeQueuedHides(
                List.of("already-hidden", "policy-hid-this"), queued);

        assertEquals(List.of("policy-hid-this"), owned);
    }

    @Test
    public void excludeQueuedHides_shouldKeepAConnectionAQueuedCommandMakesVisible() {
        // A queued showLabel:true is not a queued hide — the policy is still the cause of any hide
        // that follows it, so it must remain reported rather than vanish from the audit trail.
        Map<String, Boolean> queued = new LinkedHashMap<>();
        queued.put("c1", Boolean.TRUE);

        assertEquals(List.of("c1"),
                LabelVisibilityReadback.excludeQueuedHides(List.of("c1"), queued));
    }

    @Test
    public void excludeQueuedHides_shouldBeANoOp_whenNotInABatch() {
        // Outside a batch the live read was already authoritative; null means "no open batch".
        assertEquals(List.of("c1", "c2"),
                LabelVisibilityReadback.excludeQueuedHides(List.of("c1", "c2"), null));
        assertEquals(List.of("c1"),
                LabelVisibilityReadback.excludeQueuedHides(List.of("c1"), Map.of()));
    }

    @Test
    public void excludeQueuedHides_shouldTolerateAnEmptyHideList() {
        assertTrue(LabelVisibilityReadback.excludeQueuedHides(List.of(), Map.of("a", false)).isEmpty());
        assertTrue(LabelVisibilityReadback.excludeQueuedHides(null, Map.of("a", false)).isEmpty());
    }

    // --- projected / report: what a deferred response may say ---

    @Test
    public void projected_shouldNameEveryIdWithoutTouchingTheModel() {
        // The deferred paths have written nothing, so there is nothing to re-read; the projection is
        // the only honest content, and the envelope nests it under preview.
        List<HiddenLabelDto> projected = LabelVisibilityReadback.projected(List.of("c1", "c2"));

        assertEquals(2, projected.size());
        assertEquals("c1", projected.get(0).connectionId());
        assertEquals(HiddenLabelDto.REASON_NO_VALID_POSITION, projected.get(0).reason());
    }

    @Test
    public void report_shouldReturnTheProjection_whenTheWriteIsDeferred() {
        // applied=false must not consult the model at all — passing a null model proves it does not.
        List<HiddenLabelDto> reported = LabelVisibilityReadback.report(null, List.of("c1"), false);

        assertEquals(1, reported.size());
        assertEquals("c1", reported.get(0).connectionId());
    }

    @Test
    public void report_shouldClaimNothing_whenTheModelCannotConfirmTheWrite() {
        // applied=true with a model that resolves nothing: the write cannot be confirmed, so the
        // response must not claim it. Reporting a hide that did not happen is the failure this
        // whole re-read exists to prevent.
        List<HiddenLabelDto> reported = LabelVisibilityReadback.report(null, List.of("c1"), true);

        assertTrue("an unconfirmable hide must not be reported", reported.isEmpty());
    }

    @Test
    public void report_shouldReturnEmpty_whenNothingWasHidden() {
        assertTrue(LabelVisibilityReadback.report(null, List.of(), true).isEmpty());
        assertTrue(LabelVisibilityReadback.report(null, null, false).isEmpty());
    }

    // --- describeFrozenHides: the approval card ---

    @Test
    public void describeFrozenHides_shouldNameEveryAffectedConnection() {
        String card = LabelVisibilityReadback.describeFrozenHides(List.of("c1", "c2"));

        assertTrue(card.contains("c1"));
        assertTrue(card.contains("c2"));
        assertTrue("the reviewer must be told how many", card.contains("2"));
    }

    @Test
    public void describeFrozenHides_shouldSayNothing_whenNothingWillBeHidden() {
        assertEquals("", LabelVisibilityReadback.describeFrozenHides(List.of()));
        assertEquals("", LabelVisibilityReadback.describeFrozenHides(null));
    }

    @Test
    public void describeFrozenHides_shouldReadAsSingular_forOneLabel() {
        String card = LabelVisibilityReadback.describeFrozenHides(List.of("c1"));

        assertTrue(card.contains("label has"));
        assertFalse(card.contains("labels have"));
    }

    // --- requireRoutingPass: refuse rather than silently ignore ---

    @Test
    public void requireRoutingPass_shouldRejectAHidingPolicyOnANonRoutingPath() {
        try {
            LabelVisibilityReadback.requireRoutingPass(
                    LabelPolicy.AUTO_HIDE_ON_COLLISION, "terminals-only mode", "use the full router");
            fail("a path that runs no label optimizer must refuse the policy");
        } catch (ModelAccessException e) {
            assertTrue(e.getMessage().contains("labelPolicy"));
            assertTrue(e.getMessage().contains("terminals-only mode"));
            assertEquals("use the full router", e.getSuggestedCorrection());
        }
    }

    @Test
    public void requireRoutingPass_shouldAllowKeepAndNull() {
        // Default-off must never be blocked: the refusal is scoped to the opt-in.
        LabelVisibilityReadback.requireRoutingPass(LabelPolicy.KEEP, "terminals-only mode", "x");
        LabelVisibilityReadback.requireRoutingPass(null, "terminals-only mode", "x");
    }
}
