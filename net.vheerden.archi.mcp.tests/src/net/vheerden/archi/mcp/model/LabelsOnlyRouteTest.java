package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * {@code auto-route-connections} with {@code mode: "labels-only"} through the real accessor: it is accepted and
 * validated, it moves labels and nothing else, it is refused on a manhattan view, and it follows the tool's approval,
 * undo and batch arms.
 *
 * <p>Commands run on a real {@link CommandStack}, so one undo is one call. Elements are unnamed and only relationships
 * are named: the pass reads a label's box as a character count and never measures text, so the class is headless.</p>
 */
public class LabelsOnlyRouteTest {

    private static final String SESSION = AutoRouteArmFixture.SESSION;
    private static final String VIEW_ID = AutoRouteArmFixture.VIEW_ID;
    private static final String MODE = "labels-only";
    private static final int SOURCE = 0;
    private static final int MIDDLE = 1;
    private static final int NORTH = 1;

    // ==================== accepted and validated ====================

    /** A line crossing the Middle label with zero overlaps (crowding, not collision) moves it to Source. */
    @Test
    public void labelsOnly_movesALabelOffAForeignLine() {
        Harness h = new Harness(crossed());

        AutoRouteResultDto dto = h.labelsOnly().entity();

        assertEquals(SOURCE, conn(h.c, "l").getTextPosition());
        assertEquals(1, dto.labelsOptimized());
        assertEquals(0, dto.connectionsRouted());
        assertFalse(dto.routerTypeSwitched());
        assertEquals(List.of(), dto.warnings());
    }

    @Test
    public void labelsOnly_combinedWithAnyRoutingOnlyArgument_isRefused() {
        Map<String, Refusal> cases = new LinkedHashMap<>();
        cases.put("connectionIds", h -> h.call(List.of("l"), "orthogonal", false, MODE, null));
        cases.put("strategy 'clear'", h -> h.call(null, "clear", false, MODE, null));
        cases.put("autoNudge", h -> h.call(null, "orthogonal", true, MODE, null));
        cases.put("labelPolicy", h -> h.call(null, "orthogonal", false, MODE, "auto-hide-on-collision"));
        List<String> wrong = new ArrayList<>();
        cases.forEach((argument, call) -> {
            Harness h = new Harness(crossed());
            try {
                call.run(h);
                wrong.add(argument + ": accepted");
            } catch (ModelAccessException e) {
                if (e.getErrorCode() != ErrorCode.INVALID_PARAMETER || !e.getMessage().contains(argument)
                        || !e.getMessage().contains(MODE)) {
                    wrong.add(argument + ": " + e.getErrorCode() + " " + e.getMessage());
                }
            }
            if (conn(h.c, "l").getTextPosition() != MIDDLE || h.stack.canUndo()) {
                wrong.add(argument + ": changed the view");
            }
        });
        assertEquals(List.of(), wrong);
    }

    @Test
    public void labelsOnly_ignoresTheRoutingTunables() {
        Harness plain = new Harness(crossed());
        plain.accessor.autoRouteConnections(SESSION, VIEW_ID, null, null, false, false,
                RoutingPipeline.DEFAULT_SNAP_THRESHOLD, RoutingPipeline.DEFAULT_PERIMETER_MARGIN, MODE, true, null);
        Harness tuned = new Harness(crossed());
        tuned.accessor.autoRouteConnections(SESSION, VIEW_ID, null, null, true, false, 3, 2, MODE, false, null);

        assertFalse(plain.dispatched.isEmpty());
        assertEquals(plain.dispatched, tuned.dispatched);
    }

    // ==================== moves labels, nothing else ====================

