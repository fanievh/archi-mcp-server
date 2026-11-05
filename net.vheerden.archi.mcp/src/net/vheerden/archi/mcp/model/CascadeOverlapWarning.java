package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ResizedGroupDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Says when a container a call grew now overlaps a sibling it did not overlap before.
 *
 * <p>The parent-fit cascade grows a group to keep a moved or enlarged child inside it, and walks up
 * through every enclosing group doing the same. It never looks sideways: a group grown that way
 * can come to lie over its neighbour, and the response named the resize (in
 * {@code resizedAncestors} or {@code resizedGroups}) but not the overlap, which the next
 * {@code assess-layout} then rated. This class is that naming. It moves nothing.</p>
 *
 * <p><strong>Measured before the call lands.</strong> Every caller runs while the model still holds
 * the rectangles the call is about to replace, so a container's current bounds are its "before" and
 * the call's written rectangles its "after". A sibling's "after" is the rectangle the same call
 * writes for it, or its current one. Only an overlap that is present after and absent before is
 * news; a sibling the container already overlapped is not named. Siblings share a parent, so their
 * bounds share its coordinate space. Notes are left out, as {@code assess-layout}'s overlap count
 * leaves them out.</p>
 *
 * <p>In a batch, in a bulk call and under approval the "before" is the model as it stands, which
 * an operation queued beside this one may still change, and every message says so.</p>
 *
 * <p><strong>Never throws.</strong> A disclosure must not fail the write it discloses: callers sit
 * in prepares that have already recorded state a dropped operation would strand (the icon-band
 * reservation is one). A measurement that fails is logged and answers nothing.</p>
 */
public final class CascadeOverlapWarning {

    private static final Logger logger = LoggerFactory.getLogger(CascadeOverlapWarning.class);

    private CascadeOverlapWarning() {
    }

    /**
     * The warnings for containers whose new rectangles are listed in {@code grown}, with
     * {@code alsoWritten} carrying the other rectangles the same call writes (any of which may be a
     * sibling's). Either list may be null.
     */
    public static List<StructuredWarningDto> forMoves(IDiagramModel view,
            List<MovedViewObjectDto> grown, List<MovedViewObjectDto> alsoWritten, DispatchArm arm) {
        return guarded(() -> movesOf(view, grown, alsoWritten, arm));
    }

    private static List<StructuredWarningDto> movesOf(IDiagramModel view,
            List<MovedViewObjectDto> grown, List<MovedViewObjectDto> alsoWritten, DispatchArm arm) {
        Map<String, int[]> grownRects = new LinkedHashMap<>();
        putMoves(grownRects, grown);
        Map<String, int[]> written = new LinkedHashMap<>();
        putMoves(written, alsoWritten);
        written.putAll(grownRects);
        return of(view, grownRects.keySet(), written, arm);
    }

    /** As {@link #forMoves}, for the {@code resizedGroups} shape. */
    public static List<StructuredWarningDto> forGroups(IDiagramModel view,
            List<ResizedGroupDto> grown, Map<String, int[]> alsoWritten, DispatchArm arm) {
        return guarded(() -> groupsOf(view, grown, alsoWritten, arm));
    }

    private static List<StructuredWarningDto> groupsOf(IDiagramModel view,
            List<ResizedGroupDto> grown, Map<String, int[]> alsoWritten, DispatchArm arm) {
        Map<String, int[]> written = new LinkedHashMap<>();
        if (alsoWritten != null) {
            written.putAll(alsoWritten);
        }
        Set<String> grownIds = new LinkedHashSet<>();
        if (grown != null) {
            for (ResizedGroupDto group : grown) {
                grownIds.add(group.viewObjectId());
                written.put(group.viewObjectId(), new int[] {
                        group.newX(), group.newY(), group.newWidth(), group.newHeight() });
            }
        }
        return of(view, grownIds, written, arm);
    }

    /**
     * The warnings for a call whose whole effect is {@code command}: every container it writes a
     * larger rectangle for counts as grown, and every rectangle it writes is in place for the
     * siblings. The last placement of an object wins, as dispatching the command makes it win.
     */
    public static List<StructuredWarningDto> forCommand(IDiagramModel view, Command command,
            DispatchArm arm) {
        return guarded(() -> commandOf(view, command, arm));
    }

