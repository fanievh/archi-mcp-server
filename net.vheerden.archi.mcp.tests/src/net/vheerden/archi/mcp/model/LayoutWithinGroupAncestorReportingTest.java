package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.INode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;

/**
 * Pins the two things {@code layout-within-group} used to say without having observed them.
 *
 * <p><strong>The count with no geometry.</strong> Laying a group out with {@code recursive} walks up
 * the parent chain re-fitting every ancestor. The response said {@code ancestorsResized: 1} and
 * never what that ancestor became — measured, a group at 400x400 was re-fitted to 100x144 and the
 * only number in the response was the digit 1. A count reports <em>that</em> something changed and
 * leaves an agent that cannot see the canvas unable to say <em>where</em> anything is.</p>
 *
 * <p><strong>The outcome that was really a request.</strong> {@code groupResized} was populated from
 * the caller's own {@code autoResize} argument. Measured: two identical calls on a group already
 * fitted to its children, the group unchanged at 80x74 across both, and both responses claiming
 * {@code groupResized: true}. A field named as an outcome must not be sourced from the ask, and the
 * approval card repeated the same claim.</p>
 *
 * <p><strong>The change with no field at all.</strong> Both arms write a full rectangle per child,
 * and a grid gives every cell its column's width — so a leaf sharing a column with a container that
 * fitted wide is stretched to that width. Measured on a real conversion: six leaves went from 120px
 * to as much as 2230px, and the response carried only {@code elementsRepositioned}, a count of
 * <em>moves</em> that silently also covered the resizes. {@code resizedElements} is the third
 * member of the family, on the same observation footing as the other two.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>A real GEF {@link CommandStack} driven over an ordered compound, with the production
 * {@code NonNotifyingCompoundCommand} rebuilt as a plain {@link CompoundCommand} because its
 * {@code execute()} dereferences {@code IEditorModelManager.INSTANCE}. Every add passes explicit
 * bounds so nothing reaches {@code ElementSizer}'s display-bound measurement.</p>
 */
public class LayoutWithinGroupAncestorReportingTest {

    private static final String SESSION = "layout-within-group-ancestor-report-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private boolean approvalMode;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
        approvalMode = false;

