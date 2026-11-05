package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.SESSION;
import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.human;
import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.route;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateConnection;

import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;

/**
 * Pins that {@link UpdateViewConnectionCommand} takes what its undo restores, and the values it keeps
 * for every field the caller did not name, from the connection as it meets it when it runs — not as it
 * was when the command was built.
 *
 * <p>The two moments differ whenever something runs between them: a proposal is built when the agent
 * proposes and runs when the human approves, and a batched update is built when it is queued and runs
 * when the batch commits. Anything applied in between — the agent's own later change, a human's edit,
 * an earlier update in the same batch — used to be undone by Undo, and for styling fields the caller
 * did not name, overwritten by the commit itself.</p>
 */
public class UpdateViewConnectionExecuteTimeSnapshotTest {

    private static final List<BendpointDto> AGENT_LATER = List.of(new BendpointDto(5, 5, 5, 5));
    private static final List<BendpointDto> HUMAN = List.of(new BendpointDto(0, 80, 0, 80));

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

    // ---- the approval arm --------------------------------------------------------------------

    /**
     * A card approved over the agent's own later change: the guard forgives an agent-authored change
     * (it is not a human's), so approval goes through, and Undo must give back the agent's change, not
     * the route the connection had when the card was proposed.
     */
    @Test
    public void undo_shouldRestoreTheAgentsLaterRoute_whenAFrozenRouteCardIsApprovedOverIt() {
        String proposal = f.proposeRoute(p.connection().getId(), null);
        f.accessor.updateViewConnection(SESSION, p.connection().getId(), AGENT_LATER, null, null, null, null);
        assertEquals("precondition: the agent's own change landed", AGENT_LATER, route(p.connection()));

        assertNull("an agent-only change is forgiven", f.approveOrRefusal(proposal));
        assertNotEquals("precondition: approval wrote the card's route", AGENT_LATER, route(p.connection()));

        f.stack.undo();
        assertEquals("undo gives back what the connection held just before approval", AGENT_LATER,
                route(p.connection()));
    }

    // A card approved after the human redrew its route is refused as stale before anything runs
    // (ApprovalRouteStalenessTest), so it has no undo to pin here.

    // ---- the batch arm -----------------------------------------------------------------------

    @Test
    public void undo_shouldRestoreTheHumansRoute_whenTheHumanEditedTheConnectionWhileTheBatchWasOpen() {
        f.dispatcher.beginBatch(SESSION, "route while a human edits");
        f.accessor.updateViewConnection(SESSION, p.connection().getId(), AGENT_LATER, null, null, null, null);
        f.humanRoute(p.connection(), HUMAN);
        f.dispatcher.endBatch(SESSION, true);
        assertEquals("the queued update ran", AGENT_LATER, route(p.connection()));

        f.stack.undo();
        assertEquals("undo of the commit gives back the human's route", HUMAN, route(p.connection()));
    }

    @Test
    public void commit_shouldKeepTheHumansLineColour_whenABatchedUpdateNamesOnlyTheFontColour() {
        IDiagramModelArchimateConnection c = p.connection();
        c.setLineColor("#111111");
        f.dispatcher.beginBatch(SESSION, "font colour while a human recolours the line");
        f.accessor.updateViewConnection(SESSION, c.getId(), null, null,
                new StylingParams(null, null, "#00aa00", null, null), null, null);
        f.stack.execute(human("Recolour line", () -> c.setLineColor("#ff0000"), () -> c.setLineColor("#111111")));
        assertEquals("precondition: the human's colour landed", "#ff0000", c.getLineColor());
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("the named field is written", "#00aa00", c.getFontColor());
        assertEquals("a field the update did not name is left as the human set it", "#ff0000", c.getLineColor());

        f.stack.undo();
        assertNull("undo restores the font colour", c.getFontColor());
        assertEquals("and leaves the human's line colour", "#ff0000", c.getLineColor());
    }

