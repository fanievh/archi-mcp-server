package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.SESSION;
import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.human;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateConnection;

/**
 * Pins that {@link SetTextPositionCommand} and {@link SetTextRelativePositionCommand} read what their
 * undo restores when they run, not when they are built, and re-arm that snapshot on every undo.
 *
 * <p>These commands reach the stack only inside the routing and label tools' compounds, which are
 * built when the agent proposes (or queues) and run when the card is approved (or the batch
 * commits). Each case here builds the command, lets something change the label between build and
 * run — the agent's own immediate call, or a human command on the stack — and then runs it.</p>
 */
public class LabelPositionExecuteTimeSnapshotTest {

    private static final int SOURCE = 0;
    private static final int MIDDLE = 1;
    private static final int TARGET = 2;
    private static final int EAST = 16;
    private static final int NORTH = 4;

    private ApprovalStalenessFixture f;
    private IDiagramModelArchimateConnection c;

    @Before
    public void setUp() {
        f = new ApprovalStalenessFixture();
        c = f.pair().connection();
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void undo_shouldRestoreTheAgentsLaterPosition_whenTheCommandRunsAfterIt() {
        assertEquals("precondition: built at the middle", MIDDLE, c.getTextPosition());
        SetTextPositionCommand cmd = new SetTextPositionCommand(c, TARGET);
        f.accessor.updateViewConnection(SESSION, c.getId(), null, null, null, null, SOURCE);
        assertEquals("precondition: the agent's own change landed", SOURCE, c.getTextPosition());

        f.stack.execute(cmd);
        assertEquals(TARGET, c.getTextPosition());
        f.stack.undo();
        assertEquals("undo gives back what the label held just before the change ran", SOURCE,
                c.getTextPosition());
    }

    @Test
    public void undo_shouldRestoreTheHumansPosition_whenTheHumanMovedTheLabelBetweenBuildAndRun() {
        SetTextPositionCommand cmd = new SetTextPositionCommand(c, TARGET);
        f.stack.execute(human("Move label", () -> c.setTextPosition(SOURCE), () -> c.setTextPosition(MIDDLE)));
        f.stack.execute(cmd);
        f.stack.undo();
        assertEquals(SOURCE, c.getTextPosition());
    }

    @Test
    public void undo_shouldRestoreTheOffsetHeldWhenTheChangeRan() {
        assumeTrue("the label offset exists only on newer platforms", RelativePositionFeature.isSupported(c));
        SetTextRelativePositionCommand cmd = new SetTextRelativePositionCommand(c, EAST);
        f.stack.execute(human("Offset label", () -> RelativePositionFeature.set(c, NORTH),
                () -> RelativePositionFeature.set(c, RelativePositionFeature.CENTER)));
        f.stack.execute(cmd);
        assertEquals(EAST, RelativePositionFeature.get(c));
        f.stack.undo();
        assertEquals(NORTH, RelativePositionFeature.get(c));
    }

    @Test
    public void offset_shouldStaySilent_onAConnectionWithoutTheFeature() {
        SetTextRelativePositionCommand cmd = new SetTextRelativePositionCommand(null, EAST);
        cmd.execute();
        cmd.undo();
        assertEquals(RelativePositionFeature.CENTER, cmd.getOldRelativePosition());
    }

    // ---- the snapshot is re-armed by undo ----------------------------------------------------

    @Test
    public void execute_shouldSnapshotAgain_afterAnUndo() {
        SetTextPositionCommand position = new SetTextPositionCommand(c, TARGET);
        position.execute();
        position.undo();
        c.setTextPosition(SOURCE);
        position.execute();
        position.undo();
        assertEquals("undo lands on what the second run met", SOURCE, c.getTextPosition());

        assumeTrue(RelativePositionFeature.isSupported(c));
        SetTextRelativePositionCommand offset = new SetTextRelativePositionCommand(c, EAST);
        offset.execute();
        offset.undo();
        RelativePositionFeature.set(c, NORTH);
        offset.execute();
        offset.undo();
        assertEquals(NORTH, RelativePositionFeature.get(c));
    }

    @Test
    public void execute_shouldKeepItsSnapshot_whenRunAgainWithoutAnUndo() {
        SetTextPositionCommand position = new SetTextPositionCommand(c, TARGET);
        position.execute();
        position.execute();
        position.undo();
        assertEquals(MIDDLE, c.getTextPosition());

        assumeTrue(RelativePositionFeature.isSupported(c));
        SetTextRelativePositionCommand offset = new SetTextRelativePositionCommand(c, EAST);
        offset.execute();
        offset.execute();
        offset.undo();
        assertEquals(RelativePositionFeature.CENTER, RelativePositionFeature.get(c));
    }

    @Test
    public void undo_shouldChangeNothing_beforeAnyExecute() {
        c.setTextPosition(SOURCE);
        new SetTextPositionCommand(c, TARGET).undo();
        assertEquals(SOURCE, c.getTextPosition());

        assumeTrue(RelativePositionFeature.isSupported(c));
        RelativePositionFeature.set(c, NORTH);
        new SetTextRelativePositionCommand(c, EAST).undo();
        assertEquals(NORTH, RelativePositionFeature.get(c));
    }

    @Test
    public void stack_undoRedoUndo_shouldBeStable() {
        f.stack.execute(new SetTextPositionCommand(c, TARGET));
        for (int round = 0; round < 2; round++) {
            f.stack.undo();
            assertEquals(MIDDLE, c.getTextPosition());
            f.stack.redo();
            assertEquals(TARGET, c.getTextPosition());
        }
    }

    // ---- built and run back to back, as an immediate call runs them, nothing changes ----------

    @Test
    public void immediate_shouldStoreAndUndoTheSame_whenBuiltAndRunBackToBack() {
        c.setTextPosition(SOURCE);
        f.stack.execute(new SetTextPositionCommand(c, TARGET));
        assertEquals(TARGET, c.getTextPosition());
        f.stack.undo();
        assertEquals(SOURCE, c.getTextPosition());

        assumeTrue(RelativePositionFeature.isSupported(c));
        RelativePositionFeature.set(c, NORTH);
        f.stack.execute(new SetTextRelativePositionCommand(c, EAST));
        assertEquals(EAST, RelativePositionFeature.get(c));
        f.stack.undo();
        assertEquals(NORTH, RelativePositionFeature.get(c));
    }
}
