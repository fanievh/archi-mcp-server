package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;

/**
 * Tests for {@link SetTextRelativePositionCommand} and the reflective {@link RelativePositionFeature}.
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE}. The round-trip case only runs where
 * the platform exposes the label-offset accessors (newer Archi); on an older target it is
 * assumption-skipped, mirroring the runtime no-op. The null-connection guard runs on every platform.</p>
 */
public class SetTextRelativePositionCommandTest {

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;

    private static final int EAST = 16;

    @Test
    public void shouldRoundTripAnchor_whenFeaturePresent() {
        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        assumeTrue("Label-offset feature is only present on newer platforms",
                RelativePositionFeature.isSupported(conn));

        int original = RelativePositionFeature.get(conn);
        SetTextRelativePositionCommand cmd = new SetTextRelativePositionCommand(conn, EAST);
        assertEquals("Command captures the old anchor", original, cmd.getOldRelativePosition());
        assertEquals("Command carries the new anchor", EAST, cmd.getNewRelativePosition());

        cmd.execute();
        assertEquals("execute() writes the new anchor", EAST, RelativePositionFeature.get(conn));

        cmd.undo();
        assertEquals("undo() restores the old anchor", original, RelativePositionFeature.get(conn));
    }

    @Test
    public void nullConnection_isUnsupportedAndNoOp() {
        // The guard's unsupported path (also the older-platform path): get() defaults to CENTER and
        // set() must not throw. Portable across platforms (no connection → never supported).
        assertEquals("Unsupported → get() defaults to CENTER",
                RelativePositionFeature.CENTER, RelativePositionFeature.get(null));
        RelativePositionFeature.set(null, EAST); // must be a silent no-op (no throw)
    }
}
