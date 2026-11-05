package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.SESSION;
import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.human;
import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.route;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.gef.commands.Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;

/**
 * Pins that approving a card that touches a connection is refused as stale when, during review, a
 * human moved, resized or reconnected one of that connection's ends, or dragged what holds one.
 *
 * <p>Archi stores each bendpoint as two offsets, one from each end's centre, so a stored route means
 * something only against where its ends were. A card frozen at propose time and applied after an end
 * moved writes a route whose two halves no longer meet. The ends are compared by where they sit on
 * the canvas, so a nested end carried by its parent's drag counts; they are not tracked as targets of
 * their own, so renaming an end does not refuse a card that only routes the connection.</p>
 */
public class ApprovalEndStalenessTest {

    private static final String CONNECTION = "'Ledger' → 'Billing'";

    private ApprovalStalenessFixture f;

    @Before
    public void setUp() {
        f = new ApprovalStalenessFixture();
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void approval_shouldRefuse_whenTheHumanMovedAnEnd() {
        ApprovalStalenessFixture.Pair p = f.pair();
        String proposal = f.proposeRoute(p.connection().getId(), null);
        f.humanBounds(p.b(), 700, 600, 120, 60);
        assertRefused(proposal, p.connection(), ProposalStalenessGuard.endMovedMessage("Billing", CONNECTION));
    }

    @Test
    public void approval_shouldRefuse_whenTheHumanResizedAnEnd() {
        ApprovalStalenessFixture.Pair p = f.pair();
        String proposal = f.proposeRoute(p.connection().getId(), "terminals-only");
        f.humanBounds(p.a(), 400, 0, 200, 60);
        assertRefused(proposal, p.connection(), ProposalStalenessGuard.endMovedMessage("Ledger", CONNECTION));
    }

    /** The nested end's own bounds never change; only its place on the canvas does. */
    @Test
    public void approval_shouldRefuse_whenTheHumanDraggedTheGroupHoldingAnEnd() {
        IArchimateElement ledger = f.element("el-a", "Ledger");
        IArchimateElement billing = f.element("el-b", "Billing");
        IDiagramModelGroup group = f.group("grp", "Finance", 600, 150, 300, 200);
        IDiagramModelArchimateObject a = f.object("vo-a", ledger, f.view, 400, 0, 120, 60);
        IDiagramModelArchimateObject b = f.object("vo-b", billing, group, 100, 50, 120, 60);
        IDiagramModelArchimateConnection c = ApprovalStalenessFixture.connection("conn-ab",
                f.relationship("rel-ab", ledger, billing), a, b);
        String proposal = f.proposeRoute(c.getId(), null);

        f.humanBounds(group, 600, 550, 300, 200);
        assertEquals("precondition: the nested end's own bounds did not change", 100, b.getBounds().getX());
        assertRefused(proposal, c, ProposalStalenessGuard.endMovedMessage("Billing", CONNECTION));
    }

    @Test
    public void approval_shouldRefuse_whenTheHumanReconnectedAnEnd() {
        ApprovalStalenessFixture.Pair p = f.pair();
        IDiagramModelArchimateObject other = f.object("vo-c", f.element("el-c", "Archive"), f.view,
                700, 400, 120, 60);
        String proposal = f.proposeRoute(p.connection().getId(), null);
        f.stack.execute(human("Reconnect", () -> p.connection().connect(p.a(), other),
                () -> p.connection().connect(p.a(), p.b())));
        assertSame("precondition: reconnected", other, p.connection().getTarget());

        assertRefused(proposal, p.connection(), ProposalStalenessGuard.endMovedMessage("Billing", CONNECTION));
    }

    /** Both ends were one object, so the set of end ids alone would not change; the target role does. */
    @Test
    public void approval_shouldRefuse_whenTheHumanReconnectedASelfConnectionsTargetToAnotherObject() {
        IArchimateElement ledger = f.element("el-a", "Ledger");
        IDiagramModelArchimateObject a = f.object("vo-a", ledger, f.view, 400, 0, 120, 60);
        IDiagramModelArchimateObject other = f.object("vo-c", f.element("el-c", "Archive"), f.view,
                700, 400, 120, 60);
        IDiagramModelArchimateConnection self = ApprovalStalenessFixture.connection("conn-self",
                f.relationship("rel-self", ledger, ledger), a, a);
        String proposal = f.proposeRoute(self.getId(), null);
        f.stack.execute(human("Reconnect", () -> self.connect(a, other), () -> self.connect(a, a)));

        assertRefused(proposal, self, ProposalStalenessGuard.endMovedMessage("Ledger", "'Ledger' \u2192 'Ledger'"));
    }

    /** The same two objects at the same places, but the bendpoint offsets are measured from the other end. */
    @Test
    public void approval_shouldRefuse_whenTheHumanSwappedTheEnds() {
        ApprovalStalenessFixture.Pair p = f.pair();
        String proposal = f.proposeRoute(p.connection().getId(), null);
        f.stack.execute(human("Swap", () -> p.connection().connect(p.b(), p.a()),
                () -> p.connection().connect(p.a(), p.b())));

        assertRefused(proposal, p.connection(), ProposalStalenessGuard.endMovedMessage("Ledger", CONNECTION));
    }

    /**
     * A connection a batch has queued is not connected until the batch commits, so its proposal records
     * no ends; once the batch commits, the ends it gains are not mistaken for ends that moved.
     */
    @Test
    public void approval_shouldApply_whenTheConnectionWasQueuedAndTheBatchCommittedFirst() {
        IArchimateElement ledger = f.element("el-a", "Ledger");
        IArchimateElement billing = f.element("el-b", "Billing");
        com.archimatetool.model.IArchimateRelationship rel = f.relationship("rel-ab", ledger, billing);
        f.group("grp", "Finance", 600, 150, 300, 200);
        IDiagramModelArchimateObject a = f.object("vo-a", ledger, f.view, 100, 0, 120, 60);
        f.dispatcher.beginBatch(SESSION, "queued connection to a queued nested end");
        String b = f.accessor.addToView(SESSION, f.view.getId(), billing.getId(), 20, 30, 120, 60, false, "grp",
                null, null).entity().viewObject().viewObjectId();
        String c = f.accessor.addConnectionToView(SESSION, f.view.getId(), rel.getId(), a.getId(), b, null, null,
                null, null, null).entity().viewConnectionId();
        String proposal = f.proposeConnectionUpdate(c, List.of(new net.vheerden.archi.mcp.response.dto.BendpointDto(
                1, 1, 1, 1)));
        assertTrue("precondition: no ends recorded for a connection not yet connected",
                f.dispatcher.getPendingProposals(SESSION).get(0).capture().connections().get(c).endBounds().isEmpty());
        f.dispatcher.endBatch(SESSION, true);

        assertNull(f.approveOrRefusal(proposal));
    }

    @Test
    public void approval_shouldApply_whenTheHumanOnlyRenamedAnEnd() {
        ApprovalStalenessFixture.Pair p = f.pair();
        String proposal = f.proposeRoute(p.connection().getId(), null);
        IArchimateElement billing = p.b().getArchimateElement();
        f.stack.execute(human("Rename", () -> billing.setName("Invoicing"), () -> billing.setName("Billing")));

        assertNull("the route does not depend on an end's name", f.approveOrRefusal(proposal));
        assertFalse(route(p.connection()).isEmpty());
    }

    @Test
    public void approval_shouldRefuseAsStale_withTheCardsReasonUnchanged_whenTheHumanDeletedAnEnd() {
        ApprovalStalenessFixture.Pair p = f.pair();
        String proposal = f.proposeRoute(p.connection().getId(), null);
        f.stack.execute(human("Delete Billing", () -> {
            p.connection().disconnect();
            f.view.getChildren().remove(p.b());
        }, () -> {
            f.view.getChildren().add(p.b());
            p.connection().reconnect();
        }));

        net.vheerden.archi.mcp.model.exceptions.MutationException refused = f.approveRefusal(proposal);
        assertTrue(String.valueOf(refused),
                refused instanceof net.vheerden.archi.mcp.model.exceptions.ProposalStaleException);
        assertEquals(net.vheerden.archi.mcp.response.ErrorCode.PROPOSAL_STALE,
                ((net.vheerden.archi.mcp.model.exceptions.ProposalStaleException) refused).getErrorCode());
        assertEquals("the card shows the same reason", ProposalStalenessGuard.removedMessage(p.connection().getId()),
                refused.getMessage());
        assertTrue("the proposal stays queued", f.stillQueued(proposal));
    }

    @Test
    public void approval_shouldSayRemoved_whenTheHumanDeletedAnEnd() {
        ApprovalStalenessFixture.Pair p = f.pair();
        String proposal = f.proposeRoute(p.connection().getId(), null);
        f.stack.execute(human("Delete Billing", () -> {
            p.connection().disconnect();
            f.view.getChildren().remove(p.b());
        }, () -> {
            f.view.getChildren().add(p.b());
            p.connection().reconnect();
        }));

        assertEquals("deleting an end removes its connection, and that is what the human is told",
                ProposalStalenessGuard.removedMessage(p.connection().getId()), f.approveOrRefusal(proposal));
    }

    @Test
    public void approval_shouldApply_whenOnlyTheAgentMovedAnEnd() {
        ApprovalStalenessFixture.Pair p = f.pair();
        String proposal = f.proposeRoute(p.connection().getId(), null);
        f.accessor.updateViewObject(SESSION, p.b().getId(), 700, 600, null, null, null, null, null, null,
                null, null, null, null);
        assertEquals("precondition: the agent's move landed", 600, p.b().getBounds().getY());

        assertNull("an agent-authored move is forgiven", f.approveOrRefusal(proposal));
    }

    private void assertRefused(String proposal, IDiagramModelArchimateConnection connection, String message) {
        Command lastHuman = f.stack.getUndoCommand();
        List<?> before = route(connection);
        assertEquals(message, f.approveOrRefusal(proposal));
        assertTrue("the card stays queued", f.stillQueued(proposal));
        assertEquals("the route is untouched", before, route(connection));
        assertSame("nothing was added to the undo stack", lastHuman, f.stack.getUndoCommand());
    }
}