    private static List<StructuredWarningDto> commandOf(IDiagramModel view, Command command,
            DispatchArm arm) {
        Map<String, IDiagramModelObject> objects = new LinkedHashMap<>();
        Map<String, int[]> written = new LinkedHashMap<>();
        collectPlacements(command, objects, written);
        Set<String> grownIds = new LinkedHashSet<>();
        for (Map.Entry<String, int[]> entry : written.entrySet()) {
            IDiagramModelObject object = objects.get(entry.getKey());
            IBounds before = object.getBounds();
            int[] after = entry.getValue();
            if (object instanceof IDiagramModelContainer && before != null
                    && (after[2] > before.getWidth() || after[3] > before.getHeight())) {
                grownIds.add(entry.getKey());
            }
        }
        return of(view, grownIds, written, arm);
    }

    /**
     * For a pass that reports both prose {@code warnings} and their structured counterparts: adds
     * each warning for {@code grown}, against every placement in {@code written}, to both lists.
     */
    public static void emitInto(IDiagramModel view, List<ResizedGroupDto> grown,
            List<Command> written, DispatchArm arm, List<String> warnings,
            List<StructuredWarningDto> structuredWarnings) {
        List<StructuredWarningDto> found = guarded(() -> {
            Map<String, IDiagramModelObject> objects = new LinkedHashMap<>();
            Map<String, int[]> placements = new LinkedHashMap<>();
            for (Command command : written) {
                collectPlacements(command, objects, placements);
            }
            return groupsOf(view, grown, placements, arm);
        });
        for (StructuredWarningDto warning : found) {
            warnings.add(warning.message());
            structuredWarnings.add(warning);
        }
    }

    private static List<StructuredWarningDto> guarded(Supplier<List<StructuredWarningDto>> measure) {
        try {
            return measure.get();
        } catch (RuntimeException e) {
            logger.warn("Sibling overlap check for a grown container skipped: {}", e.toString());
            return List.of();
        }
    }

    /** {@code earlier} followed by {@code later}; a convenience for a DTO that carries both. */
    public static List<StructuredWarningDto> alongside(List<StructuredWarningDto> earlier,
            List<StructuredWarningDto> later) {
        if (later.isEmpty()) {
            return earlier;
        }
        if (earlier == null || earlier.isEmpty()) {
            return later;
        }
        List<StructuredWarningDto> both = new ArrayList<>(earlier);
        both.addAll(later);
        return List.copyOf(both);
    }

    /**
     * The core: for each id in {@code grownIds}, the siblings its rectangle in {@code written}
     * newly overlaps. A null {@code arm} answers nothing: it marks a caller inside a larger pass
     * that reports the cascade itself, once, for the whole pass.
     */
    static List<StructuredWarningDto> of(IDiagramModel view, Set<String> grownIds,
            Map<String, int[]> written, DispatchArm arm) {
        if (view == null || arm == null || grownIds.isEmpty()) {
            return List.of();
        }
        Map<String, IDiagramModelObject> byId = new LinkedHashMap<>();
        index(view, byId);
        List<StructuredWarningDto> warnings = new ArrayList<>();
        for (String id : grownIds) {
            IDiagramModelObject grown = byId.get(id);
            if (grown == null || grown.getBounds() == null
                    || !(grown.eContainer() instanceof IDiagramModelContainer parent)) {
                continue; // not on the view yet (a same-batch creation): nothing to compare against
            }
            int[] before = rect(grown.getBounds());
            int[] after = written.get(id);
            List<IDiagramModelObject> struck = new ArrayList<>();
            for (IDiagramModelObject sibling : parent.getChildren()) {
                if (sibling == grown || sibling instanceof IDiagramModelNote
                        || sibling.getBounds() == null) {
                    continue;
                }
                int[] siblingBefore = rect(sibling.getBounds());
                int[] siblingAfter = written.getOrDefault(sibling.getId(), siblingBefore);
                if (overlaps(after, siblingAfter) && !overlaps(before, siblingBefore)) {
                    struck.add(sibling);
                }
            }
            if (!struck.isEmpty()) {
                warnings.add(warning(grown, parent, struck, arm));
            }
        }
        return List.copyOf(warnings);
    }

