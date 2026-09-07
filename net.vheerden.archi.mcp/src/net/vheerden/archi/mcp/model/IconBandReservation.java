package net.vheerden.archi.mcp.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIconic;

/**
 * Reserves the corner band a container's icon renders into, at the moment a child arrives that
 * would otherwise sit under it.
 *
 * <p>Archi draws a corner-anchored image at a fixed size and clips it to the element box, so a
 * child placed in that corner and the icon occupy the same pixels. The container is grown by a
 * whole number of bands instead — one band clears an occupying child <em>rectangle</em>, a second
 * is added when that child carries its own same-corner icon so the two tiles cannot touch. The
 * over-grow is bounded and one-shot: once the band exists the occupancy test is false.</p>
 *
 * <p>Growing the container can push it out of a group, so the growth continues through the shared
 * {@link ParentFitCascade} rather than stopping at one level — one move means one thing everywhere.
 * Like that walk, this is static and takes every "what does this unit of work already know"
 * fallback as an argument: a container an open batch or a bulk pass created is still detached when
 * the reservation is computed, so live EMF containment and live bounds both answer for a state the
 * caller has already superseded.</p>
 *
 * <p>Extracted from the accessor facade so those fallbacks are visible in the signature rather than
 * read from caller state, and so the facade does not carry geometry it merely triggers.</p>
 */
final class IconBandReservation {

    /**
     * Corner positions the reservation fires for: bottom-left and bottom-right.
     *
     * <p>Top-left is recognised by the geometry predicate but deliberately not acted on — clearing
     * a top corner means shifting every existing sibling down, a far wider change than the
     * collision it avoids. Top-right is Archi's default sentinel and is excluded so a container
     * that never chose a corner keeps byte-identical bounds.</p>
     */
    private static final int CORNER_BOTTOM_LEFT = 6;
    private static final int CORNER_BOTTOM_RIGHT = 8;

    /** Undo label for the compound when the growth also had to grow a group above. */
    private static final String CASCADE_LABEL = "Icon-band parent-resize with grandparent-group cascade";

    private IconBandReservation() {
    }

