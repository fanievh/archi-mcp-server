package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * arrange-groups lays out no more grid columns than there are groups, and reports the count it used. A count past
 * the groups means one row of them. On {@code topology} the grid used to be sized by the caller's count, so a huge
 * one exhausted the heap, and on {@code grid} the response reported the requested count as the one used.
 */
public class ArrangeGroupsColumnCountTest {

    private static final String VIEW = "view-source";

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("arrange-groups-column-count-model");
        IDiagramModelArchimateObject[] members = new IDiagramModelArchimateObject[3];
        for (int i = 0; i < 3; i++) {
            IDiagramModelGroup group = h.f.group(h.f.view, "g" + i, 10 + 300 * i, 300, 200, 120);
            IArchimateElement element = h.f.element("el-g" + i);
            members[i] = h.f.object(group, "vo-g" + i, element, 20, 30, 120, 55);
        }
        connect("c01", members[0], members[1]);
        connect("c12", members[1], members[2]);
        connect("c02", members[0], members[2]);
    }

    private void connect(String id, IDiagramModelArchimateObject from, IDiagramModelArchimateObject to) {
        h.f.archimateConnection(id, h.f.relationship("rel-" + id, from.getArchimateElement(),
                to.getArchimateElement()), from, to);
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    @Test
    public void shouldReportTheColumnsUsed_notTheCountAskedFor_onAGrid() {
        JsonNode result = h.success("arrange-groups", "viewId", VIEW, "arrangement", "grid",
                "columns", Integer.MAX_VALUE).path("result");

        assertEquals(3, result.path("groupsPositioned").asInt());
        assertEquals(3, result.path("columnsUsed").asInt());
    }

    /**
     * No JUnit timeout: one runs the test on another thread, where arrange-groups' layout assessment waits on the
     * Display thread through {@code syncExec} and never returns. The heap exhaustion this pins fails it anyway.
     */
    @Test
    public void shouldLayOutAHugeColumnCountAsOneRow_onATopology() {
        JsonNode result = h.success("arrange-groups", "viewId", VIEW, "arrangement", "topology",
                "columns", Integer.MAX_VALUE).path("result");

        assertEquals(3, result.path("groupsPositioned").asInt());
        assertEquals(3, result.path("columnsUsed").asInt());
        JsonNode same = h.success("arrange-groups", "viewId", VIEW, "arrangement", "topology", "columns", 3)
                .path("result");
        assertEquals(same.path("layoutWidth"), result.path("layoutWidth"));
        assertFalse(result.path("layoutWidth").asInt() < 0);
    }

    @Test
    public void shouldStillUseTheColumnsAskedFor_whenThereAreAsManyGroups() {
        JsonNode result = h.success("arrange-groups", "viewId", VIEW, "arrangement", "grid", "columns", 2)
                .path("result");

        assertEquals(2, result.path("columnsUsed").asInt());
    }
}
