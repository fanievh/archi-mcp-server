package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;

/**
 * Recursive (descendant) layout for nested view-object containers.
 *
 * <p>Where {@code layout-within-group} without recursion arranges only the direct
 * children of one container, this collaborator arranges an <em>entire nesting
 * hierarchy</em> in a single pass: functions-inside-components-inside-domains, or
 * region &rarr; availability-zone &rarr; node &rarr; artifact. It walks the tree
 * <strong>post-order (bottom-up)</strong> so a container is sized only after its
 * children have been arranged and sized, then the parent arranges those
 * now-fitted containers as fixed boxes.</p>
 *
 * <p>Pure geometry (row/column/grid positioning, fit-to-children sizing, grid
 * column counts) is delegated to {@link GroupLayoutCalculator}; this class owns
 * the EMF traversal and command construction only. Package-visible — only
 * {@link ArchiModelAccessorImpl} uses it, following the collaborator-extraction
 * pattern that keeps the accessor facade from growing.</p>
 *
 * <p>Coordinates are RELATIVE to each container's own origin at every level, so
 * the recursion re-applies the same relative computation per container with no
 * absolute-coordinate conversion.</p>
 */
final class NestedLayoutOperations {

    private static final Logger logger = LoggerFactory.getLogger(NestedLayoutOperations.class);

    private NestedLayoutOperations() {}

    /**
     * The single title-band keep-out for a nested child, in coordinates relative to its immediate
     * parent. No child of any container kind may be placed above this line by a pass that is free
     * to choose where the child goes.
     *
     * <p>Larger than {@link GroupLayoutCalculator#GROUP_LABEL_HEIGHT} because container names
     * frequently wrap to two lines; a smaller inset lets the first nested child collide with the
     * parent label (the parent-label-obscured failure seen on dense nested application-landscape
     * views).</p>
     *
     * <p>WHY THIS VALUE, AND WHY ONE VALUE. The layout code answers "how deep is a container's
     * title" in several places, but those are <em>start offsets</em> chosen for appearance
     * ({@link GroupLayoutCalculator#GROUP_LABEL_HEIGHT} for a native group, the flat-view element
     * inset). A keep-out is a different question with a different failure mode, so it takes the
     * safe number rather than the pretty one. {@code LayoutQualityAssessor} judges a parent's band
     * as one label row, doubled to two when the title is measured wide enough to wrap — at most
     * 40 px — and a group's label width is never measured at all, so that judgement is known to be
     * an under-estimate rather than a ceiling. A keep-out below what the detector judges against
     * would still produce the critical finding it exists to prevent, so this floor sits above the
     * detector's deepest band and is a fixed number, not a re-derived one: the wrapped depth of an
     * unmeasured title is not computable, and guessing it would be a measurement's clothing on a
     * guess.</p>
     *
     * <p>The asymmetry is deliberate. Over-reserving costs pixels at the top of a container;
     * under-reserving costs a critical rating verdict. Only one of those is recoverable by the
     * caller.</p>
     */
    static final int CONTAINER_TITLE_BAND_HEIGHT = 46;

    /** Maximum nesting depth the recursion descends before stopping. */
    static final int MAX_RECURSIVE_LAYOUT_DEPTH = 10;

    /**
     * The member commands of {@code compound} as a fresh mutable list.
     *
     * <p>GEF's {@code getCommands()} is a raw {@code List}, so every caller that wants to inspect a
     * half-built compound — to ask {@link #findPendingDimensions(List, IDiagramModelObject)} what a
     * pass has already decided about an object — has to copy and cast it element by element. That
     * loop was written out at each site; having one of them drift from the others is a silent way
     * for two passes of the same call to disagree about what is already queued.</p>
     */
    static List<Command> commandsOf(CompoundCommand compound) {
        List<Command> commands = new ArrayList<>();
        for (Object cmd : compound.getCommands()) {
            commands.add((Command) cmd);
        }
        return commands;
    }

    /**
     * Appends every member command of {@code source} to {@code target}, flattening one level.
     *
     * <p>The multi-pass tools merge several independently-built compounds into the single compound
     * they finally dispatch, so that the whole call is one undo step. Adding {@code source} itself
     * would nest it and change that undo shape, so each member is appended individually — eleven
     * times over across the multi-pass tools, before this.</p>
     */
    static void appendAll(CompoundCommand target, CompoundCommand source) {
        for (Object cmd : source.getCommands()) {
            target.add((Command) cmd);
        }
    }

    /**
     * Result of a recursive layout pass: the flat command list to apply plus
     * reporting counters.
     */
    record NestedLayoutResult(
            List<Command> commands,
            int elementsRepositioned,
            int nestedContainersArranged,
            int maxDepthReached,
            boolean depthCapHit,
            int rootFittedWidth,
            int rootFittedHeight,
            Map<String, Command> fittedContainers,
            Map<String, Command> resizedLeaves) {}

