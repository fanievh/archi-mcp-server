package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;

/**
 * Stateless resolver for view-anchored positioning.
 *
 * <p>A view object may record that its position is <em>relative to a target
 * container</em> rather than a frozen absolute snapshot: the anchor
 * {@code (target, edge, dx, dy)} is persisted on the child as {@link
 * com.archimatetool.model.IFeatures} entries. This class owns (a) the pure
 * geometry that turns an anchor into a concrete position and (b) the
 * commit-time cascade that repositions every anchored child when its target's
 * bounds change — bundled into a single undo unit.</p>
 *
 * <p><strong>Edge semantics</strong> (result is the child's top-left):
 * <ul>
 *   <li>{@code below}: {@code (target.x + dx, target.y + target.height + dy)} — tracks the growing bottom.</li>
 *   <li>{@code above}: {@code (target.x + dx, target.y - childHeight - dy)}.</li>
 *   <li>{@code right}: {@code (target.x + target.width + dx, target.y + dy)}.</li>
 *   <li>{@code left}:  {@code (target.x - childWidth - dx,  target.y + dy)}.</li>
 * </ul>
 * {@code dy}/{@code dx} are the gap along/against the edge. Unknown/empty edge falls back to {@code below}.</p>
 *
 * <p><strong>Coordinate space</strong>: the target and the anchored child must
 * share a coordinate space (both top-level, or both children of the same
 * parent). The resolver uses each object's stored bounds verbatim; cross-parent
 * offset accumulation is intentionally out of scope.</p>
 *
 * <p>This collaborator lives OUTSIDE {@code ArchiModelAccessorImpl} so the facade's
 * size ratchet ({@code tools/size-ratchet.sh}) is unaffected — the facade delegates
 * to these static methods.</p>
 */
public final class AnchorResolver {

    /** Feature-list keys persisted on the anchored (child) diagram object. */
    static final String ANCHOR_TARGET_FEATURE = "anchorTarget";
    static final String ANCHOR_EDGE_FEATURE = "anchorEdge";
    static final String ANCHOR_DX_FEATURE = "anchorDx";
    static final String ANCHOR_DY_FEATURE = "anchorDy";

    static final String EDGE_BELOW = "below";
    static final String EDGE_ABOVE = "above";
    static final String EDGE_RIGHT = "right";
    static final String EDGE_LEFT = "left";
    static final String DEFAULT_EDGE = EDGE_BELOW;

    private AnchorResolver() {
    }

    private static String normalizeEdge(String edge) {
        return (edge == null || edge.isEmpty()) ? DEFAULT_EDGE : edge;
    }

    /** True if {@code edge} is one of the four supported values (or null/empty, which defaults to below). */
    public static boolean isValidEdge(String edge) {
        if (edge == null || edge.isEmpty()) {
            return true;
        }
        return EDGE_BELOW.equals(edge) || EDGE_ABOVE.equals(edge)
                || EDGE_RIGHT.equals(edge) || EDGE_LEFT.equals(edge);
    }

    /**
     * Pure-geometry primitive: the child's top-left when placed against a target
     * edge with offset {@code (dx, dy)}. Integer form (view-object bounds are ints).
     */
    static int[] resolveByEdge(String edge, int targetX, int targetY, int targetW, int targetH,
                               int childW, int childH, int dx, int dy) {
        switch (normalizeEdge(edge)) {
            case EDGE_ABOVE:
                return new int[] { targetX + dx, targetY - childH - dy };
            case EDGE_RIGHT:
                return new int[] { targetX + targetW + dx, targetY + dy };
            case EDGE_LEFT:
                return new int[] { targetX - childW - dx, targetY + dy };
            case EDGE_BELOW:
            default:
                return new int[] { targetX + dx, targetY + targetH + dy };
        }
    }

    /**
     * Double-precision overload used by the {@code add-note-to-view} content-relative
     * placement path so its rounding stays byte-identical to the pre-existing
     * {@code (int) Math.round(sum)} arithmetic (round the full sum once, not intermediates).
     */
    static int[] resolveByEdge(String edge, double targetX, double targetY, double targetW, double targetH,
                               double childW, double childH, double dx, double dy) {
        switch (normalizeEdge(edge)) {
            case EDGE_ABOVE:
                return new int[] { (int) Math.round(targetX + dx), (int) Math.round(targetY - childH - dy) };
            case EDGE_RIGHT:
                return new int[] { (int) Math.round(targetX + targetW + dx), (int) Math.round(targetY + dy) };
            case EDGE_LEFT:
                return new int[] { (int) Math.round(targetX - childW - dx), (int) Math.round(targetY + dy) };
            case EDGE_BELOW:
            default:
                return new int[] { (int) Math.round(targetX + dx), (int) Math.round(targetY + targetH + dy) };
        }
    }

    /**
     * Merges the requested x/y/width/height with the object's current bounds; when an anchor
     * is being set ({@code anchorTarget} non-null and non-empty) and the target resolves in the
     * same diagram, overrides x/y with the edge-resolved position from the target's current bounds.
     * Returns {@code {x, y, width, height}}.
     */
    static int[] mergeBounds(IDiagramModelObject obj, Integer x, Integer y, Integer width, Integer height,
                             String anchorTarget, String anchorEdge, Integer anchorDx, Integer anchorDy) {
        return mergeBounds(obj, x, y, width, height, anchorTarget, anchorEdge, anchorDx, anchorDy, null);
    }

