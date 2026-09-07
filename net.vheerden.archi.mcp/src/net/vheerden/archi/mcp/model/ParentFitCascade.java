package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.ResizedGroupDto;

/**
 * The parent-fit cascade: grows a group so it still contains a child that has been moved or
 * resized past its edge, then repeats the question one level up.
 *
 * <p>Every layer that can displace a child funnels through here — the auto-nudge pass, the
 * spacing tools, the icon-band reservation, {@code update-view-object} and the bulk equivalent —
 * so that one move means one thing everywhere. Pure and static: the walk owns no state, and both
 * of its "who is my parent" fallbacks arrive as arguments, which is what lets a caller preparing
 * many mutations before any executes hand in the containment its own not-yet-executed commands
 * imply.</p>
 *
 * <p>Extracted from the accessor facade so that the caller-supplied fallbacks are visible in the
 * signature rather than read from caller state. The pending-parent vocabulary it consults belongs
 * to {@link AnchorResolver}, which also bundles the resulting per-group resize commands into the
 * caller's undo unit.</p>
 */
final class ParentFitCascade {

    private static final Logger logger = LoggerFactory.getLogger(ParentFitCascade.class);

    private ParentFitCascade() {
    }

    /**
     * Resizes {@code parentGroup} if {@code child}'s new rectangle exceeds it, then walks up the
     * ancestor chain doing the same for each enclosing group.
     *
     * <p>Each hop finds the next ancestor from live EMF containment. A group an in-flight batch
     * created is still <em>detached</em> at prepare time — its add command has not executed — so
     * {@code eContainer()} is null exactly when the fit is being computed, and the walk would stop
     * there with every ancestor above it left unmeasured. Two caller-supplied maps close that gap:
     * the bulk pass's own record of the parents it has resolved so far, and the containment an open
     * batch's queued commands imply. They are consulted in that order and both may be null, which is
     * the ordinary single-tool case and leaves the walk driven purely by live containment.</p>
     *
     * @param parentGroup         the group to fit around {@code child}
     * @param child               the moved or resized child (used only for its id in logging)
     * @param childNewX           child's prospective relative-to-parent X
     * @param childNewY           child's prospective relative-to-parent Y
     * @param childW              child width
     * @param childH              child height
     * @param padding             padding allowance between child edge and parent edge
     * @param virtualGroupBounds  accumulated group bounds [x, y, w, h] keyed by group ID;
     *                            checked before EMF bounds, updated on resize
     * @param groupResizeCommands consolidated resize commands keyed by group ID; one per group
     * @param bulkPendingParents  the in-flight bulk pass's {@code id → destined parent} map, or null
     * @param queuedParents       the open batch's {@code id → destined parent} map, or null
     */
    static void resize(IDiagramModelGroup parentGroup,
            IDiagramModelObject child, int childNewX, int childNewY,
            int childW, int childH, int padding,
            Map<String, int[]> virtualGroupBounds,
            Map<String, Command> groupResizeCommands,
            Map<String, IDiagramModelContainer> bulkPendingParents,
            Map<String, IDiagramModelContainer> queuedParents) {
        String groupId = parentGroup.getId();

        // Use accumulated virtual bounds if this group was already resized in this iteration,
        // otherwise fall back to stale EMF bounds
        int parentX, parentY, parentW, parentH;
        int[] vBounds = virtualGroupBounds.get(groupId);
        if (vBounds != null) {
            parentX = vBounds[0]; parentY = vBounds[1];
            parentW = vBounds[2]; parentH = vBounds[3];
        } else {
            IBounds parentBounds = parentGroup.getBounds();
            parentX = parentBounds.getX(); parentY = parentBounds.getY();
            parentW = parentBounds.getWidth(); parentH = parentBounds.getHeight();
        }

        // Check if child's new position exceeds parent's current dimensions.
        // Handles both right/bottom overflow and left/top overflow. The predicate is a separate
        // static method so the post-routing overflow pass and its JUnit pin share one definition
        // of overflow — single source of truth per shared-helper guidance.
        boolean needsResize = childExceedsParentBounds(
                childNewX, childNewY, childW, childH, parentW, parentH, padding);

        if (needsResize) {
            // Formula mirrors childExceedsParentBounds — must stay in sync if padding logic changes.
            int requiredWidth = childNewX + childW + padding;
            int requiredHeight = childNewY + childH + padding;
            int newWidth = Math.max(parentW, requiredWidth);
            int newHeight = Math.max(parentH, requiredHeight);
            int newX = parentX;
            int newY = parentY;

            // Expand left/top if child has negative position within parent
            if (childNewX < 0) {
                newX += childNewX - padding;
                newWidth += -(childNewX - padding);
            }
            if (childNewY < 0) {
                newY += childNewY - padding;
                newHeight += -(childNewY - padding);
            }

            // Store accumulated bounds and consolidated command (one per group)
            virtualGroupBounds.put(groupId, new int[]{newX, newY, newWidth, newHeight});
            groupResizeCommands.put(groupId, new UpdateViewObjectCommand(parentGroup,
                    newX, newY, newWidth, newHeight));
            logger.debug("Auto-nudge: resized parent group {} to ({},{}) {}x{}",
                    groupId, newX, newY, newWidth, newHeight);

            // Recursively resize ancestor groups. Inside a batch or a bulk pass this group may
            // itself still be DETACHED (an earlier op created it; its add command executes later),
            // so fall back to the containment those pending commands imply — otherwise the walk
            // stops at the first such hop and every ancestor above it goes unmeasured.
            EObject grandparent = parentGroup.eContainer();
            if (grandparent == null) {
                grandparent = AnchorResolver.pendingParent(bulkPendingParents, queuedParents, groupId);
            }
            if (grandparent instanceof IDiagramModelGroup grandparentGroup) {
                resize(grandparentGroup, parentGroup,
                        newX, newY,
                        newWidth, newHeight, padding,
                        virtualGroupBounds, groupResizeCommands,
                        bulkPendingParents, queuedParents);
            }
        }
    }

