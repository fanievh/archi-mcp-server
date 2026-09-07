package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

/**
 * Headless tests for {@link NestedLayoutOperations}.
 *
 * <p>WHY THIS CLASS EXISTS. Until now the nested-container recursion was exercised only through
 * {@code ArchiModelAccessorImplTest}, which needs a display and is excluded from the plain lane, so
 * every geometric decision it makes was unreachable from a headless run. Pure EMF construction
 * needs no display, so the decidable geometry can and does live here; the facade call sites keep a
 * source-level wiring pin in {@code LayoutQualityAssessorTest}, which is the only thing a test can
 * assert about a layer no test can execute.</p>
 *
 * <p>The file is divided into per-concern blocks so concurrent work on distinct defects does not
 * collide in one hunk.</p>
 */
public class NestedLayoutOperationsTest {

    // ====================================================================
    // BLOCK: TITLE-BAND KEEP-OUT FOR A NUDGED CHILD
    // ====================================================================
    //
    // MECHANISM UNDER TEST, reproduced from a live failure. auto-route-connections(autoNudge:true)
    // recommended a -40px lift for a Gap sitting at relative y=50 inside a group. The only floor on
    // the path was the container padding (10), so the child landed at y=10 — inside the parent's
    // title band. assess-layout then reported parentLabelObscured, which is a critical layout
    // finding, taking the view from fair to poor: the nudge converted a cosmetic routing defect
    // into a rating veto.
    //
    // The pin is on the MECHANISM, not only on the outcome: it reproduces the exact shape (y=50,
    // dy=-40) and asserts the post-clamp position clears the band the detector judges against, and
    // it holds a control whose move is already legal byte-identical so the clamp cannot degenerate
    // into an unconditional shove.

    /** The deepest band {@code LayoutQualityAssessor} can judge a parent against: a wrapped title. */
    private static final int DETECTOR_MAX_BAND = 40;

    private static final int PADDING = 10;

    @Test
    public void clampInsideParent_shouldFloorNudgedChildAtTitleBand_whenParentIsGroup() {
        IDiagramModelGroup parent = IArchimateFactory.eINSTANCE.createDiagramModelGroup();

        // The live shape: child at relative y=50, recommendation dy=-40 -> proposed y=10.
        int[] clamped = NestedLayoutOperations.clampInsideParent(parent, 30, 50 - 40, 50, PADDING);

        assertEquals("the nudge must not place the child inside the parent's title band",
                NestedLayoutOperations.CONTAINER_TITLE_BAND_HEIGHT, clamped[1]);
        assertTrue("the clamped position must clear the deepest band the detector judges against",
                clamped[1] >= DETECTOR_MAX_BAND);
        assertEquals("x keeps its padding floor — a title band is a horizontal band", 30, clamped[0]);
    }

    @Test
    public void clampInsideParent_shouldFloorNudgedChildAtTitleBand_whenParentIsElementContainer() {
        IDiagramModelArchimateObject parent =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();

        int[] clamped = NestedLayoutOperations.clampInsideParent(parent, 30, 50 - 40, 50, PADDING);

        assertEquals("an element acting as a container stores children in the same relative frame",
                NestedLayoutOperations.CONTAINER_TITLE_BAND_HEIGHT, clamped[1]);
    }

    @Test
    public void clampInsideParent_shouldFloorNegativeX_whenParentIsElementContainer() {
        IDiagramModelArchimateObject parent =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();

        // Before the fix an element-container parent got no floor on EITHER axis, so a nudge could
        // drive a child to a negative relative coordinate — outside its parent altogether.
        int[] clamped = NestedLayoutOperations.clampInsideParent(parent, -25, 60, 60, PADDING);

        assertEquals("x must not be driven negative inside an element container", PADDING, clamped[0]);
        assertEquals("a y that already clears the band is not touched", 60, clamped[1]);
    }

    @Test
    public void clampInsideParent_shouldLeaveLegalNudgeByteIdentical() {
        IDiagramModelGroup parent = IArchimateFactory.eINSTANCE.createDiagramModelGroup();

        // Control: a move that is already clear of the band on both axes must pass through
        // untouched. If this ever changes, the clamp has become an unconditional shove.
        assertArrayEquals(new int[]{80, 120},
                NestedLayoutOperations.clampInsideParent(parent, 80, 120, 160, PADDING));
    }

    @Test
    public void clampInsideParent_shouldNotShoveChildThatStartedInsideTheBand() {
        IDiagramModelGroup parent = IArchimateFactory.eINSTANCE.createDiagramModelGroup();

        // A child laid out at y=34 (a group's own padding + label start) nudged up by 4. Flooring
        // at the full band would move it DOWN by 12 — the opposite of what the router asked for.
        // The floor is capped at the child's own pre-nudge y, so the move is shortened to zero.
        int[] clamped = NestedLayoutOperations.clampInsideParent(parent, 30, 30, 34, PADDING);

        assertEquals("the clamp may shorten a move, never reverse it", 34, clamped[1]);
    }

    @Test
    public void clampInsideParent_shouldLeaveTopLevelObjectAlone() {
        // A top-level object's container is the diagram model and its coordinates are ABSOLUTE.
        // Applying a relative floor there would silently relocate it on the canvas.
        int[] clamped = NestedLayoutOperations.clampInsideParent(
                IArchimateFactory.eINSTANCE.createArchimateDiagramModel(), -25, 5, 5, PADDING);

        assertArrayEquals(new int[]{-25, 5}, clamped);
    }

