package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;

/**
 * Pins that a note placed by {@code position} anchors to the view's content united with every note
 * the view will hold, so successive notes stack clear of each other: on single calls, in one
 * {@code bulk-mutate} call, and inside a batch, where the earlier notes are only queued. A staged
 * removal takes its note out of the anchor, a staged move puts it where the move does, and a view
 * holding only notes anchors below them.
 *
 * <p>The fixture's content is one element at (40, 40, 120x60), so its bottom is y 100. Every note
 * carries an explicit size, so nothing measures text and the class needs no display.</p>
 */
public class NotePlacementClearsNotesTest {

    private static final String SESSION = ViewContentsFixture.SESSION;

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-note-placement-clears-notes");
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private void content() {
        IArchimateElement a = f.element("el-a");
        f.object(f.view, "vo-a", a, 40, 40, 120, 60);
    }

    private static BulkOperation op(String tool, Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new BulkOperation(tool, params, null);
    }

    private BulkOperation note(String content, String position) {
        return op("add-note-to-view", "viewId", f.view.getId(), "content", content, "position", position,
                "width", 440, "height", 40);
    }

    private ViewNoteDto single(String content, String position) {
        return f.accessor.addNoteToView(SESSION, f.view.getId(), content, position, null, null, null, 440, 40,
                null, null, null).entity();
    }

    private BulkMutationResult bulk(BulkOperation... operations) {
        return f.accessor.executeBulk(SESSION, List.of(operations), "notes", false, null);
    }

    /** Each note on {@code container} as {@code content@x,y}, in the order the container holds them. */
    private static List<String> notes(IDiagramModelContainer container) {
        List<String> out = new ArrayList<>();
        for (IDiagramModelObject o : container.getChildren()) {
            if (o instanceof IDiagramModelNote n) {
                out.add(n.getContent() + "@" + n.getBounds().getX() + "," + n.getBounds().getY());
            }
        }
        return out;
    }

    private IDiagramModelNote liveNote(String id, int x, int y) {
        IDiagramModelNote note = f.note(f.view, id, x, y);
        note.setBounds(x, y, 440, 40);
        return note;
    }

    @Test
    public void shouldPlaceTheSecondNoteBelowTheFirst_onTwoSingleCalls() {
        content();
        ViewNoteDto a = single("A", "below-content");
        ViewNoteDto b = single("B", "below-content");

        assertEquals("content bottom 100 + gap 10", 110, a.y());
        assertEquals("the first note's bottom 150 + gap 10", 160, b.y());
        assertEquals(40, b.x());
        assertEquals(List.of("A@40,110", "B@40,160"), notes(f.view));
    }

    @Test
    public void shouldPlaceTheSecondNoteAboveTheFirst_forAboveContent() {
        content();
        assertEquals("content top 40 - gap 10 - height 40", -10, single("A", "above-content").y());
        assertEquals("the first note's top -10 - gap 10 - height 40", -60, single("B", "above-content").y());
    }

    @Test
    public void shouldPlaceTheSecondNoteBelowTheFirst_inOneBulkCall() {
        content();
        BulkMutationResult result = bulk(note("A", "below-content"), note("B", "below-content"));

        assertEquals(160, result.operations().get(1).effectiveBounds().y());
        assertEquals(List.of("A@40,110", "B@40,160"), notes(f.view));
    }

    @Test
    public void shouldPlaceTheSecondNoteBelowTheFirst_whenTheBatchOnlyQueuedTheFirst() {
        content();
        f.dispatcher.beginBatch(SESSION, "two notes");
        single("A", "below-content");
        ViewNoteDto b = single("B", "below-content");
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("the queued note's prepared box counts", 160, b.y());
        assertEquals(List.of("A@40,110", "B@40,160"), notes(f.view));
    }

    /** Order arm whose natural order is wrong: A is on the view, but a removal staged ahead takes it off. */
    @Test
    public void shouldPlaceANoteAsIfTheNoteAStagedRemovalTakesOffWereGone_inABatch() {
        content();
        liveNote("note-a", 40, 110);
        f.dispatcher.beginBatch(SESSION, "remove then add");
        bulk(op("remove-from-view", "viewId", f.view.getId(), "viewObjectId", "note-a"));
        ViewNoteDto b = single("B", "below-content");
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("B lands where it would on a view without A", 110, b.y());
        assertEquals(List.of("B@40,110"), notes(f.view));
    }

