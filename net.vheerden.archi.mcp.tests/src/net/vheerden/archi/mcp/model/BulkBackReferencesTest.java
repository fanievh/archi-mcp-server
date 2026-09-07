package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;

/**
 * Direct headless cover for the back-reference cluster.
 *
 * <p>These four refusals and the cascade sentence were reachable only through {@code executeBulk}
 * while they lived on the accessor facade, whose own pins run in the display lane — so the exact
 * wording an agent reads was asserted nowhere that runs by default. Moving the cluster to a
 * dependency-light collaborator is what makes them assertable at all, and that is the second reason
 * the move was worth making, independent of the lines it returned.</p>
 *
 * <p>The last two pins are the ones that matter most. They assert the <em>blind spots</em> — a
 * partial match and a nested value are invisible to {@code resolve} and to {@code checkCascade}
 * alike. That symmetry is a safety property, not an accident: a reference the cascade check cannot
 * see is one that is never substituted either, so it survives as literal text and fails honestly.
 * Widening one side alone would convert a loud failure into a silent one.</p>
 */
public class BulkBackReferencesTest {

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<Integer, String> tools(String... byIndex) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < byIndex.length; i++) {
            m.put(i, byIndex[i]);
        }
        return m;
    }

    private static ModelAccessException expectRefusal(Runnable body) {
        try {
            body.run();
            fail("expected a refusal, none was thrown");
            throw new AssertionError("unreachable");
        } catch (ModelAccessException e) {
            return e;
        }
    }

    // ---- resolve ----

    @Test
    public void shouldSubstituteTheCreatedId_whenTheReferenceNamesAnEarlierCreate() {
        Map<Integer, String> created = new LinkedHashMap<>();
        created.put(0, "id-alpha");

        Map<String, Object> resolved = BulkBackReferences.resolve(
                params("elementId", "$0.id", "viewId", "view-1"), 1,
                created, tools("create-element", "add-to-view"));

        assertEquals("id-alpha", resolved.get("elementId"));
        assertEquals("view-1", resolved.get("viewId"));
    }

    @Test
    public void shouldNotMutateTheCallersMap_whenItSubstitutes() {
        Map<Integer, String> created = new LinkedHashMap<>();
        created.put(0, "id-alpha");
        Map<String, Object> original = params("elementId", "$0.id");

        BulkBackReferences.resolve(original, 1, created, tools("create-element", "add-to-view"));

        assertEquals("the caller's params must be left as they were supplied",
                "$0.id", original.get("elementId"));
    }

    @Test
    public void shouldLeaveAPartialMatchAlone_becauseTheWholeValueMustBeTheReference() {
        Map<Integer, String> created = new LinkedHashMap<>();
        created.put(0, "id-alpha");

        Map<String, Object> resolved = BulkBackReferences.resolve(
                params("name", "grp-$0.id"), 1, created,
                tools("create-element", "add-group-to-view"));

        assertEquals("grp-$0.id", resolved.get("name"));
    }

    @Test
    public void shouldLeaveANestedReferenceAlone_becauseOnlyTopLevelStringsAreWalked() {
        Map<Integer, String> created = new LinkedHashMap<>();
        created.put(0, "id-alpha");

        Map<String, Object> resolved = BulkBackReferences.resolve(
                params("ids", List.of("$0.id")), 1, created,
                tools("create-element", "add-to-view"));

        assertEquals(List.of("$0.id"), resolved.get("ids"));
    }

    // ---- validate: the four refusals ----

    @Test
    public void shouldRefuseASelfReference_andNameThePrecedingOperation() {
        ModelAccessException e = expectRefusal(
                () -> BulkBackReferences.validate(2, 2, tools("create-element",
                        "create-element", "add-to-view")));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().contains(
                "references the current operation itself"));
        assertTrue("a self-reference above index 0 must name the operation before it: "
                + e.getMessage(),
                e.getMessage().contains("Did you mean '$1.id'"));
    }

    @Test
    public void shouldRefuseASelfReferenceAtIndexZero_withoutInventingAPrecedingOperation() {
        ModelAccessException e = expectRefusal(
                () -> BulkBackReferences.validate(0, 0, tools("create-element")));

        assertTrue(e.getMessage(), e.getMessage().contains(
                "references the current operation itself"));
        assertTrue("there is no operation before index 0 to suggest: " + e.getMessage(),
                !e.getMessage().contains("Did you mean"));
    }

    @Test
    public void shouldRefuseAForwardReference_andNameBothIndices() {
        ModelAccessException e = expectRefusal(
                () -> BulkBackReferences.validate(3, 1, tools("create-element",
                        "add-to-view", "create-element", "create-element")));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().contains("references a future operation"));
        assertTrue(e.getMessage(), e.getMessage().contains("index 3, current is 1"));
    }

    @Test
    public void shouldRefuseAnIndexNoOperationOccupies() {
        ModelAccessException e = expectRefusal(
                () -> BulkBackReferences.validate(-1, 2, tools("create-element",
                        "create-element", "add-to-view")));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().startsWith("Invalid back-reference '$-1.id'"));
    }

    @Test
    public void shouldRefuseAReferenceToAToolThatCreatesNothing() {
        ModelAccessException e = expectRefusal(
                () -> BulkBackReferences.validate(0, 1, tools("update-element", "add-to-view")));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().contains(
                "targets an update-element operation — only create operations can be referenced"));
    }

    @Test
    public void shouldAcceptEveryCreateTool_asAReferenceTarget() {
        List<String> createTools = List.of("create-element", "create-relationship", "create-view",
                "add-to-view", "add-connection-to-view", "add-group-to-view", "add-note-to-view");
        for (String tool : createTools) {
            BulkBackReferences.validate(0, 1, tools(tool, "add-to-view"));
        }
    }

    // ---- checkCascade ----

    @Test
    public void shouldNameTheFailedOperation_whenAReferencePointsAtIt() {
        String message = BulkBackReferences.checkCascade(
                params("parentViewObjectId", "$0.id"), Set.of(0));

        assertEquals("Back-reference $0.id unavailable — operation 0 failed", message);
    }

    @Test
    public void shouldStaySilent_whenTheReferencedOperationDidNotFail() {
        assertNull(BulkBackReferences.checkCascade(
                params("parentViewObjectId", "$0.id"), Set.of(1)));
    }

    @Test
    public void shouldStaySilent_whenThereIsNoReferenceAtAll() {
        assertNull(BulkBackReferences.checkCascade(
                params("parentViewObjectId", "grp-1"), Set.of(0)));
    }

    /**
     * The safety property. A partial match is invisible to {@code checkCascade} — and that is
     * correct only because it is equally invisible to {@code resolve}, which therefore never
     * substitutes it. The value survives as literal text and the operation fails on its own terms.
     */
    @Test
    public void shouldBeBlindToAPartialMatch_exactlyAsResolveIs() {
        Map<String, Object> p = params("name", "grp-$0.id");

        assertNull("the cascade check must not see what resolve cannot substitute",
                BulkBackReferences.checkCascade(p, Set.of(0)));

        Map<Integer, String> created = new LinkedHashMap<>();
        assertEquals("resolve must leave it as literal text, so it fails honestly downstream",
                "grp-$0.id",
                BulkBackReferences.resolve(p, 1, created,
                        tools("create-element", "add-group-to-view")).get("name"));
    }

    @Test
    public void shouldBeBlindToANestedReference_exactlyAsResolveIs() {
        Map<String, Object> p = params("ids", List.of("$0.id"));

        assertNull("the cascade check must not see what resolve cannot substitute",
                BulkBackReferences.checkCascade(p, Set.of(0)));

        Map<Integer, String> created = new LinkedHashMap<>();
        assertEquals("resolve must leave it untouched, so it fails honestly downstream",
                List.of("$0.id"),
                BulkBackReferences.resolve(p, 1, created,
                        tools("create-element", "add-to-view")).get("ids"));
    }

    // ---- named references: declaring a label ----

    private static BulkOperation op(String tool) {
        return new BulkOperation(tool, new LinkedHashMap<>());
    }

    private static BulkOperation op(String tool, String label) {
        return new BulkOperation(tool, new LinkedHashMap<>(), label);
    }

    /**
     * The non-degenerate shape, and the reason this class needed a new fixture rather than another
     * copy of an existing one.
     *
     * <p>The declaration sits at index 2 with an operation that creates nothing between it and the
     * reference at index 5, and a <em>second</em> labelled create sits at index 4. So the pin
     * discriminates three framings that coincide at index 0: the raw request position, the position
     * among creates only, and "the create immediately before this one". A reference to {@code $0.id}
     * — which roughly two thirds of the existing corpus uses — is green under a mutation that breaks
     * every one of them.</p>
     */
    @Test
    public void shouldSubstituteTheDeclaringOperationsId_whenAReferenceNamesItByLabel() {
        List<BulkOperation> operations = List.of(
                op("update-view-object"),                // 0 — creates nothing
                op("update-element"),                    // 1 — creates nothing
                op("add-group-to-view", "coreBanking"),  // 2 — the declaration
                op("update-view-object"),                // 3 — creates nothing
                op("add-group-to-view", "paymentsZone"), // 4 — a nearer, wrong answer
                op("add-to-view"));                      // 5 — the reference
        BulkValidationFailures failures = new BulkValidationFailures();
        Map<String, Integer> labels = BulkBackReferences.indexLabels(operations, failures);

        Map<Integer, String> created = new LinkedHashMap<>();
        created.put(2, "id-core");
        created.put(4, "id-payments");

        Map<String, Object> resolved = BulkBackReferences.resolve(
                params("parentViewObjectId", "$coreBanking.id"), 5, created,
                tools("update-view-object", "update-element", "add-group-to-view",
                        "update-view-object", "add-group-to-view", "add-to-view"),
                labels);

        assertTrue("a well-formed label set must not be refused: " + failures.rows(),
                failures.isEmpty());
        assertEquals("the label must name the operation that declared it, not the nearest create",
                "id-core", resolved.get("parentViewObjectId"));
    }

    /**
     * A label lives in a sparse namespace, so a misspelling matches nothing and can only refuse.
     * That is the whole point of the form: {@code resolve} substitutes with no null check, so an
     * unknown label that returned {@code null} here would be dropped in silence on an optional
     * parameter and the object would land somewhere else entirely.
     */
    @Test
    public void shouldRefuseAnUndeclaredLabel_ratherThanResolveItToNull() {
        Map<String, Object> supplied = params("parentViewObjectId", "$coreBankng.id");

        ModelAccessException e = expectRefusal(() -> BulkBackReferences.resolve(
                supplied, 2, new LinkedHashMap<>(),
                tools("add-group-to-view", "add-group-to-view", "add-to-view"),
                Map.of("coreBanking", 0)));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue("the refusal must name the label the caller wrote: " + e.getMessage(),
                e.getMessage().contains("coreBankng"));
        assertTrue("and must say that nothing declared it: " + e.getMessage(),
                e.getMessage().contains("no operation in this call declared"));
        assertEquals("it must never be substituted away", "$coreBankng.id",
                supplied.get("parentViewObjectId"));
    }

    /**
     * Spelt out literally rather than derived from the production pattern. A grammar test that
     * re-derives its expectations from the constant it is testing uses the same value on both
     * sides of the comparison and is blind to that value being wrong.
     */
    @Test
    public void shouldRefuseALabelTheGrammarDoesNotAccept() {
        for (String rejected : List.of("1core", "core-banking", "core banking", "core.banking",
                "", "core$", "$core", "core!", "café")) {
            BulkValidationFailures failures = new BulkValidationFailures();
            BulkBackReferences.indexLabels(List.of(op("add-group-to-view", rejected)), failures);

            assertEquals("'" + rejected + "' must be refused as a label", 1, failures.rows().size());
            BulkOperationFailure row = failures.rows().get(0);
            assertEquals(ErrorCode.INVALID_PARAMETER.name(), row.errorCode());
            assertTrue("the refusal must quote the value: " + row.message(),
                    row.message().contains("'" + rejected + "'"));
            assertTrue("and state the grammar: " + row.message(),
                    row.message().contains("letter or underscore"));
        }
    }

    /** The accepting half, spelt out for the same reason. */
    @Test
    public void shouldAcceptTheLabelGrammarSpeltOutRatherThanDerived() {
        for (String accepted : List.of("coreBanking", "_private", "zone1", "A", "_",
                "outer_zone_2", "PAYMENTS")) {
            BulkValidationFailures failures = new BulkValidationFailures();
            Map<String, Integer> labels = BulkBackReferences.indexLabels(
                    List.of(op("add-group-to-view", accepted)), failures);

            assertTrue("'" + accepted + "' must be accepted: " + failures.rows(),
                    failures.isEmpty());
            assertEquals(Integer.valueOf(0), labels.get(accepted));
        }
    }

    /**
     * Both declarations are refused, not just the later one.
     *
     * <p>Refusing only the second would leave a reference resolving to the first with nothing said
     * about which of the two the caller meant — the reference would be answered, quietly, by
     * position after all. Refusing both puts the declaring operation in the failed set, so any
     * reference to the label cascades loudly instead.</p>
     */
    @Test
    public void shouldRefuseADuplicateLabel_onBothDeclarationsAndNamingBothIndices() {
        BulkValidationFailures failures = new BulkValidationFailures();
        BulkBackReferences.indexLabels(List.of(
                op("add-group-to-view", "coreBanking"),
                op("add-to-view"),
                op("add-group-to-view", "coreBanking")), failures);

        assertEquals("both declarations must be refused", 2, failures.rows().size());
        assertEquals(Set.of(0, 2), failures.failedIndices());
        for (BulkOperationFailure row : failures.rows()) {
            assertEquals(ErrorCode.INVALID_PARAMETER.name(), row.errorCode());
            assertTrue("the refusal must name the label: " + row.message(),
                    row.message().contains("coreBanking"));
            assertTrue("and both indices, or the caller cannot find the pair: " + row.message(),
                    row.message().contains("index 0") && row.message().contains("index 2"));
        }
    }

    /** A third declaration reports itself once, without re-reporting the first a second time. */
    @Test
    public void shouldReportEachDuplicateDeclarationExactlyOnce() {
        BulkValidationFailures failures = new BulkValidationFailures();
        BulkBackReferences.indexLabels(List.of(
                op("add-group-to-view", "coreBanking"),
                op("add-group-to-view", "coreBanking"),
                op("add-group-to-view", "coreBanking")), failures);

        assertEquals("three declarations, three rows — the first must not be reported twice",
                3, failures.rows().size());
        assertEquals(Set.of(0, 1, 2), failures.failedIndices());
    }

    /** An operation with no label costs nothing and contributes nothing to the namespace. */
    @Test
    public void shouldIndexNothing_whenNoOperationCarriesALabel() {
        BulkValidationFailures failures = new BulkValidationFailures();
        Map<String, Integer> labels = BulkBackReferences.indexLabels(
                List.of(op("create-element"), op("add-to-view")), failures);

        assertTrue(failures.isEmpty());
        assertTrue(labels.isEmpty());
    }

    // ---- named references: the four refusals, phrased by name ----

    /**
     * An index-shaped message about a named reference is a defect. The caller who wrote
     * {@code $coreBanking.id} never counted, so being told about {@code $0.id} tells them nothing
     * they can act on.
     */
    @Test
    public void shouldPhraseTheCreateToolRefusalByName_whenTheReferenceWasNamed() {
        ModelAccessException e = expectRefusal(() -> BulkBackReferences.validate(
                0, 1, tools("update-element", "add-to-view"), "coreBanking"));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().contains("'$coreBanking.id'"));
        assertTrue("the named form must not be described by its index: " + e.getMessage(),
                !e.getMessage().contains("$0.id"));
        assertTrue(e.getMessage(), e.getMessage().contains(
                "only create operations can be referenced"));
    }

    @Test
    public void shouldPhraseTheForwardRefusalByName_whenTheLabelIsDeclaredLater() {
        ModelAccessException e = expectRefusal(() -> BulkBackReferences.validate(
                3, 1, tools("add-group-to-view", "add-to-view", "add-to-view",
                        "add-group-to-view"), "paymentsZone"));

        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().contains("'$paymentsZone.id'"));
        assertTrue("a forward reference must still say it is forward: " + e.getMessage(),
                e.getMessage().contains("references a future operation"));
        assertTrue("the named form must not be described by its index: " + e.getMessage(),
                !e.getMessage().contains("$3.id"));
    }

    @Test
    public void shouldNotSuggestAPositionalNeighbour_whenANamedReferenceIsSelfReferential() {
        ModelAccessException e = expectRefusal(() -> BulkBackReferences.validate(
                2, 2, tools("add-group-to-view", "add-group-to-view", "add-group-to-view"),
                "coreBanking"));

        assertTrue(e.getMessage(), e.getMessage().contains("'$coreBanking.id'"));
        assertTrue("'$1.id' is meaningless to a caller who never counted: " + e.getMessage(),
                !e.getMessage().contains("Did you mean"));
    }

    /**
     * The positional overloads must keep saying exactly what they said. Driven here rather than
     * assumed, because every existing pin in this class calls the shorter arity and would stay
     * green if the longer one drifted.
     */
    @Test
    public void shouldGiveThePositionalOverloadTheSameWordsAsTheFullFormWithNoLabel() {
        Map<Integer, String> tools = tools("update-element", "add-to-view");

        ModelAccessException viaOverload = expectRefusal(
                () -> BulkBackReferences.validate(0, 1, tools));
        ModelAccessException viaFullForm = expectRefusal(
                () -> BulkBackReferences.validate(0, 1, tools, null));

        assertEquals(viaFullForm.getMessage(), viaOverload.getMessage());
        assertTrue(viaOverload.getMessage(), viaOverload.getMessage().contains("'$0.id'"));
    }

    // ---- named references: the cascade check sees exactly what resolve sees ----

    @Test
    public void shouldNameTheLabel_whenTheOperationThatDeclaredItFailed() {
        String message = BulkBackReferences.checkCascade(
                params("parentViewObjectId", "$coreBanking.id"), Set.of(2),
                Map.of("coreBanking", 2));

        assertEquals("Back-reference $coreBanking.id unavailable — operation 2 failed", message);
    }

    /**
     * The safety property, restated for the named form. {@code resolve} substitutes with no null
     * check, so a reference {@code checkCascade} cannot see is one that resolves to {@code null}
     * and is dropped in silence. Widening one side without the other is what this pin exists to
     * catch: give {@code checkCascade} a digits-only pattern of its own and it goes red.
     */
    @Test
    public void shouldSeeALabelledReferenceExactlyAsResolveDoes() {
        Map<String, Object> p = params("parentViewObjectId", "$coreBanking.id");
        Map<String, Integer> labels = Map.of("coreBanking", 0);

        assertEquals("the cascade check must see every reference resolve would substitute",
                "Back-reference $coreBanking.id unavailable — operation 0 failed",
                BulkBackReferences.checkCascade(p, Set.of(0), labels));

        Map<Integer, String> created = new LinkedHashMap<>();
        created.put(0, "id-core");
        assertEquals("and resolve must substitute exactly what the cascade check saw", "id-core",
                BulkBackReferences.resolve(p, 1, created,
                        tools("add-group-to-view", "add-to-view"), labels)
                        .get("parentViewObjectId"));
    }

    /** An undeclared label is not a cascade — it is a refusal, and resolve is the one that makes it. */
    @Test
    public void shouldStaySilentOnAnUndeclaredLabel_becauseResolveRefusesItLouder() {
        assertNull(BulkBackReferences.checkCascade(
                params("parentViewObjectId", "$coreBankng.id"), Set.of(0),
                Map.of("coreBanking", 0)));
    }

    /**
     * The one place the two {@code $} namespaces come near each other. Archi's label-expression
     * grammar puts a brace after the dollar, so the widened pattern cannot reach it — asserted with
     * a label literally called {@code name} so the collision is as sharp as it can be made.
     */
    @Test
    public void shouldLeaveALabelExpressionAlone_becauseItsDollarIsFollowedByABrace() {
        Map<String, Object> p = params("labelExpression", "${name} ${property:evidenceMark}");
        Map<String, Integer> labels = Map.of("name", 0, "property", 1);

        assertNull(BulkBackReferences.checkCascade(p, Set.of(0, 1), labels));
        assertEquals("${name} ${property:evidenceMark}",
                BulkBackReferences.resolve(p, 2, new LinkedHashMap<>(),
                        tools("add-group-to-view", "add-group-to-view",
                                "set-view-label-expression"), labels)
                        .get("labelExpression"));
    }

    /**
     * A label can never be mistaken for an index, because the grammar forbids a leading digit on
     * both sides: {@code indexLabels} refuses to declare {@code 1core}, and even handed a map that
     * contains it the pattern will not match {@code $1core.id} at all. It stays literal text and
     * fails honestly, which is what the blind spots elsewhere in this class also do.
     */
    @Test
    public void shouldLeaveADigitLeadingNameAsLiteralText_soALabelCanNeverLookLikeAnIndex() {
        Map<String, Object> p = params("parentViewObjectId", "$1core.id");
        Map<String, Integer> impossible = Map.of("1core", 1);

        assertNull(BulkBackReferences.checkCascade(p, Set.of(1), impossible));
        assertEquals("$1core.id",
                BulkBackReferences.resolve(p, 2, new LinkedHashMap<>(),
                        tools("add-group-to-view", "add-group-to-view", "add-to-view"),
                        impossible).get("parentViewObjectId"));
    }

    /** Both shapes in one payload: which branch runs depends on the token, so drive both. */
    @Test
    public void shouldResolveAPositionalAndANamedReferenceInTheSameParams() {
        Map<Integer, String> created = new LinkedHashMap<>();
        created.put(0, "id-view");
        created.put(2, "id-core");

        Map<String, Object> resolved = BulkBackReferences.resolve(
                params("viewId", "$0.id", "parentViewObjectId", "$coreBanking.id"), 3, created,
                tools("create-view", "update-element", "add-group-to-view", "add-to-view"),
                Map.of("coreBanking", 2));

        assertEquals("id-view", resolved.get("viewId"));
        assertEquals("id-core", resolved.get("parentViewObjectId"));
    }
}
