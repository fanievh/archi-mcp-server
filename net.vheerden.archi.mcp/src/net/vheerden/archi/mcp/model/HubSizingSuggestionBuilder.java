package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.vheerden.archi.mcp.response.dto.HubElementEntryDto;

/**
 * EMF-free pure-unit helper that builds hub-element sizing suggestions for
 * {@code detect-hub-elements}. Three branches:
 * <ul>
 *   <li>{@code connectionCount} at or below
 *       {@link HubSpacingSignal#LARGE_HUB_CONNECTION_COUNT} — no suggestion.</li>
 *   <li>Above that boundary and at or below {@link #LARGE_HUB_THRESHOLD} — emit
 *       existing 1D-or-1D suggestion (height-or-width inflation,
 *       label-aware).</li>
 *   <li>{@code connectionCount > LARGE_HUB_THRESHOLD} — emit existing
 *       1D-or-1D suggestion AND a NEW 2D suggestion that splits the excess
 *       across both axes so ports distribute across all four edges
 *       (~N/4 connections per edge).</li>
 * </ul>
 */
public final class HubSizingSuggestionBuilder {

    /**
     * Connection-count threshold above which a 2D-resize suggestion is emitted
     * alongside the existing 1D-or-1D pair. Hubs with strictly more than
     * {@code LARGE_HUB_THRESHOLD} connections receive both options.
     */
    public static final int LARGE_HUB_THRESHOLD = 12;

    private HubSizingSuggestionBuilder() {
    }

    public static List<String> buildSuggestions(List<HubElementEntryDto> entries) {
        Objects.requireNonNull(entries, "entries");
        List<String> suggestions = new ArrayList<>();
        for (HubElementEntryDto entry : entries) {
            if (HubSpacingSignal.isLargeHub(entry)) {
                int excess = entry.connectionCount()
                        - HubSpacingSignal.LARGE_HUB_CONNECTION_COUNT;
                int connectionBasedWidth = entry.width() + 15 * excess;
                int suggestedHeight = entry.height() + 15 * excess;
                int labelAwareWidth = entry.maxLabelWidth() > 0
                        ? entry.maxLabelWidth() + entry.width() : 0;
                int suggestedWidth = Math.max(connectionBasedWidth, labelAwareWidth);

                String suggestion = String.format(
                        "Element '%s' has %d connections (large hub: > %d). "
                        + "Consider increasing height to %dpx (%d + 15 × %d) for horizontal layouts, "
                        + "or width to %dpx (%d + 15 × %d) for vertical layouts.",
                        entry.elementName(), entry.connectionCount(),
                        HubSpacingSignal.LARGE_HUB_CONNECTION_COUNT,
                        suggestedHeight, entry.height(), excess,
                        suggestedWidth, entry.width(), excess);
                if (labelAwareWidth > connectionBasedWidth) {
                    suggestion += String.format(
                            " Label-adjusted width: %dpx (longest label: %dpx).",
                            labelAwareWidth, entry.maxLabelWidth());
                }
                suggestions.add(suggestion);

                if (entry.connectionCount() > LARGE_HUB_THRESHOLD) {
                    int ceilExcessHalf = (excess + 1) / 2;
                    int floorExcessHalf = excess / 2;
                    int suggestedWidth2D = entry.width() + 15 * ceilExcessHalf;
                    int suggestedHeight2D = entry.height() + 15 * floorExcessHalf;
                    int estimatedConnsPerEdge = (entry.connectionCount() + 3) / 4;

                    String suggestion2D = String.format(
                            "Element '%s' has %d connections (large hub: > %d). "
                            + "Consider 2D resize: increase width to %dpx (%d + 15 × %d) AND "
                            + "height to %dpx (%d + 15 × %d) to distribute connections across "
                            + "all four edges (~%d connections per edge).",
                            entry.elementName(), entry.connectionCount(), LARGE_HUB_THRESHOLD,
                            suggestedWidth2D, entry.width(), ceilExcessHalf,
                            suggestedHeight2D, entry.height(), floorExcessHalf,
                            estimatedConnsPerEdge);
                    suggestions.add(suggestion2D);
                }
            }
        }
        return suggestions;
    }
}
