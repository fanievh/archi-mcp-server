package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.FolderType;

import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Pins that every result path of {@code auto-layout-and-route} names the notes its layout leaves
 * under other objects: flat and grouped, each with and without a target rating, applied, queued in
 * a batch, and awaiting approval, and on the served response. The layout always lands its objects
 * near the canvas origin, so one wide note there is under them whatever ELK chooses; the objects
 * start far from it.
 *
 * <p>Display lane: the layout sizes elements and the routing pass assesses its candidates, both of
 * which reach SWT.</p>
 */
public class LayoutOverNotesServedTest {

    private static final String SESSION = ViewContentsFixture.SESSION;

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-layout-over-notes-served");
        IDiagramModelGroup left = f.group(f.view, "g-left", 3000, 3000, 400, 300);
        IDiagramModelGroup right = f.group(f.view, "g-right", 3600, 3000, 400, 300);
        IDiagramModelArchimateObject a = f.object(left, "vo-a", f.element("el-a"), 20, 40, 120, 55);
        IDiagramModelArchimateObject b = f.object(left, "vo-b", f.element("el-b"), 200, 160, 120, 55);
        IDiagramModelArchimateObject c = f.object(right, "vo-c", f.element("el-c"), 20, 40, 120, 55);
        connect("ab", a, b);
        connect("bc", b, c);
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

    private void wideNoteAtTheOrigin() {
        IDiagramModelNote note = f.note(f.view, "note-origin", 0, 0);
        note.setBounds(0, 0, 2400, 1600);
    }

    private AutoLayoutAndRouteResultDto run(String mode, String targetRating) {
        return f.accessor.autoLayoutAndRoute(SESSION, f.view.getId(), mode, "DOWN", 50, targetRating, null).entity();
    }

    private static List<StructuredWarningDto> noteWarnings(AutoLayoutAndRouteResultDto dto) {
        return dto.structuredWarnings().stream()
                .filter(w -> StructuredWarningCodes.LAYOUT_OVERLAPS_NOTE.equals(w.code())).toList();
    }

    private static void assertNamesTheNote(String path, AutoLayoutAndRouteResultDto dto, String opening) {
        List<StructuredWarningDto> warnings = noteWarnings(dto);
        assertEquals(path + ": one entry: " + dto.structuredWarnings(), 1, warnings.size());
        StructuredWarningDto w = warnings.get(0);
        assertEquals(path, List.of("note-origin"), w.remediationViolatorIds());
        assertTrue(path + ": " + w.message(), w.message().startsWith(opening));
        assertTrue(path + ": names an element it lands on: " + w.message(),
                w.message().contains("note 'note-origin' overlaps "));
    }

    @Test
    public void shouldNameTheNote_onEachResultPath() {
        wideNoteAtTheOrigin();
        for (String[] path : new String[][] {{"auto", null}, {"auto", "excellent"}, {"grouped", null},
                {"grouped", "excellent"}}) {
            f.view.getChildren().stream().filter(o -> o instanceof IDiagramModelGroup)
                    .forEach(g -> g.getBounds().setX(g.getBounds().getX() + 3000));
            assertNamesTheNote(path[0] + "/" + path[1], run(path[0], path[1]), "This layout left ");
        }
    }

    @Test
    public void shouldCarryNoNoteWarning_onEachResultPath_whenTheViewHasNoNotes() {
        for (String[] path : new String[][] {{"auto", null}, {"auto", "excellent"}, {"grouped", null},
                {"grouped", "excellent"}}) {
            assertEquals(path[0] + "/" + path[1], List.of(), noteWarnings(run(path[0], path[1])));
        }
    }

    @Test
    public void shouldCarryNoNoteWarning_whenTheNoteIsClearOfTheLayout() {
        IDiagramModelNote note = f.note(f.view, "note-far", 0, 0);
        note.setBounds(20000, 20000, 200, 80);

        assertEquals(List.of(), noteWarnings(run("auto", null)));
        assertEquals(List.of(), noteWarnings(run("grouped", null)));
    }

    @Test
    public void shouldNameTheNoteInTheDeferredTense_andNameEndBatch_whenTheLayoutIsQueued() {
        wideNoteAtTheOrigin();
        f.dispatcher.beginBatch(SESSION, "layout");
        AutoLayoutAndRouteResultDto dto = run("auto", null);
        f.dispatcher.endBatch(SESSION, false);

        assertNamesTheNote("queued", dto, "As read when this call was made, from the layout it computed, the"
                + " layout will leave ");
        assertTrue(noteWarnings(dto).get(0).message().contains("end-batch with rollback:true discards it."));
    }

    @Test
    public void shouldNameTheNoteInTheDeferredTense_andNameRejecting_whenTheLayoutAwaitsApproval() {
        wideNoteAtTheOrigin();
        f.approval = true;
        AutoLayoutAndRouteResultDto dto = run("auto", null);

        assertNamesTheNote("awaiting approval", dto, "As read when this call was made, from the layout it"
                + " computed, the layout will leave ");
        assertTrue(noteWarnings(dto).get(0).message().contains("rejecting it in Archi leaves the view as it is."));
    }

    @Test
    public void shouldServeTheWarning_onTheToolResponse() {
        wideNoteAtTheOrigin();
        String served = f.serve("auto-layout-and-route", Map.of("viewId", f.view.getId(), "mode", "auto"));

        assertTrue(served, served.contains("\"code\":\"LAYOUT_OVERLAPS_NOTE\""));
        assertTrue(served, served.contains("\"remediationViolatorIds\":[\"note-origin\"]"));
    }
}