    @Test
    public void titleBandFloor_shouldNeverExceedTheChildsOwnPosition() {
        assertEquals(NestedLayoutOperations.CONTAINER_TITLE_BAND_HEIGHT,
                NestedLayoutOperations.titleBandFloor(200));
        assertEquals(12, NestedLayoutOperations.titleBandFloor(12));
    }

    @Test
    public void containerTitleBand_shouldNotBeShallowerThanTheDetectorsDeepestBand() {
        // The keep-out's whole purpose is that a clamped child does not trip the detector. A value
        // below the deepest band the detector can judge against would still produce the finding.
        assertTrue("keep-out must be >= the detector's wrapped-title band",
                NestedLayoutOperations.CONTAINER_TITLE_BAND_HEIGHT >= DETECTOR_MAX_BAND);
    }

    // ---- wiring pins: the facade call site no test can execute ----
    //
    // auto-route-connections' nudge loop lives inside a 300-line method on the accessor facade,
    // behind a routing pipeline and an EMF diagram model. No headless test reaches it and the one
    // test class that could is display-gated. Computing the right floor in this collaborator
    // therefore proves nothing about the tool unless the call site actually consumes it, so the
    // wiring is pinned at source level and the behavioural proof is the agent-in-loop live gate.

    @Test
    public void autoNudgeCallSite_shouldDelegateToTheSharedClamp() {
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");

        assertTrue("the nudge must clamp through the shared keep-out, not its own floor",
                facade.contains("NestedLayoutOperations.clampInsideParent(container,"));
        assertFalse("the padding-only floor that produced the live failure must be gone",
                facade.contains("int minPos = DEFAULT_GROUP_PADDING;"));
    }

    @Test
    public void autoNudgeCallSite_shouldReportTheClampedDisplacement() {
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");

        // A clamped nudge that reported the recommendation's delta would be an echo of the request
        // wearing an outcome's name: the agent cannot see the canvas, so the response is its only
        // ground truth. The displacement is derived from the post-clamp position, and the same
        // post-clamp rectangle is what the parent-fit cascade is asked to fit.
        int clamp = facade.indexOf("NestedLayoutOperations.clampInsideParent(container,");
        int delta = facade.indexOf("int actualDx = newX - bounds.getX() - priorDelta[0];", clamp);
        int cascade = facade.indexOf("ParentFitCascade.resize(parentGroup, dmo, newX, newY,", clamp);
        assertTrue("the reported displacement must be computed AFTER the clamp",
                clamp > 0 && delta > clamp);
        assertTrue("the parent-fit cascade must be fed the clamped rectangle",
                cascade > clamp);
    }

    /** Reads a production source file, failing rather than silently covering nothing. */
    private static String readProductionSource(String relativePath) {
        for (String candidate : new String[]{
                "net.vheerden.archi.mcp/src", "../net.vheerden.archi.mcp/src"}) {
            Path path = Paths.get(candidate, "net/vheerden/archi/mcp", relativePath);
            if (Files.isRegularFile(path)) {
                try {
                    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new AssertionError("could not resolve " + relativePath + " from "
                + Paths.get("").toAbsolutePath() + " — this pin cannot silently cover nothing");
    }

    // ====================================================================
    // BLOCK: GROUPED-MODE DESCENT INTO CONTAINER CHILDREN
    // ====================================================================

    @Test
    public void buildRecursiveLayoutCommands_shouldSizeContainerChildFromContents_notFromLabel() {
        // A top-level group holding ONE element container, which itself holds two wide children.
        // Sizing the container from its own label text (name.length()*8 + 30) would give it a box
        // far narrower than its contents, and the children would escape it.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setName("Domain");
        root.setBounds(0, 0, 400, 300);

        IDiagramModelArchimateObject container = archObject("App", 0, 0, 120, 60);
        root.getChildren().add(container);
        IDiagramModelObject fn = archObject("Payments Reconciliation Function", 0, 0, 300, 55);
        container.getChildren().add(fn);
        container.getChildren().add(archObject("Fee Function", 0, 0, 300, 55));

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root,
                        "column", 20, PADDING, null, null, true, null, true);

        // "App" is a 3-character name: sized from its own label it would be 3*8 + 30 = 54 px wide,
        // which is the defect this pins. Sized from its contents it is the widest child plus the
        // padding on both sides.
        int labelWidthOfContainerName = 3 * GroupLayoutCalculator.AVG_CHAR_WIDTH
                + GroupLayoutCalculator.HORIZONTAL_PADDING;
        int[] containerBox = pendingBox(result, container);
        assertEquals("the container must be sized from its contents, not from its label width",
                pendingBox(result, fn)[2] + 2 * PADDING, containerBox[2]);
        assertTrue("sizing from its own label would have produced a far narrower box",
                containerBox[2] > labelWidthOfContainerName);
        assertEquals("the pass must report the descendant containers it arranged",
                1, result.nestedContainersArranged());
        assertEquals("repositioned must count descendants, not only direct children",
                3, result.elementsRepositioned());
    }

    @Test
    public void buildRecursiveLayoutCommands_shouldKeepChildrenInsideEveryContainer() {
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setName("Domain");
        root.setBounds(0, 0, 100, 100);

        IDiagramModelArchimateObject container = archObject("Actor", 0, 0, 90, 40);
        root.getChildren().add(container);
        IDiagramModelObject roleA = archObject("Role A", 0, 0, 160, 50);
        IDiagramModelObject roleB = archObject("Role B", 0, 0, 160, 50);
        container.getChildren().add(roleA);
        container.getChildren().add(roleB);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root,
                        "column", 20, PADDING, null, null, true, null, true);

        int[] box = pendingBox(result, container);
        for (IDiagramModelObject role : new IDiagramModelObject[]{roleA, roleB}) {
            int[] child = pendingBox(result, role);
            assertTrue("child right edge must stay inside its container",
                    child[0] + child[2] <= box[2]);
            assertTrue("child bottom edge must stay inside its container",
                    child[1] + child[3] <= box[3]);
            assertTrue("no child may sit in its container's title band",
                    child[1] >= NestedLayoutOperations.CONTAINER_TITLE_BAND_HEIGHT);
        }
        assertTrue("the root must be grown to hold the fitted container",
                result.rootFittedWidth() >= box[2] + 2 * PADDING);
    }

    @Test
    public void buildRecursiveLayoutCommands_shouldSkipNotes() {
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 400, 300);
        root.getChildren().add(archObject("Element", 0, 0, 120, 55));
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setBounds(5, 5, 100, 40);
        root.getChildren().add(note);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root,
                        "column", 20, PADDING, null, null, true, null, false);

