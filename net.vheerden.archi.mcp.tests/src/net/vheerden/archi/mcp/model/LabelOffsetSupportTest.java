package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;

/**
 * Tests for {@link LabelOffsetSupport} — the label-offset command plumbing extracted from the fallback.
 * Uses real EMF connections via {@link IArchimateFactory#eINSTANCE}.
 */
public class LabelOffsetSupportTest {

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;

    private static final int EAST = 16;

    private IDiagramModelArchimateConnection conn(String id, boolean nameVisible) {
        IDiagramModelArchimateConnection c = factory.createDiagramModelArchimateConnection();
        c.setId(id);
        c.setNameVisible(nameVisible);
        return c;
    }

    @Test
    public void buildOffsetCommands_skipsSuppressedLabel() {
        // A suppressed label renders nothing → it must never get an offset command (regardless of platform).
        IDiagramModelArchimateConnection suppressed = conn("c1", false);
        List<Command> commands = LabelOffsetSupport.buildOffsetCommands(
                Map.of("c1", EAST), Map.of("c1", suppressed));
        assertTrue("Suppressed label must not be offset", commands.isEmpty());
    }

    @Test
    public void buildOffsetCommands_emitsForVisibleConnection_whenSupported() {
        IDiagramModelArchimateConnection visible = conn("c1", true);
        assumeTrue("Label-offset feature only present on newer platforms",
                RelativePositionFeature.isSupported(visible));

        List<Command> commands = LabelOffsetSupport.buildOffsetCommands(
                Map.of("c1", EAST), Map.of("c1", visible));
        assertEquals("One offset command for a visible, supported connection", 1, commands.size());
        assertTrue(commands.get(0) instanceof SetTextRelativePositionCommand);
        assertEquals(EAST, ((SetTextRelativePositionCommand) commands.get(0)).getNewRelativePosition());
    }

    @Test
    public void buildLabelCommands_emitsPositionAndOffset_whenOffsetsProvided() {
        // The auto-route path: emitLabelOffsets=true → a populated offsets map reaches buildLabelCommands.
        // A changed Middle position yields a SetTextPositionCommand; the own-endpoint offset yields a
        // SetTextRelativePositionCommand on the same connection.
        IDiagramModelArchimateConnection a = conn("a", true);
        a.setTextPosition(0); // current Source → a Middle (1) pick is a change
        assumeTrue("Label-offset feature only present on newer platforms",
                RelativePositionFeature.isSupported(a));

        List<Command> commands = LabelOffsetSupport.buildLabelCommands(
                Map.of("a", 1), Map.of("a", EAST), List.of(a));

        assertEquals("One position + one offset command", 2, commands.size());
        assertTrue("Carries a position command",
                commands.stream().anyMatch(c -> c instanceof SetTextPositionCommand));
        assertTrue("Carries an offset command",
                commands.stream().anyMatch(c -> c instanceof SetTextRelativePositionCommand));
    }

    @Test
    public void buildLabelCommands_emitsNoOffset_whenOffsetsEmpty() {
        // The auto-layout / grouped path: emitLabelOffsets=false → buildLabelCommands gets an EMPTY offsets
        // map, so the shared routing core emits ONLY position commands and never an offset (no double-apply
        // with executeLabelFallback's own offset pass). Identical to the prior inline write-back.
        IDiagramModelArchimateConnection a = conn("a", true);
        a.setTextPosition(0);

        List<Command> commands = LabelOffsetSupport.buildLabelCommands(
                Map.of("a", 1), Map.of(), List.of(a));

        assertEquals("Only the position command — no offset from the shared core", 1, commands.size());
        assertTrue("It is a position command", commands.get(0) instanceof SetTextPositionCommand);
    }

    @Test
    public void buildLabelCommands_emitsNothing_whenNoPositionChangeAndNoOffsets() {
        // Position already at the chosen value and no offsets → nothing to write.
        IDiagramModelArchimateConnection a = conn("a", true);
        a.setTextPosition(1);

        List<Command> commands = LabelOffsetSupport.buildLabelCommands(
                Map.of("a", 1), Map.of(), List.of(a));

        assertTrue("No change and no offset → no commands", commands.isEmpty());
    }

    @Test
    public void countOptimizedLabels_countsUniqueConnectionsAcrossCommandTypes() {
        IDiagramModelArchimateConnection a = conn("a", true);
        IDiagramModelArchimateConnection b = conn("b", true);
        CompoundCommand compound = new CompoundCommand();
        compound.add(new SetTextPositionCommand(a, 1));            // position change on a
        compound.add(new SetTextRelativePositionCommand(a, EAST)); // + offset on a → still one unique
        compound.add(new SetTextRelativePositionCommand(b, EAST)); // offset on b

        assertEquals("Two unique connections touched", 2, LabelOffsetSupport.countOptimizedLabels(compound));
    }

    @Test
    public void merge_onlyOffsets_keepsOffsetsDropsPositionChanges() {
        IDiagramModelArchimateConnection a = conn("a", true);
        CompoundCommand source = new CompoundCommand();
        source.add(new SetTextPositionCommand(a, 1));
        source.add(new SetTextRelativePositionCommand(a, EAST));

        CompoundCommand target = new CompoundCommand();
        LabelOffsetSupport.merge(target, source, true);

        assertEquals("Only the offset command is merged when onlyOffsets=true", 1, target.getCommands().size());
        assertTrue("Position change is left behind",
                LabelOffsetSupport.hasOffsetCommand(target));
    }
}
