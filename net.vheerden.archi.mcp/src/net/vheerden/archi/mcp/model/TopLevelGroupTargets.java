package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelComponent;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.SkippedContainerDto;

/**
 * Decides what the group-arrangement family treats as a top-level container.
 *
 * <p>Two different Archi objects render as a labelled box holding other objects, and both are
 * legitimate targets for arrangement:
 * <ul>
 *   <li>a <b>native view group</b> ({@link IDiagramModelGroup}), created by {@code add-group-to-view} —
 *       a diagram-only device with no model semantics; and</li>
 *   <li>an <b>ArchiMate {@code Grouping} element</b>, created by {@code create-element} and placed by
 *       {@code add-to-view} — an {@link IDiagramModelArchimateObject} whose concept is an
 *       {@link IGrouping}, which participates in relationships and is queryable in the model.</li>
 * </ul>
 *
 * <p>Collecting only the first left every view built the second way unarranged. That shape is not
 * exotic: it is what the technology and deployment guidance prescribes, which asks for one
 * {@code Grouping} per zone and then arranges the zones. Three of the four tools in the family did
 * not merely skip such a view — they rejected it with a message stating it had no groups, which is
 * a false statement an agent that cannot see the canvas has no way to contradict.
 *
 * <p><b>Why the return type is {@link IDiagramModelObject} and not something narrower.</b> Both
 * concrete types implement {@link IDiagramModelObject} (for {@code getBounds}/{@code setBounds}) and
 * {@link IDiagramModelContainer} (for {@code getChildren}), but neither interface extends the other
 * and Java forbids an intersection type as a type argument. Callers therefore hold the geometry
 * type and reach children through {@link #childrenOf(IDiagramModelObject)}, whose cast is safe by
 * construction because nothing enters these lists without passing {@link #isTarget}. This mirrors
 * the two-local idiom {@code layoutWithinGroup} already uses for the same reason.
 */
final class TopLevelGroupTargets {

    private TopLevelGroupTargets() {}

    /**
     * Whether {@code obj} is a container the arrangement family should position.
     *
     * <p>Deliberately narrow: an ArchiMate element qualifies only when its concept is an
     * {@link IGrouping}. A {@code Node} that happens to hold nested children is a host, not a zone,
     * and arranging it alongside the zones would reorder the deployment topology.
     */
    static boolean isTarget(IDiagramModelObject obj) {
        return obj instanceof IDiagramModelGroup || isGroupingZone(obj);
    }

    /**
     * Whether {@code obj} is the ArchiMate half of {@link #isTarget} — an element view-object whose
     * concept is an {@link IGrouping}.
     *
     * <p>Split out because two questions need the halves apart. {@link #isTarget} asks "is this a
     * container the arrangement family should position", and both kinds answer yes.
     * {@code resize-elements-to-fit} asks the narrower question "is this a zone rather than a
     * label-bearing element", and only this half answers yes: a native view group is not a resize
     * target at all, so it never reaches that decision. Deriving {@link #isTarget} from this method
     * keeps the canonical predicate single-sourced — the two cannot drift apart.</p>
     */
    static boolean isGroupingZone(IDiagramModelObject obj) {
        return obj instanceof IDiagramModelArchimateObject archimateObject
                && archimateObject.getArchimateConcept() instanceof IGrouping;
    }

    /**
     * The view's own children that are arrangement targets, in view child order.
     *
     * <p>Order is the caller's own insertion order, so an arrangement is predictable from what the
     * caller built. Only direct children qualify — a {@code Grouping} nested inside another
     * container is not in this list.
     *
     * <p><b>This is the CANVAS population, not the definition of top-level.</b> A zone drawn
     * inside a host the predicate declines is top-level and is not here, because these containers
     * are positioned in the view's own coordinate space and a nested one's coordinates are not
     * measured from it. {@link #collectOutermost} answers the wider question; this one answers
     * "which containers can be laid out on the canvas".
     */
    static List<IDiagramModelObject> collect(IDiagramModelContainer view) {
        List<IDiagramModelObject> targets = new ArrayList<>();
        for (IDiagramModelObject child : view.getChildren()) {
            if (isTarget(child)) {
                targets.add(child);
            }
        }
        return targets;
    }

    /**
     * Every target on the view that no other target contains — the outermost ones, at any depth.
     *
     * <p>Depth-first through the view's children in child order. A target is admitted and NOT
     * descended into, because a container inside a container is a member of it rather than a second
     * target. A non-target container is descended through, because the object it holds may be the
     * outermost target on that branch: a {@code Grouping} zone drawn inside a {@code Node} typing a
     * cloud region has no target above it and is therefore top-level, whatever its depth.</p>
     *
     * <p><b>This is {@link #topLevelGroupOf} asked from the other end.</b> For every object
     * {@code x} on the view, {@code topLevelGroupOf(x)} is either null or an element of this list —
     * one walks the containment chain up and returns the outermost admitted ancestor, the other
     * walks it down and stops at the same boundary. Keeping both in this class, deriving both from
     * {@link #isTarget}, is what stops a counting route and a collecting route from answering
     * differently about the same view, which is what they used to do.</p>
     *
     * <p><b>On any view holding no target beneath a non-target, this returns exactly what
     * {@link #collect} returns</b> — the same containers in the same order — because the descent
     * only ever reaches deeper than the view's own children when a non-target container stands in
     * the way. That equivalence is what makes the widening safe for every view already in a
     * corpus, and it is pinned rather than argued.</p>
     */
    static List<IDiagramModelObject> collectOutermost(IDiagramModelContainer view) {
        List<IDiagramModelObject> targets = new ArrayList<>();
        collectOutermostInto(view, targets);
        return targets;
    }

