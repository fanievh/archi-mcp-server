package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final int NORTH = 1;
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
                Map.of("c1", EAST), Map.of("c1", suppressed), null);
        assertTrue("Suppressed label must not be offset", commands.isEmpty());
    }

    @Test
    public void buildOffsetCommands_emitsForVisibleConnection_whenSupported() {
        IDiagramModelArchimateConnection visible = conn("c1", true);
        assumeTrue("Label-offset feature only present on newer platforms",
                RelativePositionFeature.isSupported(visible));

        List<Command> commands = LabelOffsetSupport.buildOffsetCommands(
                Map.of("c1", EAST), Map.of("c1", visible), null);
        assertEquals("One offset command for a visible, supported connection", 1, commands.size());
        assertTrue(commands.get(0) instanceof SetTextRelativePositionCommand);
        assertEquals(EAST, ((SetTextRelativePositionCommand) commands.get(0)).getNewRelativePosition());
    }

    @Test
    public void buildOffsetCommands_writesCentre_overAHeldNorth() {
        // A reset is an ordinary write of a differing value: Centre (2) over a held North (1).
        IDiagramModelArchimateConnection held = conn("c1", true);
        assumeTrue("Label-offset feature only present on newer platforms",
                RelativePositionFeature.isSupported(held));
        RelativePositionFeature.set(held, NORTH);

        List<Command> commands = LabelOffsetSupport.buildOffsetCommands(
                Map.of("c1", RelativePositionFeature.CENTER), Map.of("c1", held), null);

        assertEquals("One reset command", 1, commands.size());
        assertEquals(RelativePositionFeature.CENTER,
                ((SetTextRelativePositionCommand) commands.get(0)).getNewRelativePosition());
    }

    @Test
    public void buildOffsetCommands_writesNothing_whenTheHeldAnchorIsAlreadyTheValue() {
        IDiagramModelArchimateConnection centred = conn("c1", true);
        assumeTrue("Label-offset feature only present on newer platforms",
                RelativePositionFeature.isSupported(centred));

        assertTrue("Centre over a held Centre changes nothing", LabelOffsetSupport.buildOffsetCommands(
                Map.of("c1", RelativePositionFeature.CENTER), Map.of("c1", centred), null).isEmpty());
    }

    @Test
    public void offsetWritableIds_admitsOnlyWhatTheWriteWouldWrite() {
        // The scorer credits an offset only for these ids, so they must be exactly the connections
        // buildOffsetCommands writes to: visible and on a platform with the feature.
        IDiagramModelArchimateConnection visible = conn("v", true);
        IDiagramModelArchimateConnection hidden = conn("h", false);
        boolean supported = RelativePositionFeature.isSupported(visible);

        assertEquals(supported ? Set.of("v") : Set.of(),
                LabelOffsetSupport.offsetWritableIds(true, List.of(visible, hidden), null));
        assertEquals(supported, LabelOffsetSupport.isOffsetWritable(visible, null));
        assertFalse(LabelOffsetSupport.isOffsetWritable(hidden, null));
        assertFalse(LabelOffsetSupport.isOffsetWritable(null, null));
        assertEquals("a pass that writes no offsets admits none", Set.of(),
                LabelOffsetSupport.offsetWritableIds(false, List.of(visible, hidden), null));
        assertEquals("the write agrees with the predicate for every connection",
                supported ? 1 : 0, LabelOffsetSupport.buildOffsetCommands(
                        Map.of("v", EAST, "h", EAST), Map.of("v", visible, "h", hidden), null).size());
    }

    @Test
    public void isOffsetWritable_readsTheVisibilityTheBatchWillCommit() {
        boolean supported = RelativePositionFeature.isSupported(conn("probe", true));
        IDiagramModelArchimateConnection shownByBatch = conn("s", false);
        IDiagramModelArchimateConnection hiddenByBatch = conn("h", true);
        Map<String, Boolean> queued = Map.of("s", true, "h", false);

        assertEquals("a label the batch will show is writable where the feature is", supported,
                LabelOffsetSupport.isOffsetWritable(shownByBatch, queued));
        assertFalse("a label the batch will hide is not", LabelOffsetSupport.isOffsetWritable(hiddenByBatch, queued));
        assertFalse("control: the model hides the first", LabelOffsetSupport.isOffsetWritable(shownByBatch, null));
        assertEquals("control: the model shows the second", supported,
                LabelOffsetSupport.isOffsetWritable(hiddenByBatch, null));
        assertEquals("a map that does not name the connection reads the model", supported,
                LabelOffsetSupport.isOffsetWritable(hiddenByBatch, Map.of("other", false)));
    }

    @Test
    public void offsetWrite_followsTheVisibilityTheBatchWillCommit() {
        assumeTrue("Label-offset feature is only present on newer platforms",
                RelativePositionFeature.isSupported(conn("probe", true)));
        IDiagramModelArchimateConnection shownByBatch = conn("s", false);
        IDiagramModelArchimateConnection hiddenByBatch = conn("h", true);
        Map<String, Boolean> queued = Map.of("s", true, "h", false);
        Map<String, Integer> offsets = Map.of("s", EAST, "h", EAST);
        Map<String, IDiagramModelArchimateConnection> lookup = Map.of("s", shownByBatch, "h", hiddenByBatch);
        List<IDiagramModelArchimateConnection> both = List.of(shownByBatch, hiddenByBatch);

        assertEquals("control: the model's visibility admits the second", List.of("h"),
                offsetTargets(LabelOffsetSupport.buildOffsetCommands(offsets, lookup, null)));
        assertEquals("the batch's admits the first", List.of("s"),
                offsetTargets(LabelOffsetSupport.buildOffsetCommands(offsets, lookup, queued)));
        assertEquals("the scorer's ids agree with the write", Set.of("s"),
                LabelOffsetSupport.offsetWritableIds(true, both, queued));
        assertEquals("control: and with the model's", Set.of("h"),
                LabelOffsetSupport.offsetWritableIds(true, both, null));
        assertEquals("a pass that writes no offsets admits none, batch or not", Set.of(),
                LabelOffsetSupport.offsetWritableIds(false, both, queued));
        assertEquals("the routing write-back carries the batch's visibility to the offset", List.of("s"),
                offsetTargets(LabelOffsetSupport.buildLabelCommands(Map.of(), offsets, both, queued)));
    }

    private static List<String> offsetTargets(List<Command> commands) {
        List<String> ids = new java.util.ArrayList<>();
        for (Command command : commands) {
            if (command instanceof SetTextRelativePositionCommand set) {
                ids.add(set.getConnection().getId());
            }
        }
        return ids;
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
                Map.of("a", 1), Map.of("a", EAST), List.of(a), null);

        assertEquals("One position + one offset command", 2, commands.size());
        assertTrue("Carries a position command",
                commands.stream().anyMatch(c -> c instanceof SetTextPositionCommand));
        assertTrue("Carries an offset command",
                commands.stream().anyMatch(c -> c instanceof SetTextRelativePositionCommand));
    }

    @Test
    public void buildLabelCommands_writesOnlyTheLabelsOfTheConnectionsItIsGiven() {
        // A routing pass passes the connections whose labels it writes: a position and an anchor for anyone else
        // (a route it does not apply, a label it hides) are dropped. Null writes every connection, as labels-only does.
        IDiagramModelArchimateConnection written = conn("w", true);
        IDiagramModelArchimateConnection held = conn("h", true);
        written.setTextPosition(0);
        held.setTextPosition(0);
        Map<String, Integer> positions = Map.of("w", 1, "h", 1);
        Map<String, Integer> offsets = Map.of("w", EAST, "h", EAST);
        List<IDiagramModelArchimateConnection> both = List.of(written, held);
        int offsetsPer = RelativePositionFeature.isSupported(written) ? 1 : 0;

        List<Command> filtered = LabelOffsetSupport.buildLabelCommands(positions, offsets, both, null, Set.of("w"));
        List<Command> unfiltered = LabelOffsetSupport.buildLabelCommands(positions, offsets, both, null, null);

        assertEquals(1 + offsetsPer, filtered.size());
        assertTrue("every command is for the written connection", filtered.stream().allMatch(c ->
                (c instanceof SetTextPositionCommand p ? p.getConnection() : ((SetTextRelativePositionCommand) c)
                        .getConnection()) == written));
        assertEquals(1, LabelOffsetSupport.countOptimizedLabels(filtered));
        assertEquals("null writes both", 2 + 2 * offsetsPer, unfiltered.size());
        assertEquals(2, LabelOffsetSupport.countOptimizedLabels(unfiltered));
        assertEquals("the four-argument form is the unfiltered one", unfiltered.size(),
                LabelOffsetSupport.buildLabelCommands(positions, offsets, both, null).size());
    }

    @Test
    public void buildLabelCommands_emitsNoOffset_whenOffsetsEmpty() {
        // The auto-layout / grouped path: emitLabelOffsets=false → buildLabelCommands gets an EMPTY offsets
        // map, so the shared routing core emits ONLY position commands and never an offset (no double-apply
        // with executeLabelFallback's own offset pass). Identical to the prior inline write-back.
        IDiagramModelArchimateConnection a = conn("a", true);
        a.setTextPosition(0);

        List<Command> commands = LabelOffsetSupport.buildLabelCommands(
                Map.of("a", 1), Map.of(), List.of(a), null);

        assertEquals("Only the position command — no offset from the shared core", 1, commands.size());
        assertTrue("It is a position command", commands.get(0) instanceof SetTextPositionCommand);
    }

    @Test
    public void buildLabelCommands_emitsNothing_whenNoPositionChangeAndNoOffsets() {
        // Position already at the chosen value and no offsets → nothing to write.
        IDiagramModelArchimateConnection a = conn("a", true);
        a.setTextPosition(1);

        List<Command> commands = LabelOffsetSupport.buildLabelCommands(
                Map.of("a", 1), Map.of(), List.of(a), null);

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
    public void merge_takesThePositionsAndTheOffsetsTogether() {
        IDiagramModelArchimateConnection a = conn("a", true);
        CompoundCommand source = new CompoundCommand();
        Command position = new SetTextPositionCommand(a, 1);
        Command offset = new SetTextRelativePositionCommand(a, EAST);
        source.add(position);
        source.add(offset);

        CompoundCommand target = new CompoundCommand();
        LabelOffsetSupport.merge(target, source);

        assertEquals(List.of(position, offset), target.getCommands());
    }
}