    /**
     * Overload that supplies the object's effective current bounds ({@code {x, y, width, height}})
     * as the merge base for omitted dimensions, instead of reading {@code obj.getBounds()}.
     *
     * <p>Used by the bulk-mutate prepare path: when an earlier op in the same batch already
     * re-sized this object, that op's command has not executed yet (bulk Phase 1 prepares every
     * op against the pre-batch model), so {@code obj.getBounds()} is stale. Passing the batch's
     * pending bounds makes a later partial re-edit inherit the earlier value rather than silently
     * reverting it. A {@code null} override reads {@code obj.getBounds()} — the non-bulk path,
     * byte-identical to the pre-existing behaviour.</p>
     */
    static int[] mergeBounds(IDiagramModelObject obj, Integer x, Integer y, Integer width, Integer height,
                             String anchorTarget, String anchorEdge, Integer anchorDx, Integer anchorDy,
                             int[] currentBoundsOverride) {
        return mergeBounds(obj, x, y, width, height, anchorTarget, anchorEdge, anchorDx, anchorDy,
                currentBoundsOverride, null);
    }

    /**
     * Overload that also supplies the same-unit-of-work geometry map, so the <em>anchor target</em>
     * is measured against what this batch has already queued for it rather than its pre-batch
     * {@code getBounds()}.
     *
     * <p>Resolving a target that an earlier queued command re-sizes is not the same problem as
     * merging the anchored object's own omitted dimensions. The prepare computes a position from
     * the target's bounds; if a queued command grows the target, that prepare-time position is
     * wrong the moment it is computed, and everything derived from it — the response, the parent-fit
     * cascade, and the reposition commands built for the target's own dependents — inherits the
     * error. The anchored object's command re-resolves its own position at execute and so recovers;
     * nothing built from the projection does.</p>
     *
     * <p>A {@code null} or empty map is the ordinary single-tool call and reads {@code getBounds()},
     * byte-identical to the pre-existing behaviour.</p>
     */
    static int[] mergeBounds(IDiagramModelObject obj, Integer x, Integer y, Integer width, Integer height,
                             String anchorTarget, String anchorEdge, Integer anchorDx, Integer anchorDy,
                             int[] currentBoundsOverride, Map<String, int[]> sameBatchBounds) {
        return mergeBounds(obj, x, y, width, height, anchorTarget, anchorEdge, anchorDx, anchorDy,
                currentBoundsOverride, sameBatchBounds, null, null, null, null);
    }

    /**
     * Overload that can also resolve an anchor target the same batch has queued but not yet
     * attached, and validate the coordinate space through the containers those queued adds declare.
     *
     * <p>{@link #findInSameDiagram} walks committed containment from the anchored object's diagram,
     * so it answers null for a target whose add command has not run — and for an anchored object
     * whose own add has not run, it cannot even start. Both are bridged here, but for different
     * amounts of work, and the difference is the contract.</p>
     *
     * <p><strong>A queued TARGET is bridged for position as well as validation.</strong> The batch
     * built it, so it knows the bounds it built it with and the container it is destined for; the
     * anchored object is attached and entitled to a position now.</p>
     *
     * <p><strong>A queued ANCHORED OBJECT is bridged for validation only.</strong> Its own add has
     * not run, so the lookup cannot start from it and the same-space guard below never saw the
     * pairing at all — a cross-space anchor inside a batch committed unmoved and reported success,
     * while the identical request outside a batch was rejected up front. Reaching the target
     * through the diagram the queued add is destined for closes that asymmetry. The position is
     * deliberately <em>not</em> resolved: an object still detached at prepare has its position
     * resolved by its own command at {@code execute()}, where containment is real, and computing it
     * here would make the prepare's response claim a position it is not entitled to know.</p>
     *
     * <p>Both records null is every non-batch call, where {@code containerOf} degrades to
     * {@code eContainer()} and the same-space test is the one that was here before.</p>
     */
    static int[] mergeBounds(IDiagramModelObject obj, Integer x, Integer y, Integer width, Integer height,
                             String anchorTarget, String anchorEdge, Integer anchorDx, Integer anchorDy,
                             int[] currentBoundsOverride, Map<String, int[]> sameBatchBounds,
                             QueuedViewObject batchObject, QueuedViewObject batchAnchorTarget,
                             Map<String, IDiagramModelContainer> queuedParents,
                             Map<String, IDiagramModelContainer> bulkPendingParents) {
        int baseX, baseY, baseW, baseH;
        if (currentBoundsOverride != null) {
            baseX = currentBoundsOverride[0];
            baseY = currentBoundsOverride[1];
            baseW = currentBoundsOverride[2];
            baseH = currentBoundsOverride[3];
        } else {
            IBounds b = obj.getBounds();
            baseX = b.getX();
            baseY = b.getY();
            baseW = b.getWidth();
            baseH = b.getHeight();
        }
        int mergedX = (x != null) ? x : baseX;
        int mergedY = (y != null) ? y : baseY;
        int mergedWidth = (width != null) ? width : baseW;
        int mergedHeight = (height != null) ? height : baseH;
        if (anchorTarget != null && !anchorTarget.isEmpty()) {
            IDiagramModelObject target = findInSameDiagram(obj, anchorTarget);
            // When the ANCHORED object is the detached one, the pairing is reached through the
            // diagram its queued add is destined for — for validation only. Its position is not
            // resolved here: that stays with its own command at execute(), where containment is
            // real, which is the whole reason only the target is bridged above.
            boolean validateOnly = false;
            if (target == null && batchAnchorTarget != null) {
                target = batchAnchorTarget.object();
            } else if (target == null && batchObject != null) {
                target = findInDestinedDiagram(obj, anchorTarget, queuedParents, bulkPendingParents);
                validateOnly = target != null;
            }
            if (target != null) {
                if (target == obj) {
                    throw new ModelAccessException(
                            "Cannot anchor a view object to itself",
                            ErrorCode.INVALID_PARAMETER, null,
                            "anchorTarget must reference a different view object.", null);
                }
                if (QueuedViewObject.containerOf(target, batchAnchorTarget)
                        != QueuedViewObject.containerOf(obj, batchObject)) {
                    // Bounds are stored relative to the immediate parent; resolving across
                    // coordinate spaces would write the wrong numbers. Enforce same-space.
                    throw new ModelAccessException(
                            "anchorTarget must share the same parent container as the anchored object",
                            ErrorCode.INVALID_PARAMETER, null,
                            "Anchor to a sibling object (both top-level, or both inside the same group).",
                            null);
                }
                if (!validateOnly) {
                    int[] tb = pendingBounds(sameBatchBounds, target.getId());
                    if (tb == null) {
                        IBounds live = target.getBounds();
                        tb = new int[] { live.getX(), live.getY(), live.getWidth(), live.getHeight() };
                    }
                    int dx = (anchorDx != null) ? anchorDx : 0;
                    int dy = (anchorDy != null) ? anchorDy : 0;
                    int[] p = resolveByEdge(anchorEdge, tb[0], tb[1], tb[2], tb[3],
                            mergedWidth, mergedHeight, dx, dy);
                    mergedX = p[0];
                    mergedY = p[1];
                }
            }
        }
        return new int[] { mergedX, mergedY, mergedWidth, mergedHeight };
    }