        assertEquals("notes are never laid out", 1, result.elementsRepositioned());
    }

    @Test
    public void buildRecursiveLayoutCommands_shouldLeaveALeafOnlyGroupWhereTheOldPassPutIt() {
        // THE REGRESSION CONTROL for the grouped pipeline. Every existing grouped view in every
        // model goes through this path, and the overwhelming majority hold no nested container at
        // all. For those the recursion must land on exactly the coordinates the hand-rolled pass
        // produced: startX = padding, startY = padding + a group's label band, widths from each
        // leaf's own label. The numbers are written out rather than recomputed, so a drift in
        // either implementation fails here instead of cancelling out.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 500, 400);
        IDiagramModelObject a = archObject("Alpha", 0, 0, 999, 55);   // 5 chars -> 5*8 + 30 = 70
        IDiagramModelObject b = archObject("Bee", 0, 0, 999, 60);     // 3 chars -> 3*8 + 30 = 60
        root.getChildren().add(a);
        root.getChildren().add(b);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root,
                        "row", 40, PADDING, null, null, true, null, true);

        int expectedTop = PADDING + GroupLayoutCalculator.GROUP_LABEL_HEIGHT;
        assertArrayEquals("first leaf keeps the old pass's origin and auto-width",
                new int[]{PADDING, expectedTop, 70, 55}, pendingBox(result, a));
        assertArrayEquals("second leaf sits one spacing to the right, as before",
                new int[]{PADDING + 70 + 40, expectedTop, 60, 60}, pendingBox(result, b));
        assertEquals("a leaf-only group arranges no nested container",
                0, result.nestedContainersArranged());
        assertFalse("and cannot hit the depth cap", result.depthCapHit());
    }

    @Test
    public void buildRecursiveLayoutCommands_gridInALeafOnlyGroup_usesPerColumnWidths() {
        // SCOPE NOTE, deliberate and worth stating because it is easy to read as an oversight.
        // The grouped auto-layout pipeline routes through this recursion, so a top-level group of
        // four or more leaves — which the pipeline arranges as a grid — gets per-column cell widths
        // too, not only genuinely nested views. That is intentional: the criterion for per-column
        // sizing is whether the CALLER can see the intermediate grid and correct an over-wide
        // element, and in a whole-view orchestration it cannot, exactly as in a multi-level call.
        // The row arrangement, which the pipeline picks for three or fewer children, is unaffected.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 500, 400);
        IDiagramModelObject narrow = archObject("a", 0, 0, 60, 40);
        IDiagramModelObject wide = archObject("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 0, 0, 300, 40);
        root.getChildren().add(narrow);
        root.getChildren().add(wide);
        root.getChildren().add(archObject("c", 0, 0, 60, 40));
        root.getChildren().add(archObject("d", 0, 0, 60, 40));

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root,
                        "grid", 40, PADDING, null, null, false, 2, true);

        assertEquals("the narrow leaf is no longer widened to the widest leaf's width",
                60, pendingBox(result, narrow)[2]);
        assertEquals("the wide leaf keeps its own width", 300, pendingBox(result, wide)[2]);
        assertEquals("columns still align across rows",
                pendingBox(result, narrow)[0], pendingBox(result, root.getChildren().get(2))[0]);
    }

    @Test
    public void buildRecursiveLayoutCommands_shouldReportEachFittedContainerWithItsRectangle() {
        // A count of resized containers is an index into information the agent does not have: it
        // cannot see the canvas, so "2 containers resized" leaves it planning against the two
        // rectangles it last saw. The pass reports which containers and what they became.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 400, 300);
        IDiagramModelArchimateObject elementContainer = archObject("Actor", 0, 0, 90, 40);
        elementContainer.getChildren().add(archObject("Role", 0, 0, 150, 50));
        IDiagramModelGroup nestedGroup = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        nestedGroup.setName("Inner");
        nestedGroup.setBounds(0, 0, 50, 50);
        nestedGroup.getChildren().add(archObject("Leaf", 0, 0, 120, 50));
        root.getChildren().add(elementContainer);
        root.getChildren().add(nestedGroup);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root,
                        "column", 20, PADDING, null, null, true, null, true);

        assertEquals("both container kinds are descended into, not only element containers",
                2, result.nestedContainersArranged());
        assertEquals("every arranged container is reported with its geometry",
                result.nestedContainersArranged(), result.fittedContainers().size());
        assertTrue(result.fittedContainers().containsKey(elementContainer.getId()));
        assertTrue("a NESTED GROUP is treated identically to an element container",
                result.fittedContainers().containsKey(nestedGroup.getId()));
    }

    @Test
    public void buildRecursiveLayoutCommands_shouldStopAtTheDepthCapAndSaySo() {
        // Build a chain deeper than the cap and confirm the pass declares that it stopped rather
        // than silently truncating the layout.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 400, 300);
        IDiagramModelContainer cursor = root;
        for (int i = 0; i <= NestedLayoutOperations.MAX_RECURSIVE_LAYOUT_DEPTH + 1; i++) {
            IDiagramModelArchimateObject next = archObject("L" + i, 0, 0, 200, 100);
            cursor.getChildren().add(next);
            cursor = next;
        }
        cursor.getChildren().add(archObject("Leaf", 0, 0, 80, 40));

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root,
                        "column", 20, PADDING, null, null, true, null, true);

        assertTrue("the depth cap must be declared, not applied in silence", result.depthCapHit());
        assertTrue("and the walk must not exceed it",
                result.maxDepthReached() <= NestedLayoutOperations.MAX_RECURSIVE_LAYOUT_DEPTH);
    }

    @Test
    public void buildGroupedLayoutCommands_shouldChooseArrangementPerLevel_notOncePerView() {
        // Review finding. The grouped pipeline derives its arrangement itself, from the TOP-LEVEL
        // group's child count, and the first version of this fix then applied that one answer at
        // every depth. A group holding 2 children picks "row"; a container inside it holding 8 was
        // then laid out as a single 8-wide strip — exactly the tall/narrow shape the arrangement
        // chooser exists to prevent, applied to a count it was never computed for.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 200, 200);
        IDiagramModelArchimateObject crowded = archObject("Crowded", 0, 0, 100, 100);
        root.getChildren().add(crowded);
        root.getChildren().add(archObject("Leaf", 0, 0, 100, 50));
        for (int i = 0; i < 8; i++) {
            crowded.getChildren().add(archObject("child" + i, 0, 0, 100, 40));
        }

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildGroupedLayoutCommands(root, 20, PADDING, "DOWN");

        // 2 children at the top -> "row". 8 inside -> "grid", so the inner children must occupy
        // more than one row: at least two distinct y values among them.
        long distinctRows = crowded.getChildren().stream()
                .map(c -> pendingBox(result, c)[1]).distinct().count();
        assertTrue("a container with 8 children must not inherit its parent's row arrangement",
                distinctRows > 1);
    }

    @Test
    public void buildGroupedLayoutCommands_shouldNotShrinkAContainerThatHoldsOnlyNotes() {
        // A container whose only children are notes has nothing to arrange, so it has measured
        // nothing. Fitting it to zero children would shrink it to padding-squared, burying the
        // notes still inside it.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 400, 300);
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setBounds(5, 5, 100, 40);
        root.getChildren().add(note);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildGroupedLayoutCommands(root, 20, PADDING, "DOWN");

        assertEquals("the container keeps its width", 400, result.rootFittedWidth());
        assertEquals("the container keeps its height", 300, result.rootFittedHeight());
    }

    @Test
    public void groupedPassCallSite_shouldDescendAndFeedArrangeGroupsTheFittedRectangle() {
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");

        assertTrue("grouped mode must reuse the existing recursion, not grow a second one",
                facade.contains("NestedLayoutOperations.buildGroupedLayoutCommands(group,"));
        assertFalse("the leaf-only autoWidth shortcut that mis-sized containers must be gone",
                facade.contains("boolean effectiveAutoWidth = true;"));
        assertTrue("arrange-groups must measure the FITTED rectangle, not the pre-descent one",
                facade.contains("new int[]{nested.rootFittedWidth(), nested.rootFittedHeight()}"));
        assertTrue("the response must carry each fitted container's rectangle, not just a count",
                facade.contains("layoutPass.nestedContainersFitted"));
        assertTrue("the depth cap must reach the response",
                facade.contains("layoutPass.depthCapHit"));
    }

    @Test
    public void groupedPassCallSite_shouldNotTouchTheSharedElementSizeResolver() {
        // resolveElementSizes measures CRITICAL upstream — it is shared with layout-flat-view and
        // optimize-group-order — and its label-width behaviour is CORRECT for a leaf. The
        // container-vs-leaf decision belongs at the grouped call site, so the resolver keeps the
        // override PRECEDENCE it had at the sweep's baseline: an explicit width first, then the
        // label-derived one, then a fallback.
        //
        // What the fallback READS was allowed to change and did: it is now the child's effective
        // rectangle, so inside an open batch it yields the size the batch queued instead of the
        // pre-batch one. That is a change of source, not of precedence, and the assertion below
        // still fails if the container-vs-leaf decision is ever pushed down into the resolver.
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");

        assertTrue("the resolver's leaf sizing precedence must be untouched",
                facade.contains("int w = (elementWidth != null) ? elementWidth\n"
                        + "                    : autoWidth ? computeAutoWidth(child)\n"
                        + "                    : eff[2];"));
        assertFalse("the resolver must not learn what a container is — that decision stays at the "
                + "grouped call site", facade.contains("isRecursableContainer(child)\n"
                        + "                    ? "));
    }

    @Test
    public void groupedRelayCallSite_shouldStayBlindToTheBatchQueue() {
        // The relay pass inside the grouped quality-target loop shares resolveElementSizes with the
        // three standalone layout tools, and it must NOT become queue-aware with them: it runs only
        // where a preceding pass has already chosen every group's size from its contents, so
        // honouring a queued size would leave one call flooring the groups it happened to reorder
        // and overriding the rest. The map is therefore a PARAMETER, and this call site passes
        // null explicitly rather than inheriting a session lookup it cannot opt out of.
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");

        // Each arm is named in full. A bare "true, null);" would match whichever arm is still
        // correct and stay green while another leaked — the three calls are textually identical
        // from the arguments alone, so the assertion has to carry the method name with it.
        assertTrue("the relay's row arm must stay queue-blind",
                facade.contains("computeRowLayout(orderedChildren, startX, startY,\n"
                        + "                        resolvedSpacing, null, null, true, null);"));
        assertTrue("its column arm, which is also its default",
                facade.contains("computeColumnLayout(orderedChildren, startX, startY,\n"
                        + "                        resolvedSpacing, null, null, true, null);"));
        assertTrue("and its grid arm, which also decides a column count",
                facade.contains("computeGridLayout(orderedChildren, startX, startY,\n"
                        + "                                resolvedSpacing, resolvedPadding, 0,\n"
                        + "                                null, null, true, gridCols, null);"));
    }

    // ====================================================================
    // BLOCK: PER-COLUMN GRID WIDTH UNDER THE RECURSIVE DESCENT
    // ====================================================================
    //
    // MECHANISM UNDER TEST, from a live failure. A three-level nested inventory laid out with
    // recursiveChildren produced a 4300px band. The cause was not the column COUNT: the grid gives
    // every cell the width of the widest element in the whole grid, so a single 420px
    // sub-component set the width of every sibling cell inside its parent, and that inflated
    // parent then set every cell width one level up. One wide grandchild, compounded twice.
    //
    // A caller can see and correct this in a single-level call — it lays out one container and
    // reads the result. It cannot see it coming across three levels of one call, which is what
    // makes the recursive path the place to fix it.

    @Test
    public void buildRecursiveLayoutCommands_shouldNotLetOneWideGrandchildSetEverySiblingsWidth() {
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 200, 200);

        IDiagramModelArchimateObject band = archObject("Core", 0, 0, 100, 100);
        root.getChildren().add(band);
        // elementWidth is set explicitly so the widths under test are the fixture's, not the
        // label-derived ones — the compounding is about cell sizing, not auto-width.
        IDiagramModelObject narrow = archObject("A", 0, 0, 100, 50);
        IDiagramModelObject wide = archObject("B", 0, 0, 420, 50);
        band.getChildren().add(narrow);
        band.getChildren().add(wide);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root,
                        "grid", 20, PADDING, null, null, false, 2, true);

        int[] narrowBox = pendingBox(result, narrow);
        int[] wideBox = pendingBox(result, wide);
        assertEquals("the narrow child keeps its own width", 100, narrowBox[2]);
        assertEquals("the wide child keeps its own width", 420, wideBox[2]);
        // Uniform cells would put the second column at 10 + 420 + 20 = 450 and make the band
        // 10 + 420 + 20 + 420 + 10 = 880 wide. Per-column sizing gives 10 + 100 + 20 = 130 and
        // a 560px band — the 320px this fixture's single wide child was inflating.
        assertEquals("the second column starts after the FIRST column's width, not the widest",
                PADDING + 100 + 20, wideBox[0]);
        assertEquals("the container fits the two real columns, not two copies of the widest",
                PADDING + 100 + 20 + 420 + PADDING, pendingBox(result, band)[2]);
    }

    @Test
    public void buildRecursiveLayoutCommands_shouldKeepGridColumnsAlignedAcrossRows() {
        // Per-column sizing must not cost grid alignment: column j has one width in every row, so
        // the columns still line up vertically. Four children in two columns, widths 60/300/80/120
        // -> column widths 80 and 300, and the second row starts at the same two x positions.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 200, 200);
        IDiagramModelObject[] kids = {
                archObject("a", 0, 0, 60, 40), archObject("b", 0, 0, 300, 40),
                archObject("c", 0, 0, 80, 40), archObject("d", 0, 0, 120, 40)};
        for (IDiagramModelObject k : kids) {
            root.getChildren().add(k);
        }

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root,
                        "grid", 20, PADDING, null, null, false, 2, false);

        assertEquals("row 2 column 1 aligns with row 1 column 1",
                pendingBox(result, kids[0])[0], pendingBox(result, kids[2])[0]);
        assertEquals("row 2 column 2 aligns with row 1 column 2",
                pendingBox(result, kids[1])[0], pendingBox(result, kids[3])[0]);
        assertEquals("column 1 is sized from its own widest member",
                80, pendingBox(result, kids[0])[2]);
        assertEquals("column 2 is sized from its own widest member",
                300, pendingBox(result, kids[1])[2]);
    }


    // ====================================================================
    // BLOCK: THE SHARED RESIZE OBSERVATION
    // ====================================================================
    //
    // MECHANISM UNDER TEST. Several layout passes write a full [x, y, w, h] rectangle to every
    // child they place, so a child can leave a call at a size nobody asked for — a uniform grid
    // cell, a label-derived width, a container the same pass just re-fitted. placeChildren is the
    // one collaborator those passes share, and recordIfResized is the single comparison that
    // decides which of the placed children get named.
    //
    // WHY THE PINS LIVE HERE. Every end-to-end home for these tools needs a display and runs only
    // in the plugin lane. This class is headless, so this is the only lane in which the
    // comparison's arithmetic runs on every push. The pins drive placeChildren directly rather
    // than handing its output in as literals: a pin fed literals tests the fixture, not the
    // collector.
    //
    // The comparison is SIZE ONLY and it is against the EFFECTIVE rectangle, not getBounds():
    // inside an open batch a live read is a pre-batch one, so a size an earlier operation of the
    // same batch queued must be the basis of the comparison. Both properties are pinned below.

    @Test
    public void placeChildren_shouldRecordAChildWhoseWidthThePlacementChanges() {
        IDiagramModelObject child = archObject("stretched", 0, 0, 120, 55);
        Map<String, Command> resized = new LinkedHashMap<>();

        List<Command> placements = NestedLayoutOperations.placeChildren(
                List.of(child), List.of(new int[]{10, 10, 400, 55}), resized, Map.of());

        assertEquals("one placement per child", 1, placements.size());
        assertTrue("a child the placement widens must be recorded",
                resized.containsKey(child.getId()));
        UpdateViewObjectCommand recorded = (UpdateViewObjectCommand) resized.get(child.getId());
        assertEquals("the recorded command must carry the LANDED width, not the old one",
                400, recorded.getNewWidth());
        assertEquals("and the landed height", 55, recorded.getNewHeight());
        assertEquals("and the landed x", 10, recorded.getNewX());
        assertEquals("and the landed y", 10, recorded.getNewY());
    }

    @Test
    public void placeChildren_shouldRecordAChildWhoseHeightThePlacementChanges() {
        // Height alone, so a pin that only ever watched width cannot pass this by accident.
        IDiagramModelObject child = archObject("taller", 0, 0, 120, 55);
        Map<String, Command> resized = new LinkedHashMap<>();

        NestedLayoutOperations.placeChildren(
                List.of(child), List.of(new int[]{0, 0, 120, 200}), resized, Map.of());

        assertTrue("a child the placement heightens must be recorded",
                resized.containsKey(child.getId()));
    }

    @Test
    public void placeChildren_shouldNotRecordAChildThatOnlyMoves() {
        // The list's name promises resizes. A child that merely moves is written the same full
        // rectangle as every other child and must stay out of it, or the list degenerates into
        // "every child of every call".
        IDiagramModelObject child = archObject("moved", 0, 0, 120, 55);
        Map<String, Command> resized = new LinkedHashMap<>();

        List<Command> placements = NestedLayoutOperations.placeChildren(
                List.of(child), List.of(new int[]{300, 400, 120, 55}), resized, Map.of());

        assertEquals("the child is still placed", 1, placements.size());
        assertTrue("a move is not a resize", resized.isEmpty());
    }

    @Test
    public void placeChildren_shouldRecordOnlyTheChildrenWhoseSizeChanged() {
        // Mixed call: the discriminating shape. A pass that recorded the whole placed list, or
        // none of it, passes each single-child pin above and fails this one.
        IDiagramModelObject kept = archObject("kept", 0, 0, 100, 50);
        IDiagramModelObject widened = archObject("widened", 0, 0, 100, 50);
        IDiagramModelObject onlyMoved = archObject("onlyMoved", 0, 0, 100, 50);
        Map<String, Command> resized = new LinkedHashMap<>();

        NestedLayoutOperations.placeChildren(
                List.of(kept, widened, onlyMoved),
                List.of(new int[]{0, 0, 100, 50}, new int[]{0, 60, 260, 50},
                        new int[]{0, 120, 100, 50}),
                resized, Map.of());

        assertEquals("exactly the one resized child is named", 1, resized.size());
        assertTrue("and it is the widened one", resized.containsKey(widened.getId()));
    }

    @Test
    public void placeChildren_shouldCompareAgainstTheQueuedSizeNotThePreBatchBounds() {
        // Inside an open batch getBounds() is a pre-batch read. A child an earlier operation of
        // the same batch already widened to 400 must NOT be reported as resized when this
        // placement lands it at that same 400 — the queued size is what it effectively has.
        IDiagramModelObject child = archObject("queued", 0, 0, 120, 55);
        Map<String, int[]> sameBatchBounds = Map.of(child.getId(), new int[]{0, 0, 400, 55});
        Map<String, Command> resized = new LinkedHashMap<>();

        NestedLayoutOperations.placeChildren(
                List.of(child), List.of(new int[]{10, 10, 400, 55}), resized, sameBatchBounds);

        assertTrue("a placement matching the size the batch already queued is not a resize",
                resized.isEmpty());
    }

    @Test
    public void placeChildren_shouldRecordAgainstTheQueuedSizeWhenThePlacementDiffersFromIt() {
        // The other half of the same frame: a placement that matches the PRE-BATCH bounds but
        // differs from the queued size IS a resize. A comparison against getBounds() reports
        // nothing here, which is the defect the effective-rectangle frame exists to prevent.
        IDiagramModelObject child = archObject("clobbered", 0, 0, 120, 55);
        Map<String, int[]> sameBatchBounds = Map.of(child.getId(), new int[]{0, 0, 400, 55});
        Map<String, Command> resized = new LinkedHashMap<>();

        NestedLayoutOperations.placeChildren(
                List.of(child), List.of(new int[]{10, 10, 120, 55}), resized, sameBatchBounds);

        assertTrue("a placement that undoes a queued resize must be reported",
                resized.containsKey(child.getId()));
    }

    // ====================================================================
    // BLOCK: WHAT THE GROUPED PASS ALREADY OBSERVES
    // ====================================================================
    //
    // MECHANISM UNDER TEST. buildGroupedLayoutCommands descends into nested containers, and its
    // grid gives every cell the width of the widest element in that container — so a narrow leaf
    // is stretched to a width its own siblings decided. The walk ALREADY records that in
    // resizedLeaves. Nothing new is observed for the grouped tool; only the reporting was unbuilt,
    // and these pins are what makes the facade's read of that value meaningful rather than a
    // forwarding of something untested.
    //
    // The disjointness matters as much as the population: a container the walk DESCENDED INTO is
    // re-fitted for a different reason and is reported as a fitted container, never as a stretched
    // leaf. The two collections are disjoint by construction and the second pin holds that.

    @Test
    public void buildGroupedLayoutCommands_shouldRecordTheLeavesItResized() {
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 900, 700);
        IDiagramModelObject narrow = archObject("narrow", 0, 0, 60, 55);
        IDiagramModelObject wide = archObject("wide", 200, 0, 420, 55);
        root.getChildren().add(narrow);
        root.getChildren().add(wide);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildGroupedLayoutCommands(root, 20, PADDING, "DOWN");

        // This pass decides a leaf's width itself rather than preserving the stored one, so both
        // leaves land at a width nobody asked for and both are recorded.
        assertTrue("a leaf this pass re-sized must be recorded",
                result.resizedLeaves().containsKey(narrow.getId()));
        assertTrue(result.resizedLeaves().containsKey(wide.getId()));
        for (IDiagramModelObject leaf : new IDiagramModelObject[]{narrow, wide}) {
            assertEquals("the recorded rectangle must be the LANDED one, not the leaf's own",
                    pendingBox(result, leaf)[2],
                    ((UpdateViewObjectCommand) result.resizedLeaves().get(leaf.getId()))
                            .getNewWidth());
        }
    }

    @Test
    public void buildGroupedLayoutCommands_shouldRecordNothing_whenEveryLeafAlreadyHasTheSizeItComputes() {
        // The discrimination, calibrated by the pass itself rather than by hard-coded widths: run
        // it, APPLY what it decided, then run it again. The second pass computes the same sizes,
        // every leaf already has them, and a genuine comparison therefore records nothing. A
        // collector that recorded every child it placed passes the pin above and fails this one.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 900, 700);
        IDiagramModelObject narrow = archObject("narrow", 0, 0, 60, 55);
        IDiagramModelObject wide = archObject("wide", 200, 0, 420, 55);
        root.getChildren().add(narrow);
        root.getChildren().add(wide);

        NestedLayoutOperations.NestedLayoutResult first =
                NestedLayoutOperations.buildGroupedLayoutCommands(root, 20, PADDING, "DOWN");
        assertFalse("fixture guard: the first pass must actually have re-sized something",
                first.resizedLeaves().isEmpty());
        for (Command cmd : first.commands()) {
            if (cmd instanceof UpdateViewObjectCommand move) {
                move.getDiagramObject().setBounds(move.getNewX(), move.getNewY(),
                        move.getNewWidth(), move.getNewHeight());
            }
        }

        NestedLayoutOperations.NestedLayoutResult second =
                NestedLayoutOperations.buildGroupedLayoutCommands(root, 20, PADDING, "DOWN");

        assertTrue("every leaf already holds the size this pass computes, so it re-sized none of "
                + "them and must record none. Recorded: " + second.resizedLeaves().keySet(),
                second.resizedLeaves().isEmpty());
    }

    @Test
    public void buildGroupedLayoutCommands_shouldKeepFittedContainersOutOfTheStretchedLeaves() {
        // A container the walk descends into is re-fitted to its own contents. That is a different
        // fact from a leaf stretched to a cell, and the two are reported separately — so a
        // container must never appear in both.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 900, 700);
        IDiagramModelArchimateObject container = archObject("container", 0, 0, 100, 100);
        container.getChildren().add(archObject("deep-a", 0, 0, 300, 55));
        container.getChildren().add(archObject("deep-b", 0, 100, 120, 55));
        root.getChildren().add(container);
        root.getChildren().add(archObject("leaf", 400, 0, 60, 55));

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildGroupedLayoutCommands(root, 20, PADDING, "DOWN");

        assertTrue("the container the walk descended into must be reported as fitted",
                result.fittedContainers().containsKey(container.getId()));
        assertTrue("...and must NOT also be reported as a stretched leaf",
                !result.resizedLeaves().containsKey(container.getId()));
    }

    // ====================================================================
    // BLOCK: THE SIZE A RECURSIVE LAYOUT FEEDS ITSELF, INSIDE AN OPEN BATCH
    // ====================================================================
    //
    // MECHANISM UNDER TEST. resolveChildSize overrides only the axis the caller named and falls
    // back to the child's stored bounds on the other. Inside an open batch that fallback is a
    // PRE-BATCH read: none of the batch's own commands have run, so it measures the rectangle the
    // child is about to stop having. Its result is then handed to effectiveDims as compoundDims,
    // which prefers compoundDims by contract — correct for a size this pass genuinely computed,
    // wrong for a stale read wearing one's clothing. The queued size is discarded and a rectangle
    // derived from the pre-batch one is written in its place.
    //
    // Three fallbacks reach it by different routes and each gets its own pin: elementHeight alone
    // leaves the WIDTH stale, elementWidth alone and autoWidth alone leave the HEIGHT stale. A
    // fourth route needs no override at all — a container the walk refused to descend into returns
    // its stored size unconditionally.
    //
    // The fix is on resolveChildSize, not on effectiveDims: once the size is derived from the
    // effective rectangle, compoundDims really IS the later write and the contract needs no
    // change. The last pin in this block holds that contract from the other side.

    @Test
    public void resolveChildSize_shouldKeepTheQueuedWidth_whenOnlyElementHeightIsOverridden() {
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 900, 700);
        IDiagramModelObject leaf = archObject("leaf", 0, 0, 200, 100);
        root.getChildren().add(leaf);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root, "column",
                        20, PADDING, null, 80, false, null, false,
                        Map.of(leaf.getId(), new int[]{0, 0, 400, 300}));

        int[] box = pendingBox(result, leaf);
        assertEquals("the caller overrode the HEIGHT only, so the width must come from what the "
                + "batch queued (400) — not from the pre-batch 200 the stored bounds still hold",
                400, box[2]);
        assertEquals("the overridden axis is unaffected: an override is not the defect", 80, box[3]);
    }

    @Test
    public void resolveChildSize_shouldKeepTheQueuedHeight_whenOnlyElementWidthIsOverridden() {
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 900, 700);
        IDiagramModelObject leaf = archObject("leaf", 0, 0, 200, 100);
        root.getChildren().add(leaf);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root, "column",
                        20, PADDING, 150, null, false, null, false,
                        Map.of(leaf.getId(), new int[]{0, 0, 400, 300}));

        int[] box = pendingBox(result, leaf);
        assertEquals("the overridden axis", 150, box[2]);
        assertEquals("the other axis is a fallback, and inside a batch it must fall back to the "
                + "QUEUED height (300), not the pre-batch 100", 300, box[3]);
    }

    @Test
    public void resolveChildSize_shouldKeepTheQueuedHeight_whenOnlyAutoWidthIsSet() {
        // autoWidth computes the width from the label, so it is an override like any other and the
        // computed width must still win. It is the HEIGHT that has no override and falls back.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 900, 700);
        IDiagramModelObject leaf = archObject("Payments Reconciliation Function", 0, 0, 200, 100);
        root.getChildren().add(leaf);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root, "column",
                        20, PADDING, null, null, true, null, false,
                        Map.of(leaf.getId(), new int[]{0, 0, 400, 300}));

        int[] box = pendingBox(result, leaf);
        assertEquals("the computed width still wins over the queued one — overriding is not the "
                + "defect, reading past the queue on the axis nobody overrode is",
                GroupLayoutCalculator.computeAutoWidth("Payments Reconciliation Function"),
                box[2]);
        assertEquals("the height nobody overrode must come from the queue", 300, box[3]);
    }

    @Test
    public void resolveChildSize_shouldKeepTheQueuedSizeOfAContainerTheWalkDidNotDescendInto() {
        // The depth cap returns the container's stored size unconditionally, so that arm is stale
        // on BOTH axes rather than on the one the caller left alone. Eleven nesting levels: the
        // root is depth 0 and a child is refused at depth 11, one past MAX_RECURSIVE_LAYOUT_DEPTH.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 4000, 4000);
        IDiagramModelContainer cursor = root;
        for (int level = 1; level <= NestedLayoutOperations.MAX_RECURSIVE_LAYOUT_DEPTH; level++) {
            IDiagramModelGroup nested = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
            nested.setName("nest-" + level);
            nested.setBounds(0, 0, 3000, 3000);
            cursor.getChildren().add(nested);
            cursor = nested;
        }
        IDiagramModelGroup capped = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        capped.setName("capped");
        capped.setBounds(0, 0, 300, 300);
        capped.getChildren().add(archObject("untouched", 0, 0, 60, 55));
        cursor.getChildren().add(capped);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root, "column",
                        20, PADDING, 200, null, false, null, false,
                        Map.of(capped.getId(), new int[]{0, 0, 900, 700}));

        assertTrue("fixture guard: the walk must actually have hit the depth cap, or this pin is "
                + "measuring the ordinary leaf arm instead", result.depthCapHit());
        int[] box = pendingBox(result, capped);
        assertEquals("a container left in place keeps the size the BATCH gave it, not the one its "
                + "pre-batch bounds still hold — its untouched children fit the former",
                900, box[2]);
        assertEquals("both axes: the depth-cap arm overrides neither", 700, box[3]);
    }

    @Test
    public void effectiveDims_shouldStillPreferASizeThisPassGenuinelyComputed_overAQueuedOne() {
        // The other side of the fix. A container the walk DID descend into contributes the size its
        // own fit produced, and that value must keep outranking whatever the batch queued for it —
        // it is the later write. Making resolveChildSize queue-aware must not be mistaken for
        // narrowing this rule: the fit here exceeds the queued rectangle and must survive intact.
        IDiagramModelGroup root = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        root.setBounds(0, 0, 4000, 4000);
        IDiagramModelGroup nested = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        nested.setName("nested");
        nested.setBounds(0, 0, 100, 100);
        for (int i = 0; i < 4; i++) {
            nested.getChildren().add(archObject("wide-" + i, 0, 0, 600, 200));
        }
        root.getChildren().add(nested);

        NestedLayoutOperations.NestedLayoutResult result =
                NestedLayoutOperations.buildRecursiveLayoutCommands(root, root, "column",
                        20, PADDING, null, null, false, null, false,
                        Map.of(nested.getId(), new int[]{0, 0, 400, 300}));

        int[] box = pendingBox(result, nested);
        assertTrue("the fit must exceed the queued rectangle, or the pin cannot tell the two "
                + "sources apart: got " + box[2] + "x" + box[3],
                box[2] > 400 && box[3] > 300);
    }

    // ---- helpers ----

    private static IDiagramModelArchimateObject archObject(String name, int x, int y, int w, int h) {
        IDiagramModelArchimateObject obj =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        com.archimatetool.model.IApplicationComponent element =
                IArchimateFactory.eINSTANCE.createApplicationComponent();
        element.setName(name);
        obj.setArchimateElement(element);
        obj.setBounds(x, y, w, h);
        return obj;
    }

    /** The [x, y, w, h] the pass decided for one object, read back from its emitted command. */
    private static int[] pendingBox(NestedLayoutOperations.NestedLayoutResult result,
            IDiagramModelObject obj) {
        for (int i = result.commands().size() - 1; i >= 0; i--) {
            if (result.commands().get(i) instanceof UpdateViewObjectCommand cmd
                    && cmd.getDiagramObject() == obj) {
                return new int[]{cmd.getNewX(), cmd.getNewY(),
                        cmd.getNewWidth(), cmd.getNewHeight()};
            }
        }
        throw new AssertionError("no layout command was emitted for the object under test");
    }
}