    /**
     * Returns the command that grows {@code parentContainer} to clear its icon corner, or null when
     * no reservation is needed — the parent is the view itself, carries no image, anchors its image
     * somewhere the lever does not act on, or has an empty corner. A null return leaves the caller's
     * bounds bit-for-bit as they were.
     *
     * <p>Measured against what this unit of work has already decided for the container rather than
     * the bounds the model still holds: a batch that re-sizes a container and then drops a child
     * into its icon corner would otherwise reserve the band against a size the container no longer
     * has, and the resulting absolute resize would silently revert the batch's own earlier growth.</p>
     *
     * @param parentContainer     the resolved parent container; may be the view itself
     * @param newChildX           the arriving child's bounds, relative to the parent
     * @param newChildY           …
     * @param newChildW           …
     * @param newChildH           …
     * @param newChildIconCorner  the arriving child's OWN icon corner, or -1 when it carries no
     *                            image; drives the second-band gate
     * @param padding             padding allowance for the ancestor walk
     * @param queuedBounds        bounds an open batch's queued commands imply, or null
     * @param bulkPendingBounds   the in-flight bulk pass's own record of effective bounds, or null;
     *                            written back to, so a later op of the same pass merges from the
     *                            grown height instead of reverting it
     * @param bulkPendingParents  the bulk pass's {@code id → destined parent} map, or null
     * @param queuedParents       the open batch's {@code id → destined parent} map, or null
     * @param outResizes          receives every object this reservation re-sized, keyed by id, in
     *                            container-then-ancestors order — the container itself plus each
     *                            group the cascade grew. The caller owns it and reports from it:
     *                            these are objects the request never named. Only genuinely grown
     *                            objects are added, so an entry is always an outcome of this call.
     * @return the resize command, possibly compounded with the cascade's group resizes, or null
     */
    static Command reserve(IDiagramModelContainer parentContainer,
            int newChildX, int newChildY, int newChildW, int newChildH,
            int newChildIconCorner, int padding,
            Map<String, int[]> queuedBounds,
            Map<String, int[]> bulkPendingBounds,
            Map<String, IDiagramModelContainer> bulkPendingParents,
            Map<String, IDiagramModelContainer> queuedParents,
            Map<String, Command> outResizes) {
        if (!(parentContainer instanceof IDiagramModelObject parentObj)) {
            return null; // the parent is the view itself — nothing renders an icon there
        }
        if (!(parentObj instanceof IIconic)) {
            return null; // the parent cannot carry an image — nothing to reserve
        }
        int parentImgPos = ImageHelper.readImagePositionInt(parentObj);
        if (parentImgPos != CORNER_BOTTOM_LEFT && parentImgPos != CORNER_BOTTOM_RIGHT) {
            return null;
        }
        int[] pending = AnchorResolver.pendingBounds(queuedBounds, bulkPendingBounds, parentObj.getId());
        IBounds pb = parentObj.getBounds();
        int parentX = (pending != null) ? pending[0] : pb.getX();
        int parentY = (pending != null) ? pending[1] : pb.getY();
        int parentW = (pending != null) ? pending[2] : pb.getWidth();
        int parentH = (pending != null) ? pending[3] : pb.getHeight();

        // Existing siblings (each rect's 5th element is that child's OWN icon corner) plus the
        // prospective new child, in parent-relative coordinates.
        List<int[]> rects = ImageHelper.iconBandChildRects(parentContainer.getChildren());
        rects.add(new int[] {newChildX, newChildY, newChildW, newChildH, newChildIconCorner});

        int reserve = ImageHelper.iconBandReservePx(parentW, parentH, parentImgPos,
                ImageHelper.ICON_SIZE, ImageHelper.ICON_MARGIN, rects);
        if (reserve == 0) {
            return null; // the corner is empty — bounds byte-identical
        }
        int newParentH = parentH + reserve;
        Command parentResize = new UpdateViewObjectCommand(parentObj, parentX, parentY, parentW, newParentH);
        outResizes.put(parentObj.getId(), parentResize);

        // A container this unit of work created is DETACHED while its own add command waits, so
        // live containment answers null exactly when the reservation is being computed and the
        // group above would go unmeasured. Both pending-containment maps close that gap.
        EObject grandparent = parentObj.eContainer();
        if (grandparent == null) {
            grandparent = AnchorResolver.pendingParent(bulkPendingParents, queuedParents, parentObj.getId());
        }
        if (grandparent instanceof IDiagramModelGroup grandparentGroup) {
            Map<String, int[]> virtualGroupBounds = AnchorResolver.seedPending(queuedBounds, bulkPendingBounds);
            Map<String, Command> groupResizeCommands = new LinkedHashMap<>();
            ParentFitCascade.resize(grandparentGroup, parentObj,
                    parentX, parentY, parentW, newParentH, padding,
                    virtualGroupBounds, groupResizeCommands, bulkPendingParents, queuedParents);
            AnchorResolver.recordEffective(bulkPendingBounds, virtualGroupBounds, parentObj.getId(),
                    true, parentX, parentY, parentW, newParentH);
            // groupResizeCommands starts empty and receives an entry only where the walk actually
            // resized, so it never reports a group the walk merely measured against — unlike
            // virtualGroupBounds, which is seeded with what this unit of work already knew.
            outResizes.putAll(groupResizeCommands);
            return AnchorResolver.wrapWithGroupResizes(parentResize, groupResizeCommands, CASCADE_LABEL);
        }
        // No group above, but the reservation still re-sized the container: record it so a later op
        // of the same bulk pass merges from the grown height instead of reverting it. In a batch
        // this is already free — the resize is queued and the queued map is derived from the queue
        // — so this write only matters on the bulk path, whose map a prepare must fill.
        AnchorResolver.recordEffective(bulkPendingBounds, null, parentObj.getId(),
                true, parentX, parentY, parentW, newParentH);
        return parentResize;
    }
}
