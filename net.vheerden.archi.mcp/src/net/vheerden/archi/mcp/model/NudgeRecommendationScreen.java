package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.routing.MoveRecommendation;
import net.vheerden.archi.mcp.model.routing.RoutingRecommendationEngine;

/**
 * Screens the router's move recommendations against the clamp the autoNudge loop applies, so a
 * move that the clamp would absorb is never offered.
 *
 * <p><strong>Why it exists.</strong> The recommendation engine works on absolute canvas
 * rectangles and knows nothing about nesting; the loop applies each move to the element's bounds
 * relative to its parent and then calls {@link NestedLayoutOperations#clampInsideParent}, which
 * holds a nested child off its parent's left padding and title band. A child already sitting on
 * one of those floors was recommended the same move on every call, the clamp absorbed it every
 * time, and the next call recommended it again: a loop that never converges.</p>
 *
 * <p><strong>One rule, read by both sides.</strong> This class never computes a floor. It runs the
 * mutator's own arithmetic, in the mutator's frame (the view object's relative bounds plus the
 * displacement earlier iterations already gave it), through the same
 * {@link NestedLayoutOperations#clampInsideParent} call with the same padding, and reads back
 * what the clamp would let through. If the floor ever changes, both sides move with it. The
 * mutator keeps its own clamp as a defence; on a screened list it is a no-op.</p>
 *
 * <p><strong>Per axis, a component survives only if the clamp leaves it moving the way it was
 * recommended.</strong> A shortened move is kept and re-described by the engine's own sentence. A
 * component the clamp zeroes, or turns around (the x floor is flat, so a child already left of the
 * padding line is pushed right by it), is dropped. A recommendation with nothing left is withheld,
 * and remembered for the response with the floor that stopped it.</p>
 *
 * <p>Lives outside the accessor because the loop it guards is unreachable in a display-less test:
 * the router routes around every blocker a headless fixture can build. A headless test drives this
 * class directly.</p>
 */
final class NudgeRecommendationScreen {

    /** The parent floor that stopped a recommended move, with the direction it stops. */
    enum Floor {
        LEFT_PADDING("left padding", "left"),
        TITLE_BAND("title band", "up");

        final String side;
        final String direction;

        Floor(String side, String direction) {
            this.side = side;
            this.direction = direction;
        }
    }

    /** A recommendation the screen withheld, and why. */
    record Withheld(String elementId, String elementName, String parentId, String parentName,
            Set<Floor> floors) {
    }

    private final Map<String, IDiagramModelObject> viewObjects;
    private final int padding;
    private final Map<String, Withheld> withheld = new LinkedHashMap<>();

    /**
     * @param viewObjects every view object on the view by id, the map the loop resolves
     *                    recommendations through
     * @param padding     the padding the loop passes to the clamp; pass the same constant
     */
    NudgeRecommendationScreen(Map<String, IDiagramModelObject> viewObjects, int padding) {
        this.viewObjects = viewObjects;
        this.padding = padding;
    }

    /**
     * The recommendations the loop can apply as written, in their original order.
     *
     * @param recommendations what the routing pass recommended
     * @param priorDeltas     the displacement each element already has from earlier iterations,
     *                        keyed by view-object id; empty on the first pass
     */
    List<MoveRecommendation> screen(List<MoveRecommendation> recommendations,
            Map<String, int[]> priorDeltas) {
        List<MoveRecommendation> kept = new ArrayList<>(recommendations.size());
        for (MoveRecommendation rec : recommendations) {
            IDiagramModelObject dmo = viewObjects.get(rec.elementId());
            if (dmo == null) {
                // The loop skips an id it cannot resolve; nothing here to judge it against.
                keep(kept, rec);
                continue;
            }
            IBounds bounds = dmo.getBounds();
            int[] prior = priorDeltas.getOrDefault(rec.elementId(), new int[]{0, 0});
            int startX = bounds.getX() + prior[0];
            int startY = bounds.getY() + prior[1];
            EObject parent = dmo.eContainer();
            int[] clamped = NestedLayoutOperations.clampInsideParent(parent,
                    startX + rec.dx(), startY + rec.dy(), startY, padding);
            int keptDx = sameDirection(clamped[0] - startX, rec.dx());
            int keptDy = sameDirection(clamped[1] - startY, rec.dy());
            if (keptDx == rec.dx() && keptDy == rec.dy()) {
                keep(kept, rec);
                continue;
            }
            if (keptDx == 0 && keptDy == 0) {
                Set<Floor> floors = EnumSet.noneOf(Floor.class);
                if (rec.dx() != 0) {
                    floors.add(Floor.LEFT_PADDING);
                }
                if (rec.dy() != 0) {
                    floors.add(Floor.TITLE_BAND);
                }
                // A later pass can be stopped by another floor: the response names every floor that
                // stopped this element, not only the first.
                Withheld earlier = withheld.get(rec.elementId());
                if (earlier != null) {
                    floors.addAll(earlier.floors());
                }
                withheld.put(rec.elementId(), withheldFor(dmo, parent, floors));
                continue;
            }
            keep(kept, new MoveRecommendation(rec.elementId(), rec.elementName(), keptDx, keptDy,
                    RoutingRecommendationEngine.buildReason(keptDx, keptDy,
                            rec.connectionsUnblocked()),
                    rec.connectionsUnblocked()));
        }
        return List.copyOf(kept);
    }

    /**
     * Offers {@code rec}, and forgets any earlier withholding of its element: a later pass that
     * offers the element a move it can take (which the loop may then apply) makes "withheld, not
     * attempted" false about it, so the response must not say it.
     */
    private void keep(List<MoveRecommendation> kept, MoveRecommendation rec) {
        kept.add(rec);
        withheld.remove(rec.elementId());
    }

    /**
     * Every element withheld on the last pass that judged it, one per element, with every floor
     * that stopped it across the call.
     */
    List<Withheld> withheld() {
        return List.copyOf(withheld.values());
    }

    /**
     * The part of {@code allowed} that still moves the way {@code recommended} does: all of it when
     * the clamp only shortened the move, nothing when it zeroed or reversed it, or when nothing was
     * recommended on this axis.
     *
     * <p>Never more than was recommended. The x floor is flat, so a child left of the padding line
     * given a small move right is carried PAST the move by the clamp, to the line. That longer move
     * is not the router's: it was never checked against the neighbours, so it is not offered. The
     * recommendation stands as the router made it, and the loop's own clamp still lands the child
     * on the padding line when it applies it — the residual the routing reference states.</p>
     */
    private static int sameDirection(int allowed, int recommended) {
        if (recommended == 0 || Integer.signum(allowed) != Integer.signum(recommended)) {
            return 0;
        }
        return Math.abs(allowed) > Math.abs(recommended) ? recommended : allowed;
    }

    private static Withheld withheldFor(IDiagramModelObject dmo, EObject parent, Set<Floor> floors) {
        String parentId = null;
        String parentName = null;
        if (parent instanceof IDiagramModelObject parentObject) {
            parentId = parentObject.getId();
            parentName = displayName(parentObject);
        }
        // Kept as an EnumSet so the floors are always named in one order.
        return new Withheld(dmo.getId(), displayName(dmo), parentId, parentName,
                java.util.Collections.unmodifiableSet(floors));
    }

    private static String displayName(IDiagramModelObject obj) {
        return obj.getName() != null && !obj.getName().isBlank() ? obj.getName() : obj.getId();
    }
}