    /** Mutable accumulator threaded through the recursive walk. */
    private static final class Accumulator {
        final List<Command> commands = new ArrayList<>();
        /**
         * Each descendant container the walk re-fitted, keyed by view-object id, holding the
         * command that carries its landed rectangle. {@code nestedContainersArranged} counts the
         * same set; this says what each one became. A caller that cannot see the canvas needs the
         * rectangle — a count is an index into information it does not have.
         */
        final Map<String, Command> fittedContainers = new LinkedHashMap<>();
        /**
         * Every child the walk did <em>not</em> descend into whose placement changes its size,
         * keyed by view-object id, holding the command that carries the rectangle it lands at.
         *
         * <p>The walk writes a full rectangle to every child, and a grid cell takes its column's
         * width — so a leaf sharing a column with a container that fitted wide is stretched to that
         * width without anything in the request naming it. Measured on a real conversion: six leaves
         * went from 120px to as much as 2230px while the response said only that 69 elements had
         * been "repositioned". A count of moves that silently also covers resizes reports neither.</p>
         *
         * <p>Disjoint from {@link #fittedContainers} by construction — the two are the arms of one
         * if/else on whether the walk recursed into the child — and populated by observation: a
         * child re-written to the size it already had is not recorded. The membership rule is "not
         * recursed into", not "is a leaf", so a container left in place at the depth cap and then
         * stretched by its column is reported here rather than nowhere.</p>
         */
        final Map<String, Command> resizedLeaves = new LinkedHashMap<>();
        int elementsRepositioned = 0;
        int nestedContainersArranged = 0;
        int maxDepthReached = 0;
        boolean depthCapHit = false;
    }

    /**
     * Builds the commands that recursively arrange every descendant of
     * {@code rootContainer}, bottom-up. The root's own children are positioned;
     * the root itself is resized only when {@code rootAutoResize} is true (inner
     * containers are always resized to fit, otherwise their children overflow).
     *
     * @param rootContainer       the container whose descendants are arranged
     * @param rootContainerObject the same object viewed as a positionable object
     *                            (for the root's own bounds/resize)
     * @param arrangement         "row", "column", or "grid" (applied at every level)
     * @param spacing             gap between siblings in pixels (every level)
     * @param padding             inset from container edges in pixels (every level)
     * @param elementWidth        explicit leaf width override, or null to preserve/auto
     * @param elementHeight       explicit leaf height override, or null to preserve
     * @param autoWidth           size leaves to their label text (ignored when
     *                            elementWidth is set)
     * @param columns             explicit grid column count, or null to derive from
     *                            element count (recursive grid never reads container
     *                            width, which is being recomputed)
     * @param rootAutoResize      resize the root container to fit its children
     * @return the command list plus reporting counters
     */
    static NestedLayoutResult buildRecursiveLayoutCommands(
            IDiagramModelContainer rootContainer,
            IDiagramModelObject rootContainerObject,
            String arrangement, int spacing, int padding,
            Integer elementWidth, Integer elementHeight, boolean autoWidth,
            Integer columns, boolean rootAutoResize) {
        return buildRecursiveLayoutCommands(rootContainer, rootContainerObject, arrangement,
                spacing, padding, elementWidth, elementHeight, autoWidth, columns, rootAutoResize,
                null);
    }

    /**
     * As {@link #buildRecursiveLayoutCommands(IDiagramModelContainer, IDiagramModelObject, String,
     * int, int, Integer, Integer, boolean, Integer, boolean)}, measuring every container it re-fits
     * and every leaf it places against the geometry an open batch has already queued.
     *
     * <p>This walk emits one absolute rectangle per object, all computed from a pre-batch
     * {@code getBounds()}. Without the map it silently discards a size an earlier operation of the
     * same batch asked for — the same defect the single-level arm of the same tool was fixed for,
     * reachable simply by passing {@code recursiveChildren}. Measured before: a container queued at
     * 900x700 committed at 160x143, and an enclosing group queued at 900x700 committed at
     * 180x187.</p>
     *
     * <p>{@code sameBatchBounds} null (every pre-existing caller, and the grouped auto-layout pass,
     * which re-lays out the whole view by contract) makes every lookup miss, so the walk is exactly
     * what it was.</p>
     */
    static NestedLayoutResult buildRecursiveLayoutCommands(
            IDiagramModelContainer rootContainer,
            IDiagramModelObject rootContainerObject,
            String arrangement, int spacing, int padding,
            Integer elementWidth, Integer elementHeight, boolean autoWidth,
            Integer columns, boolean rootAutoResize, Map<String, int[]> sameBatchBounds) {
        return build(rootContainer, rootContainerObject, arrangement, null, spacing, padding,
                elementWidth, elementHeight, autoWidth, columns, rootAutoResize, sameBatchBounds);
    }

