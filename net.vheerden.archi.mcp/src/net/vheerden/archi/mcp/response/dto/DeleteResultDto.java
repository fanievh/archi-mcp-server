package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for deletion operation results.
 *
 * <p>Contains the deleted entity's identity and cascade counts indicating
 * how many related objects were also removed. Folder-specific cascade counts
 * (elementsRemoved, viewsRemoved, foldersRemoved) are null — and so omitted from
 * JSON output — in two cases: for non-folder deletions, and for folder deletions
 * without force, where the folder had to be empty for the delete to be prepared
 * at all. The other three counts are primitive and therefore always present,
 * reading 0 when nothing of that kind was removed.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeleteResultDto(
    String id,
    String name,
    String type,
    int relationshipsRemoved,
    int viewReferencesRemoved,
    int viewConnectionsRemoved,
    Integer elementsRemoved,
    Integer viewsRemoved,
    Integer foldersRemoved
) {

    /**
     * Returns a copy naming the entity as it was called when the deletion actually ran.
     *
     * <p>Only the bulk path uses this, and only where an earlier operation in the same call renamed
     * the subject after this report was prepared. A standalone deletion has no earlier operation to
     * be stale about, so its report is built and shipped unchanged and its wire bytes are the bytes
     * it always was.</p>
     *
     * <p>Every component is named explicitly rather than delegated to a shorter constructor. This
     * record has none, but the sibling copiers on {@code BulkOperationResult} record what happens
     * when a copier defaults a component away: a value was computed, attached, and then silently
     * discarded on every dispatched operation.</p>
     */
    public DeleteResultDto withName(String name) {
        return new DeleteResultDto(id, name, type, relationshipsRemoved, viewReferencesRemoved,
                viewConnectionsRemoved, elementsRemoved, viewsRemoved, foldersRemoved);
    }
}
