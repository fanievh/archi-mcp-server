package net.vheerden.archi.mcp.model;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Resolves and validates the back-references one bulk-mutate operation uses to name an entity an
 * earlier operation in the same call created.
 *
 * <p>An earlier operation can be named two ways, and {@link #BACK_REFERENCE_PATTERN} accepts both
 * in one alternation. {@code $2.id} names it by its position in the operations array;
 * {@code $coreBanking.id} names the label that operation declared with {@code as}. Which branch was
 * taken is decided by the token's shape — a run of digits is a position, anything else is a label —
 * and nothing else about the two forms differs: a label is backward-only, create-tool-only,
 * cascade-visible and same-call-scoped exactly as a position is, because they meet at the same
 * {@link #validate} and the same {@link #checkCascade}.</p>
 *
 * <p><strong>Why both forms exist.</strong> The two namespaces have opposite failure modes. A
 * position lives in a dense one: every integer from 0 to the current index names some legal earlier
 * operation, so a mistyped {@code $5.id} written as {@code $4.id} resolves — to a different, legal
 * container, which no validator can object to because nesting one component inside another is
 * legal ArchiMate. A label lives in a sparse one: {@code coreBankng} matches no declaration, so the
 * only thing a mistyped label can do is refuse. The named form does not replace the positional one
 * and changes nothing about it.</p>
 *
 * <p>Three jobs that share one pattern and must keep sharing it. {@link #resolve} substitutes a
 * reference for the id it names; {@link #validate} decides whether the reference is legal at all
 * (self, forward, out-of-range, or pointing at a tool that creates nothing); {@link #checkCascade}
 * asks the separate question of whether the operation it points at <em>failed</em>. The first two
 * run on every operation, the third only once something earlier in the call has already failed.
 * {@link #indexLabels} runs once before any of them, and is where a label is checked for a legal
 * grammar and for uniqueness within the call.</p>
 *
 * <p><strong>The cascade check is not an optimisation — it is what keeps a dropped reference
 * loud.</strong> {@code resolve} substitutes {@code createdEntityIds.get(refIndex)} with no null
 * check, and the operation-tool map is populated for every operation including the ones that
 * failed, so a reference to a failed operation passes {@code validate} and resolves to
 * {@code null}. On a required parameter that surfaces as a misleading "missing required parameter"
 * for a parameter the caller did supply; on an <em>optional</em> one it is dropped in silence and
 * the operation succeeds with different semantics — an {@code add-to-view} whose
 * {@code parentViewObjectId} was a reference lands at the view root instead of inside the
 * container the caller named. {@code checkCascade} is what turns both into a stated
 * {@code BACK_REFERENCE_FAILED}, so it must run wherever an operation is allowed to fail and the
 * call is allowed to keep going.</p>
 *
 * <p>{@code checkCascade}'s blind spots are deliberately <em>co-extensive</em> with
 * {@code resolve}'s: both walk top-level {@code String} entries only, and both use
 * {@link #BACK_REFERENCE_PATTERN} with {@link Matcher#matches()} — a whole-string match, so
 * {@code "grp-$0.id"} and any reference nested inside a {@code List} or {@code Map} are invisible
 * to both. That symmetry is the safety property: a reference the cascade check cannot see is one
 * {@code resolve} never substitutes either, so it stays the literal text {@code "$2.id"} and fails
 * honestly downstream. Widening one without the other would break it — which is why the named form
 * was added to <em>both</em>, through the one shared pattern, rather than to the substituting side
 * alone.</p>
 *
 * <p>The property is stated precisely as: <em>{@code resolve} never substitutes a reference
 * {@code checkCascade} did not look at</em>. Both see the same tokens, and they answer different
 * questions about them, so their answers may differ without the property failing. A label naming an
 * operation that has not failed, and a label naming no operation at all, both leave
 * {@code checkCascade} silent — and in the second case {@code resolve} refuses outright rather than
 * substituting {@code null}, which is louder than a cascade, not quieter. There is no token either
 * one sees and the other does not.</p>
 *
 * <p>Archi's own label-expression grammar also begins with a dollar — {@code ${name}},
 * {@code ${property:KEY}} — and cannot collide with either form here, because a brace follows the
 * dollar there and this pattern requires a digit, a letter or an underscore. A label may not begin
 * with a digit for the same class of reason applied one level down: a label {@code 1} and a
 * position {@code 1} sharing one namespace is an ambiguity nothing downstream could resolve.</p>
 *
 * <p>Package-private, {@code model/}-only and dependency-light — it constructs no model, imports no
 * EMF and needs no OSGi runtime — so it is covered headlessly by {@code BulkBackReferencesTest},
 * unlike the OSGi-gated {@code ArchiModelAccessorImpl} that calls it. Those four refusal messages
 * and the cascade sentence had no headless route at all while they lived on the facade.</p>
 */
final class BulkBackReferences {

    private BulkBackReferences() {
        // static-only utility
    }

    /**
     * The one pattern both {@link #resolve} and {@link #checkCascade} match with, covering a
     * positional reference and a named one in a single alternation.
     *
     * <p>The digits branch is first and the identifier branch cannot begin with a digit, so the two
     * are disjoint and the token's own shape says which was written. A value that is neither —
     * {@code "$1core.id"} — matches nothing and survives as literal text, the same honest failure
     * this class already gives a partial match.</p>
     */
    private static final Pattern BACK_REFERENCE_PATTERN =
            Pattern.compile("\\$(\\d+|[A-Za-z_][A-Za-z0-9_]*)\\.id");

    /**
     * The grammar a declared label must match, held separately from the reference pattern above so
     * a label is refused where it is <em>declared</em> rather than only where it is used.
     *
     * <p>A label that could never be referenced — one starting with a digit, or carrying a hyphen,
     * a space or a dot — would otherwise be accepted in silence and its reference would fail
     * somewhere else entirely, which is the displaced blame this form exists to remove.</p>
     */
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * What a caller is told when a back-reference names something this call created that is not
     * addressable on a view.
     *
     * <p>Held here rather than at its two use sites because those two sites carried
     * <em>byte-identical</em> copies with nothing asserting they stayed identical, so editing one
     * shipped a silent divergence — which is exactly what adding the named form to the sentence
     * would have done.</p>
     */
    static final String VIEW_TARGET_ADVICE =
            "Use get-view-contents to find valid view object IDs and connection IDs on the view. A "
            + "back-reference — $N.id by position, or $name.id when the operation declared as: "
            + "\"name\" — resolves only to something addressable ON a view: a note, group, element "
            + "view object or connection this call created. An element or relationship created in "
            + "this call is a model concept with no view object until an add-to-view or "
            + "add-connection-to-view places it — back-reference THAT operation instead.";

    private static final Set<String> CREATE_TOOLS = Set.of(
            "create-element", "create-relationship", "create-view",
            "add-to-view", "add-connection-to-view",
            "add-group-to-view", "add-note-to-view");

    /**
     * Indexes the labels the operations declare, refusing the two things a declaration can get
     * wrong: a name the grammar does not accept, and a name two operations both claim.
     *
     * <p>Both are checked here, once, before any operation is prepared, because both are properties
     * of the operations array as a whole rather than of the operation being prepared. Refusals are
     * recorded against the offending operation through the same collector every other
     * pre-validation failure uses, so a payload with three bad labels is reported in one refusal
     * naming all three rather than one per round-trip.</p>
     *
     * <p><strong>A duplicate refuses both declarations, not just the later one.</strong> Refusing
     * only the second would leave {@code $coreBanking.id} resolving to the first with nothing said
     * about which of the two the caller meant — the reference would be answered by position after
     * all, which is the thing a label exists to stop. Refusing both puts the declaring operation in
     * the failed set, so every reference to that label cascades loudly through
     * {@link #checkCascade}.</p>
     *
     * @param operations the call's operations, in request order
     * @param failures   the collector every pre-validation refusal is recorded on
     * @return label to the request position of the operation that declared it; empty when none did
     */
    static Map<String, Integer> indexLabels(List<BulkOperation> operations,
            BulkValidationFailures failures) {
        Map<String, Integer> byLabel = new LinkedHashMap<>();
        Set<String> alreadyReported = new HashSet<>();
        for (int i = 0; i < operations.size(); i++) {
            BulkOperation op = operations.get(i);
            String label = op.as();
            if (label == null) {
                continue;
            }
            if (!LABEL_PATTERN.matcher(label).matches()) {
                failures.record(i, op.tool(), new ModelAccessException(
                        "Operation name '" + label + "' is not a legal name. A name must start "
                                + "with a letter or underscore and continue with letters, digits "
                                + "or underscores.",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Rename the operation to something like 'coreBanking', then reference it "
                                + "as '$coreBanking.id'.",
                        null));
                continue;
            }
            Integer prior = byLabel.get(label);
            if (prior == null) {
                byLabel.put(label, i);
                continue;
            }
            if (alreadyReported.add(label)) {
                failures.record(prior, operations.get(prior).tool(),
                        duplicateName(label, prior, i));
            }
            failures.record(i, op.tool(), duplicateName(label, prior, i));
        }
        return byLabel;
    }

    /**
     * The refusal both declarations of a repeated name are given, naming both positions so the
     * caller can find the pair without re-reading its own payload.
     */
    private static ModelAccessException duplicateName(String label, int first, int second) {
        return new ModelAccessException(
                "Operation name '" + label + "' is declared twice — at index " + first
                        + " and again at index " + second + ". A name must be unique within one "
                        + "bulk-mutate call, or a reference to it names two operations.",
                ErrorCode.INVALID_PARAMETER,
                null,
                "Give one of the two operations a different name.",
                null);
    }

    /**
     * Resolves the back-references in parameter values, whether written as a position or as a name.
     *
     * <p>The positional overload for callers with no names to resolve; it is the same walk over an
     * empty label index.</p>
     */
    static Map<String, Object> resolve(
            Map<String, Object> params, int currentIndex,
            Map<Integer, String> createdEntityIds,
            Map<Integer, String> operationTools) {
        return resolve(params, currentIndex, createdEntityIds, operationTools, Map.of());
    }

    /**
     * Resolves the back-references in parameter values.
     *
     * <p>A matched token is a position when it is a run of digits and a declared name otherwise —
     * the two branches of {@link #BACK_REFERENCE_PATTERN} are disjoint, so the token decides and
     * nothing has to be told which form the caller used. A name that no operation declared is
     * refused here rather than resolved to {@code null}: substitution has no null check, so on an
     * optional parameter a null is dropped in silence and the object lands somewhere else
     * entirely.</p>
     *
     * @param labelIndices the label index from {@link #indexLabels}; empty when nothing was named
     */
    static Map<String, Object> resolve(
            Map<String, Object> params, int currentIndex,
            Map<Integer, String> createdEntityIds,
            Map<Integer, String> operationTools,
            Map<String, Integer> labelIndices) {

        Map<String, Object> resolved = new LinkedHashMap<>(params);
        for (Map.Entry<String, Object> entry : resolved.entrySet()) {
            if (entry.getValue() instanceof String strValue) {
                Matcher matcher = BACK_REFERENCE_PATTERN.matcher(strValue);
                if (matcher.matches()) {
                    String token = matcher.group(1);
                    boolean named = !isPosition(token);
                    int refIndex = named
                            ? declaredIndex(token, labelIndices)
                            : parseIndex(token);
                    validate(refIndex, currentIndex, operationTools, named ? token : null);
                    entry.setValue(createdEntityIds.get(refIndex));
                }
            }
        }
        return resolved;
    }

    /** Whether a matched token is a position rather than a name. */
    private static boolean isPosition(String token) {
        return !token.isEmpty() && Character.isDigit(token.charAt(0));
    }

    /**
     * The position of the operation that declared this name, or a refusal.
     *
     * <p>This is the whole point of the named form: a name lives in a sparse namespace, so a
     * misspelling matches no declaration and the only answer available is "no". A position, by
     * contrast, is legal whenever it is below the current index, which is why a mistyped one
     * resolves to a different, legal container instead of refusing.</p>
     */
    private static int declaredIndex(String label, Map<String, Integer> labelIndices) {
        Integer index = labelIndices.get(label);
        if (index == null) {
            throw new ModelAccessException(
                    "Back-reference '$" + label + ".id' names '" + label + "', but no operation in "
                            + "this call declared that name.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Add \"as\": \"" + label + "\" to the operation you meant to reference, or "
                            + "correct the spelling.",
                    null);
        }
        return index;
    }

    /**
     * Validates a positional back-reference. The same four rulings as the full form below, for a
     * reference that was written as a position and therefore has no name to be described by.
     */
    static void validate(int refIndex, int currentIndex,
            Map<Integer, String> operationTools) {
        validate(refIndex, currentIndex, operationTools, null);
    }

    /**
     * Validates a back-reference. Distinguishes self-reference (index ==
     * current), forward-reference (index > current), and out-of-range (index <
     * 0 or unknown) so agents reading the error get an actionable hint instead
     * of one uniform "future operation" message.
     *
     * <p>The four rulings are the same whichever way the reference was written — a name is
     * backward-only and create-tool-only exactly as a position is — but the <em>wording</em> is
     * not. A caller who wrote {@code $coreBanking.id} never counted operations, so an
     * index-shaped message tells them nothing they can act on, and the {@code $(N-1).id}
     * suggestion in particular would point them at a form they deliberately did not use. So every
     * message names the reference the way the caller wrote it, and the positional suggestion is
     * offered only to a caller who is already counting.</p>
     *
     * @param label the declared name the reference was written with, or null when it was written
     *              as a position
     */
    static void validate(int refIndex, int currentIndex,
            Map<Integer, String> operationTools, String label) {
        String shown = label != null ? label : String.valueOf(refIndex);
        if (refIndex == currentIndex) {
            String suggestion = (label == null && refIndex > 0)
                    ? " Did you mean '$" + (refIndex - 1) + ".id' (the operation immediately before this one)?"
                    : "";
            throw new ModelAccessException(
                    "Back-reference '$" + shown + ".id' references the current operation itself "
                            + "(index " + currentIndex + "). Back-references must point to a previous operation."
                            + suggestion,
                    ErrorCode.INVALID_PARAMETER);
        }
        if (refIndex > currentIndex) {
            throw new ModelAccessException(
                    "Back-reference '$" + shown + ".id' references a future operation "
                            + "(index " + refIndex + ", current is " + currentIndex + "). "
                            + "Back-references can only point to operations earlier in the batch.",
                    ErrorCode.INVALID_PARAMETER);
        }
        if (refIndex < 0 || !operationTools.containsKey(refIndex)) {
            throw new ModelAccessException(
                    "Invalid back-reference '$" + shown + ".id' — only "
                            + currentIndex + " previous operations available",
                    ErrorCode.INVALID_PARAMETER);
        }
        String refTool = operationTools.get(refIndex);
        if (!CREATE_TOOLS.contains(refTool)) {
            throw new ModelAccessException(
                    "Back-reference '$" + shown + ".id' targets an " + refTool
                            + " operation — only create operations can be referenced",
                    ErrorCode.INVALID_PARAMETER);
        }
    }

    /**
     * Reads the index out of a matched back-reference.
     *
     * <p>The pattern accepts any run of digits, so a value like {@code "$99999999999.id"} matches
     * and then overflows an {@code int}. Left as a bare parse, that throws a
     * {@link NumberFormatException} — not a {@link ModelAccessException} — which escapes the
     * per-operation catch entirely and takes the whole call down as an internal error, discarding
     * every failure collected so far. The caller of a call that reports all its failures at once
     * would get less back than it did when the call stopped at the first one.</p>
     *
     * <p>So the parse reports its own bad input the way every other back-reference refusal does:
     * as this operation's failure, leaving the rest of the report intact. The per-operation catch
     * is deliberately NOT widened to absorb runtime exceptions — that would mask real defects.</p>
     */
    private static int parseIndex(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new ModelAccessException(
                    "Back-reference '$" + digits + ".id' names an operation index that is too "
                            + "large to be an operation in this call.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Reference an earlier operation by its 0-based position in this call's "
                            + "operations array, or give that operation as: \"name\" and reference "
                            + "it as $name.id.",
                    null);
        }
    }

    /**
     * Checks whether any positional back-reference in the params targets a failed operation. The
     * same walk as the full form below over an empty label index.
     */
    static String checkCascade(Map<String, Object> params, Set<Integer> failedIndices) {
        return checkCascade(params, failedIndices, Map.of());
    }

    /**
     * Checks if any back-reference in the params targets a failed operation.
     * Returns an error message if a cascade failure is detected, null otherwise.
     *
     * <p>Reads the same pattern {@link #resolve} does, so a named reference is as visible here as a
     * positional one — the co-extensive blind spots the class javadoc describes are what makes a
     * dropped reference loud rather than silent, and a named form seen by only one of the two would
     * have re-opened exactly that hole.</p>
     *
     * <p>A name no operation declared is left to {@code resolve}, which refuses it outright. That is
     * a louder answer than a cascade, not a quieter one: there is nothing to cascade <em>from</em>
     * when no operation claimed the name.</p>
     *
     * @param labelIndices the label index from {@link #indexLabels}; empty when nothing was named
     */
    static String checkCascade(Map<String, Object> params, Set<Integer> failedIndices,
            Map<String, Integer> labelIndices) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() instanceof String strValue) {
                Matcher matcher = BACK_REFERENCE_PATTERN.matcher(strValue);
                if (matcher.matches()) {
                    String token = matcher.group(1);
                    boolean named = !isPosition(token);
                    // Not a ternary: mixing Integer and int in one conditional unboxes the
                    // Integer, so an undeclared name would throw a NullPointerException here
                    // instead of being left to resolve's refusal.
                    Integer refIndex;
                    if (named) {
                        refIndex = labelIndices.get(token);
                    } else {
                        refIndex = parseIndex(token);
                    }
                    if (refIndex != null && failedIndices.contains(refIndex)) {
                        // A position is shown re-rendered from the parsed index, exactly as it
                        // always was, so a written "$00.id" keeps reporting itself as "$0.id"; a
                        // name is shown as the caller wrote it, because that is the only spelling
                        // they can search their own payload for.
                        String shown = named ? token : String.valueOf(refIndex);
                        return "Back-reference $" + shown
                                + ".id unavailable — operation " + refIndex + " failed";
                    }
                }
            }
        }
        return null;
    }
}
