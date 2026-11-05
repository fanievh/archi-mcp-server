package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Pins the warning {@code auto-layout-and-route} carries when its layout leaves a note under an
 * object that is not a note. Notes are not laid out, so the layout moves everything else around
 * them; the warning reads the layout's own geometry (the bounds its commands give each object), so
 * it names the same notes whether the layout is applied, queued in a batch or awaiting approval,
 * and only the tense and the recovery differ.
 *
 * <p>The detector is driven directly with a hand-built layout, so nothing is laid out, routed or
 * measured and the class needs no display.</p>
 */
public class LayoutOverNotesWarningTest {

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-layout-over-notes");
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private static AutoLayoutAndRouteResultDto dto() {
        return new AutoLayoutAndRouteResultDto("view-source", "DOWN", 50, 1, 0, false, 1);
    }

    private static CompoundCommand layout(Object... objectAndBounds) {
        CompoundCommand compound = new CompoundCommand("layout");
        for (int i = 0; i < objectAndBounds.length; i += 5) {
            compound.add(new UpdateViewObjectCommand((IDiagramModelObject) objectAndBounds[i],
                    (int) objectAndBounds[i + 1], (int) objectAndBounds[i + 2], (int) objectAndBounds[i + 3],
                    (int) objectAndBounds[i + 4]));
        }
        return compound;
    }

    private IDiagramModelArchimateObject element(String id, int x, int y) {
        return f.object(f.view, "vo-" + id, f.element("el-" + id), x, y, 120, 60);
    }

    private AutoLayoutAndRouteResultDto warn(CompoundCommand layout, DispatchArm arm) {
        return AutoRouteWarnings.withLayoutOverNotes(dto(), f.view, layout, arm, null, null);
    }

    private static List<StructuredWarningDto> noteWarnings(AutoLayoutAndRouteResultDto dto) {
        return dto.structuredWarnings().stream()
                .filter(w -> StructuredWarningCodes.LAYOUT_OVERLAPS_NOTE.equals(w.code())).toList();
    }

    private static StructuredWarningDto only(AutoLayoutAndRouteResultDto dto) {
        List<StructuredWarningDto> warnings = noteWarnings(dto);
        assertEquals(dto.structuredWarnings().toString(), 1, warnings.size());
        return warnings.get(0);
    }

    @Test
    public void shouldNameTheNoteAndTheElement_whenTheLayoutPutsAnElementOnANote() {
        IDiagramModelArchimateObject a = element("a", 600, 40);
        f.note(f.view, "note-1", 40, 200);

        StructuredWarningDto w = only(warn(layout(a, 40, 200, 120, 60), DispatchArm.APPLIED));

        assertEquals(List.of("note-1"), w.remediationViolatorIds());
        assertEquals("update-view-object", w.remediationTool());
        assertTrue(w.message(), w.message().startsWith("This layout left 1 note overlap(s): note 'note-1'"
                + " overlaps element 'vo-a'."));
        assertTrue(w.message(), w.message().contains("Notes are not laid out"));
        assertTrue(w.message(), w.message().contains("position below-content"));
    }

    /** Order arm whose natural order is wrong: the element is on the note now, and the layout moves it off. */
    @Test
    public void shouldWarnOfNothing_whenTheLayoutMovesAnElementOffTheNoteItIsOnNow() {
        IDiagramModelArchimateObject a = element("a", 40, 200);
        f.note(f.view, "note-1", 40, 200);
        AutoLayoutAndRouteResultDto before = dto();

        AutoLayoutAndRouteResultDto after = AutoRouteWarnings.withLayoutOverNotes(before, f.view,
                layout(a, 600, 40, 120, 60), DispatchArm.APPLIED, null, null);

        assertSame(before, after);
    }

    @Test
    public void shouldNameTheNote_whenAnElementTheLayoutLeavesInPlaceIsOnIt() {
        element("a", 40, 200);
        IDiagramModelArchimateObject b = element("b", 600, 40);
        f.note(f.view, "note-1", 40, 200);

        assertEquals(List.of("note-1"), only(warn(layout(b, 600, 300, 120, 60), DispatchArm.APPLIED)).remediationViolatorIds());
    }