    /**
     * Finds {@code targetId} in the diagram a queued object's add is destined for.
     *
     * <p>Deliberately separate from {@link #findInSameDiagram}, which must keep answering null for
     * a detached object: {@code UpdateViewObjectCommand} calls it to decide whether it holds a
     * prepare-time snapshot of the target's bounds, and a null answer there is what tells its
     * {@code execute()} to resolve the edge outright. Teaching that method about destined
     * containment would populate the snapshot and switch on a has-not-moved fast path for commands
     * that today, correctly, always re-resolve.</p>
     *
     * <p>The diagram is reached through {@link #destinedDiagramOf}, which climbs the whole declared
     * containment chain rather than one hop. One hop is not enough and the shortfall is not
     * cosmetic: an object queued inside a <em>queued</em> group has a destined parent that is
     * itself still detached, so a single step lands on something that answers null for its diagram
     * and the lookup fails — for detachment, which is precisely the reason this bridge exists to
     * stop being confused with a coordinate-space mismatch. The guard would then not run at all and
     * an anchor no validation approved would be written to the object.</p>
     */
    private static IDiagramModelObject findInDestinedDiagram(IDiagramModelObject obj, String targetId,
            Map<String, IDiagramModelContainer> first,
            Map<String, IDiagramModelContainer> second) {
        IDiagramModel diagram = destinedDiagramOf(obj, first, second);
        return (diagram == null || targetId == null) ? null : findChild(diagram, targetId);
    }

    /**
     * Bulk same-batch effective bounds for {@code viewObjectId}, or {@code null} outside a batch
     * or on the object's first touch. Lets a later same-batch op merge omitted dimensions from the
     * value an earlier op established rather than the stale {@code getBounds()}.
     */
    static int[] pendingBounds(Map<String, int[]> pending, String viewObjectId) {
        return (pending != null) ? pending.get(viewObjectId) : null;
    }

    /**
     * As {@link #pendingBounds(Map, String)} over the two same-batch sources, consulted
     * <em>later-writer-first</em>: a bulk pass's ops are prepared after everything already sitting
     * in the batch's queue, so where both know the object the bulk value is the more recent one.
     * Null from both means no pending command has re-sized the object and the caller's own
     * {@code getBounds()} read is correct.
     *
     * <p>The precedence is <strong>reachable, not merely a forward guard</strong>. Several prepares
     * now receive the queued map — the two update prepares, the icon-band reservation and the
     * resize-to-fit pass — and a bulk call issued inside an already-open batch runs those prepares
     * with the bulk map set as well, so both are genuinely populated at once. Where both know the
     * object the bulk value is the later write, which is why it is consulted first; picking the
     * other way round would measure against geometry an earlier operation has already superseded.</p>
     */
    static int[] pendingBounds(Map<String, int[]> queued, Map<String, int[]> bulkPending, String viewObjectId) {
        int[] hit = pendingBounds(bulkPending, viewObjectId);
        return (hit != null) ? hit : pendingBounds(queued, viewObjectId);
    }

    /**
     * The rectangle {@code obj} effectively has for the purposes of a pass running inside an open
     * batch: what an earlier operation of that batch queued for it, or its live bounds when the
     * batch has not touched it.
     *
     * <p>{@code getBounds()} inside a batch is a <em>pre-batch</em> read — none of the batch's own
     * commands have executed yet — so a pass that measures a container that way measures the
     * rectangle the container is about to stop having. Every caller below wants the same thing: the
     * rectangle that will actually be there when its own command runs.</p>
     *
     * <p>Outside a batch, and for an object the batch has not re-sized, {@code pending} yields
     * nothing and this is exactly the live read it replaces.</p>
     */
    static int[] effectiveRect(IDiagramModelObject obj, Map<String, int[]> pending) {
        int[] queued = pendingBounds(pending, obj.getId());
        if (queued != null) return queued;
        IBounds live = obj.getBounds();
        return new int[] { live.getX(), live.getY(), live.getWidth(), live.getHeight() };
    }

