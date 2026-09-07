package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.DetectHubElementsResultDto;
import net.vheerden.archi.mcp.response.dto.HubElementEntryDto;

/**
 * Pure-unit tests for {@link HubSpacingSignal}.
 *
 * <p>These are driven from a {@link DetectHubElementsResultDto} rather than
 * from a literal boolean, so they discriminate the <em>derivation</em> and not
 * merely the spacing tables downstream of it. The load-bearing case is a
 * non-empty result whose every entry sits at or below the threshold: that is
 * the shape a plain emptiness check reads as "has a large hub" and this
 * predicate must read as "does not".</p>
 *
 * <p>Pure-Java, EMF-free — runnable without OSGi or a display.</p>
 */
public class HubSpacingSignalTest {

    private static HubElementEntryDto entry(String name, int conns) {
        return new HubElementEntryDto("vo-" + name, "e-" + name, name,
                "ApplicationComponent", conns, 120, 55, 0);
    }

    private static DetectHubElementsResultDto result(HubElementEntryDto... entries) {
        List<HubElementEntryDto> list = List.of(entries);
        int totalConnections = list.stream()
                .mapToInt(HubElementEntryDto::connectionCount).sum();
        return new DetectHubElementsResultDto("view-1", list.size(),
                totalConnections,
                list.isEmpty() ? 0.0 : (double) totalConnections / list.size(),
                list, List.of());
    }

    // ---- The load-bearing case: non-empty, but nothing above the threshold ----

    @Test
    public void shouldReportNoLargeHubs_whenResultIsNonEmptyButEveryEntryIsAtOrBelowSix() {
        // detect-hub-elements admits every element carrying at least one
        // connection, so a populated result says only that the view is
        // connected. On this shape a plain emptiness check answers true.
        DetectHubElementsResultDto hubResult =
                result(entry("A", 1), entry("B", 4), entry("C", 6));

        assertFalse("A connected view whose busiest element has 6 connections "
                + "carries no large hub", HubSpacingSignal.hasLargeHubs(hubResult));
    }

    // ---- Boundaries ----

    @Test
    public void shouldReportNoLargeHubs_whenBusiestElementHasExactlySixConnections() {
        assertFalse("Six connections is at the threshold, not above it",
                HubSpacingSignal.hasLargeHubs(result(entry("A", 6))));
    }

    @Test
    public void shouldReportLargeHubs_whenBusiestElementHasSevenConnections() {
        assertTrue("Seven connections is the first count above the threshold",
                HubSpacingSignal.hasLargeHubs(result(entry("A", 7))));
    }

    // ---- Empty and mixed ----

    @Test
    public void shouldReportNoLargeHubs_whenTheViewHasNoConnectedElements() {
        assertFalse("An empty result carries no large hub",
                HubSpacingSignal.hasLargeHubs(result()));
    }

    @Test
    public void shouldReportLargeHubs_whenOneEntryExceedsSixAmongSmallerSiblings() {
        DetectHubElementsResultDto hubResult =
                result(entry("A", 2), entry("B", 7), entry("C", 3));

        assertTrue("A single element above the threshold is enough",
                HubSpacingSignal.hasLargeHubs(hubResult));
    }

    @Test
    public void shouldReportLargeHubs_whenEveryEntryExceedsSix() {
        DetectHubElementsResultDto hubResult =
                result(entry("A", 9), entry("B", 14));

        assertTrue("Several large hubs still read as one signal",
                HubSpacingSignal.hasLargeHubs(hubResult));
    }

    // ---- The distinct thresholds this signal must not be confused with ----

    @Test
    public void shouldReportNoLargeHubs_whenBusiestElementSitsAtTheHubCandidateCut() {
        // 5 is the layout assessor's hub-candidate cut, a different and lower
        // threshold. An element there is a hub candidate but not a large hub.
        assertFalse("A hub candidate at 5 connections is not a large hub",
                HubSpacingSignal.hasLargeHubs(result(entry("A", 5))));
    }

    @Test
    public void shouldReportLargeHubs_whenBusiestElementIsBelowTheHighFanOutCut() {
        // 12 is the high-fan-out cut that earns a two-dimensional resize
        // suggestion. The spacing signal fires well below it.
        assertTrue("An element at 8 connections is a large hub even though it "
                + "is below the high-fan-out cut",
                HubSpacingSignal.hasLargeHubs(result(entry("A", 8))));
    }
}
