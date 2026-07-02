package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.ErrorCode;

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
        IBounds b = obj.getBounds();
        int mergedX = (x != null) ? x : b.getX();
        int mergedY = (y != null) ? y : b.getY();
        int mergedWidth = (width != null) ? width : b.getWidth();
        int mergedHeight = (height != null) ? height : b.getHeight();
        if (anchorTarget != null && !anchorTarget.isEmpty()) {
            IDiagramModelObject target = findInSameDiagram(obj, anchorTarget);
            if (target != null) {
                if (target == obj) {
                    throw new ModelAccessException(
                            "Cannot anchor a view object to itself",
                            ErrorCode.INVALID_PARAMETER, null,
                            "anchorTarget must reference a different view object.", null);
                }
                if (target.eContainer() != obj.eContainer()) {
                    // Bounds are stored relative to the immediate parent; resolving across
                    // coordinate spaces would write the wrong numbers. Enforce same-space.
                    throw new ModelAccessException(
                            "anchorTarget must share the same parent container as the anchored object",
                            ErrorCode.INVALID_PARAMETER, null,
                            "Anchor to a sibling object (both top-level, or both inside the same group).",
                            null);
                }
                IBounds tb = target.getBounds();
                int dx = (anchorDx != null) ? anchorDx : 0;
                int dy = (anchorDy != null) ? anchorDy : 0;
                int[] p = resolveByEdge(anchorEdge, tb.getX(), tb.getY(), tb.getWidth(), tb.getHeight(),
                        mergedWidth, mergedHeight, dx, dy);
                mergedX = p[0];
                mergedY = p[1];
            }
        }
        return new int[] { mergedX, mergedY, mergedWidth, mergedHeight };
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
        if (!boundsModified) {
            return base;
        }
        IDiagramModel dm = target.getDiagramModel();
        if (dm == null) {
            return base;
        }
        List<Command> moves = new ArrayList<>();
        collectAnchoredMoves(dm, target.getId(), target.eContainer(),
                targetX, targetY, targetW, targetH, moves);
        if (moves.isEmpty()) {
            return base;
        }
        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand("Update view object bounds with anchored children");
        compound.add(base);
        for (Command move : moves) {
            compound.add(move);
        }
        return compound;
    }

    private static void collectAnchoredMoves(IDiagramModelContainer container, String targetId,
            EObject targetContainer, int targetX, int targetY, int targetW, int targetH,
            List<Command> out) {
        for (IDiagramModelObject child : container.getChildren()) {
            // Reposition only children anchored to this target that live in the same coordinate
            // space (same parent) and are not the target itself — guards against cross-space
            // corruption and self-anchor double-moves.
            if (targetId.equals(getAnchorTarget(child))
                    && !targetId.equals(child.getId())
                    && child.eContainer() == targetContainer) {
                IBounds cb = child.getBounds();
                int[] p = resolveByEdge(readEdge(child),
                        targetX, targetY, targetW, targetH,
                        cb.getWidth(), cb.getHeight(),
                        readAnchorInt(child, ANCHOR_DX_FEATURE), readAnchorInt(child, ANCHOR_DY_FEATURE));
                if (p[0] != cb.getX() || p[1] != cb.getY()) {
                    out.add(new UpdateViewObjectCommand(child, p[0], p[1], cb.getWidth(), cb.getHeight()));
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                collectAnchoredMoves(nested, targetId, targetContainer,
                        targetX, targetY, targetW, targetH, out);
            }
        }
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
        String v = obj.getFeatures().getString(key, null);
        if (v == null || v.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(v);
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