    /**
     * The rectangle a container auto-resize should write, given the dimensions its fit computed and
     * the container's {@link #effectiveRect(IDiagramModelObject, Map) effective} rectangle.
     *
     * <p>A layout pass that re-fits a container emits an <em>absolute</em> rectangle. Computed from
     * a pre-batch read and queued after the operation that sized the container, that rectangle
     * silently discards the size the caller asked for — both calls reporting success, on a canvas
     * the caller cannot see. Measuring against the effective rectangle makes a same-batch size a
     * floor the fit may exceed but never shrink below.</p>
     *
     * <p><strong>The two axes are deliberately not symmetric.</strong> Dimensions take {@code max}
     * per axis, independently: the fit exists to guarantee the children are enclosed, so it must
     * still be free to grow past a queued size that is too small, and a rectangle queued wider but
     * shorter than required must win only on the axis where it is larger. The position is taken
     * from the effective rectangle outright — {@code max} is meaningless for a coordinate, and the
     * call site's intent is to leave the container where it is, which inside a batch means where
     * the batch has already put it.</p>
     *
     * <p>The floor is against anything the batch queued, not only against a caller's explicit
     * re-size: the source map is derived from the command queue, so a container re-sized by an
     * earlier pass of the same batch floors a later one just as an explicit request does.</p>
     *
     * <p><strong>The floor exists only where the batch actually queued something.</strong> With no
     * queued entry the computed dimensions are written unchanged — including when they are
     * <em>smaller</em>. Flooring against the live rectangle instead would quietly turn every re-fit
     * into a grow-only one and stop a negative delta from ever tightening a view, which is a change
     * to the single-tool path this is required to leave byte-identical.</p>
     */
    static int[] refitRect(IDiagramModelObject container, int[] computedDims,
            Map<String, int[]> pending) {
        int[] effective = effectiveRect(container, pending);
        int[] queued = pendingBounds(pending, container.getId());
        if (queued == null) {
            return new int[] { effective[0], effective[1], computedDims[0], computedDims[1] };
        }
        return new int[] { effective[0], effective[1],
                Math.max(computedDims[0], queued[2]), Math.max(computedDims[1], queued[3]) };
    }

    /**
     * A child's effective size for a layout that must not shrink what the same unit of work already
     * established: the dimensions this pass's own compound has computed for it if any, else the ones
     * the batch queued, else its live size.
     *
     * <p>{@code compoundDims} wins because it is the later write — a nested group re-fitted earlier
     * in this same pass has superseded whatever the batch queued for it.</p>
     */
    static int[] effectiveDims(IDiagramModelObject child, int[] compoundDims,
            Map<String, int[]> pending) {
        if (compoundDims != null) return compoundDims;
        int[] queued = pendingBounds(pending, child.getId());
        if (queued != null) return new int[] { queued[2], queued[3] };
        IBounds live = child.getBounds();
        return new int[] { live.getWidth(), live.getHeight() };
    }

    /**
     * Records {@code viewObjectId}'s effective bounds into the same-batch map for later ops.
     * No-op when {@code pending} is null (non-bulk path), keeping single-tool behaviour unchanged.
     */
    static void recordPending(Map<String, int[]> pending, String viewObjectId, int x, int y, int w, int h) {
        if (pending != null) pending.put(viewObjectId, new int[] { x, y, w, h });
    }

    /**
     * Bundles {@code base} with the parent-fit cascade's per-group resize commands into a single
     * undo unit, so an update and the group growth it forced land and revert together. Returns
     * {@code base} unchanged when the fit needed no resize.
     */
    static Command wrapWithGroupResizes(Command base, Map<String, Command> groupResizeCommands) {
        return wrapWithGroupResizes(base, groupResizeCommands,
                "Update view object bounds with parent-group resize");
    }

    /**
     * As {@link #wrapWithGroupResizes(Command, Map)}, with the compound's undo label supplied by the
     * caller — the levers that trigger a cascade differ only in how the growth was provoked, so they
     * share this bundling and name the resulting undo entry for their own trigger.
     */
    static Command wrapWithGroupResizes(Command base, Map<String, Command> groupResizeCommands, String label) {
        if (groupResizeCommands.isEmpty()) return base;
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(label);
        compound.add(base);
        for (Command resize : groupResizeCommands.values()) compound.add(resize);
        return compound;
    }

    /**
     * Drops a pass-scoped cascade resize for {@code viewObjectId} once the rectangle the current
     * mutation settles for that same object already satisfies it.
     *
     * <p>Only a caller that owns the fit commands for a whole pass needs this, and only because it
     * commits them <em>after</em> every object has landed. That ordering is deliberate: it is what
     * lets a group's re-fit supersede an entry that named the group and asked for it smaller than
     * its children need, which is a wrong canvas described accurately. It must not also let the
     * re-fit undo an entry that asked for the group <em>bigger</em>. Containment is the whole
     * justification for going last, and a rectangle that already contains what the cascade demanded
     * discharges it — so the cascade has nothing left to say and its command is retired rather than
     * executed over the top of a larger one the caller explicitly asked for.</p>
     *
     * <p>Retiring rather than overwriting matters for what comes next: a LATER object in the same
     * pass may push this same container again, and that fit re-enters the map on its own terms,
     * measured against the rectangle this mutation just established.</p>
     */
    static void retireCoveredResize(Map<String, Command> passFitCommands, String viewObjectId,
            int x, int y, int width, int height) {
        if (!(passFitCommands.get(viewObjectId) instanceof UpdateViewObjectCommand fit)) {
            return;
        }
        // Both rectangles describe the same object in the same parent frame, so they compare directly.
        if (x <= fit.getNewX() && y <= fit.getNewY()
                && x + width >= fit.getNewX() + fit.getNewWidth()
                && y + height >= fit.getNewY() + fit.getNewHeight()) {
            passFitCommands.remove(viewObjectId);
        }
    }

    /**
     * Bundles an add command with the icon-band reservation its arrival provoked on the parent, into
     * a single undo unit. The resize is added FIRST so the corner is reserved before the child lands.
     * Returns {@code base} unchanged when the parent needed no reservation ({@code parentResize} null).
     */
    static Command wrapWithIconBandResize(Command base, Command parentResize, String label) {
        if (parentResize == null) return base;
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(label);
        compound.add(parentResize);
        compound.add(base);
        return compound;
    }

    /**
     * A fresh working map for a parent-fit cascade, seeded with the same-batch bounds in
     * {@code pending} so the fit measures against what earlier ops of this batch established
     * rather than the stale {@code getBounds()} — an explicit same-batch group size becomes a
     * floor the grow-only fit may exceed but never shrink below. Empty when {@code pending} is
     * null (non-bulk), which is exactly the unseeded behaviour of a single-tool call.
     */
    static Map<String, int[]> seedPending(Map<String, int[]> pending) {
        Map<String, int[]> seeded = new LinkedHashMap<>();
        if (pending != null) seeded.putAll(pending);
        return seeded;
    }

