package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link LabelPolicy}.
 *
 * <p>The load-bearing property here is that hiding is <strong>opt-in</strong>: an absent parameter
 * must resolve to the keep-everything policy, and only the one explicit value may hide anything.</p>
 */
public class LabelPolicyTest {

    @Test
    public void shouldResolveToKeep_whenValueIsNull() {
        assertEquals(LabelPolicy.KEEP, LabelPolicy.parse(null));
    }

    @Test
    public void shouldResolveToKeep_whenValueIsBlank() {
        assertEquals(LabelPolicy.KEEP, LabelPolicy.parse("   "));
    }

    @Test
    public void shouldResolveAutoHide_whenValueMatches() {
        assertEquals(LabelPolicy.AUTO_HIDE_ON_COLLISION, LabelPolicy.parse("auto-hide-on-collision"));
    }

    @Test
    public void shouldResolveCaseInsensitively_andIgnoreSurroundingSpace() {
        assertEquals(LabelPolicy.AUTO_HIDE_ON_COLLISION, LabelPolicy.parse("  Auto-Hide-On-Collision "));
    }

    @Test
    public void shouldReturnNull_whenValueIsUnrecognised() {
        // Rejected rather than silently defaulted: a caller who misspells the opt-in must be told,
        // not quietly handed a pass that does nothing.
        assertNull(LabelPolicy.parse("auto-hide"));
        assertNull(LabelPolicy.parse("hide"));
    }

    @Test
    public void keepShouldNeverHideALabel() {
        assertFalse("keep is the default and must never hide anything",
                LabelPolicy.KEEP.hidesUnplaceableLabels());
    }

    @Test
    public void autoHideShouldBeTheOnlyPolicyThatHides() {
        int hiding = 0;
        for (LabelPolicy policy : LabelPolicy.values()) {
            if (policy.hidesUnplaceableLabels()) {
                hiding++;
            }
        }
        assertEquals("exactly one policy may hide labels", 1, hiding);
        assertTrue(LabelPolicy.AUTO_HIDE_ON_COLLISION.hidesUnplaceableLabels());
    }

    @Test
    public void defaultPolicyShouldBeKeep() {
        // The contract the tool spec's "default": "keep" depends on.
        assertEquals("keep", LabelPolicy.KEEP.wireValue());
        assertEquals(LabelPolicy.KEEP, LabelPolicy.parse(""));
    }

    @Test
    public void allowedValuesShouldListEveryWireValue() {
        String allowed = LabelPolicy.allowedValues();
        for (LabelPolicy policy : LabelPolicy.values()) {
            assertTrue("allowedValues must name " + policy.wireValue(),
                    allowed.contains(policy.wireValue()));
        }
    }
}