    @Test
    public void commit_shouldApplyBoth_andUndoToTheOriginal_whenOneBatchQueuesTwoUpdatesOnOneConnection() {
        IDiagramModelArchimateConnection c = p.connection();
        c.setLineWidth(1);
        c.setFont("Arial|9|0");
        f.dispatcher.beginBatch(SESSION, "two updates on one connection");
        f.accessor.updateViewConnection(SESSION, c.getId(), AGENT_LATER, null,
                new StylingParams(null, "#aa0000", null, null, 3), null, null);
        f.accessor.updateViewConnection(SESSION, c.getId(), HUMAN, null,
                new StylingParams(null, null, "#00bb00", null, null, null, null, null, null, null, "bold",
                        null, null, null, null, null), null, null);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("the later route wins", HUMAN, route(c));
        assertEquals("the first update's line colour survives the second", "#aa0000", c.getLineColor());
        assertEquals("and its line width", 3, c.getLineWidth());
        assertEquals("the second update's font colour", "#00bb00", c.getFontColor());
        assertEquals("the font style merged into the font as it stood then", "1|Arial|9.0|1|", c.getFont());

        f.stack.undo();
        assertEquals(List.of(), route(c));
        assertNull(c.getLineColor());
        assertEquals(1, c.getLineWidth());
        assertNull(c.getFontColor());
        assertEquals("Arial|9|0", c.getFont());
    }

    // ---- redo --------------------------------------------------------------------------------

    @Test
    public void redo_shouldWriteExactlyWhatTheFirstExecuteWrote_andUndoStably() {
        IDiagramModelArchimateConnection c = p.connection();
        c.setFont("Arial|9|0");
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(c,
                ConnectionResponseBuilder.createEmfBendpoints(AGENT_LATER),
                new StylingParams(null, "#aa0000", null, null, null, null, null, null, null, 14, null,
                        null, null, null, null, null), Boolean.FALSE, 2);
        f.stack.execute(cmd);
        String font = c.getFont();
        assertEquals("1|Arial|14.0|0|", font);

        for (int round = 0; round < 2; round++) {
            f.stack.undo();
            assertEquals(List.of(), route(c));
            assertNull(c.getLineColor());
            assertEquals("Arial|9|0", c.getFont());
            assertTrue(c.isNameVisible());
            assertEquals(1, c.getTextPosition());

            f.stack.redo();
            assertEquals(AGENT_LATER, route(c));
            assertEquals("#aa0000", c.getLineColor());
            assertEquals(font, c.getFont());
            assertFalse(c.isNameVisible());
            assertEquals(2, c.getTextPosition());
        }
    }

    @Test
    public void execute_shouldKeepItsFirstSnapshot_whenRunAgainWithoutAnUndo() {
        IDiagramModelArchimateConnection c = p.connection();
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(c,
                ConnectionResponseBuilder.createEmfBendpoints(AGENT_LATER),
                new StylingParams(null, "#aa0000", null, null, null), null, null);
        cmd.execute();
        cmd.execute();
        assertEquals(AGENT_LATER, route(c));

        cmd.undo();
        assertEquals("undo restores what was there before the first run", List.of(), route(c));
        assertNull(c.getLineColor());
    }

    // ---- the immediate arm is unchanged ------------------------------------------------------

    @Test
    public void immediate_shouldStoreAndReportTheSame_forStylingBendpointsAndLabel() {
        IDiagramModelArchimateConnection c = p.connection();
        c.setFont("Arial|9|0");
        c.setLineColor("#111111");
        MutationResult<ViewConnectionDto> result = f.accessor.updateViewConnection(SESSION, c.getId(), HUMAN,
                null, new StylingParams(null, null, "#00bb00", null, 2, null, null, null, null, null, "italic",
                        null, null, null, null, null), Boolean.FALSE, 0);

        ViewConnectionDto dto = result.entity();
        assertEquals(HUMAN, dto.bendpoints());
        assertEquals("#111111", dto.lineColor());
        assertEquals(Integer.valueOf(2), dto.lineWidth());
        assertEquals("#00bb00", dto.fontColor());
        assertEquals(Boolean.FALSE, dto.nameVisible());
        assertEquals(Integer.valueOf(0), dto.textPosition());
        assertEquals("italic", dto.fontStyle());

        assertEquals(HUMAN, route(c));
        assertEquals("#111111", c.getLineColor());
        assertEquals(2, c.getLineWidth());
        assertEquals("#00bb00", c.getFontColor());
        assertEquals("1|Arial|9.0|2|", c.getFont());
        assertFalse(c.isNameVisible());
        assertEquals(0, c.getTextPosition());

        f.stack.undo();
        assertEquals(List.of(), route(c));
        assertEquals("#111111", c.getLineColor());
        assertNull(c.getFontColor());
        assertEquals("Arial|9|0", c.getFont());
        assertTrue(c.isNameVisible());
        assertEquals(1, c.getTextPosition());
    }
}