    /**
     * As {@link #seedPending(Map)} over both same-unit-of-work sources, applied oldest-first so the
     * later writer wins on any object both know: an open batch's queued geometry underneath, the
     * in-flight bulk pass's own pending bounds on top. As with
     * {@link #pendingBounds(Map, Map, String)}, that ordering is a forward guard — no current path
     * supplies both at once.
     *
     * <p>Seeding from the batch is what stops two prepares of one batch each measuring a shared
     * container against its pre-batch size and emitting competing resizes for it. With the earlier
     * prepare's result in the map, the later one's overflow test sees the grown container, finds no
     * overflow, and emits nothing — one command per container per batch rather than one per prepare.
     * Both maps null (the ordinary single-tool call) yields the same empty map as before.</p>
     */
    static Map<String, int[]> seedPending(Map<String, int[]> queued, Map<String, int[]> bulkPending) {
        Map<String, int[]> seeded = seedPending(queued);
        if (bulkPending != null) seeded.putAll(bulkPending);
        return seeded;
    }

    /**
     * Folds a cascade's resulting group bounds back into the same-batch map so a LATER op that
     * touches one of those groups merges its untouched dimensions from the grown value instead of
     * reverting it. No-op when either map is null (non-bulk, or no cascade ran).
     */
    static void foldPending(Map<String, int[]> pending, Map<String, int[]> cascadeBounds) {
        if (pending != null && cascadeBounds != null) pending.putAll(cascadeBounds);
    }

    /**
     * The whole same-batch write-back an update prepare owes once it has finished: every group its
     * parent-fit cascade grew, plus the object's own effective geometry when the call actually moved
     * or re-sized it. Both update prepares end this way, and their tails were byte-near-identical —
     * a shape the two paths must not be able to drift apart on, since the two disagreeing about what
     * an earlier op established is exactly how a shared container ends up measured twice.
     *
     * <p>Called at the prepare's end, on success only, so an operation that threw or was dropped
     * leaves no floor behind. No-op when {@code pending} is null (the non-bulk path).</p>
     */
    static void recordEffective(Map<String, int[]> pending, Map<String, int[]> cascadeBounds,
            String viewObjectId, boolean boundsModified, int x, int y, int w, int h) {
        foldPending(pending, cascadeBounds);
        if (boundsModified) recordPending(pending, viewObjectId, x, y, w, h);
    }

    /**
     * The container an earlier op of this batch intends for {@code viewObjectId}, or {@code null}
     * outside a batch / for an object this batch did not add. An add-to-view builds a DETACHED
     * view object — EMF containment is set when its command executes, after every op is prepared —
     * so a same-batch update addressed by back-reference has no {@code eContainer()} to fit
     * against and must consult this instead.
     */
    static IDiagramModelContainer pendingParent(Map<String, IDiagramModelContainer> parents, String viewObjectId) {
        return (parents != null) ? parents.get(viewObjectId) : null;
    }

    /**
     * As {@link #pendingParent(Map, String)} over two sources, consulted in order. A prepare can be
     * running inside a bulk pass, inside an open batch, or inside a bulk pass nested in a batch, and
     * the ancestor walk must not have to know which: whichever source knows the destined parent
     * answers, and null means no pending command claims that object.
     */
    static IDiagramModelContainer pendingParent(Map<String, IDiagramModelContainer> first,
            Map<String, IDiagramModelContainer> second, String viewObjectId) {
        IDiagramModelContainer hit = pendingParent(first, viewObjectId);
        return (hit != null) ? hit : pendingParent(second, viewObjectId);
    }

    /**
     * The diagram {@code object} will belong to once the pending commands have run, or {@code null}
     * when neither live containment nor the pending records can say.
     *
     * <p>Climbs the destined containment {@link #pendingParent(Map, Map, String) the two records
     * declare}, stopping at the first ancestor that is already attached — its live diagram is the
     * answer for everything below it. An object still detached at prepare answers {@code null} to
     * {@code getDiagramModel()}, which is exactly why the question cannot be asked of the object
     * itself: a same-request parent has no view yet, and treating that as "belongs to no view"
     * would reject every legitimate nested add.</p>
     *
     * <p>Answering {@code null} means <em>unknown</em>, never "no diagram". Callers validating
     * against it must abstain rather than reject, so an object neither record knows about keeps
     * whatever behaviour it had.</p>
     *
     * @param object the object whose destined diagram is wanted
     * @param first  the first pending-parent record to consult, may be null
     * @param second the second, may be null
     */
    static IDiagramModel destinedDiagramOf(IDiagramModelObject object,
            Map<String, IDiagramModelContainer> first,
            Map<String, IDiagramModelContainer> second) {
        // One id is created once, so the declared containment cannot loop; the bound is a
        // backstop against a malformed queue rather than an expected depth.
        int bound = (first == null ? 0 : first.size()) + (second == null ? 0 : second.size()) + 1;
        IDiagramModelObject current = object;
        for (int hop = 0; current != null && hop <= bound; hop++) {
            IDiagramModel live = current.getDiagramModel();
            if (live != null) {
                return live;
            }
            IDiagramModelContainer destined = pendingParent(first, second, current.getId());
            if (destined instanceof IDiagramModel diagram) {
                return diagram;
            }
            current = (destined instanceof IDiagramModelObject parent) ? parent : null;
        }
        return null;
    }

    /**
     * Records the container an add op resolved for {@code viewObjectId}. Called only once the
     * prepare has succeeded, so an operation dropped under {@code continueOnError} leaves no
     * parent behind. No-op when {@code parents} is null (non-bulk path).
     */
    static void recordPendingParent(Map<String, IDiagramModelContainer> parents, String viewObjectId,
            IDiagramModelContainer parent) {
        if (parents != null && parent != null) parents.put(viewObjectId, parent);
    }

