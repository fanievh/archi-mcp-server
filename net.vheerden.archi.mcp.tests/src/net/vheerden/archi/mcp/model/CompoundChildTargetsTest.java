package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;

/**
 * Headless coverage of the compound child-id extractor (disposition (a)). Builds the
 * project-owned commands the layout/route gate-sites assemble — {@link UpdateViewObjectCommand},
 * {@link UpdateViewConnectionCommand}, {@link SetTextPositionCommand}, {@link AddConnectionToViewCommand} —
 * over real EMF fixtures (no model instantiation, no {@code Display}) and asserts
 * {@link CompoundChildTargets#collect} recovers exactly the pre-existing object ids each touches.
 */
public class CompoundChildTargetsTest {

    private static final IArchimateFactory FACTORY = IArchimateFactory.eINSTANCE;

    // ---- layout site: UpdateViewObjectCommand → diagram-object ids ----

    @Test
    public void shouldUnionAnchorAndTouchedObjectIds_forLayoutCompound() {
        CompoundCommand compound = new CompoundCommand();
        compound.add(new UpdateViewObjectCommand(dmo("d1"), 10, 10, 100, 50));
        compound.add(new UpdateViewObjectCommand(dmo("d2"), 20, 20, 100, 50));

        Set<String> ids = CompoundChildTargets.collect(compound, "view-1");

        assertEquals(Set.of("view-1", "d1", "d2"), ids);
    }

    @Test
    public void shouldKeepMultipleAnchors_forLayoutWithinGroup() {
        CompoundCommand compound = new CompoundCommand();
        compound.add(new UpdateViewObjectCommand(dmo("child"), 0, 0, 80, 40));

        Set<String> ids = CompoundChildTargets.collect(compound, "view-1", "group-1");

        assertEquals(Set.of("view-1", "group-1", "child"), ids);
    }

    // ---- route sites: connection ids ----

    @Test
    public void shouldTrackConnectionId_forUpdateViewConnectionCommand() {
        CompoundCommand compound = new CompoundCommand();
        compound.add(new UpdateViewConnectionCommand(conn("c1"), List.of()));

        assertTrue(CompoundChildTargets.collect(compound, "view-1").contains("c1"));
    }

    @Test
    public void shouldTrackConnectionId_forSetTextPositionCommand() {
        CompoundCommand compound = new CompoundCommand();
        compound.add(new SetTextPositionCommand(conn("c2"), 1));

        assertTrue(CompoundChildTargets.collect(compound, "view-1").contains("c2"));
    }

    @Test
    public void shouldTrackConnectionId_forSetTextRelativePositionCommand() {
        CompoundCommand compound = new CompoundCommand();
        compound.add(new SetTextRelativePositionCommand(conn("c3"), 16)); // EAST

        assertTrue(CompoundChildTargets.collect(compound, "view-1").contains("c3"));
    }

    // ---- auto-connect: track pre-existing endpoints, NOT the created connection ----

    @Test
    public void shouldTrackEndpoints_andSkipCreatedConnection_forAddConnection() {
        IDiagramModelArchimateObject src = dmo("src");
        IDiagramModelArchimateObject tgt = dmo("tgt");
        CompoundCommand compound = new CompoundCommand();
        compound.add(new AddConnectionToViewCommand(conn("new-conn"), src, tgt));

        Set<String> ids = CompoundChildTargets.collect(compound, "view-1");

        assertTrue("source endpoint tracked", ids.contains("src"));
        assertTrue("target endpoint tracked", ids.contains("tgt"));
        assertFalse("a being-created connection is not yet resolvable (B19) — not tracked",
                ids.contains("new-conn"));
    }

    // ---- edge cases ----

    @Test
    public void shouldReturnAnchorsOnly_whenCompoundNull() {
        assertEquals(Set.of("view-1"), CompoundChildTargets.collect(null, "view-1"));
    }

    @Test
    public void shouldFilterNullAndBlankAnchors() {
        assertEquals(Set.of("view-1"),
                CompoundChildTargets.collect(new CompoundCommand(), "view-1", null, "  "));
    }

    // ---- fixtures ----

    private static IDiagramModelArchimateObject dmo(String id) {
        IDiagramModelArchimateObject o = FACTORY.createDiagramModelArchimateObject();
        o.setId(id);
        o.setBounds(0, 0, 100, 50);
        return o;
    }

    private static IDiagramModelArchimateConnection conn(String id) {
        IDiagramModelArchimateConnection c = FACTORY.createDiagramModelArchimateConnection();
        c.setId(id);
        return c;
    }
}
