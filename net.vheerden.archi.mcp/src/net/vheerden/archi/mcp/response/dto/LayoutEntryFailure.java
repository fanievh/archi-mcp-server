package net.vheerden.archi.mcp.response.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Failure details for a single entry within an {@code apply-positions} response.
 *
 * <p>Distinct from {@link BulkOperationFailure} because this tool takes <em>two</em> caller-supplied
 * arrays. A lone {@code index} cannot distinguish {@code positions[2]} from {@code connections[2]},
 * and flattening the two into one {@code 0..P+C-1} index space would publish a number the caller
 * cannot find in anything it sent. The array is named in its own field rather than borrowed from a
 * slot meant for something else.</p>
 *
 * @param array               the caller's array this entry came from: {@code positions} or
 *                            {@code connections}
 * @param index               the 0-based position of this entry within that array
 * @param id                  the id the entry named, or null when the entry was too malformed to
 *                            carry one
 * @param errorCode           this entry's own error code
 * @param message             human-readable error description, wrapper included
 * @param suggestedCorrection guidance on how to fix the entry, or null
 */
public record LayoutEntryFailure(
    String array,
    int index,
    String id,
    String errorCode,
    String message,
    String suggestedCorrection
) implements FailureRow {

    @Override
    public Map<String, Object> identity() {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("array", array);
        // Absent only for an entry that is not an object at all, where the caller named no id.
        // Reporting the key as null would claim it named nothing when it named something unreadable.
        if (id != null) {
            keys.put("id", id);
        }
        return keys;
    }
}