    /**
     * When {@code boundsModified} is true and one or more objects in the same diagram are anchored
     * to {@code target}, returns a compound command bundling {@code base} with a reposition command
     * per anchored child (a single undo unit). Otherwise returns {@code base} unchanged.
     *
     * @param base       the target's own already-built command
     * @param target     the object whose bounds are changing
     * @param targetX/Y/W/H the target's new (merged) bounds
     */
    static Command wrapAnchoredChildren(Command base, IDiagramModelObject target,
            int targetX, int targetY, int targetW, int targetH, boolean boundsModified) {
        return wrapAnchoredChildren(base, target, targetX, targetY, targetW, targetH, boundsModified,
                null, null, null);
    }

    /**
     * As {@link #wrapAnchoredChildren(Command, IDiagramModelObject, int, int, int, int, boolean)},
     * reading the same-unit-of-work declarations an open batch has queued but not yet executed.
     *
     * <p>Two of them, for two distinct blind spots the walk has inside a batch. {@code queuedAnchors}
     * supplies anchors declared by commands that have not run, which the feature entries this walk
     * otherwise reads cannot show — without it, anchoring an object and then re-sizing its target in
     * the same batch moves nothing, because the anchor is written by the first command and read by
     * the second's prepare. {@code sameBatchBounds} supplies each candidate's own queued rectangle,
     * so a move emitted for it carries the size the batch gave it rather than reinstating the
     * pre-batch one, and the did-it-actually-move test compares against the queued position.</p>
     *
     * <p>When {@code passMoves} is non-null the moves are recorded there, keyed by anchored-object
     * id, and the CALLER commits them — one command per anchored object for the whole pass, however
     * many times the pass re-sizes the target. A pass that emits several mutations for one target
     * would otherwise wrap each of them and leave a stack of competing absolute repositions for the
     * same object in a single compound, where only the last to execute survives. Null keeps the
     * single-tool behaviour: the moves ride inside the returned compound.</p>
     */
    static Command wrapAnchoredChildren(Command base, IDiagramModelObject target,
            int targetX, int targetY, int targetW, int targetH, boolean boundsModified,
            Map<String, String[]> queuedAnchors, Map<String, int[]> sameBatchBounds,
            Map<String, Command> passMoves) {
        return wrapAnchoredChildren(base, target, targetX, targetY, targetW, targetH,
                boundsModified, queuedAnchors, sameBatchBounds, passMoves, null);
    }

    /**
     * As the ten-argument form, additionally copying the moves it collected into
     * {@code reportMoves} so the caller can name the objects it displaced.
     *
     * <p>{@code reportMoves} is <strong>not</strong> {@code passMoves} by another name and the
     * distinction is load-bearing. {@code passMoves} changes what this method <em>does</em>: with it
     * supplied, the moves are handed to the caller to commit and no longer ride inside the returned
     * compound, so a caller that has no later compound to add them to would silently stop moving
     * anchored children while reporting where they "landed" — a worse defect than the silence it set
     * out to fix. {@code reportMoves} changes nothing: the wrapping is byte-identical with it
     * present or absent, and it exists purely so the caller can see what was wrapped.</p>
     *
     * @param reportMoves out-parameter receiving the collected moves, or null to record nothing
     */
    static Command wrapAnchoredChildren(Command base, IDiagramModelObject target,
            int targetX, int targetY, int targetW, int targetH, boolean boundsModified,
            Map<String, String[]> queuedAnchors, Map<String, int[]> sameBatchBounds,
            Map<String, Command> passMoves, Map<String, Command> reportMoves) {
        if (!boundsModified) {
            return base;
        }
        IDiagramModel dm = target.getDiagramModel();
        if (dm == null) {
            return base;
        }
        Map<String, Command> moves = (passMoves != null) ? passMoves : new LinkedHashMap<>();
        int before = moves.size();
        collectAnchoredMoves(dm, target.getId(), target.eContainer(),
                targetX, targetY, targetW, targetH, moves, queuedAnchors, sameBatchBounds);
        if (reportMoves != null) {
            reportMoves.putAll(moves);
        }
        if (passMoves != null || moves.size() == before) {
            return base;
        }
        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand("Update view object bounds with anchored children");
        compound.add(base);
        for (Command move : moves.values()) {
            compound.add(move);
        }
        return compound;
    }

