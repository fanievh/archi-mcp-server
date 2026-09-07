package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.INode;

import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;

/**
 * The two layout tools that <em>rejected</em> a view built from ArchiMate {@code Grouping}
 * elements, rather than merely skipping it.
 *
 * <p>{@code auto-layout-and-route} in grouped mode answered "mode='grouped' requires a view with
 * groups" and {@code adjust-view-spacing} answered "This view has no groups with children" — both
 * false statements about a view whose top level held nothing but populated containers, and both
 * unfalsifiable by an agent that cannot see the canvas. A silent skip is recoverable; a confident
 * denial sends the caller looking for a view that does not exist.
 *
 * <p><b>Why this class is separate from {@code TopLevelGroupingArrangementTest}.</b> Both tools run
 * a real routing/assessment pass and both undo through a live {@link CommandStack}, so this class
 * needs a display and an adapter the arrangement tests do not. Keeping them apart lets the
 * {@code arrange-groups} coverage stay in the headless lane instead of following these two into the
 * display-required manifest.
 */
public class TopLevelGroupingSiblingLayoutToolsTest {

    private static final String SESSION = "top-level-grouping-siblings-session";
    private static final String VIEW_ID = "view-tlg-siblings";

    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private IArchimateModel model;

    @Before
    public void setUp() {
        stubModelManager = new StubEditorModelManager();
        model = groupingOnlyFixture();
        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        // The accessor undoes its own temporary dispatches through the model's CommandStack
        // adapter, so overriding the dispatch methods alone is not enough — without the adapter
        // every one of these tools fails on plumbing before it reaches the behaviour under test.
        model.setAdapter(CommandStack.class, stack);

        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcherFor(model, stack));
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    @Test
    public void shouldNotRejectAGroupingOnlyView_whenAutoLayoutAndRouteRunsInGroupedMode() {
        try {
            accessor.autoLayoutAndRoute(SESSION, VIEW_ID, "grouped", "DOWN", 50, null, null);
        } catch (ModelAccessException e) {
            fail("grouped mode must not claim a view of populated Grouping elements has no groups. "
                    + "Actual: " + e.getMessage());
        }
    }

    // ==================== the leaves grouped mode stretches ====================
    //
    // MECHANISM. The recursive descent decides a leaf's size itself rather than preserving the
    // stored one, so a leaf lands at a width nobody asked for. The walk ALREADY recorded that in
    // NestedLayoutResult.resizedLeaves — the observation was built, correct on this path, and then
    // read past at the call site, which took fittedContainers and depthCapHit and dropped it. The
    // only reader of resizedLeaves() in the whole plugin was layout-within-group.
    //
    // These pins are end-to-end and therefore live with this tool's other pins, in the display
    // lane: grouped mode runs a real routing and assessment pass. The observation's own arithmetic
    // is pinned headlessly against buildGroupedLayoutCommands, which is the only lane that runs on
    // every push.

    @Test
    public void shouldNameTheLeavesItResized_whenAutoLayoutAndRouteRunsInGroupedMode() {
        AutoLayoutAndRouteResultDto dto = accessor
                .autoLayoutAndRoute(SESSION, VIEW_ID, "grouped", "DOWN", 50, null, null).entity();

        assertFalse("grouped mode re-sizes the leaves it lays out, and the observation for it was "
                + "already computed and then discarded at the call site. resizedElements was: "
                + dto.resizedElements(), dto.resizedElements().isEmpty());

        for (MovedViewObjectDto sized : dto.resizedElements()) {
            IDiagramModelObject live = findInView(sized.viewObjectId());
            assertNotNull("grouped mode reported an object not in the view: "
                    + sized.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals(sized.name() + ": x", actual.getX(), sized.newX());
            assertEquals(sized.name() + ": y", actual.getY(), sized.newY());
            assertEquals(sized.name() + ": width", actual.getWidth(), sized.newWidth());
            assertEquals(sized.name() + ": height", actual.getHeight(), sized.newHeight());
        }
    }

    /**
     * The containers the descent re-fitted are reported separately and must not be duplicated into
     * the leaf list. A reader who saw the same id in both would have no way to tell which rectangle
     * to believe.
     */
    @Test
    public void shouldKeepFittedContainersOutOfTheResizedLeaves_inGroupedMode() {
        stubModelManager.setModels(List.of(nestedFixture(true)));
        IArchimateModel nested = stubModelManager.getModels().get(0);
        CommandStack nestedStack = new CommandStack();
        nested.setAdapter(CommandStack.class, nestedStack);
        accessor.dispose();
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcherFor(nested, nestedStack));

        AutoLayoutAndRouteResultDto dto = accessor
                .autoLayoutAndRoute(SESSION, VIEW_ID, "grouped", "DOWN", 50, null, null).entity();

        assertFalse("fixture guard: the descent must actually have fitted a container",
                dto.nestedContainersFitted().isEmpty());
        assertFalse("fixture guard: and must actually have stretched a leaf, or the disjointness "
                + "loop below runs zero times and proves nothing",
                dto.resizedElements().isEmpty());
        for (MovedViewObjectDto fitted : dto.nestedContainersFitted()) {
            for (MovedViewObjectDto sized : dto.resizedElements()) {
                assertNotEquals("a container the walk descended into is reported as fitted and "
                        + "never as a stretched leaf: " + fitted.viewObjectId(),
                        fitted.viewObjectId(), sized.viewObjectId());
            }
        }
    }