    /**
     * Fits the group around {@code child}'s new rectangle, and then around every rectangle the same
     * call displaced.
     *
     * <p>Those are one question, and asking only the first is how a group ends up sized to the
     * object the caller named while an object that same call moved hangs outside it — a canvas the
     * agent cannot see, described to it accurately, and still wrong. Resizing an object repositions
     * everything anchored to it, so the displaced rectangles are an outcome of the very call whose
     * fit is being computed, not a pre-existing condition some later pass will notice.</p>
     *
     * <p>The caller resolves the container, because only the caller knows whether to read live
     * containment or the containment its own not-yet-executed commands imply. Anything that is not
     * a group is a no-op, which is also exactly the case in which {@code displaced} needs no fit:
     * an anchored child is only repositioned when it shares the target's container, so if the
     * target is not in a group then neither is anything this call moved.</p>
     *
     * @param container           the target's container; anything that is not a group is a no-op
     * @param displaced           bounds commands for the objects this call moved, keyed by object
     *                            id; may be null or empty
     * @return {@code virtualGroupBounds} when a fit ran, or null when the container is not a group
     */
    static Map<String, int[]> fitAround(EObject container, IDiagramModelObject child,
            int childNewX, int childNewY, int childW, int childH, int padding,
            Map<String, int[]> virtualGroupBounds,
            Map<String, Command> groupResizeCommands,
            Map<String, Command> displaced,
            Map<String, IDiagramModelContainer> bulkPendingParents,
            Map<String, IDiagramModelContainer> queuedParents) {
        if (!(container instanceof IDiagramModelGroup parentGroup)) {
            return null;
        }
        resize(parentGroup, child, childNewX, childNewY, childW, childH, padding,
                virtualGroupBounds, groupResizeCommands, bulkPendingParents, queuedParents);
        fitDisplaced(displaced, padding, virtualGroupBounds, groupResizeCommands,
                bulkPendingParents, queuedParents);
        return virtualGroupBounds;
    }

    /**
     * Drives {@link #resize} once per object in {@code displaced}, around the rectangle that
     * object's own pending command puts it at.
     *
     * <p>Where {@link #fitAll} measures a whole view from live bounds, this measures the rectangles
     * a set of not-yet-executed commands imply — which is the only way to see them at all, since
     * those commands are still being assembled at the moment the fit has to be decided. Reading
     * live bounds here would measure where each object currently is rather than where this call is
     * about to put it, and so would find no overflow at precisely the moment one is being created.
     * </p>
     *
     * <p>A caller that bundles its moves and its fits into one undo unit must run this <em>before</em>
     * that unit is closed. Running it afterwards updates the bounds map and the report while the
     * compound still carries the earlier, smaller resize, which turns a wrong canvas into a wrong
     * canvas that is also misdescribed.</p>
     *
     * <p>Each object's own parent is consulted rather than the target's: the two are the same for an
     * anchored child by construction, but a pass that displaces objects in several containers gets
     * each of them fitted rather than all of them measured against one.</p>
     *
     * @param displaced bounds commands keyed by object id; null or empty is a no-op
     */
    static void fitDisplaced(Map<String, Command> displaced, int padding,
            Map<String, int[]> virtualGroupBounds,
            Map<String, Command> groupResizeCommands,
            Map<String, IDiagramModelContainer> bulkPendingParents,
            Map<String, IDiagramModelContainer> queuedParents) {
        if (displaced == null) {
            return;
        }
        for (Command command : displaced.values()) {
            if (!(command instanceof UpdateViewObjectCommand move)) {
                continue;
            }
            IDiagramModelObject moved = move.getDiagramObject();
            EObject parent = moved.eContainer();
            if (parent == null) {
                parent = AnchorResolver.pendingParent(bulkPendingParents, queuedParents,
                        moved.getId());
            }
            if (parent instanceof IDiagramModelGroup parentGroup) {
                resize(parentGroup, moved, move.getNewX(), move.getNewY(),
                        move.getNewWidth(), move.getNewHeight(), padding,
                        virtualGroupBounds, groupResizeCommands, bulkPendingParents, queuedParents);
            }
        }
    }

