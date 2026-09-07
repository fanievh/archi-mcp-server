package net.vheerden.archi.mcp.response.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for model summary information.
 *
 * <p>Returned by the get-model-info and update-model commands. Provides a
 * high-level overview of the loaded ArchiMate model including element counts,
 * type distributions, layer distribution, and the model's
 * own metadata — purpose and custom properties.</p>
 *
 * <p>{@code purpose} and {@code properties} fields added for
 * read-write parity with the new {@code update-model} tool. The {@code documentation}
 * field deliberately does NOT appear here — {@code IArchimateModel} does not extend
 * {@code IDocumentable} in Archi 5.7/5.8; the model's free-text field IS {@code purpose}.</p>
 *
 * <p>{@link JsonInclude.Include#NON_NULL} omits the new fields when at Archi
 * defaults (purpose = null on a freshly-created model, properties = null when
 * the EList is empty), preserving byte-identical legacy {@code get-model-info}
 * responses for callers that never write to those fields.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelInfoDto(
    String name,
    String purpose,
    Map<String, String> properties,
    int elementCount,
    int relationshipCount,
    int viewCount,
    int specializationCount,
    Map<String, Integer> elementTypeDistribution,
    Map<String, Integer> relationshipTypeDistribution,
    Map<String, Integer> layerDistribution,
    Boolean approvalMode
) {
    /**
     * Backward-compatible constructor for the 10-field call sites that pre-date the approval-mode field.
     * Delegates to the canonical 11-field constructor with {@code approvalMode} null (Jackson
     * omits it per {@link JsonInclude.Include#NON_NULL}); the get-model-info handler later
     * stamps the live, human-owned approval bit via {@link #withApprovalMode(Boolean)} so the
     * value is never served stale from cache.
     */
    public ModelInfoDto(String name, String purpose, Map<String, String> properties,
            int elementCount, int relationshipCount, int viewCount, int specializationCount,
            Map<String, Integer> elementTypeDistribution,
            Map<String, Integer> relationshipTypeDistribution,
            Map<String, Integer> layerDistribution) {
        this(name, purpose, properties, elementCount, relationshipCount, viewCount,
                specializationCount, elementTypeDistribution,
                relationshipTypeDistribution, layerDistribution, null);
    }

    /**
     * Backward-compatible constructor for legacy 8-field call sites that pre-date
     * the model-metadata fields. Delegates with {@code purpose}, {@code properties}, and
     * {@code approvalMode} set to null (Jackson omits them per
     * {@link JsonInclude.Include#NON_NULL}).
     */
    public ModelInfoDto(String name,
            int elementCount, int relationshipCount, int viewCount, int specializationCount,
            Map<String, Integer> elementTypeDistribution,
            Map<String, Integer> relationshipTypeDistribution,
            Map<String, Integer> layerDistribution) {
        this(name, null, null, elementCount, relationshipCount, viewCount,
                specializationCount, elementTypeDistribution,
                relationshipTypeDistribution, layerDistribution, null);
    }

    /**
     * Returns a copy with the read-only approval-mode bit stamped on.
     * The get-model-info handler calls this with the live human-owned bit so the agent can
     * honestly say "you're gated — confirm in Archi"; all other callers (e.g. update-model)
     * leave it null and Jackson omits it.
     *
     * <p>NOTE: hand-rolled copy (records have no generated {@code with}-er) — when a future story
     * adds a record component, thread it through here too or it will be dropped on this copy.</p>
     *
     * @param mode the current global approval bit, or null to omit
     * @return a copy of this DTO with {@code approvalMode} set
     */
    public ModelInfoDto withApprovalMode(Boolean mode) {
        return new ModelInfoDto(name, purpose, properties, elementCount, relationshipCount,
                viewCount, specializationCount, elementTypeDistribution,
                relationshipTypeDistribution, layerDistribution, mode);
    }

    /**
     * A copy carrying the name an update is about to write, or this DTO unchanged when the update
     * does not touch the name.
     *
     * <p>Exists because a model update is prepared from a live read taken <em>before</em> its
     * command runs, so the name in that read is the one the operation is about to replace. Where
     * nothing has executed — a queued batch or a proposal awaiting approval — that read is the only
     * value the response can carry, and reporting it there would describe the pre-change state as
     * if it were the queued one. Handing the requested name instead makes the preview say what the
     * operation asked for, which is what the sibling folder prepare already does.</p>
     *
     * <p>{@code null} means the update leaves the name alone, so the live read is already correct
     * and is kept.</p>
     *
     * <p>NOTE: hand-rolled copy (records have no generated {@code with}-er) — when a future story
     * adds a record component, thread it through here too or it will be dropped on this copy.</p>
     *
     * @param requested the name the update will write, or null when it changes no name
     * @return a copy of this DTO carrying {@code requested}, or this DTO when it is null
     */
    public ModelInfoDto withName(String requested) {
        return (requested == null) ? this
                : new ModelInfoDto(requested, purpose, properties, elementCount, relationshipCount,
                        viewCount, specializationCount, elementTypeDistribution,
                        relationshipTypeDistribution, layerDistribution, approvalMode);
    }
}
