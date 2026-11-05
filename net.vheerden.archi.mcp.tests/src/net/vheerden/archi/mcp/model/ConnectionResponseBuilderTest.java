package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Pins how {@link ConnectionResponseBuilder#resolveEffectiveBendpointsFromConnection} tells a
 * connection that has no end yet from one whose end is not an element.
 *
 * <p>A connection whose add has not run has both ends null, so a check of either end alone passes
 * every pin that goes through the accessor. These cases unset one end at a time, which only a
 * direct call can do.</p>
 */
public class ConnectionResponseBuilderTest {

    private static final List<AbsoluteBendpointDto> POINT = List.of(new AbsoluteBendpointDto(610, 30));

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IDiagramModelArchimateObject a;
    private IDiagramModelArchimateObject b;
    private IDiagramModelArchimateConnection connection;

    @Before
    public void setUp() {
        a = factory.createDiagramModelArchimateObject();
        a.setArchimateElement(factory.createApplicationComponent());
        a.setBounds(400, 0, 120, 60);
        b = factory.createDiagramModelArchimateObject();
        b.setArchimateElement(factory.createApplicationComponent());
        b.setBounds(700, 200, 120, 60);
        connection = factory.createDiagramModelArchimateConnection();
        connection.setId("conn-one-end");
    }

    @Test
    public void shouldNameTheUnconnectedConnection_whenOnlyTheTargetIsUnset() {
        connection.setSource(a);
        assertNotConnectedYet();
    }

    @Test
    public void shouldNameTheUnconnectedConnection_whenOnlyTheSourceIsUnset() {
        connection.setTarget(b);
        assertNotConnectedYet();
    }

    @Test
    public void shouldConvert_whenBothEndsAreElements() {
        connection.setArchimateRelationship(factory.createAssociationRelationship());
        connection.connect(a, b);
        assertEquals(List.of(new BendpointDto(150, 0, -150, -200)),
                ConnectionResponseBuilder.resolveEffectiveBendpointsFromConnection(null, POINT, connection));
    }

    @Test
    public void shouldReturnTheRelativeBendpoints_whenNoAbsoluteOnesAreGiven() {
        List<BendpointDto> relative = List.of(new BendpointDto(1, 2, 3, 4));
        assertEquals(relative,
                ConnectionResponseBuilder.resolveEffectiveBendpointsFromConnection(relative, null, connection));
    }

    private void assertNotConnectedYet() {
        try {
            ConnectionResponseBuilder.resolveEffectiveBendpointsFromConnection(null, POINT, connection);
            fail("expected the canvas points to be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertEquals("Cannot use absoluteBendpoints: connection 'conn-one-end' was added earlier in this "
                    + "batch or bulk-mutate and is not connected to its ends until that is committed",
                    e.getMessage());
            assertEquals("Give the route as absoluteBendpoints on the add-connection-to-view that adds it, use "
                    + "relative bendpoints (startX/startY/endX/endY), or update the connection once it is "
                    + "committed: after end-batch, or after the bulk-mutate returns when no batch is open",
                    e.getSuggestedCorrection());
        }
    }
}