    /**
     * The grouped auto-layout entry point: arrange a top-level group's whole subtree, choosing the
     * arrangement <em>per level</em> from that level's own child count and the view's flow
     * direction.
     *
     * <p>This is the one difference from {@code layout-within-group}'s recursive option, and it
     * matters. That tool takes an explicit arrangement from its caller and documents that the same
     * one applies at every depth, which is a contract the caller chose. The grouped pipeline has no
     * such caller: it derives the arrangement itself, from the top-level group's child count. Reused
     * unchanged at every depth, that derivation is applied to counts it was never computed for — a
     * container holding fifteen children would be laid out as a single row because its parent
     * happened to hold two, which is precisely the tall/narrow shape
     * {@link GroupLayoutCalculator#chooseIntraGroupArrangement} exists to avoid. Deriving per level
     * asks the question with the right input at each level.</p>
     *
     * <p>The root is whatever the caller is arranging — a native group or an ArchiMate
     * {@code Grouping} element. This walk descends, and everything below it already reads only
     * {@code getChildren()} and {@code getBounds()}: {@link #isRecursableContainer} recurses into
     * both kinds and {@link #labelHeightFor} already reserves each kind's own title band. The cast
     * is safe by construction — every caller sources its root from
     * {@link TopLevelGroupTargets#collect}, which admits only types that are also
     * {@link IDiagramModelContainer}.</p>
     *
     * <p>Descending is deliberately not the same question as ascending. The decision recorded at
     * {@code layoutWithinGroup}'s ancestor pass — that an ArchiMate-element container is an honest
     * no-op rather than something whose ancestors get walked — is about the <em>upward</em> walk
     * and is left standing; see {@link #resizeAncestorGroups}, which stays typed to native groups.</p>
     *
     * <p><b>Widening and disclosing are also not the same question, and the two are answered
     * oppositely on purpose.</b> The inter-container arrangement <em>widened</em> what counts as a
     * container: any direct child that is a container is arranged, whatever its element type,
     * because the caller <em>names</em> the object and refusing something the caller pointed at
     * leaves them nowhere to go. The upward walk does <em>not</em> widen, because it is implicit —
     * it traverses objects the caller never named, and growing an ArchiMate element as a side
     * effect of laying out its child is a semantic act rather than a geometric one. What the caller
     * gets there instead is a reason and a remedy, reported as {@code ancestorPropagation}. Both
     * halves are deliberate; a reader who meets only one of them will read the other as an
     * oversight and "fix" it.</p>
     *
     * @param direction the inter-group flow direction, which biases the arrangement choice
     */
    static NestedLayoutResult buildGroupedLayoutCommands(
            IDiagramModelObject rootTarget, int spacing, int padding, String direction) {
        return build((IDiagramModelContainer) rootTarget, rootTarget, null, direction,
                spacing, padding, null, null, true, null, true);
    }

    private static NestedLayoutResult build(
            IDiagramModelContainer rootContainer,
            IDiagramModelObject rootContainerObject,
            String arrangement, String direction, int spacing, int padding,
            Integer elementWidth, Integer elementHeight, boolean autoWidth,
            Integer columns, boolean rootAutoResize) {
        return build(rootContainer, rootContainerObject, arrangement, direction, spacing, padding,
                elementWidth, elementHeight, autoWidth, columns, rootAutoResize, null);
    }

    private static NestedLayoutResult build(
            IDiagramModelContainer rootContainer,
            IDiagramModelObject rootContainerObject,
            String arrangement, String direction, int spacing, int padding,
            Integer elementWidth, Integer elementHeight, boolean autoWidth,
            Integer columns, boolean rootAutoResize, Map<String, int[]> sameBatchBounds) {

        Accumulator acc = new Accumulator();

        int[] rootFitted = layoutChildrenOf(rootContainer, rootContainerObject, 0,
                arrangement, direction, spacing, padding, elementWidth, elementHeight,
                autoWidth, columns, acc, sameBatchBounds);

        // The root has no parent to place it, so it resizes itself only on request.
        if (rootAutoResize) {
            int[] rect = AnchorResolver.refitRect(rootContainerObject, rootFitted, sameBatchBounds);
            acc.commands.add(new UpdateViewObjectCommand(rootContainerObject,
                    rect[0], rect[1], rect[2], rect[3]));
            rootFitted = new int[]{ rect[2], rect[3] };
        }

        return new NestedLayoutResult(acc.commands, acc.elementsRepositioned,
                acc.nestedContainersArranged, acc.maxDepthReached, acc.depthCapHit,
                rootFitted[0], rootFitted[1], acc.fittedContainers, acc.resizedLeaves);
    }