    @Test
    public void shouldReturnTheResultUntouched_onAViewWithNoNotes() {
        IDiagramModelArchimateObject a = element("a", 600, 40);
        element("b", 40, 200);
        AutoLayoutAndRouteResultDto before = dto();

        assertSame(before, AutoRouteWarnings.withLayoutOverNotes(before, f.view, layout(a, 40, 200, 120, 60),
                DispatchArm.APPLIED, null, null));
    }

    @Test
    public void shouldWarnOfNothing_forAnElementThatOnlyTouchesTheNote() {
        IDiagramModelArchimateObject a = element("a", 600, 40);
        f.note(f.view, "note-1", 40, 200);

        assertEquals(List.of(), noteWarnings(warn(layout(a, 40, 250, 120, 60), DispatchArm.APPLIED)));
    }

    @Test
    public void shouldNameANoteInAGroup_overASiblingElement_butNotOverItsOwnGroup() {
        IDiagramModelGroup group = f.group(f.view, "g1", 0, 400, 400, 200);
        f.note(group, "note-in", 10, 30);
        IDiagramModelArchimateObject sibling = f.object(group, "vo-s", f.element("el-s"), 200, 100, 120, 60);

        StructuredWarningDto w = only(warn(layout(sibling, 20, 40, 120, 60), DispatchArm.APPLIED));

        assertEquals(List.of("note-in"), w.remediationViolatorIds());
        assertTrue(w.message(), w.message().startsWith("This layout left 1 note overlap(s): note 'note-in'"
                + " overlaps element 'vo-s'."));
    }

    @Test
    public void shouldWarnOfNothing_forANoteInsideItsOwnGroup_whenTheLayoutMovesOnlyTheGroup() {
        IDiagramModelGroup group = f.group(f.view, "g1", 0, 400, 400, 200);
        f.note(group, "note-in", 10, 30);

        assertEquals(List.of(), noteWarnings(warn(layout(group, 300, 600, 400, 200), DispatchArm.APPLIED)));
    }

    /** The note moves with the group it is in: the layout carries the group, and so the note, onto an element. */
    @Test
    public void shouldNameANoteTheLayoutCarriesOntoAnElement_withTheGroupItIsIn() {
        element("a", 40, 40);
        IDiagramModelGroup group = f.group(f.view, "g1", 600, 400, 400, 200);
        f.note(group, "note-in", 10, 10);

        StructuredWarningDto w = only(warn(layout(group, 20, 20, 400, 200), DispatchArm.APPLIED));

        assertTrue(w.message(), w.message().contains("note 'note-in' overlaps element 'vo-a'"));
        assertTrue("its own group is not named: " + w.message(), !w.message().contains("'g1'"));
    }

    @Test
    public void shouldNameAGroupTheLayoutPutsOnANote_asAGroup() {
        IDiagramModelGroup group = f.group(f.view, "g1", 600, 400, 400, 200);
        f.note(f.view, "note-1", 40, 40);

        assertTrue(only(warn(layout(group, 0, 0, 400, 200), DispatchArm.APPLIED)).message()
                .contains("note 'note-1' overlaps group 'g1'"));
    }

    @Test
    public void shouldNameTheSameNotesInTheDeferredTense_onTheQueuedAndApprovalArms() {
        IDiagramModelArchimateObject a = element("a", 600, 40);
        f.note(f.view, "note-1", 40, 200);
        CompoundCommand layout = layout(a, 40, 200, 120, 60);

        StructuredWarningDto applied = only(warn(layout, DispatchArm.APPLIED));
        StructuredWarningDto queued = only(warn(layout, DispatchArm.QUEUED));
        StructuredWarningDto awaiting = only(warn(layout, DispatchArm.AWAITING_APPROVAL));

        assertEquals(applied.remediationViolatorIds(), queued.remediationViolatorIds());
        assertEquals(applied.remediationViolatorIds(), awaiting.remediationViolatorIds());
        for (StructuredWarningDto deferred : List.of(queued, awaiting)) {
            assertTrue(deferred.message(), deferred.message().startsWith("As read when this call was made, from"
                    + " the layout it computed, the layout will leave 1 note overlap(s): note 'note-1' overlaps"
                    + " element 'vo-a'. Nothing has been applied: "));
            assertEquals("update-view-object", deferred.remediationTool());
        }
        assertTrue(queued.message(), queued.message().contains("queued in the open batch; end-batch with"
                + " rollback:true discards it."));
        assertTrue(awaiting.message(), awaiting.message().contains("waiting on the human's decision, and"
                + " rejecting it in Archi leaves the view as it is."));
    }