    private static void collectOutermostInto(
            IDiagramModelContainer container, List<IDiagramModelObject> targets) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (isTarget(child)) {
                targets.add(child);
            } else if (child instanceof IDiagramModelContainer nested) {
                collectOutermostInto(nested, targets);
            }
        }
    }

    /**
     * The largest run of the given containers that share one coordinate frame.
     *
     * <p><b>Geometry may only be compared within a frame.</b> A container drawn on the view holds
     * absolute canvas coordinates; one drawn inside a host holds coordinates relative to that
     * host. {@link #collectOutermost} deliberately returns both kinds, because "which containers
     * are top-level" is a question about containment and not about coordinates — but a gap
     * measured between an absolute rectangle and a relative one is not a distance, it is two
     * numbers subtracted for no reason, and it would be published as a measured pixel count.
     * Anything that reads BOUNDS off that collection has to narrow to one frame first.</p>
     *
     * <p>Ties go to the view's own children, so a view that never held a nested container measures
     * exactly what it always measured. On a view with no containers at all the answer is empty and
     * the caller's own "fewer than two" guard handles it.</p>
     */
    static List<IDiagramModelObject> largestSharedFrame(
            IDiagramModelContainer view, List<IDiagramModelObject> containers) {
        Map<EObject, List<IDiagramModelObject>> byFrame = new LinkedHashMap<>();
        for (IDiagramModelObject container : containers) {
            byFrame.computeIfAbsent(container.eContainer(), f -> new ArrayList<>())
                    .add(container);
        }
        List<IDiagramModelObject> largest = new ArrayList<>();
        for (Map.Entry<EObject, List<IDiagramModelObject>> frame : byFrame.entrySet()) {
            boolean beatsOnSize = frame.getValue().size() > largest.size();
            boolean tiesAndIsTheCanvas = frame.getValue().size() == largest.size()
                    && frame.getKey() == view;
            if (beatsOnSize || tiesAndIsTheCanvas) {
                largest = frame.getValue();
            }
        }
        return largest;
    }

    /**
     * The minimum gap between the given containers, which MUST already share one coordinate frame.
     *
     * <p>Lives beside {@link #largestSharedFrame} because the two are one operation: narrow to a
     * frame, then measure inside it. Splitting them put a bounds read in the facade and a frame
     * rule in this class, and the facade duly measured a gap across two frames and published it
     * as a pixel count. Keeping them together is what makes that hard to write again.</p>
     *
     * <p>Returns {@link GroupLayoutCalculator#DEFAULT_DETECTED_SPACING} for fewer than two
     * containers, where no inter-container gap exists to measure. That value is a placeholder and
     * not a measurement: every caller guards the same degenerate case separately, and none of them
     * computes a delta from it.</p>
     */
    static int sharedFrameSpacing(List<IDiagramModelObject> sameFrameContainers) {
        if (sameFrameContainers.size() < 2) {
            return GroupLayoutCalculator.DEFAULT_DETECTED_SPACING;
        }
        List<int[]> rects = new ArrayList<>(sameFrameContainers.size());
        for (IDiagramModelObject container : sameFrameContainers) {
            IBounds b = container.getBounds();
            rects.add(new int[]{b.getX(), b.getY(), b.getWidth(), b.getHeight()});
        }
        return GroupLayoutCalculator.detectInterGroupSpacing(rects);
    }

    /**
     * As {@link #collectOutermost}, restricted to targets that actually hold something — the
     * outermost-walk counterpart of {@link #collectPopulated}, and guarding the same thing for the
     * same reason.
     */
    static List<IDiagramModelObject> collectOutermostPopulated(IDiagramModelContainer view) {
        List<IDiagramModelObject> populated = new ArrayList<>();
        for (IDiagramModelObject target : collectOutermost(view)) {
            if (!childrenOf(target).isEmpty()) {
                populated.add(target);
            }
        }
        return populated;
    }

    /**
     * As {@link #collect}, restricted to targets that actually hold something. Several callers guard
     * on "groups with children" because arranging an empty box has no effect worth reporting.
     */
    static List<IDiagramModelObject> collectPopulated(IDiagramModelContainer view) {
        List<IDiagramModelObject> targets = new ArrayList<>();
        for (IDiagramModelObject child : view.getChildren()) {
            if (isTarget(child) && !childrenOf(child).isEmpty()) {
                targets.add(child);
            }
        }
        return targets;
    }

    /**
     * The {@code resize-elements-to-fit} target walk: every element view-object on the view, a
     * {@code Grouping} included, depth-first through both kinds of container.
     *
     * <p>A native view group is <em>never collected as a target</em> — the walk descends through it
     * to reach the elements inside. It can still be grown afterwards: every target this tool
     * resizes goes through the parent-fit cascade, which acts on native view groups only and grows
     * one around a child the pass widened. "Never collected" and "never touched" are different
     * claims, and only the first is true.</p>
     *
     * <p>Lives here rather than beside its caller because the collection question and the
     * {@link #isTarget} question are the same question asked twice, and because a walk in this
     * class can be driven directly by a headless test.</p>
     */
    static void collectElementViewObjects(IDiagramModelContainer container,
            List<IDiagramModelArchimateObject> result) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject archObj) {
                result.add(archObj);
                // Recurse into nested elements
                collectElementViewObjects(archObj, result);
            } else if (child instanceof IDiagramModelGroup group) {
                // Recurse into groups to find elements inside them
                collectElementViewObjects(group, result);
            }
        }
    }

    /**
     * Which targets hold which, for the two-pass containment sizing of
     * {@code resize-elements-to-fit}. Keys are parent ids; a target absent from the key set is a
     * leaf.
     *
     * <p>The two kinds of parent are mapped from different populations, and deliberately so.</p>
     *
     * <p>An ordinary element parent is mapped from {@code targets} — the list the caller's
     * {@code elementIds} has already filtered — because its size follows the children this pass is
     * actually sizing.</p>
     *
     * <p>A <b>zone</b> is mapped from every ArchiMate element it holds on the view, named or not.
     * The remedy every surface publishes for a child that has grown outside its parent is
     * "{@code resize-elements-to-fit} on the parent", so a caller following it names <em>the
     * container</em> and not the child. Mapping a zone from the filtered list left that call
     * looking at a childless zone: it dropped to the leaf pass, was skipped as a zone with nothing
     * to grow around, and the published remedy failed precisely when it was called as published.
     * The children gathered here are measured, never mutated — one the caller did not name keeps
     * its own bounds and contributes them to the box the zone must cover.</p>
     */
    static Map<String, List<IDiagramModelArchimateObject>> containmentMap(
            List<IDiagramModelArchimateObject> targets) {
        Map<String, List<IDiagramModelArchimateObject>> byParent = new LinkedHashMap<>();
        for (IDiagramModelArchimateObject target : targets) {
            if (target.eContainer() instanceof IDiagramModelArchimateObject parent
                    && !isGroupingZone(parent)) {
                byParent.computeIfAbsent(parent.getId(), k -> new ArrayList<>()).add(target);
            }
        }
        for (IDiagramModelArchimateObject target : targets) {
            if (!isGroupingZone(target)) {
                continue;
            }
            for (IDiagramModelObject child : target.getChildren()) {
                if (child instanceof IDiagramModelArchimateObject element) {
                    byParent.computeIfAbsent(target.getId(), k -> new ArrayList<>()).add(element);
                }
            }
        }
        return byParent;
    }

    /**
     * Computes nesting depth of a view object (0 = direct child of view).
     */
    static int nestingDepth(IDiagramModelObject obj) {
        int depth = 0;
        EObject container = obj.eContainer();
        while (container instanceof IDiagramModelObject) {
            depth++;
            container = container.eContainer();
        }
        return depth;
    }

    /**
     * Direct children of the view that hold children of their own but are <em>not</em> targets —
     * the containers {@code arrange-groups} leaves standing.
     *
     * <p>This is the complement of {@link #collectPopulated} over the same population, and it
     * exists because the exclusion is invisible from the response otherwise. {@link #isTarget}
     * admits a native group and an ArchiMate {@code Grouping}; a plain element that happens to hold
     * children — a {@code Node} typing a cloud region, most often — is a host rather than a zone
     * and is deliberately not arranged. Measured 2026-08-14: a view with nine populated top-level
     * containers reported {@code groupsPositioned: 8}, and the ninth held an entire account branch.
     * Nothing named it, so the shortfall was detectable only by counting containers independently
     * and diffing.</p>
     *
     * <p>Empty containers are excluded on both sides: an empty box that did not move is not a
     * finding, and reporting it would bury the one that matters.</p>
     */
    static List<IDiagramModelObject> collectSkipped(IDiagramModelContainer view) {
        List<IDiagramModelObject> skipped = new ArrayList<>();
        for (IDiagramModelObject child : view.getChildren()) {
            if (!isTarget(child) && !childrenOf(child).isEmpty()) {
                skipped.add(child);
            }
        }
        return skipped;
    }

    /**
     * The children of a target. Safe by construction: {@link #isTarget} admits only types that are
     * also {@link IDiagramModelContainer}, so the cast cannot fail for anything these lists hold.
     * An object that is not a container yields an empty list rather than throwing, so a caller that
     * strays outside the family degrades to "no children" instead of failing at runtime.
     */
    /**
     * The direct children of {@code obj} a layout pass can arrange — every child except a note.
     *
     * <p>Notes are never laid out, so a container holding nothing else has nothing to arrange and
     * every grouped pass skips it. Each pass used to open-code that filter, and the copy in the
     * grouped call site existed only to answer "is there anything here?".</p>
     */
    /**
     * The layout children of {@code container}, reordered to match {@code order}.
     *
     * <p>An element-reordering pass computes a new ORDER OF IDS and then has to turn that back
     * into objects, skipping any id the view no longer holds. Both reordering paths — the standalone
     * tool and the one inside grouped auto-layout — open-coded the same index-and-rebuild, which is
     * how they came to disagree about nothing and duplicate fourteen lines each.</p>
     */
    static List<IDiagramModelObject> childrenInOrder(IDiagramModelObject container,
            List<String> order) {
        Map<String, IDiagramModelObject> byId = new LinkedHashMap<>();
        for (IDiagramModelObject child : layoutChildrenOf(container)) {
            byId.put(child.getId(), child);
        }
        List<IDiagramModelObject> ordered = new ArrayList<>();
        for (String id : order) {
            IDiagramModelObject child = byId.get(id);
            if (child != null) {
                ordered.add(child);
            }
        }
        return ordered;
    }

    static List<IDiagramModelObject> layoutChildrenOf(IDiagramModelObject obj) {
        return childrenOf(obj).stream().filter(c -> !(c instanceof IDiagramModelNote)).toList();
    }

    static List<IDiagramModelObject> childrenOf(IDiagramModelObject obj) {
        if (obj instanceof IDiagramModelContainer container) {
            return container.getChildren();
        }
        return List.of();
    }

    /**
     * The containers this call arranges: the view's own targets, or exactly the ones the caller
     * named.
     *
     * <p>Lives beside {@link #isTarget} because the two must agree — and this is the one place they
     * deliberately <em>disagree</em>, per call. Omitting {@code groupIds} arranges
     * {@link #collect}'s answer and nothing else, so every view is arranged exactly as it always
     * was. That is the CANVAS half: these containers are positioned in the view's own coordinate
     * space, and a zone drawn inside a host does not belong in the list because its coordinates
     * are not measured from the canvas. Such zones reach the arrangement separately, through
     * {@link NestedContainerArrangement}, and a caller may name one here to restrict that half. Naming an id overrides the default with information the tool does not have: the caller
     * can see the canvas and this collection cannot, and it has already been told, by
     * {@link #describeSkipped}, which object was left standing and why. Read the disagreement as
     * an opt-in rather than as drift.
     *
     * <p><b>What may be named.</b> Any <em>direct child of this view</em> that is an
     * {@link IDiagramModelContainer} — every native group and every ArchiMate view object, whether
     * or not its concept is an {@link IGrouping} — and additionally any container
     * {@link #collectOutermost} finds, which is a zone drawn inside a host the predicate declines.
     * A container that is a member of another arrangement target is still refused: it moves with
     * the target that holds it, and positioning it separately would fight that arrangement. That admits the host a {@code Node} typing a
     * cloud region draws, which is the shape the default predicate declines and the shape this
     * opt-in exists for. It excludes a note, an image and a view reference, none of which is a
     * container: the response already tells a caller those are never arranged, and admitting them
     * here would make that published reason false. Moving one of those is {@code apply-positions}'
     * job. There is no children guard, because {@link #collect} has none either — a named empty
     * container is arranged and counted, exactly as an empty native group already is.
     *
     * <p><b>Every refusal carries a remedy the caller can execute, and none of them states
     * something untrue.</b> An id that resolves to nothing at all is the only genuinely unknown
     * one and the only {@code VIEW_OBJECT_NOT_FOUND}. Everything else is a valid id used wrongly,
     * and is told which: a model concept passed where the view object belongs, the view's own id,
     * an object drawn on another view, a connection, an object nested inside a container on this
     * view, or a top-level object that is not a container. They are separate answers because they
     * ask for different next calls — and because collapsing them produces false statements rather
     * than merely vague ones: a concept id belongs to no view, so telling it that it "belongs to
     * another view" would be a lie. Falling through to "not found" for any of them denies the
     * existence of an object the caller is holding a live id for, and sends it to re-read a
     * listing that will show the object.
     *
     * <p><b>A repeated id resolves once.</b> Naming the same container twice used to produce it
     * twice in this list, and every consumer counted the copies: the arrangement reserved a slot
     * for a container that does not exist, so the real one landed in the second slot rather than
     * the first; a command was built for it twice; {@code groupsPositioned} reported two containers
     * positioned on a view holding one; and the effective-geometry read-back listed the same view
     * object id twice. De-duplicating here rather than at each consumer keeps the one answer to
     * "which containers is this call arranging" in the one place that decides it. Order is
     * first-mention, so the arrangement a caller gets is still predictable from the list it sent.
     *
     * @throws ModelAccessException if an id is unknown, is not on this view, is not a direct child
     *     of it, or names a top-level object that is not a container
     */
    static List<IDiagramModelObject> resolveRequested(
            IArchimateModel model, String viewId,
            IDiagramModelContainer view, List<String> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return collect(view);
        }
        List<IDiagramModelObject> resolved = new ArrayList<>(requestedIds.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String gid : requestedIds) {
            IDiagramModelObject named = null;
            for (IDiagramModelObject child : view.getChildren()) {
                if (child.getId().equals(gid)) {
                    named = child;
                    break;
                }
            }
            if (named == null) {
                // A zone drawn inside a host the predicate declines is top-level in the sense
                // that matters — no zone contains it — so naming it is admissible even though it
                // is not the view's own child. A container inside a container it IS a member of
                // is not here, and keeps the refusal it always had.
                for (IDiagramModelObject outermost : collectOutermost(view)) {
                    if (outermost.getId().equals(gid)) {
                        named = outermost;
                        break;
                    }
                }
            }
            if (named == null) {
                throw notOnThisViewsTopLevel(model, viewId, gid);
            }
            if (!(named instanceof IDiagramModelContainer)) {
                throw new ModelAccessException(
                        gid + " is on view " + viewId + " but is not a container, so there is "
                                + "nothing for arrange-groups to arrange around it.",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "A note, an image and a view reference hold no children and are never "
                                + "arranged. Position it directly with apply-positions or "
                                + "update-view-object.",
                        null);
            }
            // An unknown id still has to be reported, so validity is checked before the
            // repeat is dropped: a caller that names a bad id twice learns it is bad.
            if (seen.add(gid)) {
                resolved.add(named);
            }
        }
        return resolved;
    }

    /**
     * Why an id that is not a direct child of this view was refused, told apart into the three
     * cases a caller can act on differently.
     *
     * <p>Each answer is read from the model rather than assumed, because they ask for opposite
     * next calls: re-read the id, pass the view object instead of the concept, call the other
     * view, or name the ancestor. A single "not found" collapses them into the one action that
     * cannot help.
     *
     * <p>The order is load-bearing. A view and a connection are both
     * {@link IDiagramModelComponent} but neither is an {@link IDiagramModelObject} — a view
     * through {@link IDiagramModelContainer}, a connection through {@code IConnectable} — so
     * testing for a view object first would answer both with "that is a model concept", which is
     * false for either.
     */
    private static ModelAccessException notOnThisViewsTopLevel(
            IArchimateModel model, String viewId, String gid) {
        Object named = ArchimateModelUtils.getObjectByID(model, gid);
        if (named == null) {
            return new ModelAccessException(
                    "Unknown id: " + gid + " names nothing in this model.",
                    ErrorCode.VIEW_OBJECT_NOT_FOUND,
                    null,
                    "Read the id from get-view-contents format=tree: a native group appears in "
                            + "the 'groups' list, an ArchiMate element among the elements.",
                    null);
        }
        // A view is an IDiagramModelComponent but never an IDiagramModelObject, so it has to be
        // told apart before the concept test — otherwise passing viewId here would be answered
        // "that is a model concept, pass the view-object id", which is false twice over.
        if (named instanceof IDiagramModel) {
            return new ModelAccessException(
                    gid + " is a view, not an object drawn on one.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "groupIds takes the ids of containers drawn on the view; the view itself is "
                            + "already named by viewId.",
                    null);
        }
        if (!(named instanceof IDiagramModelComponent drawn)) {
            return new ModelAccessException(
                    gid + " is a model concept, not an object drawn on view " + viewId + ".",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Pass the view-object id, not the model concept id. get-view-contents "
                            + "reports both; groupIds takes the view-object one.",
                    null);
        }
        IDiagramModel owner = drawn.getDiagramModel();
        if (owner == null) {
            return new ModelAccessException(
                    gid + " is not drawn on any view.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Name a container from get-view-contents for view " + viewId + ".",
                    null);
        }
        if (!viewId.equals(owner.getId())) {
            return new ModelAccessException(
                    gid + " is drawn on view " + owner.getId() + ", not on view " + viewId + ".",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "arrange-groups positions one view's own children. Name a container from "
                            + "get-view-contents for view " + viewId
                            + ", or arrange the other view instead.",
                    null);
        }
        // A connection reaches IDiagramModelComponent through IConnectable, so it is drawn on this
        // view and would otherwise fall through to the nested-container answer below.
        if (named instanceof IDiagramModelConnection) {
            return new ModelAccessException(
                    gid + " is a connection on view " + viewId + ", not a container.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "A connection follows the objects it joins rather than being positioned. "
                            + "Re-route it with auto-route-connections or update-view-connection.",
                    null);
        }
        if (!(named instanceof IDiagramModelObject)) {
            return new ModelAccessException(
                    gid + " is drawn on view " + viewId
                            + " but is not an object arrange-groups can position.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Name a top-level container from get-view-contents for view " + viewId + ".",
                    null);
        }
        // Reached by ANY non-direct-child on this view, a nested note or a childless nested
        // element included — so the remedy points layout-within-group at the ANCESTOR. Saying
        // "lay out its children" would be a false statement about an object that has none.
        return new ModelAccessException(
                "Group " + gid + " is not a top-level group in view " + viewId
                        + ". arrange-groups only positions top-level groups.",
                ErrorCode.INVALID_PARAMETER,
                null,
                "It is a member of a container arrange-groups treats as a zone, so it moves with "
                        + "that zone rather than being positioned itself. Name the outermost "
                        + "container it sits in, or use layout-within-group on that container to "
                        + "arrange its contents.",
                null);
    }

    /**
     * The rectangle each target actually holds, read from the model.
     *
     * <p>Must be called <em>after</em> the arrangement has been applied, never before: the point is
     * to report what the model ended up with rather than what the caller was told would happen.
     * Archi re-fits a container to its children on write, so a preserved width or height is a claim
     * that has to be observed rather than assumed — and a count of how many containers moved leaves
     * an agent that cannot see the canvas unable to say where any of them went.</p>
     */
    static List<MovedViewObjectDto> effectiveGeometryOf(List<IDiagramModelObject> targets) {
        List<MovedViewObjectDto> positioned = new ArrayList<>(targets.size());
        for (IDiagramModelObject target : targets) {
            IBounds b = target.getBounds();
            String name = (target.getName() != null && !target.getName().isBlank())
                    ? target.getName() : target.getId();
            positioned.add(new MovedViewObjectDto(target.getId(), name,
                    b.getX(), b.getY(), b.getWidth(), b.getHeight()));
        }
        return positioned;
    }

    /**
     * The populated top-level containers this collection excludes, described for the response.
     *
     * <p>Safe to call before the arrangement is applied — unlike {@link #effectiveGeometryOf},
     * nothing here is an outcome. Which containers are ineligible is decided by
     * {@link #isTarget} and is therefore known as soon as the view is read, so a queued or proposed
     * call can report it honestly while it still has nothing to say about geometry.</p>
     */
    static List<SkippedContainerDto> describeSkipped(IDiagramModelContainer view) {
        return describeSkipped(view, java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
    }

    /** As below, for a call that neither arranged nor declined any host's zones. */
    static List<SkippedContainerDto> describeSkipped(
            IDiagramModelContainer view, java.util.Set<String> positionedIds) {
        return describeSkipped(view, positionedIds, java.util.Set.of(), java.util.Set.of());
    }

    /**
     * As {@link #describeSkipped(IDiagramModelContainer)}, minus anything the same call moved.
     *
     * <p>The populations overlap in two ways, and the exclusion has to carry both. A populated
     * container is skipped when it is not an arrangement target; the standalone lane places a
     * non-target of a technology type wired to two or more arranged containers, and a {@code Node}
     * that holds children and connects two zones satisfies both. Independently of the lane, a
     * container the caller named in {@code groupIds} is arranged <em>because</em> it was named, so
     * it is a non-target this call positioned. Reporting either here would state that an object
     * this call repositioned was "left where it is" — a false statement about the tool's own
     * behaviour, and a double count against the view's child total.</p>
     *
     * <p>A host whose own nested zones this call arranged is still skipped — the host itself was
     * not moved — but its entry has to say so. Widening the target set without widening the
     * disclosure is what produces a silent shortfall: the caller reads "left where it is", plans
     * against the layout it last saw, and never learns that the boxes inside that host are
     * somewhere else now.</p>
     *
     * @param positionedIds every view-object id this call placed, arranged and lane-placed alike
     * @param hostsWithArrangedZones the ids of hosts whose nested zones this call arranged
     * @param hostsWithDeclinedZones the ids of hosts whose nested zones this call evaluated and
     *     declined for want of room — a different outcome from having none, and one the caller
     *     acts on differently
     */
    static List<SkippedContainerDto> describeSkipped(
            IDiagramModelContainer view, java.util.Set<String> positionedIds,
            java.util.Set<String> hostsWithArrangedZones,
            java.util.Set<String> hostsWithDeclinedZones) {
        List<SkippedContainerDto> described = new ArrayList<>();
        for (IDiagramModelObject obj : collectSkipped(view)) {
            if (positionedIds.contains(obj.getId())) {
                continue;
            }
            described.add(describe(obj, hostArm(obj.getId(),
                    hostsWithArrangedZones, hostsWithDeclinedZones)));
        }
        return described;
    }

    /**
     * Which of the three things happened to the containers inside this host.
     *
     * <p>The third arm is the one that is easy to miss. A host whose zones this call evaluated and
     * DECLINED — the arrangement would not fit — reported identically to a host that never held a
     * zone at all, so the caller could not tell "measured and rejected for space" from "nothing to
     * arrange here". That is the silent-shortfall shape the disclosure exists to close, and an
     * outcome the caller can act on: the decline is undone by enlarging the host, and nothing
     * else.</p>
     */
    private static String hostArm(String hostId, java.util.Set<String> arranged,
            java.util.Set<String> declined) {
        if (arranged.contains(hostId)) {
            return "holds children but is not a native group or a Grouping element, so the "
                    + "host itself is treated as a host rather than a zone and left where it is; "
                    + "the containers inside it WERE arranged, in its own coordinate space, and "
                    + "are reported in nestedContainersArranged; name this viewObjectId in "
                    + "groupIds to have arrange-groups position the host too";
        }
        if (declined.contains(hostId)) {
            return "holds children but is not a native group or a Grouping element, so it is "
                    + "treated as a host rather than a zone and left where it is; the containers "
                    + "inside it were NOT arranged either — the arrangement would not fit within "
                    + "this host, and arrange-groups never grows an ArchiMate element to make "
                    + "room, so it declined the host whole rather than moving some of its "
                    + "contents outside it; enlarge this host with update-view-object and call "
                    + "again";
        }
        return "holds children but is not a native group or a Grouping element, so it is "
                + "treated as a host rather than a zone and left where it is; name this "
                + "viewObjectId in groupIds to have arrange-groups position it too";
    }

    /**
     * Every direct child of the view that no bucket claimed, each with the reason it fell through.
     *
     * <p>Computed as a residual over {@code view.getChildren()} rather than as a fifth predicate,
     * so it cannot drift away from the population it is supposed to complete: whatever the other
     * three buckets stop claiming lands here automatically. That also makes the reconciliation true
     * by construction, which is why the pairing test asserts over ids rather than only counts.</p>
     *
     * <p>Nothing is filtered out of the denominator, a note and a view reference included. Excluding
     * a class of object before counting is the move that produced this gap one level up: naming the
     * containers that were skipped closed the container case and left the childless case looking
     * identical from the response. A note stacked on a container is the same layout defect as an
     * element there, so it is distinguished by its reason code rather than by omission — a caller
     * that does not care can filter on the code instead of trusting this tool to have filtered
     * first.</p>
     *
     * <p>Each reason leads with a stable code so the list can be triaged without reading prose, and
     * stays short: the corpus has a view with 97 unclaimed children, and a paragraph repeated 97
     * times is what would make the field unusable. The guidance that belongs beside it is in the
     * tool description, which is transmitted once rather than once per entry.</p>
     *
     * @param claimedIds the ids the arranged, lane-placed and skipped buckets already hold
     * @param laneRan whether the standalone lane ran at all on this call, which decides whether a
     *     lane-eligible element fell short of the connection threshold or never got the chance
     * @param arrangedContainerCount how many containers this call arranged, which decides whether
     *     an inter-container gap exists for the lane to place anything in at all
     */
    static List<SkippedContainerDto> describeUnhandled(
            IDiagramModelContainer view, java.util.Set<String> claimedIds, boolean laneRan,
            int arrangedContainerCount) {
        List<SkippedContainerDto> unhandled = new ArrayList<>();
        for (IDiagramModelObject child : view.getChildren()) {
            if (claimedIds.contains(child.getId())) {
                continue;
            }
            unhandled.add(describe(child, reasonFor(child, laneRan, arrangedContainerCount)));
        }
        return unhandled;
    }

    /**
     * Why one unclaimed child was not placed, in the order a caller can act on.
     *
     * <p>An object can miss for more than one condition at once, so the order matters. It is:</p>
     * <ol>
     *   <li><b>{@code not-requested}</b> — the object is a container this call would have arranged,
     *       and the caller's own parameter is what excluded it. This outranks everything because it
     *       is undone by changing the call rather than the view.</li>
     *   <li>the object's <b>type</b>, which splits in one place because the two answers are
     *       mutually exclusive: an ArchiMate object of a type the lane never places gets
     *       {@code type-not-lane-eligible}, and anything carrying no ArchiMate concept at all — a
     *       note, an image, a view reference — gets {@code not-an-archimate-element}. Telling a
     *       note its type is not lane-eligible would send the caller looking for a type fix that
     *       does not exist.</li>
     *   <li>only for an object the lane <em>could</em> have taken, in the order a caller can act
     *       on: whether the lane was offered this call's children at all
     *       ({@code lane-not-run}), then whether the arrangement left any gap between containers
     *       to place it in ({@code no-inter-container-gap}), and only then whether it reaches
     *       fewer than two of the arranged containers ({@code insufficient-connections}) —
     *       where a connection reaches a container both when it terminates on an element
     *       <em>inside</em> it and when it terminates on the container's <em>own box</em>, and
     *       two connections to one container count once. The absent gap outranks the connection
     *       count because no number of connections changes it: with one container arranged there
     *       is nothing to sit between, so reporting a near-miss would ask the caller for a
     *       connection that would place nothing.</li>
     * </ol>
     *
     * <p>{@code arrangedContainerCount} counts the CANVAS containers only, and it can be zero:
     * a call whose containers are all drawn inside a host arranges them in that host's space and
     * places nothing on the canvas, and it is no longer refused for it. So the {@code < 2} test
     * covers both a single container and none, and the sentence says which — "only one" on a view
     * that has one is a fact the caller can act on, and on a view that has none it would be a
     * false statement sending them to look for a container that is not there.</p>
     *
     * <p>Each code comes from {@link SkippedContainerDto#ARRANGE_GROUPS_UNHANDLED_REASON_CODES},
     * which is also what the guard over the served tool description iterates. Spelling one here
     * as a literal would let a new code ship documented on no surface.</p>
     */
    private static String reasonFor(IDiagramModelObject child, boolean laneRan,
            int arrangedContainerCount) {
        if (isTarget(child)) {
            return SkippedContainerDto.REASON_NOT_REQUESTED
                    + ": a container you did not name in groupIds";
        }
        if (!ArrangeGroupsStandaloneLane.isLaneEligibleType(child)) {
            return child instanceof IDiagramModelArchimateObject
                    ? SkippedContainerDto.REASON_TYPE_NOT_LANE_ELIGIBLE
                            + ": type is not placed by the standalone lane"
                    : SkippedContainerDto.REASON_NOT_AN_ARCHIMATE_ELEMENT
                            + ": a note, image or view reference is never arranged";
        }
        if (!laneRan) {
            return SkippedContainerDto.REASON_LANE_NOT_RUN
                    + ": runs only for arrangement=topology on a row or column axis";
        }
        if (arrangedContainerCount < 2) {
            return SkippedContainerDto.REASON_NO_INTER_CONTAINER_GAP
                    // Kept inside the per-entry length budget: the canvas-versus-host nuance is
                    // in the tool description, transmitted once, not once per unhandled object.
                    + (arrangedContainerCount == 1
                            ? ": only one container was arranged, so there is no gap to place it in"
                            : ": no container was arranged, so there is no gap to place it in");
        }
        return SkippedContainerDto.REASON_INSUFFICIENT_CONNECTIONS
                + ": reaches fewer than 2 arranged containers, box or element inside";
    }

    /** One view object described for the response: identity, type when it has one, and a reason. */
    static SkippedContainerDto describe(IDiagramModelObject obj, String reason) {
        String name = (obj.getName() != null && !obj.getName().isBlank())
                ? obj.getName() : obj.getId();
        String elementType = obj instanceof IDiagramModelArchimateObject archimateObject
                && archimateObject.getArchimateConcept() != null
                ? archimateObject.getArchimateConcept().eClass().getName() : null;
        return new SkippedContainerDto(obj.getId(), name, elementType, reason);
    }

    /**
     * The refusal a tool owes a view whose containers are all drawn inside a host.
     *
     * <p>Three tools position the view's OWN containers and have nothing to position on such a
     * view. Each used to say so in words that were false about it — "View has no groups",
     * "requires a view with groups … use mode='auto' for flat views" — which is a confident wrong
     * statement about a canvas holding populated zones, and one an agent that cannot see the
     * canvas has no way to contradict. The count is read rather than assumed, and the remedy names
     * the tool that DOES position these containers, so the refusal leads somewhere.</p>
     *
     * <p>Shared so the three cannot drift into three different accounts of one view. Returns null
     * when the view genuinely holds no container at any depth — the caller keeps whatever it
     * already said for that case, which is the case its old wording was actually about.</p>
     *
     * @param toolClause what this tool does, completing "… and has none to reorder here"
     */
    static ModelAccessException containersAreAllNested(
            String viewId, IDiagramModelContainer view, String toolClause) {
        int nested = nestedContainerCount(view);
        if (nested == 0) {
            return null;
        }
        return new ModelAccessException(
                "View " + viewId + " has " + nested + " top-level container"
                        + (nested == 1 ? "" : "s")
                        + ", but every one is drawn inside a host rather than on the view itself. "
                        + toolClause,
                ErrorCode.INVALID_PARAMETER,
                null,
                "arrange-groups positions those containers inside their host. To arrange the "
                        + "elements within one of them, call layout-within-group on that container.",
                null);
    }

    /**
     * The refusal a spacing tool owes this view when the step it is about to gate positions none of
     * the containers the view's corridor runs between, or null when that step has work here.
     *
     * <p>Shared by the three tools that publish it, so one view cannot be given three accounts of
     * itself. The remedy it names is executable: {@code arrange-groups} arranges nested zones in
     * their host's own space.</p>
     *
     * @param containersTheStepNeeds what the calling step requires before it has work — pass
     *                               {@link #CONTAINERS_AN_ELEMENT_STEP_NEEDS} or
     *                               {@link #CONTAINERS_A_GROUP_STEP_NEEDS}, and pass the SAME one
     *                               to {@link #positioningFrame}
     * @param stepClause             what this tool's step does, completing "… and positions none
     *                               of them"
     */
    static String containersNotPositionedBy(
            String viewId, IDiagramModelContainer view,
            int containersTheStepNeeds, String stepClause) {
        if (!positioningStepIsShortOfOwnContainers(view, containersTheStepNeeds)) {
            return null;
        }
        // The DECISION above is taken from the step's own collection; the counts below describe
        // the view's TOPOLOGY. They are different questions and the sentence needs the second:
        // an empty box on the canvas is invisible to collectPopulated, so counting that way told
        // a caller every container was drawn inside a host while one sat unhosted on the canvas.
        List<IDiagramModelObject> outermost = collectOutermost(view);
        int nested = nestedCountIn(view, outermost);
        return SpacingEntryGuardTermination.containersNotPositionedByThisTool(
                viewId, outermost.size() - nested, nested, stepClause);
    }

    /**
     * What a step that spaces elements INSIDE a container needs: one of the view's own to work in.
     *
     * <p>Named rather than written as a literal because two questions read it — whether the step
     * has work ({@link #positioningStepIsShortOfOwnContainers}) and which frame it may measure
     * ({@link #positioningFrame}) — and a literal let those two disagree about one view.</p>
     */
    static final int CONTAINERS_AN_ELEMENT_STEP_NEEDS = 1;

    /** What a step that widens a corridor BETWEEN containers needs: two of the view's own. */
    static final int CONTAINERS_A_GROUP_STEP_NEEDS = 2;

    /**
     * The frame a step that spaces elements INSIDE containers may measure, and the refusal it owes
     * when it has none to work in. Paired so a caller cannot ask the two questions with different
     * thresholds — which is exactly how a gate came to measure a frame its own step could not act
     * in, while the refusal beside it correctly declined to fire.
     */
    static List<IDiagramModelObject> elementStepFrame(
            IDiagramModelContainer view, List<IDiagramModelObject> containers) {
        return positioningFrame(view, containers, CONTAINERS_AN_ELEMENT_STEP_NEEDS);
    }

    /** The refusal an element-spacing step owes this view, or null when it has work here. */
    static String elementStepCannotPosition(
            String viewId, IDiagramModelContainer view, String stepClause) {
        return containersNotPositionedBy(
                viewId, view, CONTAINERS_AN_ELEMENT_STEP_NEEDS, stepClause);
    }

    /** As {@link #elementStepFrame}, for a step that widens a corridor BETWEEN containers. */
    static List<IDiagramModelObject> groupStepFrame(
            IDiagramModelContainer view, List<IDiagramModelObject> containers) {
        return positioningFrame(view, containers, CONTAINERS_A_GROUP_STEP_NEEDS);
    }

    /** The refusal a corridor-widening step owes this view, or null when it has work here. */
    static String groupStepCannotPosition(
            String viewId, IDiagramModelContainer view, String stepClause) {
        return containersNotPositionedBy(
                viewId, view, CONTAINERS_A_GROUP_STEP_NEEDS, stepClause);
    }

    /**
     * How many of the view's outermost containers are drawn inside something rather than on the
     * view itself.
     *
     * <p>Counted only when a container really is inside something. Callers gate on different
     * populations — one on the view's own containers, one on the POPULATED ones — so a view whose
     * single container is an EMPTY box on the canvas reaches the second caller's branch with
     * nothing nested at all. Measuring the whole outermost walk there would tell that caller its
     * canvas box is "drawn inside a host", which is a false statement about container topology
     * produced by the code that exists to stop exactly those. Deciding it here rather than at each
     * caller keeps the sentence and the condition that licenses it in one place.</p>
     */
    static int nestedContainerCount(IDiagramModelContainer view) {
        return nestedCountIn(view, collectOutermost(view));
    }

    /** As {@link #nestedContainerCount}, over a walk the caller already has in hand. */
    private static int nestedCountIn(
            IDiagramModelContainer view, List<IDiagramModelObject> outermost) {
        int nested = 0;
        for (IDiagramModelObject container : outermost) {
            if (container.eContainer() != view) {
                nested++;
            }
        }
        return nested;
    }

    /**
     * True when a step that positions the view's OWN containers has too few of them to do its
     * work, while the view does hold containers drawn inside a host.
     *
     * <p>Decided from the same two facts the positioning step and the shared refusal already
     * decide from, and from nothing else: {@link #collectPopulated} — the very collection
     * {@code adjust-view-spacing}'s entry guard reads — and {@link #nestedContainerCount}. A gate
     * that answered this from {@link #collectOutermost} would be reading a different container set
     * from the step it gates, which is the divergence this predicate exists to close.</p>
     *
     * <p>{@code containersTheStepNeeds} is what the CALLER's step requires, not a property of the
     * view: widening a corridor needs two containers to widen between, while spacing the elements
     * inside a container needs only one. Passing the wrong number here does not produce a wrong
     * boolean about the view — it produces a right boolean about the wrong step.</p>
     *
     * <p>The second half is not optional. A view whose only container is an empty box on the
     * canvas also has an empty {@code collectPopulated}; deciding on the first half alone would
     * tell that caller its canvas box is drawn inside a host.</p>
     */
    static boolean positioningStepIsShortOfOwnContainers(
            IDiagramModelContainer view, int containersTheStepNeeds) {
        return collectPopulated(view).size() < containersTheStepNeeds
                && nestedContainerCount(view) > 0;
    }

    /**
     * The frame a spacing gate may measure in: the view's own containers whenever the step it
     * gates has a corridor of its own to widen there, and otherwise the largest single frame.
     *
     * <p>{@link #largestSharedFrame} answers "which frame holds the most containers", which is the
     * right question for deciding whether the view has a corridor AT ALL and the wrong one for
     * deciding which corridor THIS tool will widen. On a view holding two populated containers on
     * the canvas and three zones inside a host it returns the host's three, and a gate reading it
     * publishes a host-relative gap beside a delta the step then applies to the canvas corridor.
     * Both numbers are individually defensible and they describe different corridors.</p>
     *
     * <p>Below what the step needs the largest frame is returned unchanged and the caller's
     * structural branch declines. Narrowing to a frame too small to measure in would hand
     * {@link #sharedFrameSpacing} too few containers, and its placeholder would be published in a
     * field that reads as a measurement.</p>
     *
     * <p>{@code containersTheStepNeeds} MUST be the number the caller also passes to
     * {@link #positioningStepIsShortOfOwnContainers} — pass
     * {@link #CONTAINERS_AN_ELEMENT_STEP_NEEDS} or {@link #CONTAINERS_A_GROUP_STEP_NEEDS} rather
     * than a literal. A frame that hardcoded one step's threshold answered the other step's
     * question: an arm needing ONE own container did not decline, and then measured the host's
     * frame anyway — the very divergence this pair of helpers exists to close, reproduced one
     * field along.</p>
     */
    static List<IDiagramModelObject> positioningFrame(
            IDiagramModelContainer view, List<IDiagramModelObject> containers,
            int containersTheStepNeeds) {
        if (collectPopulated(view).size() < containersTheStepNeeds) {
            return largestSharedFrame(view, containers);
        }
        List<IDiagramModelObject> ownFrame = new ArrayList<>();
        for (IDiagramModelObject container : containers) {
            if (container.eContainer() == view) {
                ownFrame.add(container);
            }
        }
        return ownFrame;
    }

    /**
     * Counts connections on the view whose source and target visual objects resolve to DIFFERENT
     * top-level containers — a native view group and an ArchiMate {@code Grouping} element alike,
     * as {@link #isTarget} defines them. One-side-grouped pairings (one endpoint in a container,
     * the other not) are NOT counted: the connected/unconnected distinction the spacing heuristics
     * draw from this number is about between-container routing-corridor demand, which requires two
     * containers. Reuses the same connection enumeration as
     * {@link AssessmentCollector#collectAllConnections} for source-of-truth symmetry with
     * {@code assessLayout}'s {@code connectionCount}.
     *
     * <p>Lives here rather than beside its two callers because the count is defined by
     * {@link #isTarget} and by nothing else. It previously carried its own {@code instanceof}
     * test admitting only the native group, so a view whose zones were {@code Grouping} elements
     * was counted at zero however many connections crossed between them — and zero is not a
     * report but a branch selector, which sent such a view to the unconnected column of
     * {@link GroupSpacingHeuristic} and gave its corridors the narrowest target in the table.</p>
     *
     * <p>Pinned by {@code InterGroupConnectionCountTest}, which also cross-checks the answer
     * against the route {@code arrangeGroups} resolves its own default spacing through. That route
     * sums differently and never tests a type, but it seeds from {@link #collect} and so shares
     * this class's definition of a container — it cross-checks the summation, not the predicate.</p>
     */
    static int countInterGroupConnections(
            IArchimateDiagramModel diagramModel) {
        return countInterGroupConnections(diagramModel, collectOutermost(diagramModel));
    }

    /**
     * As {@link #countInterGroupConnections(IArchimateDiagramModel)}, over exactly the containers
     * given rather than over the whole view's.
     *
     * <p>{@code arrange-groups} needs this because a caller can restrict the call to a named
     * subset, and a default spacing resolved from the whole view would answer about containers the
     * call is not arranging. It is the same count over a narrower partition, not a second count:
     * both forms sum {@link #interContainerWeights}, which is the only place a connection is ever
     * attributed to a pair of containers.</p>
     */
    static int countInterGroupConnections(
            IArchimateDiagramModel diagramModel, List<IDiagramModelObject> containers) {
        int total = 0;
        for (Map<String, Integer> perTarget
                : interContainerWeights(diagramModel, containers).values()) {
            for (int weight : perTarget.values()) {
                total += weight;
            }
        }
        return total;
    }

    /**
     * How many connections run between each ordered pair of the given containers.
     *
     * <p>The one place a connection is attributed to a pair of containers, and therefore the one
     * definition of what "between two containers" means. Both the scalar count the spacing tools
     * publish and the adjacency matrix {@code arrange-groups} orders its topology from are read
     * off this map, so the number a response reports and the order it produces cannot come from
     * two different pictures of the same view — which is what they used to do: one route resolved
     * each endpoint to its outermost admitted ancestor, the other pre-flattened every descendant
     * of the view's own children into a lookup table, and the two disagreed the moment a container
     * sat under one the predicate declines.</p>
     *
     * <p>An endpoint resolving to no listed container is skipped, not counted against a pseudo
     * container. That excludes a connection terminating on a top-level object, and equally one
     * terminating on a listed container's OWN box: a container is not inside itself, so a
     * box-to-box connection is a connection to a zone rather than one running between two of
     * them. A container nested inside a listed one does resolve — to the listed one — so a
     * connection reaching into it crosses whatever boundary the listed container draws.</p>
     *
     * <p>Weights are directional, keyed source-container then target-container, because the
     * topology orderer reads them that way; the scalar count sums every entry and is therefore
     * direction-blind.</p>
     */
    static Map<String, Map<String, Integer>> interContainerWeights(
            IArchimateDiagramModel diagramModel, List<IDiagramModelObject> containers) {
        java.util.Set<String> containerIds = new java.util.HashSet<>();
        for (IDiagramModelObject container : containers) {
            containerIds.add(container.getId());
        }
        Map<String, Map<String, Integer>> weights = new LinkedHashMap<>();
        for (IDiagramModelConnection conn :
                AssessmentCollector.collectAllConnections(diagramModel)) {
            if (!(conn.getSource() instanceof IDiagramModelObject source)
                    || !(conn.getTarget() instanceof IDiagramModelObject target)) {
                continue;
            }
            IDiagramModelObject sourceContainer = containerOf(source, containerIds);
            IDiagramModelObject targetContainer = containerOf(target, containerIds);
            if (sourceContainer == null || targetContainer == null
                    || sourceContainer == targetContainer) {
                continue;
            }
            weights.computeIfAbsent(sourceContainer.getId(), k -> new LinkedHashMap<>())
                    .merge(targetContainer.getId(), 1, Integer::sum);
        }
        return weights;
    }

    /**
     * The top-level container the given visual object sits inside, or null if it sits inside none.
     *
     * <p>Walks the {@code eContainer()} chain upward and returns the OUTERMOST ancestor
     * {@link #isTarget} admits. For every shape the arrangement family produces that ancestor is a
     * direct child of the view, so a container nested inside another resolves to the outer one
     * rather than to itself.</p>
     *
     * <p><b>It is the outermost MATCHING ancestor, not necessarily a direct child of the view.</b>
     * A target nested inside a NON-target host — a {@code Grouping} drawn inside a {@code Node}
     * typing a cloud region — has no target ancestor above it, so the walk returns that nested
     * container. That container is top-level, and {@link #collectOutermost} is this same question
     * asked downward: for every object on a view, this method returns either null or a member of
     * that list. Both run {@link #outermostAncestorMatching}, so there is one traversal rather
     * than two that could answer differently — which is what they used to do, one route seeding
     * from {@link #collect} and resolving nothing for the very object this one resolves.</p>
     *
     * <p><b>Seeing such a container is not the same as positioning it.</b> Its x/y are stored
     * relative to its host, so it cannot join a canvas arrangement;
     * {@code NestedContainerArrangement} lays each host's zones out in that host's own space.</p>
     *
     * <p>Null for an object drawn at the view's own top level, containers included: the walk
     * starts at {@code eContainer()}, which for such an object is the diagram model. That is
     * load-bearing rather than incidental — a connection terminating on a top-level element
     * crosses no boundary, and a connection terminating on a container box is a connection to the
     * zone rather than between two of them. Both are excluded by the same null, and the
     * {@code arrangeGroups} route excludes both for the same reason: it maps a container's
     * children and never the container.</p>
     */
    static IDiagramModelObject topLevelGroupOf(IDiagramModelObject obj) {
        return outermostAncestorMatching(obj, TopLevelGroupTargets::isTarget);
    }

    /**
     * The outermost of the given containers that holds {@code obj}, or null if none does.
     *
     * <p>The same walk {@link #topLevelGroupOf} runs, admitting membership of a caller-chosen set
     * instead of {@link #isTarget}. That is what lets a call restricted to named containers
     * attribute connections to exactly those, without a second traversal that could answer
     * differently: a flattened descendant-to-container lookup table built by pre-walking each
     * container's children is term-for-term this walk run from the other end, and keeping only the
     * walk means there is no second one left to drift.</p>
     *
     * <p>An object is never its own container: the walk starts at {@code eContainer()}, so naming
     * a container and asking where its own box sits answers null.</p>
     */
    static IDiagramModelObject containerOf(
            IDiagramModelObject obj, java.util.Set<String> containerIds) {
        return outermostAncestorMatching(obj, candidate -> containerIds.contains(candidate.getId()));
    }

    /**
     * Walks the {@code eContainer()} chain from {@code obj} outward and returns the LAST ancestor
     * {@code admits} accepted before the diagram model — the outermost one.
     *
     * <p>One traversal, two admission rules, because the traversal is the part that has to be
     * identical: which ancestor counts as "the container this object is in" is a single question,
     * and asking it twice in two places is how a counting route and a collecting route came to
     * describe different views.</p>
     */
    private static IDiagramModelObject outermostAncestorMatching(
            IDiagramModelObject obj, java.util.function.Predicate<IDiagramModelObject> admits) {
        EObject current = obj.eContainer();
        IDiagramModelObject outermostAncestorContainer = null;
        while (current != null) {
            if (current instanceof IDiagramModelObject candidate && admits.test(candidate)) {
                outermostAncestorContainer = candidate;
            }
            if (current instanceof IArchimateDiagramModel) {
                // The outermost admitted ancestor seen along the walk; null if none was.
                return outermostAncestorContainer;
            }
            current = current.eContainer();
        }
        return null;
    }
}
