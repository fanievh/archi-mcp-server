package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;

import net.vheerden.archi.mcp.model.CapturedViewHarness.Ctx;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The remedy {@code assess-layout} offers for a redundant bendpoint is the one that removes it: write the connection's
 * bendpoints again without the reported point, through {@code update-view-connection}.
 *
 * <p>The view holds the shape a full re-route and a terminals-only pass both leave in place: two boxes one above the
 * other, joined by a straight vertical route whose first bendpoint sits on the source's bottom face and whose last
 * bendpoint stops 10 px short of the target's top face. The last one is collinear with the target's centre, so it is
 * redundant; the router never sees it as such, because its collinear sweep runs over the bendpoints alone and the
 * centre is not among them.</p>
 */
public class RedundantBendpointRemedyTest {

    @Test
    public void shouldClearTheReportedPoint_whenTheBendpointsAreWrittenWithoutIt() {
        Ctx c = CapturedViewHarness.newView();
        CapturedViewHarness.addBox(c, "s", "ApplicationComponent", null, 0, 0, 240, 80);
        CapturedViewHarness.addBox(c, "t", "ApplicationComponent", null, 0, 300, 240, 80);
        CapturedViewHarness.connect(c, "conn-st", "obj-s", "obj-t");
        IDiagramModelConnection conn = c.objects.get("s").getSourceConnections().get(0);
        // Source centre (120,40), target centre (120,340): (120,81) on the source face, (120,290) off the target face.
        bendpoint(conn, 0, 41, 0, -259);
        bendpoint(conn, 0, 250, 0, -50);

        ArchiModelAccessorImpl accessor = accessor(c);
        try {
            assertEquals("precondition: the off-face point is reported", 1,
                    accessor.assessLayout(CapturedViewHarness.VIEW_ID).connectionRedundantBendpointCount());

            accessor.updateViewConnection("remedy", "conn-st", null,
                    List.of(new AbsoluteBendpointDto(120, 81)), null, null, null);

            assertEquals("the point is gone", 1, conn.getBendpoints().size());
            IDiagramModelBendpoint kept = conn.getBendpoints().get(0);
            // The kept bendpoint is (120,81), on the same vertical line as both centres: the drawn route is unchanged.
            assertEquals("kept relative to the source centre", List.of(0, 41),
                    List.of(kept.getStartX(), kept.getStartY()));
            assertEquals("kept relative to the target centre", List.of(0, -259),
                    List.of(kept.getEndX(), kept.getEndY()));
            assertEquals(0, accessor.assessLayout(CapturedViewHarness.VIEW_ID).connectionRedundantBendpointCount());
        } finally {
            accessor.dispose();
        }
    }

    private static void bendpoint(IDiagramModelConnection conn, int startX, int startY, int endX, int endY) {
        IDiagramModelBendpoint bp = IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
        bp.setStartX(startX);
        bp.setStartY(startY);
        bp.setEndX(endX);
        bp.setEndY(endY);
        conn.getBendpoints().add(bp);
    }

    private static ArchiModelAccessorImpl accessor(Ctx c) {
        AutoRouteArmFixture.StubEditorModelManager manager = new AutoRouteArmFixture.StubEditorModelManager();
        manager.setModels(List.of(c.model));
        MutationDispatcher dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
        dispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(manager, dispatcher);
    }
}