    /** Two orthogonal routes whose Middle labels lie on each other and on each other's line. */
    @Test
    public void labelsOnly_movesTheLabelsAndKeepsEveryBendpoint() {
        Harness h = new Harness(twoRoutes());
        Map<String, List<BendpointDto>> before = bendpoints(h.c);
        assertEquals(2, before.get("l1").size());

        AutoRouteResultDto dto = h.labelsOnly().entity();

        assertEquals(Map.of("l1", SOURCE, "l2", SOURCE), Map.of("l1", conn(h.c, "l1").getTextPosition(),
                "l2", conn(h.c, "l2").getTextPosition()));
        assertEquals(2, dto.labelsOptimized());
        assertEquals("not one bendpoint moved", before, bendpoints(h.c));
    }

    /**
     * A self-connection stored with no bendpoints, a group and a note beside the crossed label: the pass emits only
     * label commands, and every bound, bendpoint, label visibility and the router type are as they were.
     */
    @Test
    public void labelsOnly_emitsOnlyLabelCommands_andChangesNothingElse() {
        AutoRouteArmFixture.Ctx c = crossed();
        AutoRouteArmFixture.connect(c, "self", "rel-self", "obj-a-n", "obj-a-n");
        conn(c, "self").getArchimateRelationship().setName("checks itself");
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setId("group");
        group.setBounds(700, 0, 200, 150);
        c.view.getChildren().add(group);
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setId("note");
        note.setBounds(20, 10, 40, 30);
        group.getChildren().add(note);
        Harness h = new Harness(c);
        String before = geometry(c);
        assertTrue(conn(c, "self").getBendpoints().isEmpty());

        h.labelsOnly();

        List<String> kinds = new ArrayList<>();
        h.dispatched.forEach(d -> kinds.add(d.substring(0, d.indexOf(' '))));
        assertEquals(List.of("SetTextPositionCommand"), kinds);
        assertEquals(geometry(c), before);
        assertEquals(SOURCE, conn(c, "l").getTextPosition());
    }

    // ==================== manhattan ====================

    @Test
    public void labelsOnly_onAManhattanView_isRefusedAndStoresNothing() {
        List<String> wrong = new ArrayList<>();
        for (String arm : List.of("applied", "approval", "batch")) {
            Harness h = new Harness(crossed());
            h.c.view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_MANHATTAN);
            h.approval = "approval".equals(arm);
            if ("batch".equals(arm)) {
                h.dispatcher.beginBatch(SESSION, "manhattan");
            }
            try {
                h.labelsOnly();
                wrong.add(arm + ": accepted");
            } catch (ModelAccessException e) {
                if (e.getErrorCode() != ErrorCode.INVALID_PARAMETER || !e.getMessage().contains("manhattan")
                        || !e.getSuggestedCorrection().contains("auto-route-connections without mode")) {
                    wrong.add(arm + ": " + e.getMessage() + " / " + e.getSuggestedCorrection());
                }
            }
            List<Command> queued = h.dispatcher.queuedLeafCommands(SESSION);
            if (h.stack.canUndo() || !h.dispatcher.getPendingProposals(SESSION).isEmpty()
                    || (queued != null && !queued.isEmpty()) || conn(h.c, "l").getTextPosition() != MIDDLE) {
                wrong.add(arm + ": stored something");
            }
        }
        assertEquals(List.of(), wrong);

