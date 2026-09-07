package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.HubElementEntryDto;

/**
 * Pure-unit tests for {@link HubSizingSuggestionBuilder}.
 *
 * <p>Pins all three branches plus 12/13 boundary, plus odd/even excess
 * split-determinism, plus the H2 (2026-05-06 PartyTest Hub-Heavy Run 2)
 * 17-conn API Management Platform empirical case.</p>
 *
 * <p>Pure-Java, EMF-free — runnable without OSGi.</p>
 */
public class HubSizingSuggestionBuilderTest {

    private static HubElementEntryDto entry(String name, int conns, int width, int height,
                                            int maxLabelWidth) {
        return new HubElementEntryDto("vo-" + name, "e-" + name, name,
                "ApplicationComponent", conns, width, height, maxLabelWidth);
    }

    // ---- Branch 1 — connectionCount <= 6 (no suggestion) ----

    @Test
    public void buildSuggestions_zeroConns_shouldReturnEmpty() {
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("Comp", 0, 120, 55, 0)));
        assertTrue("Zero connections should yield no suggestion", result.isEmpty());
    }

    @Test
    public void buildSuggestions_atSixConns_shouldReturnEmpty() {
        // Boundary at the existing >6 hub-candidate threshold: 6 itself does NOT trip.
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("Comp", 6, 120, 55, 0)));
        assertTrue("Six connections should yield no suggestion", result.isEmpty());
    }

    // ---- Branch 2 — 7..LARGE_HUB_THRESHOLD (1D-or-1D only) ----

    @Test
    public void buildSuggestions_atSevenConns_shouldReturn1DOnly() {
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("Comp", 7, 120, 55, 0)));
        assertEquals("Seven connections should yield one suggestion", 1, result.size());
        assertTrue("Should match 1D-or-1D format",
                result.get(0).contains("for vertical layouts"));
        assertFalse("Should NOT contain 2D recommendation",
                result.get(0).contains("Consider 2D resize"));
    }

    @Test
    public void buildSuggestions_atTwelveConns_shouldReturn1DOnly() {
        // Boundary at the new LARGE_HUB_THRESHOLD: 12 itself does NOT trip the 2D branch.
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("Comp", 12, 120, 55, 0)));
        assertEquals("Twelve connections should yield one suggestion", 1, result.size());
        assertTrue("Should match 1D-or-1D format",
                result.get(0).contains("for vertical layouts"));
        assertFalse("Should NOT contain 2D recommendation at the threshold",
                result.get(0).contains("Consider 2D resize"));
    }

    // ---- Branch 3 — > LARGE_HUB_THRESHOLD (1D-or-1D + new 2D) ----

    @Test
    public void buildSuggestions_atThirteenConns_shouldReturn1DAnd2D() {
        // Boundary above LARGE_HUB_THRESHOLD: 13 trips the new 2D branch.
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("Comp", 13, 200, 180, 0)));
        assertEquals("Thirteen connections should yield two suggestions", 2, result.size());
        assertTrue("First entry should be the 1D-or-1D format",
                result.get(0).contains("for vertical layouts"));
        assertTrue("Second entry should be the 2D recommendation",
                result.get(1).contains("Consider 2D resize"));
        // excess = 7, ceil = 4, floor = 3, width2D = 200 + 15*4 = 260, height2D = 180 + 15*3 = 225
        assertTrue("2D entry should cite computed width 260", result.get(1).contains("260px"));
        assertTrue("2D entry should cite computed height 225", result.get(1).contains("225px"));
        // (13+3)/4 = 4
        assertTrue("2D entry should cite ~4 connections per edge",
                result.get(1).contains("4 connections per edge"));
    }

    @Test
    public void buildSuggestions_atSeventeenConns_shouldReturn1DAnd2D() {
        // H2 (2026-05-06 PartyTest Hub-Heavy Run 2) API Management Platform —
        // verbatim live dimensions captured 2026-05-07 via mcp__archi__detect-hub-elements
        // on view id-ddb84fbd57d24caaa15b0da62b75f531.
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("API Management Platform", 17, 300, 415, 0)));
        assertEquals("Seventeen connections should yield two suggestions", 2, result.size());
        assertTrue("First entry should be the existing 1D-or-1D format",
                result.get(0).contains("for vertical layouts"));
        assertTrue("Second entry should be the 2D recommendation",
                result.get(1).contains("Consider 2D resize"));
        // excess = 11, ceil = 6, floor = 5, width2D = 300 + 15*6 = 390, height2D = 415 + 15*5 = 490
        assertTrue("2D entry should cite computed width 390", result.get(1).contains("390px"));
        assertTrue("2D entry should cite computed height 490", result.get(1).contains("490px"));
        // (17+3)/4 = 5
        assertTrue("2D entry should cite ~5 connections per edge",
                result.get(1).contains("5 connections per edge"));
        assertTrue("2D entry should reference the empirical element name",
                result.get(1).contains("API Management Platform"));
        assertTrue("2D entry should cite the LARGE_HUB_THRESHOLD value",
                result.get(1).contains("> 12"));
    }

    // ---- Split determinism — odd vs even excess ----

    @Test
    public void buildSuggestions_oddExcess_shouldCeilWidth() {
        // connectionCount = 15 → excess = 9, ceil = 5, floor = 4 → width += 75, height += 60
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("Comp", 15, 200, 180, 0)));
        assertEquals(2, result.size());
        // 200 + 15*5 = 275, 180 + 15*4 = 240
        assertTrue("Odd-excess split should send ceil=5 to width (275px)",
                result.get(1).contains("275px"));
        assertTrue("Odd-excess split should send floor=4 to height (240px)",
                result.get(1).contains("240px"));
    }

    @Test
    public void buildSuggestions_evenExcess_shouldSplitEvenly() {
        // connectionCount = 16 → excess = 10, ceil = 5, floor = 5 → width += 75, height += 75
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("Comp", 16, 200, 180, 0)));
        assertEquals(2, result.size());
        // 200 + 15*5 = 275, 180 + 15*5 = 255
        assertTrue("Even-excess split should send 5 to width (275px)",
                result.get(1).contains("275px"));
        assertTrue("Even-excess split should send 5 to height (255px)",
                result.get(1).contains("255px"));
    }

    // ---- Owner-perception model surface ----

    @Test
    public void buildSuggestions_largeHub_shouldIncludeConnsPerEdgeText() {
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("Comp", 14, 200, 180, 0)));
        assertEquals(2, result.size());
        assertTrue("2D entry should surface 'connections per edge' owner-perception text",
                result.get(1).contains("connections per edge"));
        assertTrue("2D entry should mention four edges",
                result.get(1).contains("four edges"));
    }

    // ---- Multi-entry mixed branches ----

    @Test
    public void buildSuggestions_multipleEntries_shouldHandleMixedBranches() {
        // 5 conns (no suggestion) + 8 conns (one 1D-or-1D) + 14 conns (1D + 2D) = 3 entries total.
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(List.of(
                entry("Small", 5, 120, 55, 0),
                entry("Mid", 8, 120, 55, 0),
                entry("Large", 14, 200, 180, 0)));
        assertEquals("0 + 1 + 2 = 3 entries", 3, result.size());
        // First entry from 8-conn Mid (1D-or-1D)
        assertTrue("Index 0 should reference Mid (8 conns)",
                result.get(0).contains("'Mid'") && result.get(0).contains("8 connections"));
        assertFalse("Mid entry should NOT include 2D",
                result.get(0).contains("Consider 2D resize"));
        // Second entry from 14-conn Large (1D-or-1D)
        assertTrue("Index 1 should reference Large (14 conns) 1D-or-1D",
                result.get(1).contains("'Large'") && result.get(1).contains("for vertical layouts"));
        // Third entry from 14-conn Large (2D)
        assertTrue("Index 2 should be Large 2D",
                result.get(2).contains("'Large'") && result.get(2).contains("Consider 2D resize"));
    }

    @Test
    public void buildSuggestions_emptyList_shouldReturnEmpty() {
        assertTrue("Empty input should yield empty result",
                HubSizingSuggestionBuilder.buildSuggestions(List.of()).isEmpty());
    }

    // ---- Label-aware-width preservation (extracted from prior accessor logic) ----

    @Test
    public void buildSuggestions_labelAwareWidth_shouldStillApply() {
        // 8 conns + maxLabelWidth=180 + width=120 → labelAwareWidth = 300, connectionBasedWidth = 150
        // labelAwareWidth > connectionBasedWidth → suffix appended.
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("API Gateway", 8, 120, 55, 180)));
        assertEquals(1, result.size());
        assertTrue("Should preserve label-adjusted-width suffix when labelAware > connectionBased",
                result.get(0).contains("Label-adjusted width: 300px"));
        assertTrue("Should cite the longest label width",
                result.get(0).contains("longest label: 180px"));
    }

    // ---- Served sentence, asserted whole ----

    /**
     * Pins the emitted sentence character for character, including the
     * threshold the builder interpolates into it.
     *
     * <p>Every other assertion in this class — and every assertion reached
     * through the handler — tests a <em>fragment</em>:
     * {@code contains("for vertical layouts")},
     * {@code contains("260px")}. A fragment cannot see the part of the sentence
     * it does not name, so the boundary the sentence quotes could move to any
     * other number, or stop rendering altogether, with the whole suite green.
     * The handler-level cases that quote this sentence in full do not close
     * that gap either: they hand the finished strings to a stubbed accessor as
     * fixture input, so they assert that the handler passes a list through, not
     * that the builder ever produced it.</p>
     *
     * <p>The expected text is the eight-connection case, chosen because it is
     * the shape the handler fixtures use, so the two stay legible against each
     * other.</p>
     */
    @Test
    public void buildSuggestions_atEightConns_shouldEmitTheServedSentenceVerbatim() {
        List<String> result = HubSizingSuggestionBuilder.buildSuggestions(
                List.of(entry("API Gateway", 8, 120, 55, 0)));
        assertEquals("Eight connections should yield exactly one suggestion",
                1, result.size());
        assertEquals("The served sentence must render whole, with the large-hub "
                + "boundary interpolated as the number the shared constant holds",
                "Element 'API Gateway' has 8 connections (large hub: > 6). "
                + "Consider increasing height to 85px (55 + 15 × 2) for horizontal layouts, "
                + "or width to 150px (120 + 15 × 2) for vertical layouts.",
                result.get(0));
    }
}
