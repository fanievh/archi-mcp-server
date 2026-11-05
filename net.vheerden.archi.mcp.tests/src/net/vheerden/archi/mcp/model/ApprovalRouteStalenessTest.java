package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.SESSION;
import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.route;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.gef.commands.Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateConnection;

import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Pins that approving a card is refused as stale when, during review, a human edited the route of a
 * connection the card touches — whether the card routes it, rectifies its terminals, or sets its
 * bendpoints — and that the refusal leaves the human's route and the undo stack as they were.
 *
 * <p>A connection's bendpoints are a containment reference, not an attribute, so the attribute
 * fingerprint never saw a route edit, and approving wrote the card's route over the human's.</p>
 */
public class ApprovalRouteStalenessTest {

    private static final List<BendpointDto> HUMAN = List.of(new BendpointDto(0, 80, 0, 80));
    private static final String BY_ENDS = "'Ledger' → 'Billing'";

    private ApprovalStalenessFixture f;
    private ApprovalStalenessFixture.Pair p;

    @Before
    public void setUp() {
        f = new ApprovalStalenessFixture();
        p = f.pair();
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void approval_shouldRefuseARouteCard_whenTheHumanRedrewTheRoute() {
        for (String mode : new String[] {null, "terminals-only"}) {
            p.connection().getBendpoints().clear(); // each mode starts from the same empty route
            String proposal = f.proposeRoute(p.connection().getId(), mode);
            assertRefusedOverHumanRoute(mode, proposal, p.connection(), HUMAN);
            f.dispatcher.removeProposal(SESSION, proposal);
        }
    }

    @Test
    public void approval_shouldRefuseABendpointUpdate_whenTheHumanRedrewTheRoute() {
        String proposal = f.proposeConnectionUpdate(p.connection().getId(),
                List.of(new BendpointDto(10, 10, 10, 10)));
        assertRefusedOverHumanRoute("update", proposal, p.connection(), HUMAN);
    }

    @Test
    public void approval_shouldRefuse_whenTheHumanClearedAStoredRoute() {
        ConnectionResponseBuilder.applyBendpointsToConnection(p.connection(), HUMAN);
        String proposal = f.proposeRoute(p.connection().getId(), null);
        assertRefusedOverHumanRoute("cleared", proposal, p.connection(), List.of());
    }

    @Test
    public void refusal_shouldNameANamedConnectionByItsName() {
        p.connection().getArchimateRelationship().setName("Posts invoices");
        String proposal = f.proposeRoute(p.connection().getId(), null);
        f.humanRoute(p.connection(), HUMAN);
        assertEquals(ProposalStalenessGuard.routeEditedMessage("'Posts invoices'"), f.approveOrRefusal(proposal));
    }

    @Test
    public void approval_shouldApply_whenTheRouteIsUntouchedAndTheHumanEditedSomethingElse() {
        String proposal = f.proposeRoute(p.connection().getId(), null);
        f.humanBounds(f.object("vo-far", f.element("el-far", "Far"), f.view, 0, 900, 120, 60), 40, 900, 120, 60);

        assertNull("an unrelated human edit does not refuse", f.approveOrRefusal(proposal));
        assertTrue("the card's route was written", !route(p.connection()).isEmpty());
    }

    @Test
    public void approval_shouldApply_whenOnlyTheAgentChangedTheRoute() {
        String proposal = f.proposeRoute(p.connection().getId(), null);
        f.accessor.updateViewConnection(SESSION, p.connection().getId(), HUMAN, null, null, null, null);

        assertNull("an agent-authored route change is forgiven", f.approveOrRefusal(proposal));
    }

    /** The human edits the route; approval must refuse with the route message and change nothing. */
    private void assertRefusedOverHumanRoute(String label, String proposal,
            IDiagramModelArchimateConnection connection, List<BendpointDto> humanRoute) {
        f.humanRoute(connection, humanRoute);
        Command humanEdit = f.stack.getUndoCommand();

        assertEquals(label, ProposalStalenessGuard.routeEditedMessage(BY_ENDS), f.approveOrRefusal(proposal));
        assertTrue(label + ": the card stays queued", f.stillQueued(proposal));
        assertEquals(label + ": the human's route is untouched", humanRoute, route(connection));
        assertSame(label + ": nothing was added to the undo stack", humanEdit, f.stack.getUndoCommand());
    }
}