        Harness bendpointMode = new Harness(crossed());
        bendpointMode.labelsOnly();
        assertEquals("the same view in bendpoint mode is placed", SOURCE, conn(bendpointMode.c, "l").getTextPosition());
    }

    // ==================== approval, undo, batch ====================

    /** One connection gets a position and an offset: the card counts it once. */
    @Test
    public void labelsOnly_proposes_approves_andOneUndoRestoresEveryLabel() throws Exception {
        Harness h = new Harness(positionAndOffset());
        IDiagramModelArchimateConnection l = conn(h.c, "l");
        int heldOffset = RelativePositionFeature.get(l);
        h.approval = true;

        MutationResult<AutoRouteResultDto> result = h.labelsOnly();

        assertTrue(result.isProposal());
        Map<String, Object> card = h.dispatcher.getProposal(SESSION, result.proposalContext().proposalId())
                .proposedChanges();
        assertEquals(MODE, card.get("mode"));
        assertEquals(1, card.get("labelsOptimized"));
        assertEquals(10, card.get("trials"));
        assertEquals(1, result.entity().labelsOptimized());
        assertEquals("nothing applied before approval", SOURCE, l.getTextPosition());

        h.approval = false;
        assertNotNull(h.dispatcher.approveProposal(SESSION, result.proposalContext().proposalId()));
        assertEquals(MIDDLE, l.getTextPosition());
        assertEquals(NORTH, RelativePositionFeature.get(l));

        h.stack.undo();
        assertEquals(SOURCE, l.getTextPosition());
        assertEquals(heldOffset, RelativePositionFeature.get(l));
        assertFalse("one undo", h.stack.canUndo());
    }

    @Test
    public void labelsOnly_withNothingToPlace_queuesNothingInABatch_andSaysWhy() {
        Harness h = new Harness(crossed());
        conn(h.c, "l").setNameVisible(false);
        h.dispatcher.beginBatch(SESSION, "nothing to place");

        MutationResult<AutoRouteResultDto> result = h.labelsOnly();

        assertFalse(result.isBatched());
        assertEquals(List.of(), h.dispatcher.queuedLeafCommands(SESSION));
        assertEquals(0, result.entity().labelsOptimized());
        assertEquals(List.of("The view has no shown connection label, so there was nothing to place."),
                result.entity().warnings());
    }

    @Test
    public void labelsOnly_inABatch_queuesTheLabelCommands() {
        Harness h = new Harness(crossed());
        h.dispatcher.beginBatch(SESSION, "place labels");

        MutationResult<AutoRouteResultDto> result = h.labelsOnly();

        assertTrue(result.isBatched());
        assertEquals(MIDDLE, conn(h.c, "l").getTextPosition());
        h.dispatcher.endBatch(SESSION, true);
        assertEquals(SOURCE, conn(h.c, "l").getTextPosition());
    }

    /** Nothing to change is nothing to approve: no proposal is stored and nothing reaches the stack. */
    @Test
    public void labelsOnly_withNothingToChange_proposesNothing() {
        Harness h = new Harness(crossed());
        conn(h.c, "l").setTextPosition(SOURCE);
        h.approval = true;

        MutationResult<AutoRouteResultDto> result = h.labelsOnly();

        assertFalse(result.isProposal());
        assertEquals(1, result.entity().warnings().size());
        assertEquals(List.of(), h.dispatched);
        assertFalse(h.stack.canUndo());
    }

    @Test
    public void labelsOnly_withNoBetterPlacement_keepsThePositions_andSaysSo() {
        Harness h = new Harness(crossed());
        conn(h.c, "l").setTextPosition(SOURCE);

        AutoRouteResultDto dto = h.labelsOnly().entity();

        assertEquals(SOURCE, conn(h.c, "l").getTextPosition());
        assertEquals(0, dto.labelsOptimized());
        assertEquals(1, dto.warnings().size());
        assertEquals(List.of("No label placement scored better than the current one, so every label kept its position"
                + " and its Label Offset."), dto.warnings());
        assertEquals("no label command", List.of(), h.dispatched);
    }

    // ==================== fixtures ====================

    /**
     * l from a (0, 100) to b (500, 100), 100 x 60, a straight line along y 130, "sends orders" at Middle on (300, 130);
     * an unlabelled line down x 300 from c (270, -200) to d (270, 400) crosses the Middle label and no box does.
     */
    private static AutoRouteArmFixture.Ctx crossed() {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        AutoRouteArmFixture.addHost(c, "a", 0, 100, 100, 60);
        AutoRouteArmFixture.addHost(c, "b", 500, 100, 100, 60);
        AutoRouteArmFixture.addHost(c, "c", 270, -200, 60, 60);
        AutoRouteArmFixture.addHost(c, "d", 270, 400, 60, 60);
        labelled(c, "l", "a", "b", "sends orders", MIDDLE);
        AutoRouteArmFixture.connect(c, "f", "rel-f", "obj-c-n", "obj-d-n");
        return c;
    }

    /**
     * l1 from a1 (0, 100) to b1 (600, 220) through (350, 130) and (350, 250); l2 from a2 (0, 0) to b2 (600, 320) through
     * (380, 30) and (380, 350); boxes 100 x 60. Both Middle labels sit on (350..380, 190), on each other and on each
     * other's vertical; both Source slots are clear.
     */
    private static AutoRouteArmFixture.Ctx twoRoutes() {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        AutoRouteArmFixture.addHost(c, "a1", 0, 100, 100, 60);
        AutoRouteArmFixture.addHost(c, "b1", 600, 220, 100, 60);
        AutoRouteArmFixture.addHost(c, "a2", 0, 0, 100, 60);
        AutoRouteArmFixture.addHost(c, "b2", 600, 320, 100, 60);
        labelled(c, "l1", "a1", "b1", "sends orders", MIDDLE);
        labelled(c, "l2", "a2", "b2", "reads balances", MIDDLE);
        route(c, "l1", List.of(new AbsoluteBendpointDto(350, 130), new AbsoluteBendpointDto(350, 250)), 50, 130, 650, 250);
        route(c, "l2", List.of(new AbsoluteBendpointDto(380, 30), new AbsoluteBendpointDto(380, 350)), 50, 30, 650, 350);
        return c;
    }

    /**
     * l from a (0, 120) to b (160, 120), both 100 x 20, "sends all orders" at Source, where box s (110, 140, 80, 30)
     * sits. Middle bleeds onto both ends, which a North offset lifts it off, so the best trial moves it to Middle and
     * offsets it: two commands on one connection.
     */
    private static AutoRouteArmFixture.Ctx positionAndOffset() {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        AutoRouteArmFixture.addHost(c, "a", 0, 120, 100, 20);
        AutoRouteArmFixture.addHost(c, "b", 160, 120, 100, 20);
        AutoRouteArmFixture.addHost(c, "s", 110, 140, 80, 30);
        labelled(c, "l", "a", "b", "sends all orders", SOURCE);
        return c;
    }

    private static void labelled(AutoRouteArmFixture.Ctx c, String id, String from, String to, String name,
            int position) {
        AutoRouteArmFixture.connect(c, id, "rel-" + id, "obj-" + from + "-n", "obj-" + to + "-n");
        conn(c, id).getArchimateRelationship().setName(name);
        conn(c, id).setTextPosition(position);
    }

    private static void route(AutoRouteArmFixture.Ctx c, String id, List<AbsoluteBendpointDto> points, int sx, int sy,
            int tx, int ty) {
        ConnectionResponseBuilder.applyBendpointsToConnection(conn(c, id),
                ConnectionResponseBuilder.convertAbsoluteToRelative(points, sx, sy, tx, ty));
    }

    private static IDiagramModelArchimateConnection conn(AutoRouteArmFixture.Ctx c, String id) {
        for (IDiagramModelConnection conn : connections(c.view)) {
            if (id.equals(conn.getId())) {
                return (IDiagramModelArchimateConnection) conn;
            }
        }
        throw new AssertionError("no connection " + id);
    }

    private static List<IDiagramModelConnection> connections(IDiagramModelContainer container) {
        List<IDiagramModelConnection> out = new ArrayList<>();
        for (IDiagramModelObject child : container.getChildren()) {
            out.addAll(child.getSourceConnections());
            if (child instanceof IDiagramModelContainer nested) {
                out.addAll(connections(nested));
            }
        }
        return out;
    }

    private static Map<String, List<BendpointDto>> bendpoints(AutoRouteArmFixture.Ctx c) {
        Map<String, List<BendpointDto>> out = new LinkedHashMap<>();
        for (IDiagramModelConnection conn : connections(c.view)) {
            out.put(conn.getId(), ConnectionResponseBuilder.collectBendpoints((IDiagramModelArchimateConnection) conn));
        }
        return out;
    }

    /** Every non-label feature the pass must not touch: bounds, bendpoints, label visibility, router type. */
    private static String geometry(AutoRouteArmFixture.Ctx c) {
        StringBuilder out = new StringBuilder("router=" + c.view.getConnectionRouterType());
        objects(c.view, out);
        for (IDiagramModelConnection conn : connections(c.view)) {
            out.append(" | ").append(conn.getId()).append(" bp=").append(ConnectionResponseBuilder.collectBendpoints((IDiagramModelArchimateConnection) conn))
                    .append(" shown=").append(conn.isNameVisible());
        }
        return out.toString();
    }

    private static void objects(IDiagramModelContainer container, StringBuilder out) {
        for (IDiagramModelObject child : container.getChildren()) {
            IBounds b = child.getBounds();
            out.append(" | ").append(child.getId()).append(' ').append(b.getX()).append(',').append(b.getY())
                    .append(' ').append(b.getWidth()).append('x').append(b.getHeight());
            if (child instanceof IDiagramModelContainer nested) {
                objects(nested, out);
            }
        }
    }

    // ==================== harness ====================

    private interface Refusal {
        void run(Harness h);
    }

    /** The real accessor over a real command stack; records each dispatched leaf command as "Class id value". */
    static final class Harness {
        final AutoRouteArmFixture.Ctx c;
        final CommandStack stack = new CommandStack();
        final List<String> dispatched = new ArrayList<>();
        final MutationDispatcher dispatcher;
        final ArchiModelAccessorImpl accessor;
        boolean approval;

        Harness(AutoRouteArmFixture.Ctx c) {
            this.c = c;
            c.model.setAdapter(CommandStack.class, stack);
            dispatcher = new MutationDispatcher(() -> c.model) {
                @Override
                public void dispatchImmediate(Command command) {
                    record(command);
                    stack.execute(new AgentAuthoredCompoundCommand(plain(command)));
                }

                @Override
                protected void dispatchCommand(Command command) {
                    record(command);
                    stack.execute(new AgentAuthoredCompoundCommand(plain(command)));
                }
            };
            dispatcher.setApprovalModeProvider(() -> approval);
            AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
            mgr.setModels(List.of(c.model));
            accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
            dispatcher.onModelActive(c.model);
        }

        MutationResult<AutoRouteResultDto> labelsOnly() {
            return call(null, null, false, MODE, null);
        }

        MutationResult<AutoRouteResultDto> call(List<String> ids, String strategy, boolean autoNudge, String mode,
                String labelPolicy) {
            return accessor.autoRouteConnections(SESSION, VIEW_ID, ids, strategy, false, autoNudge,
                    RoutingPipeline.DEFAULT_SNAP_THRESHOLD, RoutingPipeline.DEFAULT_PERIMETER_MARGIN, mode,
                    true, labelPolicy);
        }

        private void record(Command command) {
            if (command instanceof CompoundCommand compound) {
                for (Object child : compound.getCommands()) {
                    record((Command) child);
                }
            } else if (command instanceof SetTextPositionCommand set) {
                dispatched.add("SetTextPositionCommand " + set.getConnection().getId() + " " + set.getNewTextPosition());
            } else if (command instanceof SetTextRelativePositionCommand set) {
                dispatched.add("SetTextRelativePositionCommand " + set.getConnection().getId() + " "
                        + set.getNewRelativePosition());
            } else {
                dispatched.add(command.getClass().getSimpleName() + " " + command.getLabel());
            }
        }

        private static Command plain(Command command) {
            if (command instanceof CompoundCommand compound) {
                CompoundCommand plain = new CompoundCommand(compound.getLabel());
                for (Object child : compound.getCommands()) {
                    plain.add(plain((Command) child));
                }
                return plain;
            }
            return command;
        }
    }
}