    @Test
    public void shouldCountEveryPair_nameTheFirstTen_andNameTheNoteOnce() {
        IDiagramModelNote note = f.note(f.view, "note-big", 0, 0);
        note.setBounds(0, 0, 2000, 200);
        Object[] moves = new Object[12 * 5];
        for (int i = 0; i < 12; i++) {
            IDiagramModelArchimateObject e = element("e" + i, 3000, 40 + i * 100);
            moves[i * 5] = e;
            moves[i * 5 + 1] = 10 + i * 150;
            moves[i * 5 + 2] = 40;
            moves[i * 5 + 3] = 120;
            moves[i * 5 + 4] = 60;
        }

        StructuredWarningDto w = only(warn(layout(moves), DispatchArm.APPLIED));

        assertEquals(List.of("note-big"), w.remediationViolatorIds());
        assertTrue(w.message(), w.message().startsWith("This layout left 12 note overlap(s): "));
        assertTrue(w.message(), w.message().contains("note 'note-big' overlaps element 'vo-e9'; and 2 more."));
        assertTrue(w.message(), !w.message().contains("'vo-e10'"));
    }

    @Test
    public void shouldCarryTheSelfConnectionWarningFirst_andThenTheNoteWarning() {
        IDiagramModelArchimateObject a = element("a", 600, 40);
        f.note(f.view, "note-1", 40, 200);

        AutoLayoutAndRouteResultDto dto = AutoRouteWarnings.withLayoutWarnings(dto(), List.of("self-1"), f.view,
                layout(a, 40, 200, 120, 60), DispatchArm.APPLIED, null, null);

        assertEquals(List.of(StructuredWarningCodes.SELF_CONNECTION_NOT_ROUTED,
                StructuredWarningCodes.LAYOUT_OVERLAPS_NOTE),
                dto.structuredWarnings().stream().map(StructuredWarningDto::code).toList());
    }

    /**
     * On the queued arm a note the batch places inside a group the batch also places sits at the
     * group's offset: neither is attached yet, so the warning climbs the container the note's add
     * names. The note's own coordinates (10,10) are relative to the group at (1000,1000).
     */
    @Test
    public void shouldPlaceAQueuedNoteByTheQueuedGroupItIsIn_onTheQueuedArm() {
        IDiagramModelArchimateObject a = element("a", 40, 40);
        IDiagramModelArchimateObject b = element("b", 400, 40);
        String session = ViewContentsFixture.SESSION;
        f.dispatcher.beginBatch(session, "a group, a note in it, then a layout");
        String group = f.accessor.addGroupToView(session, f.view.getId(), "G", 1000, 1000, 400, 300, null, null,
                null).entity().viewObjectId();
        f.accessor.addNoteToView(session, f.view.getId(), "queued", null, null, 10, 10, 100, 40, group, null, null);
        AutoLayoutAndRouteResultDto atCanvasPosition = AutoRouteWarnings.withLayoutOverNotes(dto(), f.view,
                layout(a, 1000, 1000, 120, 60, b, 3000, 3000, 120, 60), DispatchArm.QUEUED, f.dispatcher, session);
        AutoLayoutAndRouteResultDto atOwnCoordinates = AutoRouteWarnings.withLayoutOverNotes(dto(), f.view,
                layout(a, 0, 0, 120, 60, b, 3000, 3000, 120, 60), DispatchArm.QUEUED, f.dispatcher, session);
        f.dispatcher.endBatch(session, true);

        List<StructuredWarningDto> warned = noteWarnings(atCanvasPosition);
        assertEquals(warned.toString(), 1, warned.size());
        assertTrue(warned.get(0).message(), warned.get(0).message().contains("overlaps element 'vo-a'"));
        assertEquals("an element at (0,0) is clear of the note, which the climb puts at (1010,1010); "
                + "without the climb the note would sit at (10,10), on it", List.of(), noteWarnings(atOwnCoordinates));
    }
}
