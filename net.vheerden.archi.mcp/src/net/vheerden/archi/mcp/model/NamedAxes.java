package net.vheerden.archi.mcp.model;

import java.util.Map;

import com.archimatetool.model.IDiagramModelObject;

/**
 * Which of a view object's four bounds an update sets. An axis the update does not set keeps the
 * value the object holds when the update runs, rather than a copy taken while the update was
 * prepared — so a batched or approved update that moves an object sideways does not write the
 * prepare-time height or vertical position back over a drag made in between.
 *
 * <p>A command built with a complete rectangle — every layout, spacing, nesting, parent-fit and
 * resize command — sets all four ({@link #ALL}).</p>
 */
record NamedAxes(boolean x, boolean y, boolean width, boolean height) {

    /** Every axis set: a complete rectangle an algorithm computed. */
    static final NamedAxes ALL = new NamedAxes(true, true, true, true);

    /** No axis set: an update that changes only styling, text, image or label. */
    static final NamedAxes NONE = new NamedAxes(false, false, false, false);

    /**
     * The axes an {@code update-view-object} request sets, once its partial bounds have been
     * merged into a complete rectangle.
     *
     * <p>An axis is set when the caller gave it, or when the prepare computed it from something
     * other than the object's current or pending bounds: an anchor resolves x and y, and a note
     * re-fit or an icon band adjusts the height. A height so adjusted was computed for the merged
     * width, so that width is set with it — writing the computed height against a width changed in
     * between would clip or pad the content it was fitted to. An axis taken only from the current
     * or pending bounds is not set — with two exceptions, where all four are treated as set and the
     * merged rectangle is written as before:</p>
     * <ul>
     *   <li>The object is still detached: a queued or same-call add places it when that add runs,
     *       so its bounds at prepare are not something a later run should be read against.</li>
     *   <li>A pass that owns its fit commands already holds a fit for this object. Those fits are
     *       committed after every object has landed, so a pending value taken from one comes from a
     *       command that runs after this one; keeping the current value at execute would read the
     *       rectangle before that fit ran.</li>
     * </ul>
     * <p>Every other pending value — an open batch's queued bounds, a bulk call's per-operation
     * bounds, an earlier entry of the same pass — is produced by a command that runs before this
     * one, so an axis taken from it is not set: at execute it reads what that command wrote, or
     * what was changed after it.</p>
     */
    static NamedAxes forUpdate(IDiagramModelObject obj, Integer x, Integer y, Integer width, Integer height,
            String anchorTarget, boolean heightAdjusted, Map<String, ?> passFits) {
        if (obj.eContainer() == null || (passFits != null && passFits.containsKey(obj.getId()))) {
            return ALL;
        }
        boolean anchored = anchorTarget != null && !anchorTarget.isEmpty();
        return new NamedAxes(x != null || anchored, y != null || anchored, width != null || heightAdjusted,
                height != null || heightAdjusted);
    }
}