    /**
     * Drives {@link #resize} once for every object in {@code objects} that sits directly inside a
     * group, so a whole-view pass can ask "does anything still stick out of its container?" in one
     * call.
     *
     * <p>Two passes end with that question — the one that runs after routing has nudged elements
     * about, and the one that runs after the spacing tool has re-laid a view out. Both walked the
     * same objects, filtered on the same containment test and called {@link #resize} with the same
     * eleven arguments; the only thing that differed was whether a displacement had to be added to
     * each rectangle first. Keeping one copy is what stops them drifting apart on what they measure
     * against — which is exactly how two of them ended up seeding their fit maps differently.</p>
     *
     * <p>Rectangles are read from live EMF and are relative-to-parent, matching Archi's nested
     * storage convention. {@code virtualGroupBounds} is consulted before those live bounds by
     * {@link #resize} itself, so a caller that seeds it measures against what its own
     * not-yet-executed commands have already established.</p>
     *
     * @param objects             the objects to test, keyed by id; iteration order is preserved
     * @param deltas              per-object {@code [dx, dy]} displacement to add before measuring,
     *                            keyed by the same ids; null when the pass moved nothing
     * @param padding             padding allowance between child edge and parent edge
     * @param virtualGroupBounds  accumulated group bounds, seeded or empty; updated on resize
     * @param groupResizeCommands consolidated resize commands keyed by group ID; one per group
     * @param bulkPendingParents  the in-flight bulk pass's {@code id → destined parent} map, or null
     * @param queuedParents       the open batch's {@code id → destined parent} map, or null
     */
    static void fitAll(Map<String, IDiagramModelObject> objects, Map<String, int[]> deltas,
            int padding,
            Map<String, int[]> virtualGroupBounds,
            Map<String, Command> groupResizeCommands,
            Map<String, IDiagramModelContainer> bulkPendingParents,
            Map<String, IDiagramModelContainer> queuedParents) {
        for (Map.Entry<String, IDiagramModelObject> entry : objects.entrySet()) {
            IDiagramModelObject dmo = entry.getValue();
            if (!(dmo.eContainer() instanceof IDiagramModelGroup parentGroup)) {
                continue;
            }
            IBounds bounds = dmo.getBounds();
            int[] delta = (deltas != null) ? deltas.getOrDefault(entry.getKey(), NO_DELTA) : NO_DELTA;
            resize(parentGroup, dmo,
                    bounds.getX() + delta[0], bounds.getY() + delta[1],
                    bounds.getWidth(), bounds.getHeight(), padding,
                    virtualGroupBounds, groupResizeCommands, bulkPendingParents, queuedParents);
        }
    }

    /** Read-only zero displacement, shared by every unmoved object {@link #fitAll} measures. */
    private static final int[] NO_DELTA = {0, 0};

    /**
     * Returns true iff the given child rectangle exceeds the parent group's dimensions in ANY of
     * the four overflow directions (right / bottom / left / top) after the padding allowance.
     *
     * <p>Shared by the cascade above and by the post-routing overflow-detection pass, so both agree
     * on what "overflow" means.</p>
     *
     * <p>Coordinate convention: {@code childNewX} / {@code childNewY} are relative-to-parent,
     * matching Archi's nested-object storage convention.</p>
     *
     * @param childNewX child's prospective relative-to-parent X (post-nudge or post-resize)
     * @param childNewY child's prospective relative-to-parent Y
     * @param childW    child width (unchanged across moves)
     * @param childH    child height
     * @param parentW   parent group's current width
     * @param parentH   parent group's current height
     * @param padding   padding allowance
     * @return true if any of the four overflow conditions hold
     */
    static boolean childExceedsParentBounds(
            int childNewX, int childNewY, int childW, int childH,
            int parentW, int parentH, int padding) {
        int requiredWidth = childNewX + childW + padding;
        int requiredHeight = childNewY + childH + padding;
        return requiredWidth > parentW
                || requiredHeight > parentH
                || childNewX < 0 || childNewY < 0;
    }

