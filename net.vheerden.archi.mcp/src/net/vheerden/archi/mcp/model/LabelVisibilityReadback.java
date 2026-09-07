package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.model.routing.LabelPolicy;
import net.vheerden.archi.mcp.response.dto.HiddenLabelDto;

/**
 * Turns a label policy's <em>intent</em> into the response's <em>effective state</em>.
 *
 * <p>A routing pass decides which labels it cannot place while it is building commands, before any
 * of those commands has run. Reporting that decision directly would echo the request back to the
 * caller — the classic failure this codebase treats as a correctness defect, because the caller
 * cannot see the canvas and the response is its only ground truth. So on the immediate path this
 * re-reads each connection from the model <em>after</em> the write and reports only the labels that
 * are genuinely hidden now.</p>
 *
 * <p>On the deferred paths (queued in a batch, or parked behind an approval gate) nothing has been
 * written yet, so no amount of re-reading could make the answer effective. There the projection is
 * returned unchanged and the response envelope nests the whole entity under {@code preview}, which
 * is what declares it as not-yet-applied.</p>
 */
final class LabelVisibilityReadback {

    private LabelVisibilityReadback() {
        // static helper
    }

    /**
     * Reports the labels this pass hid.
     *
     * @param model       the model to re-read from
     * @param hiddenIds   the connection IDs the policy decided to hide
     * @param applied     true when the commands have actually run (the immediate path); false when
     *                    the write is deferred, in which case the projection is returned as-is
     * @return one entry per hidden label, never truncated, empty when the policy hid nothing
     */
    static List<HiddenLabelDto> report(IArchimateModel model, List<String> hiddenIds,
            boolean applied) {
        if (hiddenIds == null || hiddenIds.isEmpty()) {
            return List.of();
        }
        List<HiddenLabelDto> reported = new ArrayList<>(hiddenIds.size());
        for (String connectionId : hiddenIds) {
            if (applied && !isLabelHidden(model, connectionId)) {
                continue; // the write did not take — do not claim it did
            }
            reported.add(HiddenLabelDto.noValidPosition(connectionId));
        }
        return reported;
    }

    /**
     * The policy's decision as a list, with no model lookup — the honest content for a response
     * whose write has not happened yet (queued in a batch, or parked behind an approval gate).
     */
    static List<HiddenLabelDto> projected(List<String> hiddenIds) {
        return report(null, hiddenIds, false);
    }

    /**
     * Combines the hides of two routing passes in the same call.
     *
     * <p>A pass that re-routes after an auto-nudge produces its own hides, and those commands are
     * merged into the same compound and really execute — so omitting them from the merged list would
     * hide a label with no trace of it in the response, which is the one thing this feature exists to
     * prevent. Order is preserved and duplicates are dropped: a connection re-routed in a later
     * iteration must be named once, not twice.</p>
     */
    static List<String> merge(List<String> first, List<String> second) {
        if (second == null || second.isEmpty()) {
            return (first != null) ? first : List.of();
        }
        if (first == null || first.isEmpty()) {
            return second;
        }
        List<String> merged = new ArrayList<>(first);
        for (String id : second) {
            if (!merged.contains(id)) {
                merged.add(id);
            }
        }
        return merged;
    }

    /**
     * Rejects a hiding policy on a code path that runs no label optimizer.
     *
     * <p>Such a path holds no evidence about where a label can sit, so accepting the policy there
     * would produce a call that looks like it hid labels and did nothing at all. Refusing keeps every
     * routing entry point consistent with the ruling that excluded the tool which creates connections
     * without geometry: a pass that computes no label geometry cannot honestly act on a collision.</p>
     *
     * @param situation what the caller asked for, named in the error
     * @param remedy    the path that DOES route, so the caller can proceed
     */
    static void requireRoutingPass(LabelPolicy policy, String situation, String remedy) {
        if (policy != null && policy.hidesUnplaceableLabels()) {
            throw new ModelAccessException(
                    "labelPolicy needs a routing pass, and " + situation + " does not run one",
                    ErrorCode.INVALID_PARAMETER, null, remedy, null);
        }
    }

    /**
     * Drops the connections a still-queued command already hides.
     *
     * <p>Inside an open batch the routing pass reads a model none of the batch's own commands have
     * touched, so a label an earlier operation in the same batch already hid still reads as visible.
     * Claiming it would report a false cause for a hide the caller asked for themselves — the policy
     * is documented never to claim a hide it did not make. The redundant visibility write is
     * harmless (it sets what the queued command already sets); only the attribution is wrong, so
     * only the attribution is corrected.</p>
     *
     * @param queuedVisibility connection id → visibility a queued command will set, or null outside
     *                         batch mode, in which case the live read was already authoritative
     */
    static List<String> excludeQueuedHides(List<String> hiddenIds, Map<String, Boolean> queuedVisibility) {
        if (hiddenIds == null || hiddenIds.isEmpty() || queuedVisibility == null
                || queuedVisibility.isEmpty()) {
            return (hiddenIds != null) ? hiddenIds : List.of();
        }
        List<String> owned = new ArrayList<>(hiddenIds.size());
        for (String id : hiddenIds) {
            if (!Boolean.FALSE.equals(queuedVisibility.get(id))) {
                owned.add(id);
            }
        }
        return owned;
    }

    /** True when the connection exists and its label is currently not visible. */
    private static boolean isLabelHidden(IArchimateModel model, String connectionId) {
        if (model == null || connectionId == null) {
            return false;
        }
        EObject obj = ArchimateModelUtils.getObjectByID(model, connectionId);
        return (obj instanceof IDiagramModelConnection conn) && !conn.isNameVisible();
    }

    /**
     * Approval-card sentence naming the labels a proposal will hide, or an empty string when it
     * will hide none.
     *
     * <p>This gate applies the reviewed result exactly as computed and does not recompute it on
     * approval, so the hide decision is frozen at propose time along with the routes it travels
     * with. Naming the affected connections on the card is what lets the reviewer see the decision
     * they are actually approving instead of discovering it afterwards.</p>
     */
    static String describeFrozenHides(List<String> hiddenIds) {
        if (hiddenIds == null || hiddenIds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" ");
        sb.append(hiddenIds.size())
          .append(hiddenIds.size() == 1
                  ? " connection label has no collision-free position and will be hidden: "
                  : " connection labels have no collision-free position and will be hidden: ");
        for (int i = 0; i < hiddenIds.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(hiddenIds.get(i));
        }
        sb.append('.');
        return sb.toString();
    }
}