    @Test
    public void shouldPlaceANoteAsIfTheNoteAnEarlierOperationRemovesWereGone_inOneBulkCall() {
        content();
        liveNote("note-a", 40, 110);
        bulk(op("remove-from-view", "viewId", f.view.getId(), "viewObjectId", "note-a"), note("B", "below-content"));

        assertEquals(List.of("B@40,110"), notes(f.view));
    }

    @Test
    public void shouldPlaceANoteBelowWhereAStagedMovePutsAnotherNote() {
        content();
        IDiagramModelNote a = liveNote("note-a", 40, 110);
        f.dispatcher.beginBatch(SESSION, "move then add");
        bulk(op("update-view-object", "viewObjectId", "note-a", "x", 40, "y", 300));
        ViewNoteDto b = single("B", "below-content");
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(300, a.getBounds().getY());
        assertEquals("the moved note's bottom 340 + gap 10", 350, b.y());
    }

    /** Two staged partial updates of one note write different axes, so both count: y from the first, x from the second. */
    @Test
    public void shouldPlaceANoteBelowANoteTwoPartialUpdatesMove_axisByAxis() {
        content();
        IDiagramModelNote a = liveNote("note-a", 40, 110);
        f.dispatcher.beginBatch(SESSION, "move down, then across");
        bulk(op("update-view-object", "viewObjectId", "note-a", "y", 300));
        bulk(op("update-view-object", "viewObjectId", "note-a", "x", 60));
        ViewNoteDto b = single("B", "below-content");
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("60,300", a.getBounds().getX() + "," + a.getBounds().getY());
        assertEquals("the twice-moved note's bottom 340 + gap 10", 350, b.y());
    }

    @Test
    public void shouldPlaceANoteBelowTheNotes_onAViewHoldingOnlyNotes() {
        IDiagramModelNote existing = f.note(f.view, "existing", 100, 100);
        ViewNoteDto b = single("B", "below-content");

        assertEquals(100, b.x());
        assertEquals("the note's bottom 150 + gap 10",
                existing.getBounds().getY() + existing.getBounds().getHeight() + 10, b.y());
        assertNull("the view has content now, so no default-position note", b.note());
    }

    @Test
    public void shouldStillPlaceAtTheDefaultPosition_onAViewWithNothingOnIt() {
        ViewNoteDto b = single("B", "below-content");

        assertEquals(10, b.x());
        assertEquals(10, b.y());
        assertEquals("View has no content — note placed at default position (10, 10)", b.note());
    }

    @Test
    public void shouldCountANoteNestedInAContainer_atItsAbsolutePosition() {
        content();
        IDiagramModelGroup group = f.group(f.view, "g1", 40, 200, 300, 200);
        IDiagramModelNote nested = f.note(group, "nested", 20, 180);

        ViewNoteDto b = single("B", "below-content");

        assertEquals("the group's bottom is 400, the nested note's 200 + 180 + 50 = 430", 430,
                200 + nested.getBounds().getY() + nested.getBounds().getHeight());
        assertEquals(440, b.y());
    }

    @Test
    public void shouldCountANoteTheBatchQueuedIntoAContainer() {
        content();
        IDiagramModelGroup group = f.group(f.view, "g1", 40, 200, 300, 200);
        f.dispatcher.beginBatch(SESSION, "nested then add");
        f.accessor.addNoteToView(SESSION, f.view.getId(), "nested", null, null, 20, 180, 100, 50, "g1", null,
                null);
        List<Command> queued = f.dispatcher.queuedLeafCommands(SESSION);
        ViewNoteDto b = single("B", "below-content");
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("the nested note is only queued", true,
                queued.stream().anyMatch(c -> c instanceof AddNoteToViewCommand));
        assertEquals(440, b.y());
        assertEquals(1, notes(group).size());
    }

    /** A note the staged work places AFTER the asking one is not ahead of it, so it does not count. */
    @Test
    public void shouldNotCountANoteAddedLater_inTheSameCall() {
        content();
        bulk(note("A", "below-content"), op("add-note-to-view", "viewId", f.view.getId(), "content", "late",
                "x", 40, "y", 500, "width", 100, "height", 40));

        assertEquals(List.of("A@40,110", "late@40,500"), notes(f.view));
    }
}