    private static StructuredWarningDto warning(IDiagramModelObject grown,
            IDiagramModelContainer parent, List<IDiagramModelObject> struck, DispatchArm arm) {
        StringBuilder names = new StringBuilder();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < struck.size(); i++) {
            if (i > 0) {
                names.append(i == struck.size() - 1 ? " and " : ", ");
            }
            names.append(describe(struck.get(i)));
            ids.add(struck.get(i).getId());
        }
        String kind = grown instanceof IDiagramModelGroup ? "Group " : "Container ";
        String siblingWord = struck.size() == 1 ? "its sibling " : "its siblings ";
        boolean topLevel = parent instanceof IDiagramModel;
        String remedyTool = topLevel ? "arrange-groups" : "layout-within-group";
        String remedy = topLevel
                ? " Re-space the view's top-level containers with arrange-groups (a plain element is"
                        + " positioned only when named in groupIds), or move the sibling with"
                        + " update-view-object."
                : " Re-lay the containing object " + CallerText.quoted(((IDiagramModelObject) parent).getId())
                        + " with layout-within-group, or move the sibling with update-view-object.";
        String message = switch (arm) {
            case APPLIED -> kind + describe(grown) + " grew in this call to fit what it holds, and now"
                    + " overlaps " + siblingWord + names + ", which it did not overlap before."
                    + " Nothing was moved to make room.";
            case QUEUED -> kind + describe(grown) + " will grow to fit what it holds, and will then"
                    + " overlap " + siblingWord + names + ", which it does not overlap now. This is"
                    + " measured against the view as it stands; an operation queued beside this one"
                    + " may still move either. Nothing will be moved to make room.";
            case AWAITING_APPROVAL -> "If this change is approved, " + kind.toLowerCase(Locale.ROOT)
                    + describe(grown) + " will grow to fit what it holds, and will then overlap "
                    + siblingWord + names + ", which it does not overlap now. Nothing will be moved"
                    + " to make room.";
        };
        return new StructuredWarningDto(StructuredWarningCodes.CASCADE_GROWN_INTO_SIBLING,
                message + remedy, remedyTool, List.copyOf(ids));
    }

    private static String describe(IDiagramModelObject object) {
        String name = object.getName();
        return (name == null || name.isEmpty() ? "" : CallerText.quoted(name) + " ")
                + "(view object " + CallerText.quoted(object.getId()) + ")";
    }

    private static boolean overlaps(int[] a, int[] b) {
        return OverlapResolver.rectsOverlap(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3]);
    }

    private static int[] rect(IBounds bounds) {
        return new int[] { bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight() };
    }

    private static void putMoves(Map<String, int[]> into, List<MovedViewObjectDto> moves) {
        if (moves == null) {
            return;
        }
        for (MovedViewObjectDto move : moves) {
            into.put(move.viewObjectId(),
                    new int[] { move.newX(), move.newY(), move.newWidth(), move.newHeight() });
        }
    }

    private static void collectPlacements(Command command, Map<String, IDiagramModelObject> objects,
            Map<String, int[]> written) {
        if (command instanceof CompoundCommand compound) {
            for (Command child : NestedLayoutOperations.commandsOf(compound)) {
                collectPlacements(child, objects, written);
            }
        } else if (command instanceof UpdateViewObjectCommand placement) {
            IDiagramModelObject object = placement.getDiagramObject();
            objects.put(object.getId(), object);
            written.put(object.getId(), new int[] { placement.getNewX(), placement.getNewY(),
                    placement.getNewWidth(), placement.getNewHeight() });
        }
    }

    private static void index(IDiagramModelContainer container, Map<String, IDiagramModelObject> into) {
        for (IDiagramModelObject child : container.getChildren()) {
            into.put(child.getId(), child);
            if (child instanceof IDiagramModelContainer nested) {
                index(nested, into);
            }
        }
    }
}