        model = factory.createArchimateModel();
        model.setName("Ancestor Reporting Fixture");
        model.setId("model-ancestor-report");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Reporting");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 12; i++) {
            IBusinessActor a = factory.createBusinessActor();
            a.setId("actor-" + i);
            a.setName("Actor " + i);
            business.getElements().add(a);
        }

        // The two element-container kinds the upward walk excludes: a Grouping (the ancestor the
        // published text already mentions) and a Node (the container the original report named).
        IFolder other = model.getFolder(FolderType.OTHER);
        for (int i = 1; i <= 2; i++) {
            IGrouping g = factory.createGrouping();
            g.setId("grouping-" + i);
            g.setName("Zone " + i);
            other.getElements().add(g);
        }
        IFolder technology = model.getFolder(FolderType.TECHNOLOGY);
        for (int i = 1; i <= 2; i++) {
            INode n = factory.createNode();
            n.setId("node-" + i);
            n.setName("Node " + i);
            technology.getElements().add(n);
        }

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
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
        dispatcher.setApprovalModeProvider(() -> approvalMode);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label, x, y, w, h, parentId, null, null)
                .entity().viewObjectId();
    }

    /** Returns the VIEW OBJECT's id, which is not the element's — the leaf pins report on the former. */
    private String add(String actorId, int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), actorId, x, y, w, h, false, parentId,
                null, null).entity().viewObject().viewObjectId();
    }

    /**
     * An ArchiMate-element container — the kind {@code layout-within-group} accepts as a container
     * and the upward walk refuses to start from. Bounds are explicit for the same reason every
     * other add here is: nothing may reach {@code ElementSizer}'s display-bound measurement.
     */
    private String elementContainer(String elementId, int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), elementId, x, y, w, h, false, parentId,
                null, null).entity().viewObject().viewObjectId();
    }

    private MutationResult<LayoutWithinGroupResultDto> layout(String groupId, boolean autoResize,
            boolean recursive) {
        return accessor.layoutWithinGroup(SESSION, view.getId(), groupId, "column", 20, 10,
                null, null, autoResize, false, null, recursive, false);
    }

    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId())) {
                return child;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /**
     * The general call. The {@link #layout(String, boolean, boolean)} helper above fixes the
     * arrangement at "column" with no size overrides, which is exactly the shape that never resizes
     * a leaf — so the leaf-resize fixtures need the other axes.
     */
    private MutationResult<LayoutWithinGroupResultDto> layout(String groupId, String arrangement,
            Integer elementWidth, Integer columns, boolean autoResize, boolean recursiveChildren) {
        return accessor.layoutWithinGroup(SESSION, view.getId(), groupId, arrangement, 20, 10,
                elementWidth, null, autoResize, false, columns, false, recursiveChildren);
    }

    private static MovedViewObjectDto resizedEntryFor(LayoutWithinGroupResultDto dto, String id) {
        for (MovedViewObjectDto entry : dto.resizedElements()) {
            if (id.equals(entry.viewObjectId())) {
                return entry;
            }
        }
        return null;
    }

    private static MovedViewObjectDto entryFor(LayoutWithinGroupResultDto dto, String id) {
        for (MovedViewObjectDto entry : dto.resizedAncestors()) {
            if (id.equals(entry.viewObjectId())) {
                return entry;
            }
        }
        return null;
    }

    /** Outer holds Inner, Inner holds two actors: laying Inner out makes Outer re-fit. */
    private String[] nest() {
        String outer = group("Outer", 0, 0, 400, 400, null);
        String inner = group("Inner", 10, 10, 100, 100, outer);
        add("actor-1", 10, 30, 60, 30, inner);
        add("actor-2", 10, 70, 60, 30, inner);
        return new String[]{outer, inner};
    }

    // ---- the count with no geometry -------------------------------------------------------------

    /**
     * The ancestor is named with the rectangle it landed at, checked field by field against the
     * model and against the wire. The fixture guards itself: the ancestor must actually have
     * changed size, or the assertion proves nothing.
     */
    @Test
    public void shouldNameEachAncestorItRefitted_withTheRectangleItLandedAt() throws Exception {
        String[] ids = nest();
        String outer = ids[0];
        int widthBefore = find(view, outer).getBounds().getWidth();

        LayoutWithinGroupResultDto dto = layout(ids[1], true, true).entity();
        IBounds after = find(view, outer).getBounds();

        assertTrue("fixture must actually re-fit the ancestor, or this proves nothing: "
                + widthBefore + " -> " + after.getWidth(), after.getWidth() != widthBefore);

        MovedViewObjectDto reported = entryFor(dto, outer);
        assertNotNull("layout-within-group must name the ancestor it re-fitted. resizedAncestors "
                + "was: " + dto.resizedAncestors(), reported);
        assertEquals("ancestor: x", after.getX(), reported.newX());
        assertEquals("ancestor: y", after.getY(), reported.newY());
        assertEquals("ancestor: width", after.getWidth(), reported.newWidth());
        assertEquals("ancestor: height", after.getHeight(), reported.newHeight());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue("the rectangle must reach the wire. Response was: " + json,
                json.contains("\"resizedAncestors\""));
        assertTrue("the wire must carry the ancestor's id. Response was: " + json,
                json.contains(outer));
        assertTrue("the wire must carry its effective height (" + after.getHeight()
                + "). Response was: " + json,
                json.contains("\"newHeight\":" + after.getHeight()));
    }

    /**
     * The list and the count describe the same set — the list is the count with its geometry
     * restored, not a second, differently-scoped fact.
     */
    @Test
    public void shouldKeepTheCountAndTheListDescribingTheSameSet() {
        String[] ids = nest();

        LayoutWithinGroupResultDto dto = layout(ids[1], true, true).entity();

        assertTrue("fixture must re-fit at least one ancestor", dto.ancestorsResized() > 0);
        assertEquals("every counted ancestor must also be named",
                dto.ancestorsResized(), dto.resizedAncestors().size());
    }

    /** Nothing above the group, nothing to report — and nothing on the wire either. */
    @Test
    public void shouldSerializeAsBefore_whenThereIsNoAncestorToRefit() throws Exception {
        String lone = group("Lone", 600, 0, 200, 200, null);
        add("actor-3", 10, 30, 60, 30, lone);

        LayoutWithinGroupResultDto dto = layout(lone, true, true).entity();

        assertEquals("a top-level group has no ancestor group", 0, dto.ancestorsResized());
        assertTrue("so nothing is named", dto.resizedAncestors().isEmpty());
        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("an empty list must be omitted from the wire, not serialized as []. "
                + "Response was: " + json, json.contains("resizedAncestors"));
    }

    /**
     * The mirror of the {@code groupResized} defect, in the field this sweep added. The upward walk
     * emits a resize command for every ancestor it visits, whether or not the computed rectangle
     * differs from the one already held — so a repeated layout re-reports an ancestor that did not
     * move. A list sourced from having <em>run the mechanism</em> is the same defect as a flag
     * sourced from the request that asked for it.
     */
    @Test
    public void shouldNotNameAnAncestorThatDidNotChange_whenTheLayoutIsRepeated() {
        String[] ids = nest();
        String outer = ids[0];

        LayoutWithinGroupResultDto first = layout(ids[1], true, true).entity();
        IBounds afterFirst = find(view, outer).getBounds();
        int w = afterFirst.getWidth();
        int h = afterFirst.getHeight();

        LayoutWithinGroupResultDto second = layout(ids[1], true, true).entity();
        IBounds afterSecond = find(view, outer).getBounds();

        assertFalse("the first call must actually re-fit the ancestor, or the pair proves nothing",
                first.resizedAncestors().isEmpty());
        assertEquals("fixture guard: the second call really does leave the ancestor alone",
                w + "x" + h, afterSecond.getWidth() + "x" + afterSecond.getHeight());
        assertNull("the second call changed nothing above the group, so it must name nothing. "
                + "resizedAncestors was: " + second.resizedAncestors(), entryFor(second, outer));
        assertEquals("and the count must agree with the list it is the count of",
                second.resizedAncestors().size(), second.ancestorsResized());
    }

    // ---- the outcome that was really a request ---------------------------------------------------

    /**
     * The defect, in the form it was measured: run the same layout twice on the same group. The
     * second call changes nothing — the group is already fitted — and must say so.
     */
    @Test
    public void shouldReportGroupResizedFalse_whenTheSizeDidNotActuallyChange() {
        String lone = group("Lone", 600, 0, 200, 200, null);
        add("actor-3", 10, 30, 60, 30, lone);

        LayoutWithinGroupResultDto first = layout(lone, true, false).entity();
        IBounds afterFirst = find(view, lone).getBounds();
        int w = afterFirst.getWidth();
        int h = afterFirst.getHeight();

        LayoutWithinGroupResultDto second = layout(lone, true, false).entity();
        IBounds afterSecond = find(view, lone).getBounds();

        assertTrue("the first call must actually resize it, or the pair proves nothing",
                first.groupResized());
        assertEquals("fixture guard: the second call really does leave the size alone",
                w + "x" + h, afterSecond.getWidth() + "x" + afterSecond.getHeight());
        assertFalse("the second call resized nothing, so it must not claim it did — the field is "
                + "an outcome and must not be sourced from the autoResize request",
                second.groupResized());
    }

    /** ...and it must still be true when the size genuinely changed. */
    @Test
    public void shouldReportGroupResizedTrue_whenTheSizeActuallyChanged() {
        String lone = group("Lone", 600, 0, 200, 200, null);
        add("actor-3", 10, 30, 60, 30, lone);

        LayoutWithinGroupResultDto dto = layout(lone, true, false).entity();

        assertTrue("the group really was re-fitted", dto.groupResized());
        assertEquals("and the reported width is the one the model holds",
                (Integer) find(view, lone).getBounds().getWidth(), dto.newGroupWidth());
    }

    /** With autoResize off nothing may be claimed, exactly as before. */
    @Test
    public void shouldReportGroupResizedFalse_whenAutoResizeWasNotRequested() {
        String lone = group("Lone", 600, 0, 200, 200, null);
        add("actor-3", 10, 30, 60, 30, lone);

        LayoutWithinGroupResultDto dto = layout(lone, false, false).entity();

        assertFalse("no resize was permitted, so none is claimed", dto.groupResized());
    }

    /**
     * The approval card carried the same echo and is fixed at the same time: the preview a human
     * reads before approving must state what the operation will do, not what was asked for.
     */
    @Test
    public void shouldStateTheObservationOnTheApprovalCard_notTheRequest() {
        String lone = group("Lone", 600, 0, 200, 200, null);
        add("actor-3", 10, 30, 60, 30, lone);
        layout(lone, true, false);

        approvalMode = true;
        MutationResult<LayoutWithinGroupResultDto> result = layout(lone, true, false);

        assertNotNull("the call must actually be a proposal, or this proves nothing",
                result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher()
                .getProposal(SESSION, result.proposalContext().proposalId());
        assertNotNull("the proposal must be retrievable", pending);
        assertEquals("the card must state the observation, not the autoResize request",
                Boolean.FALSE, pending.proposedChanges().get("groupResized"));
    }

    /**
     * The card a human reads before approving must mention the groups above the one named in the
     * request, since those are the changes they would not otherwise expect. Its sibling
     * {@code auto-route-connections} already surfaces its cascade on the card; this one did not.
     */
    @Test
    public void shouldMentionTheAncestorsOnTheApprovalCard() {
        String[] ids = nest();

        approvalMode = true;
        MutationResult<LayoutWithinGroupResultDto> result = layout(ids[1], true, true);

        assertNotNull("the call must actually be a proposal, or this proves nothing",
                result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher()
                .getProposal(SESSION, result.proposalContext().proposalId());
        assertEquals("the card must say how many groups above the target will be re-fitted",
                Integer.valueOf(1), pending.proposedChanges().get("ancestorsResized"));
    }

    // ---- the zero that could not be decoded ------------------------------------------------------

    /**
     * The case the original report hit and that no test in this repository covered: the caller
     * names an ArchiMate-element container, so the upward walk never starts. The response said
     * {@code ancestorsResized: 0} — true, and indistinguishable from "nothing needed doing".
     *
     * <p>Measured on the model that produced the report: a {@code Node} was grown from 841x1868 to
     * 1998x994 and its parent kept the rectangle it had, now too small for the child. The published
     * text disclosed the <em>other</em> exclusion — that the walk stops at a non-native ancestor —
     * which in that call never got the chance to fire.</p>
     */
    @Test
    public void shouldSayTheWalkNeverStarted_whenTheContainerIsAnArchimateElement() {
        String node = elementContainer("node-1", 0, 0, 300, 300, null);
        add("actor-1", 10, 30, 60, 30, node);
        add("actor-2", 10, 70, 60, 30, node);

        LayoutWithinGroupResultDto dto = layout(node, true, true).entity();

        assertEquals("the walk is typed to native view groups, so it does not run here",
                0, dto.ancestorsResized());
        assertEquals("...and the response must name that, not leave a bare zero the caller has to "
                + "guess at", "container-not-a-native-group", codeOf(dto.ancestorPropagation()));
    }

    /**
     * Precedence, proved where it is the only thing that can be observed. Both exclusions are live
     * at once — an element container whose own parent is a {@code Grouping}, the shape measured on
     * the originating model — and the one that must win is the one that is <em>true</em>: the walk
     * never started, so it cannot also have stopped somewhere.
     *
     * <p>A test per code does not discharge this. Precedence only shows where conditions collide.</p>
     */
    @Test
    public void shouldPreferTheContainerExclusion_whenTheAncestorWouldAlsoHaveStoppedIt() {
        String zone = elementContainer("grouping-1", 0, 0, 600, 600, null);
        String node = elementContainer("node-1", 10, 10, 300, 300, zone);
        add("actor-1", 10, 30, 60, 30, node);
        add("actor-2", 10, 70, 60, 30, node);

        LayoutWithinGroupResultDto dto = layout(node, true, true).entity();

        assertTrue("fixture guard: the container really is nested inside the Grouping, or the two "
                + "conditions are not both live",
                find(view, node).eContainer() instanceof IDiagramModelObject parent
                        && zone.equals(parent.getId()));
        assertEquals("both exclusions applied; the walk-never-started one must win, because "
                + "stopped-at-non-native-ancestor would describe a walk that never ran",
                "container-not-a-native-group", codeOf(dto.ancestorPropagation()));
    }

    /**
     * The signal a zero-only field could never have carried. The walk runs, re-fits a native
     * ancestor, and <em>then</em> meets a {@code Grouping} and stops — leaving that Grouping too
     * small for the group this call just grew. The published description already asserts exactly
     * that consequence and there was no observable for it at any count.
     */
    @Test
    public void shouldReportTheEarlyStop_evenWhenItAlreadyRefittedAnAncestor() {
        String zone = elementContainer("grouping-1", 0, 0, 600, 600, null);
        String outer = group("Outer", 10, 10, 400, 400, zone);
        String inner = group("Inner", 10, 10, 100, 100, outer);
        add("actor-1", 10, 30, 60, 30, inner);
        add("actor-2", 10, 70, 60, 30, inner);

        LayoutWithinGroupResultDto dto = layout(inner, true, true).entity();

        assertTrue("fixture must actually re-fit a native ancestor, or this proves nothing about "
                + "the non-zero case: ancestorsResized was " + dto.ancestorsResized(),
                dto.ancestorsResized() >= 1);
        assertEquals("the walk ended at the Grouping, which is now too small for the group it just "
                + "grew — and the count alone reads as unqualified success",
                "stopped-at-non-native-ancestor", codeOf(dto.ancestorPropagation()));
    }

    /** Nothing above the group: terminal, and a different answer from "the walk was blocked". */
    @Test
    public void shouldSayThereIsNothingAbove_whenTheGroupIsAtTheTopLevelOfTheView() {
        String lone = group("Lone", 600, 0, 200, 200, null);
        add("actor-3", 10, 30, 60, 30, lone);

        LayoutWithinGroupResultDto dto = layout(lone, true, true).entity();

        assertEquals("a top-level group has no ancestor group", 0, dto.ancestorsResized());
        assertEquals("and 'you are done' must not read the same as 'something stopped me'",
                "no-ancestor", codeOf(dto.ancestorPropagation()));
    }

    /** The walk ran the whole way to the view and changed something: the ordinary success. */
    @Test
    public void shouldSayItPropagated_whenTheWalkReachedTheViewHavingRefittedAnAncestor() {
        String[] ids = nest();

        LayoutWithinGroupResultDto dto = layout(ids[1], true, true).entity();

        assertTrue("fixture must re-fit at least one ancestor", dto.ancestorsResized() >= 1);
        assertEquals("propagated", codeOf(dto.ancestorPropagation()));
    }

    /** The walk ran and found nothing to do — terminal, and not the same as never having run. */
    @Test
    public void shouldSayEveryAncestorWasAlreadyFitted_whenTheLayoutIsRepeated() {
        String[] ids = nest();
        LayoutWithinGroupResultDto first = layout(ids[1], true, true).entity();

        LayoutWithinGroupResultDto second = layout(ids[1], true, true).entity();

        assertTrue("the first call must actually re-fit, or the pair proves nothing",
                first.ancestorsResized() >= 1);
        assertEquals("the second call changed nothing above the group", 0,
                second.ancestorsResized());
        assertEquals("all-ancestors-already-fitted", codeOf(second.ancestorPropagation()));
    }

    /** The caller's own argument is the terminal state, and it outranks every geometric reason. */
    @Test
    public void shouldSayItWasNotRequested_whenRecursiveWasNotSet() {
        String[] ids = nest();

        LayoutWithinGroupResultDto dto = layout(ids[1], true, false).entity();

        assertEquals("this zero is undone by changing the call, not the view",
                "not-requested", codeOf(dto.ancestorPropagation()));
    }

    /** {@code recursive} without {@code autoResize} is gated out before any geometry is read. */
    @Test
    public void shouldSayAutoResizeWasNotRequested_whenRecursiveWasSetWithoutIt() {
        String[] ids = nest();

        LayoutWithinGroupResultDto dto = layout(ids[1], false, true).entity();

        assertEquals("auto-resize-not-requested", codeOf(dto.ancestorPropagation()));
    }

    /**
     * The walk stops at a fixed nesting depth. That exit leaves groups still above the last one it
     * re-fitted, so it cannot honestly borrow either terminal code: {@code no-ancestor} and
     * {@code all-ancestors-already-fitted} both mean "you are done" and here you are not.
     */
    @Test
    public void shouldSayItHitTheNestingLimit_whenGroupsRemainAboveTheCap() {
        String current = group("L0", 0, 0, 4000, 4000, null);
        for (int i = 1; i <= NestedLayoutOperations.MAX_RECURSIVE_LAYOUT_DEPTH + 1; i++) {
            current = group("L" + i, 5, 5, 3800 - (i * 20), 3800 - (i * 20), current);
        }
        add("actor-1", 10, 30, 60, 30, current);
        add("actor-2", 10, 70, 60, 30, current);

        LayoutWithinGroupResultDto dto = layout(current, true, true).entity();

        assertEquals("more groups sit above the cap than the walk will visit, so neither terminal "
                + "code is true here", "depth-cap-reached", codeOf(dto.ancestorPropagation()));
        assertTrue("and it must arrive with a POSITIVE count, which is the same hazard the "
                + "non-native stop carries: the walk really did re-fit ancestors on its way to the "
                + "cap, so a caller reading only ancestorsResized sees unqualified success while "
                + "groups above are still unfitted. ancestorsResized was " + dto.ancestorsResized(),
                dto.ancestorsResized() > 0);
    }

    /**
     * The other half of the ambiguity, and the reason the published text says these two codes
     * report <em>either</em> count rather than giving a tally of which codes mean zero.
     *
     * <p>A native group sitting directly inside a {@code Grouping} stops the walk before it has
     * visited anything, so {@code stopped-at-non-native-ancestor} arrives with <b>zero</b> — the
     * same code the three-level fixture above produces with a positive count. Any claim of the form
     * "N of the codes mean zero" is therefore false for this code in one direction or the other,
     * which is why the contract is stated as a partition and pinned here from both sides.</p>
     */
    @Test
    public void shouldReportTheEarlyStopWithAZeroCount_whenItStoppedBeforeVisitingAnything() {
        String zone = elementContainer("grouping-1", 0, 0, 600, 600, null);
        String inner = group("Inner", 10, 10, 400, 400, zone);
        add("actor-1", 10, 30, 60, 30, inner);
        add("actor-2", 10, 70, 60, 30, inner);

        LayoutWithinGroupResultDto dto = layout(inner, true, true).entity();

        assertEquals("the walk started from a native group and met the Grouping immediately",
                "stopped-at-non-native-ancestor", codeOf(dto.ancestorPropagation()));
        assertEquals("having visited nothing, it re-fitted nothing — the SAME code as the "
                + "three-level fixture, with the opposite count", 0, dto.ancestorsResized());
    }

    /**
     * The count does not partition the reasons, which is the whole argument for shipping the reason
     * beside it. Pinned as a property over the fixtures rather than as a sentence in a doc comment,
     * because the sentence is what drifted: three surfaces carried three different tallies.
     */
    @Test
    public void shouldNotLetTheCountIdentifyTheReason_inEitherDirection() {
        String[] nested = nest();
        LayoutWithinGroupResultDto propagated = layout(nested[1], true, true).entity();

        String lone = group("Lone", 900, 0, 200, 200, null);
        add("actor-3", 10, 30, 60, 30, lone);
        LayoutWithinGroupResultDto none = layout(lone, true, true).entity();

        assertEquals("propagated is the one code that guarantees a positive count",
                "propagated", codeOf(propagated.ancestorPropagation()));
        assertTrue(propagated.ancestorsResized() > 0);
        assertEquals("no-ancestor is one of the five that always report zero",
                "no-ancestor", codeOf(none.ancestorPropagation()));
        assertEquals(0, none.ancestorsResized());
        assertEquals("a positive count does NOT imply the walk finished, and a zero does NOT imply "
                + "it was blocked — the two ambiguous codes are pinned from both sides by "
                + "shouldReportTheEarlyStop_evenWhenItAlreadyRefittedAnAncestor, "
                + "shouldReportTheEarlyStopWithAZeroCount_whenItStoppedBeforeVisitingAnything and "
                + "shouldSayItHitTheNestingLimit_whenGroupsRemainAboveTheCap",
                8, LayoutWithinGroupResultDto.ANCESTOR_PROPAGATION_REASONS.size());
    }

    /**
     * The format invariant, over every published value rather than the ones these fixtures happen
     * to reach: one line an agent can triage on, a stable leading code and a short phrase.
     */
    @Test
    public void shouldKeepEveryPropagationReasonShortAndCoded() {
        assertEquals("every reason this test loops over must also be reachable, and every reachable "
                + "reason must be in the published list",
                8, LayoutWithinGroupResultDto.ANCESTOR_PROPAGATION_REASONS.size());
        for (String reason : LayoutWithinGroupResultDto.ANCESTOR_PROPAGATION_REASONS) {
            assertTrue("a reason longer than 90 characters is prose, and this field ships on every "
                    + "response: " + reason, reason.length() <= 90);
            assertTrue("every reason must lead with a stable code so the field can be triaged "
                    + "without reading the phrase: " + reason,
                    reason.matches("^[a-z][a-z-]+: .+"));
        }
    }

    /**
     * The field ships on every response, including the overwhelmingly common one where nothing was
     * asked for. A field defaulted to absent beside a list of values it can take is a false
     * all-clear: the agent cannot tell "not applicable" from "this build does not report it".
     */
    @Test
    public void shouldPutThePropagationReasonOnTheWire_evenWhenNothingWasRequested() throws Exception {
        String lone = group("Lone", 600, 0, 200, 200, null);
        add("actor-3", 10, 30, 60, 30, lone);

        LayoutWithinGroupResultDto dto = layout(lone, true, false).entity();

        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue("the reason must reach the wire on the common call too. Response was: " + json,
                json.contains("\"ancestorPropagation\":\"not-requested:"));
    }

    /**
     * The card a human reads before approving carries the reason whatever it says — unlike
     * {@code ancestorsResized}, which is put on the card only when it is greater than zero and so
     * is absent in exactly the case the reader most needs explaining.
     */
    @Test
    public void shouldPutThePropagationReasonOnTheApprovalCard_unconditionally() {
        String node = elementContainer("node-1", 0, 0, 300, 300, null);
        add("actor-1", 10, 30, 60, 30, node);
        add("actor-2", 10, 70, 60, 30, node);

        approvalMode = true;
        MutationResult<LayoutWithinGroupResultDto> result = layout(node, true, true);

        assertNotNull("the call must actually be a proposal, or this proves nothing",
                result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher()
                .getProposal(SESSION, result.proposalContext().proposalId());
        Object reason = pending.proposedChanges().get("ancestorPropagation");
        assertNotNull("the card omits ancestorsResized when it is zero, so the reason is the only "
                + "thing left that can explain the zero. proposedChanges was: "
                + pending.proposedChanges(), reason);
        assertEquals("container-not-a-native-group", codeOf(String.valueOf(reason)));
    }

    /**
     * Inside a batch nothing is effective, so the response may only be read as a projection. The
     * assertion is therefore on the label — that the call really was queued — and on the reason's
     * shape, never on a geometric outcome the model does not yet hold.
     */
    @Test
    public void shouldStillCarryAWellFormedReason_whenTheCallIsQueuedInABatch() {
        String node = elementContainer("node-1", 0, 0, 300, 300, null);
        add("actor-1", 10, 30, 60, 30, node);
        add("actor-2", 10, 70, 60, 30, node);
        dispatcher.beginBatch(SESSION, "queued reason");

        MutationResult<LayoutWithinGroupResultDto> result = layout(node, true, true);

        assertTrue("the call must actually be queued, or this proves nothing about batch mode",
                result.isBatched());
        assertTrue("a queued response still describes which walk would have run: "
                + result.entity().ancestorPropagation(),
                result.entity().ancestorPropagation().matches("^[a-z][a-z-]+: .+"));
        dispatcher.endBatch(SESSION, false);
    }

    // ---- the change with no field at all --------------------------------------------------------

    /**
     * A container holding a wide nested container and a narrow leaf in the same grid column. The
     * column takes its widest member's width, and the widest member is the container — so the leaf
     * is written to a width nothing in the request named. The fixture guards itself: the leaf's
     * model width must actually have changed, or the assertion proves nothing.
     */
    @Test
    public void shouldNameALeafItStretchedToItsColumn_whenTheRecursionRefitsAContainerBesideIt()
            throws Exception {
        String outer = group("Region", 0, 0, 600, 600, null);
        String wide = group("Wide", 10, 10, 100, 100, outer);
        add("actor-1", 10, 30, 260, 30, wide);
        String leaf = add("actor-2", 10, 200, 60, 30, outer);
        int widthBefore = find(view, leaf).getBounds().getWidth();

        LayoutWithinGroupResultDto dto = layout(outer, "grid", null, 1, true, true).entity();
        IBounds after = find(view, leaf).getBounds();

        assertTrue("fixture must actually stretch the leaf, or this proves nothing: "
                + widthBefore + " -> " + after.getWidth(), after.getWidth() != widthBefore);

        MovedViewObjectDto reported = resizedEntryFor(dto, leaf);
        assertNotNull("the call re-sized a leaf element and must name it. resizedElements was: "
                + dto.resizedElements(), reported);
        assertEquals("leaf: x", after.getX(), reported.newX());
        assertEquals("leaf: y", after.getY(), reported.newY());
        assertEquals("leaf: width", after.getWidth(), reported.newWidth());
        assertEquals("leaf: height", after.getHeight(), reported.newHeight());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue("the rectangle must reach the wire. Response was: " + json,
                json.contains("\"resizedElements\""));
        assertTrue("the wire must carry the leaf's effective width (" + after.getWidth()
                + "). Response was: " + json,
                json.contains("\"newWidth\":" + after.getWidth()));
    }

    /**
     * The single-level arm, which is a different loop in a different method and sizes its cells
     * differently again: one width across the whole grid rather than one per column. A narrow leaf
     * beside a wide sibling is stretched to the wide one, with no recursion involved at all.
     */
    @Test
    public void shouldNameALeafItStretchedToTheGridCell_whenTheLayoutIsSingleLevel() {
        String box = group("Single", 0, 0, 600, 400, null);
        add("actor-3", 10, 30, 260, 30, box);
        String leaf = add("actor-4", 10, 100, 60, 30, box);
        int widthBefore = find(view, leaf).getBounds().getWidth();

        LayoutWithinGroupResultDto dto = layout(box, "grid", null, 2, true, false).entity();
        IBounds after = find(view, leaf).getBounds();

        assertEquals("fixture guard: the single-level arm really did run",
                0, dto.nestedContainersArranged());
        assertTrue("fixture must actually stretch the leaf, or this proves nothing: "
                + widthBefore + " -> " + after.getWidth(), after.getWidth() != widthBefore);

        MovedViewObjectDto reported = resizedEntryFor(dto, leaf);
        assertNotNull("the single-level arm re-sized a leaf and must name it too — the two arms are "
                + "separate write ranges and covering one covers nothing of the other. "
                + "resizedElements was: " + dto.resizedElements(), reported);
        assertEquals("leaf: width", after.getWidth(), reported.newWidth());
        assertEquals("leaf: height", after.getHeight(), reported.newHeight());
    }

    /**
     * The resize is not a grid phenomenon. A row arrangement with an explicit {@code elementWidth}
     * writes that width to every child, so the same silent change reaches a caller who never asked
     * for a grid and never set {@code autoWidth}.
     */
    @Test
    public void shouldNameALeafItResized_whenARowLayoutAppliesAnExplicitElementWidth() {
        String strip = group("Strip", 0, 0, 800, 300, null);
        String leaf = add("actor-5", 10, 30, 120, 30, strip);
        add("actor-6", 10, 100, 120, 30, strip);
        int widthBefore = find(view, leaf).getBounds().getWidth();

        LayoutWithinGroupResultDto dto = layout(strip, "row", 200, null, true, false).entity();
        IBounds after = find(view, leaf).getBounds();

        assertTrue("fixture must actually resize the leaf, or this proves nothing: "
                + widthBefore + " -> " + after.getWidth(), after.getWidth() != widthBefore);
        MovedViewObjectDto reported = resizedEntryFor(dto, leaf);
        assertNotNull("elementWidth resizes leaves outside grid too. resizedElements was: "
                + dto.resizedElements(), reported);
        assertEquals("leaf: width", after.getWidth(), reported.newWidth());
    }

    /**
     * The mirror of the {@code resizedAncestors} observation rule, in the new field. Run the same
     * layout twice: the second call re-writes every child's rectangle exactly as the first left it,
     * so a list sourced from having emitted a command would name every leaf a second time.
     */
    @Test
    public void shouldNotNameALeafThatDidNotChange_whenTheLayoutIsRepeated() {
        String box = group("Single", 0, 0, 600, 400, null);
        add("actor-3", 10, 30, 260, 30, box);
        String leaf = add("actor-4", 10, 100, 60, 30, box);

        LayoutWithinGroupResultDto first = layout(box, "grid", null, 2, true, false).entity();
        IBounds afterFirst = find(view, leaf).getBounds();
        int w = afterFirst.getWidth();
        int h = afterFirst.getHeight();

        LayoutWithinGroupResultDto second = layout(box, "grid", null, 2, true, false).entity();
        IBounds afterSecond = find(view, leaf).getBounds();

        assertNotNull("the first call must actually resize the leaf, or the pair proves nothing",
                resizedEntryFor(first, leaf));
        assertEquals("fixture guard: the second call really does leave the leaf's size alone",
                w + "x" + h, afterSecond.getWidth() + "x" + afterSecond.getHeight());
        assertNull("the second call changed no rectangle, so it must name none. resizedElements "
                + "was: " + second.resizedElements(), resizedEntryFor(second, leaf));
    }

    /**
     * The two descendant lists partition what the downward pass re-sized: a container the walk
     * descended into is named under {@code nestedContainersFitted}, and never a second time here.
     * Both must be non-empty in the same response, or the disjointness claim is vacuous.
     */
    @Test
    public void shouldKeepTheContainerAndLeafListsDisjoint_whenBothAreNonEmpty() {
        String outer = group("Region", 0, 0, 600, 600, null);
        String wide = group("Wide", 10, 10, 100, 100, outer);
        add("actor-1", 10, 30, 260, 30, wide);
        add("actor-2", 10, 200, 60, 30, outer);

        LayoutWithinGroupResultDto dto = layout(outer, "grid", null, 1, true, true).entity();

        assertFalse("fixture must re-fit a container, or disjointness is vacuous",
                dto.nestedContainersFitted().isEmpty());
        assertFalse("fixture must resize a leaf, or disjointness is vacuous",
                dto.resizedElements().isEmpty());
        for (MovedViewObjectDto fitted : dto.nestedContainersFitted()) {
            assertNull("a container reported as fitted must not be repeated as a resized leaf: "
                    + fitted.viewObjectId() + ". resizedElements was: " + dto.resizedElements(),
                    resizedEntryFor(dto, fitted.viewObjectId()));
        }
        assertNull("the recursed container belongs to the container list only",
                resizedEntryFor(dto, wide));
    }

    /**
     * The field reports SIZE, and only size. A row layout with no size override writes each child
     * its own width and height back, so both children move and neither changes shape.
     *
     * <p>Without this fixture the size-only rule is unpinned: every other positive case here changes
     * position <em>and</em> size together, and every other negative case changes neither — so a
     * condition that had quietly become "moved OR resized" would satisfy all of them. This is the
     * one shape that tells the two apart, and {@code elementsRepositioned} is asserted alongside to
     * show the children really were re-placed rather than skipped.</p>
     */
    @Test
    public void shouldNotNameAChildThatOnlyMoved_whenItsSizeIsUnchanged() {
        String strip = group("Strip", 0, 0, 800, 300, null);
        String first = add("actor-5", 400, 200, 120, 55, strip);
        String second = add("actor-6", 600, 200, 120, 55, strip);
        int firstXBefore = find(view, first).getBounds().getX();

        LayoutWithinGroupResultDto dto = layout(strip, "row", null, null, false, false).entity();
        IBounds firstAfter = find(view, first).getBounds();
        IBounds secondAfter = find(view, second).getBounds();

        assertTrue("fixture must actually MOVE a child, or this proves nothing: "
                + firstXBefore + " -> " + firstAfter.getX(), firstAfter.getX() != firstXBefore);
        assertEquals("fixture guard: and must leave its size alone", 120, firstAfter.getWidth());
        assertEquals("fixture guard: same for the second child", 120, secondAfter.getWidth());
        assertEquals("both children really were re-placed", 2, dto.elementsRepositioned());

        assertTrue("a child that only moved must not be named — the field reports size, and a "
                + "condition that had become 'moved OR resized' would name both of these. "
                + "resizedElements was: " + dto.resizedElements(), dto.resizedElements().isEmpty());
    }

    /**
     * The corrected {@code recursiveChildren} caveat, made executable. The published text used to
     * offer {@code elementWidth} as the way out of the within-column stretch; it is not one on this
     * arm. {@code elementWidth} sets what a LEAF contributes to its column, and the column is still
     * sized by its widest member — which is the fitted container, not the leaf. A description that
     * hands the caller a remedy that does not work is the same defect class as saying nothing.
     */
    @Test
    public void shouldStillStretchALeafToItsColumn_whenElementWidthIsSetOnTheRecursiveArm() {
        String outer = group("Region", 0, 0, 600, 600, null);
        String wide = group("Wide", 10, 10, 100, 100, outer);
        add("actor-1", 10, 30, 260, 30, wide);
        String leaf = add("actor-2", 10, 200, 60, 30, outer);

        LayoutWithinGroupResultDto dto = layout(outer, "grid", 200, 1, true, true).entity();
        IBounds after = find(view, leaf).getBounds();

        assertTrue("elementWidth must NOT cap the column here — the fitted container is the wider "
                + "member and sets the cell. Landed at " + after.getWidth()
                + ", elementWidth was 200", after.getWidth() > 200);
        assertNotNull("and the stretch must still be reported. resizedElements was: "
                + dto.resizedElements(), resizedEntryFor(dto, leaf));
        assertEquals("the reported width must be the one the model holds",
                after.getWidth(), resizedEntryFor(dto, leaf).newWidth());
    }

    /**
     * The approval card names the resize too. A human deciding whether to apply a call that will
     * stretch elements has {@code nextSteps} discarded in this mode, so the card is the only prose
     * surface left. The key mirrors {@code ancestorsResized}: a count named {@code <noun>Resized}
     * beside a list named {@code resized<Noun>}, which is the pairing this tool already publishes.
     */
    @Test
    public void shouldMentionTheResizedElementsOnTheApprovalCard() {
        String box = group("Single", 0, 0, 600, 400, null);
        add("actor-3", 10, 30, 260, 30, box);
        add("actor-4", 10, 100, 60, 30, box);

        approvalMode = true;
        MutationResult<LayoutWithinGroupResultDto> result =
                layout(box, "grid", null, 2, true, false);

        assertNotNull("the call must actually be a proposal, or this proves nothing",
                result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher()
                .getProposal(SESSION, result.proposalContext().proposalId());
        assertEquals("the card must say how many children this call will re-size, or the approver "
                + "is asked to accept a silent geometry change. proposedChanges was: "
                + pending.proposedChanges(),
                Integer.valueOf(1), pending.proposedChanges().get("elementsResized"));
    }

    /** Nothing was resized, so the key is absent — the convention both siblings already keep. */
    @Test
    public void shouldOmitTheResizedLeafList_whenNothingChangedSize() throws Exception {
        String lone = group("Lone", 600, 0, 200, 200, null);
        add("actor-3", 10, 30, 60, 30, lone);

        LayoutWithinGroupResultDto dto = layout(lone, "column", null, null, true, false).entity();

        assertTrue("a single child in a column keeps its own size", dto.resizedElements().isEmpty());
        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("an empty list must be omitted from the wire, not serialized as []. "
                + "Response was: " + json, json.contains("resizedElements"));
    }

    // ---- the size the layout feeds itself, inside an open batch -----------------------------------
    //
    // Both arms compute a child's INPUT size from a read that ignores the batch's own queue, then
    // write a rectangle derived from it. Inside a batch getBounds() is a PRE-BATCH read — none of
    // the batch's commands have run — so a resize an earlier operation of the same unit of work
    // asked for is silently discarded and the pre-batch size written back over it. The report is
    // not what is wrong: it measures against the queue and correctly names the clobbered child.
    // These pins are on the INPUT, and they are end-to-end so the threading from the facade down to
    // the size resolver is what is proved, not a collaborator called with hand-written literals.

    /**
     * The commonest call shape of all: no size overrides at all. Both axes then fall back to the
     * stored bounds, so this arm needs no override to reach the defect.
     */
    @Test
    public void shouldLayOutAChildAtTheSizeTheBatchQueued_whenNoOverridesAreGiven() {
        String container = group("Region", 0, 0, 600, 600, null);
        String a = add("actor-1", 10, 30, 200, 100, container);
        add("actor-2", 10, 200, 200, 100, container);

        dispatcher.beginBatch(SESSION, "resize a child, then lay its parent out");
        accessor.updateViewObject(SESSION, a, null, null, 400, 300, null, null, null, null);
        LayoutWithinGroupResultDto dto =
                layout(container, "column", null, null, true, false).entity();
        dispatcher.endBatch(SESSION, true);

        IBounds landed = find(view, a).getBounds();
        assertEquals("the batch queued 400 wide and the layout must lay the child out at THAT, not "
                + "at the 200 its pre-batch bounds still held when the pass measured it",
                400, landed.getWidth());
        assertEquals("and the queued height", 300, landed.getHeight());

        assertNull("the layout no longer changes this child's size, so it must not be named as "
                + "resized either — the report measures against the same queue the input now does. "
                + "resizedElements was: " + dto.resizedElements(), resizedEntryFor(dto, a));
    }

    /**
     * The recursive arm reached through the facade, so the pin covers the wiring and not only the
     * collaborator: with only the HEIGHT overridden the width is a fallback, and inside a batch
     * that fallback must be the queued width.
     */
    @Test
    public void shouldKeepAQueuedWidth_whenTheRecursiveArmOverridesOnlyTheHeight() {
        String container = group("Region", 0, 0, 900, 700, null);
        String nested = group("Nested", 10, 30, 400, 400, container);
        String leaf = add("actor-1", 10, 30, 200, 100, nested);

        dispatcher.beginBatch(SESSION, "resize a nested leaf, then lay the tree out");
        accessor.updateViewObject(SESSION, leaf, null, null, 400, 300, null, null, null, null);
        accessor.layoutWithinGroup(SESSION, view.getId(), container, "column", 20, 10,
                null, 80, false, false, null, false, true);
        dispatcher.endBatch(SESSION, true);

        IBounds landed = find(view, leaf).getBounds();
        assertEquals("the un-overridden axis must come from the queue", 400, landed.getWidth());
        assertEquals("the overridden axis is untouched — an override is not the defect",
                80, landed.getHeight());
    }

    /**
     * The mirror axis through the facade: with only the WIDTH overridden the height is the
     * fallback, and it too must come from the queue.
     */
    @Test
    public void shouldKeepAQueuedHeight_whenTheRecursiveArmOverridesOnlyTheWidth() {
        String container = group("Region", 0, 0, 900, 700, null);
        String nested = group("Nested", 10, 30, 400, 400, container);
        String leaf = add("actor-1", 10, 30, 200, 100, nested);

        dispatcher.beginBatch(SESSION, "resize a nested leaf, then lay the tree out");
        accessor.updateViewObject(SESSION, leaf, null, null, 400, 300, null, null, null, null);
        accessor.layoutWithinGroup(SESSION, view.getId(), container, "column", 20, 10,
                150, null, false, false, null, false, true);
        dispatcher.endBatch(SESSION, true);

        IBounds landed = find(view, leaf).getBounds();
        assertEquals("the overridden axis", 150, landed.getWidth());
        assertEquals("the un-overridden axis must come from the queue", 300, landed.getHeight());
    }

    /**
     * The third route to the same fallback: {@code autoWidth} computes a width, so it is an
     * override like any other and the height is what falls back.
     */
    @Test
    public void shouldKeepAQueuedHeight_whenTheRecursiveArmIsGivenOnlyAutoWidth() {
        String container = group("Region", 0, 0, 900, 700, null);
        String nested = group("Nested", 10, 30, 400, 400, container);
        String leaf = add("actor-1", 10, 30, 200, 100, nested);

        dispatcher.beginBatch(SESSION, "resize a nested leaf, then lay the tree out");
        accessor.updateViewObject(SESSION, leaf, null, null, 400, 300, null, null, null, null);
        accessor.layoutWithinGroup(SESSION, view.getId(), container, "column", 20, 10,
                null, null, false, true, null, false, true);
        dispatcher.endBatch(SESSION, true);

        IBounds landed = find(view, leaf).getBounds();
        assertEquals("a computed width still wins over a queued one — overriding is not the defect",
                GroupLayoutCalculator.computeAutoWidth("Actor 1"), landed.getWidth());
        assertEquals("the height nobody overrode must come from the queue",
                300, landed.getHeight());
    }

    /**
     * The report still describes the model, on the queue-aware path. The pins above all assert that
     * a queued size SURVIVES, which means nothing is reported — so none of them can show that the
     * published rectangle still agrees with the model once the fix is in. Here an explicit width
     * differs from the queued one, so the pass really does re-size the child and must name it at the
     * rectangle the model ends up holding.
     */
    @Test
    public void shouldStillPublishTheRectangleTheModelHolds_whenTheBatchQueuedADifferentSize() {
        String container = group("Region", 0, 0, 900, 700, null);
        String leaf = add("actor-1", 10, 30, 200, 100, container);

        dispatcher.beginBatch(SESSION, "resize a child, then lay it out at a different width");
        accessor.updateViewObject(SESSION, leaf, null, null, 400, 300, null, null, null, null);
        LayoutWithinGroupResultDto dto = accessor.layoutWithinGroup(SESSION, view.getId(),
                container, "column", 20, 10, 150, null, false, false, null, false, false)
                .entity();
        dispatcher.endBatch(SESSION, true);

        MovedViewObjectDto reported = resizedEntryFor(dto, leaf);
        assertNotNull("the requested width differs from the queued one, so this child really is "
                + "re-sized by the pass and must be named. resizedElements was: "
                + dto.resizedElements(), reported);

        IBounds landed = find(view, leaf).getBounds();
        assertEquals("reported x must equal the model's", landed.getX(), reported.newX());
        assertEquals("reported y must equal the model's", landed.getY(), reported.newY());
        assertEquals("reported width must equal the model's",
                landed.getWidth(), reported.newWidth());
        assertEquals("reported height must equal the model's",
                landed.getHeight(), reported.newHeight());
        assertEquals("and the un-overridden axis still came from the queue", 300,
                landed.getHeight());
    }

    /**
     * The grid arm chooses its COLUMN COUNT from the container's own width, so a pre-batch read
     * there picks the shape of the whole grid from a rectangle the container is about to stop
     * having. Derived from the calculator's rule
     * {@code cols = max(1, (groupWidth - 2*padding + spacing) / (maxW + spacing))}: with padding 10,
     * spacing 40 and five 120-wide children, a live width of 300 gives 2 columns and the queued 900
     * gives 5. The fixture therefore discriminates on the ARRANGEMENT, not merely on a rectangle.
     */
    @Test
    public void shouldDeriveTheGridColumnCountFromTheQueuedContainerWidth() {
        String container = group("Region", 0, 0, 300, 600, null);
        List<String> children = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            children.add(add("actor-" + i, 10, 30 + i * 70, 120, 55, container));
        }

        dispatcher.beginBatch(SESSION, "widen the container, then lay it out as a grid");
        accessor.updateViewObject(SESSION, container, null, null, 900, 600,
                null, null, null, null);
        LayoutWithinGroupResultDto dto = accessor.layoutWithinGroup(SESSION, view.getId(),
                container, "grid", 40, 10, null, null, false, false, null, false, false)
                .entity();
        dispatcher.endBatch(SESSION, true);

        assertEquals("the column count must be derived from the width the batch queued (900 -> 5), "
                + "not from the pre-batch 300 (-> 2)", Integer.valueOf(5), dto.columnsUsed());

        int firstY = find(view, children.get(0)).getBounds().getY();
        for (String child : children) {
            assertEquals("five columns puts all five children on ONE row, which is the arrangement "
                    + "a two-column grid cannot produce", firstY,
                    find(view, child).getBounds().getY());
        }
    }

    /** The leading code, which is the part an agent triages on and the part that never changes. */
    private static String codeOf(String reason) {
        assertNotNull("ancestorPropagation must never be null — it ships on every response", reason);
        int colon = reason.indexOf(':');
        return colon < 0 ? reason : reason.substring(0, colon);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener l) { listeners.add(l); }
        @Override public void removePropertyChangeListener(PropertyChangeListener l) { listeners.remove(l); }
        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel m) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel m) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel m, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel m) { return false; }
        @Override public boolean saveModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel m) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }

    // ---- the four fields the deferred arms' guidance is selected on ------------------------------

    /**
     * A queued or awaiting-approval response carries the same four fields as an applied one.
     *
     * <p>The five conditional {@code nextSteps} this tool emits are selected on {@code overflow},
     * {@code nestedContainersArranged}, {@code maxDepthReached} and {@code ancestorPropagation}.
     * Only the last of those was pinned on a deferred arm; the other three were assumed. An absent
     * field would mean a step silently omitted on the arm that needs it most — or, worse, one
     * emitted from a request value dressed as an outcome.</p>
     *
     * <p>Asserted as equality across the three arms rather than as "present on each": a field that
     * is populated on every arm but with a <em>different</em> value on the deferred ones is the
     * prepare/execute divergence the preview label exists to declare, and a presence check cannot
     * see it. Each arm gets its own pristine subtree so nothing an earlier arm did is measured by
     * a later one.</p>
     */
    @Test
    public void shouldCarryOverflowRecursionAndPropagationIdentically_onEveryDispatchArm() {
        // Every subtree is built BEFORE any arm is entered: inside a batch the adds themselves
        // would be queued, and the layout call would then look up a container the model does not
        // yet hold.
        String appliedGroup = nestedOverflowSubtree("Applied");
        String queuedGroup = nestedOverflowSubtree("Queued");
        String proposedGroup = nestedOverflowSubtree("Proposed");

        LayoutWithinGroupResultDto applied = layoutForOverflow(appliedGroup);

        dispatcher.beginBatch(SESSION, "field availability");
        LayoutWithinGroupResultDto queued = layoutForOverflow(queuedGroup);
        dispatcher.endBatch(SESSION, true);

        approvalMode = true;
        LayoutWithinGroupResultDto proposed = layoutForOverflow(proposedGroup);
        approvalMode = false;

        assertTrue("the fixture must actually overflow, or all three agreeing proves nothing",
                applied.overflow());
        assertTrue("and must actually arrange a nested container",
                applied.nestedContainersArranged() > 0);

        assertEquals("overflow must not be lost when the call is queued",
                applied.overflow(), queued.overflow());
        assertEquals("nor when it is awaiting approval",
                applied.overflow(), proposed.overflow());
        assertEquals("the recursion count must survive the queue",
                applied.nestedContainersArranged(), queued.nestedContainersArranged());
        assertEquals("and the approval gate",
                applied.nestedContainersArranged(), proposed.nestedContainersArranged());
        assertEquals("the depth reached must survive the queue",
                applied.maxDepthReached(), queued.maxDepthReached());
        assertEquals("and the approval gate",
                applied.maxDepthReached(), proposed.maxDepthReached());
        assertEquals("the propagation reason must survive the queue",
                applied.ancestorPropagation(), queued.ancestorPropagation());
        assertEquals("and the approval gate",
                applied.ancestorPropagation(), proposed.ancestorPropagation());
    }

    /**
     * The same, for the propagation reason that actually leaves work undone.
     *
     * <p>The fixture above reports {@code not-requested}, which is true but is the one reason no
     * remedy is emitted for. This drives the walk far enough to be halted by an element parent —
     * the case whose remedy a queued caller was never told about.</p>
     */
    @Test
    public void shouldCarryAHaltedUpwardWalkIdentically_onEveryDispatchArm() {
        String appliedNode = haltedWalkSubtree("node-1", "actor-3");
        String queuedNode = haltedWalkSubtree("node-2", "actor-4");
        String proposedNode = haltedWalkSubtree("grouping-1", "actor-5");

        LayoutWithinGroupResultDto applied = layoutForHaltedWalk(appliedNode);

        dispatcher.beginBatch(SESSION, "halted walk availability");
        LayoutWithinGroupResultDto queued = layoutForHaltedWalk(queuedNode);
        dispatcher.endBatch(SESSION, true);

        approvalMode = true;
        LayoutWithinGroupResultDto proposed = layoutForHaltedWalk(proposedNode);
        approvalMode = false;

        assertEquals("the fixture must actually halt the walk at an element container, or the "
                        + "three agreeing proves nothing about the remedy this story adds",
                "container-not-a-native-group", codeOf(applied.ancestorPropagation()));
        assertEquals("a queued call must reach the same verdict",
                applied.ancestorPropagation(), queued.ancestorPropagation());
        assertEquals("and one awaiting approval",
                applied.ancestorPropagation(), proposed.ancestorPropagation());
    }

    /** A group too small for a nested container it recursively arranges: overflow plus recursion. */
    private String nestedOverflowSubtree(String label) {
        String outer = group("Outer " + label, 0, 0, 80, 80, null);
        String inner = group("Inner " + label, 5, 20, 60, 40, outer);
        add("actor-1", 5, 20, 200, 30, inner);
        add("actor-2", 5, 60, 200, 30, inner);
        return outer;
    }

    private LayoutWithinGroupResultDto layoutForOverflow(String groupId) {
        return accessor.layoutWithinGroup(SESSION, view.getId(), groupId, "column", 20, 10,
                null, null, /*autoResize=*/ false, false, null,
                /*recursive=*/ false, /*recursiveChildren=*/ true).entity();
    }

    /** An ArchiMate-element container: the upward walk refuses to start and says so. */
    private String haltedWalkSubtree(String elementId, String childId) {
        String node = elementContainer(elementId, 0, 0, 300, 300, null);
        add(childId, 10, 30, 60, 30, node);
        return node;
    }

    private LayoutWithinGroupResultDto layoutForHaltedWalk(String containerId) {
        return accessor.layoutWithinGroup(SESSION, view.getId(), containerId, "column", 20, 10,
                null, null, /*autoResize=*/ true, false, null,
                /*recursive=*/ true, /*recursiveChildren=*/ false).entity();
    }
}
