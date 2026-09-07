package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.CompoundCommand;

import net.vheerden.archi.mcp.response.dto.BulkOperationResult;

/**
 * Builds the set of ids the staleness guard fingerprints for a <strong>{@code bulk-mutate}</strong>
 * proposal.
 *
 * <p>Its own collaborator rather than a facade helper because the set it returns <em>is</em> the
 * whole check on this path. {@code bulk-mutate} freezes the reviewed compound and applies that
 * compound on approval rather than rebuilding it, so there is no approve-time
 * {@code prepareXxx} left to throw on a vanished endpoint — the rebuild-throw safety net that
 * covers the thirteen reviewed-or-reject siblings does not exist here. An id this misses is an id
 * nothing else will catch.</p>
 *
 * <p>Package-private, {@code model/}-only and dependency-light — it constructs no model and needs
 * no OSGi runtime — so it is reachable headlessly, unlike the OSGi-gated
 * {@code ArchiModelAccessorImpl} that calls it.</p>
 */
final class BulkStalenessTargets {

    private BulkStalenessTargets() {
        // static-only utility
    }

    /**
     * Tracked-id set for a {@code bulk-mutate} proposal: the same {@link CompoundChildTargets} walk
     * its thirteen reviewed-or-reject siblings use — which is what picks up the containers a
     * placement is placed into — anchored on each op's own entity id and unioned with the
     * create-relationship endpoint ids captured at propose. Ids naming a not-yet-created object are
     * skipped by {@link ProposalStalenessGuard#capture}; nothing resolvable exists to fingerprint.
     *
     * @param compound           the compound whose child commands are walked
     * @param operationResults   the per-operation results, for their own entity ids
     * @param secondaryTargetIds pre-existing endpoint ids captured at propose time; may be null
     * @return the tracked ids, insertion-ordered, never null
     */
    static Set<String> bulkTargetIds(CompoundCommand compound,
            List<BulkOperationResult> operationResults, Set<String> secondaryTargetIds) {
        Set<String> ids = CompoundChildTargets.collect(compound, operationResults.stream()
                .map(BulkOperationResult::entityId).toArray(String[]::new));
        if (secondaryTargetIds != null) {
            for (String id : secondaryTargetIds) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