    /**
     * FLAT mode never reaches the recursive descent, so the list is empty there and omitted from
     * the wire. Without this the field could be read as a property of the tool rather than of one
     * of its two modes.
     */
    @Test
    public void shouldNameNoResizedLeaf_whenAutoLayoutAndRouteRunsInFlatMode() throws Exception {
        AutoLayoutAndRouteResultDto dto = accessor
                .autoLayoutAndRoute(SESSION, VIEW_ID, "flat", "DOWN", 50, null, null).entity();

        // The flat construction site reports mode "auto", so mode() cannot witness the path. The
        // descent's OTHER output can: nestedContainersFitted is populated only by the recursion,
        // so an empty one is evidence the recursion did not run.
        assertTrue("fixture guard: flat mode must not have descended, or 'empty' proves nothing "
                + "about the mode. nestedContainersFitted was: " + dto.nestedContainersFitted(),
                dto.nestedContainersFitted().isEmpty());
        assertTrue("flat mode does not descend, so it stretches no leaf. resizedElements was: "
                + dto.resizedElements(), dto.resizedElements().isEmpty());

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertFalse("an empty list must be omitted from the wire, not serialized as []. "
                + "Response was: " + json, json.contains("resizedElements"));
    }

    /**
     * REPRODUCTION. Grouped mode is not one pass but three, dispatched and merged in order:
     * the recursive layout, then an element-reorder pass, then routing. The reorder pass re-lays
     * out every child of every group it reorders — with autoWidth on — and writes its own commands.
     * Those are appended AFTER the layout pass's, so for a reordered group the LAST command per
     * child is the reorder pass's and that is the rectangle the child actually lands at.
     *
     * <p>{@code resizedLeaves} is frozen when the layout pass returns, before the reorder pass has
     * run. On a view where a group is reordered, the report therefore names the rectangle the
     * FIRST pass proposed while the model holds the SECOND — the same defect the spacing tool was
     * fixed for, in a tool where it went unnoticed because the corpus this was first driven on
     * happened to reorder nothing.</p>
     */
    @Test
    public void shouldReportTheLandedRectangle_whenTheReorderPassMovesALeafTheLayoutPassPlaced() {
        useCrossedFixture();

        AutoLayoutAndRouteResultDto dto = accessor
                .autoLayoutAndRoute(SESSION, VIEW_ID, "grouped", "DOWN", 50, null, null).entity();

        assertFalse("fixture guard: the descent must have re-sized leaves at all",
                dto.resizedElements().isEmpty());

        for (MovedViewObjectDto sized : dto.resizedElements()) {
            IDiagramModelObject live = findInView(sized.viewObjectId());
            assertNotNull("reported an object not in the view: " + sized.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals(sized.name() + ": x", actual.getX(), sized.newX());
            assertEquals(sized.name() + ": y", actual.getY(), sized.newY());
            assertEquals(sized.name() + ": width", actual.getWidth(), sized.newWidth());
            assertEquals(sized.name() + ": height", actual.getHeight(), sized.newHeight());
        }
    }

    /**
     * Two groups of FOUR, cross-connected in an order that gives the reorder pass something to
     * reduce, so the run genuinely reaches the second pass instead of stopping at its guard.
     *
     * <p>Four, not three, and deliberately: the reorder pass picks its arrangement from the child
     * count, and at three or fewer it takes the row arm. Only at four does it reach the GRID arm —
     * which, unlike the recursive layout pass's grid, sizes every cell to the widest element in the
     * whole group. That difference is what lets the reorder pass re-size a child the layout pass
     * did not, so a three-child fixture cannot exercise the second half of this report at all.
     * The labels are deliberately of very different lengths, since both passes derive a leaf's
     * width from its label.</p>
     */
    private void useCrossedFixture() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelGroup left = addNativeGroup(m, v, "Left", "grp-left");
        IDiagramModelGroup right = addNativeGroup(m, v, "Right", "grp-right");
        left.setBounds(0, 0, 700, 700);
        right.setBounds(900, 0, 700, 700);

        String[] leftIds = {"l-one", "l-two", "l-three", "l-four"};
        String[] rightIds = {"r-one", "r-two", "r-three", "r-four"};
        String[] leftNames = {"L", "Left Child With A Very Long Label Indeed", "Lc", "Left Four"};
        String[] rightNames = {"R", "Right Child With A Very Long Label Too", "Rc", "Right Four"};
        for (int i = 0; i < 4; i++) {
            addNodeChild(m, left, leftNames[i], leftIds[i], 20, 40 + i * 100);
            addNodeChild(m, right, rightNames[i], rightIds[i], 20, 40 + i * 100);
        }
        // Crossed: left i connects to right (3 - i).
        for (int i = 0; i < 4; i++) {
            INode source = (INode) com.archimatetool.model.util.ArchimateModelUtils
                    .getObjectByID(m, leftIds[i]);
            INode target = (INode) com.archimatetool.model.util.ArchimateModelUtils
                    .getObjectByID(m, rightIds[3 - i]);
            com.archimatetool.model.IAssociationRelationship rel =
                    f.createAssociationRelationship();
            rel.setId("rel-cross-" + i);
            rel.setSource(source);
            rel.setTarget(target);
            m.getFolder(FolderType.RELATIONS).getElements().add(rel);

            com.archimatetool.model.IDiagramModelArchimateConnection conn =
                    f.createDiagramModelArchimateConnection();
            conn.setId("conn-cross-" + i);
            conn.setArchimateConcept(rel);
            conn.connect((IDiagramModelObject) com.archimatetool.model.util.ArchimateModelUtils
                            .getObjectByID(m, "obj-" + leftIds[i]),
                    (IDiagramModelObject) com.archimatetool.model.util.ArchimateModelUtils
                            .getObjectByID(m, "obj-" + rightIds[3 - i]));
        }

        stubModelManager.setModels(List.of(m));
        CommandStack crossedStack = new CommandStack();
        m.setAdapter(CommandStack.class, crossedStack);
        accessor.dispose();
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcherFor(m, crossedStack));
    }

    /**
     * Grouped mode inherits the widened endpoint resolution with no code of its own, because it
     * hands the same classifier the same three arguments {@code arrange-groups} does.
     *
     * <p>The candidate here is wired to each zone's own container BOX and to nothing inside
     * either, which is the shape that resolved to no zone at all before: the element-to-container
     * map holds a container's children and never the container, so both connections contributed
     * nothing and the element was left wherever it started. It must now land in the reserved
     * corridor between the two zones.</p>
     *
     * <p>End-to-end here rather than headlessly because grouped mode runs a real routing and
     * assessment pass. Its headless half — that the classifier answers the same on the arguments
     * this call site builds — is pinned in {@code TopLevelGroupingArrangementTest}, which runs on
     * every push. This tool reports no {@code unhandled} bucket, so the placement itself is the
     * only observable it has.</p>
     */
    @Test
    public void shouldPlaceACandidateWiredToTheZoneBoxes_whenAutoLayoutAndRouteRunsInGroupedMode() {
        useZoneBoxCandidateFixture();

        accessor.autoLayoutAndRoute(SESSION, VIEW_ID, "grouped", "DOWN", 50, null, null).entity();

        IBounds zoneA = findInView("obj-grp-a").getBounds();
        IBounds zoneB = findInView("obj-grp-b").getBounds();
        IBounds candidate = findInView("obj-dc").getBounds();
        String where = "Zone A " + describe(zoneA) + ", Zone B " + describe(zoneB)
                + ", candidate " + describe(candidate);

        assertTrue("the zones must be laid out one above the other for the corridor to exist. "
                + where, zoneA.getY() + zoneA.getHeight() <= zoneB.getY());
        assertTrue("the candidate must start below the first zone's foot, not stay where it was. "
                + where, candidate.getY() >= zoneA.getY() + zoneA.getHeight());
        assertTrue("and must finish above the second zone's head. " + where,
                candidate.getY() + candidate.getHeight() <= zoneB.getY());
    }

    private static String describe(IBounds b) {
        return b.getX() + "," + b.getY() + "," + b.getWidth() + "," + b.getHeight();
    }

    /** Two zones plus a top-level CommunicationNetwork wired to each zone's own container box. */
    private void useZoneBoxCandidateFixture() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = groupingOnlyFixture();
        IArchimateDiagramModel v = (IArchimateDiagramModel)
                m.getFolder(FolderType.DIAGRAMS).getElements().get(0);

        com.archimatetool.model.ICommunicationNetwork concept = f.createCommunicationNetwork();
        concept.setId("dc");
        concept.setName("Direct Connect");
        m.getFolder(FolderType.TECHNOLOGY).getElements().add(concept);
        IDiagramModelArchimateObject candidate = f.createDiagramModelArchimateObject();
        candidate.setId("obj-dc");
        candidate.setArchimateConcept(concept);
        candidate.setBounds(0, 0, 120, 55);
        v.getChildren().add(candidate);

        for (String zoneId : List.of("obj-grp-a", "obj-grp-b")) {
            com.archimatetool.model.IDiagramModelConnection conn =
                    f.createDiagramModelConnection();
            conn.setId("conn-dc-" + zoneId);
            conn.connect(candidate, findIn(v, zoneId));
        }

        stubModelManager.setModels(List.of(m));
        CommandStack boxStack = new CommandStack();
        m.setAdapter(CommandStack.class, boxStack);
        accessor.dispose();
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcherFor(m, boxStack));
    }

    /** Depth-first search for a view object by id in the model this test is currently driving. */
    private IDiagramModelObject findInView(String id) {
        for (IArchimateModel m : stubModelManager.getModels()) {
            for (Object diagram : m.getFolder(FolderType.DIAGRAMS).getElements()) {
                IDiagramModelObject hit = findIn((IDiagramModelContainer) diagram, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static IDiagramModelObject findIn(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId())) {
                return child;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = findIn(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    @Test
    public void shouldNotRejectAGroupingOnlyView_whenAdjustingViewSpacing() {
        try {
            accessor.adjustViewSpacing(SESSION, VIEW_ID, 20, null, null, false);
        } catch (ModelAccessException e) {
            fail("adjust-view-spacing must not claim a view of populated Grouping elements has no "
                    + "groups with children. Actual: " + e.getMessage());
        }
    }

    /**
     * A nested {@code Grouping} must still enclose contents a recursive spacing inflation pushed
     * apart, exactly as a nested native group does.
     *
     * <p>This is the test that settles what the native-group-only ancestor walk actually costs.
     * The concern was that a {@code Grouping} would fail to grow around contents that grew inside
     * it. It does not: the inflation recurses bottom-up, so each container re-fits itself from its
     * own children and its parent then re-fits from the children's pending sizes. The upward walk
     * has nothing left to correct at these depths, which is why leaving it typed to native groups
     * is behaviour-preserving rather than a concession.
     *
     * <p>Containment is asserted rather than exact geometry, because exact geometry legitimately
     * differs between the kinds: an element container reserves a taller title band than a native
     * group, and that difference propagates through padding detection into the fitted rectangle.
     * Pinning the pixels would pin that difference as if it were the defect.
     */
    @Test
    public void shouldKeepANestedGroupingEnclosingItsContents_afterRecursiveSpacingInflation() {
        assertNestedContainmentHolds(/*useNativeGroups=*/ true);
        assertNestedContainmentHolds(/*useNativeGroups=*/ false);
    }

    /**
     * Runs a recursive spacing inflation over a two-level nesting built from one container kind,
     * then asserts every container still encloses its own children.
     */
    private void assertNestedContainmentHolds(boolean useNativeGroups) {
        String kind = useNativeGroups ? "native group" : "Grouping element";
        IArchimateModel m = nestedFixture(useNativeGroups);
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(m));
        CommandStack localStack = new CommandStack();
        m.setAdapter(CommandStack.class, localStack);
        ArchiModelAccessorImpl local =
                new ArchiModelAccessorImpl(mgr, dispatcherFor(m, localStack));

        IDiagramModelObject inner = innerZoneOf(m);
        IDiagramModelObject secondHost = ((IDiagramModelContainer) inner).getChildren().get(1);
        int hostYBefore = secondHost.getBounds().getY();

        try {
            local.adjustViewSpacing(SESSION, VIEW_ID, 40, null, null, true);
        } finally {
            local.dispose();
        }

        // Discriminates: a run that inflated nothing would satisfy containment trivially, and the
        // test would be asserting the absence of an overflow that was never provoked.
        assertNotEquals("the inflation must actually have moved the nested contents (" + kind + ")",
                hostYBefore, secondHost.getBounds().getY());

        IArchimateDiagramModel v = (IArchimateDiagramModel)
                m.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        assertEnclosesChildren(kind, (IDiagramModelContainer) v.getChildren().get(0));
        assertEnclosesChildren(kind, (IDiagramModelContainer) inner);
    }

    /** Every child's relative rectangle must lie inside the container's own width and height. */
    private void assertEnclosesChildren(String kind, IDiagramModelContainer container) {
        IBounds box = ((IDiagramModelObject) container).getBounds();
        for (IDiagramModelObject child : container.getChildren()) {
            IBounds c = child.getBounds();
            assertTrue(kind + ": child '" + child.getName() + "' overflows its container's width ("
                            + (c.getX() + c.getWidth()) + " > " + box.getWidth() + ")",
                    c.getX() + c.getWidth() <= box.getWidth());
            assertTrue(kind + ": child '" + child.getName() + "' overflows its container's height ("
                            + (c.getY() + c.getHeight()) + " > " + box.getHeight() + ")",
                    c.getY() + c.getHeight() <= box.getHeight());
        }
    }

    /**
     * Flat mode lays a group out through ELK and then prepares every node ELK positioned. The node
     * collector emits a container BEFORE recursing into it, so the group's own new rectangle is
     * prepared first and its children's fits must measure against THAT — not against the rectangle
     * the group had before the pass.
     *
     * <p>The fixture undersizes the group deliberately: 140x90 cannot hold two stacked 120x55 nodes,
     * so ELK grows the group and moves it, and every child's fit is provoked. Given a per-child
     * working map each child re-measures the group at its pre-pass 140x90, finds an overflow, and
     * emits an absolute resize carrying the group's STALE x/y — which executes after ELK's own
     * command for the group and reverts the placement the pass just computed. The group ending
     * where it started is therefore the observable, and it is asserted rather than the size,
     * because the size the two paths converge on can coincide while the position cannot.</p>
     */
    @Test
    public void shouldKeepTheGroupWhereTheLayoutPutIt_whenFlatModeFitsItsNestedChildren() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setId("obj-elk-undersized");
        group.setName("Undersized");
        group.setBounds(400, 300, 140, 90);
        v.getChildren().add(group);
        addNodeChild(m, group, "Host One", "elk-node-1", 10, 10);
        addNodeChild(m, group, "Host Two", "elk-node-2", 10, 80);

        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(m));
        CommandStack localStack = new CommandStack();
        m.setAdapter(CommandStack.class, localStack);
        ArchiModelAccessorImpl local =
                new ArchiModelAccessorImpl(mgr, dispatcherFor(m, localStack));
        try {
            local.autoLayoutAndRoute(SESSION, VIEW_ID, "flat", "DOWN", 50, null, null);
        } finally {
            local.dispose();
        }

        IBounds after = group.getBounds();
        assertFalse("fixture guard: the pass must actually have grown the group, or there was no "
                        + "overflow to provoke a competing resize. Group was 140x90, is now "
                        + describe(after),
                after.getWidth() <= 140 && after.getHeight() <= 90);
        assertFalse("the group must keep the placement the layout computed, not the one it had "
                        + "before the pass. Group is at " + describe(after),
                after.getX() == 400 && after.getY() == 300);
        assertEnclosesChildren("ELK-laid group", group);
    }

    // ==================== fixtures ====================

    private MutationDispatcher dispatcherFor(IArchimateModel target, CommandStack targetStack) {
        MutationDispatcher dispatcher = new MutationDispatcher(() -> target) {
            @Override
            public void dispatchImmediate(Command command) {
                targetStack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
                targetStack.execute(toPlainCompound(command));
            }
            private Command toPlainCompound(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand plain = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        plain.add(toPlainCompound((Command) child));
                    }
                    return plain;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        return dispatcher;
    }

    /** Two Grouping elements, no native group — the shape the deployment guidance produces. */
    private IArchimateModel groupingOnlyFixture() {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        addGroupingElement(m, v, "Zone A", "grp-a");
        addGroupingElement(m, v, "Zone B", "grp-b");
        return m;
    }

    /**
     * One top-level container holding a nested container that itself holds two nodes, built from
     * either kind. The two variants are geometrically identical so the only difference between the
     * runs is the container type under test.
     */
    private IArchimateModel nestedFixture(boolean useNativeGroups) {
        IArchimateModel m = emptyModel();
        IArchimateDiagramModel v = viewOf(m);
        IDiagramModelContainer outer = useNativeGroups
                ? addNativeGroup(m, v, "Zone A", "grp-a")
                : addGroupingElement(m, v, "Zone A", "grp-a");

        IDiagramModelContainer inner = useNativeGroups
                ? nativeGroupIn(outer, "Inner Zone")
                : groupingElementIn(m, outer, "Inner Zone");

        addNodeChild(m, inner, "Inner Host One", "inner-node-1", 10, 30);
        addNodeChild(m, inner, "Inner Host Two", "inner-node-2", 10, 95);
        return m;
    }

    private IDiagramModelContainer groupingElementIn(
            IArchimateModel m, IDiagramModelContainer parent, String name) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IGrouping concept = f.createGrouping();
        concept.setId("grp-inner");
        concept.setName(name);
        m.getFolder(FolderType.OTHER).getElements().add(concept);
        IDiagramModelArchimateObject inner = f.createDiagramModelArchimateObject();
        inner.setId("obj-grp-inner");
        inner.setArchimateConcept(concept);
        inner.setBounds(10, 10, 200, 160);
        parent.getChildren().add(inner);
        return inner;
    }

    private IDiagramModelContainer nativeGroupIn(IDiagramModelContainer parent, String name) {
        IDiagramModelGroup inner = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        inner.setId("obj-grp-inner");
        inner.setName(name);
        inner.setBounds(10, 10, 200, 160);
        parent.getChildren().add(inner);
        return inner;
    }

    private IDiagramModelGroup addNativeGroup(
            IArchimateModel m, IArchimateDiagramModel v, String name, String id) {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setId("obj-" + id);
        group.setName(name);
        group.setBounds(0, 0, 200, 150);
        v.getChildren().add(group);
        addNodeChild(m, group, name + " Host", id + "-node", 10, 30);
        return group;
    }

    private IDiagramModelObject innerZoneOf(IArchimateModel m) {
        IArchimateDiagramModel v = (IArchimateDiagramModel)
                m.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        IDiagramModelContainer outer = (IDiagramModelContainer) v.getChildren().get(0);
        for (IDiagramModelObject child : outer.getChildren()) {
            if ("obj-grp-inner".equals(child.getId())) {
                return child;
            }
        }
        throw new AssertionError("nested Grouping not found under the outer zone");
    }

    private IArchimateModel emptyModel() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setName("Top-Level Grouping Sibling Tools");
        m.setId("model-tlg-siblings");
        m.setDefaults();
        return m;
    }

    private IArchimateDiagramModel viewOf(IArchimateModel m) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateDiagramModel v = f.createArchimateDiagramModel();
        v.setId(VIEW_ID);
        v.setName("Zones View");
        m.getFolder(FolderType.DIAGRAMS).getElements().add(v);
        return v;
    }

    private IDiagramModelArchimateObject addGroupingElement(
            IArchimateModel m, IArchimateDiagramModel v, String name, String id) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IGrouping concept = f.createGrouping();
        concept.setId(id);
        concept.setName(name);
        m.getFolder(FolderType.OTHER).getElements().add(concept);

        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(concept);
        obj.setBounds(0, 0, 200, 150);
        v.getChildren().add(obj);

        addNodeChild(m, obj, name + " Host", id + "-node", 10, 30);
        return obj;
    }

    private void addNodeChild(IArchimateModel m, IDiagramModelContainer parent,
            String name, String id, int x, int y) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode node = f.createNode();
        node.setId(id);
        node.setName(name);
        m.getFolder(FolderType.TECHNOLOGY).getElements().add(node);

        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(node);
        obj.setBounds(x, y, 120, 55);
        parent.getChildren().add(obj);
    }

    // ==================== harness ====================

    /** Minimal {@link IEditorModelManager} stub — models plus listener registration only. */
    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        @Override
        public List<IArchimateModel> getModels() {
            return models;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

        @SuppressWarnings("unused")
        void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
            PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
            for (PropertyChangeListener listener : new ArrayList<>(listeners)) {
                listener.propertyChange(evt);
            }
        }

        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel model) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel model) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel model, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel model) { return false; }
        @Override public boolean saveModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel model) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object source, String prop, Object oldValue, Object newValue) {}
    }
}