    /**
     * Arranges the direct children of one container, recursing into child
     * containers first (post-order). Emits one reposition/resize command per
     * child — including child containers, which are placed at the fitted size
     * returned by their own recursion. Returns the fit-to-children size this
     * container requires.
     */
    private static int[] layoutChildrenOf(
            IDiagramModelContainer container, IDiagramModelObject containerObject,
            int depth, String arrangement, String direction, int spacing, int padding,
            Integer elementWidth, Integer elementHeight, boolean autoWidth,
            Integer columns, Accumulator acc, Map<String, int[]> sameBatchBounds) {

        List<IDiagramModelObject> children = eligibleChildren(container);
        if (children.isEmpty()) {
            // Nothing to arrange, so nothing has been measured either. Returning a fit-to-children
            // size here would be a fit to no children — padding squared — and the caller would
            // shrink a container around whatever (notes, say) is still inside it.
            IBounds existing = containerObject.getBounds();
            return new int[]{existing.getWidth(), existing.getHeight()};
        }
        // Null arrangement means "derive it here", so each level answers for its own child count.
        String levelArrangement = (arrangement != null) ? arrangement
                : GroupLayoutCalculator.chooseIntraGroupArrangement(children.size(), direction);

        // Resolve each child's size. Child containers are recursed into first and
        // contribute their fitted size; leaves contribute their resolved size.
        List<int[]> childSizes = new ArrayList<>(children.size());
        List<IDiagramModelObject> recursedChildren = new ArrayList<>();
        for (IDiagramModelObject child : children) {
            boolean recurse = isRecursableContainer(child)
                    && (depth + 1) <= MAX_RECURSIVE_LAYOUT_DEPTH;
            if (recurse) {
                recursedChildren.add(child);
                int[] fitted = layoutChildrenOf((IDiagramModelContainer) child, child,
                        depth + 1, arrangement, direction, spacing, padding,
                        elementWidth, elementHeight, autoWidth, columns, acc, sameBatchBounds);
                // a container the batch sized keeps that size; its own fit may still exceed it
                int[] rect = AnchorResolver.refitRect(child, fitted, sameBatchBounds);
                childSizes.add(new int[]{ rect[2], rect[3] });
                acc.nestedContainersArranged++;
            } else {
                if (isRecursableContainer(child)) {
                    // A container we deliberately did not descend into (depth cap):
                    // preserve its existing size so its untouched children still fit.
                    acc.depthCapHit = true;
                }
                childSizes.add(AnchorResolver.effectiveDims(child,
                        (elementWidth != null || elementHeight != null || autoWidth)
                                ? resolveChildSize(child, elementWidth, elementHeight, autoWidth,
                                        sameBatchBounds)
                                : null,
                        sameBatchBounds));
            }
        }

        int labelHeight = labelHeightFor(containerObject);
        int startX = padding;
        int startY = padding + labelHeight;

        List<int[]> positions = arrange(childSizes, levelArrangement, startX, startY,
                spacing, padding, columns);

        for (int i = 0; i < children.size(); i++) {
            int[] p = positions.get(i);
            UpdateViewObjectCommand placement = new UpdateViewObjectCommand(children.get(i),
                    p[0], p[1], p[2], p[3]);
            acc.commands.add(placement);
            // A container the walk descended into is re-fitted here, at the size its own contents
            // required. That rectangle is the thing a caller cannot otherwise learn — the child
            // was never named in the request and its size was decided two levels down.
            if (recursedChildren.contains(children.get(i))) {
                acc.fittedContainers.put(children.get(i).getId(), placement);
            } else {
                // Everything else gets the same full-rectangle write and was reported by nothing.
                recordIfResized(children.get(i), placement, acc.resizedLeaves, sameBatchBounds);
            }
        }
        acc.elementsRepositioned += children.size();
        acc.maxDepthReached = Math.max(acc.maxDepthReached, depth);

        return GroupLayoutCalculator.computeAutoResizeDimensions(positions, padding);
    }

    /**
     * As {@link #placeChildren(List, List, Map, Map)}, for a caller that reports the objects it
     * resized by projecting the compound it builds rather than by reading an observation back.
     *
     * <p>The observation is discarded rather than never made: {@code recordIfResized} is the shared
     * arm two other tools still report from, and duplicating its comparison at this call site to
     * save one map would be the more expensive mistake.</p>
     */
    static List<Command> placeChildren(List<IDiagramModelObject> children, List<int[]> positions,
            Map<String, int[]> sameBatchBounds) {
        return placeChildren(children, positions, new LinkedHashMap<>(), sameBatchBounds);
    }

