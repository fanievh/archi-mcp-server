package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelGroup;

import net.vheerden.archi.mcp.model.routing.FailedConnection;
import net.vheerden.archi.mcp.model.routing.MoveRecommendation;

/**
 * The auto-route response's id-to-name table: both ends of every targeted connection, plus any
 * recommended element that is not one of them, resolved through the model.
 */
public class AutoRouteNameLookupTest {

    private final IArchimateFactory f = IArchimateFactory.eINSTANCE;

    @Test
    public void shouldNameConnectionEndsAndResolveARecommendedObstacleThroughTheModel() {
        IArchimateModel model = f.createArchimateModel();
        model.setDefaults();
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        model.getDefaultFolderForObject(view).getElements().add(view);

        IDiagramModelGroup source = group(view, "Source");
        IDiagramModelGroup target = group(view, "Target");
        IDiagramModelGroup obstacle = group(view, "Obstacle");
        IDiagramModelConnection conn = f.createDiagramModelConnection();
        conn.connect(source, target);

        Map<String, String> names = AutoRouteNameLookup.viewObjectNames(model, List.of(conn),
                List.of(), List.of(new MoveRecommendation(obstacle.getId(), obstacle.getId(),
                        -30, 0, "r", 1)));

        assertEquals(Map.of(source.getId(), "Source", target.getId(), "Target",
                obstacle.getId(), "Obstacle"), names);
    }

    @Test
    public void shouldBeEmpty_whenThereIsNothingToReport() {
        IArchimateModel model = f.createArchimateModel();
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        IDiagramModelConnection conn = f.createDiagramModelConnection();
        conn.connect(group(view, "A"), group(view, "B"));

        assertTrue(AutoRouteNameLookup.viewObjectNames(model, List.of(conn), List.<FailedConnection>of(),
                List.of()).isEmpty());
    }

    private IDiagramModelGroup group(IArchimateDiagramModel view, String name) {
        IDiagramModelGroup group = f.createDiagramModelGroup();
        group.setName(name);
        view.getChildren().add(group);
        return group;
    }
}
