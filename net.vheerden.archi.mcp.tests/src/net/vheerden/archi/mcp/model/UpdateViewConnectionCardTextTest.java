package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Headless tests for {@link UpdateViewConnectionCardText} — the conditional approval-card prose for
 * {@code update-view-connection}.
 *
 * <p>The defect being pinned: both of that card's prose fields were unconditional and both said
 * <em>bendpoints</em> ({@code "Update bendpoints for connection (…)"} and {@code "Connection
 * bendpoints ready for update."}) while the same method also writes styling, label visibility and
 * label position. A call hiding a label was announced, and validated, as a change to the polyline.</p>
 *
 * <p>Both ends of every aspect are pinned: present means named, absent means not named. Under-
 * disclosing hides a write; over-disclosing invents one, and both break the same promise.</p>
 */
public class UpdateViewConnectionCardTextTest {

    private static final String VIEW_CLAUSE = "in view 'Connecting'";
    private static final String TYPE = "AssociationRelationship";

    // ---- description ---------------------------------------------------------------------------

    @Test
    public void shouldKeepEstablishedWording_whenOnlyBendpointsChange() {
        // The established wording is preserved EXACTLY for the bendpoint-only call, so this fix
        // re-describes only the calls that were being described wrongly.
        assertEquals("Update bendpoints for connection (AssociationRelationship) in view 'Connecting'",
                UpdateViewConnectionCardText.description(TYPE, VIEW_CLAUSE,
                        changes("bendpointCount", 3)));
    }

    @Test
    public void shouldNameBendpoints_whenOnlyAbsoluteBendpointsChange() {
        // Absolute and relative bendpoints are one decision about one polyline, so they fold to
        // one aspect — an absolute-only call must not read differently from a relative-only one.
        assertEquals("Update bendpoints for connection (AssociationRelationship) in view 'Connecting'",
                UpdateViewConnectionCardText.description(TYPE, VIEW_CLAUSE,
                        changes("absoluteBendpointCount", 2)));
    }

    @Test
    public void shouldNotAnnounceBendpoints_whenOnlyStylingChanges() {
        // THE PIN THIS FIX EXISTS FOR: a restyle must not be announced as a reroute.
        String description = UpdateViewConnectionCardText.description(TYPE, VIEW_CLAUSE,
                changes("styling", Map.of("lineColor", "#FF0000")));

        assertEquals("Update styling for connection (AssociationRelationship) in view 'Connecting'",
                description);
        assertFalse("a styling-only call must not claim a bendpoint change",
                description.contains("bendpoint"));
    }

    @Test
    public void shouldNameLabelVisibility_whenOnlyShowLabelChanges() {
        String description = UpdateViewConnectionCardText.description(TYPE, VIEW_CLAUSE,
                changes("showLabel", Boolean.FALSE));

        assertEquals("Update label visibility for connection (AssociationRelationship) "
                + "in view 'Connecting'", description);
        assertFalse(description.contains("bendpoint"));
    }

    @Test
    public void shouldNameLabelPosition_whenOnlyTextPositionChanges() {
        String description = UpdateViewConnectionCardText.description(TYPE, VIEW_CLAUSE,
                changes("textPosition", 0));

        assertEquals("Update label position for connection (AssociationRelationship) "
                + "in view 'Connecting'", description);
        assertFalse(description.contains("bendpoint"));
    }

    @Test
    public void shouldNameEveryAspect_whenCallIsMixed() {
        assertEquals("Update bendpoints, label visibility, label position and styling "
                + "for connection (AssociationRelationship) in view 'Connecting'",
                UpdateViewConnectionCardText.description(TYPE, VIEW_CLAUSE,
                        changes("bendpointCount", 1, "showLabel", Boolean.TRUE,
                                "textPosition", 2, "styling", Map.of("lineWidth", 2))));
    }

    @Test
    public void shouldDegradeToNeutralWording_whenNothingIsDisclosed() {
        // Never claim a change the disclosure does not carry.
        assertEquals("Update connection (AssociationRelationship) in view 'Connecting'",
                UpdateViewConnectionCardText.description(TYPE, VIEW_CLAUSE, new LinkedHashMap<>()));
    }

    @Test
    public void shouldOmitViewClause_whenOwningViewIsUnresolvable() {
        assertEquals("Update styling for connection (AssociationRelationship)",
                UpdateViewConnectionCardText.description(TYPE, null,
                        changes("styling", Map.of("lineColor", "#FFF"))));
    }

    @Test
    public void shouldStillNameTheAspects_whenRelationshipTypeIsNullOrBlank() {
        // The relationship type is echoed exactly as the branch echoed it before, degradation and
        // all: this collaborator changes which aspects are named, not how an unresolvable type
        // renders. Pinned so a later aspect change cannot quietly alter the other half too.
        // Asserted byte-exact, whitespace included: normalising the string before comparing would
        // also swallow a stray double space introduced anywhere else in the sentence.
        assertEquals("Update styling for connection (null)",
                UpdateViewConnectionCardText.description(null, null,
                        changes("styling", Map.of("lineColor", "#FFF"))));
        assertEquals("Update styling for connection (  )",
                UpdateViewConnectionCardText.description("  ", null,
                        changes("styling", Map.of("lineColor", "#FFF"))));
    }

    // ---- validationSummary ---------------------------------------------------------------------

    @Test
    public void shouldKeepEstablishedSummaryWording_whenOnlyBendpointsChange() {
        assertEquals("Connection bendpoints ready for update.",
                UpdateViewConnectionCardText.validationSummary(changes("bendpointCount", 3)));
        assertEquals("Connection bendpoints ready for update.",
                UpdateViewConnectionCardText.validationSummary(changes("absoluteBendpointCount", 3)));
    }

    @Test
    public void shouldNotAnnounceBendpointsInSummary_whenOnlyStylingChanges() {
        String summary = UpdateViewConnectionCardText.validationSummary(
                changes("styling", Map.of("lineColor", "#FF0000")));

        assertEquals("Connection styling ready for update.", summary);
        assertFalse("a styling-only call must not claim a bendpoint change",
                summary.contains("bendpoint"));
    }

    @Test
    public void shouldNameEveryAspectInSummary_whenCallIsMixed() {
        assertEquals("Connection bendpoints, label visibility, label position and styling "
                + "ready for update.",
                UpdateViewConnectionCardText.validationSummary(
                        changes("bendpointCount", 1, "showLabel", Boolean.TRUE,
                                "textPosition", 2, "styling", Map.of("lineWidth", 2))));
    }

    @Test
    public void shouldDegradeSummaryToNeutralWording_whenNothingIsDisclosed() {
        assertEquals("Connection ready for update.",
                UpdateViewConnectionCardText.validationSummary(new LinkedHashMap<>()));
    }

    @Test
    public void shouldDescribeAndValidateTheSameAspects_forEveryDisclosure() {
        // The property the shared computation buys: a call can never be described one way and
        // validated another. Asserted over each aspect in turn, not on one lucky example.
        for (Map<String, Object> disclosure : List.of(
                changes("bendpointCount", 1), changes("absoluteBendpointCount", 1),
                changes("showLabel", Boolean.FALSE), changes("textPosition", 1),
                changes("styling", Map.of("lineWidth", 2)),
                changes("bendpointCount", 1, "styling", Map.of("lineWidth", 2)),
                new LinkedHashMap<String, Object>())) {
            String description = UpdateViewConnectionCardText.description(TYPE, null, disclosure);
            String summary = UpdateViewConnectionCardText.validationSummary(disclosure);
            String named = summary.substring("Connection".length(),
                    summary.length() - " ready for update.".length()).trim();
            String expected = named.isEmpty()
                    ? "Update connection (" + TYPE + ")"
                    : "Update " + named + " for connection (" + TYPE + ")";
            assertEquals("description and summary must name the same aspects", expected, description);
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
