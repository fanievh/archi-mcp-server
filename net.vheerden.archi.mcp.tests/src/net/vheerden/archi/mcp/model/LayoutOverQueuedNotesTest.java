package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateObject;

import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Pins that {@code auto-layout-and-route} in a batch warns of a note the batch placed on the view
 * earlier, as it warns of a note already there. The note has not been added when the layout is
 * computed, but it will be on the view when the layout is: the warning reads the view as the batch
 * will leave it.
 */
public class LayoutOverQueuedNotesTest {

    private static final String SESSION = ViewContentsFixture.SESSION;

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-layout-over-queued-notes");
        IDiagramModelArchimateObject a = f.object(f.view, "vo-1", f.element("p1"), 40, 40, 120, 55);
        IDiagramModelArchimateObject b = f.object(f.view, "vo-2", f.element("p2"), 400, 40, 120, 55);
        f.plainConnection("c-1", a, b);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private List<StructuredWarningDto> layOutInABatchAfterANoteAt(int x, int y, int width, int height) {
        f.dispatcher.beginBatch(SESSION, "a note, then a layout");
        f.accessor.addNoteToView(SESSION, f.view.getId(), "queued", null, null, x, y, width, height, null, null, null);
        AutoLayoutAndRouteResultDto dto = f.accessor.autoLayoutAndRoute(SESSION, f.view.getId(), "auto", "DOWN", 50,
                null, null).entity();
        f.dispatcher.endBatch(SESSION, true);
        return dto.structuredWarnings().stream()
                .filter(w -> StructuredWarningCodes.LAYOUT_OVERLAPS_NOTE.equals(w.code())).toList();
    }

    @Test
    public void shouldWarnOfANoteTheBatchPlacedEarlier_whenTheLayoutPutsObjectsUnderIt() {
        List<StructuredWarningDto> warnings = layOutInABatchAfterANoteAt(0, 0, 2400, 1600);

        assertEquals(warnings.toString(), 1, warnings.size());
        assertEquals(1, warnings.get(0).remediationViolatorIds().size());
        assertTrue(warnings.get(0).message(), warnings.get(0).message().startsWith("As read when this call was made"));
    }

    /** Control: a queued note clear of everything the layout places is not warned of. */
    @Test
    public void shouldWarnOfNothing_forANoteTheBatchPlacedClearOfTheLayout() {
        assertEquals(List.of(), layOutInABatchAfterANoteAt(20000, 20000, 100, 40));
    }
}