    /**
     * Places one container's direct children at rectangles a caller has already computed, returning
     * the commands and recording into {@code resized} every child whose <em>size</em> the placement
     * changes.
     *
     * <p>This is the single-level arm of {@code layout-within-group}: a separate loop from the
     * recursive walk above, over positions computed by a different calculator call, and it stretches
     * leaves for a different reason — one cell width across the whole grid rather than one per
     * column. Two write ranges means covering one covers nothing of the other, so the observation
     * lives here where both arms can share it rather than at either call site.</p>
     *
     * @param resized populated by this call; never read from
     */
    static List<Command> placeChildren(List<IDiagramModelObject> children, List<int[]> positions,
            Map<String, Command> resized, Map<String, int[]> sameBatchBounds) {
        List<Command> placements = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            int[] p = positions.get(i);
            UpdateViewObjectCommand placement = new UpdateViewObjectCommand(children.get(i),
                    p[0], p[1], p[2], p[3]);
            placements.add(placement);
            recordIfResized(children.get(i), placement, resized, sameBatchBounds);
        }
        return placements;
    }

    /**
     * Records {@code placement} against {@code child} iff it lands the child at a different size
     * from the one it effectively has.
     *
     * <p>The comparison is against {@link AnchorResolver#effectiveRect}, not {@code getBounds()}:
     * inside an open batch the live read is a pre-batch one, so a size an earlier operation of the
     * same batch queued would be compared away and either a real resize reported as nothing or a
     * no-op reported as a resize.</p>
     *
     * <p>Size only, deliberately. A child that changes size is the thing nothing reported; a child
     * that merely moves is not, and recording moves here would put every child of every call into a
     * list whose name promises resizes. That is a narrower claim than "the call left it alone" —
     * both arms write a full rectangle to every child either way.</p>
     */
    private static void recordIfResized(IDiagramModelObject child, UpdateViewObjectCommand placement,
            Map<String, Command> resized, Map<String, int[]> sameBatchBounds) {
        int[] effective = AnchorResolver.effectiveRect(child, sameBatchBounds);
        if (placement.getNewWidth() != effective[2] || placement.getNewHeight() != effective[3]) {
            resized.put(child.getId(), placement);
        }
    }

    /** Dispatches to the pure-geometry arrangement, count-based columns for grid. */
    private static List<int[]> arrange(List<int[]> childSizes, String arrangement,
            int startX, int startY, int spacing, int padding, Integer columns) {
        switch (arrangement) {
        case "row":
            return GroupLayoutCalculator.computeRowLayout(childSizes, startX, startY, spacing);
        case "column":
            return GroupLayoutCalculator.computeColumnLayout(childSizes, startX, startY, spacing);
        case "grid":
        default:
            Integer cols = (columns != null)
                    ? columns
                    : GroupLayoutCalculator.computeGridColumns(childSizes.size());
            // groupWidth is irrelevant when the column count is explicit. Columns are sized from
            // their own members rather than from the widest element in the grid: at a single level
            // a caller can see an over-wide element inflate its siblings and correct it, but across
            // the levels of one recursive call the inflation compounds — the widened siblings fit
            // their container wider, and that container then widens its own siblings one level up.
            return GroupLayoutCalculator.computeGridLayout(childSizes, startX, startY,
                    spacing, padding, 0, cols, false).positions();
        }
    }

    /** Direct children eligible for layout — notes are never laid out. */
    private static List<IDiagramModelObject> eligibleChildren(IDiagramModelContainer container) {
        List<IDiagramModelObject> children = new ArrayList<>();
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelNote) {
                continue;
            }
            children.add(child);
        }
        return children;
    }

    /**
     * A child is recursed into iff it is a visual group or an ArchiMate-element
     * view-object that has its own (non-note) children. Notes and leaf elements
     * are never recursed.
     */
    private static boolean isRecursableContainer(IDiagramModelObject child) {
        if (child instanceof IDiagramModelNote) {
            return false;
        }
        if (child instanceof IDiagramModelGroup group) {
            return !eligibleChildren(group).isEmpty();
        }
        if (child instanceof IDiagramModelArchimateObject element) {
            return !eligibleChildren(element).isEmpty();
        }
        return false;
    }

    /**
     * Resolves a leaf child's size. A container that is NOT being recursed into
     * (depth cap) preserves its existing size so its untouched contents still
     * fit; a true leaf honours elementWidth / elementHeight / autoWidth.
     *
     * <p>Every axis the caller did NOT override is a fallback, and the fallback reads the child's
     * {@link AnchorResolver#effectiveRect effective} rectangle rather than its stored bounds. Inside
     * an open batch the stored read is a pre-batch one, so a fallback taken from it would write the
     * size the child is about to stop having over the one an earlier operation of the same unit of
     * work asked for. The depth-cap arm overrides neither axis and is therefore a fallback on both.
     *
     * <p>This is also what keeps {@link AnchorResolver#effectiveDims}' "compoundDims wins" contract
     * true rather than merely convenient: the value returned here is genuinely the later write, so
     * the caller that prefers it over the queue is preferring something newer, not something stale.
     */
    private static int[] resolveChildSize(IDiagramModelObject child,
            Integer elementWidth, Integer elementHeight, boolean autoWidth,
            Map<String, int[]> sameBatchBounds) {
        int[] b = AnchorResolver.effectiveRect(child, sameBatchBounds);
        if (isRecursableContainer(child)) {
            // Non-recursed container (depth cap): keep as-is.
            return new int[]{b[2], b[3]};
        }
        int w = (elementWidth != null) ? elementWidth
                : autoWidth ? GroupLayoutCalculator.computeAutoWidth(getDisplayName(child))
                : b[2];
        int h = (elementHeight != null) ? elementHeight : b[3];
        return new int[]{w, h};
    }

    /** Native groups reserve a smaller label band than element containers. */
    static int labelHeightFor(IDiagramModelObject containerObject) {
        return (containerObject instanceof IDiagramModelGroup)
                ? GroupLayoutCalculator.GROUP_LABEL_HEIGHT
                : CONTAINER_TITLE_BAND_HEIGHT;
    }

    // ---- Title-band keep-out for a nudged child ----

    /**
     * The lowest relative y a nudged child may be moved to inside its parent.
     *
     * <p>Normally this is {@link #CONTAINER_TITLE_BAND_HEIGHT}. It is lowered to the child's own
     * pre-nudge position when the child already sits above the band, so the floor can only ever
     * <em>shorten</em> an upward move, never turn it into a downward shove. A clamp that pushed a
     * child further than the caller asked would be a different operation wearing a clamp's name,
     * and on a routing nudge it would move the element away from the corridor the nudge was
     * computed to open.</p>
     *
     * @param currentY the child's relative y before this move is applied
     */
    static int titleBandFloor(int currentY) {
        return Math.min(CONTAINER_TITLE_BAND_HEIGHT, currentY);
    }

    /**
     * Clamps a nudged child's target position to stay inside its parent container and clear of the
     * parent's title band. Returns {@code {x, y}} — unchanged when the parent is not a container
     * (a top-level object's coordinates are absolute canvas coordinates, which this floor would
     * corrupt).
     *
     * <p>The two axes are floored differently on purpose. A title band is a <em>horizontal</em>
     * band, so only y is held clear of it; x keeps the plain padding floor it has always had.
     * Widening the x floor to the same number would push every nudged child right by an amount no
     * observed defect asks for, and is left out rather than smuggled in beside a fix for y.</p>
     *
     * <p>Both container kinds get this FLOOR. An ArchiMate element acting as a container stores its
     * children in the same relative frame a group does, so leaving it unfloored let a nudge drive a
     * child to a negative relative coordinate — outside the parent entirely, on either axis.</p>
     *
     * <p>The ceiling is a different matter and is deliberately NOT closed here. Growing a parent to
     * contain a child that has moved toward its right or bottom edge is the parent-fit cascade's
     * job, and that cascade runs for group parents only. So a child of an element container is held
     * off the top and left but can still extend past the other two edges. That is the state this
     * floor inherited rather than one it created. Closing the ceiling means giving element
     * containers a resize cascade, which changes what the tool writes to the model rather than
     * merely where it declines to write, and no observed failure asks for it yet.</p>
     *
     * <p>The two axes are also not equally safe, and the difference is worth stating rather than
     * leaving to be discovered. The <strong>y</strong> floor is capped at {@code currentY}, so it
     * can only ever shorten an upward move — it can never push a child downward. The
     * <strong>x</strong> floor is the flat {@code padding} value with no {@code currentX}
     * equivalent, so a child that already sits left of the padding line (say at x=3) and is nudged
     * further left is moved <em>right</em>, to the padding line. That displacement is bounded by
     * the padding and is the behaviour this clamp inherited, but it is a genuine push in a
     * direction the caller did not request, and the y-axis guarantee does not extend to it.</p>
     *
     * @param parent   the child's EMF container
     * @param newX     the proposed relative x
     * @param newY     the proposed relative y
     * @param currentY the child's relative y before this move
     * @param padding  the caller's container padding, the x floor this has always used
     */
    static int[] clampInsideParent(EObject parent, int newX, int newY, int currentY, int padding) {
        if (!(parent instanceof IDiagramModelGroup)
                && !(parent instanceof IDiagramModelArchimateObject)) {
            return new int[]{newX, newY};
        }
        return new int[]{Math.max(newX, padding), Math.max(newY, titleBandFloor(currentY))};
    }

    /** Display name for auto-width: the ArchiMate element name, else the view-object name. */
    private static String getDisplayName(IDiagramModelObject child) {
        if (child instanceof IDiagramModelArchimateObject archObj) {
            IArchimateElement element = archObj.getArchimateElement();
            return (element != null) ? element.getName() : null;
        }
        return child.getName();
    }

    // ---- Ancestor auto-resize (UPWARD propagation) ----
    // Distinct from the descendant recursion above: this walks UP the parent
    // chain resizing each ancestor group to fit its (possibly just-resized)
    // children. Shared by layout-within-group's 'recursive' option and the
    // grouped auto-layout pipeline.

    /**
     * What the upward walk did and where it stopped: the number of enclosing groups whose size it
     * actually changed, and the terminal state that produced that number.
     *
     * <p>The count on its own is honest and undecodable, and the exact way it is undecodable is
     * worth stating precisely rather than as a tally, because a tally rots the moment a reason is
     * added. Of the eight reasons, <b>exactly one — {@code propagated} — guarantees a non-zero
     * count.</b> Five always report zero: the caller never asked, asked without {@code autoResize},
     * named a container the walk does not start from, had nothing above the container, or had
     * everything above it already the right size. The remaining two —
     * {@code stopped-at-non-native-ancestor} and {@code depth-cap-reached} — <b>can report
     * either</b>, and those are the ones the count actively misleads about: the walk can re-fit two
     * groups and still stop at a parent it cannot grow, so a positive count reads as unqualified
     * success while a container above is left too small. The count therefore cannot be used to
     * infer the reason in either direction, which is why the reason ships beside it.</p>
     *
     * @param ancestorsResized enclosing groups whose size this walk changed
     * @param reason           one of
     *                         {@link LayoutWithinGroupResultDto#ANCESTOR_PROPAGATION_REASONS}
     */
    record AncestorPropagation(int ancestorsResized, String reason) {}

    /**
     * The upward walk with its own preconditions folded in, returning both halves of the answer.
     *
     * <p>The three request-shaped reasons are decided here rather than at the call site because
     * they are the reasons the walk did not run, and the code that owns an outcome is the code that
     * can name it. They are ordered, and the order is load-bearing rather than cosmetic — the case
     * that motivated this reporting had two exclusions live at once (an ArchiMate-element container
     * whose own parent was a {@code Grouping}), and the walk-never-started one has to win because
     * it is the one that is true: a walk that never began cannot also have stopped somewhere.</p>
     *
     * <ol>
     *   <li><b>{@code not-requested}</b> — the caller did not set {@code recursive}. Outranks
     *       everything, because it is undone by changing the call rather than the view.</li>
     *   <li><b>{@code auto-resize-not-requested}</b> — {@code recursive} was set and
     *       {@code autoResize} was not, so the walk was gated out before any geometry was read.</li>
     *   <li><b>{@code container-not-a-native-group}</b> — the container named in the request is an
     *       ArchiMate element, so the walk never started. This is deliberate and is the opposite
     *       ruling from the one the sibling inter-container arrangement takes: see the widen-vs-
     *       disclose note on {@link #buildGroupedLayoutCommands}.</li>
     *   <li><b>{@code depth-cap-reached}</b> — the walk ran and stopped at
     *       {@link #MAX_RECURSIVE_LAYOUT_DEPTH} with enclosing groups still above the last one it
     *       re-fitted. Decided in {@link #terminationReason} and first among the terminal states,
     *       because this is the only exit that leaves {@code stoppedOn} still a group; borrowing
     *       either "you are done" code here would be false.</li>
     *   <li><b>{@code stopped-at-non-native-ancestor}</b> — the walk ran and ended by meeting a
     *       parent it cannot grow. Outranks the remaining three whatever the count, because it is
     *       the one terminal state that leaves work undone, and a caller reading only the count
     *       would see a success.</li>
     *   <li><b>{@code no-ancestor}</b> — the walk reached the view having visited nothing, so
     *       nothing sits above the container. Terminal.</li>
     *   <li><b>{@code propagated}</b> / <b>{@code all-ancestors-already-fitted}</b> — the walk
     *       reached the view having visited at least one enclosing group, and either changed one or
     *       changed none. Both terminal; they are last because they are the only two that can be
     *       told apart by the count alone.</li>
     * </ol>
     *
     * <p>Items 4 onward are decided by {@link #terminationReason} from the object the loop stopped
     * on, not re-derived here — but the precedence is one list, because a reader triaging a reason
     * needs the whole order and it does not stop at the method boundary.</p>
     *
     * @param containerObject the container the caller named, of any kind the layout accepts
     */
    static AncestorPropagation propagateToAncestors(IDiagramModelObject containerObject,
            boolean recursive, boolean autoResize, List<Command> commands, int padding,
            Map<String, Command> byGroupId, Map<String, int[]> sameBatchBounds) {
        if (!recursive) {
            return new AncestorPropagation(0,
                    LayoutWithinGroupResultDto.PROPAGATION_NOT_REQUESTED);
        }
        if (!autoResize) {
            return new AncestorPropagation(0,
                    LayoutWithinGroupResultDto.PROPAGATION_AUTO_RESIZE_NOT_REQUESTED);
        }
        if (!(containerObject instanceof IDiagramModelGroup startGroup)) {
            return new AncestorPropagation(0,
                    LayoutWithinGroupResultDto.PROPAGATION_CONTAINER_NOT_A_NATIVE_GROUP);
        }
        return resizeAncestorGroups(startGroup, commands, padding, byGroupId, sameBatchBounds);
    }

    /**
     * How the walk ended, decided from the object the loop stopped on rather than re-derived by a
     * caller re-reading the parent chain afterwards. Duplicating the loop's own exit condition one
     * file over would drift from it the first time either changed.
     *
     * <p>This is items 4 onward of the single precedence documented on
     * {@link #propagateToAncestors}; read it there. The reasons for this half of the order, in
     * short: the depth cap leaves {@code stoppedOn} <em>still</em> a group, which is the only way
     * the loop exits with one, so that test comes first — neither "you are done" code is true when
     * groups remain above the last one re-fitted. Then the non-native parent, <em>whatever the
     * count</em>, since that is the signal no zero-only field could have carried. Only then does
     * having visited nothing mean there was nothing to visit.</p>
     *
     * @param stoppedOn the object the loop stopped on: an enclosing group at the depth cap, a
     *                  non-native parent, or the view itself
     * @param visited   enclosing groups the walk reached, whether or not it changed them
     * @param resized   of those, the ones whose size it changed
     */
    private static String terminationReason(EObject stoppedOn, int visited, int resized) {
        if (stoppedOn instanceof IDiagramModelGroup) {
            return LayoutWithinGroupResultDto.PROPAGATION_DEPTH_CAP_REACHED;
        }
        if (stoppedOn instanceof IDiagramModelObject) {
            return LayoutWithinGroupResultDto.PROPAGATION_STOPPED_AT_NON_NATIVE_ANCESTOR;
        }
        if (visited == 0) {
            return LayoutWithinGroupResultDto.PROPAGATION_NO_ANCESTOR;
        }
        return resized > 0
                ? LayoutWithinGroupResultDto.PROPAGATED
                : LayoutWithinGroupResultDto.PROPAGATION_ALL_ANCESTORS_ALREADY_FITTED;
    }

    /**
     * Recursively resizes ancestor groups to fit their children, walking up the
     * parent chain from the specified group. Stops at view level or max depth.
     * Uses the specified padding for consistency with the target group's layout.
     * Returns the number of ancestors whose size actually changed, and how the walk ended.
     */
    static AncestorPropagation resizeAncestorGroups(IDiagramModelGroup startGroup,
            List<Command> commands, int padding) {
        return resizeAncestorGroups(startGroup, commands, padding, null);
    }

    /**
     * As {@link #resizeAncestorGroups(IDiagramModelGroup, List, int)}, additionally recording each
     * ancestor's resize command in {@code byGroupId} so the caller can report <em>what</em> the
     * ancestors grew to rather than only how many did.
     *
     * <p>The count this method returns has always been honest about the number and silent about the
     * geometry, and the geometry is what an agent that cannot see the canvas actually needs — a
     * count is an index into information it does not have. Keying the commands the walk already
     * builds is enough to close that, and it costs the two callers who only want the count nothing:
     * they pass null and the recording is skipped.</p>
     *
     * <p>The commands are still appended to {@code commands} exactly as before; {@code byGroupId}
     * is a second reference to the same objects, never an alternative home for them. A caller that
     * treated it as one would stop the ancestors being resized at all.</p>
     *
     * @param byGroupId out-parameter recording {@code group id → resize command}, or null
     */
    static AncestorPropagation resizeAncestorGroups(IDiagramModelGroup startGroup,
            List<Command> commands, int padding, Map<String, Command> byGroupId) {
        return resizeAncestorGroups(startGroup, commands, padding, byGroupId, null);
    }

    /**
     * As {@link #resizeAncestorGroups(IDiagramModelGroup, List, int, Map)}, measuring each ancestor
     * against the geometry an open batch has already queued for it instead of its pre-batch
     * {@code getBounds()}.
     *
     * <p>The walk emits an <em>absolute</em> rectangle per ancestor. Inside a batch that rectangle
     * is computed from a read that predates every command the batch holds, so an ancestor an earlier
     * operation of the same batch sized is silently resized back — and because this walk runs
     * <em>after</em> the caller has already floored the group it started from, fixing only that
     * caller would be a veto this walk steps straight around.</p>
     *
     * <p>The floor has to be applied <em>inside</em> the loop rather than to the finished command
     * list: each hop reads the previous hop's command through
     * {@link #findPendingDimensions(List, IDiagramModelObject)} to learn what its own children
     * measure, and that lookup takes the <em>latest</em> matching command. An ancestor floored only
     * after the walk finished would already have been fitted against by its own parent at the
     * un-floored size, leaving the grandparent too small.</p>
     *
     * <p>The claim is judged against the same effective rectangle, not against the live one: a group
     * whose size the batch — not this walk — established has not been resized <em>by this walk</em>,
     * and naming it would report an outcome that belongs to another operation.</p>
     *
     * <p>{@code sameBatchBounds} null (every pre-existing caller, via the delegating overload above,
     * and any call outside a batch) makes every lookup miss, so the effective rectangle is the live
     * one and both the command and the count are what they were before.</p>
     *
     * @param sameBatchBounds the open batch's queued geometry by view-object id, or null
     */
    static AncestorPropagation resizeAncestorGroups(IDiagramModelGroup startGroup,
            List<Command> commands, int padding, Map<String, Command> byGroupId,
            Map<String, int[]> sameBatchBounds) {
        int resized = 0;
        EObject current = startGroup.eContainer();
        int depth = 0;

        while (current instanceof IDiagramModelGroup parentGroup
                && depth < MAX_RECURSIVE_LAYOUT_DEPTH) {
            // Compute required dimensions from all children of this parent
            List<int[]> childPositions = new ArrayList<>();
            for (IDiagramModelObject child : parentGroup.getChildren()) {
                IBounds b = child.getBounds();
                // Check if this child has a pending resize command
                int[] dims = findPendingDimensions(commands, child);
                int cx = b.getX();
                int cy = b.getY();
                int cw = (dims != null) ? dims[0] : b.getWidth();
                int ch = (dims != null) ? dims[1] : b.getHeight();
                childPositions.add(new int[]{cx, cy, cw, ch});
            }

            int[] parentDims = GroupLayoutCalculator.computeAutoResizeDimensions(
                    childPositions, padding);
            int[] parentBounds = AnchorResolver.effectiveRect(parentGroup, sameBatchBounds);
            int[] rect = AnchorResolver.refitRect(parentGroup, parentDims, sameBatchBounds);
            UpdateViewObjectCommand resize = new UpdateViewObjectCommand(parentGroup,
                    rect[0], rect[1], rect[2], rect[3]);
            // The command is emitted either way: the next hop up reads this list to learn what its
            // own children will measure, so dropping a no-op here would make the grandparent fit
            // against stale dimensions. What is conditional is the CLAIM. A repeated layout
            // recomputes identical dimensions for an ancestor that is already fitted, and counting
            // or naming it then would report a resize that did not happen — the same defect as
            // sourcing an outcome from the request that asked for it, one field over.
            commands.add(resize);
            if (rect[2] != parentBounds[2] || rect[3] != parentBounds[3]) {
                if (byGroupId != null) {
                    byGroupId.put(parentGroup.getId(), resize);
                }
                resized++;
            }
            logger.debug("Recursive resize: ancestor group {} resized to {}x{}",
                    parentGroup.getId(), rect[2], rect[3]);

            current = parentGroup.eContainer();
            depth++;
        }
        return new AncestorPropagation(resized, terminationReason(current, depth, resized));
    }

    /**
     * Whether a layout pass actually changed the container's size, as opposed to having been
     * <em>asked</em> to consider it.
     *
     * <p>A field named as an outcome must not be sourced from the request that asked for it. The
     * caller's {@code autoResize} flag says what was permitted; it says nothing about whether the
     * fitted dimensions differ from the ones the container already had, and re-running a layout on
     * an already-fitted container changes nothing while still reporting a resize. So the flag is a
     * precondition here, not the answer: with it false there is no resize by construction, and with
     * it true the answer is whether the computed rectangle differs from the live one.</p>
     *
     * @param container      the container the layout targeted
     * @param autoResize     whether the caller permitted a resize at all
     * @param fittedWidth    the width the pass computed, or null when it computed none
     * @param fittedHeight   the height the pass computed, or null when it computed none
     */
    static boolean grewContainer(IDiagramModelObject container, boolean autoResize,
            Integer fittedWidth, Integer fittedHeight) {
        if (!autoResize || fittedWidth == null || fittedHeight == null) {
            return false;
        }
        IBounds current = container.getBounds();
        return fittedWidth != current.getWidth() || fittedHeight != current.getHeight();
    }

    /**
     * Finds pending resize dimensions (width, height) for a view object in the
     * commands list. Returns null if no pending command exists.
     */
    static int[] findPendingDimensions(List<Command> commands, IDiagramModelObject obj) {
        // Walk backwards to find the latest command for this object
        for (int i = commands.size() - 1; i >= 0; i--) {
            Command cmd = commands.get(i);
            if (cmd instanceof UpdateViewObjectCommand updateCmd
                    && updateCmd.getDiagramObject() == obj) {
                return new int[]{updateCmd.getNewWidth(), updateCmd.getNewHeight()};
            }
        }
        return null;
    }
}
