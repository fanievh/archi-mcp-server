package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Pins the informational note-on-note count: an unordered pair of notes whose boxes overlap counts
 * once, at any depth, its notes are published once each under the {@code noteOnNoteOverlap}
 * violator key, and no rating, band or breakdown key moves when two notes are stacked. The existing
 * {@code noteOverlapCount} (a note against objects that are not notes) keeps its meaning.
 *
 * <p>Nodes are built directly, unnamed where they are not notes, so nothing measures text.</p>
 */
public class NoteOnNoteOverlapTest {

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    private static AssessmentNode element(String id, double x, double y) {
        return new AssessmentNode(id, x, y, 120, 60, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    private static AssessmentNode note(String id, double x, double y, String parentId) {
        return new AssessmentNode(id, x, y, 200, 40, parentId, false, true, "note", 0.0, null, null, 0.0, 0.0,
                0.0);
    }

    private static AssessmentNode group(String id, double x, double y) {
        return new AssessmentNode(id, x, y, 600, 300, null, true, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    private static List<AssessmentNode> view(AssessmentNode... notes) {
        List<AssessmentNode> nodes = new ArrayList<>(List.of(element("e1", 40, 40), element("e2", 400, 40)));
        nodes.addAll(List.of(notes));
        return nodes;
    }

    private LayoutAssessmentResult assess(List<AssessmentNode> nodes) {
        return assessor.assess(nodes, List.of(), true);
    }

    @Test
    public void shouldCountTwoStackedNotesOnce_andNameBoth() {
        LayoutAssessmentResult result = assess(view(note("n1", 40, 200, null), note("n2", 40, 200, null)));

        assertEquals(1, result.noteOnNoteOverlapCount());
        assertEquals(List.of("Note 'n1' overlaps note 'n2'"), result.noteOnNoteOverlapDescriptions());
        assertEquals(new LinkedHashSet<>(List.of("n1", "n2")), result.violatorIds().get("noteOnNoteOverlap"));
    }

    @Test
    public void shouldCountNothing_forNotesThatOnlyTouch() {
        LayoutAssessmentResult result = assess(view(note("n1", 40, 200, null), note("n2", 40, 240, null)));

        assertEquals(0, result.noteOnNoteOverlapCount());
        assertEquals(List.of(), result.noteOnNoteOverlapDescriptions());
        assertFalse(result.violatorIds() != null && result.violatorIds().containsKey("noteOnNoteOverlap"));
    }

    @Test
    public void shouldCountEachPair_andNameEachNoteOnce() {
        LayoutAssessmentResult result = assess(view(note("n1", 40, 200, null), note("n2", 60, 210, null),
                note("n3", 80, 220, null)));

        assertEquals(3, result.noteOnNoteOverlapCount());
        assertEquals(List.of("Note 'n1' overlaps note 'n2'", "Note 'n1' overlaps note 'n3'",
                "Note 'n2' overlaps note 'n3'"), result.noteOnNoteOverlapDescriptions());
        assertEquals(Set.of("n1", "n2", "n3"), result.violatorIds().get("noteOnNoteOverlap"));
    }

    @Test
    public void shouldCountTwoNotesStackedInsideOneContainer() {
        List<AssessmentNode> nodes = view(note("n1", 120, 520, "g1"), note("n2", 120, 520, "g1"));
        nodes.add(group("g1", 40, 400));

        assertEquals(1, assess(nodes).noteOnNoteOverlapCount());
    }

    @Test
    public void shouldPublishNoViolatorKey_whenViolatorIdsAreNotAskedFor() {
        LayoutAssessmentResult result = assessor.assess(
                view(note("n1", 40, 200, null), note("n2", 40, 200, null)), List.of(), false);

        assertEquals(1, result.noteOnNoteOverlapCount());
        assertNull(result.violatorIds());
    }

    /** Stacked against apart: the only difference is the stacking, and nothing a rating reads moves. */
    @Test
    public void shouldMoveNoRatingAndLeaveTheNoteOverlapCountAlone_whenTwoNotesAreStacked() {
        LayoutAssessmentResult apart = assess(view(note("n1", 40, 200, null), note("n2", 40, 300, null)));
        LayoutAssessmentResult stacked = assess(view(note("n1", 40, 200, null), note("n2", 40, 200, null)));

        assertEquals("the fixture must fire the count", 1, stacked.noteOnNoteOverlapCount());
        assertEquals(0, apart.noteOnNoteOverlapCount());
        assertEquals(apart.noteOverlapCount(), stacked.noteOverlapCount());
        assertEquals(0, stacked.noteOverlapCount());
        assertEquals(apart.overallRating(), stacked.overallRating());
        assertEquals(apart.layoutRating(), stacked.layoutRating());
        assertEquals(apart.routingRating(), stacked.routingRating());
        assertEquals(apart.ratingBreakdown(), stacked.ratingBreakdown());
        assertEquals(22, stacked.ratingBreakdown().size());
        for (String key : stacked.ratingBreakdown().keySet()) {
            assertFalse("no breakdown key for an informational count: " + key,
                    key.toLowerCase(java.util.Locale.ROOT).contains("noteonnote"));
        }
    }

    /** The count carries its own remedy, so the terminal disclosure does not name it as unexplained. */
    @Test
    public void shouldExplainTwoStackedNotesInTheSuggestions_andNotDiscloseThemAsUnexplained() {
        LayoutAssessmentResult stacked = assess(view(note("n1", 40, 200, null), note("n2", 40, 200, null)));

        String prose = String.join("\n", stacked.suggestions());
        assertTrue(prose, prose.contains("1 note lies on another note"));
        assertTrue(prose, prose.contains("update-view-object"));
        assertFalse(prose, prose.contains("noteOnNoteOverlap ("));
        assertFalse(prose, prose.contains("noteOverlap ("));
    }

    @Test
    public void shouldPutNoNoteOnNoteSentence_whenTheNotesAreApart() {
        String prose = String.join("\n",
                assess(view(note("n1", 40, 200, null), note("n2", 40, 300, null))).suggestions());
        assertFalse(prose, prose.contains("lies on another note"));
    }
}
