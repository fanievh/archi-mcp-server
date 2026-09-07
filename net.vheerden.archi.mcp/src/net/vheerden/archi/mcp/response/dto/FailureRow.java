package net.vheerden.archi.mcp.response.dto;

import java.util.Map;

/**
 * One entry of a multi-entry mutation that failed pre-validation, in the shape its refusal
 * publishes.
 *
 * <p>Two tools now refuse this way — {@code bulk-mutate} over its single {@code operations} array,
 * and {@code apply-positions} over its {@code positions} and {@code connections} arrays — and an
 * agent that learned the row shape from one must be able to read the other. The projection that
 * turns these into wire rows is single-sourced on this interface rather than hand-built per tool,
 * because two copies of one row shape drift and this repository has paid for that before.</p>
 *
 * <p>{@link #index()} is always the caller's own request index, never a position in the result
 * list: only the index the caller can find in something it sent lets it map a row back to the
 * entry it has to fix.</p>
 */
public interface FailureRow {

    /** The 0-based position of this entry within the caller's array. */
    int index();

    /**
     * The keys that say what {@link #index()} is an index into, emitted in order directly after it.
     *
     * <p>One array needs nothing here beyond the tool that was attempted; two arrays need to name
     * which one, because {@code index 2} alone cannot distinguish two different entries a caller
     * sent. Empty rather than null when there is nothing to add.</p>
     */
    Map<String, Object> identity();

    /** This entry's own error code, never flattened to a single whole-call code. */
    String errorCode();

    String message();

    /** Guidance for this entry, or null when the failure carried none. */
    String suggestedCorrection();
}
