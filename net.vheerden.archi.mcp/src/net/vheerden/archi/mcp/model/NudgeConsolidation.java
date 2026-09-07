package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.response.dto.NudgedElementDto;

/**
 * Turns the {@code auto-route-connections} autoNudge loop's per-element cumulative-delta map into
 * the two lists the response is built from: the elements that actually ended somewhere new, and the
 * elements that ended exactly where they started.
 *
 * <p>This class lives OUTSIDE {@link ArchiModelAccessorImpl} on purpose: it is a small, unmeasured
 * collaborator so the facade's size ratchet ({@code tools/size-ratchet.sh}) is unaffected — the same
 * arrangement {@link InputValidation} uses. It is also the only place this behaviour can be pinned.
 * The nudge loop that fills the map runs only when the router leaves a connection unroutable, and
 * A* routes around every blocker the headless harness can build, so a test driving
 * {@code autoRouteConnections} never enters the loop body and would stay green with the split
 * missing entirely.</p>
 *
 * <p><b>Why the split exists.</b> The loop runs up to two iterations and accumulates each
 * iteration's actual displacement per element. Two different mechanisms can leave that sum at
 * {@code (0, 0)}: a move applied in one iteration and reversed in the next, and a move fully
 * absorbed by the parent-containment clamp on its only iteration. Both mean the same thing to the
 * caller — the element is where it was, and any connection the move was meant to unblock is still
 * blocked. Reporting such an element as nudged made the response contradict its own {@code failed}
 * array and its own {@code recommendations}, which repeat the identical move on the next call.</p>
 *
 * <p><b>The delta map is read, never written.</b> The same map drives applied geometry — the
 * virtual re-positioning for re-routing, the terminal re-alignment and relative-bendpoint re-encode
 * pass, and the parent-fit cascade. Dropping a key from it would change what the model ends up
 * holding. The defect was publishing a summed row without asking whether the sum was zero, not the
 * summing, so this returns new lists and leaves the map exactly as it was handed over.</p>
 */
final class NudgeConsolidation {

    private NudgeConsolidation() {}

    /**
     * The consolidated split.
     *
     * @param moved   one entry per element whose net displacement is non-zero, in the map's
     *                iteration order; this is the list the response reports as {@code nudgedElements}
     * @param netZero one entry per element whose net displacement is {@code (0, 0)}, carrying its id
     *                and name so the outcome can be named rather than silently dropped; the deltas
     *                on these entries are always zero by construction
     */
    record Result(List<NudgedElementDto> moved, List<NudgedElementDto> netZero) {}

    /**
     * Splits the cumulative deltas into the elements that moved and the elements that did not.
     *
     * @param cumulativeDeltas per-element summed {@code {dx, dy}} displacement; read only
     * @param elementNames     per-element display name, keyed by the same ids
     * @return the split; both lists are new and independent of the inputs
     */
    static Result consolidate(Map<String, int[]> cumulativeDeltas,
            Map<String, String> elementNames) {
        List<NudgedElementDto> moved = new ArrayList<>();
        List<NudgedElementDto> netZero = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : cumulativeDeltas.entrySet()) {
            int[] deltas = entry.getValue();
            NudgedElementDto dto = new NudgedElementDto(entry.getKey(),
                    elementNames.get(entry.getKey()), deltas[0], deltas[1]);
            if (deltas[0] == 0 && deltas[1] == 0) {
                netZero.add(dto);
            } else {
                moved.add(dto);
            }
        }
        return new Result(List.copyOf(moved), List.copyOf(netZero));
    }
}
