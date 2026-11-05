package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.EchoToolHarness.args;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pins the note-on-note count on the wire: {@code assess-layout}'s served JSON carries
 * {@code noteOnNoteOverlapCount}, its descriptions, and, when violator ids are asked for, both notes
 * under {@code noteOnNoteOverlap}. A field that reaches the DTO but not the response is invisible to
 * every model-level pin, so this reads the text the handler serves.
 *
 * <p>The collector measures each note's text to size its clip check, which needs a display, so this
 * class runs in the display lane.</p>
 */
public class NoteOnNoteOverlapServedTest {

    private EchoToolHarness h;

    @Before
    public void setUp() throws Exception {
        h = new EchoToolHarness("note-on-note-served-model");
    }

    @After
    public void tearDown() {
        h.dispose();
    }

    @Test
    public void shouldServeTheCountTheDescriptionsAndTheViolatorKey_forTwoStackedNotes() {
        h.f.note(h.f.view, "n1", 40, 300);
        h.f.note(h.f.view, "n2", 40, 300);

        JsonNode result = h.success("assess-layout", "viewId", "view-source", "includeViolatorIds", true)
                .path("result");

        assertEquals(1, result.path("noteOnNoteOverlapCount").asInt());
        assertEquals("[\"Note 'n1' overlaps note 'n2'\"]", result.path("noteOnNoteOverlapDescriptions").toString());
        List<String> ids = new ArrayList<>();
        result.path("violatorIds").path("noteOnNoteOverlap").forEach(id -> ids.add(id.asText()));
        assertEquals(List.of("n1", "n2"), ids);
        assertEquals("the note-against-object count is untouched", 0, result.path("noteOverlapCount").asInt());
    }

    @Test
    public void shouldServeAMeasuredZeroAndNoDescriptions_forNotesApart() {
        h.f.note(h.f.view, "n1", 40, 300);
        h.f.note(h.f.view, "n2", 40, 400);

        JsonNode result = h.success("assess-layout", "viewId", "view-source", "includeViolatorIds", true)
                .path("result");

        assertEquals(0, result.path("noteOnNoteOverlapCount").asInt());
        assertFalse(result.path("noteOnNoteOverlapCount").isMissingNode());
        assertFalse(result.has("noteOnNoteOverlapDescriptions"));
        assertFalse(result.path("violatorIds").has("noteOnNoteOverlap"));
    }

    /** Two below-content notes through the served tool land clear of each other, and the count reads 0. */
    @Test
    public void shouldPlaceTwoServedNotesClear_andAssessThemApart() {
        h.success("add-note-to-view", "viewId", "view-source", "content", "A", "width", 440, "height", 40,
                "position", "below-content");
        JsonNode second = h.success("add-note-to-view", "viewId", "view-source", "content", "B", "width", 440,
                "height", 40, "position", "below-content");

        assertEquals(160, second.path("result").path("y").asInt());
        assertEquals(0, h.success("assess-layout", "viewId", "view-source").path("result")
                .path("noteOnNoteOverlapCount").asInt());
    }
}
