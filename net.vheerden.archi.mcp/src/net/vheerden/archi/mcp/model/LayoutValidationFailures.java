package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.response.dto.LayoutEntryFailure;

/**
 * Collects the entries that fail while an {@code apply-positions} call is being validated.
 *
 * <p>Shared by both stages of that validation, on purpose. The request shapes are read in the
 * handler and the ids are resolved in the accessor, and both walks used to unwind at their first
 * bad entry; one collector means one cap, one one-versus-many rule and one refusal type rather than
 * two of each drifting apart a layer away from one another.</p>
 *
 * <p>Deliberately not a copy of {@link BulkValidationFailures}: half of that class exists to keep a
 * back-reference cascade check in step, and this tool has no back-references at all. What is copied
 * is the shape of its refusal — the first failure's scalars unchanged, a count clause only once
 * there is more than one thing to count — because that is what keeps a single-entry refusal exactly
 * what it always was.</p>
 *
 * <p>Each failure keeps the array it came from as well as its index within that array. The tool
 * takes two caller-supplied arrays, so an index alone names two different entries.</p>
 *
 * <p>The rows are capped. {@code apply-positions} accepts ten thousand entries, and the all-fail
 * case is the realistic one rather than the pathological one — an agent replaying a saved layout
 * onto a view that was cleared and rebuilt has every id stale. The true count is kept whole
 * alongside the capped list so that every clause the refusal publishes states the real number; a
 * capped reader must publish no count or a true one, never a wrong one.</p>
 *
 * <p>{@code model/}-only and dependency-light: no EMF, no OSGi, so the refusal it builds is
 * pinnable headlessly rather than only through the OSGi-gated facade.</p>
 */
public final class LayoutValidationFailures {

    /**
     * How many failed entries travel as rows. Fifty stale ids already tell a caller its payload is
     * wholesale wrong, and an unbounded array over a ten-thousand-entry ceiling is a response-size
     * defect of its own. The cost — that a caller with more than fifty defects still needs a second
     * round-trip — was weighed and accepted.
     */
    private static final int MAX_REPORTED_ROWS = 50;

    private final List<LayoutEntryFailure> rows = new ArrayList<>();

    /**
     * How many entries failed per array, counted whether or not the entry fitted in {@link #rows}.
     * The cap is shared and the walks run in a fixed order, so a first array that fails wholesale
     * can take every slot; the refusal has to be able to say that the second array failed too.
     */
    private final Map<String, Integer> totalsByArray = new LinkedHashMap<>();

    private int totalFailures;
    private ModelAccessException firstCause;
    private String firstMessage;
    private boolean firstKeepsCauseScalars;

    /** Records a {@code positions} entry whose id could not be resolved. */
    public void recordPosition(int index, String viewObjectId, ModelAccessException cause) {
        recordResolution("positions", "Position", "viewObjectId", index, viewObjectId, cause);
    }

    /** Records a {@code connections} entry whose id could not be resolved. */
    public void recordConnection(int index, String viewConnectionId, ModelAccessException cause) {
        recordResolution("connections", "Connection", "viewConnectionId", index, viewConnectionId,
                cause);
    }

    private void recordResolution(String array, String entryLabel, String idField, int index,
            String id, ModelAccessException cause) {
        // The per-entry wrapper is what tells an agent WHICH entry failed, so it is built here
        // rather than at the two call sites: one spelling of it, for both arrays.
        //
        // The wrapper republishes none of the cause's other fields, which is what this path has
        // always done — it wrapped with a message, a cause and an error code and nothing else. The
        // inner hint survives on the entry's own row, where it describes the entry it belongs to
        // instead of being promoted to speak for a whole call that may have failed for several
        // unrelated reasons.
        record(array, index, id,
                entryLabel + " entry [" + index + "] (" + idField + "='" + id + "'): "
                        + cause.getMessage(),
                cause, false);
    }

    /**
     * Records an entry whose shape could not be read at all. Its message already names the array
     * and the index, so it is published as it stands.
     */
    public void recordMalformedEntry(String array, int index, ModelAccessException cause) {
        record(array, index, null, cause.getMessage(), cause, true);
    }

    /**
     * Records a readable entry one of whose fields was rejected.
     *
     * <p>Prefixed with the entry it came from. These messages are raised by helpers shared with the
     * single-connection tools, where there is no entry to name and none is wanted; reached from an
     * array they leave a caller told that {@code Bendpoint[2]} is malformed without being told
     * which of its connections carried it.</p>
     *
     * <p>Only for failures that name something <em>inside</em> the entry. A missing or blank id is
     * recorded through {@link #recordMalformedEntry} instead, because it already reads as a
     * parameter fault and a one-entry call must publish exactly the message it always did.</p>
     */
    public void recordEntryField(String array, int index, String id, ModelAccessException cause) {
        record(array, index, id, array + "[" + index + "]: " + cause.getMessage(), cause, true);
    }

    private void record(String array, int index, String id, String message,
            ModelAccessException cause, boolean keepsCauseScalars) {
        if (rows.isEmpty()) {
            firstCause = cause;
            firstMessage = message;
            firstKeepsCauseScalars = keepsCauseScalars;
        }
        totalFailures++;
        totalsByArray.merge(array, 1, Integer::sum);
        if (rows.size() < MAX_REPORTED_ROWS) {
            rows.add(new LayoutEntryFailure(array, index, id,
                    cause.getErrorCode() != null ? cause.getErrorCode().name() : "UNKNOWN",
                    message, cause.getSuggestedCorrection()));
        }
    }

    public boolean isEmpty() {
        return totalFailures == 0;
    }

    /**
     * Builds the all-or-nothing refusal.
     *
     * <p>The scalar fields describe the first failure and nothing else, and each stage republishes
     * exactly the fields its own throw always published — the id-resolution wrapper carried a
     * message and a code alone, the shape refusals carried a correction too. Preserving that
     * difference is what keeps a one-entry refusal's fields what they were; levelling it would
     * change a refusal that is already right. The one thing that does move is the message of a
     * field-level failure, which gains its entry's prefix — see {@link #recordEntryField}.</p>
     *
     * <p>The count clause states how many failed and, separately, how many are listed. The second
     * is not always the first, and a sentence promising every one is listed would be false exactly
     * when a caller most needs to be told otherwise.</p>
     */
    public LayoutValidationException toException() {
        String message = firstMessage;
        if (totalFailures > 1) {
            message += " — " + totalFailures + " entries failed validation; "
                    + (rows.size() < totalFailures
                            ? "the first " + rows.size() + " are listed."
                            : "every one is listed.");
        }
        return new LayoutValidationException(message, firstCause.getErrorCode(),
                firstKeepsCauseScalars ? firstCause.getDetails() : null,
                firstKeepsCauseScalars ? firstCause.getSuggestedCorrection() : null,
                firstKeepsCauseScalars ? firstCause.getArchiMateReference() : null,
                firstCause, rows, totalsByArray, totalFailures);
    }
}
