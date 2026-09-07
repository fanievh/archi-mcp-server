package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Direct branch coverage for {@link SiblingUndoAnchor}. The command-level tests
 * only ever drive the "surviving successor found" branch (they delete siblings in
 * ascending order); these exercise the fallback-index and append branches and both
 * {@code successorOf} outcomes in isolation, so a regression in the residual paths —
 * the ones the byte-identical single-delete guarantee and the documented order drift
 * rely on — fails here even though the happy path stays green. Uses plain
 * {@code String} lists: the helper is generic and independent of the EMF element type.
 */
public class SiblingUndoAnchorTest {

    // ---- successorOf ----

    @Test
    public void shouldReturnFollowingSibling_whenItemHasSuccessor() {
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        assertSame("Successor of b is c", "c", SiblingUndoAnchor.successorOf(list, "b"));
    }

    @Test
    public void shouldReturnNull_whenItemIsTail() {
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        assertNull("Tail item has no successor", SiblingUndoAnchor.successorOf(list, "c"));
    }

    @Test
    public void shouldReturnNull_whenItemAbsent() {
        List<String> list = new ArrayList<>(List.of("a", "b"));
        assertNull("Absent item has no successor", SiblingUndoAnchor.successorOf(list, "z"));
    }

    // ---- restore: anchor branch ----

    @Test
    public void shouldInsertBeforeSurvivingAnchor_ignoringFallbackIndex() {
        // Anchor present → it wins even when the fallback index is deliberately wrong.
        List<String> list = new ArrayList<>(List.of("a", "c", "d"));
        SiblingUndoAnchor.restore(list, "b", 99, "c");
        assertEquals(List.of("a", "b", "c", "d"), list);
    }

    @Test
    public void shouldInsertBeforeAnchor_evenWhenAnchorMovedFromCapturedIndex() {
        // The anchor is a stable identity, not a position: it drifted left since capture.
        List<String> list = new ArrayList<>(List.of("c", "d"));
        SiblingUndoAnchor.restore(list, "b", 2, "c");
        assertEquals(List.of("b", "c", "d"), list);
    }

    // ---- restore: fallback-index branch ----

    @Test
    public void shouldInsertAtFallbackIndex_whenAnchorNull() {
        List<String> list = new ArrayList<>(List.of("a", "c", "d"));
        SiblingUndoAnchor.restore(list, "b", 1, null);
        assertEquals(List.of("a", "b", "c", "d"), list);
    }

    @Test
    public void shouldInsertAtFallbackIndex_whenAnchorNotYetRestored() {
        // Anchor is a co-removed sibling not yet back in the list → treated as absent.
        List<String> list = new ArrayList<>(List.of("a", "c"));
        SiblingUndoAnchor.restore(list, "b", 1, "z");
        assertEquals(List.of("a", "b", "c"), list);
    }

    // ---- restore: append branch ----

    @Test
    public void shouldAppend_whenAnchorNullAndFallbackBeyondSize() {
        List<String> list = new ArrayList<>(List.of("a"));
        SiblingUndoAnchor.restore(list, "b", 99, null);
        assertEquals(List.of("a", "b"), list);
    }

    @Test
    public void shouldAppend_whenFallbackNegativeAndNoAnchor() {
        List<String> list = new ArrayList<>(List.of("a"));
        SiblingUndoAnchor.restore(list, "b", -1, null);
        assertEquals(List.of("a", "b"), list);
    }

    // ---- restore: already-present guard ----
    // When one batch removes the same object twice (same target queued twice, or a
    // folder-cascade delete overlapping a standalone delete of a contained view), the
    // compound's reverse-order undo runs two restores of the same item. The second must
    // be a no-op: the real containment lists forbid duplicates and would throw. These use
    // a plain list (which WOULD allow a duplicate) so the guard, not the list type, is
    // what keeps the item single.

    @Test
    public void shouldNotReAddItem_whenAlreadyPresent_anchorBranch() {
        List<String> list = new ArrayList<>(List.of("a", "b", "c"));
        SiblingUndoAnchor.restore(list, "b", 0, "c");
        assertEquals("An item already in its list is not re-added", List.of("a", "b", "c"), list);
    }

    @Test
    public void shouldNotReAddItem_whenAlreadyPresent_fallbackAndAppendBranches() {
        List<String> viaFallback = new ArrayList<>(List.of("a", "b", "c"));
        SiblingUndoAnchor.restore(viaFallback, "b", 1, null);
        assertEquals("Already present → no re-add even on the fallback-index path",
                List.of("a", "b", "c"), viaFallback);

        List<String> viaAppend = new ArrayList<>(List.of("a", "b"));
        SiblingUndoAnchor.restore(viaAppend, "b", 99, null);
        assertEquals("Already present → no re-add even on the append path",
                List.of("a", "b"), viaAppend);
    }
}