    private static void collectAnchoredMoves(IDiagramModelContainer container, String targetId,
            EObject targetContainer, int targetX, int targetY, int targetW, int targetH,
            Map<String, Command> out, Map<String, String[]> queuedAnchors,
            Map<String, int[]> sameBatchBounds) {
        for (IDiagramModelObject child : container.getChildren()) {
            // A queued anchor outranks the persisted features: it is a declaration this batch has
            // already accepted, and the features still hold whatever was true before the batch.
            String[] queued = (queuedAnchors != null) ? queuedAnchors.get(child.getId()) : null;
            String childTarget = (queued != null) ? queued[0] : getAnchorTarget(child);
            // Reposition only children anchored to this target that live in the same coordinate
            // space (same parent) and are not the target itself — guards against cross-space
            // corruption and self-anchor double-moves.
            if (targetId.equals(childTarget)
                    && !targetId.equals(child.getId())
                    && child.eContainer() == targetContainer) {
                IBounds cb = child.getBounds();
                int[] pending = pendingBounds(sameBatchBounds, child.getId());
                int childX = (pending != null) ? pending[0] : cb.getX();
                int childY = (pending != null) ? pending[1] : cb.getY();
                int childW = (pending != null) ? pending[2] : cb.getWidth();
                int childH = (pending != null) ? pending[3] : cb.getHeight();
                int[] p = resolveByEdge(
                        (queued != null) ? queued[1] : readEdge(child),
                        targetX, targetY, targetW, targetH, childW, childH,
                        (queued != null) ? parseOffset(queued[2]) : readAnchorInt(child, ANCHOR_DX_FEATURE),
                        (queued != null) ? parseOffset(queued[3]) : readAnchorInt(child, ANCHOR_DY_FEATURE));
                if (p[0] != childX || p[1] != childY) {
                    out.put(child.getId(),
                            new UpdateViewObjectCommand(child, p[0], p[1], childW, childH));
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                collectAnchoredMoves(nested, targetId, targetContainer,
                        targetX, targetY, targetW, targetH, out, queuedAnchors, sameBatchBounds);
            }
        }
    }

    /**
     * Projects the bounds commands a pass emitted for objects the request never named into the
     * response shape, so the caller can be told what its request changed and where each ended up.
     *
     * <p>The sibling of the parent-fit cascade's own projection, and it exists for the same reason:
     * a pass ends up holding a map only it can see, and the step between "the object silently
     * changed" and "the agent knows where the object is" is turning that map into a report. Two
     * levers feed it — an anchored object displaced by the target it tracks, and a container grown
     * to clear the corner its icon renders into — and they share this so neither can drift from the
     * other on what a displaced object is called or which corner of it gets reported.</p>
     *
     * <p>Names are resolved from live containment rather than taken from the caller. An object the
     * view cannot name falls back to its id — still an actionable handle, never a placeholder that
     * would read as a real name. That fallback is the ordinary case inside a batch, where an object
     * an earlier operation created is still detached when this runs.</p>
     *
     * @param moves per-object bounds commands, keyed by object id; may be null or empty
     * @param root  the view to resolve names from
     * @return one entry per changed object, in the order the pass recorded them; never null
     */
    /**
     * The resize report for a multi-pass tool: what each pass OBSERVED, at the rectangle the
     * merged compound actually LANDS the object at.
     *
     * <p>Two passes can each re-size a leaf, and a pass that only MOVES a leaf an earlier pass
     * re-sized still changes where it ends up. Neither pass can answer on its own — the first
     * holds a rectangle a later one overwrites, and the later one never saw the size change.
     * Membership is therefore the union of the observations, and every rectangle is taken from
     * the last command in {@code landed}, which is the one that runs.</p>
     *
     * @param observed     one pass's resize observation; may be empty
     * @param alsoObserved a later pass's, or null when that pass did not run
     * @param landed       the merged compound that will actually be dispatched
     */
    static List<MovedViewObjectDto> projectResized(Map<String, Command> observed,
            Map<String, Command> alsoObserved, NonNotifyingCompoundCommand landed,
            IDiagramModelContainer root) {
        Map<String, Command> union = new LinkedHashMap<>(observed);
        if (alsoObserved != null) {
            union.putAll(alsoObserved);
        }
        return projectMoves(union, lastPlacements(landed), root);
    }

    /**
     * The objects an about-to-be-dispatched MULTI-ITERATION compound will land at a different SIZE
     * from the one they effectively have, each at the rectangle it lands at.
     *
     * <p>A control loop calls its mutation builder once per iteration and dispatches the iterations
     * it ACCEPTED as one outer compound. Projecting that compound — rather than accumulating what
     * each iteration observed — is what makes the report true of the model rather than of the
     * search that found it: an iteration the loop rejected was undone and never added, so it is
     * absent structurally rather than by a filter; the last command for an object wins, which is
     * exactly what the dispatch will do to the model; and a write range nobody threaded out is
     * still covered, because it is in the compound whether or not anyone remembered it.</p>
     *
     * <p>The walk RECURSES. The outer compound's members are the per-iteration compounds, so a scan
     * of the outer level alone matches nothing on any run that did not also emit a bare command — it
     * compiles, runs, and reports an empty list. One level in is enough for what these three tools
     * build today (measured: every per-iteration compound is flat), but a depth limit is a silent
     * failure waiting for the first pass that nests one deeper, and this file's own sibling walk
     * over queued batch commands ({@code MutationContext.forEachCommand}) already recurses without
     * one, for the same reason. Members that are bare placements are taken at whatever depth they
     * sit: a one-shot resize appended beside the iterations sits at the OUTER level, and it is
     * typically the largest single resize of the call.</p>
     *
     * <p>The size baseline is {@link #effectiveRect}, never {@code getBounds()}. Inside an open
     * batch the loop's commands were computed against queued geometry while a live read is a
     * pre-batch one, and comparing a queue-aware command against a queue-blind read reports both
     * resizes that will not happen and no-ops that will.</p>
     *
     * @param dispatched the outer compound about to be dispatched
     * @param pending    the same-batch geometry basis the commands were computed against
     * @param root       the view to resolve names from
     */
    static List<MovedViewObjectDto> projectResizedAcrossIterations(
            NonNotifyingCompoundCommand dispatched, Map<String, int[]> pending,
            IDiagramModelContainer root) {
        Map<String, Command> landed = new LinkedHashMap<>();
        collectPlacements(dispatched, landed);
        Map<String, Command> resized = new LinkedHashMap<>();
        for (Map.Entry<String, Command> entry : landed.entrySet()) {
            if (!(entry.getValue() instanceof UpdateViewObjectCommand cmd)) {
                continue;
            }
            int[] effective = effectiveRect(cmd.getDiagramObject(), pending);
            if (cmd.getNewWidth() != effective[2] || cmd.getNewHeight() != effective[3]) {
                resized.put(entry.getKey(), cmd);
            }
        }
        return projectMoves(resized, root);
    }

    /**
     * Every placement {@code compound} will run, keyed by object id, with a later command replacing
     * an earlier one for the same object — which is what dispatching the compound does to the model.
     *
     * <p>Depth-first and in order, so "later" means later in the dispatch, at whatever nesting the
     * command sits. Anything that is neither a compound to descend into nor a placement is skipped:
     * a spacing compound also carries the call's routing commands, and a connection's route is not
     * a resize.</p>
     */
    private static void collectPlacements(CompoundCommand compound, Map<String, Command> landed) {
        for (Command cmd : NestedLayoutOperations.commandsOf(compound)) {
            if (cmd instanceof CompoundCommand nested) {
                collectPlacements(nested, landed);
            } else if (cmd instanceof UpdateViewObjectCommand placement) {
                landed.put(placement.getDiagramObject().getId(), placement);
            }
        }
    }

    /**
     * The LAST placement command in {@code compound} for each object it writes, keyed by id.
     *
     * <p>A multi-pass tool merges its passes into one compound in pass order and dispatches it
     * once, so for any object the last command wins. A pass that recorded an observation about an
     * object BEFORE a later pass rewrote it therefore holds a rectangle the model never shows.
     * Feeding this map to {@link #projectMoves(Map, Map, IDiagramModelContainer)} as the
     * superseding argument fixes that for every later pass at once — including passes added
     * afterwards, which is the property a hand-listed superseding map does not have.</p>
     */
    static Map<String, Command> lastPlacements(NonNotifyingCompoundCommand compound) {
        Map<String, Command> last = new LinkedHashMap<>();
        for (Command cmd : NestedLayoutOperations.commandsOf(compound)) {
            if (cmd instanceof UpdateViewObjectCommand move) {
                last.put(move.getDiagramObject().getId(), move);
            }
        }
        return last;
    }

    /**
     * As {@link #projectMoves(Map, IDiagramModelContainer)}, but where a LATER pass of the same
     * call emitted its own command for an object, that command's rectangle is the one reported.
     *
     * <p>A multi-pass tool merges its passes into one compound in pass order, so the last command
     * for an object is the rectangle the object actually lands at. An earlier pass projected on its
     * own therefore reports a rectangle the model never holds — measured on the spacing tool: the
     * inflation pass placed a nested group at 352x66 and the overflow cascade then wrote 356x70
     * over it, so a report built from the inflation command alone was wrong by 4px in both
     * dimensions while presenting itself as landed geometry.</p>
     *
     * @param superseding commands from a later pass, keyed the same way; may be empty
     */
    static List<MovedViewObjectDto> projectMoves(Map<String, Command> moves,
            Map<String, Command> superseding, IDiagramModelContainer root) {
        if (moves == null || moves.isEmpty()) {
            return List.of();
        }
        Map<String, Command> landed = new LinkedHashMap<>(moves);
        if (superseding != null) {
            landed.replaceAll((id, cmd) -> superseding.getOrDefault(id, cmd));
        }
        return projectMoves(landed, root);
    }

    static List<MovedViewObjectDto> projectMoves(Map<String, Command> moves,
            IDiagramModelContainer root) {
        if (moves == null || moves.isEmpty()) {
            return List.of();
        }
        List<MovedViewObjectDto> projected = new ArrayList<>(moves.size());
        for (Map.Entry<String, Command> entry : moves.entrySet()) {
            if (!(entry.getValue() instanceof UpdateViewObjectCommand move)) {
                continue;
            }
            IDiagramModelObject obj = findChild(root, entry.getKey());
            String name = (obj != null && obj.getName() != null) ? obj.getName() : entry.getKey();
            projected.add(new MovedViewObjectDto(entry.getKey(), name,
                    move.getNewX(), move.getNewY(), move.getNewWidth(), move.getNewHeight()));
        }
        return projected;
    }

    /** Depth-first search for a view object by id within the originating object's diagram. */
    static IDiagramModelObject findInSameDiagram(IDiagramModelObject from, String targetId) {
        IDiagramModel dm = (from == null) ? null : from.getDiagramModel();
        if (dm == null || targetId == null) {
            return null;
        }
        return findChild(dm, targetId);
    }

    private static IDiagramModelObject findChild(IDiagramModelContainer container, String id) {
        if (container == null) {
            // Inside a batch the object a pass is preparing for may itself still be detached, so
            // there is no view to resolve names from. The id fallback above is the answer, and it
            // is the same answer this search gives for an object the view does not hold.
            return null;
        }
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId())) {
                return child;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelObject found = findChild(nested, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static String getAnchorTarget(IDiagramModelObject obj) {
        return obj.getFeatures().getString(ANCHOR_TARGET_FEATURE, null);
    }

    private static String readEdge(IDiagramModelObject obj) {
        return obj.getFeatures().getString(ANCHOR_EDGE_FEATURE, DEFAULT_EDGE);
    }

    private static int readAnchorInt(IDiagramModelObject obj, String key) {
        return parseOffset(obj.getFeatures().getString(key, null));
    }

    /**
     * Parses a persisted anchor offset. Absent, empty, and non-numeric all read as {@code 0} —
     * one parse discipline shared by the feature-list readers here and by the command that
     * re-resolves an anchor from its own stored offset strings.
     */
    static int parseOffset(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Post-mutation anchor values for the update-view-object result DTO. */
    public record AnchorInfo(String target, String edge, Integer dx, Integer dy) {
    }

    /**
     * Computes the anchor values the result DTO should report after this mutation:
     * a set echoes the requested values (edge defaulted, dx/dy defaulted to 0);
     * a clear ({@code reqTarget} empty string) reports all-null; when the call did not
     * touch the anchor ({@code reqTarget} null), reflects the object's current anchor features.
     */
    static AnchorInfo computePostAnchor(IDiagramModelObject obj, String reqTarget, String reqEdge,
            Integer reqDx, Integer reqDy) {
        if (reqTarget != null) {
            if (reqTarget.isEmpty()) {
                return new AnchorInfo(null, null, null, null);
            }
            return new AnchorInfo(reqTarget, normalizeEdge(reqEdge),
                    reqDx != null ? reqDx : 0, reqDy != null ? reqDy : 0);
        }
        String existing = getAnchorTarget(obj);
        if (existing == null) {
            return new AnchorInfo(null, null, null, null);
        }
        return new AnchorInfo(existing, readEdge(obj),
                readAnchorInt(obj, ANCHOR_DX_FEATURE), readAnchorInt(obj, ANCHOR_DY_FEATURE));
    }
}
