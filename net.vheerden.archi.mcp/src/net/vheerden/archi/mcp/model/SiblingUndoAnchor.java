package net.vheerden.archi.mcp.model;

import java.util.List;

/**
 * Restores a removed object to its original slot in a containment list on undo,
 * anchored to a surviving sibling rather than a stale absolute index.
 *
 * <p>An index captured at prepare time goes stale when another command in the same
 * compound removes or restores a sibling in the same list before this command's
 * undo runs: a compound undoes its members in reverse, so a later member restores
 * into a list whose earlier members are not yet back, and an absolute index then
 * lands in the wrong slot. The item that immediately followed the removed object at
 * capture time is a stable anchor — re-inserting directly before it is correct
 * whenever that successor is a survivor or has already been restored. The absolute
 * index remains only as a clamped fallback for the residual case where the successor
 * is itself a not-yet-restored co-removed sibling; order can drift there, but
 * membership never does.</p>
 *
 * <p>Mirrors the mechanism proven in {@link DeleteElementCommand}, extracted here so
 * the single-item delete and move commands share one implementation.</p>
 */
final class SiblingUndoAnchor {

    private SiblingUndoAnchor() {
    }

    /**
     * The item immediately after {@code item} in {@code list} at capture time, or
     * {@code null} if it was last or not present.
     *
     * <p>Relies on EMF default identity equality for {@code indexOf} (the model
     * types here do not override {@code equals}), so a value-equal twin cannot
     * shadow the real anchor. Capture this while the model is still whole — at
     * command construction (prepare time) — so the successor reflects the original
     * membership.</p>
     */
    static <T> T successorOf(List<? extends T> list, T item) {
        int i = list.indexOf(item);
        return (i >= 0 && i + 1 < list.size()) ? list.get(i + 1) : null;
    }

    /**
     * Re-insert {@code item} directly before {@code successorAnchor} if it is present,
     * else at the clamped absolute {@code fallbackIndex}, else append.
     *
     * <p>{@code successorAnchor} may legitimately be {@code null} (the item was last
     * in its list at capture time), which falls straight through to the index/append
     * path — never a failure. It is typed as {@code Object} because it is only ever
     * used as an identity probe via {@code indexOf}, so a caller whose list element
     * type differs from the anchor's captured type still composes.</p>
     */
    static <T> void restore(List<T> list, T item, int fallbackIndex, Object successorAnchor) {
        // Already present → nothing to restore. When one batch queues two deletes that both remove
        // the same object (the same target queued twice, or a folder-cascade delete overlapping a
        // standalone delete of a contained view), the compound's reverse-order undo runs both undo
        // steps: the first re-inserts the object, and the second would re-insert it again. These are
        // EMF containment lists, which forbid duplicates, so a second add throws
        // IllegalArgumentException ("no duplicates") and aborts the undo mid-way. An object that is
        // already in its list has been restored; adding it again is never correct.
        if (list.contains(item)) {
            return;
        }
        int anchorIndex = (successorAnchor != null) ? list.indexOf(successorAnchor) : -1;
        if (anchorIndex >= 0) {
            list.add(anchorIndex, item);
        } else if (fallbackIndex >= 0 && fallbackIndex <= list.size()) {
            list.add(fallbackIndex, item);
        } else {
            list.add(item);
        }
    }
}