    /**
     * Projects the accumulated fit-bounds map into the response shape, so a caller can tell its
     * client which groups this cascade grew and what each grew to.
     *
     * <p>Every pass that drives {@link #resize} ends up holding the same thing: a group-id-keyed
     * map of post-fit bounds that only the pass itself can see. Turning that into a report is the
     * one step between "the group silently moved" and "the agent knows where its group is", and it
     * was previously written out at a single call site while the other passes simply dropped the
     * map. Sharing it here means a pass gains the report by asking for it rather than by
     * reimplementing the same loop, and no pass can drift from another on what it reports.</p>
     *
     * <p>Names are resolved from live containment rather than taken from the caller, because the
     * caller's own view of names is exactly the prepare-time projection this report exists to
     * replace. A group present in the map but absent from the view falls back to its id, which is
     * still an actionable handle — never a placeholder that would read as a real name.</p>
     *
     * <p>The null-name fallback is deliberately <em>only</em> null-checked, not blank-checked, so
     * this is behaviour-identical to the single call site it was lifted from. An empty group name
     * arguably deserves the same id fallback, but changing that here would alter an existing tool's
     * response bytes under cover of a refactor.</p>
     *
     * @param fitBounds accumulated group bounds [x, y, w, h] keyed by group ID; may be null or empty
     * @param root      the view (or container) to resolve group names from
     * @return one entry per grown group, in the order the cascade recorded them; never null
     */
    static List<ResizedGroupDto> project(Map<String, int[]> fitBounds, IDiagramModelContainer root) {
        return project(fitBounds, null, root);
    }

    /**
     * As {@link #project(Map, IDiagramModelContainer)}, excluding every group whose entry is
     * unchanged from {@code seed}.
     *
     * <p>A pass that runs inside a batch seeds its fit map with the geometry the batch has already
     * queued, so the walk measures against what earlier operations established rather than the
     * pre-batch size. That seed makes the map answer two different questions at once: which groups
     * this pass <em>grew</em>, and which ones it merely <em>knew about</em>. Only the first is an
     * outcome of this call, and reporting the second would name a group as resized by a pass that
     * did not touch it — a false statement about the model dressed as a measurement.</p>
     *
     * <p>Safe as a shallow snapshot: the walk replaces a group's entry with a new array rather than
     * mutating the one it found, so a copy taken at seed time still holds the seeded values.</p>
     *
     * @param fitBounds accumulated group bounds after the walk
     * @param seed      the map's contents before the walk, or null when it started empty
     * @param root      the view (or container) to resolve group names from
     */
    static List<ResizedGroupDto> project(Map<String, int[]> fitBounds, Map<String, int[]> seed,
            IDiagramModelContainer root) {
        if (fitBounds == null || fitBounds.isEmpty()) {
            return List.of();
        }
        Map<String, IDiagramModelObject> byId = new LinkedHashMap<>();
        collectInto(root, byId);

        List<ResizedGroupDto> projected = new ArrayList<>(fitBounds.size());
        for (Map.Entry<String, int[]> entry : fitBounds.entrySet()) {
            String groupId = entry.getKey();
            int[] bounds = entry.getValue();
            if (seed != null && java.util.Arrays.equals(seed.get(groupId), bounds)) {
                continue; // seeded, not grown by this pass
            }
            IDiagramModelObject group = byId.get(groupId);
            String groupName = (group != null && group.getName() != null)
                    ? group.getName() : groupId;
            projected.add(new ResizedGroupDto(groupId, groupName,
                    bounds[0], bounds[1], bounds[2], bounds[3]));
        }
        return projected;
    }

    /** Depth-first index of every view object under {@code container}, keyed by id. */
    private static void collectInto(IDiagramModelContainer container,
            Map<String, IDiagramModelObject> into) {
        for (Object child : container.getChildren()) {
            if (child instanceof IDiagramModelObject obj) {
                into.put(obj.getId(), obj);
                if (obj instanceof IDiagramModelContainer nested) {
                    collectInto(nested, into);
                }
            }
        }
    }
}
