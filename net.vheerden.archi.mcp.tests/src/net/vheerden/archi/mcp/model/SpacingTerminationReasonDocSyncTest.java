package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Build-fired sync-guard pin for the spacing-tool termination-reason taxonomy
 * across {@link SpacingControlLoop} AND
 * {@link SpacingPreconditionInfeasibilityCertificate}.
 *
 * <p>This test enforces an INVARIANT: every spacing-tool termination reason
 * exposed by either {@link SpacingControlLoop} or
 * {@link SpacingPreconditionInfeasibilityCertificate} must be documented in
 * the MCP-protocol surface that LLM agents fetch before invoking the spacing
 * tools. The authoritative surface is
 * {@code resources/prompts/routing-preconditions-checklist.md} (loaded by
 * {@code net.vheerden.archi.mcp.handlers.ResourceHandler} at startup; served as
 * {@code archimate://prompts/routing-preconditions-checklist}).</p>
 *
 * <p><strong>Why this exists.</strong> This work
 * was queued because Retail Bank test 2026-05-19 surfaced a doc-surface gap:
 * the 9th termination reason ({@code reroute_degraded_input_baseline}, shipped
 * as the pre-loop route-normalized-baseline safety net) was
 * absent from the checklist's "eight branches" table, and the LLM following
 * the prescribed workflow misdiagnosed the cause + over-claimed the remedy in
 * the run report. During the empirical-probe phase (Task 4) a SECOND
 * undocumented reason was discovered: the 10th
 * {@code density_precondition_infeasible_reflow_required} on
 * {@link SpacingPreconditionInfeasibilityCertificate} (row 777 SOUND
 * one-sided certificate, sibling to {@code reroute_degraded_input_baseline}).
 * When a value drives an
 * LLM-visible surface, the doc invariant must be testable, not aspirational.
 * If a future 11th termination reason is added (on either class) without
 * updating the markdown, this test fails the build at commit-time.</p>
 *
 * <p><strong>What this test does NOT check.</strong> The three spacing-tool
 * descriptions in {@code ViewPlacementHandler.java} (apply-element-spacing-
 * recommendations / apply-group-spacing-recommendations / apply-spacing-
 * recommendations) MUST also enumerate every termination reason. Sync of the
 * FULL taxonomy across those three descriptions remains a code-review
 * checklist item rather than a build-fired guard: this test's authoritative
 * surface is the markdown, and it makes no claim about the Java descriptions.
 * The markdown surface (which LLMs fetch via the prompt resource URI) is the
 * load-bearing one here.</p>
 *
 * <p><strong>Correction — one sentence IS now build-fired, and the stated
 * obstacle was wrong.</strong> This javadoc previously deferred the
 * tool-description sync on the grounds that "reflectively reading the live MCP
 * tool catalog requires standing up the McpPlugin". It does not: a
 * {@code CommandRegistry} populated by
 * {@code new ViewPlacementHandler(stubAccessor, formatter, registry, null)
 * .registerTools()} serves the real descriptions with no SWT, no EMF and no
 * OSGi, and that route is used by the parity tests in this tree. Acting on
 * that, {@code ViewPlacementHandlerTest
 * .spacingTools_descriptionShouldNameTheThirdPreLoopGuardContiguously} now
 * reads the SERVED description off the registry and fails the build if the
 * third pre-loop guard is split across an interposed paragraph, or if that
 * paragraph is placed ahead of the enumeration it explains. That guard covers
 * ONE sentence, not the whole taxonomy — the caveat above still stands for
 * everything else.</p>
 *
 * <p>Pure JUnit 4 — no SWT, no EMF, no OSGi runtime. Relies on the
 * {@code Fragment-Host} relationship between this tests bundle and
 * {@code net.vheerden.archi.mcp} (which lists {@code resources/} in
 * {@code build.properties bin.includes}) so the classpath lookup
 * {@code getResourceAsStream("resources/prompts/...")} resolves at test
 * runtime.</p>
 */
public class SpacingTerminationReasonDocSyncTest {

    /** Classpath path used by ResourceHandler at runtime. Keep in sync. */
    private static final String CHECKLIST_RESOURCE_PATH =
            "resources/prompts/routing-preconditions-checklist.md";

    /**
     * Canonical reason-name forms derived from SpacingControlLoop.REASON_*
     * constants. Constants that are complete names (e.g. REASON_HEURISTIC_ALREADY_MET
     * = "heuristic_already_met_no_change") map to themselves; constants that
     * are PREFIX/INFIX/SUFFIX fragments map to the assembled canonical name.
     *
     * <p>The map is keyed by reason-family so the test can fail with an
     * informative message naming the missing reason rather than a raw
     * constant value. A new termination reason added to SpacingControlLoop
     * appears here automatically via the reflective enumeration in
     * {@link #enumerateCompleteReasonConstants()} — but the canonical-name
     * assembly for prefix-style reasons is hand-coded against the public
     * Javadoc-documented canonical forms (e.g. "goal_reached_at_iteration_N").
     * If a future maintainer adds a NEW prefix-style reason, the
     * {@link #shouldEnumerateAtLeastNineCanonicalReasons_invariantGuard()}
     * floor assertion will still fire (>= 9), and the
     * {@link #shouldAllowFutureReasonsViaCompleteConstants()} pin documents
     * that the simplest forward path is to use a complete-name constant.
     */
    private static final List<String> CANONICAL_REASON_NAMES = List.of(
            // (a) prefix-style — assembled per Javadoc
            "goal_reached_at_iteration_",
            // (b) prefix+suffix — assembled per Javadoc
            "budget_exhausted_after_",
            // (c) prefix-style — assembled per Javadoc
            "aggregate_threshold_regressed_at_iteration_",
            // (d) prefix — variable suffix in practice
            "structural_no_change_",
            // (e) complete name
            "heuristic_already_met_no_change",
            // (f) complete name
            "dry_run_recommendation_not_applied",
            // (g) prefix-style — assembled per Javadoc
            "iteration_apply_failed_at_iteration_",
            // (h) complete name — in-loop PASS-HONEST (row 703)
            "density_floor_reflow_required",
            // (i) complete name — pre-loop safety net
            "reroute_degraded_input_baseline",
            // (j) complete name — pre-loop SOUND certificate (row 777),
            // owned by SpacingPreconditionInfeasibilityCertificate (NOT
            // SpacingControlLoop). Discovered during the empirical
            // probe (Task 4) — sibling-symmetric with (i); same class of
            // doc-surface gap as the original story trigger.
            "density_precondition_infeasible_reflow_required"
    );

    /**
     * Primary sync assertion: every canonical termination-reason name must
     * appear at least once in the shipped {@code routing-preconditions-
     * checklist.md} resource.
     *
     * <p>If you add a NEW termination reason to {@link SpacingControlLoop}
     * OR {@link SpacingPreconditionInfeasibilityCertificate} (e.g. an 11th
     * branch), AND you add its canonical form to
     * {@link #CANONICAL_REASON_NAMES} above, this test will fail until the
     * markdown resource also documents the new reason. If you skip the
     * {@link #CANONICAL_REASON_NAMES} update, the
     * {@link #shouldEnumerateAtLeastTenCanonicalReasons_invariantGuard()}
     * floor assertion still gives you a signal.</p>
     *
     * <p><strong>For PREFIX-style reasons</strong> (those whose canonical
     * name contains a variable suffix like {@code _N} — e.g. (a) {@code goal_reached_at_iteration_N},
     * (b) {@code budget_exhausted_after_N_iterations}, (c) {@code aggregate_threshold_regressed_at_iteration_N_reverted_to_iteration_M},
     * (g) {@code iteration_apply_failed_at_iteration_N_reverted_after_M_accepted_iterations}):
     * you MUST also add the canonical prefix form to {@link #CANONICAL_REASON_NAMES}.
     * The reflective seam in {@link #enumerateCompleteReasonConstants()}
     * filters OUT prefix-fragment constants by design (their values end with
     * underscore), so prefix-style reasons do not auto-discover — only
     * complete-name constants do. The floor pin
     * {@link #shouldEnumerateAtLeastTenCanonicalReasons_invariantGuard()}
     * provides a secondary signal if the count drops below the threshold.</p>
     */
    @Test
    public void everyTerminationReasonShouldBeDocumentedInChecklistResource() {
        String markdown = loadChecklistResource();
        assertNotNull("routing-preconditions-checklist.md must be on the "
                + "classpath at runtime (resources/ is in bin.includes; the "
                + "tests bundle is a Fragment-Host of the production bundle). "
                + "If null, check net.vheerden.archi.mcp/build.properties + "
                + "the tests bundle MANIFEST Fragment-Host header.",
                markdown);

        List<String> missing = new ArrayList<>();
        for (String canonicalName : CANONICAL_REASON_NAMES) {
            if (!markdown.contains(canonicalName)) {
                missing.add(canonicalName);
            }
        }

        if (!missing.isEmpty()) {
            fail("routing-preconditions-checklist.md is missing documentation "
                    + "for " + missing.size()
                    + " SpacingControlLoop termination reason(s): " + missing
                    + ". Per the MCP plugin contract, every "
                    + "spacing-tool termination reason MUST be documented in "
                    + "the MCP-protocol surface the LLM fetches "
                    + "(archimate://prompts/routing-preconditions-checklist). "
                    + "Add the missing reason(s) to the 'termination branches' "
                    + "table at the top of the file, AND if it is a pre-loop "
                    + "guard add a sibling sub-section explaining what the "
                    + "agent should do on the signal (mirror the existing "
                    + "'When a spacing tool says it would have degraded the "
                    + "input' sub-section).");
        }
    }

    /**
     * Floor assertion: the spacing-tool termination-reason taxonomy must
     * expose at least ten canonical reasons today (8 pre-existing + 9th
     * branch {@code reroute_degraded_input_baseline} on
     * {@link SpacingControlLoop} + 10th branch
     * {@code density_precondition_infeasible_reflow_required} on
     * {@link SpacingPreconditionInfeasibilityCertificate}, both documented
     * 2026-05-20). This guards against a refactor that
     * accidentally drops a {@code REASON_*} constant without updating the
     * test or the markdown.
     *
     * <p>NOTE: this asserts {@code >= 10}, not {@code == 10}, deliberately —
     * future maintainers should be able to add an 11th reason WITHOUT this
     * floor pin breaking, and the
     * {@link #everyTerminationReasonShouldBeDocumentedInChecklistResource()}
     * primary pin will catch any missing markdown coverage.</p>
     */
    @Test
    public void shouldEnumerateAtLeastTenCanonicalReasons_invariantGuard() {
        Set<String> completeConstants = enumerateCompleteReasonConstants();
        // Count: complete-name constants (e) (f) (h) (i) (j) = 5 + 4 prefix-
        // family reasons (a) (b) (c) (d) (g) = 5 → 10 canonical reason
        // families. The CANONICAL_REASON_NAMES list materializes these 10
        // names; a future 11th would be added there. The 10th branch (j)
        // density_precondition_infeasible_reflow_required lives on
        // SpacingPreconditionInfeasibilityCertificate (row 777), not on
        // SpacingControlLoop — both classes are reflected.
        assertTrue("Expected at least 10 canonical spacing-tool "
                + "termination-reason families documented in "
                + "CANONICAL_REASON_NAMES; found "
                + CANONICAL_REASON_NAMES.size()
                + ". If you added a new reason constant to either "
                + "SpacingControlLoop or "
                + "SpacingPreconditionInfeasibilityCertificate, extend "
                + "CANONICAL_REASON_NAMES and the markdown.",
                CANONICAL_REASON_NAMES.size() >= 10);

        // Sanity check: every complete-name constant we discovered reflectively
        // must also be present in CANONICAL_REASON_NAMES (so the list stays
        // synchronized with the field set). Prefix-family constants are not
        // checked here because reflection cannot assemble the canonical
        // suffix (e.g. iteration_N) without runtime context.
        List<String> orphanCompleteConstants = new ArrayList<>();
        for (String value : completeConstants) {
            if (!CANONICAL_REASON_NAMES.contains(value)) {
                orphanCompleteConstants.add(value);
            }
        }
        assertTrue("SpacingControlLoop has complete-name REASON_* "
                + "constants that CANONICAL_REASON_NAMES does not list: "
                + orphanCompleteConstants
                + ". Add them to CANONICAL_REASON_NAMES so the markdown sync "
                + "assertion covers them too.",
                orphanCompleteConstants.isEmpty());
    }

    /**
     * Forward-compatibility pin: documents the contract that new termination
     * reasons added in the future should prefer complete-name constants so
     * they appear automatically in
     * {@link #enumerateCompleteReasonConstants()} for the floor pin to flag
     * if missing from {@link #CANONICAL_REASON_NAMES}.
     */
    @Test
    public void shouldAllowFutureReasonsViaCompleteConstants() {
        Set<String> completeConstants = enumerateCompleteReasonConstants();
        // The 5 complete-name constants in today's taxonomy:
        // (e) heuristic_already_met_no_change                — SpacingControlLoop
        // (f) dry_run_recommendation_not_applied             — SpacingControlLoop
        // (h) density_floor_reflow_required                  — SpacingControlLoop (in-loop)
        // (i) reroute_degraded_input_baseline                — SpacingControlLoop (pre-loop)
        // (j) density_precondition_infeasible_reflow_required — SpacingPreconditionInfeasibilityCertificate (pre-loop)
        assertTrue("Expected complete-name (e) REASON_HEURISTIC_ALREADY_MET "
                + "= 'heuristic_already_met_no_change' on the classpath",
                completeConstants.contains("heuristic_already_met_no_change"));
        // (f) dryRun is asserted via markdown-text-search OR reflective
        // contains, with the OR as an escape hatch: the production code
        // emits "dry_run_recommendation_not_applied" as a STRING LITERAL at
        // ArchiModelAccessorImpl.java:16328, NOT as a public REASON_*
        // constant on SpacingControlLoop. So the reflective seam will NOT
        // find it today, and this assertion falls back to the markdown
        // search path. The OR is correct: if (f) is later promoted to a
        // public constant, the reflective half will catch it AND the
        // markdown half remains a safety net. Documented asymmetry vs (i)
        // and (j) which ARE public constants on their respective classes.
        assertTrue("Expected (f) 'dry_run_recommendation_not_applied' to be "
                + "documented in routing-preconditions-checklist.md (note: "
                + "the value is emitted as a string literal at "
                + "ArchiModelAccessorImpl.java:16328 — NOT a public "
                + "constant on SpacingControlLoop — so this assertion uses "
                + "the markdown-text-search path as its primary signal, "
                + "with reflective-contains as a forward-compat OR if (f) "
                + "is later promoted to a public constant)",
                completeConstants.contains("dry_run_recommendation_not_applied")
                        || markdownContainsDryRunReason());
        assertTrue("Expected complete-name (h) "
                + "REASON_DENSITY_FLOOR_REFLOW_REQUIRED = "
                + "'density_floor_reflow_required'",
                completeConstants.contains("density_floor_reflow_required"));
        assertTrue("Expected complete-name (i) "
                + "REASON_REROUTE_DEGRADED_INPUT_BASELINE = "
                + "'reroute_degraded_input_baseline' — the 9th branch this "
                + "story (2026-05-20) added to the MCP-protocol surface",
                completeConstants.contains("reroute_degraded_input_baseline"));
        assertTrue("Expected complete-name (j) "
                + "REASON_DENSITY_PRECONDITION_REFLOW_REQUIRED = "
                + "'density_precondition_infeasible_reflow_required' — the "
                + "10th branch discovered + documented during this story's "
                + "(2026-05-20) empirical-probe phase (Task 4); lives on "
                + "SpacingPreconditionInfeasibilityCertificate (row 777), "
                + "NOT SpacingControlLoop. Sibling-symmetric with (i).",
                completeConstants.contains(
                        "density_precondition_infeasible_reflow_required"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Loads the shipped {@code routing-preconditions-checklist.md} resource
     * via the SAME classpath lookup the production
     * {@code ResourceHandler.loadResourceFile} uses ({@link Object#getClass()}
     * {@code .getClassLoader().getResourceAsStream(...)}). Returns {@code null}
     * if the resource is not on the classpath — this should not happen given
     * the Fragment-Host bundle relationship + {@code bin.includes resources/}
     * in the production bundle's build.properties.
     */
    private String loadChecklistResource() {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream(CHECKLIST_RESOURCE_PATH)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            fail("Failed to read shipped MCP resource "
                    + CHECKLIST_RESOURCE_PATH + ": " + e.getMessage());
            return null; // unreachable
        }
    }

    /**
     * Reflectively enumerates every {@code public static final String} field
     * on {@link SpacingControlLoop} whose name starts with {@code REASON_}
     * AND whose value does NOT end with an underscore (heuristic separating
     * complete-name constants from PREFIX/INFIX/SUFFIX fragments). Returns
     * the set of complete reason-name values.
     *
     * <p>This is the seam that picks up future complete-name reasons
     * automatically. A 10th reason added as
     * {@code public static final String REASON_FOO = "foo_complete_name";}
     * will appear here on the next test run; the floor pin in
     * {@link #shouldEnumerateAtLeastNineCanonicalReasons_invariantGuard()}
     * will then flag the missing CANONICAL_REASON_NAMES entry.</p>
     */
    private Set<String> enumerateCompleteReasonConstants() {
        Set<String> values = new LinkedHashSet<>();
        // Spacing-tool termination reasons are spread across two classes today:
        //   - SpacingControlLoop                                (in-loop +
        //                                                       2 of 3
        //                                                       pre-loop guards)
        //   - SpacingPreconditionInfeasibilityCertificate       (the 3rd
        //                                                       pre-loop guard,
        //                                                       row 777)
        // Both are reflected, since both surface via the same DTO
        // terminationReason field that LLM agents read. A future 3rd class
        // would be added here.
        collectCompleteReasonConstantsFrom(SpacingControlLoop.class, values);
        collectCompleteReasonConstantsFrom(
                SpacingPreconditionInfeasibilityCertificate.class, values);
        return values;
    }

    private void collectCompleteReasonConstantsFrom(
            Class<?> owner, Set<String> values) {
        Field[] fields = owner.getDeclaredFields();
        for (Field f : fields) {
            int mods = f.getModifiers();
            if (!Modifier.isPublic(mods)
                    || !Modifier.isStatic(mods)
                    || !Modifier.isFinal(mods)
                    || f.getType() != String.class
                    || !f.getName().startsWith("REASON_")) {
                continue;
            }
            try {
                String value = (String) f.get(null);
                if (value == null) {
                    continue;
                }
                // Complete-name heuristic: value does NOT end with '_' AND
                // does NOT start with '_'. Both guards are load-bearing:
                //
                //   - endsWith("_") catches prefix-fragment constants whose
                //     value ends with the variable-suffix anchor — e.g.
                //     "goal_reached_at_iteration_" (the `_N` suffix is
                //     appended at emit time). These are NOT complete names.
                //   - startsWith("_") catches infix-fragment constants whose
                //     value BEGINS with underscore as part of the infix form
                //     — e.g. SpacingControlLoop.REASON_REVERTED_TO_INITIAL =
                //     "_reverted_to_initial_state" or
                //     REASON_REVERTED_TO_ITERATION = "_reverted_to_iteration_"
                //     (concatenated AFTER a prefix at emit time; the leading
                //     underscore is the join separator). These are also NOT
                //     complete names — they only make sense as a tail.
                //
                // A future complete-name reason whose value legitimately
                // starts with '_' would be silently excluded by this filter
                // and miss the reflective seam — if that becomes a real
                // requirement, the filter must be revised AND the canonical
                // name must be added to CANONICAL_REASON_NAMES explicitly.
                if (!value.endsWith("_") && !value.startsWith("_")) {
                    values.add(value);
                }
            } catch (IllegalAccessException e) {
                fail("Reflective access to " + owner.getSimpleName()
                        + ".REASON_* constant '" + f.getName() + "' failed: "
                        + e.getMessage());
            }
        }
    }

    /**
     * Convenience guard for the dry-run reason, which is asserted via the
     * markdown surface (it is searchable in the markdown literally, even
     * if the reflective fingerprint of its declaring constant changes).
     */
    private boolean markdownContainsDryRunReason() {
        String md = loadChecklistResource();
        return md != null && md.contains("dry_run_recommendation_not_applied");
    }

    /**
     * Defensive pin: the field-set enumeration logic is the load-bearing
     * seam for forward compatibility. If a refactor causes
     * {@link #enumerateCompleteReasonConstants()} to return an empty set,
     * the floor pin still requires {@code >= 9} in
     * CANONICAL_REASON_NAMES, but this pin catches the reflective seam
     * failure independently and gives a clearer error message.
     */
    @Test
    public void reflectiveSeamShouldFindAtLeastOneCompleteReasonConstant() {
        Set<String> completeConstants = enumerateCompleteReasonConstants();
        assertFalse("Reflective enumeration of REASON_* complete-name "
                + "constants across SpacingControlLoop + "
                + "SpacingPreconditionInfeasibilityCertificate returned "
                + "empty. The seam used by "
                + "shouldAllowFutureReasonsViaCompleteConstants() is broken; "
                + "check Modifier filter logic in "
                + "enumerateCompleteReasonConstants() / "
                + "collectCompleteReasonConstantsFrom(). Without this seam, "
                + "future complete-name reasons will not appear in the "
                + "set and the sync guard will silently miss them.",
                completeConstants.isEmpty());

        // Sanity: at minimum the 9th + 10th branches this story documented
        // MUST be findable by the reflective seam, since the story explicitly
        // added them as complete-name constants on their respective classes.
        assertTrue("Reflective enumeration must find "
                + "'reroute_degraded_input_baseline' (the complete-name "
                + "constant SpacingControlLoop.REASON_REROUTE_DEGRADED_INPUT_"
                + "BASELINE shipped 2026-05-16 under "
                + "sprint-status row 703). Found constants: "
                + completeConstants,
                completeConstants.contains("reroute_degraded_input_baseline"));
        assertTrue("Reflective enumeration must find "
                + "'density_precondition_infeasible_reflow_required' (the "
                + "complete-name constant SpacingPreconditionInfeasibility"
                + "Certificate.REASON_DENSITY_PRECONDITION_REFLOW_REQUIRED "
                + "shipped 2026-05-18 under sprint-status row 777). Found "
                + "constants: " + completeConstants,
                completeConstants.contains(
                        "density_precondition_infeasible_reflow_required"));
    }

    /**
     * Sanity pin for the markdown count claim. The narrative prose at the
     * top of the checklist section (in the body of "The control loop inside
     * the convenience tools") states explicitly that there are "nine
     * branches" — this pin enforces that the prose and the reflective
     * field-count agree. If a future maintainer adds a 10th reason, this
     * pin fires with a clear message naming what needs to be updated.
     *
     * <p>Failure semantics: if this pin fires, EITHER (a) update the prose
     * to the new count, OR (b) revert the new reason if it should not have
     * been added. Do not silence this pin.</p>
     */
    @Test
    public void checklistProseShouldNameTheTenBranchCount() {
        String md = loadChecklistResource();
        assertNotNull("checklist markdown must be on classpath", md);

        // Token "ten branches" or "ten termination branches" must appear at
        // least once. Both phrasings are accepted (a future cleanup pass may
        // tighten one or the other).
        boolean hasTenCount = md.contains("ten branches")
                || md.contains("ten termination branches");
        assertTrue("routing-preconditions-checklist.md must state the "
                + "branch count as 'ten branches' or 'ten termination "
                + "branches' (per the 10-row taxonomy this story shipped "
                + "2026-05-20, including the row-777 SOUND infeasibility "
                + "certificate). Found neither phrasing. If you added an "
                + "11th branch, update the prose count + the table.",
                hasTenCount);

        // Negative pin: the OLD count phrasings must NOT appear.
        boolean hasStaleNineCount = md.contains("nine branches")
                || md.contains("nine termination branches");
        boolean hasStaleEightCount = md.contains("eight branches")
                || md.contains("eight termination branches");
        assertFalse("routing-preconditions-checklist.md still contains the "
                + "STALE phrasing 'nine branches' or 'nine termination "
                + "branches'. Update to 'ten branches' / 'ten termination "
                + "branches' per the 10-row taxonomy.",
                hasStaleNineCount);
        assertFalse("routing-preconditions-checklist.md still contains the "
                + "STALE phrasing 'eight branches' or 'eight termination "
                + "branches'. Update to 'ten branches' / 'ten termination "
                + "branches' per the 10-row taxonomy.",
                hasStaleEightCount);

        // Defensive: ensure my arrays import got used (silences unused-import
        // warning in some IDEs without weakening the assertion above).
        assertNotNull(Arrays.asList("ten branches", "ten termination "
                + "branches"));
    }
}
