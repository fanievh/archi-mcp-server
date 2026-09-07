package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Builds the error raised when a required string parameter is missing, naming the spelling the
 * caller actually supplied when it recognises one.
 *
 * <p>The same identifier is spelled several ways across the tool surface — an element's model id is
 * {@code id} on update-element, {@code elementId} on add-to-view and delete-element, and
 * {@code objectId} on move-to-folder; a relationship's is {@code id} or {@code relationshipId}; a
 * folder's is {@code id} or {@code folderId}. No JSON-schema validator is wired into the transport
 * and the tool schemas declare no {@code additionalProperties}, so a key spelled a sibling tool's
 * way is dropped in silence and the caller is told only that something is missing — while holding
 * that something under a name one synonym away.</p>
 *
 * <p>This explains rather than accepts. Teaching the spelling costs no permanent schema surface,
 * keeps every tool's {@code required} list intact, and covers every spelling in the table below
 * rather than the one pair that happened to be reported. Making the shared read <em>accept</em>
 * synonyms would instead normalise the whole surface to cure one pair, and entrench both spellings
 * where the goal is to converge on one.</p>
 *
 * <p>Both seams that read a required parameter delegate here — the standalone handlers' read and
 * bulk-mutate's per-operation read. They are separate code paths that never call one another, so a
 * diagnostic added to one alone would leave a fresh divergence in place of the old one.</p>
 */
public final class ParamNameDiagnostics {

    /**
     * For each required parameter, the other spellings the same concept carries elsewhere on the
     * tool surface — the keys a caller plausibly sends instead. Deliberately confined to identifier
     * names: these are the spellings that collide, and a wider table would start guessing.
     *
     * <p>Entries are directional. {@code viewId} lists only {@code id} because no tool spells a
     * view's id any other way, so offering an element spelling there would invent a near miss.
     * Every spelling listed is one some tool actually uses — a suggestion to rename a key to a name
     * nothing accepts would be worse than no suggestion.</p>
     */
    private static final Map<String, List<String>> ALTERNATIVE_SPELLINGS = Map.of(
            "id", List.of("elementId", "relationshipId", "folderId", "objectId", "conceptId", "viewId"),
            "elementId", List.of("id", "objectId", "conceptId"),
            "relationshipId", List.of("id"),
            "folderId", List.of("id"),
            "objectId", List.of("id", "elementId"),
            "viewId", List.of("id"),
            "viewObjectId", List.of("id", "objectId", "elementId"),
            "viewConnectionId", List.of("id", "relationshipId"),
            "conceptId", List.of("id", "elementId", "relationshipId", "objectId"));

    private ParamNameDiagnostics() {
    }

    /**
     * The spelling supplied in place of {@code requiredKey}, or {@code null} when nothing
     * resembling it was supplied. A blank value counts as absent, matching how both seams treat a
     * blank required parameter.
     */
    public static String suppliedInsteadOf(Map<String, ?> params, String requiredKey) {
        if (params == null) {
            return null;
        }
        for (String alternative : ALTERNATIVE_SPELLINGS.getOrDefault(requiredKey, List.of())) {
            if (params.get(alternative) instanceof String str && !str.isBlank()) {
                return alternative;
            }
        }
        return null;
    }

    /**
     * The error for a missing parameter on bulk-mutate's per-operation read. A failure here is
     * re-wrapped as a whole-call validation failure, so the caller resends every operation in the
     * call — which is what makes naming the spelling worth more here than anywhere else.
     */
    public static ModelAccessException missingBulkParameter(Map<String, ?> params, String requiredKey) {
        return missingBulkParameter(params, requiredKey, false);
    }

    /**
     * As {@link #missingBulkParameter(Map, String)}, but able to describe a parameter whose seam
     * accepts the empty string as a value. See
     * {@link #missingHandlerParameter(Map, String, boolean)} for why the wording has to follow the
     * seam rather than stay uniform.
     */
    public static ModelAccessException missingBulkParameter(Map<String, ?> params, String requiredKey,
            boolean emptyPermitted) {
        String supplied = suppliedInsteadOf(params, requiredKey);
        if (supplied == null) {
            return new ModelAccessException(
                    "Missing required parameter '" + requiredKey + "'",
                    ErrorCode.INVALID_PARAMETER, null,
                    provideCorrection(requiredKey, emptyPermitted), null);
        }
        return new ModelAccessException(
                nearMissMessage(requiredKey, supplied),
                ErrorCode.INVALID_PARAMETER, null, renameCorrection(requiredKey, supplied), null);
    }

    /** The error for a missing parameter on a standalone handler's read. */
    public static ModelAccessException missingHandlerParameter(Map<String, ?> args, String requiredKey) {
        return missingHandlerParameter(args, requiredKey, false);
    }

    /**
     * As {@link #missingHandlerParameter(Map, String)}, but able to describe a parameter whose seam
     * accepts the empty string as a value.
     *
     * <p>The default wording tells the caller to supply a <em>non-empty</em> value and reports the
     * failure as "missing or empty". Where the seam accepts "" that advice is false, and false
     * advice on this particular axis is not cosmetic: the caller is usually an LLM agent, and being
     * told to provide something non-empty is precisely what makes it invent a placeholder and paint
     * it onto the deliverable. So the wording follows the seam.</p>
     */
    public static ModelAccessException missingHandlerParameter(Map<String, ?> args, String requiredKey,
            boolean emptyPermitted) {
        String supplied = suppliedInsteadOf(args, requiredKey);
        if (supplied == null) {
            return new ModelAccessException(
                    emptyPermitted
                            ? "Missing required parameter: " + requiredKey
                            : "Missing or empty required parameter: " + requiredKey,
                    ErrorCode.INVALID_PARAMETER, null,
                    provideCorrection(requiredKey, emptyPermitted), null);
        }
        return new ModelAccessException(
                nearMissMessage(requiredKey, supplied),
                ErrorCode.INVALID_PARAMETER, null, renameCorrection(requiredKey, supplied), null);
    }

    private static String provideCorrection(String requiredKey, boolean emptyPermitted) {
        return emptyPermitted
                ? "Provide a string value for '" + requiredKey + "'. An empty string is accepted."
                : "Provide a non-empty string value for '" + requiredKey + "'";
    }

    private static String nearMissMessage(String requiredKey, String supplied) {
        return "Missing required parameter '" + requiredKey + "' — '" + supplied
                + "' was supplied, which this tool does not accept.";
    }

    private static String renameCorrection(String requiredKey, String supplied) {
        return "Rename '" + supplied + "' to '" + requiredKey
                + "'. The same identifier is spelled differently by different tools; this one "
                + "spells it '" + requiredKey + "'.";
    }
}
