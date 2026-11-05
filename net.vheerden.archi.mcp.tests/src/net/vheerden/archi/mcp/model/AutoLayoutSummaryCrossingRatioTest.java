package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;

import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;

/**
 * Pins that the assessment summary {@code auto-layout-and-route} returns with a target rating
 * carries {@code crossingsPerConnection}, and that it is the value {@code assess-layout} reports on
 * the state the call left, flat and grouped, and on the served response.
 *
 * <p>Display lane: the layout sizes elements and the routing pass assesses its candidates, both of
 * which reach SWT.</p>
 */
public class AutoLayoutSummaryCrossingRatioTest {

    private static final String SESSION = ViewContentsFixture.SESSION;

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-auto-layout-crossing-ratio");
        var left = f.group(f.view, "g-left", 0, 0, 500, 400);
        var right = f.group(f.view, "g-right", 700, 0, 500, 400);
        IDiagramModelArchimateObject[] l = new IDiagramModelArchimateObject[3];
        IDiagramModelArchimateObject[] r = new IDiagramModelArchimateObject[3];
        for (int i = 0; i < 3; i++) {
            l[i] = f.object(left, "vo-l" + i, f.element("el-l" + i), 20 + i * 150, 40, 120, 55);
            r[i] = f.object(right, "vo-r" + i, f.element("el-r" + i), 20 + i * 150, 200, 120, 55);
        }
        for (IDiagramModelArchimateObject a : l) {
            for (IDiagramModelArchimateObject b : r) {
                connect(a.getId() + "-" + b.getId(), a, b);
            }
        }
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private void connect(String id, IDiagramModelArchimateObject src, IDiagramModelArchimateObject tgt) {
        IArchimateRelationship rel = f.factory.createAssociationRelationship();
        rel.setId("rel-" + id);
        rel.setSource(src.getArchimateElement());
        rel.setTarget(tgt.getArchimateElement());
        f.model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        IDiagramModelArchimateConnection conn = f.factory.createDiagramModelArchimateConnection();
        conn.setId("conn-" + id);
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
    }

    @Test
    public void shouldCarryAssessLayoutsCrossingRatio_flatAndGrouped() {
        for (String mode : new String[] {"auto", "grouped"}) {
            AutoLayoutAndRouteResultDto dto = f.accessor.autoLayoutAndRoute(SESSION, f.view.getId(), mode, "DOWN",
                    50, "excellent", null).entity();
            assertNotNull(mode, dto.assessmentSummary());
            assertEquals(mode, Double.valueOf(f.accessor.assessLayout(f.view.getId()).crossingsPerConnection()),
                    dto.assessmentSummary().crossingsPerConnection());
        }
    }

    @Test
    public void shouldServeTheCrossingRatio_inTheAssessmentSummary() {
        String served = f.serve("auto-layout-and-route", Map.of("viewId", f.view.getId(), "mode", "auto",
                "targetRating", "excellent"));

        assertTrue(served, served.matches("(?s).*\"assessmentSummary\":\\{[^}]*\"crossingsPerConnection\":[0-9.]+.*"));
    }
}
