package net.vheerden.archi.mcp.model;

import java.util.List;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;

/**
 * A bulk-mutate pre-validation refusal that carries <em>every</em> operation that failed, not only
 * the one that failed first.
 *
 * <p>A {@link ModelAccessException} so that every existing route keeps working unchanged — the
 * accessor's own rethrow, and the handler's {@code getErrorCode() == BULK_VALIDATION_FAILED} branch,
 * both see exactly what they saw before. The list rides alongside as a typed field.</p>
 *
 * <p>The scalar fields are deliberately those of the <strong>first</strong> failure alone, exactly
 * as they were before the list existed. A caller with one bad operation must read the same refusal
 * it always read; the list is what a caller with several gains, and leading with a count would make
 * the common single-failure case strictly less informative for no gain. The count is stated in the
 * message and the details only once there is more than one thing to count.</p>
 *
 * <p>Structured, not encoded: the failures are facts a client acts on per row, so they travel as
 * records and reach the wire as an array. Packing them into the {@code details} string would make
 * every consumer parse prose to recover data it was already handed.</p>
 */
public class BulkValidationException extends ModelAccessException {

    private static final long serialVersionUID = 1L;

    private final transient List<BulkOperationFailure> failures;

    BulkValidationException(String message, String details, String suggestedCorrection,
            String archiMateReference, List<BulkOperationFailure> failures) {
        super(message, ErrorCode.BULK_VALIDATION_FAILED, details, suggestedCorrection,
                archiMateReference);
        this.failures = List.copyOf(failures);
    }

    /**
     * Every operation that failed pre-validation, in request-index order. Never empty: this
     * exception is only built once at least one operation has failed.
     */
    public List<BulkOperationFailure> getFailures() {
        return failures;
    }
}
