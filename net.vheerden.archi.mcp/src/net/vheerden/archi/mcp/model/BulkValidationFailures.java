package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;

/**
 * Collects the operations that fail while a bulk-mutate call is being pre-validated.
 *
 * <p>One object holds both the rows a caller is shown and the index set the back-reference cascade
 * check consults, because keeping them in step is a correctness requirement rather than a
 * convenience: an index recorded as failed but missing from the set lets a dependent operation
 * resolve its back-reference to {@code null} and proceed — the silent mis-placement
 * {@link BulkBackReferences} exists to prevent. Recording a failure here always does both, so the
 * two cannot drift apart by omission at a call site.</p>
 *
 * <p>The first failure's original exception is kept whole rather than reconstructed from its row.
 * {@link BulkOperationFailure} has no slot for {@code archiMateReference}, and that field has to
 * survive verbatim for the single-failure refusal to stay exactly what it was.</p>
 *
 * <p>A cascade failure can never be the first one recorded — it is only ever looked for once the
 * set is non-empty — so the first entry always carries a cause.</p>
 *
 * <p>Package-private, {@code model/}-only and dependency-light: no EMF, no OSGi, so the refusal it
 * builds is pinnable headlessly rather than only through the OSGi-gated facade.</p>
 */
final class BulkValidationFailures {

    private final List<BulkOperationFailure> rows = new ArrayList<>();
    private final Set<Integer> failedIndices = new HashSet<>();

    private ModelAccessException firstCause;
    private int firstIndex;
    private String firstTool;

    /**
     * Records an operation that failed with an exception of its own, keeping that operation's own
     * error code rather than flattening it to the whole-call code.
     */
    void record(int index, String tool, ModelAccessException cause) {
        if (rows.isEmpty()) {
            firstCause = cause;
            firstIndex = index;
            firstTool = tool;
        }
        failedIndices.add(index);
        rows.add(new BulkOperationFailure(index, tool,
                cause.getErrorCode() != null ? cause.getErrorCode().name() : "UNKNOWN",
                cause.getMessage(),
                cause.getSuggestedCorrection()));
    }

    /**
     * Records an operation declined because it back-references one that already failed.
     */
    void recordCascade(int index, String tool, String message) {
        failedIndices.add(index);
        rows.add(new BulkOperationFailure(index, tool, "BACK_REFERENCE_FAILED", message,
                "Fix the referenced operation first, or remove the dependency"));
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    /** The indices recorded as failed, for the back-reference cascade check. */
    Set<Integer> failedIndices() {
        return failedIndices;
    }

    List<BulkOperationFailure> rows() {
        return List.copyOf(rows);
    }

    /**
     * Builds the all-or-nothing refusal. The scalar fields describe the first failure and nothing
     * else; the message and details gain a count only when there is more than one failure, and the
     * whole list travels as a typed field.
     */
    BulkValidationException toException() {
        String message = "Operation " + firstIndex + " (" + firstTool + "): "
                + firstCause.getMessage();
        String details = "failedOperationIndex=" + firstIndex + ", failedTool=" + firstTool;
        if (rows.size() > 1) {
            message += " — " + rows.size() + " operations failed validation; every one is listed.";
            details += ", failedOperationCount=" + rows.size();
        }
        return new BulkValidationException(message, details,
                firstCause.getSuggestedCorrection() != null
                        ? firstCause.getSuggestedCorrection()
                        : "Fix the failed operation and retry the entire bulk-mutate call",
                firstCause.getArchiMateReference(),
                rows);
    }
}
