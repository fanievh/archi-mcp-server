package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Headless tests for {@link UpdateViewObjectCardText} — the conditional approval-card sentence for
 * {@code update-view-object}.
 *
 * <p>The defect being pinned: the sentence was the unconditional {@code "Update view object bounds
 * for …"} while the same method also writes text, a label expression, styling, image parameters and
 * four anchor fields. Since the card's visible row reads this sentence and NOT the
 * {@code proposedChanges} map, a caller renaming a box was announced to the approving human as a
 * move.</p>
 */
public class UpdateViewObjectCardTextTest {

    private static final String VIEW_CLAUSE = "in view 'Layer View'";

    @Test
    public void shouldKeepEstablishedWording_whenOnlyBoundsChange() {
        // The established wording is preserved EXACTLY for the bounds-only call, so this fix
        // re-describes only the calls that were being described wrongly.
        assertEquals("Update view object bounds for BusinessActor 'Customer' in view 'Layer View'",
                UpdateViewObjectCardText.description("BusinessActor", "Customer", VIEW_CLAUSE,
                        changes("x", 10, "y", 20, "width", 120, "height", 55)));
    }

    @Test
    public void shouldKeepEstablishedWording_whenOnlyOneBoundChanges() {
        assertEquals("Update view object bounds for BusinessActor 'Customer' in view 'Layer View'",
                UpdateViewObjectCardText.description("BusinessActor", "Customer", VIEW_CLAUSE,
                        changes("width", 200)));
    }

    @Test
    public void shouldNotAnnounceBoundsChange_whenOnlyTextChanges() {
        // THE PIN THIS FIX EXISTS FOR: a rename must not be announced as a move.
        String description = UpdateViewObjectCardText.description(
                "Note", "Caption", VIEW_CLAUSE, changes("text", "Revised caption"));

        assertEquals("Update text for Note 'Caption' in view 'Layer View'", description);
        assertFalse("a text-only call must not claim a bounds change",
                description.contains("bounds"));
    }

    @Test
    public void shouldNameLabelExpression_whenOnlyLabelExpressionChanges() {
        String description = UpdateViewObjectCardText.description(
                "BusinessActor", "Customer", VIEW_CLAUSE, changes("labelExpression", "${name}"));

        assertEquals("Update label expression for BusinessActor 'Customer' in view 'Layer View'",
                description);
        assertFalse(description.contains("bounds"));
    }

    @Test
    public void shouldNameAnchoring_whenOnlyAnchorFieldsChange() {
        // All four anchor keys are one placement decision, so they report as a single aspect.
        String description = UpdateViewObjectCardText.description("BusinessActor", "Customer",
                VIEW_CLAUSE, changes("anchorTarget", "obj-2", "anchorEdge", "right",
                        "anchorDx", 10, "anchorDy", -4));

        assertEquals("Update anchoring for BusinessActor 'Customer' in view 'Layer View'",
                description);
        assertFalse(description.contains("bounds"));
    }

    @Test
    public void shouldNameStyling_whenOnlyStylingChanges() {
        String description = UpdateViewObjectCardText.description("BusinessActor", "Customer",
                VIEW_CLAUSE, changes("styling", Map.of("fillColor", "#FF0000")));

        assertEquals("Update styling for BusinessActor 'Customer' in view 'Layer View'", description);
        assertFalse(description.contains("bounds"));
    }

    @Test
    public void shouldNameImage_whenOnlyImageParamsChange() {
        assertEquals("Update image for BusinessActor 'Customer' in view 'Layer View'",
                UpdateViewObjectCardText.description("BusinessActor", "Customer", VIEW_CLAUSE,
                        changes("imageParams", Map.of("imagePath", "img/a.png"))));
    }

    @Test
    public void shouldNameEveryAspect_whenCallIsMixed() {
        String description = UpdateViewObjectCardText.description("BusinessActor", "Customer",
                VIEW_CLAUSE, changes("x", 10, "text", "Hello",
                        "styling", Map.of("recede", Boolean.FALSE), "anchorEdge", "left"));

        assertEquals("Update view object bounds, text, styling and anchoring "
                + "for BusinessActor 'Customer' in view 'Layer View'", description);
    }

    @Test
    public void shouldDegradeToNeutralWording_whenNothingIsDisclosed() {
        // Never claim a change the disclosure does not carry.
        String description = UpdateViewObjectCardText.description(
                "BusinessActor", "Customer", VIEW_CLAUSE, new LinkedHashMap<>());

        assertEquals("Update view object for BusinessActor 'Customer' in view 'Layer View'",
                description);
    }

    @Test
    public void shouldDegradeToUntitled_whenNameIsBlankOrNull() {
        assertTrue(UpdateViewObjectCardText.description("Group", "  ", VIEW_CLAUSE,
                changes("x", 1)).contains("Group (untitled)"));
        assertTrue(UpdateViewObjectCardText.description("Group", null, VIEW_CLAUSE,
                changes("x", 1)).contains("Group (untitled)"));
    }

    @Test
    public void shouldOmitViewClause_whenOwningViewIsUnresolvable() {
        assertEquals("Update text for Note 'Caption'",
                UpdateViewObjectCardText.description("Note", "Caption", null,
                        changes("text", "t")));
    }

    // ---- validationSummary: the card's second prose field, from the same disclosure ----------

    @Test
    public void shouldKeepEstablishedSummaryWording_whenOnlyBoundsChange() {
        // Byte-identical to the sentence this field carried before it was made conditional, so a
        // bounds-only call reads exactly as it always did.
        assertEquals("View object bounds ready for update.",
                UpdateViewObjectCardText.validationSummary(
                        changes("x", 10, "y", 20, "width", 120, "height", 55)));
    }

    @Test
    public void shouldNotAnnounceBoundsInSummary_whenOnlyTextChanges() {
        // The negative end: a rename must not be validated as a move one field over from a
        // description that already says "text".
        String summary = UpdateViewObjectCardText.validationSummary(changes("text", "Revised"));

        assertEquals("Text ready for update.", summary);
        assertFalse("a text-only call must not claim a bounds change", summary.contains("bounds"));
    }

    @Test
    public void shouldNameEveryAspectInSummary_whenCallIsMixed() {
        assertEquals("View object bounds, text, styling and anchoring ready for update.",
                UpdateViewObjectCardText.validationSummary(changes("x", 10, "text", "Hello",
                        "styling", Map.of("recede", Boolean.FALSE), "anchorEdge", "left")));
    }

    @Test
    public void shouldDegradeSummaryToNeutralWording_whenNothingIsDisclosed() {
        assertEquals("View object ready for update.",
                UpdateViewObjectCardText.validationSummary(new LinkedHashMap<>()));
    }

    @Test
    public void shouldDescribeAndValidateTheSameAspects_forEveryDisclosure() {
        // The property the shared computation buys: neither field can name an aspect the other
        // does not. Asserted over each aspect in turn rather than on one lucky example.
        for (Map<String, Object> disclosure : List.of(
                changes("width", 200), changes("text", "t"), changes("labelExpression", "${name}"),
                changes("styling", Map.of("fillColor", "#FFF")),
                changes("imageParams", Map.of("imagePath", "a.png")),
                changes("anchorTarget", "obj-2"), changes("x", 1, "text", "t"),
                new LinkedHashMap<String, Object>())) {
            String description = UpdateViewObjectCardText.description(
                    "BusinessActor", "Customer", VIEW_CLAUSE, disclosure);
            String summary = UpdateViewObjectCardText.validationSummary(disclosure);
            String named = summary.substring(0, summary.length() - " ready for update.".length());
            assertTrue("description and summary must name the same aspects, got: "
                    + description + " / " + summary,
                    description.startsWith("Update " + Character.toLowerCase(named.charAt(0))
                            + named.substring(1) + " for "));
        }
    }

    private static Map<String, Object> changes(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
