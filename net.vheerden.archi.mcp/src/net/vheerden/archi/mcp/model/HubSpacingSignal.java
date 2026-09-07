package net.vheerden.archi.mcp.model;

import java.util.Objects;

import net.vheerden.archi.mcp.response.dto.DetectHubElementsResultDto;
import net.vheerden.archi.mcp.response.dto.HubElementEntryDto;

/**
 * Single source of truth for the large-hub connection-count boundary and for
 * the two predicates that read it at two granularities:
 * <ul>
 *   <li>{@link #isLargeHub(HubElementEntryDto)} — does <em>this element</em>
 *       carry more connections than the boundary? Read by
 *       {@link HubSizingSuggestionBuilder} to decide whether an element earns a
 *       resize suggestion.</li>
 *   <li>{@link #hasLargeHubs(DetectHubElementsResultDto)} — does <em>the
 *       view</em> carry any such element? The spacing signal that
 *       {@link ElementSpacingHeuristic} and {@link GroupSpacingHeuristic} use
 *       to select their hub-aware column.</li>
 * </ul>
 *
 * <p>The view-level signal is composed from the element-level predicate rather
 * than repeating its comparison, so the two are provably the same boundary at
 * two scales rather than two copies that happen to agree.</p>
 *
 * <p>This is an EMF-free utility class, in the same mould as the two
 * heuristics it feeds: {@link DetectHubElementsResultDto} and its entries
 * carry only plain data, so this class and its JUnit pin run as pure-unit
 * tests with no OSGi context and no transitive class-loading of
 * {@link ArchiModelAccessorImpl}.</p>
 *
 * <p><strong>Why this exists as a named helper rather than an inline
 * expression.</strong> The predicate was previously written out at each
 * spacing callsite. Four of those copies agreed; a fifth had drifted to
 * {@code !result.elements().isEmpty()}, which is not a hub test at all —
 * {@code detect-hub-elements} filters its result to elements carrying
 * <em>at least one</em> connection, so a non-empty {@code elements()} means
 * only that the view has a connection somewhere. Routing every callsite
 * through this method makes the copies provably identical rather than
 * coincidentally equal.</p>
 *
 * <p><strong>Three different thresholds are live in this area — do not
 * conflate them.</strong></p>
 *
 * <table border="1">
 *   <caption>Hub-related thresholds and their distinct jobs</caption>
 *   <tr><th>Threshold</th><th>Meaning</th><th>Defined at</th></tr>
 *   <tr>
 *     <td>&ge; 5</td>
 *     <td>hub <em>candidate</em> — the layout assessor's reporting cut</td>
 *     <td>{@link LayoutQualityAssessor#HUB_DETECTION_THRESHOLD}</td>
 *   </tr>
 *   <tr>
 *     <td>&gt; 6</td>
 *     <td><em>large hub</em> — the spacing signal this class computes, and the
 *         per-element gate on the sizing suggestion</td>
 *     <td>{@link #LARGE_HUB_CONNECTION_COUNT}</td>
 *   </tr>
 *   <tr>
 *     <td>&gt; 12</td>
 *     <td>high fan-out — earns the two-dimensional resize suggestion</td>
 *     <td>{@link HubSizingSuggestionBuilder#LARGE_HUB_THRESHOLD}</td>
 *   </tr>
 * </table>
 *
 * <p>Note the trap in that table: the constant whose <em>name</em> contains
 * {@code LARGE_HUB} holds 12 and is not the threshold this signal uses.
 * Wiring this method to either constant would silently move every spacing
 * callsite to a different definition.</p>
 */
final class HubSpacingSignal {

    /**
     * Connection count above which an element counts as a large hub. Matches
     * the {@code &gt; 6} boundary the element and group heuristic tables are
     * documented against, and the boundary {@code detect-hub-elements}
     * publishes when it emits a sizing suggestion.
     *
     * <p>Package-private rather than private because
     * {@link HubSizingSuggestionBuilder} needs the <em>value</em>, not only the
     * predicate: it subtracts the boundary to size the suggested growth and
     * interpolates it into the served sentence. A predicate call supplies
     * neither, so keeping this private would leave the number spelled out
     * twice more in that file.</p>
     */
    static final int LARGE_HUB_CONNECTION_COUNT = 6;

    private HubSpacingSignal() {
        // Utility class — not instantiable
    }

    /**
     * Returns whether <em>this element</em> carries more connections than the
     * large-hub boundary.
     *
     * <p>The per-element counterpart of
     * {@link #hasLargeHubs(DetectHubElementsResultDto)}, which asks whether
     * <em>the view</em> carries any such element. Both read
     * {@link #LARGE_HUB_CONNECTION_COUNT}, so an element-level gate and the
     * view-level signal cannot drift apart.</p>
     *
     * @param entry one entry from a {@code detect-hub-elements} result
     * @return true when the entry exceeds {@link #LARGE_HUB_CONNECTION_COUNT}
     */
    static boolean isLargeHub(HubElementEntryDto entry) {
        Objects.requireNonNull(entry, "entry");
        return entry.connectionCount() > LARGE_HUB_CONNECTION_COUNT;
    }

    /**
     * Returns whether the view carries at least one large hub, i.e. an element
     * exceeding {@link #LARGE_HUB_CONNECTION_COUNT} connections.
     *
     * <p>Callers pass the result of {@code detect-hub-elements} unchanged.
     * That result is <em>not</em> pre-filtered to large hubs — it admits
     * every element with at least one connection — so its emptiness says
     * nothing about hub size and must not be used in place of this call.</p>
     *
     * @param hubResult the {@code detect-hub-elements} result for the view
     * @return true when some element on the view is a large hub
     */
    static boolean hasLargeHubs(DetectHubElementsResultDto hubResult) {
        Objects.requireNonNull(hubResult, "hubResult");
        return hubResult.elements().stream()
                .anyMatch(HubSpacingSignal::isLargeHub);
    }
}
